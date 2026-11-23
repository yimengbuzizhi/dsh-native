package xin.dshaa.nativeapp.node

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * docs/187 §九 第 7 项：zip 导入健壮性（JVM 用例，不装 APK）。
 *
 * - 根目录没有统一父目录 → 用 zip 文件名做目录名（不再全落 `plugin/` 互相覆盖）；
 * - 路径禁止 `..` / 绝对路径；`main` 做 canonical 校验；
 * - 整包 / 单条目 / 总解压大小有限制。
 */
class NodePluginInstallerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun dataDir(): File = temp.newFolder("node-data")

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun rootlessZipUsesZipFileNameAsDirectory() {
        val data = dataDir()
        val installer = NodePluginInstaller(data)
        val bytes = zip(
            "package.json" to """{"name":"dsh-enhanced-hello","main":"index.js"}""",
            "index.js" to "export const name = 'hello'",
        )
        val installed = installer.installZip(bytes, "dsh-enhanced-hello.zip").getOrThrow()
        assertEquals("./plugins/dsh-enhanced-hello/index.js", installed.specifier)
        assertFalse("入口路径不能带 ..（Android /data/user/0 软链踩过）", installed.specifier.contains(".."))
        assertTrue(File(data, "plugins/dsh-enhanced-hello/index.js").isFile)
        assertEquals(listOf("./plugins/dsh-enhanced-hello/index.js"), installer.modules())
        assertEquals("dsh-enhanced-hello", installed.id)
    }

    @Test
    fun commonRootIsStrippedAndMainIsHonored() {
        val data = dataDir()
        val installer = NodePluginInstaller(data)
        val bytes = zip(
            "hello/package.json" to """{"main":"lib/entry.mjs"}""",
            "hello/lib/entry.mjs" to "export default {}",
            "hello/README.md" to "readme",
        )
        val installed = installer.installZip(bytes, "whatever.zip").getOrThrow()
        assertEquals("./plugins/hello/lib/entry.mjs", installed.specifier)
        assertFalse(installed.specifier.contains(".."))
        assertTrue(File(data, "plugins/hello/lib/entry.mjs").isFile)
    }

    @Test
    fun pathTraversalIsRejected() {
        val installer = NodePluginInstaller(dataDir())
        val bytes = zip("../evil.js" to "boom")
        val result = installer.installZip(bytes, "evil.zip")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains(".."))
    }

    @Test
    fun absolutePathEntryIsRejected() {
        val installer = NodePluginInstaller(dataDir())
        val result = installer.installZip(zip("/data/local/tmp/evil.js" to "boom"), "evil.zip")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("绝对路径"))
    }

    @Test
    fun mainEscapingPluginDirIsRejected() {
        val data = dataDir()
        val installer = NodePluginInstaller(data)
        val bytes = zip(
            "package.json" to """{"main":"../../escape.js"}""",
            "index.js" to "ok",
        )
        val result = installer.installZip(bytes, "escape.zip")
        assertTrue("main 越界必须拒绝", result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("越界"))
    }

    @Test
    fun oversizedEntryIsRejected() {
        val installer = NodePluginInstaller(dataDir(), NodePluginInstaller.Limits(maxEntryBytes = 1024))
        val big = ByteArray(2048) { 'x'.code.toByte() }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("huge.bin"))
            z.write(big)
            z.closeEntry()
        }
        val result = installer.installZip(out.toByteArray(), "huge.zip")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("单个文件太大"))
    }

    @Test
    fun sixteenMbClientBundleIsAccepted() {
        // docs/188 P4：旧上限 4MB 会把 npm 真包 dsh-client-ui-pet 的 16.5MB client.js 挡掉；
        // 这里用 5MB（>4MB）证明阈值已经抬高，又不用在 JVM 里分配 32MB。
        val data = temp.newFolder("node-data-big")
        val installer = NodePluginInstaller(data)
        val big = ByteArray(5 * 1024 * 1024) { 'a'.code.toByte() }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("package.json"))
            z.write("""{"name":"big-client","main":"client.js"}""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("client.js"))
            z.write(big)
            z.closeEntry()
        }
        val installed = installer.installZip(out.toByteArray(), "big-client.zip").getOrThrow()
        assertEquals("big-client", installed.id)
        assertEquals(5L * 1024 * 1024, File(data, "plugins/big-client/client.js").length())
    }

    @Test
    fun totalExtractedBytesAndEntryCountAreCapped() {
        val totalLimited = NodePluginInstaller(
            temp.newFolder("node-data-total"),
            NodePluginInstaller.Limits(maxTotalBytes = 3 * 1024 * 1024, maxEntryBytes = 2 * 1024 * 1024),
        )
        val twoMb = ByteArray(2 * 1024 * 1024) { 'b'.code.toByte() }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("a.bin")); z.write(twoMb); z.closeEntry()
            z.putNextEntry(ZipEntry("b.bin")); z.write(twoMb); z.closeEntry()
        }
        val total = totalLimited.installZip(out.toByteArray(), "total.zip")
        assertTrue(total.isFailure)
        assertTrue(total.exceptionOrNull()!!.message!!.contains("解压后太大"))

        val countLimited = NodePluginInstaller(temp.newFolder("node-data-count"), NodePluginInstaller.Limits(maxEntries = 2))
        val count = countLimited.installZip(zip("a.js" to "1", "b.js" to "2", "c.js" to "3"), "count.zip")
        assertTrue(count.isFailure)
        assertTrue(count.exceptionOrNull()!!.message!!.contains("条目太多"))
    }

    @Test
    fun zipBombRatioIsRejected() {
        val installer = NodePluginInstaller(
            dataDir(),
            NodePluginInstaller.Limits(maxRatio = 1, maxTotalBytes = 8 * 1024 * 1024),
        )
        val bomb = ByteArray(1024 * 1024) { 0 }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("bomb.bin"))
            z.write(bomb)
            z.closeEntry()
        }
        val result = installer.installZip(out.toByteArray(), "bomb.zip")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("压缩比异常"))
    }

    @Test
    fun manifestStoresOwnershipMetadataAndCanUninstall() {
        val data = dataDir()
        val installer = NodePluginInstaller(data)
        installer.installZip(
            zip(
                "package.json" to """{"name":"@acme/pet","version":"0.1.10","main":"lib/index.js","dsh":{"client":{"platform":"web"}}}""",
                "lib/index.js" to "export function apply(){}",
                "lib/client.js" to "client",
            ),
            "pet.zip",
        ).getOrThrow()
        val entry = installer.entries().single()
        assertEquals("@acme/pet", entry.id)
        assertEquals("0.1.10", entry.version)
        assertEquals(true, entry.hasClient)
        assertTrue(File(data, "plugins/pet/lib/client.js").isFile)

        assertEquals("@acme/pet", installer.uninstall("@acme/pet").getOrThrow())
        assertTrue(installer.entries().isEmpty())
        assertTrue(!File(data, "plugins/pet").exists())
    }

    @Test
    fun manifestKeepsExistingModules() {
        val data = dataDir()
        val installer = NodePluginInstaller(data)
        installer.installZip(zip("index.js" to "1"), "first.zip").getOrThrow()
        installer.installZip(zip("index.js" to "2"), "second.zip").getOrThrow()
        assertEquals(listOf("./plugins/first/index.js", "./plugins/second/index.js"), installer.modules())
        val manifest = Json.parseToJsonElement(File(data, "node-host-plugins.json").readText()).jsonObject
        assertNotNull(manifest["plugins"])
        assertTrue((manifest["plugins"] as kotlinx.serialization.json.JsonArray).size == 2)
    }

    @Test
    fun sanitizeKeepsZipStemSafe() {
        val data = dataDir()
        val installer = NodePluginInstaller(data)
        installer.installZip(zip("index.js" to "x"), "/sdcard/My Plug in!.zip").getOrThrow()
        assertTrue(File(data, "plugins/My_Plug_in/index.js").isFile)
        assertEquals(listOf("./plugins/My_Plug_in/index.js"), installer.modules())
    }

    @Test
    fun emptyZipIsRejected() {
        val installer = NodePluginInstaller(dataDir())
        assertEquals("插件包是空的", runCatching { installer.installZip(ByteArray(0), "x.zip").getOrThrow() }
            .exceptionOrNull()?.message)
        assertFalse(installer.installZip(ByteArray(0), "x.zip").isSuccess)
        assertTrue(installer.modules().isEmpty())
    }
}
