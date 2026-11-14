package xin.dshaa.nativeapp.device.attach

import android.content.Context
import android.location.Location
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import xin.dshaa.nativeapp.chat.ChatMirror
import xin.dshaa.nativeapp.device.DevicePermissions
import xin.dshaa.nativeapp.device.notify.NoticeEntry
import xin.dshaa.nativeapp.device.notify.NotificationFeed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 附件抓取结果：[ok] 为 false 时 [message] 直接给用户看；
 * [needsGrant] 表示「缺授权」，调用方据此跳系统授权页（通知使用权）。
 */
data class AttachOutcome(
    val ok: Boolean,
    val message: String,
    val needsGrant: Boolean = false,
)

/**
 * 悬浮窗附件面板的「通知 / 位置」两项（docs/76 W24）。
 *
 * 与 Operit 的差异：Operit 走它自己的 `get_notifications` / `get_device_location`
 * 设备工具拿 JSON 再包成附件；原生没有那套设备工具，直接在附件入口抓一次，
 * 产出待发文本附件（`notifications-*.txt` / `location-*.txt`），模型看到的内容等价。
 */
object AttachmentCapture {

    private const val TAG = "DshAttach"

    /** 通知使用权（系统设置里单独一页，不是运行时权限）。 */
    fun notificationsEnabled(context: Context): Boolean = runCatching {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }.getOrDefault(false)

    fun openNotificationAccessSettings(context: Context): Boolean =
        DevicePermissions.openNotificationAccessSettings(context)

    /**
     * 把最近 [limit] 条通知拼成文本附件。
     * 成功返回 [AttachOutcome.ok]；失败时 [AttachOutcome.message] 是原因。
     */
    fun attachNotifications(context: Context, limit: Int = 10): AttachOutcome {
        if (!notificationsEnabled(context)) {
            return AttachOutcome(
                ok = false,
                message = "还没有通知使用权：去系统设置里允许「鲸歌」读取通知，再回来点一次",
                needsGrant = true,
            )
        }
        if (ChatMirror.state == null) {
            return AttachOutcome(false, "还没有打开的会话：先回主界面打开鲸歌")
        }
        val entries = NotificationFeed.recent(limit)
        if (entries.isEmpty()) {
            return AttachOutcome(false, "最近没有捕获到通知：等有新通知进来再点一次")
        }
        val now = System.currentTimeMillis()
        val name = "notifications-${fileStamp(now)}.txt"
        val text = formatNotifications(entries, now)
        if (!ChatMirror.attachText(name, text)) {
            return AttachOutcome(false, "现在不能加附件：会话已关闭或是只读的")
        }
        Log.i(TAG, "notifications attached n=${entries.size} chars=${text.length} name=$name")
        return AttachOutcome(true, "已添加通知附件（${entries.size} 条）")
    }

    /** 位置文本附件。地址是 Geocoder 的 best-effort（拿不到就只给坐标）。 */
    fun attachLocation(context: Context, location: Location, address: String?,
                       target: xin.dshaa.ui.shellapi.chat.ChatSessionState? = ChatMirror.state): AttachOutcome {
        if (target == null) {
            return AttachOutcome(false, "还没有打开的会话：先回主界面打开鲸歌")
        }
        val now = System.currentTimeMillis()
        val name = "location-${fileStamp(now)}.txt"
        val text = formatLocation(location, address, now)
        if (!ChatMirror.attachText(name, text, target)) {
            return AttachOutcome(false, "现在不能加附件：会话已关闭或是只读的")
        }
        Log.i(
            TAG,
            "location attached lat=${"%.5f".format(Locale.US, location.latitude)} " +
                "lon=${"%.5f".format(Locale.US, location.longitude)} " +
                "provider=${location.provider} acc=${location.accuracy} address=${!address.isNullOrBlank()}",
        )
        return AttachOutcome(true, "已添加位置附件")
    }

    internal fun formatNotifications(entries: List<NoticeEntry>, now: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return buildString {
            appendLine("# 最近通知（${entries.size} 条，抓取于 ${fmt.format(Date(now))}）")
            entries.forEachIndexed { index, entry ->
                appendLine()
                appendLine("${index + 1}. [${entry.appLabel}] ${clip(entry.title, 120).ifBlank { "(无标题)" }}")
                val flags = buildString {
                    append(entry.packageName)
                    append(" · ")
                    append(fmt.format(Date(entry.postedAt)))
                    if (entry.ongoing) append(" · 常驻通知")
                }
                appendLine("   $flags")
                val body = clip(entry.content, 400)
                if (body.isNotEmpty()) appendLine("   $body")
            }
        }
    }

    internal fun formatLocation(location: Location, address: String?, now: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val age = (now - location.time).coerceAtLeast(0)
        return buildString {
            appendLine("# 位置附件")
            appendLine("纬度: ${"%.6f".format(Locale.US, location.latitude)}")
            appendLine("经度: ${"%.6f".format(Locale.US, location.longitude)}")
            appendLine("精度: ${location.accuracy.toInt()} m")
            appendLine("来源: ${location.provider ?: "unknown"}")
            appendLine("定位时间: ${fmt.format(Date(location.time))}（${humanAge(age)}前）")
            if (location.hasAltitude()) {
                appendLine("海拔: ${"%.1f".format(Locale.US, location.altitude)} m")
            }
            if (!address.isNullOrBlank()) appendLine("地址: $address")
            appendLine("坐标系: 设备原始坐标（WGS84；未转 GCJ-02）")
        }
    }

    private fun fileStamp(now: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))

    private fun clip(text: String, max: Int): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= max) oneLine else oneLine.take(max) + "…"
    }

    private fun humanAge(ms: Long): String = when {
        ms < 60_000 -> "${ms / 1000} 秒"
        ms < 3_600_000 -> "${ms / 60_000} 分钟"
        ms < 86_400_000 -> "${ms / 3_600_000} 小时"
        else -> "${ms / 86_400_000} 天"
    }
}
