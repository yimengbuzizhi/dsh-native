package xin.dshaa.nativeapp.ui.jobs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xin.dshaa.core.jobs.JobStatus
import xin.dshaa.ui.shellapi.chat.JobUi
import xin.dshaa.ui.shellapi.chat.formatJobBytes
import xin.dshaa.ui.shellapi.chat.formatJobDuration
import xin.dshaa.ui.design.component.DshCard
import xin.dshaa.ui.design.component.StatusDot
import xin.dshaa.ui.design.theme.DshTheme2
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「任务」面板（M20）：后台任务（`bash run_in_background` + `job_*`）的可视化。
 *
 * 之前 `job_*` 只有模型能操作，用户看不到 App 里到底跑着什么。这里按 Operit「任务管理」
 * 的形态做：状态摘要 + 运行中/已结束两段 + 输出预览 + 停止。
 *
 * 两条产品约束：
 * 1. **只读预览**：面板的 peek 不推进模型游标，模型 `job_output` 仍拿到全量；
 * 2. **不造历史**：任务表只在进程内，重启后为空；空态如实说明。
 */
@Composable
fun JobsScreen(
    jobs: List<JobUi>,
    onOpenDrawer: () -> Unit,
    /** 从设置进来时顶栏改成返回箭头（M74）。 */
    onBack: (() -> Unit)? = null,
    onRefresh: () -> Unit,
    onStop: (String) -> Unit,
    onPeek: (String) -> JobUi?,
) {
    val c = DshTheme2.colors
    var stopFor by remember { mutableStateOf<JobUi?>(null) }
    var detailFor by remember { mutableStateOf<JobUi?>(null) }
    var detailText by remember { mutableStateOf("") }
    var detailTruncated by remember { mutableStateOf(false) }
    val running = jobs.filter { it.running }
    val done = jobs.filterNot { it.running }

    // 运行中的任务输出和耗时会变：每秒刷一次；状态翻转本身由 JobRegistry.onChange 推送。
    LaunchedEffect(running.size) {
        if (running.isEmpty()) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(1_000)
            onRefresh()
        }
    }

    // 详情弹层：打开时取 20K 大预览；任务还在跑就每秒追一次。
    LaunchedEffect(detailFor?.id, detailFor?.status) {
        val id = detailFor?.id ?: return@LaunchedEffect
        val first = onPeek(id)
        detailText = first?.preview.orEmpty()
        detailTruncated = first?.previewTruncated == true
        while (detailFor?.running == true) {
            kotlinx.coroutines.delay(1_000)
            val next = onPeek(id) ?: break
            detailText = next.preview
            detailTruncated = next.previewTruncated
            detailFor = next
        }
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        // ---------- 顶栏 ----------
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.surface)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable { if (onBack != null) onBack() else onOpenDrawer() },
                contentAlignment = Alignment.Center,
            ) {
                if (onBack != null) {
                    Icon(Icons.Rounded.ArrowBack, "返回设置", tint = c.text, modifier = Modifier.size(23.dp))
                } else {
                    Icon(Icons.Rounded.Menu, "菜单", tint = c.text, modifier = Modifier.size(23.dp))
                }
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text("任务", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = c.text)
                Text(
                    "${jobs.size} 个任务 · 运行中 ${running.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.faint,
                )
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable { onRefresh() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Refresh, "刷新", tint = c.sub, modifier = Modifier.size(21.dp))
            }
        }

        // ---------- 状态摘要卡 ----------
        DshCard(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row {
                    Metric("${running.size}", "运行中", Modifier.weight(1f), accent = running.isNotEmpty())
                    Metric("${jobs.size}", "任务总数", Modifier.weight(1f))
                    Metric(formatJobBytes(jobs.sumOf { it.totalBytes }), "累计输出", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line2))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Terminal, null, tint = c.brand, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "任务在 App 进程内执行；预览只读，不会吃掉模型未读的输出。",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.sub,
                    )
                }
            }
        }

        if (jobs.isEmpty()) {
            EmptyJobs()
            return@Column
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (running.isNotEmpty()) {
                item(key = "h-running") { SectionLabel("运行中", c.brand) }
                items(running, key = { "run-${it.id}" }) { row ->
                    JobCard(
                        row = row,
                        onClick = { detailFor = row },
                        onStop = { stopFor = row },
                    )
                }
            }
            if (done.isNotEmpty()) {
                item(key = "h-done") { SectionLabel("已结束", c.faint) }
                items(done, key = { "done-${it.id}" }) { row ->
                    JobCard(
                        row = row,
                        onClick = { detailFor = row },
                        onStop = { stopFor = row },
                    )
                }
            }
        }
    }

    stopFor?.let { target ->
        AlertDialog(
            onDismissRequest = { stopFor = null },
            containerColor = c.surface,
            title = { Text("停止任务？", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        target.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = c.text,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "进程会被取消，任务标记为 killed。这次停止由你发起，不会再把「完成通知」投给模型；" +
                            "模型下次 job_list 仍能看到终态。",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.sub,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    stopFor = null
                    onStop(target.id)
                }) { Text("停止", color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { stopFor = null }) { Text("取消") } },
        )
    }

    detailFor?.let { target ->
        var dialogRow by remember(target.id) { mutableStateOf(target) }
        LaunchedEffect(target.id, target.status) {
            // 弹层里的状态也跟着任务表刷新（停止/完成时徽标立即变）
            dialogRow = onPeek(target.id) ?: dialogRow
        }
        AlertDialog(
            onDismissRequest = { detailFor = null },
            containerColor = c.surface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("任务输出", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    JobBadge(dialogRow.status)
                }
            },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        dialogRow.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${dialogRow.kind} · ${dialogRow.shortId} · ${dialogRow.ownerLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF111827)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 360.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                        ) {
                            SelectionContainer {
                                Text(
                                    if (detailText.isBlank()) "（还没有输出）" else detailText,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                    ),
                                    color = Color(0xFFE5E7EB),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        buildString {
                            append("${formatJobDuration(dialogRow.durationMs())} · 输出 ${formatJobBytes(dialogRow.totalBytes)}")
                            if (dialogRow.unreadBytes > 0) append(" · 模型未读 ${formatJobBytes(dialogRow.unreadBytes)}")
                            if (detailTruncated) append(" · 只显示末尾 20,000 字符")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = c.faint,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "只读预览：不会改变模型 job_output 已读到哪里。",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.faint,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { detailFor = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String, dot: Color) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(dot, pulse = dot == DshTheme2.colors.brand, size = 6.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = DshTheme2.colors.faint)
    }
}

@Composable
private fun JobCard(row: JobUi, onClick: () -> Unit, onStop: () -> Unit) {
    val c = DshTheme2.colors
    DshCard(onClick = onClick) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (row.running) c.brandSoft else c.surfaceAlt),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Terminal,
                        null,
                        tint = if (row.running) c.brand else c.sub,
                        modifier = Modifier.size(21.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        row.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${row.kind} · ${row.shortId} · ${row.ownerLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                JobBadge(row.status)
            }

            // 输出预览：Operit 的「控制台」是深色，这里给任务输出一块 mini 终端
            if (row.preview.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF111827)) {
                    Text(
                        row.preview,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        ),
                        color = Color(0xFFD1D5DB),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            } else if (row.running) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "等待输出…",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.faint,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(startedLabel(row), style = MaterialTheme.typography.labelSmall, color = c.faint)
                Spacer(Modifier.width(8.dp))
                Text(
                    formatJobDuration(row.durationMs()),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                if (row.running) {
                    ActionPill(
                        text = if (row.status == JobStatus.STOPPING) "停止中…" else "停止",
                        tint = c.danger,
                        bg = c.dangerSoft,
                        enabled = row.status != JobStatus.STOPPING,
                        onClick = onStop,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                ActionPill("查看输出", c.brand, c.brandSoft) { onClick() }
            }
        }
    }
}

@Composable
private fun JobBadge(status: JobStatus) {
    val c = DshTheme2.colors
    val label: String
    val tint: Color
    val bg: Color
    when (status) {
        JobStatus.RUNNING -> {
            label = "运行中"; tint = c.brand; bg = c.brandSoft
        }
        JobStatus.STOPPING -> {
            label = "停止中"; tint = c.warn; bg = c.warnSoft
        }
        JobStatus.COMPLETED -> {
            label = "已完成"; tint = c.success; bg = c.successSoft
        }
        JobStatus.KILLED -> {
            label = "已停止"; tint = c.warn; bg = c.warnSoft
        }
        JobStatus.FAILED -> {
            label = "失败"; tint = c.danger; bg = c.dangerSoft
        }
    }
    Surface(shape = RoundedCornerShape(999.dp), color = bg) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(tint, pulse = status == JobStatus.RUNNING, size = 6.dp)
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ActionPill(
    text: String,
    tint: Color,
    bg: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg.copy(alpha = if (enabled) 1f else 0.45f))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EmptyJobs() {
    val c = DshTheme2.colors
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(20.dp)).background(c.brandSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Terminal, null, tint = c.brand, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("还没有后台任务", style = MaterialTheme.typography.titleLarge, color = c.text, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "在对话里让模型用 bash 的 run_in_background 起一个；运行中的任务、输出和停止都在这里。",
            style = MaterialTheme.typography.bodyMedium,
            color = c.sub,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ArrowForward, null, tint = c.faint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("任务表只在当前进程内 · 重启后清空", style = MaterialTheme.typography.labelSmall, color = c.faint)
        }
    }
}

/** `05:12 开始` / `今天 05:12 开始`（跨天补日期）。 */
private fun startedLabel(row: JobUi): String {
    if (row.startedAt <= 0) return ""
    val fmt = if (isSameDay(row.startedAt, System.currentTimeMillis())) "HH:mm:ss" else "MM-dd HH:mm"
    return "${SimpleDateFormat(fmt, Locale.getDefault()).format(Date(row.startedAt))} 开始"
}

private fun isSameDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
        ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}

@Composable
private fun Metric(value: String, label: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    val c = DshTheme2.colors
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = if (accent) c.brand else c.text,
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.sub)
    }
}
