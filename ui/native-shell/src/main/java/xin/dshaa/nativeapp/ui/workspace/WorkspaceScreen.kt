package xin.dshaa.nativeapp.ui.workspace

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.Image as ComposeImage
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import xin.dshaa.ui.design.theme.DshTheme2
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工作区文件浏览（M76）：从聊天页顶栏「工作区」进入。
 *
 * - 目录逐级进入 / 返回上级，不越出工作区根目录；
 * - 文本/代码/日志直接预览（等宽、限高滚动）；图片直接显示；
 * - 其它文件交给系统「用其他应用打开」（FileProvider 已配置 files-path）；
 * - 顶部显示相对路径。
 */
@Composable
fun WorkspaceScreen(root: File, onBack: () -> Unit) {
    val c = DshTheme2.colors
    val context = LocalContext.current
    val rootPath = remember(root) { root.absolutePath }
    var currentPath by rememberSaveable(rootPath) { mutableStateOf(rootPath) }
    var previewFile by remember { mutableStateOf<File?>(null) }
    val current = File(currentPath).let { if (it.exists() && it.isDirectory) it else File(rootPath) }

    val entries = remember(currentPath) {
        (current.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
    }
    val relativePath = current.absolutePath.removePrefix(rootPath).trimStart('/').ifBlank { "根目录" }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回聊天", tint = c.text, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text("工作区", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.text)
                Text(
                    relativePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (current.absolutePath != rootPath) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable {
                        currentPath = current.parentFile?.absolutePath ?: rootPath
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.ArrowUpward, "上级目录", tint = c.sub, modifier = Modifier.size(21.dp))
                }
            }
        }

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("这个目录是空的", style = MaterialTheme.typography.bodyMedium, color = c.faint)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(entries, key = { it.absolutePath }) { file ->
                    WorkspaceRow(
                        file = file,
                        onClick = {
                            if (file.isDirectory) {
                                currentPath = file.absolutePath
                            } else if (isPreviewable(file)) {
                                previewFile = file
                            } else {
                                openExternally(context, file)
                            }
                        },
                    )
                }
            }
        }
    }

    previewFile?.let { file ->
        FilePreviewDialog(file = file, onDismiss = { previewFile = null }) {
            openExternally(context, file)
            previewFile = null
        }
    }
}

@Composable
private fun WorkspaceRow(file: File, onClick: () -> Unit) {
    val c = DshTheme2.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                file.isDirectory -> Icons.Rounded.Folder
                isImage(file) -> Icons.Rounded.Image
                isText(file) -> Icons.Rounded.Description
                else -> Icons.Rounded.InsertDriveFile
            },
            contentDescription = null,
            tint = if (file.isDirectory) c.brand else c.sub,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyMedium,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (file.isDirectory) {
                    "${file.listFiles()?.size ?: 0} 项"
                } else {
                    "${humanSize(file.length())} · ${humanTime(file.lastModified())}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = c.faint,
            )
        }
        if (file.isDirectory) {
            Text("›", style = MaterialTheme.typography.titleMedium, color = c.faint)
        }
    }
}

@Composable
private fun FilePreviewDialog(file: File, onDismiss: () -> Unit, onOpenExternal: () -> Unit) {
    val c = DshTheme2.colors
    val isImageFile = isImage(file)
    val bitmap = remember(file) {
        if (isImageFile) runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() else null
    }
    val text by produceState<String?>(initialValue = null, file) {
        value = if (!isImageFile && isText(file)) {
            runCatching {
                file.bufferedReader().use { reader ->
                    val buffer = CharArray(200_000)
                    val read = reader.read(buffer)
                    if (read <= 0) "" else String(buffer, 0, read)
                }
            }.getOrNull()
        } else null
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(c.surface)
                .padding(14.dp),
        ) {
            Text(file.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = c.text)
            Text(
                "${humanSize(file.length())} · ${humanTime(file.lastModified())}",
                style = MaterialTheme.typography.labelSmall,
                color = c.faint,
            )
            Spacer(Modifier.height(10.dp))
            when {
                bitmap != null -> ComposeImage(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = file.name,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    contentScale = ContentScale.Fit,
                )
                text != null -> Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.surfaceAlt)
                        .padding(10.dp),
                ) {
                    Text(
                        text?.ifBlank { "(空文件)" } ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = c.text,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
                else -> Text(
                    "这个文件类型不能直接预览，可以交给系统应用打开。",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.sub,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "用其他应用打开",
                    style = MaterialTheme.typography.labelLarge,
                    color = c.brand,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpenExternal() }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
                Text(
                    "关闭",
                    style = MaterialTheme.typography.labelLarge,
                    color = c.sub,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onDismiss() }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "jsonl", "yaml", "yml", "toml", "xml", "csv", "tsv",
    "kt", "kts", "java", "js", "mjs", "cjs", "ts", "tsx", "jsx", "py", "rs", "go", "c", "h",
    "cpp", "hpp", "css", "html", "htm", "sh", "ps1", "bat", "log", "ini", "conf", "gradle",
)

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic")

private fun extensionOf(file: File): String = file.extension.lowercase()

private fun isText(file: File): Boolean = extensionOf(file) in TEXT_EXTENSIONS

private fun isImage(file: File): Boolean = extensionOf(file) in IMAGE_EXTENSIONS

private fun isPreviewable(file: File): Boolean = isText(file) || isImage(file)

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

private fun humanTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun openExternally(context: android.content.Context, file: File) {
    val mime = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(extensionOf(file))
        ?: "application/octet-stream"
    runCatching {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }.onFailure {
        Toast.makeText(context, "没有能打开这个文件的应用", Toast.LENGTH_SHORT).show()
    }
}
