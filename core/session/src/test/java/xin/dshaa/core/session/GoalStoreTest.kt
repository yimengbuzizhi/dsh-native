package xin.dshaa.core.session

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xin.dshaa.core.model.GoalException
import xin.dshaa.core.model.SessionHeader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * GoalStore：与上游 `dsh-goal` 的持久化/折叠语义对齐。
 * 事件形状取自真机旧会话（v3 里的 goal/change）。
 */
class GoalStoreTest {

    private fun session(): SessionSession {
        val file = Files.createTempFile("goal-test", ".jsonl").toFile()
        val header = SessionHeader(
            version = 4,
            id = "session-goal-test",
            createdAt = 1_000L,
            cwd = "/tmp",
            isSeeded = false,
            delegationDepth = 0,
        )
        return SessionSession(file, header, emptyList())
    }

    @Test
    fun `create persists and folds from events`() {
        val session = session()
        val store = GoalStore(session)
        assertNull(store.current())

        val view = store.create("  Do the thing  ", 5)
        assertEquals("Do the thing", view.goal.objective)
        assertEquals(1, view.goal.revision)
        assertEquals("active", view.goal.phase)
        assertEquals(5, view.goal.maxGoalRounds)
        assertEquals(0, view.roundsStarted)
        assertEquals("armed", view.activation)
        assertNotNull(session.events.lastOrNull { it.type == "goal/change" })

        // 重新折叠（模拟重开 App）：activation 是进程内的，新 GoalStore 默认 disarmed
        val reloaded = GoalStore(session).current()!!
        assertEquals(view.goal.id, reloaded.goal.id)
        assertEquals(1, reloaded.goal.revision)
        assertEquals(view.createdAt, reloaded.createdAt)
        assertEquals("disarmed", reloaded.activation)
    }

    @Test
    fun `second create is rejected unless the first is complete`() {
        val store = GoalStore(session())
        val first = store.create("first")
        val error = assertFailsWith<GoalException> { store.create("second") }
        assertEquals("GOAL_ALREADY_EXISTS", error.code)

        // 上游：已完成的目标可以直接被新目标替换
        store.update(first.goal.id, 1, "complete")
        val second = store.create("second")
        assertEquals("second", second.goal.objective)
        assertEquals("active", second.goal.phase)
        assertEquals(0, second.roundsStarted)
    }

    @Test
    fun `edit bumps revision and only changes given fields`() {
        val store = GoalStore(session())
        val created = store.create("objective A", 10)
        val edited = store.update(created.goal.id, 1, "edit", objective = "objective B")
        assertEquals(2, edited.goal.revision)
        assertEquals("objective B", edited.goal.objective)
        assertEquals(10, edited.goal.maxGoalRounds)

        val recapped = store.update(created.goal.id, 2, "edit", maxGoalRounds = 20)
        assertEquals(3, recapped.goal.revision)
        assertEquals("objective B", recapped.goal.objective)
        assertEquals(20, recapped.goal.maxGoalRounds)

        val invalid = assertFailsWith<GoalException> {
            store.update(created.goal.id, 3, "edit", objective = "", maxGoalRounds = 0)
        }
        assertEquals("GOAL_INVALID_EDIT", invalid.code)
    }

    @Test
    fun `pause and resume follow upstream transitions`() {
        val store = GoalStore(session())
        val created = store.create("x")
        val paused = store.update(created.goal.id, 1, "pause")
        assertEquals("paused", paused.goal.phase)

        val resumePaused = assertFailsWith<GoalException> {
            store.update(created.goal.id, 2, "resume")
        }
        assertEquals("GOAL_TOOL_RESUME_PAUSED", resumePaused.code)

        val pauseAgain = assertFailsWith<GoalException> {
            store.update(created.goal.id, 2, "pause")
        }
        assertEquals("GOAL_INVALID_TRANSITION", pauseAgain.code)
    }

    @Test
    fun `complete ends the goal`() {
        val store = GoalStore(session())
        val created = store.create("x")
        val completed = store.update(created.goal.id, 1, "complete")
        assertEquals("complete", completed.goal.phase)
        assertEquals("disarmed", completed.activation)
        val again = assertFailsWith<GoalException> { store.update(created.goal.id, 2, "complete") }
        assertEquals("GOAL_INVALID_TRANSITION", again.code)
    }

    @Test
    fun `stale revision and wrong id are rejected`() {
        val store = GoalStore(session())
        val created = store.create("x")
        val stale = assertFailsWith<GoalException> { store.update(created.goal.id, 99, "pause") }
        assertEquals("GOAL_STALE_REVISION", stale.code)
        val wrong = assertFailsWith<GoalException> { store.update("goal-other", 1, "pause") }
        assertEquals("GOAL_STALE_REVISION", wrong.code)
    }

    @Test
    fun `goal rounds are counted from goal source messages`() {
        val session = session()
        val store = GoalStore(session)
        val created = store.create("x", 10)
        repeat(2) { round ->
            session.appendRaw(
                "user/message",
                buildJsonObject {
                    put("role", "user")
                    put(
                        "source",
                        buildJsonObject {
                            put("kind", "goal")
                            put("goalId", created.goal.id)
                            put("revision", 1)
                            put("round", round + 1)
                        },
                    )
                    put("content", kotlinx.serialization.json.buildJsonArray { })
                },
            )
        }
        assertEquals(2, store.current()!!.roundsStarted)
    }

    @Test
    fun `blocked enforces three consecutive rounds and records reason`() {
        val session = session()
        val store = GoalStore(session)
        val created = store.create("x", 10)

        val early = assertFailsWith<GoalException> {
            store.update(created.goal.id, 1, "blocked", blockedReason = "stuck")
        }
        assertEquals("GOAL_TOOL_BLOCK_THRESHOLD", early.code)

        repeat(3) { round ->
            session.appendRaw(
                "user/message",
                buildJsonObject {
                    put("role", "user")
                    put(
                        "source",
                        buildJsonObject {
                            put("kind", "goal")
                            put("goalId", created.goal.id)
                            put("round", round + 1)
                        },
                    )
                    put("content", kotlinx.serialization.json.buildJsonArray { })
                },
            )
        }
        val blocked = store.update(created.goal.id, 1, "blocked", blockedReason = "network is down")
        assertEquals("blocked", blocked.goal.phase)
        assertEquals("model-reported", blocked.goal.blockedReason?.code)
        assertEquals("network is down", blocked.goal.blockedReason?.message)

        val resumed = store.update(created.goal.id, 2, "resume")
        assertEquals("active", resumed.goal.phase)
        assertNull(resumed.goal.blockedReason)
    }

    @Test
    fun `clear tombstone removes the current goal`() {
        val session = session()
        val store = GoalStore(session)
        val created = store.create("x")
        session.appendRaw(
            "goal/change",
            buildJsonObject {
                put("kind", "goal/change")
                put("version", 1)
                put("operation", "clear")
                put(
                    "cleared",
                    buildJsonObject {
                        put("id", created.goal.id)
                        put("revision", 1)
                    },
                )
                put("clearedAt", 2_000L)
            },
        )
        assertNull(store.current())
    }

    @Test
    fun `activation follows create edit pause resume complete`() {
        val store = GoalStore(session(), activation = GoalActivationState())
        val created = store.create("x", 5)
        assertEquals("armed", created.activation)

        val edited = store.update(created.goal.id, 1, "edit", objective = "y")
        assertEquals("armed", edited.activation) // edit 保留 activation

        val paused = store.update(created.goal.id, 2, "pause")
        assertEquals("disarmed", paused.activation)
        assertEquals("paused", paused.goal.phase)

        val resumed = store.resumeAsUser(created.goal.id, 3)
        assertEquals("armed", resumed.activation)
        assertEquals("active", resumed.goal.phase)

        val completed = store.update(created.goal.id, 4, "complete")
        assertEquals("disarmed", completed.activation)
    }

    @Test
    fun `user resume re-arms active disarmed goal after session reload`() {
        val session = session()
        val created = GoalStore(session).create("x", 5)
        // 模拟重开 App 后打开会话：持久阶段还是 active，但续轮开关默认关
        val reopened = GoalStore(session, activation = GoalActivationState())
        assertEquals("disarmed", reopened.current()!!.activation)
        val active = reopened.resumeAsUser(created.goal.id, 1)
        assertEquals("active", active.goal.phase)
        assertEquals("armed", active.activation)
        assertEquals(2, active.goal.revision)
    }

    @Test
    fun `user can resume paused and clear the goal`() {
        val store = GoalStore(session())
        val created = store.create("x")
        store.update(created.goal.id, 1, "pause")
        val resumed = store.resumeAsUser(created.goal.id, 2)
        assertEquals("active", resumed.goal.phase)
        store.clearAsUser(created.goal.id, 3)
        assertNull(store.current())
        assertEquals("disarmed", store.activation.value)
    }

    @Test
    fun `goal change operation records the real action`() {
        val session = session()
        val store = GoalStore(session)
        val created = store.create("x", 10)
        store.update(created.goal.id, 1, "edit", objective = "y")
        store.update(created.goal.id, 2, "pause")
        val ops = session.events.filter { it.type == "goal/change" }
            .mapNotNull { it.data?.jsonObject?.get("operation")?.jsonPrimitive?.contentOrNull }
        assertEquals(listOf("create", "edit", "pause"), ops)
    }
}
