package xin.dshaa.core.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xin.dshaa.core.model.SessionEvent
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 时间上下文（M42）的逐字断言：文本格式、projection 折叠、节流、source 形状。
 *
 * 期望值来自桌面真会话（`C:\Users\user\.dsh\sessions` 里 243 条 time-context 事件）
 * 与上游 `dsh-time-context/lib/index.js`：
 * - `2026-11-08T04:09:37+08:00[Asia/Shanghai]`（seq 19 的 turn 1 step 1）；
 * - `Elapsed since the preceding step context: 10m 3s`（同一会话 step 134）；
 * - `Elapsed since the preceding model-visible message: unavailable`（首轮）。
 */
class TimeContextTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-11-08T04:09:37.000+08:00 —— 桌面会话 seq 19 的采样时刻。 */
    private val sampleNow = 1_794_082_177_000L

    // ---------------- 时间戳 / 时长 ----------------

    @Test
    fun timestampMatchesDesktopFormat() {
        assertEquals("2026-11-08T04:09:37+08:00[Asia/Shanghai]", TimeContext.formatTimestamp(sampleNow, zone))
        // UTC 写 +00:00 而不是 Z（上游 timeZoneName.replace(/^GMT$/, "GMT+00:00").slice(3)）
        assertEquals("2026-11-07T20:09:37+00:00[UTC]", TimeContext.formatTimestamp(sampleNow, ZoneId.of("UTC")))
        // 西五区：负号 + 两位小时
        assertEquals(
            "2026-11-07T15:09:37-05:00[America/New_York]",
            TimeContext.formatTimestamp(sampleNow, ZoneId.of("America/New_York")),
        )
        // 跨天、跨月
        assertEquals(
            "2026-12-01T00:00:00+08:00[Asia/Shanghai]",
            TimeContext.formatTimestamp(1_796_054_400_000L, zone),
        )
    }

    @Test
    fun durationMatchesDesktopFormat() {
        assertEquals("0s", TimeContext.formatDuration(0))
        assertEquals("0s", TimeContext.formatDuration(999))
        assertEquals("18s", TimeContext.formatDuration(18_000))
        assertEquals("10m 3s", TimeContext.formatDuration(603_000))
        assertEquals("1h 6m 0s", TimeContext.formatDuration(3_960_000))
        assertEquals("1d 1h 1m 1s", TimeContext.formatDuration(90_061_000))
        // 负数按 0 处理（上游 Math.max(0, elapsedMs)）
        assertEquals("0s", TimeContext.formatDuration(-5_000))
    }

    // ---------------- projection ----------------

    @Test
    fun projectionTracksLastMessageAndInjection() {
        val events = listOf(
            turnStart(1, 1_000),
            userMessage("u1", 2_000),
            timeContextMessage(3_000),
            assistantMessage(4_000),
            toolResult(5_000),
        )
        val state = TimeContext.project(events)
        assertEquals(5_000, state.lastMessageTime)
        assertEquals(3_000, state.lastInjectionTime)
        assertEquals(3_000, state.lastTurnInjectionTime)
    }

    @Test
    fun projectionClearsTurnInjectionAtTurnBoundary() {
        val events = listOf(
            turnStart(1, 1_000),
            timeContextMessage(2_000),
            turnEnd(1, 3_000),
            turnStart(2, 4_000),
        )
        val state = TimeContext.project(events)
        assertEquals(2_000, state.lastInjectionTime, "跨轮节流看 lastInjectionTime，不能清")
        assertNull(state.lastTurnInjectionTime, "turn/start 清空本轮注入时间")
        // 普通消息不算注入
        val noInjection = TimeContext.project(listOf(turnStart(1, 1_000), userMessage("u", 2_000)))
        assertNull(noInjection.lastInjectionTime)
    }

    // ---------------- decide / 文本 ----------------

    @Test
    fun firstStepHasNoBaselineAndDeviceZone() {
        val injection = assertNotNull(
            TimeContext.decide(
                events = listOf(turnStart(1, sampleNow)),
                turn = 1,
                step = 1,
                now = sampleNow,
                config = TimeContext.Config(zone = zone),
            ),
        )
        assertEquals(
            "Time sampled while preparing turn 1, step 1: 2026-11-08T04:09:37+08:00[Asia/Shanghai]\n" +
                "Browser time zone for this request: Asia/Shanghai. Interpret otherwise-unqualified dates and times in this zone.\n" +
                "Elapsed since the preceding model-visible message: unavailable.",
            injection.text,
        )
    }

    @Test
    fun firstInjectionOfTurnAfterTurnOneSaysStepContextUnavailable() {
        // 本轮（turn 2）在 step 12 才第一次注入：lastTurnInjectionTime 为 null → step context: unavailable
        val events = listOf(
            turnStart(1, sampleNow - 60_000),
            userMessage("u1", sampleNow - 50_000),
            timeContextMessage(sampleNow - 40_000),
            assistantMessage(sampleNow - 30_000),
            turnEnd(1, sampleNow - 29_000),
            turnStart(2, sampleNow - 20_000),
        )
        // 跨轮节流：上一次注入在 40s 前 < 10 分钟 → 不注入
        assertNull(TimeContext.decide(events, 2, 12, sampleNow, TimeContext.Config(zone = zone)))

        // 关掉节流后能看到 step context 的 unavailable
        val injection = assertNotNull(
            TimeContext.decide(events, 2, 12, sampleNow, TimeContext.Config(refreshIntervalMs = 0, zone = zone)),
        )
        assertEquals(true, injection.text.endsWith("Elapsed since the preceding step context: unavailable."))
    }

    @Test
    fun throttleUsesLastInjectionAcrossTurns() {
        val events = listOf(
            turnStart(1, sampleNow),
            userMessage("u1", sampleNow + 1_000),
            timeContextMessage(sampleNow + 2_000),
        )
        val config = TimeContext.Config(refreshIntervalMs = 600_000, zone = zone)
        assertNull(
            TimeContext.decide(events, 1, 2, now = sampleNow + 2_000 + 599_999, config = config),
            "差 1 毫秒到 10 分钟 → 不注入",
        )
        assertNotNull(
            TimeContext.decide(events, 1, 3, now = sampleNow + 2_000 + 600_000, config = config),
            "正好 10 分钟 → 注入",
        )
    }

    @Test
    fun secondInjectionInTurnCountsFromPreviousStepContext() {
        // 与桌面 step 134 同形：step 1 注入于 t0，10m 3s 后 step 134 再注入
        val t0 = sampleNow
        val t1 = t0 + 603_000
        val events = listOf(
            turnStart(1, t0 - 1_000),
            userMessage("u1", t0 - 500),
            timeContextMessage(t0),
            toolResult(t0 + 500_000),
        )
        val injection = assertNotNull(
            TimeContext.decide(events, 1, 134, now = t1, config = TimeContext.Config(zone = zone)),
        )
        assertEquals(true, injection.text.endsWith("Elapsed since the preceding step context: 10m 3s."))
        assertEquals(
            true,
            injection.text.startsWith(
                "Time sampled while preparing turn 1, step 134: 2026-11-08T04:19:40+08:00[Asia/Shanghai]\n",
            ),
        )
    }

    @Test
    fun firstStepOfLaterTurnCountsFromPreviousTurnLastMessage() {
        // 桌面 turn 4 step 1 = "6m 1s"（最后一条模型消息 → 新一轮第一条消息）
        val lastAssistant = sampleNow - 361_000
        val events = listOf(
            turnStart(1, sampleNow - 900_000),
            userMessage("u1", sampleNow - 890_000),
            assistantMessage(lastAssistant),
            turnEnd(1, sampleNow - 360_000),
            turnStart(2, sampleNow - 1_000),
            userMessage("u2", sampleNow - 500),
        )
        val injection = assertNotNull(
            TimeContext.decide(events, 2, 1, now = sampleNow, config = TimeContext.Config(zone = zone)),
        )
        assertEquals(true, injection.text.endsWith("Elapsed since the preceding model-visible message: 6m 1s."))
    }

    @Test
    fun stepOneIgnoresCurrentTurnUserMessage() {
        // 本轮用户消息已经落库（native 引擎先写 user/message 再注入），但基线仍是上一轮
        val events = listOf(
            turnStart(1, sampleNow - 100_000),
            assistantMessage(sampleNow - 90_000),
            turnEnd(1, sampleNow - 80_000),
            turnStart(2, sampleNow - 10),
            userMessage("u2", sampleNow - 5),
        )
        val text = assertNotNull(
            TimeContext.decide(events, 2, 1, now = sampleNow, config = TimeContext.Config(zone = zone)),
        ).text
        assertEquals(true, text.endsWith("Elapsed since the preceding model-visible message: 1m 30s."))
    }

    // ---------------- source ----------------

    @Test
    fun sourceMatchesUpstreamSnapshotShape() {
        val source = TimeContext.source("hello")
        assertEquals("time-context", source["kind"]?.jsonPrimitive?.content)
        assertEquals("snapshot", source["form"]?.jsonPrimitive?.content)
        assertNull(source["summary"], "snapshot 没有 summary")
        val sections = source["sections"]?.jsonArray ?: error("sections missing")
        assertEquals(1, sections.size)
        assertEquals("time-context", sections[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("hello", sections[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun projectReadsSourceKindFromStoredJson() {
        // 与桌面落库的 source 形状完全一致（seq 19 的 JSON）
        val source: JsonObject = buildJsonObject {
            put("kind", "time-context")
            put("form", "snapshot")
            put("sections", buildJsonArray {
                add(buildJsonObject { put("name", "time-context"); put("text", "x") })
            })
        }
        val state = TimeContext.project(
            listOf(
                SessionEvent("turn/start", 1, 1_000, buildJsonObject { put("turn", 1) }),
                SessionEvent(
                    "user/message",
                    2,
                    2_000,
                    buildJsonObject {
                        put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "x") }) })
                        put("source", source)
                    },
                ),
            ),
        )
        assertEquals(2_000, state.lastInjectionTime)
    }

    @Test
    fun nonCanonicalZoneFallsBackToUnavailableBrowserLine() {
        // 只有偏移、没有 IANA 名（桌面上 clientTimeZone 非法/缺失时的回退文案）
        val text = TimeContext.browserZoneText(ZoneId.of("+08:00"))
        assertEquals(
            "Browser time zone for this request: unavailable. Ask the user to clarify otherwise-unqualified dates and times.",
            text,
        )
    }

    // ---------------- 事件构造 ----------------

    private fun turnStart(turn: Int, time: Long) =
        SessionEvent("turn/start", null, time, buildJsonObject { put("turn", turn) })

    private fun turnEnd(turn: Int, time: Long) =
        SessionEvent("turn/end", null, time, buildJsonObject { put("turn", turn) })

    private fun userMessage(id: String, time: Long) = SessionEvent(
        "user/message",
        null,
        time,
        buildJsonObject {
            put("id", id)
            put("role", "user")
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", id) }) })
            put("source", buildJsonObject { put("kind", "user") })
        },
    )

    private fun timeContextMessage(time: Long) = SessionEvent(
        "user/message",
        null,
        time,
        buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "time") }) })
            put("source", TimeContext.source("time"))
        },
    )

    private fun assistantMessage(time: Long) = SessionEvent(
        "assistant/message",
        null,
        time,
        buildJsonObject {
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "a") }) })
        },
    )

    private fun toolResult(time: Long) = SessionEvent(
        "tool/result",
        null,
        time,
        buildJsonObject {
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "r") }) })
        },
    )
}
