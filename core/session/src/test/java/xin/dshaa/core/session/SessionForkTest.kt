package xin.dshaa.core.session

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 会话分支（Operit「创建分支 / 重新生成」的 v4 实现）：append-only 不能原地截断，
 * 分支 = 新会话 + 复制前缀；`cleanTurnBoundary` 必须扩到完整 turn，避免把半个 turn
 * （有 tool_use 没有 tool_result）带进新会话。
 */
class SessionForkTest {

    private fun tempRepo(): Pair<SessionRepository, File> {
        val root = Files.createTempDirectory("dsh-fork").toFile()
        return SessionRepository(File(root, "sessions")) to root
    }

    @Test
    fun forkCopiesPrefixAndKeepsSeqContinuous() {
        val (repo, _) = tempRepo()
        val session = repo.create(cwd = "/tmp/ws", model = "deepseek-flash")
        session.appendRequestHeader(provider = "deepseek", model = "deepseek-flash")
        session.startTurn()
        session.startStep()
        session.appendUserMessage("hello")
        val assistantSeq = session.appendAssistantMessage("hi there")
        session.endStep()
        session.endTurn()
        session.appendTitle("原会话")
        val eventCountBefore = session.events.size

        val forked = repo.fork(session.header.id, assistantSeq, cleanTurnBoundary = true, title = "分支 · 原会话")
        assertNotNull(forked)
        forked!!
        assertNotEquals(session.header.id, forked.header.id)
        assertEquals(4, forked.header.version)
        assertEquals("/tmp/ws", forked.header.cwd)
        // 前缀 = 到第一个 turn 的 turn/end（assistant 消息之后的事件也在里面），再补一条新标题
        assertEquals(eventCountBefore, forked.events.size)
        val seqs = forked.events.mapNotNull { it.seq }
        assertEquals((0L..(seqs.size - 1).toLong()).toList(), seqs)
        // 落盘可解码
        val decoded = SessionCodec.decode(forked.file.readBytes())
        assertEquals(forked.header.id, decoded.header.id)
        assertTrue(decoded.events.any { it.type == "turn/end" })
        assertEquals("分支 · 原会话", decoded.events.last().let { SessionCodec.titleOf(it) })
        assertEquals(2, SessionCodec.messagesOf(decoded.events).size)
    }

    @Test
    fun forkCutsBeforeIncompleteTurn() {
        val (repo, _) = tempRepo()
        val session = repo.create(cwd = "/tmp/ws")
        session.appendRequestHeader(provider = "deepseek", model = "deepseek-flash")
        session.startTurn()
        session.startStep()
        session.appendUserMessage("one")
        session.appendAssistantMessage("two")
        session.endStep()
        session.endTurn()
        session.startTurn()
        session.startStep()
        session.appendUserMessage("three")
        val pendingUserSeq = session.events.last().seq!!

        val forked = repo.fork(session.header.id, pendingUserSeq, cleanTurnBoundary = true)
        assertNotNull(forked)
        val types = forked!!.events.map { it.type }
        assertEquals(1, types.count { it == "turn/start" })
        assertEquals(1, types.count { it == "turn/end" })
        assertEquals(1, forked.events.count { it.type == "user/message" })
    }

    @Test
    fun forkReturnsNullForUnknownSessionOrEmptyPrefix() {
        val (repo, _) = tempRepo()
        assertNull(repo.fork("missing", 0))
        val session = repo.create(cwd = "/tmp/ws")
        session.appendRequestHeader(provider = "deepseek", model = "deepseek-flash")
        // throughSeq 在第一个事件之前：没有可复制的前缀
        assertNull(repo.fork(session.header.id, -5))
    }

    @Test
    fun forkedSessionCanAppendNextTurn() {
        val (repo, _) = tempRepo()
        val session = repo.create(cwd = "/tmp/ws")
        session.appendRequestHeader(provider = "deepseek", model = "deepseek-flash")
        session.startTurn()
        session.startStep()
        session.appendUserMessage("hello")
        val seq = session.appendAssistantMessage("hi")
        session.endStep()
        session.endTurn()

        val forked = repo.fork(session.header.id, seq, cleanTurnBoundary = true)!!
        val turn = forked.startTurn()
        assertEquals(2, turn) // 前缀里已有 turn 1
        forked.startStep()
        forked.appendUserMessage("next")
        val decoded = SessionCodec.decode(forked.file.readBytes())
        assertEquals(2, decoded.events.count { it.type == "turn/start" })
        assertTrue(forked.events.mapNotNull { it.seq }.zipWithNext().all { (a, b) -> b == a + 1 })
        // 新分支不共享原会话文件
        assertNotEquals(session.file.absolutePath, forked.file.absolutePath)
        val original = SessionCodec.decode(session.file.readBytes())
        assertEquals(1, original.events.count { it.type == "user/message" })
    }

    @Suppress("unused")
    private fun json(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
}
