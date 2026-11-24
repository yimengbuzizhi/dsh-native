package xin.dshaa.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 输出上限口径（2026-11-24 取消 8192）：默认 256k、按窗口收一半、未知窗口不收窄。
 */
class OutputBudgetTest {

    @Test fun defaultIsUpstream256k() {
        assertEquals(256_000, OutputBudget.DEFAULT_MAX_TOKENS)
        // deepseek-flash 窗口 1048576：一半是 524288，比默认值大 → 仍然发 256k
        assertEquals(256_000, OutputBudget.effective(OutputBudget.DEFAULT_MAX_TOKENS, 1_048_576))
    }

    @Test fun unknownWindowKeepsDesired() {
        assertEquals(256_000, OutputBudget.effective(OutputBudget.DEFAULT_MAX_TOKENS, 0))
        assertEquals(2_048, OutputBudget.effective(2_048, 0))
    }

    @Test fun knownWindowCapsAtHalf() {
        // 128k 窗口 → 64k 输出上限（仍然远大于旧的 8192）
        assertEquals(64_000, OutputBudget.effective(OutputBudget.DEFAULT_MAX_TOKENS, 128_000))
        // 8k 窗口 → 4096
        assertEquals(4_096, OutputBudget.effective(OutputBudget.DEFAULT_MAX_TOKENS, 8_192))
    }

    @Test fun tinyWindowNeverGoesBelowFloor() {
        assertEquals(OutputBudget.MIN_MAX_TOKENS, OutputBudget.effective(OutputBudget.DEFAULT_MAX_TOKENS, 100))
        assertEquals(OutputBudget.MIN_MAX_TOKENS, OutputBudget.effective(1, 100))
    }

    @Test fun desiredSmallerThanCapWins() {
        // 调试通道给 2048：窗口再大也只发 2048
        assertEquals(2_048, OutputBudget.effective(2_048, 1_048_576))
    }

    @Test fun compactionPolicyAlwaysResolvableWithHalfWindowCap() {
        // O = W/2 时 W − O − headroom = W/2 − headroom > 0（headroom ≤ W/8），自动压缩一定有解；
        // 这条约束是 effective() 收一半的原因，窗口小到 16k 也不能例外。
        for (window in listOf(8_192, 16_384, 65_536, 128_000, 1_048_576)) {
            val output = OutputBudget.effective(OutputBudget.DEFAULT_MAX_TOKENS, window)
            val headroom = minOf(65_536, window / 8)
            assert(window - output - headroom > 0) { "window=$window output=$output headroom=$headroom" }
        }
    }
}
