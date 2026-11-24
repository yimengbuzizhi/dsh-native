package xin.dshaa.core.model

/** 同一次模型请求的用量与分段比例。null 是未报告，明确的零必须保留。 */
data class ContextUsage(
    val requestNo: Int,
    val model: String = "",
    val protocol: String = "",
    val window: Int = 0,
    val status: String = "started",
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cacheReadTokens: Int? = null,
    val cacheCreationTokens: Int? = null,
    val estimatedSystem: Int = 0,
    val estimatedTools: Int = 0,
    val estimatedMessages: Int = 0,
    val imageCount: Int = 0,
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /**
     * 整份提示词的实际 token = 未缓存输入 + 缓存读 + 缓存写（DSH 口径）。
     * [inputTokens] 只含未缓存部分，所以上下文占用/分段比例必须用这个，否则缓存命中时会显示成
     * 「只用了几百 token」。
     */
    val totalInputTokens: Int? get() = inputTokens?.let { input ->
        (input.toLong() + (cacheReadTokens ?: 0) + (cacheCreationTokens ?: 0))
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * 缓存命中率 = 缓存读 / 整份提示词（未缓存输入 + 缓存读 + 缓存写）。
     *
     * 口径与上游 `dsh-token-meter` 的 `tokenUsage` 一致：命中率只描述「这次请求的提示词里有多少
     * 是缓存读回来的」，不掺输出。缓存读没有报告（例如端点不返回）时返回 null——
     * **不要把它当 0**，`ContextUsage` 的约定是「null = 未报告，0 = 明确的零」。
     */
    val cacheHitRate: Double? get() {
        val read = cacheReadTokens ?: return null
        val prompt = totalInputTokens ?: return null
        if (prompt <= 0) return null
        return read.toDouble() / prompt
    }

    /** 端点是否报告了「缓存写」；DeepSeek 的自动缓存不返回这个字段，不能显示成 0。 */
    val cacheWriteKnown: Boolean get() = cacheCreationTokens != null

    /** 有人报告过缓存读/写（null 或 0 都算报告过；null 才是未报告）。 */
    val cacheReported: Boolean get() = cacheReadTokens != null || cacheCreationTokens != null

    /** 按估算权重分配真实总数；使用最大余数法，三段之和始终等于实际输入（未缓存 + 缓存读/写）。 */
    fun allocatedBreakdown(): List<Int> {
        val total = totalInputTokens ?: return listOf(estimatedSystem, estimatedTools, estimatedMessages)
        val weights = listOf(estimatedSystem, estimatedTools, estimatedMessages).map { it.coerceAtLeast(0).toLong() }
        val sum = weights.sum()
        if (sum == 0L) return listOf(0, 0, total.coerceAtLeast(0))
        val products = weights.map { it * total.coerceAtLeast(0) }
        val parts = products.map { (it / sum).toInt() }.toMutableList()
        val remaining = total.coerceAtLeast(0) - parts.sum()
        products.indices.sortedByDescending { products[it] % sum }.take(remaining).forEach { parts[it]++ }
        return parts
    }
}
