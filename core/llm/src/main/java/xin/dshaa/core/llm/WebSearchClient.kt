package xin.dshaa.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xin.dshaa.core.protocol.ProtocolJson
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * DeepSeek 原生 web 搜索（移植 `@deepseek-ai/dsh-web-search-deepseek`）。
 *
 * 它不是独立的搜索 API，而是**一次 Messages 调用**：请求里挂 Anthropic 原生
 * `web_search_20250305` 服务端工具，响应里取 `web_search_tool_result` 块 + text 块的 citations。
 * 一次搜索 = 一个模型轮次（延迟与 token 成本都在这里），所以工具层限制一次调用最多 4 条 query。
 */
class WebSearchClient(
    private val http: OkHttpClient = defaultSearchClient(),
    private val config: LlmConfig,
    /** 辅助搜索端点的模型；上游默认 `deepseek-v4-flash`。 */
    private val searchModel: String = "deepseek-v4-flash",
    private val maxTokens: Int = 4096,
    private val maxUsesPerQuery: Int = 5,
) {

    data class Source(
        val url: String,
        val title: String? = null,
        val snippet: String? = null,
        val publishedAt: String? = null,
    )

    data class Outcome(val sources: List<Source>, val answer: String?)

    class SearchException(message: String) : Exception(message)

    suspend fun search(query: String): Outcome = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) throw SearchException("未配置凭据，无法搜索")
        val body = buildJsonObject {
            put("model", searchModel)
            put("max_tokens", maxTokens)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", "Perform a web search for the query: $query")
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
            put(
                "tools",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "web_search_20250305")
                            put("name", "web_search")
                            put("max_uses", maxUsesPerQuery)
                        },
                    )
                },
            )
            put("thinking", buildJsonObject { put("type", "disabled") })
        }
        val request = Request.Builder()
            .url(messagesUrl(config.baseUrl))
            .post(body.toString().toRequestBody(JSON))
            .apply {
                if (config.authMode == AuthMode.ACCOUNT) header("x-dsh-auth-token", config.apiKey)
                else header("x-api-key", config.apiKey)
            }
            .header("anthropic-version", LlmConfig.ANTHROPIC_VERSION)
            .header("Content-Type", "application/json")
            .build()
        val text = try {
            http.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw SearchException("搜索请求失败 HTTP ${response.code}: ${payload.take(300)}")
                }
                payload
            }
        } catch (e: IOException) {
            throw SearchException("搜索请求失败：${e.message ?: e.javaClass.simpleName}")
        }
        parseResponse(ProtocolJson.json.parseToJsonElement(text).jsonObject)
    }

    /** 纯解析：content[] 里取引用片段 + web_search_result 列表（按 url 去重）。 */
    fun parseResponse(response: JsonObject): Outcome {
        val blocks = response["content"]?.jsonArray ?: emptyList()
        val resultBlocks = blocks.mapNotNull { it as? JsonObject }
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "web_search_tool_result" }
        if (resultBlocks.isEmpty()) {
            throw SearchException(
                "DeepSeek returned no web_search_tool_result blocks; the request may not have triggered native web search",
            )
        }
        val snippets = LinkedHashMap<String, String>()
        val answers = ArrayList<String>()
        blocks.mapNotNull { it as? JsonObject }.forEach { block ->
            when (block["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> {
                    val text = block["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (text.isNotBlank()) answers += text
                    (block["citations"] as? kotlinx.serialization.json.JsonArray).orEmpty().forEach { citeEl ->
                        val cite = citeEl as? JsonObject ?: return@forEach
                        val url = cite["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val cited = cite["cited_text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (url.isNotEmpty() && cited.isNotEmpty() && url !in snippets) snippets[url] = cited
                    }
                }
            }
        }
        val seen = HashSet<String>()
        val sources = ArrayList<Source>()
        resultBlocks.forEach { block ->
            (block["content"] as? kotlinx.serialization.json.JsonArray).orEmpty().forEach { itemEl ->
                val item = itemEl as? JsonObject ?: return@forEach
                if (item["type"]?.jsonPrimitive?.contentOrNull != "web_search_result") return@forEach
                val url = item["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (url.isEmpty() || !seen.add(url)) return@forEach
                sources += Source(
                    url = url,
                    title = item["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    snippet = snippets[url] ?: item["snippet"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    publishedAt = item["page_age"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                )
            }
        }
        return Outcome(sources, answers.joinToString("\n\n").ifBlank { null })
    }

    /**
     * 辅助搜索端点的 URL：与聊天端点独立（上游默认 `https://api.deepseek.com/anthropic/v1` + `/messages`）。
     * 对 `…/anthropic` 这种聊天基址自动补 `/v1`，已带 `/v1` 的则不重复。
     */
    internal fun messagesUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.endsWith("/v1/messages") -> base
            base.endsWith("/messages") -> base
            base.endsWith("/v1") -> "$base/messages"
            else -> "$base/v1/messages"
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun defaultSearchClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
