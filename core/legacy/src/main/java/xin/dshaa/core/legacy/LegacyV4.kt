package xin.dshaa.core.legacy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.ContentBlock
import xin.dshaa.core.model.MessageRole
import xin.dshaa.core.model.SessionHeader
import xin.dshaa.core.model.SessionMessage
import xin.dshaa.core.session.SessionCodec
import xin.dshaa.core.session.ZstdFrames
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 旧会话 → v4 只读副本。
 *
 * **这不是官方 v0→v4 迁移链**（那条链要重排全部 seq、重写 surfaceOp/sourceEventSeqs、把 chunk 流内嵌），
 * 本类只保留「用户能看见的对话」：header + turn/step 骨架 + user/assistant/tool 消息 + 标题。
 * 好处是产物是**结构合法的 v4**（首帧 header、seq 连续、tool/result 是 v4 形态），
 * 我们的读取器和上游的宽容读都能打开；代价是日志类事件（token/approval/goal/compaction）不搬。
 * 取舍与后续升级路径写在 docs/23。
 */
object LegacyV4 {

    data class Event(val type: String, val time: Long, val data: JsonObject, val surfaceOp: String? = null)

    data class Built(val header: SessionHeader, val events: List<Event>)

    fun build(log: LegacyLog, newId: String, titleOverride: String? = null): Built {
        // 只搬「对话」：v3 的 system/message 是系统提示头节点、user 角色里还有运行时上下文注入，
        // 两者都不该变成聊天气泡（见 LegacyProjection.isRuntimeContext）
        val messages = LegacyProjection.conversation(log)
        val model = LegacyProjection.modelInfo(log)
        val events = ArrayList<Event>()

        if (model.provider != null || model.model != null) {
            events += Event(
                "request/header",
                log.header.createdAt,
                buildJsonObject {
                    put(
                        "header",
                        buildJsonObject {
                            put(
                                "config",
                                buildJsonObject {
                                    model.provider?.let { put("provider", it) }
                                    model.model?.let { put("model", it) }
                                },
                            )
                            put("reason", "initial")
                        },
                    )
                },
            )
        }

        var turn = 0
        var step = 0
        var open = false

        fun openTurn() {
            turn += 1
            step = 1
            open = true
            events += Event("turn/start", log.header.createdAt, buildJsonObject { put("turn", turn) })
            events += Event("step/start", log.header.createdAt, buildJsonObject { put("turn", turn); put("step", step) })
        }

        fun closeTurn(time: Long) {
            if (!open) return
            events += Event("step/end", time, buildJsonObject { put("turn", turn); put("step", step) })
            events += Event(
                "turn/end",
                time,
                buildJsonObject {
                    put("turn", turn)
                    put("reason", buildJsonObject { put("kind", "completed") })
                },
            )
            open = false
        }

        messages.forEach { message ->
            val time = message.time ?: log.header.createdAt
            when (message.role) {
                MessageRole.USER -> {
                    closeTurn(time)
                    openTurn()
                    events += Event("user/message", time, userMessage(message), surfaceOp = "append")
                }
                MessageRole.ASSISTANT -> {
                    if (!open) openTurn()
                    events += Event("assistant/message", time, assistantMessage(message, turn, step, model), surfaceOp = "append")
                    message.content.filter { it.type == BlockType.TOOL_CALL && it.toolCallId != null }.forEach { call ->
                        events += Event(
                            "tool/call",
                            time,
                            buildJsonObject {
                                put("turn", turn)
                                put("step", step)
                                put("callId", call.toolCallId.orEmpty())
                                put("name", call.name.orEmpty())
                                put("arguments", call.arguments)
                            },
                        )
                    }
                }
                MessageRole.TOOL -> {
                    if (!open) openTurn()
                    events += Event("tool/result", time, toolResult(message, turn, step), surfaceOp = "append")
                }
                else -> Unit
            }
        }
        closeTurn(messages.lastOrNull()?.time ?: log.header.createdAt)

        val title = titleOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: LegacyProjection.title(log)
            ?: messages.firstOrNull { it.role == MessageRole.USER && !it.text.isBlank() }?.text?.take(40)
        if (!title.isNullOrBlank()) {
            events += Event(
                "session/title",
                messages.lastOrNull()?.time ?: log.header.createdAt,
                buildJsonObject {
                    put("title", title.take(80))
                    put("messageSeqs", buildJsonArray { })
                    put("source", buildJsonObject { put("kind", "fallback") })
                },
            )
        }

        val header = SessionHeader(
            version = 4,
            id = newId,
            createdAt = log.header.createdAt,
            cwd = log.header.cwd,
            parentSession = null,
            isSeeded = false,
            origin = null,
            delegationDepth = 0,
            agentPreset = log.header.agentPreset,
        )
        return Built(header, events)
    }

    fun encode(built: Built): ByteArray =
        buildString {
            append(SessionCodec.encodeHeader(built.header)).append('\n')
            built.events.forEachIndexed { index, event ->
                append(
                    SessionCodec.encodeEvent(
                        type = event.type,
                        seq = index.toLong(),
                        time = event.time,
                        data = event.data,
                        surfaceOp = event.surfaceOp?.let { JsonPrimitive(it) },
                    ),
                ).append('\n')
            }
        }.toByteArray(Charsets.UTF_8)

    fun write(file: File, built: Built) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, false).use { out ->
            out.write(ZstdFrames.compressFrame(SessionCodec.encodeHeader(built.header) + "\n"))
            built.events.forEachIndexed { index, event ->
                out.write(
                    ZstdFrames.compressFrame(
                        SessionCodec.encodeEvent(
                            type = event.type,
                            seq = index.toLong(),
                            time = event.time,
                            data = event.data,
                            surfaceOp = event.surfaceOp?.let { JsonPrimitive(it) },
                        ) + "\n",
                    ),
                )
            }
        }
    }

    private fun userMessage(message: SessionMessage): JsonObject = buildJsonObject {
        put("content", contentJson(message.content))
        put("source", buildJsonObject { put("kind", "user") })
        put("role", "user")
        put("id", message.id ?: UUID.randomUUID().toString())
    }

    private fun assistantMessage(message: SessionMessage, turn: Int, step: Int, model: LegacyModelInfo): JsonObject {
        val source = message.source
        val provider = source?.get("provider")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: model.provider
        val modelName = source?.get("model")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: model.model
        return buildJsonObject {
            put("turn", turn)
            put("step", step)
            put(
                "message",
                buildJsonObject {
                    put("role", "assistant")
                    put("content", contentJson(message.content))
                    put(
                        "source",
                        buildJsonObject {
                            put("kind", "model")
                            if (!provider.isNullOrBlank()) put("provider", provider)
                            if (!modelName.isNullOrBlank()) put("model", modelName)
                        },
                    )
                    put("id", message.id ?: UUID.randomUUID().toString())
                },
            )
        }
    }

    private fun toolResult(message: SessionMessage, turn: Int, step: Int): JsonObject = buildJsonObject {
        put("turn", turn)
        put("step", step)
        put(
            "message",
            buildJsonObject {
                put("role", "tool")
                put(
                    "source",
                    buildJsonObject {
                        put("kind", "tool")
                        message.toolCallId?.let { put("callId", it) }
                    },
                )
                message.toolCallId?.let { put("toolCallId", it) }
                put("content", contentJson(message.content))
                put("isError", message.isError)
                put("id", message.id ?: UUID.randomUUID().toString())
            },
        )
    }

    private fun contentJson(blocks: List<ContentBlock>): JsonArray = buildJsonArray {
        blocks.forEach { block ->
            val json = when (block.type) {
                BlockType.TEXT -> buildJsonObject {
                    put("type", "text")
                    put("text", block.text)
                }
                BlockType.REASONING -> buildJsonObject {
                    put("type", "reasoning")
                    put("text", block.text)
                    if (block.signature.isNotBlank()) put("signature", block.signature)
                }
                BlockType.TOOL_CALL -> buildJsonObject {
                    put("type", "tool-call")
                    put("id", block.toolCallId.orEmpty())
                    put("name", block.name.orEmpty())
                    put("arguments", block.arguments)
                }
                BlockType.IMAGE -> block.raw ?: buildJsonObject { put("type", "image") }
                BlockType.TOOL_ADDITION -> block.raw ?: buildJsonObject {
                    put("type", "tool-addition")
                    block.name?.let { put("toolName", it) }
                }
                BlockType.UNKNOWN -> block.raw ?: buildJsonObject { put("type", "text"); put("text", "") }
            }
            add(json)
        }
    }
}
