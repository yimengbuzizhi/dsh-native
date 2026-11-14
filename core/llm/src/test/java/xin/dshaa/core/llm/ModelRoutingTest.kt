package xin.dshaa.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** M85-E：model/v1 选择规则（协议兜底 / 同 id 覆盖 / 停用 / 如实报错 / 内置插件 id 映射）。 */
class ModelRoutingTest {

    private fun deepseek(enabled: Boolean = true) = ModelCandidate(
        providerId = MODEL_PROVIDER_DEEPSEEK,
        label = "DeepSeek",
        builtinPluginId = BUILTIN_MODEL_DEEPSEEK,
        builtin = true,
        enabled = enabled,
    )

    private fun openai(enabled: Boolean = true) = ModelCandidate(
        providerId = MODEL_PROVIDER_OPENAI,
        label = "OpenAI",
        builtinPluginId = BUILTIN_MODEL_OPENAI,
        builtin = true,
        enabled = enabled,
    )

    private fun plugin(
        providerId: String = MODEL_PROVIDER_DEEPSEEK,
        pluginId: String = "demo.model",
        enabled: Boolean = true,
    ) = ModelCandidate(
        providerId = providerId,
        label = "演示模型",
        pluginId = pluginId,
        enabled = enabled,
    )

    @Test
    fun emptySelectionFollowsProtocol() {
        val active = assertIs<ModelResolution.Active>(
            ModelRouting.resolve("", "anthropic", listOf(deepseek(), openai())),
        )
        assertEquals(MODEL_PROVIDER_DEEPSEEK, active.model.providerId)
        val openAiActive = assertIs<ModelResolution.Active>(
            ModelRouting.resolve(null, "openai", listOf(deepseek(), openai())),
        )
        assertEquals(MODEL_PROVIDER_OPENAI, openAiActive.model.providerId)
    }

    @Test
    fun sameIdPluginOverridesBuiltin() {
        val active = assertIs<ModelResolution.Active>(
            ModelRouting.resolve("deepseek", "anthropic", listOf(deepseek(), plugin())),
        )
        assertEquals("demo.model", active.model.pluginId)
    }

    @Test
    fun disabledBuiltinWithoutOverrideIsUnavailable() {
        val unavailable = assertIs<ModelResolution.Unavailable>(
            ModelRouting.resolve("deepseek", "anthropic", listOf(deepseek(false))),
        )
        assertTrue(unavailable.reason.contains("已停用"))
    }

    @Test
    fun disabledPluginFallsBackToBuiltin() {
        val active = assertIs<ModelResolution.Active>(
            ModelRouting.resolve("deepseek", "anthropic", listOf(deepseek(), plugin(enabled = false))),
        )
        assertTrue(active.model.builtin)
    }

    @Test
    fun builtinPluginIdMapsToProvider() {
        val active = assertIs<ModelResolution.Active>(
            ModelRouting.resolve(BUILTIN_MODEL_DEEPSEEK, "openai", listOf(deepseek(), openai())),
        )
        assertEquals(MODEL_PROVIDER_DEEPSEEK, active.model.providerId)
    }

    @Test
    fun unknownProviderIsUnavailable() {
        val unavailable = assertIs<ModelResolution.Unavailable>(
            ModelRouting.resolve("nope", "anthropic", listOf(deepseek())),
        )
        assertTrue(unavailable.reason.contains("未安装"))
    }

    @Test
    fun bothBuiltinAndOverrideDisabledMentionsBoth() {
        val unavailable = assertIs<ModelResolution.Unavailable>(
            ModelRouting.resolve("deepseek", "anthropic", listOf(deepseek(false), plugin(enabled = false))),
        )
        assertTrue(unavailable.reason.contains("内置 deepseek 也停用"))
    }
}
