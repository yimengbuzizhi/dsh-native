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
    /** 按估算权重分配真实总数；使用最大余数法，三段之和始终等于实际输入。 */
    fun allocatedBreakdown(): List<Int> {
        val total = inputTokens ?: return listOf(estimatedSystem, estimatedTools, estimatedMessages)
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
