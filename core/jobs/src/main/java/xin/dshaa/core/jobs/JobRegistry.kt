package xin.dshaa.core.jobs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * 后台任务运行时（对齐上游 `dsh-jobs` + `dsh-jobs-local` + `dsh-tool-jobs`）。
 *
 * 为什么要自己写一层：
 * - `job_list` / `job_output` / `job_kill` 三个工具需要一张「进程内任务表」：
 *   id、kind、label、状态、环形输出、消费游标、等待/取消语义、完成通知；
 * - `bash` 的 `run_in_background` 要把进程登记成 job，模型只拿到 job id；
 * - App 侧还要把「任务完成」作为一条 `source.kind:"tool-jobs"` 的 user 消息注入回会话
 *   （引擎在 step 边界收，idle 时由宿主唤醒一轮）。
 *
 * 这里只做纯 JVM：不依赖 Android/Compose，可被 `core:tools` 与单测直接用。
 * 对外文案（`[status: ...]`、完成通知、label 截断）逐字对齐上游 `dsh-tool-jobs`。
 */
enum class JobStatus(val wire: String) {
    RUNNING("running"),
    STOPPING("stopping"),
    COMPLETED("completed"),
    KILLED("killed"),
    FAILED("failed"),
    ;

    val terminal: Boolean get() = this == COMPLETED || this == KILLED || this == FAILED
}

/** job 的公开投影（上游 `PublicJobSnapshot`：不含 owner/offset/limit）。 */
data class JobSnapshot(
    val id: String,
    val kind: String,
    val label: String,
    val status: JobStatus,
    val detail: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
    /** 模型可见输出的总字节数（决定完成通知能不能内联）。 */
    val totalBytes: Long = 0,
)

/** 一段输出：`stdout` / `stderr` / `log`（log 只给观察者，不进模型）。 */
data class JobChunk(val channel: String, val text: String, val gapBefore: Boolean = false)

/**
 * 任务面板的一行（M20）：公开快照 + owner + **只读**输出预览。
 *
 * 三个约束：
 * - [preview] 是给 UI 看的，**不推进模型游标**（`job_output` 读走的仍是全量）；
 * - 不撤回待投递的完成通知（模型没读到的那段 held 输出不会被面板吞掉）；
 * - 只保留末尾 [previewChars] 个字符，避免大任务每帧复制 1 MiB。
 *
 * [unreadBytes] = 模型可见输出总量 - 模型已消费游标，界面可显示「还有多少没读」。
 */
data class JobEntry(
    val job: JobSnapshot,
    val owner: String?,
    val preview: String,
    val previewTruncated: Boolean,
    val unreadBytes: Long,
)

/** 一次消费式读取（上游 `JobOutputRead`）：读走多少就推进多少游标。 */
data class JobRead(
    val chunks: List<JobChunk>,
    val lossy: Boolean,
    val result: String?,
    val job: JobSnapshot,
    /** 已排队完成通知里扣下的那段输出（上游 `notice.held`）；送达后为 null。 */
    val heldText: String? = null,
)

/** 一条待投递的完成通知：正文 + `source.summary`（UI/落库用）。 */
data class JobNotice(
    val jobId: String,
    val owner: String?,
    val text: String,
    val summary: String,
    /** 内联通知已经消费掉的那段输出；模型改用 job_output 收走时补回去。 */
    val heldText: String? = null,
)

/** 结算事件：owner 用它决定要不要投递通知（根会话 / 子代理各自的收件箱）。 */
data class JobSettlement(
    val job: JobSnapshot,
    val awaited: Boolean,
    /** `normal` / `killed` / `teardown`。 */
    val cause: String,
    /** 任务 owner（会话 id / 子代理 id）；null = 无 owner，不需要唤醒。 */
    val owner: String? = null,
)

/** 生产者返回的终态（bash：completed + exit code；被 kill：killed）。 */
data class JobOutcome(val status: JobStatus, val detail: String? = null)

/** 一次结算的结果：`settledNow=false` 表示调用时任务已终态（不要重复发通知/事件）。 */
private data class SettleOutcome(val settledNow: Boolean, val awaited: Boolean)

/** 后台任务运行时错误（文案与上游 `dsh-jobs-local` 一致）。 */
class JobException(message: String) : IllegalArgumentException(message)

/**
 * 生产者侧的控制面：写输出、登记取消钩子、上报最终结果。
 * 只在 `start { control -> ... }` 的协程里用。
 */
interface JobControl {
    fun stdout(text: String)

    fun stderr(text: String)

    /** 只给观察者看的过程日志，模型读不到。 */
    fun log(text: String)

    /** final-output 型任务的最终结果（subagent 用；bash 不用）。 */
    fun result(text: String)

    /** 取消钩子：job_kill / 进程退出时调用（bash 在这里 destroyForcibly）。 */
    fun onCancel(block: (reason: String?) -> Unit)

    /** 已经请求过取消（生产者可以在 spawn 前检查）。 */
    fun isCancelled(): Boolean
}

/**
 * 进程内任务注册表。一个 App 一份；owner = 当前会话 id（对齐上游 agent owner）。
 *
 * 线程模型：表/单个 job 的字段都是同步块保护；生产者协程跑在注入的 [scope] 上。
 */
class JobRegistry(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: (String) -> String = { kind ->
        "$kind-" + UUID.randomUUID().toString().replace("-", "").take(16)
    },
    /** 每个 job 保留的环形输出上限（默认 1 MiB，超出丢头并标 lossy）。 */
    private val retentionBytes: Long = 1_048_576,
    /** 完成通知内联输出的上限（上游 `inlineOutputBytes` 默认 2048）。 */
    private val inlineOutputBytes: Long = 2_048,
) {
    private val jobs = LinkedHashMap<String, ManagedJob>()
    private val pendingNotices = LinkedHashMap<String, JobNotice>()

    /** 结算回调（App 用它唤醒/注入完成通知）。回调在生产者线程上同步执行，实现方自己切线程。 */
    @Volatile
    var settlementListener: ((JobSettlement) -> Unit)? = null

    /**
     * 任务表变化回调（M20）：start / kill / settle 时触发，宿主用它刷「任务」面板。
     * 在锁外调用；实现方自己切线程（Compose 状态不要在生产者线程直接写）。
     */
    @Volatile
    var onChange: (() -> Unit)? = null

    /** 登记并启动一个后台任务；返回登记瞬间的快照（模型拿 id 用）。 */
    fun start(
        kind: String,
        label: String,
        owner: String?,
        run: suspend (JobControl) -> JobOutcome,
    ): JobSnapshot {
        require(kind.isNotEmpty()) { "invalid job kind: expected a non-empty string" }
        require(label.isNotEmpty()) { "invalid job label: expected a non-empty string" }
        val job = ManagedJob(
            id = idGenerator(kind),
            kind = kind,
            label = label,
            owner = owner,
            startedAt = clock(),
            retentionBytes = retentionBytes,
        )
        synchronized(this) { jobs[job.id] = job }
        notifyChanged()
        job.coroutine = scope.launch {
            val outcome = try {
                run(controlFor(job))
            } catch (e: CancellationException) {
                JobOutcome(
                    status = JobStatus.KILLED,
                    detail = if (job.killRequested) "killed before exit" else "cancelled",
                )
            } catch (e: Throwable) {
                JobOutcome(status = JobStatus.FAILED, detail = e.message ?: e.javaClass.simpleName)
            }
            settle(job, outcome)
        }
        return job.snapshot()
    }

    /** 一次消费式读取；如果这条 job 的通知还在队列里，顺手撤回并把 held 输出带回。 */
    fun read(jobId: String, owner: String?): JobRead {
        val job = find(jobId, owner)
        val read = job.read()
        val notice = synchronized(this) { pendingNotices.remove(jobId) }
        return read.copy(heldText = notice?.heldText)
    }

    fun list(owner: String?): List<JobSnapshot> {
        val selected = synchronized(this) { jobs.values.filter { it.owner == owner } }
        return selected.map { it.snapshot() }
    }

    /**
     * 全表视图（M20 任务面板）：跨 owner、按登记顺序；每条带只读输出预览。
     * [previewChars] 是单条预览的字符上限（默认 600，够卡片显示 3–4 行）。
     */
    fun listAll(previewChars: Int = 600): List<JobEntry> {
        val selected = synchronized(this) { jobs.values.toList() }
        return selected.map { it.toEntry(previewChars) }
    }

    /** 单条任务的面板视图；[previewChars] 可以给大（详情弹层），仍然不消费游标。 */
    fun peek(jobId: String, owner: String?, previewChars: Int = 20_000): JobEntry =
        find(jobId, owner).toEntry(previewChars)

    /** 按 id 取快照（owner 必须匹配）。 */
    fun get(jobId: String, owner: String?): JobSnapshot = find(jobId, owner).snapshot()

    /** 取消：返回 true = 已请求（job 可能还在 stopping），false = 早已终态。 */
    fun kill(jobId: String, owner: String?, reason: String?): KillResult {
        val job = find(jobId, owner)
        val requested = job.requestKill(reason)
        if (requested) notifyChanged()
        return KillResult(requested, job.snapshot())
    }

    /** 等待结算；超时返回 running/stopping 快照（不取消任务）。 */
    suspend fun wait(jobId: String, timeoutMs: Long, owner: String?): JobSnapshot {
        val job = find(jobId, owner)
        val registered = job.beginWait()
        if (!registered) return job.snapshot()
        try {
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1)) { job.settled.await() }
        } finally {
            job.endWait()
        }
        return job.snapshot()
    }

    /**
     * 取走发给某个 owner 的完成通知（引擎在 step 边界 / 宿主在 idle 时调用）。
     * owner=null 时匹配「没有 owner」的任务。
     */
    fun takeNotices(owner: String?): List<JobNotice> = synchronized(this) {
        val matched = pendingNotices.values.filter { it.owner == owner }
        matched.forEach { pendingNotices.remove(it.jobId) }
        matched
    }

    /** 还有多少条待投递通知。 */
    fun pendingNoticeCount(): Int = synchronized(this) { pendingNotices.size }

    /** 进程退出/切 App 时收尾：所有还在跑的任务按 killed 结算，不再发通知。 */
    fun shutdown() {
        val running = synchronized(this) {
            jobs.values.filter { !it.status.terminal }.onEach { it.teardown = true }
        }
        running.forEach { job ->
            job.requestKill("shutdown")
            job.coroutine?.cancel()
            settle(job, JobOutcome(JobStatus.KILLED, "cancelled"))
        }
        synchronized(this) { pendingNotices.clear() }
    }

    private fun find(jobId: String, owner: String?): ManagedJob {
        val job = synchronized(this) {
            val found = jobs[jobId] ?: throw JobException("unknown job $jobId")
            if (found.owner != owner) throw JobException("job ${found.id} belongs to another session")
            found
        }
        return job
    }

    private fun controlFor(job: ManagedJob): JobControl = object : JobControl {
        override fun stdout(text: String) = job.append("stdout", text)
        override fun stderr(text: String) = job.append("stderr", text)
        override fun log(text: String) = job.append("log", text)
        override fun result(text: String) {
            job.resultText = text
        }

        override fun onCancel(block: (reason: String?) -> Unit) = job.setCancelHook(block)
        override fun isCancelled(): Boolean = job.killRequested
    }

    private fun settle(job: ManagedJob, outcome: JobOutcome) {
        val result = job.settle(outcome.status, outcome.detail, clock())
        if (!result.settledNow) return
        val snapshot = job.snapshot()
        val teardown = job.teardown
        if (!result.awaited && !job.killRequested && !teardown && job.owner != null) {
            val notice = buildNotice(job)
            if (notice != null) synchronized(this) { pendingNotices[job.id] = notice }
        }
        val cause = when {
            teardown -> "teardown"
            job.killRequested -> "killed"
            else -> "normal"
        }
        runCatching { settlementListener?.invoke(JobSettlement(snapshot, result.awaited, cause, job.owner)) }
        // 面板先看到终态，再让宿主决定要不要唤醒父代理（与 AgentRegistry.settle 同序）
        notifyChanged()
    }

    /** 表变化通知；在锁外调用，异常不能影响任务本身。 */
    private fun notifyChanged() {
        runCatching { onChange?.invoke() }
    }

    /** 内联完成通知（输出能塞进 2048 字节）或让模型自己 job_output 的短通知。 */
    private fun buildNotice(job: ManagedJob): JobNotice? {
        val snapshot = job.snapshot()
        if (snapshot.status == JobStatus.RUNNING || snapshot.status == JobStatus.STOPPING) return null
        val detail = JobRender.noticeDetail(snapshot)
        val head = "background job ${snapshot.id}$detail"
        val inline = inlineOutputBytes > 0 && snapshot.totalBytes <= inlineOutputBytes
        val summary = JobRender.completionSummary(snapshot)
        if (!inline) {
            return JobNotice(
                jobId = snapshot.id,
                owner = job.owner,
                text = "$head. Read its output with job_output.",
                summary = summary,
            )
        }
        val read = job.read()
        val output = JobRender.renderModelDelta(read.chunks, read.lossy)
        // final-output 型任务（workflow）：结果文本随完成通知一起交付；job_output 之后不会再拿到它
        val body = buildString {
            if (output.isNotEmpty()) append(output)
            val result = read.result
            if (!result.isNullOrEmpty()) {
                if (isNotEmpty() && !endsWith("\n")) append('\n')
                append(result)
            }
        }
        val text = if (body.isEmpty()) {
            "$head. No new output."
        } else {
            "$head. Output:\n${body.removeSuffix("\n")}"
        }
        return JobNotice(
            jobId = snapshot.id,
            owner = job.owner,
            text = text,
            summary = summary,
            heldText = body,
        )
    }
}

data class KillResult(val requested: Boolean, val job: JobSnapshot)

/** 模型侧文本渲染：与上游 `dsh-tool-jobs/render` 逐字一致。 */
object JobRender {
    /** `[status: running]` / `[status: completed, exit code: 0]`。 */
    fun statusLine(snapshot: JobSnapshot): String =
        snapshot.detail?.let { "[status: ${snapshot.status.wire}, $it]" }
            ?: "[status: ${snapshot.status.wire}]"

    /**
     * 一次读取的正文：stdout（含非 stderr）按序拼，stderr 统一放在最后一个 `[stderr]` 段，
     * 丢字节时附上游同款 dropped-output 提示。
     */
    fun renderModelDelta(chunks: List<JobChunk>, lossy: Boolean, spillPaths: List<String> = emptyList()): String {
        val visible = chunks.filter { it.channel != "log" }
        val out = visible.filter { it.channel != "stderr" }.joinToString("") { it.text }
        val err = visible.filter { it.channel == "stderr" }.joinToString("") { it.text }
        val separator = if (out.isNotEmpty() && !out.endsWith("\n")) "\n" else ""
        val body = out + if (err.isNotEmpty()) "$separator[stderr]\n$err" else ""
        if (!lossy && visible.none { it.gapBefore }) return body
        val notice = "[some output was dropped from memory; full output: " +
            (if (spillPaths.isEmpty()) "(unavailable)" else spillPaths.joinToString(", ")) + "]"
        val joiner = if (body.isNotEmpty() && !body.endsWith("\n")) "\n" else ""
        return "$body$joiner$notice"
    }

    /** 通知里的 ` (kind: label) finished [status: …]`（label 取第一行并做 120 字截断）。 */
    fun noticeDetail(snapshot: JobSnapshot): String {
        val text = snapshot.label.trimStart()
        val newline = text.indexOf('\n')
        val label = bound(if (newline == -1) text else "${text.take(newline)}…")
        return " (${snapshot.kind}: $label) finished ${statusLine(snapshot)}"
    }

    /** 通知的单行摘要（`source.summary`）。 */
    fun completionSummary(snapshot: JobSnapshot): String =
        bound("${snapshot.kind} ${snapshot.label} ${statusLine(snapshot)}")

    /** 上游 `boundContextSummary`：120 个 UTF-16 单元，超长取前 119 + `…`。 */
    fun bound(text: String): String = if (text.length <= 120) text else text.take(119) + "…"
}

/**
 * 一个任务的内部状态：环形输出 + 消费游标 + 等待者/取消标记。
 * 所有字段都在 [lock] 上同步（@Volatile 只用于跨线程读的简单标记）。
 */
private class ManagedJob(
    val id: String,
    val kind: String,
    val label: String,
    val owner: String?,
    val startedAt: Long,
    private val retentionBytes: Long,
) {
    val lock = Any()
    val settled = CompletableDeferred<Unit>()
    var coroutine: CoroutineJob? = null

    var status = JobStatus.RUNNING
        private set
    var detail: String? = null
        private set
    var finishedAt: Long? = null
        private set

    @Volatile
    var killRequested = false
        private set

    @Volatile
    var teardown = false

    private var killReason: String? = null
    private var cancelHook: ((String?) -> Unit)? = null
    private var waiters = 0
    private var awaited = false

    private val chunks = ArrayDeque<StoredChunk>()
    private var retained = 0L
    private var total = 0L
    private var cursor = 0L
    private var resultConsumed = false

    @Volatile
    var resultText: String? = null

    fun snapshot(): JobSnapshot = synchronized(lock) {
        JobSnapshot(
            id = id,
            kind = kind,
            label = label,
            status = status,
            detail = detail,
            startedAt = startedAt,
            finishedAt = finishedAt,
            totalBytes = total,
        )
    }

    /**
     * 面板视图：从**末尾**回取可见输出，绝不改 [cursor]（模型游标）也不动 result 消费标记。
     * 大任务只复制末尾 [previewChars] 个字符，轮询不会把整段 1 MiB 反复搬来搬去。
     */
    fun toEntry(previewChars: Int): JobEntry = synchronized(lock) {
        val visible = chunks.filter { it.channel != "log" }
        val totalChars = visible.sumOf { it.text.length }
        val limit = previewChars.coerceAtLeast(0)
        val parts = ArrayDeque<String>()
        var remaining = limit
        for (i in visible.indices.reversed()) {
            if (remaining <= 0) break
            val text = visible[i].text
            if (text.length <= remaining) {
                parts.addFirst(text)
                remaining -= text.length
            } else {
                parts.addFirst(text.takeLast(remaining))
                remaining = 0
            }
        }
        JobEntry(
            job = JobSnapshot(id, kind, label, status, detail, startedAt, finishedAt, total),
            owner = owner,
            preview = parts.joinToString(""),
            previewTruncated = totalChars > limit || visible.any { it.gapBefore },
            unreadBytes = total - cursor,
        )
    }

    fun append(channel: String, text: String) {
        if (text.isEmpty()) return
        synchronized(lock) {
            val bytes = text.toByteArray(Charsets.UTF_8).size.toLong()
            chunks.addLast(StoredChunk(channel, text, total, total + bytes))
            total += bytes
            retained += bytes
            trimLocked()
        }
    }

    fun read(): JobRead = synchronized(lock) {
        val earliest = total - retained
        val lossy = cursor < earliest
        if (cursor < earliest) cursor = earliest
        val from = cursor
        val out = chunks.filter { it.end > from }.map { chunk ->
            if (chunk.start < from) chunk.tailFrom(from) else JobChunk(chunk.channel, chunk.text, chunk.gapBefore)
        }
        cursor = total
        val result = if (status.terminal && resultText != null && !resultConsumed) {
            resultConsumed = true
            resultText
        } else {
            null
        }
        JobRead(
            chunks = out,
            lossy = lossy,
            result = result,
            job = JobSnapshot(id, kind, label, status, detail, startedAt, finishedAt, total),
        )
    }

    fun settle(status: JobStatus, detail: String?, now: Long): SettleOutcome = synchronized(lock) {
        if (this.status.terminal) return SettleOutcome(settledNow = false, awaited = false)
        this.status = status
        this.detail = detail
        this.finishedAt = now
        this.awaited = waiters > 0
        val resultAwaited = this.awaited
        settled.complete(Unit)
        SettleOutcome(settledNow = true, awaited = resultAwaited)
    }

    /** 登记等待者；返回 false = 已经终态（调用方直接返回快照，不用等）。 */
    fun beginWait(): Boolean = synchronized(lock) {
        if (status.terminal) return false
        waiters++
        true
    }

    fun endWait() {
        synchronized(lock) { if (waiters > 0) waiters-- }
    }

    /** 请求取消；返回 false = 已经终态。钩子在锁外调用（避免生产者回调重入死锁）。 */
    fun requestKill(reason: String?): Boolean {
        var hook: ((String?) -> Unit)? = null
        synchronized(lock) {
            if (status.terminal) return false
            if (status == JobStatus.RUNNING) {
                status = JobStatus.STOPPING
                killRequested = true
                killReason = reason
            }
            hook = cancelHook
        }
        hook?.invoke(reason)
        return true
    }

    fun setCancelHook(block: (String?) -> Unit) {
        val fire: Boolean
        val reason: String?
        synchronized(lock) {
            cancelHook = block
            fire = killRequested
            reason = killReason
        }
        if (fire) block(reason)
    }

    private fun trimLocked() {
        while (retained > retentionBytes && chunks.size > 1) {
            val dropped = chunks.removeFirst()
            retained -= dropped.end - dropped.start
        }
        if (retained > retentionBytes && chunks.size == 1) {
            val chunk = chunks.first()
            val tail = utf8Tail(chunk.text, retentionBytes)
            val tailBytes = tail.toByteArray(Charsets.UTF_8).size.toLong()
            chunk.text = tail
            chunk.start = chunk.end - tailBytes
            chunk.gapBefore = true
            retained = tailBytes
        }
    }

    /** 从 UTF-8 字节尾部取安全子串（不切多字节字符）。 */
    private fun utf8Tail(text: String, maxBytes: Long): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text
        var start = bytes.size - maxBytes.toInt()
        while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) start++
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }

    private class StoredChunk(
        val channel: String,
        var text: String,
        var start: Long,
        var end: Long,
        var gapBefore: Boolean = false,
    ) {
        fun tailFrom(from: Long): JobChunk {
            if (from <= start) return JobChunk(channel, text)
            val offsetChars = bytesToChars(from - start)
            return JobChunk(channel, text.substring(offsetChars.coerceAtMost(text.length)))
        }

        private fun bytesToChars(bytes: Long): Int {
            var remaining = bytes
            var chars = 0
            var i = 0
            while (i < text.length && remaining > 0) {
                val codePoint = text.codePointAt(i)
                val width = Character.charCount(codePoint)
                val byteLen = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
                if (remaining < byteLen) break
                remaining -= byteLen
                chars += width
                i += width
            }
            return chars
        }
    }
}
