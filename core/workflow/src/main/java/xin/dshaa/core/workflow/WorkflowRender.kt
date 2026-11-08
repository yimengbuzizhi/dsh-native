package xin.dshaa.core.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * workflow 的模型可见文案（逐字对齐上游 `dsh-tool-workflow`）：
 *
 * - 前台结果：`workflow "<name>" completed (N agents).` + `Return value:` + 缩进 JSON；
 * - 后台：`workflow "<name>" started in the background as job <id>. …`
 * - 非 completed 的 stop reason 在工具层翻成 tool error（cancelled / failed）。
 */
object WorkflowRender {

    /** 前台/后台任务结果的正文上限（上游 `maxResultChars` 默认 5e4）。 */
    const val MAX_RESULT_CHARS = 50_000

    fun resultText(name: String, agentsStarted: Int, valueJson: String?, maxChars: Int = MAX_RESULT_CHARS): String {
        val rendered = pretty(valueJson)
        val clipped = if (rendered.length > maxChars) {
            rendered.take(maxChars) + "\n… [truncated: ${rendered.length - maxChars} more characters]"
        } else {
            rendered
        }
        val noun = if (agentsStarted == 1) "agent" else "agents"
        return "workflow \"$name\" completed ($agentsStarted $noun).\nReturn value:\n$clipped"
    }

    /** 后台登记后的工具回执（上游 presentResult 的 background 分支）。 */
    fun backgroundText(name: String, jobId: String): String =
        "workflow \"$name\" started in the background as job $jobId. Its return value arrives with the " +
            "completion notice; check on it with job_output, stop it with job_kill."

    /** 前台失败：上游 `stopReasonError`。 */
    fun stopReasonError(result: WorkflowRunResult): String? = when (result.stopReason) {
        WorkflowStopReason.COMPLETED -> null
        WorkflowStopReason.CANCELLED ->
            "workflow run was cancelled" + (result.error?.let { " ($it)" } ?: "")
        WorkflowStopReason.ERROR -> "workflow run failed: ${result.error ?: "unknown error"}"
        else -> "workflow run ended abnormally (${result.stopReason})"
    }

    /** 后台任务的 detail：`N agents`（与上游 jobOutcomeOf 一致）。 */
    fun agentsDetail(agentsStarted: Int): String =
        "$agentsStarted " + if (agentsStarted == 1) "agent" else "agents"

    private val prettyJson = Json { prettyPrint = true }

    private fun pretty(valueJson: String?): String {
        if (valueJson.isNullOrBlank()) return "null"
        return runCatching {
            val element = Json.parseToJsonElement(valueJson)
            if (element is JsonNull) "null" else prettyJson.encodeToString(JsonElement.serializer(), element)
        }.getOrDefault(valueJson)
    }
}
