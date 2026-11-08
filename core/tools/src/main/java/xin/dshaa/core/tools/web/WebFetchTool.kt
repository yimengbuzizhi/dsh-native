package xin.dshaa.core.tools.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult
import java.util.concurrent.TimeUnit

/**
 * `web_fetch`：抓一个 HTTP(S) URL，解码成文本返回。
 * 上游描述「return it decoded to text」——这里做 HTML→纯文本（去 script/style/标签、解实体），
 * 其它 content-type 原样返回；超长截断到 30k 字符（与 shell 工具一致的口径）。
 */
class WebFetchTool(private val http: OkHttpClient = defaultClient()) : DshTool {
    override val name = "web_fetch"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val url = runCatching { args["url"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?: return ToolResult.err("missing url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.err("only http(s) URLs are supported")
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "DSH-Native/0.2 (Android)")
            .header("Accept", "text/html,application/xhtml+xml,text/plain,*/*")
            .build()
        return withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use ToolResult.err("HTTP ${response.code}: ${response.message}")
                    }
                    val contentType = response.header("content-type").orEmpty()
                    val bytes = response.body?.bytes() ?: return@use ToolResult.err("empty body")
                    val raw = bytes.toString(Charsets.UTF_8)
                    val text = if (contentType.contains("html", true)) htmlToText(raw) else raw
                    ToolResult.ok(if (text.length > 30_000) text.take(30_000) + "\n…(truncated)" else text)
                }
            }.getOrElse { ToolResult.err("fetch failed: ${it.message}") }
        }
    }

    private fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<script.*?</script>"), " ")
        s = s.replace(Regex("(?is)<style.*?</style>"), " ")
        s = s.replace(Regex("(?is)<noscript.*?</noscript>"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|h[1-6]|li|tr|section|article|header|footer)>"), "\n")
        s = s.replace(Regex("(?s)<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&mdash;", "—")
            .replace("&hellip;", "…")
        s = s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        s = s.replace(Regex(" *\\n *"), "\n")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
