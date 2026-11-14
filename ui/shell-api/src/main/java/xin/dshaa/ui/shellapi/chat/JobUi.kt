package xin.dshaa.ui.shellapi.chat

import xin.dshaa.core.jobs.JobEntry
import xin.dshaa.core.jobs.JobStatus
import xin.dshaa.core.model.SessionSummary
import java.util.Locale

/**
 * 「任务」面板的一行（M20）。
 *
 * 数据只有一路来源：`JobRegistry`（进程内任务表）。与子代理不同，后台任务**没有落盘快照**
 * ——上游 `dsh-jobs-local` 也是进程内注册表，App 重启后任务表就空了。所以面板不伪造历史，
 * 空态直接说明「任务只在当前进程内」，避免用户以为任务被悄悄删掉。
 *
 * [ownerLabel] 来自会话列表：根会话显示标题，子代理会话显示 `子代理 · 标题`（owner 就是子会话 id）。
 */
data class JobUi(
    val id: String,
    val kind: String,
    val label: String,
    val status: JobStatus,
    val detail: String?,
    val startedAt: Long,
    val finishedAt: Long?,
    val totalBytes: Long,
    val owner: String?,
    val ownerLabel: String,
    val preview: String,
    val previewTruncated: Boolean,
    val unreadBytes: Long,
) {
    val shortId: String get() = id.take(12)

    /** 运行中 + 停止中（停止中还要等生产者协程收尾，面板不能提前说「已结束」）。 */
    val running: Boolean get() = status == JobStatus.RUNNING || status == JobStatus.STOPPING

    fun durationMs(now: Long = System.currentTimeMillis()): Long =
        (finishedAt ?: now) - startedAt
}

/** 任务列表排序：运行中优先，其次按开始时间倒序（最近起的在前）。 */
fun buildJobRows(entries: List<JobEntry>, sessions: List<SessionSummary>): List<JobUi> {
    val titles = sessions.associate { it.id to it }
    return entries.map { entry ->
        val owner = entry.owner
        val session = owner?.let { titles[it] }
        val title = session?.title?.takeIf { it.isNotBlank() }
        JobUi(
            id = entry.job.id,
            kind = entry.job.kind,
            label = entry.job.label,
            status = entry.job.status,
            detail = entry.job.detail,
            startedAt = entry.job.startedAt,
            finishedAt = entry.job.finishedAt,
            totalBytes = entry.job.totalBytes,
            owner = owner,
            ownerLabel = when {
                owner == null -> "无会话"
                session?.parentSession != null -> "子代理 · ${title ?: owner.take(8)}"
                title != null -> "会话 · $title"
                else -> "会话 ${owner.take(8)}"
            },
            preview = entry.preview,
            previewTruncated = entry.previewTruncated,
            unreadBytes = entry.unreadBytes,
        )
    }.sortedWith(
        compareByDescending<JobUi> { it.status == JobStatus.RUNNING }
            .thenByDescending { it.status == JobStatus.STOPPING }
            .thenByDescending { it.startedAt },
    )
}

/** `不到 1 秒` / `N 秒` / `M 分 S 秒` / `H 小时 M 分`。 */
fun formatJobDuration(ms: Long): String {
    val value = ms.coerceAtLeast(0)
    val seconds = value / 1000
    return when {
        value < 1_000 -> "不到 1 秒"
        seconds < 60 -> "$seconds 秒"
        seconds < 3_600 -> "${seconds / 60} 分 ${seconds % 60} 秒"
        else -> "${seconds / 3_600} 小时 ${(seconds % 3_600) / 60} 分"
    }
}

/** `B` / `KB` / `MB`（保留一位小数；任务输出从几十字节到 1 MiB）。 */
fun formatJobBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0)
    return when {
        value < 1_024 -> "$value B"
        value < 1_048_576 -> String.format(Locale.US, "%.1f KB", value / 1_024.0)
        else -> String.format(Locale.US, "%.1f MB", value / 1_048_576.0)
    }
}
