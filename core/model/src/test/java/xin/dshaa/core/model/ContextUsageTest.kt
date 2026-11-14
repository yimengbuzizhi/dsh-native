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
}
