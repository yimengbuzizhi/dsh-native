package xin.dshaa.nativeapp.device.floating

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xin.dshaa.nativeapp.device.DeviceShell
import xin.dshaa.nativeapp.device.a11y.A11y
import xin.dshaa.ui.design.theme.DshTheme
import kotlin.math.min
import com.ai.assistance.showerclient.ShellIdentity

/**
 * Operit `FloatingWindowManager` 的原生移植（docs/76 §五 S14-S27、§六）。
 *
 * 关键约束（照 Operit）：
 * - **一个 ComposeView** 承载 6 个模式，切模式只改 WindowManager.LayoutParams，不反复 add/remove；
 * - 每个模式单独算宽高/flags/位置，不用一套参数硬套；
 * - 球↔其他模式要等 Compose 转场（150ms/100ms），窗口本身不是立刻变；
 * - WINDOW 默认 NOT_FOCUSABLE，输入时去掉并重试 IME，点外部再收起。
 */
class FloatingWindowManager(
    private val context: Context,
    val state: FloatingWindowState,
    private val callback: Callback,
) {

    interface Callback {
        fun onClose()
        fun saveState()
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /**
     * 不能用 `resources.displayMetrics`：跑过 VirtualHomeActivity 后，应用进程的 resources
     * 会变成虚拟屏（1088×1920），球/窗就按虚拟屏尺寸夹取了。DEFAULT_DISPLAY 才是物理屏。
     */
    private val realMetrics = DisplayMetrics().also { metrics ->
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            display.getRealMetrics(metrics)
        } else {
            metrics.setTo(context.resources.displayMetrics)
        }
    }
    private val density = realMetrics.density
    private val screenW = realMetrics.widthPixels
    private val screenH = realMetrics.heightPixels
    /**
     * 状态栏高度：`TYPE_APPLICATION_OVERLAY + Gravity.TOP` 的 `params.y` 原点在状态栏下方，
     * 所以「能用到的总高度」是 [screenH] 减掉状态栏；窗口贴底夹取要用 [contentH]，
     * 否则 frame 会比屏底多 64px、输入条被裁到屏幕外（2026-11-09 真机实测）。
     */
    private val statusBarHeight: Int = run {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
    private val contentH = (screenH - statusBarHeight).coerceAtLeast(0)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var composeView: ComposeView? = null
    private var isViewAdded = false
    private var isIndicatorAdded = false
    private var statusIndicator: ComposeView? = null
    private var pendingImeRunnable: Runnable? = null

    private var windowDisplayEnabled = true
    private var windowPersistentHidden = false
    private var indicatorDisplayEnabled = true
    private var indicatorPersistentEnabled = false

    private val lifecycleOwner = FloatingLifecycleOwner()

    fun show(): Boolean {
        if (isViewAdded) return true
        return try {
            lifecycleOwner.start()
            val view = ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setContent {
                    DshTheme(darkTheme = themeIsDark(), accentId = themeAccent()) {
                        FloatingChatWindow(
                            state = state,
                            onClose = {
                                cancelFocusAndHideIme()
                                callback.onClose()
                            },
                            onModeChange = { switchMode(it) },
                            onSaveState = { callback.saveState() },
                            onMove = { dx, dy, scale -> onMove(dx, dy, scale) },
                            onInputFocusRequest = { setFocusable(it) },
                            onResize = { w, h ->
                                state.windowWidth.value = w
                                state.windowHeight.value = h
                                updateWindowSize()
                                callback.saveState()
                            },
                            onScaleChange = { scale ->
                                state.windowScale.value = scale.coerceIn(0.3f, 1f)
                                updateWindowSize()
                                callback.saveState()
                            },
                            onStartOcr = { startScreenOcr() },
                        )
                    }
                }
            }
            val params = createLayoutParams()
            windowManager.addView(view, params)
            composeView = view
            isViewAdded = true
            true
        } catch (t: Throwable) {
            android.util.Log.e("FloatingWindowManager", "show failed", t)
            false
        }
    }

    /**
     * 圈选 OCR 的入口：先隐藏自己的 overlay，抓一张不含悬浮窗的整屏图，再切 SCREEN_OCR。
     * 如果先切模式，这个全屏黑底会被截进去（docs/76 §十 O1 的差异说明）。
     */
    fun startScreenOcr() {
        scope.launch {
            val v = composeView ?: return@launch
            v.visibility = View.INVISIBLE
            delay(180)
            val bitmap = runCatching { capturePhysicalScreen() }.getOrNull()
            v.visibility = View.VISIBLE
            state.ocrBitmap = bitmap
            state.ocrError = if (bitmap == null) {
                "截图失败：Shizuku screencap 和无障碍截图都没有拿到画面（Android 11+ 需开启无障碍服务）"
            } else {
                null
            }
            switchMode(FloatingMode.SCREEN_OCR)
        }
    }

    /**
     * 截整屏：优先 shell `screencap -p` 的 stdout（App 已有 Shizuku/root，不依赖无障碍），
     * 拿不到时再用无障碍 takeScreenshot。两条路都要在 overlay INVISIBLE 时调用。
     */
    private suspend fun capturePhysicalScreen(): Bitmap? {
        if (DeviceShell.canUseShellIdentity()) {
            val bytes = runCatching {
                DeviceShell.runBytes("screencap -p", ShellIdentity.SHELL)
            }.getOrNull()
            Log.d(TAG, "shell screencap bytes=${bytes?.size ?: -1}")
            if (bytes != null) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                Log.d(TAG, "shell screencap decode=${bitmap?.width}x${bitmap?.height}")
                if (bitmap != null) return bitmap
            }
        }
        val fallback = A11y.screenshot()
        Log.d(TAG, "a11y screenshot=${fallback?.width}x${fallback?.height}")
        return fallback
    }

    fun destroy() {
        scope.cancel()
        hideStatusIndicator()
        composeView?.let {
            cancelFocusAndHideIme()
            runCatching { windowManager.removeView(it) }
        }
        composeView = null
        isViewAdded = false
        lifecycleOwner.destroy()
    }

    fun getComposeView(): View? = composeView

    fun prepareForExit() = cancelFocusAndHideIme()

    fun setFloatingWindowVisible(visible: Boolean) {
        windowDisplayEnabled = visible
        refreshVisibility()
    }

    fun setFloatingWindowPersistentHidden(hidden: Boolean) {
        windowPersistentHidden = hidden
        refreshVisibility()
    }

    fun setStatusIndicatorVisible(visible: Boolean) {
        indicatorDisplayEnabled = visible
        refreshVisibility()
    }

    fun setStatusIndicatorPersistentVisible(visible: Boolean) {
        indicatorPersistentEnabled = visible
        refreshVisibility()
    }

    private fun refreshVisibility() {
        val visible = !windowPersistentHidden && windowDisplayEnabled
        composeView?.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            updateViewLayout { params ->
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
        }
        val showIndicator = indicatorPersistentEnabled ||
            (indicatorDisplayEnabled && !visible &&
                (state.currentMode.value == FloatingMode.WINDOW ||
                    state.currentMode.value == FloatingMode.FULLSCREEN))
        if (showIndicator) showStatusIndicator() else hideStatusIndicator()
    }

    /** Operit 的 TOP_BAR 指示器；全屏彩虹边框不在本轮（docs/76 §十三.5）。 */
    private fun showStatusIndicator() {
        if (isIndicatorAdded) return
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                DshTheme(darkTheme = themeIsDark(), accentId = themeAccent()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                        shadowElevation = 8.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "自动化进行中",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (16 * density).toInt()
        }
        runCatching {
            windowManager.addView(view, params)
            statusIndicator = view
            isIndicatorAdded = true
        }
    }

    private fun hideStatusIndicator() {
        statusIndicator?.let { runCatching { windowManager.removeView(it) } }
        statusIndicator = null
        isIndicatorAdded = false
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            0,
            0,
            overlayType(),
            0,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = softInputModeFor(state.currentMode.value)
        }
        setPrivateFlag(params, PRIVATE_FLAG_NO_MOVE_ANIMATION)
        applyModeParams(params, state.currentMode.value)
        return params
    }

    @SuppressLint("RtlHardcoded")
    private fun applyModeParams(
        params: WindowManager.LayoutParams,
        mode: FloatingMode,
        targetGravity: Int = Gravity.TOP or Gravity.START,
    ) {
        when (mode) {
            FloatingMode.FULLSCREEN, FloatingMode.SCREEN_OCR -> {
                params.width = WindowManager.LayoutParams.MATCH_PARENT
                params.height = WindowManager.LayoutParams.MATCH_PARENT
                params.flags =
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                params.x = 0
                params.y = 0
                state.x = 0
                state.y = 0
            }

            FloatingMode.BALL, FloatingMode.VOICE_BALL -> {
                val ball = (state.ballSize.value.value * density).toInt()
                params.width = ball
                params.height = ball
                params.flags =
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                val safe = (16 * density).toInt()
                val minVisible = ball / 2
                state.x = state.x.coerceIn(-ball + minVisible + safe, screenW - minVisible - safe)
                state.y = state.y.coerceIn(safe, contentH - minVisible - safe)
                params.x = state.x
                params.y = state.y
            }

            FloatingMode.WINDOW -> {
                val scale = state.windowScale.value
                params.width = (state.windowWidth.value.value * density * scale).toInt()
                params.height = (state.windowHeight.value.value * density * scale).toInt()
                // WINDOW 常驻可获焦：输入框一聚焦系统就会弹 IME，不再依赖 Compose 焦点回调
                // 去动态去掉 NOT_FOCUSABLE（那套在本机实测只有紫色边框、键盘不出来，2026-11-09）。
                // NOT_TOUCH_MODAL + WATCH_OUTSIDE_TOUCH 保证窗口外点击仍落到后面的 App。
                params.flags =
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                val minVisibleW = params.width * 2 / 3
                val safe = (20 * density).toInt()
                state.x = state.x.coerceIn(
                    -(params.width - minVisibleW) + safe,
                    screenW - minVisibleW - safe,
                )
                state.y = state.y.coerceIn(safe, contentH - params.height / 2 - safe)
                params.x = state.x
                params.y = state.y
            }

            FloatingMode.RESULT_DISPLAY -> {
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.flags =
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                params.gravity = targetGravity
                val ball = (state.ballSize.value.value * density).toInt()
                val minVisible = ball / 2
                state.x = state.x.coerceIn(-ball + minVisible, screenW - minVisible)
                state.y = state.y.coerceIn(0, contentH - minVisible)
                params.x = state.x
                params.y = state.y
            }
        }
        params.softInputMode = softInputModeFor(mode)
        applyCutout(params, mode == FloatingMode.FULLSCREEN || mode == FloatingMode.SCREEN_OCR)
        applyBlur(params, mode == FloatingMode.FULLSCREEN)
        state.isAtEdge.value = isAtEdge(params.x, params.width)
    }

    private fun softInputModeFor(mode: FloatingMode): Int =
        if (mode == FloatingMode.FULLSCREEN) {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        }

    private fun applyCutout(params: WindowManager.LayoutParams, enabled: Boolean) {
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        params.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun applyBlur(params: WindowManager.LayoutParams, enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            state.fullscreenSystemBlurActive.value = false
            return
        }
        if (enabled) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            params.setBlurBehindRadius((48 * density).toInt())
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            params.setBlurBehindRadius(0)
        }
        state.fullscreenSystemBlurActive.value =
            enabled && windowManager.isCrossWindowBlurEnabled
    }

    private fun setPrivateFlag(params: WindowManager.LayoutParams, flag: Int) {
        runCatching {
            val field = params.javaClass.getField("privateFlags")
            field.setInt(params, field.getInt(params) or flag)
        }
    }

    private fun isAtEdge(x: Int, width: Int): Boolean {
        val tolerance = 5
        return x <= tolerance || x >= screenW - width - tolerance
    }

    fun switchMode(newMode: FloatingMode) {
        if (state.isTransitioning || state.currentMode.value == newMode) return
        state.isTransitioning = true

        if (newMode == FloatingMode.BALL || newMode == FloatingMode.VOICE_BALL) {
            cancelFocusAndHideIme()
        }

        val oldMode = state.currentMode.value
        val view = composeView
        if (view == null) {
            state.isTransitioning = false
            return
        }
        val params = view.layoutParams as WindowManager.LayoutParams

        when (oldMode) {
            FloatingMode.BALL, FloatingMode.VOICE_BALL -> {
                state.lastBallPositionX = params.x
                state.lastBallPositionY = params.y
            }

            FloatingMode.WINDOW -> {
                state.lastWindowPositionX = params.x
                state.lastWindowPositionY = params.y
                state.lastWindowScale = state.windowScale.value
            }

            else -> Unit
        }

        state.previousMode = oldMode
        state.currentMode.value = newMode
        state.ballExploding.value = false
        callback.saveState()

        data class Target(
            val width: Int,
            val height: Int,
            val x: Int,
            val y: Int,
            val flags: Int,
            val gravity: Int,
            val blur: Boolean,
        )

        val target = when (newMode) {
            FloatingMode.BALL, FloatingMode.VOICE_BALL -> {
                val ball = (state.ballSize.value.value * density).toInt()
                val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                val (nx, ny) = when (oldMode) {
                    FloatingMode.FULLSCREEN, FloatingMode.SCREEN_OCR ->
                        (screenW - ball) to (screenH - ball) / 2
                    FloatingMode.RESULT_DISPLAY ->
                        state.lastBallPositionX to state.lastBallPositionY
                    else -> {
                        val sw = if (params.width == WindowManager.LayoutParams.MATCH_PARENT) screenW else params.width
                        val sh = if (params.height == WindowManager.LayoutParams.MATCH_PARENT) screenH else params.height
                        (params.x + sw / 2 - ball / 2) to (params.y + sh / 2 - ball / 2)
                    }
                }
                val minVisible = ball / 2
                Target(
                    ball,
                    ball,
                    nx.coerceIn(-ball + minVisible, screenW - minVisible),
                    ny.coerceIn(0, contentH - minVisible),
                    flags,
                    Gravity.TOP or Gravity.START,
                    false,
                )
            }

            FloatingMode.WINDOW -> {
                val width = (state.windowWidth.value.value * density * state.lastWindowScale).toInt()
                val height = (state.windowHeight.value.value * density * state.lastWindowScale).toInt()
                val fromBall = oldMode == FloatingMode.BALL || oldMode == FloatingMode.VOICE_BALL
                val (nx, ny) = if (fromBall) {
                    (params.x + params.width / 2 - width / 2) to
                        (params.y + params.height / 2 - height / 2)
                } else {
                    state.lastWindowPositionX to state.lastWindowPositionY
                }
                state.windowScale.value = state.lastWindowScale
                val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                Target(
                    width,
                    height,
                    if (fromBall) nx.coerceIn(0, (screenW - width).coerceAtLeast(0))
                    else nx.coerceIn(-(width * 1 / 3), screenW - width / 3),
                    if (fromBall) ny.coerceIn(0, (contentH - height).coerceAtLeast(0))
                    else ny.coerceIn(0, contentH - height * 2 / 3),
                    flags,
                    Gravity.TOP or Gravity.START,
                    false,
                )
            }

            FloatingMode.FULLSCREEN, FloatingMode.SCREEN_OCR -> Target(
                screenW,
                screenH,
                0,
                0,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                Gravity.TOP or Gravity.START,
                newMode == FloatingMode.FULLSCREEN,
            )

            FloatingMode.RESULT_DISPLAY -> {
                val ball = (state.ballSize.value.value * density).toInt()
                val ballCenter = params.x + params.width / 2
                val right = ballCenter > screenW / 2
                Target(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    if (right) screenW - (params.x + params.width) else params.x,
                    params.y,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    if (right) Gravity.TOP or Gravity.END else Gravity.TOP or Gravity.START,
                    false,
                )
            }
        }

        fun apply() {
            if (composeView !== view) return
            updateViewLayout { p ->
                p.width = target.width
                p.height = target.height
                p.x = target.x
                p.y = target.y
                p.flags = target.flags
                p.gravity = target.gravity
                p.softInputMode = softInputModeFor(newMode)
                applyCutout(p, newMode == FloatingMode.FULLSCREEN || newMode == FloatingMode.SCREEN_OCR)
                applyBlur(p, target.blur)
                state.x = p.x
                state.y = p.y
            }
        }

        val isToBall = newMode == FloatingMode.BALL || newMode == FloatingMode.VOICE_BALL
        val isFromBall = oldMode == FloatingMode.BALL || oldMode == FloatingMode.VOICE_BALL
        if (isToBall && !isFromBall) {
            handler.postDelayed({ apply(); state.isTransitioning = false }, 150)
        } else if (isFromBall && !isToBall) {
            state.ballExploding.value = true
            handler.postDelayed({
                apply()
                state.ballExploding.value = false
                state.isTransitioning = false
            }, 100)
        } else {
            apply()
            handler.postDelayed({ state.isTransitioning = false }, if (isToBall) 250 else 0)
        }
    }

    private fun onMove(dx: Float, dy: Float, scale: Float) {
        if (state.currentMode.value == FloatingMode.FULLSCREEN ||
            state.currentMode.value == FloatingMode.SCREEN_OCR
        ) {
            return
        }
        updateViewLayout { params ->
            val sensitivity =
                if (state.currentMode.value == FloatingMode.BALL ||
                    state.currentMode.value == FloatingMode.VOICE_BALL
                ) 1f else scale
            params.x += (dx * sensitivity).toInt()
            params.y += (dy * sensitivity).toInt()
            when (state.currentMode.value) {
                FloatingMode.BALL, FloatingMode.VOICE_BALL -> {
                    val ball = (state.ballSize.value.value * density).toInt()
                    val minVisible = ball / 2
                    params.x = params.x.coerceIn(-ball + minVisible, screenW - minVisible)
                    params.y = params.y.coerceIn(0, contentH - minVisible)
                }
                FloatingMode.RESULT_DISPLAY -> Unit
                else -> {
                    val w = (state.windowWidth.value.value * density * scale).toInt()
                    val h = (state.windowHeight.value.value * density * scale).toInt()
                    params.x = params.x.coerceIn(-(w * 2 / 3), screenW - w / 2)
                    params.y = params.y.coerceIn(0, contentH - h * 2 / 3)
                }
            }
            state.x = params.x
            state.y = params.y
        }
    }

    private fun updateWindowSize() {
        if (state.currentMode.value != FloatingMode.WINDOW) return
        updateViewLayout { params ->
            val scale = state.windowScale.value
            params.width = (state.windowWidth.value.value * density * scale).toInt()
            params.height = (state.windowHeight.value.value * density * scale).toInt()
        }
    }

    private fun updateViewLayout(configure: (WindowManager.LayoutParams) -> Unit) {
        composeView?.let { view ->
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@let
            configure(params)
            runCatching { windowManager.updateViewLayout(view, params) }
                .onFailure { Log.w(TAG, "updateViewLayout failed: ${it.message}") }
        }
    }

    fun setFocusable(needsFocus: Boolean) {
        Log.i(TAG, "setFocusable($needsFocus) view=${composeView != null} mode=${state.currentMode.value}")
        val view = composeView ?: return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        pendingImeRunnable?.let { handler.removeCallbacks(it) }
        pendingImeRunnable = null

        if (needsFocus) {
            updateViewLayout { params ->
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                if (state.currentMode.value == FloatingMode.WINDOW) {
                    params.flags = params.flags or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                }
                params.softInputMode = softInputModeFor(state.currentMode.value)
            }
            scheduleIme(view, imm)
        } else {
            view.findFocus()?.clearFocus()
            view.clearFocus()
            runCatching { imm.hideSoftInputFromWindow(view.windowToken, 0) }
            updateViewLayout { params ->
                // WINDOW 的窗口常驻可获焦（createLayoutParams 里没有 NOT_FOCUSABLE），
                // 这里只收键盘，不再把 flag 加回去，否则下一次点输入框又要靠回调救场。
                if (state.currentMode.value != FloatingMode.FULLSCREEN &&
                    state.currentMode.value != FloatingMode.SCREEN_OCR &&
                    state.currentMode.value != FloatingMode.WINDOW
                ) {
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    params.flags = params.flags and
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
                    params.flags = params.flags and
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
                }
                params.softInputMode = softInputModeFor(state.currentMode.value)
            }
        }
    }

    private fun scheduleIme(
        view: View,
        imm: InputMethodManager,
        retry: Int = 0,
    ) {
        lateinit var runnable: Runnable
        runnable = Runnable {
            if (pendingImeRunnable !== runnable) return@Runnable
            if (composeView !== view || !isViewAdded) {
                pendingImeRunnable = null
                return@Runnable
            }
            if (!view.isAttachedToWindow || view.windowToken == null) {
                if (retry >= MAX_IME_RETRIES) {
                    pendingImeRunnable = null
                    return@Runnable
                }
                scheduleIme(view, imm, retry + 1)
                return@Runnable
            }
            view.requestFocus()
            val host = view.findFocus()?.takeIf {
                it.isAttachedToWindow && it.windowToken != null && it.onCheckIsTextEditor()
            }
            if (host == null) {
                if (retry >= MAX_IME_RETRIES) {
                    pendingImeRunnable = null
                    return@Runnable
                }
                scheduleIme(view, imm, retry + 1)
                return@Runnable
            }
            pendingImeRunnable = null
            runCatching { imm.showSoftInput(host, InputMethodManager.SHOW_IMPLICIT) }
        }
        pendingImeRunnable = runnable
        handler.postDelayed(runnable, if (retry == 0) 200L else 50L)
    }

    private fun cancelFocusAndHideIme() {
        composeView?.let { view ->
            view.findFocus()?.clearFocus()
            view.clearFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            runCatching { imm.hideSoftInputFromWindow(view.windowToken, 0) }
        }
        pendingImeRunnable?.let { handler.removeCallbacks(it) }
        pendingImeRunnable = null
        updateViewLayout { params ->
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
    }

    /** 跟主界面 SettingsStore 的 theme_mode/accent_id 走，不在悬浮窗里另搞一套主题。 */
    @Composable
    private fun themeIsDark(): Boolean {
        val prefs = context.getSharedPreferences("dsh", Context.MODE_PRIVATE)
        return when (prefs.getString("theme_mode", "system")) {
            "light" -> false
            "dark" -> true
            else -> isSystemInDarkTheme()
        }
    }

    private fun themeAccent(): String =
        context.getSharedPreferences("dsh", Context.MODE_PRIVATE)
            .getString("accent_id", "blue") ?: "blue"

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private companion object {
        const val TAG = "FloatingWindowManager"
        const val PRIVATE_FLAG_NO_MOVE_ANIMATION = 0x00000040
        const val MAX_IME_RETRIES = 4
    }
}

/**
 * Service 里的 ComposeView 需要 LifecycleOwner + ViewModelStoreOwner + SavedStateRegistryOwner；
 * Operit 用 ServiceLifecycleOwner，这里给一个等价的。
 */
private class FloatingLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun start() {
        runCatching { savedState.performRestore(null) }
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
