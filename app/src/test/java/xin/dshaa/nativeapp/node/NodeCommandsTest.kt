package xin.dshaa.nativeapp.node

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3-c：`ctx.commands.register` 的描述解析。
 *
 * 名字语法必须和原生 `parseCommandLine`（`^/[a-z][a-z0-9_-]*`）一致，
 * 否则命令会出现在面板里却永远命中不了输入框 —— 这是最容易漏的一条。
 */
class NodeCommandsTest {

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun parsesNameDescriptionHintAndOwner() {
        val spec = NodeCommands.parse(
            obj("""{"name":"hello-node","description":"演示","hint":"任意文本","owner":"./plugins/x/index.js"}"""),
        )!!
        assertEquals("hello-node", spec.name)
        assertEquals("演示", spec.description)
        assertEquals("任意文本", spec.hint)
        assertFalse(spec.attachments)
        assertEquals("./plugins/x/index.js", spec.owner)
        assertEquals("/hello-node", spec.displayLabel())
    }

    @Test
    fun rejectsNamesNativeParserWouldNeverMatch() {
        assertNull(NodeCommands.parse(obj("""{"name":"Hello"}""")))
        assertNull(NodeCommands.parse(obj("""{"name":"1hello"}""")))
        assertNull(NodeCommands.parse(obj("""{"name":"a b"}""")))
        assertNull(NodeCommands.parse(obj("""{"name":"中文命令"}""")))
        assertNull(NodeCommands.parse(obj("""{"name":""}""")))
        assertNull(NodeCommands.parse(obj("""{"description":"没有名字"}""")))
    }

    @Test
    fun acceptsHyphenUnderscoreDigits_afterFirstLetter() {
        listOf("a", "hello_node", "hello-node-2", "x9").forEach { name ->
            assertTrue("应接受 $name", NodeCommands.validName(name))
            assertEquals(name, NodeCommands.parse(obj("""{"name":"$name"}"""))!!.name)
        }
    }

    @Test
    fun lowercasesAndDedupesByName() {
        val list = NodeCommands.parseAll(
            listOf(
                obj("""{"name":"b-cmd","description":"旧"}"""),
                obj("""{"name":"a-cmd"}"""),
                obj("""{"name":"b-cmd","description":"新"}"""),
                obj("""{"name":"坏 名字"}"""),
            ),
        )
        assertEquals(listOf("a-cmd", "b-cmd"), list.map { it.name })
        assertEquals("新", list.first { it.name == "b-cmd" }.description)
    }

    @Test
    fun attachmentsFlagIsCarriedHonestly() {
        val spec = NodeCommands.parse(obj("""{"name":"withfile","attachments":true}"""))!!
        assertTrue(spec.attachments)
    }
}
