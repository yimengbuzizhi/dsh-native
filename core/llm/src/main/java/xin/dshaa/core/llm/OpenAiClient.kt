package xin.dshaa.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xin.dshaa.core.protocol.ApiMessage
import xin.dshaa.core.protocol.ApiRequest
import xin.dshaa.core.protocol.ContentBlock
import xin.dshaa.core.protocol.ProtocolJson
import xin.dshaa.core.protocol.SseReader
import java.util.concurrent.TimeUnit

/**
 * OpenAI Chat Completions 通道（`{base}/v1/chat/completions`）。
 *
 * 用户要求：支持 OpenAI 格式的全部供应商（OpenAI 官方、DeepSeek、Ollama、vLLM、
 * 硅基流动、OneAPI 之类只要兼容 `/v1/chat/completions` 就行）。内部仍用
 * [ApiRequest] / [LlmEvent] 这套 Anthropic 形状，本类负责两边的翻译：
 *
 * - 请求：system → system 消息；text/image → content；tool_use → assistant.tool_calls；
 *   tool_result → role=tool；tools → function 定义；
 * - 流：`choices[0].delta.content` / `reasoning_content` / `tool_calls[].function.arguments`
 *   → [LlmEvent.TextDelta] / [LlmEvent.ThinkingDelta] / [LlmEvent.ToolInputDelta]；
 *   `finish_reason` 映射成 Anthropic 的 stop_reason（tool_calls → tool_use，length → max_tokens）。
 */
class OpenAiClient(
    private val http: OkHttpClient = defaultClient(),
) {

    fun stream(request: ApiRequest, config: LlmConfig): Flow<LlmEvent> = flow {
        if (config.apiKey.isBlank()) {
            emit(LlmEvent.Failed("未配置 API Key", code = "NO_API_KEY"))
            return@flow
        }
        val bodyText = encodeRequest(request, config).toString()
        val body = bodyText.toRequestBody(JSON)
        val httpRequest = Request.Builder()
            .url(chatUrl(config.baseUrl))
            .post(body)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .build()

        val call = http.newCall(httpRequest)
        val parent = currentCoroutineContext()
        parent.job.invokeOnCompletion { call.cancel() }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty()
                    val err = runCatching {
                        ProtocolJson.json.parseToJsonElement(text).jsonObject["error"]?.jsonObject
                    }.getOrNull()
                    emit(
                        LlmEvent.Failed(
                            message = err?.get("message")?.jsonPrimitive?.contentOrNull
                                ?: "HTTP ${response.code}: ${text.take(300)}",
                            code = err?.get("code")?.jsonPrimitive?.contentOrNull
                                ?: err?.get("type")?.jsonPrimitive?.contentOrNull,
                            status = response.code,
                            retryable = response.code == 429 || response.code >= 500,
                        ),
                    )
                    return@use
                }
                val source = response.body?.source() ?: run {
                    emit(LlmEvent.Failed("响应体为空", status = response.code))
                    return@use
                }
                val reader = SseReader(source.inputStream().bufferedReader(Charsets.UTF_8))

                var started = false
                var nextBlock = 0
                var textBlock = -1
                var thinkingBlock = -1
                val toolBlocks = LinkedHashMap<Int, Int>()
                var outputTokens = 0
                var promptTokens = 0
                var reportedInputTokens = 0
                var stopReason: String? = null
                var finished = false

                while (!finished) {
                    parent.ensureActive()
                    val sse = reader.next() ?: break
                    when (sse) {
                        is SseReader.Event.Done -> break
                        is SseReader.Event.Comment -> Unit
                        is SseReader.Event.Data -> {
                            val obj = runCatching {
                                ProtocolJson.json.parseToJsonElement(sse.payload).jsonObject
                            }.getOrNull() ?: continue
                            if (!started) {
                                started = true
                                val usage = obj["usage"]?.let { runCatching { it.jsonObject }.getOrNull() }
                                reportedInputTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0
                                emit(
                                    LlmEvent.MessageStart(
                                        id = obj["id"]?.jsonPrimitive?.contentOrNull,
                                        model = obj["model"]?.jsonPrimitive?.contentOrNull,
                                        inputTokens = reportedInputTokens,
                                    ),
                                )
                            }
                            // 有些兼容实现会在最后一个 chunk 只带 usage、choices 为空；
                            // 流式下 prompt_tokens 往往也只在这里出现（M72：之前只读第一个 chunk，
                            // 结果 inputTokens 恒 0，鲸鱼娘挂件的「投喂 token」少算一大截）。
                            obj["usage"]?.let { usage ->
                                runCatching { usage.jsonObject }.getOrNull()?.let { usageObject ->
                                    usageObject["prompt_tokens"]?.jsonPrimitive?.intOrNull
                                        ?.let { if (it > 0) promptTokens = it }
                                    usageObject["completion_tokens"]?.jsonPrimitive?.intOrNull
                                        ?.let { if (it > 0) outputTokens = it }
                                }
                            }
                            val choices = runCatching { obj["choices"]?.jsonArray }.getOrNull()
                            val choice = choices?.firstOrNull()?.let { runCatching { it.jsonObject }.getOrNull() }
                                ?: continue
                            val delta = choice["delta"]?.let { runCatching { it.jsonObject }.getOrNull() }

                            // 1) 正文
                            val text = delta?.get("content")?.let { element ->
                                runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
                            }
                            if (!text.isNullOrEmpty()) {
                                if (textBlock < 0) {
                                    textBlock = nextBlock++
                                    emit(LlmEvent.BlockStart(textBlock, "text", null, null))
                                }
                                emit(LlmEvent.TextDelta(textBlock, text))
                            }

                            // 2) 思考（DeepSeek reasoner / vLLM reasoning_content）
                            val reasoning = delta?.get("reasoning_content")?.let { element ->
                                runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
                            } ?: delta?.get("reasoning")?.let { element ->
                                runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
                            }
                            if (!reasoning.isNullOrEmpty()) {
                                if (thinkingBlock < 0) {
                                    thinkingBlock = nextBlock++
                                    emit(LlmEvent.BlockStart(thinkingBlock, "thinking", null, null))
                                }
                                emit(LlmEvent.ThinkingDelta(thinkingBlock, reasoning))
                            }

                            // 3) 工具调用（增量参数按 index 累积）
                            val toolCalls = runCatching { delta?.get("tool_calls")?.jsonArray }.getOrNull()
                            if (toolCalls != null) {
                                toolCalls.forEachIndexed { fallbackIndex, element ->
                                    val tool = runCatching { element.jsonObject }.getOrNull() ?: return@forEachIndexed
                                    val callIndex = tool["index"]?.jsonPrimitive?.intOrNull ?: fallbackIndex
                                    val function = tool["function"]?.let { runCatching { it.jsonObject }.getOrNull() }
                                    if (callIndex !in toolBlocks) {
                                        val blockIndex = nextBlock++
                                        toolBlocks[callIndex] = blockIndex
                                        emit(
                                            LlmEvent.BlockStart(
                                                blockIndex,
                                                "tool_use",
                                                tool["id"]?.jsonPrimitive?.contentOrNull,
                                                function?.get("name")?.jsonPrimitive?.contentOrNull,
                                            ),
                                        )
                                    }
                                    val args = function?.get("arguments")?.let { argument ->
                                        runCatching { argument.jsonPrimitive.contentOrNull }.getOrNull()
                                    }
                                    if (!args.isNullOrEmpty()) {
                                        emit(LlmEvent.ToolInputDelta(toolBlocks.getValue(callIndex), args))
                                    }
                                }
                            }

                            val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                            if (!finish.isNullOrBlank()) {
                                stopReason = mapFinishReason(finish)
                                emit(LlmEvent.MessageDelta(stopReason, outputTokens))
                                finished = true
                            }
                        }
                    }
                }
                if (!started) {
                    emit(LlmEvent.Failed("响应里没有收到任何 SSE 数据", status = response.code))
                } else {
                    // 流式用量常常最后才补齐：补一条 MessageStart 纠正 inputTokens
                    // （引擎对 MessageStart 是赋值语义，重复发只是更新计数）。
                    if (promptTokens > reportedInputTokens) {
                        emit(LlmEvent.MessageStart(id = null, model = null, inputTokens = promptTokens))
                    }
                    emit(LlmEvent.MessageStop)
                }
            }
        } catch (e: Exception) {
            if (!currentCoroutineContext().isActive) return@flow
            emit(LlmEvent.Failed(e.message ?: e.javaClass.simpleName, retryable = true))
        }
    }.flowOn(Dispatchers.IO)

    // ---------------- 请求翻译 ----------------

    private fun encodeRequest(request: ApiRequest, config: LlmConfig): JsonObject {
        val messages = buildJsonArray {
            request.system?.takeIf { it.isNotBlank() }?.let { system ->
                add(buildJsonObject {
                    put("role", "system")
                    put("content", system)
                })
            }
            request.messages.forEach { message ->
                convertMessage(message).forEach { converted -> add(converted) }
            }
        }
        return buildJsonObject {
            put("model", config.model)
            put("stream", true)
            put("messages", messages)
            put("max_tokens", config.maxTokens)
            config.temperature?.let { put("temperature", it) }
            request.stopSequences?.takeIf { it.isNotEmpty() }?.let { stops ->
                put("stop", JsonArray(stops.map { JsonPrimitive(it) }))
            }
            request.tools?.takeIf { it.isNotEmpty() }?.let { tools ->
                put("tools", buildJsonArray {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                tool.description?.let { put("description", it) }
                                put("parameters", tool.inputSchema)
                            })
                        })
                    }
                })
            }
        }
    }

    /** 一条 Anthropic 消息可能拆成多条 OpenAI 消息（tool_result → role=tool）。 */
    private fun convertMessage(message: ApiMessage): List<JsonObject> {
        return when (message.role) {
            "assistant" -> listOf(convertAssistant(message))
            "user" -> convertUser(message)
            else -> convertUser(message)
        }
    }

    private fun convertAssistant(message: ApiMessage): JsonObject {
        val text = message.content.filterIsInstance<ContentBlock.Text>()
            .joinToString("") { it.text }
        val tools = message.content.filterIsInstance<ContentBlock.ToolUse>()
        return buildJsonObject {
            put("role", "assistant")
            if (text.isNotEmpty()) put("content", text) else put("content", JsonNull)
            if (tools.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    tools.forEach { call ->
                        add(buildJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", call.name)
                                put("arguments", call.input.toString())
                            })
                        })
                    }
                })
            }
        }
    }

    private fun convertUser(message: ApiMessage): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        val toolResults = message.content.filterIsInstance<ContentBlock.ToolResult>()
        toolResults.forEach { result ->
            out += buildJsonObject {
                put("role", "tool")
                put("tool_call_id", result.toolUseId)
                put("content", flatten(result.content))
            }
        }
        val rest = message.content.filterNot { it is ContentBlock.ToolResult }
        if (rest.isNotEmpty()) {
            val images = rest.filterIsInstance<ContentBlock.Image>()
            if (images.isEmpty()) {
                val text = rest.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }
                if (text.isNotEmpty()) {
                    out += buildJsonObject {
                        put("role", "user")
                        put("content", text)
                    }
                }
            } else {
                out += buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        rest.forEach { block ->
                            when (block) {
                                is ContentBlock.Text -> add(buildJsonObject {
                                    put("type", "text")
                                    put("text", block.text)
                                })
                                is ContentBlock.Image -> add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject {
                                        put("url", "data:${block.source.mediaType};base64,${block.source.data}")
                                    })
                                })
                                else -> Unit
                            }
                        }
                    })
                }
            }
        }
        return out
    }

    private fun flatten(content: List<ContentBlock>?): String {
        if (content == null) return ""
        return content.joinToString("\n") { block ->
            when (block) {
                is ContentBlock.Text -> block.text
                is ContentBlock.Image -> "[image]"
                is ContentBlock.ToolUse -> "[tool_use ${block.name}]"
                is ContentBlock.ToolResult -> flatten(block.content)
                is ContentBlock.Thinking -> ""
            }
        }.trim()
    }

    private fun mapFinishReason(reason: String): String = when (reason) {
        "length" -> "max_tokens"
        "tool_calls", "function_call" -> "tool_use"
        "content_filter" -> "stop_sequence"
        else -> "end_turn"
    }

    private fun chatUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // M79（用户 2026-11-12「发消息长时间没反应」）：以前是 0=永不超时，
            // 服务端假死时这一轮会永远挂着；3 分钟内一个字节都没来就按网络错误收口。
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
