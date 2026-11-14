package xin.dshaa.ui.shellapi

import android.net.Uri
import java.io.File
import xin.dshaa.core.input.FileCandidate
import xin.dshaa.core.model.ChatMessage
import xin.dshaa.core.model.ScheduledTask
import xin.dshaa.core.model.SessionSummary
import xin.dshaa.core.plugins.PluginRecord
import xin.dshaa.core.session.TrashedSession
import xin.dshaa.core.skill.SkillRoot
import xin.dshaa.core.skill.SkillSummary
import xin.dshaa.core.tools.AskAnswer
import xin.dshaa.ui.shellapi.chat.ChatSessionState
import xin.dshaa.ui.shellapi.chat.JobUi
import xin.dshaa.ui.shellapi.chat.SessionBadge
import xin.dshaa.ui.shellapi.chat.SubagentUi
import xin.dshaa.ui.shellapi.chat.WaitingSession
import xin.dshaa.ui.shellapi.data.BuiltinPluginUi
import xin.dshaa.ui.shellapi.data.OperitBundledPackage
import xin.dshaa.ui.shellapi.data.SettingsStore
import xin.dshaa.ui.shellapi.data.WorkspaceUi
import xin.dshaa.ui.shellapi.remote.ShellRemote

/**
 * M85-C3-1/C3-2：**壳与宿主之间唯一的界面服务面**（`ui/shell` 目录下的代码只依赖它，不再依赖 `AppViewModel`）。
 *
 * 背景（`docs/155`）：C2 之后 22 个页面组件已经只接数据 + 回调 lambda，但壳自己
 * （`NativeShellContent` / `NativePageDispatch` / `DshDrawer`）仍直接拿 `AppViewModel` 的 109 个成员，
 * 这会让 `ui:native-shell` 模块反向依赖 `app`。本接口把这些成员**原样**抽出来：
 *
 * - **只读状态 + 动作**：所有状态属性都是 `val`（实现类 `var ... private set` 满足 `val`），
 *   读它仍触发 Compose 重组；不复制快照、不解释业务，方法只转发；
 * - **行为零改动**：`AppViewModel : NativeShellHost` 的成员只是加 `override`，签名/语义不变；
 * - **扁平 v1**：分组子接口会把 109 处调用点从 `vm.x()` 改成 `host.chat.x()`，纯机械 diff；
 *   分组推迟到 C3-3 真正搬 `ui:native-shell`、需要给第三方 native 壳收窄面时再做（见 `docs/155` §2）；
 * - **C3-2 已搬进 `ui:shell-api`**：本接口和它引用的状态类型（`chat`/`data`/`remote` 子包）
 *   只依赖 `core`，实现类仍在 app；`ShellPage`/`NativeShell` 同包。
 * - **C3-4**：`page/v1` 注册表 [ShellPageRegistry] 也在同包（壳声明页 + M84-G 插件整页并成一张表），
 *   宿主只把插件整页清单交给注册表，不再持有页面清单。
 *
 * 迁移顺序（`docs/155` §3）：C3-1 抽接口 → C3-2 搬 `ui:shell-api` 模块（本文件）→ C3-3 搬 `ui:native-shell`。
 */
interface NativeShellHost {

    /** 当前本机/远程来源的共享聊天入口；旧 chat/submit 等成员继续只代表本机。 */
    val conversation: ShellConversation

    // ── 聊天：状态 / 回合 / 审批 / 答题 / plan / goal / 日程 ───────────────────────────

    /** 当前会话状态（null = 落地页，还没建草稿）。 */
    val chat: ChatSessionState?

    /** 提交一条输入（`/命令` 也走这里）。返回是否已接受，可包含已保存的排队输入。 */
    fun submit(text: String): Boolean

    /** 停止当前回合。 */
    fun stop()

    fun cancelQueuedPrompt(id: String)

    fun resumeQueuedPrompts()

    /** 审批：允许/拒绝；`always` = 本次会话总是允许。 */
    fun resolveApproval(allow: Boolean, always: Boolean = false)

    /** 回答 ask_user_question（空数组 = 跳过）。 */
    fun resolveQuestions(answers: List<AskAnswer>)

    /** 切换计划模式。 */
    fun setPlanMode(active: Boolean)

    /** 当前会话的提醒/日程（会话内展示）。 */
    fun currentSchedules(): List<ScheduledTask>

    fun deleteSchedule(id: String)

    suspend fun pauseGoal(): String?

    suspend fun resumeGoal(): String?

    suspend fun editGoal(objective: String?, maxGoalRounds: Int?): String?

    suspend fun clearGoal(): String?

    // ── 聊天：附件 / 模型 / 子代理回放 / 消息分支 ──────────────────────────────────────

    /** Capture target when launching asynchronous pickers; null never falls back to a later chat. */
    fun attachImage(uri: Uri, target: ChatSessionState? = chat)

    fun attachPhoto(file: File, target: ChatSessionState? = chat)

    fun attachScreenshot(target: ChatSessionState? = chat)

    fun attachFile(uri: Uri, target: ChatSessionState? = chat)

    /** 文本类附件进入发起时的待发草稿，生成中也可添加；只读或失效会话返回 false。 */
    fun attachTextFile(name: String, text: String, target: ChatSessionState? = chat): Boolean

    fun removePendingImage(attachmentId: String)

    fun removePendingFile(name: String)

    fun imagePathFor(attachmentId: String): String?

    fun switchModel(model: String)

    fun openSubagentSession(id: String)

    fun branchFromMessage(message: ChatMessage): String?

    fun regenerateFromMessage(message: ChatMessage): String?

    suspend fun fileCandidates(query: String): List<FileCandidate>

    fun dismissCommandNotice()

    /** 其它会话在等审批/回答时，顶部横幅一键切过去。 */
    fun focusSession(id: String)

    fun waitingElsewhere(): List<WaitingSession>

    // ── 会话列表 / 回收站 ────────────────────────────────────────────────────────────

    val sessions: List<SessionSummary>

    fun openSession(summary: SessionSummary)

    fun newChat()

    fun closeChat()

    fun renameSession(id: String, title: String)

    fun deleteSession(id: String)

    fun deleteSessionPermanently(id: String)

    val trashUi: List<TrashedSession>

    val trashBusy: Boolean

    fun refreshTrash()

    fun restoreTrashed(dirName: String)

    fun purgeTrashed(dirName: String)

    fun purgeAllTrashed()

    fun refreshSessions()

    val canGoBackFromChat: Boolean

    fun badgeFor(sessionId: String?): SessionBadge?

    // ── 工作区 ──────────────────────────────────────────────────────────────────────

    val workspace: File

    val workspacePath: String

    val currentWorkspace: WorkspaceUi

    val workspaces: List<WorkspaceUi>

    fun switchWorkspace(path: String): String?

    fun createWorkspace(name: String, path: String? = null): String?

    fun renameWorkspace(id: String, name: String)

    fun deleteWorkspace(id: String): String?

    // ── 设置（助手/模型/主题等只读入口 + 计数）────────────────────────────────────────

    val settings: SettingsStore

    /** app 版本号（壳的「关于/设置」页展示用；壳模块不读宿主 BuildConfig）。 */
    val appVersionName: String

    val appVersionCode: Int

    val toolCount: Int

    // ── 用户插件 / 内置插件 / 插件整页 ───────────────────────────────────────────────

    val pluginsUi: List<PluginRecord>

    val builtinPluginsUi: List<BuiltinPluginUi>

    val pluginInjected: Map<String, Set<String>>

    val pluginsBusy: Boolean

    val pluginNotice: String?

    /** 调试通道 `dsh_plugin_detail` 打开详情用。 */
    val debugPluginDetailId: String?

    fun refreshPlugins()

    fun importPlugin(uri: Uri)

    fun setPluginEnabled(id: String, enabled: Boolean)

    fun reloadPlugin(id: String)

    fun deletePlugin(id: String)

    fun grantPluginPermission(id: String, raw: String)

    fun savePluginConfig(id: String, jsonText: String)

    fun dismissPluginNotice()

    fun openClientPluginWindow(record: PluginRecord)

    fun openPluginPageEntry(entry: PluginPageEntry)

    /**
     * M84-K/M85-C3-4：启用中 + 有 `dsh.client` + `page:true` 的插件整页路由。
     *
     * C3-4 起**包含**与内置页同名的 route（覆盖项）；抽屉/路由解析由 [ShellPageRegistry]
     * 分成「独立插件页」和「覆盖内置页」两类。
     */
    fun pluginPageEntries(): List<PluginPageEntry>

    /**
     * M84-G：当前路由是否由插件提供整页（id 同名 = 替换内置页）。
     *
     * C3-4 起正常路径走 [ShellPageRegistry.resolve]；本方法保留给还没接注册表的老壳实现。
     */
    fun pluginPageFor(pageId: String?, subId: String?): PluginPageTarget?

    fun setBuiltinPluginEnabled(id: String, enabled: Boolean)

    /** 这个页面现在是否允许进入（内置页面插件被关掉时 false）。 */
    fun builtinScreenEnabled(screenName: String): Boolean

    /** 界面壳候选，包含停用或缺授权项；启用与使用中分别展示。 */
    val shellTargetsUi: List<RoleTargetUi>

    /** 用户选择可用壳；失败保留当前界面并给出插件提示。 */
    fun selectShell(value: String): Boolean

    fun restoreBuiltinShell()

    val loadingTargetsUi: List<RoleTargetUi>
    val loadingSafetyNotice: String?
    fun selectLoading(value: String): Boolean
    fun restoreBuiltinLoading()

    // ── 回合插件（agent/v1，M85-D）────────────────────────────────────────────────

    /** 全部可选回合（内置 dsH 引擎 + 启用的导入插件声明的 `ctx.agent.register`；含停用项用于解释原因）。 */
    val agentTargetsUi: List<AgentTargetUi>

    /** 当前生效的回合；null = 没有可用回合（聊天页显示安全提示）。 */
    val activeAgentUi: AgentTargetUi?

    /** 无可用回合时的安全提示文案（null = 正常）。 */
    val agentSafetyNotice: String?

    /** 切回合（`native`/`builtin.agent.dsh`/插件 id/`插件id#回合id`）；返回 false = 解析失败，原因见 [agentSafetyNotice]。 */
    fun setActiveAgent(id: String): Boolean

    /** 一键恢复内置 dsH 引擎（重新打开 `builtin.agent.dsh` 并选中它）。 */
    fun restoreBuiltinAgent()

    /** 调试通道 `dsh_agent_list <路径>`：回合插件清单 + 当前解析落盘。 */
    fun dumpAgents(dumpPath: String?)

    // ── 存储 / 提示词 / 模型插件（storage/v1、prompt/v1、model/v1，M85-E）────────────

    /** 全部可选存储（内置 JSONL + 插件 `ctx.storage.register`）。 */
    val storageTargetsUi: List<RoleTargetUi>

    /** 当前生效的存储；null = 没有可用存储（聊天页显示存储安全提示）。 */
    val activeStorageUi: RoleTargetUi?

    /** 存储不可用/只读时的提示（null = 正常）。 */
    val storageSafetyNotice: String?

    fun setActiveStorage(id: String): Boolean

    /** 重新打开 `builtin.storage.jsonl` 并选中它。 */
    fun restoreBuiltinStorage()

    /** 全部可选提示词实现（内置 dsH + 插件 `ctx.systemPrompt.register`）。 */
    val promptTargetsUi: List<RoleTargetUi>

    val activePromptUi: RoleTargetUi?

    val promptSafetyNotice: String?

    fun setActivePrompt(id: String): Boolean

    fun restoreBuiltinPrompt()

    /** 全部可选模型通道（内置 deepseek/openai + 插件 `ctx.llm.register`）。 */
    val modelTargetsUi: List<RoleTargetUi>

    val activeModelUi: RoleTargetUi?

    fun setActiveModel(id: String): Boolean

    /** 恢复「跟随协议」（清掉用户选的 provider id）。 */
    fun restoreBuiltinModel()

    /** 调试通道 `dsh_roles_list <路径>`：存储/提示词/模型解析落盘。 */
    fun dumpRoles(dumpPath: String?)

    // ── 能力插件（capability/v1，M85-F）────────────────────────────────────────────

    /**
     * 当前能力清单（内置平台能力 + 启用插件声明的 `ctx.capability.register`），
     * 每个 id 只列一次（插件优先内置）。
     */
    val capabilityUi: List<CapabilityUi>

    /** 最近一次能力调用的结果/错误（插件页底部一行；null = 还没调用过）。 */
    val capabilityNotice: String?

    /** 调用一个能力（异步；结果写回 [capabilityNotice]）。 */
    fun invokeCapability(id: String)

    /** 调试通道 `dsh_capability_list <路径>`：能力清单 + 提供方解析落盘。 */
    fun dumpCapabilities(dumpPath: String?)

    /** 调试通道 `dsh_capability_test <路径>`：等常驻运行时后依次调用内置/插件/缺失能力并落盘。 */
    fun runCapabilityTest(dumpPath: String?)

    // ── Operit 包 ───────────────────────────────────────────────────────────────────

    val operitPackagesUi: List<OperitBundledPackage>

    val operitPackagesBusy: Boolean

    fun loadOperitPackages()

    fun installOperitPackage(pluginId: String)

    fun setOperitPackageEnabled(pluginId: String, enabled: Boolean)

    // ── 技能 ────────────────────────────────────────────────────────────────────────

    val skillsUi: List<SkillSummary>

    val skillRoots: List<SkillRoot>

    val skillsComplete: Boolean

    val skillsWarnings: List<String>

    val skillNotice: String?

    fun refreshSkills()

    fun importSkill(uri: Uri)

    fun setSkillEnabled(name: String, enabled: Boolean)

    fun deleteSkill(skill: SkillSummary)

    fun dismissSkillNotice()

    // ── 后台任务 / 子代理 ────────────────────────────────────────────────────────────

    val jobs: List<JobUi>

    fun refreshJobs()

    fun stopJob(id: String): String?

    fun peekJob(id: String): JobUi?

    val subagents: List<SubagentUi>

    val subagentsLoading: Boolean

    val agentMaxDepth: Int

    fun refreshSubagents()

    fun stopSubagent(id: String): String?

    // ── 设备 / 远程 / 路径 ──────────────────────────────────────────────────────────

    val remote: ShellRemote

    val sessionsDir: File

    val filesDirPath: String

    // ── 引导 / 调试通道 / 模型探测 ───────────────────────────────────────────────────

    val onboardingDone: Boolean

    fun completeOnboarding()

    /** 调试通道 `dsh_open_screen`：启动/热启落到指定页面（壳自己解释字符串）。 */
    val debugScreenRequest: String?

    fun consumeDebugScreen()

    /** 调试通道 `dsh_import_zip`：直接把旧版导入页顶到前面。 */
    val importRequest: String?

    fun consumeImportRequest()

    fun onRestoredFromBackup()

    suspend fun fetchModels(): Result<List<String>>

    suspend fun testConnection(): String
}
