package xin.dshaa.core.plugins

import kotlinx.serialization.json.*

/** loading/v1 is data only: no runtime, host actions, file paths or browser content. */
data class PluginLoading(val id: String, val label: String, val treeJson: String)

data class LoadingParseResult(val loading: PluginLoading? = null, val error: String? = null)

object LoadingSpec {
    const val VERSION = "loading/v1"
    const val MAX_BYTES = 32 * 1024
    const val MAX_DECLARATIONS = 8
    const val MAX_NODES = 128
    const val MAX_DEPTH = 12
    val primitives = setOf("column", "row", "stack", "text", "progress", "icon", "spacer", "divider")
    val bindings = setOf("boot.phase", "boot.message", "boot.elapsed")
    private val containers = setOf("column", "row", "stack")
    private val common = setOf("type", "padding", "gap", "background", "corner", "align")
    private val palette = setOf("background", "surface", "brand", "text", "sub", "faint", "warn")
    private val idPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$")

    fun parse(element: JsonElement): LoadingParseResult = runCatching {
        require(element.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "加载页声明超过 32 KiB" }
        val obj = element as? JsonObject ?: error("加载页声明必须是对象")
        require(obj.keys.all { it in setOf("id", "label", "tree") }) { "加载页声明有未知字段" }
        val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        require(idPattern.matches(id)) { "加载页 id 不合法" }
        val label = obj["label"]?.let { value ->
            require(value is JsonPrimitive && value.isString) { "加载页 label 必须是字符串" }
            value.content.trim()
        }.orEmpty().ifBlank { id }
        require(label.length <= 120) { "加载页 label 超过 120 字符" }
        var nodes = 0
        fun node(value: JsonElement, depth: Int): JsonObject {
            require(depth <= MAX_DEPTH && ++nodes <= MAX_NODES) { "加载页树过深或节点过多" }
            val raw = value as? JsonObject ?: error("加载页节点必须是对象")
            val type = (raw["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            require(type in primitives) { "加载页不允许原语：$type" }
            val allowed = common + when (type) {
                "column", "row", "stack" -> setOf("children")
                "text" -> setOf("text", "bind", "size", "color", "weight")
                "progress" -> setOf("value", "color")
                "icon" -> setOf("name", "size", "color")
                "spacer" -> setOf("size")
                else -> emptySet()
            }
            require(raw.keys.all { it in allowed }) { "加载页 $type 有未知或可执行字段" }
            return buildJsonObject {
                put("type", type)
                raw.forEach { (key, item) ->
                    if (key == "type") return@forEach
                    when (key) {
                        "children" -> {
                            val children = item as? JsonArray ?: error("children 必须是数组")
                            put(key, JsonArray(children.map { node(it, depth + 1) }))
                        }
                        "bind" -> {
                            require(item is JsonPrimitive && item.isString && item.content in bindings) { "加载页只允许 boot.phase/message/elapsed 绑定" }
                            put(key, item)
                        }
                        "text" -> {
                            require(item is JsonPrimitive && item.isString && item.content.length <= 2000) { "加载页 text 必须是至多 2000 字符的文本" }
                            put(key, item)
                        }
                        "color", "background" -> {
                            require(item is JsonPrimitive && item.isString && item.content in palette) { "加载页颜色必须使用主题 token" }
                            put(key, item)
                        }
                        "align" -> {
                            require(item is JsonPrimitive && item.isString && item.content in setOf("start", "center", "end")) { "加载页 align 不合法" }
                            put(key, item)
                        }
                        "weight" -> {
                            require(item is JsonPrimitive && item.isString && item.content in setOf("normal", "medium", "bold")) { "加载页 weight 不合法" }
                            put(key, item)
                        }
                        "name" -> {
                            require(item is JsonPrimitive && item.isString && item.content in setOf("whale", "hourglass", "extension", "info")) { "加载页图标名不支持" }
                            put(key, item)
                        }
                        else -> {
                            val number = (item as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
                            require(number != null && number.isFinite()) { "加载页 $key 必须是有限数值" }
                            val range = when (key) {
                                "value" -> 0.0..1.0
                                "size" -> if (type == "text") 10.0..48.0 else 0.0..96.0
                                else -> 0.0..96.0
                            }
                            require(number in range) { "加载页 $key 超出范围" }
                            put(key, item)
                        }
                    }
                }
                if (type in containers && !raw.containsKey("children")) put("children", JsonArray(emptyList()))
            }
        }
        val tree = node(obj["tree"] ?: error("加载页缺少 tree"), 0)
        val loading = PluginLoading(id, label, tree.toString())
        // Defaults/canonical container children may grow the serialized form; every accepted
        // declaration must remain valid after registry/snapshot round trips.
        require(declaration(loading).toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "加载页规范声明超过 32 KiB" }
        LoadingParseResult(loading)
    }.getOrElse { LoadingParseResult(error = it.message ?: "加载页声明不合法") }

    /** Registry/snapshot reads validate the complete static declaration again. */
    fun parseAll(element: JsonElement?): List<PluginLoading> = (element as? JsonArray).orEmpty()
        .take(MAX_DECLARATIONS).mapNotNull { parse(it).loading }.distinctBy { it.id }

    fun declaration(loading: PluginLoading): JsonObject = buildJsonObject {
        put("id", loading.id)
        put("label", loading.label)
        put("tree", Json.parseToJsonElement(loading.treeJson))
    }
}
