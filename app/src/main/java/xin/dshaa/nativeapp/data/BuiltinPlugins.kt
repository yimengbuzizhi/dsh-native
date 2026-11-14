package xin.dshaa.nativeapp.data

import android.content.Context
import xin.dshaa.ui.shellapi.data.BuiltinPlugin
import xin.dshaa.ui.shellapi.data.BuiltinPluginKind
import xin.dshaa.ui.shellapi.data.BuiltinPluginUi
import xin.dshaa.ui.shellapi.BuiltinPageIds

/**
 * M83 阶段 2：内置插件**目录**（每个内置工具/页面登记成可单独开关的插件）。
 *
 * M85-C3-2：`BuiltinPlugin`/`BuiltinPluginKind`/`BuiltinPluginUi` 数据类已搬进
 * `ui:shell-api` 的 `data` 子包；目录依赖 `BuiltinPageIds`/工具清单，仍留在 app。
 * 开关持久化见 [BuiltinPluginStore]（prefs 的 `builtin_plugins_disabled`）。
 */
object BuiltinPlugins {

    /**
     * M85-C：内置界面插件（`shell/v1` native 后端）的稳定 id。
     *
     * 默认界面的整屏 UI（聊天/抽屉/22 页/设置/引导）由它提供；关掉后宿主只画安全模式，
     * 可从安全模式重新打开——和工具/页面插件同一套开关、同一条持久化路径。
     */
    const val NATIVE_UI_SHELL = "builtin.shell.native"

    /**
     * M85-D：内置回合插件（`agent/v1` native 后端）的稳定 id。
     *
     * 现有 `TurnEngine` 的实现登记成插件：和导入的 JS 回合插件同一套开关、同样的 `agent` 授权、
     * 可被同 id 的第三方实现覆盖；关掉后聊天页进入回合安全提示（不崩溃、可一键恢复）。
     */
    const val NATIVE_AGENT = "builtin.agent.dsh"

    /**
     * M85-E：内置存储插件（`storage/v1` native 后端）的稳定 id——现有 `FileSessionRepository`
     * 原样登记成插件（沿用 `files/sessions/` 下的 JSONL）。关掉 = 没有会话存储（存储安全提示），
     * 可被同 id 第三方只读导出器接管。
     */
    const val NATIVE_STORAGE = "builtin.storage.jsonl"

    /** M85-E：内置提示词插件（`prompt/v1` native 后端）的稳定 id——现有 `PromptPack` 原样登记。 */
    const val NATIVE_PROMPT = "builtin.prompt.dsh"

    /** M85-E：内置模型插件（`model/v1` native 后端）的稳定 id——现有两条 LlmClient 通道。 */
    const val NATIVE_MODEL_DEEPSEEK = "builtin.model.deepseek"
    const val NATIVE_MODEL_OPENAI = "builtin.model.openai"

    /**
     * M84-A：32 个内置工具分成 **12 个内置工具插件组**（每个工具恰好归属一组）。
     * M84-B：页面也归属插件——有天然工具组的页（会话/工作区/子代理/任务/终端/技能/记忆库）
     * 挂在对应组上，其余页面由 9 个页面插件提供；插件页「插件」/「设置」是核心页，不可关。
     * 这层仍是「登记 + 可开关」；工具/页面实现还是 Kotlin，真正 JS 化按 `docs/115 §A/B` 后续做。
     * M85-C：界面插件也进这份清单（`kind = Shell`），默认 UI 因此可停用/可回退。
     */
    val all: List<BuiltinPlugin> = listOf(
        BuiltinPlugin(
            id = NATIVE_UI_SHELL,
            name = "界面：原生 UI",
            description = "整屏界面：聊天页 / 输入条 / 抽屉 / 22 页 / 设置 / onboarding（关掉 = 安全模式，可从安全模式重新打开）",
            kind = BuiltinPluginKind.Shell,
        ),
        BuiltinPlugin(
            id = NATIVE_AGENT,
            name = "回合：dsH 引擎",
            description = "回合循环（agent/v1 native 实现：请求/工具/压缩/审批/goal/子代理）；关掉 = 聊天页回合安全提示，可一键恢复",
            kind = BuiltinPluginKind.Agent,
        ),
        BuiltinPlugin(
            id = NATIVE_STORAGE,
            name = "存储：JSONL 会话",
            description = "会话仓库（storage/v1 native 实现：现有 files/sessions/*.jsonl，zstd 帧/回收站/分支不变）；" +
                "关掉 = 没有会话存储（存储安全提示），可被同 id 只读导出器接管",
            kind = BuiltinPluginKind.Storage,
        ),
        BuiltinPlugin(
            id = NATIVE_PROMPT,
            name = "提示词：dsH 系统提示词",
            description = "系统提示词拼装（prompt/v1 native 实现：现有 PromptPack 段/变量/角色卡/插件段不变）；" +
                "关掉 = 没有系统提示词（提示词安全提示），可被同 id 插件接管",
            kind = BuiltinPluginKind.Prompt,
        ),
        BuiltinPlugin(
            id = NATIVE_MODEL_DEEPSEEK,
            name = "模型：DeepSeek",
            description = "DeepSeek · Anthropic Messages 通道（model/v1 native 实现）；关掉 = 选中它时如实报错，不悄悄换通道",
            kind = BuiltinPluginKind.Model,
        ),
        BuiltinPlugin(
            id = NATIVE_MODEL_OPENAI,
            name = "模型：OpenAI 兼容",
            description = "OpenAI chat/completions 兼容通道（model/v1 native 实现）；关掉 = 选中它时如实报错，不悄悄换通道",
            kind = BuiltinPluginKind.Model,
        ),
        BuiltinPlugin(
            id = "builtin.plugin.dev",
            name = "插件开发",
            description = "供 AI 生成、试运行、安装、启停与测试插件；变更仍走工具审批，权限沿用插件清单",
            tools = setOf("plugin_dev_list", "plugin_dev_test", "plugin_dev_install", "plugin_dev_enable",
                "plugin_dev_reload", "plugin_dev_scaffold", "plugin_dev_call", "plugin_dev_test_shell", "plugin_dev_test_capability"),
        ),
        BuiltinPlugin(
            id = "builtin.fs",
            name = "文件读写",
            description = "read / read_image / write / edit / glob / grep：工作区文件读写与检索；工作区页",
            tools = setOf("read", "read_image", "write", "edit", "glob", "grep"),
            screens = setOf(BuiltinPageIds.Workspace),
        ),
        BuiltinPlugin(
            id = "builtin.shell",
            name = "命令与目录",
            description = "bash / working_directory / 终端会话 6 个工具：跑命令、切换工具根目录、持久 PTY；终端页",
            tools = setOf(
                "bash", "working_directory",
                "create_terminal_session", "execute_in_terminal_session", "execute_hidden_terminal_command",
                "input_in_terminal_session", "close_terminal_session", "get_terminal_session_screen",
            ),
            screens = setOf(BuiltinPageIds.Terminal),
        ),
        BuiltinPlugin(
            id = "builtin.jobs",
            name = "后台任务",
            description = "job_output / job_list / job_kill：后台 bash 与任务页",
            tools = setOf("job_output", "job_list", "job_kill"),
            screens = setOf(BuiltinPageIds.Jobs),
        ),
        BuiltinPlugin(
            id = "builtin.web",
            name = "联网",
            description = "web_search / web_fetch：搜索与抓网页",
            tools = setOf("web_search", "web_fetch"),
        ),
        BuiltinPlugin(
            id = "builtin.agent",
            name = "子代理",
            description = "subagent / subagent_fork / list_agents / send_message / interrupt_agent；子代理页",
            tools = setOf("subagent", "subagent_fork", "list_agents", "send_message", "interrupt_agent"),
            screens = setOf(BuiltinPageIds.Agents),
        ),
        BuiltinPlugin(
            id = "builtin.schedule",
            name = "定时提醒",
            description = "schedule_create / list / update / delete：会话内提醒",
            tools = setOf("schedule_create", "schedule_list", "schedule_update", "schedule_delete"),
        ),
        BuiltinPlugin(
            id = "builtin.goal",
            name = "目标",
            description = "get_goal / create_goal / update_goal：长任务目标与轮次",
            tools = setOf("get_goal", "create_goal", "update_goal"),
        ),
        BuiltinPlugin(
            id = "builtin.plan",
            name = "计划模式",
            description = "exit_plan_mode：计划审批与退出计划模式",
            tools = setOf("exit_plan_mode"),
        ),
        BuiltinPlugin(
            id = "builtin.interaction",
            name = "交互",
            description = "ask_user_question / present：向用户提问与展示成品",
            tools = setOf("ask_user_question", "present"),
        ),
        BuiltinPlugin(
            id = "builtin.todo",
            name = "待办事项",
            description = "todo_write：给模型一个任务清单，进度对用户可见",
            tools = setOf("todo_write"),
        ),
        BuiltinPlugin(
            id = "builtin.skill",
            name = "技能",
            description = "skill：按需加载工作区/内置技能说明；技能页",
            tools = setOf("skill"),
            screens = setOf(BuiltinPageIds.Skills),
        ),
        BuiltinPlugin(
            id = "builtin.workflow",
            name = "工作流与工作区依赖",
            description = "workflow / load_workspace_dependencies：多步工作流与工作区依赖探测",
            tools = setOf("workflow", "load_workspace_dependencies"),
        ),
        BuiltinPlugin(
            id = "builtin.memory",
            name = "记忆库",
            description = "记忆库页面 + 8 个记忆工具：新增/修改/删除/移动/检索/链接（关掉后入口、路由与工具都消失）",
            tools = setOf(
                "query_memory", "get_memory_by_title", "create_memory", "update_memory",
                "delete_memory", "link_memories", "query_memory_links", "move_memory",
            ),
            screens = setOf(BuiltinPageIds.Memory),
            kind = BuiltinPluginKind.Page,
        ),

        // ---- M84-B：页面插件（没有天然工具组的页面各归一个页面组，逐页可关） ----
        BuiltinPlugin(
            id = "builtin.page.sessions",
            name = "页面：会话列表",
            description = "会话页：全部会话 / 回收站 / 重命名 / 删除（聊天本身是核心，不受影响）",
            screens = setOf(BuiltinPageIds.Sessions),
            kind = BuiltinPluginKind.Page,
        ),
        BuiltinPlugin(
            id = "builtin.page.workspaces",
            name = "页面：工作区管理",
            description = "工作区管理二级页：切换 / 新建 / 重命名 / 删除",
            screens = setOf(BuiltinPageIds.Workspaces),
            kind = BuiltinPluginKind.Page,
        ),
        BuiltinPlugin(
            id = "builtin.page.device",
            name = "页面：设备",
            description = "虚拟屏 / 无障碍 / 权限与能力三个页面 + 12 个设备工具（读屏/点按/滑动/输入/截图/启动应用/虚拟屏）",
            tools = setOf(
                "device_info", "dump_ui", "click_element", "tap", "long_press", "swipe",
                "press_key", "input_text", "launch_app", "capture_screenshot",
                "start_virtual_display", "close_all_virtual_displays",
            ),
            screens = setOf(
                BuiltinPageIds.VirtualScreen,
                BuiltinPageIds.Accessibility,
                BuiltinPageIds.Permissions,
            ),
            kind = BuiltinPluginKind.Page,
        ),
        BuiltinPlugin(
            id = "builtin.page.packages",
            name = "页面：包管理",
            description = "包管理页：插件 / 技能 / MCP 的导入、启停与配置",
            screens = setOf(BuiltinPageIds.Packages),
            kind = BuiltinPluginKind.Page,
        ),
        BuiltinPlugin(
            id = "builtin.page.model",
            name = "页面：模型配置",
            description = "模型与参数配置二级页：端点 / 密钥 / 模型名 / 生成参数",
            screens = setOf(BuiltinPageIds.ModelConfig),
            kind = BuiltinPluginKind.Page,
        ),
        BuiltinPlugin(
            id = "builtin.page.assistant",
            name = "页面：助手与行为",
            description = "助手配置 / 显示与行为两个二级页",
            screens = setOf(BuiltinPageIds.Assistant, BuiltinPageIds.Behavior),
            kind = BuiltinPluginKind.Page,
        ),
        BuiltinPlugin(
            id = "builtin.page.data",
            name = "页面：数据",
            description = "备份与恢复 / 旧版数据导入 / 隐私与数据清理三个二级页",
            screens = setOf(
                BuiltinPageIds.Backup,
                BuiltinPageIds.DataMigration,
                BuiltinPageIds.Privacy,
            ),
            kind = BuiltinPluginKind.Page,
        ),
        BuiltinPlugin(
            id = "builtin.page.remote",
            name = "页面：远程 DSH",
            description = "远程 DSH 二级页：连接电脑上的 DSH、会话列表与远程聊天",
            screens = setOf(BuiltinPageIds.RemoteDsh),
            kind = BuiltinPluginKind.Page,
        ),
        BuiltinPlugin(
            id = "builtin.page.about",
            name = "页面：关于",
            description = "关于二级页：版本、协议、界面参考与许可（M84-B 从弹窗改成整页）",
            screens = setOf(BuiltinPageIds.About),
            kind = BuiltinPluginKind.Page,
        ),

        // ---- M84-D：悬浮窗四分口示例插件（默认关；开 = 注册到 FloatingSlots） ----
        BuiltinPlugin(
            id = "builtin.floating.demo",
            name = "悬浮窗插口（示例）",
            description = "球红点「1」+ 粉底气泡 + 面板/全屏页横幅；关掉四个插口立即恢复默认",
            kind = BuiltinPluginKind.Floating,
            defaultEnabled = false,
        ),
    )

    fun byId(id: String): BuiltinPlugin? = all.firstOrNull { it.id == id }

    fun byTool(toolName: String): BuiltinPlugin? = all.firstOrNull { toolName in it.tools }

    fun byScreen(screenName: String): BuiltinPlugin? = all.firstOrNull { screenName in it.screens }

    /** 每个内置工具必须恰好属于一个组（测试/调试用）。 */
    fun duplicateTools(): List<String> {
        val seen = mutableSetOf<String>()
        return all.flatMap { it.tools }.filter { !seen.add(it) }
    }

    /** 每个页面最多只能由一个内置插件提供（否则关一个组另一组还开着，语义不清）。 */
    fun duplicateScreens(): List<String> {
        val seen = mutableSetOf<String>()
        return all.flatMap { it.screens }.filter { !seen.add(it) }
    }

    /** 页面 id 必须存在；拼错时调试 dump/单测能看出来。 */
    fun unknownScreens(): List<String> = all.flatMap { it.screens }.filter { it !in BuiltinPageIds.all }
}

/**
 * 内置插件开关的持久化。和导入插件的 `plugins.json` 分开：内置插件没有目录、没有权限，
 * 只有用户的一个「关/开」决定（`builtin_plugins_disabled` = 关掉的内置插件 id 集合）。
 */
class BuiltinPluginStore(context: Context) {
    private val prefs = context.getSharedPreferences("dsh", Context.MODE_PRIVATE)

    fun disabled(): Set<String> =
        prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()

    fun isEnabled(id: String): Boolean {
        val plugin = BuiltinPlugins.byId(id) ?: return false
        val off = disabled()
        // 默认开的记录在「关掉的集合」里；默认关的（以后会有）记在同一个集合里表示「显式打开」。
        return if (plugin.defaultEnabled) id !in off else id in off
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val plugin = BuiltinPlugins.byId(id) ?: return
        val current = disabled().toMutableSet()
        // M84-D：默认关的插件（悬浮窗示例）反过来记——集合里有 id = 显式打开。
        if (plugin.defaultEnabled) {
            if (enabled) current.remove(id) else current.add(id)
        } else {
            if (enabled) current.add(id) else current.remove(id)
        }
        prefs.edit().putStringSet(KEY, current).apply()
    }

    /** 调试/验收：所有内置插件恢复默认开（`--ez dsh_builtin_reset true`）。 */
    fun resetAll() {
        prefs.edit().remove(KEY).apply()
    }

    /** 工具是否可用（不是内置工具 → 不受内置开关影响）。 */
    fun toolEnabled(toolName: String): Boolean {
        val plugin = BuiltinPlugins.byTool(toolName) ?: return true
        return isEnabled(plugin.id)
    }

    /** 页面是否可用（不是内置页面 → 不受影响，例如核心页「插件」「设置」）。 */
    fun screenEnabled(screenName: String): Boolean {
        val plugin = BuiltinPlugins.byScreen(screenName) ?: return true
        return isEnabled(plugin.id)
    }

    fun ui(): List<BuiltinPluginUi> = BuiltinPlugins.all.map { BuiltinPluginUi(it, isEnabled(it.id)) }

    private companion object {
        const val KEY = "builtin_plugins_disabled"
    }
}
