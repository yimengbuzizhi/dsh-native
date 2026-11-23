package xin.dshaa.nativeapp.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import xin.dshaa.core.plugins.LoadingSpec
import xin.dshaa.core.plugins.PluginLoading
import xin.dshaa.core.plugins.PluginSchema
import xin.dshaa.core.protocol.ApiTool
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult
import xin.dshaa.core.tools.resolvePath
import java.io.File

/** App-owned tools: mutations rely on the caller's existing ToolExecutor approval policy. */
class PluginDevTools(
    private val store: PluginStore,
    private val onChanged: suspend () -> Unit,
    private val callTool: suspend (String, String, JsonObject, ToolContext) -> ToolResult = { _, _, _, _ -> ToolResult.err("Tool testing is not wired up") },
    private val testCapability: suspend (String, JsonObject) -> ToolResult = { _, _ -> ToolResult.err("Capability testing is not wired up") },
    /** 0.3.4：停用后清角色选择（与 UI 开关同一条路径）；参数是插件 id。 */
    private val onDisabled: suspend (String) -> Unit = {},
    /** 0.3.4：删除插件（共享实现：清角色选择 + 常驻运行时 + 依赖重算）；force 只给 UI 用。 */
    private val delete: suspend (String, Boolean) -> PluginStore.Result = { id, _ ->
        PluginStore.Result(false, "Delete channel is not wired up: $id")
    },
    /** M88-E：审计写入（action / target / detail）。 */
    private val audit: (String, String, String) -> Unit = { _, _, _ -> },
    /** M88-E：最近一次 AI 操作（插件列表用）。 */
    private val lastAiAction: (String) -> String? = { null },
    /** M88-E：选择角色（shell/loading/agent/storage/prompt/model）；复用 AppViewModel 的选择逻辑。 */
    private val selectRole: (String, String) -> Boolean = { _, _ -> false },
    /** M88-E：AI 刚授予权限（插件 id、`, ` 分隔的权限）；宿主据此显示「AI 已授予 … · 撤销」横幅。 */
    private val onAiGranted: (String, String) -> Unit = { _, _ -> },
    /** M88-I：插件中间件总开关的当前值（只读工具用；不进运行时上下文、不进 plugin_dev_list）。 */
    private val middlewareEnabled: () -> Boolean = { true },
    /** M88-I：改插件中间件总开关（复用 AppViewModel.setPluginMiddlewareEnabled，会重建链/钩子并即时生效）。 */
    private val setMiddlewareEnabled: (Boolean) -> Unit = {},
) {
    val tools: List<DshTool> = definitions.map { api -> object : DshTool {
        override val name = api.name
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            PluginSchema.validate(api.inputSchema, args)?.let { return ToolResult.err(it) }
            return try {
                executeTool(name, args, ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolResult.err(e.message ?: e.javaClass.simpleName)
            }
        }
    } }

    fun apiTools(): List<ApiTool> = definitions

    private suspend fun executeTool(name: String, args: JsonObject, ctx: ToolContext): ToolResult {
        fun string(key: String) = args[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun result(value: PluginStore.Result) = if (value.ok) ToolResult.ok(value.message) else ToolResult.err(value.message)
        when (name) {
            "plugin_dev_list" -> {
                val id = string("id")
                val records = withContext(Dispatchers.IO) { store.list() }
                if (id.isNotEmpty() && records.none { it.id == id }) return ToolResult.err("Plugin not found: $id")
                val matches = records.filter { id.isEmpty() || it.id == id }
                return ToolResult.ok(buildJsonObject {
                    put("total", matches.size)
                    put("truncated", matches.size > 100)
                    put("plugins", buildJsonArray {
                        matches.take(100).forEach { record -> add(buildJsonObject {
                            put("id", record.id); put("name", record.name); put("version", record.version)
                            put("enabled", record.enabled); put("path", record.path)
                            put("tools", JsonArray(record.tools.take(100).map(::JsonPrimitive)))
                            put("shells", buildJsonArray { record.shells.forEach { shell -> add(buildJsonObject {
                                put("id", shell.id); put("backend", shell.backend)
                            }) } })
                            put("loadings", loadingMetadata(record.id, record.loadings))
                            put("permissions", JsonArray(record.permissions.map(::JsonPrimitive)))
                            put("grantedPermissions", JsonArray(record.grantedPermissions.map(::JsonPrimitive)))
                            lastAiAction(record.id)?.let { put("lastAiAction", it) }
                            record.error?.let { put("error", it.take(4000)) }
                            record.configError?.let { put("configError", it.take(2000)) }
                            // Do not return config/service values: they can contain credentials/user data.
                        }) }
                    })
                }.toString())
            }
            "plugin_dev_test" -> {
                val tested = store.testPath(resolvePath(string("path"), ctx))
                val text = buildJsonObject {
                    put("ok", tested.ok); put("message", tested.message.take(8000))
                    tested.id?.let { put("id", it) }
                    tested.run?.let { run ->
                        put("tools", JsonArray(run.tools.take(100).map { JsonPrimitive(it.name) }))
                        put("shells", buildJsonArray { run.shells.forEach { shell -> add(buildJsonObject {
                            put("id", shell.id); put("backend", shell.backend)
                        }) } })
                        put("loadings", loadingMetadata(tested.id, run.loadings))
                        put("unsupported", JsonArray(run.unsupported.take(50).map(::JsonPrimitive)))
                        put("missingServices", JsonArray(run.missingServices.map(::JsonPrimitive)))
                    }
                }.toString()
                return if (tested.ok) ToolResult.ok(text) else ToolResult.err(text)
            }
            "plugin_dev_install" -> {
                val path = resolvePath(string("path"), ctx)
                val installed = if (path.isDirectory) store.installDirectory(path) else store.installFile(path)
                if (installed.ok) onChanged()
                return result(installed)
            }
            "plugin_dev_enable" -> {
                val id = string("id")
                val enabled = args.getValue("enabled").jsonPrimitive.boolean
                val changed = withContext(Dispatchers.IO) { store.setEnabled(id, enabled) }
                if (!changed) return ToolResult.err("Plugin not found: $id")
                // 0.3.4（用户第 6 条）：停用正在使用的插件时静默回退内置并清选择，不留红字、不拦发送。
                if (!enabled) onDisabled(id)
                onChanged()
                return ToolResult.ok(
                    "Plugin $id: " + (if (enabled) "enabled" else "disabled (role selections pointing to it have fallen back to built-ins)"),
                )
            }
            // 0.3.4（用户第 7 条闸门 A）：默认拒绝删除正在使用/运行中的插件并返回原因；
            // 模型先 plugin_dev_enable {enabled:false} 停用（会回退内置），停用后再删即可。
            // UI 删除走确认弹窗，使用 AppViewModel.deletePlugin 的 force 路径，不经过这里。
            "plugin_dev_delete" -> {
                val id = string("id")
                val deleted = delete(id, false)
                return if (deleted.ok) ToolResult.ok(deleted.message) else ToolResult.err(deleted.message)
            }
            "plugin_dev_reload" -> {
                val id = string("id")
                val record = withContext(Dispatchers.IO) { store.list().firstOrNull { it.id == id } }
                    ?: return ToolResult.err("Plugin not found: $id")
                val tested = store.testPath(File(record.path))
                if (!tested.ok) return ToolResult.err(tested.message)
                if (tested.id != id) return ToolResult.err("Manifest id has changed; reinstall the plugin")
                val reloaded = withContext(Dispatchers.IO) { store.reload(id) }
                // reload can update error/schema metadata even on failure.
                onChanged()
                return result(reloaded)
            }
            "plugin_dev_call" -> {
                return callTool(string("id"), string("tool"), args["args"]?.jsonObject ?: buildJsonObject {}, ctx)
            }
            "plugin_dev_test_capability" -> {
                return testCapability(string("capability"), args["args"]?.jsonObject ?: buildJsonObject {})
            }
            "plugin_dev_test_shell" -> {
                val record = withContext(Dispatchers.IO) { store.list().firstOrNull { it.id == string("id") } }
                    ?: return ToolResult.err("Plugin not found: ${string("id")}")
                return withContext(Dispatchers.IO) {
                    PluginDevProbe.testShell(record, ctx.workspace, string("shell").ifEmpty { null },
                        string("action").ifEmpty { null }, args["value"]?.jsonObject ?: buildJsonObject {})
                }
            }
            "plugin_dev_scaffold" -> {
                val path = resolvePath(string("path"), ctx)
                val template = string("template").ifEmpty { "tool" }
                withContext(Dispatchers.IO) { PluginDevScaffold.create(path, string("id"), template) }
                return ToolResult.ok("Created $template plugin at ${path.absolutePath}; run plugin_dev_test to verify")
            }
            // ---- M88-E（docs/183 §5）：AI 全控制插件（授予/撤销/配置/角色） ----
            "plugin_dev_grant" -> {
                val id = string("id")
                val record = withContext(Dispatchers.IO) { store.list().firstOrNull { it.id == id } }
                    ?: return ToolResult.err("Plugin not found: $id")
                val all = args["all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
                val requested = requestedPermissions(args)
                if (!all && requested.isEmpty()) {
                    return ToolResult.err("plugin_dev_grant requires a permissions array, or all:true to grant every grantable permission declared in the manifest")
                }
                val toGrant = if (all) {
                    xin.dshaa.core.plugins.PluginPermissions
                        .approvalNeeded(xin.dshaa.core.plugins.PluginPermissions.parseAll(record.permissions))
                        .map { it.raw }
                } else {
                    requested
                }
                val before = record.grantedPermissions.toSet()
                val granted = withContext(Dispatchers.IO) { store.grantMany(id, toGrant) }
                if (!granted.ok) return ToolResult.err(granted.message)
                onChanged()
                val added = withContext(Dispatchers.IO) {
                    (store.list().firstOrNull { it.id == id }?.grantedPermissions?.toSet() ?: before) - before
                }
                val detail = (if (added.isEmpty()) toGrant else added.toList()).joinToString(", ")
                audit(PluginAuditLog.ACTION_GRANT, id, detail)
                if (added.isNotEmpty()) onAiGranted(id, added.joinToString(", "))
                return ToolResult.ok(
                    buildJsonObject {
                        put("ok", true)
                        put("id", id)
                        put("granted", JsonArray(toGrant.map(::JsonPrimitive)))
                        put("added", JsonArray(added.map(::JsonPrimitive)))
                        put("message", "Granted ${record.name}: ${detail.ifEmpty { "(no new permissions)" }}")
                    }.toString(),
                )
            }
            "plugin_dev_revoke" -> {
                val id = string("id")
                val record = withContext(Dispatchers.IO) { store.list().firstOrNull { it.id == id } }
                    ?: return ToolResult.err("Plugin not found: $id")
                val all = args["all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
                val requested = requestedPermissions(args)
                if (!all && requested.isEmpty()) {
                    return ToolResult.err("plugin_dev_revoke requires a permissions array, or all:true to revoke every granted permission")
                }
                val toRevoke = if (all) record.grantedPermissions else requested
                val before = record.grantedPermissions.toSet()
                val revoked = withContext(Dispatchers.IO) { store.revokeGrants(id, toRevoke) }
                if (!revoked.ok) return ToolResult.err(revoked.message)
                onChanged()
                val removed = before - withContext(Dispatchers.IO) {
                    store.list().firstOrNull { it.id == id }?.grantedPermissions?.toSet().orEmpty()
                }
                val detail = if (removed.isEmpty()) toRevoke.joinToString(", ") else removed.joinToString(", ")
                audit(
                    if (all) PluginAuditLog.ACTION_REVOKE_ALL else PluginAuditLog.ACTION_REVOKE,
                    id,
                    detail,
                )
                return ToolResult.ok(
                    buildJsonObject {
                        put("ok", true)
                        put("id", id)
                        put("removed", JsonArray(removed.map(::JsonPrimitive)))
                        put("message", "Revoked ${record.name}: ${detail.ifEmpty { "(no permissions to revoke)" }}")
                    }.toString(),
                )
            }
            "plugin_dev_config" -> {
                val id = string("id")
                val record = withContext(Dispatchers.IO) { store.list().firstOrNull { it.id == id } }
                    ?: return ToolResult.err("Plugin not found: $id")
                val reset = args["reset"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
                val config = args["config"]
                if (!reset && config != null && config !is JsonObject) {
                    return ToolResult.err("plugin_dev_config config must be a JSON object, or use reset:true to restore defaults")
                }
                if (!reset && config == null) {
                    return ToolResult.err("plugin_dev_config requires a config object, or use reset:true to restore defaults")
                }
                val text = if (reset) "" else config.toString()
                val saved = withContext(Dispatchers.IO) { store.setConfig(id, text) }
                if (!saved.ok) return ToolResult.err(saved.message)
                onChanged()
                audit(PluginAuditLog.ACTION_CONFIG, id, if (reset) "已恢复默认" else "已更新配置")
                return ToolResult.ok(
                    buildJsonObject {
                        put("ok", true)
                        put("id", id)
                        put("reset", reset)
                        put("message", saved.message)
                    }.toString(),
                )
            }
            "plugin_dev_role" -> {
                val role = string("role").trim().lowercase()
                val value = string("value").trim()
                roleError(role, value)?.let { return ToolResult.err(it) }
                if (!selectRole(role, value)) {
                    return ToolResult.err("Role selection failed: $role = $value (target does not exist or is unavailable; see the hint on the Plugins page)")
                }
                audit(PluginAuditLog.ACTION_ROLE, role, value)
                return ToolResult.ok(
                    buildJsonObject {
                        put("ok", true)
                        put("role", role)
                        put("value", value)
                        put("message", "Switched $role: $value")
                    }.toString(),
                )
            }
            // M88-I：读总开关状态（只读，无审批、不写审计）。
            "plugin_dev_middleware_state" -> {
                val enabled = middlewareEnabled()
                return ToolResult.ok(
                    buildJsonObject {
                        put("ok", true)
                        put("enabled", enabled)
                        put("state", if (enabled) "on" else "off")
                        put("message", middlewareStateText(enabled))
                    }.toString(),
                )
            }
            // M88-I：插件中间件总开关（全局旁路）。扩展点插件“没效果”时由 AI 打开；
            // 走全局审批档并写审计（docs/190 §3-F：不再有「AI 全控制插件」开关）。
            "plugin_dev_middleware" -> {
                val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull
                    ?: return ToolResult.err("plugin_dev_middleware requires enabled:true/false")
                setMiddlewareEnabled(enabled)
                audit(PluginAuditLog.ACTION_MIDDLEWARE, "middleware", if (enabled) "on" else "off")
                return ToolResult.ok(
                    buildJsonObject {
                        put("ok", true)
                        put("enabled", enabled)
                        put("message", middlewareStateText(enabled) + " (effective immediately)")
                    }.toString(),
                )
            }
            else -> return ToolResult.err("Unknown plugin development tool")
        }
    }

    /** `permissions` 数组里的原始字符串（非字符串项丢弃）。 */
    private fun requestedPermissions(args: JsonObject): List<String> =
        (args["permissions"] as? JsonArray)
            ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    companion object {
        /** M88-E：`plugin_dev_role` 的 role/value 校验（纯函数，JVM 可测）；null = 合法。 */
        fun roleError(role: String, value: String): String? = when {
            role !in ROLE_NAMES -> "Unknown role: $role (available: ${ROLE_NAMES.joinToString(" / ")})"
            value.isBlank() -> "plugin_dev_role requires value: for built-in implementations write native (builtin.loading.native for loading pages, deepseek/openai for models); for third-party plugins write the plugin id or pluginId#implementationId"
            else -> null
        }

        /** M88-E：可用于 `plugin_dev_role` 的六个角色。 */
        val ROLE_NAMES = setOf("shell", "loading", "agent", "storage", "prompt", "model")

        /** M88-I：中间件总开关的可读状态（plugin_dev_middleware(_state) 的工具返回共用）。 */
        fun middlewareStateText(enabled: Boolean): String =
            if (enabled) {
                "Plugin middleware master switch: on"
            } else {
                "Plugin middleware master switch: off (tool/request/storage/event hooks are all bypassed, so extension-point plugins will not take effect; " +
                    "enable it at the top of Plugins page → \"Extension points\" → \"Plugin middleware master switch\")"
            }

        /** 全部 `plugin_dev_*` 工具名（含 M88-I 的中间件总开关）。 */
        val toolNames: Set<String> get() = definitions.map { it.name }.toSet()

        /** Only these may enter AppViewModel's read-only whitelist; all other names require normal write approval. */
        val readOnlyNames = setOf("plugin_dev_list", "plugin_dev_test", "plugin_dev_middleware_state")
        val writeNames = setOf("plugin_dev_install", "plugin_dev_enable", "plugin_dev_delete", "plugin_dev_reload",
            "plugin_dev_scaffold", "plugin_dev_call", "plugin_dev_test_shell", "plugin_dev_test_capability",
            // M88-E：AI 全控制插件（写类，沿用审批策略；权限确认不再单独弹窗，靠审计/撤销兜底）。
            "plugin_dev_grant", "plugin_dev_revoke", "plugin_dev_config", "plugin_dev_role",
            // M88-I：全局旁路开关（写类；关掉会让所有扩展点插件失效，所以必须走审批）。
            "plugin_dev_middleware")

        /** Registration metadata only; no static text/tree, config, services or active-selection claim. */
        internal fun loadingMetadata(pluginId: String?, loadings: List<PluginLoading>): JsonArray = buildJsonArray {
            loadings.take(LoadingSpec.MAX_DECLARATIONS).forEach { loading -> add(buildJsonObject {
                put("id", loading.id)
                put("label", loading.label)
                put("protocol", LoadingSpec.VERSION)
                pluginId?.let { put("selectionValue", "$it#${loading.id}") }
            }) }
        }

        private fun schema(properties: String, required: String = "[]"): JsonObject =
            Json.parseToJsonElement("""{"type":"object","properties":$properties,"required":$required,"additionalProperties":false}""").jsonObject

        internal val definitions = listOf(
            ApiTool("plugin_dev_list", "List installed plugins with versions, enabled state, permissions, and registered tool/shell/loading-page metadata; optionally filter by id. Registration does not mean selection.", schema("""{"id":{"type":"string","minLength":1}}""")),
            ApiTool("plugin_dev_test", "Dry-run apply for a directory or zip in isolation, returning registered tools/shells/loading pages and errors; does not install, execute tools, or verify the cold-start UI.", schema("""{"path":{"type":"string","minLength":1,"description":"Plugin directory or zip path, resolved relative to the current directory."}}""", """["path"]""")),
            ApiTool("plugin_dev_install", "Install a plugin directory or zip after validation and dry-run pass; keeps compatible config, rolls back to the old package on failure, and grants no dangerous permissions or selects no shell.", schema("""{"path":{"type":"string","minLength":1}}""", """["path"]""")),
            ApiTool("plugin_dev_enable", "Enable or disable an installed plugin and refresh its registrations without granting permissions or selecting a shell; disabling falls role selections back to built-ins automatically.", schema("""{"id":{"type":"string","minLength":1},"enabled":{"type":"boolean"}}""", """["id","enabled"]""")),
            ApiTool("plugin_dev_delete", "Delete an installed plugin and clear role selections pointing to it; refused while the plugin is in use/running, so disable it with plugin_dev_enable first.", schema("""{"id":{"type":"string","minLength":1}}""", """["id"]""")),
            ApiTool("plugin_dev_reload", "Validate an installed plugin, then reload its manifest and registrations to refresh the existing plugin.", schema("""{"id":{"type":"string","minLength":1}}""", """["id"]""")),
            ApiTool("plugin_dev_call", "Execute one tool of an enabled plugin, reusing its permissions, argument validation, and timeout; may have real side effects.", schema("""{"id":{"type":"string","minLength":1},"tool":{"type":"string","minLength":1},"args":{"type":"object"}}""", """["id","tool"]""")),
            ApiTool("plugin_dev_test_shell", "Mount a shell offscreen in an isolated runtime and optionally test actions; host commands are only recorded, the verified tree or HTML is returned, and the current UI is not switched.", schema("""{"id":{"type":"string","minLength":1},"shell":{"type":"string"},"action":{"type":"string"},"value":{"type":"object"}}""", """["id"]""")),
            ApiTool("plugin_dev_test_capability", "Invoke a granted capability and return the result, reusing the provider's permissions and timeout; may have real side effects.", schema("""{"capability":{"type":"string","minLength":1},"args":{"type":"object"}}""", """["capability"]""")),
            ApiTool("plugin_dev_scaffold", "Generate a tool, Surface/WebView shell, or loading/v1 static loading page template in a new directory; never overwrites an existing directory.", schema("""{"path":{"type":"string","minLength":1},"id":{"type":"string","minLength":1,"maxLength":80},"template":{"type":"string","enum":["tool","surface-shell","webview-shell","loading"],"default":"tool"}}""", """["path","id"]""")),
            // M88-E：AI 全控制插件（docs/190 §3-F：总开关已永久删除，这四个工具永远可调，仍走全局审批 + 审计）。
            ApiTool("plugin_dev_grant", "Grant permissions declared in an installed plugin's manifest (undeclared or unimplemented ones are dropped). Pass permissions one by one, or all:true to grant every grantable permission; does not change system permissions.", schema("""{"id":{"type":"string","minLength":1},"permissions":{"type":"array","items":{"type":"string"}},"all":{"type":"boolean"}}""", """["id"]""")),
            ApiTool("plugin_dev_revoke", "Revoke permissions of an installed plugin (the inverse of plugin_dev_grant); pass permissions one by one, or all:true to revoke every granted permission.", schema("""{"id":{"type":"string","minLength":1},"permissions":{"type":"array","items":{"type":"string"}},"all":{"type":"boolean"}}""", """["id"]""")),
            ApiTool("plugin_dev_config", "Write plugin config (validated against the plugin's Config schema); config is a JSON object, reset:true restores defaults.", schema("""{"id":{"type":"string","minLength":1},"config":{"type":"object"},"reset":{"type":"boolean"}}""", """["id"]""")),
            ApiTool("plugin_dev_role", "Switch a plugin role: shell/loading/agent/storage/prompt/model; for value use the role selection value (native for built-in implementations, builtin.loading.native for loading pages, deepseek/openai for models), or the plugin id / pluginId#implementationId for third-party plugins.", schema("""{"role":{"type":"string","enum":["shell","loading","agent","storage","prompt","model"]},"value":{"type":"string","minLength":1}}""", """["role","value"]""")),
            // M88-I：插件中间件总开关（全局旁路）。扩展点插件“没效果”时由 AI 打开；
            // 走全局审批档并写审计（docs/190 §3-F：不再有「AI 全控制插件」开关）。
            // M88-I：读总开关状态（只读）。
            ApiTool("plugin_dev_middleware_state", "Read the current plugin middleware master switch state (on/off) without changing any setting; check it first when an extension-point plugin does not seem to work.", schema("""{}""")),
            ApiTool("plugin_dev_middleware", "Turn the plugin middleware master switch on/off (a global bypass: when off, tool/request/storage/event hooks all stop working, so extension-point plugins appear to do nothing). Use only when troubleshooting or fixing \"extension-point plugin has no effect\".", schema("""{"enabled":{"type":"boolean"}}""", """["enabled"]""")),
        )
    }
}
