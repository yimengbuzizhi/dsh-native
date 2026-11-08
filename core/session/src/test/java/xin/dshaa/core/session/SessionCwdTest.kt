package xin.dshaa.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

/**
 * M36（docs/60）：跨设备恢复时只改会话 header 的 `cwd`，事件帧逐字节保留。
 */
class SessionCwdTest {

    private fun tempDir(): File = Files.createTempDirectory("dsh-cwd").toFile()

    private fun sessionFile(bytes: ByteArray, name: String = "s"): File {
        val dir = tempDir()
        val file = File(dir, "session.v4.jsonl.zstd")
        FileOutputStream(file).use { it.write(bytes) }
        return file
    }

    private fun frame(text: String) = ZstdFrames.compressFrame(text)

    // ------------------------------------------------------------------ 帧边界

    @Test
    fun firstFrameLengthSingleFrame() {
        val bytes = frame("hello\n")
        assertEquals(bytes.size, ZstdFrames.firstFrameLength(bytes))
        assertEquals("hello\n", ZstdFrames.decompressFrame(bytes, bytes.size).toString(Charsets.UTF_8))
    }

    @Test
    fun firstFrameLengthStopsAtFirstOfManyFrames() {
        val first = frame("first\n")
        val all = first + frame("second\n") + frame("third\n")
        val length = ZstdFrames.firstFrameLength(all)
        assertNotNull(length)
        assertEquals(first.size, length)
        assertEquals("first\n", ZstdFrames.decompressFrame(all, length!!).toString(Charsets.UTF_8))
    }

    @Test
    fun firstFrameLengthRejectsGarbageAndTruncated() {
        assertNull(ZstdFrames.firstFrameLength(ByteArray(0)))
        assertNull(ZstdFrames.firstFrameLength(byteArrayOf(1, 2, 3, 4, 5, 6)))
        val frame = frame("hello\n")
        assertNull(ZstdFrames.firstFrameLength(frame.copyOf(frame.size - 1)))
    }

    // ------------------------------------------------------------------ 读 header

    @Test
    fun readCwdFromRepositorySession() {
        val root = tempDir()
        val repo = SessionRepository(File(root, "sessions"))
        val session = repo.create(cwd = "/storage/emulated/0/work")
        session.appendUserMessage("hi")
        session.appendAssistantMessage("hello")

        assertEquals("/storage/emulated/0/work", SessionCwd.readCwd(session.file))
    }

    @Test
    fun readCwdRejectsNonSession() {
        val file = File(tempDir(), "session.v4.jsonl.zstd")
        file.writeBytes(byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9))
        assertNull(SessionCwd.readCwd(file))
    }

    // ------------------------------------------------------------------ 映射

    @Test
    fun usableCwdIsLeftUntouched() {
        val root = tempDir()
        val repo = SessionRepository(File(root, "sessions"))
        val session = repo.create(cwd = "/tmp/available")
        session.appendUserMessage("hi")
        val before = session.file.readBytes()

        val result = SessionCwd.remapIfNeeded(session.file, "/tmp/new-home") { it == "/tmp/available" }

        assertFalse(result.changed)
        assertNull(result.error)
        assertEquals("/tmp/available", result.oldCwd)
        assertArrayEquals(before, session.file.readBytes())
    }

    @Test
    fun missingCwdIsRewrittenAndEventsKeptByteForByte() {
        val root = tempDir()
        val repo = SessionRepository(File(root, "sessions"))
        val session = repo.create(cwd = "/storage/emulated/0/old-device")
        session.appendUserMessage("hello from the old device")
        session.appendAssistantMessage("reply")
        session.appendTitle("跨设备")
        val before = session.file.readBytes()
        val beforeFrameLength = ZstdFrames.firstFrameLength(before)!!

        val result = SessionCwd.remapIfNeeded(session.file, "/storage/emulated/0/new-home") { false }

        assertTrue(result.changed)
        assertNull(result.error)
        assertEquals("/storage/emulated/0/old-device", result.oldCwd)
        assertEquals("/storage/emulated/0/new-home", SessionCwd.readCwd(session.file))

        val after = session.file.readBytes()
        val afterFrameLength = ZstdFrames.firstFrameLength(after)!!
        // 事件帧 = 第一帧之后的所有字节，必须完全一致
        assertArrayEquals(
            before.copyOfRange(beforeFrameLength, before.size),
            after.copyOfRange(afterFrameLength, after.size),
        )
        val decoded = SessionCodec.decode(after)
        assertEquals("/storage/emulated/0/new-home", decoded.header.cwd)
        assertEquals(3, decoded.events.size)
        assertEquals("hello from the old device", SessionCodec.messagesOf(decoded.events).first().text)
    }

    @Test
    fun blankCwdIsTreatedAsDefaultAndNotRewritten() {
        val root = tempDir()
        val repo = SessionRepository(File(root, "sessions"))
        val session = repo.create(cwd = "")
        val result = SessionCwd.remapIfNeeded(session.file, "/tmp/new-home") { false }
        assertFalse(result.changed)
        assertEquals("", result.oldCwd)
    }

    @Test
    fun unknownHeaderFieldsSurviveRewrite() {
        val header = """{"type":"session","version":4,"id":"abc","createdAt":1,"cwd":"/tmp/old","futureField":42}"""
        val bytes = frame(header + "\n") +
            frame("""{"type":"user/message","seq":0,"time":2,"data":{"role":"user"}}""" + "\n")
        val file = sessionFile(bytes)

        val result = SessionCwd.remapIfNeeded(file, "/tmp/new") { false }

        assertTrue(result.changed)
        val text = ZstdFrames.decompressAll(file.readBytes()).toString(Charsets.UTF_8)
        val headerJson = Json.parseToJsonElement(text.lineSequence().first { it.isNotBlank() }).jsonObject
        assertEquals("/tmp/new", headerJson["cwd"]?.jsonPrimitive?.content)
        assertEquals(42, headerJson["futureField"]?.jsonPrimitive?.int)
    }

    @Test
    fun corruptFileIsReportedAndLeftAlone() {
        val file = File(tempDir(), "session.v4.jsonl.zstd")
        file.writeBytes(byteArrayOf(1, 2, 3))
        val result = SessionCwd.remapIfNeeded(file, "/tmp/new") { false }
        assertFalse(result.changed)
        assertNotNull(result.error)
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
    }
}
