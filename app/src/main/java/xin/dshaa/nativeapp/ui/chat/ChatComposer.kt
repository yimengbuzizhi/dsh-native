package xin.dshaa.nativeapp.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ScreenshotMonitor
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xin.dshaa.core.input.DSH_COMMANDS
import xin.dshaa.core.model.ChatMessage
import xin.dshaa.core.plugins.PluginRecord
import xin.dshaa.core.skill.SkillSummary
import xin.dshaa.nativeapp.chat.ChatMirror
import xin.dshaa.nativeapp.chat.ChatSessionState
import xin.dshaa.nativeapp.chat.CommandSheet
import xin.dshaa.nativeapp.data.McpConfigStore
import xin.dshaa.nativeapp.data.McpServer
import xin.dshaa.nativeapp.device.attach.AttachmentCapture
import xin.dshaa.nativeapp.device.attach.LocationCapture
import xin.dshaa.nativeapp.memory.MemoryPickerDialog
import xin.dshaa.nativeapp.memory.buildMemoryReference
import xin.dshaa.ui.design.theme.DshTheme2
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * 聊天输入区（2026-11-07 第二版，照 Operit `AgentChatInputSection` 的 Agent 输入条重排）。
 *
 * Operit 的几何（`chat-composer.css` 的 `.agent-input-card`/`.agent-input-bottom`）：
 * 1. 卡片一行 **先输入**（textarea 可长到 144px）+ 右侧「全屏编辑」小圆钮；
 * 2. 下一行 **模型 pill 在左**（带下箭头，点开选模型/开关能力）；右侧依次是齿轮、＋、发送圆钮；
 * 3. 发送圆钮外面套一圈进度环（生成中转），不是在输入框里塞额外的行。
 *
 * 旧版的「模型芯片 + 计划芯片 + Enter 发送」整行已删：计划模式/目标/提醒/导出都进了齿轮面板，
 * 输入区只剩 Operit 那四个控件，视线不再被一排小字占满。
 *
 * 另：`state.attachError`（附件失败/模型不识图）之前从未显示过，这里补在卡片上方。
 */
@Composable
fun ChatComposer(
    state: ChatSessionState,
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    model: String,
    planModeActive: Boolean,
    planModePending: Boolean?,
    onSetPlanMode: (Boolean) -> Unit,
    replyTo: ChatMessage?,
    onClearReply: () -> Unit,
    onOpenGallery: () -> Unit,
    onTakePhoto: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    onRemovePendingImage: (String) -> Unit,
    /** 系统文件选择（2026-11-08 夜间：附件不只图片）。 */
    onOpenFilePicker: () -> Unit = {},
    /** 「包」引用（照 Operit `PackageSelectorDialog`）：可选的插件 / 技能 / MCP。 */
    plugins: List<PluginRecord> = emptyList(),
    skills: List<SkillSummary> = emptyList(),
    /** Operit 选包时会自动启用标准包；鲸歌对应把插件启用后再引用。 */
    onEnablePlugin: (String) -> Unit = {},
    onRemovePendingFile: (String) -> Unit = {},
    onOpenGoal: () -> Unit,
    onOpenSchedules: () -> Unit,
    /** 齿轮面板里的「导出 Markdown」：实现仍在 ChatScreen（要拿 context 写文件）。 */
    onExport: () -> Unit,
    /** 当前模型的识图声明（模型配置页同一个开关）。 */
    visionEnabled: Boolean,
    onToggleVision: (Boolean) -> Unit,
    /** 当前模型的联网搜索开关（控制请求里给不给 web_search）。 */
    webSearchEnabled: Boolean,
    onToggleWebSearch: (Boolean) -> Unit,
    /** 模型面板里的「打开模型配置」（二级页）。 */
    onOpenModelSettings: () -> Unit,
    /** 最近保存过的模型名（模型下拉直接切，顺序=最近使用在前）。 */
    /** 已勾选的模型（M74：切换器只在这些里切，不再列「历史用过」的模型）。 */
    enabledModels: List<String> = emptyList(),
    /** 输入条模型下拉：直接切 settings.model + 当前会话显示。 */
    onSwitchModel: (String) -> Unit = {},
    /** 工具审批方式（settings.approvalMode）：`/permission` 面板与设置 → 行为同一份值。 */
    approvalMode: String = "ask",
    onSetApprovalMode: (String) -> Unit = {},
    /** 思考模式 / 思考程度（M58，照 Operit 输入条 `ThinkingSettingsItem`）。 */
    reasoningEffort: String = "high",
    onSetReasoningEffort: (String) -> Unit = {},
    /** Max 模式（Operit `max_mode`）：超大上下文，关掉自动压缩。 */
    maxMode: Boolean = false,
    onSetMaxMode: (Boolean) -> Unit = {},
    /** 行为面板：思考过程默认展开 / 时间上下文（与设置 → 行为同一份值）。 */
    expandThinking: Boolean = true,
    onSetExpandThinking: (Boolean) -> Unit = {},
    timeContext: Boolean = true,
    onSetTimeContext: (Boolean) -> Unit = {},
    /** 记忆空间名（鲸歌目前只有 Default，多空间在记忆库二期）。 */
    memorySpaceName: String = "Default",
    onOpenMemoryLibrary: () -> Unit = {},
    onOpenPlugins: () -> Unit = {},
    inputFocus: FocusRequester,
) {
    val c = DshTheme2.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val mcp = remember { McpConfigStore.get(context) }
    val mcpServers = mcp.servers
    var attachSheet by remember { mutableStateOf(false) }
    var packagePicker by remember { mutableStateOf(false) }
    var memoryPicker by remember { mutableStateOf(false) }
    var settingsSheet by remember { mutableStateOf(false) }
    var behaviorSheet by remember { mutableStateOf(false) }
    var modelSheet by remember { mutableStateOf(false) }
    var permissionSheet by remember { mutableStateOf(false) }
    var helpSheet by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    val planOn = planModePending ?: planModeActive
    val canSend = input.text.isNotBlank() || state.pendingImages.isNotEmpty() || state.pendingFiles.isNotEmpty()
    val enabledPluginCount = plugins.count { it.enabled }

    // 斜杠命令要求打开面板（M40）：命令在 ViewModel 里解析，这里消费一次性信号。
    // 放在 composable 顶层而不是点击回调里：调试通道 dsh_send "/model" 也能弹出面板。
    LaunchedEffect(state.pendingCommandSheet) {
        val requested = state.pendingCommandSheet ?: return@LaunchedEffect
        when (requested) {
            CommandSheet.MODEL -> modelSheet = true
            CommandSheet.PERMISSION -> permissionSheet = true
            CommandSheet.HELP -> helpSheet = true
        }
        android.util.Log.i("DshCmd", "sheet: $requested consumed")
        state.pendingCommandSheet = null
    }

    /**
     * 把帮助面板里点中的命令插到光标处（不覆盖已有草稿），再把焦点还给输入框。
     * 位置按 `selection.end` 收敛到文本长度内：面板打开时输入框失焦，选区可能过期。
     */
    fun insertCommandText(text: String) {
        val at = input.selection.end.coerceIn(0, input.text.length)
        val merged = input.text.substring(0, at) + text + input.text.substring(at)
        onInputChange(TextFieldValue(merged, TextRange(at + text.length)))
        android.util.Log.i("DshCmd", "help: insert $text")
        runCatching { inputFocus.requestFocus() }
    }

    /**
     * 「包」引用（照 Operit `AttachmentDelegate.attachPackageInternal`）：
     * 选一个插件 / 技能 / MCP，把它的使用说明作为文本附件塞进待发列表，让 AI 知道怎么用。
     * 不是跳包管理；插件会先按 Operit 的行为自动启用。
     */
    fun attachPackage(choice: PackageChoice) {
        if (choice.kind == PackageKind.PLUGIN) onEnablePlugin(choice.id)
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                when (choice.kind) {
                    PackageKind.PLUGIN -> {
                        val record = plugins.firstOrNull { it.id == choice.id }
                        if (record == null) "插件已不在本地：${choice.id}" else pluginReference(record)
                    }
                    PackageKind.SKILL -> {
                        val skill = skills.firstOrNull { it.path == choice.id }
                        if (skill == null) "技能已不在本地：${choice.id}" else skillReference(skill)
                    }
                    PackageKind.MCP -> {
                        val server = mcpServers.firstOrNull { it.id == choice.id }
                        if (server == null) "MCP 配置已不存在：${choice.id}" else mcpReference(server)
                    }
                }
            }
            val safe = choice.id.replace(Regex("[^A-Za-z0-9_.-]"), "-").take(40)
            val name = "package-$safe.txt"
            if (ChatMirror.attachText(name, text)) {
                Toast.makeText(context, "已引用${choice.kind.label}：${choice.title}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "现在不能加附件：这一轮正在生成，或会话是只读的", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 「记忆」附件（M56）：打开真实记忆库选择器，勾选记忆后作为文本附件引用给 AI。
     * 替代 M55 的临时方案（把当前会话最近 40 条压成附件）。
     */
    fun attachMemories(memories: List<xin.dshaa.nativeapp.memory.Memory>) {
        if (memories.isEmpty()) {
            Toast.makeText(context, "没有选择记忆", Toast.LENGTH_SHORT).show()
            return
        }
        val body = buildMemoryReference(memories)
        val name = "memory-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
        if (ChatMirror.attachText(name, body)) {
            Toast.makeText(context, "已引用 ${memories.size} 条记忆", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "现在不能加附件：这一轮正在生成，或会话是只读的", Toast.LENGTH_LONG).show()
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(c.background)
            .navigationBarsPadding()
            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
    ) {
        // ---- 引用（长按消息 → 引用） ----
        replyTo?.let { target ->
            val preview = target.content.filter { it.type == xin.dshaa.core.model.BlockType.TEXT }
                .joinToString(" ") { it.text }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.surfaceAlt)
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(3.dp).height(26.dp).clip(CircleShape).background(c.brand))
                Spacer(Modifier.width(8.dp))
                Text(
                    preview.take(90),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.size(26.dp).clip(CircleShape).clickable { onClearReply() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "取消引用", tint = c.faint, modifier = Modifier.size(15.dp))
                }
            }
        }

        // ---- 待发图片（+ 追加按钮） ----
        if (state.pendingImages.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.pendingImages) { pending ->
                    PendingImageChip(pending, onRemove = { onRemovePendingImage(pending.ref.attachmentId) })
                }
                item {
                    Box(
                        Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.surfaceAlt)
                            .clickable { attachSheet = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Add, "继续添加", tint = c.sub, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // ---- 待发文件（文本/代码） ----
        if (state.pendingFiles.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.pendingFiles) { file ->
                    PendingFileChip(file, onRemove = { onRemovePendingFile(file.name) })
                }
            }
        }

        // ---- 附件/能力报错（旧版只写进 state，界面从没显示过） ----
        state.attachError?.let { message ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.dangerSoft)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(message, style = MaterialTheme.typography.labelSmall, color = c.danger, modifier = Modifier.weight(1f))
            }
        }

        // ---- 本轮用量（上游 DSH 输入条下方那行 stats 的移动端精简版：有数据才显示） ----
        state.usageText?.let { usage ->
            Text(
                usage,
                style = MaterialTheme.typography.labelSmall,
                color = c.faint,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        // ---- 卡片本体（Operit agent-input-card：先输入，再控件行） ----
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = c.surface,
            border = BorderStroke(1.dp, c.line),
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 6.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicTextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp)
                            .padding(vertical = 6.dp)
                            .focusRequester(inputFocus),
                        textStyle = TextStyle(color = c.text, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                        cursorBrush = SolidColor(c.brand),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (canSend) onSubmit() }),
                        maxLines = 8,
                        decorationBox = { inner ->
                            if (input.text.isEmpty()) {
                                Text(
                                    if (state.streaming) "生成中，可继续输入…" else "发消息…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = c.faint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        },
                    )
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).clickable { fullscreen = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.OpenInFull, "全屏编辑", tint = c.faint, modifier = Modifier.size(17.dp))
                    }
                }

                Spacer(Modifier.height(4.dp))

                // ---- 控件行：模型 pill → 弹簧 → 齿轮 / ＋ / 发送圆钮 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier
                            .widthIn(max = 190.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.surfaceAlt)
                            .border(1.dp, c.line, RoundedCornerShape(12.dp))
                            .clickable { modelSheet = true }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            model,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = c.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Rounded.KeyboardArrowDown, "模型设置", tint = c.faint, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    ComposerIconButton(
                        icon = Icons.Rounded.Tune,
                        label = "输入设置",
                        active = planOn,
                        onClick = { settingsSheet = true },
                    )
                    Spacer(Modifier.width(2.dp))
                    ComposerIconButton(
                        icon = Icons.Rounded.Add,
                        label = "附件",
                        onClick = { attachSheet = true },
                    )
                    Spacer(Modifier.width(4.dp))
                    SendOrb(
                        streaming = state.streaming,
                        enabled = canSend,
                        onClick = { if (state.streaming) onStop() else if (canSend) onSubmit() },
                    )
                }
            }
        }
    }

    if (attachSheet) {
        ModalSheet(onDismiss = { attachSheet = false }) {
            Text("添加", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.text)
            if (state.streaming) {
                Text(
                    "生成中也能选图，会排在下一条消息里。",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
            } else {
                Spacer(Modifier.height(12.dp))
            }
            // 顺序照 Operit `AttachmentSelectorPopupPanel`：
            // 照片 / 拍照 / 记忆 / 文件 / 屏幕内容 / 当前通知 / 当前位置 / 包
            SheetActionRow(Icons.Rounded.Image, "照片", "从系统相册挑 1–9 张") {
                attachSheet = false
                onOpenGallery()
            }
            SheetActionRow(Icons.Rounded.PhotoCamera, "拍照", "用系统相机拍一张") {
                attachSheet = false
                onTakePhoto()
            }
            SheetActionRow(Icons.Rounded.Memory, "记忆", "从记忆库选几条引用给 AI") {
                attachSheet = false
                memoryPicker = true
            }
            SheetActionRow(Icons.Rounded.Description, "文件", "文本 / 代码 / 日志（任意后缀可选）") {
                attachSheet = false
                onOpenFilePicker()
            }
            SheetActionRow(Icons.Rounded.ScreenshotMonitor, "屏幕内容", "需要无障碍服务（Android 11+）") {
                attachSheet = false
                onCaptureScreenshot()
            }
            SheetActionRow(Icons.Rounded.Notifications, "当前通知", "最近 10 条通知（需要通知使用权）") {
                attachSheet = false
                if (!AttachmentCapture.notificationsEnabled(context)) {
                    Toast.makeText(context, "先去系统设置允许「鲸歌」读取通知，再回来点一次", Toast.LENGTH_LONG).show()
                    AttachmentCapture.openNotificationAccessSettings(context)
                } else {
                    val outcome = AttachmentCapture.attachNotifications(context)
                    Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
                    if (!outcome.ok && outcome.needsGrant) {
                        AttachmentCapture.openNotificationAccessSettings(context)
                    }
                }
            }
            SheetActionRow(Icons.Rounded.LocationOn, "当前位置", "取一次定位（需要定位权限）") {
                attachSheet = false
                if (!LocationCapture.start(context)) {
                    Toast.makeText(context, "无法打开定位授权页", Toast.LENGTH_SHORT).show()
                }
            }
            SheetActionRow(Icons.Rounded.Widgets, "包", "选择插件 / 技能 / MCP，把用法引用给 AI") {
                attachSheet = false
                packagePicker = true
            }
        }
    }

    if (settingsSheet) {
        // M60：按 Operit `AgentExtraSettingsPopup`（AgentChatInputSection.kt:2289-2334）改成
        // 贴输入栏右下角的 300dp 浮层卡片（不是底部弹层）；分组行点开原地展开。
        val thinkingOn = reasoningEffort != "off"
        val effortLabel = when (reasoningEffort) {
            "low" -> "低"
            "max" -> "最高"
            "off" -> "关"
            else -> "高"
        }
        val enabledPlugins = enabledPluginCount
        ComposerMenuPopup(visible = true, onDismiss = { settingsSheet = false }) {
            // 思考：Operit `AgentThinkingSettingsItem` 是可折叠分组；默认展开。
            var thinkingMenuOpen by remember { mutableStateOf(true) }
            val thinkingValue = if (thinkingOn) effortLabel else "关"
            ComposerMenuGroupHeader(
                title = "思考",
                value = thinkingValue,
                expanded = thinkingMenuOpen,
                icon = Icons.Rounded.AutoAwesome,
                onToggle = { thinkingMenuOpen = !thinkingMenuOpen },
            )
            ComposerMenuExpansion(thinkingMenuOpen) {
                ComposerMenuGroupBody {
                    SheetToggleRow(
                        title = "思考模式",
                        subtitle = if (thinkingOn) {
                            "开启：请求里带 thinking，当前 $effortLabel"
                        } else {
                            "关闭：把 thinking 设为 disabled，不发 output_config"
                        },
                        checked = thinkingOn,
                        onCheckedChange = { on ->
                            onSetReasoningEffort(if (on) reasoningEffort.takeIf { it != "off" } ?: "high" else "off")
                        },
                    )
                    if (thinkingOn) {
                        val efforts = listOf("low", "high", "max")
                        // 注意：low 的 index 是 0，不能 coerceAtLeast(1)——旧代码把「低」顶成「高」，
                        // 导致滑杆拉到最左又弹回去（用户 2026-11-10 反馈「D 档拉不到」）。
                        val index = efforts.indexOf(reasoningEffort).takeIf { it >= 0 } ?: 1
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("思考程度", style = MaterialTheme.typography.bodySmall, color = c.text)
                                Spacer(Modifier.weight(1f))
                                Text(effortLabel, style = MaterialTheme.typography.labelSmall, color = c.brandBright)
                            }
                            Slider(
                                value = index.toFloat(),
                                onValueChange = { v -> onSetReasoningEffort(efforts[v.toInt().coerceIn(0, 2)]) },
                                valueRange = 0f..2f,
                                steps = 1,
                                colors = SliderDefaults.colors(
                                    thumbColor = c.brand,
                                    activeTrackColor = c.brand,
                                    inactiveTrackColor = c.line,
                                ),
                                modifier = Modifier.fillMaxWidth().height(30.dp),
                            )
                            Row(Modifier.fillMaxWidth()) {
                                Text("低", style = MaterialTheme.typography.labelSmall, color = c.faint)
                                Spacer(Modifier.weight(1f))
                                Text("高", style = MaterialTheme.typography.labelSmall, color = c.faint)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "最高",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = c.faint,
                                    textAlign = TextAlign.End,
                                )
                            }
                        }
                    }
                }
            }
            SheetToggleRow(
                title = "Max模式",
                subtitle = if (maxMode) {
                    "超大上下文：关闭自动压缩，整段窗口直接进请求"
                } else {
                    "按模型窗口自动压缩（模型配置页可改窗口大小）"
                },
                checked = maxMode,
                onCheckedChange = onSetMaxMode,
            )

            // 记忆 / 工具 / 行为 / 插件：照 Operit `AgentExtraSettingsPopup` 做成可折叠分组
            // （标题 + 值 + 上下箭头，点开原地展开；不是再弹一个底部面板）。
            var memoryMenuOpen by remember { mutableStateOf(false) }
            var toolsMenuOpen by remember { mutableStateOf(false) }
            var behaviorMenuOpen by remember { mutableStateOf(false) }
            var pluginsMenuOpen by remember { mutableStateOf(false) }

            SheetValueRow(Icons.Rounded.DataObject, "模型", model) { modelSheet = true }

            ComposerMenuGroupHeader(
                title = "记忆",
                value = memorySpaceName,
                expanded = memoryMenuOpen,
                icon = Icons.Rounded.Memory,
                onToggle = { memoryMenuOpen = !memoryMenuOpen },
            )
            ComposerMenuExpansion(memoryMenuOpen) {
                ComposerMenuGroupBody {
                    ComposerMenuRow(
                        title = memorySpaceName,
                        subtitle = "当前记忆空间（多空间在记忆库二期）",
                        value = "✓",
                        onClick = {
                            settingsSheet = false
                            onOpenMemoryLibrary()
                        },
                    )
                    ComposerMenuRow(
                        title = "打开记忆库",
                        subtitle = "搜索 / 图谱 / 文件夹 / 编辑",
                        onClick = {
                            settingsSheet = false
                            onOpenMemoryLibrary()
                        },
                    )
                }
            }

            ComposerMenuGroupHeader(
                title = "工具",
                value = if (approvalMode == "auto") "允许" else "询问",
                expanded = toolsMenuOpen,
                icon = Icons.Rounded.Rule,
                onToggle = { toolsMenuOpen = !toolsMenuOpen },
            )
            ComposerMenuExpansion(toolsMenuOpen) {
                ComposerMenuGroupBody {
                    PermissionChoiceRow(
                        title = "每次询问",
                        subtitle = "写类工具执行前弹审批卡",
                        selected = approvalMode != "auto",
                    ) { onSetApprovalMode("ask") }
                    PermissionChoiceRow(
                        title = "自动允许",
                        subtitle = "不再打断，写类工具直接执行",
                        selected = approvalMode == "auto",
                    ) { onSetApprovalMode("auto") }
                }
            }

            ComposerMenuGroupHeader(
                title = "行为",
                value = buildString {
                    append("流式")
                    if (expandThinking) append(" · 思考展开") else append(" · 思考折叠")
                },
                expanded = behaviorMenuOpen,
                icon = Icons.Rounded.Tune,
                onToggle = { behaviorMenuOpen = !behaviorMenuOpen },
            )
            ComposerMenuExpansion(behaviorMenuOpen) {
                ComposerMenuGroupBody {
                    SheetToggleRow(
                        title = "默认展开思考过程",
                        subtitle = if (expandThinking) "回合结束后思考块保持展开" else "回合结束后自动折叠",
                        checked = expandThinking,
                        onCheckedChange = onSetExpandThinking,
                    )
                    SheetToggleRow(
                        title = "时间上下文",
                        subtitle = if (timeContext) "每个 step 起点带上当前时间和间隔" else "请求体不带时间注入",
                        checked = timeContext,
                        onCheckedChange = onSetTimeContext,
                    )
                    ComposerMenuRow(
                        title = "流式输出",
                        subtitle = "客户端固定逐字渲染；Operit 的「非流式」还没做",
                        value = "开",
                    )
                }
            }

            ComposerMenuGroupHeader(
                title = "插件",
                value = "$enabledPlugins/${plugins.size}",
                expanded = pluginsMenuOpen,
                icon = Icons.Rounded.Widgets,
                onToggle = { pluginsMenuOpen = !pluginsMenuOpen },
            )
            ComposerMenuExpansion(pluginsMenuOpen) {
                ComposerMenuGroupBody {
                    ComposerMenuRow(
                        title = "打开插件页",
                        subtitle = "启用 / 停用、导入、详情、命令列表",
                        value = "$enabledPlugins/${plugins.size}",
                        onClick = {
                            settingsSheet = false
                            onOpenPlugins()
                        },
                    )
                }
            }

            SheetSectionLabel("其他")
            SheetToggleRow(
                title = "计划模式",
                subtitle = when {
                    planModePending == true -> "下一步生效：先给方案再执行"
                    planModePending == false -> "下一步生效：恢复直接执行"
                    planModeActive -> "已开启：模型先出方案，等你批准"
                    else -> "开启后模型先给方案，批准后再动手"
                },
                checked = planOn,
                onCheckedChange = { onSetPlanMode(it) },
            )
            SheetActionRow(
                Icons.Rounded.Flag,
                "目标",
                if (state.goal != null) state.goal?.goal?.objective.orEmpty() else "还没有长期目标",
                enabled = state.goal != null,
            ) {
                settingsSheet = false
                onOpenGoal()
            }
            SheetActionRow(Icons.Rounded.Notifications, "提醒", "查看/删除这个会话的定时提醒") {
                settingsSheet = false
                onOpenSchedules()
            }
            SheetActionRow(
                icon = Icons.Rounded.FileDownload,
                title = "导出 Markdown",
                subtitle = "把看得见的内容写成文件",
                enabled = state.messages.isNotEmpty(),
            ) {
                settingsSheet = false
                onExport()
            }
        }
    }

    if (behaviorSheet) {
        ComposerMenuPopup(visible = true, onDismiss = { behaviorSheet = false }) {
            Text("行为", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.text)
            Spacer(Modifier.height(4.dp))
            Text(
                "流式输出固定开启（逐字渲染、随时可停）；Operit 的「非流式」本轮没做。",
                style = MaterialTheme.typography.labelSmall,
                color = c.faint,
            )
            Spacer(Modifier.height(8.dp))
            SheetToggleRow(
                title = "默认展开思考过程",
                subtitle = if (expandThinking) "回合结束后思考块保持展开" else "回合结束后自动折叠",
                checked = expandThinking,
                onCheckedChange = onSetExpandThinking,
            )
            SheetToggleRow(
                title = "时间上下文",
                subtitle = if (timeContext) "每个 step 起点带上当前时间和间隔" else "请求体不带时间注入",
                checked = timeContext,
                onCheckedChange = onSetTimeContext,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "和 设置 → 行为 是同一份值，改完立刻生效。",
                style = MaterialTheme.typography.labelSmall,
                color = c.faint,
            )
        }
    }

    if (modelSheet) {
        ComposerMenuPopup(visible = true, onDismiss = { modelSheet = false }) {
            // M73 精修：浮层里所有行统一 10dp 内边距 + 42dp 行高 + 一致的圆角/分隔线，
            // 不再是「贴边的大色块 + 忽高忽低的行」。
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                // 当前模型
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.brandSoft.copy(alpha = if (c.isDark) 0.5f else 0.65f))
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(c.brand.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.DataObject, null, tint = c.brand, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            model,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = c.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("本会话使用的模型", style = MaterialTheme.typography.labelSmall, color = c.faint)
                    }
                    Surface(shape = RoundedCornerShape(999.dp), color = c.brand.copy(alpha = 0.16f)) {
                        Text(
                            "当前",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.brandBright,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }

                // 已勾选的模型：点一下直接切（settings.model + 当前会话顶栏/输入条）。
                // M74：切完**保留浮层**（用户 23:20：以前一点整个浮层就消失，没法连着切第二个）。
                val alternatives = enabledModels.filter { it != model }
                if (alternatives.isNotEmpty()) {
                    MenuSectionLabel("已启用模型", alternatives.size)
                    alternatives.forEach { candidate ->
                        MenuRow(
                            icon = Icons.Rounded.SwapHoriz,
                            title = candidate,
                            subtitle = "点一下切换本会话模型（浮层保留）",
                            trailing = null,
                        ) {
                            onSwitchModel(candidate)
                        }
                    }
                } else {
                    MenuSectionLabel("已启用模型", 0)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "只有当前这一个模型。去「打开模型配置」拉取列表并勾选更多，勾过的才会出现在这里。",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.faint,
                        )
                    }
                }

                MenuDivider()
                MenuSectionLabel("本会话选项")
                SheetToggleRow(
                    title = "模型支持识图",
                    subtitle = if (visionEnabled) "图片直接发给模型" else "关闭后不发图、read_image 也会拒绝",
                    checked = visionEnabled,
                    onCheckedChange = onToggleVision,
                )
                SheetToggleRow(
                    title = "联网搜索",
                    subtitle = if (webSearchEnabled) "允许模型调用 web_search" else "请求里不提供 web_search",
                    checked = webSearchEnabled,
                    onCheckedChange = onToggleWebSearch,
                )
                MenuDivider()
                MenuRow(
                    icon = Icons.Rounded.Settings,
                    title = "打开模型配置",
                    subtitle = "端点 / 密钥 / 模型名 / 生成参数",
                    trailing = { Icon(Icons.Rounded.ChevronRight, null, tint = c.faint, modifier = Modifier.size(18.dp)) },
                ) {
                    modelSheet = false
                    onOpenModelSettings()
                }
            }
        }
    }

    if (permissionSheet) {
        ModalSheet(onDismiss = { permissionSheet = false }) {
            Text("工具审批", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.text)
            Spacer(Modifier.height(4.dp))
            Text(
                "写类工具（write / edit / bash…）执行前怎么处理；只读工具永远直接执行。",
                style = MaterialTheme.typography.labelSmall,
                color = c.faint,
            )
            Spacer(Modifier.height(10.dp))
            PermissionChoiceRow(
                title = "每次询问",
                subtitle = "弹审批卡，等你点允许或拒绝",
                selected = approvalMode != "auto",
            ) {
                onSetApprovalMode("ask")
                permissionSheet = false
            }
            PermissionChoiceRow(
                title = "自动允许",
                subtitle = "不再打断，写类工具直接执行",
                selected = approvalMode == "auto",
            ) {
                onSetApprovalMode("auto")
                permissionSheet = false
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "随时用 /permission auto|ask 直接切；设置 → 行为里是同一个开关。",
                style = MaterialTheme.typography.labelSmall,
                color = c.faint,
            )
        }
    }

    // `/help`（M41）：命令表与 `/` 菜单、派发共用 DSH_COMMANDS，点一行把用法插进输入框。
    if (helpSheet) {
        ModalSheet(onDismiss = { helpSheet = false }) {
            Text("命令", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.text)
            Spacer(Modifier.height(4.dp))
            Text(
                "斜杠命令只作用于客户端或会话，不会变成模型消息。",
                style = MaterialTheme.typography.labelSmall,
                color = c.faint,
            )
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                DSH_COMMANDS.forEach { command ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                insertCommandText(command.insertText())
                                helpSheet = false
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                command.display(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = c.text,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(command.description, style = MaterialTheme.typography.labelSmall, color = c.faint)
                        }
                    }
                }
                // M58：插件用 ctx.commands.register 注册的命令也列出来（P5 执行链路已接）。
                val pluginCommands = plugins.filter { it.enabled }.flatMap { record ->
                    record.commands.map { record to it }
                }
                if (pluginCommands.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "插件命令",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = c.faint,
                    )
                    pluginCommands.forEach { (record, pluginCommand) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    insertCommandText("/${pluginCommand.name} ")
                                    helpSheet = false
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "/${pluginCommand.name}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = c.text,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    record.name + if (pluginCommand.description.isBlank()) "" else "：${pluginCommand.description}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = c.faint,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "点一行插入输入框；技能用 /名字 调用，输入 / 可打开命令菜单。",
                style = MaterialTheme.typography.labelSmall,
                color = c.faint,
            )
        }
    }

    if (packagePicker) {
        PackageSelectorDialog(
            plugins = plugins,
            skills = skills,
            mcpServers = mcpServers,
            onDismiss = { packagePicker = false },
            onSelect = { choice ->
                packagePicker = false
                attachPackage(choice)
            },
        )
    }

    if (memoryPicker) {
        MemoryPickerDialog(
            onDismiss = { memoryPicker = false },
            onSelect = { memories ->
                memoryPicker = false
                attachMemories(memories)
            },
        )
    }

    if (fullscreen) {
        FullscreenEditor(
            input = input,
            onInputChange = onInputChange,
            onDismiss = { fullscreen = false },
            placeholder = if (state.streaming) "生成中，可继续输入…" else "发消息…",
        )
    }
}

/** 输入条上的小圆钮（Operit `.agent-icon-button`：36dp，激活时品牌色）。 */
@Composable
private fun ComposerIconButton(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val c = DshTheme2.colors
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (active) c.brand else c.sub, modifier = Modifier.size(21.dp))
    }
}

/**
 * 发送圆钮：Operit `.agent-action-orb`（36dp）+ 外面 40dp 的进度环。
 * 生成中转一圈，比「按钮变个 Stop 图标」更容易看出「还在跑」。
 */
@Composable
private fun SendOrb(streaming: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = DshTheme2.colors
    val active = streaming || enabled
    val transition = rememberInfiniteTransition(label = "send-orb")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "send-orb-angle",
    )
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        if (streaming) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 2.dp.toPx()
                val d = size.minDimension - stroke
                val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
                drawArc(
                    color = c.line2,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(d, d),
                    style = Stroke(width = stroke),
                )
                drawArc(
                    color = c.brand,
                    startAngle = angle - 90f,
                    sweepAngle = 96f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(d, d),
                    style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            }
        }
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (active) Brush.linearGradient(listOf(c.brandBright, c.brand))
                    else Brush.linearGradient(listOf(c.line2, c.line2)),
                )
                .clickable(enabled = active, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (streaming) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                if (streaming) "停止" else "发送",
                tint = if (active) Color.White else c.faint,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/** 面板骨架：圆角 22dp 的底部卡片（Operit 的 agent popup 也是同一层）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModalSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = DshTheme2.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            content()
        }
    }
}

/** 浮层里的小节标题（「最近使用」「本会话选项」）：左侧一条竖线 + 可选数量。 */
@Composable
private fun MenuSectionLabel(title: String, count: Int? = null) {
    val c = DshTheme2.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 2.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 2.dp, height = 11.dp).clip(RoundedCornerShape(2.dp)).background(c.brand.copy(alpha = 0.55f)))
        Spacer(Modifier.width(7.dp))
        Text(title, style = MaterialTheme.typography.labelSmall, color = c.faint, fontWeight = FontWeight.Medium)
        if (count != null && count > 1) {
            Spacer(Modifier.width(6.dp))
            Text("$count", style = MaterialTheme.typography.labelSmall, color = c.faint.copy(alpha = 0.7f))
        }
    }
}

/** 浮层里的一条可点行：图标 + 标题/说明 + 尾部内容，统一 42dp 高、10dp 圆角。 */
@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = DshTheme2.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.sub, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = c.faint, maxLines = 1)
            }
        }
        trailing?.invoke()
    }
}

/** 浮层里的分组分隔线（左右各缩进 2dp，避免和卡片圆角打架）。 */
@Composable
private fun MenuDivider() {
    val c = DshTheme2.colors
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 2.dp, end = 2.dp, top = 8.dp, bottom = 2.dp)
            .height(1.dp)
            .background(c.line.copy(alpha = 0.55f)),
    )
}

/** 面板里的一行开关（标题 + 说明 + Switch）。 */
@Composable
private fun SheetToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val c = DshTheme2.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = c.text)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = c.faint)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.85f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = c.brand,
                checkedBorderColor = c.brand,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = c.line,
                uncheckedBorderColor = c.line,
            ),
        )
    }
}

/** 面板里的一行动作（图标 + 标题/说明 + 右箭头）。 */
@Composable
private fun SheetActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = DshTheme2.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (enabled) c.brand else c.faint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) c.text else c.faint,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            null,
            tint = if (enabled) c.faint else c.line2,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 面板里的小节标题（Operit 设置菜单的分组标签）。 */
@Composable
private fun SheetSectionLabel(text: String) {
    val c = DshTheme2.colors
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = c.faint,
        modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 2.dp),
    )
}

/** 面板里的「值 + 箭头」行：左边标题（可带图标），右边当前值。 */
@Composable
private fun SheetValueRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    val c = DshTheme2.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.brand, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = c.text,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = c.sub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp),
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            null,
            tint = c.faint,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 思考程度的三档胶囊（low/high/max，上游 `reasoningEffort` 的原值）。 */
@Composable
private fun EffortPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = DshTheme2.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) c.brand else c.surfaceAlt)
            .border(1.dp, if (selected) c.brand else c.line, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.White else c.sub,
        )
    }
}

/** 审批方式面板里的一行（单选：选中态用品牌色勾）。 */
@Composable
private fun PermissionChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = DshTheme2.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.brand.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) c.brandBright else c.text,
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = c.faint)
        }
        if (selected) {
            Icon(Icons.Rounded.Check, "当前档位", tint = c.brand, modifier = Modifier.size(18.dp))
        }
    }
}

/** 全屏编辑（Operit 的 `.agent-input-fullscreen`）：长文/多行不用挤在 8 行里改。 */
@Composable
private fun FullscreenEditor(
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onDismiss: () -> Unit,
    placeholder: String,
) {
    val c = DshTheme2.colors
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = c.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("编辑消息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.text)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("完成", color = c.brand) }
                }
                Spacer(Modifier.height(8.dp))
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(c.surface)
                        .padding(14.dp)
                        .focusRequester(focus),
                    textStyle = TextStyle(color = c.text, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                    cursorBrush = SolidColor(c.brand),
                    decorationBox = { inner ->
                        if (input.text.isEmpty()) {
                            Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = c.faint)
                        }
                        inner()
                    },
                )
            }
        }
    }
}

/** 待发文本文件的输入条小卡：图标 + 文件名 + 字数 + 移除。 */
@Composable
internal fun PendingFileChip(file: xin.dshaa.nativeapp.chat.PendingFile, onRemove: () -> Unit) {    val c = DshTheme2.colors
    Row(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.surfaceAlt)
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Description, null, tint = c.brand, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            file.name,
            style = MaterialTheme.typography.labelMedium,
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (file.isReference) formatBytes(file.size) else "${file.chars}",
            style = MaterialTheme.typography.labelSmall,
            color = c.faint,
        )
        Box(Modifier.size(26.dp).clip(CircleShape).clickable { onRemove() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "移除文件", tint = c.faint, modifier = Modifier.size(13.dp))
        }
    }
}

/** 附件小卡上的大小（路径引用文件没有正文字数）。 */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

// ---- 包引用（照 Operit `PackageSelectorDialog`） ----

private enum class PackageKind(val label: String) {
    PLUGIN("插件"),
    SKILL("技能"),
    MCP("MCP"),
}

private data class PackageChoice(
    val id: String,
    val title: String,
    val description: String,
    val kind: PackageKind,
)

/**
 * 输入条「包」的选择弹层：列出插件 / 技能 / MCP，选中后把使用说明作为附件引用给 AI。
 * 布局照 Operit：标题 + 搜索框 + 行（AutoAwesome 图标 + 标题 + 「类型 · 描述」）+ 取消。
 */
@Composable
private fun PackageSelectorDialog(
    plugins: List<PluginRecord>,
    skills: List<SkillSummary>,
    mcpServers: List<McpServer>,
    onDismiss: () -> Unit,
    onSelect: (PackageChoice) -> Unit,
) {
    val c = DshTheme2.colors
    var query by remember { mutableStateOf("") }
    val options = remember(plugins, skills, mcpServers) {
        buildList {
            plugins.forEach { record ->
                add(PackageChoice(record.id, record.name, record.description, PackageKind.PLUGIN))
            }
            skills.forEach { skill ->
                add(PackageChoice(skill.path, skill.name, skill.description, PackageKind.SKILL))
            }
            mcpServers.forEach { server ->
                add(PackageChoice(server.id, server.name, server.summary, PackageKind.MCP))
            }
        }
    }
    val filtered = remember(options, query) {
        val text = query.trim()
        if (text.isEmpty()) options
        else options.filter {
            it.title.contains(text, true) || it.description.contains(text, true) || it.kind.label.contains(text, true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择包", color = c.text) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
            ) {
                if (options.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("还没有可引用的包 / 技能 / MCP", style = MaterialTheme.typography.bodyMedium, color = c.sub)
                    }
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("搜索包 / 技能 / MCP", style = MaterialTheme.typography.bodySmall, color = c.faint) },
                        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = c.faint, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                Box(
                                    Modifier.size(32.dp).clip(CircleShape).clickable { query = "" },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.Close, "清空", tint = c.faint, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        if (filtered.isEmpty()) {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("没有找到匹配的包", style = MaterialTheme.typography.bodyMedium, color = c.sub)
                            }
                        }
                        filtered.forEach { option ->
                            Surface(
                                onClick = { onSelect(option) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = androidx.compose.ui.graphics.Color.Transparent,
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Rounded.AutoAwesome,
                                        null,
                                        tint = c.brand,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            option.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = c.text,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            buildString {
                                                append(option.kind.label)
                                                if (option.description.isNotBlank()) append(" · ${option.description}")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = c.sub,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = c.sub) }
        },
        containerColor = c.surface,
    )
}

/** 插件引用正文：名称/版本/描述 + 工具与参数 schema + 权限 + 调用方式。 */
private fun pluginReference(record: PluginRecord): String = buildString {
    appendLine("# 插件引用：${record.name} ${record.version}")
    appendLine(record.description.ifBlank { "（没有描述）" })
    appendLine()
    appendLine("状态：${if (record.enabled) "已启用" else "已停用"}")
    appendLine("入口：${record.main} · 格式：${record.format}")
    if (record.tools.isEmpty()) {
        appendLine("这个插件没有注册工具（可能只提供 hook / UI 扩展）。")
    } else {
        appendLine("已注册工具（已接入当前对话的工具表，可直接按名字调用）：")
        record.tools.forEach { tool ->
            appendLine("- $tool：${record.toolDescriptions[tool].orEmpty().ifBlank { "（没有描述）" }}")
            record.toolSchemas[tool]?.let { schema ->
                appendLine("  参数 JSON Schema：${schema.take(1_500)}")
            }
        }
    }
    if (record.permissions.isNotEmpty()) {
        appendLine("权限：${record.permissions.joinToString("、")}")
    }
    if (record.error != null) {
        appendLine("注意：最近一次试运行报错：${record.error}")
    }
    appendLine()
    appendLine("用法：按工具名调用；参数按上面的 JSON Schema 填。")
}

/** 技能引用正文：frontmatter 元信息 + SKILL.md 全文（读不到时退回摘要）。 */
private fun skillReference(skill: SkillSummary): String {
    val raw = runCatching { File(skill.path).readText(Charsets.UTF_8) }.getOrNull()
    return buildString {
        appendLine("# 技能引用：${skill.name}")
        appendLine("描述：${skill.description}")
        skill.whenToUse?.let { appendLine("何时使用：$it") }
        appendLine("来源：${skill.source} · 文件：${skill.path}")
        appendLine()
        if (raw.isNullOrBlank()) {
            appendLine("（读不到 SKILL.md 原文，按上面的描述使用该技能。）")
        } else {
            appendLine(raw.take(40_000))
        }
    }
}

/** MCP 引用正文：配置 JSON + 当前能力边界（还不能真正起会话）。 */
private fun mcpReference(server: McpServer): String = buildString {
    appendLine("# MCP 引用：${server.name}")
    appendLine("类型：${server.type}")
    appendLine("摘要：${server.summary}")
    appendLine()
    appendLine("配置 JSON：")
    appendLine(runCatching { JSONObject(server.raw).toString(2) }.getOrDefault(server.raw))
    appendLine()
    appendLine("说明：鲸歌当前只做 MCP 配置管理，MCP 会话与工具列表还没接入；这份配置先作为参考。")
}
