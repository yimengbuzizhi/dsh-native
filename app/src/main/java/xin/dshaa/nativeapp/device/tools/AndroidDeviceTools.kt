package xin.dshaa.nativeapp.device.tools

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult
import xin.dshaa.nativeapp.device.DeviceShell
import xin.dshaa.nativeapp.device.a11y.A11y
import xin.dshaa.nativeapp.device.shower.VirtualDisplaySpec
import xin.dshaa.nativeapp.device.shower.VirtualScreenManager
import xin.dshaa.nativeapp.device.shower.VirtualScreenOverlay
import xin.dshaa.nativeapp.device.shower.VirtualScreenSession
import com.ai.assistance.showerclient.ShellIdentity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Operit 设备工具的 Android 原生实现（手机代理能力）：
 * `device_info` / `dump_ui` / `click_element` / `tap` / `long_press` / `swipe` /
 * `press_key` / `input_text` / `launch_app` / `capture_screenshot`。
 *
 * 坐标约定对齐 Operit phone-agent：`0..999` 归一化，服务端按当前屏幕像素换算。
 *
 * **虚拟屏（2026-11-07，照 Operit DebuggerUITools）**：上面这些工具都加可选 `display`。
 * 传了 display 就用 `input -d <id> ...` 走 shell 注入，不碰主屏；不传还是走无障碍。
 * `set_input_text` 的虚拟屏路径照 Operit：先 `KEYCODE_CLEAR`，把文本放剪贴板，
 * 再 `KEYCODE_PASTE`（这样中文也能进，`input text` 只支持 ASCII）。
 * 另外补两个 Operit 生命周期工具：`start_virtual_display` / `close_all_virtual_displays`。
 */
class AndroidDeviceTools(private val app: Application) {

    private val display get() = app.resources.displayMetrics

    val tools: List<DshTool> = listOf(
        tool("device_info") { _, _ -> deviceInfo() },
        tool("dump_ui") { args, _ -> dumpUi(args.int("max_nodes") ?: 250) },
        tool("click_element") { args, _ -> clickElement(args.str("text"), args.int("index")) },
        tool("tap") { args, _ -> tap(args) },
        tool("long_press") { args, _ -> longPress(args) },
        tool("swipe") { args, _ -> swipe(args) },
        tool("press_key") { args, _ -> pressKey(args.str("key"), args.displayId()) },
        tool("input_text") { args, _ -> inputText(args.str("text"), args.displayId()) },
        tool("launch_app") { args, _ -> launchApp(args.str("package_name"), args.displayId()) },
        // capture_screenshot 要读 display 参数，单独写一个。
        object : DshTool {
            override val name: String = "capture_screenshot"

            override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
                captureScreenshot(ctx, args.displayId())
        },
        tool("start_virtual_display") { args, _ -> startVirtualDisplay(args) },
        tool("close_all_virtual_displays") { _, _ -> closeAllVirtualDisplays() },
    )

    private fun tool(name: String, block: suspend (JsonObject, ToolContext) -> ToolResult): DshTool =
        object : DshTool {
            override val name: String = name

            override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = block(args, ctx)
        }

    private fun deviceInfo(): ToolResult {
        val root = A11y.root()
        val foreground = root?.packageName?.toString() ?: "(unknown)"
        val text = buildString {
            append("model: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("screen: ${display.widthPixels}x${display.heightPixels} @ ${display.densityDpi}dpi\n")
            append("foreground: $foreground\n")
            append("accessibility_service: ${if (A11y.isReady()) "connected" else "not connected"}\n")
            append("shizuku: ${if (DeviceShell.hasShizuku()) "connected" else "unavailable"}\n")
            append("root: ${if (DeviceShell.hasRoot()) "available" else "unavailable"}\n")
            append(VirtualScreenManager.statusLine())
        }
        return ToolResult.ok(text)
    }

    private fun dumpUi(maxNodes: Int): ToolResult {
        if (!A11y.isReady()) return ToolResult.err(noA11y)
        val count = maxNodes.coerceIn(1, 1_000)
        return ToolResult.ok(A11y.dump(count))
    }

    private fun clickElement(text: String?, index: Int?): ToolResult {
        if (!A11y.isReady()) return ToolResult.err(noA11y)
        val node = when {
            index != null -> A11y.nodeAt(index)
            !text.isNullOrBlank() -> A11y.find(text = text).firstOrNull()
            else -> return ToolResult.err("pass either index or text")
        } ?: return ToolResult.err("no matching node (re-run dump_ui and try another index/text)")
        return if (A11y.click(node)) {
            ToolResult.ok("clicked ${text?.let { "\"$it\"" } ?: "node #$index"}")
        } else {
            ToolResult.err("node is not clickable (no clickable ancestor)")
        }
    }

    // ---------------- 手势：主屏无障碍 / 虚拟屏 shell（Operit 同款） ----------------

    private suspend fun tap(args: JsonObject): ToolResult {
        val displayId = args.displayId()
        val x = args.num("x") ?: return ToolResult.err("x is required (0..999)")
        val y = args.num("y") ?: return ToolResult.err("y is required (0..999)")
        if (displayId != null) {
            return virtualGesture(displayId, "tap", x, y) { px, py ->
                "input -d $displayId tap $px $py"
            }
        }
        if (!A11y.isReady()) return ToolResult.err(noA11y)
        val (w, h) = display.widthPixels to display.heightPixels
        val ok = A11y.tap(x / 999f * w, y / 999f * h)
        return if (ok) ToolResult.ok("tap ($x,$y)") else ToolResult.err("tap failed: gesture was cancelled")
    }

    private suspend fun longPress(args: JsonObject): ToolResult {
        val displayId = args.displayId()
        val x = args.num("x") ?: return ToolResult.err("x is required (0..999)")
        val y = args.num("y") ?: return ToolResult.err("y is required (0..999)")
        if (displayId != null) {
            return virtualGesture(displayId, "long_press", x, y) { px, py ->
                "input -d $displayId swipe $px $py $px $py 800"
            }
        }
        if (!A11y.isReady()) return ToolResult.err(noA11y)
        val (w, h) = display.widthPixels to display.heightPixels
        val ok = A11y.longPress(x / 999f * w, y / 999f * h)
        return if (ok) ToolResult.ok("long_press ($x,$y)") else ToolResult.err("long_press failed: gesture was cancelled")
    }

    private suspend fun swipe(args: JsonObject): ToolResult {
        val displayId = args.displayId()
        val startX = args.num("start_x") ?: return ToolResult.err("start_x is required")
        val startY = args.num("start_y") ?: return ToolResult.err("start_y is required")
        val endX = args.num("end_x") ?: return ToolResult.err("end_x is required")
        val endY = args.num("end_y") ?: return ToolResult.err("end_y is required")
        val duration = (args.int("duration_ms") ?: 300).coerceIn(30, 5_000)
        if (displayId != null) {
            val size = VirtualScreenManager.videoSizeOf(displayId) ?: return virtualNotRunning(displayId)
            val w = size.first
            val h = size.second
            val sx = (startX / 999f * w).roundToInt().coerceIn(0, w - 1)
            val sy = (startY / 999f * h).roundToInt().coerceIn(0, h - 1)
            val ex = (endX / 999f * w).roundToInt().coerceIn(0, w - 1)
            val ey = (endY / 999f * h).roundToInt().coerceIn(0, h - 1)
            val result = DeviceShell.run(
                "input -d $displayId swipe $sx $sy $ex $ey $duration",
                ShellIdentity.SHELL,
            )
            return if (result.success) {
                ToolResult.ok("swiped on display $displayId: ($sx,$sy) -> ($ex,$ey) ${duration}ms")
            } else {
                ToolResult.err("swipe failed on display $displayId: ${result.stderr.ifBlank { result.stdout }}")
            }
        }
        if (!A11y.isReady()) return ToolResult.err(noA11y)
        val (w, h) = display.widthPixels to display.heightPixels
        val ok = A11y.swipe(
            (startX / 999f * w),
            (startY / 999f * h),
            (endX / 999f * w),
            (endY / 999f * h),
            duration.toLong(),
        )
        return if (ok) {
            ToolResult.ok("swiped ($startX,$startY) -> ($endX,$endY)")
        } else {
            ToolResult.err("swipe failed: gesture was cancelled")
        }
    }

    private suspend fun virtualGesture(
        displayId: Int,
        name: String,
        x: Float,
        y: Float,
        command: (Int, Int) -> String,
    ): ToolResult {
        val size = VirtualScreenManager.videoSizeOf(displayId) ?: return virtualNotRunning(displayId)
        val w = size.first
        val h = size.second
        val px = (x / 999f * w).roundToInt().coerceIn(0, w - 1)
        val py = (y / 999f * h).roundToInt().coerceIn(0, h - 1)
        val result = DeviceShell.run(command(px, py), ShellIdentity.SHELL)
        return if (result.success) {
            ToolResult.ok("$name on display $displayId: ($px,$py)")
        } else {
            ToolResult.err("$name failed on display $displayId: ${result.stderr.ifBlank { result.stdout }}")
        }
    }

    private suspend fun pressKey(key: String?, displayId: Int?): ToolResult {
        if (key.isNullOrBlank()) return ToolResult.err("key is required")
        if (displayId != null) {
            if (VirtualScreenManager.videoSizeOf(displayId) == null) return virtualNotRunning(displayId)
            if (key.equals("home", ignoreCase = true)) {
                val ok = VirtualScreenManager.openHome(app)
                return if (ok) ToolResult.ok("pressed home on display $displayId (virtual desktop)")
                else ToolResult.err("failed to open the virtual desktop on display $displayId")
            }
            if (key.equals("recents", ignoreCase = true) || key.equals("recent", ignoreCase = true)) {
                return ToolResult.err(
                    "recents is a system-wide key and cannot be scoped to display $displayId; " +
                        "use start_virtual_display/launch_app to switch apps instead",
                )
            }
            if (!Regex("^[A-Za-z0-9_.-]+$").matches(key)) return ToolResult.err("invalid key: $key")
            val result = DeviceShell.run("input -d $displayId keyevent $key", ShellIdentity.SHELL)
            return if (result.success) {
                ToolResult.ok("pressed $key on display $displayId")
            } else {
                ToolResult.err("keyevent failed on display $displayId: ${result.stderr.ifBlank { result.stdout }}")
            }
        }
        val named = when (key.lowercase()) {
            "back" -> A11y.globalBack()
            "home" -> A11y.globalHome()
            "recents", "recent" -> A11y.globalRecents()
            "notifications", "notification" -> A11y.globalNotifications()
            else -> null
        }
        if (named != null) {
            return if (named) ToolResult.ok("pressed $key") else ToolResult.err("accessibility service is not connected")
        }
        if (!Regex("^[A-Za-z0-9_.-]+$").matches(key)) {
            return ToolResult.err("invalid key: $key")
        }
        val result = DeviceShell.run("input keyevent $key", ShellIdentity.DEFAULT)
        return if (result.success) {
            ToolResult.ok("pressed $key")
        } else {
            ToolResult.err("keyevent failed (need Shizuku/root for shell key injection): ${result.stderr.ifBlank { result.stdout }}")
        }
    }

    /**
     * Operit DebuggerUITools.setInputText 的虚拟屏路径：
     * `KEYCODE_CLEAR` → 剪贴板 → `KEYCODE_PASTE`。`input text` 只支持 ASCII，中文会静默丢。
     */
    private suspend fun inputText(text: String?, displayId: Int?): ToolResult {
        if (text == null) return ToolResult.err("text is required")
        if (displayId != null) {
            if (VirtualScreenManager.videoSizeOf(displayId) == null) return virtualNotRunning(displayId)
            val clear = DeviceShell.run(
                "input -d $displayId keyevent KEYCODE_CLEAR",
                ShellIdentity.SHELL,
            )
            if (!clear.success) {
                return ToolResult.err("clear field failed on display $displayId: ${clear.stderr.ifBlank { clear.stdout }}")
            }
            delay(250)
            if (text.isEmpty()) return ToolResult.ok("cleared the focused field on display $displayId")
            withContext(Dispatchers.Main) {
                val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("dsh_input", text))
            }
            delay(120)
            val paste = DeviceShell.run(
                "input -d $displayId keyevent KEYCODE_PASTE",
                ShellIdentity.SHELL,
            )
            return if (paste.success) {
                ToolResult.ok("text set on display $displayId (${text.length} chars, clipboard paste)")
            } else {
                ToolResult.err("paste failed on display $displayId: ${paste.stderr.ifBlank { paste.stdout }}")
            }
        }
        if (!A11y.isReady()) return ToolResult.err(noA11y)
        val node = A11y.focusedEditable()
            ?: return ToolResult.err("no focused editable field (tap a text field first)")
        return if (A11y.setText(node, text)) {
            ToolResult.ok("text set (${text.length} chars)")
        } else {
            ToolResult.err("the focused field rejected ACTION_SET_TEXT")
        }
    }

    private suspend fun launchApp(packageName: String?, displayId: Int?): ToolResult {
        val pkg = packageName?.trim()
        if (pkg.isNullOrBlank()) return ToolResult.err("package_name is required")
        if (displayId != null) {
            if (VirtualScreenManager.videoSizeOf(displayId) == null) return virtualNotRunning(displayId)
            return VirtualScreenManager.launch(app, pkg).fold(
                onSuccess = { ToolResult.ok("launched $it") },
                onFailure = { ToolResult.err(it.message ?: "launch failed") },
            )
        }
        val intent = app.packageManager.getLaunchIntentForPackage(pkg)
            ?: return ToolResult.err("package $pkg has no launchable activity (installed?)")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            app.startActivity(intent)
            ToolResult.ok("launched $pkg")
        }.getOrElse { ToolResult.err("launch failed: ${it.message}") }
    }

    private suspend fun captureScreenshot(ctx: ToolContext, displayId: Int?): ToolResult {
        if (displayId != null) {
            if (VirtualScreenManager.videoSizeOf(displayId) == null) return virtualNotRunning(displayId)
            val bytes = runCatching {
                VirtualScreenSession.controller.requestScreenshot(5_000)
            }.getOrNull()
                ?: return ToolResult.err("virtual screen screenshot failed (display $displayId)")
            val dir = File(ctx.workspace, "screenshots").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "virtual-$displayId-$stamp.png")
            val saved = runCatching { file.outputStream().use { it.write(bytes) } }.isSuccess
            if (!saved) return ToolResult.err("failed to write ${file.absolutePath}")
            return ToolResult.ok(
                "saved virtual screen screenshot: ${file.absolutePath} (display $displayId). " +
                    "Inspect it with read_image, then tap/swipe with normalized 0..999 coordinates and display=$displayId.",
                meta = mapOf("path" to file.absolutePath),
            )
        }
        if (!A11y.isReady()) return ToolResult.err(noA11y)
        val bitmap = A11y.screenshot()
            ?: return ToolResult.err("screenshot failed (takeScreenshot needs Android 11+ and the service enabled)")
        val dir = File(ctx.workspace, "screenshots").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "shot-$stamp.png")
        val saved = runCatching {
            file.outputStream().use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
        }.isSuccess
        if (!saved) return ToolResult.err("failed to write ${file.absolutePath}")
        return ToolResult.ok(
            "saved screenshot: ${file.absolutePath} (${bitmap.width}x${bitmap.height}). " +
                "Inspect it with read_image.",
            meta = mapOf("path" to file.absolutePath),
        )
    }

    // ---------------- 虚拟屏生命周期 ----------------

    private suspend fun startVirtualDisplay(args: JsonObject): ToolResult {
        val spec = VirtualDisplaySpec(
            width = (args.int("width") ?: 1080).coerceIn(320, 2_400),
            height = (args.int("height") ?: 1920).coerceIn(320, 2_400),
            dpi = (args.int("dpi") ?: 320).coerceIn(120, 640),
        )
        val appQuery = args.str("app")
        return VirtualScreenManager.start(app, spec, appQuery).fold(
            onSuccess = { started ->
                // 预览窗（2026-11-08，照 Operit 的 VirtualDisplayOverlay）：AI 一开虚拟屏就浮出来
                val preview = VirtualScreenOverlay.show(app, started.displayId)
                val appText = started.appLabel?.let { " app=$it" } ?: ""
                val homeText = if (started.homeOpened) " virtual desktop opened." else ""
                val previewText = if (preview) {
                    " A preview window is shown on the device screen."
                } else {
                    " (Preview window needs the 'display over other apps' permission; ask the user to grant it.)"
                }
                ToolResult.ok(
                    "virtual display started: id=${started.displayId}, " +
                        "${started.size.first}x${started.size.second} @${started.spec.dpi}dpi$appText.$homeText$previewText " +
                        "Use display=${started.displayId} on tap/long_press/swipe/press_key/set_input_text/" +
                        "capture_screenshot/launch_app. Use normalized 0..999 coordinates.",
                    meta = mapOf("display" to started.displayId.toString()),
                )
            },
            onFailure = { ToolResult.err(it.message ?: "start virtual display failed") },
        )
    }

    private suspend fun closeAllVirtualDisplays(): ToolResult {
        VirtualScreenManager.stop(app)
        VirtualScreenOverlay.hide()
        return ToolResult.ok("closed all virtual displays")
    }

    private fun virtualNotRunning(displayId: Int): ToolResult = ToolResult.err(
        "virtual display $displayId is not running. ${VirtualScreenManager.statusLine()}. " +
            "Call start_virtual_display first.",
    )

    private val noA11y: String
        get() = "accessibility service is not connected. Ask the user to enable " +
            "\"DSH 无障碍\" in Android Settings > Accessibility, then retry."

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.num(key: String): Float? =
        runCatching { this[key]?.jsonPrimitive?.doubleOrNull?.toFloat() }.getOrNull()

    private fun JsonObject.int(key: String): Int? =
        runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()

    /** `display` 允许传数字或字符串（模型常把 id 写成 "13"）。 */
    private fun JsonObject.displayId(): Int? {
        val element = this["display"] ?: return null
        return runCatching {
            element.jsonPrimitive.intOrNull ?: element.jsonPrimitive.contentOrNull?.trim()?.toIntOrNull()
        }.getOrNull()
    }

    @Suppress("unused")
    private fun JsonObject.bool(key: String): Boolean? =
        runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()
}
