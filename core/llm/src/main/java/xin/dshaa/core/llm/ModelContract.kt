package xin.dshaa.core.llm

// M85-E：model/v1 —— 模型（供应商）角色的契约 + 选择规则。
//
// 设计基线是 docs/144 的第 3 节：模型协议从「宿主写死两条通道」变成「内置插件 + 第三方插件
// 共用同一个 model/v1 角色」。
// - 内置 builtin.model.deepseek / builtin.model.openai = 现有 LlmClient / OpenAiClient；
// - 第三方插件继续用 ctx.llm.register({id,label,stream?}) 声明（M84-J/M），id 与内置相同即覆盖；
// - 选中的通道被停用/不存在时**如实报错**，不悄悄换回内置供应商。
// 本文件是纯 Kotlin，选择规则可在 JVM 单测里锁死。

/** 契约版本（插件页/调试 dump 里展示；演进时宿主同时支持旧版）。 */
const val MODEL_CONTRACT: String = "model/v1"

/** 内置模型插件的稳定 id（一个插件对应一个 provider id）。 */
const val BUILTIN_MODEL_DEEPSEEK: String = "builtin.model.deepseek"
const val BUILTIN_MODEL_OPENAI: String = "builtin.model.openai"

/** 内置两条通道的 provider id（和设置里 `llmProviderId` 的写法一致）。 */
const val MODEL_PROVIDER_DEEPSEEK: String = "deepseek"
const val MODEL_PROVIDER_OPENAI: String = "openai"

/**
 * 一个可选的模型通道。
 *
 * [builtinPluginId] 非空 = 内置模型插件（可停用）；[pluginId] 非空 = 导入插件声明的 provider。
 * 同 id 覆盖：第三方 provider id 与内置相同且启用时优先。
 */
data class ModelCandidate(
    val providerId: String,
    val label: String,
    val pluginId: String? = null,
    val builtinPluginId: String? = null,
    val builtin: Boolean = false,
    val enabled: Boolean = true,
    val permitted: Boolean = true,
)

/** 已选中的模型通道。 */
data class ResolvedModel(
    val providerId: String,
    val label: String,
    val pluginId: String? = null,
    val builtinPluginId: String? = null,
    val builtin: Boolean = false,
)

/** 解析结果：要么有可用通道，要么给出「为什么没有」的用户可读文案。 */
sealed interface ModelResolution {
    data class Active(val model: ResolvedModel) : ModelResolution

    data class Unavailable(val reason: String) : ModelResolution
}

/**
 * 用户选择 / 协议兜底 → 具体模型通道。
 *
 * - [selected] 为空：按 [protocol] 兜底（`openai` → openai，其余 → deepseek）；
 * - [selected] 是 provider id：同 id 第三方优先，其次内置；被停用 ⇒ [ModelResolution.Unavailable]；
 * - [selected] 是内置插件 id（`builtin.model.*`）：映射到它的 provider id 再解析；
 * - 任何「选不中」都不回退到别的通道（不悄悄用内置供应商）。
 */
object ModelRouting {

    fun providerOfBuiltinPlugin(pluginId: String?): String? = when {
        pluginId == null -> null
        pluginId.equals(BUILTIN_MODEL_DEEPSEEK, true) -> MODEL_PROVIDER_DEEPSEEK
        pluginId.equals(BUILTIN_MODEL_OPENAI, true) -> MODEL_PROVIDER_OPENAI
        else -> null
    }

    fun resolve(
        selected: String?,
        protocol: String,
        candidates: List<ModelCandidate>,
    ): ModelResolution {
        val raw = selected?.trim().orEmpty()
        val wanted = when {
            raw.isEmpty() -> if (protocol.equals("openai", true)) MODEL_PROVIDER_OPENAI else MODEL_PROVIDER_DEEPSEEK
            providerOfBuiltinPlugin(raw) != null -> providerOfBuiltinPlugin(raw)!!
            else -> raw
        }
        val known = candidates.filter { it.providerId.equals(wanted, true) }
        val usable = known.filter { it.enabled && it.permitted }
        if (usable.isNotEmpty()) {
            // 同 id 覆盖：第三方优先（按候选顺序），否则内置 native。
            val chosen = usable.firstOrNull { !it.builtin } ?: usable.first()
            return ModelResolution.Active(chosen.resolved())
        }
        return ModelResolution.Unavailable(
            when {
                known.isEmpty() -> "未安装模型插件 $wanted（没有启用声明 model/v1 的插件）。"
                else -> {
                    val disabledBuiltin = known.any { it.builtin && !it.enabled }
                    val disabledPlugin = known.firstOrNull { !it.builtin && !it.enabled }
                    val missingPermission = known.firstOrNull { !it.permitted }
                    when {
                        missingPermission != null -> "模型插件 ${missingPermission.label} 缺少 model 授权。"
                        disabledBuiltin && disabledPlugin != null ->
                            "模型插件 ${disabledPlugin.label} 已停用，内置 $wanted 也停用；没有可用模型通道。"
                        disabledBuiltin -> "内置模型插件 $wanted 已停用；没有可用模型通道。"
                        disabledPlugin != null -> "模型插件 ${disabledPlugin.label} 已停用；没有可用模型通道。"
                        else -> "模型通道 $wanted 不可用。"
                    }
                }
            },
        )
    }
}

private fun ModelCandidate.resolved(): ResolvedModel = ResolvedModel(
    providerId = providerId,
    label = label,
    pluginId = pluginId,
    builtinPluginId = builtinPluginId,
    builtin = builtin,
)
