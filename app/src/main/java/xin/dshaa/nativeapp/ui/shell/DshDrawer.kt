package xin.dshaa.nativeapp.ui.shell

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.sp
import xin.dshaa.core.model.SessionSummary
import xin.dshaa.nativeapp.chat.SessionBadge
import xin.dshaa.nativeapp.data.WorkspaceUi
import xin.dshaa.nativeapp.ui.Screen
import xin.dshaa.ui.design.component.DshAvatar
import xin.dshaa.ui.design.theme.DshTheme2
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * RikkaHub 式侧边栏（2026-11-07 外壳重做）。
 *
 * 结构照 RikkaHub `ChatDrawerContent`：顶部品牌/身份区 → 会话列表（按日期分组、当前会话高亮、
 * 长按改名/删除）→ 底部圆形动作条；中间多一格 Operit 的「当前工作区」切换器。
 * 会话列表只给**当前工作区**（`header.cwd` 过滤），切工作区在 [WorkspaceSwitcherSheet] 里。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DshDrawer(
    currentSessionId: String?,
    sessions: List<SessionSummary>,
    workspace: WorkspaceUi,
    workspaces: List<WorkspaceUi>,
    assistantName: String,
    assistantAvatar: String,
    model: String,
    toolCount: Int,
    badgeFor: (String?) -> SessionBadge?,
    onOpenSession: (SessionSummary) -> Unit,
    onNewChat: () -> Unit,
    onSwitchWorkspace: (String) -> Unit,
    onCreateWorkspace: (String, String?) -> Unit,
    onManageWorkspaces: () -> Unit,
    onSelectScreen: (Screen) -> Unit,
    onAbout: () -> Unit,
    onRenameSession: (String, String) -> Unit,
    onDeleteSession: (String) -> Unit,
) {
    val c = DshTheme2.colors
    var workspaceSheet by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<SessionSummary?>(null) }
    var renameFor by remember { mutableStateOf<SessionSummary?>(null) }
    var deleteFor by remember { mutableStateOf<SessionSummary?>(null) }
    val grouped = sessions.groupBy { drawerDayLabel(it.updatedAt) }

    ModalDrawerSheet(
        modifier = Modifier.width(304.dp),
        drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
        drawerContainerColor = c.surface,
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 10.dp)) {
            // ---- 品牌 + 助手 ----
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DshAvatar(avatar = assistantAvatar, name = assistantName, size = 38.dp, corner = 13.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        assistantName.ifBlank { "DSH" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "$model · $toolCount 个工具",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.faint,
                        maxLines = 1,
                    )
                }
            }

            // ---- 工作区切换器（Operit 的「工作区」+ RikkaHub 的整行卡片） ----
            Surface(
                onClick = { workspaceSheet = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = c.surfaceAlt,
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(c.brandSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Workspaces, null, tint = c.brand, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            workspace.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = c.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append("${workspace.count} 个会话")
                                if (workspaces.size > 1) append(" · 共 ${workspaces.size} 个工作区")
                                if (!workspace.exists) append(" · 目录不可用")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (workspace.exists) c.faint else c.warn,
                            maxLines = 1,
                        )
                    }
                    Icon(Icons.Rounded.ExpandMore, "切换工作区", tint = c.sub, modifier = Modifier.size(20.dp))
                }
            }

            // ---- 新对话 ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.brand)
                    .clickable { onNewChat() }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Add, null, tint = c.onHero, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("新对话", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = c.onHero)
                Spacer(Modifier.weight(1f))
                Text(
                    if (workspace.managed) "当前工作区" else "历史位置",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.onHero.copy(alpha = 0.75f),
                )
            }

            // ---- 会话列表标题 ----
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp, top = 14.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("会话", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = c.faint)
                Spacer(Modifier.width(6.dp))
                Text("${sessions.size}", style = MaterialTheme.typography.labelSmall, color = c.faint)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(30.dp).clip(CircleShape).clickable { onSelectScreen(Screen.Sessions) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.List, "全部会话", tint = c.sub, modifier = Modifier.size(18.dp))
                }
            }

            if (sessions.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Rounded.ChatBubbleOutline, null, tint = c.faint, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("这个工作区还没有会话", style = MaterialTheme.typography.bodySmall, color = c.faint)
                    Text("发一条消息就会自动建会话", style = MaterialTheme.typography.labelSmall, color = c.faint)
                }
                Spacer(Modifier.weight(1f))
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp),
                ) {
                    grouped.forEach { (day, list) ->
                        item(key = "day-$day") { DrawerDayHeader(day) }
                        items(list, key = { it.id }) { summary ->
                            DrawerSessionRow(
                                summary = summary,
                                selected = summary.id == currentSessionId,
                                badge = badgeFor(summary.id),
                                onClick = { onOpenSession(summary) },
                                onLongClick = { menuFor = summary },
                            )
                        }
                    }
                }
            }

            // ---- 底部动作条（RikkaHub 的圆形 DrawerAction） ----
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DrawerAction("设置", Icons.Rounded.Settings) { onSelectScreen(Screen.Settings) }
                DrawerAction("关于", Icons.Rounded.Info) { onAbout() }
            }
        }
    }

    if (workspaceSheet) {
        WorkspaceSwitcherSheet(
            workspaces = workspaces,
            current = workspace,
            onDismiss = { workspaceSheet = false },
            onSwitch = { path ->
                workspaceSheet = false
                onSwitchWorkspace(path)
            },
            onCreate = { name, path ->
                workspaceSheet = false
                onCreateWorkspace(name, path)
            },
            onManage = {
                workspaceSheet = false
                onManageWorkspaces()
            },
        )
    }

    // 会话长按菜单
    menuFor?.let { target ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            containerColor = c.surface,
            title = { Text(target.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    DrawerMenuRow("重命名") {
                        menuFor = null
                        renameFor = target
                    }
                    DrawerMenuRow("删除（移到回收站）", danger = true) {
                        menuFor = null
                        deleteFor = target
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
            text = { DrawerTextField(text, { text = it }) },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank()) onRenameSession(target.id, text.trim())
                    renameFor = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("取消") } },
        )
    }

    deleteFor?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            containerColor = c.surface,
            title = { Text("删除会话") },
            text = { Text("「${target.title}」会移到回收站（不真删），之后可从 .trash 找回。", color = c.sub) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSession(target.id)
                    deleteFor = null
                }) { Text("删除", color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun DrawerDayHeader(label: String) {
    val c = DshTheme2.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = c.brand)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerSessionRow(
    summary: SessionSummary,
    selected: Boolean,
    badge: SessionBadge?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = DshTheme2.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) c.brandSoft else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            summary.title.ifBlank { "新会话" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) c.brand else c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (summary.legacy) {
            Text("旧版", style = MaterialTheme.typography.labelSmall, color = c.success, modifier = Modifier.padding(horizontal = 4.dp))
        }
        SessionBadgeMark(badge)
        Spacer(Modifier.width(6.dp))
        Text(drawerTimeLabel(summary.updatedAt), style = MaterialTheme.typography.labelSmall, color = c.faint)
    }
}

@Composable
private fun DrawerAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val c = DshTheme2.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick, shape = CircleShape, color = c.brandSoft) {
            Box(Modifier.padding(11.dp).size(20.dp), contentAlignment = Alignment.Center) {
                Icon(icon, label, tint = c.brand, modifier = Modifier.size(19.dp))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.faint)
    }
}

@Composable
private fun DrawerMenuRow(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = DshTheme2.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onClick() }.padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (danger) c.danger else c.text)
    }
}

@Composable
private fun DrawerTextField(value: String, onValueChange: (String) -> Unit) {
    val c = DshTheme2.colors
    Surface(shape = RoundedCornerShape(8.dp), color = c.surfaceAlt, modifier = Modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.padding(12.dp),
            textStyle = TextStyle(color = c.text, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
            singleLine = true,
        )
    }
}

/** 工作区切换面板：当前工作区、全部工作区、新建/管理。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceSwitcherSheet(
    workspaces: List<WorkspaceUi>,
    current: WorkspaceUi,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onCreate: (String, String?) -> Unit,
    onManage: () -> Unit,
) {
    val c = DshTheme2.colors
    var creating by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("工作区", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c.text)
                Spacer(Modifier.weight(1f))
                Text("${workspaces.size} 个", style = MaterialTheme.typography.labelMedium, color = c.faint)
            }
            Text(
                "工作区 = 工具的文件根目录；会话按工作区分开，切过去只看/只写那个目录。",
                style = MaterialTheme.typography.bodySmall,
                color = c.sub,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(workspaces, key = { it.id }) { ws ->
                    Surface(
                        onClick = { if (!ws.current) onSwitch(ws.path) else onDismiss() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = if (ws.current) c.brandSoft else c.surfaceAlt,
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (ws.managed) Icons.Rounded.Folder else Icons.Rounded.Workspaces,
                                null,
                                tint = if (ws.current) c.brand else c.sub,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    ws.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (ws.current) c.brand else c.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${ws.count} 个会话 · ${ws.path}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = c.faint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (ws.current) Icon(Icons.Rounded.Check, "当前", tint = c.brand, modifier = Modifier.size(19.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetButton("新建工作区", Icons.Rounded.Add, primary = true, modifier = Modifier.weight(1f)) { creating = true }
                SheetButton("管理工作区", Icons.Rounded.Settings, primary = false, modifier = Modifier.weight(1f)) { onManage() }
            }
            if (creating) {
                xin.dshaa.nativeapp.ui.workspaces.CreateWorkspaceDialog(
                    onDismiss = { creating = false },
                    onCreate = { name, path ->
                        onCreate(name, path)
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun SheetButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = DshTheme2.colors
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        color = if (primary) c.brand else c.surfaceAlt,
    ) {
        Row(
            Modifier.padding(vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = if (primary) c.onHero else c.sub, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (primary) c.onHero else c.text,
            )
        }
    }
}

private fun drawerTimeLabel(ts: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    return if (sameYear) SimpleDateFormat("M/d", Locale.getDefault()).format(Date(ts))
    else SimpleDateFormat("yy/M/d", Locale.getDefault()).format(Date(ts))
}

/** 抽屉里的日期分组：今天 / 昨天 / 7 天内 / 更早（RikkaHub 的 DateHeader 更细，手机上 4 档够用）。 */
private fun drawerDayLabel(ts: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (sameDay(now, then)) return "今天"
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (sameDay(yesterday, then)) return "昨天"
    if (now.timeInMillis - ts <= 7L * 24 * 3600 * 1000) return "7 天内"
    return "更早"
}
