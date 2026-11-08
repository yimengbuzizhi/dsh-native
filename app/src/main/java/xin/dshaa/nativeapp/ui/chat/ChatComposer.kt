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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.ScreenshotMonitor
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import xin.dshaa.core.input.DSH_COMMANDS
import xin.dshaa.core.model.ChatMessage
import xin.dshaa.nativeapp.chat.ChatSessionState
import xin.dshaa.nativeapp.chat.CommandSheet
import xin.dshaa.ui.design.theme.DshTheme2

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
    recentModels: List<String> = emptyList(),
    /** 输入条模型下拉：直接切 settings.model + 当前会话显示。 */
    onSwitchModel: (String) -> Unit = {},
    /** 工具审批方式（settings.approvalMode）：`/permission` 面板与设置 → 行为同一份值。 */
    approvalMode: String = "ask",
    onSetApprovalMode: (String) -> Unit = {},
    inputFocus: FocusRequester,
) {
    val c = DshTheme2.colors
    var attachSheet by remember { mutableStateOf(false) }
    var settingsSheet by remember { mutableStateOf(false) }
    var modelSheet by remember { mutableStateOf(false) }
    var permissionSheet by remember { mutableStateOf(false) }
    var helpSheet by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    val planOn = planModePending ?: planModeActive
    val canSend = input.text.isNotBlank() || state.pendingImages.isNotEmpty() || state.pendingFiles.isNotEmpty()

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
            SheetActionRow(Icons.Rounded.Image, "相册图片", "从系统相册挑 1–9 张") {
                attachSheet = false
                onOpenGallery()
            }
            SheetActionRow(Icons.Rounded.PhotoCamera, "拍照", "用系统相机拍一张") {
                attachSheet = false
                onTakePhoto()
            }
            SheetActionRow(Icons.Rounded.ScreenshotMonitor, "截图当前屏幕", "需要无障碍服务（Android 11+）") {
                attachSheet = false
                onCaptureScreenshot()
            }
            SheetActionRow(Icons.Rounded.Description, "文件", "文本 / 代码 / 日志（PDF、Word 暂不支持解析）") {
                attachSheet = false
                onOpenFilePicker()
            }
        }
    }

    if (settingsSheet) {
        ModalSheet(onDismiss = { settingsSheet = false }) {
            Text("输入设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.text)
            Spacer(Modifier.height(8.dp))
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
            SheetActionRow(Icons.Rounded.Flag, "目标", if (state.goal != null) state.goal?.goal?.objective.orEmpty() else "还没有长期目标", enabled = state.goal != null) {
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

    if (modelSheet) {
        ModalSheet(onDismiss = { modelSheet = false }) {
            Text("模型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.text)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.brandSoft.copy(alpha = if (c.isDark) 0.55f else 0.7f))
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.DataObject, null, tint = c.brand, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(model, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.text)
                    Text("本会话使用的模型", style = MaterialTheme.typography.labelSmall, color = c.faint)
                }
                Text("当前", style = MaterialTheme.typography.labelSmall, color = c.brand)
            }
            // 最近用过的模型：点一下直接切（settings.model + 当前会话顶栏/输入条），不用进配置页
            if (recentModels.size > 1) {
                Spacer(Modifier.height(10.dp))
                Text("最近使用", style = MaterialTheme.typography.labelSmall, color = c.faint)
                Spacer(Modifier.height(2.dp))
                recentModels.forEach { candidate ->
                    val selected = candidate == model
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) c.brand.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable {
                                onSwitchModel(candidate)
                                modelSheet = false
                            }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            candidate,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) c.brandBright else c.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Icon(Icons.Rounded.Check, "当前模型", tint = c.brand, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
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
            SheetActionRow(
                icon = Icons.Rounded.Settings,
                title = "打开模型配置",
                subtitle = "端点 / 密钥 / 模型名 / 生成参数",
            ) {
                modelSheet = false
                onOpenModelSettings()
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
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "点一行插入输入框；技能用 /名字 调用，输入 / 可打开命令菜单。",
                style = MaterialTheme.typography.labelSmall,
                color = c.faint,
            )
        }
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
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = c.text)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = c.faint)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
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
internal fun PendingFileChip(file: xin.dshaa.nativeapp.chat.PendingFile, onRemove: () -> Unit) {
    val c = DshTheme2.colors
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
        Text("${file.chars}", style = MaterialTheme.typography.labelSmall, color = c.faint)
        Box(Modifier.size(26.dp).clip(CircleShape).clickable { onRemove() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "移除文件", tint = c.faint, modifier = Modifier.size(13.dp))
        }
    }
}
