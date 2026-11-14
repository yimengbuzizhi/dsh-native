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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
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
import xin.dshaa.core.agent.AGENT_RUN_METHOD
import xin.dshaa.core.agent.AgentCandidate
import xin.dshaa.core.agent.AgentResolution
import xin.dshaa.core.agent.AgentRouting
import xin.dshaa.core.agent.BUILTIN_AGENT_ID
import xin.dshaa.core.agent.ResolvedAgent
import xin.dshaa.core.attachment.AttachmentStore
import xin.dshaa.core.attachment.ImageNormalizer
import xin.dshaa.core.engine.Compaction
import xin.dshaa.core.engine.EngineDeps
import xin.dshaa.core.engine.EngineHooks
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
import xin.dshaa.core.js.JsOutcome
import xin.dshaa.core.js.JsStatus
import xin.dshaa.core.llm.ConfiguredLlmProvider
import xin.dshaa.core.llm.BUILTIN_MODEL_DEEPSEEK
import xin.dshaa.core.llm.BUILTIN_MODEL_OPENAI
import xin.dshaa.core.llm.MODEL_CONTRACT
import xin.dshaa.core.llm.MODEL_PROVIDER_DEEPSEEK
import xin.dshaa.core.llm.MODEL_PROVIDER_OPENAI
import xin.dshaa.core.llm.ModelCandidate
import xin.dshaa.core.llm.ModelResolution
import xin.dshaa.core.llm.ModelRouting
import xin.dshaa.core.llm.ResolvedModel
import xin.dshaa.core.llm.LlmClient
import xin.dshaa.core.llm.LlmConfig
import xin.dshaa.core.llm.LlmEvent
import xin.dshaa.core.llm.LlmProvider
import xin.dshaa.core.llm.LlmProviderConfig
import xin.dshaa.core.llm.LlmProviders
import xin.dshaa.core.llm.OpenAiClient
import xin.dshaa.core.llm.WebSearchClient
import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.ChatMessage
import xin.dshaa.core.model.ContentBlock
import xin.dshaa.core.model.GoalActivation
import xin.dshaa.core.model.GoalAuthority
import xin.dshaa.core.model.GoalException
import xin.dshaa.core.model.GoalPhase
import xin.dshaa.core.model.GoalView
import xin.dshaa.core.model.ImageOffloadTarget
import xin.dshaa.core.model.ImageRef
import xin.dshaa.core.model.MessageRole
import xin.dshaa.core.model.markOffloadedImages
import xin.dshaa.core.model.toImageRef
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
import xin.dshaa.nativeapp.data.PluginShellHost
import xin.dshaa.core.parity.ParityJson
import xin.dshaa.core.plugins.PluginRecord
import xin.dshaa.core.plugins.PluginRoute
import xin.dshaa.core.parity.RunRecorder
import xin.dshaa.core.parity.SseCaptureRecorder
import xin.dshaa.core.prompt.PromptPack
import xin.dshaa.core.prompt.PromptProviders
import xin.dshaa.core.prompt.PromptRequest
import xin.dshaa.core.prompt.BUILTIN_PROMPT_ID
import xin.dshaa.core.prompt.PROMPT_ASSEMBLE_METHOD
import xin.dshaa.core.prompt.PROMPT_CONTRACT
import xin.dshaa.core.prompt.PromptCandidate
import xin.dshaa.core.prompt.PromptResolution
import xin.dshaa.core.prompt.PromptRouting
import xin.dshaa.core.prompt.ResolvedPrompt
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
import xin.dshaa.core.session.FileSessionRepository
import xin.dshaa.core.session.BUILTIN_STORAGE_ID
import xin.dshaa.core.session.STORAGE_CONTRACT
import xin.dshaa.core.session.StorageCandidate
import xin.dshaa.core.session.StorageResolution
import xin.dshaa.core.session.StorageRouting
import xin.dshaa.core.session.ResolvedStorage
import xin.dshaa.core.session.ZstdFrames
import xin.dshaa.nativeapp.device.floating.FloatingDemoPlugin
import xin.dshaa.nativeapp.device.floating.FloatingSlots
import xin.dshaa.core.session.SessionStore
import xin.dshaa.core.session.SessionStores
import xin.dshaa.core.session.TrashedSession
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
import xin.dshaa.core.tools.BuiltinToolCatalog
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
import xin.dshaa.nativeapp.data.PluginSessionStore
import xin.dshaa.nativeapp.data.PluginStore
import xin.dshaa.nativeapp.data.PluginToolRuntime
import xin.dshaa.nativeapp.data.RoutingSessionStore
import xin.dshaa.nativeapp.data.StorageUnavailableException
import xin.dshaa.ui.shellapi.data.SettingsStore
import xin.dshaa.nativeapp.data.WorkspaceStore
import xin.dshaa.nativeapp.fish.BigFish
import xin.dshaa.ui.shellapi.data.WorkspaceUi
import xin.dshaa.nativeapp.device.DeviceShell
import xin.dshaa.nativeapp.device.a11y.A11y
import com.ai.assistance.showerclient.ShellIdentity
import xin.dshaa.nativeapp.device.tools.AndroidDeviceTools
import xin.dshaa.nativeapp.memory.MemoryTools
import xin.dshaa.nativeapp.device.tools.TerminalTools
import xin.dshaa.ui.shellapi.NativeShellHost
import xin.dshaa.ui.shellapi.AgentTargetUi
import xin.dshaa.ui.shellapi.RoleTargetUi
import xin.dshaa.ui.shellapi.PluginPageEntry
import xin.dshaa.ui.shellapi.PluginPageTarget
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.ZoneId
import java.util.UUID
import xin.dshaa.ui.shellapi.chat.ATTACHMENT_REFERENCE_HINT
import xin.dshaa.ui.shellapi.chat.ChatSessionState
import xin.dshaa.ui.shellapi.chat.CommandSheet
import xin.dshaa.ui.shellapi.chat.JobUi
import xin.dshaa.ui.shellapi.chat.PendingApproval
import xin.dshaa.ui.shellapi.chat.PendingFile
import xin.dshaa.ui.shellapi.chat.PendingImage
import xin.dshaa.ui.shellapi.chat.READ_ONLY_IMPORT
import xin.dshaa.ui.shellapi.chat.READ_ONLY_STORAGE
import xin.dshaa.ui.shellapi.chat.READ_ONLY_SUBAGENT
import xin.dshaa.ui.shellapi.chat.SessionBadge
import xin.dshaa.ui.shellapi.chat.SubagentUi
import xin.dshaa.ui.shellapi.chat.WaitingSession
import xin.dshaa.ui.shellapi.chat.buildJobRows
import xin.dshaa.ui.shellapi.chat.mergeSubagents

private const val MAX_LOG_TEXT = 200_000

/** 委托深度上限（上游 `dsh-subagent` 的默认 maxDepth = 1：根能委派一层，子代理不能再委派）。 */
private const val MAX_AGENT_DEPTH = 1

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
    "skill", "plugin_dev_list", "plugin_dev_test",
)

/** M85-D：第三方 JS 回合的最长等待（插件自己应尽快返回；超时按失败收尾，UI 可在 1.5s 后强制停）。 */
private const val AGENT_TURN_TIMEOUT_MS = 120_000L

/** M85-E：第三方提示词实现的最长等待（拼装要很快；超时就回退内置拼装并亮提示）。 */
private const val PROMPT_ASSEMBLE_TIMEOUT_MS = 8_000L

/**
 * 应用级状态：设置 + 会话仓库 + 当前聊天 + 工具循环。
 * 运行时暂时在 ViewModel；M2 后挪到前台服务持有的 ChatRuntimeHolder（01 §五.4）。
 */
class AppViewModel(app: Application) : AndroidViewModel(app), NativeShellHost {

    // ---- 插件（M26：本地导入 + 试运行） ----
    //
    // 状态必须声明在 init 之前：init 里的调试导入（`--es dsh_plugin_zip`）会立刻读写它们。
    // 存储：`files/plugins/<id>/` + `files/plugins.json`。

    private val pluginStore = PluginStore(app)

    var runtimeReady by mutableStateOf(false)
        private set
    var runtimeInitError by mutableStateOf<String?>(null)
        private set
    var runtimeCoreReady by mutableStateOf(false)
        private set
    private val initializationMutex = kotlinx.coroutines.sync.Mutex()
    private var runtimeBindingsInstalled = false

    /** 首帧由独立 BootHost 提供；耗时 IO 在后台准备，主线程统一登记后才开放发送。 */
    suspend fun initializeRuntime(onPhase: (String, String) -> Unit = { _, _ -> }) {
        initializationMutex.lock()
        try {
            if (runtimeReady) {
                consumeStartupDebug()
                return
            }
            runtimeInitError = null
            onPhase("packages", "准备内置插件…")
            val records = withContext(Dispatchers.IO) {
                xin.dshaa.nativeapp.data.BundledPlugins.ensureWhaleShell(getApplication(), pluginStore)
                BundledSkills.ensureInstalled(getApplication())
                pluginStore.list()
            }
            pluginsUi = records
            onPhase("registry", "登记工具与角色…")
            initializeCoreRegistry()
            syncPluginTools()
            runtimeCoreReady = true
            shellActive = resolveShellTarget(prefs.getString("dsh_shell", null))
            onPhase("skills", "准备技能目录…")
            val snapshot = withContext(Dispatchers.IO) { skills.snapshot() }
            skillsUi = snapshot.skills
            skillsComplete = snapshot.complete
            skillsWarnings = withContext(Dispatchers.IO) { skills.lastWarnings() }
            onPhase("roles", "等待当前角色…")
            // Onboarding and the shell safety manager must remain reachable even with a broken role.
            // Sending always checks exact registrations independently in inputReadinessError.
            val requiresRoles = onboardingDone && (nativeUiEnabled() || shellActive != null)
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (requiresRoles && pendingRoleRegistrations().isNotEmpty() && android.os.SystemClock.elapsedRealtime() < deadline) {
                delay(50)
            }
            check(!requiresRoles || pendingRoleRegistrations().isEmpty()) {
                "当前角色运行时未就绪：" + pendingRoleRegistrations().joinToString("、")
            }
            onPhase("ready", "准备界面…")
            runtimeReady = true
            installRuntimeBindings()
            consumeStartupDebug()
            refreshSessions()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            runtimeReady = false
            runtimeInitError = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            initializationMutex.unlock()
        }
    }

    private val loadingSnapshotStore by lazy {
        xin.dshaa.nativeapp.data.LoadingSnapshotStore(File(getApplication<Application>().filesDir, "loading-snapshot.json"))
    }
    override var loadingSafetyNotice by mutableStateOf<String?>(null)
        private set
    override val loadingTargetsUi: List<RoleTargetUi>
        get() {
            val selected = prefs.getString("dsh_loading", null)?.ifBlank { null }
                ?: xin.dshaa.nativeapp.data.BuiltinLoadingPlugin.ID
            val snapshot = loadingSnapshotStore.read()
            return buildList {
                add(RoleTargetUi(role = "loading", implementationId = xin.dshaa.nativeapp.data.BuiltinLoadingPlugin.ID,
                    label = "鲸歌加载页", builtin = true, active = snapshot?.ownerPlugin == xin.dshaa.nativeapp.data.BuiltinLoadingPlugin.ID ||
                        (snapshot == null && selected == xin.dshaa.nativeapp.data.BuiltinLoadingPlugin.ID),
                    selectionValue = xin.dshaa.nativeapp.data.BuiltinLoadingPlugin.ID))
                pluginsUi.forEach { record -> record.loadings.forEach { loading ->
                    val granted = "ui" in record.permissions && "ui" in record.grantedPermissions
                    val value = "${record.id}#${loading.id}"
                    add(RoleTargetUi(role = "loading", implementationId = loading.id, label = loading.label,
                        pluginId = record.id, provider = record.name, enabled = record.enabled, permitted = granted,
                        active = snapshot?.selection == value || (selected == record.id && snapshot?.ownerPlugin == record.id),
                        selectionValue = value, unavailableReason = when {
                            !record.enabled -> "提供方插件已停用"
                            !granted -> "缺少 ui 授权"
                            record.error != null -> "插件登记失败"
                            else -> null
                        }))
                } }
            }
        }

    override fun selectLoading(value: String): Boolean {
        val candidate = loadingTargetsUi.firstOrNull { it.selectionValue == value } ?: return false
        if (candidate.unavailableReason != null) { pluginNotice = candidate.unavailableReason; return false }
        return runCatching {
            val refreshed = loadingSnapshotStore.refresh(pluginsUi, value)
            loadingSafetyNotice = refreshed.unavailableReason
            check(refreshed.unavailableReason == null) { refreshed.unavailableReason.orEmpty() }
            prefs.edit().putString("dsh_loading", value).apply()
            pluginNotice = "加载页已选择，下次启动生效：${candidate.label}"
            true
        }.getOrElse { pluginNotice = "加载页选择失败：${it.message}"; false }
    }

    override fun restoreBuiltinLoading() { selectLoading(xin.dshaa.nativeapp.data.BuiltinLoadingPlugin.ID) }

    /** M83 阶段 4：远程 DSH（设置 → 远程 DSH 的连接页/会话列表/远程聊天状态）。 */
    override val remote = xin.dshaa.nativeapp.remote.RemoteDshController(app)
    override val conversation: xin.dshaa.ui.shellapi.ShellConversation by lazy {
        xin.dshaa.nativeapp.remote.ConversationAdapter(this, remote, viewModelScope)
    }

    override var pluginsUi by mutableStateOf(pluginStore.list())
        private set

    // ---- M83 阶段 2：内置插件（工具/页面可单独开关） ----
    private val builtinStore = xin.dshaa.nativeapp.data.BuiltinPluginStore(app)

    override var builtinPluginsUi by mutableStateOf(builtinStore.ui())
        private set

    // ---- M85-D：回合插件（agent/v1） ----
    //
    // 每次发消息前重新解析 `dsh_agent`：内置 builtin.agent.dsh（TurnEngine）、导入插件的
    // ctx.agent.register 声明、同 id 覆盖、停用 → 安全提示，都只在这一处判定。
    /** 当前生效的回合；null = 没有可用回合（聊天页显示安全提示，发送被拦下）。 */
    var activeAgent by mutableStateOf<ResolvedAgent?>(null)
        private set

    /** 无可用回合时的提示文案（停用/缺授权的具体原因）。 */
    override var agentSafetyNotice by mutableStateOf<String?>(null)
        private set

    /** M85-E：当前生效的提示词实现（null = 不可用，发送被拦）。声明必须在 init 之前。 */
    private var activePromptState by mutableStateOf<ResolvedPrompt?>(null)

    /** M85-E：提示词不可用/插件运行失败时的提示（聊天页横幅）。 */
    override var promptSafetyNotice by mutableStateOf<String?>(null)
        private set

    /** JS 回合插件 `ctx.abort()` 读的停止位（每次起轮清零，用户点停止置 true）。 */
    @Volatile
    private var pluginAgentAbort = false

    /** 内置插件开关：关掉后工具不进 tools[]、页面入口消失（重启后保持）。 */
    override fun setBuiltinPluginEnabled(id: String, enabled: Boolean) {
        builtinStore.setEnabled(id, enabled)
        // A2：工具组关掉要从 registry 注销，打开要重新注册（不只是请求侧过滤）
        syncBuiltinTools()
        // M84-D：悬浮窗插口插件关掉要从 FloatingSlots 注销（四个插口立即恢复默认）
        syncFloatingSlots()
        builtinPluginsUi = builtinStore.ui()
        // M85-D：内置回合插件开关 → 立刻重解析（关掉 = 安全提示；重新打开 = 恢复）
        refreshAgentResolution()
        // 工具清单是每次请求现算的（offeredToolNames），这里只需要让界面立刻反映数量。
        pluginNotice = "${builtinStore.ui().firstOrNull { it.plugin.id == id }?.plugin?.name ?: id}：" +
            if (enabled) "已打开" else "已关闭"
        Log.i("DshPlugin", "builtin plugin $id enabled=$enabled tools=${registry.names.size}")
    }

    /** 这个一级页面现在是否允许进入（内置页面被关掉时返回 false）。 */
    override fun builtinScreenEnabled(screenName: String): Boolean {
        // 读一次 Compose state：在设置页/抽屉里开关插件时，依赖它的界面要跟着重组；
        // 只读 SharedPreferences 不会触发 Compose 快照，之前会出现「开关关了但入口还在」。
        builtinPluginsUi.size
        return builtinStore.screenEnabled(screenName)
    }

    /**
     * M85-C：内置界面插件（`shell/v1` native 后端）是否启用。
     *
     * 同样读一次 `builtinPluginsUi`：在插件页关掉它时宿主立刻切到安全模式；重新打开立刻回原界面。
     */
    fun nativeUiEnabled(): Boolean {
        builtinPluginsUi.size
        return builtinStore.isEnabled(xin.dshaa.nativeapp.data.BuiltinPlugins.NATIVE_UI_SHELL)
    }

    override var pluginsBusy by mutableStateOf(false)
        private set

    /** 插件页顶部的一次性提示（导入/删除结果）。 */
    override var pluginNotice by mutableStateOf<String?>(null)
        private set

    /** 插件工具接入（M27）：已启用且 schema 可用的工具会注册进 `ToolRegistry`。 */
    private val pluginToolRuntime = PluginToolRuntime(
        notifier = { title, body ->
            xin.dshaa.nativeapp.data.PluginNotifier.notify(app, title, body)
        },
        // M67：Operit 包的 Tools.* 需要设备/无障碍/截图等 app 侧能力。
        hostBridge = xin.dshaa.nativeapp.data.AndroidPluginHostBridge(app),
    )

    /**
     * M84-H：注册了会话投影的插件各起一个常驻 QuickJS 运行时。
     *
     * 与 [pluginToolRuntime] 不是一回事：工具仍是「每次调用新建 QuickJS」；这里只为
     * `ctx.sessionProjections.register(...)` 保留 apply 后的 runtime，宿主事件逐条 dispatch 进去。
     */
    private val pluginResidentRuntime = xin.dshaa.nativeapp.data.PluginResidentRuntime(
        scope = viewModelScope,
        notifier = { title, body -> xin.dshaa.nativeapp.data.PluginNotifier.notify(app, title, body) },
        hostBridge = xin.dshaa.nativeapp.data.AndroidPluginHostBridge(app),
        // M84-N：投影 checkpoint 落 files/projection-checkpoints.json；重启只重投 seq > lastSeq。
        checkpoints = xin.dshaa.core.plugins.ProjectionCheckpoints(
            java.io.File(app.filesDir, "projection-checkpoints.json"),
        ),
        // M85-D：JS 回合插件的 ctx 原语（agent/v1）。
        onAgentStatus = { _, text -> setPluginAgentStatus(text) },
        isAgentAborted = { pluginAgentAbort },
        agentToolNames = { offeredToolNames().toList() },
        // M85-E：存储/提示词插件要等常驻 runtime 起来才能列会话/拼提示词；起来后刷新一次。
        onResidentStarted = { pluginId ->
            Log.i("DshStorage", "resident started plugin=$pluginId -> refresh sessions")
            refreshSessions()
        },
        // M85-E：只读导出器用的宿主导出原语（只读 builtin.storage.jsonl，不碰活动存储）。
        builtinSessionExport = { method, payload -> exportBuiltinSessions(method, payload) },
    )

    /** 当前由插件注册进工具表的名字（刷新时先注销这一批，再按最新清单注册）。 */
    private val pluginToolNames = mutableSetOf<String>()

    /**
     * M85-A：壳宿主（只有用户选了插件壳时才会 mount）。state 由 [shellStateJson] 在主线程现算；
     * 连续失败回退原生壳（[fallbackFromShell]）。
     */
    val shellHost: PluginShellHost by lazy {
        PluginShellHost(
            scope = viewModelScope,
            dispatch = { pluginId, method, payload, timeout ->
                pluginResidentRuntime.dispatch(pluginId, method, payload, timeout)
            },
            residentReady = { pluginId -> pluginResidentRuntime.isResident(pluginId) },
            stateProvider = { shellStateJson() },
            onFallback = { reason -> fallbackFromShell(reason) },
        )
    }

    /** M84-E2：当前由插件注册进 [LlmProviders] 的供应商 id（刷新时先收回这一批）。 */
    private val pluginProviderIds = LinkedHashSet<String>()

    /**
     * M85-F：能力宿主（`capability/v1`）。内置平台能力 + 插件 `ctx.capability.register` 的提供方，
     * 解析规则（插件优先/停用回落/如实不可用）在 `core:capability-api` 的单测里锁死。
     */
    val capabilityHost: xin.dshaa.nativeapp.data.CapabilityHost by lazy {
        xin.dshaa.nativeapp.data.CapabilityHost(
            context = app,
            dispatch = { pluginId, method, payload, timeout ->
                pluginResidentRuntime.dispatch(pluginId, method, payload, timeout)
            },
            records = { pluginsUi },
        )
    }

    /** 插件页底部最近一次能力调用的结果（null = 还没调用过）。 */
    override var capabilityNotice by mutableStateOf<String?>(null)
        private set
    /** 当前请求可用的插件工具 schema（每次请求现取，随 `ToolRegistry` 同步刷新）。 */
    @Volatile
    private var pluginApiTools: List<ApiTool> = emptyList()

    /** 插件页展示：插件 id → 已接入对话的工具名（未启用/参数认不出 = 不在表里）。 */
    override var pluginInjected by mutableStateOf<Map<String, Set<String>>>(emptyMap())
        private set

    /** 调试/截图用：启动即打开某个插件详情（`--es dsh_plugin_detail <id>`，只 debug 包）。 */
    override var debugPluginDetailId by mutableStateOf<String?>(null)
        private set

    // ---- M67：随包内置的 Operit ToolPkg 目录（包管理 → 包） ----

    private val operitBundled = xin.dshaa.nativeapp.data.OperitBundledPackages(app)

    /** 「包」tab 的列表；第一次进 tab 时从 assets 扫出来。 */
    override var operitPackagesUi by mutableStateOf<List<xin.dshaa.ui.shellapi.data.OperitBundledPackage>>(emptyList())
        private set

    override var operitPackagesBusy by mutableStateOf(false)
        private set

    override val settings = SettingsStore(app.getSharedPreferences("dsh", 0))
    private val prefs = app.getSharedPreferences("dsh", 0)

    /**
     * M85-A：当前由插件提供的整壳（null = 原生壳）。
     *
     * 选择规则（冷启动读 `dsh_shell` 调试项）：`native`/空 = 原生；`插件id` = 该插件的第一个壳；
     * `插件id#壳id` = 指定壳。插件必须启用、有 `ui` 授权、常驻运行时能起来。
     */
    var shellActive by mutableStateOf<PluginShellHost.Target?>(null)
        private set

    /**
     * M84-C2 / M85-E：会话存储。
     *
     * 默认 `builtin.storage.jsonl` = 原来的 `SessionRepository` 目录实现（行为/磁盘布局不变）；
     * `dsh_storage` 可切到第三方只读导出器（同 id 覆盖内置 = 默认角色被接管）；
     * debug 包仍可用 `--es dsh_session_store memory` 冷启动切到内存实现（M84-C2 验收通道）。
     * [RoutingSessionStore] 负责在插件开关变化后换绑；写路径在只读/不可用时抛
     * [StorageUnavailableException]，聊天页同时显示安全提示（不悄悄回退）。
     */
    private val sessionsRootDir = File(app.filesDir, "sessions")

    private val builtinSessionStore: SessionStore = FileSessionRepository(sessionsRootDir)

    private val storageRouter = RoutingSessionStore(sessionsRootDir, builtinSessionStore)

    private val repo: SessionStore = storageRouter

    /** 当前由 `dsh_storage` 解析出的存储（UI/dump 用；null = 不可用）。 */
    var activeStorage by mutableStateOf<ResolvedStorage?>(null)
        private set

    /** 存储不可用/只读时的安全提示（聊天页横幅 + 插件页说明）。 */
    override var storageSafetyNotice by mutableStateOf<String?>(null)
        private set

    /** 原生备份 v1（M35，docs/59）：导出/恢复会话、插件、附件、设置（不含密钥）。 */
    private val backupManager = BackupManager(app, xin.dshaa.nativeapp.BuildConfig.VERSION_NAME)

    /**
     * 首次启动的使用指导（2026-11-07）：引导页点「开始使用/跳过」置 true。
     *
     * 放 prefs 而不是「有没有会话」：旧版导入的会话可能很多，但用户从没看过引导；
     * 反过来清数据重装也应该重新看一次。
     */
    override var onboardingDone by mutableStateOf(prefs.getBoolean("onboarding_done", false))
        private set

    override fun completeOnboarding() {
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
    override var workspace: File = File(workspaceStore.currentPath()).apply { mkdirs() }
        private set

    /** 当前工作区下的会话（抽屉/会话页只显示这一份；全部会话另存 [allRootSessions]）。 */
    override var workspaces by mutableStateOf<List<WorkspaceUi>>(emptyList())
        private set

    /** 图片附件（内容寻址）；会话只存 attachmentId，请求时再读回字节。 */
    private val attachments = AttachmentStore(File(app.filesDir, "attachments/v1"))

    /** 会话内提醒（宿主级，`files/schedules.json`；不进 v4 会话）。 */
    private val schedules = ScheduleStore(File(app.filesDir, "schedules.json"))

    /**
     * M85-B3：提醒列表的变化计数（Compose 状态）。
     *
     * 提醒不在 ChatSessionState 里，`currentSchedules()` 也不读任何快照状态；壳的
     * `chat.hasSchedules` / `chat.schedules` 绑定若不用一个可观察的值，新建/删除提醒后
     * 已组合的树会被 Compose 按「参数相等」跳过，界面上看不到变化。
     */
    var schedulesRevision by mutableStateOf(0)
        private set

    private fun bumpSchedulesRevision() {
        schedulesRevision += 1
    }

    /**
     * 技能系统（M19，对齐上游 `dsh-skill-filesystem`）：每个请求现扫这些根
     * `<workspace>/.dsh/skills` → `<workspace>/.agents/skills` → `files/skills` → `files/agents/skills`。
     * 目录消息只在**变化**时落一条 `source.kind:"skill-catalog"` 的 user 消息（空目录不占上下文）。
     * 切换工作区时整体重建（项目级技能根跟着 cwd 走）。
     */
    private var skills = FileSystemSkills(
        FileSystemSkills.androidRoots(workspace, app.filesDir),
        // 用户关掉的技能不发现、不加载（M81）
        disabled = { settings.disabledSkills },
    )

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

    private val pluginDevTools = xin.dshaa.nativeapp.data.PluginDevTools(pluginStore, onChanged = {
        withContext(Dispatchers.IO) { pluginStore.reconcileServices() }
        withContext(Dispatchers.Main) {
            pluginsUi = pluginStore.list()
            syncPluginTools()
        }
    }, callTool = { pluginId, toolName, args, ctx ->
        val record = pluginsUi.firstOrNull { it.id == pluginId && it.enabled }
        if (record == null || toolName !in record.tools || toolName !in pluginInjected[pluginId].orEmpty()) {
            xin.dshaa.core.tools.ToolResult.err("该插件工具未启用或未接入：$pluginId / $toolName")
        } else {
            registry.execute(toolName, args, ctx)
        }
    }, testCapability = { id, args ->
        val result = capabilityHost.invoke(id, args.toString())
        if (result.ok) xin.dshaa.core.tools.ToolResult.ok(result.valueJson ?: "调用成功")
        else xin.dshaa.core.tools.ToolResult.err(result.error ?: "能力调用失败")
    })

    private val registry: ToolRegistry =
        ToolRegistry(androidDeviceTools.tools + terminalTools.tools + memoryTools.tools + pluginDevTools.tools)

    /**
     * M84-F2：不属于 [xin.dshaa.core.tools.BuiltinToolCatalog] 的 app 工具（设备/终端/记忆），
     * 也按内置插件组开关——关掉组 = 从 registry 注销，空壳 tools[] 才真的是 0。
     */
    private val appToolsByName =
        (androidDeviceTools.tools + terminalTools.tools + memoryTools.tools + pluginDevTools.tools).associateBy { it.name }

    /**
     * M84-D：内置悬浮窗插口插件按开关注册/注销到 [FloatingSlots]。
     * 现在只有一个示例插件；以后 JS 插件走同一个注册表，不需要改悬浮窗 UI。
     */
    private fun syncFloatingSlots() {
        val wanted = builtinStore.isEnabled(FloatingDemoPlugin.PLUGIN_ID)
        val present = FloatingSlots.get(FloatingDemoPlugin.ID) != null
        when {
            wanted && !present -> {
                FloatingSlots.register(FloatingDemoPlugin())
                Log.i("DshFloatingSlot", "registered ${FloatingDemoPlugin.ID}")
            }
            !wanted && present -> {
                FloatingSlots.unregister(FloatingDemoPlugin.ID)
                Log.i("DshFloatingSlot", "unregistered ${FloatingDemoPlugin.ID}")
            }
        }
        Log.i("DshFloatingSlot", "sync demo=$wanted plugins=${FloatingSlots.ids()}")
    }

    /**
     * M84-A2：内置工具由 [BuiltinToolCatalog] 按插件组提供，**开着的组才注册进 registry**。
     * 关掉 = 注销该组全部工具（模型看不到、调不到，registry 里也没有）；打开 = 重新注册。
     */
    private fun syncBuiltinTools() {
        for (group in BuiltinToolCatalog.groups) {
            val wanted = builtinStore.isEnabled(group.id)
            val present = group.tools.any { it.name in registry.names }
            if (wanted && !present) {
                group.tools.forEach { tool ->
                    if (!registry.register(tool)) Log.w("DshPlugin", "内置工具名冲突，未注册：${tool.name}（${group.id}）")
                }
            } else if (!wanted && present) {
                group.tools.forEach { registry.unregister(it.name) }
            }
        }
        // M84-F2：设备/终端/记忆这些 app 工具不在 catalog 里，但已归到内置插件组的工具名上；
        // 按同样的开关注册/注销，空壳（23 组全关）才不会有 26 个漏网工具。
        for (group in xin.dshaa.nativeapp.data.BuiltinPlugins.all) {
            val appTools = group.tools.mapNotNull { appToolsByName[it] }
            if (appTools.isEmpty()) continue
            val wanted = builtinStore.isEnabled(group.id)
            if (wanted) {
                appTools.forEach { tool ->
                    if (tool.name !in registry.names) {
                        if (!registry.register(tool)) Log.w("DshPlugin", "内置工具名冲突，未注册：${tool.name}（${group.id}）")
                    }
                }
            } else {
                appTools.forEach { registry.unregister(it.name) }
            }
        }
    }

    /**
     * M84-C / M85-E：内置模型插件注册进 [LlmProviders]。
     *
     * 两条通道现在都是**内置模型插件**（`builtin.model.deepseek` / `builtin.model.openai`）：
     * 关掉 = 不注册（选中它时由 [ModelRouting] 如实报错），不是「永远可用的宿主兜底」。
     * 同 id 的插件 provider 仍可覆盖（[syncPluginLlmProviders] 在它们之后注册）。
     */
    private fun registerBuiltinProviders() {
        if (builtinStore.isEnabled(xin.dshaa.nativeapp.data.BuiltinPlugins.NATIVE_MODEL_DEEPSEEK)) {
            LlmProviders.register(object : LlmProvider {
                override val id: String = MODEL_PROVIDER_DEEPSEEK
                override val label: String = "DeepSeek · Anthropic Messages"
                override fun stream(request: ApiRequest, config: LlmConfig): Flow<LlmEvent> =
                    client.stream(request, config)
            })
        }
        if (builtinStore.isEnabled(xin.dshaa.nativeapp.data.BuiltinPlugins.NATIVE_MODEL_OPENAI)) {
            LlmProviders.register(object : LlmProvider {
                override val id: String = MODEL_PROVIDER_OPENAI
                override val label: String = "OpenAI 兼容"
                override fun stream(request: ApiRequest, config: LlmConfig): Flow<LlmEvent> =
                    openAiClient.stream(request, config)
            })
        }
    }

    /**
     * 请求出口（M84-C / M85-E）：debug 供应商 > model/v1 解析（设置/协议）> 注册表。
     *
     * model/v1 的硬约定：选中的通道被停用/不存在时**如实报错**（LlmEvent.Failed），
     * 不悄悄换回另一条内置通道。debug 通道（M84-C 验收）仍优先，且不参与角色解析。
     */
    private fun llmStream(request: ApiRequest, config: LlmConfig): Flow<LlmEvent> {
        val debugId = if (xin.dshaa.nativeapp.BuildConfig.DEBUG) {
            prefs.getString("debug_llm_provider", null)?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        if (debugId != null) {
            val provider = LlmProviders.get(debugId)
            return if (provider != null) {
                provider.stream(request, config)
            } else {
                flow {
                    emit(
                        LlmEvent.Failed(
                            "调试供应商 $debugId 未注册（插件可能已停用）",
                            code = "MODEL_UNAVAILABLE",
                        ),
                    )
                }
            }
        }
        return when (val resolution = currentModelResolution()) {
            is ModelResolution.Active -> {
                val provider = LlmProviders.get(resolution.model.providerId)
                if (provider != null) {
                    provider.stream(request, config)
                } else {
                    flow {
                        emit(
                            LlmEvent.Failed(
                                "模型通道 ${resolution.model.providerId} 未注册（插件可能已停用）",
                                code = "MODEL_UNAVAILABLE",
                            ),
                        )
                    }
                }
            }

            is ModelResolution.Unavailable -> flow {
                emit(LlmEvent.Failed(resolution.reason, code = "MODEL_UNAVAILABLE"))
            }
        }
    }

    /** M84-C 验收通道：热切换模型供应商 / 提示词实现（仅 debug 包注册了调试实现）。 */
    fun applyDebugProviders(providerId: String?, promptMarker: String?) {
        DebugProviders.apply(prefs, providerId, promptMarker)
    }

    /** M84-C 验收：当前注册的模型通道/提示词实现与选中的 id 落盘。 */
    fun dumpProviders(dumpPath: String?) {
        if (dumpPath.isNullOrBlank()) return
        runCatching {
            // M84-E2：插件供应商只落「能公开的部分」，apiKey 不写盘（有 key 只记 hasKey）
            val pluginProviders = org.json.JSONArray()
            pluginsUi.filter { it.enabled }.forEach { record ->
                record.llmProviders.forEach { provider ->
                    pluginProviders.put(
                        org.json.JSONObject()
                            .put("id", provider.id)
                            .put("label", provider.label)
                            .put("protocol", provider.protocol)
                            .put("model", provider.model)
                            .put("baseUrl", provider.baseUrl)
                            .put("owner", record.id)
                            .put("hasKey", provider.apiKey.isNotBlank())
                            .put("hasStream", provider.hasStream),
                    )
                }
            }
            val payload = org.json.JSONObject()
                .put("llm", org.json.JSONArray(LlmProviders.ids()))
                .put("llmActive", LlmProviders.activeId ?: "")
                .put("llmSelected", settings.llmProviderId)
                .put("pluginProviders", pluginProviders)
                .put("prompt", org.json.JSONArray(PromptProviders.ids()))
                .put("promptActive", PromptProviders.activeId ?: "")
            val file = File(dumpPath)
            file.parentFile?.mkdirs()
            file.writeText(payload.toString())
            Log.i("DshProvider", "dump providers -> $dumpPath")
        }.onFailure { Log.w("DshProvider", "dump providers failed: ${it.message}") }
    }

    /** M84-C2 验收：当前存储实现 / 活动会话 / 回收站 / 磁盘会话数落盘（证明不是写死文件实现）。 */
    fun dumpSessionStore(dumpPath: String?) {
        if (dumpPath.isNullOrBlank()) return
        runCatching {
            val active = repo.list()
            val disk = runCatching {
                FileSessionRepository(sessionsRootDir).list()
            }.getOrDefault(emptyList())
            val payload = org.json.JSONObject()
                .put("store", repo.id)
                .put("root", repo.root.absolutePath)
                .put("registered", org.json.JSONArray(SessionStores.registeredIds()))
                .put("active", org.json.JSONArray(active.map { it.id }))
                .put("activeCount", active.size)
                .put("trash", org.json.JSONArray(repo.listTrash().map { it.dirName }))
                .put("diskCount", disk.size)
            val file = File(dumpPath)
            file.parentFile?.mkdirs()
            file.writeText(payload.toString())
            Log.i("DshSessionStore", "dump: store=${repo.id} active=${active.size} disk=${disk.size} -> $dumpPath")
        }.onFailure { Log.w("DshSessionStore", "dump sessions failed: ${it.message}") }
    }

    /**
     * M84-H 验收：常驻 JS 运行时状态 + 会话投影值落盘。
     *
     * 先让 syncPluginTools 走一遍（确保常驻运行时与插件开关一致），再写 active 会话的投影。
     */
    fun dumpProjections(dumpPath: String?) {
        if (dumpPath.isNullOrBlank()) return
        // 起 runtime 是异步的：dump 前等它落定，否则会把「刚启用」误读成没有常驻运行时。
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                syncPluginTools()
                pluginResidentRuntime.awaitSettled()
                // M84-N：dump 常和 `--es dsh_open_session` 一起用；等会话打开、checkpoint 恢复落定再写，
                // 否则会把「刚恢复的值」误读成空（调试通道，最多多等 3 秒）。
                val deadline = System.currentTimeMillis() + 3_000
                while (current?.state?.sessionId == null && System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.delay(100)
                }
                if (current?.state?.sessionId != null) kotlinx.coroutines.delay(300)
                val activeSessionId = current?.state?.sessionId
                val payload = pluginResidentRuntime.dumpJson(activeSessionId)
                val file = File(dumpPath)
                file.parentFile?.mkdirs()
                file.writeText(payload)
                Log.i(
                    "DshProjection",
                    "dump projections resident=${pluginResidentRuntime.residencyIds()} values=${pluginResidentRuntime.allValues().size} -> $dumpPath",
                )
            }.onFailure { Log.w("DshProjection", "dump projections failed: ${it.message}") }
        }
    }

    /**
     * M84-H 验收：单个导入插件的开/关 + 常驻运行时/投影落盘。
     *
     * `--es dsh_plugin_off <id>`：停用后常驻 runtime 关闭、该插件投影值收回；
     * `--es dsh_plugin_on <id>`：启用后重新起 runtime，dump 能再看到投影。
     */
    fun applyDebugPluginToggle(offId: String?, onId: String?, dumpPath: String?) {
        val target = (offId ?: onId)?.trim()
        if (!target.isNullOrBlank()) {
            setPluginEnabled(target, offId.isNullOrBlank())
            Log.i("DshProjection", "debug plugin toggle id=$target enabled=${offId.isNullOrBlank()} resident=${pluginResidentRuntime.residencyIds()}")
        }
        if (!dumpPath.isNullOrBlank()) dumpProjections(dumpPath)
    }

    /** 当前活动会话的投影值（插件详情展示用；空 = 没有插件注册投影）。 */
    fun projectionsForCurrentSession(): List<xin.dshaa.nativeapp.data.PluginResidentRuntime.ProjectionValue> =
        pluginResidentRuntime.valuesFor(current?.state?.sessionId)

    private fun initializeCoreRegistry() {
        registerBuiltinProviders()
        DebugProviders.install(prefs, xin.dshaa.nativeapp.BuildConfig.DEBUG)
        syncBuiltinTools()
        syncFloatingSlots()
        // M85-E：三个新角色（存储/提示词/模型）冷启动先解析；存储要赶在第一次 refreshSessions 之前绑定。
        refreshStorageResolution()
        refreshPromptResolution()
        refreshModelResolution()
    }

    /**
     * M84-A 验收通道（只有 debug 包的 MainActivity 会调）：
     * 改内置插件开关 + 把全部工具名 / 当前 tools[] 名单 / 重复归属落盘，用来逐组对拍（不发真请求）。
     * 可重复调用：同一次进程里 `am start --es dsh_builtin_off … --es dsh_dump_tools …` 会走 onNewIntent。
     */
    fun applyDebugBuiltin(offIds: String?, onIds: String?, reset: Boolean, dumpPath: String?) {
        if (reset) builtinStore.resetAll()
        // M84-F：`all` = 23 个内置插件一起关/开（空壳与全装配验收）
        if (offIds?.trim()?.equals("all", ignoreCase = true) == true) {
            xin.dshaa.nativeapp.data.BuiltinPlugins.all.forEach { builtinStore.setEnabled(it.id, false) }
        } else {
            offIds?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.forEach { builtinStore.setEnabled(it, false) }
        }
        if (onIds?.trim()?.equals("all", ignoreCase = true) == true) {
            xin.dshaa.nativeapp.data.BuiltinPlugins.all.forEach { builtinStore.setEnabled(it.id, true) }
        } else {
            onIds?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.forEach { builtinStore.setEnabled(it, true) }
        }
        syncBuiltinTools()
        syncFloatingSlots()
        builtinPluginsUi = builtinStore.ui()
        // M85-D：内置回合插件被调试通道关/开时也要立刻重解析（安全提示 / 恢复）。
        refreshAgentResolution()
        // M85-E：内置存储/提示词/模型插件同样即时重解析。
        refreshStorageResolution()
        refreshPromptResolution()
        refreshModelResolution()
        Log.i(
            "DshAgent",
            "debug builtin off=${offIds ?: "-"} on=${onIds ?: "-"} reset=$reset " +
                "agent=${activeAgent?.agentId ?: "-"} safety=${agentSafetyNotice ?: "-"}",
        )
        if (dumpPath.isNullOrBlank()) return
        runCatching {
            val payload = org.json.JSONObject()
                .put("all", org.json.JSONArray(registry.names))
                .put("catalog", org.json.JSONArray(BuiltinToolCatalog.allTools().map { it.name }))
                .put("offered", org.json.JSONArray(offeredToolNames().sorted()))
                .put("duplicates", org.json.JSONArray(xin.dshaa.nativeapp.data.BuiltinPlugins.duplicateTools()))
                .put("disabled", org.json.JSONArray(builtinStore.disabled().sorted()))
            val file = File(dumpPath)
            file.parentFile?.mkdirs()
            file.writeText(payload.toString())
            Log.i("DshPlugin", "dump tools: all=${registry.names.size} offered=${offeredToolNames().size} -> $dumpPath")
        }.onFailure { Log.w("DshPlugin", "dump tools failed: ${it.message}") }
    }

    /**
     * M84-B 验收通道（只有 debug 包的 MainActivity 会调）：
     * 把页面 / 归属插件 / 当前开关 / 未知 id / 重复归属落盘，用来对拍「页面清单由插件提供」。
     *
     * M85-C2：**页面清单已经不归宿主**——22 页的标题/层级由内置界面插件在 `shell/v1` 里声明
     * （[xin.dshaa.ui.shellapi.NativeShell.pages]），宿主只读 [xin.dshaa.ui.shellapi.ShellPageRegistry]。
     * M85-C3-4：注册表搬进 `ui:shell-api`，并把 M84-G 插件整页并进同一张表——dump 额外写
     * `pluginPages`（独立插件页 / 同 id 覆盖项），`pages` 仍是壳声明的 22 页（M84-B 断言不变）。
     */
    fun applyDebugPages(dumpPath: String?) {
        if (dumpPath.isNullOrBlank()) return
        runCatching {
            val registry = xin.dshaa.ui.shellapi.ShellPageRegistry
            val pluginEntries = pluginPageEntries()
            val declared = registry.declared()
            val builtinScreens = xin.dshaa.nativeapp.data.BuiltinPlugins.all.flatMap { it.screens }
            val pages = org.json.JSONArray()
            declared.forEach { page ->
                val owner = xin.dshaa.nativeapp.data.BuiltinPlugins.byScreen(page.id)
                pages.put(
                    org.json.JSONObject()
                        .put("id", page.id)
                        .put("title", page.title)
                        .put("level", page.level.name)
                        .put("plugin", owner?.id ?: "")
                        .put("core", owner == null)
                        .put("enabled", builtinStore.screenEnabled(page.id)),
                )
            }
            val pluginPages = org.json.JSONArray()
            registry.table(declared, pluginEntries).filter { it.isPlugin }.forEach { page ->
                pluginPages.put(
                    org.json.JSONObject()
                        .put("id", page.id)
                        .put("title", page.title)
                        .put("level", page.level.name)
                        .put("plugin", page.pluginId.orEmpty())
                        .put("pluginName", page.pluginName.orEmpty())
                        .put("overrides", page.overridesShellPage),
                )
            }
            val payload = org.json.JSONObject()
                .put("pages", pages)
                .put("pluginPages", pluginPages)
                .put("unknown", org.json.JSONArray(registry.unknownPluginScreens(builtinScreens)))
                .put("duplicates", org.json.JSONArray(registry.duplicatePluginScreens(builtinScreens)))
                // 壳声明 id 必须和宿主 id 词表一致；没有插件归属的只能是核心页（插件 / 设置）。
                .put("catalogMatch", registry.catalogMatch())
                .put("unowned", org.json.JSONArray(registry.unownedPages(builtinScreens.toSet())))
                .put("disabled", org.json.JSONArray(builtinStore.disabled().sorted()))
            val file = File(dumpPath)
            file.parentFile?.mkdirs()
            file.writeText(payload.toString())
            Log.i("DshPlugin", "dump pages: ${pages.length()} plugin=${pluginPages.length()} -> $dumpPath")
        }.onFailure { Log.w("DshPlugin", "dump pages failed: ${it.message}") }
    }

    /** 调试通道：热启动直接落页面（`--es dsh_open_screen X` 走 onNewIntent，不用冷启）。 */
    fun requestDebugScreen(name: String) {
        debugScreenRequest = name
    }

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
                val created = schedules.create(sessionId, title, prompt, timing, next, now)
                bumpSchedulesRevision()
                return created
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
                return schedules.update(id, sessionId, title, prompt, timing, next, now).also {
                    bumpSchedulesRevision()
                }
            }

            override fun delete(id: String): ScheduledTask {
                val sessionId = requireSession()
                return schedules.delete(id, sessionId).also { bumpSchedulesRevision() }
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
    override fun imagePathFor(attachmentId: String): String? = attachments.imageFile(attachmentId)?.absolutePath

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
        // M83 阶段 2：内置插件关掉后，它的工具不进 tools[]（模型看不到也调不到）。
        val names = registry.names.filter { builtinStore.toolEnabled(it) }.toSet()
        return if (settings.webSearchEnabled) names else names - "web_search"
    }

    // ---------------- 聊天输入条附件（docs/42） ----------------

    /**
     * 相册/文件选择：读 Uri → 归一化落盘（内容寻址附件存储）→ 进当前会话的待发列表。
     * 图片不立即进模型请求，等用户按发送才随 `TurnInput.images` 一起走一轮。
     */
    private fun attachmentLive(state: ChatSessionState?): LiveChat? = state?.let { target ->
        lives[target]?.takeIf { it.acceptingInputs && !target.readOnly }
    }

    override fun attachImage(uri: Uri, target: ChatSessionState?) {
        val live = attachmentLive(target) ?: return
        val state = live.state
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
    override fun attachPhoto(file: File, target: ChatSessionState?) {
        val live = attachmentLive(target) ?: run {
            deleteCameraTemp(file)
            return
        }
        val state = live.state
        state.attachError = null
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { file.inputStream().use { readAtMost(it, maxImageFileBytes + 1) } }.getOrNull().also { deleteCameraTemp(file) }
            }
            if (bytes == null || bytes.isEmpty()) {
                state.attachError = "无法读取相机照片"
                return@launch
            }
            if (bytes.size > maxImageFileBytes) {
                state.attachError = "图片超过 50MB，请先压缩"
                return@launch
            }
            state.attachError = savePendingImage(state, bytes, null, file.name)
        }
    }

    /** Only remove a verified regular file from the app's private camera cache. */
    private fun deleteCameraTemp(file: File) {
        val camera = File(getApplication<Application>().cacheDir, "camera").canonicalFile
        val resolved = file.canonicalFile
        if (resolved.parentFile == camera && resolved.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            resolved.delete()
        }
    }

    /**
     * 截图进待发附件：优先无障碍 takeScreenshot；无障碍没开（或服务没连上）时走 Shizuku
     * `screencap -p` 的 stdout —— 和悬浮窗 OCR 的截图通道一致，不依赖无障碍是否真的连上。
     */
    override fun attachScreenshot(target: ChatSessionState?) {
        val live = attachmentLive(target) ?: return
        val state = live.state
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

    override fun removePendingImage(attachmentId: String) {
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
    override fun attachFile(uri: Uri, target: ChatSessionState?) {
        val live = attachmentLive(target) ?: return
        val state = live.state
        val attachmentWorkspace = File(live.queueWorkspace)
        state.attachError = null
        viewModelScope.launch {
            val error = readPendingFile(state, uri, attachmentWorkspace)
            if (attachmentLive(state) !== live) return@launch
            state.attachError = error
        }
    }

    override fun removePendingFile(name: String) {
        val list = chat?.pendingFiles ?: return
        val index = list.indexOfFirst { it.name == name }
        if (index >= 0) list.removeAt(index)
    }

    /**
     * 把一段现成文本放进待发附件（悬浮窗「通知」「位置」附件用，docs/76 W24）。
     *
     * 和 [attachFile] 的区别：不经过 SAF 选文件，文本由调用方（NotificationListenerService /
     * LocationAttachActivity）组好；写入发起时的草稿，已交付的输入有独立快照。
     * 同名附件自动加序号，不覆盖用户已加的。
     */
    override fun attachTextFile(name: String, text: String, target: ChatSessionState?): Boolean {
        val state = attachmentLive(target)?.state ?: return false
        if (text.isBlank()) return false
        addPendingFile(state, PendingFile(name, text))
        state.attachError = null
        return true
    }

    /** Main-thread mutation preserves all attachment completions even after switching foreground chat. */
    private fun addPendingFile(state: ChatSessionState, file: PendingFile) {
        val dot = file.name.lastIndexOf('.')
        val base = if (dot > 0) file.name.substring(0, dot) else file.name
        val ext = if (dot > 0) file.name.substring(dot) else ""
        var finalName = file.name
        var n = 2
        while (state.pendingFiles.any { it.name == finalName }) {
            finalName = "$base ($n)$ext"
            n += 1
        }
        state.pendingFiles += file.copy(name = finalName)
    }

    /** 读一个用户选中的文件；返回面向用户的错误（null = 成功或已走图片管线）。 */
    private suspend fun readPendingFile(state: ChatSessionState, uri: Uri, attachmentWorkspace: File): String? = withContext(Dispatchers.IO) {
        readPendingFileContents(state, uri, attachmentWorkspace)
    }

    private suspend fun readPendingFileContents(state: ChatSessionState, uri: Uri, attachmentWorkspace: File): String? {
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
            return withContext(Dispatchers.Main.immediate) {
                if (attachmentLive(state) == null) "原会话已关闭，附件未添加"
                else { addPendingFile(state, PendingFile(name, text)); null }
            }
        }

        // Operit 的做法：任何文件都复制到本地，消息里只放 <attachment id=路径 …/>，
        // 模型用 read / read_image 工具按路径读。鲸歌复制到工作区 .dsh/attachments，
        // 这样现有 read / read_image 的 workspace 路径守卫不用放宽。
        return try {
            val (relative, bytes) = withContext(Dispatchers.IO) {
                copyAttachmentToWorkspace(uri, name, attachmentWorkspace)
            }
            val mime = declared?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                ?: runCatching { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }.getOrNull()
                ?: "application/octet-stream"
            withContext(Dispatchers.Main.immediate) {
                if (attachmentLive(state) == null) "原会话已关闭，附件未添加"
                else {
                    addPendingFile(state, PendingFile(name = name, text = "", path = relative, mime = mime, size = bytes))
                    null
                }
            }
        } catch (e: Exception) {
            "无法引用这个文件：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 原样复制到 `<workspace>/.dsh/attachments/`，返回（相对工作区的路径, 字节数）。 */
    private fun copyAttachmentToWorkspace(uri: Uri, name: String, attachmentWorkspace: File): Pair<String, Long> {
        val dir = File(attachmentWorkspace, ".dsh/attachments").apply { mkdirs() }
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
                    outs.fd.sync()
                }
            }
            if (total == 0L) error("文件是空的：$name")
            val relative = target.relativeTo(attachmentWorkspace).path.replace('\\', '/')
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
            withContext(Dispatchers.Main.immediate) {
                if (attachmentLive(state) == null) "原会话已关闭，图片未添加"
                else { state.pendingImages += PendingImage(ref, path); null }
            }
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
    override var sessions by mutableStateOf<List<SessionSummary>>(emptyList())
        private set

    /** 全部工作区的根会话（工作区清单/任务标题映射用）。 */
    private var allRootSessions: List<SessionSummary> = emptyList()

    /** 当前工作区（= [workspace]）的投影；工作区清单还没扫完时是默认项。 */
    override val currentWorkspace: WorkspaceUi
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
    override val canGoBackFromChat: Boolean get() = chatBackStack.isNotEmpty()

    /**
     * 子代理面板数据（M17）：子会话文件 + 进程内 AgentRegistry 合并。
     * 子会话不进 [sessions]（主列表只放用户自己的会话）。
     */
    override var subagents by mutableStateOf<List<SubagentUi>>(emptyList())
        private set

    /** 最近一次 `repo.list()` 里的子会话（磁盘骨架，重启后面板靠它）。 */
    private var childSessions: List<SessionSummary> = emptyList()

    /** 首次扫描会话目录还没完成（冷启动要解压 100+ 会话，面板不能先闪「没有子代理」）。 */
    override var subagentsLoading by mutableStateOf(true)
        private set

    /** 子代理深度上限（面板状态卡显示）。 */
    override val agentMaxDepth: Int get() = agentRegistry.limit()

    /** 工具箱/设置页展示当前注册的工具名（含 workflow 的第 32 个）。 */
    val toolNames: List<String> get() = registry.names

    /**
     * 任务面板数据（M20）：进程内 `JobRegistry` 的全表投影（跨 owner）。
     * 后台任务不落盘，重启后为空——面板如实说明，不做假历史。
     */
    override var jobs by mutableStateOf<List<JobUi>>(emptyList())
        private set

    /** 已实现的工具数量（工具页/工作台统计用）。 */
    override val appVersionName: String get() = xin.dshaa.nativeapp.BuildConfig.VERSION_NAME
    override val appVersionCode: Int get() = xin.dshaa.nativeapp.BuildConfig.VERSION_CODE

    override val toolCount: Int get() = offeredToolNames().size

    /**
     * 当前打开的聊天。null = 停在列表/工作台。
     * 切会话只是把前台指到别的 [ChatSessionState]：原来那条的轮次在后台继续跑（[LiveChat.job]）。
     */
    override var chat by mutableStateOf<ChatSessionState?>(null)
        private set

    /** 打开过的会话运行时（state → LiveChat）：切走不销毁，切回来直接复用，不重读文件、不丢流式状态。 */
    private val lives = LinkedHashMap<ChatSessionState, LiveChat>()
    private var current: LiveChat? = null

    /**
     * 切工作区时被摘掉、但引擎收尾还没写完的 writer（按工作区绝对路径记账）。
     * 删除工作区必须先 `join` 它们再移动目录，否则 NonCancellable 的收尾写入会落到回收站里。
     */
    private val writerBarrier = SessionWriterBarrier()

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
    override fun badgeFor(sessionId: String?): SessionBadge? {
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
    override fun waitingElsewhere(): List<WaitingSession> {
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
    override fun focusSession(id: String) {
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
    override val sessionsDir: File get() = repo.root

    /**
     * 调试通道 `--es dsh_import_zip <路径>` 带进来的备份包：
     * 非空时外壳直接把「旧版数据导入」页打开并预扫描（真机无 SAF 也能验收）。
     */
    override var importRequest by mutableStateOf<String?>(null)
        private set

    override fun consumeImportRequest() {
        importRequest = null
        settings.debugImportPath = null
    }

    /** 调试通道 `--es dsh_open_screen <Screen>`：启动即落到指定一级页面（真机验收用）。 */
    override var debugScreenRequest by mutableStateOf<String?>(null)
        private set

    override fun consumeDebugScreen() {
        debugScreenRequest = null
        settings.debugScreen = null
    }

    /**
     * 一个会话的运行时（2026-11-08 夜间：切会话不再中断生成）。
     * 会话文件句柄、停止位、计划模式、goal activation、引擎、正在跑的 job 全在这里；
     * 引擎是**每会话一个**，`sessions.current()` 绑死自己的句柄——后台轮次写的永远是自己的文件。
     */
    private inner class LiveChat(val state: ChatSessionState) {
        /** M84-H：句柄一挂上就让插件会话投影开始收事件（含历史重放）。 */
        var handle: SessionSession? = null
            set(value) {
                field = value
                if (value != null) pluginResidentRuntime.attach(value)
            }
        val stop = TurnStopState()
        val plan = PlanModeState()
        var goalActivation = GoalActivationState()
        var job: Job? = null
        val queueWorkspace = workspace.absolutePath
        val queueStorageId = activeStorage?.storageId ?: BUILTIN_STORAGE_ID
        var acceptingInputs = true
        var runningAgentBuiltin = true
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

    private val promptQueue by lazy {
        xin.dshaa.core.session.PromptQueueStore(File(getApplication<Application>().filesDir, "prompt-queue"))
    }

    private fun queueOwner(live: LiveChat): xin.dshaa.core.session.PromptQueueOwner? = live.state.sessionId?.let {
        xin.dshaa.core.session.PromptQueueOwner(live.queueStorageId, it, live.queueWorkspace)
    }

    private fun refreshPromptQueue(live: LiveChat) {
        val owner = queueOwner(live) ?: return
        live.state.queuedPrompts = promptQueue.list(owner)
    }

    private fun pausePromptQueue(live: LiveChat, reason: String) {
        val owner = queueOwner(live) ?: return
        try {
            promptQueue.pause(owner, reason)
            refreshPromptQueue(live)
            if (live.state.queuedPrompts.isNotEmpty()) live.state.queueNotice = reason
        } catch (e: Exception) {
            live.state.queueNotice = "保存队列状态失败：${e.message}"
            Log.e("DshQueue", "pause failed session=${owner.sessionId}", e)
        }
    }

    private fun recoverPromptQueue(live: LiveChat) {
        val owner = queueOwner(live) ?: return
        val session = live.handle ?: return
        promptQueue.recover(owner, session.events)
        refreshPromptQueue(live)
        if (live.state.queuedPrompts.isNotEmpty()) live.state.queueNotice = "已保留待发送消息，点击继续发送"
    }

    private fun xin.dshaa.core.session.QueuedPrompt.turnInput() = TurnInput(
        text = payload.text, textBlocks = payload.textBlocks, images = payload.images,
        promptId = id, delivery = delivery.name.lowercase(),
    )

    /** Current selected roles must be ready before consuming draft or queue data. */
    private fun inputReadinessError(state: ChatSessionState): String? {
        if (!runtimeReady) return runtimeInitError?.let { "运行时初始化失败：$it" } ?: "运行时正在准备，请稍后发送"
        if (state.readOnly) return "当前会话为只读，不能发送"
        roleSafetyError()?.let { return it }
        refreshAgentResolution()
        if (activeAgent == null) return agentSafetyNotice ?: "没有可用的回合插件"
        (currentModelResolution() as? ModelResolution.Unavailable)?.let { return "模型安全提示：${it.reason}" }
        pendingRoleRegistrations().firstOrNull()?.let {
            return "当前角色插件尚未就绪：$it，请稍后发送"
        }
        return null
    }

    private fun pendingRoleRegistrations(): List<String> = buildList {
        activeAgent?.let { agent -> agent.pluginId?.let { plugin ->
            if (agent.agentId !in pluginResidentRuntime.agentsOf(plugin)) add("$plugin#${agent.agentId}")
        } }
        activePromptState?.let { prompt -> prompt.pluginId?.let { plugin ->
            if (prompt.promptId !in pluginResidentRuntime.promptsOf(plugin)) add("$plugin#${prompt.promptId}")
        } }
        (currentModelResolution() as? ModelResolution.Active)?.model?.let { model -> model.pluginId?.let { plugin ->
            if (pluginsUi.any { it.id == plugin && it.llmProviders.any { p -> p.id == model.providerId && p.hasStream } } &&
                model.providerId !in pluginResidentRuntime.streamsOf(plugin)) add("$plugin#${model.providerId}")
        } }
    }.distinct()

    private fun claimPrompt(live: LiveChat, steering: Boolean = false): TurnInput? {
        if (!live.acceptingInputs || live.stop.requested) return null
        val owner = queueOwner(live) ?: return null
        val error = inputReadinessError(live.state)
            ?: if (workspace.absolutePath != live.queueWorkspace || activeStorage?.storageId != live.queueStorageId) "会话工作区或存储已切换" else null
        if (error != null) {
            pausePromptQueue(live, error)
            return null
        }
        val pending = promptQueue.list(owner).firstOrNull { it.status == xin.dshaa.core.session.PromptQueueStatus.PENDING &&
            it.pausedReason == null && (!steering || it.delivery == xin.dshaa.core.session.PromptDelivery.STEER) } ?: return null
        if ((!live.runningAgentBuiltin || activeAgent?.builtin == false) && (pending.payload.images.isNotEmpty() || pending.payload.textBlocks.isNotEmpty())) {
            pausePromptQueue(live, "当前回合插件仅支持纯文本，切回内置回合后继续")
            return null
        }
        if (pending.payload.images.isNotEmpty() && !imageInputAvailable) {
            pausePromptQueue(live, "当前模型未开启识图，调整模型后继续")
            return null
        }
        val claimed = promptQueue.claim(owner, if (steering) xin.dshaa.core.session.PromptDelivery.STEER else null)
        refreshPromptQueue(live)
        return claimed?.turnInput()
    }

    private suspend fun ackPrompt(live: LiveChat, id: String, seq: Long) {
        val owner = queueOwner(live) ?: error("队列所属会话不存在")
        withContext(Dispatchers.IO) {
            requireNotNull(live.handle).flushDurable()
            promptQueue.ack(owner, id, seq)
        }
        refreshPromptQueue(live)
        if (live.state.queuedPrompts.isEmpty()) live.state.queueNotice = null
    }

    override fun cancelQueuedPrompt(id: String) {
        val live = current ?: return
        val owner = queueOwner(live) ?: return
        try {
            if (!promptQueue.cancel(owner, id)) live.state.queueNotice = "这条消息已进入交付，不能取消"
            refreshPromptQueue(live)
            if (live.state.queuedPrompts.isEmpty()) live.state.queueNotice = null
        } catch (e: Exception) { live.state.queueNotice = "取消失败：${e.message}" }
    }

    override fun resumeQueuedPrompts() {
        val live = current ?: return
        val owner = queueOwner(live) ?: return
        inputReadinessError(live.state)?.let { live.state.queueNotice = it; return }
        if (live.job?.isCompleted == false || live.state.streaming) {
            live.state.queueNotice = "当前轮结束后再继续发送"
            return
        }
        try {
            live.handle?.let { promptQueue.recover(owner, it.events) }
            promptQueue.resume(owner)
            live.stop.requested = false
            live.state.queueNotice = null
            refreshPromptQueue(live)
            val next = claimPrompt(live) ?: return
            if (!startTurn(live.state, next)) {
                recoverPromptQueue(live)
                pausePromptQueue(live, live.state.attachError ?: "当前不能发送")
            }
        } catch (e: Exception) { live.state.queueNotice = "继续发送失败：${e.message}" }
    }

    /** Only after the writer has finished may claims be recovered/cancelled for a deleted session. */
    private suspend fun discardSessionQueue(id: String) {
        val live = liveById(id)
        live?.acceptingInputs = false
        live?.stop?.requested = true
        live?.job?.cancel()
        live?.job?.join()
        val owners = promptQueue.list().filter { it.owner.sessionId == id && it.owner.storageId == (live?.queueStorageId ?: activeStorage?.storageId) }
            .map { it.owner }.distinct()
        owners.forEach { owner -> promptQueue.discard(owner) }
        live?.let { refreshPromptQueue(it) }
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

    /**
     * M83 上下文明细三段：拿**本次真正发出去的请求体**现算系统 / 工具 / 对话三段的字符，
     * 再折算成 token 估算。
     *
     * 旧实现只在 `apiTools` / `systemPrompt` 里估前两段，面板里「对话消息」= 真实
     * `usage.prompt_tokens` − 两段估算；估算只要偏高一点，对话就恒为 0（用户反馈）。
     * 现在三段同一口径、同一个请求快照，面板再按真实 prompt tokens 等比校准（见 ContextPanel）。
     */
    private fun recordPromptBreakdown(state: ChatSessionState, request: ApiRequest) {
        val system = estimateTokens(request.system.orEmpty())
        val tools = estimateTokens(
            buildString {
                request.tools.orEmpty().forEach { tool ->
                    append(tool.name).append('\n')
                    append(tool.description.orEmpty()).append('\n')
                    append(tool.inputSchema.toString()).append('\n')
                }
            },
        )
        val messages = estimateTokens(apiMessagesText(request.messages))
        fun imageCount(blocks: List<ApiBlock>): Int = blocks.sumOf { block ->
            when (block) {
                is ApiBlock.Image -> 1
                is ApiBlock.ToolResult -> imageCount(block.content.orEmpty())
                else -> 0
            }
        }
        val previous = state.contextUsage ?: xin.dshaa.core.model.ContextUsage(0)
        state.contextUsage = previous.copy(
            model = request.model, protocol = settings.protocol, window = settings.contextWindow,
            estimatedSystem = system, estimatedTools = tools, estimatedMessages = messages,
            imageCount = request.messages.sumOf { imageCount(it.content) },
        )
        state.contextInvalidationReason = null
        state.contextWindow = settings.contextWindow
    }

    /** 请求消息里「有 token 重量」的文本；图片按块算，不把 base64 字符数当 token 估。 */
    private fun apiMessagesText(messages: List<ApiMessage>): String = buildString {
        fun appendBlock(block: ApiBlock) {
            when (block) {
                is ApiBlock.Text -> append(block.text)
                is ApiBlock.Thinking -> append(block.thinking)
                is ApiBlock.ToolUse -> {
                    append(block.name)
                    append(block.input.toString())
                }
                is ApiBlock.ToolResult -> block.content?.forEach { appendBlock(it) }
                is ApiBlock.Image -> append("[image]")
            }
            append('\n')
        }
        messages.forEach { message ->
            append(message.role).append('\n')
            message.content.forEach { appendBlock(it) }
        }
    }

    /**
     * M85-B2-2：引擎钩子 = debug 钩子 + 工具耗时记录。
     *
     * 只观察不改循环：起点/终点时间戳写进会话状态，工具卡按 toolCallId 取；
     * 工具在后台/子代理里跑也照样记录，跟 UI 是否在前台无关。
     */
    private fun turnHooks(state: ChatSessionState): EngineHooks {
        val base = DebugTurnHook.hooks
        return EngineHooks(
            onStepStart = base.onStepStart,
            onStepEnd = base.onStepEnd,
            onToolStart = { call ->
                base.onToolStart(call)
                state.noteToolStart(call.id)
            },
            onToolEnd = { call, result ->
                base.onToolEnd(call, result)
                state.noteToolEnd(call.id)
            },
            onStop = base.onStop,
            onError = base.onError,
            onRetry = base.onRetry,
        )
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
                    promptPack.androidApiTools().filter { it.name in offeredToolNames() } + pluginApiTools +
                        pluginDevTools.apiTools().filter { it.name in offeredToolNames() }
                },
                systemPrompt = {
                    val text = buildSystemPrompt(
                        PromptRequest(
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
                        ),
                    )
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
                        llmStream(request, config)
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
                    recordPromptBreakdown(live.state, request)
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
                // M79：设置里关掉自动压缩时直接给 null（手工「立即压缩」不受影响）；比例可调
                contextWindow = {
                    if (settings.autoCompact) settings.effectiveContextWindow.takeIf { it > 0 } else null
                },
                compactionRatio = { settings.autoCompactRatio.toDouble() },
                log = { message -> Log.i("DshCompact", message) },
                plan = live.plan,
                onPlanChanged = { syncPlanUiFor(live) },
                hooks = turnHooks(live.state),
                claimSteeringInputs = { listOfNotNull(claimPrompt(live, steering = true)) },
                nextQueuedInput = { claimPrompt(live) },
                onInputDelivered = { id, seq -> ackPrompt(live, id, seq) },
            ),
    )


    /** 已批准过的工具（本进程；「总是允许」）。 */
    private val approvedTools = mutableSetOf<String>()

    // ---- 技能（M19/M21） ----
    // 注意：这三个 Compose 状态必须在 init 之前声明——init 里的调试通道会走 submit()，
    // 而 submit 要用 skillsUi 判断「/名字 是技能还是未知命令」。

    /** 设置页「技能」的数据：当前发现的技能（按名字序）。 */
    override var skillsUi by mutableStateOf<List<SkillSummary>>(emptyList())
        private set

    /** 发现是否完整（某个根读失败 → 目录消息带「发现不完整」提示）。 */
    override var skillsComplete by mutableStateOf(true)
        private set

    /** 被忽略的 SKILL.md 及原因（frontmatter 坏了、同名低优先级……）。 */
    override var skillsWarnings by mutableStateOf<List<String>>(emptyList())
        private set

    /** 包管理页「导入技能」的一次性结果（Snackbar 用）。 */
    override var skillNotice by mutableStateOf<String?>(null)
        private set

    private fun installRuntimeBindings() {
        if (runtimeBindingsInstalled) return
        runtimeBindingsInstalled = true
        // 悬浮窗的桥：服务里的 Compose 面板通过它读写同一个 ViewModel
        ChatMirror.host = this
        // M85-C3-3c：虚拟屏 Overlay 已搬进 native-shell，停止虚拟屏时仍要停当前聊天回合
        xin.dshaa.nativeapp.device.shower.VirtualScreenRuntime.onStopChat = { ChatMirror.stop() }
        // 随包文件与技能快照已由 initializeRuntime 准备，登记已完成。
        Log.i("DshShell", "active=${shellActive?.pluginId}:${shellActive?.shellId} pref=${prefs.getString("dsh_shell", null)}")
        // M85-D：冷启动按 `dsh_agent` 选回合插件（native/空 = builtin.agent.dsh）
        refreshAgentResolution()
        Log.i(
            "DshAgent",
            "active=${activeAgent?.pluginId ?: "builtin"}:${activeAgent?.agentId ?: "-"} " +
                "pref=${prefs.getString("dsh_agent", null)} safety=${agentSafetyNotice ?: "-"}",
        )
        // 壳的 `ctx.surface.command` 落到本 ViewModel（桥是进程级的，ViewModel 只有一个）
        xin.dshaa.nativeapp.data.SurfaceCommandRouter.handler = { command, args ->
            shellCommand(command, args)
        }
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
    }

    /** Serialize claimed debug inputs across Activity recreation and attachment/session IO. */
    private val debugInputMutex = kotlinx.coroutines.sync.Mutex()

    private fun consumeStartupDebug() {
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
                    getApplication(),
                    registry,
                    toolContext,
                    keepSession = keepSession,
                )
            }
        }
        val auto = sp.getString("debug_send", null)
        val debugGoal = sp.getString("debug_goal", null)
        val debugGoalRounds = sp.getInt("debug_goal_max_rounds", 0).takeIf { it > 0 }
        val debugAttach = sp.getString("debug_attach_image", null)
        val debugAttachText = sp.getString("debug_attach_text", null)
        val openSessionId = sp.getString("debug_open_session", null)
        val debugPlanMode = sp.getBoolean("debug_plan_mode", false)
        val hasDebugInput = !auto.isNullOrBlank() || !debugGoal.isNullOrBlank() ||
            !debugAttach.isNullOrBlank() || !debugAttachText.isNullOrBlank() || !openSessionId.isNullOrBlank()
        // Claim the complete input before launching/suspending. A recreated Activity can consume
        // another request while this one waits for disk; late removals would erase or repeat it.
        if (hasDebugInput) sp.edit().remove("debug_send").remove("debug_goal").remove("debug_goal_max_rounds")
            .remove("debug_attach_image").remove("debug_attach_text").remove("debug_open_session")
            .remove("debug_plan_mode").apply()
        runRecorder?.record("run.meta") {
            put("scenario", prefs.getString("debug_parity_scenario", "").orEmpty())
            put("model", settings.model)
            put("provider", providerLabel())
            put("baseUrl", settings.baseUrl)
            put("approvalMode", settings.approvalMode)
            put("tools", registry.names.size)
            put("prompt", auto.orEmpty())
        }
        if (hasDebugInput) viewModelScope.launch {
            debugInputMutex.lock()
            try {
                val target = when {
                    !openSessionId.isNullOrBlank() -> {
                        // Opening and all subsequent attachment/send work belong to this request.
                        openSessionById(openSessionId)
                        chat
                    }
                    !debugGoal.isNullOrBlank() -> {
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
                        state
                    }
                    else -> {
                        newChat()
                        if (!auto.isNullOrBlank() && debugPlanMode) {
                            planState.active = true
                            planState.entered = true
                            syncPlanUi()
                        }
                        chat
                    }
                } ?: return@launch
                attachDebugImage(debugAttach, target)
                attachDebugText(debugAttachText, target)
                if (!auto.isNullOrBlank()) {
                    if (chat !== target) {
                        // The user may switch during IO; retain this input on its original state.
                        val previous = target.draft.text
                        target.draft = androidx.compose.ui.text.input.TextFieldValue(
                            if (previous.isBlank()) auto else previous + "\n\n" + auto,
                        )
                        target.commandNotice = "调试发送时会话已切换，消息已保留为原会话草稿"
                    } else if (!submit(auto)) {
                        val previous = target.draft.text
                        target.draft = androidx.compose.ui.text.input.TextFieldValue(
                            if (previous.isBlank()) auto else previous + "\n\n" + auto,
                        )
                        target.commandNotice = "调试消息未被接受，已保留草稿"
                    }
                }
            } finally {
                debugInputMutex.unlock()
            }
        }
    }

    /**
     * 调试通道：把一张本地图片读进待发附件（与用户从相册选图走同一个 [savePendingImage]，
     * 覆盖归一化/落盘/能力门前的全部路径）。文件读不到就静默跳过（自动验收要能继续发文字）。
     */
    private suspend fun attachDebugImage(path: String?, target: ChatSessionState? = chat) {
        if (path.isNullOrBlank()) return
        val state = target ?: return
        val file = File(path)
        val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() } ?: return
        state.attachError = savePendingImage(state, bytes, null, file.name)
    }

    /**
     * 调试通道：`--es dsh_attach_text <绝对路径>` 把设备上一个文本文件读进待发附件，
     * 走的是和「附件 → 文件」同一条 [attachTextFile]（M74 引用 chip 验收用）。
     */
    private suspend fun attachDebugText(path: String?, target: ChatSessionState? = chat) {
        if (path.isNullOrBlank()) return
        val state = target ?: return
        val file = File(path)
        val text = withContext(Dispatchers.IO) { runCatching { file.readText(Charsets.UTF_8) }.getOrNull() } ?: return
        attachTextFile(file.name, text, state)
    }

    override fun refreshSessions() {
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
    override fun onRestoredFromBackup() {
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
    override fun switchWorkspace(path: String): String? {
        if (path.isBlank()) return "工作区路径为空"
        if (path == workspace.absolutePath) return null
        // Retain all old-workspace inputs before invalidating their runtimes.
        lives.values.toList().forEach { live ->
            pausePromptQueue(live, "工作区已切换，待发送消息已保留")
            // 取消 ≠ 写完：引擎的 NonCancellable 收尾还要写 tool/result、step/end、turn/end。
            // 把 job 记进屏障，删除工作区时靠 join 等它真正结束再移动目录（见 deleteWorkspace）。
            live.job?.let { job -> writerBarrier.track(live.queueWorkspace, job) }
            live.acceptingInputs = false
            live.stop.requested = true
            live.job?.cancel()
        }
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
        skills = FileSystemSkills(
            FileSystemSkills.androidRoots(workspace, getApplication<Application>().filesDir),
            disabled = { settings.disabledSkills },
        )
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
    override fun createWorkspace(name: String, path: String?): String? {
        val result = workspaceStore.create(name, path)
        val entry = result.getOrElse { return it.message ?: "新建工作区失败" }
        return switchWorkspace(entry.path)
    }

    override fun renameWorkspace(id: String, name: String) {
        if (workspaceStore.rename(id, name)) applyWorkspaceFilter()
    }

    /**
     * 删除工作区：目录与会话都移到回收站（`files/workspaces/.trash`、`files/sessions/.trash`），
     * 不真删。默认工作区与历史位置不可删。返回 null = 成功。
     */
    override fun deleteWorkspace(id: String): String? {
        val entry = workspaceStore.find(id) ?: return "工作区不存在"
        if (id == WorkspaceStore.DEFAULT_ID) return "默认工作区不能删除"
        val target = entry.path
        if (entry.path == workspace.absolutePath) {
            // 先切回默认工作区，避免把当前会话的根删掉。
            // switchWorkspace 会把被取消的 writer 记进 writerBarrier（取消 ≠ 写完）。
            switchWorkspace(workspaceStore.defaultPath)
        }
        val removed = workspaceStore.remove(id) ?: return "工作区不存在"
        viewModelScope.launch {
            // 目录移动必须是最后一步：旧 writer 的收尾（NonCancellable 里补 tool/result、
            // step/end、turn/end）都还写在原路径上，先移动就会把回收站历史写缺/写坏。
            awaitWorkspaceWriters(removed.path)
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
     * 等工作区 [path] 名下所有 writer **真正**结束（含 NonCancellable 收尾）再返回。
     *
     * 两类来源都要等：还挂在 [lives] 里的（正常不会有了，防御性处理）和切工作区时
     * 被摘掉、记进 [writerBarrier] 的。不用固定延迟：join 是唯一可靠的完成信号。
     */
    private suspend fun awaitWorkspaceWriters(path: String) {
        val bound = lives.values.filter { it.queueWorkspace == path }
        bound.forEach { live ->
            pausePromptQueue(live, "工作区已切换，待发送消息已保留")
            live.acceptingInputs = false
            live.stop.requested = true
            live.job?.cancel()
        }
        bound.forEach { live -> live.job?.join() }
        writerBarrier.cancelAndJoinAll(path)
    }

    /**
     * 刷新子代理面板：先按内存里的代理表立即翻状态（不等解压会话文件），
     * 再读一遍仓库拿子会话的标题/预览。注册表 start/settle 会调它。
     */
    override fun refreshSubagents() {
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
    override fun refreshJobs() {
        viewModelScope.launch {
            val entries = jobRegistry.listAll()
            jobs = buildJobRows(entries, allRootSessions + childSessions)
        }
    }

    /** 详情弹层的大预览（默认 20K 字符；仍是只读，不推进模型游标）。 */
    override fun peekJob(id: String): JobUi? {
        val row = jobs.firstOrNull { it.id == id } ?: return null
        return runCatching {
            buildJobRows(listOf(jobRegistry.peek(id, row.owner)), sessions + childSessions).single()
        }.getOrNull()
    }

    /** 面板停止一个后台任务（二次确认在 UI）；返回错误文案，成功为 null。 */
    override fun stopJob(id: String): String? {
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
    override fun openSubagentSession(id: String) {
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
    override fun stopSubagent(id: String): String? {
        val error = runCatching { interruptAgent(id) }.exceptionOrNull()?.message
        refreshSubagents()
        return error
    }

    /** 删除会话：移到 .trash（不真删），列表立即刷新。 */
    override fun deleteSession(id: String) {
        viewModelScope.launch {
            try { discardSessionQueue(id) } catch (e: Exception) {
                commandNotice("删除失败：无法清理待发送消息：${e.message}")
                return@launch
            }
            withContext(Dispatchers.IO) {
                repo.trash(id)
                // 会话没了，它的提醒也一起失效（提醒不跨会话复用）
                runCatching { schedules.removeForSession(id) }
            }
            // 存储里已经没有这条会话：运行时无论前台后台都必须作废（见 invalidateLivesForDeletedSession）
            invalidateLivesForDeletedSession(id)
            if (id in unreadDone) unreadDone = unreadDone - id
            refreshSessions()
        }
    }

    /**
     * 会话已经从存储里消失后收口它的运行时（删除 / 彻底删除 / 清空回收站共用）。
     *
     * 以前只在「删的是当前会话」时移除 live：后台会话 A 被 [discardSessionQueue] 置
     * `acceptingInputs=false` 后仍留在表里，从回收站恢复并打开 A 会复用这条失效运行时，
     * 发送/附件一直被拒，直到重建 ViewModel。现在前后台一律作废，前台额外脱离聊天页；
     * 其它会话的草稿、待发附件和返回栈一概不动。
     */
    private fun invalidateLivesForDeletedSession(id: String) {
        val states = SessionLifecyclePolicy.runtimesForDeletedSession(
            lives.entries.associate { entry -> entry.key to entry.key.sessionId },
            id,
        )
        states.forEach { state ->
            val live = lives.remove(state) ?: return@forEach
            // discardSessionQueue 正常路径已经取消并 join 过；这里兜底以防调用方漏掉。
            if (live in activeTurns) {
                live.stop.requested = true
                live.job?.cancel()
            }
            markStopped(live)
            BigFish.forget(state)
        }
        // 返回栈里指向已删会话的项也清掉（否则返回键会把它重新 attach 成一条空壳）。
        // 只删这一条会话的项，别的会话草稿仍然留在栈里。
        chatBackStack.removeAll { it.sessionId == id }
        if (chat?.sessionId == id) attach(null)
    }

    // ---------------- 回收站 / 彻底删除（M79，用户 2026-11-12） ----------------

    /** 回收站列表（会话列表页「回收站」弹层用）。 */
    override var trashUi by mutableStateOf<List<TrashedSession>>(emptyList())
        private set

    override var trashBusy by mutableStateOf(false)
        private set

    /** 用户要看回收站时刷一次（不做常驻监听，回收站变化都由本进程的动作触发）。 */
    override fun refreshTrash() {
        viewModelScope.launch {
            trashUi = withContext(Dispatchers.IO) { runCatching { repo.listTrash() }.getOrDefault(emptyList()) }
        }
    }

    /** **彻底删除**一条会话：不经过回收站，目录直接删掉；删的是当前会话时同时停轮次并回草稿。 */
    override fun deleteSessionPermanently(id: String) {
        viewModelScope.launch {
            try { discardSessionQueue(id) } catch (e: Exception) {
                commandNotice("删除失败：无法清理待发送消息：${e.message}")
                return@launch
            }
            trashBusy = true
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    repo.purge(id)
                }.getOrDefault(false).also { runCatching { schedules.removeForSession(id) } }
            }
            trashBusy = false
            if (!ok) {
                commandNotice("彻底删除失败：找不到这条会话")
                refreshSessions()
                return@launch
            }
            invalidateLivesForDeletedSession(id)
            commandNotice("已彻底删除会话（不可恢复）")
            refreshSessions()
            refreshTrash()
        }
    }

    /** 回收站里的一条：彻底删除。 */
    override fun purgeTrashed(dirName: String) {
        viewModelScope.launch {
            val target = withContext(Dispatchers.IO) { repo.listTrash().firstOrNull { it.dirName == dirName } }
            if (target == null) { commandNotice("找不到这条回收站会话"); return@launch }
            try { discardSessionQueue(target.id) } catch (e: Exception) {
                commandNotice("删除失败：无法清理待发送消息：${e.message}")
                return@launch
            }
            trashBusy = true
            val ok = withContext(Dispatchers.IO) { runCatching { repo.purgeTrashed(dirName) }.getOrDefault(false) }
            trashBusy = false
            if (ok) {
                // 这条会话已经彻底没了：如果它正好是当前打开的那条（刚被移进回收站又打开过），
                // 或者后台还留着运行时，一并作废
                invalidateLivesForDeletedSession(target.id)
                commandNotice("已彻底删除（不可恢复）")
            } else {
                commandNotice("彻底删除失败：文件被占用？")
            }
            refreshTrash()
            refreshSessions()
        }
    }

    /** 回收站里的一条：恢复回原工作区。 */
    override fun restoreTrashed(dirName: String) {
        viewModelScope.launch {
            trashBusy = true
            val ok = withContext(Dispatchers.IO) { runCatching { repo.restoreTrashed(dirName) }.getOrDefault(false) }
            trashBusy = false
            commandNotice(if (ok) "已恢复到会话列表" else "恢复失败：原位置已有同 id 会话")
            refreshTrash()
            refreshSessions()
        }
    }

    /** 清空回收站。 */
    override fun purgeAllTrashed() {
        viewModelScope.launch {
            val targets = withContext(Dispatchers.IO) { repo.listTrash() }
            try { targets.forEach { discardSessionQueue(it.id) } } catch (e: Exception) {
                commandNotice("清空失败：无法清理待发送消息：${e.message}")
                return@launch
            }
            trashBusy = true
            val count = withContext(Dispatchers.IO) { runCatching { repo.purgeAllTrash() }.getOrDefault(0) }
            trashBusy = false
            targets.forEach { invalidateLivesForDeletedSession(it.id) }
            commandNotice("回收站已清空：彻底删除 $count 条")
            refreshTrash()
            refreshSessions()
        }
    }

    /** 重命名会话：追加 session/title，刷新列表。 */
    override fun renameSession(id: String, title: String) {
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
    override fun branchFromMessage(message: ChatMessage): String? {
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
    override fun regenerateFromMessage(message: ChatMessage): String? {
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

    override fun openSession(summary: SessionSummary) {
        // 切会话**不取消**正在跑的轮次（2026-11-08 夜间）：这条会话如果本来就活在后台
        // （有 job 在跑或刚跑完），直接把前台指回它——流式光标、工具卡、待办都还在。
        // 但已经关掉输入的运行时（删除过/切过工作区）不能复用：它会让发送/附件永久被拒。
        liveById(summary.id)?.takeIf { it.acceptingInputs }?.let { live ->
            attach(live)
            syncPlanUi()
            return
        }
        viewModelScope.launch { openSessionById(summary.id) }
    }

    /** 打开一个已有会话（列表点击与调试通道 `--es dsh_open_session` 共用）。 */
    private suspend fun openSessionById(id: String) {
        // 后台已有这条会话的运行时：复用，不要重读文件把流式状态冲掉
        val existing = liveById(id)
        if (existing != null) {
            if (existing.acceptingInputs) {
                attach(existing)
                syncPlanUi()
                return
            }
            // 失效运行时（待发消息已清理/会话删过又恢复）：丢掉按磁盘重读，
            // 否则会带着 acceptingInputs=false 一直拒绝发送和附件。
            lives.remove(existing.state)
            BigFish.forget(existing.state)
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
            readOnlyReason = if (loaded.readOnly) readOnlyReasonOf(loaded) else null,
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
        try { recoverPromptQueue(live) } catch (e: Exception) {
            state.queueNotice = "恢复待发送消息失败：${e.message}"
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
    /**
     * M85-E：只读会话的来源文案（旧版导入副本 / 子代理回放 / 只读存储插件）。
     * 只读存储插件打开的任何会话都按 storage 文案（不是「旧版导入」）。
     */
    private fun readOnlyReasonOf(loaded: SessionSession): String =
        if (repo.readOnly && loaded.readOnly) READ_ONLY_STORAGE else READ_ONLY_IMPORT

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
        state.readOnlyReason = readOnlyReason ?: if (loaded.readOnly) readOnlyReasonOf(loaded) else null
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

    override fun newChat() {
        // 旧草稿（没会话、没消息、没在跑）直接回收，避免 lives 越堆越多；
        // 正在跑的会话原样留在后台继续写它自己的文件。
        // 但回收前必须看 draft / pendingImages / pendingFiles：这些内容还没有任何会话文件，
        // 跟着运行时一起丢就是用户数据丢失（M86 收尾修复）。有内容就搬到新会话输入框。
        var carried: ChatSessionState? = null
        current?.let { old ->
            val disposition = SessionLifecyclePolicy.disposeOfUnpersistedChat(
                sessionId = old.state.sessionId,
                messageCount = old.state.messages.size,
                hasJob = old.job != null,
                draftText = old.state.draft.text,
                pendingImages = old.state.pendingImages.size,
                pendingFiles = old.state.pendingFiles.size,
            )
            if (disposition != SessionLifecyclePolicy.EmptyChatDisposition.KEEP) {
                if (disposition == SessionLifecyclePolicy.EmptyChatDisposition.CARRY_DRAFT) carried = old.state
                lives.remove(old.state)
                // 大肥鱼那份快照也一起清（M78：按会话记状态）
                BigFish.forget(old.state)
            }
        }
        chatBackStack.clear()
        val state = ChatSessionState(settings.model)
        carried?.let { old ->
            // 光标放到末尾：草稿是「接着写」的，不是重新选中全部
            val text = old.draft.text
            if (text.isNotEmpty()) {
                state.draft = androidx.compose.ui.text.input.TextFieldValue(
                    text = text,
                    selection = androidx.compose.ui.text.TextRange(text.length),
                )
            }
            state.pendingImages.addAll(old.pendingImages)
            state.pendingFiles.addAll(old.pendingFiles)
        }
        val live = LiveChat(state)
        lives[state] = live
        attach(live)
        syncPlanUi()
    }

    override fun closeChat() {
        // 只把前台切回栈里的父会话（或空），后台轮次不动
        val previous = chatBackStack.removeLastOrNull()
        attach(if (previous != null) liveFor(previous) else null)
    }

    /**
     * 输入条模型下拉直接切模型：settings 决定下一次请求，state.model 决定顶栏/输入条显示。
     * 以前只能去模型配置页改：settings 变了但当前会话顶栏还顶着旧名字（用户反馈的「header 不一致」）。
     */
    override fun switchModel(model: String) {
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
    override fun setPlanMode(active: Boolean) {
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
    override fun submit(text: String): Boolean {
        val state = chat ?: return false
        if (state.readOnly) return false
        roleSafetyError()?.let { reason ->
            state.attachError = reason
            return false
        }
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
            if (text.isBlank() && !hasImages && !hasFiles) return false
            if (hasImages && !imageInputAvailable) {
                state.attachError = "当前模型未开启识图：设置 → 模型与参数配置 → 「模型支持识图」打开后再发"
                return false
            }
            return send(text)
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
        val skill = skillsUi.firstOrNull { it.name == command.name && it.userInvocable && it.enabled }
            // 进程刚起来时 skillsUi 可能还没扫完（调试通道 dsh_send 会抢在 init 的刷新之前）：
            // 斜杠行不是已知命令时做一次同步快照，避免把合法技能误判成未知命令。
            ?: runCatching {
                skills.snapshot().skills.firstOrNull {
                    it.name == command.name && it.userInvocable && it.enabled
                }
            }.getOrNull()
        if (skill != null) return send(text)
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

    override fun dismissCommandNotice() {
        chat?.commandNotice = null
    }

    /** `@file` 候选（IO；查询在光标处每次变化时调用）。 */
    override suspend fun fileCandidates(query: String): List<FileCandidate> = withContext(Dispatchers.IO) {
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

    /**
     * M85-E：发送前的角色安全检查。
     *
     * - 没有可用存储（内置被停用且无插件）：存储安全提示，拦下发送；
     * - 只读存储插件：只能浏览，拦下发送；
     * - 没有可用提示词（内置被停用且无插件）：提示词安全提示，拦下发送。
     *
     * 模型通道不可用不在这里拦：它照常进一轮，由 [llmStream] 用 `LlmEvent.Failed` 如实报错。
     */
    private fun roleSafetyError(): String? {
        if (activeStorage == null) {
            return "存储安全提示：" + (storageSafetyNotice ?: "没有可用的会话存储。")
        }
        if (repo.readOnly) {
            return "存储安全提示：只读存储插件只能浏览导出的会话，不能新建或发送。"
        }
        if (activePromptState == null) {
            return "提示词安全提示：" + (promptSafetyNotice ?: "没有可用的系统提示词。")
        }
        return null
    }

    fun send(text: String): Boolean {
        val trimmed = text.trim()
        val state = chat ?: return false
        val live = current ?: return false
        if (state.readOnly || state.stopping || !live.acceptingInputs) return false
        if (live.queueWorkspace != workspace.absolutePath || live.queueStorageId != activeStorage?.storageId) {
            state.attachError = "工作区或存储已切换，请重新打开会话后发送"
            return false
        }
        inputReadinessError(state)?.let { reason ->
            state.attachError = reason
            return false
        }
        state.attachError = null
        val images = state.pendingImages.map { it.ref }
        val files = state.pendingFiles.toList()
        if (trimmed.isEmpty() && images.isEmpty() && files.isEmpty()) return false
        if (images.isNotEmpty() && !imageInputAvailable) {
            state.attachError = "当前模型未开启识图：设置 → 模型与参数配置 → 「模型支持识图」打开后再发"
            return false
        }
        if (activeAgent?.builtin == false && (images.isNotEmpty() || files.isNotEmpty())) {
            state.attachError = "当前回合插件只支持纯文本，请切回内置回合后发送附件"
            return false
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
        val input = TurnInput(trimmed, textBlocks = if (files.isEmpty()) emptyList() else blocks, images = images)
        val accepted = if (live.job?.isCompleted == false || state.streaming) {
            val owner = queueOwner(live)
            if (owner == null || live.handle == null) {
                state.queueNotice = "会话正在创建，请稍后再次发送；草稿已保留"
                false
            } else if (live.stop.requested) {
                state.queueNotice = "上一轮正在收尾，草稿已保留"
                false
            } else try {
                val jsQueue = !live.runningAgentBuiltin
                val delivery = if (!jsQueue && settings.sendWhileRunningMode == "steer")
                    xin.dshaa.core.session.PromptDelivery.STEER else xin.dshaa.core.session.PromptDelivery.QUEUE
                promptQueue.enqueue(owner, xin.dshaa.core.session.PromptPayload(input.text, input.textBlocks, input.images), delivery)
                refreshPromptQueue(live)
                state.queueNotice = if (jsQueue && settings.sendWhileRunningMode == "steer")
                    "当前回合插件只支持排队，已保存为排队消息" else "已保存，等待${if (delivery == xin.dshaa.core.session.PromptDelivery.STEER) "安全边界交付" else "当前任务完成"}"
                true
            } catch (e: Exception) {
                state.queueNotice = "消息未保存：${e.message}；草稿已保留"
                false
            }
        } else startTurn(state, input)
        if (accepted) {
            state.pendingImages.removeAll { it.ref in images }
            state.pendingFiles.removeAll(files.toSet())
        }
        return accepted
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
            bumpSchedulesRevision()
            live.pendingSchedules.addLast(task.prompt)
        }
        return live.pendingSchedules.removeFirst()
    }

    /** 当前会话的提醒（聊天页「提醒」面板打开时重新读）。 */
    override fun currentSchedules(): List<ScheduledTask> {
        // 读一次版本号：Compose 的壳绑定靠这个快照状态在提醒增删后重组（见 schedulesRevision）。
        if (schedulesRevision < 0) return emptyList()
        return chat?.sessionId?.let { schedules.listForSession(it) } ?: emptyList()
    }

    /** 用户手动删除一条提醒。 */
    override fun deleteSchedule(id: String) {
        val sessionId = chat?.sessionId ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { schedules.delete(id, sessionId) } }
            bumpSchedulesRevision()
        }
    }

    // ---------------- /goal 面板：用户侧的暂停/恢复/编辑/清空 ----------------

    override suspend fun pauseGoal(): String? {
        val error = mutateGoal { store, view -> store.pauseAsUser(view.goal.id, view.goal.revision) }
        // 暂停的是当前会话的目标：只中止当前会话自己的轮次，不能误停后台别的会话
        val live = current
        if (error == null && live?.state?.streaming == true) {
            live.stop.requested = true
            live.job?.cancel()
        }
        return error
    }

    override suspend fun resumeGoal(): String? {
        val error = mutateGoal { store, view -> store.resumeAsUser(view.goal.id, view.goal.revision) }
        // 上游 goal/changed 会触发 driver：idle 点 resume 应当立刻起下一轮
        if (error == null) kickGoalIfIdle()
        return error
    }

    override suspend fun editGoal(objective: String?, maxGoalRounds: Int?): String? {
        val error = mutateGoal { store, view -> store.editAsUser(view.goal.id, view.goal.revision, objective, maxGoalRounds) }
        if (error == null) kickGoalIfIdle()
        return error
    }

    override suspend fun clearGoal(): String? = mutateGoal { store, view ->
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

    override fun stop() {
        // 2026-11-12（用户：长任务干一半自己停）：先把「谁按了停止」记下来。以前 stop() 没有任何日志，
        // 只能看到 turn/end aborted，分不清是用户点了停止、悬浮窗关了，还是 Activity 被销毁。
        Log.i(
            "DshTurn",
            "stop requested: " + Throwable().stackTrace.drop(1).take(8).joinToString(" <- ") {
                "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
            },
        )
        // 停止当前前台这条（它正在跑）；用户看着一条没跑的会话时，停止最近启动的那条。
        val live = current?.takeIf { it in activeTurns } ?: lastActiveTurn ?: current ?: return
        // 2026-11-12（用户 23:00：轮次早就「已完成」，左下角还挂着「正在停止…」）：
        // 没有在跑的轮次时按「停止」只是在清状态，不能写「正在停止」——引擎的收尾不会再跑，
        // 1.5 秒兜底又只在「还占着运行集合」时才清，这行字就会永久留在界面上。
        pausePromptQueue(live, "已停止，待发送消息已保留")
        if (live.job?.isCompleted != false && !live.state.streaming) {
            live.state.stopping = false
            live.state.statusText = null
            return
        }
        // 用户主动 interrupt：取消后由引擎的收尾逻辑决定 pause（goal 轮）还是 disarm。
        // M85-D：JS 回合插件通过 ctx.abort() 读这个位；dispatch 阻塞时也能尽快让插件自己收尾。
        pluginAgentAbort = true
        // streaming 由取消收尾统一置 false：先写完 turn/end 再允许下一次发送，
        // 否则用户立刻再发一条会与取消收尾并发写同一个会话文件。
        live.stop.requested = true
        live.job?.cancel()
        live.state.apply {
            // M79：立刻给用户「已收到停止」的反馈（按钮切「停止中…」、状态条换文案），
            // 真正的收尾（写 turn/end + 补中断 tool_result）在引擎 finally 里完成。
            stopping = true
            statusText = "正在停止…"
            // 最后一条 assistant 消息也要结束流式态，否则思考块会一直显示「思考中」
            val lastIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
            if (lastIndex >= 0) messages[lastIndex] = messages[lastIndex].copy(streaming = false)
        }
        // runningStates / activeTurns 不在这里清：等引擎的取消收尾（finally）统一清，
        // 否则 keep-alive 会在 turn/end 还没写完时先停掉（写一半的会话最怕进程被冻）。
        //
        // 兜底（用户 2026-11-11 反馈「停掉了还在运行、发消息没反应、开新话题被挡住」）：
        // 取消收尾可能卡在阻塞的工具/网络调用里永远等不到 finally。1.5 秒后若这条轮次
        // 还占着运行集合 / streaming，就强制清 UI 状态，至少让用户能继续发消息。
        val stopped = live
        viewModelScope.launch {
            delay(1500)
            val stillHeld = stopped in activeTurns || stopped.state.streaming
            if (stillHeld) {
                forceStopUi(stopped, "stop timeout")
            } else if (stopped.state.stopping) {
                // 竞态：点停止的同一瞬间轮次刚好正常收尾——引擎的 finally 已经跑过，
                // 不会再来清「正在停止…」。这里静默清掉，不当成「收尾超时」误报。
                stopped.state.stopping = false
                if (stopped.state.statusText == "正在停止…") stopped.state.statusText = null
            }
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
        // UI timeout is not writer completion: retain the job and active-turn fence.
        if (live.job?.isCompleted != false) markStopped(live)
        live.state.streaming = false
        live.state.stopping = live.job?.isCompleted == false
        live.state.statusText = null
        val lastIndex = live.state.messages.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (lastIndex >= 0) live.state.messages[lastIndex] = live.state.messages[lastIndex].copy(streaming = false)
        live.state.commandNotice = "上一轮仍在收尾，待发送消息已保留；收尾完成后可继续。"
        syncKeepAlive()
    }

    /** 内部取消（不带用户 interrupt 语义）：换工作区/删会话时用。 */
    private fun cancelStream() {
        val live = current ?: return
        live.stop.requested = false
        live.job?.cancel()
        pausePromptQueue(live, "会话已中断，待发送消息已保留")
        live.state.stopping = live.job?.isCompleted == false
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
            // M76/M85-B2-2：起点在 startTurn 已经写好（含首包前的等待），这里不再重置。
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

        override fun onRequestUsage(update: xin.dshaa.core.engine.RequestUsage) {
            val now = System.currentTimeMillis()
            val status = update.status.name.lowercase()
            if (update.status == xin.dshaa.core.engine.RequestUsageStatus.STARTED) {
                state.contextUsage?.let { previous ->
                    state.contextHistory.add(previous)
                    while (state.contextHistory.size > 30) state.contextHistory.removeAt(0)
                }
                state.contextUsage = xin.dshaa.core.model.ContextUsage(
                    requestNo = update.requestNo, status = status, startedAt = now, updatedAt = now,
                )
            } else {
                val previous = state.contextUsage?.takeIf { it.requestNo == update.requestNo } ?: return
                state.contextUsage = previous.copy(
                    status = status, inputTokens = update.usage.inputTokens, outputTokens = update.usage.outputTokens,
                    cacheReadTokens = update.usage.cacheReadInputTokens,
                    cacheCreationTokens = update.usage.cacheCreationInputTokens, updatedAt = now,
                )
            }
            if (update.status != xin.dshaa.core.engine.RequestUsageStatus.STREAMING) {
                Log.i("DshContext", "request=${update.requestNo} status=$status input=${update.usage.inputTokens} " +
                    "output=${update.usage.outputTokens} cacheRead=${update.usage.cacheReadInputTokens} " +
                    "cacheCreation=${update.usage.cacheCreationInputTokens}")
            }
        }

        /** 奖励统计仍按成功 step 结算；上下文显示走同请求的 nullable 快照。 */
        override fun setTokenUsage(inputTokens: Int, outputTokens: Int) {
            BigFish.onTokenUsage(state, inputTokens, outputTokens)
        }

        private fun invalidateContext(reason: String) {
            state.contextInvalidationReason = reason
            state.contextChanges.add("${System.currentTimeMillis()}: $reason")
            while (state.contextChanges.size > 30) state.contextChanges.removeAt(0)
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
            invalidateContext("历史已压缩，等待下一请求用量")
        }

        /**
         * 工具结果修剪（M46）：被剪的那条 tool 消息**原地**换掉（seq 指向新的 `tool/result` 事件），
         * 不挪位置 —— 请求体里工具结果必须紧跟它的调用。
         */
        override fun applyToolResultPrune(originalSeq: Long, replacement: ChatMessage) {
            val index = state.messages.indexOfFirst { it.seq == originalSeq }
            if (index >= 0) {
                state.messages[index] = replacement
                invalidateContext("工具结果已修剪，等待下一请求用量")
            }
        }

        /**
         * 图片卸载（M47）：只给选中的 image 块补 `offloaded` 标记，消息与顺序不动；
         * 下一次请求组装会把这几张图换成占位文本。
         */
        override fun applyImageOffload(targets: List<ImageOffloadTarget>) {
            if (targets.isNotEmpty()) invalidateContext("图片已卸载，等待下一请求用量")
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
    private fun startTurn(state: ChatSessionState, input: TurnInput): Boolean {
        // 并行（M77）：每条会话都可以有自己的在跑轮次；这里按 state 找它自己的 LiveChat，
        // 不再要求「必须是当前前台会话」（提醒/通知可以在后台会话上起一轮）。
        val live = lives[state] ?: current?.takeIf { it.state === state } ?: return false
        val otherWriter = activeTurns.any { it !== live && it.state.sessionId != null &&
            it.state.sessionId == state.sessionId && it.job?.isCompleted == false }
        if (!live.acceptingInputs || live.job?.isCompleted == false || live.state.streaming || otherWriter) {
            state.attachError = "上一轮仍在运行或收尾，请稍后发送"
            return false
        }
        inputReadinessError(state)?.let { state.attachError = it; return false }
        // M85-D：每次发消息前重新解析回合插件（`dsh_agent` 改了就立刻生效）。
        // 选不中 = 安全提示（插件停用/缺授权/选了不存在的），不崩溃、也不悄悄退回内置引擎。
        refreshAgentResolution()
        val turnAgent = activeAgent
        if (turnAgent == null) {
            val reason = agentSafetyNotice ?: "没有可用的回合插件"
            state.attachError = "回合安全提示：$reason 可在插件页重新打开 builtin.agent.dsh，或点顶部「恢复内置回合」。"
            Log.w("DshAgent", "turn blocked session=${state.sessionId ?: "-"}: $reason")
            return false
        }
        // agent/v1 v1 的第三方实现只处理纯文本：图片/附件/后台通知轮仍由内置 dsH 引擎处理。
        if (!turnAgent.builtin && (input.images.isNotEmpty() || input.textBlocks.isNotEmpty() || input.wake)) {
            state.attachError =
                "回合插件 ${turnAgent.label} 只支持纯文本输入：附件/图片/后台通知轮请切回内置 dsH 引擎"
            Log.w("DshAgent", "turn blocked (rich input) agent=${turnAgent.agentId}")
            return false
        }
        pluginAgentAbort = false
        // fork 的 seed 边界：本轮开始前的消息 = 已完成的轮次（当前轮不算）
        state.turnStartIndex = state.messages.size
        // 模型可能刚被改过：工具上下文里的图片能力门按当前设置重新绑定（read_image 的准入）
        live.toolContext.imageRoute = currentImageRoute()
        // 保活：turn 跑着的时候挂前台服务（+ 唤醒锁），切后台/锁屏不被冻/回收（docs/48）
        TurnKeepAliveService.stopListener = { stop() }
        startKeepAlive("对话进行中 · 切后台也会继续跑")
        Log.i(
            "DshTurn",
            "turn start session=${state.sessionId ?: "-"} foreground=${state === chat} model=${settings.model} " +
                "agent=${turnAgent.pluginId ?: "builtin"}:${turnAgent.agentId}",
        )
        val startedAt = System.currentTimeMillis()
        // M85-B2-2：耗时从真正的 turn 起点算（不是等第一次流式回调），与 DshTurn 日志一致
        state.turnStartedAt = startedAt
        state.lastTurnDurationMs = 0L
        markRunning(live)
        state.streaming = true
        live.stop.requested = false
        live.runningAgentBuiltin = turnAgent.builtin
        var completedNormally = false
        val turnJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                if (turnAgent.builtin) {
                    val outcome = live.engine.startTurn(ComposeTurnUi(state), input)
                    completedNormally = outcome == TurnOutcome.COMPLETED
                    if (!completedNormally) pausePromptQueue(live, state.error ?: "本轮未正常完成，待发送消息已保留")
                } else {
                    var next: TurnInput? = input
                    while (next != null) {
                        runPluginAgentTurn(live, state, turnAgent, next)
                        if (state.error != null || live.stop.requested) {
                            pausePromptQueue(live, state.error ?: "已停止，待发送消息已保留")
                            break
                        }
                        next = claimPrompt(live)
                    }
                    completedNormally = state.error == null && !live.stop.requested
                }
            } catch (e: CancellationException) {
                pausePromptQueue(live, "已停止，待发送消息已保留")
                throw e
            } catch (e: Exception) {
                state.error = e.message ?: "对话执行失败"
                pausePromptQueue(live, state.error ?: "对话执行失败")
                Log.e("DshTurn", "turn failed", e)
            } finally {
                state.streaming = false
                state.stopping = false
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
                // Keep the actual writer job until invokeOnCompletion, including cancelled cleanup.
                val durationMs = System.currentTimeMillis() - startedAt
                state.lastTurnDurationMs = durationMs.coerceAtLeast(0)
                Log.i("DshTurn", "turn end after ${durationMs}ms")
                // 空正文轮次保留日志，便于排查模型只回思考/空内容的情况。
                if (!state.stopping && state.error == null) {
                    val lastAssistant = state.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
                    val hasText = lastAssistant?.content
                        ?.any { it.type == BlockType.TEXT && it.text.isNotBlank() } == true
                    val hasToolCall = lastAssistant?.content?.any { it.type == BlockType.TOOL_CALL } == true
                    if (lastAssistant != null && !hasText && !hasToolCall) {
                        Log.w("DshTurn", "turn ended without assistant text session=${state.sessionId}")
                    }
                }
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
                syncKeepAlive()
            }
        }
        live.job = turnJob
        turnJob.invokeOnCompletion {
            viewModelScope.launch {
                if (live.job !== turnJob) return@launch
                live.job = null
                markStopped(live)
                if (lives[state] === live && live.acceptingInputs && completedNormally && !live.stop.requested) {
                    try {
                        val queued = claimPrompt(live)
                        if (queued != null) {
                            if (!startTurn(state, queued)) {
                                recoverPromptQueue(live)
                                pausePromptQueue(live, state.attachError ?: "当前不能发送")
                            }
                        } else maybeDeliverNoticesFor(state)
                    } catch (e: Exception) { pausePromptQueue(live, "队列交付失败：${e.message}") }
                }
                syncKeepAlive()
            }
        }
        turnJob.start()
        return true
    }

    /**
     * M85-D：跑一轮**第三方 JS 回合插件**（agent/v1）。
     *
     * 边界（v1，写清避免夸大）：
     * - 只支持纯文本轮：宿主负责落 `turn/start → step/start → user/message → assistant/message →
     *   step/end → turn/end` 与会话文件（和内置引擎同一种事件格式，所以切回内置引擎不丢数据）；
     * - 插件只拿到 `{turnId, sessionId, input, state}`，通过 `ctx.notify/status/abort/tools.names`
     *   回调宿主；`tools.invoke` / `llm.stream` / `storage.append` 在 v1 明确返回 UNSUPPORTED；
     * - 没有流式：插件的返回值 `{stopReason, text}` 就是这一轮的结果（echo agent 用它演示「回合可换」）。
     */
    private suspend fun runPluginAgentTurn(
        live: LiveChat,
        state: ChatSessionState,
        agent: ResolvedAgent,
        input: TurnInput,
    ) {
        val pluginId = agent.pluginId ?: run {
            state.error = "回合插件缺少插件 id"
            return
        }
        val session = ensureTurnSession(live) ?: run {
            state.error = "无法创建会话"
            return
        }
        state.sessionId = session.header.id
        if (state.title == "新会话" && input.text.isNotBlank()) state.title = input.text.take(18)
        state.streaming = true
        state.statusText = "回合插件：${agent.label}"
        pluginAgentState = state
        state.error = null
        var pluginTurnOpen = false
        try {
            val (turnNo, userSeq) = withContext(Dispatchers.IO) {
                session.appendRequestHeader(
                    provider = providerLabel(),
                    model = settings.model,
                    reasoningEffort = settings.reasoningEffort,
                    maxTokens = settings.maxTokens,
                )
                val turn = session.startTurn()
                session.startStep()
                pluginTurnOpen = true
                val source = input.promptId?.let { id -> buildJsonObject {
                    put("kind", "user"); put("promptId", id); put("delivery", input.delivery)
                } }
                val seq = session.appendUserMessage(input.text, source)
                turn to seq
            }
            state.messages += ChatMessage(
                id = java.util.UUID.randomUUID().toString(),
                role = MessageRole.USER,
                content = listOf(ContentBlock(BlockType.TEXT, text = input.text)),
                sourceKind = "user",
                seq = userSeq,
            )
            input.promptId?.let { ackPrompt(live, it, userSeq) }
            check(pluginResidentRuntime.isResident(pluginId)) { "当前回合插件尚未就绪：$pluginId" }
            val payload = buildJsonObject {
                put("id", agent.agentId)
                put(
                    "turn",
                    buildJsonObject {
                        put("turnId", "$pluginId#${session.header.id}#$turnNo")
                        put("sessionId", session.header.id)
                        put("input", input.text)
                        put("state", buildJsonObject {
                            put("sessionId", session.header.id)
                            put("title", state.title)
                            put("streaming", state.streaming)
                            put("messageCount", state.messages.size)
                        })
                    },
                )
            }.toString()
            val outcome = withContext(Dispatchers.IO) {
                pluginResidentRuntime.dispatch(pluginId, AGENT_RUN_METHOD, payload, AGENT_TURN_TIMEOUT_MS)
            }
            val reply = parseAgentReply(outcome)
            val text = reply?.text.orEmpty()
            val error = when {
                reply == null -> describeAgentFailure(outcome)
                reply.stopReason == "max_tokens" -> "达到 max_tokens 上限，待发送消息已保留"
                text.isBlank() -> "回合插件 ${agent.label} 没有返回正文"
                else -> null
            }
            val assistantSeq = withContext(Dispatchers.IO) {
                session.appendAssistantMessage(
                    text,
                    provider = providerLabel(),
                    model = settings.model,
                )
            }
            state.messages += ChatMessage(
                id = java.util.UUID.randomUUID().toString(),
                role = MessageRole.ASSISTANT,
                content = if (text.isBlank()) emptyList() else listOf(ContentBlock(BlockType.TEXT, text = text)),
                error = error,
                seq = assistantSeq,
            )
            if (error != null) state.error = error
            withContext(Dispatchers.IO) {
                session.endStep()
                session.endTurn(
                    when {
                        reply == null -> "error"
                        pluginAgentAbort -> "aborted"
                        else -> "completed"
                    },
                )
            }
            pluginTurnOpen = false
            Log.i(
                "DshAgent",
                "plugin turn done agent=${agent.agentId} plugin=$pluginId session=${session.header.id} " +
                    "turn=$turnNo chars=${text.length} stop=${reply?.stopReason ?: "error"} error=${error ?: "-"}",
            )
        } finally {
            if (pluginTurnOpen) withContext(NonCancellable + Dispatchers.IO) {
                session.endStep()
                session.endTurn(if (live.stop.requested) "aborted" else "error")
            }
            pluginAgentState = null
            state.streaming = false
            if (state.statusText?.startsWith("回合插件") == true) state.statusText = null
        }
    }

    /** 本轮没有会话文件时建一个（与内置引擎 `SessionGateway.create` 的落库形状一致）。 */
    private suspend fun ensureTurnSession(live: LiveChat): SessionSession? {
        live.handle?.let { return it }
        val created = withContext(Dispatchers.IO) {
            repo.create(cwd = workspace.absolutePath, model = settings.model)
        }
        runCatching {
            created.appendRequestHeader(
                provider = providerLabel(),
                model = settings.model,
                reasoningEffort = settings.reasoningEffort,
                maxTokens = settings.maxTokens,
            )
        }.onFailure { Log.w("DshAgent", "append request header failed: ${it.message}") }
        live.handle = created
        refreshSessions()
        Log.i("DshAgent", "created session ${created.header.id} for plugin agent turn")
        return created
    }

    private data class PluginAgentReply(val stopReason: String, val text: String?)

    /** 解析 `agent.run` 的 dispatch 结果；失败返回 null（原因见 [describeAgentFailure]）。 */
    private fun parseAgentReply(outcome: JsOutcome?): PluginAgentReply? {
        if (outcome == null || outcome.status != JsStatus.COMPLETED) return null
        val obj = runCatching { Json.parseToJsonElement(outcome.valueJson ?: "null").jsonObject }.getOrNull()
            ?: return null
        val ok = runCatching { obj["ok"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() }.getOrNull()
        if (ok != true) return null
        val stopReason = obj["stopReason"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "completed"
        val text = obj["text"]?.jsonPrimitive?.contentOrNull
        return PluginAgentReply(stopReason, text)
    }

    private fun describeAgentFailure(outcome: JsOutcome?): String {
        if (outcome == null) return "回合插件常驻运行时没有就绪（插件已停用或启动失败）"
        if (outcome.status != JsStatus.COMPLETED) {
            return "回合插件运行超时/中断：${outcome.error ?: outcome.status}"
        }
        val obj = runCatching { Json.parseToJsonElement(outcome.valueJson ?: "null").jsonObject }.getOrNull()
        return obj?.get("error")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?: "回合插件没有返回 {stopReason,text}"
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
            TurnKeepAliveService.setLocalBusy(getApplication(), true)
            startKeepAlive(
                when {
                    turnRunning -> "对话进行中 · 切后台也会继续跑"
                    jobsRunning -> "后台任务进行中"
                    else -> "子代理运行中"
                },
            )
        } else {
            // M83：本地空闲时不能直接 stop——远程 DSH 的 turn 可能还在跑（同一个前台服务）
            TurnKeepAliveService.setLocalBusy(getApplication(), false)
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

    override fun refreshPlugins() {
        pluginsUi = pluginStore.list()
        syncPluginTools()
    }

    /**
     * M84-K/M85-C3-4：插件注册的**整页路由**（启用中 + 有 `dsh.client` + `page:true`）。
     *
     * C3-4 起不再排除与内置页同名的 route：`page/v1` 注册表
     * （[xin.dshaa.ui.shellapi.ShellPageRegistry]）把同 id 项标记为「覆盖内置页」，
     * 抽屉只列独立插件页（M84-K 语义不变）；[PluginPageEntry.target] 带上 record+route，
     * 壳的 dispatch 直接交给宿主页面槽渲染。
     */
    override fun pluginPageEntries(): List<PluginPageEntry> = pluginsUi.asSequence()
        .filter { it.enabled && it.clientUi }
        .flatMap { record ->
            record.routes.asSequence()
                .filter { it.page }
                .map { route ->
                    PluginPageEntry(
                        pluginId = record.id,
                        pluginName = record.name,
                        routeId = route.id,
                        title = route.title,
                        target = PluginPageTarget(record, route),
                    )
                }
        }
        .distinctBy { it.routeId }
        .toList()

    /** M84-K：从抽屉打开插件的整页（插件自己的 dsh.client）。 */
    override fun openPluginPageEntry(entry: PluginPageEntry) {
        val record = pluginsUi.firstOrNull { it.id == entry.pluginId } ?: return
        xin.dshaa.nativeapp.pluginui.PluginPageActivity.open(getApplication(), record, entry.routeId)
    }

    fun runPluginDevSelfTest(path: String) {
        if (!xin.dshaa.nativeapp.BuildConfig.DEBUG) return
        viewModelScope.launch {
            val dir = File(workspace, "m86-plugin-dev-${System.currentTimeMillis()}")
            xin.dshaa.nativeapp.data.PluginDevSelfTest.run(dir, toolContext,
                invoke = { name, args -> registry.execute(name, args, toolContext) },
                writeReport = { text -> File(path).apply { parentFile?.mkdirs() }.writeText(text, Charsets.UTF_8) },
            )
        }
    }

    private var deviceProbeJob: Job? = null

    fun runPromptQueueDeviceProbe(path: String) {
        if (!xin.dshaa.nativeapp.BuildConfig.DEBUG || deviceProbeJob?.isCompleted == false) return
        deviceProbeJob = viewModelScope.launch {
            if (activeTurns.isNotEmpty() || current?.job?.isCompleted == false ||
                activeAgent?.builtin != true || activeStorage?.storageId != BUILTIN_STORAGE_ID || activePromptState?.builtin != true) {
                File(path).writeText("""{"running":false,"passed":false,"reason":"需要空闲的内置可写回合、存储和提示词"}""")
                return@launch
            }
            val previous = current
            val previousStates = lives.keys.toSet()
            val previousBackStack = chatBackStack.toList()
            val providerPref = prefs.getString("debug_llm_provider", null)
            val previousProvider = LlmProviders.activeId
            LlmProviders.register(DebugM86QueueProvider())
            applyDebugProviders(DebugM86QueueProvider.ID, null)
            try {
                File(path).apply { parentFile?.mkdirs() }.writeText("""{"running":true}""")
                PromptQueueDeviceProbe.run(this@AppViewModel, session = { current?.handle }, queueStore = promptQueue,
                    context = getApplication(), fixtureDir = File(getApplication<Application>().filesDir, "m86g-fixtures"),
                    activeTurnCount = { activeTurns.size },
                    awaitIdle = { kotlinx.coroutines.withTimeout(20_000) {
                        while (activeTurns.isNotEmpty() || lives.values.any { it.job?.isCompleted == false }) delay(20)
                    } },
                    dropAndReopen = { id ->
                        check(activeTurns.isEmpty())
                        val old = current ?: error("fixture live missing")
                        check(old.state.sessionId == id && old.job?.isCompleted != false)
                        lives.remove(old.state)
                        attach(null)
                        openSessionById(id)
                    },
                    writeReport = { report -> withContext(Dispatchers.IO) { File(path).writeText(report.toString(), Charsets.UTF_8) } },
                )
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) {
                    val created = lives.filterKeys { it !in previousStates }.values.toList()
                    created.forEach { live -> live.stop.requested = true; live.job?.cancel() }
                    created.forEach { live -> live.job?.join(); lives.remove(live.state) }
                    prefs.edit().apply {
                        if (providerPref == null) remove("debug_llm_provider") else putString("debug_llm_provider", providerPref)
                    }.apply()
                    LlmProviders.activeId = previousProvider
                    LlmProviders.unregister(DebugM86QueueProvider.ID)
                    previous?.let { lives[it.state] = it }
                    attach(previous)
                    chatBackStack.clear(); previousBackStack.forEach { chatBackStack.addLast(it) }
                    refreshSessions()
                }
            }
        }
    }

    fun runRemoteConversationDeviceProbe(path: String) {
        if (!xin.dshaa.nativeapp.BuildConfig.DEBUG || deviceProbeJob?.isCompleted == false) return
        deviceProbeJob = viewModelScope.launch {
            if (activeTurns.isNotEmpty()) {
                File(path).writeText("""{"running":false,"passed":false,"reason":"需要所有本机回合空闲"}""")
                return@launch
            }
            val previous = current
            val previousRemote = conversation.remoteProfileId
            try {
                File(path).apply { parentFile?.mkdirs() }.writeText("""{"running":true}""")
                RemoteConversationDeviceProbe.run(this@AppViewModel,
                    File(getApplication<Application>().filesDir, "m86h-fixtures"),
                    writeReport = { text -> File(path).writeText(text, Charsets.UTF_8) },
                )
            } finally {
                conversation.selectTarget(previousRemote)
                previous?.let { lives[it.state] = it }; attach(previous)
            }
        }
    }

    fun runLoadingDevSelfTest(path: String) {
        if (!xin.dshaa.nativeapp.BuildConfig.DEBUG) return
        viewModelScope.launch {
            val directory = File(workspace, "m86-loading-dev-${System.currentTimeMillis()}")
            xin.dshaa.nativeapp.data.PluginLoadingDevSelfTest.run(directory, pluginStore,
                invoke = { name, args -> registry.execute(name, args, toolContext) },
                writeReport = { text -> File(path).apply { parentFile?.mkdirs() }.writeText(text, Charsets.UTF_8) },
            )
        }
    }

    /** Debug observations omit tokens, configuration and existing message content. */
    fun dumpLoading(path: String) {
        if (!xin.dshaa.nativeapp.BuildConfig.DEBUG) return
        val snapshot = loadingSnapshotStore.read()
        File(path).apply { parentFile?.mkdirs() }.writeText(buildJsonObject {
            put("selected", prefs.getString("dsh_loading", null) ?: xin.dshaa.nativeapp.data.BuiltinLoadingPlugin.ID)
            put("snapshotOwner", snapshot?.ownerPlugin ?: "")
            put("snapshotSelection", snapshot?.selection ?: "")
            put("revision", snapshot?.revision ?: "")
            put("notice", loadingSafetyNotice ?: "")
            put("targets", buildJsonArray { loadingTargetsUi.forEach { target -> add(buildJsonObject {
                put("value", target.selectionValue ?: ""); put("label", target.label); put("active", target.active)
                put("reason", target.unavailableReason ?: "")
            }) } })
        }.toString(), Charsets.UTF_8)
    }

    /** Debug UI acceptance observes global writers; message text is limited to M86 fixture tags. */
    fun dumpUiProbeState(path: String) {
        if (!xin.dshaa.nativeapp.BuildConfig.DEBUG) return
        val state = chat
        val live = current
        fun fixtureText(message: ChatMessage): String? = message.content
            .filter { it.type == xin.dshaa.core.model.BlockType.TEXT }.joinToString("") { it.text.orEmpty() }
            .takeIf { Regex("M86GUI_[A-Za-z0-9_]+(?: done)?").matches(it) }
        val fixture = state?.messages?.any { it.role == MessageRole.USER && fixtureText(it) != null } == true
        val payload = buildJsonObject {
            put("observedAt", System.currentTimeMillis())
            put("activeTurnCount", activeTurns.size)
            put("writerCount", lives.values.count { it.job?.isCompleted == false })
            put("streamingCount", lives.values.count { it.state.streaming || it.state.stopping })
            put("sessionId", state?.sessionId ?: "")
            put("streaming", state?.streaming == true); put("stopping", state?.stopping == true)
            put("shell", shellActive?.let { "${it.pluginId}#${it.shellId}" } ?: "native")
            put("sendMode", settings.sendWhileRunningMode)
            put("errorPresent", state?.error != null)
            put("hasDraft", state?.draft?.text?.isNotBlank() == true)
            put("pendingImageCount", state?.pendingImages?.size ?: 0)
            put("pendingFileCount", state?.pendingFiles?.size ?: 0)
            put("fixtureSession", fixture)
            put("turnEnds", buildJsonArray {
                if (fixture) live?.handle?.events?.filter { it.type == "turn/end" }?.forEach { event ->
                    val data = event.data as? JsonObject
                    val reason = data?.get("reason") as? JsonObject
                    add(reason?.get("kind")?.jsonPrimitive?.contentOrNull ?: "")
                }
            })
            if (fixture && state != null) {
                put("messages", buildJsonArray { state.messages.forEach { message ->
                    fixtureText(message)?.let { text -> add(buildJsonObject {
                        put("role", message.role.name.lowercase()); put("text", text)
                    }) }
                } })
                put("queued", buildJsonArray { state.queuedPrompts.forEach { entry -> add(buildJsonObject {
                    put("id", entry.id); put("delivery", entry.delivery.name.lowercase())
                    put("status", entry.status.name.lowercase()); put("paused", entry.pausedReason != null)
                    if (Regex("M86GUI_[A-Za-z0-9_]+").matches(entry.payload.text)) put("text", entry.payload.text)
                }) } })
                put("delivered", buildJsonArray {
                    live?.handle?.events?.filter { it.type == "user/message" }?.forEach { event ->
                        val data = event.data as? JsonObject
                        val source = ((data?.get("message") as? JsonObject) ?: data)?.get("source") as? JsonObject
                        source?.get("promptId")?.jsonPrimitive?.contentOrNull?.let { id -> add(id) }
                    }
                })
            }
        }
        File(path).apply { parentFile?.mkdirs() }.writeText(payload.toString(), Charsets.UTF_8)
    }

    /** 只记录用量和请求身份；不包含正文、请求头或凭据。 */
    fun dumpContextUsage(path: String) {
        val state = chat
        fun json(value: xin.dshaa.core.model.ContextUsage): org.json.JSONObject = org.json.JSONObject()
            .put("requestNo", value.requestNo).put("model", value.model).put("protocol", value.protocol)
            .put("window", value.window).put("status", value.status)
            .put("inputTokens", value.inputTokens ?: org.json.JSONObject.NULL)
            .put("outputTokens", value.outputTokens ?: org.json.JSONObject.NULL)
            .put("cacheReadTokens", value.cacheReadTokens ?: org.json.JSONObject.NULL)
            .put("cacheCreationTokens", value.cacheCreationTokens ?: org.json.JSONObject.NULL)
            .put("estimatedSystem", value.estimatedSystem).put("estimatedTools", value.estimatedTools)
            .put("estimatedMessages", value.estimatedMessages).put("imageCount", value.imageCount)
            .put("startedAt", value.startedAt).put("updatedAt", value.updatedAt)
        runCatching {
            val payload = org.json.JSONObject().put("sessionId", state?.sessionId ?: org.json.JSONObject.NULL)
                .put("current", state?.contextUsage?.let(::json) ?: org.json.JSONObject.NULL)
                .put("invalidationReason", state?.contextInvalidationReason ?: org.json.JSONObject.NULL)
                .put("history", org.json.JSONArray(state?.contextHistory?.map(::json).orEmpty()))
                .put("changes", org.json.JSONArray(state?.contextChanges?.toList().orEmpty()))
            File(path).apply { parentFile?.mkdirs() }.writeText(payload.toString(), Charsets.UTF_8)
        }.onFailure { Log.w("DshContext", "dump failed: ${it.message}") }
    }

    /**
     * M84-G：当前路由是否由插件提供整页。
     * 只看启用中 + 有 dsh.client + `page:true` 的 route；id 与宿主页/二级页同名 = 替换该页。
     */
    override fun pluginPageFor(pageId: String?, subId: String?): PluginPageTarget? {
        val ids = listOfNotNull(pageId, subId).filter { it.isNotBlank() }
        if (ids.isEmpty()) return null
        return pluginsUi.asSequence()
            .filter { it.enabled && it.clientUi }
            .firstNotNullOfOrNull { record ->
                record.routes.firstOrNull { it.page && it.id in ids }?.let { PluginPageTarget(record, it) }
            }
    }

    /**
     * M85-A 验收通道（仅 debug）：把所有插件壳 + 当前选择落盘。
     */
    fun dumpShells(dumpPath: String?) {
        if (dumpPath.isNullOrBlank()) return
        runCatching {
            val pref = prefs.getString("dsh_shell", null).orEmpty()
            val shells = org.json.JSONArray()
            pluginsUi.forEach { record ->
                record.shells.forEach { shell ->
                    shells.put(
                        org.json.JSONObject()
                            .put("plugin", record.id)
                            .put("pluginName", record.name)
                            .put("enabled", record.enabled)
                            .put("uiGranted", record.grantedPermissions.any { it.equals("ui", true) })
                            .put("id", shell.id)
                            .put("label", shell.label)
                            .put("backend", shell.backend)
                            .put("hasState", shell.hasState)
                            .put("hasAction", shell.hasAction)
                            .put("hasEvent", shell.hasEvent)
                            .put(
                                "active",
                                shellActive?.pluginId == record.id && shellActive?.shellId == shell.id,
                            ),
                    )
                }
            }
            val payload = org.json.JSONObject()
                .put("pref", pref)
                .put("active", shellActive?.let { "${it.pluginId}#${it.shellId}" } ?: "")
                // M85-C2：内置界面插件也是一个「壳」，有 id/pages/启用状态；`dsh_shell` 里
                // `native` 和它的插件 id（builtin.shell.native）等价。
                .put(
                    "native",
                    org.json.JSONObject()
                        .put("id", xin.dshaa.nativeapp.data.BuiltinPlugins.NATIVE_UI_SHELL)
                        .put("label", xin.dshaa.ui.shellapi.NativeShells.builtin()?.label ?: "")
                        .put("enabled", nativeUiEnabled())
                        .put("pages", xin.dshaa.ui.shellapi.NativeShells.pages().size)
                        .put("selected", shellActive == null)
                        .put("safeMode", shellActive == null && !nativeUiEnabled()),
                )
                .put("resident", org.json.JSONArray(pluginResidentRuntime.residencyIds()))
                .put("treeMounted", shellHost.tree != null)
                .put("treeError", shellHost.error ?: "")
                .put("shells", shells)
            val file = File(dumpPath)
            file.parentFile?.mkdirs()
            file.writeText(payload.toString())
            Log.i("DshShell", "dump shells active=${shellActive?.shellId} count=${shells.length()} -> $dumpPath")
        }.onFailure { Log.w("DshShell", "dump shells failed: ${it.message}") }
    }

    override val shellTargetsUi: List<xin.dshaa.ui.shellapi.RoleTargetUi>
        get() = buildList {
            val nativeEnabled = nativeUiEnabled()
            add(xin.dshaa.ui.shellapi.RoleTargetUi(
                role = "shell", implementationId = xin.dshaa.nativeapp.data.BuiltinPlugins.NATIVE_UI_SHELL,
                label = xin.dshaa.ui.shellapi.NativeShells.builtin()?.label ?: "原生界面",
                builtin = true, enabled = nativeEnabled, active = shellActive == null && nativeEnabled,
                selectionValue = "native", unavailableReason = if (nativeEnabled) null else "内置界面插件已停用",
            ))
            pluginsUi.forEach { record ->
                record.shells.forEach { shell ->
                    val permitted = record.grantedPermissions.any { it.equals("ui", true) }
                    add(xin.dshaa.ui.shellapi.RoleTargetUi(
                        role = "shell", implementationId = shell.id,
                        label = "${shell.label} · ${shell.backend}", pluginId = record.id,
                        provider = record.name, builtin = record.id == "builtin.shell.whale",
                        enabled = record.enabled, permitted = permitted,
                        active = shellActive?.pluginId == record.id && shellActive?.shellId == shell.id,
                        selectionValue = "${record.id}#${shell.id}",
                        unavailableReason = when {
                            !record.enabled -> "提供方插件已停用"
                            !permitted -> "缺少 ui 授权"
                            else -> null
                        },
                    ))
                }
            }
        }

    override fun selectShell(value: String): Boolean {
        val candidate = shellTargetsUi.firstOrNull { it.selectionValue == value }
        if (candidate == null || candidate.unavailableReason != null) {
            pluginNotice = candidate?.unavailableReason ?: "界面壳不存在"
            return false
        }
        setActiveShell(if (value == "native") null else resolveShellTarget(value) ?: return false)
        return true
    }

    override fun restoreBuiltinShell() {
        setBuiltinPluginEnabled(xin.dshaa.nativeapp.data.BuiltinPlugins.NATIVE_UI_SHELL, true)
        setActiveShell(null)
    }

    /** 全部可选的插件壳（启用 + ui 授权 + 声明了壳）。 */
    fun shellTargets(): List<PluginShellHost.Target> = pluginsUi.asSequence()
        .filter { record ->
            record.enabled && record.shells.isNotEmpty() &&
                record.grantedPermissions.any { it.equals("ui", true) }
        }
        .flatMap { record ->
            record.shells.asSequence().map { shell ->
                PluginShellHost.Target(record.id, shell.id, shell.label, record.name, shell.backend)
            }
        }
        .toList()

    /**
     * `dsh_shell` 调试项 → 具体壳。`native`/空/失效都返回 null（原生壳）。
     *
     * M85-C2：内置界面插件的 id（[xin.dshaa.nativeapp.data.BuiltinPlugins.NATIVE_UI_SHELL]）也是
     * **合法壳 id**，和 `native` 指向同一个后端——这样「壳 id」语义统一，插件停用时同样落到安全模式。
     *
     * M85-C5（同 id 接管）：如果某个启用中 + 有 `ui` 授权的第三方插件声明了**同一个壳 id**
     * （`builtin.shell.native`），`dsh_shell=builtin.shell.native` 就选中它（Surface/WebView 壳接管）；
     * 插件停用/失效后解析回 null，宿主自动回到内置 native 界面。`native` 仍然永远指向内置后端。
     */
    fun resolveShellTarget(value: String?): PluginShellHost.Target? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("native", true)) return null
        if (raw.equals(xin.dshaa.nativeapp.data.BuiltinPlugins.NATIVE_UI_SHELL, true)) {
            return pluginShellByShellId(xin.dshaa.nativeapp.data.BuiltinPlugins.NATIVE_UI_SHELL)
        }
        val pluginId = raw.substringBefore('#').trim()
        val wantedShellId = raw.substringAfter('#', "").trim()
        val record = pluginsUi.firstOrNull { it.id == pluginId && it.enabled } ?: return null
        if (record.grantedPermissions.none { it.equals("ui", true) }) return null
        val shell = if (wantedShellId.isNotEmpty()) {
            record.shells.firstOrNull { it.id == wantedShellId }
        } else {
            record.shells.firstOrNull()
        } ?: return null
        return PluginShellHost.Target(record.id, shell.id, shell.label, record.name, shell.backend)
    }

    /** 同 id 接管：在启用 + 有 ui 授权的插件里找声明了 [shellId] 的壳（先到先得）。 */
    private fun pluginShellByShellId(shellId: String): PluginShellHost.Target? = pluginsUi.asSequence()
        .filter { record ->
            record.enabled && record.shells.isNotEmpty() &&
                record.grantedPermissions.any { it.equals("ui", true) }
        }
        .firstNotNullOfOrNull { record ->
            record.shells.firstOrNull { it.id.equals(shellId, true) }
                ?.let { shell -> PluginShellHost.Target(record.id, shell.id, shell.label, record.name, shell.backend) }
        }

    /** 选插件壳 / 回原生壳（持久化到 `dsh_shell`；原生 = `native`）。 */
    fun setActiveShell(target: PluginShellHost.Target?) {
        // 同一个壳重复下发（热启 intent 重投/Activity 重建）不能把已 mount 的壳重置成空白：
        // reset 会让 WebView 保留旧画面、Compose 又回到 loading 层，看起来像壳没起来。
        if (target == shellActive) return
        shellHost.reset()
        shellActive = target
        val value = target?.let { "${it.pluginId}#${it.shellId}" } ?: "native"
        prefs.edit().putString("dsh_shell", value).apply()
        Log.i("DshShell", "set active=$value targets=${shellTargets().map { "${it.pluginId}#${it.shellId}" }}")
    }

    private fun fallbackFromShell(reason: String) {
        shellActive = null
        prefs.edit().putString("dsh_shell", "native").apply()
        Log.w("DshShell", "fallback to native: $reason")
        runCatching {
            android.widget.Toast.makeText(getApplication(), reason + "；已回到原生界面", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** 输入框草稿：Surface 的 textfield 直接写这里（不跨 JS）。 */
    fun setShellDraft(text: String) {
        val state = chat ?: return
        state.draft = androidx.compose.ui.text.input.TextFieldValue(text)
    }

    // ---- M85-D：回合插件（agent/v1） ----

    /** 正在跑的 JS 回合对应的会话状态（`ctx.status` 回调只改它，不误改前台别的会话）。 */
    @Volatile
    private var pluginAgentState: ChatSessionState? = null

    /** 全部候选（内置 + 导入插件声明；含停用/缺授权项，用于解释为什么选不中）。 */
    private fun agentCandidates(): List<AgentCandidate> {
        val out = ArrayList<AgentCandidate>()
        out += AgentCandidate(
            agentId = xin.dshaa.nativeapp.data.BuiltinPlugins.NATIVE_AGENT,
            label = "dsH 引擎（内置）",
            pluginId = null,
            builtin = true,
            enabled = builtinStore.isEnabled(xin.dshaa.nativeapp.data.BuiltinPlugins.NATIVE_AGENT),
            permitted = true,
        )
        pluginsUi.forEach { record ->
            record.agents.forEach { agent ->
                out += AgentCandidate(
                    agentId = agent.id,
                    label = agent.label,
                    pluginId = record.id,
                    builtin = false,
                    enabled = record.enabled,
                    permitted = record.grantedPermissions.any { it.equals("agent", true) },
                )
            }
        }
        return out
    }

    override val agentTargetsUi: List<AgentTargetUi>
        get() {
            val active = activeAgent
            return agentCandidates().map { candidate ->
                val reason = when {
                    !candidate.enabled -> "插件已停用"
                    !candidate.permitted -> "缺少 agent 授权"
                    else -> null
                }
                AgentTargetUi(
                    agentId = candidate.agentId,
                    label = candidate.label,
                    pluginId = candidate.pluginId,
                    provider = candidate.pluginId?.let { id ->
                        pluginsUi.firstOrNull { it.id == id }?.name ?: id
                    } ?: "内置",
                    builtin = candidate.builtin,
                    enabled = candidate.enabled,
                    permitted = candidate.permitted,
                    active = active != null &&
                        active.agentId == candidate.agentId &&
                        active.pluginId == candidate.pluginId,
                    unavailableReason = reason,
                )
            }
        }

    override val activeAgentUi: AgentTargetUi?
        get() {
            val agent = activeAgent ?: return null
            return agentTargetsUi.firstOrNull {
                it.agentId == agent.agentId && it.pluginId == agent.pluginId
            } ?: AgentTargetUi(
                agentId = agent.agentId,
                label = agent.label,
                pluginId = agent.pluginId,
                provider = agent.pluginId ?: "内置",
                builtin = agent.builtin,
                active = true,
            )
        }

    /**
     * 重新解析 `dsh_agent`：发消息前、插件开关变化、冷启动都会调。
     * 解析失败 = 安全提示（不悄悄退回内置实现），并记下具体原因。
     */
    private fun refreshAgentResolution() {
        val pref = prefs.getString("dsh_agent", null)
        val previous = activeAgent
        when (val resolution = AgentRouting.resolve(pref, agentCandidates())) {
            is AgentResolution.Active -> {
                activeAgent = resolution.agent
                agentSafetyNotice = null
                clearAgentErrorNotice()
                if (previous?.agentId != resolution.agent.agentId ||
                    previous?.pluginId != resolution.agent.pluginId
                ) {
                    Log.i(
                        "DshAgent",
                        "resolved active=${resolution.agent.pluginId ?: "builtin"}:${resolution.agent.agentId} pref=${pref ?: "-"}",
                    )
                }
            }

            is AgentResolution.Unavailable -> {
                activeAgent = null
                agentSafetyNotice = resolution.reason
                if (previous != null) {
                    Log.w("DshAgent", "agent unavailable: ${resolution.reason} pref=${pref ?: "-"}")
                }
            }
        }
    }

    override fun setActiveAgent(id: String): Boolean {
        return when (val resolution = AgentRouting.resolve(id, agentCandidates())) {
            is AgentResolution.Active -> {
                val pref = AgentRouting.prefOf(resolution.agent)
                prefs.edit().putString("dsh_agent", pref).apply()
                activeAgent = resolution.agent
                agentSafetyNotice = null
                clearAgentErrorNotice()
                pluginNotice = "回合插件：${resolution.agent.label}"
                Log.i("DshAgent", "set active=$pref label=${resolution.agent.label}")
                true
            }

            is AgentResolution.Unavailable -> {
                agentSafetyNotice = resolution.reason
                pluginNotice = resolution.reason
                Log.w("DshAgent", "set agent failed id=$id: ${resolution.reason}")
                false
            }
        }
    }

    override fun restoreBuiltinAgent() {
        // 安全提示的「一键恢复」：先把内置回合插件打开，再选中 native（明确内置实现，
        // 不给同 id 插件接管——与 dsh_shell=native 的语义一致）。
        builtinStore.setEnabled(xin.dshaa.nativeapp.data.BuiltinPlugins.NATIVE_AGENT, true)
        builtinPluginsUi = builtinStore.ui()
        prefs.edit().putString("dsh_agent", "native").apply()
        refreshAgentResolution()
        pluginNotice = activeAgent?.let { "已恢复内置回合：${it.label}" } ?: (agentSafetyNotice ?: "恢复失败")
        Log.i(
            "DshAgent",
            "restore builtin active=${activeAgent?.agentId ?: "-"} safety=${agentSafetyNotice ?: "-"}",
        )
    }

    /** `ctx.status(text)`：JS 回合插件改状态条（主线程，且只改它自己那条会话）。 */
    private fun setPluginAgentStatus(text: String?) {
        val target = pluginAgentState ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (target.streaming) target.statusText = text
        }
    }

    /** 恢复/切换回合后清掉「回合安全提示」那条 composer 错误（附件类错误不动）。 */
    private fun clearAgentErrorNotice() {
        val state = chat ?: return
        if (state.attachError?.startsWith("回合安全提示") == true) state.attachError = null
    }

    /** 调试通道 `--es dsh_agent_list <绝对路径>`：回合清单 + 当前解析 + 常驻登记落盘。 */
    override fun dumpAgents(dumpPath: String?) {
        if (dumpPath.isNullOrBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val pref = prefs.getString("dsh_agent", null).orEmpty()
                val active = activeAgent
                val candidates = org.json.JSONArray()
                agentTargetsUi.forEach { item ->
                    candidates.put(
                        org.json.JSONObject()
                            .put("agent", item.agentId)
                            .put("plugin", item.pluginId ?: "")
                            .put("label", item.label)
                            .put("provider", item.provider)
                            .put("builtin", item.builtin)
                            .put("enabled", item.enabled)
                            .put("permitted", item.permitted)
                            .put("active", item.active)
                            .put("unavailable", item.unavailableReason ?: ""),
                    )
                }
                val resident = org.json.JSONArray()
                pluginsUi.forEach { record ->
                    val ids = pluginResidentRuntime.agentsOf(record.id)
                    if (ids.isNotEmpty()) {
                        resident.put(
                            org.json.JSONObject()
                                .put("plugin", record.id)
                                .put("agents", org.json.JSONArray(ids)),
                        )
                    }
                }
                val payload = org.json.JSONObject()
                    .put("version", 1)
                    .put("contract", xin.dshaa.core.agent.AGENT_CONTRACT)
                    .put("pref", pref)
                    .put(
                        "active",
                        if (active == null) org.json.JSONObject() else org.json.JSONObject()
                            .put("agent", active.agentId)
                            .put("plugin", active.pluginId ?: "")
                            .put("label", active.label)
                            .put("builtin", active.builtin),
                    )
                    .put("safety", agentSafetyNotice ?: "")
                    .put(
                        "builtin",
                        org.json.JSONObject()
                            .put("id", xin.dshaa.nativeapp.data.BuiltinPlugins.NATIVE_AGENT)
                            .put("enabled", builtinStore.isEnabled(xin.dshaa.nativeapp.data.BuiltinPlugins.NATIVE_AGENT))
                            .put("label", "dsH 引擎（内置）"),
                    )
                    .put("candidates", candidates)
                    .put("resident", resident)
                val file = File(dumpPath)
                file.parentFile?.mkdirs()
                file.writeText(payload.toString())
                Log.i("DshAgent", "dump agents pref=$pref active=${active?.agentId} -> $dumpPath")
            }.onFailure { Log.w("DshAgent", "dump agents failed: ${it.message}") }
        }
    }

    // ---- M85-E：storage/v1 ----

    /** 全部存储候选：内置 JSONL + 启用/停用的插件 `ctx.storage.register`（含原因）。 */
    private fun storageCandidates(): List<StorageCandidate> {
        val out = ArrayList<StorageCandidate>()
        out += StorageCandidate(
            storageId = BUILTIN_STORAGE_ID,
            label = "内置 JSONL 会话",
            pluginId = null,
            builtin = true,
            enabled = builtinStore.isEnabled(xin.dshaa.nativeapp.data.BuiltinPlugins.NATIVE_STORAGE),
            readOnly = false,
        )
        pluginsUi.forEach { record ->
            record.storages.forEach { storage ->
                out += StorageCandidate(
                    storageId = storage.id,
                    label = storage.label,
                    pluginId = record.id,
                    builtin = false,
                    enabled = record.enabled,
                    permitted = record.grantedPermissions.any { it.equals("storage", true) },
                    readOnly = true,
                )
            }
        }
        return out
    }

    override val storageTargetsUi: List<RoleTargetUi>
        get() {
            val active = activeStorage
            return storageCandidates().map { candidate ->
                RoleTargetUi(
                    role = "storage",
                    implementationId = candidate.storageId,
                    label = candidate.label,
                    pluginId = candidate.pluginId,
                    provider = candidate.pluginId?.let { id -> pluginsUi.firstOrNull { it.id == id }?.name ?: id } ?: "内置",
                    builtin = candidate.builtin,
                    enabled = candidate.enabled,
                    permitted = candidate.permitted,
                    active = active != null && active.storageId == candidate.storageId &&
                        active.pluginId == candidate.pluginId,
                    readOnly = candidate.readOnly,
                    selectionValue = if (candidate.builtin) "native" else "${candidate.pluginId}#${candidate.storageId}",
                    unavailableReason = when {
                        !candidate.enabled -> "插件已停用"
                        !candidate.permitted -> "缺少 storage 授权"
                        else -> null
                    },
                )
            }
        }

    override val activeStorageUi: RoleTargetUi?
        get() = storageTargetsUi.firstOrNull { it.active }

    /**
     * 重新解析 `dsh_storage` 并把路由壳换绑到新存储。
     *
     * - debug 包 `debug_session_store`（M84-C2 调试通道）优先；
     * - 内置 / 第三方只读导出器按 [StorageRouting] 选择（同 id 第三方优先）；
     * - 选不中 ⇒ 路由壳置空 + 存储安全提示（聊天页横幅，发送被拦）。
     */
    private fun refreshStorageResolution() {
        val debugId = if (xin.dshaa.nativeapp.BuildConfig.DEBUG) {
            prefs.getString("debug_session_store", null)?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        if (debugId != null) {
            val store = SessionStores.create(sessionsRootDir, debugId)
            storageRouter.bind(store, null)
            activeStorage = ResolvedStorage(
                storageId = "debug:$debugId",
                label = "调试存储 $debugId",
                pluginId = null,
                builtin = true,
            )
            storageSafetyNotice = null
            Log.i("DshStorage", "debug store=$debugId active=${store.id}")
            return
        }
        val pref = prefs.getString("dsh_storage", null)
        when (val resolution = StorageRouting.resolve(pref, storageCandidates())) {
            is StorageResolution.Active -> {
                val storage = resolution.storage
                val pluginId = storage.pluginId
                val store: SessionStore = if (storage.builtin || pluginId.isNullOrBlank()) {
                    builtinSessionStore
                } else {
                    PluginSessionStore(
                        pluginId = pluginId,
                        storeId = storage.storageId,
                        label = storage.label,
                        dispatch = { id, method, payload, timeout ->
                            pluginResidentRuntime.dispatch(id, method, payload, timeout)
                        },
                        awaitReady = { pluginResidentRuntime.awaitSettled(3_000) },
                    )
                }
                storageRouter.bind(store, null)
                activeStorage = storage
                storageSafetyNotice = if (storage.readOnly) {
                    "只读存储插件：${storage.label}——只能打开/浏览导出的会话，不能新建或发送。"
                } else {
                    null
                }
                Log.i(
                    "DshStorage",
                    "resolved active=${storage.pluginId ?: "builtin"}:${storage.storageId} " +
                        "readOnly=${storage.readOnly} pref=${pref ?: "-"}",
                )
            }

            is StorageResolution.Unavailable -> {
                storageRouter.bind(null, resolution.reason)
                activeStorage = null
                storageSafetyNotice = resolution.reason
                Log.w("DshStorage", "storage unavailable: ${resolution.reason} pref=${pref ?: "-"}")
            }
        }
    }

    override fun setActiveStorage(id: String): Boolean {
        return when (val resolution = StorageRouting.resolve(id, storageCandidates())) {
            is StorageResolution.Active -> {
                prefs.edit().putString("dsh_storage", StorageRouting.prefOf(resolution.storage)).apply()
                refreshStorageResolution()
                pluginNotice = "存储插件：${resolution.storage.label}" +
                    if (resolution.storage.readOnly) "（只读，打开会话后只能浏览）" else ""
                Log.i("DshStorage", "set active=${StorageRouting.prefOf(resolution.storage)}")
                true
            }

            is StorageResolution.Unavailable -> {
                storageSafetyNotice = resolution.reason
                pluginNotice = resolution.reason
                Log.w("DshStorage", "set storage failed id=$id: ${resolution.reason}")
                false
            }
        }
    }

    override fun restoreBuiltinStorage() {
        builtinStore.setEnabled(xin.dshaa.nativeapp.data.BuiltinPlugins.NATIVE_STORAGE, true)
        builtinPluginsUi = builtinStore.ui()
        prefs.edit().putString("dsh_storage", "native").apply()
        refreshStorageResolution()
        refreshSessions()
        pluginNotice = "已恢复内置存储：builtin.storage.jsonl"
        Log.i("DshStorage", "restore builtin active=${activeStorage?.storageId}")
    }

    /**
     * M85-E：只读导出器的宿主导出原语（`ctx.storage.exportList/exportLoad`）。
     *
     * 只读 **内置** `builtin.storage.jsonl`（不读活动存储，避免递归）；列表返回摘要，
     * 单个会话返回原始 JSONL 文档（逐帧解压后的原文，交给插件原样转回）。
     */
    private fun exportBuiltinSessions(method: String, payloadJson: String): String {
        return try {
            if (method == "storage.exportList") {
                val sessions = buildJsonArray {
                    builtinSessionStore.list().forEach { summary ->
                        add(
                            buildJsonObject {
                                put("id", summary.id)
                                put("title", summary.title)
                                put("preview", summary.preview)
                                put("createdAt", summary.createdAt)
                                put("updatedAt", summary.updatedAt)
                                put("messageCount", summary.messageCount)
                                put("cwd", summary.cwd)
                                put("path", summary.path)
                                put("parentSession", summary.parentSession ?: "")
                                put("delegationDepth", summary.delegationDepth)
                                put("origin", summary.origin ?: "")
                                put("isSeeded", summary.isSeeded)
                            },
                        )
                    }
                }
                buildJsonObject {
                    put("ok", true)
                    put("sessions", sessions)
                }.toString()
            } else {
                val payload = runCatching { Json.parseToJsonElement(payloadJson).jsonObject }.getOrNull()
                val id = payload?.get("sessionId")?.jsonPrimitive?.contentOrNull.orEmpty()
                val file = builtinSessionStore.findFile(id)
                if (file == null || !file.isFile) {
                    buildJsonObject {
                        put("ok", false)
                        put("error", "内置存储里没有会话 $id")
                    }.toString()
                } else {
                    val doc = ZstdFrames.decompressAll(file.readBytes()).toString(Charsets.UTF_8)
                    if (doc.isBlank()) {
                        buildJsonObject { put("ok", false); put("error", "会话文件解压为空：$id") }.toString()
                    } else {
                        buildJsonObject { put("ok", true); put("doc", doc) }.toString()
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w("DshStorage", "export $method failed: ${t.message}")
            buildJsonObject {
                put("ok", false)
                put("error", "导出失败：${t.message ?: t.javaClass.simpleName}")
            }.toString()
        }
    }

    // ---- M85-E：prompt/v1 ----

    private fun promptCandidates(): List<PromptCandidate> {
        val out = ArrayList<PromptCandidate>()
        out += PromptCandidate(
            promptId = BUILTIN_PROMPT_ID,
            label = "dsH 系统提示词",
            pluginId = null,
            builtin = true,
            enabled = builtinStore.isEnabled(xin.dshaa.nativeapp.data.BuiltinPlugins.NATIVE_PROMPT),
        )
        pluginsUi.forEach { record ->
            record.prompts.forEach { prompt ->
                out += PromptCandidate(
                    promptId = prompt.id,
                    label = prompt.label,
                    pluginId = record.id,
                    builtin = false,
                    enabled = record.enabled,
                    permitted = record.grantedPermissions.any { it.equals("prompt", true) },
                )
            }
        }
        return out
    }

    override val promptTargetsUi: List<RoleTargetUi>
        get() {
            val active = activePromptState
            return promptCandidates().map { candidate ->
                RoleTargetUi(
                    role = "prompt",
                    implementationId = candidate.promptId,
                    label = candidate.label,
                    pluginId = candidate.pluginId,
                    provider = candidate.pluginId?.let { id -> pluginsUi.firstOrNull { it.id == id }?.name ?: id } ?: "内置",
                    builtin = candidate.builtin,
                    enabled = candidate.enabled,
                    permitted = candidate.permitted,
                    active = active != null && active.promptId == candidate.promptId &&
                        active.pluginId == candidate.pluginId,
                    selectionValue = if (candidate.builtin) "native" else "${candidate.pluginId}#${candidate.promptId}",
                    unavailableReason = when {
                        !candidate.enabled -> "插件已停用"
                        !candidate.permitted -> "缺少 prompt 授权"
                        else -> null
                    },
                )
            }
        }

    override val activePromptUi: RoleTargetUi?
        get() = promptTargetsUi.firstOrNull { it.active }

    private fun refreshPromptResolution() {
        val pref = prefs.getString("dsh_prompt", null)
        when (val resolution = PromptRouting.resolve(pref, promptCandidates())) {
            is PromptResolution.Active -> {
                activePromptState = resolution.prompt
                promptSafetyNotice = null
                Log.i(
                    "DshPrompt",
                    "resolved active=${resolution.prompt.pluginId ?: "builtin"}:${resolution.prompt.promptId} pref=${pref ?: "-"}",
                )
            }

            is PromptResolution.Unavailable -> {
                activePromptState = null
                promptSafetyNotice = resolution.reason
                Log.w("DshPrompt", "prompt unavailable: ${resolution.reason} pref=${pref ?: "-"}")
            }
        }
    }

    override fun setActivePrompt(id: String): Boolean {
        return when (val resolution = PromptRouting.resolve(id, promptCandidates())) {
            is PromptResolution.Active -> {
                prefs.edit().putString("dsh_prompt", PromptRouting.prefOf(resolution.prompt)).apply()
                activePromptState = resolution.prompt
                promptSafetyNotice = null
                pluginNotice = "提示词插件：${resolution.prompt.label}"
                Log.i("DshPrompt", "set active=${PromptRouting.prefOf(resolution.prompt)}")
                true
            }

            is PromptResolution.Unavailable -> {
                promptSafetyNotice = resolution.reason
                pluginNotice = resolution.reason
                Log.w("DshPrompt", "set prompt failed id=$id: ${resolution.reason}")
                false
            }
        }
    }

    override fun restoreBuiltinPrompt() {
        builtinStore.setEnabled(xin.dshaa.nativeapp.data.BuiltinPlugins.NATIVE_PROMPT, true)
        builtinPluginsUi = builtinStore.ui()
        prefs.edit().putString("dsh_prompt", "native").apply()
        refreshPromptResolution()
        pluginNotice = "已恢复内置提示词：builtin.prompt.dsh"
        Log.i("DshPrompt", "restore builtin active=${activePromptState?.promptId}")
    }

    /**
     * 每轮 system 拼装入口（M85-E）：内置实现 = 现有 `PromptProviders.build`（含 M84-C 调试实现）；
     * 第三方实现 = 把内置结果作为 base dispatch 给 `prompt.assemble`。插件失败时回到 base 并亮提示，
     * 不让插件 bug 把整轮打挂（停用/选不中仍然在发送前被拦）。
     */
    private fun buildSystemPrompt(request: PromptRequest): String {
        val resolved = activePromptState ?: return ""
        // 内置/调试实现：原路径（M84-E1 插件段/变量/抑制运行时上下文都在 PromptProviders 里）。
        val base = PromptProviders.build(promptPack, request)
        val promptPluginId = resolved.pluginId
        if (resolved.builtin || promptPluginId.isNullOrBlank()) return base
        val payload = buildJsonObject {
            put("id", resolved.promptId)
            put("base", base)
            put("model", request.model)
            put("provider", request.provider)
            put(
                "tools",
                buildJsonArray { request.tools.sorted().forEach { add(it) } },
            )
            put("planMode", request.planMode)
            put("personaPrefix", request.personaPrefix)
            put("personaSuffix", request.personaSuffix)
            put("variables", buildJsonObject { request.variables.forEach { (key, value) -> put(key, value) } })
        }.toString()
        val outcome = pluginResidentRuntime.dispatch(
            promptPluginId,
            PROMPT_ASSEMBLE_METHOD,
            payload,
            PROMPT_ASSEMBLE_TIMEOUT_MS,
        )
        val text = parsePromptText(outcome)
        if (text == null) {
            val reason = "提示词插件 ${resolved.label} 运行失败（已回退内置拼装）：" +
                describePromptFailure(outcome)
            Log.w("DshPrompt", reason)
            android.os.Handler(android.os.Looper.getMainLooper()).post { promptSafetyNotice = reason }
            return base
        }
        Log.i("DshPrompt", "assembled by plugin=${resolved.pluginId} chars=${text.length} base=${base.length}")
        return text
    }

    private fun parsePromptText(outcome: JsOutcome?): String? {
        if (outcome == null || outcome.status != JsStatus.COMPLETED) return null
        val obj = runCatching { Json.parseToJsonElement(outcome.valueJson ?: "null").jsonObject }.getOrNull()
            ?: return null
        val ok = obj["ok"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
        if (!ok) return null
        return obj["text"]?.jsonPrimitive?.contentOrNull
    }

    private fun describePromptFailure(outcome: JsOutcome?): String {
        if (outcome == null) return "常驻运行时没有就绪（插件已停用或启动失败）"
        if (outcome.status != JsStatus.COMPLETED) return "运行超时/中断：${outcome.error ?: outcome.status}"
        val obj = runCatching { Json.parseToJsonElement(outcome.valueJson ?: "null").jsonObject }.getOrNull()
        return obj?.get("error")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: "返回内容不可用"
    }

    // ---- M85-E：model/v1 ----

    private fun modelCandidates(): List<ModelCandidate> {
        val out = ArrayList<ModelCandidate>()
        out += ModelCandidate(
            providerId = MODEL_PROVIDER_DEEPSEEK,
            label = "DeepSeek · Anthropic Messages",
            builtinPluginId = BUILTIN_MODEL_DEEPSEEK,
            builtin = true,
            enabled = builtinStore.isEnabled(xin.dshaa.nativeapp.data.BuiltinPlugins.NATIVE_MODEL_DEEPSEEK),
        )
        out += ModelCandidate(
            providerId = MODEL_PROVIDER_OPENAI,
            label = "OpenAI 兼容",
            builtinPluginId = BUILTIN_MODEL_OPENAI,
            builtin = true,
            enabled = builtinStore.isEnabled(xin.dshaa.nativeapp.data.BuiltinPlugins.NATIVE_MODEL_OPENAI),
        )
        pluginsUi.forEach { record ->
            record.llmProviders.forEach { provider ->
                out += ModelCandidate(
                    providerId = provider.id,
                    label = provider.label,
                    pluginId = record.id,
                    builtin = false,
                    enabled = record.enabled,
                )
            }
        }
        return out
    }

    private fun currentModelResolution(): ModelResolution =
        ModelRouting.resolve(settings.llmProviderId, settings.protocol, modelCandidates())

    /** 插件开关/内置模型开关变化后记一次解析结果（模型选择本身是现算的，无需缓存）。 */
    private fun refreshModelResolution() {
        when (val resolution = currentModelResolution()) {
            is ModelResolution.Active ->
                Log.i(
                    "DshModel",
                    "resolved active=${resolution.model.providerId} plugin=${resolution.model.pluginId ?: "builtin"} " +
                        "pref=${settings.llmProviderId.ifBlank { "-" }} protocol=${settings.protocol}",
                )

            is ModelResolution.Unavailable ->
                Log.w("DshModel", "model unavailable: ${resolution.reason}")
        }
    }

    override val modelTargetsUi: List<RoleTargetUi>
        get() {
            val active = currentModelResolution().let { (it as? ModelResolution.Active)?.model }
            return modelCandidates().map { candidate ->
                RoleTargetUi(
                    role = "model",
                    implementationId = candidate.providerId,
                    label = candidate.label,
                    pluginId = candidate.pluginId,
                    provider = candidate.pluginId?.let { id -> pluginsUi.firstOrNull { it.id == id }?.name ?: id } ?: "内置",
                    builtin = candidate.builtin,
                    enabled = candidate.enabled,
                    permitted = candidate.permitted,
                    active = active != null && active.providerId == candidate.providerId &&
                        active.pluginId == candidate.pluginId,
                    selectionValue = candidate.providerId,
                    unavailableReason = when {
                        !candidate.enabled -> "插件已停用"
                        !candidate.permitted -> "缺少 model 授权"
                        else -> null
                    },
                )
            }
        }

    override val activeModelUi: RoleTargetUi?
        get() = modelTargetsUi.firstOrNull { it.active }

    override fun setActiveModel(id: String): Boolean {
        return when (val resolution = ModelRouting.resolve(id, settings.protocol, modelCandidates())) {
            is ModelResolution.Active -> {
                settings.setLlmProviderId(resolution.model.providerId)
                prefs.edit().remove("debug_llm_provider").apply()
                pluginNotice = "模型通道：${resolution.model.label}"
                Log.i("DshModel", "set active=${resolution.model.providerId} plugin=${resolution.model.pluginId ?: "-"}")
                true
            }

            is ModelResolution.Unavailable -> {
                pluginNotice = resolution.reason
                Log.w("DshModel", "set model failed id=$id: ${resolution.reason}")
                false
            }
        }
    }

    override fun restoreBuiltinModel() {
        settings.setLlmProviderId("")
        prefs.edit().remove("debug_llm_provider").apply()
        pluginNotice = "模型通道：跟随协议（内置 deepseek/openai）"
        Log.i("DshModel", "restore follow-protocol")
    }

    /** 调试通道 `dsh_roles_list <路径>`：存储/提示词/模型三角色解析 + 注册表落盘。 */
    override fun dumpRoles(dumpPath: String?) {
        if (dumpPath.isNullOrBlank()) return
        runCatching {
            val roleRows = org.json.JSONArray()
            (storageTargetsUi + promptTargetsUi + modelTargetsUi).forEach { item ->
                roleRows.put(
                    org.json.JSONObject()
                        .put("role", item.role)
                        .put("id", item.implementationId)
                        .put("plugin", item.pluginId ?: "")
                        .put("label", item.label)
                        .put("builtin", item.builtin)
                        .put("enabled", item.enabled)
                        .put("permitted", item.permitted)
                        .put("active", item.active)
                        .put("readOnly", item.readOnly)
                        .put("selection", item.selectionValue)
                        .put("unavailable", item.unavailableReason ?: ""),
                )
            }
            val payload = org.json.JSONObject()
                .put("version", 1)
                .put("storageContract", STORAGE_CONTRACT)
                .put("promptContract", PROMPT_CONTRACT)
                .put("modelContract", MODEL_CONTRACT)
                .put("storagePref", prefs.getString("dsh_storage", "").orEmpty())
                .put("promptPref", prefs.getString("dsh_prompt", "").orEmpty())
                .put("modelPref", settings.llmProviderId)
                .put(
                    "active",
                    org.json.JSONObject()
                        .put("storage", activeStorage?.storageId ?: "")
                        .put("storagePlugin", activeStorage?.pluginId ?: "")
                        .put("storageReadOnly", activeStorage?.readOnly == true)
                        .put("storageSafety", storageSafetyNotice ?: "")
                        .put("prompt", activePromptState?.promptId ?: "")
                        .put("promptPlugin", activePromptState?.pluginId ?: "")
                        .put("promptSafety", promptSafetyNotice ?: "")
                        .put("model", activeModelUi?.implementationId ?: "")
                        .put("modelPlugin", activeModelUi?.pluginId ?: "")
                        .put("modelReason", (currentModelResolution() as? ModelResolution.Unavailable)?.reason ?: ""),
                )
                .put("store", repo.id)
                .put("storeReadOnly", repo.readOnly)
                .put("registeredProviders", org.json.JSONArray(LlmProviders.ids()))
                .put("roles", roleRows)
            val file = File(dumpPath)
            file.parentFile?.mkdirs()
            file.writeText(payload.toString())
            Log.i("DshRoles", "dump roles -> $dumpPath")
        }.onFailure { Log.w("DshRoles", "dump roles failed: ${it.message}") }
    }

    // ---- M85-F：能力插件（capability/v1，root/NFC/Shizuku/adb-shell 等由插件提供） ----

    override val capabilityUi: List<xin.dshaa.ui.shellapi.CapabilityUi>
        get() = capabilityHost.ui()

    override fun invokeCapability(id: String) {
        val clean = id.trim()
        if (clean.isEmpty()) return
        capabilityNotice = "正在调用能力 $clean…"
        viewModelScope.launch {
            val result = capabilityHost.invoke(clean, "{}")
            capabilityNotice = if (result.ok) {
                "${result.summary}：${result.valueJson.take(240)}"
            } else {
                result.error ?: "能力 $clean 调用失败"
            }
        }
    }

    /** 调试通道 `dsh_capability_list <路径>`：能力清单 + 每个 id 的提供方解析 + 全部插件提供方。 */
    override fun dumpCapabilities(dumpPath: String?) {
        if (dumpPath.isNullOrBlank()) return
        runCatching {
            val all = capabilityHost.candidates()
            val rows = org.json.JSONArray()
            capabilityHost.ui().forEach { item ->
                rows.put(
                    org.json.JSONObject()
                        .put("id", item.id)
                        .put("label", item.label)
                        .put("kind", item.kind)
                        .put("description", item.description)
                        .put("provider", item.provider)
                        .put("plugin", item.pluginId ?: "")
                        .put("builtin", item.builtin)
                        .put("available", item.available)
                        .put("enabled", item.enabled)
                        .put("permitted", item.permitted)
                        .put("reason", item.unavailableReason ?: ""),
                )
            }
            val providers = org.json.JSONArray()
            all.filter { !it.builtin }.forEach { candidate ->
                providers.put(
                    org.json.JSONObject()
                        .put("plugin", candidate.pluginId ?: "")
                        .put("id", candidate.id)
                        .put("label", candidate.label)
                        .put("kind", candidate.kind)
                        .put("enabled", candidate.enabled)
                        .put("permitted", candidate.permitted),
                )
            }
            val payload = org.json.JSONObject()
                .put("version", 1)
                .put("contract", xin.dshaa.core.capability.CAPABILITY_CONTRACT)
                .put("capabilities", rows)
                .put("pluginProviders", providers)
                .put("builtin", org.json.JSONArray(capabilityHost.ui().filter { it.builtin }.map { it.id }))
                .put("notice", capabilityNotice ?: "")
            val file = File(dumpPath)
            file.parentFile?.mkdirs()
            file.writeText(payload.toString())
            Log.i("DshCapability", "dump capabilities -> $dumpPath count=${rows.length()}")
        }.onFailure { Log.w("DshCapability", "dump capabilities failed: ${it.message}") }
    }

    /**
     * 调试通道 `dsh_capability_test <路径>`：等常驻运行时后依次调用
     * 内置能力（notify / clipboard.write / clipboard.read）、插件能力（nfc / adb-shell）
     * 和一条故意不存在的能力，结果落盘。`share` 会弹系统面板，不放进自动验收。
     */
    override fun runCapabilityTest(dumpPath: String?) {
        if (dumpPath.isNullOrBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { pluginResidentRuntime.awaitSettled(6_000) }
            val cases = listOf(
                Triple("notify", """{"title":"M85-F 能力验收","body":"builtin notify"}""", "内置通知"),
                Triple("clipboard.write", """{"text":"M85F-CLIP"}""", "内置写剪贴板"),
                Triple("clipboard.read", "{}", "内置读剪贴板"),
                Triple("nfc", """{"action":"read"}""", "插件 NFC（演示）"),
                Triple("adb-shell", """{"cmd":"id"}""", "插件 adb-shell（演示）"),
                Triple("m85f.missing", "{}", "不存在的能力"),
            )
            val results = org.json.JSONArray()
            cases.forEach { (id, args, title) ->
                val result = capabilityHost.invoke(id, args)
                results.put(
                    org.json.JSONObject()
                        .put("case", title)
                        .put("id", id)
                        .put("args", args)
                        .put("ok", result.ok)
                        .put("plugin", result.pluginId ?: "")
                        .put("value", result.valueJson)
                        .put("error", result.error ?: ""),
                )
            }
            val payload = org.json.JSONObject()
                .put("version", 1)
                .put("contract", xin.dshaa.core.capability.CAPABILITY_CONTRACT)
                .put("results", results)
                .put("capabilities", org.json.JSONArray(capabilityHost.ui().map { it.id }))
            val file = File(dumpPath)
            file.parentFile?.mkdirs()
            file.writeText(payload.toString())
            Log.i("DshCapability", "capability test -> $dumpPath cases=${cases.size}")
        }
    }

    /** M85-B2：壳命令登记的「打开系统选择器」请求。
     *
     * 壳（JS）只发通用 `pickFile`/`screenshot` 命令；真正的 SAF/相机由壳宿主屏的
     * `rememberLauncherForActivityResult` 启动，消费后序号对得上才清空，避免慢启动的选择器
     * 把后来的请求吃掉。
     */
    var shellPickRequest by mutableStateOf<ShellPickRequest?>(null)
        private set
    private var shellPickSerial = 0L

    private fun requestShellPick(kind: ShellPickKind) {
        shellPickRequest = ShellPickRequest(++shellPickSerial, kind, chat)
        Log.i("DshShell", "pick kind=$kind serial=$shellPickSerial")
    }

    fun consumeShellPick(serial: Long) {
        val current = shellPickRequest ?: return
        if (current.serial == serial) shellPickRequest = null
    }

    /**
     * M85-B2-2：壳的展开/收起状态（通用 key-value，按会话隔离、不落盘）。
     *
     * 宿主不认识 key 的语义；只做长度/条数上限，避免插件把状态当无限存储。
     */
    fun setShellView(key: String, value: Boolean?, toggle: Boolean, clearPrefix: String? = null) {
        val state = chat ?: return
        val safe = key.trim().take(160)
        if (safe.isEmpty()) return
        val current = state.shellView[safe] ?: false
        val next = if (toggle) !current else (value ?: false)
        // 单选组：先清掉同前缀的兄弟项，再写自己（壳不解释前缀语义）
        if (next && !clearPrefix.isNullOrBlank() && clearPrefix.length <= 120) {
            state.shellView.keys.filter { it.startsWith(clearPrefix) && it != safe }.forEach { keyToDrop ->
                state.shellView.remove(keyToDrop)
            }
        }
        state.shellView[safe] = next
        if (state.shellView.size > 400) {
            // 只保留最近 400 条（没有顺序可依，直接清空最省事：壳下轮会重新展开）
            state.shellView.clear()
        }
    }

    /**
     * 壳返回的 state（协议 §5）。只在结构变化时由壳宿主取一次；token 走 bind 直读 Compose 状态。
     */
    fun shellStateJson(): String {
        val state = chat
        val toolResults = state?.toolResults.orEmpty()
        val messages = state?.messages.orEmpty().map { message ->
            buildJsonObject {
                put("id", message.id)
                put("role", roleName(message.role))
                // M85-F：WebView/Surface 壳都可能要过滤内部注入消息（goal/技能目录/时间上下文）。
                put("sourceKind", message.sourceKind ?: "")
                put("text", messageText(message))
                put("thinking", message.content.filter { it.type == BlockType.REASONING }.joinToString("") { it.text })
                put("streaming", message.streaming)
                put("seq", message.seq ?: -1L)
                message.error?.let { put("error", it) }
                // M85-B2：历史消息里的图片（会话只存 attachmentId，路径由宿主附件存储寻址）
                put(
                    "attachments",
                    buildJsonArray {
                        message.content.filter { it.type == BlockType.IMAGE }.forEach { block ->
                            val image = block.toImageRef() ?: return@forEach
                            add(
                                buildJsonObject {
                                    put("id", image.attachmentId)
                                    put("name", image.name ?: "图片")
                                    put("path", imagePathFor(image.attachmentId).orEmpty())
                                    put("mediaType", image.mediaType)
                                },
                            )
                        }
                    },
                )
                put(
                    "toolCalls",
                    buildJsonArray {
                        message.content.filter { it.type == BlockType.TOOL_CALL }.forEach { block ->
                            val result = block.toolCallId?.let { toolResults[it] }
                            add(
                                buildJsonObject {
                                    put("id", block.toolCallId ?: "")
                                    put("name", block.name ?: "")
                                    put("arguments", block.arguments)
                                    put(
                                        "result",
                                        result?.content?.filter { it.type == BlockType.TEXT }
                                            ?.joinToString("") { it.text }.orEmpty(),
                                    )
                                    put("isError", result?.error != null)
                                    put("running", message.streaming && result == null)
                                    put(
                                        "durationMs",
                                        block.toolCallId?.let { id -> state?.toolDurations?.get(id) } ?: 0L,
                                    )
                                    put(
                                        "status",
                                        when {
                                            result?.error != null -> "error"
                                            message.streaming && result == null -> "running"
                                            result != null -> "done"
                                            else -> "pending"
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
                put(
                    "blocks",
                    buildJsonArray {
                        message.content.forEach { block ->
                            add(
                                buildJsonObject {
                                    put("type", blockName(block.type))
                                    put("text", block.text)
                                    block.name?.let { put("name", it) }
                                    block.toolCallId?.let { put("toolCallId", it) }
                                    if (block.arguments.isNotBlank()) put("arguments", block.arguments)
                                },
                            )
                        }
                    },
                )
            }
        }
        val status = when {
            state == null -> ""
            state.stopping -> "停止中…"
            state.streaming -> state.statusText?.takeIf { it.isNotBlank() } ?: "正在回复…"
            else -> state.statusText.orEmpty()
        }
        val sessions = sessions.map { summary ->
            buildJsonObject {
                put("id", summary.id)
                put("title", summary.title.ifBlank { "未命名会话" })
                put("updatedAt", summary.updatedAt)
                put("current", summary.id == state?.sessionId)
            }
        }
        return buildJsonObject {
            put(
                "chat",
                buildJsonObject {
                    put("sessionId", state?.sessionId ?: "")
                    put("title", state?.title ?: "新会话")
                    put("status", status)
                    put("draft", state?.draft?.text.orEmpty())
                    put("streaming", state?.streaming == true)
                    put("error", state?.error ?: "")
                    put("readOnly", state?.readOnly == true)
                    put("usage", state?.usageText ?: "")
                    put("queueNotice", state?.queueNotice ?: "")
                    put("hasQueuedPrompts", !state?.queuedPrompts.isNullOrEmpty())
                    put("queuePaused", state?.queuedPrompts?.any { it.pausedReason != null } == true)
                    put("queuedPrompts", buildJsonArray {
                        state?.queuedPrompts.orEmpty().forEach { queued -> add(buildJsonObject {
                            put("id", queued.id); put("text", queued.payload.text)
                            put("delivery", queued.delivery.name.lowercase())
                            put("status", queued.status.name.lowercase())
                            put("pausedReason", queued.pausedReason ?: "")
                            put("createdAt", queued.createdAt)
                            put("imageCount", queued.payload.images.size)
                            put("fileCount", queued.payload.textBlocks.count { it.startsWith("[附件: ") || it.startsWith("<attachment ") })
                            put("canCancel", queued.status == xin.dshaa.core.session.PromptQueueStatus.PENDING)
                        }) }
                    })
                    // M85-B2 待发附件：壳只拿 name/path/大小，不解释附件存储格式
                    put(
                        "pendingImages",
                        buildJsonArray {
                            state?.pendingImages.orEmpty().forEach { pending ->
                                add(
                                    buildJsonObject {
                                        put("id", pending.ref.attachmentId)
                                        put("name", pending.ref.name ?: "图片")
                                        put("path", pending.path)
                                        put("mediaType", pending.ref.mediaType)
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "pendingFiles",
                        buildJsonArray {
                            state?.pendingFiles.orEmpty().forEach { file ->
                                add(
                                    buildJsonObject {
                                        put("name", file.name)
                                        put("path", file.path.orEmpty())
                                        put("chars", file.chars)
                                        put("size", file.size)
                                        put("reference", file.isReference)
                                    },
                                )
                            }
                        },
                    )
                    put("attachError", state?.attachError ?: "")
                    put(
                        "hasAttachments",
                        state != null && (state.pendingImages.isNotEmpty() || state.pendingFiles.isNotEmpty()),
                    )
                    put(
                        "context",
                        buildJsonObject {
                            val window = state?.contextWindow ?: 0
                            val prompt = state?.lastPromptTokens ?: 0
                            val ratio = if (window > 0) (prompt.toFloat() / window).coerceIn(0f, 1f) else 0f
                            put("promptTokens", prompt)
                            put("completionTokens", state?.lastCompletionTokens ?: 0)
                            put("window", window)
                            put("ratio", ratio)
                            put("label", if (window > 0) "${(ratio * 100).toInt()}% · $prompt/$window" else "")
                        },
                    )
                    put(
                        "pendingApproval",
                        buildJsonObject {
                            val approval = state?.pendingApproval
                            put("active", approval != null)
                            put("toolName", approval?.toolName ?: "")
                            put("summary", approval?.summary ?: "")
                            put("arguments", approval?.arguments ?: "")
                        },
                    )
                    put(
                        "pendingQuestions",
                        buildJsonObject {
                            val questions = state?.pendingQuestions.orEmpty()
                            put("active", questions.isNotEmpty())
                            put("count", questions.size)
                            put(
                                "header",
                                questions.firstOrNull()?.header?.takeIf { it.isNotBlank() }
                                    ?: questions.firstOrNull()?.question
                                    ?: "需要你确认",
                            )
                            put(
                                "items",
                                buildJsonArray {
                                    questions.forEach { question ->
                                        add(
                                            buildJsonObject {
                                                put("id", question.id)
                                                put("header", question.header ?: "")
                                                put("question", question.question)
                                                put("multiSelect", question.multiSelect)
                                                put("detail", question.detail ?: "")
                                                put(
                                                    "options",
                                                    buildJsonArray {
                                                        question.options.forEach { option ->
                                                            add(
                                                                buildJsonObject {
                                                                    put("label", option.label)
                                                                    put("description", option.description ?: "")
                                                                },
                                                            )
                                                        }
                                                    },
                                                )
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                    // 壳自己的展开/多选状态：只读回传，壳用 Object.keys 自己算答案
                    put(
                        "view",
                        buildJsonObject {
                            state?.shellView?.forEach { (key, value) -> put(key, value) }
                        },
                    )
                    put(
                        "goal",
                        buildJsonObject {
                            val goal = state?.goal
                            put("active", goal != null)
                            put("objective", goal?.goal?.objective ?: "")
                            put("phase", goal?.goal?.phase ?: "")
                            put("rounds", goal?.roundsStarted ?: 0)
                            put("maxRounds", goal?.goal?.maxGoalRounds ?: 0)
                            put("activation", goal?.activation ?: "")
                            put("blockedReason", goal?.goal?.blockedReason?.message ?: "")
                            put("label", goalLabel(goal))
                        },
                    )
                    put(
                        "turn",
                        buildJsonObject {
                            put("active", state?.streaming == true || state?.stopping == true)
                            put("step", state?.statusText ?: "")
                        },
                    )
                    put("messages", buildJsonArray { messages.forEach { add(it) } })
                },
            )
            put("sessions", buildJsonArray { sessions.forEach { add(it) } })
            put(
                "settings",
                buildJsonObject {
                    put("model", settings.model)
                    put("assistantName", settings.assistantName)
                    put("themeMode", settings.themeMode)
                    put("accent", settings.accentId)
                    put("planActive", state?.planActive == true)
                    put("sendWhileRunningMode", settings.sendWhileRunningMode)
                },
            )
            put(
                "theme",
                buildJsonObject {
                    put("mode", settings.themeMode)
                    put("accent", settings.accentId)
                },
            )
            put(
                "capabilities",
                buildJsonObject {
                    put("surface", xin.dshaa.core.plugins.SurfaceSpec.VERSION)
                    put(
                        "commands",
                        buildJsonArray {
                            xin.dshaa.core.plugins.SurfaceSpec.COMMANDS.forEach { add(it) }
                        },
                    )
                    put(
                        "bindRoots",
                        buildJsonArray {
                            xin.dshaa.core.plugins.SurfaceSpec.BIND_ROOTS.forEach { add(it) }
                        },
                    )
                },
            )
        }.toString()
    }

    /** 目标条文案（与 legacy GoalStrip 同一套阶段语义，壳只显示）。 */
    private fun goalLabel(view: GoalView?): String {
        if (view == null) return ""
        val phase = when (view.goal.phase) {
            GoalPhase.ACTIVE -> if (view.activation == GoalActivation.ARMED) "进行中" else "已停续轮"
            GoalPhase.PAUSED -> "已暂停"
            GoalPhase.BLOCKED -> "受阻"
            GoalPhase.COMPLETE -> "已完成"
            else -> view.goal.phase
        }
        return "$phase · 第 ${view.roundsStarted}/${view.goal.maxGoalRounds} 轮"
    }

    private fun roleName(role: MessageRole): String = when (role) {
        MessageRole.USER -> "user"
        MessageRole.ASSISTANT -> "assistant"
        MessageRole.TOOL -> "tool"
        MessageRole.SYSTEM -> "system"
        MessageRole.DEVELOPER -> "developer"
        MessageRole.UNKNOWN -> "unknown"
    }

    private fun blockName(type: BlockType): String = when (type) {
        BlockType.TEXT -> "text"
        BlockType.REASONING -> "thinking"
        BlockType.TOOL_CALL -> "tool_use"
        BlockType.IMAGE -> "image"
        BlockType.TOOL_ADDITION -> "tool_addition"
        BlockType.UNKNOWN -> "unknown"
    }

    private fun messageText(message: ChatMessage): String = message.content
        .filter { it.type == BlockType.TEXT || it.type == BlockType.REASONING }
        .joinToString("") { it.text }

    /**
     * 壳 → 宿主的命令（M85-A 白名单）。JS 线程同步调用，宿主切主线程执行后返回 JSON。
     */
    fun shellCommand(command: String, argsJson: String?): String {
        val name = command.trim()
        if (name !in xin.dshaa.core.plugins.SurfaceSpec.COMMANDS) {
            return """{"ok":false,"error":"未知命令：${escapeJson(name)}"}"""
        }
        val args = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(argsJson ?: "{}").jsonObject
        }.getOrDefault(JsonObject(emptyMap()))
        return onMainBlocking(4_000) {
            runCatching {
                when (name) {
                    "send" -> {
                        val text = args["text"]?.jsonPrimitive?.contentOrNull
                            ?: args["value"]?.jsonPrimitive?.contentOrNull
                            ?: chat?.draft?.text.orEmpty()
                        val ok = submit(text)
                        if (ok) setShellDraft("")
                        """{"ok":$ok}"""
                    }

                    "stop" -> {
                        stop()
                        """{"ok":true}"""
                    }

                    "cancelQueuedPrompt" -> {
                        val id = args["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val before = chat?.queuedPrompts?.any { it.id == id } == true
                        if (before) cancelQueuedPrompt(id)
                        val ok = before && chat?.queuedPrompts?.none { it.id == id } == true
                        buildJsonObject { put("ok", ok); if (!ok) put("error", chat?.queueNotice ?: "没有可取消的队列消息") }.toString()
                    }

                    "resumeQueuedPrompts" -> {
                        resumeQueuedPrompts()
                        val ok = chat?.queuedPrompts?.none { it.pausedReason != null } == true
                        buildJsonObject { put("ok", ok); put("notice", chat?.queueNotice ?: "") }.toString()
                    }

                    "setSendWhileRunningMode" -> {
                        val mode = args["mode"]?.jsonPrimitive?.contentOrNull
                        require(mode == "queue" || mode == "steer") { "发送模式只能是 queue 或 steer" }
                        settings.setSendWhileRunningMode(mode)
                        """{"ok":true}"""
                    }

                    "newSession" -> {
                        newChat()
                        """{"ok":true}"""
                    }

                    "setDraft" -> {
                        setShellDraft(
                            args["text"]?.jsonPrimitive?.contentOrNull
                                ?: args["value"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        )
                        """{"ok":true}"""
                    }

                    "approval" -> {
                        val allow = args["allow"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                        val always = args["always"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                        resolveApproval(allow, always)
                        """{"ok":true}"""
                    }

                    // M85-B2 附件：选择器请求交给壳宿主屏启动（SAF/相机不能在 ViewModel 里直接 launch）
                    "pickFile" -> {
                        when (args["kind"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                            "image" -> requestShellPick(ShellPickKind.IMAGE)
                            "camera" -> requestShellPick(ShellPickKind.CAMERA)
                            else -> requestShellPick(ShellPickKind.FILE)
                        }
                        """{"ok":true}"""
                    }

                    "screenshot" -> {
                        attachScreenshot()
                        """{"ok":true}"""
                    }

                    "removeAttachment" -> {
                        val kind = args["kind"]?.jsonPrimitive?.contentOrNull?.lowercase()
                        if (kind == "image") {
                            val id = args["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (id.isNotBlank()) removePendingImage(id)
                        } else {
                            val name = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (name.isNotBlank()) removePendingFile(name)
                        }
                        """{"ok":true}"""
                    }

                    "setShellView" -> {
                        val key = args["key"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val toggle = args["toggle"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                        val value = args["value"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                        val clearPrefix = args["clearPrefix"]?.jsonPrimitive?.contentOrNull
                        setShellView(key, value, toggle, clearPrefix)
                        """{"ok":true}"""
                    }

                    "answer" -> {
                        val pending = chat?.pendingQuestions.orEmpty()
                        val items = runCatching { args["answers"]?.jsonArray }.getOrNull()
                        val answers = if (items.isNullOrEmpty()) {
                            // 空/缺省 = 跳过全部（与 legacy「跳过」一致）
                            pending.map { question -> xin.dshaa.core.tools.AskAnswer(question.id) }
                        } else {
                            items.mapNotNull { element ->
                                val obj = element as? JsonObject ?: return@mapNotNull null
                                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                                val selected = runCatching {
                                    obj["selected"]?.jsonArray.orEmpty().mapNotNull { option ->
                                        option.jsonPrimitive.contentOrNull
                                    }
                                }.getOrDefault(emptyList())
                                val custom = obj["custom"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                xin.dshaa.core.tools.AskAnswer(id, selected, custom)
                            }
                        }
                        resolveQuestions(answers)
                        // 答题输入框复用了通用 draft：提交/跳过后清空，别让下一条消息继承
                        setShellDraft("")
                        """{"ok":true,"answered":${answers.size}}"""
                    }

                    // M85-B3：goal 面板的暂停/继续/编辑/清空（与 legacy GoalDialog 同一套 VM 入口）
                    "goal" -> {
                        val goalAction = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
                        val objective = args["objective"]?.jsonPrimitive?.contentOrNull
                        val maxRounds = args["maxRounds"]?.jsonPrimitive?.intOrNull
                        val error = runBlocking {
                            when (goalAction) {
                                "pause" -> pauseGoal()
                                "resume" -> resumeGoal()
                                "edit" -> editGoal(objective, maxRounds)
                                "clear" -> clearGoal()
                                else -> "未知 goal 动作：$goalAction"
                            }
                        }
                        if (error == null) """{"ok":true}""" else """{"ok":false,"error":"${escapeJson(error)}"}"""
                    }

                    // M85-B3：会话提醒的查看（rows `chat.schedules`）与删除
                    "deleteSchedule" -> {
                        val id = args["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (id.isNotBlank()) deleteSchedule(id)
                        """{"ok":true}"""
                    }

                    // M85-B3：其它会话在等你的横幅 → 切到那条会话（不带 id = 第一条等待项）
                    "focusSession" -> {
                        val id = args["id"]?.jsonPrimitive?.contentOrNull
                            ?: args["sessionId"]?.jsonPrimitive?.contentOrNull
                            ?: waitingElsewhere().firstOrNull()?.sessionId.orEmpty()
                        if (id.isNotBlank()) focusSession(id)
                        """{"ok":true}"""
                    }

                    "openSession", "selectSession" -> {
                        val id = args["id"]?.jsonPrimitive?.contentOrNull
                            ?: args["sessionId"]?.jsonPrimitive?.contentOrNull
                        val index = args["index"]?.jsonPrimitive?.intOrNull ?: -1
                        val target = if (!id.isNullOrBlank()) {
                            sessions.firstOrNull { it.id == id }
                        } else {
                            sessions.getOrNull(index)
                        }
                        if (target == null) {
                            """{"ok":false,"error":"没有这个会话"}"""
                        } else {
                            openSession(target)
                            """{"ok":true}"""
                        }
                    }

                    "openSettings" -> {
                        // M85-A 设置页仍由宿主提供：先回原生壳，再落到设置页（M85-C 迁完后改为壳内页面）
                        setActiveShell(null)
                        requestDebugScreen("Settings")
                        """{"ok":true,"note":"已切回原生设置页"}"""
                    }

                    "closeShell" -> {
                        setActiveShell(null)
                        """{"ok":true}"""
                    }

                    else -> """{"ok":false,"error":"未知命令：${escapeJson(name)}"}"""
                }
            }.getOrElse { """{"ok":false,"error":"${escapeJson(it.message ?: "宿主命令失败")}"}""" }
        }
    }

    /** 主线程执行并阻塞等待（JS 的 surface.command 是同步协议）；不在主线程时用。 */
    private fun onMainBlocking(timeoutMs: Long, block: () -> String): String {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        val latch = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference("""{"ok":false,"error":"宿主命令超时"}""")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                result.set(block())
            } finally {
                latch.countDown()
            }
        }
        return if (latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            result.get()
        } else {
            """{"ok":false,"error":"宿主命令超时（${timeoutMs}ms）"}"""
        }
    }

    private fun escapeJson(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    /**
     * M84-F 验收通道（仅 debug）：把所有导入插件一起开/关，并落盘当前名单。
     * 空壳 = 内置 `all` off + 这里 off；全装配 = 内置 reset + 这里 on。
     */
    fun applyDebugUserPlugins(offAll: Boolean, onAll: Boolean, dumpPath: String?) {
        val changed = when {
            offAll -> pluginStore.setAllEnabled(false)
            onAll -> pluginStore.setAllEnabled(true)
            else -> 0
        }
        pluginsUi = pluginStore.list()
        syncPluginTools()
        val enabled = pluginsUi.filter { it.enabled }
        Log.i(
            "DshPlugin",
            "debug user plugins changed=$changed enabled=${enabled.size}/${pluginsUi.size} tools=${pluginToolNames.size}",
        )
        if (dumpPath.isNullOrBlank()) return
        runCatching {
            val payload = org.json.JSONObject()
                .put("changed", changed)
                .put("total", pluginsUi.size)
                .put("enabledCount", enabled.size)
                .put("enabled", org.json.JSONArray(enabled.map { it.id }))
                .put("disabled", org.json.JSONArray(pluginsUi.filter { !it.enabled }.map { it.id }))
                .put("pluginTools", org.json.JSONArray(pluginToolNames.sorted()))
                .put("llmProviders", org.json.JSONArray(pluginProviderIds.sorted()))
            val file = File(dumpPath)
            file.parentFile?.mkdirs()
            file.writeText(payload.toString())
            Log.i("DshPlugin", "dump plugins -> $dumpPath")
        }.onFailure { Log.w("DshPlugin", "dump plugins failed: ${it.message}") }
    }

    /**
     * M70：打开插件**自己的**浏览器半侧（`dsh.client` 的 client.js），走通用客户端插件宿主，
     * 不再按插件写死原生窗口。结果（成功/为什么不能跑）回写到插件页顶部的 notice。
     */
    override fun openClientPluginWindow(record: PluginRecord) {
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
        runCatching { loadingSnapshotStore.refresh(pluginsUi, prefs.getString("dsh_loading", null)) }
            .onSuccess { loadingSafetyNotice = it.unavailableReason }
            .onFailure {
                // A failed invalidation must not let a stale selected tree return on next boot.
                val reset = prefs.edit().remove("dsh_loading").commit()
                loadingSafetyNotice = "启动页快照更新失败，已恢复默认选择：${it.message}" + if (reset) "" else "（选择保存失败）"
            }
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
        // M84-E2：插件声明的模型供应商跟插件开关一起进出注册表
        syncPluginLlmProviders()
        // M84-H：注册了会话投影的插件跟插件开关一起起/停常驻运行时
        pluginResidentRuntime.sync(
            pluginsUi,
            workspace,
            xin.dshaa.core.plugins.PluginServices.snapshot(pluginsUi),
        )
        // M85-A：插件启用/停用/重载后重新解析当前壳；失效就回原生，重新启用按 `dsh_shell` 恢复。
        val resolvedShell = resolveShellTarget(prefs.getString("dsh_shell", null))
        if (resolvedShell != shellActive) {
            shellHost.reset()
            shellActive = resolvedShell
            Log.i("DshShell", "shell resolved=${resolvedShell?.pluginId}:${resolvedShell?.shellId} pref=${prefs.getString("dsh_shell", null)}")
        }
        // M85-D：插件启用/停用/重载后重新解析回合插件（停用选中项 → 安全提示，不偷偷回退）。
        refreshAgentResolution()
        // M85-E：存储/提示词/模型三个角色一起重解析（存储插件换了要换绑路由壳）。
        refreshStorageResolution()
        refreshPromptResolution()
        refreshModelResolution()
    }

    /**
     * M84-E2 / M85-E：按「启用中的插件」重建插件供应商。
     *
     * 先收回上一批插件 provider，再收回内置两条通道（内置模型插件停用后必须真的消失），
     * 然后按开关重注册内置通道，最后按 plugins.json 顺序注册启用插件声明的供应商。
     * 同 id 后注册覆盖先注册 = docs/115 §C 的「同 id 覆盖即替换」。
     */
    private fun syncPluginLlmProviders() {
        pluginProviderIds.forEach { LlmProviders.unregister(it) }
        pluginProviderIds.clear()
        LlmProviders.unregister(MODEL_PROVIDER_DEEPSEEK)
        LlmProviders.unregister(MODEL_PROVIDER_OPENAI)
        registerBuiltinProviders()
        pluginsUi.asSequence().filter { it.enabled }.forEach { record ->
            record.llmProviders.forEach { provider ->
                if (provider.hasStream) {
                    // M84-J：插件自己产出模型回合 —— 请求 dispatch 进它的常驻 runtime。
                    LlmProviders.register(
                        xin.dshaa.core.llm.JsCallbackLlmProvider(
                            id = provider.id,
                            label = provider.label,
                            modelHint = provider.model,
                            onStream = { line -> Log.i("DshProvider", "$line plugin=${record.id}") },
                            call = { requestJson, emit ->
                                pluginResidentRuntime.callProvider(record.id, provider.id, requestJson, onChunk = emit)
                            },
                        ),
                    )
                    pluginProviderIds += provider.id
                    Log.i("DshProvider", "plugin stream provider id=${provider.id} plugin=${record.id}")
                } else {
                    // 空协议 = 跟随宿主当前协议（插件不猜端点形状）；未知值在解析时就已归空
                    val protocol = provider.protocol.ifBlank { settings.protocol }
                    LlmProviders.register(
                        ConfiguredLlmProvider(
                            LlmProviderConfig(
                                id = provider.id,
                                label = provider.label,
                                protocol = protocol,
                                baseUrl = provider.baseUrl,
                                model = provider.model,
                                apiKey = provider.apiKey,
                                ownerPlugin = record.id,
                            ),
                            onStream = { line -> Log.i("DshProvider", "$line plugin=${record.id}") },
                        ),
                    )
                    pluginProviderIds += provider.id
                }
            }
        }
        if (pluginProviderIds.isNotEmpty()) {
            Log.i("DshProvider", "plugin providers=${pluginProviderIds.sorted()} active=${LlmProviders.activeId}")
        }
    }

    override fun dismissPluginNotice() {
        pluginNotice = null
    }

    override fun importPlugin(uri: Uri) {
        if (pluginsBusy) return
        pluginsBusy = true
        pluginNotice = null
        viewModelScope.launch {
            val result = pluginStore.install(uri)
            pluginsUi = pluginStore.list()
            pluginNotice = result.message
            pluginsBusy = false
            // M83：新装的插件可能提供/依赖服务 → 依赖重算（QuickJS 试运行放 IO）
            withContext(Dispatchers.IO) { pluginStore.reconcileServices() }
            pluginsUi = pluginStore.list()
            syncPluginTools()
        }
    }

    override fun setPluginEnabled(id: String, enabled: Boolean) {
        pluginStore.setEnabled(id, enabled)
        pluginsUi = pluginStore.list()
        syncPluginTools()
        // M83 服务容器：启用集合变了要重算依赖——关掉提供者后，依赖它的插件会变成
        // 「缺少服务 X」且不再注册工具；开回来恢复。apply 重跑放 IO 线程。
        viewModelScope.launch {
            val (reloaded, missing) = withContext(Dispatchers.IO) { pluginStore.reconcileServices() }
            pluginsUi = pluginStore.list()
            syncPluginTools()
            Log.i("DshPlugin", "services reconcile: reloaded=$reloaded missingPlugins=$missing")
            pluginNotice = if (missing > 0) {
                "启用状态已更新：$missing 个插件缺少依赖服务（见插件详情）"
            } else {
                "启用状态已更新（重算 $reloaded 个插件的依赖）"
            }
        }
    }

    override fun reloadPlugin(id: String) {
        if (pluginsBusy) return
        pluginsBusy = true
        viewModelScope.launch {
            // M37：重载会重跑 QuickJS 试运行（最长 12 秒），放 IO 线程，别把界面焊住。
            val result = withContext(Dispatchers.IO) { pluginStore.reload(id) }
            // M83：重载后依赖关系也可能变（provide 的值/依赖项），一并重算。
            withContext(Dispatchers.IO) { pluginStore.reconcileServices() }
            pluginsUi = pluginStore.list()
            pluginNotice = result.message
            pluginsBusy = false
            // M84-J：重载 = 代码/配置变了，常驻运行时必须重建（否则 stream/投影还是旧闭包）。
            pluginResidentRuntime.invalidate(id)
            syncPluginTools()
        }
    }

    override fun deletePlugin(id: String) {
        val result = pluginStore.uninstall(id)
        // M84-N：删掉插件时连它的投影 checkpoint 一起清（停用由 sync 清）。
        pluginResidentRuntime.forget(id)
        pluginsUi = pluginStore.list()
        pluginNotice = result.message
        syncPluginTools()
        // M83：删掉提供者后，依赖它的插件要立刻变成「缺少服务 X」。
        viewModelScope.launch {
            withContext(Dispatchers.IO) { pluginStore.reconcileServices() }
            pluginsUi = pluginStore.list()
            syncPluginTools()
        }
    }

    // ---- M67：内置 Operit 包（包管理 → 包） ----

    /** 扫描 `assets/operit-packages/` 下的 .js（第一次进「包」tab 时调用；结果缓存到 [operitPackagesUi]）。 */
    override fun loadOperitPackages() {
        if (operitPackagesBusy || operitPackagesUi.isNotEmpty()) return
        operitPackagesBusy = true
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { operitBundled.load() }
            operitPackagesUi = list
            operitPackagesBusy = false
            Log.i(
                "DshPlugin",
                "内置 Operit 包 ${list.size} 个：可用 ${list.count { it.status == xin.dshaa.ui.shellapi.data.OperitStatus.READY }}、" +
                    "部分可用 ${list.count { it.status == xin.dshaa.ui.shellapi.data.OperitStatus.PARTIAL }}、" +
                    "需要 Key ${list.count { it.status == xin.dshaa.ui.shellapi.data.OperitStatus.NEEDS_ENV }}",
            )
        }
    }

    /**
     * 安装一个内置 Operit 包：现拼 `package.json + <包>.js` 的 zip，走和导入 zip 完全相同的链路
     * （ToolPkg 包装、试运行、工具注册）。网络/命令/设备权限仍要用户到插件页确认。
     */
    override fun installOperitPackage(pluginId: String) {
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
    override fun grantPluginPermission(id: String, raw: String) {
        val result = pluginStore.grant(id, raw)
        pluginNotice = result.message
        pluginsUi = pluginStore.list()
        syncPluginTools()
    }

    /** 插件详情里保存配置（M30）：JSON 对象文本；空文本 = 恢复默认。 */
    override fun savePluginConfig(id: String, jsonText: String) {
        val result = pluginStore.setConfig(id, jsonText)
        pluginNotice = result.message
        pluginsUi = pluginStore.list()
        syncPluginTools()
    }

    // ---- 技能（M19） ----

    /** 技能扫描的根目录（设置页展示，便于用户知道该往哪放文件）。 */
    override val skillRoots: List<SkillRoot> get() = skills.roots()

    /** App 私有目录（= DSH home）/ 工作区路径：设置页把长路径缩成 `files/…`、`workspace/…`。 */
    override val filesDirPath: String get() = getApplication<Application>().filesDir.absolutePath
    override val workspacePath: String get() = workspace.absolutePath

    /** 重新扫盘（设置页下拉/进入时调用；引擎每个 step 也会现扫）。 */
    override fun refreshSkills() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { skills.snapshot() }
            skillsUi = snapshot.skills
            skillsComplete = snapshot.complete
            skillsWarnings = withContext(Dispatchers.IO) { skills.lastWarnings() }
        }
    }

    /** 技能开关（M81）：关掉后不再出现在目录/工具/斜杠命令里。 */
    override fun setSkillEnabled(name: String, enabled: Boolean) {
        settings.setSkillEnabled(name, enabled)
        skillNotice = if (enabled) "已启用技能 $name" else "已关闭技能 $name"
        refreshSkills()
    }

    /**
     * 删除用户导入的技能（M81）：内置技能只给「关」，不给删——删了下次启动
     * [BundledSkills.ensureInstalled] 又会装回来，留着按钮只会让人困惑。
     */
    override fun deleteSkill(skill: SkillSummary) {
        if (BundledSkills.isBundled(skill.name)) {
            skillNotice = "内置技能不能删除，可以关闭"
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(skill.path)
                    val parent = file.parentFile
                    val deleted = file.delete()
                    // 目录型技能：SKILL.md 删掉后父目录空了就一起删
                    if (deleted && parent != null && parent.name == skill.name) {
                        if (parent.listFiles()?.isEmpty() == true) parent.delete()
                    }
                    deleted
                }.getOrDefault(false)
            }
            skillNotice = if (ok) "已删除技能 ${skill.name}" else "删除失败：${skill.name}"
            refreshSkills()
        }
    }

    /**
     * 内置包开关（M81）：开 = 没装过就先装再启用；关 = 只禁用，不删文件（下次开回来还是原配置）。
     */
    override fun setOperitPackageEnabled(pluginId: String, enabled: Boolean) {
        val installed = pluginsUi.firstOrNull { it.id == pluginId }
        if (enabled && installed == null) {
            installOperitPackage(pluginId)
            return
        }
        if (installed == null) return
        pluginStore.setEnabled(pluginId, enabled)
        pluginsUi = pluginStore.list()
        pluginNotice = if (enabled) "已启用 ${installed.name}" else "已关闭 ${installed.name}"
        syncPluginTools()
    }

    /**
     * 包管理页「导入技能」（Operit 技能 Tab 的导入入口）：
     * 选一个 SKILL.md → 校验 frontmatter → 写到 `files/skills/<name>.md` → 重扫。
     *
     * 写平铺 .md 而不是目录型技能：DSH 的文件系统 provider 两种都认，单文件最好搬。
     */
    override fun importSkill(uri: Uri) {
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

    override fun dismissSkillNotice() {
        skillNotice = null
    }

    /**
     * 这个 step 起点要不要给模型补一条技能目录（上游 `dsh-tool-skill` 的 pre-step）：
     * 摘要没变 → null（请求体不动）；变了 → `source.kind:"skill-catalog"` 的 user 消息。
     */
    private fun skillCatalogNotice(session: SessionSession): UserNotice? {
        val previous = runCatching { lastSkillCatalogSource(session) }.getOrNull()
        val snapshot = runCatching { skills.snapshot() }.getOrElse { return null }
        // M81：关掉的技能不进模型目录（快照里仍保留 enabled=false 供包管理展示）
        val enabledSnapshot = snapshot.copy(skills = snapshot.skills.filter { it.enabled })
        val notice = SkillCatalog.decide(enabledSnapshot, previous) ?: return null
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
                    promptPack.androidApiTools().filter { it.name in offeredToolNames() } + pluginApiTools +
                        pluginDevTools.apiTools().filter { it.name in offeredToolNames() }
                },
                systemPrompt = {
                    buildSystemPrompt(
                        PromptRequest(
                            model = settings.model,
                            provider = providerLabel(),
                            tools = offeredToolNames(),
                            personaPrefix = personaPrefixText(),
                            personaSuffix = settings.assistantSystem,
                            extraSections = pluginPromptSections(),
                            variables = pluginPromptVariables(),
                        ),
                    )
                },
                runtimeContext = { runtimeContextText(record.depth, cwd) },
                stream = { request, config -> llmStream(request, config) },
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
                hooks = turnHooks(state),
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
    override fun resolveApproval(allow: Boolean, always: Boolean) {
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
    override fun resolveQuestions(answers: List<xin.dshaa.core.tools.AskAnswer>) {
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
    override suspend fun fetchModels(): Result<List<String>> = withContext(Dispatchers.IO) {
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
    override suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val cfg = llmConfig()
        // 插件供应商可能自带 key：宿主 key 为空时不要抢在通道之前报错，让通道自己给准确原因。
        if (cfg.apiKey.isBlank() && LlmProviders.get(settings.llmProviderId) == null) {
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
        val events = llmStream(request, cfg)
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
        // 2026-11-12：长任务「自己停」的取证——Activity 被销毁时 viewModelScope 会取消在跑的轮次，
        // 这条日志能把它和「用户主动停止」区分开。
        Log.i("DshTurn", "viewmodel cleared; running turns will be cancelled")
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
        runCatching { remote.close() }
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
     * M84-E1：是否有**启用中**的插件要求抑制宿主的运行时上下文快照
     * （`ctx.systemPrompt.suppressRuntimeContext()`）。插件自己的 `systemPrompt.context`
     * 不算 runtime context，仍照常注入。
     */
    private fun pluginSuppressRuntimeContext(): Boolean =
        pluginsUi.any { it.enabled && it.suppressRuntimeContext }

    /**
     * 运行时上下文快照（逐字对齐上游模板；Android 固定 danger-full-access + 审批策略按设置映射）。
     * 子代理额外追加：固定权限范围自述 + 深度用尽提示（上游 `SUBAGENT_DELEGATION_CONTEXT` /
     * `tool:subagent:depth` 两个 context contribution）。
     * M84-E1：任一启用插件调了 `suppressRuntimeContext()` → 宿主的这段快照整段不注入。
     */
    private fun runtimeContextText(depth: Int = 0, cwd: File = toolContext.cwd): String {
        val suppressed = pluginSuppressRuntimeContext()
        val approval = if (settings.approvalMode == "auto") {
            "Approval prompts are disabled in this session: actions that require approval are rejected automatically — " +
                "do not request sandbox escalation (do not set `sandbox_permissions`)."
        } else {
            "Approval policy: ask. Operations that require approval may ask through the configured answerers; " +
                "without an available answerer, the request fails closed."
        }
        return buildString {
            if (!suppressed) {
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
            }
            // 插件上下文（ctx.systemPrompt.context）就在这条 user 快照末尾（M71）；
            // suppressRuntimeContext 只压宿主那段，不压插件自己的 context。
            val pluginContext = pluginContextText()
            if (pluginContext.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
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
