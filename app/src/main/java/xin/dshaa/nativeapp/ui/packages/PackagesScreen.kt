package xin.dshaa.nativeapp.ui.packages

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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import xin.dshaa.core.plugins.PluginRecord
import xin.dshaa.core.skill.SkillSummary
import xin.dshaa.nativeapp.data.McpConfigStore
import xin.dshaa.nativeapp.data.McpServer
import xin.dshaa.nativeapp.data.OperitBundledPackage
import xin.dshaa.nativeapp.data.OperitStatus
import xin.dshaa.nativeapp.ui.plugins.PluginDetailDialog
import xin.dshaa.ui.design.theme.DshTheme2
import java.io.File

/**
 * 包管理（2026-11-09，按 Operit `PackageManagerScreen` 的结构重做）。
 *
 * 结构照搬 Operit：
 * 1. 顶部 TabRow：插件 / 技能 / MCP（对应 Operit 的 PLUGINS / SKILLS / MCP；
 *    Operit 的 PACKAGES（ToolPkg 脚本包）鲸歌没有对应数据层，先不摆空 Tab）；
 * 2. 内容区 LazyColumn：每行是「图标 + 名称/描述 + 右侧开关」，点行开详情；
 * 3. 空状态：Info 图标 + 一句话（照 Operit `EmptyState`）；
 * 4. 右下角 + 导入：插件选 zip、技能选 SKILL.md、MCP 选 json；
 * 5. 详情弹层：插件复用现有 [PluginDetailDialog]，技能/MCP 用同样的字段式弹层。
 *
 * 数据层不重做：插件仍是 `PluginStore`（QuickJS zip），技能仍是文件系统扫描
 * （`FileSystemSkills`），MCP 仍是 `McpConfigStore`（配置管理）。
 */
@Composable
fun PackagesScreen(
    plugins: List<PluginRecord>,
    injected: Map<String, Set<String>>,
    pluginsBusy: Boolean,
    pluginNotice: String?,
    operitPackages: List<OperitBundledPackage>,
    operitPackagesBusy: Boolean,
    skills: List<SkillSummary>,
    skillsComplete: Boolean,
    skillsWarnings: List<String>,
    skillNotice: String?,
    homeDir: String,
    workspaceDir: String,
    onBack: () -> Unit,
    onRefreshPlugins: () -> Unit,
    onImportPlugin: (Uri) -> Unit,
    onTogglePlugin: (String, Boolean) -> Unit,
    onReloadPlugin: (String) -> Unit,
    onDeletePlugin: (String) -> Unit,
    onGrantPlugin: (String, String) -> Unit,
    onSetPluginConfig: (String, String) -> Unit,
    onDismissPluginNotice: () -> Unit,
    onLoadOperitPackages: () -> Unit,
    onInstallOperitPackage: (String) -> Unit,
    onRefreshSkills: () -> Unit,
    onImportSkill: (Uri) -> Unit,
    onDismissSkillNotice: () -> Unit,
) {
    val c = DshTheme2.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val mcp = remember { McpConfigStore.get(context) }
    val mcpServers = mcp.servers

    var tab by rememberSaveable { mutableStateOf(PackageTab.PLUGINS) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    var selectedPluginId by remember { mutableStateOf<String?>(null) }
    var selectedSkill by remember { mutableStateOf<SkillSummary?>(null) }
    var selectedMcp by remember { mutableStateOf<McpServer?>(null) }
    /** M76：内置包详情（未安装也能看完整介绍 / 工具 / 权限）。 */
    var selectedPackage by remember { mutableStateOf<OperitBundledPackage?>(null) }
    var pendingDeletePlugin by remember { mutableStateOf<PluginRecord?>(null) }
    var pendingDeleteMcp by remember { mutableStateOf<McpServer?>(null) }
    var showSkillWarnings by remember { mutableStateOf(false) }

    // 插件包：zip → AppViewModel.importPlugin（现有链路）。
    val pluginPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportPlugin(uri)
    }
    // 技能：单个 SKILL.md → AppViewModel.importSkill，落到 files/skills。
    val skillPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportSkill(uri)
    }
    // MCP 配置：{"mcpServers": {...}} 或直接 map。
    val mcpPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.getOrDefault("")
        val result = mcp.importJson(text)
        scope.launch {
            snackbar.showSnackbar(
                result.fold(
                    { "已导入 $it 个 MCP 服务器" },
                    { "导入失败：${it.message}" },
                ),
            )
        }
    }

    // 导入结果提示：Operit 用 SnackbarHost，这里保持同一交互。
    LaunchedEffect(pluginNotice) {
        val text = pluginNotice ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        onDismissPluginNotice()
    }
    LaunchedEffect(skillNotice) {
        val text = skillNotice ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        onDismissSkillNotice()
    }
    // 「包」tab 的数据来自 assets（32 个 Operit 自带包），第一次进 tab 时扫描一次。
    LaunchedEffect(tab) {
        if (tab == PackageTab.PACKAGES && operitPackages.isEmpty()) onLoadOperitPackages()
    }

    val search = query.trim()
    val filteredPlugins = remember(plugins, search) {
        if (search.isEmpty()) {
            plugins
        } else {
            plugins.filter { record ->
                record.name.contains(search, true) ||
                    record.description.contains(search, true) ||
                    record.id.contains(search, true) ||
                    record.version.contains(search, true) ||
                    record.tools.any { it.contains(search, true) }
            }
        }
    }
    val filteredSkills = remember(skills, search) {
        if (search.isEmpty()) {
            skills
        } else {
            skills.filter { skill ->
                skill.name.contains(search, true) ||
                    skill.description.contains(search, true) ||
                    skill.source.contains(search, true) ||
                    skill.path.contains(search, true)
            }
        }
    }
    val filteredMcp = remember(mcpServers, search) {
        if (search.isEmpty()) {
            mcpServers
        } else {
            mcpServers.filter { server ->
                server.name.contains(search, true) ||
                    server.summary.contains(search, true) ||
                    server.type.contains(search, true)
            }
        }
    }
    val filteredOperitPackages = remember(operitPackages, search) {
        if (search.isEmpty()) {
            operitPackages
        } else {
            operitPackages.filter { pkg ->
                pkg.title.contains(search, true) ||
                    pkg.description.contains(search, true) ||
                    pkg.pluginId.contains(search, true) ||
                    pkg.category.contains(search, true) ||
                    pkg.tools.any { it.contains(search, true) }
            }
        }
    }

    fun launchImport() {
        when (tab) {
            PackageTab.PLUGINS -> runCatching { pluginPicker.launch(arrayOf("*/*")) }
            PackageTab.PACKAGES -> Unit
            PackageTab.SKILLS -> runCatching { skillPicker.launch(arrayOf("*/*")) }
            PackageTab.MCP ->
                runCatching { mcpPicker.launch(arrayOf("application/json", "text/plain", "*/*")) }
        }
    }

    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize()) {
            // ---- 顶栏：返回 / 标题 / 搜索 / 刷新 ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.surface)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable { onBack() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.ArrowBack, "返回", tint = c.text, modifier = Modifier.size(23.dp))
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "包管理",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = c.text,
                    )
                    Text(
                        "插件 ${plugins.size} · 包 ${operitPackages.size} · 技能 ${skills.size} · MCP ${mcpServers.size}" +
                            if (!skillsComplete) " · 技能发现不完整" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.faint,
                    )
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable {
                        searchOpen = !searchOpen
                        if (!searchOpen) query = ""
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                        if (searchOpen) "关闭搜索" else "搜索",
                        tint = if (searchOpen) c.brand else c.sub,
                        modifier = Modifier.size(21.dp),
                    )
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable {
                        onRefreshPlugins()
                        onRefreshSkills()
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Refresh, "刷新", tint = c.sub, modifier = Modifier.size(21.dp))
                }
            }

            // ---- Tab（Operit：插件 / 包 / 技能 / MCP，这里去掉没有数据层的「包」）----
            TabRow(
                selectedTabIndex = tab.ordinal,
                containerColor = c.surface,
                contentColor = c.brand,
                divider = { HorizontalDivider(color = c.line2) },
            ) {
                PackageTab.entries.forEach { item ->
                    val selected = tab == item
                    Tab(
                        selected = selected,
                        onClick = {
                            tab = item
                            query = ""
                        },
                        modifier = Modifier.height(48.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                item.icon,
                                null,
                                modifier = Modifier.size(16.dp),
                                tint = if (selected) c.brand else c.sub,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                item.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) c.brand else c.sub,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            if (searchOpen) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = {
                        Text(
                            when (tab) {
                                PackageTab.PLUGINS -> "搜索插件"
                                PackageTab.PACKAGES -> "搜索内置包"
                                PackageTab.SKILLS -> "搜索技能"
                                PackageTab.MCP -> "搜索 MCP 服务器"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = c.faint,
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).clickable { query = "" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Close, "清空", tint = c.faint, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                )
            }

            // ---- 内容：LazyColumn 列表（照 Operit 的 TabContent）----
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
                ) {
                    when (tab) {
                        PackageTab.PLUGINS -> {
                            if (filteredPlugins.isEmpty()) {
                                item(key = "empty-plugins") {
                                    EmptyState(
                                        if (search.isNotEmpty()) "没有找到匹配的插件" else "还没有可用的插件",
                                        Modifier.fillParentMaxSize(),
                                    )
                                }
                            }
                            items(filteredPlugins, key = { it.id }) { record ->
                                PluginRow(
                                    record = record,
                                    injectedTools = injected[record.id].orEmpty(),
                                    onOpen = { selectedPluginId = record.id },
                                    onToggle = { on -> onTogglePlugin(record.id, on) },
                                )
                            }
                        }

                        PackageTab.PACKAGES -> {
                            if (filteredOperitPackages.isEmpty()) {
                                item(key = "empty-operit-packages") {
                                    EmptyState(
                                        if (search.isNotEmpty()) "没有找到匹配的包" else
                                            if (operitPackagesBusy) "正在读取内置包…" else "内置包读不出来（assets 缺失？）",
                                        Modifier.fillParentMaxSize(),
                                    )
                                }
                            }
                            items(filteredOperitPackages, key = { it.pluginId }) { pkg ->
                                val installed = plugins.firstOrNull { it.id == pkg.pluginId }
                                OperitPackageRow(
                                    pkg = pkg,
                                    installed = installed,
                                    busy = operitPackagesBusy || pluginsBusy,
                                    onInstall = { onInstallOperitPackage(pkg.pluginId) },
                                    onOpen = { selectedPackage = pkg },
                                    onOpenPlugin = {
                                        if (installed != null) selectedPluginId = installed.id
                                    },
                                )
                            }
                        }

                        PackageTab.SKILLS -> {
                            if (filteredSkills.isEmpty()) {                                item(key = "empty-skills") {
                                    EmptyState(
                                        if (search.isNotEmpty()) "没有找到匹配的技能" else "还没有可用的技能",
                                        Modifier.fillParentMaxSize(),
                                    )
                                }
                            }
                            items(filteredSkills, key = { it.path }) { skill ->
                                SkillRow(
                                    skill = skill,
                                    homeDir = homeDir,
                                    workspaceDir = workspaceDir,
                                    onOpen = { selectedSkill = skill },
                                )
                            }
                        }

                        PackageTab.MCP -> {
                            if (filteredMcp.isEmpty()) {
                                item(key = "empty-mcp") {
                                    EmptyState(
                                        if (search.isNotEmpty()) "没有找到匹配的 MCP 服务器" else "还没有 MCP 服务器配置",
                                        Modifier.fillParentMaxSize(),
                                    )
                                }
                            }
                            items(filteredMcp, key = { it.id }) { server ->
                                McpRow(
                                    server = server,
                                    onOpen = { selectedMcp = server },
                                    onToggle = { enabled -> mcp.toggle(server.id, enabled) },
                                    onDelete = { pendingDeleteMcp = server },
                                )
                            }
                        }
                    }
                }

                Column(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    if (tab == PackageTab.SKILLS && skillsWarnings.isNotEmpty()) {
                        SmallFloatingActionButton(
                            onClick = { showSkillWarnings = true },
                            containerColor = c.warnSoft,
                            contentColor = c.warn,
                        ) {
                            Icon(Icons.Rounded.ErrorOutline, "技能发现问题", modifier = Modifier.size(20.dp))
                        }
                    }
                    if (tab != PackageTab.PACKAGES) {
                        FloatingActionButton(
                            onClick = { if (!pluginsBusy) launchImport() },
                            containerColor = c.brandSoft,
                            contentColor = c.brandBright,
                        ) {
                            if (pluginsBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = c.brand,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Rounded.Add, "导入", modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }

                SnackbarHost(
                    snackbar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 88.dp, start = 16.dp, end = 16.dp),
                )
            }
        }

        // ---- 详情弹层 ----
        plugins.firstOrNull { it.id == selectedPluginId }?.let { record ->
            PluginDetailDialog(
                record = record,
                injectedTools = injected[record.id].orEmpty(),
                onDismiss = { selectedPluginId = null },
                onReload = {
                    selectedPluginId = null
                    onReloadPlugin(record.id)
                },
                onDelete = {
                    selectedPluginId = null
                    pendingDeletePlugin = record
                },
                onGrant = { raw -> onGrantPlugin(record.id, raw) },
                onSetConfig = { text -> onSetPluginConfig(record.id, text) },
            )
        }

        selectedPackage?.let { pkg ->
            val installed = plugins.firstOrNull { it.id == pkg.pluginId }
            OperitPackageDetailDialog(
                pkg = pkg,
                installed = installed,
                busy = operitPackagesBusy || pluginsBusy,
                onDismiss = { selectedPackage = null },
                onInstall = { onInstallOperitPackage(pkg.pluginId) },
                onOpenPlugin = {
                    selectedPackage = null
                    if (installed != null) selectedPluginId = installed.id
                },
            )
        }

        selectedSkill?.let { skill ->
            SkillDetailDialog(
                skill = skill,
                homeDir = homeDir,
                workspaceDir = workspaceDir,
                onDismiss = { selectedSkill = null },
            )
        }

        selectedMcp?.let { server ->
            McpDetailDialog(
                server = server,
                onToggle = { enabled -> mcp.toggle(server.id, enabled) },
                onDelete = {
                    selectedMcp = null
                    pendingDeleteMcp = server
                },
                onDismiss = { selectedMcp = null },
            )
        }

        pendingDeletePlugin?.let { record ->
            AlertDialog(
                onDismissRequest = { pendingDeletePlugin = null },
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
                        pendingDeletePlugin = null
                        onDeletePlugin(record.id)
                    }) { Text("删除", color = c.danger) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeletePlugin = null }) { Text("取消", color = c.sub) }
                },
                containerColor = c.surface,
            )
        }

        pendingDeleteMcp?.let { server ->
            AlertDialog(
                onDismissRequest = { pendingDeleteMcp = null },
                title = { Text("删除 MCP 配置？", color = c.text) },
                text = {
                    Text(
                        "会从本地配置里删掉 ${server.name}。\n只是删配置，不会动任何进程。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.sub,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDeleteMcp = null
                        mcp.remove(server.id)
                    }) { Text("删除", color = c.danger) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteMcp = null }) { Text("取消", color = c.sub) }
                },
                containerColor = c.surface,
            )
        }

        if (showSkillWarnings) {
            AlertDialog(
                onDismissRequest = { showSkillWarnings = false },
                title = { Text("被忽略的技能", color = c.text) },
                text = {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        skillsWarnings.forEach { warning ->
                            Text(
                                warning,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = c.warn,
                                modifier = Modifier.padding(vertical = 3.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSkillWarnings = false }) { Text("关闭", color = c.brand) }
                },
                containerColor = c.surface,
            )
        }
    }
}

/** Operit `PackageTab` 的四项：插件 / 包（ToolPkg 内置包）/ 技能 / MCP。 */
private enum class PackageTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    PLUGINS("插件", Icons.Rounded.Apps),
    PACKAGES("包", Icons.Rounded.List),
    SKILLS("技能", Icons.Rounded.Build),
    MCP("MCP", Icons.Rounded.Cloud),
}

/** Operit `EmptyState`：Info 48dp + bodyLarge 一句话。 */
@Composable
private fun EmptyState(message: String, modifier: Modifier = Modifier) {
    val c = DshTheme2.colors
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.Info,
                null,
                modifier = Modifier.size(48.dp),
                tint = c.brand,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = c.sub,
            )
        }
    }
}

/** Operit `PluginTabContent` 的行：图标 + 名称/描述/数量 + Switch。 */
@Composable
private fun PluginRow(
    record: PluginRecord,
    injectedTools: Set<String>,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val c = DshTheme2.colors
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = c.surface,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Extension,
                    null,
                    modifier = Modifier.size(22.dp),
                    tint = if (record.error != null) c.danger else c.brand,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    record.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    record.description.ifBlank { "没有描述" },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(record.version)
                        append(" · ")
                        append("${record.tools.size} 个工具")
                        if (injectedTools.isNotEmpty()) append(" · 已接入 ${injectedTools.size}")
                        if (record.error != null) append(" · 试运行报错")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (record.error != null) c.danger else c.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = record.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.size(width = 32.dp, height = 20.dp),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = c.brand,
                    checkedTrackColor = c.brandSoft,
                    uncheckedThumbColor = c.faint,
                    uncheckedTrackColor = c.surfaceAlt,
                ),
            )
        }
    }
}

/**
 * 内置 Operit 包的行（M67）：一个包 = 一个待安装的插件。
 * 右侧：未安装 →「安装」按钮；已安装 → 「已安装」并可点开插件详情（授权入口在那里）。
 */
@Composable
private fun OperitPackageRow(
    pkg: OperitBundledPackage,
    installed: PluginRecord?,
    busy: Boolean,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    onOpenPlugin: () -> Unit,
) {
    val c = DshTheme2.colors
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = c.surface,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.List,
                    null,
                    modifier = Modifier.size(21.dp),
                    tint = when (pkg.status) {
                        OperitStatus.READY -> c.brand
                        else -> c.warn
                    },
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    pkg.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    pkg.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.sub,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(pkg.category)
                        append(" · ")
                        append("${pkg.tools.size} 个工具")
                        append(" · ")
                        append(pkg.permissionLabel)
                        append(" · ")
                        append(pkg.status.label)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (pkg.status) {
                        OperitStatus.READY -> c.faint
                        else -> c.warn
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (pkg.notes.isNotEmpty()) {
                    Text(
                        pkg.notes.first(),
                        style = MaterialTheme.typography.labelSmall,
                        color = c.warn,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (installed != null) {
                TextButton(onClick = onOpenPlugin) {
                    Text("已安装", color = c.brand, style = MaterialTheme.typography.labelMedium)
                }
            } else {
                TextButton(onClick = onInstall, enabled = !busy) {
                    Text(
                        if (busy) "安装中…" else "安装",
                        color = if (busy) c.faint else c.brand,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/**
 * 内置包详情（M76）：未安装的包也能点开——列表里只截 3 行介绍，完整介绍、工具清单、
 * 权限、兼容状态与安装入口都在这里；已安装的给「插件详情」跳转。
 */
@Composable
private fun OperitPackageDetailDialog(
    pkg: OperitBundledPackage,
    installed: PluginRecord?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    onOpenPlugin: () -> Unit,
) {
    val c = DshTheme2.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pkg.title, color = c.text) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "${pkg.category} · ${pkg.tools.size} 个工具 · ${pkg.permissionLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.faint,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (installed != null) "已安装 · ${installed.version} · ${pkg.status.label}" else pkg.status.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (installed != null) c.brand else if (pkg.status == OperitStatus.READY) c.sub else c.warn,
                )
                Spacer(Modifier.height(12.dp))
                Text("介绍", style = MaterialTheme.typography.labelSmall, color = c.faint)
                Spacer(Modifier.height(4.dp))
                Text(pkg.description, style = MaterialTheme.typography.bodyMedium, color = c.text)
                if (pkg.notes.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    pkg.notes.forEach { note ->
                        Text(
                            "· $note",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.warn,
                        )
                    }
                }
                if (pkg.tools.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("工具（${pkg.tools.size}）", style = MaterialTheme.typography.labelSmall, color = c.faint)
                    Spacer(Modifier.height(4.dp))
                    pkg.tools.forEach { tool ->
                        Text("· $tool", style = MaterialTheme.typography.bodySmall, color = c.sub)
                    }
                }
                if (pkg.permissions.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("权限", style = MaterialTheme.typography.labelSmall, color = c.faint)
                    Spacer(Modifier.height(4.dp))
                    Text(pkg.permissions.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.sub)
                }
                Spacer(Modifier.height(12.dp))
                Text("插件 id", style = MaterialTheme.typography.labelSmall, color = c.faint)
                Text(pkg.pluginId, style = MaterialTheme.typography.bodySmall, color = c.sub)
            }
        },
        confirmButton = {
            if (installed == null) {
                TextButton(onClick = onInstall, enabled = !busy) {
                    Text(if (busy) "安装中…" else "安装", color = if (busy) c.faint else c.brand)
                }
            } else {
                TextButton(onClick = onOpenPlugin) {
                    Text("插件详情", color = c.brand)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = c.sub) }
        },
        containerColor = c.surface,
    )
}

/** Operit `SkillConfigScreen` 的行：图标 + 名称/描述 + 路径；点开详情。 */
@Composable
private fun SkillRow(    skill: SkillSummary,
    homeDir: String,
    workspaceDir: String,
    onOpen: () -> Unit,
) {
    val c = DshTheme2.colors
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = c.surface,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Build, null, modifier = Modifier.size(21.dp), tint = c.brand)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        skill.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (!skill.modelInvocable) "仅用户" else if (!skill.userInvocable) "仅模型" else skill.source,
                        style = MaterialTheme.typography.labelSmall,
                        color = c.faint,
                        maxLines = 1,
                    )
                }
                Text(
                    skill.description.ifBlank { "没有描述" },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.sub,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    shortPath(skill.path, homeDir, workspaceDir),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = c.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** MCP 配置行：图标 + 名称/摘要 + 启停 + 删除（点行看原始 JSON）。 */
@Composable
private fun McpRow(
    server: McpServer,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val c = DshTheme2.colors
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = c.surface,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Dns, null, modifier = Modifier.size(21.dp), tint = c.brand)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    server.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    server.summary.ifBlank { "没有配置摘要" },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    server.type,
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                )
            }
            Switch(
                checked = server.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.size(width = 32.dp, height = 20.dp),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = c.brand,
                    checkedTrackColor = c.brandSoft,
                    uncheckedThumbColor = c.faint,
                    uncheckedTrackColor = c.surfaceAlt,
                ),
            )
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier.size(30.dp).clip(CircleShape).clickable { onDelete() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.DeleteOutline, "删除", tint = c.danger, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** 技能详情：字段式弹层（Operit `SkillDetailDialog` 的精简版：本地只能只读展示）。 */
@Composable
private fun SkillDetailDialog(
    skill: SkillSummary,
    homeDir: String,
    workspaceDir: String,
    onDismiss: () -> Unit,
) {
    val c = DshTheme2.colors
    var content by remember(skill.path) { mutableStateOf<String?>(null) }
    LaunchedEffect(skill.path) {
        content = withContext(Dispatchers.IO) {
            runCatching { File(skill.path).readText().take(40_000) }
                .getOrElse { "读取失败：${it.message}" }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(skill.name, color = c.text) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DetailField("描述", skill.description.ifBlank { "没有描述" })
                DetailField("来源", "${skill.source} · rank ${skill.rank}")
                DetailField("调用方式", invocationText(skill))
                DetailField("文件", shortPath(skill.path, homeDir, workspaceDir))
                if (skill.whenToUse != null) {
                    DetailField("何时使用", skill.whenToUse.orEmpty())
                }
                Text(
                    "SKILL.md",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.sub,
                    fontWeight = FontWeight.Medium,
                )
                Surface(shape = RoundedCornerShape(10.dp), color = c.surfaceAlt) {
                    Text(
                        content ?: "读取中…",
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = c.text,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = c.brand) }
        },
        containerColor = c.surface,
    )
}

/** MCP 详情：原始 JSON + 启停/删除（Operit `MCPServerDetailsDialog` 的配置只读版）。 */
@Composable
private fun McpDetailDialog(
    server: McpServer,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = DshTheme2.colors
    val pretty = remember(server.raw) {
        runCatching { JSONObject(server.raw).toString(2) }.getOrDefault(server.raw)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(server.name, color = c.text) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DetailField("类型", server.type)
                DetailField("摘要", server.summary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "启用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.text,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = server.enabled,
                        onCheckedChange = onToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = c.brand,
                            checkedTrackColor = c.brandSoft,
                            uncheckedThumbColor = c.faint,
                            uncheckedTrackColor = c.surfaceAlt,
                        ),
                    )
                }
                Text(
                    "配置 JSON",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.sub,
                    fontWeight = FontWeight.Medium,
                )
                Surface(shape = RoundedCornerShape(10.dp), color = c.surfaceAlt) {
                    Text(
                        pretty,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = c.text,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDelete) { Text("删除", color = c.danger) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = c.sub) }
        },
        containerColor = c.surface,
    )
}

@Composable
private fun DetailField(title: String, value: String) {
    val c = DshTheme2.colors
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = c.sub, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = c.text,
        )
    }
}

private fun invocationText(skill: SkillSummary): String = when {
    skill.modelInvocable && skill.userInvocable -> "模型可调用，也可用 /${skill.name} 直接调用"
    skill.modelInvocable -> "仅模型可调用（user-invocable: false）"
    skill.userInvocable -> "仅用户可调用（disable-model-invocation: true）"
    else -> "模型和用户都不可调用"
}

/** 把 App 私有目录前缀换成 `files/…` / `workspace/…`（与技能设置页一致）。 */
private fun shortPath(path: String, homeDir: String, workspaceDir: String): String = when {
    workspaceDir.isNotEmpty() && path.startsWith(workspaceDir) -> "workspace" + path.removePrefix(workspaceDir)
    homeDir.isNotEmpty() && path.startsWith(homeDir) -> "files" + path.removePrefix(homeDir)
    else -> path
}
