package xin.dshaa.nativeapp.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xin.dshaa.core.plugins.LoadingSpec
import xin.dshaa.core.plugins.ManifestRead
import xin.dshaa.core.plugins.PluginLoading
import xin.dshaa.core.plugins.PluginModule
import xin.dshaa.core.plugins.PluginPackage
import java.io.File

class PluginLoadingDevelopmentTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun loadingScaffoldHasStaticDeclarationCompatibleWithScopedPackageIds() {
        val directory = File(temp.root, "loading")
        PluginDevScaffold.create(directory, "@local/loading-page", "loading")
        val manifest = (PluginPackage.readManifest(directory) as ManifestRead.Ok).manifest
        assertEquals("@local/loading-page", manifest.id)
        assertEquals(listOf("ui"), manifest.permissions)
        val source = File(directory, "index.js").readText()
        val module = PluginModule.transform(source)
        assertTrue(module.warnings.toString(), module.warnings.isEmpty())
        assertTrue(module.exports.any { it.exported == "apply" })
        assertTrue(module.imports.isEmpty())
        // The literal handed to the real JS API is JSON, so validate that exact tree on the JVM too.
        val raw = source.substringAfter("ctx.ui.registerLoading(").substringBeforeLast(");")
        val parsed = LoadingSpec.parse(Json.parseToJsonElement(raw))
        assertNull(parsed.error)
        assertEquals("startup", parsed.loading!!.id)
        assertTrue(parsed.loading!!.treeJson.contains("boot.message"))
        assertTrue(parsed.loading!!.treeJson.contains("boot.elapsed"))
        assertFalse(source.contains("registerShell"))
        assertFalse(source.contains("mount:"))
    }

    @Test fun listAndTestLoadingMetadataDistinguishesRegistrationFromSelectionAndOmitsTree() {
        val loading = PluginLoading("startup", "示例加载页", "sensitive static text must stay out of metadata")
        val result = PluginDevTools.loadingMetadata("@local/demo", listOf(loading)).single().jsonObject
        assertEquals("startup", result.getValue("id").jsonPrimitive.content)
        assertEquals("示例加载页", result.getValue("label").jsonPrimitive.content)
        assertEquals(LoadingSpec.VERSION, result.getValue("protocol").jsonPrimitive.content)
        assertEquals("@local/demo#startup", result.getValue("selectionValue").jsonPrimitive.content)
        assertEquals(setOf("id", "label", "protocol", "selectionValue"), result.keys)
        assertFalse(result.toString().contains("sensitive"))
        assertFalse(result.containsKey("active"))
        assertFalse(result.containsKey("selected"))
        assertFalse(PluginDevTools.loadingMetadata(null, listOf(loading)).single().jsonObject.containsKey("selectionValue"))
        assertTrue(PluginDevTools.loadingMetadata("demo", emptyList()).isEmpty())
        assertEquals(LoadingSpec.MAX_DECLARATIONS, PluginDevTools.loadingMetadata("demo",
            List(LoadingSpec.MAX_DECLARATIONS + 1) { loading.copy(id = "loading$it") }).size)
    }
}
