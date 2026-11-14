package xin.dshaa.ui.shellapi.chat

import xin.dshaa.core.agents.AgentRecord
import xin.dshaa.core.agents.AgentStatus
import xin.dshaa.core.model.SessionSummary

/**
 * 「子代理」面板的一行（M17）。
 *
 * 数据有两路来源，必须合并：
 * 1. **会话仓库**：所有 `header.parentSession != null` 的子会话——App 重启后子代理运行时没了，
 *    但子会话文件还在，面板不能只剩空白；
 * 2. **`AgentRegistry`**：本次进程内启动的代理——只有它有 running/inactive 与终态（aborted/error 等），
 *    也带着用户可读的 label（description）。
 *
 * 合并规则：以子会话为骨架，进程内记录覆盖 label / 状态 / 深度 / 终态；
 * 进程内还没有对应文件（启动瞬间）的记录也要显示。
 */
data class SubagentUi(
    val id: String,
    val parentId: String?,
    val label: String,
    val running: Boolean,
    val depth: Int,
    val cwd: String,
    val preview: String,
    val messageCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
    /** 进程内还有这条代理记录（可停止；终态可见）。false = 只剩会话文件（重启后）。 */
    val live: Boolean,
    /** 终态原因（completed / aborted / max-tokens / refusal / error）；运行中或只剩文件时为 null。 */
    val stopReason: String?,
    /** 子代理最后一轮的收尾话（面板「看结果」用）。 */
    val closingText: String?,
    /** fork 出来的子代理（header.isSeeded）。 */
    val forked: Boolean,
) {
    val shortId: String get() = id.take(8)
}

/** 运行中优先，其次按最近活动倒序（面板列表顺序）。 */
fun mergeSubagents(sessions: List<SessionSummary>, agents: List<AgentRecord>): List<SubagentUi> {
    val live = agents.associateBy { it.id }
    val seen = HashSet<String>(sessions.size + agents.size)
    val rows = ArrayList<SubagentUi>(sessions.size + agents.size)
    for (s in sessions) {
        val a = live[s.id]
        seen += s.id
        rows += SubagentUi(
            id = s.id,
            parentId = a?.parentId ?: s.parentSession,
            label = a?.label?.takeIf { it.isNotBlank() } ?: s.title.ifBlank { "子代理" },
            running = a?.status == AgentStatus.RUNNING,
            depth = a?.depth ?: s.delegationDepth,
            cwd = a?.cwd ?: s.cwd,
            preview = s.preview,
            messageCount = s.messageCount,
            createdAt = a?.createdAt ?: s.createdAt,
            updatedAt = s.updatedAt,
            live = a != null,
            stopReason = a?.terminal?.stopReason ?: s.lastStopReason,
            closingText = a?.terminal?.closingTexts?.filter { it.isNotBlank() }?.joinToString("\n"),
            forked = s.isSeeded,
        )
    }
    for (a in agents) {
        if (a.id in seen) continue
        rows += SubagentUi(
            id = a.id,
            parentId = a.parentId,
            label = a.label.ifBlank { "子代理" },
            running = a.status == AgentStatus.RUNNING,
            depth = a.depth,
            cwd = a.cwd.orEmpty(),
            preview = "",
            messageCount = 0,
            createdAt = a.createdAt,
            updatedAt = a.createdAt,
            live = true,
            stopReason = a.terminal?.stopReason,
            closingText = a.terminal?.closingTexts?.filter { it.isNotBlank() }?.joinToString("\n"),
            forked = false,
        )
    }
    return rows.sortedWith(
        compareByDescending<SubagentUi> { it.running }
            .thenByDescending { it.updatedAt },
    )
}
