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
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
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
import xin.dshaa.nativeapp.ui.plugins.PluginsScreen
import xin.dshaa.nativeapp.ui.screen.VirtualScreenPage
import xin.dshaa.nativeapp.ui.sessions.SessionsScreen
import xin.dshaa.nativeapp.ui.settings.AssistantScreen
import xin.dshaa.nativeapp.ui.settings.BackupScreen
import xin.dshaa.nativeapp.ui.settings.BehaviorScreen
import xin.dshaa.nativeapp.ui.settings.DataMigrationScreen
import xin.dshaa.nativeapp.ui.settings.ModelConfigScreen
import xin.dshaa.nativeapp.ui.settings.SettingsScreen
import xin.dshaa.nativeapp.ui.settings.SkillsScreen
import xin.dshaa.nativeapp.ui.settings.WorkspacesScreen
import xin.dshaa.nativeapp.ui.shell.DshDrawer
import xin.dshaa.nativeapp.ui.terminal.TerminalPage
import xin.dshaa.ui.design.theme.DshTheme2

/** 一级页面（RikkaHub 外壳下都从设置 →「功能与工具」进）。 */
enum class Screen(val label: String, val icon: ImageVector) {
    Sessions("会话", Icons.Rounded.ChatBubbleOutline),
    Agents("子代理", Icons.Rounded.Person),
    Jobs("任务", Icons.Rounded.PlayArrow),
    Terminal("终端", Icons.Rounded.Terminal),
    VirtualScreen("虚拟屏", Icons.Rounded.PlayArrow),
    Accessibility("无障碍", Icons.Rounded.CheckCircle),
    Plugins("插件", Icons.Rounded.Extension),
    Settings("设置", Icons.Rounded.Settings),
}

/** 二级页（设置里的独立页面，Operit 用独立路由）。 */
enum class SubScreen { ModelConfig, Assistant, Behavior, Backup, DataMigration, Skills, Workspaces, Permissions }

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
            screenName != null -> screenName = null
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

    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            DshDrawer(
                currentSessionId = vm.chat?.sessionId,
                sessions = vm.sessions,
                workspace = vm.currentWorkspace,
                workspaces = vm.workspaces,
                assistantName = vm.settings.assistantName,
                assistantAvatar = vm.settings.assistantAvatar,
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
        },
    ) {
        Box(Modifier.fillMaxSize().background(c.background)) {
            when (screenName) {
                null -> {
                    val current = vm.chat
                    if (current != null) {
                        ChatScreen(
                            state = current,
                            assistantName = vm.settings.assistantName,
                            assistantAvatar = vm.settings.assistantAvatar,
                            expandThinking = vm.settings.expandThinking,
                            imagePath = { id -> vm.imagePathFor(id) },
                            onOpenDrawer = openDrawer,
                            onNewChat = { vm.newChat() },
                            onAttachImage = { uri -> vm.attachImage(uri) },
                            onAttachPhoto = { file -> vm.attachPhoto(file) },
                            onCaptureScreenshot = { vm.attachScreenshot() },
                            onRemovePendingImage = { id -> vm.removePendingImage(id) },
                            onAttachFile = { uri -> vm.attachFile(uri) },
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
                            recentModels = vm.settings.recentModels,
                            onSwitchModel = { vm.switchModel(it) },
                            approvalMode = vm.settings.approvalMode,
                            onSetApprovalMode = { vm.settings.setApprovalMode(it) },
                        )
                    } else {
                        // 一帧空白：LaunchedEffect 马上会建草稿
                        Spacer(Modifier.fillMaxSize())
                    }
                }

                Screen.Sessions.name -> SessionsScreen(
                    sessions = vm.sessions,
                    assistantName = vm.settings.assistantName,
                    assistantAvatar = vm.settings.assistantAvatar,
                    badgeFor = { id -> vm.badgeFor(id) },
                    onOpenDrawer = openDrawer,
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
                    onRefresh = { vm.refreshSubagents() },
                    onOpen = { vm.openSubagentSession(it.id) },
                    onStop = { vm.stopSubagent(it.id) },
                )

                Screen.Jobs.name -> JobsScreen(
                    jobs = vm.jobs,
                    onOpenDrawer = openDrawer,
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
                    onRefresh = { vm.refreshPlugins() },
                    onImport = { uri -> vm.importPlugin(uri) },
                    onToggle = { id, enabled -> vm.setPluginEnabled(id, enabled) },
                    onReload = { id -> vm.reloadPlugin(id) },
                    onDelete = { id -> vm.deletePlugin(id) },
                    onGrant = { id, raw -> vm.grantPluginPermission(id, raw) },
                    onSetConfig = { id, text -> vm.savePluginConfig(id, text) },
                    onDismissNotice = { vm.dismissPluginNotice() },
                )

                Screen.Terminal.name -> TerminalPage()

                Screen.VirtualScreen.name -> VirtualScreenPage(onBack = { go(Screen.Settings) })

                Screen.Accessibility.name -> AccessibilityScreen(onBack = { go(Screen.Settings) })

                else -> {
                    when (subScreen) {
                        SubScreen.ModelConfig -> ModelConfigScreen(
                            settings = vm.settings,
                            onBack = { subScreenName = null },
                            onTestConnection = { vm.testConnection() },
                        )
                        SubScreen.Assistant -> AssistantScreen(
                            settings = vm.settings,
                            onBack = { subScreenName = null },
                        )
                        SubScreen.Behavior -> BehaviorScreen(
                            settings = vm.settings,
                            onBack = { subScreenName = null },
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
                            onOpenModelConfig = { subScreenName = SubScreen.ModelConfig.name },
                            onOpenAssistant = { subScreenName = SubScreen.Assistant.name },
                            onOpenBehavior = { subScreenName = SubScreen.Behavior.name },
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

private fun credentialReady(settings: SettingsStore): Boolean =
    if (settings.providerId == SettingsStore.PROVIDER_DEEPSEEK_ACCOUNT) settings.accountToken.isNotBlank()
    else settings.apiKey.isNotBlank()
