package xin.dshaa.core.agent

// M85-D：agent/v1 —— 回合（agent）角色的契约 + 选择规则。
//
// 设计基线是 docs/144 的第 3 节与第 6 节：宿主只认「一个回合插件」这一角色，
// 不关心它是 Kotlin 内置实现还是导入的 JS 插件。
// - 内置 builtin.agent.dsh = 现有 TurnEngine 的 native 实现（行为与 M84 一致）；
// - 第三方插件用 ctx.agent.register({ id, label, run }) 声明，常驻 runtime 里 dispatch
//   agent.run({ turnId, sessionId, input, state }, ctx) -> { stopReason, text? }。
// 两条路共用同一份「启用 / agent 授权 / 同 id 覆盖 / 停用 → 安全模式」判定。
// 本文件是纯 Kotlin（无 android / 无 app 依赖），选择规则可在 JVM 单测里锁死。

/** 契约版本（插件页/调试 dump 里展示；演进时宿主同时支持旧版）。 */
const val AGENT_CONTRACT: String = "agent/v1"

/** 内置回合插件（TurnEngine 的 native 实现）的稳定 id。 */
const val BUILTIN_AGENT_ID: String = "builtin.agent.dsh"

/** 常驻 JS 运行时里的 dispatch 方法名。 */
const val AGENT_RUN_METHOD: String = "agent.run"

/** 一个回合实现的元数据（插件声明或内置登记）。 */
data class AgentDescriptor(val id: String, val label: String)

/**
 * 一个可选的回合实现。
 *
 * - [pluginId] 为空 = 内置 native 实现（`builtin.agent.dsh`）；
 * - 同 id 覆盖：第三方插件声明了 `agentId = builtin.agent.dsh` 且启用 + 有 `agent` 授权时优先；
 * - [enabled] / [permitted] 是宿主在选择时现算的开关与授权（插件停用后立刻失效）。
 */
data class AgentCandidate(
    val agentId: String,
    val label: String,
    val pluginId: String? = null,
    val builtin: Boolean = false,
    val enabled: Boolean = true,
    val permitted: Boolean = true,
)

/** 已选中的回合实现（每次发消息前重新解析，避免插件停用后仍走旧引用）。 */
data class ResolvedAgent(
    val agentId: String,
    val label: String,
    val pluginId: String? = null,
    val builtin: Boolean = false,
)

/** 解析结果：要么有可用回合，要么给出「为什么没有」的安全提示文案。 */
sealed interface AgentResolution {
    data class Active(val agent: ResolvedAgent) : AgentResolution

    data class Unavailable(val reason: String) : AgentResolution
}

/**
 * `dsh_agent` 调试项 / 设置项 → 具体回合实现。
 *
 * 取值：
 * - 空、`native`、`builtin.agent.dsh`：默认回合，可被**同 id** 第三方插件接管（与 C3-5 壳同规则）；
 * - 插件 id：选该插件声明的第一个回合；
 * - `插件id#回合id`：精确选择；
 * - 回合 id：按 id 选择（第三方与内置同 id 时第三方优先）。
 *
 * 任何「选不中」都返回 [AgentResolution.Unavailable]（安全提示），**不会**悄悄退回内置实现。
 */
object AgentRouting {

    fun resolve(pref: String?, candidates: List<AgentCandidate>): AgentResolution {
        val raw = pref?.trim().orEmpty()
        // `native` = 明确要内置 native 实现（与壳一致：不给同 id 插件接管）；
        // 空 / builtin.agent.dsh = 默认角色，同 id 第三方插件优先。
        if (raw.equals("native", true)) {
            val builtin = candidates.firstOrNull {
                it.builtin && it.agentId.equals(BUILTIN_AGENT_ID, true) && it.enabled && it.permitted
            } ?: return AgentResolution.Unavailable(
                "内置回合插件 $BUILTIN_AGENT_ID 已停用；没有可用的回合实现。",
            )
            return AgentResolution.Active(builtin.resolved())
        }
        val wantsDefault = raw.isEmpty() || raw.equals(BUILTIN_AGENT_ID, true)
        if (wantsDefault) {
            val usable = candidates.filter {
                it.enabled && it.permitted && it.agentId.equals(BUILTIN_AGENT_ID, true)
            }
            if (usable.isEmpty()) {
                return AgentResolution.Unavailable(
                    "内置回合插件 $BUILTIN_AGENT_ID 已停用；没有可用的回合实现。",
                )
            }
            // 同 id 接管：第三方优先（按候选顺序），否则用内置 native。
            val chosen = usable.firstOrNull { !it.builtin } ?: usable.first()
            return AgentResolution.Active(chosen.resolved())
        }
        val target = raw.substringBefore('#').trim()
        val wantedAgentId = raw.substringAfter('#', "").trim()
        val match = candidates.firstOrNull { candidate ->
            if (!candidate.enabled || !candidate.permitted) return@firstOrNull false
            candidate.agentId.equals(target, true) ||
                (!candidate.builtin && candidate.pluginId?.equals(target, true) == true &&
                    (wantedAgentId.isEmpty() || candidate.agentId.equals(wantedAgentId, true)))
        }
        if (match != null) return AgentResolution.Active(match.resolved())
        // 同 id 覆盖的插件停用后回退内置实现（「覆盖后回退原实现」，不是安全模式）：
        // 选的是 builtin.agent.dsh 这个默认角色，第三方只是接管者。
        val wantsDefaultId = target.equals(BUILTIN_AGENT_ID, true) || wantedAgentId.equals(BUILTIN_AGENT_ID, true)
        if (wantsDefaultId) {
            val builtin = candidates.firstOrNull {
                it.builtin && it.agentId.equals(BUILTIN_AGENT_ID, true) && it.enabled && it.permitted
            }
            if (builtin != null) return AgentResolution.Active(builtin.resolved())
        }
        val known = candidates.firstOrNull {
            it.agentId.equals(target, true) || it.pluginId?.equals(target, true) == true
        }
        return AgentResolution.Unavailable(
            when {
                known == null -> "未安装回合插件 $raw（没有声明 $AGENT_CONTRACT 的启用插件）。"
                !known.enabled -> "回合插件 $raw 已停用。"
                !known.permitted -> "回合插件 $raw 缺少 agent 授权。"
                else -> "回合插件 $raw 不可用。"
            },
        )
    }

    /** 写回 `dsh_agent` 的规范值：内置实现写 `native`，第三方写 `插件id#回合id`。 */
    fun prefOf(agent: ResolvedAgent): String =
        if (agent.builtin || agent.pluginId.isNullOrBlank()) "native" else "${agent.pluginId}#${agent.agentId}"
}

private fun AgentCandidate.resolved(): ResolvedAgent = ResolvedAgent(
    agentId = agentId,
    label = label,
    pluginId = pluginId,
    builtin = builtin,
)
