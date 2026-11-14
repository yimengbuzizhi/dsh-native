package xin.dshaa.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xin.dshaa.core.protocol.ApiRequest
import xin.dshaa.core.protocol.ProtocolJson
import xin.dshaa.core.protocol.TokenUsage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * M84-J/M84-M：**JS `stream` 回调供应商**（`ctx.llm.register({ id, stream(request, emit) })`）。
 *
 * 与 [ConfiguredLlmProvider] 的分界：
 * - 配置式 = 插件只登记端点/模型/key，请求仍由宿主的内置通道发出去；
 * - 这个 = 模型回合**真的由插件算**：插件在常驻 QuickJS 里拿到完整请求体（Anthropic 形状，
 *   `ProtocolJson.encodeRequest`），边算边 `emit(chunk)` 把增量送回宿主，最终返回
 *   `string` / `{text, toolCalls, stopReason, usage}` / 它们的 Promise。
 *
 * 事件协议（插件 → 宿主，`llm.emit` 同步回灌）：
 * - `'片段'` 或 `{type:'text', text}` → `TextDelta`（首次自动开一个 text 块）；
 * - `{type:'thinking', text}` / `{type:'signature', signature}` → thinking 块；
 * - `{type:'tool_use', id, name, input}` → `BlockStart(tool_use) + ToolInputDelta + BlockStop`（一次给全）；
 * - `{type:'stop', reason}` / `{type:'usage', input, output}` → 记 stop_reason / token 数。
 *
 * 最终结果里的 `toolCalls` 也折成 tool_use 块，因此**插件模型可以调宿主工具**（含插件工具），
 * 回合循环、工具卡、tool_result 回传都走宿主原有链路。
 *
 * 边界（明确不假装）：
 * - 工具参数一次给全（`input` 已经是对象），不是 `input_json_delta` 逐字；
 * - 文本增量是真流式（`emit` 一到就 `TextDelta`），但 JS 与投影/事件共用一条线程；
 * - [call] 是阻塞调用（宿主把它放 IO 线程），超时/异常归一成 `LlmEvent.Failed`，不会卡死回合；
 * - 取消（用户点停止）会让 Flow 在 200ms 内退出，但插件侧 stream 本身要等自己的超时/结算。
 */
class JsCallbackLlmProvider(
    override val id: String,
    override val label: String,
    /** 请求体里没有 model 时的展示用默认值。 */
    private val modelHint: String = "",
    /** 每次 stream 前回调一句人话（宿主写 logcat；core 不依赖 Android）。 */
    private val onStream: (String) -> Unit = {},
    /**
     * `(requestJson, emit) -> 最终结果 JSON`（阻塞，宿主在 IO 线程调）。
     *
     * [emit] 可以在计算过程中从任意线程调用：宿主把它转成增量 [LlmEvent]。返回值是
     * `{ok:true,text,toolCalls?,stopReason?,usage?,model?}` 或 `{ok:false,error}` 的 JSON 文本。
     */
    private val call: (String, (String) -> Unit) -> String,
) : LlmProvider {

    override fun stream(request: ApiRequest, config: LlmConfig): Flow<LlmEvent> = flow {
        val requestJson = ProtocolJson.encodeRequest(request)
        val model = request.model.ifBlank { modelHint.ifBlank { config.model } }
        onStream("plugin stream provider id=$id model=$model requestChars=${requestJson.length}")

        val queue = LinkedBlockingQueue<Item>()
        val worker = Thread({
            try {
                val raw = call(requestJson) { chunk -> queue.put(Item.Chunk(chunk)) }
                queue.put(Item.Result(raw))
            } catch (t: Throwable) {
                queue.put(Item.Failure(t.message ?: t.javaClass.simpleName))
            } finally {
                queue.put(Item.Done)
            }
        }, "dsh-plugin-stream-$id").apply { isDaemon = true }
        worker.start()

        val state = StreamState()
        var resultJson: String? = null
        var callError: String? = null
        var started = false
        // MessageStart 等到第一块（或最终结果）才发：插件在 emit 里先报 usage 时 inputTokens 才对得上。
        suspend fun ensureStarted(producer: suspend (LlmEvent) -> Unit) {
            if (started) return
            started = true
            producer(LlmEvent.MessageStart(id = "$id:plugin", model = model, inputTokens = state.usage.inputTokens ?: 0, usage = state.usage))
        }
        try {
            loop@ while (true) {
                // 阻塞式队列 + 200ms 轮询：用户点停止时 Flow 能及时退出（worker 由它自己的超时兜底）。
                currentCoroutineContext().ensureActive()
                val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                when (item) {
                    is Item.Done -> break@loop
                    is Item.Chunk -> {
                        val events = handleChunk(item.json, state)
                        if (events.isNotEmpty()) ensureStarted { emit(it) }
                        for (event in events) emit(event)
                    }

                    is Item.Result -> resultJson = item.json
                    is Item.Failure -> {
                        callError = item.message
                        break@loop
                    }
                }
            }
            if (callError != null) {
                emit(
                    LlmEvent.Failed(
                        "插件 stream 调用失败：$callError",
                        code = "PLUGIN_STREAM_ERROR",
                    ),
                )
                return@flow
            }
            val raw = resultJson ?: run {
                emit(LlmEvent.Failed("插件供应商没有返回结果", code = "PLUGIN_STREAM_ERROR"))
                return@flow
            }
            val reply = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            if (reply == null) {
                emit(LlmEvent.Failed("插件供应商返回的不是 JSON：${raw.take(300)}", code = "PLUGIN_STREAM_ERROR"))
                return@flow
            }
            if (reply.boolean("ok") != true) {
                emit(
                    LlmEvent.Failed(
                        reply.string("error").orEmpty().ifBlank { "插件供应商没有返回结果" },
                        code = "PLUGIN_STREAM_ERROR",
                    ),
                )
                return@flow
            }
            val finalEvents = applyResult(reply, state)
            ensureStarted { emit(it) }
            for (event in finalEvents) emit(event)
            for (event in closeOpenBlocks(state)) emit(event)
            emit(
                LlmEvent.MessageDelta(
                    stopReason = state.stopReason ?: if (state.toolCalls > 0) "tool_use" else "end_turn",
                    outputTokens = state.usage.outputTokens ?: 0,
                    usage = state.usage,
                ),
            )
            emit(LlmEvent.MessageStop)
        } finally {
            worker.interrupt()
        }
    }.flowOn(Dispatchers.IO)

    // ---- 内部：协议解析 ----

    private sealed interface Item {
        class Chunk(val json: String) : Item
        class Result(val json: String) : Item
        class Failure(val message: String) : Item
        data object Done : Item
    }

    /** 一次 stream 的内容块状态（块序号只在这里分配，保证事件顺序合法）。 */
    private class StreamState {
        var nextIndex = 0
        var openText = -1
        var openThinking = -1
        var streamedText = StringBuilder()
        var toolCalls = 0
        var stopReason: String? = null
        var usage = TokenUsage()
    }

    /** 插件 `emit` 的一块；不认识的类型静默忽略（向前兼容）。 */
    private fun handleChunk(raw: String, state: StreamState): List<LlmEvent> {
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        // `emit('...')` 直接传字符串也算文本增量。
        if (element is JsonPrimitive && element.isString) {
            return textEvents(element.content, state)
        }
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return emptyList()
        return when (obj.string("type")) {
            "text", "delta", "text_delta" -> textEvents(obj.string("text").orEmpty(), state)
            "thinking", "thinking_delta" -> {
                val text = obj.string("text").orEmpty()
                if (text.isEmpty()) {
                    emptyList()
                } else {
                    val events = ArrayList<LlmEvent>(2)
                    if (state.openThinking < 0) {
                        closeText(state, events)
                        state.openThinking = state.nextIndex++
                        events += LlmEvent.BlockStart(state.openThinking, "thinking", null, null)
                    }
                    events += LlmEvent.ThinkingDelta(state.openThinking, text)
                    events
                }
            }

            "signature" -> {
                val signature = obj.string("signature").orEmpty()
                if (state.openThinking < 0 || signature.isEmpty()) {
                    emptyList()
                } else {
                    listOf(LlmEvent.SignatureDelta(state.openThinking, signature))
                }
            }

            "tool_use", "tool_call" -> toolEvents(
                toolId = obj.string("id").orEmpty(),
                name = obj.string("name").orEmpty(),
                input = obj["input"],
                state = state,
            )

            "stop" -> {
                state.stopReason = obj.string("reason") ?: obj.string("stopReason") ?: state.stopReason
                emptyList()
            }

            "usage" -> {
                val update = obj.tokenUsage()
                // Preserve the JS emit protocol's historical output increments; normalize at this boundary.
                val output = update.outputTokens?.let { (state.usage.outputTokens ?: 0) + it }
                state.usage = state.usage.merge(update.copy(outputTokens = output))
                if (update.hasReportedTokens) {
                    listOf(LlmEvent.MessageDelta(null, state.usage.outputTokens ?: 0, state.usage))
                } else emptyList()
            }

            else -> emptyList()
        }
    }

    private fun textEvents(text: String, state: StreamState): List<LlmEvent> {
        if (text.isEmpty()) return emptyList()
        val events = ArrayList<LlmEvent>(2)
        if (state.openThinking >= 0) {
            events += LlmEvent.BlockStop(state.openThinking)
            state.openThinking = -1
        }
        if (state.openText < 0) {
            state.openText = state.nextIndex++
            events += LlmEvent.BlockStart(state.openText, "text", null, null)
        }
        state.streamedText.append(text)
        events += LlmEvent.TextDelta(state.openText, text)
        return events
    }

    private fun toolEvents(toolId: String, name: String, input: JsonElement?, state: StreamState): List<LlmEvent> {
        if (name.isBlank()) return emptyList()
        val events = ArrayList<LlmEvent>(4)
        closeText(state, events)
        val index = state.nextIndex++
        events += LlmEvent.BlockStart(index, "tool_use", toolId.ifBlank { "tool_$index" }, name)
        events += LlmEvent.ToolInputDelta(index, inputJson(input))
        events += LlmEvent.BlockStop(index)
        state.toolCalls++
        return events
    }

    /** 最终结果里的 `text`：没流过增量就整块补、流了前缀就补后缀（避免重复一个字）。 */
    private fun applyResult(reply: JsonObject, state: StreamState): List<LlmEvent> {
        val events = ArrayList<LlmEvent>()
        reply.string("stopReason")?.takeIf { it.isNotBlank() }?.let { state.stopReason = it }
        reply["usage"]?.let { usage ->
            runCatching { usage.jsonObject }.getOrNull()?.tokenUsage()?.let { update ->
                state.usage = state.usage.merge(update)
            }
        }
        val text = reply.string("text").orEmpty()
        if (text.isNotEmpty()) {
            val streamed = state.streamedText.toString()
            when {
                streamed.isEmpty() -> events += textEvents(text, state)
                text.length > streamed.length && text.startsWith(streamed) -> events += textEvents(text.substring(streamed.length), state)
                text != streamed -> state.streamedText = StringBuilder(text)
            }
        }
        val calls = runCatching { reply["toolCalls"]?.jsonArray }.getOrNull().orEmpty()
        for (call in calls) {
            val obj = runCatching { call.jsonObject }.getOrNull() ?: continue
            events += toolEvents(
                toolId = obj.string("id").orEmpty(),
                name = obj.string("name").orEmpty(),
                input = obj["input"],
                state = state,
            )
        }
        return events
    }

    private fun closeText(state: StreamState, events: MutableList<LlmEvent>) {
        if (state.openText >= 0) {
            events += LlmEvent.BlockStop(state.openText)
            state.openText = -1
        }
    }

    private fun closeOpenBlocks(state: StreamState): List<LlmEvent> {
        val events = ArrayList<LlmEvent>(2)
        closeText(state, events)
        if (state.openThinking >= 0) {
            events += LlmEvent.BlockStop(state.openThinking)
            state.openThinking = -1
        }
        return events
    }

    /** 工具参数一次给全（对象原样、字符串当 JSON 原文、缺省 `{}`）。 */
    private fun inputJson(input: JsonElement?): String {
        if (input == null || input is JsonNull) return "{}"
        if (input is JsonPrimitive && input.isString) return input.content.ifBlank { "{}" }
        return input.toString()
    }

    /** JS input/inputTokens is total input, with cache fields treated as subsets like TokenUsage. */
    private fun JsonObject.tokenUsage(): TokenUsage = TokenUsage(
        inputTokens = (int("input") ?: int("inputTokens"))?.takeIf { it >= 0 },
        outputTokens = (int("output") ?: int("outputTokens"))?.takeIf { it >= 0 },
        cacheReadInputTokens = int("cacheReadInputTokens")?.takeIf { it >= 0 },
        cacheCreationInputTokens = int("cacheCreationInputTokens")?.takeIf { it >= 0 },
    )

    private fun JsonObject.string(key: String): String? = runCatching {
        this[key]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun JsonObject.boolean(key: String): Boolean? = runCatching {
        this[key]?.jsonPrimitive?.booleanOrNull
    }.getOrNull()

    private fun JsonObject.int(key: String): Int? = runCatching {
        this[key]?.jsonPrimitive?.intOrNull
    }.getOrNull()
}
