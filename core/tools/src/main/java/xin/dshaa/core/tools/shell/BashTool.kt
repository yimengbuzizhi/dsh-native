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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import xin.dshaa.core.jobs.JobOutcome
import xin.dshaa.core.jobs.JobStatus
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ElevatedRequest
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult
import xin.dshaa.core.tools.resolvePath
import java.io.File
import java.io.InputStream
import java.io.Reader
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private fun JsonObject.str(key: String): String? = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
private fun JsonObject.longNum(key: String): Long? = runCatching {
    val p = this[key]?.jsonPrimitive ?: return@runCatching null
    p.intOrNull?.toLong()
        ?: p.doubleOrNull?.toLong()
        ?: p.contentOrNull?.trim()?.toDoubleOrNull()?.toLong()
}.getOrNull()
private fun JsonObject.bool(key: String): Boolean? = runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()

/** 结果最大字符数（超出取尾部；完整输出落盘，路径写在结果第一行）。 */
private const val MAX_OUTPUT_CHARS = 30_000

/** 落盘/内存缓冲的完整输出最多保留多少字节（环形尾缓冲，防止大输出 OOM）。 */
private const val MAX_LOG_BYTES = 4 * 1024 * 1024

/** 日志目录最多保留的历史日志数，防止越积越多。 */
private const val MAX_LOG_FILES = 20

/** 工作区里的「自有工具箱」目录：存在就前置进 PATH。 */
private const val WORKSPACE_BIN_DIR = ".dsh/bin"

/** `bash.identity` 的默认值（App uid，走打包 bash / 系统 sh）。 */
private const val IDENTITY_APP = "app"

/** `bash.identity` 的合法值（docs/198 §二）：app（默认）/ shell（Android uid 2000）/ root（su）。 */
private val IDENTITIES = setOf(IDENTITY_APP, "shell", "root")

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
 *
 * 2026-11-28（docs/198 §二）`identity`：`app`（默认）/ `shell`（Shizuku 的 Android uid 2000）/
 * `root`（`su`）。AI 跑 `pm` / `settings` / `am` / `dumpsys` / `input` / `cmd` 时不用再手工套 `rish`；
 * 提权通道由 [ShellHost.runElevated] 提供（Android 端 = `DeviceShell` 的 root → Shizuku → App 链）。
 */
class BashTool : DshTool {
    override val name = "bash"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val command = args.str("command") ?: return ToolResult.err("missing command")
        // `description` 是模型侧的必填人话说明（工具卡副行显示），执行端不参与，这里读一次只为
        // 不被误当成未知参数丢掉（null 也允许：老会话/子代理可能不带）。
        args.str("description")
        val timeoutMs = (args.longNum("timeoutMs") ?: ctx.timeoutMs).coerceIn(1_000, 600_000)
        val workdir = args.str("workdir")?.let { resolvePath(it, ctx) } ?: ctx.cwd
        if (!workdir.isDirectory) {
            return ToolResult.err("workdir is not an existing directory: ${workdir.absolutePath}")
        }
        val extraEnv = envArgs(args) ?: return ToolResult.err("env must be an object of string values")
        val stdin = args.str("stdin")
        val identity = args.str("identity")?.trim()?.lowercase().orEmpty().ifEmpty { IDENTITY_APP }
        if (identity !in IDENTITIES) {
            return ToolResult.err("identity must be one of: app (default), shell, root — got: $identity")
        }
        if (identity != IDENTITY_APP) {
            // 提权通道一次性执行整条命令（不保留 cd/变量），后台 job 没有对应实现。
            if (args.bool("run_in_background") == true) {
                return ToolResult.err("identity=$identity does not support run_in_background; run it in the foreground instead.")
            }
            return runElevated(command, identity, workdir, timeoutMs, extraEnv, stdin, ctx)
        }
        // 冷启动竞态防护：等待工具链准备完成；失败则静默退回 /system/bin/sh
        runCatching { ctx.shell?.prepare() }
        val shell = shellFor(command, ctx)
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

        val tailBuffer = TailByteBuffer(MAX_LOG_BYTES)
        val outcome = try {
            withTimeoutOrNull(timeoutMs) {
                readAndWaitCancellable(process, tailBuffer)
            }
        } catch (e: CancellationException) {
            killProcessTree(process)
            throw e
        }
        if (outcome == null) {
            killProcessTree(process)
            val partial = decodeUtf8Safely(tailBuffer.snapshot()).trimEnd()
            val msg = if (partial.isEmpty()) {
                "command timed out after ${timeoutMs}ms"
            } else {
                val snippet = if (partial.length > 4_000) "…\n" + partial.takeLast(4_000) else partial
                "command timed out after ${timeoutMs}ms\n[partial output before timeout]\n$snippet"
            }
            return ToolResult.err(msg)
        }
        val (bytes, exit) = outcome
        var text = decodeUtf8Safely(bytes)
        if (text.length > MAX_OUTPUT_CHARS || tailBuffer.truncated) {
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
     * `identity=shell/root`：整条命令交给宿主的一次性提权通道（Shizuku / `su`），
     * 输出仍按 `bash` 的老规矩处理（尾部截断、超时带部分输出、`[exit code: N]`、大输出落盘）。
     *
     * 与 app 身份的区别（都会写进结果首行，避免模型误判当前身份）：
     * - 不经过打包 bash，PATH/环境按系统 shell 重建（app 私有的 toolbox 路径系统 shell 读不到）；
     * - `cwd` 若在 app 私有目录里，宿主会改用 `/data/local/tmp` 并在 `note` 里说明；
     * - 失败（没 root / Shizuku 未授权）返回可执行的提示，不是裸的 `Permission denied`。
     */
    private suspend fun runElevated(
        command: String,
        identity: String,
        workdir: File,
        timeoutMs: Long,
        extraEnv: Map<String, String>,
        stdin: String?,
        ctx: ToolContext,
    ): ToolResult {
        val host = ctx.shell ?: return ToolResult.err(
            "identity=$identity is unavailable: this session has no Android shell host. " +
                "Omit identity (app uid) or run inside the DSH Android app.",
        )
        if (!host.supportsElevatedIdentity()) {
            return ToolResult.err(
                "identity=$identity is unavailable in this deployment (no Shizuku/su channel); use the default app identity.",
            )
        }
        val result = try {
            host.runElevated(
                ElevatedRequest(
                    command = command,
                    identity = identity,
                    cwd = workdir,
                    env = elevatedEnvironment(ctx, workdir, timeoutMs, extraEnv),
                    timeoutMs = timeoutMs,
                    stdin = stdin,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult.err("identity=$identity failed to start: ${e.message ?: e.javaClass.simpleName}")
        }
        val error = result.error
        if (!error.isNullOrBlank()) return ToolResult.err(error)
        val header = "identity: ${result.identity}"
        val meta = mapOf(
            "identity" to result.identity,
            "exit_code" to result.exitCode.toString(),
            "timed_out" to result.timedOut.toString(),
        )
        var text = result.output.trimEnd()
        if (result.timedOut) {
            return ToolResult.err(
                buildString {
                    appendLine(header)
                    appendLine("command timed out after ${timeoutMs}ms")
                    result.note?.let { appendLine("note: $it") }
                    if (text.isNotEmpty()) {
                        appendLine("[partial output before timeout]")
                        append(text.takeLast(4_000))
                    }
                }.trimEnd(),
                meta = meta,
            )
        }
        if (result.truncated || text.length > MAX_OUTPUT_CHARS) {
            val logPath = saveElevatedOutput(ctx, text)
            val note = if (logPath != null) "output tail saved: $logPath" else "output tail not saved"
            text = "…(output truncated to tail; $note)\n" + text.takeLast(MAX_OUTPUT_CHARS)
        }
        val body = buildString {
            appendLine(header)
            result.note?.let { appendLine("note: $it") }
            append(text)
            if (result.exitCode != 0) {
                if (isNotEmpty() && !endsWith("\n")) append('\n')
                append("[exit code: ${result.exitCode}]")
            }
        }.trimEnd()
        return ToolResult.ok(body, meta = meta)
    }

    /**
     * 提权命令的环境：系统 shell 的 PATH/HOME，加上托管 `DSH_*` 与模型给的 `env`。
     *
     * 不铺打包工具箱的 `LD_LIBRARY_PATH`/CA/`BASH_ENV`：那些是 app 私有路径，shell/root 身份读不到。
     */
    private fun elevatedEnvironment(
        ctx: ToolContext,
        workdir: File,
        timeoutMs: Long,
        extraEnv: Map<String, String>,
    ): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        env["PATH"] = "/system/bin:/system/xbin:/product/bin"
        env["HOME"] = "/data/local/tmp"
        env["TMPDIR"] = "/data/local/tmp"
        env["LANG"] = "C.UTF-8"
        env["DSH_WORKSPACE"] = runCatching { ctx.workspace.absolutePath }.getOrDefault("")
        env["DSH_CWD"] = runCatching { workdir.absolutePath }.getOrDefault("")
        env["DSH_TIMEOUT_MS"] = timeoutMs.toString()
        ctx.jobOwner()?.let { env["DSH_SESSION"] = it }
        extraEnv.forEach { (k, v) -> env[k] = v }
        return env
    }

    /** 提权输出落盘（复用 app 身份的滚动日志目录）。 */
    private fun saveElevatedOutput(ctx: ToolContext, text: String): String? =
        saveFullOutput(ctx, text.toByteArray(Charsets.UTF_8))

    /**
     * 环境变量：托管 `DSH_*` + 工作区工具箱 PATH 前缀 + 模型给的 `env`（最后写入，可覆盖托管值）。
     *
     * 后台与前台共用，保证 `run_in_background` 的进程看到同样的环境。
     */
    private fun applyEnvironment(        builder: ProcessBuilder,
        ctx: ToolContext,
        workdir: File,
        timeoutMs: Long?,
        extraEnv: Map<String, String>,
    ) {
        val env = builder.environment()
        // 打包工具箱的 PATH/HOME/CA/LD_LIBRARY_PATH 先铺底，工作区工具箱与模型给的 env 后覆盖。
        ctx.shell?.environment()?.forEach { (key, value) -> env[key] = value }
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
     * 完整输出落盘：优先写 `<workspace>/.dsh/bash-logs/`（确保模型随后用 `read` 工具可读，不会触发
     * `path escapes workspace`），不可写再退回系统临时目录；带 UUID 防并发冲突并滚动清理旧日志。
     */
    private fun saveFullOutput(ctx: ToolContext, bytes: ByteArray): String? {
        val dirs = listOfNotNull(
            runCatching { File(ctx.workspace, ".dsh/bash-logs") }.getOrNull(),
            System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() }?.let { File(it, "dsh-bash-logs") },
        )
        val payload = utf8SafeTail(bytes, MAX_LOG_BYTES)
        val suffix = UUID.randomUUID().toString().take(8)
        val fileName = "bash-${System.currentTimeMillis()}-$suffix.log"
        for (dir in dirs) {
            val target = runCatching {
                if (!dir.isDirectory && !dir.mkdirs()) null else File(dir, fileName)
            }.getOrNull() ?: continue
            val written = runCatching { target.writeBytes(payload) }.isSuccess
            if (written) {
                pruneOldLogs(dir)
                return runCatching { target.absolutePath }.getOrNull()
            }
        }
        return null
    }

    private fun pruneOldLogs(dir: File) {
        runCatching {
            val logs = dir.listFiles { f -> f.isFile && f.name.startsWith("bash-") && f.name.endsWith(".log") }
                ?: return
            if (logs.size > MAX_LOG_FILES) {
                logs.sortedBy { it.lastModified() }
                    .take(logs.size - MAX_LOG_FILES)
                    .forEach { it.delete() }
            }
        }
    }

    /** 截断字节尾部时跳过开头残留的 UTF-8 continuation bytes (10xxxxxx)，避免首字符乱码。 */
    private fun utf8SafeTail(bytes: ByteArray, limit: Int): ByteArray {
        if (bytes.size <= limit) return bytes
        var start = bytes.size - limit
        while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) {
            start++
        }
        return bytes.copyOfRange(start, bytes.size)
    }

    private fun decodeUtf8Safely(bytes: ByteArray): String {
        var start = 0
        while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) start++
        return if (start == 0) bytes.toString(Charsets.UTF_8) else String(bytes, start, bytes.size - start, Charsets.UTF_8)
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
            control.onCancel { killProcessTree(process) }
            if (control.isCancelled()) killProcessTree(process)
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
     * 有界环形尾字节缓冲：流式吸收进程输出，最多占 [capacity] 字节内存，彻底消除大输出 OOM，
     * 并允许超时后读取已经收集到的部分输出。
     */
    private class TailByteBuffer(private val capacity: Int) {
        private val chunks = ArrayDeque<ByteArray>()
        private var total = 0
        @Volatile
        var truncated: Boolean = false
            private set

        @Synchronized
        fun append(data: ByteArray, len: Int) {
            if (len <= 0) return
            val copy = data.copyOf(len)
            chunks.addLast(copy)
            total += len
            while (total > capacity && chunks.size > 1) {
                val dropped = chunks.removeFirst()
                total -= dropped.size
                truncated = true
            }
            if (total > capacity && chunks.size == 1) {
                val only = chunks.removeFirst()
                val kept = only.copyOfRange(only.size - capacity, only.size)
                chunks.addLast(kept)
                total = kept.size
                truncated = true
            }
        }

        @Synchronized
        fun snapshot(): ByteArray {
            val out = ByteArray(total)
            var pos = 0
            for (chunk in chunks) {
                System.arraycopy(chunk, 0, out, pos, chunk.size)
                pos += chunk.size
            }
            return out
        }
    }

    /**
     * 流式读进程输出 + 等待进程退出（两者同在超时与取消保护之内，防止早关 stdout 导致 waitFor 永久挂起）。
     */
    private suspend fun readAndWaitCancellable(
        process: Process,
        tailBuffer: TailByteBuffer,
    ): Pair<ByteArray, Int> =
        suspendCancellableCoroutine { cont ->
            val reader = Thread({
                try {
                    process.inputStream.use { input ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n > 0) tailBuffer.append(buf, n)
                        }
                    }
                    val exit = process.waitFor()
                    cont.resumeWith(Result.success(tailBuffer.snapshot() to exit))
                } catch (t: Throwable) {
                    cont.resumeWith(Result.failure(t))
                }
            }, "dsh-bash-read").apply {
                isDaemon = true
                start()
            }
            cont.invokeOnCancellation { _ ->
                killProcessTree(process)
                reader.interrupt()
            }
        }

    /** 清理子进程及其后代进程树，防止超时/取消后留下孤儿 sleep/curl 进程或占住管道。 */
    private fun killProcessTree(process: Process) {
        val pid = processPid(process)
        val descendants = if (pid > 0) findProcDescendants(pid) else emptyList()
        runCatching { process.destroyForcibly() }
        // Linux/Android 上通过 /proc 扫出的后代进程逐个 SIGKILL
        if (descendants.isNotEmpty() && File("/proc").isDirectory) {
            descendants.reversed().forEach { childPid ->
                runCatching {
                    ProcessBuilder("/system/bin/kill", "-9", childPid.toString()).start()
                        .waitFor(200, TimeUnit.MILLISECONDS)
                }.onFailure {
                    runCatching {
                        ProcessBuilder("kill", "-9", childPid.toString()).start()
                            .waitFor(200, TimeUnit.MILLISECONDS)
                    }
                }
            }
        }
    }

    private fun processPid(proc: Process): Int {
        runCatching {
            val field = proc.javaClass.getDeclaredField("pid").apply { isAccessible = true }
            val v = field.getInt(proc)
            if (v > 0) return v
        }
        runCatching {
            val method = Process::class.java.getMethod("pid").apply { isAccessible = true }
            val v = (method.invoke(proc) as? Number)?.toInt() ?: -1
            if (v > 0) return v
        }
        return -1
    }

    private fun findProcDescendants(rootPid: Int): List<Int> {
        val procDir = File("/proc")
        if (!procDir.isDirectory) return emptyList()
        val children = HashMap<Int, MutableList<Int>>()
        procDir.listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@forEach
            val close = stat.lastIndexOf(')')
            if (close < 0 || close + 2 >= stat.length) return@forEach
            val fields = stat.substring(close + 2).split(' ')
            val ppid = fields.getOrNull(1)?.toIntOrNull() ?: return@forEach
            children.getOrPut(ppid) { ArrayList() } += pid
        }
        val out = ArrayList<Int>()
        val queue = ArrayDeque(children[rootPid].orEmpty())
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            out += pid
            children[pid]?.let { queue.addAll(it) }
        }
        return out
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
            if (!isCancelled()) throw e
        }
    }

    private fun shellFor(command: String, ctx: ToolContext): List<String> {
        // 仅在打包工具箱确实就绪时使用 bundled bash，否则回退 /system/bin/sh，避免冷启动/损坏时报 CANNOT LINK EXECUTABLE
        val host = ctx.shell
        if (host != null && host.isReady()) {
            host.bashPath()?.let { bashPath ->
                return listOf(bashPath, "--noprofile", "--norc", "-c", command)
            }
        }
        return when {
            System.getProperty("os.name").orEmpty().lowercase().contains("win") -> listOf("cmd.exe", "/c", command)
            File("/system/bin/sh").exists() -> listOf("/system/bin/sh", "-c", command)
            else -> listOf("/bin/sh", "-c", command)
        }
    }
}
