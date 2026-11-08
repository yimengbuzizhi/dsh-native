package xin.dshaa.nativeapp.device.floating.ui.fullscreen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import xin.dshaa.nativeapp.chat.ChatMirror
import xin.dshaa.nativeapp.device.attach.AttachmentCapture
import xin.dshaa.nativeapp.device.attach.LocationCapture
import xin.dshaa.nativeapp.device.floating.FloatingMode
import xin.dshaa.nativeapp.device.floating.FloatingSpeechManager
import xin.dshaa.nativeapp.device.floating.FloatingWindowState
import xin.dshaa.nativeapp.device.floating.isInternalNotice
import xin.dshaa.ui.design.component.MarkdownText
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
    var waveActive by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("按住说话 · 上滑取消") }
    var preview by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var ttsMuted by remember { mutableStateOf(false) }
    var attachScreen by remember { mutableStateOf(false) }
    var attachNotifications by remember { mutableStateOf(false) }
    var lastSpokenId by remember { mutableStateOf<String?>(null) }

    // 按住说话（Operit 全屏语音的手势）：按下开始识别，松开发送，上滑取消。
    fun startVoice() {
        if (recording) return
        preview = ""
        waveActive = true
        recording = true
        speech.startListening()
    }

    fun finishVoice(cancel: Boolean) {
        if (!recording) return
        recording = false
        if (cancel) {
            speech.stopListening(true)
            status = "已取消"
        } else {
            speech.stopListening(false)
        }
    }

    val chat = ChatMirror.state
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
            status = "已识别，发送中…"
            if (ChatMirror.send(text)) {
                processing = true
            } else {
                status = "发送失败（没有打开的会话？）"
                recording = false
            }
        }
        speech.onError = {
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
    Box(Modifier.fillMaxSize().background(Color(0x380B0F17))) {
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
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                        reverseLayout = true,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(messages.takeLast(100), key = { it.id }) { message ->
                            FullscreenMessage(message)
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
                            if (ChatMirror.send(input)) input = ""
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
                            if (input.isNotBlank()) Color(0xFF2F6FE0) else Color.White.copy(alpha = 0.08f),
                            CircleShape,
                        )
                        .clickable(enabled = input.isNotBlank()) {
                            if (ChatMirror.send(input)) input = ""
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Send, "发送", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun FullscreenMessage(message: ChatMessage) {
    val c = DshTheme2.colors
    val text = message.content
        .filter { it.type == BlockType.TEXT || it.type == BlockType.REASONING }
        .joinToString("\n") { it.text }
        .trim()
    if (text.isBlank()) return
    if (message.role == MessageRole.USER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = 0.12f)) {
                Text(
                    text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    } else {
        MarkdownText(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.92f),
        )
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
