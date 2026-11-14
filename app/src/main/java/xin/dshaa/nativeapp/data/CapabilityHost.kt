package xin.dshaa.nativeapp.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xin.dshaa.core.capability.CAPABILITY_INVOKE_METHOD
import xin.dshaa.core.capability.CapabilityCandidate
import xin.dshaa.core.capability.CapabilityLookup
import xin.dshaa.core.capability.CapabilityRouting
import xin.dshaa.core.js.JsOutcome
import xin.dshaa.core.js.JsStatus
import xin.dshaa.core.plugins.PluginRecord
import xin.dshaa.ui.shellapi.CapabilityUi

/**
 * M85-F：能力宿主（`capability/v1`）。
 *
 * 职责只有三件（docs/144 §3）：**登记 + 权限门 + 超时**。
 * - 登记：内置骨架平台能力由宿主直接登记；插件能力来自 `ctx.capability.register`
 *   （`PluginRecord.capabilities`，常驻运行时里 dispatch `capability.invoke`）。
 * - 权限门：插件提供方必须启用且拿到 `capability` 授权；内置提供方不经过插件权限。
 * - 超时：插件调用 4.5s（dispatch 自带）外层再兜 5s；超时/运行时消失如实报错。
 *
 * 特权能力（root / NFC / Shizuku / adb-shell）**不在宿主写死**：没有插件提供时
 * [CapabilityRouting] 会给出「没有提供方」的不可用原因，插件页/调试 dump 原样展示。
 */
class CapabilityHost(
    private val context: Context,
    private val dispatch: (pluginId: String, method: String, payloadJson: String, timeoutMs: Long) -> JsOutcome?,
    private val records: () -> List<PluginRecord>,
) {

    data class InvokeResult(
        val ok: Boolean,
        val valueJson: String = "null",
        val error: String? = null,
        /** 实际执行的提供方插件 id；内置为 null。 */
        val pluginId: String? = null,
        /** 给用户/调试看的一句话。 */
        val summary: String = "",
    )

    private data class Builtin(
        val id: String,
        val label: String,
        val description: String,
        val kind: String,
        val invoke: (JsonObject) -> JsonElement,
    )

    /** 内置骨架平台能力：只覆盖通知/剪贴板/分享这类无特权原语；root/NFC/Shizuku 一律不内置。 */
    private val builtins: List<Builtin> = listOf(
        Builtin(
            id = "notify",
            label = "系统提示（Toast）",
            description = "骨架平台能力：弹一条 Toast（不写通知栏、不需要 notify 权限）。",
            kind = "system",
        ) { args ->
            val title = args.string("title").orEmpty()
            val body = args.string("body").orEmpty()
            val text = if (title.isBlank()) body else "$title：$body"
            runCatching {
                Toast.makeText(context, text.ifBlank { "鲸歌能力调用" }, Toast.LENGTH_SHORT).show()
            }
            buildJsonObject { put("shown", true); put("via", "toast"); put("text", text) }
        },
        Builtin(
            id = "clipboard.read",
            label = "读剪贴板",
            description = "骨架平台能力：读当前剪贴板的纯文本（无文本返回空串）。",
            kind = "system",
        ) {
            val text = runCatching {
                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            }.getOrNull().orEmpty()
            buildJsonObject { put("text", text) }
        },
        Builtin(
            id = "clipboard.write",
            label = "写剪贴板",
            description = "骨架平台能力：把一段纯文本放进剪贴板。",
            kind = "system",
        ) { args ->
            val text = args.string("text").orEmpty()
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("鲸歌", text))
            buildJsonObject { put("written", true); put("length", text.length) }
        },
        Builtin(
            id = "share",
            label = "分享文本",
            description = "骨架平台能力：调系统分享面板发一段纯文本。",
            kind = "system",
        ) { args ->
            val text = args.string("text").orEmpty()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, args.string("title") ?: "分享").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(chooser) }
            buildJsonObject { put("shared", true); put("length", text.length) }
        },
    )

    /** 全部候选（内置 + 每个已安装插件声明的能力；停用/缺授权也进来，用于解释原因）。 */
    fun candidates(): List<CapabilityCandidate> {
        val out = ArrayList<CapabilityCandidate>()
        builtins.forEach { item ->
            out += CapabilityCandidate(
                id = item.id,
                label = item.label,
                description = item.description,
                kind = item.kind,
                pluginId = null,
                builtin = true,
            )
        }
        records().forEach { record ->
            record.capabilities.forEach { capability ->
                out += CapabilityCandidate(
                    id = capability.id,
                    label = capability.label,
                    description = capability.description,
                    kind = capability.kind,
                    pluginId = record.id,
                    builtin = false,
                    enabled = record.enabled,
                    permitted = record.grantedPermissions.any { it.equals("capability", true) },
                )
            }
        }
        return out
    }

    /** 插件页展示用：每个能力 id 一行，插件优先内置；没有可用提供方时如实说不可用。 */
    fun ui(): List<CapabilityUi> {
        val all = candidates()
        return CapabilityRouting.catalog(all).map { first ->
            when (val lookup = CapabilityRouting.resolve(first.id, all)) {
                is CapabilityLookup.Found -> {
                    val capability = lookup.capability
                    CapabilityUi(
                        id = capability.id,
                        label = capability.label,
                        description = capability.description,
                        kind = capability.kind,
                        provider = providerName(capability.pluginId, capability.builtin),
                        pluginId = capability.pluginId,
                        builtin = capability.builtin,
                        available = true,
                    )
                }

                is CapabilityLookup.Unavailable -> CapabilityUi(
                    id = first.id,
                    label = first.label,
                    description = first.description,
                    kind = first.kind,
                    provider = providerName(first.pluginId, first.builtin),
                    pluginId = first.pluginId,
                    builtin = first.builtin,
                    available = false,
                    enabled = first.enabled,
                    permitted = first.permitted,
                    unavailableReason = lookup.reason,
                )
            }
        }
    }

    /**
     * 调用一个能力。解析失败/超时/插件报错都返回 `ok=false` + 可读原因，
     * **不静默换成别的提供方**（同 id 覆盖的插件停用时，解析规则会回落到内置；
     * 这是角色语义，不是把失败藏起来）。
     */
    suspend fun invoke(id: String, argsJson: String?): InvokeResult {
        val cleanId = id.trim()
        val all = candidates()
        val lookup = CapabilityRouting.resolve(cleanId, all)
        if (lookup !is CapabilityLookup.Found) {
            val reason = (lookup as CapabilityLookup.Unavailable).reason
            Log.w(TAG, "invoke id=$cleanId unavailable: $reason")
            return InvokeResult(ok = false, error = reason, summary = reason)
        }
        val capability = lookup.capability
        val args = runCatching {
            Json.parseToJsonElement(argsJson?.takeIf { it.isNotBlank() } ?: "{}")
        }.getOrNull() as? JsonObject ?: buildJsonObject { }
        if (capability.builtin) {
            val builtin = builtins.firstOrNull { it.id.equals(capability.id, true) }
                ?: return InvokeResult(ok = false, error = "内置能力 ${capability.id} 没有实现", summary = "内置能力没有实现")
            return runCatching {
                val value = withTimeoutOrNull(5_000) { withContext(Dispatchers.Default) { builtin.invoke(args) } }
                    ?: return InvokeResult(ok = false, error = "能力调用超时（5s）", summary = "调用超时")
                InvokeResult(
                    ok = true,
                    valueJson = value.toString(),
                    pluginId = null,
                    summary = "内置能力 ${capability.id} 调用成功",
                )
            }.getOrElse { error ->
                val reason = "内置能力 ${capability.id} 调用失败：${error.message ?: error.javaClass.simpleName}"
                Log.w(TAG, reason)
                InvokeResult(ok = false, error = reason, summary = reason)
            }
        }
        val pluginId = capability.pluginId
            ?: return InvokeResult(ok = false, error = "能力 ${capability.id} 没有提供方", summary = "没有提供方")
        val payload = buildJsonObject {
            put("id", capability.id)
            put("args", args)
        }
        Log.i(TAG, "invoke id=${capability.id} plugin=$pluginId")
        val startedAt = System.currentTimeMillis()
        val outcome = withTimeoutOrNull(5_000) {
            withContext(Dispatchers.IO) {
                dispatch(pluginId, CAPABILITY_INVOKE_METHOD, payload.toString(), 4_500)
            }
        }
        val elapsed = System.currentTimeMillis() - startedAt
        if (outcome == null) {
            val reason = "能力 ${capability.id} 调用超时/插件运行时不可用（${elapsed}ms）"
            Log.w(TAG, reason)
            return InvokeResult(ok = false, error = reason, pluginId = pluginId, summary = reason)
        }
        if (outcome.status != JsStatus.COMPLETED) {
            val reason = "能力 ${capability.id} 调用失败：${outcome.error ?: outcome.status}"
            Log.w(TAG, reason)
            return InvokeResult(ok = false, error = reason, pluginId = pluginId, summary = reason)
        }
        val obj = runCatching { Json.parseToJsonElement(outcome.valueJson ?: "null").jsonObject }.getOrNull()
        if (obj == null) {
            val reason = "能力 ${capability.id} 返回了非 JSON 结果"
            Log.w(TAG, reason)
            return InvokeResult(ok = false, error = reason, pluginId = pluginId, summary = reason)
        }
        val ok = obj["ok"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
        if (!ok) {
            val reason = obj["error"]?.jsonPrimitive?.contentOrNull
                ?: "能力 ${capability.id} 调用失败"
            Log.w(TAG, "invoke failed plugin=$pluginId: $reason")
            return InvokeResult(ok = false, error = reason, pluginId = pluginId, summary = reason)
        }
        val value = obj["value"] ?: obj["result"] ?: buildJsonObject { }
        Log.i(TAG, "invoke ok id=${capability.id} plugin=$pluginId elapsed=${elapsed}ms")
        return InvokeResult(
            ok = true,
            valueJson = value.toString(),
            pluginId = pluginId,
            summary = "能力 ${capability.id} 调用成功（${providerName(pluginId, false)}，${elapsed}ms）",
        )
    }

    private fun providerName(pluginId: String?, builtin: Boolean): String {
        if (builtin || pluginId.isNullOrBlank()) return "内置（骨架平台）"
        return records().firstOrNull { it.id == pluginId }?.name ?: pluginId
    }

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    companion object {
        private const val TAG = "DshCapability"
    }
}
