package xin.dshaa.nativeapp.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import xin.dshaa.core.js.JsHost
import xin.dshaa.core.js.JsOutcome
import xin.dshaa.core.js.JsStatus
import xin.dshaa.core.js.QuickJsSession
import xin.dshaa.core.plugins.PluginHostBridge
import xin.dshaa.core.plugins.PluginModule
import xin.dshaa.core.plugins.PluginPermissions
import xin.dshaa.core.plugins.PluginRecord
import xin.dshaa.core.plugins.ProjectionCheckpoint
import xin.dshaa.core.plugins.ProjectionCheckpoints
import xin.dshaa.core.plugins.PluginSandbox
import xin.dshaa.core.plugins.PluginSchema
import xin.dshaa.core.plugins.PluginServiceHost
import xin.dshaa.core.model.SessionEvent
import xin.dshaa.core.session.SessionSession
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * M84-H：插件**常驻 JS 运行时**与会话投影宿主。
 *
 * 背景：`QuickJs` 是「一次 evaluate = 新建 runtime、结算后销毁」，插件注册的每事件回调留不住；
 * 于是 `ctx.sessionProjections.register(...)` 一直只能是 unsupported。这一层给「注册了投影/事件订阅/
 * 清理器」的插件各起一个 [QuickJsSession]：`apply` 只跑一次，之后宿主每提交一条会话事件就 dispatch 进
 * 同一个 runtime——投影 fold（`init` / `apply` / `wire.view`）、`ctx.on` 订阅者、停用时的
 * `ctx.effect` cleanup 都用这同一个 runtime 的闭包与状态。
 *
 * 边界（不许夸口）：
 * - 只有**注册了投影/事件订阅/清理器**的启用插件才起常驻 runtime（其余插件仍是每次调用新建 QuickJS）；
 * - 投影回调必须**同步**（返回 Promise 记为插件错误，照 DSH 的同步纪律）；`ctx.on` handler 可以 async，
 *   宿主不等它，异常只记插件错误；
 * - `stateSchema` / `wire.viewSchema` 不做 zod 校验（QuickJS 里没有 zod），只记录有没有视图；
 * - 值只在内存里，App 重启从会话日志重放（打开会话时 attach 会把历史事件全投一遍）；
 * - 插件停用/删除/重载：先跑 ctx.effect cleanup，再关 runtime、收回投影值。
 */
class PluginResidentRuntime(
    private val scope: CoroutineScope,
    /** 插件通知（`plugin.notify` / `agent.notify`）；默认丢弃。 */
    private val notifier: (String, String) -> Unit = { _, _ -> },
    /** 设备/无障碍/截图等 app 侧能力；null = 单测/无宿主。 */
    private val hostBridge: PluginHostBridge? = null,
    /** M84-N：投影 checkpoint（App 重启只重投 `seq > lastSeq`）；null = 不持久化。 */
    private val checkpoints: ProjectionCheckpoints? = null,
    /** M85-D：回合插件调用 `ctx.status(text)` 时刷新界面状态条（null 文本 = 清空）。 */
    private val onAgentStatus: (pluginId: String, text: String?) -> Unit = { _, _ -> },
    /** M85-D：回合插件 `ctx.abort()` 查询「用户是否按了停止」。 */
    private val isAgentAborted: () -> Boolean = { false },
    /** M85-D：`ctx.tools.names()` 的工具名快照。 */
    private val agentToolNames: () -> List<String> = { emptyList() },
    /** M85-E：常驻运行时起来后通知宿主（插件存储需要等 runtime 才能列会话）。 */
    private val onResidentStarted: (pluginId: String) -> Unit = {},
    /**
     * M85-E：`ctx.storage.exportList/exportLoad` 的宿主实现（只读内置 builtin.storage.jsonl）。
     * 入参 = 方法名 + JSON payload，返回 JSON 文本；没有 storage 授权时宿主拒绝。
     */
    private val builtinSessionExport: (String, String) -> String = { _, _ ->
        """{"ok":false,"error":"宿主没有提供会话导出原语"}"""
    },
) {

    /** 一条投影值（`wire.view` 或 state 的 JSON 文本）。 */
    data class ProjectionValue(
        val pluginId: String,
        val sessionId: String,
        val key: String,
        val seq: Long?,
        val viewJson: String,
        val updatedAt: Long,
    )

    /** M84-N：投进常驻运行时队列的两类活（顺序即投递顺序：先恢复 checkpoint，再投增量事件）。 */
    private sealed interface QueueItem {
        class Event(val payload: String) : QueueItem
        class Restore(val payload: String) : QueueItem
    }

    /** 一个插件的常驻运行时。 */
    private class Residency(
        val record: PluginRecord,
        val session: QuickJsSession,
        val hostExecutor: ExecutorService,
        val channel: Channel<QueueItem>,
        var job: Job?,
        val keys: MutableList<String>,
        val subscriptions: MutableList<String>,
        val cleanups: Int,
        val streams: MutableList<String>,
        /** M85-A：注册的整壳 id（同步状态/dump 用）。 */
        val shells: MutableList<String>,
        /** M85-D：注册的回合实现 id（agent/v1；同步状态/dump 用）。 */
        val agents: MutableList<String>,
        /** M85-E：注册的存储实现 id（storage/v1；同步状态/dump 用）。 */
        val storages: MutableList<String>,
        /** M85-E：注册的提示词实现 id（prompt/v1；同步状态/dump 用）。 */
        val prompts: MutableList<String>,
        /** M84-M：callId → 本次 stream 的增量接收器（插件 `emit` 同步回灌进来）。 */
        val streamSinks: ConcurrentHashMap<String, (String) -> Unit>,
        var startedAt: Long,
        /** M84-N：上次落 checkpoint 的墙钟（节流用）。 */
        var lastCheckpointAt: Long = 0L,
        var error: String? = null,
    )

    private val residencies = ConcurrentHashMap<String, Residency>()

    /** sessionId → (插件 id + key) → 最新投影值。 */
    private val values = ConcurrentHashMap<String, MutableMap<String, ProjectionValue>>()

    /** 已经挂上事件监听的会话（避免重复 addEventListener；也用于给新起的 runtime 补投历史）。 */
    private val observed = ConcurrentHashMap<String, SessionSession>()

    /** 正在起 runtime 的插件（避免 sync 在启动完成前重复启动）。 */
    private val starting = ConcurrentHashMap.newKeySet<String>()

    /** M84-M：stream 调用序号（callId 用，保证并发/重入时 `emit` 不会串到别的一次请求）。 */
    private val streamCallSeq = java.util.concurrent.atomic.AtomicLong()

    /** 最近一次投影错误（插件/验收排查用）。 */
    private val recentErrors = ArrayDeque<String>()

    /** 当前常驻的插件数（同步状态，UI/dump 用）。 */
    fun residencyIds(): List<String> = residencies.keys.sorted()

    fun isResident(pluginId: String): Boolean = residencies.containsKey(pluginId)

    /**
     * M85-A：向某个常驻运行时 dispatch 一次（壳的 mount/state/action/event）。
     *
     * [QuickJsSession.dispatch] 自带单线程串行，宿主可以在任意线程调用；
     * 运行时不存在/已关闭返回 null（调用方超时/回退，不当成功）。
     */
    fun dispatch(pluginId: String, method: String, payloadJson: String, timeoutMs: Long = 3_000): JsOutcome? {
        val residency = residencies[pluginId] ?: return null
        return runCatching { residency.session.dispatch(method, payloadJson, timeoutMs) }.getOrNull()
    }

    /** M85-A：这个插件当前注册了哪些壳（运行时还没起来时为空）。 */
    fun shellsOf(pluginId: String): List<String> = residencies[pluginId]?.shells?.toList().orEmpty()

    /** M85-D：这个插件当前注册了哪些回合实现（`ctx.agent.register`，运行时起后才可见）。 */
    fun agentsOf(pluginId: String): List<String> = residencies[pluginId]?.agents?.toList().orEmpty()

    /** M85-E：这个插件当前注册了哪些存储实现（`ctx.storage.register`）。 */
    fun storagesOf(pluginId: String): List<String> = residencies[pluginId]?.storages?.toList().orEmpty()

    /** M85-E：这个插件当前注册了哪些提示词实现（`ctx.systemPrompt.register`）。 */
    fun promptsOf(pluginId: String): List<String> = residencies[pluginId]?.prompts?.toList().orEmpty()

    /** Only stream callbacks actually registered by this runtime, not stale dry-run metadata. */
    fun streamsOf(pluginId: String): List<String> = residencies[pluginId]?.streams?.toList().orEmpty()

    /**
     * 按最新插件清单同步常驻运行时：启用 + 有投影的插件起/保持，其余停。
     *
     * 在 `syncPluginTools()` 之后调用（导入/启用/停用/重载/删除都会走到）。
     */
    fun sync(records: List<PluginRecord>, workspace: File, services: Map<String, String> = emptyMap()) {
        val wanted = records.filter { record ->
            record.enabled &&
                (record.projections.isNotEmpty() || record.subscriptions.isNotEmpty() ||
                    record.effects.isNotEmpty() || record.llmProviders.any { it.hasStream } ||
                    record.shells.isNotEmpty() || record.agents.isNotEmpty() ||
                    record.storages.isNotEmpty() || record.prompts.isNotEmpty() ||
                    record.capabilities.isNotEmpty()) &&
                File(record.path, record.main).isFile
        }
        val wantedIds = wanted.map { it.id }.toSet()
        for ((id, residency) in residencies.entries.toList()) {
            val next = wanted.firstOrNull { it.id == id }
            if (next == null || next.path != residency.record.path || next.version != residency.record.version ||
                next.installedAt != residency.record.installedAt
            ) {
                stop(id)
            }
        }
        for (record in wanted) {
            if (residencies.containsKey(record.id) || !starting.add(record.id)) continue
            start(record, workspace, services)
        }
        // 收回已停用插件的投影值（保留仍在常驻的插件的值）
        for ((sessionId, map) in values) {
            map.entries.removeIf { (_, value) -> value.pluginId !in wantedIds }
            if (map.isEmpty()) values.remove(sessionId)
        }
        // M84-N：停用/删除的插件不再保留 checkpoint（再启用从会话开头重折）。只在清单非空时清理，
        // 避免启动早期 pluginsUi 还没加载就把全部 checkpoint 抹掉。
        val store = checkpoints
        if (store != null && records.isNotEmpty()) {
            var dropped = 0
            for (record in records) {
                if (!record.enabled) dropped += store.removePlugin(record.id)
            }
            if (dropped > 0) {
                Log.i(TAG, "checkpoint dropped=$dropped (disabled plugins)")
                scope.launch(Dispatchers.IO) { store.save() }
            }
        }
    }

    /** 插件被删除时清掉它的投影值与 checkpoint（[sync] 只处理停用，删除走这里）。 */
    fun forget(pluginId: String) {
        for ((sessionId, map) in values) {
            map.entries.removeIf { (_, value) -> value.pluginId == pluginId }
            if (map.isEmpty()) values.remove(sessionId)
        }
        val store = checkpoints ?: return
        if (store.removePlugin(pluginId) > 0) scope.launch(Dispatchers.IO) { store.save() }
    }

    /**
     * 把一条已打开的会话挂上监听，并把已有事件重放给所有常驻运行时。
     *
     * M84-N：有 checkpoint 的插件先 `projection.restore`（state + 上次 view），只重投
     * `seq > lastSeq` 的事件；checkpoint 的 `stateVersion` 与插件当前声明不一致时整条作废（从头折）。
     */
    fun attach(session: SessionSession) {
        val sessionId = session.header.id
        if (observed.putIfAbsent(sessionId, session) != null) return
        session.addEventListener { event -> deliver(session, event) }
        for (residency in residencies.values) replayInto(residency, session)
        Log.i(TAG, "attached session=$sessionId events=${session.events.size} residents=${residencies.size}")
    }

    /**
     * 把一条会话已有的事件投给某个常驻运行时：有 checkpoint 就先恢复、只投更新的；否则整段重放。
     *
     * 会被 [attach]（打开会话）和 [start]（运行时比会话晚起）共用，保证两条路都走 checkpoint。
     */
    private fun replayInto(residency: Residency, session: SessionSession) {
        val sessionId = session.header.id
        val entries = checkpointEntriesFor(residency, sessionId)
        if (entries.isEmpty()) {
            for (event in session.events) enqueue(residency, QueueItem.Event(payloadOf(session, event)))
            Log.i(TAG, "replay plugin=${residency.record.id} session=$sessionId events=${session.events.size} checkpoint=none")
            return
        }
        enqueue(residency, QueueItem.Restore(restorePayload(sessionId, entries)))
        seedValues(residency, sessionId, entries)
        val resumeFrom = entries.minOf { it.lastSeq }
        var replayed = 0
        for (event in session.events) {
            val seq = event.seq
            if (seq != null && seq <= resumeFrom) continue
            enqueue(residency, QueueItem.Event(payloadOf(session, event)))
            replayed++
        }
        Log.i(
            TAG,
            "checkpoint restore plugin=${residency.record.id} session=$sessionId keys=${entries.map { it.key }} " +
                "lastSeq=$resumeFrom replayed=$replayed/${session.events.size}",
        )
    }

    /** 该插件对这条会话可用的 checkpoint（key 仍声明 + stateVersion 一致 + lastSeq >= 0）。 */
    private fun checkpointEntriesFor(residency: Residency, sessionId: String): List<ProjectionCheckpoint> {
        val store = checkpoints ?: return emptyList()
        val declared = residency.record.projections.associate { it.key to it.stateVersion }
        return store.forSession(sessionId).filter {
            xin.dshaa.core.plugins.ProjectionCheckpointPolicy.usable(it, residency.record.id, declared)
        }
    }

    private fun restorePayload(sessionId: String, entries: List<ProjectionCheckpoint>): String = buildJsonObject {
        put("sessionId", sessionId)
        put(
            "entries",
            JsonArray(
                entries.map { entry ->
                    buildJsonObject {
                        put("key", entry.key)
                        put("stateVersion", entry.stateVersion)
                        put("seq", entry.lastSeq)
                        put("state", runCatching { Json.parseToJsonElement(entry.stateJson) }.getOrElse { kotlinx.serialization.json.JsonNull })
                        put("view", runCatching { Json.parseToJsonElement(entry.viewJson.ifBlank { "null" }) }.getOrElse { kotlinx.serialization.json.JsonNull })
                    }
                },
            ),
        )
    }.toString()

    /** 重启后 UI 立刻有值：把上次的 view 放回内存（新事件折出后会被覆盖）。 */
    private fun seedValues(residency: Residency, sessionId: String, entries: List<ProjectionCheckpoint>) {
        val map = values.getOrPut(sessionId) { ConcurrentHashMap() }
        for (entry in entries) {
            map["${residency.record.id}\u0000${entry.key}"] = ProjectionValue(
                pluginId = residency.record.id,
                sessionId = sessionId,
                key = entry.key,
                seq = entry.lastSeq,
                viewJson = entry.viewJson.ifBlank { "null" },
                updatedAt = entry.updatedAt,
            )
        }
    }

    /** 当前会话的投影值（按插件 + key 排序）。 */
    fun valuesFor(sessionId: String?): List<ProjectionValue> {
        if (sessionId.isNullOrBlank()) return emptyList()
        return values[sessionId]?.values?.sortedWith(compareBy({ it.pluginId }, { it.key })).orEmpty()
    }

    /** 全部投影值（dump 用）。 */
    fun allValues(): List<ProjectionValue> =
        values.values.flatMap { it.values }.sortedWith(compareBy({ it.sessionId }, { it.pluginId }, { it.key }))

    /**
     * M84-J/M84-M：把一次模型请求 dispatch 给插件的 `stream(request, emit)` 回调（阻塞，调用方放 IO 线程）。
     *
     * [onChunk] 是插件的 `emit(chunk)` 通道：插件在 JS 线程上同步调宿主 `llm.emit`，这里按 `callId`
     * 找到本次请求的接收器并立刻回调（实现方只做入队，不要阻塞）。返回
     * `{ok:true,text,toolCalls?,stopReason?,usage?,emitted}` / `{ok:false,error}` JSON 文本；
     * 插件没起常驻 runtime、超时、异常都归一成 `ok:false`，让供应商层转成 `LlmEvent.Failed`。
     */
    fun callProvider(
        pluginId: String,
        providerId: String,
        requestJson: String,
        timeoutMs: Long = PROVIDER_TIMEOUT_MS,
        onChunk: ((String) -> Unit)? = null,
    ): String {
        val residency = residencies[pluginId] ?: return errorJson("插件常驻运行时未就绪：$pluginId")
        val request = runCatching { Json.parseToJsonElement(requestJson) }
            .getOrElse { kotlinx.serialization.json.JsonPrimitive(requestJson) }
        val callId = "stream-${streamCallSeq.incrementAndGet()}"
        val payload = buildJsonObject {
            put("id", providerId)
            put("callId", callId)
            put("request", request)
        }.toString()
        if (onChunk != null) residency.streamSinks[callId] = onChunk
        try {
            val outcome = residency.session.dispatch("llm.stream", payload, timeoutMs)
            return when (outcome.status) {
                JsStatus.COMPLETED -> {
                    val text = outcome.valueJson
                    val emitted = runCatching {
                        Json.parseToJsonElement(text ?: "null").jsonObject["emitted"]?.jsonPrimitive?.longOrNull
                    }.getOrNull() ?: 0L
                    Log.i(
                        TAG,
                        "stream plugin=$pluginId provider=$providerId chars=${text?.length ?: 0} chunks=$emitted",
                    )
                    text ?: errorJson("插件 stream 返回空结果")
                }

                JsStatus.INTERRUPTED -> errorJson("插件 stream 超时（>${timeoutMs}ms）")
                JsStatus.ERROR -> errorJson("插件 stream 报错：${outcome.error ?: "QuickJS error"}")
            }
        } finally {
            residency.streamSinks.remove(callId)
        }
    }

    private fun errorJson(message: String): String =
        buildJsonObject { put("ok", false); put("error", message) }.toString()

    /**
     * M85-D：`agent/v1` 的宿主原语。
     *
     * 权限门和 sandbox 一致：必须真的拿到 `agent` 授权（导入即允许，但停用/撤销后立刻失效）。
     * 只开放 notify/status/abort/tools.names；`tools.invoke` / `llm.stream` / `storage.append`
     * 在 M85-D 明确返回 `UNSUPPORTED`（M85-E 再做），避免插件以为调用成功了。
     */
    private fun agentHostCall(record: PluginRecord, method: String, payloadJson: String): String {
        val granted = record.grantedPermissions.any { it.equals(PluginPermissions.AGENT, true) } &&
            record.permissions.any { it.trim().lowercase() == PluginPermissions.AGENT }
        if (!granted) {
            return buildJsonObject {
                put("ok", false)
                put("error", "缺少 agent 授权（插件设置里授权后再选它）")
                put("code", "PERMISSION_DENIED")
            }.toString()
        }
        val payload = runCatching { Json.parseToJsonElement(payloadJson).jsonObject }.getOrNull()
        return when (method) {
            "agent.notify" -> {
                val title = payload?.get("title")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    ?: "回合插件"
                val body = payload?.get("body")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: ""
                notifier(title, body)
                """{"ok":true}"""
            }

            "agent.status" -> {
                val text = payload?.get("text")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    ?.takeIf { it.isNotBlank() }
                onAgentStatus(record.id, text)
                """{"ok":true}"""
            }

            "agent.abort" -> buildJsonObject {
                put("ok", true)
                put("abort", isAgentAborted())
            }.toString()

            "agent.tools.names" -> buildJsonObject {
                put("ok", true)
                put(
                    "names",
                    JsonArray(agentToolNames().sorted().map { kotlinx.serialization.json.JsonPrimitive(it) }),
                )
            }.toString()

            "agent.tools.invoke", "agent.llm.stream", "agent.storage.append" -> buildJsonObject {
                put("ok", false)
                put("error", "${method.removePrefix("agent.")} 在 agent/v1 的 M85-D 版本未开放（M85-E 接入存储/模型后开放）")
                put("code", "UNSUPPORTED")
            }.toString()

            else -> buildJsonObject {
                put("ok", false)
                put("error", "未知 agent 原语：$method")
                put("code", "UNKNOWN_METHOD")
            }.toString()
        }
    }

    /** 运行时状态 + 投影值 + 最近错误落成 JSON 文本（debug dump）。 */
    fun dumpJson(activeSessionId: String?): String {
        val root = buildJsonObject {
            put("version", 1)
            put("capabilities", xin.dshaa.core.plugins.PluginCapabilities.VERSION)
            put("activeSession", activeSessionId ?: "")
            put("resident", JsonArray(residencyIds().map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put(
                "plugins",
                JsonArray(
                    residencies.values.sortedBy { it.record.id }.map { residency ->
                        buildJsonObject {
                            put("id", residency.record.id)
                            put("version", residency.record.version)
                            put("keys", JsonArray(residency.keys.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                            put(
                                "subscriptions",
                                JsonArray(residency.subscriptions.map { kotlinx.serialization.json.JsonPrimitive(it) }),
                            )
                            put("cleanups", residency.cleanups)
                            put(
                                "streams",
                                JsonArray(residency.streams.map { kotlinx.serialization.json.JsonPrimitive(it) }),
                            )
                            put(
                                "shells",
                                JsonArray(residency.shells.map { kotlinx.serialization.json.JsonPrimitive(it) }),
                            )
                            put(
                                "agents",
                                JsonArray(residency.agents.map { kotlinx.serialization.json.JsonPrimitive(it) }),
                            )
                            put(
                                "storages",
                                JsonArray(residency.storages.map { kotlinx.serialization.json.JsonPrimitive(it) }),
                            )
                            put(
                                "prompts",
                                JsonArray(residency.prompts.map { kotlinx.serialization.json.JsonPrimitive(it) }),
                            )
                            put("startedAt", residency.startedAt)
                            put("error", residency.error ?: "")
                        }
                    },
                ),
            )
            put(
                "values",
                JsonArray(
                    allValues().map { value ->
                        buildJsonObject {
                            put("plugin", value.pluginId)
                            put("session", value.sessionId)
                            put("key", value.key)
                            put("seq", value.seq ?: -1)
                            put(
                                "view",
                                runCatching { Json.parseToJsonElement(value.viewJson) }
                                    .getOrDefault(kotlinx.serialization.json.JsonPrimitive(value.viewJson)),
                            )
                        }
                    },
                ),
            )
            put("errors", JsonArray(recentErrors.toList().map { kotlinx.serialization.json.JsonPrimitive(it) }))
            // M84-N：checkpoint 状态（验收「重启只重投新事件」时看 mem/size 与当前会话的 lastSeq）。
            val store = checkpoints
            put(
                "checkpoints",
                buildJsonObject {
                    put("enabled", store != null)
                    put("loadedFromDisk", store?.loadedFromDisk == true)
                    put("total", store?.size() ?: 0)
                    put(
                        "current",
                        JsonArray(
                            (activeSessionId?.let { store?.forSession(it) }.orEmpty()).map { entry ->
                                buildJsonObject {
                                    put("plugin", entry.pluginId)
                                    put("key", entry.key)
                                    put("stateVersion", entry.stateVersion)
                                    put("lastSeq", entry.lastSeq)
                                    put("updatedAt", entry.updatedAt)
                                }
                            },
                        ),
                    )
                },
            )
        }
        return root.toString()
    }

    /** 让某个插件的常驻运行时失效（重载/换代码后调用；下一次 sync 会用最新文件重建）。 */
    fun invalidate(pluginId: String) {
        stop(pluginId)
    }

    /** 等所有「正在起 runtime」的插件落定（dump/验收用；最多等 [timeoutMs]）。 */
    fun awaitSettled(timeoutMs: Long = 3_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (starting.isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
    }

    /** 关闭全部常驻运行时（进程退出/测试）。 */
    fun close() {
        for (id in residencies.keys.toList()) stop(id)
    }

    // ---- 内部 ----

    private fun start(record: PluginRecord, workspace: File, services: Map<String, String>) {
        scope.launch(Dispatchers.IO) {
            val entry = File(record.path, record.main)
            val source = runCatching { entry.readText(Charsets.UTF_8) }.getOrElse {
                addError(record.id, "读不了插件入口：${it.message}")
                starting.remove(record.id)
                return@launch
            }
            val module = runCatching { PluginModule.transform(source) }.getOrElse {
                addError(record.id, "解析插件模块失败：${it.message}")
                starting.remove(record.id)
                return@launch
            }
            val configJson = PluginSchema.effectiveText(record.configSchema, record.config, record.configDefaults)
            val script = runCatching {
                PluginModule.residentScript(module, workspace.absolutePath, configJson)
            }.getOrElse {
                addError(record.id, "生成常驻脚本失败：${it.message}")
                starting.remove(record.id)
                return@launch
            }
            val sandbox = PluginSandbox(
                pluginId = record.id,
                workspace = workspace,
                permissions = PluginPermissions.parseAll(record.permissions),
                grantedPermissions = record.grantedPermissions,
                bridge = hostBridge,
            )
            val serviceHost = PluginServiceHost(initial = services)
            val hostExecutor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "plugin-resident-host-${record.id}").apply { isDaemon = true }
            }
            val jsRef = AtomicReference<QuickJsSession?>(null)
            // M84-M：本插件的 stream 增量接收器（插件 emit → llm.emit → 这里）。
            val streamSinks = ConcurrentHashMap<String, (String) -> Unit>()
            val session = QuickJsSession(object : JsHost {
                override fun callSync(method: String, payloadJson: String): String? {
                    if (method.startsWith("plugin.services.")) return serviceHost.callSync(method, payloadJson)
                    // M84-M：插件模型的增量通道。JS 线程上同步进来，接收器只做入队。
                    if (method == "llm.emit") {
                        val payload = runCatching { Json.parseToJsonElement(payloadJson).jsonObject }.getOrNull()
                        val callId = runCatching {
                            payload?.get("callId")?.jsonPrimitive?.content
                        }.getOrNull().orEmpty()
                        val sink = streamSinks[callId]
                            ?: return errorJson("没有进行中的 stream：$callId")
                        val chunk = payload?.get("chunk")?.toString() ?: "null"
                        return try {
                            sink(chunk)
                            """{"ok":true}"""
                        } catch (t: Throwable) {
                            errorJson("stream 接收器失败：${t.message ?: t.javaClass.simpleName}")
                        }
                    }
                    if (method == "log") {
                        Log.i(TAG, "resident ${record.id} console: ${payloadJson.take(1000)}")
                        return """{"ok":true}"""
                    }
                    if (method == "plugin.notify") {
                        val payload = runCatching { Json.parseToJsonElement(payloadJson).jsonObject }.getOrNull()
                        val title = payload?.get("title")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                            ?: "插件通知"
                        val body = payload?.get("body")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                            ?: ""
                        notifier(title, body)
                        return """{"ok":true}"""
                    }
                    // M85-D：回合插件原语（agent/v1）。只开放 notify/status/abort/tools.names；
                    // 其余在 v1 明确回「未开放」，不静默成功（M85-E 再做 storage / llm / tools.invoke）。
                    if (method.startsWith("agent.")) {
                        return agentHostCall(record, method, payloadJson)
                    }
                    // M85-E：只读存储导出原语（ctx.storage.exportList/exportLoad）。
                    // 只给有 storage 授权、且真的在导出内置会话的插件；活动存储不会被递归读取。
                    if (method == "storage.exportList" || method == "storage.exportLoad") {
                        val granted = record.grantedPermissions.any {
                            it.equals(PluginPermissions.STORAGE, true)
                        } && record.permissions.any { it.trim().lowercase() == PluginPermissions.STORAGE }
                        if (!granted) {
                            return buildJsonObject {
                                put("ok", false)
                                put("error", "缺少 storage 授权（插件设置里授权后再选它）")
                                put("code", "PERMISSION_DENIED")
                            }.toString()
                        }
                        return builtinSessionExport(method, payloadJson)
                    }
                    return sandbox.call(method, payloadJson).payloadJson
                }

                override fun callAsync(method: String, payloadJson: String, callId: Int) {
                    hostExecutor.execute {
                        val reply = sandbox.call(method, payloadJson)
                        val payload = if (reply.ok) {
                            reply.payloadJson
                        } else {
                            buildJsonObject {
                                put("message", reply.error ?: "宿主拒绝")
                                put("code", reply.code ?: "PLUGIN_HOST_ERROR")
                                put("fatal", true)
                            }.toString()
                        }
                        jsRef.get()?.complete(callId, reply.ok, payload)
                    }
                }
            })
            jsRef.set(session)
            val outcome = session.start(script, entry.name, RESIDENT_START_TIMEOUT_MS)
            val keys = mutableListOf<String>()
            val subscriptions = mutableListOf<String>()
            val streams = mutableListOf<String>()
            val shells = mutableListOf<String>()
            val agents = mutableListOf<String>()
            val storages = mutableListOf<String>()
            val prompts = mutableListOf<String>()
            val capabilities = mutableListOf<String>()
            var cleanups = 0
            var startError: String? = null
            when (outcome.status) {
                JsStatus.COMPLETED -> {
                    val parsed = parseResidentStart(outcome)
                    if (parsed == null) {
                        startError = "常驻启动没有返回 JSON：${(outcome.valueJson ?: "null").take(300)}"
                    } else {
                        keys += parsed.keys
                        subscriptions += parsed.subscriptions
                        streams += parsed.streams
                        shells += parsed.shells
                        agents += parsed.agents
                        storages += parsed.storages
                        prompts += parsed.prompts
                        capabilities += parsed.capabilities
                        cleanups = parsed.cleanups
                        if (parsed.error.isNotBlank()) startError = parsed.error
                    }
                }

                JsStatus.INTERRUPTED -> startError = "常驻启动超时（>${RESIDENT_START_TIMEOUT_MS}ms）"
                JsStatus.ERROR -> startError = "常驻启动报错：${outcome.error ?: "QuickJS error"}"
            }
            if (startError != null ||
                (keys.isEmpty() && subscriptions.isEmpty() && cleanups == 0 && streams.isEmpty() &&
                    shells.isEmpty() && agents.isEmpty() && storages.isEmpty() && prompts.isEmpty() &&
                    capabilities.isEmpty())
            ) {
                addError(record.id, startError ?: "插件没有注册投影/事件订阅/清理器/stream/壳/回合/存储/提示词/能力")
                Log.w(TAG, "resident start failed for ${record.id}: ${startError ?: "nothing registered"}")
                runCatching { session.close() }
                hostExecutor.shutdownNow()
                starting.remove(record.id)
                return@launch
            }
            val residency = Residency(
                record = record,
                session = session,
                hostExecutor = hostExecutor,
                channel = Channel(Channel.UNLIMITED),
                job = null,
                keys = keys,
                subscriptions = subscriptions,
                cleanups = cleanups,
                streams = streams,
                shells = shells,
                agents = agents,
                storages = storages,
                prompts = prompts,
                streamSinks = streamSinks,
                startedAt = System.currentTimeMillis(),
                error = null,
            )
            residency.job = scope.launch(Dispatchers.IO) { consume(residency) }
            residencies[record.id] = residency
            starting.remove(record.id)
            Log.i(
                TAG,
                "resident started plugin=${record.id} keys=${keys.joinToString(",")} " +
                    "subscriptions=${subscriptions.joinToString(",")} cleanups=$cleanups streams=${streams.joinToString(",")} " +
                    "shells=${shells.joinToString(",")} agents=${agents.joinToString(",")} " +
                    "storages=${storages.joinToString(",")} prompts=${prompts.joinToString(",")}",
            )
            // 已在观察中的会话：按 checkpoint 补投（App 重启只补 seq > lastSeq 的事件）
            for (live in observed.values.toList()) {
                replayInto(residency, live)
            }
            // M85-E：存储/提示词插件要等 runtime 起来才能列会话/拼提示词，通知宿主刷新。
            runCatching { onResidentStarted(record.id) }
        }
    }

    private fun stop(pluginId: String) {
        starting.remove(pluginId)
        val residency = residencies.remove(pluginId) ?: return
        // Capture this generation's executor before a new residency can reuse the plugin id.
        val hostExecutor = residency.hostExecutor
        residency.job?.cancel()
        residency.channel.close()
        residency.streamSinks.clear()
        scope.launch(Dispatchers.IO) {
            // M84-I：停用/重载前先跑 ctx.effect 的 cleanup（best-effort，3s 超时）。
            if (residency.cleanups > 0) {
                val outcome = runCatching { residency.session.dispatch("shutdown", "{}", 3_000) }.getOrNull()
                if (outcome != null && outcome.status == JsStatus.COMPLETED) {
                    val text = runCatching {
                        Json.parseToJsonElement(outcome.valueJson ?: "null").jsonObject
                    }.getOrNull()
                    val cleaned = runCatching { text?.get("cleaned")?.jsonPrimitive?.longOrNull }.getOrNull() ?: 0L
                    Log.i(TAG, "resident shutdown plugin=$pluginId cleaned=$cleaned")
                } else {
                    Log.w(TAG, "resident shutdown failed plugin=$pluginId status=${outcome?.status}")
                }
            }
            runCatching { residency.session.close() }
            hostExecutor.shutdownNow()
        }
        Log.i(TAG, "resident stopped plugin=$pluginId")
    }

    private fun deliver(session: SessionSession, event: SessionEvent) {
        if (residencies.isEmpty()) return
        val payload = payloadOf(session, event)
        for (residency in residencies.values) enqueue(residency, QueueItem.Event(payload))
    }

    private fun enqueue(residency: Residency, item: QueueItem) {
        if (residency.channel.trySend(item).isFailure) {
            addError(residency.record.id, "投影事件队列已关闭，丢弃一条事件")
        }
    }

    private fun payloadOf(session: SessionSession, event: SessionEvent): String {
        val header = buildJsonObject {
            put("id", session.header.id)
            put("cwd", session.header.cwd)
            put("createdAt", session.header.createdAt)
            put("agentPreset", session.header.agentPreset ?: "")
            put("parentSession", session.header.parentSession ?: "")
        }
        val eventJson = buildJsonObject {
            put("type", event.type)
            put("seq", event.seq ?: -1L)
            put("time", event.time ?: 0L)
            put("data", event.data ?: kotlinx.serialization.json.JsonNull)
            event.surfaceOp?.let { put("surfaceOp", it) }
        }
        return buildJsonObject {
            put("sessionId", session.header.id)
            put("header", header)
            put("inheritedEventCount", 0)
            put("event", eventJson)
        }.toString()
    }

    private suspend fun consume(residency: Residency) {
        for (item in residency.channel) {
            when (item) {
                is QueueItem.Restore -> {
                    val outcome = residency.session.dispatch("projection.restore", item.payload, DISPATCH_TIMEOUT_MS)
                    val restored = runCatching {
                        Json.parseToJsonElement(outcome.valueJson ?: "null").jsonObject["restored"]?.jsonPrimitive?.longOrNull
                    }.getOrNull() ?: 0L
                    if (outcome.status == JsStatus.COMPLETED) {
                        Log.i(TAG, "checkpoint restored plugin=${residency.record.id} entries=$restored")
                    } else {
                        addError(residency.record.id, "checkpoint 恢复失败：${outcome.error ?: outcome.status}")
                        Log.w(TAG, "checkpoint restore failed plugin=${residency.record.id} status=${outcome.status}")
                    }
                }

                is QueueItem.Event -> {
                    val outcome = residency.session.dispatch("session.event", item.payload, DISPATCH_TIMEOUT_MS)
                    when (outcome.status) {
                        JsStatus.COMPLETED -> applyDispatchResult(residency, item.payload, outcome)
                        JsStatus.INTERRUPTED -> {
                            addError(residency.record.id, "投影 apply 超时（>${DISPATCH_TIMEOUT_MS}ms），已中断")
                            Log.w(TAG, "dispatch interrupted plugin=${residency.record.id}")
                        }

                        JsStatus.ERROR -> {
                            addError(residency.record.id, "投影 dispatch 报错：${outcome.error ?: "QuickJS error"}")
                            Log.w(TAG, "dispatch failed plugin=${residency.record.id}: ${outcome.error}")
                        }
                    }
                    // M84-N：这一批事件处理完（队列空）就落 checkpoint，重启只重投更新的事件。
                    // 回合/工具边界强制落一次：别让节流把「最后一屏」留成过期 checkpoint。
                    val eventType = eventTypeOf(item.payload)
                    maybeCheckpoint(
                        residency,
                        item.payload,
                        force = eventType == "turn/end" || eventType == "tool/result",
                    )
                }
            }
        }
    }

    /**
     * M84-N：把当前会话的投影 state/seq/view 快照进 checkpoint 并落盘。
     *
     * 节流 [CHECKPOINT_INTERVAL_MS]：事件密集时不必每条都存；checkpoint 旧一点只是重启后多回放几条，
     * 不会错（`apply` 前有 seq 守卫）。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun maybeCheckpoint(residency: Residency, eventPayload: String, force: Boolean = false) {
        val store = checkpoints ?: return
        // 停用/重载时 runtime 已从注册表摘掉：别让收尾中的快照把 checkpoint 又写回来。
        if (residencies[residency.record.id] !== residency) return
        if (!residency.channel.isEmpty) return
        val now = System.currentTimeMillis()
        if (!force && now - residency.lastCheckpointAt < CHECKPOINT_INTERVAL_MS) return
        val sessionId = sessionIdOf(eventPayload) ?: return
        val payload = buildJsonObject { put("sessionId", sessionId) }.toString()
        val outcome = residency.session.dispatch("projection.snapshot", payload, DISPATCH_TIMEOUT_MS)
        if (outcome.status != JsStatus.COMPLETED) {
            addError(residency.record.id, "checkpoint 快照失败：${outcome.error ?: outcome.status}")
            return
        }
        residency.lastCheckpointAt = now
        val obj = runCatching { Json.parseToJsonElement(outcome.valueJson ?: "null").jsonObject }.getOrNull() ?: return
        val items = ArrayList<ProjectionCheckpoint>()
        for (element in runCatching { obj["entries"]?.jsonArray }.getOrNull().orEmpty()) {
            val entry = runCatching { element.jsonObject }.getOrNull() ?: continue
            val key = entry["key"]?.jsonPrimitive?.content ?: continue
            val lastSeq = runCatching { entry["seq"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: -1L
            if (lastSeq < 0) continue
            items += ProjectionCheckpoint(
                pluginId = residency.record.id,
                sessionId = sessionId,
                key = key,
                stateVersion = runCatching { entry["stateVersion"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 1,
                lastSeq = lastSeq,
                stateJson = entry["state"]?.toString() ?: "null",
                viewJson = entry["view"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.toString().orEmpty(),
                updatedAt = now,
            )
        }
        if (items.isEmpty()) return
        store.putAll(items)
        store.save()
        Log.i(
            TAG,
            "checkpoint saved plugin=${residency.record.id} session=$sessionId " +
                "entries=${items.map { "${it.key}@${it.lastSeq}" }} total=${store.size()}",
        )
    }

    private fun sessionIdOf(payload: String): String? = runCatching {
        Json.parseToJsonElement(payload).jsonObject["sessionId"]?.jsonPrimitive?.content
    }.getOrNull()

    private fun eventTypeOf(payload: String): String? = runCatching {
        Json.parseToJsonElement(payload).jsonObject["event"]?.jsonObject
            ?.get("type")?.jsonPrimitive?.content
    }.getOrNull()

    private fun applyDispatchResult(residency: Residency, payload: String, outcome: JsOutcome) {
        val sessionId = sessionIdOf(payload).orEmpty()
        val obj = runCatching { Json.parseToJsonElement(outcome.valueJson ?: "null").jsonObject }.getOrNull()
        if (obj == null) {
            addError(residency.record.id, "投影结果不是 JSON：${(outcome.valueJson ?: "null").take(200)}")
            return
        }
        if (obj["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == false) {
            obj["error"]?.let { addError(residency.record.id, "投影结果失败：${it.jsonPrimitive.content}") }
        }
        // M84-I：ctx.on 订阅者被调用了几次（handler 是同步调用；async handler 不阻塞）。
        val handled = runCatching { obj["handled"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: 0L
        if (handled > 0) {
            val eventType = runCatching {
                Json.parseToJsonElement(payload).jsonObject["event"]?.jsonObject
                    ?.get("type")?.jsonPrimitive?.content
            }.getOrNull()
            Log.i(TAG, "event plugin=${residency.record.id} event=$eventType handlers=$handled")
        }
        val map = values.getOrPut(sessionId) { ConcurrentHashMap() }
        obj["values"]?.let { element ->
            (element as? JsonArray)?.forEach { item ->
                val value = item as? JsonObject ?: return@forEach
                val key = value["key"]?.jsonPrimitive?.content ?: return@forEach
                val seq = value["seq"]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
                val view = value["view"] ?: kotlinx.serialization.json.JsonNull
                map["${residency.record.id}\u0000$key"] = ProjectionValue(
                    pluginId = residency.record.id,
                    sessionId = sessionId,
                    key = key,
                    seq = seq,
                    viewJson = view.toString(),
                    updatedAt = System.currentTimeMillis(),
                )
                Log.i(TAG, "projection plugin=${residency.record.id} key=$key seq=$seq view=${view.toString().take(300)}")
            }
        }
        obj["errors"]?.let { element ->
            (element as? JsonArray)?.forEach { item ->
                val error = item as? JsonObject ?: return@forEach
                val key = error["key"]?.jsonPrimitive?.content.orEmpty()
                val message = error["error"]?.jsonPrimitive?.content.orEmpty()
                addError(residency.record.id, "投影 $key 报错：${message.take(400)}")
                Log.w(TAG, "projection error plugin=${residency.record.id} key=$key $message")
            }
        }
    }

    /** 常驻启动返回值。 */
    private data class ResidentStart(
        val keys: List<String>,
        val subscriptions: List<String>,
        val streams: List<String>,
        val shells: List<String>,
        val agents: List<String>,
        val storages: List<String>,
        val prompts: List<String>,
        val capabilities: List<String>,
        val cleanups: Int,
        val error: String,
    )

    /** 解析常驻启动返回值；坏 JSON 返回 null。 */
    private fun parseResidentStart(outcome: JsOutcome): ResidentStart? {
        val text = decodeJsonString(outcome.valueJson) ?: return null
        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val keys = runCatching {
            obj["keys"]?.jsonArray?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
        }.getOrDefault(emptyList())
        val subscriptions = runCatching {
            obj["subscriptions"]?.jsonArray?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
        }.getOrDefault(emptyList())
        val streams = runCatching {
            obj["streams"]?.jsonArray?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
        }.getOrDefault(emptyList())
        val shells = runCatching {
            obj["shells"]?.jsonArray?.mapNotNull { element ->
                (element as? JsonObject)?.get("id")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            }.orEmpty()
        }.getOrDefault(emptyList())
        val agents = runCatching {
            obj["agents"]?.jsonArray?.mapNotNull { element ->
                (element as? JsonObject)?.get("id")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            }.orEmpty()
        }.getOrDefault(emptyList())
        val storages = runCatching {
            obj["storages"]?.jsonArray?.mapNotNull { element ->
                (element as? JsonObject)?.get("id")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            }.orEmpty()
        }.getOrDefault(emptyList())
        val prompts = runCatching {
            obj["prompts"]?.jsonArray?.mapNotNull { element ->
                (element as? JsonObject)?.get("id")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            }.orEmpty()
        }.getOrDefault(emptyList())
        val capabilities = runCatching {
            obj["capabilities"]?.jsonArray?.mapNotNull { element ->
                (element as? JsonObject)?.get("id")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            }.orEmpty()
        }.getOrDefault(emptyList())
        val cleanups = runCatching {
            obj["cleanups"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0
        }.getOrDefault(0)
        val errors = runCatching {
            obj["errors"]?.jsonArray?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
        }.getOrDefault(emptyList())
        return ResidentStart(
            keys,
            subscriptions,
            streams,
            shells,
            agents,
            storages,
            prompts,
            capabilities,
            cleanups,
            errors.joinToString("\n").take(800),
        )
    }

    /** 启动脚本返回的是「JSON 编码过的 JSON 字符串」：解两层。 */
    private fun decodeJsonString(valueJson: String?): String? {
        val element = runCatching { Json.parseToJsonElement(valueJson ?: "null") }.getOrNull() ?: return null
        return runCatching { element.jsonPrimitive.content }.getOrNull()
    }

    private fun addError(pluginId: String, message: String) {
        recentErrors.addLast("[${System.currentTimeMillis()}] $pluginId: $message")
        while (recentErrors.size > 40) recentErrors.removeFirst()
    }

    companion object {
        private const val TAG = "DshProjection"
        private const val RESIDENT_START_TIMEOUT_MS = 20_000L
        private const val DISPATCH_TIMEOUT_MS = 5_000L
        /** 插件 stream 回调可能很慢（等上游/算法）；给足 3 分钟，超时由 dispatch 中断。 */
        private const val PROVIDER_TIMEOUT_MS = 180_000L
        /** M84-N：checkpoint 落盘节流（同一插件两次快照至少隔这么久）。 */
        private const val CHECKPOINT_INTERVAL_MS = 1_200L
    }
}
