package xin.dshaa.nativeapp.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xin.dshaa.core.js.JsHost
import xin.dshaa.core.js.JsStatus
import xin.dshaa.core.js.QuickJs
import xin.dshaa.core.plugins.PluginInvokeResult
import xin.dshaa.core.plugins.PluginModule
import xin.dshaa.core.plugins.PluginPermission
import xin.dshaa.core.plugins.PluginPermissions
import xin.dshaa.core.plugins.PluginRecord
import xin.dshaa.core.plugins.PluginSandbox
import xin.dshaa.core.plugins.PluginSchema
import xin.dshaa.core.protocol.ApiTool
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * 插件工具接入对话 + 宿主能力门（M27 接入 / M28 限权）。
 *
 * 链路：插件页导入/启用 → [refresh] 把「已启用 + 参数能归一成 JSON Schema」的工具做成
 * [DshTool] → `AppViewModel` 注册进 `ToolRegistry` → 下一次请求的 `tools` 里就有它。
 *
 * 真正执行时**每次调用新建一个 QuickJS 实例**，重新跑模块、重新 `apply(ctx)`，再调
 * `execute(args)`。这样注册和执行看到的是同一份代码/闭包；不做常驻 JS 运行时（插件是低频工具，
 * 少一份生命周期和内存泄漏的风险）。
 *
 * 宿主能力（M28）：插件里的 `fs` / `http` / `fetch` / `child_process` 不是空实现了，而是走
 * [PluginSandbox]：文件受 `fs:workspace` / `fs:any` 限制，网络只允许 manifest 里声明的域名，
 * shell 必须用户逐项确认。权限来自插件记录的 `grantedPermissions`，**每次执行现取**。
 *
 * 边界（必须和用户/群友说清楚，别夸口）：
 * - 不是内核级沙箱：拦的是宿主能力调用，不是内存/CPU 隔离（死循环靠超时中断）；
 * - 参数认不出 JSON Schema（schemastery 里 tuple/transform 这类转不了的）就不注入，插件页会说明；
 * - schemastery 参数会转成 JSON Schema（M29），但不套默认值：插件 `execute` 拿到的就是模型给的参数；
 * - 工具名全局唯一；和内置工具或别的插件重名时跳过并打日志（不覆盖内置工具）。
 */
class PluginToolRuntime {

    /** 一个已接入对话的插件工具。 */
    data class Binding(
        val pluginId: String,
        val pluginName: String,
        val toolName: String,
        val description: String,
        val schema: JsonObject,
        val entry: File,
        val permissions: List<PluginPermission>,
        val grantedPermissions: List<String>,
        val workspace: File,
        /** 已合并默认值的插件配置（M30）：每次 apply 都拿到它。 */
        val configJson: String,
    )

    @Volatile
    private var current: List<Binding> = emptyList()

    /** 按当前插件清单重算可注入工具；启用/停用/重载/删除/授权/切换工作区后都要调一次。 */
    fun refresh(records: List<PluginRecord>, workspace: File): List<Binding> {
        val bindings = ArrayList<Binding>()
        val used = HashSet<String>()
        records.filter { it.enabled }.forEach { record ->
            val entry = File(record.path, record.main)
            if (!entry.isFile) return@forEach
            val permissions = PluginPermissions.parseAll(record.permissions)
            val configJson = PluginSchema.effectiveText(record.configSchema, record.config, record.configDefaults)
            record.tools.forEach { toolName ->
                // 只有 schema 能被 API 接受的工具才注入；缺 schema 的旧记录在插件页提示重新加载
                val schema = PluginSchema.parse(record.toolSchemas[toolName]) ?: return@forEach
                if (!used.add(toolName)) {
                    Log.w(TAG, "工具名冲突，跳过 ${record.id} 的 $toolName")
                    return@forEach
                }
                bindings += Binding(
                    pluginId = record.id,
                    pluginName = record.name,
                    toolName = toolName,
                    description = record.toolDescriptions[toolName].orEmpty()
                        .ifBlank { "插件「${record.name}」注册的工具" },
                    schema = schema,
                    entry = entry,
                    permissions = permissions,
                    grantedPermissions = record.grantedPermissions,
                    workspace = workspace,
                    configJson = configJson,
                )
            }
        }
        current = bindings
        return bindings
    }

    /** 当前已接入的工具（注册表异步刷新后，请求体从这里取 schema）。 */
    fun apiTools(): List<ApiTool> = current.map { binding ->
        ApiTool(
            name = binding.toolName,
            description = binding.description,
            inputSchema = binding.schema,
        )
    }

    /** 把一条绑定包成 `DshTool`（每次刷新都新建，闭包持有的是不可变绑定）。 */
    fun toolFor(binding: Binding): DshTool = PluginDshTool(binding)

    private inner class PluginDshTool(private val binding: Binding) : DshTool {
        override val name: String = binding.toolName

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            // M30：模型可以省略有默认值的参数；宿主按 schema 补齐后再交给插件。
            val filled = PluginSchema.withDefaults(binding.schema, args)
            val meta = mapOf("plugin" to binding.pluginId, "pluginTool" to binding.toolName)
            // 先挡明显不对的调用（缺必填/类型错/枚举外），错误原文直接给模型看。
            PluginSchema.validate(binding.schema, filled)?.let { invalid ->
                return ToolResult.err("插件工具参数不合法：$invalid", meta = meta)
            }
            val result = withContext(Dispatchers.IO) { run(binding, filled.toString(), ctx.timeoutMs) }
            return if (result.ok) {
                ToolResult.ok(result.text, meta = meta)
            } else {
                ToolResult.err(result.error ?: "插件工具执行失败", meta = meta)
            }
        }
    }

    /** 跑一次插件工具。任何异常都归一成 [PluginInvokeResult]，不让插件把整轮对话弄崩。 */
    private fun run(binding: Binding, argsJson: String, timeoutMs: Long): PluginInvokeResult {
        val source = runCatching { binding.entry.readText(Charsets.UTF_8) }
            .getOrElse { return PluginInvokeResult(false, error = "读不了插件入口：${it.message}") }
        val module = runCatching { PluginModule.transform(source) }
            .getOrElse { return PluginInvokeResult(false, error = "解析插件模块失败：${it.message}") }
        val sandbox = PluginSandbox(
            pluginId = binding.pluginId,
            workspace = binding.workspace,
            permissions = binding.permissions,
            grantedPermissions = binding.grantedPermissions,
        )
        val script = runCatching {
            PluginModule.invokeScript(
                module,
                binding.toolName,
                argsJson,
                binding.workspace.absolutePath,
                binding.configJson,
            )
        }.getOrElse { return PluginInvokeResult(false, error = "生成插件调用脚本失败：${it.message}") }

        // 异步宿主能力（net.fetch / shell.exec 的异步形态）在别的线程跑，完成后回灌 QuickJS。
        val hostExecutor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "plugin-host-${binding.pluginId}").apply { isDaemon = true }
        }
        val jsRef = AtomicReference<QuickJs?>(null)
        val js = runCatching {
            QuickJs(object : JsHost {
                override fun callSync(method: String, payloadJson: String): String? {
                    if (method == "log") {
                        logPluginConsole(binding, payloadJson)
                        return """{"ok":true}"""
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
        }.getOrElse {
            hostExecutor.shutdownNow()
            return PluginInvokeResult(false, error = "QuickJS 不可用（这个 ABI 没编进去？）：${it.message}")
        }
        jsRef.set(js)

        // 插件里写了 while(true) 也不能把对话线程焊死：到点中断脚本。
        val watchdog = Thread {
            try {
                Thread.sleep(timeoutMs.coerceAtLeast(1_000))
                js.interrupt()
            } catch (_: InterruptedException) {
                // 正常收尾
            }
        }.apply {
            name = "plugin-tool-watchdog"
            isDaemon = true
            start()
        }

        return try {
            val outcome = js.evaluate(script, binding.entry.name)
            when (outcome.status) {
                JsStatus.COMPLETED -> {
                    val payload = decodeString(outcome.valueJson)
                    if (payload == null) {
                        PluginInvokeResult(false, error = "插件执行没有返回结果（completed，value=null）")
                    } else {
                        PluginModule.parseInvoke(payload)
                    }
                }

                JsStatus.INTERRUPTED -> PluginInvokeResult(
                    false,
                    error = "插件执行超时（>${timeoutMs}ms）或已被中断",
                )

                JsStatus.ERROR -> PluginInvokeResult(
                    false,
                    error = "插件执行报错：${outcome.error ?: "QuickJS error"}",
                )
            }
        } catch (t: Throwable) {
            PluginInvokeResult(false, error = "插件执行崩溃：${t.message ?: t.javaClass.simpleName}")
        } finally {
            watchdog.interrupt()
            hostExecutor.shutdownNow()
            runCatching { js.close() }
        }
    }

    /** 插件 `console.*` → logcat（限定长度，别让插件刷爆日志）。 */
    private fun logPluginConsole(binding: Binding, payloadJson: String) {
        val payload = runCatching { Json.parseToJsonElement(payloadJson).jsonObject }.getOrNull() ?: return
        val level = payload["level"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
        val message = payload["message"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
        val line = "plugin ${binding.pluginId}/${binding.toolName} [$level] ${message.take(2000)}"
        if (level == "error") Log.e(TAG, line) else Log.i(TAG, line)
    }

    /** QuickJS 返回值是「JSON 编码过的 JSON 字符串」：解两层。 */
    private fun decodeString(valueJson: String?): String? {
        if (valueJson == null) return null
        val element = runCatching { Json.parseToJsonElement(valueJson) }.getOrNull() ?: return null
        return runCatching { element.jsonPrimitive.content }.getOrNull()
    }

    private companion object {
        const val TAG = "DshPlugin"
    }
}
