package xin.dshaa.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginSandboxTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    private fun perms(vararg raw: String): List<PluginPermission> =
        PluginPermissions.parseAll(raw.toList())

    private fun reply(sandbox: PluginSandbox, method: String, payload: String): JsonObject =
        Json.parseToJsonElement(sandbox.call(method, payload).payloadJson) as JsonObject

    private fun JsonObject.ok(): Boolean = this["ok"]?.jsonPrimitive?.content == "true"
    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun surfaceCommandRequiresUiGrantAndForwardsToHost() {
        val workspace = tempDir("dsh-sandbox")
        var seen: String? = null
        val bridge = object : PluginHostBridge {
            override fun call(method: String, payloadJson: String): String {
                seen = "$method:$payloadJson"
                return """{"ok":true}"""
            }
        }
        val denied = PluginSandbox("demo", workspace, perms("ui"), emptyList(), bridge)
        val deniedReply = reply(denied, "surface.command", """{"command":"send"}""")
        assertFalse(deniedReply.ok())
        assertEquals("PERMISSION_DENIED", deniedReply.text("code"))

        val allowed = PluginSandbox("demo", workspace, perms("ui"), listOf("ui"), bridge)
        val result = reply(allowed, "surface.command", """{"command":"send","args":{"text":"hi"}}""")
        assertTrue(result.ok())
        assertTrue(seen.orEmpty().startsWith("surface.command:"))
        assertTrue(seen.orEmpty().contains("\"send\""))
    }

    @Test
    fun fsWithoutPermissionIsDeniedWithClearCode() {
        val workspace = tempDir("dsh-sandbox")
        File(workspace, "a.txt").writeText("hi")
        val sandbox = PluginSandbox("demo", workspace, emptyList(), emptyList())
        val result = reply(sandbox, "fs.read", """{"path":"a.txt"}""")
        assertFalse(result.ok())
        assertEquals("PERMISSION_DENIED", result.text("code"))
        assertTrue(result.text("error").orEmpty().contains("fs:workspace"))
    }

    @Test
    fun fsWorkspaceReadsWritesAndListsInsideRoot() {
        val workspace = tempDir("dsh-sandbox")
        val sandbox = PluginSandbox("demo", workspace, perms("fs:workspace"), listOf("fs:workspace"))

        assertTrue(reply(sandbox, "fs.mkdir", """{"path":"notes","recursive":true}""").ok())
        assertTrue(reply(sandbox, "fs.write", """{"path":"notes/a.txt","text":"hello"}""").ok())
        assertEquals("hello", reply(sandbox, "fs.read", """{"path":"notes/a.txt"}""").text("text"))

        val list = reply(sandbox, "fs.list", """{"path":"notes"}""")
        val names = (list["names"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        assertTrue(names.contains("a.txt"))
    }

    @Test
    fun fsWorkspaceRejectsEscape() {
        val workspace = tempDir("dsh-sandbox")
        val outside = tempDir("dsh-outside")
        File(outside, "secret.txt").writeText("nope")
        val sandbox = PluginSandbox("demo", workspace, perms("fs:workspace"), listOf("fs:workspace"))

        val parent = reply(sandbox, "fs.read", """{"path":"../secret.txt"}""")
        assertFalse(parent.ok())
        val absolute = reply(sandbox, "fs.read", """{"path":"${outside.absolutePath.replace("\\", "\\\\")}/secret.txt"}""")
        assertFalse(absolute.ok())
        assertEquals("PATH_DENIED", absolute.text("code"))
    }

    @Test
    fun fsAnyAllowsPathOutsideWorkspaceOnlyWhenGranted() {
        val workspace = tempDir("dsh-sandbox")
        val outsideDir = tempDir("dsh-outside")
        val outside = File(outsideDir, "x.txt").apply { writeText("outside") }
        val escapedPath = outside.absolutePath.replace("\\", "\\\\")

        val denied = PluginSandbox("demo", workspace, perms("fs:workspace"), listOf("fs:workspace"))
        assertFalse(reply(denied, "fs.read", """{"path":"$escapedPath"}""").ok())

        val allowed = PluginSandbox("demo", workspace, perms("fs:any"), listOf("fs:any"))
        val result = reply(allowed, "fs.read", """{"path":"$escapedPath"}""")
        assertTrue(result.ok())
        assertEquals("outside", result.text("text"))
    }

    @Test
    fun netRejectsCleartextLoopbackAndUnlistedHosts() {
        val sandbox = PluginSandbox("demo", tempDir("dsh-sandbox"), perms("net:any"), listOf("net:any"))

        val http = reply(sandbox, "net.fetch", """{"url":"http://api.example.com/x"}""")
        assertFalse(http.ok())
        assertEquals("PERMISSION_DENIED", http.text("code"))
        assertTrue(http.text("error").orEmpty().contains("https"))

        val loopback = reply(sandbox, "net.fetch", """{"url":"https://127.0.0.1/x"}""")
        assertFalse(loopback.ok())
        assertEquals("PERMISSION_DENIED", loopback.text("code"))

        val onlyOne = PluginSandbox("demo", tempDir("dsh-sandbox"), perms("net:api.example.com"), listOf("net:api.example.com"))
        val other = reply(onlyOne, "net.fetch", """{"url":"https://evil.example.org/x"}""")
        assertFalse(other.ok())
        assertEquals("PERMISSION_DENIED", other.text("code"))
    }

    @Test
    fun shellRequiresExplicitGrant() {
        val sandbox = PluginSandbox("demo", tempDir("dsh-sandbox"), perms("shell"), emptyList())
        val result = reply(sandbox, "shell.exec", """{"command":"echo hi"}""")
        assertFalse(result.ok())
        assertEquals("PERMISSION_DENIED", result.text("code"))
        assertTrue(result.text("error").orEmpty().contains("shell"))
    }

    @Test
    fun logIsAlwaysAllowedAndUnknownMethodsAreReported() {
        val sandbox = PluginSandbox("demo", tempDir("dsh-sandbox"), emptyList(), emptyList())
        assertTrue(reply(sandbox, "log", """{"message":"hi"}""").ok())
        val unknown = reply(sandbox, "device.reboot", """{}""")
        assertFalse(unknown.ok())
        assertEquals("UNKNOWN_METHOD", unknown.text("code"))
    }
}
