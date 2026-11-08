package xin.dshaa.core.skill

import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 会话内技能目录（对齐上游 `@deepseek-ai/dsh-tool-skill` 的 `agent/pre-step` 目录注入）。
 *
 * 上游在**每个 step 起点**比较「当前目录」与「会话里最后一次可见目录」的摘要：
 * - 相同 → 什么都不加；
 * - 不同 → 追加一条 `user/message`，`source = {kind:"skill-catalog", form:"catalog", entries, [update], [incomplete]}`，
 *   正文是一段 `<system-reminder>`（首次 = 目录；之后 = 「目录变了，这份取代之前所有列表」）；
 * - 从没发布过且当前没有任何技能 → 也不加（空目录不占上下文）。
 *
 * 摘要与文案逐字照抄上游（`renderCatalogMessage` / `renderCatalogUpdate` /
 * `digestCatalogEntries` / `catalogDescription`），只把 hash 换成 Java 的 SHA-256。
 */
object SkillCatalog {

    /** 上游 `DEFAULT_CATALOG_DESCRIPTION_MAX_LENGTH`。 */
    const val DESCRIPTION_MAX_LENGTH = 500

    /** 一次目录决策：要有新消息时返回，否则 null。 */
    data class Notice(
        val text: String,
        val entries: List<SkillCatalogEntry>,
        val incomplete: Boolean,
        /** true = 之前发布过目录，这次是「目录变了」的替换消息（source.update = true）。 */
        val update: Boolean,
    )

    /** 上游 `catalogDescription`：空白折叠成单空格 + 超长按 500 截断加 `...`。 */
    fun description(value: String, maxLength: Int = DESCRIPTION_MAX_LENGTH): String {
        val normalized = value.replace(Regex("\\s+"), " ").trim()
        return if (normalized.length <= maxLength) normalized else normalized.substring(0, maxLength - 3) + "..."
    }

    /** 目录条目：只收「模型可调用」的技能，description 按上限归一化，按名字码元序。 */
    fun entries(skills: List<SkillSummary>, maxDescriptionLength: Int = DESCRIPTION_MAX_LENGTH): List<SkillCatalogEntry> =
        skills.filter { it.modelInvocable }
            .sortedBy { it.name }
            .map { SkillCatalogEntry(it.name, description(it.description, maxDescriptionLength)) }

    /** 上游 `digestCatalogEntries`：sha256("incomplete\n"?) + 每行 `["name","description"]`，行间 `\n`。 */
    fun digest(entries: List<SkillCatalogEntry>, incomplete: Boolean = false): String {
        val md = MessageDigest.getInstance("SHA-256")
        if (incomplete) md.update("incomplete\n".toByteArray(Charsets.UTF_8))
        val canonical = entries.joinToString("\n") { entry ->
            buildJsonArray {
                add(JsonPrimitive(entry.name))
                add(JsonPrimitive(entry.description))
            }.toString()
        }
        md.update(canonical.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 上游 `renderCatalogEntries`：`- \`name\`: escaped description`。 */
    private fun renderEntries(entries: List<SkillCatalogEntry>): List<String> =
        entries.map { "- `${it.name}`: ${escapeText(it.description)}" }

    /** 上游 `incompleteCatalogNotice()`。 */
    private fun incompleteNotice(): String =
        "Some skills could not be checked. Only confirmed skills are listed; a lower-priority definition of the " +
            "same name may be selected when a preferred source is unavailable. Discovery will be retried on the next request."

    /** 首次目录消息（上游 `renderCatalogMessage`）。 */
    fun firstMessage(entries: List<SkillCatalogEntry>, incomplete: Boolean = false): String {
        val lines = ArrayList<String>()
        lines += "<system-reminder>"
        lines += if (entries.isEmpty()) {
            "No skills could be confirmed in this discovery attempt."
        } else {
            "A skill is a reusable set of task-specific instructions. The following skills are available in this session:"
        }
        lines += ""
        lines += "<available_skills>"
        lines += renderEntries(entries)
        lines += "</available_skills>"
        lines += ""
        if (incomplete) {
            lines += incompleteNotice()
            lines += ""
        }
        lines += if (entries.isEmpty()) {
            "Wait for an updated catalog before calling the `skill` tool. Do not infer skill instructions from an empty catalog."
        } else {
            "If the user names a skill, or the task clearly matches a skill's description, call the `skill` tool with the " +
                "exact skill name before taking task actions. Load all applicable skills, then follow their full " +
                "instructions. This catalog contains summaries only; do not infer or follow a skill's instructions until " +
                "it has been loaded."
        }
        lines += "A user may also invoke a skill directly; its <skill_content> block then appears in this conversation. " +
            "Follow it, and do not call the `skill` tool again for that skill."
        lines += "</system-reminder>"
        return lines.joinToString("\n")
    }

    /** 目录变更消息（上游 `renderCatalogUpdate`）。 */
    fun updateMessage(entries: List<SkillCatalogEntry>, incomplete: Boolean = false): String {
        val lines = ArrayList<String>()
        lines += "<system-reminder>"
        lines += if (incomplete) {
            "Skill discovery is incomplete. This catalog of currently available skills replaces every earlier " +
                "available-skills list in this session:"
        } else {
            "The available skill catalog changed. This complete catalog replaces every earlier available-skills list " +
                "in this session:"
        }
        lines += ""
        lines += "<available_skills>"
        lines += renderEntries(entries)
        lines += "</available_skills>"
        lines += ""
        if (incomplete) {
            lines += incompleteNotice()
            lines += ""
        }
        when {
            entries.isEmpty() && incomplete -> {
                lines += "No skills could be confirmed in this discovery attempt. Do not use names from earlier skill catalogs."
                lines += "A user may still invoke a skill directly; its <skill_content> block then appears in this " +
                    "conversation. Follow it, and do not call the `skill` tool for it."
            }
            entries.isEmpty() -> {
                lines += "No skills are currently available through the `skill` tool. Do not use names from earlier skill catalogs."
                lines += "A user may still invoke a skill directly; its <skill_content> block then appears in this " +
                    "conversation. Follow it, and do not call the `skill` tool for it."
            }
            else -> {
                lines += "Use only names in this replacement catalog. If the user names a listed skill, or the task " +
                    "clearly matches its description, call the `skill` tool with the exact name before acting."
                lines += "A user may also invoke a skill directly; its <skill_content> block then appears in this " +
                    "conversation. Follow it, and do not call the `skill` tool again for that skill."
            }
        }
        lines += "</system-reminder>"
        return lines.joinToString("\n")
    }

    /**
     * 决定这个 step 要不要注入目录消息。
     * [previous] = 会话里最后一条 `source.kind:"skill-catalog"` 的 source（null = 从没发布过）。
     */
    fun decide(
        snapshot: SkillSnapshot,
        previous: JsonObject?,
        maxDescriptionLength: Int = DESCRIPTION_MAX_LENGTH,
    ): Notice? {
        val entries = entries(snapshot.skills, maxDescriptionLength)
        val digest = digest(entries, !snapshot.complete)
        val previousEntries = previous?.let { readEntries(it) }
        val published = previous != null && previousEntries != null
        if (published) {
            val previousIncomplete = runCatching { previous!!["incomplete"]?.jsonPrimitive?.booleanOrNull }.getOrNull() == true
            if (digest(previousEntries!!, previousIncomplete) == digest) return null
        }
        if (!published && entries.isEmpty() && snapshot.complete) return null
        return Notice(
            text = if (published) updateMessage(entries, !snapshot.complete) else firstMessage(entries, !snapshot.complete),
            entries = entries,
            incomplete = !snapshot.complete,
            update = published,
        )
    }

    /** 会话里的完整 source（字段顺序与上游一致：kind → form → update? → entries → incomplete?）。 */
    fun source(notice: Notice): JsonObject = buildJsonObject {
        put("kind", "skill-catalog")
        put("form", "catalog")
        if (notice.update) put("update", true)
        put(
            "entries",
            buildJsonArray {
                notice.entries.forEach { entry ->
                    add(
                        buildJsonObject {
                            put("name", entry.name)
                            put("description", entry.description)
                        },
                    )
                }
            },
        )
        if (notice.incomplete) put("incomplete", true)
    }

    /** 从会话 source 里读回条目（上游 `readCatalogEntries`：形状不对 = 不是可用目录，返回 null）。 */
    fun readEntries(source: JsonObject): List<SkillCatalogEntry>? {
        val array = runCatching { source["entries"]!!.jsonArray }.getOrNull() ?: return null
        val out = ArrayList<SkillCatalogEntry>()
        for (element in array) {
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return null
            val name = runCatching { obj["name"]!!.jsonPrimitive.contentOrNull }.getOrNull()
            val description = runCatching { obj["description"]!!.jsonPrimitive.contentOrNull }.getOrNull()
            if (name.isNullOrEmpty() || description == null) return null
            out += SkillCatalogEntry(name, description)
        }
        return out
    }
}
