package xin.dshaa.core.session

import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.ChatMessage
import xin.dshaa.core.model.ContentBlock
import xin.dshaa.core.model.MessageRole
import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 会话导出 Markdown（M22）：消息分组、思考折叠、工具调用/结果配对、内部来源注记、截断、文件名清洗。
 * 纯函数，不依赖 Android/时区（时间只按本地格式渲染，断言不绑死具体时区）。
 */
class SessionMarkdownTest {

    private fun user(id: String, text: String, sourceKind: String? = null, summary: String? = null) =
        ChatMessage(
            id = id,
            role = MessageRole.USER,
            content = listOf(ContentBlock(BlockType.TEXT, text = text)),
            sourceKind = sourceKind,
            sourceSummary = summary,
            createdAt = 1_760_000_000_000,
        )

    private fun assistant(
        id: String,
        blocks: List<ContentBlock>,
        at: Long = 1_760_000_060_000,
    ) = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = blocks, createdAt = at)

    private fun toolResult(id: String, callId: String, text: String) =
        ChatMessage(
            id = id,
            role = MessageRole.TOOL,
            content = listOf(ContentBlock(BlockType.TEXT, text = text)),
            toolCallId = callId,
            createdAt = 1_760_000_070_000,
        )

    @Test
    fun rendersHeaderAndBothRoles() {
        val md = SessionMarkdown.render(
            title = "迁移对拍",
            model = "deepseek-flash",
            sessionId = "1234567890abcdef",
            exportedAt = 1_760_000_100_000,
            messages = listOf(user("u1", "你好"), assistant("a1", listOf(ContentBlock(BlockType.TEXT, text = "在的")))),
        )

        assertTrue(md.startsWith("# 迁移对拍\n"))
        assertContains(md, "> 模型 deepseek-flash")
        assertContains(md, "会话 12345678")
        assertContains(md, "2 条消息")
        assertContains(md, "## 用户 · ")
        assertContains(md, "你好")
        assertContains(md, "## 助手 · ")
        assertContains(md, "在的")
        // 工具消息不单独成段（没有配对时也不出现）
        assertFalse(md.contains("## 工具"))
    }

    @Test
    fun groupsConsecutiveAssistantMessagesIntoOneRun() {
        val md = SessionMarkdown.render(
            title = "分组",
            model = "m",
            sessionId = null,
            exportedAt = 1_760_000_100_000,
            messages = listOf(
                user("u1", "做两件事"),
                assistant("a1", listOf(ContentBlock(BlockType.TOOL_CALL, toolCallId = "t1", name = "read", arguments = """{"path":"a"}"""))),
                assistant("a2", listOf(ContentBlock(BlockType.TEXT, text = "done"))),
            ),
        )

        assertEquals(1, Regex("## 助手").findAll(md).count(), "连续 assistant 消息应合成一个「助手」段")
        assertContains(md, "**工具 · read**")
        assertContains(md, "done")
    }

    @Test
    fun foldsThinkingAndPairsToolResult() {
        val md = SessionMarkdown.render(
            title = "工具",
            model = "m",
            sessionId = null,
            exportedAt = 1_760_000_100_000,
            messages = listOf(
                user("u1", "读一下"),
                assistant(
                    "a1",
                    listOf(
                        ContentBlock(BlockType.REASONING, text = "先读文件"),
                        ContentBlock(BlockType.TOOL_CALL, toolCallId = "t1", name = "read", arguments = """{"path":"hello.md"}"""),
                        ContentBlock(BlockType.TEXT, text = "第一行是 hi"),
                    ),
                ),
                toolResult("tr1", "t1", "# hello\nhi"),
            ),
        )

        assertContains(md, "<details><summary>思考过程 · 4 字</summary>")
        assertContains(md, "先读文件")
        assertContains(md, "**工具 · read**")
        assertContains(md, """"path":"hello.md"""")
        assertContains(md, "<details><summary>工具结果</summary>")
        assertContains(md, "# hello")
        assertContains(md, "第一行是 hi")
    }

    @Test
    fun internalSourcesBecomeNotesWithoutDumpingPrompt() {
        val md = SessionMarkdown.render(
            title = "内部消息",
            model = "m",
            sessionId = null,
            exportedAt = 1_760_000_100_000,
            messages = listOf(
                user("u0", "真实用户消息"),
                user("u1", "SECRET-INTERNAL-PROMPT", sourceKind = "goal", summary = null),
                user("u2", "CATALOG", sourceKind = "skill-catalog", summary = "2 个技能"),
                user("u3", "SKILL-BODY", sourceKind = "skill-invocation", summary = "m21-demo"),
            ),
        )

        assertContains(md, "〔自动续轮〕")
        assertContains(md, "〔技能目录 · 2 个技能〕")
        assertContains(md, "〔技能指令 · m21-demo〕")
        assertFalse(md.contains("SECRET-INTERNAL-PROMPT"), "内部提示词正文不应导出")
        assertFalse(md.contains("CATALOG"))
        assertFalse(md.contains("SKILL-BODY"))
        assertTrue(md.contains("真实用户消息"))
    }

    @Test
    fun truncatesLongToolResult() {
        val long = "A".repeat(SessionMarkdown.TOOL_RESULT_LIMIT) + "TAIL-MARKER"
        val md = SessionMarkdown.render(
            title = "截断",
            model = "m",
            sessionId = null,
            exportedAt = 1_760_000_100_000,
            messages = listOf(
                assistant("a1", listOf(ContentBlock(BlockType.TOOL_CALL, toolCallId = "t1", name = "bash", arguments = "{}"))),
                toolResult("tr1", "t1", long),
            ),
        )

        assertContains(md, "已截断，原长 ${long.length} 字符")
        assertFalse(md.contains("TAIL-MARKER"), "超出上限的尾部不应写进导出文件")
    }

    @Test
    fun fileNameSanitizesTitleAndUsesTimestamp() {
        val at = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 7, 5, 46, 30)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val name = SessionMarkdown.fileName("""a/b:c*?"<>|  d""", at)

        assertTrue(name.matches(Regex("""DSH-a-b-c-d-\d{8}-\d{6}\.md""")), name)
        assertTrue(name.endsWith("-20261107-054630.md"))
        assertFalse(name.contains("/"))
        assertFalse(name.contains(":"))
    }

    @Test
    fun fileNameFallsBackForBlankTitle() {
        val name = SessionMarkdown.fileName("   ", 1_760_000_000_000)
        assertTrue(name.startsWith("DSH-会话-"), name)
    }

    @Test
    fun emptyConversationStillRendersHeader() {
        val md = SessionMarkdown.render("空", "m", null, 1_760_000_100_000, emptyList())
        assertContains(md, "# 空")
        assertContains(md, "0 条消息")
    }
}
