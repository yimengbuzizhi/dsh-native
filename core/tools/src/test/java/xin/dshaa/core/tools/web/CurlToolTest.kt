package xin.dshaa.core.tools.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolRegistry
import xin.dshaa.core.tools.ToolResult
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.Executors

/**
 * `curl` 工具（docs/187 §6.5）的 JVM 验收。
 *
 * 用 JDK 自带 `com.sun.net.httpserver.HttpServer` 起本地服务，不依赖外网：
 * GET/头、POST body（`data` 与旧名 `body`）、302 跟随、`max_redirects`、非法 scheme、超时、
 * body 截断、`max_bytes=0` 不截断、非文本 base64、重定向到其它 scheme、重定向环。
 *
 * 还要锁死一件事：旧名字 `http_request` 只能当别名执行，**不出现在** `ToolRegistry.names` 里
 * （模型看不到它，请求里也不会带上）。
 */
class CurlToolTest {

    private lateinit var server: HttpServer
    private var base = ""

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/get") { ex ->
            ex.responseHeaders.add("X-Test", "yes")
            ex.responseHeaders.add("Set-Cookie", "secret=1")
            respond(ex, 200, "hello", "text/plain; charset=utf-8")
        }
        server.createContext("/post") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            respond(ex, 200, "${ex.requestMethod}:$body", "text/plain; charset=utf-8")
        }
        server.createContext("/redirect") { ex ->
            ex.responseHeaders.add("Location", "/get")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/to-ftp") { ex ->
            ex.responseHeaders.add("Location", "ftp://example.com/x")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/redirect-loop") { ex ->
            ex.responseHeaders.add("Location", "/redirect-loop")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/slow") { ex ->
            Thread.sleep(1_500)
            respond(ex, 200, "late", "text/plain")
        }
        server.createContext("/large") { ex ->
            respond(ex, 200, "a".repeat(1_000), "text/plain")
        }
        server.createContext("/binary") { ex ->
            val bytes = ByteArray(64) { (it * 3).toByte() }
            ex.responseHeaders.add("Content-Type", "application/octet-stream")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun respond(ex: HttpExchange, status: Int, body: String, contentType: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun run(argsJson: String): ToolResult = runBlocking {
        CurlTool().execute(Json.parseToJsonElement(argsJson).jsonObject, ToolContext(File(".")))
    }

    private fun json(result: ToolResult) = Json.parseToJsonElement(result.text).jsonObject

    @Test
    fun toolNameIsCurl() {
        assertEquals("curl", CurlTool().name)
        assertEquals("http_request", HttpRequestAliasTool(CurlTool()).name)
    }

    @Test
    fun aliasIsExecutableButInvisibleToTheModel() = runBlocking {
        // 别名与 `curl` 共用同一实现；注册进去后 names/apiTools 都看不见它。
        val registry = ToolRegistry(listOf(CurlTool()))
        assertTrue(registry.registerAlias(HttpRequestAliasTool(registry.tools.first())))
        assertEquals(listOf("curl"), registry.names)
        assertEquals(listOf("http_request"), registry.aliasNames)
        val viaAlias = registry.execute(
            "http_request",
            Json.parseToJsonElement("""{"url":"$base/get"}""").jsonObject,
            ToolContext(File(".")),
        )
        assertFalse(viaAlias.isError, viaAlias.text)
        assertEquals(200, json(viaAlias)["status"]!!.jsonPrimitive.int)
    }

    @Test
    fun getReturnsStatusBodyAndResponseHeadersWithoutSetCookie() {
        val result = run("""{"url":"$base/get"}""")
        assertFalse(result.isError, result.text)
        val obj = json(result)
        assertEquals(200, obj["status"]!!.jsonPrimitive.int)
        assertEquals("hello", obj["body"]!!.jsonPrimitive.content)
        assertEquals("text/plain; charset=utf-8", obj["contentType"]!!.jsonPrimitive.content)
        val responseHeaders = obj["headers"]!!.jsonObject
        val xTest = responseHeaders.entries.firstOrNull { it.key.equals("X-Test", ignoreCase = true) }
            ?: error("缺 X-Test 响应头：$responseHeaders")
        assertEquals("yes", xTest.value.jsonPrimitive.content)
        assertFalse(
            responseHeaders.keys.any { it.equals("Set-Cookie", ignoreCase = true) },
            "set-cookie 不能回显",
        )
        assertFalse(obj["truncated"]!!.jsonPrimitive.boolean)
        assertFalse(obj["unlimited"]!!.jsonPrimitive.boolean)
        assertTrue(obj["elapsedMs"]!!.jsonPrimitive.long >= 0)
        assertTrue(obj["url"]!!.jsonPrimitive.content.endsWith("/get"))
    }

    @Test
    fun postSendsBodyViaLegacyBodyKey() {
        val result = run(
            """{"url":"$base/post","method":"POST","headers":{"Content-Type":"text/plain"},"body":"ping"}""",
        )
        assertFalse(result.isError)
        assertEquals("POST:ping", json(result)["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun postSendsBodyViaCurlDataKey() {
        val result = run(
            """{"url":"$base/post","method":"POST","headers":{"Content-Type":"text/plain"},"data":"pong"}""",
        )
        assertFalse(result.isError)
        assertEquals("POST:pong", json(result)["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun redirectIsFollowedToFinalUrl() {
        val result = run("""{"url":"$base/redirect"}""")
        assertFalse(result.isError)
        val obj = json(result)
        assertEquals(200, obj["status"]!!.jsonPrimitive.int)
        assertTrue(obj["url"]!!.jsonPrimitive.content.endsWith("/get"))
        assertEquals("hello", obj["body"]!!.jsonPrimitive.content)
        assertEquals(1, obj["redirects"]!!.jsonPrimitive.int)
    }

    @Test
    fun maxRedirectsZeroReturnsTheRedirectItself() {
        val result = run("""{"url":"$base/redirect","follow_redirects":false}""")
        assertFalse(result.isError, result.text)
        assertEquals(302, json(result)["status"]!!.jsonPrimitive.int)
    }

    @Test
    fun nonHttpSchemesAreRejectedBeforeAnyRequest() {
        val result = run("""{"url":"file:///etc/passwd"}""")
        assertTrue(result.isError)
        assertTrue(result.text.contains("Only http/https URLs are allowed"), result.text)

        val ftp = run("""{"url":"ftp://example.com/x"}""")
        assertTrue(ftp.isError)
        assertTrue(ftp.text.contains("Only http/https URLs are allowed"), ftp.text)
    }

    @Test
    fun timeoutReturnsAnExplicitError() {
        val result = run("""{"url":"$base/slow","timeout_ms":100}""")
        assertTrue(result.isError)
        assertTrue(result.text.contains("curl request failed"), result.text)
    }

    @Test
    fun largeBodyIsTruncatedToMaxBytes() {
        val result = run("""{"url":"$base/large","max_bytes":10}""")
        assertFalse(result.isError)
        val obj = json(result)
        assertTrue(obj["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(10, obj["bytes"]!!.jsonPrimitive.int)
        assertEquals(10, obj["body"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun maxBytesZeroMeansNoTruncation() {
        val result = run("""{"url":"$base/large","max_bytes":0}""")
        assertFalse(result.isError, result.text)
        val obj = json(result)
        assertFalse(obj["truncated"]!!.jsonPrimitive.boolean)
        assertTrue(obj["unlimited"]!!.jsonPrimitive.boolean)
        assertEquals(1_000, obj["bytes"]!!.jsonPrimitive.int)
    }

    @Test
    fun binaryBodyComesBackAsBase64() {
        val result = run("""{"url":"$base/binary"}""")
        assertFalse(result.isError)
        val obj = json(result)
        assertEquals("base64", obj["encoding"]!!.jsonPrimitive.content)
        val decoded = Base64.getDecoder().decode(obj["body"]!!.jsonPrimitive.content)
        assertEquals(ByteArray(64) { (it * 3).toByte() }.toList(), decoded.toList())
    }

    @Test
    fun redirectToOtherSchemeAndRedirectLoopAreRejected() {
        val foreign = run("""{"url":"$base/to-ftp"}""")
        assertTrue(foreign.isError)
        assertTrue(foreign.text.contains("unsupported scheme"), foreign.text)

        val loop = run("""{"url":"$base/redirect-loop"}""")
        assertTrue(loop.isError)
        // 默认 max_redirects=10（docs/187 §6.5 去掉了原来的 5 跳硬限制）
        assertTrue(loop.text.contains("Redirect exceeded max_redirects=10"), loop.text)
    }
}
