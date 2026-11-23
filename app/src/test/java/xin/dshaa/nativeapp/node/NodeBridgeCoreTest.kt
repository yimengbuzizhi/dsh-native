package xin.dshaa.nativeapp.node

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * docs/187 §九 第 9 项：桥的四种异常在纯 JVM 上验（假 transport，不装 APK）。
 *
 * 每个用例都同时断言「工具表摘除/恢复」：生产里这张表是 [NodeToolSurface]，
 * 宿主 Ready 时 replace、失联时 clear（[NodePluginHost] 调的就是这两个方法）。
 * 这里用同一份生产类，避免测试自己另写一套状态。
 */
class NodeBridgeCoreTest {

    // ---- 假 transport：记录发出去的帧，close 时记原因 ----

    private class FakeTransport : NodeTransport {
        val sent = CopyOnWriteArrayList<JsonObject>()
        var closedReason: String? = null

        override fun send(text: String): Boolean {
            sent += Json.parseToJsonElement(text).jsonObject
            return true
        }

        override fun close(reason: String) {
            closedReason = reason
        }
    }

    /** 生产宿主的最小镜像：hello → 换表；断开 → 摘表。 */
    private class Harness(
        val core: NodeBridgeCore,
        val transport: FakeTransport,
        val surface: NodeToolSurface,
    )

    private fun harness(): Harness {
        val transport = FakeTransport()
        return Harness(NodeBridgeCore(transport), transport, NodeToolSurface())
    }

    private fun helloFrame(vararg toolNames: String): String = buildJsonObject {
        put("op", "host.hello")
        put("pid", 4242)
        put("version", "v22.0.0-test")
        put("bootMs", 12)
        put(
            "tools",
            kotlinx.serialization.json.JsonArray(
                toolNames.map { name ->
                    buildJsonObject {
                        put("name", name)
                        put("description", "test tool $name")
                        put("parameters", buildJsonObject { put("type", "object") })
                    }
                },
            ),
        )
        put("plugins", kotlinx.serialization.json.JsonArray(emptyList()))
    }.toString()

    /** 与 [NodePluginHost] 同样的映射：hello 的 tools → NodeToolSpec → surface。 */
    private suspend fun applyHello(harness: Harness) {
        val info = harness.core.hello.await()
        val specs = info.tools.map { tool ->
            NodeToolSpec(
                name = (tool["name"] as? JsonPrimitive)?.content.orEmpty(),
                description = (tool["description"] as? JsonPrimitive)?.content.orEmpty(),
                parameters = tool["parameters"] as? JsonObject
                    ?: buildJsonObject { put("type", "object") },
            )
        }
        harness.surface.replace(specs)
    }

    private suspend fun harnessWithHello(vararg names: String): Harness {
        val h = harness()
        h.core.onText(helloFrame(*names))
        applyHello(h)
        return h
    }

    private suspend fun awaitSent(transport: FakeTransport, op: String): JsonObject =
        withTimeout(2_000) {
            while (true) {
                transport.sent.firstOrNull { (it["op"] as? JsonPrimitive)?.content == op }?.let {
                    return@withTimeout it
                }
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

    // ---- ① hello 超时 ----

    @Test
    fun helloTimeout_clearsStaleToolTable_andNextGoodHelloRestoresIt() = runBlocking {
        val h = harness()
        // 上一次会话残留的工具表：新宿主迟迟不 hello 时必须清掉，不能继续骗模型。
        h.surface.replace(listOf(NodeToolSpec("stale_tool", "old", buildJsonObject { put("type", "object") })))
        assertEquals(1, h.surface.size())

        val hello = withTimeoutOrNull(150) { h.core.hello.await() }
        assertNull("没有 host.hello 就是超时", hello)
        // 生产：NodePluginHost 握手超时 → bridge.close() → 摘表。
        h.core.onClosed("握手超时")
        h.surface.clear()
        assertEquals("超时后工具表必须为空", 0, h.surface.size())
        assertNotNull(h.core.closedReason)

        // 恢复：下一次 spawn 的正常 hello 让表回来。
        val next = harnessWithHello("hello_probe", "todo_write")
        assertEquals(2, next.surface.size())
    }

    // ---- ② 进程崩溃 / EOF ----

    @Test
    fun processEof_failsPendingCall_andClearsSurface_thenRecovers() = runBlocking {
        val h = harnessWithHello("hello_probe")
        assertEquals(1, h.surface.size())

        val pending = launch(Dispatchers.Default) {
            val result = h.core.invoke("hello_probe", buildJsonObject { put("note", "x") }, 30_000)
            assertTrue("EOF 时应以失败返回而不是挂死", result is NodeCall.Failure)
        }
        // 等 tool.invoke 真正发出去，再模拟进程退出。
        awaitSent(h.transport, "tool.invoke")
        h.core.onClosed("node 宿主进程已退出（exit=137）")
        pending.join()
        assertEquals("崩溃后 pending 必须清空", 0, h.core.pendingCount())
        h.surface.clear()
        assertEquals(0, h.surface.size())

        val next = harnessWithHello("hello_probe")
        assertEquals("重启（新 hello）后工具表恢复", 1, next.surface.size())
    }

    // ---- ③ 非法帧 / 协议错误 ----

    @Test
    fun protocolError_closesTransport_clearsSurface_andRecovers() = runBlocking {
        val h = harnessWithHello("hello_probe")

        h.core.onText("this is not json")
        assertEquals("非法帧要主动断开", "非法帧（不是 JSON object）：this is not json", h.transport.closedReason)
        assertNotNull(h.core.closedReason)
        h.surface.clear()
        assertEquals(0, h.surface.size())

        // 缺 op 的 JSON 同样算协议错误。
        val h2 = harnessWithHello("hello_probe")
        h2.core.onText("""{"id":"x"}""")
        assertNotNull(h2.core.closedReason)
        assertTrue(h2.transport.closedReason!!.contains("缺 op"))

        // 恢复：新的合法 hello。
        val next = harnessWithHello("hello_probe", "todo_write")
        assertEquals(2, next.surface.size())
    }

    // ---- ④ 协程取消 ----

    @Test
    fun coroutineCancel_sendsToolCancel_clearsPending_keepsToolTable() = runBlocking {
        val h = harnessWithHello("hello_probe")
        var cancelled = false
        val job = launch(Dispatchers.Default) {
            try {
                h.core.invoke("hello_probe", buildJsonObject { put("note", "slow") }, 30_000)
            } catch (_: CancellationException) {
                cancelled = true
            }
        }
        val invoke = awaitSent(h.transport, "tool.invoke")
        val id = (invoke["id"] as? JsonPrimitive)?.content
        assertEquals(1, h.core.pendingCount())

        job.cancel()
        job.join()

        assertTrue("取消要往上抛（引擎要能结束这轮）", cancelled)
        val cancel = awaitSent(h.transport, "tool.cancel")
        assertEquals("tool.cancel 必须带同一个 id", id, (cancel["id"] as? JsonPrimitive)?.content)
        assertEquals("取消后 pending 必须移除", 0, h.core.pendingCount())
        // 取消只结束这一次调用；宿主还活着，工具表不清。
        assertEquals(1, h.surface.size())

        // 宿主迟到的 tool.cancelled 回执不能把别的调用弄坏。
        h.core.onText("""{"op":"tool.cancelled","id":"$id","ms":10}""")
        assertEquals(0, h.core.pendingCount())
        assertTrue(h.core.hello.isCompleted)
    }
}
