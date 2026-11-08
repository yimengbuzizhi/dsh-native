package xin.dshaa.nativeapp.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xin.dshaa.core.legacy.LegacyBackup
import xin.dshaa.core.legacy.LegacyBackupEntry
import xin.dshaa.core.legacy.LegacyImportOutcome
import xin.dshaa.core.legacy.LegacyScanResult
import xin.dshaa.ui.design.component.MiniChip
import xin.dshaa.ui.design.component.SettingsCard
import xin.dshaa.ui.design.component.SettingsInfoBanner
import xin.dshaa.ui.design.theme.DshTheme2
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 备份包是 zip；部分文件管理器把它报成 octet-stream，两个都给。 */
private val BACKUP_MIME_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

private sealed interface MigrationState {
    data object Idle : MigrationState
    data class Scanning(val name: String) : MigrationState
    data class Ready(val sourceName: String, val scan: LegacyScanResult) : MigrationState
    data class Importing(val total: Int, val done: Int, val imported: Int, val failed: Int) : MigrationState
    data class Done(
        val imported: Int,
        val skipped: Int,
        val failed: Int,
        val messages: Int,
        val errors: List<String>,
    ) : MigrationState
    data class Error(val message: String) : MigrationState
}

/**
 * 旧版数据导入（M6）：SAF 选 `DSH备份-*.zip` → 扫描旧会话（v0/v3/v4）→ 预览 → 只读导入。
 *
 * 三条硬约束：
 * 1. **只读**：不写回、不删除备份包里的任何东西；导入只是在本 App 的 sessions 根下生成副本。
 * 2. **可见**：扫描结果列标题/时间/消息数/工作区/版本，点开能预览前几条消息。
 * 3. **可验收**：`--es dsh_import_zip <路径>` 调试通道能跳过 SAF 直接扫同一个包。
 */
@Composable
fun DataMigrationScreen(
    sessionsRoot: File,
    onBack: () -> Unit,
    onImported: () -> Unit,
    onOpenSessions: () -> Unit,
    initialPath: String? = null,
    onInitialPathConsumed: () -> Unit = {},
) {
    val c = DshTheme2.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf<MigrationState>(MigrationState.Idle) }
    var lastScan by remember { mutableStateOf<LegacyScanResult?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourcePath by remember { mutableStateOf<String?>(null) }
    var sourceName by remember { mutableStateOf("") }
    var previewFor by remember { mutableStateOf<LegacyBackupEntry?>(null) }

    fun startScan(uri: Uri?, path: String?) {
        val name = if (uri != null) displayName(context, uri) else File(path.orEmpty()).name
        sourceName = name
        state = MigrationState.Scanning(name)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val imported = LegacyBackup.importedKeys(sessionsRoot)
                    openBackup(context, uri, path).use { input ->
                        LegacyBackup.scan(input, name, imported)
                    }
                }
            }.onSuccess { scan ->
                lastScan = scan
                state = MigrationState.Ready(name, scan)
                selected = scan.entries
                    .filter { !it.alreadyImported && it.messageCount > 0 }
                    .map { it.entryName }
                    .toSet()
            }.onFailure { err ->
                state = MigrationState.Error(err.message ?: "读取备份包失败")
            }
        }
    }

    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            sourceUri = uri
            sourcePath = null
            startScan(uri, null)
        }
    }

    val initialConsumed = remember { mutableStateOf(false) }
    LaunchedEffect(initialPath) {
        if (!initialConsumed.value && !initialPath.isNullOrBlank()) {
            initialConsumed.value = true
            sourceUri = null
            sourcePath = initialPath
            startScan(null, initialPath)
            onInitialPathConsumed()
        }
    }

    fun startImport(entries: List<LegacyBackupEntry>) {
        if (entries.isEmpty()) return
        state = MigrationState.Importing(entries.size, 0, 0, 0)
        scope.launch {
            var imported = 0
            var skipped = 0
            var failed = 0
            var messages = 0
            val importedNames = HashSet<String>()
            val errors = ArrayList<String>()
            entries.forEachIndexed { index, entry ->
                val outcome = runCatching {
                    withContext(Dispatchers.IO) {
                        LegacyBackup.import(
                            sessionsRoot = sessionsRoot,
                            openSource = { openBackup(context, sourceUri, sourcePath) },
                            entry = entry,
                            sourceName = sourceName,
                        )
                    }
                }.getOrElse { LegacyImportOutcome.Failed(it.message ?: it.javaClass.simpleName) }
                when (outcome) {
                    is LegacyImportOutcome.Imported -> {
                        imported++
                        messages += outcome.messageCount
                        importedNames += entry.entryName
                    }
                    is LegacyImportOutcome.Skipped -> {
                        skipped++
                        importedNames += entry.entryName
                    }
                    is LegacyImportOutcome.Failed -> {
                        failed++
                        errors += "${entry.title.take(24)}：${outcome.error}"
                    }
                }
                state = MigrationState.Importing(entries.size, index + 1, imported, failed)
            }
            // 导入后刷新列表标记（不重新解压备份，直接把成功的项标成「已导入」）
            lastScan = lastScan?.let { scan ->
                scan.copy(entries = scan.entries.map { if (it.entryName in importedNames) it.copy(alreadyImported = true) else it })
            }
            selected = emptySet()
            state = MigrationState.Done(imported, skipped, failed, messages, errors)
            if (imported > 0) onImported()
        }
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().background(c.surface).statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowBack, "返回", tint = c.text, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("旧版数据导入", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c.text)
                Text("只读副本 · 不改动旧备份", style = MaterialTheme.typography.labelSmall, color = c.faint)
            }
            MiniChip("只读", color = c.success)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { IntroCard() }
            item { SourceCard(sourceName, onPick = { pickZip.launch(BACKUP_MIME_TYPES) }) }

            if (state is MigrationState.Scanning) {
                item {
                    SettingsCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(
                                modifier = Modifier.weight(1f).height(4.dp),
                                color = c.brand,
                                trackColor = c.line2,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("正在解压并解析会话…", style = MaterialTheme.typography.bodySmall, color = c.sub)
                        }
                    }
                }
            }

            val doneEarly = state as? MigrationState.Done
            if (doneEarly != null) {
                item {
                    SettingsCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = c.success, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "导入完成：${doneEarly.imported} 条会话 / ${doneEarly.messages} 条消息",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = c.text,
                            )
                        }
                        Text(
                            "跳过 ${doneEarly.skipped} · 失败 ${doneEarly.failed}" +
                                if (doneEarly.skipped > 0) "（跳过 = 之前已经导入过）" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.sub,
                        )
                        doneEarly.errors.take(4).forEach { err ->
                            Text("· $err", style = MaterialTheme.typography.bodySmall, color = c.danger)
                        }
                    }
                }
            }

            val shownScan = (state as? MigrationState.Ready)?.scan ?: lastScan
            if (shownScan != null) {
                val entries = shownScan.entries
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("扫描结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.text)
                            Text(
                                summaryLine(entries),
                                style = MaterialTheme.typography.bodySmall,
                                color = c.sub,
                                maxLines = 2,
                            )
                        }
                        TextButton(onClick = {
                            selected = if (selected.isEmpty()) {
                                entries.filter { !it.alreadyImported && it.messageCount > 0 }.map { it.entryName }.toSet()
                            } else {
                                emptySet()
                            }
                        }) {
                            Text(if (selected.isEmpty()) "全选" else "全不选")
                        }
                    }
                }
                if (shownScan.warnings.isNotEmpty()) {
                    item {
                        SettingsInfoBanner(
                            shownScan.warnings.take(3).joinToString("\n"),
                            tint = c.warn,
                        )
                    }
                }
                items(entries, key = { it.entryName }) { entry ->
                    LegacyEntryRow(
                        entry = entry,
                        checked = entry.entryName in selected,
                        enabled = state is MigrationState.Ready || state is MigrationState.Scanning,
                        onCheck = { checked ->
                            selected = if (checked) selected + entry.entryName else selected - entry.entryName
                        },
                        onClick = { previewFor = entry },
                    )
                }
                if (entries.isEmpty()) {
                    item {
                        SettingsInfoBanner("备份包里没有会话日志。请确认选的是 DSH 的备份 zip（含 dshhome/sessions/）。")
                    }
                }
            }

            if (state is MigrationState.Error) {
                item { SettingsInfoBanner((state as MigrationState.Error).message, tint = c.danger) }
            }
        }

        BottomBar(
            state = state,
            selectedCount = selected.size,
            onImport = {
                val ready = state as? MigrationState.Ready ?: return@BottomBar
                startImport(ready.scan.entries.filter { it.entryName in selected })
            },
            onOpenSessions = onOpenSessions,
            onPick = { pickZip.launch(BACKUP_MIME_TYPES) },
        )
    }

    previewFor?.let { entry ->
        LegacyPreviewDialog(
            entry = entry,
            checked = entry.entryName in selected,
            onToggle = {
                selected = if (entry.entryName in selected) selected - entry.entryName else selected + entry.entryName
            },
            onDismiss = { previewFor = null },
        )
    }
}

@Composable
private fun IntroCard() {
    val c = DshTheme2.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = c.brandSoft,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(c.brand),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.FolderZip, null, tint = c.onHero, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("从旧版备份恢复对话", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.text)
                Spacer(Modifier.height(2.dp))
                Text(
                    "选择 DSH备份-*.zip，把旧会话转成只读副本放进当前 App；备份包原样保留。",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.sub,
                )
            }
        }
    }
}

@Composable
private fun SourceCard(sourceName: String, onPick: () -> Unit) {
    val c = DshTheme2.colors
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Download, null, tint = c.brand, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("备份包", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = c.text)
                Text(
                    sourceName.ifBlank { "未选择" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (sourceName.isBlank()) c.faint else c.sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = onPick,
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.onHero),
            ) {
                Text(if (sourceName.isBlank()) "选择备份包" else "重新选择", style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(
            "备份包一般在这个位置：内部存储 / Download / DSH备份-yyyyMMdd-HHmmss.zip",
            style = MaterialTheme.typography.bodySmall,
            color = c.faint,
        )
    }
}

@Composable
private fun LegacyEntryRow(
    entry: LegacyBackupEntry,
    checked: Boolean,
    enabled: Boolean,
    onCheck: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val c = DshTheme2.colors
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(14.dp),
        color = c.surface,
        border = BorderStroke(1.dp, if (checked) c.brand.copy(alpha = 0.45f) else c.line2),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = checked,
                onCheckedChange = if (enabled) onCheck else null,
                enabled = enabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = c.brand,
                    uncheckedColor = c.faint,
                    checkmarkColor = c.onHero,
                ),
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.title.ifBlank { "未命名会话" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    MiniChip("v${entry.formatVersion}")
                    if (entry.alreadyImported) {
                        Spacer(Modifier.width(4.dp))
                        MiniChip("已导入", color = c.success)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${dateLabel(entry.header.createdAt)} · ${entry.messageCount} 条消息 · ${workdirLabel(entry.header.cwd)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.preview.isNotEmpty()) {
                    Text(
                        entry.preview.first().text,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomBar(
    state: MigrationState,
    selectedCount: Int,
    onImport: () -> Unit,
    onOpenSessions: () -> Unit,
    onPick: () -> Unit,
) {
    val c = DshTheme2.colors
    val ready = state as? MigrationState.Ready
    val importing = state as? MigrationState.Importing
    val done = state as? MigrationState.Done
    if (ready == null && importing == null && done == null && state !is MigrationState.Scanning) return
    Surface(color = c.surface, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            when {
                importing != null -> {
                    LinearProgressIndicator(
                        progress = { if (importing.total == 0) 0f else importing.done.toFloat() / importing.total },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = c.brand,
                        trackColor = c.line2,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "正在导入 ${importing.done}/${importing.total} · 成功 ${importing.imported}" +
                            if (importing.failed > 0) " · 失败 ${importing.failed}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.sub,
                    )
                }
                done != null -> {
                    Button(
                        onClick = onOpenSessions,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = c.brand, contentColor = c.onHero),
                    ) { Text("去会话列表查看", fontWeight = FontWeight.SemiBold) }
                    TextButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("再导一个备份包") }
                }
                ready != null -> {
                    Button(
                        onClick = onImport,
                        enabled = selectedCount > 0,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = c.brand,
                            contentColor = c.onHero,
                            disabledContainerColor = c.line2,
                            disabledContentColor = c.faint,
                        ),
                    ) {
                        Text(
                            if (selectedCount > 0) "导入 $selectedCount 条会话（只读）" else "先选择要导入的会话",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                else -> {
                    Text(
                        "正在扫描备份包…",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.sub,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LegacyPreviewDialog(
    entry: LegacyBackupEntry,
    checked: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = DshTheme2.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = {
            Column {
                Text(entry.title.ifBlank { "未命名会话" }, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MiniChip("v${entry.formatVersion}")
                    Spacer(Modifier.width(6.dp))
                    MiniChip("${entry.messageCount} 条消息")
                    Spacer(Modifier.width(6.dp))
                    if (entry.alreadyImported) MiniChip("已导入", color = c.success)
                }
            }
        },
        text = {
            Column(Modifier.height(320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "工作区：${entry.header.cwd.ifBlank { "（未记录）" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.sub,
                )
                entry.preview.forEach { line ->
                    Surface(shape = RoundedCornerShape(10.dp), color = c.surfaceAlt.copy(alpha = 0.7f)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                if (line.role == "user") "用户" else "助手",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (line.role == "user") c.brandBright else c.sub,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(line.text, style = MaterialTheme.typography.bodySmall, color = c.text)
                        }
                    }
                }
                if (entry.preview.isEmpty()) {
                    Text("这条会话没有可预览的正文。", style = MaterialTheme.typography.bodySmall, color = c.faint)
                }
                Text(
                    "导入后是只读副本：可以看历史，不能继续对话；旧备份包不会被修改。",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onToggle(); onDismiss() }) {
                Text(if (checked) "取消选择" else "选中这条")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

// ---------------- 工具 ----------------

private fun openBackup(context: Context, uri: Uri?, path: String?): InputStream =
    if (uri != null) {
        context.contentResolver.openInputStream(uri) ?: error("无法打开备份包（权限可能已失效）")
    } else {
        FileInputStream(File(path ?: error("没有选择备份包")))
    }

private fun displayName(context: Context, uri: Uri): String {
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index) ?: uri.lastPathSegment.orEmpty()
            }
        }
    }
    return uri.lastPathSegment ?: "备份包"
}

private fun summaryLine(entries: List<LegacyBackupEntry>): String {
    if (entries.isEmpty()) return "没有找到会话"
    val byVersion = entries.groupingBy { it.formatVersion }.eachCount().entries
        .sortedBy { it.key }
        .joinToString(" / ") { "v${it.key} ${it.value}" }
    val messages = entries.sumOf { it.messageCount }
    val already = entries.count { it.alreadyImported }
    return "${entries.size} 条 · $byVersion · ${messages} 条消息" +
        if (already > 0) " · $already 条已导入" else ""
}

private fun dateLabel(ts: Long): String =
    if (ts <= 0) "时间未知" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

private fun workdirLabel(cwd: String): String {
    if (cwd.isBlank()) return "未记录工作区"
    val parts = cwd.trimEnd('/').split('/').filter { it.isNotBlank() }
    if (parts.isEmpty()) return cwd
    val tail = parts.last()
    // 末段太短（例如 /files/1）时带上父目录，列表里才有辨识度
    return if (tail.length <= 2 && parts.size >= 2) parts.takeLast(2).joinToString("/") else tail
}
