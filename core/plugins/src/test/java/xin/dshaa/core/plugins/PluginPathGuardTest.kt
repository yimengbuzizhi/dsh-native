package xin.dshaa.core.plugins

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PluginPathGuardTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    @Test
    fun resolvesInsidePathAndReturnsCanonicalFile() {
        val root = tempDir("dsh-guard")
        val file = File(root, "notes/a.txt").apply {
            parentFile.mkdirs()
            writeText("hi")
        }
        assertEquals(file.canonicalFile, PluginPathGuard.resolve(root, "notes/a.txt"))
        assertEquals(file.canonicalFile, PluginPathGuard.resolve(root, file.absolutePath))
        assertEquals(root.canonicalFile, PluginPathGuard.resolve(root, "."))
    }

    @Test
    fun rejectsParentTraversal() {
        val root = tempDir("dsh-guard")
        assertFailsWith<PluginPathException> { PluginPathGuard.resolve(root, "../outside.txt") }
        assertFailsWith<PluginPathException> { PluginPathGuard.resolve(root, "a/../../outside.txt") }
    }

    @Test
    fun rejectsAbsolutePathOutsideWorkspace() {
        val root = tempDir("dsh-guard")
        val outside = File(tempDir("dsh-outside"), "x.txt")
        assertFailsWith<PluginPathException> { PluginPathGuard.resolve(root, outside.absolutePath) }
    }

    @Test
    fun rejectsSymlinkThatEscapesWorkspace() {
        val root = tempDir("dsh-guard")
        val outside = tempDir("dsh-outside")
        val link = File(root, "escape")
        val created = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        if (!created) return // Windows 没开开发者模式时建不了软链接，跳过这条（CI 上也不该失败）
        assertFailsWith<PluginPathException> { PluginPathGuard.resolve(root, "escape/secret.txt") }
    }

    @Test
    fun normalizesAndroidStorageAliases() {
        assertEquals("/storage/emulated/0/x.txt", PluginPathGuard.normalizeAlias("/sdcard/x.txt"))
        assertEquals("/storage/emulated/0/x.txt", PluginPathGuard.normalizeAlias("/mnt/sdcard/x.txt"))
        assertEquals("/storage/emulated/0/x.txt", PluginPathGuard.normalizeAlias("/storage/self/primary/x.txt"))
        assertEquals("/storage/emulated/0", PluginPathGuard.normalizeAlias("/sdcard"))
        // 前缀必须整段匹配，不能把 /sdcard2 也换掉
        assertEquals("/sdcard2/x.txt", PluginPathGuard.normalizeAlias("/sdcard2/x.txt"))
    }
}
