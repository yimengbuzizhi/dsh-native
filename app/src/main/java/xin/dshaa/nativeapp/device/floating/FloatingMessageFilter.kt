package xin.dshaa.nativeapp.device.floating

import xin.dshaa.core.model.ChatMessage

/**
 * 宿主注入给模型的内部 user 消息（主界面有专门的小行样式：`GoalRoundNotice` /
 * `JobNoticeRow` / `TimeContextRow` …）。
 *
 * 悬浮窗没有这些样式，直接按 user 气泡渲染就会把整段内部提示词当成用户发言——真机
 * 2026-11-09 在 WINDOW 模式看到一大段「Time sampled while preparing turn 1…」。
 * 悬浮窗只显示真人用户、助手和工具结果；内部消息仍可在主界面回看。
 */
private val INTERNAL_SOURCE_KINDS = setOf(
    "goal",
    "tool-goal",
    "tool-jobs",
    "subagent-settled",
    "agent-message",
    "plan-mode",
    "skill-catalog",
    "skill-invocation",
    "time-context",
    "compact-checkpoint",
)

internal fun ChatMessage.isInternalNotice(): Boolean =
    sourceKind != null && sourceKind in INTERNAL_SOURCE_KINDS
