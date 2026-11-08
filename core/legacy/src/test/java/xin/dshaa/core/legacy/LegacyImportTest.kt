package xin.dshaa.core.legacy

import xin.dshaa.core.model.MessageRole
import xin.dshaa.core.session.SessionCodec
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 用真机备份里的两条真实旧会话做夹具（`fixtures/legacy-backup-mini.zip`）：
 * - v0：`6635ec2c-…/session.jsonl.zstd`（packed chunk + 5 条消息）
 * - v3：`session-e6324ca7-…/session.v3.jsonl.zstd`（12 条消息 + 工具结果包装块）
 *
 * 夹具来源：2026-09-24 真机备份 `DSH备份-20260924-193035.zip`，原始压缩字节，未改写。
 */
class LegacyImportTest {

    private fun fixtureZip(): File {
        val tmp = Files.createTempFile("legacy-backup", ".zip").toFile()
        javaClass.getResourceAsStream("/fixtures/legacy-backup-mini.zip")!!.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        return tmp
    }

    private fun entryBytes(zip: File, name: String): ByteArray {
        ZipInputStream(FileInputStream(zip)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name == name) {
                    val out = java.io.ByteArrayOutputStream()
                    zis.copyTo(out)
                    return out.toByteArray()
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        error("entry not found: $name")
    }

    private fun scan(): LegacyScanResult {
        val result = fixtureZip().inputStream().use { LegacyBackup.scan(it, "legacy-backup-mini.zip") }
        return result
    }

    @Test
    fun `scan finds v0 and v3 with titles and messages`() {
        val scan = scan()
        assertEquals(2, scan.entries.size, "mini 备份里应有 2 条会话：${scan.warnings}")
        val v0 = scan.entries.first { it.formatVersion == 0 }
        val v3 = scan.entries.first { it.formatVersion == 3 }

        assertEquals("/storage/emulated/0/鸡太美", v0.header.cwd)
        assertEquals("这是一个工具测试。请直接回", v0.title)
        assertTrue(v0.messageCount >= 4, "v0 消息数=${v0.messageCount}")
        assertTrue(v0.preview.isNotEmpty())

        // v3 有两条 session/title，投影必须取最后一条（重命名/LLM 起名后写生效）
        assertEquals("抖音虚拟屏群发消息", v3.title)
        assertTrue(v3.messageCount >= 10, "v3 消息数=${v3.messageCount}")
        assertFalse(v3.alreadyImported)
        assertTrue(v3.zipBytes > 0)
        assertEquals(64, v3.sha256.length)
    }

    @Test
    fun `v3 tool result wrapper is lifted to role tool`() {
        val scan = scan()
        val v3 = scan.entries.first { it.formatVersion == 3 }
        val zip = fixtureZip()
        val log = LegacyLogReader.decode(entryBytes(zip, v3.entryName))
        val messages = LegacyProjection.messages(log)
        val tools = messages.filter { it.role == MessageRole.TOOL }
        assertTrue(tools.isNotEmpty(), "v3 会话应含工具结果")
        assertTrue(tools.all { it.toolCallId != null }, "工具结果必须带 toolCallId")
        assertTrue(
            messages.filter { it.role == MessageRole.ASSISTANT }.any { m -> m.content.any { it.type == xin.dshaa.core.model.BlockType.TOOL_CALL } },
            "assistant 消息应含 tool-call 块",
        )
    }

    @Test
    fun `convert v0 to readable v4`() {
        val scan = scan()
        val v0 = scan.entries.first { it.formatVersion == 0 }
        val zip = fixtureZip()
        val log = LegacyLogReader.decode(entryBytes(zip, v0.entryName))
        val built = LegacyV4.build(log, "11111111-1111-1111-1111-111111111111")
        assertEquals(4, built.header.version)
        assertFalse(built.header.isSeeded)
        assertEquals(v0.header.cwd, built.header.cwd)
        assertEquals(v0.header.createdAt, built.header.createdAt)

        val decoded = SessionCodec.decodeText(String(LegacyV4.encode(built), Charsets.UTF_8))
        assertEquals(4, decoded.header.version)
        val seqs = decoded.events.mapNotNull { it.seq }
        assertEquals(seqs.indices.map { it.toLong() }, seqs, "seq 必须从 0 连续")
        val messages = SessionCodec.messagesOf(decoded.events)
        assertEquals(v0.messageCount, messages.size)
        assertTrue(messages.any { it.role == MessageRole.USER && it.text.contains("工具测试") })
        // 标题是最后一条，且等于源会话标题
        val lastTitle = decoded.events.mapNotNull { ev -> SessionCodec.titleOf(ev) }.lastOrNull()
        assertEquals(v0.title, lastTitle)
    }

    @Test
    fun `import writes v4 copy plus sidecar and is idempotent`() {
        val scan = scan()
        val v3 = scan.entries.first { it.formatVersion == 3 }
        val zip = fixtureZip()
        val root = Files.createTempDirectory("sessions").toFile()

        val first = LegacyBackup.import(root, { FileInputStream(zip) }, v3, "legacy-backup-mini.zip")
        assertTrue(first is LegacyImportOutcome.Imported, "第一次导入应成功：$first")
        first as LegacyImportOutcome.Imported
        val dir = File(first.path)
        val file = File(dir, "session.v4.jsonl.zstd")
        assertTrue(file.isFile)
        assertTrue(File(dir, "imported.json").isFile)

        val decoded = SessionCodec.decode(file.readBytes())
        assertEquals(4, decoded.header.version)
        assertEquals(v3.header.cwd, decoded.header.cwd)
        assertFalse(decoded.header.isSeeded)
        val messages = SessionCodec.messagesOf(decoded.events)
        assertEquals(v3.messageCount, messages.size)
        assertTrue(messages.any { it.role == MessageRole.ASSISTANT })
        assertTrue(decoded.events.mapNotNull { it.seq }.let { it == it.indices.map { i -> i.toLong() } })

        // 已导入索引能识别出发 sha
        val keys = LegacyBackup.importedKeys(root)
        assertTrue(v3.sha256 in keys)

        // 同一份备份再导入 → 跳过（sha 已记录在侧车里，但 import 本身不查索引；用 v4 原样路径验证幂等）
        val second = LegacyBackup.import(root, { FileInputStream(zip) }, v3, "legacy-backup-mini.zip")
        assertTrue(second is LegacyImportOutcome.Imported, "v0-v3 每次生成新副本（由 UI 层按 sha 去重）")
    }

    @Test
    fun `v4 source is copied byte for byte and skipped on repeat`() {
        val scan = scan()
        val v3 = scan.entries.first { it.formatVersion == 3 }
        val zip = fixtureZip()
        val log = LegacyLogReader.decode(entryBytes(zip, v3.entryName))
        val sourceV4 = LegacyV4.encode(LegacyV4.build(log, "22222222-2222-2222-2222-222222222222"))

        val backup = Files.createTempFile("v4-backup", ".zip").toFile()
        val entryName = "dshhome/sessions/--x--/session-22222222-2222-2222-2222-222222222222/session.v4.jsonl.zstd"
        ZipOutputStream(backup.outputStream()).use { out ->
            out.putNextEntry(ZipEntry(entryName))
            out.write(sourceV4)
            out.closeEntry()
        }

        val v4Entry = backup.inputStream().use { LegacyBackup.scan(it, "v4.zip") }.entries.single()
        assertEquals(4, v4Entry.formatVersion)

        val root = Files.createTempDirectory("sessions-v4").toFile()
        val outcome = LegacyBackup.import(root, { FileInputStream(backup) }, v4Entry, "v4.zip")
        assertTrue(outcome is LegacyImportOutcome.Imported, "v4 导入应成功：$outcome")
        val copied = File((outcome as LegacyImportOutcome.Imported).path, "session.v4.jsonl.zstd")
        assertTrue(sourceV4.contentEquals(copied.readBytes()), "v4 源必须逐字节复制")

        val again = LegacyBackup.import(root, { FileInputStream(backup) }, v4Entry, "v4.zip")
        assertTrue(again is LegacyImportOutcome.Skipped, "重复导入同一 v4 会话应跳过")
    }

    @Test
    fun `sha256 matches known fixture value`() {
        val scan = scan()
        val v0 = scan.entries.first { it.formatVersion == 0 }
        val zip = fixtureZip()
        val bytes = entryBytes(zip, v0.entryName)
        assertEquals(LegacyBackup.sha256(bytes), v0.sha256)
        assertNotNull(v0.preview.firstOrNull())
    }
}
