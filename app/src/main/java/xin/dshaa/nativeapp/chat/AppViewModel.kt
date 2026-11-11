package xin.dshaa.nativeapp.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xin.dshaa.core.agents.AgentNotice
import xin.dshaa.core.agents.AgentRecord
import xin.dshaa.core.agents.AgentRegistry
import xin.dshaa.core.agents.AgentTerminal
import xin.dshaa.core.attachment.AttachmentStore
import xin.dshaa.core.attachment.ImageNormalizer
import xin.dshaa.core.engine.Compaction
import xin.dshaa.core.engine.EngineDeps
import xin.dshaa.core.engine.ImageOffload
import xin.dshaa.core.engine.PlanModeState
import xin.dshaa.core.engine.SessionGateway
import xin.dshaa.core.engine.TimeContext
import xin.dshaa.core.engine.TurnEngine
import xin.dshaa.core.engine.TurnInput
import xin.dshaa.core.engine.TurnOutcome
import xin.dshaa.core.engine.TurnStopState
import xin.dshaa.core.engine.TurnUi
import xin.dshaa.core.engine.UserNotice
import xin.dshaa.core.input.CommandInvocation
import xin.dshaa.core.input.DSH_COMMANDS
import xin.dshaa.core.input.FileCandidate
import xin.dshaa.core.input.FileReferenceSearch
import xin.dshaa.core.input.findCommand
import xin.dshaa.core.input.parseApprovalArg
import xin.dshaa.core.input.parseCommandLine
import xin.dshaa.core.jobs.JobOutcome
import xin.dshaa.core.jobs.JobRegistry
import xin.dshaa.core.jobs.JobStatus
import xin.dshaa.core.llm.LlmClient
import xin.dshaa.core.llm.LlmConfig
import xin.dshaa.core.llm.LlmEvent
import xin.dshaa.core.llm.OpenAiClient
import xin.dshaa.core.llm.WebSearchClient
import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.ChatMessage
import xin.dshaa.core.model.ContentBlock
import xin.dshaa.core.model.GoalAuthority
import xin.dshaa.core.model.GoalException
import xin.dshaa.core.model.GoalView
import xin.dshaa.core.model.ImageOffloadTarget
import xin.dshaa.core.model.ImageRef
import xin.dshaa.core.model.MessageRole
import xin.dshaa.core.model.markOffloadedImages
import xin.dshaa.core.model.ScheduleException
import xin.dshaa.core.model.ScheduleTiming
import xin.dshaa.core.model.ScheduledTask
import xin.dshaa.core.model.SessionSummary
import xin.dshaa.core.workflow.WorkflowChildHost
import xin.dshaa.core.workflow.WorkflowChildOutcome
import xin.dshaa.core.workflow.WorkflowChildRequest
import xin.dshaa.core.workflow.WorkflowEngine
import xin.dshaa.core.workflow.WorkflowException
import xin.dshaa.core.workflow.WorkflowLimits
import xin.dshaa.core.workflow.WorkflowMeta
import xin.dshaa.core.workflow.WorkflowObserver
import xin.dshaa.core.workflow.WorkflowRender
import xin.dshaa.core.workflow.WorkflowStopReason
import xin.dshaa.core.tools.workflow.WorkflowHost
import xin.dshaa.core.tools.workflow.WorkflowToolOutcome
import xin.dshaa.core.model.ToolCall
import xin.dshaa.core.parity.ParityJson
import xin.dshaa.core.plugins.PluginRecord
import xin.dshaa.core.parity.RunRecorder
import xin.dshaa.core.parity.SseCaptureRecorder
import xin.dshaa.core.prompt.PromptPack
import xin.dshaa.core.protocol.ApiMessage
import xin.dshaa.core.protocol.ApiRequest
import xin.dshaa.core.protocol.ApiTool
import xin.dshaa.core.protocol.ContentBlock as ApiBlock
import xin.dshaa.core.session.GoalActivationState
import xin.dshaa.core.session.GoalIdleDecision
import xin.dshaa.core.session.GoalRoundDriver
import xin.dshaa.core.session.GoalRoundRef
import xin.dshaa.core.session.GoalStore
import xin.dshaa.core.session.GoalWrapup
import xin.dshaa.core.session.ScheduleStore
import xin.dshaa.core.session.SessionCodec
import xin.dshaa.core.session.SessionRepository
import xin.dshaa.core.session.SessionSession
import xin.dshaa.core.skill.FileSystemSkills
import xin.dshaa.core.skill.SkillCatalog
import xin.dshaa.core.skill.SkillFrontmatter
import xin.dshaa.core.skill.SkillLoad
import xin.dshaa.core.skill.SkillRoot
import xin.dshaa.core.skill.SkillSummary
import xin.dshaa.core.skill.invokedSkillNames
import xin.dshaa.core.skill.isSkillName
import xin.dshaa.core.skill.renderSkillContent
import xin.dshaa.core.tools.DefaultTools
import xin.dshaa.core.tools.AgentEntry
import xin.dshaa.core.tools.AgentHost
import xin.dshaa.core.tools.AgentSend
import xin.dshaa.core.tools.AgentStart
import xin.dshaa.core.tools.GoalNotice
import xin.dshaa.core.tools.GoalUpdateResult
import xin.dshaa.core.tools.ImageRoute
import xin.dshaa.core.tools.ImageStoreException
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolRegistry
import xin.dshaa.core.tools.fs.sniffImageMediaType
import xin.dshaa.core.tools.schedule.ScheduleTime
import xin.dshaa.nativeapp.data.BackupManager
import xin.dshaa.nativeapp.data.BundledSkills
import xin.dshaa.nativeapp.data.PluginStore
import xin.dshaa.nativeapp.data.PluginToolRuntime
import xin.dshaa.nativeapp.data.SettingsStore
import xin.dshaa.nativeapp.data.WorkspaceStore
import xin.dshaa.nativeapp.fish.BigFish
import xin.dshaa.nativeapp.data.WorkspaceUi
import xin.dshaa.nativeapp.device.DeviceShell
import xin.dshaa.nativeapp.device.a11y.A11y
import com.ai.assistance.showerclient.ShellIdentity
import xin.dshaa.nativeapp.device.tools.AndroidDeviceTools
import xin.dshaa.nativeapp.memory.MemoryTools
import xin.dshaa.nativeapp.device.tools.TerminalTools
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.ZoneId
import java.util.UUID

private const val MAX_ROUNDS = 12
private const val MAX_LOG_TEXT = 200_000

/** 委托深度上限（上游 `dsh-subagent` 的默认 maxDepth = 1：根能委派一层，子代理不能再委派）。 */
private const val MAX_AGENT_DEPTH = 1

/** 只读会话的来源：旧版备份导入副本 / 子代理会话回放（聊天页底部说明文案不同）。 */
const val READ_ONLY_IMPORT = "import"
const val READ_ONLY_SUBAGENT = "subagent"

/** 只读工具：审批策略为「每次询问」时也不弹窗（与上游权限分类一致）。 */
private val READ_ONLY_TOOLS = setOf(
    "read", "read_image", "glob", "grep", "working_directory", "web_fetch", "web_search", "ask_user_question",
    "todo_write", "get_goal", "create_goal", "update_goal", "schedule_list", "job_list", "job_output",
    "list_agents", "send_message",
    // 计划审批：exit_plan_mode 自己就是一次用户审批（plan review 对话框），不再叠一层工具审批
    "exit_plan_mode",
    // 只读查询：报 Android 无 payload，不需要审批
    "load_workspace_dependencies",
    // 技能加载：只读 SKILL.md，内容就是它自己的说明
    "skill",
)

/**
 * 应用级状态：设置 + 会话仓库 + 当前聊天 + 工具循环。
 * 运行时暂时在 ViewModel；M2 后挪到前台服务持有的 ChatRuntimeHolder（01 §五.4）。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    // ---- 插件（M26：本地导入 + 试运行） ----
    //
    // 状态必须声明在 init 之前：init 里的调试导入（`--es dsh_plugin_zip`）会立刻读写它们。
    // 存储：`files/plugins/<id>/` + `files/plugins.json`。

    private val pluginStore = PluginStore(app)

    var pluginsUi by mutableStateOf(pluginStore.list())
        private set

    var pluginsBusy by mutableStateOf(false)
        private set

    /** 插件页顶部的一次性提示（导入/删除结果）。 */
    var pluginNotice by mutableStateOf<String?>(null)
        private set

    /** 插件工具接入（M27）：已启用且 schema 可用的工具会注册进 `ToolRegistry`。 */
    private val pluginToolRuntime = PluginToolRuntime(
        notifier = { title, body ->
            xin.dshaa.nativeapp.data.PluginNotifier.notify(app, title, body)
        },
        // M67：Operit 包的 Tools.* 需要设备/无障碍/截图等 app 侧能力。
        hostBridge = xin.dshaa.nativeapp.data.AndroidPluginHostBridge(app),
    )

    /** 当前由插件注册进工具表的名字（刷新时先注销这一批，再按最新清单注册）。 */
    private val pluginToolNames = mutableSetOf<String>()

    /** 当前请求可用的插件工具 schema（每次请求现取，随 `ToolRegistry` 同步刷新）。 */
    @Volatile
    private var pluginApiTools: List<ApiTool> = emptyList()

    /** 插件页展示：插件 id → 已接入对话的工具名（未启用/参数认不出 = 不在表里）。 */
    var pluginInjected by mutableStateOf<Map<String, Set<String>>>(emptyMap())
        private set

    /** 调试/截图用：启动即打开某个插件详情（`--es dsh_plugin_detail <id>`，只 debug 包）。 */
    var debugPluginDetailId by mutableStateOf<String?>(null)
        private set

    // ---- M67：随包内置的 Operit ToolPkg 目录（包管理 → 包） ----

    private val operitBundled = xin.dshaa.nativeapp.data.OperitBundledPackages(app)

    /** 「包」tab 的列表；第一次进 tab 时从 assets 扫出来。 */
    var operitPackagesUi by mutableStateOf<List<xin.dshaa.nativeapp.data.OperitBundledPackage>>(emptyList())
        private set

    var operitPackagesBusy by mutableStateOf(false)
        private set

    val settings = SettingsStore(app.getSharedPreferences("dsh", 0))
    private val prefs = app.getSharedPreferences("dsh", 0)
    private val repo = SessionRepository(File(app.filesDir, "sessions"))

    /** 原生备份 v1（M35，docs/59）：导出/恢复会话、插件、附件、设置（不含密钥）。 */
    private val backupManager = BackupManager(app)

    /**
     * 首次启动的使用指导（2026-11-07）：引导页点「开始使用/跳过」置 true。
     *
     * 放 prefs 而不是「有没有会话」：旧版导入的会话可能很多，但用户从没看过引导；
     * 反过来清数据重装也应该重新看一次。
     */
    var onboardingDone by mutableStateOf(prefs.getBoolean("onboarding_done", false))
        private set

    fun completeOnboarding() {
        prefs.edit().putBoolean("onboarding_done", true).apply()
        onboardingDone = true
    }

    /** 调试通道：重新看一遍引导（`--ez dsh_reset_onboarding true`）。 */
    fun resetOnboarding() {
        prefs.edit().putBoolean("onboarding_done", false).apply()
        onboardingDone = false
    }

    /**
     * 工作区（2026-11-07，RikkaHub 外壳）：会话按 `header.cwd` 分工作区，当前工作区决定
     * 工具的可写根、技能根、@file 索引与新会话的 cwd。默认 `files/workspace`。
     */
    private val workspaceStore = WorkspaceStore(File(app.filesDir, "workspaces.json"), prefs, app.filesDir)
    var workspace: File = File(workspaceStore.currentPath()).apply { mkdirs() }
        private set

    /** 当前工作区下的会话（抽屉/会话页只显示这一份；全部会话另存 [allRootSessions]）。 */
    var workspaces by mutableStateOf<List<WorkspaceUi>>(emptyList())
        private set

    /** 图片附件（内容寻址）；会话只存 attachmentId，请求时再读回字节。 */
    private val attachments = AttachmentStore(File(app.filesDir, "attachments/v1"))

    /** 会话内提醒（宿主级，`files/schedules.json`；不进 v4 会话）。 */
    private val schedules = ScheduleStore(File(app.filesDir, "schedules.json"))

    /**
     * 技能系统（M19，对齐上游 `dsh-skill-filesystem`）：每个请求现扫这些根
     * `<workspace>/.dsh/skills` → `<workspace>/.agents/skills` → `files/skills` → `files/agents/skills`。
     * 目录消息只在**变化**时落一条 `source.kind:"skill-catalog"` 的 user 消息（空目录不占上下文）。
     * 切换工作区时整体重建（项目级技能根跟着 cwd 走）。
     */
    private var skills = FileSystemSkills(FileSystemSkills.androidRoots(workspace, app.filesDir))

    /** `@file` 候选（M21）：有界索引 + 模糊排序；工具结果/轮次结束 [invalidate]。切换工作区重建。 */
    private var fileReferenceSearch: FileReferenceSearch = FileReferenceSearch(workspace)

    /** 斜杠命令生命周期 id：上游 `cmd-<instanceToken>-<seq>`（进程内单调，恢复日志不会重复）。 */
    private val commandInstanceToken: String = UUID.randomUUID().toString().replace("-", "").take(8)
    private var commandSeq: Int = 0

    /**
     * 后台任务表（`bash run_in_background` + `job_*`）。owner = 当前会话 id；
     * 任务跑在 App 级 scope 上，切会话/停一轮都不杀后台任务（上游语义）。
     */
    private val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobRegistry = JobRegistry(jobScope)

    /**
     * 子代理运行时（M16，对齐上游 `dsh-subagent` 的进程内 provider）：
     * - 代理表/深度/通知队列在 `core:agents`；真正跑子代理的是**独立 TurnEngine + 独立会话文件**
     *   （header.parentSession / origin=subagent / delegationDepth），复用同一套工具与提示词；
     * - 子会话不进主会话列表（header.parentSession != null），留给后续「子代理」面板；
     * - 跑在 App 级 scope：切会话/切页面不打断子代理（与后台任务一致）。
     */
    private val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val agentRegistry = AgentRegistry(agentScope, maxDepth = MAX_AGENT_DEPTH)
    private val childRuntimes = java.util.concurrent.ConcurrentHashMap<String, ChildRuntime>()
    private val childGoalActivations = java.util.concurrent.ConcurrentHashMap<String, GoalActivationState>()

    // ---- 门 2 对拍：仅调试通道会打开，生产路径全为 null（零开销） ----
    private val captureRecorder: SseCaptureRecorder? = prefs.getString("debug_dump_sse", null)
        ?.takeIf { it.isNotBlank() && it != "off" }
        ?.let { SseCaptureRecorder(File(it)) }
    private val runRecorder: RunRecorder? = prefs.getString("debug_parity_run", null)
        ?.takeIf { it.isNotBlank() && it != "off" }
        ?.let { RunRecorder(File(it)) }
    private var toolNo = 0

    private val client = LlmClient(tapFactory = captureRecorder?.let { rec -> { rec.newTap() } })
    private val openAiClient = OpenAiClient()
    private val promptPack by lazy { PromptPack.load() }

    /**
     * 溢出恢复验收（M45，仅 debug 包会写这个 prefs）：`--es dsh_overflow_once <报错文案>` 让
     * **下一条**模型请求假装返回「上下文超限」（不真发网络），用来在锁屏/无 UI 时验证
     * 「识别溢出 → 最大压缩 → 同一步重发」。消费一次就清掉，正常使用永远是 null。
     */
    private val debugOverflowOnce: String? =
        prefs.getString("debug_overflow_once", null)?.takeIf { it.isNotBlank() }
    private val debugOverflowPending = java.util.concurrent.atomic.AtomicBoolean(debugOverflowOnce != null)

    /** Operit 的设备工具（无障碍/手势/截图/launch_app）：Android 实现，追加在上游 32 个工具之后。 */
    private val androidDeviceTools = AndroidDeviceTools(app)

    /**
     * Operit 的终端工具（PTY 会话，docs/77）：建会话 / 跑命令 / 读屏幕 / 写输入 / Ctrl+C / 关会话。
     * 与 `bash`（每次新进程）互补：需要 `cd`、环境变量、交互式程序时用它。
     */
    private val terminalTools = TerminalTools(app)

    /** 记忆库工具（M56，照搬 Operit 8 个记忆工具）。 */
    private val memoryTools = MemoryTools(app)

    private val registry: ToolRegistry =
        ToolRegistry(DefaultTools.registry().tools + androidDeviceTools.tools + terminalTools.tools + memoryTools.tools)

    /**
     * goal 三件套的后端：读写**指定会话**的 goal/change 事件（见 GoalStore）。
     * 根会话与子代理各自一份（同一个打开的会话必须共用一份 activation；进程内、不落盘）。
     */
    private fun goalHostFor(current: () -> SessionSession?, activation: () -> GoalActivationState): xin.dshaa.core.tools.GoalHost =
        object : xin.dshaa.core.tools.GoalHost {
            override fun current(): GoalView? = current()?.let { runCatching { goalStore(it, activation()).current() }.getOrNull() }

            override fun create(objective: String, maxGoalRounds: Int?): GoalView {
                val session = current() ?: throw GoalException("GOAL_NOT_FOUND", "no active session")
                return goalStore(session, activation()).create(objective, maxGoalRounds)
            }

            override fun authority(): GoalAuthority =
                current()?.let { goalStore(it, activation()).authority() } ?: GoalAuthority.None

            override fun update(
                goalId: String,
                revision: Int,
                action: String,
                objective: String?,
                maxGoalRounds: Int?,
                blockedReason: String?,
                authority: GoalAuthority,
                minBlockedRounds: Int,
            ): GoalUpdateResult {
                val session = current() ?: throw GoalException("GOAL_NOT_FOUND", "no active session")
                val view = goalStore(session, activation()).update(
                    goalId = goalId,
                    revision = revision,
                    action = action,
                    objective = objective,
                    maxGoalRounds = maxGoalRounds,
                    blockedReason = blockedReason,
                    minBlockedRounds = minBlockedRounds,
                )
                // 收尾指令：只有自动续轮里 complete/blocked 才注入（上游 deferContext）
                val notice = when {
                    authority !is GoalAuthority.GoalRound -> null
                    action == "complete" ->
                        GoalNotice(GoalWrapup.complete(view.goal.objective), GoalWrapup.summary("complete", view.goal.objective))
                    action == "blocked" ->
                        GoalNotice(GoalWrapup.blocked(view.goal.objective, blockedReason.orEmpty()), GoalWrapup.summary("blocked", view.goal.objective))
                    else -> null
                }
                return GoalUpdateResult(view, notice)
            }
        }

    // 工具执行归属：默认看前台会话；每个 [LiveChat] 会另建自己的 goalHost（并行轮次互不串）
    private val goalHost = goalHostFor(
        { current?.handle ?: handle },
        { current?.goalActivation ?: goalActivation },
    )

    /** 同一个打开的会话必须共用一份 activation（根 = `goalActivation`，子代理 = 各自的）。 */
    private fun goalStore(session: SessionSession, activation: GoalActivationState = goalActivation): GoalStore =
        GoalStore(session, activation = activation)

    /** `web_search` 的后端：DeepSeek 原生搜索（一次 Messages 调用）。 */
    private val webSearchHost = object : xin.dshaa.core.tools.WebSearchHost {
        override suspend fun search(query: String): xin.dshaa.core.tools.WebSearchHost.Outcome {
            val outcome = WebSearchClient(config = llmConfig()).search(query)
            return xin.dshaa.core.tools.WebSearchHost.Outcome(
                sources = outcome.sources.map {
                    xin.dshaa.core.tools.WebSearchHost.Source(
                        url = it.url,
                        title = it.title,
                        snippet = it.snippet,
                        publishedAt = it.publishedAt,
                    )
                },
                answer = outcome.answer,
            )
        }
    }

    /**
     * `schedule_*` 四件套的后端：提醒属于 App（宿主），会话只是投递目标。
     * 时间计算在 `ScheduleTime`，持久化在 `ScheduleStore`，这里只做「当前会话」适配。
     * 子代理传自己的会话 id（提醒也跟随子会话，不串到父会话）。
     */
    private fun scheduleHostFor(currentId: () -> String?): xin.dshaa.core.tools.ScheduleHost =
        object : xin.dshaa.core.tools.ScheduleHost {
            override fun sessionId(): String? = currentId()

            override fun list(): List<ScheduledTask> =
                currentId()?.let { schedules.listForSession(it) } ?: emptyList()

            override fun create(title: String, prompt: String, timing: ScheduleTiming): ScheduledTask {
                val sessionId = requireSession()
                val now = System.currentTimeMillis()
                val next = ScheduleTime.nextOccurrence(timing, now, now)
                    ?: throw ScheduleException("not_future", "the timing has no future occurrence")
                return schedules.create(sessionId, title, prompt, timing, next, now)
            }

            override fun update(id: String, title: String?, prompt: String?, timing: ScheduleTiming?): ScheduledTask {
                val sessionId = requireSession()
                val now = System.currentTimeMillis()
                val existing = schedules.listForSession(sessionId).firstOrNull { it.id == id }
                    ?: throw ScheduleException("schedule_not_found", "no reminder with id $id in this session")
                val next = if (timing == null) {
                    null
                } else {
                    // 间隔型按「保存时刻」重新对齐（快照文案）；其余仍按创建时刻
                    val anchor = if (timing.kind == "every_seconds") now else existing.createdAt
                    ScheduleTime.nextOccurrence(timing, now, anchor)
                        ?: throw ScheduleException("not_future", "the timing has no future occurrence")
                }
                return schedules.update(id, sessionId, title, prompt, timing, next, now)
            }

            override fun delete(id: String): ScheduledTask {
                val sessionId = requireSession()
                return schedules.delete(id, sessionId)
            }

            private fun requireSession(): String = currentId()
                ?: throw ScheduleException("schedule_not_found", "no active session")
        }

    private val scheduleHost = scheduleHostFor {
        (current?.state ?: chat)?.sessionId?.takeIf { it.isNotBlank() }
    }

    /**
     * 计划模式状态（M18）：每个会话一份（[LiveChat.plan]），引擎与 UI 共享同一个对象。
     * 换会话用 `reset()` 原地改；切走/切回来还是同一个对象，后台轮次读到的也是自己那份。
     */
    private val planHost = object : xin.dshaa.core.tools.PlanModeHost {
        // 默认操作前台会话；每个 [LiveChat] 的 ToolContext 会覆盖成自己的 plan（并行轮次互不串）
        private val plan: PlanModeState get() = current?.plan ?: planState

        override fun active(): Boolean = plan.active

        override fun requestExit() {
            plan.pending = false
            plan.narrate = false
        }
    }

    /**
     * `workflow` 工具的后端（第 32 个工具）：QuickJS 执行脚本，`agent()` 复用子代理引擎
     * （独立子会话，结果只回给 workflow、不投结算通知/不进子代理面板，与上游一致）。
     */
    private val workflowHost = AppWorkflowHost()

    private val toolContext = ToolContext(
        workspace,
        askUser = { questions ->
            val live = current
            if (live == null) questions.map { xin.dshaa.core.tools.AskAnswer(it.id) }
            else askUser(live, questions)
        },
        goalHost = goalHost,
        scheduleHost = scheduleHost,
        webSearchHost = webSearchHost,
        attachments = attachments,
        imageRoute = currentImageRoute(),
        jobs = jobRegistry,
        // 默认归属前台会话；并行轮次用自己的 ToolContext 覆盖成各自 sessionId
        jobOwner = { (current?.state ?: chat)?.sessionId },
        agents = RootAgentHost { current },
        planMode = planHost,
        skills = skills,
        workflowHost = workflowHost,
    )

    /**
     * `read_image` 的图片文件路径（UI 直接按 path 解码显示；null = 附件不存在）。
     * 放在 ViewModel 上是为了让会话里只有 attachmentId 的 image 块也能重放。
     */
    fun imagePathFor(attachmentId: String): String? = attachments.imageFile(attachmentId)?.absolutePath

    /**
     * 当前模型路由的图片能力门（`read_image` 与「给模型发图」共用）。
     * 每次现取：用户在设置里换模型/开识图后立即生效（工具上下文里的旧快照会被 [startTurn] 重新绑定）。
     */
    private fun currentImageRoute(): ImageRoute = ImageRoute(
        provider = providerLabel(),
        model = settings.model,
        // 模型配置页的「模型支持识图」：默认按上游模型目录（deepseek-flash 开），用户可自己声明
        supportsImage = settings.visionEnabled,
    )

    /** 用户当前选的模型能不能看图（发送带图消息前的硬门）。 */
    val imageInputAvailable: Boolean get() = settings.visionEnabled

    /**
     * 本轮真正提供给模型的工具名：`web_search` 受「启用 DeepSeek 网络搜索」开关控制，
     * 关掉后请求里没有这个工具、system 的工具清单也不列它（模型不会再调）。
     */
    private fun offeredToolNames(): Set<String> {
        val names = registry.names.toSet()
        return if (settings.webSearchEnabled) names else names - "web_search"
    }

    // ---------------- 聊天输入条附件（docs/42） ----------------

    /**
     * 相册/文件选择：读 Uri → 归一化落盘（内容寻址附件存储）→ 进当前会话的待发列表。
     * 图片不立即进模型请求，等用户按发送才随 `TurnInput.images` 一起走一轮。
     */
    fun attachImage(uri: Uri) {
        val state = chat ?: return
        if (state.readOnly || state.streaming) return
        state.attachError = null
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val name = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()
            val declared = runCatching { resolver.getType(uri) }.getOrNull()
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    resolver.openInputStream(uri)?.use { readAtMost(it, (ImageNormalizer.MAX_SOURCE_BYTES + 1).toInt()) }
                }.getOrNull()
            }
            if (bytes == null) {
                state.attachError = "无法读取这张图片"
                return@launch
            }
            if (bytes.size > ImageNormalizer.MAX_SOURCE_BYTES) {
                state.attachError = "图片超过 50MB，请先压缩"
                return@launch
            }
            state.attachError = savePendingImage(state, bytes, declared, name)
        }
    }

    /** 相机拍照结果（FileProvider 临时文件）：读字节 → 落附件存储 → 删临时文件。 */
    fun attachPhoto(file: File) {
        val state = chat ?: return
        if (state.readOnly || state.streaming) {
            file.delete()
            return
        }
        state.attachError = null
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { file.readBytes() }.getOrNull().also { file.delete() }
            }
            if (bytes == null || bytes.isEmpty()) {
                state.attachError = "无法读取相机照片"
                return@launch
            }
            state.attachError = savePendingImage(state, bytes, null, file.name)
        }
    }

    /**
     * 截图进待发附件：优先无障碍 takeScreenshot；无障碍没开（或服务没连上）时走 Shizuku
     * `screencap -p` 的 stdout —— 和悬浮窗 OCR 的截图通道一致，不依赖无障碍是否真的连上。
     */
    fun attachScreenshot() {
        val state = chat ?: return
        if (state.readOnly || state.streaming) return
        state.attachError = null
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                A11y.screenshot() ?: run {
                    if (!DeviceShell.canUseShellIdentity()) return@run null
                    val bytes = runCatching {
                        DeviceShell.runBytes("screencap -p", ShellIdentity.SHELL)
                    }.getOrNull() ?: return@run null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
            if (bitmap == null) {
                state.attachError = "截图失败：无障碍服务未连上，Shizuku screencap 也没拿到画面"
                return@launch
            }
            val bytes = withContext(Dispatchers.IO) {
                ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }.also { bitmap.recycle() }
            }
            state.attachError = savePendingImage(
                state,
                bytes,
                "image/png",
                "screenshot-${System.currentTimeMillis()}.png",
            )
        }
    }

    fun removePendingImage(attachmentId: String) {
        val list = chat?.pendingImages ?: return
        val index = list.indexOfFirst { it.ref.attachmentId == attachmentId }
        if (index >= 0) list.removeAt(index)
    }

    /** 待发文件上限（单个）：超过就不读，直接提示。 */
    private val maxFileBytes = 512 * 1024
    /** 图片/任意文件引用的上限（与 Operit 一样，文件只复制不解析）。 */
    private val maxImageFileBytes = 50 * 1024 * 1024
    private val maxAttachmentBytes = 50L * 1024 * 1024
    private val imageFileExts = setOf("png", "jpg", "jpeg", "webp", "gif")

    /** 认作文本的后缀（未知后缀再看内容像不像文本）。 */
    private val textFileExts = setOf(
        "txt", "md", "markdown", "json", "jsonl", "ndjson", "csv", "tsv", "log", "yaml", "yml",
        "toml", "ini", "conf", "cfg", "xml", "html", "htm", "css", "scss", "kt", "kts", "java",
        "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "vue", "sh", "bash", "zsh", "ps1", "bat",
        "sql", "c", "h", "cpp", "hpp", "cc", "rs", "go", "rb", "php", "swift", "gradle", "properties",
        "diff", "patch", "srt", "vtt", "tex", "proto", "graphql",
    )

    /**
     * 任意文件选择（2026-11-08 夜间：附件不只图片）：
     * - 图片 → 走原来的图片管线（归一化 + 内容寻址附件 + base64）；
     * - 文本/代码 → 按 UTF-8 读进 [ChatSessionState.pendingFiles]，发送时作为额外 text 块；
     * - 二进制（PDF/Word/压缩包等）→ 明确提示暂不支持解析，不塞乱码给模型。
     */
    fun attachFile(uri: Uri) {
        val state = chat ?: return
        if (state.readOnly || state.streaming) return
        state.attachError = null
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) { readPendingFile(state, uri) }
            if (chat !== state) return@launch
            state.attachError = error
        }
    }

    fun removePendingFile(name: String) {
        val list = chat?.pendingFiles ?: return
        val index = list.indexOfFirst { it.name == name }
        if (index >= 0) list.removeAt(index)
    }

    /**
     * 把一段现成文本放进待发附件（悬浮窗「通知」「位置」附件用，docs/76 W24）。
     *
     * 和 [attachFile] 的区别：不经过 SAF 选文件，文本由调用方（NotificationListenerService /
     * LocationAttachActivity）组好；只读会话和生成中不允许加，避免把附件塞进正在发的回合。
     * 同名附件自动加序号，不覆盖用户已加的。
     */
    fun attachTextFile(name: String, text: String): Boolean {
        val state = chat ?: return false
        if (state.readOnly || state.streaming) return false
        if (text.isBlank()) return false
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var finalName = name
        var n = 2
        while (state.pendingFiles.any { it.name == finalName }) {
            finalName = "$base ($n)$ext"
            n += 1
        }
        state.pendingFiles += PendingFile(finalName, text)
        state.attachError = null
        return true
    }

    /** 读一个用户选中的文件；返回面向用户的错误（null = 成功或已走图片管线）。 */
    private suspend fun readPendingFile(state: ChatSessionState, uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "附件"
        val declared = runCatching { resolver.getType(uri) }.getOrNull()
        val ext = name.substringAfterLast('.', "").lowercase()

        // 图片走原来的图片管线（模型直接看图）；上限 50MB，和 read_image 一致。
        if (declared?.startsWith("image/") == true || ext in imageFileExts) {
            val bytes = runCatching {
                resolver.openInputStream(uri)?.use { readAtMost(it, maxImageFileBytes + 1) }
            }.getOrNull() ?: return "读取失败：$name"
            if (bytes.isEmpty()) return "文件是空的：$name"
            if (bytes.size > maxImageFileBytes) return "图片太大：$name（上限 50MB）"
            return savePendingImage(state, bytes, declared, name)
        }

        // 非图片先探 512KB：确认是文本就内联；文本超限/二进制 → 复制到工作区做路径引用。
        val probe = runCatching {
            resolver.openInputStream(uri)?.use { readAtMost(it, maxFileBytes + 1) }
        }.getOrNull() ?: return "读取失败：$name"
        if (probe.isEmpty()) return "文件是空的：$name"
        val textual = declared?.startsWith("text/") == true ||
            declared == "application/json" ||
            ext in textFileExts ||
            looksLikeText(probe)
        if (textual && probe.size <= maxFileBytes) {
            val text = probe.toString(Charsets.UTF_8)
            if (text.isBlank()) return "文件里没有可发送的文字：$name"
            var finalName = name
            var n = 2
            while (state.pendingFiles.any { it.name == finalName }) {
                finalName = "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.')}"
                n += 1
            }
            state.pendingFiles += PendingFile(finalName, text)
            return null
        }

        // Operit 的做法：任何文件都复制到本地，消息里只放 <attachment id=路径 …/>，
        // 模型用 read / read_image 工具按路径读。鲸歌复制到工作区 .dsh/attachments，
        // 这样现有 read / read_image 的 workspace 路径守卫不用放宽。
        return try {
            val (relative, bytes) = withContext(Dispatchers.IO) {
                copyAttachmentToWorkspace(uri, name)
            }
            val mime = declared?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                ?: runCatching { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }.getOrNull()
                ?: "application/octet-stream"
            var finalName = name
            var n = 2
            while (state.pendingFiles.any { it.name == finalName }) {
                finalName = "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.')}"
                n += 1
            }
            state.pendingFiles += PendingFile(
                name = finalName,
                text = "",
                path = relative,
                mime = mime,
                size = bytes,
            )
            null
        } catch (e: Exception) {
            "无法引用这个文件：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 原样复制到 `<workspace>/.dsh/attachments/`，返回（相对工作区的路径, 字节数）。 */
    private fun copyAttachmentToWorkspace(uri: Uri, name: String): Pair<String, Long> {
        val dir = File(workspace, ".dsh/attachments").apply { mkdirs() }
        val safe = name
            .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
            .take(100)
            .ifBlank { "attachment" }
        val target = File(dir, "${System.currentTimeMillis()}-$safe")
        val resolver = getApplication<Application>().contentResolver
        val input = resolver.openInputStream(uri) ?: error("读不到文件：$name")
        var total = 0L
        try {
            input.use { ins ->
                target.outputStream().use { outs ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = ins.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > maxAttachmentBytes) {
                            error("文件太大（上限 ${maxAttachmentBytes / 1024 / 1024} MB）")
                        }
                        outs.write(buffer, 0, read)
                    }
                }
            }
            if (total == 0L) error("文件是空的：$name")
            val relative = target.relativeTo(workspace).path.replace('\\', '/')
            return relative to total
        } catch (e: Exception) {
            runCatching { target.delete() }
            throw e
        }
    }

    /** 没声明 mime 的未知后缀：没有 NUL、UTF-8 解出来不乱码就当文本。 */
    private fun looksLikeText(bytes: ByteArray): Boolean {
        val sample = bytes.take(4096)
        if (sample.any { it == 0.toByte() }) return false
        val decoded = sample.toByteArray().toString(Charsets.UTF_8)
        return decoded.none { it == '\uFFFD' }
    }

    /** `<attachment …>` 属性值转义（路径/文件名里可能有引号或 &）。 */
    private fun xmlAttr(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /**
     * 归一化 + 落盘 + 加入待发列表；返回面向用户的错误文案（null = 成功）。
     * 媒体类型按文件头识别（Photo Picker 返回的是通配 media type，不能直接当声明的精确类型用）。
     */
    private suspend fun savePendingImage(
        state: ChatSessionState,
        bytes: ByteArray,
        declared: String?,
        name: String?,
    ): String? {
        val mediaType = sniffImageMediaType(bytes)
            ?: declared?.takeIf { it in setOf("image/png", "image/jpeg", "image/webp", "image/gif") }
            ?: return "不支持的图片格式（支持 PNG/JPEG/WebP/GIF）"
        return try {
            val ref = withContext(Dispatchers.IO) { attachments.saveImage(bytes, mediaType, name) }
            val path = attachments.imageFile(ref.attachmentId)?.absolutePath ?: return "附件写入失败"
            // 选图期间用户切了会话：附件已落盘，但不该出现在新会话的草稿里
            if (chat !== state) return null
            state.pendingImages += PendingImage(ref, path)
            null
        } catch (e: ImageStoreException) {
            imageStoreMessage(e)
        } catch (e: Throwable) {
            "图片处理失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 附件层稳定错误码 → 用户能看懂的一句话（read_image 工具用的是给模型的文案，这里面向人）。 */
    private fun imageStoreMessage(e: ImageStoreException): String = when (e.code) {
        ImageStoreException.Code.INVALID_IMAGE -> "这个文件不是可识别的图片"
        ImageStoreException.Code.IMAGE_TOO_LARGE -> "图片太大（上限 50MB），请先压缩"
        ImageStoreException.Code.IMAGE_TOO_MANY_PIXELS -> "图片像素太大（上限 6400 万像素），请先缩小"
        ImageStoreException.Code.IMAGE_DIMENSION_TOO_LARGE -> "图片单边超过 65535px，请先缩小"
        ImageStoreException.Code.IMAGE_TYPE_MISMATCH -> "图片内容与格式不一致，可能已损坏"
        ImageStoreException.Code.WRITE_FAILED -> "附件写入失败，请检查存储空间"
    }

    /** 最多读 [limit] 字节（防止超大文件整读进内存；多读 1 字节用来判断「超限」）。 */
    private fun readAtMost(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 1 shl 20))
        val buf = ByteArray(1 shl 16)
        var total = 0
        while (total < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - total))
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    /** 当前工作区的会话（抽屉/会话页只显示这一份，按 `header.cwd` 过滤）。 */
    var sessions by mutableStateOf<List<SessionSummary>>(emptyList())
        private set

    /** 全部工作区的根会话（工作区清单/任务标题映射用）。 */
    private var allRootSessions: List<SessionSummary> = emptyList()

    /** 当前工作区（= [workspace]）的投影；工作区清单还没扫完时是默认项。 */
    val currentWorkspace: WorkspaceUi
        get() = workspaces.firstOrNull { it.current } ?: WorkspaceUi(
            id = WorkspaceStore.DEFAULT_ID,
            name = WorkspaceStore.displayName(workspace.absolutePath, workspaceStore.defaultPath),
            path = workspace.absolutePath,
            managed = true,
            count = sessions.size,
            current = true,
            exists = workspace.isDirectory,
        )

    /** 聊天页里是否有「返回上一会话」的栈（子代理回放）。系统返回键用它决定是否拦截。 */
    val canGoBackFromChat: Boolean get() = chatBackStack.isNotEmpty()

    /**
     * 子代理面板数据（M17）：子会话文件 + 进程内 AgentRegistry 合并。
     * 子会话不进 [sessions]（主列表只放用户自己的会话）。
     */
    var subagents by mutableStateOf<List<SubagentUi>>(emptyList())
        private set

    /** 最近一次 `repo.list()` 里的子会话（磁盘骨架，重启后面板靠它）。 */
    private var childSessions: List<SessionSummary> = emptyList()

    /** 首次扫描会话目录还没完成（冷启动要解压 100+ 会话，面板不能先闪「没有子代理」）。 */
    var subagentsLoading by mutableStateOf(true)
        private set

    /** 子代理深度上限（面板状态卡显示）。 */
    val agentMaxDepth: Int get() = agentRegistry.limit()

    /** 工具箱/设置页展示当前注册的工具名（含 workflow 的第 32 个）。 */
    val toolNames: List<String> get() = registry.names

    /**
     * 任务面板数据（M20）：进程内 `JobRegistry` 的全表投影（跨 owner）。
     * 后台任务不落盘，重启后为空——面板如实说明，不做假历史。
     */
    var jobs by mutableStateOf<List<JobUi>>(emptyList())
        private set

    /** 已实现的工具数量（工具页/工作台统计用）。 */
    val toolCount: Int get() = registry.names.size

    /**
     * 当前打开的聊天。null = 停在列表/工作台。
     * 切会话只是把前台指到别的 [ChatSessionState]：原来那条的轮次在后台继续跑（[LiveChat.job]）。
     */
    var chat by mutableStateOf<ChatSessionState?>(null)
        private set

    /** 打开过的会话运行时（state → LiveChat）：切走不销毁，切回来直接复用，不重读文件、不丢流式状态。 */
    private val lives = LinkedHashMap<ChatSessionState, LiveChat>()
    private var current: LiveChat? = null

    /** 正在生成的会话集合（M77 起可多条并行）。Compose 状态：列表右侧转圈/橙点靠它。 */
    var runningStates by mutableStateOf<Set<ChatSessionState>>(emptySet())
        private set

    /** 正在跑的所有运行时（可多条；stop / 通知归属 / keep-alive 用）。只在主线程改。 */
    private val activeTurns = LinkedHashSet<LiveChat>()

    /** 最近启动、且仍在跑的轮次；后台通知/停止兜底用。 */
    private var lastActiveTurn: LiveChat? = null

    /** 跑完但用户还没看过的会话 id：列表右侧绿点，打开（或切回）即消。 */
    var unreadDone by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * 会话列表右侧状态标：生成中转圈 / 等回答橙点 / 未读完成绿点。
     * 每次组合时读 [runningStates] 与它的审批/提问状态，状态一变列表自动重绘。
     */
    fun badgeFor(sessionId: String?): SessionBadge? {
        if (sessionId.isNullOrBlank()) return null
        val running = liveById(sessionId)?.state
        if (running != null && running in runningStates) {
            return if (running.pendingApproval != null || running.pendingQuestions != null) {
                SessionBadge.WAITING
            } else {
                SessionBadge.RUNNING
            }
        }
        return if (sessionId in unreadDone) SessionBadge.DONE else null
    }

    /**
     * 其它会话正等着用户点（M78 并行收尾）：
     * 审批卡和提问框只画在**当前会话**上，后台轮次弹出来时前台聊天页完全看不到，
     * 只有抽屉里一个橙点。这里把「不是前台的等待项」列出来，聊天页顶部画成横幅，点一下切过去。
     *
     * Compose 读的是 [runningStates] 和各会话的 `pendingApproval/pendingQuestions`
     * （都是 snapshot state），审批一来/一走横幅自动重绘。
     */
    fun waitingElsewhere(): List<WaitingSession> {
        val here = current?.state
        return runningStates.mapNotNull { state ->
            if (state === here) return@mapNotNull null
            val approval = state.pendingApproval
            val questions = state.pendingQuestions
            if (approval == null && questions.isNullOrEmpty()) return@mapNotNull null
            WaitingSession(
                sessionId = state.sessionId ?: return@mapNotNull null,
                title = state.title.ifBlank { "未命名会话" },
                toolName = approval?.toolName ?: "提问",
                summary = approval?.summary ?: questions?.firstOrNull()?.question.orEmpty(),
                question = approval == null,
            )
        }
    }

    /** 横幅点击：把前台切到那条会话（审批卡跟着会话走，切过去就能点）。 */
    fun focusSession(id: String) {
        if (id == current?.state?.sessionId) return
        Log.i("DshTurn", "focus session=$id (from waiting banner)")
        liveById(id)?.let { live ->
            attach(live)
            syncPlanUi()
            return
        }
        viewModelScope.launch { openSessionById(id) }
    }

    /** 聊天页内的返回栈（M22）：从工具卡打开子代理只读回放，返回键回到父会话而不是退出。 */
    private val chatBackStack = ArrayDeque<ChatSessionState>()

    // 没有打开会话时的兜底对象（引擎/命令只在有 chat 时被调用，兜底只为空安全，不参与状态）
    private val strayStop = TurnStopState()
    private val strayPlan = PlanModeState()
    private val strayActivation = GoalActivationState()

    private var handle: SessionSession?
        get() = current?.handle
        set(value) {
            current?.handle = value
        }

    private var streamJob: Job?
        get() = current?.job
        set(value) {
            current?.job = value
        }

    /**
     * 当前会话的 goal 续轮开关（对齐上游 activation：进程内、不落盘）。
     * 打开/新建会话换成新的 disarmed —— 会话恢复后 active 目标不会自己跑，必须用户 resume。
     */
    private var goalActivation: GoalActivationState
        get() = current?.goalActivation ?: strayActivation
        set(value) {
            current?.goalActivation = value
        }

    /** 当前会话的计划模式（每个 [LiveChat] 一份；后台轮次读自己那份）。 */
    private val planState: PlanModeState get() = current?.plan ?: strayPlan

    /** 会话根目录（旧版导入页直接用）。 */
    val sessionsDir: File get() = repo.root

    /**
     * 调试通道 `--es dsh_import_zip <路径>` 带进来的备份包：
     * 非空时外壳直接把「旧版数据导入」页打开并预扫描（真机无 SAF 也能验收）。
     */
    var importRequest by mutableStateOf<String?>(null)
        private set

    fun consumeImportRequest() {
        importRequest = null
        settings.debugImportPath = null
    }

    /** 调试通道 `--es dsh_open_screen <Screen>`：启动即落到指定一级页面（真机验收用）。 */
    var debugScreenRequest by mutableStateOf<String?>(null)
        private set

    fun consumeDebugScreen() {
        debugScreenRequest = null
        settings.debugScreen = null
    }

    /**
     * 一个会话的运行时（2026-11-08 夜间：切会话不再中断生成）。
     * 会话文件句柄、停止位、计划模式、goal activation、引擎、正在跑的 job 全在这里；
     * 引擎是**每会话一个**，`sessions.current()` 绑死自己的句柄——后台轮次写的永远是自己的文件。
     */
    private inner class LiveChat(val state: ChatSessionState) {
        var handle: SessionSession? = null
        val stop = TurnStopState()
        val plan = PlanModeState()
        var goalActivation = GoalActivationState()
        var job: Job? = null
        /** 本会话待投递的提醒（并行轮次各投各的，不抢别的会话的提醒）。 */
        val pendingSchedules = ArrayDeque<String>()
        /** 本会话挂起的工具审批/提问（并行时后台轮次不能覆盖前台的那一份）。 */
        var approvalDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
        var questionDeferred: kotlinx.coroutines.CompletableDeferred<List<xin.dshaa.core.tools.AskAnswer>>? = null
        /**
         * 本会话的工具上下文（M77 并行）：goal / 提醒 / 计划模式 / 后台任务 owner / 子代理归属
         * 全绑死自己这条会话，不再读「当前前台是谁」。工作区仍是全局的（会话按工作区分组）。
         */
        val toolContext: xin.dshaa.core.tools.ToolContext by lazy { buildToolContext(this) }
        val engine: TurnEngine by lazy { buildEngine(this) }
    }

    /**
     * 给一个会话建独立的 ToolContext：从基础上下文 copy（共享 registry/附件/webSearch/jobs），
     * 再覆盖所有「属于哪条会话」的回调。`state` 也独立，避免 todo 在并行会话间串。
     */
    private fun buildToolContext(live: LiveChat): xin.dshaa.core.tools.ToolContext = toolContext.copy(
        state = mutableMapOf(),
        cwd = workspace,
        askUser = { questions -> askUser(live, questions) },
        goalHost = goalHostFor({ live.handle }, { live.goalActivation }),
        scheduleHost = scheduleHostFor { live.state.sessionId },
        jobOwner = { live.state.sessionId },
        agents = RootAgentHost { live },
        planMode = object : xin.dshaa.core.tools.PlanModeHost {
            override fun active(): Boolean = live.plan.active

            override fun requestExit() {
                live.plan.pending = false
                live.plan.narrate = false
            }
        },
    )

    /** 把某个会话运行时挂到前台；顺带清掉它的「完成未读」绿点。 */
    private fun attach(live: LiveChat?) {
        current = live
        chat = live?.state
        // 悬浮窗（2026-11-08）跟着前台会话走：它读的是同一个 ChatSessionState
        ChatMirror.state = live?.state
        // 大肥鱼/客户端插件窗口（M78）：并行时优先显示前台这条会话的状态
        BigFish.setForeground(live?.state)
        val id = live?.state?.sessionId
        if (id != null && id in unreadDone) unreadDone = unreadDone - id
    }

    private fun liveFor(state: ChatSessionState): LiveChat = lives.getOrPut(state) { LiveChat(state) }

    private fun liveById(id: String?): LiveChat? =
        id?.let { wanted -> lives.values.firstOrNull { it.state.sessionId == wanted } }

    /** 当前会话的执行器（每会话一个实例，句柄绑死自己的会话文件）。 */
    private val engine: TurnEngine get() = current?.engine ?: error("没有打开的会话")

    /**
     * 一轮对话的执行器（core:engine）。原 AppViewModel 里的 runTurn/续轮循环搬到这里，
     * 依赖以接口注入：JVM 单测可换假 LLM/假工具跑完整轮次。
     */
    /**
     * 粗估 token（M76 上下文三段明细用）：CJK/全角按 1 字符≈1 token，其余按 4 字符≈1 token。
     * 面板里这些数都标「约」；百分比和总数用模型返回的真实 prompt tokens。
     */
    private fun estimateTokens(text: String): Int {
        var cjk = 0
        var other = 0
        for (ch in text) {
            val code = ch.code
            if (code in 0x2E80..0x9FFF || code in 0xFF00..0xFFEF) cjk++ else other++
        }
        return cjk + (other + 3) / 4
    }

    private fun buildEngine(live: LiveChat): TurnEngine = TurnEngine(
            EngineDeps(
                workspacePath = workspace.absolutePath,
                config = { llmConfig() },
                providerLabel = { providerLabel() },
                reasoningEffort = { settings.reasoningEffort },
                registry = registry,
                toolContext = live.toolContext,
                stop = live.stop,
                apiTools = {
                    val apiTools = promptPack.androidApiTools().filter { it.name in offeredToolNames() } + pluginApiTools
                    // M76 上下文三段明细：工具 schema 的 token 是估算值（字符折算）
                    live.state.estToolsTokens = apiTools.sumOf { tool ->
                        estimateTokens(tool.name + tool.description.orEmpty() + tool.inputSchema.toString())
                    }
                    apiTools
                },
                systemPrompt = {
                    val text = promptPack.systemFor(
                        model = settings.model,
                        provider = providerLabel(),
                        tools = offeredToolNames(),
                        // 计划模式：system 里动态带 plan:policy 段（上游 PLAN_POLICY，order 500）
                        planMode = live.plan.selected(),
                        // 角色卡（M71）：人设替换 deployment:persona-prefix，系统提示词追加到
                        // deployment:persona-suffix（order 10200）；插件注册的段落按 order 一起排、
                        // 同名段插件覆盖（插件的人设/上下文走同一条路，不往最后再糊一段）。
                        personaPrefix = personaPrefixText(),
                        personaSuffix = settings.assistantSystem,
                        extraSections = pluginPromptSections(),
                        variables = pluginPromptVariables(),
                    )
                    live.state.estSystemTokens = estimateTokens(text)
                    live.state.contextWindow = settings.contextWindow
                    text
                },
                runtimeContext = { runtimeContextText() },
                stream = { request, config ->
                    val fakeOverflow = debugOverflowOnce
                    if (fakeOverflow != null && debugOverflowPending.compareAndSet(true, false)) {
                        prefs.edit().remove("debug_overflow_once").apply()
                        Log.i("DshCompact", "debug overflow injection: ${fakeOverflow.take(120)}")
                        flow {
                            emit(LlmEvent.MessageStart(id = null, model = config.model, inputTokens = 0))
                            emit(LlmEvent.Failed(fakeOverflow, code = "invalid_request_error", status = 400))
                        }
                    } else {
                        if (settings.protocol == "openai") {
                            openAiClient.stream(request, config)
                        } else {
                            client.stream(request, config)
                        }
                    }
                },
                sessions = object : SessionGateway {
                    override fun current(): SessionSession? = live.handle

                    override suspend fun create(): SessionSession =
                        repo.create(cwd = workspace.absolutePath, model = settings.model).also { created ->
                            created.appendRequestHeader(
                                provider = providerLabel(),
                                model = settings.model,
                                reasoningEffort = settings.reasoningEffort,
                                maxTokens = settings.maxTokens,
                            )
                            // 新会话在第一条消息之前就进了计划模式（UI 先记在内存）：建会话时补写 plan/mode
                            if (live.plan.selected()) {
                                created.appendPlanMode(true)
                                live.plan.active = true
                                live.plan.pending = null
                                live.plan.entered = true
                            }
                            live.handle = created
                            // 后台建会话也要立刻让列表出现这一行（状态标要靠它）
                            refreshSessions()
                        }
                },
                goals = { session -> goalStore(session, live.goalActivation) },
                imageBytes = { id -> attachments.readImage(id) },
                imagePath = { id -> imageReadonlyPath(id) },
                imageBudget = { imageOffloadBudget() },
                approval = { call -> awaitApproval(live, call) },
                nextSchedulePrompt = { nextSchedulePrompt(live) },
                tickSchedules = { tickSchedulesFor(live) },
                onRequest = { n, request ->
                    dumpRequestIfRequested(request)
                    logRequest(n, request)
                },
                onSummaryRequest = { request -> dumpSummaryRequestIfRequested(request) },
                onLlmEvent = { n, event -> logLlmEvent(n, event) },
                onToolResult = { call, text, isError ->
                    logToolCall(call, text, isError)
                    // write/edit/bash 都可能改工作区：`@file` 索引下次查询重建
                    fileReferenceSearch.invalidate()
                },
                onTurnFinished = {
                    refreshSessions()
                    logTurnEnd(live.state)
                },
                jobNotices = {
                    takeNoticesFor(live.state.sessionId)
                },
                skillCatalog = { session -> skillCatalogNotice(session) },
                skillInvocations = { _, texts -> skillInvocationNotices(texts) },
                // 时间上下文（M42）：设置开关 + 设备时区（Android 单一客户端，browser 时区恒为设备时区）
                timeContext = { timeContextConfig() },
                // 自动压缩（M44）：模型配置页声明的上下文窗口；0/未知 = 不自动压缩
                contextWindow = { settings.effectiveContextWindow.takeIf { it > 0 } },
                log = { message -> Log.i("DshCompact", message) },
                plan = live.plan,
                onPlanChanged = { syncPlanUiFor(live) },
            ),
    )


    /** 已批准过的工具（本进程；「总是允许」）。 */
    private val approvedTools = mutableSetOf<String>()

    // ---- 技能（M19/M21） ----
    // 注意：这三个 Compose 状态必须在 init 之前声明——init 里的调试通道会走 submit()，
    // 而 submit 要用 skillsUi 判断「/名字 是技能还是未知命令」。

    /** 设置页「技能」的数据：当前发现的技能（按名字序）。 */
    var skillsUi by mutableStateOf<List<SkillSummary>>(emptyList())
        private set

    /** 发现是否完整（某个根读失败 → 目录消息带「发现不完整」提示）。 */
    var skillsComplete by mutableStateOf(true)
        private set

    /** 被忽略的 SKILL.md 及原因（frontmatter 坏了、同名低优先级……）。 */
    var skillsWarnings by mutableStateOf<List<String>>(emptyList())
        private set

    /** 包管理页「导入技能」的一次性结果（Snackbar 用）。 */
    var skillNotice by mutableStateOf<String?>(null)
        private set

    init {
        // 悬浮窗的桥：服务里的 Compose 面板通过它读写同一个 ViewModel
        ChatMirror.host = this
        BundledSkills.ensureInstalled(getApplication())
        refreshSessions()
        refreshSkills()
        // 已装插件在冷启动时就接进工具表（不用等用户进插件页点一下）
        syncPluginTools()
        // 后台任务/子代理结算 → 按 owner 唤醒对应代理（根会话走聊天，子代理走它自己的引擎）
        jobRegistry.settlementListener = { settlement -> wakeOwner(settlement.owner) }
        // 后台任务表变化（start / kill / settle）→ 任务面板立即翻状态，不靠轮询；
        // 顺便维护保活前台服务（后台 bash 也算「正在跑」，退后台不能被冻）
        jobRegistry.onChange = {
            refreshJobs()
            syncKeepAlive()
        }
        agentRegistry.onNotice = { owner -> wakeOwner(owner) }
        // 子代理启动/结算 → 子代理面板立刻翻状态（面板数据是运行态 + 会话文件的合并）
        agentRegistry.onChange = {
            refreshSubagents()
            syncKeepAlive()
        }
        // 提醒轮询：前台进程内 5 秒粒度；停机/后台错过的，回到该会话时补投最近一次
        viewModelScope.launch {
            while (true) {
                runCatching { tickSchedules() }
                kotlinx.coroutines.delay(5_000)
            }
        }
        // 调试通道：`am start --es dsh_import_zip <绝对路径>` → 直接打开旧版导入页
        settings.debugImportPath?.let { path ->
            importRequest = if (File(path).isAbsolute) path else File(getApplication<Application>().filesDir, path).absolutePath
        }
        debugScreenRequest = settings.debugScreen
        // 调试通道：`am start --es dsh_send "..."`（仅 debug，用于无 IME 的自动验收）
        val sp = getApplication<Application>().getSharedPreferences("dsh", 0)
        // 调试通道：`--es dsh_backup_export <绝对路径>` 直接导出原生备份（锁屏/无 UI 也能验，docs/59）
        sp.getString("debug_backup_export", null)?.takeIf { it.isNotBlank() }?.let { raw ->
            sp.edit().remove("debug_backup_export").apply()
            viewModelScope.launch {
                val target = if (File(raw).isAbsolute) File(raw) else File(getApplication<Application>().filesDir, raw)
                val result = runCatching { withContext(Dispatchers.IO) { backupManager.exportTo(target) } }
                result.onSuccess { record ->
                    val manifest = record.manifest
                    Log.i(
                        "DshBackup",
                        "export ok path=${target.absolutePath} zipBytes=${record.zipBytes} " +
                            "sessions=${manifest.sessions.size} plugins=${manifest.plugins.size} " +
                            "attachments=${manifest.attachmentsFiles} settings=${manifest.settingsKeys} " +
                            "rawBytes=${manifest.totalBytes}",
                    )
                }.onFailure { Log.e("DshBackup", "export failed: ${it.message}", it) }
            }
        }
        // 调试通道：`--es dsh_backup_import <绝对路径>` 直接恢复原生备份（真机验收用）
        // 可选 `--es dsh_backup_workspaces <slug1,slug2>`：只恢复这些工作区的会话（M39）
        sp.getString("debug_backup_import", null)?.takeIf { it.isNotBlank() }?.let { raw ->
            sp.edit().remove("debug_backup_import").apply()
            val wantedRaw = sp.getString("debug_backup_workspaces", null)
            if (wantedRaw != null) sp.edit().remove("debug_backup_workspaces").apply()
            val wantedWorkspaces = wantedRaw
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
            viewModelScope.launch {
                val source = if (File(raw).isAbsolute) File(raw) else File(getApplication<Application>().filesDir, raw)
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val only = wantedWorkspaces?.let { wanted ->
                            // 调试参数用子串匹配（`storage-emulated-0` 能匹配 `--storage-emulated-0--`），方便手敲。
                            backupManager.inspect(source).sessions
                                .filter { session -> wanted.any { token -> session.workspace.contains(token) } }
                                .map { it.id }
                                .toSet()
                        }
                        backupManager.restore(
                            source,
                            mapMissingCwdTo = workspace.absolutePath,
                            onlySessionIds = only,
                        ) to (only?.size ?: -1)
                    }
                }
                result.onSuccess { (restored, onlySize) ->
                    onRestoredFromBackup()
                    Log.i(
                        "DshBackup",
                        "restore sessions=${restored.sessions.restored} skipped=${restored.sessions.skipped} " +
                            "failed=${restored.sessions.failed} deselected=${restored.sessions.deselected} " +
                            "only=${if (onlySize >= 0) onlySize else "all"} cwdRemapped=${restored.cwdRemapped} " +
                            "plugins=${restored.pluginsAdded} attachments=${restored.attachments.restored} " +
                            "settings=${restored.settingsApplied} errors=${restored.errors.size}",
                    )
                    restored.errors.take(6).forEach { Log.w("DshBackup", "restore: $it") }
                }.onFailure { Log.e("DshBackup", "restore failed: ${it.message}", it) }
            }
        }
        // 调试通道：`--es dsh_plugin_detail <插件id>` → 插件页并直接打开详情（解锁后截图/回归用）
        sp.getString("debug_plugin_detail", null)?.takeIf { it.isNotBlank() }?.let { id ->
            sp.edit().remove("debug_plugin_detail").apply()
            debugScreenRequest = "Plugins"
            debugPluginDetailId = id
        }
        // 调试通道：`--es dsh_plugin_zip <绝对路径>` → 直接导入并落到插件页（锁屏/无 UI 也能验）
        sp.getString("debug_plugin_zip", null)?.takeIf { it.isNotBlank() }?.let { raw ->
            sp.edit().remove("debug_plugin_zip").apply()
            val path = if (File(raw).isAbsolute) raw else File(getApplication<Application>().filesDir, raw).absolutePath
            debugScreenRequest = "Plugins"
            viewModelScope.launch {
                pluginsBusy = true
                val result = pluginStore.installFile(File(path))
                pluginsUi = pluginStore.list()
                pluginNotice = result.message
                pluginsBusy = false
                syncPluginTools()
                Log.i("DshPlugin", "debug import ok=${result.ok} ${result.message}")
                Log.i("DshPlugin", "debug import injected=${pluginInjected[pluginsUi.firstOrNull()?.id].orEmpty()}")
            }
        }
        // 调试通道：`--es dsh_plugin_grant <插件id|all>` 一次授完清单里的危险权限（锁屏/无 UI 验收）
        val debugPluginGrant = sp.getString("debug_plugin_grant", null)
        if (!debugPluginGrant.isNullOrBlank()) {
            sp.edit().remove("debug_plugin_grant").apply()
            viewModelScope.launch {
                val targets = if (debugPluginGrant == "all") pluginsUi.map { it.id } else listOf(debugPluginGrant)
                val messages = targets.map { id -> pluginStore.grantAll(id).message }
                pluginsUi = pluginStore.list()
                syncPluginTools()
                Log.i("DshPlugin", "debug grant target=$debugPluginGrant ${messages.joinToString(" | ")}")
            }
        }
        // 调试通道：`--es dsh_plugin_reload <插件id>` 重跑试运行（锁屏时验 Config 变更/升级）
        val debugPluginReload = sp.getString("debug_plugin_reload", null)
        if (!debugPluginReload.isNullOrBlank()) {
            sp.edit().remove("debug_plugin_reload").apply()
            viewModelScope.launch {
                // 试运行要起 QuickJS（最长 12 秒），别占主线程。
                val result = withContext(Dispatchers.IO) { pluginStore.reload(debugPluginReload) }
                pluginsUi = pluginStore.list()
                pluginNotice = result.message
                syncPluginTools()
                Log.i("DshPlugin", "debug reload ok=${result.ok} ${result.message}")
            }
        }
        // 调试通道：`--es dsh_plugin_config <插件id|all> --es dsh_plugin_config_b64 <base64 JSON>`
        // 配置先落盘，再 syncPluginTools；下一次工具调用 apply(ctx, config) 就会拿到合并后的配置。
        val debugConfigId = sp.getString("debug_plugin_config_id", null)
        if (!debugConfigId.isNullOrBlank()) {
            val configJson = sp.getString("debug_plugin_config_json", null).orEmpty()
            sp.edit().remove("debug_plugin_config_id").remove("debug_plugin_config_json").apply()
            viewModelScope.launch {
                val targets = if (debugConfigId == "all") pluginsUi.map { it.id } else listOf(debugConfigId)
                val messages = targets.map { id -> pluginStore.setConfig(id, configJson).message }
                pluginsUi = pluginStore.list()
                syncPluginTools()
                Log.i("DshPlugin", "debug config target=$debugConfigId ${messages.joinToString(" | ")}")
            }
        }
        // 调试通道：`--es dsh_plugin_call <工具名> --es dsh_plugin_args '{"…"}'`
        // 锁屏/无 UI 时直接验「插件工具真的能执行」；不经过模型，只验宿主链路。
        val debugPluginCall = sp.getString("debug_plugin_call", null)
        if (!debugPluginCall.isNullOrBlank()) {
            val argsRaw = sp.getString("debug_plugin_args", null)
            sp.edit().remove("debug_plugin_call").remove("debug_plugin_args").apply()
            viewModelScope.launch {
                // 冷启动后系统绑定无障碍服务要几百毫秒到一两秒；调试通道在 ViewModel init 里就跑，
                // 会抢在 A11y.attach 之前拿到「无障碍未连接」。这里只给**调试通道**加一点等待（M68）。
                kotlinx.coroutines.delay(2_500)
                syncPluginTools()
                val tool = registry.tools.firstOrNull { it.name == debugPluginCall }
                val result = if (tool == null) {
                    xin.dshaa.core.tools.ToolResult.err("没有注册工具：$debugPluginCall")
                } else {
                    val args = runCatching {
                        Json.parseToJsonElement(argsRaw?.ifBlank { "{}" } ?: "{}").jsonObject
                    }.getOrElse { JsonObject(emptyMap()) }
                    runCatching { tool.execute(args, toolContext) }
                        .getOrElse { xin.dshaa.core.tools.ToolResult.err("调用崩溃：${it.message}") }
                }
                Log.i(
                    "DshPlugin",
                    "debug call name=$debugPluginCall args=${argsRaw.orEmpty().take(500)} " +
                        "isError=${result.isError} text=${result.text.take(4000)}",
                )
            }
        }
        // 调试通道：`--es dsh_client_plugin <插件id>` → 打开插件自己的 dsh.client 界面（M70）
        sp.getString("debug_client_plugin", null)?.takeIf { it.isNotBlank() }?.let { id ->
            sp.edit().remove("debug_client_plugin").apply()
            viewModelScope.launch {
                kotlinx.coroutines.delay(2_500)
                val record = pluginStore.list().firstOrNull { it.id == id }
                val message = if (record == null) {
                    "没有这个插件：$id"
                } else {
                    xin.dshaa.nativeapp.pluginui.ClientPluginWindows.open(getApplication(), record)
                }
                Log.i("DshClientHost", "debug open plugin window id=$id -> $message")
            }
        }
        // 调试通道：`--es dsh_fish_demo thinking|working|waiting|success|error` → 给悬浮窗摆一个状态
        // （真机截图验收用，不需要真的跑一轮模型）。
        sp.getString("debug_fish_demo", null)?.takeIf { it.isNotBlank() }?.let { demo ->
            sp.edit().remove("debug_fish_demo").apply()
            when (demo) {
                "thinking" -> BigFish.onTurnStart(null, settings.reasoningEffort, "鲸歌适配大肥鱼")
                "working" -> BigFish.onStatus(null, "运行工具 read…")
                "waiting" -> BigFish.onWaiting(null, "bash")
                "success" -> BigFish.onTurnEnd(null, null)
                "error" -> BigFish.onTurnEnd(null, "HTTP 401：API key 无效")
                else -> BigFish.onIdle(null)
            }
            Log.i("DshBigFish", "debug fish demo=$demo")
        }
        // 调试通道：`--es dsh_fetch_models <绝对路径>` → 拉一次供应商模型列表，结果写文件        // （2026-11-09 用户要求「拉取模型」；锁屏/无 UI 也能验端点、密钥与解析）。
        sp.getString("debug_fetch_models", null)?.takeIf { it.isNotBlank() }?.let { outPath ->
            sp.edit().remove("debug_fetch_models").apply()
            viewModelScope.launch {
                val result = fetchModels()
                val text = result.fold(
                    onSuccess = { names -> "ok ${names.size}\n" + names.joinToString("\n") },
                    onFailure = { error -> "error ${error.message}" },
                )
                runCatching { File(outPath).writeText(text) }
                Log.i("DshModels", text.take(1200))
            }
        }
        // 调试通道：`--ez dsh_terminal_selftest true` 不经过模型，按 registry 调 6 个终端工具，
        // 每一步的完整结果写进 filesDir/terminal-selftest.log（docs/77 真机验收）。
        if (sp.getBoolean("debug_terminal_selftest", false)) {
            val keepSession = sp.getBoolean("debug_terminal_selftest_keep", false)
            sp.edit().remove("debug_terminal_selftest").remove("debug_terminal_selftest_keep").apply()
            if (keepSession) debugScreenRequest = "Terminal"
            viewModelScope.launch {
                xin.dshaa.nativeapp.device.tools.TerminalSelfTest.run(
                    app,
                    registry,
                    toolContext,
                    keepSession = keepSession,
                )
            }
        }
        val auto = sp.getString("debug_send", null)
        runRecorder?.record("run.meta") {
            put("scenario", prefs.getString("debug_parity_scenario", "").orEmpty())
            put("model", settings.model)
            put("provider", providerLabel())
            put("baseUrl", settings.baseUrl)
            put("approvalMode", settings.approvalMode)
            put("tools", registry.names.size)
            put("prompt", auto.orEmpty())
        }
        val debugGoal = sp.getString("debug_goal", null)
        // `--ei dsh_goal_max_rounds 2`：调试目标自带的轮次上限（验 goal-round-driver 的 round-limit 收尾）
        val debugGoalRounds = sp.getInt("debug_goal_max_rounds", 0).takeIf { it > 0 }
        // 附件验收：`--es dsh_attach_image <绝对路径>` 先把图片放进待发列表（可与 dsh_send 连用）
        val debugAttach = sp.getString("debug_attach_image", null)
        if (!debugAttach.isNullOrBlank()) sp.edit().remove("debug_attach_image").apply()
        // M74：`--es dsh_attach_text <绝对路径>` 把文本文件放进待发附件（验聊天页引用 chip）
        val debugAttachText = sp.getString("debug_attach_text", null)
        if (!debugAttachText.isNullOrBlank()) sp.edit().remove("debug_attach_text").apply()
        val openSessionId = sp.getString("debug_open_session", null)
        if (!openSessionId.isNullOrBlank()) {
            // 调试通道：`--es dsh_open_session <id>` 打开已有会话；可与 `dsh_send` 连用验历史重放
            sp.edit().remove("debug_open_session").apply()
            viewModelScope.launch {
                openSessionById(openSessionId)
                if (!auto.isNullOrBlank()) {
                    sp.edit().remove("debug_send").apply()
                    attachDebugImage(debugAttach)
                    attachDebugText(debugAttachText)
                    submit(auto)
                }
            }
        } else if (!debugGoal.isNullOrBlank()) {
            // 调试通道：`--es dsh_goal "<目标>"` → 新建会话并写一个 active goal（UI/工具验收用）；
            // 若同时带 `--es dsh_send`，就在**同一个** goal 会话里发送，方便验证 get_goal/update_goal。
            sp.edit().remove("debug_goal").apply()
            viewModelScope.launch {
                val session = withContext(Dispatchers.IO) { repo.create(cwd = workspace.absolutePath, model = settings.model) }
                val view = withContext(Dispatchers.IO) {
                    runCatching { goalStore(session).create(debugGoal.trim(), debugGoalRounds) }.getOrNull()
                }
                val state = ChatSessionState(settings.model)
                state.sessionId = session.header.id
                state.title = "目标：${debugGoal.trim().take(16)}"
                state.goal = view
                val live = LiveChat(state).apply {
                    handle = session
                    goalActivation = GoalActivationState()
                }
                lives[state] = live
                attach(live)
                refreshSessions()
                if (!auto.isNullOrBlank()) {
                    sp.edit().remove("debug_send").apply()
                    attachDebugImage(debugAttach)
                    submit(auto)
                }
            }
        } else if (!auto.isNullOrBlank()) {
            sp.edit().remove("debug_send").apply()
            newChat()
            // 调试通道：`--ez dsh_plan_mode true` → 新会话一开始就在计划模式（真机验收 plan/mode 事件）
            if (sp.getBoolean("debug_plan_mode", false)) {
                sp.edit().remove("debug_plan_mode").apply()
                planState.active = true
                planState.entered = true
                syncPlanUi()
            }
            viewModelScope.launch {
                attachDebugImage(debugAttach)
                attachDebugText(debugAttachText)
                submit(auto)
            }
        }
    }

    /**
     * 调试通道：把一张本地图片读进待发附件（与用户从相册选图走同一个 [savePendingImage]，
     * 覆盖归一化/落盘/能力门前的全部路径）。文件读不到就静默跳过（自动验收要能继续发文字）。
     */
    private suspend fun attachDebugImage(path: String?) {
        if (path.isNullOrBlank()) return
        val file = File(path)
        val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() } ?: return
        val state = chat ?: return
        state.attachError = savePendingImage(state, bytes, null, file.name)
    }

    /**
     * 调试通道：`--es dsh_attach_text <绝对路径>` 把设备上一个文本文件读进待发附件，
     * 走的是和「附件 → 文件」同一条 [attachTextFile]（M74 引用 chip 验收用）。
     */
    private suspend fun attachDebugText(path: String?) {
        if (path.isNullOrBlank()) return
        val file = File(path)
        val text = withContext(Dispatchers.IO) { runCatching { file.readText(Charsets.UTF_8) }.getOrNull() } ?: return
        attachTextFile(file.name, text)
    }

    fun refreshSessions() {
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { repo.list() }
            // 子代理会话（header.parentSession != null）不混进用户会话列表，进子代理面板
            allRootSessions = all.filter { it.parentSession == null }
            childSessions = all.filter { it.parentSession != null }
            applyWorkspaceFilter()
            rebuildSubagents()
            refreshJobs()
            subagentsLoading = false
        }
    }

    /**
     * 从原生备份恢复后刷新内存状态（M35）：会话列表 + 插件清单/工具注入。
     * 设置项写到 SharedPreferences 后要重启 App 才生效，UI 会提示。
     */
    fun onRestoredFromBackup() {
        refreshSessions()
        pluginsUi = pluginStore.list()
        syncPluginTools()
    }

    /**
     * 按当前工作区过滤会话，并重建工作区清单（RikkaHub 外壳的抽屉数据）。
     *
     * 归属判定用 `header.cwd`；解码失败/空 cwd 的兜底摘要（cwd 是 slug 或空）算到默认工作区，
     * 避免旧版本留下的异常会话在列表里消失。
     */
    private fun applyWorkspaceFilter() {
        val currentPath = workspace.absolutePath
        sessions = allRootSessions.filter { normalizeCwd(it.cwd) == currentPath }
        workspaces = buildWorkspaces(allRootSessions, currentPath)
    }

    private fun normalizeCwd(cwd: String): String =
        if (cwd.isBlank() || !cwd.startsWith("/")) workspaceStore.defaultPath else cwd

    private fun buildWorkspaces(all: List<SessionSummary>, currentPath: String): List<WorkspaceUi> {
        val counts = all.groupingBy { normalizeCwd(it.cwd) }.eachCount()
        val managed = workspaceStore.managed()
        val known = managed.map { it.path }.toSet()
        val out = ArrayList<WorkspaceUi>()
        managed.forEach { entry ->
            out += WorkspaceUi(
                id = entry.id,
                name = entry.name,
                path = entry.path,
                managed = true,
                count = counts[entry.path] ?: 0,
                current = entry.path == currentPath,
                exists = File(entry.path).isDirectory,
            )
        }
        // 历史位置：旧版导入/别的工作区留下的 cwd，只能浏览（不能改名/删除）
        counts.keys
            .filter { it !in known && it.isNotBlank() }
            .sortedByDescending { counts[it] ?: 0 }
            .forEach { path ->
                out += WorkspaceUi(
                    id = "cwd:$path",
                    name = WorkspaceStore.displayName(path, workspaceStore.defaultPath),
                    path = path,
                    managed = false,
                    count = counts[path] ?: 0,
                    current = path == currentPath,
                    exists = File(path).isDirectory,
                )
            }
        return out.sortedWith(compareByDescending<WorkspaceUi> { it.current }.thenByDescending { it.managed }.thenByDescending { it.count })
    }

    // ---- 工作区切换/管理（2026-11-07，RikkaHub 外壳） ----

    /**
     * 切换工作区：关掉当前聊天（回到聊天页的新草稿），重绑工具根/技能根/@file 索引，
     * 再按新 cwd 过滤会话。后台任务与子代理不受影响（它们持有自己的 cwd 快照）。
     * 返回 null = 成功，否则是给用户看的错误文案。
     */
    fun switchWorkspace(path: String): String? {
        if (path.isBlank()) return "工作区路径为空"
        if (path == workspace.absolutePath) return null
        // 换工作区要换工具可写根：正在跑的轮次必须停（它不是能迁走的东西）
        stop()
        // 旧工作区的会话运行时全部作废（句柄/引擎绑的是旧根）
        lives.clear()
        chatBackStack.clear()
        attach(null)
        syncPlanUi()
        workspace = File(path)
        if (workspace.absolutePath == workspaceStore.defaultPath || workspace.path.startsWith(File(getApplication<Application>().filesDir, "workspaces").path)) {
            workspace.mkdirs()
        }
        workspaceStore.setCurrentPath(workspace.absolutePath)
        // 引擎持有同一个 ToolContext：原地改可写根，孤儿用 copy 的子代理不受影响
        toolContext.workspace = workspace
        toolContext.cwd = workspace
        skills = FileSystemSkills(FileSystemSkills.androidRoots(workspace, getApplication<Application>().filesDir))
        toolContext.skills = skills
        fileReferenceSearch = FileReferenceSearch(workspace)
        applyWorkspaceFilter()
        refreshSkills()
        // 插件 fs:workspace 的根跟着工作区走：切完重算绑定，下一次请求就按新根执行。
        syncPluginTools()
        return null
    }

    /**
     * 新建工作区并切过去（抽屉/设置页共用）。
     *
     * [path] 为空 = 建在 App 私有目录；非空 = 用户选的任意目录（/sdcard/... 也行，
     * 需要「所有文件访问」权限，权限页有入口）。返回 null = 成功，否则是错误文案。
     */
    fun createWorkspace(name: String, path: String? = null): String? {
        val result = workspaceStore.create(name, path)
        val entry = result.getOrElse { return it.message ?: "新建工作区失败" }
        return switchWorkspace(entry.path)
    }

    fun renameWorkspace(id: String, name: String) {
        if (workspaceStore.rename(id, name)) applyWorkspaceFilter()
    }

    /**
     * 删除工作区：目录与会话都移到回收站（`files/workspaces/.trash`、`files/sessions/.trash`），
     * 不真删。默认工作区与历史位置不可删。返回 null = 成功。
     */
    fun deleteWorkspace(id: String): String? {
        val entry = workspaceStore.find(id) ?: return "工作区不存在"
        if (id == WorkspaceStore.DEFAULT_ID) return "默认工作区不能删除"
        if (entry.path == workspace.absolutePath) {
            // 先切回默认工作区，避免把当前会话的根删掉
            switchWorkspace(workspaceStore.defaultPath)
        }
        val removed = workspaceStore.remove(id) ?: return "工作区不存在"
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.trashWorkspace(removed.path)
                val dir = File(removed.path)
                if (dir.isDirectory) {
                    val trashRoot = File(File(getApplication<Application>().filesDir, "workspaces"), ".trash").apply { mkdirs() }
                    dir.renameTo(File(trashRoot, "${dir.name}-${System.currentTimeMillis()}"))
                }
            }
            refreshSessions()
        }
        applyWorkspaceFilter()
        return null
    }

    /**
     * 刷新子代理面板：先按内存里的代理表立即翻状态（不等解压会话文件），
     * 再读一遍仓库拿子会话的标题/预览。注册表 start/settle 会调它。
     */
    fun refreshSubagents() {
        viewModelScope.launch {
            rebuildSubagents()
            val all = withContext(Dispatchers.IO) { repo.list() }
            childSessions = all.filter { it.parentSession != null }
            rebuildSubagents()
            refreshJobs()
            subagentsLoading = false
        }
    }

    /**
     * 刷新任务面板（`JobRegistry.onChange` / 面板下拉 / 运行中 1 秒轮询共用）。
     * owner 标题来自会话列表：根会话 + 子代理会话（子会话 id 就是 owner）。
     */
    fun refreshJobs() {
        viewModelScope.launch {
            val entries = jobRegistry.listAll()
            jobs = buildJobRows(entries, allRootSessions + childSessions)
        }
    }

    /** 详情弹层的大预览（默认 20K 字符；仍是只读，不推进模型游标）。 */
    fun peekJob(id: String): JobUi? {
        val row = jobs.firstOrNull { it.id == id } ?: return null
        return runCatching {
            buildJobRows(listOf(jobRegistry.peek(id, row.owner)), sessions + childSessions).single()
        }.getOrNull()
    }

    /** 面板停止一个后台任务（二次确认在 UI）；返回错误文案，成功为 null。 */
    fun stopJob(id: String): String? {
        val row = jobs.firstOrNull { it.id == id } ?: return "unknown job $id"
        val error = runCatching {
            val result = jobRegistry.kill(id, row.owner, "stopped from task panel")
            if (!result.requested) "任务已经结束" else null
        }.exceptionOrNull()?.message
        refreshJobs()
        return error
    }

    private fun rebuildSubagents() {
        subagents = mergeSubagents(childSessions, agentRegistry.all())
    }

    /** 从子代理面板打开子会话：**只读回放**，不接管成当前会话
     * （不写 handle、不投提醒、不唤醒通知——子会话的续轮只能由主代理 send_message 触发）。
     *
     * M22：从聊天里的工具卡点进来时，把父会话压栈，返回键回到父会话而不是直接退出。 */
    fun openSubagentSession(id: String) {
        // 不取消主会话的轮次：子会话只是只读回放
        val previous = chat
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { repo.load(id) }
            if (loaded == null) {
                if (previous != null) {
                    // 子会话文件可能已被清理（回收站/测试清理）：留在当前页，只弹一条瞬态提示，
                    // 不要跳进一个空会话（M22 真机踩过：M16 的子会话已被删）。
                    previous.commandNotice = "子会话不存在：${id.take(8)}"
                } else {
                    val failed = ChatSessionState(settings.model)
                    failed.error = "子会话读取失败：$id"
                    lives[failed] = LiveChat(failed)
                    attach(lives[failed])
                }
                return@launch
            }
            val state = projectSession(loaded, readOnlyReason = READ_ONLY_SUBAGENT, withGoal = false)
            val live = LiveChat(state).apply { handle = loaded }
            lives[state] = live
            if (previous != null && previous.sessionId != id) chatBackStack.addLast(previous)
            attach(live)
        }
    }

    /** 停止一个子代理：注册表取消启动协程，宿主再取消它当前的续轮。成功返回 null。 */
    fun stopSubagent(id: String): String? {
        val error = runCatching { interruptAgent(id) }.exceptionOrNull()?.message
        refreshSubagents()
        return error
    }

    /** 删除会话：移到 .trash（不真删），列表立即刷新。 */
    fun deleteSession(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.trash(id)
                // 会话没了，它的提醒也一起失效（提醒不跨会话复用）
                runCatching { schedules.removeForSession(id) }
            }
            if (chat?.sessionId == id) {
                // 删的是当前会话：如果它还在跑，先停掉；运行时一并作废
                liveById(id)?.let { live ->
                    if (live in activeTurns) {
                        live.stop.requested = true
                        live.job?.cancel()
                        markStopped(live)
                    }
                    lives.remove(live.state)
                }
                attach(null)
                chatBackStack.clear()
            }
            if (id in unreadDone) unreadDone = unreadDone - id
            refreshSessions()
        }
    }

    /** 重命名会话：追加 session/title，刷新列表。 */
    fun renameSession(id: String, title: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.rename(id, title) }
            if (chat?.sessionId == id) chat?.title = title
            refreshSessions()
        }
    }

    /**
     * Operit 的「创建分支」：以当前会话到该消息为止的前缀建一个新会话并打开。
     * 返回 null = 成功；否则是给用户看的错误文案。
     */
    fun branchFromMessage(message: ChatMessage): String? {
        val sessionId = chat?.sessionId ?: return "没有打开的会话"
        val seq = message.seq ?: return "这条消息还没有落库（流式中不能分支）"
        val sourceTitle = chat?.title.orEmpty().ifBlank { "会话" }
        // 分支要拿磁盘上的稳定前缀：正在跑的轮次先停掉（正常流程里分支只在 idle 点可用）
        stop()
        viewModelScope.launch {
            val forked = withContext(Dispatchers.IO) {
                repo.fork(sessionId, seq, cleanTurnBoundary = true, title = "分支 · $sourceTitle")
            }
            if (forked == null) {
                commandNotice("无法创建分支：找不到会话前缀")
                return@launch
            }
            refreshSessions()
            openSessionById(forked.header.id)
        }
        return null
    }

    /**
     * Operit 的「重新生成」在 v4（append-only）里的等价物：
     * 以「这条 AI 消息之前那一轮用户消息之前」为前缀建分支，在新会话里把同一句话重发一遍。
     * 返回 null = 已接受（异步执行），否则是错误文案。
     */
    fun regenerateFromMessage(message: ChatMessage): String? {
        val state = chat ?: return "没有打开的会话"
        val sessionId = state.sessionId ?: return "没有打开的会话"
        if (state.streaming) return "正在生成中，先停止再重新生成"
        val index = state.messages.indexOfFirst { it.id == message.id }
        if (index <= 0) return "找不到这条消息"
        val previousUser = state.messages.subList(0, index).lastOrNull { it.role == MessageRole.USER }
            ?: return "这条消息之前没有用户消息"
        val userSeq = previousUser.seq ?: return "用户消息还没有落库"
        val text = previousUser.content.filter { it.type == BlockType.TEXT }.joinToString("\n") { it.text }
        if (text.isBlank()) return "上一条用户消息是空的"
        stop()
        viewModelScope.launch {
            val forked = withContext(Dispatchers.IO) {
                repo.fork(
                    sessionId,
                    (userSeq - 1).coerceAtLeast(0),
                    cleanTurnBoundary = true,
                    title = "重生成 · ${state.title.ifBlank { "会话" }}",
                )
            }
            if (forked == null) {
                commandNotice("无法重新生成：找不到会话前缀")
                return@launch
            }
            refreshSessions()
            openSessionById(forked.header.id)
            send(text)
        }
        return null
    }

    private fun commandNotice(text: String) {
        chat?.commandNotice = text
    }

    fun openSession(summary: SessionSummary) {
        // 切会话**不取消**正在跑的轮次（2026-11-08 夜间）：这条会话如果本来就活在后台
        // （有 job 在跑或刚跑完），直接把前台指回它——流式光标、工具卡、待办都还在。
        liveById(summary.id)?.let { live ->
            attach(live)
            syncPlanUi()
            return
        }
        viewModelScope.launch { openSessionById(summary.id) }
    }

    /** 打开一个已有会话（列表点击与调试通道 `--es dsh_open_session` 共用）。 */
    private suspend fun openSessionById(id: String) {
        // 后台已有这条会话的运行时：复用，不要重读文件把流式状态冲掉
        liveById(id)?.let { live ->
            attach(live)
            syncPlanUi()
            return
        }
        val loaded = withContext(Dispatchers.IO) { repo.load(id) }
        if (loaded == null) {
            val failed = ChatSessionState(settings.model)
            failed.error = "会话读取失败：$id"
            lives[failed] = LiveChat(failed)
            attach(lives[failed])
            return
        }
        val state = projectSession(
            loaded,
            readOnlyReason = if (loaded.readOnly) READ_ONLY_IMPORT else null,
            withGoal = true,
        )
        val live = LiveChat(state).apply {
            handle = loaded
            // 会话开始边沿：activation 一律回到 disarmed（上游 agent/created 的行为）
            goalActivation = GoalActivationState()
            // 计划模式也是会话级状态：打开会话时从 plan/mode 事件折叠（pending 不跨会话恢复）
            val planView = SessionCodec.planModeOf(loaded.events)
            plan.reset(planView.active, planView.entered)
        }
        lives[state] = live
        attach(live)
        syncPlanUi()
        // 打开会话时补投停机期间错过的提醒（只投最近一次）
        tickSchedules()
        // 子代理/后台任务在后台结算的通知：打开会话后补唤醒一轮
        maybeDeliverNotices()
    }

    /**
     * 会话文件 → Compose 状态（打开根会话 / 只读查看子会话共用）。
     * [readOnlyReason] 非空 = 只读（`import` 旧版副本 / `subagent` 子代理回放）。
     */
    private fun projectSession(
        loaded: SessionSession,
        readOnlyReason: String?,
        withGoal: Boolean,
    ): ChatSessionState {
        val state = ChatSessionState(settings.model)
        state.sessionId = loaded.header.id
        // 标题语义是「最后一条生效」（docs/port/01 §7）：重命名/LLM 起名后打开要显示新标题
        state.title = loaded.events.mapNotNull { SessionCodec.titleOf(it) }.lastOrNull()?.ifBlank { "会话" } ?: "会话"
        state.readOnly = loaded.readOnly || readOnlyReason != null
        state.readOnlyReason = readOnlyReason ?: if (loaded.readOnly) READ_ONLY_IMPORT else null
        if (withGoal) state.goal = runCatching { goalStore(loaded).current() }.getOrNull()
        // 压缩摘要的账本（M43）：checkpoint 消息的 source 只带 compactionId，条数/令牌在
        // compaction/summary 里；先折成「已压缩 N 条历史记录（约 T tokens）」给弹层当副标题。
        val compactionLabels = loaded.events.mapNotNull { ev ->
            if (ev.type != "compaction/summary") return@mapNotNull null
            val data = ev.data?.let { runCatching { it.jsonObject }.getOrNull() } ?: return@mapNotNull null
            val id = data["compactionId"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                ?: return@mapNotNull null
            val items = data["shadowedSeqs"]?.let { runCatching { it.jsonArray.size }.getOrNull() } ?: 0
            val tokens = data["shadowedTokenCount"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() } ?: 0
            id to "已压缩 $items 条历史记录（约 $tokens tokens）"
        }.toMap()
        // 会话面投影（M43/M46）：核心层按 `surfaceOp:{op:"replace"}` 原地顶掉被压缩的历史 /
        // 被修剪的工具结果（`SessionCodec.surfaceOf`，有单测），这里只做「领域消息 → Compose」。
        SessionCodec.surfaceOf(loaded.events).forEach { m ->
            if (m.role != MessageRole.USER && m.role != MessageRole.ASSISTANT && m.role != MessageRole.TOOL) return@forEach
            val sourceKind = m.source?.get("kind")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            val summary = m.source?.get("summary")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                // 技能目录不写 summary（上游 source 只有 entries）：界面按条目数显示
                ?: if (sourceKind == "skill-catalog") {
                    val count = m.source?.get("entries")?.let { runCatching { it.jsonArray.size }.getOrNull() } ?: 0
                    "$count 个技能"
                } else if (sourceKind == "skill-invocation") {
                    // 技能调用 source 只有 name（没有 summary）：回放时用名字当标题
                    m.source?.get("name")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                } else if (sourceKind == Compaction.SOURCE_KIND) {
                    m.source?.get("compactionId")
                        ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                        ?.let { compactionLabels[it] }
                } else {
                    null
                }
            state.messages += ChatMessage(
                id = m.id ?: UUID.randomUUID().toString(),
                role = m.role,
                content = m.content,
                createdAt = m.time ?: System.currentTimeMillis(),
                toolCallId = m.toolCallId,
                seq = m.seq,
                sourceKind = sourceKind,
                goalRound = m.source?.get("round")?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() },
                sourceSummary = summary,
            )
        }
        return state
    }

    fun newChat() {
        // 旧草稿（没会话、没消息、没在跑）直接回收，避免 lives 越堆越多；
        // 正在跑的会话原样留在后台继续写它自己的文件。
        current?.let { old ->
            if (old.state.sessionId == null && old.state.messages.isEmpty() && old.job == null) {
                lives.remove(old.state)
                // 大肥鱼那份快照也一起清（M78：按会话记状态）
                BigFish.forget(old.state)
            }
        }
        chatBackStack.clear()
        val state = ChatSessionState(settings.model)
        val live = LiveChat(state)
        lives[state] = live
        attach(live)
        syncPlanUi()
    }

    fun closeChat() {
        // 只把前台切回栈里的父会话（或空），后台轮次不动
        val previous = chatBackStack.removeLastOrNull()
        attach(if (previous != null) liveFor(previous) else null)
    }

    /**
     * 输入条模型下拉直接切模型：settings 决定下一次请求，state.model 决定顶栏/输入条显示。
     * 以前只能去模型配置页改：settings 变了但当前会话顶栏还顶着旧名字（用户反馈的「header 不一致」）。
     */
    fun switchModel(model: String) {
        val state = chat ?: return
        if (state.readOnly) return
        val m = model.trim()
        if (m.isEmpty() || m == state.model) return
        settings.setModel(m)
        state.model = m
    }

    /**
     * 计划模式开关（M18，上游 `dsh-plan-mode` 的 `/plan`）：
     * - 轮次进行中：只记 pending，由引擎在下一个 step 起点落 `plan/mode`（并给模型一条 narration）；
     * - idle：立即落库；如果上一次请求描述的是另一态，补一条 `source.kind:"plan-mode"` 的 user 通知；
     * - 还没建会话文件（新会话第一条消息之前）：先记内存，建会话时补写（SessionGateway.create）。
     */
    fun setPlanMode(active: Boolean) {
        val state = chat ?: return
        if (state.readOnly) return
        if (active == planState.selected()) return
        if (state.streaming || handle == null) {
            // 还没发第一条消息（没有会话文件）时「开→关」= 撤销待生效，不留下「下一步生效」的计划条
            if (handle == null && active == planState.active) {
                planState.pending = null
                planState.narrate = false
                syncPlanUi()
                return
            }
            planState.pending = active
            planState.narrate = true
            syncPlanUi()
            return
        }
        viewModelScope.launch { applyPlanSelection(active, createSession = false) }
    }

    /** `set()` 的四种结果（上游 `dsh-plan-mode` 的 committed / queued / cancelled / noop）。 */
    private enum class PlanSelection { COMMITTED, QUEUED, CANCELLED, NOOP }

    /**
     * 上游 `PlanModeController.set()` 的逐字移植（M21 把结果从 Unit 提升为四态，
     * 供 `/plan` 的 command/done 文案使用）。[createSession] = 命令入口：会话不存在时先建，
     * 这样 command/run → plan/mode → command/done 都落在同一个 v4 文件里。
     */
    private suspend fun applyPlanSelection(active: Boolean, createSession: Boolean): PlanSelection {
        val state = chat ?: return PlanSelection.NOOP
        if (state.readOnly) return PlanSelection.NOOP
        if (active == planState.selected()) return PlanSelection.NOOP
        val existing = handle
        if (state.streaming || (existing == null && !createSession)) {
            // 同上：新会话第一条消息前的「开→关」直接撤销，不留 pending
            if (existing == null && !createSession && active == planState.active) {
                planState.pending = null
                planState.narrate = false
                syncPlanUi()
                return PlanSelection.CANCELLED
            }
            planState.pending = active
            planState.narrate = true
            syncPlanUi()
            return if (planState.active == active) PlanSelection.CANCELLED else PlanSelection.QUEUED
        }
        val session = existing ?: ensureCommandSession() ?: return PlanSelection.NOOP
        withContext(Dispatchers.IO) { session.appendPlanMode(active) }
        val told = planState.activeAtLastHeader
        planState.active = active
        planState.entered = planState.entered || active
        planState.pending = null
        if (told != null && told != active) {
            val text = if (active) {
                "The user switched this session to plan mode."
            } else {
                "The user switched this session back to the default mode."
            }
            withContext(Dispatchers.IO) {
                session.appendUserMessageTexts(
                    listOf(text),
                    source = buildJsonObject {
                        put("kind", "plan-mode")
                        put("form", "notice")
                        put("summary", text)
                    },
                )
            }
            state.messages += ChatMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.USER,
                content = listOf(ContentBlock(BlockType.TEXT, text = text)),
                sourceKind = "plan-mode",
                sourceSummary = text,
            )
        }
        syncPlanUi()
        return PlanSelection.COMMITTED
    }

    /**
     * 命令入口的会话保障：上游命令总是对着一个已存在的 agent 运行；原生新会话在第一条消息前
     * 还没有 v4 文件，所以 `/plan` 会先把文件建出来（request/header 与正常建会话一致）。
     */
    private suspend fun ensureCommandSession(): SessionSession? {
        handle?.let { return it }
        val created = withContext(Dispatchers.IO) {
            runCatching {
                repo.create(cwd = workspace.absolutePath, model = settings.model).also { session ->
                    session.appendRequestHeader(
                        provider = providerLabel(),
                        model = settings.model,
                        reasoningEffort = settings.reasoningEffort,
                        maxTokens = settings.maxTokens,
                    )
                }
            }.getOrNull()
        } ?: return null
        handle = created
        chat?.sessionId = created.header.id
        refreshSessions()
        return created
    }

    /**
     * 一次「发送」入口（M21）：整行先按上游 `parseCommand()` 解析。
     *
     * - 已知命令（`/plan`、`/model`、`/permission`、`/help`）：直接执行，**不产生模型消息**；
     * - 名称是用户可调用技能：按普通提示词发送（`/name` 原文进模型，pre-step 再注入 `<skill_content>`）；
     * - 其余 `/xxx`：与上游客户端适配器一致，拒绝而不是降级成提示词；
     * - 普通文本：走 [send]。
     *
     * @return true = 已接受（调用方可以清空输入框）；false = 保留草稿（未知命令/只读/运行中）。
     */
    fun submit(text: String): Boolean {
        val state = chat ?: return false
        if (state.readOnly) return false
        val hasImages = state.pendingImages.isNotEmpty()
        val hasFiles = state.pendingFiles.isNotEmpty()
        val command = parseCommandLine(text)
        if (command == null) {
            // 上游适配器：任何以 `/` 开头、却解析不出已知命令的行都不降级成提示词。
            // （技能 `/name` 会解析成 command，再在下面按技能目录认领。）
            if (text.startsWith("/")) {
                showCommandNotice(state, "未知命令 ${text.takeWhile { !it.isWhitespace() }}")
                return false
            }
            if (state.streaming) return false
            if (text.isBlank() && !hasImages && !hasFiles) return false
            if (hasImages && !imageInputAvailable) {
                state.attachError = "当前模型未开启识图：设置 → 模型与参数配置 → 「模型支持识图」打开后再发"
                return false
            }
            send(text)
            return true
        }
        if (hasImages || hasFiles) {
            // 命令走的是另一条执行路径（不产生模型消息），附件没有落点：保留草稿让用户删附件或换普通消息
            showCommandNotice(state, "斜杠命令不能带附件")
            return false
        }
        if (command.name == "plan") {
            executePlanCommand(command, state)
            return true
        }
        // P5（M57 第二批）：插件用 ctx.commands.register 注册的斜杠命令。
        // 命令不进模型轮次，结果用 command notice 显示（与 DSH 一致）。
        val pluginCommand = pluginsUi.asSequence()
            .filter { it.enabled }
            .flatMap { record -> record.commands.asSequence().map { record to it } }
            .firstOrNull { it.second.name == command.name }
        if (pluginCommand != null) {
            val (record, pluginCmd) = pluginCommand
            viewModelScope.launch {
                val result = withContext(Dispatchers.IO) {
                    pluginToolRuntime.invokeCommand(record, pluginCmd.name, command.rawInput, workspace)
                }
                val text = if (result.ok) {
                    result.text.ifBlank { "插件命令 /${pluginCmd.name} 已完成" }
                } else {
                    "插件命令失败：${result.error ?: "未知错误"}"
                }
                showCommandNotice(state, text)
                Log.i("DshCmd", "plugin command /${pluginCmd.name} from ${record.id} ok=${result.ok}")
            }
            return true
        }
        if (command.name == "model") {
            executeModelCommand(command, state)
            return true
        }
        if (command.name == "permission") {
            executePermissionCommand(command, state)
            return true
        }
        if (command.name == "compact") {
            executeCompactCommand(command, state)
            return true
        }
        if (command.name == "help") {
            executeHelpCommand(command, state)
            return true
        }
        val skill = skillsUi.firstOrNull { it.name == command.name && it.userInvocable }
            // 进程刚起来时 skillsUi 可能还没扫完（调试通道 dsh_send 会抢在 init 的刷新之前）：
            // 斜杠行不是已知命令时做一次同步快照，避免把合法技能误判成未知命令。
            ?: runCatching {
                skills.snapshot().skills.firstOrNull { it.name == command.name && it.userInvocable }
            }.getOrNull()
        if (skill != null) {
            if (state.streaming) return false
            send(text)
            return true
        }
        showCommandNotice(state, "未知命令 /${command.name}")
        return false
    }

    /**
     * `/plan [off|message]`（上游 dsh-plan-mode 的命令处理器逐字移植）：
     * - `off`：选 false，成功文案按 committed/queued/cancelled/noop 分支；
     * - 其余：选 true；非空消息作为**普通用户消息**提交（`agent.steer()` 的等价物）。
     * 命令本身与结果都不进模型历史，只落 command/run + command/done 两条日志事件。
     */
    private fun executePlanCommand(command: CommandInvocation, state: ChatSessionState) {
        val message = command.rawInput.trim()
        val commandId = nextCommandId()
        viewModelScope.launch {
            val session = ensureCommandSession()
            if (session == null) {
                showCommandNotice(state, "无法创建会话")
                return@launch
            }
            withContext(Dispatchers.IO) { session.appendCommandRun(commandId, "plan", command.rawInput) }
            val off = message == "off"
            val outcome = applyPlanSelection(active = !off, createSession = true)
            val result = planCommandText(off, outcome)
            withContext(Dispatchers.IO) { session.appendCommandDone(commandId, "success", result) }
            syncPlanUi()
            showCommandNotice(state, result)
            // 上游：`/plan [message]` 选完模式后用 agent.steer() 提交消息（下一步的普通 user 消息）。
            if (!off && message.isNotEmpty() && !state.streaming && !state.readOnly) {
                startTurn(state, TurnInput(message))
            }
        }
    }

    /**
     * `/model [名字]`（M40）：
     * - 不带参数：请 ChatComposer 打开模型面板（与点输入条模型 pill 是同一个面板）；
     * - 带参数：直接切模型（`settings.model` + 当前会话顶栏），下一轮请求生效；
     * - 两条路径都不建会话文件、不产生模型消息——用户只是想换模型，不该因此多出一条空会话。
     */
    private fun executeModelCommand(command: CommandInvocation, state: ChatSessionState) {
        val arg = unquoteArg(command.rawInput)
        if (arg.isEmpty()) {
            state.pendingCommandSheet = CommandSheet.MODEL
            Log.i("DshCmd", "model: open sheet session=${state.sessionId}")
            return
        }
        if (arg.any { it.isWhitespace() }) {
            showCommandNotice(state, "模型名不能带空格：/model <名字>")
            Log.i("DshCmd", "model: reject arg=$arg")
            return
        }
        val before = settings.model
        switchModel(arg)
        val after = settings.model
        val text = when {
            before == after -> "当前已经是 $after"
            state.streaming -> "模型已切换为 $after（这条回复结束后生效）"
            else -> "模型已切换为 $after"
        }
        showCommandNotice(state, text)
        Log.i("DshCmd", "model: $before -> $after streaming=${state.streaming}")
    }

    /**
     * `/permission [auto|ask]`（M40）：
     * - 不带参数：请 ChatComposer 打开审批方式面板；
     * - 带参数：直接改 `settings.approvalMode`（与设置 → 行为 → 工具审批同一个值）；
     * - 参数非法：拒绝并给出用法，不改任何设置。
     */
    private fun executePermissionCommand(command: CommandInvocation, state: ChatSessionState) {
        val raw = command.rawInput.trim()
        if (raw.isEmpty()) {
            state.pendingCommandSheet = CommandSheet.PERMISSION
            Log.i("DshCmd", "permission: open sheet current=${settings.approvalMode}")
            return
        }
        val mode = parseApprovalArg(raw)
        if (mode == null) {
            showCommandNotice(state, "用法：/permission auto|ask（当前：${approvalLabel(settings.approvalMode)}）")
            Log.i("DshCmd", "permission: reject arg=$raw")
            return
        }
        val before = settings.approvalMode
        if (before == mode) {
            showCommandNotice(state, "工具审批已经是：${approvalLabel(mode)}")
        } else {
            settings.setApprovalMode(mode)
            showCommandNotice(state, "工具审批已改为：${approvalLabel(mode)}")
        }
        Log.i("DshCmd", "permission: $before -> $mode")
    }

    /**
     * `/compact`（M43，上游 `dsh-command-compact` + `dsh-compaction-basic` 的手工路径）：
     * 把较早的历史压成一份摘要（checkpoint 消息），最近一段原样保留；不消耗模型轮次。
     *
     * 事务顺序与桌面逐字节同形（docs/67 §二）：command/run → compaction/start → 摘要请求 →
     * compaction/summary → 替换消息（surfaceOp=replace）→ compaction/end → command/done。
     * 失败时补一条带 error 的 compaction/end + command/done(kind=error)，**不落替换消息**。
     */
    private fun executeCompactCommand(command: CommandInvocation, state: ChatSessionState) {
        if (command.rawInput.trim().isNotEmpty()) {
            showCommandNotice(state, Compaction.USAGE_TEXT)
            Log.i("DshCmd", "compact: reject arg=${command.rawInput}")
            return
        }
        if (state.streaming) {
            showCommandNotice(state, Compaction.BUSY_TEXT)
            Log.i("DshCmd", "compact: busy streaming")
            return
        }
        val session = handle
        val visible = state.messages.toList()
        val nodes = visible.mapNotNull { m ->
            m.seq?.let { seq -> Compaction.Node(seq, Compaction.estimateMessageTokens(m), m.role == MessageRole.TOOL) }
        }
        // 还有没落库的消息（极少数：流式/分支刚插入）→ 区间和 seq 对不上，按「忙」处理更安全
        if (session == null || nodes.size != visible.size) {
            showCommandNotice(state, if (session == null) Compaction.NO_HISTORY_TEXT else Compaction.BUSY_TEXT)
            Log.i("DshCmd", "compact: no session or unsaved messages session=${state.sessionId}")
            return
        }
        val range = Compaction.selectRange(nodes, Compaction.retainBudgetFor(nodes.sumOf { it.tokens }))
        if (range == null) {
            showCommandNotice(state, Compaction.NO_HISTORY_TEXT)
            Log.i("DshCmd", "compact: nothing compactable messages=${nodes.size}")
            return
        }
        val shadowed = visible.subList(range.first, range.last + 1)
        val shadowedSeqs = nodes.subList(range.first, range.last + 1).map { it.seq }
        var shadowedMessages = shadowed
        var shadowedTokens = nodes.subList(range.first, range.last + 1).sumOf { it.tokens }
        val compactionId = UUID.randomUUID().toString()
        val commandId = nextCommandId()
        state.statusText = "正在压缩…"
        Log.i(
            "DshCmd",
            "compact: start id=$commandId range=${shadowedSeqs.first()}..${shadowedSeqs.last()} " +
                "items=${shadowedSeqs.size} tokens=$shadowedTokens keep=${nodes.size - shadowedSeqs.size}",
        )
        viewModelScope.launch {
            withContext(Dispatchers.IO) { session.appendCommandRun(commandId, "compact", command.rawInput) }
            val startSeq = withContext(Dispatchers.IO) { session.appendCompactionStart(compactionId, commandId) }
            var failure: String? = null
            val summary = runCatching {
                engine.summarizeCompaction(shadowedMessages, llmConfig()) { offload ->
                    // M48（上游 `compaction/summary-error`）：摘要请求只含选区，超图片预算时
                    // 在选区内卸载最老的图 → 落 image/offload → 更新界面 → 重试摘要。
                    if (chat !== state || handle !== session) return@summarizeCompaction null
                    val targets = ImageOffload.selectOldest(shadowedMessages, offload.count)
                    if (targets.isEmpty()) {
                        Log.i("DshCmd", "compact: summary image-offload: no retained image left in the region")
                        return@summarizeCompaction null
                    }
                    val offloadSeq = withContext(Dispatchers.IO) { session.appendImageOffload(targets) }
                    if (offloadSeq == null) {
                        Log.i("DshCmd", "compact: summary image-offload: session rejected the selection")
                        return@summarizeCompaction null
                    }
                    targets.forEach { target ->
                        val index = state.messages.indexOfFirst { it.seq == target.seq }
                        if (index >= 0) {
                            state.messages[index] =
                                state.messages[index].markOffloadedImages(target.imageIndexes.toSet())
                        }
                    }
                    shadowedMessages = ImageOffload.markOffloaded(shadowedMessages, targets)
                    val before = shadowedTokens
                    shadowedTokens = shadowedMessages.sumOf { Compaction.estimateMessageTokens(it) }
                    Log.i(
                        "DshCmd",
                        "compact: summary image-offload: offloaded ${targets.sumOf { it.imageIndexes.size }} image(s) " +
                            "from ${targets.size} message(s) seq=$offloadSeq tokens=$before->$shadowedTokens",
                    )
                    shadowedMessages
                }
            }.getOrElse { error ->
                failure = error.message ?: error.toString()
                null
            }
            // 摘要期间用户切了会话/又发了一轮：区间已经不是同一个面，语义等同上游的 SurfaceChanged
            if (chat !== state || handle !== session) failure = Compaction.CHANGED_TEXT
            val framed = summary?.let { Compaction.frameSummary(it.text) }
            val framedTokens = framed?.let {
                Compaction.estimateContentTokens(it.map { text -> ContentBlock(BlockType.TEXT, text = text) }) +
                    Compaction.BLOCK_OVERHEAD
            }
            if (failure == null && (framed == null || framedTokens == null || framedTokens >= shadowedTokens)) {
                failure = Compaction.NO_SUMMARY_TEXT
            }
            if (failure != null || summary == null || framed == null) {
                val text = failure ?: Compaction.NO_SUMMARY_TEXT
                withContext(Dispatchers.IO) {
                    session.appendCompactionEnd(compactionId, commandId, error = text)
                    session.appendCommandDone(commandId, "error", text)
                }
                state.statusText = null
                showCommandNotice(state, text)
                Log.i("DshCmd", "compact: failed ${text.take(60)}")
                return@launch
            }
            val summarySeq = withContext(Dispatchers.IO) {
                session.appendCompactionSummary(
                    compactionId = compactionId,
                    sourceCommandId = commandId,
                    summary = summary.text,
                    rawOutput = summary.rawOutput,
                    shadowedRange = shadowedSeqs.first()..shadowedSeqs.last(),
                    shadowedSeqs = shadowedSeqs,
                    shadowedTokenCount = shadowedTokens,
                    provider = providerLabel(),
                    model = settings.model,
                    maxTokens = summary.maxTokens,
                    usage = summary.usage,
                )
            }
            val checkpointSeq = withContext(Dispatchers.IO) {
                session.appendCompactionCheckpoint(
                    compactionId = compactionId,
                    sourceCommandId = commandId,
                    texts = framed,
                    startSeq = shadowedSeqs.first(),
                    endSeq = shadowedSeqs.last(),
                    sourceEventSeqs = listOf(startSeq, summarySeq) + shadowedSeqs,
                )
            }
            withContext(Dispatchers.IO) { session.appendCompactionEnd(compactionId, commandId) }
            val result = Compaction.resultText(shadowedSeqs.size, shadowedTokens)
            withContext(Dispatchers.IO) { session.appendCommandDone(commandId, "success", result, summarySeq) }
            // 会话面替换：区间换成一条 checkpoint 消息（surfaceOp 同形；重开会话时 projection 也这么折）
            val startIndex = state.messages.indexOfFirst { it.seq == shadowedSeqs.first() }
            if (startIndex >= 0) {
                state.messages.add(
                    startIndex,
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = MessageRole.USER,
                        content = framed.map { ContentBlock(BlockType.TEXT, text = it) },
                        seq = checkpointSeq,
                        sourceKind = Compaction.SOURCE_KIND,
                        // 与重开会话时的折叠同文（桌面 zh 文案）：已压缩 N 条历史记录（约 T tokens）
                        sourceSummary = "已压缩 ${shadowedSeqs.size} 条历史记录（约 $shadowedTokens tokens）",
                    ),
                )
                repeat(shadowedSeqs.size) { state.messages.removeAt(startIndex + 1) }
            }
            state.statusText = null
            showCommandNotice(state, result)
            refreshSessions()
            Log.i("DshCmd", "compact: done $result checkpointSeq=$checkpointSeq")
        }
    }

    /**
     * `/help [命令名]`（M41）：
     * - 不带参数：请 ChatComposer 打开命令帮助面板（列出 [DSH_COMMANDS]，点一行插入输入框）；
     * - 带参数：直接在 notice 里给这一条命令的用法和说明（`/help plan`），不弹面板；
     * - 参数不是宿主命令：拒绝并提示「/help 查看全部命令」，不建会话、不产生模型消息。
     *
     * 上游 `/help` 的实现没有随启动器发布（.upstream 只有 launcher），文案按客户端习惯自己写；
     * 命令表本身与 `/` 菜单、派发共用 [DSH_COMMANDS]，所以帮助里不会出现「点了没反应」的命令。
     */
    private fun executeHelpCommand(command: CommandInvocation, state: ChatSessionState) {
        val raw = command.rawInput.trim().removePrefix("/").trim()
        if (raw.isEmpty()) {
            state.pendingCommandSheet = CommandSheet.HELP
            Log.i("DshCmd", "help: open sheet commands=${DSH_COMMANDS.size}")
            return
        }
        val target = findCommand(raw)
        if (target == null) {
            showCommandNotice(state, "没有这个命令：/$raw。输入 /help 查看全部命令")
            Log.i("DshCmd", "help: reject arg=$raw")
            return
        }
        showCommandNotice(state, "${target.display()}：${target.description}")
        Log.i("DshCmd", "help: ${target.name}")
    }

    /** `/permission` 与设置页共用的中文档位名（空/未知按「每次询问」显示）。 */
    private fun approvalLabel(mode: String): String = if (mode == "auto") "自动允许" else "每次询问"

    /** 命令参数去引号：`/model "x"` 与 `/model x` 等价。 */
    private fun unquoteArg(raw: String): String {
        val t = raw.trim()
        return if (t.length >= 2 && t.first() == '"' && t.last() == '"') t.substring(1, t.length - 1).trim() else t
    }

    /** 上游 `/plan` 的四态文案（逐字取自 `dsh-plan-mode` 的命令处理器）。 */
    private fun planCommandText(off: Boolean, outcome: PlanSelection): String {
        if (!off) {
            return if (outcome == PlanSelection.COMMITTED) {
                "Plan mode on. Use /plan off to leave."
            } else {
                "Entering plan mode (applies from the next step). Use /plan off to leave."
            }
        }
        return when (outcome) {
            PlanSelection.COMMITTED -> "Plan mode off."
            PlanSelection.QUEUED -> "Leaving plan mode (applies from the next step)."
            PlanSelection.CANCELLED -> "Plan mode entry cancelled."
            PlanSelection.NOOP -> if (planState.active) {
                "Leaving plan mode (applies from the next step)."
            } else {
                "Plan mode is already inactive."
            }
        }
    }

    private fun nextCommandId(): String {
        commandSeq += 1
        return "cmd-$commandInstanceToken-$commandSeq"
    }

    /** 命令结果条（上游「分离结果 notice」）：不写聊天正文，5 秒后自动消失。 */
    private fun showCommandNotice(state: ChatSessionState, text: String) {
        state.commandNotice = text
        viewModelScope.launch {
            delay(5_000)
            if (state.commandNotice == text) state.commandNotice = null
        }
    }

    fun dismissCommandNotice() {
        chat?.commandNotice = null
    }

    /** `@file` 候选（IO；查询在光标处每次变化时调用）。 */
    suspend fun fileCandidates(query: String): List<FileCandidate> = withContext(Dispatchers.IO) {
        runCatching { fileReferenceSearch.candidates(query) }.getOrElse { emptyList() }
    }

    /** 把引擎持有 [planState] 同步到 Compose（计划条/菜单用）。 */
    private fun syncPlanUi() {
        val state = chat ?: return
        state.planActive = planState.active
        state.planPending = planState.pending
        state.planEntered = planState.entered
    }

    /** 后台轮次改的是它自己的计划状态，只能刷新它自己的 Compose 字段。 */
    private fun syncPlanUiFor(live: LiveChat) {
        val state = live.state
        state.planActive = live.plan.active
        state.planPending = live.plan.pending
        state.planEntered = live.plan.entered
    }

    fun send(text: String) {
        val trimmed = text.trim()
        val state = chat ?: return
        // 旧版备份导入的会话是只读副本：不允许续写（源数据契约：导入只读）
        if (state.readOnly || state.streaming) return
        state.attachError = null
        val images = state.pendingImages.map { it.ref }
        val files = state.pendingFiles.toList()
        if (trimmed.isEmpty() && images.isEmpty() && files.isEmpty()) return
        if (images.isNotEmpty() && !imageInputAvailable) {
            state.attachError = "当前模型未开启识图：设置 → 模型与参数配置 → 「模型支持识图」打开后再发"
            return
        }
        // 文件：文本内联；非文本/超大文件按 Operit 形状放 <attachment>，模型用 read/read_image 读。
        val blocks = buildList {
            if (trimmed.isNotEmpty()) add(trimmed)
            files.forEach { file ->
                val path = file.path
                if (path != null) {
                    add(
                        """<attachment id="${xmlAttr(path)}" filename="${xmlAttr(file.name)}" """ +
                            """type="${xmlAttr(file.mime ?: "application/octet-stream")}" size="${file.size}"/>""",
                    )
                } else {
                    add("[附件: ${file.name}]\n\n${file.text}")
                }
            }
            if (files.any { it.isReference }) {
                add(ATTACHMENT_REFERENCE_HINT)
            }
        }
        // 待发附件一次性交给这一轮（失败时 send 提前返回，草稿与图片都还在）
        if (images.isNotEmpty()) state.pendingImages.clear()
        if (files.isNotEmpty()) state.pendingFiles.clear()
        startTurn(
            state,
            TurnInput(
                text = trimmed,
                textBlocks = if (files.isEmpty()) emptyList() else blocks,
                images = images,
            ),
        )
    }

    /**
     * 提醒轮询：只投递「当前打开的会话」的到期提醒（快照文案：提醒属于当前会话）。
     * 多条同时到期时逐轮投递；停机/后台错过的，只补最近一次（每个任务投递一次）。
     */
    private fun tickSchedules() {
        val live = current ?: return
        tickSchedulesFor(live)
    }

    /** 只投递某一条会话自己的提醒（并行轮次各投各的）。 */
    private fun tickSchedulesFor(live: LiveChat) {
        val state = live.state
        if (state.readOnly || state.streaming) return
        val prompt = nextSchedulePrompt(live) ?: return
        startTurn(state, TurnInput(prompt))
    }

    /**
     * 取下一条到期提醒（不要求 idle；goal 续轮循环里也用它让路给提醒）。
     * 返回的 prompt 会顺手把任务标成已投递、把后续触发时间推到未来。
     */
    private fun nextSchedulePrompt(live: LiveChat): String? {
        val state = live.state
        if (state.readOnly) return null
        if (live.pendingSchedules.isNotEmpty()) return live.pendingSchedules.removeFirst()
        val sessionId = state.sessionId ?: return null
        val now = System.currentTimeMillis()
        val due = schedules.listForSession(sessionId).filter { it.active && it.nextRunAt <= now }
        if (due.isEmpty()) return null
        due.forEach { task ->
            val anchor = if (task.timing.kind == "every_seconds") task.updatedAt else task.createdAt
            val next = runCatching { ScheduleTime.nextOccurrence(task.timing, now, anchor) }.getOrNull()
            runCatching { schedules.markDelivered(task.id, now, next) }
            live.pendingSchedules.addLast(task.prompt)
        }
        return live.pendingSchedules.removeFirst()
    }

    /** 当前会话的提醒（聊天页「提醒」面板打开时重新读）。 */
    fun currentSchedules(): List<ScheduledTask> =
        chat?.sessionId?.let { schedules.listForSession(it) } ?: emptyList()

    /** 用户手动删除一条提醒。 */
    fun deleteSchedule(id: String) {
        val sessionId = chat?.sessionId ?: return
        viewModelScope.launch { withContext(Dispatchers.IO) { runCatching { schedules.delete(id, sessionId) } } }
    }

    // ---------------- /goal 面板：用户侧的暂停/恢复/编辑/清空 ----------------

    suspend fun pauseGoal(): String? {
        val error = mutateGoal { store, view -> store.pauseAsUser(view.goal.id, view.goal.revision) }
        // 暂停的是当前会话的目标：只中止当前会话自己的轮次，不能误停后台别的会话
        val live = current
        if (error == null && live?.state?.streaming == true) {
            live.stop.requested = true
            live.job?.cancel()
        }
        return error
    }

    suspend fun resumeGoal(): String? {
        val error = mutateGoal { store, view -> store.resumeAsUser(view.goal.id, view.goal.revision) }
        // 上游 goal/changed 会触发 driver：idle 点 resume 应当立刻起下一轮
        if (error == null) kickGoalIfIdle()
        return error
    }

    suspend fun editGoal(objective: String?, maxGoalRounds: Int?): String? {
        val error = mutateGoal { store, view -> store.editAsUser(view.goal.id, view.goal.revision, objective, maxGoalRounds) }
        if (error == null) kickGoalIfIdle()
        return error
    }

    suspend fun clearGoal(): String? = mutateGoal { store, view ->
        store.clearAsUser(view.goal.id, view.goal.revision)
        store.current()
    }

    /** 成功返回 null；失败返回给面板显示的文案。状态更新回主线程。 */
    private suspend fun mutateGoal(block: (GoalStore, GoalView) -> Unit): String? {
        val session = handle ?: return "没有打开的会话"
        val store = goalStore(session)
        val view = withContext(Dispatchers.IO) { runCatching { store.current() }.getOrNull() } ?: return "当前没有目标"
        val error = withContext(Dispatchers.IO) {
            runCatching { block(store, view) }.exceptionOrNull()?.let { it.message ?: "操作失败" }
        }
        chat?.goal = withContext(Dispatchers.IO) { runCatching { store.current() }.getOrNull() }
        return error
    }

    /** idle 点被用户重新 arm 的目标要立刻续轮（上游 goal/changed → requestDrive）。 */
    private fun kickGoalIfIdle() {
        val state = chat ?: return
        if (state.streaming || state.readOnly) return
        val next = engine.nextGoalTurn(ComposeTurnUi(state), TurnOutcome.COMPLETED) ?: return
        startTurn(state, next)
    }

    fun stop() {
        // 停止当前前台这条（它正在跑）；用户看着一条没跑的会话时，停止最近启动的那条。
        val live = current?.takeIf { it in activeTurns } ?: lastActiveTurn ?: current ?: return
        // 用户主动 interrupt：取消后由引擎的收尾逻辑决定 pause（goal 轮）还是 disarm。
        // streaming 由取消收尾统一置 false：先写完 turn/end 再允许下一次发送，
        // 否则用户立刻再发一条会与取消收尾并发写同一个会话文件。
        live.stop.requested = true
        live.job?.cancel()
        live.state.apply {
            // 最后一条 assistant 消息也要结束流式态，否则思考块会一直显示「思考中」
            val lastIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
            if (lastIndex >= 0) messages[lastIndex] = messages[lastIndex].copy(streaming = false)
        }
        // runningStates / activeTurns 不在这里清：等引擎的取消收尾（finally）统一清，
        // 否则 keep-alive 会在 turn/end 还没写完时先停掉（写一半的会话最怕进程被冻）。
        //
        // 兜底（用户 2026-11-11 反馈「停掉了还在运行、发消息没反应、开新话题被挡住」）：
        // 取消收尾可能卡在阻塞的工具/网络调用里永远等不到 finally。3 秒后若这条轮次
        // 还占着运行集合 / streaming，就强制清 UI 状态，至少让用户能继续发消息。
        val stopped = live
        viewModelScope.launch {
            delay(3000)
            val stillHeld = stopped in activeTurns || stopped.state.streaming
            if (stillHeld) forceStopUi(stopped, "stop timeout")
        }
    }

    private fun markRunning(live: LiveChat) {
        activeTurns += live
        lastActiveTurn = live
        runningStates = runningStates + live.state
    }

    private fun markStopped(live: LiveChat) {
        activeTurns -= live
        runningStates = runningStates - live.state
        if (lastActiveTurn === live) lastActiveTurn = activeTurns.lastOrNull()
    }

    /**
     * 轮次没收干净时的兜底：把「正在生成」状态强行清掉，并给用户一条明确提示。
     * 只在用户点过「停止」/ 状态明显是僵尸（没有 job）时调用，正常收尾不走这里。
     */
    private fun forceStopUi(live: LiveChat, reason: String) {
        Log.w("DshTurn", "force stop turn: $reason")
        live.job = null
        markStopped(live)
        live.state.streaming = false
        live.state.statusText = null
        val lastIndex = live.state.messages.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (lastIndex >= 0) live.state.messages[lastIndex] = live.state.messages[lastIndex].copy(streaming = false)
        live.state.commandNotice = "上一轮收尾超时，已强制停止；可以直接发消息。"
        syncKeepAlive()
    }

    /** 内部取消（不带用户 interrupt 语义）：换工作区/删会话时用。 */
    private fun cancelStream() {
        val live = current ?: return
        live.stop.requested = false
        live.job?.cancel()
        live.job = null
        live.state.streaming = false
    }


    /**
     * 把 ChatSessionState 适配成 core:engine 的 TurnUi。
     * 每轮一个实例：切会话会取消旧 turn，不会把新状态写进旧容器。
     */
    private inner class ComposeTurnUi(private val state: ChatSessionState) : TurnUi {
        override fun messages(): List<ChatMessage> = state.messages
        override fun title(): String = state.title

        override fun setTitle(title: String) {
            state.title = title
        }

        override fun setSessionId(id: String) {
            state.sessionId = id
        }

        override fun setStreaming(streaming: Boolean) {
            state.streaming = streaming
            // M76：运行状态条按本轮开始时间计时；结束不清零，行内文案会切回「已完成」。
            if (streaming) state.turnStartedAt = System.currentTimeMillis()
            // 大肥鱼（M59）：一轮开始 → THINKING；收口在 startTurn 的 finally 里（那里才是
            // 真正「turn end」的时刻，引擎有些取消路径不会经过 setStreaming(false)）。
            if (streaming) BigFish.onTurnStart(state, settings.reasoningEffort, workspace.name)
        }

        override fun setStatus(text: String?) {
            state.statusText = text
            BigFish.onStatus(state, text)
            // 通知栏里同步当前步骤（没有前台服务时 start() 是空操作，实例不存在就直接 return）
            if (text != null) TurnKeepAliveService.start(getApplication(), text)
        }

        override fun setError(text: String?) {
            state.error = text
            if (text != null) BigFish.onError(state, text)
        }

        override fun setUsage(text: String?) {
            state.usageText = text
        }

        /** M72：数字版用量 → BigFish 按会话/轮累加，turn/end 随快照喂给客户端插件。 */
        override fun setTokenUsage(inputTokens: Int, outputTokens: Int) {
            // M76：上下文圆环/明细用最近一次请求的真实 prompt tokens
            if (inputTokens > 0) state.lastPromptTokens = inputTokens
            if (outputTokens > 0) state.lastCompletionTokens = outputTokens
            BigFish.onTokenUsage(state, inputTokens, outputTokens)
        }

        override fun setGoal(view: GoalView?) {
            state.goal = view
        }

        override fun appendMessage(message: ChatMessage): Int {
            state.messages.add(message)
            return state.messages.lastIndex
        }

        override fun updateMessage(index: Int, message: ChatMessage) {
            if (index in state.messages.indices) state.messages[index] = message
        }

        /**
         * 自动压缩（M44）：引擎把 [shadowedSeqs] 这段历史换成 checkpoint。
         * 与会话面 replace 同形：第一条被顶掉的位置插入 checkpoint，其余移除。
         */
        override fun applyCompaction(shadowedSeqs: List<Long>, checkpoint: ChatMessage) {
            val first = state.messages.indexOfFirst { it.seq != null && it.seq in shadowedSeqs }
            if (first < 0) return
            state.messages.removeAll { it.seq != null && it.seq in shadowedSeqs }
            state.messages.add(first, checkpoint)
        }

        /**
         * 工具结果修剪（M46）：被剪的那条 tool 消息**原地**换掉（seq 指向新的 `tool/result` 事件），
         * 不挪位置 —— 请求体里工具结果必须紧跟它的调用。
         */
        override fun applyToolResultPrune(originalSeq: Long, replacement: ChatMessage) {
            val index = state.messages.indexOfFirst { it.seq == originalSeq }
            if (index >= 0) state.messages[index] = replacement
        }

        /**
         * 图片卸载（M47）：只给选中的 image 块补 `offloaded` 标记，消息与顺序不动；
         * 下一次请求组装会把这几张图换成占位文本。
         */
        override fun applyImageOffload(targets: List<ImageOffloadTarget>) {
            targets.forEach { target ->
                val index = state.messages.indexOfFirst { it.seq == target.seq }
                if (index >= 0) {
                    state.messages[index] = state.messages[index].markOffloadedImages(target.imageIndexes.toSet())
                }
            }
        }
    }

    /**
     * 跑一轮，然后按 goal activation 决定要不要在 idle 点自动续轮（上游 goal-round-driver）。
     * 循环本身在 core:engine；这里只挂协程并收尾 UI 状态。
     */
    private fun startTurn(state: ChatSessionState, input: TurnInput) {
        // 并行（M77）：每条会话都可以有自己的在跑轮次；这里按 state 找它自己的 LiveChat，
        // 不再要求「必须是当前前台会话」（提醒/通知可以在后台会话上起一轮）。
        val live = lives[state] ?: current?.takeIf { it.state === state } ?: return
        // 同一会话仍只允许一条轮次（工具调用/会话文件写入不能并发）
        if (live.job?.isActive == true || live.state.streaming) return
        // fork 的 seed 边界：本轮开始前的消息 = 已完成的轮次（当前轮不算）
        state.turnStartIndex = state.messages.size
        // 模型可能刚被改过：工具上下文里的图片能力门按当前设置重新绑定（read_image 的准入）
        live.toolContext.imageRoute = currentImageRoute()
        // 保活：turn 跑着的时候挂前台服务（+ 唤醒锁），切后台/锁屏不被冻/回收（docs/48）
        TurnKeepAliveService.stopListener = { stop() }
        startKeepAlive("对话进行中 · 切后台也会继续跑")
        Log.i(
            "DshTurn",
            "turn start session=${state.sessionId ?: "-"} foreground=${state === chat} model=${settings.model}",
        )
        val startedAt = System.currentTimeMillis()
        markRunning(live)
        live.job = viewModelScope.launch {
            try {
                live.engine.startTurn(ComposeTurnUi(state), input)
            } finally {
                state.streaming = false
                state.statusText = null
                // 大肥鱼：turn/end（成功 → SUCCESS 脉冲；state.error 非空 → ERROR）。
                // M66：把本轮最终回复正文一并塞进快照，鲸鱼娘挂件按上游规则取最后一段逐句播报。
                val finalReply = state.messages
                    .lastOrNull { it.role == MessageRole.ASSISTANT && it.error == null }
                    ?.content
                    ?.filter { it.type == BlockType.TEXT }
                    ?.joinToString("\n") { it.text }
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                // 先把「这一轮占着运行集合」的标志清掉，再跑收尾副作用：收尾里任何一步抛异常
                // 都不能让轮次永久占位（用户 2026-11-11：停止后仍显示运行、发不出消息）。
                live.job = null
                markStopped(live)
                Log.i("DshTurn", "turn end after ${System.currentTimeMillis() - startedAt}ms")
                runCatching { BigFish.onTurnEnd(state, state.error, finalReply) }
                    .onFailure { Log.w("DshTurn", "turn end hook failed", it) }
                // 跑完但用户正看着别的会话 → 列表右侧亮绿点（打开/切回即消）；
                // 会话已经被删（回收站）就不再标
                runCatching {
                    val id = state.sessionId
                    if (id != null && chat?.sessionId != id && lives.containsValue(live)) {
                        unreadDone = unreadDone + id
                    }
                    refreshSessions()
                }
                // 轮次收口瞬间才结算的通知不会被引擎的 step 边界看到：这里补一次唤醒（只唤醒这条会话）
                runCatching { maybeDeliverNoticesFor(state) }
                syncKeepAlive()
            }
        }
    }

    /**
     * 保活前台服务（docs/48）：有 turn 在跑、或有后台任务（bash run_in_background/job_*）在跑时
     * 就保持；都结束才停。**不要**在 turn 里直接 stop——否则 turn 结束后后台任务会被冻住。
     */
    private fun syncKeepAlive() {
        val turnRunning = activeTurns.isNotEmpty()
        val jobsRunning = runCatching {
            jobRegistry.listAll(previewChars = 0).any {
                it.job.status == xin.dshaa.core.jobs.JobStatus.RUNNING
            }
        }.getOrDefault(false)
        // 后台子代理也算：它跑在自己的 scope 上，主 turn 结束后还可能留在后台收尾
        val agentsRunning = runCatching {
            agentRegistry.all().any { it.status == xin.dshaa.core.agents.AgentStatus.RUNNING }
        }.getOrDefault(false)
        if (turnRunning || jobsRunning || agentsRunning) {
            startKeepAlive(
                when {
                    turnRunning -> "对话进行中 · 切后台也会继续跑"
                    jobsRunning -> "后台任务进行中"
                    else -> "子代理运行中"
                },
            )
        } else {
            TurnKeepAliveService.stop(getApplication())
        }
    }

    private fun startKeepAlive(text: String?) {
        TurnKeepAliveService.start(getApplication(), text)
    }

    /** 合并两路通知：后台任务（tool-jobs）+ 子代理（subagent-settled / agent-message）。 */
    private fun takeNoticesFor(owner: String?): List<UserNotice> {
        if (owner == null) return emptyList()
        val jobs = jobRegistry.takeNotices(owner).map { UserNotice(it.text, it.summary) }
        val agents = agentRegistry.takeNotices(owner).map { it.toUserNotice() }
        return jobs + agents
    }

    // ---- 插件（M26 导入/试运行，M27 工具接入） ----

    /**
     * 插件存储：`files/plugins/<id>/` + `files/plugins.json`。
     *
     * 导入/启用/重载/删除之后都要调 [syncPluginTools]：把「已启用 + 参数能归一成 JSON Schema」的工具
     * 注册进 `ToolRegistry`，下一次请求就能看到；停用/删除时先注销旧名字。
     */

    fun refreshPlugins() {
        pluginsUi = pluginStore.list()
        syncPluginTools()
    }

    /**
     * M70：打开插件**自己的**浏览器半侧（`dsh.client` 的 client.js），走通用客户端插件宿主，
     * 不再按插件写死原生窗口。结果（成功/为什么不能跑）回写到插件页顶部的 notice。
     */
    fun openClientPluginWindow(record: PluginRecord) {
        val message = xin.dshaa.nativeapp.pluginui.ClientPluginWindows.open(getApplication(), record)
        pluginNotice = message
    }

    /**
     * 按最新插件清单重建「插件工具 → 工具表」的映射。
     *
     * 先注销上一批名字（停用/删除/改名都靠这一步收回工具），再按清单注册。
     * 工具名和内置工具冲突时 [ToolRegistry.register] 拒绝，这里只打日志、不覆盖内置工具。
     */
    private fun syncPluginTools() {
        pluginToolNames.toList().forEach { registry.unregister(it) }
        pluginToolNames.clear()

        val injected = LinkedHashMap<String, MutableSet<String>>()
        pluginToolRuntime.refresh(pluginsUi, workspace).forEach { binding ->
            if (registry.register(pluginToolRuntime.toolFor(binding))) {
                pluginToolNames += binding.toolName
                injected.getOrPut(binding.pluginId) { LinkedHashSet() } += binding.toolName
            } else {
                Log.w("DshPlugin", "工具名冲突，未注入：${binding.toolName}（${binding.pluginId}）")
            }
        }
        pluginApiTools = pluginToolRuntime.apiTools().filter { it.name in pluginToolNames }
        pluginInjected = injected
    }

    fun dismissPluginNotice() {
        pluginNotice = null
    }

    fun importPlugin(uri: Uri) {
        if (pluginsBusy) return
        pluginsBusy = true
        pluginNotice = null
        viewModelScope.launch {
            val result = pluginStore.install(uri)
            pluginsUi = pluginStore.list()
            pluginNotice = result.message
            pluginsBusy = false
            syncPluginTools()
        }
    }

    fun setPluginEnabled(id: String, enabled: Boolean) {
        pluginStore.setEnabled(id, enabled)
        pluginsUi = pluginStore.list()
        syncPluginTools()
    }

    fun reloadPlugin(id: String) {
        if (pluginsBusy) return
        pluginsBusy = true
        viewModelScope.launch {
            // M37：重载会重跑 QuickJS 试运行（最长 12 秒），放 IO 线程，别把界面焊住。
            val result = withContext(Dispatchers.IO) { pluginStore.reload(id) }
            pluginsUi = pluginStore.list()
            pluginNotice = result.message
            pluginsBusy = false
            syncPluginTools()
        }
    }

    fun deletePlugin(id: String) {
        val result = pluginStore.uninstall(id)
        pluginsUi = pluginStore.list()
        pluginNotice = result.message
        syncPluginTools()
    }

    // ---- M67：内置 Operit 包（包管理 → 包） ----

    /** 扫描 `assets/operit-packages/` 下的 .js（第一次进「包」tab 时调用；结果缓存到 [operitPackagesUi]）。 */
    fun loadOperitPackages() {
        if (operitPackagesBusy || operitPackagesUi.isNotEmpty()) return
        operitPackagesBusy = true
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { operitBundled.load() }
            operitPackagesUi = list
            operitPackagesBusy = false
            Log.i(
                "DshPlugin",
                "内置 Operit 包 ${list.size} 个：可用 ${list.count { it.status == xin.dshaa.nativeapp.data.OperitStatus.READY }}、" +
                    "部分可用 ${list.count { it.status == xin.dshaa.nativeapp.data.OperitStatus.PARTIAL }}、" +
                    "需要 Key ${list.count { it.status == xin.dshaa.nativeapp.data.OperitStatus.NEEDS_ENV }}",
            )
        }
    }

    /**
     * 安装一个内置 Operit 包：现拼 `package.json + <包>.js` 的 zip，走和导入 zip 完全相同的链路
     * （ToolPkg 包装、试运行、工具注册）。网络/命令/设备权限仍要用户到插件页确认。
     */
    fun installOperitPackage(pluginId: String) {
        if (pluginsBusy) return
        val pkg = operitPackagesUi.firstOrNull { it.pluginId == pluginId } ?: return
        pluginsBusy = true
        pluginNotice = null
        viewModelScope.launch {
            val result = operitBundled.install(pluginStore, pkg)
            pluginsUi = pluginStore.list()
            pluginNotice = result.message
            pluginsBusy = false
            syncPluginTools()
            Log.i("DshPlugin", "内置包安装 ${pkg.pluginId} ok=${result.ok} ${result.message}")
        }
    }

    /** 插件页「确认授权」：危险能力逐项放行（红框确认后调用）。 */
    fun grantPluginPermission(id: String, raw: String) {
        val result = pluginStore.grant(id, raw)
        pluginNotice = result.message
        pluginsUi = pluginStore.list()
        syncPluginTools()
    }

    /** 插件详情里保存配置（M30）：JSON 对象文本；空文本 = 恢复默认。 */
    fun savePluginConfig(id: String, jsonText: String) {
        val result = pluginStore.setConfig(id, jsonText)
        pluginNotice = result.message
        pluginsUi = pluginStore.list()
        syncPluginTools()
    }

    // ---- 技能（M19） ----

    /** 技能扫描的根目录（设置页展示，便于用户知道该往哪放文件）。 */
    val skillRoots: List<SkillRoot> get() = skills.roots()

    /** App 私有目录（= DSH home）/ 工作区路径：设置页把长路径缩成 `files/…`、`workspace/…`。 */
    val filesDirPath: String get() = getApplication<Application>().filesDir.absolutePath
    val workspacePath: String get() = workspace.absolutePath

    /** 重新扫盘（设置页下拉/进入时调用；引擎每个 step 也会现扫）。 */
    fun refreshSkills() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { skills.snapshot() }
            skillsUi = snapshot.skills
            skillsComplete = snapshot.complete
            skillsWarnings = withContext(Dispatchers.IO) { skills.lastWarnings() }
        }
    }

    /**
     * 包管理页「导入技能」（Operit 技能 Tab 的导入入口）：
     * 选一个 SKILL.md → 校验 frontmatter → 写到 `files/skills/<name>.md` → 重扫。
     *
     * 写平铺 .md 而不是目录型技能：DSH 的文件系统 provider 两种都认，单文件最好搬。
     */
    fun importSkill(uri: Uri) {
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val displayName = runCatching {
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "SKILL.md"
                    if (!displayName.endsWith(".md", ignoreCase = true)) {
                        error("只支持 SKILL.md 文本文件（当前是 $displayName）")
                    }
                    val bytes = resolver.openInputStream(uri)?.use { readAtMost(it, 2 * 1024 * 1024) }
                        ?: error("读不到文件：$displayName")
                    if (bytes.size >= 2 * 1024 * 1024) error("文件太大（上限 2 MB）")
                    val text = bytes.toString(Charsets.UTF_8)
                    val parsed = SkillFrontmatter.parse(text)
                        ?: error("不是 SKILL.md：缺少 YAML frontmatter（第一行必须是 ---）")
                    val name = parsed.data["name"]?.takeIf { it.isNotEmpty() }
                        ?: error("frontmatter 缺少 name")
                    if (!isSkillName(name)) error("技能名不合法（要 kebab-case）：$name")
                    if (parsed.data["description"].isNullOrEmpty()) error("frontmatter 缺少 description")
                    val dir = File(getApplication<Application>().filesDir, "skills").apply { mkdirs() }
                    if (File(dir, name).isDirectory) {
                        error("已存在同名目录技能「$name」，先删掉旧目录再导入")
                    }
                    val target = File(dir, "$name.md")
                    val overwritten = target.exists()
                    target.writeText(text, Charsets.UTF_8)
                    if (overwritten) "已覆盖技能 $name" else "已导入技能 $name"
                }.getOrElse { "导入技能失败：${it.message}" }
            }
            skillNotice = message
            refreshSkills()
        }
    }

    fun dismissSkillNotice() {
        skillNotice = null
    }

    /**
     * 这个 step 起点要不要给模型补一条技能目录（上游 `dsh-tool-skill` 的 pre-step）：
     * 摘要没变 → null（请求体不动）；变了 → `source.kind:"skill-catalog"` 的 user 消息。
     */
    private fun skillCatalogNotice(session: SessionSession): UserNotice? {
        val previous = runCatching { lastSkillCatalogSource(session) }.getOrNull()
        val snapshot = runCatching { skills.snapshot() }.getOrElse { return null }
        val notice = SkillCatalog.decide(snapshot, previous) ?: return null
        return UserNotice(
            text = notice.text,
            summary = "${notice.entries.size} 个技能",
            kind = "skill-catalog",
            source = SkillCatalog.source(notice),
        )
    }

    /** 会话里最后一条技能目录消息的 source（null = 从没发布过）。 */
    private fun lastSkillCatalogSource(session: SessionSession): JsonObject? {
        for (event in session.events.asReversed()) {
            if (event.type != "user/message") continue
            val source = event.data?.let { data ->
                runCatching { data.jsonObject["source"]?.jsonObject }.getOrNull()
            } ?: continue
            val kind = source["kind"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            if (kind == "skill-catalog") return source
        }
        return null
    }

    /**
     * 用户直接 `/名字` 调用（M21，上游 `dsh-tool-skill` 的 pre-step 第一段）：
     * 从直接用户输入的 text 块里提取手势 → 只保留目录里 `userInvocable` 的技能 →
     * 逐个渲染 `<skill_content>` 并按首次出现顺序去重。
     *
     * 目录里没有的名字静默跳过（用户可能只是写了 `/usr/bin` 这类路径）；
     * `disable-model-invocation` 的技能在这里**可以**加载（这是它唯一的入口）。
     */
    private fun skillInvocationNotices(texts: List<String>): List<UserNotice> {
        val names = invokedSkillNames(texts)
        if (names.isEmpty()) return emptyList()
        val snapshot = runCatching { skills.snapshot() }.getOrElse { return emptyList() }
        val byName = snapshot.skills.associateBy { it.name }
        return names.mapNotNull { name ->
            val summary = byName[name] ?: return@mapNotNull null
            if (!summary.userInvocable) return@mapNotNull null
            val loaded = skills.loadForUser(name) as? SkillLoad.Ok ?: return@mapNotNull null
            UserNotice(
                text = renderSkillContent(loaded.skill),
                summary = name,
                kind = "skill-invocation",
                source = buildJsonObject {
                    put("kind", "skill-invocation")
                    put("name", name)
                    put("form", "instructions")
                },
            )
        }
    }

    /** 子代理通知 → 引擎可落库的 user 通知（source 字段逐字对齐上游 `dsh-subagent`）。 */
    private fun AgentNotice.toUserNotice(): UserNotice = UserNotice(
        text = text,
        summary = summary,
        kind = kind,
        texts = texts,
        source = buildJsonObject {
            put("kind", kind)
            put("form", form)
            if (summary.isNotEmpty()) put("summary", summary)
            if (senderSessionId != null) put("senderSessionId", senderSessionId)
        },
    )

    /**
     * 收件箱通知的宿主投递（上游 `completionDelivery: wakeup` / agent inbox）：
     * - 有轮次在跑：引擎在每个 step 边界自己取，这里不动；
     * - idle：带着通知唤醒一轮，通知由引擎在 `turn/start` 之后落成 user 消息
     *   （`source.kind:"tool-jobs"` / `"subagent-settled"`，与会话格式的顺序一致）。
     */
    private fun maybeDeliverNotices() {
        maybeDeliverNoticesFor(chat)
    }

    /** 只给指定会话投递通知（并行时不要把 A 的通知塞进 B 的轮次）。 */
    private fun maybeDeliverNoticesFor(target: ChatSessionState?) {
        viewModelScope.launch {
            val state = target ?: return@launch
            if (state.readOnly || state.streaming) return@launch
            val owner = state.sessionId ?: return@launch
            val notices = takeNoticesFor(owner)
            if (notices.isEmpty()) return@launch
            // M78：后台根会话也会被唤醒（以前只认当前打开的那条），日志记录是不是前台
            Log.i(
                "DshTurn",
                "notice wake session=$owner foreground=${state === chat} notices=${notices.size}",
            )
            startTurn(state, TurnInput("", notices = notices))
        }
    }

    /** 通知 owner 不是根会话就是子代理：分别走聊天唤醒 / 子代理续轮。 */
    private fun wakeOwner(owner: String?) {
        if (owner == null) return
        // M78：owner 可能是**任意一条**根会话（并行时前台只有一条）。对上哪条就投给它自己。
        // 旧实现只认 `chat?.sessionId`，后台根会话的「子代理/后台任务结算」永远等不到唤醒，
        // 只有把那条会话打开才补投。
        val live = liveById(owner)
        if (live != null) maybeDeliverNoticesFor(live.state) else wakeChildAgent(owner)
    }

    /**
     * 子代理空闲时被消息/结算唤醒：在它自己的引擎上开一轮（通知在 turn/start → step/start 之后落库）。
     * 跑着的子代理不打断：引擎在 step 边界取队列。收尾时再查一次队列，避免「取空 → 放标记」的竞争。
     */
    private fun wakeChildAgent(ownerId: String) {
        val runtime = childRuntimes[ownerId] ?: return
        synchronized(runtime) {
            if (runtime.state.streaming || runtime.wakeScheduled) return
            runtime.wakeScheduled = true
        }
        agentScope.launch {
            try {
                while (true) {
                    val notices = takeNoticesFor(ownerId)
                    if (notices.isEmpty()) break
                    runtime.turnJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                    try {
                        runtime.engine.startTurn(runtime.ui, TurnInput("", notices = notices))
                    } finally {
                        runtime.turnJob = null
                    }
                }
            } catch (e: CancellationException) {
                // App teardown / interrupt：队列里的通知留给下次启动
            } finally {
                runtime.wakeScheduled = false
                refreshSessions()
                if (agentRegistry.hasNotices(ownerId)) wakeChildAgent(ownerId)
            }
        }
    }

    /** 停止一个子代理：注册表取消启动协程，宿主再取消它当前的续轮协程。 */
    private fun interruptAgent(id: String) {
        if (!agentRegistry.interrupt(id)) throw IllegalArgumentException("agent $id is not known to this runtime")
        childRuntimes[id]?.turnJob?.cancel()
    }

    private fun agentEntries(scope: String, parentId: String?): List<AgentEntry> {
        if (parentId == null) return emptyList()
        val records = if (scope == "descendants") {
            agentRegistry.listDescendants(parentId)
        } else {
            agentRegistry.listChildren(parentId)
        }
        return records.map {
            AgentEntry(id = it.id, label = it.label, status = it.status.wire, parentId = it.parentId, depth = it.depth)
        }
    }

    /** 根会话（用户直接对话的 agent）看到的子代理工具后端；按 LiveChat 绑定归属。 */
    private inner class RootAgentHost(private val owner: () -> LiveChat?) : AgentHost {
        // 工具归属：这条轮次自己的会话（并行时 A 的工具不能算到 B 头上）
        override fun currentAgentId(): String? =
            owner()?.state?.sessionId?.takeIf { it.isNotBlank() }

        override fun currentDepth(): Int = 0

        override fun maxDepth(): Int = agentRegistry.limit()

        override suspend fun start(description: String, prompt: String, cwd: String?, fork: Boolean): AgentStart {
            val parentState = owner()?.state
                ?: throw IllegalArgumentException("subagent requires an active session")
            val parentId = parentState.sessionId?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("subagent requires an active session")
            val seed = if (fork) {
                parentState.messages.take(parentState.turnStartIndex.coerceIn(0, parentState.messages.size))
            } else {
                emptyList()
            }
            val childCwd = resolveAgentCwd(cwd)
            val session = createChildSession(parentId, childCwd, fork, depth = 1)
            val record = agentRegistry.start(parentId, 0, description, childCwd.absolutePath, id = session.header.id) { rec ->
                runChildAgent(rec, session, prompt, seed)
            }
            return AgentStart(record.id, record.depth)
        }

        override fun list(scope: String): List<AgentEntry> = agentEntries(scope, currentAgentId())

        override fun send(agentId: String, message: String): AgentSend {
            val sender = currentAgentId() ?: throw IllegalArgumentException("send_message requires an active session")
            val delivery = agentRegistry.send(sender, agentId, message)
            return AgentSend(delivery.messageId, delivery.agentId)
        }

        override fun interrupt(agentId: String) = interruptAgent(agentId)
    }

    /** 子代理内部再调用委派工具时的后端（深度 = 自己 + 1，超过上限由注册表拒绝）。 */
    private inner class ChildAgentHost(private val record: AgentRecord) : AgentHost {
        override fun currentAgentId(): String = record.id

        override fun currentDepth(): Int = record.depth

        override fun maxDepth(): Int = agentRegistry.limit()

        override suspend fun start(description: String, prompt: String, cwd: String?, fork: Boolean): AgentStart {
            // 先查深度：超限不要在磁盘上留一个孤儿会话
            val childDepth = record.depth + 1
            if (childDepth > agentRegistry.limit()) {
                throw IllegalArgumentException("subagent depth $childDepth exceeds maxDepth ${agentRegistry.limit()}")
            }
            val childCwd = resolveAgentCwd(cwd)
            val session = createChildSession(record.id, childCwd, fork, depth = childDepth)
            val child = agentRegistry.start(record.id, record.depth, description, childCwd.absolutePath, id = session.header.id) { rec ->
                runChildAgent(rec, session, prompt, emptyList())
            }
            return AgentStart(child.id, child.depth)
        }

        override fun list(scope: String): List<AgentEntry> = agentEntries(scope, record.id)

        override fun send(agentId: String, message: String): AgentSend {
            val delivery = agentRegistry.send(record.id, agentId, message)
            return AgentSend(delivery.messageId, delivery.agentId)
        }

        override fun interrupt(agentId: String) = interruptAgent(agentId)
    }

    /** 子代理的初始 cwd：省略继承父工作区；相对路径按父工作区解析；必须是已存在目录。 */
    private fun resolveAgentCwd(raw: String?): File {
        val trimmed = raw?.trim().orEmpty()
        val file = when {
            trimmed.isEmpty() -> File(workspace.absolutePath)
            File(trimmed).isAbsolute -> File(trimmed)
            else -> File(workspace.absolutePath, trimmed)
        }.canonicalFile
        require(file.isDirectory) { "cwd is not a directory: $raw" }
        return file
    }

    /**
     * 子代理的会话文件：id 即代理 id（上游 `subagentId == child session id`）。
     * header 带上 parentSession / origin=subagent / delegationDepth / isSeeded，进会话目录但不进主列表。
     */
    private suspend fun createChildSession(
        parentId: String,
        cwd: File,
        fork: Boolean,
        depth: Int,
    ): SessionSession = withContext(Dispatchers.IO) {
        repo.create(
            cwd = cwd.absolutePath,
            model = settings.model,
            parentSession = parentId,
            isSeeded = fork,
            origin = "subagent",
            delegationDepth = depth,
        ).also { created ->
            created.appendRequestHeader(
                provider = providerLabel(),
                model = settings.model,
                reasoningEffort = settings.reasoningEffort,
                maxTokens = settings.maxTokens,
            )
        }
    }

    /**
     * 跑一个子代理的一轮（独立会话 + 独立 TurnEngine，复用同一套工具/提示词）。
     *
     * 关键契约（对齐上游 in-process provider）：
     * - 子会话 header：parentSession / origin=subagent / delegationDepth，进会话目录但不进主列表；
     * - 初始任务 = prompt + 返回指导（两个 text 块；指导告诉它用 send_message 把结果交给父代理）；
     * - 子代理权限固定：需要审批的工具默认拒绝（只有全局 auto 才放行），与运行时上下文自述一致；
     * - 结束返回 closing text；结算通知由 `AgentRegistry` 按上游文案构造。
     */
    private suspend fun runChildAgent(
        record: AgentRecord,
        session: SessionSession,
        prompt: String,
        seed: List<ChatMessage>,
        /** 工具里的委派后端：workflow 子代理用「不可再委派」的宿主；普通子代理省略。 */
        agentHost: AgentHost? = null,
    ): AgentTerminal {
        val cwd = File(record.cwd ?: workspace.absolutePath)
        val activation = GoalActivationState()
        childGoalActivations[session.header.id] = activation
        val state = ChatSessionState(settings.model)
        state.sessionId = session.header.id
        state.title = record.label.ifBlank { "子代理" }
        state.messages.addAll(seed)
        val stop = TurnStopState()
        val ui = ComposeTurnUi(state)
        val childToolContext = toolContext.copy(
            workspace = cwd,
            cwd = cwd,
            // 子代理没有「问用户」的通道：问题一律按跳过处理（不让它挂住整轮）
            askUser = { questions -> questions.map { xin.dshaa.core.tools.AskAnswer(it.id) } },
            goalHost = goalHostFor({ session }, { activation }),
            scheduleHost = scheduleHostFor { record.id },
            jobOwner = { record.id },
            agents = agentHost ?: ChildAgentHost(record),
            // 子代理没有计划审批通道：exit_plan_mode 报「no plan-mode service is mounted」
            planMode = null,
        )
        // 子代理也用当前模型的图片能力门（copy 会带上父上下文里的旧快照，这里按当前设置刷新）
        childToolContext.imageRoute = currentImageRoute()
        val childEngine = TurnEngine(
            EngineDeps(
                workspacePath = cwd.absolutePath,
                config = { llmConfig() },
                providerLabel = { providerLabel() },
                reasoningEffort = { settings.reasoningEffort },
                registry = registry,
                toolContext = childToolContext,
                stop = stop,
                apiTools = {
                    promptPack.androidApiTools().filter { it.name in offeredToolNames() } + pluginApiTools
                },
                systemPrompt = {
                    promptPack.systemFor(
                        model = settings.model,
                        provider = providerLabel(),
                        tools = offeredToolNames(),
                        personaPrefix = personaPrefixText(),
                        personaSuffix = settings.assistantSystem,
                        extraSections = pluginPromptSections(),
                        variables = pluginPromptVariables(),
                    )
                },
                runtimeContext = { runtimeContextText(record.depth, cwd) },
                stream = { request, config -> client.stream(request, config) },
                sessions = object : SessionGateway {
                    override fun current(): SessionSession = session
                    override suspend fun create(): SessionSession = session
                },
                goals = { s -> GoalStore(s, activation = activation) },
                imageBytes = { id -> attachments.readImage(id) },
                imagePath = { id -> imageReadonlyPath(id) },
                imageBudget = { imageOffloadBudget() },
                approval = { call -> childApproval(call) },
                nextSchedulePrompt = { null },
                tickSchedules = {},
                onRequest = { _, _ -> },
                onSummaryRequest = { request -> dumpSummaryRequestIfRequested(request) },
                onLlmEvent = { _, _ -> },
                onToolResult = { _, _, _ -> },
                onTurnFinished = { refreshSessions() },
                jobNotices = { takeNoticesFor(record.id) },
                skillCatalog = { s -> skillCatalogNotice(s) },
                // 子代理同样带时间上下文（上游 time-context 对每个 agent 都生效）
                timeContext = { timeContextConfig() },
                contextWindow = { settings.effectiveContextWindow.takeIf { it > 0 } },
                log = { message -> Log.i("DshCompact", message) },
                plan = PlanModeState(),
            ),
        )
        val runtime = ChildRuntime(record, session, state, stop, ui, childEngine)
        childRuntimes[record.id] = runtime
        try {
            runtime.turnJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            val outcome = try {
                childEngine.startTurn(
                    ui,
                    TurnInput(
                        text = prompt,
                        textBlocks = listOf(prompt, AgentRegistry.returnGuidance(record.parentId.orEmpty())),
                    ),
                )
            } finally {
                runtime.turnJob = null
            }
            val closing = state.messages.asReversed()
                .firstOrNull { it.role == MessageRole.ASSISTANT }
                ?.content?.filter { it.type == BlockType.TEXT }
                ?.joinToString("") { it.text }
                .orEmpty()
            return AgentTerminal(
                stopReason = when (outcome) {
                    TurnOutcome.COMPLETED -> "completed"
                    TurnOutcome.MAX_TOKENS -> "max-tokens"
                    TurnOutcome.ERROR -> "error"
                },
                closingTexts = listOf(closing),
            )
        } finally {
            state.streaming = false
            refreshSessions()
        }
    }

    /** 子代理权限：权限档位在启动时固定，需要审批的工具直接拒绝（上游 SUBAGENT_DELEGATION_CONTEXT）。 */
    /**
     * workflow 工具宿主：前台直接跑（结果回给模型），后台登记成 job（值随完成通知交付）。
     * 子代理与普通委派共用 TurnEngine，但 workflow 子代理不登记 AgentRegistry、不发结算通知。
     */
    private inner class AppWorkflowHost : WorkflowHost {
        override suspend fun execute(
            script: String,
            metaJson: String,
            argsJson: String?,
            background: Boolean,
            owner: String?,
        ): WorkflowToolOutcome {
            val meta = try {
                WorkflowMeta.parse(metaJson)
            } catch (e: WorkflowException) {
                return WorkflowToolOutcome(e.message ?: "invalid workflow meta", isError = true)
            }
            if (argsJson != null) {
                val valid = runCatching { Json.parseToJsonElement(argsJson) is JsonObject }.getOrDefault(false)
                if (!valid) return WorkflowToolOutcome("workflow args must be an object", isError = true)
            }

            if (background) {
                val snapshot = jobRegistry.start(kind = "workflow", label = meta.name, owner = owner) { control ->
                    val observer = object : WorkflowObserver {
                        override fun phase(title: String) {
                            control.log("▸ $title\n")
                        }

                        override fun log(message: String) {
                            control.log("$message\n")
                        }

                        override fun agentStart(seq: Int, label: String, phase: String?, childId: String?) {
                            control.log("agent #$seq $label started\n")
                        }

                        override fun agentEnd(seq: Int, outcome: String) {
                            control.log("agent #$seq $outcome\n")
                        }
                    }
                    val run = engineFor(owner, observer).start(script, meta, argsJson)
                    control.onCancel { reason -> run.cancel(reason ?: "background workflow job killed") }
                    val result = run.result.await()
                    when (result.stopReason) {
                        WorkflowStopReason.COMPLETED -> {
                            control.result(WorkflowRender.resultText(meta.name, result.agentsStarted, result.valueJson))
                            JobOutcome(JobStatus.COMPLETED, WorkflowRender.agentsDetail(result.agentsStarted))
                        }
                        WorkflowStopReason.CANCELLED -> JobOutcome(JobStatus.KILLED, "cancelled")
                        else -> JobOutcome(JobStatus.FAILED, result.error ?: "workflow run failed")
                    }
                }
                return WorkflowToolOutcome(WorkflowRender.backgroundText(meta.name, snapshot.id))
            }

            val run = engineFor(owner, null).start(script, meta, argsJson)
            val result = try {
                run.result.await()
            } catch (e: CancellationException) {
                run.cancel("parent step aborted")
                throw e
            }
            val error = WorkflowRender.stopReasonError(result)
            return if (error != null) {
                WorkflowToolOutcome(error, isError = true)
            } else {
                WorkflowToolOutcome(WorkflowRender.resultText(meta.name, result.agentsStarted, result.valueJson))
            }
        }

        private fun engineFor(owner: String?, observer: WorkflowObserver?): WorkflowEngine = WorkflowEngine(
            jsFactory = { QuickJsWorkflowRuntime() },
            children = WorkflowChildRunner(owner),
            scope = jobScope,
            limits = WorkflowLimits(maxConcurrentAgents = 4),
            observer = observer ?: object : WorkflowObserver {},
        )
    }

    /** workflow 的 `agent()`：独立子会话跑一轮，只把收尾正文/结构化结果交回脚本。 */
    private inner class WorkflowChildRunner(private val parentId: String?) : WorkflowChildHost {
        override suspend fun run(request: WorkflowChildRequest): WorkflowChildOutcome {
            val parent = parentId ?: return WorkflowChildOutcome(false, error = "workflow has no owning session")
            val cwd = workspace
            val session = createChildSession(parent, cwd, fork = false, depth = 1)
            val schemaJson = request.schemaJson
            val prompt = if (schemaJson != null) {
                schemaPrompt(request.prompt, schemaJson)
            } else {
                request.prompt
            }
            val record = AgentRecord(
                id = session.header.id,
                parentId = parent,
                label = request.label,
                depth = 1,
                cwd = cwd.absolutePath,
                createdAt = System.currentTimeMillis(),
            )
            val terminal = runChildAgent(record, session, prompt, emptyList(), agentHost = NoDelegationAgentHost)
            val text = terminal.closingTexts.filter { it.isNotBlank() }.joinToString("\n")
            if (terminal.stopReason != "completed") {
                return WorkflowChildOutcome(false, text = text, error = terminal.diagnostic ?: terminal.stopReason)
            }
            if (request.schemaJson == null) return WorkflowChildOutcome(true, text = text)
            return WorkflowChildOutcome(true, text = text, structuredJson = extractJsonValue(text))
        }
    }

    /** workflow 子代理的委派深度已用尽：工具里再调 subagent 直接拒绝（与上游 depth 1 一致）。 */
    private object NoDelegationAgentHost : AgentHost {
        override fun currentAgentId(): String? = null

        override fun currentDepth(): Int = 1

        override fun maxDepth(): Int = 1

        override suspend fun start(description: String, prompt: String, cwd: String?, fork: Boolean): AgentStart =
            throw IllegalArgumentException("workflow child agents cannot delegate further")

        override fun list(scope: String): List<AgentEntry> = emptyList()

        override fun send(agentId: String, message: String): AgentSend =
            throw IllegalArgumentException("workflow child agents cannot send messages")

        override fun interrupt(agentId: String) = Unit
    }

    private fun childApproval(call: ToolCall): Boolean =
        call.name in READ_ONLY_TOOLS || settings.approvalMode == "auto"

    private class ChildRuntime(
        val record: AgentRecord,
        val session: SessionSession,
        val state: ChatSessionState,
        val stop: TurnStopState,
        val ui: TurnUi,
        val engine: TurnEngine,
    ) {
        @Volatile
        var wakeScheduled = false

        @Volatile
        var turnJob: kotlinx.coroutines.Job? = null
    }

    /** 审批：允许/拒绝当前挂起的工具调用（优先前台会话；后台会话的审批切回去再处理）。 */
    fun resolveApproval(allow: Boolean, always: Boolean = false) {
        val live = current?.takeIf { it.state.pendingApproval != null }
            ?: activeTurns.firstOrNull { it.state.pendingApproval != null }
            ?: current
            ?: return
        val state = live.state
        if (allow && always) state.pendingApproval?.let { approvedTools += it.toolName }
        Log.i("DshApproval", "resolve session=${state.sessionId ?: "-"} allow=$allow always=$always")
        state.pendingApproval = null
        live.approvalDeferred?.complete(allow)
        live.approvalDeferred = null
        BigFish.onApprovalResolved(state)
    }

    /** 模型提问：挂起该会话的工具循环，等 UI 收集答案后 complete。 */
    private suspend fun askUser(
        live: LiveChat,
        questions: List<xin.dshaa.core.tools.AskQuestion>,
    ): List<xin.dshaa.core.tools.AskAnswer> {
        val state = live.state
        val deferred = kotlinx.coroutines.CompletableDeferred<List<xin.dshaa.core.tools.AskAnswer>>()
        live.questionDeferred = deferred
        state.pendingQuestions = questions
        return try {
            deferred.await()
        } finally {
            state.pendingQuestions = null
            if (live.questionDeferred === deferred) live.questionDeferred = null
        }
    }

    /** UI 提交答案（空答案 = 用户跳过）。 */
    fun resolveQuestions(answers: List<xin.dshaa.core.tools.AskAnswer>) {
        val live = current?.takeIf { it.state.pendingQuestions != null }
            ?: activeTurns.firstOrNull { it.state.pendingQuestions != null }
            ?: return
        live.questionDeferred?.complete(answers)
    }

    private suspend fun awaitApproval(live: LiveChat, call: ToolCall): Boolean {
        if (call.name in READ_ONLY_TOOLS || settings.approvalMode == "auto" || call.name in approvedTools) return true
        val state = live.state
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        live.approvalDeferred = deferred
        state.pendingApproval = PendingApproval(
            id = call.id,
            toolName = call.name,
            arguments = call.arguments,
            summary = summarizeTool(call.name, call.arguments),
        )
        // 大肥鱼：按会话记状态（M78），并行时前台/在跑的那条优先，别互相覆盖。
        BigFish.onWaiting(live.state, call.name)
        val foreground = state === current?.state
        Log.i(
            "DshApproval",
            "ask session=${state.sessionId ?: "-"} tool=${call.name} foreground=$foreground",
        )
        // 后台会话的审批卡在前台看不到（聊天页顶部横幅 + 抽屉橙点提示）：顺手把常驻通知的
        // 文案改成「等待审批」，用户切到桌面也能看见是谁在等。
        if (!foreground) {
            TurnKeepAliveService.start(
                getApplication(),
                "「${state.title.ifBlank { "会话" }}」等待审批：${call.name}",
            )
        }
        return try {
            deferred.await()
        } finally {
            // 用户「停止本轮」或切会话取消时也要把审批卡收掉（否则会一直挂在聊天页上）
            state.pendingApproval = null
            if (live.approvalDeferred === deferred) live.approvalDeferred = null
        }
    }

    private fun summarizeTool(name: String, args: String): String {
        val obj = runCatching { Json.parseToJsonElement(args.ifBlank { "{}" }) as JsonObject }.getOrNull()
        val value = when (name) {
            "bash" -> obj?.get("command")?.toString()
            "write", "edit", "read" -> obj?.get("file_path")?.toString()
            else -> null
        } ?: return name
        return value.trim('"')
    }

    /**
     * 从供应商拉取模型列表（模型配置页「拉取模型」，2026-11-09 用户要求）。
     *
     * - Anthropic 官方 / 自定义 Anthropic 端点：`GET {base}/v1/models` + `x-api-key` + `anthropic-version`；
     * - DeepSeek / OpenAI 兼容端点：`GET {root}/v1/models` + `Authorization: Bearer`，其中
     *   `root` = base 去掉 `/anthropic`（DeepSeek 的模型目录在 OpenAI 兼容端点上）。
     * 两种响应的模型条目都按 `data[].id` 解析。
     */
    suspend fun fetchModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        val cfg = llmConfig()
        if (cfg.apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("未配置 API 密钥"))
        }
        val anthropicStyle = settings.providerId == SettingsStore.PROVIDER_ANTHROPIC
        val base = cfg.baseUrl.trim().trimEnd('/')
        val root = if (base.endsWith("/anthropic")) base.removeSuffix("/anthropic") else base
        val url = when {
            root.endsWith("/v1/models") -> root
            root.endsWith("/v1") -> "$root/models"
            else -> "$root/v1/models"
        }
        runCatching {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                if (anthropicStyle) {
                    setRequestProperty("x-api-key", cfg.apiKey)
                    setRequestProperty("anthropic-version", LlmConfig.ANTHROPIC_VERSION)
                } else {
                    setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
                }
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "whale-song")
                connectTimeout = 15_000
                readTimeout = 20_000
            }
            try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) error("HTTP $code：${text.take(200)}")
                val items = org.json.JSONObject(text).optJSONArray("data")
                    ?: error("响应里没有 data 数组")
                val names = buildList {
                    for (i in 0 until items.length()) {
                        val id = items.optJSONObject(i)?.optString("id").orEmpty()
                        if (id.isNotBlank()) add(id)
                    }
                }.distinct()
                if (names.isEmpty()) error("模型列表为空")
                names.sorted()
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * 测试连接：发一条最小请求（max_tokens=32，无工具、无 system），只验证端点/密钥/模型名。
     * 对应 Operit 模型配置页的「测试连接」。
     */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val cfg = llmConfig()
        if (cfg.apiKey.isBlank()) {
            return@withContext "连接失败：未配置 API 密钥"
        }
        val request = ApiRequest(
            model = settings.model,
            stream = true,
            messages = listOf(ApiMessage("user", listOf(ApiBlock.Text("ping")))),
            maxTokens = 32,
        )
        var reply = StringBuilder()
        var failure: String? = null
        val events = if (settings.protocol == "openai") {
            openAiClient.stream(request, cfg)
        } else {
            client.stream(request, cfg)
        }
        events.collect { event ->
            when (event) {
                is LlmEvent.TextDelta -> reply.append(event.text)
                is LlmEvent.Failed -> failure = event.message
                else -> Unit
            }
        }
        if (failure != null) {
            "连接失败：$failure"
        } else {
            "连接成功 · ${settings.model}" + if (reply.isNotBlank()) " · 回复：${reply.take(30)}" else ""
        }
    }

    private fun providerLabel(): String = when (settings.providerId) {
        SettingsStore.PROVIDER_ANTHROPIC -> "anthropic"
        SettingsStore.PROVIDER_OPENAI,
        SettingsStore.PROVIDER_OPENAI_COMPATIBLE -> "openai"
        else -> "deepseek"
    }

    private fun llmConfig(): LlmConfig = LlmConfig(
        apiKey = settings.apiKey,
        // 端点允许留空（设置页删空后不再弹回默认值）：请求时回落到当前供应商的默认端点。
        baseUrl = settings.baseUrl.ifBlank { SettingsStore.defaultBaseUrl(settings.providerId) },
        model = settings.model,
        maxTokens = settings.maxTokens,
        temperature = settings.temperature,
    )

    /**
     * 图片卸载预算（M47）：默认上游 600 张 / 20 MiB 内联。
     * 调试通道 `debug_image_budget_bytes` 可把字节预算临时改小 —— 锁屏验收时一张普通截图
     * 就能触发 `IMAGE_OFFLOAD_REQUIRED`，不用造 20 MiB 的会话。
     */
    private fun imageOffloadBudget(): ImageOffload.Budget {
        val bytes = prefs.getInt("debug_image_budget_bytes", 0)
        if (bytes <= 0) return ImageOffload.DEFAULT_BUDGET
        return ImageOffload.Budget(
            maxImages = ImageOffload.DEFAULT_MAX_IMAGES,
            maxBytes = bytes,
            byteQuantum = minOf(1024, bytes).coerceAtLeast(1),
            countQuantum = 1,
        )
    }

    /** 附件归一化副本的绝对路径（图片卸载占位文本里的只读路径）。 */
    private fun imageReadonlyPath(id: String): String? = attachments.imageFile(id)?.absolutePath

    /** 门 2 对拍：`--es dsh_dump_request <path>` 时把这一次的请求体原样落盘（仅 debug 通道会设置）。 */
    private fun dumpRequestIfRequested(request: ApiRequest) {
        val sp = getApplication<Application>().getSharedPreferences("dsh", 0)
        val path = sp.getString("debug_dump_request", null)?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(xin.dshaa.core.protocol.ProtocolJson.encodeRequest(request))
        }
        sp.edit().remove("debug_dump_request").apply()
    }

    /** 摘要请求落盘序号（每次摘要请求一个文件，M48 验收用）。 */
    private var summaryRequestDumpSeq = 0

    /**
     * M48 验收：`--es dsh_dump_summary_request <目录>` 时把每一次摘要请求体写成
     * `summary-<n>.json`（第一次是带图的超预算请求，第二次是卸载后的重试）。
     * 只改 debug pref，生产不设置；测完 `sed` 清掉。
     */
    private fun dumpSummaryRequestIfRequested(request: ApiRequest) {
        val sp = getApplication<Application>().getSharedPreferences("dsh", 0)
        val dir = sp.getString("debug_dump_summary_dir", null)?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            val file = File(dir, "summary-${++summaryRequestDumpSeq}.json")
            file.parentFile?.mkdirs()
            file.writeText(xin.dshaa.core.protocol.ProtocolJson.encodeRequest(request))
        }
    }

    // ---------------- 门 2 对拍：run.jsonl（仅调试通道打开；生产为 null） ----------------

    private fun logRequest(n: Int, request: ApiRequest) {
        val rec = runRecorder ?: return
        val body = xin.dshaa.core.protocol.ProtocolJson.encodeRequest(request)
        val bytes = body.toByteArray(Charsets.UTF_8)
        rec.record("llm.request") {
            put("n", n)
            put("sha256", ParityJson.sha256(bytes))
            put("bytes", bytes.size)
            put("body", body)
        }
    }

    private fun logLlmEvent(n: Int, event: LlmEvent) {
        val rec = runRecorder ?: return
        rec.record("llm.event") {
            put("n", n)
            when (event) {
                is LlmEvent.MessageStart -> {
                    put("e", "message_start")
                    put("id", event.id)
                    put("model", event.model)
                    put("inputTokens", event.inputTokens)
                }
                is LlmEvent.BlockStart -> {
                    put("e", "block_start")
                    put("index", event.index)
                    put("type", event.type)
                    put("toolId", event.toolId)
                    put("toolName", event.toolName)
                }
                is LlmEvent.TextDelta -> {
                    put("e", "text_delta")
                    put("index", event.index)
                    put("text", event.text)
                }
                is LlmEvent.ThinkingDelta -> {
                    put("e", "thinking_delta")
                    put("index", event.index)
                    put("text", event.text)
                }
                is LlmEvent.SignatureDelta -> {
                    put("e", "signature_delta")
                    put("index", event.index)
                    put("signature", event.signature)
                }
                is LlmEvent.ToolInputDelta -> {
                    put("e", "tool_input_delta")
                    put("index", event.index)
                    put("partialJson", event.partialJson)
                }
                is LlmEvent.BlockStop -> {
                    put("e", "block_stop")
                    put("index", event.index)
                }
                is LlmEvent.MessageDelta -> {
                    put("e", "message_delta")
                    put("stopReason", event.stopReason)
                    put("outputTokens", event.outputTokens)
                }
                LlmEvent.MessageStop -> put("e", "message_stop")
                is LlmEvent.Failed -> {
                    put("e", "failed")
                    put("message", event.message)
                    put("status", event.status)
                    put("retryable", event.retryable)
                }
            }
        }
    }

    private fun logToolCall(call: ToolCall, result: String, isError: Boolean) {
        val rec = runRecorder ?: return
        rec.record("tool.call") {
            put("n", ++toolNo)
            put("name", call.name)
            put("args", call.arguments)
            put("isError", isError)
            put("result", result.take(MAX_LOG_TEXT))
        }
    }

    private fun logTurnEnd(state: ChatSessionState) {
        val rec = runRecorder ?: return
        rec.record("turn.end") {
            put("title", state.title)
            put("error", state.error)
            put("messages", buildJsonArray {
                state.messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role.name)
                        put("blocks", buildJsonArray {
                            m.content.forEach { b ->
                                add(buildJsonObject {
                                    put("type", b.type.name)
                                    when (b.type) {
                                        BlockType.TEXT -> put("text", b.text)
                                        BlockType.REASONING -> {
                                            put("text", b.text)
                                            put("signature", b.signature)
                                        }
                                        BlockType.TOOL_CALL -> {
                                            put("toolCallId", b.toolCallId)
                                            put("name", b.name)
                                            put("arguments", b.arguments)
                                        }
                                        else -> Unit
                                    }
                                })
                            }
                        })
                    })
                }
            })
        }
    }

    override fun onCleared() {
        // Activity 真被销毁（划掉任务/退出）：turn 跟着 viewModelScope 取消，保活服务也要收掉
        TurnKeepAliveService.stopListener = null
        TurnKeepAliveService.stop(getApplication())
        // 悬浮窗的桥也要断开：否则悬浮窗会拿着已销毁的 ViewModel 发消息
        if (ChatMirror.host === this) ChatMirror.host = null
        ChatMirror.state = null
        runCatching { jobRegistry.shutdown() }
        runCatching { jobScope.cancel() }
        runCatching { agentRegistry.shutdown() }
        runCatching { agentScope.cancel() }
        runCatching { runRecorder?.close() }
        super.onCleared()
    }

    /**
     * 时间上下文配置（M42）：设置里关掉就返回 null（请求体不带时间注入）。
     * 时区每次现取 `ZoneId.systemDefault()`——用户改系统时区后下一次注入就是新时区。
     */
    private fun timeContextConfig(): TimeContext.Config? =
        if (settings.timeContext) TimeContext.Config(zone = ZoneId.systemDefault()) else null

    /**
     * 角色卡人设（M71）：上游 `dsh-persona` 的 `deployment:persona-prefix`（order 0）。
     * 空 = 用上游默认段（"You are a coding agent powered by the {{model}} model."）；
     * 非空 = 整段替换，并在前面补一句名字，让模型知道自己在这个 App 里叫什么。
     */
    private fun personaPrefixText(): String {
        val persona = settings.assistantPersona.trim()
        if (persona.isEmpty()) return ""
        val name = settings.assistantName.trim()
        return buildString {
            if (name.isNotEmpty()) {
                append("你的名字是「").append(name).append("」。")
                append("\n\n")
            }
            append(persona)
        }
    }

    /**
     * 插件用 `ctx.systemPrompt.section` 注册的段（M57）：交给 [PromptPack.systemFor] 和宿主段一起
     * 按 order/name 排序，同名段后注册的覆盖前面的（插件人设走同一条路，不额外拼在最后）。
     * `complete:true` 会整体接管 system，手机版不启用（权限过大）。
     */
    private fun pluginPromptSections(): List<xin.dshaa.core.prompt.PromptSection> =
        pluginsUi
            .filter { it.enabled }
            .flatMap { it.promptSections.filterNot { section -> section.complete } }
            .map { section ->
                xin.dshaa.core.prompt.PromptSection(
                    name = section.name,
                    order = section.order,
                    text = section.text,
                )
            }

    /**
     * 插件用 `ctx.systemPrompt.variable(name, fn)` 求值出的变量（M74）：段文本里的 `{{name}}`
     * 由 [PromptPack.systemFor] 替换。只取**启用中**插件的变量；同名的后注册覆盖（和段一致）。
     */
    private fun pluginPromptVariables(): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        pluginsUi.filter { it.enabled }.forEach { merged.putAll(it.promptVariables) }
        return merged
    }

    /**
     * 插件用 `ctx.systemPrompt.context` 注册的运行时上下文：DSH 里它是 **user-role 快照**
     * （不是 system），所以并进 [runtimeContextText] 的同一个 user 消息，按 order/name 排序。
     * M74：段文本里的 `{{name}}` 和 system 段一样做变量替换（插件常在两处写同一套占位符）。
     */
    private fun pluginContextText(): String {
        val variables = pluginPromptVariables()
        return pluginsUi
            .filter { it.enabled }
            .flatMap { it.contexts }
            .sortedWith(compareBy({ it.order }, { it.name }))
            .map { promptPack.interpolate(it.text.trim(), settings.model, providerLabel(), variables) }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
    }

    /**
     * 运行时上下文快照（逐字对齐上游模板；Android 固定 danger-full-access + 审批策略按设置映射）。
     * 子代理额外追加：固定权限范围自述 + 深度用尽提示（上游 `SUBAGENT_DELEGATION_CONTEXT` /
     * `tool:subagent:depth` 两个 context contribution）。
     */
    private fun runtimeContextText(depth: Int = 0, cwd: File = toolContext.cwd): String {
        val approval = if (settings.approvalMode == "auto") {
            "Approval prompts are disabled in this session: actions that require approval are rejected automatically — " +
                "do not request sandbox escalation (do not set `sandbox_permissions`)."
        } else {
            "Approval policy: ask. Operations that require approval may ask through the configured answerers; " +
                "without an available answerer, the request fails closed."
        }
        return buildString {
            append("Current runtime context. This snapshot supersedes earlier runtime-context snapshots.")
            append("\n\n")
            append("Current working directory: \"${cwd.absolutePath}\".")
            append("\n\n")
            append(
                "Current DSH file policy: danger-full-access. " +
                    "The DSH file sandbox does not restrict file modifications by available operations.",
            )
            append("\n\n")
            append(approval)
            if (depth > 0) {
                append("\n\n")
                append(AgentRegistry.DELEGATION_CONTEXT)
            }
            if (depth > 0 && depth >= MAX_AGENT_DEPTH) {
                append("\n\n")
                append(AgentRegistry.depthContext("subagent", depth, MAX_AGENT_DEPTH))
            }
            // 插件上下文（ctx.systemPrompt.context）就在这条 user 快照末尾（M71）
            val pluginContext = pluginContextText()
            if (pluginContext.isNotEmpty()) {
                append("\n\n")
                append(pluginContext)
            }
        }
    }

}

/** workflow 的结构化输出：子代理没有原生 output schema，用提示词要求返回 JSON（上游是 Activation output）。 */
private fun schemaPrompt(prompt: String, schemaJson: String): String = buildString {
    append(prompt)
    append("\n\nReturn your final answer as a single JSON value that matches this JSON Schema. ")
    append("Do not wrap it in Markdown code fences and do not add prose before or after it.\n")
    append(schemaJson)
}

/** 从子代理收尾正文里取一个 JSON 值：整段优先，其次第一个 `{` 到最后一个 `}`。 */
private fun extractJsonValue(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()?.let { return it.toString() }
    val start = trimmed.indexOf('{')
    val end = trimmed.lastIndexOf('}')
    if (start in 0 until end) {
        runCatching { Json.parseToJsonElement(trimmed.substring(start, end + 1)) }.getOrNull()?.let { return it.toString() }
    }
    return null
}
