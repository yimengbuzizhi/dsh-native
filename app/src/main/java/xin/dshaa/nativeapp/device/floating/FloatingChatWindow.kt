package xin.dshaa.nativeapp.device.floating

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
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

    // 流式结束且当前是悬浮球 → 弹一次结果气泡。
    // key 只跟 streaming 走：原来把 delay(3000) 放在 key=(streaming, mode) 的 effect 里，
    // 切到 RESULT_DISPLAY 会立刻取消这个 effect，3 秒后永远回不到球——真机复现是
    // 白色气泡卡在桌面中央、悬浮球消失（用户 2026-11-11 反馈「悬浮窗怎么消失了」）。
    LaunchedEffect(streaming) {
        if (lastStreaming && !streaming && state.currentMode.value == FloatingMode.BALL) {
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
            FloatingResultDisplay(onBackToBall = { onModeChange(FloatingMode.BALL) })
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
                    FloatingMode.WINDOW -> FloatingWindowScreen(
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
                    FloatingMode.BALL -> FloatingOrb(
                        size = state.ballSize.value,
                        active = streaming,
                        fadingOut = state.ballExploding.value,
                        onTap = { onModeChange(FloatingMode.WINDOW) },
                        onDrag = { dx, dy -> onMove(dx, dy, 1f) },
                        onDragEnd = onSaveState,
                    )
                    FloatingMode.VOICE_BALL -> FloatingOrb(
                        size = state.ballSize.value,
                        active = streaming,
                        fadingOut = state.ballExploding.value,
                        onTap = { onModeChange(FloatingMode.FULLSCREEN) },
                        onDrag = { dx, dy -> onMove(dx, dy, 1f) },
                        onDragEnd = onSaveState,
                    )
                    FloatingMode.FULLSCREEN -> FloatingFullscreenScreen(
                        state = state,
                        onClose = onClose,
                        onModeChange = onModeChange,
                        onStartOcr = onStartOcr,
                    )
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
    }
}
