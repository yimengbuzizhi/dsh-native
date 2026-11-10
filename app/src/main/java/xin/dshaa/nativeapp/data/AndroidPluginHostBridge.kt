package xin.dshaa.nativeapp.data

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.ai.assistance.showerclient.ShellIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xin.dshaa.core.plugins.PluginHostBridge
import xin.dshaa.nativeapp.device.DeviceShell
import xin.dshaa.nativeapp.device.a11y.A11y
import xin.dshaa.nativeapp.device.tools.TerminalPluginHost
import xin.dshaa.nativeapp.memory.MemoryPluginHost
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 插件宿主桥的 Android 实现（M67）。
 *
 * 只做 [PluginHostBridge] 约定的方法名分发，每个方法返回完整 JSON（带 `ok`）。
 * 能力边界和 Operit 对齐但不越权：
 * - 设备信息 / 应用列表 / 启动应用：PackageManager、Build、BatteryManager；
 * - 停止应用：只有 Shizuku/root 的 shell 身份才做得到，否则如实返回失败；
 * - 系统设置：Settings.System/Secure/Global 读写，写失败（没有 WRITE_SETTINGS）如实返回；
 * - 无障碍点按/滑动/按键/点元素/截图：走 [A11y]（和内置设备工具同一条链路）；
 * - 打开/分享文件：FileProvider + Intent（用户会看到系统弹层）。
 *
 * 记忆库（Tools.Memory，M68）：`memory.*` 交给 [MemoryPluginHost]（复用内置记忆工具 + MemoryStore），
 * 需要插件的 `memory` 授权（权限门在 PluginSandbox）。
 */
class AndroidPluginHostBridge(private val app: Application) : PluginHostBridge {

    private val memoryHost: MemoryPluginHost by lazy { MemoryPluginHost(app) }
    private val terminalHost: TerminalPluginHost by lazy { TerminalPluginHost(app) }

    override fun call(method: String, payloadJson: String): String? {
        val args = runCatching { Json.parseToJsonElement(payloadJson).jsonObject }
            .getOrNull() ?: JsonObject(emptyMap())
        return try {
            when (method) {
                "device.info" -> deviceInfo()
                "device.apps" -> listApps(args.flag("includeSystem"))
                "device.startApp" -> startApp(args.str("packageName").orEmpty(), args.str("activity"))
                "device.stopApp" -> stopApp(args.str("packageName").orEmpty())
                "device.getSetting" -> getSetting(args.str("setting").orEmpty(), args.str("namespace") ?: "system")
                "device.setSetting" -> setSetting(
                    args.str("setting").orEmpty(),
                    args.str("value").orEmpty(),
                    args.str("namespace") ?: "system",
                )
                "device.toast" -> toast(args.str("message").orEmpty())
                "device.sendBroadcast" -> sendBroadcast(args)
                "file.open" -> openFile(args.str("path").orEmpty())
                "file.share" -> shareFile(args.str("path").orEmpty(), args.str("title").orEmpty())
                "ui.tap" -> gesture("tap", args)
                "ui.longPress" -> gesture("long_press", args)
                "ui.swipe" -> gesture("swipe", args)
                "ui.pressKey" -> pressKey(args.str("keyCode").orEmpty())
                "ui.setText" -> setText(args.str("text").orEmpty())
                "ui.clickElement" -> clickElement(args.str("text"), args.int("index"))
                "ui.captureScreenshot" -> captureScreenshot()
                "ui.pageInfo" -> pageInfo()
                "terminal.create", "terminal.exec", "terminal.input", "terminal.screen", "terminal.close" ->
                    terminal(method.removePrefix("terminal."), args)
                else -> if (method.startsWith("memory.")) memory(method.removePrefix("memory."), args) else null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "host bridge $method failed: ${t.message}", t)
            fail(t.message ?: t.javaClass.simpleName)
        }
    }

    /** 记忆库（M68）：MemoryStore 走 IO 线程，这里同步等结果（单条记忆操作是毫秒级）。 */
    private fun memory(method: String, args: JsonObject): String =
        runBlocking { memoryHost.call(method, args) }

    /** 终端（M68）：真 PTY 会话可能跑几十秒，同步等（调用方是插件的 JS 线程，不能回主线程）。 */
    private fun terminal(action: String, args: JsonObject): String =
        runBlocking { terminalHost.call(action, args) }

    // ---------------- 设备 ----------------

    private fun deviceInfo(): String {
        val metrics = app.resources.displayMetrics
        val activity = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memory = android.app.ActivityManager.MemoryInfo().also { activity.getMemoryInfo(it) }
        val dataDir = Environment.getDataDirectory()
        val stat = StatFs(dataDir.path)
        val totalStorage = stat.blockCountLong * stat.blockSizeLong
        val availableStorage = stat.availableBlocksLong * stat.blockSizeLong
        val battery = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = runCatching {
            app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
        }.getOrDefault(false)
        val foreground = runCatching { A11y.foregroundPackage() }.getOrNull() ?: ""
        return ok {
            put("deviceId", runCatching {
                Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull() ?: "")
            put("model", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkVersion", Build.VERSION.SDK_INT)
            put("screenResolution", "${metrics.widthPixels}x${metrics.heightPixels}")
            put("screenDensity", metrics.density)
            put("densityDpi", metrics.densityDpi)
            put("totalMemory", formatBytes(memory.totalMem))
            put("availableMemory", formatBytes(memory.availMem))
            put("totalStorage", formatBytes(totalStorage))
            put("availableStorage", formatBytes(availableStorage))
            put("batteryLevel", batteryLevel)
            put("batteryCharging", charging)
            put("cpuInfo", Build.SUPPORTED_ABIS.joinToString(", "))
            put("networkType", networkType())
            put("foregroundPackage", foreground)
            put("accessibilityService", A11y.isReady())
            put("shizuku", DeviceShell.hasShizuku())
            put("root", DeviceShell.hasRoot())
        }
    }

    private fun listApps(includeSystem: Boolean): String {
        val pm = app.packageManager
        val installed = pm.getInstalledApplications(0)
        val packages = installed
            .filter { includeSystem || (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { it.packageName }
            .distinct()
            .sorted()
        return ok {
            put("includesSystemApps", includeSystem)
            put("packages", buildJsonArray { packages.forEach { add(JsonPrimitive(it)) } })
        }
    }

    private fun startApp(packageName: String, activity: String?): String {
        if (packageName.isBlank()) return fail("packageName 不能为空")
        val intent = if (!activity.isNullOrBlank()) {
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(packageName, activity)
            }
        } else {
            app.packageManager.getLaunchIntentForPackage(packageName)
        } ?: return appOp("start", packageName, false, "找不到可启动的 Activity（未安装？）")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            app.startActivity(intent)
            appOp("start", packageName, true, "已启动 $packageName")
        }.getOrElse { appOp("start", packageName, false, "启动失败：${it.message}") }
    }

    private fun stopApp(packageName: String): String {
        if (packageName.isBlank()) return fail("packageName 不能为空")
        if (!DeviceShell.canUseShellIdentity()) {
            return appOp("stop", packageName, false, "停止其他应用需要 Shizuku 或 root（当前都没有）")
        }
        val result = runBlocking { DeviceShell.run("am force-stop $packageName", ShellIdentity.DEFAULT) }
        val detail = if (result.success) "已停止 $packageName" else (result.stderr.ifBlank { result.stdout }).take(500)
        return appOp("stop", packageName, result.success, detail)
    }

    private fun appOp(type: String, packageName: String, success: Boolean, details: String): String = ok {
        put("operationType", type)
        put("packageName", packageName)
        put("success", success)
        put("details", details)
    }

    private fun getSetting(setting: String, namespace: String): String {
        if (setting.isBlank()) return fail("setting 不能为空")
        val value = runCatching {
            when (namespace) {
                "secure" -> Settings.Secure.getString(app.contentResolver, setting)
                "global" -> Settings.Global.getString(app.contentResolver, setting)
                else -> Settings.System.getString(app.contentResolver, setting)
            }
        }.getOrElse { return fail("读设置失败：${it.message}") }
        return ok {
            put("namespace", namespace)
            put("setting", setting)
            put("value", value ?: "")
        }
    }

    private fun setSetting(setting: String, value: String, namespace: String): String {
        if (setting.isBlank()) return fail("setting 不能为空")
        val written = runCatching {
            val ok = when (namespace) {
                "secure" -> Settings.Secure.putString(app.contentResolver, setting, value)
                "global" -> Settings.Global.putString(app.contentResolver, setting, value)
                else -> Settings.System.putString(app.contentResolver, setting, value)
            }
            ok
        }.getOrElse { return fail("写设置失败（需要 WRITE_SETTINGS？）：${it.message}") }
        return ok {
            put("namespace", namespace)
            put("setting", setting)
            put("value", value)
            put("written", written)
        }
    }

    private fun toast(message: String): String {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(app, message.take(300), Toast.LENGTH_SHORT).show()
        }
        return ok { put("shown", true) }
    }

    private fun sendBroadcast(args: JsonObject): String {
        val action = args.str("action").orEmpty()
        if (action.isBlank()) return fail("action 不能为空")
        val intent = Intent(action)
        args.str("uri")?.takeIf { it.isNotBlank() }?.let { runCatching { intent.data = Uri.parse(it) } }
        args.str("package")?.takeIf { it.isNotBlank() }?.let { intent.setPackage(it) }
        val component = args.str("component")
        if (!component.isNullOrBlank() && component.contains('/')) {
            val pkg = component.substringBefore('/')
            val cls = component.substringAfter('/')
            intent.component = ComponentName(pkg, cls)
        }
        return runCatching {
            app.sendBroadcast(intent)
            ok { put("sent", true); put("action", action) }
        }.getOrElse { fail("发广播失败：${it.message}") }
    }

    // ---------------- 文件 Intent ----------------

    private fun openFile(path: String): String {
        if (path.isBlank()) return fail("path 不能为空")
        val file = File(path)
        if (!file.exists()) return fail("文件不存在：$path")
        val uri = runCatching { FileProvider.getUriForFile(app, AUTHORITY, file) }
            .getOrElse { return fail("这个路径不能通过 FileProvider 暴露：${it.message}") }
        val mime = mimeOf(file.name)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            app.startActivity(intent)
            ok { put("opened", true); put("path", file.absolutePath); put("mime", mime) }
        }.getOrElse { fail("没有应用能打开这个文件：${it.message}") }
    }

    private fun shareFile(path: String, title: String): String {
        if (path.isBlank()) return fail("path 不能为空")
        val file = File(path)
        if (!file.isFile) return fail("文件不存在：$path")
        val uri = runCatching { FileProvider.getUriForFile(app, AUTHORITY, file) }
            .getOrElse { return fail("这个路径不能通过 FileProvider 暴露：${it.message}") }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(file.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, title.ifBlank { "分享文件" })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            app.startActivity(chooser)
            ok { put("shared", true); put("path", file.absolutePath) }
        }.getOrElse { fail("分享失败：${it.message}") }
    }

    // ---------------- 无障碍 ----------------

    private fun gesture(kind: String, args: JsonObject): String {
        if (!A11y.isReady()) return fail(NO_A11Y)
        return when (kind) {
            "tap" -> {
                val x = args.num("x") ?: return fail("x 不能为空")
                val y = args.num("y") ?: return fail("y 不能为空")
                val done = runBlocking { A11y.tap(x, y) }
                if (!done) fail("点击失败（手势被取消）") else uiOk("tap", "已点击 ($x,$y)", x, y)
            }
            "long_press" -> {
                val x = args.num("x") ?: return fail("x 不能为空")
                val y = args.num("y") ?: return fail("y 不能为空")
                val done = runBlocking { A11y.longPress(x, y) }
                if (!done) fail("长按失败（手势被取消）") else uiOk("long_press", "已长按 ($x,$y)", x, y)
            }
            "swipe" -> {
                val sx = args.num("startX") ?: return fail("startX 不能为空")
                val sy = args.num("startY") ?: return fail("startY 不能为空")
                val ex = args.num("endX") ?: return fail("endX 不能为空")
                val ey = args.num("endY") ?: return fail("endY 不能为空")
                val duration = (args.num("durationMs") ?: 300f).toLong().coerceIn(30L, 5_000L)
                val done = runBlocking { A11y.swipe(sx, sy, ex, ey, duration) }
                if (!done) {
                    fail("滑动失败（手势被取消）")
                } else {
                    uiOk("swipe", "已滑动 ($sx,$sy) → ($ex,$ey) ${duration}ms", null, null)
                }
            }
            else -> fail("未知手势：$kind")
        }
    }

    private fun uiOk(action: String, description: String, x: Float?, y: Float?): String = ok {
        put("actionType", action)
        put("actionDescription", description)
        if (x != null && y != null) {
            put("coordinates", buildJsonArray { add(JsonPrimitive(x.toInt())); add(JsonPrimitive(y.toInt())) })
        }
    }

    private fun pressKey(keyCode: String): String {
        val key = keyCode.trim()
        if (key.isEmpty()) return fail("keyCode 不能为空")
        val named = runCatching { A11y.isReady() }.getOrDefault(false)
        val global = when (key.lowercase()) {
            "back", "keycode_back" -> A11y.globalBack()
            "home", "keycode_home" -> A11y.globalHome()
            "recents", "recent", "keycode_app_switch" -> A11y.globalRecents()
            "notifications", "keycode_notification" -> A11y.globalNotifications()
            else -> null
        }
        if (global != null) {
            return if (global && named) {
                uiOk("press_key", "已按 $key", null, null)
            } else {
                fail(NO_A11Y)
            }
        }
        if (!Regex("^[A-Za-z0-9_.-]+$").matches(key)) return fail("非法按键：$key")
        val result = runBlocking { DeviceShell.run("input keyevent $key", ShellIdentity.DEFAULT) }
        return if (result.success) {
            uiOk("press_key", "已按 $key", null, null)
        } else {
            fail("按键失败（其他按键需要 Shizuku/root）：${result.stderr.ifBlank { result.stdout }}".take(500))
        }
    }

    private fun setText(text: String): String {
        if (!A11y.isReady()) return fail(NO_A11Y)
        val node = A11y.focusedEditable() ?: return fail("没有聚焦的输入框（先点一下输入框）")
        return if (A11y.setText(node, text)) {
            uiOk("input_text", "已输入 ${text.length} 字符", null, null)
        } else {
            fail("输入框拒绝了 ACTION_SET_TEXT")
        }
    }

    private fun clickElement(text: String?, index: Int?): String {
        if (!A11y.isReady()) return fail(NO_A11Y)
        val node = when {
            index != null -> A11y.nodeAt(index)
            !text.isNullOrBlank() -> A11y.find(text = text).firstOrNull()
            else -> null
        } ?: return fail("没有匹配的控件（先 dump_ui 看索引/文本）")
        return if (A11y.click(node)) {
            uiOk("click_element", "已点击${text?.let { "「$it」" } ?: "控件 #$index"}", null, null)
        } else {
            fail("控件不可点击")
        }
    }

    /**
     * `Tools.UI.getPageInfo`（M68）：把当前窗口的无障碍树映射成 Operit `UIPageResultData` 的形状
     * （`packageName` / `activityName` / `uiElements[SimplifiedUINode]`）。
     *
     * 诚实点：`activityName` 取不到（无障碍只给包名，App 内也没有读前台 Activity 的实现），
     * 这里填空字符串，不编一个假名字。节点上限 [MAX_UI_NODES] 与 [MAX_UI_DEPTH]，
     * 和 `dump_ui` 的 BFS 顺序一致（同一棵树的先序遍历）。
     */
    private fun pageInfo(): String {
        if (!A11y.isReady()) return fail(NO_A11Y)
        val root = A11y.root() ?: return fail("读不到当前窗口（无障碍没有拿到 root，可能正在动画/受保护页面）")
        val counter = intArrayOf(0)
        val rootNode = runCatching { uiNodeJson(root, 0, counter) }.getOrNull()
            ?: return fail("读取无障碍节点时出错")
        return ok {
            put("packageName", root.packageName?.toString().orEmpty())
            put("activityName", "")
            put("nodeCount", counter[0])
            put("truncated", counter[0] >= MAX_UI_NODES)
            // uiElements 是数组（Operit 的 UIPageResultData.uiElements），根节点是第一项。
            put("uiElements", buildJsonArray { add(rootNode) })
        }
    }

    private fun uiNodeJson(
        node: android.view.accessibility.AccessibilityNodeInfo,
        depth: Int,
        counter: IntArray,
    ): JsonObject? {
        if (depth > MAX_UI_DEPTH || counter[0] >= MAX_UI_NODES) return null
        counter[0] += 1
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val children = ArrayList<JsonObject>()
        for (index in 0 until node.childCount) {
            if (counter[0] >= MAX_UI_NODES) break
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            uiNodeJson(child, depth + 1, counter)?.let { children += it }
        }
        return buildJsonObject {
            put("className", node.className?.toString()?.substringAfterLast('.').orEmpty())
            put("text", node.text?.toString().orEmpty().take(200))
            put("contentDesc", node.contentDescription?.toString().orEmpty().take(120))
            put("resourceId", node.viewIdResourceName?.substringAfterLast('/').orEmpty())
            put("bounds", "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]")
            put("isClickable", node.isClickable)
            if (children.isNotEmpty()) put("children", buildJsonArray { children.forEach { add(it) } })
        }
    }

    private fun captureScreenshot(): String {        if (!A11y.isReady()) return fail(NO_A11Y)
        val bitmap = runBlocking { A11y.screenshot() } ?: return fail("截图失败（需要 Android 11+ 且无障碍已连接）")
        val dir = File(app.filesDir, "screenshots").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "plugin-shot-$stamp.png")
        val saved = runCatching {
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }.isSuccess
        if (!saved) return fail("截图写文件失败：${file.absolutePath}")
        return ok {
            put("path", file.absolutePath)
            put("width", bitmap.width)
            put("height", bitmap.height)
        }
    }

    // ---------------- 小工具 ----------------

    private fun networkType(): String {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "unknown"
        val network = cm.activeNetwork ?: return "offline"
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
        else -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    }

    private fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    private fun ok(build: JsonObjectBuilder.() -> Unit): String {
        val body = buildJsonObject(build)
        val payload = buildJsonObject {
            put("ok", true)
            body.forEach { (key, value) -> put(key, value) }
        }
        return payload.toString()
    }

    private fun fail(message: String, code: String = "PLUGIN_HOST_ERROR"): String = buildJsonObject {
        put("ok", false)
        put("error", message)
        put("code", code)
    }.toString()

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    private fun JsonObject.int(key: String): Int? =
        runCatching { this[key]?.jsonPrimitive?.content?.toIntOrNull() }.getOrNull()

    private fun JsonObject.num(key: String): Float? =
        runCatching { this[key]?.jsonPrimitive?.content?.toFloatOrNull() }.getOrNull()

    private fun JsonObject.flag(key: String): Boolean =
        runCatching { this[key]?.jsonPrimitive?.content?.equals("true", ignoreCase = true) == true }
            .getOrDefault(false)

    private companion object {
        const val TAG = "DshPluginHost"
        const val AUTHORITY = "xin.dshaa.nativeapp.files"
        const val NO_A11Y =
            "无障碍服务未连接：请到系统设置 → 无障碍里打开「DSH 无障碍」，再重试"
        /** `getPageInfo` 的节点预算：和 `dump_ui` 默认 250 同量级，别把整棵树塞进模型上下文。 */
        const val MAX_UI_NODES = 250
        const val MAX_UI_DEPTH = 24
    }
}
