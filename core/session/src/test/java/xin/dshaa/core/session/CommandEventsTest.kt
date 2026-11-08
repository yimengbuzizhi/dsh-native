package xin.dshaa.core.session

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `command/run` / `command/done`（M21）：上游 `dsh-commands` 的日志生命周期。
 * 命令不包 turn，直接作为仅日志事件追加；字段顺序与 `settle()` 一致。
 */
class CommandEventsTest {

    @Test
    fun commandLifecycleIsPersistedInOrder() {
        val root = Files.createTempDirectory("command-events").toFile()
        val repo = SessionRepository(File(root, "sessions"))
        val session = repo.create(cwd = root.absolutePath, model = "deepseek-flash")
        val runSeq = session.appendCommandRun("cmd-abc-1", "plan", " off")
        val doneSeq = session.appendCommandDone("cmd-abc-1", "success", "Plan mode off.")
        session.appendCommandDone("cmd-abc-2", "error", "boom")

        val reloaded = repo.load(session.header.id)!!
        val events = reloaded.events.filter { it.type == "command/run" || it.type == "command/done" }
        assertEquals(listOf("command/run", "command/done", "command/done"), events.map { it.type })
        assertEquals(runSeq, events[0].seq)
        assertEquals(doneSeq, events[1].seq)

        val run = events[0].data!!.jsonObject
        assertEquals("cmd-abc-1", run["commandId"]!!.jsonPrimitive.content)
        assertEquals("plan", run["name"]!!.jsonPrimitive.content)
        assertEquals(" off", run["args"]!!.jsonPrimitive.content)
        assertEquals("user", run["source"]!!.jsonObject["kind"]!!.jsonPrimitive.content)

        val done = events[1].data!!.jsonObject
        assertEquals("cmd-abc-1", done["commandId"]!!.jsonPrimitive.content)
        assertEquals("success", done["kind"]!!.jsonPrimitive.content)
        assertEquals("Plan mode off.", done["text"]!!.jsonPrimitive.content)
        assertFalse(done.containsKey("sourceEventSeq"))

        // 命令事件不进消息投影（命令行/结果永不成为模型消息）
        assertTrue(SessionCodec.messagesOf(reloaded.events).isEmpty())
    }

    @Test
    fun recordInputFalseOmitsArgs() {
        val root = Files.createTempDirectory("command-events-2").toFile()
        val repo = SessionRepository(File(root, "sessions"))
        val session = repo.create(cwd = root.absolutePath, model = "deepseek-flash")
        session.appendCommandRun("cmd-abc-1", "plan", " off", recordInput = false)

        val run = repo.load(session.header.id)!!.events.single { it.type == "command/run" }.data!!.jsonObject
        assertFalse(run.containsKey("args"))
    }
}
