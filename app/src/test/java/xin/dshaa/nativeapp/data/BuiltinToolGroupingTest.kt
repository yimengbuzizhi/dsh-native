package xin.dshaa.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * M88-C/D 新工具的内置插件归属（2026-11-17 用户追问后补的契约）。
 *
 * 本项目里「AI 工具插件化」= 工具名挂在一个可开关的内置插件组上：
 * `BuiltinPlugins.byTool(name)` 能找到组，关掉组后 `syncBuiltinTools` 会从 registry 注销。
 * `capability` / `notify` / `curl` 必须各归其组，不能是「注册了但关不掉」的野工具。
 *
 * docs/187 §6.5：`http_request` 改名 `curl`（模型只看得见 `curl`），旧名字只做执行端别名，
 * 所以它**不在**任何组的 `tools` 里——`byTool("http_request")` 返回 null 是预期。
 */
class BuiltinToolGroupingTest {

    @Test
    fun newToolsBelongToTogglableBuiltinGroups() {
        val expected = mapOf(
            "capability" to "builtin.capability",
            "notify" to "builtin.capability",
            "curl" to "builtin.web",
        )
        expected.forEach { (tool, groupId) ->
            val group = BuiltinPlugins.byTool(tool)
            assertNotNull("$tool 没有归属任何内置插件组", group)
            assertEquals("$tool 归错组", groupId, group!!.id)
            // 组里确实列了它（BuiltinPluginStore.toolEnabled 与 registry 注销都按 group.tools 走）。
            assertEquals(true, tool in group.tools)
        }
    }

    @Test
    fun curlIsInTheWebBuiltinGroup() {
        val web = BuiltinPlugins.byId("builtin.web") ?: error("缺内置组 builtin.web")
        assertEquals(setOf("web_search", "web_fetch", "curl"), web.tools)
        assertEquals(null, BuiltinPlugins.byTool("http_request"))
    }
}
