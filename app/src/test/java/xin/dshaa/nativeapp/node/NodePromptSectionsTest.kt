package xin.dshaa.nativeapp.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xin.dshaa.core.prompt.PromptSection

/**
 * docs/187 §九 第 11 项（P3-a 回归）：Node 插件提示词段只认领自己的，
 * 不混入 dsh identity/persona；关掉 Node 宿主后段落消失。
 *
 * 生产调用点：`AppViewModel.pluginPromptSections()` → [NodePromptSections.merge]。
 */
class NodePromptSectionsTest {

    private fun section(name: String, text: String, order: Int = 500) =
        PromptSection(name = name, order = order, text = text)

    private val native = listOf(
        section("identity", "你是鲸歌，回答要简洁。", order = 10),
        section("persona", "用户设定的人设。", order = 20),
        section("native-plugin/extra", "原生插件段", order = 100),
    )

    @Test
    fun nodeSectionsCannotClaimDshIdentityOrPersona() {
        val node = listOf(
            section("identity", "我是被三方插件顶掉的身份", order = 1),
            section("persona", "篡改人设", order = 2),
            section("hello-plugin/greeting", "hello from node plugin", order = 500),
        )
        val merged = NodePromptSections.merge(native, node, hostEnabled = true)

        assertEquals("identity 只应有一份（原生的）", 1, merged.count { it.name == "identity" })
        assertEquals("原生身份文本不能被覆盖", "你是鲸歌，回答要简洁。", merged.first { it.name == "identity" }.text)
        assertEquals(1, merged.count { it.name == "persona" })
        assertEquals("原生人设文本不能被覆盖", "用户设定的人设。", merged.first { it.name == "persona" }.text)
        assertTrue("插件自己的段要保留", merged.any { it.name == "hello-plugin/greeting" })
        assertEquals(4, merged.size)
    }

    @Test
    fun hostDisabled_dropsEveryNodeSection() {
        val node = listOf(section("hello-plugin/greeting", "hello from node plugin"))
        val merged = NodePromptSections.merge(native, node, hostEnabled = false)
        assertEquals(native, merged)
        assertFalse("关宿主后不允许任何 Node 段残留", merged.any { it.name.startsWith("hello-plugin/") })
    }

    @Test
    fun blankSectionsAreDropped() {
        val node = listOf(
            section("empty", "   "),
            section(" ", "有文本但没名字"),
            section("ok", "有效段"),
        )
        val merged = NodePromptSections.merge(emptyList(), node, hostEnabled = true)
        assertEquals(listOf("ok"), merged.map { it.name })
    }
}
