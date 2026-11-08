package xin.dshaa.core.session

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xin.dshaa.core.model.GoalPhase
import xin.dshaa.core.model.SessionHeader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * goal-round-driver：提示词逐字节对齐上游 + idle/中断状态机。
 * 上游源码：`.upstream/oracle/.../@deepseek-ai/dsh-goal-round-driver/lib/index.js`。
 */
class GoalRoundDriverTest {

    private fun session(): SessionSession {
        val file = Files.createTempFile("goal-round-test", ".jsonl").toFile()
        val header = SessionHeader(
            version = 4,
            id = "session-goal-round-test",
            createdAt = 1_000L,
            cwd = "/tmp",
        )
        return SessionSession(file, header, emptyList())
    }

    private fun appendGoalRound(session: SessionSession, goalId: String, revision: Int, round: Int) {
        session.appendRaw(
            "user/message",
            buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray { })
                put(
                    "source",
                    buildJsonObject {
                        put("kind", "goal")
                        put("goalId", goalId)
                        put("revision", revision)
                        put("round", round)
                    },
                )
            },
        )
    }

    @Test
    fun `prompt is byte-identical to the upstream goal_round block`() {
        val expected =
            "<goal_round>\n" +
                "Objective: \"把工具补到 20 个\"\n" +
                "Round: 2/3\n" +
                "\n" +
                "Continue working toward the objective in this same session. Treat the current workspace, " +
                "tool results, and durable session state as authoritative; inspect them instead of assuming " +
                "earlier narration is still current. Make concrete progress and verify the result. Before " +
                "claiming completion, gather evidence that the whole objective is achieved, read the current " +
                "goal, and mark it complete. If work remains, leave the goal active for the next round. " +
                "Follow the configured goal-tool policy before reporting a blocker.\n" +
                "</goal_round>"
        assertEquals(expected, GoalRoundPrompt.render("把工具补到 20 个", 2, 3))
    }

    @Test
    fun `objective quoting matches JSON stringify`() {
        assertEquals("\"a\\\"b\\nc\\\\d\"", GoalRoundPrompt.quote("a\"b\nc\\d"))
        assertEquals("\"把工具补到 20 个\"", GoalRoundPrompt.quote("把工具补到 20 个"))
        assertEquals("\"\\u0001\"", GoalRoundPrompt.quote("\u0001"))
    }

    @Test
    fun `armed active goal continues with the next round`() {
        val session = session()
        val store = GoalStore(session)
        val created = store.create("x", 5)
        appendGoalRound(session, created.goal.id, 1, 1)
        val driver = GoalRoundDriver(store)
        val decision = assertIs<GoalIdleDecision.Continue>(driver.decideAtIdle())
        assertEquals(2, decision.round)
        assertEquals(created.goal.id, decision.view.goal.id)
        assertEquals(1, decision.view.roundsStarted)
    }

    @Test
    fun `cap exhaustion writes round-limit blocked`() {
        val session = session()
        val store = GoalStore(session)
        val created = store.create("x", 1)
        appendGoalRound(session, created.goal.id, 1, 1)
        val decision = assertIs<GoalIdleDecision.Blocked>(GoalRoundDriver(store).decideAtIdle())
        assertEquals(GoalPhase.BLOCKED, decision.view.goal.phase)
        assertEquals("round-limit", decision.view.goal.blockedReason?.code)
        assertEquals(
            "Goal reached its configured limit of 1 rounds.",
            decision.view.goal.blockedReason?.message,
        )
        assertEquals("disarmed", decision.view.activation)
        assertEquals(1, decision.view.roundsStarted)
    }

    @Test
    fun `paused or disarmed goals never continue`() {
        val pausedStore = GoalStore(session())
        val paused = pausedStore.create("x", 5)
        pausedStore.pauseAsUser(paused.goal.id, 1)
        assertEquals(GoalIdleDecision.Stop, GoalRoundDriver(pausedStore).decideAtIdle())

        val disarmedStore = GoalStore(session())
        disarmedStore.create("x", 5)
        disarmedStore.disarm()
        assertEquals(GoalIdleDecision.Stop, GoalRoundDriver(disarmedStore).decideAtIdle())
    }

    @Test
    fun `interrupting the current goal round pauses the goal`() {
        val store = GoalStore(session())
        val created = store.create("x", 5)
        val view = store.current()!!
        val paused = assertNotNull(
            GoalRoundDriver(store).onInterrupted(GoalRoundRef(created.goal.id, view.goal.revision, 1)),
        )
        assertEquals(GoalPhase.PAUSED, paused.goal.phase)
        assertEquals("disarmed", paused.activation)
    }

    @Test
    fun `unrelated cancellation only disarms`() {
        val store = GoalStore(session())
        store.create("x", 5)
        GoalRoundDriver(store).onInterrupted(null)
        val view = assertNotNull(store.current())
        assertEquals(GoalPhase.ACTIVE, view.goal.phase)
        assertEquals("disarmed", view.activation)
        assertTrue(!GoalRoundDriver(store).canContinue())
    }
}
