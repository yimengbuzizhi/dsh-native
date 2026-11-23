package xin.dshaa.core.protocol

/**
 * One request's cumulative usage snapshot (never a delta).
 *
 * DSH 口径（`@deepseek-ai/dsh-llm-deepseek`）：
 * - [inputTokens] = **未缓存输入**。OpenAI 通道是 `prompt_tokens - cacheRead`；
 *   Anthropic 通道的 `input_tokens` 本来就不含缓存读/写，直接用。
 * - [cacheReadInputTokens] / [cacheCreationInputTokens] 单独记，**不是** [inputTokens] 的子集，
 *   不能再加回 [inputTokens] 或从中重复扣除。
 * - [totalTokens] = input + cacheRead + cacheCreation + output（缓存写计入总价口径，
 *   与 Anthropic 的 `cache_creation_input_tokens` 计费一致；OpenAI 没有 cache 写时该项为 0）。
 *
 * null 表示未报告，零是明确的零。
 * Providers normalize their wire format before emitting this contract.
 */
data class TokenUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cacheReadInputTokens: Int? = null,
    val cacheCreationInputTokens: Int? = null,
) {
    /** Missing fields leave the current request's known values intact, including explicit zero. */
    fun merge(update: TokenUsage): TokenUsage = TokenUsage(
        inputTokens = update.inputTokens ?: inputTokens,
        outputTokens = update.outputTokens ?: outputTokens,
        cacheReadInputTokens = update.cacheReadInputTokens ?: cacheReadInputTokens,
        cacheCreationInputTokens = update.cacheCreationInputTokens ?: cacheCreationInputTokens,
    )

    val hasReportedTokens: Boolean get() = inputTokens != null || outputTokens != null ||
        cacheReadInputTokens != null || cacheCreationInputTokens != null

    /** 本次请求的总 token：未缓存输入 + 缓存读 + 缓存写 + 输出（两套通道同口径）。 */
    val totalTokens: Int? get() = if (inputTokens != null && outputTokens != null) {
        (inputTokens.toLong() + (cacheReadInputTokens ?: 0) + (cacheCreationInputTokens ?: 0) + outputTokens)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    } else null

    /** 整份提示词侧的 token（未缓存输入 + 缓存读 + 缓存写）；上下文占用用这个，不是 [inputTokens]。 */
    val totalInputTokens: Int? get() = if (inputTokens != null) {
        (inputTokens.toLong() + (cacheReadInputTokens ?: 0) + (cacheCreationInputTokens ?: 0))
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    } else null
}
