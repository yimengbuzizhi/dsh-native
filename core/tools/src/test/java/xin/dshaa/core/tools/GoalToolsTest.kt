package xin.dshaa.core.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import xin.dshaa.core.model.GoalAuthority
import xin.dshaa.core.model.GoalBlockReason
import xin.dshaa.core.model.GoalException
import xin.dshaa.core.model.GoalSnapshot
import xin.dshaa.core.model.GoalView
import xin.dshaa.core.tools.goal.CreateGoalTool
import xin.dshaa.core.tools.goal.GetGoalTool
import xin.dshaa.core.tools.goal.UpdateGoalTool
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** goal 三件套的输出契约（上游：三个工具返回相同的紧凑 JSON）。 */
class GoalToolsTest {

    private class FakeHost(
        var view: GoalView? = null,
        var lastUpdate: List<Any?>? = null,
        var authority: GoalAuthority = GoalAuthority.DirectHuman,
        /** 收尾通知（由 App 的 GoalHost 适配器在 goal 轮 complete/blocked 时生成）。 */
        var noticeOnUpdate: GoalNotice? = null,
    ) : GoalHost {
        override fun current(): GoalView? = view

        override fun authority(): GoalAuthority = authority

        override fun create(objective: String, maxGoalRounds: Int?): GoalView {
            if (view != null) throw GoalException("GOAL_ALREADY_EXISTS", "a goal already exists in this session")
            view = GoalView(
                goal = GoalSnapshot(
                    id = "goal-1",
                    revision = 1,
                    objective = objective,
                    phase = "active",
                    maxGoalRounds = maxGoalRounds ?: 256,
                ),
                roundsStarted = 0,
                createdAt = 1L,
                updatedAt = 1L,
                activation = "armed",
            )
            return view!!
        }

        override fun update(
            goalId: String,
            revision: Int,
            action: String,
            objective: String?,
            maxGoalRounds: Int?,
            blockedReason: String?,
            authority: GoalAuthority,
            minBlockedRounds: Int,
        ): GoalUpdateResult {
            lastUpdate = listOf(goalId, revision, action, objective, maxGoalRounds, blockedReason, authority, minBlockedRounds)
            val current = view ?: throw GoalException("GOAL_NOT_FOUND", "no current goal")
            val next = current.goal.copy(
                revision = current.goal.revision + 1,
                objective = objective ?: current.goal.objective,
                phase = if (action == "complete") "complete" else current.goal.phase,
            )
            view = current.copy(goal = next)
            return GoalUpdateResult(view!!, noticeOnUpdate)
        }
    }

    private fun ctx(host: GoalHost?) = ToolContext(workspace = File("."), goalHost = host)

    @Test
    fun `get_goal returns null goal when absent`() = runBlocking {
        val result = GetGoalTool().execute(kotlinx.serialization.json.JsonObject(emptyMap()), ctx(FakeHost()))
        assertEquals("""{"goal":null}""", result.text)
        assertTrue(!result.isError)
    }

    @Test
    fun `create_goal returns upstream compact json`() = runBlocking {
        val args = kotlinx.serialization.json.Json.parseToJsonElement("""{"objective":"ship it"}""").jsonObject
        val result = CreateGoalTool().execute(args, ctx(FakeHost()))
        assertEquals(
            """{"goal":{"id":"goal-1","revision":1,"objective":"ship it","phase":"active","roundsStarted":0,"maxGoalRounds":256},"activation":"armed"}""",
            result.text,
        )
    }

    @Test
    fun `create_goal rejects empty objective`() = runBlocking {
        val args = kotlinx.serialization.json.Json.parseToJsonElement("""{"objective":"  "}""").jsonObject
        val result = CreateGoalTool().execute(args, ctx(FakeHost()))
        assertTrue(result.isError)
        assertEquals("GOAL_INVALID_OBJECTIVE", result.meta["code"])
    }

    @Test
    fun `create_goal surfaces duplicate goal`() = runBlocking {
        val host = FakeHost()
        host.create("first", null)
        val args = kotlinx.serialization.json.Json.parseToJsonElement("""{"objective":"second"}""").jsonObject
        val result = CreateGoalTool().execute(args, ctx(host))
        assertTrue(result.isError)
        assertEquals("GOAL_ALREADY_EXISTS", result.meta["code"])
    }

    @Test
    fun `update_goal forwards exact ref and fields`() = runBlocking {
        val host = FakeHost()
        host.create("first", null)
        val args = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"goal_id":"goal-1","revision":1,"action":"edit","objective":"new objective","max_goal_rounds":9}""",
        ).jsonObject
        val result = UpdateGoalTool().execute(args, ctx(host))
        assertTrue(!result.isError, result.text)
        assertEquals(
            listOf<Any?>("goal-1", 1, "edit", "new objective", 9, null, GoalAuthority.DirectHuman, 0),
            host.lastUpdate,
        )
        assertTrue(result.text.contains("\"revision\":2"))
    }

    // ---------------- 授权（上游 authority.js：create/edit/pause/resume 要人类，complete/blocked 还允许当前轮） ----------------

    private fun goalRound(r: Int = 1) = GoalAuthority.GoalRound("goal-1", 1, r)

    @Test
    fun `create_goal requires a direct human turn`() = runBlocking {
        val args = kotlinx.serialization.json.Json.parseToJsonElement("""{"objective":"ship it"}""").jsonObject
        val result = CreateGoalTool().execute(args, ctx(FakeHost(authority = goalRound())))
        assertTrue(result.isError)
        assertEquals("GOAL_TOOL_AUTHORITY_REQUIRED", result.meta["code"])
        assertEquals("this goal operation requires a direct human turn on a top-level agent", result.text)
    }

    @Test
    fun `edit pause resume require a direct human turn`() = runBlocking {
        for (action in listOf("edit", "pause", "resume")) {
            val host = FakeHost(authority = goalRound())
            host.create("first", null)
            val args = kotlinx.serialization.json.Json.parseToJsonElement(
                """{"goal_id":"goal-1","revision":1,"action":"$action","objective":"x"}""",
            ).jsonObject
            val result = UpdateGoalTool().execute(args, ctx(host))
            assertTrue(result.isError, action)
            assertEquals("GOAL_TOOL_AUTHORITY_REQUIRED", result.meta["code"], action)
            assertTrue(host.lastUpdate == null, "$action must not reach the store")
        }
    }

    @Test
    fun `complete is allowed on the current goal round and carries the wrapup notice`() = runBlocking {
        val host = FakeHost(authority = goalRound())
        host.create("ship it", null)
        host.noticeOnUpdate = GoalNotice("<goal_complete>…", "complete: ship it")
        val args = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"goal_id":"goal-1","revision":1,"action":"complete"}""",
        ).jsonObject
        val result = UpdateGoalTool().execute(args, ctx(host))
        assertTrue(!result.isError, result.text)
        assertEquals("complete: ship it", result.notice?.summary)
        // 自动续轮传 3 轮阈值；人类直连传 0（上游只对 goal 轮判阈值）
        assertEquals(goalRound(), host.lastUpdate?.get(6))
        assertEquals(3, host.lastUpdate?.get(7))
    }

    @Test
    fun `complete and blocked without any authority are rejected`() = runBlocking {
        for (action in listOf("complete", "blocked")) {
            val host = FakeHost(authority = GoalAuthority.None)
            host.create("first", null)
            val args = kotlinx.serialization.json.Json.parseToJsonElement(
                """{"goal_id":"goal-1","revision":1,"action":"$action","blocked_reason":"stuck"}""",
            ).jsonObject
            val result = UpdateGoalTool().execute(args, ctx(host))
            assertTrue(result.isError, action)
            assertEquals("GOAL_TOOL_AUTHORITY_REQUIRED", result.meta["code"], action)
            assertEquals("complete and blocked require a direct human turn or the current goal round", result.text)
        }
    }

    @Test
    fun `blocked requires a reason before the state machine`() = runBlocking {
        val host = FakeHost(authority = GoalAuthority.DirectHuman)
        host.create("first", null)
        val args = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"goal_id":"goal-1","revision":1,"action":"blocked","blocked_reason":"  "}""",
        ).jsonObject
        val result = UpdateGoalTool().execute(args, ctx(host))
        assertTrue(result.isError)
        assertEquals("GOAL_TOOL_INVALID_UPDATE", result.meta["code"])
        assertEquals("blocked_reason is required with action blocked", result.text)
    }

    @Test
    fun `human direct complete has no round threshold`() = runBlocking {
        val host = FakeHost(authority = GoalAuthority.DirectHuman)
        host.create("first", null)
        val args = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"goal_id":"goal-1","revision":1,"action":"complete"}""",
        ).jsonObject
        UpdateGoalTool().execute(args, ctx(host))
        assertEquals(0, host.lastUpdate?.get(7))
    }

    @Test
    fun `goal tools are unavailable without a host`() = runBlocking {
        val result = GetGoalTool().execute(kotlinx.serialization.json.JsonObject(emptyMap()), ctx(null))
        assertTrue(result.isError)
        assertEquals("GOAL_UNAVAILABLE", result.meta["code"])
    }

    @Test
    fun `blocked reason is rendered in compact json`() = runBlocking {
        val host = FakeHost(
            GoalView(
                goal = GoalSnapshot(
                    id = "goal-1",
                    revision = 2,
                    objective = "x",
                    phase = "blocked",
                    maxGoalRounds = 5,
                    blockedReason = GoalBlockReason("model-reported", "no network"),
                ),
                roundsStarted = 3,
                createdAt = 1L,
                updatedAt = 2L,
                activation = "disarmed",
            ),
        )
        val result = GetGoalTool().execute(kotlinx.serialization.json.JsonObject(emptyMap()), ctx(host))
        assertEquals(
            """{"goal":{"id":"goal-1","revision":2,"objective":"x","phase":"blocked","roundsStarted":3,"maxGoalRounds":5,"blockedReason":{"code":"model-reported","message":"no network"}},"activation":"disarmed"}""",
            result.text,
        )
    }
}
