package xin.dshaa.core.session

import xin.dshaa.core.model.GoalActivation
import xin.dshaa.core.model.GoalPhase
import xin.dshaa.core.model.GoalView

/**
 * 自动续轮（对齐上游 `@deepseek-ai/dsh-goal-round-driver`）。
 *
 * 上游语义（README + lib/index.js）里与手机端相关的部分：
 * - 只在 agent 完全 idle 时起一轮；active + **armed** + 还有轮次额度才续；
 * - 每轮是一条 goal 来源的 user 消息（`source={kind:"goal",goalId,revision,round}`），
 *   正文是固定的 `<goal_round>` 提示词（逐字节移植，见 [GoalRoundPrompt]）；
 * - 只有真正进入历史的 goal 消息才消耗额度（本实现 = 写入即进入）；
 * - 额度耗尽时由驱动写 `blocked`，稳定错误码 `round-limit`，**不走**工具的三轮阈值；
 * - pause/complete/blocked/clear 立即中止；用户 interrupt 正在跑的 goal 轮 → 下一 idle 点 pause；
 * - 会话恢复/新进程后 active 目标默认 disarmed，必须用户显式 resume 才继续。
 *
 * 手机端没有多 agent/队列，所以队列争抢、pre-step fence、durability flush 这些
 * 上游并发栅栏不适用；这里只保留可观察的状态机。
 */
class GoalRoundDriver(private val store: GoalStore) {

    /** idle 点的判决。 */
    fun decideAtIdle(): GoalIdleDecision {
        val view = store.current() ?: return GoalIdleDecision.Stop
        if (view.goal.phase != GoalPhase.ACTIVE) return GoalIdleDecision.Stop
        if (view.activation != GoalActivation.ARMED) return GoalIdleDecision.Stop
        if (view.roundsStarted >= view.goal.maxGoalRounds) {
            val blocked = runCatching {
                store.blockByDriver(
                    goalId = view.goal.id,
                    revision = view.goal.revision,
                    code = "round-limit",
                    message = "Goal reached its configured limit of ${view.goal.maxGoalRounds} rounds.",
                )
            }.getOrNull()
            return if (blocked == null) GoalIdleDecision.Stop else GoalIdleDecision.Blocked(blocked)
        }
        return GoalIdleDecision.Continue(view, view.roundsStarted + 1)
    }

    /**
     * 用户停止/异常中断后的收尾：
     * - 被中断的就是当前目标当前 revision 的 goal 轮 → pause（与上游「next idle 点 pause」一致）；
     * - 其它中断（普通对话、会话切换）→ 只 disarm，持久阶段不变。
     */
    fun onInterrupted(round: GoalRoundRef?): GoalView? {
        val view = store.current() ?: run {
            store.disarm()
            return null
        }
        val sameRound = round != null &&
            view.goal.id == round.goalId &&
            view.goal.revision == round.revision &&
            view.goal.phase == GoalPhase.ACTIVE
        if (!sameRound) {
            store.disarm()
            return view
        }
        if (view.activation != GoalActivation.ARMED) {
            store.disarm()
            return view
        }
        val paused = runCatching { store.pauseAsUser(view.goal.id, view.goal.revision) }.getOrNull()
        if (paused == null) store.disarm()
        return paused ?: store.current()
    }

    /** 异常/上限 token 后只关续轮，不 retry（上游：transient failure 要人 resume）。 */
    fun disarm() {
        store.disarm()
    }

    /** 当前目标是否还能自动续轮（UI 用）。 */
    fun canContinue(): Boolean {
        val view = store.current() ?: return false
        return view.goal.phase == GoalPhase.ACTIVE &&
            view.activation == GoalActivation.ARMED &&
            view.roundsStarted < view.goal.maxGoalRounds
    }

    /** 给模型看的一轮 prompt（objective 已 JSON 引号转义）。 */
    fun promptFor(view: GoalView, round: Int): String =
        GoalRoundPrompt.render(view.goal.objective, round, view.goal.maxGoalRounds)
}

sealed interface GoalIdleDecision {
    data class Continue(val view: GoalView, val round: Int) : GoalIdleDecision
    data class Blocked(val view: GoalView) : GoalIdleDecision
    data object Stop : GoalIdleDecision
}

/** 当前正在跑的 goal 轮（写进 user/message.source，也是中断时对账的凭据）。 */
data class GoalRoundRef(val goalId: String, val revision: Int, val round: Int)

/**
 * 一轮 goal 的提示词，逐字节对齐上游 `dsh-goal-round-driver/lib/index.js` 的
 * `renderGoalRoundPrompt`（契约 #1：提示词等价）。
 */
object GoalRoundPrompt {
    fun render(objective: String, round: Int, maxGoalRounds: Int): String =
        "<goal_round>\n" +
            "Objective: ${quote(objective)}\n" +
            "Round: $round/$maxGoalRounds\n" +
            "\n" +
            "Continue working toward the objective in this same session. Treat the current workspace, " +
            "tool results, and durable session state as authoritative; inspect them instead of assuming " +
            "earlier narration is still current. Make concrete progress and verify the result. Before " +
            "claiming completion, gather evidence that the whole objective is achieved, read the current " +
            "goal, and mark it complete. If work remains, leave the goal active for the next round. " +
            "Follow the configured goal-tool policy before reporting a blocker.\n" +
            "</goal_round>"

    /**
     * JS `JSON.stringify(string)` 的等价实现：转义 `"`、`\` 与 U+0000–U+001F，
     * 非 ASCII（中文等）原样保留。上游把 objective 直接嵌进 prompt，必须同形。
     */
    fun quote(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') {
                    append("\\u")
                    append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }
}
