package xin.dshaa.nativeapp.device.floating.ui.window

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.ChatMessage
import xin.dshaa.core.model.MessageRole
import xin.dshaa.core.plugins.PluginRecord
import xin.dshaa.nativeapp.chat.ChatMirror
import xin.dshaa.nativeapp.chat.PendingFile
import xin.dshaa.nativeapp.data.PluginStore
import xin.dshaa.nativeapp.device.attach.AttachmentCapture
import xin.dshaa.nativeapp.device.attach.LocationCapture
import xin.dshaa.nativeapp.device.floating.FloatingMode
import xin.dshaa.nativeapp.device.floating.FloatingWindowState
import xin.dshaa.nativeapp.device.floating.isInternalNotice
import xin.dshaa.ui.design.component.MarkdownText
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * Operit `FloatingChatWindowScreen.kt`(1402) 的 WINDOW 模式移植。
 * 对照表 docs/76 §七 W1-W30。
 *
 * 窗口物理尺寸由 FloatingWindowManager 按 `宽×scale` 设置；这里用 Layout 先把内容按逻辑
 * 宽高测量，再 graphicsLayer 缩放，保证点击区域/布局与 Operit 一致（不是二次缩放）。
 */
@Composable
fun FloatingWindowScreen(
    state: FloatingWindowState,
    onClose: () -> Unit,
    onModeChange: (FloatingMode) -> Unit,
    onSaveState: () -> Unit,
    onMove: (Float, Float, Float) -> Unit,
    onInputFocusRequest: (Boolean) -> Unit,
    onResize: (Dp, Dp) -> Unit,
    onScaleChange: (Float) -> Unit,
    onStartOcr: () -> Unit,
) {
    val width = state.windowWidth.value
    val height = state.windowHeight.value
    val scale = state.windowScale.value
    val density = LocalDensity.current

    Layout(
        content = {
            Box(Modifier.width(width).height(height)) {
                WindowContent(
                    state = state,
                    onClose = onClose,
                    onModeChange = onModeChange,
                    onSaveState = onSaveState,
                    onMove = onMove,
                    onInputFocusRequest = onInputFocusRequest,
                    onResize = onResize,
                    onScaleChange = onScaleChange,
                    onStartOcr = onStartOcr,
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, _ ->
        val wPx = with(density) { width.toPx() }.roundToInt().coerceAtLeast(1)
        val hPx = with(density) { height.toPx() }.roundToInt().coerceAtLeast(1)
        val placeable = measurables.first().measure(Constraints.fixed(wPx, hPx))
        layout((wPx * scale).roundToInt(), (hPx * scale).roundToInt()) {
            placeable.placeRelativeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

@Composable
private fun WindowContent(
    state: FloatingWindowState,
    onClose: () -> Unit,
    onModeChange: (FloatingMode) -> Unit,
    onSaveState: () -> Unit,
    onMove: (Float, Float, Float) -> Unit,
    onInputFocusRequest: (Boolean) -> Unit,
    onResize: (Dp, Dp) -> Unit,
    onScaleChange: (Float) -> Unit,
    onStartOcr: () -> Unit,
) {
    val c = DshTheme2.colors
    val density = LocalDensity.current
    var showHistory by remember { mutableStateOf(false) }
    var resizing by remember { mutableStateOf(false) }

    val chat = ChatMirror.state
    // 内部注入消息（time-context/goal/tool-jobs…）不按 user 气泡显示，见 FloatingMessageFilter
    val messages = chat?.messages.orEmpty().filterNot { it.isInternalNotice() }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, chat?.streaming) {
        if (messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(0) }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface)
            .border(
                width = 3.dp,
                color = if (resizing) c.brand else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            ),
    ) {
        Column(Modifier.fillMaxSize()) {
            TitleBar(
                title = chat?.title?.ifBlank { "新会话" } ?: "鲸歌",
                status = when {
                    chat == null -> "还没有打开的会话"
                    chat.streaming -> chat.statusText ?: "生成中…"
                    else -> "${chat.model} · 就绪"
                },
                streaming = chat?.streaming == true,
                onHistory = { showHistory = true },
                onFullscreen = { onModeChange(FloatingMode.FULLSCREEN) },
                onOcr = onStartOcr,
                onMinimize = { onModeChange(FloatingMode.BALL) },
                onOpenApp = {
                    ChatMirror.openApp(state.appContext)
                    onClose()
                },
                onClose = onClose,
                onDrag = { dx, dy -> onMove(dx, dy, state.windowScale.value) },
                onDragEnd = onSaveState,
            )
            HorizontalDivider(color = c.line)

            if (chat == null) {
                EmptyState(onOpenApp = { ChatMirror.openApp(state.appContext) })
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (messages.isEmpty()) {
                        Text(
                            "发一句话开始",
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.faint,
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            reverseLayout = true,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(messages.takeLast(200).asReversed(), key = { it.id }) { message ->
                                MessageItem(message)
                            }
                        }
                    }
                }

                ProcessingStatus(chat.statusText, chat.error, chat.streaming)
                PendingCards()
                AttachmentRow()
                Composer(
                    state = state,
                    streaming = chat.streaming,
                    onInputFocusRequest = onInputFocusRequest,
                    onStartOcr = onStartOcr,
                )
            }
        }

        if (showHistory) {
            HistoryOverlay(
                currentId = chat?.sessionId,
                onDismiss = { showHistory = false },
                onOpenApp = { ChatMirror.openApp(state.appContext) },
            )
        }

        if (chat != null) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(14.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { resizing = true },
                            onDragEnd = {
                                resizing = false
                                onSaveState()
                            },
                            onDragCancel = { resizing = false },
                            onDrag = { change, drag ->
                                change.consume()
                                val delta = drag.x / with(density) { state.windowWidth.value.toPx() }
                                onScaleChange((state.windowScale.value + delta).coerceIn(0.3f, 1f))
                            },
                        )
                    },
            ) {
                Canvas(Modifier.width(6.dp).height(60.dp).align(Alignment.Center)) {
                    val color = if (resizing) c.brand else c.brand.copy(alpha = 0.35f)
                    val x = size.width / 2f
                    drawLine(color, Offset(x, 6f), Offset(x, size.height - 6f), 3f)
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(10.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { resizing = true },
                            onDragEnd = {
                                resizing = false
                                onSaveState()
                            },
                            onDragCancel = { resizing = false },
                            onDrag = { change, drag ->
                                change.consume()
                                val deltaDp = with(density) { drag.y.toDp() }
                                onResize(
                                    state.windowWidth.value,
                                    (state.windowHeight.value + deltaDp).coerceIn(250.dp, 1200.dp),
                                )
                            },
                        )
                    },
            ) {
                Canvas(Modifier.width(60.dp).height(6.dp).align(Alignment.Center)) {
                    val color = if (resizing) c.brand else c.brand.copy(alpha = 0.35f)
                    listOf(0.25f, 0.5f, 0.75f).forEach { f ->
                        drawLine(
                            color,
                            Offset(0f, size.height * f),
                            Offset(size.width, size.height * f),
                            2f,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleBar(
    title: String,
    status: String,
    streaming: Boolean,
    onHistory: () -> Unit,
    onFullscreen: () -> Unit,
    onOcr: () -> Unit,
    onMinimize: () -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val c = DshTheme2.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(c.surfaceAlt.copy(alpha = 0.55f))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TitleButton(Icons.Default.History, "历史会话", onHistory)
        TitleButton(Icons.Default.Fullscreen, "全屏", onFullscreen)
        TitleButton(Icons.Default.Crop, "圈选识别", onOcr)
        TitleButton(Icons.Default.KeyboardArrowDown, "收起为球", onMinimize)
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = onDragEnd,
                        onDrag = { change, drag ->
                            change.consume()
                            onDrag(drag.x, drag.y)
                        },
                    )
                }
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = if (streaming) c.brand else c.faint,
                    maxLines = 1,
                )
            }
        }
        TitleButton(Icons.Default.Home, "打开主界面", onOpenApp)
        TitleButton(Icons.Default.Close, "关闭", onClose)
    }
}

@Composable
private fun TitleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Icon(
            icon,
            description,
            tint = DshTheme2.colors.sub,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun EmptyState(onOpenApp: () -> Unit) {
    val c = DshTheme2.colors
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("先打开鲸歌，在聊天页选一个会话", style = MaterialTheme.typography.bodySmall, color = c.sub)
        Spacer(Modifier.height(10.dp))
        Surface(onClick = onOpenApp, shape = RoundedCornerShape(12.dp), color = c.brand) {
            Text(
                "打开鲸歌",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun MessageItem(message: ChatMessage) {
    val c = DshTheme2.colors
    val textBlocks = message.content.filter {
        it.type == BlockType.TEXT || it.type == BlockType.REASONING
    }
    val text = textBlocks.joinToString("\n") { it.text }.trim()
    val toolCalls = message.content.filter { it.type == BlockType.TOOL_CALL }

    when (message.role) {
        MessageRole.USER -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = c.brandSoft,
                modifier = Modifier.widthIn(max = 280.dp),
            ) {
                Text(
                    text.ifBlank { "（空）" },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.text,
                )
            }
        }

        MessageRole.ASSISTANT -> Column(Modifier.fillMaxWidth()) {
            toolCalls.forEach { call ->
                ToolChip(call.name ?: "tool", call.arguments)
            }
            if (text.isNotBlank()) {
                MarkdownText(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.text,
                )
            } else if (message.streaming) {
                Text("…", color = c.faint, style = MaterialTheme.typography.bodySmall)
            }
        }

        MessageRole.TOOL -> Surface(
            shape = RoundedCornerShape(8.dp),
            color = c.surfaceAlt,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text.ifBlank { message.toolCallId ?: "工具结果" }.take(800),
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = c.sub,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }

        else -> if (text.isNotBlank()) {
            Text(text, style = MaterialTheme.typography.labelSmall, color = c.faint)
        }
    }
}

@Composable
private fun ToolChip(name: String, args: String) {
    val c = DshTheme2.colors
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = c.surfaceAlt,
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        Text(
            "⚙ $name ${args.take(80)}",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = c.sub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProcessingStatus(status: String?, error: String?, streaming: Boolean) {
    val c = DshTheme2.colors
    if (error != null) {
        Text(
            error,
            modifier = Modifier.fillMaxWidth().background(c.dangerSoft).padding(10.dp),
            style = MaterialTheme.typography.labelSmall,
            color = c.danger,
        )
    } else if (streaming && !status.isNullOrBlank()) {
        Row(
            Modifier.fillMaxWidth().background(c.surfaceAlt.copy(alpha = 0.9f))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = c.brand)
            Spacer(Modifier.width(8.dp))
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = c.sub,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PendingCards() {
    val c = DshTheme2.colors
    val chat = ChatMirror.state ?: return
    chat.pendingApproval?.let { approval ->
        Surface(
            color = c.warnSoft,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Column(Modifier.padding(10.dp)) {
                Text("需要审批：${approval.toolName}", style = MaterialTheme.typography.labelMedium, color = c.text)
                Text(
                    approval.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = c.sub,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { ChatMirror.resolveApproval(true) }) { Text("允许") }
                    TextButton(onClick = { ChatMirror.resolveApproval(false) }) { Text("拒绝") }
                    TextButton(onClick = {
                        ChatMirror.resolveApproval(false)
                        ChatMirror.stop()
                    }) { Text("停止本轮") }
                }
            }
        }
    }
    chat.pendingQuestions?.let { questions ->
        if (questions.isNotEmpty()) {
            val question = questions.first()
            Surface(
                color = c.brandSoft,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(question.question, style = MaterialTheme.typography.labelMedium, color = c.text)
                    question.options.take(6).forEach { option ->
                        Text(
                            option.label,
                            modifier = Modifier.fillMaxWidth().clickable {
                                ChatMirror.resolveQuestions(
                                    listOf(
                                        xin.dshaa.core.tools.AskAnswer(
                                            id = question.id,
                                            selected = listOf(option.label),
                                        )
                                    )
                                )
                            }.padding(vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.brand,
                        )
                    }
                    TextButton(onClick = {
                        ChatMirror.resolveQuestions(
                            listOf(xin.dshaa.core.tools.AskAnswer(id = question.id))
                        )
                    }) { Text("跳过") }
                }
            }
        }
    }
}

@Composable
private fun AttachmentRow() {
    val chat = ChatMirror.state ?: return
    if (chat.pendingImages.isEmpty() && chat.pendingFiles.isEmpty()) return
    val c = DshTheme2.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chat.pendingImages.take(4).forEach { image ->
            Surface(shape = RoundedCornerShape(8.dp), color = c.surfaceAlt) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        image.ref.name ?: "图片",
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = c.sub,
                        maxLines = 1,
                    )
                    IconButton(
                        onClick = { ChatMirror.removePendingImage(image.ref.attachmentId) },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(Icons.Default.Close, "移除", modifier = Modifier.size(14.dp), tint = c.faint)
                    }
                }
            }
        }
        chat.pendingFiles.take(4).forEach { file ->
            Surface(shape = RoundedCornerShape(8.dp), color = c.surfaceAlt) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        file.name,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = c.sub,
                        maxLines = 1,
                    )
                    IconButton(
                        onClick = { ChatMirror.removePendingFile(file.name) },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(Icons.Default.Close, "移除", modifier = Modifier.size(14.dp), tint = c.faint)
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    state: FloatingWindowState,
    streaming: Boolean,
    onInputFocusRequest: (Boolean) -> Unit,
    onStartOcr: () -> Unit,
) {
    val c = DshTheme2.colors
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var attachOpen by remember { mutableStateOf(false) }
    var pluginPicker by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val chat = ChatMirror.state
    // 窗口默认 NOT_FOCUSABLE，输入框拿到焦点后要通知 Service 去掉这个 flag 并拉起 IME。
    // 不能只挂 `Modifier.onFocusChanged`：它观察的是外层装饰节点的焦点，TextField 的焦点
    // 在内部 BasicTextField 上，实测不回调（2026-11-09 真机：只弹紫色边框、键盘不出现）。
    // 现在 WINDOW 窗口在 createLayoutParams 里就是可获焦的，这里保留 interactionSource +
    // 按下兜底，命中时让 Service 走 scheduleIme（再点一次键盘也不会丢）。
    val focusSource = remember { MutableInteractionSource() }
    val inputFocused by focusSource.collectIsFocusedAsState()
    LaunchedEffect(inputFocused) { onInputFocusRequest(inputFocused) }
    val canSend = input.isNotBlank() || (chat?.pendingImages?.isNotEmpty() == true) ||
        (chat?.pendingFiles?.isNotEmpty() == true)
    val plugins = remember(pluginPicker) { PluginStore(context).list() }

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    Column(
        Modifier.fillMaxWidth().imePadding().padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        // Operit AttachmentPanel（docs/76 W24/W25）：5 项，屏幕/通知/位置/圈选/插件包。
        AnimatedVisibility(visible = attachOpen) {
            AttachmentPanel(
                onScreen = {
                    attachOpen = false
                    toast(
                        if (ChatMirror.attachScreenshot()) "已添加屏幕截图"
                        else "截图失败：请开启无障碍或 Shizuku",
                    )
                },
                onNotifications = {
                    attachOpen = false
                    val outcome = AttachmentCapture.attachNotifications(context)
                    toast(outcome.message)
                    if (outcome.needsGrant) {
                        if (!AttachmentCapture.openNotificationAccessSettings(context)) {
                            toast("打不开通知使用权设置，请到系统设置里手动授权")
                        }
                    }
                },
                onLocation = {
                    attachOpen = false
                    if (!LocationCapture.start(context)) {
                        toast("打不开定位授权页，请从主界面重试")
                    }
                },
                onOcr = {
                    attachOpen = false
                    onStartOcr()
                },
                onPlugin = {
                    attachOpen = false
                    if (plugins.isEmpty()) toast("还没有安装插件") else pluginPicker = true
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (attachOpen) c.brand else c.surfaceAlt)
                    .clickable { attachOpen = !attachOpen },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Add,
                    "附件",
                    tint = if (attachOpen) Color.White else c.sub,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("发消息…", style = MaterialTheme.typography.bodySmall, color = c.faint) },
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(Unit) {
                        // 兜底：手指按到输入框就通知 Service 把窗口变成可获焦（去掉 NOT_FOCUSABLE）。
                        // WINDOW 现在默认就可获焦，这一条防的是以后又改回动态切换时键盘不弹。
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            onInputFocusRequest(true)
                        }
                    },
                interactionSource = focusSource,
                textStyle = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (canSend && !streaming) {
                            if (ChatMirror.send(input)) input = ""
                            keyboard?.hide()
                        }
                    },
                ),
            )
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (streaming || canSend) c.brand else c.line2)
                    .clickable(enabled = streaming || canSend) {
                        if (streaming) {
                            ChatMirror.stop()
                        } else if (ChatMirror.send(input)) {
                            input = ""
                            keyboard?.hide()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (streaming) Icons.Default.Stop else Icons.Default.Send,
                    if (streaming) "停止" else "发送",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }

    if (pluginPicker) {
        PluginPickerDialog(
            plugins = plugins,
            onDismiss = { pluginPicker = false },
            onPick = { plugin ->
                pluginPicker = false
                val text = buildString {
                    append("【插件包】id=${plugin.id}; name=${plugin.name}; version=${plugin.version}; ")
                    append("enabled=${plugin.enabled}; format=${plugin.format}; path=${plugin.path}\n")
                    append("description: ${plugin.description}\n")
                    append("tools: ${plugin.tools.joinToString(", ")}\n")
                }
                val session = ChatMirror.state
                if (session == null) {
                    toast("还没有打开的会话")
                } else {
                    session.pendingFiles += PendingFile("plugin-${plugin.id}.txt", text)
                    toast("已添加插件包附件：${plugin.name}")
                }
            },
        )
    }
}

@Composable
private fun AttachmentPanel(
    onScreen: () -> Unit,
    onNotifications: () -> Unit,
    onLocation: () -> Unit,
    onOcr: () -> Unit,
    onPlugin: () -> Unit,
) {
    val c = DshTheme2.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { AttachOption(Icons.Default.Fullscreen, "屏幕内容", onScreen) }
            item { AttachOption(Icons.Default.Notifications, "通知", onNotifications) }
            item { AttachOption(Icons.Default.LocationOn, "位置", onLocation) }
            item { AttachOption(Icons.Default.Crop, "圈选识别", onOcr) }
            item { AttachOption(Icons.Default.Build, "插件包", onPlugin) }
        }
        Text(
            "屏幕内容走无障碍/Shizuku 截图；通知要「通知使用权」，位置要定位权限",
            modifier = Modifier.padding(start = 2.dp, top = 4.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = c.faint,
        )
    }
}

@Composable
private fun AttachOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val c = DshTheme2.colors
    Column(
        Modifier.width(72.dp).clickable { onClick() }.padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(50.dp).clip(RoundedCornerShape(14.dp)).background(c.surfaceAlt),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = c.sub, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = c.sub,
            maxLines = 1,
        )
    }
}

@Composable
private fun PluginPickerDialog(
    plugins: List<PluginRecord>,
    onDismiss: () -> Unit,
    onPick: (PluginRecord) -> Unit,
) {
    val c = DshTheme2.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("选择插件包") },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(plugins, key = { it.id }) { plugin ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onPick(plugin) }.padding(vertical = 8.dp),
                    ) {
                        Text(plugin.name, style = MaterialTheme.typography.bodyMedium, color = c.text)
                        Text(
                            "v${plugin.version} · ${if (plugin.enabled) "已启用" else "已停用"} · ${plugin.id}",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.faint,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun HistoryOverlay(
    currentId: String?,
    onDismiss: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val c = DshTheme2.colors
    val sessions = ChatMirror.sessions.sortedByDescending { it.updatedAt }.take(20)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = c.surface,
            modifier = Modifier.fillMaxWidth().padding(12.dp).align(Alignment.TopCenter),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "会话历史",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = c.text,
                    )
                    TextButton(onClick = onOpenApp) { Text("主界面") }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "关闭", tint = c.faint)
                    }
                }
                HorizontalDivider(color = c.line)
                if (sessions.isEmpty()) {
                    Text(
                        "还没有会话",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.faint,
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(sessions, key = { it.id }) { session ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (session.id == currentId) c.brandSoft else Color.Transparent
                                    )
                                    .clickable {
                                        ChatMirror.openSession(session.id)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        session.title.ifBlank { "未命名" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = c.text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${session.messageCount} 条 · ${formatTime(session.updatedAt)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = c.faint,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return ""
    val s = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return s.format(java.util.Date(ms))
}
