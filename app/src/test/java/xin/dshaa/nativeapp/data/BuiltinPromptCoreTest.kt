package xin.dshaa.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dshaa.ui.shellapi.data.BuiltinPluginKind

/**
 * docs/190-E：内置提示词规则开关（`builtin.prompt.core`）。
 *
 * 它是「鲸歌自带提示词内容」的可关项，和 prompt/v1 实现插件（`builtin.prompt.dsh`）不是同一个东西：
 * 关掉只去掉打包 sections + plan:policy，用户人设/系统提示词/插件段/运行时上下文都保留。
 */
class BuiltinPromptCoreTest {

    @Test
    fun promptCoreIsADefaultOnBuiltinPromptPlugin() {
        val plugin = BuiltinPlugins.byId(BuiltinPlugins.PROMPT_CORE)
        assertNotNull("缺内置插件 ${BuiltinPlugins.PROMPT_CORE}", plugin)
        assertEquals("提示词：鲸歌内置规则", plugin!!.name)
        assertEquals(BuiltinPluginKind.Prompt, plugin.kind)
        assertTrue("默认必须开", plugin.defaultEnabled)
        assertTrue("它不拥有工具，只控制提示词内容", plugin.tools.isEmpty())
        assertNotEquals(BuiltinPlugins.NATIVE_PROMPT, plugin.id)
    }

    @Test
    fun promptCoreFollowsTheSameEnabledForRuleAsEveryDefaultOnPlugin() {
        val disabled = setOf(BuiltinPlugins.PROMPT_CORE)
        // 默认开：不在「关掉集合」里 = 开；显式关掉 = 关。
        assertEquals(true, BuiltinPluginStore.enabledFor(true, emptySet(), BuiltinPlugins.PROMPT_CORE))
        assertEquals(false, BuiltinPluginStore.enabledFor(true, disabled, BuiltinPlugins.PROMPT_CORE))
        // 其它组不受影响
        assertEquals(true, BuiltinPluginStore.enabledFor(true, disabled, "builtin.fs"))
    }

    @Test
    fun promptCoreIsNotAToolOrPageOwner() {
        assertEquals(null, BuiltinPlugins.byTool("builtin.prompt.core"))
        assertEquals(null, BuiltinPlugins.byScreen(BuiltinPlugins.PROMPT_CORE))
        assertEquals(emptyList<String>(), BuiltinPlugins.duplicateTools())
        assertEquals(emptyList<String>(), BuiltinPlugins.duplicateScreens())
    }
}
