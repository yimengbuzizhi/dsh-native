package xin.dshaa.core.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xin.dshaa.core.tools.plan.ExitPlanModeTool
import xin.dshaa.core.tools.workspace.LoadWorkspaceDependenciesTool
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 计划模式工具（M18）：
 * - `exit_plan_mode` 只在计划模式可用；plan 必须以 `#` 标题开头；
 * - 审批问题 id/选项文案对齐上游；批准 → `Plan approved — …` 并请求退出；
 *   拒绝/自定义反馈 → 上游同形错误文案；
 * - `load_workspace_dependencies` 在 Android 没有 payload，如实报不可用。
 */
class PlanToolsTest {

    private class FakePlan(active: Boolean) : PlanModeHost {
        var activeNow = active
        var exits = 0
        override fun active(): Boolean = activeNow
        override fun requestExit() {
            exits++
            activeNow = false
        }
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    private fun ctx(
        plan: PlanModeHost?,
        answers: List<AskAnswer>? = null,
        questions: MutableList<List<AskQuestion>> = mutableListOf(),
    ): ToolContext = ToolContext(
        workspace = File("."),
        askUser = if (answers == null) null else { qs ->
            questions += qs
            answers
        },
        planMode = plan,
    )

    @Test
    fun onlyAvailableInPlanMode() = runBlocking {
        val result = ExitPlanModeTool().execute(args("plan" to "# Plan"), ctx(FakePlan(active = false)))
        assertTrue(result.isError)
        assertEquals("exit_plan_mode is only available in plan mode", result.text)
    }

    @Test
    fun planMustStartWithHeading() = runBlocking {
        val result = ExitPlanModeTool().execute(
            args("plan" to "just prose"),
            ctx(FakePlan(active = true), answers = listOf(AskAnswer("plan-review", listOf("Approve")))),
        )
        assertTrue(result.isError)
        assertEquals("exit_plan_mode requires a non-empty markdown plan starting with a # heading", result.text)
    }

    @Test
    fun approveLeavesPlanModeAndReturnsUpstreamText() = runBlocking {
        val plan = FakePlan(active = true)
        val asked = mutableListOf<List<AskQuestion>>()
        val result = ExitPlanModeTool().execute(
            args("plan" to "# Migrate\n\nDo the thing."),
            ctx(plan, answers = listOf(AskAnswer("plan-review", listOf("Approve"))), questions = asked),
        )
        assertTrue(!result.isError)
        assertEquals("Plan approved — plan mode exited; carry out the plan starting with your next step.", result.text)
        assertEquals(1, plan.exits)

        val question = asked.single().single()
        assertEquals("plan-review", question.id)
        assertEquals("Plan review", question.header)
        assertEquals("Approve this plan and leave plan mode?", question.question)
        assertEquals("# Migrate\n\nDo the thing.", question.detail)
        assertEquals(listOf("Approve", "Keep planning"), question.options.map { it.label })
    }

    @Test
    fun keepPlanningReturnsUpstreamError() = runBlocking {
        val plan = FakePlan(active = true)
        val result = ExitPlanModeTool().execute(
            args("plan" to "# Plan"),
            ctx(plan, answers = listOf(AskAnswer("plan-review", listOf("Keep planning")))),
        )
        assertTrue(result.isError)
        assertEquals("The user chose to keep planning; revise the plan and present it again.", result.text)
        assertEquals(0, plan.exits)
    }

    @Test
    fun feedbackIsEchoedInTheError() = runBlocking {
        val result = ExitPlanModeTool().execute(
            args("plan" to "# Plan"),
            ctx(FakePlan(active = true), answers = listOf(AskAnswer("plan-review", emptyList(), custom = "add tests"))),
        )
        assertTrue(result.isError)
        assertEquals("The user chose to keep planning; their feedback: add tests", result.text)
    }

    @Test
    fun skippedReviewCountsAsKeepPlanning() = runBlocking {
        val result = ExitPlanModeTool().execute(
            args("plan" to "# Plan"),
            ctx(FakePlan(active = true), answers = listOf(AskAnswer("plan-review"))),
        )
        assertTrue(result.isError)
        assertEquals("The user chose to keep planning; revise the plan and present it again.", result.text)
    }

    @Test
    fun noUserChannelIsReported() = runBlocking {
        val result = ExitPlanModeTool().execute(args("plan" to "# Plan"), ctx(FakePlan(active = true)))
        assertTrue(result.isError)
        assertTrue(result.text.contains("no user-questions channel is available"))
    }

    @Test
    fun workspaceDependenciesReportMissingAndroidPayload() = runBlocking {
        val result = LoadWorkspaceDependenciesTool().execute(buildJsonObject { }, ToolContext(File(".")))
        assertTrue(result.isError)
        assertTrue(result.text.contains("not bundled in this Android build"))
    }
}
