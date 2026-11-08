package xin.dshaa.core.tools.shell

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import xin.dshaa.core.jobs.JobOutcome
import xin.dshaa.core.jobs.JobStatus
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult
import xin.dshaa.core.tools.resolvePath
import java.io.File
import java.io.Reader

private fun JsonObject.str(key: String): String? = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
private fun JsonObject.int(key: String): Int? = runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()
private fun JsonObject.bool(key: String): Boolean? = runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()

/**
 * `bash` 工具（Android 用 toybox `sh -c`；桌面 JVM 测试用 cmd / sh）。
 *
 * 前台：输出取尾部 30k 字符，非零退出码追加 `[exit code: N]`（与上游一致）。
 * 后台（`run_in_background: true`）：进程登记进 `ctx.jobs`，立刻返回 `started background job <id>`；
 * 输出按 stdout/stderr 分流进环形缓冲，模型用 `job_output` 消费、`job_kill` 取消。
 */
class BashTool : DshTool {
    override val name = "bash"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val command = args.str("command") ?: return ToolResult.err("missing command")
        val timeoutMs = (args.int("timeoutMs")?.toLong() ?: ctx.timeoutMs).coerceIn(1_000, 600_000)
        val workdir = args.str("workdir")?.let { resolvePath(it, ctx) } ?: ctx.cwd
        val shell = shellFor(command)
        if (args.bool("run_in_background") == true) {
            return startBackground(command, shell, workdir, ctx)
        }
        val process = runCatching {
            ProcessBuilder(shell)
                .directory(workdir)
                .redirectErrorStream(true)
                .start()
        }.getOrElse { return ToolResult.err("spawn failed: ${it.message}") }

        val bytes = try {
            withTimeoutOrNull(timeoutMs) { readCancellable(process) }
        } catch (e: CancellationException) {
            // 中断（用户 stop / 子代理 interrupt / 切会话）：先杀进程，别让长命令把取消拖到它自己退出
            runCatching { process.destroyForcibly() }
            throw e
        }
        if (bytes == null) {
            process.destroyForcibly()
            return ToolResult.err("command timed out after ${timeoutMs}ms")
        }
        val exit = runCatching { process.waitFor() }.getOrDefault(-1)
        var text = bytes.toString(Charsets.UTF_8)
        if (text.length > 30_000) {
            text = "…(output truncated to tail)\n" + text.takeLast(30_000)
        }
        if (exit != 0) {
            text += (if (text.endsWith("\n") || text.isEmpty()) "" else "\n") + "[exit code: $exit]"
        }
        return ToolResult.ok(text.ifEmpty { "(no output)" })
    }

    /** 后台执行：登记 job 后立刻返回 id（无超时；取消走 job_kill）。 */
    private fun startBackground(command: String, shell: List<String>, workdir: File, ctx: ToolContext): ToolResult {
        val jobs = ctx.jobs
            ?: return ToolResult.err("background jobs unavailable: load the job runtime in this composition")
        val snapshot = jobs.start(
            kind = "bash",
            label = command,
            owner = ctx.jobOwner(),
        ) { control ->
            val process = try {
                ProcessBuilder(shell)
                    .directory(workdir)
                    .redirectErrorStream(false)
                    .start()
            } catch (e: Exception) {
                return@start JobOutcome(JobStatus.FAILED, "spawn failed: ${e.message}")
            }
            control.onCancel { runCatching { process.destroyForcibly() } }
            if (control.isCancelled()) process.destroyForcibly()
            val exit = coroutineScope {
                val out = launch { pump(process.inputStream.reader(Charsets.UTF_8), { control.isCancelled() }) { control.stdout(it) } }
                val err = launch { pump(process.errorStream.reader(Charsets.UTF_8), { control.isCancelled() }) { control.stderr(it) } }
                val code = withContext(Dispatchers.IO) { runCatching { process.waitFor() }.getOrDefault(-1) }
                out.join()
                err.join()
                code
            }
            if (control.isCancelled()) {
                JobOutcome(JobStatus.KILLED, "killed before exit")
            } else {
                JobOutcome(JobStatus.COMPLETED, "exit code: $exit")
            }
        }
        return ToolResult.ok("started background job ${snapshot.id}", meta = mapOf("jobId" to snapshot.id))
    }

    /**
     * 读进程 stdout 直到 EOF；协程被取消时**立刻** destroy 进程。
     *
     * 为什么不用 `withContext(Dispatchers.IO) { readBytes() }`：阻塞读不响应协程取消，
     * 前台 `bash sleep 180` 会把 stop/interrupt 拖到命令自己结束（契约 5 的中断语义就废了）。
     * 这里用可取消挂起 + 看门线程，取消时 destroyForcibly → 读端 EOF → 立刻抛 CancellationException。
     */
    private suspend fun readCancellable(process: Process): ByteArray =
        suspendCancellableCoroutine { cont ->
            val reader = Thread({
                try {
                    cont.resumeWith(Result.success(process.inputStream.use { it.readBytes() }))
                } catch (t: Throwable) {
                    cont.resumeWith(Result.failure(t))
                }
            }, "dsh-bash-read").apply {
                isDaemon = true
                start()
            }
            cont.invokeOnCancellation { _ ->
                runCatching { process.destroyForcibly() }
                reader.interrupt()
            }
        }

    private suspend fun pump(
        reader: Reader,
        isCancelled: () -> Boolean,
        sink: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            reader.use { input ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) sink(String(buffer, 0, read))
                }
            }
        } catch (e: java.io.IOException) {
            // destroyForcibly 后管道读可能抛 "Stream closed" 而不是 EOF；取消语义下按 EOF 处理，
            // 否则 job 会以 failed 结算（M20 任务面板验收时抓到）。
            if (!isCancelled()) throw e
        }
    }

    private fun shellFor(command: String): List<String> = when {
        System.getProperty("os.name").orEmpty().lowercase().contains("win") -> listOf("cmd.exe", "/c", command)
        File("/system/bin/sh").exists() -> listOf("/system/bin/sh", "-c", command)
        else -> listOf("/bin/sh", "-c", command)
    }
}
