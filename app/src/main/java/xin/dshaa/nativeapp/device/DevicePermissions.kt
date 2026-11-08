package xin.dshaa.nativeapp.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import rikka.shizuku.Shizuku
import xin.dshaa.nativeapp.device.a11y.DshAccessibilityService

/**
 * 设备能力的「授权状态 + 去授权」唯一入口。
 *
 * 为什么单拎一个 object：权限状态散落在 Shizuku / Settings / AccessibilityManager / PowerManager
 * 四个系统服务里，界面上要的是同一个形状（有没有、怎么开、开完回来怎么刷新）。
 * 这一层只做「查 + 跳转」，不持有任何 Compose 状态。
 */
object DevicePermissions {

    /** Shizuku 管理器包名（Shizuku 官方 App）。 */
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private const val SHIZUKU_REQUEST_CODE = 0x5A1

    /** Shizuku 三态：没装 / 装了没跑（binder 没起来）/ 跑了没授权 / 已授权。 */
    enum class ShizukuState { NOT_INSTALLED, NOT_RUNNING, NOT_GRANTED, GRANTED }

    fun shizukuState(context: Context): ShizukuState {
        if (!isShizukuInstalled(context)) return ShizukuState.NOT_INSTALLED
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!running) return ShizukuState.NOT_RUNNING
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (granted) ShizukuState.GRANTED else ShizukuState.NOT_GRANTED
    }

    fun isShizukuInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /**
     * 发一次 Shizuku 授权请求（弹 Shizuku 自己的确认框）。
     *
     * 回调可能在任意线程，调用方自己切主线程；binder 没起来/没装时直接回 false，
     * 不抛异常（界面上这时应该显示「先启动 Shizuku」而不是崩）。
     */
    fun requestShizuku(onResult: (Boolean) -> Unit) {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!running) {
            onResult(false)
            return
        }
        val already = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)
        if (already) {
            onResult(true)
            return
        }
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != SHIZUKU_REQUEST_CODE) return
                runCatching { Shizuku.removeRequestPermissionResultListener(this) }
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        runCatching {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        }.onFailure {
            runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
            onResult(false)
        }
    }

    /** 无障碍服务是否在系统里被启用（不是「当前进程连上了」）。 */
    fun accessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, DshAccessibilityService::class.java)
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        return am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val name = info.resolveInfo?.serviceInfo?.let { ComponentName(it.packageName, it.name) }
                name == expected
            }
    }

    fun overlayGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * 「通知使用权」是否授予（docs/76 W24 的通知附件靠它）。
     *
     * 与 [notificationsEnabled] 不是一回事：后者是本 App 自己发通知的总开关；
     * 这里是「能不能读别的 App 的通知」，在系统设置 → 通知使用权 里单独授权。
     */
    fun notificationAccessGranted(context: Context): Boolean = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }.getOrDefault(false)

    /** 跳「通知使用权」页（一般位于 设置 → 通知与状态栏 → 通知使用权）。 */
    fun openNotificationAccessSettings(context: Context): Boolean = start(
        context,
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    /** 定位权限（粗/精任一即可，docs/76 W24 的位置附件）。 */
    fun locationGranted(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** 所有文件访问（Android 11+ 的 MANAGE_EXTERNAL_STORAGE；旧系统退回读存储权限）。 */
    fun allFilesGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager()
        return context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun batteryOptimizationIgnored(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /** 打开 Shizuku 管理器（没装时返回 false，由调用方给安装引导）。 */
    fun openShizukuManager(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return false
        return start(context, intent)
    }

    /** 跳转 Shizuku 的商店/官网安装页。 */
    fun openShizukuStore(context: Context): Boolean {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE"))
        if (start(context, market)) return true
        return start(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/")))
    }

    fun openAccessibilitySettings(context: Context): Boolean {
        // Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS 在部分 compileSdk 上不是公开常量，直接用 action 字符串
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
            putExtra(
                Intent.EXTRA_COMPONENT_NAME,
                ComponentName(context, DshAccessibilityService::class.java).flattenToString(),
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (start(context, details)) return true
        return start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openOverlaySettings(context: Context): Boolean = start(
        context,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    fun openNotificationSettings(context: Context): Boolean = start(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    fun openAllFilesSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return start(
                context,
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        return start(
            context,
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** 申请电池优化白名单（部分 ROM 会先弹系统确认框）。 */
    fun requestBatteryExemption(context: Context): Boolean = runCatching {
        start(
            context,
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.getOrDefault(false)

    fun openAppDetails(context: Context): Boolean = start(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
