package xin.dshaa.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xin.dshaa.core.tools.agents.InterruptAgentTool
import xin.dshaa.core.tools.agents.ListAgentsTool
import xin.dshaa.core.tools.agents.SendMessageTool
import xin.dshaa.core.tools.agents.SubagentTool
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 子代理五件套的模型侧文案（逐字对齐上游 `dsh-tool-subagent` / `-control`）：
 * - `started subagent <id>`；
 * - `(no subagents)` / `<id> [status] — <label>` / descendants 的 ` parent= depth=`；
 * - `message delivered to agent <id>` / `interrupt requested for agent <id>`。
 */
class AgentToolsTest {

    private val ctxBase = ToolContext(workspace = File("."))

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    private class FakeHost(
        var started: AgentStart? = null,
        var entries: List<AgentEntry> = emptyList(),
        var fail: String? = null,
    ) : AgentHost {
        val sent = mutableListOf<Pair<String, String>>()
        val interrupted = mutableListOf<String>()
        var lastFork: Boolean? = null
        var lastCwd: String? = null

        override fun currentAgentId() = "root"
        override fun currentDepth() = 0
        override fun maxDepth() = 1
        override suspend fun start(description: String, prompt: String, cwd: String?, fork: Boolean): AgentStart {
            fail?.let { throw IllegalArgumentException(it) }
            lastFork = fork
            lastCwd = cwd
            return started ?: AgentStart("agent-1", 1)
        }

        override fun list(scope: String): List<AgentEntry> = entries
        override fun send(agentId: String, message: String): AgentSend {
            fail?.let { throw IllegalArgumentException(it) }
            sent += agentId to message
            return AgentSend("msg-1", agentId)
        }

        override fun interrupt(agentId: String) {
            fail?.let { throw IllegalArgumentException(it) }
            interrupted += agentId
        }
    }

    @Test
    fun subagentStartsAndReturnsId() {
        val host = FakeHost()
        val result = kotlinx.coroutines.runBlocking {
            SubagentTool().execute(
                args("description" to "look around", "prompt" to "inspect the repo", "cwd" to "sub"),
                ctxBase.copy(agents = host),
            )
        }
        assertFalse(result.isError)
        assertEquals("started subagent agent-1", result.text)
        assertEquals(false, host.lastFork)
        assertEquals("sub", host.lastCwd)
    }

    @Test
    fun subagentForkSetsForkFlag() {
        val host = FakeHost()
        val result = kotlinx.coroutines.runBlocking {
            SubagentTool(fork = true).execute(
                args("description" to "review", "prompt" to "review the diff"),
                ctxBase.copy(agents = host),
            )
        }
        assertEquals("started subagent agent-1", result.text)
        assertEquals(true, host.lastFork)
    }

    @Test
    fun subagentRequiresFieldsAndRuntime() {
        val noRuntime = kotlinx.coroutines.runBlocking {
            SubagentTool().execute(args("description" to "x", "prompt" to "y"), ctxBase)
        }
        assertTrue(noRuntime.isError)
        assertTrue(noRuntime.text.startsWith("subagents unavailable"))

        val host = FakeHost()
        val missing = kotlinx.coroutines.runBlocking {
            SubagentTool().execute(args("description" to "x"), ctxBase.copy(agents = host))
        }
        assertTrue(missing.isError)
        assertEquals("prompt is required", missing.text)
    }

    @Test
    fun subagentSurfacesDepthError() {
        val host = FakeHost(fail = "subagent depth 2 exceeds maxDepth 1")
        val result = kotlinx.coroutines.runBlocking {
            SubagentTool().execute(args("description" to "x", "prompt" to "y"), ctxBase.copy(agents = host))
        }
        assertTrue(result.isError)
        assertEquals("subagent depth 2 exceeds maxDepth 1", result.text)
    }

    @Test
    fun listAgentsRendersUpstreamRows() {
        val host = FakeHost(
            entries = listOf(
                AgentEntry("agent-1", "look around", "running", parentId = "root", depth = 1),
                AgentEntry("agent-2", "quiet", "inactive", parentId = "root", depth = 1),
            ),
        )
        val children = kotlinx.coroutines.runBlocking {
            ListAgentsTool().execute(buildJsonObject { }, ctxBase.copy(agents = host))
        }
        assertEquals("agent-1 [running] — look around\nagent-2 [inactive] — quiet", children.text)

        val descendants = kotlinx.coroutines.runBlocking {
            ListAgentsTool().execute(args("scope" to "descendants"), ctxBase.copy(agents = host))
        }
        assertEquals(
            "agent-1 [running] parent=root depth=1 — look around\n" +
                "agent-2 [inactive] parent=root depth=1 — quiet",
            descendants.text,
        )

        val empty = kotlinx.coroutines.runBlocking {
            ListAgentsTool().execute(buildJsonObject { }, ctxBase.copy(agents = FakeHost()))
        }
        assertEquals("(no subagents)", empty.text)
    }

    @Test
    fun listAgentsRejectsUnknownScope() {
        val result = kotlinx.coroutines.runBlocking {
            ListAgentsTool().execute(args("scope" to "all"), ctxBase.copy(agents = FakeHost()))
        }
        assertTrue(result.isError)
        assertEquals("scope must be children or descendants", result.text)
    }

    @Test
    fun sendMessageRendersReceiptAndPassesThrough() {
        val host = FakeHost()
        val result = kotlinx.coroutines.runBlocking {
            SendMessageTool().execute(args("agent_id" to "agent-1", "message" to "最新的发现？"), ctxBase.copy(agents = host))
        }
        assertEquals("message delivered to agent agent-1", result.text)
        assertEquals(listOf("agent-1" to "最新的发现？"), host.sent)
    }

    @Test
    fun interruptAgentRendersRequest() {
        val host = FakeHost()
        val result = kotlinx.coroutines.runBlocking {
            InterruptAgentTool().execute(args("agent_id" to "agent-1"), ctxBase.copy(agents = host))
        }
        assertEquals("interrupt requested for agent agent-1", result.text)
        assertEquals(listOf("agent-1"), host.interrupted)
    }

    @Test
    fun controlToolsAlsoRequireRuntime() {
        val send = kotlinx.coroutines.runBlocking {
            SendMessageTool().execute(args("agent_id" to "a", "message" to "m"), ctxBase)
        }
        val stop = kotlinx.coroutines.runBlocking {
            InterruptAgentTool().execute(args("agent_id" to "a"), ctxBase)
        }
        assertTrue(send.isError && stop.isError)
        assertEquals("subagents unavailable: no agent runtime is mounted", send.text)
    }

    @Test
    fun registryContainsAllFiveDelegationTools() {
        val names = BuiltinToolCatalog.allTools().map { it.name }
        listOf("subagent", "subagent_fork", "list_agents", "send_message", "interrupt_agent").forEach {
            assertTrue(it in names, "missing $it")
        }
        // M19 起：28 + exit_plan_mode + load_workspace_dependencies + skill = 31；M23 加 workflow = 32
        assertEquals(32, names.size)
    }
}
