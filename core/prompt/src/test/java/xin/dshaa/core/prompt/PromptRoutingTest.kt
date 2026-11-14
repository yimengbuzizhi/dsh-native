package xin.dshaa.core.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** M85-E：prompt/v1 选择规则（默认 / 同 id 覆盖 / 停用 / 缺授权 / native 强制 / 覆盖回退）。 */
class PromptRoutingTest {

    private fun builtin(enabled: Boolean = true) = PromptCandidate(
        promptId = BUILTIN_PROMPT_ID,
        label = "dsH 提示词",
        builtin = true,
        enabled = enabled,
    )

    private fun plugin(
        promptId: String = BUILTIN_PROMPT_ID,
        pluginId: String = "demo.prompt",
        enabled: Boolean = true,
        permitted: Boolean = true,
    ) = PromptCandidate(
        promptId = promptId,
        label = "演示提示词",
        pluginId = pluginId,
        enabled = enabled,
        permitted = permitted,
    )

    @Test
    fun emptyPrefUsesBuiltinWhenNoOverride() {
        val active = assertIs<PromptResolution.Active>(PromptRouting.resolve("", listOf(builtin())))
        assertTrue(active.prompt.builtin)
        assertEquals(BUILTIN_PROMPT_ID, active.prompt.promptId)
    }

    @Test
    fun sameIdPluginOverridesDefault() {
        val active = assertIs<PromptResolution.Active>(PromptRouting.resolve("", listOf(builtin(), plugin())))
        assertEquals("demo.prompt", active.prompt.pluginId)
    }

    @Test
    fun nativeForcesBuiltinEvenWithOverride() {
        val active = assertIs<PromptResolution.Active>(
            PromptRouting.resolve("native", listOf(builtin(), plugin())),
        )
        assertTrue(active.prompt.builtin)
    }

    @Test
    fun disabledBuiltinWithoutOverrideIsUnavailable() {
        val unavailable = assertIs<PromptResolution.Unavailable>(PromptRouting.resolve("", listOf(builtin(false))))
        assertTrue(unavailable.reason.contains("已停用"))
    }

    @Test
    fun disabledSameIdPluginFallsBackToBuiltin() {
        val active = assertIs<PromptResolution.Active>(
            PromptRouting.resolve("", listOf(builtin(), plugin(enabled = false))),
        )
        assertTrue(active.prompt.builtin)
    }

    @Test
    fun missingPermissionIsUnavailable() {
        val unavailable = assertIs<PromptResolution.Unavailable>(
            PromptRouting.resolve("demo.prompt", listOf(builtin(), plugin(permitted = false))),
        )
        assertTrue(unavailable.reason.contains("prompt 授权"))
    }

    @Test
    fun pluginAndPromptIdSelectsExact() {
        val active = assertIs<PromptResolution.Active>(
            PromptRouting.resolve("demo.prompt#mark", listOf(builtin(), plugin(promptId = "mark"))),
        )
        assertEquals("mark", active.prompt.promptId)
        assertEquals("demo.prompt#mark", PromptRouting.prefOf(active.prompt))
    }

    @Test
    fun unknownPluginIsUnavailable() {
        val unavailable = assertIs<PromptResolution.Unavailable>(PromptRouting.resolve("nope", listOf(builtin())))
        assertTrue(unavailable.reason.contains("未安装"))
    }

    @Test
    fun builtinPrefFallsBackToBuiltinAfterOverrideDisabled() {
        val active = assertIs<PromptResolution.Active>(
            PromptRouting.resolve(BUILTIN_PROMPT_ID, listOf(builtin(), plugin(enabled = false))),
        )
        assertTrue(active.prompt.builtin)
        assertEquals("native", PromptRouting.prefOf(active.prompt))
    }
}
