package xin.dshaa.nativeapp.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M87-H7：root 探测的解析/降级纯函数。
 *
 * 本轮**不实测 root 成功路径**（设备无 root，docs/182 §H7-4）：只锁住
 * 「哪种 su 写法成功怎么记」「超时=等待授权」「没 root 不降级」这些能在 JVM 验的部分。
 */
class RootProbeTest {

    @Test
    fun looksLikeRootOnlyForUidZero() {
        assertTrue(RootProbe.looksLikeRoot("uid=0(root) gid=0(root) groups=0(root)"))
        assertTrue(RootProbe.looksLikeRoot("uid=0 gid=0"))
        assertFalse(RootProbe.looksLikeRoot("uid=2000(shell) gid=2000(shell)"))
        assertFalse(RootProbe.looksLikeRoot("uid=10123(u0_a123)"))
        assertFalse(RootProbe.looksLikeRoot("uid=1000(system)"))
    }

    @Test
    fun probesInOrderAndRecordsWinningCommand() {
        val tried = mutableListOf<List<String>>()
        val result = RootProbe.probe { argv ->
            tried += argv
            SuOutput(stdout = if (argv[1] == "0") "uid=0(root)" else "", exitCode = 0)
        }
        assertTrue(result.available)
        assertEquals("su 0 id", result.command)
        assertEquals(listOf("su -c id", "su 0 id"), tried.map { RootProbe.commandText(it) })
    }

    @Test
    fun firstVariantWinsWithoutTryingOthers() {
        val tried = mutableListOf<String>()
        val result = RootProbe.probe { argv ->
            tried += RootProbe.commandText(argv)
            SuOutput(stdout = "uid=0(root) gid=0(root)", exitCode = 0)
        }
        assertTrue(result.available)
        assertEquals("su -c id", result.command)
        assertEquals(listOf("su -c id"), tried)
    }

    @Test
    fun noRootReturnsReadableUnavailableReason() {
        val result = RootProbe.probe { SuOutput(stderr = "su: permission denied", exitCode = 1) }
        assertFalse(result.available)
        assertTrue(result.reason, result.reason.contains("root 不可用"))
        assertTrue(result.reason, result.reason.contains("permission denied"))
    }

    @Test
    fun missingSuBinaryAlsoDegradesToUnavailable() {
        val result = RootProbe.probe { null }
        assertFalse(result.available)
        assertTrue(result.reason, result.reason.contains("root 不可用"))
    }

    @Test
    fun timeoutMeansWaitingForAuthorizationAndStopsProbing() {
        val tried = mutableListOf<String>()
        val result = RootProbe.probe { argv ->
            tried += RootProbe.commandText(argv)
            SuOutput(timedOut = true)
        }
        assertFalse(result.available)
        assertTrue(result.waiting)
        assertTrue(result.reason, result.reason.contains("等待 root 授权"))
        assertEquals("超时后不应再试其它写法，否则会重复弹授权框", listOf("su -c id"), tried)
    }

    @Test
    fun identityPrefersRootThenPermittedShizukuThenApp() {
        assertEquals("root", RootProbe.identity(root = true, shizuku = true, shizukuPermission = true))
        assertEquals("shizuku", RootProbe.identity(root = false, shizuku = true, shizukuPermission = true))
        assertEquals("app", RootProbe.identity(root = false, shizuku = true, shizukuPermission = false))
        assertEquals("app", RootProbe.identity(root = false, shizuku = false, shizukuPermission = false))
    }

    // ---- 候选 D：探测成功的 su 写法要真的用于执行 ----

    @Test
    fun execArgvUsesTheProbedSuVariant() {
        val standard = RootProbeResult(available = true, command = "su -c id")
        assertEquals(listOf("su", "-c", "cp a b"), RootProbe.execArgv(standard, "cp a b", null))

        val dashZero = RootProbeResult(available = true, command = "su -0 -c id")
        assertEquals(listOf("su", "-0", "-c", "cp a b"), RootProbe.execArgv(dashZero, "cp a b", null))

        // `su 0 id` 的探测写法没有 -c：执行任意命令必须补 sh -c，否则整条命令会被当成程序名
        val userId = RootProbeResult(available = true, command = "su 0 id")
        assertEquals(listOf("su", "0", "sh", "-c", "cp a b"), RootProbe.execArgv(userId, "cp a b", null))
    }

    @Test
    fun execArgvWithoutProbeFallsBackToStandardSuC() {
        val unavailable = RootProbeResult(available = false, reason = "no root")
        assertEquals(listOf("su", "-c", "id"), RootProbe.execArgv(unavailable, "id", null))
    }

    // ---- launcher：root 下的 uid 2000 身份 ----

    @Test
    fun execArgvPrefixesTheLauncherWithTheProbedVariant() {
        val launcher = "/data/user/0/xin.dshaa.nativeapp/files/bin/operit_shell_exec"
        val command = "CLASSPATH=/data/local/tmp/shower-server.jar app_process / com.ai.assistance.shower.Main xin.dshaa.nativeapp"

        val standard = RootProbeResult(available = true, command = "su -c id")
        val argv = RootProbe.execArgv(standard, command, launcher)
        val wrapped = ShellIdentityLauncher.wrapCommand(launcher, command)
        assertEquals(listOf("su", "-c", wrapped), argv)
        // 整条命令必须交给降权后的 sh -c，而不是 `launcher <命令>` 裸拼接。
        assertTrue(argv.last().contains("/system/bin/sh -c"))
        assertTrue(argv.last().contains("'CLASSPATH=/data/local/tmp/shower-server.jar"))

        val userId = RootProbeResult(available = true, command = "su 0 id")
        assertEquals(
            listOf("su", "0", "sh", "-c", wrapped),
            RootProbe.execArgv(userId, command, launcher),
        )
    }

    @Test
    fun execArgvWithoutLauncherKeepsPlainUidZeroCommand() {
        val standard = RootProbeResult(available = true, command = "su -c id")
        assertFalse(RootProbe.execArgv(standard, "cp a b", null).last().contains("operit_shell_exec"))
        assertFalse(RootProbe.execArgv(standard, "cp a b", "  ").last().contains("operit_shell_exec"))
    }

    // ---- 候选 C：root 探测缓存要能重探 ----

    @Test
    fun rootProbeCacheIsRetriedOnlyWhenItWasNotAvailable() {
        val root = RootProbeResult(available = true, command = "su -c id")
        assertFalse(RootProbe.shouldReprobe(root, Long.MAX_VALUE / 2))
        assertTrue(RootProbe.shouldReprobe(null, 0))

        val unavailable = RootProbeResult(available = false, reason = "no root")
        assertFalse(RootProbe.shouldReprobe(unavailable, RootProbe.REPROBE_UNAVAILABLE_MS - 1))
        assertTrue(RootProbe.shouldReprobe(unavailable, RootProbe.REPROBE_UNAVAILABLE_MS))

        val waiting = RootProbeResult(available = false, reason = "waiting", waiting = true)
        assertFalse(RootProbe.shouldReprobe(waiting, RootProbe.REPROBE_WAITING_MS - 1))
        assertTrue(RootProbe.shouldReprobe(waiting, RootProbe.REPROBE_WAITING_MS))
    }

    // ---- 候选 A/E 文案：错误必须说清 root / Shizuku / launcher 三件事 ----

    @Test
    fun channelReasonExplainsRootAndLauncherNotJustShizuku() {
        val text = ShellDiagnosis.channelReason(
            root = true,
            rootCommand = "su -c id",
            rootReason = "root 可用（su -c id）",
            shizuku = false,
            shizukuPermission = false,
            launcherAvailable = false,
            launcherReason = "当前 ABI=x86_64，launcher 只有 arm64-v8a 版本",
        )
        assertTrue(text, text.contains("root 可用"))
        assertTrue(text, text.contains("x86_64"))
        assertTrue(text, text.contains("uid 0"))
        assertTrue(text, text.contains("Shizuku"))

        val noRoot = ShellDiagnosis.channelReason(
            root = false,
            rootCommand = null,
            rootReason = "root 不可用：设备没有 root，或 su 授权被拒绝",
            shizuku = true,
            shizukuPermission = false,
            launcherAvailable = false,
            launcherReason = "当前 ABI=x86_64",
        )
        assertTrue(noRoot, noRoot.contains("root 不可用"))
        assertTrue(noRoot, noRoot.contains("已连接但未授权"))
        // launcher 只在 root 可用时才相关，别混进无 root 的文案
        assertFalse(noRoot, noRoot.contains("x86_64"))
    }

    @Test
    fun statusExplainsIdentityAndPermissions() {
        val root = RootProbe.status(true, "su -c id", shizuku = false, shizukuPermission = false, rootReason = "root 可用（su -c id）")
        assertEquals("root", root.defaultIdentity)
        assertTrue(root.reason.contains("root 可用"))

        val shizuku = RootProbe.status(false, null, shizuku = true, shizukuPermission = true, rootReason = "root 不可用")
        assertEquals("shizuku", shizuku.defaultIdentity)
        assertTrue(shizuku.reason.contains("Shizuku"))

        val app = RootProbe.status(false, null, shizuku = true, shizukuPermission = false, rootReason = "root 不可用")
        assertEquals("app", app.defaultIdentity)
        assertTrue(app.reason, app.reason.contains("未授权"))
    }

    @Test
    fun rootUnavailableJsonKeepsStructuredReason() {
        val json = ShellCapability.rootUnavailableJson()
        assertTrue(json.contains("\"ok\":false"))
        assertTrue(json.contains("\"reason\":\"root_unavailable\""))
        assertTrue(json.contains("message"))
    }

    @Test
    fun execIdentityUsesTheSameRootShizukuAppChain() {
        // M88-B：能力层的 shell.exec 身份链与执行顺序 / shell.status 的 default 一致。
        assertEquals("root", ShellCapability.execIdentity(root = true, shizuku = true, shizukuPermission = true))
        assertEquals("shizuku", ShellCapability.execIdentity(root = false, shizuku = true, shizukuPermission = true))
        assertEquals("app", ShellCapability.execIdentity(root = false, shizuku = true, shizukuPermission = false))
        assertEquals("app", ShellCapability.execIdentity(root = false, shizuku = false, shizukuPermission = false))
    }

    @Test
    fun mergedRootExecJsonIsStructuredAndExplainsTheMerge() {
        val json = ShellCapability.mergedRootExecJson()
        assertTrue(json.contains("\"ok\":false"))
        assertTrue(json.contains("\"reason\":\"merged_into_shell_exec\""))
        assertTrue(json.contains("root.exec 已并入 shell.exec"))
        assertTrue(json.contains("root → Shizuku → App"))
    }

    @Test
    fun execJsonEscapesOutputAndKeepsIdentity() {
        val json = ShellCapability.execJson(
            ok = false,
            exitCode = 1,
            stdout = "a\nb",
            stderr = "quote\"here",
            identity = "app",
        )
        assertTrue(json.contains("\"ok\":false"))
        assertTrue(json.contains("\"exitCode\":1"))
        assertTrue(json.contains("a\\nb"))
        assertTrue(json.contains("quote\\\"here"))
        assertTrue(json.contains("\"identity\":\"app\""))
    }

    @Test
    fun statusJsonHasContractFields() {
        val status = ShellStatus(
            root = false,
            rootCommand = null,
            shizuku = true,
            shizukuPermission = false,
            defaultIdentity = "app",
            reason = "Shizuku 已连接但未授权；当前只能用 App 身份",
        )
        val json = ShellCapability.statusJson(status)
        assertTrue(json.contains("\"root\":false"))
        assertTrue(json.contains("\"rootCommand\":null"))
        assertTrue(json.contains("\"shizuku\":true"))
        assertTrue(json.contains("\"shizukuPermission\":false"))
        assertTrue(json.contains("\"default\":\"app\""))
        assertTrue(json.contains("\"preference\":\"root>shizuku>app\""))
        assertTrue(json.contains("\"reason\""))
    }
}
