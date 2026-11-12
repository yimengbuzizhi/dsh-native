package xin.dshaa.core.engine

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.ChatMessage
import xin.dshaa.core.model.ContentBlock
import xin.dshaa.core.model.MessageRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 上下文压缩（M43）的逐字断言：指令文本、checkpoint 分段、令牌启发式、选区边界。
 *
 * 期望值来自两处实证：
 * - 桌面会话 `session-9922ad59…` 的 `compaction/summary` + 替换消息（前言 300 字符、闭标签独立成块）；
 * - 上游 `dsh-compaction-basic/lib/index.js`：`COMPACTION_INSTRUCTION`（34 行）、
 *   `selectCompactableRange()`（第 410-433 行）、`frameSummary()`（第 570-580 行）；
 * - 上游 `dsh-token-meter/lib/index.js`：`CHARS_PER_TOKEN=4`、`BLOCK_OVERHEAD=4`、每条消息再 +4。
 */
class CompactionTest {

    // ---------------- 摘要指令 / checkpoint 分段 ----------------

    @Test
    fun instructionMatchesUpstreamText() {
        val lines = Compaction.INSTRUCTION.lines()
        assertEquals(34, lines.size, "上游是 34 行数组用 \\n join")
        assertTrue(lines[0].startsWith("You are now acting as a compaction engine"))
        assertTrue(lines[0].endsWith("with no loss of essential context."))
        assertEquals("", lines[1])
        assertTrue(lines[2].startsWith("Output EXACTLY the Markdown structure below"))
        // 八个固定小节，顺序不能变
        val sections = lines.filter { it.startsWith("## ") }
        assertEquals(
            listOf(
                "## Primary Request and Intent",
                "## Key Technical Concepts",
                "## Files and Code",
                "## Errors and Fixes",
                "## Pending Jobs",
                "## Current Work",
                "## Next Step",
                "## Critical Context",
            ),
            sections,
        )
        assertEquals("Rules:", lines[28])
        // 末条规则把 <compacted-summary> 展开进去（上游模板串）
        assertTrue(lines.last().contains("<compacted-summary> block, it is a PRIOR checkpoint."))
    }

    @Test
    fun frameSummaryIsThreeTextBlocks() {
        val framed = Compaction.frameSummary("## Current Work\n- x")
        assertEquals(3, framed.size)
        // 桌面样本 seq 1443 block[0]，逐字
        assertEquals(
            "This is an automatically generated checkpoint condensing an earlier span of the conversation " +
                "to free up context. Treat the captured context as established background and build on it " +
                "without restating it. Continue the task directly from the messages that follow, without " +
                "acknowledging this checkpoint.\n\n<compacted-summary>",
            framed[0],
        )
        assertEquals(300 + 2 + 19, framed[0].length, "桌面样本 block[0] 是 321 字符")
        assertEquals("## Current Work\n- x", framed[1])
        assertEquals("</compacted-summary>", framed[2])
    }

    // ---------------- 令牌启发式 ----------------

    @Test
    fun tokenEstimateMatchesUpstreamHeuristic() {
        // text：ceil(len/4) + 4
        assertEquals(5, Compaction.estimateContentTokens(listOf(ContentBlock(BlockType.TEXT, text = "abcd"))))
        assertEquals(6, Compaction.estimateContentTokens(listOf(ContentBlock(BlockType.TEXT, text = "abcde"))))
        // tool-call：ceil(name/4) + ceil(args/4) + 4 = 1 + 1 + 4
        assertEquals(
            6,
            Compaction.estimateContentTokens(
                listOf(ContentBlock(BlockType.TOOL_CALL, name = "read", arguments = "{}", toolCallId = "c1")),
            ),
        )
        // 一条消息 = 内容 + 4（role 框架开销）
        val message = ChatMessage(
            id = "m1",
            role = MessageRole.USER,
            content = listOf(ContentBlock(BlockType.TEXT, text = "abcd")),
        )
        assertEquals(9, Compaction.estimateMessageTokens(message))
        // 思考块不计价（上游 estimateContent 只认 text / tool_use / image）
        val thinking = ChatMessage(
            id = "m2",
            role = MessageRole.ASSISTANT,
            content = listOf(ContentBlock(BlockType.REASONING, text = "x".repeat(400), signature = "sig")),
        )
        assertEquals(4, Compaction.estimateMessageTokens(thinking))
    }

    // ---------------- 选区 ----------------

    private fun nodes(vararg tokens: Int): List<Compaction.Node> =
        tokens.mapIndexed { index, t -> Compaction.Node(seq = index.toLong(), tokens = t, toolResult = false) }

    @Test
    fun selectRangeKeepsRecentTailByTokens() {
        // 从最新往回累计到 250+：100,200,300 → 停在倒数第三条（keepFrom=2）→ 压 0..1
        assertEquals(0..1, Compaction.selectRange(nodes(100, 100, 100, 100, 100), 250))
        // 预算很小：只保最后一条
        assertEquals(0..3, Compaction.selectRange(nodes(100, 100, 100, 100, 100), 1))
        // 预算比全量还大：没有可压缩区间
        assertNull(Compaction.selectRange(nodes(100, 100, 100), 500))
        // 空会话
        assertNull(Compaction.selectRange(emptyList(), 0))
    }

    @Test
    fun selectRangeDoesNotSplitToolCallPair() {
        // 边界原本落在倒数第二（工具结果）上 → 往回退一条，把调用与结果留在同一侧
        val list = listOf(
            Compaction.Node(seq = 10, tokens = 10, toolResult = false),
            Compaction.Node(seq = 20, tokens = 10, toolResult = false),
            Compaction.Node(seq = 21, tokens = 500, toolResult = true),
            Compaction.Node(seq = 30, tokens = 400, toolResult = false),
        )
        assertEquals(0..0, Compaction.selectRange(list, 450))
        // 退到第一条就没有可压缩区间了
        val tight = listOf(
            Compaction.Node(seq = 10, tokens = 10, toolResult = false),
            Compaction.Node(seq = 21, tokens = 500, toolResult = true),
        )
        assertNull(Compaction.selectRange(tight, 100))
    }

    @Test
    fun retainBudgetFloorsAtMinimumAndRatioIsSixteenPercent() {
        assertEquals(Compaction.MIN_RETAIN_TOKENS, Compaction.retainBudgetFor(1_000))
        assertEquals(16_000, Compaction.retainBudgetFor(100_000))
        assertEquals(2048, Compaction.MIN_RETAIN_TOKENS)
        assertEquals(65_536, Compaction.MAX_SUMMARY_TOKENS)
    }

    // ---------------- M44：自动压缩策略 ----------------

    @Test
    fun autoPolicyMatchesUpstreamForLargeWindow() {
        // deepseek-flash：W=1048576、O=256000、B=min(65536, W/8)=65536
        // threshold = min(floor(W×0.8)=838860, W−O−B=727040) = 727040
        // retain    = floor((W−O)×0.16) = 126812（> 2048 下限）
        val policy = assertNotNull(Compaction.Policy.resolve(1_048_576, 256_000))
        assertEquals(727_040, policy.thresholdTokens)
        assertEquals(126_812, policy.retainTokens)
    }

    @Test
    fun autoPolicyScalesHeadroomForSmallWindows() {
        // 8k 窗口：B=1024（W/8，比固定 64k 小），threshold=min(6553, 8192−2048−1024=5120)
        // retain=max(2048, floor(6144×0.16)=983)=2048
        val policy = assertNotNull(Compaction.Policy.resolve(8_192, 2_048))
        assertEquals(5_120, policy.thresholdTokens)
        assertEquals(2_048, policy.retainTokens)
    }

    @Test
    fun autoPolicyUsesUserRatio() {
        // M79：用户可设触发比例。W=65536、O=8192、B=8192 ⇒ pressureBudget=49152
        // ratio 0.5 ⇒ 32768（小于 pressureBudget，取比例）；ratio 0.9 ⇒ 58982 被 pressureBudget 夹到 49152
        val half = assertNotNull(Compaction.Policy.resolve(65_536, 8_192, 0.5))
        assertEquals(32_768, half.thresholdTokens)
        val ninety = assertNotNull(Compaction.Policy.resolve(65_536, 8_192, 0.9))
        assertEquals(49_152, ninety.thresholdTokens)
        // 超出范围的脏值收敛到 0.3–0.95，不会算出不成立/负数的阈值
        val clamped = assertNotNull(Compaction.Policy.resolve(65_536, 8_192, 5.0))
        assertEquals(49_152, clamped.thresholdTokens)
    }

    @Test
    fun autoPolicyRejectsImpossibleConfigs() {
        assertNull(Compaction.Policy.resolve(0, 1_024))
        // 输出上限 + headroom 已经把窗口占满
        assertNull(Compaction.Policy.resolve(8_192, 8_192))
        assertNull(Compaction.Policy.resolve(256_000, 256_000))
        // 窗口太小：阈值被 2048 的保留下限顶到不成立
        assertNull(Compaction.Policy.resolve(2_000, 0))
    }

    // ---------------- 文案 ----------------

    @Test
    fun commandTextsMatchUpstream() {
        assertEquals("Compacted 400 history items (~253362 tokens).", Compaction.resultText(400, 253_362))
        assertEquals("No compactable history yet.", Compaction.NO_HISTORY_TEXT)
        assertEquals("Usage: /compact (no arguments)", Compaction.USAGE_TEXT)
        assertEquals(
            "Compaction is unavailable because this process has an active compaction, or the agent is not idle.",
            Compaction.BUSY_TEXT,
        )
        assertEquals(
            "Compaction could not produce a useful summary. The attempt is recorded in the session log.",
            Compaction.NO_SUMMARY_TEXT,
        )
        assertEquals("Compaction cancelled.", Compaction.CANCELLED_TEXT)
    }

    @Test
    fun checkpointSourceShapeMatchesDesktop() {
        // 桌面样本：{"kind":"compact-checkpoint","compactionId":…,"sourceCommandId":…}
        val source = buildJsonObject {
            put("kind", Compaction.SOURCE_KIND)
            put("compactionId", "fba2d2dd")
            put("sourceCommandId", "cmd-866982e7-1")
        }
        assertEquals("compact-checkpoint", source["kind"].toString().trim('"'))
    }

    // ---------------- 溢出识别（M45，上游 isContextWindowExceededError） ----------------

    @Test
    fun structuredOverflowCodesAreRecognized() {
        assertTrue(Compaction.isContextOverflow("CONTEXT_WINDOW_EXCEEDED", null))
        assertTrue(Compaction.isContextOverflow("context_window_exceeded", "whatever"))
        // 上游适配器归一后的 code 也可能混在 message 里
        assertTrue(Compaction.isContextOverflow(null, "error type: CONTEXT_WINDOW_EXCEEDED"))
        assertTrue(Compaction.isContextOverflow(null, "context length exceeded"))
        assertTrue(Compaction.isContextOverflow(null, "context_window_limit_exceeded"))
        assertTrue(Compaction.isContextOverflow(null, "prompt is too long for the model's context window"))
    }

    @Test
    fun providerWordingIsRecognized() {
        // DeepSeek / OpenAI 兼容文案（真机溢出走这一条：code 是 invalid_request_error）
        assertTrue(
            Compaction.isContextOverflow(
                "invalid_request_error",
                "This model's maximum context length is 1048576 tokens. However, your messages resulted " +
                    "in 1200000 tokens. Please reduce the length of the messages.",
            ),
        )
        assertTrue(
            Compaction.isContextOverflow(
                "invalid_request_error",
                "Request too large for this model's context window: 1300000 tokens > 1048576 tokens",
            ),
        )
        assertTrue(
            Compaction.isContextOverflow(
                "invalid_request_error",
                "The request exceeds the allowed context window of 131072 tokens",
            ),
        )
    }

    @Test
    fun unrelatedFailuresAreNotOverflow() {
        assertFalse(Compaction.isContextOverflow(null, null))
        assertFalse(Compaction.isContextOverflow("", ""))
        assertFalse(Compaction.isContextOverflow("AUTHENTICATION_ERROR", "Invalid API key"))
        assertFalse(Compaction.isContextOverflow("RATE_LIMIT", "Too many requests"))
        assertFalse(Compaction.isContextOverflow("invalid_request_error", "invalid temperature: 3"))
        // 只是「上下文」这个词出现，没说超限
        assertFalse(Compaction.isContextOverflow(null, "loaded the context window metadata"))
        assertFalse(Compaction.isContextOverflow(null, "context: 1234 tokens used"))
    }

    // ---------------- 工具结果修剪（M46，上游 dsh-compaction-tool-result-pruner） ----------------

    private fun text(value: String) = ContentBlock(BlockType.TEXT, text = value)

    @Test
    fun pruneConfigMatchesUpstreamDefaults() {
        val config = Compaction.PruneConfig.DEFAULT
        assertEquals(8192, config.thresholdChars)
        assertEquals(4096, config.headChars)
        assertEquals(1024, config.tailChars)
        // 上游 PRUNE_MARKER 是 39 个字符（4 个换行 + 35 个可见字符）
        assertEquals(39, Compaction.PRUNE_MARKER.length)
        assertEquals("\n\n[... tool result middle pruned ...]\n\n", Compaction.PRUNE_MARKER)

        assertFailsWith<IllegalArgumentException> { Compaction.PruneConfig(thresholdChars = 0) }
        assertFailsWith<IllegalArgumentException> { Compaction.PruneConfig(headChars = -1) }
        assertFailsWith<IllegalArgumentException> { Compaction.PruneConfig(tailChars = -1) }
        // head + marker + tail 必须塞得进阈值
        assertFailsWith<IllegalArgumentException> {
            Compaction.PruneConfig(thresholdChars = 1000, headChars = 900, tailChars = 100)
        }
    }

    @Test
    fun pruneContentKeepsHeadMarkerAndTail() {
        val original = "H".repeat(4096) + "M".repeat(8192) + "T".repeat(1024)
        val pruned = assertNotNull(Compaction.pruneContent(listOf(text(original))))
        assertEquals(1, pruned.size)
        val value = pruned.single().text
        assertEquals(4096 + 39 + 1024, value.length)
        assertEquals("H".repeat(4096) + Compaction.PRUNE_MARKER + "T".repeat(1024), value)
        // 剪完必须更小、且落在阈值内（上游 postcondition）
        assertEquals(5159, Compaction.measurePruneChars(pruned))
        assertTrue(Compaction.measurePruneChars(pruned) < Compaction.measurePruneChars(listOf(text(original))))
    }

    @Test
    fun pruneContentLeavesWithinBudgetUntouched() {
        // 恰好等于阈值：不剪（上游是 `total > threshold`）
        assertNull(Compaction.pruneContent(listOf(text("x".repeat(8192)))))
        assertNotNull(Compaction.pruneContent(listOf(text("x".repeat(8193)))))
        assertNull(Compaction.pruneContent(emptyList()))
        assertNull(Compaction.pruneContent(listOf(ContentBlock(BlockType.IMAGE, raw = buildJsonObject { put("type", "image") }))))
    }

    @Test
    fun pruneCountsCodePointsAndNeverSplitsSurrogatePairs() {
        // 5000 个 emoji = 5000 码点 / 10000 个 UTF-16 单元：按码点算没超预算 → 不剪
        assertNull(Compaction.pruneContent(listOf(text("😀".repeat(5000)))))
        // 9000 码点：头 4096 个 emoji 完整保留，尾 1024 个 x 原样
        val pruned = assertNotNull(Compaction.pruneContent(listOf(text("😀".repeat(5000) + "x".repeat(4000)))))
        val value = pruned.single().text
        assertTrue(value.startsWith("😀".repeat(4096)))
        assertTrue(value.endsWith("x".repeat(1024)))
        assertFalse(value.contains('\uFFFD'), "代理对不能被切开")
        assertEquals(5159, Compaction.codePointLength(value))
        assertEquals(9255, value.length, "4096 个 emoji 各占 2 个 UTF-16 单元")
    }

    @Test
    fun pruneKeepsRichBlocksInOrderAndInsertsMarkerOnce() {
        val image = ContentBlock(BlockType.IMAGE, raw = buildJsonObject { put("type", "image"); put("handle", "img-1") })
        val blocks = listOf(text("a".repeat(5000)), image, text("b".repeat(5000)))
        val pruned = assertNotNull(Compaction.pruneContent(blocks))
        assertEquals(3, pruned.size)
        // 图片块原样保留、位置不变
        assertEquals(BlockType.IMAGE, pruned[1].type)
        assertEquals(image.raw, pruned[1].raw)
        // 标记只出现一次（落在第一个与被删区间相交的文本块）
        val joined = pruned.filter { it.type == BlockType.TEXT }.joinToString("") { it.text }
        assertEquals(1, joined.split(Compaction.PRUNE_MARKER).size - 1)
        assertTrue(pruned[0].text.startsWith("a".repeat(4096)))
        assertTrue(pruned[2].text.endsWith("b".repeat(1024)))
        assertEquals(5159, Compaction.measurePruneChars(pruned))
    }
}
