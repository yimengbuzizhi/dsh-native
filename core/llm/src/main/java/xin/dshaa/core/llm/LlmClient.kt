package xin.dshaa.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xin.dshaa.core.protocol.ApiRequest
import xin.dshaa.core.protocol.ProtocolJson
import xin.dshaa.core.protocol.SseReader
import xin.dshaa.core.protocol.StreamErrorBody
import xin.dshaa.core.protocol.StreamParser
import java.util.concurrent.TimeUnit

/**
 * DeepSeek 的 Anthropic Messages 通道。
 * 与上游 `dsh-llm-deepseek` 一致：`{baseURL}/v1/messages` + `x-api-key`。
 */
enum class AuthMode { API_KEY, ACCOUNT }

data class LlmConfig(
    val apiKey: String,
    val authMode: AuthMode = AuthMode.API_KEY,
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = "deepseek-flash",
    val maxTokens: Int = 256_000,
    val temperature: Double? = null,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/anthropic"
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}

/**
 * SSE 原始字节抓包口（门 2 对拍用）。
 *
 * 记录的是**解析器实际读到的字节**（OkHttp 解压后的响应体），每次 `read()` 一个回调，
 * 因此 chunk 边界 = 消费侧边界；回放时按同样的切分逐块写出。
 * 生产路径下 tap 为 null，不产生任何分配。
 */
interface SseTap {
    fun onStart(requestBody: ByteArray) {}
    fun onBytes(buffer: ByteArray, offset: Int, length: Int)
    fun onEnd() {}
}

sealed interface LlmEvent {
    data class MessageStart(val id: String?, val model: String?, val inputTokens: Int) : LlmEvent
    data class BlockStart(val index: Int, val type: String, val toolId: String?, val toolName: String?) : LlmEvent
    data class TextDelta(val index: Int, val text: String) : LlmEvent
    data class ThinkingDelta(val index: Int, val text: String) : LlmEvent
    data class SignatureDelta(val index: Int, val signature: String) : LlmEvent
    data class ToolInputDelta(val index: Int, val partialJson: String) : LlmEvent
    data class BlockStop(val index: Int) : LlmEvent
    data class MessageDelta(val stopReason: String?, val outputTokens: Int) : LlmEvent
    data object MessageStop : LlmEvent
    data class Failed(
        val message: String,
        val code: String? = null,
        val status: Int? = null,
        val retryable: Boolean = false,
        /** `IMAGE_OFFLOAD_REQUIRED` 时还要卸载几张，见 M47 / `ImageOffload`。 */
        val offloadImages: Int? = null,
    ) : LlmEvent
}

class LlmClient(
    private val http: OkHttpClient = defaultClient(),
    /** 每次 `stream()` 调用创建一个 tap；为 null 表示不抓包（默认）。 */
    private val tapFactory: (() -> SseTap?)? = null,
) {

    fun stream(request: ApiRequest, config: LlmConfig): Flow<LlmEvent> = flow {
        if (config.apiKey.isBlank()) {
            emit(LlmEvent.Failed("未配置 API Key", code = "NO_API_KEY"))
            return@flow
        }
        val bodyText = ProtocolJson.encodeRequest(request)
        val body = bodyText.toRequestBody(JSON)
        val httpRequest = Request.Builder()
            .url(messagesUrl(config.baseUrl))
            .post(body)
            .apply { if (config.authMode == AuthMode.ACCOUNT) header("x-dsh-auth-token", config.apiKey) else header("x-api-key", config.apiKey) }
            .header("anthropic-version", LlmConfig.ANTHROPIC_VERSION)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .build()

        val call = http.newCall(httpRequest)
        val tap = tapFactory?.invoke()
        val parent = currentCoroutineContext()
        parent.job.invokeOnCompletion { call.cancel() }

        try {
            tap?.onStart(bodyText.toByteArray(Charsets.UTF_8))
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.string().orEmpty()
                    val err = runCatching {
                        ProtocolJson.json.decodeFromString(StreamErrorBody.serializer(), text).error
                    }.getOrNull()
                    emit(
                        LlmEvent.Failed(
                            message = err?.message ?: "HTTP ${response.code}: ${text.take(300)}",
                            code = err?.type,
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
                val raw = source.inputStream()
                val input = if (tap == null) raw else TappedInputStream(raw, tap)
                val reader = SseReader(input.bufferedReader(Charsets.UTF_8))
                while (true) {
                    parent.ensureActive()
                    val sse = reader.next() ?: break
                    when (sse) {
                        is SseReader.Event.Done -> break
                        is SseReader.Event.Comment -> Unit
                        is SseReader.Event.Data -> {
                            when (val ev = StreamParser.parse(sse.payload)) {
                                is StreamParser.Event.MessageStart ->
                                    emit(LlmEvent.MessageStart(ev.id, ev.model, ev.inputTokens))
                                is StreamParser.Event.BlockStart ->
                                    emit(LlmEvent.BlockStart(ev.index, ev.type, ev.toolId, ev.toolName))
                                is StreamParser.Event.TextDelta -> emit(LlmEvent.TextDelta(ev.index, ev.text))
                                is StreamParser.Event.ThinkingDelta -> emit(LlmEvent.ThinkingDelta(ev.index, ev.text))
                                is StreamParser.Event.SignatureDelta -> emit(LlmEvent.SignatureDelta(ev.index, ev.signature))
                                is StreamParser.Event.ToolInputDelta -> emit(LlmEvent.ToolInputDelta(ev.index, ev.partialJson))
                                is StreamParser.Event.BlockStop -> emit(LlmEvent.BlockStop(ev.index))
                                is StreamParser.Event.MessageDelta ->
                                    emit(LlmEvent.MessageDelta(ev.stopReason, ev.outputTokens))
                                StreamParser.Event.MessageStop -> emit(LlmEvent.MessageStop)
                                StreamParser.Event.Ping -> Unit
                                is StreamParser.Event.Error ->
                                    emit(LlmEvent.Failed(ev.message, code = ev.type, retryable = false))
                                null -> Unit
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (!currentCoroutineContext().isActive) return@flow
            emit(LlmEvent.Failed(e.message ?: e.javaClass.simpleName, retryable = true))
        } finally {
            tap?.onEnd()
        }
    }.flowOn(Dispatchers.IO)

    private fun messagesUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.endsWith("/v1/messages") -> base
            base.endsWith("/v1") -> "$base/messages"
            else -> "$base/v1/messages"
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/** 只旁路记录字节，不影响读语义。 */
private class TappedInputStream(
    private val delegate: java.io.InputStream,
    private val tap: SseTap,
) : java.io.InputStream() {
    override fun read(): Int {
        val b = delegate.read()
        if (b >= 0) tap.onBytes(byteArrayOf(b.toByte()), 0, 1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) tap.onBytes(b, off, n)
        return n
    }

    override fun available(): Int = delegate.available()
    override fun close() = delegate.close()
}
