package xin.dshaa.nativeapp.device.notify

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification

/**
 * 一条已捕获的通知（只留附件要用的字段，不持有系统 Notification 对象）。
 *
 * [postedAt] 用 `StatusBarNotification.postTime`，跨进程/重启都稳定；
 * [ongoing] 是常驻通知（音乐播放、下载进度等），附件里会标出来。
 */
data class NoticeEntry(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val content: String,
    val postedAt: Long,
    val ongoing: Boolean,
)

/**
 * 「通知」附件的数据源（docs/76 W24）。
 *
 * 为什么自己存一份而不是每次问系统：`NotificationListenerService.activeNotifications`
 * 只给「此刻还挂在通知栏里」的条目，用户点掉/划掉的会丢；附件要的是「最近发生过什么」。
 * 这里按 key 去重、按时间倒序保留 [MAX_ENTRIES] 条；进程重启后 listener 重连时用
 * `activeNotifications` 重新播种，不会从空开始。
 *
 * 没有加持久化：通知正文属于敏感数据，落盘的价值低于风险；进程被系统杀掉后丢历史，
 * 但重连时仍能拿到当前通知栏里的内容（docs/76 §十三 记录为有意差异）。
 */
object NotificationFeed {

    const val MAX_ENTRIES = 30

    private val lock = Any()
    private val entries = ArrayDeque<NoticeEntry>()
    private val labelCache = HashMap<String, String>()

    /** listener 是否已连上（给 UI 区分「没授权」和「授权了但服务没起来」）。 */
    @Volatile
    var connected: Boolean = false
        private set

    fun onConnected() {
        connected = true
    }

    fun onDisconnected() {
        connected = false
    }

    fun record(context: Context, sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        // 自己的前台服务通知不进附件（用户要的是「别的 App 来了什么」）。
        if (sbn.packageName == context.packageName) return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
            )?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val entry = NoticeEntry(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel(context, sbn.packageName),
            title = title,
            content = text,
            postedAt = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis(),
            ongoing = sbn.isOngoing,
        )
        synchronized(lock) {
            entries.removeAll { it.key == entry.key }
            entries.addFirst(entry)
            while (entries.size > MAX_ENTRIES) entries.removeLast()
        }
    }

    fun remove(key: String) {
        synchronized(lock) { entries.removeAll { it.key == key } }
    }

    /** listener 刚连上时播种：按时间升序 addFirst，最新的排最前。 */
    fun seed(context: Context, active: List<StatusBarNotification>) {
        active.sortedBy { it.postTime }.forEach { record(context, it) }
    }

    fun recent(limit: Int): List<NoticeEntry> = synchronized(lock) {
        entries.take(limit.coerceIn(1, MAX_ENTRIES))
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    private fun appLabel(context: Context, packageName: String): String {
        synchronized(lock) { labelCache[packageName] }?.let { return it }
        val label = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: packageName
        synchronized(lock) { labelCache[packageName] = label }
        return label
    }
}
