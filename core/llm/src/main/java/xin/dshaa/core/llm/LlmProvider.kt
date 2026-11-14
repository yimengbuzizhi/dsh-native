package xin.dshaa.core.llm

import kotlinx.coroutines.flow.Flow
import xin.dshaa.core.protocol.ApiRequest
import java.util.concurrent.ConcurrentHashMap

/**
 * M84-C：**可替换的模型供应商**。
 *
 * 宿主内置两条通道（`deepseek` = Anthropic Messages、`openai` = OpenAI chat/completions），
 * 但请求路径不再写死 `if (protocol == "openai")`：引擎只问注册表要一个 [LlmProvider]，
 * 换供应商 = 注册同一个 id 的新实现（或注册新 id + 切到它），不需要改 TurnEngine。
 *
 * 与 `docs/115 §C` 的 `llm.register` 对应；外部 JS 插件的注册入口在 §E 接。
 * 注册表本身不碰网络、不读设置：选哪个 id 由宿主（AppViewModel）决定。
 */
interface LlmProvider {
    /** 稳定 id：`deepseek` / `openai` / 插件自定义（如 `my-provider`）。 */
    val id: String

    /** 设置页/日志展示用。 */
    val label: String

    /** 发一次流式请求。实现方负责把 HTTP/SSE 解析成 [LlmEvent]。 */
    fun stream(request: ApiRequest, config: LlmConfig): Flow<LlmEvent>
}

object LlmProviders {
    private val providers = ConcurrentHashMap<String, LlmProvider>()

    /** 当前选中的供应商 id；null = 由宿主按协议兜底。 */
    @Volatile
    var activeId: String? = null

    /** 注册/覆盖一个供应商；返回被覆盖的旧实现（没有则 null）。 */
    fun register(provider: LlmProvider): LlmProvider? = providers.put(provider.id, provider)

    fun unregister(id: String): LlmProvider? = providers.remove(id)

    fun get(id: String?): LlmProvider? = id?.let { providers[it] }

    /** 解析：显式 id > activeId；都没有则 null（宿主回落到内置客户端）。 */
    fun resolve(requested: String? = null): LlmProvider? =
        get(requested) ?: get(activeId)

    fun ids(): List<String> = providers.keys.sorted()

    /** 单测用；生产不调用。 */
    fun clearForTest() {
        providers.clear()
        activeId = null
    }
}
