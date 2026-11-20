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

/** 结果最大字符数（超出取尾部；完整输出落盘，路径写在结果第一行）。 */
private const val MAX_OUTPUT_CHARS = 30_000

/** 落盘的完整输出最多保留多少字节（再大就只留尾部，避免把手机存储写满）。 */
private const val MAX_LOG_BYTES = 4 * 1024 * 1024

/** 工作区里的「自有工具箱」目录：存在就前置进 PATH（用户/模型往里丢静态 busybox、curl 等）。 */
private const val WORKSPACE_BIN_DIR = ".dsh/bin"

/**
 * `bash` 工具（Android 用 toybox `sh -c`；桌面 JVM 测试用 cmd / sh）。
 *
 * 前台：输出取尾部 30k 字符（完整输出落盘并报路径），非零退出码追加 `[exit code: N]`（与上游一致）。
 * 后台（`run_in_background: true`）：进程登记进 `ctx.jobs`，立刻返回 `started background job <id>`；
 * 输出按 stdout/stderr 分流进环形缓冲，模型用 `job_output` 消费、`job_kill` 取消。
 *
 * 2026-11-20 能力补全（用户反馈「bash 工具箱有点不全」）：
 * - `description`：模型必填的人话说明（UI 展示用，执行端不参与）——以前完全没读；
 * - `env`：给这条命令加环境变量；`stdin`：喂标准输入（不给就立刻 EOF，避免 `cat` 之类的命令把管道读挂）；
 * - 托管变量 `DSH_WORKSPACE` / `DSH_CWD` / `DSH_TIMEOUT_MS` / `DSH_SESSION`（prompt 里承诺过，以前没实现）；
 * - `<workspace>/.dsh/bin` 存在时前置进 `PATH`（手机上没有 bash/curl/busybox 时的补位口）；
 * - 截断时把完整输出写进 `<tmp>/dsh-bash-logs/`（写不进去就退到 `<workspace>/.dsh/bash-logs/`），
 *   并把路径写进结果第一行（prompt 里承诺过「full output is saved to a file whose path is reported」）；
 * - `sandbox_permissions` / `justification`：Android 没有分级沙箱（越界路径直接 `path escapes workspace`），
 *   两个字段按上游 schema 保留但**故意忽略**，不假装提权成功。
 */
class BashTool : DshTool {
    override val name = "bash"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val command = args.str("command") ?: return ToolResult.err("missing command")
        // `description` 是模型侧的必填人话说明（工具卡副行显示），执行端不参与，这里读一次只为
        // 不被误当成未知参数丢掉（null 也允许：老会话/子代理可能不带）。
        args.str("description")
        val timeoutMs = (args.int("timeoutMs")?.toLong() ?: ctx.timeoutMs).coerceIn(1_000, 600_000)
        val workdir = args.str("workdir")?.let { resolvePath(it, ctx) } ?: ctx.cwd
        val extraEnv = envArgs(args) ?: return ToolResult.err("env must be an object of string values")
        val stdin = args.str("stdin")
        val shell = shellFor(command)
        if (args.bool("run_in_background") == true) {
            if (stdin != null) {
                return ToolResult.err("stdin is not supported with run_in_background: the process outlives this call")
            }
            return startBackground(command, shell, workdir, ctx, extraEnv)
        }
        val builder = ProcessBuilder(shell)
            .directory(workdir)
            .redirectErrorStream(true)
        applyEnvironment(builder, ctx, workdir, timeoutMs, extraEnv)
        val process = runCatching { builder.start() }
            .getOrElse { return ToolResult.err("spawn failed: ${it.message}") }

        // 不给 stdin 也要把管道关上：否则 `cat` / `grep` 这类命令会一直等输入直到超时。
        if (stdin != null) feedStdin(process, stdin) else runCatching { process.outputStream.close() }

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
        if (text.length > MAX_OUTPUT_CHARS) {
            val logPath = saveFullOutput(ctx, bytes)
            val note = if (logPath != null) "full output: $logPath" else "full output not saved"
            text = "…(output truncated to tail; $note)\n" + text.takeLast(MAX_OUTPUT_CHARS)
        }
        if (exit != 0) {
            text += (if (text.endsWith("\n") || text.isEmpty()) "" else "\n") + "[exit code: $exit]"
        }
        return ToolResult.ok(text.ifEmpty { "(no output)" })
    }

    /**
     * 环境变量：托管 `DSH_*` + 工作区工具箱 PATH 前缀 + 模型给的 `env`（最后写入，可覆盖托管值）。
     *
     * 后台与前台共用，保证 `run_in_background` 的进程看到同样的环境。
     */
    private fun applyEnvironment(
        builder: ProcessBuilder,
        ctx: ToolContext,
        workdir: File,
        timeoutMs: Long?,
        extraEnv: Map<String, String>,
    ) {
        val env = builder.environment()
        val binDir = runCatching { File(ctx.workspace, WORKSPACE_BIN_DIR) }.getOrNull()
        if (binDir != null && binDir.isDirectory) {
            val pathKey = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
            val current = env[pathKey].orEmpty()
            env[pathKey] = if (current.isEmpty()) {
                binDir.absolutePath
            } else {
                binDir.absolutePath + File.pathSeparator + current
            }
        }
        env["DSH_WORKSPACE"] = runCatching { ctx.workspace.absolutePath }.getOrDefault("")
        env["DSH_CWD"] = runCatching { workdir.absolutePath }.getOrDefault("")
        env["DSH_TIMEOUT_MS"] = timeoutMs?.toString() ?: ""
        ctx.jobOwner()?.let { env["DSH_SESSION"] = it }
        extraEnv.forEach { (k, v) -> env[k] = v }
    }

    /** 解析 `env`：必须是 `{key: string}`；键里有 `=` 或 NUL 是非法的（ProcessBuilder 也会拒绝）。 */
    private fun envArgs(args: JsonObject): Map<String, String>? {
        val raw = args["env"] ?: return emptyMap()
        if (raw !is JsonObject) return null
        val out = LinkedHashMap<String, String>()
        for ((key, value) in raw) {
            val text = runCatching { value.jsonPrimitive.contentOrNull }.getOrNull() ?: return null
            if (key.isEmpty() || key.any { it == '=' || it == '\u0000' }) return null
            out[key] = text
        }
        return out
    }

    /** 后台写 stdin（不阻塞调用协程）；写完关流让子进程看到 EOF。 */
    private fun feedStdin(process: Process, data: String) {
        Thread({
            runCatching {
                process.outputStream.use { it.write(data.toByteArray(Charsets.UTF_8)) }
            }
        }, "dsh-bash-stdin").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 完整输出落盘：优先系统临时目录（App 缓存），不可写退到 `<workspace>/.dsh/bash-logs/`。
     * 超过 [MAX_LOG_BYTES] 只留尾部（与「truncated to tail」的语义一致）。
     */
    private fun saveFullOutput(ctx: ToolContext, bytes: ByteArray): String? {
        val dirs = listOfNotNull(
            System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() }?.let { File(it, "dsh-bash-logs") },
            runCatching { File(ctx.workspace, ".dsh/bash-logs") }.getOrNull(),
        )
        val payload = if (bytes.size > MAX_LOG_BYTES) {
            bytes.copyOfRange(bytes.size - MAX_LOG_BYTES, bytes.size)
        } else {
            bytes
        }
        val fileName = "bash-${System.currentTimeMillis()}.log"
        for (dir in dirs) {
            val target = runCatching {
                if (!dir.isDirectory && !dir.mkdirs()) null else File(dir, fileName)
            }.getOrNull() ?: continue
            val written = runCatching { target.writeBytes(payload) }.isSuccess
            if (written) return runCatching { target.absolutePath }.getOrNull()
        }
        return null
    }

    /** 后台执行：登记 job 后立刻返回 id（无超时；取消走 job_kill）。 */
    private fun startBackground(
        command: String,
        shell: List<String>,
        workdir: File,
        ctx: ToolContext,
        extraEnv: Map<String, String>,
    ): ToolResult {
        val jobs = ctx.jobs
            ?: return ToolResult.err("background jobs unavailable: load the job runtime in this composition")
        val snapshot = jobs.start(
            kind = "bash",
            label = command,
            owner = ctx.jobOwner(),
        ) { control ->
            val builder = ProcessBuilder(shell)
                .directory(workdir)
                .redirectErrorStream(false)
            applyEnvironment(builder, ctx, workdir, null, extraEnv)
            val process = try {
                builder.start()
            } catch (e: Exception) {
                return@start JobOutcome(JobStatus.FAILED, "spawn failed: ${e.message}")
            }
            // 后台没有 stdin：立刻 EOF，别让命令挂在等输入。
            runCatching { process.outputStream.close() }
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
