package xin.dshaa.core.plugins

import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class LoadingSpecTest {
    private fun declaration(tree: String) = Json.parseToJsonElement("""{"id":"demo","label":"示例","tree":$tree}""")

    @Test fun acceptsStaticBootBindingsAndMultipleDeclarations() {
        val raw = declaration("""{"type":"column","children":[{"type":"text","bind":"boot.message"},{"type":"progress"}]}""")
        val loading = assertNotNull(LoadingSpec.parse(raw).loading)
        assertEquals("demo", loading.id)
        val run = PluginModule.parseDryRun(buildJsonObject { put("loadings", buildJsonArray { add(raw) }) }.toString())
        assertNull(run.error)
        assertEquals(listOf(loading), run.loadings)
        assertTrue(PluginModule.dryRunScript(PluginModule.transform("export function apply(ctx) {}" )).contains("registerLoading: function(def)"))
    }

    @Test fun refusesExecutableAndExternalContentInsteadOfDroppingIt() {
        listOf(
            """{"type":"webview","html":"x"}""",
            """{"type":"image","src":"/sdcard/private"}""",
            """{"type":"text","bind":"chat.messages"}""",
            """{"type":"text","text":"x","onTap":"send"}""",
            """{"type":"textfield"}""",
            """{"type":"progress","value":2}""",
        ).forEach { tree ->
            val raw = declaration(tree)
            assertNull(LoadingSpec.parse(raw).loading, tree)
            val run = PluginModule.parseDryRun(buildJsonObject { put("loadings", buildJsonArray { add(raw) }) }.toString())
            assertNotNull(run.error, tree)
        }
    }

    @Test fun rejectsDuplicateAndOverLimitDeclarations() {
        val raw = declaration("""{"type":"progress"}""")
        val duplicate = PluginModule.parseDryRun(buildJsonObject { put("loadings", buildJsonArray { add(raw); add(raw) }) }.toString())
        assertNotNull(duplicate.error)
        val tooMany = PluginModule.parseDryRun(buildJsonObject { put("loadings", buildJsonArray {
            repeat(LoadingSpec.MAX_DECLARATIONS + 1) { add(raw) }
        }) }.toString())
        assertNotNull(tooMany.error)
    }

    @Test fun boundsTreeDepthNodesAndText() {
        var tree = """{"type":"text","text":"ok"}"""
        repeat(LoadingSpec.MAX_DEPTH + 1) { tree = """{"type":"column","children":[$tree]}""" }
        assertNotNull(LoadingSpec.parse(declaration(tree)).error)
        val nodes = List(LoadingSpec.MAX_NODES) { """{"type":"progress"}""" }.joinToString(",")
        assertNotNull(LoadingSpec.parse(declaration("""{"type":"column","children":[$nodes]}""")).error)
        assertNotNull(LoadingSpec.parse(declaration("""{"type":"text","text":"${"a".repeat(2001)}"}""")).error)
    }

    @Test fun persistsAndReloadsValidatedDeclarations() {
        val directory = Files.createTempDirectory("loading-registry").toFile()
        try {
            val loading = LoadingSpec.parse(declaration("""{"type":"text","bind":"boot.elapsed"}""")).loading!!
            val record = PluginRecord("demo", "demo", "1", "", directory.path, "index.js", "dsh", true, emptyList(), imports = emptyList(), error = null, installedAt = 1, loadings = listOf(loading))
            val registry = PluginRegistryFile(java.io.File(directory, "plugins.json"))
            registry.upsert(record)
            assertEquals(record.loadings, registry.find("demo")!!.loadings)
            val manifest = (PluginPackage.parseManifestText("""{"name":"demo","main":"index.js"}""", PluginFormat.DSH) as ManifestRead.Ok).manifest
            assertEquals(emptyList(), PluginReload.apply(record, manifest, PluginDryRun()).loadings)
            assertEquals(listOf(loading), PluginReload.apply(record, manifest, PluginDryRun(loadings = listOf(loading))).loadings)
        } finally {
            directory.deleteRecursively()
        }
    }
}
