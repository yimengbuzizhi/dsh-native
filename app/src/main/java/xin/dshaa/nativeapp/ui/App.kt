package xin.dshaa.nativeapp.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.viewmodel.compose.viewModel
import xin.dshaa.nativeapp.chat.AppViewModel
import xin.dshaa.nativeapp.pluginui.PluginPageScreen
import xin.dshaa.ui.shellapi.NativeShellHostUi
import xin.dshaa.ui.shellapi.NativeShells
import xin.dshaa.ui.shellapi.PluginPageTarget
import xin.dshaa.nativeapp.ui.shell.SafeModeScreen
import xin.dshaa.nativeapp.ui.surface.PluginShellScreen
import xin.dshaa.nativeapp.ui.surface.WebViewShellScreen

/**
 * 应用入口（M85-C 起 = 宿主壳选择器，不再是 UI 本体）。
 *
 * 宿主在这里只做三件事：主题/系统栏、选壳、安全模式兜底；
 * 具体界面由 `shell/v1` 的后端提供：
 * - **native 后端**（默认）：内置界面插件 `builtin.shell.native`，现有 Compose UI 原样搬进
 *   `ui/shell/native/NativeShellRoot.kt`（聊天/抽屉/22 页/设置/onboarding）；
 * - **Surface 后端**（可选）：`builtin.shell.whale` 等 JS 壳返回 UI 树，由 `PluginShellScreen` 渲染；
 * - 内置界面插件被停用且没选可选壳 → [SafeModeScreen]（可从安全模式重新打开）。
 *
 * 本文件不允许 import 任何具体页面/聊天页/抽屉——那是界面插件的事（`docs/152` 源码扫描验收）。
 */
@Composable
fun DshApp(vm: AppViewModel = viewModel()) {
    val dark = when (vm.settings.themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    xin.dshaa.ui.design.theme.DshTheme(darkTheme = dark, accentId = vm.settings.accentId) {
        // 系统栏图标跟着主题走（enableEdgeToEdge 只按系统深色模式取样式，切浅色后图标会看不见）
        val view = LocalView.current
        val context = LocalContext.current
        LaunchedEffect(dark) {
            val activity = context as? android.app.Activity ?: return@LaunchedEffect
            androidx.core.view.WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        val surfaceShell = vm.shellActive
        val nativeShell = NativeShells.builtin()
        val nativeEnabled = vm.nativeUiEnabled()
        // M85-C3-3c：插件整页仍由宿主 pluginui 渲染，经 shell/v1 的页面槽交回壳
        val hostUi = remember {
            object : NativeShellHostUi {
                @Composable
                override fun PluginPage(target: PluginPageTarget, onBack: () -> Unit) {
                    PluginPageScreen(
                        record = target.record,
                        title = target.route.title,
                        onBack = onBack,
                    )
                }

                override fun openPluginPage(
                    context: android.content.Context,
                    record: xin.dshaa.core.plugins.PluginRecord,
                    routeId: String,
                ) {
                    xin.dshaa.nativeapp.pluginui.PluginPageActivity.open(context, record, routeId)
                }
            }
        }
        var recoveryPlugins by remember { mutableStateOf(false) }
        LaunchedEffect(surfaceShell, nativeEnabled) { recoveryPlugins = false }
        when {
            recoveryPlugins && nativeShell != null && !nativeEnabled && surfaceShell == null -> {
                androidx.activity.compose.BackHandler { recoveryPlugins = false }
                nativeShell.PluginManager(vm, hostUi) { recoveryPlugins = false }
            }
            // 1) 用户选了 JS/Surface/WebView 壳：可选壳，onboarding 仍由 native 壳提供
            surfaceShell != null -> {
                if (!vm.onboardingDone) {
                    // M85-C3：引导页也走 shell/v1（宿主不 import 具体页面），实现仍在内置界面插件里
                    nativeShell?.Onboarding(onDone = { vm.completeOnboarding() }) ?: SafeModeScreen(vm)
                } else if (surfaceShell.backend == "webview") {
                    // M85-F：shell/v1 第三种后端——整屏 WebView，与 Surface 共用 shell.* / surface.command
                    WebViewShellScreen(vm, surfaceShell)
                } else {
                    PluginShellScreen(vm, surfaceShell)
                }
            }

            // 2) 默认：内置 native 界面插件（原界面原样，含 onboarding）
            nativeShell != null && nativeEnabled -> nativeShell.Root(vm, hostUi)

            // 3) 内置界面插件被停用（且没选可选壳）：安全模式，可一键重新打开
            else -> SafeModeScreen(vm, onOpenPlugins = { recoveryPlugins = true })
        }
    }
}
