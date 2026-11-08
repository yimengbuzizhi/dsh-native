package xin.dshaa.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 契约 #1 的金标准：真机发出过的一次请求体（`request-golden.json`，10.5KB，account 通道）。
 *
 * 两件事：
 * 1. 反序列化后再序列化必须**逐字节相同**（guards encodeDefaults / explicitNulls / 字段顺序）；
 * 2. 顶层键序必须是 model → stream → messages → max_tokens → thinking → output_config → system → tools。
 *
 * 换上游版本时：用 `--es dsh_dump_request <path>` 重新抓一份，替换 fixture，跑通这条测试。
 */
class RequestGoldenTest {

    private val golden: String = javaClass.classLoader!!
        .getResourceAsStream("request-golden.json")!!
        .use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun roundTripsByteIdentically() {
        val decoded = ProtocolJson.json.decodeFromString(ApiRequest.serializer(), golden)
        assertEquals(golden, ProtocolJson.encodeRequest(decoded))
    }

    @Test
    fun topLevelKeyOrderMatchesContract() {
        val keys = listOf("model", "stream", "messages", "max_tokens", "thinking", "output_config", "system", "tools")
        val positions = keys.map { "\"$it\":" to golden.indexOf("\"$it\":") }
        positions.forEach { (key, pos) -> assertTrue(pos > 0, "missing key $key") }
        val sorted = positions.sortedBy { it.second }
        assertEquals(keys.size, sorted.size)
        sorted.forEachIndexed { index, (key, _) ->
            assertEquals("\"${keys[index]}\":", key, "key order mismatch at $index")
        }
    }

    @Test
    fun requestHasStreamingAndThinkingEnabled() {
        val decoded = ProtocolJson.json.decodeFromString(ApiRequest.serializer(), golden)
        assertTrue(decoded.stream)
        assertEquals("enabled", decoded.thinking?.type)
        assertTrue(decoded.messages.isNotEmpty())
        assertTrue(!decoded.system.isNullOrBlank())
        assertTrue(!decoded.tools.isNullOrEmpty())
    }
}
