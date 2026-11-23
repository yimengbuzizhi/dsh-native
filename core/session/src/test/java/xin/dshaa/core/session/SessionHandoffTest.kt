package xin.dshaa.core.session

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * P-1 handoff 的存储契约（`docs/185` §九）：**会话格式不变**。
 *
 * 交接的三步在存储层只用到已有能力：
 * 1. `SessionStore.fork` 建新话题（复制前缀，v4 header/事件原样）；
 * 2. 新话题补 `request/header` + 一条普通 `user/message`（`source.kind="handoff"`）承载 brief；
 * 3. `SessionStore.trash` 把旧话题移进回收站（可恢复，不真删）。
 *
 * 这里断言重新解码后还是 v4、事件类型/来源正确、旧话题不受影响。
 */
class SessionHandoffTest {

    private fun tempRepo(): SessionRepository =
        SessionRepository(Files.createTempDirectory("dsh-handoff").toFile().resolve("sessions"))

    private fun seedTurn(session: SessionSession, text: String = "hello", reply: String = "hi there"): Long {
        session.appendRequestHeader(provider = "deepseek", model = "deepseek-flash")
        session.startTurn()
        session.startStep()
        session.appendUserMessage(text)
        val seq = session.appendAssistantMessage(reply)
        session.endStep()
        session.endTurn()
        session.appendTitle("旧话题")
        return seq
    }

    @Test
    fun handoffForksAndSeedsBriefWithoutChangingSessionFormat() {
        val repo = tempRepo()
        val old = repo.create(cwd = "/tmp/ws", model = "deepseek-flash")
        val through = seedTurn(old)
        val oldEvents = old.events.size

        // 1) session.fork（原语）：在旧话题的干净边界建新话题
        val fresh = repo.fork(old.header.id, through, cleanTurnBoundary = true, title = "交接后的话题")!!
        assertEquals(4, fresh.header.version)

        // 2) topic.switch 的落地：新话题先补 request/header，再写交接 brief
        fresh.appendRequestHeader(provider = "deepseek", model = "deepseek-flash", reasoningEffort = "high")
        val brief = """
            ## Critical Context
            - 决定：node 宿主放在页面可用之后启动。
            - 约束：零补丁跟上游 0.2.2-rc.5。
        """.trimIndent()
        val briefSeq = fresh.appendUserMessage(
            brief,
            source = buildJsonObject {
                put("kind", JsonPrimitive("handoff"))
                put("form", JsonPrimitive("notice"))
                put("summary", JsonPrimitive("交接摘要"))
            },
        )
        assertTrue(briefSeq > 0)

        // 3) 落盘后重新按 v4 解码：header/事件/来源都还在
        val decoded = SessionCodec.decode(fresh.file.readBytes())
        assertEquals(4, decoded.header.version)
        assertEquals(fresh.header.id, decoded.header.id)
        assertEquals("/tmp/ws", decoded.header.cwd)
        val handoff = decoded.events.single { it.type == "user/message" && it.seq == briefSeq }
        assertEquals("handoff", handoff.data!!.jsonObject["source"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        val messages = SessionCodec.messagesOf(decoded.events)
        assertEquals(3, messages.size) // 旧前缀两条 + brief 一条
        assertTrue(messages.last().text.contains("零补丁跟上游"))

        // 4) topic.end（原语）：旧话题进回收站，可恢复；新话题不受影响
        assertTrue(repo.trash(old.header.id))
        assertNull(repo.load(old.header.id))
        assertNotNull(repo.load(fresh.header.id))
        val trashed = repo.listTrash().single { it.id == old.header.id }
        assertTrue(repo.restoreTrashed(trashed.dirName))
        assertNotNull(repo.load(old.header.id))
        // 旧话题事件数没有因为交接被改写
        assertEquals(oldEvents, repo.load(old.header.id)!!.events.size)
    }

    @Test
    fun seededTopicCanAppendNextTurnOnSameStream() {
        val repo = tempRepo()
        val old = repo.create(cwd = "/tmp/ws", model = "deepseek-flash")
        val through = seedTurn(old)
        val fresh = repo.fork(old.header.id, through, cleanTurnBoundary = true)!!
        fresh.appendRequestHeader(provider = "deepseek", model = "deepseek-flash")
        fresh.appendUserMessage("brief", source = buildJsonObject { put("kind", JsonPrimitive("handoff")) })

        val turn = fresh.startTurn()
        fresh.startStep()
        fresh.appendUserMessage("继续这个话题")
        fresh.appendAssistantMessage("收到")
        fresh.endStep()
        fresh.endTurn()

        val decoded = SessionCodec.decode(fresh.file.readBytes())
        assertEquals(2, decoded.events.count { it.type == "turn/start" })
        assertEquals(2, turn)
        val seqs = decoded.events.mapNotNull { it.seq }
        assertEquals((0L until seqs.size.toLong()).toList(), seqs)
        // 旧话题的 hello + brief + 新话题的第一句 = 3 条 user
        assertEquals(3, SessionCodec.messagesOf(decoded.events).count { it.role == xin.dshaa.core.model.MessageRole.USER })
    }
}
