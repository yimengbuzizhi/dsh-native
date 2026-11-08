package xin.dshaa.nativeapp.device.floating

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Operit `FloatingWindowState` 的逐项移植（docs/76 §五 S1、S9）。
 *
 * 这里只存「窗口形状/位置/模式」；聊天消息不放在这里（由 ChatMirror 提供）。
 * 行号对照：Operit FloatingWindowState.kt:10-124。
 */
class FloatingWindowState(val context: Context) {

    val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences =
        context.getSharedPreferences("dsh", Context.MODE_PRIVATE)

    /**
     * 不能用 `resources.displayMetrics`：跑过 VirtualHomeActivity 后这个进程的 resources
     * 会变成虚拟屏（1088×1920），窗口尺寸/位置就全按虚拟屏算了。物理屏只认 DEFAULT_DISPLAY。
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
    private val screenWidthPx = realMetrics.widthPixels
    private val screenHeightPx = realMetrics.heightPixels

    init {
        migrateLegacyPrefs()
    }

    /**
     * v1 的 `floating_*` 是 M51 早期自绘面板的几何值（高度 250dp、x/y=0，窗口钉在左上角），
     * 和 Operit 默认 300×400dp / 0.8 / 200,200 不一致。只重置这一组键，
     * 不碰 `dsh.xml` 里的会话、主题、模型等其他配置。
     */
    private fun migrateLegacyPrefs() {
        if (prefs.getInt(KEY_PREFS_VERSION, 1) >= 2) return
        prefs.edit()
            .remove(KEY_X).remove(KEY_Y)
            .remove(KEY_W).remove(KEY_H)
            .remove(KEY_SCALE).remove(KEY_LAST_SCALE)
            .remove(KEY_MODE).remove(KEY_PREV_MODE)
            .remove(KEY_LAST_WX).remove(KEY_LAST_WY)
            .remove(KEY_LAST_BX).remove(KEY_LAST_BY)
            .putInt(KEY_PREFS_VERSION, 2)
            .apply()
    }

    /** 窗口左上角，单位 px。Operit 默认 200,200（FloatingWindowState.kt:19-20,87-88）。 */
    var x: Int = prefs.getInt(KEY_X, 200)
    var y: Int = prefs.getInt(KEY_Y, 200)

    /** 与 Operit 相同的默认值：300×400dp、scale 0.8、球 60dp。 */
    val windowWidth = mutableStateOf(
        prefs.getFloat(KEY_W, 300f).coerceIn(200f, screenWidthDp() * 0.8f).dp
    )
    val windowHeight = mutableStateOf(
        prefs.getFloat(KEY_H, 400f).coerceIn(250f, screenHeightDp() * 0.8f).dp
    )
    val windowScale = mutableStateOf(prefs.getFloat(KEY_SCALE, 0.8f).coerceIn(0.3f, 1f))
    var lastWindowScale: Float = prefs.getFloat(KEY_LAST_SCALE, 0.8f).coerceIn(0.3f, 1f)

    val currentMode = mutableStateOf(restoreMode(KEY_MODE, FloatingMode.BALL))
    var previousMode: FloatingMode = restoreMode(KEY_PREV_MODE, FloatingMode.WINDOW)

    val ballSize = mutableStateOf(60.dp)
    val isAtEdge = mutableStateOf(false)

    var lastWindowPositionX: Int = prefs.getInt(KEY_LAST_WX, x)
    var lastWindowPositionY: Int = prefs.getInt(KEY_LAST_WY, y)
    var lastBallPositionX: Int = prefs.getInt(KEY_LAST_BX, x)
    var lastBallPositionY: Int = prefs.getInt(KEY_LAST_BY, y)

    var isTransitioning = false
    val transitionDebounceTime = 500L

    /** 球→其他模式时先 100ms 淡出，Operit 用这个 state 驱动。 */
    val ballExploding = mutableStateOf(false)

    /** 系统级跨窗口模糊是否真的可用；不可用时全屏画 Compose 回退。 */
    val fullscreenSystemBlurActive = mutableStateOf(false)

    /**
     * SCREEN_OCR 用的整屏截图。必须在切到 SCREEN_OCR 之前抓：这个模式自己就是全屏窗口，
     * 先切再截会把遮罩/黑底截进去（Operit 用 AIToolHandler，原生用无障碍 takeScreenshot）。
     */
    var ocrBitmap by mutableStateOf<Bitmap?>(null)
    var ocrError by mutableStateOf<String?>(null)

    fun screenWidthDp(): Float = screenWidthPx / density
    fun screenHeightDp(): Float = screenHeightPx / density

    /**
     * SCREEN_OCR / RESULT_DISPLAY 是瞬态模式：截图 / 结果内容不持久化，重启后恢复这两个模式
     * 只会得到空黑屏或空结果。恢复时统一退回 WINDOW（Operit 的 currentMode 也不存 OCR）。
     */
    private fun restoreMode(key: String, fallback: FloatingMode): FloatingMode {
        val stored = runCatching {
            FloatingMode.valueOf(prefs.getString(key, null) ?: "")
        }.getOrDefault(fallback)
        return if (stored == FloatingMode.SCREEN_OCR || stored == FloatingMode.RESULT_DISPLAY) {
            FloatingMode.WINDOW
        } else {
            stored
        }
    }

    fun saveState() {
        require(windowWidth.value.value.isFinite()) { "Non-finite window_width" }
        require(windowHeight.value.value.isFinite()) { "Non-finite window_height" }
        require(windowScale.value.isFinite()) { "Non-finite window_scale" }
        prefs.edit()
            .putInt(KEY_X, x)
            .putInt(KEY_Y, y)
            .putFloat(KEY_W, windowWidth.value.value.coerceIn(200f, screenWidthDp() * 0.8f))
            .putFloat(KEY_H, windowHeight.value.value.coerceIn(250f, screenHeightDp() * 0.8f))
            .putFloat(KEY_SCALE, windowScale.value.coerceIn(0.3f, 1f))
            .putFloat(KEY_LAST_SCALE, lastWindowScale.coerceIn(0.3f, 1f))
            .putString(KEY_MODE, currentMode.value.name)
            .putString(KEY_PREV_MODE, previousMode.name)
            .putInt(KEY_LAST_WX, lastWindowPositionX)
            .putInt(KEY_LAST_WY, lastWindowPositionY)
            .putInt(KEY_LAST_BX, lastBallPositionX)
            .putInt(KEY_LAST_BY, lastBallPositionY)
            .apply()
    }

    private companion object {
        const val KEY_PREFS_VERSION = "floating_prefs_version"
        const val KEY_X = "floating_x"
        const val KEY_Y = "floating_y"
        const val KEY_W = "floating_width"
        const val KEY_H = "floating_height"
        const val KEY_SCALE = "floating_scale"
        const val KEY_LAST_SCALE = "floating_last_scale"
        const val KEY_MODE = "floating_mode"
        const val KEY_PREV_MODE = "floating_previous_mode"
        const val KEY_LAST_WX = "floating_last_window_x"
        const val KEY_LAST_WY = "floating_last_window_y"
        const val KEY_LAST_BX = "floating_last_ball_x"
        const val KEY_LAST_BY = "floating_last_ball_y"
    }
}
