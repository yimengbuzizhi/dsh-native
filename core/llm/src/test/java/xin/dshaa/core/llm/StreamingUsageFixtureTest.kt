package xin.dshaa.core.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import xin.dshaa.core.protocol.ApiMessage
import xin.dshaa.core.protocol.ApiRequest
import xin.dshaa.core.protocol.ContentBlock
import xin.dshaa.core.protocol.ProtocolJson
import xin.dshaa.core.protocol.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real client HTTP response handling + SSE parsing, with an interceptor instead of external sockets. */
class StreamingUsageFixtureTest {
    private val request = ApiRequest("fixture", messages = listOf(ApiMessage("user", listOf(ContentBlock.Text("hi")))), maxTokens = 128)
    private val config = LlmConfig(apiKey = "fixture-key", baseUrl = "https://fixture.invalid", model = "fixture")

    private fun http(sse: String, onRequest: (String) -> Unit = {}): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val body = Buffer()
            chain.request().body!!.writeTo(body)
            onRequest(body.readUtf8())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(sse.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()

    private fun sse(vararg chunks: String): String = chunks.joinToString("") { "data: $it\n\n" }
    private fun List<LlmEvent>.usage(): TokenUsage = fold(TokenUsage()) { usage, event ->
        when (event) {
            is LlmEvent.MessageStart -> usage.merge(event.usage)
            is LlmEvent.MessageDelta -> usage.merge(event.usage)
            else -> usage
        }
    }

    @Test
    fun `openai finish then empty choices tail reports usage and requests it`() = runBlocking {
        var body = ""
        val events = OpenAiClient(http(sse(
            """{"id":"m","choices":[{"delta":{"content":"hi"},"finish_reason":null}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":80}}}""",
            "[DONE]",
        )) { body = it }).stream(request, config).toList()
        val options = ProtocolJson.json.parseToJsonElement(body).jsonObject["stream_options"]!!.jsonObject
        assertEquals("true", options["include_usage"]!!.jsonPrimitive.content)
        assertEquals(TokenUsage(100, 7, 80), events.usage())
        assertEquals(107, events.usage().totalTokens, "cached 80 is already part of prompt 100")
        assertEquals(1, events.count { it is LlmEvent.MessageStop })
        assertEquals("end_turn", events.filterIsInstance<LlmEvent.MessageDelta>().last().stopReason)
        assertEquals("hi", events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text })
        assertFalse(events.any { it is LlmEvent.Failed })
    }

    @Test
    fun `openai absent usage versus zero and normal EOF`() = runBlocking {
        val missing = OpenAiClient(http(sse("""{"choices":[{"delta":{},"finish_reason":"length"}]}""")))
            .stream(request, config).toList()
        assertNull(missing.usage().inputTokens)
        assertNull(missing.usage().outputTokens)
        assertEquals("max_tokens", missing.filterIsInstance<LlmEvent.MessageDelta>().last().stopReason)
        val zero = OpenAiClient(http(sse(
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":0,"completion_tokens":0}}""",
        ))).stream(request, config).toList()
        assertEquals(TokenUsage(0, 0), zero.usage())
        assertEquals(LlmEvent.MessageStop, zero.last())
    }

    @Test
    fun `openai repeated cumulative usage and flat deepseek cache remain snapshots`() = runBlocking {
        val events = OpenAiClient(http(sse(
            """{"choices":[{"delta":{}}],"usage":{"prompt_tokens":100,"prompt_cache_hit_tokens":75,"completion_tokens":3}}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}],"usage":{"completion_tokens":7}}""",
            """{"choices":[],"usage":{"completion_tokens":7}}""",
            "[DONE]",
        ))).stream(request, config).toList()
        assertEquals(TokenUsage(100, 7, 75), events.usage())
        assertEquals("tool_use", events.filterIsInstance<LlmEvent.MessageDelta>().last().stopReason)
    }

    @Test
    fun `anthropic client preserves cache and cumulative output across missing stop usage`() = runBlocking {
        val events = LlmClient(http(sse(
            """{"type":"message_start","message":{"id":"m","usage":{"input_tokens":12,"output_tokens":0,"cache_read_input_tokens":80,"cache_creation_input_tokens":8}}}""",
            """{"type":"message_delta","usage":{"output_tokens":3}}""",
            """{"type":"message_delta","usage":{"output_tokens":7}}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            """{"type":"message_stop"}""",
        ))).stream(request, config).toList()
        assertEquals(TokenUsage(100, 7, 80, 8), events.usage())
        assertTrue(events.any { it is LlmEvent.MessageStop })
    }
}
