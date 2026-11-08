package xin.dshaa.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.ContentBlock
import xin.dshaa.core.model.ImageOffloadTarget
import xin.dshaa.core.model.ImageRef
import xin.dshaa.core.model.SessionEvent
import xin.dshaa.core.model.ToolCall
import xin.dshaa.core.model.toImageRef
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 上下文压缩的会话记账（M43）：`compaction/start` → `compaction/summary` →
 * 替换消息（`surfaceOp:{op:"replace"}`）→ `compaction/end`。
 *
 * 期望值全部来自桌面实证（`session-9922ad59…` seq 1440-1445）：
 * - 替换消息根上有 `surfaceOp` 对象 + `sourceEventSeqs`；
 * - `compaction/summary` 带 `shadowedRange` / `shadowedSeqs` / `shadowedTokenCount` / `usage`；
 * - 记账事件不进消息投影，替换消息是唯一的 user 消息。
 */
class CompactionEventsTest {

    private fun newSession(): Pair<SessionRepository, SessionSession> {
        val root = Files.createTempDirectory("compaction-events").toFile()
        val repo = SessionRepository(File(root, "sessions"))
        return repo to repo.create(cwd = root.absolutePath, model = "deepseek-flash")
    }

    @Test
    fun compactionLifecycleIsPersistedWithSurfaceReplace() {
        val (repo, session) = newSession()
        val userSeq = session.appendUserMessage("旧的用户消息")
        val assistantSeq = session.appendAssistantMessage("旧的助手消息")
        assertTrue(userSeq < assistantSeq)

        val compactionId = "fba2d2dd-0bd8-478d-abe7-1cd4df160af8"
        val commandId = "cmd-866982e7-1"
        session.appendCommandRun(commandId, "compact", "")
        val startSeq = session.appendCompactionStart(compactionId, commandId)
        val summarySeq = session.appendCompactionSummary(
            compactionId = compactionId,
            sourceCommandId = commandId,
            summary = "## Current Work\n- 搬 M43",
            rawOutput = Json.parseToJsonElement("""[{"type":"text","index":0,"text":"## Current Work\n- 搬 M43"}]""").jsonArray,
            shadowedRange = userSeq..assistantSeq,
            shadowedSeqs = listOf(userSeq, assistantSeq),
            shadowedTokenCount = 1234,
            provider = "deepseek-account",
            model = "deepseek-flash",
            maxTokens = 65536,
            usage = Json.parseToJsonElement(
                """{"inputTokens":10,"outputTokens":20,"cacheReadTokens":0,"cacheWriteTokens":0,"totalTokens":30}""",
            ).jsonObject,
        )
        val checkpointSeq = session.appendCompactionCheckpoint(
            compactionId = compactionId,
            sourceCommandId = commandId,
            texts = listOf("前言\n\n<compacted-summary>", "## Current Work\n- 搬 M43", "</compacted-summary>"),
            startSeq = userSeq,
            endSeq = assistantSeq,
            sourceEventSeqs = listOf(startSeq, summarySeq, userSeq, assistantSeq),
        )
        val endSeq = session.appendCompactionEnd(compactionId, commandId)
        session.appendCommandDone(commandId, "success", "Compacted 2 history items (~1234 tokens).", summarySeq)

        // 重新从磁盘读：surfaceOp / sourceEventSeqs 必须原样回来
        val reloaded = repo.load(session.header.id)!!
        val events = reloaded.events
        assertEquals(
            listOf(
                "user/message",
                "command/run",
                "compaction/start",
                "compaction/summary",
                "user/message",
                "compaction/end",
                "command/done",
            ),
            events
                .filter { it.type == "user/message" || it.type.startsWith("compaction/") || it.type.startsWith("command/") }
                .map { it.type },
        )

        val start = events.first { it.type == "compaction/start" }
        assertEquals(startSeq, start.seq)
        assertEquals(compactionId, start.data!!.jsonObject["compactionId"]!!.jsonPrimitive.content)
        assertEquals("null", start.data!!.jsonObject["turn"].toString())

        val summary = events.first { it.type == "compaction/summary" }.data!!.jsonObject
        assertEquals(listOf(userSeq, assistantSeq), summary["shadowedSeqs"]!!.jsonArray.map { it.jsonPrimitive.longOrNull!! })
        assertEquals(1234, summary["shadowedTokenCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(userSeq.toString(), summary["shadowedRange"]!!.jsonObject["start"]!!.jsonPrimitive.content)
        assertEquals(assistantSeq.toString(), summary["shadowedRange"]!!.jsonObject["end"]!!.jsonPrimitive.content)
        assertEquals("deepseek-account", summary["provider"]!!.jsonPrimitive.content)
        assertEquals("30", summary["usage"]!!.jsonObject["totalTokens"]!!.jsonPrimitive.content)
        assertTrue(summary["llmStreamCall"]?.jsonPrimitive?.content?.toBoolean() == true)

        val checkpoint = events.first { it.type == "user/message" && SessionCodec.replaceRangeOf(it) != null }
        assertEquals(checkpointSeq, checkpoint.seq)
        assertEquals(userSeq..assistantSeq, SessionCodec.replaceRangeOf(checkpoint))
        assertEquals("compact-checkpoint", checkpoint.data!!.jsonObject["source"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        // 三段 text：前言+开标签 / 摘要 / 闭标签
        val blocks = checkpoint.data!!.jsonObject["content"]!!.jsonArray
        assertEquals(3, blocks.size)
        assertTrue(blocks[0].jsonObject["text"]!!.jsonPrimitive.content.endsWith("<compacted-summary>"))
        assertEquals("</compacted-summary>", blocks[2].jsonObject["text"]!!.jsonPrimitive.content)
        // 根上的 sourceEventSeqs 指向 start / summary / 被顶掉的两条消息
        assertEquals(
            listOf(startSeq, summarySeq, userSeq, assistantSeq),
            diskSourceEventSeqs(repo, session),
        )

        val end = events.first { it.type == "compaction/end" }
        assertEquals(endSeq, end.seq)
        assertFalse(end.data!!.jsonObject.containsKey("error"))

        // 记账事件不进消息投影（projection 只认四条消息事件）；替换是**读取方**的事：
        // messagesOf 原样返回三条消息（旧 user / 旧 assistant / checkpoint），由 UI 按 surfaceOp 顶掉前两条。
        val messages = SessionCodec.messagesOf(events)
        assertEquals(3, messages.size)
        assertEquals(3, messages.last().content.size)
        assertEquals(checkpointSeq, messages.last().seq)
    }

    /** 从磁盘行里读根上的 `sourceEventSeqs`（SessionEvent 模型不保留它，只有写入方要保证）。 */
    private fun diskSourceEventSeqs(repo: SessionRepository, session: SessionSession): List<Long> {
        val text = ZstdFrames.decompressAll(File(session.file.absolutePath).readBytes()).toString(Charsets.UTF_8)
        val line = text.split('\n').first { it.contains("\"surfaceOp\":{\"op\":\"replace\"") }
        val obj = Json.parseToJsonElement(line).jsonObject
        return obj["sourceEventSeqs"]!!.jsonArray.map { it.jsonPrimitive.longOrNull!! }
    }

    @Test
    fun compactionEndKeepsErrorWhenFailed() {
        val (repo, session) = newSession()
        val compactionId = "c-1"
        session.appendCompactionStart(compactionId, null)
        session.appendCompactionEnd(compactionId, null, error = "Compaction could not produce a useful summary.")

        val end = repo.load(session.header.id)!!.events.first { it.type == "compaction/end" }
        assertEquals(
            "Compaction could not produce a useful summary.",
            end.data!!.jsonObject["error"]!!.jsonPrimitive.content,
        )
        // 手工压缩没有命令来源：sourceCommandId 不写
        assertFalse(end.data!!.jsonObject.containsKey("sourceCommandId"))
        assertNull(SessionCodec.replaceRangeOf(end))
    }

    @Test
    fun replaceRangeOnlyForReplaceOp() {
        val plain = SessionCodec.parseEvent("""{"type":"user/message","seq":3,"time":1,"data":{},"surfaceOp":"append"}""")
        assertNull(SessionCodec.replaceRangeOf(plain!!))
        val replace = SessionCodec.parseEvent(
            """{"type":"user/message","seq":4,"time":1,"data":{},"surfaceOp":{"op":"replace","startSeq":1,"endSeq":3}}""",
        )
        assertEquals(1L..3L, SessionCodec.replaceRangeOf(replace!!))
        // 区间反了/缺字段：不认
        val bad = SessionCodec.parseEvent(
            """{"type":"user/message","seq":5,"time":1,"data":{},"surfaceOp":{"op":"replace","startSeq":9,"endSeq":3}}""",
        )
        assertNull(SessionCodec.replaceRangeOf(bad!!))
    }

    // ---------------- M46：工具结果修剪 ----------------

    private fun event(type: String, seq: Long, data: String, surfaceOp: String? = null): SessionEvent =
        SessionCodec.parseEvent(
            """{"type":"$type","seq":$seq,"time":1,"data":$data""" +
                (if (surfaceOp != null) ""","surfaceOp":$surfaceOp""" else "") + "}",
        )!!

    @Test
    fun surfaceOfReplacesInPlaceInsteadOfAppending() {
        val events = listOf(
            event("user/message", 1, """{"content":[{"type":"text","text":"u1"}],"role":"user","id":"u1"}"""),
            event("assistant/message", 2, """{"message":{"content":[{"type":"text","text":"a1"}],"role":"assistant","id":"a1"}}"""),
            event("user/message", 3, """{"content":[{"type":"text","text":"u2"}],"role":"user","id":"u2"}"""),
            // checkpoint 顶掉 1..2：必须插回它们的位置，而不是追加到最后
            event(
                "user/message",
                4,
                """{"content":[{"type":"text","text":"checkpoint"}],"role":"user","id":"c"}""",
                surfaceOp = """{"op":"replace","startSeq":1,"endSeq":2}""",
            ),
        )
        assertEquals(listOf("checkpoint", "u2"), SessionCodec.surfaceOf(events).map { it.text })
        // 工具结果替换同理：位置留在调用之后、下一条消息之前
        val toolEvents = listOf(
            event("user/message", 1, """{"content":[{"type":"text","text":"go"}],"role":"user","id":"u1"}"""),
            event(
                "assistant/message",
                2,
                """{"message":{"content":[{"type":"tool-call","id":"c1","name":"read","arguments":"{}"}],"role":"assistant","id":"a1"}}""",
            ),
            event(
                "tool/result",
                3,
                """{"turn":1,"step":1,"message":{"role":"tool","toolCallId":"c1","content":[{"type":"text","text":"original"}],"id":"t1"}}""",
            ),
            event(
                "tool/result",
                4,
                """{"turn":1,"step":1,"message":{"role":"tool","toolCallId":"c1","content":[{"type":"text","text":"pruned"}],"id":"t1"}}""",
                surfaceOp = """{"op":"replace","startSeq":3,"endSeq":3}""",
            ),
        )
        val surface = SessionCodec.surfaceOf(toolEvents)
        assertEquals(listOf(1L, 2L, 4L), surface.map { it.seq })
        assertEquals("pruned", surface.last().text)
    }

    @Test
    fun toolResultPruneEventsRoundTripAndReplaceInPlace() {
        val (repo, session) = newSession()
        val marker = "\n\n[... tool result middle pruned ...]\n\n"
        val userSeq = session.appendUserMessage("读一下大文件")
        val callSeq = session.appendToolCall(ToolCall("call-1", "read", """{"path":"big.txt"}"""))
        val toolSeq = session.appendToolResult("call-1", "Z".repeat(30_000), isError = false)
        val pruneSeq = session.appendCompactionPrune(toolSeq, 7508)
        val prunedText = "H".repeat(4096) + marker + "T".repeat(1024)
        val replacementSeq = assertNotNull(
            session.appendToolResultPruned(toolSeq, listOf(ContentBlock(BlockType.TEXT, text = prunedText))),
        )
        val assistantSeq = session.appendAssistantMessage("读完了")

        val events = repo.load(session.header.id)!!.events
        // 1) pruning 账本：shadowedRange / shadowedSeqs / shadowedTokenCount 与 summary 同形
        val prune = events.first { it.seq == pruneSeq }
        assertEquals("compaction/prune", prune.type)
        assertEquals(
            listOf(toolSeq),
            prune.data!!.jsonObject["shadowedSeqs"]!!.jsonArray.map { it.jsonPrimitive.longOrNull!! },
        )
        assertEquals(toolSeq.toString(), prune.data!!.jsonObject["shadowedRange"]!!.jsonObject["start"]!!.jsonPrimitive.content)
        assertEquals(toolSeq.toString(), prune.data!!.jsonObject["shadowedRange"]!!.jsonObject["end"]!!.jsonPrimitive.content)
        assertEquals(7508, prune.data!!.jsonObject["shadowedTokenCount"]!!.jsonPrimitive.content.toInt())

        // 2) 替换事件：只换 content，其余字段原样；surfaceOp 顶掉原 seq
        val replacement = events.first { it.seq == replacementSeq }
        val original = events.first { it.seq == toolSeq }
        val originalMessage = original.data!!.jsonObject["message"]!!.jsonObject
        assertEquals("tool/result", replacement.type)
        assertEquals(toolSeq..toolSeq, SessionCodec.replaceRangeOf(replacement))
        val message = replacement.data!!.jsonObject["message"]!!.jsonObject
        assertEquals("tool", message["role"]!!.jsonPrimitive.content)
        assertEquals("call-1", message["toolCallId"]!!.jsonPrimitive.content)
        // 根上的 turn/step 与 message 的 id / isError 原样保留
        assertEquals(original.data!!.jsonObject["turn"], replacement.data!!.jsonObject["turn"])
        assertEquals(originalMessage["id"], message["id"])
        assertEquals(false, message["isError"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(
            prunedText,
            message["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        // 根上的 sourceEventSeqs 指向被顶掉的原事件
        assertEquals(listOf(toolSeq), diskSourceEventSeqs(repo, session))

        // 3) 回放：`surfaceOf` 原地替换（工具结果仍紧跟调用；tool/call 不是消息事件，不占位）
        val surface = SessionCodec.surfaceOf(events)
        assertEquals(listOf(userSeq, replacementSeq, assistantSeq), surface.map { it.seq })
        assertTrue(callSeq !in surface.map { it.seq }, "tool/call 是记账事件，不是会话面消息")
        assertEquals(prunedText, surface[1].content.single().text)
        // 会话列表预览/条数也走会话面（不是 4 条原始消息）
        val summary = repo.list().single()
        assertEquals(3, summary.messageCount)
    }

    // ---------------- M47：图片卸载 ----------------

    private fun imageRef(hex: Char, name: String? = null) = ImageRef(
        attachmentId = "sha256:" + hex.toString().repeat(64),
        mediaType = "image/png",
        bytes = 4,
        width = 2,
        height = 2,
        name = name,
    )

    @Test
    fun imageOffloadEventMarksSelectedOccurrenceOnReplay() {
        val (repo, session) = newSession()
        val first = imageRef('a', "first.png")
        val second = imageRef('b', "second.png")
        val userSeq = session.appendUserMessageContent(listOf("看两张图"), listOf(first, second))
        val offloadSeq = assertNotNull(
            session.appendImageOffload(listOf(ImageOffloadTarget(userSeq, listOf(1)))),
        )

        val events = repo.load(session.header.id)!!.events
        val event = events.first { it.seq == offloadSeq }
        assertEquals("image/offload", event.type)
        assertNull(event.surfaceOp)
        val targets = SessionCodec.imageOffloadTargetsOf(event)
        assertEquals(listOf(userSeq), targets.map { it.seq })
        assertEquals(listOf(1), targets.single().imageIndexes)

        // 回放：只有第二张被标成 offloaded，第一张原样
        val surface = SessionCodec.surfaceOf(events)
        val images = surface.single().content
            .filter { it.type == BlockType.IMAGE }
            .map { assertNotNull(it.toImageRef()) }
        assertEquals(listOf(false, true), images.map { it.offloaded })
        assertEquals(first.attachmentId, images[0].attachmentId)
        assertEquals(second.attachmentId, images[1].attachmentId)
    }

    @Test
    fun imageOffloadSurvivesToolResultPruneReplacement() {
        val (repo, session) = newSession()
        session.appendUserMessage("读图")
        session.appendToolCall(ToolCall("call-img", "read_image", """{"file_path":"/sdcard/a.png"}"""))
        val toolSeq = session.appendToolResult("call-img", "read", isError = false, images = listOf(imageRef('c')))
        session.appendImageOffload(listOf(ImageOffloadTarget(toolSeq, listOf(0))))

        // 修剪用的是投影后的消息内容（带 offloaded 标记），替换事件落盘后标记不能丢
        val projected = SessionCodec.surfaceOf(repo.load(session.header.id)!!.events)
            .single { it.seq == toolSeq }
        val replacementSeq = assertNotNull(session.appendToolResultPruned(toolSeq, projected.content))

        val surface = SessionCodec.surfaceOf(repo.load(session.header.id)!!.events)
        val toolMessage = surface.single { it.seq == replacementSeq }
        assertTrue(toolMessage.content.filter { it.type == BlockType.IMAGE }.single().toImageRef()!!.offloaded)
    }
}
