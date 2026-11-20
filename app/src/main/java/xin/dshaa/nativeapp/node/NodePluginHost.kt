package xin.dshaa.nativeapp.node

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import xin.dshaa.core.protocol.ApiTool
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult

/**
 * Node 插件宿主（docs/185 P2）：一个常驻 Node 进程 + 一条本地 WebSocket 桥。
 *
 * 生命周期（P2 验收项）：
 * `Disabled → Extracting(首次解压) → Starting → Ready`；宿主意外退出时按
 * `Restarting(1..3)` 重启，三次都失败才 `Failed`（并带上宿主最后几行输出，别让人猜）。
 * `stop()` 走 `host.stop → ack → SIGTERM → 进程树 SIGKILL`。**并发 spawn 用 mutex 串行化**，
 * 否则首次启动的两次 onStart 会起两个 Node。
 *
 * 工具映射（§7.2 "原生落点只留数据类"）：Node 侧 `ctx.tools.schemas()` 的
 * `{name, description, parameters}` 原样搬进 [ApiTool]/[DshTool]，注册名加 `node_` 前缀，
 * 避免和原生工具重名；执行仍回到 Node 里跑，原生只审批 + 转发 + 写回结果。
 */
class NodePluginHost(private val context: Context) {

    sealed interface State {
        /** 开关关着，或者还没到启动时机。 */
        data object Disabled : State

        /** 首次安装：解压 assets 里的 runtime.zip。 */
        data class Extracting(val percent: Int, val detail: String) : State

        data object Starting : State

        data class Restarting(val attempt: Int, val reason: String) : State

        data class Ready(
            val pid: Int,
            val version: String,
            val bootMs: Long,
            val toolCount: Int,
            val plugins: List<PluginStatus>,
        ) : State

        data class Failed(val reason: String) : State

        data object Stopped : State
    }

    data class PluginStatus(
        val module: String,
        val ok: Boolean,
        val error: String?,
        /** 这个插件注册了哪些工具（宿主按加载前后 `ctx.tools.schemas()` 差集算出来的）。 */
        val tools: List<String> = emptyList(),
    )

    /** 从宿主 manifest 搬过来的工具描述（参数 JSON Schema 原样保留）。 */
    data class NodeToolInfo(
        val name: String,
        val description: String,
        val parameters: JsonObject,
    )

    private val installer = NodeRuntimeInstaller(context)
    private val runtimeDir = java.io.File(context.filesDir, "node")
    private val pluginInstaller = NodePluginInstaller(runtimeDir)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow<State>(State.Disabled)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 每次 Ready 后变化，ViewModel 用它触发工具表重算。 */
    private val _tools = MutableStateFlow<List<NodeToolInfo>>(emptyList())
    val tools: StateFlow<List<NodeToolInfo>> = _tools.asStateFlow()

    @Volatile
    private var process: NodeHostProcess? = null

    @Volatile
    private var bridge: NodeHostBridge? = null

    private var monitor: Job? = null
    private var restartCount = 0

    val pid: Int
        get() = process?.pid ?: -1

    val rssKb: Long?
        get() = process?.rssKb()

    fun isReady(): Boolean = _state.value is State.Ready

    /** 启动（或复用）宿主；失败不抛，状态里带原因。 */
    suspend fun start(): Boolean = mutex.withLock {
        if (_state.value is State.Ready) return true
        _state.value = State.Extracting(0, "准备运行时")
        installer.ensureInstalled { p -> _state.value = State.Extracting(p.percent, p.detail) }
            .onFailure { error ->
                _state.value = State.Failed("运行时安装失败：${error.message}")
                return false
            }
        return spawnLocked()
    }

    /** 真正起进程 + 连桥 + 拉 manifest。调用方必须已持锁。 */
    private suspend fun spawnLocked(): Boolean {
        _state.value = State.Starting
        val proc = NodeHostProcess(installer) { line -> if (line.startsWith("HOST_READY")) Log.i(TAG, line) }
        process = proc
        val ready = runCatching { proc.start(READY_TIMEOUT_MS) }.getOrElse { error ->
            proc.stop(500)
            process = null
            _state.value = State.Failed("Node 启动失败：${error.message}")
            error.message?.let { Log.e(TAG, "spawn failed", error) }
            return false
        }
        val ws = NodeHostBridge()
        bridge = ws
        ws.connect(ready.port)
        val hello = withTimeoutOrNull(HELLO_TIMEOUT_MS) { ws.hello.await() }
        if (hello == null) {
            ws.close()
            proc.stop(500)
            process = null
            bridge = null
            _state.value = State.Failed("宿主握手超时（${HELLO_TIMEOUT_MS}ms）：${proc.tail(6).joinToString(" | ")}")
            return false
        }
        val descriptor = hello.tools.mapNotNull { tool -> descriptorOf(tool) }
        _tools.value = descriptor
        _state.value = State.Ready(
            pid = hello.pid,
            version = hello.version,
            bootMs = hello.bootMs,
            toolCount = descriptor.size,
            plugins = hello.plugins.mapNotNull { plugin ->
                val module = (plugin["module"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                PluginStatus(
                    module = module,
                    ok = (plugin["ok"] as? JsonPrimitive)?.content == "true",
                    error = (plugin["error"] as? JsonPrimitive)?.content,
                    tools = (plugin["tools"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content }
                        .orEmpty(),
                )
            },
        )
        restartCount = 0
        monitor?.cancel()
        monitor = scope.launch { watchForCrash() }
        Log.i(TAG, "node host ready pid=${hello.pid} version=${hello.version} tools=${descriptor.size} bootMs=${hello.bootMs}")
        return true
    }

    private fun descriptorOf(tool: JsonObject): NodeToolInfo? {
        val name = (tool["name"] as? JsonPrimitive)?.content ?: return null
        val description = (tool["description"] as? JsonPrimitive)?.content.orEmpty()
        val parameters = (tool["parameters"] as? JsonObject) ?: buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
        }
        return NodeToolInfo(name, description, parameters)
    }

    private suspend fun watchForCrash() {
        while (scope.isActive) {
            delay(CRASH_POLL_MS)
            val current = process ?: return
            if (current.isAlive()) continue
            val reason = "宿主进程已退出（exit=${current.lastExitCode}）：${current.tail(6).joinToString(" | ")}"
            Log.w(TAG, reason)
            bridge?.close()
            bridge = null
            process = null
            _tools.value = emptyList()
            if (restartCount >= MAX_RESTARTS) {
                _state.value = State.Failed("Node 宿主连续崩溃 ${MAX_RESTARTS} 次：$reason")
                return
            }
            restartCount += 1
            _state.value = State.Restarting(restartCount, reason)
            mutex.withLock {
                if (process == null || process?.isAlive() != true) spawnLocked()
            }
        }
    }

    /** 转发一次工具调用；不管宿主状态如何都返回 ToolResult，不往上抛。 */
    suspend fun invoke(toolName: String, args: JsonObject, timeoutMs: Long): ToolResult {
        val ws = bridge
            ?: return ToolResult.err("Node 宿主未就绪（${describeState()}）")
        if (_state.value !is State.Ready) {
            return ToolResult.err("Node 宿主未就绪（${describeState()}）")
        }
        return when (val call = ws.invoke(toolName, args, timeoutMs)) {
            is NodeHostBridge.Call.Ok -> ToolResult.ok(
                call.text ?: call.value?.toString().orEmpty(),
                meta = mapOf(
                    "host" to "node",
                    "nodeTool" to toolName,
                    "ms" to call.ms.toString(),
                ),
            )

            is NodeHostBridge.Call.Failure -> ToolResult.err(
                call.error,
                meta = mapOf(
                    "host" to "node",
                    "nodeTool" to toolName,
                    "ms" to call.ms.toString(),
                ),
            )
        }
    }

    /**
     * 拉一次插件注册的提示词段/上下文（`ctx.systemPrompt.section/context`）。
     * 宿主没就绪或超时都返回 null —— 提示词拼装绝不能因此失败。
     */
    suspend fun promptPayload(): NodeHostBridge.PromptPayload? {
        val ws = bridge ?: return null
        if (_state.value !is State.Ready) return null
        return runCatching { ws.prompt() }.getOrNull()
    }

    /** 给模型看的工具表：名字加 `node_` 前缀，schema 原样搬。 */
    fun apiTools(): List<ApiTool> = _tools.value.map { tool ->
        ApiTool(
            name = registryName(tool.name),
            description = tool.description.ifBlank { "Node plugin tool: ${tool.name}" },
            inputSchema = tool.parameters,
        )
    }

    /** 给 ToolRegistry 的工具实现：执行回 Node，审计靠 meta 里的 host=node 区分。 */
    fun dshTools(): List<DshTool> = _tools.value.map { tool ->
        object : DshTool {
            override val name: String = registryName(tool.name)

            override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
                val timeout = ctx.timeoutMs.coerceIn(MIN_TOOL_TIMEOUT_MS, MAX_TOOL_TIMEOUT_MS)
                return invoke(tool.name, args, timeout)
            }
        }
    }

    fun stop() {
        monitor?.cancel()
        monitor = null
        scope.launch {
            mutex.withLock {
                val ws = bridge
                if (ws != null) {
                    ws.requestStop(STOP_ACK_MS)
                    ws.close()
                }
                bridge = null
                process?.stop(STOP_GRACEFUL_MS)
                process = null
                _tools.value = emptyList()
                _state.value = State.Stopped
            }
        }
    }

    /**
     * 关开关 / App 关闭时收摊：停进程树、清工具表、状态回「已停止」。
     *
     * **不** cancel [scope]——关了还能再打开，正是开关的意义；真正的收摊用 [close]。
     */
    fun shutdown() {
        monitor?.cancel()
        monitor = null
        runCatching { bridge?.close() }
        runCatching { process?.stop(300) }
        bridge = null
        process = null
        _tools.value = emptyList()
        _state.value = State.Stopped
    }

    /** 彻底收摊（ViewModel 销毁时调）：关进程后再 cancel 掉内部 scope。 */
    fun close() {
        shutdown()
        scope.cancel()
    }

    fun describeState(): String = when (val s = _state.value) {
        State.Disabled -> "已关闭"
        is State.Extracting -> "正在解压 ${s.percent}% ${s.detail}"
        State.Starting -> "正在启动"
        is State.Restarting -> "正在重启（第 ${s.attempt} 次）"
        is State.Ready -> "就绪 pid=${s.pid} ${s.version} 工具 ${s.toolCount}"
        is State.Failed -> "失败：${s.reason}"
        State.Stopped -> "已停止"
    }

    /** 宿主日志尾巴（插件页排障用）：桥上的 `host.log` 广播 + 进程 stdout 合并。 */
    fun tailLogs(max: Int = 20): List<String> {
        val fromBridge = bridge?.tail(max).orEmpty()
        val fromProcess = process?.tail(max).orEmpty()
        return (fromBridge + fromProcess).distinct().takeLast(max)
    }

    /** 清单里登记过的模块（含加载失败的）。 */
    fun manifestModules(): List<String> = pluginInstaller.modules()

    /** 重启宿主拿最新清单：相当于「重新加载插件」。返回是否成功就绪。 */
    suspend fun reload(): Boolean {
        monitor?.cancel()
        monitor = null
        mutex.withLock {
            val ws = bridge
            if (ws != null) {
                ws.requestStop(STOP_ACK_MS)
                ws.close()
            }
            bridge = null
            process?.stop(STOP_GRACEFUL_MS)
            process = null
            _tools.value = emptyList()
            _state.value = State.Stopped
        }
        restartCount = 0
        return start()
    }

    /**
     * 装一个插件 zip：解到 `dsh/plugins/<名字>/`、写进清单、重启宿主。
     * 失败原因原样带回给界面（不吞）。
     */
    suspend fun installPluginZip(bytes: ByteArray, fallbackName: String): Result<String> {
        val installed = pluginInstaller.installZip(bytes, fallbackName)
        if (installed.isSuccess) reload()
        return installed
    }

    private companion object {
        const val TAG = "DshNodeHost"
        const val READY_TIMEOUT_MS = 20_000L
        const val HELLO_TIMEOUT_MS = 10_000L
        const val STOP_ACK_MS = 1_500L
        const val STOP_GRACEFUL_MS = 2_000L
        const val CRASH_POLL_MS = 1_000L
        const val MAX_RESTARTS = 3
        const val MIN_TOOL_TIMEOUT_MS = 1_000L
        const val MAX_TOOL_TIMEOUT_MS = 600_000L

        fun registryName(tool: String): String = if (tool.startsWith(NODE_TOOL_PREFIX)) tool else NODE_TOOL_PREFIX + tool
    }
}

/** 工具名前缀：和原生工具区分开，模型看到 `node_todo_write` 就知道它跑在 Node 宿主里。 */
const val NODE_TOOL_PREFIX = "node_"
