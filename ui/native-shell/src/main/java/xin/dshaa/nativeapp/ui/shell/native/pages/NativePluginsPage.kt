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
        capabilityTargets = host.capabilityUi,
        capabilityNotice = host.capabilityNotice,
        onInvokeCapability = host::invokeCapability,
    )
}
