package xin.dshaa.nativeapp.fish

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xin.dshaa.ui.shellapi.chat.ChatSessionState

/**
 * 大肥鱼（dsh-dafeiyu）状态机——照 `src/protocol.js` + `src/companion-reducer.js` 移植。
 *
 * 原插件把 DSH 的 `session/event` 归约成 7 个伴侣状态，再通过 stdio 协议推给桌面原生
 * Helper（Qt/PySide/Swift 透明置顶窗）。Android 上没有 Electron / Node / 原生 Helper。
 *
 * M71 起原生悬浮窗（`BigFishService`）已删除：这里只保留**状态机与快照**，作为通用客户端插件
 * 宿主 `ctx.get('sessions')` 的数据源（插件自己的 `dsh.client` 读它决定动画/气泡）；
 * 不再有鲸歌自己画的窗口。
 *
 * 状态优先级照抄 reducer：WAITING > ERROR > WORKING > THINKING > IDLE。
 *
 * **M78 多会话并行**：每个会话各记一份快照（[owner] = 该会话的 `ChatSessionState` 对象），
 * 对外发布的 [state] 是合并结果——**前台在跑发前台；否则发最近一条在跑的；都没有才发前台/最新的**。
 * 以前是进程级单状态、谁最后写谁覆盖：A 还在跑时 B 结束一句「这轮做完了」就把 A 盖掉了。
 */
enum class FishState {
    IDLE,
    THINKING,
    WORKING,
    WAITING,
    SUCCESS,
    ERROR,
}

/** 一次渲染需要的全部信息（reducer 的 STATE/TASK 消息合并成一个快照）。 */
data class FishSnapshot(
    val state: FishState = FishState.IDLE,
    val stage: String = "等待任务",
    val message: String = "我在这儿等新任务哦",
    val detail: String = "鲸歌 · 等待下一次任务",
    val activity: String? = null,
    val toolName: String? = null,
    val project: String? = null,
    val reasoningEffort: String? = null,
    /** 瞬时提示（SUCCESS/ERROR 脉冲）到这个时间点为止，之后回到 IDLE。 */
    val pulseUntil: Long = 0L,
    /** 本轮最终回复正文（M66，喂给鲸鱼娘挂件播报；只在 turn/end 时写一次）。 */
    val reply: String? = null,
    /** 回复序号：同一段文字再次出现也能让挂件重新播报（只增不减）。 */
    val replySeq: Long = 0L,
    /** 本轮真实 token 用量（input+output，M72：鲸鱼娘挂件按上游规则折成功德）。 */
    val tokens: Int = 0,
    /** token 序号：同一数字再次出现也算新的一轮（只增不减）。 */
    val tokensSeq: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
)

object BigFish {

    private const val TAG = "DshFish"

    /** 同时保留几份会话快照；超了丢最旧、且不是前台的那份。 */
    private const val MAX_OWNERS = 8

    private val _state = MutableStateFlow(FishSnapshot())
    val state: StateFlow<FishSnapshot> = _state.asStateFlow()

    /** 每个会话一份快照；键是同一条会话稳定的对象（`ChatSessionState`）。 */
    private val owners = LinkedHashMap<Any, FishSnapshot>()

    /** 前台会话（停在列表页时为 null）。合并时优先它。 */
    private var foreground: Any? = null

    private val lock = Any()

    /** 回复序号只增不减：`onTurnStart` 重建快照时也带着它，挂件不会把旧回复再播一遍。 */
    private var replyCounter = 0L

    /** token 序号（M72）：同上，挂件按它判断「这是新的一轮用量」。 */
    private var tokenCounter = 0L

    /** 每个会话本轮累计 token（并行时不能共用一个数）。 */
    private val turnTokens = HashMap<Any, Int>()

    /** 没有 owner 的调试通道（`--es dsh_fish_demo`）用这一份累计。 */
    private var anonymousTokens = 0

    /** 「还没发布过」的哨兵（owner 可以是 null，不能拿 null 当哨兵）。 */
    private val NOBODY = Any()

    /** 上一次发布的 owner / 状态，只用来打日志（logcat `DshFish`，真机验收证据）。 */
    private var lastOwner: Any? = NOBODY
    private var lastState: FishState? = null

    /** 工具名 → 活动类型；正则顺序逐字照搬 reducer 的 `toolActivity()`。 */
    fun activityOf(tool: String): String {
        val value = tool.lowercase()
        return when {
            Regex("search|grep|find|glob|web|read|fetch|open").containsMatchIn(value) -> "searching"
            Regex("write|edit|patch|replace|create|move|delete").containsMatchIn(value) -> "editing"
            Regex("test|check|lint|build|verify").containsMatchIn(value) -> "testing"
            Regex("shell|bash|exec|command|terminal|powershell").containsMatchIn(value) -> "commanding"
            else -> "using-tool"
        }
    }

    private fun activityStage(activity: String): String = when (activity) {
        "searching" -> "查找资料"
        "editing" -> "改文件"
        "testing" -> "跑验证"
        "commanding" -> "执行命令"
        else -> "调用工具"
    }

    private fun activityCopy(activity: String): String = when (activity) {
        "searching" -> "我翻翻资料…"
        "editing" -> "我在改文件…"
        "testing" -> "跑一下验证…"
        "commanding" -> "执行命令中…"
        else -> "忙起来了…"
    }

    private fun detailFor(
        project: String?,
        effort: String?,
        stage: String?,
        task: String? = null,
    ): String = buildList {
        if (!project.isNullOrBlank()) add(project)
        if (!effort.isNullOrBlank() && effort != "off") add("推理 $effort")
        if (!task.isNullOrBlank()) add(task) else if (!stage.isNullOrBlank()) add(stage)
    }.joinToString(" · ").ifBlank { "鲸歌任务" }

    private val FishSnapshot.isRunning: Boolean
        get() = state == FishState.THINKING || state == FishState.WORKING || state == FishState.WAITING

    // ---- 会话合并（M78） ----

    /** 前台会话变化（`AppViewModel.attach`）：大肥鱼/插件窗口跟着前台走。 */
    fun setForeground(owner: Any?) {
        synchronized(lock) {
            if (foreground === owner) return
            foreground = owner
            publish()
        }
    }

    /** 会话被丢弃（草稿回收等）时清掉它那份快照。 */
    fun forget(owner: Any) {
        synchronized(lock) {
            owners.remove(owner)
            turnTokens.remove(owner)
            if (foreground === owner) foreground = null
            publish()
        }
    }

    private fun snapshotOf(owner: Any?): FishSnapshot =
        if (owner == null) _state.value else owners[owner] ?: FishSnapshot(replySeq = replyCounter, tokensSeq = tokenCounter)

    private fun update(owner: Any?, snapshot: FishSnapshot) {
        if (owner == null) {
            // 调试通道（没有会话）：直接摆状态，不参与合并
            _state.value = snapshot
            return
        }
        owners[owner] = snapshot
        trim()
        publish()
    }

    private fun trim() {
        while (owners.size > MAX_OWNERS) {
            val victim = owners.entries
                .filter { it.key !== foreground }
                .minByOrNull { it.value.updatedAt } ?: return
            owners.remove(victim.key)
            turnTokens.remove(victim.key)
        }
    }

    /**
     * 合并规则（M78）：
     * 1. 前台会话在跑 → 发前台（用户正看着的那条永远最相关）；
     * 2. 否则发最近一条在跑的会话（A 在跑、B 刚结束 → 显示 A，不再被 B 的「已完成」盖掉）；
     * 3. 都没在跑 → 发最近一条刚完成/失败的（成功脉冲 2.4 秒，挂件照旧播报）；
     * 4. 再退到前台会话、最后是最新一条。
     */
    private fun publish() {
        val now = System.currentTimeMillis()
        val fg = foreground?.let { owners[it] }
        if (fg != null && fg.isRunning) {
            emit(foreground, fg)
            return
        }
        val running = owners.entries.filter { it.value.isRunning }.maxByOrNull { it.value.updatedAt }
        if (running != null) {
            emit(running.key, running.value)
            return
        }
        val fresh = owners.entries.filter { it.value.pulseUntil > now }.maxByOrNull { it.value.updatedAt }
        if (fresh != null) {
            emit(fresh.key, fresh.value)
            return
        }
        if (fg != null) {
            emit(foreground, fg)
            return
        }
        val newest = owners.entries.maxByOrNull { it.value.updatedAt } ?: return
        emit(newest.key, newest.value)
    }

    private fun emit(owner: Any?, snapshot: FishSnapshot) {
        _state.value = snapshot
        if (owner !== lastOwner || snapshot.state != lastState) {
            lastOwner = owner
            lastState = snapshot.state
            Log.i(
                TAG,
                "publish owner=@${Integer.toHexString(System.identityHashCode(owner))} " +
                    "state=${snapshot.state} project=${snapshot.project} detail=${snapshot.detail.take(60)}",
            )
        }
    }

    // ---- token 累计（按会话） ----

    private fun resetTokens(owner: Any?) {
        if (owner == null) anonymousTokens = 0 else turnTokens[owner] = 0
    }

    private fun addTokens(owner: Any?, inputTokens: Int, outputTokens: Int) {
        val delta = (if (inputTokens > 0) inputTokens else 0) + (if (outputTokens > 0) outputTokens else 0)
        if (delta <= 0) return
        if (owner == null) anonymousTokens += delta else turnTokens[owner] = (turnTokens[owner] ?: 0) + delta
    }

    private fun usedTokens(owner: Any?): Int =
        if (owner == null) anonymousTokens else turnTokens[owner] ?: 0

    // ---- 状态机 ----

    /** 一轮开始（上游 `turn/start`）：进入 THINKING / 准备阶段。 */
    fun onTurnStart(owner: Any?, effort: String?, project: String?) {
        synchronized(lock) {
            resetTokens(owner)
            update(
                owner,
                FishSnapshot(
                    state = FishState.THINKING,
                    stage = "准备阶段",
                    message = "新任务来啦，我先想想…",
                    detail = detailFor(project, effort, "准备阶段"),
                    project = project,
                    reasoningEffort = effort,
                    replySeq = replyCounter,
                    tokensSeq = tokenCounter,
                ),
            )
        }
    }

    /**
     * 每个 step 的 token 用量（M72，`TurnEngine` 的 `ui.setTokenUsage`）。
     * 引擎给的是**本 step** 的 input/output，这里按会话按轮累加，turn/end 时随快照交给插件。
     */
    fun onTokenUsage(owner: Any?, inputTokens: Int, outputTokens: Int) {
        synchronized(lock) { addTokens(owner, inputTokens, outputTokens) }
    }

    /** 设置栏的当前输入（`request/header` 的 reasoningEffort 展示）。 */
    fun onReasoning(owner: Any?, effort: String?) {
        synchronized(lock) {
            val current = snapshotOf(owner)
            if (current.reasoningEffort == effort) return
            update(
                owner,
                current.copy(
                    reasoningEffort = effort,
                    detail = detailFor(current.project, effort, current.stage),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * 上游引擎每个 step 的 `ui.setStatus()`：
     * - `运行工具 <name>…` → WORKING + 活动分类；
     * - `思考中…` / `继续处理…` / `继续…` → THINKING；
     * - `已拒绝` → WAITING；
     * - `上下文超限，正在压缩…` → WORKING(整理)。
     */
    fun onStatus(owner: Any?, text: String?) {
        if (text == null) return
        synchronized(lock) {
            val current = snapshotOf(owner)
            val project = current.project
            val effort = current.reasoningEffort
            when {
                text.startsWith("运行工具 ") -> {
                    val tool = text.removePrefix("运行工具 ").removeSuffix("…").trim()
                    val activity = activityOf(tool)
                    update(
                        owner,
                        current.copy(
                            state = FishState.WORKING,
                            stage = activityStage(activity),
                            message = activityCopy(activity),
                            detail = detailFor(project, effort, activityStage(activity)),
                            activity = activity,
                            toolName = tool,
                            pulseUntil = 0L,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                text.startsWith("思考中") || text.startsWith("继续处理") || text == "继续…" -> {
                    if (current.state == FishState.THINKING && current.stage == "分析阶段") return
                    update(
                        owner,
                        current.copy(
                            state = FishState.THINKING,
                            stage = "分析阶段",
                            message = "我想想…",
                            detail = detailFor(project, effort, "分析阶段"),
                            activity = null,
                            toolName = null,
                            pulseUntil = 0L,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                text.contains("压缩") -> {
                    update(
                        owner,
                        current.copy(
                            state = FishState.WORKING,
                            stage = "整理上下文",
                            message = "上下文有点长，我先收拾一下…",
                            detail = detailFor(project, effort, "整理上下文"),
                            activity = null,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                text == "已拒绝" -> onWaiting(owner, current.toolName)
            }
        }
    }

    /** 上游 `approval/asked`：WAITING，气泡提醒（悬浮窗会响一声，见设置）。 */
    fun onWaiting(owner: Any?, toolName: String?) {
        synchronized(lock) {
            val current = snapshotOf(owner)
            update(
                owner,
                current.copy(
                    state = FishState.WAITING,
                    stage = "等待审批",
                    message = "需要你点一下：工具要执行",
                    detail = detailFor(current.project, current.reasoningEffort, "等待审批") +
                        (toolName?.let { " · $it" } ?: ""),
                    toolName = toolName ?: current.toolName,
                    pulseUntil = 0L,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** `approval/decided` 或问题回答完：回到 THINKING。 */
    fun onApprovalResolved(owner: Any?) {
        synchronized(lock) {
            val current = snapshotOf(owner)
            if (current.state != FishState.WAITING) return
            update(
                owner,
                current.copy(
                    state = FishState.THINKING,
                    stage = "分析阶段",
                    message = "收到，继续做…",
                    detail = detailFor(current.project, current.reasoningEffort, "分析阶段"),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** `turn/end`：完成 → SUCCESS 脉冲；错误 → ERROR 常驻。[reply] 是本轮最终回复正文。 */
    fun onTurnEnd(
        owner: Any?,
        error: String?,
        reply: String? = null,
        tokens: Int? = null,
    ) {
        synchronized(lock) {
            val current = snapshotOf(owner)
            val now = System.currentTimeMillis()
            if (!error.isNullOrBlank()) {
                update(
                    owner,
                    current.copy(
                        state = FishState.ERROR,
                        stage = "需要处理",
                        message = "出错了，看一眼",
                        detail = error.take(80),
                        pulseUntil = 0L,
                        updatedAt = now,
                    ),
                )
                return
            }
            val cleanReply = reply
                ?.trim()
                ?.takeIf { it.length >= 2 }
                ?.take(4000)
            if (cleanReply != null) replyCounter += 1
            val cleanTokens = (tokens ?: usedTokens(owner)).coerceIn(0, 50_000_000)
            if (cleanTokens > 0) tokenCounter += 1
            update(
                owner,
                current.copy(
                    state = FishState.SUCCESS,
                    stage = "已完成",
                    message = "这轮做完了！",
                    detail = detailFor(current.project, current.reasoningEffort, "本轮已完成"),
                    activity = null,
                    toolName = null,
                    pulseUntil = now + 2_400L,
                    reply = cleanReply ?: current.reply,
                    replySeq = replyCounter,
                    tokens = cleanTokens,
                    tokensSeq = tokenCounter,
                    updatedAt = now,
                ),
            )
        }
    }

    fun onError(owner: Any?, message: String?) {
        if (message.isNullOrBlank()) return
        synchronized(lock) {
            val current = snapshotOf(owner)
            update(
                owner,
                current.copy(
                    state = FishState.ERROR,
                    stage = "需要处理",
                    message = "出错了，看一眼",
                    detail = message.take(80),
                    pulseUntil = 0L,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** 会话标题 = 上游 reducer 的 projectName（项目名优先）。 */
    fun onProject(owner: Any?, title: String?) {
        val project = title?.trim()?.takeIf { it.isNotBlank() && it != "新会话" } ?: return
        synchronized(lock) {
            val current = snapshotOf(owner)
            if (current.project == project) return
            update(
                owner,
                current.copy(
                    project = project,
                    detail = detailFor(project, current.reasoningEffort, current.stage),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** 用户停止 / 会话切换：回到 IDLE。 */
    fun onIdle(owner: Any?) {
        synchronized(lock) {
            turnTokens.remove(owner)
            if (owner == null) anonymousTokens = 0
            update(owner, FishSnapshot(replySeq = replyCounter, tokensSeq = tokenCounter))
        }
    }

    /** 悬浮窗显示时用：脉冲过期就按 IDLE 画。 */
    fun visibleSnapshot(now: Long = System.currentTimeMillis()): FishSnapshot {
        val current = _state.value
        return if (current.pulseUntil in 1 until now) FishSnapshot(project = current.project) else current
    }
}
