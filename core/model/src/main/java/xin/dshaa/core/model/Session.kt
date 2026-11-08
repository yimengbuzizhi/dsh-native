package xin.dshaa.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * DSH 会话模型（v4 及以后的公共部分）。
 *
 * 设计约束（用户 2026-11-06 定调）：上游内核会持续更新，模型层必须能容纳新事件类型，
 * 所以事件保留原始 JSON（[SessionEvent.data]），投影层只认已知类型，未知类型原样保留、不丢弃。
 */

/** 会话文件第一行。字段与 v4 对齐；未来版本新增字段应放 [extra]，不要改已有字段语义。 */
@Serializable
data class SessionHeader(
    val type: String = "session",
    val version: Int,
    val id: String,
    val createdAt: Long,
    val cwd: String = "",
    val parentSession: String? = null,
    val isSeeded: Boolean = false,
    val origin: String? = null,
    val delegationDepth: Int = 0,
    val agentPreset: String? = null,
)

enum class MessageRole { SYSTEM, USER, ASSISTANT, TOOL, DEVELOPER, UNKNOWN }

enum class BlockType { TEXT, REASONING, TOOL_CALL, IMAGE, TOOL_ADDITION, UNKNOWN }

/** 消息内容块：与上游 content[] 的 type 对齐，未知块保留原始 JSON。 */
data class ContentBlock(
    val type: BlockType,
    val text: String = "",
    val toolCallId: String? = null,
    val name: String? = null,
    val arguments: String = "",
    /** reasoning 块的 Anthropic thinking signature；多轮回传时必须原样带回（契约 #1）。 */
    val signature: String = "",
    val raw: JsonObject? = null,
)

data class SessionMessage(
    val id: String? = null,
    val role: MessageRole,
    val content: List<ContentBlock>,
    val toolCallId: String? = null,
    val isError: Boolean = false,
    val time: Long? = null,
    val seq: Long? = null,
    val source: JsonObject? = null,
) {
    val text: String get() = content.filter { it.type == BlockType.TEXT || it.type == BlockType.REASONING }
        .joinToString("") { it.text }
}

/** 一条原始会话事件（JSONL 的一行，除首行外）。 */
data class SessionEvent(
    val type: String,
    val seq: Long? = null,
    val time: Long? = null,
    val data: JsonElement? = null,
    /**
     * 会话面的**替换指令**（M43，上游 `surfaceOp`）：普通消息写字符串 `"append"`，
     * 压缩替换消息写对象 `{"op":"replace","startSeq":…,"endSeq":…}` —— 这条消息顶掉那个区间。
     * 旧读取器（M42 之前）没有这个字段，读桌面压缩过的会话会把旧历史也铺出来。
     */
    val surfaceOp: JsonElement? = null,
)

/**
 * 计划模式折叠（`plan/mode` 事件，M18）：
 * - [active]：最后一条 `plan/mode` 的开关；
 * - [entered]：本会话是否曾经进过计划模式（上游 `entered`，工具准入沿用到会话结束）。
 */
data class PlanModeView(
    val active: Boolean = false,
    val entered: Boolean = false,
)

data class SessionTitle(val title: String, val seq: Long? = null)

/** 会话摘要（列表页用）。 */
data class SessionSummary(
    val id: String,
    val title: String = "",
    val preview: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val messageCount: Int = 0,
    val cwd: String = "",
    val path: String = "",
    val draft: Boolean = false,
    /** 旧版备份只读导入的会话（会话目录里有 imported.json 侧车）。 */
    val legacy: Boolean = false,
    /** 子代理会话（header.parentSession）：主会话列表默认不展示，留给子代理面板。 */
    val parentSession: String? = null,
    /** 子代理委派深度（根 = 0；面板显示「深度 N」）。 */
    val delegationDepth: Int = 0,
    /** 产品来源（子代理 = `subagent`；普通会话为 null）。 */
    val origin: String? = null,
    /** fork 出来的子代理：继承了父会话已完成的轮次。 */
    val isSeeded: Boolean = false,
    /** 最后一条 `turn/end` 的 reason.kind（completed / aborted / error…；没跑完为 null）。 */
    val lastStopReason: String? = null,
)

data class ToolCall(val id: String, val name: String, val arguments: String)

data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val cachedTokens: Int = 0,
)

@SerialName("message")
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: List<ContentBlock> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val streaming: Boolean = false,
    val error: String? = null,
    val usage: Usage? = null,
    val toolCallId: String? = null,
    /** 会话里的 seq（分支/回滚按它切前缀；流式临时消息为 null）。 */
    val seq: Long? = null,
    /** user 消息来源：`user`（人类）/ `goal`（自动续轮）/ `tool-goal`（goal 收尾通知）；null = 未知/assistant。 */
    val sourceKind: String? = null,
    /** goal 来源消息的轮次（UI 显示「第 N 轮」而不是内部提示词）。 */
    val goalRound: Int? = null,
    /** 通知类消息的单行摘要（`tool-goal` 收尾通知；上游 source.summary，已按 120 字截断）。 */
    val sourceSummary: String? = null,
)
