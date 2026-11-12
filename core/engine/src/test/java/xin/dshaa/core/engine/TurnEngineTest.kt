package xin.dshaa.core.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import xin.dshaa.core.llm.LlmConfig
import xin.dshaa.core.llm.LlmEvent
import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.ChatMessage
import xin.dshaa.core.model.ContentBlock
import xin.dshaa.core.model.GoalPhase
import xin.dshaa.core.model.GoalView
import xin.dshaa.core.model.ImageOffloadTarget
import xin.dshaa.core.model.ImageRef
import xin.dshaa.core.model.MessageRole
import xin.dshaa.core.model.markOffloadedImages
import xin.dshaa.core.model.toContentBlock
import xin.dshaa.core.model.toImageRef
import xin.dshaa.core.protocol.ApiRequest
import xin.dshaa.core.protocol.ApiTool
import xin.dshaa.core.protocol.ContentBlock as ApiBlock
import xin.dshaa.core.session.GoalActivationState
import xin.dshaa.core.session.GoalStore
import xin.dshaa.core.session.SessionCodec
import xin.dshaa.core.session.SessionRepository
import xin.dshaa.core.session.SessionSession
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.GoalNotice
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolRegistry
import xin.dshaa.core.tools.ToolResult
import java.io.File
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M46 工具结果修剪用的大输出：头 5000 个 H、唯一中段、25000 个 M、尾 1024 个 T（31038 码点，≈7768 令牌）。 */
private val BIG_TOOL_OUTPUT = "H".repeat(5_000) + "MIDDLE-UNIQUE-" + "M".repeat(25_000) + "T".repeat(1_024)

/**
 * `core:engine` 的行为锁定：把 `runTurn` / 续轮循环从 App 搬出来以后，
 * 事件顺序、请求体形状、取消落库、goal 续轮、收尾通知顺序必须与抽出前一致。
 *
 * 用假 LLM（脚本化 SSE 事件）+ 假工具 + 临时目录里的真 v4 会话跑，不碰 Android。
 */
class TurnEngineTest {

    private lateinit var root: File
    private lateinit var repo: SessionRepository
    private lateinit var gateway: TestGateway
    private lateinit var ui: TestUi
    private lateinit var activation: GoalActivationState
    private val responses = ArrayDeque<List<LlmEvent>>()
    private val requests = mutableListOf<ApiRequest>()
    private var toolResult: () -> ToolResult = { ToolResult.ok("TOOL_OUTPUT") }
    private var approve = true
    private var streamOverride: ((ApiRequest) -> Flow<LlmEvent>)? = null
    private var idSeq = 0
    private val logs = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("turn-engine-test").toFile()
        repo = SessionRepository(File(root, "sessions"))
        gateway = TestGateway(repo, File(root, "workspace").apply { mkdirs() })
        ui = TestUi()
        activation = GoalActivationState()
    }

    // ---------------- 用例 ----------------

    @Test
    fun plainTurnWritesBalancedEvents() = runBlocking {
        responses += textResponse("hello")
        engine().startTurn(ui, TurnInput("hi"))

        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        assertEquals(
            listOf(
                "request/header",
                "turn/start",
                "step/start",
                "user/message",
                "assistant/message",
                "step/end",
                "turn/end",
                "session/title",
            ),
            session.events.map { it.type },
        )
        assertEquals("completed", turnEndKind(session))

        // 请求体：运行时上下文与用户输入是**同一条** user 消息里的两个 text 块
        // （与真实夹具 parity/fixtures/device-smoke 一致：role=user, content=[text,text]）
        assertEquals(1, requests.size)
        val body = requests[0]
        assertEquals("deepseek-flash", body.model)
        assertEquals("SYS", body.system)
        val firstUser = body.messages.first().content.filterIsInstance<ApiBlock.Text>()
        assertEquals("CTX", firstUser[0].text)
        assertEquals("hi", firstUser[1].text)

        // UI：user + assistant 两条，assistant 是最终文本
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), ui.messages.map { it.role })
        assertEquals("hello", ui.messages[1].content.first().text)
        assertNull(ui.lastError)
    }

    @Test
    fun toolLoopContinuesInSameTurn() = runBlocking {
        responses += toolCallResponse("call-1", "fake_tool", "{\"q\":1}")
        responses += textResponse("done")
        engine().startTurn(ui, TurnInput("hi"))

        assertEquals(2, requests.size)
        // 第二次请求：最后一条 user 消息里是 tool_result
        val toolResultBlock = requests[1].messages.last().content.filterIsInstance<ApiBlock.ToolResult>().single()
        assertEquals("call-1", toolResultBlock.toolUseId)
        assertEquals("TOOL_OUTPUT", (toolResultBlock.content!!.first() as ApiBlock.Text).text)

        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        assertEquals(1, session.events.count { it.type == "turn/start" })
        assertEquals(2, session.events.count { it.type == "step/start" })
        assertEquals(2, session.events.count { it.type == "step/end" })
        assertEquals(1, session.events.count { it.type == "tool/call" })
        assertEquals(1, session.events.count { it.type == "tool/result" })
        // tool/result 必须早于第二次 assistant
        val seqToolResult = session.events.first { it.type == "tool/result" }.seq!!
        val seqSecondAssistant = session.events.filter { it.type == "assistant/message" }.last().seq!!
        assertTrue(seqToolResult < seqSecondAssistant)
        // 请求体里工具 schema 原样带上
        assertEquals("fake_tool", requests[0].tools!!.single().name)
    }

    @Test
    fun toolLoopHasNoRoundCap() = runBlocking {
        // 用户 2026-11-12：长任务干一半自己停。根因是单轮 12 步上限，到顶静默退出。
        // 这里连做 15 轮工具调用，必须在同一轮里继续跑到模型自己收尾。
        repeat(15) { i ->
            responses += toolCallResponse("call-$i", "fake_tool", "{\"i\":$i}")
        }
        responses += textResponse("done-after-15")

        engine().startTurn(ui, TurnInput("hi"))

        assertEquals(16, requests.size)
        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        assertEquals(16, session.events.count { it.type == "step/start" })
        assertEquals(15, session.events.count { it.type == "tool/call" })
        assertEquals(15, session.events.count { it.type == "tool/result" })
        assertEquals("completed", turnEndKind(session))
        assertEquals("done-after-15", ui.messages.last().content.first().text)
    }

    @Test
    fun deniedToolBecomesErrorResult() = runBlocking {
        approve = false
        responses += toolCallResponse("call-1", "fake_tool", "{}")
        responses += textResponse("ok")
        engine().startTurn(ui, TurnInput("hi"))

        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        val result = SessionCodec.messagesOf(session.events).last { it.role == MessageRole.TOOL }
        assertEquals("User denied this tool call.", result.content.first().text)
        assertTrue(result.isError)
        assertEquals(2, requests.size)
    }

    @Test
    fun goalRoundContinuesThenBlocksOnRoundLimit() = runBlocking {
        // 先把会话建出来再写目标：max=1，人类一轮之后再自动续 1 轮，然后 round-limit
        val session = gateway.create()
        val store = GoalStore(session, activation = activation)
        store.create("do the thing", maxGoalRounds = 1)

        responses += textResponse("human turn")
        responses += textResponse("goal round")
        engine().startTurn(ui, TurnInput("hi"))

        // 两个 turn：人类 + 自动续轮
        val reloaded = assertNotNull(repo.load(session.header.id))
        assertEquals(2, reloaded.events.count { it.type == "turn/start" })
        val userSources = reloaded.events
            .filter { it.type == "user/message" }
            .map { it.data!!.jsonObject["source"]!!.jsonObject["kind"]!!.jsonPrimitive.contentOrNull }
        assertEquals(listOf("user", "goal"), userSources)
        // goal 轮提示词逐字节（round 1/1）
        val goalText = SessionCodec.messagesOf(reloaded.events).last { it.role == MessageRole.USER }.content.first().text
        assertTrue(goalText.startsWith("<goal_round>\nObjective: \"do the thing\"\nRound: 1/1"))

        // 轮次用尽 → driver 自己 block，不是工具的 3 轮阈值
        val blocked = GoalStore(reloaded, activation = GoalActivationState()).current()!!
        assertEquals(GoalPhase.BLOCKED, blocked.goal.phase)
        assertEquals("round-limit", blocked.goal.blockedReason!!.code)
        assertEquals(1, blocked.roundsStarted)
    }

    @Test
    fun wrapupNoticeAppendedAfterAllToolResults() = runBlocking {
        toolResult = {
            ToolResult.ok("OUT", notice = GoalNotice("WRAP_BODY", "complete: objective"))
        }
        responses += toolCallResponse("call-1", "fake_tool", "{}")
        responses += textResponse("wrap up")
        engine().startTurn(ui, TurnInput("hi"))

        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        val seqToolResult = session.events.first { it.type == "tool/result" }.seq!!
        val notice = session.events.last { it.type == "user/message" }
        assertTrue(seqToolResult < notice.seq!!, "收尾通知必须排在 tool/result 之后")
        val source = notice.data!!.jsonObject["source"]!!.jsonObject
        assertEquals("tool-goal", source["kind"]!!.jsonPrimitive.contentOrNull)
        assertEquals("complete: objective", source["summary"]!!.jsonPrimitive.contentOrNull)

        // UI 里也有一条 tool-goal 用户消息（渲染成「目标收尾」分隔条）
        assertEquals("tool-goal", ui.messages.first { it.role == MessageRole.USER && it.sourceKind == "tool-goal" }.sourceKind)
    }

    @Test
    fun cancellationPersistsPartialAssistantAndClosesTurn() = runBlocking {
        val emitted = CompletableDeferred<Unit>()
        streamOverride = {
            flow {
                emit(LlmEvent.MessageStart("m", "deepseek-flash", 1))
                emit(LlmEvent.BlockStart(0, "text", null, null))
                emit(LlmEvent.TextDelta(0, "half "))
                emitted.complete(Unit)
                awaitCancellation()
            }
        }
        val engine = engine()
        val job = launch(Dispatchers.Default) { engine.startTurn(ui, TurnInput("hi")) }
        emitted.await()
        job.cancelAndJoin()

        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        val assistant = SessionCodec.messagesOf(session.events).last { it.role == MessageRole.ASSISTANT }
        assertEquals("half ", assistant.content.first().text)
        assertEquals("aborted", turnEndKind(session))
        assertEquals(1, session.events.count { it.type == "step/end" })
    }

    @Test
    fun orphanToolUseInHistoryIsRepairedInRequest() = runBlocking {
        // 旧事故（用户 2026-11-08 截图）：assistant 落了 tool_use，工具结果没写就切走会话。
        // 历史里留下一条只有 tool_use 的 assistant，之后每次请求都被服务端 400 拒绝。
        ui.messages += ChatMessage(
            id = "old-assistant",
            role = MessageRole.ASSISTANT,
            content = listOf(
                ContentBlock(
                    BlockType.TOOL_CALL,
                    toolCallId = "call-orphan",
                    name = "fake_tool",
                    arguments = "{}",
                ),
            ),
        )
        ui.messages += ChatMessage(
            id = "old-user",
            role = MessageRole.USER,
            content = listOf(ContentBlock(BlockType.TEXT, text = "上一条用户消息")),
        )
        responses += textResponse("ok")
        engine().startTurn(ui, TurnInput("hi"))

        val body = requests.single()
        val assistantIndex = body.messages.indexOfFirst { message ->
            message.content.any { it is ApiBlock.ToolUse && it.id == "call-orphan" }
        }
        assertTrue(assistantIndex >= 0, "原来的 assistant tool_use 应保留：${body.messages}")
        val next = body.messages[assistantIndex + 1]
        assertEquals("user", next.role)
        val repaired = next.content.filterIsInstance<ApiBlock.ToolResult>().firstOrNull { it.toolUseId == "call-orphan" }
        val result = assertNotNull(repaired, "缺的 tool_result 必须补在 assistant 后面：${body.messages}")
        assertEquals(true, result.isError)
        assertEquals(INTERRUPTED_TOOL_RESULT, (result.content!!.first() as ApiBlock.Text).text)
        // 后面的用户消息还在（没有被顶掉）
        assertTrue(requests.single().allText().contains("上一条用户消息"))
    }

    @Test
    fun cancellingWhileToolRunsPersistsSyntheticToolResult() = runBlocking {
        val approvalGate = CompletableDeferred<Boolean>()
        responses += toolCallResponse("call-1", "fake_tool", "{}")
        val engine = engine(approval = { approvalGate.await() })
        val job = launch(Dispatchers.Default) { engine.startTurn(ui, TurnInput("hi")) }

        // 等 assistant（带 tool_use）落库，再模拟「切会话/停止」把这一轮取消
        withTimeout(5_000) {
            while (gateway.session?.events?.none { it.type == "assistant/message" } != false) delay(10)
        }
        job.cancelAndJoin()

        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        val result = session.events.lastOrNull { it.type == "tool/result" }
        val persisted = assertNotNull(result, "取消后必须补一条 tool/result：${session.events.map { it.type }}")
        val message = persisted.data!!.jsonObject["message"]!!.jsonObject
        assertEquals("call-1", message["toolCallId"]!!.jsonPrimitive.content)
        assertEquals(true, message["isError"]!!.jsonPrimitive.booleanOrNull)
        val text = message["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(INTERRUPTED_TOOL_RESULT, text)
        assertEquals("aborted", turnEndKind(session))
        // 补的结果在 turn/end 之前、close 之后
        assertTrue(persisted.seq!! < session.events.last { it.type == "turn/end" }.seq!!)
    }

    @Test
    fun maxTokensStopsContinuationAndDisarms() = runBlocking {
        val session = gateway.create()
        val store = GoalStore(session, activation = activation)
        store.create("do the thing", maxGoalRounds = 5)
        responses += listOf(
            LlmEvent.MessageStart("m", "deepseek-flash", 1),
            LlmEvent.BlockStart(0, "text", null, null),
            LlmEvent.TextDelta(0, "truncated"),
            LlmEvent.MessageDelta("max_tokens", 3),
            LlmEvent.MessageStop,
        )
        engine().startTurn(ui, TurnInput("hi"))

        val reloaded = assertNotNull(repo.load(session.header.id))
        assertEquals(1, reloaded.events.count { it.type == "turn/start" })
        assertEquals("error", turnEndKind(reloaded))
        // 超 token 只 disarm，不自动续轮、不改目标阶段
        val view = GoalStore(reloaded, activation = activation).current()!!
        assertEquals(GoalPhase.ACTIVE, view.goal.phase)
        assertEquals("disarmed", view.activation)
    }

    @Test
    fun jobNoticeIsInjectedBeforeTurnEndAndContinuesTheTurn() = runBlocking {
        var drains = 0
        responses += textResponse("first")
        responses += textResponse("noticed")
        engine(
            jobNotices = {
                // 第 1 次 drain 在第一个 step 起点（还没通知）；第一个 assistant 出来后 drain 到通知
                drains++
                if (drains == 2) {
                    listOf(UserNotice("NOTICE_BODY", "bash echo hi [status: completed, exit code: 0]"))
                } else {
                    emptyList()
                }
            },
        ).startTurn(ui, TurnInput("hi"))

        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        assertEquals(1, session.events.count { it.type == "turn/start" })
        assertEquals(2, session.events.count { it.type == "step/start" })
        assertEquals(2, session.events.count { it.type == "step/end" })
        assertEquals(2, requests.size, "通知必须再要一次模型（turn 不能在 inbox 有通知时收口）")

        val assistantSeq = session.events.filter { it.type == "assistant/message" }.first().seq!!
        val notice = session.events.single { it.type == "user/message" && it.data!!.jsonObject["source"]!!.jsonObject["kind"]!!.jsonPrimitive.contentOrNull == "tool-jobs" }
        assertTrue(notice.seq!! > assistantSeq, "通知排在 assistant 之后")
        assertEquals("NOTICE_BODY", SessionCodec.messagesOf(listOf(notice)).single().content.first().text)
        assertEquals("completed", turnEndKind(session))
        assertTrue(ui.messages.any { it.role == MessageRole.USER && it.sourceKind == "tool-jobs" })
        // 第二次请求里带着通知正文
        val lastUserTexts = requests[1].messages.last().content.filterIsInstance<ApiBlock.Text>()
        assertEquals("NOTICE_BODY", lastUserTexts.last().text)
    }

    @Test
    fun wakeTurnUsesPreAppendedNoticeWithoutAppendingAnotherUserMessage() = runBlocking {
        val session = gateway.create()
        responses += textResponse("ok")
        engine().startTurn(
            ui,
            TurnInput("", notices = listOf(UserNotice("NOTICE_BODY", "bash echo hi [status: completed, exit code: 0]"))),
        )

        val reloaded = assertNotNull(repo.load(session.header.id))
        assertEquals(1, reloaded.events.count { it.type == "user/message" }, "wake 轮只写通知，不追加用户消息")
        assertEquals(1, reloaded.events.count { it.type == "turn/start" })
        assertEquals(0, reloaded.events.count { it.type == "session/title" }, "wake 轮不写会话标题")
        // 通知必须在 turn/start、step/start 之后（上游 inbox 在 step 起点 claim）
        val seqNotice = reloaded.events.single { it.type == "user/message" }.seq!!
        val seqTurnStart = reloaded.events.first { it.type == "turn/start" }.seq!!
        val seqStepStart = reloaded.events.first { it.type == "step/start" }.seq!!
        assertTrue(seqNotice > seqTurnStart && seqNotice > seqStepStart)
        // 请求体：运行时上下文 + 通知正文合并成同一条 user 消息
        val firstUser = requests[0].messages.first().content.filterIsInstance<ApiBlock.Text>()
        assertEquals("CTX", firstUser[0].text)
        assertEquals("NOTICE_BODY", firstUser[1].text)
        assertTrue(ui.messages.any { it.role == MessageRole.USER && it.sourceKind == "tool-jobs" })
    }

    @Test
    fun multiBlockTaskKeepsSeparateTextBlocks() = runBlocking {
        responses += textResponse("ok")
        // 子代理初始任务：prompt + 返回指导是两个 text 块，不能先 join 成一条
        engine().startTurn(ui, TurnInput("task", textBlocks = listOf("task", "GUIDE")))

        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        val user = SessionCodec.messagesOf(session.events).single { it.role == MessageRole.USER }
        assertEquals(listOf("task", "GUIDE"), user.content.map { it.text })
        // 请求体：运行时上下文 + 两个任务块合并成同一条 user 消息的三个 text 块
        val firstUser = requests[0].messages.first().content.filterIsInstance<ApiBlock.Text>()
        assertEquals(listOf("CTX", "task", "GUIDE"), firstUser.map { it.text })
        assertEquals(2, ui.messages.first().content.size)
        assertEquals("task", ui.currentTitle)
    }

    @Test
    fun subagentSettlementNoticeKeepsSourceAndBlocks() = runBlocking {
        responses += textResponse("ok")
        val source = buildJsonObject {
            put("kind", "subagent-settled")
            put("form", "notice")
            put("summary", "Summary")
            put("senderSessionId", "child-1")
        }
        engine().startTurn(
            ui,
            TurnInput(
                "",
                notices = listOf(
                    UserNotice(
                        text = "Summary\nIts closing message:\ndone",
                        summary = "Summary",
                        kind = "subagent-settled",
                        texts = listOf("Summary", "Its closing message:", "done"),
                        source = source,
                    ),
                ),
            ),
        )

        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        val event = session.events.single { it.type == "user/message" }.data!!.jsonObject
        assertEquals(source, event["source"])
        assertEquals(3, (event["content"] as kotlinx.serialization.json.JsonArray).size)
        val firstUser = requests[0].messages.first().content.filterIsInstance<ApiBlock.Text>()
        assertEquals(listOf("CTX", "Summary", "Its closing message:", "done"), firstUser.map { it.text })
    }

    @Test
    fun pendingPlanModeAppliesBeforeStepAndNarrates() = runBlocking {
        val plan = PlanModeState()
        plan.activeAtLastHeader = false // 上一次请求告诉模型的是「非计划模式」
        plan.pending = true            // 用户切到计划模式（轮次内排队）
        responses += textResponse("planned")

        engine(plan = plan).startTurn(ui, TurnInput("hi"))

        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        assertEquals(
            listOf(
                "request/header",
                "turn/start",
                "plan/mode",
                "step/start",
                "user/message",
                "user/message",
                "assistant/message",
                "step/end",
                "turn/end",
                "session/title",
            ),
            session.events.map { it.type },
        )
        // plan/mode 在 step/start **之前**（上游 agent/pre-step）
        val seqPlan = session.events.first { it.type == "plan/mode" }.seq!!
        val seqStep = session.events.first { it.type == "step/start" }.seq!!
        assertTrue(seqPlan < seqStep)
        assertEquals(
            true,
            session.events.first { it.type == "plan/mode" }.data!!.jsonObject["active"]!!.jsonPrimitive.booleanOrNull,
        )
        // narration（source.kind=plan-mode）排在真正的用户消息之前
        val users = SessionCodec.messagesOf(session.events).filter { it.role == MessageRole.USER }
        assertEquals(2, users.size)
        assertEquals("plan-mode", users[0].source?.get("kind")?.jsonPrimitive?.contentOrNull)
        assertEquals("notice", users[0].source?.get("form")?.jsonPrimitive?.contentOrNull)
        assertEquals(PLAN_ON_NOTICE, users[0].content.first().text)
        assertEquals("hi", users[1].content.first().text)
        // 请求体：system 走计划模式分支（测试脚手架按 selected() 返回 SYS-PLAN）
        assertEquals("SYS-PLAN", requests[0].system)
        assertEquals(true, plan.active)
        assertNull(plan.pending)
        assertTrue(plan.entered)
        // 导航条也有一行 plan-mode 通知
        assertTrue(ui.messages.any { it.role == MessageRole.USER && it.sourceKind == "plan-mode" })
    }

    @Test
    fun planSwitchSkipsNarrationWhenHeaderAlreadyToldIt() = runBlocking {
        val plan = PlanModeState()
        plan.activeAtLastHeader = true // 上一次请求已经说「在计划模式」，再切同一态不用 narrate
        plan.pending = true
        responses += textResponse("ok")

        engine(plan = plan).startTurn(ui, TurnInput("hi"))

        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        assertEquals(1, session.events.count { it.type == "plan/mode" })
        assertEquals(1, SessionCodec.messagesOf(session.events).count { it.role == MessageRole.USER })
    }

    @Test
    fun planPendingEqualToLoggedStateIsDropped() = runBlocking {
        val plan = PlanModeState()
        plan.pending = false // 已经是 false，选 false = 取消排队，不写 plan/mode
        responses += textResponse("ok")

        engine(plan = plan).startTurn(ui, TurnInput("hi"))

        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        assertEquals(0, session.events.count { it.type == "plan/mode" })
        assertEquals("SYS", requests[0].system)
    }

    // ---------------- 技能目录（M19） ----------------

    @Test
    fun skillCatalogNoticeIsPersistedAfterUserMessageAndBeforeRequest() = runBlocking {
        val catalog = UserNotice(
            text = "<system-reminder>\n<available_skills>\n- `demo`: 示例\n</available_skills>\n</system-reminder>",
            summary = "1 个技能",
            kind = "skill-catalog",
            source = buildJsonObject {
                put("kind", "skill-catalog")
                put("form", "catalog")
                put(
                    "entries",
                    buildJsonArray { add(buildJsonObject { put("name", "demo"); put("description", "示例") }) },
                )
            },
        )
        var calls = 0
        responses += textResponse("ok")

        engine(skillCatalog = { calls++; catalog }).startTurn(ui, TurnInput("hi"))

        assertEquals(1, calls)
        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        val users = SessionCodec.messagesOf(session.events).filter { it.role == MessageRole.USER }
        // 会话里：真正的用户消息 → 技能目录消息（source.kind=skill-catalog）
        assertEquals(2, users.size)
        assertEquals("hi", users[0].content.first().text)
        assertEquals("skill-catalog", users[1].source?.get("kind")?.jsonPrimitive?.contentOrNull)
        assertEquals("catalog", users[1].source?.get("form")?.jsonPrimitive?.contentOrNull)
        // 目录消息在 step/start 之后、请求之前；请求体里三个 text 块（运行时上下文 + 用户 + 目录）
        val catalogSeq = session.events.first { it.type == "user/message" && it.data.toString().contains("skill-catalog") }.seq!!
        val stepSeq = session.events.first { it.type == "step/start" }.seq!!
        assertTrue(catalogSeq > stepSeq)
        val merged = requests[0].messages.first { it.role == "user" }
        assertEquals(3, merged.content.size)
        // UI 也有一条「技能目录」分隔条
        assertTrue(ui.messages.any { it.sourceKind == "skill-catalog" && it.sourceSummary == "1 个技能" })
    }

    @Test
    fun skillCatalogNullLeavesRequestUntouched() = runBlocking {
        responses += textResponse("ok")

        engine(skillCatalog = { null }).startTurn(ui, TurnInput("hi"))

        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        assertEquals(1, SessionCodec.messagesOf(session.events).count { it.role == MessageRole.USER })
        val merged = requests[0].messages.first { it.role == "user" }
        assertEquals(2, merged.content.size) // 运行时上下文 + 用户消息
    }

    // ---------------- 用户直接调用技能（M21） ----------------

    @Test
    fun skillInvocationIsInjectedAfterCatalogAndBeforeRequest() = runBlocking {
        val catalog = UserNotice(
            text = "CATALOG",
            summary = "1 个技能",
            kind = "skill-catalog",
            source = buildJsonObject {
                put("kind", "skill-catalog")
                put("form", "catalog")
                put("entries", buildJsonArray {
                    add(buildJsonObject { put("name", "demo"); put("description", "示例") })
                })
            },
        )
        val invocation = UserNotice(
            text = "<skill_content name=\"demo\">\nINSTRUCTIONS\n</skill_content>",
            summary = "demo",
            kind = "skill-invocation",
            source = buildJsonObject {
                put("kind", "skill-invocation")
                put("name", "demo")
                put("form", "instructions")
            },
        )
        var seen: List<String>? = null
        responses += textResponse("ok")

        engine(
            skillCatalog = { catalog },
            skillInvocations = { _, texts ->
                seen = texts
                listOf(invocation)
            },
        ).startTurn(ui, TurnInput("use /demo"))

        assertEquals(listOf("use /demo"), seen)
        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        val users = SessionCodec.messagesOf(session.events).filter { it.role == MessageRole.USER }
        assertEquals(3, users.size)
        assertEquals("use /demo", users[0].content.first().text)
        assertEquals("skill-catalog", users[1].source?.get("kind")?.jsonPrimitive?.contentOrNull)
        assertEquals("skill-invocation", users[2].source?.get("kind")?.jsonPrimitive?.contentOrNull)
        assertEquals("instructions", users[2].source?.get("form")?.jsonPrimitive?.contentOrNull)
        assertEquals("demo", users[2].source?.get("name")?.jsonPrimitive?.contentOrNull)
        // 请求体：运行时上下文 + 用户输入 + 目录 + 技能指令（同一个 user 消息的四个 text 块）
        val merged = requests[0].messages.first { it.role == "user" }
        assertEquals(4, merged.content.size)
        assertEquals("CTX", (merged.content[0] as ApiBlock.Text).text)
        assertEquals("use /demo", (merged.content[1] as ApiBlock.Text).text)
        assertEquals("CATALOG", (merged.content[2] as ApiBlock.Text).text)
        assertEquals(invocation.text, (merged.content[3] as ApiBlock.Text).text)
        assertTrue(ui.messages.any { it.sourceKind == "skill-invocation" && it.sourceSummary == "demo" })
    }

    @Test
    fun skillInvocationRunsOncePerTurnNotPerStep() = runBlocking {
        var calls = 0
        responses += toolCallResponse("call-1", "fake_tool", "{}")
        responses += textResponse("done")

        engine(
            skillInvocations = { _, _ ->
                calls++
                emptyList()
            },
        ).startTurn(ui, TurnInput("use /demo"))

        assertEquals(1, calls, "只扫第一步的直接用户输入；后续 step 没有新的 source.kind=user 消息")
    }

    @Test
    fun wakeTurnDoesNotScanNoticesForSkillGestures() = runBlocking {
        var calls = 0
        responses += textResponse("ok")
        val notice = UserNotice(text = "job done /demo", summary = "bash: done")

        engine(
            skillInvocations = { _, _ ->
                calls++
                emptyList()
            },
        ).startTurn(ui, TurnInput(text = "", notices = listOf(notice)))

        assertEquals(0, calls)
        val merged = requests[0].messages.first { it.role == "user" }
        assertEquals("CTX", (merged.content[0] as ApiBlock.Text).text)
        assertEquals("job done /demo", (merged.content[1] as ApiBlock.Text).text)
    }

    @Test
    fun userImageIsPersistedAndSerialized() = runBlocking {
        val ref = ImageRef(
            attachmentId = "sha256:" + "a".repeat(64),
            mediaType = "image/png",
            bytes = 4,
            width = 2,
            height = 2,
            name = "shot.png",
        )
        responses += textResponse("看到了")
        engine(imageBytes = { id -> if (id == ref.attachmentId) byteArrayOf(1, 2, 3, 4) else null })
            .startTurn(ui, TurnInput("look", images = listOf(ref)))

        // v4：同一个 user/message 里 text 在前、image 块随后，只存引用
        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        val userMessage = SessionCodec.messagesOf(session.events).single { it.role == MessageRole.USER }
        assertEquals(listOf(BlockType.TEXT, BlockType.IMAGE), userMessage.content.map { it.type })
        assertEquals(ref, userMessage.content[1].toImageRef())

        // 请求体：句柄文本 + base64 image（上游 user 分支同形），且在用户正文之后
        val apiContent = requests[0].messages.first().content
        val texts = apiContent.filterIsInstance<ApiBlock.Text>().map { it.text }
        assertEquals(listOf("CTX", "look"), texts.take(2))
        assertTrue(texts[2].startsWith("Image \"shot.png\" (sha256:aaaaaaaa"))
        val image = apiContent.filterIsInstance<ApiBlock.Image>().single()
        assertEquals("image/png", image.source.mediaType)
        assertEquals("AQIDBA==", image.source.data)

        // UI 消息也带 image 块（聊天页直接渲染缩略图）
        assertEquals(listOf(BlockType.TEXT, BlockType.IMAGE), ui.messages.first().content.map { it.type })
        assertEquals("look", ui.currentTitle)
    }

    @Test
    fun imageOnlyTurnUsesImageTitleAndSkipsEmptyText() = runBlocking {
        val ref = ImageRef("sha256:" + "b".repeat(64), "image/jpeg", 4, 3, 2)
        responses += textResponse("ok")
        engine(imageBytes = { byteArrayOf(9, 8, 7, 6) })
            .startTurn(ui, TurnInput("", images = listOf(ref)))

        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        val userMessage = SessionCodec.messagesOf(session.events).single { it.role == MessageRole.USER }
        // 只发图片：content 里没有空 text 块
        assertEquals(listOf(BlockType.IMAGE), userMessage.content.map { it.type })
        assertEquals("图片", ui.currentTitle)
        // 第一条 user 请求只有运行时上下文 + 图片句柄/图，没有空 Text("")
        val texts = requests[0].messages.first().content.filterIsInstance<ApiBlock.Text>().map { it.text }
        assertEquals("CTX", texts.first())
        assertTrue(texts.none { it.isEmpty() })
    }

    // ---------------- M47：图片卸载 ----------------

    @Test
    fun imageBudgetOffloadsOldestImageThenRetriesWithPlaceholder() = runBlocking {
        val ref = ImageRef(
            attachmentId = "sha256:" + "a".repeat(64),
            mediaType = "image/png",
            bytes = 4,
            width = 2,
            height = 2,
            name = "shot.png",
        )
        responses += textResponse("收到图片")
        engine(
            imageBytes = { byteArrayOf(1, 2, 3, 4) },
            imagePath = { id -> "/data/user/0/xin.dshaa.nativeapp/files/attachments/$id.png" },
            // 4 字节预算 < 一张 4 字节图的 base64（8 字符）：第一份请求必然触发卸载
            imageBudget = { ImageOffload.Budget(maxImages = 600, maxBytes = 4, byteQuantum = 1, countQuantum = 1) },
        ).startTurn(ui, TurnInput("看图", images = listOf(ref)))

        // 同一个 step 内两次请求：第一次带真图，第二次只有占位文本
        assertEquals(2, requests.size)
        assertTrue(requests[0].messages.flatMap { it.content }.any { it is ApiBlock.Image })
        val second = requests[1].messages.flatMap { it.content }
        assertEquals(0, second.count { it is ApiBlock.Image })
        val placeholder = second.filterIsInstance<ApiBlock.Text>().map { it.text }
            .first { it.startsWith("[image omitted to fit request image limits;") }
        assertTrue(placeholder.contains("\"shot.png\" (${ref.attachmentId})"))
        assertTrue(placeholder.contains("/data/user/0/xin.dshaa.nativeapp/files/attachments/${ref.attachmentId}.png"))

        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        val offload = session.events.single { it.type == "image/offload" }
        val target = SessionCodec.imageOffloadTargetsOf(offload).single()
        assertEquals(ui.messages.first().seq, target.seq)
        assertEquals(listOf(0), target.imageIndexes)
        // 原始 user 事件不变；回放投影与界面消息都带 offloaded 标记
        val original = SessionCodec.messagesOf(session.events).first { it.role == MessageRole.USER }
        assertTrue(!original.content.single { it.type == BlockType.IMAGE }.toImageRef()!!.offloaded)
        val projected = SessionCodec.surfaceOf(session.events).first { it.role == MessageRole.USER }
        assertTrue(projected.content.single { it.type == BlockType.IMAGE }.toImageRef()!!.offloaded)
        assertTrue(ui.messages.first().content.single { it.type == BlockType.IMAGE }.toImageRef()!!.offloaded)

        assertNull(ui.lastError)
        assertEquals("completed", turnEndKind(session))
        assertTrue(logs.any { it.startsWith("image-offload: offloaded 1 image(s)") })
    }

    @Test
    fun imageOffloadWithoutRetainedImagesPreservesTheError() = runBlocking {
        streamOverride = {
            flow {
                emit(
                    LlmEvent.Failed(
                        "request images exceed the route budget",
                        code = ImageOffload.REQUIRED_CODE,
                        status = 400,
                        offloadImages = 1,
                    ),
                )
            }
        }
        engine().startTurn(ui, TurnInput("hi"))

        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        // 没有可卸载的图：不落 image/offload，也不重发，原始错误保留
        assertTrue(session.events.none { it.type == "image/offload" })
        assertEquals(1, requests.size)
        assertEquals("error", turnEndKind(session))
        assertTrue(ui.lastError!!.contains("request images exceed the route budget"))
        assertTrue(logs.any { it.contains("no retained image left to offload") })
    }

    // ---------------- M48：摘要请求的图片卸载（compaction/summary-error） ----------------

    @Test
    fun summaryImageBudgetOffloadsRegionImageThenRetries() = runBlocking {
        val ref = ImageRef(
            attachmentId = "sha256:" + "c".repeat(64),
            mediaType = "image/png",
            bytes = 4,
            width = 2,
            height = 2,
            name = "summary.png",
        )
        val region = listOf(
            ChatMessage(
                id = "u1",
                role = MessageRole.USER,
                content = listOf(ContentBlock(BlockType.TEXT, text = "看图"), ref.toContentBlock()),
                seq = 7,
            ),
        )
        val recorded = mutableListOf<ApiRequest>()
        streamOverride = { request ->
            recorded += request
            scripted(request)
        }
        responses += textResponse("SUMMARY")
        val engine = engine(
            imageBytes = { byteArrayOf(1, 2, 3, 4) },
            imagePath = { id -> "/data/user/0/xin.dshaa.nativeapp/files/attachments/$id.png" },
            imageBudget = { ImageOffload.Budget(maxImages = 600, maxBytes = 4, byteQuantum = 1, countQuantum = 1) },
        )
        var recovered = 0
        val summary = engine.summarizeCompaction(
            region,
            LlmConfig(apiKey = "k", model = "deepseek-flash", maxTokens = 4096),
        ) { failure ->
            recovered += 1
            assertEquals(1, failure.count)
            ImageOffload.markOffloaded(region, listOf(ImageOffloadTarget(7, listOf(0))))
        }

        assertEquals("SUMMARY", summary.text)
        assertEquals(1, recovered)
        // 第一份（带图）在本地就被预算拦下，没有发到网络；只有第二份（占位文本）真的流式请求
        assertEquals(1, recorded.size)
        val content = recorded.single().messages.flatMap { it.content }
        assertTrue(content.none { it is ApiBlock.Image })
        val placeholder = content.filterIsInstance<ApiBlock.Text>().map { it.text }
            .first { it.startsWith("[image omitted to fit request image limits;") }
        assertTrue(placeholder.contains("\"summary.png\" (${ref.attachmentId})"))
        assertTrue(placeholder.contains("attachments/${ref.attachmentId}.png"))
        assertTrue(logs.any { it.startsWith("compaction: summary request images exceed budget; offload=1") })
    }

    @Test
    fun summaryImageBudgetWithoutRecoveryFailsTheAttempt() = runBlocking {
        val ref = ImageRef("sha256:" + "e".repeat(64), "image/png", 4, 2, 2, name = "only.png")
        val region = listOf(
            ChatMessage(
                id = "u1",
                role = MessageRole.USER,
                content = listOf(ref.toContentBlock()),
                seq = 9,
            ),
        )
        val recorded = mutableListOf<ApiRequest>()
        streamOverride = { request ->
            recorded += request
            scripted(request)
        }
        val engine = engine(
            imageBytes = { byteArrayOf(1, 2, 3, 4) },
            imageBudget = { ImageOffload.Budget(maxImages = 600, maxBytes = 4, byteQuantum = 1, countQuantum = 1) },
        )
        val failure = assertFailsWith<SummaryImageOffloadRequired> {
            engine.summarizeCompaction(region, LlmConfig(apiKey = "k", model = "deepseek-flash", maxTokens = 4096))
        }
        assertEquals(1, failure.count)
        assertTrue(recorded.isEmpty(), "没有人接手时不应该发出带图请求")
        assertTrue(logs.any { it.startsWith("compaction: summary request images exceed budget") })
    }

    @Test
    fun summaryImageBudgetWithNothingToOffloadKeepsTheFailure() = runBlocking {
        val ref = ImageRef("sha256:" + "f".repeat(64), "image/png", 4, 2, 2, name = "gone.png")
        val region = listOf(
            ChatMessage(
                id = "u1",
                role = MessageRole.USER,
                content = listOf(ref.toContentBlock()),
                seq = 11,
            ),
        )
        val recorded = mutableListOf<ApiRequest>()
        streamOverride = { request ->
            recorded += request
            scripted(request)
        }
        val engine = engine(
            imageBytes = { byteArrayOf(1, 2, 3, 4) },
            imageBudget = { ImageOffload.Budget(maxImages = 600, maxBytes = 4, byteQuantum = 1, countQuantum = 1) },
        )
        assertFailsWith<SummaryImageOffloadRequired> {
            engine.summarizeCompaction(
                region,
                LlmConfig(apiKey = "k", model = "deepseek-flash", maxTokens = 4096),
            ) { null }
        }
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun autoCompactionOffloadsSummaryImageThenReplacesHistory() = runBlocking {
        val ref = ImageRef("sha256:" + "d".repeat(64), "image/png", 4, 2, 2, name = "old.png")
        val user1 = "B".repeat(20_000)
        val reply1 = "A".repeat(20_000)
        val user2 = "C".repeat(20_000)
        responses += textResponse(reply1)
        responses += textResponse("SUMMARY")
        responses += textResponse("done")
        var budget = ImageOffload.DEFAULT_BUDGET
        val recorded = mutableListOf<ApiRequest>()
        streamOverride = { request ->
            recorded += request
            scripted(request)
        }
        val e = engine(
            contextWindow = 12_000,
            imageBytes = { byteArrayOf(1, 2, 3, 4) },
            imagePath = { id -> "/data/user/0/xin.dshaa.nativeapp/files/attachments/$id.png" },
            imageBudget = { budget },
        )
        e.startTurn(ui, TurnInput(user1, images = listOf(ref)))
        // 换到小预算路由（真实场景：换模型 / 冷启后第一次请求前先压缩）
        budget = ImageOffload.Budget(maxImages = 600, maxBytes = 4, byteQuantum = 1, countQuantum = 1)
        e.startTurn(ui, TurnInput(user2))

        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        val types = session.events.map { it.type }
        val startIdx = types.indexOf("compaction/start")
        val offloadIdx = types.indexOf("image/offload")
        val summaryIdx = types.indexOf("compaction/summary")
        val endIdx = types.indexOf("compaction/end")
        assertTrue(startIdx >= 0 && startIdx < offloadIdx, "compaction/start 在 image/offload 之前")
        assertTrue(offloadIdx < summaryIdx && summaryIdx < endIdx, "卸载在 summary 之前、end 收口")

        val imgSeq = session.events.first { it.type == "user/message" }.seq!!
        val target = SessionCodec.imageOffloadTargetsOf(session.events.single { it.type == "image/offload" }).single()
        assertEquals(imgSeq, target.seq)
        assertEquals(listOf(0), target.imageIndexes)

        val summaryEvent = session.events.single { it.type == "compaction/summary" }
        val replySeq = session.events.first { it.type == "assistant/message" }.seq!!
        assertEquals(
            listOf(imgSeq, replySeq),
            summaryEvent.data!!.jsonObject["shadowedSeqs"]!!.jsonArray.map { it.jsonPrimitive.longOrNull!! },
        )
        // 重新计价：卸载后的选区（image 块带 offloaded 标记）而不是卸载前的价
        val raw = SessionCodec.messagesOf(session.events)
        val markedUser = raw.first { it.role == MessageRole.USER }.markOffloadedImages(setOf(0))
        val expectedTokens = Compaction.estimateContentTokens(markedUser.content) + Compaction.BLOCK_OVERHEAD +
            Compaction.estimateContentTokens(raw.first { it.role == MessageRole.ASSISTANT }.content) +
            Compaction.BLOCK_OVERHEAD
        assertEquals(expectedTokens, summaryEvent.data!!.jsonObject["shadowedTokenCount"]!!.jsonPrimitive.intOrNull)
        assertNull(session.events.single { it.type == "compaction/end" }.data!!.jsonObject["error"])

        // 摘要重试（recorded[1]）只剩占位文本；压缩后的主请求（recorded[2]）已经没有旧图
        assertEquals(3, recorded.size)
        val summaryRetry = recorded[1]
        assertTrue(summaryRetry.messages.flatMap { it.content }.none { it is ApiBlock.Image })
        assertTrue(summaryRetry.allText().contains("[image omitted to fit request image limits;"))
        assertTrue(summaryRetry.allText().contains("\"old.png\""))
        assertTrue(recorded[2].allText().contains("<compacted-summary>"))

        assertEquals(3, ui.messages.size)
        assertEquals("compact-checkpoint", ui.messages[0].sourceKind)
        assertEquals(user2, ui.messages[1].content.first().text)
        assertNull(ui.lastError)
        assertTrue(logs.any { it.startsWith("auto-compact: summary image-offload: offloaded 1 image(s)") })
        assertTrue(logs.any { it.startsWith("auto-compact: done") })
    }

    @Test
    fun reasoningOffSendsDisabledThinkingAndNoOutputConfig() = runBlocking {
        // 上游 dsh-llm-deepseek serialize()：effort=off ⇒ thinking:{"type":"disabled"}，且不带 output_config
        responses += textResponse("ok")
        engine(effort = "off").startTurn(ui, TurnInput("hi"))

        val request = requests.single()
        assertEquals("disabled", request.thinking?.type)
        assertNull(request.outputConfig)
    }

    @Test
    fun reasoningHighKeepsThinkingEnabledAndEffort() = runBlocking {
        responses += textResponse("ok")
        engine(effort = "high").startTurn(ui, TurnInput("hi"))

        val request = requests.single()
        assertEquals("enabled", request.thinking?.type)
        assertEquals("high", request.outputConfig?.effort)
    }

    // ---------------- M44：自动压缩 ----------------

    @Test
    fun uiMessagesCarrySessionSeqs() = runBlocking {
        responses += textResponse("hello")
        engine().startTurn(ui, TurnInput("hi"))

        assertTrue(ui.messages.all { it.seq != null }, "界面消息必须带回会话 seq")
        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        assertEquals(session.events.first { it.type == "user/message" }.seq, ui.messages[0].seq)
        assertEquals(session.events.first { it.type == "assistant/message" }.seq, ui.messages[1].seq)
    }

    @Test
    fun autoCompactionReplacesOldHistoryBeforeRequest() = runBlocking {
        // 窗口 12000 / 输出 4096：headroom=1500 → 阈值 6400、保留 2048（详见 CompactionTest）
        val user1 = "B".repeat(20_000) // 5004 令牌
        val reply1 = "A".repeat(20_000) // 5004 令牌
        val user2 = "C".repeat(20_000) // 5004 令牌
        responses += textResponse(reply1)
        responses += textResponse("SUMMARY") // 自动压缩的摘要请求
        responses += textResponse("done")
        val e = engine(contextWindow = 12_000)
        e.startTurn(ui, TurnInput(user1))
        e.startTurn(ui, TurnInput(user2))

        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        val start = session.events.single { it.type == "compaction/start" }
        // 自动压缩带所属轮次，没有 sourceCommandId（上游 lifecycle 同形）
        assertEquals(2, start.data!!.jsonObject["turn"]!!.jsonPrimitive.intOrNull)
        assertNull(start.data!!.jsonObject["sourceCommandId"])
        val summary = session.events.single { it.type == "compaction/summary" }
        assertNull(summary.data!!.jsonObject["sourceCommandId"])
        assertEquals(2, summary.data!!.jsonObject["shadowedSeqs"]!!.jsonArray.size)
        val end = session.events.single { it.type == "compaction/end" }
        assertEquals(2, end.data!!.jsonObject["turn"]!!.jsonPrimitive.intOrNull)
        assertEquals(2, session.events.count { it.type == "turn/end" })

        // 第二次请求的会话面：运行时上下文 + checkpoint + 新输入；旧历史只在摘要里
        assertEquals(2, requests.size)
        val second = requests[1].messages.flatMap { it.content }
            .filterIsInstance<ApiBlock.Text>()
            .joinToString("\n") { it.text }
        assertTrue(second.contains("<compacted-summary>"))
        assertTrue(second.contains("SUMMARY"))
        assertTrue(second.contains(user2))
        assertTrue(!second.contains("B".repeat(100)), "被压掉的第一轮用户消息不应再进请求")
        assertTrue(!second.contains("A".repeat(100)), "被压掉的回复不应再进请求")

        // 界面：checkpoint 顶掉两条旧消息，user2 / done 保留
        assertEquals(3, ui.messages.size)
        assertEquals("compact-checkpoint", ui.messages[0].sourceKind)
        assertTrue(ui.messages[0].content[1].text == "SUMMARY")
        assertEquals(user2, ui.messages[1].content.first().text)
        assertEquals("done", ui.messages[2].content.first().text)
        assertTrue(logs.any { it.startsWith("auto-compact: done") })
    }

    @Test
    fun autoCompactionSkippedBelowThreshold() = runBlocking {
        responses += textResponse("hello")
        engine(contextWindow = 12_000).startTurn(ui, TurnInput("hi"))
        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        assertTrue(session.events.none { it.type.startsWith("compaction/") })
        assertTrue(logs.none { it.startsWith("auto-compact: start") })
    }

    // ---------------- M45：溢出恢复 ----------------

    @Test
    fun contextOverflowCompactsMaximallyThenRetriesInSameStep() = runBlocking {
        // 窗口放大到 200k：压力阈值 160000 够不着，只有「模型报溢出」才会压缩。
        // 被压掉的两条各 5000 令牌左右，摘要（前言 300 字符）才比原文小。
        val user1 = "U1-" + "B".repeat(20_000)
        val reply1 = "A1-" + "A".repeat(20_000)
        val user2 = "C".repeat(4_000)
        responses += textResponse(reply1)
        responses += listOf(
            LlmEvent.Failed(
                "This model's maximum context length is 200000 tokens. However, your messages resulted in " +
                    "260000 tokens. Please reduce the length of the messages.",
                code = "invalid_request_error",
                status = 400,
            ),
        )
        responses += textResponse("SUMMARY") // 最大压缩的摘要请求
        responses += textResponse("done") // 同一 step 里重发的请求

        val e = engine(contextWindow = 200_000)
        e.startTurn(ui, TurnInput(user1))
        val outcome = e.startTurn(ui, TurnInput(user2))

        assertEquals(TurnOutcome.COMPLETED, outcome)
        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        assertEquals("completed", turnEndKind(session))
        // 重试发生在同一个 step（不新开 step）：两轮各 1 个 step/start
        assertEquals(2, session.events.count { it.type == "step/start" })

        val start = session.events.single { it.type == "compaction/start" }
        assertEquals(2, start.data!!.jsonObject["turn"]!!.jsonPrimitive.intOrNull)
        assertNull(start.data!!.jsonObject["sourceCommandId"])
        val summary = session.events.single { it.type == "compaction/summary" }
        // 最大压缩：整个会话面除最后一条（user2）外全部换成 checkpoint
        assertEquals(2, summary.data!!.jsonObject["shadowedSeqs"]!!.jsonArray.size)
        assertNull(summary.data!!.jsonObject["sourceCommandId"])
        val end = session.events.single { it.type == "compaction/end" }
        assertNull(end.data!!.jsonObject["error"])

        // 三次请求：第 1 轮、第 2 轮失败的那次、压缩后的重发
        assertEquals(3, requests.size)
        val retried = requests[2].messages.flatMap { it.content }
            .filterIsInstance<ApiBlock.Text>()
            .joinToString("\n") { it.text }
        assertTrue(retried.contains("<compacted-summary>"))
        assertTrue(retried.contains("SUMMARY"))
        assertTrue(retried.contains("C".repeat(100)))
        assertTrue(!retried.contains("U1-"), "被压掉的旧消息不应再进重发请求")
        assertTrue(!retried.contains("A1-"), "被压掉的回复不应再进重发请求")

        // 界面：checkpoint 顶掉两条旧消息；失败的气泡被复用成重发结果，没有留下错误卡片
        assertEquals(3, ui.messages.size)
        assertEquals("compact-checkpoint", ui.messages[0].sourceKind)
        assertEquals(user2, ui.messages[1].content.first().text)
        assertEquals("done", ui.messages[2].content.first().text)
        assertNull(ui.messages[2].error)
        assertNull(ui.lastError)
        // 只剩一条 assistant（重发后的 done）：失败的那次没有额外留一条气泡
        assertEquals(1, ui.messages.count { it.role == MessageRole.ASSISTANT })
        assertTrue(logs.any { it.startsWith("context-overflow: request failed") && it.contains("retry=1/1") })
        assertTrue(logs.any { it.startsWith("context-overflow: done") })
    }

    @Test
    fun nonOverflowFailureStillEndsTheTurnWithoutCompacting() = runBlocking {
        responses += listOf(LlmEvent.Failed("Invalid API key", code = "AUTHENTICATION_ERROR", status = 401))

        val outcome = engine(contextWindow = 12_000).startTurn(ui, TurnInput("U1"))

        assertEquals(TurnOutcome.ERROR, outcome)
        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        assertEquals("error", turnEndKind(session))
        assertTrue(session.events.none { it.type.startsWith("compaction/") })
        assertEquals(1, requests.size)
        assertEquals("Invalid API key", ui.lastError)
    }

    @Test
    fun overflowWithoutCompactableHistoryPreservesTheOriginalError() = runBlocking {
        // 只有一条用户消息：没有可压缩的跨度（上游 selectCompactableRange 同样返回 null）
        responses += listOf(LlmEvent.Failed("context length exceeded", code = "invalid_request_error", status = 400))

        val outcome = engine(contextWindow = 12_000).startTurn(ui, TurnInput("U1"))

        assertEquals(TurnOutcome.ERROR, outcome)
        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        assertTrue(session.events.none { it.type.startsWith("compaction/") })
        assertEquals(1, requests.size)
        assertTrue(logs.any { it.startsWith("context-overflow: no compactable range") })
        assertTrue(logs.any { it.startsWith("context-overflow: no surface progress") })
        assertEquals("context length exceeded", ui.lastError)
    }

    // ---------------- M46：工具结果修剪 ----------------

    @Test
    fun pruningRelievesPressureWithoutSummarizing() = runBlocking {
        // 窗口 12000 / 输出 4096：阈值 6404。一条 31038 字符的工具结果 ≈ 7768 令牌，
        // 剪成 5159 字符 ≈ 1298 令牌后就低于阈值 —— 上游这时跳过摘要（只省，不压）。
        toolResult = { ToolResult.ok(BIG_TOOL_OUTPUT) }
        responses += toolCallResponse("call-1", "fake_tool", "{}")
        responses += textResponse("done")
        engine(contextWindow = 12_000).startTurn(ui, TurnInput("hi"))

        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        val prune = session.events.single { it.type == "compaction/prune" }
        val originalSeq = prune.data!!.jsonObject["shadowedSeqs"]!!.jsonArray.single().jsonPrimitive.longOrNull!!
        assertEquals(originalSeq, prune.data!!.jsonObject["shadowedRange"]!!.jsonObject["start"]!!.jsonPrimitive.longOrNull)
        assertEquals(originalSeq, prune.data!!.jsonObject["shadowedRange"]!!.jsonObject["end"]!!.jsonPrimitive.longOrNull)
        // 影子价：原工具结果的令牌价（ceil(31038/4)+4 块开销+4 消息开销）
        assertEquals(7_768, prune.data!!.jsonObject["shadowedTokenCount"]!!.jsonPrimitive.intOrNull)

        val replacement = session.events.single { it.type == "tool/result" && SessionCodec.replaceRangeOf(it) != null }
        assertEquals(originalSeq..originalSeq, SessionCodec.replaceRangeOf(replacement))
        // 没有摘要：pruning 已经解压
        assertTrue(session.events.none { it.type == "compaction/start" })
        assertEquals(2, session.events.count { it.type == "tool/result" }, "原事件保留 + 一条替换")

        // 第二次请求：工具结果已经是「头 + 标记 + 尾」，中段原文不在
        val toolText = requests[1].messages.last().content.filterIsInstance<ApiBlock.ToolResult>().single()
            .content!!.filterIsInstance<ApiBlock.Text>().joinToString("") { it.text }
        assertTrue(toolText.contains("[... tool result middle pruned ...]"))
        assertEquals(5159, toolText.length)
        assertTrue(!toolText.contains("MIDDLE-UNIQUE-"))
        assertTrue(!toolText.contains("M".repeat(100)))
        assertTrue(toolText.startsWith("H".repeat(4096)))
        assertTrue(toolText.endsWith("T".repeat(1024)))

        // 界面：同一位置换成剪过的消息，seq 指向新事件
        val uiTool = ui.messages.single { it.role == MessageRole.TOOL }
        assertEquals(replacement.seq, uiTool.seq)
        assertTrue(uiTool.content.single().text.contains("[... tool result middle pruned ...]"))
        assertTrue(logs.any { it.startsWith("auto-compact: prune seq=") })
        assertTrue(logs.any { it.startsWith("auto-compact: pruning relieved the pressure") })
    }

    @Test
    fun overflowPrunesBeforeSelectingAndTheSummaryReadsThePrunedSurface() = runBlocking {
        toolResult = { ToolResult.ok(BIG_TOOL_OUTPUT) }
        responses += toolCallResponse("call-1", "fake_tool", "{}")
        responses += textResponse("done")
        responses += listOf(
            LlmEvent.Failed(
                "This model's maximum context length is 200000 tokens. However, your messages resulted in " +
                    "260000 tokens.",
                code = "invalid_request_error",
                status = 400,
            ),
        )
        responses += textResponse("SUMMARY")
        responses += textResponse("ok")

        val recorded = mutableListOf<ApiRequest>()
        streamOverride = { request ->
            recorded += request
            scripted(request)
        }
        val e = engine(contextWindow = 200_000)
        e.startTurn(ui, TurnInput("U1"))
        assertEquals(TurnOutcome.COMPLETED, e.startTurn(ui, TurnInput("U2")))

        val session = assertNotNull(repo.load(gateway.session!!.header.id))
        // 顺序：先 pruning（溢出分支也先剪），再选区；summary 的 shadowedSeqs 是替换后的新 seq
        val prune = session.events.single { it.type == "compaction/prune" }
        val originalSeq = prune.data!!.jsonObject["shadowedSeqs"]!!.jsonArray.single().jsonPrimitive.longOrNull!!
        val replacement = session.events.single { it.type == "tool/result" && SessionCodec.replaceRangeOf(it) != null }
        val summarySeqs = session.events.single { it.type == "compaction/summary" }
            .data!!.jsonObject["shadowedSeqs"]!!.jsonArray.map { it.jsonPrimitive.longOrNull!! }
        assertTrue(replacement.seq!! in summarySeqs, "压缩的是剪过的那条，不是原事件")
        assertTrue(originalSeq !in summarySeqs)
        assertTrue(logs.any { it.startsWith("context-overflow: prune seq=") })

        // 摘要请求（recorded[3]）读的是剪过的会话面：标记在、中段原文不在
        val summaryText = recorded[3].allText()
        assertTrue(summaryText.contains("[... tool result middle pruned ...]"))
        assertTrue(!summaryText.contains("MIDDLE-UNIQUE-"))
        assertTrue(!summaryText.contains("M".repeat(100)))

        // 重发请求：checkpoint + U2；被剪的结果已经进了 checkpoint
        val retried = recorded.last().allText()
        assertTrue(retried.contains("<compacted-summary>"))
        assertTrue(retried.contains("SUMMARY"))
        assertTrue(retried.contains("U2"))
        assertTrue(!retried.contains("MIDDLE-UNIQUE-"))
    }

    // ---------------- 脚手架 ----------------
    private fun engine(
        jobNotices: () -> List<UserNotice> = { emptyList() },
        plan: PlanModeState = PlanModeState(),
        skillCatalog: (SessionSession) -> UserNotice? = { null },
        skillInvocations: (SessionSession, List<String>) -> List<UserNotice> = { _, _ -> emptyList() },
        imageBytes: (String) -> ByteArray? = { null },
        imagePath: (String) -> String? = { null },
        imageBudget: () -> ImageOffload.Budget = { ImageOffload.DEFAULT_BUDGET },
        effort: String = "high",
        contextWindow: Int? = null,
        approval: suspend (xin.dshaa.core.model.ToolCall) -> Boolean = { approve },
    ): TurnEngine = TurnEngine(
        EngineDeps(
            workspacePath = root.absolutePath,
            config = { LlmConfig(apiKey = "k", model = "deepseek-flash", maxTokens = 4096) },
            providerLabel = { "deepseek" },
            reasoningEffort = { effort },
            registry = ToolRegistry(
                listOf(
                    object : DshTool {
                        override val name: String = "fake_tool"
                        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = toolResult()
                    },
                ),
            ),
            toolContext = ToolContext(File(root, "workspace").apply { mkdirs() }),
            apiTools = { listOf(ApiTool(name = "fake_tool", description = "fake", inputSchema = buildJsonObject { put("type", "object") })) },
            systemPrompt = { if (plan.selected()) "SYS-PLAN" else "SYS" },
            runtimeContext = { "CTX" },
            stream = { request, _ ->
                streamOverride?.invoke(request) ?: scripted(request)
            },
            sessions = gateway,
            goals = { session -> GoalStore(session, activation = activation) },
            imageBytes = imageBytes,
            imagePath = imagePath,
            imageBudget = imageBudget,
            approval = { call -> approval(call) },
            nextSchedulePrompt = { null },
            tickSchedules = { },
            onRequest = { _, request -> requests += request },
            onLlmEvent = { _, _ -> },
            onToolResult = { _, _, _ -> },
            onTurnFinished = { },
            jobNotices = jobNotices,
            skillCatalog = skillCatalog,
            skillInvocations = skillInvocations,
            contextWindow = { contextWindow },
            log = { logs += it },
            plan = plan,
            newId = { "id-${idSeq++}" },
        ),
    )

    private fun scripted(request: ApiRequest): Flow<LlmEvent> = flow {
        val script = responses.removeFirstOrNull() ?: emptyList()
        script.forEach { emit(it) }
    }

    private fun textResponse(text: String): List<LlmEvent> = listOf(
        LlmEvent.MessageStart("m", "deepseek-flash", 10),
        LlmEvent.BlockStart(0, "text", null, null),
        LlmEvent.TextDelta(0, text),
        LlmEvent.BlockStop(0),
        LlmEvent.MessageDelta("end_turn", 5),
        LlmEvent.MessageStop,
    )

    private fun toolCallResponse(id: String, name: String, args: String): List<LlmEvent> = listOf(
        LlmEvent.MessageStart("m", "deepseek-flash", 10),
        LlmEvent.BlockStart(0, "text", null, null),
        LlmEvent.TextDelta(0, "checking"),
        LlmEvent.BlockStop(0),
        LlmEvent.BlockStart(1, "tool_use", id, name),
        LlmEvent.ToolInputDelta(1, args),
        LlmEvent.BlockStop(1),
        LlmEvent.MessageDelta("tool_use", 5),
        LlmEvent.MessageStop,
    )

    /** 请求里模型可见的全部文本（含 `tool_result` 的 content，修剪断言用）。 */
    private fun ApiRequest.allText(): String = messages.flatMap { message ->
        message.content.flatMap { block ->
            when (block) {
                is ApiBlock.Text -> listOf(block.text)
                is ApiBlock.ToolResult -> block.content.orEmpty().filterIsInstance<ApiBlock.Text>().map { it.text }
                else -> emptyList()
            }
        }
    }.joinToString("\n")

    private fun turnEndKind(session: SessionSession): String? =
        session.events.last { it.type == "turn/end" }.data!!.jsonObject["reason"]!!.jsonObject["kind"]!!.jsonPrimitive.contentOrNull

    private class TestGateway(private val repo: SessionRepository, private val workspace: File) : SessionGateway {
        var session: SessionSession? = null

        override fun current(): SessionSession? = session

        override suspend fun create(): SessionSession {
            val created = repo.create(cwd = workspace.absolutePath, model = "deepseek-flash")
            created.appendRequestHeader(
                provider = "deepseek",
                model = "deepseek-flash",
                reasoningEffort = "high",
                maxTokens = 4096,
            )
            session = created
            return created
        }
    }

    private class TestUi : TurnUi {
        val messages = mutableListOf<ChatMessage>()
        var currentTitle = "新会话"
        var lastSessionId: String? = null
        var lastStreaming = false
        var lastStatus: String? = null
        var lastError: String? = null
        var lastUsage: String? = null
        var lastGoal: GoalView? = null

        override fun messages(): List<ChatMessage> = messages
        override fun title(): String = currentTitle
        override fun setTitle(title: String) {
            currentTitle = title
        }

        override fun setSessionId(id: String) {
            lastSessionId = id
        }

        override fun setStreaming(streaming: Boolean) {
            lastStreaming = streaming
        }

        override fun setStatus(text: String?) {
            lastStatus = text
        }

        override fun setError(text: String?) {
            lastError = text
        }

        override fun setUsage(text: String?) {
            lastUsage = text
        }

        override fun setGoal(view: GoalView?) {
            lastGoal = view
        }

        override fun appendMessage(message: ChatMessage): Int {
            messages += message
            return messages.lastIndex
        }

        override fun updateMessage(index: Int, message: ChatMessage) {
            if (index in messages.indices) messages[index] = message
        }

        override fun applyCompaction(shadowedSeqs: List<Long>, checkpoint: ChatMessage) {
            val first = messages.indexOfFirst { it.seq != null && it.seq in shadowedSeqs }
            if (first < 0) return
            messages.removeAll { it.seq != null && it.seq in shadowedSeqs }
            messages.add(first, checkpoint)
        }

        override fun applyToolResultPrune(originalSeq: Long, replacement: ChatMessage) {
            val index = messages.indexOfFirst { it.seq == originalSeq }
            if (index >= 0) messages[index] = replacement
        }

        override fun applyImageOffload(targets: List<xin.dshaa.core.model.ImageOffloadTarget>) {
            targets.forEach { target ->
                val index = messages.indexOfFirst { it.seq == target.seq }
                if (index >= 0) {
                    messages[index] = messages[index].markOffloadedImages(target.imageIndexes.toSet())
                }
            }
        }
    }
}
