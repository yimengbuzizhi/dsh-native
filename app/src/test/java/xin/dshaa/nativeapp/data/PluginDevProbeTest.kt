package xin.dshaa.nativeapp.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xin.dshaa.core.js.JsHost
import xin.dshaa.core.js.JsOutcome
import xin.dshaa.core.js.JsStatus
import xin.dshaa.core.plugins.PluginRecord
import xin.dshaa.core.tools.ToolResult
import java.io.File

class PluginDevProbeTest {
    @get:Rule val temp = TemporaryFolder()
    private val empty = JsonObject(emptyMap())
    private val tree = """{"ok":true,"tree":{"type":"column","children":[{"type":"text","text":"hello"}]}}"""

    private fun record(granted: Boolean = true): PluginRecord {
        val root = temp.newFolder()
        File(root, "index.js").writeText("export function apply(ctx) { ctx.ui.registerShell({id:'actual',mount:function(){return {type:'text',text:'hello'};}}); }")
        return PluginRecord("fixture", "Fixture", "1", "", root.path, "index.js", "DSH", false,
            emptyList(), imports = emptyList(), error = null, installedAt = 0,
            permissions = listOf("ui"), grantedPermissions = if (granted) listOf("ui") else emptyList())
    }

    private class FakeRuntime : PluginDevProbe.ProbeRuntime {
        var started = JsOutcome(JsStatus.COMPLETED, """{"ok":true,"shells":[{"id":"actual","label":"Actual","backend":"surface"}],"errors":[]}""")
        var next = JsOutcome(JsStatus.COMPLETED, """{"ok":true,"tree":{"type":"text","text":"hello"}}""")
        var closed = 0
        var script = ""
        var startTimeout = 0L
        val dispatches = mutableListOf<Triple<String, JsonObject, Long>>()
        var onDispatch: (String) -> Unit = {}
        var onClose: () -> Unit = {}
        override fun start(script: String, filename: String, timeoutMs: Long): JsOutcome {
            this.script = script; startTimeout = timeoutMs
            return started
        }
        override fun dispatch(method: String, payload: String, timeoutMs: Long): JsOutcome {
            dispatches += Triple(method, Json.parseToJsonElement(payload).jsonObject, timeoutMs)
            onDispatch(method)
            return next
        }
        override fun complete(callId: Int, ok: Boolean, payload: String) {}
        override fun close() { closed++; onClose() }
    }

    private fun ToolResult.json() = Json.parseToJsonElement(text).jsonObject

    @Test fun validatesRealSurfaceShapeAndRejectsBadTreeWithoutRendering() {
        val good = PluginDevProbe.validateReply("shell.mount", "surface", JsOutcome(JsStatus.COMPLETED, tree))
        assertEquals(true, good["valid"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(2, good["nodes"]!!.jsonPrimitive.intOrNull)
        assertEquals(false, good["rendered"]!!.jsonPrimitive.booleanOrNull)
        val bad = PluginDevProbe.validateReply("shell.mount", "surface", JsOutcome(JsStatus.COMPLETED, """{"ok":true,"tree":{"type":"unknown"}}"""))
        assertEquals(false, bad["valid"]!!.jsonPrimitive.booleanOrNull)
        assertTrue(bad["error"]!!.jsonPrimitive.content.contains("未知原语"))
    }

    @Test fun validatesHtmlDataOnlyAndBoundsReplyPreview() {
        val html = "<main>" + "x".repeat(8_000) + "</main>"
        val good = PluginDevProbe.validateReply("shell.state", "webview", JsOutcome(JsStatus.COMPLETED, buildJsonObject { put("ok", true); put("html", html) }.toString()))
        assertEquals(true, good["valid"]!!.jsonPrimitive.booleanOrNull)
        assertTrue(good["preview"]!!.jsonPrimitive.content.length <= 1_000)
        for (reply in listOf("""{"ok":true,"html":" "}""", """{"ok":true,"html":12}""", "x".repeat(PluginDevProbe.MAX_REPLY_CHARS + 1))) {
            assertEquals(false, PluginDevProbe.validateReply("shell.mount", "webview", JsOutcome(JsStatus.COMPLETED, reply))["valid"]!!.jsonPrimitive.booleanOrNull)
        }
    }

    @Test fun runsRealResidentScriptAndDispatchSequenceWithSyntheticStateAndCommandSimulation() {
        val runtime = FakeRuntime().apply { started = started.copy(valueJson = JsonPrimitive(started.valueJson!!).toString()) }
        lateinit var host: JsHost
        var commandReply: JsonObject? = null
        runtime.onDispatch = { method ->
            if (method == "shell.action") {
                commandReply = Json.parseToJsonElement(host.callSync("surface.command", """{"command":"send","args":{"text":"test"}}""")!!).jsonObject
                val denied = Json.parseToJsonElement(host.callSync("net.fetch", "{}")!!).jsonObject
                assertEquals("PROBE_ISOLATED", denied["code"]!!.jsonPrimitive.content)
            }
        }
        val result = PluginDevProbe.testShellWithRuntime(record(), temp.root, null, "send", buildJsonObject { put("text", "value") }) { host = it; runtime }
        assertFalse(result.text, result.isError)
        assertTrue(runtime.script.contains("__dshDispatch"))
        assertTrue(runtime.script.contains("__dshApply"))
        assertEquals(10_000L, runtime.startTimeout)
        assertEquals(listOf("shell.mount", "shell.state", "shell.action"), runtime.dispatches.map { it.first })
        assertEquals(listOf(10_000L, 3_000L, 3_000L), runtime.dispatches.map { it.third })
        assertEquals("actual", runtime.dispatches[0].second["id"]!!.jsonPrimitive.content)
        assertTrue(runtime.dispatches[0].second["state"]!!.jsonObject["chat"]!!.jsonObject["messages"]!!.jsonArray.isEmpty())
        assertEquals("value", runtime.dispatches.last().second["value"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(true, commandReply!!["simulated"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(false, commandReply!!["executed"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(0, result.json()["commandsExecuted"]!!.jsonPrimitive.intOrNull)
        assertEquals(1, result.json()["commandCount"]!!.jsonPrimitive.intOrNull)
        assertEquals(1, runtime.closed)
    }

    @Test fun applyAndDispatchTimeoutsAlwaysCloseDisposableRuntime() {
        val startupTimeout = FakeRuntime().apply { started = JsOutcome(JsStatus.INTERRUPTED, error = "start timeout") }
        val startup = PluginDevProbe.testShellWithRuntime(record(), temp.root, null, null, empty) { startupTimeout }
        assertTrue(startup.isError)
        assertTrue(startup.json()["error"]!!.jsonPrimitive.content.contains("timeout"))
        assertEquals(1, startupTimeout.closed)
        assertTrue(startupTimeout.dispatches.isEmpty())
        val dispatchTimeout = FakeRuntime().apply { next = JsOutcome(JsStatus.INTERRUPTED, error = "dispatch timeout") }
        val dispatch = PluginDevProbe.testShellWithRuntime(record(), temp.root, null, "action", empty) { dispatchTimeout }
        assertTrue(dispatch.isError)
        assertEquals(listOf("shell.mount"), dispatchTimeout.dispatches.map { it.first })
        assertEquals(1, dispatchTimeout.closed)
    }

    @Test fun commandsRequireGrantAndCleanupUsesSameIsolatedHost() {
        val runtime = FakeRuntime()
        lateinit var host: JsHost
        runtime.onClose = {
            val reply = Json.parseToJsonElement(host.callSync("surface.command", """{"command":"closeShell"}""")!!).jsonObject
            assertEquals(false, reply["ok"]!!.jsonPrimitive.booleanOrNull)
            assertEquals("PERMISSION_DENIED", reply["code"]!!.jsonPrimitive.content)
            host.callSync("shell.exec", """{"command":"unsafe"}""")
        }
        val result = PluginDevProbe.testShellWithRuntime(record(granted = false), temp.root, null, null, empty) { host = it; runtime }
        assertFalse(result.text, result.isError)
        val command = result.json()["commands"]!!.jsonArray.single().jsonObject
        assertEquals("cleanup", command["phase"]!!.jsonPrimitive.content)
        assertEquals(false, command["executed"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(1, result.json()["deniedHostCallCount"]!!.jsonPrimitive.intOrNull)
    }

    @Test fun thrownStartupAndCleanupFailureReturnBoundedErrors() {
        val runtime = FakeRuntime().apply { onClose = { error("cleanup failed") } }
        val result = PluginDevProbe.testShellWithRuntime(record(), temp.root, null, null, empty) { runtime }
        assertTrue(result.isError)
        assertEquals(1, runtime.closed)
        assertTrue(result.json()["error"]!!.jsonPrimitive.content.contains("cleanup failed"))
        val failedFactory = PluginDevProbe.testShellWithRuntime(record(), temp.root, null, null, empty) { error("create failed") }
        assertTrue(failedFactory.isError)
        assertTrue(failedFactory.json()["error"]!!.jsonPrimitive.content.contains("create failed"))
    }

    @Test fun missingShellClosesRuntimeAndCommandReportRemainsBounded() {
        val runtime = FakeRuntime()
        lateinit var host: JsHost
        runtime.onClose = { repeat(100) { host.callSync("surface.command", """{"command":"closeShell","args":{"text":"${"x".repeat(2_000)}"}}""") } }
        val result = PluginDevProbe.testShellWithRuntime(record(), temp.root, "missing", null, empty) { host = it; runtime }
        assertTrue(result.isError)
        assertEquals(1, runtime.closed)
        assertTrue(runtime.dispatches.isEmpty())
        assertEquals(100, result.json()["commandCount"]!!.jsonPrimitive.intOrNull)
        assertEquals(32, result.json()["commands"]!!.jsonArray.size)
        assertEquals(true, result.json()["commandsTruncated"]!!.jsonPrimitive.booleanOrNull)
        assertTrue(result.text.length < 100_000)
    }
}
