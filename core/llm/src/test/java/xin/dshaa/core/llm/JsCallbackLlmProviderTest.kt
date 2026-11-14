package xin.dshaa.core.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import xin.dshaa.core.protocol.ApiMessage
import xin.dshaa.core.protocol.ApiRequest
import xin.dshaa.core.protocol.ContentBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M84-M：插件 `stream(request, emit)` 的宿主侧折算。
 *
 * 覆盖：老式 `{text}`、逐块 `emit`（含后缀补齐、不重复整块）、最终结果里的 `toolCalls`
 * （折成 tool_use 块）、`emit` 工具块、失败信封、非 JSON、`call` 抛异常。
 */
class JsCallbackLlmProviderTest {

    private fun request(model: String = "plugin-model") = ApiRequest(
        model = model,
        messages = listOf(ApiMessage("user", listOf(ContentBlock.Text("hi")))),
        maxTokens = 128,
    )

    private fun provider(call: (String, (String) -> Unit) -> String) = JsCallbackLlmProvider(
        id = "test.provider",
        label = "测试供应商",
        modelHint = "hint-model",
        call = call,
    )

    private fun eventsOf(call: (String, (String) -> Unit) -> String): List<LlmEvent> = runBlocking {
        provider(call).stream(request(), LlmConfig(apiKey = "test")).toList()
    }

    @Test
    fun `legacy text result becomes one text block`() {
        val events = eventsOf { _, _ -> """{"ok":true,"text":"你好" }""" }
        assertEquals(6, events.size, "events=$events")
        assertIs<LlmEvent.MessageStart>(events[0])
        assertEquals("test.provider:plugin", (events[0] as LlmEvent.MessageStart).id)
        val start = assertIs<LlmEvent.BlockStart>(events[1])
        assertEquals(0, start.index)
        assertEquals("text", start.type)
        assertEquals("你好", assertIs<LlmEvent.TextDelta>(events[2]).text)
        assertEquals(0, assertIs<LlmEvent.BlockStop>(events[3]).index)
        assertEquals("end_turn", assertIs<LlmEvent.MessageDelta>(events[4]).stopReason)
        assertEquals(LlmEvent.MessageStop, events[5])
    }

    /** 插件 `emit('文本')` 到宿主的真实形状：JSON 字符串。 */
    private fun text(value: String): String = JsonPrimitive(value).toString()

    @Test
    fun `emit chunks stream live and result suffix is appended once`() {
        val events = eventsOf { _, emit ->
            emit(text("A"))
            emit(text("B"))
            """{"ok":true,"text":"ABC"}"""
        }
        val deltas = events.filterIsInstance<LlmEvent.TextDelta>().map { it.text }
        assertEquals(listOf("A", "B", "C"), deltas, "events=$events")
        // 一个 text 块：BlockStart 只出现一次
        assertEquals(1, events.count { it is LlmEvent.BlockStart })
        assertEquals("ABC", deltas.joinToString(""))
    }

    @Test
    fun `emitted full text is not repeated by the result`() {
        val events = eventsOf { _, emit ->
            emit(text("全部"))
            """{"ok":true,"text":"全部"}"""
        }
        assertEquals(listOf("全部"), events.filterIsInstance<LlmEvent.TextDelta>().map { it.text })
    }

    @Test
    fun `result tool calls become tool_use blocks and stop_reason`() {
        val events = eventsOf { _, _ ->
            """{"ok":true,"text":"","stopReason":"tool_use","toolCalls":[
                 {"id":"call_1","name":"bash","input":{"command":"echo hi"}},
                 {"name":"m84m_ping","input":{}}]}"""
        }
        val starts = events.filterIsInstance<LlmEvent.BlockStart>()
        assertEquals(2, starts.size, "events=$events")
        assertEquals("tool_use", starts[0].type)
        assertEquals("call_1", starts[0].toolId)
        assertEquals("bash", starts[0].toolName)
        assertEquals(0, starts[0].index)
        // 没给 id 的工具块自动补一个
        assertEquals("tool_1", starts[1].toolId)
        assertEquals("m84m_ping", starts[1].toolName)
        val inputs = events.filterIsInstance<LlmEvent.ToolInputDelta>()
        assertEquals("""{"command":"echo hi"}""", inputs[0].partialJson)
        assertEquals("{}", inputs[1].partialJson)
        assertEquals(listOf(0, 1), events.filterIsInstance<LlmEvent.BlockStop>().map { it.index })
        assertEquals("tool_use", assertIs<LlmEvent.MessageDelta>(events.last { it is LlmEvent.MessageDelta }).stopReason)
    }

    @Test
    fun `emitted tool_use closes the open text block first`() {
        val events = eventsOf { _, emit ->
            emit(text("先查一下"))
            emit("""{"type":"tool_use","id":"t1","name":"m84m_ping","input":{"text":"x"}}""")
            """{"ok":true,"text":"先查一下","stopReason":"tool_use"}"""
        }
        val stops = events.filterIsInstance<LlmEvent.BlockStop>().map { it.index }
        assertEquals(listOf(0, 1), stops, "events=$events")
        val text = events.filterIsInstance<LlmEvent.BlockStart>()[0]
        val tool = events.filterIsInstance<LlmEvent.BlockStart>()[1]
        assertEquals("text", text.type)
        assertEquals("tool_use", tool.type)
        assertEquals("""{"text":"x"}""", events.filterIsInstance<LlmEvent.ToolInputDelta>().single().partialJson)
    }

    @Test
    fun `emit plain string and usage chunk are accepted`() {
        val events = eventsOf { _, emit ->
            emit(text("片段"))
            emit("""{"type":"usage","output":42}""")
            """{"ok":true,"text":"片段"}"""
        }
        assertEquals(listOf("片段"), events.filterIsInstance<LlmEvent.TextDelta>().map { it.text })
        assertEquals(42, assertIs<LlmEvent.MessageDelta>(events.last { it is LlmEvent.MessageDelta }).outputTokens)
    }

    @Test
    fun `late emitted input and final usage notify after text started`() {
        val events = eventsOf { _, emit ->
            emit(text("hi"))
            emit("""{"type":"usage","input":100,"output":3}""")
            emit("""{"type":"usage","output":4}""")
            """{"ok":true,"text":"hi","usage":{"inputTokens":90,"outputTokens":6}}"""
        }
        assertEquals(null, events.filterIsInstance<LlmEvent.MessageStart>().first().usage.inputTokens)
        val snapshots = events.filterIsInstance<LlmEvent.MessageDelta>().map { it.usage }
        assertEquals(listOf(3, 7, 6), snapshots.map { it.outputTokens })
        assertEquals(listOf(100, 100, 90), snapshots.map { it.inputTokens })
    }

    @Test
    fun `final zero usage is reported while missing remains unknown`() {
        val missing = eventsOf { _, _ -> """{"ok":true,"text":"hi"}""" }
        assertEquals(null, missing.filterIsInstance<LlmEvent.MessageDelta>().last().usage.outputTokens)
        val zero = eventsOf { _, emit ->
            emit(text("hi"))
            """{"ok":true,"usage":{"input":0,"output":0}}"""
        }
        val usage = zero.filterIsInstance<LlmEvent.MessageDelta>().last().usage
        assertEquals(0, usage.inputTokens)
        assertEquals(0, usage.outputTokens)
    }

    @Test
    fun `failed envelope becomes Failed with plugin code`() {
        val events = eventsOf { _, _ -> """{"ok":false,"error":"插件说不行"}""" }
        val failed = assertIs<LlmEvent.Failed>(events.last())
        assertEquals("插件说不行", failed.message)
        assertEquals("PLUGIN_STREAM_ERROR", failed.code)
        assertFalse(events.any { it is LlmEvent.MessageStop })
    }

    @Test
    fun `non json and thrown call both fail without hanging`() {
        val nonJson = eventsOf { _, _ -> "不是 JSON" }
        assertIs<LlmEvent.Failed>(nonJson.last())

        val thrown = eventsOf { _, _ -> throw IllegalStateException("炸了") }
        val failed = assertIs<LlmEvent.Failed>(thrown.last())
        assertTrue(failed.message.contains("炸了"), "message=${failed.message}")
    }

    @Test
    fun `request json reaches the plugin intact`() {
        var seen: String? = null
        eventsOf { requestJson, _ ->
            seen = requestJson
            """{"ok":true,"text":"ok"}"""
        }
        val obj = Json.parseToJsonElement(seen!!).jsonObject
        assertEquals("plugin-model", obj["model"]?.toString()?.trim('"'))
        assertTrue(seen!!.contains("\"max_tokens\":128"), "request=$seen")
    }
}
