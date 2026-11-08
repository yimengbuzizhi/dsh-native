package xin.dshaa.core.plugins

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginRegistryBackupTest {

    @Test
    fun mergeAddsOnlyMissingPluginsAndRewritesPath() {
        val dir = Files.createTempDirectory("dsh-plugin-merge-").toFile()
        val file = File(dir, "plugins.json")
        file.writeText(
            """{"version":1,"plugins":[{"id":"p1","name":"One","version":"1","path":"/old/p1","enabled":true,"tools":["a"]}]}""",
        )
        val registry = PluginRegistryFile(file)
        val backup = """
            {"version":1,"plugins":[
              {"id":"p1","name":"One","version":"1","path":"p1","enabled":true,"tools":["a"]},
              {"id":"p2","name":"Two","version":"2","path":"p2","enabled":false,"tools":["b"],
               "config":"{\"city\":\"Beijing\"}"}
            ]}
        """.trimIndent()

        val added = registry.mergeFromRegistryJson(backup) { id -> "/new/$id" }

        assertEquals(listOf("p2"), added)
        val records = registry.list()
        assertEquals(2, records.size)
        assertEquals("/old/p1", records.first { it.id == "p1" }.path)
        val p2 = records.first { it.id == "p2" }
        assertEquals("/new/p2", p2.path)
        assertEquals("Two", p2.name)
        assertEquals("2", p2.version)
        assertTrue(!p2.enabled)
        assertEquals("{\"city\":\"Beijing\"}", p2.config)

        // 再合并同一份：没有新 id，清单不变。
        assertEquals(emptyList(), registry.mergeFromRegistryJson(backup) { id -> "/new/$id" })
        assertEquals(2, registry.list().size)
    }

    @Test
    fun mergeIgnoresBrokenRegistryText() {
        val dir = Files.createTempDirectory("dsh-plugin-merge-bad-").toFile()
        val file = File(dir, "plugins.json")
        val registry = PluginRegistryFile(file)
        assertEquals(emptyList(), registry.mergeFromRegistryJson("not json") { "/new/$it" })
        assertTrue(registry.list().isEmpty())
    }
}
