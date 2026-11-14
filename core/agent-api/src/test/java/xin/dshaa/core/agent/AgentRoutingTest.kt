package xin.dshaa.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// M85-D：agent/v1 选择规则的单测（纯 JVM，不依赖 app / Android）。
//
// 覆盖硬验收的四条：默认内置、可被同 id 覆盖、停用后消失（安全提示）、
// 选中的第三方停用后不再悄悄回退。
class AgentRoutingTest {

    private val builtin = AgentCandidate(
        agentId = BUILTIN_AGENT_ID,
        label = "dsH 引擎（内置）",
        pluginId = null,
        builtin = true,
    )

    private fun thirdParty(
        agentId: String = "m85.echo",
        pluginId: String = "m85.echo-agent",
        enabled: Boolean = true,
        permitted: Boolean = true,
    ) = AgentCandidate(
        agentId = agentId,
        label = "echo 回合",
        pluginId = pluginId,
        builtin = false,
        enabled = enabled,
        permitted = permitted,
    )

    @Test
    fun blankNativeAndBuiltinIdAllPickTheBuiltinImplementation() {
        for (pref in listOf(null, "", "  ", "native", "NATIVE", BUILTIN_AGENT_ID)) {
            val resolution = AgentRouting.resolve(pref, listOf(builtin))
            val agent = (resolution as AgentResolution.Active).agent
            assertEquals(BUILTIN_AGENT_ID, agent.agentId)
            assertTrue(agent.builtin)
        }
    }

    @Test
    fun sameIdThirdPartyOverridesBuiltinForDefaultPref() {
        val override = thirdParty(agentId = BUILTIN_AGENT_ID, pluginId = "m85.sameid.agent")
        val agent = (AgentRouting.resolve("builtin.agent.dsh", listOf(builtin, override)) as AgentResolution.Active).agent
        assertEquals("m85.sameid.agent", agent.pluginId)
        assertEquals(BUILTIN_AGENT_ID, agent.agentId)
        assertTrue(!agent.builtin)
    }

    @Test
    fun nativeForcesBuiltinEvenWhenSameIdOverrideExists() {
        val override = thirdParty(agentId = BUILTIN_AGENT_ID, pluginId = "m85.sameid.agent")
        val agent = (AgentRouting.resolve("native", listOf(builtin, override)) as AgentResolution.Active).agent
        assertEquals(BUILTIN_AGENT_ID, agent.agentId)
        assertTrue(agent.builtin)
        // 与壳一致：`native` 不给同 id 插件接管；空 / builtin.agent.dsh 才按默认角色覆盖。
        val viaEmpty = (AgentRouting.resolve(null, listOf(builtin, override)) as AgentResolution.Active).agent
        assertEquals("m85.sameid.agent", viaEmpty.pluginId)
    }

    @Test
    fun disabledSameIdOverrideFallsBackToBuiltinRole() {
        // 同 id 覆盖插件停用 → 回退内置实现（「覆盖后回退原实现」）；不是安全模式。
        val pref = "m85.sameid.agent#builtin.agent.dsh"
        val agent = (AgentRouting.resolve(
            pref,
            listOf(builtin, thirdParty(agentId = BUILTIN_AGENT_ID, pluginId = "m85.sameid.agent", enabled = false)),
        ) as AgentResolution.Active).agent
        assertEquals(BUILTIN_AGENT_ID, agent.agentId)
        assertTrue(agent.builtin)
    }

    @Test
    fun disabledBuiltinWithoutOverrideIsSafetyMode() {
        val resolution = AgentRouting.resolve("native", listOf(builtin.copy(enabled = false)))
        val unavailable = resolution as AgentResolution.Unavailable
        assertTrue(unavailable.reason.contains("已停用"), unavailable.reason)
    }

    @Test
    fun disabledBuiltinStillAllowsSameIdThirdPartyOverrideForDefaultRole() {
        val override = thirdParty(agentId = BUILTIN_AGENT_ID, pluginId = "m85.sameid.agent")
        val agent = (AgentRouting.resolve(
            BUILTIN_AGENT_ID,
            listOf(builtin.copy(enabled = false), override),
        ) as AgentResolution.Active).agent
        assertEquals("m85.sameid.agent", agent.pluginId)
    }

    @Test
    fun selectsThirdPartyByPluginIdOrExplicitAgentId() {
        val echo = thirdParty()
        for (pref in listOf("m85.echo-agent", "m85.echo-agent#m85.echo", "m85.echo")) {
            val agent = (AgentRouting.resolve(pref, listOf(builtin, echo)) as AgentResolution.Active).agent
            assertEquals("m85.echo", agent.agentId)
            assertEquals("m85.echo-agent", agent.pluginId)
        }
    }

    @Test
    fun disabledSelectedThirdPartyDoesNotSilentlyFallBack() {
        val resolution = AgentRouting.resolve("m85.echo-agent", listOf(builtin, thirdParty(enabled = false)))
        val unavailable = resolution as AgentResolution.Unavailable
        assertTrue(unavailable.reason.contains("已停用"), unavailable.reason)
    }

    @Test
    fun missingAgentPermissionIsReported() {
        val resolution = AgentRouting.resolve("m85.echo", listOf(builtin, thirdParty(permitted = false)))
        val unavailable = resolution as AgentResolution.Unavailable
        assertTrue(unavailable.reason.contains("agent 授权"), unavailable.reason)
    }

    @Test
    fun unknownSelectionIsReportedAsNotInstalled() {
        val resolution = AgentRouting.resolve("m85.nope", listOf(builtin))
        val unavailable = resolution as AgentResolution.Unavailable
        assertTrue(unavailable.reason.contains("未安装"), unavailable.reason)
    }

    @Test
    fun prefOfWritesNativeForBuiltinAndPluginPairForThirdParty() {
        val builtinResolved = (AgentRouting.resolve(null, listOf(builtin)) as AgentResolution.Active).agent
        assertEquals("native", AgentRouting.prefOf(builtinResolved))
        val thirdResolved = (AgentRouting.resolve("m85.echo", listOf(builtin, thirdParty())) as AgentResolution.Active).agent
        assertEquals("m85.echo-agent#m85.echo", AgentRouting.prefOf(thirdResolved))
    }
}
