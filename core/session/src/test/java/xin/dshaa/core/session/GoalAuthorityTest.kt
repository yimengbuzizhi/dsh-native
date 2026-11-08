package xin.dshaa.core.session

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xin.dshaa.core.model.GoalAuthority
import xin.dshaa.core.model.SessionHeader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * goal 工具授权（对齐上游 `dsh-tool-goal/lib/types/authority.js`）：
 * 证据只看**当前 open turn** 的 user/message source，不看模型自述。
 */
class GoalAuthorityTest {

    private fun session(): SessionSession {
        val file = Files.createTempFile("goal-authority", ".jsonl").toFile()
        return SessionSession(
            file,
            SessionHeader(version = 4, id = "session-authority-test", createdAt = 1_000L, cwd = "/tmp"),
            emptyList(),
        )
    }

    private fun goalSource(session: SessionSession, round: Int, revision: Int = 1): SessionSession {
        val goal = GoalStore(session).current()!!
        session.appendUserMessage(
            "<goal_round>",
            buildJsonObject {
                put("kind", "goal")
                put("goalId", goal.goal.id)
                put("revision", revision)
                put("round", round)
            },
        )
        return session
    }

    @Test
    fun `no open turn has no authority`() {
        val session = session()
        val store = GoalStore(session)
        store.create("do it", 5)
        assertEquals(GoalAuthority.None, store.authority())
    }

    @Test
    fun `human message in the open turn is direct human`() {
        val session = session()
        val store = GoalStore(session)
        store.create("do it", 5)
        session.startTurn()
        session.startStep()
        session.appendUserMessage("please continue")
        assertEquals(GoalAuthority.DirectHuman, store.authority())
    }

    @Test
    fun `closed turn has no authority`() {
        val session = session()
        val store = GoalStore(session)
        store.create("do it", 5)
        session.startTurn()
        session.startStep()
        session.appendUserMessage("hello")
        session.endStep()
        session.endTurn()
        assertEquals(GoalAuthority.None, store.authority())
    }

    @Test
    fun `the current goal round is a goal round authority`() {
        val session = session()
        val store = GoalStore(session)
        store.create("do it", 5)
        session.startTurn()
        session.startStep()
        goalSource(session, round = 1)
        val view = store.current()!!
        val authority = store.authority()
        assertIs<GoalAuthority.GoalRound>(authority)
        assertEquals(view.goal.id, authority.goalId)
        assertEquals(1, authority.revision)
        assertEquals(1, authority.round)
    }

    @Test
    fun `a stale round number or revision is not authority`() {
        val session = session()
        val store = GoalStore(session)
        store.create("do it", 5)

        // 第 1 轮已跑完，第 2 轮正在跑；此刻再来一条 round=1 的消息不构成授权
        session.startTurn()
        session.startStep()
        goalSource(session, round = 1)
        session.endStep()
        session.endTurn()

        session.startTurn()
        session.startStep()
        goalSource(session, round = 2)
        session.endStep()
        session.endTurn()

        session.startTurn()
        session.startStep()
        goalSource(session, round = 1)
        assertEquals(GoalAuthority.None, store.authority())

        session.endStep()
        session.endTurn()
        session.startTurn()
        session.startStep()
        goalSource(session, round = 3, revision = 99)
        assertEquals(GoalAuthority.None, store.authority())
    }
}
