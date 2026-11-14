package xin.dshaa.ui.shellapi

import androidx.compose.runtime.Composable

/**
 * M85-C3-3c：壳在渲染时向**宿主**借用的页面槽。
 *
 * UI 实现已经搬进 `ui:native-shell`，但插件整页的 WebView 渲染器（`pluginui`）仍按设计留在宿主；
 * 壳通过本接口把「渲染这一页」交回宿主，自己不 import pluginui/WebView。
 */
interface NativeShellHostUi {

    /** M84-G：插件整页（含同 id 替换内置页）的宿主渲染器。 */
    @Composable
    fun PluginPage(target: PluginPageTarget, onBack: () -> Unit)

    /** 打开插件独立页（宿主 Activity；抽屉「插件页面」入口用）。 */
    fun openPluginPage(context: android.content.Context, record: xin.dshaa.core.plugins.PluginRecord, routeId: String)
}
