package xin.dshaa.core.workflow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * 上游 `dsh-tools/json-schema` 的 JSON Schema 子集（workflow 的 `agent({schema})` 用）。
 *
 * 支持的约束关键字：`type` / `oneOf` / `properties` / `required` / `additionalProperties` /
 * `items` / `enum` / `const`，以及注解 `description` / `title` / `default` / `examples`。
 * 其它关键字一律**拒绝**（上游宁可不接受也不静默忽略）。
 *
 * structured output 额外要求对象根：`assertObjectSchema` 强制 `type:"object"`。
 */
object WorkflowSchema {

    private val constraint = setOf(
        "type", "oneOf", "properties", "required", "additionalProperties", "items", "enum", "const",
    )
    private val annotations = setOf("description", "title", "default", "examples")
    private val types = setOf("object", "array", "string", "number", "integer", "boolean", "null")
    private val oneOfSiblings = setOf("properties", "required", "additionalProperties", "items", "enum", "const")

    /** 校验 schema 属于子集且根是 object；返回规范化的 schema（就是原对象）。 */
    fun assertObjectSchema(schema: JsonElement): JsonObject {
        val violations = ArrayList<String>()
        checkNode(schema, "schema", violations, HashSet())
        if (violations.isEmpty()) {
            val obj = schema as? JsonObject
            if (obj == null || (obj["type"] as? JsonPrimitive)?.contentOrNull != "object") {
                violations += "schema.type must be \"object\" (structured output is object-rooted)"
            }
        }
        if (violations.isNotEmpty()) {
            throw WorkflowException("agent() schema is outside the supported subset — ${violations.joinToString("; ")}", "UNSUPPORTED_SCHEMA")
        }
        return schema as JsonObject
    }

    /** 校验一个值；null = 通过，否则是给模型看的单条错误。 */
    fun validate(value: JsonElement, schema: JsonObject): String? {
        val errors = ArrayList<String>()
        validateNode(value, schema, "result", errors)
        return errors.firstOrNull()
    }

    private fun checkNode(node: JsonElement, path: String, violations: MutableList<String>, seen: MutableSet<JsonElement>) {
        val obj = node as? JsonObject
        if (obj == null) {
            violations += "$path must be a schema object"
            return
        }
        if (!seen.add(obj)) {
            violations += "$path is circular"
            return
        }
        try {
            obj.keys.forEach { key ->
                if (key in constraint) return@forEach
                if (key in annotations) {
                    if (key == "description" && (obj[key] as? JsonPrimitive)?.isString != true) {
                        violations += "$path.description must be a string"
                    }
                    if (key == "title" && (obj[key] as? JsonPrimitive)?.isString != true) {
                        violations += "$path.title must be a string"
                    }
                    return@forEach
                }
                violations += "$path.$key is not a supported keyword (subset: type/oneOf/properties/required/additionalProperties/items/enum/const + annotations)"
            }
            val hasType = obj.containsKey("type")
            val hasOneOf = obj.containsKey("oneOf")
            if (hasType && hasOneOf) {
                violations += "$path cannot declare both type and oneOf"
                return
            }
            if (!hasType && !hasOneOf) {
                oneOfSiblings.forEach { if (obj.containsKey(it)) violations += "$path.$it requires type or oneOf" }
                return
            }
            if (hasOneOf) {
                val branches = obj["oneOf"] as? JsonArray
                if (branches == null || branches.size < 2) {
                    violations += "$path.oneOf must be an array of at least two schemas"
                }
                oneOfSiblings.forEach { if (obj.containsKey(it)) violations += "$path.$it is not supported beside oneOf" }
                branches?.forEachIndexed { index, branch -> checkNode(branch, "$path.oneOf[$index]", violations, seen) }
                return
            }
            val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (type == null || type !in types) {
                violations += if (obj["type"] is JsonArray) {
                    "$path.type must be a single type string (type arrays are not supported)"
                } else {
                    "$path.type must be one of ${types.joinToString("/")}"
                }
                return
            }
            if (type != "object") {
                setOf("properties", "required", "additionalProperties").forEach {
                    if (obj.containsKey(it)) violations += "$path.$it is not supported on type \"$type\""
                }
            }
            if (type != "array" && obj.containsKey("items")) {
                violations += "$path.items is not supported on type \"$type\""
            }
            if (type !in setOf("string", "number", "integer", "boolean", "null")) {
                if (obj.containsKey("enum")) violations += "$path.enum is not supported on type \"$type\""
                if (obj.containsKey("const")) violations += "$path.const is not supported on type \"$type\""
            }
            when (type) {
                "object" -> {
                    val properties = obj["properties"]
                    if (properties != null && properties !is JsonObject) {
                        violations += "$path.properties must be an object of schemas"
                    }
                    val required = obj["required"]
                    if (required != null) {
                        if (required !is JsonArray || required.any { (it as? JsonPrimitive)?.isString != true }) {
                            violations += "$path.required must be an array of strings"
                        } else {
                            val declared = (properties as? JsonObject)?.keys.orEmpty()
                            required.forEach {
                                val key = (it as JsonPrimitive).content
                                if (key !in declared) violations += "$path.required names \"$key\" which is not in properties"
                            }
                        }
                    }
                    if (obj.containsKey("additionalProperties") && obj["additionalProperties"] !is JsonPrimitive) {
                        violations += "$path.additionalProperties must be a boolean"
                    }
                    (properties as? JsonObject)?.forEach { (key, value) ->
                        checkNode(value, "$path.properties.$key", violations, seen)
                    }
                }
                "array" -> obj["items"]?.let { checkNode(it, "$path.items", violations, seen) }
                else -> {
                    val schemaType = type
                    obj["enum"]?.let { enumValue ->
                        val array = enumValue as? JsonArray
                        if (array == null || array.isEmpty() || array.any { !scalarMatches(schemaType, it) }) {
                            violations += "$path.enum must be a non-empty array of $schemaType values"
                        }
                    }
                    if (obj.containsKey("const") && !scalarMatches(schemaType, obj["const"]!!)) {
                        violations += "$path.const must be a $schemaType value"
                    }
                }
            }
        } finally {
            seen.remove(obj)
        }
    }

    private fun validateNode(value: JsonElement, schema: JsonObject, path: String, errors: MutableList<String>) {
        if (errors.isNotEmpty()) return
        val oneOf = schema["oneOf"] as? JsonArray
        if (oneOf != null) {
            val matching = oneOf.count { branch ->
                val probe = ArrayList<String>()
                validateNode(value, branch as? JsonObject ?: return@count false, path, probe)
                probe.isEmpty()
            }
            if (matching != 1) errors += "$path must match exactly one oneOf branch (matched $matching)"
            return
        }
        val type = (schema["type"] as? JsonPrimitive)?.contentOrNull
        if (type == null) return  // 纯注解 schema：任意 JSON
        if (!scalarMatches(type, value) && type !in setOf("object", "array")) {
            errors += "$path must be of type $type"
            return
        }
        when (type) {
            "object" -> {
                val obj = value as? JsonObject ?: run {
                    errors += "$path must be an object"
                    return
                }
                val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                (schema["required"] as? JsonArray)?.forEach {
                    val key = (it as? JsonPrimitive)?.contentOrNull ?: return@forEach
                    if (!obj.containsKey(key)) errors += "$path.$key is required"
                }
                obj.forEach { (key, child) ->
                    val childSchema = properties[key] as? JsonObject
                    if (childSchema != null) {
                        validateNode(child, childSchema, "$path.$key", errors)
                    } else {
                        val additional = schema["additionalProperties"]
                        if (additional is JsonPrimitive && additional.booleanOrNull == false) {
                            errors += "$path.$key is not allowed (additionalProperties: false)"
                        }
                    }
                }
            }
            "array" -> {
                val array = value as? JsonArray ?: run {
                    errors += "$path must be an array"
                    return
                }
                val items = schema["items"] as? JsonObject ?: return
                array.forEachIndexed { index, item -> validateNode(item, items, "$path[$index]", errors) }
            }
            else -> {
                if (schema.containsKey("enum")) {
                    val allowed = (schema["enum"] as? JsonArray).orEmpty()
                    if (allowed.none { jsonEquals(it, value) }) errors += "$path must be one of ${allowed.joinToString()}"
                }
                if (schema.containsKey("const")) {
                    if (!jsonEquals(schema["const"]!!, value)) errors += "$path must equal ${schema["const"]}"
                }
            }
        }
    }

    private fun scalarMatches(type: String, value: JsonElement): Boolean = when (type) {
        "string" -> value is JsonPrimitive && value.isString
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        "integer" -> {
            val d = (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            d != null && d % 1.0 == 0.0
        }
        "boolean" -> value is JsonPrimitive && value.isString.not() && value.booleanOrNull != null
        "null" -> value is JsonNull
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        else -> false
    }

    private fun jsonEquals(a: JsonElement, b: JsonElement): Boolean =
        a is JsonPrimitive && b is JsonPrimitive && a.isString == b.isString && a.content == b.content
}
