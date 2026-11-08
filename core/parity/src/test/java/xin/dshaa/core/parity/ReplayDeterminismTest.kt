package xin.dshaa.core.parity

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import xin.dshaa.core.llm.LlmClient
import xin.dshaa.core.llm.LlmConfig
import xin.dshaa.core.llm.LlmEvent
import xin.dshaa.core.protocol.ApiMessage
import xin.dshaa.core.protocol.ApiRequest
import xin.dshaa.core.protocol.ApiTool
import xin.dshaa.core.protocol.ContentBlock
import xin.dshaa.core.protocol.ProtocolJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 门 2 的「先证明尺子准」：同一份录制回放两次，事件序列必须逐条相同（docs/02 §七.2）。
 *
 * 覆盖：thinking / signature / text / tool_use / input_json_delta / message_delta。
 * 夹具按 37 字节硬切，故意切在 UTF-8 多字节字符中间——回放必须字节级还原。
 */
class ReplayDeterminismTest {

    private fun sse(vararg events: String): String =
        events.joinToString("\n\n") { it.trim() } + "\n\n"

    private fun toolCallStream(): String = sse(
        """event: message_start
data: {"type":"message_start","message":{"id":"msg_tool","model":"deepseek-flash","usage":{"input_tokens":128,"output_tokens":1}}}""",
        """event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
        """event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"先看目录里有什么。"}}""",
        """event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-abc-123"}}""",
        """event: content_block_stop
data: {"type":"content_block_stop","index":0}""",
        """event: content_block_start
data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_01","name":"bash"}}""",
        """event: content_block_delta
data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"command\":\"ls -1\"}"}}""",
        """event: content_block_stop
data: {"type":"content_block_stop","index":1}""",
        """event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":42}}""",
        """event: message_stop
data: {"type":"message_stop"}""",
    )

    private fun answerStream(): String = sse(
        """event: message_start
data: {"type":"message_start","message":{"id":"msg_answer","model":"deepseek-flash","usage":{"input_tokens":256,"output_tokens":1}}}""",
        """event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        """event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"目录里只有 a.txt。"}}""",
        """event: content_block_stop
data: {"type":"content_block_stop","index":0}""",
        """event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":12}}""",
        """event: message_stop
data: {"type":"message_stop"}""",
    )

    private val tools = listOf(
        ApiTool(
            name = "bash",
            description = "run shell",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("command", buildJsonObject { put("type", "string") }) })
            },
        ),
    )

    private fun request1(): ApiRequest = ApiRequest(
        model = "deepseek-flash",
        messages = listOf(ApiMessage("user", listOf(ContentBlock.Text("看看目录里有什么")))) +
            listOf(ApiMessage("user", listOf(ContentBlock.Text("Current runtime context. cwd=\"/tmp/ws\"")))),
        maxTokens = 1024,
        system = "you are dsh",
        tools = tools,
    )

    private fun request2(): ApiRequest = ApiRequest(
        model = "deepseek-flash",
        messages = listOf(
            ApiMessage("user", listOf(ContentBlock.Text("看看目录里有什么"))),
            ApiMessage(
                "assistant",
                listOf(
                    ContentBlock.Thinking("先看目录里有什么。", "sig-abc-123"),
                    ContentBlock.ToolUse("toolu_01", "bash", buildJsonObject { put("command", "ls -1") }),
                ),
            ),
            ApiMessage("user", listOf(ContentBlock.ToolResult("toolu_01", listOf(ContentBlock.Text("a.txt")), false))),
        ),
        maxTokens = 1024,
        system = "you are dsh",
        tools = tools,
    )

    private fun runOnce(port: Int, request1: ApiRequest, request2: ApiRequest): List<String> = runBlocking {
        val client = LlmClient()
        val config = LlmConfig(apiKey = "test-token", baseUrl = "http://127.0.0.1:$port")
        val first = client.stream(request1, config).toList()
        val second = client.stream(request2, config).toList()
        // 断言关键事件被正确解析，避免「两边都空」也算 0 diff
        assertTrue(first.any { it is LlmEvent.ThinkingDelta }, "events=$first")
        assertTrue(first.any { it is LlmEvent.ToolInputDelta }, "events=$first")
        assertTrue(first.any { it is LlmEvent.BlockStart && it.type == "tool_use" }, "events=$first")
        assertTrue(first.any { it is LlmEvent.MessageDelta && it.stopReason == "tool_use" }, "events=$first")
        assertTrue(second.any { it is LlmEvent.TextDelta }, "events=$second")
        (first + second).map { it.toString() }
    }

    @Test
    fun `同一份录制回放两次 0 diff`() {
        val req1 = request1()
        val req2 = request2()
        val bytes1 = ProtocolJson.encodeRequest(req1).toByteArray(Charsets.UTF_8)
        val bytes2 = ProtocolJson.encodeRequest(req2).toByteArray(Charsets.UTF_8)
        val captures = listOf(
            SseCapture.ofWholeBody(toolCallStream().toByteArray(Charsets.UTF_8), chunkSize = 37, requestBody = bytes1),
            SseCapture.ofWholeBody(answerStream().toByteArray(Charsets.UTF_8), chunkSize = 37, requestBody = bytes2),
        )
        ReplayServer(captures, port = 0).start().use { serverA ->
            val a = runOnce(serverA.actualPort, req1, req2)
            // 每次 run 都换一个干净的回放器：fixture 只能消费一次
            ReplayServer(captures, port = 0).start().use { serverB ->
                val b = runOnce(serverB.actualPort, req1, req2)
                assertEquals(a, b, "两次回放事件序列应逐条相同")
                assertEquals(2, serverA.requests.size, "runA 应该发两次请求")
                assertEquals(2, serverB.requests.size, "runB 应该发两次请求")
                assertTrue(serverA.problems.isEmpty(), "回放器不应报错：${serverA.problems}")
                assertTrue(serverB.problems.isEmpty(), "回放器不应报错：${serverB.problems}")
                assertEquals(ParityJson.sha256(bytes1), serverA.requests[0].bodySha256, "第 1 次请求体应字节一致")
                assertEquals(ParityJson.sha256(bytes2), serverA.requests[1].bodySha256, "第 2 次请求体应字节一致")
                assertEquals(ParityJson.sha256(bytes1), serverB.requests[0].bodySha256, "两次 run 的请求体应一致")
            }
        }
    }

    @Test
    fun `strictMatch 按请求哈希匹配而不是按序`() {
        val req1 = request1()
        val req2 = request2()
        val captures = listOf(
            SseCapture.ofWholeBody(answerStream().toByteArray(), 64, ProtocolJson.encodeRequest(req2).toByteArray()),
            SseCapture.ofWholeBody(toolCallStream().toByteArray(), 64, ProtocolJson.encodeRequest(req1).toByteArray()),
        )
        ReplayServer(captures, port = 0, strictMatch = true).start().use { server ->
            val events = runBlocking {
                LlmClient().stream(req1, LlmConfig(apiKey = "t", baseUrl = "http://127.0.0.1:${server.actualPort}")).toList()
            }
            assertTrue(events.any { it is LlmEvent.BlockStart && it.type == "tool_use" }, "应按哈希拿到工具调用那份夹具")
            assertTrue(server.problems.isEmpty())
        }
    }

    @Test
    fun `loops 允许一次起服跑两次 run`() {
        val req = request1()
        val capture = SseCapture.ofWholeBody(
            toolCallStream().toByteArray(),
            chunkSize = 50,
            requestBody = ProtocolJson.encodeRequest(req).toByteArray(),
        )
        ReplayServer(listOf(capture), port = 0, loops = 2).start().use { server ->
            val cfg = LlmConfig(apiKey = "t", baseUrl = "http://127.0.0.1:${server.actualPort}")
            repeat(2) {
                val events = runBlocking { LlmClient().stream(req, cfg).toList() }
                assertTrue(events.any { it is LlmEvent.ToolInputDelta }, "第 ${it + 1} 次 run 应拿到工具调用")
            }
            assertEquals(2, server.requests.size)
            assertTrue(server.problems.isEmpty(), server.problems.toString())
        }
    }

    @Test
    fun `realTime 模式按录制间隔回放`() {
        val body = answerStream().toByteArray()
        val cut = listOf(body.copyOfRange(0, 40), body.copyOfRange(40, 90), body.copyOfRange(90, body.size))
        val capture = SseCapture.of(cut, deltasMs = listOf(0, 60, 120))
        ReplayServer(listOf(capture), port = 0, realTime = true).start().use { server ->
            val started = System.nanoTime()
            val events = runBlocking {
                LlmClient().stream(
                    ApiRequest(model = "m", messages = listOf(ApiMessage("user", listOf(ContentBlock.Text("hi")))), maxTokens = 8),
                    LlmConfig(apiKey = "t", baseUrl = "http://127.0.0.1:${server.actualPort}"),
                ).toList()
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(events.any { it is LlmEvent.TextDelta })
            assertTrue(elapsedMs >= 100, "realTime 应至少等 120ms，实际 ${elapsedMs}ms")
        }
    }
}
