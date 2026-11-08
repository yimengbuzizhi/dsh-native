package xin.dshaa.core.legacy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.ContentBlock
import xin.dshaa.core.model.MessageRole
import xin.dshaa.core.model.SessionEvent
import xin.dshaa.core.model.SessionMessage

data class LegacyModelInfo(val provider: String?, val model: String?)

/**
 * v0–v4 事件 → 消息投影。
 *
 * v0/v3 与 v4 的差别只在两处（docs/port/01 §5.6）：
 * 1. `tool/result.message.role` 是 `user`，正文包在 `tool-result` 块里；
 * 2. `source.kind` 还是旧命名（保留原样即可，UI 不依赖）。
 *
 * v0 的 packed chunk 行（text-chunks / reasoning-chunks / tool-call-chunks）**不参与投影**：
 * 它们是同一条 assistant/message 的增量流，最终正文已经在 assistant/message 里。
 */
object LegacyProjection {

    fun messages(log: LegacyLog): List<SessionMessage> =
        log.events
            .sortedBy { it.seq ?: Long.MAX_VALUE }
            .flatMap { projectEvent(it) }
            .filter { it.content.isNotEmpty() }

    /**
     * 真正给用户看的对话：去掉 system 头节点和**运行时上下文**注入消息。
     *
     * v3 里运行时上下文是一条 `role:user` 消息，`source.kind:"plugin"`、
     * `plugin:"@deepseek-ai/dsh-system-prompt"`、`form:"snapshot"`；v4 会归一成 `kind:"runtime-context"`。
     * 它不是用户打的字，导入时不应该变成聊天气泡（否则每个回合都会多一条巨型英文提示）。
     */
    fun conversation(log: LegacyLog): List<SessionMessage> =
        messages(log).filter {
            (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT || it.role == MessageRole.TOOL) &&
                !isRuntimeContext(it)
        }

    fun isRuntimeContext(message: SessionMessage): Boolean {
        val src = message.source
        if (src != null) {
            val kind = src["kind"]?.jsonPrimitive?.contentOrNull
            if (kind == "runtime-context") return true
            val plugin = src["plugin"]?.jsonPrimitive?.contentOrNull
            if (plugin == "@deepseek-ai/dsh-system-prompt" && message.role != MessageRole.SYSTEM) return true
        }
        return message.role == MessageRole.USER &&
            message.text.trimStart().startsWith("Current runtime context. This snapshot supersedes")
    }

    fun title(log: LegacyLog): String? =
        log.events.asReversed().firstNotNullOfOrNull { ev ->
            if (ev.type != "session/title") return@firstNotNullOfOrNull null
            ev.data?.asObject()?.get("title")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }

    fun modelInfo(log: LegacyLog): LegacyModelInfo {
        var provider: String? = null
        var model: String? = null
        for (ev in log.events) {
            val data = ev.data?.asObject() ?: continue
            when (ev.type) {
                "model/selection" -> {
                    provider = data["provider"]?.jsonPrimitive?.contentOrNull ?: provider
                    model = data["model"]?.jsonPrimitive?.contentOrNull ?: model
                }
                "request/header" -> {
                    val config = data["header"]?.asObject()?.get("config")?.asObject() ?: continue
                    provider = config["provider"]?.jsonPrimitive?.contentOrNull ?: provider
                    model = config["model"]?.jsonPrimitive?.contentOrNull ?: model
                }
            }
            if (provider != null && model != null) break
        }
        return LegacyModelInfo(provider, model)
    }

    fun projectEvent(ev: SessionEvent): List<SessionMessage> {
        val data = ev.data?.asObject() ?: return emptyList()
        return when (ev.type) {
            "user/message", "developer/message", "system/message", "steering/message" -> {
                val obj = data["message"]?.asObject() ?: data
                val role = when (ev.type) {
                    "system/message" -> MessageRole.SYSTEM
                    "developer/message" -> MessageRole.DEVELOPER
                    "steering/message" -> MessageRole.USER
                    else -> MessageRole.USER
                }
                listOfNotNull(parseMessage(obj, role, ev))
            }
            "assistant/message" -> {
                val obj = data["message"]?.asObject() ?: return emptyList()
                listOfNotNull(parseMessage(obj, MessageRole.ASSISTANT, ev))
            }
            "tool/result" -> {
                val obj = data["message"]?.asObject() ?: return emptyList()
                listOfNotNull(parseToolResult(obj, ev))
            }
            else -> emptyList()
        }
    }

    private fun parseMessage(obj: JsonObject, defaultRole: MessageRole, ev: SessionEvent): SessionMessage? {
        val role = roleOf(obj["role"]?.jsonPrimitive?.contentOrNull) ?: defaultRole
        val content = parseContent(obj["content"] as? JsonArray)
        if (content.isEmpty()) return null
        return SessionMessage(
            id = obj["id"]?.jsonPrimitive?.contentOrNull,
            role = role,
            content = content,
            toolCallId = obj["toolCallId"]?.jsonPrimitive?.contentOrNull,
            isError = obj["isError"]?.jsonPrimitive?.booleanOrNull == true,
            time = ev.time,
            seq = ev.seq,
            source = obj["source"]?.asObject(),
        )
    }

    /**
     * v3 的 tool/result：`content` 是 `tool-result` 包装块数组，真正内容在包装块里。
     * v4 的 tool/result：`role=tool`，content 直接是正文数组 —— 两种都接受。
     */
    private fun parseToolResult(obj: JsonObject, ev: SessionEvent): SessionMessage? {
        val blocks = (obj["content"] as? JsonArray)?.mapNotNull { it.asObject() }.orEmpty()
        val wrappers = blocks.filter { it["type"]?.jsonPrimitive?.contentOrNull == "tool-result" }
        val sourceCallId = obj["source"]?.asObject()?.get("callId")?.jsonPrimitive?.contentOrNull
        if (wrappers.isEmpty()) {
            val content = parseContent(obj["content"] as? JsonArray)
            if (content.isEmpty()) return null
            return SessionMessage(
                id = obj["id"]?.jsonPrimitive?.contentOrNull,
                role = MessageRole.TOOL,
                content = content,
                toolCallId = obj["toolCallId"]?.jsonPrimitive?.contentOrNull ?: sourceCallId,
                isError = obj["isError"]?.jsonPrimitive?.booleanOrNull == true,
                time = ev.time,
                seq = ev.seq,
                source = obj["source"]?.asObject(),
            )
        }
        val content = ArrayList<ContentBlock>()
        var isError = obj["isError"]?.jsonPrimitive?.booleanOrNull == true
        var callId = obj["toolCallId"]?.jsonPrimitive?.contentOrNull
        wrappers.forEach { wrapper ->
            callId = callId ?: wrapper["toolCallId"]?.jsonPrimitive?.contentOrNull
            isError = isError || wrapper["isError"]?.jsonPrimitive?.booleanOrNull == true
            content += parseContent(wrapper["content"] as? JsonArray)
        }
        // 包装块以外还有别的块（极少见）也保留，避免丢内容
        blocks.filter { it["type"]?.jsonPrimitive?.contentOrNull != "tool-result" }
            .forEach { other -> parseBlock(other)?.let { content += it } }
        if (content.isEmpty()) return null
        return SessionMessage(
            id = obj["id"]?.jsonPrimitive?.contentOrNull,
            role = MessageRole.TOOL,
            content = content,
            toolCallId = callId ?: sourceCallId,
            isError = isError,
            time = ev.time,
            seq = ev.seq,
            source = obj["source"]?.asObject(),
        )
    }

    fun parseContent(array: JsonArray?): List<ContentBlock> =
        array?.mapNotNull { el -> el.asObject()?.let { parseBlock(it) } }.orEmpty()

    fun parseBlock(obj: JsonObject): ContentBlock? = when (obj["type"]?.jsonPrimitive?.contentOrNull) {
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
        "image", "file" -> ContentBlock(BlockType.IMAGE, raw = obj)
        "tool-addition" -> ContentBlock(
            type = BlockType.TOOL_ADDITION,
            name = obj["toolName"]?.jsonPrimitive?.contentOrNull,
            raw = obj,
        )
        else -> if (obj["type"] is JsonPrimitive) ContentBlock(BlockType.UNKNOWN, raw = obj) else null
    }

    private fun roleOf(value: String?): MessageRole? = when (value) {
        "system" -> MessageRole.SYSTEM
        "user" -> MessageRole.USER
        "assistant" -> MessageRole.ASSISTANT
        "tool" -> MessageRole.TOOL
        "developer" -> MessageRole.DEVELOPER
        else -> null
    }

    fun JsonElement.asObject(): JsonObject? = runCatching { jsonObject }.getOrNull()

    /** 预览用：取消息里第一条正文/推理的纯文本。 */
    fun previewText(message: SessionMessage, max: Int = 160): String =
        message.content
            .filter { it.type == BlockType.TEXT }
            .joinToString(" ") { it.text }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(max)
}
