package xin.dshaa.core.protocol

/**
 * One request's cumulative usage snapshot (never a delta).
 * null means unreported; zero is a reported zero. [inputTokens] includes cache reads/writes;
 * cache fields are diagnostic subsets and MUST NOT be added to it again.
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

    val totalTokens: Int? get() = if (inputTokens != null && outputTokens != null) {
        (inputTokens.toLong() + outputTokens).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    } else null
}
