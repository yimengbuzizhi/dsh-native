package xin.dshaa.nativeapp.ui.shell.native.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import xin.dshaa.nativeapp.ui.plugins.PluginsScreen
import xin.dshaa.ui.shellapi.NativeShellHost
import xin.dshaa.ui.shellapi.NativeShellHostUi

/** 普通路由和安全模式共用插件管理接线；恢复入口不经过第三方页面覆盖。 */
@Composable
internal fun NativePluginsPage(
    host: NativeShellHost,
    hostUi: NativeShellHostUi,
    onOpenDrawer: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val context = LocalContext.current
    PluginsScreen(
        plugins = host.pluginsUi,
        injected = host.pluginInjected,
        busy = host.pluginsBusy,
        notice = host.pluginNotice,
        workspacePath = host.workspacePath,
        initialDetailId = host.debugPluginDetailId,
        onOpenDrawer = onOpenDrawer,
        onBack = onBack,
        onRefresh = host::refreshPlugins,
        onImport = host::importPlugin,
        onToggle = host::setPluginEnabled,
        onReload = host::reloadPlugin,
        onDelete = host::deletePlugin,
        onGrant = host::grantPluginPermission,
        onSetConfig = host::savePluginConfig,
        onDismissNotice = host::dismissPluginNotice,
        onOpenPluginWindow = host::openClientPluginWindow,
        onOpenPluginPage = { record, routeId -> hostUi.openPluginPage(context, record, routeId) },
        builtins = host.builtinPluginsUi,
        onToggleBuiltin = host::setBuiltinPluginEnabled,
        loadingTargets = host.loadingTargetsUi,
        loadingSafetyNotice = host.loadingSafetyNotice,
        onSelectLoading = { host.selectLoading(it) },
        onRestoreBuiltinLoading = host::restoreBuiltinLoading,
        shellTargets = host.shellTargetsUi,
        onSelectShell = { host.selectShell(it) },
        onRestoreBuiltinShell = host::restoreBuiltinShell,
        agentTargets = host.agentTargetsUi,
        activeAgent = host.activeAgentUi,
        agentSafetyNotice = host.agentSafetyNotice,
        onSelectAgent = { host.setActiveAgent(it) },
        onRestoreBuiltinAgent = host::restoreBuiltinAgent,
        storageTargets = host.storageTargetsUi,
        activeStorage = host.activeStorageUi,
        storageSafetyNotice = host.storageSafetyNotice,
        onSelectStorage = { host.setActiveStorage(it) },
        onRestoreBuiltinStorage = host::restoreBuiltinStorage,
        promptTargets = host.promptTargetsUi,
        activePrompt = host.activePromptUi,
        promptSafetyNotice = host.promptSafetyNotice,
        onSelectPrompt = { host.setActivePrompt(it) },
        onRestoreBuiltinPrompt = host::restoreBuiltinPrompt,
        modelTargets = host.modelTargetsUi,
        activeModel = host.activeModelUi,
        onSelectModel = { host.setActiveModel(it) },
        onRestoreBuiltinModel = host::restoreBuiltinModel,
        // M88-E：AI 授予的可见性（横幅撤销 / 详情审计 / 权限行「AI 授予」）。
        onUndoAiGrant = { host.undoAiPluginGrant() },
        pluginAuditLines = { pluginId -> host.pluginAuditLines(pluginId) },
        aiGrantedPermissions = { pluginId -> host.aiGrantedPermissions(pluginId) },
        capabilityTargets = host.capabilityUi,
        capabilityNotice = host.capabilityNotice,
        onInvokeCapability = host::invokeCapability,
        // M87-F：扩展点页签 + 插件中间件总开关。
        pluginExtensions = host.pluginExtensionsUi,
        pluginMiddlewareEnabled = host.pluginMiddlewareEnabled,
        onTogglePluginMiddleware = host::setPluginMiddlewareEnabled,
        // docs/185 P2：Node 插件宿主（实验开关）。
        nodeHostEnabled = host.nodePluginHostEnabled,
        nodeHostState = host.nodeHostStateText,
        onToggleNodeHost = host::setNodePluginHostEnabled,
        nodeHostPlugins = host.nodeHostPlugins,
        nodeHostLogs = host.nodeHostLogs,
        onReloadNodeHost = host::reloadNodeHost,
        onImportNodePlugin = host::importNodePlugin,
        onRefreshExtensions = host::refreshPluginExtensions,
    )
}
