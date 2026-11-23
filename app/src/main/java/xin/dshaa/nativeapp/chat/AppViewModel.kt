package xin.dshaa.nativeapp.chat

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import xin.dshaa.core.engine.ToolMiddleware
import xin.dshaa.core.engine.ToolMiddlewareChain
import xin.dshaa.core.engine.RequestMiddleware
import xin.dshaa.core.engine.RequestMiddlewareChain
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
import xin.dshaa.core.primitives.PrimitiveArgs
import xin.dshaa.core.primitives.PrimitiveBridgeJson
import xin.dshaa.core.primitives.PrimitiveCaller
import xin.dshaa.core.primitives.PrimitiveCatalog
import xin.dshaa.core.primitives.PrimitiveHost
import xin.dshaa.core.primitives.PrimitiveIds
import xin.dshaa.core.primitives.PrimitiveRange
import xin.dshaa.core.primitives.PrimitiveResult
import xin.dshaa.core.tools.primitives.PrimitiveTools
import xin.dshaa.core.model.ToolCall
import xin.dshaa.nativeapp.data.PluginShellHost
import xin.dshaa.nativeapp.data.PluginToolMiddleware
import xin.dshaa.nativeapp.data.PluginRequestMiddleware
import xin.dshaa.nativeapp.data.TOOL_MIDDLEWARE_TIMEOUT_MS
import xin.dshaa.core.parity.ParityJson
import xin.dshaa.core.plugins.PluginRecord
import xin.dshaa.core.plugins.PluginPrimitiveBridge
import xin.dshaa.core.plugins.PluginPromptSection
import xin.dshaa.core.plugins.PluginPermissions
import xin.dshaa.core.plugins.PluginMiddlewareSwitch
import xin.dshaa.core.plugins.PluginRoute
import xin.dshaa.core.plugins.PluginPreset
import xin.dshaa.core.plugins.PresetCodec
import xin.dshaa.core.plugins.ContainerKind
import xin.dshaa.core.plugins.ContainerSpec
import xin.dshaa.core.plugins.PluginOwnership
import xin.dshaa.core.plugins.PluginPackage
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
import xin.dshaa.nativeapp.device.floating.FloatingSlots
import xin.dshaa.core.session.SessionStore
import xin.dshaa.core.session.SessionStores
import xin.dshaa.core.session.TrashedSession
import xin.dshaa.core.session.SessionSession
import xin.dshaa.core.session.PendingSessionEvent
import xin.dshaa.core.session.SessionAppendDecision
import xin.dshaa.core.session.SessionAppendGate
import xin.dshaa.core.session.SessionAppendVetoed
import xin.dshaa.core.session.SessionWriteHooks
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
import xin.dshaa.nativeapp.data.PluginRoleRefs
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
import xin.dshaa.nativeapp.node.NodePluginHost
import xin.dshaa.nativeapp.node.NodePluginInstaller
import xin.dshaa.ui.shellapi.NodeBootProgressUi
import xin.dshaa.ui.shellapi.NodeHostManifestUi
import xin.dshaa.ui.shellapi.NodeHostPluginUi
import xin.dshaa.ui.shellapi.NodeHostStatusUi
import xin.dshaa.ui.shellapi.PluginImportDraftUi
import xin.dshaa.ui.shellapi.NativeShellHost
import xin.dshaa.ui.shellapi.SlotRegistry
import xin.dshaa.ui.shellapi.PluginExtensionInput
import xin.dshaa.ui.shellapi.PluginExtensionRows
import xin.dshaa.ui.shellapi.PluginExtensionUi
import xin.dshaa.ui.shellapi.PluginLlmExtensionInput
import xin.dshaa.ui.shellapi.PluginSlotInput
import xin.dshaa.ui.shellapi.PluginStorageExtensionInput
import xin.dshaa.ui.shellapi.PluginToolExtensionInput
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
import xin.dshaa.ui.shellapi.chat.CONTINUE_TRUNCATED_PROMPT
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
import xin.dshaa.ui.shellapi.chat.SharedPreferencesTruncationResumePreferences
import xin.dshaa.ui.shellapi.chat.SubagentUi
import xin.dshaa.ui.shellapi.chat.TruncationKind
import xin.dshaa.ui.shellapi.chat.TruncationResumeStore
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
    // M88-I：中间件总开关状态查询是纯只读
    "plugin_dev_middleware_state",
    // docs/190-B：Node 宿主的 4 个查询类工具（宿主关着/失败时也要能看状态与诊断）
    "nodehost_status", "nodehost_plugins", "nodehost_logs", "nodehost_doctor",
) + PrimitiveCatalog.approvalFreeToolNames // P1：免审批的原语（只读/会话/设置；permission.set 与 tool.call 仍要审批）

/** `tool.call` 不允许嵌套调用的工具（会自己再进审批/回合，或就是原语层本身）。 */
private val NESTED_TOOL_DENYLIST = setOf(
    "ask_user_question",
    "exit_plan_mode",
    "subagent",
    "subagent_fork",
    "workflow",
)

/** `tool.call` 回给模型的工具结果上限（防止把超大输出再塞回上下文）。 */
private const val MAX_NESTED_TOOL_TEXT = 16_000

/** 插件桥面同步调用原语的超时（插件 JS 线程会一直阻塞到结果返回）。 */
private const val PLUGIN_PRIMITIVE_TIMEOUT_MS = 120_000L

/** M85-D：第三方 JS 回合的最长等待（插件自己应尽快返回；超时按失败收尾，UI 可在 1.5s 后强制停）。 */
private const val AGENT_TURN_TIMEOUT_MS = 120_000L

/** M85-E：第三方提示词实现的最长等待（拼装要很快；超时就回退内置拼装并亮提示）。 */
private const val PROMPT_ASSEMBLE_TIMEOUT_MS = 8_000L

/**
 * Node 宿主「随应用后台启动」的延迟：原生插件体系和首帧先就位，再起 node 进程，
 * 免得开机时 75MB RSS + 1.5s 解压抢 CPU（用户手动开开关时不等）。
 */
private const val NODE_HOST_AUTOSTART_DELAY_MS = 1_500L

/** docs/190-C：Node 启动小卡片在 READY 后停留 1 秒再自动消失（用户拍板）。 */
private const val NodeBootReadyVisibleMs = 1_000L

/**
 * docs/190-B：上次启动以 Failed 结束 → 本次开机跳过自动启动并显示横幅。
 * 只记两个键（布尔 + 原因），Ready / 用户或 AI 主动启动时清掉。
 */
private const val KEY_NODE_HOST_LAST_FAILED = "node_host_last_failed"
private const val KEY_NODE_HOST_LAST_FAILED_REASON = "node_host_last_failed_reason"

/** docs/190-B：Node 插件连续加载失败计数（JSON：module → 次数），用于自动隔离。 */
private const val KEY_NODE_PLUGIN_FAIL_COUNTS = "node_plugin_fail_counts"

/** docs/188 P4：统一导入的 zip 上限（与插件页展示的上限一致；原生/Node 都先落这个缓存）。 */
private const val IMPORT_MAX_BYTES = 64L * 1024 * 1024

/**
 * P3-c：一条 Node 斜杠命令的最长等待。命令是用户主动敲的、不产生模型轮次，
 * 给 30s（与宿主侧默认一致）；超时以「失败文案」显示，不静默。
 */
private const val NODE_COMMAND_TIMEOUT_MS = 30_000L

/**
 * 应用级状态：设置 + 会话仓库 + 当前聊天 + 工具循环。
 * 运行时暂时在 ViewModel；M2 后挪到前台服务持有的 ChatRuntimeHolder（01 §五.4）。
 */
/** M87-C：插槽动作是用户主动点击，给比中间件宽松的预算（仍然有上限，超时只记诊断）。 */
private const val SLOT_ACTION_TIMEOUT_MS = 500L

/** M87-E：会话写入钩子在同步写路径上，单插件预算 50ms（超时放行 + 诊断，不能拖住落库）。 */
private const val HOOK_TIMEOUT_MS = 50L

/**
 * 液态玻璃界面壳的壳 id（`docs/187` §4.2）：`dsh_shell = liquid`。
 *
 * 它不是插件 id（插件壳是 `插件id#壳id` 形式），`resolveShellTarget` 对它返回 null，
 * 由 [AppViewModel.liquidUiEnabled] 单独表达「内置外观选玻璃」。
 */
private const val LIQUID_SHELL_ID = "liquid"

class AppViewModel(app: Application) : AndroidViewModel(app), NativeShellHost {
    // ---- 插件（M26：本地导入 + 试运行） ----
    //
    // 状态必须声明在 init 之前：init 里的调试导入（`--es dsh_plugin_zip`）会立刻读写它们。
    // 存储：`files/plugins/<id>/` + `files/plugins.json`。

    private val pluginStore = PluginStore(app)

    /**
     * M88-E（docs/183 §5）：插件操作审计（AI 授予/撤销/改配置/选角色）。
     * 文件 `files/plugin-audit.jsonl`，512KB 轮转保留 1 份；插件详情展示最近 5 条。
     */
    private val pluginAuditLog = xin.dshaa.nativeapp.data.PluginAuditLog(
        xin.dshaa.nativeapp.data.PluginAuditLog.fileIn(app.filesDir),
    )

    /** 本进程最近一次 AI 授予（插件 id → 权限），供横幅「撤销」使用。 */
    private var lastAiGrant: Pair<String, Set<String>>? = null

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
                // docs/187 §五：`builtin.shell.whale` / `builtin.floating.demo` 已从包里删掉，
                // 老设备的记录与目录在这里清掉（不再安装任何内置插件）。
                xin.dshaa.nativeapp.data.BundledPlugins.purgeObsolete(getApplication(), pluginStore)
                BundledSkills.ensureInstalled(getApplication())
                pluginStore.list()
            }
            pluginsUi = records
            onPhase("registry", "登记工具与角色…")
            initializeCoreRegistry()
            syncPluginTools()
            // 命令行工具箱（bash/curl/CA）在首屏之后再解压：它只影响 `bash` 工具，不挡启动。
            viewModelScope.launch {
                val result = withContext(Dispatchers.IO) { toolchain.ensureInstalled() }
                toolchainStatus = result.getOrElse { e ->
                    xin.dshaa.nativeapp.toolchain.Toolchain.Status(
                        installed = false,
                        bashPath = null,
                        curlPath = null,
                        libDir = toolchain.libDir.absolutePath,
                        caFile = toolchain.caFile.absolutePath,
                        symlinkOk = false,
                        probe = "",
                        error = e.message ?: e.javaClass.simpleName,
                    )
                }
            }
            runtimeCoreReady = true
            shellActive = resolveShellTarget(prefs.getString("dsh_shell", null))
            // docs/187 §4：`dsh_shell = liquid` 表示「内置外观选液态玻璃」，不走插件壳解析。
            liquidUiEnabled = prefs.getString("dsh_shell", null)?.trim() == LIQUID_SHELL_ID
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
            // P4：Node 开关关着也要把清单/状态读一次，否则 Node 分区会显示 0/空白（真机 bug）。
            // docs/190-B：上次以 Failed 结束 → 本次开机不自动启动，改为横幅 + FAILED 状态（App 其余照用）。
            if (settings.nodePluginHostEnabled) loadNodeHostFailureState()
            refreshNodeHostDiagnostics()
            if (settings.nodePluginHostEnabled) {
                if (nodeHostSkippedAutoStart) {
                    nodeHostStateText = "已跳过自动启动（上次启动失败）"
                    nodeBootStartedAt = System.currentTimeMillis()
                    val reason = nodeHostLastFailureReason ?: "未知"
                    // 卡片失败态会自动展开日志；这里填上原因，别让「查看日志」是空的。
                    nodeHostLogs = listOf("上次启动失败，已跳过自动启动。原因：$reason")
                    nodeBootProgress = NodeBootProgressUi(
                        phase = "failed",
                        percent = 0,
                        detail = "上次启动失败，已跳过自动启动",
                        error = "上次启动失败，已跳过自动启动。原因：$reason",
                        elapsedMs = 0,
                        startedAtMs = nodeBootStartedAt,
                        visible = true,
                    )
                    Log.i(
                        "DshNodeHost",
                        "auto-start skipped after previous failure: " +
                            nodeHostLastFailureReason.orEmpty().take(240),
                    )
                } else {
                    attachNodeHost(delayMs = NODE_HOST_AUTOSTART_DELAY_MS)
                }
            }
            // P3-d：预设是纯数据，启动时读一次（不依赖 Node 宿主，关着也能用）。
            refreshPresets()
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

    // ---- M87-F：插件页「扩展点」诊断 / 中间的命中统计 ----
    //
    // 统计只进内存（重启清零），key = "pluginId\u0000phase"；页签打开时现算快照。
    // 不假装有持久化：没有 dispatch 过就是「尚未命中」。
    private data class ExtensionStat(
        var hits: Long = 0,
        var denies: Long = 0,
        var errors: Long = 0,
        var lastMs: Long = 0,
        var lastError: String? = null,
        var lastInHash: String? = null,
        var lastOutHash: String? = null,
    )

    private val extensionStats = java.util.concurrent.ConcurrentHashMap<String, ExtensionStat>()

    override var pluginExtensionsUi by mutableStateOf<List<PluginExtensionUi>>(emptyList())
        private set

    override val pluginMiddlewareEnabled: Boolean get() = settings.pluginMiddlewareEnabled

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
     * docs/190-E：鲸歌内置提示词规则是否启用（`builtin.prompt.core`，默认开）。
     * 关掉只影响打包的 sections/plan:policy，不影响用户人设/系统提示词/用户插件段/运行时上下文。
     */
    override fun builtinPromptCoreEnabled(): Boolean {
        // 和 builtinScreenEnabled 一样读一次 Compose 快照，插件页开关后下一轮请求立刻生效。
        builtinPluginsUi.size
        return builtinStore.isEnabled(xin.dshaa.nativeapp.data.BuiltinPlugins.PROMPT_CORE)
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
        // P1（docs/185 §四）：插件桥面与 AI 工具面共用同一个 PrimitiveHost（见 AppPrimitiveHost）。
        primitiveBridge = pluginPrimitiveBridge(),
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
            // M87-A：常驻运行时起来了才知道这个插件注册了哪些工具中间件，重建链。
            rebuildPluginToolChain()
            // M87-B：请求/提示词中间件同样要在 runtime 起来后重建（闭包在 runtime 里）。
            rebuildPluginRequestChain()
            // M87-C：插槽动作要 dispatch 进常驻 runtime；起来后再同步一次（幂等）。
            syncPluginSlots()
        },
        // M85-E：只读导出器用的宿主导出原语（只读 builtin.storage.jsonl，不碰活动存储）。
        builtinSessionExport = { method, payload -> exportBuiltinSessions(method, payload) },
        // M87-F：中间件总开关关闭时不再投递 session/event 观察与 after-load。
        hooksEnabled = { PluginMiddlewareSwitch.enabled },
        onAfterLoad = { pluginId, ok, error, ms ->
            recordExtensionDispatch(pluginId, "storage.afterLoad", ok, error, ms)
        },
    )

    /** 当前由插件注册进工具表的名字（刷新时先注销这一批，再按最新清单注册）。 */
    private val pluginToolNames = mutableSetOf<String>()

    /**
     * M87-A：工具中间件链快照（docs/181 §3.2）。
     * 插件启用/停用/重载、或常驻运行时起来拿到注册元数据后重建；引擎每次请求/工具调用现取。
     */
    @Volatile
    private var pluginToolChain: ToolMiddlewareChain = ToolMiddlewareChain.EMPTY

    /**
     * M87-B：请求/提示词中间件链快照（docs/181 §3.3）。
     * 与工具链同一热换语义：插件启用/停用/重载或常驻运行时起来后重建，引擎每次请求/增量现取。
     */
    @Volatile
    private var pluginRequestChain: RequestMiddlewareChain = RequestMiddlewareChain.EMPTY

    /**
     * M87-C：插件插槽注册表（原生壳在聊天输入条/工具卡/设置首页读取）。
     * 只放「启用 + ui:slot 已授权」的声明；停用/删除/重载由 [syncPluginSlots] 立即摘掉。
     */
    val pluginSlotRegistry = SlotRegistry()

    override val slotRegistry: SlotRegistry get() = pluginSlotRegistry

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

    /**
     * M88-C（docs/183 §3）：能力进模型工具表——`capability {id,args?}` + `notify {title?,body}`。
     * 归内置组 `builtin.capability`，随该组开关注册/注销（见 [syncBuiltinTools]）。
     */
    private val capabilityTools = xin.dshaa.nativeapp.device.tools.CapabilityTools(
        invoke = { id, args -> capabilityHost.invoke(id, args) },
        availableIds = { capabilityHost.ui().filter { it.available }.map { it.id } },
        notificationsAllowed = { capabilityHost.notificationsAllowed() },
    )

    /** 插件页底部最近一次能力调用的结果（null = 还没调用过）。 */
    override var capabilityNotice by mutableStateOf<String?>(null)
        private set
    /** 当前请求可用的插件工具 schema（每次请求现取，随 `ToolRegistry` 同步刷新）。 */
    @Volatile
    private var pluginApiTools: List<ApiTool> = emptyList()

    /**
     * Node 插件宿主（docs/185 的 P2）：独立进程 + 本地 WS 桥，跟原生插件体系并存。
     *
     * 默认关（[xin.dshaa.ui.shellapi.data.SettingsStore.nodePluginHostEnabled]）；打开后才解压运行时、
     * 起进程、把宿主 manifest 里的工具并进 [registry]（名字统一加 `node_` 前缀，跟原生插件不撞）。
     */
    private val nodePluginHost: NodePluginHost by lazy {
        NodePluginHost(app, approvalMode = { settings.approvalMode })
    }

    /** Node 侧工具 schema（每次宿主 Ready 后重算）。 */
    @Volatile
    private var nodeApiTools: List<ApiTool> = emptyList()

    private val nodeToolNames = LinkedHashSet<String>()

    private var nodeHostJob: Job? = null

    /** 已经拉过提示词段的宿主 pid（同 pid 不重复拉）。 */
    private var nodePromptPid = -1

    /** 已经拉过供应商/命令清单的宿主 pid（同 pid 不重复拉）。 */
    private var nodeRegistrationsPid = -1

    private var nodePromptJob: Job? = null

    /** 插件页展示的宿主状态（Extracting/Ready/Failed 的人话版本）。 */
    override var nodeHostStateText by mutableStateOf("")
        private set

    /** 插件页展示：宿主里已加载的模块 + 各自注册的工具。 */
    override var nodeHostPlugins by mutableStateOf<List<NodeHostPluginUi>>(emptyList())
        private set

    /** 插件页展示：宿主日志尾巴。 */
    override var nodeHostLogs by mutableStateOf<List<String>>(emptyList())
        private set

    /** docs/190-C：全局 Node 启动小卡片（任何页面可见；READY 1s 后自动隐藏，FAILED 保持）。 */
    override var nodeBootProgress by mutableStateOf(NodeBootProgressUi())
        private set

    private var nodeBootStartedAt = 0L
    private var nodeBootSession = 0L
    private var nodeBootDismissed = false
    private var nodeBootHideJob: Job? = null

    /** docs/190-B：上次以 Failed 结束 → 本次开机跳过自动启动（横幅里说明原因）。 */
    private var nodeHostSkippedAutoStart = false
    private var nodeHostLastFailureReason: String? = null

    /** docs/190-B：自动隔离只跑一个 job（Ready 状态可能连续触发）。 */
    private var nodeAutoQuarantineJob: Job? = null

    /**
     * P4：Node 宿主状态的人话版本（OFF/IDLE/EXTRACTING/STARTING/READY/FAILED/STOPPED）。
     * 关宿主后 Node 分区显示 OFF，不留空白；失败时 detail/hint 都可见。
     */
    override var nodeHostStatus by mutableStateOf(
        NodeHostStatusUi(
            code = "off",
            title = "已关闭（OFF）",
            detail = "Node 宿主没开：Node 插件不会加载；原生插件、聊天和工具不受影响。",
            hint = "打开开关后会在后台起 Node（首次要解压约 25MB 运行时）。",
        ),
    )
        private set

    /** P4：清单里登记过的 Node 插件（宿主关着/没加载也要能看见）。 */
    override var nodeHostManifest by mutableStateOf<List<NodeHostManifestUi>>(emptyList())
        private set

    /** P4：统一导入的待确认草稿（选择 zip 后、真正安装前）。 */
    override var pluginImportDraft by mutableStateOf<PluginImportDraftUi?>(null)
        private set

    /** 导入草稿对应的缓存 zip；确认/取消后删除。 */
    private var pendingPluginImport: File? = null

    override val pluginImportLimitsText: String
        get() = "原生：${PluginStore.limitsText()}｜Node：${NodePluginInstaller.limitsText()}"

    /** P3-d：角色页「预设」区的可选列表（纯数据，点一下套用/清除）。 */
    override var presetTargetsUi by mutableStateOf<List<xin.dshaa.core.plugins.PluginPreset>>(emptyList())
        private set

    /** 当前套用中的预设 id（null = 没套用）。 */
    override var activePresetTargetUi by mutableStateOf<String?>(null)
        private set

    /** 预设操作的一句话结果（套用/保存/删除/清除）。 */
    override var presetNoticeUi by mutableStateOf<String?>(null)
        private set

    /**
     * Node 宿主里的插件往系统提示注册的段/上下文（`ctx.systemPrompt.section/context`）。
     * 宿主 Ready 后拉一次，导入/重载后重拉；拼进 [pluginPromptSections] / [pluginContextText] 同一张表。
     */
    @Volatile
    private var nodePromptSections: List<PluginPromptSection> = emptyList()

    @Volatile
    private var nodePromptContexts: List<PluginPromptSection> = emptyList()

    @Volatile
    private var nodePromptVariables: Map<String, String> = emptyMap()

    // ---- P3-b / P3-c / P3-d（docs/187 §9.2）：Node 供应商 / 命令 / 预设 ----

    /** 插件登记的模型供应商（宿主 Ready 后从 hello/`llm.list` 拿；关宿主即清空）。 */
    @Volatile
    private var nodeLlmSpecs: List<xin.dshaa.nativeapp.node.NodeLlmProviderSpec> = emptyList()

    /** 由 Node 宿主注册进 [LlmProviders] 的 provider id（收回这一批时用）。 */
    private val nodeProviderIds = LinkedHashSet<String>()

    /** 不可用的 Node 供应商 id → 人话原因（callback-only / 宿主未就绪）。 */
    private val nodeProviderNotes = LinkedHashMap<String, String>()

    /** Node 插件注册的斜杠命令（面板/输入框命中都查这张表）。 */
    @Volatile
    private var nodeCommandSpecs: List<xin.dshaa.nativeapp.node.NodeCommandSpec> = emptyList()

    /** 预设（纯数据）：落 `files/node/data/presets.json`，不进 session.v4。 */
    private val presetStore by lazy { PresetStore(getApplication<Application>().filesDir) }

    /** 当前套用的预设 id（null = 没套用）；套用时按预设收窄工具/提示词段。 */
    @Volatile
    private var activePresetId: String? = null

    /** 套用中的预设快照（避免每轮都读盘/解析）。 */
    @Volatile
    private var activePreset: PluginPreset? = null

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

    /** H1：截断后「继续生成」的按会话标记（prefs，不进会话 JSONL）。 */
    private val truncationResume = TruncationResumeStore(SharedPreferencesTruncationResumePreferences(prefs))

    /**
     * M85-A：当前由插件提供的整壳（null = 原生壳）。
     *
     * 选择规则（冷启动读 `dsh_shell` 调试项）：`native`/空 = 原生；`插件id` = 该插件的第一个壳；
     * `插件id#壳id` = 指定壳。插件必须启用、有 `ui` 授权、常驻运行时能起来。
     */
    var shellActive by mutableStateOf<PluginShellHost.Target?>(null)
        private set

    /**
     * docs/187 §4：液态玻璃界面壳开关（默认关 = 原生界面，与 0.3.5 一致）。
     *
     * 它和 [shellActive] 正交：液态玻璃是**内置外观**（`dsh_shell = liquid`），不是插件壳；
     * 选插件壳时这里恒为 false（插件壳自己不画玻璃）。冷启动从 prefs 恢复。
     */
    override var liquidUiEnabled by mutableStateOf(false)
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
            xin.dshaa.core.tools.ToolResult.err("Plugin tool is not enabled or not connected: $pluginId / $toolName")
        } else {
            registry.execute(toolName, args, ctx)
        }
    }, testCapability = { id, args ->
        val result = capabilityHost.invoke(id, args.toString())
        if (result.ok) xin.dshaa.core.tools.ToolResult.ok(result.valueJson ?: "Call succeeded")
        else xin.dshaa.core.tools.ToolResult.err(result.error ?: "Capability call failed")
    }, onDisabled = { id ->
        // 0.3.4（用户第 5/6 条）：AI 停用插件也要清角色选择、静默回退内置——和 UI 开关同一条路径。
        withContext(Dispatchers.Main) {
            pluginsUi.firstOrNull { it.id == id }?.let { clearRoleSelection(it) }
        }
    }, delete = { id, force ->
        deletePluginInternal(id, force)
    }, audit = { action, target, detail ->
            pluginAuditLog.append(xin.dshaa.nativeapp.data.PluginAuditLog.ACTOR_AI, action, target, detail)
        },
        lastAiAction = { id -> pluginAuditLog.lastAiAction(id) },
        selectRole = { role, value ->
            // M88-E：六个角色复用现有选择路径（失败原因由各 select* 写 pluginNotice）。
            when (role) {
                "shell" -> selectShell(value)
                "loading" -> selectLoading(value)
                "agent" -> setActiveAgent(value)
                "storage" -> setActiveStorage(value)
                "prompt" -> setActivePrompt(value)
                "model" -> setActiveModel(value)
                else -> false
            }
        },
        onAiGranted = { id, detail ->
            lastAiGrant = id to detail.split('、').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            pluginNotice = "AI 已授予 $id：$detail · 撤销"
        },
        // M88-I：AI 可按需查/开关「插件中间件总开关」（查只读、开关走写工具审批与审计）。
        middlewareEnabled = { settings.pluginMiddlewareEnabled },
        setMiddlewareEnabled = { enabled -> setPluginMiddlewareEnabled(enabled) },
    )

    /**
     * docs/190-B：Node 宿主自治工具（`nodehost_*`）。
     *
     * 静态注册（不走 NodeToolSurface）：宿主关着/失败时正是要靠它看状态、看日志、修宿主。
     * 写类工具走现有的 `awaitApproval`（不在 `READ_ONLY_TOOLS` 里），并统一写 node-audit。
     */
    private val nodeHostTools by lazy {
        xin.dshaa.nativeapp.node.NodeHostTools(
            host = nodePluginHost,
            isHostEnabled = { settings.nodePluginHostEnabled },
            setHostEnabled = { enabled -> setNodePluginHostEnabled(enabled) },
            restartHost = { restartNodeHostFromTool() },
            reinstallRuntime = { reinstallNodeRuntimeFromTool() },
            refreshHost = { refreshNodeHostFromTool() },
            onNotice = { text -> pluginNotice = text },
            workspace = { workspace },
            approvalMode = { settings.approvalMode },
            auditTail = { max -> nodePluginHost.auditTail(max) },
        )
    }

    private val registry: ToolRegistry =
        ToolRegistry(
            androidDeviceTools.tools + terminalTools.tools + memoryTools.tools + pluginDevTools.tools +
                nodeHostTools.tools + capabilityTools.tools + PrimitiveTools.all,
        )

    /**
     * M84-F2：不属于 [xin.dshaa.core.tools.BuiltinToolCatalog] 的 app 工具（设备/终端/记忆/能力），
     * 也按内置插件组开关——关掉组 = 从 registry 注销，空壳 tools[] 才真的是 0。
     */
    private val appToolsByName =
        (androidDeviceTools.tools + terminalTools.tools + memoryTools.tools + pluginDevTools.tools +
            nodeHostTools.tools + capabilityTools.tools)
            .associateBy { it.name }

    /**
     * docs/187 §5.2：内置悬浮窗插口示例（`builtin.floating.demo`）已删除。
     * [FloatingSlots] 注册表保留——以后插件仍可注册插口，只是不再随包带示例。
     */

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
        // docs/187 §6.5：`http_request` → `curl` 的旧会话别名，跟着 builtin.web 组一起开/关，
        // 但只进 ToolRegistry 的别名表（模型看不见，apiTools 里没有）。
        val webEnabled = builtinStore.isEnabled("builtin.web")
        BuiltinToolCatalog.aliases().forEach { alias ->
            if (webEnabled) {
                if (alias.name !in registry.aliasNames) registry.registerAlias(alias)
            } else {
                registry.unregisterAlias(alias.name)
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
        // M87-F：先按设置恢复插件中间件总开关（关 = 写入钩子/两条链/事件投递全旁路）。
        syncPluginMiddlewareSwitch()
        // M87-E：会话写入钩子（before-append / session-event veto）先装，第一次建会话就生效。
        installSessionWriteHooks()
        registerBuiltinProviders()
        DebugProviders.install(prefs, xin.dshaa.nativeapp.BuildConfig.DEBUG)
        syncBuiltinTools()
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
     * 打包的命令行工具箱（`docs/187` §六）：真 bash + 真 curl + CA + PATH 桥。
     * `bash` 工具经 [ToolContext.shell] 拿它；`ensureInstalled()` 在运行时初始化后后台跑，
     * 失败不影响启动（BashTool 会退回 `/system/bin/sh`，状态留在 [toolchainStatus] 里）。
     */
    private val toolchain = xin.dshaa.nativeapp.toolchain.Toolchain(getApplication())

    /** 工具箱自检结果（设置/关于页展示；null = 还没跑过）。 */
    var toolchainStatus by mutableStateOf<xin.dshaa.nativeapp.toolchain.Toolchain.Status?>(null)
        private set

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
        shell = toolchain,
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
     *
     * [includePrimitives]：P1 的 19 个原语工具只给根话题；子代理会话拿不到
     * （它们改的是父话题的模型/话题，不能由子代理发起）。
     */
    private fun offeredToolNames(includePrimitives: Boolean = true): Set<String> {
        // M83 阶段 2：内置插件关掉后，它的工具不进 tools[]（模型看不到也调不到）。
        var names = registry.names.filter { builtinStore.toolEnabled(it) }.toSet()
        if (!includePrimitives) names = names - PrimitiveCatalog.toolNames
        val filtered = if (settings.webSearchEnabled) names else names - "web_search"
        // P3-d：套用预设时按预设的工具名单收窄（不在名单里的工具既不发 schema 也不进提示词）。
        val presetTools = activePreset?.tools.orEmpty()
        return if (presetTools.isEmpty()) filtered else filtered intersect presetTools.toSet()
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
            // M88-H：不按文件大小拒绝。先把 Uri 流式落到临时文件（分块写，不整读进内存），
            // 再交给附件层流式归一化；临时文件处理完即删。
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    val temp = copyUriToTemp(uri)
                    try {
                        savePendingImageFile(state, temp, declared, name)
                    } finally {
                        temp.delete()
                    }
                }.getOrElse { "无法读取这张图片：${it.message ?: it.javaClass.simpleName}" }
            }
            state.attachError = error
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
            // M88-H：相机临时文件本来就是 File，直接交给附件层流式归一化（不整读、不限大小）。
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    if (!file.isFile || file.length() <= 0L) {
                        "无法读取相机照片"
                    } else {
                        savePendingImageFile(state, file, null, file.name)
                    }
                }.getOrElse { "无法读取相机照片：${it.message ?: it.javaClass.simpleName}" }.also {
                    deleteCameraTemp(file)
                }
            }
            state.attachError = error
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

    /** 待发文件上限（单个，仅内联文本）：超过就走工作区路径引用，不拒绝。 */
    private val maxFileBytes = 512 * 1024
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

        // 图片走原来的图片管线（模型直接看图）；M88-H：不按大小拒绝，流式落临时文件再归一化。
        if (declared?.startsWith("image/") == true || ext in imageFileExts) {
            val temp = runCatching { copyUriToTemp(uri) }.getOrElse { return "读取失败：$name（${it.message}）" }
            return try {
                savePendingImageFile(state, temp, declared, name)
            } finally {
                temp.delete()
            }
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
                    // M88-H：文件引用不再设 50MB 上限，分块流式复制（不整读进内存）。
                    val copied = xin.dshaa.nativeapp.data.AttachmentStreams.copy(ins, outs)
                    total = copied
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

    /**
     * M88-H：把 SAF Uri 分块复制到进程缓存临时文件（不整读进内存、不按大小拒绝）。
     * 调用方负责在用完后删除；空文件抛错（由调用方翻成用户文案）。
     */
    private fun copyUriToTemp(uri: Uri): File {
        val dir = File(getApplication<Application>().cacheDir, "incoming").apply { mkdirs() }
        val temp = File.createTempFile("attach-", ".bin", dir)
        try {
            val input = getApplication<Application>().contentResolver.openInputStream(uri)
                ?: error("读不到文件")
            input.use { ins ->
                temp.outputStream().use { outs ->
                    val total = xin.dshaa.nativeapp.data.AttachmentStreams.copy(ins, outs)
                    if (total == 0L) error("文件是空的")
                    outs.fd.sync()
                }
            }
            return temp
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    /**
     * M88-H：图片的「文件入口」——只读 16 字节文件头嗅探格式，其余交给
     * [AttachmentStore.saveImageFile] 流式归一化；源文件大小不限。
     */
    private suspend fun savePendingImageFile(
        state: ChatSessionState,
        file: File,
        declared: String?,
        name: String?,
    ): String? {
        val header = withContext(Dispatchers.IO) {
            file.inputStream().use { input ->
                val buffer = ByteArray(16)
                val read = input.read(buffer)
                if (read <= 0) ByteArray(0) else buffer.copyOf(read)
            }
        }
        if (header.isEmpty()) return "文件是空的：${name ?: file.name}"
        val mediaType = sniffImageMediaType(header)
            ?: declared?.takeIf { it in setOf("image/png", "image/jpeg", "image/webp", "image/gif") }
            ?: return "不支持的图片格式（支持 PNG/JPEG/WebP/GIF）"
        return try {
            val ref = withContext(Dispatchers.IO) { attachments.saveImageFile(file, mediaType, name) }
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
        ImageStoreException.Code.IMAGE_TOO_LARGE -> "图片太大（无法压到可发送大小），请先缩小或转成 JPEG/PNG"
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
     * M87-H5：插件通知点击回到聊天落地页（用户拍板：只打开 App 聊天，不深链会话/插件页）。
     * 每次请求自增；壳看到变化后清页面栈并 [consumeLandingChatRequest]。
     */
    override var landingChatRequest by mutableStateOf(0L)
        private set

    override fun consumeLandingChatRequest() {
        landingChatRequest = 0L
    }

    /** MainActivity 收到 `PluginNotifier.ACTION_OPEN_CHAT` 时调用。 */
    fun requestLandingChat() {
        landingChatRequest += 1
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
        /**
         * P-1 `topic.end`：当前话题在跑轮次时不能先移会话目录（收尾还会 append），
         * 先记在这里，等 `invokeOnCompletion` 收完尾再归档。
         */
        var archiveAfterTurn = false
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
        // P-1 原语（docs/185 §四）：绑死这条会话；子代理上下文不挂（见 runChildAgent）
        primitives = AppPrimitiveHost(live, PrimitiveCaller.TOOL),
    )

    /**
     * P-1 原语宿主（`docs/185` §四）：6 个动作全部落在 App 已有的实现上，
     * 工具面（`core:tools`）只做参数校验——这就是「一个动作只暴露一次」。
     *
     * 绑死一条 [LiveChat]：`fork` / `summarize` 默认操作它；`switchTopic` 切前台；
     * `endTopic` 归档（当前轮还在跑时延迟到收尾，避免移走正在 append 的会话目录）。
     */
    private inner class AppPrimitiveHost(
        private val live: LiveChat?,
        private val caller: PrimitiveCaller = PrimitiveCaller.NATIVE,
    ) : PrimitiveHost {

        override fun currentTopicId(): String? = live?.state?.sessionId

        private fun requireLive(): LiveChat = live ?: error("当前没有打开的话题")

        /**
         * 原生/工具/插件三面唯一的执行入口：先按目录做可用性 + 参数校验，再落到具体动作。
         * 具体动作全部复用 App 已有实现（fork / compaction 摘要 / prefs / 导航 / 回收站 / stop）。
         */
        override suspend fun invoke(id: String, args: JsonObject): PrimitiveResult {
            val definition = PrimitiveCatalog.byId[id]
                ?: return PrimitiveResult.error("unknown primitive: $id", "UNKNOWN_PRIMITIVE")
            if (definition.availability != xin.dshaa.core.primitives.PrimitiveAvailability.READY) {
                return PrimitiveResult.error(
                    "${definition.id} 尚未实现（${definition.availability.name}）",
                    "NOT_IMPLEMENTED",
                )
            }
            PrimitiveArgs.validate(definition, args)?.let { reason ->
                return PrimitiveResult.error("invalid ${definition.id}: $reason", "INVALID_ARGUMENTS")
            }
            return when (id) {
                PrimitiveIds.SESSION_LIST -> sessionList(args)
                PrimitiveIds.SESSION_OPEN -> sessionOpen(args)
                PrimitiveIds.SESSION_READ -> sessionRead(args)
                PrimitiveIds.SESSION_FORK -> sessionFork(args)
                PrimitiveIds.SESSION_SUMMARIZE -> sessionSummarize(args)
                PrimitiveIds.TOPIC_SWITCH -> topicSwitch(args)
                PrimitiveIds.TOPIC_END -> topicEnd(args)
                PrimitiveIds.MODEL_LIST -> modelList()
                PrimitiveIds.MODEL_SET -> modelSet(args)
                PrimitiveIds.EFFORT_SET -> effortSet(args)
                PrimitiveIds.PERMISSION_SET -> permissionSet(args)
                PrimitiveIds.TURN_SEND -> turnSend(args)
                PrimitiveIds.TURN_STOP -> turnStop()
                PrimitiveIds.TURN_STATE -> turnState()
                PrimitiveIds.TOOL_LIST -> toolList()
                PrimitiveIds.TOOL_CALL -> toolCall(args)
                PrimitiveIds.STORE_GET -> storeGet(args)
                PrimitiveIds.STORE_SET -> storeSet(args)
                PrimitiveIds.STORE_LIST -> storeList(args)
                PrimitiveIds.UI_OPEN_CONTAINER -> openContainer(args)
                else -> PrimitiveResult.error("unknown primitive: $id", "UNKNOWN_PRIMITIVE")
            }
        }

        /**
         * P3-e：`ui.openContainer`（三种容器）。
         *
         * 原语只负责「说清楚要开哪个插件、用哪种容器」，真正的窗口在
         * `ClientPluginWindows`/`ClientPluginWindowService` 里；插件自己的 `exports["./client"]`
         * 由 `PluginClientEntry` 解析。**不做 slot 协议、不做会话流内嵌**（docs/187 §9.2 红线）。
         */
        private suspend fun openContainer(args: JsonObject): PrimitiveResult {
            val rawKind = (args["kind"] as? JsonPrimitive)?.contentOrNull
            val kind = ContainerKind.parse(rawKind)
                ?: return PrimitiveResult.error(
                    "kind 只支持 fullscreen / floating / sheet（收到 ${rawKind ?: "空"}）",
                    "INVALID_ARGUMENTS",
                )
            val pluginId = (args["plugin_id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (pluginId.isEmpty()) {
                return PrimitiveResult.error(
                    "缺少 plugin_id：要打开哪个插件的 exports[\"./client\"]？可先用 tool_list 看插件",
                    "INVALID_ARGUMENTS",
                )
            }
            val record = withContext(Dispatchers.IO) { pluginStore.list().firstOrNull { it.id == pluginId } }
                ?: return PrimitiveResult.error("没有这个插件：$pluginId", "NOT_FOUND")
            val resolved = withContext(Dispatchers.IO) {
                xin.dshaa.nativeapp.pluginui.ClientPluginWindows.resolve(record)
            }
            if (resolved !is xin.dshaa.core.plugins.PluginClientEntry.Result.Resolved) {
                return PrimitiveResult.error(
                    "插件 $pluginId 没有可运行的浏览器半侧：" +
                        (resolved as xin.dshaa.core.plugins.PluginClientEntry.Result.Unsupported).reason,
                    "UNSUPPORTED",
                )
            }
            val title = (args["title"] as? JsonPrimitive)?.contentOrNull
            val message = xin.dshaa.nativeapp.pluginui.ClientPluginWindows.open(
                getApplication(),
                record,
                kind,
                title,
            )
            Log.i("DshContainer", "primitive ui.openContainer kind=${kind.wire} plugin=$pluginId -> $message")
            val opened = message.startsWith("正在打开")
            return if (opened) {
                PrimitiveResult.ok(
                    summary = "已在${xin.dshaa.nativeapp.pluginui.ClientPluginWindows.kindLabel(kind)}容器打开「${record.name}」",
                    data = buildJsonObject {
                        put("kind", kind.wire)
                        put("pluginId", record.id)
                        put("entry", resolved.entry)
                        put("capabilities", buildJsonArray { ContainerSpec.capabilities.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                    },
                )
            } else {
                PrimitiveResult.error(message, "OPEN_FAILED")
            }
        }

        // ---- 会话 / 话题 ----

        private suspend fun sessionList(args: JsonObject): PrimitiveResult {
            val limit = (PrimitiveArgs.long(args, "limit") ?: 20L).coerceIn(1, 100).toInt()
            val includeTrash = PrimitiveArgs.bool(args, "include_trash")
            val all = withContext(Dispatchers.IO) { repo.list().filter { it.parentSession == null } }
            val currentId = currentTopicId()
            val shown = all.take(limit)
            val text = buildString {
                append(all.size).append(" topics")
                if (all.size > shown.size) append(" (showing ").append(limit).append(')')
                append('\n')
                shown.forEach { topic ->
                    append("- ").append(topic.id).append(" | ")
                        .append(topic.title.ifBlank { "(untitled)" }).append(" | ")
                        .append(topic.updatedAt)
                    if (topic.id == currentId) append(" | current")
                    append('\n')
                }
                if (includeTrash) {
                    val trash = withContext(Dispatchers.IO) { repo.listTrash() }
                    append("trash: ").append(trash.size).append('\n')
                    trash.take(limit).forEach { trashed ->
                        append("- ").append(trashed.id).append(" | ")
                            .append(trashed.title.ifBlank { "(untitled)" })
                            .append(" | deleted ").append(trashed.deletedAt).append('\n')
                    }
                }
            }.trimEnd()
            val data = buildJsonObject {
                put("count", all.size)
                put("current", currentId.orEmpty())
                put(
                    "topics",
                    buildJsonArray {
                        shown.forEach { topic ->
                            add(
                                buildJsonObject {
                                    put("id", topic.id)
                                    put("title", topic.title)
                                    put("updatedAt", topic.updatedAt)
                                    put("current", topic.id == currentId)
                                },
                            )
                        }
                    },
                )
            }
            return PrimitiveResult.ok(text, data)
        }

        private suspend fun sessionOpen(args: JsonObject): PrimitiveResult {
            val id = PrimitiveArgs.string(args, "topic_id").orEmpty().trim()
            val exists = withContext(Dispatchers.IO) { repo.load(id) != null }
            if (!exists) error("找不到话题：$id")
            withContext(Dispatchers.Main) { openSessionById(id) }
            val opened = liveById(id) ?: error("打开话题失败：$id")
            return PrimitiveResult.ok(
                "opened topic $id (${opened.state.title.ifBlank { "untitled" }})",
                buildJsonObject {
                    put("topicId", id)
                    put("title", opened.state.title)
                },
            )
        }

        private suspend fun sessionRead(args: JsonObject): PrimitiveResult {
            val topicId = PrimitiveArgs.string(args, "topic_id")?.trim()?.takeIf { it.isNotEmpty() }
                ?: currentTopicId()
                ?: error("当前没有打开的话题；请传 topic_id")
            val range = PrimitiveRange(
                PrimitiveArgs.long(args, "from_seq"),
                PrimitiveArgs.long(args, "to_seq"),
            )
            val maxChars = (PrimitiveArgs.long(args, "max_chars") ?: 4000L).toInt().coerceIn(200, 20_000)
            val session = live?.takeIf { it.state.sessionId == topicId }?.handle
                ?: withContext(Dispatchers.IO) { repo.load(topicId) }
                ?: error("找不到话题：$topicId")
            val title = live?.takeIf { it.state.sessionId == topicId }?.state?.title
                ?: withContext(Dispatchers.IO) { repo.list().firstOrNull { it.id == topicId }?.title }
                .orEmpty()
            val messages = session.events.mapNotNull { event -> xin.dshaa.core.session.SessionCodec.messageOf(event) }
                .filter { message -> range.contains(message.seq) }
            if (messages.isEmpty()) {
                return PrimitiveResult.ok(
                    "topic $topicId ($title) has no messages in the requested range",
                    buildJsonObject {
                        put("topicId", topicId)
                        put("messages", 0)
                    },
                )
            }
            val text = buildString {
                append("topic ").append(topicId)
                if (title.isNotBlank()) append(" (").append(title).append(')')
                append(": ").append(messages.size).append(" messages")
                val first = messages.first().seq
                val last = messages.last().seq
                if (first != null || last != null) {
                    append(", seq ").append(first ?: "?").append("..").append(last ?: "?")
                }
                append('\n')
                for (message in messages) {
                    val body = message.text.trim()
                    if (body.isEmpty()) continue
                    append('[').append(message.seq ?: "-").append("] ")
                        .append(message.role.name.lowercase()).append(": ")
                        .append(if (body.length > 1000) body.take(1000) + " …" else body)
                        .append('\n')
                    if (length >= maxChars) {
                        append("… (truncated at ").append(maxChars).append(" chars)\n")
                        break
                    }
                }
            }.trimEnd().take(maxChars)
            return PrimitiveResult.ok(
                text,
                buildJsonObject {
                    put("topicId", topicId)
                    put("messages", messages.size)
                    appendSeqRange(this, messages.mapNotNull { it.seq })
                },
            )
        }

        private suspend fun sessionFork(args: JsonObject): PrimitiveResult {
            val opened = requireLive()
            val session = opened.handle ?: error("当前话题还没有落库：先发一条消息再分支")
            val through = PrimitiveArgs.long(args, "at_event")
                ?: session.events.lastOrNull()?.seq
                ?: error("当前话题还没有事件可分支")
            val title = PrimitiveArgs.string(args, "title")?.trim()?.takeIf { it.isNotEmpty() }
            val sourceTitle = opened.state.title.ifBlank { "话题" }
            val resolvedTitle = title ?: "分支 · $sourceTitle"
            val forked = withContext(Dispatchers.IO) {
                repo.fork(session.header.id, through, cleanTurnBoundary = true, title = resolvedTitle)
            } ?: error("无法在 seq $through 建分支：找不到干净的轮次边界")
            refreshSessions()
            val throughSeq = forked.events.mapNotNull { it.seq }.maxOrNull() ?: through
            return PrimitiveResult.ok(
                "forked topic ${forked.header.id} at seq $throughSeq (${forked.events.size} events, title=$resolvedTitle)",
                buildJsonObject {
                    put("topicId", forked.header.id)
                    put("throughSeq", throughSeq)
                    put("events", forked.events.size)
                    put("title", resolvedTitle)
                },
            )
        }

        private suspend fun sessionSummarize(args: JsonObject): PrimitiveResult {
            val opened = requireLive()
            val range = PrimitiveRange(
                PrimitiveArgs.long(args, "from_seq"),
                PrimitiveArgs.long(args, "to_seq"),
            )
            val (sessionId, selected) = withContext(Dispatchers.Main) {
                val handle = opened.handle ?: error("当前话题还没有落库，无法生成 brief")
                // P1：调用本工具的这一轮还没写完，不能进摘要（否则 brief 自指、还会把
                // 「要求生成摘要」当成待办）。只取调用轮之前已完成的消息。
                val openTurnStart = handle.events.lastOrNull { it.type == "turn/start" }?.seq
                val messages = opened.state.messages.filter { message ->
                    range.contains(message.seq) &&
                        (openTurnStart == null || (message.seq ?: Long.MAX_VALUE) < openTurnStart)
                }
                handle.header.id to messages
            }
            if (selected.isEmpty()) error("没有可摘要的已完成消息（当前轮次不参与摘要）")
            // 复用 compaction 的摘要请求（真模型、只收文本），但不落 compaction 事件：
            // 会话格式不变，brief 只作为工具结果回到模型 / 被 topic.switch 写进新话题。
            val summary = withContext(Dispatchers.IO) {
                opened.engine.summarizeCompaction(selected, llmConfig())
            }
            val text = summary.text.trim()
            if (text.isEmpty()) error("摘要为空")
            return PrimitiveResult.ok(
                text,
                buildJsonObject {
                    put("topicId", sessionId)
                    put("messages", selected.size)
                    put("briefChars", text.length)
                },
            )
        }

        private suspend fun topicSwitch(args: JsonObject): PrimitiveResult {
            val topicId = PrimitiveArgs.string(args, "topic_id")?.trim()?.takeIf { it.isNotEmpty() }
            val title = PrimitiveArgs.string(args, "title")?.trim()?.takeIf { it.isNotEmpty() }
            val brief = PrimitiveArgs.string(args, "brief")?.takeIf { it.isNotBlank() }
            val trimmed = brief?.trim()?.takeIf { it.isNotEmpty() }
            val creating = topicId == null
            // 这些读的都是主线程状态（lives / activeTurns / settings），先取好再进 IO。
            val open = if (creating) null else liveById(topicId)
            val openRunning = open != null &&
                (open in activeTurns || open.state.streaming || open.job?.isCompleted == false)
            val model = settings.model
            val effort = settings.reasoningEffort
            val maxTokens = settings.maxTokens
            val provider = providerLabel()
            val cwd = workspace.absolutePath
            val targetId = withContext(Dispatchers.IO) {
                if (creating) {
                    val session = repo.create(cwd = cwd, model = model)
                    session.appendRequestHeader(
                        provider = provider,
                        model = model,
                        reasoningEffort = effort,
                        maxTokens = maxTokens,
                    )
                    session.appendTitle(title ?: if (trimmed != null) "交接话题" else "新话题")
                    if (trimmed != null) session.appendUserMessage(trimmed, handoffSource())
                    session.header.id
                } else {
                    if (open != null) {
                        require(!openRunning) { "目标话题正在运行，不能注入交接摘要：$topicId" }
                        if (trimmed != null) require(open !== live) {
                            "不能在当前话题正在跑轮次时给它注入交接摘要"
                        }
                        val handle = open.handle ?: error("目标话题没有可写句柄：$topicId")
                        if (title != null) handle.appendTitle(title)
                        if (trimmed != null) handle.appendUserMessage(trimmed, handoffSource())
                    } else {
                        val loaded = repo.load(topicId) ?: error("找不到话题：$topicId")
                        require(!loaded.readOnly) { "目标话题只读，不能注入交接摘要：$topicId" }
                        if (title != null) loaded.appendTitle(title)
                        if (trimmed != null) loaded.appendUserMessage(trimmed, handoffSource())
                    }
                    topicId
                }
            }
            // 注入过 brief 的旧运行时必须作废：否则 openSessionById 会复用内存态，看不到刚从磁盘写的 brief。
            // 没有 brief 时不动运行时（切回正在跑的话题也不能把它的 job 扔掉）。
            if (trimmed != null) {
                liveById(targetId)?.let { stale ->
                    require(stale !== live) { "不能在当前话题正在跑轮次时给它注入交接摘要" }
                    lives.remove(stale.state)
                    BigFish.forget(stale.state)
                }
            }
            withContext(Dispatchers.Main) {
                openSessionById(targetId)
                if (title != null) liveById(targetId)?.state?.title = title
            }
            val opened = liveById(targetId)
            return PrimitiveResult.ok(
                "switched to topic $targetId (${opened?.state?.title ?: title ?: "新话题"})" +
                    (if (creating) ", created" else "") +
                    (if (trimmed != null) ", handed off brief" else ""),
                buildJsonObject {
                    put("topicId", targetId)
                    put("created", creating)
                    put("seeded", trimmed != null)
                },
            )
        }

        private suspend fun topicEnd(args: JsonObject): PrimitiveResult {
            val id = PrimitiveArgs.string(args, "topic_id")?.trim()?.takeIf { it.isNotEmpty() }
                ?: currentTopicId()
                ?: error("当前话题还没有落库")
            val title = liveById(id)?.state?.title
                ?: withContext(Dispatchers.IO) { repo.list().firstOrNull { it.id == id }?.title }
                ?: "话题"
            val target = liveById(id)
            if (target != null &&
                (target in activeTurns || target.state.streaming || target.job?.isCompleted == false)
            ) {
                target.archiveAfterTurn = true
                return PrimitiveResult.ok(
                    "will be archived after the running turn ends: $id ($title)",
                    buildJsonObject {
                        put("topicId", id)
                        put("deferred", true)
                    },
                )
            }
            archiveTopicNow(id)
            return PrimitiveResult.ok(
                "archived: $id ($title)",
                buildJsonObject {
                    put("topicId", id)
                    put("deferred", false)
                },
            )
        }

        // ---- 模型 / 设置 ----

        private fun modelList(): PrimitiveResult {
            val active = (currentModelResolution() as? ModelResolution.Active)?.model
            val candidates = modelCandidates()
            val text = buildString {
                append("model=").append(settings.model)
                append(" provider=").append(providerLabel())
                append(" protocol=").append(settings.protocol)
                append(" effort=").append(settings.reasoningEffort)
                append(" approval=").append(settings.approvalMode)
                append('\n')
                candidates.forEach { candidate ->
                    append("- ").append(candidate.providerId).append(" | ").append(candidate.label)
                    if (!candidate.enabled) append(" | disabled")
                    if (active?.providerId == candidate.providerId) append(" | active")
                    append('\n')
                }
            }.trimEnd()
            return PrimitiveResult.ok(
                text,
                buildJsonObject {
                    put("model", settings.model)
                    put("provider", providerLabel())
                    put("protocol", settings.protocol)
                    put("effort", settings.reasoningEffort)
                    put("approval", settings.approvalMode)
                    put(
                        "candidates",
                        buildJsonArray {
                            candidates.forEach { candidate ->
                                add(
                                    buildJsonObject {
                                        put("providerId", candidate.providerId)
                                        put("label", candidate.label)
                                        put("enabled", candidate.enabled)
                                        put("active", active?.providerId == candidate.providerId)
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }

        private suspend fun modelSet(args: JsonObject): PrimitiveResult = withContext(Dispatchers.Main) {
            val model = PrimitiveArgs.string(args, "model").orEmpty().trim()
            val previous = settings.model
            settings.setModel(model)
            requireLive().state.model = settings.model
            PrimitiveResult.ok(
                "model: $previous -> ${settings.model}",
                buildJsonObject {
                    put("previous", previous)
                    put("current", settings.model)
                },
            )
        }

        private suspend fun effortSet(args: JsonObject): PrimitiveResult = withContext(Dispatchers.Main) {
            val effort = PrimitiveArgs.string(args, "effort").orEmpty().trim().lowercase()
            if (effort !in PrimitiveCatalog.EFFORTS) error("不支持的推理强度：$effort")
            val previous = settings.reasoningEffort
            settings.setReasoningEffort(effort)
            PrimitiveResult.ok(
                "reasoning effort: $previous -> ${settings.reasoningEffort}",
                buildJsonObject {
                    put("previous", previous)
                    put("current", settings.reasoningEffort)
                },
            )
        }

        private suspend fun permissionSet(args: JsonObject): PrimitiveResult = withContext(Dispatchers.Main) {
            val mode = PrimitiveArgs.string(args, "mode").orEmpty().trim().lowercase()
            if (mode !in PrimitiveCatalog.APPROVAL_MODES) error("不支持的审批方式：$mode")
            val previous = settings.approvalMode
            settings.setApprovalMode(mode)
            PrimitiveResult.ok(
                "approval mode: $previous -> ${settings.approvalMode}",
                buildJsonObject {
                    put("previous", previous)
                    put("current", settings.approvalMode)
                },
            )
        }

        // ---- 回合 ----

        private suspend fun turnSend(args: JsonObject): PrimitiveResult {
            val opened = requireLive()
            if (opened !== current) {
                return PrimitiveResult.error(
                    "turn.send 只支持当前前台话题；先用 session.open 切换",
                    "NOT_FOREGROUND",
                )
            }
            val state = opened.state
            if (state.readOnly) return PrimitiveResult.error("当前话题只读，不能发送", "READ_ONLY")
            if (state.streaming || opened.job?.isCompleted == false) {
                return PrimitiveResult.error(
                    "当前话题正在跑轮次；turn.send 只能发给空闲话题（避免自循环）",
                    "BUSY",
                )
            }
            val text = PrimitiveArgs.string(args, "text").orEmpty().trim()
            val accepted = withContext(Dispatchers.Main) { send(text) }
            if (!accepted) {
                return PrimitiveResult.error(
                    state.attachError ?: "发送被拒绝（运行时未就绪或没有可用模型）",
                    "REJECTED",
                )
            }
            return PrimitiveResult.ok(
                "started a turn on topic ${state.sessionId ?: "(unsaved)"}",
                buildJsonObject {
                    put("topicId", state.sessionId.orEmpty())
                    put("accepted", true)
                },
            )
        }

        private suspend fun turnStop(): PrimitiveResult {
            if (caller == PrimitiveCaller.TOOL) {
                return PrimitiveResult.error(
                    "模型不能停止自己的轮次；请让用户按停止按钮",
                    "PERMISSION_DENIED",
                )
            }
            val opened = requireLive()
            if (opened !== current && opened !== lastActiveTurn) {
                return PrimitiveResult.error("turn.stop 只能停当前/最近的前台话题", "NOT_FOREGROUND")
            }
            val state = opened.state
            val running = opened in activeTurns || state.streaming || opened.job?.isCompleted == false
            if (!running) {
                return PrimitiveResult.ok(
                    "topic ${state.sessionId ?: "(unsaved)"} is already idle",
                    buildJsonObject { put("running", false) },
                )
            }
            withContext(Dispatchers.Main) { stop() }
            return PrimitiveResult.ok(
                "stop requested for topic ${state.sessionId ?: "(unsaved)"}",
                buildJsonObject {
                    put("running", true)
                    put("stopRequested", true)
                },
            )
        }

        private fun turnState(): PrimitiveResult {
            val opened = requireLive()
            val state = opened.state
            val running = opened in activeTurns || state.streaming || opened.job?.isCompleted == false
            val status = when {
                state.readOnly -> "read-only"
                state.stopping -> "stopping"
                running -> "running"
                else -> "idle"
            }
            val text = buildString {
                append("topic ").append(state.sessionId ?: "(unsaved)")
                if (state.title.isNotBlank()) append(" (").append(state.title).append(')')
                append(" status=").append(status)
                append(" messages=").append(state.messages.size)
                append(" model=").append(state.model)
                append(" effort=").append(settings.reasoningEffort)
                if (state.queuedPrompts.isNotEmpty()) append(" queued=").append(state.queuedPrompts.size)
            }
            return PrimitiveResult.ok(
                text,
                buildJsonObject {
                    put("topicId", state.sessionId.orEmpty())
                    put("title", state.title)
                    put("status", status)
                    put("running", running)
                    put("messages", state.messages.size)
                    put("model", state.model)
                    put("effort", settings.reasoningEffort)
                    put("queued", state.queuedPrompts.size)
                },
            )
        }

        // ---- 工具 ----

        private fun toolList(): PrimitiveResult {
            val offered = offeredToolNames()
            val all = registry.names
            val text = buildString {
                append(all.size).append(" tools registered, ").append(offered.size).append(" offered\n")
                all.forEach { name ->
                    append("- ").append(name)
                    if (name !in offered) append(" (not offered)")
                    append('\n')
                }
            }.trimEnd()
            return PrimitiveResult.ok(
                text,
                buildJsonObject {
                    put("registered", all.size)
                    put("offered", offered.size)
                    put(
                        "tools",
                        buildJsonArray {
                            all.forEach { name ->
                                add(
                                    buildJsonObject {
                                        put("name", name)
                                        put("offered", name in offered)
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }

        private suspend fun toolCall(args: JsonObject): PrimitiveResult {
            val name = PrimitiveArgs.string(args, "name").orEmpty().trim()
            if (name in PrimitiveCatalog.toolNames) {
                return PrimitiveResult.error("不能通过 tool.call 调用原语（会递归）：$name", "INVALID_ARGUMENTS")
            }
            if (name in NESTED_TOOL_DENYLIST) {
                return PrimitiveResult.error("tool.call 不允许调用 $name", "INVALID_ARGUMENTS")
            }
            if (name !in registry.names) return PrimitiveResult.error("unknown tool: $name", "UNKNOWN_TOOL")
            if (name !in offeredToolNames()) {
                return PrimitiveResult.error("工具当前未开放：$name", "UNAVAILABLE")
            }
            val innerArgs = PrimitiveArgs.objectArg(args, "arguments") ?: JsonObject(emptyMap())
            val opened = requireLive()
            val approved = withContext(Dispatchers.Main) {
                awaitApproval(
                    opened,
                    ToolCall(
                        id = "primitive-tool-${System.nanoTime()}",
                        name = name,
                        arguments = innerArgs.toString(),
                    ),
                )
            }
            if (!approved) return PrimitiveResult.error("用户拒绝了 $name", "DENIED")
            val result = registry.execute(name, innerArgs, opened.toolContext)
            // 与引擎的 onToolResult 同一语义：写类工具可能改了工作区，`@file` 索引要重建。
            fileReferenceSearch.invalidate()
            val text = if (result.text.length > MAX_NESTED_TOOL_TEXT) {
                result.text.take(MAX_NESTED_TOOL_TEXT) + " …(truncated)"
            } else {
                result.text
            }
            return PrimitiveResult(
                summary = text,
                data = buildJsonObject {
                    put("tool", name)
                    put("isError", result.isError)
                },
                isError = result.isError,
                code = if (result.isError) "TOOL_ERROR" else null,
            )
        }

        // ---- 数据（不碰工作区；App 内的命名空间键值） ----

        private fun storeGet(args: JsonObject): PrimitiveResult {
            val namespace = PrimitiveArgs.string(args, "namespace").orEmpty()
            val key = PrimitiveArgs.string(args, "key").orEmpty()
            val value = primitiveStore.getString(storeKey(namespace, key), null)
            return PrimitiveResult.ok(
                value.orEmpty(),
                buildJsonObject {
                    put("namespace", namespace)
                    put("key", key)
                    put("found", value != null)
                    if (value != null) put("value", value)
                },
            )
        }

        private fun storeSet(args: JsonObject): PrimitiveResult {
            val namespace = PrimitiveArgs.string(args, "namespace").orEmpty()
            val key = PrimitiveArgs.string(args, "key").orEmpty()
            val value = PrimitiveArgs.string(args, "value").orEmpty()
            val storageKey = storeKey(namespace, key)
            val previous = primitiveStore.getString(storageKey, null)
            primitiveStore.edit().putString(storageKey, value).apply()
            val bytes = value.toByteArray(Charsets.UTF_8).size
            return PrimitiveResult.ok(
                "stored $namespace/$key ($bytes bytes, ${if (previous == null) "created" else "replaced"})",
                buildJsonObject {
                    put("namespace", namespace)
                    put("key", key)
                    put("bytes", bytes)
                    put("created", previous == null)
                },
            )
        }

        private fun storeList(args: JsonObject): PrimitiveResult {
            val namespace = PrimitiveArgs.string(args, "namespace").orEmpty()
            val prefix = "$namespace/"
            val keys = primitiveStore.all.keys
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix) }
                .sorted()
            return PrimitiveResult.ok(
                if (keys.isEmpty()) "no keys in namespace $namespace" else keys.joinToString("\n") { "- $it" },
                buildJsonObject {
                    put("namespace", namespace)
                    put(
                        "keys",
                        buildJsonArray { keys.forEach { add(JsonPrimitive(it)) } },
                    )
                },
            )
        }

        private fun storeKey(namespace: String, key: String): String = "$namespace/$key"

        private val primitiveStore by lazy {
            getApplication<Application>().getSharedPreferences("primitive_store", Context.MODE_PRIVATE)
        }

        private fun appendSeqRange(builder: kotlinx.serialization.json.JsonObjectBuilder, seqs: List<Long>) {
            if (seqs.isEmpty()) return
            builder.put("firstSeq", seqs.min())
            builder.put("lastSeq", seqs.max())
        }

        private fun handoffSource(): JsonObject = buildJsonObject {
            put("kind", "handoff")
            put("form", "notice")
            put("summary", "交接摘要")
        }
    }

    // ---- P1 插件桥面（docs/185 §四）：和工具面共用同一个 AppPrimitiveHost ----

    /**
     * `ctx.primitive.invoke/list` 的宿主实现。P1 只开放 `PrimitiveCatalog.bridgeVisible`
     * （只读原语）；写会话/改设置/执行工具等留给 P4 的权限模型，不靠插件清单静默获得。
     */
    private fun pluginPrimitiveBridge(): PluginPrimitiveBridge = object : PluginPrimitiveBridge {
        override fun invoke(id: String, argsJson: String): String = invokePrimitiveForPlugin(id, argsJson)

        override fun list(): String = PrimitiveBridgeJson.list(PrimitiveCatalog.bridgeVisible)
    }

    /**
     * 插件 JS 线程同步调用原语（`runBlocking` 到主/IO，超时 120s）。
     *
     * 为什么可以阻塞：QuickJS 常驻运行时自带单线程执行器（不是主线程）。主线程直接拒绝，
     * 避免内部 `withContext(Dispatchers.Main)` 死锁；插件侧超时按失败返回。
     */
    private fun invokePrimitiveForPlugin(id: String, argsJson: String): String {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return PrimitiveBridgeJson.error("原语桥不能在主线程同步执行", "MAIN_THREAD")
        }
        val definition = PrimitiveCatalog.byId[id]
            ?: return PrimitiveBridgeJson.error("unknown primitive: $id", "UNKNOWN_PRIMITIVE")
        if (!definition.bridgeVisible) {
            return PrimitiveBridgeJson.error(
                "${definition.id} 不开放给插件（P1 只开只读原语）",
                "PERMISSION_DENIED",
            )
        }
        val args = PrimitiveArgs.parse(argsJson)
            ?: return PrimitiveBridgeJson.error("primitive arguments must be a JSON object", "INVALID_ARGUMENTS")
        PrimitiveArgs.validate(definition, args)?.let { reason ->
            return PrimitiveBridgeJson.error(reason, "INVALID_ARGUMENTS")
        }
        val host = AppPrimitiveHost(current, PrimitiveCaller.PLUGIN)
        return try {
            val result = runBlocking {
                withTimeout(PLUGIN_PRIMITIVE_TIMEOUT_MS) { host.invoke(id, args) }
            }
            PrimitiveBridgeJson.reply(result)
        } catch (e: TimeoutCancellationException) {
            PrimitiveBridgeJson.error("原语 $id 超时（>${PLUGIN_PRIMITIVE_TIMEOUT_MS}ms）", "TIMEOUT")
        } catch (e: Exception) {
            PrimitiveBridgeJson.error(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName, "PLUGIN_HOST_ERROR")
        }
    }

    /**
     * `topic.end` 的落点：和删除会话同一条路径（移进回收站，可恢复，不真删）。
     * 调用方必须保证目标没有在跑的轮次（当前轮由 [LiveChat.archiveAfterTurn] 延迟）。
     */
    private suspend fun archiveTopicNow(id: String) {
        runCatching { discardSessionQueue(id) }
        val ok = withContext(Dispatchers.IO) { repo.trash(id) }
        if (!ok) error("归档失败：找不到话题 $id")
        withContext(Dispatchers.IO) { runCatching { schedules.removeForSession(id) } }
        withContext(Dispatchers.Main) {
            invalidateLivesForDeletedSession(id)
            refreshSessions()
        }
    }

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
                    promptPack.androidApiTools().filter { it.name in offeredToolNames() } + pluginApiTools + nodeApiTools +
                        pluginDevTools.apiTools().filter { it.name in offeredToolNames() } +
                        nodeHostTools.apiTools().filter { it.name in offeredToolNames() }
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
                            includeBuiltinSections = builtinPromptCoreEnabled(),
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
                // M87-A：插件工具中间件链（每次请求现取快照；空链 = 零开销）。
                toolMiddleware = { pluginToolChain },
                // M87-B：插件请求/提示词中间件链（每次请求现取快照；空链 = 零开销）。
                requestMiddleware = { pluginRequestChain },
                claimSteeringInputs = { listOfNotNull(claimPrompt(live, steering = true)) },
                nextQueuedInput = { claimPrompt(live) },
                onInputDelivered = { id, seq -> ackPrompt(live, id, seq) },
                // 工作流 D（docs/187 §7.2）：`--ez dsh_cache_debug true` 打开后，每步落一行到
                // `files/cache-debug.jsonl`（前缀哈希 + 缓存命中），用来做 Anthropic / chat-completions A/B。
                cacheDebug = { CacheDebug.sink() },
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
        val sp = getApplication<Application>().getSharedPreferences("dsh", 0)
        // 调试通道：`--ez dsh_node_host_on true` → 打开 Node 宿主开关并等它把工具并进工具表（仅 debug）。
        if (sp.getBoolean("debug_node_host_on", false)) {
            sp.edit().remove("debug_node_host_on").apply()
            setNodePluginHostEnabled(true)
            viewModelScope.launch {
                val deadline = System.currentTimeMillis() + 25_000
                while (!nodePluginHost.isReady() && System.currentTimeMillis() < deadline) delay(200)
                delay(500)
                val merged = nodeToolNames.toList()
                val offered = offeredToolNames().filter { it.startsWith("node_") }
                Log.i(
                    "DshNodeHost",
                    "integration state=${nodePluginHost.describeState()} tools=${merged.size} names=$merged " +
                        "apiTools=${nodeApiTools.size} offered=$offered pid=${nodePluginHost.pid} rss=${nodePluginHost.rssKb}",
                )
                runCatching {
                    File(getApplication<Application>().filesDir, "node-host-integration.json").writeText(
                        "{\"ok\":${nodePluginHost.isReady() && merged.isNotEmpty()}," +
                            "\"state\":\"${nodePluginHost.describeState()}\"," +
                            "\"tools\":${merged.joinToString(",", "[", "]") { "\"$it\"" }}," +
                            "\"apiTools\":${nodeApiTools.size},\"pid\":${nodePluginHost.pid}}",
                    )
                }
            }
        }
        // docs/187 §九 第 11 项：`--ez dsh_node_host_off true` → 关宿主并落 filesDir/node-host-off.json，
        // 断言提示词段消失、node_* 工具摘除、无残留进程（只 debug 通道用）。
        if (sp.getBoolean("debug_node_host_off", false)) {
            sp.edit().remove("debug_node_host_off").apply()
            setNodePluginHostEnabled(false)
            viewModelScope.launch {
                val deadline = System.currentTimeMillis() + 10_000
                while (nodeToolNames.isNotEmpty() && System.currentTimeMillis() < deadline) delay(200)
                delay(300)
                val sections = pluginPromptSections().map { it.name }
                val tools = nodeToolNames.toList()
                val state = nodePluginHost.describeState()
                Log.i(
                    "DshNodeHost",
                    "debug host off enabled=${settings.nodePluginHostEnabled} tools=$tools " +
                        "promptSections=$sections state=$state pid=${nodePluginHost.pid}",
                )
                runCatching {
                    val json = buildString {
                        append("{\"ok\":").append(!settings.nodePluginHostEnabled && tools.isEmpty())
                        append(",\"enabled\":").append(settings.nodePluginHostEnabled)
                        append(",\"tools\":[").append(tools.joinToString(",") { "\"$it\"" }).append("]")
                        append(",\"promptSections\":[").append(sections.joinToString(",") { "\"$it\"" }).append("]")
                        append(",\"state\":\"").append(state.replace("\"", "'")).append("\"")
                        append(",\"pid\":").append(nodePluginHost.pid).append("}")
                    }
                    File(getApplication<Application>().filesDir, "node-host-off.json")
                        .writeText(json, Charsets.UTF_8)
                }
            }
        }
        // A 验收：`--es dsh_node_plugin_zip <绝对路径>` → 与插件页「导入 .zip」同一条 installPluginZip 路径
        sp.getString("debug_node_plugin_zip", null)?.takeIf { it.isNotBlank() }?.let { raw ->
            sp.edit().remove("debug_node_plugin_zip").apply()
            viewModelScope.launch {
                val source = if (File(raw).isAbsolute) File(raw) else File(getApplication<Application>().filesDir, raw)
                setNodePluginHostEnabled(true)
                val deadline = System.currentTimeMillis() + 25_000
                while (!nodePluginHost.isReady() && System.currentTimeMillis() < deadline) delay(200)
                val bytes = runCatching { withContext(Dispatchers.IO) { source.readBytes() } }.getOrNull()
                val result = if (bytes == null) {
                    Result.failure(IllegalStateException("cannot read $raw"))
                } else {
                    nodePluginHost.installPluginZip(bytes, source.name)
                }
                result.onSuccess { installed ->
                    refreshNodeHostDiagnostics()
                    Log.i(
                        "DshNodeHost",
                        "debug import ok module=$installed modules=${nodePluginHost.manifestModules()} " +
                            "tools=${nodeToolNames.toList()}",
                    )
                }.onFailure { Log.e("DshNodeHost", "debug import failed: ${it.message}", it) }
            }
        }
        // 调试通道：`am start --es dsh_send "..."`（仅 debug，用于无 IME 的自动验收）
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
        // 调试通道：`--es dsh_plugin_draft <绝对路径>` → 打开统一导入确认框（P4 截图/迁移验收）
        sp.getString("debug_plugin_draft", null)?.takeIf { it.isNotBlank() }?.let { raw ->
            sp.edit().remove("debug_plugin_draft").apply()
            val path = if (File(raw).isAbsolute) raw else File(getApplication<Application>().filesDir, raw).absolutePath
            debugScreenRequest = "Plugins"
            viewModelScope.launch {
                beginPluginImport(Uri.fromFile(File(path)))
            }
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
            val delayMs = sp.getString("debug_plugin_call_delay_ms", null)?.toLongOrNull()?.coerceIn(0, 120_000) ?: 2_500L
            sp.edit().remove("debug_plugin_call").remove("debug_plugin_args")
                .remove("debug_plugin_call_delay_ms").apply()
            viewModelScope.launch {
                // 冷启动后系统绑定无障碍服务要几百毫秒到一两秒；调试通道在 ViewModel init 里就跑，
                // 会抢在 A11y.attach 之前拿到「无障碍未连接」。这里只给**调试通道**加一点等待（M68）；
                // docs/190-B：Node 工具要等宿主 READY，可用 dsh_plugin_call_delay_ms 延后。
                kotlinx.coroutines.delay(delayMs)
                syncPluginTools()
                val tool = registry.tools.firstOrNull { it.name == debugPluginCall }
                val result = if (tool == null) {
                    xin.dshaa.core.tools.ToolResult.err("No such registered tool: $debugPluginCall")
                } else {
                    val args = runCatching {
                        Json.parseToJsonElement(argsRaw?.ifBlank { "{}" } ?: "{}").jsonObject
                    }.getOrElse { JsonObject(emptyMap()) }
                    runCatching { tool.execute(args, toolContext) }
                        .getOrElse { xin.dshaa.core.tools.ToolResult.err("Call crashed: ${it.message}") }
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
            // P3-e：`--es dsh_container_kind fullscreen|floating|sheet` 选容器；不给 = 悬浮窗。
            val kindRaw = sp.getString("debug_container_kind", null)
            sp.edit().remove("debug_container_kind").apply()
            val kind = ContainerKind.parse(kindRaw) ?: run {
                if (kindRaw.isNullOrBlank()) ContainerKind.FLOATING else null
            }
            viewModelScope.launch {
                delay(2_500)
                val record = pluginStore.list().firstOrNull { it.id == id }
                if (record == null) {
                    Log.i("DshClientHost", "debug open container id=$id -> 没有这个插件")
                    writeContainerEvidence(
                        mapOf("ok" to false, "pluginId" to id, "error" to "没有这个插件"),
                    )
                    return@launch
                }
                if (kind == null) {
                    val error = "未知容器种类：$kindRaw（只支持 fullscreen / floating / sheet）"
                    Log.w("DshClientHost", "debug open container id=$id -> $error")
                    writeContainerEvidence(mapOf("ok" to false, "pluginId" to id, "error" to error))
                    return@launch
                }
                val resolved = xin.dshaa.nativeapp.pluginui.ClientPluginWindows.resolve(record)
                val started = System.currentTimeMillis()
                val message = xin.dshaa.nativeapp.pluginui.ClientPluginWindows.open(getApplication(), record, kind)
                Log.i("DshClientHost", "debug open container kind=${kind.wire} id=$id -> $message")
                writeContainerEvidence(
                    buildMap {
                        put("ok", message.startsWith("正在打开"))
                        put("kind", kind.wire)
                        put("pluginId", record.id)
                        put("pluginName", record.name)
                        put("path", record.path)
                        put("message", message)
                        put("ms", System.currentTimeMillis() - started)
                        if (resolved is xin.dshaa.core.plugins.PluginClientEntry.Result.Resolved) {
                            put("entry", resolved.entry)
                        } else {
                            put("error", (resolved as xin.dshaa.core.plugins.PluginClientEntry.Result.Unsupported).reason)
                        }
                    },
                )
            }
        }
        // 调试通道：`--ez dsh_container_close true` → 关掉当前插件容器（截图/证据收尾用）。
        if (sp.getBoolean("debug_container_close", false)) {
            sp.edit().remove("debug_container_close").apply()
            xin.dshaa.nativeapp.pluginui.ClientPluginWindows.close(getApplication())
            Log.i("DshClientHost", "debug close container")
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
        // H1：会话没了，它的「继续生成」标记也一起清（回收站恢复/彻底删除共用）。
        truncationResume.clear(id)
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
        // H1：冷启/切会话后恢复「继续生成」入口（按会话 id 存在 prefs；只读会话不给）。
        state.pendingContinue = if (state.readOnly) null else truncationResume.pending(loaded.header.id)
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
        // P3-d：预设（模型 + 提示词段 + 工具 + 权限的组合，纯数据）。
        if (command.name == "preset") {
            executePresetCommand(command, state)
            return true
        }
        // P3-c：Node 插件用 `ctx.commands.register` 注册的斜杠命令（docs/187 §9.2）。
        // 原生命令优先（上面那些已经先匹配）；命中后转发给宿主，结果走 command notice。
        nodeCommandSpecs.firstOrNull { it.name.equals(command.name, ignoreCase = true) }?.let { spec ->
            return invokeNodeCommand(spec.name, command.rawInput)
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
        // P3-c：宿主开着但还没就绪/命令没注册 —— 给明确原因，不要静默无反应。
        val nodeHint = if (settings.nodePluginHostEnabled && !runCatching { nodePluginHost.isReady() }.getOrDefault(false)) {
            "（Node 宿主${nodeHostStateText.ifBlank { "未启动" }}，插件命令暂时不可用）"
        } else if (nodeCommandSpecs.isNotEmpty()) {
            "（Node 宿主已注册的命令：${nodeCommandSpecs.joinToString("、") { "/${it.name}" }}）"
        } else {
            ""
        }
        showCommandNotice(state, "未知命令 /${command.name}$nodeHint")
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
     * H1：按「最后一条 assistant 消息有没有 tool_use」区分截断类型。
     * 工具批次的参数可能被切断（引擎一条都没执行），文案与纯文本截断不同。
     */
    private fun truncationKindOf(state: ChatSessionState): TruncationKind {
        val last = state.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
        val hasToolCall = last?.content?.any { it.type == BlockType.TOOL_CALL } == true
        return if (hasToolCall) TruncationKind.TOOL_CALL else TruncationKind.TEXT
    }

    /** H1：记下「这条会话还能继续生成」，冷启/切会话后由 prefs 回填。 */
    private fun markTruncated(state: ChatSessionState, kind: TruncationKind = truncationKindOf(state)) {
        state.pendingContinue = kind
        val id = state.sessionId
        if (id != null && !state.readOnly) truncationResume.mark(id, kind)
        Log.i("DshTurn", "truncated turn: continue available session=${id ?: "-"} kind=$kind")
    }

    /** H1：清掉待续标记（新一轮开始 / 非截断收尾 / 会话删除）。 */
    private fun clearTruncated(state: ChatSessionState) {
        if (state.pendingContinue != null) state.pendingContinue = null
        state.sessionId?.let { truncationResume.clear(it) }
    }

    /**
     * H1：截断后「继续生成」——点一次消费一次（先清标记再发，连点/重放拿不到第二轮）；
     * 以固定用户消息起新一轮。发送未被接受（只读/队列/安全提示）时还原标记，按钮不丢。
     */
    override fun continueTruncated(): Boolean {
        val state = chat ?: return false
        if (state.readOnly || state.streaming) return false
        val kind = state.pendingContinue ?: return false
        val id = state.sessionId
        state.pendingContinue = null
        if (id != null) truncationResume.clear(id)
        Log.i("DshTurn", "continue truncated turn session=${id ?: "-"} kind=$kind")
        if (!submit(CONTINUE_TRUNCATED_PROMPT)) {
            state.pendingContinue = kind
            if (id != null) truncationResume.mark(id, kind)
            return false
        }
        return true
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
        // H1：新一轮开始就消费旧的「继续生成」标记；本轮若再次截断，收尾时会重新标记。
        clearTruncated(state)
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
                    if (outcome == TurnOutcome.MAX_TOKENS) {
                        markTruncated(state)
                        // H1（docs/182 第 5 条）：普通回合也明确「待发送消息已保留」，
                        // 与插件回合路径（runPluginAgentTurn 的 max_tokens 文案）对齐。
                        pausePromptQueue(live, "达到 max_tokens 上限，待发送消息已保留")
                    } else if (!completedNormally) {
                        pausePromptQueue(live, state.error ?: "本轮未正常完成，待发送消息已保留")
                    }
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
            } catch (e: SessionAppendVetoed) {
                // M87-E：插件拒绝写入时，这条事件没落盘、也没有助手占位消息可挂错误文案，
                // 把原因同时写进输入条上方的 attachError，用户能看到「拒绝写入」而不是静默丢失。
                state.error = e.message ?: "插件拒绝写入会话"
                state.attachError = state.error
                pausePromptQueue(live, state.error ?: "插件拒绝写入会话")
                Log.e("DshTurn", "turn append vetoed", e)
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
                // P-1：topic.end 在当前话题上调用过 → 轮次收完尾才归档（此时会话语义已完整）
                if (live.archiveAfterTurn) {
                    live.archiveAfterTurn = false
                    val archivedId = state.sessionId
                    if (archivedId != null) {
                        runCatching { archiveTopicNow(archivedId) }
                            .onFailure { Log.w("DshTopic", "archive after turn failed", it) }
                    }
                    syncKeepAlive()
                    return@launch
                }
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
            if (error != null) {
                state.error = error
                // H1：第三方回合插件也可能报 max_tokens；同样给「继续生成」入口（只能纯文本续写）。
                if (reply?.stopReason == "max_tokens") markTruncated(state, TruncationKind.TEXT)
            }
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

    /**
     * P3-e 调试通道的证据落盘：`files/container-open.json`（截图/JSON 一起归档到 docs/188-evidence/p3/）。
     */
    private fun writeContainerEvidence(fields: Map<String, Any?>) {
        runCatching {
            val json = org.json.JSONObject()
            fields.forEach { (key, value) -> json.put(key, value ?: org.json.JSONObject.NULL) }
            json.put("at", System.currentTimeMillis())
            File(getApplication<Application>().filesDir, "container-open.json")
                .writeText(json.toString(), Charsets.UTF_8)
            Log.i("DshClientHost", "container evidence: $json")
        }.onFailure { Log.w("DshClientHost", "write container evidence failed: ${it.message}") }
    }

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
                builtin = true, enabled = nativeEnabled, active = shellActive == null && !liquidUiEnabled && nativeEnabled,
                selectionValue = "native", unavailableReason = if (nativeEnabled) null else "内置界面插件已停用",
            ))
            // docs/187 §4.2：液态玻璃是**同一份页面代码的可选外观**，不是插件壳。
            // 它和原生壳并列出现在角色页，选中后写 `dsh_shell = liquid`，重启保持。
            add(xin.dshaa.ui.shellapi.RoleTargetUi(
                role = "shell", implementationId = LIQUID_SHELL_ID,
                label = "界面：液态玻璃（实验）",
                builtin = true, enabled = nativeEnabled, active = shellActive == null && liquidUiEnabled && nativeEnabled,
                selectionValue = LIQUID_SHELL_ID,
                unavailableReason = if (nativeEnabled) null else "内置界面插件已停用",
            ))
            pluginsUi.forEach { record ->
                record.shells.forEach { shell ->
                    val permitted = record.grantedPermissions.any { it.equals("ui", true) }
                    add(xin.dshaa.ui.shellapi.RoleTargetUi(
                        role = "shell", implementationId = shell.id,
                        label = "${shell.label} · ${shell.backend}", pluginId = record.id,
                        provider = record.name, builtin = false,
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
        // docs/187 §4.2：`native` / `liquid` 都是内置壳，只切外观开关，不走插件壳解析。
        if (value == "native") {
            liquidUiEnabled = false
            prefs.edit().putString("dsh_shell", "native").apply()
            setActiveShell(null)
            return true
        }
        if (value == LIQUID_SHELL_ID) {
            liquidUiEnabled = true
            prefs.edit().putString("dsh_shell", LIQUID_SHELL_ID).apply()
            setActiveShell(null)
            return true
        }
        liquidUiEnabled = false
        prefs.edit().putString("dsh_shell", value).apply()
        setActiveShell(resolveShellTarget(value) ?: return false)
        return true
    }

    override fun restoreBuiltinShell() {
        setBuiltinPluginEnabled(xin.dshaa.nativeapp.data.BuiltinPlugins.NATIVE_UI_SHELL, true)
        liquidUiEnabled = false
        prefs.edit().putString("dsh_shell", "native").apply()
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
        // docs/190-E：内置提示词规则开关（默认开）。关闭时只去掉打包 sections + plan:policy，
        // 角色卡/插件段/运行时上下文仍走原路径；这里留一行日志方便抓请求时对照。
        Log.i(
            "DshPrompt",
            "builtin prompt core=${if (request.includeBuiltinSections) "on" else "off"} " +
                "persona=${request.personaPrefix.length}chars system=${request.personaSuffix.length}chars " +
                "pluginSections=${request.extraSections.size}",
        )
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
        // P3-b：Node 宿主里 `ctx.llm.register` 声明的供应商。
        // callback-only 的也列出来（用户要看见「有这个东西但不可用」），但 enabled=false + 原因，
        // 绝不假装可用、也绝不悄悄从列表里消失。
        if (settings.nodePluginHostEnabled) {
            val ready = runCatching { nodePluginHost.isReady() }.getOrDefault(false)
            nodeLlmSpecs.forEach { spec ->
                val usable = spec.usable && ready
                out += ModelCandidate(
                    providerId = spec.id,
                    label = xin.dshaa.nativeapp.node.NodeLlmProviders.displayLabel(spec),
                    pluginId = null,
                    builtin = false,
                    enabled = usable,
                )
                if (!usable) {
                    nodeProviderNotes[spec.id] = when {
                        !spec.usable -> spec.reason.ifBlank { "仅 JS 流回调，原生桥接不到" }
                        else -> "Node 宿主未就绪"
                    }
                }
            }
        } else {
            nodeProviderNotes.clear()
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
                        nodeProviderNotes.containsKey(candidate.providerId) -> nodeProviderNotes[candidate.providerId]
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
     * 内置能力（notify / clipboard.write / clipboard.read / shell.status / shell.exec）与
     * 插件能力（nfc / adb-shell）、一条已合并的 root.exec（应返回 merged_into_shell_exec）
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
                // M88-B：shell 身份 / shell 执行；root.exec 已并入 shell.exec，应返回结构化拒绝。
                Triple("shell.status", "{}", "Shell 身份（只读）"),
                Triple("shell.exec", """{"command":"echo M88B-SHELL; id"}""", "Shell 执行（root→Shizuku→App）"),
                Triple("root.exec", """{"command":"id"}""", "root.exec 已合并（应返回 merged_into_shell_exec）"),
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
    // ---- Node 插件宿主（docs/185 P2） ----

    override val nodePluginHostEnabled: Boolean get() = settings.nodePluginHostEnabled

    /**
     * 打开/关闭 Node 宿主。关闭时先杀掉进程树再收回工具名——宿主没了还留着 schema，
     * 模型会一直调用一个永远失败的 `node_*` 工具。
     */
    override fun setNodePluginHostEnabled(enabled: Boolean) {
        if (settings.nodePluginHostEnabled == enabled) return
        settings.setNodePluginHostEnabled(enabled)
        // docs/190-B：用户手动开关就是明确的「再来一次/不折腾了」，清掉上次失败的跳过标记。
        clearNodeHostFailureFlag()
        if (enabled) {
            // docs/190-C：开关一拨就给全局小卡片反馈（真正的进度由 state 收集器接着刷新）。
            beginNodeBootSession("正在准备 Node 运行时")
            refreshNodeHostStatus()
            refreshNodeHostDiagnostics()
            attachNodeHost()
        } else {
            nodeBootHideJob?.cancel()
            nodeBootHideJob = null
            nodeBootProgress = NodeBootProgressUi() // hidden
            nodeHostJob?.cancel()
            nodeHostJob = null
            nodeHostStateText = ""
            nodeHostPlugins = emptyList()
            nodeHostLogs = emptyList()
            // docs/187 §九 第 11 项：关宿主后提示词段必须消失（不能只在下次刷新时才清）。
            nodePromptPid = -1
            nodePromptJob?.cancel()
            nodePromptJob = null
            nodePromptSections = emptyList()
            nodePromptContexts = emptyList()
            nodePromptVariables = emptyMap()
            // P3-b/c：供应商与命令也随宿主一起消失（模型列表回退内置、斜杠命令变回未知命令）。
            nodeRegistrationsPid = -1
            nodeLlmSpecs = emptyList()
            nodeCommandSpecs = emptyList()
            runCatching { nodePluginHost.shutdown() }
            syncNodeLlmProviders()
            syncNodeTools()
            refreshNodeHostDiagnostics()
        }
    }

    // ---- docs/190-C：全局 Node 启动小卡片 ----

    /** 开一次新的启动会话：清空「收起」、重新计时。 */
    private fun beginNodeBootSession(detail: String) {
        nodeBootHideJob?.cancel()
        nodeBootHideJob = null
        nodeBootSession++
        nodeBootStartedAt = System.currentTimeMillis()
        nodeBootDismissed = false
        nodeBootProgress = NodeBootProgressUi(
            phase = "starting",
            detail = detail,
            startedAtMs = nodeBootStartedAt,
            visible = true,
        )
    }

    /** 把宿主原始状态翻成小卡片四档；READY 1s 后自动消失，FAILED 保持。 */
    private fun updateNodeBootProgress(state: NodePluginHost.State) {
        val now = System.currentTimeMillis()
        // 新的解压（可能是开关、开机自动启动或 AI 触发）：重新计时，尊重下一次显式操作。
        // 只认 Extracting 作为「一轮启动」的起点：start() 先解压再 spawn，失败/重启循环里
        // Failed→Restarting→Starting 都是同一轮，不能重置计时（否则卡片会一直显示「已用 0s」）。
        if (state is NodePluginHost.State.Extracting) {
            val phase = nodeBootProgress.phase
            if (phase != "extracting" && phase != "starting") {
                nodeBootHideJob?.cancel()
                nodeBootHideJob = null
                nodeBootSession++
                nodeBootStartedAt = now
                nodeBootDismissed = false
            }
            if (nodeBootStartedAt <= 0L) nodeBootStartedAt = now
        } else if (state is NodePluginHost.State.Starting &&
            nodeBootProgress.phase != "extracting" && nodeBootProgress.phase != "starting" &&
            nodeBootProgress.phase != "restarting"
        ) {
            // 兜底：极少数直接进 Starting 的路径（没有解压阶段）也要开一轮并计时。
            nodeBootSession++
            nodeBootStartedAt = now
        }
        val elapsed = if (nodeBootStartedAt > 0L) (now - nodeBootStartedAt).coerceAtLeast(0L) else 0L
        Log.i(
            "DshNodeBoot",
            "progress state=${state::class.simpleName} phase=${nodeBootProgress.phase} " +
                "startedAt=$nodeBootStartedAt elapsed=${elapsed}ms dismissed=$nodeBootDismissed",
        )
        fun show(phase: String, percent: Int, detail: String, error: String = "", attempt: Int = 0) {
            if (nodeBootDismissed) return
            nodeBootProgress = NodeBootProgressUi(
                phase = phase,
                percent = percent,
                detail = detail,
                elapsedMs = elapsed,
                error = error,
                startedAtMs = nodeBootStartedAt,
                attempt = attempt,
                visible = true,
            )
        }
        when (state) {
            is NodePluginHost.State.Extracting ->
                show("extracting", state.percent, state.detail)

            is NodePluginHost.State.Starting ->
                show("starting", 100, "正在起 Node 进程并握手（通常 1–3 秒）")

            is NodePluginHost.State.Restarting ->
                show("restarting", 0, "上次退出原因：${state.reason}", attempt = state.attempt)

            is NodePluginHost.State.Ready -> {
                show(
                    "ready", 100,
                    "pid=${state.pid} · ${state.version} · 工具 ${state.toolCount} 个",
                )
                val session = nodeBootSession
                nodeBootHideJob?.cancel()
                nodeBootHideJob = viewModelScope.launch {
                    delay(NodeBootReadyVisibleMs)
                    if (nodeBootSession == session) {
                        nodeBootProgress = nodeBootProgress.copy(visible = false)
                    }
                }
            }

            is NodePluginHost.State.Failed -> {
                nodeBootHideJob?.cancel()
                nodeBootHideJob = null
                // 启动失败时进程可能还没吐任何日志：把失败原因塞进日志尾巴，别让「查看日志」一片空白。
                if (nodeHostLogs.isEmpty()) nodeHostLogs = listOf("启动失败：${state.reason}")
                show("failed", 0, "Node 宿主启动失败", error = state.reason)
            }

            NodePluginHost.State.Stopped, NodePluginHost.State.Disabled -> {
                if (!nodeBootProgress.busy) nodeBootProgress = NodeBootProgressUi() // hidden
            }
        }
    }

    override fun retryNodeBoot() {
        // 失败/已停止后重试：宿主关着时先开开关；开着就直接重载。
        // docs/190-B：重试 = 明确要再试一次，清掉「跳过自动启动」标记。
        clearNodeHostFailureFlag()
        beginNodeBootSession("正在重试启动 Node 宿主")
        if (!settings.nodePluginHostEnabled) {
            setNodePluginHostEnabled(true)
        } else {
            reloadNodeHost()
        }
    }

    override fun stopNodeBoot() {
        nodeBootHideJob?.cancel()
        nodeBootHideJob = null
        nodeBootProgress = NodeBootProgressUi() // hidden
        if (settings.nodePluginHostEnabled) setNodePluginHostEnabled(false)
    }

    override fun dismissNodeBoot() {
        nodeBootDismissed = true
        nodeBootProgress = nodeBootProgress.copy(visible = false)
    }

    /**
     * 起宿主（幂等）+ 订阅状态；失败只体现在状态文案里，不影响原生插件。
     *
     * [delayMs] > 0 时表示「随应用后台启动」：等原生首帧和插件体系就位后再起进程，
     * 别跟启动抢 CPU/内存（用户手动打开开关时传 0，立即生效）。
     * [startHost] = false 只订阅状态、不动进程（AI 的 `nodehost_*` 已经自己 reload/start，
     * 这样不会在 reload 之后又并发起一次 start）。
     */
    private fun attachNodeHost(delayMs: Long = 0, startHost: Boolean = true) {
        if (!settings.nodePluginHostEnabled) return
        if (nodeHostJob?.isActive == true) return
        nodeHostJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            launch {
                nodePluginHost.state.collect { state ->
                    nodeHostStateText = nodePluginHost.describeState()
                    refreshNodeHostDiagnostics()
                    updateNodeBootProgress(state)
                    when (state) {
                        is NodePluginHost.State.Ready -> {
                            // docs/190-B：Ready 说明这次启动成功；下次冷启动不再跳过自动启动。
                            clearNodeHostFailureFlag()
                            refreshNodePromptAsync()
                            // P3-b/c：Ready 后拉一次供应商/命令，并做模型通道注册。
                            refreshNodeRegistrations()
                            // docs/190-B：同一个插件连续两轮加载失败 → 自动隔离。
                            recordNodePluginFailures(state.plugins)
                        }

                        is NodePluginHost.State.Failed -> {
                            // docs/190-B：上次以 Failed 结束 → 下次开机跳过自动启动并显示横幅。
                            markNodeHostFailure(state.reason)
                            nodeLlmSpecs = emptyList()
                            nodeCommandSpecs = emptyList()
                            nodeRegistrationsPid = -1
                            syncNodeLlmProviders()
                        }

                        else -> {
                            // 失联/重启中：先把 Node 供应商收回（工具表在 tools 流里会自己摘）。
                            nodeLlmSpecs = emptyList()
                            nodeCommandSpecs = emptyList()
                            nodeRegistrationsPid = -1
                            syncNodeLlmProviders()
                        }
                    }
                }
            }
            launch {
                nodePluginHost.tools.collect { syncNodeTools() }
            }
            if (startHost) {
                val started = nodePluginHost.start()
                if (!started) {
                    Log.w("DshNodeHost", "node host start failed: ${nodePluginHost.describeState()}")
                }
            }
            nodeHostStateText = nodePluginHost.describeState()
            refreshNodeHostDiagnostics()
        }
    }

    // ---- docs/190-B：启动降级 / 插件隔离 / AI 维护入口 ----

    /** 读上次失败标记（只在开机时读一次；之后由 mark/clear 维护）。 */
    private fun loadNodeHostFailureState() {
        nodeHostSkippedAutoStart = prefs.getBoolean(KEY_NODE_HOST_LAST_FAILED, false)
        nodeHostLastFailureReason = prefs.getString(KEY_NODE_HOST_LAST_FAILED_REASON, null)
    }

    private fun markNodeHostFailure(reason: String) {
        nodeHostSkippedAutoStart = true
        nodeHostLastFailureReason = reason
        prefs.edit()
            .putBoolean(KEY_NODE_HOST_LAST_FAILED, true)
            .putString(KEY_NODE_HOST_LAST_FAILED_REASON, reason.take(500))
            .apply()
    }

    private fun clearNodeHostFailureFlag() {
        nodeHostSkippedAutoStart = false
        nodeHostLastFailureReason = null
        if (prefs.contains(KEY_NODE_HOST_LAST_FAILED) ||
            prefs.contains(KEY_NODE_HOST_LAST_FAILED_REASON)
        ) {
            prefs.edit()
                .remove(KEY_NODE_HOST_LAST_FAILED)
                .remove(KEY_NODE_HOST_LAST_FAILED_REASON)
                .apply()
        }
    }

    private fun readNodePluginFailCounts(): Map<String, Int> = runCatching {
        val raw = prefs.getString(KEY_NODE_PLUGIN_FAIL_COUNTS, null) ?: return emptyMap()
        val obj = org.json.JSONObject(raw)
        obj.keys().asSequence()
            .associateWith { obj.optInt(it, 0) }
            .filterValues { it > 0 }
    }.getOrDefault(emptyMap())

    private fun writeNodePluginFailCounts(counts: Map<String, Int>) {
        if (counts.isEmpty()) {
            prefs.edit().remove(KEY_NODE_PLUGIN_FAIL_COUNTS).apply()
            return
        }
        val obj = org.json.JSONObject()
        counts.forEach { (module, value) -> obj.put(module, value) }
        prefs.edit().putString(KEY_NODE_PLUGIN_FAIL_COUNTS, obj.toString()).apply()
    }

    /**
     * docs/190-B：`hello.plugins[].ok=false` 连续出现 2 次 → 卸载该插件（隔离）+ 通知 + 审计。
     *
     * v1 的归因边界：只有"宿主起得来、单个插件自己报错"能定位到插件；如果插件把宿主进程
     * 直接搞崩，`hello` 到不了，只能在 crash_loop 里体现，无法自动归因（写在 b-node/README）。
     */
    private fun recordNodePluginFailures(plugins: List<NodePluginHost.PluginStatus>) {
        if (plugins.isEmpty()) return
        val update = xin.dshaa.nativeapp.node.NodePluginFailureTracker.update(
            readNodePluginFailCounts(),
            plugins.map { it.module to it.ok },
        )
        writeNodePluginFailCounts(update.counts)
        if (update.quarantine.isEmpty() || nodeAutoQuarantineJob?.isActive == true) return
        nodeAutoQuarantineJob = viewModelScope.launch {
            val isolated = mutableListOf<String>()
            update.quarantine.forEach { module ->
                val meta = runCatching { nodePluginHost.manifestEntries().firstOrNull { it.module == module } }.getOrNull()
                val target = meta?.id?.ifBlank { module } ?: module
                val reason = "plugin failed to load ${xin.dshaa.nativeapp.node.NodePluginFailureTracker.THRESHOLD} " +
                    "times in a row (auto quarantine)"
                val removed = runCatching { nodePluginHost.quarantinePlugin(target, reason, restart = false) }.getOrNull()
                if (removed?.isSuccess == true) {
                    isolated += target
                } else {
                    Log.w("DshNodeHost", "auto quarantine failed for $target: ${removed?.exceptionOrNull()?.message}")
                }
            }
            if (isolated.isEmpty()) return@launch
            pluginNotice = "Node 插件反复加载失败，已自动隔离（卸载）：${isolated.joinToString("、")}；可重新安装修复后的包"
            nodeHostLogs = listOf("已自动隔离 Node 插件：${isolated.joinToString("、")}") + nodeHostLogs
            Log.w("DshNodeHost", "auto quarantined node plugins: $isolated")
            // 隔离后重启一次，让其余插件重新加载（失败也不影响原生功能）。
            runCatching { nodePluginHost.reload() }
            refreshNodeHostDiagnostics()
        }
    }

    // ---- docs/190-B：AI 的 nodehost_* 维护入口（与 UI 按钮同一条路径） ----

    /** AI 启动/重启宿主：清「上次失败」标记 → reload → 只订阅状态不重复 start。返回是否 Ready。 */
    private suspend fun restartNodeHostFromTool(): Boolean {
        clearNodeHostFailureFlag()
        if (!settings.nodePluginHostEnabled) {
            settings.setNodePluginHostEnabled(true)
            beginNodeBootSession("AI 请求启动 Node 宿主")
        } else if (nodePluginHost.state.value !is NodePluginHost.State.Ready) {
            beginNodeBootSession("AI 请求重启 Node 宿主")
        }
        val ok = runCatching { nodePluginHost.reload() }.getOrDefault(false)
        attachNodeHost(startHost = false)
        refreshNodeHostDiagnostics()
        refreshNodePromptAsync(force = true)
        refreshNodeRegistrations(force = true)
        return ok
    }

    /** AI 重装运行时（只换 `files/node/runtime`，插件目录不动）。 */
    private suspend fun reinstallNodeRuntimeFromTool(): Boolean {
        clearNodeHostFailureFlag()
        if (!settings.nodePluginHostEnabled) settings.setNodePluginHostEnabled(true)
        beginNodeBootSession("AI 请求重装 Node 运行时")
        val ok = runCatching { nodePluginHost.reinstallRuntime() }.getOrDefault(false)
        attachNodeHost(startHost = false)
        refreshNodeHostDiagnostics()
        refreshNodePromptAsync(force = true)
        refreshNodeRegistrations(force = true)
        return ok
    }

    /** AI 写操作后的收尾刷新（不启动宿主）。 */
    private suspend fun refreshNodeHostFromTool() {
        attachNodeHost(startHost = false)
        refreshNodeHostDiagnostics()
        if (nodePluginHost.isReady()) {
            refreshNodePromptAsync(force = true)
            refreshNodeRegistrations(force = true)
        }
    }

    /** 插件页要的：已加载模块 + 各自工具、清单条目、日志尾巴、结构化状态。 */
    private fun refreshNodeHostDiagnostics() {
        val ready = nodePluginHost.state.value as? NodePluginHost.State.Ready
        val manifest = runCatching { nodePluginHost.manifestEntries() }.getOrDefault(emptyList())
        nodeHostManifest = manifest.map { entry ->
            NodeHostManifestUi(
                id = entry.id,
                name = entry.name,
                version = entry.version,
                module = entry.module,
                hasClient = entry.hasClient,
            )
        }
        val byModule = manifest.associateBy { it.module }
        nodeHostPlugins = ready?.plugins.orEmpty().map { plugin ->
            val meta = byModule[plugin.module]
            NodeHostPluginUi(
                module = plugin.module,
                ok = plugin.ok,
                error = plugin.error,
                tools = plugin.tools,
                id = meta?.id.orEmpty(),
                name = meta?.name.orEmpty(),
                version = meta?.version.orEmpty(),
                hasClient = meta?.hasClient == true,
            )
        }
        val tail = runCatching { nodePluginHost.tailLogs(40) }.getOrDefault(emptyList())
        // 失败后进程被回收，tail 可能短暂为空；不要把上一屏日志擦掉（错误不许吞）。
        if (tail.isNotEmpty()) nodeHostLogs = tail
        refreshNodeHostStatus()
    }

    /** P4：把宿主原始状态翻译成插件页直接显示的 7 档人话状态。 */
    private fun refreshNodeHostStatus() {
        val enabled = settings.nodePluginHostEnabled
        val state = nodePluginHost.state.value
        nodeHostStatus = when {
            !enabled -> NodeHostStatusUi(
                code = "off",
                title = "已关闭（OFF）",
                detail = "Node 宿主没开：Node 插件不会加载；原生插件、聊天和工具不受影响。",
                hint = "打开开关后会在后台起 Node（首次要解压约 25MB 运行时）。",
            )

            // docs/190-B：上次以 Failed 结束 → 本次开机没自动启动（横幅同时可见）。
            nodeHostSkippedAutoStart && state is NodePluginHost.State.Disabled -> NodeHostStatusUi(
                code = "failed",
                title = "已跳过自动启动（FAILED）",
                detail = nodeHostLastFailureReason ?: "上次启动失败，本次开机没有自动拉起 Node 宿主。",
                hint = "点「重试启动」或让 AI 调 nodehost_control start；先看日志/诊断（nodehost_doctor）再动手。",
            )

            state is NodePluginHost.State.Extracting -> NodeHostStatusUi(
                code = "extracting",
                title = "正在解压（EXTRACTING）· ${state.percent}%",
                detail = state.detail.ifBlank { "正在准备 Node 运行时" },
                hint = "首次启动要解压运行时；失败会保留原因，重试会重新解压（不删已装插件）。",
            )

            state is NodePluginHost.State.Starting -> NodeHostStatusUi(
                code = "starting",
                title = "正在启动（STARTING）",
                detail = "正在起 Node 进程并握手（通常 1–3 秒）；工具要等 READY 后才并表。",
                hint = "",
            )

            state is NodePluginHost.State.Restarting -> NodeHostStatusUi(
                code = "starting",
                title = "正在重启（STARTING）· 第 ${state.attempt} 次",
                detail = "上次退出原因：${state.reason}",
                hint = "5 分钟内连续崩溃 3 次会停下来（FAILED）；可展开日志看原始错误。",
            )

            state is NodePluginHost.State.Ready -> NodeHostStatusUi(
                code = "ready",
                title = "已就绪（READY）",
                detail = "pid=${state.pid} · ${state.version} · 工具 ${state.toolCount} 个" +
                    if (state.bootMs > 0) " · 启动 ${state.bootMs}ms" else "",
                hint = "Node 工具已以 node_ 前缀并进模型工具表；关掉开关会收回这些工具名。",
            )

            state is NodePluginHost.State.Failed -> NodeHostStatusUi(
                code = "failed",
                title = "失败（FAILED）",
                detail = state.reason,
                hint = nodeHostFailureHint(state.reason),
            )

            state is NodePluginHost.State.Stopped -> NodeHostStatusUi(
                code = "stopped",
                title = "已停止（STOPPED）",
                detail = "宿主进程已停，但开关还开着；Node 工具已从模型工具表收回。",
                hint = "点「重试启动」会重新起进程；已装插件和配置不受影响。",
            )

            else -> NodeHostStatusUi(
                code = "idle",
                title = "等待启动（IDLE）",
                detail = "开关已开，宿主还没开始启动（等原生首帧/后台调度）。",
                hint = "通常 1–2 秒内会进入解压/启动；长时间不动可点「重试启动」。",
            )
        }
    }

    /** 失败时给一句可操作的话（不要只把原始异常丢给用户，也不假装正常）。 */
    private fun nodeHostFailureHint(reason: String): String = when {
        reason.contains("解压") || reason.contains("运行时安装") || reason.contains("安装包缺少") -> {
            "解压/安装运行时失败：点「重试启动」会重新解压运行时（只换运行时，不删已装插件）。"
        }

        reason.contains("缺少 libnode") || reason.contains("这个 ABI") || reason.contains("没有打包 Node") -> {
            "这个安装包/ABI 没有 Node 运行时：换 arm64 安装包；重试不会变好，可先关掉开关。"
        }

        reason.contains("连续崩溃") || reason.contains("连续启动失败") -> {
            "5 分钟内连续崩溃 3 次，已停止自动重启：先展开日志看最后几行；修好或移除出问题的插件后点「重试启动」。"
        }

        reason.contains("握手超时") || reason.contains("HOST_READY") ||
            reason.contains("进程已退出") || reason.contains("启动失败") || reason.contains("spawn") -> {
            "Node 进程没起来：展开日志看尾部原始错误；点「重试启动」会重起进程（不动原生插件）。"
        }

        else -> "点「重试启动」会重启宿主；如果还失败，展开日志把尾部错误发出来。"
    }

    /** 宿主 Ready 后拉一次插件注册的提示词段；同一进程只拉一次，`force` 用于重载后重拉。 */
    private fun refreshNodePromptAsync(force: Boolean = false) {
        val ready = nodePluginHost.state.value as? NodePluginHost.State.Ready ?: return
        if (!force && nodePromptPid == ready.pid) return
        nodePromptPid = ready.pid
        nodePromptJob?.cancel()
        nodePromptJob = viewModelScope.launch { refreshNodePrompt() }
    }

    private suspend fun refreshNodePrompt() {
        val payload = nodePluginHost.promptPayload()
        if (payload == null) {
            nodePromptSections = emptyList()
            nodePromptContexts = emptyList()
            nodePromptVariables = emptyMap()
            return
        }
        nodePromptSections = payload.sections.mapNotNull { it.toNodePromptSection() }
        nodePromptContexts = payload.contexts.mapNotNull { it.toNodePromptSection() }
        nodePromptVariables = payload.variables
        Log.i(
            "DshNodeHost",
            "prompt source=${payload.source} sections=${nodePromptSections.size} " +
                "contexts=${nodePromptContexts.size} variables=${nodePromptVariables.size} " +
                "names=${nodePromptSections.map { it.name }}",
        )
    }

    /** 宿主回的段：`{name, text, order?}`；没有 order 的排在原生段之后（900），同名互相覆盖。 */
    private fun JsonObject.toNodePromptSection(): PluginPromptSection? {
        val name = (this["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val text = (this["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (text.isBlank()) return null
        val order = (this["order"] as? JsonPrimitive)?.intOrNull ?: 900
        return PluginPromptSection(name = name, order = order, text = text)
    }

    override fun reloadNodeHost() {
        // docs/190-B：UI 的「重试启动」也是明确重来，清掉跳过自动启动标记。
        clearNodeHostFailureFlag()
        viewModelScope.launch {
            val ok = runCatching { nodePluginHost.reload() }.getOrDefault(false)
            refreshNodeHostDiagnostics()
            refreshNodePromptAsync(force = true)
            refreshNodeRegistrations(force = true)
            if (!ok) pluginNotice = "Node 宿主重试失败：${nodePluginHost.describeState()}"
        }
    }

    /**
     * P3-b / P3-c：重拉插件登记的模型供应商与斜杠命令，并把可用供应商注册进 [LlmProviders]。
     * 宿主 Ready 后调用；关宿主/失联时 [syncNodeLlmProviders] 会把这一批收回并回退内置模型。
     */
    private fun refreshNodeRegistrations(force: Boolean = false) {
        val ready = nodePluginHost.state.value as? NodePluginHost.State.Ready ?: run {
            nodeLlmSpecs = emptyList()
            nodeCommandSpecs = emptyList()
            syncNodeLlmProviders()
            return
        }
        if (!force && nodeRegistrationsPid == ready.pid) return
        nodeRegistrationsPid = ready.pid
        viewModelScope.launch {
            runCatching { nodePluginHost.refreshRegistrations() }
            nodeLlmSpecs = nodePluginHost.llmProviders.value
            nodeCommandSpecs = nodePluginHost.commands.value
            syncNodeLlmProviders()
            Log.i(
                "DshNodeHost",
                "registrations providers=${nodeLlmSpecs.map { it.id }} commands=${nodeCommandSpecs.map { it.name }}",
            )
        }
    }

    /**
     * P3-b：把 Node 宿主的可用供应商注册成 [ConfiguredLlmProvider]（走内置 HTTP 通道，真流式）。
     *
     * - 只注册 `usable` 的；callback-only 留在 [nodeProviderNotes] 里如实标不可用；
     * - 关宿主 / 换 pid：先收回这一批；当前选中的是某个 node provider 时**回退内置模型**
     *   （避免"设置里选着 node:xxx，宿主关了以后每轮都 MODEL_UNAVAILABLE"）。
     */
    private fun syncNodeLlmProviders() {
        nodeProviderIds.forEach { LlmProviders.unregister(it) }
        nodeProviderIds.clear()
        val ready = runCatching { nodePluginHost.isReady() }.getOrDefault(false)
        if (!settings.nodePluginHostEnabled || !ready) {
            val selected = settings.llmProviderId
            if (xin.dshaa.nativeapp.node.NodeLlmProviders.isNativeId(selected)) {
                settings.setLlmProviderId("")
                pluginNotice = "Node 宿主已关闭，模型回退为跟随协议"
                Log.i("DshProvider", "node host off: model fallback from $selected")
            }
            return
        }
        nodeLlmSpecs.filter { it.usable }.forEach { spec ->
            LlmProviders.register(
                ConfiguredLlmProvider(
                    LlmProviderConfig(
                        id = spec.id,
                        label = xin.dshaa.nativeapp.node.NodeLlmProviders.displayLabel(spec),
                        // 空协议 = 跟随宿主当前协议（插件不猜端点形状）。
                        protocol = spec.protocol.ifBlank { settings.protocol },
                        baseUrl = spec.baseUrl,
                        model = spec.model,
                        apiKey = spec.apiKey,
                        ownerPlugin = spec.owner,
                    ),
                    onStream = { line -> Log.i("DshProvider", "$line node=${spec.owner}") },
                ),
            )
            nodeProviderIds += spec.id
        }
        if (nodeProviderIds.isNotEmpty()) {
            Log.i("DshProvider", "node providers=${nodeProviderIds.sorted()} active=${LlmProviders.activeId}")
        }
    }

    /**
     * P3-c：执行一条 Node 插件命令（斜杠输入框命中后走这里）。
     *
     * 命令不进模型轮次，结果用 command notice 显示（与原生插件命令同一条口径）；
     * 审计由 [NodePluginHost.invokeCommand] 落 `node-audit.jsonl`。
     */
    override fun invokeNodeCommand(name: String, rawInput: String): Boolean {
        val state = chat ?: return false
        val spec = nodeCommandSpecs.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: run {
                showCommandNotice(state, "Node 命令 /$name 未注册（宿主：${nodeHostStateText.ifBlank { "未启动" }}）")
                return false
            }
        viewModelScope.launch {
            val result = runCatching {
                nodePluginHost.invokeCommand(spec.name, rawInput, NODE_COMMAND_TIMEOUT_MS)
            }.getOrElse {
                xin.dshaa.nativeapp.node.NodeCommandCall.Failure("调用失败：${it.message}", "exception", 0)
            }
            val text = when (result) {
                is xin.dshaa.nativeapp.node.NodeCommandCall.Ok ->
                    result.text.ifBlank { "Node 命令 /${spec.name} 已完成（${result.ms}ms）" }

                is xin.dshaa.nativeapp.node.NodeCommandCall.Failure ->
                    "Node 命令 /${spec.name} 失败：${result.error}"
            }
            showCommandNotice(state, text)
            Log.i(
                "DshCmd",
                "node command /${spec.name} owner=${spec.owner} " +
                    "ok=${result is xin.dshaa.nativeapp.node.NodeCommandCall.Ok} ms=${result.ms}",
            )
        }
        return true
    }

    // ---- P3-d：预设（纯数据） ----

    /** 读一次 `files/node/data/presets.json`，恢复「当前套用的预设」并刷新角色页列表。 */
    private fun refreshPresets() {
        runCatching {
            val decoded = presetStore.reload()
            activePresetId = decoded.activeId?.takeIf { id -> decoded.presets.any { it.id == id } }
            activePreset = decoded.presets.firstOrNull { it.id == activePresetId }
            presetTargetsUi = decoded.presets
            activePresetTargetUi = activePresetId
            if (decoded.errors.isNotEmpty()) {
                presetNoticeUi = decoded.errors.joinToString("；").take(400)
                Log.w("DshPreset", "presets.json 部分条目被跳过：${decoded.errors}")
            }
            Log.i(
                "DshPreset",
                "presets=${decoded.presets.map { it.id }} active=${activePresetId ?: "-"} " +
                    "file=${presetStore.file.absolutePath}",
            )
        }.onFailure { Log.w("DshPreset", "read presets failed: ${it.message}") }
    }

    /**
     * 套用预设：模型 + 权限真的落地，提示词段/工具按预设**收窄**。
     *
     * 顺序上先验模型通道（不可用就整体不套用，避免半套用），再授权限，最后记 active。
     */
    override fun applyPreset(id: String): Boolean {
        val preset = presetStore.presets().firstOrNull { it.id == id }
        ?: run {
            presetNoticeUi = "没有这个预设：$id"
            return false
        }
        if (preset.modelProviderId.isNotBlank()) {
            when (val resolution = ModelRouting.resolve(preset.modelProviderId, settings.protocol, modelCandidates())) {
                is ModelResolution.Active -> settings.setLlmProviderId(resolution.model.providerId)
                is ModelResolution.Unavailable -> {
                    presetNoticeUi = "预设「${preset.name}」的模型通道不可用：${resolution.reason}"
                    Log.w("DshPreset", "apply $id: model unavailable ${resolution.reason}")
                    return false
                }
            }
        } else {
            settings.setLlmProviderId("")
        }
        var granted = 0
        var failed = 0
        preset.permissions.forEach { (pluginId, names) ->
            val result = pluginStore.grantMany(pluginId, names.split(',').map { it.trim() }.filter { it.isNotEmpty() })
            if (result.ok) granted += 1 else failed += 1
        }
        activePresetId = preset.id
        activePreset = preset
        presetStore.setActive(preset.id)
        presetTargetsUi = presetStore.presets()
        activePresetTargetUi = preset.id
        pluginsUi = pluginStore.list()
        syncPluginTools()
        presetNoticeUi = buildString {
            append("已套用「${preset.name}」")
            append(if (granted > 0) "；授予 $granted 个插件权限" else "")
            append(if (failed > 0) "；$failed 个插件授权失败" else "")
            append("；工具 ${if (preset.tools.isEmpty()) "不限" else "${preset.tools.size} 个"}")
            append("；提示词段 ${if (preset.promptSections.isEmpty()) "不限" else "${preset.promptSections.size} 段"}")
        }
        Log.i("DshPreset", "apply id=${preset.id} model=${settings.llmProviderId.ifBlank { "follow" }} granted=$granted failed=$failed")
        return true
    }

    /** 清除套用：收窄解除（模型选择保留 —— 用户可能就是想继续用它）。 */
    override fun clearPreset() {
        val before = activePresetId
        activePresetId = null
        activePreset = null
        presetStore.setActive(null)
        presetTargetsUi = presetStore.presets()
        activePresetTargetUi = null
        presetNoticeUi = if (before == null) "当前没有套用预设" else "已清除预设「$before」：工具/提示词段恢复不限制"
        Log.i("DshPreset", "clear from=$before")
    }

    /** 把**当前状态**存成预设（模型 + 提示词段 + 工具 + 权限）；不写死任何插件。 */
    override fun savePresetFromCurrent(name: String): Boolean {
        val slug = xin.dshaa.core.plugins.PresetCodec.slug(name)
        if (slug == null) {
            presetNoticeUi = "预设名不能为空（只由字母/数字/汉字派生 id，纯符号不行）"
            return false
        }
        val preset = xin.dshaa.core.plugins.PluginPreset(
            id = slug,
            name = name.trim(),
            modelProviderId = settings.llmProviderId,
            modelLabel = activeModelUi?.label ?: settings.llmProviderId,
            promptSections = pluginPromptSections().map { it.name }.sorted(),
            tools = offeredToolNames().sorted(),
            permissions = pluginsUi
                .filter { it.grantedPermissions.isNotEmpty() }
                .associate { it.id to it.grantedPermissions.joinToString(",") },
            note = "从当前状态保存",
        )
        return presetStore.save(preset).fold(
            onSuccess = { saved ->
                presetTargetsUi = presetStore.presets()
                presetNoticeUi = "已保存预设「${saved.name}」（工具 ${saved.tools.size} / 提示词段 ${saved.promptSections.size}）"
                Log.i("DshPreset", "save id=${saved.id} file=${presetStore.file.absolutePath}")
                true
            },
            onFailure = { error ->
                presetNoticeUi = "保存预设失败：${error.message}"
                false
            },
        )
    }

    override fun deletePreset(id: String): Boolean {
        val removed = presetStore.delete(id)
        if (removed) {
            if (activePresetId == id) {
                activePresetId = null
                activePreset = null
            }
            presetTargetsUi = presetStore.presets()
            activePresetTargetUi = activePresetId
            presetNoticeUi = "已删除预设「$id」"
        } else {
            presetNoticeUi = "没有这个预设：$id"
        }
        return removed
    }

    /** `/preset [list|save <名字>|apply <名字>|off]`：输入框里的预设入口（设置页之外的第二条路）。 */
    private fun executePresetCommand(command: CommandInvocation, state: ChatSessionState) {
        val raw = command.rawInput.trim()
        if (raw.isEmpty() || raw == "list") {
            val lines = presetTargetsUi.map { preset ->
                val mark = if (preset.id == activePresetId) "✓ " else "· "
                "$mark${preset.name}（${preset.id}）：${preset.summary()}"
            }
            showCommandNotice(
                state,
                if (lines.isEmpty()) {
                    "还没有预设。用 /preset save <名字> 把当前模型+提示词段+工具+权限存一份。"
                } else {
                    "预设（${lines.size}）：\n" + lines.joinToString("\n") + "\n套用：/preset apply <名字>；清除：/preset off"
                },
            )
            return
        }
        val parts = raw.split(Regex("\\s+"), limit = 2)
        when (parts[0]) {
            "save" -> {
                val name = parts.getOrNull(1).orEmpty().trim()
                if (name.isEmpty()) {
                    showCommandNotice(state, "用法：/preset save <名字>")
                    return
                }
                savePresetFromCurrent(name)
            }

            "apply" -> {
                val name = parts.getOrNull(1).orEmpty().trim()
                val preset = presetTargetsUi.firstOrNull { it.id == name || it.name == name }
                if (preset == null) {
                    showCommandNotice(state, "没有这个预设：$name（/preset list 看已有的）")
                    return
                }
                applyPreset(preset.id)
            }

            "off", "clear" -> clearPreset()
            else -> {
                showCommandNotice(state, "用法：/preset [list|save <名字>|apply <名字>|off]")
                return
            }
        }
        showCommandNotice(state, presetNoticeUi ?: "预设已更新")
    }

    override fun importNodePlugin(uri: Uri) {
        viewModelScope.launch {
            val name = runCatching { uri.lastPathSegment?.substringAfterLast('/') }.getOrNull().orEmpty()
            val result = runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("打不开这个文件：$uri")
                }
                nodePluginHost.installPluginZip(bytes, name.ifBlank { "plugin" }).getOrThrow()
            }
            result.onSuccess { specifier ->
                Log.i("DshNodeHost", "node plugin imported: $specifier → ${nodePluginHost.describeState()}")
                refreshNodePromptAsync(force = true)
            }.onFailure { error ->
                Log.w("DshNodeHost", "node plugin import failed: ${error.message}")
            }
            refreshNodeHostDiagnostics()
        }
    }

    /**
     * P4：统一导入第一步——选 zip、识别归属、查双装，**不直接安装**。
     *
     * 非 zip（.md/.json 声明式包）保持旧路径直接按原生装；zip 一律先出确认对话框，
     * 用户可以在「原生 / Node」之间改选。
     */
    override fun beginPluginImport(uri: Uri) {
        if (pluginsBusy) return
        pluginsBusy = true
        pluginNotice = null
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { copyImportToCache(uri) }
            if (file == null) {
                pluginsBusy = false
                pluginNotice = "选中的文件打不开或太大（上限 64MB）"
                return@launch
            }
            val isZip = withContext(Dispatchers.IO) { looksLikeZip(file) }
            if (!isZip) {
                // 非 zip：保持 0.3.5/0.3.6 的声明式导入路径（没有归属可识别）。
                val result = pluginStore.installFile(file)
                pluginsUi = pluginStore.list()
                pluginNotice = result.message
                pluginsBusy = false
                file.delete()
                syncPluginTools()
                return@launch
            }
            val read = withContext(Dispatchers.IO) { PluginPackage.read(file) }
            if (read is xin.dshaa.core.plugins.ArchiveRead.Failed) {
                pluginsBusy = false
                pluginNotice = "插件包读取失败：${read.error}"
                file.delete()
                return@launch
            }
            val summary = withContext(Dispatchers.IO) {
                (read as xin.dshaa.core.plugins.ArchiveRead.Ok).let { PluginPackage.summarize(it.files) }
            }
            if (summary == null) {
                // zip 里没有可识别清单：交给原生导入给出原来的错误，不假装能识别。
                val result = pluginStore.installFile(file)
                pluginsUi = pluginStore.list()
                pluginNotice = result.message
                pluginsBusy = false
                file.delete()
                syncPluginTools()
                return@launch
            }
            val nativeInstalled = pluginStore.list().any { it.id.equals(summary.id, ignoreCase = true) }
            val nodeInstalled = runCatching {
                nodePluginHost.manifestEntries().any { it.id.equals(summary.id, ignoreCase = true) }
            }.getOrDefault(false)
            pendingPluginImport = file
            pluginImportDraft = PluginImportDraftUi(
                id = summary.id,
                name = summary.name,
                version = summary.version,
                description = summary.description,
                ownership = summary.ownership,
                signals = summary.signals,
                hasClient = summary.hasClient,
                sourceName = uri.lastPathSegment?.substringAfterLast('/').orEmpty(),
                nativeInstalled = nativeInstalled,
                nodeInstalled = nodeInstalled,
                limitsNative = PluginStore.limitsText(),
                limitsNode = NodePluginInstaller.limitsText(),
            )
            pluginsBusy = false
            Log.i(
                "DshPlugin",
                "import draft id=${summary.id} name=${summary.name} ownership=${summary.ownership} " +
                    "signals=${summary.signals} native=$nativeInstalled node=$nodeInstalled",
            )
        }
    }

    /**
     * P4：确认安装。先装新的一侧，成功后才删旧侧（迁移失败不会把用户原有的插件弄丢）；
     * 同 id 已装在另一侧时这一步就是迁移，旧配置会随旧目录删除而重置。
     */
    override fun confirmPluginImport(ownership: String) {
        val pending = pendingPluginImport ?: return
        val draft = pluginImportDraft ?: return
        if (ownership != PluginOwnership.NATIVE && ownership != PluginOwnership.NODE) {
            pluginNotice = "不认识的归属：$ownership（只支持原生 / Node）"
            return
        }
        if (pluginsBusy) return
        pluginsBusy = true
        pluginNotice = null
        viewModelScope.launch {
            val warnings = ArrayList<String>()
            val result = runCatching {
                if (ownership == PluginOwnership.NATIVE) {
                    val installed = pluginStore.installFile(
                        pending,
                        ownership = PluginOwnership.NATIVE,
                    )
                    if (!installed.ok) error(installed.message)
                    if (draft.nodeInstalled) {
                        val removed = nodePluginHost.uninstallPlugin(
                            draft.id,
                            restart = settings.nodePluginHostEnabled,
                        )
                        if (removed.isFailure) {
                            warnings += "Node 副本没删掉：" + (removed.exceptionOrNull()?.message ?: "未知原因")
                        } else {
                            warnings += "已从 Node 侧移除同 id 副本（配置重置）"
                        }
                    }
                    pluginsUi = pluginStore.list()
                    withContext(Dispatchers.IO) { pluginStore.reconcileServices() }
                    pluginsUi = pluginStore.list()
                    syncPluginTools()
                    refreshNodeHostDiagnostics()
                    installed.message + "（归属：原生）"
                } else {
                    if (!settings.nodePluginHostEnabled) {
                        setNodePluginHostEnabled(true)
                        warnings += "已打开 Node 宿主开关"
                    }
                    val bytes = withContext(Dispatchers.IO) { pending.readBytes() }
                    val installed = nodePluginHost.installPluginZip(
                        bytes = bytes,
                        fallbackName = pending.name,
                        resetConfig = draft.nodeInstalled,
                    ).getOrThrow()
                    if (draft.nativeInstalled) {
                        val removed = pluginStore.uninstall(draft.id)
                        if (!removed.ok) {
                            warnings += "原生副本没删掉：" + removed.message
                        } else {
                            warnings += "已从原生侧移除同 id 副本（配置重置）"
                        }
                        pluginsUi = pluginStore.list()
                        syncPluginTools()
                    }
                    refreshNodeHostDiagnostics()
                    refreshNodePromptAsync(force = true)
                    "已导入 ${installed.name} ${installed.version}（归属：Node · ${installed.module}）"
                }
            }
            result.onSuccess { message ->
                pluginNotice = message + if (warnings.isEmpty()) "" else "；" + warnings.joinToString("；")
                clearImportDraft()
            }.onFailure { error ->
                pluginNotice = "导入失败：${error.message ?: error.javaClass.simpleName}（没有删掉旧侧的插件）"
            }
            pluginsBusy = false
        }
    }

    override fun cancelPluginImport() {
        clearImportDraft()
    }

    private fun clearImportDraft() {
        val file = pendingPluginImport
        pendingPluginImport = null
        pluginImportDraft = null
        if (file != null) runCatching { file.delete() }
    }

    /** URI → cache zip；超过 64MB 直接拒绝（插件页会显示同一上限）。 */
    private fun copyImportToCache(uri: Uri): File? = runCatching {
        val target = File(
            getApplication<Application>().cacheDir,
            "plugin-import-${System.currentTimeMillis()}.zip",
        )
        val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: return null
        input.use { stream ->
            target.outputStream().use { out ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > IMPORT_MAX_BYTES) error("插件包太大（>${IMPORT_MAX_BYTES / 1024 / 1024}MB）")
                    out.write(buffer, 0, read)
                }
            }
        }
        if (target.length() == 0L) error("选中的文件是空的")
        target
    }.getOrNull()

    private fun looksLikeZip(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val magic = ByteArray(2)
            stream.read(magic) == 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)

    private fun syncNodeTools() {        runCatching {
            val tools = nodePluginHost.dshTools()
            val names = tools.map { it.name }.toSet()
            (nodeToolNames - names).forEach { registry.unregister(it) }
            nodeToolNames.clear()
            tools.forEach { tool ->
                if (registry.register(tool)) {
                    nodeToolNames += tool.name
                } else {
                    Log.w("DshNodeHost", "工具名冲突，未注入：${tool.name}")
                }
            }
            nodeApiTools = nodePluginHost.apiTools().filter { it.name in nodeToolNames }
            nodeHostStateText = nodePluginHost.describeState()
            Log.i("DshNodeHost", "merge tools=${nodeToolNames.size} apiTools=${nodeApiTools.size} names=$nodeToolNames")
        }.onFailure { Log.w("DshNodeHost", "sync node tools failed: ${it.message}") }
    }

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
        // M87-A：插件启用/停用/重载后重建工具中间件链（常驻运行时起来后会再触发一次）。
        rebuildPluginToolChain()
        // M87-B：请求/提示词中间件链同理（同一个热换点）。
        rebuildPluginRequestChain()
        // M87-C：插件插槽（启用 + ui:slot 授权才算生效；停用即从注册表摘掉）。
        syncPluginSlots()
        // M87-F：插件清单/常驻元数据变了，扩展点快照跟着重算。
        refreshPluginExtensions()
    }

    /**
     * M87-A：按「启用中的插件 + 常驻运行时的注册元数据」重建工具中间件链。
     *
     * 链顺序 = 插件清单顺序（= 启用顺序，docs/181 §3.1 不引入隐式优先级）。
     * 没有插件注册时回到 [ToolMiddlewareChain.EMPTY]（零开销、请求体与 0.3.4 一致）。
     */
    private fun rebuildPluginToolChain() {
        val middlewares = ArrayList<ToolMiddleware>()
        pluginsUi.filter { it.enabled }.forEach { record ->
            val meta = pluginResidentRuntime.toolMiddlewareOf(record.id) ?: return@forEach
            if (meta.isEmpty) return@forEach
            val interceptGranted = PluginPermissions.toolsInterceptGranted(
                record.permissions,
                record.grantedPermissions,
            )
            if (meta.hasHooks && !interceptGranted) {
                Log.w("DshExt", "tools:intercept not granted plugin=${record.id}; hooks disabled (restrict/guard still allowed)")
            }
            middlewares += PluginToolMiddleware(
                pluginId = record.id,
                pluginName = record.name,
                meta = meta,
                interceptGranted = interceptGranted,
                dispatch = { method, payload -> dispatchPluginToolMiddleware(record.id, method, payload) },
                scope = viewModelScope,
                log = { message -> Log.i("DshExt", message) },
            )
        }
        val chain = if (middlewares.isEmpty()) {
            ToolMiddlewareChain.EMPTY
        } else {
            ToolMiddlewareChain(
                middlewares,
                onDiagnostic = { diagnostic ->
                    Log.w(
                        "DshExt",
                        "middleware=${diagnostic.middlewareId} phase=${diagnostic.phase} error=${diagnostic.error}",
                    )
                },
            )
        }
        // M87-F：总开关关闭时整条链旁路（插件清单/常驻运行时不动，打开即恢复）。
        pluginToolChain = PluginMiddlewareSwitch.bypass(chain, ToolMiddlewareChain.EMPTY)
        if (!PluginMiddlewareSwitch.enabled) {
            if (!chain.isEmpty) Log.i("DshExt", "tool middleware chain bypassed (switch off) size=${chain.size}")
        } else if (!chain.isEmpty) {
            Log.i(
                "DshExt",
                "tool middleware chain rebuilt size=${chain.size} ids=${chain.ids.joinToString(",")}",
            )
        }
        refreshPluginExtensions()
    }

    /** M87-A：宿主 → 常驻运行时的一次中间件 dispatch（50ms 预算；失败只记诊断，不打断回合）。 */
    private fun dispatchPluginToolMiddleware(pluginId: String, method: String, payload: String): String? {
        val startedAt = System.currentTimeMillis()
        val outcome = pluginResidentRuntime.dispatch(pluginId, method, payload, TOOL_MIDDLEWARE_TIMEOUT_MS)
        val ms = System.currentTimeMillis() - startedAt
        if (outcome == null || outcome.status != JsStatus.COMPLETED) {
            Log.w(
                "DshExt",
                "middleware dispatch failed plugin=$pluginId method=$method " +
                    "status=${outcome?.status ?: "NOT_RESIDENT"} error=${outcome?.error ?: "-"}",
            )
            recordExtensionDispatch(
                pluginId, method, ok = false,
                error = outcome?.error ?: outcome?.status?.name ?: "NOT_RESIDENT", ms = ms,
            )
            return null
        }
        recordExtensionDispatch(
            pluginId, method, ok = true, error = null, ms = ms,
            payload = payload.takeIf { method == "tools.postExecute" },
            reply = outcome.valueJson.takeIf { method == "tools.postExecute" },
        )
        return outcome.valueJson
    }

    /**
     * M87-B：按「启用中的插件 + 常驻运行时的注册元数据」重建请求/提示词中间件链。
     *
     * 链顺序 = 插件清单顺序（= 启用顺序，docs/181 §3.1）。没有插件注册时回到
     * [RequestMiddlewareChain.EMPTY]（零开销、请求体/system 与 0.3.4 逐字节相同）。
     * 申请了 `llm:request` 但没授权的插件不挂链（日志与插件页可见，不静默生效）。
     */
    private fun rebuildPluginRequestChain() {
        val middlewares = ArrayList<RequestMiddleware>()
        pluginsUi.filter { it.enabled }.forEach { record ->
            val meta = pluginResidentRuntime.llmMiddlewareOf(record.id) ?: return@forEach
            if (meta.isEmpty) return@forEach
            val granted = PluginPermissions.llmRequestGranted(
                record.permissions,
                record.grantedPermissions,
            )
            if (!granted) {
                Log.w(
                    "DshExt",
                    "llm:request not granted plugin=${record.id}; request middleware disabled",
                )
                return@forEach
            }
            middlewares += PluginRequestMiddleware(
                pluginId = record.id,
                pluginName = record.name,
                meta = meta,
                granted = true,
                dispatch = { method, payload -> dispatchPluginRequestMiddleware(record.id, method, payload) },
                log = { message -> Log.i("DshExt", message) },
            )
        }
        val chain = if (middlewares.isEmpty()) {
            RequestMiddlewareChain.EMPTY
        } else {
            RequestMiddlewareChain(
                middlewares,
                onDiagnostic = { diagnostic ->
                    Log.w(
                        "DshExt",
                        "middleware=${diagnostic.middlewareId} phase=${diagnostic.phase} error=${diagnostic.error}",
                    )
                },
            )
        }
        // M87-F：总开关关闭时整条链旁路。
        pluginRequestChain = PluginMiddlewareSwitch.bypass(chain, RequestMiddlewareChain.EMPTY)
        if (!PluginMiddlewareSwitch.enabled) {
            if (!chain.isEmpty) Log.i("DshExt", "request middleware chain bypassed (switch off) size=${chain.size}")
        } else if (!chain.isEmpty) {
            Log.i(
                "DshExt",
                "request middleware chain rebuilt size=${chain.size} ids=${chain.ids.joinToString(",")}",
            )
        }
        refreshPluginExtensions()
    }

    /** M87-B：宿主 → 常驻运行时的一次请求中间件 dispatch（50ms 预算；失败只记诊断）。 */
    private fun dispatchPluginRequestMiddleware(pluginId: String, method: String, payload: String): String? {
        val startedAt = System.currentTimeMillis()
        val outcome = pluginResidentRuntime.dispatch(pluginId, method, payload, PluginRequestMiddleware.TIMEOUT_MS)
        val ms = System.currentTimeMillis() - startedAt
        if (outcome == null || outcome.status != JsStatus.COMPLETED) {
            Log.w(
                "DshExt",
                "request middleware dispatch failed plugin=$pluginId method=$method " +
                    "status=${outcome?.status ?: "NOT_RESIDENT"} error=${outcome?.error ?: "-"}",
            )
            recordExtensionDispatch(
                pluginId, method, ok = false,
                error = outcome?.error ?: outcome?.status?.name ?: "NOT_RESIDENT", ms = ms,
            )
            return null
        }
        recordExtensionDispatch(pluginId, method, ok = true, error = null, ms = ms)
        return outcome.valueJson
    }

    // ---- M87-F：扩展点诊断（设置里的总开关 + 插件页「扩展点」页签） ----

    /**
     * M87-F：设置里的「插件中间件」总开关。
     *
     * 关掉：工具链/请求链换成空链、写入钩子整条旁路（`SessionWriteHooks.bypass`）、
     * 常驻运行时不再投递 `session/event` 与 after-load；插槽/工具注册不受影响。
     * 打开：按当前插件清单重建两条链，写入钩子恢复。两个方向都立即生效（不用重启）。
     */
    override fun setPluginMiddlewareEnabled(enabled: Boolean) {
        settings.setPluginMiddlewareEnabled(enabled)
        PluginMiddlewareSwitch.set(enabled)
        SessionWriteHooks.bypass = !enabled
        rebuildPluginToolChain()
        rebuildPluginRequestChain()
        refreshPluginExtensions()
        pluginNotice = if (enabled) {
            "插件中间件已开启"
        } else {
            "插件中间件已关闭：工具/请求/存储/事件钩子已旁路（插槽与插件注册不受影响）"
        }
        Log.i(
            "DshExt",
            "plugin middleware switch enabled=$enabled toolChain=${pluginToolChain.size} " +
                "requestChain=${pluginRequestChain.size} sessionHooksBypass=${SessionWriteHooks.bypass}",
        )
    }

    /** M87-F：记一次 dispatch 结果（命中/异常/最近耗时；payload/reply 非空时记前置/后置 SHA-256）。 */
    private fun recordExtensionDispatch(
        pluginId: String,
        phase: String,
        ok: Boolean,
        error: String?,
        ms: Long,
        payload: String? = null,
        reply: String? = null,
    ) {
        val stat = extensionStats.getOrPut("$pluginId\u0000$phase") { ExtensionStat() }
        synchronized(stat) {
            if (ok) {
                stat.hits++
            } else {
                stat.errors++
                stat.lastError = error ?: "运行时不可用/超时"
            }
            stat.lastMs = ms
            payload?.let { stat.lastInHash = sha256Short(it) }
            reply?.let { stat.lastOutHash = sha256Short(it) }
        }
    }

    /** M87-F：记一次拒绝（veto / guard），拒绝也是一次命中。 */
    private fun recordExtensionDeny(pluginId: String, phase: String, reason: String, ms: Long) {
        val stat = extensionStats.getOrPut("$pluginId\u0000$phase") { ExtensionStat() }
        synchronized(stat) {
            stat.hits++
            stat.denies++
            stat.lastMs = ms
            stat.lastError = reason
        }
    }

    /** 改写诊断用的短哈希（前 12 个十六进制字符；只证明「变了」，不泄露正文）。 */
    private fun sha256Short(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        return runCatching {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(12)
        }.getOrNull()
    }

    /**
     * M88-A：按插件清单顺序生成「扩展点」快照。
     *
     * 数据源只有宿主已经拿到的：`PluginRecord` 声明 + 常驻运行时注册元数据 + 本进程命中统计。
     * 停用插件**不出现**（与角色页同一规则）；启用但未常驻的插件保留「未常驻」提示，不冒充生效。
     * 行构造是 `ui:shell-api` 的纯函数 [PluginExtensionRows.build]（JVM 可测）。
     */
    override fun refreshPluginExtensions() {
        val inputs = pluginsUi.map { record ->
            val toolMeta = pluginResidentRuntime.toolMiddlewareOf(record.id)
            val llmMeta = pluginResidentRuntime.llmMiddlewareOf(record.id)
            val storageMeta = pluginResidentRuntime.storageHooksOf(record.id)
            PluginExtensionInput(
                pluginId = record.id,
                pluginName = record.name,
                enabled = record.enabled,
                permissions = record.permissions,
                grantedPermissions = record.grantedPermissions,
                toolMiddleware = record.toolMiddleware,
                llmMiddleware = record.llmMiddleware,
                storageHooks = record.storageHooks,
                slots = record.slots.map { PluginSlotInput(it.slot, it.id, it.order) },
                tool = toolMeta?.let {
                    PluginToolExtensionInput(
                        restrict = it.restrict,
                        guard = it.guard,
                        describe = it.describe.map { patch -> patch.tool },
                        preExecute = it.preExecute,
                        postExecute = it.postExecute,
                        result = it.result,
                    )
                },
                llm = llmMeta?.let {
                    PluginLlmExtensionInput(
                        assemble = it.assemble,
                        request = it.request,
                        stream = it.stream,
                        preStep = it.preStep,
                    )
                },
                storage = storageMeta?.let {
                    PluginStorageExtensionInput(
                        beforeAppend = it.beforeAppend,
                        afterLoad = it.afterLoad,
                        subscriptions = pluginResidentRuntime.subscriptionsOf(record.id),
                    )
                },
            )
        }
        pluginExtensionsUi = PluginExtensionRows.build(
            inputs = inputs,
            switchOn = PluginMiddlewareSwitch.enabled,
            stats = { pluginId, phase, hashes -> statsText(pluginId, phase, hashes) },
        )
    }

    /** M87-F：把某条链的统计格式化成一行（无数据就说「尚未命中」，不编数）。 */
    private fun statsText(pluginId: String, phase: String, hashes: Boolean = false): String {
        val stat = extensionStats["$pluginId\u0000$phase"] ?: return "尚未命中（本次进程）"
        return buildString {
            append("命中 ${stat.hits}")
            if (stat.denies > 0) append(" · 拒绝 ${stat.denies}")
            append(" · 异常 ${stat.errors}")
            append(" · 最近 ${stat.lastMs}ms")
            if (hashes && stat.lastInHash != null) {
                append(" · 哈希 ${stat.lastInHash}→${stat.lastOutHash ?: "-"}")
            }
        }
    }


    /**
     * M87-C：按「启用 + ui:slot 已授权」把插件声明同步进原生壳的插槽注册表。
     *
     * 顺序 = 插件清单顺序（`pluginsUi` 顺序），槽位内部再按 `order`；停用/删除/取消授权后
     * 立即从注册表摘掉（`removePlugin`），原生页面于是恢复原样（不会留白块）。
     * `state.*` 绑定只读插件动作返回值；`view.*` 绑定到该插件当前会话的投影 `wire.view`。
     */
    private fun syncPluginSlots() {
        val registry = pluginSlotRegistry
        registry.viewResolver = resolver@ { pluginId, path ->
            val sid = conversation.state?.sessionId?.takeIf { it.isNotBlank() } ?: return@resolver null
            val key = path.substringBefore('.')
            pluginResidentRuntime.valuesFor(sid)
                .firstOrNull { it.pluginId == pluginId && it.key == key }
                ?.viewJson
        }
        registry.actionHandler = { entry, action, payload ->
            dispatchPluginSlotAction(entry, action, payload)
        }
        val active = HashSet<String>()
        pluginsUi.filter { it.enabled }.forEach { record ->
            if (record.slots.isEmpty()) return@forEach
            if (!PluginPermissions.uiSlotGranted(record.permissions, record.grantedPermissions)) {
                Log.w("DshExt", "ui:slot not granted plugin=${record.id}; slots disabled")
                return@forEach
            }
            active += record.id
            registry.syncPlugin(record.id, record.name, record.slots)
        }
        // 已经不在「启用 + 授权」集合里的插件：清掉残留（停用/删除/取消授权）。
        registry.all().map { it.pluginId }.distinct().filter { it !in active }.forEach { registry.removePlugin(it) }
        if (active.isNotEmpty()) {
            Log.i("DshExt", "slot registry synced plugins=${active.joinToString(",")} entries=${registry.all().size}")
        }
    }

    /** M87-C：一次插槽动作 dispatch（用户点击；500ms 预算；失败只记诊断，不影响其它条目）。 */
    private fun dispatchPluginSlotAction(entry: SlotRegistry.Entry, action: String, payloadJson: String?) {
        val stateJson = pluginSlotRegistry.fresh(entry)?.stateJson ?: entry.stateJson
        val body = buildJsonObject {
            put("action", action)
            put("slot", entry.slot.slot)
            put("id", entry.slot.id)
            put("state", runCatching { Json.parseToJsonElement(stateJson) }.getOrElse { buildJsonObject { } })
            payloadJson?.let { raw ->
                runCatching { put("payload", Json.parseToJsonElement(raw)) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = pluginResidentRuntime.dispatch(
                entry.pluginId,
                "ui.slot.action",
                body.toString(),
                SLOT_ACTION_TIMEOUT_MS,
            )
            if (outcome == null || outcome.status != JsStatus.COMPLETED) {
                Log.w(
                    "DshExt",
                    "slot action failed plugin=${entry.pluginId} slot=${entry.slot.key} action=$action " +
                        "status=${outcome?.status ?: "NOT_RESIDENT"} error=${outcome?.error ?: "-"}",
                )
                return@launch
            }
            val reply = runCatching { Json.parseToJsonElement(outcome.valueJson ?: "null").jsonObject }.getOrNull()
            if (reply == null || reply["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == false) {
                Log.w(
                    "DshExt",
                    "slot action rejected plugin=${entry.pluginId} slot=${entry.slot.key} action=$action " +
                        "error=${reply?.get("error")?.jsonPrimitive?.contentOrNull ?: "bad-json"}",
                )
                return@launch
            }
            val next = reply["state"] as? JsonObject ?: return@launch
            val updated = pluginSlotRegistry.updateState(entry.pluginId, entry.slot.key, next.toString())
            Log.i(
                "DshExt",
                "slot action ok plugin=${entry.pluginId} slot=${entry.slot.key} action=$action updated=$updated",
            )
        }
    }

    /**
     * M87-E（docs/181 §3.5）：安装会话写入钩子。
     *
     * 两层：
     * 1. `storage:hooks` 插件的 `before-append`：可拒绝 user/message、tool/result 落盘；
     * 2. `session/event` 订阅者对**插件记录**（`source.kind=plugin-*`）的 veto：普通会话事件不可 veto。
     *
     * 钩子由 [SessionSession.appendRaw] 在同步写路径上调用；实现方必须快速返回（单插件 50ms），
     * 超时/异常一律放行并记 `DshExt` 诊断，不能让插件故障阻塞会话落库。
     */
    private fun installSessionWriteHooks() {
        if (SessionWriteHooks.gate != null) return
        SessionWriteHooks.gate = object : SessionAppendGate {
            override fun beforeAppend(event: PendingSessionEvent): SessionAppendDecision =
                if (PluginMiddlewareSwitch.enabled) evaluateAppendHooks(event) else SessionAppendDecision()
        }
        Log.i("DshExt", "session write hooks installed (storage:hooks + plugin-record veto)")
    }

    /**
     * M87-F：把设置里的「插件中间件」总开关同步到进程级开关。
     * `PluginMiddlewareSwitch` 负责工具/请求链与事件投递；`SessionWriteHooks.bypass` 负责写钩子。
     */
    private fun syncPluginMiddlewareSwitch() {
        PluginMiddlewareSwitch.set(settings.pluginMiddlewareEnabled)
        SessionWriteHooks.bypass = !PluginMiddlewareSwitch.enabled
        Log.i(
            "DshExt",
            "plugin middleware switch enabled=${PluginMiddlewareSwitch.enabled} " +
                "sessionHooksBypass=${SessionWriteHooks.bypass}",
        )
    }

    private fun evaluateAppendHooks(event: PendingSessionEvent): SessionAppendDecision {
        val storage = dispatchStorageBeforeAppend(event)
        if (!storage.allow) return storage
        // session/event 只对插件记录（插件注入的 user/message）开放 veto；普通事件不允许。
        if (event.pluginRecord) return dispatchPluginRecordVeto(event)
        return SessionAppendDecision()
    }

    /** `storage:hooks` 的 before-append：按插件清单顺序问，第一个拒绝生效。 */
    private fun dispatchStorageBeforeAppend(event: PendingSessionEvent): SessionAppendDecision {
        val candidates = pluginsUi.filter { record ->
            record.enabled && record.storageHooks &&
                PluginPermissions.storageHooksGranted(record.permissions, record.grantedPermissions) &&
                (pluginResidentRuntime.storageHooksOf(record.id)?.beforeAppend ?: 0) > 0
        }
        if (candidates.isEmpty()) return SessionAppendDecision()
        val data = runCatching { Json.parseToJsonElement(event.dataJson) }.getOrNull()
            ?: return SessionAppendDecision()
        val payload = buildJsonObject {
            put(
                "event",
                buildJsonObject {
                    put("type", event.type)
                    put("sessionId", event.sessionId)
                    put("sourceKind", event.sourceKind ?: "")
                    put("data", data)
                    event.surfaceOpJson?.let { op ->
                        runCatching { put("surfaceOp", Json.parseToJsonElement(op)) }
                    }
                },
            )
        }.toString()
        for (record in candidates) {
            val startedAt = System.currentTimeMillis()
            val outcome = pluginResidentRuntime.dispatch(record.id, "storage.beforeAppend", payload, HOOK_TIMEOUT_MS)
            val ms = System.currentTimeMillis() - startedAt
            if (outcome == null || outcome.status != JsStatus.COMPLETED) {
                Log.w(
                    "DshExt",
                    "storage before-append skipped plugin=${record.id} type=${event.type} " +
                        "status=${outcome?.status ?: "NOT_RESIDENT"} error=${outcome?.error ?: "-"}",
                )
                recordExtensionDispatch(
                    record.id, "storage.beforeAppend", ok = false,
                    error = outcome?.error ?: outcome?.status?.name ?: "NOT_RESIDENT", ms = ms,
                )
                continue
            }
            val reply = runCatching { Json.parseToJsonElement(outcome.valueJson ?: "null").jsonObject }.getOrNull()
            val allow = reply?.get("allow")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
            if (!allow) {
                val reason = reply?.get("reason")?.jsonPrimitive?.contentOrNull ?: "插件拒绝写入"
                Log.w(
                    "DshExt",
                    "storage before-append veto plugin=${record.id} type=${event.type} " +
                        "source=${event.sourceKind ?: "-"} reason=$reason",
                )
                recordExtensionDeny(record.id, "storage.beforeAppend", reason, ms)
                return SessionAppendDecision(
                    allow = false,
                    reason = "插件「${record.name}」拒绝写入 ${event.type}：$reason",
                    pluginId = record.id,
                )
            }
            recordExtensionDispatch(record.id, "storage.beforeAppend", ok = true, error = null, ms = ms)
        }
        return SessionAppendDecision()
    }

    /** 插件记录的 session/event veto：只问订阅了该事件 / `session/event` / `*` 的启用插件，第一个 veto 生效。 */
    private fun dispatchPluginRecordVeto(event: PendingSessionEvent): SessionAppendDecision {
        val candidates = pluginsUi.filter { record ->
            record.enabled && pluginResidentRuntime.subscriptionsOf(record.id).any { name ->
                name == "session/event" || name == event.type || name == "*"
            }
        }
        if (candidates.isEmpty()) return SessionAppendDecision()
        val data = runCatching { Json.parseToJsonElement(event.dataJson) }.getOrNull()
            ?: return SessionAppendDecision()
        val payload = buildJsonObject {
            put("sessionId", event.sessionId)
            put(
                "event",
                buildJsonObject {
                    put("type", event.type)
                    put("sessionId", event.sessionId)
                    put("sourceKind", event.sourceKind ?: "")
                    put("data", data)
                },
            )
        }.toString()
        for (record in candidates) {
            val startedAt = System.currentTimeMillis()
            val outcome = pluginResidentRuntime.dispatch(record.id, "session.veto", payload, HOOK_TIMEOUT_MS)
            val ms = System.currentTimeMillis() - startedAt
            if (outcome == null || outcome.status != JsStatus.COMPLETED) {
                Log.w(
                    "DshExt",
                    "session veto skipped plugin=${record.id} type=${event.type} " +
                        "status=${outcome?.status ?: "NOT_RESIDENT"} error=${outcome?.error ?: "-"}",
                )
                recordExtensionDispatch(
                    record.id, "session.veto", ok = false,
                    error = outcome?.error ?: outcome?.status?.name ?: "NOT_RESIDENT", ms = ms,
                )
                continue
            }
            val reply = runCatching { Json.parseToJsonElement(outcome.valueJson ?: "null").jsonObject }.getOrNull()
            val veto = reply?.get("veto")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            if (veto) {
                val reason = reply?.get("reason")?.jsonPrimitive?.contentOrNull ?: "插件否决了这条插件记录"
                Log.w(
                    "DshExt",
                    "session veto plugin=${record.id} type=${event.type} source=${event.sourceKind ?: "-"} reason=$reason",
                )
                recordExtensionDeny(record.id, "session.veto", reason, ms)
                return SessionAppendDecision(
                    allow = false,
                    reason = "插件「${record.name}」否决了 ${event.type} 记录：$reason",
                    pluginId = record.id,
                )
            }
            recordExtensionDispatch(record.id, "session.veto", ok = true, error = null, ms = ms)
        }
        return SessionAppendDecision()
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
        val record = pluginsUi.firstOrNull { it.id == id }
        pluginStore.setEnabled(id, enabled)
        pluginsUi = pluginStore.list()
        // 0.3.4（用户第 6 条）：停用正在使用的插件 → 静默回退内置/native/跟随协议 + 一次 notice；
        // 不留红字、不拦发送；重新启用后也不会自动恢复旧选择（pref 已被清掉）。
        val fallback = if (!enabled && record != null) clearRoleSelection(record) else emptyList()
        syncPluginTools()
        val fallbackText = if (fallback.isNotEmpty()) "，已回退内置${fallback.joinToString("、")}" else ""
        if (fallback.isNotEmpty()) pluginNotice = "已停用 ${record?.name ?: id}$fallbackText"
        // M83 服务容器：启用集合变了要重算依赖——关掉提供者后，依赖它的插件会变成
        // 「缺少服务 X」且不再注册工具；开回来恢复。apply 重跑放 IO 线程。
        viewModelScope.launch {
            val (reloaded, missing) = withContext(Dispatchers.IO) { pluginStore.reconcileServices() }
            pluginsUi = pluginStore.list()
            syncPluginTools()
            Log.i("DshPlugin", "services reconcile: reloaded=$reloaded missingPlugins=$missing")
            pluginNotice = if (missing > 0) {
                "启用状态已更新$fallbackText：$missing 个插件缺少依赖服务（见插件详情）"
            } else {
                "启用状态已更新$fallbackText（重算 $reloaded 个插件的依赖）"
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

    /**
     * UI 删除插件（0.3.4）：确认弹窗已经在插件页点过，这里带 `force = true`；内部仍走共享实现，
     * 所以角色选择、常驻运行时、工具注册、依赖重算都不会被绕过。
     */
    override fun deletePlugin(id: String) {
        viewModelScope.launch {
            val result = deletePluginInternal(id, force = true)
            if (!result.ok) pluginNotice = result.message
        }
    }

    /**
     * 0.3.4（用户第 5/7 条）：UI 与 AI 工具共用的删除实现。
     *
     * 闸门 A（用户第 7 条）：默认拒绝删除「正在使用 / 运行中」的插件并返回原因；模型可以先用
     * `plugin_dev_enable {enabled:false}` 停用（停用会自动回退内置），再删。UI 确认弹窗后带
     * [force] = true，内部先清角色选择（= 先回退内置）再删。
     */
    private suspend fun deletePluginInternal(id: String, force: Boolean): PluginStore.Result {
        val record = withContext(Dispatchers.Main) { pluginsUi.firstOrNull { it.id == id } }
            ?: withContext(Dispatchers.IO) { pluginStore.list().firstOrNull { it.id == id } }
            ?: return PluginStore.Result(false, "插件不存在：$id")
        val inUse = withContext(Dispatchers.Main) { pluginInUseReason(record) }
        if (inUse != null && !force) {
            return PluginStore.Result(
                false,
                "拒绝删除 $id：$inUse。先停用它（plugin_dev_enable {id, enabled:false}），" +
                    "停用会自动回退内置角色，然后再删。",
            )
        }
        // 先清选择（= 先回退内置）再删，避免删除后解析落到「未安装」的红字/拦发送。
        val cleared = withContext(Dispatchers.Main) { clearRoleSelection(record) }
        val result = withContext(Dispatchers.IO) { pluginStore.uninstall(id) }
        withContext(Dispatchers.Main) {
            // M84-N：删掉插件时连它的投影 checkpoint 一起清（停用由 sync 清）。
            pluginResidentRuntime.forget(id)
            pluginsUi = pluginStore.list()
            syncPluginTools()
            pluginNotice = result.message + if (cleared.isNotEmpty()) {
                "，已回退内置${cleared.joinToString("、")}"
            } else {
                ""
            }
        }
        // M83：删掉提供者后，依赖它的插件要立刻变成「缺少服务 X」。
        withContext(Dispatchers.IO) { pluginStore.reconcileServices() }
        withContext(Dispatchers.Main) {
            pluginsUi = pluginStore.list()
            syncPluginTools()
        }
        return result
    }

    /**
     * 0.3.4（用户第 4 条 c / 第 6 条）：清掉指向 [record] 的角色选择，静默回退内置/native/跟随协议。
     *
     * 停用与删除共用，UI 与 AI 工具也共用；返回清掉的角色名，调用方合成一次 notice。
     * 顺序按 [PluginRoleRefs.prefKeys]：界面壳 → 回合 → 存储 → 提示词 → 加载页。
     */
    private fun clearRoleSelection(record: PluginRecord): List<String> {
        val cleared = ArrayList<String>()
        for ((key, label) in PluginRoleRefs.rolesToClear(record) { prefs.getString(it, null) }) {
            when (key) {
                "dsh_shell" -> setActiveShell(null)
                "dsh_agent" -> restoreBuiltinAgent()
                "dsh_storage" -> restoreBuiltinStorage()
                "dsh_prompt" -> restoreBuiltinPrompt()
                "dsh_loading" -> restoreBuiltinLoading()
            }
            cleared += label
        }
        // 文档要求连调试模型通道键一起清；插件声明的模型通道被选中时回「跟随协议」。
        val debugProvider = prefs.getString("debug_llm_provider", null)
        if (PluginRoleRefs.providerReferences(record, debugProvider)) {
            prefs.edit().remove("debug_llm_provider").apply()
            cleared += "模型（调试）"
        }
        if (PluginRoleRefs.providerReferences(record, settings.llmProviderId)) {
            restoreBuiltinModel()
            cleared += "模型"
        }
        if (cleared.isNotEmpty()) {
            Log.i("DshPlugin", "clear role selection for ${record.id}: ${cleared.joinToString("、")}")
        }
        return cleared
    }

    /** 0.3.4（用户第 7 条闸门 A）：删除前的「正在使用/运行中」判定；null = 可以删。 */
    private fun pluginInUseReason(record: PluginRecord): String? = PluginRoleRefs.inUseReason(
        record = record,
        readPref = { prefs.getString(it, null) },
        resident = pluginResidentRuntime.residencyIds().contains(record.id),
        shellPluginId = shellActive?.pluginId,
        agentPluginId = activeAgent?.pluginId,
        storagePluginId = activeStorage?.pluginId,
        promptPluginId = activePromptState?.pluginId,
        loadingOwnerPlugin = loadingSnapshotStore.read()?.ownerPlugin,
        modelProviderId = settings.llmProviderId,
    )

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

    // ---- M88-E（docs/183 §5）：AI 全控制插件 —— 审计、撤销 ----
    // docs/190 §3-F：总开关已永久删除（工具永远可调，写类仍走全局审批 + 审计）。

    /** 横幅「撤销」：撤销本进程最近一次 AI 授予（重启后按审计回放找最近一条）。 */
    override fun undoAiPluginGrant() {
        val target = lastAiGrant ?: pluginsUi.asSequence()
            .map { plugin -> plugin.id to pluginAuditLog.aiGrantedPermissions(plugin.id) }
            .firstOrNull { it.second.isNotEmpty() }
        if (target == null) {
            pluginNotice = "没有可撤销的 AI 授予"
            return
        }
        val (id, permissions) = target
        val detail = permissions.joinToString("、")
        val result = pluginStore.revokeGrants(id, permissions.toList())
        pluginAuditLog.append(xin.dshaa.nativeapp.data.PluginAuditLog.ACTOR_USER,
            xin.dshaa.nativeapp.data.PluginAuditLog.ACTION_REVOKE, id, detail)
        lastAiGrant = null
        pluginNotice = result.message
        pluginsUi = pluginStore.list()
        syncPluginTools()
        Log.i("DshPlugin", "undo AI grant plugin=$id permissions=$detail ok=${result.ok}")
    }

    /** 插件详情底部：该插件最近 5 条审计（AI 与用户操作都显示）。 */
    override fun pluginAuditLines(pluginId: String): List<String> =
        pluginAuditLog.recent(pluginId, 5).map { it.summary() }

    /** 权限行标「AI 授予」：按审计回放当前仍由 AI 授予的权限。 */
    override fun aiGrantedPermissions(pluginId: String): Set<String> =
        pluginAuditLog.aiGrantedPermissions(pluginId)

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
                    val names = offeredToolNames(includePrimitives = false)
                    promptPack.androidApiTools().filter { it.name in names } + pluginApiTools +
                        nodeApiTools.filter { it.name in names } +
                        pluginDevTools.apiTools().filter { it.name in names } +
                        nodeHostTools.apiTools().filter { it.name in names }
                },
                systemPrompt = {
                    buildSystemPrompt(
                        PromptRequest(
                            model = settings.model,
                            provider = providerLabel(),
                            tools = offeredToolNames(includePrimitives = false),
                            personaPrefix = personaPrefixText(),
                            personaSuffix = settings.assistantSystem,
                            extraSections = pluginPromptSections(),
                            variables = pluginPromptVariables(),
                            includeBuiltinSections = builtinPromptCoreEnabled(),
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
                toolMiddleware = { pluginToolChain },
                // M87-B：插件请求/提示词中间件链（每次请求现取快照；空链 = 零开销）。
                requestMiddleware = { pluginRequestChain },
                // 子代理回合同样记缓存诊断（docs/187 §7.2），否则子代理开销看不见。
                cacheDebug = { CacheDebug.sink() },
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
        // docs/185 P2：ViewModel 真销毁才收摊 Node 宿主（关开关只是 shutdown，不 close）。
        runCatching { nodePluginHost.close() }
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
    private fun pluginPromptSections(): List<xin.dshaa.core.prompt.PromptSection> {
        val native = pluginsUi
            .filter { it.enabled }
            .flatMap { it.promptSections.filterNot { section -> section.complete } }
            .map { section ->
                xin.dshaa.core.prompt.PromptSection(
                    name = section.name,
                    order = section.order,
                    text = section.text,
                )
            }
        // Node 插件（真 cordis 宿主）注册的段：同一张表里排序、同名互相覆盖。
        // docs/187 §九 第 11 项：只认领插件自己的段；关宿主后一个都不进。
        val node = nodePromptSections.map { section ->
            xin.dshaa.core.prompt.PromptSection(
                name = section.name,
                order = section.order,
                text = section.text,
            )
        }
        val merged = xin.dshaa.nativeapp.node.NodePromptSections.merge(
            native = native,
            node = node,
            hostEnabled = settings.nodePluginHostEnabled,
        )
        // P3-d：套用预设时只保留预设里列出的插件段（原生 identity/persona 由 PromptPack 自己拼，
        // 不在这张表里，所以收窄不会动到 dsh 的身份段）。
        val keep = activePreset?.promptSections.orEmpty()
        return if (keep.isEmpty()) merged else merged.filter { it.name in keep }
    }

    /**
     * 插件用 `ctx.systemPrompt.variable(name, fn)` 求值出的变量（M74）：段文本里的 `{{name}}`
     * 由 [PromptPack.systemFor] 替换。只取**启用中**插件的变量；同名的后注册覆盖（和段一致）。
     */
    private fun pluginPromptVariables(): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        pluginsUi.filter { it.enabled }.forEach { merged.putAll(it.promptVariables) }
        // Node 宿主里的插件（`ctx.systemPrompt.variable`）后注册的覆盖同名原生变量。
        // 关宿主后不再合并（否则关掉开关还残留插件变量）。
        if (settings.nodePluginHostEnabled) merged.putAll(nodePromptVariables)
        return merged
    }

    /**
     * 插件用 `ctx.systemPrompt.context` 注册的运行时上下文：DSH 里它是 **user-role 快照**
     * （不是 system），所以并进 [runtimeContextText] 的同一个 user 消息，按 order/name 排序。
     * M74：段文本里的 `{{name}}` 和 system 段一样做变量替换（插件常在两处写同一套占位符）。
     */
    private fun pluginContextText(): String {
        val variables = pluginPromptVariables()
        val native = pluginsUi
            .filter { it.enabled }
            .flatMap { it.contexts }
        // 关宿主后 Node 上下文一个都不进（与提示词段同一条纪律）。
        val node = if (settings.nodePluginHostEnabled) nodePromptContexts else emptyList()
        return (native + node)
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
                // M88-C：让模型知道本会话能调哪些能力 id（内置 + 已启用且授权 capability 的插件能力）。
                val capabilityIds = capabilityHost.ui().filter { it.available }.map { it.id }.take(20)
                if (capabilityIds.isNotEmpty()) {
                    append("\n\n")
                    append(
                        "可用能力（用 capability 工具按 id 调用）：${capabilityIds.joinToString("、")}。" +
                            "插件能力需要插件已启用且拿到 capability 授权。",
                    )
                }
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
