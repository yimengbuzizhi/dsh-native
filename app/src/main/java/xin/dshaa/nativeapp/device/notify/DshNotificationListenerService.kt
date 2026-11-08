package xin.dshaa.nativeapp.device.notify

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 「通知」附件的数据源（docs/76 W24）。
 *
 * 权限是系统的「通知使用权」(`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`)，
 * 不是运行时权限：没授权时系统不会 bind 本服务，[NotificationFeed] 里就没有数据，
 * 附件入口会提示去开（[xin.dshaa.nativeapp.device.attach.AttachmentCapture]）。
 *
 * 注意：本服务与 App 同进程；进程被杀后系统会在有新通知时重新拉起它，重连时用
 * `activeNotifications` 播种最近内容（见 [NotificationFeed.seed]）。
 */
class DshNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationFeed.onConnected()
        val active = runCatching { activeNotifications?.toList() }.getOrNull().orEmpty()
        NotificationFeed.seed(this, active)
        Log.i(TAG, "listener connected active=${active.size} feed=${NotificationFeed.size()}")
    }

    override fun onListenerDisconnected() {
        NotificationFeed.onDisconnected()
        Log.i(TAG, "listener disconnected feed=${NotificationFeed.size()}")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        NotificationFeed.record(this, notification)
        Log.d(
            TAG,
            "posted pkg=${notification.packageName} ongoing=${notification.isOngoing} " +
                "feed=${NotificationFeed.size()}",
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        NotificationFeed.remove(notification.key)
        Log.d(TAG, "removed pkg=${notification.packageName} feed=${NotificationFeed.size()}")
    }

    private companion object {
        const val TAG = "DshNotify"
    }
}
