package xin.dshaa.core.model

/**
 * 单轮输出上限（`max_tokens`）的口径。
 *
 * 2026-11-24 用户要求「取消 8000 多的输出限制」：0.3.6 及以前请求固定 `max_tokens=8192`
 * （2026-11-09 删掉设置项时写死的 DEFAULT），长回复/长文件会被截断。
 *
 * 对齐上游的口径：`dsh-llm-deepseek` 的适配器默认 `maxTokens = 256_000`
 * （`.upstream/oracle/.../dsh-llm-deepseek/lib/index.js`：`maxTokens: config.maxTokens ?? 256e3`），
 * 模型目录里有 `models[].maxTokens` 时以模型为准。手机端没有那套模型目录，于是：
 *
 * - 默认按上游 **256_000** 发；
 * - 已知上下文窗口时再收一半（`window / 2`），保证 `max_tokens` 不会把窗口撑满，
 *   也让自动压缩的 [xin.dshaa.core.engine.Compaction.Policy] 永远有解
 *   （它要求 `W > O + headroom`，O 取 W/2 时恒成立）；
 * - 窗口未知（0，自动压缩关闭）时只发默认值，不再额外收窄。
 *
 * 这里只做纯计算，方便 JVM 单测；设置与展示在 `SettingsStore`。
 */
object OutputBudget {

    /** 上游 `dsh-llm-deepseek` 的适配器默认输出上限。 */
    const val DEFAULT_MAX_TOKENS = 256_000

    /** 再小就没有意义，且会突破上游 schema 的 `min(1)` 语义底线。 */
    const val MIN_MAX_TOKENS = 1_024

    /**
     * 实际发给接口的 `max_tokens`。
     *
     * @param desired 设置里的期望值（默认 [DEFAULT_MAX_TOKENS]；调试通道可能给更小的值）
     * @param contextWindow 当前模型的上下文窗口；0 = 未知
     */
    fun effective(desired: Int, contextWindow: Int): Int {
        val wanted = desired.coerceAtLeast(MIN_MAX_TOKENS)
        if (contextWindow <= 0) return wanted
        return minOf(wanted, (contextWindow / 2).coerceAtLeast(MIN_MAX_TOKENS))
    }
}
