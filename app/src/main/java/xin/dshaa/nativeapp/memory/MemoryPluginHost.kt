package xin.dshaa.nativeapp.memory

import android.app.Application
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import xin.dshaa.core.tools.ToolContext
import java.io.File

/**
 * Operit `Tools.Memory.*` 的宿主实现（M68，docs/95）。
 *
 * 为什么要单独一层：Operit 包的 `Tools.Memory` 在 Operit 里是 `toolCall("create_memory", …)`
 * 的语法糖，返回值是**工具输出文本**（包体用 `result.length > 0` 判成功）。鲸歌已经有 8 个
 * 同语义记忆工具（[MemoryTools]，逐字对齐 Operit 的 `MemoryQueryToolExecutor` 文案），
 * 所以这里**直接复用**那些工具，而不是再写一套 SQL：
 * - `create/update/deleteMemory/move/link/queryLinks` → 调对应内置工具，返回它的文本；
 * - `updateLink/deleteLink` → 内置工具里没有这两个（DSH 的 8 件套不含），用 [MemoryStore] 实现，
 *   定位规则照 Operit：优先 `link_id`，否则按 source/target/link_type 组合定位。
 *
 * 失败一律返回 `{ok:false,error}`，由 `__dshHostOk` 抛成 JS Error，包的 `wrapToolExecution`
 * 会把它变成 `{success:false,message}`——和 Operit 的失败形状一致，不静默成功。
 */
class MemoryPluginHost(private val app: Application) {

    private val store: MemoryStore by lazy { MemoryStore.get(app) }
    private val tools by lazy { MemoryTools(app).tools.associateBy { it.name } }

    /** 返回完整 JSON 文本（`{ok:true,text}` / `{ok:false,error}`）。 */
    suspend fun call(method: String, args: JsonObject): String = when (method) {
        "create" -> runBuiltIn("create_memory", args)
        "update" -> runBuiltIn("update_memory", args)
        "deleteMemory" -> runBuiltIn("delete_memory", args)
        "move" -> runBuiltIn("move_memory", args)
        "link" -> runBuiltIn("link_memories", args)
        "queryLinks" -> runBuiltIn("query_memory_links", args)
        "query" -> runBuiltIn("query_memory", args)
        "getByTitle" -> runBuiltIn("get_memory_by_title", args)
        "updateLink" -> updateLink(args)
        "deleteLink" -> deleteLink(args)
        else -> fail("Tools.Memory.$method：鲸歌兼容层没有实现这个方法")
    }

    // ---------------- 复用内置记忆工具 ----------------

    private suspend fun runBuiltIn(name: String, args: JsonObject): String {
        val tool = tools[name] ?: return fail("内置记忆工具 $name 不在注册表里")
        val result = tool.execute(args, ToolContext(workspace = File(app.filesDir, "workspace")))
        return if (result.isError) fail(result.text) else ok(result.text)
    }

    // ---------------- 链接的更新 / 删除 ----------------

    private suspend fun updateLink(args: JsonObject): String {
        val found = resolveLink(args) ?: return fail("找不到匹配的记忆链接")
        val link = found
        val type = args.str("new_link_type")?.takeIf { it.isNotBlank() } ?: link.type
        val weight = args.float("weight") ?: link.weight
        // 显式传了 description（哪怕是空串）就覆盖，没传保持原样——和 Operit 的 update 语义一致。
        val description = if (args.containsKey("description")) {
            args.str("description").orEmpty()
        } else {
            link.description
        }
        val updated = store.updateLink(link.id, type, weight, description)
        if (!updated) return fail("链接 #${link.id} 更新失败（可能已被删除）")
        return ok(
            "Successfully updated memory link #${link.id} " +
                "(Type: $type, Weight: ${weight.coerceIn(0f, 1f)})",
        )
    }

    private suspend fun deleteLink(args: JsonObject): String {
        val found = resolveLink(args) ?: return fail("找不到匹配的记忆链接")
        if (!store.deleteLink(found.id)) return fail("链接 #${found.id} 删除失败（可能已被删除）")
        return ok("Successfully deleted memory link #${found.id}")
    }

    /** 定位链接：`link_id` 优先；否则按 source/target/type 过滤，多条时报错不猜。 */
    private suspend fun resolveLink(args: JsonObject): MemoryLink? {
        val linkId = args.long("link_id")
        val all = store.allLinks()
        if (linkId != null) return all.firstOrNull { it.id == linkId }

        // 解析标题必须和内置工具用**同一个**入口（findMemoryByTitle），否则同名记忆会各挑一条、
        // 明明建了链接却“找不到匹配”——M68 真机踩过。
        val sourceId = args.str("source_title")?.let { title -> store.findMemoryByTitle(title)?.id }
        val targetId = args.str("target_title")?.let { title -> store.findMemoryByTitle(title)?.id }
        val type = args.str("link_type")
        if (sourceId == null && targetId == null && type == null) return null
        val matches = all.filter { link ->
            (sourceId == null || link.sourceId == sourceId) &&
                (targetId == null || link.targetId == targetId) &&
                (type == null || link.type == type)
        }
        return matches.singleOrNull()
    }

    // ---------------- JSON 小工具 ----------------

    private fun ok(text: String): String = buildJsonObject {
        put("ok", true)
        put("text", text)
    }.toString()

    private fun fail(message: String): String = buildJsonObject {
        put("ok", false)
        put("error", message)
    }.toString()

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.long(key: String): Long? = runCatching {
        val primitive: JsonPrimitive = this[key]?.jsonPrimitive ?: return null
        primitive.longOrNull ?: primitive.contentOrNull?.trim()?.toLongOrNull()
    }.getOrNull()

    private fun JsonObject.float(key: String): Float? =
        runCatching { this[key]?.jsonPrimitive?.floatOrNull }.getOrNull()
}
