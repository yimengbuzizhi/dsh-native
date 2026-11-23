package xin.dshaa.core.tools.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * `curl` 工具（`docs/187` §6.5）：发一次 HTTP 请求，返回**结构化结果**。
 *
 * 为什么叫 `curl`：模型对 `curl` 这个名字有强先验（以前的 `http_request` 它认不出来，
 * 总要试探）。名字换成 `curl`，语义仍然是结构化的 `url/method/headers/data`，
 * **不是**解析命令行——命令行 curl 走 `bash` 工具。
 *
 * 边界（安全边界，不是「限制」）：
 * - 只允许 http/https；`file://` 之类一律拒绝；
 * - 重定向可配 `max_redirects`（默认 10，最大 50），跳到非 http(s) 直接拒绝；
 * - 不回显请求头（可能含 token），只回响应头，且去掉 `set-cookie`；
 * - 响应体按 `max_bytes` 截断（`truncated=true` 不算错误）；`max_bytes=0` = 不截断
 *   （只剩 [SAFETY_MAX_BYTES] 这个防止把手机内存吃爆的硬顶，不是业务限制）；
 * - 非文本 content-type 用 base64；不读本地文件、不自动重试 POST。
 *
 * 归内置组 `builtin.web`（与 web_search/web_fetch 一起可整体关闭）。
 */
class CurlTool(private val http: OkHttpClient = WebFetchTool.defaultClient()) : DshTool {
    override val name = "curl"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val method = (args.text("method") ?: "GET").trim().uppercase(Locale.ROOT)
        if (method !in METHODS) {
            return ToolResult.err("Unsupported method: $method (available: ${METHODS.joinToString(" / ")})")
        }
        val rawUrl = args.text("url").orEmpty().trim()
        if (rawUrl.isEmpty()) return ToolResult.err("curl requires a url")
        val scheme = rawUrl.substringBefore(':', "").lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            return ToolResult.err("Only http/https URLs are allowed; got scheme=${scheme.ifBlank { "(none)" }}")
        }
        val url = rawUrl.toHttpUrlOrNull()
            ?: return ToolResult.err("Invalid URL: $rawUrl")

        val timeoutMs = (args.int("timeout_ms") ?: DEFAULT_TIMEOUT_MS).coerceIn(1, MAX_TIMEOUT_MS)
        // 0 = 不截断（仍然有 16MB 的防 OOM 硬顶）；正数按用户给的值，clamp 到 1..16MB。
        val requestedMaxBytes = args.int("max_bytes") ?: DEFAULT_MAX_BYTES
        val maxBytes = if (requestedMaxBytes <= 0) {
            SAFETY_MAX_BYTES
        } else {
            requestedMaxBytes.coerceIn(1, SAFETY_MAX_BYTES)
        }
        val unlimited = requestedMaxBytes <= 0
        val followRedirects = args.boolean("follow_redirects") ?: true
        val maxRedirects = (args.int("max_redirects") ?: DEFAULT_MAX_REDIRECTS).coerceIn(0, MAX_REDIRECTS)
        val requestHeaders = args["headers"] as? JsonObject
        if (args["headers"] != null && requestHeaders == null) {
            return ToolResult.err("headers must be a JSON object (string → string)")
        }
        // `data` 是 curl 习惯的名字；`body` 是旧名字，继续接受（老会话/提示词写法）。
        val bodyText = args.text("data") ?: args.text("body")
        val client = http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()

        return withContext(Dispatchers.IO) {
            try {
                val started = System.currentTimeMillis()
                var currentUrl = url
                var currentMethod = method
                var currentBody = requestBody(method, bodyText, requestHeaders)
                var hops = 0
                var response: Response
                while (true) {
                    val callHeaders = ArrayList<Pair<String, String>>()
                    requestHeaders?.forEach { (key, value) ->
                        val text = runCatching { (value as JsonPrimitive).contentOrNull }.getOrNull()
                        if (!text.isNullOrEmpty()) callHeaders += key to text
                    }
                    val request = Request.Builder()
                        .url(currentUrl)
                        .method(currentMethod, currentBody)
                        .apply { callHeaders.forEach { (k, v) -> header(k, v) } }
                        .build()
                    response = client.newCall(request).execute()
                    if (!followRedirects) break
                    if (response.code !in REDIRECT_CODES) break
                    val location = response.header("Location")
                    if (location.isNullOrBlank()) break
                    response.close()
                    if (hops >= maxRedirects) {
                        throw IllegalArgumentException("Redirect exceeded max_redirects=$maxRedirects; stopped")
                    }
                    // 先看 Location 自己的 scheme：HttpUrl.resolve 对非 http(s) 返回 null，
                    // 那样只能报「非法地址」，说不清是跳到别的 scheme 被拒。
                    val locationText = location.trim()
                    if (!locationText.startsWith("//")) {
                        val locationScheme = locationText.substringBefore(':', "").lowercase(Locale.ROOT)
                        if (locationScheme.isNotEmpty() && locationScheme != "http" && locationScheme != "https") {
                            throw IllegalArgumentException("Redirect to an unsupported scheme: $locationScheme")
                        }
                    }
                    val next = currentUrl.resolve(locationText)
                        ?: throw IllegalArgumentException("Invalid redirect location: $location")
                    if (next.scheme != "http" && next.scheme != "https") {
                        throw IllegalArgumentException("Redirect to an unsupported scheme: ${next.scheme}")
                    }
                    hops++
                    if (response.code == 303 ||
                        ((response.code == 301 || response.code == 302) &&
                            currentMethod.equals("POST", ignoreCase = true))
                    ) {
                        currentMethod = "GET"
                        currentBody = null
                    }
                    currentUrl = next
                }
                response.use { resp -> buildResult(resp, currentUrl, started, maxBytes, unlimited, hops) }
            } catch (e: Exception) {
                ToolResult.err("curl request failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun requestBody(method: String, bodyText: String?, headers: JsonObject?): RequestBody? {
        if (method == "GET" || method == "HEAD") return null
        if (bodyText == null) return null
        val contentType = headers?.entries
            ?.firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value
            ?.let { runCatching { (it as JsonPrimitive).contentOrNull }.getOrNull() }
            ?: "text/plain; charset=utf-8"
        return bodyText.toRequestBody(contentType.toMediaTypeOrNull())
    }

    private fun buildResult(
        response: Response,
        finalUrl: HttpUrl,
        started: Long,
        maxBytes: Int,
        unlimited: Boolean,
        redirects: Int,
    ): ToolResult {
        val contentType = response.header("content-type").orEmpty()
        val raw = if (response.request.method == "HEAD") {
            ByteArray(0)
        } else {
            response.body?.let { readLimited(it.source(), maxBytes) } ?: ByteArray(0)
        }
        val truncated = raw.size > maxBytes
        val bytes = if (truncated) raw.copyOf(maxBytes) else raw
        val textual = isTextual(contentType)
        val body = if (textual) {
            bytes.toString(Charsets.UTF_8)
        } else {
            Base64.getEncoder().encodeToString(bytes)
        }
        val headersJson = buildJsonObject {
            val names = response.headers.names().take(MAX_RESPONSE_HEADERS)
            names.forEach { name ->
                if (name.equals("set-cookie", ignoreCase = true)) return@forEach
                val values = response.headers.values(name)
                    .joinToString(", ")
                    .let { if (it.length > MAX_HEADER_CHARS) it.take(MAX_HEADER_CHARS) + "…" else it }
                put(name, values)
            }
        }
        val elapsedMs = System.currentTimeMillis() - started
        val json = buildJsonObject {
            put("ok", true)
            put("status", response.code)
            put("url", finalUrl.toString())
            put("method", response.request.method)
            put("redirects", redirects)
            put("headers", headersJson)
            put("body", body)
            put("encoding", if (textual) "utf-8" else "base64")
            put("bytes", bytes.size)
            put("truncated", truncated)
            put("unlimited", unlimited)
            put("contentType", contentType)
            put("elapsedMs", elapsedMs)
        }
        return ToolResult.ok(json.toString())
    }

    /** 最多读 [maxBytes]+1 字节（多读 1 字节只用来判 `truncated`），不把大响应整个吃进内存。 */
    private fun readLimited(source: BufferedSource, maxBytes: Int): ByteArray {
        val buffer = okio.Buffer()
        val cap = maxBytes.toLong() + 1L
        while (buffer.size < cap) {
            val read = source.read(buffer, cap - buffer.size)
            if (read == -1L) break
        }
        return buffer.readByteArray()
    }

    private fun isTextual(contentType: String): Boolean {
        val ct = contentType.lowercase(Locale.ROOT)
        if (ct.isBlank()) return true
        return ct.startsWith("text/") ||
            ct.contains("json") ||
            ct.contains("xml") ||
            ct.contains("javascript") ||
            ct.contains("x-www-form-urlencoded") ||
            ct.contains("graphql")
    }

    private fun JsonObject.text(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.int(key: String): Int? =
        runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()

    private fun JsonObject.boolean(key: String): Boolean? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() }.getOrNull()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000
        const val MAX_TIMEOUT_MS = 60_000
        const val DEFAULT_MAX_BYTES = 262_144

        /** `max_bytes=0`（不截断）时的硬顶：只为防 OOM，不是业务限制。 */
        const val SAFETY_MAX_BYTES = 16 * 1024 * 1024
        const val DEFAULT_MAX_REDIRECTS = 10
        const val MAX_REDIRECTS = 50
        const val MAX_RESPONSE_HEADERS = 100
        const val MAX_HEADER_CHARS = 8_192

        val METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/**
 * 旧会话兼容别名：`http_request` → [CurlTool]。
 *
 * 只经 [xin.dshaa.core.tools.ToolRegistry.execute] 命中（`registerAlias`），
 * **不**出现在 `ToolRegistry.names` / `apiTools()` / 提示词工具清单里——
 * 模型只看得见 `curl`，老会话里残留的 `http_request` 调用仍能执行。
 */
class HttpRequestAliasTool(private val delegate: DshTool) : DshTool {
    override val name = "http_request"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = delegate.execute(args, ctx)
}
