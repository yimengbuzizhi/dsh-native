package xin.dshaa.nativeapp.device

/**
 * shell 身份 launcher（从 Operit 移植）的纯逻辑：ABI 选择、ELF 校验、命令拼接、错误文案。
 *
 * 为什么需要它：只有 root、没有 Shizuku 时，`su -c <cmd>` 得到 uid 0 / magisk 域；
 * `shower-server.jar` 里的 FakeContext 自称 `com.android.shell`（uid 2000），
 * DisplayManagerService 会校验 calling uid 与包名，建虚拟屏/`launchApp` 因此被拒。
 * launcher 以 root 启动后 `setgroups` → `setgid/setuid 2000` → `setcon("u:r:shell:s0")`
 * → `execvp` 目标命令，使 `app_process` 真正以 shell 身份运行。
 *
 * 本文件不依赖 Android，JVM 单测直接覆盖；安装/执行在 [DeviceShell]。
 */

/** launcher 的可用状态（安装失败时 [reason] 是给日志/工具错误看的真实原因）。 */
data class LauncherStatus(
    val available: Boolean,
    val path: String?,
    val reason: String,
) {
    companion object {
        fun ready(path: String): LauncherStatus =
            LauncherStatus(true, path, "shell 身份 launcher 已安装（uid 2000）")

        fun missing(reason: String): LauncherStatus = LauncherStatus(false, null, reason)
    }
}

object ShellIdentityLauncher {

    /** launcher 只有 aarch64 产物：只允许设备首选项是这个 ABI 时才用。 */
    const val ARM64_ABI: String = "arm64-v8a"

    /** APK 内 jniLibs/arm64-v8a 下的文件名（可执行 ELF，按 .so 命名只为随 ABI 打包）。 */
    const val LIB_NAME: String = "libshower_shell_exec.so"

    /** 安装到 `filesDir/bin` 后的真实文件名（`su -c` 执行的就是它）。 */
    const val BINARY_NAME: String = "operit_shell_exec"

    /** 设备首选项 ABI 是否是 launcher 支持的 arm64-v8a（32 位 userspace 不算）。 */
    fun isSupportedAbi(abis: List<String>): Boolean = abis.firstOrNull() == ARM64_ABI

    /** APK 解压后的 nativeLibraryDir 里的 launcher 来源路径。 */
    fun sourceFile(nativeLibraryDir: java.io.File): java.io.File =
        java.io.File(nativeLibraryDir, LIB_NAME)

    /** 安装目标：`<filesDir>/bin/operit_shell_exec`（`su -c` 执行的就是这个绝对路径）。 */
    fun installedFile(filesDir: java.io.File): java.io.File =
        java.io.File(java.io.File(filesDir, "bin"), BINARY_NAME)

    /**
     * 校验读到的文件头是不是 AArch64 可执行 ELF。
     *
     * 只读前 20 字节（ELF ident + e_type + e_machine），避免把 x86_64 模拟器上误打包的
     * 文件当 launcher 执行。ET_EXEC(2) / ET_DYN(3) 都接受（Android PIE 产物是 ET_DYN）。
     */
    fun isAarch64Executable(header: ByteArray): Boolean {
        if (header.size < 20) return false
        val magicOk = header[0] == 0x7f.toByte() &&
            header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() &&
            header[3] == 'F'.code.toByte()
        if (!magicOk) return false
        if (header[4] != 2.toByte()) return false // ELFCLASS64
        if (header[5] != 1.toByte()) return false // little-endian
        val type = (header[16].toInt() and 0xff) or ((header[17].toInt() and 0xff) shl 8)
        if (type != 2 && type != 3) return false
        val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        return machine == 183 // EM_AARCH64
    }

    /**
     * root 分支的包装：`'<launcher>' /system/bin/sh -c '<原命令>'`。
     *
     * 为什么不是 `"$launcherPath $command"`（Operit 的裸拼接）：那股命令只让**第一段**降权，
     * `cp a b; id` / `ps | grep x` / `cd /data && ./run.sh` 里 `;`、`|`、`&&`、重定向、`$()`
     * 都由外层 root sh 解释 → 后半段仍是 uid 0；`cd`/`export` 这类 builtin 更会因为
     * `execvp("cd")` 直接失败。这里把整条命令交给降权后的 `/system/bin/sh -c`，保证
     * 「identity=SHELL 时整条命令都是 uid 2000」，与旧 `su -c <command>` 的可组合语义一致。
     */
    fun wrapCommand(launcherPath: String, command: String): String =
        shQuote(launcherPath) + " /system/bin/sh -c " + shQuote(command)

    /** POSIX 单引号转义（`'` → `'\''`），供 launcher 路径与整条命令使用。 */
    fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

/**
 * 「为什么没有 shell 身份」的可读文案（虚拟屏/工具错误用）。
 *
 * 旧文案只有「Shizuku 是否已授权？」，会把 root 探测失败、launcher 架构不符、Shizuku 未装
 * 全部误导成 Shizuku 授权问题。这里把 root / Shizuku / launcher 三件事都写出来。
 */
object ShellDiagnosis {

    fun channelReason(
        root: Boolean,
        rootCommand: String?,
        rootReason: String,
        shizuku: Boolean,
        shizukuPermission: Boolean,
        launcherAvailable: Boolean,
        launcherReason: String,
    ): String {
        val rootText = if (root) {
            "root 可用（${rootCommand ?: "su"}）"
        } else {
            "root 不可用（${rootReason.ifBlank { RootProbe.UNAVAILABLE_REASON }}）"
        }
        val shizukuText = when {
            shizuku && shizukuPermission -> "Shizuku 已授权"
            shizuku -> "Shizuku 已连接但未授权"
            else -> "Shizuku 未连接（未安装或 binder 未就绪）"
        }
        return buildString {
            append(rootText).append("；").append(shizukuText)
            if (root && !launcherAvailable && !(shizuku && shizukuPermission)) {
                append("；root 下的 shell 身份 launcher 不可用（")
                append(launcherReason)
                append("），已退回 uid 0，虚拟屏的 DisplayManager 校验可能失败")
            }
        }
    }
}
