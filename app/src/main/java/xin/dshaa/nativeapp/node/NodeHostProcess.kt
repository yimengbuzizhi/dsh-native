package xin.dshaa.nativeapp.node

import android.os.Process as AndroidProcess
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 一个 Node 宿主进程（docs/185 P2 §生命周期）。
 *
 * 关键约束（都是真机踩出来的，别随手改）：
 * - **必须**带 `OPENSSL_CONF=/dev/null`：这份 libnode.so 是给 Termux 编的，OpenSSL 默认配置路径
 *   被烘焙成 `/data/data/com.termux/files/usr/etc/tls/openssl.cnf`，读不到就 `OpenSSL configuration
 *   error` 直接起不来。设成 /dev/null 后一切正常（P2 真机实测）。
 * - `LD_LIBRARY_PATH` 必须 `files/node/lib` 在前、`nativeLibraryDir` 在后，两段都要。
 * - stdout/stderr 合并后**必须**有常驻消费线程：不读满管道 Node 会阻塞在写日志上，表现成"起不来"。
 * - 端口由 Node 侧 `listen(0)` 让内核分配，再用一行 `HOST_READY <port> <bootMs>` 回传；这里只解析。
 * - 回收按进程树：Node 自己 spawn 的子进程不跟着主进程死，先把树拍下来，再逐个 SIGKILL。
 */
class NodeHostProcess(
    private val installer: NodeRuntimeInstaller,
    private val onLog: (String) -> Unit = {},
) {

    /** HOST_READY 解析结果。 */
    data class Ready(val port: Int, val bootMs: Long)

    private val ready = CompletableDeferred<Ready>()
    private val logLines = ArrayDeque<String>()

    @Volatile
    private var process: Process? = null

    @Volatile
    var lastExitCode: Int? = null
        private set

    val pid: Int
        get() = process?.let { pidOf(it) } ?: -1

    fun isAlive(): Boolean = process?.isAlive == true

    /** 最近 N 行宿主输出（插件页日志 / 失败原因都从这里取）。 */
    fun tail(max: Int = 40): List<String> = synchronized(logLines) {
        logLines.toList().takeLast(max)
    }

    /** 起进程并等到 HOST_READY；超时/提前退出都抛异常，由上层决定是否重启。 */
    suspend fun start(readyTimeoutMs: Long = 20_000, token: String): Ready = withContext(Dispatchers.IO) {
        val binary = installer.binaryFile
        if (!binary.isFile) error("缺少 ${binary.absolutePath}（这个 ABI 没打包 Node 运行时）")
        if (!installer.hostScript.isFile) error("缺少 ${installer.hostScript.absolutePath}（运行时没解压完）")
        installer.tmpDir.mkdirs()
        installer.homeDir.mkdirs()
        installer.dataDir.mkdirs()

        val builder = ProcessBuilder(binary.absolutePath, installer.hostScript.absolutePath)
            .directory(installer.dshDir)
            .redirectErrorStream(true)
        // 干净环境：不继承 app 进程的 Android 变量，免得 TMPDIR/HOME 指到不可写的地方。
        val env = builder.environment()
        env.clear()
        env["PATH"] = "/system/bin:/system/xbin"
        env["LD_LIBRARY_PATH"] = "${installer.libDir.absolutePath}:${installer.nativeLibraryDir}"
        env["HOME"] = installer.homeDir.absolutePath
        env["TMPDIR"] = installer.tmpDir.absolutePath
        env["OPENSSL_CONF"] = "/dev/null"
        env["NODE_NO_WARNINGS"] = "1"
        // docs/187 §九 第 2 项：WS 鉴权 token 只经环境变量进宿主，不落盘、不进日志。
        env["DSH_NODE_TOKEN"] = token
        // 插件/清单在 files/node/data/，运行时在 runtime/<version>/：host.mjs 用这个找用户插件。
        env["DSH_NODE_DATA"] = installer.dataDir.absolutePath

        val proc = runCatching { builder.start() }.getOrElse { error("spawn 失败：${it.message}") }
        process = proc
        Log.i(TAG, "node host spawned pid=${pidOf(proc)}")
        Thread({ pump(proc) }, "dsh-node-stdout").apply { isDaemon = true; start() }
        Thread({ watchExit(proc) }, "dsh-node-waiter").apply { isDaemon = true; start() }

        withTimeout(readyTimeoutMs) { ready.await() }.also { info ->
            Log.i(TAG, "node host ready port=${info.port} bootMs=${info.bootMs}")
        }
    }

    private fun pump(proc: Process) {
        runCatching {
            BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val trimmed = if (line.length > 2_000) line.take(2_000) + "…" else line
                    synchronized(logLines) {
                        logLines.addLast(trimmed)
                        while (logLines.size > 200) logLines.removeFirst()
                    }
                    if (trimmed.startsWith("HOST_READY ")) {
                        val parts = trimmed.split(' ')
                        val port = parts.getOrNull(1)?.toIntOrNull()
                        val bootMs = parts.getOrNull(2)?.toLongOrNull() ?: -1
                        if (port != null && port in 1..65_535) {
                            ready.complete(Ready(port, bootMs))
                        } else {
                            ready.completeExceptionally(IllegalStateException("HOST_READY 解析失败：$trimmed"))
                        }
                    }
                    onLog(trimmed)
                }
            }
        }
        Log.i(TAG, "node host stdout closed pid=${pidOf(proc)}")
    }

    private fun watchExit(proc: Process) {
        val code = runCatching { proc.waitFor() }.getOrDefault(-1)
        lastExitCode = code
        if (!ready.isCompleted) {
            // 还没 READY 就退：把最后几行塞进异常，否则上层只能看到 "closed"。
            ready.completeExceptionally(
                IllegalStateException("node 在 HOST_READY 前退出（code=$code）：${tail(8).joinToString(" | ")}"),
            )
        }
        Log.w(TAG, "node host exited code=$code pid=${pidOf(proc)}")
    }

    /** 可选的健康指标：/proc/<pid>/status 的 VmRSS。 */
    fun rssKb(): Long? {
        val pid = pid
        if (pid <= 0) return null
        return runCatching {
            File("/proc/$pid/status").readLines()
                .firstOrNull { it.startsWith("VmRSS:") }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull()
        }.getOrNull()
    }

    /** 优雅停：先 SIGTERM（Node 收到会走 exit），再按进程树强杀。 */
    fun stop(gracefulMs: Long = 2_000) {
        val proc = process ?: return
        val parentPid = pidOf(proc)
        // 先拍树：父进程一死，子进程就被 init 收养，再按 ppid 就找不到它们了。
        val tree = if (parentPid > 0) runCatching { descendantsOf(parentPid) }.getOrDefault(emptyList()) else emptyList()
        runCatching { proc.destroy() }
        val exited = runCatching { proc.waitFor(gracefulMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!exited) {
            Log.w(TAG, "node host did not exit in ${gracefulMs}ms, killing tree size=${tree.size}")
            runCatching { proc.destroyForcibly() }
            runCatching { proc.waitFor(1, TimeUnit.SECONDS) }
        }
        tree.forEach { childPid -> runCatching { AndroidProcess.sendSignal(childPid, SIG_KILL) } }
        process = null
    }

    /** 按 /proc 扫出 root 的全部后代（BFS），返回顺序 = 由浅到深。 */
    private fun descendantsOf(rootPid: Int): List<Int> {
        val children = HashMap<Int, MutableList<Int>>()
        val procDir = File("/proc")
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

    /**
     * `java.lang.Process.pid()` 在编译期（jdk-release 8 视角）看不到，但运行时 API 26+ 有，
     * 所以走一次反射：拿不到就 -1，最多是回收不到子进程，不影响主链。
     */
    /**
     * 子进程 pid。
     *
     * `Process.pid()` 在 jdk-release 8 视角下不可见；Android 的实类是 package-private 的
     * `java.lang.UNIXProcess`，反射调用公开方法会抛 IllegalAccessException，所以先读它的
     * `pid` 字段（拿不到再退回方法调用）。pid 只用于日志与按进程树回收，拿不到时返回 -1，
     * 调用方会退化成只杀进程本身。
     */
    private fun pidOf(proc: Process): Int {
        runCatching {
            val field = proc.javaClass.getDeclaredField("pid").apply { isAccessible = true }
            val value = field.getInt(proc)
            if (value > 0) return value
        }
        runCatching {
            val method = Process::class.java.getMethod("pid").apply { isAccessible = true }
            val value = method.invoke(proc) as Int
            if (value > 0) return value
        }
        return -1
    }

    private companion object {
        const val TAG = "DshNodeHost"

        /** android.os.Process 没有 SIGKILL 常量，signal 9 就是它的值。 */
        const val SIG_KILL = 9
    }
}
