package xin.dshaa.core.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import xin.dshaa.core.jobs.JobOutcome
import xin.dshaa.core.jobs.JobRegistry
import xin.dshaa.core.jobs.JobRender
import xin.dshaa.core.jobs.JobStatus
import xin.dshaa.core.tools.jobs.JobKillTool
import xin.dshaa.core.tools.jobs.JobListTool
import xin.dshaa.core.tools.jobs.JobOutputTool
import xin.dshaa.core.tools.shell.BashTool
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `job_list` / `job_output` / `job_kill` 与 `bash run_in_background` 的模型侧契约：
 * 读取是消费式的、状态行一定在末尾、list 一行一个任务、kill 分 requested/already-finished。
 * 文案对齐上游 `dsh-tool-jobs`（docs/32）。
 */
class JobToolsTest {

    private fun workspace(): File = Files.createTempDirectory("job-tools-test").toFile()

    private fun arg(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun terminal(registry: JobRegistry, id: String): Unit = runBlocking {
        repeat(300) {
            if (registry.get(id, "s1").status.terminal) return@runBlocking
            delay(10)
        }
    }

    @Test
    fun jobListRendersOneLinePerJob() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val registry = JobRegistry(scope, inlineOutputBytes = 0)
            val started = registry.start("bash", "echo hi", "s1") { control ->
                control.stdout("hi\n")
                JobOutcome(JobStatus.COMPLETED, "exit code: 0")
            }
            registry.wait(started.id, 2_000, "s1")
            val ctx = ToolContext(workspace(), jobs = registry, jobOwner = { "s1" })

            val list = JobListTool().execute(Json.parseToJsonElement("{}").jsonObject, ctx)
            assertEquals("${started.id} [bash] completed — echo hi", list.text)
            assertEquals("(no background jobs)", JobListTool().execute(Json.parseToJsonElement("{}").jsonObject, ToolContext(workspace(), jobs = registry, jobOwner = { "s2" })).text)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun jobOutputIsConsumingAndAlwaysEndsWithStatusLine() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val registry = JobRegistry(scope, inlineOutputBytes = 0)
            val started = registry.start("bash", "echo hi", "s1") { control ->
                control.stdout("hi\n")
                JobOutcome(JobStatus.COMPLETED, "exit code: 0")
            }
            registry.wait(started.id, 2_000, "s1")
            val ctx = ToolContext(workspace(), jobs = registry, jobOwner = { "s1" })

            val first = JobOutputTool().execute(arg("""{"job_id":"${started.id}"}"""), ctx)
            assertEquals("hi\n[status: completed, exit code: 0]", first.text)
            val second = JobOutputTool().execute(arg("""{"job_id":"${started.id}"}"""), ctx)
            assertEquals("(no new output)\n[status: completed, exit code: 0]", second.text)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun jobOutputWaitCollectsTerminalState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val registry = JobRegistry(scope, inlineOutputBytes = 0)
            val release = CompletableDeferred<Unit>()
            val started = registry.start("bash", "sleep", "s1") { control ->
                control.stdout("done\n")
                release.await()
                JobOutcome(JobStatus.COMPLETED, "exit code: 0")
            }
            val ctx = ToolContext(workspace(), jobs = registry, jobOwner = { "s1" })
            launch(Dispatchers.Default) {
                delay(60)
                release.complete(Unit)
            }
            val result = JobOutputTool().execute(arg("""{"job_id":"${started.id}","wait":true,"timeout_ms":2000}"""), ctx)
            assertEquals("done\n[status: completed, exit code: 0]", result.text)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun jobKillReportsRequestedThenAlreadyFinished() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val registry = JobRegistry(scope)
            val release = CompletableDeferred<Unit>()
            val started = registry.start("bash", "sleep", "s1") { control ->
                control.onCancel { release.complete(Unit) }
                release.await()
                JobOutcome(JobStatus.KILLED, "killed before exit")
            }
            val ctx = ToolContext(workspace(), jobs = registry, jobOwner = { "s1" })
            val killed = JobKillTool().execute(arg("""{"job_id":"${started.id}","reason":"不再需要"}"""), ctx)
            assertEquals("requested cancellation of job ${started.id}", killed.text)
            terminal(registry, started.id)
            val again = JobKillTool().execute(arg("""{"job_id":"${started.id}"}"""), ctx)
            assertEquals("job ${started.id} had already finished [status: killed, killed before exit]", again.text)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun bashRunInBackgroundStartsJobAndStreamsOutput() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val registry = JobRegistry(scope, inlineOutputBytes = 0)
            val ctx = ToolContext(workspace(), jobs = registry, jobOwner = { "s1" })
            val started = BashTool().execute(
                buildJsonObject {
                    put("command", "echo bg-ok")
                    put("run_in_background", true)
                },
                ctx,
            )
            assertTrue(started.text.startsWith("started background job bash-"), started.text)
            val id = started.meta["jobId"]!!
            registry.wait(id, 5_000, "s1")
            val read = registry.read(id, "s1")
            assertEquals("bg-ok", JobRender.renderModelDelta(read.chunks, read.lossy).trim())
            assertEquals(JobStatus.COMPLETED, read.job.status)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun bashRunInBackgroundKillSettlesKilledNotFailed() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val registry = JobRegistry(scope, inlineOutputBytes = 0)
            val ctx = ToolContext(workspace(), jobs = registry, jobOwner = { "s1" })
            val command = if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
                // 必须在 cmd 进程内跑，不能留子进程持有管道（否则 destroyForcibly 后 pump 永远等不到 EOF）
                "for /L %i in (1,1,100000000) do @echo tick"
            } else {
                "while true; do echo tick; sleep 1; done"
            }
            val started = BashTool().execute(
                buildJsonObject {
                    put("command", command)
                    put("run_in_background", true)
                },
                ctx,
            )
            val id = started.meta["jobId"]!!
            delay(400)
            registry.kill(id, "s1", "test")
            val settled = registry.wait(id, 5_000, "s1")
            assertEquals(
                JobStatus.KILLED,
                settled.status,
                "取消后台 bash 必须按 killed 结算，不能因为 destroyForcibly 后管道读抛 IOException 变 failed",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun bashRunInBackgroundWithoutRuntimeFails() = runBlocking {
        val result = BashTool().execute(
            buildJsonObject {
                put("command", "echo hi")
                put("run_in_background", true)
            },
            ToolContext(workspace()),
        )
        assertTrue(result.isError)
        assertEquals("background jobs unavailable: load the job runtime in this composition", result.text)
    }

    @Test
    fun emptyJobIdIsRejected() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val ctx = ToolContext(workspace(), jobs = JobRegistry(scope), jobOwner = { "s1" })
            assertEquals(
                "invalid job_id: expected a non-empty string, got \"\"",
                JobOutputTool().execute(arg("""{"job_id":""}"""), ctx).text,
            )
            assertEquals(
                "unknown job bash-0000000000000000",
                JobOutputTool().execute(arg("""{"job_id":"bash-0000000000000000"}"""), ctx).text,
            )
        } finally {
            scope.cancel()
        }
    }
}
