package xin.dshaa.nativeapp.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import xin.dshaa.nativeapp.MainActivity

/**
 * 一轮对话（或后台 bash 任务）跑着的时候的保活前台服务。
 *
 * 为什么需要：App 退到后台后进程变成 cached，可能被 cached-app freezer 冻住或被 LMK 回收，
 * 模型请求 / bash / 工具调用就停在半路（docs/47 记录的「turn 无声结束」）。有前台服务时
 * 进程是 foreground-service 状态，不会被冻、oom_adj 也低得多；再配一个 PARTIAL_WAKE_LOCK，
 * 锁屏后 CPU 也不会睡过去。
 *
 * 生命周期由 [AppViewModel] 管：turn 开始 start、turn 结束（且没有后台任务在跑）stop。
 * 通知是 Android 对前台服务的硬性要求，放在 IMPORTANCE_MIN 静音渠道，尽量不打扰。
 */
class TurnKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastText: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel()
        startInForeground(resolveText(null))
        acquireWakeLock()
        Log.i(TAG, "foreground start")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "stop requested from notification")
                stopListener?.invoke()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val text = resolveText(intent?.getStringExtra(EXTRA_TEXT))
                updateText(text)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        instance = null
        Log.i(TAG, "foreground stop")
        super.onDestroy()
    }

    /** 用户从最近任务划掉 App 时服务继续跑（turn 正在收尾），收尾由 ViewModel 负责。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed, keep alive")
        super.onTaskRemoved(rootIntent)
    }

    private fun startInForeground(text: String) {
        lastText = text
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            // 上限 2 小时兜底：正常由 onDestroy 释放，异常退出也不会把电池按住不放。
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsh:turn").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.onFailure { Log.w(TAG, "wake lock failed: ${it.message}") }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
        wakeLock = null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "任务运行中", NotificationManager.IMPORTANCE_MIN).apply {
            description = "对话/后台任务跑着的时候显示，避免切后台被系统掐断"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TurnKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("DSH 正在运行")
            .setContentText(text)
            .setSmallIcon(applicationInfo.icon)
            .setContentIntent(pending)
            .addAction(0, "停止", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private fun notify(notification: Notification) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
    }

    /** 文案没变就不打扰通知栏（一轮 turn 里 setStatus 会调很多次）。 */
    private fun updateText(text: String) {
        if (text == lastText) return
        lastText = text
        notify(buildNotification(text))
    }

    private fun resolveText(text: String?): String =
        text?.trim()?.takeIf { it.isNotEmpty() } ?: "对话进行中 · 切后台也会继续跑"

    companion object {
        private const val TAG = "DshTurn"
        private const val CHANNEL_ID = "dsh-turn"
        private const val NOTIFICATION_ID = 4101
        private const val EXTRA_TEXT = "text"
        private const val WAKE_LOCK_TIMEOUT_MS = 2L * 60 * 60 * 1000

        const val ACTION_STOP = "xin.dshaa.nativeapp.action.STOP_TURN"

        /** 通知上的「停止」按钮 → 由 ViewModel 装进来的停止动作（进程内）。 */
        @Volatile
        var stopListener: (() -> Unit)? = null

        /** 活着的实例（只用来原地更新通知文案，避免每次 setStatus 都 startService 一次）。 */
        @Volatile
        private var instance: TurnKeepAliveService? = null

        /** turn / 后台任务还在跑时调用；已经在跑就只更新文案。 */
        fun start(context: Context, text: String? = null) {
            val running = instance
            if (running != null) {
                if (!text.isNullOrBlank()) running.updateText(text.trim())
                return
            }
            val intent = Intent(context, TurnKeepAliveService::class.java)
                .putExtra(EXTRA_TEXT, text)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                // Android 12+ 后台起前台服务会被拒（比如定时提醒在后台触发）：turn 照跑，只是没保活。
                Log.w(TAG, "start foreground service failed: ${it.message}")
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TurnKeepAliveService::class.java)) }
        }
    }
}
