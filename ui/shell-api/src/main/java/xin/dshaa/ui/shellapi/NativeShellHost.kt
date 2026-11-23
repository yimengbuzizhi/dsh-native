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

    /**
     * M87-C：插件插槽注册表（docs/181 §3.4）。`null` = 这个壳不接受插件插槽；
     * 原生壳在聊天输入条上方 / 工具卡 body / 设置首页读取它渲染 `chat.composer.dock` 等槽位。
     */
    val slotRegistry: SlotRegistry? get() = null

    // ── 聊天：状态 / 回合 / 审批 / 答题 / plan / goal / 日程 ───────────────────────────

    /** 当前会话状态（null = 落地页，还没建草稿）。 */
    val chat: ChatSessionState?

    /**
     * M87-H5：外部入口（目前是插件通知点击）要求回到聊天落地页。
     * 每次请求自增；壳把页面栈清回聊天后调 [consumeLandingChatRequest]。
     */
    val landingChatRequest: Long

    fun consumeLandingChatRequest()

    /** 提交一条输入（`/命令` 也走这里）。返回是否已接受，可包含已保存的排队输入。 */
    fun submit(text: String): Boolean

    /** 停止当前回合。 */
    fun stop()

    /**
     * H1（docs/182）：上一轮被 max_tokens 截断时点「继续生成」。
     *
     * 只对 `TurnOutcome.MAX_TOKENS` 留下的标记生效；点一次消费一次（连点/重发不会重复起轮），
     * 发送未被接受时把标记还原（按钮不丢）。返回是否已接受。
     */
    fun continueTruncated(): Boolean

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

    // ── M87-F：扩展点诊断 / 插件中间件总开关 ─────────────────────────────────────────

    /** 插件页「扩展点」页签的逐链快照（按插件清单顺序）。 */
    val pluginExtensionsUi: List<PluginExtensionUi>

    /** 设置里的「插件中间件」总开关（关 = 工具/请求/存储/事件钩子全部旁路）。 */
    val pluginMiddlewareEnabled: Boolean

    /** 重新计算逐链快照（页签打开时调用，拿到最新命中/耗时）。 */
    fun refreshPluginExtensions()

    fun setPluginMiddlewareEnabled(enabled: Boolean)

    // ── docs/185 P2 / docs/188 P4：Node 插件宿主（实验） ───────────────────────────

    /** 插件页 Node 分区的开关（默认关）。 */
    val nodePluginHostEnabled: Boolean

    /** 宿主状态人话版：`解析运行时 42%` / `已就绪 · 4 个工具 · v26.4.0` / `启动失败：…`。 */
    val nodeHostStateText: String

    /** P4：结构化状态（OFF/IDLE/EXTRACTING/STARTING/READY/FAILED/STOPPED + 失败提示）。 */
    val nodeHostStatus: NodeHostStatusUi

    fun setNodePluginHostEnabled(enabled: Boolean)

    /** 宿主里已经加载的模块 + 各自注册的工具（就绪后才有内容）。 */
    val nodeHostPlugins: List<NodeHostPluginUi>

    /** P4：清单里登记过的 Node 插件（宿主关着也要显示，不能一片空白）。 */
    val nodeHostManifest: List<NodeHostManifestUi>

    /** 宿主日志尾巴（最近 N 行，排障用）。 */
    val nodeHostLogs: List<String>

    /** docs/190-C：全局启动小卡片的进度（非模态；READY 1s 后自动隐藏，FAILED 保持）。 */
    val nodeBootProgress: NodeBootProgressUi

    /** docs/190-C：「重试」（宿主关着时先开开关，开着就直接重载）。 */
    fun retryNodeBoot()

    /** docs/190-C：「取消启动」= 关掉宿主开关（进程停掉、工具名收回）。 */
    fun stopNodeBoot()

    /** docs/190-C：「收起」只隐藏小卡片，不改宿主状态。 */
    fun dismissNodeBoot()

    /** 重启宿主，重新按清单加载插件（也是失败后的「重试启动」）。 */
    fun reloadNodeHost()

    /** 导入一个 Node 插件 zip（解到 `data/plugins/`、写清单、重启宿主）。 */
    fun importNodePlugin(uri: Uri)

    // ── docs/188 P4：统一导入（识别归属 + 可改 + 迁移） ─────────────────────────────

    /** 待确认的导入（null = 没有对话框）。 */
    val pluginImportDraft: PluginImportDraftUi?

    /** 原生 / Node 两侧当前的导入上限事实（导入页原样展示）。 */
    val pluginImportLimitsText: String

    /** 选完 zip 先识别，不直接安装；确认对话框据此显示「原生 / Node」与迁移风险。 */
    fun beginPluginImport(uri: Uri)

    /** 按用户确认的归属安装；`native` / `node` 以外直接拒绝。 */
    fun confirmPluginImport(ownership: String)

    fun cancelPluginImport()

    /**
     * docs/187 P3-c：斜杠输入框命中 Node 插件命令后转发给宿主执行。
     *
     * 返回 false = 没这个命令/宿主没就绪（调用方已经拿到一句人话错误），true = 已受理（异步执行，
     * 结果走 command notice）。权限与审计沿用 P2 的 `node-audit.jsonl`。
     */
    fun invokeNodeCommand(name: String, rawInput: String): Boolean

    // ── docs/187 P3-d：预设（纯数据：模型 + 提示词段 + 工具 + 权限） ─────────────────

    /**
     * 角色页「预设」区的可选列表。预设落在 `files/node/data/presets.json`，
     * **不进 session.v4**；套用只收窄选择，不改插件本身。
     */
    val presetTargetsUi: List<xin.dshaa.core.plugins.PluginPreset>

    /** 当前套用的预设 id（null = 没套用）。 */
    val activePresetTargetUi: String?

    /** 最近一次预设操作的一句话结果（保存/套用/删除/清除）。 */
    val presetNoticeUi: String?

    /** 套用；返回 false 时 [presetNoticeUi] 带原因（模型通道不可用等）。 */
    fun applyPreset(id: String): Boolean

    /** 清除套用（收窄解除；模型选择保留）。 */
    fun clearPreset()

    /** 把当前状态（模型/提示词段/工具/权限）存成预设；名字非法返回 false。 */
    fun savePresetFromCurrent(name: String): Boolean

    fun deletePreset(id: String): Boolean

    // ── M88-E：AI 全控制插件（审计 / 撤销；docs/190 §3-F 已永久删除总开关） ──────────

    /** 撤销本进程最近一次 AI 授予（或按审计回放找最近一条）。 */
    fun undoAiPluginGrant()

    /** 插件详情底部：该插件最近 5 条审计（AI 与用户操作）。 */
    fun pluginAuditLines(pluginId: String): List<String>

    /** 权限行标「AI 授予」：按审计回放当前仍由 AI 授予的权限。 */
    fun aiGrantedPermissions(pluginId: String): Set<String>


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

    /** docs/190-E：鲸歌内置提示词规则（`builtin.prompt.core`）是否启用；默认 true。 */
    fun builtinPromptCoreEnabled(): Boolean

    /** 界面壳候选，包含停用或缺授权项；启用与使用中分别展示。 */
    val shellTargetsUi: List<RoleTargetUi>

    /**
     * 是否启用「液态玻璃」界面壳（`docs/187` §4）。
     *
     * 液态玻璃**不是**替换原生界面的新界面，而是同一份页面代码的可选外观：
     * `false`（默认）= 原生 UI，色板/背景/卡片与 0.3.5 一致；
     * `true` = 玻璃色板 + 墙面背景 + 玻璃卡片。由角色页「界面：液态玻璃（实验）」切换，
     * 持久化在 `dsh_shell = liquid`。
     */
    val liquidUiEnabled: Boolean

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
