package xin.dshaa.core.agents

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 子代理运行时的契约（M16）：
 * - 结算通知逐字对齐上游 `createSettlementMessage`（含 omitClosing / 无收尾话）；
 * - 只允许直接父子收发；深度上限默认 1；
 * - 通知按收件人队列、只投递一次；interrupt 走 aborted；
 * - teardown 不再投递。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentRegistryTest {

    private fun registry(scope: kotlinx.coroutines.CoroutineScope, maxDepth: Int = 1): AgentRegistry {
        var n = 0
        return AgentRegistry(
            scope = scope,
            maxDepth = maxDepth,
            clock = { 1_000L + n },
            newAgentId = { "agent-${++n}" },
            newMessageId = { "msg-${n}" },
        )
    }

    @Test
    fun startReturnsIdAndSettlesWithClosingText() = runTest {
        val notices = mutableListOf<AgentNotice>()
        val registry = registry(this)
        registry.onNotice = { owner -> notices += registry.takeNotices(owner) }

        val record = registry.start("root", 0, "inspect", null) { AgentTerminal("completed", listOf("done")) }
        assertEquals("agent-1", record.id)
        assertEquals(AgentStatus.RUNNING, record.status)
        advanceUntilIdle()
        assertEquals(AgentStatus.INACTIVE, record.status)

        val notice = notices.single()
        assertEquals("subagent-settled", notice.kind)
        assertEquals("root", notice.owner)
        assertEquals("agent-1", notice.senderSessionId)
        assertEquals(
            listOf(
                "Background subagent agent-1 finished and will do no further work unless you send it more.",
                "Its closing message:",
                "done",
            ),
            notice.texts,
        )
        assertEquals(notice.texts.first(), notice.summary)
    }

    @Test
    fun settlementWithoutClosingTextSaysSo() = runTest {
        val notices = mutableListOf<AgentNotice>()
        val registry = registry(this)
        registry.onNotice = { owner -> notices += registry.takeNotices(owner) }
        registry.start("root", 0, "quiet", null) { AgentTerminal("completed", emptyList()) }
        advanceUntilIdle()
        assertEquals(
            listOf(
                "Background subagent agent-1 finished and will do no further work unless you send it more.",
                "It left no closing message.",
            ),
            notices.single().texts,
        )
    }

    @Test
    fun messageToParentOmitsClosingText() = runTest {
        val notices = mutableListOf<AgentNotice>()
        val registry = registry(this)
        registry.onNotice = { owner ->
            if (owner == "root") notices += registry.takeNotices(owner)
        }
        val gate = CompletableDeferred<Unit>()
        val record = registry.start("root", 0, "report", null) { rec ->
            registry.send(rec.id, "root", "found the answer")
            gate.await()
            AgentTerminal("completed", listOf("done"))
        }
        runCurrent()
        // 子→父的消息先到；父收件队列里此时只有 agent-message
        val message = notices.single()
        assertEquals("agent-message", message.kind)
        assertEquals(listOf("Agent agent-1 sent a message: ", "found the answer"), message.texts)

        gate.complete(Unit)
        advanceUntilIdle()
        val settlement = notices.last()
        assertEquals("subagent-settled", settlement.kind)
        assertEquals(
            listOf(
                "Background subagent ${record.id} finished and will do no further work unless you send it more.",
                "It sent you a message just before finishing, so its closing message is omitted.",
            ),
            settlement.texts,
        )
    }

    @Test
    fun abortedSettlementAndNoNoticeAfterShutdown() = runTest {
        val notices = mutableListOf<AgentNotice>()
        val registry = registry(this)
        registry.onNotice = { owner -> notices += registry.takeNotices(owner) }
        val gate = CompletableDeferred<Unit>()
        val record = registry.start("root", 0, "slow", null) {
            gate.await()
            AgentTerminal("completed")
        }
        runCurrent()
        assertTrue(registry.interrupt(record.id))
        advanceUntilIdle()
        assertEquals(AgentStatus.INACTIVE, record.status)
        assertEquals(1, notices.size)
        assertEquals(
            "Background subagent ${record.id} was stopped before it finished.",
            notices.single().texts.first(),
        )

        // shutdown 后不再投递
        val gate2 = CompletableDeferred<Unit>()
        registry.start("root", 0, "second", null) {
            gate2.await()
            AgentTerminal("completed")
        }
        runCurrent()
        registry.shutdown()
        advanceUntilIdle()
        assertEquals(1, notices.size)
    }

    @Test
    fun errorSettlementCarriesDiagnostic() = runTest {
        val notices = mutableListOf<AgentNotice>()
        val registry = registry(this)
        registry.onNotice = { owner -> notices += registry.takeNotices(owner) }
        registry.start("root", 0, "boom", null) { throw IllegalStateException("llm exploded") }
        advanceUntilIdle()
        val texts = notices.single().texts
        assertEquals("Background subagent agent-1 failed before it finished.", texts.first())
        assertEquals("It left no closing message.", texts[1])
        assertEquals("llm exploded", texts[2])
    }

    @Test
    fun depthLimitRejectsSecondLevel() = runTest {
        val registry = registry(this)
        val parent = registry.start("root", 0, "level1", null) { AgentTerminal("completed") }
        advanceUntilIdle()
        val error = assertFailsWith<AgentException> {
            registry.start(parent.id, parent.depth, "level2", null) { AgentTerminal("completed") }
        }
        assertEquals("subagent depth 2 exceeds maxDepth 1", error.message)
    }

    @Test
    fun sendAuthorizationAllowsDirectParentAndChildOnly() = runTest {
        val registry = registry(this, maxDepth = 2)
        val first = registry.start("root", 0, "a", null) { CompletableDeferred<Unit>().await(); AgentTerminal("completed") }
        val second = registry.start("root", 0, "b", null) { CompletableDeferred<Unit>().await(); AgentTerminal("completed") }
        runCurrent()
        // 根 → 直接子：允许（根不是 AgentRecord）
        registry.send("root", first.id, "hello")
        // 子 → 父：允许
        registry.send(first.id, "root", "hi")
        // 兄弟之间：拒绝
        assertFailsWith<AgentException> { registry.send(first.id, second.id, "nope") }
        // 未知目标：拒绝
        assertFailsWith<AgentException> { registry.send("root", "agent-404", "nope") }
        registry.interrupt(first.id)
        registry.interrupt(second.id)
        advanceUntilIdle()
    }

    @Test
    fun listsChildrenAndDescendants() = runTest {
        val registry = registry(this, maxDepth = 3)
        val a = registry.start("root", 0, "a", null) { AgentTerminal("completed") }
        val b = registry.start("root", 0, "b", null) { AgentTerminal("completed") }
        advanceUntilIdle()
        val c = registry.start(a.id, a.depth, "a-child", null) { AgentTerminal("completed") }
        advanceUntilIdle()
        assertEquals(listOf(a.id, b.id), registry.listChildren("root").map { it.id })
        assertEquals(listOf(a.id, b.id, c.id), registry.listDescendants("root").map { it.id })
        assertEquals(2, registry.get(c.id)?.depth)
        assertNull(registry.listChildren("root").firstOrNull { it.depth != 1 })
    }

    @Test
    fun noticesAreDeliveredOnceAndInterruptUnknownIsFalse() = runTest {
        val registry = registry(this)
        assertFalse(registry.interrupt("agent-404"))
        registry.onNotice = { }
        registry.start("root", 0, "once", null) { AgentTerminal("completed", listOf("x")) }
        advanceUntilIdle()
        assertEquals(1, registry.takeNotices("root").size)
        assertTrue(registry.takeNotices("root").isEmpty())
        assertNull(registry.get("agent-404"))
    }

    @Test
    fun stopBeforeCoroutineStartsSettlesAborted() = runTest {
        val registry = registry(this)
        val notices = mutableListOf<AgentNotice>()
        registry.onNotice = { owner -> notices += registry.takeNotices(owner) }
        val record = registry.start("root", 0, "early", null) {
            error("must not run")
        }
        registry.interrupt(record.id)
        advanceUntilIdle()
        assertEquals(AgentStatus.INACTIVE, record.status)
        assertEquals(
            "Background subagent ${record.id} was stopped before it finished.",
            notices.single().texts.first(),
        )
    }

    @Test
    fun returnGuidanceAndDepthContextMatchUpstream() {
        assertEquals(
            "Your parent agent id is \"root-session\". Before you finish, send your result to that agent with " +
                "send_message({ agent_id: \"root-session\", message: \"<self-contained result>\" }). The parent shares " +
                "your workspace but does not automatically receive your transcript, tool output, or reasoning. Send " +
                "earlier messages as well when a finding changes what the parent should do next; sending a message " +
                "does not end your turn.",
            AgentRegistry.returnGuidance("root-session"),
        )
        assertEquals(
            "Delegation depth for `subagent`: current 1, maximum 1, remaining levels 0. " +
                "Do not call `subagent` at this depth; carry out the work directly.",
            AgentRegistry.depthContext("subagent", 1, 1),
        )
        assertEquals(120, AgentRegistry.boundSummary("x".repeat(500)).length)
        assertTrue(AgentRegistry.boundSummary("x".repeat(500)).endsWith("…"))
    }

    @Test
    fun shortNoticeSummaryIsBounded() = runTest {
        val registry = registry(this)
        val notices = mutableListOf<AgentNotice>()
        registry.onNotice = { owner -> notices += registry.takeNotices(owner) }
        registry.start("root", 0, "x", null) { AgentTerminal("max-tokens") }
        advanceUntilIdle()
        assertEquals("Background subagent agent-1 ran out of room before it finished.", notices.single().summary)
        assertTrue(notices.single().summary.length <= 120)
        assertNotNull(registry.get("agent-1"))
    }

    // ---------------- M17：子代理面板依赖的读接口 ----------------

    @Test
    fun allListsEveryAgentInCreationOrderEvenAfterSettle() = runTest {
        val registry = registry(this)
        val gate = CompletableDeferred<Unit>()
        val a = registry.start("root", 0, "a", null) { gate.await(); AgentTerminal("completed") }
        val b = registry.start("root", 0, "b", null) { gate.await(); AgentTerminal("completed") }
        runCurrent()
        assertEquals(listOf(a.id, b.id), registry.all().map { it.id })
        gate.complete(Unit)
        advanceUntilIdle()
        // 结算后进程内历史仍然可见（面板「已结束」段靠它拿终态）
        assertEquals(listOf(a.id, b.id), registry.all().map { it.id })
        assertEquals(AgentStatus.INACTIVE, registry.all().first().status)
    }

    @Test
    fun changeListenerFiresOnStartAndOnceOnSettle() = runTest {
        val registry = registry(this)
        var changes = 0
        registry.onChange = { changes++ }
        val gate = CompletableDeferred<Unit>()
        val record = registry.start("root", 0, "watched", null) { gate.await(); AgentTerminal("completed", listOf("ok")) }
        assertEquals(1, changes) // start
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, changes) // settle（只结算一次）
        assertEquals(AgentStatus.INACTIVE, record.status)
    }

    @Test
    fun terminalIsRecordedOnSettleForPanelResult() = runTest {
        val registry = registry(this)
        val record = registry.start("root", 0, "closer", null) { AgentTerminal("completed", listOf("the result")) }
        assertNull(record.terminal)
        runCurrent()
        advanceUntilIdle()
        assertEquals("completed", record.terminal?.stopReason)
        assertEquals(listOf("the result"), record.terminal?.closingTexts)
    }
}
