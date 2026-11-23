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
 * P3-b：`ctx.llm.register` 声明的解析口径。
 *
 * 这里的用例就是「不要假装流式」这条红线的回归：宿主把 callback-only 标成 usable 也没用，
 * 原生必须按 streamMode 纠正成不可用；声明式 provider 才进模型列表。
 */
class NodeLlmProvidersTest {

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun declarativeProvider_isUsableAndNamespaced() {
        val spec = NodeLlmProviders.parse(
            obj("""{"id":"my-provider","label":"我的供应商","protocol":"openai","baseUrl":"https://x/v1","model":"m1","owner":"pkg"}"""),
        )!!
        assertEquals("node:my-provider", spec.id)
        assertEquals("my-provider", spec.remoteId)
        assertTrue(spec.id.startsWith(NodeLlmProviders.PREFIX))
        assertEquals("我的供应商（m1）", spec.label)
        assertEquals("openai", spec.protocol)
        assertEquals("https://x/v1", spec.baseUrl)
        assertTrue(spec.usable)
        assertEquals(NodeLlmProviders.MODE_HTTP, spec.streamMode)
        assertFalse(spec.hasStreamCallback)
        assertEquals("pkg", spec.owner)
    }

    @Test
    fun callbackOnlyProvider_isNeverUsable_evenIfHostClaimsOtherwise() {
        val spec = NodeLlmProviders.parse(
            obj("""{"id":"cb","hasStreamCallback":true,"streamMode":"callback","usable":true,"reason":"host says ok"}"""),
        )!!
        assertFalse("原生调不到 JS stream()：必须不可用", spec.usable)
        assertEquals(NodeLlmProviders.MODE_CALLBACK, spec.streamMode)
        assertTrue(spec.hasStreamCallback)
        assertTrue("原因要能显示给人看", spec.reason.isNotBlank())
    }

    @Test
    fun httpPlusCallback_isUsableButKeepsCallbackFlag() {
        val spec = NodeLlmProviders.parse(
            obj("""{"id":"both","hasStreamCallback":true,"streamMode":"http+callback","model":"m"}"""),
        )!!
        assertTrue("声明了 baseUrl/model → 原生 HTTP 通道可用", spec.usable)
        assertTrue(spec.hasStreamCallback)
        assertEquals(NodeLlmProviders.MODE_HTTP_CALLBACK, spec.streamMode)
    }

    @Test
    fun missingId_isRejected() {
        assertNull(NodeLlmProviders.parse(obj("""{"label":"无名"}""")))
        assertNull(NodeLlmProviders.parse(obj("""{"id":"   "}""")))
        assertNull(NodeLlmProviders.parse(obj("{}")))
    }

    @Test
    fun unknownProtocol_fallsBackToEmpty_followHost() {
        assertEquals("", NodeLlmProviders.parse(obj("""{"id":"a","protocol":"grpc"}"""))!!.protocol)
        assertEquals("anthropic", NodeLlmProviders.parse(obj("""{"id":"a","protocol":"Anthropic"}"""))!!.protocol)
    }

    @Test
    fun parseAll_dedupesById_lastWins_andSorts() {
        val list = NodeLlmProviders.parseAll(
            listOf(
                obj("""{"id":"b","label":"B 旧"}"""),
                obj("""{"id":"a","label":"A"}"""),
                obj("""{"id":"b","label":"B 新"}"""),
                obj("""{"label":"坏条目"}"""),
            ),
        )
        assertEquals(listOf("node:a", "node:b"), list.map { it.id })
        assertEquals("B 新", list.first { it.remoteId == "b" }.label)
        assertTrue(NodeLlmProviders.isNativeId("node:a"))
        assertFalse(NodeLlmProviders.isNativeId("openai"))
    }

    @Test
    fun displayLabel_marksNodeOrigin() {
        val spec = NodeLlmProviders.parse(obj("""{"id":"x","label":"X"}"""))!!
        assertEquals("[Node] X", NodeLlmProviders.displayLabel(spec))
    }
}
