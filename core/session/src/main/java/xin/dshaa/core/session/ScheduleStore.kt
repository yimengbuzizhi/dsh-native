package xin.dshaa.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import xin.dshaa.core.model.ScheduleException
import xin.dshaa.core.model.ScheduleTiming
import xin.dshaa.core.model.ScheduledTask
import java.io.File
import java.util.UUID

/**
 * 会话内提醒的持久化（`<files>/schedules.json`，**不进 v4 会话**）。
 *
 * 为什么单独存：提醒属于宿主（App），会话只是投递目标；会话被删/导入只读都不影响
 * 其余会话的提醒。文件是小 JSON，写入走临时文件 + rename（原子写）。
 *
 * 只做 CRUD 与投递记账；时间计算（下一次触发）在 `core:tools` 的 `ScheduleTime`，
 * 由 App 层的 [xin.dshaa.core.tools.ScheduleHost] 适配器负责，避免模块循环依赖。
 */
class ScheduleStore(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 全部任务（按下次触发时间排序）。 */
    fun list(): List<ScheduledTask> = synchronized(LOCK) { load().sortedBy { it.nextRunAt } }

    /** 某个会话的任务（schedule_list 的可见范围：当前会话）。 */
    fun listForSession(sessionId: String): List<ScheduledTask> =
        synchronized(LOCK) { load().filter { it.sessionId == sessionId }.sortedBy { it.nextRunAt } }

    fun create(
        sessionId: String,
        title: String,
        prompt: String,
        timing: ScheduleTiming,
        nextRunAt: Long,
        now: Long = System.currentTimeMillis(),
    ): ScheduledTask = synchronized(LOCK) {
        val tasks = load().toMutableList()
        val task = ScheduledTask(
            id = "sched-${UUID.randomUUID()}",
            sessionId = sessionId,
            title = title,
            prompt = prompt,
            timing = timing,
            createdAt = now,
            updatedAt = now,
            nextRunAt = nextRunAt,
        )
        tasks += task
        save(tasks)
        task
    }

    /**
     * 原地改一条（保留 id）：只覆盖传入的字段；[nextRunAt] 只在触发方式变了时由调用方给新值。
     */
    fun update(
        id: String,
        sessionId: String,
        title: String?,
        prompt: String?,
        timing: ScheduleTiming?,
        nextRunAt: Long?,
        now: Long = System.currentTimeMillis(),
    ): ScheduledTask = synchronized(LOCK) {
        val tasks = load().toMutableList()
        val index = tasks.indexOfFirst { it.id == id && it.sessionId == sessionId }
        if (index < 0) throw ScheduleException("schedule_not_found", "no reminder with id $id in this session")
        val current = tasks[index]
        val updated = current.copy(
            title = title ?: current.title,
            prompt = prompt ?: current.prompt,
            timing = timing ?: current.timing,
            nextRunAt = nextRunAt ?: current.nextRunAt,
            updatedAt = now,
            // 改 trigger 等于重新开始：之前的投递记录保留，但重新变成 active
            active = if (nextRunAt != null) true else current.active,
        )
        tasks[index] = updated
        save(tasks)
        updated
    }

    fun delete(id: String, sessionId: String): ScheduledTask = synchronized(LOCK) {
        val tasks = load().toMutableList()
        val index = tasks.indexOfFirst { it.id == id && it.sessionId == sessionId }
        if (index < 0) throw ScheduleException("schedule_not_found", "no reminder with id $id in this session")
        val removed = tasks.removeAt(index)
        save(tasks)
        removed
    }

    /** 投递记账：记下这次投递，并把下一次触发推进到未来（null = 不再触发，任务结束）。 */
    fun markDelivered(id: String, deliveredAt: Long, nextRunAt: Long?): ScheduledTask = synchronized(LOCK) {
        val tasks = load().toMutableList()
        val index = tasks.indexOfFirst { it.id == id }
        if (index < 0) throw ScheduleException("schedule_not_found", "no reminder with id $id")
        val current = tasks[index]
        val updated = current.copy(
            lastDeliveredAt = deliveredAt,
            nextRunAt = nextRunAt ?: current.nextRunAt,
            active = nextRunAt != null,
        )
        tasks[index] = updated
        save(tasks)
        updated
    }

    /** 清掉某个会话的提醒（会话删除时调用；任务只属于会话，不跨会话复用）。 */
    fun removeForSession(sessionId: String) {
        synchronized(LOCK) {
            val tasks = load()
            val kept = tasks.filterNot { it.sessionId == sessionId }
            if (kept.size != tasks.size) save(kept)
        }
    }

    // ---------------------------------------------------------------- 读写

    private fun load(): List<ScheduledTask> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
            val tasks = root["tasks"] as? JsonArray ?: return emptyList()
            tasks.mapNotNull { element -> parseTask(element as? JsonObject ?: return@mapNotNull null) }
        }.getOrElse { emptyList() }
    }

    private fun save(tasks: List<ScheduledTask>) {
        file.parentFile?.mkdirs()
        val body = buildJsonObject {
            put("version", 1)
            put("tasks", buildJsonArray { tasks.forEach { add(taskToJson(it)) } })
        }.toString()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(body, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(body, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun taskToJson(task: ScheduledTask): JsonObject = buildJsonObject {
        put("id", task.id)
        put("sessionId", task.sessionId)
        put("title", task.title)
        put("prompt", task.prompt)
        put("timing", timingToJson(task.timing))
        put("createdAt", task.createdAt)
        put("updatedAt", task.updatedAt)
        put("nextRunAt", task.nextRunAt)
        task.lastDeliveredAt?.let { put("lastDeliveredAt", it) }
        put("active", task.active)
    }

    private fun timingToJson(timing: ScheduleTiming): JsonObject = buildJsonObject {
        put("kind", timing.kind)
        timing.afterSeconds?.let { put("afterSeconds", it) }
        timing.everySeconds?.let { put("everySeconds", it) }
        timing.atMillis?.let { put("atMillis", it) }
        timing.time?.let { put("time", it) }
        timing.timeZone?.let { put("timeZone", it) }
        if (timing.weekdays.isNotEmpty()) {
            put("weekdays", buildJsonArray { timing.weekdays.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }
        timing.expression?.let { put("expression", it) }
    }

    private fun parseTask(obj: JsonObject): ScheduledTask? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val timingObj = obj["timing"] as? JsonObject ?: return null
        val timing = parseTiming(timingObj) ?: return null
        return ScheduledTask(
            id = id,
            sessionId = sessionId,
            title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            timing = timing,
            createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            updatedAt = obj["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            nextRunAt = obj["nextRunAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            lastDeliveredAt = obj["lastDeliveredAt"]?.jsonPrimitive?.longOrNull,
            active = obj["active"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true,
        )
    }

    private fun parseTiming(obj: JsonObject): ScheduleTiming? {
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: return null
        return ScheduleTiming(
            kind = kind,
            afterSeconds = obj["afterSeconds"]?.jsonPrimitive?.longOrNull,
            everySeconds = obj["everySeconds"]?.jsonPrimitive?.longOrNull,
            atMillis = obj["atMillis"]?.jsonPrimitive?.longOrNull,
            time = obj["time"]?.jsonPrimitive?.contentOrNull,
            timeZone = obj["timeZone"]?.jsonPrimitive?.contentOrNull,
            weekdays = (obj["weekdays"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList(),
            expression = obj["expression"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private companion object {
        val LOCK = Any()
    }
}
