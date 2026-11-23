package xin.dshaa.nativeapp.node

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.Proxy
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 原生 ↔ Node 宿主的 WebSocket 桥（docs/185 P2：`tool.invoke` 那条链路）。
 *
 * 协议核心在 [NodeBridgeCore]（纯 Kotlin，可 JVM 测）；这里只负责：
 * - OkHttp 连接 **127.0.0.1**，不走系统代理（Android 上代理可能把 localhost 也劫走）；
 * - 带 `?token=` 鉴权（docs/187 §九 第 2 项：启动时 native 生成 32B 随机 token，
 *   经 env `DSH_NODE_TOKEN` 传给宿主；宿主校验失败立即关连接）；
 * - 把 socket 帧/断开转给 core，把 core 的日志/协议错误接到 logcat。
 */
class NodeHostBridge(
    /** 非主动 close 的断开（崩溃/EOF/协议错误）：宿主侧要摘工具表并进重启。 */
    private val onClosed: (String) -> Unit = {},
) {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .proxy(Proxy.NO_PROXY)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var closingByUs = false

    private var onLogSink: (String) -> Unit = {}

    private val core = NodeBridgeCore(
        transport = object : NodeTransport {
            override fun send(text: String): Boolean = socket?.send(text) ?: false

            override fun close(reason: String) {
                runCatching { socket?.close(1000, reason.take(100)) }
            }
        },
        onLog = { line -> onLogSink(line) },
        onProtocolError = { reason -> Log.w(TAG, reason) },
    )

    /** 连上去；hello 由 [hello] 兑现，连不上/握手失败由它异常兑现。 */
    fun connect(port: Int, token: String, onLog: (String) -> Unit = {}) {
        onLogSink = onLog
        closingByUs = false
        val encoded = URLEncoder.encode(token, "UTF-8")
        val request = Request.Builder().url("ws://127.0.0.1:$port/?token=$encoded").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                core.onText(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val reason = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "ws failure: $reason")
                socket = null
                core.onClosed("node 宿主连接断开：$reason")
                if (!closingByUs) onClosed(reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val text = reason.ifBlank { "closed($code)" }
                Log.i(TAG, "ws closed: $text")
                socket = null
                core.onClosed("node 宿主已断开：$text")
                if (!closingByUs) onClosed(text)
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "ws open")
            }
        })
    }

    val hello: CompletableDeferred<NodeHello> get() = core.hello

    val closedReason: String? get() = core.closedReason

    fun tail(max: Int = 20): List<String> = core.tail(max)

    suspend fun invoke(name: String, args: JsonObject, timeoutMs: Long, callId: String): NodeCall =
        core.invoke(name, args, timeoutMs, callId)

    fun nextCallId(): String = core.nextCallId()

    suspend fun manifest(timeoutMs: Long = 10_000): List<JsonObject> = core.manifest(timeoutMs)

    suspend fun prompt(timeoutMs: Long = 8_000): NodePromptPayload? = core.prompt(timeoutMs)

    /** P3-b：插件登记的模型供应商（宿主没回 = 空列表，原生继续用内置通道）。 */
    suspend fun llmList(timeoutMs: Long = 8_000): List<JsonObject> = core.llmList(timeoutMs)

    /** P3-c：插件注册的斜杠命令描述。 */
    suspend fun commandList(timeoutMs: Long = 8_000): List<JsonObject> = core.commandList(timeoutMs)

    /** P3-c：执行一条插件命令（超时/取消都以 [NodeCommandCall.Failure] 回来）。 */
    suspend fun invokeCommand(
        name: String,
        rawInput: String,
        timeoutMs: Long,
        callId: String,
    ): NodeCommandCall = core.commandInvoke(name, rawInput, timeoutMs, callId)

    suspend fun requestStop(waitMs: Long = 1_500): Boolean = core.requestStop(waitMs)

    /** 主动关：先标记，避免 onClosed 把「我们自己收摊」当成崩溃回调。 */
    fun close() {
        closingByUs = true
        runCatching { socket?.close(1000, "client closing") }
        socket = null
        core.onClosed("客户端主动关闭")
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }

    private companion object {
        const val TAG = "DshNodeHost"
    }
}
