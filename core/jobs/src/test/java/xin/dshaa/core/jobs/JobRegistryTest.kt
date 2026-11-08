package xin.dshaa.core.jobs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 后台任务运行时（`job_*` 三件套的底座）的契约锁定：
 *
 * - 消费式读取：读走的输出不再返回；空读取渲染 `(no new output)` 由工具层负责；
 * - 环形缓冲：超过保留上限丢头并标 lossy（附上游同款 dropped-output 提示）；
 * - 取消：`kill` 立刻返回 requested、任务先 stopping 再 killed，且不再发完成通知；
 * - 等待：`wait` 超时返回 running 不杀任务；结算时有活等待者 = awaited，不再发通知；
 * - 完成通知：内联输出 / 让模型自己 job_output 两种文案，逐字对齐上游 `dsh-tool-jobs`。
 */
class JobRegistryTest {

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun terminal(registry: JobRegistry, id: String, owner: String = "s1"): JobSnapshot = runBlocking {
        var snapshot = registry.get(id, owner)
        repeat(200) {
            if (snapshot.status.terminal) return@runBlocking snapshot
            delay(10)
            snapshot = registry.get(id, owner)
        }
        snapshot
    }

    /** 状态先置终态、通知随后入队（同一个结算协程），等通知真正入队（不取走）。 */
    private fun awaitPending(registry: JobRegistry): Unit = runBlocking {
        repeat(200) {
            if (registry.pendingNoticeCount() > 0) return@runBlocking
            delay(10)
        }
        throw AssertionError("no pending completion notice")
    }

    /** 状态先置终态、通知随后入队（同一个结算协程），等通知真正可读。 */
    private fun awaitNotice(registry: JobRegistry, owner: String = "s1"): JobNotice = runBlocking {
        repeat(200) {
            val notices = registry.takeNotices(owner)
            if (notices.isNotEmpty()) return@runBlocking notices.single()
            delay(10)
        }
        throw AssertionError("no completion notice for $owner")
    }

    @Test
    fun readsAreConsumingAndRenderStdoutThenStderr() = runBlocking {
        val scope = scope()
        try {
            val registry = JobRegistry(scope, inlineOutputBytes = 0)
            val started = registry.start("bash", "printf hi", "s1") { control ->
                control.stdout("hello\n")
                control.stderr("oops\n")
                JobOutcome(JobStatus.COMPLETED, "exit code: 0")
            }
            assertTrue(started.id.matches(Regex("bash-[0-9a-f]{16}")), started.id)
            assertEquals(JobStatus.COMPLETED, terminal(registry, started.id).status)

            val first = registry.read(started.id, "s1")
            assertEquals("hello\n[stderr]\noops\n", JobRender.renderModelDelta(first.chunks, first.lossy))
            assertEquals("exit code: 0", first.job.detail)
            val second = registry.read(started.id, "s1")
            assertTrue(second.chunks.isEmpty(), "第二次读取必须为空（消费式）")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun ringDropsHeadAndFlagsLossy() = runBlocking {
        val scope = scope()
        try {
            val registry = JobRegistry(scope, retentionBytes = 8, inlineOutputBytes = 0)
            val started = registry.start("bash", "seq", "s1") { control ->
                control.stdout("0123456789")
                JobOutcome(JobStatus.COMPLETED, "exit code: 0")
            }
            terminal(registry, started.id)
            val read = registry.read(started.id, "s1")
            assertTrue(read.lossy)
            assertEquals("23456789", JobRender.renderModelDelta(read.chunks, read.lossy).substringBefore("\n["))
            assertTrue(JobRender.renderModelDelta(read.chunks, read.lossy).contains("[some output was dropped from memory; full output: (unavailable)]"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun killMarksStoppingThenKilledAndSuppressesNotice() = runBlocking {
        val scope = scope()
        try {
            val registry = JobRegistry(scope)
            val release = CompletableDeferred<Unit>()
            val started = registry.start("bash", "sleep 100", "s1") { control ->
                control.onCancel { release.complete(Unit) }
                release.await()
                JobOutcome(JobStatus.KILLED, "killed before exit")
            }
            val killed = registry.kill(started.id, "s1", "不再需要")
            assertTrue(killed.requested)
            assertEquals(JobStatus.STOPPING, killed.job.status)

            val settled = terminal(registry, started.id)
            assertEquals(JobStatus.KILLED, settled.status)
            assertEquals("killed before exit", settled.detail)
            assertFalse(registry.kill(started.id, "s1", null).requested, "终态任务再 kill 必须是 already-finished")
            assertTrue(registry.pendingNoticeCount() == 0, "模型自己 kill 掉的任务不发完成通知")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun waitTimeoutLeavesJobRunningAndSettlementIsAwaited() = runBlocking {
        val scope = scope()
        try {
            val registry = JobRegistry(scope)
            val release = CompletableDeferred<Unit>()
            val started = registry.start("bash", "sleep", "s1") { control ->
                control.stdout("partial\n")
                release.await()
                JobOutcome(JobStatus.COMPLETED, "exit code: 0")
            }
            val timedOut = registry.wait(started.id, 80, "s1")
            assertEquals(JobStatus.RUNNING, timedOut.status, "超时等待不能杀任务")

            val waiter = async(Dispatchers.Default) { registry.wait(started.id, 2_000, "s1") }
            delay(50)
            release.complete(Unit)
            val settled = waiter.await()
            assertEquals(JobStatus.COMPLETED, settled.status)
            assertEquals(0, registry.pendingNoticeCount(), "结算被活等待收走 = awaited，不再发通知")
            assertEquals("partial\n", JobRender.renderModelDelta(registry.read(started.id, "s1").chunks, false))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun completionNoticeInlinesShortOutputAndHeldTextIsReturnedOnRead() = runBlocking {
        val scope = scope()
        try {
            val registry = JobRegistry(scope)
            val started = registry.start("bash", "echo hi", "s1") { control ->
                control.stdout("hello\n")
                JobOutcome(JobStatus.COMPLETED, "exit code: 0")
            }
            terminal(registry, started.id)
            awaitPending(registry)

            // 通知内联了输出（消费掉游标）；模型若改用 job_output 收，held 输出要补回来
            val read = registry.read(started.id, "s1")
            assertEquals("hello\n", read.heldText)
            assertTrue(read.chunks.isEmpty())
            assertEquals(0, registry.pendingNoticeCount(), "job_output 读取时撤回 pending 通知")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun longOutputNoticePointsAtJobOutput() = runBlocking {
        val scope = scope()
        try {
            val registry = JobRegistry(scope, inlineOutputBytes = 16)
            val started = registry.start("bash", "big", "s1") { control ->
                control.stdout("0123456789abcdef0123456789abcdef")
                JobOutcome(JobStatus.COMPLETED, "exit code: 1")
            }
            terminal(registry, started.id)
            val notice = awaitNotice(registry)
            assertEquals(
                "background job ${started.id} (bash: big) finished [status: completed, exit code: 1]. Read its output with job_output.",
                notice.text,
            )
            assertEquals("bash big [status: completed, exit code: 1]", notice.summary)
            assertTrue(registry.takeNotices("s1").isEmpty(), "通知只能取走一次")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun inlineNoticeCarriesOutputAndIsBoundedByLabelLine() = runBlocking {
        val scope = scope()
        try {
            val registry = JobRegistry(scope)
            val started = registry.start("bash", "echo hi\nsecond line", "s1") { control ->
                control.stdout("done\n")
                JobOutcome(JobStatus.COMPLETED, "exit code: 0")
            }
            terminal(registry, started.id)
            val notice = awaitNotice(registry)
            assertEquals(
                "background job ${started.id} (bash: echo hi…) finished [status: completed, exit code: 0]. Output:\ndone",
                notice.text,
            )
            assertEquals("done\n", notice.heldText)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun ownerIsolationAndUnknownJob() = runBlocking {
        val scope = scope()
        try {
            val registry = JobRegistry(scope)
            val started = registry.start("bash", "true", "s1") { JobOutcome(JobStatus.COMPLETED, "exit code: 0") }
            terminal(registry, started.id)
            assertTrue(registry.list("s2").isEmpty())
            assertFailsWith<IllegalArgumentException> { registry.read(started.id, "s2") }
            assertFailsWith<IllegalArgumentException> { registry.read("bash-0000000000000000", "s1") }
            assertEquals("unknown job bash-0000000000000000", assertFailsWith<JobException> { registry.read("bash-0000000000000000", "s1") }.message)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failureSettlesWithFailedDetail() = runBlocking {
        val scope = scope()
        try {
            val registry = JobRegistry(scope)
            val started = registry.start("bash", "bad", "s1") {
                throw IllegalStateException("spawn failed: boom")
            }
            val settled = terminal(registry, started.id)
            assertEquals(JobStatus.FAILED, settled.status)
            assertEquals("spawn failed: boom", settled.detail)
            assertEquals("[status: failed, spawn failed: boom]", JobRender.statusLine(settled))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun renderMatchesUpstreamShapes() {
        val completed = JobSnapshot("bash-1", "bash", "ls -1", JobStatus.COMPLETED, "exit code: 0", 1)
        assertEquals("[status: completed, exit code: 0]", JobRender.statusLine(completed))
        assertEquals(" (bash: ls -1) finished [status: completed, exit code: 0]", JobRender.noticeDetail(completed))

        val running = JobSnapshot("bash-2", "bash", "sleep 1", JobStatus.RUNNING, null, 1)
        assertEquals("[status: running]", JobRender.statusLine(running))

        assertEquals(
            "a\nb\n[stderr]\nerr",
            JobRender.renderModelDelta(listOf(JobChunk("stdout", "a\nb"), JobChunk("stderr", "err")), lossy = false),
        )
        assertEquals(
            "out\n[stderr]\nerr\n",
            JobRender.renderModelDelta(listOf(JobChunk("stdout", "out"), JobChunk("stderr", "err\n")), lossy = false),
        )
        assertEquals("", JobRender.renderModelDelta(listOf(JobChunk("log", "hidden")), lossy = false))

        val long = "x".repeat(200)
        assertEquals(120, JobRender.bound(long).length)
        assertTrue(JobRender.bound(long).endsWith("…"))
        assertEquals(120, JobRender.completionSummary(JobSnapshot("bash-3", "bash", long, JobStatus.COMPLETED, "exit code: 0", 1)).length)
    }

    @Test
    fun teardownKillsRunningJobsWithoutNotice() = runBlocking {
        val scope = scope()
        try {
            val registry = JobRegistry(scope)
            val started = registry.start("bash", "sleep", "s1") { control ->
                control.onCancel { }
                CompletableDeferred<Unit>().await()
                JobOutcome(JobStatus.COMPLETED, "exit code: 0")
            }
            delay(30)
            registry.shutdown()
            val snapshot = terminal(registry, started.id)
            assertEquals(JobStatus.KILLED, snapshot.status)
            assertEquals(0, registry.pendingNoticeCount())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun resultIsCarriedOnlyOnceAfterSettlement() = runBlocking {
        val scope = scope()
        try {
            val registry = JobRegistry(scope, inlineOutputBytes = 0)
            val started = registry.start("subagent", "delegate", "s1") { control ->
                control.log("hidden narration")
                control.stdout("working")
                control.result("final answer")
                JobOutcome(JobStatus.COMPLETED)
            }
            terminal(registry, started.id)
            val first = registry.read(started.id, "s1")
            assertNotNull(first.result)
            assertEquals("final answer", first.result)
            assertEquals("working", JobRender.renderModelDelta(first.chunks, first.lossy))
            assertTrue(registry.read(started.id, "s1").result == null, "result 只能被读走一次")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun listAllSpansOwnersAndPreviewDoesNotConsumeCursor() = runBlocking {
        val scope = scope()
        try {
            val registry = JobRegistry(scope, inlineOutputBytes = 0)
            val first = registry.start("bash", "echo one", "s1") { control ->
                control.stdout("one\n")
                JobOutcome(JobStatus.COMPLETED, "exit code: 0")
            }
            val second = registry.start("bash", "echo two", "s2") { control ->
                control.stdout("two\n")
                JobOutcome(JobStatus.COMPLETED, "exit code: 0")
            }
            terminal(registry, first.id, "s1")
            terminal(registry, second.id, "s2")

            val entries = registry.listAll().associateBy { it.job.id }
            assertEquals(setOf("s1", "s2"), entries.values.map { it.owner }.toSet())
            assertEquals("one\n", entries.getValue(first.id).preview)
            assertEquals("two\n", entries.getValue(second.id).preview)

            // 面板先看过，模型再 job_output 仍然拿到全量（UI peek 不能推进 cursor）
            val read = registry.read(first.id, "s1")
            assertEquals("one\n", JobRender.renderModelDelta(read.chunks, read.lossy))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun peekCapsPreviewAndReportsTruncationWithoutConsuming() = runBlocking {
        val scope = scope()
        try {
            val registry = JobRegistry(scope, inlineOutputBytes = 0)
            val started = registry.start("bash", "seq", "s1") { control ->
                control.stdout("0123456789")
                JobOutcome(JobStatus.COMPLETED)
            }
            terminal(registry, started.id)

            val small = registry.listAll(previewChars = 4).single()
            assertEquals("6789", small.preview)
            assertTrue(small.previewTruncated, "截断必须标记")
            assertEquals(10, small.unreadBytes)

            val full = registry.peek(started.id, "s1")
            assertEquals("0123456789", full.preview)
            assertFalse(full.previewTruncated)
            assertEquals(10, full.unreadBytes, "peek 不能消费模型游标")

            assertEquals(
                "0123456789",
                JobRender.renderModelDelta(registry.read(started.id, "s1").chunks, false),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun onChangeFiresForStartKillAndSettle() = runBlocking {
        val scope = scope()
        try {
            val registry = JobRegistry(scope)
            val changes = java.util.concurrent.atomic.AtomicInteger()
            registry.onChange = { changes.incrementAndGet() }
            val release = CompletableDeferred<Unit>()
            val started = registry.start("bash", "sleep", "s1") { control ->
                control.onCancel { release.complete(Unit) }
                release.await()
                JobOutcome(JobStatus.KILLED, "killed before exit")
            }
            assertTrue(changes.get() >= 1, "start 必须通知面板")
            registry.kill(started.id, "s1", "stop")
            terminal(registry, started.id)

            var seen = changes.get()
            repeat(100) {
                if (seen < 3) {
                    delay(10)
                    seen = changes.get()
                }
            }
            assertTrue(seen >= 3, "start / kill / settle 都要触发 onChange，实际 $seen")
        } finally {
            scope.cancel()
        }
    }
}
