package xin.dshaa.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 插件工具参数的归一化（M27，M29 加 schemastery）。
 *
 * 模型 API 只认 JSON Schema（`{type:"object", properties:{…}, required:[…]}`），
 * 而插件里能见到四种写法：
 * 1. **标准 JSON Schema**（DSH 的 `defineTool` / 手写 `parameters`，也是 `dsh-context-handoff` 的写法）→ 原样使用；
 * 2. **ToolPkg 简写**（`docs/04` 里写的 `{ city: { type:'string', required:true } }`）→ 包一层 object；
 * 3. **schemastery 的 `z.object(...)`** → 试运行时由宿主的最小 `z` 序列化成上游的 `{uid,refs}` 图，
 *    这里交给 [SchemasterySchema] 翻译；认不出的类型仍然不注入，不猜；
 * 4. 认不出的形状 → null（插件页会写「参数认不出，未接入」）。
 *
 * 这个类只做「能不能用」的判断和最小包装，不做完整 JSON Schema 校验——真正的 schema
 * 由插件作者给出，宿主只保证形状能被 API 接受。
 */
object PluginSchema {

    /** 解析 `files/plugins.json` 里存的 schema 文本；坏 JSON / 非对象返回 null。 */
    fun parse(text: String?): JsonObject? {
        if (text.isNullOrBlank()) return null
        return runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()?.let { normalize(it) }
    }

    /** 把各种写法归一成 API 能接受的 JSON Schema；认不出返回 null。 */
    fun normalize(raw: JsonObject?): JsonObject? {
        if (raw == null) return emptyObjectSchema()

        // 0. schemastery 的序列化图（{uid, refs}）：翻成 JSON Schema；认不出返回 null。
        if (SchemasterySchema.looksLike(raw)) return SchemasterySchema.toJsonSchema(raw)

        // 1. 已经是 JSON Schema：有 type / properties / 组合关键字 / $ref 之一就原样用。
        if (raw.containsKey("type") || raw.containsKey("properties") || raw.containsKey("\$ref") ||
            raw.containsKey("oneOf") || raw.containsKey("anyOf") || raw.containsKey("allOf")
        ) {
            return raw
        }

        // 2. ToolPkg 简写：每个值都是 { type: ... }；required:true 提到顶层 required 数组。
        if (raw.isEmpty()) return emptyObjectSchema()
        val properties = LinkedHashMap<String, JsonObject>()
        val required = ArrayList<String>()
        for ((name, value) in raw) {
            val obj = value as? JsonObject ?: return null
            if (!obj.containsKey("type") && !obj.containsKey("\$ref")) return null
            properties[name] = buildJsonObject {
                obj.forEach { (key, element) ->
                    // ToolPkg 的 required 是字段上的布尔，不是 JSON Schema 关键字
                    if (key != "required") put(key, element)
                }
            }
            val flag = obj["required"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            if (flag == "true") required += name
        }
        if (properties.isEmpty()) return null
        return buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(properties))
            if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        }
    }

    /**
     * 按 schema 给配置/工具参数补默认值（M30）。
     *
     * 语义照 `@deepseek-ai/schemastery` 的 `Schema.resolve`（`docs/52` 的对照脚本验证过）：
     * - 标量缺省且 schema 有 `default` → 用 default；没有就**不出现**（插件读到 undefined）；
     * - object 缺省 → `{}`，再递归补内层默认值；
     * - array 缺省 → `[]`（schemastery 的 array 默认值是空数组）；
     * - 已经给了值的对象/数组只递归补缺项，不覆盖用户/模型给的值。
     *
     * 只处理 `properties` / `items` / `default` 这三个关键字；不认识的组合（anyOf/oneOf）原样保留，
     * 不做校验（校验是下一轮和参数检查一起做的事）。
     */
    fun withDefaults(schema: JsonObject, value: JsonObject?): JsonObject =
        fillObject(schema, value ?: JsonObject(emptyMap()))

    /** 算「全默认配置」；schema 认不出时返回 `{}`。 */
    fun defaultsText(schemaText: String?): String {
        val schema = parse(schemaText) ?: return "{}"
        return withDefaults(schema, JsonObject(emptyMap())).toString()
    }

    /**
     * 合并「清单默认值 + 用户配置 + schema 默认值」（执行插件 / 详情展示都用这一份）。
     *
     * [defaultsText] 是 ToolPkg manifest 的 `config.default`（M32）；DSH 插件没有，传 null 即可。
     * 顺序：manifest 默认 → 用户配置（深合并）→ schema 里剩余的默认值。
     */
    fun effectiveText(schemaText: String?, configText: String?, defaultsText: String? = null): String {
        val config = parseObject(configText)
        val schema = parse(schemaText) ?: return config.toString()
        val merged = mergeObjects(parseObject(defaultsText), config)
        return withDefaults(schema, merged).toString()
    }

    /** 配置 JSON 文本 → 对象；坏 JSON 当空配置。 */
    fun parseObject(text: String?): JsonObject {
        if (text.isNullOrBlank()) return JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
    }

    /**
     * 按 schema 把配置里不认识的键裁掉（M37 热重载：清单换了 schema，旧键不能一直带着）。
     *
     * schema 认不出（没有 properties）时原样返回——「不认识这个 schema」不等于「什么键都不认识」。
     */
    fun prune(schemaText: String?, configText: String?): String {
        val config = parseObject(configText)
        val keys = configKeys(schemaText) ?: return config.toString()
        return JsonObject(config.filterKeys { it in keys }).toString()
    }

    /**
     * 工具参数校验（M30）：只查 `required` / `type` / `enum` / `properties` / `items` 这几个关键字。
     *
     * 为什么不做完整 JSON Schema：插件的参数是作者给的，宿主只挡「明显不对」的调用（缺必填、类型错、枚举外），
     * 不为了一个 corner case 把合法调用拒掉。返回 null = 通过；否则是给模型/用户看的一句话。
     */
    fun validate(schema: JsonObject, args: JsonObject): String? = validateValue(schema, args, "")

    private fun validateValue(schema: JsonObject, value: JsonElement, path: String): String? {
        val label = path.ifEmpty { "参数" }
        val enumValues = schema["enum"] as? JsonArray
        if (enumValues != null && value !in enumValues) {
            return "$label 不在允许的值里：${enumValues.joinToString("、") { it.toString() }}"
        }
        val type = schema.string("type")
        if (type != null && !matchesType(type, value)) {
            return "$label 类型不对：要 $type，实际是 ${value.typeName()}"
        }
        val primitive = value as? JsonPrimitive
        if (primitive != null && !primitive.isString) {
            val number = primitive.doubleOrNull
            if (number != null) {
                schema.finiteDouble("minimum")?.let { min ->
                    if (number < min) return "$label 不能小于 ${formatNumber(min)}"
                }
                schema.finiteDouble("maximum")?.let { max ->
                    if (number > max) return "$label 不能大于 ${formatNumber(max)}"
                }
            }
        }
        if (primitive != null && primitive.isString) {
            val length = primitive.content.length
            schema.finiteInt("minLength")?.let { min ->
                if (length < min) return "$label 至少 $min 个字符"
            }
            schema.finiteInt("maxLength")?.let { max ->
                if (length > max) return "$label 最多 $max 个字符"
            }
            // JSON Schema 的 pattern 是 ECMA 正则；写成坏正则时忽略（不因为 schema 写错拒掉合法值）。
            schema.string("pattern")?.takeIf { it.isNotBlank() }?.let { pattern ->
                val regex = runCatching { Regex(pattern) }.getOrNull()
                if (regex != null && !regex.containsMatchIn(primitive.content)) {
                    return "$label 不匹配格式：$pattern"
                }
            }
        }
        if (value is JsonObject) {
            val required = schema["required"] as? JsonArray
            if (required != null) {
                for (item in required) {
                    val name = (item as? JsonPrimitive)?.contentOrNull ?: continue
                    if (!value.containsKey(name) || value[name] is kotlinx.serialization.json.JsonNull) {
                        return "缺少必填参数：${path.prefix()}$name"
                    }
                }
            }
            val properties = schema["properties"] as? JsonObject
            if (properties != null) {
                for ((name, element) in properties) {
                    val childSchema = element as? JsonObject ?: continue
                    val childValue = value[name] ?: continue
                    validateValue(childSchema, childValue, path.child(name))?.let { return it }
                }
            }
        }
        if (value is JsonArray) {
            schema.finiteInt("minItems")?.let { min ->
                if (value.size < min) return "$label 至少 $min 项"
            }
            schema.finiteInt("maxItems")?.let { max ->
                if (value.size > max) return "$label 最多 $max 项"
            }
            val items = schema["items"] as? JsonObject
            if (items != null) {
                value.forEachIndexed { index, item ->
                    validateValue(items, item, "${path.child(index.toString())}")?.let { return it }
                }
            }
        }
        return null
    }

    private fun matchesType(type: String, value: JsonElement): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && value.contentOrNull in setOf("true", "false")
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
        "null" -> value is kotlinx.serialization.json.JsonNull
        else -> true
    }

    private fun JsonObject.finiteDouble(key: String): Double? {
        val number = (this[key] as? JsonPrimitive)?.doubleOrNull ?: return null
        if (number.isNaN() || number.isInfinite()) return null
        return number
    }

    private fun JsonObject.finiteInt(key: String): Int? {
        val number = (this[key] as? JsonPrimitive)?.doubleOrNull ?: return null
        if (number.isNaN() || number.isInfinite()) return null
        return number.toInt()
    }

    private fun formatNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun JsonElement.typeName(): String = when (this) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        is kotlinx.serialization.json.JsonNull -> "null"
        is JsonPrimitive -> when {
            isString -> "string"
            contentOrNull in setOf("true", "false") -> "boolean"
            else -> "number"
        }
    }

    private fun String.prefix(): String = if (isEmpty()) "" else "$this."
    private fun String.child(name: String): String = if (isEmpty()) name else "$this.$name"

    /** 配置 schema 里认识的键（给「手改配置」做白名单；没有 schema 返回 null = 不限制）。 */
    fun configKeys(schemaText: String?): Set<String>? {
        val schema = parse(schemaText) ?: return null
        val properties = schema["properties"] as? JsonObject ?: return null
        return properties.keys
    }

    /** 深合并两个 JSON 对象：对象递归合并，其他值用 [override] 覆盖。 */
    private fun mergeObjects(base: JsonObject, override: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(base)
        for ((key, value) in override) {
            val existing = out[key]
            out[key] = if (existing is JsonObject && value is JsonObject) {
                mergeObjects(existing, value)
            } else {
                value
            }
        }
        return JsonObject(out)
    }

    private fun fillObject(schema: JsonObject, value: JsonObject): JsonObject {        val properties = schema["properties"] as? JsonObject ?: return value
        val out = LinkedHashMap<String, JsonElement>(value)
        for ((name, element) in properties) {
            val property = element as? JsonObject ?: continue
            val current = value[name]
            if (current != null) {
                out[name] = fillValue(property, current)
            } else {
                fillMissing(property)?.let { out[name] = it }
            }
        }
        return JsonObject(out)
    }

    /** 缺省值：显式 default 优先；object/array 按 schemastery 语义补空容器。 */
    private fun fillMissing(schema: JsonObject): JsonElement? {
        schema["default"]?.let { return it }
        return when (schema.string("type")) {
            "object" -> fillObject(schema, JsonObject(emptyMap()))
            "array" -> JsonArray(emptyList())
            "dict" -> JsonObject(emptyMap())
            else -> null
        }
    }

    /** 已有值：对象/数组向下递归补缺，标量原样返回（不覆盖）。 */
    private fun fillValue(schema: JsonObject, value: JsonElement): JsonElement = when (schema.string("type")) {
        "object" -> (value as? JsonObject)?.let { fillObject(schema, it) } ?: value
        "array" -> {
            val items = schema["items"] as? JsonObject
            if (value is JsonArray && items != null) {
                JsonArray(value.map { item -> if (item is JsonObject) fillObject(items, item) else item })
            } else {
                value
            }
        }
        else -> value
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    /** 没有参数的工具：空 object schema（模型可以不带参数调用）。 */
    fun emptyObjectSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(emptyMap()))
    }
}
