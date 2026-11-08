package xin.dshaa.nativeapp.ui.settings

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.content.Context
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import xin.dshaa.nativeapp.data.WorkspaceUi
import xin.dshaa.nativeapp.ui.workspaces.CreateWorkspaceDialog
import xin.dshaa.nativeapp.ui.workspaces.shortWorkspacePath
import xin.dshaa.ui.design.component.MiniChip
import xin.dshaa.ui.design.component.SettingsInfoBanner
import xin.dshaa.ui.design.theme.DshTheme2
import java.io.File

/**
 * 工作区管理（2026-11-07，RikkaHub 外壳）：切换 / 新建 / 重命名 / 删除。
 *
 * 语义与上游 v4 一致：工作区就是会话的 `cwd`，也是工具的可写根；会话按工作区分开。
 * 「历史位置」是从旧版导入会话的 cwd 推导出来的，只读浏览，不能在这里改名/删除。
 */
@Composable
fun WorkspacesScreen(
    workspaces: List<WorkspaceUi>,
    currentPath: String,
    onBack: () -> Unit,
    onSwitch: (String) -> Unit,
    onCreate: (String, String?) -> String?,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> String?,
) {
    val c = DshTheme2.colors
    val context = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<WorkspaceUi?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceUi?>(null) }
    val current = workspaces.firstOrNull { it.current }

    Column(Modifier.fillMaxSize().background(c.background)) {
        Row(
            Modifier.fillMaxWidth().background(c.surface).statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowBack, "返回", tint = c.text, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text("工作区", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c.text)
                Text(
                    "${workspaces.size} 个 · 当前：${current?.name ?: "默认工作区"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(c.brand).clickable { creating = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Add, "新建工作区", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SettingsInfoBanner(
                    "工作区 = 会话的 cwd，也是工具的可写根目录。切换工作区后：抽屉只显示该工作区的会话，" +
                        "新会话建在该目录，技能根与 @file 索引跟着换；正在生成的对话会被停止。",
                )
            }
            workspaces.groupBy { it.managed }.forEach { (managed, list) ->
                item(key = "header-$managed") {
                    Text(
                        if (managed) "本地工作区" else "历史位置（来自旧版/其他目录的会话）",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.faint,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                    )
                }
                items(list, key = { it.id }) { ws ->
                    WorkspaceRow(
                        workspace = ws,
                        onClick = { if (!ws.current) onSwitch(ws.path) },
                        onOpen = if (ws.exists) ({ openWorkspaceDirectory(context, ws.path) }) else null,
                        onRename = if (ws.managed && ws.id != "default") ({ renameTarget = ws }) else null,
                        onDelete = if (ws.managed && ws.id != "default") ({ deleteTarget = ws }) else null,
                    )
                }
            }
            item {
                Text(
                    "删除工作区会把它的目录和会话一起移到回收站（files/workspaces/.trash、files/sessions/.trash），" +
                        "不会真删；默认工作区不可删除。数据目录：${shortWorkspacePath(currentPath)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                    modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                )
            }
        }
    }

    if (creating) {
        CreateWorkspaceDialog(
            onDismiss = { creating = false },
            onCreate = { name, path -> onCreate(name, path) },
        )
    }

    renameTarget?.let { target ->
        var name by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = c.surface,
            title = { Text("重命名工作区") },
            text = { WorkspaceNameField(name, { name = it }) },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) onRename(target.id, name.trim())
                    renameTarget = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = c.surface,
            title = { Text("删除工作区") },
            text = {
                Text(
                    "「${target.name}」下的 ${target.count} 个会话和目录会移到回收站（不真删）。",
                    color = c.sub,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target.id)
                    deleteTarget = null
                }) { Text("删除", color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun WorkspaceRow(
    workspace: WorkspaceUi,
    onClick: () -> Unit,
    onOpen: (() -> Unit)?,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val c = DshTheme2.colors
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (workspace.current) c.brandSoft else c.surface,
        border = if (workspace.current) null else androidx.compose.foundation.BorderStroke(1.dp, c.line2),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(if (workspace.current) c.brand else c.surfaceAlt),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (workspace.managed) Icons.Rounded.Folder else Icons.Rounded.Workspaces,
                    null,
                    tint = if (workspace.current) c.onHero else c.sub,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        workspace.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (workspace.current) c.brand else c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (workspace.current) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Rounded.Check, "当前工作区", tint = c.brand, modifier = Modifier.size(17.dp))
                    }
                }
                Text(
                    shortWorkspacePath(workspace.path),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${workspace.count} 个会话", style = MaterialTheme.typography.labelSmall, color = c.sub)
                    Spacer(Modifier.width(6.dp))
                    if (!workspace.exists) {
                        MiniChip("目录不可用", color = c.warn)
                    } else if (!workspace.managed) {
                        MiniChip("历史", color = c.purple)
                    }
                }
            }
            if (onRename != null || onDelete != null || onOpen != null) {
                Box {
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).clickable { menu = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.MoreVert, "更多", tint = c.sub, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (onOpen != null) {
                            DropdownMenuItem(
                                text = { Text("打开目录", color = c.text) },
                                leadingIcon = { Icon(Icons.Rounded.FolderOpen, null, tint = c.brand, modifier = Modifier.size(19.dp)) },
                                onClick = {
                                    menu = false
                                    onOpen()
                                },
                            )
                        }
                        if (onRename != null) {
                            DropdownMenuItem(
                                text = { Text("重命名", color = c.text) },
                                onClick = {
                                    menu = false
                                    onRename()
                                },
                            )
                        }
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = { Text("删除（移到回收站）", color = c.danger) },
                                onClick = {
                                    menu = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 工作区行尾的「打开目录」：优先交给系统文件管理器（DocumentsUI），应用私有目录它打不开。 */
private fun openWorkspaceDirectory(context: Context, path: String) {
    val dir = File(path)
    if (!dir.isDirectory) {
        Toast.makeText(context, "目录不存在或不可读", Toast.LENGTH_SHORT).show()
        return
    }
    val canonical = runCatching { dir.canonicalPath }.getOrDefault(path)
    val external = runCatching { Environment.getExternalStorageDirectory()?.absolutePath }.getOrNull()
    if (external.isNullOrBlank() || (canonical != external && !canonical.startsWith("$external/"))) {
        Toast.makeText(context, "系统文件管理器打不开应用私有目录", Toast.LENGTH_SHORT).show()
        return
    }
    val relative = canonical.removePrefix(external).trimStart('/')
    val documentId = if (relative.isEmpty()) "primary:" else "primary:$relative"
    val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", documentId)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "没有文件管理器能打开这个目录", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun WorkspaceNameField(value: String, onValueChange: (String) -> Unit) {
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
