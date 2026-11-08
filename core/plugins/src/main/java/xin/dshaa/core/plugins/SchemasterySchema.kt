package xin.dshaa.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.math.floor

/**
 * schemastery 序列化图 → JSON Schema（M29）。
 *
 * 为什么不是「把 z 当函数硬猜」：schemastery 的 schema 是函数对象，`JSON.stringify` 拿不到类型；
 * 上游用 `toJSON()` 把它序列化成 **uid + refs 的图**（`{uid:0, refs:{"0":{type:"object",dict:{…}}}}`）。
 * 插件里 `import z from '@deepseek-ai/schemastery'` 的那份 `z` 由宿主 [PluginModule] 提供
 * （最小实现，序列化格式与上游 3.18.2 逐字段对齐，见 `docs/52` 的对照测试），
 * 这里只负责把图翻译成模型 API 认识的 JSON Schema。
 *
 * 取舍（宁可少接一个工具，也不能给模型一个假参数定义）：
 * - 只翻译能一一对应的类型：object / dict / array / string / number / boolean / const / union / intersect / any / lazy；
 * - `transform` / `is` / `bitset` / `function` / `tuple` / `date` 认不出 → 整个 schema 返回 null（不注入）；
 * - 显式默认值写成 JSON Schema 的 `default`（M30 起宿主会真的补默认值）；隐式空对象/空数组默认不写；
 *   `meta.description` 会带上；
 * - `object` 不写 `additionalProperties:false`：schemastery 默认会把未知键合并回来，写 false 反而更严。
 *
 * 深度上限 12：lazy 递归 schema 可以在图里自引用，超过就当认不出，别把栈跑炸。
 */
object SchemasterySchema {

    private const val MAX_DEPTH = 12

    /** 是不是 schemastery 的 `toJSON()` 结果：同时有 `uid` 和 `refs`。 */
    fun looksLike(raw: JsonObject): Boolean =
        raw["refs"] is JsonObject && raw["uid"] != null

    /** 转换；根必须是 object（tool 参数的形状），认不出返回 null。 */
    fun toJsonSchema(raw: JsonObject): JsonObject? {
        val refs = raw["refs"] as? JsonObject ?: return null
        val nodes = LinkedHashMap<String, JsonObject>()
        for ((id, value) in refs) {
            nodes[id] = value as? JsonObject ?: continue
        }
        val rootId = (raw["uid"] as? JsonPrimitive)?.contentOrNull ?: return null
        val root = nodes[rootId] ?: return null
        val converted = convert(root, nodes, 0) ?: return null
        if (converted.string("type") != "object") return null
        return converted
    }

    /** 给调试/单测用：一份 schemastery JSON 文本 → JSON Schema。 */
    fun convertText(text: String): JsonObject? = runCatching {
        val raw = Json.parseToJsonElement(text).jsonObject
        if (looksLike(raw)) toJsonSchema(raw) else null
    }.getOrNull()

    private fun convert(node: JsonObject, nodes: Map<String, JsonObject>, depth: Int): JsonObject? {
        if (depth > MAX_DEPTH) return null
        val meta = node["meta"] as? JsonObject
        return when (node.string("type")) {
            "object" -> objectSchema(node, nodes, depth, meta)
            "dict" -> {
                val inner = resolve(node["inner"], nodes)?.let { convert(it, nodes, depth + 1) } ?: return null
                buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", inner)
                    putDescription(meta)
                    putDefault(meta, "dict")
                }
            }
            "array" -> {
                val inner = resolve(node["inner"], nodes)?.let { convert(it, nodes, depth + 1) } ?: return null
                buildJsonObject {
                    put("type", "array")
                    put("items", inner)
                    putDescription(meta)
                    putDefault(meta, "array")
                }
            }
            "string" -> buildJsonObject {
                put("type", "string")
                meta?.finiteInt("min")?.let { put("minLength", it) }
                meta?.finiteInt("max")?.let { put("maxLength", it) }
                putDescription(meta)
                putDefault(meta, "string")
            }
            "number" -> buildJsonObject {
                put("type", "number")
                meta?.finiteDouble("min")?.let { putNumber("minimum", it) }
                meta?.finiteDouble("max")?.let { putNumber("maximum", it) }
                putDescription(meta)
                putDefault(meta, "number")
            }
            "boolean" -> buildJsonObject {
                put("type", "boolean")
                putDescription(meta)
                putDefault(meta, "boolean")
            }
            "const" -> {
                val value = node["value"] ?: return null
                buildJsonObject {
                    put("enum", buildJsonArray { add(value) })
                    putDescription(meta)
                    putDefault(meta, "const")
                }
            }
            "union" -> unionSchema(node, nodes, depth, meta)
            "intersect" -> intersectSchema(node, nodes, depth, meta)
            // JSON Schema 的布尔 schema：任何值都合法。放在 properties 里可以接受。
            "any" -> buildJsonObject {
                putDescription(meta)
                putDefault(meta, "any")
            }
            // 递归 schema：上游序列化时已经点开了内层，直接翻。
            "lazy" -> resolve(node["inner"], nodes)?.let { convert(it, nodes, depth + 1) }
            else -> null
        }
    }

    private fun objectSchema(
        node: JsonObject,
        nodes: Map<String, JsonObject>,
        depth: Int,
        meta: JsonObject?,
    ): JsonObject? {
        val dict = node["dict"] as? JsonObject ?: JsonObject(emptyMap())
        val properties = LinkedHashMap<String, JsonObject>()
        val required = ArrayList<String>()
        for ((name, element) in dict) {
            val child = resolve(element, nodes) ?: return null
            properties[name] = convert(child, nodes, depth + 1) ?: return null
            if (child.required()) required += name
        }
        return buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(properties))
            if (required.isNotEmpty()) {
                put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            }
            putDescription(meta)
            putDefault(meta, "object")
        }
    }

    private fun unionSchema(
        node: JsonObject,
        nodes: Map<String, JsonObject>,
        depth: Int,
        meta: JsonObject?,
    ): JsonObject? {
        val list = node["list"] as? JsonArray ?: return null
        if (list.isEmpty()) return null
        val converted = list.map { element ->
            val child = resolve(element, nodes) ?: return null
            convert(child, nodes, depth + 1) ?: return null
        }
        // 全是 const → enum（模型对 enum 最熟）；否则用 anyOf。
        if (converted.all { it.containsKey("enum") }) {
            val values = converted.flatMap { it["enum"]!!.jsonArray }
            return buildJsonObject {
                put("enum", JsonArray(values))
                putDescription(meta)
                putDefault(meta, "union")
            }
        }
        return buildJsonObject {
            put("anyOf", JsonArray(converted))
            putDescription(meta)
            putDefault(meta, "union")
        }
    }

    private fun intersectSchema(
        node: JsonObject,
        nodes: Map<String, JsonObject>,
        depth: Int,
        meta: JsonObject?,
    ): JsonObject? {
        val list = node["list"] as? JsonArray ?: return null
        if (list.isEmpty()) return null
        val properties = LinkedHashMap<String, JsonObject>()
        val required = LinkedHashSet<String>()
        for (element in list) {
            val child = resolve(element, nodes) ?: return null
            val converted = convert(child, nodes, depth + 1) ?: return null
            if (converted.string("type") != "object") return null
            for ((name, value) in converted["properties"] as? JsonObject ?: JsonObject(emptyMap())) {
                properties[name] = value as? JsonObject ?: return null
            }
            (converted["required"] as? JsonArray)?.forEach { item ->
                (item as? JsonPrimitive)?.contentOrNull?.let { required += it }
            }
        }
        return buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(properties))
            if (required.isNotEmpty()) {
                put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            }
            putDescription(meta)
            putDefault(meta, "intersect")
        }
    }

    /** 图里嵌套 schema 用 uid 数字引用；手工写的内联对象也认。 */
    private fun resolve(element: JsonElement?, nodes: Map<String, JsonObject>): JsonObject? =
        when (element) {
            is JsonObject -> element
            is JsonPrimitive -> element.contentOrNull?.let { nodes[it] }
            else -> null
        }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.required(): Boolean =
        ((this["meta"] as? JsonObject)?.get("required") as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject.finiteDouble(key: String): Double? {
        val number = (this[key] as? JsonPrimitive)?.doubleOrNull ?: return null
        if (number.isNaN() || number.isInfinite()) return null
        return number
    }

    private fun JsonObject.finiteInt(key: String): Int? {
        val primitive = (this[key] as? JsonPrimitive) ?: return null
        primitive.intOrNull?.let { return it }
        val number = primitive.doubleOrNull ?: return null
        if (number.isNaN() || number.isInfinite() || number != floor(number)) return null
        return number.toInt()
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putDescription(meta: JsonObject?) {
        val text = meta?.descriptionText() ?: return
        put("description", text)
    }

    /**
     * 写 `default`（M30）：宿主已经会按 schema 补默认值，所以这里可以如实写。
     *
     * 跳过 schemastery 自动加的「空对象/空数组」默认（`z.object` 的 `{}`、`z.array` 的 `[]`）：
     * 那是构造器的隐式行为，不是作者显式配置的默认值，写进模型参数里只会让 schema 变吵。
     * 显式 `.default({...})` / `.default([...])` 非空，照写。
     */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putDefault(meta: JsonObject?, type: String) {
        val value = meta?.get("default") ?: return
        if (value is JsonObject && value.isEmpty()) return
        if (value is JsonArray && value.isEmpty()) return
        put("default", value)
    }

    /** 整数就写成整数（`0` 不是 `0.0`），和 DSH 手写 schema 的观感一致。 */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putNumber(key: String, value: Double) {
        val element = if (value == floor(value) && value >= -9.0E15 && value <= 9.0E15) {
            JsonPrimitive(value.toLong())
        } else {
            JsonPrimitive(value)
        }
        put(key, element)
    }

    /** `meta.description` 可能是字符串，也可能是 `{"":"…","zh":"…"}` 的 i18n 对象。 */
    private fun JsonObject.descriptionText(): String? {
        val raw = this["description"] ?: return null
        val text = when (raw) {
            is JsonPrimitive -> raw.contentOrNull
            is JsonObject ->
                sequenceOf("", "zh", "zh-CN", "en")
                    .mapNotNull { (raw[it] as? JsonPrimitive)?.contentOrNull }
                    .firstOrNull()
                    ?: raw.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> null
        }
        return text?.trim()?.takeIf { it.isNotEmpty() }
    }
}
