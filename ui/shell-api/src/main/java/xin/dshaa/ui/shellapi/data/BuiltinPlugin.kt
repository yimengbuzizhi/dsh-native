package xin.dshaa.ui.shellapi.data

import xin.dshaa.ui.shellapi.BuiltinPageIds

/**
 * M83 阶段 2：**内置插件**——把宿主里已有的一个工具 / 一个页面包装成「插件」，可以单独开关。
 *
 * 为什么不是普通 DSH 插件：内置工具是 Kotlin 实现（不是 JS），页面是 Compose 路由；
 * 所以「插件化」的第一层是**登记 + 可开关**：关掉 = 不从 tools[] 里给模型、入口不出现、路由拦截；
 * 打开 = 恢复。等页面/工具真正搬到 JS 插件（`docs/115 §A/B`）时，这套 id 和开关直接沿用。
 *
 * M84-B：页面不再由 `Screen`/`SubScreen` 枚举提供，而是由这里的 `screens`（[BuiltinPageIds]）
 * 声明归属、由内置界面插件的页面清单（M85-C2 起在 `ui/shell/native/pages/`）提供标题/图标/层级；
 * 关掉插件 = 该页入口消失、路由被拦。
 *
 * 红线：用户导入的插件不进 APK；这里全是**内置**能力，和导入插件共用同一个「插件页」开关，
 * 但记录在本地 prefs（`builtin_plugins_disabled`），不进 `plugins.json`。
 */
enum class BuiltinPluginKind {
    /** 12 个内置工具组：关掉 = 工具从 ToolRegistry 注销 / tools[] 消失。 */
    Tools,

    /** 页面插件：关掉 = 页面入口消失 / 路由拦截；部分组同时带工具。 */
    Page,

    /** M84-D 悬浮窗插口插件：关掉 = 从 FloatingSlots 注销，四个插口恢复默认。 */
    Floating,

    /** M85-C：界面插件（shell/v1 native 后端）。关掉 = 安全模式/回退壳，可从安全模式重新打开。 */
    Shell,

    /** M85-D：回合插件（agent/v1）。关掉 = 没有可用回合，聊天页进安全提示，可从提示一键恢复。 */
    Agent,

    /** M85-E：存储插件（storage/v1）。关掉 = 没有会话存储，聊天页进存储安全提示。 */
    Storage,

    /** M85-E：提示词插件（prompt/v1）。关掉 = 没有系统提示词，聊天页进提示词安全提示。 */
    Prompt,

    /** M85-E：模型插件（model/v1）。关掉 = 该模型通道消失，选中它时如实报错（不悄悄换内置）。 */
    Model,
}

data class BuiltinPlugin(
    /** 稳定 id：以后 JS 化的同名插件沿用这个 id，能做迁移。 */
    val id: String,
    val name: String,
    val description: String,
    /** 这个内置插件贡献的工具名（关掉后不进 tools[]）。 */
    val tools: Set<String> = emptySet(),
    /** 贡献的页面（[BuiltinPageIds]；关掉后入口消失、路由拦截）。 */
    val screens: Set<String> = emptySet(),
    val defaultEnabled: Boolean = true,
    val kind: BuiltinPluginKind = BuiltinPluginKind.Tools,
)

/** 插件页展示用：内置插件 + 当前开关状态。 */
data class BuiltinPluginUi(val plugin: BuiltinPlugin, val enabled: Boolean)
