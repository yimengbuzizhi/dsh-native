package xin.dshaa.nativeapp.toolchain

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xin.dshaa.core.tools.ElevatedRequest
import xin.dshaa.core.tools.ElevatedResult
import xin.dshaa.core.tools.ShellHost
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * 打包的命令行工具箱（`docs/187` §六）：真 bash（Termux GPLv3）+ 真 curl（Termux MIT）+ Mozilla CA。
 *
 * 两条 Android 硬约束决定了这里的形状：
 * 1. targetSdk 34 下应用数据目录**不能 execve**，可执行体只能放 `nativeLibraryDir`（系统从
 *    jniLibs 解压出来，`useLegacyPackaging = true`）。所以 bash/curl 的 ELF 以
 *    `libbash.so` / `libcurl-bin.so` 的名字进 jniLibs——`lib*.so` 才会被打进 APK。
 * 2. 依赖库（`libreadline.so.8` / `libncursesw.so.6` / `libssl.so.3` …）的 `DT_NEEDED` 是带
 *    版本号的真名，jniLibs 装不下这种名字；它们以**普通数据文件**解到 `files/toolchain/lib`，
 *    由 `LD_LIBRARY_PATH` 找到（`dlopen`/链接器加载数据目录里的 `.so` 是允许的，P0 已证）。
 *
 * 派生出的 `bin/` 软链（`bash` → `nativeLibraryDir/libbash.so`）只是为了让 `command -v bash`
 * 这类命令有输出；软链本身不可执行也没关系——execve 跟随到 nativeLibraryDir 里的真文件。
 * 若设备/系统策略连软链都不给建，退回 `BASH_ENV` 函数（`bash_env.sh`），工具照样能用。
 */
class Toolchain(private val context: Context) : ShellHost {

    /** 安装结果：给设置页/关于页显示，也给自检 JSON。 */
    data class Status(
        val installed: Boolean,
        val bashPath: String?,
        val curlPath: String?,
        val libDir: String,
        val caFile: String,
        val symlinkOk: Boolean,
        val probe: String,
        val error: String? = null,
    )

    val root: File = File(context.filesDir, "toolchain")
    val libDir: File = File(root, "lib")
    val binDir: File = File(root, "bin")
    val busyboxBinDir: File = File(root, "busybox-bin")
    val certsDir: File = File(root, "certs")
    val caFile: File = File(root, "cacert.pem")
    val bashEnvFile: File = File(root, "bash_env.sh")
    val homeDir: File = File(context.filesDir, "home")
    private val stampFile: File = File(root, "install.json")
    private val installMutex = Mutex()

    @Volatile
    private var readyFlag: Boolean = false

    /** 可执行体：只有 nativeLibraryDir 里的 ELF 能 exec。 */
    val bashFile: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libbash.so")
    val curlFile: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libcurl-bin.so")
    val busyboxFile: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libbusybox.so")
    val nodeFile: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libnode.so")

    private val nativeDir: String
        get() = context.applicationInfo.nativeLibraryDir

    /** 本机 ABI 有没有打包 bash/curl（模拟器 x86_64 没有，只能退回 `/system/bin/sh`）。 */
    fun bundledAvailable(): Boolean = bashFile.isFile && curlFile.isFile

    fun isInstalled(): Boolean =
        stampMatches() &&
            libDir.isDirectory &&
            caFile.isFile &&
            (!bundledAvailable() || (binDir.isDirectory && linksValid()))

    override suspend fun prepare() {
        if (readyFlag && isInstalled()) return
        ensureInstalled()
    }

    override fun isReady(): Boolean = readyFlag && bundledAvailable() && libDir.isDirectory

    /** 解压 + 建软链（含 BusyBox 工具集）+ 自检；已就绪且版本/nativeDir 匹配时直接短路跳过重复解压。 */
    suspend fun ensureInstalled(): Result<Status> = installMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!bundledAvailable()) {
                    readyFlag = false
                    return@runCatching Status(
                        installed = false,
                        bashPath = null,
                        curlPath = null,
                        libDir = libDir.absolutePath,
                        caFile = caFile.absolutePath,
                        symlinkOk = false,
                        probe = "unsupported abi: no libbash.so in nativeLibraryDir ($nativeDir)",
                    )
                }
                if (isInstalled()) {
                    readyFlag = true
                    val symlinkOk = linksValid()
                    return@runCatching Status(
                        installed = true,
                        bashPath = bashFile.absolutePath,
                        curlPath = curlFile.absolutePath,
                        libDir = libDir.absolutePath,
                        caFile = caFile.absolutePath,
                        symlinkOk = symlinkOk,
                        probe = "cached",
                    )
                }
                libDir.mkdirs()
                homeDir.mkdirs()
                binDir.mkdirs()
                certsDir.mkdirs()
                extractAssets()
                val symlinkOk = createLinks()
                val busyboxCount = createBusyboxLinks()
                writeBashEnv(symlinkOk)
                val (probeOk, probe) = runProbe()
                if (!probeOk) {
                    readyFlag = false
                    Log.w(TAG, "toolchain probe failed, falling back to /system/bin/sh: $probe")
                    return@runCatching Status(
                        installed = false,
                        bashPath = null,
                        curlPath = null,
                        libDir = libDir.absolutePath,
                        caFile = caFile.absolutePath,
                        symlinkOk = symlinkOk,
                        probe = probe,
                        error = "toolchain self-probe failed",
                    )
                }
                readyFlag = true
                writeStamp(symlinkOk, probe, busyboxCount)
                Log.i(
                    TAG,
                    "toolchain ready: bash=${bashFile.absolutePath} curl=${curlFile.absolutePath} " +
                        "symlink=$symlinkOk busyboxApplets=$busyboxCount",
                )
                Status(
                    installed = true,
                    bashPath = bashFile.absolutePath,
                    curlPath = curlFile.absolutePath,
                    libDir = libDir.absolutePath,
                    caFile = caFile.absolutePath,
                    symlinkOk = symlinkOk,
                    probe = probe,
                )
            }
        }
    }

    /** 解 assets/toolchain 下的依赖库与 cacert.pem 到 files/toolchain/（不依赖不可靠的 input.available()）。 */
    private fun extractAssets() {
        val libs = context.assets.list(ASSET_LIB_DIR).orEmpty().filter { it.isNotBlank() }
        for (name in libs) {
            val target = File(libDir, name)
            val assetPath = "$ASSET_LIB_DIR/$name"
            val expected = runCatching { context.assets.openFd(assetPath).use { it.length } }.getOrDefault(-1L)
            context.assets.open(assetPath).use { input ->
                copyIfChanged(expected, target) { out -> input.copyTo(out, BUFFER_SIZE) }
            }
        }
        val caExpected = runCatching { context.assets.openFd(ASSET_CA).use { it.length } }.getOrDefault(-1L)
        context.assets.open(ASSET_CA).use { input ->
            copyIfChanged(caExpected, caFile) { out -> input.copyTo(out, BUFFER_SIZE) }
        }
    }

    private fun copyIfChanged(expectedSize: Long, target: File, write: (FileOutputStream) -> Unit) {
        if (expectedSize > 0 && target.isFile && target.length() == expectedSize) return
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            write(out)
            out.flush()
            out.fd.sync()
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * `files/toolchain/bin/{bash,curl}` 软链到 nativeLibraryDir。返回是否全部就绪。
     */
    private fun createLinks(): Boolean {
        val pairs = listOf(
            File(binDir, "bash") to bashFile,
            File(binDir, "curl") to curlFile,
        )
        var ok = true
        for ((link, target) in pairs) {
            if (!ensureSymlink(link, target)) ok = false
        }
        return ok
    }

    /**
     * 零体积增量接入已随包打包的 `libbusybox.so` 常用命令行工具（awk/sed/grep/tar/xz/bzip2/unzip/xxd/diff/patch 等）。
     * 放入独立目录 `busybox-bin/`，绝不覆盖真 `bash`、`curl` 或系统 `sh`。
     */
    private fun createBusyboxLinks(): Int {
        if (!busyboxFile.isFile) return 0
        busyboxBinDir.mkdirs()
        var created = 0
        if (ensureSymlink(File(binDir, "busybox"), busyboxFile)) created++
        for (applet in BUSYBOX_APPLETS) {
            if (ensureSymlink(File(busyboxBinDir, applet), busyboxFile)) created++
        }
        return created
    }

    private fun ensureSymlink(link: File, target: File): Boolean = runCatching {
        if (Files.isSymbolicLink(link.toPath())) {
            val current = Files.readSymbolicLink(link.toPath()).toString()
            if (current == target.absolutePath && target.isFile) return@runCatching true
            Files.delete(link.toPath())
        }
        if (link.exists()) link.delete()
        Files.createSymbolicLink(link.toPath(), target.toPath())
        Files.isSymbolicLink(link.toPath()) &&
            Files.readSymbolicLink(link.toPath()).toString() == target.absolutePath
    }.getOrElse { error ->
        Log.w(TAG, "symlink ${link.name} failed: ${error.message}")
        false
    }

    /**
     * 软链不可用时的兜底脚本 + 可选 `node` 隔离包装（当 Node 运行时已解压时自动提供 `node` 命令，不污染全局 LD_LIBRARY_PATH）。
     */
    private fun writeBashEnv(symlinkOk: Boolean) {
        val nodeRuntimeRoot = File(context.filesDir, "node/runtime")
        val text = buildString {
            appendLine("# 鲸歌打包工具箱（docs/187 §6.4）。由 Toolchain.kt 生成，升级会覆盖。")
            appendLine("DSH_NATIVE_DIR=\"\${DSH_NATIVE_DIR:-$nativeDir}\"")
            appendLine("DSH_TOOLCHAIN_LIB=\"\${DSH_TOOLCHAIN_LIB:-${libDir.absolutePath}}\"")
            if (!symlinkOk) {
                appendLine("curl() { \"\$DSH_NATIVE_DIR/libcurl-bin.so\" \"\$@\"; }")
                appendLine("bash() { \"\$DSH_NATIVE_DIR/libbash.so\" \"\$@\"; }")
                if (busyboxFile.isFile) {
                    appendLine("busybox() { \"\$DSH_NATIVE_DIR/libbusybox.so\" \"\$@\"; }")
                }
            }
            if (nodeFile.isFile) {
                appendLine(
                    "node() { " +
                        "local _nlib=\"\"; " +
                        "for _d in \"${nodeRuntimeRoot.absolutePath}\"/*/lib; do [ -d \"\$_d\" ] && _nlib=\"\$_d\"; done; " +
                        "if [ -n \"\$_nlib\" ]; then " +
                        "LD_LIBRARY_PATH=\"\$_nlib:\$DSH_NATIVE_DIR\" OPENSSL_CONF=/dev/null \"\$DSH_NATIVE_DIR/libnode.so\" \"\$@\"; " +
                        "else " +
                        "OPENSSL_CONF=/dev/null \"\$DSH_NATIVE_DIR/libnode.so\" \"\$@\"; " +
                        "fi; }",
                )
            }
            appendLine("dsh_toolchain_info() { echo \"bash=\$DSH_NATIVE_DIR/libbash.so curl=\$DSH_NATIVE_DIR/libcurl-bin.so lib=\$DSH_TOOLCHAIN_LIB\"; }")
        }
        val changed = !bashEnvFile.isFile ||
            runCatching { bashEnvFile.readText(Charsets.UTF_8) != text }.getOrDefault(true)
        if (changed) bashEnvFile.writeText(text, Charsets.UTF_8)
    }

    /** 自检：直接 exec 一次 bash，确认 ELF/依赖库/软链都成立且 exit=0。 */
    private fun runProbe(): Pair<Boolean, String> = runCatching {
        val builder = ProcessBuilder(
            bashFile.absolutePath,
            "--noprofile",
            "--norc",
            "-c",
            "echo \"bash=\$BASH_VERSION\"; command -v bash; command -v curl; " +
                "${curlFile.absolutePath} --version | head -1",
        )
        builder.directory(homeDir)
        builder.redirectErrorStream(true)
        buildEnvironment(includeFallbackBashEnv = true).forEach { (k, v) -> builder.environment()[k] = v }
        val process = builder.start()
        process.outputStream.close()
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching false to "probe timed out after 10s"
        }
        val text = process.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val exit = process.exitValue()
        (exit == 0) to "exit=$exit\n${text.trim()}"
    }.getOrElse { false to "probe failed: ${it.javaClass.simpleName}: ${it.message}" }

    private fun stampMatches(): Boolean {
        if (!stampFile.isFile) return false
        return runCatching {
            val obj = Json.parseToJsonElement(stampFile.readText(Charsets.UTF_8)) as? JsonObject
                ?: return@runCatching false
            val verOk = (obj["version"] as? kotlinx.serialization.json.JsonPrimitive)?.content == VERSION
            // App 升级后 Android 会更换 nativeLibraryDir 路径，必须同时校验避免留下悬空软链
            val bashOk = (obj["bash"] as? kotlinx.serialization.json.JsonPrimitive)?.content == bashFile.absolutePath
            verOk && bashOk
        }.getOrDefault(false)
    }

    private fun writeStamp(symlinkOk: Boolean, probe: String, busyboxCount: Int) {
        val json = buildJsonObject {
            put("version", VERSION)
            put("bash", bashFile.absolutePath)
            put("curl", curlFile.absolutePath)
            put("libDir", libDir.absolutePath)
            put("ca", caFile.absolutePath)
            put("symlinkOk", symlinkOk)
            put("busyboxApplets", busyboxCount)
            put("probe", probe)
            put("installedAt", System.currentTimeMillis())
        }
        stampFile.writeText(json.toString(), Charsets.UTF_8)
    }

    // ---------------- ShellHost ----------------

    override fun bashPath(): String? = if (isReady()) bashFile.takeIf { it.isFile }?.absolutePath else null

    override fun curlPath(): String? = if (isReady()) curlFile.takeIf { it.isFile }?.absolutePath else null

    override fun environment(): Map<String, String> = buildEnvironment(includeFallbackBashEnv = !linksValid())

    /** Android 端才有 Shizuku/su 通道；桌面/测试的 `ShellHost` 默认 false。 */
    override fun supportsElevatedIdentity(): Boolean = true

    /**
     * `bash identity=shell/root`：交给 [DeviceShell] 的 root → Shizuku → App 身份链执行（docs/198 §二）。
     * 这一层只做类型转换与「实际身份」回报，执行/超时/进程树回收都在 DeviceShell。
     */
    override suspend fun runElevated(request: ElevatedRequest): ElevatedResult {
        val identity = when (request.identity) {
            "root" -> com.ai.assistance.showerclient.ShellIdentity.ROOT
            "shell" -> com.ai.assistance.showerclient.ShellIdentity.SHELL
            else -> return ElevatedResult(
                ok = false,
                identity = request.identity,
                exitCode = -1,
                timedOut = false,
                output = "",
                truncated = false,
                error = "unknown identity: ${request.identity} (expected shell or root)",
            )
        }
        val run = xin.dshaa.nativeapp.device.DeviceShell.runElevatedCommand(
            command = request.command,
            identity = identity,
            cwd = request.cwd,
            env = request.env,
            timeoutMs = request.timeoutMs,
            stdin = request.stdin,
        )
        return ElevatedResult(
            ok = run.ok,
            identity = run.identity,
            exitCode = run.exitCode,
            timedOut = run.timedOut,
            output = run.output,
            truncated = run.truncated,
            error = run.error,
            note = run.note,
        )
    }

    private fun buildEnvironment(includeFallbackBashEnv: Boolean): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        // 保留系统原始 PATH（含 /product/bin、/apex/...），前置 binDir，末尾/补缺放 busyboxBinDir，移除无意义的 nativeDir
        val inherited = System.getenv("PATH").orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
        val pathParts = buildList {
            if (binDir.isDirectory) add(binDir.absolutePath)
            inherited.forEach { if (it !in this) add(it) }
            for (sys in listOf("/system/bin", "/system/xbin", "/product/bin")) {
                if (sys !in this) add(sys)
            }
            if (busyboxBinDir.isDirectory && busyboxBinDir.absolutePath !in this) {
                add(busyboxBinDir.absolutePath)
            }
        }
        env["PATH"] = pathParts.joinToString(File.pathSeparator)
        if (libDir.isDirectory) {
            env["LD_LIBRARY_PATH"] = listOf(libDir.absolutePath, nativeDir).joinToString(File.pathSeparator)
        }
        env["HOME"] = homeDir.absolutePath
        env["TMPDIR"] = context.cacheDir.absolutePath
        env["LANG"] = "C.UTF-8"
        env["DSH_BIN"] = binDir.absolutePath
        env["DSH_NATIVE_DIR"] = nativeDir
        if (caFile.isFile) {
            env["CURL_CA_BUNDLE"] = caFile.absolutePath
            env["SSL_CERT_FILE"] = caFile.absolutePath
            env["SSL_CERT_DIR"] = certsDir.absolutePath
            env["OPENSSL_CONF"] = "/dev/null"
        }
        env["TERM"] = "dumb"
        if (bashEnvFile.isFile && (includeFallbackBashEnv || nodeFile.isFile)) {
            env["BASH_ENV"] = bashEnvFile.absolutePath
        }
        return env
    }

    private fun linksValid(): Boolean =
        linkPointsTo(File(binDir, "curl"), curlFile) &&
            linkPointsTo(File(binDir, "bash"), bashFile)

    private fun linkPointsTo(link: File, target: File): Boolean = runCatching {
        Files.isSymbolicLink(link.toPath()) &&
            Files.readSymbolicLink(link.toPath()).toString() == target.absolutePath &&
            target.isFile
    }.getOrDefault(false)

    companion object {
        private const val TAG = "DshToolchain"
        private const val BUFFER_SIZE = 64 * 1024
        private const val ASSET_ROOT = "toolchain"
        private const val ASSET_LIB_DIR = "$ASSET_ROOT/lib"
        private const val ASSET_CA = "$ASSET_ROOT/cacert.pem"

        /** 常用 BusyBox 工具补缺表（绝不包含 bash / curl / sh，避免覆盖真 GNU bash 与 curl）。 */
        private val BUSYBOX_APPLETS = listOf(
            "awk", "base64", "bzip2", "bunzip2", "bzcat", "cmp", "cut", "diff",
            "dirname", "basename", "du", "env", "expr", "find", "grep", "egrep",
            "fgrep", "gzip", "gunzip", "zcat", "head", "hexdump", "less", "md5sum",
            "od", "patch", "printf", "readlink", "realpath", "sed", "seq",
            "sha1sum", "sha256sum", "sha512sum", "sort", "split", "strings",
            "tail", "tar", "tee", "tr", "uniq", "unzip", "wc", "which",
            "xargs", "xxd", "xz", "unxz", "xzcat",
        )

        /** 打包内容变了就改这里，已装用户会重新解压。 */
        const val VERSION = "toolchain-bash5.3.20-curl8.x-busybox-ca2026.11"
    }
}
