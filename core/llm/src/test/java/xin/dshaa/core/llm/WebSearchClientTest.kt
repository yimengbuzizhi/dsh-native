package xin.dshaa.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DeepSeek 原生搜索的响应解析（夹具取自 Anthropic `web_search_20250305` 的响应形状）：
 * `web_search_tool_result.content[]` 里的 `web_search_result` + text 块的 `citations`。
 */
class WebSearchClientTest {

    private fun client() = WebSearchClient(config = LlmConfig(apiKey = "test"))

    private fun parse(json: String) = client().parseResponse(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `parses sources and joins citation snippets`() {
        val outcome = parse(
            """
            {"content":[
              {"type":"text","text":"DeepSeek 的搜索由服务端完成。","citations":[
                {"type":"web_search_result_location","url":"https://example.com/a","cited_text":"A 的片段"},
                {"url":"https://example.com/b","cited_text":"B 的片段"}
              ]},
              {"type":"web_search_tool_result","content":[
                {"type":"web_search_result","url":"https://example.com/a","title":"A 标题","page_age":"2 days ago"},
                {"type":"web_search_result","url":"https://example.com/b","title":"B"},
                {"type":"web_search_result","url":"https://example.com/a","title":"重复 URL"}
              ]}
            ]}
            """.trimIndent(),
        )
        assertEquals(2, outcome.sources.size)
        assertEquals("https://example.com/a", outcome.sources[0].url)
        assertEquals("A 标题", outcome.sources[0].title)
        assertEquals("A 的片段", outcome.sources[0].snippet)
        assertEquals("2 days ago", outcome.sources[0].publishedAt)
        assertEquals("B 的片段", outcome.sources[1].snippet)
        assertEquals("DeepSeek 的搜索由服务端完成。", outcome.answer)
    }

    @Test
    fun `missing result blocks fail like upstream`() {
        val error = assertFailsWith<WebSearchClient.SearchException> {
            parse("""{"content":[{"type":"text","text":"no search happened"}]}""")
        }
        assertTrue(error.message!!.contains("no web_search_tool_result"))
    }

    @Test
    fun `no answer text yields null answer`() {
        val outcome = parse(
            """{"content":[{"type":"web_search_tool_result","content":[{"type":"web_search_result","url":"https://x.test","title":""}]}]}""",
        )
        assertNull(outcome.answer)
        assertEquals(1, outcome.sources.size)
        assertNull(outcome.sources[0].title)
    }

    @Test
    fun `search endpoint derives v1 from chat base`() {
        val client = client()
        assertEquals("https://api.deepseek.com/anthropic/v1/messages", client.messagesUrl("https://api.deepseek.com/anthropic"))
        assertEquals("https://api.deepseek.com/anthropic/v1/messages", client.messagesUrl("https://api.deepseek.com/anthropic/v1"))
        assertEquals("https://x.test/v1/messages", client.messagesUrl("https://x.test/"))
    }
}
