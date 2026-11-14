package xin.dshaa.core.prompt

import java.util.concurrent.ConcurrentHashMap

/**
 * M84-C：**可替换的系统提示词实现**。
 *
 * 默认实现仍是 [PromptPack.systemFor]（段/order/变量/角色卡全部保留）；
 * 注册一个同 id 的 [PromptProvider] 就能整体替换拼装逻辑，或者包一层默认实现做
 * 「最终 transform」（追加/删改/审计）。`docs/115 §C` 的提示词可替换对应这里；
 * 插件侧的 `ctx.systemPrompt.transform` 在 §E 接。
 */
interface PromptProvider {
    val id: String
    val label: String

    fun system(pack: PromptPack, request: PromptRequest): String
}

/** 一次 system 拼装的输入（和 [PromptPack.systemFor] 参数一一对应）。 */
data class PromptRequest(
    val model: String,
    val provider: String,
    val tools: Set<String>,
    val platform: String = "android",
    val planMode: Boolean = false,
    val personaPrefix: String = "",
    val personaSuffix: String = "",
    val extraSections: List<PromptSection> = emptyList(),
    val variables: Map<String, String> = emptyMap(),
)

object PromptProviders {
    private val providers = ConcurrentHashMap<String, PromptProvider>()

    /** 当前选中的提示词实现；null = 默认 [PromptPack.systemFor]。 */
    @Volatile
    var activeId: String? = null

    fun register(provider: PromptProvider): PromptProvider? = providers.put(provider.id, provider)

    fun unregister(id: String): PromptProvider? = providers.remove(id)

    fun get(id: String?): PromptProvider? = id?.let { providers[it] }

    /** 拼 system：activeId 有效就用它的实现，否则用打包的默认实现。 */
    fun build(pack: PromptPack, request: PromptRequest): String {
        val custom = get(activeId)
        return custom?.system(pack, request) ?: pack.systemFor(
            model = request.model,
            provider = request.provider,
            tools = request.tools,
            platform = request.platform,
            planMode = request.planMode,
            personaPrefix = request.personaPrefix,
            personaSuffix = request.personaSuffix,
            extraSections = request.extraSections,
            variables = request.variables,
        )
    }

    fun ids(): List<String> = providers.keys.sorted()

    /** 单测用；生产不调用。 */
    fun clearForTest() {
        providers.clear()
        activeId = null
    }
}
