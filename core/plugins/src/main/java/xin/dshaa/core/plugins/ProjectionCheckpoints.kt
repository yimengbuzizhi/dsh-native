package xin.dshaa.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 一条投影 checkpoint（M84-N）。
 *
 * 语义：`(pluginId, sessionId, key)` 的投影 state **已经折到 [lastSeq]**；App 重启后 `attach` 先
 * 把 state（和上次的 view）灌回常驻运行时，只重投 `seq > lastSeq` 的事件，不再整段重放。
 *
 * - [stateJson] 是 `init/apply` 维护的内部状态（JSON 文本，插件自己决定形状）；
 * - [viewJson] 是上次 `wire.view` 的结果（`""` = 没有 view），用来让 UI 重启后立刻有值；
 * - [stateVersion] 必须与插件当前声明的版本一致，否则整条作废（插件升级后从会话开头重折）。
 */
data class ProjectionCheckpoint(
    val pluginId: String,
    val sessionId: String,
    val key: String,
    val stateVersion: Int,
    val lastSeq: Long,
    val stateJson: String,
    val viewJson: String = "",
    val updatedAt: Long = 0L,
)

/**
 * 投影 checkpoint 的可用性判定（M84-N，宿主与单测共用）。
 *
 * 三条同时满足才算可用：插件 id 对得上、这条 key 现在仍声明且 `stateVersion` 一致、
 * 至少折过一条带 seq 的事件（`lastSeq >= 0`）。插件升级/降级或改名后旧 checkpoint 整条作废，
 * 从会话开头重折——宁可慢，不可错。
 */
object ProjectionCheckpointPolicy {
    fun usable(entry: ProjectionCheckpoint, pluginId: String, declared: Map<String, Int>): Boolean {
        if (entry.pluginId != pluginId || entry.lastSeq < 0) return false
        val current = declared[entry.key] ?: return false
        return current == entry.stateVersion
    }

    /** 该插件的哪些 key 可以恢复（日志/dump 用）。 */
    fun usableKeys(entries: List<ProjectionCheckpoint>, pluginId: String, declared: Map<String, Int>): List<String> =
        entries.filter { usable(it, pluginId, declared) }.map { it.key }
}

/**
 * 投影 checkpoint 的落盘存储（M84-N）。
 *
 * 单文件 JSON：`files/projection-checkpoints.json`，整份读写；内存里是
 * `pluginId \u0000 sessionId \u0000 key → checkpoint` 的并发表。
 *
 * 纪律：
 * - 写盘用「临时文件 + rename」避免半截文件；解析失败的旧文件直接当空（不炸宿主）；
 * - 同一 (插件/会话/键) 只留最新一条；条目上限 [MAX_ENTRIES]，超了按 `updatedAt` 丢最旧；
 * - 插件停用/删除时用 [removePlugin] 清行（插件升级/降级用 [removeSession]）。
 */
class ProjectionCheckpoints(private val file: File, private val maxEntries: Int = MAX_ENTRIES) {

    private val entries = ConcurrentHashMap<String, ProjectionCheckpoint>()

    init {
        load()
    }

    /** 是否从磁盘读到了内容（验收/日志用）。 */
    @Volatile
    var loadedFromDisk: Boolean = false
        private set

    fun forSession(sessionId: String): List<ProjectionCheckpoint> =
        entries.values.filter { it.sessionId == sessionId }.sortedWith(compareBy({ it.pluginId }, { it.key }))

    fun forPlugin(pluginId: String): List<ProjectionCheckpoint> =
        entries.values.filter { it.pluginId == pluginId }.sortedWith(compareBy({ it.sessionId }, { it.key }))

    fun get(pluginId: String, sessionId: String, key: String): ProjectionCheckpoint? =
        entries[key(pluginId, sessionId, key)]

    fun put(checkpoint: ProjectionCheckpoint) {
        entries[key(checkpoint.pluginId, checkpoint.sessionId, checkpoint.key)] = checkpoint
    }

    fun putAll(items: List<ProjectionCheckpoint>) {
        for (item in items) put(item)
    }

    fun removePlugin(pluginId: String): Int = removeWhere { it.pluginId == pluginId }

    fun removeSession(sessionId: String): Int = removeWhere { it.sessionId == sessionId }

    fun remove(pluginId: String, sessionId: String, key: String): ProjectionCheckpoint? =
        entries.remove(key(pluginId, sessionId, key))

    fun size(): Int = entries.size

    /** 只保留这些插件（停用/删除的插件清掉）。返回清掉的条数。 */
    fun retainPlugins(pluginIds: Set<String>): Int = removeWhere { it.pluginId !in pluginIds }

    /** 整份落盘（原子替换）。调用方负责放到 IO 线程。 */
    @Synchronized
    fun save() {
        trim()
        val payload = buildJsonObject {
            put("version", 1)
            put(
                "entries",
                JsonArray(
                    entries.values.sortedWith(compareBy({ it.pluginId }, { it.sessionId }, { it.key })).map { item ->
                        buildJsonObject {
                            put("pluginId", item.pluginId)
                            put("sessionId", item.sessionId)
                            put("key", item.key)
                            put("stateVersion", item.stateVersion)
                            put("lastSeq", item.lastSeq)
                            put("state", runCatching { Json.parseToJsonElement(item.stateJson) }.getOrElse { JsonNull })
                            put("view", runCatching { Json.parseToJsonElement(item.viewJson.ifBlank { "null" }) }.getOrElse { JsonNull })
                            put("updatedAt", item.updatedAt)
                        }
                    },
                ),
            )
        }
        val target = file
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile ?: File("."), target.name + ".tmp")
        runCatching {
            temp.writeText(payload.toString(), Charsets.UTF_8)
            if (target.exists()) target.delete()
            temp.renameTo(target)
        }.onFailure {
            temp.delete()
        }
    }

    private fun load() {
        if (!file.isFile) return
        val obj = runCatching { Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject }.getOrNull()
            ?: return
        val list = runCatching { obj["entries"]?.jsonArray }.getOrNull().orEmpty()
        for (element in list) {
            val item = runCatching { element.jsonObject }.getOrNull() ?: continue
            val pluginId = item.string("pluginId").orEmpty()
            val sessionId = item.string("sessionId").orEmpty()
            val key = item.string("key").orEmpty()
            if (pluginId.isBlank() || sessionId.isBlank() || key.isBlank()) continue
            entries[key(pluginId, sessionId, key)] = ProjectionCheckpoint(
                pluginId = pluginId,
                sessionId = sessionId,
                key = key,
                stateVersion = item.int("stateVersion") ?: 1,
                lastSeq = item.long("lastSeq") ?: 0L,
                stateJson = item["state"]?.toString() ?: "null",
                viewJson = item["view"]?.takeIf { it !is JsonNull }?.toString().orEmpty(),
                updatedAt = item.long("updatedAt") ?: 0L,
            )
        }
        loadedFromDisk = entries.isNotEmpty()
    }

    private fun trim() {
        if (entries.size <= maxEntries) return
        val drop = entries.values.sortedBy { it.updatedAt }.take(entries.size - maxEntries)
        for (item in drop) entries.remove(key(item.pluginId, item.sessionId, item.key))
    }

    private fun removeWhere(predicate: (ProjectionCheckpoint) -> Boolean): Int {
        val targets = entries.values.filter(predicate)
        for (target in targets) entries.remove(key(target.pluginId, target.sessionId, target.key))
        return targets.size
    }

    private fun key(pluginId: String, sessionId: String, key: String): String =
        pluginId + '\u0000' + sessionId + '\u0000' + key

    private fun JsonObject.string(name: String): String? =
        runCatching { this[name]?.jsonPrimitive?.content }.getOrNull()

    private fun JsonObject.long(name: String): Long? =
        runCatching { this[name]?.jsonPrimitive?.longOrNull }.getOrNull()

    private fun JsonObject.int(name: String): Int? =
        runCatching { this[name]?.jsonPrimitive?.intOrNull }.getOrNull()

    companion object {
        /** 条目上限：按会话 × 插件 × key，正常手机远用不到；超了丢最旧。 */
        const val MAX_ENTRIES = 400
    }
}
