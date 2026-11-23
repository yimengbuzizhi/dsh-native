package xin.dshaa.nativeapp.node

/**
 * 宿主崩溃循环策略（docs/187 §九 第 5 项）。
 *
 * 旧实现每次 Ready 就把重启计数清零，于是「起 1 秒就崩」可以无限重启；这里改成时间窗：
 * - 只有**连续运行 ≥ [stableMs]**（60s）的宿主崩了，才把历史崩溃次数清零；
 * - 任意 [windowMs]（5 分钟）内累计 ≥ [maxCrashes]（3）次 → 不再重启，进 `Failed`；
 * - 显式「重试 / 重新加载」会 [reset]（用户主动重来不该被旧计数拦住）。
 *
 * 纯 Kotlin：JVM 用例直接喂时间戳，不依赖真机/进程。
 */
class NodeCrashPolicy(
    private val maxCrashes: Int = 3,
    private val windowMs: Long = 5 * 60_000L,
    private val stableMs: Long = 60_000L,
) {

    /** [restart] = false 时调用方必须进 `Failed`，不再起进程。 */
    data class Decision(
        val restart: Boolean,
        val attempt: Int,
        val reason: String,
    )

    private var runStartedAt = 0L
    private val crashTimes = ArrayDeque<Long>()

    /** 宿主 Ready：记下本段运行的起点。 */
    @Synchronized
    fun onReady(now: Long) {
        runStartedAt = now
    }

    /** 宿主退出/启动失败：按时间窗决定重启还是收手。 */
    @Synchronized
    fun onExit(now: Long, reason: String): Decision {
        if (runStartedAt > 0 && now - runStartedAt >= stableMs) {
            crashTimes.clear()
        }
        runStartedAt = 0
        while (crashTimes.isNotEmpty() && now - crashTimes.first() > windowMs) {
            crashTimes.removeFirst()
        }
        crashTimes.addLast(now)
        val recent = crashTimes.size
        return if (recent >= maxCrashes) {
            Decision(
                restart = false,
                attempt = recent,
                reason = "5 分钟内连续崩溃 $recent 次，不再自动重启（最后一次：$reason）",
            )
        } else {
            Decision(restart = true, attempt = recent, reason = reason)
        }
    }

    @Synchronized
    fun reset() {
        runStartedAt = 0L
        crashTimes.clear()
    }

    /** 证据/排障用：最近窗口里的崩溃时间戳。 */
    @Synchronized
    fun crashTimes(): List<Long> = crashTimes.toList()
}
