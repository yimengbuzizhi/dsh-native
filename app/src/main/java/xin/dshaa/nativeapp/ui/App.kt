package xin.dshaa.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import xin.dshaa.nativeapp.BuildConfig
import xin.dshaa.nativeapp.chat.AppViewModel
import xin.dshaa.nativeapp.data.SettingsStore
import xin.dshaa.nativeapp.ui.agents.SubagentsScreen
import xin.dshaa.nativeapp.ui.chat.ChatScreen
import xin.dshaa.nativeapp.ui.device.AccessibilityScreen
import xin.dshaa.nativeapp.ui.jobs.JobsScreen
import xin.dshaa.nativeapp.memory.MemoryScreen
import xin.dshaa.nativeapp.ui.packages.PackagesScreen
import xin.dshaa.nativeapp.ui.plugins.PluginsScreen
import xin.dshaa.nativeapp.ui.screen.VirtualScreenPage
import xin.dshaa.nativeapp.ui.sessions.SessionsScreen
import xin.dshaa.nativeapp.ui.settings.AssistantScreen
import xin.dshaa.nativeapp.ui.settings.BackupScreen
import xin.dshaa.nativeapp.ui.settings.BehaviorScreen
import xin.dshaa.nativeapp.ui.settings.DataMigrationScreen
import xin.dshaa.nativeapp.ui.settings.ModelConfigScreen
import xin.dshaa.nativeapp.ui.settings.PrivacyScreen
import xin.dshaa.nativeapp.ui.settings.SettingsScreen
import xin.dshaa.nativeapp.ui.settings.SkillsScreen
import xin.dshaa.nativeapp.ui.settings.WorkspacesScreen
import xin.dshaa.nativeapp.ui.workspace.WorkspaceScreen
import xin.dshaa.nativeapp.ui.shell.DshDrawer
import xin.dshaa.nativeapp.ui.terminal.TerminalPage
import xin.dshaa.ui.design.theme.DshTheme2

/** 一级页面（RikkaHub 外壳下都从设置 →「功能与工具」进）。 */
enum class Screen(val label: String, val icon: ImageVector) {
    Sessions("会话", Icons.Rounded.ChatBubbleOutline),
    Workspace("工作区", Icons.Rounded.Folder),
    Agents("子代理", Icons.Rounded.Person),
    Jobs("任务", Icons.Rounded.PlayArrow),
    Terminal("终端", Icons.Rounded.Terminal),
    VirtualScreen("虚拟屏", Icons.Rounded.PlayArrow),
    Accessibility("无障碍", Icons.Rounded.CheckCircle),
    Plugins("插件", Icons.Rounded.Extension),
    Packages("包管理", Icons.Rounded.Widgets),
    Memory("记忆库", Icons.Rounded.Memory),
    Settings("设置", Icons.Rounded.Settings),
}

/** 二级页（设置里的独立页面，Operit 用独立路由）。 */
enum class SubScreen { ModelConfig, Assistant, Behavior, Backup, DataMigration, Skills, Workspaces, Permissions, Privacy }

/**
 * 应用外壳（2026-11-07 第二次重做，仿 RikkaHub）：
 * - **落地页 = 聊天**（`screenName == null`），抽屉从聊天页左上角三条杠打开；
 * - 抽屉里是「当前工作区的会话列表」+ 工作区切换器 + 功能/设置/关于；
 * - 其他页面仍然存在（Operit 的功能全部保留），从抽屉「功能」面板进入；
 * - 系统返回：抽屉 → 关抽屉；二级页 → 回设置；其他一级页 → 回聊天；聊天页 → 退出 App。
 *
 * 为什么改成聊天落地：用户 2026-11-07 21:40 明确「打开直接落在聊天页（不是工作台）」，
 * 并指出 RikkaHub 是这么做的；工作台仍保留，只是不再是入口。
 */
@Composable
fun DshApp(vm: AppViewModel = viewModel()) {
    val dark = when (vm.settings.themeMode) {
        "light" -> false
        "dark" -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    xin.dshaa.ui.design.theme.DshTheme(darkTheme = dark, accentId = vm.settings.accentId) {
        // 系统栏图标跟着主题走（enableEdgeToEdge 只按系统深色模式取样式，切浅色后图标会看不见）
        val view = androidx.compose.ui.platform.LocalView.current
        val context = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.runtime.LaunchedEffect(dark) {
            val activity = context as? android.app.Activity ?: return@LaunchedEffect
            androidx.core.view.WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        // 首次启动：使用指导（里面有权限页）；完成后才进聊天外壳
        if (!vm.onboardingDone) {
            xin.dshaa.nativeapp.ui.onboarding.OnboardingScreen(onDone = { vm.completeOnboarding() })
        } else {
            DshAppContent(vm)
        }
    }
}

@Composable
private fun DshAppContent(vm: AppViewModel) {
    val c = DshTheme2.colors
    // null = 聊天（落地页）；非空 = 一级页名字
    var screenName by rememberSaveable { mutableStateOf<String?>(null) }
    var subScreenName by rememberSaveable { mutableStateOf<String?>(null) }
    var showAbout by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val subScreen = subScreenName?.let { SubScreen.valueOf(it) }
    val chat = vm.chat

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }
    val go: (Screen) -> Unit = { screen ->
        screenName = screen.name
        subScreenName = null
    }
    /** 从功能页直接回设置（M74；这些页原来只能开抽屉，用户 23:00 第 1 条）。 */
    val backToSettings: () -> Unit = {
        screenName = Screen.Settings.name
        subScreenName = null
    }
    // 宽屏（平板 / 折叠屏展开）侧边栏常驻：600dp 是 Material 的 compact/expanded 断点
    val wideLayout = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    // 设置页的滚动位置提到这里：进出二级页不再被重置到顶部（用户 23:00 第 3 条）
    val settingsScroll = androidx.compose.foundation.rememberScrollState()

    // 落地页必须是聊天：冷启动 / 删除当前会话后回来，都补一个空草稿（不建会话文件）。
    androidx.compose.runtime.LaunchedEffect(screenName, vm.chat == null) {
        if (screenName == null && vm.chat == null) vm.newChat()
    }

    // 系统返回键：抽屉 → 关抽屉；二级页 → 回设置；一级页 → 回聊天；子代理回放 → 回父会话；
    // 聊天页本身不拦截（交给系统退出 App，RikkaHub 同款语义）。
    androidx.activity.compose.BackHandler(
        enabled = drawerState.isOpen || subScreen != null || screenName != null || vm.canGoBackFromChat,
    ) {
        when {
            drawerState.isOpen -> closeDrawer()
            subScreen != null -> subScreenName = null
            // 功能页回到设置（用户 23:00 第 1 条：此前直接回聊天，等于丢掉了来路）
            screenName == Screen.Workspace.name -> screenName = null
            screenName == Screen.Settings.name -> screenName = null
            screenName != null -> backToSettings()
            else -> vm.closeChat()
        }
    }

    // 调试通道：`--es dsh_import_zip` 直接把旧版导入页顶到前面（真机无 SAF 也能验收）
    androidx.compose.runtime.LaunchedEffect(vm.importRequest) {
        if (vm.importRequest != null) {
            screenName = Screen.Settings.name
            subScreenName = SubScreen.DataMigration.name
        }
    }

    // 调试通道：`--es dsh_open_screen agents` 启动即落到指定页面（一级页或二级页都认）
    androidx.compose.runtime.LaunchedEffect(vm.debugScreenRequest) {
        val requested = vm.debugScreenRequest ?: return@LaunchedEffect
        val screenMatch = Screen.entries
            .firstOrNull { it.name.equals(requested, ignoreCase = true) || it.label == requested }
        if (screenMatch != null) {
            go(screenMatch)
        } else {
            val subMatch = SubScreen.entries.firstOrNull {
                it.name.equals(requested, ignoreCase = true) || it.name.equals("${requested}Screen", ignoreCase = true)
            }
            if (subMatch != null) {
                screenName = Screen.Settings.name
                subScreenName = subMatch.name
            }
        }
        vm.consumeDebugScreen()
    }

    // 抽屉内容：窄屏是滑出的 Modal 抽屉；宽屏（平板/折叠屏展开，>=600dp）常驻在左侧（M74，用户 23:00 第 5 条）
    val drawerContent: @Composable () -> Unit = {
            DshDrawer(
                currentSessionId = vm.chat?.sessionId,
                sessions = vm.sessions,
                workspace = vm.currentWorkspace,
                workspaces = vm.workspaces,
                assistantName = vm.settings.assistantName,
                assistantAvatar = vm.settings.assistantAvatar,
                assistantProfiles = vm.settings.assistantProfiles,
                activeAssistantId = vm.settings.activeAssistantId,
                onSwitchAssistant = { id -> vm.settings.setActiveAssistant(id) },
                onManageAssistants = {
                    closeDrawer()
                    screenName = Screen.Settings.name
                    subScreenName = SubScreen.Assistant.name
                },
                model = vm.settings.model,
                toolCount = vm.toolCount,
                badgeFor = { id -> vm.badgeFor(id) },
                onOpenSession = { summary ->
                    closeDrawer()
                    screenName = null
                    subScreenName = null
                    vm.openSession(summary)
                },
                onNewChat = {
                    closeDrawer()
                    screenName = null
                    subScreenName = null
                    vm.newChat()
                },
                onSwitchWorkspace = { path ->
                    closeDrawer()
                    screenName = null
                    subScreenName = null
                    vm.switchWorkspace(path)
                },
                onCreateWorkspace = { name, path ->
                    closeDrawer()
                    screenName = null
                    subScreenName = null
                    vm.createWorkspace(name, path)
                },
                onManageWorkspaces = {
                    closeDrawer()
                    screenName = Screen.Settings.name
                    subScreenName = SubScreen.Workspaces.name
                },
                onSelectScreen = { screen ->
                    closeDrawer()
                    go(screen)
                },
                onAbout = {
                    closeDrawer()
                    showAbout = true
                },
                onRenameSession = { id, title -> vm.renameSession(id, title) },
                onDeleteSession = { id -> vm.deleteSession(id) },
            )
    }

    // 屏幕主体：宽屏时和常驻抽屉并排，窄屏时铺满
    val body: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(c.background)) {
            when (screenName) {
                null -> {
                    val current = vm.chat
                    if (current != null) {
                        ChatScreen(
                            state = current,
                            assistantName = vm.settings.assistantName,
                            assistantAvatar = vm.settings.assistantAvatar,
                            assistantOpening = vm.settings.assistantOpening,
                            expandThinking = vm.settings.expandThinking,
                            imagePath = { id -> vm.imagePathFor(id) },
                            onOpenDrawer = openDrawer,
                            menuVisible = !wideLayout,
                            onNewChat = { vm.newChat() },
                            onOpenWorkspace = { screenName = Screen.Workspace.name },
                            onAttachImage = { uri -> vm.attachImage(uri) },
                            onAttachPhoto = { file -> vm.attachPhoto(file) },
                            onCaptureScreenshot = { vm.attachScreenshot() },
                            onRemovePendingImage = { id -> vm.removePendingImage(id) },
                            onAttachFile = { uri -> vm.attachFile(uri) },
                            plugins = vm.pluginsUi,
                            skills = vm.skillsUi,
                            onEnablePlugin = { id -> vm.setPluginEnabled(id, true) },
                            onRemovePendingFile = { name -> vm.removePendingFile(name) },
                            onSubmit = { vm.submit(it) },
                            onStop = { vm.stop() },
                            onApproval = { allow, always -> vm.resolveApproval(allow, always) },
                            onAnswer = { answers -> vm.resolveQuestions(answers) },
                            planModeActive = current.planActive,
                            planModePending = current.planPending,
                            onSetPlanMode = { vm.setPlanMode(it) },
                            schedules = { vm.currentSchedules() },
                            onDeleteSchedule = { id -> vm.deleteSchedule(id) },
                            skillChoices = vm.skillsUi.filter { it.userInvocable },
                            onRefreshSkills = { vm.refreshSkills() },
                            fileCandidates = { query -> vm.fileCandidates(query) },
                            onDismissCommandNotice = { vm.dismissCommandNotice() },
                            onPauseGoal = { vm.pauseGoal() },
                            onResumeGoal = { vm.resumeGoal() },
                            onEditGoal = { objective, cap -> vm.editGoal(objective, cap) },
                            onClearGoal = { vm.clearGoal() },
                            onOpenJobs = {
                                // 不关当前聊天：任务页返回时还能继续刚才的对话（旧实现会丢会话视图）
                                screenName = Screen.Jobs.name
                                vm.refreshJobs()
                            },
                            onOpenSubagent = { id -> vm.openSubagentSession(id) },
                            onBranchMessage = { message -> vm.branchFromMessage(message) },
                            onRegenerateMessage = { message -> vm.regenerateFromMessage(message) },
                            visionEnabled = vm.settings.visionEnabled,
                            onToggleVision = { vm.settings.setVisionEnabled(it) },
                            webSearchEnabled = vm.settings.webSearchEnabled,
                            onToggleWebSearch = { vm.settings.setWebSearchEnabled(it) },
                            onOpenModelSettings = {
                                screenName = Screen.Settings.name
                                subScreenName = SubScreen.ModelConfig.name
                            },
                            enabledModels = vm.settings.enabledModels,
                            onSwitchModel = { vm.switchModel(it) },
                            approvalMode = vm.settings.approvalMode,
                            onSetApprovalMode = { vm.settings.setApprovalMode(it) },
                            reasoningEffort = vm.settings.reasoningEffort,
                            onSetReasoningEffort = { vm.settings.setReasoningEffort(it) },
                            maxMode = vm.settings.maxMode,
                            onSetMaxMode = { vm.settings.setMaxMode(it) },
                            onSetExpandThinking = { vm.settings.setExpandThinking(it) },
                            timeContext = vm.settings.timeContext,
                            onSetTimeContext = { vm.settings.setTimeContext(it) },
                            memorySpaceName = "Default",
                            onOpenMemoryLibrary = { screenName = Screen.Memory.name },
                            onOpenPlugins = { screenName = Screen.Plugins.name },
                            // M78 并行：其它会话在等审批/回答时，顶部横幅一键切过去
                            waitingElsewhere = vm.waitingElsewhere(),
                            onFocusSession = { id -> vm.focusSession(id) },
                        )
                    } else {
                        // 一帧空白：LaunchedEffect 马上会建草稿
                        Spacer(Modifier.fillMaxSize())
                    }
                }

                Screen.Workspace.name -> WorkspaceScreen(
                    root = vm.workspace,
                    onBack = { screenName = null },
                )

                Screen.Sessions.name -> SessionsScreen(
                    sessions = vm.sessions,
                    assistantName = vm.settings.assistantName,
                    assistantAvatar = vm.settings.assistantAvatar,
                    badgeFor = { id -> vm.badgeFor(id) },
                    onOpenDrawer = openDrawer,
                    onBack = backToSettings,
                    onOpen = { summary ->
                        screenName = null
                        vm.openSession(summary)
                    },
                    onNew = {
                        screenName = null
                        vm.newChat()
                    },
                    onDelete = { vm.deleteSession(it.id) },
                    onRename = { summary, title -> vm.renameSession(summary.id, title) },
                )

                Screen.Agents.name -> SubagentsScreen(
                    subagents = vm.subagents,
                    maxDepth = vm.agentMaxDepth,
                    loading = vm.subagentsLoading,
                    onOpenDrawer = openDrawer,
                    onBack = backToSettings,
                    onRefresh = { vm.refreshSubagents() },
                    onOpen = { vm.openSubagentSession(it.id) },
                    onStop = { vm.stopSubagent(it.id) },
                )

                Screen.Jobs.name -> JobsScreen(
                    jobs = vm.jobs,
                    onOpenDrawer = openDrawer,
                    onBack = backToSettings,
                    onRefresh = { vm.refreshJobs() },
                    onStop = { vm.stopJob(it) },
                    onPeek = { vm.peekJob(it) },
                )

                Screen.Plugins.name -> PluginsScreen(
                    plugins = vm.pluginsUi,
                    injected = vm.pluginInjected,
                    busy = vm.pluginsBusy,
                    notice = vm.pluginNotice,
                    workspacePath = vm.workspacePath,
                    initialDetailId = vm.debugPluginDetailId,
                    onOpenDrawer = openDrawer,
                    onBack = backToSettings,
                    onRefresh = { vm.refreshPlugins() },
                    onImport = { uri -> vm.importPlugin(uri) },
                    onToggle = { id, enabled -> vm.setPluginEnabled(id, enabled) },
                    onReload = { id -> vm.reloadPlugin(id) },
                    onDelete = { id -> vm.deletePlugin(id) },
                    onGrant = { id, raw -> vm.grantPluginPermission(id, raw) },
                    onSetConfig = { id, text -> vm.savePluginConfig(id, text) },
                    onDismissNotice = { vm.dismissPluginNotice() },
                    onOpenPluginWindow = { record -> vm.openClientPluginWindow(record) },
                )

                Screen.Terminal.name -> TerminalPage(onBack = backToSettings)

                Screen.Packages.name -> PackagesScreen(
                    plugins = vm.pluginsUi,
                    injected = vm.pluginInjected,
                    pluginsBusy = vm.pluginsBusy,
                    pluginNotice = vm.pluginNotice,
                    operitPackages = vm.operitPackagesUi,
                    operitPackagesBusy = vm.operitPackagesBusy,
                    skills = vm.skillsUi,
                    skillsComplete = vm.skillsComplete,
                    skillsWarnings = vm.skillsWarnings,
                    skillNotice = vm.skillNotice,
                    homeDir = vm.filesDirPath,
                    workspaceDir = vm.workspacePath,
                    onBack = { go(Screen.Settings) },
                    onRefreshPlugins = { vm.refreshPlugins() },
                    onImportPlugin = { uri -> vm.importPlugin(uri) },
                    onTogglePlugin = { id, enabled -> vm.setPluginEnabled(id, enabled) },
                    onReloadPlugin = { id -> vm.reloadPlugin(id) },
                    onDeletePlugin = { id -> vm.deletePlugin(id) },
                    onGrantPlugin = { id, raw -> vm.grantPluginPermission(id, raw) },
                    onSetPluginConfig = { id, text -> vm.savePluginConfig(id, text) },
                    onDismissPluginNotice = { vm.dismissPluginNotice() },
                    onLoadOperitPackages = { vm.loadOperitPackages() },
                    onInstallOperitPackage = { id -> vm.installOperitPackage(id) },
                    onRefreshSkills = { vm.refreshSkills() },
                    onImportSkill = { uri -> vm.importSkill(uri) },
                    onDismissSkillNotice = { vm.dismissSkillNotice() },
                )

                Screen.VirtualScreen.name -> VirtualScreenPage(onBack = { go(Screen.Settings) })

                Screen.Memory.name -> MemoryScreen(onBack = { go(Screen.Settings) })

                Screen.Accessibility.name -> AccessibilityScreen(onBack = { go(Screen.Settings) })

                else -> {
                    when (subScreen) {
                        SubScreen.ModelConfig -> ModelConfigScreen(
                            settings = vm.settings,
                            onBack = { subScreenName = null },
                            onTestConnection = { vm.testConnection() },
                            onFetchModels = { vm.fetchModels() },
                        )
                        SubScreen.Assistant -> AssistantScreen(
                            settings = vm.settings,
                            plugins = vm.pluginsUi,
                            onBack = { subScreenName = null },
                        )
                        SubScreen.Behavior -> BehaviorScreen(
                            settings = vm.settings,
                            onBack = { subScreenName = null },
                        )
                        SubScreen.Privacy -> PrivacyScreen(
                            settings = vm.settings,
                            onBack = { subScreenName = null },
                            onOpenSessions = {
                                subScreenName = null
                                go(Screen.Sessions)
                            },
                        )
                        SubScreen.Backup -> BackupScreen(
                            onBack = { subScreenName = null },
                            onRestored = { vm.onRestoredFromBackup() },
                            currentWorkspace = vm.workspacePath,
                        )
                        SubScreen.DataMigration -> DataMigrationScreen(
                            sessionsRoot = vm.sessionsDir,
                            initialPath = vm.importRequest,
                            onInitialPathConsumed = { vm.consumeImportRequest() },
                            onBack = { subScreenName = null },
                            onImported = { vm.refreshSessions() },
                            onOpenSessions = {
                                subScreenName = null
                                go(Screen.Sessions)
                            },
                        )
                        SubScreen.Skills -> SkillsScreen(
                            skills = vm.skillsUi,
                            roots = vm.skillRoots,
                            complete = vm.skillsComplete,
                            warnings = vm.skillsWarnings,
                            homeDir = vm.filesDirPath,
                            workspaceDir = vm.workspacePath,
                            onRefresh = { vm.refreshSkills() },
                            onBack = { subScreenName = null },
                        )
                        SubScreen.Workspaces -> WorkspacesScreen(
                            workspaces = vm.workspaces,
                            currentPath = vm.workspacePath,
                            onBack = { subScreenName = null },
                            onSwitch = { path ->
                                vm.switchWorkspace(path)
                                screenName = null
                                subScreenName = null
                            },
                            onCreate = { name, path -> vm.createWorkspace(name, path) },
                            onRename = { id, name -> vm.renameWorkspace(id, name) },
                            onDelete = { id -> vm.deleteWorkspace(id) },
                        )
                        SubScreen.Permissions -> xin.dshaa.nativeapp.ui.permissions.PermissionsScreen(
                            onBack = { subScreenName = null },
                        )
                        null -> SettingsScreen(
                            settings = vm.settings,
                            onOpenDrawer = openDrawer,
                            scrollState = settingsScroll,
                            menuVisible = !wideLayout,
                            onOpenModelConfig = { subScreenName = SubScreen.ModelConfig.name },
                            onOpenAssistant = { subScreenName = SubScreen.Assistant.name },
                            onOpenBehavior = { subScreenName = SubScreen.Behavior.name },
                    onOpenPrivacy = { subScreenName = SubScreen.Privacy.name },
                            onOpenBackup = { subScreenName = SubScreen.Backup.name },
                            onOpenMigration = { subScreenName = SubScreen.DataMigration.name },
                            onOpenSkills = { subScreenName = SubScreen.Skills.name },
                            onOpenWorkspaces = { subScreenName = SubScreen.Workspaces.name },
                            onOpenPermissions = { subScreenName = SubScreen.Permissions.name },
                            onOpenFeature = { screen -> go(screen) },
                            currentWorkspaceName = vm.currentWorkspace.name,
                            onOpenSessions = { go(Screen.Sessions) },
                            onOpenAbout = { showAbout = true },
                        )
                    }
                }
            }
        }
    }

    if (wideLayout) {
        androidx.compose.material3.PermanentNavigationDrawer(drawerContent = drawerContent) { body() }
    } else {
        androidx.compose.material3.ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = true,
            drawerContent = drawerContent,
        ) { body() }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            containerColor = c.surface,
            title = { Text("鲸歌", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = c.text)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Kotlin + Jetpack Compose 重写，协议为 Anthropic Messages（DSH 0.2.2-rc.1）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.sub,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "界面参照 Operit（LGPL-3.0）：设置分组、模型配置页、思考折叠与角色面板。",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.sub,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "外壳参照 RikkaHub（AGPL-3.0，只学信息架构与排版，未复制代码）：聊天落地、抽屉会话列表、工作区切换。",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.sub,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("License: LGPL-3.0", style = MaterialTheme.typography.labelSmall, color = c.faint)
                }
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("好") } },
        )
    }
}
