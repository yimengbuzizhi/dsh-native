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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import xin.dshaa.nativeapp.chat.ATTACHMENT_REFERENCE_HINT
import xin.dshaa.nativeapp.chat.ChatSessionState
import xin.dshaa.nativeapp.chat.PendingApproval
import xin.dshaa.nativeapp.chat.PendingImage
import xin.dshaa.nativeapp.chat.READ_ONLY_SUBAGENT
import xin.dshaa.nativeapp.chat.WaitingSession
import xin.dshaa.ui.design.component.DshAvatar
import xin.dshaa.ui.design.component.MarkdownText
import xin.dshaa.ui.design.component.StatusDot
import xin.dshaa.ui.design.theme.DshTheme2

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
     * 会话工作区根目录：`present` 卡片用它把模型给的**相对路径**拼成绝对路径。
     * 上游契约（tools.json 的 present.path）写明「Relative paths use the Session working directory」，
     * 所以相对路径是合法调用，卡片必须自己按工作区解析，不能当绝对路径用。
     * null = 只认绝对路径（老调用方行为不变）。
     */
    workspacePath: String? = null,
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
    /** 发送入口（M21）：整行先交给 ViewModel 解析斜杠命令；返回 true = 已接受，可清空输入。 */
    onSubmit: (String) -> Boolean,
    onStop: () -> Unit,
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
    memorySpaceName: String = "Default",
    onOpenMemoryLibrary: () -> Unit = {},
    onOpenPlugins: () -> Unit = {},
    /** 其它会话正在等审批/回答（M78 并行）：卡片只画在当前会话上，这里给一条可点横幅。 */
    waitingElsewhere: List<WaitingSession> = emptyList(),
    onFocusSession: (String) -> Unit = {},
) {
    val c = DshTheme2.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf(state.draft) }
    // M76：横竖屏切换时宽的抽屉分支会重建整棵子树（remember 丢失），草稿以会话状态为准。
    androidx.compose.runtime.LaunchedEffect(state) { input = state.draft }
    /** 长按菜单「引用」后的输入条预览（Operit 的 reply preview）。 */
    var replyTo by remember { mutableStateOf<ChatMessage?>(null) }
    /** 输入条左侧「＋」的附件/设置菜单。 */
    var attachMenu by remember { mutableStateOf(false) }
    /** 触发面板（M21）：同一个 token/查询被选过之后不再自动弹回（上游 input-trigger 的「关闭保持关闭」）。 */
    var suppressedTrigger by remember { mutableStateOf<String?>(null) }
    var triggerFiles by remember { mutableStateOf<List<FileCandidate>>(emptyList()) }
    val trigger = inputTrigger(input.text, input.selection.start)
    val triggerVisible = trigger != null && trigger.key() != suppressedTrigger
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

    // 相册：系统 Photo Picker（不需要存储权限；多选后逐张落附件存储）
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        uris.forEach { onAttachImage(it) }
    }
    // 相机：FileProvider 临时文件 + 系统相机（不申请 CAMERA 权限，拍照由相机应用完成）
    var cameraTemp by remember { mutableStateOf<File?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = cameraTemp
        cameraTemp = null
        if (ok && file != null) onAttachPhoto(file) else file?.delete()
    }
    fun launchCamera() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File.createTempFile("chat-", ".jpg", dir)
        cameraTemp = file
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        runCatching { takePicture.launch(uri) }.onFailure {
            file.delete()
            Toast.makeText(context, "没有可用的相机应用", Toast.LENGTH_SHORT).show()
        }
    }

    // 任意文件：系统文件选择器（多选；VM 按类型分流：图片走图片管线，文本读正文）
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { onAttachFile(it) }
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
        if (state.streaming) return
        // 允许只发图片（文字为空）：附件本身就是一条消息的内容
        if (input.text.isBlank() && state.pendingImages.isEmpty()) return
        if (onSubmit(input.text)) {
            input = TextFieldValue("")
            state.draft = input
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
            .background(c.background)
            .imePadding(),
    ) {
        // 顶栏：返回 + 标题/模型 + 搜索 + 更多；搜索态换成输入框 + 命中计数 + 上/下
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.surface)
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
                        else "${assistantName.ifBlank { "DSH" }} · ${state.model}",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.faint,
                    )
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable { onOpenWorkspace() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Folder, "工作区", tint = c.sub, modifier = Modifier.size(21.dp))
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

        if (schedulesOpen) {
            SchedulesDialog(
                tasks = scheduleTasks,
                onDelete = { id ->
                    onDeleteSchedule(id)
                    scheduleTasks = schedules()
                },
                onDismiss = { schedulesOpen = false },
            )
        }
        if (goalOpen) {
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
                canResend = target.role == MessageRole.USER && targetText.isNotBlank(),
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
                containerColor = c.surface,
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
        if (waitingElsewhere.isNotEmpty()) {
            WaitingElsewhereBar(waitingElsewhere, onFocusSession)
        }

        // 目标条：goal 工具写入的长期目标（active/paused/blocked/complete）；点开 /goal 面板
        state.goal?.let { GoalStrip(it) { goalOpen = true } }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (visible.isEmpty()) {
                EmptyChat(assistantName, assistantAvatar, assistantOpening)
            } else {
                // LazyColumn：长会话（含 41 条导入会话、5000+ 消息）只组合可见项；
                // key 用消息 id，搜索跳转/流式追加都不会错位。
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp)
                        .nestedScroll(followConnection),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
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
                                    workspacePath = workspacePath,
                                    linksEnabled = !state.streaming,
                                    // 工具执行期间 assistant 消息本身已「不再流式」，但整轮还在跑：
                                    // 折叠行要按轮次状态显示活动标题，不能显示「已完成」
                                    // （用户 2026-11-11 反馈：块显示已完成、状态条还在调用工具）。
                                    turnRunning = state.streaming && row === rows.lastOrNull(),
                                    onAction = { actionTarget = it },
                                    onOpenSubagent = onOpenSubagent,
                                    onOpenJobs = onOpenJobs,
                                )
                            }
                        }
                    }
                }
                if ((!followOutput || listState.canScrollForward) && visible.isNotEmpty()) {
                    val scope = rememberCoroutineScope()
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(c.surface)
                            .clickable {
                                followOutput = true
                                scope.launch {
                                    listState.animateScrollToItem(rows.lastIndex.coerceAtLeast(0))
                                    listState.animateScrollBy(100_000f)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.ExpandMore, "回到底部", tint = c.brand, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // 待办面板：取最近一次 todo_write 的参数（模型维护的任务清单）
        latestTodos(visible)?.let { todos ->
            TodoPanel(todos)
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
        if (planModeActive || planModePending != null) {
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
                },
                onSubmit = { submitInput() },
                onStop = onStop,
                model = state.model,
                planModeActive = planModeActive,
                planModePending = planModePending,
                onSetPlanMode = onSetPlanMode,
                replyTo = replyTo,
                onClearReply = { replyTo = null },
                onOpenGallery = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onTakePhoto = { launchCamera() },
                onCaptureScreenshot = onCaptureScreenshot,
                onRemovePendingImage = onRemovePendingImage,
                onOpenFilePicker = { runCatching { filePicker.launch(arrayOf("*/*")) } },
                plugins = plugins,
                skills = skills,
                onEnablePlugin = onEnablePlugin,
                onRemovePendingFile = onRemovePendingFile,
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
                memorySpaceName = memorySpaceName,
                onOpenMemoryLibrary = onOpenMemoryLibrary,
                onOpenPlugins = onOpenPlugins,
                inputFocus = inputFocus,
            )
        }
    }

    state.pendingApproval?.let { ApprovalDialog(it, onApproval, onStop) }
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
 */
@Composable
private fun AgentMessageRow(message: ChatMessage) {
    val c = DshTheme2.colors
    val raw = message.content.filter { it.type == BlockType.TEXT }.joinToString("\n") { it.text }
    val sender = Regex("^Agent (\\S+) sent a message:").find(raw)?.groupValues?.get(1).orEmpty()
    val body = raw.replaceFirst(Regex("^Agent \\S+ sent a message: \\n?"), "")
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (c.isDark) c.surface else Color(0xFFF3F6FD),
        border = BorderStroke(1.dp, c.brand.copy(alpha = 0.24f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Build, null, tint = c.brand, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "子代理消息" + if (sender.isNotEmpty()) " · ${sender.take(8)}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.brand,
                )
            }
            if (body.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = c.text)
            }
        }
    }
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
        containerColor = c.surface,
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
                        .background(c.surface.copy(alpha = 0.85f))
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

/** 只读会话的底部说明（旧版导入副本 / 子代理会话回放），替代输入条。 */
@Composable
private fun ReadOnlyInputNotice(reason: String?) {
    val c = DshTheme2.colors
    val subagent = reason == READ_ONLY_SUBAGENT
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = c.surfaceAlt.copy(alpha = 0.75f),
        border = BorderStroke(1.dp, c.line2),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, tint = c.sub, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    if (subagent) "子代理会话 · 只读回放" else "旧版会话 · 只读",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = c.text,
                )
                Text(
                    if (subagent) "主代理委派的子会话，结果已通过通知交回；这里只回看执行过程。"
                    else "从备份包导入的副本，只能回看，不支持继续对话。",
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
        containerColor = c.surface,
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
                                color = c.surfaceAlt.copy(alpha = 0.7f),
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
                                color = if (isOn) c.brandSoft.copy(alpha = if (c.isDark) 0.85f else 0.9f) else c.surfaceAlt.copy(alpha = 0.6f),
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
                                .background(c.surfaceAlt.copy(alpha = 0.5f))
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
                .background(c.line2.copy(alpha = 0.6f), RoundedCornerShape(50))
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
            color = c.surface,
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
                    if (!isUser) {
                        MenuRow("重新生成", Icons.Rounded.Refresh, onClick = onRegenerate)
                        MenuRow("创建分支", Icons.Rounded.Share, onClick = onBranch)
                    }
                }
                MenuRow("信息", Icons.Rounded.Info, onClick = onInfo)
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
        containerColor = c.surface,
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
        color = if (accent) c.brand.copy(alpha = 0.12f) else c.line2.copy(alpha = 0.45f),
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

    // Operit Cursor 风：右对齐圆角卡片，整张卡片可长按（文本本身仍可选片段复制）
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp),
            color = c.brandSoft,
            modifier = Modifier
                .widthIn(max = 340.dp)
                .pointerInput(message.id) {
                    detectTapGestures(onLongPress = { onLongPress(message) })
                },
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
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
    /** 会话工作区根：`present` 卡片解析相对路径（见 [ChatScreen] 的同名参数）。 */
    workspacePath: String? = null,
    /** 整轮还在跑（工具执行中 assistant 消息可能已不在流式），折叠行要显示活动标题。 */
    turnRunning: Boolean = false,
    /** 轮次进行中不显示工具卡跳转：切页/开子会话会取消当前 turn。 */
    linksEnabled: Boolean,
    onAction: (ChatMessage) -> Unit,
    onOpenSubagent: (String) -> Unit,
    onOpenJobs: () -> Unit,
) {
    val c = DshTheme2.colors
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

    Column(Modifier.fillMaxWidth()) {
        // Operit Cursor 风：左侧固定 "Response" 小标题，右侧角色名 · 模型（半透明小字）
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
                Spacer(Modifier.height(8.dp))
                if (call.name == "present") {
                    // 卡片要拿工具结果：里面才有按会话 cwd 解析过的**绝对路径**和文件大小
                    // （模型给的 path 可能是相对路径，见 PresentedCard 的注释）。
                    PresentedCard(
                        arguments = call.arguments,
                        result = call.toolCallId?.let { results[it] },
                        workspacePath = workspacePath,
                    )
                } else {
                    ToolCallCard(
                        name = call.name.orEmpty(),
                        arguments = call.arguments,
                        result = call.toolCallId?.let { results[it] },
                        imagePath = imagePath,
                        linksEnabled = linksEnabled,
                        onOpenSubagent = onOpenSubagent,
                        onOpenJobs = onOpenJobs,
                    )
                }
            }
            if (text.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                // 助手正文保留系统文本选择（长按选片段复制）；整条消息的操作菜单只在用户气泡上
                MarkdownText(
                    text = text,
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

/** 流式中的标题：Operit 是扫光，这里用同节奏的呼吸亮度（渐变文字 API 在 compose 1.7 不可用）。 */
@Composable
private fun ThinkingTitle(title: String, pulse: Float?) {
    val c = DshTheme2.colors
    val alpha = if (pulse == null) 0.7f else 0.45f + 0.55f * pulse
    Text(title, style = MaterialTheme.typography.labelMedium, color = c.text.copy(alpha = alpha))
}

private fun Int.toDp() = this.dp

/**
 * `present` 的交付物卡：文件名 + 说明 + 大小 + 打开 / 分享（FileProvider）。
 *
 * 路径解析顺序（修复「点打开必然打不开」）：
 * 1. 工具结果里每行的**绝对路径**——PresentTool 用 `resolvePath(path, ctx)` 按会话 cwd 解析过，
 *    是最权威的值（子会话 / 自定义 cwd 也对）；
 * 2. 结果缺失（压缩、工具结果修剪之后的历史卡片）时，用 [workspacePath] 把相对路径拼成绝对路径；
 * 3. 都拿不到 → 显示「位置未知」、不出现按钮，绝不再把相对路径当绝对路径用。
 *
 * 上游契约：`tools.json` 里 present.path 写着 "Relative paths use the Session working
 * directory"，所以模型传相对路径是合法调用，客户端必须自己补上工作区。
 */
@Composable
private fun PresentedCard(arguments: String, result: ChatMessage?, workspacePath: String?) {
    val c = DshTheme2.colors
    val context = LocalContext.current
    val files = remember(arguments, result, workspacePath) {
        mergePresentedResult(parsePresentedFiles(arguments), parsePresentedResult(result))
    }
    if (files.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = c.surface,
        border = BorderStroke(1.dp, c.line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, tint = c.success, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "已产出 ${files.size} 个文件",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.text,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            files.forEach { file ->
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
                        Text(
                            file.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.text,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
        }
    }
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

/** FileProvider 的 authority 与 AndroidManifest 里的声明一致（无 applicationIdSuffix）。 */
private fun presentedFileUri(context: android.content.Context, file: File): Uri? = runCatching {
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
        color = c.surfaceAlt.copy(alpha = 0.6f),
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
) {
    val c = DshTheme2.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    var previewPath by remember { mutableStateOf<String?>(null) }
    val resultText = result?.content?.filter { it.type == BlockType.TEXT }?.joinToString("") { it.text }.orEmpty()
    val images = result?.content?.filter { it.type == BlockType.IMAGE }.orEmpty()
    val failed = resultText.startsWith("User denied") || resultText.contains("not found", true) ||
        resultText.startsWith("<error>") || resultText.contains("error:", true)
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
                                    .background(if (c.isDark) c.surfaceAlt else c.brandSoft.copy(alpha = 0.55f))
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
            if (linksEnabled && (subagentId != null || jobId != null)) {
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
                        Text("结果", style = MaterialTheme.typography.labelSmall, color = c.faint)
                        val resultScroll = rememberScrollState()
                        Text(
                            resultText.take(3000),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                            color = if (failed) c.danger else c.sub,
                            modifier = Modifier
                                .heightIn(max = 240.dp)
                                .consumeInnerScroll(resultScroll)
                                .verticalScroll(resultScroll)
                                .padding(top = 3.dp),
                        )
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

@Composable
private fun EmptyChat(assistantName: String, assistantAvatar: String, assistantOpening: String = "") {
    val c = DshTheme2.colors
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
        containerColor = c.surface,
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
                    "工具在 App 工作区里执行；bash 不受工作区限制，请确认命令安全。",
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
        containerColor = c.surface,
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
