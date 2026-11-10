package xin.dshaa.core.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.ChatMessage
import xin.dshaa.core.model.ContentBlock

/**
 * 上下文压缩（M43）：逐字移植上游 `@deepseek-ai/dsh-compaction-basic` 0.2.1-rc.2 的
 * **手工 `/compact`** 路径，外加 `dsh-command-compact` 的命令文案。
 *
 * 搬了什么（对着 `D:\dsh021\node_modules\@deepseek-ai\dsh-compaction-basic\lib\index.js`）：
 * - `selectCompactableRange()`（第 410-433 行）：从最新往回累计消息价，攒够 `retainTokens`
 *   就停；边界不许切开一条工具结果（工具调用/结果必须同侧）；
 * - 令牌价（`dsh-token-meter` 的固定密度启发式，第 16-18 行）：4 字符 1 令牌 + 每块 4 + 每条 4；
 * - `frameSummary()`：替换消息 = 三段 text（前言+开标签 / 摘要 / 闭标签）；
 * - `COMPACTION_INSTRUCTION`：摘要请求最后一条 user 指令，逐字；
 * - `compaction/start`、`compaction/summary`、`compaction/end` 记账事件与
 *   `source.kind:"compact-checkpoint"` 的替换消息（surfaceOp: replace）在
 *   [xin.dshaa.core.session.SessionSession] 里落库。
 *
 * M44 起补上**自动压缩**的压力触发（[Policy] + `TurnEngine.maybeAutoCompact`，等价上游
 * `agent/pre-step` 的 `compactIfNeeded("pressure")`）；M45 再补**溢出恢复**
 * （`agent/request-error` 的 `CONTEXT_WINDOW_EXCEEDED` → `retainTokens=0` 最大压缩 → 重发一次，
 * 识别规则见 [isContextOverflow]）；M46 补**工具结果修剪**（上游
 * `dsh-compaction-tool-result-pruner`：压力/溢出触发之后、选区之前，把头尾之外的中段换成
 * [PRUNE_MARKER]，见 [pruneContent]）；M47 图片卸载（请求体换占位文本，见 `ImageOffload`）；
 * M48 摘要请求的图片卸载恢复（上游 `compaction/summary-error`，见 `TurnEngine.summarizeCompaction`）。
 * 仍没搬：每模型 `modelPolicies`、`compactionRetries` 循环。
 * 原生没有 token meter / 路由容量，窗口由设置里的「上下文窗口」声明；手工路径的保留预算
 * 仍按「当前历史的 16%、下限 2048 令牌」现算（同一个 0.16）。
 */
object Compaction {

    /** 固定密度启发式：4 个 UTF-16 字符 ≈ 1 令牌（上游 `CHARS_PER_TOKEN`）。 */
    const val CHARS_PER_TOKEN = 4

    /** 每个内容块的 JSON 结构开销（上游 `BLOCK_OVERHEAD`）。 */
    const val BLOCK_OVERHEAD = 4

    /**
     * 保留预算下限：上游 `retainTokens` 默认 = `floor((contextWindow - outputReservation) × 0.16)`，
     * 原生没有路由容量，用「当前历史 × 0.16」代替，再压一个 2048 的下限，避免只留最后一两条
     * 导致摘要之后紧接着又要压缩。
     */
    const val RETAIN_RATIO = 0.16
    const val MIN_RETAIN_TOKENS = 2048

    /** 摘要输出的令牌上限：上游 `maxTokens` 默认 = `headroomTokens` = 65536。 */
    const val MAX_SUMMARY_TOKENS = 65_536

    /** 替换消息第一段的固定前言（上游 `CHECKPOINT_PREAMBLE`，300 字符）。 */
    const val CHECKPOINT_PREAMBLE =
        "This is an automatically generated checkpoint condensing an earlier span of the conversation " +
            "to free up context. Treat the captured context as established background and build on it " +
            "without restating it. Continue the task directly from the messages that follow, without " +
            "acknowledging this checkpoint."

    const val SUMMARY_OPEN_TAG = "<compacted-summary>"
    const val SUMMARY_CLOSE_TAG = "</compacted-summary>"

    /** `source.kind`：压缩替换消息（上游 `compactCheckpointSource()`）。 */
    const val SOURCE_KIND = "compact-checkpoint"

    // ---- 命令文案（上游 dsh-command-compact 的稳定错误/成功消息，逐字） ----

    const val NO_HISTORY_TEXT = "No compactable history yet."
    const val USAGE_TEXT = "Usage: /compact (no arguments)"
    const val BUSY_TEXT =
        "Compaction is unavailable because this process has an active compaction, or the agent is not idle."
    const val CHANGED_TEXT =
        "The history selected for compaction changed before it could be replaced. The attempt is recorded in the session log."
    const val NO_SUMMARY_TEXT =
        "Compaction could not produce a useful summary. The attempt is recorded in the session log."
    const val UNCLEAN_TEXT =
        "Compaction did not finish cleanly; some session history may have changed. Inspect the current session state before retrying."
    const val UNSAVED_TEXT = "Compaction finished, but the session could not be saved."
    const val CANCELLED_TEXT = "Compaction cancelled."

    /**
     * 摘要请求的最后一条 user 指令（上游 `COMPACTION_INSTRUCTION`，逐字；
     * 唯一差别：末条规则里的 `${SUMMARY_OPEN_TAG}` 展开成 `<compacted-summary>`）。
     *
     * 原文是 `[...].join("\n")` 的字符串数组，这里保持同样的分行（空串 = 空行）。
     */
    val INSTRUCTION: String = listOf(
        "You are now acting as a compaction engine for this AI coding assistant. Condense the " +
            "conversation ABOVE into a structured checkpoint that lets another model resume the work " +
            "with no loss of essential context.",
        "",
        "Output EXACTLY the Markdown structure below: keep every section, in order. Use terse bullets, " +
            "not prose paragraphs. Write \"(none)\" for an empty section — never drop a section.",
        "",
        "## Primary Request and Intent",
        "- [the user's original and evolving goals; quote verbatim where the exact wording matters]",
        "",
        "## Key Technical Concepts",
        "- [technologies, frameworks, patterns, and conventions in play]",
        "",
        "## Files and Code",
        "- [exact path: why it matters, key changes or snippets]",
        "",
        "## Errors and Fixes",
        "- [error: how it was resolved, plus any related user feedback]",
        "",
        "## Pending Jobs",
        "- [explicitly requested work not yet completed]",
        "",
        "## Current Work",
        "- [precisely what was in progress at this checkpoint]",
        "",
        "## Next Step",
        "- [the single next action, directly in line with the most recent request, or \"(none)\"]",
        "",
        "## Critical Context",
        "- [decisions and their rationale, constraints, user preferences, open questions, data needed to continue]",
        "",
        "Rules:",
        "- Write concise English engineering prose. Preserve exact file paths, commands, error strings, " +
            "identifiers, numeric values, function signatures, and syntax fragments.",
        "- Capture user feedback and explicit instructions faithfully, especially corrections.",
        "- Do NOT mention this summarization request or that the context was compacted.",
        "- Output only the checkpoint text: do not call any tool or take any other action.",
        "- If the conversation already contains a $SUMMARY_OPEN_TAG block, it is a PRIOR checkpoint. " +
            "Do not copy it forward verbatim: preserve still-true facts, drop stale ones, and merge " +
            "newer information into a single consolidated summary under the same structure.",
    ).joinToString("\n")

    /** 一条模型可见消息的价格（上游 `estimateContent()` + `estimateMessage()`：内容 + 4）。 */
    fun estimateMessageTokens(message: ChatMessage): Int = estimateContentTokens(message.content) + BLOCK_OVERHEAD

    fun estimateContentTokens(content: List<ContentBlock>): Int {
        var tokens = 0
        content.forEach { block ->
            tokens += when (block.type) {
                BlockType.TEXT -> ceilDiv(block.text.length, CHARS_PER_TOKEN) + BLOCK_OVERHEAD
                BlockType.REASONING -> 0 // 上游只价 text / tool_use / image：思考块不进会话面计价
                BlockType.TOOL_CALL -> ceilDiv(block.name.orEmpty().length, CHARS_PER_TOKEN) +
                    ceilDiv(block.arguments.length, CHARS_PER_TOKEN) + BLOCK_OVERHEAD
                // image：上游按附件引用的 JSON 长度计价（`JSON.stringify(reference)`）；原生保留 raw
                BlockType.IMAGE -> ceilDiv(
                    (block.raw?.toString() ?: block.text).length,
                    CHARS_PER_TOKEN,
                ) + BLOCK_OVERHEAD
                else -> ceilDiv(block.text.length, CHARS_PER_TOKEN) + BLOCK_OVERHEAD
            }
        }
        return tokens
    }

    /**
     * 选区里的一条消息：只要 [seq]（落 `shadowedSeqs` / `shadowedRange` 用）、
     * [tokens]（保留下限累计用）和「是不是工具结果」（边界不许切开调用/结果对）。
     */
    data class Node(val seq: Long, val tokens: Int, val toolResult: Boolean)

    /**
     * 上游 `selectCompactableRange()`：从最新往回累计，攒够 [retainTokens] 就停；
     * 边界落在工具结果上就往回退到它前面那条（工具调用与结果必须同侧）。
     *
     * @return 被压缩的**闭区间下标**（相对 [nodes]）；null = 没有可压缩的跨度
     *   （全在保留区里 / 边退到第一条）。
     */
    fun selectRange(nodes: List<Node>, retainTokens: Int): IntRange? {
        if (nodes.isEmpty()) return null
        val firstIdx = 0 // 原生会话面没有 system 头（system 是请求字段，不是消息）
        var accumulated = 0
        var keepFromIdx = nodes.size
        for (index in nodes.indices.reversed()) {
            accumulated += nodes[index].tokens
            keepFromIdx = index
            if (accumulated >= retainTokens) break
        }
        if (keepFromIdx <= firstIdx) return null
        while (keepFromIdx > firstIdx && nodes[keepFromIdx].toolResult) keepFromIdx -= 1
        if (keepFromIdx <= firstIdx) return null
        return firstIdx..(keepFromIdx - 1)
    }

    // ---- 溢出恢复（M45） ----

    /** 上游 `CONTEXT_WINDOW_EXCEEDED_CODE`：适配器把溢出文案归一成这个 code。 */
    const val CONTEXT_OVERFLOW_CODE = "CONTEXT_WINDOW_EXCEEDED"

    /** 溢出重试预算（上游 `maxOverflowRetries` 默认 1）；重试发生在同一个 step 里。 */
    const val MAX_OVERFLOW_RETRIES = 1

    // 下面五条正则逐字抄上游 `dsh-llm/lib/types/error.js` 的 `STRUCTURED_CONTEXT_OVERFLOW` /
    // `TOO_LARGE_FOR_CONTEXT` / `EXCEEDS_MODEL_CONTEXT`（大小写不敏感；`.` 不跨行，与 JS 一致）。
    private val STRUCTURED_CONTEXT_OVERFLOW = Regex(
        "(?:^|[^a-z0-9])context[\\s_-](?:length|window)[\\s_-]" +
            "(?:exceed(?:ed|s)?|overflow(?:ed)?|limit[\\s_-]exceeded)(?:${'$'}|[^a-z0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val MAX_CONTEXT = Regex(
        "\\b(?:maximum|max)(?:\\s+(?:allowed|supported))?\\s+context\\s+(?:length|window)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val TOO_LARGE_FOR_CONTEXT = Regex(
        "\\b(?:request|prompt|input|messages?)\\s+(?:is\\s+|are\\s+)?too\\s+(?:large|long)\\s+for\\s+" +
            "(?:(?:this|the)\\s+)?(?:model(?:'s)?\\s+)?context(?:\\s+window)?\\b",
        RegexOption.IGNORE_CASE,
    )
    private val TOO_LONG_FOR_MODEL = Regex(
        "\\b(?:input|prompt|request)\\s+(?:is\\s+)?too\\s+(?:long|large)\\s+for\\s+(?:this|the)\\s+model\\b",
        RegexOption.IGNORE_CASE,
    )
    private val EXCEEDS_MODEL_CONTEXT = Regex(
        "\\b(?:input|prompt|request|messages?)\\b.{0,40}\\b(?:exceed(?:s|ed)?|overflows?|is\\s+larger\\s+than)\\b" +
            ".{0,40}\\b(?:the\\s+)?(?:model(?:'s)?\\s+)?context(?:\\s+(?:length|window))?\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 上游 `isContextWindowExceededError(detail)`：把 provider 给的 code / type / message
     * 拼成一段 detail 再按五条正则认溢出。上游适配器会先把命中的文案归一成
     * [CONTEXT_OVERFLOW_CODE]，所以这里两个都认（原生 `LlmClient` 直接把 `error.type` 当 code，
     * DeepSeek 回的是 `invalid_request_error`，只能靠文案）。
     */
    fun isContextOverflow(code: String?, message: String?): Boolean {
        if (code != null && code.equals(CONTEXT_OVERFLOW_CODE, ignoreCase = true)) return true
        val detail = listOfNotNull(code, message).joinToString(" ")
        if (detail.isBlank()) return false
        return STRUCTURED_CONTEXT_OVERFLOW.containsMatchIn(detail) ||
            MAX_CONTEXT.containsMatchIn(detail) ||
            TOO_LARGE_FOR_CONTEXT.containsMatchIn(detail) ||
            TOO_LONG_FOR_MODEL.containsMatchIn(detail) ||
            EXCEEDS_MODEL_CONTEXT.containsMatchIn(detail)
    }

    // ---- 工具结果修剪（M46，上游 `dsh-compaction-tool-result-pruner`） ----

    /**
     * 中段省略标记（上游 `PRUNE_MARKER`，逐字）：39 个字符（4 个换行 + 35 个可见字符）。
     * 被剪掉的中段整体换成它，头尾各保留配置的字符数。
     */
    const val PRUNE_MARKER = "\n\n[... tool result middle pruned ...]\n\n"

    /** 修剪阈值：一条工具结果的文本超过这么多**码点**就剪（上游默认 8192）。 */
    const val PRUNE_THRESHOLD_CHARS = 8192

    /** 保留头部码点数（上游默认 4096）。 */
    const val PRUNE_HEAD_CHARS = 4096

    /** 保留尾部码点数（上游默认 1024）。 */
    const val PRUNE_TAIL_CHARS = 1024

    /**
     * 修剪预算（上游 `resolveConfig()` 的三字段契约）：`thresholdChars > 0`，
     * `headChars` / `tailChars` 非负，且 `head + marker + tail <= threshold`
     * （保证每条替换都更小、且不会反复重写）。
     */
    data class PruneConfig(
        val thresholdChars: Int = PRUNE_THRESHOLD_CHARS,
        val headChars: Int = PRUNE_HEAD_CHARS,
        val tailChars: Int = PRUNE_TAIL_CHARS,
    ) {
        init {
            require(thresholdChars > 0) { "ToolResultPruneConfig: thresholdChars must be a positive integer" }
            require(headChars >= 0) { "ToolResultPruneConfig: headChars must be a non-negative integer" }
            require(tailChars >= 0) { "ToolResultPruneConfig: tailChars must be a non-negative integer" }
            val emitted = headChars + PRUNE_MARKER.length + tailChars
            require(emitted <= thresholdChars) {
                "ToolResultPruneConfig: headChars + marker + tailChars ($emitted) must be at most " +
                    "thresholdChars ($thresholdChars)"
            }
        }

        companion object {
            /** 上游默认（`DEFAULTS`）：8192 / 4096 / 1024。 */
            val DEFAULT = PruneConfig()
        }
    }

    /** Unicode 码点长度（上游 `codePointLength()`：`Array.from(text).length`）。 */
    fun codePointLength(text: String): Int = text.codePointCount(0, text.length)

    /**
     * 工具结果文本的码点数（上游 `measureContent()`）：只价 `text` 块，富块（图片等）算 0。
     */
    fun measurePruneChars(content: List<ContentBlock>): Int = content.sumOf { block ->
        if (block.type == BlockType.TEXT) codePointLength(block.text) else 0
    }

    /**
     * 上游 `pruneContent()`：总文本超过 [PruneConfig.thresholdChars] 时，把「头 [headChars]
     * 之后、尾 [tailChars] 之前」的中段换成 [PRUNE_MARKER]，返回新内容；没超预算返回 null。
     *
     * 逐字照抄的三个细节：
     * - 文本按**码点**切（不会切开代理对；不做字素簇分割）；
     * - 非 text 块原样保留、保持相对顺序，且不计入 `consumed`（它们价 0）；
     * - 标记只插一次，落在「被删区间与该文本块相交」的第一块里；
     * - 头尾预算按**整条结果的文本流**算，不是每个块各算一份。
     */
    fun pruneContent(
        content: List<ContentBlock>,
        config: PruneConfig = PruneConfig.DEFAULT,
    ): List<ContentBlock>? {
        val totalChars = measurePruneChars(content)
        if (totalChars <= config.thresholdChars) return null
        val removedStart = config.headChars
        val removedEnd = totalChars - config.tailChars
        val pruned = ArrayList<ContentBlock>(content.size)
        var consumed = 0
        var markerInserted = false
        content.forEach { block ->
            if (block.type != BlockType.TEXT) {
                pruned += block
                return@forEach
            }
            val points = codePointsOf(block.text)
            val blockStart = consumed
            val blockEnd = blockStart + points.size
            val headEnd = minOf(points.size, maxOf(0, removedStart - blockStart))
            val tailStart = minOf(points.size, maxOf(0, removedEnd - blockStart))
            val marker = if (blockStart < removedEnd && blockEnd > removedStart && !markerInserted) {
                PRUNE_MARKER
            } else {
                ""
            }
            if (marker.isNotEmpty()) markerInserted = true
            val text = sliceCodePoints(points, 0, headEnd) + marker + sliceCodePoints(points, tailStart, points.size)
            if (text.isNotEmpty()) pruned += block.copy(text = text)
            consumed = blockEnd
        }
        check(markerInserted) { "tool-result prune: failed to locate the removed text span" }
        val charsAfter = measurePruneChars(pruned)
        check(charsAfter <= config.thresholdChars && charsAfter < totalChars) {
            "tool-result prune: replacement must be smaller and within threshold"
        }
        return pruned
    }

    private fun codePointsOf(text: String): IntArray {
        val count = text.codePointCount(0, text.length)
        val points = IntArray(count)
        var offset = 0
        var index = 0
        while (offset < text.length) {
            val cp = text.codePointAt(offset)
            points[index++] = cp
            offset += Character.charCount(cp)
        }
        return points
    }

    private fun sliceCodePoints(points: IntArray, from: Int, to: Int): String {
        if (to <= from) return ""
        val sb = StringBuilder(to - from)
        for (index in from until to) sb.appendCodePoint(points[index])
        return sb.toString()
    }

    /** 上游 `frameSummary()`：三段 text（前言 + 开标签 / 摘要 / 闭标签）。 */
    fun frameSummary(summary: String): List<String> = listOf(
        "$CHECKPOINT_PREAMBLE\n\n$SUMMARY_OPEN_TAG",
        summary,
        SUMMARY_CLOSE_TAG,
    )

    /** `/compact` 成功文案（上游 `Compacted ${n} history items (~${t} tokens).`）。 */
    fun resultText(items: Int, tokens: Int): String = "Compacted $items history items (~$tokens tokens)."

    /** 保留预算：当前历史价 × 0.16，不低于 2048（见类注释的偏差说明）。 */
    fun retainBudgetFor(totalTokens: Int): Int =
        maxOf(MIN_RETAIN_TOKENS, (totalTokens * RETAIN_RATIO).toInt())

    /**
     * 自动压缩（M44）的容量策略：由「上下文窗口 W、请求输出上限 O」解出触发线与保留预算。
     *
     * 逐字抄上游 `resolveCompactSpec()`：
     * `threshold = floor(min(W × 0.8, W − O − B))`、`retainTokens = floor((W − O) × 0.16)`。
     * **唯一偏差**：headroom `B` 取 `min(65536, W/8)` 而不是固定 65536 —— 手机上会有 8k/16k
     * 窗口的自定义模型，固定 64k 余量会让 `W − O − B` 永远为负、自动压缩直接不可用；
     * 窗口 ≥ 524288 时 `W/8 ≥ 65536`，与上游完全一致。
     *
     * 返回 null = 这组窗口/输出配置不成立（自动压缩跳过，手工 `/compact` 不受影响；
     * 上游对这种情况也是「警告一次、跳过自动压缩」）。
     */
    data class Policy(val thresholdTokens: Int, val retainTokens: Int) {
        companion object {
            const val THRESHOLD_RATIO = 0.8
            const val HEADROOM_TOKENS = 65_536

            fun resolve(contextWindow: Int, maxOutputTokens: Int): Policy? {
                if (contextWindow <= 0) return null
                val output = maxOutputTokens.coerceAtLeast(0)
                val headroom = minOf(HEADROOM_TOKENS, contextWindow / 8)
                val pressureBudget = contextWindow - output - headroom
                if (pressureBudget <= 0) return null
                val threshold = minOf((contextWindow * THRESHOLD_RATIO).toInt(), pressureBudget)
                val retain = maxOf(MIN_RETAIN_TOKENS, ((contextWindow - output) * RETAIN_RATIO).toInt())
                if (threshold <= retain) return null
                return Policy(threshold, retain)
            }
        }
    }

    /** 摘要请求的产物（手工 `/compact` 与自动压缩共用；只收文本，reasoning 进 rawOutput）。 */
    data class Summary(
        val text: String,
        val rawOutput: JsonArray,
        val maxTokens: Int,
        val usage: JsonObject,
    )

    private fun ceilDiv(value: Int, div: Int): Int = (value + div - 1) / div
}
