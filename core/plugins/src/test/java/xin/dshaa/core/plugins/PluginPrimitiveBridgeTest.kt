package xin.dshaa.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P1（`docs/185` §四）：插件桥面 `ctx.primitive.invoke/list` 只做参数转发，
 * 权限/可用性由 `core:primitives` 的目录决定（测试里用假桥锁住转发形状）。
 */
class PluginPrimitiveBridgeTest {

    private fun tempDir(): File = Files.createTempDirectory("dsh-primitive-bridge").toFile().also { it.deleteOnExit() }

    private fun reply(sandbox: PluginSandbox, method: String, payload: String): JsonObject =
        Json.parseToJsonElement(sandbox.call(method, payload).payloadJson) as JsonObject

    private fun JsonObject.ok(): Boolean = this["ok"]?.jsonPrimitive?.content == "true"

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun withoutBridgeInvokeReportsUnavailable() {
        val sandbox = PluginSandbox("probe", tempDir())
        val result = reply(sandbox, "primitive.invoke", """{"id":"turn.state","arguments":{}}""")
        assertFalse(result.ok())
        assertEquals("UNAVAILABLE", result.text("code"))
        assertTrue(result.text("error").orEmpty().contains("原语"))
    }

    @Test
    fun invokeForwardsIdAndArgumentsAndKeepsReply() {
        var seen: Pair<String, String>? = null
        val bridge = object : PluginPrimitiveBridge {
            override fun invoke(id: String, argsJson: String): String {
                seen = id to argsJson
                return """{"ok":true,"summary":"topic t-1 status=idle","data":{"status":"idle"},"code":null}"""
            }

            override fun list(): String = """{"ok":true,"primitives":[]}"""
        }
        val sandbox = PluginSandbox("probe", tempDir(), primitiveBridge = bridge)
        val result = reply(sandbox, "primitive.invoke", """{"id":"turn.state","arguments":{"n":1}}""")
        assertTrue(result.ok())
        assertEquals("turn.state", seen?.first)
        assertEquals("""{"n":1}""", seen?.second)
        assertEquals("topic t-1 status=idle", result.text("summary"))
        assertEquals("idle", (result["data"] as JsonObject)["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun invokeWithoutArgumentsSendsEmptyObject() {
        var seen: String? = null
        val bridge = object : PluginPrimitiveBridge {
            override fun invoke(id: String, argsJson: String): String {
                seen = argsJson
                return """{"ok":true,"summary":"ok","data":{}}"""
            }

            override fun list(): String = """{"ok":true,"primitives":[]}"""
        }
        val sandbox = PluginSandbox("probe", tempDir(), primitiveBridge = bridge)
        assertTrue(reply(sandbox, "primitive.invoke", """{"id":"model.list"}""").ok())
        assertEquals("{}", seen)
    }

    @Test
    fun invalidInvokePayloadIsRejectedBeforeBridge() {
        var calls = 0
        val bridge = object : PluginPrimitiveBridge {
            override fun invoke(id: String, argsJson: String): String {
                calls += 1
                return """{"ok":true,"summary":"ok","data":{}}"""
            }

            override fun list(): String = """{"ok":true,"primitives":[]}"""
        }
        val sandbox = PluginSandbox("probe", tempDir(), primitiveBridge = bridge)
        assertEquals("INVALID_ARGUMENT", reply(sandbox, "primitive.invoke", """{"arguments":{}}""").text("code"))
        assertEquals(
            "INVALID_ARGUMENT",
            reply(sandbox, "primitive.invoke", """{"id":"turn.state","arguments":"[]"}""").text("code"),
        )
        assertEquals(0, calls)
    }

    @Test
    fun bridgeFailureBecomesHostError() {
        val bridge = object : PluginPrimitiveBridge {
            override fun invoke(id: String, argsJson: String): String = error("boom")

            override fun list(): String = error("boom")
        }
        val sandbox = PluginSandbox("probe", tempDir(), primitiveBridge = bridge)
        assertEquals("PLUGIN_HOST_ERROR", reply(sandbox, "primitive.invoke", """{"id":"turn.state"}""").text("code"))
        assertEquals("PLUGIN_HOST_ERROR", reply(sandbox, "primitive.list", "{}").text("code"))
    }

    @Test
    fun listReturnsBridgeCatalog() {
        val bridge = object : PluginPrimitiveBridge {
            override fun invoke(id: String, argsJson: String): String = """{"ok":false,"error":"unused"}"""

            override fun list(): String = """{"ok":true,"primitives":[{"id":"turn.state","bridge":true}]}"""
        }
        val sandbox = PluginSandbox("probe", tempDir(), primitiveBridge = bridge)
        val result = reply(sandbox, "primitive.list", "{}")
        assertTrue(result.ok())
        val primitives = result["primitives"] as kotlinx.serialization.json.JsonArray
        assertEquals("turn.state", (primitives.first() as JsonObject)["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun preludeExposesPrimitiveBridge() {
        val module = PluginModule.transform(
            """
            export const apply = (ctx) => {
              ctx.sessionProjections.register({ key: 'probe' })
            }
            """.trimIndent(),
        )
        val resident = PluginModule.residentScript(module)
        assertTrue(resident.contains("primitive: __permissive"))
        assertTrue(resident.contains("__dshHostJson('primitive.invoke'"))
        assertTrue(resident.contains("__dshHostJson('primitive.list'"))
    }
}
