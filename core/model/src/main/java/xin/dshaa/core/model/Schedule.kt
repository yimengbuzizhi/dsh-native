package xin.dshaa.core.model

/**
 * 一条提醒的触发方式。
 *
 * 形状对齐桌面快照 `prompt/0.2.2-rc.1/tools.json` 里 `schedule_*` 的参数
 * （旧版 DSH 的「会话内提醒」API，不是 npm 0.2.2 的 rrule 版）：
 * `after_seconds` / `every_seconds` / `daily{time,time_zone}` /
 * `weekly{time,time_zone,weekdays}` / `cron{expression,time_zone}` / `at`。
 *
 * `kind` 取值与参数名一一对应（快照的 snake_case），便于原样回显给模型。
 */
data class ScheduleTiming(
    val kind: String,
    /** 一次性：创建后多少秒触发。 */
    val afterSeconds: Long? = null,
    /** 固定间隔（≥60 秒，按创建/保存时刻对齐）。 */
    val everySeconds: Long? = null,
    /** 一次性绝对时刻（UTC epoch millis）。 */
    val atMillis: Long? = null,
    /** 本地时间 `HH:mm:ss[.SSS]`（daily/weekly）。 */
    val time: String? = null,
    /** `UTC` 或 IANA 时区（daily/weekly/cron/at 对象形式）。 */
    val timeZone: String? = null,
    /** ISO 星期 1..7（周一=1），weekly 用。 */
    val weekdays: List<Int> = emptyList(),
    /** 五段 Vixie cron（cron 用）。 */
    val expression: String? = null,
)

/**
 * 会话内的一条提醒（持久化在 `<files>/schedules.json`，不进 v4 会话）：
 * 提醒属于「宿主」，会话只是投递目标；会话删了提醒跟着失效。
 */
data class ScheduledTask(
    val id: String,
    val sessionId: String,
    val title: String,
    val prompt: String,
    val timing: ScheduleTiming,
    val createdAt: Long,
    val updatedAt: Long,
    /** 下一次触发的 UTC 时刻；null = 已结束（一次性已投递 / 无未来触发点）。 */
    val nextRunAt: Long,
    val lastDeliveredAt: Long? = null,
    val active: Boolean = true,
)

/** 提醒的稳定错误码（工具把它翻成 error 结果 + `meta.code`）。 */
class ScheduleException(val code: String, message: String) : Exception(message)
