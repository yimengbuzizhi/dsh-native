package xin.dshaa.nativeapp.ui.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xin.dshaa.core.model.SessionSummary
import xin.dshaa.ui.design.component.DshAvatar
import xin.dshaa.ui.design.component.DshCard
import xin.dshaa.ui.design.component.MiniChip
import xin.dshaa.ui.design.theme.DshTheme2
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 会话列表：搜索常驻、按天分组、长按重命名/删除（删除是移到 .trash，不真删）。 */
@Composable
fun SessionsScreen(
    sessions: List<SessionSummary>,
    assistantName: String,
    assistantAvatar: String,
    badgeFor: (String?) -> xin.dshaa.nativeapp.chat.SessionBadge? = { null },
    onOpenDrawer: () -> Unit,
    /** 从设置进来时顶栏改成返回箭头（M74）。 */
    onBack: (() -> Unit)? = null,
    onOpen: (SessionSummary) -> Unit,
    onNew: () -> Unit,
    onDelete: (SessionSummary) -> Unit,
    onRename: (SessionSummary, String) -> Unit,
) {
    val c = DshTheme2.colors
    var query by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<SessionSummary?>(null) }
    var renameFor by remember { mutableStateOf<SessionSummary?>(null) }
    val shown = sessions.filter {
        query.isBlank() || it.title.contains(query, true) || it.preview.contains(query, true)
    }
    val grouped = shown.groupBy { dayLabel(it.updatedAt) }

    Column(Modifier.fillMaxSize().background(c.background)) {
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
                Text("会话", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = c.text)
                Text("${sessions.size} 条 · 本地 v4", style = MaterialTheme.typography.labelMedium, color = c.faint)
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(c.brand).clickable { onNew() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Add, "新会话", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }

        Surface(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = c.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, c.line2),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = c.faint, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(color = c.text, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text("搜索会话", style = MaterialTheme.typography.bodyMedium, color = c.faint)
                        inner()
                    },
                )
            }
        }

        if (shown.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(20.dp)).background(c.brandSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.ChatBubbleOutline, null, tint = c.brand, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text("还没有会话", style = MaterialTheme.typography.titleLarge, color = c.text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("点右上角 + 开一个", style = MaterialTheme.typography.bodyMedium, color = c.sub)
            }
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 4.dp, 16.dp, 110.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                grouped.forEach { (day, list) ->
                    item(key = "header-$day") {
                        Text(
                            day,
                            style = MaterialTheme.typography.labelMedium,
                            color = c.faint,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(list, key = { it.id }) { s ->
                        SessionRow(s, assistantName, assistantAvatar, badgeFor(s.id), onClick = { onOpen(s) }, onLongClick = { menuFor = s })
                    }
                }
            }
        }
    }

    menuFor?.let { target ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            containerColor = c.surface,
            title = { Text(target.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    MenuRow("重命名") {
                        menuFor = null
                        renameFor = target
                    }
                    MenuRow("删除（移到回收站）", danger = true) {
                        menuFor = null
                        onDelete(target)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text("取消") } },
        )
    }

    renameFor?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            containerColor = c.surface,
            title = { Text("重命名会话") },
            text = {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = c.surfaceAlt.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.padding(12.dp),
                        textStyle = TextStyle(color = c.text, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank()) onRename(target, text.trim())
                    renameFor = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun MenuRow(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = DshTheme2.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (danger) c.danger else c.text)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    s: SessionSummary,
    assistantName: String,
    assistantAvatar: String,
    badge: xin.dshaa.nativeapp.chat.SessionBadge?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = DshTheme2.colors
    DshCard {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(if (s.draft) c.brandSoft else c.surfaceAlt),
                contentAlignment = Alignment.Center,
            ) {
                DshAvatar(avatar = assistantAvatar, name = assistantName.ifBlank { s.title }, size = 32.dp, corner = 11.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    s.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    s.preview.ifBlank { "还没有消息" },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            xin.dshaa.nativeapp.ui.shell.SessionBadgeMark(badge)
            if (badge != null) Spacer(Modifier.width(6.dp))
            if (s.legacy) {
                MiniChip("旧版", color = c.success)
                Spacer(Modifier.width(6.dp))
            }
            Text(timeLabel(s.updatedAt), style = MaterialTheme.typography.labelSmall, color = c.faint)
        }
    }
}

private fun timeLabel(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

/** 今天 / 昨天 / 本周 / yyyy-MM-dd。 */
private fun dayLabel(ts: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    if (sameDay(now, then)) return "今天"
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (sameDay(yesterday, then)) return "昨天"
    if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.WEEK_OF_YEAR) == then.get(Calendar.WEEK_OF_YEAR)) {
        return "本周"
    }
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))
}
