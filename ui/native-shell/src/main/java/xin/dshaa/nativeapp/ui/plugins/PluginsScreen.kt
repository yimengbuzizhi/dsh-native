package xin.dshaa.nativeapp.ui.plugins

import xin.dshaa.ui.design.component.liquidGlass
import xin.dshaa.ui.design.theme.LiquidLevel
import xin.dshaa.ui.design.theme.LiquidRadius
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
import android.Manifest
import android.net.Uri
import android.os.Build
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import xin.dshaa.core.plugins.PluginCapabilities
import xin.dshaa.core.plugins.PluginCapability
import xin.dshaa.core.plugins.PluginPermission
import xin.dshaa.core.plugins.PluginPermissionKind
import xin.dshaa.core.plugins.PluginPermissions
import xin.dshaa.core.plugins.PluginOwnership
import xin.dshaa.core.plugins.PluginRecord
import xin.dshaa.core.plugins.PluginSchema
import xin.dshaa.nativeapp.device.DevicePermissions
import xin.dshaa.ui.design.component.DshCard
import xin.dshaa.ui.design.component.MiniChip
import xin.dshaa.ui.design.component.SectionHeader
import xin.dshaa.ui.design.component.SettingsSwitchRow
import xin.dshaa.ui.design.theme.DshTheme2
import xin.dshaa.ui.shellapi.NodeHostManifestUi
import xin.dshaa.ui.shellapi.NodeHostPluginUi
import xin.dshaa.ui.shellapi.NodeHostStatusUi
import xin.dshaa.ui.shellapi.PluginExtensionUi
import xin.dshaa.ui.shellapi.PluginImportDraftUi
import xin.dshaa.ui.shellapi.visibleInRoleList
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

/** 切界面壳会让整棵宿主重组、丢掉 rememberSaveable 的页签；进程内记住最近一次页签。 */
private object PluginsTabMemory { var index = 0 }

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
    /** M87-F：插件页「扩展点」页签（逐链快照）与中间件总开关。 */
    pluginExtensions: List<PluginExtensionUi> = emptyList(),
    pluginMiddlewareEnabled: Boolean = true,
    onTogglePluginMiddleware: (Boolean) -> Unit = {},
    /** docs/185 P2：Node 插件宿主（跑真 dsh Node 插件）的开关与状态文案。 */
    nodeHostEnabled: Boolean = false,
    nodeHostState: String = "",
    /** docs/188 P4：结构化状态（OFF/IDLE/EXTRACTING/STARTING/READY/FAILED/STOPPED）。 */
    nodeHostStatus: NodeHostStatusUi = NodeHostStatusUi(
        code = "off",
        title = "已关闭（OFF）",
        detail = "Node 宿主没开：Node 插件不会加载；原生插件、聊天和工具不受影响。",
    ),
    onToggleNodeHost: (Boolean) -> Unit = {},
    nodeHostPlugins: List<NodeHostPluginUi> = emptyList(),
    nodeHostManifest: List<NodeHostManifestUi> = emptyList(),
    nodeHostLogs: List<String> = emptyList(),
    onReloadNodeHost: () -> Unit = {},
    onImportNodePlugin: (Uri) -> Unit = {},
    onRefreshExtensions: () -> Unit = {},
    /** docs/188 P4：统一导入（识别归属 + 可改 + 迁移）。 */
    pluginImportDraft: PluginImportDraftUi? = null,
    pluginImportLimits: String = "",
    onBeginPluginImport: (Uri) -> Unit = onImport,
    onConfirmPluginImport: (String) -> Unit = {},
    onCancelPluginImport: () -> Unit = {},
    /** docs/187 P3-d：预设（纯数据：模型 + 提示词段 + 工具 + 权限）。 */
    presetTargets: List<xin.dshaa.core.plugins.PluginPreset> = emptyList(),
    activePresetId: String? = null,
    presetNotice: String? = null,
    onApplyPreset: (String) -> Unit = {},
    onClearPreset: () -> Unit = {},
    onSavePreset: (String) -> Unit = {},
    onDeletePreset: (String) -> Unit = {},
    /** M88-E：AI 授予后的横幅「撤销」与详情审计/AI 授予标记。 */
    onUndoAiGrant: () -> Unit = {},
    pluginAuditLines: (String) -> List<String> = { emptyList() },
    aiGrantedPermissions: (String) -> Set<String> = { emptySet() },
) {
    val c = DshTheme2.colors
    // 详情按 id 从最新的 plugins 里取：授权/重新加载后弹层立刻翻状态，不拿旧 record。
    var detailId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<PluginRecord?>(null) }
    var showCapabilities by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(PluginsTabMemory.index) }
    var query by rememberSaveable { mutableStateOf("") }
    var builtinDetailId by remember { mutableStateOf<String?>(null) }
    fun matches(vararg fields: String): Boolean = query.isBlank() || fields.any { it.contains(query.trim(), true) }
    // docs/187 §5.1：随包内置的壳插件（`builtin.shell.whale`）已删除，所以「内置」分区恒空；
    // 保留这个变量是为了以后真有内置插件时不用改下面的分区渲染（旧记录由 App 启动时清理）。
    val builtinRecords = plugins.filter { it.id in OBSOLETE_PLUGIN_IDS }
    val imported = plugins.filterNot { it.id in OBSOLETE_PLUGIN_IDS }
    // docs/188 P4：两分区。归属为 Node 但装在原生目录里的记录（迁移/备份恢复可能出现）
    // 不混进「原生插件」，放在 Node 分区里标明「原生安装」。
    val nativeRecords = imported.filter { it.ownership != PluginOwnership.NODE }
    val nodeOwnedNative = imported.filter { it.ownership == PluginOwnership.NODE }
    val nodeCount = maxOf(nodeHostManifest.size, nodeHostPlugins.size) + nodeOwnedNative.size
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
        if (uri != null) onBeginPluginImport(uri)
    }

    var nodeLogsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onRefresh() }
    // M87-F：切到「扩展点」页签时重算一次快照（命中/耗时只在本进程内存里）。
    LaunchedEffect(tab) { PluginsTabMemory.index = tab; if (tab == 3) onRefreshExtensions() }

    Column(Modifier.fillMaxSize().background(Color.Transparent)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp).liquidGlass(shape = RoundedCornerShape(LiquidRadius.xl), level = LiquidLevel.Panel)
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
                    "原生 ${builtins.size + nativeRecords.size} · Node $nodeCount · 宿主 ${nodeHostStatus.title}",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.faint,
                )
            }
            TextButton(onClick = { showCapabilities = true }) { Text("帮助", color = c.sub) }
        }

        TabRow(selectedTabIndex = tab, containerColor = c.panel, contentColor = c.brand) {
            listOf("导入", "内置", "角色", "扩展点").forEachIndexed { index, label ->
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
            if (notice != null) item {
                NoticeBanner(
                    notice,
                    onDismissNotice,
                    onUndo = if (notice.startsWith("AI 已授予")) {
                        { onUndoAiGrant() }
                    } else {
                        null
                    },
                )
            }
            when (tab) {
                0 -> {
                    item {
                        ImportCard(
                            busy = busy,
                            limits = pluginImportLimits,
                            onPick = { picker.launch(arrayOf("*/*")) },
                        )
                    }
                    // 分区 1：原生插件（鲸歌 JS 插件 / 声明式包 / 内置）。
                    val nativeVisible = nativeRecords.filter { recordMatches(it) }
                    item { SectionHeader("原生插件（${nativeVisible.size}）") }
                    if (nativeVisible.isEmpty()) item {
                        Text(
                            if (nativeRecords.isEmpty()) "还没有原生插件" else "没有匹配的原生插件",
                            color = c.faint,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    items(nativeVisible, key = { it.id }) { record ->
                        PluginCard(record, injected[record.id].orEmpty(),
                            onOpen = { detailId = record.id }, onToggle = { onToggle(record.id, it) },
                            active = record.id in usedPlugins)
                    }
                    if (PluginPermissions.isBroadStorageRoot(workspacePath) &&
                        plugins.any { "fs:workspace" in it.permissions }) item {
                        Text("当前工作区是共享存储根（$workspacePath）：fs:workspace 可访问整块共享存储。", color = c.warn,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    // 分区 2：Node 插件（宿主模块 + 清单 + 状态 + 日志入口）。
                    item {
                        NodeHostSection(
                            enabled = nodeHostEnabled,
                            status = nodeHostStatus,
                            plugins = nodeHostPlugins.filter {
                                query.isBlank() || matches(it.module, it.name, it.id, it.tools.joinToString(" "))
                            },
                            manifest = nodeHostManifest.filter {
                                query.isBlank() || matches(it.module, it.name, it.id)
                            },
                            logs = nodeHostLogs,
                            limits = pluginImportLimits,
                            busy = busy,
                            onToggle = onToggleNodeHost,
                            onReload = onReloadNodeHost,
                            onImport = {
                                picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                            },
                            logsOpen = nodeLogsOpen,
                            onToggleLogs = { nodeLogsOpen = !nodeLogsOpen },
                        )
                    }
                    if (nodeOwnedNative.isNotEmpty()) {
                        item {
                            Text(
                                "Node 归属、装在原生侧（可开容器，Node 宿主不会重复加载）",
                                color = c.faint,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        items(nodeOwnedNative.filter { recordMatches(it) }, key = { it.id }) { record ->
                            PluginCard(record, injected[record.id].orEmpty(), onOpen = { detailId = record.id },
                                onToggle = { onToggle(record.id, it) }, active = record.id in usedPlugins)
                        }
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
                        // 0.3.4（用户第 4 条）：停用/卸载的插件不再以「不可用」占位，也不再进计数。
                        Text(
                            "只列已启用且有授权的实现；停用当前提供方会自动回退内置并清空选择，这里不再显示。",
                            color = c.faint,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    val shells = shellTargets.filter { it.visibleInRoleList() && roleMatches("界面 壳 shell", it.label, it.implementationId, it.provider + " " + it.pluginId.orEmpty()) }
                    if (shells.isNotEmpty() || query.isBlank()) item {
                        RoleTargetsSection("界面壳（${shells.size}）", "", shells, null,
                            shellTargets.firstOrNull { it.active }, "恢复原生界面", onSelectShell, onRestoreBuiltinShell)
                    }
                    val loadings = loadingTargets.filter { it.visibleInRoleList() && roleMatches("启动 加载页 loading", it.label, it.implementationId, it.provider + " " + it.pluginId.orEmpty()) }
                    if (loadings.isNotEmpty() || query.isBlank() || loadingSafetyNotice != null) item {
                        RoleTargetsSection("启动加载页（${loadings.size}）", "选择后下次启动生效", loadings,
                            loadingSafetyNotice, loadingTargets.firstOrNull { it.active }, "恢复默认加载页",
                            onSelectLoading, onRestoreBuiltinLoading)
                    }
                    val agents = agentTargets.filter { it.visibleInRoleList() && roleMatches("回合 agent", it.label, it.agentId, it.provider + " " + it.pluginId.orEmpty()) }
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
                        val filtered = targets.filter { it.visibleInRoleList() && roleMatches(role, it.label, it.implementationId, it.provider + " " + it.pluginId.orEmpty()) }
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
                    // docs/187 P3-d：预设（模型 + 提示词段 + 工具 + 权限的组合，纯数据）。
                    // 只有「套用 / 清除 / 存为预设 / 删除」四个动作，不写死任何插件。
                    item {
                        PresetSection(
                            presets = presetTargets,
                            activeId = activePresetId,
                            notice = presetNotice,
                            onApply = onApplyPreset,
                            onClear = onClearPreset,
                            onSave = onSavePreset,
                            onDelete = onDeletePreset,
                        )
                    }
                    val caps = capabilityTargets.filter { matches("能力 capability", it.id, it.label, it.provider) }
                    if (caps.isNotEmpty() || query.isBlank()) item { SectionHeader("能力（${caps.size}）") }
                    if (capabilityNotice != null) item { Text(capabilityNotice, color = c.sub, style = MaterialTheme.typography.labelSmall) }
                    items(caps, key = { "cap:" + it.id }) { target ->
                        DshCard(Modifier.fillMaxWidth()) { CapabilityRow(target) { onInvokeCapability(target.id) } }
                    }
                }
                3 -> {
                    // M87-F：总开关 + 逐链快照（注册者/顺序/命中/耗时/异常/改写哈希）。
                    // M87-F：总开关 + 逐链快照（注册者/顺序/命中/耗时/异常/改写哈希）。
                    item {
                        DshCard(Modifier.fillMaxWidth()) {
                            SettingsSwitchRow(
                                title = "插件中间件总开关",
                                subtitle = "关闭后工具/请求/存储/事件钩子全部旁路（排障用）；插槽与插件注册不受影响",
                                checked = pluginMiddlewareEnabled,
                                onCheckedChange = onTogglePluginMiddleware,
                            )
                        }
                    }
                    item {
                        Text(
                            "逐链快照（插件清单顺序 = 链顺序）。命中/耗时只统计本次进程；没有 dispatch 过的显示「尚未命中」。",
                            color = c.faint,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    val visible = pluginExtensions.filter {
                        query.isBlank() || it.pluginId.contains(query.trim(), true) ||
                            it.pluginName.contains(query.trim(), true)
                    }
                    if (visible.isEmpty()) item {
                        DshCard(Modifier.fillMaxWidth()) {
                            Text(
                                when {
                                    plugins.none { it.enabled } -> "没有启用中的插件"
                                    query.isNotBlank() -> "没有匹配的插件"
                                    else -> "已启用的插件没有注册中间件/钩子/插槽"
                                },
                                color = c.faint,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                    }
                    items(visible, key = { it.pluginId }) { row ->
                        PluginExtensionCard(row)
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
            } }, confirmButton = { TextButton(onClick = { builtinDetailId = null }) { Text("关闭") } }, containerColor = c.panel)
    }

    val detailRecord = plugins.firstOrNull { it.id == detailId }
    detailRecord?.let { record ->
        PluginDetailDialog(
            record = record,
            injectedTools = injected[record.id].orEmpty(),
            auditLines = pluginAuditLines(record.id),
            aiGranted = aiGrantedPermissions(record.id),
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
                    "会删掉 ${record.name} 的目录（files/plugins/${record.id}）。\n" +
                        "不影响会话和内置插件；如果它正在使用中，会先回退到内置实现并清空相关角色选择。",
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
            containerColor = c.panel,
        )
    }

    pluginImportDraft?.let { draft ->
        PluginImportDialog(
            draft = draft,
            busy = busy,
            onConfirm = onConfirmPluginImport,
            onCancel = onCancelPluginImport,
        )
    }

    if (showCapabilities) {
        CapabilityDialog(onDismiss = { showCapabilities = false })
    }
}

/** M87-F：一个插件的逐链快照（扩展点页签）。 */
@Composable
private fun PluginExtensionCard(row: PluginExtensionUi) {
    val c = DshTheme2.colors
    DshCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${row.order}", color = c.faint, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(6.dp))
                Text(
                    row.pluginName,
                    fontWeight = FontWeight.SemiBold,
                    color = c.text,
                    modifier = Modifier.weight(1f),
                )
                MiniChip(if (row.enabled) "已启用" else "已停用", color = if (row.enabled) c.success else c.faint)
            }
            Text(row.pluginId, color = c.faint, style = MaterialTheme.typography.labelSmall)
            row.chains.forEach { chain ->
                Spacer(Modifier.height(8.dp))
                Row {
                    Text(
                        chain.kind,
                        color = c.brand,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(32.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                chain.name,
                                color = c.text,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                chain.status,
                                color = extensionStatusColor(chain.status),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (chain.stats.isNotBlank()) {
                            Text(chain.stats, color = c.sub, style = MaterialTheme.typography.labelSmall)
                        }
                        chain.detail?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = c.faint, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun extensionStatusColor(status: String): androidx.compose.ui.graphics.Color {
    val c = DshTheme2.colors
    return when {
        status.startsWith("生效") -> c.success
        status.startsWith("缺授权") || status.startsWith("已停用") -> c.warn
        else -> c.sub
    }
}

@Composable
private fun CapabilityDialog(onDismiss: () -> Unit) {
    val c = DshTheme2.colors
    val done = PluginCapabilities.all.count { it.implemented }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.panel,
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
private fun ImportCard(busy: Boolean, limits: String, onPick: () -> Unit) {
    val c = DshTheme2.colors
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { onPick() },
        shape = RoundedCornerShape(18.dp),
        color = c.panel,
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
                    if (busy) "正在识别插件包…" else "从本地文件导入",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = c.text,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    if (busy) "读取清单、识别归属、检查是否已装" else "选择 .zip：先识别「原生 / Node」，确认后再装",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.sub,
                )
                if (limits.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "上限：$limits",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.faint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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

/**
 * docs/188 P4：Node 分区。宿主关着也显示 OFF 状态与已登记模块（不能一片空白）；
 * 失败时原始原因 + 可操作提示 + 日志尾巴 + 重试入口都在同一块里。
 */
@Composable
private fun NodeHostSection(
    enabled: Boolean,
    status: NodeHostStatusUi,
    plugins: List<NodeHostPluginUi>,
    manifest: List<NodeHostManifestUi>,
    logs: List<String>,
    limits: String,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onReload: () -> Unit,
    onImport: () -> Unit,
    logsOpen: Boolean,
    onToggleLogs: () -> Unit,
) {
    val c = DshTheme2.colors
    val statusColor = when (status.code) {
        "ready" -> c.success
        "failed" -> c.danger
        "extracting", "starting" -> c.brand
        "stopped" -> c.warn
        else -> c.faint
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Node 插件（${maxOf(plugins.size, manifest.size)}）",
                color = c.text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "导入 .zip",
                color = c.brand,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable(enabled = !busy) { onImport() },
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (status.code == "failed") "重试启动" else "重新加载",
                color = c.brand,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable(enabled = enabled && !busy) { onReload() },
            )
        }
        DshCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(statusColor))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        status.title,
                        color = if (status.failed) c.danger else c.text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = onToggle,
                        colors = SwitchDefaults.colors(checkedTrackColor = c.brand, uncheckedTrackColor = c.line2),
                    )
                }
                if (status.detail.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        status.detail,
                        color = if (status.failed) c.danger else c.sub,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (status.hint.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(status.hint, color = c.warn, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    (if (enabled) "Node 导入上限：" else "Node 关闭中；导入上限：") +
                        limits.substringAfter("｜Node：").ifBlank { limits },
                    color = c.faint,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (!enabled) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "宿主 OFF：Node 插件不会加载、不出现在模型工具表；原生插件和聊天照常。",
                        color = c.faint,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (status.failed) {
                    Spacer(Modifier.height(6.dp))
                    Text("先看日志尾巴再重试；错误不会被吞。", color = c.faint, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        val loadedByModule = plugins.associateBy { it.module }
        val unloaded = manifest.filter { it.module !in loadedByModule }
        if (plugins.isEmpty() && manifest.isEmpty()) {
            DshCard(Modifier.fillMaxWidth()) {
                Text(
                    if (!enabled) "还没有 Node 插件。打开开关或点「导入 .zip」（会把归属识别为 Node）。"
                    else "清单里还没有模块；就绪前状态行会显示「正在解压 / 正在启动」。",
                    color = c.faint,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
        plugins.forEach { plugin ->
            NodePluginCard(plugin, loaded = true)
        }
        unloaded.forEach { entry ->
            NodeManifestCard(entry, status.code)
        }
        DshCard(Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "宿主日志（${logs.size} 行）",
                        color = c.sub,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (logsOpen) "收起" else "展开",
                        color = c.brand,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.clickable { onToggleLogs() },
                    )
                }
                if (logsOpen) {
                    Text(
                        logs.joinToString("\n").ifBlank { "（还没有日志；宿主启动后这里会有 stdout / host.log）" },
                        color = c.faint,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NodePluginCard(plugin: NodeHostPluginUi, loaded: Boolean) {
    val c = DshTheme2.colors
    DshCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (plugin.ok) c.success else c.danger),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    plugin.name.ifBlank {
                        when {
                            plugin.module.startsWith("./plugins/") ->
                                plugin.module.removePrefix("./plugins/").substringBeforeLast('/')
                            else -> plugin.module.substringAfterLast('/').ifBlank { plugin.module }
                        }
                    },
                    color = c.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when {
                        !loaded -> "未加载"
                        plugin.ok -> "已加载"
                        else -> "加载失败"
                    },
                    color = if (plugin.ok) c.sub else c.danger,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (plugin.id.isNotBlank() || plugin.version.isNotBlank()) {
                Text(
                    listOf(plugin.id, plugin.version).filter { it.isNotBlank() }.joinToString(" · "),
                    color = c.faint,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "模块：${plugin.module}",
                color = c.faint,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                plugin.tools.isNotEmpty() -> Text(
                    "工具：" + plugin.tools.joinToString("、") { "node_$it" },
                    color = c.sub,
                    style = MaterialTheme.typography.labelSmall,
                )
                plugin.ok -> Text(
                    if (plugin.hasClient) "没有宿主工具（界面类插件：可在原生插件里用容器打开）"
                    else "没有注册工具（可能只注册提示词段/供应商/命令）",
                    color = c.faint,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            plugin.error?.let { message ->
                Text(
                    message,
                    color = c.danger,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NodeManifestCard(entry: NodeHostManifestUi, statusCode: String) {
    val c = DshTheme2.colors
    DshCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c.warn))
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.name.ifBlank { entry.id },
                    color = c.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when (statusCode) {
                        "off" -> "未加载（OFF）"
                        "failed" -> "未加载（FAILED）"
                        "extracting", "starting" -> "等待启动"
                        else -> "未报上来"
                    },
                    color = c.warn,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                listOf(entry.id, entry.version).filter { it.isNotBlank() }.joinToString(" · "),
                color = c.faint,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                "模块：${entry.module}",
                color = c.faint,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * docs/188 P4：导入确认对话框。默认归属来自清单判据；可以改选另一侧。
 * 同 id 已装在另一侧 = 迁移（会删旧侧，配置重置）；同侧 = 覆盖（也会重置配置）。
 */
@Composable
private fun PluginImportDialog(
    draft: PluginImportDraftUi,
    busy: Boolean,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val c = DshTheme2.colors
    var chosen by remember(draft.id, draft.ownership) { mutableStateOf(draft.ownership) }
    val migrating = (chosen == PluginOwnership.NATIVE && draft.nodeInstalled) ||
        (chosen == PluginOwnership.NODE && draft.nativeInstalled)
    val sameSide = (chosen == PluginOwnership.NATIVE && draft.nativeInstalled) ||
        (chosen == PluginOwnership.NODE && draft.nodeInstalled)
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        containerColor = c.panel,
        title = { Text("导入插件", color = c.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(draft.name, color = c.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    listOf(draft.id, draft.version).filter { it.isNotBlank() }.joinToString(" · "),
                    color = c.faint,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (draft.description.isNotBlank()) {
                    Text(draft.description, color = c.sub, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(12.dp))
                Text("归属（可改）", color = c.sub, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                DialogOwnershipRow(
                    label = "原生",
                    detail = "鲸歌原生插件：QuickJS 里 apply(ctx)，工具直接进原生工具表。",
                    selected = chosen == PluginOwnership.NATIVE,
                    onClick = { if (!busy) chosen = PluginOwnership.NATIVE },
                )
                DialogOwnershipRow(
                    label = "Node",
                    detail = "DSH/Node 插件：交给 Node 宿主加载（node_ 工具）；带 web client 的界面由容器跑。",
                    selected = chosen == PluginOwnership.NODE,
                    onClick = { if (!busy) chosen = PluginOwnership.NODE },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "识别判据：" + (draft.signals.takeIf { it.isNotEmpty() }?.joinToString("；")
                        ?: "没有 @deepseek-ai 依赖、也没有 exports[\"./client\"] → 原生"),
                    color = c.faint,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (draft.hasClient) {
                    Text("含 web client：装到原生侧后可用容器打开。", color = c.faint, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (chosen == PluginOwnership.NATIVE) "上限：${draft.limitsNative}" else "上限：${draft.limitsNode}",
                    color = c.faint,
                    style = MaterialTheme.typography.labelSmall,
                )
                when {
                    migrating -> {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "迁移：同 id 已装在" + (if (draft.nodeInstalled) " Node " else "原生 ") + "侧。" +
                                "确认后会先装到所选侧，再删掉旧侧；旧侧的配置/开关会重置，同一个 id 不会双装。",
                            color = c.warn,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    sameSide -> {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "同 id 已装在同一侧：覆盖导入会重置这个插件的配置。",
                            color = c.warn,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { onConfirm(chosen) }) {
                Text(
                    if (busy) "正在安装…" else "导入为" + PluginOwnership.label(chosen),
                    color = if (busy) c.faint else c.brand,
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onCancel) { Text("取消", color = c.sub) }
        },
    )
}

@Composable
private fun DialogOwnershipRow(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val c = DshTheme2.colors
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) c.brandSoft else c.panel,
        border = BorderStroke(1.dp, if (selected) c.brand else c.line2),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color = c.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(detail, color = c.faint, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun NoticeBanner(text: String, onDismiss: () -> Unit, onUndo: (() -> Unit)? = null) {
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
            // M88-E：AI 授予的横幅带一个「撤销」，不用再进插件详情找权限行。
            onUndo?.let {
                TextButton(
                    onClick = it,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("撤销", color = c.brand, style = MaterialTheme.typography.labelMedium)
                }
            }
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
        shape = RoundedCornerShape(16.dp), color = c.panel,
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
                Text("${PluginOwnership.label(record.ownership)} · ${if (record.id in OBSOLETE_PLUGIN_IDS) "内置" else "导入"} · ${if (record.enabled) "已启用" else "已停用"}${if (active) " · 使用中" else ""}" +
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
    auditLines: List<String> = emptyList(),
    aiGranted: Set<String> = emptySet(),
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
    // M87-H5：notify 是「插件权限 + Android 系统权限」两道门。用户确认 notify 时，
    // Android 13+ 顺带请求 POST_NOTIFICATIONS；拒绝后权限行回显「系统通知权限未开」+ 去设置入口。
    val context = LocalContext.current
    var systemNotifyGranted by remember {
        mutableStateOf(DevicePermissions.notificationPermissionGranted(context))
    }
    val notifyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        systemNotifyGranted = granted
        if (!granted) {
            Toast.makeText(context, "系统通知权限未开：插件通知不会出现在通知栏", Toast.LENGTH_LONG).show()
        }
    }
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
                            "${record.version} · ${record.format} · 归属 ${PluginOwnership.label(record.ownership)} · " +
                                (if (record.enabled) "已启用" else "已停用"),
                            style = MaterialTheme.typography.labelSmall,
                            color = c.faint,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    DetailField("归属", PluginOwnership.label(record.ownership))
                    DetailField("插件目录", record.path)
                    DetailField("入口", record.main.ifBlank { "—（声明式包，无 JS 入口）" })
                    DetailField("工具数", "${record.tools.size} 个 · 已接入对话 ${injectedTools.size} 个")
                    if (record.contributions.isNotEmpty()) {
                        // M87-G：统一包 IR 的贡献物（旧脚本包也会显示 script/client/preset）。
                        DetailField("包格式", record.irFormat)
                        DetailField(
                            "贡献物",
                            record.contributions.joinToString("\n") { contribution ->
                                "· ${contribution.kind.label}（${contribution.kind.id}）：${contribution.id}" +
                                    (contribution.entry?.let { " · 入口 $it" } ?: "") +
                                    (if (contribution.assets.isNotEmpty()) " · 素材 ${contribution.assets.size} 个" else "")
                            },
                        )
                    }
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
                            val granted = PluginPermissions.isGranted(permission, record.grantedPermissions)
                            PermissionRow(
                                permission = permission,
                                granted = granted,
                                aiGranted = permission.raw in aiGranted,
                                onAsk = { pendingGrant = permission },
                            )
                            // M87-H5：插件 notify 已授权但系统通知权限没开 → 给出真实原因与设置入口。
                            if (permission.kind == PluginPermissionKind.NOTIFY && granted &&
                                (!systemNotifyGranted || !DevicePermissions.notificationsEnabled(context))
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "系统通知权限未开，插件通知不会显示",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = c.danger,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { DevicePermissions.openNotificationSettings(context) }) {
                                        Text("去系统设置", color = c.brand)
                                    }
                                }
                            }
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
                    // M88-E：AI/用户对插件做过的最近 5 条操作（授予/撤销/配置/角色）。
                    if (auditLines.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "最近操作（审计）",
                            style = MaterialTheme.typography.labelMedium,
                            color = c.sub,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        auditLines.forEach { line ->
                            Text(
                                "· $line",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = c.faint,
                            )
                        }
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
                    // 勾选 notify 之后才请求系统权限（Android 13+）；低版本不需要。
                    if (permission.kind == PluginPermissionKind.NOTIFY &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !systemNotifyGranted
                    ) {
                        notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
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
                containerColor = c.panel,
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
                containerColor = c.panel,
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
                containerColor = c.panel,
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
                containerColor = c.panel,
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
                containerColor = c.panel,
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
        containerColor = c.panel,
    )
}

@Composable
private fun PermissionRow(
    permission: PluginPermission,
    granted: Boolean,
    onAsk: () -> Unit,
    aiGranted: Boolean = false,
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
                    granted -> if (aiGranted) "已允许 · AI 授予" else "已允许"
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

/** docs/187 §5：随包内置、现已删除的插件 id（旧记录由 App 启动时清理；这里只用于分区/标签兜底）。 */
private val OBSOLETE_PLUGIN_IDS = setOf("builtin.shell.whale", "builtin.floating.demo")

/**
 * docs/187 P3-d：角色页的「预设」区（只有这一段是 P3-d 加的，其余布局不动）。
 *
 * 预设 = 模型 + 提示词段 + 工具 + 权限的组合，纯数据（`files/node/data/presets.json`）。
 * 这里只提供「套用 / 清除 / 存为预设 / 删除」四个动作，**不写死任何插件**。
 */
@Composable
private fun PresetSection(
    presets: List<xin.dshaa.core.plugins.PluginPreset>,
    activeId: String?,
    notice: String?,
    onApply: (String) -> Unit,
    onClear: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val c = DshTheme2.colors
    var draft by remember { mutableStateOf("") }
    SectionHeader("预设（${presets.size}）")
    if (notice != null) {
        Text(
            notice,
            color = c.sub,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
        )
    }
    DshCard(Modifier.fillMaxWidth()) {
        Column {
            if (presets.isEmpty()) {
                Text(
                    "还没有预设。把当前的模型 / 提示词段 / 工具 / 权限存一份，换场景时一键套用。",
                    color = c.faint,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(14.dp),
                )
            }
            presets.forEach { preset ->
                val active = preset.id == activeId
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (active) "${preset.name} · 使用中" else preset.name,
                            color = if (active) c.brand else c.text,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(preset.summary(), color = c.faint, style = MaterialTheme.typography.labelSmall)
                    }
                    if (!active) {
                        TextButton(onClick = { onApply(preset.id) }) { Text("套用", color = c.brand) }
                    }
                    TextButton(onClick = { onDelete(preset.id) }) { Text("删除", color = c.danger) }
                }
                RowDivider()
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text("预设名") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        val name = draft.trim()
                        if (name.isNotEmpty()) {
                            onSave(name)
                            draft = ""
                        }
                    },
                ) { Text("存为预设", color = c.brand) }
            }
            if (activeId != null) {
                TextButton(onClick = onClear) { Text("清除套用（恢复工具/提示词段不限制）") }
            }
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
