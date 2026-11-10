package xin.dshaa.nativeapp.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一份「助手配置」＝ 一张角色卡（M73）。
 *
 * 用户可以同时留多份（鲸歌 / 代码搭子 / 严谨审阅…），在侧边栏切换**当前使用**的那一份：
 * - [persona] → 提示词 `deployment:persona-prefix`（order 0，替换默认 coding agent 段）
 * - [system] → 提示词 `deployment:persona-suffix`（order 10200，system 最后一段）
 * - [opening] → 只用于空会话问候气泡，不进提示词
 * - [desc] / [avatar] → 纯展示（聊天页顶栏、每条 AI 消息头、设置页）
 *
 * 落盘：`shared_prefs/dsh.xml` 的 `assistant_profiles`（JSON 数组）+ `assistant_active_id`。
 * 老的单份键（`assistant_name/persona/opening/system`…）在首次升级时迁移成第一份，之后继续同步写回，
 * 保证备份/降级/调试通道还读得到「当前这份」。
 */
data class AssistantProfile(
    val id: String,
    val name: String = "鲸歌",
    val desc: String = "",
    val avatar: String = "",
    val persona: String = "",
    val opening: String = "",
    val system: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("desc", desc)
        .put("avatar", avatar)
        .put("persona", persona)
        .put("opening", opening)
        .put("system", system)

    /** 卡片上的一行摘要（没有简介时用开场白/人设兜底，避免整行空白）。 */
    fun summary(): String = when {
        desc.isNotBlank() -> desc
        opening.isNotBlank() -> opening.lineSequence().firstOrNull().orEmpty()
        persona.isNotBlank() -> "人设：" + persona.lineSequence().firstOrNull().orEmpty()
        else -> "没填人设/开场白（用上游默认回复风格）"
    }.take(60)

    companion object {
        const val DEFAULT_ID = "default"

        fun fromJson(obj: JSONObject): AssistantProfile? {
            val id = obj.optString("id").trim()
            if (id.isEmpty()) return null
            return AssistantProfile(
                id = id,
                name = obj.optString("name").trim().ifBlank { "鲸歌" },
                desc = obj.optString("desc").orEmpty(),
                avatar = obj.optString("avatar").orEmpty(),
                persona = obj.optString("persona").orEmpty(),
                opening = obj.optString("opening").orEmpty(),
                system = obj.optString("system").orEmpty(),
            )
        }

        fun listToJson(list: List<AssistantProfile>): String {
            val array = JSONArray()
            list.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun listFromJson(raw: String): List<AssistantProfile> = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                (array.opt(index) as? JSONObject)?.let { fromJson(it) }
            }
        }.getOrDefault(emptyList())
    }
}
