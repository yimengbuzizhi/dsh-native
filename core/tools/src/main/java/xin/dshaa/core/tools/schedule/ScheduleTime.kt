package xin.dshaa.core.tools.schedule

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import xin.dshaa.core.model.ScheduleException
import xin.dshaa.core.model.ScheduleTiming
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.TreeSet

/**
 * 提醒时间：解析 `schedule_*` 参数 + 算下一次触发时刻（纯 JVM，可单测）。
 *
 * 语义逐条来自桌面快照 tools.json 的文案：
 * - 本地时间在时区里不存在（夏令时跳变）→ 跳过该次；重复的本地时间只触发一次，取较早的那个瞬间。
 * - 停机后只投递最近一次错过的触发（由调用方「跳到下一次未来触发」实现）。
 * - 固定间隔 ≥ 60 秒，按创建/保存时刻对齐。
 *
 * 错误码是本实现的设计（快照没有 output schema 可对照）：
 * `invalid_timing` / `invalid_title` / `invalid_prompt` / `invalid_rule` /
 * `invalid_time_zone` / `not_future` / `frequency_too_high` / `schedule_not_found`。
 */
object ScheduleTime {

    private val TIME_KEYS = listOf("after_seconds", "every_seconds", "at", "daily", "weekly", "cron")

    /** 标题上限（快照 description：「at most 120 characters」）。 */
    const val MAX_TITLE_LENGTH = 120

    /** 固定间隔下限（快照 description：「at least 60」）。 */
    const val MIN_EVERY_SECONDS = 60L

    private const val CRON_HORIZON_DAYS = 366 * 5

    // ---------------------------------------------------------------- 参数解析

    /** 参数里出现的 timing 字段（恰好一个才是合法创建/更新）。 */
    fun timingKeysPresent(args: JsonObject): List<String> = TIME_KEYS.filter { args[it] != null }

    /** 从工具参数解析触发方式；[now] 用于相对/绝对时间的合法性检查。 */
    fun parse(args: JsonObject, now: Long): ScheduleTiming {
        val present = timingKeysPresent(args)
        if (present.size != 1) {
            throw ScheduleException(
                "invalid_timing",
                "supply exactly one timing parameter: after_seconds, at, every_seconds, daily, weekly, or cron",
            )
        }
        return when (val key = present.single()) {
            "after_seconds" -> {
                val seconds = requireWholeSeconds(args, key, minimum = 1)
                ScheduleTiming(kind = "after_seconds", afterSeconds = seconds)
            }
            "every_seconds" -> {
                val seconds = requireWholeSeconds(args, key, minimum = MIN_EVERY_SECONDS, tooHighCode = "frequency_too_high")
                ScheduleTiming(kind = "every_seconds", everySeconds = seconds)
            }
            "at" -> parseAt(args["at"]!!, now)
            "daily" -> {
                val obj = requireObject(args, "daily")
                val time = requireTime(obj, "time")
                val zone = requireZone(obj, "time_zone")
                ScheduleTiming(kind = "daily", time = time, timeZone = zone)
            }
            "weekly" -> {
                val obj = requireObject(args, "weekly")
                val time = requireTime(obj, "time")
                val zone = requireZone(obj, "time_zone")
                val weekdays = parseWeekdays(obj["weekdays"])
                ScheduleTiming(kind = "weekly", time = time, timeZone = zone, weekdays = weekdays)
            }
            "cron" -> {
                val obj = requireObject(args, "cron")
                val expression = obj["expression"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (expression.isEmpty()) throw ScheduleException("invalid_rule", "cron requires expression")
                parseCron(expression)
                val zone = requireZone(obj, "time_zone")
                ScheduleTiming(kind = "cron", expression = expression, timeZone = zone)
            }
            else -> error("unreachable timing key: $key")
        }
    }

    /** 标题：非空、≤120 字符（按 Unicode 码点数）。 */
    fun validateTitle(raw: String?): String {
        val title = raw?.trim().orEmpty()
        if (title.isEmpty()) throw ScheduleException("invalid_title", "title must be non-empty")
        if (title.codePointCount(0, title.length) > MAX_TITLE_LENGTH) {
            throw ScheduleException("invalid_title", "title must be at most $MAX_TITLE_LENGTH characters")
        }
        return title
    }

    /** 提醒正文：非空。 */
    fun validatePrompt(raw: String?): String {
        val prompt = raw?.trim().orEmpty()
        if (prompt.isEmpty()) throw ScheduleException("invalid_prompt", "prompt must be non-empty")
        return prompt
    }

    private fun requireWholeSeconds(
        args: JsonObject,
        key: String,
        minimum: Long,
        tooHighCode: String = "invalid_timing",
    ): Long {
        val primitive = args[key]?.jsonPrimitive ?: throw ScheduleException("invalid_timing", "$key must be a number")
        val raw = primitive.longOrNull ?: primitive.contentOrNull?.toDoubleOrNull()?.let { value ->
            if (value != Math.floor(value) || value.isNaN() || value.isInfinite()) {
                throw ScheduleException("invalid_timing", "$key must be whole seconds")
            }
            value.toLong()
        } ?: throw ScheduleException("invalid_timing", "$key must be whole seconds")
        if (raw < minimum) {
            val code = if (tooHighCode == "frequency_too_high") "frequency_too_high" else "invalid_timing"
            throw ScheduleException(code, "$key must be at least $minimum")
        }
        return raw
    }

    private fun parseAt(element: kotlinx.serialization.json.JsonElement, now: Long): ScheduleTiming {
        val millis = when (element) {
            is JsonPrimitive -> {
                val text = element.contentOrNull?.trim().orEmpty()
                if (text.isEmpty()) throw ScheduleException("invalid_timing", "at must be a date-time string")
                try {
                    OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
                } catch (e: DateTimeParseException) {
                    throw ScheduleException("invalid_rule", "at must be an RFC 3339 date-time with offset: $text")
                }
            }
            is JsonObject -> {
                val date = element["date"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val timeText = element["time"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val zoneText = element["time_zone"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (date.isEmpty() || timeText.isEmpty() || zoneText.isEmpty()) {
                    throw ScheduleException("invalid_rule", "at requires date, time, and time_zone")
                }
                val zone = zoneOf(zoneText)
                val localDate = try {
                    LocalDate.parse(date)
                } catch (e: DateTimeParseException) {
                    throw ScheduleException("invalid_rule", "at.date must be YYYY-MM-DD: $date")
                }
                val localTime = parseLocalTime(timeText)
                ZonedDateTime.of(localDate, localTime, zone).toInstant().toEpochMilli()
            }
            else -> throw ScheduleException("invalid_timing", "at must be a string or an object")
        }
        if (millis <= now) throw ScheduleException("not_future", "at must be in the future")
        return ScheduleTiming(kind = "at", atMillis = millis)
    }

    private fun requireObject(args: JsonObject, key: String): JsonObject =
        args[key] as? JsonObject
            ?: throw ScheduleException("invalid_timing", "$key must be an object")

    private fun requireTime(obj: JsonObject, key: String): String {
        val text = obj[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) throw ScheduleException("invalid_rule", "$key is required")
        parseLocalTime(text)
        return text
    }

    private fun parseLocalTime(text: String): LocalTime = try {
        LocalTime.parse(text, DateTimeFormatter.ISO_LOCAL_TIME)
    } catch (e: DateTimeParseException) {
        throw ScheduleException("invalid_rule", "time must be HH:mm:ss with optional fractional digits: $text")
    }

    private fun requireZone(obj: JsonObject, key: String): String {
        val text = obj[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) throw ScheduleException("invalid_rule", "$key is required")
        zoneOf(text)
        return text
    }

    /** `UTC` 或 IANA 时区；非法时区码 `invalid_time_zone`。 */
    fun zoneOf(text: String): ZoneId {
        if (text == "UTC" || text == "Etc/UTC") return ZoneOffset.UTC
        return try {
            ZoneId.of(text)
        } catch (e: DateTimeException) {
            throw ScheduleException("invalid_time_zone", "unknown time zone: $text")
        }
    }

    private fun parseWeekdays(element: kotlinx.serialization.json.JsonElement?): List<Int> {
        val array = element as? kotlinx.serialization.json.JsonArray
            ?: throw ScheduleException("invalid_rule", "weekly requires weekdays")
        val days = array.map { it.jsonPrimitive.intOrNull ?: throw ScheduleException("invalid_rule", "weekdays must be integers") }
        if (days.isEmpty()) throw ScheduleException("invalid_rule", "weekdays must be non-empty")
        if (days.size != days.toSet().size) throw ScheduleException("invalid_rule", "weekdays must not repeat")
        days.forEach { if (it < 1 || it > 7) throw ScheduleException("invalid_rule", "weekdays are ISO 1 (Monday) through 7 (Sunday)") }
        return days.sorted()
    }

    // ---------------------------------------------------------------- cron

    private class CronField(
        val values: TreeSet<Int>,
        val restricted: Boolean,
    )

    private class CronSpec(
        val minutes: List<Int>,
        val hours: List<Int>,
        val daysOfMonth: CronField,
        val months: CronField,
        val daysOfWeek: CronField,
    )

    private val MONTH_NAMES = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    private val DAY_NAMES = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")

    /** 解析五段 Vixie cron（星号、N、N-M、步进、逗号列表；月份/星期支持英文缩写）。 */
    fun parseCron(expression: String): Unit {
        val parts = expression.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size != 5) {
            throw ScheduleException("invalid_rule", "cron must have exactly five fields: minute hour day-of-month month day-of-week")
        }
        val spec = buildCron(parts)
        if (spec.minutes.isEmpty()) throw ScheduleException("invalid_rule", "cron minute field has no value")
    }

    private fun buildCron(parts: List<String>): CronSpec = CronSpec(
        minutes = parseField(parts[0], 0, 59, null).values.toList(),
        hours = parseField(parts[1], 0, 23, null).values.toList(),
        daysOfMonth = parseField(parts[2], 1, 31, null),
        months = parseField(parts[3], 1, 12, MONTH_NAMES),
        daysOfWeek = parseField(parts[4], 0, 7, DAY_NAMES).let { field ->
            // 7 与 0 都表示周日：归一化到 0
            val normalized = TreeSet<Int>()
            field.values.forEach { normalized += if (it == 7) 0 else it }
            CronField(normalized, field.restricted)
        },
    )

    private fun parseField(text: String, min: Int, max: Int, names: List<String>?): CronField {
        val values = TreeSet<Int>()
        var restricted = false
        text.split(',').forEach { partRaw ->
            val part = partRaw.trim()
            if (part.isEmpty()) throw ScheduleException("invalid_rule", "cron field has an empty item")
            if (part == "*") {
                (min..max).forEach { values += it }
                return@forEach
            }
            restricted = true
            val stepSplit = part.split('/')
            if (stepSplit.size > 2) throw ScheduleException("invalid_rule", "cron step must be N/S")
            val step = if (stepSplit.size == 2) {
                stepSplit[1].toIntOrNull()?.takeIf { it >= 1 }
                    ?: throw ScheduleException("invalid_rule", "cron step must be a positive integer")
            } else {
                1
            }
            val range = stepSplit[0]
            val bounds = when {
                range == "*" -> min to max
                range.contains('-') -> {
                    val (a, b) = range.split('-', limit = 2)
                    val from = fieldValue(a, names) ?: throw ScheduleException("invalid_rule", "bad cron value: $a")
                    val to = fieldValue(b, names) ?: throw ScheduleException("invalid_rule", "bad cron value: $b")
                    from to to
                }
                else -> {
                    val single = fieldValue(range, names) ?: throw ScheduleException("invalid_rule", "bad cron value: $range")
                    if (stepSplit.size == 2) single to max else single to single
                }
            }
            val (from, to) = bounds
            if (from < min || to > max || from > to) {
                throw ScheduleException("invalid_rule", "cron value out of range in: $part")
            }
            var value = from
            while (value <= to) {
                values += value
                value += step
            }
        }
        if (values.isEmpty()) throw ScheduleException("invalid_rule", "cron field has no value")
        return CronField(values, restricted)
    }

    private fun fieldValue(text: String, names: List<String>?): Int? {
        text.toIntOrNull()?.let { return it }
        val upper = text.uppercase()
        val index = names?.indexOf(upper) ?: -1
        if (index >= 0) return if (names === MONTH_NAMES) index + 1 else index
        return null
    }

    // ---------------------------------------------------------------- 下一次触发

    /**
     * 下一次触发时刻（严格晚于 [after]）；null = 没有未来触发点（一次性已过）。
     * [anchor] = 固定间隔的对齐基准（创建时刻；改过间隔则是保存时刻）。
     */
    fun nextOccurrence(timing: ScheduleTiming, after: Long, anchor: Long): Long? = when (timing.kind) {
        "after_seconds" -> {
            val at = anchor + (timing.afterSeconds ?: 0) * 1000
            at.takeIf { it > after }
        }
        "every_seconds" -> {
            val step = (timing.everySeconds ?: 60) * 1000
            if (step <= 0) null else {
                var next = anchor + ((after - anchor) / step + 1) * step
                if (next <= after) next += step
                next
            }
        }
        "at" -> timing.atMillis?.takeIf { it > after }
        "daily" -> nextLocal(timing, after, weekdays = null)
        "weekly" -> nextLocal(timing, after, weekdays = timing.weekdays)
        "cron" -> nextCron(timing, after)
        else -> null
    }

    private fun nextLocal(timing: ScheduleTiming, after: Long, weekdays: List<Int>?): Long? {
        val zone = zoneOf(timing.timeZone ?: "UTC")
        val time = parseLocalTime(timing.time ?: throw ScheduleException("invalid_rule", "time is required"))
        var date = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
        val days = weekdays?.toSet()
        for (i in 0..370) {
            if (days == null || days.contains(date.dayOfWeek.value)) {
                val zoned = resolveLocal(date, time, zone)
                if (zoned != null) {
                    val millis = zoned.toInstant().toEpochMilli()
                    if (millis > after) return millis
                }
            }
            date = date.plusDays(1)
        }
        return null
    }

    /**
     * 把本地日期+时间解析成时刻：跳变不存在的本地时间返回 null（跳过该次），
     * 重叠时取较早的瞬间（`ZonedDateTime.of` 的默认行为）。
     */
    private fun resolveLocal(date: LocalDate, time: LocalTime, zone: ZoneId): ZonedDateTime? {
        val zoned = try {
            ZonedDateTime.of(date, time, zone)
        } catch (e: DateTimeException) {
            return null
        }
        // 跳变缺口：java.time 会前移到合法时间，本地时刻不再等于请求值 → 该次不存在
        if (zoned.toLocalTime() != time) return null
        return zoned
    }

    private fun nextCron(timing: ScheduleTiming, after: Long): Long? {
        val zone = zoneOf(timing.timeZone ?: "UTC")
        val spec = buildCron((timing.expression ?: throw ScheduleException("invalid_rule", "cron requires expression"))
            .split(Regex("\\s+")).filter { it.isNotEmpty() })
        var date = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
        for (i in 0..CRON_HORIZON_DAYS) {
            if (cronDayMatches(spec, date)) {
                for (hour in spec.hours) {
                    for (minute in spec.minutes) {
                        val zoned = resolveLocal(date, LocalTime.of(hour, minute), zone) ?: continue
                        val millis = zoned.toInstant().toEpochMilli()
                        if (millis > after) return millis
                    }
                }
            }
            date = date.plusDays(1)
        }
        return null
    }

    /**
     * 日期是否匹配：日/月字段各自匹配；日与星期「都受限时任一匹配即可」（Vixie 语义），
     * 只有一个受限时按那个字段。
     */
    private fun cronDayMatches(spec: CronSpec, date: LocalDate): Boolean {
        if (date.monthValue !in spec.months.values) return false
        val domMatch = date.dayOfMonth in spec.daysOfMonth.values
        val dowMatch = (date.dayOfWeek.value % 7) in spec.daysOfWeek.values
        return when {
            spec.daysOfMonth.restricted && spec.daysOfWeek.restricted -> domMatch || dowMatch
            spec.daysOfMonth.restricted -> domMatch
            spec.daysOfWeek.restricted -> dowMatch
            else -> true
        }
    }

    // ---------------------------------------------------------------- 展示

    /** UTC ISO 秒级时间：`2026-11-07T18:30:00Z`。 */
    fun isoUtc(millis: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC))

    /** 按时区渲染本地时间：`2026-11-07 18:30:00`；没给时区就用设备时区（用户看到的「本地时间」）。 */
    fun localText(millis: Long, timeZone: String? = null): String {
        val zone = runCatching {
            timeZone?.let { zoneOf(it) } ?: ZoneId.systemDefault()
        }.getOrDefault(ZoneOffset.UTC)
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(Instant.ofEpochMilli(millis).atZone(zone))
    }
}
