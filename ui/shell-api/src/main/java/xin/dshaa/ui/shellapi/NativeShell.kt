package xin.dshaa.ui.shellapi

import androidx.compose.runtime.Composable

/**
 * M85-C：`shell/v1` 的 **native 后端**契约（宿主编译期只认这个接口）。
 *
 * 背景（`docs/144` §5 + 2026-11-13 23:4x 澄清）：原生 UI 不删、原样搬进内置界面插件；
 * 宿主只负责「选壳 + 主题 + 安全模式」，不再 import 聊天页/抽屉/22 页/设置/页面清单/引导页。
 *
 * 后端二选一：
 * - [NativeShell]（本文件）：Kotlin/Compose 实现，`builtin.shell.native` 是默认；
 * - `PluginShellHost` / Surface：JS 壳返回 UI 树，`builtin.shell.whale` 是可选的第二套。
 *
 * native 后端也在内置插件清单里（`builtin.shell.native`，`kind = Shell`；目录仍是宿主的
 * `BuiltinPlugins`）：可停用（→ 宿主安全模式）、可重新打开、和工具/页面插件同一套开关与持久化。
 * 第三方 UI 仍走 Surface/WebView 后端；native 实现随 APK 编译，不做 DEX 动态加载
 * （`docs/144` §10 非目标）。
 *
 * M85-C3-2：本接口搬进 `ui:shell-api`；具体实现（`BuiltinNativeShell`）与注册表
 * `NativeShells` 仍留在宿主，等 C3-3 再随 `ui:native-shell` 模块迁走。
 */
interface NativeShell {
    /** 稳定 id；默认壳 = `builtin.shell.native`。 */
    val id: String

    /** 插件页/设置里显示的名字。 */
    val label: String

    /**
     * M85-C2（`page/v1` 雏形）：这个壳**声明**的页面清单。
     *
     * 只交 id/标题/层级这类元数据给宿主（调试 dump、清单校验用）；页面怎么画、怎么路由
     * 全部留在壳里（[Root] 内部）。宿主不再持有任何页面清单/标题/图标。
     */
    val pages: List<ShellPage>

    /**
     * 调试通道 `--es dsh_open_screen X` 的宽松匹配：id、标题、`<id>Screen` 都认（大小写不敏感）。
     * 由壳自己解释——宿主只把它当字符串透传。
     */
    fun findPage(requested: String): ShellPage?

    /**
     * 画整屏 UI：引导页（未完成时）+ 抽屉/页面/聊天/设置全部由壳决定。
     * 宿主提供 [host]（应用状态与动作，见 [NativeShellHost]）、[ui]（宿主自有的页面槽，
     * 如插件整页 WebView 渲染器）和主题；壳内部自己管路由。
     */
    @Composable
    fun Root(host: NativeShellHost, ui: NativeShellHostUi)

    /**
     * M85-C3：引导页。Surface 壳没有引导页，宿主在挂载可选壳之前**向壳要**这一步，
     * 这样引导页实现留在内置界面插件里，宿主 `ui/App.kt` 不再 import 任何具体页面。
     */
    @Composable
    fun Onboarding(onDone: () -> Unit)

    /** 安全模式单独借用插件管理页，不启用整套界面壳。 */
    @Composable
    fun PluginManager(host: NativeShellHost, ui: NativeShellHostUi, onBack: () -> Unit)
}
