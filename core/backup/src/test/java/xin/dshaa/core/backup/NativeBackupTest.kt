package xin.dshaa.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeBackupTest {

    @Test
    fun exportWritesAllSectionsAndInspectReadsThem() {
        val source = sourceTree()
        val zip = File(source.root, "backup.zip")
        zip.outputStream().use { out ->
            NativeBackup.export(
                out = out,
                sessionsRoot = source.sessions,
                settings = testSettings(),
                pluginsRoot = source.plugins,
                pluginRegistry = source.registry,
                attachmentsRoot = source.attachments,
                appVersion = "0.2.0-test",
                now = 1_800_000_000_000L,
            )
        }

        val manifest = NativeBackup.inspect(zip)
        assertEquals(2, manifest.sessions.size)
        assertTrue(manifest.sessions.any { it.id == "s-legacy" && it.legacy })
        assertEquals(1, manifest.plugins.size)
        assertEquals("p1", manifest.plugins.first().id)
        assertEquals(6, manifest.settingsKeys)
        assertEquals(1, manifest.attachmentsFiles)
        assertTrue(manifest.includesSettings)
        assertTrue(manifest.includesPlugins)
        assertTrue(manifest.includesAttachments)
        assertFalse(manifest.toJson()["includes"]!!.jsonObject["credentials"]!!.jsonPrimitive.content.toBoolean())

        ZipFile(zip).use { zf ->
            assertNotNull(zf.getEntry(NativeBackup.MANIFEST_ENTRY))
            assertNotNull(zf.getEntry(NativeBackup.SETTINGS_ENTRY))
            assertNotNull(zf.getEntry("sessions/--sdcard--/s1/session.v4.jsonl.zstd"))
            assertNotNull(zf.getEntry("sessions/--sdcard--/s-legacy/imported.json"))
            assertNotNull(zf.getEntry("plugins/plugins.json"))
            assertNotNull(zf.getEntry("plugins/p1/index.js"))
            assertNotNull(zf.getEntry("attachments/objects/ab/${sha256(ATTACHMENT_BYTES)}"))
            val registry = zf.getInputStream(zf.getEntry("plugins/plugins.json")).use {
                Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject
            }
            val record = registry["plugins"]!!.let { it as kotlinx.serialization.json.JsonArray }[0].jsonObject
            // 备份里的清单不写本机绝对路径，只留插件 id；恢复时 App 再重写成新路径。
            assertEquals("p1", record["path"]!!.jsonPrimitive.content)
            assertFalse(zip.readBytes().toString(Charsets.ISO_8859_1).contains("/data/user/0/"))
        }
    }

    @Test
    fun exportCarriesSessionCwdsForRestoreHints() {
        val source = sourceTree()
        val zip = File(source.root, "with-cwd.zip")
        zip.outputStream().use { out ->
            NativeBackup.export(
                out = out,
                sessionsRoot = source.sessions,
                settings = testSettings(),
                pluginsRoot = source.plugins,
                pluginRegistry = source.registry,
                attachmentsRoot = source.attachments,
                sessionCwds = mapOf(
                    "s1" to "/storage/emulated/0/work",
                    "s-legacy" to "/storage/emulated/0/old",
                ),
            )
        }

        val manifest = NativeBackup.inspect(zip)
        assertEquals("/storage/emulated/0/work", manifest.sessions.first { it.id == "s1" }.cwd)
        assertEquals("/storage/emulated/0/old", manifest.sessions.first { it.id == "s-legacy" }.cwd)
    }

    @Test
    fun oldManifestWithoutCwdStillParses() {
        val dir = temp("old-cwd")
        val zip = File(dir, "old.zip")
        val manifest = BackupManifest(
            createdAt = 1L,
            appVersion = "old",
            sessions = listOf(BackupSessionEntry("s1", "--sdcard--", false, 4, "00")),
            plugins = emptyList(),
            settingsKeys = 0,
            attachmentsFiles = 0,
            attachmentsBytes = 0,
            totalBytes = 0,
            includesSettings = false,
            includesPlugins = false,
            includesAttachments = false,
        )
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry(NativeBackup.MANIFEST_ENTRY))
            zos.write(manifest.toJson().toString().toByteArray())
            zos.closeEntry()
        }
        assertEquals("", NativeBackup.inspect(zip).sessions.single().cwd)
    }

    @Test
    fun restoreSessionsCopiesBytesAndSkipsExisting() {
        val source = sourceTree()
        val zip = File(source.root, "backup.zip")
        zip.outputStream().use {
            NativeBackup.export(it, source.sessions, testSettings(), source.plugins, source.registry, source.attachments)
        }

        val target = temp("restore")
        val report = NativeBackup.restoreSessions(zip, target)
        assertEquals(2, report.restored)
        assertEquals(0, report.failed)

        val writable = File(target, "--sdcard--/s1/session.v4.jsonl.zstd")
        assertContentEquals(SESSION_BYTES, writable.readBytes())
        assertFalse(File(target, "--sdcard--/s1/imported.json").exists())
        assertTrue(File(target, "--sdcard--/s-legacy/imported.json").isFile)

        // 恢复 = 原文件直搬，没有 imported.json 的会话可直接续聊。
        val again = NativeBackup.restoreSessions(zip, target)
        assertEquals(0, again.restored)
        assertEquals(2, again.skipped)
    }

    @Test
    fun restoreSessionsHonorsSelectedIdsPerWorkspace() {
        val source = sourceTree()
        // 把 s1 挪到另一个工作区，模拟「多个工作区，只恢复其中一个」。
        val moved = File(source.sessions, "--work--/s1")
        moved.parentFile?.mkdirs()
        assertTrue(File(source.sessions, "--sdcard--/s1").renameTo(moved))
        val zip = File(source.root, "filter.zip")
        zip.outputStream().use { NativeBackup.export(it, source.sessions, testSettings()) }

        val picked = temp("restore-picked")
        val only = NativeBackup.restoreSessions(zip, picked, setOf("s1"))
        assertEquals(1, only.restored)
        assertEquals(0, only.failed)
        assertEquals(1, only.deselected)
        assertEquals(listOf("s1"), only.ids)
        assertTrue(File(picked, "--work--/s1/session.v4.jsonl.zstd").isFile)
        assertFalse(File(picked, "--sdcard--/s-legacy").exists())

        // 一个都没选：一条也不恢复，全部计入未选（不算失败）。
        val none = NativeBackup.restoreSessions(zip, temp("restore-none"), emptySet())
        assertEquals(0, none.restored)
        assertEquals(2, none.deselected)
        assertEquals(0, none.failed)

        // null = 旧的「全恢复」行为。
        val all = NativeBackup.restoreSessions(zip, temp("restore-all"), null)
        assertEquals(2, all.restored)
        assertEquals(0, all.deselected)
    }

    @Test
    fun restoreSessionsRejectsTraversalAndBadHash() {
        val target = temp("evil")
        val manifest = BackupManifest(
            createdAt = 1L,
            appVersion = "",
            sessions = listOf(BackupSessionEntry("s1", "--sdcard--", false, 4, "00")),
            plugins = emptyList(),
            settingsKeys = 0,
            attachmentsFiles = 0,
            attachmentsBytes = 0,
            totalBytes = 0,
            includesSettings = false,
            includesPlugins = false,
            includesAttachments = false,
        )
        val zip = File(target.parentFile, "evil-${System.nanoTime()}.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry(NativeBackup.MANIFEST_ENTRY))
            zos.write(manifest.toJson().toString().toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("sessions/--sdcard--/../../evil/session.v4.jsonl.zstd"))
            zos.write(byteArrayOf(1, 2, 3))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("sessions/--sdcard--/s1/session.v4.jsonl.zstd"))
            zos.write(byteArrayOf(1, 2, 3))
            zos.closeEntry()
        }

        val report = NativeBackup.restoreSessions(zip, target)
        assertEquals(0, report.restored)
        assertEquals(2, report.failed)
        assertTrue(report.errors.any { it.contains("非法") })
        assertFalse(File(target.parentFile, "evil/session.v4.jsonl.zstd").exists())
        // sha256 对不上：会话必须失败，不能把坏文件当成功恢复。
        assertTrue(report.errors.any { it.contains("sha256") })
        assertFalse(File(target, "--sdcard--/s1").exists())
    }

    @Test
    fun restorePluginsExtractsFilesAndReturnsRegistry() {
        val source = sourceTree()
        val zip = File(source.root, "backup.zip")
        zip.outputStream().use {
            NativeBackup.export(it, source.sessions, testSettings(), source.plugins, source.registry, source.attachments)
        }

        val target = temp("plugins")
        val report = NativeBackup.restorePlugins(zip, target)
        assertEquals(2, report.restoredFiles)
        assertEquals(listOf("p1"), report.restoredIds)
        assertEquals(0, report.failed)
        assertContentEquals(PLUGIN_JS, File(target, "p1/index.js").readBytes())
        assertNotNull(report.registryJson)
        assertTrue(report.registryJson!!.contains("\"id\":\"p1\""))
        assertFalse(report.registryJson!!.contains("/data/user/0/"))

        val again = NativeBackup.restorePlugins(zip, target)
        assertEquals(0, again.restoredFiles)
        assertEquals(1, again.skipped)
    }

    @Test
    fun restoreAttachmentsSkipsExistingAndVerifiesHash() {
        val source = sourceTree()
        val zip = File(source.root, "backup.zip")
        zip.outputStream().use {
            NativeBackup.export(it, source.sessions, testSettings(), source.plugins, source.registry, source.attachments)
        }

        val target = temp("attachments")
        val report = NativeBackup.restoreAttachments(zip, target)
        assertEquals(1, report.restored)
        val name = sha256(ATTACHMENT_BYTES)
        assertContentEquals(ATTACHMENT_BYTES, File(target, "objects/ab/$name").readBytes())

        val again = NativeBackup.restoreAttachments(zip, target)
        assertEquals(0, again.restored)
        assertEquals(1, again.skipped)
    }

    @Test
    fun readSettingsReturnsNullForBackupsWithoutSettings() {
        val target = temp("nosettings")
        val manifest = BackupManifest(
            1L, "", emptyList(), emptyList(), 0, 0, 0, 0, false, false, false,
        )
        val zip = File(target, "plain.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry(NativeBackup.MANIFEST_ENTRY))
            zos.write(manifest.toJson().toString().toByteArray())
            zos.closeEntry()
        }
        assertNull(NativeBackup.readSettings(zip))
    }

    @Test
    fun inspectRejectsForeignZip() {
        val dir = temp("foreign")
        val zip = File(dir, "foreign.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry(NativeBackup.MANIFEST_ENTRY))
            zos.write("""{"type":"dsh.backup","version":1}""".toByteArray())
            zos.closeEntry()
        }
        assertFailsWith<BackupFormatException> { NativeBackup.inspect(zip) }
    }

    // ------------------------------------------------------------------ fixtures

    private class Source(
        val root: File,
        val sessions: File,
        val plugins: File,
        val registry: File,
        val attachments: File,
    )

    private fun sourceTree(): Source {
        val root = temp("source")
        val sessions = File(root, "sessions")
        val writable = File(sessions, "--sdcard--/s1").apply { mkdirs() }
        File(writable, NativeBackup.SESSION_FILE).writeBytes(SESSION_BYTES)
        val legacy = File(sessions, "--sdcard--/s-legacy").apply { mkdirs() }
        File(legacy, NativeBackup.SESSION_FILE).writeBytes(SESSION_BYTES)
        File(legacy, NativeBackup.IMPORTED_SIDECAR).writeText("""{"readOnly":true}""")

        val plugins = File(root, "plugins/p1").apply { mkdirs() }
        File(plugins, "index.js").writeBytes(PLUGIN_JS)
        File(plugins, "lib").mkdirs()
        File(plugins, "lib/util.js").writeBytes("export const x = 1;\n".toByteArray())
        val registry = File(root, "plugins.json")
        registry.writeText(
            """{"version":1,"plugins":[{"id":"p1","name":"Plugin One","version":"1.0.0",""" +
                """"path":"/data/user/0/xin.dshaa.nativeapp/files/plugins/p1","enabled":true,"tools":["echo"]}]}""",
        )

        val attachments = File(root, "attachments")
        val objectDir = File(attachments, "objects/ab").apply { mkdirs() }
        File(objectDir, sha256(ATTACHMENT_BYTES)).writeBytes(ATTACHMENT_BYTES)
        return Source(root, sessions, File(root, "plugins"), registry, attachments)
    }

    private fun testSettings(): JsonObject = buildJsonObject {
        put("strings", buildJsonObject { put("model", "deepseek-flash") })
        put("booleans", buildJsonObject { put("expand_thinking", true) })
        put("ints", buildJsonObject { put("max_tokens", 256000) })
        put("longs", buildJsonObject { put("installed_at", 1_800_000_000_000L) })
        put("floats", buildJsonObject { put("temperature", 0.7) })
        put(
            "stringSets",
            buildJsonObject {
                put("recent", buildJsonArray { add(JsonPrimitive("deepseek-flash")); add(JsonPrimitive("deepseek-chat")) })
            },
        )
    }

    private fun temp(prefix: String): File =
        Files.createTempDirectory("dsh-backup-$prefix-").toFile().also { it.deleteOnExit() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val SESSION_BYTES = "{\"type\":\"session\"}\n{\"t\":\"turn/start\"}\n".toByteArray()
        val PLUGIN_JS = "export function apply(ctx) { ctx.tools.register('echo', () => 'hi') }\n".toByteArray()
        val ATTACHMENT_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3, 4)
    }
}
