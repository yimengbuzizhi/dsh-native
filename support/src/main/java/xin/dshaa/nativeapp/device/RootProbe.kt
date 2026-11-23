package xin.dshaa.nativeapp.device

/**
 * M87-H7：root 探测与 shell 身份（纯 Kotlin，JVM 可单测）。
 *
 * 设备上 `su` 的写法不止一种：Magisk/KernelSU/SuperSU 对 `su -c id` / `su 0 id` /
 * `su -0 -c id` 的支持不一样。这里按顺序探测（docs/182 §H7-2），记录哪种成功；
 * 超时要能区分「没有 root」和「授权弹窗还没点」。
 *
 * 纯函数只负责「怎么解析/怎么降级」，真正启动进程在 `DeviceShell`（Android）。
 */

/** 一次 `su` 调用的原始输出。 */
data class SuOutput(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = -1,
    /** 超时（通常是 Magisk 首次授权弹窗还没点）。 */
    val timedOut: Boolean = false,
)

/** 探测结果。[command] 是成功的那种写法（`su -c id` 等），展示/诊断用。 */
data class RootProbeResult(
    val available: Boolean,
    val command: String? = null,
    val output: String = "",
    val reason: String = "",
    /** 超时且可能正在等授权弹窗。 */
    val waiting: Boolean = false,
)

/** 「Shell 身份」页/能力 `shell.status` 的数据。 */
data class ShellStatus(
    val root: Boolean,
    val rootCommand: String?,
    val shizuku: Boolean,
    val shizukuPermission: Boolean,
    /** `root` / `shizuku` / `app`。 */
    val defaultIdentity: String,
    val reason: String,
)

object RootProbe {

    /** 依次尝试的写法：标准 `su -c` → uid 0 参数 → `-0 -c` 变体。 */
    val COMMANDS: List<List<String>> = listOf(
        listOf("su", "-c", "id"),
        listOf("su", "0", "id"),
        listOf("su", "-0", "-c", "id"),
    )

    /** 探测到「root 不可用」后隔多久允许重探（授权可能刚刚才被点允许）。 */
    const val REPROBE_UNAVAILABLE_MS: Long = 60_000L

    /** 探测超时（= 正在等授权框）后的重探间隔：用户随时可能点允许，但别每 5s 弹一次。 */
    const val REPROBE_WAITING_MS: Long = 60_000L

    /** 没探测到可用写法时的标准执行前缀（`su -c <命令>`）。 */
    val DEFAULT_SU_PREFIX: List<String> = listOf("su", "-c")

    private val WHITESPACE = Regex("""\s+""")

    /**
     * 缓存是否该重探：root 可用就一直用缓存；不可用/等待授权则按间隔重试，
     * 修掉「12s 超时后永久缓存 false，工具路径再也不重探」的问题。
     */
    fun shouldReprobe(cached: RootProbeResult?, ageMs: Long): Boolean {
        if (cached == null) return true
        if (cached.available) return false
        val interval = if (cached.waiting) REPROBE_WAITING_MS else REPROBE_UNAVAILABLE_MS
        return ageMs >= interval
    }

    /**
     * 把探测成功的写法还原成能执行任意命令的 argv 前缀。
     *
     * 探测命令固定以 `id` 结尾，去掉最后一个 token 就是前缀；`su 0 id` 这种「用户参数」
     * 写法没有 `-c`，执行任意命令时要显式补 `sh -c`（否则整条命令会被当成程序名）。
     */
    fun execPrefix(probeCommand: String?): List<String> {
        val tokens = probeCommand?.trim()?.split(WHITESPACE)?.filter { it.isNotBlank() }.orEmpty()
        if (tokens.size < 2) return DEFAULT_SU_PREFIX
        val prefix = tokens.dropLast(1)
        if (prefix.isEmpty()) return DEFAULT_SU_PREFIX
        return if (prefix.contains("-c")) prefix else prefix + listOf("sh", "-c")
    }

    /**
     * root 执行 argv：用探测成功的 su 写法（候选 D），需要 shell 身份时把 launcher 拼在命令前。
     *
     * [launcherPath] 为 null/空白 = launcher 不可用，保持旧的 `su -c <命令>`（uid 0）。
     */
    fun execArgv(
        probe: RootProbeResult,
        command: String,
        launcherPath: String?,
    ): List<String> {
        val effective = if (launcherPath.isNullOrBlank()) {
            command
        } else {
            ShellIdentityLauncher.wrapCommand(launcherPath, command)
        }
        return execPrefix(probe.command) + effective
    }

    const val WAITING_REASON = "正在等待 root 授权：若系统弹了授权框，请允许后点「重新检测」"
    const val UNAVAILABLE_REASON = "root 不可用：设备没有 root，或 su 授权被拒绝"

    fun commandText(argv: List<String>): String = argv.joinToString(" ")

    /** `id` 输出里真的有 `uid=0`（App 的 `uid=2000`/`uid=10xxx` 不算）。 */
    fun looksLikeRoot(output: String): Boolean =
        Regex("""(^|\s)uid=0(\s|$|\(|\z)""").containsMatchIn(output)

    /**
     * 按 [COMMANDS] 顺序探测。`run` 返回 null = 连 `su` 进程都起不来（没装/无权限）。
     * 遇到超时立刻停（再试其它写法会重复弹授权框）。
     */
    fun probe(run: (List<String>) -> SuOutput?): RootProbeResult {
        var lastStdout = ""
        var lastStderr = ""
        COMMANDS.forEach { argv ->
            val out = run(argv) ?: return@forEach
            lastStdout = out.stdout
            lastStderr = out.stderr
            if (out.timedOut) {
                return RootProbeResult(
                    available = false,
                    command = commandText(argv),
                    output = lastStdout.trim(),
                    reason = WAITING_REASON,
                    waiting = true,
                )
            }
            if (looksLikeRoot(out.stdout) || looksLikeRoot(out.stderr)) {
                return RootProbeResult(
                    available = true,
                    command = commandText(argv),
                    output = out.stdout.trim(),
                    reason = "root 可用（${commandText(argv)}）",
                )
            }
        }
        val detail = lastStderr.trim().ifBlank { lastStdout.trim() }.take(160)
        return RootProbeResult(
            available = false,
            output = lastStdout.trim(),
            reason = UNAVAILABLE_REASON + if (detail.isBlank()) "" else "（$detail）",
        )
    }

    /** 当前默认 shell 身份：root > 已授权的 Shizuku > App。 */
    fun identity(root: Boolean, shizuku: Boolean, shizukuPermission: Boolean): String = when {
        root -> "root"
        shizuku && shizukuPermission -> "shizuku"
        else -> "app"
    }

    fun status(
        root: Boolean,
        rootCommand: String?,
        shizuku: Boolean,
        shizukuPermission: Boolean,
        rootReason: String,
    ): ShellStatus {
        val identity = identity(root, shizuku, shizukuPermission)
        val reason = when (identity) {
            "root" -> rootReason.ifBlank { "root 可用" }
            "shizuku" -> "Shizuku 已授权（ADB 级 shell）"
            else -> if (shizuku) "Shizuku 已连接但未授权；当前只能用 App 身份" else rootReason.ifBlank { "只有 App 身份" }
        }
        return ShellStatus(
            root = root,
            rootCommand = rootCommand,
            shizuku = shizuku,
            shizukuPermission = shizukuPermission,
            defaultIdentity = identity,
            reason = reason,
        )
    }
}

/**
 * `shell.exec` / `shell.status` 的 JSON 结构（宿主与 JVM 测试共用）。
 *
 * 能力宿主返回给插件/调试通道的形状固定在这里，避免「root 不可用」时被宿主包装成
 * 普通异常而丢掉 `reason`。M88-B（docs/183 §2）起 `root.exec` 并入 `shell.exec`：
 * 按 id 直调返回 [mergedRootExecJson]。
 */
object ShellCapability {

    /** 旧能力 `root.exec` 已合并进 `shell.exec`（不再作为独立候选）。 */
    const val MERGED_ROOT_EXEC_REASON = "merged_into_shell_exec"

    /** 合并说明（能力宿主与调试 dump 共用同一句）。 */
    const val MERGED_MESSAGE = "root.exec 已并入 shell.exec（root → Shizuku → App 自动选择）"

    /** 设备没有 root / su 拒绝时的结构化原因（保留给 `shell.status` 与调试）。 */
    const val ROOT_UNAVAILABLE_REASON = "root_unavailable"

    fun execIdentity(root: Boolean, shizuku: Boolean, shizukuPermission: Boolean): String =
        RootProbe.identity(root, shizuku, shizukuPermission)

    /** 直接按 id 调 `root.exec` 时的结构化拒绝（不静默失败、也不假装还是独立能力）。 */
    fun mergedRootExecJson(): String =
        "{\"ok\":false,\"reason\":\"$MERGED_ROOT_EXEC_REASON\",\"message\":${quote(MERGED_MESSAGE)}}"

    fun rootUnavailableJson(message: String = RootProbe.UNAVAILABLE_REASON): String =
        "{\"ok\":false,\"reason\":\"$ROOT_UNAVAILABLE_REASON\",\"message\":${quote(message)}}"

    fun execJson(
        ok: Boolean,
        exitCode: Int,
        stdout: String,
        stderr: String,
        identity: String,
    ): String = buildString {
        append('{')
        append("\"ok\":").append(ok)
        append(",\"exitCode\":").append(exitCode)
        append(",\"stdout\":").append(quote(stdout))
        append(",\"stderr\":").append(quote(stderr))
        append(",\"identity\":").append(quote(identity))
        append('}')
    }

    fun statusJson(status: ShellStatus): String = buildString {
        append('{')
        append("\"root\":").append(status.root)
        append(",\"rootCommand\":").append(if (status.rootCommand == null) "null" else quote(status.rootCommand))
        append(",\"shizuku\":").append(status.shizuku)
        append(",\"shizukuPermission\":").append(status.shizukuPermission)
        append(",\"default\":").append(quote(status.defaultIdentity))
        // M88-B：身份链只此一条；插件/诊断据此知道 root → Shizuku → App 的自动顺序。
        append(",\"preference\":\"root>shizuku>app\"")
        append(",\"reason\":").append(quote(status.reason))
        append('}')
    }

    private fun quote(text: String): String = buildString {
        append('"')
        text.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u").append(ch.code.toString(16).padStart(4, '0')) else append(ch)
            }
        }
        append('"')
    }
}
