package xin.dshaa.core.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xin.dshaa.core.primitives.PrimitiveCatalog
import xin.dshaa.core.primitives.PrimitiveHost
import xin.dshaa.core.primitives.PrimitiveIds
import xin.dshaa.core.primitives.PrimitiveResult
import xin.dshaa.core.tools.primitives.PrimitiveTool
import xin.dshaa.core.tools.primitives.PrimitiveTools
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P1 原语工具面（`docs/185` §九）：工具只是目录的投影——参数校验在这里，执行一律落到
 * [PrimitiveHost]。真实现（App 层）由 `:app:testDebugUnitTest`/真机验收覆盖。
 */
class PrimitiveToolsTest {

    private val ctxBase = ToolContext(workspace = File("."))

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is String -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Boolean -> put(key, value)
                is JsonObject -> put(key, value)
                else -> error("unsupported fixture value: $value")
            }
        }
    }

    private fun tool(id: String): DshTool = PrimitiveTool(PrimitiveCatalog.byId.getValue(id))

    private fun run(tool: DshTool, host: PrimitiveHost?, args: JsonObject): ToolResult =
        runBlocking { tool.execute(args, ctxBase.copy(primitives = host)) }

    private class FakeHost : PrimitiveHost {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var result: PrimitiveResult = PrimitiveResult.ok("ok")
        var fail: String? = null

        override fun currentTopicId(): String? = "topic-current"

        override suspend fun invoke(id: String, args: JsonObject): PrimitiveResult {
            fail?.let { throw IllegalArgumentException(it) }
            calls += id to args
            return result
        }
    }

    @Test
    fun toolFaceCoversAvailablePrimitivesOnly() {
        assertEquals(PrimitiveCatalog.available.size, PrimitiveTools.all.size)
        assertEquals(PrimitiveCatalog.toolNames, PrimitiveTools.all.map { it.name }.toSet())
        // P3-e：ui.openContainer 从「登记未实现」变成就绪 —— 工具面必须真的有它。
        assertTrue(PrimitiveTools.all.any { it.name == PrimitiveCatalog.toolName(PrimitiveIds.UI_OPEN_CONTAINER) })
        assertTrue(PrimitiveTools.all.map { it.name }.contains("session_summarize"))
    }

    @Test
    fun toolsWithoutHostAreUnavailable() {
        PrimitiveTools.all.forEach { tool ->
            val result = run(tool, null, args())
            assertTrue(result.isError, "${tool.name} must fail without a host")
            assertTrue("primitives unavailable" in result.text, tool.name)
        }
    }

    @Test
    fun validationRunsBeforeHostCall() {
        val host = FakeHost()
        val result = run(tool(PrimitiveIds.EFFORT_SET), host, args("effort" to "medium"))
        assertTrue(result.isError)
        assertTrue("invalid effort.set" in result.text)
        assertTrue(host.calls.isEmpty())

        val missing = run(tool(PrimitiveIds.MODEL_SET), host, args())
        assertTrue(missing.isError)
        assertTrue("missing required parameter: model" in missing.text)
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun toolPassesIdAndArgumentsUnchanged() {
        val host = FakeHost()
        val payload = args("from_seq" to 3L, "to_seq" to 9L)
        run(tool(PrimitiveIds.SESSION_SUMMARIZE), host, payload)
        assertEquals(1, host.calls.size)
        assertEquals(PrimitiveIds.SESSION_SUMMARIZE, host.calls[0].first)
        assertEquals(payload, host.calls[0].second)
    }

    @Test
    fun resultSummaryAndScalarMetaPassThrough() {
        val host = FakeHost().apply {
            result = PrimitiveResult.ok(
                "## Critical Context\n- decided A",
                buildJsonObject {
                    put("topicId", "topic-1")
                    put("messages", 4)
                },
            )
        }
        val result = run(tool(PrimitiveIds.SESSION_SUMMARIZE), host, args())
        assertFalse(result.isError)
        assertEquals("## Critical Context\n- decided A", result.text)
        assertEquals("topic-1", result.meta["topicId"])
        assertEquals("4", result.meta["messages"])
    }

    @Test
    fun hostErrorBecomesToolError() {
        val host = FakeHost().apply { result = PrimitiveResult.error("not implemented", "NOT_IMPLEMENTED") }
        val result = run(tool(PrimitiveIds.TURN_STATE), host, args())
        assertTrue(result.isError)
        assertEquals("not implemented", result.text)
    }

    @Test
    fun hostExceptionBecomesToolErrorNotCrash() {
        val host = FakeHost().apply { fail = "摘要请求失败：no api key" }
        val result = run(tool(PrimitiveIds.SESSION_SUMMARIZE), host, args())
        assertTrue(result.isError)
        assertTrue("no api key" in result.text)
    }

    @Test
    fun turnSendRequiresNonBlankText() {
        val host = FakeHost()
        val result = run(tool(PrimitiveIds.TURN_SEND), host, args("text" to " "))
        assertTrue(result.isError)
        assertTrue(host.calls.isEmpty())
        val ok = run(tool(PrimitiveIds.TURN_SEND), host, args("text" to "继续"))
        assertFalse(ok.isError)
        assertEquals("继续", host.calls.single().second.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun toolCallNeedsApprovalAndArgumentsObject() {
        val host = FakeHost()
        val bad = run(tool(PrimitiveIds.TOOL_CALL), host, args("name" to "todo_write", "arguments" to "[]"))
        assertTrue(bad.isError)
        assertTrue(host.calls.isEmpty())
        val ok = run(
            tool(PrimitiveIds.TOOL_CALL),
            host,
            args("name" to "todo_write", "arguments" to buildJsonObject { put("x", 1) }),
        )
        assertFalse(ok.isError)
        assertEquals(PrimitiveIds.TOOL_CALL, host.calls.single().first)
    }
}
