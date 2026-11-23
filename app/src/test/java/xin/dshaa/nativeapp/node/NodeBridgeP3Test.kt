package xin.dshaa.nativeapp.node

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * P3-b / P3-c（docs/187 §9.2）：桥上的模型供应商与斜杠命令。
 *
 * 这里用和 [NodeBridgeCoreTest] 同一个假 transport，验的是**协议层**：
 * hello 里的 llm/commands、`llm.list` / `command.list` / `command.invoke` 的往返、
 * 未注册命令的明确失败、以及超时/取消不发呆（原生不能因为命令卡住而焊死输入框）。
 */
class NodeBridgeP3Test {

    private class FakeTransport : NodeTransport {
        val sent = CopyOnWriteArrayList<JsonObject>()

        override fun send(text: String): Boolean {
            sent += Json.parseToJsonElement(text).jsonObject
            return true
        }

        override fun close(reason: String) {}
    }

    private fun helloFrame(llm: Boolean = true, commands: Boolean = true): String = buildJsonObject {
        put("op", "host.hello")
        put("pid", 99)
        put("version", "v24.0.0-test")
        put("bootMs", 5)
        put("tools", JsonArray(emptyList()))
        put("plugins", JsonArray(emptyList()))
        put(
            "llm",
            JsonArray(
                if (!llm) {
                    emptyList()
                } else {
                    listOf(
                        buildJsonObject {
                            put("id", "follow-host")
                            put("label", "演示 · 跟随宿主")
                            put("protocol", "openai")
                            put("streamMode", "http")
                            put("usable", true)
                            put("owner", "./plugins/x/index.js")
                        },
                        buildJsonObject {
                            put("id", "callback-only")
                            put("label", "演示 · 仅回调")
                            put("hasStreamCallback", true)
                            put("streamMode", "callback")
                            put("usable", true) // 宿主谎报可用：原生必须按 streamMode 纠正
                            put("reason", "ignored by native")
                        },
                    )
                },
            ),
        )
        put(
            "commands",
            JsonArray(
                if (!commands) {
                    emptyList()
                } else {
                    listOf(
                        buildJsonObject {
                            put("name", "hello-node")
                            put("description", "演示命令")
                            put("hint", "任意文本")
                        },
                    )
                },
            ),
        )
    }.toString()

    private suspend fun awaitSent(transport: FakeTransport, op: String): JsonObject = withTimeout(2_000) {
        while (true) {
            transport.sent.firstOrNull { (it["op"] as? JsonPrimitive)?.content == op }?.let {
                return@withTimeout it
            }
            delay(5)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    /** 等**指定 id** 的那一帧发出去（同一 op 会发多次时用这个，避免抢到上一帧）。 */
    private suspend fun awaitSentId(transport: FakeTransport, op: String, id: String): JsonObject =
        withTimeout(2_000) {
            while (true) {
                transport.sent.firstOrNull {
                    (it["op"] as? JsonPrimitive)?.content == op && (it["id"] as? JsonPrimitive)?.content == id
                }?.let { return@withTimeout it }
                delay(5)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

    @Test
    fun helloCarriesLlmAndCommands_andCallbackOnlyIsNeverUsable() = runBlocking {
        val transport = FakeTransport()
        val core = NodeBridgeCore(transport)
        core.onText(helloFrame())

        val hello = core.hello.await()
        val providers = NodeLlmProviders.parseAll(hello.llm)
        assertEquals(listOf("node:callback-only", "node:follow-host"), providers.map { it.id })
        val follow = providers.first { it.remoteId == "follow-host" }
        val callback = providers.first { it.remoteId == "callback-only" }
        assertTrue("声明式 provider 可用（原生 HTTP 通道）", follow.usable)
        assertEquals(NodeLlmProviders.MODE_HTTP, follow.streamMode)
        assertEquals("node:", follow.id.take(5))
        assertFalse("callback-only 就算宿主谎报 usable=true 也必须判不可用", callback.usable)
        assertEquals(NodeLlmProviders.MODE_CALLBACK, callback.streamMode)
        assertTrue(callback.reason.isNotBlank())

        val commands = NodeCommands.parseAll(hello.commands)
        assertEquals(listOf("hello-node"), commands.map { it.name })
        assertEquals("任意文本", commands.first().hint)
    }

    @Test
    fun llmListRoundTrip_returnsProviders() = runBlocking {
        val transport = FakeTransport()
        val core = NodeBridgeCore(transport)
        core.onText(helloFrame())
        val request = async(Dispatchers.Default) { core.llmList(2_000) }
        val frame = awaitSent(transport, "llm.list")
        val id = (frame["id"] as? JsonPrimitive)?.content!!
        core.onText(
            buildJsonObject {
                put("op", "llm.result")
                put("id", id)
                put("ok", true)
                put(
                    "providers",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("id", "p1")
                                put("label", "P1")
                                put("streamMode", "http")
                            },
                        ),
                    ),
                )
            }.toString(),
        )
        val providers = request.await()
        assertEquals(1, providers.size)
        assertEquals("p1", (providers.first()["id"] as? JsonPrimitive)?.content)
    }

    @Test
    fun commandInvoke_successAndUnknown_areDistinguishable() = runBlocking {
        val transport = FakeTransport()
        val core = NodeBridgeCore(transport)
        core.onText(helloFrame())

        val okCall = launch(Dispatchers.Default) {
            val result = core.commandInvoke("hello-node", " hi", 5_000, "c1")
            assertTrue(result is NodeCommandCall.Ok)
            assertEquals("done:hi", (result as NodeCommandCall.Ok).text)
            assertEquals("cmd-7", result.commandId)
        }
        val invoke = awaitSentId(transport, "command.invoke", "c1")
        assertEquals("hello-node", (invoke["name"] as? JsonPrimitive)?.content)
        assertEquals(" hi", (invoke["rawInput"] as? JsonPrimitive)?.content)
        core.onText("""{"op":"command.invoke.result","id":"c1","ok":true,"kind":"success","text":"done:hi","commandId":"cmd-7","ms":3}""")
        okCall.join()

        val unknown = launch(Dispatchers.Default) {
            val result = core.commandInvoke("nope", "", 5_000, "c2")
            assertTrue(result is NodeCommandCall.Failure)
            assertTrue((result as NodeCommandCall.Failure).error.contains("未注册"))
            assertEquals("unknown", result.kind)
        }
        awaitSentId(transport, "command.invoke", "c2")
        core.onText(
            """{"op":"command.invoke.result","id":"c2","ok":false,"kind":"unknown","text":"命令未注册：/nope","error":"命令未注册：/nope","ms":1}""",
        )
        unknown.join()
    }

    @Test
    fun commandTimeout_returnsFailureInsteadOfHanging() = runBlocking {
        val transport = FakeTransport()
        val core = NodeBridgeCore(transport)
        core.onText(helloFrame())
        val started = System.currentTimeMillis()
        val result = core.commandInvoke("hello-node", "", timeoutMs = 300, callId = "c9")
        val elapsed = System.currentTimeMillis() - started
        assertTrue(result is NodeCommandCall.Failure)
        assertEquals("timeout", (result as NodeCommandCall.Failure).kind)
        assertTrue("超时后要发 command.cancel 让宿主别继续跑", transport.sent.any {
            (it["op"] as? JsonPrimitive)?.content == "command.cancel"
        })
        assertTrue("兜底等待应 ≈ timeout+2s，实测 ${elapsed}ms", elapsed >= 2_000)
    }

    @Test
    fun commandCancelledFrame_settlesAsCancelled() = runBlocking {
        val transport = FakeTransport()
        val core = NodeBridgeCore(transport)
        core.onText(helloFrame())
        val job = launch(Dispatchers.Default) {
            val result = core.commandInvoke("hello-node", "", 5_000, "c3")
            assertTrue(result is NodeCommandCall.Failure)
            assertEquals("cancelled", (result as NodeCommandCall.Failure).kind)
        }
        awaitSent(transport, "command.invoke")
        core.onText("""{"op":"command.cancelled","id":"c3","active":true}""")
        job.join()
        assertEquals("取消回执不该被当成协议错误", null, core.closedReason)
    }

    @Test
    fun closeFailsCommandWaiters() = runBlocking {
        val transport = FakeTransport()
        val core = NodeBridgeCore(transport)
        core.onText(helloFrame())
        val job = launch(Dispatchers.Default) {
            val result = runCatching { core.commandInvoke("hello-node", "", 30_000, "c4") }
            // onClosed 时 waiter 被 completeExceptionally → 这里要么异常，要么失败结果。
            assertTrue(result.isFailure || result.getOrNull() is NodeCommandCall.Failure)
        }
        awaitSent(transport, "command.invoke")
        core.onClosed("宿主进程已退出")
        job.join()
        assertNotNull(core.closedReason)
    }
}
