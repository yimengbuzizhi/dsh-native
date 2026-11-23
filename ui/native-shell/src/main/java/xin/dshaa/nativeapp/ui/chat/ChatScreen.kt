package xin.dshaa.nativeapp.ui.chat

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xin.dshaa.nativeapp.ui.shell.slots.SlotDock
import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.ChatMessage
import xin.dshaa.core.model.ContentBlock
import xin.dshaa.core.model.GoalActivation
import xin.dshaa.core.model.GoalPhase
import xin.dshaa.core.model.GoalView
import xin.dshaa.core.model.MessageRole
import xin.dshaa.core.model.ScheduledTask
import xin.dshaa.core.session.SessionMarkdown
import xin.dshaa.core.input.DSH_COMMANDS
import xin.dshaa.core.input.FileCandidate
import xin.dshaa.core.input.InputTriggerKind
import xin.dshaa.core.input.formatFileMention
import xin.dshaa.core.input.inputTrigger
import xin.dshaa.core.skill.SkillSummary
import xin.dshaa.core.tools.schedule.ScheduleTime
import xin.dshaa.ui.shellapi.chat.ATTACHMENT_REFERENCE_HINT
import xin.dshaa.ui.shellapi.chat.ChatSessionState
import xin.dshaa.ui.shellapi.chat.PendingApproval
import xin.dshaa.ui.shellapi.chat.PendingImage
import xin.dshaa.ui.shellapi.chat.READ_ONLY_SUBAGENT
import xin.dshaa.ui.shellapi.chat.TruncationKind
import xin.dshaa.ui.shellapi.chat.WaitingSession
import xin.dshaa.ui.shellapi.chat.toolResultLooksFailed
import xin.dshaa.ui.shellapi.chat.truncationResumeNotice
import xin.dshaa.ui.shellapi.BuiltinPageIds
import xin.dshaa.ui.shellapi.ChatUiCapabilities
import xin.dshaa.ui.design.component.DshAvatar
import xin.dshaa.ui.design.chat.QuotedAttachmentChip
import xin.dshaa.ui.design.chat.consumeInnerScroll
import xin.dshaa.ui.design.chat.parseQuotedAttachment
import xin.dshaa.ui.design.component.MarkdownText
import xin.dshaa.ui.design.component.StatusDot
import xin.dshaa.ui.design.theme.DshTheme2
import xin.dshaa.ui.design.theme.LiquidLevel
import xin.dshaa.ui.design.theme.LiquidRadius
import xin.dshaa.ui.design.component.WaterOrb
import xin.dshaa.ui.design.component.WaterOrbShadow
import xin.dshaa.ui.design.component.WaterOrbWhale
import xin.dshaa.ui.design.component.threadEdgeFade
import xin.dshaa.ui.design.theme.LiquidMotion
import xin.dshaa.ui.design.theme.LocalLiquidTokens
import xin.dshaa.ui.design.component.liquidClickable
import xin.dshaa.ui.design.component.liquidEnter
import xin.dshaa.ui.design.component.inkReveal
import xin.dshaa.ui.design.component.liquidGlass
import xin.dshaa.ui.design.theme.wash

/** 时间上下文折叠行的时钟：从注入正文的 ISO 时间戳里取 `HH:mm:ss`（M42）。 */
private val TIME_CONTEXT_CLOCK = Regex("""T(\d{2}:\d{2}:\d{2})""")

/**
 * 聊天页：Operit 的消息结构（头像/名字可定制、思考折叠、工具卡）+ Rikka 的留白。
 * AI 正文不套气泡；用户消息才有气泡；工具调用与结果合成一张卡。
 *
 * 2026-11-07：状态直接读 [ChatSessionState] 的 Compose snapshot 字段，
 * 不再经过 StateFlow/data class 拷贝，流式 token 逐字可见（旧 key hack 已删）。
 */
@Composable
fun ChatScreen(
    state: ChatSessionState,
    assistantName: String,
    assistantAvatar: String,
    /** 角色卡开场白（M71）：空会话显示的第一句话；空 = 默认提示语。 */
    assistantOpening: String = "",
    expandThinking: Boolean,
    /** 附件 id → 本地图片路径（read_image 的缩略图用；null = 附件缺失）。 */
    imagePath: (String) -> String?,
    /**
     * 左上角三条杠：打开侧边栏（2026-11-07 RikkaHub 外壳）。
     * 聊天页是 App 的落地页，返回键不再用来「退出聊天」。
     */
    onOpenDrawer: () -> Unit,
    /** 宽屏侧边栏常驻时隐藏 ☰（M74）。 */
    menuVisible: Boolean = true,
    /** 右上角「新对话」：开一个空草稿（不建会话文件，第一条消息才落盘）。 */
    onNewChat: () -> Unit = {},
    /** 右上角「工作区」（M76）：打开工作区文件浏览页。 */
    onOpenWorkspace: () -> Unit = {},
    /** present 交付物卡片的兜底根：工具结果里拿不到绝对路径时，用它把相对路径拼成绝对路径。 */
    workspacePath: String? = null,
    /** 相册选图（Photo Picker 多选，逐张回调；VM 负责归一化落盘）。 */
    onAttachImage: (Uri) -> Unit = {},
    /** 相机拍照结果（FileProvider 临时文件；VM 读完即删）。 */
    onAttachPhoto: (File) -> Unit = {},
    /** 无障碍截图进待发附件。 */
    onCaptureScreenshot: () -> Unit = {},
    /** 从输入条移除一张待发图片（attachmentId）。 */
    onRemovePendingImage: (String) -> Unit = {},
    /** 系统文件选择（文本/代码/日志；图片会自动走图片管线）。 */
    onAttachFile: (Uri) -> Unit = {},
    /** 从输入条移除一个待发文件（按文件名）。 */
    onRemovePendingFile: (String) -> Unit = {},
    /** 文本类附件（通知/位置）直接进发起操作的会话；false = 无可用或可写会话。 */
    onAttachText: (String, String) -> Boolean = { _, _ -> false },
    /** 通知使用权状态（宿主设备能力；UI 只读）。 */
    notificationsEnabled: (android.content.Context) -> Boolean = { false },
    onOpenNotificationAccess: (android.content.Context) -> Unit = {},
    /** 抓取最近通知并作为文本附件（返回结果供 UI 提示）。 */
    onAttachNotifications: (android.content.Context) -> xin.dshaa.ui.shellapi.chat.NotifyAttachResult =
        { xin.dshaa.ui.shellapi.chat.NotifyAttachResult(false, "") },
    /** 取一次定位并作为文本附件（返回 false = 打不开授权页）。 */
    onAttachLocation: (android.content.Context) -> Boolean = { false },
    /** 记忆选择器（宿主注入；选中后给文件名 + 引用正文）。 */
    memoryPicker: (@Composable (onDismiss: () -> Unit, onPick: (String, String) -> Unit) -> Unit)? = null,
    /** 发送入口（M21）：整行先交给 ViewModel 解析斜杠命令；返回 true = 已接受，可清空输入。 */
    onSubmit: (String) -> Boolean,
    onStop: () -> Unit,
    /** H1：上一轮被 max_tokens 截断时点「继续生成」（点一次消费一次）。 */
    onContinueTruncated: () -> Unit = {},
    /** H1：单轮输出上限，只用于截断说明文案（0 = 不显示具体数值）。 */
    outputTokenLimit: Int = 0,
    onApproval: (allow: Boolean, always: Boolean) -> Unit,
    onAnswer: (List<xin.dshaa.core.tools.AskAnswer>) -> Unit,
    /** 计划模式（M18）：状态来自 `PlanModeState` 的镜像；pending 非空 = 下一步生效。 */
    planModeActive: Boolean = false,
    planModePending: Boolean? = null,
    onSetPlanMode: (Boolean) -> Unit = {},
    /** 当前会话的提醒（每次打开面板重新读，刚用 schedule_create 建的也在）。 */
    schedules: () -> List<ScheduledTask> = { emptyList() },
    onDeleteSchedule: (String) -> Unit = {},
    /** `/` 菜单的候选（M21）：用户可调用技能 + 工作区文件；查询变化时现取。 */
    skillChoices: List<SkillSummary> = emptyList(),
    onRefreshSkills: () -> Unit = {},
    fileCandidates: suspend (String) -> List<FileCandidate> = { emptyList() },
    onDismissCommandNotice: () -> Unit = {},
    /** `/goal` 面板：用户侧暂停/恢复/编辑/清空；返回 null = 成功，否则是错误文案。 */
    onPauseGoal: suspend () -> String? = { null },
    onResumeGoal: suspend () -> String? = { null },
    onEditGoal: suspend (String?, Int?) -> String? = { _, _ -> null },
    onClearGoal: suspend () -> String? = { null },
    /** 工具卡跳转（M22）：后台任务 → 一级页「任务」；子代理 → 只读子会话回放。 */
    onOpenJobs: () -> Unit = {},
    onOpenSubagent: (String) -> Unit = {},
    /** Operit 消息菜单里的会话操作：在指定消息处创建分支 / 重新生成。 */
    onBranchMessage: (ChatMessage) -> Unit = {},
    onRegenerateMessage: (ChatMessage) -> Unit = {},
    /** 模型能力开关（与设置页同一份值）：输入条的模型面板直接改，不用跳页面。 */
    visionEnabled: Boolean = true,
    onToggleVision: (Boolean) -> Unit = {},
    webSearchEnabled: Boolean = true,
    onToggleWebSearch: (Boolean) -> Unit = {},
    /** 模型面板底部的「打开模型配置」（设置 → 模型与参数配置）。 */
    onOpenModelSettings: () -> Unit = {},
    /** 输入条「包」引用：可选插件 / 技能 / MCP（照 Operit PackageSelectorDialog）。 */
    plugins: List<xin.dshaa.core.plugins.PluginRecord> = emptyList(),
    skills: List<SkillSummary> = emptyList(),
    onEnablePlugin: (String) -> Unit = {},
    /** 最近保存过的模型名（输入条模型下拉直接切）。 */
    enabledModels: List<String> = emptyList(),
    onSwitchModel: (String) -> Unit = {},
    /** 工具审批方式（M40）：`/permission` 打开的面板与设置 → 行为共用同一份值。 */
    approvalMode: String = "ask",
    onSetApprovalMode: (String) -> Unit = {},
    /** 输入条设置菜单（M58，照 Operit）：思考模式/程度、Max 模式、行为、记忆空间、插件入口。 */
    reasoningEffort: String = "high",
    onSetReasoningEffort: (String) -> Unit = {},
    maxMode: Boolean = false,
    onSetMaxMode: (Boolean) -> Unit = {},
    /** 自动压缩（M79）：设置 → 显示与行为同一份值；「立即压缩」发 /compact。 */
    autoCompact: Boolean = true,
    onSetAutoCompact: (Boolean) -> Unit = {},
    autoCompactRatio: Float = 0.8f,
    onSetAutoCompactRatio: (Float) -> Unit = {},
    onCompactNow: () -> Unit = {},
    modelContextWindow: Int = 0,
    onSetExpandThinking: (Boolean) -> Unit = {},
    timeContext: Boolean = true,
    onSetTimeContext: (Boolean) -> Unit = {},
    sendWhileRunningMode: String = "queue",
    onSetSendWhileRunningMode: (String) -> Unit = {},
    onCancelQueuedPrompt: (String) -> Unit = {},
    onResumeQueuedPrompts: () -> Unit = {},
    memorySpaceName: String = "Default",
    onOpenMemoryLibrary: () -> Unit = {},
    onOpenPlugins: () -> Unit = {},
    /** 其它会话正在等审批/回答（M78 并行）：卡片只画在当前会话上，这里给一条可点横幅。 */
    waitingElsewhere: List<WaitingSession> = emptyList(),
    onFocusSession: (String) -> Unit = {},
    /** M84-B：页面插件开关；关掉的页面的聊天快捷入口（工作区/记忆/任务/模型配置）一起隐藏。 */
    pageEnabled: (String) -> Boolean = { true },
    /** M85-D：当前回合插件（null/内置 = 不显示提示条；第三方显示「回合插件：…」）。 */
    activeAgent: xin.dshaa.ui.shellapi.AgentTargetUi? = null,
    /** M85-D：无可用回合时的安全提示（非 null = 顶部红色横幅 + 一键恢复内置回合）。 */
    agentSafetyNotice: String? = null,
    onRestoreBuiltinAgent: () -> Unit = {},
    /** M85-E：存储不可用/只读时的提示（非 null = 顶部横幅 + 一键恢复内置存储）。 */
    storageSafetyNotice: String? = null,
    onRestoreBuiltinStorage: () -> Unit = {},
    /** M85-E：提示词不可用时的提示（非 null = 顶部横幅 + 一键恢复内置提示词）。 */
    promptSafetyNotice: String? = null,
    onRestoreBuiltinPrompt: () -> Unit = {},
    capabilities: ChatUiCapabilities = ChatUiCapabilities.Local,
    canSubmit: Boolean = true,
    connectionNotice: String? = null,
    onReconnect: () -> Unit = {},
    onSwitchToLocal: () -> Unit = {},
    onDraftChange: (String) -> Unit = {},
) {
    val c = DshTheme2.colors
    // docs/187 §4：液态玻璃壳保持 PR #4 的悬浮玻璃顶栏/输入台版式；
    // 原生壳回到 0.3.5（89b1455）的实底顶栏 + 常规列表留白。
    val liquid = DshTheme2.liquidEnabled
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf(state.draft) }
    // M76：横竖屏切换时宽的抽屉分支会重建整棵子树（remember 丢失），草稿以会话状态为准。
    androidx.compose.runtime.LaunchedEffect(state, state.draft) {
        if (input != state.draft) input = state.draft
    }
    /** 长按菜单「引用」后的输入条预览（Operit 的 reply preview）。 */
    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    /** 输入条左侧「＋」的附件/设置菜单。 */
    var attachMenu by remember { mutableStateOf(false) }
    /** 触发面板（M21）：同一个 token/查询被选过之后不再自动弹回（上游 input-trigger 的「关闭保持关闭」）。 */
    var suppressedTrigger by remember { mutableStateOf<String?>(null) }
    var triggerFiles by remember { mutableStateOf<List<FileCandidate>>(emptyList()) }
    val trigger = inputTrigger(input.text, input.selection.start)
    val triggerVisible = capabilities.localCommands && trigger != null && trigger.key() != suppressedTrigger
    var schedulesOpen by remember { mutableStateOf(false) }
    var scheduleTasks by remember { mutableStateOf<List<ScheduledTask>>(emptyList()) }
    var goalOpen by remember { mutableStateOf(false) }
    /** 长按打开的消息操作菜单（Operit：复制 / 朗读 / 引用 / 重发 / 重新生成 / 分支 / 信息）。 */
    var actionTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var infoTarget by remember { mutableStateOf<ChatMessage?>(null) }
    /** 会话导出结果（M22）：成功弹「已导出」对话框，失败 Toast。 */
    var exported by remember { mutableStateOf<ExportedSession?>(null) }
    val exportScope = rememberCoroutineScope()

    /**
     * 导出 Markdown（原来藏在右上角「⋮」菜单里，和输入条齿轮里的目标/提醒/计划重复；
     * 那个菜单已删，导出挪到输入条齿轮面板）。
     */
    fun exportSession() {
        if (state.messages.isEmpty()) return
        val title = state.title.ifBlank { "会话" }
        val model = state.model
        val sessionId = state.sessionId
        val snapshot = state.messages.toList()
        exportScope.launch {
            val at = System.currentTimeMillis()
            val content = withContext(Dispatchers.Default) { SessionMarkdown.render(title, model, sessionId, at, snapshot) }
            val name = SessionMarkdown.fileName(title, at)
            val result = withContext(Dispatchers.IO) { writeSessionMarkdown(context, name, content) }
            exported = result
            if (!result.ok) Toast.makeText(context, result.error ?: "导出失败", Toast.LENGTH_LONG).show()
        }
    }
    /** 引用后把焦点送回输入框（计数变化触发一次）。 */
    var focusInputTick by remember { mutableStateOf(0) }
    val inputFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    // 会话内搜索：标题栏切换成搜索框，匹配条数 + 上/下跳转，命中消息高亮
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var matchIndex by rememberSaveable { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // ActivityResult 使用启动时的 lambda，避免切会话后调用重组产生的新回调。
    // 不 rememberSaveable：配置重建若丢失原回调，就安全忽略结果。
    var imageResult by remember { mutableStateOf<((Uri) -> Unit)?>(null) }
    var fileResult by remember { mutableStateOf<((Uri) -> Unit)?>(null) }
    var photoResult by remember { mutableStateOf<((File) -> Unit)?>(null) }
    var cameraTemp by remember { mutableStateOf<File?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        val attach = imageResult
        imageResult = null
        if (attach != null) uris.forEach(attach)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val attach = fileResult
        fileResult = null
        if (attach != null) uris.forEach(attach)
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = cameraTemp
        val attach = photoResult
        cameraTemp = null
        photoResult = null
        if (ok && file != null && attach != null) attach(file) else file?.delete()
    }
    fun pickerBusy(): Boolean = imageResult != null || fileResult != null || photoResult != null
    fun launchGallery() {
        if (pickerBusy()) return
        imageResult = onAttachImage
        runCatching {
            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }.onFailure {
            imageResult = null
            Toast.makeText(context, "无法打开相册", Toast.LENGTH_SHORT).show()
        }
    }
    fun launchFiles() {
        if (pickerBusy()) return
        fileResult = onAttachFile
        runCatching { filePicker.launch(arrayOf("*/*")) }.onFailure {
            fileResult = null
            Toast.makeText(context, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }
    fun launchCamera() {
        if (pickerBusy()) return
        val file = runCatching {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            File.createTempFile("chat-", ".jpg", dir)
        }.getOrNull()
        if (file == null) {
            Toast.makeText(context, "没有可用的缓存目录", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }.getOrNull()
        if (uri == null) {
            file.delete()
            Toast.makeText(context, "无法创建拍照文件", Toast.LENGTH_SHORT).show()
            return
        }
        cameraTemp = file
        photoResult = onAttachPhoto
        runCatching { takePicture.launch(uri) }.onFailure {
            file.delete()
            cameraTemp = null
            photoResult = null
            Toast.makeText(context, "没有可用的相机应用", Toast.LENGTH_SHORT).show()
        }
    }

    val messages = state.messages
    val visible = messages.filter { it.role != MessageRole.TOOL }
    val rows = buildRows(visible)
    val results = messages.filter { it.role == MessageRole.TOOL && it.toolCallId != null }.associateBy { it.toolCallId!! }

    // 消息 id → 行下标（连续 assistant 消息合成一行，所以不能直接用消息下标）
    val rowIndexByMessage = HashMap<String, Int>()
    rows.forEachIndexed { index, row ->
        when (row) {
            is ChatRow.User -> rowIndexByMessage[row.message.id] = index
            is ChatRow.Assistant -> row.messages.forEach { rowIndexByMessage[it.id] = index }
            is ChatRow.Day -> Unit
        }
    }
    val matches: List<Pair<ChatMessage, Int>> = if (query.isBlank()) {
        emptyList()
    } else {
        // goal 轮/收尾通知、后台任务完成通知的正文是内部提示词，界面上只显示分隔条，不参与搜索
        visible.filter { m ->
            m.sourceKind != "goal" && m.sourceKind != "tool-goal" && m.sourceKind != "tool-jobs" &&
                m.sourceKind != "subagent-settled" && m.sourceKind != "skill-catalog" &&
                m.sourceKind != "skill-invocation" && m.sourceKind != "time-context" &&
                // M43：压缩 checkpoint 是内部注入（摘要正文可能很长），不参与会话内搜索
                m.sourceKind != "compact-checkpoint" &&
                m.content.any { it.type == BlockType.TEXT && it.text.contains(query, ignoreCase = true) }
        }.mapNotNull { m -> rowIndexByMessage[m.id]?.let { m to it } }
    }
    val safeMatchIndex = if (matches.isEmpty()) 0 else matchIndex.coerceIn(0, matches.size - 1)
    val currentMatchId = matches.getOrNull(safeMatchIndex)?.first?.id

    LaunchedEffect(searching) {
        if (searching) {
            focusRequester.requestFocus()
            keyboard?.show()
        } else {
            query = ""
            keyboard?.hide()
        }
    }

    LaunchedEffect(focusInputTick) {
        if (focusInputTick > 0) {
            inputFocus.requestFocus()
            keyboard?.show()
        }
    }

    // `/` 菜单打开时刷新一次技能目录（上游菜单打开时拉 skills/list）；`@` 每个查询现查工作区。
    LaunchedEffect(trigger?.kind, triggerVisible) {
        if (!triggerVisible) {
            triggerFiles = emptyList()
            return@LaunchedEffect
        }
        if (trigger?.kind == InputTriggerKind.COMMAND) {
            triggerFiles = emptyList()
            onRefreshSkills()
        }
    }
    LaunchedEffect(trigger?.key(), triggerVisible) {
        val t = trigger
        if (t == null || !triggerVisible || t.kind != InputTriggerKind.FILE) return@LaunchedEffect
        triggerFiles = fileCandidates(t.query)
    }

    fun submitInput() {
        if (!canSubmit || state.readOnly || state.stopping) return
        // 附件本身就是一条消息；运行中由宿主按用户选择持久化排队或插话。
        if (input.text.isBlank() && state.pendingImages.isEmpty() && state.pendingFiles.isEmpty()) return
        if (onSubmit(input.text)) {
            input = TextFieldValue("")
            state.draft = input
            onDraftChange(input.text)
            replyTo = null
            suppressedTrigger = null
        }
    }

    /** 把当前触发 token 替换成选中的命令/技能/mention，光标落在末尾。 */
    fun replaceTrigger(replacement: String, suppress: Boolean = false) {
        val t = trigger ?: return
        val text = input.text.replaceRange(t.start, t.end, replacement)
        val cursor = t.start + replacement.length
        input = TextFieldValue(text, TextRange(cursor))
        state.draft = input
        onDraftChange(input.text)
        suppressedTrigger = if (suppress) inputTrigger(text, cursor)?.key() else null
        keyboard?.show()
    }

    val lastText = visible.lastOrNull()?.content?.joinToString("") { it.text }.orEmpty()
    /**
     * 生成中要不要自动贴底。
     *
     * 旧实现用 `lastVisible >= rows.lastIndex - 1` 判断「在底部」：最后一条 assistant 回复很长时，
     * 用户往上翻但最后一条还在屏幕里 → 判定成贴底 → 每来一个 token 就被拽回底部，
     * 表现就是「输出期间不受控制地往上弹，等输出完才看得到」。
     * 现在只认手势：手指往下拖（翻历史）立刻停跟随；滑回底部再恢复。
     */
    var followOutput by remember { mutableStateOf(true) }
    val followConnection = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && available.y > 0f) {
                    followOutput = false
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && !listState.canScrollForward) {
                    followOutput = true
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    LaunchedEffect(visible.size, lastText.length, state.statusText, state.streaming) {
        if (visible.isEmpty() || query.isNotBlank() || !followOutput) return@LaunchedEffect
        // 等一帧让新内容参与测量，再按实际剩余距离贴底（scrollBy 到头会被 clamp，不会越界）
        withFrameNanos { }
        listState.scrollBy(200_000f)
    }
    LaunchedEffect(state.sessionId) {
        // 打开已有会话先落到最新一条：LazyColumn 默认在顶部，长会话会看到几天前的消息。
        // 最后一条可能很长（assistant 回复），再补一次滚动才落到真正的底部。
        if (rows.isNotEmpty()) {
            listState.scrollToItem(rows.lastIndex)
            listState.scrollBy(100_000f)
        }
    }
    LaunchedEffect(query, matchIndex, matches.size) {
        if (query.isBlank()) return@LaunchedEffect
        matches.getOrNull(safeMatchIndex)?.let { (_, row) -> listState.animateScrollToItem(row) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .imePadding(),
    ) {
        // 顶栏：返回 + 标题/模型 + 搜索 + 更多；搜索态换成输入框 + 命中计数 + 上/下
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (liquid) {
                        // 设计稿 .topbar：悬浮玻璃条（12/12/6 外边距 + 22dp 圆角）
                        Modifier
                            .padding(start = 12.dp, end = 12.dp, top = 6.dp)
                            .liquidGlass(shape = RoundedCornerShape(LiquidRadius.xl), level = LiquidLevel.Panel)
                    } else {
                        // 0.3.5 顶栏：整条实底 surface，贴着状态栏
                        Modifier.background(c.surface)
                    },
                )
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (searching) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable { searching = false },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, "关闭搜索", tint = c.text, modifier = Modifier.size(21.dp))
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it; matchIndex = 0 },
                    modifier = Modifier.weight(1f).focusRequester(focusRequester).padding(horizontal = 4.dp, vertical = 8.dp),
                    textStyle = TextStyle(color = c.text, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                    singleLine = true,
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text("搜索本会话", style = MaterialTheme.typography.bodyLarge, color = c.faint)
                        }
                        inner()
                    },
                )
                if (query.isNotBlank()) {
                    Text(
                        if (matches.isEmpty()) "0/0" else "${safeMatchIndex + 1}/${matches.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (matches.isEmpty()) c.faint else c.brand,
                    )
                }
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable {
                        if (matches.isNotEmpty()) matchIndex = (safeMatchIndex - 1 + matches.size) % matches.size
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.KeyboardArrowUp, "上一处", tint = if (matches.isEmpty()) c.faint else c.sub, modifier = Modifier.size(20.dp))
                }
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable {
                        if (matches.isNotEmpty()) matchIndex = (safeMatchIndex + 1) % matches.size
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "下一处", tint = if (matches.isEmpty()) c.faint else c.sub, modifier = Modifier.size(20.dp))
                }
            } else {
                if (menuVisible) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).clickable { onOpenDrawer() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Menu, "打开侧边栏", tint = c.text, modifier = Modifier.size(23.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        state.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (state.readOnlyReason == READ_ONLY_SUBAGENT) "子代理会话 · ${state.model}"
                        else listOf(assistantName.ifBlank { "DSH" }, state.model).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = c.faint,
                    )
                }
                if (capabilities.workspace && pageEnabled(BuiltinPageIds.Workspace)) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).clickable { onOpenWorkspace() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Folder, "工作区", tint = c.sub, modifier = Modifier.size(21.dp))
                    }
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable { onNewChat() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.AddComment, "新对话", tint = c.sub, modifier = Modifier.size(21.dp))
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable { searching = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Search, "搜索会话", tint = c.sub, modifier = Modifier.size(21.dp))
                }
            }
        }

        if (capabilities.agentFeatures && schedulesOpen) {
            SchedulesDialog(
                tasks = scheduleTasks,
                onDelete = { id ->
                    onDeleteSchedule(id)
                    scheduleTasks = schedules()
                },
                onDismiss = { schedulesOpen = false },
            )
        }
        if (capabilities.agentFeatures && goalOpen) {
            state.goal?.let { view ->
                GoalDialog(
                    view = view,
                    onDismiss = { goalOpen = false },
                    onPause = onPauseGoal,
                    onResume = onResumeGoal,
                    onEdit = onEditGoal,
                    onClear = {
                        val error = onClearGoal()
                        if (error == null) goalOpen = false
                        error
                    },
                )
            }
        }

        // 消息长按菜单（Operit 同款：复制 / 朗读 / 引用 / 重发 / 重新生成 / 分支 / 信息）
        actionTarget?.let { target ->
            val targetText = target.content.filter { it.type == BlockType.TEXT }.joinToString("\n") { it.text }
            MessageMenuDialog(
                isUser = target.role == MessageRole.USER,
                canAct = !state.readOnly && !state.streaming,
                canResend = capabilities.messageMutation && canSubmit && target.role == MessageRole.USER && targetText.isNotBlank(),
                canMutate = capabilities.messageMutation,
                onCopy = {
                    copyMessage(clipboard, context, targetText)
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    actionTarget = null
                },
                onSpeak = {
                    speakText(context, targetText)
                    actionTarget = null
                },
                onQuote = {
                    val quoted = quotePrefix(targetText) + input.text
                    input = TextFieldValue(quoted, TextRange(quoted.length))
                    state.draft = input
                    onDraftChange(input.text)
                    replyTo = target
                    actionTarget = null
                    focusInputTick += 1
                },
                onResend = {
                    actionTarget = null
                    onSubmit(targetText)
                },
                onRegenerate = {
                    actionTarget = null
                    onRegenerateMessage(target)
                },
                onBranch = {
                    actionTarget = null
                    onBranchMessage(target)
                },
                onInfo = {
                    actionTarget = null
                    infoTarget = target
                },
                onDismiss = { actionTarget = null },
            )
        }
        infoTarget?.let { target ->
            MessageInfoDialog(target) { infoTarget = null }
        }

        // 导出结果（M22）：成功给出路径 + 「打开」；失败在点击时已 Toast，这里不重复弹窗
        exported?.takeIf { it.ok }?.let { result ->
            AlertDialog(
                onDismissRequest = { exported = null },
                containerColor = c.panel,
                title = { Text("已导出 Markdown", style = MaterialTheme.typography.titleMedium, color = c.text) },
                text = {
                    Column {
                        Text(result.displayPath, style = MaterialTheme.typography.bodyMedium, color = c.text)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "只含聊天里看得见的内容；思考与工具结果折叠在 <details> 里。",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.faint,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { exported = null }) { Text("好", color = c.brand) }
                },
                dismissButton = result.uri?.let { uri ->
                    {
                        TextButton(
                            onClick = {
                                exported = null
                                openExportedMarkdown(context, uri)
                            },
                        ) { Text("打开", color = c.sub) }
                    }
                },
            )
        }

        // 其它会话在等审批/回答（M78 并行）：审批卡只画在当前会话上，后台会话弹出来时
        // 前台看不到卡片，这里给一条可点横幅（点一下切过去，卡片就在那边等）。
        if (capabilities.agentFeatures && waitingElsewhere.isNotEmpty()) {
            WaitingElsewhereBar(waitingElsewhere, onFocusSession)
        }

        // 目标条：goal 工具写入的长期目标（active/paused/blocked/complete）；点开 /goal 面板
        if (capabilities.agentFeatures) state.goal?.let { GoalStrip(it) { goalOpen = true } }

        connectionNotice?.let { notice ->
            Row(Modifier.fillMaxWidth().background(c.surfaceAlt).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(notice, style = MaterialTheme.typography.labelSmall, color = c.sub, modifier = Modifier.weight(1f))
                TextButton(onClick = onReconnect) { Text("重试") }
                TextButton(onClick = onSwitchToLocal) { Text("本机") }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (visible.isEmpty()) {
                EmptyChat(assistantName, assistantAvatar, assistantOpening) { picked ->
                    input = TextFieldValue(picked)
                    inputFocus.requestFocus()
                }
            } else {
                // LazyColumn：长会话（含 41 条导入会话、5000+ 消息）只组合可见项；
                // key 用消息 id，搜索跳转/流式追加都不会错位。
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (liquid) {
                                Modifier
                                    .padding(horizontal = 14.dp)
                                    // 设计稿 .thread 的 mask：气泡从悬浮顶栏/输入台背后穿过去，两端渐隐
                                    .threadEdgeFade(topSolid = 54.dp, bottomSolid = 88.dp)
                            } else {
                                // 0.3.5：普通列表留白，没有悬浮层要躲
                                Modifier.padding(horizontal = 18.dp)
                            },
                        )
                        .nestedScroll(followConnection),
                    // 设计稿 padding：顶部留出悬浮顶栏（safe+68），底部留出输入台（122）
                    contentPadding = if (liquid) {
                        PaddingValues(top = 68.dp, bottom = 118.dp)
                    } else {
                        PaddingValues(vertical = 16.dp)
                    },
                    verticalArrangement = Arrangement.spacedBy(if (liquid) 14.dp else 20.dp),
                ) {
                    items(rows, key = { row -> rowKey(row) }) { row ->
                        val highlighted = when (row) {
                            is ChatRow.User -> row.message.id == currentMatchId
                            is ChatRow.Assistant -> row.messages.any { it.id == currentMatchId }
                            is ChatRow.Day -> false
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (highlighted) c.brand.copy(alpha = 0.08f) else Color.Transparent,
                                    RoundedCornerShape(14.dp),
                                )
                                .padding(if (highlighted) 8.dp else 0.dp),
                        ) {
                            when (row) {
                                is ChatRow.Day -> DaySeparator(row.label)
                                is ChatRow.User -> when (row.message.sourceKind) {
                                    "goal" -> GoalRoundNotice(row.message)
                                    "tool-goal" -> GoalWrapupNotice(row.message)
                                    "tool-jobs" -> JobNoticeRow(row.message)
                                    "subagent-settled" -> AgentSettledRow(row.message)
                                    "agent-message" -> AgentMessageRow(row.message)
                                    "plan-mode" -> PlanModeNoticeRow(row.message)
                                    "skill-catalog" -> SkillCatalogRow(row.message)
                                    "skill-invocation" -> SkillInvocationRow(row.message)
                                    "time-context" -> TimeContextRow(row.message)
                                    "compact-checkpoint" -> CompactCheckpointRow(row.message)
                                    else -> UserMessage(row.message, imagePath) { actionTarget = it }
                                }
                                is ChatRow.Assistant -> AssistantTurn(
                                    row.messages, results, assistantName, assistantAvatar, expandThinking, imagePath,
                                    linksEnabled = capabilities.toolNavigation && !state.streaming,
                                    // 工具执行期间 assistant 消息本身已「不再流式」，但整轮还在跑：
                                    // 折叠行要按轮次状态显示活动标题，不能显示「已完成」
                                    // （用户 2026-11-11 反馈：块显示已完成、状态条还在调用工具）。
                                    turnRunning = state.streaming && row === rows.lastOrNull(),
                                    onAction = { actionTarget = it },
                                    onOpenSubagent = onOpenSubagent,
                                    onOpenJobs = onOpenJobs,
                                    jobsPageEnabled = capabilities.toolNavigation && pageEnabled(BuiltinPageIds.Jobs),
                                    workspacePath = workspacePath,
                                    localArtifacts = capabilities.toolNavigation,
                                )
                            }
                        }
                    }
                }
                if ((!followOutput || listState.canScrollForward) && visible.isNotEmpty()) {
                    val scope = rememberCoroutineScope()
                    val scrollToBottom: () -> Unit = {
                        followOutput = true
                        scope.launch {
                            listState.animateScrollToItem(rows.lastIndex.coerceAtLeast(0))
                            listState.animateScrollBy(100_000f)
                        }
                    }
                    if (liquid) {
                        // 「回到底部」是**胶囊**（不是 38dp 圆钮）：之前误把顶栏的
                        // padding(12,12,6) 注入到了 .size(38.dp) 上，净宽只剩 14dp。
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp)
                                .heightIn(min = 38.dp)
                                .clip(RoundedCornerShape(LiquidRadius.pill))
                                .liquidGlass(shape = RoundedCornerShape(LiquidRadius.pill), level = LiquidLevel.Panel)
                                .clickable(onClick = scrollToBottom)
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.ExpandMore,
                                    "回到底部",
                                    tint = c.brand,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    "回到底部",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontSize = 12.5.sp,
                                    color = c.sub,
                                )
                            }
                        }
                    } else {
                        // 0.3.5：38dp 实底圆钮，只有箭头
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp)
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(c.surface)
                                .clickable(onClick = scrollToBottom),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.ExpandMore, "回到底部", tint = c.brand, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        // 待办面板：取最近一次 todo_write 的参数（模型维护的任务清单）
        latestTodos(visible)?.let { todos ->
            TodoPanel(todos)
        }

        // M85-D：回合插件（agent/v1）提示 / 安全模式。
        // 安全提示优先：没有可用回合时整条输入被拦下，这里给出原因与一键恢复。
        if (capabilities.agentFeatures && agentSafetyNotice != null) {
            AgentSafetyBar(agentSafetyNotice, onRestoreBuiltinAgent)
        } else if (capabilities.agentFeatures && activeAgent != null && !activeAgent.builtin) {
            AgentPluginStrip(activeAgent.label, activeAgent.provider)
        }

        // M85-E：存储 / 提示词角色的安全提示（内置插件停用或只读存储）。
        if (capabilities.agentFeatures && storageSafetyNotice != null) {
            RoleSafetyBar(
                title = "存储安全提示",
                reason = storageSafetyNotice,
                action = "恢复内置存储",
                onRestore = onRestoreBuiltinStorage,
            )
        }
        if (capabilities.agentFeatures && promptSafetyNotice != null) {
            RoleSafetyBar(
                title = "提示词安全提示",
                reason = promptSafetyNotice,
                action = "恢复内置提示词",
                onRestore = onRestoreBuiltinPrompt,
            )
        }

        // 运行状态条（M76）：流式期间常驻「运行中 · 12s · 正在执行 …」，不再让人以为突然停住。
        if (state.streaming || state.statusText != null) {
            var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
            androidx.compose.runtime.LaunchedEffect(state.streaming, state.turnStartedAt) {
                while (state.streaming) {
                    nowMs = System.currentTimeMillis()
                    kotlinx.coroutines.delay(1000)
                }
                nowMs = System.currentTimeMillis()
            }
            val elapsed = if (state.turnStartedAt > 0) {
                ((nowMs - state.turnStartedAt) / 1000).coerceAtLeast(0)
            } else 0
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(c.brand, pulse = state.streaming, size = 6.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    buildString {
                        if (state.streaming) {
                            append("运行中")
                            if (elapsed > 0) append(" · ${elapsed}s")
                        }
                        state.statusText?.takeIf { it.isNotBlank() }?.let { status ->
                            if (isNotEmpty()) append(" · ")
                            append(status)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 计划模式状态条（M18）：active = 已生效，pending = 下一步生效（轮次内切换）
        if (capabilities.agentFeatures && (planModeActive || planModePending != null)) {
            PlanModeStrip(
                active = planModeActive,
                pending = planModePending,
                onExit = { onSetPlanMode(false) },
            )
        }

        // 斜杠命令结果（M21）：上游命令的「分离结果 notice」，不写聊天正文
        state.commandNotice?.let { notice ->
            CommandNoticeBar(notice, onDismissCommandNotice)
        }

        // H1（docs/182）：上一轮被 max_tokens 截断 → 可行动的「继续生成」入口。
        // 流式中不显示（新一轮已经开始）；只读会话不给；冷启/切会话由宿主回填 pendingContinue。
        if (!state.readOnly && !state.streaming) {
            state.pendingContinue?.let { kind ->
                TruncationResumeBar(
                    kind = kind,
                    outputTokenLimit = outputTokenLimit,
                    queuedMessages = state.queuedPrompts.isNotEmpty(),
                    onContinue = onContinueTruncated,
                )
            }
        }

        // 输入触发面板（M21）：`/` 命令 + 技能，`@` 工作区文件/目录
        if (triggerVisible && !state.readOnly) {
            trigger?.let { active ->
                InputTriggerPanel(
                    kind = active.kind,
                    query = active.query,
                    commands = DSH_COMMANDS,
                    skills = skillChoices,
                    files = triggerFiles,
                    onPickCommand = { name -> replaceTrigger("/$name ") },
                    onPickSkill = { name -> replaceTrigger("/$name ") },
                    onPickFile = { file ->
                        formatFileMention(file.path, file.directory)?.let { mention ->
                            replaceTrigger(if (file.directory) mention else "$mention ", suppress = true)
                        }
                    },
                )
            }
        }

        // 输入区（2026-11-07 重做，仿 Operit Agent 输入条）：实现见 ChatComposer.kt
        if (state.readOnly) {
            ReadOnlyInputNotice(state.readOnlyReason)
        } else {
            ChatComposer(
                state = state,
                input = input,
                onInputChange = {
                    input = it
                    state.draft = it
                    onDraftChange(it.text)
                },
                onSubmit = { submitInput() },
                onStop = onStop,
                model = state.model,
                capabilities = capabilities,
                submitEnabled = canSubmit,
                planModeActive = planModeActive,
                planModePending = planModePending,
                onSetPlanMode = onSetPlanMode,
                replyTo = replyTo,
                onClearReply = { replyTo = null },
                onOpenGallery = { launchGallery() },
                onTakePhoto = { launchCamera() },
                onCaptureScreenshot = onCaptureScreenshot,
                onRemovePendingImage = onRemovePendingImage,
                onOpenFilePicker = { launchFiles() },
                plugins = plugins,
                skills = skills,
                onEnablePlugin = onEnablePlugin,
                onRemovePendingFile = onRemovePendingFile,
                onAttachText = onAttachText,
                notificationsEnabled = notificationsEnabled,
                onOpenNotificationAccess = onOpenNotificationAccess,
                onAttachNotifications = onAttachNotifications,
                onAttachLocation = onAttachLocation,
                memoryPicker = memoryPicker,
                onOpenGoal = { goalOpen = true },
                onOpenSchedules = {
                    scheduleTasks = schedules()
                    schedulesOpen = true
                },
                onExport = { exportSession() },
                visionEnabled = visionEnabled,
                onToggleVision = onToggleVision,
                webSearchEnabled = webSearchEnabled,
                onToggleWebSearch = onToggleWebSearch,
                onOpenModelSettings = onOpenModelSettings,
                enabledModels = enabledModels,
                onSwitchModel = onSwitchModel,
                approvalMode = approvalMode,
                onSetApprovalMode = onSetApprovalMode,
                reasoningEffort = reasoningEffort,
                onSetReasoningEffort = onSetReasoningEffort,
                maxMode = maxMode,
                onSetMaxMode = onSetMaxMode,
                autoCompact = autoCompact,
                onSetAutoCompact = onSetAutoCompact,
                autoCompactRatio = autoCompactRatio,
                onSetAutoCompactRatio = onSetAutoCompactRatio,
                onCompactNow = onCompactNow,
                modelContextWindow = modelContextWindow,
                expandThinking = expandThinking,
                onSetExpandThinking = onSetExpandThinking,
                timeContext = timeContext,
                onSetTimeContext = onSetTimeContext,
                sendWhileRunningMode = sendWhileRunningMode,
                onSetSendWhileRunningMode = onSetSendWhileRunningMode,
                onCancelQueuedPrompt = onCancelQueuedPrompt,
                onResumeQueuedPrompts = onResumeQueuedPrompts,
                memorySpaceName = memorySpaceName,
                onOpenMemoryLibrary = onOpenMemoryLibrary,
                onOpenPlugins = onOpenPlugins,
                pageEnabled = pageEnabled,
                inputFocus = inputFocus,
            )
        }
    }

    if (capabilities.agentFeatures) state.pendingApproval?.let { ApprovalDialog(it, onApproval, onStop) }
    state.pendingQuestions?.let { questions -> AskUserDialog(questions, onAnswer, onStop) }
}

/** 目标条：objective 一行 + 阶段/轮次；阶段决定颜色（Operit 状态卡语汇）。点开 /goal 面板。 */
@Composable
private fun GoalStrip(view: GoalView, onClick: () -> Unit) {
    val c = DshTheme2.colors
    val phaseColor = goalPhaseColor(view)
    val phaseLabel = goalPhaseLabel(view)
    Surface(color = phaseColor.copy(alpha = if (c.isDark) 0.16f else 0.09f)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Flag, null, tint = phaseColor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    view.goal.objective,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(phaseLabel)
                        append(" · 第 ${view.roundsStarted}/${view.goal.maxGoalRounds} 轮")
                        view.goal.blockedReason?.let { append(" · ${it.message}") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = phaseColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "目标设置", tint = phaseColor, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun goalPhaseColor(view: GoalView): Color {
    val c = DshTheme2.colors
    return when (view.goal.phase) {
        GoalPhase.ACTIVE -> c.brand
        GoalPhase.PAUSED -> c.warn
        GoalPhase.BLOCKED -> c.danger
        GoalPhase.COMPLETE -> c.success
        else -> c.sub
    }
}

@Composable
private fun goalPhaseLabel(view: GoalView): String = when (view.goal.phase) {
    GoalPhase.ACTIVE -> if (view.activation == GoalActivation.ARMED) "进行中" else "已停续轮"
    GoalPhase.PAUSED -> "已暂停"
    GoalPhase.BLOCKED -> "受阻"
    GoalPhase.COMPLETE -> "已完成"
    else -> view.goal.phase
}

/**
 * goal 轮消息：正文是给模型的内部 `<goal_round>` 提示词，界面上只显示一条分隔线 + 轮次，
 * 与上游 Chat「目标轮次不占用户气泡」的观感一致。
 */
@Composable
private fun GoalRoundNotice(message: ChatMessage) {
    val c = DshTheme2.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(c.line2))
        Spacer(Modifier.width(10.dp))
        Icon(Icons.Rounded.Flag, null, tint = c.brand, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            "自动续轮 · 第 ${message.goalRound ?: "?"} 轮",
            style = MaterialTheme.typography.labelSmall,
            color = c.brand,
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(c.line2))
    }
}

/**
 * goal 收尾通知（`source.kind:"tool-goal"`）：自动续轮里模型把目标标成 complete/blocked 后，
 * 工具注入的 `<goal_complete>` / `<goal_blocked>` 指令。正文是内部提示词，
 * 界面只显示「目标收尾 · 完成/受阻」，与自动续轮分隔条同一套观感。
 */
@Composable
private fun GoalWrapupNotice(message: ChatMessage) {
    val c = DshTheme2.colors
    val blocked = message.sourceSummary?.startsWith("blocked:") == true
    val tint = if (blocked) c.warn else c.brand
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(c.line2))
        Spacer(Modifier.width(10.dp))
        Icon(
            if (blocked) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
            null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            if (blocked) "目标收尾 · 受阻" else "目标收尾 · 完成",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(c.line2))
    }
}

/**
 * 后台任务完成通知（`source.kind:"tool-jobs"`）：任务结算后宿主注入的内部 user 消息，
 * 正文是给模型的「job 完成 + 输出」通知，界面只显示一条分隔线 + kind/状态，
 * 与 goal 收尾、自动续轮同一套语汇。
 */
@Composable
private fun JobNoticeRow(message: ChatMessage) {
    val c = DshTheme2.colors
    val summary = message.sourceSummary.orEmpty()
    val killed = summary.contains("[status: killed")
    val failed = summary.contains("[status: failed")
    val kind = summary.substringBefore(' ').ifBlank { "job" }
    val tint = if (failed) c.warn else c.brand
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(c.line2))
        Spacer(Modifier.width(10.dp))
        Icon(
            if (failed) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
            null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            when {
                failed -> "后台任务失败 · $kind"
                killed -> "后台任务已终止 · $kind"
                else -> "后台任务完成 · $kind"
            },
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(c.line2))
    }
}

/**
 * 技能目录消息（`source.kind:"skill-catalog"`，M19）：模型在 step 起点收到的内部 user 消息
 * （可用技能列表 / 「目录变了」的替换通知），界面只画一条分隔条 + 条目数。
 */
@Composable
private fun SkillCatalogRow(message: ChatMessage) {
    val c = DshTheme2.colors
    val summary = message.sourceSummary.orEmpty()
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(c.line2))
        Spacer(Modifier.width(10.dp))
        Icon(Icons.Rounded.Star, null, tint = c.brand, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            if (summary == "0 个技能") "技能目录已同步（无技能）" else "技能目录 · $summary",
            style = MaterialTheme.typography.labelSmall,
            color = c.brand,
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(c.line2))
    }
}

/**
 * 用户直接 `/名字` 调用的技能指令（`source.kind:"skill-invocation"`，M21）：
 * 正文是宿主注入的完整 `<skill_content>`（与 `skill` 工具结果同一外壳）。
 * 折叠时只显示「技能指令 · 名字」，点开渲染 `<skill_instructions>` 里的 markdown。
 */
@Composable
private fun SkillInvocationRow(message: ChatMessage) {
    val c = DshTheme2.colors
    var expanded by remember(message.id) { mutableStateOf(false) }
    val name = message.sourceSummary ?: "skill"
    val full = message.content.joinToString("\n") { it.text }
    val instructions = full.substringAfter("<skill_instructions>", "")
        .substringBefore("</skill_instructions>", "")
        .trim()
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).height(1.dp).background(c.line2))
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Rounded.Star, null, tint = c.brand, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("技能指令 · $name", style = MaterialTheme.typography.labelSmall, color = c.brand)
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                if (expanded) "收起" else "展开",
                tint = c.brand,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).height(1.dp).background(c.line2))
        }
        if (expanded) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                shape = RoundedCornerShape(14.dp),
                color = c.brand.copy(alpha = if (c.isDark) 0.12f else 0.06f),
                border = BorderStroke(1.dp, c.brand.copy(alpha = 0.18f)),
            ) {
                MarkdownText(
                    text = instructions.ifBlank { full },
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * 上下文压缩的 checkpoint（`source.kind:"compact-checkpoint"`，M43）：`/compact` 把较早的历史
 * 换成一条 user 消息（前言 + `<compacted-summary>` 摘要 + 闭标签）。界面折叠成一行
 * 「上下文已压缩 · 点开看摘要」，点开展开摘要正文（与桌面的 checkpoint 卡片同义）。
 */
@Composable
private fun CompactCheckpointRow(message: ChatMessage) {
    val c = DshTheme2.colors
    var expanded by remember(message.id) { mutableStateOf(false) }
    val blocks = message.content.filter { it.type == BlockType.TEXT }.map { it.text }
    // content = [前言+<compacted-summary>, 摘要正文, </compacted-summary>]：只显示中间那段
    val summary = when {
        blocks.size >= 3 -> blocks[1]
        blocks.size == 2 -> blocks[1]
        blocks.isEmpty() -> ""
        else -> blocks[0]
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).height(1.dp).background(c.line2))
            Spacer(Modifier.width(10.dp))
            Text(
                "上下文已压缩" + if (expanded) "" else " · 点开看摘要",
                style = MaterialTheme.typography.labelSmall,
                color = c.brand,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                if (expanded) "收起" else "展开",
                tint = c.brand,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).height(1.dp).background(c.line2))
        }
        if (expanded) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                shape = RoundedCornerShape(14.dp),
                color = c.brand.copy(alpha = if (c.isDark) 0.12f else 0.06f),
                border = BorderStroke(1.dp, c.brand.copy(alpha = 0.18f)),
            ) {
                Column(Modifier.padding(14.dp)) {
                    // 副标题：桌面 zh 文案「已压缩 N 条历史记录（约 T tokens）」
                    message.sourceSummary?.let { label ->
                        Text(label, style = MaterialTheme.typography.labelSmall, color = c.sub)
                        Spacer(Modifier.height(8.dp))
                    }
                    MarkdownText(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * 时间上下文（`source.kind:"time-context"`，M42）：上游 `dsh-time-context` 每个 step 起点注入的
 * 「当前时间 + 距上一条模型可见消息/上次注入的间隔」user 消息。正文是给模型的内部注入，
 * 界面折叠成一行「时间上下文 · HH:mm:ss」，点开展开原文（对应桌面 ContextInjectionRow 的 snapshot）。
 */
@Composable
private fun TimeContextRow(message: ChatMessage) {
    val c = DshTheme2.colors
    var expanded by remember(message.id) { mutableStateOf(false) }
    val full = message.content.joinToString("\n") { it.text }
    val clock = TIME_CONTEXT_CLOCK.find(full)?.groupValues?.getOrNull(1)
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).height(1.dp).background(c.line2))
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Rounded.Schedule, null, tint = c.brand, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                if (clock.isNullOrBlank()) "时间上下文" else "时间上下文 · $clock",
                style = MaterialTheme.typography.labelSmall,
                color = c.brand,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                if (expanded) "收起" else "展开",
                tint = c.brand,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).height(1.dp).background(c.line2))
        }
        if (expanded) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                shape = RoundedCornerShape(14.dp),
                color = c.brand.copy(alpha = if (c.isDark) 0.12f else 0.06f),
                border = BorderStroke(1.dp, c.brand.copy(alpha = 0.18f)),
            ) {
                Text(
                    full,
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.sub,
                )
            }
        }
    }
}

/**
 * 计划模式切换通知（`source.kind:"plan-mode"`，M18）：模型看到的是一条内部 user 通知，
 * 界面只画一条分隔条（与后台任务/子代理结算通知同一套语汇）。
 */
@Composable
private fun PlanModeNoticeRow(message: ChatMessage) {
    val c = DshTheme2.colors
    val entering = message.sourceSummary?.contains("to plan mode") == true
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(c.line2))
        Spacer(Modifier.width(10.dp))
        Icon(Icons.AutoMirrored.Rounded.List, null, tint = c.brand, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            if (entering) "已进入计划模式" else "已退出计划模式",
            style = MaterialTheme.typography.labelSmall,
            color = c.brand,
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(c.line2))
    }
}

/**
 * 子代理结算通知（`source.kind:"subagent-settled"`）：模型在 turn 里看到的内部 user 消息
 * （摘要 + closing message），界面只显示一条分隔线 + 终态（与 job 完成通知同一套语汇）。
 */
@Composable
private fun AgentSettledRow(message: ChatMessage) {
    val c = DshTheme2.colors
    val summary = message.sourceSummary.orEmpty()
    val stopped = summary.contains("was stopped")
    val failed = summary.contains("failed") || summary.contains("declined")
    val exhausted = summary.contains("ran out of room")
    val tint = if (failed) c.warn else c.brand
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(c.line2))
        Spacer(Modifier.width(10.dp))
        Icon(
            if (failed) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
            null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            when {
                failed -> "子代理失败"
                stopped -> "子代理已停止"
                exhausted -> "子代理达到上限"
                else -> "子代理完成"
            },
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(c.line2))
    }
}

/**
 * 子代理主动发给父代理的消息（`source.kind:"agent-message"`）：正文就是子代理的报告，
 * 界面用一张轻卡片展示（不伪装成用户气泡），让用户能看到委派结果。
 *
 * 折叠（2026-11-20 用户反馈「那条消息全部显示、特别长」）：报告往往是整份实现总结，
 * 默认只显示 [AGENT_MESSAGE_COLLAPSED_LINES] 行纯文本预览 + 行数，点标题行或「展开全文」
 * 才摊开全文（Markdown 渲染、限高 420dp 内滚，不带动整个聊天列表）；长报告才给折叠控件，
 * 短报告保持原样全显示（折叠一个三行小消息反而是多余的交互）。
 */
@Composable
private fun AgentMessageRow(message: ChatMessage) {
    val c = DshTheme2.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val raw = message.content.filter { it.type == BlockType.TEXT }.joinToString("\n") { it.text }
    val sender = Regex("^Agent (\\S+) sent a message:").find(raw)?.groupValues?.get(1).orEmpty()
    val body = raw.replaceFirst(Regex("^Agent \\S+ sent a message: \\n?"), "")
    val lineCount = body.count { it == '\n' } + 1
    val collapsible = body.length > AGENT_MESSAGE_COLLAPSED_CHARS || lineCount > AGENT_MESSAGE_COLLAPSED_LINES
    // 按消息 id 记忆：同一条消息滚出屏幕再回来仍是折叠态，不同消息互不影响。
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val showFull = expanded || !collapsible
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (c.isDark) c.surface else Color(0xFFF3F6FD),
        border = BorderStroke(1.dp, c.brand.copy(alpha = 0.24f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = collapsible) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Build, null, tint = c.brand, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "子代理消息" + if (sender.isNotEmpty()) " · ${sender.take(8)}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.brand,
                )
                if (collapsible) {
                    Spacer(Modifier.weight(1f))
                    Text("$lineCount 行", style = MaterialTheme.typography.labelSmall, color = c.faint)
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = c.faint,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (body.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                if (showFull) {
                    val bodyScroll = rememberScrollState()
                    MarkdownText(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            // 报告自己滚，不要带动整个聊天列表（与思考块同一套处理）。
                            .consumeInnerScroll(bodyScroll)
                            .verticalScroll(bodyScroll),
                    )
                } else {
                    Text(
                        agentMessagePreview(body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.text,
                        maxLines = AGENT_MESSAGE_COLLAPSED_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (collapsible) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PresentedAction(if (expanded) "收起" else "展开全文") { expanded = !expanded }
                    Spacer(Modifier.weight(1f))
                    Text("${body.length} 字", style = MaterialTheme.typography.labelSmall, color = c.faint)
                    PresentedAction("复制") { copyMessage(clipboard, context, body) }
                }
            }
        }
    }
}

/** 折叠态的行数上限 / 字符阈值（超过任一条就出现折叠控件）。 */
private const val AGENT_MESSAGE_COLLAPSED_LINES = 6
private const val AGENT_MESSAGE_COLLAPSED_CHARS = 480

/**
 * 折叠预览用的纯文本：去掉 Markdown 记法（标题井号、引用符、列表符、强调/行内代码记号、
 * 代码围栏行），否则预览里全是 `##`/`**` 这些噪音。
 */
private fun agentMessagePreview(body: String): String {
    val sb = StringBuilder()
    for (line in body.lineSequence()) {
        val l = line.trim()
        // 代码围栏行整行丢掉（内容仍保留，只是不再出现 ``` 噪音）
        if (l.startsWith("```")) continue
        when {
            l.startsWith("#") -> sb.append(l.trimStart('#').trimStart())
            l.startsWith("> ") -> sb.append(l.substring(2))
            l.startsWith("- ") || l.startsWith("* ") || l.startsWith("+ ") -> sb.append("· ").append(l.substring(2))
            else -> sb.append(l)
        }
        sb.append('\n')
    }
    val plain = sb.toString().replace(Regex("\\*\\*|__|`|~~"), "").trim()
    return plain.ifBlank { body.trim() }
}

/**
 * `/goal` 面板：查看阶段/轮次/受阻原因；用户可暂停、继续（只有用户能 resume paused）、
 * 编辑目标与轮次上限、清空。所有操作结果由 ViewModel 返回错误文案。
 */
@Composable
private fun GoalDialog(
    view: GoalView,
    onDismiss: () -> Unit,
    onPause: suspend () -> String?,
    onResume: suspend () -> String?,
    onEdit: suspend (String?, Int?) -> String?,
    onClear: suspend () -> String?,
) {
    val c = DshTheme2.colors
    val scope = rememberCoroutineScope()
    val phaseColor = goalPhaseColor(view)
    val phaseLabel = goalPhaseLabel(view)
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var objective by remember(view.goal.revision) { mutableStateOf(view.goal.objective) }
    var capText by remember(view.goal.revision) { mutableStateOf(view.goal.maxGoalRounds.toString()) }
    var confirmClear by remember { mutableStateOf(false) }
    val active = view.goal.phase == GoalPhase.ACTIVE
    val armed = view.activation == GoalActivation.ARMED
    val canResume = view.goal.phase == GoalPhase.PAUSED ||
        view.goal.phase == GoalPhase.BLOCKED ||
        (active && !armed)
    val exhausted = view.roundsStarted >= view.goal.maxGoalRounds

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.panel,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Flag, null, tint = phaseColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("目标", fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                    color = phaseColor.copy(alpha = if (c.isDark) 0.16f else 0.09f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (editing) {
                            Text("目标", style = MaterialTheme.typography.labelSmall, color = c.faint)
                            GoalTextField(objective, { objective = it }, placeholder = "目标（非空）", minHeight = 72)
                        } else {
                            Text(view.goal.objective, style = MaterialTheme.typography.bodyLarge, color = c.text)
                        }
                        Text(
                            buildString {
                                append(phaseLabel)
                                append(" · 第 ${view.roundsStarted}/${view.goal.maxGoalRounds} 轮")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = phaseColor,
                        )
                        view.goal.blockedReason?.let {
                            Text("受阻：${it.message}", style = MaterialTheme.typography.bodySmall, color = c.danger)
                        }
                    }
                }

                if (editing) {
                    Text("轮次上限", style = MaterialTheme.typography.labelSmall, color = c.faint)
                    GoalTextField(capText, { capText = it.filter(Char::isDigit).take(6) }, placeholder = "正整数", minHeight = 0)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            val cap = capText.toIntOrNull()
                            scope.launch {
                                val e = onEdit(objective, cap)
                                if (e == null) editing = false else error = e
                            }
                        }) { Text("保存", color = c.brand) }
                        TextButton(onClick = { editing = false; error = null }) { Text("取消", color = c.faint) }
                    }
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger) }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active && armed) {
                    TextButton(onClick = {
                        scope.launch {
                            val e = onPause()
                            if (e == null) onDismiss() else error = e
                        }
                    }) { Text("暂停", color = c.warn) }
                }
                if (canResume) {
                    if (exhausted && !editing) {
                        Text("轮次已用尽，先改上限", style = MaterialTheme.typography.labelSmall, color = c.warn)
                    } else if (!exhausted) {
                        TextButton(onClick = {
                            scope.launch {
                                val e = onResume()
                                if (e == null) onDismiss() else error = e
                            }
                        }) { Text("继续", color = c.brand) }
                    }
                }
                if (!editing && view.goal.phase != GoalPhase.COMPLETE) {
                    TextButton(onClick = { editing = true; error = null }) { Text("编辑", color = c.sub) }
                }
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (confirmClear) {
                    TextButton(onClick = {
                        scope.launch {
                            val e = onClear()
                            if (e == null) onDismiss() else error = e
                        }
                    }) { Text("确认清空", color = c.danger) }
                } else {
                    TextButton(onClick = { confirmClear = true }) { Text("清空", color = c.faint) }
                }
                TextButton(onClick = onDismiss) { Text("关闭", color = c.sub) }
            }
        },
    )
}

/** 面板里的输入框：与聊天输入条同一套排版（Rikka 的留白 + 细边）。 */
@Composable
private fun GoalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    minHeight: Int,
) {
    val c = DshTheme2.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight.dp),
        textStyle = TextStyle(color = c.text, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
        decorationBox = { inner ->
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = c.faint)
            inner()
        },
    )
}

/** 计划模式状态条：只做方案不改文件；轮次内切换显示「下一步生效」，idle 时可直接退出。 */
@Composable
private fun PlanModeStrip(active: Boolean, pending: Boolean?, onExit: () -> Unit) {
    val c = DshTheme2.colors
    val selected = pending ?: active
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = c.brandSoft,
        border = BorderStroke(1.dp, c.brand.copy(alpha = 0.25f)),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Rounded.List, null, tint = c.brand, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "计划模式",
                    style = MaterialTheme.typography.labelLarge,
                    color = c.brand,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when {
                        pending == true -> "下一步生效 · 只做方案，不改文件"
                        pending == false -> "下一步退出 · 恢复执行"
                        else -> "只做方案，不改文件；用 exit_plan_mode 提交审批"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = c.sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(c.surface.wash(0.85f))
                        .clickable { onExit() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("退出", style = MaterialTheme.typography.labelMedium, color = c.brand)
                }
            }
        }
    }
}

/**
 * 「别的会话在等你」横幅（M78 并行收尾）。
 *
 * 审批卡/提问框只画在**当前会话**的聊天页上（文件末尾的 `ApprovalDialog`），后台会话弹审批时
 * 用户在前台完全看不到，只有抽屉里一个橙点。这条横幅点一下切到那条会话，卡片就在那边等。
 */
@Composable
private fun WaitingElsewhereBar(items: List<WaitingSession>, onOpen: (String) -> Unit) {
    val c = DshTheme2.colors
    val first = items.first()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onOpen(first.sessionId) },
        shape = RoundedCornerShape(12.dp),
        color = c.warnSoft,
        border = BorderStroke(1.dp, c.warn.copy(alpha = 0.32f)),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Lock, null, tint = c.warn, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        items.size > 1 -> "另外 ${items.size} 个会话在等你"
                        first.question -> "「${first.title}」在等你回答"
                        else -> "「${first.title}」正在等你点审批"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (first.summary.isNotBlank()) {
                    Text(
                        if (first.question) first.summary else "${first.toolName} · ${first.summary}",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.sub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text("切过去", style = MaterialTheme.typography.labelMedium, color = c.warn)
        }
    }
}

/** M85-D：无可用回合（插件停用/缺授权）时的安全提示条（agent/v1）；可一键恢复内置回合。 */
@Composable
private fun AgentSafetyBar(reason: String, onRestore: () -> Unit) {
    val c = DshTheme2.colors
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = c.danger.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, c.danger.copy(alpha = 0.45f)),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = c.danger, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "回合安全模式",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.danger,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = c.sub,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onRestore) {
                Text("恢复内置回合", color = c.danger)
            }
        }
    }
}

/** M85-D：第三方回合插件的常驻提示（明确告诉用户「现在不是内置 dsH 引擎在跑」）。 */
@Composable
private fun AgentPluginStrip(label: String, provider: String) {
    val c = DshTheme2.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Build, null, tint = c.brand, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            "回合插件：$label（$provider）",
            style = MaterialTheme.typography.labelSmall,
            color = c.faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** M85-E：存储 / 提示词角色的安全提示条（内置插件停用 / 只读存储）。 */
@Composable
private fun RoleSafetyBar(title: String, reason: String, action: String, onRestore: () -> Unit) {
    val c = DshTheme2.colors
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = c.danger.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, c.danger.copy(alpha = 0.45f)),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = c.danger, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = c.danger,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = c.sub,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onRestore) {
                Text(action, color = c.danger)
            }
        }
    }
}

/** 斜杠命令的瞬态结果条（M21）：上游把命令结果显示在输入框附近，不进聊天正文。 */
@Composable
private fun CommandNoticeBar(text: String, onDismiss: () -> Unit) {
    val c = DshTheme2.colors
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = c.brandSoft,
        border = BorderStroke(1.dp, c.brand.copy(alpha = 0.22f)),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Terminal, null, tint = c.brand, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = c.text,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier.size(26.dp).clip(CircleShape).clickable { onDismiss() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, "关闭", tint = c.faint, modifier = Modifier.size(15.dp))
            }
        }
    }
}

/**
 * H1（docs/182）：截断后的「继续生成」卡片。
 *
 * 为什么放在输入条上方而不是某条消息下面：错误正文不落会话 JSONL，冷启后 assistant 消息上
 * 没有 error；待续状态由宿主按会话 id 持久化并回填 [ChatSessionState.pendingContinue]，
 * 所以卡片必须只依赖这个状态（消息在不在、有没有 error 都不影响）。
 */
@Composable
private fun TruncationResumeBar(
    kind: TruncationKind,
    outputTokenLimit: Int,
    queuedMessages: Boolean,
    onContinue: () -> Unit,
) {
    val c = DshTheme2.colors
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = c.surfaceAlt,
        border = BorderStroke(1.dp, c.danger.copy(alpha = 0.25f)),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = c.danger, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                truncationResumeNotice(kind, outputTokenLimit, queuedMessages),
                style = MaterialTheme.typography.labelMedium,
                color = c.text,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onContinue) {
                Text("继续生成", color = c.brand, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** 只读会话的底部说明（旧版导入副本 / 子代理会话回放 / 只读存储插件），替代输入条。 */@Composable
private fun ReadOnlyInputNotice(reason: String?) {
    val c = DshTheme2.colors
    val subagent = reason == READ_ONLY_SUBAGENT
    val storage = reason == xin.dshaa.ui.shellapi.chat.READ_ONLY_STORAGE
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = c.surfaceAlt.wash(0.75f),
        border = BorderStroke(1.dp, c.line2),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, tint = c.sub, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    when {
                        subagent -> "子代理会话 · 只读回放"
                        storage -> "存储插件会话 · 只读"
                        else -> "旧版会话 · 只读"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = c.text,
                )
                Text(
                    when {
                        subagent -> "主代理委派的子会话，结果已通过通知交回；这里只回看执行过程。"
                        storage -> "只读存储插件导出的会话，只能回看；要对话请恢复内置存储（builtin.storage.jsonl）。"
                        else -> "从备份包导入的副本，只能回看，不支持继续对话。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.sub,
                )
            }
        }
    }
}

/** ask_user_question：模型提问卡（单选/多选 + 自定义回答）。 */
@Composable
private fun AskUserDialog(
    questions: List<xin.dshaa.core.tools.AskQuestion>,
    onAnswer: (List<xin.dshaa.core.tools.AskAnswer>) -> Unit,
    onStop: () -> Unit = {},
) {
    val c = DshTheme2.colors
    /**
     * 每题的选中项。
     *
     * **别再退回 `MutableSet`**：`mutableStateMapOf<String, MutableSet<String>>` 只观察 map 的增删，
     * 对 set 内部的 add/remove 不会触发重组——用户点选项时状态其实改了，但界面一直画旧值，
     * 看起来「点完不知道选了哪个」（2026-11-07 用户反馈的根因）。
     * 这里改成不可变 List，每次点击整体替换（Compose 能观察到赋值）。
     */
    val selected = remember(questions) { mutableStateMapOf<String, List<String>>() }
    val custom = remember(questions) { mutableStateMapOf<String, String>() }

    fun submit() {
        onAnswer(
            questions.map { q ->
                xin.dshaa.core.tools.AskAnswer(
                    id = q.id,
                    selected = selected[q.id].orEmpty(),
                    custom = custom[q.id].orEmpty(),
                )
            },
        )
    }

    val chosen = selected.values.sumOf { it.size } + custom.values.count { it.isNotBlank() }

    AlertDialog(
        // 点外部/返回键不再「把已选的提交上去」：2026-11-08 用户反馈「没法取消预设回答的选择」，
        // 误触外部区域会把半截选择当成答案发出去；改成等同「跳过」。
        onDismissRequest = { onAnswer(questions.map { xin.dshaa.core.tools.AskAnswer(it.id) }) },
        containerColor = c.panel,
        title = {
            Text(
                questions.firstOrNull()?.header?.takeIf { it.isNotBlank() } ?: "需要你确认",
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                questions.forEach { q ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(q.question, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
                            if (q.multiSelect && q.options.size > 1) {
                                Text("可多选", style = MaterialTheme.typography.labelSmall, color = c.faint)
                            }
                        }
                        // 计划审批：完整 plan markdown（上游 user-questions 的 detail）
                        q.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = c.surfaceAlt.wash(0.7f),
                                border = BorderStroke(1.dp, c.line2),
                            ) {
                                Box(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                                    MarkdownText(detail, style = MaterialTheme.typography.bodySmall, color = c.text)
                                }
                            }
                        }
                        q.options.forEach { option ->
                            val current = selected[q.id].orEmpty()
                            val isOn = option.label in current
                            // 选中态要一眼能看出来：整行品牌底 + 1.5dp 品牌边 + 左侧实心圆/方块 + 右侧「已选」
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        selected[q.id] = if (q.multiSelect) {
                                            if (isOn) current - option.label else current + option.label
                                        } else {
                                            // 单选再点一次 = 取消选择（否则选错了只能提交或跳过）
                                            if (isOn) emptyList() else listOf(option.label)
                                        }
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isOn) c.brandSoft.wash(if (c.isDark) 0.85f else 0.9f) else c.surfaceAlt.wash(0.6f),
                                border = BorderStroke(if (isOn) 1.5.dp else 1.dp, if (isOn) c.brand else c.line2),
                            ) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                    // 单选画圆、多选画方（Operit 的 radio / checkbox 语汇）
                                    Box(
                                        Modifier
                                            .size(20.dp)
                                            .clip(if (q.multiSelect) RoundedCornerShape(6.dp) else CircleShape)
                                            .background(if (isOn) c.brand else Color.Transparent)
                                            .border(
                                                width = if (isOn) 1.5.dp else 1.5.dp,
                                                color = if (isOn) c.brand else c.line,
                                                shape = if (q.multiSelect) RoundedCornerShape(6.dp) else CircleShape,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (isOn) {
                                            Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                    Spacer(Modifier.width(11.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            option.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isOn) c.brandBright else c.text,
                                            fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal,
                                        )
                                        option.description?.takeIf { it.isNotBlank() }?.let {
                                            Text(it, style = MaterialTheme.typography.bodySmall, color = c.sub)
                                        }
                                    }
                                    if (isOn) {
                                        Text("已选", style = MaterialTheme.typography.labelSmall, color = c.brand)
                                    }
                                }
                            }
                        }
                        BasicTextField(
                            value = custom[q.id].orEmpty(),
                            onValueChange = { custom[q.id] = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(c.surfaceAlt.wash(0.5f))
                                .border(1.dp, c.line2, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            textStyle = TextStyle(color = c.text, fontSize = MaterialTheme.typography.bodySmall.fontSize),
                            decorationBox = { inner ->
                                if (custom[q.id].isNullOrBlank()) {
                                    Text("其他回答（可留空）", style = MaterialTheme.typography.bodySmall, color = c.faint)
                                }
                                inner()
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { submit() }) { Text(if (chosen > 0) "提交（已选 $chosen）" else "提交") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onStop() }) { Text("停止本轮", color = c.danger) }
                TextButton(onClick = {
                    onAnswer(questions.map { xin.dshaa.core.tools.AskAnswer(it.id) })
                }) { Text("跳过") }
            }
        },
    )
}

private sealed interface ChatRow {
    data class User(val message: ChatMessage) : ChatRow
    data class Assistant(val messages: List<ChatMessage>) : ChatRow

    /** 跨天分隔（Rikka 的「今天 / 昨天 / 7月3日」样式）。 */
    data class Day(val label: String) : ChatRow
}

/** 连续的多条 assistant 消息合成一个「回合」，只画一次头像/名字（Operit 的 step 分组）。 */
private fun buildRows(messages: List<ChatMessage>): List<ChatRow> {
    val out = ArrayList<ChatRow>()
    var i = 0
    var lastDay = ""
    while (i < messages.size) {
        val message = messages[i]
        val day = dayKey(message.createdAt)
        if (day != lastDay) {
            out += ChatRow.Day(dayLabel(message.createdAt))
            lastDay = day
        }
        if (message.role == MessageRole.ASSISTANT) {
            val group = ArrayList<ChatMessage>()
            while (i < messages.size && messages[i].role == MessageRole.ASSISTANT) {
                // 同一「回合」跨天时也要断开：先收口当前组，下一轮循环会插分隔条
                if (group.isNotEmpty() && dayKey(messages[i].createdAt) != day) break
                group += messages[i]
                i++
            }
            out += ChatRow.Assistant(group)
        } else {
            out += ChatRow.User(message)
            i++
        }
    }
    return out
}

/** 天的稳定 key（年月日）。 */
private fun dayKey(ms: Long): String {
    if (ms <= 0) return ""
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.DAY_OF_YEAR)}"
}

/** 分隔条文案：今天 / 昨天 / M月d日（跨年带年份）。 */
private fun dayLabel(ms: Long): String {
    if (ms <= 0) return ""
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val now = java.util.Calendar.getInstance()
    val startOfToday = (now.clone() as java.util.Calendar).apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    val todayStart = startOfToday.timeInMillis
    val dayLength = 86_400_000L
    return when {
        cal.timeInMillis >= todayStart -> "今天"
        cal.timeInMillis >= todayStart - dayLength -> "昨天"
        cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) ->
            "${cal.get(java.util.Calendar.MONTH) + 1} 月 ${cal.get(java.util.Calendar.DAY_OF_MONTH)} 日"
        else ->
            "${cal.get(java.util.Calendar.YEAR)} 年 ${cal.get(java.util.Calendar.MONTH) + 1} 月 " +
                "${cal.get(java.util.Calendar.DAY_OF_MONTH)} 日"
    }
}

/** LazyColumn 的稳定 key：user 行用消息 id，assistant 行用组内第一条消息 id。 */
private fun rowKey(row: ChatRow): String = when (row) {
    is ChatRow.User -> "u:${row.message.id}"
    is ChatRow.Assistant -> "a:${row.messages.firstOrNull()?.id ?: "empty"}"
    is ChatRow.Day -> "d:${row.label}"
}

/** 长按复制整条消息（用户气泡 / 助手正文）。 */
private fun copyMessage(clipboard: androidx.compose.ui.platform.ClipboardManager, context: android.content.Context, text: String) {
    if (text.isBlank()) return
    clipboard.setText(AnnotatedString(text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

/** 打开刚导出的 Markdown（MediaStore content://）；没有 Markdown 阅读器时给提示。 */
private fun openExportedMarkdown(context: android.content.Context, uri: Uri) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/markdown")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    } catch (t: Throwable) {
        Toast.makeText(context, "没有能打开 Markdown 的应用", Toast.LENGTH_SHORT).show()
    }
}

/** 引用：把原文按 markdown 引用块塞进输入框（上游/桌面端同样以 `>` 前缀引用）。 */
private fun quotePrefix(text: String): String =
    text.trim().lines().joinToString("\n") { "> $it" } + "\n\n"

/** 跨天分隔条：中间一枚淡淡的日期胶囊。 */
@Composable
private fun DaySeparator(label: String) {
    val c = DshTheme2.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = c.faint,
            modifier = Modifier
                .background(c.line2.wash(0.6f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/**
 * 消息长按菜单：照 Operit `ChatArea.MessageItem` 的 DropdownMenu 视觉（180dp 宽、36dp 行高、
 * 13sp、16dp 前置图标），选项按 Operit 的集合裁剪到 v4 能表达的语义：
 *
 * - 复制 / 朗读 / 引用（用户与 AI 都有）；
 * - 用户消息额外有「重发」；AI 消息额外有「重新生成」「创建分支」「信息」；
 * - Operit 的「删除 / 回滚到此处 / 修改记忆 / 插入总结 / 多选」依赖**可截断的会话**；
 *   v4 是 append-only（docs/port/01），这几项留到 branch 完整落地后再做。
 */
@Composable
private fun MessageMenuDialog(
    isUser: Boolean,
    canAct: Boolean,
    canResend: Boolean,
    canMutate: Boolean,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onQuote: () -> Unit,
    onResend: () -> Unit,
    onRegenerate: () -> Unit,
    onBranch: () -> Unit,
    onInfo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = DshTheme2.colors
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = c.panel,
            border = BorderStroke(1.dp, c.line),
            modifier = Modifier.width(228.dp),
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                MenuRow("复制", Icons.AutoMirrored.Rounded.List, onClick = onCopy)
                MenuRow("朗读", Icons.Rounded.PlayArrow, onClick = onSpeak)
                if (canAct) {
                    MenuRow("引用", Icons.Rounded.Send, onClick = onQuote)
                    if (isUser && canResend) {
                        MenuRow("重发", Icons.Rounded.Refresh, onClick = onResend)
                    }
                    if (!isUser && canMutate) {
                        MenuRow("重新生成", Icons.Rounded.Refresh, onClick = onRegenerate)
                        MenuRow("创建分支", Icons.Rounded.Share, onClick = onBranch)
                    }
                }
                MenuRow("信息", Icons.Rounded.Info, onClick = onInfo)
                // M87-D：插件消息操作项（`chat.message.actions`）；空注册表时菜单保持原样。
                SlotDock("chat.message.actions", horizontalPadding = 0, verticalPadding = 0)
            }
        }
    }
}

/** Operit 菜单行：36dp 高、13sp、16dp 图标。 */
@Composable
private fun MenuRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val c = DshTheme2.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.sub, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, fontSize = 13.sp, color = c.text)
    }
}

/** 消息信息弹窗（Operit `MessageInfoDialog` 的字段子集：角色 / 时间 / 模型 / 块数 / 来源）。 */
@Composable
private fun MessageInfoDialog(message: ChatMessage, onDismiss: () -> Unit) {
    val c = DshTheme2.colors
    val blocks = message.content
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.panel,
        title = { Text("消息信息", style = MaterialTheme.typography.titleMedium, color = c.text) },
        text = {
            Column {
                InfoLine("角色", if (message.role == MessageRole.USER) "user" else "assistant")
                InfoLine("时间", formatMessageTime(message.createdAt))
                message.usage?.let { usage ->
                    InfoLine("Token", "输入 ${usage.promptTokens} / 输出 ${usage.completionTokens} / 缓存 ${usage.cachedTokens}")
                }
                InfoLine("内容块", blocks.joinToString(" / ") { it.type.name.lowercase() })
                InfoLine("来源", message.sourceKind ?: "user")
                InfoLine("消息 id", message.id.take(8))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("好", color = c.brand) } },
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    val c = DshTheme2.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.faint, modifier = Modifier.width(64.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = c.text)
    }
}

/** 朗读一段文字（Operit 的「朗读消息」；TTS 不申请权限，失败静默）。 */
private fun speakText(context: android.content.Context, text: String) {
    if (text.isBlank()) return
    val tts = android.speech.tts.TextToSpeech(context) { status ->
        if (status == android.speech.tts.TextToSpeech.SUCCESS) {
            // 回调里拿不到刚创建的实例，交给下面队列的首次 speak 触发即可
        }
    }
    tts.language = java.util.Locale.CHINA
    tts.setSpeechRate(1.0f)
    tts.speak(text.take(4_000), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "dsh-message")
    // 让引擎把队列念完再释放；5 分钟后兜底 shutdown，避免持有引擎
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { tts.shutdown() } }, 300_000)
}

/** 输入条上方的设置芯片（Operit `ClassicChatSettingsBar` 的裁剪版：模型 / 角色 / 计划）。 */
@Composable
private fun SettingsChip(text: String, accent: Boolean = false) {
    val c = DshTheme2.colors
    Surface(
        shape = RoundedCornerShape(50),
        color = if (accent) c.brand.copy(alpha = 0.12f) else c.line2.wash(0.45f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (accent) c.brand else c.sub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** 消息时间：今天只显示 HH:mm，跨天带 MM-dd。 */
private fun formatMessageTime(ms: Long): String {
    if (ms <= 0) return ""
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val now = java.util.Calendar.getInstance()
    val clock = String.format(java.util.Locale.US, "%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    val sameDay = cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
        cal.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
    return if (sameDay) {
        clock
    } else {
        String.format(java.util.Locale.US, "%02d-%02d %s", cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH), clock)
    }
}

@Composable
private fun UserMessage(
    message: ChatMessage,
    imagePath: (String) -> String?,
    onLongPress: (ChatMessage) -> Unit,
) {
    val c = DshTheme2.colors
    // M74（用户 23:23）：引用的附件 / 插件包 / 记忆在发送时是**整段文本块**（模型要看全文），
    // 但聊天气泡里不该铺开一大长串。这里把附件块单独抽出来渲染成一条可折叠的引用 chip：
    // 默认只显示「名字 · 大小/行数」，点一下才展开原文。
    val textBlocks = message.content.filter { it.type == BlockType.TEXT }.map { it.text }
    val quoted = textBlocks.mapNotNull { parseQuotedAttachment(it) }
    val text = textBlocks
        .filter { parseQuotedAttachment(it) == null && it.trim() != ATTACHMENT_REFERENCE_HINT }
        .joinToString("\n")
        .trim()
    // 用户随消息发出的图片（docs/42）：附件按 id 从存储找回路径；丢失的只留一句提示
    val imageBlocks = message.content.filter { it.type == BlockType.IMAGE }
    val images = imageBlocks.mapNotNull { block ->
        val info = imageInfoOf(block) ?: return@mapNotNull null
        val path = imagePath(info.attachmentId) ?: return@mapNotNull null
        path to info
    }
    val missing = imageBlocks.size - images.size
    var previewPath by remember(message.id) { mutableStateOf<String?>(null) }

    val liquid = DshTheme2.liquidEnabled
    // Operit Cursor 风：右对齐圆角卡片，整张卡片可长按（文本本身仍可选片段复制）
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        val bubbleShape = if (liquid) {
            // 设计稿 .bub：border-radius 20/20/6/20
            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
        } else {
            // 0.3.5：18dp 圆角
            RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp)
        }
        Box(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .then(
                    if (liquid) {
                        // background accent-soft（不是玻璃）
                        Modifier.liquidGlass(
                            shape = bubbleShape,
                            level = LiquidLevel.Pill,
                            tint = c.brandSoft,
                            tintGradient = 1f,
                            elevated = false,
                        )
                    } else {
                        // 0.3.5：brandSoft 实底
                        Modifier.clip(bubbleShape).background(c.brandSoft)
                    },
                )
                .pointerInput(message.id) {
                    detectTapGestures(onLongPress = { onLongPress(message) })
                },
        ) {
            Column(Modifier.padding(horizontal = if (liquid) 15.dp else 12.dp, vertical = 10.dp)) {
                images.forEachIndexed { index, (path, _) ->
                    val bitmap = remember(path) { decodeSampledBitmap(path, 1200) }
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "图片附件",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .heightIn(max = 260.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { previewPath = path },
                        )
                        if (index != images.lastIndex || text.isNotBlank() || missing > 0) Spacer(Modifier.height(6.dp))
                    }
                }
                if (text.isNotBlank()) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = c.text,
                    )
                }
                quoted.forEachIndexed { index, item ->
                    if (index > 0 || text.isNotBlank()) Spacer(Modifier.height(6.dp))
                    QuotedAttachmentChip(item)
                }
                if (missing > 0) {
                    Text(
                        "（$missing 张图片已丢失）",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.faint,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(formatMessageTime(message.createdAt), style = MaterialTheme.typography.labelSmall, color = c.faint)
        // M87-D：用户消息附加行（插件 `chat.node.user`）；按内容宽度靠右，跟气泡对齐。
        SlotDock("chat.node.user", horizontalPadding = 0, verticalPadding = 2, fillWidth = false)
        previewPath?.let { path -> ImagePreviewDialog(path = path, onDismiss = { previewPath = null }) }
    }
}

/** 输入条上的待发图片缩略图（56dp 圆角 + 右上角移除）。 */
@Composable
internal fun PendingImageChip(pending: PendingImage, onRemove: () -> Unit) {
    val c = DshTheme2.colors
    Box(Modifier.size(58.dp).clip(RoundedCornerShape(10.dp))) {
        val bitmap = remember(pending.path) { decodeSampledBitmap(pending.path, 320) }
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "待发送图片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(c.line2), contentAlignment = Alignment.Center) {
                Text("丢失", style = MaterialTheme.typography.labelSmall, color = c.faint)
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color(0x99000000))
                .clickable { onRemove() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, "移除图片", tint = Color.White, modifier = Modifier.size(11.dp))
        }
    }
}

@Composable
private fun AssistantTurn(
    messages: List<ChatMessage>,
    results: Map<String, ChatMessage>,
    assistantName: String,
    assistantAvatar: String,
    expandThinking: Boolean,
    imagePath: (String) -> String?,
    /** 整轮还在跑（工具执行中 assistant 消息可能已不在流式），折叠行要显示活动标题。 */
    turnRunning: Boolean = false,
    /** 轮次进行中不显示工具卡跳转：切页/开子会话会取消当前 turn。 */
    linksEnabled: Boolean,
    onAction: (ChatMessage) -> Unit,
    onOpenSubagent: (String) -> Unit,
    onOpenJobs: () -> Unit,
    /** M84-B：任务页被页面插件关掉时，工具卡上的「查看后台任务」链接也不显示。 */
    jobsPageEnabled: Boolean = true,
    workspacePath: String? = null,
    localArtifacts: Boolean = true,
) {
    val c = DshTheme2.colors
    val liquid = DshTheme2.liquidEnabled
    val generating = messages.any { it.streaming }
    // 整轮还在跑（含工具执行阶段）——折叠行/运行中的显示都看这个，不能只看消息流式位。
    val live = generating || turnRunning
    val emptyStep = messages.size == 1 && messages[0].content.isEmpty()

    // M76（照 DSH 的「工作步骤」）：**一开始就折叠**，运行中也只显示一行活动标题
    // （正在思考 / 正在执行 xxx），点开才看推理 / 工具调用 / 中间消息；结束后标题变成
    // 「已完成 · 用时 Xs」。最终回复所在的消息始终显示（同级折叠不隐藏回答）。
    val finalIndex = messages.indexOfLast { m ->
        m.content.any { it.type == BlockType.TEXT && it.text.isNotBlank() }
    }
    val canFold = messages.isNotEmpty() && (finalIndex > 0 || live)
    var processExpanded by rememberSaveable(messages.firstOrNull()?.id ?: "empty") { mutableStateOf(false) }
    val turnDurationMs = if (messages.size >= 2) {
        (messages.last().createdAt - messages.first().createdAt).coerceAtLeast(0)
    } else 0L
    val liveLabel = run {
        val last = messages.lastOrNull()
        val tool = last?.content?.lastOrNull { it.type == BlockType.TOOL_CALL }?.name
        when {
            !live -> ""
            tool != null -> "正在执行 $tool"
            last?.content?.any { it.type == BlockType.REASONING } == true -> "正在思考"
            else -> "正在生成回复"
        }
    }
    // 本轮创建 / 修改 / 交付的文件：统一收到回复末尾（见 TurnFilesCard）。
    // 页面插件关掉工具导航时，present 的交付物卡片照旧不显示。
    val turnFiles = remember(messages, results, workspacePath, localArtifacts) {
        collectTurnFiles(messages, results, workspacePath)
            .filter { localArtifacts || it.action != FILE_ACTION_PRESENTED }
    }

    // 设计稿 .reply：整轮回复是一张 22dp 圆角的厚透镜玻璃卡（padding 15/16/7），
    // 卡里依次是 who 行（青点 + 角色名 + 模型元信息）、思考框、正文、操作栏。
    // 原生壳（0.3.5）是一列无卡片背景的普通内容，标题行左「Response」右角色名。
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (liquid) {
                    Modifier
                        .liquidEnter()
                        .liquidGlass(
                            shape = RoundedCornerShape(LiquidRadius.l),
                            level = LiquidLevel.Card,
                        )
                        .padding(start = 16.dp, end = 16.dp, top = 15.dp, bottom = 7.dp)
                } else {
                    Modifier
                },
            ),
    ) {
        if (liquid) {
            // 设计稿 .reply__who：6dp 青点 + 名字，右侧 meta-tag
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .pointerInput(messages.firstOrNull()?.id) {
                        detectTapGestures(onLongPress = { messages.lastOrNull()?.let(onAction) })
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(c.success))
                Spacer(Modifier.width(7.dp))
                Text(
                    assistantName.ifBlank { "DSH" },
                    style = MaterialTheme.typography.labelSmall,
                    color = c.sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (generating && emptyStep) {
                    Spacer(Modifier.width(8.dp))
                    Text("生成中…", style = MaterialTheme.typography.labelSmall, color = c.brand)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "Response",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                )
            }
        } else {
            // 0.3.5 / Operit Cursor 风：左侧固定 "Response" 小标题，右侧角色名（半透明小字）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .pointerInput(messages.firstOrNull()?.id) {
                        detectTapGestures(onLongPress = { messages.lastOrNull()?.let(onAction) })
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Response", style = MaterialTheme.typography.labelSmall, color = c.sub.copy(alpha = 0.7f))
                    if (generating && emptyStep) {
                        Spacer(Modifier.width(8.dp))
                        Text("生成中…", style = MaterialTheme.typography.labelSmall, color = c.brand)
                    }
                }
                Text(
                    assistantName.ifBlank { "DSH" },
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (canFold) {
            TurnProcessRow(
                expanded = processExpanded,
                liveLabel = liveLabel,
                live = live,
                durationMs = turnDurationMs,
                onToggle = { processExpanded = !processExpanded },
            )
        }

        messages.forEachIndexed { index, message ->
            // 折叠时只显示最终回复那条消息
            if (canFold && !processExpanded && index != finalIndex) return@forEachIndexed
            // 最终回复那条消息里如果混着推理/工具块，折叠状态下也只显示正文
            val foldProcessBlocks = canFold && !processExpanded && index == finalIndex
            val text = message.content.filter { it.type == BlockType.TEXT }.joinToString("") { it.text }
            val reasoning = message.content.filter { it.type == BlockType.REASONING }.joinToString("") { it.text }
            val toolCalls = message.content.filter { it.type == BlockType.TOOL_CALL }
            if (reasoning.isNotBlank() && !foldProcessBlocks) {
                Spacer(Modifier.height(8.dp))
                ThinkingBlock(text = reasoning, streaming = message.streaming, defaultExpanded = expandThinking)
            }
            toolCalls.forEach { call ->
                if (foldProcessBlocks) return@forEach
                // 交付物卡片不再夹在过程里：统一收到本轮末尾的 TurnFilesCard。
                if (call.name == "present") return@forEach
                Spacer(Modifier.height(8.dp))
                ToolCallCard(
                    name = call.name.orEmpty(),
                    arguments = call.arguments,
                    result = call.toolCallId?.let { results[it] },
                    imagePath = imagePath,
                    linksEnabled = linksEnabled,
                    onOpenSubagent = onOpenSubagent,
                    onOpenJobs = onOpenJobs,
                    jobsPageEnabled = jobsPageEnabled,
                )
            }
            if (text.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                // 助手正文保留系统文本选择（长按选片段复制）；整条消息的操作菜单只在用户气泡上
                // 设计稿三.3：水墨晕染式流式打字——流式时正文做一次从左到右的洇出。
                MarkdownText(
                    text = text,
                    modifier = if (liquid) {
                        Modifier.inkReveal(
                            trigger = message.id,
                            enabled = message.streaming,
                        )
                    } else {
                        Modifier
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = c.text,
                )
            }
            if (message.error != null) {
                Spacer(Modifier.height(6.dp))
                Text(message.error ?: "", style = MaterialTheme.typography.bodySmall, color = c.danger)
            }
            if (!message.streaming && (text.isNotBlank() || toolCalls.isNotEmpty())) {
                Spacer(Modifier.height(4.dp))
                if (liquid) {
                    // 设计稿 .acts：顶边 1px flat 分隔线，操作项 38dp 高 / 11dp 圆角 / 12.5sp dim / 按下 scale(.94)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 5.dp)
                            .drawBehind {
                                drawLine(
                                    color = c.line2,
                                    start = Offset(0f, 0f),
                                    end = Offset(size.width, 0f),
                                    strokeWidth = 1.dp.toPx(),
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            formatMessageTime(message.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = c.faint,
                            modifier = Modifier.padding(start = 2.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        // 助手正文保留系统文本选择；整条复制/引用走这个小「⋯」，不抢长按手势（M22）。
                        // 只有工具调用、没有正文的消息不给入口（复制/引用都无内容可操作）。
                        if (text.isNotBlank()) {
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .liquidClickable(scale = 0.94f) { onAction(message) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.MoreVert, "消息操作", tint = c.sub, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                } else {
                    // 0.3.5：时间 + 24dp 圆钮「⋯」
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatMessageTime(message.createdAt), style = MaterialTheme.typography.labelSmall, color = c.faint)
                        // 助手正文保留系统文本选择；整条复制/引用走这个小「⋯」，不抢长按手势（M22）。
                        // 只有工具调用、没有正文的消息不给入口（复制/引用都无内容可操作）。
                        if (text.isNotBlank()) {
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .clickable { onAction(message) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.MoreVert, "消息操作", tint = c.faint, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }
            }
        }
        // 本轮文件卡片：固定收在这一轮的最底部（正文 / 工具过程 / 操作栏之后）。
        if (turnFiles.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            TurnFilesCard(files = turnFiles, workspacePath = workspacePath)
        }
        // M87-D：助手回合 footer 附加行（插件 `chat.node.assistant`），每轮一条。
        SlotDock("chat.node.assistant", horizontalPadding = 0, verticalPadding = 2)
    }
}

/**
 * 折叠行（M76，照 DSH `TurnProcessNodeView` / `ProcessGroupHeader`）：
 * - 运行中：`正在思考` / `正在执行 read`（品牌色 + 呼吸点），默认折叠；
 * - 已结束：`已完成 · 用时 12 秒`，默认折叠，点开看完整工作步骤。
 */
@Composable
private fun TurnProcessRow(
    expanded: Boolean,
    liveLabel: String,
    live: Boolean,
    durationMs: Long,
    onToggle: () -> Unit,
) {
    val c = DshTheme2.colors
    val label = when {
        live -> liveLabel.ifBlank { "正在工作" }
        durationMs >= 1000 -> "已完成 · 用时 ${formatElapsed(durationMs)}"
        else -> "已完成"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggle() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live) {
            StatusDot(c.brand, pulse = true, size = 6.dp)
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = if (expanded) "收起工作步骤" else "展开工作步骤",
            tint = if (live) c.brand else c.brand.copy(alpha = 0.75f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (live) c.brand else c.sub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Text(
            if (expanded) "收起" else "展开",
            style = MaterialTheme.typography.labelSmall,
            color = c.faint,
        )
    }
}

/** 秒级耗时：`12 秒` / `1 分 05 秒`（工具/回合共用）。 */
private fun formatElapsed(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return if (totalSeconds < 60) {
        "$totalSeconds 秒"
    } else {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        "$minutes 分 ${seconds.toString().padStart(2, '0')} 秒"
    }
}

/**
 * 思考过程：照 Operit `CanvasExpandableHeaderRow` + `CanvasIndentedGuide`：
 * 20dp 箭头（展开时转 90°）、主色@70% 标题、流式中扫光、正文左侧 24dp 缩进 + 渐隐竖线、限高 300dp。
 * 行为：流式中按用户偏好决定展开；结束后总是折叠。
 */
@Composable
private fun ThinkingBlock(text: String, streaming: Boolean, defaultExpanded: Boolean) {
    val c = DshTheme2.colors
    val liquid = DshTheme2.liquidEnabled
    var expanded by rememberSaveable { mutableStateOf(defaultExpanded) }
    var userToggled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(streaming) {
        // Operit：思考进行中按偏好展开；结束后折叠。DSH 的「默认展开」若开启，结束后也保持展开，
        // 否则用户看不到刚生成的思考（用户明确要求在聊天页能看思考过程）。
        if (streaming) {
            if (!userToggled) expanded = defaultExpanded
        } else if (!defaultExpanded) {
            expanded = false
        }
    }

    // 流式呼吸（Operit：1400ms 线性循环的扫光；这里用同节奏的亮度变化）
    val shimmer = if (streaming) {
        val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "think")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(1400, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                RepeatMode.Reverse,
            ),
            label = "pulse",
        ).value
    } else {
        null
    }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(300),
        label = "arrow",
    )

    if (liquid) {
        // 设计稿 .think：flat-l1 底、14dp 圆角、下边距 12dp；头 40dp 高（6dp 品牌呼吸点 + 12.5sp dim + 右侧 chevron）
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(c.surfaceAlt),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clickable {
                        userToggled = true
                        expanded = !expanded
                    }
                    .padding(start = 12.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            c.brand.copy(alpha = if (shimmer == null) 0.55f else 0.45f + 0.55f * shimmer),
                        ),
                )
                Spacer(Modifier.width(8.dp))
                ThinkingTitle("思考过程", pulse = shimmer)
                Spacer(Modifier.weight(1f))
                Text("${text.length} 字", style = MaterialTheme.typography.labelSmall, color = c.faint)
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = c.sub.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp).rotate(arrowRotation),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                exit = shrinkVertically(tween(220)) + fadeOut(tween(160)),
            ) {
                // 设计稿 .think__body：padding 2/13/11/26、13px / 1.72、dim
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 26.dp, end = 13.dp, top = 2.dp, bottom = 11.dp),
                ) {
                    val reasoningScroll = rememberScrollState()
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 22.4.sp),
                        color = c.sub,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            // 思考正文滚到底后不要带动整个聊天列表（用户 2026-11-11 反馈）。
                            .consumeInnerScroll(reasoningScroll)
                            .verticalScroll(reasoningScroll),
                    )
                }
            }
        }
    } else {
        // 0.3.5：无卡片底、8dp 圆角点击区，箭头在前；正文左侧渐隐竖线 + 24dp 缩进
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                    userToggled = true
                    expanded = !expanded
                }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = c.brand.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp).rotate(arrowRotation),
                )
                Spacer(Modifier.width(4.dp))
                ThinkingTitle("思考过程", pulse = shimmer)
                Spacer(Modifier.width(8.dp))
                Text("${text.length} 字", style = MaterialTheme.typography.labelSmall, color = c.faint)
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                exit = shrinkVertically(tween(220)) + fadeOut(tween(160)),
            ) {
                // Operit CanvasIndentedGuide：正文左侧一条 1dp、主色@20%、上下渐隐的竖线
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, top = 2.dp, bottom = 2.dp)
                        .drawBehind {
                            val stroke = 1.dp.toPx()
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.16f to c.brand.copy(alpha = 0.20f),
                                    0.84f to c.brand.copy(alpha = 0.20f),
                                    1f to Color.Transparent,
                                ),
                                topLeft = Offset(0f, 0f),
                                size = Size(stroke, size.height),
                                cornerRadius = CornerRadius(999f, 999f),
                            )
                        },
                ) {
                    val reasoningScroll = rememberScrollState()
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.sub,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 4.dp, bottom = 6.dp)
                            .heightIn(max = 300.dp)
                            // 思考正文滚到底后不要带动整个聊天列表（用户 2026-11-11 反馈）。
                            .consumeInnerScroll(reasoningScroll)
                            .verticalScroll(reasoningScroll),
                    )
                }
            }
        }
    }
}

/** 流式中的标题：Operit 是扫光，这里用同节奏的呼吸亮度（渐变文字 API 在 compose 1.7 不可用）。 */
@Composable
private fun ThinkingTitle(title: String, pulse: Float?) {
    val c = DshTheme2.colors
    val alpha = if (pulse == null) 0.7f else 0.45f + 0.55f * pulse
    Text(title, style = MaterialTheme.typography.labelMedium, color = c.text.copy(alpha = alpha))
}

private fun Int.toDp() = this.dp

/**
 * 本轮**文件卡片**：把这一轮里创建 / 修改 / 交付的文件统一收在回复的最底部。
 *
 * 为什么挪到底部（用户 2026-11-20「创建或修改文件时自动把文件卡片放到最底部」）：
 * `write` / `edit` / `present` 的卡片原来夹在过程里，把正文切得七零八落；现在正文先讲完，
 * 产物固定收尾，滚到底一眼就能看到「这一轮动了哪些文件」。数据由 [collectTurnFiles] 汇总。
 *
 * 路径解析顺序（修复「点打开必然打不开」）：
 * 1. 工具结果里每行的**绝对路径**——PresentTool 用 `resolvePath(path, ctx)` 按会话 cwd 解析过，
 *    是最权威的值（子会话 / 自定义 cwd 也对）；
 * 2. 结果缺失（工具调用落盘但结果没回来的会话、被修剪过的工具结果）时，用 [workspacePath]
 *    把相对路径拼成绝对路径；
 * 3. 都拿不到 → 显示「位置未知」、不出现按钮，绝不再把相对路径当绝对路径用。
 *
 * 上游契约：`tools.json` 里 present.path 写着 "Relative paths use the Session working
 * directory"，所以模型传相对路径是合法调用，客户端必须自己补上工作区。
 */
@Composable
private fun TurnFilesCard(files: List<PresentedFile>, workspacePath: String?) {
    if (files.isEmpty()) return
    val c = DshTheme2.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    val allPresented = files.all { it.action == FILE_ACTION_PRESENTED }
    val collapsible = files.size > TURN_FILES_PREVIEW
    val shown = if (collapsible && !expanded) files.take(TURN_FILES_PREVIEW) else files
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = c.panel,
        border = BorderStroke(1.dp, c.line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (allPresented) Icons.Rounded.CheckCircle else Icons.Rounded.FolderOpen,
                    null,
                    tint = if (allPresented) c.success else c.brand,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (allPresented) "已产出 ${files.size} 个文件" else "本轮文件 · ${files.size} 个",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.text,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (collapsible) {
                    Text(
                        if (expanded) "收起" else "全部 ${files.size} 个",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.brand,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { expanded = !expanded }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
            shown.forEach { file ->
                TurnFileRow(file = file, workspacePath = workspacePath)
            }
        }
    }
}

/** 文件卡片里的一行：类型角标 + 文件名 + 新建/修改标记 + 说明/大小 + 打开/分享。 */
@Composable
private fun TurnFileRow(file: PresentedFile, workspacePath: String?) {
    val c = DshTheme2.colors
    val context = LocalContext.current
    val target = remember(file, workspacePath) { resolvePresentedFile(workspacePath, file) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(c.brandSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                file.extensionLabel,
                style = MaterialTheme.typography.labelSmall,
                color = c.brandBright,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.text,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (file.action.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    FileActionChip(file.action)
                }
            }
            Text(
                // 说明 + 大小（大小来自工具结果；没有结果时退回原始 path）
                listOf(file.description.ifBlank { file.path }, file.size)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = c.sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (target != null) {
            PresentedAction("打开") { openPresentedFile(context, target) }
            PresentedAction("分享") { sharePresentedFile(context, target) }
        } else {
            Text(
                "位置未知",
                style = MaterialTheme.typography.labelSmall,
                color = c.faint,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

/** `新建` / `修改` / `已产出` 小标记（14% 淡底 + 同色字）。 */
@Composable
private fun FileActionChip(action: String) {
    val c = DshTheme2.colors
    val tint = when (action) {
        FILE_ACTION_CREATED -> c.success
        FILE_ACTION_EDITED -> c.brand
        else -> c.brandBright
    }
    Text(
        action,
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** 卡片行尾的小按钮（打开 / 分享）。 */
@Composable
private fun PresentedAction(label: String, onClick: () -> Unit) {
    val c = DshTheme2.colors
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = c.brandBright,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    )
}

/**
 * 一张交付物条目：模型给的原始 path + 说明 + 工具结果里解析出的绝对路径 / 大小。
 * [absolutePath] 为空 = 结果里没解析到，改由工作区兜底或显示「位置未知」。
 */
private data class PresentedFile(
    val path: String,
    val description: String,
    val absolutePath: String? = null,
    val size: String = "",
    /** 这个文件在本轮里是怎么来的：`新建` / `修改` / `已产出`（present）。 */
    val action: String = "",
) {
    private val displayPath: String get() = absolutePath ?: path
    val name: String get() = displayPath.substringAfterLast('/')
    val extensionLabel: String
        get() = name.substringAfterLast('.', "").take(4).ifBlank { "FILE" }.uppercase()
}

private fun parsePresentedFiles(arguments: String): List<PresentedFile> {
    val json = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(arguments.ifBlank { "{}" })
            .let { it as? kotlinx.serialization.json.JsonObject }
    }.getOrNull() ?: return emptyList()
    val array = json["files"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return array.mapNotNull { el ->
        val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        val path = (obj["path"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val desc = (obj["description"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
        PresentedFile(path, desc)
    }
}

/** 工具结果里的一行：`- /abs/path (1.2 KB) — 说明`。 */
private data class PresentedResultLine(val absolutePath: String, val size: String)

// 路径用**惰性**匹配：说明里带 ASCII 括号（"Q3 report (final)"）时，贪婪的 .+ 会吞掉
// "(1.2 KB) — 说明" 整段当路径，导致 file.exists() 为假、误报「文件已不存在」。
private val presentedLineRegex = Regex("""^-\s+(.+?)\s+\(([^()]*)\)(?:\s+[—–-]\s+(.*))?$""")

/**
 * 从 present 的结果文本里解析「绝对路径 + 大小」。
 * 结果文本是 `ToolResult.ok("Presented N file(s):\n- {absolutePath} ({humanSize}) — {desc}")`，
 * 与 [ToolCallCard] 取结果文本的写法一致（只取 TEXT 块）。
 */
private fun parsePresentedResult(result: ChatMessage?): List<PresentedResultLine> {
    val text = result?.content
        ?.filter { it.type == BlockType.TEXT }
        ?.joinToString("") { it.text }
        .orEmpty()
    if (text.isBlank()) return emptyList()
    return text.lineSequence().mapNotNull { raw ->
        val m = presentedLineRegex.find(raw.trim()) ?: return@mapNotNull null
        PresentedResultLine(absolutePath = m.groupValues[1], size = m.groupValues[2])
    }.toList()
}

/**
 * 把结果行合回参数列表：按文件名配对，配不上的保持原样（交给工作区兜底）。
 * 不按顺序硬配——工具会跳过无效文件（"not a file"），顺序会错位。
 */
private fun mergePresentedResult(
    files: List<PresentedFile>,
    lines: List<PresentedResultLine>,
): List<PresentedFile> {
    if (files.isEmpty() || lines.isEmpty()) return files
    val unused = lines.toMutableList()
    return files.map { file ->
        val name = file.path.substringAfterLast('/')
        val hit = unused.firstOrNull { it.absolutePath.substringAfterLast('/') == name } ?: return@map file
        unused.remove(hit)
        file.copy(absolutePath = hit.absolutePath, size = hit.size)
    }
}

/** 卡片条目的真实文件：结果里的绝对路径 > 工作区 + 相对路径 > null（不猜）。 */
private fun resolvePresentedFile(workspacePath: String?, file: PresentedFile): File? {
    file.absolutePath?.takeIf { it.isNotBlank() }?.let { return File(it) }
    val raw = file.path
    if (raw.isBlank()) return null
    val direct = File(raw)
    if (direct.isAbsolute) return direct
    val root = workspacePath?.takeIf { it.isNotBlank() } ?: return null
    return File(root, raw)
}

/** 文件卡片里每条的本轮来源。 */
private const val FILE_ACTION_CREATED = "新建"
private const val FILE_ACTION_EDITED = "修改"
private const val FILE_ACTION_PRESENTED = "已产出"

/** 文件卡片默认最多铺几行，超出的折在「全部 N 个」里。 */
private const val TURN_FILES_PREVIEW = 6

/** 会改动文件的工具：write 新建或覆盖、edit 局部替换（与 core/tools FileTools 的 name 对齐）。 */
private val fileMutatingTools = setOf("write", "edit")

/** 工具参数里的 `file_path`（write / edit 都用这个名字）。 */
private val toolFilePathRegex = Regex("\"file_path\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

/** 从工具参数 JSON 里取 `file_path`，并把 JSON 转义还原成真实路径。 */
private fun parseToolFilePath(arguments: String): String? {
    val raw = toolFilePathRegex.find(arguments)?.groupValues?.get(1) ?: return null
    return raw.replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\/", "/")
        .ifBlank { null }
}

/**
 * 汇总一轮里的文件：`present` 的交付物 + `write` / `edit` 改动的文件，按绝对路径去重，
 * `present` 优先（它带说明和大小）。顺序 = 首次出现的顺序。
 *
 * 失败 / 结果没回来的调用**不进卡片**（工具卡片里仍留着原始错误，证据不丢）。
 */
private fun collectTurnFiles(
    messages: List<ChatMessage>,
    results: Map<String, ChatMessage>,
    workspacePath: String?,
): List<PresentedFile> {
    val out = LinkedHashMap<String, PresentedFile>()
    fun keyOf(file: PresentedFile): String {
        val raw = file.absolutePath?.takeIf { it.isNotBlank() }
            ?: resolvePresentedFile(workspacePath, file)?.path
            ?: file.path
        return runCatching { File(raw).canonicalPath }.getOrDefault(raw)
    }
    messages.forEach { message ->
        message.content.filter { it.type == BlockType.TOOL_CALL }.forEach { call ->
            val name = call.name.orEmpty()
            val result = call.toolCallId?.let { results[it] }
            val resultText = result?.content
                ?.filter { it.type == BlockType.TEXT }
                ?.joinToString("") { it.text }
                .orEmpty()
            when {
                name == "present" -> {
                    mergePresentedResult(parsePresentedFiles(call.arguments), parsePresentedResult(result))
                        .forEach { file ->
                            val withAction = file.copy(action = FILE_ACTION_PRESENTED)
                            out[keyOf(withAction)] = withAction
                        }
                }
                name in fileMutatingTools -> {
                    if (result == null || toolResultLooksFailed(resultText)) return@forEach
                    val path = parseToolFilePath(call.arguments) ?: return@forEach
                    val created = name == "write" && resultText.contains("Created file")
                    val file = PresentedFile(
                        path = path,
                        description = "",
                        action = if (created) FILE_ACTION_CREATED else FILE_ACTION_EDITED,
                    )
                    val key = keyOf(file)
                    // present 的条目带说明和大小，不要被 write/edit 的简版覆盖
                    if (out[key] == null) out[key] = file
                }
            }
        }
    }
    return out.values.toList()
}

/** FileProvider 的 authority 与 AndroidManifest 里的声明一致（无 applicationIdSuffix）。 */
private fun presentedFileUri(context: android.content.Context, file: File): Uri? = runCatching {
    // 全限定名：本模块没有 import androidx.core.content.FileProvider（避免与其他 FileProvider 重名）
    androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}.onFailure {
    Toast.makeText(context, "文件不在可分享的目录里：${file.name}", Toast.LENGTH_SHORT).show()
}.getOrNull()

/** 打开：先查存在（历史卡片可能指向已删除的文件），再 ACTION_VIEW。 */
private fun openPresentedFile(context: android.content.Context, file: File) {
    if (!file.exists()) {
        Toast.makeText(context, "文件已不存在：${file.name}", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = presentedFileUri(context, file) ?: return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeOf(file.name))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "没有能打开 ${file.name} 的应用", Toast.LENGTH_SHORT).show()
    }
}

/** 分享：ACTION_SEND + clipData（部分接收方只认 clipData 里的读权限授予）。 */
private fun sharePresentedFile(context: android.content.Context, file: File) {
    if (!file.exists()) {
        Toast.makeText(context, "文件已不存在：${file.name}", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = presentedFileUri(context, file) ?: return
    val intent = Intent(Intent.ACTION_SEND)
        .setType(mimeOf(file.name))
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = android.content.ClipData.newRawUri(file.name, uri) }
    runCatching { context.startActivity(Intent.createChooser(intent, "分享 ${file.name}")) }.onFailure {
        Toast.makeText(context, "没有能分享 ${file.name} 的应用", Toast.LENGTH_SHORT).show()
    }
}

private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "md", "markdown" -> "text/markdown"
    "txt", "log", "kt", "kts", "java", "py", "js", "ts", "sh", "xml", "yml", "yaml", "toml", "ini" -> "text/plain"
    "json" -> "application/json"
    "csv" -> "text/csv"
    "html", "htm" -> "text/html"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "pdf" -> "application/pdf"
    "zip" -> "application/zip"
    // present 的主用例：Office 文档 / 表格 / 幻灯片（旧实现落到 */*，选不出对应应用）
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "mp4" -> "video/mp4"
    "mp3" -> "audio/mpeg"
    else -> "*/*"
}

private data class TodoItem(val content: String, val status: String)

/** 从最近一条 todo_write 调用里取任务清单；没有就返回 null。 */
private fun latestTodos(messages: List<ChatMessage>): List<TodoItem>? {
    for (message in messages.asReversed()) {
        val call = message.content.lastOrNull { it.type == BlockType.TOOL_CALL && it.name == "todo_write" } ?: continue
        val obj = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(call.arguments.ifBlank { "{}" }) as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return null
        val array = obj["todos"] as? kotlinx.serialization.json.JsonArray ?: return null
        val items = array.mapNotNull { el ->
            val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val content = (o["content"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val status = (o["status"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "pending"
            TodoItem(content, status)
        }
        if (items.isNotEmpty()) return items
    }
    return null
}

/** 待办面板（Operit 任务清单的紧凑版）：完成度 + 展开列表。 */
@Composable
private fun TodoPanel(todos: List<TodoItem>) {
    val c = DshTheme2.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    val done = todos.count { it.status == "completed" }
    val progress = if (todos.isEmpty()) 0f else done.toFloat() / todos.size
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        shape = RoundedCornerShape(12.dp),
        color = c.surfaceAlt.wash(0.6f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.CheckCircle, null, tint = c.brand, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("任务清单", style = MaterialTheme.typography.labelMedium, color = c.text, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                Text("$done/${todos.size}", style = MaterialTheme.typography.labelSmall, color = c.faint)
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    null,
                    tint = c.faint,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.line2)) {
                Box(Modifier.fillMaxWidth(progress).height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.brand))
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                todos.forEach { item ->
                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                        Text(
                            when (item.status) {
                                "completed" -> "✓"
                                "in_progress" -> "▶"
                                else -> "○"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = when (item.status) {
                                "completed" -> c.success
                                "in_progress" -> c.brand
                                else -> c.faint
                            },
                            modifier = Modifier.width(16.dp),
                        )
                        Text(
                            item.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.status == "completed") c.faint else c.sub,
                        )
                    }
                }
            }
        }
    }
}

/** 工具卡：一次调用 + 对应结果放在一起；点开看参数与原始输出；read_image 的图直接显示缩略图。 */
@Composable
private fun ToolCallCard(
    name: String,
    arguments: String,
    result: ChatMessage?,
    imagePath: (String) -> String?,
    linksEnabled: Boolean = true,
    onOpenSubagent: (String) -> Unit = {},
    onOpenJobs: () -> Unit = {},
    jobsPageEnabled: Boolean = true,
) {
    val c = DshTheme2.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var previewPath by remember { mutableStateOf<String?>(null) }
    val resultText = result?.content?.filter { it.type == BlockType.TEXT }?.joinToString("") { it.text }.orEmpty()
    val images = result?.content?.filter { it.type == BlockType.IMAGE }.orEmpty()
    // M87-A / H1：失败态规则集中到 shell-api（插件拒绝、max_tokens 截断不执行等），避免漏判。
    val failed = toolResultLooksFailed(resultText)
    // M22：工具结果里能拿到 id 的「活对象」给一个跳转入口（子代理会话 / 后台任务面板）。
    val subagentId = if (name == "subagent" || name == "subagent_fork") {
        Regex("^started subagent (\\S+)").find(resultText)?.groupValues?.get(1)
    } else {
        null
    }
    val jobId = if (name == "bash") {
        Regex("^started background job (\\S+)").find(resultText)?.groupValues?.get(1)
    } else {
        null
    }
    // 参数面补全（2026-11-20）：`description` / `workdir` / `timeoutMs` / 后台标记 / env 项数
    // 之前只能在展开的原始 JSON 里读到，折叠态完全看不出这条命令在干什么、跑在哪个目录。
    val argObject = remember(arguments) {
        runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(arguments.ifBlank { "{}" })
                as? kotlinx.serialization.json.JsonObject
        }.getOrNull()
    }
    fun argText(key: String): String? = argObject?.get(key)
        ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        ?.takeIf { it.isNotBlank() }
    val argDescription = argText("description")?.takeIf { it != compactArgs(name, arguments) }
    val argWorkdir = argText("workdir")
    val argTimeout = argText("timeoutMs")?.toLongOrNull()?.let {
        if (it % 1000L == 0L) "${it / 1000}s" else "${it}ms"
    }
    val argBackground = argObject?.get("run_in_background")
        ?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } == true
    val argEnvCount = (argObject?.get("env") as? kotlinx.serialization.json.JsonObject)?.size ?: 0
    val argMeta = listOfNotNull(
        "后台运行".takeIf { argBackground },
        argTimeout?.let { "超时 $it" },
        argWorkdir?.let { "目录 $it" },
        if (argEnvCount > 0) "env $argEnvCount 项" else null,
    ).joinToString(" · ")

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (c.isDark) c.surface else Color(0xFFF9FAFC),
        border = BorderStroke(1.dp, c.line2),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Build, null, tint = c.brand, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(7.dp))
                Text(name, style = MaterialTheme.typography.labelMedium, color = c.text, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(6.dp))
                Text(
                    compactArgs(name, arguments),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = c.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                if (result == null) {
                    StatusDot(c.brand, pulse = true, size = 6.dp)
                } else if (failed) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = c.danger, modifier = Modifier.size(15.dp))
                } else {
                    Icon(Icons.Rounded.CheckCircle, null, tint = c.success, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    null,
                    tint = c.faint,
                    modifier = Modifier.size(17.dp),
                )
            }
            // 工具卡的「人话」副行（2026-11-20 补全）：不展开也能看清这条命令干什么、跑在哪。
            if (argDescription != null || argMeta.isNotEmpty()) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp)) {
                    if (argDescription != null) {
                        Text(
                            argDescription,
                            style = MaterialTheme.typography.labelSmall,
                            color = c.sub,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (argMeta.isNotEmpty()) {
                        Text(
                            argMeta,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = c.faint,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // M87-A：插件改写了这次工具结果 → 明确标出改写者与原始哈希（证据不因改写失真）。
            result?.sourceSummary?.takeIf { it.startsWith("已由 ") }?.let { rewrite ->
                Row(
                    Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Info, null, tint = c.warn, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(rewrite, style = MaterialTheme.typography.labelSmall, color = c.warn)
                }
            }
            // M87-C：工具卡 body 的插件附加行（chat.node.tool）。
            SlotDock("chat.node.tool", horizontalPadding = 12, verticalPadding = 0)
            // ask_user_question 的结果直接摊开：用户点完选项，聊天里要能一眼看到「刚才选的是哪个」，
            // 而不是去展开原始 JSON 找。格式来自 AskUserQuestionTool（`- [id] 问题` + `→ 答案`）。
            if (name == "ask_user_question" && resultText.isNotBlank()) {
                val pairs = ArrayList<Pair<String, String>>()
                var question = ""
                resultText.lines().forEach { raw ->
                    val line = raw.trim()
                    when {
                        line.startsWith("- [") -> question = line.substringAfter("] ", "").trim()
                        line.startsWith("→") -> pairs += question to line.removePrefix("→").trim()
                    }
                }
                if (pairs.isNotEmpty()) {
                    Column(
                        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        pairs.forEach { (q, a) ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (c.isDark) c.surfaceAlt else c.brandSoft.wash(0.55f))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                if (q.isNotBlank()) {
                                    Text(q, style = MaterialTheme.typography.labelSmall, color = c.faint)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Check, null, tint = c.brand, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        a.ifBlank { "（跳过）" },
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = c.text,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (linksEnabled && (subagentId != null || (jobId != null && jobsPageEnabled))) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (subagentId != null) onOpenSubagent(subagentId) else onOpenJobs()
                        }
                        .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        null,
                        tint = c.brand,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (subagentId != null) "回放子代理会话" else "查看后台任务",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.brand,
                    )
                }
            }
            if (images.isNotEmpty()) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    images.forEach { block ->
                        val info = imageInfoOf(block) ?: return@forEach
                        val path = imagePath(info.attachmentId) ?: return@forEach
                        AttachmentThumb(path = path, info = info, onClick = { previewPath = path })
                    }
                }
            }
            if (expanded) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    val argsScroll = rememberScrollState()
                    Text("参数", style = MaterialTheme.typography.labelSmall, color = c.faint)
                    Text(
                        arguments.ifBlank { "{}" },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        color = c.sub,
                        modifier = Modifier
                            .heightIn(max = 160.dp)
                            .consumeInnerScroll(argsScroll)
                            .verticalScroll(argsScroll)
                            .padding(top = 3.dp),
                    )
                    if (resultText.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("结果", style = MaterialTheme.typography.labelSmall, color = c.faint)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${resultText.length} 字",
                                style = MaterialTheme.typography.labelSmall,
                                color = c.faint,
                            )
                            PresentedAction("复制结果") { copyMessage(clipboard, context, resultText) }
                        }
                        val resultScroll = rememberScrollState()
                        Text(
                            // 2026-11-20：原来硬截断到 3000 字符、不给提示也没有复制入口——长输出
                            // （bash 尾部 30k）既看不全也拿不走；现在渲染到 [TOOL_RESULT_OPEN_CHARS]，
                            // 超出部分明说，并提供「复制结果」拿全文。
                            if (resultText.length > TOOL_RESULT_OPEN_CHARS) {
                                resultText.take(TOOL_RESULT_OPEN_CHARS)
                            } else {
                                resultText
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                            color = if (failed) c.danger else c.sub,
                            modifier = Modifier
                                .heightIn(max = 240.dp)
                                .consumeInnerScroll(resultScroll)
                                .verticalScroll(resultScroll)
                                .padding(top = 3.dp),
                        )
                        if (resultText.length > TOOL_RESULT_OPEN_CHARS) {
                            Text(
                                "…（卡片只渲染前 $TOOL_RESULT_OPEN_CHARS 字符，实际 ${resultText.length} 字符；" +
                                    "点「复制结果」拿全文）",
                                style = MaterialTheme.typography.labelSmall,
                                color = c.faint,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    previewPath?.let { path -> ImagePreviewDialog(path = path, onDismiss = { previewPath = null }) }
}

/** 会话 image 块里的展示信息（尺寸/字节/附件 id）。 */
private data class ImageBlockInfo(
    val attachmentId: String,
    val width: Int,
    val height: Int,
    val bytes: Int,
)

private fun imageInfoOf(block: ContentBlock): ImageBlockInfo? {
    val root = block.raw ?: return null
    val obj = runCatching { root["attachment"]?.jsonObject }.getOrNull() ?: root
    val id = runCatching { obj["attachmentId"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: return null
    return ImageBlockInfo(
        attachmentId = id,
        width = runCatching { obj["width"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 0,
        height = runCatching { obj["height"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 0,
        bytes = runCatching { obj["bytes"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 0,
    )
}

/** read_image 的缩略图：限高 260dp、圆角、点开全屏预览。 */
@Composable
private fun AttachmentThumb(path: String, info: ImageBlockInfo, onClick: () -> Unit) {
    val c = DshTheme2.colors
    val bitmap = remember(path) { decodeSampledBitmap(path, 1400) }
    if (bitmap == null) return
    Column(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "图片附件",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { onClick() },
        )
        Spacer(Modifier.height(5.dp))
        Text(
            "${info.width}×${info.height} · ${humanBytes(info.bytes)} · 点击查看大图",
            style = MaterialTheme.typography.labelSmall,
            color = c.faint,
        )
    }
}

@Composable
private fun ImagePreviewDialog(path: String, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val bitmap = remember(path) { decodeSampledBitmap(path, 2400) }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xF2000000))
                .clickable { onDismiss() }
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "图片预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text("图片已丢失", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun decodeSampledBitmap(path: String, maxDimension: Int): android.graphics.Bitmap? = try {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2
        android.graphics.BitmapFactory.decodeFile(
            path,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
} catch (_: Throwable) {
    null
}

private fun humanBytes(bytes: Int): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** 工具结果在卡片里最多渲染多少字符（超出只提示，全文走「复制结果」）。 */
private const val TOOL_RESULT_OPEN_CHARS = 20_000

private fun compactArgs(name: String, args: String): String {
    val raw = when (name) {
        "bash" -> Regex("\"command\"\\s*:\\s*\"([^\"]*)\"").find(args)?.groupValues?.get(1)
        "read", "write", "edit" -> Regex("\"file_path\"\\s*:\\s*\"([^\"]*)\"").find(args)?.groupValues?.get(1)
        "subagent", "subagent_fork" -> Regex("\"description\"\\s*:\\s*\"([^\"]*)\"").find(args)?.groupValues?.get(1)
        "send_message", "interrupt_agent" -> Regex("\"agent_id\"\\s*:\\s*\"([^\"]*)\"").find(args)?.groupValues?.get(1)
        else -> null
    } ?: args
    return raw.replace("\n", " ").take(90)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyChat(
    assistantName: String,
    assistantAvatar: String,
    assistantOpening: String = "",
    onQuickAsk: (String) -> Unit = {},
) {
    val c = DshTheme2.colors
    if (!DshTheme2.liquidEnabled) {
        // 0.3.5 / 89b1455 空态：头像 + 名称 + 开场白 + 副标题，没有水球/胶囊/快捷提问
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            DshAvatar(avatar = assistantAvatar, name = assistantName, size = 64.dp)
            Spacer(Modifier.height(18.dp))
            Text(assistantName.ifBlank { "DSH" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c.text)
            Spacer(Modifier.height(6.dp))
            if (assistantOpening.isNotBlank()) {
                // 角色卡开场白（M71）：像一条 AI 气泡，但不进会话历史（不伪造 assistant 消息）
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    color = c.surface,
                ) {
                    Text(
                        assistantOpening,
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.text,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            Text("说需求，或者让它直接动手。", style = MaterialTheme.typography.bodyMedium, color = c.sub)
        }
        return
    }
    val liquid = LocalLiquidTokens.current
    val context = LocalContext.current
    val orbName = assistantName.ifBlank { "鲸歌" }.filterNot { it.isWhitespace() }.take(4)
    // 设计稿 .is-capsule：球 ⇄ 水囊（218×94）两边都能拨，点球本体或第一颗胶囊都行
    var capsule by remember { mutableStateOf(false) }
    val toggleShape: () -> Unit = {
        capsule = !capsule
        Toast.makeText(
            context,
            if (capsule) "已切换为「厚透镜液态水囊」" else "已切换为「厚透镜失重水球」",
            Toast.LENGTH_SHORT,
        ).show()
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 中央舞台（设计稿 .hero__stage 240×172）：144dp 厚透镜水球 + 底下跟着呼吸的焦散光斑
        Box(
            modifier = Modifier.width(240.dp).height(172.dp),
            contentAlignment = Alignment.Center,
        ) {
            WaterOrbShadow(Modifier.align(Alignment.BottomCenter))
            // reserveCapsuleSpace：形变过程中不重新测量布局，胶囊不会把舞台撑开
            WaterOrb(
                size = 144.dp,
                capsule = capsule,
                reserveCapsuleSpace = true,
                onTap = toggleShape,
            ) {
                // 设计稿 .water-orb 内容：鲸鱼 glyph + 名称（14px / letter-spacing .30em）
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    WaterOrbWhale(
                        color = c.text,
                        modifier = Modifier.width(32.dp).height(21.dp),
                    )
                    Text(
                        text = orbName,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 4.2.sp, // .30em @14px
                        textAlign = TextAlign.Center,
                        color = c.text.copy(alpha = 0.95f),
                        // letter-spacing 会在最后一个字后面多留一份，右移半格抵消（等价 CSS text-indent）
                        modifier = Modifier.offset(x = 2.1.dp),
                    )
                }
            }
        }
        if (assistantOpening.isNotBlank()) {
            // 角色卡开场白（M71）：像一条 AI 气泡，但不进会话历史（不伪造 assistant 消息）
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = c.panel,
            ) {
                Text(
                    assistantOpening,
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        // 设计稿 .hero：stage / line / pills 之间统一 18 的间距
        Spacer(Modifier.height(18.dp))
        // 设计稿 .hero__line：13.5px、dim、letter-spacing .06em
        Text(
            "说需求，或者让它直接动手。",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 13.5.sp,
            color = c.sub,
            letterSpacing = 0.8.sp,
        )
        Spacer(Modifier.height(18.dp))
        // 快捷提问胶囊（设计稿 .qpill）：38dp 高、pill-fill 底色、不上滤镜、按下缩到 .96
        val quick = listOf(
            "这个工作区里有什么" to Icons.Rounded.FolderOpen,
            "能装哪些插件" to Icons.Rounded.Extension,
            "帮我配个每日提醒" to Icons.Rounded.Alarm,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 2,
            modifier = Modifier.widthIn(max = 400.dp),
        ) {
            // 第 0 颗（设计稿 #orbShapeToggle）：切换球 / 水囊，图标跟着目标形状走
            Row(
                modifier = Modifier
                    .liquidEnter(index = 0)
                    .height(38.dp)
                    .clip(RoundedCornerShape(LiquidRadius.pill))
                    .background(liquid.pillFill)
                    .liquidClickable(scale = 0.96f, onClick = toggleShape)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                OrbShapeIcon(
                    capsule = capsule,
                    tint = c.brand,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    if (capsule) "切换为圆形失重水球" else "切换为液态胶囊水囊",
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 12.5.sp,
                    color = c.text,
                )
            }
            quick.forEachIndexed { i, item ->
                Row(
                    modifier = Modifier
                        .liquidEnter(index = i + 1)
                        .height(38.dp)
                        .clip(RoundedCornerShape(LiquidRadius.pill))
                        .background(liquid.pillFill)
                        .liquidClickable(scale = 0.96f, onClick = { onQuickAsk(item.first) })
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(item.second, contentDescription = null, tint = c.brand, modifier = Modifier.size(14.dp))
                    Text(
                        item.first,
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = 12.5.sp,
                        color = c.text,
                    )
                }
            }
        }
    }
}

/**
 * 设计稿 #orbShapeToggle 的小图标：圆形失重水球 ⇄ 液态胶囊水囊。
 * 14dp 的画布里画一个 1.6dp 的描边轮廓，圆 ↔ 胶囊之间按 440ms 的厚透镜缓动插值。
 */
@Composable
private fun OrbShapeIcon(
    capsule: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val m by animateFloatAsState(
        targetValue = if (capsule) 1f else 0f,
        animationSpec = tween(durationMillis = 440, easing = LiquidMotion.enterEasing),
        label = "orbShapeIcon",
    )
    Canvas(modifier) {
        val sphere = size.minDimension * 0.72f
        val w = sphere + (size.width - sphere) * m
        val h = sphere + (size.height * 0.58f - sphere) * m
        drawRoundRect(
            color = tint,
            topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
            size = Size(w, h),
            cornerRadius = CornerRadius(h / 2f, h / 2f),
            style = Stroke(width = 1.6.dp.toPx()),
        )
    }
}
@Composable
private fun ApprovalDialog(
    approval: PendingApproval,
    onApproval: (Boolean, Boolean) -> Unit,
    onStop: () -> Unit = {},
) {
    val c = DshTheme2.colors
    AlertDialog(
        onDismissRequest = { onApproval(false, false) },
        containerColor = c.panel,
        title = { Text("允许执行 ${approval.toolName}？") },
        text = {
            Column {
                Text(
                    approval.summary,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 8,
                    modifier = Modifier
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    // 只有 bash 真的不受工作区限制；其它工具不能再跟着写这句（M88-I 收口）。
                    if (approval.toolName == "bash") {
                        "工具在 App 工作区里执行；bash 不受工作区限制，请确认命令安全。"
                    } else {
                        "工具在 App 工作区里执行，请确认参数与操作安全。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApproval(true, false) }) { Text("允许一次") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onStop() }) { Text("停止本轮", color = c.danger) }
                TextButton(onClick = { onApproval(false, false) }) { Text("拒绝") }
                TextButton(onClick = { onApproval(true, true) }) { Text("总是允许") }
            }
        },
    )
}

/**
 * 会话提醒面板：列出当前会话的提醒（标题 + 下次触发 + 状态），可手动删除。
 * 提醒由模型用 `schedule_*` 工具创建；这里只做查看/删除（「当前会话」语义与快照文案一致）。
 */
@Composable
private fun SchedulesDialog(
    tasks: List<ScheduledTask>,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = DshTheme2.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.panel,
        title = { Text("会话提醒", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (tasks.isEmpty()) {
                    Text(
                        "这个会话还没有提醒。让模型「30 分钟后提醒我…」即可创建。",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.faint,
                    )
                } else {
                    tasks.forEach { task ->
                        Surface(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                            color = c.surfaceAlt,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        task.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = c.text,
                                    )
                                    Text(
                                        if (task.active) {
                                            "下次 ${ScheduleTime.localText(task.nextRunAt, task.timing.timeZone)}"
                                        } else {
                                            "已结束"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = c.faint,
                                    )
                                }
                                TextButton(onClick = { onDelete(task.id) }) { Text("删除", color = c.faint) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
