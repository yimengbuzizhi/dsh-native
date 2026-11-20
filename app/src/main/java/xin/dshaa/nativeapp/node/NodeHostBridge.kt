package xin.dshaa.nativeapp.node

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 原生 ↔ Node 宿主的 WebSocket 桥（docs/185 P2：`tool.invoke` 那条链路）。
 *
 * 协议（`D:\dsh\node-runtime\dsh-min\host.mjs` 定义，改动要两边同时改）：
 * - 连上就收 `host.hello`：`{pid, version, bootMs, tools, plugins, logs}`；
 * - `tool.invoke {id, name, args, timeoutMs}` → `tool.result {id, ok, value?, text?, error?, ms}`；
 * - `host.manifest {id}` → `host.manifest {id, tools}`；
 * - `host.log {level, text}`（宿主里的 console 输出，失败时给人看）；
 * - `host.stop` → `host.ack` + `host.bye` → 进程 exit(0)。
 *
 * 只在 127.0.0.1 上通话，不走系统代理（Android 上代理可能把 localhost 也劫走）。
 */
class NodeHostBridge {

    /** 一次工具调用的结果。失败也带 ms，方便审计里看到"卡了多久"。 */
    sealed interface Call {
        data class Ok(val value: JsonElement?, val text: String?, val ms: Long) : Call
        data class Failure(val error: String, val ms: Long) : Call
    }

    data class Hello(
        val pid: Int,
        val version: String,
        val bootMs: Long,
        val tools: List<JsonObject>,
        val plugins: List<JsonObject>,
    )

    /**
     * 插件往系统提示里注册的段/上下文（`ctx.systemPrompt.section/context`）。
     * [source] 是宿主实际走的路径：`assemble`（内核组装）或 `registry`（只读登记表兜底）。
     */
    data class PromptPayload(
        val source: String,
        val sections: List<JsonObject>,
        val contexts: List<JsonObject>,
        val variables: Map<String, String>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .proxy(Proxy.NO_PROXY)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Call>>()
    private val toolReplies = ConcurrentHashMap<String, CompletableDeferred<List<JsonObject>>>()
    private val promptReplies = ConcurrentHashMap<String, CompletableDeferred<PromptPayload>>()
    private val ackWaiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val seq = AtomicLong(0)

    val hello = CompletableDeferred<Hello>()

    @Volatile
    var closedReason: String? = null
        private set

    private val logLines = ArrayDeque<String>()

    fun tail(max: Int = 20): List<String> = synchronized(logLines) { logLines.toList().takeLast(max) }

    /** 连上去；hello 由 [hello] 兑现，连不上/握手失败由它异常兑现。 */
    fun connect(port: Int, onLog: (String) -> Unit = {}) {
        val request = Request.Builder().url("ws://127.0.0.1:$port/").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { handleFrame(text) }.onFailure {
                    Log.w(TAG, "bad frame: ${text.take(300)}", it)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closedReason = t.message ?: t.javaClass.simpleName
                if (!hello.isCompleted) hello.completeExceptionally(t)
                failAll("node 宿主连接断开：${closedReason}")
                Log.w(TAG, "ws failure: ${closedReason}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closedReason = reason.ifBlank { "closed($code)" }
                if (!hello.isCompleted) hello.completeExceptionally(IllegalStateException(closedReason))
                failAll("node 宿主已断开：$closedReason")
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "ws open")
            }
        })
        this.onLogSink = onLog
    }

    private var onLogSink: (String) -> Unit = {}

    private fun handleFrame(text: String) {
        val frame = json.parseToJsonElement(text) as? JsonObject ?: return
        when (frame.primitive("op")) {
            "host.hello" -> {
                val info = Hello(
                    pid = frame.primitive("pid")?.toIntOrNull() ?: -1,
                    version = frame.primitive("version") ?: "?",
                    bootMs = frame.primitive("bootMs")?.toLongOrNull() ?: -1,
                    tools = frame.arrayOfObjects("tools"),
                    plugins = frame.arrayOfObjects("plugins"),
                )
                frame.arrayOfStrings("logs").forEach { appendLog(it) }
                if (!hello.isCompleted) hello.complete(info)
            }

            "tool.result" -> {
                val id = frame.primitive("id") ?: return
                val ok = frame.primitive("ok") == "true"
                val ms = frame.primitive("ms")?.toLongOrNull() ?: 0
                val result = if (ok) {
                    Call.Ok(frame["value"], frame.primitive("text"), ms)
                } else {
                    Call.Failure(frame.primitive("error") ?: "未知错误", ms)
                }
                pending.remove(id)?.complete(result)
            }

            "host.manifest" -> {
                val id = frame.primitive("id") ?: return
                toolReplies.remove(id)?.complete(frame.arrayOfObjects("tools"))
            }

            "prompt.result" -> {
                val id = frame.primitive("id") ?: return
                val waiter = promptReplies.remove(id) ?: return
                if (frame.primitive("ok") != "true") {
                    waiter.completeExceptionally(
                        IllegalStateException(frame.primitive("error") ?: "prompt.assemble 失败"),
                    )
                    return
                }
                val variables = (frame["variables"] as? JsonObject)
                    ?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.content?.let { key to it } }
                    ?.toMap()
                    .orEmpty()
                waiter.complete(
                    PromptPayload(
                        source = frame.primitive("source") ?: "registry",
                        sections = frame.arrayOfObjects("sections"),
                        contexts = frame.arrayOfObjects("contexts"),
                        variables = variables,
                    ),
                )
            }

            "host.log" -> {                val logText = frame.primitive("text") ?: return
                appendLog(logText)
                onLogSink(logText)
            }

            "host.ack" -> {
                frame.primitive("id")?.let { id -> ackWaiters.remove(id)?.complete(Unit) }
            }

            "host.bye" -> {
                if (!hello.isCompleted) hello.completeExceptionally(IllegalStateException("宿主 bye"))
            }
        }
    }

    private fun appendLog(line: String) {
        synchronized(logLines) {
            logLines.addLast(if (line.length > 1_000) line.take(1_000) + "…" else line)
            while (logLines.size > 100) logLines.removeFirst()
        }
    }

    private fun failAll(reason: String) {
        pending.values.forEach { it.complete(Call.Failure(reason, 0)) }
        pending.clear()
        toolReplies.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        toolReplies.clear()
        promptReplies.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        promptReplies.clear()
        ackWaiters.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        ackWaiters.clear()
    }

    suspend fun invoke(name: String, args: JsonObject, timeoutMs: Long): Call {
        val id = "t${seq.incrementAndGet()}"
        val deferred = CompletableDeferred<Call>()
        pending[id] = deferred
        val payload = buildJsonObject {
            put("op", "tool.invoke")
            put("id", id)
            put("name", name)
            put("args", args)
            put("timeoutMs", timeoutMs)
        }
        val ws = socket
        if (ws == null || !ws.send(payload.toString())) {
            pending.remove(id)
            return Call.Failure("node 宿主未连接（${closedReason ?: "socket=null"}）", 0)
        }
        // 原生侧比 Node 侧多等 2s：Node 的超时文案先到，否则说明宿主整体卡死，这里兜底。
        return withTimeoutOrNull(timeoutMs + 2_000) { deferred.await() }
            ?: run {
                pending.remove(id)
                Call.Failure("node 宿主 ${timeoutMs + 2_000}ms 内无响应（可能已卡死）", timeoutMs + 2_000)
            }
    }

    suspend fun manifest(timeoutMs: Long = 10_000): List<JsonObject> {
        val id = "m${seq.incrementAndGet()}"
        val deferred = CompletableDeferred<List<JsonObject>>()
        toolReplies[id] = deferred
        val payload = buildJsonObject {
            put("op", "host.manifest")
            put("id", id)
        }
        val ws = socket
        if (ws == null || !ws.send(payload.toString())) {
            toolReplies.remove(id)
            error("node 宿主未连接")
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: run {
                toolReplies.remove(id)
                error("manifest 超时")
            }
    }

    /** 拉一次插件注册的提示词段。失败返回 null（提示词拼装不能因为宿主卡住而失败）。 */
    suspend fun prompt(timeoutMs: Long = 8_000): PromptPayload? {
        val id = "p${seq.incrementAndGet()}"
        val deferred = CompletableDeferred<PromptPayload>()
        promptReplies[id] = deferred
        val payload = buildJsonObject {
            put("op", "prompt.assemble")
            put("id", id)
        }
        val ws = socket
        if (ws == null || !ws.send(payload.toString())) {
            promptReplies.remove(id)
            return null
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: run {
                promptReplies.remove(id)
                Log.w(TAG, "prompt.assemble 超时（${timeoutMs}ms）")
                null
            }
    }

    /** 发 host.stop 并等 ack（等不到也不算错：进程快死时 ack 可能来不及发）。 */
    suspend fun requestStop(waitMs: Long = 1_500): Boolean {
        val id = "s${seq.incrementAndGet()}"
        val deferred = CompletableDeferred<Unit>()
        ackWaiters[id] = deferred
        val payload = buildJsonObject {
            put("op", "host.stop")
            put("id", id)
        }
        val ws = socket ?: return false
        if (!ws.send(payload.toString())) return false
        val acked = withTimeoutOrNull(waitMs) { deferred.await() } != null
        ackWaiters.remove(id)
        return acked
    }

    fun close() {
        runCatching { socket?.close(1000, "client closing") }
        socket = null
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }

    private fun JsonObject.primitive(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.arrayOfObjects(key: String): List<JsonObject> =
        (this[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonObject.arrayOfStrings(key: String): List<String> =
        (this[key] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty()

    private companion object {
        const val TAG = "DshNodeHost"
    }
}
