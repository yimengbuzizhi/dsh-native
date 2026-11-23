package xin.dshaa.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P3-d（`docs/187` §9.2）：**预设**是纯数据对象 —— 模型 + 提示词段 + 工具 + 权限的组合。
 *
 * 三条纪律（写进类型里，不靠调用方自觉）：
 * 1. **不进 session.v4**：预设只落在 `files/node/data/presets.json`，会话格式一个字节都不动；
 * 2. **不写死某个插件**：所有字段都是「id 列表」或「插件 id → 权限名」映射，
 *    没有任何插件专属分支（D9）；换插件/换包不需要改代码；
 * 3. **套用是「选择」不是「改写」**：套用只把当下宿主的模型/提示词段/工具/权限**收窄到**
 *    预设里列出的集合；清除 = 回到未收窄状态，不修改插件本身。
 *
 * 本文件纯 Kotlin（无 Android、无 org.json），JVM 用例可以锁死编解码与校验规则。
 */
data class PluginPreset(
    /** 文件名/稳定 id：小写字母数字与 `-`，由名字派生（重名覆盖，不生成第二份）。 */
    val id: String,
    /** 用户看得见的名字。 */
    val name: String,
    /** 创建时间（毫秒）；同名覆盖时保留首次创建时间，[updatedAt] 记最后一次。 */
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** 模型通道 id（`deepseek` / `openai` / `node:<id>` / 原生插件 provider id）；空 = 跟随协议。 */
    val modelProviderId: String = "",
    /** 模型通道的展示名（宿主的 provider 表变了也能看出当时选的是什么）。 */
    val modelLabel: String = "",
    /** 要保留的提示词段名字（插件段，空 = 不限制）。 */
    val promptSections: List<String> = emptyList(),
    /** 要保留的工具名（`offeredToolNames()` 的子集，空 = 不限制）。 */
    val tools: List<String> = emptyList(),
    /** 插件 id → 该插件需要的权限名（逗号分隔，套用时逐条授予；空 = 不动权限）。 */
    val permissions: Map<String, String> = emptyMap(),
    /** 备注（给用户看的一句话）。 */
    val note: String = "",
) {
    /** 一行摘要（角色页/日志共用，避免两处各写一份文案）。 */
    fun summary(): String {
        val parts = ArrayList<String>()
        parts += if (modelProviderId.isBlank()) "模型：跟随协议" else "模型：${modelLabel.ifBlank { modelProviderId }}"
        parts += if (promptSections.isEmpty()) "提示词段：不限" else "提示词段：${promptSections.size}"
        parts += if (tools.isEmpty()) "工具：不限" else "工具：${tools.size}"
        parts += if (permissions.isEmpty()) "权限：不动" else "权限：${permissions.size} 个插件"
        return parts.joinToString(" · ")
    }
}

/**
 * 预设的编解码（`{version:1, active:<id?>, presets:[...]}`）。
 *
 * 容错口径：坏 JSON / 坏条目**跳过**而不是整份丢弃（用户手改坏一行不该丢掉其它预设），
 * 跳过的原因由 [Decoded.errors] 带回给界面显示，不静默。
 */
object PresetCodec {

    const val VERSION = 1

    /** `files/node/data/presets.json`（app 侧路径常量；编解码本身不碰文件）。 */
    const val FILE_NAME = "presets.json"

    data class Decoded(
        val presets: List<PluginPreset> = emptyList(),
        val activeId: String? = null,
        val errors: List<String> = emptyList(),
    )

    /** 名字 → 稳定 id：小写、非 `[a-z0-9]` 折成 `-`、去首尾 `-`；空则 null（调用方报错）。 */
    fun slug(name: String): String? {
        val text = name.trim().lowercase()
        if (text.isEmpty()) return null
        val builder = StringBuilder()
        var lastDash = false
        for (ch in text) {
            val ok = ch in 'a'..'z' || ch in '0'..'9'
            if (ok) {
                builder.append(ch)
                lastDash = false
            } else if (!lastDash) {
                builder.append('-')
                lastDash = true
            }
        }
        val slug = builder.toString().trim('-')
        if (slug.isEmpty()) return null
        return slug.take(64)
    }

    /** 权限映射里单条权限名的合法性（与 PluginPermissions 的写法一致：`kind:scope`）。 */
    fun normalizePermissions(raw: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        raw.forEach { (pluginId, names) ->
            val key = pluginId.trim()
            if (key.isEmpty()) return@forEach
            val cleaned = names.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.length <= 80 }
                .distinct()
            if (cleaned.isNotEmpty()) out[key] = cleaned.joinToString(",")
        }
        return out
    }

    fun encode(presets: List<PluginPreset>, activeId: String? = null): String {
        val root = buildJsonObject {
            put("version", VERSION)
            put("active", activeId?.let { JsonPrimitive(it) } ?: JsonNull)
            put(
                "presets",
                buildJsonArray {
                    presets.forEach { preset ->
                        add(
                            buildJsonObject {
                                put("id", preset.id)
                                put("name", preset.name)
                                put("createdAt", preset.createdAt)
                                put("updatedAt", preset.updatedAt)
                                put("modelProviderId", preset.modelProviderId)
                                put("modelLabel", preset.modelLabel)
                                put("promptSections", buildJsonArray { preset.promptSections.forEach { add(JsonPrimitive(it)) } })
                                put("tools", buildJsonArray { preset.tools.forEach { add(JsonPrimitive(it)) } })
                                put(
                                    "permissions",
                                    buildJsonObject { preset.permissions.forEach { (key, value) -> put(key, value) } },
                                )
                                put("note", preset.note)
                            },
                        )
                    }
                },
            )
        }
        return root.toString()
    }

    /** 解一份 `presets.json`；[text] 为空/坏 JSON 时返回空 + 一条错误（不抛）。 */
    fun decode(text: String?): Decoded {
        if (text.isNullOrBlank()) return Decoded()
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return Decoded(errors = listOf("presets.json 不是合法的 JSON 对象"))
        val errors = ArrayList<String>()
        val version = root.int("version")
        if (version > VERSION) errors += "预设文件版本 $version 比宿主支持的 $VERSION 新，已按兼容口径读取"
        val active = root.string("active")?.ifBlank { null }
        val array = (root["presets"] as? JsonArray).orEmpty()
        val out = ArrayList<PluginPreset>()
        val seen = HashSet<String>()
        array.forEachIndexed { index, element ->
            val item = element as? JsonObject
            if (item == null) {
                errors += "第 ${index + 1} 条预设不是对象，已跳过"
                return@forEachIndexed
            }
            val name = item.string("name").orEmpty().trim()
            val id = item.string("id").orEmpty().trim().ifBlank { slug(name).orEmpty() }
            if (id.isEmpty()) {
                errors += "第 ${index + 1} 条预设没有 id/名字，已跳过"
                return@forEachIndexed
            }
            if (!seen.add(id)) errors += "重复的预设 id=$id（后一条覆盖前一条）"
            out.removeAll { it.id == id }
            out += PluginPreset(
                id = id,
                name = name.ifBlank { id },
                createdAt = item.long("createdAt"),
                updatedAt = item.long("updatedAt"),
                modelProviderId = item.string("modelProviderId").orEmpty(),
                modelLabel = item.string("modelLabel").orEmpty(),
                promptSections = item.stringList("promptSections"),
                tools = item.stringList("tools"),
                permissions = normalizePermissions(
                    (item["permissions"] as? JsonObject)
                        ?.mapNotNull { (key, value) ->
                            val text = value.stringValue() ?: return@mapNotNull null
                            key to text
                        }
                        ?.toMap()
                        .orEmpty(),
                ),
                note = item.string("note").orEmpty(),
            )
        }
        return Decoded(presets = out.sortedBy { it.name.lowercase() }, activeId = active, errors = errors)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0
    private fun JsonElement.stringValue(): String? = (this as? JsonPrimitive)?.content

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()
}
