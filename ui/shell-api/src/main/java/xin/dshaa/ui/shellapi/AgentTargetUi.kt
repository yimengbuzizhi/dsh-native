package xin.dshaa.ui.shellapi

/**
 * M85-D：插件页/聊天页展示用的**回合插件**条目（`agent/v1`）。
 *
 * [pluginId] 为空 = 内置 `builtin.agent.dsh`（TurnEngine 的 native 实现）。
 * [enabled] / [permitted] 是宿主现算的开关与 `agent` 授权；[available] 表示「现在选中它能不能跑」。
 * 壳只展示与发选择命令，不理解回合循环本身。
 */
data class AgentTargetUi(
    val agentId: String,
    val label: String,
    val pluginId: String? = null,
    val provider: String = "内置",
    val builtin: Boolean = false,
    val enabled: Boolean = true,
    val permitted: Boolean = true,
    val active: Boolean = false,
    /** 停用/缺授权的原因（null = 可用）。 */
    val unavailableReason: String? = null,
) {
    fun selectionValue(): String = if (builtin) "native" else pluginId?.let { "$it#$agentId" } ?: agentId
}
