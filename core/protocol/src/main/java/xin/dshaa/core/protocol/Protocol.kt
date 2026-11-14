package xin.dshaa.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * DSH 上游的真实协议：**Anthropic Messages API**（不是 OpenAI chat/completions）。
 *
 * 端点：`{baseURL}/v1/messages`，默认 `https://api.deepseek.com/anthropic`
 * 认证：`x-api-key`（或 `x-dsh-auth-token`），版本头 `anthropic-version: 2023-06-01`
 *
 * 字段顺序 = 类内声明顺序，序列化 `encodeDefaults=false` / `explicitNulls=false`，
 * 这是契约 #1「请求体逐字节一致」的前提，改动顺序前先跑对拍。
 */
@Serializable
data class ApiRequest(
    val model: String,
    val stream: Boolean = true,
    val messages: List<ApiMessage>,
    @SerialName("max_tokens") val maxTokens: Int,
    val thinking: ThinkingConfig? = null,
    @SerialName("output_config") val outputConfig: OutputConfig? = null,
    val system: String? = null,
    val temperature: Double? = null,
    @SerialName("stop_sequences") val stopSequences: List<String>? = null,
    val tools: List<ApiTool>? = null,
)

@Serializable
data class ThinkingConfig(val type: String, val budgetTokens: Int? = null)

@Serializable
data class OutputConfig(val effort: String? = null)

@Serializable
data class ApiMessage(
    val role: String,
    val content: List<ContentBlock>,
)

/** Anthropic content block；type 作为序列化判别器。 */
@Serializable
sealed interface ContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentBlock

    @Serializable
    @SerialName("image")
    data class Image(val source: ImageSource) : ContentBlock

    @Serializable
    @SerialName("thinking")
    data class Thinking(val thinking: String, val signature: String? = null) : ContentBlock

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject,
    ) : ContentBlock

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        @SerialName("tool_use_id") val toolUseId: String,
        val content: List<ContentBlock>? = null,
        @SerialName("is_error") val isError: Boolean? = null,
    ) : ContentBlock
}

@Serializable
data class ImageSource(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String,
    val data: String,
)

@Serializable
data class ApiTool(
    val name: String,
    val description: String? = null,
    @SerialName("input_schema") val inputSchema: JsonObject,
    @SerialName("defer_loading") val deferLoading: Boolean? = null,
)

// ---------------- 流式事件（SSE data 里的 JSON） ----------------

object StreamEvents {
    const val MESSAGE_START = "message_start"
    const val MESSAGE_DELTA = "message_delta"
    const val MESSAGE_STOP = "message_stop"
    const val CONTENT_BLOCK_START = "content_block_start"
    const val CONTENT_BLOCK_DELTA = "content_block_delta"
    const val CONTENT_BLOCK_STOP = "content_block_stop"
    const val PING = "ping"
    const val ERROR = "error"
}

@Serializable
data class StreamErrorBody(val type: String = "error", val error: StreamErrorDetail? = null)

@Serializable
data class StreamErrorDetail(val type: String? = null, val message: String = "")

@Serializable
data class MessageStartPayload(
    val message: StreamMessage? = null,
)

@Serializable
data class StreamMessage(
    val id: String? = null,
    val model: String? = null,
    val usage: StreamUsage? = null,
)

@Serializable
data class StreamUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int? = null,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int? = null,
) {
    /** Anthropic input_tokens excludes the two cache categories; absent cache fields mean no split. */
    fun normalized(): TokenUsage = TokenUsage(
        inputTokens = inputTokens?.let { input ->
            (input.toLong() + (cacheReadInputTokens ?: 0) + (cacheCreationInputTokens ?: 0))
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        },
        outputTokens = outputTokens,
        cacheReadInputTokens = cacheReadInputTokens,
        cacheCreationInputTokens = cacheCreationInputTokens,
    )
}

@Serializable
data class ContentBlockStartPayload(
    val index: Int = 0,
    @SerialName("content_block") val contentBlock: StreamBlock? = null,
)

@Serializable
data class StreamBlock(
    val type: String = "",
    val id: String? = null,
    val name: String? = null,
    val text: String? = null,
    val thinking: String? = null,
    val signature: String? = null,
)

@Serializable
data class ContentBlockDeltaPayload(
    val index: Int = 0,
    val delta: StreamDelta? = null,
)

@Serializable
data class StreamDelta(
    val type: String = "",
    val text: String? = null,
    val thinking: String? = null,
    val signature: String? = null,
    @SerialName("partial_json") val partialJson: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
)

@Serializable
data class MessageDeltaPayload(
    val delta: StreamDelta? = null,
    val usage: StreamUsage? = null,
)

object ProtocolJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        // 必须 true：stream/type 等字段的值恰好等于默认值，false 会被省掉（曾经导致服务端按非流式返回）
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    fun encodeRequest(request: ApiRequest): String =
        json.encodeToString(ApiRequest.serializer(), request)
}
