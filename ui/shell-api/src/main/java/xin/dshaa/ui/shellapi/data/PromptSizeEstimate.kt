package xin.dshaa.ui.shellapi.data

/**
 * docs/190-D：提示词字数 / ≈token 估算（助手配置页显示与超长提醒）。
 *
 * 为什么放 `ui/shell-api`：助手配置页在 `ui/native-shell`，不能反向依赖 `app`；
 * 估算规则必须和 App 侧「上下文明细」面板同一口径（`AppViewModel.estimateTokens`）：
 * CJK（0x2E80–0x9FFF、0xFF00–0xFFEF）按 1 字 ≈ 1 token，其它字符按 4 字 ≈ 1 token 向上取整。
 * 只能当量级参考，不是精确分词。
 */
object PromptSizeEstimate {

    /** 超过这个估算值就在助手配置页提醒「建议拆分」（任务书：>8k tokens 建议拆分）。 */
    const val LONG_PROMPT_TOKENS: Int = 8_000

    data class Size(val chars: Int, val approxTokens: Int) {
        val long: Boolean get() = approxTokens > LONG_PROMPT_TOKENS
    }

    fun of(text: String): Size {
        if (text.isEmpty()) return Size(0, 0)
        var cjk = 0
        var other = 0
        for (ch in text) {
            val code = ch.code
            if (code in 0x2E80..0x9FFF || code in 0xFF00..0xFFEF) cjk++ else other++
        }
        return Size(chars = text.length, approxTokens = cjk + (other + 3) / 4)
    }
}
