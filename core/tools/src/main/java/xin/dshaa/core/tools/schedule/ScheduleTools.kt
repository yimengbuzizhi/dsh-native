package xin.dshaa.core.tools.schedule

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xin.dshaa.core.model.ScheduleException
import xin.dshaa.core.model.ScheduledTask
import xin.dshaa.core.model.ScheduleTiming
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult

/**
 * 会话内提醒四件套：`schedule_create` / `schedule_list` / `schedule_update` / `schedule_delete`。
 *
 * 参数与文案来自桌面快照 `prompt/0.2.2-rc.1/tools.json`（旧版 reminders API）。
 * 输出 JSON 是本实现设计的稳定形状（快照没有 output schema 可对照）：
 * ```json
 * {"id":"…","title":"…","prompt":"…","timing":{…},"sessionId":"…","status":"active",
 *  "nextRunAt":"2026-11-07T18:30:00Z","nextRunAtLocal":"2026-11-08 02:30:00","createdAt":"…","updatedAt":"…"}
 * ```
 * 失败：`ToolResult.err(message, meta.code)`，码见 [ScheduleTime] 的注释。
 */
class ScheduleCreateTool : DshTool {
    override val name = "schedule_create"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val host = ctx.scheduleHost ?: return unavailable()
        return runCatching {
            val title = ScheduleTime.validateTitle(args["title"]?.jsonPrimitive?.contentOrNull)
            val prompt = ScheduleTime.validatePrompt(args["prompt"]?.jsonPrimitive?.contentOrNull)
            val timing = ScheduleTime.parse(args, System.currentTimeMillis())
            host.create(title, prompt, timing)
        }.fold({ ToolResult.ok(taskJson(it)) }, { errorResult(it) })
    }
}

class ScheduleListTool : DshTool {
    override val name = "schedule_list"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val host = ctx.scheduleHost ?: return unavailable()
        return runCatching { host.list().filter { it.active } }
            .fold({ tasks ->
                ToolResult.ok(
                    buildJsonObject {
                        put("tasks", buildJsonArray { tasks.forEach { add(taskJsonElement(it)) } })
                        put("count", tasks.size)
                    }.toString(),
                )
            }, { errorResult(it) })
    }
}

class ScheduleUpdateTool : DshTool {
    override val name = "schedule_update"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val host = ctx.scheduleHost ?: return unavailable()
        return runCatching {
            val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (id.isEmpty()) throw ScheduleException("schedule_not_found", "id is required")
            val title = args["title"]?.let { ScheduleTime.validateTitle(it.jsonPrimitive.contentOrNull) }
            val prompt = args["prompt"]?.let { ScheduleTime.validatePrompt(it.jsonPrimitive.contentOrNull) }
            // at 没有「改」的语义（相对延迟要新建）：只允许 after/every/daily/weekly/cron 之外的一个 timing
            val timing = if (ScheduleTime.timingKeysPresent(args).isEmpty()) null else ScheduleTime.parse(args, System.currentTimeMillis())
            if (title == null && prompt == null && timing == null) {
                throw ScheduleException("invalid_timing", "supply title, prompt, or one timing parameter")
            }
            host.update(id, title, prompt, timing)
        }.fold({ ToolResult.ok(taskJson(it)) }, { errorResult(it) })
    }
}

class ScheduleDeleteTool : DshTool {
    override val name = "schedule_delete"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val host = ctx.scheduleHost ?: return unavailable()
        return runCatching {
            val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (id.isEmpty()) throw ScheduleException("schedule_not_found", "id is required")
            host.delete(id)
        }.fold({
            ToolResult.ok(buildJsonObject { put("id", it.id); put("deleted", true) }.toString())
        }, { errorResult(it) })
    }
}

private fun unavailable(): ToolResult =
    ToolResult.err("schedule tools are unavailable: no schedule store attached", meta = mapOf("code" to "SCHEDULE_UNAVAILABLE"))

private fun errorResult(error: Throwable): ToolResult = when (error) {
    is ScheduleException -> ToolResult.err(error.message ?: error.code, meta = mapOf("code" to error.code))
    else -> ToolResult.err("${error.javaClass.simpleName}: ${error.message}")
}

internal fun taskJson(task: ScheduledTask): String = taskJsonElement(task).toString()

internal fun taskJsonElement(task: ScheduledTask) = buildJsonObject {
    put("id", task.id)
    put("title", task.title)
    put("prompt", task.prompt)
    put("timing", timingJson(task.timing))
    put("sessionId", task.sessionId)
    put("status", if (task.active) "active" else "ended")
    put("nextRunAt", ScheduleTime.isoUtc(task.nextRunAt))
    put("nextRunAtLocal", ScheduleTime.localText(task.nextRunAt, task.timing.timeZone))
    put("createdAt", ScheduleTime.isoUtc(task.createdAt))
    put("updatedAt", ScheduleTime.isoUtc(task.updatedAt))
    task.lastDeliveredAt?.let { put("lastDeliveredAt", ScheduleTime.isoUtc(it)) }
}

/** 回显触发方式：daily/weekly/cron 保持输入时的嵌套形状，after/every 是数字，at 是 ISO 字符串。 */
internal fun timingJson(timing: ScheduleTiming) = buildJsonObject {
    when (timing.kind) {
        "after_seconds" -> {
            put("kind", "after_seconds")
            put("after_seconds", timing.afterSeconds)
        }
        "every_seconds" -> {
            put("kind", "every_seconds")
            put("every_seconds", timing.everySeconds)
        }
        "at" -> {
            put("kind", "at")
            put("at", ScheduleTime.isoUtc(timing.atMillis ?: 0L))
        }
        "daily" -> {
            put("kind", "daily")
            put(
                "daily",
                buildJsonObject {
                    put("time", timing.time)
                    put("time_zone", timing.timeZone)
                },
            )
        }
        "weekly" -> {
            put("kind", "weekly")
            put(
                "weekly",
                buildJsonObject {
                    put("time", timing.time)
                    put("time_zone", timing.timeZone)
                    put("weekdays", buildJsonArray { timing.weekdays.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                },
            )
        }
        "cron" -> {
            put("kind", "cron")
            put(
                "cron",
                buildJsonObject {
                    put("expression", timing.expression)
                    put("time_zone", timing.timeZone)
                },
            )
        }
    }
}
