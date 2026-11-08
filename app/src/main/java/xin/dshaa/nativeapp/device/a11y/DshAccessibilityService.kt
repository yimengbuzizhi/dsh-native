package xin.dshaa.nativeapp.device.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍服务（Operit 的设备 UI 自动化能力的原生替代）：
 * 读屏（节点树 / 文本 / 包名）、点击、输入、全局动作、手势、截图。
 *
 * 对外只暴露 [A11y] 单例；服务本身保持无状态，避免 Compose/工具层直接依赖 Service 生命周期。
 */
class DshAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        A11y.attach(this)
        Log.i("DshA11y", "accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        A11y.detach(this)
        super.onDestroy()
    }
}

/** 无障碍能力的进程内入口。 */
object A11y {

    @Volatile
    private var service: DshAccessibilityService? = null

    @Volatile
    var screenshotPermissionDenied: Boolean = false
        private set

    fun attach(service: DshAccessibilityService) {
        this.service = service
    }

    fun detach(service: DshAccessibilityService) {
        if (this.service === service) this.service = null
    }

    fun isReady(): Boolean = service != null

    fun root(): AccessibilityNodeInfo? = service?.rootInActiveWindow

    /** 当前前台包名（从根节点推断；服务没连时 null）。 */
    fun foregroundPackage(): String? = root()?.packageName?.toString()

    /**
     * 把当前窗口的节点树 dump 成文本（给模型/调试面板）：
     * `[i] class text="…" desc="…" id=… bounds=[l,t][r,b] clickable …`
     */
    fun dump(maxNodes: Int = 250): String {
        val root = root() ?: return "(accessibility service is not connected)"
        val out = StringBuilder()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var index = 0
        while (queue.isNotEmpty() && index < maxNodes) {
            val (node, depth) = queue.removeFirst()
            out.append('[').append(index++).append("] ")
            out.append("  ".repeat(depth))
            out.append(node.className?.toString()?.substringAfterLast('.') ?: "?")
            val text = node.text?.toString()?.take(120)
            if (!text.isNullOrBlank()) out.append(" text=\"").append(text).append('"')
            val desc = node.contentDescription?.toString()?.take(80)
            if (!desc.isNullOrBlank()) out.append(" desc=\"").append(desc).append('"')
            node.viewIdResourceName?.let { out.append(" id=").append(it.substringAfterLast('/')) }
            val rect = Rect()
            node.getBoundsInScreen(rect)
            out.append(" bounds=[").append(rect.left).append(',').append(rect.top)
                .append("][").append(rect.right).append(',').append(rect.bottom).append(']')
            if (node.isClickable) out.append(" clickable")
            if (node.isEditable) out.append(" editable")
            if (node.isScrollable) out.append(" scrollable")
            out.append('\n')
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it to depth + 1) }
            }
        }
        if (queue.isNotEmpty()) out.append("… (truncated at $maxNodes nodes)\n")
        return out.toString()
    }

    /** 按可见文本 / contentDescription / viewId 查找节点。 */
    fun find(text: String? = null, viewId: String? = null, max: Int = 20): List<AccessibilityNodeInfo> {
        val root = root() ?: return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        while (queue.isNotEmpty() && out.size < max) {
            val node = queue.removeFirst()
            val matchesText = text.isNullOrBlank() ||
                node.text?.toString()?.contains(text) == true ||
                node.contentDescription?.toString()?.contains(text) == true
            val matchesId = viewId.isNullOrBlank() || node.viewIdResourceName?.endsWith(viewId) == true
            if (matchesText && matchesId) out += node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it) }
        }
        return out
    }

    /** 按 dump 的宽度优先序号取节点（dump 与这里必须用同一种遍历顺序）。 */
    fun nodeAt(index: Int): AccessibilityNodeInfo? {
        val root = root() ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var current = 0
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (current == index) return node
            current++
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it) }
        }
        return null
    }

    /** 当前输入焦点上的可编辑节点（`input_text` 用）。 */
    fun focusedEditable(): AccessibilityNodeInfo? {
        val focused = service?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        var node: AccessibilityNodeInfo? = focused
        var depth = 0
        while (node != null && depth < 6) {
            if (node.isEditable) return node
            node = node.parent
            depth++
        }
        return focused.takeIf { it.isEditable }
    }

    /** 点击节点的可点击祖先（文本节点本身通常不可点）。 */
    fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 8) {
            if (current.isClickable) {
                return runCatching {
                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
            }
            current = current.parent
            depth++
        }
        return false
    }

    fun clickText(text: String): Boolean = find(text = text).firstOrNull()?.let { click(it) } ?: false

    fun longClick(node: AccessibilityNodeInfo): Boolean = runCatching {
        node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }.getOrDefault(false)

    fun setText(node: AccessibilityNodeInfo, text: String): Boolean = runCatching {
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }.getOrDefault(false)

    fun globalBack(): Boolean = perform(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)

    fun globalHome(): Boolean = perform(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)

    fun globalRecents(): Boolean = perform(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)

    fun globalNotifications(): Boolean = perform(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)

    private fun perform(action: Int): Boolean = service?.performGlobalAction(action) ?: false

    suspend fun tap(x: Float, y: Float): Boolean = gesture(120) { path -> path.moveTo(x, y) }

    suspend fun longPress(x: Float, y: Float): Boolean = gesture(650) { path -> path.moveTo(x, y) }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean =
        gesture(durationMs) { path -> path.moveTo(x1, y1); path.lineTo(x2, y2) }

    private suspend fun gesture(durationMs: Long, build: (Path) -> Unit): Boolean {
        val service = service ?: return false
        val latch = CountDownLatch(1)
        var ok = false
        val path = Path().also(build)
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        val description = GestureDescription.Builder().addStroke(stroke).build()
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                ok = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }
        service.dispatchGesture(description, callback, null)
        latch.await(5, TimeUnit.SECONDS)
        return ok
    }

    /** 截图（API 30+ 的 takeScreenshot；无障碍服务需要 canTakeScreenshot）。 */
    suspend fun screenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        val service = service ?: return null
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        runCatching {
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                java.util.concurrent.Executors.newSingleThreadExecutor(),
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        screenshot.hardwareBuffer.close()
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        screenshotPermissionDenied = errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                        latch.countDown()
                    }
                },
            )
        }.onFailure { latch.countDown() }
        latch.await(5, TimeUnit.SECONDS)
        return bitmap
    }
}
