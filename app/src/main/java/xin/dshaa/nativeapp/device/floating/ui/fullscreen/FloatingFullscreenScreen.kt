package xin.dshaa.nativeapp.device.floating.ui.fullscreen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.ChatMessage
import xin.dshaa.core.model.MessageRole
import xin.dshaa.ui.shellapi.chat.ATTACHMENT_REFERENCE_HINT
import xin.dshaa.nativeapp.chat.ChatMirror
import xin.dshaa.ui.design.chat.QuotedAttachment
import xin.dshaa.ui.design.chat.consumeInnerScroll
import xin.dshaa.ui.design.chat.parseQuotedAttachment
import xin.dshaa.nativeapp.device.attach.AttachmentCapture
import xin.dshaa.nativeapp.device.attach.LocationCapture
import xin.dshaa.nativeapp.device.floating.FloatingMode
import xin.dshaa.nativeapp.device.floating.FloatingSpeechManager
import xin.dshaa.nativeapp.device.floating.FloatingWindowState
import xin.dshaa.nativeapp.device.floating.isInternalNotice
import xin.dshaa.nativeapp.device.floating.ui.window.FloatingPromptQueue
import xin.dshaa.ui.design.component.MarkdownText
import xin.dshaa.ui.design.theme.DshTheme
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * FULLSCREEN 模式（docs/76 §九 F1-F36）。
 *
 * 结构与 Operit 对齐：全屏暗底 + 顶部新对话/回窗口/缩成语音球/关闭 + 消息区 +
 * 语音波形/头像 + 底部 chip 与胶囊输入 + 按住说话。
 * 语音后端用系统 SpeechRecognizer/TextToSpeech，明确差异见 docs/76 §十三.2（无唤醒词）。
 */
@Composable
fun FloatingFullscreenScreen(
    state: FloatingWindowState,
    onClose: () -> Unit,
    onModeChange: (FloatingMode) -> Unit,
    onStartOcr: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val speech = remember { FloatingSpeechManager(context) }
    val chat = ChatMirror.state
    var voiceTarget by remember { mutableStateOf<xin.dshaa.ui.shellapi.chat.ChatSessionState?>(null) }
    var waveActive by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("按住说话 · 上滑取消") }
    var preview by remember { mutableStateOf("") }
    var input by remember(chat) { mutableStateOf("") }
    var ttsMuted by remember { mutableStateOf(false) }
    var attachScreen by remember { mutableStateOf(false) }
    var attachNotifications by remember { mutableStateOf(false) }
    var lastSpokenId by remember { mutableStateOf<String?>(null) }

    // 按住说话（Operit 全屏语音的手势）：按下开始识别，松开发送，上滑取消。
    fun startVoice() {
        if (recording) return
        voiceTarget = ChatMirror.state
        preview = ""
        waveActive = true
        recording = true
        speech.startListening()
    }

    fun finishVoice(cancel: Boolean) {
        if (!recording) return
        recording = false
        if (cancel) {
            voiceTarget = null
            speech.stopListening(true)
            status = "已取消"
        } else {
            speech.stopListening(false)
        }
    }

    val canSend = chat != null && !chat.readOnly && !chat.stopping &&
        (input.isNotBlank() || chat.pendingImages.isNotEmpty() || chat.pendingFiles.isNotEmpty())
    val sendMode = ChatMirror.sendWhileRunningMode
    val sendLabel = if (chat?.streaming == true) {
        if (sendMode == "steer") "插话发送" else "排队发送"
    } else "发送"
    // 内部注入消息（time-context/goal/tool-jobs…）不按 user 气泡显示，见 FloatingMessageFilter
    val messages = chat?.messages.orEmpty().filterNot { it.isInternalNotice() }

    DisposableEffect(Unit) {
        speech.initialize()
        speech.onPartial = {
            preview = it
            status = "在听…"
        }
        speech.onFinal = { text ->
            preview = ""
            recording = false
            val target = voiceTarget
            voiceTarget = null
            if (target == null || ChatMirror.state !== target) {
                processing = false
                status = "会话已切换，请在目标会话重新发送"
            } else if (ChatMirror.send(text)) {
                processing = true
                status = if (target.streaming) "已接受，等待处理" else "已识别，发送中…"
            } else {
                processing = false
                status = "消息未发送，请检查当前会话"
            }
        }
        speech.onError = {
            voiceTarget = null
            recording = false
            processing = false
            preview = ""
            status = it
        }
        speech.onState = { status = it }
        onDispose {
            speech.release()
        }
    }

    LaunchedEffect(chat?.messages?.lastOrNull()?.id) {
        val last = chat?.messages?.lastOrNull() ?: return@LaunchedEffect
        if (last.role == MessageRole.ASSISTANT && !last.streaming && last.id != lastSpokenId) {
            lastSpokenId = last.id
            if (!ttsMuted) {
                val text = last.content.filter { it.type == BlockType.TEXT }
                    .joinToString("\n") { it.text }
                    .trim()
                if (text.isNotBlank()) speech.speak(text.take(2_000))
            }
            // AI 回合结束且还停在语音态：等 TTS 读完再自动恢复录音（Operit F31 的自动再听）。
            if (waveActive) {
                while (speech.isSpeaking()) delay(200)
                delay(300)
                if (waveActive && !recording) startVoice()
            }
        }
    }

    // Operit 全屏是 0.22 scrim + 系统 48dp blur；不能画成接近不透明（会看不到后面的 App）。
    // 2026-11-12（用户：全屏里字泛白、看不清）：Operit 那套依赖系统模糊，但这台 ColorOS 上
    // `isCrossWindowBlurEnabled` 报 true、实际并没有模糊（截图/肉眼都是透的），0.22 遮罩等于没遮。
    // 所以不再看模糊开关，固定 0.69 暗底——仍能隐约看到后面的 App，但白字和代码块能读清。
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB00B0F17)),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 30.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { ChatMirror.newChat() }) {
                    Icon(Icons.Default.AddComment, "新对话", tint = Color.White)
                }
                Text(
                    chat?.title?.ifBlank { "鲸歌" } ?: "鲸歌",
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { onModeChange(FloatingMode.WINDOW) }) {
                    Icon(Icons.Default.KeyboardArrowDown, "回窗口", tint = Color.White)
                }
                IconButton(onClick = { onModeChange(FloatingMode.VOICE_BALL) }) {
                    Icon(Icons.Default.Phone, "缩成语音球", tint = Color.White)
                }
                IconButton(onClick = {
                    speech.stopListening(true)
                    speech.stopSpeaking()
                    onClose()
                }) {
                    Icon(Icons.Default.Close, "关闭", tint = Color.White)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (waveActive) {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        VoiceWave(
                            active = recording,
                            processing = processing,
                            modifier = Modifier.pressToTalk(
                                onStart = { startVoice() },
                                onFinish = { finishVoice(it) },
                            ),
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            when {
                                preview.isNotBlank() -> preview
                                recording -> status
                                processing -> "识别中…"
                                // 空闲状态也显示 status：识别服务不可用/出错时用户能看到原因，
                                // 而不是永远显示「按住说话」像没发生一样。
                                else -> status
                            },
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                        )
                        if (preview.isBlank() && !recording) {
                            Text(
                                "点「语音」chip 退出语音态",
                                color = Color.White.copy(alpha = 0.4f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                } else {
                    // 2026-11-12（用户）：全屏浮层是暗底，但整页此前套在系统浅色主题里，
                    // Markdown 行内代码就成了「浅底 + 白字」（路径泛白看不清）。这里强制深色主题，
                    // 行内代码/代码块才会是深底白字。
                    DshTheme(darkTheme = true) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                            reverseLayout = true,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(messages.takeLast(100).asReversed(), key = { it.id }) { message ->
                                FullscreenMessage(message)
                            }
                        }
                    }
                }
            }

            if (chat != null) {
                DshTheme(darkTheme = true) { FloatingPromptQueue(chat) }
            }
            if (chat != null && (chat.streaming || chat.stopping)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (chat.stopping) "停止中…" else "生成中可继续发送", color = FsSub,
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        ChatMirror.setSendWhileRunningMode(if (sendMode == "steer") "queue" else "steer")
                    }, enabled = !chat.readOnly && !chat.stopping) {
                        Text("$sendLabel · 切换", color = FsAccent, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            chat?.attachError?.let { error ->
                Text(error, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp))
            }
            if (chat != null && (chat.pendingImages.isNotEmpty() || chat.pendingFiles.isNotEmpty())) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chat.pendingImages.forEach { image ->
                        TextButton(onClick = { ChatMirror.removePendingImage(image.ref.attachmentId) }) {
                            Text("${image.ref.name ?: "图片"} ×", color = FsAccent,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    chat.pendingFiles.forEach { file ->
                        TextButton(onClick = { ChatMirror.removePendingFile(file.name) }) {
                            Text("${file.name} ×", color = FsAccent, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (recording) {
                Text(
                    "松开结束 · 上滑取消",
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            ChipsRow(
                ttsMuted = ttsMuted,
                onTtsMuted = {
                    ttsMuted = it
                    if (it) speech.stopSpeaking()
                },
                attachScreen = attachScreen,
                onAttachScreen = {
                    attachScreen = it
                    if (it && !ChatMirror.attachScreenshot()) {
                        attachScreen = false
                        status = "截图失败：请先开无障碍服务"
                    }
                },
                attachNotifications = attachNotifications,
                onAttachNotifications = {
                    attachNotifications = it
                    if (it) {
                        val outcome = AttachmentCapture.attachNotifications(context)
                        status = outcome.message
                        if (!outcome.ok) {
                            attachNotifications = false
                            if (outcome.needsGrant) {
                                AttachmentCapture.openNotificationAccessSettings(context)
                            }
                        }
                    }
                },
                onAttachLocation = {
                    status = "位置附件：授权后取当前位置…"
                    if (!LocationCapture.start(context)) {
                        status = "打不开定位授权页，请从主窗口附件面板重试"
                    }
                },
                onOcr = onStartOcr,
                onWave = {
                    waveActive = !waveActive
                    if (!waveActive) {
                        speech.stopListening(true)
                        recording = false
                    }
                },
                waveActive = waveActive,
            )

            Row(
                Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text("输入或点麦克风说话", color = Color.White.copy(alpha = 0.45f))
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    maxLines = 3,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (canSend && ChatMirror.send(input)) input = ""
                        },
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(46.dp)
                        .background(
                            if (recording) Color(0xFFE05A5A) else Color.White.copy(alpha = 0.14f),
                            CircleShape,
                        )
                        .pressToTalk(
                            onStart = { startVoice() },
                            onFinish = { finishVoice(it) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Mic, "语音", tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(46.dp)
                        .background(
                            if (canSend) Color(0xFF2F6FE0) else Color.White.copy(alpha = 0.08f),
                            CircleShape,
                        )
                        .clickable(enabled = canSend) {
                            if (canSend && ChatMirror.send(input)) input = ""
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Send, sendLabel, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                if (chat != null && (chat.streaming || chat.stopping)) {
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = { ChatMirror.stop() }, enabled = !chat.stopping,
                        modifier = Modifier.size(40.dp).background(Color(0xFFE05A5A), CircleShape)) {
                        if (chat.stopping) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Stop, "停止", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenMessage(message: ChatMessage) {
    // 2026-11-12（用户）：全屏正文只取 TEXT；思考、过程、工具结果都折起来，和窗口模式一致。
    // 之前把 TEXT + REASONING 拼成一段正文、工具结果也当正文铺开，观感很乱。
    val reasoning = message.content
        .filter { it.type == BlockType.REASONING }
        .joinToString("\n") { it.text }
        .trim()
    val text = message.content
        .filter { it.type == BlockType.TEXT }
        .joinToString("\n") { it.text }
        .trim()
    if (message.role == MessageRole.USER) {
        // 引用包/文件折成 chip（用户 2026-11-11 反馈：悬浮窗（含全屏语音页）里还是整段全文，
        // 主界面已经是小卡片）。
        val texts = message.content.filter { it.type == BlockType.TEXT }.map { it.text }
        val quoted = texts.mapNotNull { parseQuotedAttachment(it) }
        val plain = texts
            .filter { parseQuotedAttachment(it) == null && it.trim() != ATTACHMENT_REFERENCE_HINT }
            .joinToString("\n")
            .trim()
        if (plain.isBlank() && quoted.isEmpty()) return
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = 0.12f)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (plain.isNotBlank()) {
                        Text(
                            plain,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    quoted.forEachIndexed { index, item ->
                        if (index > 0 || plain.isNotBlank()) Spacer(Modifier.height(6.dp))
                        FullscreenQuoteChip(item)
                    }
                }
            }
        }
        return
    }
    val toolCalls = message.content.filter { it.type == BlockType.TOOL_CALL }
    when (message.role) {
        MessageRole.ASSISTANT -> Column(Modifier.fillMaxWidth()) {
            if (toolCalls.isNotEmpty()) {
                // 带工具调用的那条 = 过程消息：思考 + 中间叙述 + 工具调用全部折成一行。
                FullscreenProcessRow(reasoning, text, toolCalls, message.streaming)
            } else {
                if (reasoning.isNotBlank()) FullscreenReasoningRow(reasoning, message.streaming)
                if (text.isNotBlank()) {
                    MarkdownText(
                        text = text,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = FsText,
                    )
                } else if (message.streaming && reasoning.isBlank()) {
                    Text("…", color = FsFaint, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // 工具结果不铺正文：和聊天页一样只留一行痕迹。
        MessageRole.TOOL -> Text(
            "⚙ 工具结果 · ${text.length} 字",
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = FsFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        else -> if (text.isNotBlank()) {
            Text(text, style = MaterialTheme.typography.labelSmall, color = FsSub)
        }
    }
}

// 全屏浮层是暗底：这几档颜色不跟主题走，避免浅色主题下再出现「浅底白字」。
private val FsText = Color(0xFFEAF0FA)
private val FsSub = Color(0xFFB9C6DA)
private val FsFaint = Color(0xFF93A2B8)
private val FsAccent = Color(0xFF8CB6FF)
private val FsRowBg = Color.White.copy(alpha = 0.10f)
private val FsRowLine = Color.White.copy(alpha = 0.16f)

/**
 * 过程行：一条带工具调用的 assistant 消息 = 思考 + 中间叙述 + N 个工具调用。
 * 默认折成一行（流式中显示「正在执行 …」），点开才看全文；与窗口模式 `ProcessRow` 同一语义。
 */
@Composable
private fun FullscreenProcessRow(
    reasoning: String,
    text: String,
    toolCalls: List<xin.dshaa.core.model.ContentBlock>,
    streaming: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val lastTool = toolCalls.lastOrNull()?.name ?: "工具"
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = FsRowBg,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, FsRowLine, RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (streaming) "正在执行 $lastTool…" else "过程 · ${toolCalls.size} 个工具调用",
                    style = MaterialTheme.typography.labelSmall,
                    color = FsSub,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = FsAccent,
                )
            }
            if (expanded) {
                if (reasoning.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text("思考：$reasoning", style = MaterialTheme.typography.labelSmall, color = FsFaint)
                }
                if (text.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(text, style = MaterialTheme.typography.bodySmall, color = FsSub)
                }
                toolCalls.forEach { call ->
                    Spacer(Modifier.height(4.dp))
                    FullscreenToolLine(call.name ?: "tool", call.arguments)
                }
            }
        }
    }
}

/** 思考块：默认只占一行「思考过程 · N 字」（流式中「正在思考…」），点开才看全文。 */
@Composable
private fun FullscreenReasoningRow(text: String, streaming: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = FsRowBg,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, FsRowLine, RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (streaming) "正在思考…" else "思考过程 · ${text.length} 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = FsSub,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = FsAccent,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text(text, style = MaterialTheme.typography.labelSmall, color = FsFaint)
            }
        }
    }
}

@Composable
private fun FullscreenToolLine(name: String, args: String) {
    Text(
        "⚙ $name ${args.take(80)}",
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
        color = FsFaint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 全屏页的引用 chip：白字 + 半透明白底（这个页面是透明浮层，用不了主界面的浅色卡片）。
 * 展开后正文自己滚，到顶/到底不再带动整页（用户 2026-11-11 反馈的嵌套滚动）。
 */
@Composable
private fun FullscreenQuoteChip(item: QuotedAttachment) {
    val expandable = item.body != null
    var expanded by remember(item.name, item.body?.hashCode()) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(10.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .let { base -> if (expandable) base.clickable { expanded = !expanded } else base }
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.AttachFile,
                null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (expandable) "${item.sizeLabel} · 点开看原文" else item.sizeLabel,
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expandable) {
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    if (expanded) "收起" else "展开",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (expanded) {
            item.body?.let { body ->
                val bodyScroll = rememberScrollState()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        body,
                        color = Color.White.copy(alpha = 0.86f),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .consumeInnerScroll(bodyScroll)
                            .verticalScroll(bodyScroll),
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceWave(active: Boolean, processing: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "voice-wave")
    val phase by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Box(
        modifier.size(260.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val base = size.minDimension * 0.27f
            listOf(0f, 0.33f, 0.66f).forEachIndexed { index, offset ->
                val p = ((phase + offset) % 1f)
                val radius = base * (1f + p * 0.55f)
                val alpha = (1f - p) * if (active) 0.32f else 0.12f
                drawCircle(
                    color = Color(0xFF4F8BFF).copy(alpha = alpha),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
            drawCircle(
                color = Color(0xFF1B2A47).copy(alpha = 0.9f),
                radius = base,
                center = center,
            )
            drawCircle(
                color = if (active) Color(0xFF4F8BFF) else Color.White.copy(alpha = 0.55f),
                radius = base * 0.34f,
                center = center,
            )
            if (processing) {
                drawArc(
                    color = Color.White,
                    startAngle = phase * 360f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(center.x - base * 0.45f, center.y - base * 0.45f),
                    size = androidx.compose.ui.geometry.Size(base * 0.9f, base * 0.9f),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
        Icon(
            Icons.Default.Mic,
            "语音",
            tint = Color.White,
            modifier = Modifier.size(40.dp),
        )
    }
}

@Composable
private fun ChipsRow(
    ttsMuted: Boolean,
    onTtsMuted: (Boolean) -> Unit,
    attachScreen: Boolean,
    onAttachScreen: (Boolean) -> Unit,
    attachNotifications: Boolean,
    onAttachNotifications: (Boolean) -> Unit,
    onAttachLocation: () -> Unit,
    onOcr: () -> Unit,
    onWave: () -> Unit,
    waveActive: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chip(
            selected = waveActive,
            text = "语音",
            icon = Icons.Default.Phone,
            onClick = onWave,
        )
        Chip(
            selected = ttsMuted,
            text = "",
            icon = if (ttsMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
            onClick = { onTtsMuted(!ttsMuted) },
        )
        Chip(
            selected = attachScreen,
            text = "屏幕",
            icon = if (attachScreen) Icons.Default.Check else Icons.Default.Crop,
            onClick = { onAttachScreen(!attachScreen) },
        )
        Chip(
            selected = attachNotifications,
            text = "通知",
            icon = if (attachNotifications) Icons.Default.Check else Icons.Default.Notifications,
            onClick = { onAttachNotifications(!attachNotifications) },
        )
        Chip(
            selected = false,
            text = "位置",
            icon = Icons.Default.LocationOn,
            onClick = onAttachLocation,
        )
        Chip(
            selected = false,
            text = "圈选",
            icon = Icons.Default.Crop,
            onClick = onOcr,
        )
    }
}

@Composable
private fun Chip(
    selected: Boolean,
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    val accent = Color(0xFF00E5FF)
    Row(
        Modifier
            .background(
                if (selected) Color.Black.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.25f),
                RoundedCornerShape(16.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            text,
            tint = if (selected) accent else Color.White.copy(alpha = 0.75f),
            modifier = Modifier.size(14.dp),
        )
        if (text.isNotBlank()) {
            Spacer(Modifier.width(4.dp))
            Text(
                text,
                color = if (selected) accent else Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            )
        }
    }
}

/**
 * 按住说话：按下开始识别，松开发送；手指上滑超过 60dp 视为取消（Operit 全屏语音的手势）。
 * 用 awaitEachGesture 而不是 clickable，保证「接触即说、离开即停」，不依赖点击抬起的语义。
 */
private fun Modifier.pressToTalk(
    onStart: () -> Unit,
    onFinish: (cancel: Boolean) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onStart()
        var cancel = false
        val cancelDistance = 60.dp.toPx()
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (down.position.y - change.position.y > cancelDistance) cancel = true
            if (!change.pressed) {
                onFinish(cancel)
                break
            }
        }
    }
}
