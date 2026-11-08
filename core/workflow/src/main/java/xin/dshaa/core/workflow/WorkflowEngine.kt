package xin.dshaa.core.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/** JS 运行时的最小接口（App 用 `core:js` 的 QuickJS 适配；单测用假实现）。 */
interface WorkflowJsRuntime {
    /** 执行脚本并阻塞到根 Promise / done 回调结束。 */
    fun run(code: String, filename: String, host: WorkflowJsHost): WorkflowJsResult

    fun complete(callId: Int, ok: Boolean, payloadJson: String?)

    fun interrupt()
}

/** JS 侧宿主回调（在 JS 线程上调用）。 */
interface WorkflowJsHost {
    fun callSync(method: String, payloadJson: String): String

    fun callAsync(method: String, payloadJson: String, callId: Int)
}

enum class WorkflowJsStatus { COMPLETED, ERROR, INTERRUPTED }

data class WorkflowJsResult(
    val status: WorkflowJsStatus,
    val valueJson: String? = null,
    val error: String? = null,
)

/** 一次 workflow run 的句柄：结果 + 取消（后台 job / 前台 abort 共用）。 */
class WorkflowRun internal constructor(
    val id: String,
    val result: CompletableDeferred<WorkflowRunResult>,
    private val cancelImpl: (String?) -> Unit,
) {
    fun cancel(reason: String? = null) = cancelImpl(reason)
}

/**
 * workflow 引擎：把「生成 JS 外壳 → 跑 JS → agent() 子代理 → 结果」串起来。
 *
 * 语义对齐上游 `dsh-workflow` + `dsh-workflow-ptc`：
 * - `agent()` 立即返回 Promise，多个调用天然并发（`parallel` 因此是真并发）；
 * - 子代理失败只让该 `agent()` 兑现 `null`，不终止脚本；选项/限额/schema 错误是 fatal；
 * - 取消 = 中断 JS + 取消所有子代理，结果 stopReason = cancelled。
 *
 * 引擎不碰 Android：JS 运行时与子代理执行都由宿主注入。
 */
class WorkflowEngine(
    private val jsFactory: () -> WorkflowJsRuntime,
    private val children: WorkflowChildHost,
    private val scope: CoroutineScope,
    private val limits: WorkflowLimits = WorkflowLimits(),
    private val observer: WorkflowObserver = object : WorkflowObserver {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 启动一次 run。脚本/meta 的合法性由调用方先校验（工具层）；这里只做运行时的事。
     * 返回句柄后立刻返回；结果在 [WorkflowRun.result] 上。
     */
    fun start(script: String, meta: WorkflowMeta, argsJson: String?): WorkflowRun {
        val runId = UUID.randomUUID().toString()
        val result = CompletableDeferred<WorkflowRunResult>()
        val runtime = jsFactory()
        val semaphore = Semaphore(limits.maxConcurrentAgents.coerceAtLeast(1))
        val childJobs = java.util.Collections.synchronizedSet(mutableSetOf<Job>())
        val started = java.util.concurrent.atomic.AtomicInteger(0)
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

        val host = object : WorkflowJsHost {
            override fun callSync(method: String, payloadJson: String): String {
                val payload = runCatching { json.parseToJsonElement(payloadJson).jsonObject }.getOrNull()
                when (method) {
                    "phase" -> payload?.get("title")?.jsonPrimitive?.contentOrNull?.let { observer.phase(it) }
                    "log" -> payload?.get("message")?.jsonPrimitive?.contentOrNull?.let { observer.log(it) }
                    else -> observer.log("unknown host call: $method")
                }
                return ""
            }

            override fun callAsync(method: String, payloadJson: String, callId: Int) {
                if (method != "agent") {
                    runtime.complete(callId, false, errorPayload("unknown host call: $method", "HOST_UNAVAILABLE"))
                    return
                }
                val payload = runCatching { json.parseToJsonElement(payloadJson).jsonObject }.getOrNull()
                if (payload == null) {
                    runtime.complete(callId, false, errorPayload("agent() payload is not an object", "INVALID_ARGUMENT"))
                    return
                }
                dispatchAgent(payload, callId, runtime, semaphore, childJobs) { started.incrementAndGet() }
            }
        }

        val job = scope.launch(Dispatchers.IO) {
            val filename = "workflow:${meta.name}"
            val code = WorkflowScript.build(script, argsJson, limits)
            val jsResult = try {
                runtime.run(code, filename, host)
            } catch (t: Throwable) {
                WorkflowJsResult(
                    WorkflowJsStatus.ERROR,
                    error = t.message ?: t.javaClass.simpleName,
                )
            }
            if (cancelled.get()) {
                result.complete(WorkflowRunResult(WorkflowStopReason.CANCELLED, agentsStarted = started.get()))
                return@launch
            }
            when (jsResult.status) {
                WorkflowJsStatus.COMPLETED -> result.complete(
                    WorkflowRunResult(WorkflowStopReason.COMPLETED, valueJson = jsResult.valueJson, agentsStarted = started.get()),
                )
                WorkflowJsStatus.INTERRUPTED -> result.complete(
                    WorkflowRunResult(WorkflowStopReason.CANCELLED, agentsStarted = started.get()),
                )
                WorkflowJsStatus.ERROR -> result.complete(
                    WorkflowRunResult(WorkflowStopReason.ERROR, error = jsResult.error, agentsStarted = started.get()),
                )
            }
        }

        return WorkflowRun(runId, result) { reason ->
            if (cancelled.get() || result.isCompleted) return@WorkflowRun
            cancelled.set(true)
            runCatching { runtime.interrupt() }
            childJobs.toList().forEach { it.cancel() }
            job.cancel()
            result.complete(WorkflowRunResult(WorkflowStopReason.CANCELLED, error = reason, agentsStarted = started.get()))
        }
    }

    private fun dispatchAgent(
        payload: JsonObject,
        callId: Int,
        runtime: WorkflowJsRuntime,
        semaphore: Semaphore,
        childJobs: MutableSet<Job>,
        onStarted: () -> Unit,
    ) {
        val prompt = payload["prompt"]?.jsonPrimitive?.contentOrNull
        if (prompt.isNullOrBlank()) {
            runtime.complete(callId, false, errorPayload("agent() requires a non-empty prompt string", "INVALID_ARGUMENT"))
            return
        }
        val label = payload["label"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: prompt.take(48)
        val phase = payload["phase"]?.jsonPrimitive?.contentOrNull
        val schemaElement = payload["schema"]?.takeIf { it !is JsonNull }
        val schema = if (schemaElement != null) {
            try {
                WorkflowSchema.assertObjectSchema(schemaElement)
            } catch (e: WorkflowException) {
                runtime.complete(callId, false, errorPayload(e.message ?: "unsupported schema", "UNSUPPORTED_SCHEMA"))
                return
            }
        } else {
            null
        }
        val request = WorkflowChildRequest(
            prompt = prompt,
            label = label,
            phase = phase,
            schemaJson = schema?.toString(),
            provider = payload["provider"]?.jsonPrimitive?.contentOrNull,
            model = payload["model"]?.jsonPrimitive?.contentOrNull,
        )
        val seq = payload["seq"]?.jsonPrimitive?.intOrNull ?: 0
        onStarted()
        val job = scope.launch {
            semaphore.withPermit {
                observer.agentStart(seq, label, phase, null)
                val outcome = try {
                    children.run(request)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    observer.agentEnd(seq, "failed")
                    runtime.complete(
                        callId,
                        false,
                        errorPayload(t.message ?: "child agent failed", "AGENT_START"),
                    )
                    return@withPermit
                }
                if (!outcome.completed) {
                    observer.agentEnd(seq, "failed")
                    runtime.complete(callId, true, "null")
                    return@withPermit
                }
                if (schema == null) {
                    val text = outcome.text ?: outcome.structuredJson
                    observer.agentEnd(seq, "completed")
                    runtime.complete(callId, true, jsonString(text.orEmpty()))
                    return@withPermit
                }
                val structured = outcome.structuredJson ?: outcome.text
                val parsed = structured?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
                val valid = parsed != null && WorkflowSchema.validate(parsed, schema) == null
                if (!valid) {
                    observer.agentEnd(seq, "failed")
                    runtime.complete(callId, true, "null")
                    return@withPermit
                }
                observer.agentEnd(seq, "completed")
                runtime.complete(callId, true, parsed.toString())
            }
        }
        childJobs += job
        job.invokeOnCompletion { childJobs.remove(job) }
    }

    private fun jsonString(value: String): String = JsonPrimitive(value).toString()

    private fun errorPayload(message: String, code: String): String = buildJsonObject {
        put("message", message)
        put("code", code)
        put("fatal", true)
    }.toString()
}
