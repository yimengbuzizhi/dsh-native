package xin.dshaa.nativeapp.ui.shell.native.pages

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import xin.dshaa.ui.shellapi.BuiltinPageIds
import xin.dshaa.ui.shellapi.ShellPageRegistry
import xin.dshaa.nativeapp.memory.MemoryScreen
import xin.dshaa.nativeapp.memory.MemoryPickerDialog
import xin.dshaa.nativeapp.memory.buildMemoryReference
import xin.dshaa.nativeapp.device.attach.AttachmentCapture
import xin.dshaa.nativeapp.device.attach.LocationCapture
import xin.dshaa.ui.shellapi.NativeShellHostUi
import xin.dshaa.nativeapp.ui.agents.SubagentsScreen
import xin.dshaa.nativeapp.ui.chat.ChatScreen
import xin.dshaa.nativeapp.ui.device.AccessibilityScreen
import xin.dshaa.nativeapp.ui.jobs.JobsScreen
import xin.dshaa.nativeapp.ui.packages.PackagesScreen
import xin.dshaa.nativeapp.ui.plugins.PluginsScreen
import xin.dshaa.nativeapp.ui.remote.RemoteDshScreen
import xin.dshaa.nativeapp.ui.screen.VirtualScreenPage
import xin.dshaa.nativeapp.ui.sessions.SessionsScreen
import xin.dshaa.ui.shellapi.NativeShellHost
import xin.dshaa.ui.shellapi.chat.NotifyAttachResult
import xin.dshaa.nativeapp.ui.settings.AboutScreen
import xin.dshaa.nativeapp.ui.settings.AssistantScreen
import xin.dshaa.nativeapp.ui.settings.BackupScreen
import xin.dshaa.nativeapp.ui.settings.BehaviorScreen
import xin.dshaa.nativeapp.ui.settings.DataMigrationScreen
import xin.dshaa.nativeapp.ui.settings.ModelConfigScreen
import xin.dshaa.nativeapp.ui.settings.PrivacyScreen
import xin.dshaa.nativeapp.ui.settings.SettingsScreen
import xin.dshaa.nativeapp.ui.settings.SkillsScreen
import xin.dshaa.nativeapp.ui.settings.WorkspacesScreen
import xin.dshaa.nativeapp.ui.terminal.TerminalPage
import xin.dshaa.nativeapp.ui.workspace.WorkspaceScreen

/**
 * M85-C2：壳内部的导航动作。
 *
 * 路由状态（当前一级页/二级页、转场方向、抽屉）在 `NativeShellContent` 里；页面只拿动作，
 * 这样每一页都不认识路由状态机——页面本来就是壳的一部分，宿主不参与。
 */
class NativeNav(
    /** 打开抽屉（各页的「菜单」按钮）。 */
    val openDrawer: () -> Unit,
    /** 回到聊天落地页（清空一级页/二级页）。 */
    val toChat: () -> Unit,
    /** 进一级页（从聊天/设置进；`go` 会记「从设置来」）。 */
    val go: (String) -> Unit,
    /** 从聊天页直接进功能页（返回时落回聊天）。 */
    val goFromChat: (String) -> Unit,
    /** 进设置二级页。 */
    val openSub: (String) -> Unit,
    /** 关二级页回设置（带转场方向）。 */
    val closeSub: () -> Unit,
    /** 一级页的返回：从聊天来的回聊天，从设置来的回设置。 */
    val backFromPage: () -> Unit,
    /** 设置页的滚动位置（进出二级页不重置）。 */
    val settingsScroll: ScrollState,
    /** 宽屏（平板/折叠屏展开）：设置页不显示菜单按钮。 */
    val wideLayout: Boolean,
)

/**
 * M85-C2：页面渲染 dispatch（从宿主的 `ui/pages/BuiltinPages.kt` + 原 `ui/App.kt` 搬来）。
 *
 * - 路由只认页面 id；标题/图标/层级由 [NativePages] 查表；
 * - M84-G 的整页插件（`page:true`，id 同名 = 替换）优先于壳内置页；
 * - `pageId == null` = 聊天落地页；未知 id 兜底空白（和 M79 以来一致）。
 */
@Composable
fun NativePageDispatch(
    vm: NativeShellHost,
    pageId: String?,
    subId: String?,
    nav: NativeNav,
    hostUi: NativeShellHostUi,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val page = NativePages.byId(pageId)
    val subPage = NativePages.byId(subId)
    // M85-C3-4：`page/v1` 统一页面表（壳声明页 + M84-G 插件整页）解析；
    // 插件 route id 与壳页/二级页同名 = 替换，渲染仍交宿主页面槽（壳不 import pluginui）。
    val resolved = ShellPageRegistry.resolve(
        shellPages = NativePages.declarations,
        pluginPages = vm.pluginPageEntries(),
        pageId = pageId,
        subId = subId,
    )
    if (resolved != null) {
        // 老壳实现可能不在 entry 里带目标，回落到 host 查询（同一套判定，行为不变）
        val target = resolved.pluginTarget ?: vm.pluginPageFor(pageId, subId)
        if (target != null) {
            hostUi.PluginPage(target, nav.backFromPage)
            return
        }
    }
    when (page?.id) {
        null -> {
            val conversation = vm.conversation
            val current = conversation.state
            if (current != null) {
                // 远程来源每次切换都要换一棵 ChatScreen（stateKey 带 profile/generation），
                // 但本机会话的 stateKey 里还带 sessionId，而新会话的 sessionId 是在第一条消息
                // 的回合里才落定的：直接拿它当组合 key 会把整棵 ChatScreen 销毁重建
                // （LazyColumn 滚动位置、焦点、弹层、选择器回调全丢）。本机沿用原行为，
                // 只按「是不是本机聊天页」这一层 key 组合。
                key(if (conversation.isLocal) "local-chat" else conversation.stateKey) {
                ChatScreen(
                    state = current,
                    assistantName = conversation.assistantName,
                    assistantAvatar = conversation.assistantAvatar,
                    assistantOpening = if (conversation.isLocal) vm.settings.assistantOpening else "",
                    expandThinking = vm.settings.expandThinking,
                    imagePath = { id -> if (conversation.isLocal) vm.imagePathFor(id) else null },
                    onOpenDrawer = nav.openDrawer,
                    menuVisible = !nav.wideLayout,
                    onNewChat = { conversation.newChat() },
                    onOpenWorkspace = { nav.goFromChat(BuiltinPageIds.Workspace) },
                    onAttachImage = { uri -> vm.attachImage(uri, target = current) },
                    onAttachPhoto = { file -> vm.attachPhoto(file, target = current) },
                    onCaptureScreenshot = { vm.attachScreenshot(target = current) },
                    onRemovePendingImage = { id -> vm.removePendingImage(id) },
                    onAttachFile = { uri -> vm.attachFile(uri, target = current) },
                    plugins = if (conversation.isLocal) vm.pluginsUi.filter { it.enabled } else emptyList(),
                    skills = if (conversation.isLocal) vm.skillsUi.filter { it.enabled } else emptyList(),
                    onEnablePlugin = { id -> vm.setPluginEnabled(id, true) },
                    onRemovePendingFile = { name -> vm.removePendingFile(name) },
                    onAttachText = { name, text -> vm.attachTextFile(name, text, target = current) },
                    notificationsEnabled = { ctx -> AttachmentCapture.notificationsEnabled(ctx) },
                    onOpenNotificationAccess = { ctx -> AttachmentCapture.openNotificationAccessSettings(ctx) },
                    onAttachNotifications = { ctx ->
                        val outcome = AttachmentCapture.attachNotifications(ctx)
                        NotifyAttachResult(outcome.ok, outcome.message, outcome.needsGrant)
                    },
                    onAttachLocation = { ctx -> LocationCapture.start(ctx) },
                    memoryPicker = { dismiss, pick ->
                        MemoryPickerDialog(
                            onDismiss = dismiss,
                            onSelect = { memories ->
                                dismiss()
                                pick(
                                    "memory-" + java.text.SimpleDateFormat(
                                        "yyyyMMdd-HHmmss",
                                        java.util.Locale.US,
                                    ).format(java.util.Date()) + ".txt",
                                    buildMemoryReference(memories),
                                )
                            },
                        )
                    },
                    onSubmit = { conversation.submit(it) },
                    onStop = { conversation.stop() },
                    capabilities = conversation.capabilities,
                    canSubmit = conversation.canSubmit,
                    connectionNotice = conversation.connectionNotice,
                    onReconnect = { conversation.reconnect() },
                    onSwitchToLocal = { conversation.selectTarget(null) },
                    onDraftChange = { conversation.updateDraft(it) },
                    onApproval = { allow, always -> vm.resolveApproval(allow, always) },
                    onAnswer = { answers -> conversation.answer(answers) },
                    planModeActive = current.planActive,
                    planModePending = current.planPending,
                    onSetPlanMode = { vm.setPlanMode(it) },
                    schedules = { vm.currentSchedules() },
                    onDeleteSchedule = { id -> vm.deleteSchedule(id) },
                    skillChoices = if (conversation.isLocal) vm.skillsUi.filter { it.userInvocable && it.enabled } else emptyList(),
                    onRefreshSkills = { vm.refreshSkills() },
                    fileCandidates = { query -> vm.fileCandidates(query) },
                    onDismissCommandNotice = { vm.dismissCommandNotice() },
                    onPauseGoal = { vm.pauseGoal() },
                    onResumeGoal = { vm.resumeGoal() },
                    onEditGoal = { objective, cap -> vm.editGoal(objective, cap) },
                    onClearGoal = { vm.clearGoal() },
                    onOpenJobs = {
                        // 不关当前聊天：任务页返回时还能继续刚才的对话（旧实现会丢会话视图）
                        nav.goFromChat(BuiltinPageIds.Jobs)
                        vm.refreshJobs()
                    },
                    onOpenSubagent = { id -> vm.openSubagentSession(id) },
                    onBranchMessage = { message -> vm.branchFromMessage(message) },
                    onRegenerateMessage = { message -> vm.regenerateFromMessage(message) },
                    visionEnabled = vm.settings.visionEnabled,
                    onToggleVision = { vm.settings.setVisionEnabled(it) },
                    webSearchEnabled = vm.settings.webSearchEnabled,
                    onToggleWebSearch = { vm.settings.setWebSearchEnabled(it) },
                    onOpenModelSettings = { nav.openSub(BuiltinPageIds.ModelConfig) },
                    enabledModels = vm.settings.enabledModels,
                    onSwitchModel = { vm.switchModel(it) },
                    approvalMode = vm.settings.approvalMode,
                    onSetApprovalMode = { vm.settings.setApprovalMode(it) },
                    reasoningEffort = vm.settings.reasoningEffort,
                    onSetReasoningEffort = { vm.settings.setReasoningEffort(it) },
                    maxMode = vm.settings.maxMode,
                    onSetMaxMode = { vm.settings.setMaxMode(it) },
                    autoCompact = vm.settings.autoCompact,
                    onSetAutoCompact = { vm.settings.setAutoCompact(it) },
                    autoCompactRatio = vm.settings.autoCompactRatio,
                    onSetAutoCompactRatio = { vm.settings.setAutoCompactRatio(it) },
                    onCompactNow = { vm.submit("/compact") },
                    modelContextWindow = vm.settings.contextWindow,
                    onSetExpandThinking = { vm.settings.setExpandThinking(it) },
                    timeContext = vm.settings.timeContext,
                    onSetTimeContext = { vm.settings.setTimeContext(it) },
                    sendWhileRunningMode = vm.settings.sendWhileRunningMode,
                    onSetSendWhileRunningMode = { vm.settings.setSendWhileRunningMode(it) },
                    onCancelQueuedPrompt = { vm.cancelQueuedPrompt(it) },
                    onResumeQueuedPrompts = { vm.resumeQueuedPrompts() },
                    memorySpaceName = "Default",
                    onOpenMemoryLibrary = {
                        // M83 阶段 2：记忆库是内置插件，关掉后入口点了也不进（goFromChat 自己有拦截）
                        nav.goFromChat(BuiltinPageIds.Memory)
                    },
                    onOpenPlugins = { nav.goFromChat(BuiltinPageIds.Plugins) },
                    pageEnabled = { id -> conversation.isLocal && vm.builtinScreenEnabled(id) },
                    // M85-D：回合插件提示 / 安全模式（agent/v1）
                    activeAgent = if (conversation.isLocal) vm.activeAgentUi else null,
                    agentSafetyNotice = if (conversation.isLocal) vm.agentSafetyNotice else null,
                    onRestoreBuiltinAgent = { vm.restoreBuiltinAgent() },
                    // M85-E：存储 / 提示词角色安全提示（模型不可用由 llmStream 如实报错）
                    storageSafetyNotice = if (conversation.isLocal) vm.storageSafetyNotice else null,
                    onRestoreBuiltinStorage = { vm.restoreBuiltinStorage() },
                    promptSafetyNotice = if (conversation.isLocal) vm.promptSafetyNotice else null,
                    onRestoreBuiltinPrompt = { vm.restoreBuiltinPrompt() },
                    // M78 并行：其它会话在等审批/回答时，顶部横幅一键切过去
                    waitingElsewhere = if (conversation.isLocal) vm.waitingElsewhere() else emptyList(),
                    onFocusSession = { id -> vm.focusSession(id) },
                )
                }
            } else {
                // 一帧空白：LaunchedEffect 马上会建草稿
                Spacer(Modifier.fillMaxSize())
            }
        }

        BuiltinPageIds.Workspace -> WorkspaceScreen(
            root = vm.workspace,
            onBack = { nav.toChat() },
        )

        BuiltinPageIds.Sessions -> SessionsScreen(
            sessions = vm.sessions,
            assistantName = vm.settings.assistantName,
            assistantAvatar = vm.settings.assistantAvatar,
            badgeFor = { id -> vm.badgeFor(id) },
            onOpenDrawer = nav.openDrawer,
            onBack = nav.backFromPage,
            onOpen = { summary ->
                nav.toChat()
                vm.openSession(summary)
            },
            onNew = {
                nav.toChat()
                vm.newChat()
            },
            onDelete = { vm.deleteSession(it.id) },
            onRename = { summary, title -> vm.renameSession(summary.id, title) },
            onDeletePermanently = { vm.deleteSessionPermanently(it.id) },
            trash = vm.trashUi,
            trashBusy = vm.trashBusy,
            onOpenTrash = { vm.refreshTrash() },
            onRestoreTrash = { vm.restoreTrashed(it) },
            onPurgeTrash = { vm.purgeTrashed(it) },
            onPurgeAllTrash = { vm.purgeAllTrashed() },
        )

        BuiltinPageIds.Agents -> SubagentsScreen(
            subagents = vm.subagents,
            maxDepth = vm.agentMaxDepth,
            loading = vm.subagentsLoading,
            onOpenDrawer = nav.openDrawer,
            onBack = nav.backFromPage,
            onRefresh = { vm.refreshSubagents() },
            onOpen = { vm.openSubagentSession(it.id) },
            onStop = { vm.stopSubagent(it.id) },
        )

        BuiltinPageIds.Jobs -> JobsScreen(
            jobs = vm.jobs,
            onOpenDrawer = nav.openDrawer,
            onBack = nav.backFromPage,
            onRefresh = { vm.refreshJobs() },
            onStop = { vm.stopJob(it) },
            onPeek = { vm.peekJob(it) },
        )

        BuiltinPageIds.Plugins -> NativePluginsPage(vm, hostUi, nav.openDrawer, nav.backFromPage)

        BuiltinPageIds.Terminal -> TerminalPage(onBack = nav.backFromPage)

        BuiltinPageIds.Packages -> PackagesScreen(
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
            onBack = nav.backFromPage,
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
            onSetOperitPackageEnabled = { id, enabled -> vm.setOperitPackageEnabled(id, enabled) },
            onRefreshSkills = { vm.refreshSkills() },
            onImportSkill = { uri -> vm.importSkill(uri) },
            onSetSkillEnabled = { name, enabled -> vm.setSkillEnabled(name, enabled) },
            onDeleteSkill = { skill -> vm.deleteSkill(skill) },
            onDismissSkillNotice = { vm.dismissSkillNotice() },
        )

        BuiltinPageIds.VirtualScreen -> VirtualScreenPage(onBack = nav.backFromPage)

        BuiltinPageIds.Memory -> MemoryScreen(onBack = nav.backFromPage)

        BuiltinPageIds.Accessibility -> AccessibilityScreen(onBack = nav.backFromPage)

        BuiltinPageIds.Settings -> {
            when (subPage?.id) {
                BuiltinPageIds.ModelConfig -> ModelConfigScreen(
                    settings = vm.settings,
                    onBack = nav.closeSub,
                    onTestConnection = { vm.testConnection() },
                    onFetchModels = { vm.fetchModels() },
                )
                BuiltinPageIds.Assistant -> AssistantScreen(
                    settings = vm.settings,
                    plugins = vm.pluginsUi,
                    onBack = nav.closeSub,
                )
                BuiltinPageIds.Behavior -> BehaviorScreen(
                    settings = vm.settings,
                    onBack = nav.closeSub,
                )
                BuiltinPageIds.Privacy -> PrivacyScreen(
                    settings = vm.settings,
                    onBack = nav.closeSub,
                    onOpenSessions = {
                        nav.closeSub()
                        nav.go(BuiltinPageIds.Sessions)
                    },
                )
                BuiltinPageIds.Backup -> BackupScreen(
                    onBack = nav.closeSub,
                    onRestored = { vm.onRestoredFromBackup() },
                    currentWorkspace = vm.workspacePath,
                    appVersion = vm.appVersionName,
                )
                BuiltinPageIds.DataMigration -> DataMigrationScreen(
                    sessionsRoot = vm.sessionsDir,
                    initialPath = vm.importRequest,
                    onInitialPathConsumed = { vm.consumeImportRequest() },
                    onBack = nav.closeSub,
                    onImported = { vm.refreshSessions() },
                    onOpenSessions = {
                        nav.closeSub()
                        nav.go(BuiltinPageIds.Sessions)
                    },
                )
                BuiltinPageIds.Skills -> SkillsScreen(
                    skills = vm.skillsUi,
                    roots = vm.skillRoots,
                    complete = vm.skillsComplete,
                    warnings = vm.skillsWarnings,
                    homeDir = vm.filesDirPath,
                    workspaceDir = vm.workspacePath,
                    onRefresh = { vm.refreshSkills() },
                    onBack = nav.closeSub,
                )
                BuiltinPageIds.Workspaces -> WorkspacesScreen(
                    workspaces = vm.workspaces,
                    currentPath = vm.workspacePath,
                    onBack = nav.closeSub,
                    onSwitch = { path ->
                        vm.switchWorkspace(path)
                        nav.toChat()
                    },
                    onCreate = { name, path -> vm.createWorkspace(name, path) },
                    onRename = { id, name -> vm.renameWorkspace(id, name) },
                    onDelete = { id -> vm.deleteWorkspace(id) },
                )
                BuiltinPageIds.Permissions -> xin.dshaa.nativeapp.ui.permissions.PermissionsScreen(
                    onBack = nav.closeSub,
                )
                BuiltinPageIds.RemoteDsh -> RemoteDshScreen(
                    controller = vm.remote,
                    onBack = nav.closeSub,
                    onSelectTarget = { id -> vm.conversation.selectTarget(id); nav.toChat() },
                )
                BuiltinPageIds.About -> AboutScreen(
                    onBack = nav.closeSub,
                    versionName = vm.appVersionName,
                    versionCode = vm.appVersionCode,
                )
                null -> SettingsScreen(
                    settings = vm.settings,
                    onOpenDrawer = nav.openDrawer,
                    scrollState = nav.settingsScroll,
                    menuVisible = !nav.wideLayout,
                    versionName = vm.appVersionName,
                    onOpenModelConfig = { nav.openSub(BuiltinPageIds.ModelConfig) },
                    onOpenAssistant = { nav.openSub(BuiltinPageIds.Assistant) },
                    onOpenBehavior = { nav.openSub(BuiltinPageIds.Behavior) },
                    onOpenPrivacy = { nav.openSub(BuiltinPageIds.Privacy) },
                    onOpenBackup = { nav.openSub(BuiltinPageIds.Backup) },
                    onOpenMigration = { nav.openSub(BuiltinPageIds.DataMigration) },
                    onOpenSkills = { nav.openSub(BuiltinPageIds.Skills) },
                    onOpenWorkspaces = { nav.openSub(BuiltinPageIds.Workspaces) },
                    onOpenPermissions = { nav.openSub(BuiltinPageIds.Permissions) },
                    onOpenRemote = { nav.openSub(BuiltinPageIds.RemoteDsh) },
                    onOpenFeature = { pageId -> nav.go(pageId) },
                    currentWorkspaceName = vm.currentWorkspace.name,
                    onOpenSessions = { nav.go(BuiltinPageIds.Sessions) },
                    onOpenAbout = { nav.openSub(BuiltinPageIds.About) },
                    screenEnabled = { pageId -> vm.builtinScreenEnabled(pageId) },
                )
            }
        }

        else -> Spacer(Modifier.fillMaxSize())
    }
}
