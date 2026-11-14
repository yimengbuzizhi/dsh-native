package xin.dshaa.nativeapp.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import xin.dshaa.ui.design.theme.DshTheme2

/** 文件夹树节点（照搬 Operit FolderNode：name/fullPath/children/展开）。 */
data class FolderNode(
    val name: String,
    val fullPath: String,
    val children: MutableList<FolderNode> = mutableListOf(),
)

/** 扁平路径 → 树（照搬 Operit `buildFolderTree`）。 */
fun buildFolderTree(paths: List<String>): FolderNode {
    val root = FolderNode("", "", mutableListOf())
    paths.map { normalizeFolderPath(it) }.filterNotNull().distinct().sorted().forEach { fullPath ->
        val parts = fullPath.split('/').filter { it.isNotBlank() }
        var parent = root
        var walking = ""
        parts.forEach { part ->
            walking = if (walking.isEmpty()) part else "$walking/$part"
            val existing = parent.children.firstOrNull { it.name == part }
            parent = existing ?: FolderNode(part, walking).also { parent.children.add(it) }
        }
    }
    return root
}

/**
 * 左侧文件夹导航（照搬 Operit `FolderNavigator` 的交互）：
 * 全部记忆 / 未分类 / 树；长按文件夹重命名或删除；顶部「新建」。
 * Operit 的记忆空间（profile）选择器本版未搬（鲸歌还没有多记忆空间数据层），
 * 这里不摆空的下拉框。
 */
@Composable
fun FolderNavigator(
    folderPaths: List<String>,
    selectedFolderPath: String,
    onFolderSelected: (String) -> Unit,
    onFolderRename: (String, String) -> Unit,
    onFolderDelete: (String) -> Unit,
    onFolderCreate: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val c = DshTheme2.colors
    val root = remember(folderPaths) { buildFolderTree(folderPaths) }
    // key = fullPath；Operit 用 rememberLocal 持久化，这里页面内 remember 即可
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var contextMenuFor by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier.fillMaxHeight().width(268.dp),
        color = c.surface,
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Folder, null, tint = c.brand, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "文件夹",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = c.text,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismissRequest) {
                    Icon(Icons.Rounded.ChevronLeft, "收起侧栏", tint = c.sub)
                }
            }
            HorizontalDivider(color = c.line)

            LazyColumn(Modifier.weight(1f)) {
                item {
                    FolderRow(
                        label = "全部记忆",
                        icon = Icons.Rounded.SelectAll,
                        selected = selectedFolderPath.isEmpty(),
                        depth = 0,
                        onClick = { onFolderSelected("") },
                    )
                }
                item {
                    FolderRow(
                        label = UNCATEGORIZED,
                        icon = Icons.Rounded.Inbox,
                        selected = selectedFolderPath == UNCATEGORIZED,
                        depth = 0,
                        onClick = { onFolderSelected(UNCATEGORIZED) },
                    )
                }
                renderFolderTree(
                    nodes = root.children,
                    depth = 0,
                    selectedFolderPath = selectedFolderPath,
                    expanded = expanded,
                    onSelect = onFolderSelected,
                    onLongPress = { contextMenuFor = it },
                )
            }

            HorizontalDivider(color = c.line)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { showCreate = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.CreateNewFolder, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("新建文件夹")
                }
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.FolderOpen, "刷新", tint = c.sub)
                }
            }
        }
    }

    contextMenuFor?.let { path ->
        DropdownMenu(expanded = true, onDismissRequest = { contextMenuFor = null }) {
            DropdownMenuItem(
                text = { Text("重命名") },
                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                onClick = {
                    contextMenuFor = null
                    renameTarget = path
                },
            )
            DropdownMenuItem(
                text = { Text("删除文件夹") },
                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = c.danger) },
                onClick = {
                    contextMenuFor = null
                    deleteTarget = path
                },
            )
        }
    }

    if (showCreate) {
        FolderNameDialog(
            title = "新建文件夹",
            initial = "",
            hint = "例如：工作/项目A",
            confirmLabel = "创建",
            onDismiss = { showCreate = false },
            onConfirm = {
                showCreate = false
                onFolderCreate(it)
            },
        )
    }

    renameTarget?.let { path ->
        FolderNameDialog(
            title = "重命名文件夹",
            initial = path,
            hint = "新路径",
            confirmLabel = "保存",
            onDismiss = { renameTarget = null },
            onConfirm = { newPath ->
                renameTarget = null
                onFolderRename(path, newPath)
            },
        )
    }

    deleteTarget?.let { path ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除文件夹") },
            text = { Text("删除「$path」？该文件夹里的直接记忆会移到「$UNCATEGORIZED」，子文件夹保留。") },
            confirmButton = {
                Button(
                    onClick = {
                        onFolderDelete(path)
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("删除") }
            },
            dismissButton = { OutlinedButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.renderFolderTree(
    nodes: List<FolderNode>,
    depth: Int,
    selectedFolderPath: String,
    expanded: MutableMap<String, Boolean>,
    onSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    nodes.forEach { node ->
        val isExpanded = expanded[node.fullPath] ?: (depth < 1)
        item(key = node.fullPath) {
            FolderRow(
                label = node.name,
                icon = if (isExpanded) Icons.Rounded.FolderOpen else Icons.Rounded.Folder,
                selected = selectedFolderPath == node.fullPath,
                depth = depth + 1,
                hasChildren = node.children.isNotEmpty(),
                expanded = isExpanded,
                onToggle = { expanded[node.fullPath] = !isExpanded },
                onClick = { onSelect(node.fullPath) },
                onLongPress = { onLongPress(node.fullPath) },
            )
        }
        if (isExpanded) {
            renderFolderTree(node.children, depth + 1, selectedFolderPath, expanded, onSelect, onLongPress)
        }
    }
}

@Composable
private fun FolderRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    depth: Int,
    hasChildren: Boolean = false,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    val c = DshTheme2.colors
    val rowModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 2.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(if (selected) c.brandSoft else Color.Transparent)
        .then(
            if (onLongPress != null) {
                Modifier.combinedClickableCompat(onClick = onClick, onLongClick = onLongPress)
            } else {
                Modifier.clickable(onClick = onClick)
            },
        )
        .padding(start = (8 + depth * 14).dp, end = 8.dp, top = 9.dp, bottom = 9.dp)
    Row(
        rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasChildren && onToggle != null) {
            Box(Modifier.size(22.dp).clip(CircleShape).clickable { onToggle() }, contentAlignment = Alignment.Center) {
                Icon(
                    if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                    if (expanded) "折叠" else "展开",
                    tint = c.sub,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(2.dp))
        }
        Icon(icon, null, tint = if (selected) c.brand else c.sub, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) c.brand else c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** combinedClickable 需要 ExperimentalFoundationApi，单独包一层，避免整文件加注解。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

@Composable
private fun FolderNameDialog(
    title: String,
    initial: String,
    hint: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("文件夹路径") },
                    placeholder = { Text(hint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "用 / 分隔层级，例如「工作/项目A」。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } },
    )
}
