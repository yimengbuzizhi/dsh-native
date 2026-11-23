package xin.dshaa.core.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import xin.dshaa.core.protocol.ApiMessage
import xin.dshaa.core.protocol.ApiRequest
import xin.dshaa.core.protocol.ContentBlock
import xin.dshaa.core.protocol.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 工作流 D（docs/187 §7.4）：两条通道对同一份原始 usage 必须算出同一个 total。
 *
 * - OpenAI chat/completions：`prompt_tokens` 含缓存读，`inputTokens = prompt_tokens - cached_tokens`；
 * - Anthropic Messages：`input_tokens` 不含缓存读/写，直接当未缓存输入；
 * - cache 读/写单独记，`totalTokens = input + cacheRead + cacheCreation + output`。
 */
class UsageAlignmentTest {
    private val request = ApiRequest(
        "fixture",
        messages = listOf(ApiMessage("user", listOf(ContentBlock.Text("hi")))),
        maxTokens = 128,
    )
    private val config = LlmConfig(apiKey = "fixture-key", baseUrl = "https://fixture.invalid", model = "fixture")

    private fun http(sse: String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
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
    fun `openai and anthropic totals agree for the same raw usage`() = runBlocking {
        // 同一份原始用量：100 个提示词 token（80 命中缓存）+ 7 个输出 token。
        val openai = OpenAiClient(http(sse(
            """{"id":"m","choices":[{"delta":{"content":"hi"},"finish_reason":"stop"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":80}}}""",
            "[DONE]",
        ))).stream(request, config).toList().usage()
        // Anthropic 的 input_tokens 不含缓存：未缓存 20 + 缓存读 80 = 100。
        val anthropic = LlmClient(http(sse(
            """{"type":"message_start","message":{"id":"m","usage":{"input_tokens":20,"output_tokens":0,"cache_read_input_tokens":80}}}""",
            """{"type":"message_delta","usage":{"output_tokens":7}}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            """{"type":"message_stop"}""",
        ))).stream(request, config).toList().usage()

        assertEquals(TokenUsage(20, 7, 80), openai)
        assertEquals(TokenUsage(20, 7, 80), anthropic)
        assertEquals(107, openai.totalTokens)
        assertEquals(openai.totalTokens, anthropic.totalTokens, "两条通道同口径：100 + 7")
    }

    @Test
    fun `openai without cache fields reports no cache read`() = runBlocking {
        val usage = OpenAiClient(http(sse(
            """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":7}}""",
            "[DONE]",
        ))).stream(request, config).toList().usage()
        assertEquals(TokenUsage(100, 7), usage)
        assertNull(usage.cacheReadInputTokens, "没有 cached_tokens / prompt_cache_hit_tokens 就不产生缓存读")
        assertNull(usage.cacheCreationInputTokens)
        assertEquals(107, usage.totalTokens)
    }

    @Test
    fun `anthropic cache write is counted once in the total`() = runBlocking {
        val usage = LlmClient(http(sse(
            """{"type":"message_start","message":{"id":"m","usage":{"input_tokens":12,"output_tokens":0,"cache_read_input_tokens":80,"cache_creation_input_tokens":8}}}""",
            """{"type":"message_delta","usage":{"output_tokens":7}}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            """{"type":"message_stop"}""",
        ))).stream(request, config).toList().usage()
        assertEquals(TokenUsage(12, 7, 80, 8), usage)
        // 12 未缓存 + 80 读 + 8 写 + 7 输出；缓存写只加一次（input 里没有它）。
        assertEquals(107, usage.totalTokens)
        assertEquals(100, usage.totalInputTokens)
    }
}
