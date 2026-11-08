package xin.dshaa.core.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import xin.dshaa.core.model.SessionEvent
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * 时间上下文（M42）：**逐字移植**上游 `@deepseek-ai/dsh-time-context`
 * 0.2.1-rc.2 `lib/index.js`（0.2.x 起是 `agent/pre-step` 插件，不是 system 段，docs/port/02 §2.6）。
 *
 * 上游行为（下面每一条都对着 `lib/index.js` 抄）：
 * - 每个 step 起点（pre-step）采样一次 `Date.now()`，满足节流就**追加**一条
 *   `source.kind:"time-context"`、`form:"snapshot"` 的 user 消息（`sections[0].name = "time-context"`）；
 * - 节流只看 `lastInjectionTime`（跨轮全局）：`refreshIntervalMs` 默认 600000（10 分钟），0 = 不节流；
 * - 文本三段（`renderText`，第 144-149 行）：
 *   `Time sampled while preparing turn {turn}, step {step}: {ISO 带偏移}[{IANA}]`
 *   `Browser time zone for this request: {tz}. Interpret ...`
 *   `Elapsed since the preceding {model-visible message|step context}: {dur}`
 * - `Elapsed` 的基线：step 1 = `lastMessageTime`（上一条模型可见消息），其余 = `lastTurnInjectionTime`
 *   （本轮上一次注入）；为 null 时写 `unavailable`（首轮、以及本轮第一次注入都会命中）；
 * - 状态三层（projection `timeContext` v2）：`lastMessageTime` / `lastInjectionTime` /
 *   `lastTurnInjectionTime`；`turn/start`、`turn/end` 把 `lastTurnInjectionTime` 清空；
 * - 时间戳格式 = `Intl.DateTimeFormat("en-US", { … hourCycle:"h23", timeZoneName:"longOffset" })`
 *   再 `timeZoneName.replace(/^GMT$/, "GMT+00:00").slice(3)`：`2026-11-08T04:09:37+08:00[Asia/Shanghai]`；
 * - 时长格式（`formatDuration`）：天/时/分（有才写）+ **秒（总是写）**，空格分隔：`10m 3s`、`1h 6m 0s`、`0s`。
 *
 * 与桌面端的已知差异（docs/66 §八）：桌面从用户消息的 `clientTimeZone` 推 browser 时区
 * （可能 mixed / unavailable）；Android 是单一客户端，直接用设备时区，永远 resolved。
 *
 * 这个对象不碰 IO、不碰 Android，纯函数，JVM 单测直接跑（`TimeContextTest`）。
 */
object TimeContext {

    /** `source.kind`（上游 `const name = "time-context"`）。 */
    const val KIND = "time-context"

    /** 上游 `refreshIntervalMs ?? 6e5`。 */
    const val DEFAULT_REFRESH_INTERVAL_MS = 600_000L

    /** 上游 `timeContextStateSchema`（stateVersion 2）在内存里的等价物。 */
    data class State(
        /** 上一条模型可见事件（user/assistant/tool 消息）的时间。 */
        val lastMessageTime: Long? = null,
        /** 本插件最近一次落库注入的时间（跨轮）。 */
        val lastInjectionTime: Long? = null,
        /** 本轮最近一次注入的时间（turn/start 与 turn/end 清空）。 */
        val lastTurnInjectionTime: Long? = null,
    )

    /** 注入配置：节流间隔 + 展示时区（上游 `Config.timeZone` 的进程回退值）。 */
    data class Config(
        val refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS,
        val zone: ZoneId = ZoneId.systemDefault(),
    )

    /** 一次注入：user 消息正文 + 会话里的 `source`。 */
    data class Injection(val text: String, val source: JsonObject)

    /**
     * 折叠会话事件得到 projection 状态（上游 `sessionProjections.register(apply)`）：
     * 只看 `turn/start`、`turn/end`、`user/message`、`assistant/message`、`tool/result`。
     */
    fun project(events: List<SessionEvent>): State {
        var lastMessageTime: Long? = null
        var lastInjectionTime: Long? = null
        var lastTurnInjectionTime: Long? = null
        for (event in events) {
            val time = event.time
            when (event.type) {
                "turn/start", "turn/end" -> {
                    if (lastTurnInjectionTime != null) lastTurnInjectionTime = null
                }
                "user/message" -> {
                    if (time != null) lastMessageTime = time
                    if (time != null && sourceKind(event) == KIND) {
                        lastInjectionTime = time
                        lastTurnInjectionTime = time
                    }
                }
                "assistant/message", "tool/result" -> {
                    if (time != null) lastMessageTime = time
                }
            }
        }
        return State(lastMessageTime, lastInjectionTime, lastTurnInjectionTime)
    }

    /**
     * 一个 step 起点要不要注入。返回 null = 节流命中（本 step 请求体一个字节都不变）。
     *
     * [events] 传**当前会话已落盘的事件**（本 step 已注入过的消息算在内）；
     * [turn] / [step] 是本 step 的编号（上游 `{turn, step}`）；[now] 采样时刻（毫秒）。
     */
    fun decide(
        events: List<SessionEvent>,
        turn: Int,
        step: Int,
        now: Long,
        config: Config,
    ): Injection? {
        require(config.refreshIntervalMs >= 0) { "refreshIntervalMs must be non-negative: ${config.refreshIntervalMs}" }
        val state = project(events)
        val interval = config.refreshIntervalMs
        if (interval > 0) {
            val last = state.lastInjectionTime
            if (last != null && now >= last && now - last < interval) return null
        }
        // step 1 的上一条模型可见消息：本 step 的用户消息在 pre-step 时还没进投影（上游
        // `requestMessages(agent, turn, proposed)` 就是补这个差），所以只看**本轮之前**的事件。
        val previous = if (step <= 1) previousTurnMessageTime(events) else state.lastTurnInjectionTime
        val text = render(now, turn, step, previous, config.zone)
        return Injection(text, source(text))
    }

    /** 文本逐字对齐上游 `renderText`（第 144-149 行）。 */
    fun render(now: Long, turn: Int, step: Int, previous: Long?, zone: ZoneId): String {
        val elapsed = if (previous == null) "unavailable" else formatDuration(now - previous)
        val baseline = if (step <= 1) "model-visible message" else "step context"
        return "Time sampled while preparing turn $turn, step $step: ${formatTimestamp(now, zone)}\n" +
            "${browserZoneText(zone)}\n" +
            "Elapsed since the preceding $baseline: $elapsed."
    }

    /**
     * `source` 逐字对齐上游 `createUserMessage({source: {kind, form:"snapshot", sections:[{name, text}]}})`。
     * 注意**没有 summary**：桌面把 snapshot 渲染成可展开的 context row，不靠 summary。
     */
    fun source(text: String): JsonObject = buildJsonObject {
        put("kind", KIND)
        put("form", "snapshot")
        put("sections", buildJsonArray {
            add(
                buildJsonObject {
                    put("name", KIND)
                    put("text", text)
                },
            )
        })
    }

    /** `Browser time zone for this request: …`（上游 `renderBrowserTimeZoneContext` 的 resolved 分支）。 */
    fun browserZoneText(zone: ZoneId): String {
        val id = zone.id
        return if (isCanonicalZoneId(id)) {
            "Browser time zone for this request: $id. Interpret otherwise-unqualified dates and times in this zone."
        } else {
            // Android 上几乎不会走到（systemDefault 是 IANA 名）；桌面在没有 clientTimeZone 时走这条。
            "Browser time zone for this request: unavailable. Ask the user to clarify otherwise-unqualified dates and times."
        }
    }

    /** 上游 `formatTimestamp`：本地时间 + 偏移 + `[IANA]`，UTC 写 `+00:00` 不写 `Z`。 */
    fun formatTimestamp(now: Long, zone: ZoneId): String {
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val offsetSeconds = local.offset.totalSeconds
        val sign = if (offsetSeconds < 0) "-" else "+"
        val magnitude = abs(offsetSeconds)
        return String.format(
            Locale.ROOT,
            "%04d-%02d-%02dT%02d:%02d:%02d%s%02d:%02d[%s]",
            local.year,
            local.monthValue,
            local.dayOfMonth,
            local.hour,
            local.minute,
            local.second,
            sign,
            magnitude / 3600,
            (magnitude % 3600) / 60,
            local.zone.id,
        )
    }

    /** 上游 `formatDuration`：天/时/分（有才写）+ 秒（总是写），空格分隔。 */
    fun formatDuration(elapsedMs: Long): String {
        var seconds = maxOf(0L, elapsedMs) / 1000
        val days = seconds / 86_400
        seconds %= 86_400
        val hours = seconds / 3_600
        seconds %= 3_600
        val minutes = seconds / 60
        seconds %= 60
        val parts = ArrayList<String>(4)
        if (days > 0) parts += "${days}d"
        if (hours > 0) parts += "${hours}h"
        if (minutes > 0) parts += "${minutes}m"
        parts += "${seconds}s"
        return parts.joinToString(" ")
    }

    /** 本轮之前的最后一条模型可见消息时间（等价于「pre-step 时投影里只有上一轮」）。 */
    private fun previousTurnMessageTime(events: List<SessionEvent>): Long? {
        val index = events.indexOfLast { it.type == "turn/start" }
        val prefix = if (index >= 0) events.subList(0, index) else events
        return project(prefix).lastMessageTime
    }

    /** 会话事件里 `data.source.kind`（坏数据当没有 source）。 */
    private fun sourceKind(event: SessionEvent): String? {
        val data = event.data as? JsonObject ?: return null
        val source = data["source"] as? JsonObject ?: return null
        return (source["kind"] as? JsonPrimitive)?.contentOrNull
    }

    /** 上游 `IANA_TIME_ZONE` 正则 + `UTC` 特例（`browserTimeZone` 的 canonical 判定）。 */
    private fun isCanonicalZoneId(id: String): Boolean =
        id == "UTC" || IANA_TIME_ZONE.matches(id)

    private val IANA_TIME_ZONE = Regex("""^[A-Za-z][A-Za-z0-9_+.-]*(?:/[A-Za-z0-9_+.-]+)+$""")
}
