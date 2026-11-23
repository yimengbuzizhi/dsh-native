package xin.dshaa.nativeapp.chat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dshaa.core.plugins.PluginPreset
import xin.dshaa.core.plugins.PresetCodec
import java.io.File
import java.nio.file.Files

/**
 * P3-d：预设落盘（`files/node/data/presets.json`）。
 *
 * 用例覆盖：目录/文件位置、保存→重读、同名覆盖保留 createdAt、active 记录、
 * 删除、坏文件不炸（容错交给 [PresetCodec]）、以及「中途被杀不留半截 JSON」（写临时文件 + rename）。
 */
class PresetStoreTest {

    private val root: File = Files.createTempDirectory("dsh-preset-test").toFile()

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun preset(id: String, name: String = id, tools: List<String> = listOf("bash")) = PluginPreset(
        id = id,
        name = name,
        tools = tools,
        modelProviderId = "deepseek",
        promptSections = listOf("hello-plugin/greeting"),
        permissions = mapOf("pkg" to "fs:workspace"),
    )

    @Test
    fun fileLivesUnderNodeData_andRoundTrips() {
        val store = PresetStore(root)
        assertEquals(File(File(root, "node/data"), "presets.json"), store.file)
        assertTrue(store.presets().isEmpty())

        assertTrue(store.save(preset("coding", "写代码")).isSuccess)
        assertTrue("必须真的落盘", store.file.isFile)

        val reopened = PresetStore(root)
        val loaded = reopened.presets().single()
        assertEquals("coding", loaded.id)
        assertEquals(listOf("bash"), loaded.tools)
        assertEquals("deepseek", loaded.modelProviderId)
        assertEquals(mapOf("pkg" to "fs:workspace"), loaded.permissions)
        assertNull(reopened.activeId())
    }

    @Test
    fun saveTwice_keepsCreatedAt_andUpdatesFields() {
        val store = PresetStore(root)
        val first = store.save(preset("coding", "写代码", listOf("bash", "write"))).getOrThrow()
        Thread.sleep(5)
        val second = store.save(preset("coding", "写代码 v2", listOf("bash"))).getOrThrow()
        assertEquals(1, store.presets().size)
        assertEquals(first.createdAt, second.createdAt)
        assertTrue(second.updatedAt >= first.updatedAt)
        assertEquals(listOf("bash"), store.presets().single().tools)
        assertEquals("写代码 v2", store.presets().single().name)
    }

    @Test
    fun activeIsPersisted_andClearedOnDelete() {
        val store = PresetStore(root)
        store.save(preset("a"))
        store.save(preset("b"))
        store.setActive("b")
        assertEquals("b", PresetStore(root).activeId())

        assertTrue(store.delete("b"))
        assertNull("删掉套用中的预设时 active 必须一起清掉", PresetStore(root).activeId())
        assertEquals(listOf("a"), store.presets().map { it.id })
        assertFalse(store.delete("nope"))
    }

    @Test
    fun badFile_isTolerated_andErrorsSurface() {
        val dir = File(File(root, "node"), "data").apply { mkdirs() }
        File(dir, "presets.json").writeText("{ not json", Charsets.UTF_8)
        val store = PresetStore(root)
        assertTrue(store.presets().isEmpty())
        assertTrue(store.errors().isNotEmpty())
        // 坏文件之后还能正常保存（下一次写入覆盖成合法 JSON）。
        assertTrue(store.save(preset("after")).isSuccess)
        assertEquals(listOf("after"), PresetStore(root).presets().map { it.id })
    }

    @Test
    fun noHalfWrittenFile_leftBehind() {
        val store = PresetStore(root)
        store.save(preset("a"))
        val leftovers = store.dir.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
        assertTrue("写盘要原子：不应留 .tmp（实际 ${leftovers.map { it.name }}）", leftovers.isEmpty())
    }

    @Test
    fun blankName_isRejectedBySlugButStoreAlsoGuards() {
        val store = PresetStore(root)
        val result = store.save(PluginPreset(id = "", name = "   ").copy(id = ""))
        assertTrue("没有名字/id 必须失败，不能写出一个怪文件", result.isFailure)
        assertNull(PresetCodec.slug("   "))
    }
}
