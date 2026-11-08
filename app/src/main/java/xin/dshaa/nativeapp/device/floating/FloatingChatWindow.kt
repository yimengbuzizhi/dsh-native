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

    LaunchedEffect(streaming, mode) {
        if (lastStreaming && !streaming && mode == FloatingMode.BALL) {
            onModeChange(FloatingMode.RESULT_DISPLAY)
            delay(3000)
            if (state.currentMode.value == FloatingMode.RESULT_DISPLAY) {
                onModeChange(FloatingMode.BALL)
            }
        }
        lastStreaming = streaming
    }

    Box(Modifier.fillMaxSize()) {
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
