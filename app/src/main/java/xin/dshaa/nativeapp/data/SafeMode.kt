package xin.dshaa.nativeapp.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 崩溃循环自救（安全模式）。
 *
 * 用户 2026-11-24 反馈：有人写了坏插件，装上后 App 一开就崩，只能重装。手机上没有
 * Android 官方的 safe mode，插件又是**同进程**跑的，所以需要一个自己的看门狗：
 *
 * - 每次进程启动记一个时间戳（`Application.onCreate` 最早执行，插件还没开始加载）；
 * - **60 秒内出现 5 次启动** → 认定崩溃循环，进入安全模式；
 * - 进入时把第三方（导入的）插件全部停用，并记下「本来开着的是哪些」；
 * - 本次开机不自动拉起 Node 宿主（Node 宿主坏插件同理，虽然它自己也会隔离）；
 * - 聊天页顶部显示安全模式横幅，可一键恢复那批插件（恢复后如果还崩，会再次触发）。
 *
 * 内置插件（`builtin.*`）不在 `PluginStore` 里，不受影响——安全模式下界面/回合/存储
 * 仍然是内置实现，App 一定能画出「恢复」入口。
 *
 * 为什么用「启动时间戳」而不是真正的崩溃回调：崩溃在原生层/JS 里都可能直接杀进程，
 * 来不及写标记；冷启动次数是唯一在崩溃后仍然可靠的信号（Android 官方安全模式同思路）。
 */
class SafeMode(context: Context) {

    private val sp: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 当前是否处于安全模式（持久化；恢复时清掉）。 */
    val active: Boolean get() = sp.getBoolean(KEY_ACTIVE, false)

    /** 安全模式下被停用的第三方插件 id（恢复时按这份名单开回去）。 */
    val disabledIds: Set<String>
        get() = sp.getStringSet(KEY_DISABLED, emptySet()).orEmpty()

    /** 触发时间（给 UI 说明用；0 = 没触发过）。 */
    val triggeredAt: Long get() = sp.getLong(KEY_AT, 0L)

    /**
     * 记一次进程启动，返回 true = **这次**启动应进入安全模式（调用方立即停用第三方插件）。
     *
     * 两条触发口径（2026-11-24 第二版，用户反馈「进入方式只有重启五次」）：
     *
     * 1. **上次开机没走完**（[markBootOk] 没被调用：插件加载期崩溃/卡死、或用户等不到界面就强杀）：
     *    连续 [SafeModePolicy.MAX_FAILED_BOOTS] 次就进安全模式——不用凑够 5 次，也不用挤在一分钟内。
     *    这是「在加载插件之前就检测」的主路径：判定只看上一次启动有没有到过「运行时就绪」。
     * 2. 一分钟内第 [SafeModePolicy.MAX_LAUNCHES] 次启动（第一版口径，保留）。
     */
    fun recordLaunch(now: Long = System.currentTimeMillis()): Boolean {
        if (active) return false
        val firstEver = !sp.getBoolean(KEY_LAUNCHED, false)
        val lastBootOk = sp.getBoolean(KEY_BOOT_OK, true)
        var failedBoots = if (lastBootOk) 0 else sp.getInt(KEY_FAILED_BOOTS, 0)
        if (!firstEver && !lastBootOk) failedBoots += 1
        // 本次启动开始：先记成「未完成」，只有 markBootOk() 才会翻成 true。
        sp.edit()
            .putBoolean(KEY_LAUNCHED, true)
            .putBoolean(KEY_BOOT_OK, false)
            .putInt(KEY_FAILED_BOOTS, failedBoots)
            .apply()
        if (failedBoots >= SafeModePolicy.MAX_FAILED_BOOTS) {
            sp.edit().putInt(KEY_FAILED_BOOTS, 0).apply()
            Log.w(TAG, "safe mode: $failedBoots consecutive boots never reached ready")
            reason = REASON_BOOT_FAILED
            return true
        }
        val recent = SafeModePolicy.recent(launches(), now).toMutableList()
        recent += now
        if (recent.size >= SafeModePolicy.MAX_LAUNCHES) {
            sp.edit().remove(KEY_LAUNCHES).apply()
            Log.w(TAG, "safe mode: ${recent.size} launches within ${SafeModePolicy.WINDOW_MS}ms")
            reason = REASON_LAUNCHES
            return true
        }
        sp.edit().putString(KEY_LAUNCHES, recent.joinToString(",")).apply()
        return false
    }

    /** 启动真正走完（运行时就绪）时调用：清失败计数，下次启动不再按「上次没完成」算。 */
    fun markBootOk() {
        sp.edit()
            .putBoolean(KEY_BOOT_OK, true)
            .putInt(KEY_FAILED_BOOTS, 0)
            .apply()
    }

    /** 进入安全模式的原因（`launches` / `boot_failed` / `manual`），UI 用来说明为什么被停用。 */
    var reason: String = REASON_LAUNCHES
        private set

    /** 进入安全模式并记下要停用的插件；调用方负责真正停用 [PluginStore]。 */
    fun activate(disabled: Set<String>, reason: String = this.reason, now: Long = System.currentTimeMillis()) {
        sp.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_AT, now)
            .putStringSet(KEY_DISABLED, disabled)
            .putString(KEY_REASON, reason)
            .apply()
        Log.w(TAG, "safe mode on: disabled=${disabled.size} plugins reason=$reason")
    }

    /** 退出安全模式（不负责重新启用插件；调用方按 [disabledIds] 开回去）。 */
    fun deactivate() {
        sp.edit()
            .putBoolean(KEY_ACTIVE, false)
            .remove(KEY_DISABLED)
            .remove(KEY_LAUNCHES)
            .apply()
        Log.i(TAG, "safe mode off")
    }

    /** 持久化的触发原因；没记录时按「5 次启动」处理。 */
    val storedReason: String
        get() = sp.getString(KEY_REASON, REASON_LAUNCHES) ?: REASON_LAUNCHES

    /** 最近窗口内的启动时间戳（旧 → 新）。 */
    fun launches(): List<Long> =
        sp.getString(KEY_LAUNCHES, "")
            .orEmpty()
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .sorted()

    companion object {
        const val TAG = "DshSafeMode"

        const val REASON_LAUNCHES = "launches"
        const val REASON_BOOT_FAILED = "boot_failed"
        const val REASON_MANUAL = "manual"

        private const val PREFS = "dsh"
        private const val KEY_LAUNCHES = "safe_mode_launches"
        private const val KEY_ACTIVE = "safe_mode_active"
        private const val KEY_DISABLED = "safe_mode_disabled_plugins"
        private const val KEY_AT = "safe_mode_triggered_at"
        private const val KEY_REASON = "safe_mode_reason"
        private const val KEY_LAUNCHED = "safe_mode_launched_once"
        private const val KEY_BOOT_OK = "safe_mode_last_boot_ok"
        private const val KEY_FAILED_BOOTS = "safe_mode_failed_boots"
    }
}

/**
 * 安全模式的纯判定逻辑（不碰 Android，JVM 单测直接覆盖）。
 *
 * 口径来自用户 2026-11-24 原话：「一分钟之内重启应用 5 次进入安全模式」。
 */
object SafeModePolicy {

    /** 判定窗口：一分钟。 */
    const val WINDOW_MS = 60_000L

    /** 判定次数：5 次启动。 */
    const val MAX_LAUNCHES = 5

    /** 连续多少次「开机没走到就绪」进入安全模式（插件加载期崩溃的主路径）。 */
    const val MAX_FAILED_BOOTS = 2

    /** 窗口内的启动（保留乱序输入、去重、按时间升序），`now` 之前的时间戳才算。 */
    fun recent(launches: List<Long>, now: Long): List<Long> =
        launches
            .filter { it in (now - WINDOW_MS)..now }
            .distinct()
            .sorted()

    /** 再记一次启动后是否达到阈值（含这一次）。 */
    fun shouldTrigger(launches: List<Long>, now: Long): Boolean =
        recent(launches, now).size + 1 >= MAX_LAUNCHES

    /** 连续「开机没走到就绪」是否达到阈值（不必挤在一分钟内）。 */
    fun failedBootsTrigger(consecutiveFailedBoots: Int): Boolean =
        consecutiveFailedBoots >= MAX_FAILED_BOOTS
}
