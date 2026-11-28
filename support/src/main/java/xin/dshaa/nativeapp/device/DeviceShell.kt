package xin.dshaa.nativeapp.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.ai.assistance.showerclient.ShellCommandResult
import com.ai.assistance.showerclient.ShellIdentity
import com.ai.assistance.showerclient.ShellRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.io.File
import java.io.Reader
import java.util.concurrent.TimeUnit

/**
 * `DeviceShell.runElevatedCommand` 的结果（`bash identity=shell/root` 用）。
 *
 * [identity] 是**实际**身份（`root (uid 0 via su -c id)` / `shell (uid 2000 via Shizuku)` / …），
 * 不假设请求什么就是什么；[error] 非空 = 命令根本没跑起来（没 root / Shizuku 未授权）。
 */
data class ElevatedRun(
    val ok: Boolean,
    val identity: String,
    val exitCode: Int,
    val timedOut: Boolean,
    val output: String,
    val truncated: Boolean,
    val error: String? = null,
    val note: String? = null,
)

/**
 * 设备 shell 后端：给 Shower（虚拟屏）与后续设备工具用。
 *
 * 身份优先级：
 * - `SHELL`（uid 2000，`app_process`/`cp /data/local/tmp` 必需）→ Shizuku；
 * - `ROOT` → `su -c`；
 * - `DEFAULT` → 先 root 再 Shizuku 再 App 自己的 `sh`（pkill 自己的子进程够用）。
 *
 * 没有 Shizuku/root 时所有身份退化成 App `sh`（命令会因权限失败，但不会崩）。
 *
 * root-only 时的 `SHELL`：`su -c` 是 uid 0 / magisk 域，而 shower-server 的 FakeContext
 * 自称 `com.android.shell`（uid 2000），DisplayManagerService 会拒绝建虚拟屏。因此先把
 * Operit 的 shell 身份 launcher（aarch64 ELF，随 jniLibs/arm64-v8a 打包）复制到
 * `filesDir/bin`，再以 `su -c "<launcher> <原命令>"` 执行，由 launcher 降权到 uid 2000 +
 * `u:r:shell:s0`。launcher 不可用（架构不符/未打包）时退回旧的 uid 0 路径，并在日志与
 * 命令 stderr 里写明真实原因，不静默。
 */
object DeviceShell : ShellRunner {

    private const val TAG = "DeviceShell"

    /** 提权命令最多留在内存里的输出字符数（`dumpsys` 之类可能几十 MB）。 */
    private const val ELEVATED_MAX_OUTPUT_CHARS = 300_000

    /** `bash identity=shell` 不可用时的文案：告诉模型/用户下一步做什么，而不是裸 Permission denied。 */
    private const val SHELL_UNAVAILABLE_REASON =
        "identity=shell is unavailable: Shizuku is not running or this app is not authorized. " +
            "Grant it once in DSH → Settings → Permissions & Capabilities → Shizuku (the grant is persistent), then retry. " +
            "identity=root needs a rooted device; the default app identity needs no grant."

    /** `bash identity=root` 不可用时的文案。 */
    private const val ROOT_UNAVAILABLE_REASON =
        "identity=root is unavailable: no su/root on this device, or the su grant was denied. " +
            "Use identity=shell (Shizuku) or the default app identity."

    /** 单次 `su` 探测超时：Magisk 首次会弹授权框，给用户 12 秒点。 */
    private const val ROOT_PROBE_TIMEOUT_SECONDS = 12L

    @Volatile
    var shizukuReady: Boolean = false
        private set

    /** 缓存里同时记时间：root 不可用/等待授权时要能按间隔重探（不再永久缓存 false）。 */
    private data class CachedRootProbe(val result: RootProbeResult, val atMs: Long)

    @Volatile
    private var rootProbeCache: CachedRootProbe? = null

    private val rootProbeLock = Any()

    /** App context：安装 launcher 需要（[initIdentityLauncher] 注入）。 */
    @Volatile
    private var appContext: Context? = null

    private val launcherLock = Any()

    @Volatile
    private var launcherState: LauncherStatus? = null

    /** 探测 root（首次会阻塞；UI 用 [shellStatus]/[primeRootProbe] 别在主线程序列化等）。 */
    fun probeRoot(force: Boolean = false): RootProbeResult {
        val cached = rootProbeCache
        if (!force && cached != null && !RootProbe.shouldReprobe(cached.result, ageMs(cached))) {
            return cached.result
        }
        synchronized(rootProbeLock) {
            val again = rootProbeCache
            if (!force && again != null && !RootProbe.shouldReprobe(again.result, ageMs(again))) {
                return again.result
            }
            val result = RootProbe.probe(::runSuProbe)
            rootProbeCache = CachedRootProbe(result, System.currentTimeMillis())
            Log.i(TAG, "root probe available=${result.available} command=${result.command} waiting=${result.waiting}")
            return result
        }
    }

    private fun ageMs(cached: CachedRootProbe): Long =
        (System.currentTimeMillis() - cached.atMs).coerceAtLeast(0L)

    /** 非阻塞读缓存：null = 还没探测过。 */
    fun rootAvailableCached(): Boolean? = rootProbeCache?.result?.available

    fun hasRoot(): Boolean = probeRoot().available

    /** App 启动时后台探一次，避免首次进能力页/终端时主线程卡在 su 上。 */
    fun primeRootProbe() {
        if (rootProbeCache != null) return
        Thread({ runCatching { probeRoot() } }, "root-probe").apply {
            isDaemon = true
            start()
        }
    }

    /** H7：设置 →「权限与能力」的 Shell 身份行。 */
    fun shellStatus(): ShellStatus {
        val probe = probeRoot()
        val binderReady = shizukuReady
        val permission = hasShizukuPermission()
        return RootProbe.status(
            root = probe.available,
            rootCommand = probe.command,
            shizuku = binderReady,
            shizukuPermission = permission,
            rootReason = probe.reason,
        )
    }

    /**
     * 记录 App context 并预装 shell 身份 launcher（`DshApplication.onCreate` 调一次）。
     *
     * 复制只有几十 KB，但仍在后台线程做，避免和启动关键路径抢磁盘；真正取用时
     * [launcherStatus] 会同步补装一次（幂等）。
     */
    fun initIdentityLauncher(context: Context) {
        appContext = context.applicationContext
        Thread({ runCatching { launcherStatus() } }, "shell-launcher").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 安装/复用 shell 身份 launcher（aarch64 ELF → `filesDir/bin/operit_shell_exec`）。
     *
     * 不可用时 [LauncherStatus.reason] 说明真实原因（ABI 不符 / APK 未带 / ELF 架构不对 /
     * 复制失败），供日志和虚拟屏错误文案使用；SHELL 身份会退回 uid 0。
     */
    fun launcherStatus(): LauncherStatus {
        launcherState?.let { return it }
        synchronized(launcherLock) {
            launcherState?.let { return it }
            val status = installLauncher()
            launcherState = status
            if (status.available) {
                Log.i(TAG, "shell identity launcher ready: ${status.path}")
            } else {
                Log.w(TAG, "shell identity launcher unavailable: ${status.reason}")
            }
            return status
        }
    }

    private fun installLauncher(): LauncherStatus {
        val context = appContext
            ?: return LauncherStatus.missing("应用上下文未初始化：DeviceShell.initIdentityLauncher 未调用")
        val abis = Build.SUPPORTED_ABIS.toList()
        if (!ShellIdentityLauncher.isSupportedAbi(abis)) {
            return LauncherStatus.missing(
                "当前 ABI=${abis.firstOrNull() ?: "unknown"}，launcher 只有 ${ShellIdentityLauncher.ARM64_ABI} 版本",
            )
        }
        val source = ShellIdentityLauncher.sourceFile(File(context.applicationInfo.nativeLibraryDir))
        if (!source.isFile) {
            return LauncherStatus.missing(
                "APK 未携带 launcher（${ShellIdentityLauncher.ARM64_ABI} 包内缺少 ${ShellIdentityLauncher.LIB_NAME}）",
            )
        }
        val header = runCatching {
            val buffer = ByteArray(20)
            source.inputStream().use { input ->
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n <= 0) break
                    read += n
                }
            }
            buffer
        }.getOrElse {
            return LauncherStatus.missing("读取 launcher 失败：${it.message ?: it.javaClass.simpleName}")
        }
        if (!ShellIdentityLauncher.isAarch64Executable(header)) {
            return LauncherStatus.missing("launcher ELF 不是 AArch64：${source.absolutePath}")
        }
        val dir = ShellIdentityLauncher.installedFile(context.filesDir).parentFile
            ?: return LauncherStatus.missing("无法解析 launcher 安装目录：${context.filesDir}")
        if (!dir.isDirectory && !dir.mkdirs()) {
            return LauncherStatus.missing("创建 launcher 目录失败：${dir.absolutePath}")
        }
        val target = ShellIdentityLauncher.installedFile(context.filesDir)
        if (!target.isFile || target.length() != source.length()) {
            runCatching {
                source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            }.onFailure {
                return LauncherStatus.missing("复制 launcher 失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
        if (target.length() != source.length()) {
            return LauncherStatus.missing(
                "launcher 复制后长度不一致（源 ${source.length()}B / 目标 ${target.length()}B）：${target.absolutePath}",
            )
        }
        if (!target.setExecutable(true, false)) {
            return LauncherStatus.missing("launcher chmod 失败：${target.absolutePath}")
        }
        return LauncherStatus.ready(target.absolutePath)
    }

    /**
     * 「为什么没有 shell 身份 / 为什么 root 也可能失败」的完整文案：
     * root 探测结果、Shizuku 状态、root 下 launcher 是否可用（架构/安装原因）都写出来。
     */
    fun shellChannelReason(): String {
        val probe = probeRoot()
        val launcher = launcherStatus()
        return ShellDiagnosis.channelReason(
            root = probe.available,
            rootCommand = probe.command,
            rootReason = probe.reason,
            shizuku = shizukuReady,
            shizukuPermission = hasShizukuPermission(),
            launcherAvailable = launcher.available,
            launcherReason = launcher.reason,
        )
    }

    /** 检测一次的进程执行器：stdout/stderr 分读，超时立刻销毁（授权弹窗会走这里）。 */
    private fun runSuProbe(argv: List<String>): SuOutput? = runCatching {
        val process = ProcessBuilder(argv).redirectErrorStream(false).start()
        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = Thread {
            runCatching { out.append(process.inputStream.bufferedReader().readText()) }
        }.apply { isDaemon = true; start() }
        val errThread = Thread {
            runCatching { err.append(process.errorStream.bufferedReader().readText()) }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(ROOT_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        outThread.join(1_500)
        errThread.join(1_500)
        SuOutput(
            stdout = out.toString(),
            stderr = err.toString(),
            exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1,
            timedOut = !finished,
        )
    }.getOrNull()

    /**
     * M88-B（docs/183 §2）：`shell.exec` 的唯一执行入口 —— **root → Shizuku → App** 自动选择。
     *
     * 有 root 用 root；没有 root 自动退到 Shizuku；都没有用 App 自己的 `sh`。
     * 需要 root 的命令在无 root 时由命令的 stderr 体现失败；返回的 `identity` 说清实际身份，
     * **不假装 root**。`root.exec` 已并入本能力（按 id 直调返回 `merged_into_shell_exec`）。
     */
    suspend fun runElevated(command: String): ShellCommandResult = withContext(Dispatchers.IO) {
        when {
            hasRoot() -> runRoot(command)
            hasShizuku() -> runShizuku(command)
            else -> runApp(command)
        }
    }

    /** `shell.exec` 实际用的身份：root > 已授权 Shizuku > App（与 [runElevated] 的顺序一致）。 */
    fun elevatedIdentity(): String {
        val probe = probeRoot()
        return ShellCapability.execIdentity(
            root = probe.available,
            shizuku = shizukuReady,
            shizukuPermission = hasShizukuPermission(),
        )
    }

    /** binder 收到 + 权限已授予才算「Shizuku 可用」（只收到 binder 时命令仍会被拒）。 */
    fun hasShizuku(): Boolean = shizukuReady && hasShizukuPermission()

    /** 有没有任何一种能拿到 shell 身份的通道（SHELL）。 */
    fun canUseShellIdentity(): Boolean = hasShizuku() || hasRoot()

    override suspend fun run(command: String, identity: ShellIdentity): ShellCommandResult =
        withContext(Dispatchers.IO) {
            when (identity) {
                ShellIdentity.ROOT -> if (hasRoot()) runRoot(command) else runApp(command)
                ShellIdentity.SHELL -> when {
                    hasShizuku() -> runShizuku(command)
                    hasRoot() -> runRoot(command, shellIdentity = true)
                    else -> runApp(command)
                }
                ShellIdentity.DEFAULT -> when {
                    hasRoot() -> runRoot(command)
                    hasShizuku() -> runShizuku(command)
                    else -> runApp(command)
                }
            }
        }

    /**
     * 取命令 stdout 的原始字节，专供 `screencap -p` 这类二进制输出。
     *
     * 不落文件：`screencap -p /sdcard/Android/data/<pkg>/...` 依赖 shell uid 在 FUSE 上的写权限，
     * 不同 ROM 行为不一致；直接读 stdout 没有这个变量（docs/76 §十七 17.3-1）。
     */
    suspend fun runBytes(command: String, identity: ShellIdentity): ByteArray? =
        withContext(Dispatchers.IO) {
            when (identity) {
                ShellIdentity.ROOT -> if (hasRoot()) runRootBytes(command) else runAppBytes(command)
                ShellIdentity.SHELL -> when {
                    hasShizuku() -> runShizukuBytes(command)
                    hasRoot() -> runRootBytes(command, shellIdentity = true)
                    else -> runAppBytes(command)
                }
                ShellIdentity.DEFAULT -> when {
                    hasRoot() -> runRootBytes(command)
                    hasShizuku() -> runShizukuBytes(command)
                    else -> runAppBytes(command)
                }
            }
        }

    /**
     * 一次性提权执行（`bash identity=shell/root`，docs/198 §二）：**整条命令**交给系统 shell，
     * 流式收 stdout/stderr 的尾部，按 [timeoutMs] 到时杀进程树，如实回报实际身份。
     *
     * 与 [runElevated] 的区别：这条给 AI 的 `bash` 工具用，所以支持 `cwd`/`env`/`stdin`/超时，
     * 且**绝不静默降级成 App 身份**（身份不可用时返回 [ElevatedRun.error]，让模型知道该去授权）。
     *
     * `shell` 身份的 cwd 如果在 app 私有目录里（shell uid 2000 进不去），改用 `/data/local/tmp`
     * 并在 [ElevatedRun.note] 里说明，不假装命令是在原目录跑的。
     */
    suspend fun runElevatedCommand(
        command: String,
        identity: ShellIdentity,
        cwd: File?,
        env: Map<String, String>,
        timeoutMs: Long,
        stdin: String?,
        maxOutputChars: Int = ELEVATED_MAX_OUTPUT_CHARS,
    ): ElevatedRun = withContext(Dispatchers.IO) {
        val timeout = timeoutMs.coerceIn(1_000L, 600_000L)
        val wanted = if (identity == ShellIdentity.DEFAULT) ShellIdentity.SHELL else identity
        when (wanted) {
            ShellIdentity.ROOT -> {
                if (!hasRoot()) {
                    return@withContext ElevatedRun(
                        ok = false,
                        identity = "root",
                        exitCode = -1,
                        timedOut = false,
                        output = "",
                        truncated = false,
                        error = ROOT_UNAVAILABLE_REASON,
                    )
                }
                runRootStreaming(command, cwd, env, timeout, stdin, maxOutputChars, shellIdentity = false)
            }
            ShellIdentity.SHELL -> when {
                hasShizuku() -> {
                    val (dir, note) = shellAccessibleCwd(cwd)
                    runShizukuStreaming(command, dir, env, timeout, stdin, maxOutputChars).copy(note = note)
                }
                hasRoot() -> {
                    val (dir, note) = shellAccessibleCwd(cwd)
                    runRootStreaming(command, dir, env, timeout, stdin, maxOutputChars, shellIdentity = true)
                        .copy(note = note)
                }
                else -> ElevatedRun(
                    ok = false,
                    identity = "shell",
                    exitCode = -1,
                    timedOut = false,
                    output = "",
                    truncated = false,
                    error = SHELL_UNAVAILABLE_REASON,
                )
            }
            ShellIdentity.DEFAULT -> error("unreachable")
        }
    }

    /** shell uid 2000 进不去 app 私有目录：换成 `/data/local/tmp` 并给出说明。 */
    private fun shellAccessibleCwd(cwd: File?): Pair<File?, String?> {
        if (cwd == null) return null to null
        val dataDir = appContext?.applicationInfo?.dataDir ?: return cwd to null
        val insideAppData = cwd.path == dataDir || cwd.path.startsWith(dataDir + File.separator)
        if (!insideAppData) return cwd to null
        val fallback = File("/data/local/tmp").takeIf { it.isDirectory } ?: File("/")
        return fallback to (
            "workspace is app-private storage, which shell (uid 2000) cannot enter; " +
                "the command ran in ${fallback.absolutePath}. " +
                "Pass workdir only for paths the shell identity can read (e.g. /sdcard, /data/local/tmp)."
            )
    }

    /**
     * root 执行（[shellIdentity] = 用 launcher 降权到 uid 2000）。
     * `su` 写法的选择复用 [RootProbe]；环境变量由调用方给全（系统 PATH/HOME 等）。
     */
    private fun runRootStreaming(
        command: String,
        cwd: File?,
        env: Map<String, String>,
        timeoutMs: Long,
        stdin: String?,
        maxOutputChars: Int,
        shellIdentity: Boolean,
    ): ElevatedRun {
        val probe = probeRoot()
        val launcher = if (shellIdentity) launcherStatus() else null
        val useLauncher = launcher?.available == true
        val argv = RootProbe.execArgv(probe, command, if (useLauncher) launcher?.path else null)
        val label = when {
            !shellIdentity -> "root (uid 0 via ${probe.command ?: "su"})"
            useLauncher -> "shell (uid 2000 via root launcher)"
            else -> "root (uid 0; shell launcher unavailable: ${launcher?.reason ?: "unknown"})"
        }
        val builder = ProcessBuilder(argv).redirectErrorStream(true)
        if (cwd != null) runCatching { builder.directory(cwd) }
        builder.environment().putAll(env)
        val process = runCatching { builder.start() }.getOrElse {
            return ElevatedRun(
                ok = false,
                identity = label,
                exitCode = -1,
                timedOut = false,
                output = "",
                truncated = false,
                error = "failed to start ${argv.firstOrNull() ?: "su"}: ${it.message ?: it.javaClass.simpleName}",
            )
        }
        feedStdin(process, stdin)
        val tail = TailText(maxOutputChars)
        val reader = Thread({
            runCatching { process.inputStream.reader(Charsets.UTF_8).use { readInto(it, tail) } }
        }, "dsh-elevated-out").apply { isDaemon = true; start() }
        val finished = runCatching { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!finished) killProcessTree(process)
        reader.join(1_500)
        val exit = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
        return ElevatedRun(
            ok = finished && exit == 0,
            identity = label,
            exitCode = exit,
            timedOut = !finished,
            output = tail.text(),
            truncated = tail.truncated,
        )
    }

    /** Shizuku 执行：`newProcess(argv, env, dir)` + 流式读 stdout/stderr；超时销毁并保留已读输出。 */
    private fun runShizukuStreaming(
        command: String,
        cwd: File?,
        env: Map<String, String>,
        timeoutMs: Long,
        stdin: String?,
        maxOutputChars: Int,
    ): ElevatedRun = runCatching {
        val binder = Shizuku.getBinder() ?: error("Shizuku binder 未收到")
        val service = IShizukuService.Stub.asInterface(ShizukuBinderWrapper(binder))
            ?: error("IShizukuService 代理为空")
        val envArray = env.entries.map { "${it.key}=${it.value}" }.toTypedArray().takeIf { it.isNotEmpty() }
        val remote = service.newProcess(arrayOf("sh", "-c", command), envArray, cwd?.absolutePath)
        val tail = TailText(maxOutputChars)
        feedRemoteStdin(remote, stdin)
        val pumps = listOf("dsh-elevated-out" to remote.inputStream, "dsh-elevated-err" to remote.errorStream)
            .map { (name, pfd) ->
                Thread({
                    runCatching {
                        ParcelFileDescriptor.AutoCloseInputStream(pfd).reader(Charsets.UTF_8).use { readInto(it, tail) }
                    }
                }, name).apply { isDaemon = true; start() }
            }
        val exitHolder = java.util.concurrent.atomic.AtomicInteger(-1)
        val waiter = Thread({ exitHolder.set(runCatching { remote.waitFor() }.getOrDefault(-1)) }, "dsh-elevated-wait")
            .apply { isDaemon = true; start() }
        waiter.join(timeoutMs)
        val timedOut = waiter.isAlive
        if (timedOut) {
            runCatching { remote.destroy() }
            waiter.join(1_000)
        }
        pumps.forEach { it.join(1_500) }
        val exit = exitHolder.get()
        ElevatedRun(
            ok = !timedOut && exit == 0,
            identity = "shell (uid 2000 via Shizuku)",
            exitCode = if (timedOut) -1 else exit,
            timedOut = timedOut,
            output = tail.text(),
            truncated = tail.truncated,
        )
    }.getOrElse {
        ElevatedRun(
            ok = false,
            identity = "shell",
            exitCode = -1,
            timedOut = false,
            output = "",
            truncated = false,
            error = "failed to start a Shizuku process: ${it.message ?: it.javaClass.simpleName}",
        )
    }

    /** 把 stdin 一次性喂给进程再关流（不给就立刻 EOF，别让 `cat` 挂着）。 */
    private fun feedStdin(process: Process, data: String?) {
        runCatching {
            process.outputStream.use { stream ->
                if (data != null) stream.write(data.toByteArray(Charsets.UTF_8))
            }
        }
    }

    /** Shizuku 的 `IRemoteProcess` 有独立的 stdin 管道；关掉它子进程才能看到 EOF。 */
    private fun feedRemoteStdin(remote: moe.shizuku.server.IRemoteProcess, data: String?) {
        val pfd = runCatching { remote.outputStream }.getOrNull() ?: return
        runCatching {
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { stream ->
                if (data != null) stream.write(data.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun readInto(reader: Reader, tail: TailText) {
        val buffer = CharArray(8 * 1024)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            if (read > 0) tail.append(String(buffer, 0, read))
        }
    }

    /**
     * 有界字符尾部缓冲：提权输出可能很大（`dumpsys`），只留最后 [capacity] 个字符，
     * 超出的部分丢掉并标记 [truncated]（bash 工具随后落盘/截断展示）。
     */
    private class TailText(private val capacity: Int) {
        private val chunks = ArrayDeque<String>()
        private var total = 0

        @Volatile
        var truncated: Boolean = false
            private set

        @Synchronized
        fun append(text: String) {
            if (text.isEmpty()) return
            chunks.addLast(text)
            total += text.length
            while (total > capacity && chunks.size > 1) {
                total -= chunks.removeFirst().length
                truncated = true
            }
            if (total > capacity && chunks.size == 1) {
                val only = chunks.removeFirst()
                val kept = only.takeLast(capacity)
                chunks.addLast(kept)
                total = kept.length
                truncated = true
            }
        }

        @Synchronized
        fun text(): String = chunks.joinToString("")
    }

    /** 超时清理：先拍进程树再强杀，避免 `su` 下的子进程被 init 收养后继续跑。 */
    private fun killProcessTree(process: Process) {
        val parentPid = processPid(process)
        val descendants = if (parentPid > 0) runCatching { descendantsOf(parentPid) }.getOrDefault(emptyList()) else emptyList()
        runCatching { process.destroyForcibly() }
        descendants.reversed().forEach { childPid ->
            runCatching {
                ProcessBuilder("/system/bin/kill", "-9", childPid.toString()).start()
                    .waitFor(200, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun processPid(process: Process): Int {
        runCatching {
            val field = process.javaClass.getDeclaredField("pid").apply { isAccessible = true }
            val value = field.getInt(process)
            if (value > 0) return value
        }
        runCatching {
            val method = Process::class.java.getMethod("pid").apply { isAccessible = true }
            val value = (method.invoke(process) as? Number)?.toInt() ?: -1
            if (value > 0) return value
        }
        return -1
    }

    private fun descendantsOf(rootPid: Int): List<Int> {
        val children = HashMap<Int, MutableList<Int>>()
        File("/proc").listFiles()?.forEach { dir ->
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

    private fun runShizukuBytes(command: String): ByteArray? = runCatching {
        val binder = Shizuku.getBinder() ?: error("Shizuku binder 未收到")
        val service = IShizukuService.Stub.asInterface(ShizukuBinderWrapper(binder))
            ?: error("IShizukuService 代理为空")
        val remote = service.newProcess(arrayOf("sh", "-c", command), null, null)
        val out = ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream).use { it.readBytes() }
        val exit = runCatching { remote.waitFor() }.getOrDefault(-1)
        runCatching { remote.destroy() }
        if (exit == 0 && out.isNotEmpty()) out else null
    }.getOrNull()

    private fun runRootBytes(command: String, shellIdentity: Boolean = false): ByteArray? {
        val launcher = if (shellIdentity) launcherStatus() else null
        val argv = RootProbe.execArgv(probeRoot(), command, launcher?.takeIf { it.available }?.path)
        return runProcessBytes(argv)
    }

    private fun runAppBytes(command: String): ByteArray? =
        runProcessBytes(listOf("sh", "-c", command))

    private fun runProcessBytes(argv: List<String>): ByteArray? = runCatching {
        val process = ProcessBuilder(argv).redirectErrorStream(false).start()
        // 先读完 stdout 再 waitFor，避免管道写满把子进程卡死。
        val out = process.inputStream.use { it.readBytes() }
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        if (finished && process.exitValue() == 0 && out.isNotEmpty()) out else null
    }.getOrNull()

    /**
     * root 执行：argv 用 [RootProbe.execArgv] 依据探测成功的 su 写法拼（候选 D），
     * [shellIdentity] = true 时在最前面加 launcher（root-only 的虚拟屏就靠它拿到 uid 2000）。
     *
     * launcher 不可用时只退回旧的 uid 0 路径（不是每次命令都重试），并在失败结果的 stderr
     * 里写明原因，避免再把「架构不符/未打包」误报成 Shizuku 授权问题。
     */
    private fun runRoot(command: String, shellIdentity: Boolean = false): ShellCommandResult {
        val launcher = if (shellIdentity) launcherStatus() else null
        val argv = RootProbe.execArgv(probeRoot(), command, launcher?.takeIf { it.available }?.path)
        val result = runProcess(argv)
        if (shellIdentity && launcher != null && !launcher.available) {
            Log.w(TAG, "shell identity launcher unavailable (${launcher.reason}); running as uid 0: $command")
            if (!result.success) {
                return result.copy(
                    stderr = "[DeviceShell] shell 身份 launcher 不可用：${launcher.reason}；" +
                        "已退回 uid 0（su -c），虚拟屏的 DisplayManager 校验可能失败。\n" + result.stderr,
                )
            }
        }
        return result
    }

    /**
     * Shizuku 执行：Shizuku 13 的 `api` artifact **没有** `newProcess`（旧版反射写法在这里一直失败，
     * 结果是所有 shell 命令静默退化到 App uid —— 虚拟屏因此永远拉不起来）。
     *
     * 正确姿势是走 `aidl` artifact 的 `IShizukuService`：`ShizukuBinderWrapper(Shizuku.getBinder())`
     * 把系统 binder 包成可调用的接口代理，然后 `newProcess(cmd, env, dir)` 拿 `IRemoteProcess`。
     */
    private fun runShizuku(command: String): ShellCommandResult = runCatching {
        val binder = Shizuku.getBinder() ?: error("Shizuku binder 未收到")
        val service = IShizukuService.Stub.asInterface(ShizukuBinderWrapper(binder))
            ?: error("IShizukuService 代理为空")
        val remote = service.newProcess(arrayOf("sh", "-c", command), null, null)
        collectRemote(remote)
    }.getOrElse {
        Log.w(TAG, "Shizuku exec failed, falling back to app shell", it)
        runApp(command)
    }

    /** 读 IRemoteProcess 的 stdout/stderr（两边各起一个线程，避免大输出把管道堵死）。 */
    private fun collectRemote(process: moe.shizuku.server.IRemoteProcess): ShellCommandResult {
        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = Thread {
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(process.inputStream).bufferedReader()
                    .use { out.append(it.readText()) }
            }
        }.apply { isDaemon = true; start() }
        val errThread = Thread {
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(process.errorStream).bufferedReader()
                    .use { err.append(it.readText()) }
            }
        }.apply { isDaemon = true; start() }
        val exit = runCatching { process.waitFor() }.getOrDefault(-1)
        outThread.join(3_000)
        errThread.join(3_000)
        runCatching { process.destroy() }
        return ShellCommandResult(
            success = exit == 0,
            stdout = out.toString(),
            stderr = err.toString(),
            exitCode = exit,
        )
    }

    private fun runApp(command: String): ShellCommandResult =
        runProcess(listOf("sh", "-c", command))

    private fun runProcess(argv: List<String>): ShellCommandResult = runCatching {
        collect(ProcessBuilder(argv).redirectErrorStream(false).start())
    }.getOrElse {
        ShellCommandResult(false, "", it.message ?: it.javaClass.simpleName, -1)
    }

    private fun runProcess(process: Process): ShellCommandResult = runCatching {
        collect(process)
    }.getOrElse {
        ShellCommandResult(false, "", it.message ?: it.javaClass.simpleName, -1)
    }

    private fun collect(process: Process): ShellCommandResult {
        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = Thread { runCatching { out.append(process.inputStream.bufferedReader().readText()) } }
            .apply { isDaemon = true; start() }
        val errThread = Thread { runCatching { err.append(process.errorStream.bufferedReader().readText()) } }
            .apply { isDaemon = true; start() }
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        outThread.join(2_000)
        errThread.join(2_000)
        return ShellCommandResult(
            success = finished && runCatching { process.exitValue() }.getOrDefault(-1) == 0,
            stdout = out.toString(),
            stderr = err.toString(),
            exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1,
        )
    }

    /** Shizuku 客户端初始化（Application.onCreate 调一次）。binder 是异步到的，用 sticky 监听补种。 */
    fun initShizuku(context: android.content.Context) {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky {
                shizukuReady = true
                Log.i(TAG, "Shizuku binder received")
            }
            Shizuku.addBinderDeadListener {
                shizukuReady = false
                Log.i(TAG, "Shizuku binder dead")
            }
            shizukuReady = Shizuku.pingBinder()
        }.onFailure { Log.i(TAG, "Shizuku not installed: ${it.message}") }
    }

    /** Shizuku 权限是否已授予（binder 没起来 / 没装时返回 false，不抛）。 */
    fun hasShizukuPermission(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 旧入口保留给「权限页之外」的地方：直接发一次授权请求（结果回调用 DevicePermissions 的版本）。 */
    fun requestShizukuPermission(): Boolean = runCatching {
        Shizuku.requestPermission(0)
        true
    }.getOrDefault(false)

    /** Shower 需要把 jar 写到外部目录：优先 App 专属外部目录，失败时退回 filesDir。 */
    fun showerJarDir(context: android.content.Context): File {
        val external = context.getExternalFilesDir(null) ?: context.filesDir
        return File(external, "shower").apply { mkdirs() }
    }
}
