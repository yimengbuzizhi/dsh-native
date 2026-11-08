package xin.dshaa.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import xin.dshaa.core.model.ScheduleException
import xin.dshaa.core.model.ScheduleTiming
import xin.dshaa.core.tools.schedule.ScheduleTime
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 提醒时间语义（快照 tools.json 的文案 + 本实现的设计）：
 * 本地时间不存在 → 跳过；重复 → 取较早瞬间；停机只补最近一次；间隔对齐创建/保存时刻。
 */
class ScheduleTimeTest {

    private val now = ZonedDateTime.of(2026, 11, 7, 10, 0, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun timing(json: String): ScheduleTiming = ScheduleTime.parse(args(json), now)

    private fun next(timing: ScheduleTiming, after: Long = now, anchor: Long = now): Long? =
        ScheduleTime.nextOccurrence(timing, after, anchor)

    // ---------------------------------------------------------------- 参数解析

    @Test
    fun `after_seconds fires once at created plus delay`() {
        val t = timing("""{"after_seconds":90}""")
        assertEquals(now + 90_000, next(t))
        // 已经过了触发点 → 没有下一次
        assertNull(next(t, after = now + 91_000))
    }

    @Test
    fun `exactly one timing parameter is required`() {
        val error = assertFailsWith<ScheduleException> {
            timing("""{"after_seconds":30,"every_seconds":60}""")
        }
        assertEquals("invalid_timing", error.code)
        assertEquals("invalid_timing", assertFailsWith<ScheduleException> { timing("{}") }.code)
    }

    @Test
    fun `every_seconds must be at least sixty`() {
        val error = assertFailsWith<ScheduleException> { timing("""{"every_seconds":30}""") }
        assertEquals("frequency_too_high", error.code)
        // 60 秒正好合法，并且按 anchor 对齐
        val t = timing("""{"every_seconds":60}""")
        val anchor = now - 90_000
        assertEquals(anchor + 120_000, next(t, anchor = anchor))
    }

    @Test
    fun `daily uses the zone local time`() {
        val t = timing("""{"daily":{"time":"09:00:00","time_zone":"Asia/Shanghai"}}""")
        // 现在是上海 18:00 → 下一次是明天 09:00（= 01:00Z）
        assertEquals("2026-11-08T01:00:00Z", ScheduleTime.isoUtc(next(t)!!))
        // 现在是上海 08:30 → 同一天 09:00
        val morning = ZonedDateTime.of(2026, 11, 7, 0, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("2026-11-07T01:00:00Z", ScheduleTime.isoUtc(next(t, after = morning)!!))
    }

    @Test
    fun `daily skips a nonexistent local time and fires once at the earlier instant of a repeat`() {
        val zone = ZoneId.of("America/New_York")
        // 春季跳变：2026-03-08 02:30 不存在 → 跳过，下一次 03-09 02:30 EDT（06:30Z）
        val spring = timing("""{"daily":{"time":"02:30:00","time_zone":"America/New_York"}}""")
        val beforeSpring = ZonedDateTime.of(2026, 3, 7, 15, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(
            ZonedDateTime.of(LocalDate.of(2026, 3, 9), LocalTime.of(2, 30), zone).toInstant().toEpochMilli(),
            next(spring, after = beforeSpring),
        )
        // 秋季重叠：2026-11-01 01:30 出现两次 → 只触发一次，取较早瞬间（EDT, 05:30Z）
        val fall = timing("""{"daily":{"time":"01:30:00","time_zone":"America/New_York"}}""")
        val beforeFall = ZonedDateTime.of(2026, 10, 31, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val first = next(fall, after = beforeFall)!!
        assertEquals("2026-11-01T05:30:00Z", ScheduleTime.isoUtc(first))
        // 之后不会再重复同一个本地时间
        assertEquals("2026-11-02T06:30:00Z", ScheduleTime.isoUtc(next(fall, after = first)!!))
    }

    @Test
    fun `weekly honours ISO weekdays`() {
        val t = timing("""{"weekly":{"time":"09:00:00","time_zone":"Asia/Shanghai","weekdays":[1]}}""")
        // 2026-11-07 是周六（上海 18:00）→ 下一次周一 09:00 = 11-09T01:00Z
        assertEquals("2026-11-09T01:00:00Z", ScheduleTime.isoUtc(next(t)!!))
    }

    @Test
    fun `weekdays must be iso one through seven without repetitions`() {
        assertEquals("invalid_rule", assertFailsWith<ScheduleException> {
            timing("""{"weekly":{"time":"09:00:00","time_zone":"UTC","weekdays":[0]}}""")
        }.code)
        assertEquals("invalid_rule", assertFailsWith<ScheduleException> {
            timing("""{"weekly":{"time":"09:00:00","time_zone":"UTC","weekdays":[1,1]}}""")
        }.code)
    }

    @Test
    fun `cron matches vixie fields and combines day fields with either`() {
        val t = timing("""{"cron":{"expression":"*/15 9-17 * * 1-5","time_zone":"Asia/Shanghai"}}""")
        // 上海周五 18:00 → 下一次周一 09:00
        val friday = ZonedDateTime.of(2026, 11, 6, 10, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("2026-11-09T01:00:00Z", ScheduleTime.isoUtc(next(t, after = friday)!!))
        // 同一天 09:07 → 09:15
        val nineOhSeven = ZonedDateTime.of(2026, 11, 6, 1, 7, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("2026-11-06T01:15:00Z", ScheduleTime.isoUtc(next(t, after = nineOhSeven)!!))
        // 日与星期都受限 → 任一匹配：13 号或周五
        val either = timing("""{"cron":{"expression":"0 0 13 * 5","time_zone":"UTC"}}""")
        val start = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        // 2026-11-06 是周五，早于 13 号
        assertEquals("2026-11-06T00:00:00Z", ScheduleTime.isoUtc(next(either, after = start)!!))
    }

    @Test
    fun `cron rejects malformed expressions`() {
        assertEquals("invalid_rule", assertFailsWith<ScheduleException> {
            timing("""{"cron":{"expression":"* * * *","time_zone":"UTC"}}""")
        }.code)
        assertEquals("invalid_rule", assertFailsWith<ScheduleException> {
            timing("""{"cron":{"expression":"61 * * * *","time_zone":"UTC"}}""")
        }.code)
        assertEquals("invalid_rule", assertFailsWith<ScheduleException> {
            timing("""{"cron":{"expression":"*/0 * * * *","time_zone":"UTC"}}""")
        }.code)
    }

    @Test
    fun `at accepts rfc3339 or local date time zone and must be in the future`() {
        val iso = timing("""{"at":"2026-11-08T01:00:00+08:00"}""")
        assertEquals("2026-11-07T17:00:00Z", ScheduleTime.isoUtc(next(iso)!!))
        val local = timing("""{"at":{"date":"2026-11-08","time":"09:00:00","time_zone":"Asia/Shanghai"}}""")
        assertEquals("2026-11-08T01:00:00Z", ScheduleTime.isoUtc(next(local)!!))
        assertEquals("not_future", assertFailsWith<ScheduleException> {
            timing("""{"at":"2026-11-01T00:00:00Z"}""")
        }.code)
    }

    @Test
    fun `bad time zone and title are rejected with stable codes`() {
        assertEquals("invalid_time_zone", assertFailsWith<ScheduleException> {
            timing("""{"daily":{"time":"09:00:00","time_zone":"Mars/Olympus"}}""")
        }.code)
        assertEquals("invalid_title", assertFailsWith<ScheduleException> {
            ScheduleTime.validateTitle("   ")
        }.code)
        assertEquals("invalid_title", assertFailsWith<ScheduleException> {
            ScheduleTime.validateTitle("x".repeat(ScheduleTime.MAX_TITLE_LENGTH + 1))
        }.code)
        assertEquals("invalid_prompt", assertFailsWith<ScheduleException> {
            ScheduleTime.validatePrompt("  ")
        }.code)
    }

    @Test
    fun `display helpers are stable`() {
        assertEquals("2026-11-07T02:00:00Z", ScheduleTime.isoUtc(now))
        assertEquals("2026-11-07 10:00:00", ScheduleTime.localText(now, "Asia/Shanghai"))
        assertTrue(ScheduleTime.isoUtc(now).endsWith("Z"))
    }
}
