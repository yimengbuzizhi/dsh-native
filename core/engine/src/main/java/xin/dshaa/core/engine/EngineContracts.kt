package xin.dshaa.core.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import xin.dshaa.core.llm.LlmConfig
import xin.dshaa.core.llm.LlmEvent
import xin.dshaa.core.model.ChatMessage
import xin.dshaa.core.model.GoalView
import xin.dshaa.core.model.ImageOffloadTarget
import xin.dshaa.core.model.ImageRef
import xin.dshaa.core.model.ToolCall
import xin.dshaa.core.protocol.ApiRequest
import xin.dshaa.core.protocol.ApiTool
import xin.dshaa.core.protocol.TokenUsage
import xin.dshaa.core.session.GoalRoundRef
import xin.dshaa.core.session.GoalStore
import xin.dshaa.core.session.SessionSession
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolRegistry

/**
 * `core:engine` 的契约层：把「一轮对话怎么跑」从 App 的 Compose 状态里剥出来。
 *
 * 为什么要抽（docs/28 §七.2 起欠了四轮的账）：
 * - [TurnEngine] 现在只依赖这些接口，不再 import 任何 `androidx.*`，可以在 JVM 单测里
 *   用假 LLM / 假工具跑完整轮次（步/轮事件平衡、goal 续轮、收尾通知顺序、取消落库）；
 * - 下一步的 `subagent` / `job_*` 工具需要「在进程内再跑一个 agent」，只能复用一个不绑 UI 的引擎；
 * - App 侧只剩「把事件写进 Compose 状态」的适配器，`AppViewModel` 不再是一千三百行的上帝对象。
 *
 * 契约等价性：本文件只做「搬家」，所有事件顺序、文案、写盘时机与抽出前逐字节一致
 * （见 `TurnEngineTest` 与门 2 对拍）。
 */

/** 一轮输入：普通人类消息，或 goal-round-driver 排的一轮自动续轮。 */
data class TurnInput(
    val text: String,
    val goalRound: GoalRoundRef? = null,
    /**
     * 后台任务完成通知唤醒的一轮：通知在 `turn/start` / `step/start` 之后作为
     * `user/message` 落库（上游 inbox 在 step 起点被 claim 的顺序），本轮不追加文本用户消息。
     */
    val notices: List<UserNotice> = emptyList(),
    /**
     * 非空 = 用户消息写成**多个 text 块**（上游子代理初始任务 = 任务 + 返回指导；
     * 不能先 join 成一条，否则请求体/会话格式与上游不一致）。
     */
    val textBlocks: List<String> = emptyList(),
    /**
     * 用户随消息附上的图片（聊天输入条附件，docs/42）：落进同一个 `user/message` 的 image 块，
     * 请求体里转成「句柄文本 + base64 image」（上游 `dsh-llm-deepseek` 的 user 分支）。
     * 只有图片没有文字时 [text] 为空。
     */
    val images: List<ImageRef> = emptyList(),
    /** Durable outbox identity; only direct human inputs may carry it. */
    val promptId: String? = null,
    /** queue / steer, recorded on user/message.source for recovery and display. */
    val delivery: String = "queue",
) {
    /** 通知唤醒轮：不追加用户消息、不起标题，直接从通知消息发请求。 */
    val wake: Boolean get() = notices.isNotEmpty()

    /** 本轮用户消息的 text 块（普通轮为单块）。 */
    val userTexts: List<String> get() = if (textBlocks.isNotEmpty()) textBlocks else listOf(text)
}

/**
 * 宿主注入的一条 user 通知（`source.kind:"tool-jobs"` / `"subagent-settled"` / `"agent-message"`）。
 * 上游把已完成 job / 已结算子代理的通知塞进 agent 的 next-step inbox；这里由 App 从运行时取。
 */
data class UserNotice(
    val text: String,
    val summary: String,
    val kind: String = "tool-jobs",
    /** 非空 = 落成多个 text 块（子代理结算通知：摘要 + closing message）。 */
    val texts: List<String>? = null,
    /** 完整 source 覆盖（子代理通知的 source 字段与 tool-jobs 不同）。 */
    val source: JsonObject? = null,
)

/** 一轮结束后的状态（决定能不能自动续轮）。 */
enum class TurnOutcome { COMPLETED, MAX_TOKENS, ERROR }

/** A request attempt's lifecycle. COMPLETED means collection ended without failure/cancellation. */
enum class RequestUsageStatus { STARTED, STREAMING, COMPLETED, FAILED, CANCELLED }

/** Scoped to one main-turn request (summary requests have their own accounting). */
data class RequestUsage(
    val requestNo: Int,
    val usage: TokenUsage,
    val status: RequestUsageStatus,
)

/**
 * UI 侧（Compose 状态容器）要实现的最小接口。
 *
 * 消息列表仍然由 UI 持有：流式 token 每 33ms 就地替换一条消息，这是 Compose 快照状态的强项，
 * 引擎只负责「往哪个下标写什么」。回放/重开会话时 UI 直接铺历史，引擎不用重复一份。
 */
interface TurnUi {
    /** 当前会话可见的全部消息（含历史）。引擎据此组装请求。 */
    fun messages(): List<ChatMessage>

    fun title(): String
    fun setTitle(title: String)
    fun setSessionId(id: String)
    fun setStreaming(streaming: Boolean)
    fun setStatus(text: String?)

    /** 失败文案（null = 清空）。 */
    fun setError(text: String?)

    /** token 用量文案（如 `123 in / 45 out`）。 */
    fun setUsage(text: String?)

    /**
     * 本 step 的 token 用量（数字版，M72）。
     *
     * [setUsage] 只给文案（界面用），宿主侧统计（鲸鱼娘挂件的「投喂 token 换功德」）需要数字；
     * 引擎每个 step 成功收口时调一次，宿主按轮累加。默认空实现，宿主不关心时不用管。
     */
    fun setTokenUsage(inputTokens: Int, outputTokens: Int) {}

    /**
     * Nullable cumulative usage for each main-turn request attempt. STARTED carries an empty snapshot
     * immediately before onRequest(requestNo, request); STREAMING updates and one terminal callback
     * use that same number. Replace display state on STARTED; merge only within that request.
     * Includes failed/cancelled attempts without adding them to legacy success-only rewards.
     */
    fun onRequestUsage(update: RequestUsage) {}

    /** 目标条快照（null = 没有目标）。 */
    fun setGoal(view: GoalView?)

    /** 追加一条消息，返回它的下标（流式更新要用）。 */
    fun appendMessage(message: ChatMessage): Int

    /** 就地替换一条消息（流式渲染）。 */
    fun updateMessage(index: Int, message: ChatMessage)

    /**
     * 自动压缩（M44）把 [shadowedSeqs] 这段历史换成一条 checkpoint：
     * 宿主删掉这些 seq 的消息，在第一条的位置插入 [checkpoint]（seq = 新落库的 user/message）。
     * 与手工 `/compact` 的会话面替换同形；只由引擎在 step 起点调用。
     */
    fun applyCompaction(shadowedSeqs: List<Long>, checkpoint: ChatMessage)

    /**
     * 工具结果修剪（M46，上游 `pruneSession()` 的会话面替换）：把 seq=[originalSeq] 的那条
     * `tool` 消息**就地**换成 [replacement]（`seq` = 新落库的 `tool/result`，内容 = 头/尾 + 省略标记）。
     * 位置不能动：请求体里工具结果必须紧跟它的调用。
     */
    fun applyToolResultPrune(originalSeq: Long, replacement: ChatMessage)

    /**
     * 图片卸载（M47，上游 `image/offload` 投影）：把 [targets] 选中的 image 出现位置**就地**
     * 标成 `offloaded`，下一份请求里它们会被换成占位文本。消息对象与顺序都不变。
     */
    fun applyImageOffload(targets: List<ImageOffloadTarget>)
}

/** 会话来源：新会话由宿主创建（写 header、设 cwd/model），引擎只管用。 */
interface SessionGateway {
    fun current(): SessionSession?

    /** 新建并登记为当前会话（实现方负责 `request/header` 与 `handle` 赋值）。 */
    suspend fun create(): SessionSession
}

/**
 * 「用户是否按了停止」的共享开关。宿主与引擎都要读写，所以拿出来独立成对象：
 * - `stop()` 置 true（用户 interrupt）；
 * - 切会话 / 关页面（cancelStream）置 false（内部取消）；
 * - 每轮开始置 false（与抽出前 AppViewModel.userStopRequested 的三处写点一一对应）。
 */
class TurnStopState {
    @Volatile
    var requested: Boolean = false
}

/**
 * 引擎的全部外部依赖。App 之外的东西（jvm 单测）可以逐项造假。
 *
 * 注意 [apiTools] / [systemPrompt] / [runtimeContext] 都是**每次请求现算**的语义：
 * 上游把工具清单和系统提示词拼进请求体，抽出来以后仍由 App 用同一个 PromptPack 生成，
 * 保证与抽出前逐字节相同。
 */
class EngineDeps(
    /** 工作区绝对路径（只用于运行时上下文里的 cwd 文案）。 */
    val workspacePath: String,
    /** 模型 / 端点 / 采样参数（每次请求现取，用户中途改设置要立刻生效）。 */
    val config: () -> LlmConfig,
    /** `deepseek` / `deepseek-account`（写进 request/header 与系统提示词）。 */
    val providerLabel: () -> String,
    /** `high` / `max`（output_config.effort）。 */
    val reasoningEffort: () -> String,
    val registry: ToolRegistry,
    val toolContext: ToolContext,
    /** 宿主与引擎共享的「用户按停止」开关。 */
    val stop: TurnStopState = TurnStopState(),
    /** 已经按 registry 过滤好的工具 schema，**每次请求现算**（顺序即请求体顺序；能力开关要能中途生效）。 */
    val apiTools: () -> List<ApiTool>,
    val systemPrompt: () -> String,
    val runtimeContext: () -> String,
    /** 真正发请求（测试里换成脚本化的假流）。 */
    val stream: (ApiRequest, LlmConfig) -> Flow<LlmEvent>,
    val sessions: SessionGateway,
    val goals: (SessionSession) -> GoalStore,
    /** 附件字节（image 块重放）；null = 附件丢了，跳过该块。 */
    val imageBytes: (String) -> ByteArray?,
    /**
     * 附件归一化副本的绝对路径（M47 图片卸载的占位文本用）；null = 拿不到路径，
     * 占位文本会让模型请用户重新发图。
     */
    val imagePath: (String) -> String? = { null },
    /**
     * 当前生效的图片请求预算（M47）。默认是上游 600 张 / 20 MiB；调试通道可以临时改小，
     * 锁屏时不用造 20 MiB 的会话也能验卸载链路。
     */
    val imageBudget: () -> ImageOffload.Budget = { ImageOffload.DEFAULT_BUDGET },
    /** 工具审批：返回 false = 用户拒绝（引擎落一条 `User denied this tool call.` 结果）。 */
    val approval: suspend (ToolCall) -> Boolean,
    /** 到期提醒（startTurn 的循环里用来让路）；null = 没有。 */
    val nextSchedulePrompt: () -> String?,
    /** 循环收尾后再投一次提醒（原 AppViewModel 行为）。 */
    val tickSchedules: () -> Unit,
    /** 每次请求发出前（门 2 落 body / 写 run.jsonl）；第一个参数是本次 run 内的请求序号。 */
    val onRequest: (Int, ApiRequest) -> Unit,
    /**
     * 每次**摘要请求**发出前（M48 调试通道：`--es dsh_dump_summary_request <目录>` 把摘要请求体
     * 原样落盘，验证「第一份带图 → 卸载 → 第二份只剩占位文本」）。与 [onRequest] 分开：
     * 轮次请求的编号与 run.jsonl 语义不变。
     */
    val onSummaryRequest: (ApiRequest) -> Unit = {},
    /** 每个 SSE 事件（run.jsonl 用）。 */
    val onLlmEvent: (Int, LlmEvent) -> Unit,
    /** 每次工具执行完（run.jsonl 用）。 */
    val onToolResult: (ToolCall, String, Boolean) -> Unit,
    /** 一轮结束（日志、会话列表刷新）。 */
    val onTurnFinished: () -> Unit,
    /**
     * 取走排队中的后台任务完成通知（每个 step 边界现取；idle 时由宿主另起一轮）。
     * 返回的通知会在本 step 的所有 tool_result 之后落成 `user/message`。
     */
    val jobNotices: () -> List<UserNotice> = { emptyList() },
    /**
     * 技能目录（M19，上游 `dsh-tool-skill` 的 `agent/pre-step`）：每个 step 起点算一次；
     * 非 null 就落一条 `source.kind:"skill-catalog"` 的 `user/message`（正文是给模型的目录/变更通知）。
     * 返回 null = 目录没变 / 目录为空且从没发布过 → 请求体一个字节都不变。
     */
    val skillCatalog: (SessionSession) -> UserNotice? = { null },
    /**
     * 用户直接 `/名字` 调用技能（M21，上游 `dsh-tool-skill` 的 `agent/pre-step` 第一段）：
     * 收到本 step 直接用户输入的各 text 块，返回要注入的 `source.kind:"skill-invocation"`
     * user 消息（正文 = `renderSkillContent()`）。注入点在本 step 的技能目录之后、请求之前。
     */
    val skillInvocations: (SessionSession, List<String>) -> List<UserNotice> = { _, _ -> emptyList() },
    /**
     * 时间上下文（M42，上游 `dsh-time-context` 的 `agent/pre-step`）：每个 step 起点现取；
     * null = 关闭（设置里关掉，或配置被禁用）。非 null 时引擎自己算投影与节流，
     * 满足条件就在本 step 的注入消息之后、请求之前落一条 `source.kind:"time-context"` 的 user 消息。
     */
    val timeContext: () -> TimeContext.Config? = { null },
    /**
     * 自动压缩（M44）：当前模型的上下文窗口（tokens）；null / ≤0 = 不知道 → 不自动压缩。
     * 引擎用 [Compaction.Policy.resolve] 结合 [config] 的 maxTokens 算触发线与保留预算。
     */
    val contextWindow: () -> Int? = { null },
    /**
     * 自动压缩触发比例（M79）：窗口用到多少开始压。默认 0.8（上游 `THRESHOLD_RATIO`）；
     * App 设置页可调 0.3–0.95，关掉自动压缩时 contextWindow 直接给 null。
     */
    val compactionRatio: () -> Double = { Compaction.Policy.THRESHOLD_RATIO },
    /** 引擎日志（自动压缩触发/跳过/失败）；App 接到 logcat，单测可收集。 */
    val log: (String) -> Unit = {},
    /** 会话级计划模式状态（M18）：引擎在 step 起点应用 pending、组装请求时记 activeAtLastHeader。 */
    val plan: PlanModeState = PlanModeState(),
    /** 计划模式状态可能变了（pending 落库）：宿主刷新计划条。 */
    val onPlanChanged: () -> Unit = {},
    /** 消息 id（测试可换成确定性序列）。 */
    val newId: () -> String = { java.util.UUID.randomUUID().toString() },
    /**
     * M84-C2：step / 工具 / 停止 / 错误重试的观察钩子。默认全 no-op，
     * 不改变事件顺序与文案（只观察，不重写循环；见 `EngineHooks`）。
     */
    val hooks: EngineHooks = EngineHooks(),
    /** Claim at most one durable steering input at a step boundary; never cancels an active tool. */
    val claimSteeringInputs: suspend () -> List<TurnInput> = { emptyList() },
    /** Claim the next human input after a successfully completed turn, before reminders/goals. */
    val nextQueuedInput: suspend () -> TurnInput? = { null },
    /** Called only after user/message(promptId) is durable. Failures propagate; recover by promptId. */
    val onInputDelivered: suspend (promptId: String, seq: Long) -> Unit = { _, _ -> },
)
