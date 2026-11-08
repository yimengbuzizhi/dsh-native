package xin.dshaa.core.session

import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.ChatMessage
import xin.dshaa.core.model.MessageRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话导出（Markdown）。
 *
 * 上游有 `dsh-session-log-export`（会话日志导出），桌面端聊天页没有「导出为 Markdown」的入口，
 * 所以格式由我们自己定：**只导出用户看得见的内容**，内部提示词（goal 轮、任务通知、技能目录…）
 * 只留一行来源注记，不把模型上下文倒进文件。
 *
 * 纯函数（不碰 Android），单测锁住：消息分组、思考折叠、工具调用/结果配对、截断、文件名清洗。
 */
object SessionMarkdown {

    /** 单个工具结果最多写这么多字符；超出截断并注明原长（导出是给人看的，不是归档原始 JSONL）。 */
    const val TOOL_RESULT_LIMIT = 4_000

    private const val FILE_TITLE_LIMIT = 40

    /**
     * 渲染整份会话。
     *
     * @param title 会话标题（最后一条生效的 `session/title`）
     * @param model 当前模型名
     * @param sessionId 会话 id（可空：还没落盘的草稿）
     * @param exportedAt 导出时刻（毫秒），只影响页眉时间
     * @param messages UI 投影出来的消息序列（含 TOOL 消息；内部来源的消息只留注记）
     */
    fun render(
        title: String,
        model: String,
        sessionId: String?,
        exportedAt: Long,
        messages: List<ChatMessage>,
    ): String {
        val visible = messages.filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
        val toolResults = messages
            .asSequence()
            .filter { it.role == MessageRole.TOOL && it.toolCallId != null }
            .associateBy { it.toolCallId!! }

        val out = StringBuilder()
        out.append("# ").append(title.ifBlank { "会话" }).append("\n\n")
        out.append("> ")
            .append("模型 ").append(model.ifBlank { "未知" })
            .append(" · 会话 ").append(sessionId?.take(8) ?: "未保存")
            .append(" · 导出 ").append(timestamp(exportedAt))
            .append(" · ").append(visible.size).append(" 条消息")
            .append("\n\n---\n")

        var assistantRun = mutableListOf<ChatMessage>()

        fun flushAssistant() {
            if (assistantRun.isEmpty()) return
            appendAssistantRun(out, assistantRun, toolResults)
            assistantRun = mutableListOf()
        }

        visible.forEach { message ->
            when (message.role) {
                MessageRole.USER -> {
                    flushAssistant()
                    val source = message.sourceKind
                    if (source != null && source != "user") {
                        out.append("\n> 〔").append(sourceLabel(message)).append("〕\n")
                    } else {
                        val text = message.content
                            .filter { it.type == BlockType.TEXT }
                            .joinToString("\n") { it.text }
                            .trim()
                        if (text.isNotEmpty()) {
                            out.append("\n## 用户 · ").append(timestamp(message.createdAt)).append("\n\n")
                            out.append(text).append("\n")
                        }
                    }
                }
                MessageRole.ASSISTANT -> assistantRun += message
                else -> Unit
            }
        }
        flushAssistant()

        return out.toString().trimEnd() + "\n"
    }

    private fun appendAssistantRun(
        out: StringBuilder,
        run: List<ChatMessage>,
        toolResults: Map<String, ChatMessage>,
    ) {
        val body = StringBuilder()
        run.forEach { message ->
            val reasoning = message.content
                .filter { it.type == BlockType.REASONING }
                .joinToString("") { it.text }
                .trim()
            if (reasoning.isNotEmpty()) {
                body.append("\n<details><summary>思考过程 · ").append(reasoning.length).append(" 字</summary>\n\n")
                body.append("```text\n").append(reasoning).append("\n```\n\n</details>\n")
            }
            message.content.filter { it.type == BlockType.TOOL_CALL }.forEach { call ->
                val name = call.name.orEmpty().ifBlank { "tool" }
                body.append("\n**工具 · ").append(name).append("**\n\n")
                body.append("```json\n").append(call.arguments.ifBlank { "{}" }).append("\n```\n")
                val result = call.toolCallId?.let { toolResults[it] }
                val resultText = result?.content
                    ?.filter { it.type == BlockType.TEXT }
                    ?.joinToString("") { it.text }
                    ?.trim()
                    .orEmpty()
                if (resultText.isNotEmpty()) {
                    val shown = if (resultText.length > TOOL_RESULT_LIMIT) {
                        resultText.take(TOOL_RESULT_LIMIT) + "\n…（已截断，原长 ${resultText.length} 字符）"
                    } else {
                        resultText
                    }
                    body.append("\n<details><summary>工具结果</summary>\n\n")
                    body.append("```text\n").append(shown).append("\n```\n\n</details>\n")
                }
            }
            val text = message.content
                .filter { it.type == BlockType.TEXT }
                .joinToString("") { it.text }
                .trim()
            if (text.isNotEmpty()) {
                body.append("\n").append(text).append("\n")
            }
            message.error?.takeIf { it.isNotBlank() }?.let { body.append("\n> ⚠️ ").append(it).append("\n") }
        }

        if (body.isBlank()) return
        out.append("\n## 助手 · ").append(timestamp(run.first().createdAt)).append("\n")
        out.append(body.toString().trimEnd()).append("\n")
    }

    /** 内部来源的单行注记（不导出正文：那些是给模型的上下文，不是聊天内容）。 */
    private fun sourceLabel(message: ChatMessage): String = when (message.sourceKind) {
        "goal" -> "自动续轮" + (message.goalRound?.let { " · 第 $it 轮" } ?: "")
        "tool-goal" -> "目标收尾通知"
        "tool-jobs" -> "后台任务通知"
        "subagent-settled" -> "子代理完成通知"
        "agent-message" -> "子代理消息"
        "plan-mode" -> "计划模式通知"
        "skill-catalog" -> "技能目录" + (message.sourceSummary?.let { " · $it" } ?: "")
        "skill-invocation" -> "技能指令" + (message.sourceSummary?.let { " · $it" } ?: "")
        else -> message.sourceKind.orEmpty()
    }

    /**
     * 下载目录里的文件名：`DSH-<标题>-<yyyyMMdd-HHmmss>.md`。
     * 标题里的路径分隔符/控制字符换成 `-`，空白压成一个 `-`，最长 40 字；空标题退化为「会话」。
     */
    fun fileName(title: String, at: Long): String {
        val safe = title.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "-")
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(FILE_TITLE_LIMIT)
            .ifBlank { "会话" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))
        return "DSH-$safe-$stamp.md"
    }

    /** 本地时间 `yyyy-MM-dd HH:mm`；导出文件给人看，不写秒。 */
    private fun timestamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(if (ms > 0) ms else System.currentTimeMillis()))
}
