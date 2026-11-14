package xin.dshaa.core.llm

import kotlinx.coroutines.flow.Flow
import xin.dshaa.core.protocol.ApiRequest

/**
 * M84-E2：**配置式模型供应商**（`ctx.llm.register` 的宿主实现）。
 *
 * DSH 桌面端的插件可以注册一个带 `stream()` 的 provider；手机端没有常驻 JS 运行时，
 * 接不了 JS 回调。这里退一步：插件只登记 id / 协议 / 端点 / 模型 / key，
 * 宿主把请求转给内置的两条通道（[LlmClient] = Anthropic Messages，[OpenAiClient] = OpenAI 兼容）。
 *
 * 端点/key 的回落规则（避免把宿主的 key 发给插件的端点）：
 * - 插件没写 `baseUrl` → 用宿主当前端点 + 宿主当前 key + 宿主当前模型（纯跟随）；
 * - 插件写了 `baseUrl` 但没写 key → 不回落宿主 key，交给通道报「未配置 API Key」；
 * - 插件写了 `model` → 请求体的 `model` 用它（否则跟随宿主当前模型）。
 *
 * 注册表只负责「可被选中」，不会自动激活；选中逻辑仍在宿主（AppViewModel / 调试通道）。
 */
class ConfiguredLlmProvider(
    private val config: LlmProviderConfig,
    private val anthropic: LlmClient = LlmClient(),
    private val openai: OpenAiClient = OpenAiClient(),
    /** 每次 stream 前回调一句人话（宿主拿去写 logcat；核心模块不依赖 Android）。 */
    private val onStream: (String) -> Unit = {},
) : LlmProvider {

    override val id: String = config.id

    override val label: String = config.label.ifBlank { config.id }

    override fun stream(request: ApiRequest, config: LlmConfig): Flow<LlmEvent> {
        val pluginBase = this.config.baseUrl.trim()
        val followHost = pluginBase.isBlank()
        val effective = LlmConfig(
            apiKey = this.config.apiKey.ifBlank { if (followHost) config.apiKey else "" },
            baseUrl = if (followHost) config.baseUrl else pluginBase,
            model = this.config.model.ifBlank { config.model },
            maxTokens = config.maxTokens,
            temperature = config.temperature,
        )
        val effectiveRequest = if (this.config.model.isNotBlank() && request.model != this.config.model) {
            request.copy(model = this.config.model)
        } else {
            request
        }
        onStream(
            "plugin provider id=${this.config.id} protocol=${this.config.protocol} " +
                "model=${effective.model} baseUrl=${if (followHost) "host" else "plugin"} " +
                "apiKey=${if (this.config.apiKey.isNotBlank()) "plugin" else if (followHost) "host" else "none"} " +
                "requestModel=${request.model}",
        )
        return if (this.config.protocol == "openai") {
            openai.stream(effectiveRequest, effective)
        } else {
            anthropic.stream(effectiveRequest, effective)
        }
    }
}

/** 插件登记的一条供应商配置（不依赖 plugins 模块，避免 llm → plugins 反向依赖）。 */
data class LlmProviderConfig(
    val id: String,
    val label: String = "",
    val protocol: String = "anthropic",
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    /** 来源插件 id（日志/插件页展示用）。 */
    val ownerPlugin: String = "",
)
