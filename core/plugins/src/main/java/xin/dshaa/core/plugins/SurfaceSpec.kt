package xin.dshaa.core.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * M85-A：Surface 协议 v1 的宿主侧解析（纯 JVM，不依赖 Android）。
 *
 * 一切 UI 都从插件返回的 JSON 树来：宿主只认识 18 个原语和少量属性，
 * 不把「聊天页 / 消息气泡 / 工具卡」这类产品语义写进宿主。
 *
 * 安全边界（解析时强制，渲染器不用再防御）：
 * - 只允许 [PRIMITIVES] 里的类型；
 * - 树最多 [MAX_NODES] 个节点、[MAX_DEPTH] 层，文本截到 [MAX_TEXT] 字符；
 * - `bind` 只允许只读路径表达式（根必须是 [BIND_ROOTS]，`item.*` 只在 list 的 item 模板里）；
 * - 动作 id 只允许 `[A-Za-z0-9][A-Za-z0-9._:-]{0,63}`；
 * - 未知属性直接丢弃（不认识的属性不会悄悄影响布局/权限）。
 */
data class SurfaceNode(
    val type: String,
    val key: String?,
    val props: JsonObject,
    val children: List<SurfaceNode> = emptyList(),
    /** `list` 的行模板；其它类型为 null。 */
    val item: SurfaceNode? = null,
) {
    fun string(name: String): String? =
        props[name]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    fun int(name: String): Int? =
        props[name]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }

    fun double(name: String): Double? =
        props[name]?.let { runCatching { it.jsonPrimitive.content.toDoubleOrNull() }.getOrNull() }

    fun boolean(name: String): Boolean? =
        props[name]?.let { element ->
            runCatching { element.jsonPrimitive.booleanOrNull }.getOrNull()
                ?: runCatching { element.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull()
        }

    fun objectValue(name: String): JsonObject? = props[name] as? JsonObject

    val bind: String? get() = string("bind")
    val onTap: String? get() = string("onTap")
    val onLongPress: String? get() = string("onLongPress")
    val onSubmit: String? get() = string("onSubmit")
    val onChange: String? get() = string("onChange")
}

/** 一棵 Surface 树。`title` 是宿主可选的窗口标题（壳可只返回 tree）。 */
data class SurfaceTree(val title: String?, val root: SurfaceNode)

/** 解析结果：坏树返回 [error]，调用方回退原生壳/显示错误，不崩溃。 */
data class SurfaceParseResult(val tree: SurfaceTree?, val error: String? = null) {
    val ok: Boolean get() = tree != null
}

object SurfaceSpec {

    const val VERSION = "surface/v1"

    /** 单棵树的上限：1000 条消息的压测远够（每个消息模板只算一次，不按数据条数展开）。 */
    const val MAX_NODES = 2_000
    const val MAX_DEPTH = 24
    const val MAX_TEXT = 8_000

    val PRIMITIVES: Set<String> = linkedSetOf(
        "column", "row", "stack", "scroll", "list",
        "text", "markdown", "image", "icon", "button", "textfield",
        "switch", "slider", "divider", "spacer", "progress", "chip", "webview",
    )

    val CONTAINERS: Set<String> = setOf("column", "row", "stack", "scroll")

    /** 壳 → 宿主的同步命令（M85-A 先最小集；M85-B 加附件；其余在 B/C 加）。 */
    val COMMANDS: Set<String> = linkedSetOf(
        "send", "stop", "newSession", "openSession", "selectSession",
        // M86-G：持久化待发送消息；模式只影响之后提交的新消息。
        "cancelQueuedPrompt", "resumeQueuedPrompts", "setSendWhileRunningMode",
        "openSettings", "closeShell", "setDraft", "approval",
        // M85-B2 附件：选择器/相机/截图/移除待发附件（宿主只做通用能力，不解释聊天语义）
        "pickFile", "screenshot", "removeAttachment",
        // M85-B2-2：壳自己的展开/收起状态（通用 key-value，宿主不解释 key 的含义）
        "setShellView",
        // M85-B2-3：模型提问的答案提交（空数组 = 跳过全部）
        "answer",
        // M85-B3：goal 面板（暂停/继续/编辑/清空）、提醒删除、后台会话聚焦
        "goal", "deleteSchedule", "focusSession",
    )

    val BIND_ROOTS: Set<String> = setOf("chat", "sessions", "settings", "theme", "capabilities", "item")

    private val BIND_PATH = Regex("""^!?[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*){0,4}$""")
    private val ACTION_ID = Regex("""^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$""")

    private val COMMON_PROPS = setOf(
        "key", "bind", "visible", "onTap", "onLongPress", "onSubmit", "onChange", "payload",
        "padding", "paddingH", "paddingV", "gap", "width", "height", "maxHeight", "grow",
        "background", "corner", "align", "justify", "margin",
    )

    private val PROPS: Map<String, Set<String>> = mapOf(
        "column" to COMMON_PROPS,
        "row" to COMMON_PROPS,
        "stack" to COMMON_PROPS,
        "scroll" to COMMON_PROPS,
        "list" to (COMMON_PROPS + setOf("horizontal", "empty", "item", "lazy")),
        "text" to (COMMON_PROPS + setOf("text", "size", "color", "weight", "maxLines", "mono", "align", "selectable", "lineHeight")),
        "markdown" to (COMMON_PROPS + setOf("text", "size", "color", "align")),
        "image" to (COMMON_PROPS + setOf("src", "fit", "corner", "height", "width", "contentDescription")),
        "icon" to (COMMON_PROPS + setOf("name", "size", "color")),
        "button" to (COMMON_PROPS + setOf("label", "icon", "style", "enabled")),
        "textfield" to (COMMON_PROPS + setOf("placeholder", "multiline", "maxLines", "value", "enabled")),
        "switch" to (COMMON_PROPS + setOf("checked", "label")),
        "slider" to (COMMON_PROPS + setOf("value", "steps")),
        "divider" to COMMON_PROPS,
        "spacer" to (COMMON_PROPS + setOf("size")),
        "progress" to (COMMON_PROPS + setOf("value", "label")),
        "chip" to (COMMON_PROPS + setOf("label", "selected", "icon")),
        "webview" to (COMMON_PROPS + setOf("url", "html", "height")),
    )

    /** 解析宿主收到的 JSON 文本：`{title?, tree}`，也接受直接给一个节点。 */
    fun parse(raw: String): SurfaceParseResult {
        val element = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) }.getOrNull()
            ?: return SurfaceParseResult(null, "Surface 树不是 JSON")
        return parseElement(element)
    }

    fun parseElement(element: JsonElement): SurfaceParseResult {
        val obj = element as? JsonObject ?: return SurfaceParseResult(null, "Surface 树根必须是对象")
        val title = runCatching { obj["title"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?.trim()?.take(120)?.takeIf { it.isNotBlank() }
        val rootElement = obj["tree"] ?: if (obj.containsKey("type")) obj else null
        if (rootElement == null) return SurfaceParseResult(null, "Surface 树缺少 tree")
        val counter = intArrayOf(0)
        val root = runCatching { parseNode(rootElement, 0, counter, inItem = false) }
            .getOrElse { return SurfaceParseResult(null, "Surface 树解析失败：${it.message ?: "未知错误"}") }
        return SurfaceParseResult(SurfaceTree(title, root), null)
    }

    private fun parseNode(element: JsonElement, depth: Int, counter: IntArray, inItem: Boolean): SurfaceNode {
        val obj = element as? JsonObject ?: throw IllegalArgumentException("节点必须是对象")
        if (depth > MAX_DEPTH) throw IllegalArgumentException("树太深（> $MAX_DEPTH 层）")
        counter[0]++
        if (counter[0] > MAX_NODES) throw IllegalArgumentException("节点太多（> $MAX_NODES 个）")

        val rawType = obj["type"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
        if (rawType.isEmpty()) throw IllegalArgumentException("节点缺少 type")
        if (rawType !in PRIMITIVES) throw IllegalArgumentException("未知原语：$rawType")

        val allowed = PROPS[rawType] ?: COMMON_PROPS
        val kept = buildJsonObject {
            obj.forEach { (name, value) ->
                if (name !in allowed || value is JsonNull) return@forEach
                when (name) {
                    "key" -> {
                        val key = value.jsonPrimitive.contentOrNull?.trim()?.take(120)
                        if (!key.isNullOrBlank() && key.none { it.isISOControl() }) put(name, JsonPrimitive(key))
                    }

                    "bind", "visible" -> {
                        val expr = value.jsonPrimitive.contentOrNull?.trim().orEmpty()
                        if (validBind(expr) && (inItem || !expr.removePrefix("!").startsWith("item."))) {
                            put(name, JsonPrimitive(expr))
                        }
                    }

                    "onTap", "onLongPress", "onSubmit", "onChange" -> {
                        val id = value.jsonPrimitive.contentOrNull?.trim().orEmpty()
                        if (validAction(id)) put(name, JsonPrimitive(id))
                    }

                    "text", "label", "placeholder", "empty", "contentDescription", "url", "html" -> {
                        val text = value.jsonPrimitive.contentOrNull ?: return@forEach
                        put(name, JsonPrimitive(text.take(MAX_TEXT)))
                    }

                    else -> put(name, value)
                }
            }
        }

        val children = if (rawType in CONTAINERS) {
            (obj["children"] as? JsonArray)?.map { child ->
                parseNode(child, depth + 1, counter, inItem = inItem)
            }.orEmpty()
        } else {
            emptyList()
        }

        val item = if (rawType == "list") {
            val template = obj["item"] ?: throw IllegalArgumentException("list 缺少 item 模板")
            parseNode(template, depth + 1, counter, inItem = true)
        } else {
            null
        }

        return SurfaceNode(
            type = rawType,
            key = kept["key"]?.jsonPrimitive?.contentOrNull,
            props = kept,
            children = children,
            item = item,
        )
    }

    /** `bind` 只读表达式：根在白名单里，点号分段，可选 `!` 取反（给 visible 用）。 */
    fun validBind(expr: String?): Boolean {
        val text = expr?.trim().orEmpty()
        if (text.isEmpty() || !BIND_PATH.matches(text)) return false
        val root = text.removePrefix("!").substringBefore('.')
        if (root !in BIND_ROOTS) return false
        // 标量必须指到字段；只有会话列表可以整根绑定（`sessions`）。
        return text.contains('.') || text.removePrefix("!") == "sessions"
    }

    fun validAction(id: String?): Boolean = !id.isNullOrBlank() && ACTION_ID.matches(id)

    /**
     * 收窄插件发来的 state：只保留壳协议里公开的字段。
     * 宿主自己构造 state，这里是给「插件回传 state」的防御（M85-A 还没有回传，先留接口）。
     */
    fun sanitizeState(obj: JsonObject): JsonObject = buildJsonObject {
        listOf("chat", "sessions", "settings", "theme", "capabilities").forEach { name ->
            obj[name]?.takeIf { it !is JsonNull }?.let { put(name, it) }
        }
    }
}
