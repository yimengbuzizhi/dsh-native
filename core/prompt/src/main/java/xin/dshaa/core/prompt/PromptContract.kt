package xin.dshaa.core.prompt

// M85-E：prompt/v1 —— 提示词（system 拼装）角色的契约 + 选择规则。
//
// 设计基线是 docs/144 的第 3 节：默认 builtin.prompt.dsh = 现有 PromptPack 的 native 实现
// （段/order/变量/角色卡/插件段全部保留）；第三方用 ctx.systemPrompt.register({id,label,assemble})
// 声明，同 id 可覆盖内置实现。
// 本文件是纯 Kotlin，选择规则可在 JVM 单测里锁死。

/** 契约版本（插件页/调试 dump 里展示；演进时宿主同时支持旧版）。 */
const val PROMPT_CONTRACT: String = "prompt/v1"

/** 内置提示词插件（PromptPack 的 native 实现）的稳定 id。 */
const val BUILTIN_PROMPT_ID: String = "builtin.prompt.dsh"

/** 常驻 JS 运行时里的拼装 dispatch 方法名。 */
const val PROMPT_ASSEMBLE_METHOD: String = "prompt.assemble"

/** 一个可选的提示词实现。 */
data class PromptCandidate(
    val promptId: String,
    val label: String,
    val pluginId: String? = null,
    val builtin: Boolean = false,
    val enabled: Boolean = true,
    val permitted: Boolean = true,
)

/** 已选中的提示词实现。 */
data class ResolvedPrompt(
    val promptId: String,
    val label: String,
    val pluginId: String? = null,
    val builtin: Boolean = false,
)

/** 解析结果：要么有可用实现，要么给出「为什么没有」的安全提示文案。 */
sealed interface PromptResolution {
    data class Active(val prompt: ResolvedPrompt) : PromptResolution

    data class Unavailable(val reason: String) : PromptResolution
}

/**
 * `dsh_prompt` 调试项 / 插件页选择 → 具体提示词实现。
 *
 * 规则与 agent/storage 一致：空 / `builtin.prompt.dsh` = 默认角色（同 id 第三方优先）；
 * `native` = 强制内置（不给同 id 接管）；`插件id` / `插件id#实现id` / 实现 id = 精确选择；
 * 选不中 ⇒ [PromptResolution.Unavailable]（不悄悄用内置拼装冒充第三方）。
 */
object PromptRouting {

    fun resolve(pref: String?, candidates: List<PromptCandidate>): PromptResolution {
        val raw = pref?.trim().orEmpty()
        if (raw.equals("native", true)) {
            val builtin = candidates.firstOrNull {
                it.builtin && it.promptId.equals(BUILTIN_PROMPT_ID, true) && it.enabled && it.permitted
            } ?: return PromptResolution.Unavailable(
                "内置提示词插件 $BUILTIN_PROMPT_ID 已停用；没有可用的系统提示词。",
            )
            return PromptResolution.Active(builtin.resolved())
        }
        val wantsDefault = raw.isEmpty() || raw.equals(BUILTIN_PROMPT_ID, true)
        if (wantsDefault) {
            val usable = candidates.filter {
                it.enabled && it.permitted && it.promptId.equals(BUILTIN_PROMPT_ID, true)
            }
            if (usable.isEmpty()) {
                return PromptResolution.Unavailable(
                    "内置提示词插件 $BUILTIN_PROMPT_ID 已停用；没有可用的系统提示词。",
                )
            }
            val chosen = usable.firstOrNull { !it.builtin } ?: usable.first()
            return PromptResolution.Active(chosen.resolved())
        }
        val target = raw.substringBefore('#').trim()
        val wantedPromptId = raw.substringAfter('#', "").trim()
        val match = candidates.firstOrNull { candidate ->
            if (!candidate.enabled || !candidate.permitted) return@firstOrNull false
            candidate.promptId.equals(target, true) ||
                (!candidate.builtin && candidate.pluginId?.equals(target, true) == true &&
                    (wantedPromptId.isEmpty() || candidate.promptId.equals(wantedPromptId, true)))
        }
        if (match != null) return PromptResolution.Active(match.resolved())
        val wantsDefaultId = target.equals(BUILTIN_PROMPT_ID, true) ||
            wantedPromptId.equals(BUILTIN_PROMPT_ID, true)
        if (wantsDefaultId) {
            val builtin = candidates.firstOrNull {
                it.builtin && it.promptId.equals(BUILTIN_PROMPT_ID, true) && it.enabled && it.permitted
            }
            if (builtin != null) return PromptResolution.Active(builtin.resolved())
        }
        val known = candidates.firstOrNull {
            it.promptId.equals(target, true) || it.pluginId?.equals(target, true) == true
        }
        return PromptResolution.Unavailable(
            when {
                known == null -> "未安装提示词插件 $raw（没有声明 $PROMPT_CONTRACT 的启用插件）。"
                !known.enabled -> "提示词插件 $raw 已停用。"
                !known.permitted -> "提示词插件 $raw 缺少 prompt 授权。"
                else -> "提示词插件 $raw 不可用。"
            },
        )
    }

    /** 写回 `dsh_prompt` 的规范值：内置实现写 `native`，第三方写 `插件id#实现id`。 */
    fun prefOf(prompt: ResolvedPrompt): String =
        if (prompt.builtin || prompt.pluginId.isNullOrBlank()) "native" else "${prompt.pluginId}#${prompt.promptId}"
}

private fun PromptCandidate.resolved(): ResolvedPrompt = ResolvedPrompt(
    promptId = promptId,
    label = label,
    pluginId = pluginId,
    builtin = builtin,
)
