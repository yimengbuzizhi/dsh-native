package xin.dshaa.ui.shellapi

/**
 * M84-B：**页面路由 id**。
 *
 * 这里取代 M79 以来的 `Screen` / `SubScreen` 枚举：路由状态、`rememberSaveable` 恢复、
 * 调试通道 `--es dsh_open_screen <id>`、内置插件的 `screens` 和页面目录共用同一套**稳定字符串 id**。
 * 沿用旧枚举常量名是刻意的——旧路由快照、旧验收命令、旧截图脚本都不用改。
 *
 * M85-C2：**页面清单已归内置界面插件**（`ui/shell/native/pages/NativePages.kt` 提供标题/图标/层级，
 * 渲染 dispatch 也在壳里）；这里只剩「稳定 id 词表」——内置插件的 `screens` 归属、路由快照、
 * 调试通道 `--es dsh_open_screen <id>` 共用同一套 id。
 * 页面被关掉后：设置/抽屉/聊天快捷入口不显示，`go()`/`openSub()`/调试通道都拒绝进入。
 */
object BuiltinPageIds {
    // ---- 一级页面（11） ----
    const val Sessions = "Sessions"
    const val Workspace = "Workspace"
    const val Agents = "Agents"
    const val Jobs = "Jobs"
    const val Terminal = "Terminal"
    const val VirtualScreen = "VirtualScreen"
    const val Accessibility = "Accessibility"
    const val Plugins = "Plugins"
    const val Packages = "Packages"
    const val Memory = "Memory"
    const val Settings = "Settings"

    // ---- 二级页面（11；RemoteDsh 是 M83 新加，About 是 M84-B 把弹窗补成整页） ----
    const val ModelConfig = "ModelConfig"
    const val Assistant = "Assistant"
    const val Behavior = "Behavior"
    const val Backup = "Backup"
    const val DataMigration = "DataMigration"
    const val Skills = "Skills"
    const val Workspaces = "Workspaces"
    const val Permissions = "Permissions"
    const val Privacy = "Privacy"
    const val RemoteDsh = "RemoteDsh"
    const val About = "About"

    /** 全部页面 id（顺序 = 页面目录顺序；验收/调试 dump 用）。 */
    val all: List<String> = listOf(
        Sessions, Workspace, Agents, Jobs, Terminal, VirtualScreen, Accessibility,
        Plugins, Packages, Memory, Settings,
        ModelConfig, Assistant, Behavior, Backup, DataMigration, Skills, Workspaces,
        Permissions, Privacy, RemoteDsh, About,
    )
}
