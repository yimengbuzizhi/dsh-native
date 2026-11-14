package xin.dshaa.nativeapp.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xin.dshaa.core.backup.BackupManifest
import xin.dshaa.core.backup.BackupSessionEntry
import xin.dshaa.nativeapp.data.BackupManager
import xin.dshaa.ui.design.component.MiniChip
import xin.dshaa.ui.design.component.SettingsCard
import xin.dshaa.ui.design.component.SettingsInfoBanner
import xin.dshaa.ui.design.theme.DshTheme2
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BACKUP_MIME_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

private sealed interface BackupUi {
    data object Idle : BackupUi
    data class Busy(val text: String) : BackupUi
    data class Exported(val record: BackupManager.ExportRecord) : BackupUi
    data class ReadyRestore(val file: File, val manifest: BackupManifest) : BackupUi
    data class Restored(val result: BackupManager.RestoreResult) : BackupUi
    data class Error(val message: String) : BackupUi
}

/**
 * 备份与恢复（M35，docs/59）。
 *
 * 和「旧版数据导入」的区别：这里导出的是**本 App 自己的 v4 会话原文件**，恢复后可以直接续聊；
 * 旧版导入进来到的只读会话会连 imported.json 一起备份/恢复，仍然只读。
 */
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    onRestored: () -> Unit,
    currentWorkspace: String,
    /** 导出清单里记录的 app 版本（宿主传入；UI 模块不依赖 app BuildConfig）。 */
    appVersion: String = "",
) {
    val c = DshTheme2.colors
    val context = LocalContext.current
    val manager = remember { BackupManager(context.applicationContext, appVersion) }
    val scope = rememberCoroutineScope()

    var ui by remember { mutableStateOf<BackupUi>(BackupUi.Idle) }
    var includeAttachments by remember { mutableStateOf(true) }
    var lastExport by remember { mutableStateOf(manager.lastExport()) }
    var statsVersion by remember { mutableIntStateOf(0) }
    val stats by produceState<BackupManager.Stats?>(null, statsVersion) {
        value = withContext(Dispatchers.IO) { runCatching { manager.stats() }.getOrNull() }
    }

    fun startExport(uri: Uri) {
        ui = BackupUi.Busy("正在写备份…")
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        manager.exportTo(out, includeAttachments)
                    } ?: error("打不开目标文件（SAF 权限可能已失效）")
                }
            }
            ui = result.fold(
                onSuccess = { record ->
                    lastExport = manager.lastExport()
                    BackupUi.Exported(record)
                },
                onFailure = { BackupUi.Error(it.message ?: "导出失败") },
            )
            statsVersion++
        }
    }

    fun startInspect(uri: Uri) {
        ui = BackupUi.Busy("正在读取备份…")
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val file = File(context.cacheDir, "restore-${System.currentTimeMillis()}.zip")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("打不开备份文件")
                    file to manager.inspect(file)
                }
            }
            ui = result.fold(
                onSuccess = { (file, manifest) -> BackupUi.ReadyRestore(file, manifest) },
                onFailure = { BackupUi.Error(it.message ?: "这不是可恢复的备份") },
            )
        }
    }

    fun startRestore(file: File, onlySessionIds: Set<String>? = null) {
        ui = BackupUi.Busy("正在恢复（会话 / 插件 / 附件 / 设置）…")
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    manager.restore(file, mapMissingCwdTo = currentWorkspace, onlySessionIds = onlySessionIds)
                }
            }
            ui = result.fold(
                onSuccess = {
                    statsVersion++
                    onRestored()
                    BackupUi.Restored(it)
                },
                onFailure = { BackupUi.Error(it.message ?: "恢复失败") },
            )
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) startExport(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startInspect(uri)
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        Row(
            Modifier.fillMaxWidth().background(c.surface).statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowBack, "返回", tint = c.text, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("备份与恢复", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c.text)
                Text("本机数据 · 原生 v4 会话", style = MaterialTheme.typography.labelSmall, color = c.faint)
            }
            MiniChip("不含密钥", color = c.success)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SettingsInfoBanner(
                    "备份包含会话、插件、图片附件和设置；不包含 API Key / 账号令牌。" +
                        "恢复只补不删：同 id 会话、已装插件、已存在附件都会跳过。",
                )
            }
            item {
                SettingsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FolderZip, null, tint = c.brand, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("本机数据", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = c.text)
                        Spacer(Modifier.weight(1f))
                        MiniChip("v4 原文件")
                    }
                    Spacer(Modifier.height(8.dp))
                    val current = stats
                    if (current == null) {
                        Text("正在统计…", style = MaterialTheme.typography.bodySmall, color = c.faint)
                    } else {
                        Text(
                            "${current.sessions} 条会话 · ${current.messages} 条消息 · ${formatBytes(current.sessionBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.text,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${current.plugins} 个插件 · 图片附件 ${formatBytes(current.attachmentBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.sub,
                        )
                    }
                }
            }
            item {
                SettingsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Restore, null, tint = c.faint, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "上次备份",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = c.text,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    val last = lastExport
                    if (last == null) {
                        Text(
                            "还没有导出过备份。本机数据只在这台设备上。",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.faint,
                        )
                    } else {
                        Text(
                            "${formatTimestamp(last.at)} · ${formatBytes(last.zipBytes)} · ${last.sessions} 条会话" +
                                if (last.plugins > 0) " · ${last.plugins} 个插件" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.sub,
                        )
                        Text(
                            "恢复后会话可直接续聊；设置要重启 App 生效。",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.faint,
                        )
                    }
                }
            }
            item {
                SettingsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = includeAttachments, onCheckedChange = { includeAttachments = it })
                        Column(Modifier.weight(1f)) {
                            Text("包含图片附件", style = MaterialTheme.typography.bodyMedium, color = c.text)
                            Text(
                                "关闭后备份更小，但恢复后聊天里的图片会缺失",
                                style = MaterialTheme.typography.labelSmall,
                                color = c.faint,
                            )
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                        exportLauncher.launch("DSH-native-backup-$stamp.zip")
                    },
                    enabled = ui !is BackupUi.Busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.onHero),
                ) {
                    Icon(Icons.Rounded.FolderZip, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("导出备份（.zip）", fontWeight = FontWeight.SemiBold)
                }
            }
            item {
                Button(
                    onClick = { importLauncher.launch(BACKUP_MIME_TYPES) },
                    enabled = ui !is BackupUi.Busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = c.surfaceAlt,
                        contentColor = c.text,
                    ),
                ) {
                    Icon(Icons.Rounded.Restore, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("从备份恢复", fontWeight = FontWeight.SemiBold)
                }
            }

            when (val state = ui) {
                is BackupUi.Busy -> item {
                    SettingsCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = c.brand, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(state.text, style = MaterialTheme.typography.bodySmall, color = c.sub)
                        }
                    }
                }
                is BackupUi.Exported -> item {
                    val m = state.record.manifest
                    SettingsCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = c.success, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("备份导出完成", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = c.text)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${m.sessions.size} 条会话 · ${m.plugins.size} 个插件 · ${m.attachmentsFiles} 个附件 · 原始 ${formatBytes(m.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.sub,
                        )
                        Text(
                            "备份包 ${formatBytes(state.record.zipBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.faint,
                        )
                        if (m.warnings.isNotEmpty()) {
                            Text(m.warnings.take(3).joinToString("；"), style = MaterialTheme.typography.labelSmall, color = c.danger)
                        }
                    }
                }
                is BackupUi.Restored -> item {
                    val r = state.result
                    SettingsCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = c.success, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("恢复完成", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = c.text)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "会话 ${r.sessions.restored}（跳过 ${r.sessions.skipped}" +
                                (if (r.sessions.deselected > 0) "，未选 ${r.sessions.deselected}" else "") + "）· " +
                                "插件 +${r.pluginsAdded.size} · 附件 ${r.attachments.restored} · 设置 ${r.settingsApplied} 项",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.sub,
                        )
                        if (r.cwdRemapped > 0) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${r.cwdRemapped} 条会话的工作区已映射到 ${currentWorkspace}（原路径在本机不可用）",
                                style = MaterialTheme.typography.labelSmall,
                                color = c.sub,
                            )
                        }
                        if (r.settingsApplied > 0) {
                            Text(
                                "设置要重启 App 后生效（当前界面仍读旧值）。",
                                style = MaterialTheme.typography.labelSmall,
                                color = c.faint,
                            )
                            Spacer(Modifier.height(6.dp))
                            Button(
                                onClick = { context.findActivity()?.recreate() },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.onHero),
                            ) { Text("立即重启生效", fontWeight = FontWeight.SemiBold) }
                        }
                        val errors = r.errors
                        if (errors.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            errors.take(4).forEach {
                                Text("· $it", style = MaterialTheme.typography.labelSmall, color = c.danger)
                            }
                        }
                    }
                }
                is BackupUi.Error -> item {
                    SettingsCard {
                        Text("出错了", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = c.danger)
                        Text(state.message, style = MaterialTheme.typography.bodySmall, color = c.sub)
                    }
                }
                is BackupUi.ReadyRestore -> Unit
                BackupUi.Idle -> Unit
            }

            item {
                SettingsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, tint = c.faint, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "备份包是本机 zip；恢复会话可以直接继续聊，旧版只读会话仍只读。",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.faint,
                        )
                    }
                }
            }
        }
    }

    val ready = ui as? BackupUi.ReadyRestore
    if (ready != null) {
        val m = ready.manifest
        // M39：按工作区分组，恢复前可以让用户只挑一部分（默认全选）。
        val groups = remember(ready.file.absolutePath) { groupSessionsByWorkspace(m.sessions) }
        var selectedWorkspaces by remember(ready.file.absolutePath) {
            mutableStateOf(groups.map { it.slug }.toSet())
        }
        val selectedSessions = groups.filter { it.slug in selectedWorkspaces }.flatMap { it.sessions }
        val allSelected = selectedWorkspaces.size == groups.size
        val missingCwd = selectedSessions.count { !it.legacy && it.cwd.isNotBlank() && !isWritableDir(it.cwd) }
        AlertDialog(
            onDismissRequest = { ui = BackupUi.Idle },
            containerColor = c.surface,
            title = { Text("恢复这份备份？", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${m.sessions.size} 条会话（旧版只读 ${m.sessions.count { it.legacy }} 条）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.text,
                    )
                    Text(
                        "${m.plugins.size} 个插件 · ${m.attachmentsFiles} 个附件 · ${m.settingsKeys} 项设置",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.text,
                    )
                    if (groups.size > 1) {
                        Text(
                            "按工作区选择（已选 ${selectedSessions.size} / ${m.sessions.size} 条）",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.sub,
                        )
                        Column(
                            Modifier.heightIn(max = 210.dp).verticalScroll(rememberScrollState()),
                        ) {
                            groups.forEach { group ->
                                val checked = group.slug in selectedWorkspaces
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            selectedWorkspaces = if (checked) {
                                                selectedWorkspaces - group.slug
                                            } else {
                                                selectedWorkspaces + group.slug
                                            }
                                        }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = {
                                            selectedWorkspaces = if (checked) {
                                                selectedWorkspaces - group.slug
                                            } else {
                                                selectedWorkspaces + group.slug
                                            }
                                        },
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            group.label,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = c.text,
                                            maxLines = 1,
                                        )
                                        Text(
                                            "${group.sessions.size} 条 · ${formatBytes(group.bytes)}" +
                                                if (group.legacy > 0) " · 旧版只读 ${group.legacy}" else "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = c.faint,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        "恢复不会删除现有数据：同 id 会话和已装插件跳过；设置会按备份值覆盖。",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.sub,
                    )
                    if (missingCwd > 0) {
                        Text(
                            "其中 $missingCwd 条会话的原工作区在本机不存在或不可写，会映射到当前工作区：$currentWorkspace",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.danger,
                        )
                    }
                    Text(
                        "备份来自 ${m.appVersion.ifBlank { "未知版本" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.faint,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = groups.isEmpty() || selectedSessions.isNotEmpty(),
                    onClick = {
                        val only = if (allSelected) {
                            null
                        } else {
                            selectedSessions.map { it.id }.toSet()
                        }
                        ui = BackupUi.Busy("正在恢复…")
                        startRestore(ready.file, only)
                    },
                ) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { ui = BackupUi.Idle }) { Text("取消") } },
        )
    }
}

/** 恢复确认里的一行：一个工作区 + 它下面的会话（M39）。 */
private data class RestoreWorkspace(
    val slug: String,
    val label: String,
    val sessions: List<BackupSessionEntry>,
) {
    val legacy: Int get() = sessions.count { it.legacy }
    val bytes: Long get() = sessions.sumOf { it.bytes }
}

private fun groupSessionsByWorkspace(sessions: List<BackupSessionEntry>): List<RestoreWorkspace> =
    sessions.groupBy { it.workspace }
        .map { (slug, list) -> RestoreWorkspace(slug, workspaceLabel(slug, list), list) }
        .sortedWith(compareBy({ it.label }, { it.slug }))

/** 有 cwd 就用原路径；老备份没有 cwd 时把 slug 还原成大致路径。 */
private fun workspaceLabel(slug: String, sessions: List<BackupSessionEntry>): String {
    val cwd = sessions.mapNotNull { it.cwd.takeIf { path -> path.isNotBlank() } }.distinct()
    if (cwd.size == 1) return cwd.first()
    val body = slug.removePrefix("--").removeSuffix("--")
    if (body.isBlank() || body == "default") return "默认工作区"
    return "/" + body.replace('-', '/')
}

private fun formatTimestamp(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun isWritableDir(path: String): Boolean {
    val dir = File(path)
    return dir.isDirectory && dir.canWrite()
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
