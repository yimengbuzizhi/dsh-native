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
    private val callTool: suspend (String, String, JsonObject, ToolContext) -> ToolResult = { _, _, _, _ -> ToolResult.err("工具测试未挂载") },
    private val testCapability: suspend (String, JsonObject) -> ToolResult = { _, _ -> ToolResult.err("能力测试未挂载") },
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
                if (id.isNotEmpty() && records.none { it.id == id }) return ToolResult.err("插件不存在：$id")
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
                val changed = withContext(Dispatchers.IO) { store.setEnabled(string("id"), args.getValue("enabled").jsonPrimitive.boolean) }
                if (!changed) return ToolResult.err("插件不存在：${string("id")}")
                onChanged()
                return ToolResult.ok("插件 ${string("id")}：${if (args.getValue("enabled").jsonPrimitive.boolean) "已启用" else "已停用"}")
            }
            "plugin_dev_reload" -> {
                val id = string("id")
                val record = withContext(Dispatchers.IO) { store.list().firstOrNull { it.id == id } }
                    ?: return ToolResult.err("插件不存在：$id")
                val tested = store.testPath(File(record.path))
                if (!tested.ok) return ToolResult.err(tested.message)
                if (tested.id != id) return ToolResult.err("清单 id 已变化，请重新安装")
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
                    ?: return ToolResult.err("插件不存在：${string("id")}")
                return withContext(Dispatchers.IO) {
                    PluginDevProbe.testShell(record, ctx.workspace, string("shell").ifEmpty { null },
                        string("action").ifEmpty { null }, args["value"]?.jsonObject ?: buildJsonObject {})
                }
            }
            "plugin_dev_scaffold" -> {
                val path = resolvePath(string("path"), ctx)
                val template = string("template").ifEmpty { "tool" }
                withContext(Dispatchers.IO) { PluginDevScaffold.create(path, string("id"), template) }
                return ToolResult.ok("已创建 $template 插件：${path.absolutePath}；可运行 plugin_dev_test")
            }
            else -> return ToolResult.err("未知插件开发工具")
        }
    }

    companion object {
        /** Only these may enter AppViewModel's read-only whitelist; all other names require normal write approval. */
        val readOnlyNames = setOf("plugin_dev_list", "plugin_dev_test")
        val writeNames = setOf("plugin_dev_install", "plugin_dev_enable", "plugin_dev_reload", "plugin_dev_scaffold",
            "plugin_dev_call", "plugin_dev_test_shell", "plugin_dev_test_capability")

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

        private val definitions = listOf(
            ApiTool("plugin_dev_list", "列出已安装插件的版本、开关、权限和工具/壳/加载页注册元数据；可按 id 查询，登记不等于选中。", schema("""{"id":{"type":"string","minLength":1}}""")),
            ApiTool("plugin_dev_test", "隔离试运行目录或 zip 的 apply，返回工具/壳/加载页登记和错误；不安装、不执行工具或验证冷启动画面。", schema("""{"path":{"type":"string","minLength":1,"description":"插件目录或 zip 路径，相对当前目录解析。"}}""", """["path"]""")),
            ApiTool("plugin_dev_install", "验证和试运行通过后安装插件目录或 zip；保留兼容配置，失败回滚旧包，不授危险权限或选择壳。", schema("""{"path":{"type":"string","minLength":1}}""", """["path"]""")),
            ApiTool("plugin_dev_enable", "启用或停用已安装插件并刷新注册，不授予权限或选择壳。", schema("""{"id":{"type":"string","minLength":1},"enabled":{"type":"boolean"}}""", """["id","enabled"]""")),
            ApiTool("plugin_dev_reload", "验证已安装插件后重新加载清单和注册，刷新现有插件。", schema("""{"id":{"type":"string","minLength":1}}""", """["id"]""")),
            ApiTool("plugin_dev_call", "执行已启用插件的一项工具，沿用其权限、参数校验和超时；可能产生真实效果。", schema("""{"id":{"type":"string","minLength":1},"tool":{"type":"string","minLength":1},"args":{"type":"object"}}""", """["id","tool"]""")),
            ApiTool("plugin_dev_test_shell", "在隔离运行时离屏挂载壳并可测试动作；宿主命令仅记录，验证树或 HTML，不切换当前界面。", schema("""{"id":{"type":"string","minLength":1},"shell":{"type":"string"},"action":{"type":"string"},"value":{"type":"object"}}""", """["id"]""")),
            ApiTool("plugin_dev_test_capability", "调用已授权的能力并返回结果；沿用能力提供方权限和超时，可能产生真实效果。", schema("""{"capability":{"type":"string","minLength":1},"args":{"type":"object"}}""", """["capability"]""")),
            ApiTool("plugin_dev_scaffold", "在新目录生成工具、Surface/WebView 壳或 loading/v1 静态加载页模板，不覆盖已有目录。", schema("""{"path":{"type":"string","minLength":1},"id":{"type":"string","minLength":1,"maxLength":80},"template":{"type":"string","enum":["tool","surface-shell","webview-shell","loading"],"default":"tool"}}""", """["path","id"]""")),
        )
    }
}
