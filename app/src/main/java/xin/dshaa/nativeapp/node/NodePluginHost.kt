package xin.dshaa.nativeapp.node

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xin.dshaa.core.protocol.ApiTool
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult
import java.io.File
import java.security.SecureRandom

/**
 * Node 插件宿主（docs/185 P2 / docs/187 §九）：一个常驻 Node 进程 + 一条本地 WebSocket 桥。
 *
 * 生命周期：`Disabled → Extracting(首次解压) → Starting → Ready`；宿主意外退出时按
 * [NodeCrashPolicy] 重启（5 分钟内 ≥3 次进 `Failed`，不再无限重启）；`stop()` 走
 * `host.stop → ack → SIGTERM → 进程树 SIGKILL`。**并发 spawn 用 mutex 串行化**，
 * 否则首次启动的两次 onStart 会起两个 Node。
 *
 * WS 鉴权（第 2 项）：每次 spawn 生成 32B 随机 token，经 env `DSH_NODE_TOKEN` 传宿主，
 * 桥连接带 `?token=`；校验失败宿主直接关连接。
 *
 * 工具映射（§7.2 "原生落点只留数据类"）：Node 侧 `ctx.tools.schemas()` 的
 * `{name, description, parameters}` 原样搬进 [ApiTool]/[DshTool]，注册名加 `node_` 前缀，
 * 避免和原生工具重名；执行仍回到 Node 里跑，原生只审批 + 转发 + 写审计。
 */
class NodePluginHost(
    private val context: Context,
    /** 审计里的 `approval`：`auto`（自动允许）或 `ask`（每次询问），由设置页的审批模式决定。 */
    private val approvalMode: () -> String = { "ask" },
) {

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

    private val installer = NodeRuntimeInstaller(context)
    private val pluginInstaller = NodePluginInstaller(installer.dataDir)
    private val audit = NodeAuditLog(context.filesDir)
    private val surface = NodeToolSurface()
    private val crashPolicy = NodeCrashPolicy()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<State>(State.Disabled)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 每次 Ready / 失联后变化，ViewModel 用它触发工具表重算。 */
    private val _tools = MutableStateFlow<List<NodeToolSpec>>(emptyList())
    val tools: StateFlow<List<NodeToolSpec>> = _tools.asStateFlow()

    /** P3-b：插件登记的模型供应商（原生据此注册 ConfiguredLlmProvider）。 */
    private val _llmProviders = MutableStateFlow<List<NodeLlmProviderSpec>>(emptyList())
    val llmProviders: StateFlow<List<NodeLlmProviderSpec>> = _llmProviders.asStateFlow()

    /** P3-c：插件注册的斜杠命令（无 handler 的描述）。 */
    private val _commands = MutableStateFlow<List<NodeCommandSpec>>(emptyList())
    val commands: StateFlow<List<NodeCommandSpec>> = _commands.asStateFlow()

    @Volatile
    private var process: NodeHostProcess? = null

    @Volatile
    private var bridge: NodeHostBridge? = null

    @Volatile
    private var _port: Int = -1

    private var monitor: Job? = null

    /** 宿主原始输出（进程被回收后仍然保留最后一屏，失败排障不能只剩状态行）。 */
    private val retainedLogs = ArrayDeque<String>()

    private fun recordLog(line: String) {
        synchronized(retainedLogs) {
            retainedLogs.addLast(line.take(2_000))
            while (retainedLogs.size > 200) retainedLogs.removeFirst()
        }
    }

    val pid: Int
        get() = process?.pid ?: -1

    /** 当前监听端口（只给自测的「错误 token 必须被拒」用）。 */
    val port: Int
        get() = _port

    val rssKb: Long?
        get() = process?.rssKb()

    fun isReady(): Boolean = _state.value is State.Ready

    /** 启动（或复用）宿主；失败不抛，状态里带原因。 */
    suspend fun start(): Boolean = mutex.withLock {
        if (_state.value is State.Ready) return true
        // 用户主动重试 / 从 Failed 恢复：旧崩溃计数不该继续拦着。
        if (_state.value is State.Failed || _state.value is State.Restarting) crashPolicy.reset()
        _state.value = State.Extracting(0, "准备运行时")
        installer.ensureInstalled { p -> _state.value = State.Extracting(p.percent, p.detail) }
            .onFailure { error ->
                _state.value = State.Failed("运行时安装失败：${error.message}")
                return false
            }
        var result = false
        while (true) {
            if (spawnLocked()) {
                ensureMonitor()
                result = true
                break
            }
            val reason = describeState()
            if (isPermanentSpawnFailure(reason)) {
                // describeState() 对 Failed 会再加一个「失败：」前缀，这里用原始原因，别显示两层。
                _state.value = State.Failed(reason.removePrefix("失败："))
                break
            }
            val decision = crashPolicy.onExit(System.currentTimeMillis(), reason)
            if (!decision.restart) {
                _state.value = State.Failed("Node 宿主连续启动失败：${decision.reason}")
                break
            }
            _state.value = State.Restarting(decision.attempt, reason)
        }
        result
    }

    /**
     * 缺二进制/缺运行时是永久错误，重试没有意义（别把 3 次配额浪费在这上面）。
     *
     * docs/190-C：真机/模拟器的报错会把绝对路径写在前面
     * （`缺少 /data/app/…/lib/x86_64/libnode.so`），旧判断 `contains("缺少 libnode.so")` 匹配不到，
     * 于是同一个 ABI 错误被当成崩溃连试 3 次才 FAILED。这里同时认「缺少」+`libnode.so` 组合。
     */
    private fun isPermanentSpawnFailure(reason: String): Boolean =
        (reason.contains("缺少") && reason.contains("libnode.so")) ||
            reason.contains("安装包缺少")

    /** 真正起进程 + 连桥 + 拉 manifest。调用方必须已持锁。 */
    private suspend fun spawnLocked(): Boolean {
        _state.value = State.Starting
        val token = newToken()
        val proc = NodeHostProcess(installer, onLog = ::recordLog)
        process = proc
        val ready = runCatching { proc.start(READY_TIMEOUT_MS, token) }.getOrElse { error ->
            proc.stop(500)
            process = null
            _port = -1
            _state.value = State.Failed("Node 启动失败：${error.message}")
            Log.e(TAG, "spawn failed", error)
            return false
        }
        val ws = NodeHostBridge(onClosed = ::onBridgeLost)
        bridge = ws
        ws.connect(ready.port, token)
        _port = ready.port
        val hello = withTimeoutOrNull(HELLO_TIMEOUT_MS) { ws.hello.await() }
        if (hello == null) {
            ws.close()
            proc.stop(500)
            process = null
            bridge = null
            _port = -1
            _state.value = State.Failed("宿主握手超时（${HELLO_TIMEOUT_MS}ms）：${proc.tail(6).joinToString(" | ")}")
            return false
        }
        val specs = hello.tools.mapNotNull { descriptorOf(it) }
        surface.replace(specs)
        _tools.value = surface.snapshot()
        _llmProviders.value = NodeLlmProviders.parseAll(hello.llm)
        _commands.value = NodeCommands.parseAll(hello.commands)
        _state.value = State.Ready(
            pid = hello.pid,
            version = hello.version,
            bootMs = hello.bootMs,
            toolCount = specs.size,
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
        crashPolicy.onReady(System.currentTimeMillis())
        Log.i(
            TAG,
            "node host ready pid=${hello.pid} version=${hello.version} tools=${specs.size} " +
                "bootMs=${hello.bootMs} port=${ready.port}",
        )
        return true
    }

    private fun ensureMonitor() {
        if (monitor?.isActive == true) return
        monitor = scope.launch { watchForCrash() }
    }

    private fun descriptorOf(tool: JsonObject): NodeToolSpec? {
        val name = (tool["name"] as? JsonPrimitive)?.content ?: return null
        val description = (tool["description"] as? JsonPrimitive)?.content.orEmpty()
        val parameters = (tool["parameters"] as? JsonObject) ?: buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        }
        return NodeToolSpec(name, description, parameters)
    }

    private suspend fun watchForCrash() {
        while (scope.isActive) {
            delay(CRASH_POLL_MS)
            val dead = process?.takeUnless { it.isAlive() } ?: continue
            val reason = "宿主进程已退出（exit=${dead.lastExitCode}）：${dead.tail(6).joinToString(" | ")}"
            Log.w(TAG, reason)
            bridge?.close()
            bridge = null
            process = null
            _port = -1
            surface.clear()
            _tools.value = emptyList()

            var crashReason = reason
            while (scope.isActive) {
                val decision = crashPolicy.onExit(System.currentTimeMillis(), crashReason)
                if (!decision.restart) {
                    _state.value = State.Failed(decision.reason)
                    return
                }
                _state.value = State.Restarting(decision.attempt, crashReason)
                if (spawnLocked()) break
                crashReason = describeState()
            }
        }
    }

    /**
     * 桥非主动断开（EOF / 协议错误 / 宿主异常）：立刻摘工具并把进程收掉，
     * 交给 [watchForCrash] 按崩溃计数重启。否则模型会看到一张永远失败的工具表。
     */
    private fun onBridgeLost(reason: String) {
        if (_state.value !is State.Ready) return
        Log.w(TAG, "bridge lost: $reason")
        surface.clear()
        _tools.value = emptyList()
        // P3-b/c：供应商与命令跟工具表同进退（宿主没了还留着 = 模型/输入框指一个不存在的通道）。
        _llmProviders.value = emptyList()
        _commands.value = emptyList()
        _state.value = State.Starting
        // 保留 process 引用：watcher 下一次轮询能看到「已退出」并走重启。
        runCatching { process?.stop(500) }
    }

    /** 转发一次工具调用；不管宿主状态如何都返回 ToolResult，不往上抛（取消除外）。 */
    suspend fun invoke(
        toolName: String,
        args: JsonObject,
        timeoutMs: Long,
        toolContext: JsonObject? = null,
    ): ToolResult {
        val ws = bridge
            ?: return ToolResult.err("Node host is not ready (state=${stateCode()})")
        if (_state.value !is State.Ready) {
            return ToolResult.err("Node host is not ready (state=${stateCode()})")
        }
        val callId = ws.nextCallId()
        val approval = if (approvalMode() == "auto") "auto" else "ask"
        val argsSha = NodeAuditLog.sha256(args.toString())
        val pidNow = pid
        // 桥调用前先落 start 行：进程崩了也能看到「发出去但没回来」的调用。
        audit.start(callId, toolName, argsSha, approval, pidNow)
        val startedAt = System.currentTimeMillis()
        var result: ToolResult? = null
        var failureText: String? = null
        try {
            when (val call = ws.invoke(toolName, args, timeoutMs, callId, toolContext)) {
                is NodeCall.Ok -> {
                    val text = call.text ?: formatNodeValue(call.value)
                    result = ToolResult.ok(
                        text,
                        meta = mapOf(
                            "host" to "node",
                            "nodeTool" to toolName,
                            "callId" to callId,
                            "approval" to approval,
                            "ms" to call.ms.toString(),
                        ),
                    )
                }

                is NodeCall.Failure -> {
                    failureText = call.error
                    result = ToolResult.err(
                        call.error,
                        meta = mapOf(
                            "host" to "node",
                            "nodeTool" to toolName,
                            "callId" to callId,
                            "approval" to approval,
                            "ms" to call.ms.toString(),
                        ),
                    )
                }
            }
            return result!!
        } catch (cancelled: CancellationException) {
            // 桥 core 已经发过 tool.cancel 并移除 pending；这里只补审计，然后把取消往上抛。
            failureText = "已取消（调用方取消）"
            throw cancelled
        } finally {
            val ok = result?.isError == false
            audit.done(
                callId = callId,
                tool = toolName,
                argsSha256 = argsSha,
                approval = approval,
                ok = ok,
                error = failureText ?: result?.takeIf { it.isError }?.text,
                ms = System.currentTimeMillis() - startedAt,
                pid = pidNow,
            )
        }
    }

    /**
     * 拉一次插件注册的提示词段/上下文（`ctx.systemPrompt.section/context`）。
     * 宿主没就绪或超时都返回 null —— 提示词拼装绝不能因此失败。
     */
    suspend fun promptPayload(): NodePromptPayload? {
        val ws = bridge ?: return null
        if (_state.value !is State.Ready) return null
        return runCatching { ws.prompt() }.getOrNull()
    }

    /**
     * P3-b / P3-c：重拉插件登记的模型供应商与斜杠命令（宿主 Ready 后、插件重载后调用）。
     *
     * 失败返回 false 并保留旧列表（宁可显示上一次的快照，也不要把已经可用的通道清空）。
     */
    suspend fun refreshRegistrations(): Boolean {
        val ws = bridge ?: return false
        if (_state.value !is State.Ready) return false
        return runCatching {
            // 同步重拉工具表、模型供应商与斜杠命令，支持异步/动态注册工具刷新
            runCatching { ws.manifest() }.getOrNull()?.let { rawTools ->
                val specs = rawTools.mapNotNull { descriptorOf(it) }
                surface.replace(specs)
                _tools.value = surface.snapshot()
            }
            val llm = ws.llmList()
            val commands = ws.commandList()
            _llmProviders.value = NodeLlmProviders.parseAll(llm)
            _commands.value = NodeCommands.parseAll(commands)
            Log.i(
                TAG,
                "registrations tools=${_tools.value.size} llm=${_llmProviders.value.map { it.id }} " +
                    "commands=${_commands.value.map { it.name }}",
            )
            true
        }.getOrElse {
            Log.w(TAG, "refresh registrations failed: ${it.message}")
            false
        }
    }

    /** 这个命令名是不是宿主里的插件命令（原生先查这张表再转发）。 */
    fun commandSpec(name: String): NodeCommandSpec? =
        _commands.value.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * P3-c：执行一条插件命令。审计与工具调用同一套（`files/node-audit.jsonl`，
     * `tool="command:<name>"`）；权限沿用 P2 的审批模式（`auto` / `ask` 如实落盘）。
     * 不管宿主状态如何都返回 [NodeCommandCall]，**不抛**（取消除外）。
     */
    suspend fun invokeCommand(name: String, rawInput: String, timeoutMs: Long = 30_000): NodeCommandCall {
        val ws = bridge
            ?: return NodeCommandCall.Failure("Node 宿主未就绪（${describeState()}）", "offline", 0)
        if (_state.value !is State.Ready) {
            return NodeCommandCall.Failure("Node 宿主未就绪（${describeState()}）", "offline", 0)
        }
        val callId = ws.nextCallId()
        val tool = "command:$name"
        val approval = if (approvalMode() == "auto") "auto" else "ask"
        val argsSha = NodeAuditLog.sha256(rawInput)
        val pidNow = pid
        audit.start(callId, tool, argsSha, approval, pidNow)
        val startedAt = System.currentTimeMillis()
        var failure: String? = null
        var ok = false
        try {
            return when (val call = ws.invokeCommand(name, rawInput, timeoutMs, callId)) {
                is NodeCommandCall.Ok -> {
                    ok = true
                    call
                }

                is NodeCommandCall.Failure -> {
                    failure = call.error
                    call
                }
            }
        } catch (cancelled: CancellationException) {
            failure = "已取消（调用方取消）"
            throw cancelled
        } finally {
            audit.done(
                callId = callId,
                tool = tool,
                argsSha256 = argsSha,
                approval = approval,
                ok = ok,
                error = failure,
                ms = System.currentTimeMillis() - startedAt,
                pid = pidNow,
            )
        }
    }

    /** 给模型看的工具表：名字加 `node_` 前缀，schema 原样搬。 */
    fun apiTools(): List<ApiTool> = surface.snapshot().map { tool ->
        ApiTool(
            name = registryName(tool.name),
            description = tool.description.ifBlank { "Node plugin tool: ${tool.name}" },
            inputSchema = tool.parameters,
        )
    }

    /** 给 ToolRegistry 的工具实现：执行回 Node，附带 workspace/cwd/session 上下文；审批/审计由 [invoke] 统一做。 */
    fun dshTools(): List<DshTool> = surface.snapshot().map { tool ->
        object : DshTool {
            override val name: String = registryName(tool.name)

            override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
                val timeout = ctx.timeoutMs.coerceIn(MIN_TOOL_TIMEOUT_MS, MAX_TOOL_TIMEOUT_MS)
                val callContext = kotlinx.serialization.json.buildJsonObject {
                    put("workspace", kotlinx.serialization.json.JsonPrimitive(ctx.workspace.absolutePath))
                    put("cwd", kotlinx.serialization.json.JsonPrimitive(ctx.cwd.absolutePath))
                }
                return invoke(tool.name, args, timeout, callContext)
            }
        }
    }

    private fun formatNodeValue(value: kotlinx.serialization.json.JsonElement?): String {
        if (value == null) return ""
        if (value is kotlinx.serialization.json.JsonPrimitive && value.isString) return value.content
        if (value is JsonObject) {
            (value["text"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }
        }
        return value.toString()
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
                _port = -1
                surface.clear()
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
        _port = -1
        surface.clear()
        _tools.value = emptyList()
        // P3-b/c：供应商与命令跟工具表同进退（宿主没了还留着 = 模型/输入框指一个不存在的通道）。
        _llmProviders.value = emptyList()
        _commands.value = emptyList()
        _state.value = State.Stopped
    }

    /** 彻底收摊（ViewModel 销毁时调）：关进程后再 cancel 掉内部 scope。 */
    fun close() {
        shutdown()
        scope.cancel()
    }

    /** 稳定小写状态码：给模型/工具返回用（`describeState()` 是给 UI 的中文文案）。 */
    fun stateCode(): String = when (val s = _state.value) {
        State.Disabled -> "disabled"
        is State.Extracting -> "extracting"
        State.Starting -> "starting"
        is State.Restarting -> "restarting"
        is State.Ready -> "ready"
        is State.Failed -> "failed"
        State.Stopped -> "stopped"
    }

    fun describeState(): String = when (val s = _state.value) {        State.Disabled -> "已关闭"
        is State.Extracting -> "正在解压 ${s.percent}% ${s.detail}"
        State.Starting -> "正在启动"
        is State.Restarting -> "正在重启（第 ${s.attempt} 次）"
        is State.Ready -> "就绪 pid=${s.pid} ${s.version} 工具 ${s.toolCount}"
        is State.Failed -> "失败：${s.reason}"
        State.Stopped -> "已停止"
    }

    /** 宿主日志尾巴（插件页排障用）：桥上的 `host.log` 广播 + 进程 stdout + 最近的进程输出。 */
    fun tailLogs(max: Int = 20): List<String> {
        val fromBridge = bridge?.tail(max).orEmpty()
        val fromProcess = process?.tail(max).orEmpty()
        val retained = synchronized(retainedLogs) { retainedLogs.toList().takeLast(max) }
        return (fromBridge + fromProcess + retained).distinct().takeLast(max)
    }

    /** 清单里登记过的模块（含加载失败的）。 */
    fun manifestModules(): List<String> = pluginInstaller.modules()

    // ---- docs/190-B：宿主自治工具（nodehost_*）需要的只读事实 ----

    /** 打包的 Node 运行时版本（`install.json` 里的 version 与它一致才算装好）。 */
    val runtimeVersion: String get() = NodeRuntimeInstaller.RUNTIME_VERSION

    /** 运行时是否已解压且版本匹配（doctor 的 runtime 检查）。 */
    fun runtimeInstalled(): Boolean = installer.isInstalled()

    /** `install.json`（条目数/字节数/安装时间）；缺失或损坏返回 null。 */
    fun runtimeStamp(): NodeRuntimeInstaller.Stamp? = installer.stamp()

    /** 运行时根目录（`files/node/runtime`，reinstall-runtime 只换它，不动插件）。 */
    val runtimeRoot: File get() = installer.runtimeRoot

    /** `files/node`（用户插件在 `data/plugins` 下）。 */
    val installRoot: File get() = installer.root

    val dataDir: File get() = installer.dataDir
    val pluginsDir: File get() = installer.pluginsDir

    /** `dsh/host.mjs`（缺了 = 运行时坏了，doctor 建议 reinstall-runtime）。 */
    val hostScriptFile: File get() = installer.hostScript

    /** 只有 `nativeLibraryDir/libnode.so` 这一份能执行；缺失 = 该 ABI 没打包 Node。 */
    val nodeBinary: File get() = installer.binaryFile

    /** 当前设备主 ABI（诊断缺 `libnode.so` 时要说清是哪个 ABI）。 */
    val abi: String get() = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

    /** 私有目录剩余空间（doctor 的磁盘检查）。 */
    fun freeBytes(): Long = runCatching { context.filesDir.usableSpace }.getOrDefault(0L)

    /** 最后一次失败原因（非 Failed 状态返回 null）。 */
    fun lastFailureReason(): String? = (_state.value as? State.Failed)?.reason

    /** 模块说明符对应的插件目录（doctor 检查目录/入口是否存在）。 */
    fun pluginDir(module: String): File? = pluginInstaller.dirFor(module)

    /** 读取已安装 Node 插件的 JSON 配置。 */
    fun pluginConfig(idOrModule: String): String = pluginInstaller.getConfig(idOrModule)

    /** 保存已安装 Node 插件的 JSON 配置，并按需重启宿主使新配置生效。 */
    suspend fun savePluginConfig(
        idOrModule: String,
        configJson: String,
        restart: Boolean = true,
    ): Result<String> {
        val saved = withContext(Dispatchers.IO) { pluginInstaller.setConfig(idOrModule, configJson) }
        if (saved.isSuccess && restart) {
            reload()
        }
        return saved
    }

    /** 审计文件尾部（`nodehost_logs` 的 audit 来源）。 */
    fun auditTail(max: Int = 50): List<String> {
        val file = auditFile()
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines(Charsets.UTF_8).takeLast(max.coerceIn(1, 500))
        }.getOrDefault(emptyList())
    }

    /** 宿主管理动作审计（`phase:"event"`，一行一动作；不落明文参数）。 */
    fun auditEvent(tool: String, detail: String, ok: Boolean = true, approval: String = "ask") {
        runCatching { audit.event(tool, detail, ok, approval) }
    }

    /** 清掉临时解压目录（`cache/node-tmp`），返回释放的字节数。 */
    fun clearCache(): Long {
        val tmp = File(context.cacheDir, "node-tmp")
        val freed = sizeOf(tmp)
        runCatching { tmp.deleteRecursively() }
        if (freed > 0) Log.i(TAG, "node cache cleared: $freed bytes")
        return freed
    }

    private fun sizeOf(file: File): Long = when {
        file.isDirectory -> file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        file.isFile -> file.length()
        else -> 0L
    }

    /** P4：清单条目（id/name/version/module/hasClient），Node 分区与双装判断用。 */
    fun manifestEntries(): List<NodePluginInstaller.NodeModuleRecord> = pluginInstaller.entries()

    /** 用户可见的 Node 导入上限（导入页原样展示）。 */
    fun limitsText(): String = NodePluginInstaller.limitsText()

    /** 审计文件（自测/排障读取）。 */
    fun auditFile(): File = File(context.filesDir, "node-audit.jsonl")

    /** 最近窗口内的崩溃时间戳（自测证据）。 */
    fun crashTimes(): List<Long> = crashPolicy.crashTimes()

    /** 重启宿主拿最新清单：相当于「重新加载插件」。返回是否成功就绪。 */
    suspend fun reload(): Boolean {
        monitor?.cancel()
        monitor = null
        mutex.withLock { teardownLocked() }
        crashPolicy.reset()
        return start()
    }

    /**
     * docs/190-B `reinstall-runtime`：删掉 `files/node/runtime` 后重新解压并启动。
     *
     * **只换运行时**：`files/node/data`（用户插件/清单/home）一个字节都不动。返回是否就绪。
     */
    suspend fun reinstallRuntime(): Boolean {
        monitor?.cancel()
        monitor = null
        mutex.withLock { teardownLocked() }
        crashPolicy.reset()
        val root = installer.runtimeRoot
        val freed = sizeOf(root)
        val deleted = runCatching { root.deleteRecursively() }.getOrDefault(false)
        Log.i(TAG, "runtime reinstall: path=${root.absolutePath} deleted=$deleted freed=$freed")
        if (!deleted && root.exists()) {
            _state.value = State.Failed("无法删除旧运行时目录：${root.absolutePath}")
            return false
        }
        return start()
    }

    /**
     * docs/190-B 插件隔离：卸载一个反复加载失败的 Node 插件，并写一条 `quarantine` 审计。
     *
     * v1 的「禁用」= 从清单移除并删目录（Node 插件清单没有 enabled 字段）；[restart] = false
     * 时不动宿主进程（自动隔离后由调用方统一重启一次）。
     */
    suspend fun quarantinePlugin(
        idOrModule: String,
        reason: String,
        restart: Boolean = true,
    ): Result<String> {
        val removed = pluginInstaller.uninstall(idOrModule)
        removed.onSuccess { display ->
            audit.event("quarantine", "target=$idOrModule name=$display reason=${reason.take(300)}", true, "auto")
            Log.w(TAG, "quarantined node plugin: $idOrModule ($display): $reason")
        }.onFailure { error ->
            audit.event("quarantine", "target=$idOrModule error=${error.message.orEmpty().take(300)}", false, "auto")
        }
        if (removed.isSuccess && restart) reload()
        return removed
    }

    /**
     * 停进程/桥并回到 `Stopped`。调用方必须已持有 [mutex]（reload / reinstall 共用）。
     * 与 [shutdown] 的区别：不动 `scope`，也不清供应商/命令表（启动流程会重新拉）。
     */
    private suspend fun teardownLocked() {
        val ws = bridge
        if (ws != null) {
            ws.requestStop(STOP_ACK_MS)
            ws.close()
        }
        bridge = null
        process?.stop(STOP_GRACEFUL_MS)
        process = null
        _port = -1
        surface.clear()
        _tools.value = emptyList()
        _state.value = State.Stopped
    }

    /**
     * 装一个插件 zip：解到 `files/node/data/plugins/<名字>/`、写进清单、重启宿主。
     * 失败原因原样带回给界面（不吞）。
     *
     * [restart] = false 时只落盘 + 写清单，不动宿主进程（宿主关着时由 `nodehost_install` 用）。
     */
    suspend fun installPluginZip(
        bytes: ByteArray,
        fallbackName: String,
        resetConfig: Boolean = false,
        restart: Boolean = true,
    ): Result<NodePluginInstaller.Installed> {
        val installed = pluginInstaller.installZip(bytes, fallbackName, resetConfig)
        if (installed.isSuccess && restart) reload()
        return installed
    }

    /** 卸载一个 Node 插件（迁移到原生/用户删除用）；成功后重启宿主（[restart] = false 时不动进程）。 */
    suspend fun uninstallPlugin(idOrModule: String, restart: Boolean = true): Result<String> {
        val removed = pluginInstaller.uninstall(idOrModule)
        if (removed.isSuccess && restart) reload()
        return removed
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val TAG = "DshNodeHost"
        const val READY_TIMEOUT_MS = 20_000L
        const val HELLO_TIMEOUT_MS = 10_000L
        const val STOP_ACK_MS = 1_500L
        const val STOP_GRACEFUL_MS = 2_000L
        const val CRASH_POLL_MS = 1_000L
        const val MIN_TOOL_TIMEOUT_MS = 1_000L
        const val MAX_TOOL_TIMEOUT_MS = 600_000L
        const val TOKEN_BYTES = 32

        fun registryName(tool: String): String = if (tool.startsWith(NODE_TOOL_PREFIX)) tool else NODE_TOOL_PREFIX + tool
    }
}

/** 工具名前缀：和原生工具区分开，模型看到 `node_todo_write` 就知道它跑在 Node 宿主里。 */
const val NODE_TOOL_PREFIX = "node_"
