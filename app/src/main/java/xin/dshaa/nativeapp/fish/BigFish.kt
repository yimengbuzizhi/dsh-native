package xin.dshaa.nativeapp.fish

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val _state = MutableStateFlow(FishSnapshot())
    val state: StateFlow<FishSnapshot> = _state.asStateFlow()

    /** 回复序号只增不减：`onTurnStart` 重建快照时也带着它，挂件不会把旧回复再播一遍。 */
    private var replyCounter = 0L

    /** token 序号（M72）：同上，挂件按它判断「这是新的一轮用量」。 */
    private var tokenCounter = 0L

    /** 本轮累计 token（每个 step 的 input+output 相加；onTurnStart 归零）。 */
    private var turnTokens = 0

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

    /** 一轮开始（上游 `turn/start`）：进入 THINKING / 准备阶段。 */
    fun onTurnStart(effort: String?, project: String?) {
        turnTokens = 0
        _state.value = FishSnapshot(
            state = FishState.THINKING,
            stage = "准备阶段",
            message = "新任务来啦，我先想想…",
            detail = detailFor(project, effort, "准备阶段"),
            project = project,
            reasoningEffort = effort,
            replySeq = replyCounter,
            tokensSeq = tokenCounter,
        )
    }

    /**
     * 每个 step 的 token 用量（M72，`TurnEngine` 的 `ui.setTokenUsage`）。
     * 引擎给的是**本 step** 的 input/output，这里按轮累加，turn/end 时随快照交给插件。
     */
    fun onTokenUsage(inputTokens: Int, outputTokens: Int) {
        if (inputTokens > 0) turnTokens += inputTokens
        if (outputTokens > 0) turnTokens += outputTokens
    }

    /** 设置栏的当前输入（`request/header` 的 reasoningEffort 展示）。 */
    fun onReasoning(effort: String?) {
        val current = _state.value
        if (current.reasoningEffort == effort) return
        _state.value = current.copy(
            reasoningEffort = effort,
            detail = detailFor(current.project, effort, current.stage),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * 上游引擎每个 step 的 `ui.setStatus()`：
     * - `运行工具 <name>…` → WORKING + 活动分类；
     * - `思考中…` / `继续处理…` / `继续…` → THINKING；
     * - `已拒绝` → WAITING；
     * - `上下文超限，正在压缩…` → WORKING(整理)。
     */
    fun onStatus(text: String?) {
        val current = _state.value
        if (text == null) return
        val project = current.project
        val effort = current.reasoningEffort
        when {
            text.startsWith("运行工具 ") -> {
                val tool = text.removePrefix("运行工具 ").removeSuffix("…").trim()
                val activity = activityOf(tool)
                _state.value = current.copy(
                    state = FishState.WORKING,
                    stage = activityStage(activity),
                    message = activityCopy(activity),
                    detail = detailFor(project, effort, activityStage(activity)),
                    activity = activity,
                    toolName = tool,
                    pulseUntil = 0L,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            text.startsWith("思考中") || text.startsWith("继续处理") || text == "继续…" -> {
                if (current.state == FishState.THINKING && current.stage == "分析阶段") return
                _state.value = current.copy(
                    state = FishState.THINKING,
                    stage = "分析阶段",
                    message = "我想想…",
                    detail = detailFor(project, effort, "分析阶段"),
                    activity = null,
                    toolName = null,
                    pulseUntil = 0L,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            text.contains("压缩") -> {
                _state.value = current.copy(
                    state = FishState.WORKING,
                    stage = "整理上下文",
                    message = "上下文有点长，我先收拾一下…",
                    detail = detailFor(project, effort, "整理上下文"),
                    activity = null,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            text == "已拒绝" -> onWaiting(current.toolName)
        }
    }

    /** 上游 `approval/asked`：WAITING，气泡提醒（悬浮窗会响一声，见设置）。 */
    fun onWaiting(toolName: String?) {
        val current = _state.value
        _state.value = current.copy(
            state = FishState.WAITING,
            stage = "等待审批",
            message = "需要你点一下：工具要执行",
            detail = detailFor(current.project, current.reasoningEffort, "等待审批") +
                (toolName?.let { " · $it" } ?: ""),
            toolName = toolName ?: current.toolName,
            pulseUntil = 0L,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** `approval/decided` 或问题回答完：回到 THINKING。 */
    fun onApprovalResolved() {
        val current = _state.value
        if (current.state != FishState.WAITING) return
        _state.value = current.copy(
            state = FishState.THINKING,
            stage = "分析阶段",
            message = "收到，继续做…",
            detail = detailFor(current.project, current.reasoningEffort, "分析阶段"),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** `turn/end`：完成 → SUCCESS 脉冲；错误 → ERROR 常驻。[reply] 是本轮最终回复正文。 */
    fun onTurnEnd(error: String?, reply: String? = null, tokens: Int = turnTokens) {
        val current = _state.value
        val now = System.currentTimeMillis()
        if (!error.isNullOrBlank()) {
            _state.value = current.copy(
                state = FishState.ERROR,
                stage = "需要处理",
                message = "出错了，看一眼",
                detail = error.take(80),
                pulseUntil = 0L,
                updatedAt = now,
            )
            return
        }
        val cleanReply = reply
            ?.trim()
            ?.takeIf { it.length >= 2 }
            ?.take(4000)
        if (cleanReply != null) replyCounter += 1
        val cleanTokens = tokens.coerceIn(0, 50_000_000)
        if (cleanTokens > 0) tokenCounter += 1
        _state.value = current.copy(
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
        )
    }

    fun onError(message: String?) {
        if (message.isNullOrBlank()) return
        val current = _state.value
        _state.value = current.copy(
            state = FishState.ERROR,
            stage = "需要处理",
            message = "出错了，看一眼",
            detail = message.take(80),
            pulseUntil = 0L,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** 会话标题 = 上游 reducer 的 projectName（项目名优先）。 */
    fun onProject(title: String?) {
        val current = _state.value
        val project = title?.trim()?.takeIf { it.isNotBlank() && it != "新会话" } ?: return
        if (current.project == project) return
        _state.value = current.copy(
            project = project,
            detail = detailFor(project, current.reasoningEffort, current.stage),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** 用户停止 / 会话切换：回到 IDLE。 */
    fun onIdle() {
        turnTokens = 0
        _state.value = FishSnapshot(replySeq = replyCounter, tokensSeq = tokenCounter)
    }

    /** 悬浮窗显示时用：脉冲过期就按 IDLE 画。 */
    fun visibleSnapshot(now: Long = System.currentTimeMillis()): FishSnapshot {
        val current = _state.value
        return if (current.pulseUntil in 1 until now) FishSnapshot(project = current.project) else current
    }
}
