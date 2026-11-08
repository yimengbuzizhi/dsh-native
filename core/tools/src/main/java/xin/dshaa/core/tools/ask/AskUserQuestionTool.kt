package xin.dshaa.core.tools.ask

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xin.dshaa.core.tools.AskAnswer
import xin.dshaa.core.tools.AskOption
import xin.dshaa.core.tools.AskQuestion
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult

/**
 * `ask_user_question`：需要确认/选择/缺信息时问用户。
 * 工具本身只做参数解析与结果格式化，真正的 UI 通道由 [ToolContext.askUser] 提供（App 层挂对话框）。
 */
class AskUserQuestionTool : DshTool {
    override val name = "ask_user_question"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val raw = args["questions"] as? JsonArray ?: return ToolResult.err("missing questions")
        val questions = raw.mapNotNull { el ->
            val obj = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = obj.str("id") ?: return@mapNotNull null
            val question = obj.str("question") ?: return@mapNotNull null
            val options = (obj["options"] as? JsonArray)?.mapNotNull { o ->
                val oo = runCatching { o.jsonObject }.getOrNull() ?: return@mapNotNull null
                val label = oo.str("label") ?: return@mapNotNull null
                AskOption(label, oo.str("description"))
            }.orEmpty()
            AskQuestion(
                id = id,
                question = question,
                header = obj.str("header"),
                options = options,
                multiSelect = runCatching { (obj["multi_select"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull }.getOrNull() ?: false,
            )
        }
        if (questions.isEmpty()) return ToolResult.err("no valid questions")

        val channel = ctx.askUser ?: return ToolResult.err("ask_user_question is not available in this client")
        val answers = channel(questions)
        val byId = answers.associateBy { it.id }
        val text = buildString {
            appendLine("User answers:")
            questions.forEach { q ->
                val a = byId[q.id]
                append("- [${q.id}] ${q.question}")
                appendLine()
                val selected = a?.selected.orEmpty()
                val custom = a?.custom.orEmpty()
                val answerText = when {
                    selected.isNotEmpty() && custom.isNotBlank() -> selected.joinToString(", ") + " / " + custom
                    selected.isNotEmpty() -> selected.joinToString(", ")
                    custom.isNotBlank() -> custom
                    else -> "(skipped)"
                }
                appendLine("  → $answerText")
            }
        }
        return ToolResult.ok(text.trimEnd())
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
}
