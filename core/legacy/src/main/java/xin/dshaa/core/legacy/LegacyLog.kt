package xin.dshaa.core.legacy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import xin.dshaa.core.model.SessionEvent
import xin.dshaa.core.session.SessionCodec
import xin.dshaa.core.session.ZstdFrames

/**
 * 旧版会话（v0–v4）的读取层。
 *
 * 背景：`/sdcard/Download/DSH备份-*.zip` 里是旧版 App 的完整 dshhome，会话日志有四种物理形态：
 * - `session.jsonl.zstd`（v0，packed chunk 行 + seedLength）
 * - `session.v2.jsonl.zstd`
 * - `session.v3.jsonl.zstd`
 * - `session.v4.jsonl.zstd`（与当前一致）
 *
 * 本轮只做**只读导入**：把旧会话投影成消息再写成一份 v4 副本（见 [LegacyV4]）。
 * 完整 v0→v4 迁移链（seq 重编号 / surfaceOp 端点 / assistant/message.stream 内嵌）见 docs/port/01 §5.7，
 * 不在本轮范围内；具体取舍写在 docs/23。
 */
data class LegacyHeader(
    val version: Int,
    val id: String,
    val createdAt: Long,
    val cwd: String,
    val parentSession: String?,
    val isSeeded: Boolean,
    val delegationDepth: Int,
    val agentPreset: String?,
)

/** 一份旧版会话日志：header + 事件（事件只保留 public 字段，不丢 data）。 */
data class LegacyLog(
    val header: LegacyHeader,
    val events: List<SessionEvent>,
    val plainBytes: Int,
)

object LegacyLogReader {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /** zstd magic 0xFD2FB528（小端字节 28 B5 2F FD）。 */
    fun isZstd(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x28.toByte() &&
            bytes[1] == 0xB5.toByte() &&
            bytes[2] == 0x2F.toByte() &&
            bytes[3] == 0xFD.toByte()

    fun decode(bytes: ByteArray): LegacyLog {
        val text = if (isZstd(bytes)) {
            String(ZstdFrames.decompressAll(bytes), Charsets.UTF_8)
        } else {
            String(bytes, Charsets.UTF_8)
        }
        return decodeText(text)
    }

    fun decodeText(text: String): LegacyLog {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.isNotEmpty()) { "empty session log" }
        val head = runCatching { json.parseToJsonElement(lines.first()).jsonObject }.getOrNull()
            ?: throw IllegalArgumentException("session header is not a JSON object")
        val version = head["version"]?.jsonPrimitive?.intOrNull ?: 0
        val header = LegacyHeader(
            version = version,
            id = head["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            createdAt = head["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            cwd = head["cwd"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            parentSession = head["parentSession"]?.jsonPrimitive?.contentOrNull,
            // v0/v1：isSeeded = (seedLength !== undefined)；v2+ 直接是布尔
            isSeeded = head["isSeeded"]?.jsonPrimitive?.booleanOrNull == true ||
                (head["seedLength"] is JsonPrimitive),
            delegationDepth = head["delegationDepth"]?.jsonPrimitive?.intOrNull ?: 0,
            agentPreset = head["agentPreset"]?.jsonPrimitive?.contentOrNull,
        )
        require(header.id.isNotBlank()) { "session header has no id" }
        val events = lines.drop(1).mapNotNull { SessionCodec.parseEvent(it) }
        return LegacyLog(header, events, text.length)
    }
}
