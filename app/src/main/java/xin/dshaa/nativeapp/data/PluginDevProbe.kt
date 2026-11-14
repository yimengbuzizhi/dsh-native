package xin.dshaa.nativeapp.data

import kotlinx.serialization.json.*
import xin.dshaa.core.js.JsHost
import xin.dshaa.core.js.JsOutcome
import xin.dshaa.core.js.JsStatus
import xin.dshaa.core.js.QuickJsSession
import xin.dshaa.core.plugins.PluginModule
import xin.dshaa.core.plugins.PluginPermissions
import xin.dshaa.core.plugins.PluginRecord
import xin.dshaa.core.plugins.PluginSchema
import xin.dshaa.core.plugins.PluginServiceHost
import xin.dshaa.core.plugins.SurfaceNode
import xin.dshaa.core.plugins.SurfaceSpec
import xin.dshaa.core.tools.ToolResult
import java.io.File
import java.util.concurrent.Executors

/**
 * Disposable, offscreen execution of the installed module's real apply + shell dispatch path.
 * Blocking: call on Dispatchers.IO. No active shell, AppVM, platform bridge or persistent registry
 * is referenced. Services start empty and remain local; every external capability is denied.
 * Commands require the installed record's UI grant and are simulated, never performed.
 */
internal object PluginDevProbe {
    private const val MAX_SOURCE_BYTES = 2L * 1024 * 1024
    internal const val MAX_REPLY_CHARS = 512 * 1024
    private const val MAX_CALLS = 32
    private const val PREVIEW_CHARS = 1_000

    fun testShell(
        record: PluginRecord,
        workspace: File,
        shellId: String?,
        action: String?,
        value: JsonObject,
    ): ToolResult = testShellWithRuntime(record, workspace, shellId, action, value) { host ->
        val runtime = QuickJsSession(host)
        object : ProbeRuntime {
            override fun start(script: String, filename: String, timeoutMs: Long) = runtime.start(script, filename, timeoutMs)
            override fun dispatch(method: String, payload: String, timeoutMs: Long) = runtime.dispatch(method, payload, timeoutMs)
            override fun complete(callId: Int, ok: Boolean, payload: String) = runtime.complete(callId, ok, payload)
            override fun close() = runtime.close()
        }
    }

    /** Test seam for lifecycle/host policy; production always uses the real QuickJsSession above. */
    internal interface ProbeRuntime : java.io.Closeable {
        fun start(script: String, filename: String, timeoutMs: Long): JsOutcome
        fun dispatch(method: String, payload: String, timeoutMs: Long): JsOutcome
        fun complete(callId: Int, ok: Boolean, payload: String)
    }

    internal fun testShellWithRuntime(
        record: PluginRecord, workspace: File, shellId: String?, action: String?, value: JsonObject,
        createRuntime: (JsHost) -> ProbeRuntime,
    ): ToolResult {
        val checks = mutableListOf<JsonObject>()
        val commands = mutableListOf<JsonObject>()
        val denied = mutableListOf<JsonObject>()
        var commandCount = 0
        var deniedCount = 0
        var phase = "apply"
        var selectedId: String? = null
        var backend: String? = null
        var failure: String? = null
        var registered: List<String> = emptyList()
        val serviceHost = PluginServiceHost() // No live plugin services or host objects.
        val grants = PluginPermissions.sanitizeGrants(
            PluginPermissions.parseAll(record.permissions), record.grantedPermissions,
        )
        fun reject(method: String): String {
            deniedCount++
            if (denied.size < MAX_CALLS) denied += buildJsonObject {
                put("phase", phase); put("method", method.take(120))
            }
            return buildJsonObject {
                put("ok", false); put("code", "PROBE_ISOLATED")
                put("error", "离屏探针未提供此宿主能力：${method.take(120)}")
            }.toString()
        }
        val completions = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "plugin-dev-probe-host").apply { isDaemon = true }
        }
        var session: ProbeRuntime? = null
        try {
            require(value.toString().length <= 32_768) { "action value 太大（>32768 字符）" }
            require(action == null || SurfaceSpec.validAction(action)) { "action id 不合法" }
            val root = File(record.path).canonicalFile
            val entry = File(root, record.main).canonicalFile
            require(entry.toPath().startsWith(root.toPath()) && entry != root && entry.isFile) { "插件入口不存在或越出安装目录" }
            require(entry.length() <= MAX_SOURCE_BYTES) { "插件入口太大（>2MiB）" }
            val module = PluginModule.transform(entry.readText(Charsets.UTF_8))
            val script = PluginModule.residentScript(
                module, workspace.absolutePath,
                PluginSchema.effectiveText(record.configSchema, record.config, record.configDefaults),
            )
            val runtime = createRuntime(object : JsHost {
                override fun callSync(method: String, payloadJson: String): String? {
                    if (method.startsWith("plugin.services.")) return serviceHost.callSync(method, payloadJson)
                    if (method == "log" || method == "plugin.log") return """{"ok":true,"isolated":true}"""
                    if (method != "surface.command") return reject(method)
                    commandCount++
                    val payload = runCatching { Json.parseToJsonElement(payloadJson) as? JsonObject }.getOrNull()
                    val command = payload?.string("command")?.trim().orEmpty()
                    val allowed = PluginPermissions.UI in grants && command in SurfaceSpec.COMMANDS
                    if (commands.size < MAX_CALLS) commands += buildJsonObject {
                        put("phase", phase); put("command", command.take(120))
                        put("argsPreview", (payload?.get("args")?.toString() ?: "{}").take(PREVIEW_CHARS))
                        put("argsTruncated", (payload?.get("args")?.toString()?.length ?: 0) > PREVIEW_CHARS)
                        put("simulated", allowed); put("executed", false)
                    }
                    return buildJsonObject {
                        put("ok", allowed); put("isolated", true); put("simulated", allowed); put("executed", false)
                        if (!allowed) {
                            put("code", if (PluginPermissions.UI !in grants) "PERMISSION_DENIED" else "UNKNOWN_COMMAND")
                            put("error", "缺少已授权的 ui 权限或命令不在 Surface 白名单")
                        }
                    }.toString()
                }

                override fun callAsync(method: String, payloadJson: String, callId: Int) {
                    // Complete off the JS thread, with no I/O/platform operation behind the Promise.
                    reject(method)
                    completions.execute {
                        session?.complete(callId, false, buildJsonObject {
                            put("message", "离屏探针禁止异步宿主能力：${method.take(120)}")
                            put("code", "PROBE_ISOLATED"); put("fatal", true)
                        }.toString())
                    }
                }
            })
            session = runtime
            val started = runtime.start(script, entry.name, 10_000)
            require(started.status == JsStatus.COMPLETED) { started.error ?: "插件 apply ${started.status}" }
            require((started.valueJson?.length ?: 0) <= MAX_REPLY_CHARS) { "插件 apply 返回值太大" }
            val start = decodeObject(started.valueJson) ?: error("插件 apply 没有返回 JSON 对象")
            require(start["ok"]?.jsonPrimitive?.booleanOrNull == true) { start.string("error") ?: "插件 apply 失败" }
            val errors = (start["errors"] as? JsonArray).orEmpty()
            require(errors.isEmpty()) { errors.take(8).joinToString("\n").take(2_000) }
            // Parse freshly registered metadata; the installed record's cached shells may be stale.
            val shells = PluginModule.parseDryRun(start.toString()).shells
            registered = shells.take(64).map { it.id }
            val selected = if (shellId != null) {
                shells.firstOrNull { it.id == shellId } ?: error("apply 没有注册指定壳：${shellId.take(120)}")
            } else {
                require(shells.size == 1) { "apply 注册了 ${shells.size} 个壳，请指定 shellId" }
                shells.single()
            }
            selectedId = selected.id
            backend = selected.backend
            val state = initialState()
            for (method in listOf("shell.mount", "shell.state") + if (action != null) listOf("shell.action") else emptyList()) {
                phase = method
                val payload = buildJsonObject {
                    put("id", selected.id); put("state", state)
                    if (method == "shell.action") { put("action", action); put("value", value) }
                }
                val outcome = runtime.dispatch(method, payload.toString(), if (method == "shell.mount") 10_000 else 3_000)
                checks += validateReply(method, selected.backend, outcome)
                if (outcome.status == JsStatus.INTERRUPTED) break
            }
        } catch (t: Exception) {
            failure = (t.message ?: t.javaClass.simpleName).take(2_000)
        } finally {
            phase = "cleanup"
            // Real cleanup handlers may run here, under the same isolated host policy.
            try {
                session?.close()
            } catch (t: Exception) {
                if (failure == null) failure = "探针清理失败：${t.message ?: t.javaClass.simpleName}".take(2_000)
            } finally {
                completions.shutdownNow()
            }
        }
        val ok = failure == null && checks.size == (if (action == null) 2 else 3) &&
            checks.all { it["valid"]?.jsonPrimitive?.booleanOrNull == true }
        val report = buildJsonObject {
            put("ok", ok); put("isolated", true); put("pluginId", record.id.take(160))
            put("shellId", selectedId); put("backend", backend)
            put("mode", "offscreen-disposable-runtime")
            put("stateSource", "synthetic-empty-session")
            put("servicesSource", "empty-local-snapshots")
            put("rendered", false); put("commandsExecuted", 0)
            put("registeredShells", JsonArray(registered.map(::JsonPrimitive)))
            put("checks", JsonArray(checks)); put("commands", JsonArray(commands))
            put("commandCount", commandCount); put("commandsTruncated", commandCount > commands.size)
            put("deniedHostCalls", JsonArray(denied)); put("deniedHostCallCount", deniedCount)
            put("deniedHostCallsTruncated", deniedCount > denied.size)
            failure?.let { put("error", it) }
        }.toString()
        return ToolResult(report, isError = !ok)
    }

    /** Validates data as the real shell host does; this never creates a renderer/WebView. */
    internal fun validateReply(method: String, backend: String, outcome: JsOutcome): JsonObject {
        var error: String? = null
        var nodes = 0
        var preview: String? = null
        var replyChars = outcome.valueJson?.length ?: 0
        if (outcome.status != JsStatus.COMPLETED) {
            error = outcome.error ?: outcome.status.name
        } else if (replyChars > MAX_REPLY_CHARS) {
            error = "壳返回值太大（>${MAX_REPLY_CHARS} 字符）"
        } else {
            val obj = decodeObject(outcome.valueJson)
            if (obj == null) error = "壳返回值不是 JSON 对象"
            else if (obj["ok"]?.let { (it as? JsonPrimitive)?.booleanOrNull } != true) {
                error = obj.string("error") ?: "壳 dispatch 失败"
            } else if (backend == "webview") {
                val html = obj["html"] as? JsonPrimitive
                if (html == null || !html.isString || html.content.isBlank()) error = "webview 壳没有返回非空 html"
                else { preview = html.content.take(PREVIEW_CHARS); replyChars = html.content.length }
            } else {
                val tree = obj["tree"]
                val parsed = tree?.let(SurfaceSpec::parseElement)
                if (parsed?.ok != true) error = parsed?.error ?: "壳没有返回 tree"
                else {
                    fun count(node: SurfaceNode): Int = 1 + node.children.sumOf(::count) + (node.item?.let(::count) ?: 0)
                    nodes = count(parsed.tree!!.root)
                    preview = tree.toString().take(PREVIEW_CHARS)
                }
            }
        }
        return buildJsonObject {
            put("method", method); put("status", outcome.status.name); put("valid", error == null)
            put("validation", if (backend == "webview") "nonempty-html-data-only" else SurfaceSpec.VERSION)
            put("replyChars", replyChars); put("rendered", false)
            if (nodes > 0) put("nodes", nodes)
            preview?.let { put("preview", it); put("previewTruncated", replyChars > PREVIEW_CHARS) }
            error?.let { put("error", it.take(2_000)) }
        }
    }

    private fun decodeObject(raw: String?): JsonObject? {
        var value = runCatching { Json.parseToJsonElement(raw ?: "null") }.getOrNull()
        // residentScript returns a JSON string; dispatch returns a JSON object.
        repeat(2) {
            if (value is JsonObject) return value as JsonObject
            val primitive = value as? JsonPrimitive ?: return null
            if (!primitive.isString) return null
            value = runCatching { Json.parseToJsonElement(primitive.content) }.getOrNull()
        }
        return value as? JsonObject
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun initialState(): JsonObject = buildJsonObject {
        put("chat", buildJsonObject {
            put("sessionId", "probe"); put("title", "离屏测试"); put("draft", ""); put("status", "")
            put("streaming", false); put("readOnly", true); put("error", ""); put("usage", "")
            listOf("messages", "pendingImages", "pendingFiles").forEach { put(it, JsonArray(emptyList())) }
            put("hasAttachments", false); put("attachError", ""); put("view", JsonObject(emptyMap()))
            put("context", buildJsonObject { put("promptTokens", 0); put("completionTokens", 0); put("window", 0); put("ratio", 0); put("label", "") })
            put("pendingApproval", buildJsonObject { put("active", false); put("toolName", ""); put("summary", ""); put("arguments", "") })
            put("pendingQuestions", buildJsonObject { put("active", false); put("count", 0); put("header", ""); put("items", JsonArray(emptyList())) })
            put("goal", buildJsonObject { put("active", false); put("objective", ""); put("phase", ""); put("rounds", 0); put("maxRounds", 0); put("activation", ""); put("blockedReason", ""); put("label", "") })
            put("turn", buildJsonObject { put("active", false); put("step", "") })
        })
        put("sessions", JsonArray(emptyList()))
        put("settings", buildJsonObject { put("model", "probe"); put("assistantName", "离屏测试"); put("themeMode", "light"); put("accent", "default"); put("planActive", false) })
        put("theme", buildJsonObject { put("mode", "light"); put("accent", "default") })
        put("capabilities", buildJsonObject {
            put("surface", SurfaceSpec.VERSION); put("isolated", true)
            put("commands", JsonArray(SurfaceSpec.COMMANDS.map(::JsonPrimitive)))
            put("bindRoots", JsonArray(SurfaceSpec.BIND_ROOTS.map(::JsonPrimitive)))
        })
    }
}
