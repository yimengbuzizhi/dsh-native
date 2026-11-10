package xin.dshaa.nativeapp.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import xin.dshaa.nativeapp.MainActivity

/**
 * 插件通知（M57，鲸歌私有扩展点）。
 *
 * DSH 没有 `ctx.notifications`；社区插件的「通知」是浏览器 `new Notification()` + WebAudio。
 * 手机端把两者收敛到 Android 通知：
 * - 插件调 `ctx.whale.notify({title, body})`；
 * - 插件调 `new Notification(title, {body})`（JS shim 里重定向到同一个宿主方法）。
 *
 * 没有通知权限时静默丢弃（不抛错，插件逻辑不因系统权限中断）；点击通知回到鲸歌主界面。
 */
object PluginNotifier {
    private const val CHANNEL_ID = "dsh_plugin_notify"
    private const val CHANNEL_NAME = "插件通知"

    fun notify(context: Context, title: String, body: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    manager.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT),
                    )
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title.take(80).ifBlank { "插件通知" })
            .setContentText(body.take(200))
            .setStyle(Notification.BigTextStyle().bigText(body.take(1000)))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching { manager.notify((System.currentTimeMillis() and 0xFFFF).toInt(), notification) }
    }
}
