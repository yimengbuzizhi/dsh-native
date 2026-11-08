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
