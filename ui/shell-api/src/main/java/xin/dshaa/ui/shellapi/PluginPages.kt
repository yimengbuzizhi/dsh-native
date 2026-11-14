package xin.dshaa.ui.shellapi

import xin.dshaa.core.plugins.PluginRecord
import xin.dshaa.core.plugins.PluginRoute

/**
 * M85-C3：从 `AppViewModel` 里搬出来的**页面注册表类型**（原来嵌在 VM 里）。
 *
 * `NativeShellHost`（`ui/shell/NativeShellHost.kt`）要引用这两个类型，
 * 如果它们还嵌在 `AppViewModel` 里，壳模块拆出去后仍会反向依赖宿主 VM；
 * 现在它们和 `ShellPage`/`NativeShell` 同在 `ui/shell` 契约包。
 */

/** M84-G：整页插件页面的匹配结果。 */
data class PluginPageTarget(val record: PluginRecord, val route: PluginRoute)

/**
 * M84-K：插件注册的**整页路由**（启用中 + 有 dsh.client + `page:true`）。
 *
 * M85-C3-4 起 `pluginPageEntries()` 不再排除与内置页同名的 route——同 id 项由
 * [ShellPageRegistry] 并进统一页面表并标记 `overridesShellPage`；抽屉只列独立插件页。
 *
 * [target] 是渲染目标（record+route），dispatch 直接交给宿主页面槽
 * [NativeShellHostUi.PluginPage]；老调用方（只读 id/标题）可以不填。
 */
data class PluginPageEntry(
    val pluginId: String,
    val pluginName: String,
    val routeId: String,
    val title: String,
    val target: PluginPageTarget? = null,
)
