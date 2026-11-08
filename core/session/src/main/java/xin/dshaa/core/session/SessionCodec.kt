package xin.dshaa.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.ContentBlock
import xin.dshaa.core.model.ImageOffloadTarget
import xin.dshaa.core.model.MessageRole
import xin.dshaa.core.model.PlanModeView
import xin.dshaa.core.model.SessionEvent
import xin.dshaa.core.model.SessionHeader
import xin.dshaa.core.model.SessionMessage
import xin.dshaa.core.model.markOffloadedImages

/**
 * v4 JSONL 编解码。
 *
 * 行格式：首行 session header；其后每行 `{type, seq, time, data, surfaceOp?}`。
 * 未知 type 一律保留（前向兼容，上游会持续加事件）。
 */
object SessionCodec {

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        // header 的 isSeeded/delegationDepth 必须显式写出（v4 读取方按必需字段校验）
        encodeDefaults = true
        prettyPrint = false
    }

    data class Decoded(val header: SessionHeader, val events: List<SessionEvent>)

    fun decode(compressed: ByteArray): Decoded {
        val text = ZstdFrames.decompressAll(compressed).toString(Charsets.UTF_8)
        return decodeText(text)
    }

    fun decodeText(text: String): Decoded {
        val lines = text.split('\n').filter { it.isNotBlank() }
        require(lines.isNotEmpty()) { "empty session file" }
        val header = json.decodeFromString(SessionHeader.serializer(), lines.first())
        val events = lines.drop(1).mapNotNull { parseEvent(it) }
        return Decoded(header, events)
    }

    fun parseEvent(line: String): SessionEvent? = runCatching {
        val obj = json.parseToJsonElement(line).jsonObject
        SessionEvent(
            type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            seq = obj["seq"]?.jsonPrimitive?.longOrNull,
            time = obj["time"]?.jsonPrimitive?.longOrNull,
            data = obj["data"],
            surfaceOp = obj["surfaceOp"],
        )
    }.getOrNull()

    fun encodeHeader(header: SessionHeader): String =
        json.encodeToString(SessionHeader.serializer(), header)

    fun encodeEvent(
        type: String,
        seq: Long,
        time: Long,
        data: JsonObject,
        surfaceOp: JsonElement? = null,
        sourceEventSeqs: List<Long>? = null,
    ): String {
        val obj = buildJsonObject {
            put("type", type)
            put("seq", seq)
            put("time", time)
            put("data", data)
            if (surfaceOp != null) put("surfaceOp", surfaceOp)
            if (!sourceEventSeqs.isNullOrEmpty()) {
                put("sourceEventSeqs", kotlinx.serialization.json.buildJsonArray {
                    sourceEventSeqs.forEach { add(JsonPrimitive(it)) }
                })
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    // ---------------- 投影 ----------------

    /** 事件 → 消息（只投影会话正文相关的四类事件）。 */
    fun messageOf(event: SessionEvent): SessionMessage? {
        val data = event.data?.jsonObjectOrNull() ?: return null
        return when (event.type) {
            "user/message", "developer/message", "system/message" -> {
                val m = data["message"]?.jsonObjectOrNull()
                val msg = m ?: data
                parseMessage(msg, event.seq, event.time)
            }
            "assistant/message", "tool/result" -> {
                val m = data["message"]?.jsonObjectOrNull() ?: return null
                parseMessage(m, event.seq, event.time)
            }
            else -> null
        }
    }

    /**
     * 会话面替换区间（M43，上游 `surfaceOp:{op:"replace", startSeq, endSeq}`）：
     * 返回 `start..end` 的闭区间，普通消息 / 旧文件返回 null。
     */
    fun replaceRangeOf(event: SessionEvent): LongRange? {
        val op = event.surfaceOp?.jsonObjectOrNull() ?: return null
        if (op["op"]?.jsonPrimitive?.contentOrNull != "replace") return null
        val start = op["startSeq"]?.jsonPrimitive?.longOrNull ?: return null
        val end = op["endSeq"]?.jsonPrimitive?.longOrNull ?: return null
        if (start > end) return null
        return start..end
    }

    fun titleOf(event: SessionEvent): String? {
        if (event.type != "session/title") return null
        return event.data?.jsonObjectOrNull()?.get("title")?.jsonPrimitive?.contentOrNull
    }

    /**
     * `image/offload` 的选中目标（M47，上游 imageOffloadProjection）：
     * `{targets:[{seq, imageIndexes:[…]}]}`。坏事件/坏下标一律跳过（会话文件来自旧版/桌面，
     * 不能因为一条投影事件读不开整段历史）。
     */
    fun imageOffloadTargetsOf(event: SessionEvent): List<ImageOffloadTarget> {
        if (event.type != "image/offload") return emptyList()
        val data = event.data?.jsonObjectOrNull() ?: return emptyList()
        val targets = data["targets"] as? JsonArray ?: return emptyList()
        return targets.mapNotNull { element ->
            val target = element.jsonObjectOrNull() ?: return@mapNotNull null
            val seq = target["seq"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val indexes = (target["imageIndexes"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.intOrNull }
                ?: return@mapNotNull null
            if (indexes.isEmpty()) null else ImageOffloadTarget(seq, indexes)
        }
    }

    /**
     * `turn/end` 的终止原因（completed / aborted / error / max-tokens / refusal）。
     * 子代理面板用它区分「已完成」和「已停止」（进程被杀时没有 turn/end → null）。
     */
    fun stopReasonOf(event: SessionEvent): String? {
        if (event.type != "turn/end") return null
        return event.data?.jsonObjectOrNull()
            ?.get("reason")?.jsonObjectOrNull()
            ?.get("kind")?.jsonPrimitive?.contentOrNull
    }

    /**
     * 计划模式折叠（上游 `plan` projection）：最后一条 `plan/mode` 决定 active；
     * `entered` = 曾经进过（`exit_plan_mode` 的准入沿用到会话结束）。
     */
    fun planModeOf(events: List<SessionEvent>): PlanModeView {
        var active = false
        var entered = false
        events.forEach { event ->
            if (event.type != "plan/mode") return@forEach
            val value = event.data?.jsonObjectOrNull()?.get("active")?.jsonPrimitive?.booleanOrNull ?: return@forEach
            active = value
            if (value) entered = true
        }
        return PlanModeView(active = active, entered = entered)
    }

    private fun parseMessage(obj: JsonObject, seq: Long?, time: Long?): SessionMessage {
        val role = when (obj["role"]?.jsonPrimitive?.contentOrNull) {
            "system" -> MessageRole.SYSTEM
            "user" -> MessageRole.USER
            "assistant" -> MessageRole.ASSISTANT
            "tool" -> MessageRole.TOOL
            "developer" -> MessageRole.DEVELOPER
            else -> MessageRole.UNKNOWN
        }
        val content = (obj["content"] as? JsonArray)?.mapNotNull { parseBlock(it) } ?: emptyList()
        return SessionMessage(
            id = obj["id"]?.jsonPrimitive?.contentOrNull,
            role = role,
            content = content,
            toolCallId = obj["toolCallId"]?.jsonPrimitive?.contentOrNull,
            isError = obj["isError"]?.jsonPrimitive?.booleanOrNull ?: false,
            time = time,
            seq = seq,
            source = obj["source"]?.jsonObjectOrNull(),
        )
    }

    private fun parseBlock(el: kotlinx.serialization.json.JsonElement): ContentBlock? {
        val obj = el.jsonObjectOrNull() ?: return null
        return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "text" -> ContentBlock(BlockType.TEXT, text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
            "reasoning" -> ContentBlock(
                type = BlockType.REASONING,
                text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                signature = obj["signature"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
            "tool-call" -> ContentBlock(
                type = BlockType.TOOL_CALL,
                toolCallId = obj["id"]?.jsonPrimitive?.contentOrNull,
                name = obj["name"]?.jsonPrimitive?.contentOrNull,
                arguments = obj["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
                raw = obj,
            )
            "image" -> ContentBlock(BlockType.IMAGE, raw = obj)
            "tool-addition" -> ContentBlock(
                type = BlockType.TOOL_ADDITION,
                name = obj["toolName"]?.jsonPrimitive?.contentOrNull,
                raw = obj,
            )
            else -> ContentBlock(BlockType.UNKNOWN, raw = obj)
        }
    }

    /**
     * 供上层（UI/LLM）使用的稳定投影：按 seq 排序、跳过空消息、把 tool result 标出来。
     */
    fun messagesOf(events: List<SessionEvent>): List<SessionMessage> =
        events.sortedBy { it.seq ?: Long.MAX_VALUE }
            .mapNotNull { messageOf(it) }
            .filter { it.content.isNotEmpty() }

    /**
     * 会话面投影（M43/M46/M47，上游 `session.surface`）：在 [messagesOf] 的基础上应用
     * `surfaceOp:{op:"replace",startSeq,endSeq}` —— 替换消息**原地**顶掉区间里的消息，
     * 占区间第一条的位置（不是追加到末尾）。压缩 checkpoint 顶掉一段历史、工具结果修剪顶掉
     * 一条结果都走这条；回放顺序必须与模型当时看到的一致。
     *
     * M47：`image/offload` 在事件顺序里给当前会话面的 image 块标卸载标记；之后如果这条消息被
     * 修剪替换，替换内容是从投影后的消息复制的，标记会跟着新 `tool/result` 一起落盘。
     */
    fun surfaceOf(events: List<SessionEvent>): List<SessionMessage> {
        val surface = ArrayList<SessionMessage>()
        events.sortedBy { it.seq ?: Long.MAX_VALUE }.forEach { event ->
            if (event.type == "image/offload") {
                imageOffloadTargetsOf(event).forEach { target ->
                    val index = surface.indexOfFirst { it.seq == target.seq }
                    if (index >= 0) {
                        surface[index] = surface[index].markOffloadedImages(target.imageIndexes.toSet())
                    }
                }
                return@forEach
            }
            val message = messageOf(event) ?: return@forEach
            if (message.content.isEmpty()) return@forEach
            val range = replaceRangeOf(event)
            if (range == null) {
                surface += message
                return@forEach
            }
            val at = surface.indexOfFirst { it.seq != null && it.seq in range }
            surface.removeAll { it.seq != null && it.seq in range }
            if (at >= 0) surface.add(at.coerceAtMost(surface.size), message) else surface.add(message)
        }
        return surface
    }

    fun isProjectionEvent(type: String): Boolean = type in setOf(
        "user/message", "assistant/message", "tool/result", "system/message", "developer/message",
    )
}

internal fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
    runCatching { jsonObject }.getOrNull()

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull
