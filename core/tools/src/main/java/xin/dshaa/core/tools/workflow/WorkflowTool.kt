package xin.dshaa.core.tools.workflow

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult

/**
 * `workflow` 工具（第 32 个）：schema 来自上游 fixtures（tools.json），
 * 真正的执行（JS 引擎 + 子代理 + 后台 job）由 App 注入的 [WorkflowHost] 完成。
 *
 * 工具层只做三件事：
 * - 参数形状校验（script / meta / args / run_in_background）；
 * - 交给宿主；
 * - 把宿主的结果映射成 `ToolResult`（宿主返回的文案已是模型可见的最终文案）。
 */
class WorkflowTool : DshTool {
    override val name = "workflow"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val host = ctx.workflowHost
            ?: return ToolResult.err("workflow runtime unavailable in this composition")
        val script = (args["script"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return ToolResult.err("script is required and must be a string")
        if (script.isBlank()) return ToolResult.err("script is required and must be a non-empty string")
        val meta = args["meta"] as? JsonObject
            ?: return ToolResult.err("meta is required and must be an object")
        val options = args["args"]
        if (options != null && options !is JsonObject) {
            return ToolResult.err("args must be an object when provided")
        }
        val background = (args["run_in_background"] as? JsonPrimitive)?.booleanOrNull == true

        val outcome = host.execute(
            script = script,
            metaJson = meta.toString(),
            argsJson = options?.toString(),
            background = background,
            owner = ctx.jobOwner(),
        )
        return if (outcome.isError) ToolResult.err(outcome.text) else ToolResult.ok(outcome.text)
    }
}

/** `workflow` 的宿主接口（App 实现；core:tools 保持纯 JVM、可单测）。 */
interface WorkflowHost {
    /**
     * 执行一次 workflow。[background] = true 时立刻返回 `started ... job <id>` 文案，
     * 结果通过 job 的完成通知交付（与上游 `run_in_background` 一致）。
     */
    suspend fun execute(
        script: String,
        metaJson: String,
        argsJson: String?,
        background: Boolean,
        owner: String?,
    ): WorkflowToolOutcome
}

data class WorkflowToolOutcome(val text: String, val isError: Boolean = false)

/** JSON 小工具：从工具参数里取字符串（与其它工具保持同样的宽松取值）。 */
internal fun JsonObject.workflowString(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
