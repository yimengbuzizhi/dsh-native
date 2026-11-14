package xin.dshaa.nativeapp.device.floating

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import xin.dshaa.nativeapp.chat.ChatMirror
import xin.dshaa.nativeapp.device.floating.ui.ball.FloatingResultDisplay
import xin.dshaa.nativeapp.device.floating.ui.fullscreen.FloatingFullscreenScreen
import xin.dshaa.nativeapp.device.floating.ui.screenocr.FloatingScreenOcrScreen
import xin.dshaa.nativeapp.device.floating.ui.window.FloatingWindowScreen

/**
 * Operit `FloatingChatWindow.kt:70-233` 的根 UI：按 currentMode 渲染 6 个模式，
 * 转场时长照抄（窗口↔全屏 220/160，其他→球 350+150，球→其他 400+100，球↔球 250/100）。
 */
@Composable
fun FloatingChatWindow(
    state: FloatingWindowState,
    onClose: () -> Unit,
    onModeChange: (FloatingMode) -> Unit,
    onSaveState: () -> Unit,
    onMove: (Float, Float, Float) -> Unit,
    onInputFocusRequest: (Boolean) -> Unit,
    onResize: (Dp, Dp) -> Unit,
    onScaleChange: (Float) -> Unit,
    onStartOcr: () -> Unit = {},
) {
    val mode = state.currentMode.value
    val previous = state.previousMode
    val streaming = ChatMirror.state?.streaming == true
    var lastStreaming by remember { mutableStateOf(false) }
    // M84-D：读一次插件注册表版本，开关悬浮窗插件后立即重组四个插口。
    val slotsRevision = FloatingSlots.revision
    val ballBadge = remember(slotsRevision) { FloatingSlots.ballBadge() }
    val bubbleStyle = remember(slotsRevision) { FloatingSlots.bubbleStyle() }
    val slot = mode.toSlot()
    val banner = remember(slot, slotsRevision) { slot?.let { FloatingSlots.banner(it) } }

    // M84-D：插口显示钩子（气泡/面板/全屏页真正出现时通知插件）。
    LaunchedEffect(mode, slotsRevision) {
        slot?.let { FloatingSlots.dispatchShow(it) }
    }

    // 流式结束且当前是悬浮球 → 弹一次结果气泡（气泡插口的「显示时机」由插件决定）。
    // key 只跟 streaming 走：原来把 delay(3000) 放在 key=(streaming, mode) 的 effect 里，
    // 切到 RESULT_DISPLAY 会立刻取消这个 effect，3 秒后永远回不到球——真机复现是
    // 白色气泡卡在桌面中央、悬浮球消失（用户 2026-11-11 反馈「悬浮窗怎么消失了」）。
    LaunchedEffect(streaming) {
        if (lastStreaming && !streaming && state.currentMode.value == FloatingMode.BALL &&
            FloatingSlots.visible(FloatingSlot.BUBBLE)
        ) {
            onModeChange(FloatingMode.RESULT_DISPLAY)
        }
        lastStreaming = streaming
    }

    // 气泡 3 秒后回球；key 只看 mode，切换模式不会打断已经排上的计时器。
    LaunchedEffect(mode) {
        if (mode != FloatingMode.RESULT_DISPLAY) return@LaunchedEffect
        delay(3000)
        if (state.currentMode.value == FloatingMode.RESULT_DISPLAY) {
            onModeChange(FloatingMode.BALL)
        }
    }

    // 结果气泡是 WRAP_CONTENT 窗口：根节点必须跟着内容收缩，否则 fillMaxSize 会把它
    // 撑成整屏（实测 640×1504）——窗口范围内的触摸都被吃掉，NOT_TOUCH_MODAL 只在
    // 窗口外生效，等于没修（用户反馈第 2 条的真根因，docs/103）。
    val rootModifier =
        if (mode == FloatingMode.RESULT_DISPLAY) Modifier.wrapContentSize() else Modifier.fillMaxSize()

    Box(rootModifier) {
        // 结果气泡直接渲染，不走 AnimatedContent：过渡容器会沿用悬浮球的尺寸去量内容，
        // 实测气泡被压成 120×95、文字截成「命…」。它是 3 秒瞬态提示，直接切换即可。
        if (mode == FloatingMode.RESULT_DISPLAY) {
            FloatingResultDisplay(
                onBackToBall = {
                    if (!FloatingSlots.handleClick(FloatingSlot.BUBBLE)) onModeChange(FloatingMode.BALL)
                },
                style = bubbleStyle,
            )
        } else {
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    val target = targetState
                    val initial = initialState
                    val toBall = target == FloatingMode.BALL || target == FloatingMode.VOICE_BALL
                    val fromBall = initial == FloatingMode.BALL || initial == FloatingMode.VOICE_BALL
                    val windowFullscreen =
                        (initial == FloatingMode.WINDOW &&
                            (target == FloatingMode.FULLSCREEN || target == FloatingMode.SCREEN_OCR)) ||
                            ((initial == FloatingMode.FULLSCREEN || initial == FloatingMode.SCREEN_OCR) &&
                                target == FloatingMode.WINDOW)
                    when {
                        windowFullscreen -> {
                            (fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                                scaleIn(
                                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                                    initialScale = 0.92f,
                                ))
                                .togetherWith(
                                    fadeOut(tween(160)) +
                                        scaleOut(
                                            animationSpec = tween(160),
                                            targetScale = 1.03f,
                                        )
                                )
                        }
                        toBall && !fromBall -> {
                            (fadeIn(tween(350, delayMillis = 150, easing = FastOutSlowInEasing)) +
                                scaleIn(
                                    animationSpec = tween(
                                        350,
                                        delayMillis = 150,
                                        easing = FastOutSlowInEasing,
                                    ),
                                    initialScale = 0f,
                                ))
                                .togetherWith(
                                    fadeOut(tween(150)) +
                                        scaleOut(animationSpec = tween(150), targetScale = 0f)
                                )
                        }
                        fromBall && !toBall -> {
                            (fadeIn(tween(400, delayMillis = 100, easing = FastOutSlowInEasing)) +
                                scaleIn(
                                    animationSpec = tween(
                                        400,
                                        delayMillis = 100,
                                        easing = FastOutSlowInEasing,
                                    ),
                                    initialScale = 0f,
                                ))
                                .togetherWith(
                                    fadeOut(tween(100)) +
                                        scaleOut(animationSpec = tween(100), targetScale = 0f)
                                )
                        }
                        else -> fadeIn(tween(250, delayMillis = 100))
                            .togetherWith(fadeOut(tween(100)))
                    }
                },
                label = "mode_transition",
            ) { target ->
                when (target) {
                    FloatingMode.WINDOW -> {
                        // M85-F：插件用 Surface 提供整块面板内容时，宿主改用 SurfaceTreeView；
                        // 不提供 = 原生聊天面板（默认，行为不变）。
                        val surface = remember(slotsRevision) { FloatingSlots.surfaceTree(FloatingSlot.PANEL) }
                        if (surface != null) {
                            FloatingSurfaceSlot(tree = surface, slot = FloatingSlot.PANEL)
                        } else {
                            FloatingWindowScreen(
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
                    }
                    FloatingMode.BALL -> FloatingOrb(
                        size = state.ballSize.value,
                        active = streaming,
                        fadingOut = state.ballExploding.value,
                        badge = ballBadge,
                        onTap = {
                            if (!FloatingSlots.handleClick(FloatingSlot.BALL)) onModeChange(FloatingMode.WINDOW)
                        },
                        onDrag = { dx, dy -> onMove(dx, dy, 1f) },
                        onDragEnd = onSaveState,
                    )
                    FloatingMode.VOICE_BALL -> FloatingOrb(
                        size = state.ballSize.value,
                        active = streaming,
                        fadingOut = state.ballExploding.value,
                        badge = ballBadge,
                        onTap = {
                            if (!FloatingSlots.handleClick(FloatingSlot.BALL)) onModeChange(FloatingMode.FULLSCREEN)
                        },
                        onDrag = { dx, dy -> onMove(dx, dy, 1f) },
                        onDragEnd = onSaveState,
                    )
                    FloatingMode.FULLSCREEN -> {
                        // 同一个 Surface 插口也支持全屏页；演示插件只注册面板，避免整页替换聊天。
                        val surface = remember(slotsRevision) { FloatingSlots.surfaceTree(FloatingSlot.FULLSCREEN) }
                        if (surface != null) {
                            FloatingSurfaceSlot(tree = surface, slot = FloatingSlot.FULLSCREEN)
                        } else {
                            FloatingFullscreenScreen(
                                state = state,
                                onClose = onClose,
                                onModeChange = onModeChange,
                                onStartOcr = onStartOcr,
                            )
                        }
                    }
                    // 走不到这里（上面 if 已直接渲染），保留分支只为 when 穷尽。
                    FloatingMode.RESULT_DISPLAY -> FloatingResultDisplay(
                        onBackToBall = { onModeChange(FloatingMode.BALL) }
                    )
                    FloatingMode.SCREEN_OCR -> FloatingScreenOcrScreen(
                        state = state,
                        onClose = { onModeChange(previous) },
                    )
                }
            }
        }
        // M84-D：面板 / 全屏页插口的内容横幅（插件提供；null = 不打扰）。
        if (banner != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp, start = 10.dp, end = 10.dp)
                    .background(Color(0xE6FFFFFF), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(banner, fontSize = 12.sp, color = Color(0xFF333333))
            }
        }
    }
}

/** 模式 → 四个插口之一；SCREEN_OCR 是工具页，不算四分之一。 */
private fun FloatingMode.toSlot(): FloatingSlot? = when (this) {
    FloatingMode.BALL, FloatingMode.VOICE_BALL -> FloatingSlot.BALL
    FloatingMode.RESULT_DISPLAY -> FloatingSlot.BUBBLE
    FloatingMode.WINDOW -> FloatingSlot.PANEL
    FloatingMode.FULLSCREEN -> FloatingSlot.FULLSCREEN
    FloatingMode.SCREEN_OCR -> null
}
