package xin.dshaa.core.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `ToolRegistry` 的异常边界（M17 修）：
 * 取消（CancellationException，`IllegalStateException` 的子类）不能被当成工具错误吞掉，
 * 否则 stop / 子代理 interrupt 只会写一条 `CancellationException: …` 的 tool_result，然后继续跑下一步。
 */
class ToolRegistryTest {

    private class StubTool(
        override val name: String,
        private val block: suspend () -> ToolResult,
    ) : DshTool {
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = block()
    }

    private val ctx = ToolContext(workspace = File("."))

    @Test
    fun cancellationIsRethrownInsteadOfBecomingToolError() {
        val registry = ToolRegistry(listOf(StubTool("stub") { throw CancellationException("stopped") }))
        assertFailsWith<CancellationException> {
            runBlocking { registry.execute("stub", buildJsonObject { }, ctx) }
        }
    }

    @Test
    fun ordinaryFailuresStayToolErrors() = runBlocking {
        val registry = ToolRegistry(
            listOf(
                StubTool("bad") { throw IllegalArgumentException("bad args") },
                StubTool("boom") { throw IllegalStateException("kaboom") },
            ),
        )
        val bad = registry.execute("bad", buildJsonObject { }, ctx)
        assertTrue(bad.isError)
        assertEquals("bad args", bad.text)
        val boom = registry.execute("boom", buildJsonObject { }, ctx)
        assertTrue(boom.isError)
        assertEquals("IllegalStateException: kaboom", boom.text)
    }

    @Test
    fun unknownToolIsAnError() = runBlocking {
        val result = ToolRegistry(emptyList()).execute("nope", buildJsonObject { }, ctx)
        assertTrue(result.isError)
        assertEquals("unknown tool: nope", result.text)
    }

    @Test
    fun registersAndUnregistersRuntimeTools() = runBlocking {
        val registry = ToolRegistry(listOf(StubTool("builtin") { ToolResult.ok("b") }))
        assertTrue(registry.register(StubTool("plugin_echo") { ToolResult.ok("hi") }))
        assertEquals(listOf("builtin", "plugin_echo"), registry.names)
        assertEquals("hi", registry.execute("plugin_echo", buildJsonObject { }, ctx).text)

        // 同名（含内置）拒绝：不能悄悄覆盖内置工具
        assertTrue(!registry.register(StubTool("builtin") { ToolResult.ok("fake") }))
        assertEquals("b", registry.execute("builtin", buildJsonObject { }, ctx).text)

        assertTrue(registry.unregister("plugin_echo"))
        assertTrue(!registry.unregister("plugin_echo"))
        assertTrue("plugin_echo" !in registry.names)
    }
}
