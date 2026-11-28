package xin.dshaa.nativeapp.device.shower

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Minimize
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
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
import com.ai.assistance.showerclient.ui.ShowerSurfaceView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import xin.dshaa.ui.design.theme.DshTheme

/**
 * 虚拟屏浮层（Operit `VirtualDisplayOverlay.kt` 1296 行，docs/76 §十一 V1-V23）。
 *
 * 关键能力：40% 屏宽视频 + 56dp 自动化栏、贴边收起、全屏、App 图标把手、控制层 3s
 * 自动隐藏、触摸转发、当前帧截图、自动化步骤/暂停/退出、Shower 断流提示。
 *
 * 仍保留原生单例入口（当前 ShowerController 是进程级单例）；内部按 agentId 建实例，
 * 为以后多 agent 留结构。单 sink 约束：VirtualScreenPage 打开前会 hide()，避免两个
 * ShowerSurfaceView 抢同一条视频流。
 */
object VirtualScreenOverlay {

    private val instances = java.util.concurrent.ConcurrentHashMap<String, OverlayInstance>()

    val visible: Boolean
        get() = instances.values.any { it.visible }

    private fun key(agentId: String?): String =
        agentId?.takeIf { it.isNotBlank() } ?: "default"

    private fun get(context: Context, agentId: String? = null): OverlayInstance =
        instances.getOrPut(key(agentId)) {
            OverlayInstance(context.applicationContext, key(agentId))
        }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    fun canDraw(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(context)

    fun show(context: Context, displayId: Int, agentId: String? = null): Boolean {
        if (!canDraw(context)) return false
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return get(context, agentId).show(displayId)
        }
        val appCtx = context.applicationContext
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        mainHandler.post {
            try {
                ok = get(appCtx, agentId).show(displayId)
            } finally {
                latch.countDown()
            }
        }
        latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        return ok
    }

    fun hide(agentId: String? = null) {
        val inst = instances.remove(key(agentId)) ?: return
        runOnMain { inst.hide() }
    }

    fun hideAll() {
        val all = instances.values.toList()
        instances.clear()
        runOnMain { all.forEach { it.hide() } }
    }

    fun updateCurrentAppPackageName(packageName: String?, agentId: String? = null) {
        instances[key(agentId)]?.updateCurrentAppPackageName(packageName)
    }

    suspend fun captureCurrentFramePng(agentId: String? = null): ByteArray? =
        instances[key(agentId)]?.captureCurrentFramePng()

    fun showAutomationControls(
        totalSteps: Int,
        initialStatus: String,
        onTogglePauseResume: (Boolean) -> Unit,
        onExit: () -> Unit,
        agentId: String? = null,
    ) {
        instances[key(agentId)]?.showAutomationControls(
            totalSteps, initialStatus, onTogglePauseResume, onExit,
        )
    }

    fun updateAutomationProgress(
        currentStep: Int,
        totalSteps: Int,
        statusText: String,
        agentId: String? = null,
    ) {
        instances[key(agentId)]?.updateAutomationProgress(currentStep, totalSteps, statusText)
    }

    fun hideAutomationControls(agentId: String? = null) {
        instances[key(agentId)]?.hideAutomationControls()
    }

    fun setShowerBorderVisible(visible: Boolean, agentId: String? = null) {
        instances[key(agentId)]?.setShowerBorderVisible(visible)
    }
}

private sealed interface OverlayTouchCmd {
    data class Down(val x: Int, val y: Int) : OverlayTouchCmd
    data class Move(val x: Int, val y: Int) : OverlayTouchCmd
    data class Up(val x: Int, val y: Int) : OverlayTouchCmd
}

private class OverlayInstance(
    private val context: Context,
    private val agentId: String,
) {
    private val main = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences("dsh", Context.MODE_PRIVATE)
    private val prefKeyX = "vs_overlay_x_$agentId"
    private val prefKeyY = "vs_overlay_y_$agentId"

    /**
     * 物理主屏尺寸与密度：每次 show / applyParams 动态刷新，避免：
     * 1. VirtualHomeActivity 在虚拟屏启动后污染 context.resources 的 density；
     * 2. 横竖屏旋转或折叠屏展开后仍沿用构造时的旧宽高。
     */
    private var screenW = 1080
    private var screenH = 1920
    private var density = 2f
    private var panelWidthPx = 112

    private fun refreshDisplayMetrics() {
        val metrics = DisplayMetrics()
        val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        @Suppress("DEPRECATION")
        val defaultDisplay = dm?.getDisplay(Display.DEFAULT_DISPLAY) ?: wm.defaultDisplay
        @Suppress("DEPRECATION")
        defaultDisplay?.getRealMetrics(metrics)
        if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            screenW = metrics.widthPixels
            screenH = metrics.heightPixels
            density = metrics.density.coerceAtLeast(1f)
            panelWidthPx = (56 * density).roundToInt()
        }
    }

    init {
        refreshDisplayMetrics()
    }

    private var owner: OverlayLifecycleOwner? = null
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var surface: ShowerSurfaceView? = null
    private var displayId: Int? = null
    private var touchBoundFullscreen: Boolean? = null

    private var touchScope: CoroutineScope? = null
    private var touchChannel: Channel<OverlayTouchCmd>? = null

    private var isFullscreen by mutableStateOf(false)
    private var isSnapped by mutableStateOf(false)
    private var snappedToRight by mutableStateOf(true)
    private var controlsVisible by mutableStateOf(false)
    private var fullscreenToolbarExpanded by mutableStateOf(true)
    private var automationVisible by mutableStateOf(false)
    private var automationCurrent by mutableStateOf<Int?>(null)
    private var automationTotal by mutableStateOf<Int?>(null)
    private var automationPaused by mutableStateOf(false)
    private var automationToggle: ((Boolean) -> Unit)? = null
    private var automationExit: (() -> Unit)? = null
    private var currentPackage by mutableStateOf<String?>(null)
    private var rainbowBorder by mutableStateOf(false)
    private var animator: ValueAnimator? = null

    private var lastX = 0
    private var lastY = 0
    private var lastW = 0
    private var lastH = 0
    private var dragAccX = 0f
    private var dragAccY = 0f
    /** 贴边前的小窗位置，恢复时用（不能拿 lastX/lastY：贴边动画会把它们改成把手坐标）。 */
    private var snapRestoreX = 0
    private var snapRestoreY = 0
    private var hasShown = false

    val visible: Boolean get() = view != null

    private fun startTouchDispatcher() {
        if (touchScope != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ch = Channel<OverlayTouchCmd>(Channel.UNLIMITED)
        touchScope = scope
        touchChannel = ch
        scope.launch {
            for (cmd in ch) {
                when (cmd) {
                    is OverlayTouchCmd.Down -> VirtualScreenSession.controller.touchDown(cmd.x, cmd.y)
                    is OverlayTouchCmd.Move -> VirtualScreenSession.controller.touchMove(cmd.x, cmd.y)
                    is OverlayTouchCmd.Up -> VirtualScreenSession.controller.touchUp(cmd.x, cmd.y)
                }
            }
        }
    }

    private fun stopTouchDispatcher() {
        touchChannel?.close()
        touchChannel = null
        touchScope?.cancel()
        touchScope = null
    }

    private fun savePosition(x: Int, y: Int) {
        prefs.edit()
            .putInt(prefKeyX, x)
            .putInt(prefKeyY, y)
            .apply()
    }

    fun show(displayId: Int): Boolean {
        if (!VirtualScreenOverlay.canDraw(context)) return false
        refreshDisplayMetrics()
        startTouchDispatcher()
        this.displayId = displayId
        currentPackage = VirtualScreenSession.appPackage
        if (view != null) {
            view?.visibility = View.VISIBLE
            applyParams(animate = false)
            return true
        }
        val (videoW, videoH) = previewVideoSize()
        if (!hasShown) {
            val topSafe = topInsetPx()
            val bottomSafe = bottomInsetPx()
            val usableH = (screenH - topSafe - bottomSafe).coerceAtLeast(videoH)
            lastW = videoW + currentPanelWidth()
            lastH = videoH
            val maxX = (screenW - lastW).coerceAtLeast(0)
            val maxY = (screenH - bottomSafe - videoH).coerceAtLeast(topSafe)
            // 优先恢复上次用户拖拽停放的位置；首次默认居中偏上，避免总死板地挤在右下角挡手势/输入栏。
            if (prefs.contains(prefKeyX) && prefs.contains(prefKeyY)) {
                lastX = prefs.getInt(prefKeyX, 0).coerceIn(0, maxX)
                lastY = prefs.getInt(prefKeyY, topSafe).coerceIn(topSafe, maxY)
            } else {
                lastX = ((screenW - lastW) / 2).coerceIn(0, maxX)
                lastY = (topSafe + ((usableH - videoH) * 0.35f).roundToInt()).coerceIn(topSafe, maxY)
            }
            hasShown = true
        }
        ensureView()
        if (view == null) return false
        applyParams(animate = false)
        return true
    }

    private fun ensureView() {
        if (view != null) return
        try {
            val lifecycle = OverlayLifecycleOwner().apply { start() }
            owner = lifecycle
            val initialW = lastW.takeIf { it > 0 } ?: WindowManager.LayoutParams.WRAP_CONTENT
            val initialH = lastH.takeIf { it > 0 } ?: WindowManager.LayoutParams.WRAP_CONTENT
            params = WindowManager.LayoutParams(
                initialW,
                initialH,
                overlayType(),
                // 必须带 LAYOUT_IN_SCREEN：TYPE_APPLICATION_OVERLAY + TOP|START 的原点默认在状态栏下方；
                // 非全屏态保持 FLAG_NOT_FOCUSABLE 不抢主屏焦点，进入全屏态时动态去掉以接管返回键。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = lastX
                y = lastY
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            val compose = ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    DshTheme(darkTheme = false) {
                        OverlayCard()
                    }
                }
            }
            val container = object : android.widget.FrameLayout(context) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (isFullscreen && event.keyCode == KeyEvent.KEYCODE_BACK) {
                        if (event.action == KeyEvent.ACTION_UP) {
                            touchScope?.launch { VirtualScreenSession.controller.key(KeyEvent.KEYCODE_BACK) }
                        }
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }
            }.apply {
                isFocusableInTouchMode = true
                setViewTreeLifecycleOwner(lifecycle)
                setViewTreeViewModelStoreOwner(lifecycle)
                setViewTreeSavedStateRegistryOwner(lifecycle)
                addView(
                    compose,
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            wm.addView(container, params)
            view = container
        } catch (t: Throwable) {
            android.util.Log.e("VirtualScreenOverlay", "ensureView failed", t)
            owner?.destroy()
            owner = null
            view = null
            params = null
        }
    }

    private fun videoSize(): Pair<Int, Int>? =
        VirtualScreenSession.controller.getVideoSize()

    /**
     * 预览窗视频区尺寸：高度取可用屏高的 40%，宽度按**视频真实宽高比**算，同时限制宽度不超过屏宽 78%。
     */
    private fun previewVideoSize(): Pair<Int, Int> {
        val usableH = (screenH - topInsetPx() - bottomInsetPx()).coerceAtLeast(300)
        var targetH = (usableH * 0.40f).roundToInt()
        val vs = videoSize()
        var targetW = if (vs != null && vs.first > 0 && vs.second > 0) {
            (targetH.toFloat() * vs.first / vs.second).roundToInt()
        } else {
            (screenW * 0.42f).roundToInt()
        }
        val maxW = (screenW * 0.78f).roundToInt()
        if (targetW > maxW && targetW > 0) {
            val scale = maxW.toFloat() / targetW
            targetW = maxW
            targetH = (targetH * scale).roundToInt()
        }
        return targetW.coerceAtLeast(120) to targetH.coerceAtLeast(120)
    }

    private fun currentPanelWidth(): Int = if (automationVisible) panelWidthPx else 0

    /**
     * 物理主屏顶部安全留白（状态栏 + 刘海/挖孔）：
     * API 30+ 直接读 currentWindowMetrics.windowInsets，彻底避开 VirtualHomeActivity 污染的 resources.density；
     * 低版本按物理 density / resources.density 校正比例。
     */
    private fun topInsetPx(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = runCatching {
                wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout(),
                ).top
            }.getOrDefault(0)
            if (insets > 0) return insets
        }
        val raw = statusBarHeight()
        val resDensity = context.resources.displayMetrics.density.coerceAtLeast(0.5f)
        return (raw * (density / resDensity)).roundToInt().coerceAtLeast((24 * density).roundToInt())
    }

    /** 物理主屏底部安全留白（导航栏/手势条）。 */
    private fun bottomInsetPx(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = runCatching {
                wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout(),
                ).bottom
            }.getOrDefault(0)
            if (insets >= 0) return insets
        }
        val raw = navBarHeight()
        val resDensity = context.resources.displayMetrics.density.coerceAtLeast(0.5f)
        return (raw * (density / resDensity)).roundToInt().coerceAtLeast(0)
    }

    private fun navBarHeight(): Int {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    private fun applyParams(animate: Boolean) {
        refreshDisplayMetrics()
        val p = params ?: return
        val v = view ?: return
        val (videoW, videoH) = previewVideoSize()
        val panelWidth = currentPanelWidth()
        val topSafe = topInsetPx()
        val bottomSafe = bottomInsetPx()
        val targetX: Int
        val targetY: Int
        val targetW: Int
        val targetH: Int
        when {
            isFullscreen -> {
                targetX = 0
                targetY = 0
                targetW = screenW
                targetH = screenH
            }
            isSnapped -> {
                val visibleW = (36 * density).roundToInt()
                val extra = (12 * density).roundToInt()
                targetW = visibleW + extra
                targetH = (48 * density).roundToInt()
                targetX = if (snappedToRight) screenW - visibleW else -extra
                targetY = lastY.coerceIn(topSafe, (screenH - bottomSafe - targetH).coerceAtLeast(topSafe))
            }
            else -> {
                targetW = videoW + panelWidth
                targetH = videoH
                targetX = lastX.coerceIn(0, (screenW - targetW).coerceAtLeast(0))
                targetY = lastY.coerceIn(
                    topSafe,
                    (screenH - bottomSafe - targetH).coerceAtLeast(topSafe),
                )
            }
        }
        // 全屏态允许接收返回键；非全屏态保持 FLAG_NOT_FOCUSABLE 不影响底层 App 操作
        p.flags = if (isFullscreen) {
            (p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            p.flags or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        android.util.Log.d(
            "VirtualScreenOverlay",
            "apply animate=$animate full=$isFullscreen snap=$isSnapped target=$targetX,$targetY ${targetW}x$targetH screen=${screenW}x$screenH",
        )
        if (!isSnapped && !isFullscreen) {
            lastX = targetX
            lastY = targetY
            lastW = targetW
            lastH = targetH
        }
        animator?.cancel()
        animator = null
        if (!animate) {
            p.x = targetX
            p.y = targetY
            p.width = targetW
            p.height = targetH
            update()
            if (isFullscreen) v.requestFocus()
            return
        }
        val sx = p.x
        val sy = p.y
        val sw = p.width
        val sh = p.height
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            addUpdateListener { a ->
                val f = a.animatedFraction
                p.x = (sx + (targetX - sx) * f).toInt()
                p.y = (sy + (targetY - sy) * f).toInt()
                p.width = (sw + (targetW - sw) * f).toInt()
                p.height = (sh + (targetH - sh) * f).toInt()
                update()
            }
            start()
        }
    }

    private fun update() {
        val p = params ?: return
        val v = view ?: return
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun closeSelf() {
        VirtualScreenOverlay.hide(agentId)
    }

    fun hide() {
        animator?.cancel()
        animator = null
        stopTouchDispatcher()
        val v = view
        val o = owner
        view = null
        params = null
        surface = null
        owner = null
        displayId = null
        currentPackage = null
        touchBoundFullscreen = null
        isFullscreen = false
        isSnapped = false
        controlsVisible = false
        fullscreenToolbarExpanded = true
        automationVisible = false
        if (v != null) runCatching { wm.removeView(v) }
        if (o != null) o.destroy()
    }

    fun updateCurrentAppPackageName(packageName: String?) {
        currentPackage = packageName
    }

    suspend fun captureCurrentFramePng(): ByteArray? = surface?.captureCurrentFramePng()

    fun showAutomationControls(
        totalSteps: Int,
        initialStatus: String,
        onTogglePauseResume: (Boolean) -> Unit,
        onExit: () -> Unit,
    ) {
        automationTotal = totalSteps
        automationCurrent = 1
        automationPaused = false
        automationToggle = onTogglePauseResume
        automationExit = onExit
        automationVisible = true
        main.post { applyParams(animate = !isFullscreen) }
    }

    fun updateAutomationProgress(currentStep: Int, totalSteps: Int, statusText: String) {
        automationTotal = totalSteps
        automationCurrent = if (currentStep <= 0) 1 else currentStep
    }

    fun hideAutomationControls() {
        automationVisible = false
        automationToggle = null
        automationExit = null
        automationCurrent = null
        automationTotal = null
        automationPaused = false
        main.post { applyParams(animate = !isFullscreen) }
    }

    fun setShowerBorderVisible(visible: Boolean) {
        rainbowBorder = visible
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            controlsVisible = false
            fullscreenToolbarExpanded = true
        }
        isSnapped = false
        // 全屏切换跳过 60Hz WindowManager 缩放动画：SurfaceView 在独立合成层，高频改窗体宽高极易撕裂/闪黑
        applyParams(animate = false)
    }

    private fun minimizeFromFullscreen() {
        val restoreX = lastX
        val restoreY = lastY
        val restoreW = lastW.takeIf { it > 0 } ?: (previewVideoSize().first + currentPanelWidth())
        isFullscreen = false
        controlsVisible = false
        snapRestoreX = restoreX
        snapRestoreY = restoreY
        snappedToRight = (restoreX + restoreW / 2) >= screenW / 2
        isSnapped = true
        applyParams(animate = false)
    }

    private fun snap() {
        if (isSnapped || isFullscreen) return
        controlsVisible = false
        val p = params ?: return
        snapRestoreX = p.x
        snapRestoreY = p.y
        val center = p.x + p.width / 2
        snappedToRight = center >= screenW / 2
        isSnapped = true
        applyParams(animate = true)
    }

    private fun restoreFromSnap() {
        isSnapped = false
        lastX = snapRestoreX
        lastY = snapRestoreY
        applyParams(animate = true)
    }

    private fun moveWindow(dx: Float, dy: Float) {
        if (isFullscreen) return
        animator?.cancel()
        animator = null
        val p = params ?: return
        dragAccX += dx
        dragAccY += dy
        val stepX = dragAccX.toInt()
        val stepY = dragAccY.toInt()
        if (stepX == 0 && stepY == 0) return
        dragAccX -= stepX
        dragAccY -= stepY
        val topSafe = topInsetPx()
        val bottomSafe = bottomInsetPx()
        if (isSnapped) {
            lastY = (p.y + stepY).coerceIn(
                topSafe,
                (screenH - bottomSafe - p.height).coerceAtLeast(topSafe),
            )
            applyParams(animate = false)
            return
        }
        lastX = (p.x + stepX).coerceIn(0, (screenW - p.width).coerceAtLeast(0))
        lastY = (p.y + stepY).coerceIn(
            topSafe,
            (screenH - bottomSafe - p.height).coerceAtLeast(topSafe),
        )
        applyParams(animate = false)
    }

    @Composable
    private fun OverlayCard() {
        var overlaySize by remember { mutableStateOf(IntSize.Zero) }
        var videoDimensions by remember { mutableStateOf(videoSize()) }
        val hasDisplay = videoDimensions != null
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            while (true) {
                val cur = videoSize()
                val wasReady = videoDimensions != null
                val ready = cur != null
                if (wasReady && !ready) {
                    VirtualScreenRuntime.onStopChat()
                }
                if ((!wasReady && ready) || (cur != null && cur != videoDimensions)) {
                    videoDimensions = cur
                    applyParams(animate = false)
                } else {
                    videoDimensions = cur
                }
                delay(500)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { overlaySize = it }
                .pointerInput(isFullscreen, isSnapped) {
                    if (isSnapped) {
                        detectTapGestures { restoreFromSnap() }
                    } else if (!isFullscreen) {
                        detectDragGestures(
                            onDragEnd = {
                                dragAccX = 0f
                                dragAccY = 0f
                                savePosition(lastX, lastY)
                            },
                            onDragCancel = {
                                dragAccX = 0f
                                dragAccY = 0f
                            },
                        ) { change, drag ->
                            change.consume()
                            controlsVisible = true
                            moveWindow(drag.x, drag.y)
                        }
                    }
                },
        ) {
            if (!hasDisplay) {
                Box(
                    Modifier.fillMaxSize().background(Color(0xFF1A1A1A)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("虚拟屏尚未就绪…", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        Text(
                            "关闭预览",
                            color = Color(0xFF7FB2FF),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 12.dp).clickable { closeSelf() },
                        )
                    }
                }
            } else {
                // 贴边收起时不能把 ShowerSurfaceView 从组合里移除：缩到 1dp 让渲染管线活着。
                val vs = videoDimensions
                val videoAspect = if (vs != null && vs.first > 0 && vs.second > 0) {
                    vs.first.toFloat() / vs.second.toFloat()
                } else {
                    9f / 16f
                }
                Row(
                    Modifier
                        .then(if (isSnapped) Modifier.size(1.dp) else Modifier.fillMaxSize())
                        .background(if (isFullscreen) Color.Black else Color.Transparent)
                        .align(
                            when {
                                !isSnapped -> Alignment.Center
                                snappedToRight -> Alignment.CenterEnd
                                else -> Alignment.CenterStart
                            },
                        ),
                ) {
                    if (!isFullscreen && !isSnapped && automationVisible) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .width(56.dp)
                                .background(Color(0xCC111820)),
                        ) {
                            val step = automationCurrent
                            val total = automationTotal
                            if (step != null && total != null) {
                                AutomationBar(step, total)
                            }
                        }
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pointerInput(isFullscreen, overlaySize) {
                                if (!isFullscreen) {
                                    detectTapGestures { controlsVisible = true }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        // 全屏态避让顶部状态栏/刘海和底部导航条，并严格保持视频宽高比（黑边居中），
                        // 彻底解决 16:9 虚拟屏在 20:9 手机上纵向拉伸变形及底部按钮被系统手势挡住的问题。
                        val topPadDp = if (isFullscreen) (topInsetPx() / density).dp else 0.dp
                        val bottomPadDp = if (isFullscreen) (bottomInsetPx() / density).dp else 0.dp
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(top = topPadDp, bottom = bottomPadDp),
                            contentAlignment = Alignment.Center,
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    ShowerSurfaceView(ctx).also { sv ->
                                        sv.bindController(VirtualScreenSession.controller)
                                        surface = sv
                                        touchBoundFullscreen = isFullscreen
                                        if (isFullscreen) attachTouch(sv) else sv.setOnTouchListener(null)
                                    }
                                },
                                update = { sv ->
                                    surface = sv
                                    if (touchBoundFullscreen != isFullscreen) {
                                        touchBoundFullscreen = isFullscreen
                                        if (isFullscreen) {
                                            attachTouch(sv)
                                        } else {
                                            sv.setOnTouchListener(null)
                                        }
                                    }
                                },
                                modifier = if (isFullscreen) {
                                    Modifier.aspectRatio(videoAspect)
                                } else {
                                    Modifier.fillMaxSize()
                                },
                            )
                        }
                        if (rainbowBorder) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.Transparent)
                                    .padding(3.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                        }
                        if (isFullscreen) {
                            FullscreenControls(
                                onVirtualBack = {
                                    scope.launch { VirtualScreenSession.controller.key(KeyEvent.KEYCODE_BACK) }
                                },
                                onVirtualHome = {
                                    scope.launch { VirtualScreenManager.openHome(context) }
                                },
                            )
                        } else if (!isSnapped && controlsVisible) {
                            SmallControls()
                        }
                    }
                }
                if (isSnapped) SnapHandle()
            }
        }

        LaunchedEffect(controlsVisible) {
            if (controlsVisible) {
                delay(3000)
                controlsVisible = false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(sv: ShowerSurfaceView) {
        var lastMoveX = Int.MIN_VALUE
        var lastMoveY = Int.MIN_VALUE
        var lastMoveMs = 0L
        var fingerDown = false
        var lastDownX = 0
        var lastDownY = 0
        sv.setOnTouchListener { v, event ->
            val size = videoSize() ?: return@setOnTouchListener false
            val vw = v.width.coerceAtLeast(1)
            val vh = v.height.coerceAtLeast(1)
            val x = (event.x / vw * size.first).toInt().coerceIn(0, size.first - 1)
            val y = (event.y / vh * size.second).toInt().coerceIn(0, size.second - 1)
            val ch = touchChannel ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    fingerDown = true
                    lastDownX = x
                    lastDownY = y
                    lastMoveX = x
                    lastMoveY = y
                    lastMoveMs = android.os.SystemClock.uptimeMillis()
                    ch.trySend(OverlayTouchCmd.Down(x, y))
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!fingerDown) return@setOnTouchListener true
                    val now = android.os.SystemClock.uptimeMillis()
                    if (abs(x - lastMoveX) >= 6 || abs(y - lastMoveY) >= 6 || now - lastMoveMs >= 16L) {
                        lastMoveX = x
                        lastMoveY = y
                        lastMoveMs = now
                        lastDownX = x
                        lastDownY = y
                        ch.trySend(OverlayTouchCmd.Move(x, y))
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (fingerDown) {
                        fingerDown = false
                        ch.trySend(OverlayTouchCmd.Up(x, y))
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // 边缘手势/通知栏中断触摸时必须补发 touchUp，否则虚拟屏永久卡在按下态
                    if (fingerDown) {
                        fingerDown = false
                        ch.trySend(OverlayTouchCmd.Up(lastDownX, lastDownY))
                    }
                    true
                }
                else -> false
            }
        }
    }

    @Composable
    private fun BoxScope.FullscreenControls(
        onVirtualBack: () -> Unit,
        onVirtualHome: () -> Unit,
    ) {
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = (topInsetPx() / density).dp + 8.dp, end = 12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.Black.copy(alpha = 0.56f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (fullscreenToolbarExpanded) {
                IconButton(onClick = onVirtualBack, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.ArrowBack, "虚拟屏返回", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onVirtualHome, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Home, "虚拟桌面", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { minimizeFromFullscreen() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Minimize, "贴边收起", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { toggleFullscreen() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.FullscreenExit, "退出全屏", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { closeSelf() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, "关闭", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            IconButton(
                onClick = { fullscreenToolbarExpanded = !fullscreenToolbarExpanded },
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    if (fullscreenToolbarExpanded) Icons.Default.ChevronRight else Icons.Rounded.MoreVert,
                    if (fullscreenToolbarExpanded) "收起工具条" else "展开工具条",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }

    @Composable
    private fun SmallControls() {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = { snap() }) {
                    Icon(Icons.Outlined.Minimize, "贴边", tint = Color.White, modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = { toggleFullscreen() }) {
                    Icon(Icons.Default.Fullscreen, "全屏", tint = Color.White, modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = { closeSelf() }) {
                    Icon(Icons.Default.Close, "关闭", tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
        }
    }

    @Composable
    private fun SnapHandle() {
        val pkg = currentPackage
        val icon by produceState<ImageBitmap?>(null, pkg) {
            value = if (pkg.isNullOrBlank()) null else withContext(Dispatchers.IO) {
                runCatching {
                    context.packageManager.getApplicationIcon(pkg)
                        .toBitmap((30 * density).toInt(), (30 * density).toInt())
                        .asImageBitmap()
                }.getOrNull()
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xD9808080), RoundedCornerShape(12.dp))
                .clickable { restoreFromSnap() },
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(
                    icon!!,
                    contentDescription = "恢复虚拟屏",
                    modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    if (snappedToRight) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
                    "恢复虚拟屏",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }

    @Composable
    private fun AutomationBar(step: Int, total: Int) {
        Column(
            Modifier.padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xE6FFFFFF),
                ),
            ) {
                Column(
                    Modifier.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "$step/$total",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Black,
                    )
                    IconButton(
                        onClick = {
                            automationPaused = !automationPaused
                            automationToggle?.invoke(automationPaused)
                        },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            if (automationPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            if (automationPaused) "继续" else "暂停",
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = { automationExit?.invoke() },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            "退出自动化",
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
}

private class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    fun start() {
        runCatching { saved.performRestore(null) }
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
