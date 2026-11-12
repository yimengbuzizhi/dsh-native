package xin.dshaa.core.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xin.dshaa.core.llm.LlmConfig
import xin.dshaa.core.llm.LlmEvent
import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.ChatMessage
import xin.dshaa.core.model.ContentBlock
import xin.dshaa.core.model.GoalView
import xin.dshaa.core.model.ImageRef
import xin.dshaa.core.model.MessageRole
import xin.dshaa.core.model.ToolCall
import xin.dshaa.core.model.toContentBlock
import xin.dshaa.core.model.toImageRef
import xin.dshaa.core.protocol.ApiMessage
import xin.dshaa.core.protocol.ApiRequest
import xin.dshaa.core.protocol.ContentBlock as ApiBlock
import xin.dshaa.core.protocol.ImageSource
import xin.dshaa.core.protocol.OutputConfig
import xin.dshaa.core.protocol.ThinkingConfig
import xin.dshaa.core.session.GoalIdleDecision
import xin.dshaa.core.session.GoalRoundDriver
import xin.dshaa.core.session.GoalRoundRef
import xin.dshaa.core.session.SessionSession
import xin.dshaa.core.tools.ToolResult
import java.util.Base64

// 2026-11-12（用户：长任务干一半自己停）：这里原来有「单轮最多 12 步」的上限，一条诊断任务
// 跑到第 12 步就被截断，而且是静默退出（turn/end=completed、没有任何提示）。用户明确要求
// 不要上限：循环只在模型自己不再调用工具、max_tokens、用户停止/取消时结束（上游主循环同样没有上限）。

/** 计划模式切换的 narration（上游 `dsh-plan-mode` `narration()` 逐字）。 */
internal const val PLAN_ON_NOTICE = "The user switched this session to plan mode."
internal const val PLAN_OFF_NOTICE = "The user switched this session back to the default mode."

/**
 * 工具调用没有结果就被打断时，补写/补发给模型的 tool_result 正文。
 * 服务端要求每个 `tool_use` 后面紧跟对应的 `tool_result`（用户 2026-11-08 的 400 事故）。
 */
internal const val INTERRUPTED_TOOL_RESULT =
    "Tool call did not return a result: the turn was interrupted before it finished. " +
        "Treat this call as failed; do not retry it unless the user asks."

/**
 * 一轮对话的执行器：写 v4 事件、跑工具循环、按 goal activation 自动续轮。
 *
 * 抽出前这段逻辑在 `AppViewModel.runTurn`（约 300 行）里，直接读写 Compose 状态；
 * 现在只剩 [TurnUi] 这一层适配，App 侧不再持有任何协议细节。
 *
 * 行为契约（与抽出前逐字节一致，`TurnEngineTest` 锁定）：
 * - 事件顺序 `request/header? → turn/start → step/start → user/message → … → step/end → turn/end`；
 * - goal 轮在 user 消息前额外写一条 `request/header`（独立 request series）；
 * - 工具结果全部落库后，才追加 `tool-goal` 收尾通知（上游 deferContext 的顺序）；
 * - 取消时：没落过库的半截 assistant 先补写，再补 step/turn 的 end，用户 stop 的 goal 轮走 pause；
 * - `max_tokens` / 请求失败：只 disarm，不自动重试。
 */
class TurnEngine(private val deps: EngineDeps) {

    /**
     * 自动压缩的两种触发（上游 `CompactionTrigger`）：step 起点的压力、模型确认溢出后的恢复。
     * [logTag] 进 `DshCompact` 日志（锁屏验收靠它）。
     */
    private enum class CompactionTrigger(val logTag: String) {
        PRESSURE("auto-compact"),
        OVERFLOW("context-overflow"),
    }

    private var requestNo = 0

    /** 自动压缩配置不成立时只警告一次（上游 warnedPressureConfigTargets 的同一语义）。 */
    private val warnedCompactionTargets = mutableSetOf<String>()

    /**
     * 跑一轮，然后按 goal activation 决定要不要在 idle 点自动续轮（上游 goal-round-driver）。
     * 整个循环共用同一份 streaming 状态：续轮判决期间不接受新的用户输入。
     * 返回**最后一次 runTurn** 的结果（子代理结算需要 completed / max-tokens / error）。
     */
    suspend fun startTurn(ui: TurnUi, input: TurnInput): TurnOutcome {
        deps.stop.requested = false
        var current = input
        var outcome: TurnOutcome? = null
        while (true) {
            val result = runTurn(ui, current)
            outcome = result
            // 先结清 goal（可能写 round-limit blocked），再让到期的提醒插队：
            // 提醒是用户输入，自动续轮必须为它让路，投递完再继续 goal。
            val nextGoal = nextGoalTurn(ui, result)
            val reminder = deps.nextSchedulePrompt()
            if (reminder != null) {
                current = TurnInput(reminder)
                continue
            }
            current = nextGoal ?: break
        }
        // 用户主动停止时不会走到这里；自动投递下一条提醒
        deps.tickSchedules()
        return outcome ?: TurnOutcome.COMPLETED
    }

    /**
     * idle 点：active + armed + 还有轮次 → 排下一轮；额度耗尽 → 驱动写 blocked(round-limit)；
     * 异常/超 max_tokens → 只 disarm（不自动重试，等用户 resume）。
     */
    fun nextGoalTurn(ui: TurnUi, outcome: TurnOutcome): TurnInput? {
        val session = deps.sessions.current() ?: return null
        val store = deps.goals(session)
        val driver = GoalRoundDriver(store)
        // outcome 只区分「能不能续轮」；异常/超限都不自动重试
        if (outcome != TurnOutcome.COMPLETED) {
            driver.disarm()
            ui.setGoal(runCatching { store.current() }.getOrNull())
            return null
        }
        val decision = runCatching { driver.decideAtIdle() }.getOrElse {
            driver.disarm()
            GoalIdleDecision.Stop
        }
        ui.setGoal(runCatching { store.current() }.getOrNull())
        return when (decision) {
            is GoalIdleDecision.Continue -> {
                val round = decision.round
                TurnInput(
                    text = driver.promptFor(decision.view, round),
                    goalRound = GoalRoundRef(decision.view.goal.id, decision.view.goal.revision, round),
                )
            }
            is GoalIdleDecision.Blocked -> null
            GoalIdleDecision.Stop -> null
        }
    }

    private suspend fun runTurn(ui: TurnUi, input: TurnInput): TurnOutcome {
        ui.setStreaming(true)
        ui.setError(null)
        ui.setUsage(null)
        ui.setStatus("思考中…")

        val session = deps.sessions.current() ?: withContext(Dispatchers.IO) { deps.sessions.create() }
        // 本轮设置快照：下一轮会重新取（用户中途改模型/token 上限，从下一轮开始生效）
        val config = deps.config()
        val provider = deps.providerLabel()
        val effort = deps.reasoningEffort()
        val goalSource = input.goalRound?.let { round ->
            buildJsonObject {
                put("kind", "goal")
                put("goalId", round.goalId)
                put("revision", round.revision)
                put("round", round.round)
            }
        }
        var planStep: PlanStep? = null
        // 本轮的 turn/step 编号（时间上下文 M42 要写进正文；与落库的 turn/start、step/start 一致）
        var turnNumber = 0
        var stepNumber = 0
        // wake 轮的通知：落库时按顺序收集 seq，界面上的通知条也要带同一个 seq（M44 压缩选区用）
        val noticeSeqs = ArrayList<Long>()
        val userSeq = withContext(Dispatchers.IO) {
            // goal 轮是独立的 request series：上游会在 goal 消息前渲染自己的 request header
            if (goalSource != null) {
                session.appendRequestHeader(
                    provider = provider,
                    model = config.model,
                    reasoningEffort = effort,
                    maxTokens = config.maxTokens,
                )
            }
            turnNumber = session.startTurn()
            // 计划模式：pending 在 step/start **之前**落库（上游 agent/pre-step），
            // narration 和 inbox 通知一样在 step/start 之后落成 user/message。
            planStep = applyPendingPlan(session)
            stepNumber = session.startStep()
            planStep?.let { step ->
                step.narration?.let { narration ->
                    step.narrationSeq = session.appendUserMessageTexts(
                        listOf(narration),
                        source = noticeSource("plan-mode", narration),
                    )
                }
            }
            // wake 轮：完成通知在 step/start 之后落成 user/message（上游 inbox 在 step 起点 claim）
            if (input.wake) {
                input.notices.forEach { notice ->
                    noticeSeqs += session.appendUserMessageTexts(
                        notice.texts ?: listOf(notice.text),
                        source = notice.source ?: noticeSource(notice.kind, notice.summary),
                    )
                }
                null
            } else {
                if (input.images.isEmpty()) {
                    session.appendUserMessageTexts(input.userTexts, source = goalSource)
                } else {
                    // 用户附件（docs/42）：text 块 + image 块落在同一条 user/message 里
                    session.appendUserMessageContent(input.userTexts, input.images, source = goalSource)
                }
            }
        }
        planStep?.let { step ->
            if (step.changed) deps.onPlanChanged()
            step.narration?.let { text ->
                ui.appendMessage(
                    noticeMessage(UserNotice(text = text, summary = text, kind = "plan-mode"))
                        .copy(seq = step.narrationSeq),
                )
            }
        }
        var stepOpen = true
        // 本轮 assistant 消息是否已经落库（停止时不要重复写一遍）
        var assistantPersisted = false
        // 本轮已落库、但还没写 tool_result 的调用：停止/切会话打断工具执行时必须补一条
        // error 结果，否则历史里会留一条只有 tool_use 没有 tool_result 的 assistant，
        // 之后每次请求都会被服务端 400 拒绝（用户 2026-11-08 的 messages.78 事故）。
        val pendingToolCalls = ArrayList<ToolCall>()

        ui.setSessionId(session.header.id)
        if (input.wake) {
            input.notices.forEachIndexed { i, notice ->
                ui.appendMessage(noticeMessage(notice).copy(seq = noticeSeqs.getOrNull(i)))
            }
        } else {
            val titleText = input.text.ifBlank { "图片" }
            if (ui.title() == "新会话" && input.goalRound == null) ui.setTitle(titleText.take(18))
            ui.appendMessage(
                ChatMessage(
                    id = deps.newId(),
                    role = MessageRole.USER,
                    content = input.userTexts.filter { it.isNotEmpty() }.map { ContentBlock(BlockType.TEXT, text = it) } +
                        input.images.map { it.toContentBlock() },
                    sourceKind = if (input.goalRound != null) "goal" else "user",
                    goalRound = input.goalRound?.round,
                    // 当前轮用户消息的 seq（M44）：界面消息与会话面一一对应，自动压缩/分支都靠它
                    seq = userSeq,
                ),
            )
        }

        var lastAssistantText = ""
        var lastAssistantReasoning: String? = null
        var lastSignature = ""
        // 当前这一轮流式中的实时内容：取消（停止）时用它落库，避免丢半截回复
        var liveText = ""
        var liveReasoning = ""
        var liveSignature = ""
        // 最后一次 API 调用的 stop_reason（max_tokens 要中止续轮）
        var lastStopReason: String? = null
        // 本轮当前 assistant 占位消息在 UI 里的下标（取消时要把落库后的 seq 回填到同一条）
        var liveAssistantIndex = -1
        // 本轮的错误文案（决定 turn/end 写 completed 还是 error；每轮开头清零，与抽出前 state.error 一致）
        var errorText: String? = null
        try {
            var round = 0
            // 已从注册表取走、等下一个 step 起点落库的后台任务通知
            val queuedNotices = ArrayList<UserNotice>()
            // 不设步数上限（2026-11-12 用户要求）：模型继续调工具就继续跑，
            // 只有模型自己停（toolCalls 为空）、max_tokens、用户停止/取消才会结束。
            while (true) {
                round++
                if (round > 1) {
                    // 第二个及以后的 step：计划模式 pending 在 step/start 之前落库（上游 pre-step），
                    // narration 紧跟在 step/start 之后（与 inbox 通知同序）。
                    val step = withContext(Dispatchers.IO) {
                        val applied = applyPendingPlan(session)
                        stepNumber = session.startStep()
                        applied.narration?.let { narration ->
                            applied.narrationSeq =
                                session.appendUserMessageTexts(listOf(narration), source = noticeSource("plan-mode", narration))
                        }
                        applied
                    }
                    if (step.changed) deps.onPlanChanged()
                    step.narration?.let { text ->
                        ui.appendMessage(
                            noticeMessage(UserNotice(text = text, summary = text, kind = "plan-mode"))
                                .copy(seq = step.narrationSeq),
                        )
                    }
                }
                // step 起点 claim 通知（上游 inbox 在每一步开始投递）：落成 user/message 后再发请求
                queuedNotices += drainJobNotices()
                if (queuedNotices.isNotEmpty()) {
                    appendJobNotices(session, ui, queuedNotices)
                    queuedNotices.clear()
                }
                // 技能目录（M19）：上游 tool-skill 在每个 step 起点的 pre-step 里比较目录摘要，
                // 变了才落一条 `user/message`（source.kind="skill-catalog"）。在 inbox 通知之后、
                // 本 step 的请求之前——与上游「claimed messages → 注入」的顺序一致。
                withContext(Dispatchers.IO) { deps.skillCatalog(session) }?.let { catalog ->
                    val seq = withContext(Dispatchers.IO) {
                        session.appendUserMessageTexts(
                            listOf(catalog.text),
                            source = catalog.source ?: noticeSource(catalog.kind, catalog.summary),
                        )
                    }
                    ui.appendMessage(noticeMessage(catalog).copy(seq = seq))
                }
                // 用户直接 `/名字` 调用技能（M21）：只扫本 step 的直接用户输入（source.kind:"user"）；
                // 注入点在本 step 的技能目录之后、请求之前（上游 pre-step 的注入顺序）。
                val directTexts = if (round == 1 && !input.wake && input.goalRound == null) input.userTexts else emptyList()
                if (directTexts.isNotEmpty()) {
                    val invocations = withContext(Dispatchers.IO) { deps.skillInvocations(session, directTexts) }
                    invocations.forEach { invocation ->
                        val seq = withContext(Dispatchers.IO) {
                            session.appendUserMessageTexts(
                                listOf(invocation.text),
                                source = invocation.source ?: noticeSource(invocation.kind, invocation.summary),
                            )
                        }
                        ui.appendMessage(noticeMessage(invocation).copy(seq = seq))
                    }
                }

                // 时间上下文（M42）：上游 `dsh-time-context` 的 `agent/pre-step`（prepend），
                // 在 skill-invocation 之后、请求之前落一条 `source.kind:"time-context"` 的 user 消息。
                // 节流（默认 10 分钟）与「上一条模型可见消息 / 本轮上次注入」的基线都在 TimeContext 里算。
                val timeConfig = deps.timeContext()
                if (timeConfig != null) {
                    val injection = TimeContext.decide(
                        events = session.events,
                        turn = turnNumber,
                        step = stepNumber,
                        now = System.currentTimeMillis(),
                        config = timeConfig,
                    )
                    if (injection != null) {
                        val seq = withContext(Dispatchers.IO) {
                            session.appendUserMessageTexts(listOf(injection.text), source = injection.source)
                        }
                        // 对话流里折叠成一行「时间上下文 · HH:mm:ss」（正文是给模型的内部注入）
                        ui.appendMessage(
                            noticeMessage(UserNotice(text = injection.text, summary = "", kind = TimeContext.KIND))
                                .copy(seq = seq),
                        )
                    }
                }

                // 自动压缩（M44，上游 `agent/pre-step` 的压力监听）：注入都落完、本 step 请求之前
                // 估一次会话面；越过阈值就先把最早一段换成 checkpoint，再按新的会话面组装请求。
                maybeAutoCompact(ui, session, config, turnNumber)

                val assistantId = deps.newId()
                var assistant = ChatMessage(
                    id = assistantId,
                    role = MessageRole.ASSISTANT,
                    content = emptyList(),
                    streaming = true,
                )
                // 下标会变：溢出恢复压缩会话面后，占位消息会被往前顶（下面重新定位）
                var assistantIndex = ui.appendMessage(assistant)
                liveAssistantIndex = assistantIndex
                ui.setStatus(if (round == 1) "思考中…" else "继续处理…")

                val fullText = StringBuilder()
                val fullReasoning = StringBuilder()
                val signature = StringBuilder()
                val calls = LinkedHashMap<Int, MutableCall>()
                var stopReason: String? = null
                var inputTokens = 0
                var outputTokens = 0
                var failure: String? = null
                var lastEmit = 0L

                fun render(force: Boolean = false) {
                    val now = System.currentTimeMillis()
                    if (!force && now - lastEmit < 33) return
                    lastEmit = now
                    liveText = fullText.toString()
                    liveReasoning = fullReasoning.toString()
                    liveSignature = signature.toString()
                    assistant = assistant.copy(
                        content = buildList {
                            if (fullReasoning.isNotEmpty()) {
                                add(ContentBlock(BlockType.REASONING, text = fullReasoning.toString(), signature = signature.toString()))
                            }
                            if (fullText.isNotEmpty()) add(ContentBlock(BlockType.TEXT, text = fullText.toString()))
                            calls.values.sortedBy { it.index }.forEach { call ->
                                add(
                                    ContentBlock(
                                        type = BlockType.TOOL_CALL,
                                        toolCallId = call.id,
                                        name = call.name,
                                        arguments = call.args.toString(),
                                    ),
                                )
                            }
                        },
                    )
                    ui.updateMessage(assistantIndex, assistant)
                }

                // 溢出恢复（M45）与图片卸载（M47）共用同一个 step 内重试循环：
                // 模型确认超窗 → 压缩整个会话面；图片超预算 → 记录永久卸载选择；然后同一个气泡重发。
                var overflowRetries = 0
                var imageOffloadRetries = 0
                // 同一个气泡重发：清空这次尝试的流式内容，不新开一条 assistant 消息。
                fun restartAttempt() {
                    fullText.setLength(0)
                    fullReasoning.setLength(0)
                    signature.setLength(0)
                    calls.clear()
                    stopReason = null
                    inputTokens = 0
                    outputTokens = 0
                    failure = null
                    errorText = null
                    lastEmit = 0L
                    liveText = ""
                    liveReasoning = ""
                    liveSignature = ""
                    assistant = ChatMessage(
                        id = assistantId,
                        role = MessageRole.ASSISTANT,
                        content = emptyList(),
                        streaming = true,
                    )
                    // 压缩把前面的消息换成了 checkpoint / 卸载只改内容：占位消息永远是最后一条。
                    assistantIndex = ui.messages().lastIndex
                    liveAssistantIndex = assistantIndex
                    ui.updateMessage(assistantIndex, assistant)
                    ui.setStatus(if (round == 1) "思考中…" else "继续处理…")
                }
                while (true) {
                    val request = buildRequest(ui.messages().dropLast(1), config, effort)
                    val turnRequestNo = requestNo
                    var failedEvent: LlmEvent.Failed? = null
                    // 图片卸载（M47）：上游由适配器按路由图片预算报 `IMAGE_OFFLOAD_REQUIRED`；
                    // 原生按 base64 直传、没有 Files 通道，所以在请求组装完之后用同一套默认预算
                    // 本地算一遍，超了合成一条同形失败，省掉一次真实网络往返。
                    val offloadRequired = ImageOffload.requiredCount(request, deps.imageBudget())
                    val events = if (offloadRequired > 0) {
                        deps.log("image-offload: request images exceed budget; offload=$offloadRequired")
                        flowOf(
                            LlmEvent.Failed(
                                message = "request images exceed the route budget; " +
                                    "$offloadRequired more oldest occurrence(s) must be offloaded.",
                                code = ImageOffload.REQUIRED_CODE,
                                status = 400,
                                offloadImages = offloadRequired,
                            ),
                        )
                    } else {
                        deps.stream(request, config)
                    }
                    events.collect { event ->
                        deps.onLlmEvent(turnRequestNo, event)
                        when (event) {
                            is LlmEvent.TextDelta -> {
                                fullText.append(event.text)
                                render()
                            }
                            is LlmEvent.ThinkingDelta -> {
                                fullReasoning.append(event.text)
                                render()
                            }
                            is LlmEvent.SignatureDelta -> signature.append(event.signature)
                            is LlmEvent.BlockStart -> {
                                if (event.type == "tool_use") {
                                    calls[event.index] = MutableCall(event.index, event.toolId, event.toolName)
                                    render(force = true)
                                }
                            }
                            is LlmEvent.ToolInputDelta -> {
                                calls[event.index]?.args?.append(event.partialJson)
                                render()
                            }
                            is LlmEvent.MessageStart -> inputTokens = event.inputTokens
                            is LlmEvent.MessageDelta -> {
                                outputTokens += event.outputTokens
                                if (event.stopReason != null) stopReason = event.stopReason
                            }
                            is LlmEvent.Failed -> {
                                failure = event.message
                                failedEvent = event
                                errorText = event.message
                                assistant = assistant.copy(streaming = false, error = event.message)
                                ui.updateMessage(assistantIndex, assistant)
                                ui.setError(event.message)
                            }
                            else -> Unit
                        }
                    }
                    if (failure == null) break
                    val failed = failedEvent
                    val offloadCount = failed?.offloadImages
                    if (failed != null && offloadCount != null && ImageOffload.isRequired(failed.code)) {
                        if (imageOffloadRetries >= ImageOffload.MAX_RETRIES || deps.stop.requested) {
                            deps.log(
                                "image-offload: retry budget exhausted ($imageOffloadRetries/${ImageOffload.MAX_RETRIES}); " +
                                    "preserving the request error",
                            )
                            break
                        }
                        // 会话面 = 界面消息去掉本轮还没落库的 assistant 占位
                        val targets = ImageOffload.selectOldest(ui.messages().dropLast(1), offloadCount)
                        if (targets.isEmpty()) {
                            deps.log("image-offload: no retained image left to offload; preserving the request error")
                            break
                        }
                        val offloadSeq = withContext(Dispatchers.IO) { session.appendImageOffload(targets) }
                        if (offloadSeq == null) {
                            deps.log("image-offload: session rejected the selection; preserving the request error")
                            break
                        }
                        ui.applyImageOffload(targets)
                        imageOffloadRetries++
                        deps.log(
                            "image-offload: offloaded ${targets.sumOf { it.imageIndexes.size }} image(s) " +
                                "from ${targets.size} message(s) seq=$offloadSeq retry=$imageOffloadRetries",
                        )
                        ui.setError(null)
                        restartAttempt()
                        continue
                    }
                    if (failed == null || !Compaction.isContextOverflow(failed.code, failed.message)) break
                    if (overflowRetries >= Compaction.MAX_OVERFLOW_RETRIES || deps.stop.requested) {
                        deps.log(
                            "context-overflow: retry budget exhausted ($overflowRetries/${Compaction.MAX_OVERFLOW_RETRIES}); preserving the request error",
                        )
                        break
                    }
                    overflowRetries++
                    deps.log(
                        "context-overflow: request failed code=${failed.code ?: "-"} retry=$overflowRetries/${Compaction.MAX_OVERFLOW_RETRIES}; compacting the whole surface",
                    )
                    ui.setStatus("上下文超限，正在压缩…")
                    ui.setError(null)
                    // 会话面 = 界面消息去掉本轮还没落库的 assistant 占位
                    val replaced = compactOverflowingSurface(
                        ui = ui,
                        session = session,
                        config = config,
                        turn = turnNumber,
                        surface = ui.messages().dropLast(1),
                    )
                    if (!replaced) {
                        deps.log("context-overflow: no surface progress; preserving the request error")
                        // 压缩没成：原来的错误照旧挂在占位消息上（压缩流程会清 status / error）
                        assistant = assistant.copy(streaming = false, error = failed.message)
                        ui.updateMessage(assistantIndex, assistant)
                        ui.setError(failed.message)
                        break
                    }
                    restartAttempt()
                }
                if (failure != null) {
                    withContext(Dispatchers.IO) { session.endStep() }
                    stepOpen = false
                    break
                }
                lastStopReason = stopReason

                render(force = true)
                assistant = assistant.copy(streaming = false)
                ui.updateMessage(assistantIndex, assistant)
                ui.setUsage(if (inputTokens + outputTokens > 0) "$inputTokens in / $outputTokens out" else null)
                // 数字版用量（M72）：客户端插件（鲸鱼娘挂件）按上游规则把 token 折成功德。
                if (inputTokens + outputTokens > 0) ui.setTokenUsage(inputTokens, outputTokens)

                val toolCalls = calls.values.sortedBy { it.index }.mapNotNull { c ->
                    if (c.name == null || c.id == null) null else ToolCall(c.id!!, c.name!!, c.args.toString().ifBlank { "{}" })
                }
                lastAssistantText = fullText.toString()
                lastAssistantReasoning = fullReasoning.toString().ifBlank { null }
                lastSignature = signature.toString()

                val assistantSeq = withContext(Dispatchers.IO) {
                    session.appendAssistantMessage(
                        text = lastAssistantText,
                        reasoning = lastAssistantReasoning,
                        signature = lastSignature.ifBlank { null },
                        toolCalls = toolCalls,
                        provider = provider,
                        model = config.model,
                    )
                }
                assistantPersisted = true
                pendingToolCalls.clear()
                pendingToolCalls.addAll(toolCalls)
                assistant = assistant.copy(seq = assistantSeq)
                ui.updateMessage(assistantIndex, assistant)
                toolCalls.forEach { call -> withContext(Dispatchers.IO) { session.appendToolCall(call) } }

                if (toolCalls.isEmpty()) {
                    // 后台任务完成通知：turn 不能在 inbox 还有通知时收口（上游语义）。
                    // 先收口本 step，再开下一个 step；通知在下一个 step 起点落成 user/message。
                    val arriving = drainJobNotices()
                    if (arriving.isNotEmpty() && stopReason != "max_tokens") {
                        queuedNotices += arriving
                        withContext(Dispatchers.IO) { session.endStep() }
                        stepOpen = false
                        ui.setStatus("收到后台任务通知…")
                        continue
                    }
                    withContext(Dispatchers.IO) { session.endStep() }
                    stepOpen = false
                    if (stopReason == "max_tokens") {
                        errorText = "达到 max_tokens 上限，输出被截断"
                        ui.setError(errorText)
                    }
                    break
                }

                // 执行工具，结果作为 user 消息里的 tool_result 继续
                // 收尾通知（deferContext）要先收集、等**全部** tool_result 写完之后再追加：
                // 上游把 additionalContexts 接在这一步的工具结果后面，不插进 tool_result 中间。
                val deferredNotices = ArrayList<xin.dshaa.core.tools.GoalNotice>()
                for (call in toolCalls) {
                    // M79（用户 2026-11-12「调用工具的时候点停止不能立刻停」）：
                    // 工具可能吞掉 CancellationException（内部 runCatching 包了 Throwable），
                    // 每进一个工具再显式确认一次「用户是否已经点了停止」。
                    if (deps.stop.requested) throw kotlinx.coroutines.CancellationException("stopped by user")
                    ui.setStatus("运行工具 ${call.name}…")
                    if (!deps.approval(call)) {
                        val denied = ToolResult.err("User denied this tool call.")
                        deps.onToolResult(call, denied.text, true)
                        val seq = withContext(Dispatchers.IO) { session.appendToolResult(call.id, denied.text, true) }
                        ui.appendMessage(
                            ChatMessage(
                                id = deps.newId(),
                                role = MessageRole.TOOL,
                                content = listOf(ContentBlock(BlockType.TEXT, text = denied.text)),
                                toolCallId = call.id,
                                seq = seq,
                            ),
                        )
                        pendingToolCalls.removeAll { it.id == call.id }
                        ui.setStatus("已拒绝")
                        continue
                    }
                    val args = parseArgs(call.arguments)
                    val result = deps.registry.execute(call.name, args, deps.toolContext)
                    deps.onToolResult(call, result.text, result.isError)
                    val resultSeq = withContext(Dispatchers.IO) {
                        session.appendToolResult(call.id, result.text, result.isError, result.images)
                    }
                    ui.appendMessage(
                        ChatMessage(
                            id = deps.newId(),
                            role = MessageRole.TOOL,
                            content = buildList {
                                add(ContentBlock(BlockType.TEXT, text = result.text))
                                result.images.forEach { add(it.toContentBlock()) }
                            },
                            toolCallId = call.id,
                            seq = resultSeq,
                        ),
                    )
                    pendingToolCalls.removeAll { it.id == call.id }
                    // goal 工具改完立刻刷新目标条（不等本轮结束）
                    if (call.name == "create_goal" || call.name == "update_goal" || call.name == "get_goal") {
                        ui.setGoal(withContext(Dispatchers.IO) { runCatching { deps.goals(session).current() }.getOrNull() })
                    }
                    result.notice?.let { deferredNotices += it }
                }
                // goal 收尾：落一条 source.kind="tool-goal" 的 user 通知，模型在同一 turn 里写收尾话。
                // 正文是内部指令，界面只显示「目标收尾」分隔条。
                deferredNotices.forEach { notice ->
                    val seq = withContext(Dispatchers.IO) {
                        session.appendUserMessage(
                            notice.text,
                            source = buildJsonObject {
                                put("kind", "tool-goal")
                                put("form", "notice")
                                put("summary", notice.summary)
                            },
                        )
                    }
                    ui.appendMessage(
                        ChatMessage(
                            id = deps.newId(),
                            role = MessageRole.USER,
                            content = listOf(ContentBlock(BlockType.TEXT, text = notice.text)),
                            sourceKind = "tool-goal",
                            sourceSummary = notice.summary,
                            seq = seq,
                        ),
                    )
                }
                withContext(Dispatchers.IO) { session.endStep() }
                stepOpen = false
                ui.setStatus("继续…")
            }
            withContext(Dispatchers.IO) { session.endTurn(if (errorText == null) "completed" else "error") }
            return when {
                errorText != null -> TurnOutcome.ERROR
                lastStopReason == "max_tokens" -> TurnOutcome.MAX_TOKENS
                else -> TurnOutcome.COMPLETED
            }
        } catch (e: CancellationException) {
            val partial = liveText
            withContext(NonCancellable) {
                // 只输出到思考就被停止时也要落库（否则这一轮在会话里凭空消失）；
                // 已经落过库的（工具执行中途停止）不要重复追加。
                if (!assistantPersisted && (partial.isNotEmpty() || liveReasoning.isNotEmpty())) {
                    val seq = withContext(Dispatchers.IO) {
                        session.appendAssistantMessage(
                            partial,
                            liveReasoning.ifBlank { null },
                            signature = liveSignature.ifBlank { null },
                            provider = provider,
                            model = config.model,
                        )
                    }
                    // 半截回复也回填 seq（下次自动压缩/分支能把它当会话面消息）
                    if (liveAssistantIndex in 0 until ui.messages().size) {
                        ui.updateMessage(
                            liveAssistantIndex,
                            ui.messages()[liveAssistantIndex].copy(seq = seq, streaming = false),
                        )
                    }
                }
                // 工具执行被打断（用户停止 / 切会话 / 关页面）：给已落库但没写结果的调用补一条
                // error tool_result。NonCancellable 里写，否则 withContext 会立刻再抛取消。
                // 已落过结果的不重复写（取消恰好发生在 appendToolResult 内部时靠 hasToolResult 去重）。
                pendingToolCalls.toList().forEach { call ->
                    if (runCatching { session.hasToolResult(call.id) }.getOrDefault(false)) return@forEach
                    runCatching { session.appendToolResult(call.id, INTERRUPTED_TOOL_RESULT, true) }
                }
                pendingToolCalls.clear()
                withContext(Dispatchers.IO) {
                    // 一轮必须收口：即使一个字都没流出来，step/turn 也要有 end（v4 事件平衡）
                    if (stepOpen) {
                        session.endStep()
                        stepOpen = false
                    }
                    session.endTurn("aborted")
                }
                // 用户 stop 正在跑的 goal 轮 → 下一 idle 点 pause（上游语义）；
                // 切会话/关页面这类无关取消只 disarm，持久阶段不变。
                runCatching {
                    val driver = GoalRoundDriver(deps.goals(session))
                    if (deps.stop.requested) driver.onInterrupted(input.goalRound) else driver.disarm()
                }
                ui.setGoal(runCatching { deps.goals(session).current() }.getOrNull())
            }
            throw e
        } finally {
            ui.setGoal(runCatching { deps.sessions.current()?.let { deps.goals(it).current() } }.getOrNull())
            deps.onTurnFinished()
            if (!input.wake && input.goalRound == null && session.events.none { it.type == "session/title" }) {
                withContext(Dispatchers.IO) {
                    session.appendTitle(SessionTitle.from(input.text), listOfNotNull(userSeq))
                }
            }
        }
    }

    private data class MutableCall(
        val index: Int,
        val id: String?,
        val name: String?,
        val args: StringBuilder = StringBuilder(),
    )

    /** 取走排队中的后台任务完成通知（宿主实现；取失败不能弄断一轮）。 */
    private fun drainJobNotices(): List<UserNotice> =
        runCatching { deps.jobNotices() }.getOrElse { emptyList() }

    /**
     * 把后台任务通知落成 `user/message`（上游 `source.kind:"tool-jobs", form:"notice"`）。
     * 正文是给模型的内部通知，界面按 [UserNotice.summary] 渲染成一条分隔条。
     */
    private suspend fun appendJobNotices(session: SessionSession, ui: TurnUi, notices: List<UserNotice>) {
        notices.forEach { notice ->
            val texts = notice.texts ?: listOf(notice.text)
            val seq = withContext(Dispatchers.IO) {
                session.appendUserMessageTexts(
                    texts,
                    source = notice.source ?: noticeSource(notice.kind, notice.summary),
                )
            }
            ui.appendMessage(noticeMessage(notice, texts).copy(seq = seq))
        }
    }

    private fun noticeSource(kind: String, summary: String): JsonObject = buildJsonObject {
        put("kind", kind)
        put("form", "notice")
        put("summary", summary)
    }

    private fun noticeMessage(notice: UserNotice, texts: List<String> = notice.texts ?: listOf(notice.text)): ChatMessage = ChatMessage(
        id = deps.newId(),
        role = MessageRole.USER,
        content = texts.map { ContentBlock(BlockType.TEXT, text = it) },
        sourceKind = notice.kind,
        sourceSummary = notice.summary,
    )

    private fun buildRequest(messages: List<ChatMessage>, config: LlmConfig, effort: String): ApiRequest {
        val apiMessages = ArrayList<ApiMessage>()
        // 运行时上下文（docs/port/02 §2.5）：是 user 消息，不是 system；抬头逐字固定。
        apiMessages += ApiMessage("user", listOf(ApiBlock.Text(deps.runtimeContext())))
        appendHistory(apiMessages, messages)
        // 历史里可能留着「有 tool_use 没 tool_result」的残缺轮次（旧版本停止/切会话留下的），
        // 服务端会整条请求 400；发之前补齐，已经补齐的不会重复动。
        repairToolPairs(apiMessages)
        // 计划模式：这次请求的 system 会带/不带 plan:policy，按发送时的选中状态记下来
        // （上游 request/header 时的 activeAtLastHeader；用来决定切换模式要不要给模型补 narration）。
        deps.plan.activeAtLastHeader = deps.plan.selected()
        val request = ApiRequest(
            model = config.model,
            stream = true,
            messages = apiMessages,
            maxTokens = config.maxTokens,
            // 上游 dsh-llm-deepseek serialize()：effort=off ⇒ thinking disabled 且**不发** output_config；
            // 其余 effort ⇒ thinking enabled + output_config.effort（docs/48 §推理强度）。
            thinking = ThinkingConfig(type = if (effort == "off") "disabled" else "enabled"),
            outputConfig = if (effort == "off") null else OutputConfig(effort = effort),
            system = deps.systemPrompt(),
            temperature = config.temperature,
            tools = deps.apiTools(),
        )
        deps.onRequest(++requestNo, request)
        return request
    }

    /**
     * 上下文压缩的摘要请求（M43，上游 `prepareCompaction()` + `summarizeCompaction()`）：
     * system 用当前拼装结果、工具用当前清单、messages = 被压缩区间的逐条投影，
     * 最后追一条固定指令（[Compaction.INSTRUCTION]）。
     *
     * 与上游的两处已知差别（docs/67）：原生没有「会话面里的 system/message」节点，
     * 也没有每次现拼的运行时上下文事件，所以 system 只出现一次、运行时上下文不进摘要请求。
     */
    fun buildCompactionRequest(shadowed: List<ChatMessage>, config: LlmConfig): ApiRequest {
        val apiMessages = ArrayList<ApiMessage>()
        appendHistory(apiMessages, shadowed)
        repairToolPairs(apiMessages)
        appendBlocks(apiMessages, "user", listOf(ApiBlock.Text(Compaction.INSTRUCTION)))
        val effort = deps.reasoningEffort()
        return ApiRequest(
            model = config.model,
            stream = true,
            messages = apiMessages,
            maxTokens = minOf(config.maxTokens, Compaction.MAX_SUMMARY_TOKENS),
            thinking = ThinkingConfig(type = if (effort == "off") "disabled" else "enabled"),
            outputConfig = if (effort == "off") null else OutputConfig(effort = effort),
            system = deps.systemPrompt(),
            temperature = config.temperature,
            tools = deps.apiTools(),
        )
    }

    /** 合并同角色相邻消息（上游 serialize() 的 content[] 逐块追加语义）。 */
    private fun appendBlocks(apiMessages: MutableList<ApiMessage>, role: String, blocks: List<ApiBlock>) {
        if (blocks.isEmpty()) return
        val last = apiMessages.lastOrNull()
        if (last != null && last.role == role) {
            apiMessages[apiMessages.lastIndex] = last.copy(content = last.content + blocks)
        } else {
            apiMessages += ApiMessage(role, blocks)
        }
    }

    /**
     * 跑一次摘要请求（M43 手工路径 / M44 自动压缩共用，上游 `summarizeCompaction()` 的
     * 直连 `ctx.llm.stream()`）：不经过轮次状态机、不落 turn/step 事件，不消耗模型轮次；
     * 只收文本（reasoning 进 `rawOutput`），失败抛异常由调用方落 `compaction/end{error}`。
     *
     * M48（上游 `compaction/summary-error` 瀑布）：摘要请求只含被压缩选区，它自己超图片预算时
     * 调 [recover]——在选区里卸载最老的图、落 `image/offload`、返回重新投影后的选区消息，
     * 然后**原地**重试摘要（同一个 compaction bracket，不新开 start/end）。
     * 没有可卸的图 / 调用方不接 / 重试预算用完 → 抛 [SummaryImageOffloadRequired]，
     * 按普通摘要失败收口（选区外的图不会被这次摘要动到）。
     */
    suspend fun summarizeCompaction(
        shadowed: List<ChatMessage>,
        config: LlmConfig,
        recover: (suspend (SummaryImageOffloadRequired) -> List<ChatMessage>?)? = null,
    ): Compaction.Summary {
        var messages = shadowed
        var attempts = 0
        while (true) {
            val request = buildCompactionRequest(messages, config)
            deps.onSummaryRequest(request)
            val required = ImageOffload.requiredCount(request, deps.imageBudget())
            try {
                if (required > 0) {
                    deps.log("compaction: summary request images exceed budget; offload=$required attempt=${attempts + 1}")
                    throw SummaryImageOffloadRequired(
                        count = required,
                        message = "request images exceed the route budget; " +
                            "$required more oldest occurrence(s) must be offloaded.",
                    )
                }
                return runSummaryRequest(request, config)
            } catch (failure: SummaryImageOffloadRequired) {
                val next = if (recover == null || attempts >= ImageOffload.MAX_RETRIES || deps.stop.requested) {
                    null
                } else {
                    recover(failure)
                }
                if (next == null) throw failure
                messages = next
                attempts += 1
            }
        }
    }

    /**
     * 一次摘要请求的流式收口（M43 的采集逻辑，M48 从 [summarizeCompaction] 里拆出来复用）。
     * 真路由报 `IMAGE_OFFLOAD_REQUIRED`（带张数）时映射成 [SummaryImageOffloadRequired]，
     * 与本地预算检查走同一个恢复钩子；其余失败仍是普通异常（调用方记 `compaction/end{error}`）。
     */
    private suspend fun runSummaryRequest(request: ApiRequest, config: LlmConfig): Compaction.Summary {
        val texts = sortedMapOf<Int, StringBuilder>()
        val reasoning = sortedMapOf<Int, StringBuilder>()
        var inputTokens = 0
        var outputTokens = 0
        var failedEvent: LlmEvent.Failed? = null
        deps.stream(request, config).collect { event ->
            when (event) {
                is LlmEvent.MessageStart -> inputTokens = event.inputTokens
                is LlmEvent.BlockStart -> if (event.type == "text") texts.getOrPut(event.index) { StringBuilder() }
                is LlmEvent.TextDelta -> texts.getOrPut(event.index) { StringBuilder() }.append(event.text)
                is LlmEvent.ThinkingDelta -> reasoning.getOrPut(event.index) { StringBuilder() }.append(event.text)
                is LlmEvent.MessageDelta -> outputTokens = event.outputTokens
                is LlmEvent.Failed -> failedEvent = event
                else -> Unit
            }
        }
        val failed = failedEvent
        if (failed != null) {
            val offloadCount = failed.offloadImages
            if (ImageOffload.isRequired(failed.code) && offloadCount != null) {
                throw SummaryImageOffloadRequired(offloadCount, failed.message)
            }
            throw IllegalStateException(failed.message)
        }
        val text = texts.values.joinToString("") { it.toString() }.trim()
        if (text.isEmpty()) throw IllegalStateException("摘要为空")
        val rawOutput = buildJsonArray {
            reasoning.forEach { (index, value) ->
                if (value.isNotEmpty()) {
                    add(buildJsonObject { put("type", "reasoning"); put("text", value.toString()); put("index", index) })
                }
            }
            texts.forEach { (index, value) ->
                add(buildJsonObject { put("type", "text"); put("text", value.toString()); put("index", index) })
            }
        }
        val usage = buildJsonObject {
            put("inputTokens", inputTokens)
            put("outputTokens", outputTokens)
            put("cacheReadTokens", 0)
            put("cacheWriteTokens", 0)
            put("totalTokens", inputTokens + outputTokens)
        }
        return Compaction.Summary(text, rawOutput, minOf(config.maxTokens, Compaction.MAX_SUMMARY_TOKENS), usage)
    }

    /**
     * 自动压缩（M44，上游 `agent/pre-step` 的 `compactIfNeeded("pressure")`）：
     * 每个 step 起点、组装请求之前估一次会话面令牌；越过阈值就把最早一段换成 checkpoint
     * （事件与手工路径同形，`turn` = 当前轮、没有 `sourceCommandId`，也没有 command/run/done）。
     *
     * 与上游一致的语义：窗口/输出配置不成立只警告一次并跳过；没有可压缩区间就什么都不写；
     * 摘要不比被顶掉的历史小算失败（写 `compaction/end{error}`），但本轮照常继续。
     * 区别：压力值用与会话面相同的启发式（[Compaction.estimateMessageTokens]），不是真实 token meter。
     */
    private suspend fun maybeAutoCompact(ui: TurnUi, session: SessionSession, config: LlmConfig, turn: Int) {
        compactSurface(ui, session, config, turn, CompactionTrigger.PRESSURE)
    }

    /**
     * 溢出恢复（M45）：模型已经确认超窗，跳过压力阈值、`retainTokens = 0`，把整个会话面
     * （最后一条除外）换成 checkpoint（上游 `compactIfNeeded("context-overflow")`）。
     *
     * 与压力路径的差别：不看窗口/阈值（窗口未知也压）、不保留最近一段；工具结果修剪（M46）
     * 两条路径都跑（上游溢出分支同样先 prune 再选区）。返回 true = 会话面真的被替换了，可以重发请求。
     */
    private suspend fun compactOverflowingSurface(
        ui: TurnUi,
        session: SessionSession,
        config: LlmConfig,
        turn: Int,
        surface: List<ChatMessage>,
    ): Boolean = compactSurface(ui, session, config, turn, CompactionTrigger.OVERFLOW, surface)

    /**
     * 工具结果修剪（M46，上游 `dsh-compaction-tool-result-pruner` 的 `pruneSession()`）：
     * 把文本超过预算（默认 8192 码点）的 tool 结果换成「头 4096 + [Compaction.PRUNE_MARKER] + 尾 1024」，
     * 位置不动（工具结果必须紧跟它的调用）。每条替换写两个事件：
     * `compaction/prune`（影子价，纯读取方据此减令牌）→ 新 `tool/result`（`surfaceOp:replace` 顶掉原位）。
     * 原事件留在 append-only 日志里，回放能找回原始输入。
     *
     * @return 修剪后的会话面（seq 已换成新事件）；null = 一条都没剪（此时一个字节都不写）。
     */
    private suspend fun pruneToolResults(
        ui: TurnUi,
        session: SessionSession,
        surface: List<ChatMessage>,
        tag: String,
    ): List<ChatMessage>? {
        var prunedAny = false
        val result = ArrayList<ChatMessage>(surface.size)
        for (message in surface) {
            val seq = message.seq
            if (message.role != MessageRole.TOOL || seq == null) {
                result += message
                continue
            }
            val content = Compaction.pruneContent(message.content)
            if (content == null) {
                result += message
                continue
            }
            if (session.events.none { it.seq == seq && it.type == "tool/result" }) {
                deps.log("$tag: prune seq=$seq skipped (tool/result event missing)")
                result += message
                continue
            }
            val charsBefore = Compaction.measurePruneChars(message.content)
            val charsAfter = Compaction.measurePruneChars(content)
            val shadowedTokens = Compaction.estimateMessageTokens(message)
            val pruneSeq = withContext(Dispatchers.IO) { session.appendCompactionPrune(seq, shadowedTokens) }
            val replacementSeq = withContext(Dispatchers.IO) { session.appendToolResultPruned(seq, content) }
            if (replacementSeq == null) {
                deps.log("$tag: prune seq=$seq replacement failed; keeping the original content")
                result += message
                continue
            }
            val replacement = message.copy(content = content, seq = replacementSeq)
            ui.applyToolResultPrune(seq, replacement)
            deps.log(
                "$tag: prune seq=$seq replacement=$replacementSeq chars=$charsBefore->$charsAfter pruneSeq=$pruneSeq",
            )
            result += replacement
            prunedAny = true
        }
        return if (prunedAny) result else null
    }

    private fun compactNodes(messages: List<ChatMessage>): List<Compaction.Node> = messages.map { m ->
        Compaction.Node(m.seq!!, Compaction.estimateMessageTokens(m), m.role == MessageRole.TOOL)
    }

    private suspend fun compactSurface(
        ui: TurnUi,
        session: SessionSession,
        config: LlmConfig,
        turn: Int,
        trigger: CompactionTrigger,
        surface: List<ChatMessage> = ui.messages(),
    ): Boolean {
        val tag = trigger.logTag
        val window = deps.contextWindow() ?: 0
        val policy = Compaction.Policy.resolve(window, config.maxTokens, deps.compactionRatio())
        if (trigger == CompactionTrigger.PRESSURE && policy == null) {
            val key = "${config.model}|$window|${config.maxTokens}"
            if (warnedCompactionTargets.add(key)) {
                deps.log(
                    "$tag: disabled model=${config.model} window=$window maxTokens=${config.maxTokens} (window must exceed output + headroom)",
                )
            }
            return false
        }
        var visible = surface
        if (visible.isEmpty()) return false
        // 会话面必须与界面一一对应：还没落库的消息（seq 为空）不能进选区，跳过本次
        if (visible.any { it.seq == null }) {
            deps.log("$tag: skip unpersisted messages=${visible.count { it.seq == null }}")
            return false
        }
        var nodes = compactNodes(visible)
        var total = nodes.sumOf { it.tokens }
        if (trigger == CompactionTrigger.PRESSURE && policy != null && total < policy.thresholdTokens) return false

        // 工具结果修剪（M46）：上游在「压力确认之后、选区之前」先剪再重新量；
        // 剪完低于阈值就跳过摘要（README：trimming may relieve enough token pressure to skip summarization）。
        val prunedSurface = pruneToolResults(ui, session, visible, tag)
        if (prunedSurface != null) {
            visible = prunedSurface
            nodes = compactNodes(visible)
            total = nodes.sumOf { it.tokens }
            if (trigger == CompactionTrigger.PRESSURE && policy != null && total < policy.thresholdTokens) {
                deps.log(
                    "$tag: pruning relieved the pressure total=$total threshold=${policy.thresholdTokens}; skipping summary",
                )
                return false
            }
        }
        val retainTokens = if (trigger == CompactionTrigger.OVERFLOW) 0 else policy?.retainTokens ?: 0
        val range = Compaction.selectRange(nodes, retainTokens)
        if (range == null) {
            deps.log("$tag: no compactable range total=$total threshold=${policy?.thresholdTokens ?: "-"}")
            return false
        }
        val shadowed = visible.subList(range.first, range.last + 1)
        val shadowedSeqs = nodes.subList(range.first, range.last + 1).map { it.seq }
        var shadowedMessages = shadowed
        var shadowedTokens = nodes.subList(range.first, range.last + 1).sumOf { it.tokens }
        val compactionId = deps.newId()
        ui.setStatus("正在压缩…")
        deps.log(
            "$tag: start id=$compactionId turn=$turn total=$total threshold=${policy?.thresholdTokens ?: "-"} " +
                "range=${shadowedSeqs.first()}..${shadowedSeqs.last()} items=${shadowedSeqs.size} " +
                "tokens=$shadowedTokens keep=${nodes.size - shadowedSeqs.size}",
        )
        val startSeq = withContext(Dispatchers.IO) { session.appendCompactionStart(compactionId, null, turn) }
        val surfaceSeqs = nodes.map { it.seq }
        val summary = try {
            summarizeCompaction(shadowedMessages, config) { failure ->
                // M48（上游 `compaction/summary-error`）：只在本选区里卸，落 image/offload，
                // 更新界面与本地投影，然后同一个 bracket 内重试摘要。
                if (deps.stop.requested) return@summarizeCompaction null
                val targets = ImageOffload.selectOldest(shadowedMessages, failure.count)
                if (targets.isEmpty()) {
                    deps.log("$tag: summary image-offload: no retained image left in the region")
                    return@summarizeCompaction null
                }
                val offloadSeq = withContext(Dispatchers.IO) { session.appendImageOffload(targets) }
                if (offloadSeq == null) {
                    deps.log("$tag: summary image-offload: session rejected the selection")
                    return@summarizeCompaction null
                }
                ui.applyImageOffload(targets)
                shadowedMessages = ImageOffload.markOffloaded(shadowedMessages, targets)
                val before = shadowedTokens
                shadowedTokens = shadowedMessages.sumOf { Compaction.estimateMessageTokens(it) }
                deps.log(
                    "$tag: summary image-offload: offloaded ${targets.sumOf { it.imageIndexes.size }} image(s) " +
                        "from ${targets.size} message(s) seq=$offloadSeq tokens=$before->$shadowedTokens",
                )
                shadowedMessages
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                runCatching { session.appendCompactionEnd(compactionId, null, Compaction.CANCELLED_TEXT, turn) }
            }
            throw e
        } catch (e: Exception) {
            val text = e.message ?: e.toString()
            withContext(Dispatchers.IO) { session.appendCompactionEnd(compactionId, null, text, turn) }
            ui.setStatus(null)
            deps.log("$tag: failed ${text.take(120)}")
            return false
        }
        val framed = Compaction.frameSummary(summary.text)
        val framedTokens = Compaction.estimateContentTokens(framed.map { ContentBlock(BlockType.TEXT, text = it) }) +
            Compaction.BLOCK_OVERHEAD
        if (framedTokens >= shadowedTokens) {
            withContext(Dispatchers.IO) { session.appendCompactionEnd(compactionId, null, Compaction.NO_SUMMARY_TEXT, turn) }
            ui.setStatus(null)
            deps.log("$tag: summary not smaller framed=$framedTokens shadowed=$shadowedTokens")
            return false
        }
        // 摘要期间用户又落了消息（理论上同一协程内不会发生）：区间已不是同一个面，放弃替换
        if (ui.messages().mapNotNull { it.seq } != surfaceSeqs) {
            withContext(Dispatchers.IO) { session.appendCompactionEnd(compactionId, null, Compaction.CHANGED_TEXT, turn) }
            ui.setStatus(null)
            deps.log("$tag: surface changed during summary")
            return false
        }
        val summarySeq = withContext(Dispatchers.IO) {
            session.appendCompactionSummary(
                compactionId = compactionId,
                sourceCommandId = null,
                summary = summary.text,
                rawOutput = summary.rawOutput,
                shadowedRange = shadowedSeqs.first()..shadowedSeqs.last(),
                shadowedSeqs = shadowedSeqs,
                shadowedTokenCount = shadowedTokens,
                provider = deps.providerLabel(),
                model = config.model,
                maxTokens = summary.maxTokens,
                usage = summary.usage,
            )
        }
        val checkpointSeq = withContext(Dispatchers.IO) {
            session.appendCompactionCheckpoint(
                compactionId = compactionId,
                sourceCommandId = null,
                texts = framed,
                startSeq = shadowedSeqs.first(),
                endSeq = shadowedSeqs.last(),
                sourceEventSeqs = listOf(startSeq, summarySeq) + shadowedSeqs,
            )
        }
        withContext(Dispatchers.IO) { session.appendCompactionEnd(compactionId, null, turn = turn) }
        val checkpoint = ChatMessage(
            id = deps.newId(),
            role = MessageRole.USER,
            content = framed.map { ContentBlock(BlockType.TEXT, text = it) },
            seq = checkpointSeq,
            sourceKind = Compaction.SOURCE_KIND,
            sourceSummary = "已压缩 ${shadowedSeqs.size} 条历史记录（约 $shadowedTokens tokens）",
        )
        ui.applyCompaction(shadowedSeqs, checkpoint)
        ui.setStatus(null)
        deps.log(
            "$tag: done ${Compaction.resultText(shadowedSeqs.size, shadowedTokens)} checkpointSeq=$checkpointSeq",
        )
        return true
    }

    /**
     * 请求体修复：assistant 的每个 `tool_use` 后面必须紧跟同一批 `tool_result`。
     * 历史里残缺的配对（工具执行被打断的旧事故）在这里补一条 error 结果——
     * 只影响本次请求，不改会话文件；已经配好对的消息原样跳过。
     */
    private fun repairToolPairs(apiMessages: MutableList<ApiMessage>) {
        var i = 0
        while (i < apiMessages.size) {
            val message = apiMessages[i]
            if (message.role != "assistant") {
                i += 1
                continue
            }
            val ids = message.content.filterIsInstance<ApiBlock.ToolUse>().map { it.id }
            if (ids.isEmpty()) {
                i += 1
                continue
            }
            val next = apiMessages.getOrNull(i + 1)
            val answered = next?.content.orEmpty()
                .filterIsInstance<ApiBlock.ToolResult>()
                .mapTo(HashSet()) { it.toolUseId }
            val missing = ids.filterNot { it in answered }
            if (missing.isEmpty()) {
                i += 1
                continue
            }
            val synthetic = missing.map {
                ApiBlock.ToolResult(
                    toolUseId = it,
                    content = listOf(ApiBlock.Text(INTERRUPTED_TOOL_RESULT)),
                    isError = true,
                )
            }
            if (next != null && next.role == "user" && next.content.any { it is ApiBlock.ToolResult }) {
                // 同一批的一部分已经答过：把缺的补在这个 user 消息最前面
                apiMessages[i + 1] = next.copy(content = synthetic + next.content)
            } else {
                apiMessages.add(i + 1, ApiMessage("user", synthetic))
            }
            i += 2
        }
    }

    /** 这条调用的 tool_result 是否已经落在会话里（取消收尾去重）。 */
    private fun SessionSession.hasToolResult(callId: String): Boolean = events.any { event ->
        if (event.type != "tool/result") return@any false
        val message = runCatching { event.data?.jsonObject?.get("message")?.jsonObject }.getOrNull()
            ?: return@any false
        runCatching { message["toolCallId"]?.jsonPrimitive?.content }.getOrNull() == callId
    }

    /** 会话消息 → 请求 messages（请求体与摘要请求共用；不含运行时上下文抬头）。 */
    private fun appendHistory(apiMessages: MutableList<ApiMessage>, messages: List<ChatMessage>) {
        fun add(role: String, blocks: List<ApiBlock>) = appendBlocks(apiMessages, role, blocks)
        messages.forEach { m ->
            when (m.role) {
                MessageRole.USER -> {
                    // 一个 user 消息可以有多个 text 块（运行时上下文与首条输入合并、子代理初始任务等），
                    // 不能 join 成一条：请求体按 content[] 逐块发（契约 #1）。
                    // 用户附件（docs/42）在 text 之后逐张展开成「句柄文本 + base64 image」，
                    // 与上游 serialize() 的 user/tool image 分支同形。
                    val blocks = ArrayList<ApiBlock>()
                    m.content.forEach { b ->
                        when (b.type) {
                            BlockType.TEXT -> if (b.text.isNotBlank()) blocks += ApiBlock.Text(b.text)
                            BlockType.IMAGE -> blocks += imageApiBlocks(b)
                            else -> Unit
                        }
                    }
                    if (blocks.isNotEmpty()) add("user", blocks)
                }
                MessageRole.ASSISTANT -> {
                    val blocks = ArrayList<ApiBlock>()
                    m.content.forEach { b ->
                        when (b.type) {
                            // 契约 #1：reasoning 必须原样回传（thinking + signature），否则多轮里模型看不到自己的思考
                            BlockType.REASONING -> if (b.text.isNotBlank() && b.signature.isNotBlank()) {
                                blocks += ApiBlock.Thinking(b.text, b.signature)
                            }
                            BlockType.TEXT -> if (b.text.isNotBlank()) blocks += ApiBlock.Text(b.text)
                            BlockType.TOOL_CALL -> {
                                val id = b.toolCallId ?: return@forEach
                                val name = b.name ?: return@forEach
                                blocks += ApiBlock.ToolUse(id, name, parseArgs(b.arguments))
                            }
                            else -> Unit
                        }
                    }
                    if (blocks.isNotEmpty()) add("assistant", blocks)
                }
                MessageRole.TOOL -> {
                    val id = m.toolCallId ?: return@forEach
                    val blocks = ArrayList<ApiBlock>()
                    m.content.forEach { b ->
                        when (b.type) {
                            BlockType.TEXT -> if (b.text.isNotEmpty()) blocks += ApiBlock.Text(b.text)
                            // 图片 = 句柄文本 + base64 image 块（与上游 dsh-llm-deepseek serialize() 的 image 分支同形）
                            BlockType.IMAGE -> blocks += imageApiBlocks(b)
                            else -> Unit
                        }
                    }
                    add("user", listOf(ApiBlock.ToolResult(id, blocks)))
                }
                else -> Unit
            }
        }
    }

    /** 一个 step 起点的计划模式推进结果：[changed] 落了新的 `plan/mode`；[narration] 要给模型的切换通知。 */
    private data class PlanStep(val changed: Boolean, val narration: String?, var narrationSeq: Long? = null)

    /**
     * 在 step 起点应用 pending 选择（上游 `dsh-plan-mode` 的 `agent/pre-step`）：
     * 落 `plan/mode`；只有「用户切换」且上一次请求描述的是另一态时才 narrate
     * （`exit_plan_mode` 的切换 narrate=false，工具结果已经叙述过）。
     */
    private fun applyPendingPlan(session: SessionSession): PlanStep {
        val target = deps.plan.pending ?: return PlanStep(false, null)
        deps.plan.pending = null
        val narrate = deps.plan.narrate
        deps.plan.narrate = true
        if (target == deps.plan.active) return PlanStep(false, null)
        session.appendPlanMode(target)
        val told = deps.plan.activeAtLastHeader
        deps.plan.active = target
        if (target) deps.plan.entered = true
        val narration = if (narrate && told != null && told != target) {
            if (target) PLAN_ON_NOTICE else PLAN_OFF_NOTICE
        } else {
            null
        }
        return PlanStep(changed = true, narration = narration)
    }

    private fun parseArgs(raw: String): JsonObject =
        runCatching { Json.parseToJsonElement(raw.ifBlank { "{}" }) as JsonObject }.getOrElse { buildJsonObject { } }

    /**
     * image 块 → 请求体里的「句柄文本 + base64 图」（user 与 tool_result 共用）。
     * 附件字节丢了就整块跳过（上游对坏附件的降级语义），不阻断这一轮请求。
     *
     * M47：被 `image/offload` 选中的出现位置只发占位文本（附件名 + 只读路径），不再发图；
     * 这是永久的（换更大预算 / 压缩后也不会自动恢复，与上游一致）。
     */
    private fun imageApiBlocks(block: ContentBlock): List<ApiBlock> {
        val ref = block.toImageRef() ?: return emptyList()
        if (ref.offloaded) {
            return listOf(ApiBlock.Text(ImageOffload.placeholderText(ref, deps.imagePath(ref.attachmentId))))
        }
        val bytes = deps.imageBytes(ref.attachmentId) ?: return emptyList()
        return listOf(
            ApiBlock.Text(imageHandleText(ref)),
            ApiBlock.Image(
                ImageSource(
                    mediaType = ref.mediaType,
                    data = Base64.getEncoder().encodeToString(bytes),
                ),
            ),
        )
    }

    /** 上游 `requestImageHandleText`（没有归一化副本路径时的分支）：给模型一句可引用的图片句柄。 */
    private fun imageHandleText(ref: ImageRef): String {
        val identity = ref.name?.let { "\"$it\" (${ref.attachmentId})" } ?: ref.attachmentId
        return "Image $identity; request preview ${ref.width}x${ref.height}px. " +
            "It may be resized or re-encoded; source dimensions, format, and byte size may differ."
    }
}
