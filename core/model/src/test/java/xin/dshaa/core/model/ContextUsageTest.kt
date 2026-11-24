package xin.dshaa.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ContextUsageTest {
    @Test fun roundingPreservesTotalForSmallInputs() {
        for (total in 0..20) {
            val result = ContextUsage(1, inputTokens = total, estimatedSystem = 1,
                estimatedTools = 1, estimatedMessages = 1).allocatedBreakdown()
            assertEquals(total, result.sum())
        }
    }

    @Test fun weightsAreProportionalAndNonnegative() {
        assertEquals(listOf(10, 20, 70), ContextUsage(1, inputTokens = 100,
            estimatedSystem = 1, estimatedTools = 2, estimatedMessages = 7).allocatedBreakdown())
        assertEquals(listOf(0, 0, 5), ContextUsage(1, inputTokens = 5).allocatedBreakdown())
    }

    @Test fun reportedZeroDiffersFromMissing() {
        val unknown = ContextUsage(1, estimatedSystem = 5, estimatedTools = 3, estimatedMessages = 2)
        assertEquals(listOf(5, 3, 2), unknown.allocatedBreakdown())
        assertEquals(listOf(0, 0, 0), unknown.copy(inputTokens = 0).allocatedBreakdown())
    }

    @Test fun validLowerInputReplacesPreviousSize() {
        val previous = ContextUsage(1, inputTokens = 10000, estimatedSystem = 10, estimatedMessages = 90)
        val next = previous.copy(requestNo = 2, inputTokens = 2000)
        assertEquals(2000, next.allocatedBreakdown().sum())
    }

    @Test fun cacheReadAndWriteCountTowardPromptOccupancy() {
        // DSH 口径：inputTokens 只是未缓存输入；占用/分段要把缓存读/写加回来。
        val usage = ContextUsage(1, inputTokens = 20, cacheReadTokens = 80, cacheCreationTokens = 8,
            estimatedSystem = 1, estimatedTools = 1, estimatedMessages = 2)
        assertEquals(108, usage.totalInputTokens)
        assertEquals(108, usage.allocatedBreakdown().sum())
        // 缺 input 时仍按估算展示（未报告 ≠ 0）。
        assertEquals(listOf(1, 1, 2), usage.copy(inputTokens = null).allocatedBreakdown())
    }

    @Test fun cacheHitRateIsCacheReadOverWholePrompt() {
        // 上游 dsh-token-meter 口径：缓存读 /（未缓存 + 缓存读 + 缓存写）。
        val usage = ContextUsage(1, inputTokens = 200, cacheReadTokens = 800, cacheCreationTokens = 0)
        assertEquals(0.8, usage.cacheHitRate!!, 1e-9)
        // 缓存写为 0 是「明确的零」，命中率照样能算。
        assertEquals(0.8, usage.copy(cacheCreationTokens = null).cacheHitRate!!, 1e-9)
    }

    @Test fun cacheHitRateIsNullWhenEndpointDoesNotReport() {
        // 未报告 ≠ 0：不能显示成 0% 命中。
        assertEquals(null, ContextUsage(1, inputTokens = 100).cacheHitRate)
        assertEquals(null, ContextUsage(1, inputTokens = null, cacheReadTokens = 50).cacheHitRate)
        assertEquals(null, ContextUsage(1, inputTokens = 0, cacheReadTokens = 0).cacheHitRate)
    }

    @Test fun cacheWriteKnownDistinguishesZeroFromMissing() {
        assertEquals(false, ContextUsage(1).cacheWriteKnown)
        assertEquals(true, ContextUsage(1, cacheCreationTokens = 0).cacheWriteKnown)
        assertEquals(true, ContextUsage(1, cacheCreationTokens = 42).cacheWriteKnown)
    }
}
