package xin.dshaa.nativeapp.data

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xin.dshaa.core.plugins.*
import java.io.File
import java.nio.file.Files

class PluginDevelopmentTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun templatesUseValidNativeManifestAndModuleExports() {
        listOf("tool", "surface-shell", "webview-shell", "loading").forEach { template ->
            val dir = File(temp.root, template)
            PluginDevScaffold.create(dir, "local.demo", template)
            val parsed = PluginPackage.readManifest(dir) as ManifestRead.Ok
            assertEquals("index.js", parsed.manifest.main)
            assertEquals(if (template == "tool") emptyList<String>() else listOf("ui"), parsed.manifest.permissions)
            val module = PluginModule.transform(File(dir, "index.js").readText())
            assertTrue(module.warnings.toString(), module.warnings.isEmpty())
            assertTrue(module.exports.any { it.exported == "apply" })
            assertTrue(module.imports.isEmpty())
            val zip = File(temp.root, "$template.zip")
            PluginDevPackage.pack(dir, zip)
            val archive = PluginPackage.read(zip) as ArchiveRead.Ok
            assertTrue(PluginPackage.parse(archive.files) is PackageParse.Ok)
        }
    }

    @Test fun surfaceScaffoldButtonUsesRendererLabel() {
        val source = PluginDevScaffold.source("local.surface", "surface-shell")
        assertTrue(source.contains("type: 'button', label: '返回原生界面'"))
        assertFalse(source.contains("type: 'button', text:"))
    }

    @Test fun manifestConfigControlsRegistrationWithoutExportedConfig() {
        val schema = """{"type":"object","properties":{"enabled":{"type":"boolean","default":false},"title":{"type":"string","default":"schema title"}}}"""
        val observed = mutableListOf<String>()
        val run = PluginStore.configuredDryRun("{}", """{"enabled":true}""", schema) { config ->
            observed += config
            val enabled = PluginSchema.parseObject(config)["enabled"]?.jsonPrimitive?.content == "true"
            PluginDryRun(tools = if (enabled) listOf(PluginTool("from_manifest", "test")) else emptyList())
        }
        assertEquals(listOf("from_manifest"), run.tools.map { it.name })
        assertEquals(schema, run.configSchema)
        val effective = PluginSchema.parseObject(observed.last())
        assertEquals("true", effective.getValue("enabled").jsonPrimitive.content)
        assertEquals("schema title", effective.getValue("title").jsonPrimitive.content)
    }

    @Test fun manifestSchemaPrunesOldKeysAndKeepsUserOverrides() {
        val schema = """{"type":"object","properties":{"enabled":{"type":"boolean","default":true},"title":{"type":"string"}}}"""
        val observed = mutableListOf<String>()
        PluginStore.configuredDryRun("""{"enabled":false,"obsolete":true}""", """{"title":"manifest title"}""", schema) { config ->
            observed += config
            PluginDryRun()
        }
        val effective = PluginSchema.parseObject(observed.last())
        assertFalse(effective.containsKey("obsolete"))
        assertEquals("false", effective.getValue("enabled").jsonPrimitive.content)
        assertEquals("manifest title", effective.getValue("title").jsonPrimitive.content)
    }

    @Test fun exportedSchemaWinsAndOldConfigIsPrunedBeforeSecondApply() {
        val exportedSchema = """{"type":"object","properties":{"enabled":{"type":"boolean","default":true}}}"""
        val manifestSchema = """{"type":"object","properties":{"obsolete":{"type":"boolean"}}}"""
        val observed = mutableListOf<String>()
        val run = PluginStore.configuredDryRun("""{"enabled":false,"obsolete":true}""", "{}", manifestSchema) { config ->
            observed += config
            val values = PluginSchema.parseObject(config)
            PluginDryRun(configSchema = exportedSchema,
                tools = if (values["obsolete"] != null) listOf(PluginTool("stale_tool", "bad")) else emptyList())
        }
        assertEquals(exportedSchema, run.configSchema)
        assertEquals(2, observed.size)
        assertEquals("""{"enabled":false}""", observed.last())
        assertTrue(run.tools.isEmpty())
    }

    @Test fun scaffoldRefusesOverwriteAndReservedIds() {
        val dir = temp.newFolder("existing")
        File(dir, "keep").writeText("user content")
        assertThrows(IllegalArgumentException::class.java) { PluginDevScaffold.create(dir, "local.demo", "tool") }
        assertEquals("user content", File(dir, "keep").readText())
        assertThrows(IllegalArgumentException::class.java) { PluginDevScaffold.create(File(temp.root, "reserved"), "builtin.shell.native", "surface-shell") }
    }

    @Test fun directoryPackagingRejectsLargeFilesAndEscapingSymlinks() {
        val dir = temp.newFolder("package")
        File(dir, "large.js").outputStream().use { it.write(ByteArray(PluginPackage.MAX_ENTRY_BYTES.toInt() + 1)) }
        assertThrows(IllegalArgumentException::class.java) { PluginDevPackage.pack(dir, File(temp.root, "large.zip")) }
        val linked = temp.newFolder("linked")
        val secret = temp.newFile("outside.txt").apply { writeText("private") }
        // Some Windows test hosts lack symbolic-link privilege; keep the size check independent.
        val created = runCatching { Files.createSymbolicLink(File(linked, "escape").toPath(), secret.toPath()) }.isSuccess
        if (created) assertThrows(IllegalArgumentException::class.java) { PluginDevPackage.pack(linked, File(temp.root, "linked.zip")) }
        assertEquals("private", secret.readText())
    }

    @Test fun replacementFailureRestoresOldPackageAndCleansStaging() {
        val root = temp.newFolder("plugins")
        val old = File(root, "demo").apply { mkdir() }
        File(old, "index.js").writeText("old")
        PluginInstallTransaction(root, "demo").use { transaction ->
            File(transaction.staging, "index.js").writeText("new")
            assertThrows(IllegalStateException::class.java) {
                transaction.commit { error("registry write failed") }
            }
            assertEquals("old", File(old, "index.js").readText())
        }
        assertEquals(listOf("demo"), root.list()!!.toList())
    }

    @Test fun successfulReplacementAndFirstInstallKeepOnlyNewPackage() {
        val root = temp.newFolder("install")
        repeat(2) { version ->
            PluginInstallTransaction(root, "demo").use { transaction ->
                File(transaction.staging, "index.js").writeText("v$version")
                transaction.commit { assertEquals("v$version", File(transaction.target, "index.js").readText()) }
            }
            assertEquals(listOf("demo"), root.list()!!.toList())
        }
    }

    @Test fun failedFirstInstallDoesNotLeavePackage() {
        val root = temp.newFolder("failed")
        PluginInstallTransaction(root, "demo").use { transaction ->
            File(transaction.staging, "index.js").writeText("new")
            assertThrows(IllegalStateException::class.java) { transaction.commit { error("fail") } }
        }
        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun failedFirstInstallReportsIncompleteRollbackWhenCleanupFails() {
        val root = temp.newFolder("failed-cleanup")
        PluginInstallTransaction(root, "demo", deletePackage = { false }).use { transaction ->
            File(transaction.staging, "index.js").writeText("new")
            val failure = assertThrows(IllegalStateException::class.java) {
                transaction.commit { error("metadata failed") }
            }
            assertTrue(failure.message.orEmpty().contains("回滚未完成"))
            assertTrue(failure.message.orEmpty().contains("新包残留"))
            assertEquals("metadata failed", failure.cause?.message)
            assertTrue(File(root, "demo/index.js").isFile)
        }
    }

    @Test fun transactionRejectsTraversalAndExistingSymlinkTargets() {
        val root = temp.newFolder("guarded")
        assertThrows(IllegalArgumentException::class.java) { PluginInstallTransaction(root, "../sessions") }
        val outside = temp.newFolder("sessions")
        File(outside, "keep").writeText("session")
        val created = runCatching { Files.createSymbolicLink(File(root, "demo").toPath(), outside.toPath()) }.isSuccess
        if (created) assertThrows(IllegalArgumentException::class.java) { PluginInstallTransaction(root, "demo") }
        assertEquals("session", File(outside, "keep").readText())
    }
}
