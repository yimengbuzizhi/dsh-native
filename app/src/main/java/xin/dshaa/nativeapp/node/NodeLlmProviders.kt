package xin.dshaa.nativeapp.node

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * P3-b（`docs/187` §9.2）：Node 插件通过 `ctx.llm.register` 声明的模型供应商。
 *
 * 宿主侧（`assets/node/runtime.zip` 里的 `host.mjs`）只做**登记**并把声明原样报上来；
 * 真正发请求的是原生 `ConfiguredLlmProvider`（走内置 Anthropic / OpenAI 两条 HTTP 通道）。
 *
 * **流式必须如实分层**（用户原话「不要假装流式」）：
 * - [streamMode] = `http` → 原生 HTTP 通道，真流式；
 * - `callback` → 插件只给了 JS `stream()`，原生桥接不到 → [usable] = false，列出来但标不可用；
 * - `http+callback` → 两者都有，原生只走 HTTP 那一半，callback 不会被执行。
 *
 * 本文件纯 Kotlin（无 Android），JVM 用例锁死解析与「不把 callback 当可用」这条。
 */
data class NodeLlmProviderSpec(
    /** 原生 provider id：`node:<插件声明的 id>`（与内置 `deepseek`/`openai` 命名空间隔开）。 */
    val id: String,
    /** 插件声明的原始 id（日志/证据里保留）。 */
    val remoteId: String,
    val label: String,
    /** `openai` / `anthropic` / 空（跟随宿主当前协议）。 */
    val protocol: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    /** 插件是否给了 `stream()` 函数（给了也调不到，只用于展示与标注）。 */
    val hasStreamCallback: Boolean,
    val streamMode: String,
    val usable: Boolean,
    /** 不可用/降级的人话原因（空 = 没有需要注意的）。 */
    val reason: String,
    /** 来源模块（插件清单里的 specifier）。 */
    val owner: String,
)

object NodeLlmProviders {

    /** 原生 provider id 前缀：模型列表/设置里一眼能看出它来自 Node 宿主。 */
    const val PREFIX = "node:"

    const val MODE_HTTP = "http"
    const val MODE_CALLBACK = "callback"
    const val MODE_HTTP_CALLBACK = "http+callback"

    fun nativeId(remoteId: String): String =
        if (remoteId.startsWith(PREFIX)) remoteId else PREFIX + remoteId

    fun isNativeId(id: String?): Boolean = id != null && id.startsWith(PREFIX)

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()

    /**
     * 一条声明 → [NodeLlmProviderSpec]；没有 id 返回 null（其余字段都允许缺省）。
     *
     * `usable` 的口径与 host.mjs 一致，但**这里再判一次**：桥对面是第三方代码，
     * 不能只信它给的布尔值（万一它把 callback-only 标成 usable，原生也不能当真）。
     */
    fun parse(raw: JsonObject): NodeLlmProviderSpec? {
        val remoteId = raw.text("id").trim()
        if (remoteId.isEmpty()) return null
        val hasCallback = raw.text("hasStreamCallback").toBoolean()
        val declaredMode = raw.text("streamMode").trim().lowercase()
        val mode = when {
            declaredMode == MODE_HTTP || declaredMode == MODE_CALLBACK || declaredMode == MODE_HTTP_CALLBACK -> declaredMode
            hasCallback -> MODE_CALLBACK
            else -> MODE_HTTP
        }
        // callback-only 永远不可用：原生调不到 JS 函数，这是硬事实，不看宿主怎么说。
        val usable = mode != MODE_CALLBACK
        val reason = when {
            !usable -> raw.text("reason").ifBlank { "插件只提供 JS stream() 回调，原生桥接不到（不假装流式）" }
            else -> raw.text("reason")
        }
        val protocol = when (raw.text("protocol").trim().lowercase()) {
            "openai" -> "openai"
            "anthropic" -> "anthropic"
            else -> ""
        }
        val model = raw.text("model").trim()
        val label = raw.text("label").trim().ifBlank { remoteId }
        return NodeLlmProviderSpec(
            id = nativeId(remoteId),
            remoteId = remoteId,
            label = if (model.isBlank()) label else "$label（$model）",
            protocol = protocol,
            baseUrl = raw.text("baseUrl").trim(),
            model = model,
            apiKey = raw.text("apiKey"),
            hasStreamCallback = hasCallback,
            streamMode = mode,
            usable = usable,
            reason = reason,
            owner = raw.text("owner"),
        )
    }

    /** 一批声明 → 去重按 id（后注册覆盖，和 dsh/原生注册表同语义），按 id 排序。 */
    fun parseAll(raw: List<JsonObject>): List<NodeLlmProviderSpec> {
        val byId = LinkedHashMap<String, NodeLlmProviderSpec>()
        raw.forEach { element ->
            parse(element)?.let { byId[it.id] = it }
        }
        return byId.values.sortedBy { it.id }
    }

    /** 角色页展示用的一行：`[Node] 标签`。 */
    fun displayLabel(spec: NodeLlmProviderSpec): String = "[Node] ${spec.label}"
}
