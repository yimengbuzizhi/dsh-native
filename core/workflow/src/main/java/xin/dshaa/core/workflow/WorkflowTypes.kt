package xin.dshaa.core.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `workflow` 工具的 `meta`（对应上游 `WorkflowMeta`）。
 *
 * 上游在**发布 run 之前**校验 metadata：`name` / `description` 必填非空字符串，
 * `phases[].title` 必填；非法直接拒绝（不产生 run）。这里保持同样的严格度。
 */
data class WorkflowMeta(
    val name: String,
    val description: String,
    val whenToUse: String? = null,
    val phases: List<WorkflowPhase> = emptyList(),
) {
    data class WorkflowPhase(
        val title: String,
        val detail: String? = null,
        val provider: String? = null,
        val model: String? = null,
    )

    companion object {
        /** 解析 + 校验 meta JSON（原始 JSON 由工具层透传，字段顺序不影响语义）。 */
        fun parse(raw: String): WorkflowMeta {
            val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
                ?: throw WorkflowException("workflow meta must be valid JSON", "INVALID_META")
            val obj = element as? JsonObject
                ?: throw WorkflowException("workflow meta must be an object", "INVALID_META")
            fun str(key: String): String? =
                (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

            val name = str("name")?.takeIf { it.isNotBlank() }
                ?: throw WorkflowException("workflow meta.name must be a non-empty string", "INVALID_META")
            val description = str("description")?.takeIf { it.isNotBlank() }
                ?: throw WorkflowException("workflow meta.description must be a non-empty string", "INVALID_META")
            val whenToUse = str("whenToUse")
            val phases = (obj["phases"] as? JsonArray)?.mapIndexed { index, element ->
                val phase = element as? JsonObject
                    ?: throw WorkflowException("workflow meta.phases[$index] must be an object", "INVALID_META")
                val title = (phase["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf { it.isNotBlank() }
                    ?: throw WorkflowException("workflow meta.phases[$index].title must be a non-empty string", "INVALID_META")
                fun opt(key: String): String? = (phase[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                WorkflowPhase(title, opt("detail"), opt("provider"), opt("model"))
            }.orEmpty()
            return WorkflowMeta(name, description, whenToUse, phases)
        }
    }
}

/** workflow 的失败（错误码对齐上游 `WorkflowErrorCode` 的主要子集；文案给模型看）。 */
class WorkflowException(message: String, val code: String = "WORKFLOW_ERROR") : IllegalArgumentException(message)

/** 声明 run 的结果原因（对齐上游 `WorkflowStopReason`）。 */
object WorkflowStopReason {
    const val COMPLETED = "completed"
    const val CANCELLED = "cancelled"
    const val ERROR = "error"
}

/** 一次 run 的结果（`value` 是脚本返回值的 JSON 文本；失败时 null）。 */
data class WorkflowRunResult(
    val stopReason: String,
    val valueJson: String? = null,
    val error: String? = null,
    val agentsStarted: Int = 0,
) {
    val completed: Boolean get() = stopReason == WorkflowStopReason.COMPLETED
}

/** 引擎限额（对齐上游 `dsh-workflow-ptc` 默认值）。 */
data class WorkflowLimits(
    val maxConcurrentAgents: Int = 4,
    val maxTotalAgents: Int = 1_000,
    val maxItemsPerCall: Int = 4_096,
)

/** 单个子代理请求（脚本 `agent()` 的入参）。 */
data class WorkflowChildRequest(
    val prompt: String,
    val label: String,
    val phase: String? = null,
    val schemaJson: String? = null,
    val provider: String? = null,
    val model: String? = null,
)

/** 子代理执行结果：失败（未完成 / 没拿到结构化结果）时由引擎映射成 `null`。 */
data class WorkflowChildOutcome(
    val completed: Boolean,
    val text: String? = null,
    val structuredJson: String? = null,
    val error: String? = null,
)

/** 宿主提供：真正跑一个子代理（App 用 TurnEngine + 子会话实现）。 */
fun interface WorkflowChildHost {
    suspend fun run(request: WorkflowChildRequest): WorkflowChildOutcome
}

/** 观察者：阶段 / 日志 / 子代理生命周期（进度面板、后台 job 的 log 流）。 */
interface WorkflowObserver {
    fun phase(title: String) {}

    fun log(message: String) {}

    fun agentStart(seq: Int, label: String, phase: String?, childId: String?) {}

    fun agentEnd(seq: Int, outcome: String) {}
}
