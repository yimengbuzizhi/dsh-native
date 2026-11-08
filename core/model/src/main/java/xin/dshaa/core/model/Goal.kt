package xin.dshaa.core.model

/**
 * 目标（goal）领域模型 —— 对齐上游 `@deepseek-ai/dsh-goal` 的 GoalSnapshot / GoalView。
 *
 * 持久层只存 [GoalSnapshot]（会话里 `goal/change` 事件），[GoalView] 是读时投影：
 * roundsStarted 由 goal 轮次消息累计，activation 是进程内状态（上游从不持久化）。
 */
data class GoalBlockReason(
    /** 稳定的 lower-kebab-case 分类；模型自报时上游写 `model-reported`。 */
    val code: String,
    val message: String,
)

data class GoalSnapshot(
    val id: String,
    val revision: Int,
    val objective: String,
    /** active / paused / blocked / complete */
    val phase: String,
    val maxGoalRounds: Int,
    val blockedReason: GoalBlockReason? = null,
)

data class GoalView(
    val goal: GoalSnapshot,
    val roundsStarted: Int,
    val createdAt: Long,
    val updatedAt: Long,
    /** armed / disarmed（进程内可续轮状态，不持久化） */
    val activation: String,
)

/**
 * 一次 goal 工具调用的授权来源（对齐上游 `dsh-tool-goal/lib/types/authority.js`）。
 *
 * 上游在**当前 open turn 的事件**里找证据，不是看模型怎么说：
 * - [DirectHuman]：本 turn 里有宿主认可的 `source.kind:"user"` 消息（手机端只有根 agent，恒成立）；
 * - [GoalRound]：本 turn 正好是该目标本轮（goal 来源消息的 goalId / revision / round 全对上）；
 * - [None]：都没有（例如 turn 已收口）。
 */
sealed interface GoalAuthority {
    data object None : GoalAuthority

    data object DirectHuman : GoalAuthority

    data class GoalRound(val goalId: String, val revision: Int, val round: Int) : GoalAuthority
}

/** 目标操作错误：code 与上游 GoalError 对齐，message 给模型/人看。 */
class GoalException(val code: String, message: String) : Exception(message)

object GoalPhase {
    const val ACTIVE = "active"
    const val PAUSED = "paused"
    const val BLOCKED = "blocked"
    const val COMPLETE = "complete"
}

object GoalActivation {
    const val ARMED = "armed"
    const val DISARMED = "disarmed"
}
