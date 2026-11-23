package xin.dshaa.nativeapp.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * shell 身份 launcher 的纯逻辑单测（JVM，无需 root/设备）。
 *
 * 真机 root 路径本机无法验证（设备无 root）；这里锁住能在 JVM 确认的部分：
 * ABI 选择、ELF 架构校验、`su -c "<launcher> <命令>"` 拼接、launcher 缺失时不静默。
 */
class ShellIdentityLauncherTest {

    /** 与仓库内 jniLibs/arm64-v8a/libshower_shell_exec.so 前 20 字节一致。 */
    private val aarch64Header = byteArrayOf(
        0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(),
        2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        3, 0, 0xb7.toByte(), 0,
    )

    @Test
    fun recognizesTheBundledAarch64ExecutableHeader() {
        assertTrue(ShellIdentityLauncher.isAarch64Executable(aarch64Header))
    }

    @Test
    fun rejectsWrongArchitectureOrTruncatedHeader() {
        val x86_64 = aarch64Header.copyOf().also { it[18] = 0x3e; it[19] = 0x00 }
        assertFalse(ShellIdentityLauncher.isAarch64Executable(x86_64))

        val elf32 = aarch64Header.copyOf().also { it[4] = 1 }
        assertFalse(ShellIdentityLauncher.isAarch64Executable(elf32))

        val notElf = aarch64Header.copyOf().also { it[0] = 0x00 }
        assertFalse(ShellIdentityLauncher.isAarch64Executable(notElf))

        assertFalse(ShellIdentityLauncher.isAarch64Executable(aarch64Header.copyOf(10)))
        assertFalse(ShellIdentityLauncher.isAarch64Executable(ByteArray(0)))
    }

    @Test
    fun onlyUsesTheLauncherWhenThePreferredAbiIsArm64() {
        assertTrue(ShellIdentityLauncher.isSupportedAbi(listOf("arm64-v8a", "armeabi-v7a")))
        assertFalse(ShellIdentityLauncher.isSupportedAbi(listOf("x86_64")))
        assertFalse(ShellIdentityLauncher.isSupportedAbi(listOf("armeabi-v7a")))
        assertFalse(ShellIdentityLauncher.isSupportedAbi(emptyList()))
    }

    @Test
    fun resolvesThePackagedSourceAndTheInstalledPath() {
        val nativeDir = java.io.File("/data/app/~~x/xin.dshaa.nativeapp-1/lib/arm64")
        assertEquals(
            "/data/app/~~x/xin.dshaa.nativeapp-1/lib/arm64/libshower_shell_exec.so",
            ShellIdentityLauncher.sourceFile(nativeDir).path.replace('\\', '/'),
        )

        val filesDir = java.io.File("/data/user/0/xin.dshaa.nativeapp/files")
        assertEquals(
            "/data/user/0/xin.dshaa.nativeapp/files/bin/operit_shell_exec",
            ShellIdentityLauncher.installedFile(filesDir).path.replace('\\', '/'),
        )
        assertEquals("operit_shell_exec", ShellIdentityLauncher.installedFile(filesDir).name)
    }

    @Test
    fun wrapsTheWholeCommandIntoADroppedPrivilegeShell() {
        val launcher = "/data/user/0/xin.dshaa.nativeapp/files/bin/operit_shell_exec"
        assertEquals(
            "'$launcher' /system/bin/sh -c 'cp /tmp/a /data/local/tmp/a'",
            ShellIdentityLauncher.wrapCommand(launcher, "cp /tmp/a /data/local/tmp/a"),
        )
        assertEquals(
            "'$launcher' /system/bin/sh -c 'CLASSPATH=/data/local/tmp/shower-server.jar app_process / com.ai.assistance.shower.Main xin.dshaa.nativeapp'",
            ShellIdentityLauncher.wrapCommand(
                launcher,
                "CLASSPATH=/data/local/tmp/shower-server.jar app_process / com.ai.assistance.shower.Main xin.dshaa.nativeapp",
            ),
        )
    }

    @Test
    fun compoundCommandsStayInsideTheDroppedPrivilegeShell() {
        val launcher = "/data/user/0/x/files/bin/operit_shell_exec"
        val wrapped = ShellIdentityLauncher.wrapCommand(launcher, "cp a b; id")
        // 整条命令（含 ;）必须在单引号里，不能让它落到外层 root sh。
        assertTrue(wrapped.endsWith(" -c 'cp a b; id'"))
        assertEquals(
            "'/data/user/0/x/files/bin/operit_shell_exec' /system/bin/sh -c 'ps -A | grep shower'",
            ShellIdentityLauncher.wrapCommand(launcher, "ps -A | grep shower"),
        )
    }

    @Test
    fun singleQuotesAndSpacesInTheLauncherPathAreEscaped() {
        assertEquals("'a'\\''b'", ShellIdentityLauncher.shQuote("a'b"))
        val wrapped = ShellIdentityLauncher.wrapCommand("/data/x y/operit_shell_exec", "echo 'hi'")
        assertEquals("'/data/x y/operit_shell_exec' /system/bin/sh -c 'echo '\\''hi'\\'''", wrapped)
        // 转义后的字符串再被 POSIX shell 读一遍必须还原成原命令（单引号闭合/重开）。
        assertEquals("echo 'hi'", wrapped.substringAfter(" -c ").trim().removeSurrounding("'").replace("'\\''", "'"))
    }

    @Test
    fun missingLauncherCarriesARealReasonForLogsAndToolErrors() {
        val missing = LauncherStatus.missing("当前 ABI=x86_64，launcher 只有 arm64-v8a 版本")
        assertFalse(missing.available)
        assertEquals(null, missing.path)
        assertTrue(missing.reason, missing.reason.contains("x86_64"))

        val ready = LauncherStatus.ready("/data/user/0/x/files/bin/operit_shell_exec")
        assertTrue(ready.available)
        assertEquals("/data/user/0/x/files/bin/operit_shell_exec", ready.path)
    }
}
