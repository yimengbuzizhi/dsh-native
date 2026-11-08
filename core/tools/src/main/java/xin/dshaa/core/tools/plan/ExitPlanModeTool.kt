package xin.dshaa.core.tools.plan

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import xin.dshaa.core.tools.AskOption
import xin.dshaa.core.tools.AskQuestion
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult

/**
 * `exit_plan_mode`（工具 29）：计划模式下把完整方案交给用户审批，批准后退出计划模式。
 *
 * 文案/行为对齐上游 `dsh-plan-mode`：
 * - 只允许在计划模式里调用（`exit_plan_mode is only available in plan mode`）；
 * - plan 必须是以 `#` 一级标题开头的非空 markdown；
 * - 审批问题 id `plan-review`、header `Plan review`、
 *   `Approve this plan and leave plan mode?`，选项 `Approve` / `Keep planning`；
 * - 批准 → 结果 `Plan approved — plan mode exited; carry out the plan starting with your next step.`，
 *   并在**下一个 step 起点**落 `plan/mode {active:false}`（narrate=false，工具结果已叙述）；
 * - 拒绝（或自定义反馈）→ 错误：`The user chose to keep planning; revise the plan and present it again.`
 *   （有反馈时 `their feedback: …`）。
 */
class ExitPlanModeTool : DshTool {
    override val name = "exit_plan_mode"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val host = ctx.planMode
            ?: return ToolResult.err("no plan-mode service is mounted")
        if (!host.active()) return ToolResult.err("exit_plan_mode is only available in plan mode")
        val plan = args["plan"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!Regex("^#\\s+\\S").containsMatchIn(plan.trim())) {
            return ToolResult.err("exit_plan_mode requires a non-empty markdown plan starting with a # heading")
        }
        val ask = ctx.askUser
            ?: return ToolResult.err(
                "no user-questions channel is available to review the plan; ask the user to switch the session mode instead",
            )
        // 跳过/空答案 = 用户没批准：按「keep planning」处理（上游取消通道返回 approved:false）
        val answer = ask(
            listOf(
                AskQuestion(
                    id = REVIEW_ID,
                    header = "Plan review",
                    question = "Approve this plan and leave plan mode?",
                    detail = plan,
                    options = listOf(
                        AskOption("Approve", "Leave plan mode; the plan is carried out from the next step."),
                        AskOption("Keep planning", "Stay in plan mode; feedback goes back to the model."),
                    ),
                ),
            ),
        ).firstOrNull { it.id == REVIEW_ID }
        val approved = answer != null &&
            answer.selected.size == 1 &&
            answer.selected[0] == APPROVE_LABEL &&
            answer.custom.isBlank()
        if (!approved) {
            val feedback = answer?.custom?.trim().orEmpty()
            return ToolResult.err(
                if (feedback.isEmpty()) {
                    "The user chose to keep planning; revise the plan and present it again."
                } else {
                    "The user chose to keep planning; their feedback: $feedback"
                },
            )
        }
        host.requestExit()
        return ToolResult.ok("Plan approved — plan mode exited; carry out the plan starting with your next step.")
    }

    private companion object {
        const val REVIEW_ID = "plan-review"
        const val APPROVE_LABEL = "Approve"
    }
}
