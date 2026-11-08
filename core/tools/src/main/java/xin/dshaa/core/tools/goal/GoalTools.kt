package xin.dshaa.core.tools.goal

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xin.dshaa.core.model.GoalAuthority
import xin.dshaa.core.model.GoalException
import xin.dshaa.core.model.GoalView
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult

/**
 * goal 三件套（对齐 `@deepseek-ai/dsh-tool-goal`）：
 * `get_goal` / `create_goal` / `update_goal`。
 *
 * 输出约定（上游 README「三个工具都返回相同的紧凑 JSON」）：
 * ```json
 * {"goal":null}
 * // 或
 * {"goal":{"id","revision","objective","phase","roundsStarted","maxGoalRounds",["blockedReason"]},"activation":"armed"}
 * ```
 * 权限（上游 `lib/types/authority.js`）：
 * - `create_goal` / `edit` / `pause` / `resume` 要求**人类直连**的当前 turn；
 * - `complete` / `blocked` 允许人类直连，或**正好是当前目标这一轮**的自动续轮；
 * - 三轮 blocked 阈值只约束自动续轮（人类直连可以随时报受阻）。
 * 失败时把上游相同的错误文案作为 error 结果返回（meta.code 带稳定错误码）。
 */
class GetGoalTool : DshTool {
    override val name = "get_goal"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val host = ctx.goalHost ?: return unavailable()
        return ToolResult.ok(goalJson(host.current()))
    }
}

class CreateGoalTool : DshTool {
    override val name = "create_goal"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val host = ctx.goalHost ?: return unavailable()
        if (host.authority() !is GoalAuthority.DirectHuman) {
            return reject("this goal operation requires a direct human turn on a top-level agent")
        }
        val objective = args["objective"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (objective.isEmpty()) {
            return ToolResult.err("objective must be non-empty", meta = mapOf("code" to "GOAL_INVALID_OBJECTIVE"))
        }
        val maxRounds = args["max_goal_rounds"]?.jsonPrimitive?.intOrNull
        return runCatching { host.create(objective, maxRounds) }
            .fold({ ToolResult.ok(goalJson(it)) }, { errorResult(it) })
    }
}

class UpdateGoalTool : DshTool {
    override val name = "update_goal"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val host = ctx.goalHost ?: return unavailable()
        val goalId = args["goal_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val revision = args["revision"]?.jsonPrimitive?.intOrNull ?: 0
        val action = args["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val objective = args["objective"]?.jsonPrimitive?.contentOrNull
        val maxGoalRounds = args["max_goal_rounds"]?.jsonPrimitive?.intOrNull
        val blockedReason = args["blocked_reason"]?.jsonPrimitive?.contentOrNull

        val authority = host.authority()
        when (action) {
            "edit", "pause", "resume" -> if (authority !is GoalAuthority.DirectHuman) {
                return reject("this goal operation requires a direct human turn on a top-level agent")
            }
            "complete", "blocked" -> if (authority is GoalAuthority.None) {
                return reject("complete and blocked require a direct human turn or the current goal round")
            }
        }
        // 上游在进状态机之前就挡掉空原因（稳定码 GOAL_TOOL_INVALID_UPDATE）
        if (action == "blocked" && blockedReason.isNullOrBlank()) {
            return ToolResult.err(
                "blocked_reason is required with action blocked",
                meta = mapOf("code" to "GOAL_TOOL_INVALID_UPDATE"),
            )
        }
        // 三轮阈值只约束自动续轮；人类直连传 0（上游 authority.kind === "goal-round" 才判）
        val minBlockedRounds = if (authority is GoalAuthority.GoalRound) 3 else 0
        return runCatching {
            val outcome = host.update(
                goalId = goalId,
                revision = revision,
                action = action,
                objective = objective,
                maxGoalRounds = maxGoalRounds,
                blockedReason = blockedReason,
                authority = authority,
                minBlockedRounds = minBlockedRounds,
            )
            ToolResult.ok(goalJson(outcome.view), notice = outcome.notice)
        }.fold({ it }, { errorResult(it) })
    }
}

internal fun goalJson(view: GoalView?): String = if (view == null) {
    buildJsonObject { put("goal", kotlinx.serialization.json.JsonNull) }.toString()
} else {
    buildJsonObject {
        put(
            "goal",
            buildJsonObject {
                put("id", view.goal.id)
                put("revision", view.goal.revision)
                put("objective", view.goal.objective)
                put("phase", view.goal.phase)
                put("roundsStarted", view.roundsStarted)
                put("maxGoalRounds", view.goal.maxGoalRounds)
                view.goal.blockedReason?.let { reason ->
                    put(
                        "blockedReason",
                        buildJsonObject {
                            put("code", reason.code)
                            put("message", reason.message)
                        },
                    )
                }
            },
        )
        put("activation", view.activation)
    }.toString()
}

/** 上游 `reject()` 的默认码。 */
internal fun reject(message: String): ToolResult =
    ToolResult.err(message, meta = mapOf("code" to "GOAL_TOOL_AUTHORITY_REQUIRED"))

private fun unavailable(): ToolResult =
    ToolResult.err("goal tools are unavailable: no goal store attached", meta = mapOf("code" to "GOAL_UNAVAILABLE"))

private fun errorResult(error: Throwable): ToolResult = when (error) {
    is GoalException -> ToolResult.err(error.message ?: error.code, meta = mapOf("code" to error.code))
    else -> ToolResult.err("${error.javaClass.simpleName}: ${error.message}")
}
