package xin.dshaa.core.engine

/**
 * 会话级计划模式状态（M18，对齐上游 `dsh-plan-mode` 的 `plan` projection + `pendingIntents`）。
 *
 * - [active]：**已落库**状态（最后一条 `plan/mode`）；工具 `exit_plan_mode` 只看它；
 * - [pending]：轮次内选中的下一个步态——在下一个 step 起点落 `plan/mode`（上游 `agent/pre-step`），
 *   不在轮次中途改 prompt（否则同一轮请求会前后不一致）；
 * - [narrate]：pending 落库时是否给模型补一条 user 通知（用户在轮次内切换 = true；
 *   `exit_plan_mode` 审批通过 = false——工具结果已经叙述了这次切换）；
 * - [activeAtLastHeader]：上一次组装请求时使用的状态（上游在 `request/header` 时记
 *   `activeAtLastHeader`，用来判断要不要 narrate「用户切换了模式」）；
 * - [entered]：曾经进过计划模式（上游 `entered`，`exit_plan_mode` 的准入沿用到会话结束）。
 *
 * 线程模型：引擎协程与 Compose 主线程都会读写，字段都是 @Volatile；真正的状态机由引擎在
 * step 起点串行推进（写 pending 只在主线程）。
 */
class PlanModeState {
    @Volatile
    var active: Boolean = false

    @Volatile
    var pending: Boolean? = null

    @Volatile
    var narrate: Boolean = true

    @Volatile
    var activeAtLastHeader: Boolean? = null

    @Volatile
    var entered: Boolean = false

    /** 下一个请求要呈现的状态（上游 `selectedActive`）：pending 优先，否则已落库状态。 */
    fun selected(): Boolean = pending ?: active

    /** 打开/新建会话：从会话日志折叠出的状态（pending 一律清空，activation 不跨会话恢复）。 */
    fun reset(active: Boolean, entered: Boolean) {
        this.active = active
        this.entered = entered || active
        pending = null
        narrate = true
        activeAtLastHeader = null
    }
}
