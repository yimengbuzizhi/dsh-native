package xin.dshaa.core.engine

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xin.dshaa.core.llm.LlmConfig
import xin.dshaa.core.protocol.ApiMessage
import xin.dshaa.core.protocol.ApiRequest
import xin.dshaa.core.protocol.ApiTool
import xin.dshaa.core.protocol.ProtocolJson
import xin.dshaa.core.protocol.TokenUsage
import java.security.MessageDigest

/**
 * 工作流 D（docs/187 §7.2）：缓存诊断落盘口。
 *
 * `core` 不能依赖 Android，所以这里只定义「写一行」的接口与记录形状；
 * App 层（debug 包）把它实现成追加到 `files/cache-debug.jsonl`。release 里宿主不注入 sink，
 * 引擎每次请求只多做一次 null 判断。
 */
fun interface CacheDebugSink {
    /** 一条记录 = 一行 JSON（不含换行；由实现方补 `\n`）。 */
    fun emit(line: String)
}

/**
 * 一次主回合请求（一个 step 的一次尝试）的缓存诊断快照。
 *
 * 前缀哈希都取**本次实际发出的请求**：system 文本 / tools JSON（Anthropic 形状的稳定序列化）/
 * messages 数组。同一份输入必须得到同一个哈希——哈希变了就说明前缀真的变了（缓存会从第一个
 * 变化 token 开始失效）。
 */
data class CacheDebugRecord(
    val provider: String,
    val model: String,
    val systemSha256: String,
    val toolsSha256: String,
    val messagesPrefixSha256: String,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val cacheRead: Int?,
    val cacheCreation: Int?,
    val requestNo: Int,
    val at: Long,
) {
    fun toJsonLine(): String = buildJsonObject {
        put("provider", provider)
        put("model", model)
        put("systemSha256", systemSha256)
        put("toolsSha256", toolsSha256)
        put("messagesPrefixSha256", messagesPrefixSha256)
        put("inputTokens", inputTokens?.let { JsonPrimitive(it) } ?: JsonNull)
        put("outputTokens", outputTokens?.let { JsonPrimitive(it) } ?: JsonNull)
        put("cacheRead", cacheRead?.let { JsonPrimitive(it) } ?: JsonNull)
        put("cacheCreation", cacheCreation?.let { JsonPrimitive(it) } ?: JsonNull)
        put("requestNo", requestNo)
        put("at", at)
    }.toString()

    companion object {
        /**
         * 用本次请求 + 本次请求的累计 usage 生成一条记录。
         *
         * [TokenUsage.inputTokens] 已经是未缓存输入；这里不再做通道差异处理。
         */
        fun of(
            request: ApiRequest,
            usage: TokenUsage,
            config: LlmConfig,
            providerLabel: String,
            requestNo: Int,
            at: Long = System.currentTimeMillis(),
        ): CacheDebugRecord = CacheDebugRecord(
            provider = cacheDebugProviderName(providerLabel, config.baseUrl),
            model = config.model,
            systemSha256 = sha256Hex(request.system.orEmpty()),
            toolsSha256 = sha256Hex(
                ProtocolJson.json.encodeToString(ListSerializer(ApiTool.serializer()), request.tools.orEmpty()),
            ),
            messagesPrefixSha256 = sha256Hex(
                ProtocolJson.json.encodeToString(ListSerializer(ApiMessage.serializer()), request.messages),
            ),
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            cacheRead = usage.cacheReadInputTokens,
            cacheCreation = usage.cacheCreationInputTokens,
            requestNo = requestNo,
            at = at,
        )
    }
}

/**
 * 诊断日志里的通道名：要能一眼区分 Anthropic Messages 与 OpenAI chat/completions。
 *
 * - 内置 DeepSeek（`providerLabel="deepseek"`）默认端点是 `…/anthropic` → `deepseek-anthropic`；
 * - 同一通道换到 OpenAI 兼容端点（`https://api.deepseek.com`）→ `deepseek-openai`；
 * - OpenAI 官方/兼容供应商 → `openai-chat`；Anthropic 官方 → `anthropic-messages`。
 *
 * 不用 providerLabel 单独判断通道：设置里的 `deepseek-openai` 也可能映射到 `deepseek` 标签，
 * 真正决定线上形状的是端点（`/anthropic` 或 `/v1/messages` = Anthropic Messages）。
 */
internal fun cacheDebugProviderName(providerLabel: String, baseUrl: String): String {
    val base = baseUrl.trim().trimEnd('/')
    val anthropicStyle = base.endsWith("/anthropic") ||
        base.endsWith("/v1/messages") ||
        base.contains("api.anthropic.com")
    return when {
        anthropicStyle && providerLabel == "deepseek" -> "deepseek-anthropic"
        anthropicStyle && providerLabel == "anthropic" -> "anthropic-messages"
        anthropicStyle -> "$providerLabel-anthropic"
        providerLabel == "deepseek" -> "deepseek-openai"
        providerLabel == "openai" -> "openai-chat"
        else -> "$providerLabel-openai"
    }
}

/** 完整 SHA-256（64 个 hex 字符）；同一字符串永远同一结果，字符集固定 UTF-8。 */
internal fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }
}

private const val HEX = "0123456789abcdef"
