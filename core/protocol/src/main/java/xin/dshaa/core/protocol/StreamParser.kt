package xin.dshaa.core.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Anthropic Messages API 的 SSE 事件解析。
 *
 * 事件名在 `event:` 行，数据在 `data:` 行；我们以 data 里的 `type` 为准（更稳）。
 */
object StreamParser {

    sealed interface Event {
        data class MessageStart(val id: String?, val model: String?, val inputTokens: Int) : Event
        data class BlockStart(val index: Int, val type: String, val toolId: String?, val toolName: String?) : Event
        data class TextDelta(val index: Int, val text: String) : Event
        data class ThinkingDelta(val index: Int, val text: String) : Event
        data class SignatureDelta(val index: Int, val signature: String) : Event
        data class ToolInputDelta(val index: Int, val partialJson: String) : Event
        data class BlockStop(val index: Int) : Event
        data class MessageDelta(val stopReason: String?, val outputTokens: Int) : Event
        data object MessageStop : Event
        data object Ping : Event
        data class Error(val type: String?, val message: String) : Event
    }

    fun parse(data: String): Event? {
        val obj = runCatching { ProtocolJson.json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        return when (obj.str("type")) {
            StreamEvents.MESSAGE_START -> {
                val message = obj["message"]?.let { runCatching { it.jsonObject }.getOrNull() }
                val usage = message?.get("usage")?.let { runCatching { it.jsonObject }.getOrNull() }
                Event.MessageStart(
                    id = message?.str("id"),
                    model = message?.str("model"),
                    inputTokens = usage?.int("input_tokens") ?: 0,
                )
            }
            StreamEvents.CONTENT_BLOCK_START -> {
                val block = obj["content_block"]?.let { runCatching { it.jsonObject }.getOrNull() }
                Event.BlockStart(
                    index = obj.int("index") ?: 0,
                    type = block?.str("type").orEmpty(),
                    toolId = block?.str("id"),
                    toolName = block?.str("name"),
                )
            }
            StreamEvents.CONTENT_BLOCK_DELTA -> {
                val delta = obj["delta"]?.let { runCatching { it.jsonObject }.getOrNull() }
                val index = obj.int("index") ?: 0
                when (delta?.str("type")) {
                    "text_delta" -> Event.TextDelta(index, delta.str("text").orEmpty())
                    "thinking_delta" -> Event.ThinkingDelta(index, delta.str("thinking").orEmpty())
                    "signature_delta" -> Event.SignatureDelta(index, delta.str("signature").orEmpty())
                    "input_json_delta" -> Event.ToolInputDelta(index, delta.str("partial_json").orEmpty())
                    else -> null
                }
            }
            StreamEvents.CONTENT_BLOCK_STOP -> Event.BlockStop(obj.int("index") ?: 0)
            StreamEvents.MESSAGE_DELTA -> {
                val delta = obj["delta"]?.let { runCatching { it.jsonObject }.getOrNull() }
                val usage = obj["usage"]?.let { runCatching { it.jsonObject }.getOrNull() }
                Event.MessageDelta(
                    stopReason = delta?.str("stop_reason"),
                    outputTokens = usage?.int("output_tokens") ?: 0,
                )
            }
            StreamEvents.MESSAGE_STOP -> Event.MessageStop
            StreamEvents.PING -> Event.Ping
            StreamEvents.ERROR -> {
                val err = obj["error"]?.let { runCatching { it.jsonObject }.getOrNull() }
                Event.Error(err?.str("type"), err?.str("message") ?: "unknown error")
            }
            else -> null
        }
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.int(key: String): Int? =
        runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()
}
