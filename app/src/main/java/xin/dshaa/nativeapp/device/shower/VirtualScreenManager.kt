package xin.dshaa.nativeapp.device.shower

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.ai.assistance.showerclient.ShowerServerManager
import com.ai.assistance.showerclient.ShellIdentity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xin.dshaa.nativeapp.VirtualHomeActivity
import xin.dshaa.nativeapp.device.DeviceShell

/**
 * 虚拟显示的生命周期与「AI 工具入口」（照 Operit 的用法，2026-11-07）。
 *
 * Operit 的设备工具不另起一套 `virtual_*`，而是在 `tap/swipe/press_key/set_input_text/
 * capture_screenshot/launch_app` 上加可选 `display`：有 display 就用 `input -d <id>` 走
 * shell，没有就走无障碍。这里保持同样的形状，AI 只要拿到 display id 就能操作虚拟屏。
 *
 * 为什么 start/stop 放在这里：Shower 的 controller 是进程级单例，页面和工具必须共用同一份
 * display id / 尺寸；否则工具会对着一个已销毁的 display 注入。
 */
object VirtualScreenManager {

    private val lifecycleLock = Mutex()

    data class StartResult(
        val displayId: Int,
        val size: Pair<Int, Int>,
        val spec: VirtualDisplaySpec,
        val appLabel: String?,
        val homeOpened: Boolean,
    )

    /** 创建（或复用）虚拟显示；[appQuery] 为空时打开虚拟桌面。 */
    suspend fun start(
        context: Context,
        spec: VirtualDisplaySpec,
        appQuery: String? = null,
    ): Result<StartResult> = lifecycleLock.withLock {
        val app = if (appQuery.isNullOrBlank()) {
            null
        } else {
            runCatching { resolveApp(context, appQuery) }.getOrElse {
                return@withLock Result.failure(it)
            }
        }
        if (!DeviceShell.canUseShellIdentity()) {
            return@withLock Result.failure(
                IllegalStateException("需要 Shizuku 或 root：虚拟屏必须由 shell 身份创建"),
            )
        }
        val server = runCatching { ShowerServerManager.ensureServerStarted(context) }.getOrDefault(false)
        if (!server) {
            return@withLock Result.failure(IllegalStateException("Shower 服务启动失败（Shizuku 是否已授权？）"))
        }
        val controller = VirtualScreenSession.controller
        val created = runCatching { controller.ensureDisplay(context, spec.width, spec.height, spec.dpi) }
            .getOrDefault(false)
        if (!created) {
            return@withLock Result.failure(IllegalStateException("虚拟显示创建失败（看 logcat: ShowerServerManager）"))
        }
        val displayId = controller.getDisplayId()
            ?: return@withLock Result.failure(IllegalStateException("虚拟显示没有返回 display id"))
        val size = controller.getVideoSize() ?: (spec.width to spec.height)
        VirtualScreenSession.markStarted(size, null, spec)

        var homeOpened = false
        if (app != null) {
            val launched = runCatching { controller.launchApp(app.first) }.getOrDefault(false)
            if (!launched) {
                return@withLock Result.failure(IllegalStateException("启动 ${app.second} 失败"))
            }
            VirtualScreenSession.markStarted(size, app.second, spec, app.first)
            VirtualScreenOverlay.updateCurrentAppPackageName(app.first)
        } else {
            homeOpened = runCatching { openHome(context, displayId) }.getOrDefault(false)
            VirtualScreenSession.markStarted(size, if (homeOpened) "桌面" else null, spec)
        }
        saveSpec(context, spec)
        Result.success(StartResult(displayId, size, spec, VirtualScreenSession.appLabel, homeOpened))
    }

    /** 在虚拟屏里启动应用（包名或应用名）。 */
    suspend fun launch(context: Context, appQuery: String): Result<String> = lifecycleLock.withLock {
        val controller = VirtualScreenSession.controller
        val displayId = controller.getDisplayId()
            ?: return@withLock Result.failure(IllegalStateException("虚拟屏没有启动，先调用 start_virtual_display"))
        val app = runCatching { resolveApp(context, appQuery) }.getOrElse {
            return@withLock Result.failure(it)
        }
        val ok = runCatching { controller.launchApp(app.first) }.getOrDefault(false)
        if (!ok) return@withLock Result.failure(IllegalStateException("启动 ${app.second} 失败"))
        VirtualScreenSession.markStarted(
            controller.getVideoSize(),
            app.second,
            VirtualScreenSession.spec,
            app.first,
        )
        Result.success("${app.second} (${app.first}) on display $displayId")
    }

    /** 关闭当前虚拟显示与 Shower 服务。 */
    suspend fun stop(context: Context) = lifecycleLock.withLock {
        VirtualScreenOverlay.hide()
        runCatching { VirtualScreenSession.controller.shutdown() }
        runCatching { ShowerServerManager.stopServer() }
        VirtualScreenSession.markStopped()
    }

    /** 打开虚拟桌面（Home 语义：只影响虚拟屏，不碰主屏）。 */
    suspend fun openHome(context: Context): Boolean = lifecycleLock.withLock {
        val displayId = VirtualScreenSession.controller.getDisplayId() ?: return@withLock false
        openHome(context, displayId)
    }

    private suspend fun openHome(context: Context, displayId: Int): Boolean {
        val component = ComponentName(context, VirtualHomeActivity::class.java).flattenToShortString()
        val command = "am start --display $displayId -f 0x10000000 -n $component"
        val result = DeviceShell.run(command, ShellIdentity.SHELL)
        if (!result.success) {
            android.util.Log.w(
                "VirtualScreenManager",
                "openHome failed: ${result.stderr.ifBlank { result.stdout }}",
            )
        }
        return result.success
    }

    fun specFromPrefs(context: Context): VirtualDisplaySpec {
        val prefs = context.getSharedPreferences("dsh", 0)
        return VirtualDisplaySpec(
            width = prefs.getInt("vs_width", 1080),
            height = prefs.getInt("vs_height", 1920),
            dpi = prefs.getInt("vs_dpi", 320),
        )
    }

    fun saveSpec(context: Context, spec: VirtualDisplaySpec) {
        context.getSharedPreferences("dsh", 0).edit()
            .putInt("vs_width", spec.width)
            .putInt("vs_height", spec.height)
            .putInt("vs_dpi", spec.dpi)
            .apply()
    }

    /** 校验工具传进来的 display id 是否就是当前活着的虚拟屏，并返回真实视频尺寸。 */
    fun videoSizeOf(displayId: Int): Pair<Int, Int>? {
        val controller = VirtualScreenSession.controller
        if (controller.getDisplayId() != displayId) return null
        return controller.getVideoSize()
    }

    fun statusLine(): String {
        val controller = VirtualScreenSession.controller
        val id = controller.getDisplayId()
        val size = controller.getVideoSize()
        val label = VirtualScreenSession.appLabel
        return if (id == null) {
            "virtual display: not running (call start_virtual_display first)"
        } else {
            "virtual display: id=$id size=${size?.first ?: "?"}x${size?.second ?: "?"}" +
                (label?.let { " app=$it" } ?: "")
        }
    }

    /** 包名或应用名的解析；应用名用已安装 launcher activity 的 label 匹配。 */
    fun resolveApp(context: Context, query: String): Pair<String, String> {
        val q = query.trim()
        if (q.isEmpty()) throw IllegalArgumentException("app is required")
        val pm = context.packageManager
        if (q.contains('.')) {
            val launch = pm.getLaunchIntentForPackage(q)
                ?: throw IllegalArgumentException("$q 没有可启动的 Activity（没装？）")
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(q, 0)).toString()
            }.getOrDefault(q)
            return q to label
        }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { resolveInfo ->
                val label = resolveInfo.loadLabel(pm).toString()
                Triple(resolveInfo.activityInfo.packageName, label, resolveInfo)
            }
            .distinctBy { it.first }
        val exact = apps.filter { it.second.equals(q, ignoreCase = true) }
        val starts = apps.filter { it.second.startsWith(q, ignoreCase = true) }
        val contains = apps.filter { it.second.contains(q, ignoreCase = true) }
        val hit = (exact + starts + contains).firstOrNull()
            ?: throw IllegalArgumentException("没找到应用「$q」，可以改用包名")
        return hit.first to hit.second
    }

    /** 供“打开应用”列表使用（虚拟桌面与页面共用）。 */
    data class LaunchableApp(val packageName: String, val label: String, val activityName: String)

    fun launchableApps(context: Context, includeSelf: Boolean = false): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)
            .asSequence()
            .filter { includeSelf || it.activityInfo.packageName != context.packageName }
            .map {
                LaunchableApp(
                    packageName = it.activityInfo.packageName,
                    label = it.loadLabel(pm).toString(),
                    activityName = it.activityInfo.name,
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

}
