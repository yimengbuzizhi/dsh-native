package xin.dshaa.core.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xin.dshaa.core.model.ScheduleException
import xin.dshaa.core.model.ScheduleTiming
import xin.dshaa.core.model.ScheduledTask
import xin.dshaa.core.tools.schedule.ScheduleCreateTool
import xin.dshaa.core.tools.schedule.ScheduleDeleteTool
import xin.dshaa.core.tools.schedule.ScheduleListTool
import xin.dshaa.core.tools.schedule.ScheduleTime
import xin.dshaa.core.tools.schedule.ScheduleUpdateTool
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 四个提醒工具：参数校验 → host → 稳定 JSON。schema 在 tools.json（不在这里手写），
 * 这里只验行为与输出形状。
 */
class ScheduleToolsTest {

    private val workspace = File(System.getProperty("java.io.tmpdir"), "dsh-schedule-tools")

    private class FakeHost : ScheduleHost {
        val tasks = mutableListOf<ScheduledTask>()
        private var seq = 0

        override fun sessionId(): String = "session-1"

        override fun list(): List<ScheduledTask> = tasks.toList()

        override fun create(title: String, prompt: String, timing: ScheduleTiming): ScheduledTask {
            val now = 1_000L
            val task = ScheduledTask(
                id = "sched-${seq++}",
                sessionId = "session-1",
                title = title,
                prompt = prompt,
                timing = timing,
                createdAt = now,
                updatedAt = now,
                nextRunAt = ScheduleTime.nextOccurrence(timing, now, now) ?: 0L,
            )
            tasks += task
            return task
        }

        override fun update(id: String, title: String?, prompt: String?, timing: ScheduleTiming?): ScheduledTask {
            val index = tasks.indexOfFirst { it.id == id }
            if (index < 0) throw ScheduleException("schedule_not_found", "no reminder with id $id in this session")
            val current = tasks[index]
            val updated = current.copy(
                title = title ?: current.title,
                prompt = prompt ?: current.prompt,
                timing = timing ?: current.timing,
                updatedAt = 2_000,
            )
            tasks[index] = updated
            return updated
        }

        override fun delete(id: String): ScheduledTask {
            val index = tasks.indexOfFirst { it.id == id }
            if (index < 0) throw ScheduleException("schedule_not_found", "no reminder with id $id in this session")
            return tasks.removeAt(index)
        }
    }

    private fun run(tool: DshTool, host: ScheduleHost?, args: String): ToolResult {
        val ctx = ToolContext(workspace, scheduleHost = host)
        return runBlocking { tool.execute(Json.parseToJsonElement(args).jsonObject, ctx) }
    }

    private fun json(result: ToolResult): JsonObject = Json.parseToJsonElement(result.text).jsonObject

    @Test
    fun `create returns the stored task as json`() {
        val host = FakeHost()
        val result = run(
            ScheduleCreateTool(),
            host,
            """{"title":"喝水","prompt":"提醒我喝水","after_seconds":120}""",
        )
        assertTrue(!result.isError, result.text)
        val body = json(result)
        assertEquals("喝水", body["title"]?.jsonPrimitive?.content)
        assertEquals("active", body["status"]?.jsonPrimitive?.content)
        assertEquals("after_seconds", body["timing"]?.jsonObject?.get("kind")?.jsonPrimitive?.content)
        assertEquals(120, body["timing"]?.jsonObject?.get("after_seconds")?.jsonPrimitive?.content?.toInt())
        assertTrue(body["nextRunAt"]!!.jsonPrimitive.content.endsWith("Z"))
        assertEquals(1, host.tasks.size)
    }

    @Test
    fun `create rejects bad arguments with stable codes`() {
        val host = FakeHost()
        val badTiming = run(ScheduleCreateTool(), host, """{"title":"A","prompt":"p","every_seconds":30}""")
        assertTrue(badTiming.isError)
        assertEquals("frequency_too_high", badTiming.meta["code"])

        val missingTiming = run(ScheduleCreateTool(), host, """{"title":"A","prompt":"p"}""")
        assertEquals("invalid_timing", missingTiming.meta["code"])

        val badTitle = run(ScheduleCreateTool(), host, """{"title":"  ","prompt":"p","after_seconds":60}""")
        assertEquals("invalid_title", badTitle.meta["code"])
    }

    @Test
    fun `list shows active tasks only`() {
        val host = FakeHost()
        run(ScheduleCreateTool(), host, """{"title":"A","prompt":"a","after_seconds":60}""")
        run(ScheduleCreateTool(), host, """{"title":"B","prompt":"b","after_seconds":120}""")
        host.tasks[1] = host.tasks[1].copy(active = false)
        val body = json(run(ScheduleListTool(), host, "{}"))
        assertEquals(1, body["count"]?.jsonPrimitive?.content?.toInt())
        assertEquals(1, (body["tasks"] as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun `update requires a field and keeps the id`() {
        val host = FakeHost()
        val created = json(run(ScheduleCreateTool(), host, """{"title":"A","prompt":"a","after_seconds":60}"""))
        val id = created["id"]!!.jsonPrimitive.content

        val updated = json(run(ScheduleUpdateTool(), host, """{"id":"$id","title":"B"}"""))
        assertEquals("B", updated["title"]?.jsonPrimitive?.content)
        assertEquals(id, updated["id"]?.jsonPrimitive?.content)

        val empty = run(ScheduleUpdateTool(), host, """{"id":"$id"}""")
        assertTrue(empty.isError)
        assertEquals("invalid_timing", empty.meta["code"])
    }

    @Test
    fun `delete removes and reports`() {
        val host = FakeHost()
        val created = json(run(ScheduleCreateTool(), host, """{"title":"A","prompt":"a","after_seconds":60}"""))
        val id = created["id"]!!.jsonPrimitive.content
        val body = json(run(ScheduleDeleteTool(), host, """{"id":"$id"}"""))
        assertEquals(true, body["deleted"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(0, host.tasks.size)
        assertEquals("schedule_not_found", run(ScheduleDeleteTool(), host, """{"id":"$id"}""").meta["code"])
    }

    @Test
    fun `tools report unavailable without a host`() {
        val result = run(ScheduleCreateTool(), null, """{"title":"A","prompt":"p","after_seconds":60}""")
        assertTrue(result.isError)
        assertEquals("SCHEDULE_UNAVAILABLE", result.meta["code"])
    }

    @Test
    fun `builtin catalog advertises thirty-two tools`() {
        val names = BuiltinToolCatalog.allTools().map { it.name }
        assertTrue("schedule_create" in names)
        assertTrue("schedule_delete" in names)
        assertEquals(32, names.size)
    }
}
