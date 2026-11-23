package xin.dshaa.core.primitives

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * P1（`docs/185` §四/§九）：原语层的**单一目录**。
 *
 * 一个原语只在这里定义一次（id / 说明 / 参数 JSON Schema / 风险 / 可用性 / 审批），
 * 三方面都从这一份定义生成：
 * - AI 工具面：`core:tools` 的 `PrimitiveTool` + `core:prompt` 的 `androidApiTools()`；
 * - 插件桥面：`ctx.primitive.invoke` / `ctx.primitive.list`（`core:plugins` 的 `PluginSandbox`）；
 * - 原生调用面：任何持有 [PrimitiveHost] 的 Kotlin 代码（App 层）。
 *
 * **不许**为某一面手写第二份 schema/参数校验——加原语时只改 [PrimitiveCatalog.definitions]。
 */
data class PrimitiveDefinition(
    /** 稳定 id，例如 `session.fork`（ANSI 小写 + 点）。 */
    val id: String,
    /** 分组：session / topic / model / turn / tool / store / ui。 */
    val group: String,
    /** 一行中文标题（文档/插件能力清单用；模型看到的是 [description]）。 */
    val title: String,
    /** 给模型/插件看的英文说明（工具面 description，逐字进请求体）。 */
    val description: String,
    /** JSON Schema（`type:object` + properties/required）；工具面原样发出。 */
    val parameters: JsonObject,
    val risk: PrimitiveRisk,
    /** 工具审批策略；[PrimitiveApproval.ASK] 的原语不在 `READ_ONLY_TOOLS` 里。 */
    val approval: PrimitiveApproval = PrimitiveApproval.FREE,
    val availability: PrimitiveAvailability = PrimitiveAvailability.READY,
    /**
     * 是否开放给插件桥面。P1 只开只读原语（[PrimitiveRisk.READ]）：
     * 写会话/改设置需要等 P4 的 `primitive` 权限模型，不能靠插件清单静默获得。
     */
    val bridgeVisible: Boolean =
        availability == PrimitiveAvailability.READY && risk == PrimitiveRisk.READ,
    /** 跨字段校验（类型/必填/枚举由 [PrimitiveArgs.validate] 先查）；返回 null = 通过。 */
    val validate: ((JsonObject) -> String?)? = null,
)

/** 原语的影响面；决定默认审批和插件桥可见性。 */
enum class PrimitiveRisk {
    /** 只读（不改会话、不改设置、不写工作区）。 */
    READ,

    /** 改会话/话题（fork / 摘要 / 切话题 / 归档 / 起停轮次）。 */
    SESSION,

    /** 改模型/推理强度/审批策略等设置。 */
    SETTINGS,

    /** 能执行任意工具、能降审批：模型调用前必须用户确认。 */
    DANGEROUS,

    /** 界面（P3 容器；当前只登记、不实现）。 */
    UI,
}

enum class PrimitiveApproval { FREE, ASK }

enum class PrimitiveAvailability {
    READY,

    /** `docs/185` §九 P3：宿主能力扩展（三种容器）。 */
    P3,

    /** `docs/185` §九 P4：导入与 UI。 */
    P4,
}

/** 调用方：同一动作不同来源的额外纪律（例如模型不能停自己的轮次）。 */
enum class PrimitiveCaller { TOOL, PLUGIN, NATIVE }

/** 原语执行结果：给 AI 的文本摘要 + 给插件/原生的结构化数据。 */
data class PrimitiveResult(
    val summary: String,
    val data: JsonObject = JsonObject(emptyMap()),
    val isError: Boolean = false,
    val code: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("ok", !isError)
        put("summary", summary)
        put("data", data)
        if (code != null) put("code", code)
        if (isError) put("error", summary)
    }

    companion object {
        fun ok(summary: String, data: JsonObject = JsonObject(emptyMap())) = PrimitiveResult(summary, data)

        fun error(summary: String, code: String? = null) =
            PrimitiveResult(summary, isError = true, code = code)

        /** 结构化成功结果的便捷构造（插件/原生面用）。 */
        fun of(summary: String, data: Map<String, String>) = PrimitiveResult(
            summary,
            buildJsonObject { data.forEach { (k, v) -> put(k, v) } },
        )
    }
}

/**
 * 原生调用面：App 层实现一次，工具面与插件面都只转调它。
 *
 * [invoke] 收到的是**已通过 [PrimitiveArgs.validate] 的参数**；实现仍要防御性检查，
 * 并把可预期的失败翻成 [PrimitiveResult.error]（不把异常栈甩给模型/插件）。
 */
interface PrimitiveHost {
    fun currentTopicId(): String?

    suspend fun invoke(id: String, args: JsonObject): PrimitiveResult
}

/** 一段会话区间：两端都可省略（省略 = 该侧不限），按事件 seq 闭区间。 */
data class PrimitiveRange(val fromSeq: Long? = null, val toSeq: Long? = null) {
    init {
        require(fromSeq == null || fromSeq >= 0) { "from_seq must be >= 0" }
        require(toSeq == null || toSeq >= 0) { "to_seq must be >= 0" }
        require(fromSeq == null || toSeq == null || fromSeq <= toSeq) { "from_seq must be <= to_seq" }
    }

    fun contains(seq: Long?): Boolean {
        if (seq == null) return fromSeq == null && toSeq == null
        if (fromSeq != null && seq < fromSeq) return false
        if (toSeq != null && seq > toSeq) return false
        return true
    }
}

/**
 * 参数校验（三面共用）：必填、JSON 类型、enum、跨字段 [PrimitiveDefinition.validate]。
 *
 * 刻意不引入 JSON Schema 库：上游工具的 schema 也只用到 `type/properties/required/enum/
 * minimum/maximum` 这一小撮，手写足够且没有额外依赖。
 */
object PrimitiveArgs {
    private val json = Json { ignoreUnknownKeys = true }

    fun validate(definition: PrimitiveDefinition, args: JsonObject): String? {
        val properties = definition.parameters["properties"]?.asObject() ?: JsonObject(emptyMap())
        val required = definition.parameters["required"]?.asStringList().orEmpty()
        for (name in required) {
            val value = args[name]
            if (value == null || value is JsonNull) return "missing required parameter: $name"
        }
        for ((name, value) in args) {
            if (value is JsonNull) continue
            val spec = properties[name]?.asObject() ?: continue
            val type = spec["type"]?.asString()
            val typeError = when (type) {
                "string" -> if (!(value is JsonPrimitive && value.isString)) {
                    "parameter $name must be a string"
                } else null
                "integer" -> if (!(value is JsonPrimitive && !value.isString && value.longOrNull != null)) {
                    "parameter $name must be an integer"
                } else null
                "boolean" -> if (!(value is JsonPrimitive && value.jsonBooleanOrNull() != null)) {
                    "parameter $name must be a boolean"
                } else null
                "object" -> if (value !is JsonObject) "parameter $name must be an object" else null
                "array" -> if (value !is JsonArray) "parameter $name must be an array" else null
                else -> null
            }
            if (typeError != null) return typeError
            val allowed = spec["enum"]?.asStringList()
            if (allowed != null) {
                val raw = (value as? JsonPrimitive)?.contentOrNull
                if (raw == null || raw !in allowed) {
                    return "parameter $name must be one of ${allowed.joinToString("/")}"
                }
            }
        }
        return definition.validate?.invoke(args)
    }

    fun string(args: JsonObject, name: String): String? = runCatching {
        val primitive = args[name] as? JsonPrimitive ?: return@runCatching null
        if (primitive.isString) primitive.content else null
    }.getOrNull()

    fun long(args: JsonObject, name: String): Long? = runCatching {
        val primitive = args[name] as? JsonPrimitive ?: return@runCatching null
        if (primitive.isString) null else primitive.longOrNull
    }.getOrNull()

    fun int(args: JsonObject, name: String): Int? = long(args, name)?.toInt()

    fun bool(args: JsonObject, name: String, default: Boolean = false): Boolean = runCatching {
        val primitive = args[name] as? JsonPrimitive ?: return@runCatching default
        if (primitive.isString) default else primitive.jsonBooleanOrNull() ?: default
    }.getOrDefault(default)

    fun objectArg(args: JsonObject, name: String): JsonObject? =
        runCatching { (args[name] as? JsonObject) }.getOrNull()

    fun list(args: JsonObject, name: String): List<String> =
        runCatching { args[name]?.asStringList().orEmpty() }.getOrDefault(emptyList())

    /** 测试/调试用：解析 JSON 文本成对象；坏 JSON 返回 null。 */
    fun parse(text: String): JsonObject? = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
}

/** 插件桥的 JSON 形状（`{ok,summary,data,code}`），与 [PrimitiveResult.toJson] 同形。 */
object PrimitiveBridgeJson {
    fun reply(result: PrimitiveResult): String = result.toJson().toString()

    fun error(message: String, code: String): String =
        PrimitiveResult.error(message, code).toJson().toString()

    fun list(definitions: List<PrimitiveDefinition>): String = buildJsonObject {
        put("ok", true)
        put(
            "primitives",
            buildJsonArray {
                definitions.forEach { definition ->
                    add(
                        buildJsonObject {
                            put("id", definition.id)
                            put("group", definition.group)
                            put("title", definition.title)
                            put("description", definition.description)
                            put("risk", definition.risk.name.lowercase())
                            put("availability", definition.availability.name.lowercase())
                            put("bridge", definition.bridgeVisible)
                            put("approval", definition.approval.name.lowercase())
                        },
                    )
                }
            },
        )
    }.toString()
}

// ---- JSON 小工具（三面共用；不散落在各模块） ----

internal fun JsonElement?.asObject(): JsonObject? = runCatching { this?.jsonObject }.getOrNull()

internal fun JsonElement?.asString(): String? = runCatching {
    val primitive = this as? JsonPrimitive ?: return@runCatching null
    if (primitive.isString) primitive.content else null
}.getOrNull()

internal fun JsonElement?.asStringList(): List<String>? = runCatching {
    (this as? JsonArray)?.mapNotNull { element ->
        val primitive = element as? JsonPrimitive ?: return@mapNotNull null
        if (primitive.isString) primitive.content else null
    }
}.getOrNull()

internal fun JsonPrimitive.jsonBooleanOrNull(): Boolean? = runCatching {
    if (isString) null else contentOrNull?.toBooleanStrictOrNull()
}.getOrNull()
