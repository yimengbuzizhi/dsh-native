package xin.dshaa.nativeapp.device.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult
import xin.dshaa.nativeapp.data.CapabilityHost

/**
 * M88-C（docs/183 §3）：把「能力」送进模型工具表。
 *
 * 根因：能力一直只在 [CapabilityHost]（插件页/调试入口），模型工具表只有
 * `promptPack.androidApiTools() + pluginApiTools + pluginDevTools`，所以 AI 从来「看不到」
 * notify/clipboard 这些能力——不是模型不听话。
 *
 * 这两个工具归内置组 `builtin.capability`（「能力与通知」，可整体关闭）：
 * - `capability {id, args?}`：调任意内置/插件能力；未知 id 会列出本会话可用 id；
 * - `notify {title?, body}`：发系统通知；没系统权限时返回可行动错误（沿用 H5 的检查）。
 *
 * 依赖用函数注入（不直接持有 Android Context），JVM 测试可给假 host。
 */
class CapabilityTools(
    private val invoke: suspend (id: String, argsJson: String?) -> CapabilityHost.InvokeResult,
    /** 本会话「可用」的能力 id（内置 + 已启用且授权 capability 的插件能力）。 */
    private val availableIds: () -> List<String>,
    /** 现在能不能发系统通知（`CapabilityHost.notificationsAllowed`）。 */
    private val notificationsAllowed: () -> Boolean,
) {

    /** 内置组 `builtin.capability` 的两个工具名（与 [xin.dshaa.nativeapp.data.BuiltinPlugins] 一致）。 */
    companion object {
        const val GROUP_ID = "builtin.capability"
        const val CAPABILITY_TOOL = "capability"
        const val NOTIFY_TOOL = "notify"
    }

    val tools: List<DshTool> = listOf(CapabilityTool(), NotifyTool())

    private inner class CapabilityTool : DshTool {
        override val name: String = CAPABILITY_TOOL

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val id = args.text("id").trim()
            val available = availableIds()
            if (id.isEmpty()) {
                return ToolResult.err("capability requires id. Available capabilities (${available.size}): ${idList(available)}")
            }
            if (id.equals("root.exec", ignoreCase = true)) {
                // M88-B：root.exec 已并入 shell.exec。不是「未知 id」，直接给合并说明。
                return ToolResult.err(
                    "${xin.dshaa.nativeapp.device.ShellCapability.MERGED_ROOT_EXEC_REASON}: " +
                        CapabilityHost.mergedRootExecMessage(),
                )
            }
            if (id !in available) {
                return ToolResult.err(
                    "Unknown capability id: $id. Available capabilities (${available.size}): ${idList(available)}. " +
                        "Plugin capabilities require the plugin to be enabled and granted the capability permission.",
                )
            }
            val extra = when (val raw = args["args"]) {
                null -> "{}"
                is JsonObject -> raw.toString()
                else -> return ToolResult.err("capability args must be a JSON object; received: ${typeName(raw)}")
            }
            val result = invoke(id, extra)
            return if (result.ok) {
                ToolResult.ok(result.valueJson)
            } else {
                ToolResult.err("Capability $id failed: ${result.error ?: result.summary.ifBlank { "unknown reason" }}")
            }
        }
    }

    private inner class NotifyTool : DshTool {
        override val name: String = NOTIFY_TOOL

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val title = args.text("title").trim()
            val body = args.text("body").trim()
            if (title.isEmpty() && body.isEmpty()) {
                return ToolResult.err("notify requires at least one of title or body")
            }
            if (!notificationsAllowed()) {
                return ToolResult.err(
                    "System notification permission is not granted. Open Settings → Permissions & Capabilities → Notifications " +
                        "and allow notifications for this app, or use capability {id:\"notify.toast\"} to show only a Toast. Nothing was sent.",
                )
            }
            val payload = buildJsonObject {
                if (title.isNotEmpty()) put("title", title)
                put("body", body)
            }
            val result = invoke("notify", payload.toString())
            if (!result.ok) {
                return ToolResult.err("Failed to send notification: ${result.error ?: result.summary.ifBlank { "unknown reason" }}")
            }
            val text = if (title.isEmpty()) body else "$title: $body"
            return ToolResult.ok("Notification sent: $text")
        }
    }

    private fun JsonObject.text(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()

    private fun idList(ids: List<String>): String =
        if (ids.isEmpty()) "(none; enable and authorize capabilities on the Plugins page first)" else ids.joinToString(", ")

    private fun typeName(value: JsonElement): String = value::class.simpleName ?: "non-object"
}
