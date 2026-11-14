package xin.dshaa.nativeapp.device

import android.content.pm.PackageManager
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
import java.util.concurrent.TimeUnit

/**
 * 设备 shell 后端：给 Shower（虚拟屏）与后续设备工具用。
 *
 * 身份优先级：
 * - `SHELL`（uid 2000，`app_process`/`cp /data/local/tmp` 必需）→ Shizuku；
 * - `ROOT` → `su -c`；
 * - `DEFAULT` → 先 root 再 Shizuku 再 App 自己的 `sh`（pkill 自己的子进程够用）。
 *
 * 没有 Shizuku/root 时所有身份退化成 App `sh`（命令会因权限失败，但不会崩）。
 */
object DeviceShell : ShellRunner {

    private const val TAG = "DeviceShell"

    @Volatile
    var shizukuReady: Boolean = false
        private set

    @Volatile
    private var rootChecked = false

    @Volatile
    private var rootReady = false

    fun hasRoot(): Boolean {
        if (!rootChecked) {
            rootChecked = true
            rootReady = runCatching {
                val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor(3, TimeUnit.SECONDS) && output.contains("uid=0")
            }.getOrDefault(false)
        }
        return rootReady
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
                    hasRoot() -> runRoot(command)
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
                    hasRoot() -> runRootBytes(command)
                    else -> runAppBytes(command)
                }
                ShellIdentity.DEFAULT -> when {
                    hasRoot() -> runRootBytes(command)
                    hasShizuku() -> runShizukuBytes(command)
                    else -> runAppBytes(command)
                }
            }
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

    private fun runRootBytes(command: String): ByteArray? =
        runProcessBytes(listOf("su", "-c", command))

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

    private fun runRoot(command: String): ShellCommandResult = runProcess(listOf("su", "-c", command))

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
