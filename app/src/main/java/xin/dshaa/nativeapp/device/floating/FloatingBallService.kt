package xin.dshaa.nativeapp.device.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import xin.dshaa.nativeapp.MainActivity
import xin.dshaa.nativeapp.chat.ChatMirror

/**
 * 悬浮窗前台服务（docs/76 §五 S1-S13）。
 *
 * 与 Operit 的结构对齐：Service 只管生命周期/通知，窗口全部交给
 * [FloatingWindowManager]（单 ComposeView + 6 模式）；聊天数据走 [ChatMirror] 共享主界面
 * 的 AppViewModel（差异见 docs/76 §十三.1）。
 *
 * 2026-11-09 起由 [DshApplication] 的 ProcessLifecycle 驱动：应用不在前台自动出现、
 * 回前台自动收起；长按快捷菜单已删除（点按球直接展开 WINDOW，见 docs/79）。
 */
class FloatingBallService : Service(), FloatingWindowManager.Callback {

    private lateinit var windowManager: FloatingWindowManager
    private lateinit var state: FloatingWindowState
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private var hasHandledStart = false
    private var autoExitRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        acquireWakeLock()
        state = FloatingWindowState(this)
        windowManager = FloatingWindowManager(this, state, this)
        if (!windowManager.show()) {
            stopSelf()
        } else {
            running = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::windowManager.isInitialized) {
            stopSelf()
            return START_NOT_STICKY
        }
        val keepIfExists = intent?.getBooleanExtra(EXTRA_KEEP_IF_EXISTS, false) == true
        if (keepIfExists && hasHandledStart) {
            return START_STICKY
        }
        intent?.getStringExtra(EXTRA_INITIAL_MODE)?.let { name ->
            runCatching { FloatingMode.valueOf(name) }.getOrNull()?.let { mode ->
                if (state.currentMode.value != mode) windowManager.switchMode(mode)
            }
        }
        hasHandledStart = true

        autoExitRunnable?.let { main.removeCallbacks(it) }
        autoExitRunnable = null
        if (intent?.hasExtra(EXTRA_AUTO_EXIT_AFTER_MS) == true) {
            val timeout = intent.getLongExtra(EXTRA_AUTO_EXIT_AFTER_MS, -1L)
            if (timeout > 0) {
                val r = Runnable { closeSelf() }
                autoExitRunnable = r
                main.postDelayed(r, timeout)
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        super.onTaskRemoved(rootIntent)
        runCatching {
            startService(Intent(applicationContext, this.javaClass).setPackage(packageName))
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::state.isInitialized) state.saveState()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::state.isInitialized) state.saveState()
    }

    override fun onDestroy() {
        running = false
        autoExitRunnable?.let { main.removeCallbacks(it) }
        autoExitRunnable = null
        if (::state.isInitialized) state.saveState()
        if (::windowManager.isInitialized) windowManager.destroy()
        releaseWakeLock()
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onClose() {
        closeSelf()
    }

    override fun saveState() {
        if (::state.isInitialized) state.saveState()
    }

    private fun closeSelf() {
        if (::windowManager.isInitialized) windowManager.prepareForExit()
        ChatMirror.stop()
        stopSelf()
    }

    // ---------------- 基础设施 ----------------

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Dsh:FloatingChatServiceWakeLock",
            ).apply { setReferenceCounted(false) }
        }
        runCatching {
            if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "鲸歌悬浮窗", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
            )
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("鲸歌悬浮窗")
            .setContentText("点球展开对话")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pending)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "dsh-floating"
        private const val NOTIFICATION_ID = 4001
        const val EXTRA_INITIAL_MODE = "INITIAL_MODE"
        const val EXTRA_KEEP_IF_EXISTS = "KEEP_IF_EXISTS"
        const val EXTRA_AUTO_EXIT_AFTER_MS = "AUTO_EXIT_AFTER_MS"

        /** 服务是否在跑。进程重启后由 [onCreate] 重新置位，只用于前后台自动切换的幂等判断。 */
        @Volatile
        private var running = false

        fun start(context: Context, mode: FloatingMode? = FloatingMode.BALL) {
            val intent = Intent(context, FloatingBallService::class.java)
            if (mode != null) intent.putExtra(EXTRA_INITIAL_MODE, mode.name)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBallService::class.java))
        }

        /**
         * 应用回到前台：收起悬浮球（用户 2026-11-09：光团只在应用不在前台时出现）。
         *
         * 只停服务、不写任何「用户关掉了」的状态——下次退到后台还会自动出现，
         * 这样长按菜单里的「关闭悬浮球」只是临时收起，不需要再找开启入口。
         *
         * 不判断 [running]：服务可能还在后台启动途中，回到前台时要能把它拦下来
         * （stopService 对未运行的服务是安全空操作）。
         */
        fun hideWhenForeground(context: Context) {
            runCatching { context.stopService(Intent(context, FloatingBallService::class.java)) }
        }

        /**
         * 应用退到后台：自动显示悬浮球。
         *
         * - 没有「显示在其他应用上层」权限时静默跳过（不弹 Toast、不引导，避免退后台就被打扰）；
         * - Android 12+ 后台启动前台服务有限制，SYSTEM_ALERT_WINDOW 是官方豁免之一；
         *   个别 ROM 仍可能拒绝，runCatching 兜底不让进程崩。
         */
        fun showWhenBackground(context: Context) {
            if (running) return
            if (!canDrawOverlays(context)) return
            runCatching { start(context) }.onFailure {
                android.util.Log.w("FloatingBall", "background start rejected: ${it.message}")
            }
        }

        fun overlayPermissionIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun toastPermission(context: Context) {
            Toast.makeText(context, "请先授予「显示在其他应用上层」权限", Toast.LENGTH_SHORT).show()
        }
    }
}
