package xin.dshaa.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M85-A：Surface 协议解析的纯逻辑单测（不碰 Android/QuickJS）。
 *
 * 重点：18 原语白名单、bind 只读表达式、list item 模板、动作 id、节点/深度上限。
 */
class SurfaceSpecTest {

    @Test
    fun parsesEighteenPrimitivesAndListTemplate() {
        val raw = """
            {"title":"M85-A 壳","tree":{"type":"column","gap":8,"children":[
              {"type":"text","text":"标题","size":20,"weight":"bold"},
              {"type":"list","bind":"chat.messages","item":{"type":"column","children":[
                {"type":"text","bind":"item.role","size":11,"color":"faint"},
                {"type":"markdown","bind":"item.text"}
              ]}},
              {"type":"row","children":[
                {"type":"textfield","bind":"chat.draft","placeholder":"说点什么","onChange":"draft","onSubmit":"send"},
                {"type":"button","label":"发送","icon":"send","onTap":"send"}
              ]}
            ]}}
        """.trimIndent()
        val result = SurfaceSpec.parse(raw)
        assertTrue(result.error.orEmpty(), result.ok)
        val tree = result.tree!!
        assertEquals("M85-A 壳", tree.title)
        assertEquals("column", tree.root.type)
        assertEquals("text", tree.root.children[0].type)
        assertEquals("标题", tree.root.children[0].string("text"))
        val list = tree.root.children[1]
        assertEquals("list", list.type)
        assertEquals("chat.messages", list.bind)
        assertEquals("column", list.item!!.type)
        assertEquals("item.role", list.item!!.children[0].bind)
        val row = tree.root.children[2]
        assertEquals("textfield", row.children[0].type)
        assertEquals("draft", row.children[0].onChange)
        assertEquals("send", row.children[0].onSubmit)
        assertEquals("send", row.children[1].onTap)
    }

    @Test
    fun rejectsUnknownPrimitive() {
        val result = SurfaceSpec.parse("""{"tree":{"type":"magic","text":"x"}}""")
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("未知原语"))
    }

    @Test
    fun rejectsMissingTreeNode() {
        assertFalse(SurfaceSpec.parse("""{"title":"x"}""").ok)
        assertFalse(SurfaceSpec.parse("""{"tree":{"type":"text"}} 坏""").ok)
        assertFalse(SurfaceSpec.parse("not json").ok)
    }

    @Test
    fun bindOnlyReadOnlyPathWithAllowedRoot() {
        assertTrue(SurfaceSpec.validBind("chat.messages"))
        assertTrue(SurfaceSpec.validBind("item.text"))
        assertTrue(SurfaceSpec.validBind("sessions"))
        assertTrue(SurfaceSpec.validBind("!chat.turn.active"))
        assertFalse(SurfaceSpec.validBind("process.env"))
        assertFalse(SurfaceSpec.validBind("chat"))
        assertFalse(SurfaceSpec.validBind("chat.messages[0].text"))
        assertFalse(SurfaceSpec.validBind("chat..text"))
        assertFalse(SurfaceSpec.validBind(""))
    }

    @Test
    fun itemBindOnlyAllowedInListTemplate() {
        // 普通节点上的 item.* 丢
        val plain = SurfaceSpec.parse("""{"tree":{"type":"text","bind":"item.text"}}""").tree!!
        assertNull(plain.root.bind)
        // item 模板里允许
        val nested = SurfaceSpec.parse(
            """{"tree":{"type":"list","bind":"sessions","item":{"type":"text","bind":"item.title"}}}""",
        ).tree!!
        assertEquals("item.title", nested.root.item!!.bind)
    }

    @Test
    fun listRequiresItemTemplate() {
        val result = SurfaceSpec.parse("""{"tree":{"type":"list","bind":"sessions"}}""")
        assertFalse(result.ok)
        assertTrue(result.error.orEmpty().contains("item"))
    }

    @Test
    fun actionIdsAreRestricted() {
        val node = SurfaceSpec.parse(
            """{"tree":{"type":"button","label":"x","onTap":"send","onChange":"bad id/with space"}}""",
        ).tree!!.root
        assertEquals("send", node.onTap)
        assertNull(node.onChange)
        assertTrue(SurfaceSpec.validAction("openSession"))
        assertFalse(SurfaceSpec.validAction("a b"))
        assertFalse(SurfaceSpec.validAction(""))
    }

    @Test
    fun dropsUnknownPropsAndCapsText() {
        val longText = "x".repeat(SurfaceSpec.MAX_TEXT + 50)
        val node = SurfaceSpec.parse(
            """{"tree":{"type":"text","text":"$longText","dangerous":"rm -rf","size":12}}""",
        ).tree!!.root
        assertEquals(SurfaceSpec.MAX_TEXT, node.string("text")!!.length)
        assertNull(node.props["dangerous"])
        assertEquals(12, node.int("size"))
    }

    @Test
    fun rejectsTooDeepAndTooManyNodes() {
        var deep = "{" + """"type":"text","text":"x""" + "}"
        repeat(SurfaceSpec.MAX_DEPTH + 2) {
            deep = """{"type":"column","children":[$deep]}"""
        }
        assertFalse(SurfaceSpec.parse("""{"tree":$deep}""").ok)

        val many = (0..SurfaceSpec.MAX_NODES).joinToString(",") { """{"type":"text","text":"x"}""" }
        assertFalse(SurfaceSpec.parse("""{"tree":{"type":"column","children":[$many]}}""").ok)
    }

    @Test
    fun acceptsNestedListInsideItemTemplate() {
        val raw = """
            {"tree":{"type":"list","bind":"chat.visibleMessages","item":{"type":"column","children":[
              {"type":"text","bind":"item.role"},
              {"type":"list","bind":"item.toolCalls","item":{"type":"row","children":[
                {"type":"text","bind":"item.name"},
                {"type":"text","bind":"item.result"}
              ]}}
            ]}}}
        """.trimIndent()
        val result = SurfaceSpec.parse(raw)
        assertTrue(result.error.orEmpty(), result.ok)
        val outer = result.tree!!.root
        assertEquals("chat.visibleMessages", outer.bind)
        val nested = outer.item!!.children[1]
        assertEquals("item.toolCalls", nested.bind)
        assertEquals("item.result", nested.item!!.children[1].bind)
    }

    @Test
    fun approvalCommandIsWhitelisted() {
        assertTrue("approval" in SurfaceSpec.COMMANDS)
        assertTrue("send" in SurfaceSpec.COMMANDS)
        assertFalse("shell.exec" in SurfaceSpec.COMMANDS)
    }

    @Test
    fun attachmentCommandsAreWhitelisted() {
        // M85-B2：附件走宿主通用命令；插件拿不到 Activity/文件系统权限
        assertTrue("pickFile" in SurfaceSpec.COMMANDS)
        assertTrue("screenshot" in SurfaceSpec.COMMANDS)
        assertTrue("removeAttachment" in SurfaceSpec.COMMANDS)
        assertFalse("openDocument" in SurfaceSpec.COMMANDS)
    }

    @Test
    fun shellViewCommandIsWhitelisted() {
        // M85-B2-2：展开/收起是壳自己的状态，宿主只存通用 key-value
        assertTrue("setShellView" in SurfaceSpec.COMMANDS)
        assertTrue("toggleThinking" !in SurfaceSpec.COMMANDS) // 语义动作留在壳里
        assertTrue("answer" in SurfaceSpec.COMMANDS)
    }

    @Test
    fun goalScheduleAndFocusCommandsAreWhitelisted() {
        // M85-B3：goal 控制/提醒删除/后台会话聚焦都是宿主通用命令，壳只发动作
        assertTrue("goal" in SurfaceSpec.COMMANDS)
        assertTrue("deleteSchedule" in SurfaceSpec.COMMANDS)
        assertTrue("focusSession" in SurfaceSpec.COMMANDS)
        assertFalse("pauseGoal" in SurfaceSpec.COMMANDS)
    }

    @Test
    fun longPressIsAValidatedAction() {
        // M85-B3：消息长按引用——和 onTap 同一套动作 id 校验
        val node = SurfaceSpec.parse(
            """{"tree":{"type":"column","onLongPress":"quoteMessage","children":[
              {"type":"text","text":"x","onLongPress":"bad id/space"}
            ]}}""",
        ).tree!!.root
        assertEquals("quoteMessage", node.onLongPress)
        assertNull(node.children[0].onLongPress)
        assertTrue(SurfaceSpec.validAction("quoteMessage"))
    }

    @Test
    fun acceptsRawNodeWithoutWrapper() {
        val result = SurfaceSpec.parse("""{"type":"column","children":[{"type":"divider"}]}""")
        assertTrue(result.ok)
        assertEquals("column", result.tree!!.root.type)
        assertNull(result.tree!!.title)
    }

    @Test
    fun sanitizeStateKeepsOnlyProtocolKeys() {
        val obj = Json.parseToJsonElement(
            """{"chat":{"title":"a"},"sessions":[],"secret":"x","settings":{},"theme":{},"capabilities":{}}""",
        ).jsonObject
        val clean = SurfaceSpec.sanitizeState(obj)
        assertEquals(setOf("chat", "sessions", "settings", "theme", "capabilities"), clean.keys)
        assertEquals("a", clean["chat"]!!.jsonObject["title"]!!.jsonPrimitive.content)
    }
}
