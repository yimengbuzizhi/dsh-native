package xin.dshaa.nativeapp.node

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 桥的传输层。生产实现是 OkHttp WebSocket，JVM 用例用假 transport：
 * 这条接口就是为了让「超时 / 崩溃 EOF / 非法帧 / 协程取消」四种异常能在纯 JVM 上验。
 */
interface NodeTransport {
    fun send(text: String): Boolean
    fun close(reason: String)
}

/** 一次工具调用的结果。失败也带 ms，方便审计里看到"卡了多久"。 */
sealed interface NodeCall {
    /** 需要回写审计的 callId：宿主侧同一 id，取消也按它找。 */
    val id: String

    data class Ok(
        override val id: String,
        val value: JsonElement?,
        val text: String?,
        val ms: Long,
    ) : NodeCall

    data class Failure(
        override val id: String,
        val error: String,
        val ms: Long,
    ) : NodeCall
}

data class NodeHello(
    val pid: Int,
    val version: String,
    val bootMs: Long,
    val tools: List<JsonObject>,
    val plugins: List<JsonObject>,
    /** P3-b：插件登记的模型供应商（`ctx.llm.register` 的声明）。 */
    val llm: List<JsonObject> = emptyList(),
    /** P3-c：插件注册的斜杠命令描述（无 handler）。 */
    val commands: List<JsonObject> = emptyList(),
)

/**
 * 插件往系统提示里注册的段/上下文（`ctx.systemPrompt.section/context`）。
 * [source] 是宿主实际走的路径：`assemble`（内核组装）或 `registry`（只读登记表兜底）。
 */
data class NodePromptPayload(
    val source: String,
    val sections: List<JsonObject>,
    val contexts: List<JsonObject>,
    val variables: Map<String, String>,
)

/**
 * 原生 ↔ Node 宿主的**协议核心**（docs/185 P2，docs/187 §九 第 4/9 项）。
 *
 * 这里不碰 OkHttp/Android，只做帧解析、pending 表、超时与取消、日志环。这样：
 * - 生产：[NodeHostBridge] 把 OkHttp 的收发接到 [onText]/[onClosed]；
 * - JVM：`FakeNodeTransport` 直接喂帧，四种异常全部可断言。
 *
 * 协议（`assets/node/runtime.zip` 里的 `host.mjs` 定义，改动要两边同时改）：
 * - 连上就收 `host.hello`：`{pid, version, bootMs, tools, plugins, logs}`；
 * - `tool.invoke {id, name, args, timeoutMs}` → `tool.result {id, ok, value?, text?, error?, ms}`；
 * - `tool.cancel {id}` → `tool.cancelled {id, ms}`（协作取消；宿主 `controller.abort()`）；
 * - `host.manifest {id}` → `host.manifest {id, tools}`；
 * - `prompt.assemble {id}` → `prompt.result {id, ok, source, sections, contexts, variables}`；
 * - P3-b：`llm.list {id}` → `llm.result {id, ok, providers:[…]}`；
 * - P3-c：`command.list {id}` → `command.list.result {id, ok, commands:[…]}`；
 *   `command.invoke {id, name, rawInput, timeoutMs}` → `command.invoke.result {id, ok, kind, text, commandId?, ms}`；
 *   `command.cancel {id}` → `command.cancelled {id, active}`；
 * - `host.log {level, text}`（宿主里的 console 输出，失败时给人看）；
 * - `host.stop` → `host.ack` + `host.bye` → 进程 exit(0)。
 */
class NodeBridgeCore(
    private val transport: NodeTransport,
    var onLog: (String) -> Unit = {},
    var onProtocolError: (String) -> Unit = {},
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val pending = ConcurrentHashMap<String, CompletableDeferred<NodeCall>>()
    private val toolReplies = ConcurrentHashMap<String, CompletableDeferred<List<JsonObject>>>()
    private val promptReplies = ConcurrentHashMap<String, CompletableDeferred<NodePromptPayload>>()
    private val llmReplies = ConcurrentHashMap<String, CompletableDeferred<List<JsonObject>>>()
    private val commandListReplies = ConcurrentHashMap<String, CompletableDeferred<List<JsonObject>>>()
    private val commandReplies = ConcurrentHashMap<String, CompletableDeferred<NodeCommandCall>>()
    private val ackWaiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val seq = AtomicLong(0)

    val hello = CompletableDeferred<NodeHello>()

    @Volatile
    var closedReason: String? = null
        private set

    private val logLines = ArrayDeque<String>()

    fun tail(max: Int = 20): List<String> = synchronized(logLines) { logLines.toList().takeLast(max) }

    /** 新的 callId（调用方要在发帧前先拿到它去写审计的 start 行）。 */
    fun nextCallId(): String = "t${seq.incrementAndGet()}"

    /** 还等着宿主回 frame 的调用数（取消/超时后必须归零，JVM 用例直接断言）。 */
    fun pendingCount(): Int = pending.size

    /** 收一帧文本。非法帧按协议错误处理：断开 + 让上层摘工具（fail closed）。 */
    fun onText(text: String) {
        val frame = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        if (frame == null) {
            failProtocol("非法帧（不是 JSON object）：${text.take(200)}")
            return
        }
        when (val op = frame.primitive("op")) {
            "host.hello" -> {
                val info = NodeHello(
                    pid = frame.primitive("pid")?.toIntOrNull() ?: -1,
                    version = frame.primitive("version") ?: "?",
                    bootMs = frame.primitive("bootMs")?.toLongOrNull() ?: -1,
                    tools = frame.arrayOfObjects("tools"),
                    plugins = frame.arrayOfObjects("plugins"),
                    llm = frame.arrayOfObjects("llm"),
                    commands = frame.arrayOfObjects("commands"),
                )
                frame.arrayOfStrings("logs").forEach { appendLog(it) }
                if (!hello.isCompleted) hello.complete(info)
            }

            "tool.result" -> {
                val id = frame.primitive("id") ?: return
                val ok = frame.primitive("ok") == "true"
                val ms = frame.primitive("ms")?.toLongOrNull() ?: 0
                val result = if (ok) {
                    NodeCall.Ok(id, frame["value"], frame.primitive("text"), ms)
                } else {
                    NodeCall.Failure(id, frame.primitive("error") ?: "未知错误", ms)
                }
                pending.remove(id)?.complete(result)
            }

            // 宿主对取消的显式回执（取消和正常结果可能竞争，先到的算）。
            "tool.cancelled" -> {
                val id = frame.primitive("id") ?: return
                val ms = frame.primitive("ms")?.toLongOrNull() ?: 0
                pending.remove(id)?.complete(NodeCall.Failure(id, "已取消（tool.cancelled）", ms))
            }

            "host.manifest" -> {
                val id = frame.primitive("id") ?: return
                toolReplies.remove(id)?.complete(frame.arrayOfObjects("tools"))
            }

            // P3-b：模型供应商清单（hello 之外随时可重拉）。
            "llm.result" -> {
                val id = frame.primitive("id") ?: return
                val waiter = llmReplies.remove(id) ?: return
                if (frame.primitive("ok") != "true") {
                    waiter.completeExceptionally(
                        IllegalStateException(frame.primitive("error") ?: "llm.list 失败"),
                    )
                    return
                }
                waiter.complete(frame.arrayOfObjects("providers"))
            }

            // P3-c：斜杠命令清单 / 执行结果 / 取消回执。
            "command.list.result" -> {
                val id = frame.primitive("id") ?: return
                val waiter = commandListReplies.remove(id) ?: return
                if (frame.primitive("ok") != "true") {
                    waiter.completeExceptionally(
                        IllegalStateException(frame.primitive("error") ?: "command.list 失败"),
                    )
                    return
                }
                waiter.complete(frame.arrayOfObjects("commands"))
            }

            "command.invoke.result" -> {
                val id = frame.primitive("id") ?: return
                val waiter = commandReplies.remove(id) ?: return
                val ms = frame.primitive("ms")?.toLongOrNull() ?: 0
                val ok = frame.primitive("ok") == "true"
                val text = frame.primitive("text").orEmpty()
                waiter.complete(
                    if (ok) {
                        NodeCommandCall.Ok(text, frame.primitive("commandId").orEmpty(), ms)
                    } else {
                        NodeCommandCall.Failure(
                            error = frame.primitive("error") ?: text.ifBlank { "命令执行失败" },
                            kind = frame.primitive("kind") ?: "error",
                            ms = ms,
                        )
                    },
                )
            }

            "command.cancelled" -> {
                val id = frame.primitive("id") ?: return
                commandReplies.remove(id)?.complete(
                    NodeCommandCall.Failure("已取消（命令）", "cancelled", 0),
                )
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
                    NodePromptPayload(
                        source = frame.primitive("source") ?: "registry",
                        sections = frame.arrayOfObjects("sections"),
                        contexts = frame.arrayOfObjects("contexts"),
                        variables = variables,
                    ),
                )
            }

            "host.log" -> {
                val logText = frame.primitive("text") ?: return
                appendLog(logText)
                onLog(logText)
            }

            "host.ack" -> {
                frame.primitive("id")?.let { id -> ackWaiters.remove(id)?.complete(Unit) }
            }

            "host.bye" -> {
                if (!hello.isCompleted) hello.completeExceptionally(IllegalStateException("宿主 bye"))
            }

            null -> failProtocol("非法帧（缺 op）：${text.take(200)}")
            // 未知 op 只记账、不断链：给宿主/插件留前向兼容空间。
            else -> onProtocolError("未知帧 op=$op")
        }
    }

    /** 连接断开/进程 EOF：兑现所有等待者，工具表由上层摘除。 */
    fun onClosed(reason: String) {
        closedReason = reason
        if (!hello.isCompleted) hello.completeExceptionally(IllegalStateException(reason))
        failAll(reason)
    }

    private fun failProtocol(reason: String) {
        onProtocolError(reason)
        transport.close(reason)
        onClosed(reason)
    }

    private fun appendLog(line: String) {
        synchronized(logLines) {
            logLines.addLast(if (line.length > 1_000) line.take(1_000) + "…" else line)
            while (logLines.size > 100) logLines.removeFirst()
        }
    }

    private fun failAll(reason: String) {
        pending.values.forEach { it.complete(NodeCall.Failure("?", reason, 0)) }
        pending.clear()
        toolReplies.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        toolReplies.clear()
        promptReplies.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        promptReplies.clear()
        llmReplies.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        llmReplies.clear()
        commandListReplies.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        commandListReplies.clear()
        commandReplies.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        commandReplies.clear()
        ackWaiters.values.forEach { it.completeExceptionally(IllegalStateException(reason)) }
        ackWaiters.clear()
    }

    suspend fun invoke(name: String, args: JsonObject, timeoutMs: Long, callId: String = nextCallId()): NodeCall {
        val id = callId
        val deferred = CompletableDeferred<NodeCall>()
        pending[id] = deferred
        val payload = buildJsonObject {
            put("op", "tool.invoke")
            put("id", id)
            put("name", name)
            put("args", args)
            put("timeoutMs", timeoutMs)
        }
        if (!transport.send(payload.toString())) {
            pending.remove(id)
            return NodeCall.Failure(id, "node 宿主未连接（${closedReason ?: "socket=null"}）", 0)
        }
        try {
            // 原生侧比 Node 侧多等 2s：Node 的超时文案先到，否则说明宿主整体卡死，这里兜底。
            return withTimeoutOrNull(timeoutMs + 2_000) { deferred.await() }
                ?: run {
                    pending.remove(id)
                    // 原生已经不等了，让宿主也别继续跑（协作取消；不理会也不影响原生侧）。
                    sendCancel(id)
                    NodeCall.Failure(id, "node 宿主 ${timeoutMs + 2_000}ms 内无响应（可能已卡死）", timeoutMs + 2_000)
                }
        } catch (cancelled: CancellationException) {
            // 协程取消（用户停止/切会话）：发 tool.cancel、移 pending，再把取消往上抛。
            pending.remove(id)
            sendCancel(id)
            throw cancelled
        }
    }

    private fun sendCancel(id: String) {
        val frame = buildJsonObject {
            put("op", "tool.cancel")
            put("id", id)
        }
        runCatching { transport.send(frame.toString()) }
    }

    suspend fun manifest(timeoutMs: Long = 10_000): List<JsonObject> {
        val id = "m${seq.incrementAndGet()}"
        val deferred = CompletableDeferred<List<JsonObject>>()
        toolReplies[id] = deferred
        val payload = buildJsonObject {
            put("op", "host.manifest")
            put("id", id)
        }
        if (!transport.send(payload.toString())) {
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
    suspend fun prompt(timeoutMs: Long = 8_000): NodePromptPayload? {
        val id = "p${seq.incrementAndGet()}"
        val deferred = CompletableDeferred<NodePromptPayload>()
        promptReplies[id] = deferred
        val payload = buildJsonObject {
            put("op", "prompt.assemble")
            put("id", id)
        }
        if (!transport.send(payload.toString())) {
            promptReplies.remove(id)
            return null
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: run {
                promptReplies.remove(id)
                null
            }
    }

    /**
     * P3-b：拉一次插件登记的模型供应商。宿主没回/没连上都返回空列表 ——
     * 模型列表不能因为 Node 卡住而整个不可用（原生还有内置通道）。
     */
    suspend fun llmList(timeoutMs: Long = 8_000): List<JsonObject> {
        val id = "l${seq.incrementAndGet()}"
        val deferred = CompletableDeferred<List<JsonObject>>()
        llmReplies[id] = deferred
        val payload = buildJsonObject {
            put("op", "llm.list")
            put("id", id)
        }
        if (!transport.send(payload.toString())) {
            llmReplies.remove(id)
            return emptyList()
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: run {
                llmReplies.remove(id)
                emptyList()
            }
    }

    /** P3-c：拉一次插件注册的斜杠命令（无 handler）。失败返回空列表。 */
    suspend fun commandList(timeoutMs: Long = 8_000): List<JsonObject> {
        val id = "cl${seq.incrementAndGet()}"
        val deferred = CompletableDeferred<List<JsonObject>>()
        commandListReplies[id] = deferred
        val payload = buildJsonObject {
            put("op", "command.list")
            put("id", id)
        }
        if (!transport.send(payload.toString())) {
            commandListReplies.remove(id)
            return emptyList()
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: run {
                commandListReplies.remove(id)
                emptyList()
            }
    }

    /**
     * P3-c：执行一条插件命令。超时/取消都返回 [NodeCommandCall.Failure]（不抛）——
     * 斜杠命令没有模型轮次可依赖，调用方需要一句能显示给人看的话。
     *
     * 取消（协程被取消）时补发 `command.cancel` 并把取消继续往上抛，
     * 和工具调用那条链保持同一纪律。
     */
    suspend fun commandInvoke(
        name: String,
        rawInput: String,
        timeoutMs: Long = 30_000,
        callId: String = "c${seq.incrementAndGet()}",
    ): NodeCommandCall {
        val id = callId
        val deferred = CompletableDeferred<NodeCommandCall>()
        commandReplies[id] = deferred
        val payload = buildJsonObject {
            put("op", "command.invoke")
            put("id", id)
            put("name", name)
            put("rawInput", rawInput)
            put("timeoutMs", timeoutMs)
        }
        if (!transport.send(payload.toString())) {
            commandReplies.remove(id)
            return NodeCommandCall.Failure("node 宿主未连接（${closedReason ?: "socket=null"}）", "offline", 0)
        }
        try {
            // 原生比宿主多等 2s：宿主的超时文案先到；这里兜底「整个宿主卡死」。
            return withTimeoutOrNull(timeoutMs + 2_000) { deferred.await() }
                ?: run {
                    commandReplies.remove(id)
                    sendCommandCancel(id)
                    NodeCommandCall.Failure(
                        "node 宿主 ${timeoutMs + 2_000}ms 内没回命令结果（可能已卡死）",
                        "timeout",
                        timeoutMs + 2_000,
                    )
                }
        } catch (cancelled: CancellationException) {
            commandReplies.remove(id)
            sendCommandCancel(id)
            throw cancelled
        }
    }

    private fun sendCommandCancel(id: String) {
        val frame = buildJsonObject {
            put("op", "command.cancel")
            put("id", id)
        }
        runCatching { transport.send(frame.toString()) }
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
        if (!transport.send(payload.toString())) return false
        val acked = withTimeoutOrNull(waitMs) { deferred.await() } != null
        ackWaiters.remove(id)
        return acked
    }

    private fun JsonObject.primitive(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.arrayOfObjects(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonObject.arrayOfStrings(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
}
