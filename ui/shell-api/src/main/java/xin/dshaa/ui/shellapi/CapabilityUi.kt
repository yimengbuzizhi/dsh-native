package xin.dshaa.ui.shellapi

/**
 * M85-F：`capability/v1` 在插件页的一行。
 *
 * 与 [RoleTargetUi] 的区别：能力不是「唯一 active 角色」，每个能力 id 各自解析提供方
 * （插件优先内置），所以这里没有 `active` / `selectionValue`，只有「能不能用、谁提供」。
 */
data class CapabilityUi(
    /** 能力 id：`notify` / `clipboard.read` / `nfc` / `adb-shell` … */
    val id: String,
    val label: String,
    val description: String = "",
    /** 展示用分类（宿主不解释）。 */
    val kind: String = "",
    /** 展示用提供者：`内置` 或插件名。 */
    val provider: String = "内置",
    val pluginId: String? = null,
    val builtin: Boolean = false,
    /** 现在能不能调用（有可用提供方）。 */
    val available: Boolean = true,
    /** 被选中的提供方插件是否启用（内置恒 true）。 */
    val enabled: Boolean = true,
    /** 被选中的提供方插件是否有 capability 授权（内置恒 true）。 */
    val permitted: Boolean = true,
    /** 不可用原因（available=false 时给用户看）。 */
    val unavailableReason: String? = null,
)
