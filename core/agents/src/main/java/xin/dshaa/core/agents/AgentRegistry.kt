package xin.dshaa.core.agents

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 进程内子代理运行时（对齐上游 `dsh-subagent` + `dsh-subagent-fork/spawn-in-process`
 * + `dsh-tool-subagent(-control)` 的模型侧语义）。
 *
 * 职责边界：
 * - 这里只做「代理表 + 生命周期 + 通知队列 + 授权/深度检查」，**不跑 LLM、不碰会话文件**；
 *   真正跑一轮的是 App 注入的 [run]（复用 `core:engine` 的 `TurnEngine`）。
 * - 通知队列按**收件人**分组：子代理结算 → 父代理；`send_message` → 目标代理。
 *   收件人正在跑时由引擎在 step 边界取；idle 时宿主回调 [onNotice] 唤醒一轮。
 *
 * 文案逐字对齐上游：
 * - 结算通知 `createSettlementMessage`（`Background subagent <id> …` / `Its closing message:` /
 *   `It left no closing message.` / `It sent you a message just before finishing, …`）；
 * - `send_message` 正文 = `Agent <senderId> sent a message: ` + 消息块；
 * - `source.summary` 上限 120（`boundContextSummary`）。
 */
enum class AgentStatus(val wire: String) {
    RUNNING("running"),
    INACTIVE("inactive"),
}

/** 子代理运行时错误（文案尽量与上游一致；工具层原样回给模型）。 */
class AgentException(message: String) : IllegalArgumentException(message)

/** 一个子代理的公开记录（`list_agents` 的来源）。 */
data class AgentRecord(
    val id: String,
    val parentId: String?,
    val label: String,
    val depth: Int,
    val cwd: String?,
    val continuable: Boolean = true,
    val createdAt: Long,
) {
    @Volatile
    var status: AgentStatus = AgentStatus.INACTIVE

    /** 收尾前给父代理发过消息：结算通知省略 closing message（上游 `omitClosingText`）。 */
    @Volatile
    var reportedToParent: Boolean = false

    /** 在协程登记前就收到的 interrupt，由启动方检查后直接按 aborted 结算。 */
    @Volatile
    var stopRequested: Boolean = false

    /** 协程体是否已经开始（还没开始就 cancel 会连结算都不跑）。 */
    @Volatile
    var started: Boolean = false

    /** 结算后的终态（面板显示「完成/已停止/失败」与收尾话；运行中为 null）。 */
    @Volatile
    var terminal: AgentTerminal? = null
}

/** 子代理最后一轮的终态（对齐上游 `SubagentResult` 里模型可见的部分）。 */
data class AgentTerminal(
    /** completed / aborted / max-tokens / refusal / error（其他值按 abnormal 渲染）。 */
    val stopReason: String,
    /** 最后一轮 assistant 的 text 块（空 = 没有收尾话）。 */
    val closingTexts: List<String> = emptyList(),
    val structured: String? = null,
    val diagnostic: String? = null,
)

/**
 * 一条待投递通知：`texts` 逐块写成 user/message 的 content（上游结算通知是多个 text 块）。
 * [kind] 即 v4 `source.kind`（`subagent-settled` / `agent-message`）。
 */
data class AgentNotice(
    val agentId: String,
    val owner: String?,
    val texts: List<String>,
    val summary: String,
    val kind: String,
    val form: String = "notice",
    val senderSessionId: String? = null,
) {
    val text: String get() = texts.joinToString("\n")
}

/** `send_message` 的投递回执。 */
data class AgentDelivery(val messageId: String, val agentId: String, val notice: AgentNotice)

/**
 * 一个 App 一份的代理表。线程模型：表/队列由一把锁保护；[run] 在注入的 [scope] 上跑，
 * [onNotice] 一定在锁外调用（宿主可能立刻回头调 [takeNotices]）。
 */
class AgentRegistry(
    private val scope: CoroutineScope,
    private val maxDepth: Int = 1,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newAgentId: () -> String = { UUID.randomUUID().toString() },
    private val newMessageId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private val agents = LinkedHashMap<String, AgentRecord>()
    private val jobs = HashMap<String, CoroutineJob>()
    private val notices = HashMap<String, ArrayDeque<AgentNotice>>()
    private var closing = false

    /** 某个收件人（根会话 id 或子代理 id）有新通知；锁外调用。 */
    var onNotice: ((String) -> Unit)? = null

    /**
     * 代理表变了（新增 / 结算）：宿主刷新「子代理」面板用。
     * 与 [onNotice] 一样锁外调用；结算时先通知面板，再投递父代理通知。
     */
    var onChange: (() -> Unit)? = null

    fun limit(): Int = maxDepth

    fun get(id: String): AgentRecord? = synchronized(lock) { agents[id] }

    /** 本次进程内启动过的全部代理（创建顺序；含已结算的，面板用）。 */
    fun all(): List<AgentRecord> = synchronized(lock) { agents.values.toList() }

    /**
     * 创建一个子代理并在 [scope] 上跑 [run]。立刻返回记录（工具只回 id）。
     * 深度 = 父深度 + 1，超过 [maxDepth] 直接抛（上游 `subagent depth N exceeds maxDepth M`）。
     */
    fun start(
        parentId: String?,
        parentDepth: Int,
        label: String,
        cwd: String?,
        /** 指定的代理 id（App 用子会话 id，保证 `subagentId == child session id`，与上游一致）。 */
        id: String? = null,
        run: suspend (AgentRecord) -> AgentTerminal,
    ): AgentRecord {
        val record = synchronized(lock) {
            if (closing) throw AgentException("subagent runtime is shutting down")
            val depth = parentDepth + 1
            if (depth > maxDepth) throw AgentException("subagent depth $depth exceeds maxDepth $maxDepth")
            val created = AgentRecord(
                id = id ?: newAgentId(),
                parentId = parentId,
                label = label,
                depth = depth,
                cwd = cwd,
                createdAt = clock(),
            )
            created.status = AgentStatus.RUNNING
            agents[created.id] = created
            created
        }
        val job = scope.launch {
            record.started = true
            if (record.stopRequested) {
                settle(record, AgentTerminal("aborted"))
                return@launch
            }
            val terminal = try {
                run(record)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { settle(record, AgentTerminal("aborted")) }
                throw e
            } catch (e: Throwable) {
                // 子代理失败只结算给父代理，不上抛：不能因为一个委派挂掉宿主 scope。
                settle(record, AgentTerminal("error", diagnostic = e.message ?: e.javaClass.simpleName))
                return@launch
            }
            settle(record, terminal)
        }
        synchronized(lock) { if (!job.isCompleted) jobs[record.id] = job }
        job.invokeOnCompletion { synchronized(lock) { if (jobs[record.id] === job) jobs.remove(record.id) } }
        onChange?.invoke()
        return record
    }

    /** 直接子代理（`list_agents` scope=children），按创建顺序。 */
    fun listChildren(parentId: String): List<AgentRecord> = synchronized(lock) {
        agents.values.filter { it.parentId == parentId }
    }

    /** 整棵子树（BFS，按深度/创建顺序），每条记录自带 depth/parent。 */
    fun listDescendants(parentId: String): List<AgentRecord> = synchronized(lock) {
        val out = ArrayList<AgentRecord>()
        val queue = ArrayDeque<String>().apply { add(parentId) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children = agents.values.filter { it.parentId == current }
            out += children
            children.forEach { queue.addLast(it.id) }
        }
        out
    }

    /**
     * 投递一条 `send_message`。只允许「直接子代理 ↔ 直接父代理」；
     * 子→父会记下 [AgentRecord.reportedToParent]（结算时省略 closing text）。
     */
    fun send(senderId: String, targetId: String, text: String): AgentDelivery {
        val notice = synchronized(lock) {
            // 根会话不是 AgentRecord，但它是直接父代理：允许「未知目标 = 发送者的 parentId」。
            val sender = agents[senderId]
            val target = agents[targetId]
            val senderIsParent = target != null && target.parentId == senderId
            val senderIsChild = sender != null && sender.parentId == targetId
            if (!senderIsParent && !senderIsChild) {
                throw AgentException("agent $targetId is not your direct child or parent")
            }
            if (sender != null && sender.parentId == targetId) sender.reportedToParent = true
            val built = AgentNotice(
                agentId = senderId,
                owner = targetId,
                texts = listOf("Agent $senderId sent a message: ", text),
                summary = "",
                kind = "agent-message",
                form = "relay",
                senderSessionId = senderId,
            )
            notices.getOrPut(targetId, ::ArrayDeque).addLast(built)
            built
        }
        onNotice?.invoke(targetId)
        return AgentDelivery(newMessageId(), targetId, notice)
    }

    /** 请求停止当前工作（不等待、不杀它自己启动的下级）。未知 id 返回 false。 */
    fun interrupt(id: String): Boolean {
        val job = synchronized(lock) {
            val record = agents[id] ?: return false
            record.stopRequested = true
            // 协程体还没跑：只置标记，由启动体自己按 aborted 结算（cancel 掉会连结算都不执行）
            if (!record.started) return true
            jobs[id]
        }
        job?.cancel()
        return true
    }

    /** 取走投递给 [ownerId] 的全部通知（引擎 step 边界 / 宿主唤醒共用）。 */
    fun takeNotices(ownerId: String?): List<AgentNotice> {
        if (ownerId == null) return emptyList()
        return synchronized(lock) {
            val queue = notices.remove(ownerId) ?: return emptyList()
            queue.toList()
        }
    }

    /** 收件队列里还有没有通知（宿主在唤醒轮收尾时补一次，避免「看到空 → 放标记」的竞争）。 */
    fun hasNotices(ownerId: String?): Boolean {
        if (ownerId == null) return false
        return synchronized(lock) { notices[ownerId]?.isNotEmpty() == true }
    }

    /** teardown：取消全部在跑的子代理，结算通知一律不再投递。 */
    fun shutdown() {
        val running = synchronized(lock) {
            closing = true
            jobs.values.toList()
        }
        running.forEach { it.cancel() }
    }

    /** 结算（只做一次）：标记 inactive，构造上游同款通知，入父代理的收件队列。 */
    private fun settle(record: AgentRecord, terminal: AgentTerminal) {
        var notifyOwner: String? = null
        val changed = synchronized(lock) {
            if (record.status == AgentStatus.INACTIVE) return@synchronized false
            record.status = AgentStatus.INACTIVE
            record.terminal = terminal
            if (!closing) {
                val parentId = record.parentId
                if (parentId != null) {
                    notices.getOrPut(parentId, ::ArrayDeque).addLast(settlementNotice(record, terminal))
                    notifyOwner = parentId
                }
            }
            true
        }
        if (!changed) return
        // 先让面板看到终态（结算通知可能立刻唤醒父代理再跑一轮，面板不该落后）
        onChange?.invoke()
        notifyOwner?.let { onNotice?.invoke(it) }
    }

    private fun settlementNotice(record: AgentRecord, terminal: AgentTerminal): AgentNotice {
        val summary = settlementSummary(record.id, terminal.stopReason, record.continuable)
        val texts = ArrayList<String>()
        texts += summary
        val closing = terminal.closingTexts.filter { it.isNotEmpty() }
        when {
            record.reportedToParent ->
                texts += "It sent you a message just before finishing, so its closing message is omitted."
            closing.isEmpty() -> texts += "It left no closing message."
            else -> {
                texts += "Its closing message:"
                texts += closing
            }
        }
        terminal.structured?.let { texts += "Structured result: $it" }
        terminal.diagnostic?.let { texts += it }
        return AgentNotice(
            agentId = record.id,
            owner = record.parentId,
            texts = texts,
            summary = boundSummary(summary),
            kind = "subagent-settled",
            form = "notice",
            senderSessionId = record.id,
        )
    }

    companion object {
        /** 上游 `boundContextSummary`：≤120 原样，否则前 119 + `…`。 */
        fun boundSummary(text: String): String =
            if (text.length <= 120) text else text.take(119) + "…"

        /** 上游 `settlementSummary` 的逐字文案。 */
        fun settlementSummary(childId: String, stopReason: String, continuable: Boolean = true): String {
            val subject = "Background subagent $childId"
            return when (stopReason) {
                "completed" ->
                    if (continuable) "$subject finished and will do no further work unless you send it more."
                    else "$subject finished. It cannot receive follow-up messages."
                "aborted" -> "$subject was stopped before it finished."
                "max-tokens" -> "$subject ran out of room before it finished."
                "refusal" -> "$subject declined the task."
                "error" -> "$subject failed before it finished."
                else -> "$subject ended abnormally ($stopReason) before it finished."
            }
        }

        /** 子代理初始任务末尾的返回指导（上游 `withContinuableReturnGuidance`）。 */
        fun returnGuidance(parentId: String): String {
            val encoded = "\"$parentId\""
            return "Your parent agent id is $encoded. Before you finish, send your result to that agent with " +
                "send_message({ agent_id: $encoded, message: \"<self-contained result>\" }). The parent shares " +
                "your workspace but does not automatically receive your transcript, tool output, or reasoning. Send " +
                "earlier messages as well when a finding changes what the parent should do next; sending a message " +
                "does not end your turn."
        }

        /** 子代理自述（上游 `SUBAGENT_DELEGATION_CONTEXT`，进运行时上下文）。 */
        const val DELEGATION_CONTEXT: String =
            "You are a delegated subagent: your permission scope was fixed when you were started and cannot be " +
                "widened from inside this session — operations that require approval are rejected automatically. " +
                "When the task needs access beyond that scope, do not retry the denied operation; state the " +
                "limitation in your reply so the delegating agent can handle it."

        /** 深度用尽时的上下文提示（上游 `tool:subagent:depth`）。 */
        fun depthContext(toolName: String, current: Int, maximum: Int): String =
            "Delegation depth for `$toolName`: current $current, maximum $maximum, remaining levels 0. " +
                "Do not call `$toolName` at this depth; carry out the work directly."
    }
}
