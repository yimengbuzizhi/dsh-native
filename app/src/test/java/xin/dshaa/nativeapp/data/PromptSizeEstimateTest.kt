package xin.dshaa.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dshaa.ui.shellapi.data.PromptSizeEstimate

/**
 * docs/190-D：助手配置页的字数 / ≈token 估算。
 *
 * 与 `AppViewModel.estimateTokens` 同一口径（CJK 1 字 ≈ 1 token，其它 4 字 ≈ 1 token 向上取整）；
 * 这里锁住纯函数，防止 UI 文案以后和上下文明细面板算得不一样。
 */
class PromptSizeEstimateTest {

    @Test fun emptyTextIsZero() {
        val size = PromptSizeEstimate.of("")
        assertEquals(0, size.chars)
        assertEquals(0, size.approxTokens)
        assertFalse(size.long)
    }

    @Test fun asciiIsRoughlyFourCharsPerToken() {
        assertEquals(1, PromptSizeEstimate.of("abcd").approxTokens)
        assertEquals(2, PromptSizeEstimate.of("abcde").approxTokens)
        assertEquals(8000, PromptSizeEstimate.of("a".repeat(32_000)).approxTokens)
    }

    @Test fun cjkCountsOneTokenPerChar() {
        assertEquals(3, PromptSizeEstimate.of("鲸歌呀").approxTokens)
        assertEquals(3, PromptSizeEstimate.of("鲸歌呀").chars)
    }

    @Test fun mixedTextAddsBoth() {
        // 4 ASCII = 1 token + 2 CJK = 2 tokens
        assertEquals(3, PromptSizeEstimate.of("abcd鲸歌").approxTokens)
    }

    @Test fun longFlagAtEightThousandTokens() {
        assertFalse(PromptSizeEstimate.of("a".repeat(32_000)).long) // 恰好 8000
        assertTrue(PromptSizeEstimate.of("a".repeat(32_004)).long) // 8001
        // 中文 8001 字 = 8001 tokens
        assertTrue(PromptSizeEstimate.of("鲸".repeat(8_001)).long)
    }

    @Test fun markerStyleLongPromptIsLong() {
        // docs/190-D 取证用的 2100 字 marker 本身不算「超长」，两个字段都 2000+ 也只是量级参考
        val size = PromptSizeEstimate.of("PERSONA::AAA\n" + "P".repeat(2_100))
        assertEquals(2_113, size.chars)
        assertEquals(529, size.approxTokens)
        assertFalse(size.long)
    }
}
