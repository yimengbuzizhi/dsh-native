package xin.dshaa.core.session

/**
 * goal 收尾指令（对齐上游 `dsh-tool-goal/lib/types/wrapup.js` 的 `renderWrapupContext`）。
 *
 * 上游语义：自动续轮里模型把目标标成 `complete` / `blocked` 后，工具不直接掐断本轮，
 * 而是 `deferContext` 注入一条 user 通知，让模型**在同一次 turn 里**对用户说一次收尾话
 * （做了什么、怎么验的、还差什么），然后本轮自然结束。
 *
 * 文案逐字节移植（契约 #1）：`Objective:` / `Blocked:` 后面是 `JSON.stringify` 的结果，
 * 与 [GoalRoundPrompt.quote] 同一套转义。单测夹具由 `tools/wrapup-oracle.mjs` 从上游源码
 * 现算生成（`core/session/src/test/resources/goal-wrapup/`）。
 */
object GoalWrapup {

    /** 上游 `CONTEXT_SUMMARY_MAX_CHARS`：通知单行摘要的长度上限。 */
    const val SUMMARY_MAX_CHARS = 120

    /** `complete` 的收尾通知正文。 */
    fun complete(objective: String): String =
        "<goal_complete>\n" +
            "Objective: ${GoalRoundPrompt.quote(objective)}\n" +
            "The goal is marked complete and this autonomous run is ending. Write the closing message " +
            "to the user now: state the outcome, summarize what was done and how it was verified, and " +
            "point to the concrete results (files, commits, or other artifacts). Report only what " +
            "earlier rounds and tool results in this session actually establish; when a detail is not " +
            "in the session, say so instead of inventing it. Note anything the user should review or do " +
            "next. Address the user directly. Do not call any more tools in this run; further work waits " +
            "for the user's next instruction.\n" +
            "</goal_complete>"

    /**
     * `blocked` 的收尾通知正文。[blockedReason] 是模型传来的原始 `blocked_reason`
     * （上游用它原文渲染，落库的 message 才 trim）。
     */
    fun blocked(objective: String, blockedReason: String): String =
        "<goal_blocked>\n" +
            "Objective: ${GoalRoundPrompt.quote(objective)}\n" +
            "Blocked: ${GoalRoundPrompt.quote(blockedReason)}\n" +
            "The goal is marked blocked and this autonomous run is ending. Write the closing message " +
            "to the user now: state what has been completed so far, describe the concrete blocking " +
            "condition and what you tried, and say exactly what you need from the user to continue. " +
            "Report only what earlier rounds and tool results in this session actually establish; when " +
            "a detail is not in the session, say so instead of inventing it. Address the user directly. " +
            "Do not call any more tools in this run; further work waits for the user's next instruction.\n" +
            "</goal_blocked>"

    /** 落库/界面用的单行摘要：`complete: <objective>`，超长按上游规则截断。 */
    fun summary(action: String, objective: String): String =
        bound("$action: $objective")

    /** 上游 `boundContextSummary`：>120 个 UTF-16 单元时取前 119 个 + `…`。 */
    fun bound(summary: String): String =
        if (summary.length <= SUMMARY_MAX_CHARS) {
            summary
        } else {
            summary.substring(0, SUMMARY_MAX_CHARS - 1) + "\u2026"
        }
}
