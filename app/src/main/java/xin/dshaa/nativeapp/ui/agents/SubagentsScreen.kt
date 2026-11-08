package xin.dshaa.nativeapp.ui.agents

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xin.dshaa.nativeapp.chat.SubagentUi
import xin.dshaa.ui.design.component.DshCard
import xin.dshaa.ui.design.component.MiniChip
import xin.dshaa.ui.design.component.StatusDot
import xin.dshaa.ui.design.theme.DshTheme2
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 子代理面板（M17）：进程内 running/inactive + 历史子会话文件。
 *
 * 为什么单独一页而不是塞进会话列表：子会话是主代理的内部执行单元（header.parentSession），
 * 混进用户会话列表会让人以为是自己开的对话（docs/33 §五.2）。这里按 Operit「任务管理」
 * 的形态做：顶部状态摘要 + 运行中/已结束两段 + 每张卡可打开只读回放、可停止。
 */
@Composable
fun SubagentsScreen(
    subagents: List<SubagentUi>,
    maxDepth: Int,
    loading: Boolean,
    onOpenDrawer: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (SubagentUi) -> Unit,
    onStop: (SubagentUi) -> Unit,
) {
    val c = DshTheme2.colors
    var stopFor by remember { mutableStateOf<SubagentUi?>(null) }
    val running = subagents.filter { it.running }
    val done = subagents.filterNot { it.running }

    // 运行中的子代理在写会话文件，面板要跟着动：只在有 running 时每 3 秒重合并一次
    // （状态翻转为 inactive 由 AgentRegistry.onChange 立即推，不靠轮询）。
    LaunchedEffect(running.size) {
        if (running.isEmpty()) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(3_000)
            onRefresh()
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
                Modifier.size(40.dp).clip(CircleShape).clickable { onOpenDrawer() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Menu, "菜单", tint = c.text, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text("子代理", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = c.text)
                Text(
                    "${subagents.size} 个子会话 · 运行中 ${running.size}",
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
                    Metric("${done.size}", "已结束", Modifier.weight(1f))
                    Metric("$maxDepth 层", "深度上限", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line2))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Person, null, tint = c.brand, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "子代理在独立 v4 会话里执行，结果以通知回到主代理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.sub,
                    )
                }
            }
        }

        if (subagents.isEmpty()) {
            if (loading) LoadingAgents() else EmptyAgents()
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
                    SubagentCard(row, onClick = { onOpen(row) }, onStop = { stopFor = row })
                }
            }
            if (done.isNotEmpty()) {
                item(key = "h-done") { SectionLabel("已结束", c.faint) }
                items(done, key = { "done-${it.id}" }) { row ->
                    SubagentCard(row, onClick = { onOpen(row) }, onStop = { stopFor = row })
                }
            }
        }
    }

    stopFor?.let { target ->
        AlertDialog(
            onDismissRequest = { stopFor = null },
            containerColor = c.surface,
            title = { Text("停止子代理？", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(target.label, style = MaterialTheme.typography.bodyLarge, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "它当前的轮次会被中止，会话标记为 aborted，主代理会收到一条「已停止」通知。",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.sub,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    stopFor = null
                    onStop(target)
                }) { Text("停止", color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { stopFor = null }) { Text("取消") } },
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
private fun SubagentCard(row: SubagentUi, onClick: () -> Unit, onStop: () -> Unit) {
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
                        Icons.Rounded.Person,
                        null,
                        tint = if (row.running) c.brand else c.sub,
                        modifier = Modifier.size(21.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = c.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (row.forked) {
                            Spacer(Modifier.width(6.dp))
                            MiniChip("fork", color = c.purple)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${row.shortId} · 深度 ${row.depth} · ${row.messageCount} 条消息",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                AgentBadge(row.running, row.stopReason)
            }

            // 结果预览：终态收尾话优先，其次会话最后一条正文（「看结果」不占用整页）
            val text = row.closingText?.takeIf { it.isNotBlank() } ?: row.preview
            if (text.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.sub,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(timeLabel(row.updatedAt), style = MaterialTheme.typography.labelSmall, color = c.faint)
                Spacer(Modifier.weight(1f))
                if (row.running && row.live) {
                    ActionPill("停止", c.danger, c.dangerSoft) { onStop() }
                    Spacer(Modifier.width(8.dp))
                }
                ActionPill("查看会话", c.brand, c.brandSoft) { onClick() }
            }
        }
    }
}

@Composable
private fun ActionPill(text: String, tint: Color, bg: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AgentBadge(running: Boolean, stopReason: String?) {
    val c = DshTheme2.colors
    val label: String
    val tint: Color
    val bg: Color
    when {
        running -> {
            label = "运行中"; tint = c.brand; bg = c.brandSoft
        }
        stopReason == "completed" -> {
            label = "已完成"; tint = c.success; bg = c.successSoft
        }
        stopReason == "aborted" -> {
            label = "已停止"; tint = c.warn; bg = c.warnSoft
        }
        stopReason == "error" -> {
            label = "失败"; tint = c.danger; bg = c.dangerSoft
        }
        stopReason == "max-tokens" -> {
            label = "超出长度"; tint = c.warn; bg = c.warnSoft
        }
        stopReason == "refusal" -> {
            label = "已拒绝"; tint = c.danger; bg = c.dangerSoft
        }
        else -> {
            label = "已结束"; tint = c.faint; bg = c.surfaceAlt
        }
    }
    Surface(shape = RoundedCornerShape(999.dp), color = bg) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(tint, pulse = running, size = 6.dp)
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun LoadingAgents() {
    val c = DshTheme2.colors
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = c.brand,
            strokeWidth = 2.5.dp,
        )
        Spacer(Modifier.height(14.dp))
        Text("正在读取子会话…", style = MaterialTheme.typography.bodyMedium, color = c.sub)
    }
}

@Composable
private fun EmptyAgents() {
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
            Icon(Icons.Rounded.Person, null, tint = c.brand, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("还没有子代理", style = MaterialTheme.typography.titleLarge, color = c.text, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "在对话里让主代理用 subagent 启动一个；运行中与历史子会话都会出现在这里。",
            style = MaterialTheme.typography.bodyMedium,
            color = c.sub,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ArrowForward, null, tint = c.faint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("抽屉 → 会话，先开一段对话", style = MaterialTheme.typography.labelSmall, color = c.faint)
        }
    }
}

/** 刚刚 / N 分钟前 / HH:mm（今天）/ MM-dd HH:mm。 */
private fun timeLabel(ts: Long): String {
    if (ts <= 0) return ""
    val now = System.currentTimeMillis()
    val diff = now - ts
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }
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
