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
import android.view.MotionEvent
import android.view.View
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    fun canDraw(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(context)

    fun show(context: Context, displayId: Int, agentId: String? = null): Boolean =
        get(context, agentId).show(displayId)

    fun hide(agentId: String? = null) {
        instances.remove(key(agentId))?.hide()
    }

    fun hideAll() {
        val all = instances.values.toList()
        instances.clear()
        all.forEach { it.hide() }
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

private class OverlayInstance(
    private val context: Context,
    private val agentId: String,
) {
    private val main = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    /**
     * 必须用 DisplayManager 的 DEFAULT_DISPLAY：应用进程在虚拟屏里跑过 VirtualHomeActivity 后，
     * wm.defaultDisplay / resources.displayMetrics 会变成虚拟屏尺寸（1088x1920），
     * 拿它算预览窗会把 40% 屏宽算成 547px，窗口直接盖住半个手机屏。
     */
    private val realMetrics = DisplayMetrics().also { metrics ->
        val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        (dm?.getDisplay(Display.DEFAULT_DISPLAY) ?: wm.defaultDisplay).getRealMetrics(metrics)
    }
    private val screenW = realMetrics.widthPixels
    private val screenH = realMetrics.heightPixels
    private val density = realMetrics.density
    private val panelWidthPx = (56 * density).roundToInt()

    private var owner: OverlayLifecycleOwner? = null
    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var surface: ShowerSurfaceView? = null
    private var displayId: Int? = null

    private var isFullscreen by mutableStateOf(false)
    private var isSnapped by mutableStateOf(false)
    private var snappedToRight by mutableStateOf(true)
    private var controlsVisible by mutableStateOf(false)
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
    /** 贴边前的小窗位置，恢复时用（不能拿 lastX/lastY：贴边动画会把它们改成把手坐标）。 */
    private var snapRestoreX = 0
    private var snapRestoreY = 0
    private var hasShown = false

    val visible: Boolean get() = view != null

    fun show(displayId: Int): Boolean {
        if (!VirtualScreenOverlay.canDraw(context)) return false
        this.displayId = displayId
        currentPackage = VirtualScreenSession.appPackage
        if (view != null) {
            view?.visibility = View.VISIBLE
            return true
        }
        ensureView()
        if (view == null) return false
        val (videoW, videoH) = previewVideoSize()
        if (!hasShown) {
            // 默认放右下角：悬浮窗（WINDOW/TOP_BAR 模式）通常在屏幕上半部分，预览窗贴着它会把
            // 输入框/关闭键盖住；右下角离浮窗最远，竖屏也顺手可拖。
            val margin = (8 * density).roundToInt()
            lastW = videoW + currentPanelWidth()
            lastH = videoH
            lastX = (screenW - lastW - margin).coerceAtLeast(0)
            lastY = (screenH - videoH - navBarHeight() - margin).coerceAtLeast(statusBarHeight())
            hasShown = true
        }
        applyParams(animate = false)
        return true
    }

    private fun ensureView() {
        if (view != null) return
        try {
            val lifecycle = OverlayLifecycleOwner().apply { start() }
            owner = lifecycle
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                // 必须带 LAYOUT_IN_SCREEN：TYPE_APPLICATION_OVERLAY + TOP|START 的原点默认在状态栏下方，
                // 小窗会整体下移 64px，全屏 [0,0][720,1570] 会变成 [0,64][720,1634] 被裁掉底部。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = lastX
                y = lastY
            }
            val compose = ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setViewTreeLifecycleOwner(lifecycle)
                setViewTreeViewModelStoreOwner(lifecycle)
                setViewTreeSavedStateRegistryOwner(lifecycle)
                setContent {
                    DshTheme(darkTheme = false) {
                        OverlayCard()
                    }
                }
            }
            wm.addView(compose, params)
            view = compose
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
     * 预览窗视频区尺寸：高度取可用屏高的 40%，宽度按**视频真实宽高比**算。
     *
     * 旧实现宽高各取 40%，1088×1920 的虚拟屏会被塞进 288×602 的框里横向压扁（QQ 文字变窄）；
     * 视频尺寸还没上报时退回 40% 屏宽。
     */
    private fun previewVideoSize(): Pair<Int, Int> {
        val targetH = ((screenH - statusBarHeight()) * 0.4f).roundToInt()
        val vs = videoSize()
        val targetW = if (vs != null && vs.first > 0 && vs.second > 0) {
            (targetH.toFloat() * vs.first / vs.second).roundToInt()
        } else {
            (screenW * 0.4f).roundToInt()
        }
        return targetW to targetH
    }

    /**
     * 自动化条只有真的显示时才占 56dp。旧实现无条件留一条空面板，用户看到的就是预览窗左边
     * 一条「大黑边」，还把视频挤窄了（2026-11-09 用户反馈）。
     */
    private fun currentPanelWidth(): Int = if (automationVisible) panelWidthPx else 0

    private fun navBarHeight(): Int {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    private fun applyParams(animate: Boolean) {
        val p = params ?: return
        val v = view ?: return
        val (videoW, videoH) = previewVideoSize()
        val panelWidth = currentPanelWidth()
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
                targetY = lastY.coerceIn(statusBarHeight(), (screenH - targetH).coerceAtLeast(0))
            }
            else -> {
                targetX = lastX.coerceIn(0, (screenW - (videoW + panelWidth)).coerceAtLeast(0))
                targetY = lastY.coerceIn(
                    statusBarHeight(),
                    (screenH - videoH).coerceAtLeast(statusBarHeight()),
                )
                targetW = videoW + panelWidth
                targetH = videoH
            }
        }
        android.util.Log.d(
            "VirtualScreenOverlay",
            "apply animate=$animate full=$isFullscreen snap=$isSnapped target=$targetX,$targetY ${targetW}x$targetH screen=${screenW}x$screenH",
        )
        if (!animate) {
            p.x = targetX
            p.y = targetY
            p.width = targetW
            p.height = targetH
            update()
            return
        }
        animator?.cancel()
        val sx = p.x
        val sy = p.y
        val sw = p.width
        val sh = p.height
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
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
        // 只有普通小窗的位置才算「上次位置」；全屏/贴边的目标坐标不能覆盖它，
        // 否则退出全屏或从把手恢复时会跳到屏幕边角（Operit 会恢复贴边前的位置）。
        if (!isSnapped && !isFullscreen) {
            lastX = targetX
            lastY = targetY
            lastW = targetW
            lastH = targetH
        }
    }

    private fun update() {
        val p = params ?: return
        val v = view ?: return
        runCatching { wm.updateViewLayout(v, p) }
    }

    fun hide() {
        val v = view
        val o = owner
        view = null
        params = null
        surface = null
        owner = null
        displayId = null
        currentPackage = null
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
        // 自动化条占 56dp：窗口要变宽，否则会把视频挤掉一半。
        main.post { applyParams(animate = true) }
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
        main.post { applyParams(animate = true) }
    }

    fun setShowerBorderVisible(visible: Boolean) {
        rainbowBorder = visible
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) controlsVisible = false
        isSnapped = false
        applyParams(animate = true)
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
        val p = params ?: return
        if (isSnapped) {
            lastY = (p.y + dy.toInt()).coerceIn(
                statusBarHeight(),
                (screenH - p.height).coerceAtLeast(statusBarHeight()),
            )
            applyParams(animate = false)
            return
        }
        lastX = (p.x + dx.toInt()).coerceIn(0, (screenW - p.width).coerceAtLeast(0))
        lastY = (p.y + dy.toInt()).coerceIn(
            statusBarHeight(),
            (screenH - p.height).coerceAtLeast(statusBarHeight()),
        )
        applyParams(animate = false)
    }

    @Composable
    private fun OverlayCard() {
        var overlaySize by remember { mutableStateOf(IntSize.Zero) }
        var hasDisplay by remember { mutableStateOf(videoSize() != null) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            while (true) {
                val ready = videoSize() != null
                if (hasDisplay && !ready) {
                    xin.dshaa.nativeapp.chat.ChatMirror.stop()
                }
                if (!hasDisplay && ready) {
                    // 视频尺寸晚到：按真实宽高比重算窗口大小（show() 时可能还没上报）。
                    applyParams(animate = false)
                }
                hasDisplay = ready
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
                        detectDragGestures { change, drag ->
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
                        // 全屏时如果流断了，至少要能点出来关掉：否则整屏都是这个浮层的触摸区，
                        // 用户会觉得「整个手机点不动了」（2026-11-09 用户反馈）。
                        Text(
                            "关闭预览",
                            color = Color(0xFF7FB2FF),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 10.dp).clickable { hide() },
                        )
                    }
                }
            } else {
                // 贴边收起时**不能**把 ShowerSurfaceView 从组合里移除：那会 surfaceDestroyed →
                // setBinaryHandler(null)，恢复时新解码器只有「最近 120 帧」、没有 SPS/PPS，
                // 预览永久黑屏（真机 2026-11-09 复现）。缩到 1dp 让渲染管线活着。
                Row(
                    Modifier
                        .then(if (isSnapped) Modifier.size(1.dp) else Modifier.fillMaxSize())
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
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                ShowerSurfaceView(ctx).also { sv ->
                                    sv.bindController(VirtualScreenSession.controller)
                                    surface = sv
                                    if (isFullscreen) attachTouch(sv)
                                }
                            },
                            update = { sv ->
                                surface = sv
                                runCatching { sv.bindController(VirtualScreenSession.controller) }
                                if (isFullscreen) {
                                    attachTouch(sv)
                                } else {
                                    sv.setOnTouchListener(null)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
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
                            FullscreenControls()
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

    private fun attachTouch(sv: ShowerSurfaceView) {
        sv.setOnTouchListener { v, event ->
            val size = videoSize() ?: return@setOnTouchListener false
            val x = (event.x / v.width.coerceAtLeast(1) * size.first).toInt()
                .coerceIn(0, size.first - 1)
            val y = (event.y / v.height.coerceAtLeast(1) * size.second).toInt()
                .coerceIn(0, size.second - 1)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                        VirtualScreenSession.controller.touchDown(x, y)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                        VirtualScreenSession.controller.touchMove(x, y)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                        VirtualScreenSession.controller.touchUp(x, y)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    @Composable
    private fun BoxScope.FullscreenControls() {
        Row(
            Modifier
                .align(Alignment.TopEnd)
                // 全屏时窗口铺到 [0,0]（LAYOUT_IN_SCREEN），状态栏会盖在最上层：
                // 控件必须避让 status_bar_height，否则点了是状态栏的点击区（实测点不动）。
                .padding(top = (statusBarHeight() / density).dp + 8.dp, end = 12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    val restoreX = lastX
                    val restoreY = lastY
                    toggleFullscreen()
                    snap()
                    snapRestoreX = restoreX
                    snapRestoreY = restoreY
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Outlined.Minimize, "贴边收起", tint = Color.White, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { toggleFullscreen() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.FullscreenExit, "退出全屏", tint = Color.White, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { hide() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, "关闭", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }

    @Composable
    private fun SmallControls() {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)),
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
                IconButton(onClick = { hide() }) {
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
