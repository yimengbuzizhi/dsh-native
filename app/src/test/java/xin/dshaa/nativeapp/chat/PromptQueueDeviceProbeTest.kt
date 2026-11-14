package xin.dshaa.nativeapp.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dshaa.core.llm.LlmConfig
import xin.dshaa.core.llm.LlmEvent
import xin.dshaa.core.protocol.ApiMessage
import xin.dshaa.core.protocol.ApiRequest
import xin.dshaa.core.protocol.ContentBlock

/** Provider guarantees required by the real AppVM fixture; no Android fake or user credentials. */
class PromptQueueDeviceProbeTest {
    private val config = LlmConfig(apiKey = "", model = "fixture")
    private fun request(vararg texts: String) = ApiRequest(
        model = "fixture",
        messages = texts.map { ApiMessage("user", listOf(ContentBlock.Text(it))) },
        maxTokens = 32,
    )

    @Test fun initialRequestLeavesVisibleCancellableEnqueueWindow() = runBlocking {
        val events = ArrayList<LlmEvent>()
        val prefix = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            DebugM86QueueProvider(1_500).stream(request("[M86G:run:stop:INIT:OK]"), config).collect {
                events += it
                if (it is LlmEvent.TextDelta) prefix.complete(Unit)
            }
        }
        withTimeout(1_000) { prefix.await() }
        assertTrue(job.isActive)
        assertEquals(DebugM86QueueProvider.PARTIAL, events.filterIsInstance<LlmEvent.TextDelta>().single().text)
        assertFalse(events.any { it is LlmEvent.MessageDelta || it is LlmEvent.MessageStop })
        job.cancelAndJoin()
        assertFalse(events.any { it is LlmEvent.MessageStop })
    }

    @Test fun errorAndMaxTokenTagsProduceDistinctTerminalPaths() = runBlocking {
        val provider = DebugM86QueueProvider(1_500)
        val error = provider.stream(request("[M86G:run:error:INIT:ERROR]"), config).toList()
        assertEquals("M86G_FIXTURE_ERROR", error.filterIsInstance<LlmEvent.Failed>().single().code)
        assertFalse(error.any { it is LlmEvent.MessageStop })
        val max = provider.stream(request("[M86G:run:max:INIT:MAX_TOKEN]"), config).toList()
        assertEquals("max_tokens", max.filterIsInstance<LlmEvent.MessageDelta>().single().stopReason)
        assertEquals(LlmEvent.MessageStop, max.last())
    }

    @Test fun latestQueuedInputOverridesHistoricalFailureTag() = runBlocking {
        val events = withTimeout(1_000) {
            DebugM86QueueProvider().stream(request(
                "[M86G:run:error:INIT:ERROR]",
                "[M86G:run:error:INPUT:RESUME]",
                "unrelated fixture system notice",
            ), config).toList()
        }
        assertFalse(events.any { it is LlmEvent.Failed })
        assertEquals("end_turn", events.filterIsInstance<LlmEvent.MessageDelta>().single().stopReason)
        assertEquals(LlmEvent.MessageStop, events.last())
    }

    @Test fun untaggedRequestFailsLocally() = runBlocking {
        val events = DebugM86QueueProvider().stream(request("fixture without a marker"), config).toList()
        assertEquals("M86G_FIXTURE_INPUT", events.filterIsInstance<LlmEvent.Failed>().single().code)
        assertEquals(1, events.size)
    }
}
