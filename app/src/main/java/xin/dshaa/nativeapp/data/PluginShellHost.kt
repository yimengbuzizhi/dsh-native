package xin.dshaa.nativeapp.data

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xin.dshaa.core.js.JsOutcome
import xin.dshaa.core.js.JsStatus
import xin.dshaa.core.plugins.SurfaceParseResult
import xin.dshaa.core.plugins.SurfaceSpec
import xin.dshaa.core.plugins.SurfaceTree
import xin.dshaa.nativeapp.ui.surface.SurfaceRow

/**
 * M85-A：壳宿主。
 *
 * 一个整壳完全由插件提供：宿主只把 state 交给插件、拿回一棵 Surface 树交给渲染器。
 * 超时/失败都按「壳坏了」处理：mount 10s、dispatch 3s，连续 3 次失败回退原生壳。
 *
 * 性能纪律：state 只在**结构变化**时 dispatch（消息条数/会话切换/回合状态）；
 * 流式 token 由 Surface 的 `bind` 直接读宿主 state，不跨 JS。
 */
class PluginShellHost(
    private val scope: CoroutineScope,
    private val dispatch: (pluginId: String, method: String, payloadJson: String, timeoutMs: Long) -> JsOutcome?,
    /** 常驻运行时是否已经起来（不要用 dispatch 探活：会排在 start 后面并中断启动）。 */
    private val residentReady: (pluginId: String) -> Boolean,
    /** 在主线程构造 state JSON（读 Compose 状态）。 */
    private val stateProvider: () -> String,
    private val onFallback: (reason: String) -> Unit,
) {

    data class Target(
        val pluginId: String,
        val shellId: String,
        val label: String,
        val pluginName: String,
        /** M85-F：`surface`（默认）或 `webview`。 */
        val backend: String = "surface",
    )

    var tree by mutableStateOf<SurfaceTree?>(null)
        private set

    /** M85-F：webview 后端的整屏 HTML 片段（每次 shell.* 返回的最新一份）。 */
    var html by mutableStateOf<String?>(null)
        private set

    /** Set only after the current HTML has crossed the WebView visual state callback. */
    var webContentReady by mutableStateOf(false)

    var title by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var mounting by mutableStateOf(false)
        private set
    var target by mutableStateOf<Target?>(null)
        private set

    private val mutex = Mutex()
    private var stateJob: Job? = null
    private var failures = 0
    private var generation = 0

    fun start(next: Target) {
        if (target == next && (mounting || tree != null || html != null)) return
        // 只有换壳时才清失败计数；同一目标的重试要累计，否则 mount 连续失败会无限重试。
        val changed = target != next
        target = next
        tree = null
        html = null
        webContentReady = false
        title = null
        error = null
        if (changed) failures = 0
        mounting = true
        val myGeneration = ++generation
        stateJob?.cancel()
        scope.launch {
            val ready = awaitResident(next.pluginId)
            if (myGeneration != generation) return@launch
            if (!ready) {
                mounting = false
                fail("常驻运行时没有起来（10s）")
                return@launch
            }
            val result = send(next, "shell.mount", 10_000, buildJsonObject { })
            if (myGeneration != generation) return@launch
            mounting = false
            if (result != null) applied(result) else fail("mount 超时/运行时消失")
        }
    }

    fun reset() {
        generation++
        stateJob?.cancel()
        target = null
        tree = null
        html = null
        webContentReady = false
        title = null
        error = null
        mounting = false
        failures = 0
    }

    /** 结构变化（新消息/切会话/回合状态）→ 让壳重算一次树；token 不走这里。 */
    fun requestState() {
        val current = target ?: return
        if ((tree == null && html == null) || mounting) return
        stateJob?.cancel()
        stateJob = scope.launch {
            delay(60)
            val myGeneration = generation
            val result = send(current, "shell.state", 3_000, buildJsonObject { }) ?: return@launch
            if (myGeneration == generation) applied(result)
        }
    }

    /** 点击/输入/提交。payload 带行号与行 id，壳可用 `state.sessions[index]` 找目标。 */
    fun action(actionId: String, row: SurfaceRow?, index: Int) {
        val current = target ?: return
        if (tree == null && html == null) return
        Log.i(TAG, "action=$actionId row=${row?.id} index=$index")
        scope.launch {
            val result = send(
                current,
                "shell.action",
                3_000,
                buildJsonObject {
                    put("action", actionId)
                    put(
                        "value",
                        buildJsonObject {
                            put("index", index)
                            row?.id?.let { put("itemId", it) }
                            // M85-B3：消息长按「引用」需要原文；附件块已折成 [附件: name] 摘要，
                            // 不让壳去猜（payload 只在动作时传一次，不参与流式轮询）。
                            if (row is SurfaceRow.Message) {
                                put(
                                    "text",
                                    row.text.ifBlank {
                                        row.attachments.joinToString("\n") { "[附件: ${it.name}]" }
                                    },
                                )
                            }
                        },
                    )
                },
            ) ?: return@launch
            applied(result)
        }
    }

    /**
     * M85-F：webview 后端的通用动作。WebView 里的按钮/输入框把动作 id + 任意 payload
     * 交给宿主，宿主按与 Surface 相同的 `shell.action` 协议 dispatch 给插件。
     */
    fun actionRaw(actionId: String, payloadJson: String?) {
        val current = target ?: return
        if (html == null) return
        Log.i(TAG, "raw action=$actionId payload=${payloadJson?.length ?: 0}b")
        scope.launch {
            val value = runCatching {
                Json.parseToJsonElement(payloadJson?.takeIf { it.isNotBlank() } ?: "{}")
            }.getOrDefault(buildJsonObject { })
            val result = send(
                current,
                "shell.action",
                3_000,
                buildJsonObject {
                    put("action", actionId)
                    put("value", value)
                },
            ) ?: return@launch
            applied(result)
        }
    }

    fun event(name: String) {
        val current = target ?: return
        if (tree == null && html == null) return
        scope.launch {
            val result = send(current, "shell.event", 3_000, buildJsonObject { put("event", name) }) ?: return@launch
            applied(result)
        }
    }

    private suspend fun send(
        current: Target,
        method: String,
        timeoutMs: Long,
        extra: JsonObject,
    ): ShellReply? = mutex.withLock {
        val state = withContext(Dispatchers.Main) { stateProvider() }
        if (target != current) return@withLock null
        val payload = buildJsonObject {
            put("id", current.shellId)
            put("state", runCatching { Json.parseToJsonElement(state) }.getOrDefault(buildJsonObject { }))
            extra.forEach { (key, value) -> put(key, value) }
        }
        val startedAt = System.currentTimeMillis()
        val outcome = withContext(Dispatchers.IO) {
            dispatch(current.pluginId, method, payload.toString(), timeoutMs)
        }
        Log.i(
            TAG,
            "dispatch=$method plugin=${current.pluginId} elapsed=${System.currentTimeMillis() - startedAt}ms " +
                "status=${outcome?.status} bytes=${outcome?.valueJson?.length ?: 0}",
        )
        if (outcome == null) {
            Log.w("DshShell", "shell dispatch=$method plugin=${current.pluginId} shell=${current.shellId} resident=missing")
            return@withLock null
        }
        if (outcome.status != JsStatus.COMPLETED) {
            Log.w("DshShell", "shell dispatch=$method status=${outcome.status} error=${outcome.error}")
            null
        } else {
            parseReply(outcome, current, method)
        }
    }

    private fun parseReply(outcome: JsOutcome, current: Target, method: String): ShellReply? {
        val obj = runCatching { Json.parseToJsonElement(outcome.valueJson ?: "null").jsonObject }.getOrNull()
        if (obj == null) return null
        val ok = runCatching { obj["ok"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() }.getOrNull() ?: false
        if (!ok) {
            Log.w("DshShell", "shell $method failed: ${obj["error"]?.toString()?.take(400)}")
            return null
        }
        val title = runCatching { obj["title"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        // M85-F：webview 壳返回整屏 HTML（{ok,html}）；surface 壳仍返回 Surface 树（{ok,tree}）。
        val html = runCatching { obj["html"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        if (current.backend == "webview") {
            if (html.isNullOrBlank()) {
                Log.w("DshShell", "shell $method webview 没有返回 html")
                return null
            }
            return ShellReply(ok = true, tree = null, html = html, title = title)
        }
        val treeJson = obj["tree"]?.toString() ?: return null
        val parsed = SurfaceSpec.parse(treeJson)
        if (!parsed.ok) return ShellReply(ok = false, error = parsed.error ?: "Surface 树无效")
        return ShellReply(ok = true, tree = parsed.tree, title = title ?: parsed.tree?.title)
    }

    private fun applied(result: ShellReply) {
        if (target == null) return
        if (result.ok) {
            if (target?.backend == "webview") {
                if (result.html == null) {
                    fail("webview 壳没有返回 html")
                    return
                }
                if (html != result.html) webContentReady = false
                html = result.html
                tree = null
                title = result.title
            } else {
                if (result.tree == null) {
                    fail("Surface 树为空")
                    return
                }
                tree = result.tree
                html = null
                title = result.title ?: result.tree.title
            }
            error = null
            failures = 0
        } else {
            fail(result.error ?: "界面返回无效")
        }
    }

    /** M85-F：壳返回值统一成 surface（tree）或 webview（html）。 */
    private data class ShellReply(
        val ok: Boolean,
        val tree: SurfaceTree? = null,
        val html: String? = null,
        val title: String? = null,
        val error: String? = null,
    )

    private fun fail(reason: String) {
        failures++
        Log.w("DshShell", "shell failure $failures: $reason target=${target?.pluginId}/${target?.shellId}")
        if (failures >= 3) {
            val name = target?.pluginName ?: target?.pluginId.orEmpty()
            reset()
            onFallback("$name 的界面连续失败：$reason")
            return
        }
        error = reason
        // 还有额度：让壳重新 mount 一次，避免一次抖动就整屏空白。
        val current = target ?: return
        scope.launch {
            delay(400)
            if (target == current && tree == null && html == null && !mounting) start(current)
        }
    }

    private suspend fun awaitResident(pluginId: String): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (withContext(Dispatchers.IO) { runCatching { residentReady(pluginId) }.getOrDefault(false) }) {
                return true
            }
            delay(100)
        }
        return false
    }

    companion object {
        private const val TAG = "DshShell"
    }
}
