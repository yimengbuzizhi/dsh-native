package xin.dshaa.core.session

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `plan/mode` 事件（M18）：折叠规则必须与上游 plan projection 一致——
 * 最后一条决定 active，`entered` 一旦进过就保持（exit_plan_mode 准入沿用到会话结束）。
 */
class PlanModeTest {

    private fun event(active: Boolean, seq: Int) = SessionCodec.parseEvent(
        """{"type":"plan/mode","seq":$seq,"time":1,"data":{"active":$active}}""",
    )!!

    @Test
    fun emptyLogIsInactive() {
        val view = SessionCodec.planModeOf(emptyList())
        assertFalse(view.active)
        assertFalse(view.entered)
    }

    @Test
    fun lastPlanModeWinsAndEntryIsSticky() {
        val events = listOf(
            SessionCodec.parseEvent("""{"type":"turn/start","seq":1,"time":1,"data":{"turn":1}}""")!!,
            event(true, 2),
            event(false, 3),
        )
        val view = SessionCodec.planModeOf(events)
        assertFalse(view.active)
        assertTrue(view.entered, "进过一次计划模式后 entered 保持（工具准入沿用到会话结束）")

        val again = SessionCodec.planModeOf(events + event(true, 4))
        assertTrue(again.active)
        assertTrue(again.entered)
    }

    @Test
    fun malformedOrUnknownEventsAreIgnored() {
        val events = listOf(
            SessionCodec.parseEvent("""{"type":"plan/mode","seq":1,"time":1,"data":{}}""")!!,
            SessionCodec.parseEvent("""{"type":"plan/mode","seq":2,"time":1,"data":{"active":"yes"}}""")!!,
            SessionCodec.parseEvent("""{"type":"user/message","seq":3,"time":1,"data":{}}""")!!,
        )
        val view = SessionCodec.planModeOf(events)
        assertFalse(view.active)
        assertFalse(view.entered)
    }

    @Test
    fun appendPlanModeIsPersistedAndReloadable() {
        val root = Files.createTempDirectory("plan-mode-test").toFile()
        val repo = SessionRepository(java.io.File(root, "sessions"))
        val session = repo.create(cwd = root.absolutePath, model = "deepseek-flash")
        session.startTurn()
        session.appendPlanMode(true)
        session.endTurn("completed")

        val reloaded = repo.load(session.header.id)!!
        val view = SessionCodec.planModeOf(reloaded.events)
        assertTrue(view.active)
        assertTrue(view.entered)
        val event = reloaded.events.single { it.type == "plan/mode" }
        assertEquals(true, event.data?.jsonObject?.get("active")?.jsonPrimitive?.booleanOrNull)
    }
}
