package xin.dshaa.nativeapp.ui.workspaces

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xin.dshaa.nativeapp.device.DevicePermissions
import xin.dshaa.ui.design.theme.DshTheme2
import java.io.File

/**
 * 新建工作区（2026-11-07 重做）。
 *
 * 旧版只能建在 `files/workspaces/` 下（应用私有目录），用户想拿 `/sdcard/Download/xxx` 当工作区
 * 完全没有入口。这版给两个来源：
 * - 应用目录：最稳，不需要任何权限；
 * - 手机存储/自定义：目录选择器直接挑（需要「所有文件访问」，没授权时给一个去授权的按钮）。
 */
@Composable
fun CreateWorkspaceDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, path: String?) -> String?,
) {
    val c = DshTheme2.colors
    var name by remember { mutableStateOf("") }
    var customPath by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("新建工作区") },
        text = {
            Column {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    singleLine = true,
                    textStyle = TextStyle(color = c.text, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.surfaceAlt, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        if (name.isEmpty()) Text("工作区名字（可留空）", color = c.faint, style = MaterialTheme.typography.bodyMedium)
                        inner()
                    },
                )
                Spacer(Modifier.height(12.dp))

                Text("位置", style = MaterialTheme.typography.labelMedium, color = c.faint)
                Spacer(Modifier.height(6.dp))
                LocationRow(
                    title = "应用私有目录",
                    subtitle = "files/workspaces/…（不需要权限，最稳）",
                    selected = customPath == null,
                    onClick = { customPath = null; error = null },
                )
                Spacer(Modifier.height(6.dp))
                LocationRow(
                    title = "手机存储 / 自定义目录",
                    subtitle = customPath ?: "点「浏览」挑一个文件夹（可以新建）",
                    selected = customPath != null,
                    onClick = {
                        if (DevicePermissions.allFilesGranted(context)) {
                            picking = true
                        } else {
                            DevicePermissions.openAllFilesSettings(context)
                            error = "先给「所有文件访问」权限，回来再挑目录"
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    error ?: "创建后立即切过去；已有会话仍按原来的 cwd 归属。",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (error != null) c.danger else c.faint,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val result = onCreate(name.trim(), customPath)
                if (result != null) error = result else onDismiss()
            }) { Text("创建") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    if (DevicePermissions.allFilesGranted(context)) picking = true
                    else {
                        DevicePermissions.openAllFilesSettings(context)
                        error = "先给「所有文件访问」权限"
                    }
                }) { Text("浏览") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )

    if (picking) {
        DirectoryPickerDialog(
            initial = customPath?.let { File(it) } ?: defaultRoot(),
            onDismiss = { picking = false },
            onPick = { dir ->
                customPath = dir.absolutePath
                if (name.isBlank()) name = friendlyDirName(dir)
                picking = false
                error = null
            },
        )
    }
}

/** 选完目录后的默认名字：内置存储根叫「手机存储」，其余取末级目录名。 */
private fun friendlyDirName(dir: File): String = when (dir.absolutePath) {
    "/storage/emulated/0" -> "手机存储"
    else -> dir.name.ifBlank { "工作区" }
}

private fun defaultRoot(): File {
    val external = Environment.getExternalStorageDirectory()
    return if (external != null && external.isDirectory && external.canRead()) external
    else File("/storage/emulated/0")
}

@Composable
private fun LocationRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val c = DshTheme2.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) c.brandSoft else c.surfaceAlt, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) Icons.Rounded.FolderOpen else Icons.Rounded.Folder,
            null,
            tint = if (selected) c.brand else c.sub,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = if (selected) c.brand else c.text)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = c.faint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 目录选择器（自己实现，不走 SAF）。
 *
 * 为什么不用 `ACTION_OPEN_DOCUMENT_TREE`：SAF 给的是 `content://` URI，
 * 而工具链（read/write/bash/@file 索引）要的是真实路径，转换在 Android 11+ 不可靠。
 * 我们已经在权限页提供「所有文件访问」，直接 File 遍历最干净。
 */
@Composable
fun DirectoryPickerDialog(
    initial: File,
    onDismiss: () -> Unit,
    onPick: (File) -> Unit,
) {
    val c = DshTheme2.colors
    var current by remember { mutableStateOf(initial) }
    var newFolder by remember { mutableStateOf<String?>(null) }
    val children = remember(current) {
        (current.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
            .take(300)
    }
    val parent = current.parentFile

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("选择目录") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(32.dp).background(c.surfaceAlt, RoundedCornerShape(9.dp)).clickable {
                            parent?.let { current = it }
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.ArrowUpward, "上一级", tint = if (parent != null) c.sub else c.faint, modifier = Modifier.size(17.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        current.absolutePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = c.sub,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier.size(32.dp).background(c.surfaceAlt, RoundedCornerShape(9.dp)).clickable { newFolder = "" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.CreateNewFolder, "新建文件夹", tint = c.sub, modifier = Modifier.size(17.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.heightIn(min = 120.dp, max = 320.dp)) {
                    if (children.isEmpty()) {
                        item {
                            Text(
                                "这个目录下没有子目录",
                                style = MaterialTheme.typography.labelSmall,
                                color = c.faint,
                                modifier = Modifier.padding(vertical = 16.dp, horizontal = 4.dp),
                            )
                        }
                    }
                    items(children, key = { it.absolutePath }) { dir ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { current = dir }
                                .padding(vertical = 9.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Folder, null, tint = c.brand, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(dir.name, style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1)
                        }
                    }
                }
                Text(
                    if (current.canWrite()) "可写" else "不可写（换一个目录）",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (current.canWrite()) c.success else c.danger,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(current) }) { Text("用这个目录") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    newFolder?.let { value ->
        AlertDialog(
            onDismissRequest = { newFolder = null },
            containerColor = c.surface,
            title = { Text("新建文件夹") },
            text = {
                BasicTextField(
                    value = value,
                    onValueChange = { newFolder = it },
                    singleLine = true,
                    textStyle = TextStyle(color = c.text, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.surfaceAlt, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        if (value.isEmpty()) Text("文件夹名", color = c.faint, style = MaterialTheme.typography.bodyMedium)
                        inner()
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = value.trim()
                    if (name.isNotEmpty()) {
                        val dir = File(current, name)
                        if (dir.mkdirs() || dir.isDirectory) current = dir
                    }
                    newFolder = null
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { newFolder = null }) { Text("取消") } },
        )
    }
}

/** 工作区行尾的路径缩写：/storage/emulated/0/a/b → 手机存储/a/b（列表里不撑爆一行）。 */
fun shortWorkspacePath(path: String): String = when {
    path.startsWith("/storage/emulated/0/") -> "手机存储/" + path.removePrefix("/storage/emulated/0/")
    path == "/storage/emulated/0" -> "手机存储"
    path.contains("/files/workspace") -> path.substringAfterLast("/files/").let { "应用目录/$it" }
    else -> path
}
