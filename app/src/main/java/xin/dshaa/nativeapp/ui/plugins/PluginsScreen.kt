package xin.dshaa.nativeapp.ui.plugins

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import xin.dshaa.core.plugins.PluginPermission
import xin.dshaa.core.plugins.PluginPermissions
import xin.dshaa.core.plugins.PluginRecord
import xin.dshaa.core.plugins.PluginSchema
import xin.dshaa.ui.design.component.DshCard
import xin.dshaa.ui.design.component.MiniChip
import xin.dshaa.ui.design.component.SectionHeader
import xin.dshaa.ui.design.theme.DshTheme2
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 插件页（M26 导入/试运行，M27 工具接入对话）：内置插件 + **本地导入**（群反馈 4）。
 *
 * 三块内容：
 * 1. 导入卡：SAF 选 zip → 后端解压/解析/QuickJS 试运行 → 清单落 `files/plugins.json`；
 * 2. 已安装列表：名称/版本/格式/注册了哪些工具/最近一次试运行的报错原文，点开看详情；
 *    已启用且参数能归一成 JSON Schema 的工具会**注册进对话工具表**（[injected] 里能看到）；
 * 3. 内置插件：只展示（还不是真正的内核插件，开关等工具注入接完再统一）。
 *
 * 边界写在页脚：插件必须声明权限；fs 限工作区、网络限域名、shell 需确认；不做插件市场（用户定稿）。
 */
@Composable
fun PluginsScreen(
    plugins: List<PluginRecord>,
    injected: Map<String, Set<String>>,
    busy: Boolean,
    notice: String?,
    workspacePath: String,
    initialDetailId: String? = null,
    onOpenDrawer: () -> Unit,
    onRefresh: () -> Unit,
    onImport: (Uri) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onReload: (String) -> Unit,
    onDelete: (String) -> Unit,
    onGrant: (String, String) -> Unit,
    onSetConfig: (String, String) -> Unit,
    onDismissNotice: () -> Unit,
) {
    val c = DshTheme2.colors
    // 详情按 id 从最新的 plugins 里取：授权/重新加载后弹层立刻翻状态，不拿旧 record。
    var detailId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<PluginRecord?>(null) }

    // 调试/截图入口（`--es dsh_plugin_detail <id>`）：插件列表就绪后自动打开详情。
    LaunchedEffect(initialDetailId, plugins) {
        val target = initialDetailId
        if (target != null && plugins.any { it.id == target }) detailId = target
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }

    LaunchedEffect(Unit) { onRefresh() }

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
                Modifier.size(40.dp).clip(CircleShape).clickable { onOpenDrawer() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Menu, "菜单", tint = c.text, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text("插件", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = c.text)
                Text(
                    "内置 3 · 本地 ${plugins.size} · 已接入 ${injected.values.sumOf { it.size }}",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.faint,
                )
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            ImportCard(busy = busy, onPick = { picker.launch(arrayOf("*/*")) })

            if (notice != null) {
                Spacer(Modifier.height(12.dp))
                NoticeBanner(notice, onDismissNotice)
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader("已安装（${plugins.size}）")
            Spacer(Modifier.height(8.dp))

            if (plugins.isEmpty()) {
                EmptyCard()
            } else {
                plugins.forEach { record ->
                    Spacer(Modifier.height(10.dp))
                    PluginCard(
                        record = record,
                        injectedTools = injected[record.id].orEmpty(),
                        onOpen = { detailId = record.id },
                        onToggle = { on -> onToggle(record.id, on) },
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            SectionHeader("内置插件")
            Spacer(Modifier.height(8.dp))
            DshCard(Modifier.fillMaxWidth()) {
                Column {
                    BuiltinRow("聊天", "会话、流式、消息渲染", Icons.Rounded.ChatBubbleOutline)
                    RowDivider()
                    BuiltinRow("会话", "v0→v4 读写、zstd、迁移", Icons.Rounded.GridView)
                    RowDivider()
                    BuiltinRow("设置与主题", "token 覆盖、主题预设", Icons.Rounded.Settings)
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Info, null, tint = c.faint, modifier = Modifier.size(15.dp))
                Text(
                    "已启用、参数是 JSON Schema（DSH 的 parameters 简写也行）的工具会接进对话工具表；" +
                        "用 schemastery（z.object）写的参数会转成 JSON Schema（tuple/transform 这类转不了的不注入）。" +
                        "插件要在清单 permissions 里声明能力：" +
                        "fs:workspace 只能读写当前工作区，net:域名 只允许 https 访问该域名和子域名，shell 要你逐项确认" +
                        "（以 App 身份执行，不是 root）。没声明就调用会被拒绝；插件包要自带 JS（手机上没有 npm）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                )
            }
            if (PluginPermissions.isBroadStorageRoot(workspacePath) &&
                plugins.any { record -> "fs:workspace" in record.permissions }
            ) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Warning, null, tint = c.warn, modifier = Modifier.size(15.dp))
                    Text(
                        "当前工作区是共享存储根（$workspacePath）：fs:workspace 等于整块手机存储。" +
                            "想要更严的边界，去抽屉把工作区切到一个单独目录再装文件类插件。",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.warn,
                    )
                }
            }
            Spacer(Modifier.height(110.dp))
        }
    }

    val detailRecord = plugins.firstOrNull { it.id == detailId }
    detailRecord?.let { record ->
        PluginDetailDialog(
            record = record,
            injectedTools = injected[record.id].orEmpty(),
            onDismiss = { detailId = null },
            onReload = {
                detailId = null
                onReload(record.id)
            },
            onDelete = {
                detailId = null
                pendingDelete = record
            },
            onGrant = { raw -> onGrant(record.id, raw) },
            onSetConfig = { text -> onSetConfig(record.id, text) },
        )
    }

    pendingDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除插件？", color = c.text) },
            text = {
                Text(
                    "会删掉 ${record.name} 的目录（files/plugins/${record.id}）。\n不影响会话、设置和内置插件。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.sub,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(record.id)
                }) { Text("删除", color = c.danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消", color = c.sub) }
            },
            containerColor = c.surface,
        )
    }
}

@Composable
private fun ImportCard(busy: Boolean, onPick: () -> Unit) {
    val c = DshTheme2.colors
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { onPick() },
        shape = RoundedCornerShape(18.dp),
        color = c.surface,
        border = BorderStroke(1.dp, if (busy) c.brand else c.line),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(c.purpleSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Add, null, tint = c.purple, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (busy) "正在解压并试运行…" else "从本地文件导入",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = c.text,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    if (busy) "QuickJS 里真的跑一次 apply(ctx)，看注册了什么" else "选一个 .zip：package.json / manifest.json + JS 入口",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.sub,
                )
            }
            Spacer(Modifier.width(10.dp))
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = c.brand,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(Icons.Rounded.KeyboardArrowRight, null, tint = c.faint, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun NoticeBanner(text: String, onDismiss: () -> Unit) {
    val c = DshTheme2.colors
    val failed = text.contains("报错") || text.contains("失败") || text.contains("删不掉")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (failed) c.warnSoft else c.successSoft,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (failed) Icons.Rounded.Warning else Icons.Rounded.CheckCircle,
                null,
                tint = if (failed) c.warn else c.success,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = c.text,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.size(28.dp).clip(CircleShape).clickable { onDismiss() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "关闭", tint = c.faint, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun EmptyCard() {
    val c = DshTheme2.colors
    DshCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(c.purpleSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Extension, null, tint = c.purple, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("还没有导入插件", style = MaterialTheme.typography.titleMedium, color = c.text, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                "把插件的 zip 放进手机，点上面的「从本地文件导入」。\n导入时会解析清单并在 QuickJS 里试运行一次。",
                style = MaterialTheme.typography.bodySmall,
                color = c.sub,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PluginCard(
    record: PluginRecord,
    injectedTools: Set<String>,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val c = DshTheme2.colors
    val error = record.error
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onOpen() },
        shape = RoundedCornerShape(18.dp),
        color = c.surface,
        border = BorderStroke(1.dp, if (error != null) c.danger.copy(alpha = 0.45f) else c.line2),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(c.brandSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Extension, null, tint = c.brand, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        record.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${record.version} · ${record.format} · 导入于 ${formatTime(record.installedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.faint,
                    )
                }
                Switch(
                    checked = record.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedTrackColor = c.brand, uncheckedTrackColor = c.line2),
                )
            }

            if (record.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    record.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.sub,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                MiniChip(record.format, color = c.brand)
                MiniChip(
                    if (record.tools.isEmpty()) "0 个工具" else "${record.tools.size} 个工具",
                    color = if (record.tools.isEmpty()) c.faint else c.success,
                )
                if (injectedTools.isNotEmpty()) {
                    MiniChip("已接入 ${injectedTools.size}", color = c.success)
                }
                if (record.permissions.isNotEmpty()) {
                    val parsed = record.permissions.mapNotNull { PluginPermissions.parse(it) }
                    val pending = parsed.count {
                        it.dangerous && it.implemented && !PluginPermissions.isGranted(it, record.grantedPermissions)
                    }
                    MiniChip("权限 ${parsed.size}", color = c.faint)
                    if (pending > 0) MiniChip("需授权 $pending", color = c.warn)
                }
                if (!record.enabled) MiniChip("已停用", color = c.faint)
                if (error != null) MiniChip("试运行报错", color = c.danger)
            }

            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Warning, null, tint = c.danger, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = c.danger,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginDetailDialog(
    record: PluginRecord,
    injectedTools: Set<String>,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onDelete: () -> Unit,
    onGrant: (String) -> Unit,
    onSetConfig: (String) -> Unit,
) {
    val c = DshTheme2.colors
    val error = record.error
    var pendingGrant by remember { mutableStateOf<PluginPermission?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = c.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = 580.dp).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(c.brandSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Extension, null, tint = c.brand, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            record.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = c.text,
                        )
                        Text(
                            "${record.version} · ${record.format} · ${if (record.enabled) "已启用" else "已停用"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.faint,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    DetailField("插件目录", record.path)
                    DetailField("入口", record.main)
                    DetailField("工具数", "${record.tools.size} 个 · 已接入对话 ${injectedTools.size} 个")
                    if (record.permissions.isNotEmpty()) {
                        Text(
                            "权限（manifest 声明）",
                            style = MaterialTheme.typography.labelMedium,
                            color = c.sub,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        record.permissions.forEach { raw ->
                            val permission = PluginPermissions.parse(raw) ?: return@forEach
                            PermissionRow(
                                permission = permission,
                                granted = PluginPermissions.isGranted(permission, record.grantedPermissions),
                                onAsk = { pendingGrant = permission },
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    if (record.configSchema != null) {
                        ConfigSection(record = record, onSetConfig = onSetConfig)
                        Spacer(Modifier.height(10.dp))
                    }
                    if (record.imports.isNotEmpty()) {
                        DetailField("import", record.imports.joinToString("\n"))
                    }
                    if (record.tools.isNotEmpty()) {
                        Text(
                            "注册的工具",
                            style = MaterialTheme.typography.labelMedium,
                            color = c.sub,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        record.tools.forEach { tool ->
                            val schema = record.toolSchemas[tool]
                            val suffix = when {
                                !record.enabled -> "（插件已停用）"
                                tool in injectedTools -> "  ✓ 已接入"
                                schema == null -> "（参数认不出，未接入）"
                                else -> "（未接入）"
                            }
                            Text(
                                "· $tool$suffix",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (tool in injectedTools) c.success else c.sub,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    if (error != null) {
                        Text(
                            "最近一次试运行报错",
                            style = MaterialTheme.typography.labelMedium,
                            color = c.danger,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Surface(shape = RoundedCornerShape(10.dp), color = c.dangerSoft) {
                            Text(
                                error,
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = c.danger,
                            )
                        }
                    }
                    if (record.tools.isEmpty() && error == null) {
                        Text(
                            "没有注册工具：这个包可能只提供 hook / UI 扩展（试运行只验证能不能跑）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.sub,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onReload) {
                        Icon(Icons.Rounded.Refresh, null, tint = c.brand, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重新加载", color = c.brand)
                    }
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, null, tint = c.danger, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("删除", color = c.danger)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭", color = c.sub) }
                }
            }
        }

        pendingGrant?.let { permission ->
            PermissionConfirmDialog(
                permission = permission,
                onDismiss = { pendingGrant = null },
                onConfirm = {
                    pendingGrant = null
                    onGrant(permission.raw)
                },
            )
        }
    }
}

/**
 * 插件配置（M30）：按 Config 的 JSON Schema 生成最简单的编辑行。
 *
 * 只做「能改」：string / number / boolean / enum 用对应控件，object / array 退化成 JSON 文本。
 * 不做 schema 校验（下一轮和工具参数校验一起做）；保存时把**合并默认值后的整份配置**写回，
 * 所以「恢复默认」= 清空用户值再按 schema 重算。
 */
@Composable
private fun ConfigSection(record: PluginRecord, onSetConfig: (String) -> Unit) {
    val c = DshTheme2.colors
    val schema = remember(record.configSchema) { PluginSchema.parse(record.configSchema) }
    val properties = remember(schema) { schema?.get("properties") as? JsonObject }
    val effective = remember(record.config, record.configSchema, record.configDefaults) {
        PluginSchema.parseObject(PluginSchema.effectiveText(record.configSchema, record.config, record.configDefaults))
    }
    var editing by remember { mutableStateOf<String?>(null) }
    if (properties == null || properties.isEmpty()) return

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "配置（Config）",
            style = MaterialTheme.typography.labelMedium,
            color = c.sub,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { onSetConfig("") }) { Text("恢复默认", color = c.brand) }
    }
    properties.forEach { (name, element) ->
        val property = element as? JsonObject ?: return@forEach
        val label = (property["description"] as? JsonPrimitive)?.contentOrNull ?: name
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { editing = name }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = c.text)
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = c.faint,
                )
            }
            Text(
                configValueText(effective[name]),
                style = MaterialTheme.typography.bodySmall,
                color = c.brand,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 170.dp).padding(start = 8.dp),
            )
            Icon(Icons.Rounded.KeyboardArrowRight, null, tint = c.faint, modifier = Modifier.size(18.dp))
        }
    }
    val configError = record.configError
    if (configError != null) {
        Text(
            "配置 schema 没认出来：$configError",
            style = MaterialTheme.typography.labelSmall,
            color = c.warn,
        )
    }

    val editingName = editing
    val editingProperty = editingName?.let { properties[it] as? JsonObject }
    if (editingName != null && editingProperty != null) {
        ConfigEditDialog(
            name = editingName,
            property = editingProperty,
            current = effective[editingName],
            onDismiss = { editing = null },
            onSave = { value ->
                val updated = LinkedHashMap<String, JsonElement>(effective)
                updated[editingName] = value
                editing = null
                onSetConfig(JsonObject(updated).toString())
            },
        )
    }
}

@Composable
private fun ConfigEditDialog(
    name: String,
    property: JsonObject,
    current: JsonElement?,
    onDismiss: () -> Unit,
    onSave: (JsonElement) -> Unit,
) {
    val c = DshTheme2.colors
    val type = (property["type"] as? JsonPrimitive)?.contentOrNull
    val enumValues = (property["enum"] as? JsonArray)?.toList()
    val title = (property["description"] as? JsonPrimitive)?.contentOrNull ?: name

    when {
        enumValues != null && enumValues.isNotEmpty() -> {
            var selected by remember { mutableStateOf(current ?: enumValues.first()) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title, color = c.text) },
                text = {
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        enumValues.forEach { value ->
                            Row(
                                Modifier.fillMaxWidth().clickable { selected = value }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected == value, onClick = { selected = value })
                                Text(
                                    (value as? JsonPrimitive)?.contentOrNull ?: value.toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.text,
                                )
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { onSave(selected) }) { Text("保存", color = c.brand) } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = c.sub) } },
                containerColor = c.surface,
            )
        }

        type == "boolean" -> {
            var checked by remember { mutableStateOf((current as? JsonPrimitive)?.booleanOrNull ?: false) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title, color = c.text) },
                text = {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (checked) "已开启" else "已关闭", color = c.sub, modifier = Modifier.weight(1f))
                        Switch(checked = checked, onCheckedChange = { checked = it })
                    }
                },
                confirmButton = { TextButton(onClick = { onSave(JsonPrimitive(checked)) }) { Text("保存", color = c.brand) } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = c.sub) } },
                containerColor = c.surface,
            )
        }

        type == "number" -> {
            var text by remember { mutableStateOf((current as? JsonPrimitive)?.contentOrNull.orEmpty()) }
            var error by remember { mutableStateOf<String?>(null) }
            val min = (property["minimum"] as? JsonPrimitive)?.doubleOrNull
            val max = (property["maximum"] as? JsonPrimitive)?.doubleOrNull
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title, color = c.text) },
                text = {
                    Column {
                        val parsed = text.toDoubleOrNull()
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it; error = null },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = error != null,
                            singleLine = true,
                        )
                        if (error != null) {
                            Text(error!!, style = MaterialTheme.typography.labelSmall, color = c.danger)
                        } else if (parsed != null) {
                            Text(
                                "保存为数字 ${if (parsed == parsed.toLong().toDouble()) parsed.toLong() else parsed}",
                                style = MaterialTheme.typography.labelSmall,
                                color = c.faint,
                            )
                        }
                        if (min != null || max != null) {
                            Text(
                                when {
                                    min != null && max != null -> "范围：${min.toDisplay()} – ${max.toDisplay()}"
                                    min != null -> "最小：${min.toDisplay()}"
                                    else -> "最大：${max!!.toDisplay()}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = c.faint,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val parsed = text.toDoubleOrNull()
                        when {
                            parsed == null -> error = "请输入数字"
                            min != null && parsed < min -> error = "不能小于 ${min.toDisplay()}"
                            max != null && parsed > max -> error = "不能大于 ${max.toDisplay()}"
                            else -> {
                                val element = text.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(parsed)
                                onSave(element)
                            }
                        }
                    }) { Text("保存", color = c.brand) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = c.sub) } },
                containerColor = c.surface,
            )
        }

        type == "object" || type == "array" -> {
            var text by remember { mutableStateOf(current?.toString().orEmpty()) }
            var error by remember { mutableStateOf<String?>(null) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("$title（JSON）", color = c.text) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it; error = null },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            isError = error != null,
                            minLines = 3,
                            maxLines = 8,
                        )
                        if (error != null) {
                            Text(error!!, style = MaterialTheme.typography.labelSmall, color = c.danger)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
                        if (parsed == null) error = "不是合法 JSON" else onSave(parsed)
                    }) { Text("保存", color = c.brand) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = c.sub) } },
                containerColor = c.surface,
            )
        }

        else -> {
            var text by remember { mutableStateOf((current as? JsonPrimitive)?.contentOrNull.orEmpty()) }
            var error by remember { mutableStateOf<String?>(null) }
            val pattern = (property["pattern"] as? JsonPrimitive)?.contentOrNull
            val minLength = (property["minLength"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            val maxLength = (property["maxLength"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            val regex = remember(pattern) { pattern?.let { runCatching { Regex(it) }.getOrNull() } }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title, color = c.text) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it; error = null },
                            singleLine = true,
                            isError = error != null,
                        )
                        if (error != null) {
                            Text(error!!, style = MaterialTheme.typography.labelSmall, color = c.danger)
                        } else {
                            if (pattern != null) {
                                Text(
                                    "格式：$pattern",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = c.faint,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            if (minLength != null || maxLength != null) {
                                Text(
                                    when {
                                        minLength != null && maxLength != null -> "长度：$minLength – $maxLength 个字符"
                                        minLength != null -> "至少 $minLength 个字符"
                                        else -> "最多 $maxLength 个字符"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = c.faint,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        when {
                            minLength != null && text.length < minLength -> error = "至少 $minLength 个字符"
                            maxLength != null && text.length > maxLength -> error = "最多 $maxLength 个字符"
                            regex != null && !regex.containsMatchIn(text) -> error = "不匹配格式：$pattern"
                            else -> onSave(JsonPrimitive(text))
                        }
                    }) { Text("保存", color = c.brand) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = c.sub) } },
                containerColor = c.surface,
            )
        }
    }
}

private fun configValueText(value: JsonElement?): String {
    if (value == null) return "（未设置）"
    return when (value) {
        is JsonPrimitive -> value.contentOrNull ?: value.toString()
        else -> value.toString().let { if (it.length > 36) it.take(36) + "…" else it }
    }
}

private fun Double.toDisplay(): String = if (this == toLong().toDouble()) toLong().toString() else toString()

/**
 * 危险权限确认：红框 + 原始权限字符串 + 一句话解释（docs/04 §七「危险能力永远不能静默获得」）。
 */
@Composable
private fun PermissionConfirmDialog(
    permission: PluginPermission,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val c = DshTheme2.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Warning, null, tint = c.danger, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("允许「${permission.title}」？", color = c.text)
            }
        },
        text = {
            Column {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = c.dangerSoft,
                    border = BorderStroke(1.5.dp, c.danger),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            "插件申请的原始权限",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.danger,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            permission.raw,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = c.danger,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(permission.explain, style = MaterialTheme.typography.bodySmall, color = c.sub)
                Spacer(Modifier.height(10.dp))
                Text(
                    "只在你信任这个插件时授权。授权会记在插件记录里；删除或重新导入插件会收回。",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("确认授权", color = c.danger) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = c.sub) }
        },
        containerColor = c.surface,
    )
}

@Composable
private fun PermissionRow(
    permission: PluginPermission,
    granted: Boolean,
    onAsk: () -> Unit,
) {
    val c = DshTheme2.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(permission.title, style = MaterialTheme.typography.bodySmall, color = c.text)
            Text(
                permission.raw,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = c.faint,
            )
            Text(
                when {
                    !permission.implemented -> "本版还没实现，授权也不会生效"
                    granted -> "已允许"
                    permission.dangerous -> "需要你确认"
                    else -> "未允许"
                },
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    granted -> c.success
                    permission.dangerous -> c.warn
                    else -> c.faint
                },
            )
        }
        if (permission.dangerous && permission.implemented && !granted) {
            TextButton(onClick = onAsk) { Text("确认启用", color = c.danger) }
        }
    }
}

@Composable
private fun DetailField(title: String, value: String) {
    val c = DshTheme2.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = c.sub, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = c.text,
        )
    }
}

@Composable
private fun BuiltinRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val c = DshTheme2.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(c.brandSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = c.brand, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.text, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.sub, maxLines = 1)
        }
        MiniChip("内置", color = c.brand)
    }
}

@Composable
private fun RowDivider() {
    val c = DshTheme2.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(c.line2))
}

private fun formatTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "未知"
    return runCatching {
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(epochMillis))
    }.getOrDefault("未知")
}
