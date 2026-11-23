package xin.dshaa.core.plugins

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginPackageTest {

    @Test
    fun readsDshPackageAtRoot() {
        val zip = zipOf(
            "package.json" to """{"name":"dsh-tool-todo","version":"0.2.0","description":"Todo list","main":"lib/index.js"}""",
            "lib/index.js" to "export const apply = () => {}",
        )
        val files = (PluginPackage.read(zip) as ArchiveRead.Ok).files
        val parsed = PluginPackage.parse(files) as PackageParse.Ok
        assertEquals("dsh-tool-todo", parsed.manifest.id)
        assertEquals("0.2.0", parsed.manifest.version)
        assertEquals("lib/index.js", parsed.manifest.main)
        assertEquals(PluginFormat.DSH, parsed.manifest.format)
        assertEquals("", parsed.root)
    }

    @Test
    fun stripsSingleTopFolder() {
        val zip = zipOf(
            "my-plugin/package.json" to """{"name":"@acme/plugin-x","version":"1.0.0"}""",
            "my-plugin/lib/index.js" to "exports.apply = function(){}",
        )
        val files = (PluginPackage.read(zip) as ArchiveRead.Ok).files
        val parsed = PluginPackage.parse(files) as PackageParse.Ok
        assertEquals("my-plugin", parsed.root)
        assertEquals("@acme/plugin-x", parsed.manifest.id)
        assertTrue(parsed.files.any { it.path == "lib/index.js" })
    }

    @Test
    fun defaultsMainForDshAndOperit() {
        val dsh = PluginPackage.parse(
            listOf(
                PluginFile("package.json", """{"name":"p1"}""".toByteArray()),
                PluginFile("lib/index.js", "".toByteArray()),
            ),
        ) as PackageParse.Ok
        assertEquals("lib/index.js", dsh.manifest.main)

        val operit = PluginPackage.parse(
            listOf(
                PluginFile("manifest.json", """{"id":"toolpkg.demo","name":"Demo","main":"index.js"}""".toByteArray()),
                PluginFile("index.js", "".toByteArray()),
            ),
        ) as PackageParse.Ok
        assertEquals(PluginFormat.OPERIT, operit.manifest.format)
        assertEquals("toolpkg.demo", operit.manifest.id)
    }

    @Test
    fun parsesToolPkgConfigSchemaAndDefaults() {
        val parsed = PluginPackage.parse(
            listOf(
                PluginFile(
                    "manifest.json",
                    """
                    {"id":"toolpkg.cfg","name":"Cfg","main":"index.js",
                     "config":{"schema":{"type":"object","properties":{"city":{"type":"string","default":"Shanghai"}}},
                               "default":{"city":"Beijing"}}}
                    """.trimIndent().toByteArray(),
                ),
                PluginFile("index.js", "".toByteArray()),
            ),
        ) as PackageParse.Ok
        assertEquals(
            """{"type":"object","properties":{"city":{"type":"string","default":"Shanghai"}}}""",
            parsed.manifest.configSchema?.toString(),
        )
        assertEquals("""{"city":"Beijing"}""", parsed.manifest.configDefaults?.toString())
    }

    @Test
    fun rejectsMissingManifestAndMissingMain() {
        val noManifest = PluginPackage.parse(listOf(PluginFile("index.js", "".toByteArray())))
        assertTrue(noManifest is PackageParse.Failed)

        val noMain = PluginPackage.parse(listOf(PluginFile("package.json", """{"name":"p2"}""".toByteArray())))
        assertTrue(noMain is PackageParse.Failed)
        assertTrue((noMain as PackageParse.Failed).error.contains("main"))
    }

    @Test
    fun rejectsAmbiguousTopLevelDirectories() {
        val parsed = PluginPackage.parse(
            listOf(
                PluginFile("a/package.json", """{"name":"a"}""".toByteArray()),
                PluginFile("a/lib/index.js", "".toByteArray()),
                PluginFile("b/package.json", """{"name":"b"}""".toByteArray()),
                PluginFile("b/lib/index.js", "".toByteArray()),
            ),
        )
        assertTrue(parsed is PackageParse.Failed)
        assertTrue((parsed as PackageParse.Failed).error.contains("多个顶层"))
    }

    @Test
    fun rejectsUnsafeZipPathsAndJunkIsIgnored() {
        val evil = zipOf(
            "package.json" to """{"name":"p"}""",
            "lib/index.js" to "",
            "../escape.js" to "x",
        )
        assertTrue(PluginPackage.read(evil) is ArchiveRead.Failed)

        val junk = zipOf(
            "package.json" to """{"name":"p"}""",
            "lib/index.js" to "",
            "__MACOSX/._index.js" to "junk",
            "lib/.DS_Store" to "junk",
        )
        val files = (PluginPackage.read(junk) as ArchiveRead.Ok).files
        assertTrue(files.none { it.path.contains("__MACOSX") || it.path.endsWith(".DS_Store") })
    }

    @Test
    fun detectsOwnershipFromDependenciesAndClientExport() {
        val deps = zipOf(
            "package.json" to """{"name":"node-tool","main":"lib/index.js","dependencies":{"@deepseek-ai/cordis":"^4.0.1"}}""",
            "lib/index.js" to "",
        )
        val node = PluginPackage.parse((PluginPackage.read(deps) as ArchiveRead.Ok).files) as PackageParse.Ok
        assertEquals(PluginOwnership.NODE, node.manifest.ownership)

        val client = zipOf(
            "package.json" to """{"name":"ui-pet","main":"lib/index.js","exports":{"./client":"./lib/client.js"}}""",
            "lib/index.js" to "",
            "lib/client.js" to "",
        )
        val summary = PluginPackage.summarize((PluginPackage.read(client) as ArchiveRead.Ok).files)!!
        assertEquals(PluginOwnership.NODE, summary.ownership)
        assertEquals(true, summary.hasClient)
        assertTrue(summary.signals.any { it.contains("./client") })

        val native = zipOf("package.json" to """{"name":"whale-plugin","main":"index.js"}""", "index.js" to "")
        val nativeSummary = PluginPackage.summarize((PluginPackage.read(native) as ArchiveRead.Ok).files)!!
        assertEquals(PluginOwnership.NATIVE, nativeSummary.ownership)
    }

    @Test
    fun registryPersistsOwnershipAndDefaultsOldRecordsToNative() {
        val dir = createTempDir()
        try {
            val file = File(dir, "plugins.json")
            val registry = PluginRegistryFile(file)
            val record = PluginRecord(
                id = "node-ui", name = "node-ui", version = "1.0.0", description = "",
                path = File(dir, "plugins/node-ui").absolutePath, main = "lib/index.js",
                format = PluginFormat.DSH.label, enabled = false, tools = emptyList(), imports = emptyList(),
                error = null, installedAt = 1L, ownership = PluginOwnership.NODE,
            )
            registry.upsert(record)
            assertEquals(PluginOwnership.NODE, registry.find("node-ui")?.ownership)
            assertTrue(file.readText().contains("\"ownership\":\"node\""))

            // 旧清单没有 ownership：读回按原生，不迁移、不改开关。
            file.writeText(
                """{"version":1,"plugins":[{"id":"old","name":"old","path":"/x","main":"index.js","format":"DSH","enabled":true,"tools":[],"imports":[],"installedAt":1}]}""",
            )
            assertEquals(PluginOwnership.NATIVE, PluginRegistryFile(file).find("old")?.ownership)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fiveMbClientBundleBelowNewEntryLimitIsReadable() {
        // docs/188 P4：单文件上限从 4MB 提到 32MB；5MB 的 client bundle 必须能读进来。
        val big = ByteArray(5 * 1024 * 1024) { 'x'.code.toByte() }
        val file = File.createTempFile("plugin-big", ".zip")
        try {
            ZipOutputStream(file.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("package.json"))
                out.write("""{"name":"big","main":"client.js"}""".toByteArray())
                out.closeEntry()
                out.putNextEntry(ZipEntry("client.js"))
                out.write(big)
                out.closeEntry()
            }
            val read = PluginPackage.read(file)
            assertTrue(read is ArchiveRead.Ok)
            assertEquals(5L * 1024 * 1024, (read as ArchiveRead.Ok).files.first { it.path == "client.js" }.bytes.size.toLong())
        } finally {
            file.delete()
        }
    }

    @Test
    fun normalizePathRejectsAbsoluteAndDotDot() {
        assertEquals("a/b.js", PluginPackage.normalizePath("./a/b.js"))
        assertEquals("a/b.js", PluginPackage.normalizePath("a\\b.js"))
        assertEquals(null, PluginPackage.normalizePath("/etc/passwd"))
        assertEquals(null, PluginPackage.normalizePath("C:/x"))
        assertEquals(null, PluginPackage.normalizePath("a/../../b"))
    }

    @Test
    fun readManifestFromInstalledDirectory() {
        val dir = createTempDir()
        try {
            File(dir, "manifest.json").writeText(
                """
                {"id":"com.example.cfg","name":"Cfg","version":"1.2.0","description":"d","main":"index.js",
                 "permissions":["fs:workspace"],
                 "config":{"schema":{"type":"object","properties":{"city":{"type":"string","default":"Shanghai"}}},
                           "default":{"city":"Beijing"}}}
                """.trimIndent(),
            )
            val read = PluginPackage.readManifest(dir) as ManifestRead.Ok
            assertEquals("com.example.cfg", read.manifest.id)
            assertEquals("Cfg", read.manifest.name)
            assertEquals("1.2.0", read.manifest.version)
            assertEquals("index.js", read.manifest.main)
            assertEquals(PluginFormat.OPERIT, read.manifest.format)
            assertEquals(listOf("fs:workspace"), read.manifest.permissions)
            assertEquals("""{"city":"Beijing"}""", read.manifest.configDefaults?.toString())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readManifestPrefersPackageJsonAndReportsProblems() {
        val dir = createTempDir()
        try {
            assertTrue(PluginPackage.readManifest(dir) is ManifestRead.Missing)
            File(dir, "manifest.json").writeText("""{"id":"a.b","name":"A","main":"index.js"}""")
            File(dir, "package.json").writeText("""{"name":"pkg-b","version":"2.0.0","main":"lib/index.js"}""")
            val read = PluginPackage.readManifest(dir) as ManifestRead.Ok
            assertEquals("pkg-b", read.manifest.id)
            assertEquals(PluginFormat.DSH, read.manifest.format)

            File(dir, "package.json").writeText("{not json")
            val broken = PluginPackage.readManifest(dir)
            assertTrue(broken is ManifestRead.Failed)
            assertTrue((broken as ManifestRead.Failed).error.contains("package.json"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun registryRoundTripAndMutations() {
        val dir = createTempDir()
        try {
            val file = File(dir, "plugins.json")
            val registry = PluginRegistryFile(file)
            assertTrue(registry.list().isEmpty())

            val record = PluginRecord(
                id = "dsh-tool-todo",
                name = "dsh-tool-todo",
                version = "0.2.0",
                description = "todo",
                path = File(dir, "plugins/dsh-tool-todo").absolutePath,
                main = "lib/index.js",
                format = PluginFormat.DSH.label,
                enabled = true,
                tools = listOf("todo_write"),
                imports = emptyList(),
                error = null,
                installedAt = 1L,
                configSchema = """{"type":"object","properties":{"city":{"type":"string"}}}""",
                config = """{"city":"Beijing"}""",
                configDefaults = """{"city":"Shanghai"}""",
            )
            registry.upsert(record)
            assertEquals(1, registry.list().size)
            assertEquals("todo_write", registry.list().first().tools.first())
            val loaded = registry.list().first()
            assertEquals(record.configSchema, loaded.configSchema)
            assertEquals("""{"city":"Beijing"}""", loaded.config)
            assertEquals("""{"city":"Shanghai"}""", loaded.configDefaults)

            registry.setEnabled(record.id, false)
            assertEquals(false, registry.find(record.id)?.enabled)

            registry.upsert(record.copy(version = "0.3.0", error = "boom"))
            assertEquals("0.3.0", registry.find(record.id)?.version)
            assertEquals("boom", registry.find(record.id)?.error)

            registry.remove(record.id)
            assertTrue(registry.list().isEmpty())

            file.writeText("{not json")
            assertTrue(PluginRegistryFile(file).list().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun createTempDir(): File = File.createTempFile("plugins-test", "").let { file ->
        file.delete()
        file.mkdirs()
        file
    }

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("plugin", ".zip")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
        return file
    }
}
