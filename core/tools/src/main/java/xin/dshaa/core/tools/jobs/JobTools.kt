package xin.dshaa.core.tools.jobs

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import xin.dshaa.core.jobs.JobException
import xin.dshaa.core.jobs.JobRead
import xin.dshaa.core.jobs.JobRender
import xin.dshaa.core.jobs.JobSnapshot
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult

private fun JsonObject.str(key: String): String? = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
private fun JsonObject.int(key: String): Int? = runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()
private fun JsonObject.bool(key: String): Boolean? = runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()

/** `job_output` 的默认等待与上限（上游 `waitTimeoutMs` / `maxWaitTimeoutMs`）。 */
private const val DEFAULT_WAIT_MS = 30_000L
private const val MAX_WAIT_MS = 600_000L

/**
 * `job_output`：读后台任务。流式任务返回「上次读取之后」的输出（消费式），
 * 每行结果末尾一定跟 `[status: …]`；`wait: true` 阻塞到结算或超时（超时不杀任务）。
 * 文案对齐上游 `dsh-tool-jobs` 的 render。
 */
class JobOutputTool : DshTool {
    override val name = "job_output"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val jobId = args.str("job_id")?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.err("invalid job_id: expected a non-empty string, got \"\"")
        val jobs = ctx.jobs ?: return ToolResult.err("background jobs unavailable in this composition")
        val owner = ctx.jobOwner()
        if (args.bool("wait") == true) {
            val timeout = (args.int("timeout_ms")?.toLong() ?: DEFAULT_WAIT_MS).coerceIn(1, MAX_WAIT_MS)
            try {
                jobs.wait(jobId, timeout, owner)
            } catch (e: JobException) {
                return ToolResult.err(e.message ?: "wait failed")
            }
        }
        val read: JobRead = try {
            jobs.read(jobId, owner)
        } catch (e: JobException) {
            return ToolResult.err(e.message ?: "read failed")
        }
        val body = StringBuilder()
        read.heldText?.let { body.append(it) }
        body.append(JobRender.renderModelDelta(read.chunks, read.lossy))
        read.result?.let { result ->
            if (body.isNotEmpty() && !body.endsWith("\n")) body.append('\n')
            body.append(result)
        }
        val shown = if (body.isEmpty()) "(no new output)" else body.toString()
        val text = shown + (if (shown.endsWith("\n")) "" else "\n") + JobRender.statusLine(read.job)
        return ToolResult.ok(text, meta = mapOf("status" to read.job.status.wire))
    }
}

/** `job_list`：一行一个 `<id> [<kind>] <status> — <label>`；空列表 `(no background jobs)`。 */
class JobListTool : DshTool {
    override val name = "job_list"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val jobs = ctx.jobs ?: return ToolResult.err("background jobs unavailable in this composition")
        val list: List<JobSnapshot> = jobs.list(ctx.jobOwner())
        val text = if (list.isEmpty()) {
            "(no background jobs)"
        } else {
            list.joinToString("\n") { "${it.id} [${it.kind}] ${it.status.wire} — ${it.label}" }
        }
        return ToolResult.ok(text)
    }
}

/**
 * `job_kill`：请求取消；返回 `requested cancellation of job <id>`，
 * 已经结束的任务返回 `job <id> had already finished [status: …]`。
 */
class JobKillTool : DshTool {
    override val name = "job_kill"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val jobId = args.str("job_id")?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.err("invalid job_id: expected a non-empty string, got \"\"")
        val jobs = ctx.jobs ?: return ToolResult.err("background jobs unavailable in this composition")
        val result = try {
            jobs.kill(jobId, ctx.jobOwner(), args.str("reason"))
        } catch (e: JobException) {
            return ToolResult.err(e.message ?: "kill failed")
        }
        val text = if (result.requested) {
            "requested cancellation of job ${result.job.id}"
        } else {
            "job ${result.job.id} had already finished ${JobRender.statusLine(result.job)}"
        }
        return ToolResult.ok(text, meta = mapOf("status" to result.job.status.wire))
    }
}
