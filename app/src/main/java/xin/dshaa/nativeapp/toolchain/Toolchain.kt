package xin.dshaa.nativeapp.toolchain

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xin.dshaa.core.tools.ShellHost
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption

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
    val caFile: File = File(root, "cacert.pem")
    val bashEnvFile: File = File(root, "bash_env.sh")
    val homeDir: File = File(context.filesDir, "home")
    private val stampFile: File = File(root, "install.json")

    /** 可执行体：只有 nativeLibraryDir 里这两份能 exec。 */
    val bashFile: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libbash.so")
    val curlFile: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libcurl-bin.so")

    private val nativeDir: String
        get() = context.applicationInfo.nativeLibraryDir

    /** 本机 ABI 有没有打包 bash/curl（模拟器 x86_64 没有，只能退回 `/system/bin/sh`）。 */
    fun bundledAvailable(): Boolean = bashFile.isFile && curlFile.isFile

    fun isInstalled(): Boolean =
        stampMatches() && libDir.isDirectory && caFile.isFile && (!bundledAvailable() || binDir.isDirectory)

    /** 解压 + 建软链 + 自检；任何失败都用 Result 带出去，不往调用方抛。 */
    suspend fun ensureInstalled(): Result<Status> = withContext(Dispatchers.IO) {
        runCatching {
            if (!bundledAvailable()) {
                // 模拟器/非 arm64：保持「没有工具箱」的事实，BashTool 会退回 /system/bin/sh。
                val status = Status(
                    installed = false,
                    bashPath = null,
                    curlPath = null,
                    libDir = libDir.absolutePath,
                    caFile = caFile.absolutePath,
                    symlinkOk = false,
                    probe = "unsupported abi: no libbash.so in nativeLibraryDir (${nativeDir})",
                )
                return@runCatching status
            }
            libDir.mkdirs()
            homeDir.mkdirs()
            binDir.mkdirs()
            extractAssets()
            val symlinkOk = createLinks()
            writeBashEnv(symlinkOk)
            val probe = runProbe()
            writeStamp(symlinkOk, probe)
            Log.i(TAG, "toolchain ready: bash=${bashFile.absolutePath} curl=${curlFile.absolutePath} symlink=$symlinkOk")
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

    /** 解 assets/toolchain 下的依赖库与 cacert.pem 到 files/toolchain/；同名等长就跳过（幂等）。 */
    private fun extractAssets() {
        // lib 下的依赖库：名字必须与 DT_NEEDED 完全一致（libreadline.so.8 之类），所以不能进 jniLibs。
        val libs = context.assets.list(ASSET_LIB_DIR).orEmpty().filter { it.isNotBlank() }
        for (name in libs) {
            val target = File(libDir, name)
            context.assets.open("$ASSET_LIB_DIR/$name").use { input ->
                copyIfChanged(input.available().toLong(), target) { out -> input.copyTo(out, BUFFER_SIZE) }
            }
        }
        context.assets.open(ASSET_CA).use { input ->
            val size = input.available().toLong()
            copyIfChanged(size, caFile) { out -> input.copyTo(out, BUFFER_SIZE) }
        }
    }

    private fun copyIfChanged(expectedSize: Long, target: File, write: (FileOutputStream) -> Unit) {
        if (target.isFile && target.length() == expectedSize && expectedSize > 0) return
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
     *
     * 软链在应用数据目录里**不是**被 exec 的那个 inode（内核跟随到 apk_data_file 的真文件），
     * 所以不受「数据目录 noexec」限制；失败则退回 `BASH_ENV` 函数。
     */
    private fun createLinks(): Boolean {
        val pairs = listOf(
            File(binDir, "bash") to bashFile,
            File(binDir, "curl") to curlFile,
        )
        var ok = true
        for ((link, target) in pairs) {
            val linked = runCatching {
                if (Files.isSymbolicLink(link.toPath())) {
                    val current = Files.readSymbolicLink(link.toPath()).toString()
                    if (current == target.absolutePath) return@runCatching true
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
            if (!linked) ok = false
        }
        return ok
    }

    /**
     * 软链不可用时的兜底：`BASH_ENV` 脚本里定义 `bash()` / `curl()` 函数。
     * 软链可用时脚本只定义 `dsh_toolchain_info`，不覆盖任何命令。
     */
    private fun writeBashEnv(symlinkOk: Boolean) {
        val text = buildString {
            appendLine("# 鲸歌打包工具箱（docs/187 §6.4）。由 Toolchain.kt 生成，升级会覆盖。")
            appendLine("DSH_NATIVE_DIR=\"\${DSH_NATIVE_DIR:-$nativeDir}\"")
            appendLine("DSH_TOOLCHAIN_LIB=\"\${DSH_TOOLCHAIN_LIB:-${libDir.absolutePath}}\"")
            if (!symlinkOk) {
                appendLine("curl() { \"\$DSH_NATIVE_DIR/libcurl-bin.so\" \"\$@\"; }")
                appendLine("bash() { \"\$DSH_NATIVE_DIR/libbash.so\" \"\$@\"; }")
            }
            appendLine("dsh_toolchain_info() { echo \"bash=\$DSH_NATIVE_DIR/libbash.so curl=\$DSH_NATIVE_DIR/libcurl-bin.so lib=\$DSH_TOOLCHAIN_LIB\"; }")
        }
        val changed = !bashEnvFile.isFile ||
            runCatching { bashEnvFile.readText(Charsets.UTF_8) != text }.getOrDefault(true)
        if (changed) bashEnvFile.writeText(text, Charsets.UTF_8)
    }

    /** 自检：直接 exec 一次 bash，确认 ELF/依赖库/软链都成立（结果进 JSON 与日志）。 */
    private fun runProbe(): String = runCatching {
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
        environment().forEach { (k, v) -> builder.environment()[k] = v }
        val process = builder.start()
        process.outputStream.close()
        val text = process.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val exit = process.waitFor()
        "exit=$exit\n${text.trim()}"
    }.getOrElse { "probe failed: ${it.javaClass.simpleName}: ${it.message}" }

    private fun stampMatches(): Boolean {
        if (!stampFile.isFile) return false
        return runCatching {
            val obj = Json.parseToJsonElement(stampFile.readText(Charsets.UTF_8)) as? JsonObject
                ?: return@runCatching false
            (obj["version"] as? kotlinx.serialization.json.JsonPrimitive)?.content == VERSION
        }.getOrDefault(false)
    }

    private fun writeStamp(symlinkOk: Boolean, probe: String) {
        val json = buildJsonObject {
            put("version", VERSION)
            put("bash", bashFile.absolutePath)
            put("curl", curlFile.absolutePath)
            put("libDir", libDir.absolutePath)
            put("ca", caFile.absolutePath)
            put("symlinkOk", symlinkOk)
            put("probe", probe)
            put("installedAt", System.currentTimeMillis())
        }
        stampFile.writeText(json.toString(), Charsets.UTF_8)
    }

    // ---------------- ShellHost ----------------

    override fun bashPath(): String? = bashFile.takeIf { it.isFile }?.absolutePath

    override fun curlPath(): String? = curlFile.takeIf { it.isFile }?.absolutePath

    override fun environment(): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        // bin 在最前：软链存在时 `command -v bash/curl` 有输出；nativeDir 兜绝对路径调用。
        env["PATH"] = listOf(binDir.absolutePath, nativeDir, "/system/bin", "/system/xbin")
            .joinToString(File.pathSeparator)
        if (libDir.isDirectory) {
            env["LD_LIBRARY_PATH"] = listOf(libDir.absolutePath, nativeDir).joinToString(File.pathSeparator)
        }
        env["HOME"] = homeDir.absolutePath
        env["TMPDIR"] = context.cacheDir.absolutePath
        env["DSH_BIN"] = binDir.absolutePath
        env["DSH_NATIVE_DIR"] = nativeDir
        if (caFile.isFile) {
            // curl（OpenSSL）认这两个；Termux 构建里 OPENSSL_CONF 缺省指向 /data/data/com.termux，
            // 设备上不存在会报 "Error loading config" —— 显式指到 /dev/null（P0 踩过）。
            env["CURL_CA_BUNDLE"] = caFile.absolutePath
            env["SSL_CERT_FILE"] = caFile.absolutePath
            env["SSL_CERT_DIR"] = File(root, "certs").absolutePath
            env["OPENSSL_CONF"] = "/dev/null"
        }
        env["TERM"] = "dumb"
        // 只有软链不可用时才进 BASH_ENV（脚本里定义 curl()/bash() 函数），避免污染正常环境。
        if (bashEnvFile.isFile && !bashEnvFileUsableViaPath()) {
            env["BASH_ENV"] = bashEnvFile.absolutePath
        }
        return env
    }

    private fun bashEnvFileUsableViaPath(): Boolean =
        runCatching { Files.isSymbolicLink(File(binDir, "curl").toPath()) }.getOrDefault(false) &&
            runCatching { Files.isSymbolicLink(File(binDir, "bash").toPath()) }.getOrDefault(false)

    companion object {
        private const val TAG = "DshToolchain"
        private const val BUFFER_SIZE = 64 * 1024
        private const val ASSET_ROOT = "toolchain"
        private const val ASSET_LIB_DIR = "$ASSET_ROOT/lib"
        private const val ASSET_CA = "$ASSET_ROOT/cacert.pem"

        /** 打包内容变了就改这里，已装用户会重新解压。 */
        const val VERSION = "toolchain-bash5.3.20-curl8.x-ca2026.11"
    }
}
