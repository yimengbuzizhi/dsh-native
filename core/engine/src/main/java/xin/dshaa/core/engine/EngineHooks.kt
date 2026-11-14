package xin.dshaa.core.engine

import xin.dshaa.core.model.ToolCall
import xin.dshaa.core.tools.ToolResult

/**
 * M84-C2：回合引擎的**观察/扩展钩子**（`docs/115 §C` 的「TurnEngine → 可替换服务」）。
 *
 * 设计约束：
 * - 只观察，不重写循环：钩子在引擎的关键点上被调用，**不返回任何值**，不改变事件顺序、
 *   文案与落库时机（`TurnEngineTest` 仍是逐字节回归基线）；
 * - 全部默认 no-op，`EngineDeps` 不传就与加钩子之前完全一样；
 * - 钩子抛异常只记一条 `engine-hook failed` 日志，不能弄断一轮（[TurnEngine.fire] 包了 runCatching）；
 * - 同步回调：钩子拿不到 suspend 能力，想发事件/写盘请自己起协程（App 的 debug 钩子只写 logcat）。
 *
 * 对应 `docs/115 §C` 要求的五类点：step 前后、工具执行前后、停止、错误与重试。
 */

/** 一个 step 的坐标（与落库的 `turn/start` / `step/start` 编号一致）。 */
data class EngineStepPoint(val turn: Int, val step: Int)

/** 一次模型请求失败（`LlmEvent.Failed`）。[code] / [status] 与 SSE 失败事件同源。 */
data class EngineFailure(val message: String, val code: String?, val status: Int?)

/**
 * 一次**重试**：[kind] 取 `context-overflow` / `image-offload` / `summary-image-offload`，
 * [attempt] 从 1 开始，[max] 是这一类重试的预算上限。
 */
data class EngineRetry(val kind: String, val attempt: Int, val max: Int)

/**
 * 一轮被取消。[reason] = `user`（用户按了停止）或 `cancelled`（切会话/关页面等内部取消）；
 * [userRequested] 与 `EngineDeps.stop.requested` 同一事实。
 */
data class EngineStop(val reason: String, val userRequested: Boolean, val turn: Int, val step: Int)

/**
 * 引擎钩子集合。所有回调都是同步、可选、默认空实现。
 */
class EngineHooks(
    /** step 打开后（注入消息落完、请求组装前）。 */
    val onStepStart: (EngineStepPoint) -> Unit = {},
    /** step 收口前（工具结果都落库、`step/end` 之前；失败/取消路径也会调）。 */
    val onStepEnd: (EngineStepPoint) -> Unit = {},
    /** 工具真正执行前（审批已通过）。 */
    val onToolStart: (ToolCall) -> Unit = {},
    /** 工具执行完，结果（含 isError）拿到后。 */
    val onToolEnd: (ToolCall, ToolResult) -> Unit = { _, _ -> },
    /** 一轮被取消。 */
    val onStop: (EngineStop) -> Unit = {},
    /** 模型请求失败（每次尝试一次；重试的成功/失败另有 onRetry）。 */
    val onError: (EngineFailure) -> Unit = {},
    /** 引擎决定重试（会话面压缩 / 图片卸载 / 摘要图片卸载）。 */
    val onRetry: (EngineRetry) -> Unit = {},
)
