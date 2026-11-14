package xin.dshaa.nativeapp.ui.plugins

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.material.icons.rounded.ArrowBack
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
import xin.dshaa.core.plugins.PluginCapabilities
import xin.dshaa.core.plugins.PluginCapability
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

/** 插件管理：导入、内置和角色分别浏览；搜索包含停用项。 */
@Composable
fun PluginsScreen(
    plugins: List<PluginRecord>,
    injected: Map<String, Set<String>>,
    busy: Boolean,
    notice: String?,
    workspacePath: String,
    initialDetailId: String? = null,
    onOpenDrawer: () -> Unit,
    /** 从设置进来时顶栏改成返回箭头（M74）。 */
    onBack: (() -> Unit)? = null,
    onRefresh: () -> Unit,
    onImport: (Uri) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onReload: (String) -> Unit,
    onDelete: (String) -> Unit,
    onGrant: (String, String) -> Unit,
    onSetConfig: (String, String) -> Unit,
    onDismissNotice: () -> Unit,
    /** M70：打开插件自己的 `dsh.client` 界面（通用宿主，不再是按插件写死的原生窗口）。 */
    onOpenPluginWindow: (PluginRecord) -> Unit = {},
    /** M84-G：以**整页**打开插件自己的 dsh.client（插件详情里的「以整页打开」）。 */
    onOpenPluginPage: (PluginRecord, String) -> Unit = { _, _ -> },
    /** M83 阶段 2：内置插件（工具/页面）与开关。 */
    builtins: List<xin.dshaa.ui.shellapi.data.BuiltinPluginUi> = emptyList(),
    onToggleBuiltin: (String, Boolean) -> Unit = { _, _ -> },
    /** M85-D：回合插件（agent/v1）选择。 */
    agentTargets: List<xin.dshaa.ui.shellapi.AgentTargetUi> = emptyList(),
    activeAgent: xin.dshaa.ui.shellapi.AgentTargetUi? = null,
    agentSafetyNotice: String? = null,
    onSelectAgent: (String) -> Unit = {},
    onRestoreBuiltinAgent: () -> Unit = {},
    /** M85-E：存储 / 提示词 / 模型插件（storage/v1、prompt/v1、model/v1）选择。 */
    storageTargets: List<xin.dshaa.ui.shellapi.RoleTargetUi> = emptyList(),
    activeStorage: xin.dshaa.ui.shellapi.RoleTargetUi? = null,
    storageSafetyNotice: String? = null,
    onSelectStorage: (String) -> Unit = {},
    onRestoreBuiltinStorage: () -> Unit = {},
    promptTargets: List<xin.dshaa.ui.shellapi.RoleTargetUi> = emptyList(),
    activePrompt: xin.dshaa.ui.shellapi.RoleTargetUi? = null,
    promptSafetyNotice: String? = null,
    onSelectPrompt: (String) -> Unit = {},
    onRestoreBuiltinPrompt: () -> Unit = {},
    modelTargets: List<xin.dshaa.ui.shellapi.RoleTargetUi> = emptyList(),
    activeModel: xin.dshaa.ui.shellapi.RoleTargetUi? = null,
    onSelectModel: (String) -> Unit = {},
    onRestoreBuiltinModel: () -> Unit = {},
    /** M85-F：能力插件（capability/v1）——内置平台能力 + `ctx.capability.register` 的提供方。 */
    loadingTargets: List<xin.dshaa.ui.shellapi.RoleTargetUi> = emptyList(),
    loadingSafetyNotice: String? = null,
    onSelectLoading: (String) -> Unit = {},
    onRestoreBuiltinLoading: () -> Unit = {},
    shellTargets: List<xin.dshaa.ui.shellapi.RoleTargetUi> = emptyList(),
    onSelectShell: (String) -> Unit = {},
    onRestoreBuiltinShell: () -> Unit = {},
    capabilityTargets: List<xin.dshaa.ui.shellapi.CapabilityUi> = emptyList(),
    capabilityNotice: String? = null,
    onInvokeCapability: (String) -> Unit = {},
) {
    val c = DshTheme2.colors
    // 详情按 id 从最新的 plugins 里取：授权/重新加载后弹层立刻翻状态，不拿旧 record。
    var detailId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<PluginRecord?>(null) }
    var showCapabilities by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var builtinDetailId by remember { mutableStateOf<String?>(null) }
    fun matches(vararg fields: String): Boolean = query.isBlank() || fields.any { it.contains(query.trim(), true) }
    val builtinRecords = plugins.filter { it.id == "builtin.shell.whale" }
    val imported = plugins.filterNot { it.id == "builtin.shell.whale" }
    val usedPlugins = buildSet {
        shellTargets.filter { it.active }.forEach { add(it.pluginId ?: it.implementationId) }
        agentTargets.filter { it.active }.forEach { add(it.pluginId ?: it.agentId) }
        (storageTargets + promptTargets + modelTargets).filter { it.active }.forEach {
            add(it.pluginId ?: if (it.role == "model") "builtin.model.${it.implementationId}" else it.implementationId)
        }
    }
    fun recordMatches(record: PluginRecord): Boolean = matches(record.name, record.id, record.description,
        record.tools.joinToString(" "), record.shells.joinToString { "界面 壳 shell ${it.id} ${it.label}" },
        record.agents.joinToString { "回合 agent ${it.id} ${it.label}" },
        record.storages.joinToString { "存储 storage ${it.id} ${it.label}" },
        record.prompts.joinToString { "提示词 prompt ${it.id} ${it.label}" },
        record.llmProviders.joinToString { "模型 model ${it.id} ${it.label}" },
        record.capabilities.joinToString { "能力 capability ${it.id} ${it.label}" })

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
                Text("插件", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = c.text)
                Text(
                    "已安装 ${builtins.size + plugins.size} · 已启用 ${builtins.count { it.enabled } + plugins.count { it.enabled }}",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.faint,
                )
            }
            TextButton(onClick = { showCapabilities = true }) { Text("帮助", color = c.sub) }
        }

        TabRow(selectedTabIndex = tab, containerColor = c.surface, contentColor = c.brand) {
            listOf("导入", "内置", "角色").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("搜索名称、ID、描述或角色") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清空") } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            shape = RoundedCornerShape(14.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (notice != null) item { NoticeBanner(notice, onDismissNotice) }
            when (tab) {
                0 -> {
                    item { ImportCard(busy = busy, onPick = { picker.launch(arrayOf("*/*")) }) }
                    val visible = imported.filter { recordMatches(it) }
                    if (visible.isEmpty()) item {
                        if (imported.isEmpty()) EmptyCard() else Text("没有匹配的插件", color = c.faint)
                    }
                    items(visible, key = { it.id }) { record ->
                        PluginCard(record, injected[record.id].orEmpty(),
                            onOpen = { detailId = record.id }, onToggle = { onToggle(record.id, it) },
                            active = record.id in usedPlugins)
                    }
                    if (PluginPermissions.isBroadStorageRoot(workspacePath) &&
                        plugins.any { "fs:workspace" in it.permissions }) item {
                        Text("当前工作区是共享存储根（$workspacePath）：fs:workspace 可访问整块共享存储。", color = c.warn,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                1 -> {
                    val visible = builtins.filter { matches(it.plugin.name, it.plugin.id, it.plugin.description,
                        it.plugin.kind.name, builtinKindLabel(it.plugin.kind), it.plugin.tools.joinToString(" ")) }
                    items(visible, key = { it.plugin.id }) { item ->
                        DshCard(Modifier.fillMaxWidth()) {
                            BuiltinSwitchRow(item, onToggleBuiltin, onOpen = { builtinDetailId = item.plugin.id },
                                active = item.plugin.id in usedPlugins)
                        }
                    }
                    items(builtinRecords.filter { recordMatches(it) }, key = { it.id }) { record ->
                        PluginCard(record, injected[record.id].orEmpty(), onOpen = { detailId = record.id },
                            onToggle = { onToggle(record.id, it) }, active = record.id in usedPlugins)
                    }
                    if (visible.isEmpty() && builtinRecords.none { recordMatches(it) }) item { Text("没有匹配的插件", color = c.faint) }
                }
                2 -> {
                    fun roleMatches(role: String, label: String, id: String, provider: String) = matches(role, label, id, provider)
                    item {
                        Text("启用后才可选择；停用当前提供方时会显示安全提示。", color = c.faint, style = MaterialTheme.typography.labelSmall)
                    }
                    val shells = shellTargets.filter { roleMatches("界面 壳 shell", it.label, it.implementationId, it.provider + " " + it.pluginId.orEmpty()) }
                    if (shells.isNotEmpty() || query.isBlank()) item {
                        RoleTargetsSection("界面壳（${shells.size}）", "", shells, null,
                            shellTargets.firstOrNull { it.active }, "恢复原生界面", onSelectShell, onRestoreBuiltinShell)
                    }
                    val loadings = loadingTargets.filter { roleMatches("启动 加载页 loading", it.label, it.implementationId, it.provider + " " + it.pluginId.orEmpty()) }
                    if (loadings.isNotEmpty() || query.isBlank() || loadingSafetyNotice != null) item {
                        RoleTargetsSection("启动加载页（${loadings.size}）", "选择后下次启动生效", loadings,
                            loadingSafetyNotice, loadingTargets.firstOrNull { it.active }, "恢复默认加载页",
                            onSelectLoading, onRestoreBuiltinLoading)
                    }
                    val agents = agentTargets.filter { roleMatches("回合 agent", it.label, it.agentId, it.provider + " " + it.pluginId.orEmpty()) }
                    if (agents.isNotEmpty() || query.isBlank() || agentSafetyNotice != null) item {
                        SectionHeader("回合（${agents.size}）")
                        agentSafetyNotice?.let { Text(it, color = c.danger, style = MaterialTheme.typography.labelSmall) }
                        DshCard(Modifier.fillMaxWidth()) { Column {
                            agents.forEach { target -> AgentSelectRow(target) { onSelectAgent(target.selectionValue()) }; RowDivider() }
                        } }
                        if (activeAgent?.builtin != true) TextButton(onClick = onRestoreBuiltinAgent) { Text("恢复内置回合") }
                    }
                    val roles = listOf(
                        Triple("存储 storage", storageTargets, storageSafetyNotice),
                        Triple("提示词 prompt", promptTargets, promptSafetyNotice),
                        Triple("模型 model", modelTargets, null),
                    )
                    roles.forEachIndexed { index, (role, targets, safety) ->
                        val filtered = targets.filter { roleMatches(role, it.label, it.implementationId, it.provider + " " + it.pluginId.orEmpty()) }
                        if (filtered.isNotEmpty() || query.isBlank() || safety != null) item {
                            RoleTargetsSection(
                                title = role.substringBefore(' ') + "（${filtered.size}）", hint = "", targets = filtered, safetyNotice = safety,
                                active = listOf(activeStorage, activePrompt, activeModel)[index],
                                restoreLabel = listOf("恢复内置存储", "恢复内置提示词", "恢复跟随协议")[index],
                                onSelect = listOf(onSelectStorage, onSelectPrompt, onSelectModel)[index],
                                onRestore = listOf(onRestoreBuiltinStorage, onRestoreBuiltinPrompt, onRestoreBuiltinModel)[index],
                            )
                        }
                    }
                    val caps = capabilityTargets.filter { matches("能力 capability", it.id, it.label, it.provider) }
                    if (caps.isNotEmpty() || query.isBlank()) item { SectionHeader("能力（${caps.size}）") }
                    if (capabilityNotice != null) item { Text(capabilityNotice, color = c.sub, style = MaterialTheme.typography.labelSmall) }
                    items(caps, key = { "cap:" + it.id }) { target ->
                        DshCard(Modifier.fillMaxWidth()) { CapabilityRow(target) { onInvokeCapability(target.id) } }
                    }
                }
            }
        }
    }

    builtins.firstOrNull { it.plugin.id == builtinDetailId }?.let { item ->
        AlertDialog(onDismissRequest = { builtinDetailId = null },
            title = { Text(item.plugin.name) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                DetailField("ID", item.plugin.id)
                DetailField("来源与状态", "内置 · ${if (item.enabled) "已启用" else "已停用"}" + if (item.plugin.id in usedPlugins) " · 使用中" else "")
                DetailField("角色", builtinKindLabel(item.plugin.kind))
                DetailField("说明", item.plugin.description)
                if (item.plugin.tools.isNotEmpty()) DetailField("工具", item.plugin.tools.joinToString("、"))
                if (item.plugin.screens.isNotEmpty()) DetailField("页面", item.plugin.screens.joinToString("、"))
            } }, confirmButton = { TextButton(onClick = { builtinDetailId = null }) { Text("关闭") } }, containerColor = c.surface)
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
            onOpenWindow = { onOpenPluginWindow(record) },
            onOpenPage = { routeId -> onOpenPluginPage(record, routeId) },
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

    if (showCapabilities) {
        CapabilityDialog(onDismiss = { showCapabilities = false })
    }
}

@Composable
private fun CapabilityDialog(onDismiss: () -> Unit) {
    val c = DshTheme2.colors
    val done = PluginCapabilities.all.count { it.implemented }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("插件 API 参考", color = c.text) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "宿主能力目录（${PluginCapabilities.VERSION}）：$done / ${PluginCapabilities.all.size} 个原语已实现。" +
                        "插件按这里的 JS 写法调用；标「未实现」的会返回 unsupported，插件页会提示。",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.sub,
                )
                PluginCapabilities.all.forEach { cap ->
                    Spacer(Modifier.height(10.dp))
                    CapabilityRow(cap)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

@Composable
private fun CapabilityRow(cap: PluginCapability) {
    val c = DshTheme2.colors
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                cap.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = c.text,
                modifier = Modifier.weight(1f),
            )
            MiniChip(if (cap.implemented) "已实现" else "未实现", color = if (cap.implemented) c.success else c.faint)
            cap.permission?.let { MiniChip("权限 $it", color = c.warn) }
        }
        Text(
            "${cap.id} · ${cap.api}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = c.faint,
        )
        if (cap.note.isNotBlank()) {
            Text(cap.note, style = MaterialTheme.typography.labelSmall, color = c.sub)
        }
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
                    if (busy) "正在检查插件内容" else "选择 .zip 插件包",
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
    active: Boolean = false,
) {
    val c = DshTheme2.colors
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onOpen() },
        shape = RoundedCornerShape(16.dp), color = c.surface,
        border = BorderStroke(1.dp, if (record.error != null) c.danger.copy(alpha = 0.45f) else c.line2),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(c.brandSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Extension, null, tint = c.brand, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(record.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${if (record.id == "builtin.shell.whale") "内置" else "导入"} · ${if (record.enabled) "已启用" else "已停用"}${if (active) " · 使用中" else ""}" +
                    if (injectedTools.isNotEmpty()) " · ${injectedTools.size} 个工具已接入" else "",
                    style = MaterialTheme.typography.labelSmall, color = if (active) c.brand else c.faint)
                if (record.description.isNotBlank()) Text(record.description,
                    style = MaterialTheme.typography.labelSmall, color = c.faint,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val issue = record.error ?: record.missingServices.takeIf { it.isNotEmpty() }?.let { "缺少服务：${it.joinToString("、")}" }
                if (issue != null) Text(issue, style = MaterialTheme.typography.labelSmall, color = c.danger,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(6.dp))
            Switch(checked = record.enabled, onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = c.brand, uncheckedTrackColor = c.line2))
        }
    }
}

@Composable
fun PluginDetailDialog(
    record: PluginRecord,
    injectedTools: Set<String>,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onDelete: () -> Unit,
    onGrant: (String) -> Unit,
    onSetConfig: (String) -> Unit,
    onOpenWindow: (() -> Unit)? = null,
    onOpenPage: ((String) -> Unit)? = null,
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
                    if (record.clientUi) {
                        // M71：原生替身（大肥鱼 / 3D 鲸鱼娘 / 鲸鱼娘挂件）全部删除，
                        // dsh.client 只走通用客户端插件宿主（跑插件自己的代码）。
                        DetailField(
                            "界面扩展",
                            "含浏览器半侧（dsh.client）：鲸歌用通用客户端插件宿主跑插件自己的 client.js" +
                                "（下方「打开插件窗口」），不再按插件重画；桌面专属的原生 Helper（Electron/Qt/PowerShell）" +
                                "不能在 Android 执行。",
                        )
                        if (onOpenWindow != null) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = onOpenWindow) {
                                Icon(
                                    Icons.Rounded.KeyboardArrowRight,
                                    null,
                                    tint = c.brand,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("打开插件窗口", color = c.brand)
                            }
                        }
                    }
                    record.bundlePatch?.let { DetailField("组合包补丁", it) }
                    if (record.bundleChildren.isNotEmpty()) {
                        Text(
                            "组合包 insert（只展示，不自动安装）",
                            style = MaterialTheme.typography.labelMedium,
                            color = c.sub,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        record.bundleChildren.forEach { child ->
                            Text(
                                "· ${child.name}" +
                                    (if (child.id != child.name) "（id ${child.id}）" else "") +
                                    (if (child.disabled) " · 默认停用" else "") +
                                    (if (child.inject.isNotEmpty()) " · inject ${child.inject.joinToString(",")}" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (child.disabled) c.faint else c.sub,
                            )
                        }
                        if (record.bundleNotes.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "未执行的补丁条目：" + record.bundleNotes.joinToString("；"),
                                style = MaterialTheme.typography.labelSmall,
                                color = c.warn,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    record.enginesDsh?.let { DetailField("要求 DSH 版本", it) }
                    if (record.promptSections.isNotEmpty()) {
                        DetailField(
                            "提示词段",
                            record.promptSections.joinToString("\n") { "· ${it.name}（order ${it.order}）" } +
                                if (record.promptSections.any { it.complete }) "\n（complete=true 手机版不启用）" else "",
                        )
                    }
                    if (record.commands.isNotEmpty()) {
                        DetailField(
                            "命令",
                            record.commands.joinToString("\n") { "· /${it.name}${if (it.description.isBlank()) "" else " — ${it.description}"}" } +
                                "\n（输入 /名字 直接执行，结果走命令提示条，不进模型历史）",
                        )
                    }
                    // M84-E2：ctx.llm.register（配置式供应商）
                    if (record.llmProviders.isNotEmpty()) {
                        DetailField(
                            "模型供应商",
                            record.llmProviders.joinToString("\n") { provider ->
                                "· ${provider.id}（${provider.label}）· " +
                                    (if (provider.protocol.isBlank()) "跟随宿主协议" else provider.protocol) +
                                    (if (provider.model.isNotBlank()) " · model ${provider.model}" else " · 跟随宿主模型") +
                                    (if (provider.baseUrl.isNotBlank()) " · 自定义端点" else " · 跟随宿主端点") +
                                    (if (provider.apiKey.isNotBlank()) " · 自带 key" else " · 跟随宿主 key") +
                                    (if (provider.hasStream) " · 自带 stream（模型由插件产出）" else "")
                            } +
                                "\n（ctx.llm.register：没 stream 的是配置式，宿主包装成内置 Anthropic / OpenAI 通道；" +
                                "带 stream 的在插件常驻运行时里执行：stream(request, emit) 的 emit 是逐块文本，" +
                                "返回值里的 toolCalls 会折成工具块走宿主工具回合；" +
                                "用哪个 id 由宿主/用户选）" +
                                if (record.enabled) "" else "\n（插件已停用：供应商已从注册表收回）",
                        )
                    }
                    // M84-E2：ctx.ui.registerRoute（元数据 + 打开插件自己的 dsh.client）
                    if (record.routes.isNotEmpty()) {
                        Text(
                            "页面路由（ctx.ui.registerRoute）",
                            style = MaterialTheme.typography.labelMedium,
                            color = c.sub,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        record.routes.forEach { route ->
                            Text(
                                "· ${route.title}（${route.id}）" +
                                    (if (route.page) " · 整页" else "") +
                                    if (route.description.isBlank()) "" else "\n  ${route.description}",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.sub,
                            )
                            if (record.enabled && route.page && onOpenPage != null) {
                                TextButton(onClick = { onOpenPage(route.id) }) {
                                    Icon(
                                        Icons.Rounded.KeyboardArrowRight,
                                        null,
                                        tint = c.brand,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("以整页打开", color = c.brand)
                                }
                            }
                        }
                        Text(
                            "（只登记入口，不往宿主页面插任意 UI；点开跑插件自己的 dsh.client）" +
                                if (record.enabled) "" else "\n（插件已停用：路由不生效，重新启用后再打开）",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (record.enabled) c.faint else c.warn,
                        )
                        if (record.enabled && onOpenWindow != null) {
                            TextButton(onClick = onOpenWindow) {
                                Icon(
                                    Icons.Rounded.KeyboardArrowRight,
                                    null,
                                    tint = c.brand,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("打开插件界面", color = c.brand)
                            }
                        }
                    }
                    // M84-H：ctx.sessionProjections.register（常驻 JS 运行时，每事件回调）
                    if (record.projections.isNotEmpty()) {
                        DetailField(
                            "会话投影（ctx.sessionProjections.register）",
                            record.projections.joinToString("\n") { projection ->
                                "· ${projection.key}（stateVersion ${projection.stateVersion}）· " +
                                    (if (projection.hasView) "有 wire.view" else "无视图（落 state）") +
                                    (if (projection.hasApply) "" else " · 没有 apply")
                            } +
                                "\n（插件启用时宿主起常驻 QuickJS，每提交一条会话事件就回调一次 apply 折叠；" +
                                "值在内存里，可用 --es dsh_dump_projections <路径> 查看）" +
                                if (record.enabled) "" else "\n（插件已停用：常驻运行时已关闭，投影值已收回）",
                        )
                    }
                    // M83 服务容器：提供 / 依赖 / 缺依赖 / effect / 事件订阅
                    if (record.provides.isNotEmpty() || record.injects.isNotEmpty() ||
                        record.missingServices.isNotEmpty() || record.effects.isNotEmpty() ||
                        record.subscriptions.isNotEmpty()
                    ) {
                        Text(
                            "服务与依赖（ctx.provide / inject / effect / on）",
                            style = MaterialTheme.typography.labelMedium,
                            color = c.sub,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        if (record.provides.isNotEmpty()) {
                            DetailField("提供", record.provides.keys.joinToString("、"))
                        }
                        if (record.injects.isNotEmpty()) {
                            DetailField(
                                "依赖",
                                record.injects.joinToString("\n") { dep -> "· ${dep.name}${if (dep.optional) "（可选）" else ""}" },
                            )
                        }
                        if (record.missingServices.isNotEmpty()) {
                            DetailField("缺少服务", record.missingServices.joinToString("、") + "\n（提供者被停用或卸载；本插件不注册工具）")
                        }
                        if (record.effects.isNotEmpty()) {
                            DetailField(
                                "ctx.effect",
                                record.effects.joinToString("、") +
                                    "\n（M84-I：插件停用/重载/删除时宿主在常驻运行时里回调 cleanup；异步 cleanup 不阻塞）",
                            )
                        }
                        if (record.subscriptions.isNotEmpty()) {
                            DetailField(
                                "事件订阅",
                                record.subscriptions.joinToString("、") +
                                    "\n（M84-I：会话事件按 type 投递；记 session/event 的插件收全部事件，* 也收）",
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (record.unsupported.isNotEmpty()) {
                        Text(
                            "用到但鲸歌未支持的能力",
                            style = MaterialTheme.typography.labelMedium,
                            color = c.warn,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            record.unsupported.joinToString("、"),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.warn,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
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

/** M83 阶段 2：内置插件一行（名字 + 说明 + 开关）；关掉即从工具表/页面入口消失。 */
@Composable
private fun BuiltinSwitchRow(
    item: xin.dshaa.ui.shellapi.data.BuiltinPluginUi,
    onToggle: (String, Boolean) -> Unit,
    onOpen: () -> Unit = {},
    active: Boolean = false,
) {
    val c = DshTheme2.colors
    Row(
        Modifier.fillMaxWidth().clickable { onOpen() }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(c.brandSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Extension, null, tint = c.brand, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.plugin.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = c.text,
            )
            Text(
                "内置 · ${builtinKindLabel(item.plugin.kind)} · ${if (item.enabled) "已启用" else "已停用"}${if (active) " · 使用中" else ""}\n${item.plugin.description}",
                style = MaterialTheme.typography.labelSmall,
                color = c.faint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = item.enabled,
            onCheckedChange = { on -> onToggle(item.plugin.id, on) },
            colors = SwitchDefaults.colors(checkedTrackColor = c.brand, uncheckedTrackColor = c.line2),
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

/** M85-D：回合插件一行（选中/可用性 + 点击切换）。 */
@Composable
private fun AgentSelectRow(
    item: xin.dshaa.ui.shellapi.AgentTargetUi,
    onSelect: () -> Unit,
) {
    val c = DshTheme2.colors
    val available = item.unavailableReason == null
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = available && !item.active) { onSelect() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(c.brandSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Extension,
                null,
                tint = if (item.active) c.brand else c.sub,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = c.text,
            )
            Text(
                buildString {
                    append(item.provider)
                    append(" · ")
                    append(item.agentId)
                    item.unavailableReason?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (available) c.faint else c.danger,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            when {
                item.active -> "使用中"
                available -> "选择"
                else -> "不可用"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (item.active) c.brand else if (available) c.sub else c.faint,
        )
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

@Composable
private fun RoleTargetsSection(
    title: String,
    hint: String,
    targets: List<xin.dshaa.ui.shellapi.RoleTargetUi>,
    safetyNotice: String?,
    active: xin.dshaa.ui.shellapi.RoleTargetUi?,
    restoreLabel: String,
    onSelect: (String) -> Unit,
    onRestore: () -> Unit,
) {
    val c = DshTheme2.colors
    Spacer(Modifier.height(18.dp))
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = c.sub,
    )
    Spacer(Modifier.height(6.dp))
    if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.labelSmall, color = c.faint)
    if (safetyNotice != null) {
        Spacer(Modifier.height(6.dp))
        Text(safetyNotice, style = MaterialTheme.typography.labelSmall, color = c.danger)
    }
    Spacer(Modifier.height(8.dp))
    DshCard(Modifier.fillMaxWidth()) {
        Column {
            targets.forEach { item ->
                RoleSelectRow(item = item, onSelect = { onSelect(item.selectionValue) })
                RowDivider()
            }
            if (targets.isEmpty()) {
                BuiltinRow("没有可用实现", "对应的内置插件被停用；点下方恢复", Icons.Rounded.Extension)
            }
        }
    }
    if (active?.builtin != true) {
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onRestore) { Text(restoreLabel) }
    }
}

@Composable
private fun RoleSelectRow(
    item: xin.dshaa.ui.shellapi.RoleTargetUi,
    onSelect: () -> Unit,
) {
    val c = DshTheme2.colors
    val available = item.unavailableReason == null
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = available && !item.active) { onSelect() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(c.brandSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Extension,
                null,
                tint = if (item.active) c.brand else c.sub,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = c.text,
            )
            Text(
                buildString {
                    append(item.provider)
                    append(" · ")
                    append(item.implementationId)
                    if (item.readOnly) append(" · 只读")
                    item.unavailableReason?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (available) c.faint else c.danger,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            when {
                item.active -> "使用中"
                available -> "选择"
                else -> "不可用"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (item.active) c.brand else if (available) c.sub else c.faint,
        )
    }
}

@Composable
private fun CapabilityRow(
    item: xin.dshaa.ui.shellapi.CapabilityUi,
    onInvoke: () -> Unit,
) {
    val c = DshTheme2.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(c.brandSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Extension,
                null,
                tint = if (item.available) c.brand else c.faint,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = c.text,
            )
            Text(
                buildString {
                    append(item.provider)
                    append(" · ")
                    append(item.id)
                    if (item.kind.isNotBlank()) append(" · ").append(item.kind)
                    item.unavailableReason?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (item.available) c.faint else c.danger,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.available) {
            TextButton(onClick = onInvoke) { Text("调用", color = c.brand) }
        } else {
            Text(
                "不可用",
                style = MaterialTheme.typography.labelMedium,
                color = c.faint,
            )
        }
    }
}

private fun builtinKindLabel(kind: xin.dshaa.ui.shellapi.data.BuiltinPluginKind): String = when (kind) {
    xin.dshaa.ui.shellapi.data.BuiltinPluginKind.Tools -> "工具"
    xin.dshaa.ui.shellapi.data.BuiltinPluginKind.Page -> "页面"
    xin.dshaa.ui.shellapi.data.BuiltinPluginKind.Floating -> "悬浮窗"
    xin.dshaa.ui.shellapi.data.BuiltinPluginKind.Shell -> "界面壳 shell"
    xin.dshaa.ui.shellapi.data.BuiltinPluginKind.Agent -> "回合 agent"
    xin.dshaa.ui.shellapi.data.BuiltinPluginKind.Storage -> "存储 storage"
    xin.dshaa.ui.shellapi.data.BuiltinPluginKind.Prompt -> "提示词 prompt"
    xin.dshaa.ui.shellapi.data.BuiltinPluginKind.Model -> "模型 model"
}
