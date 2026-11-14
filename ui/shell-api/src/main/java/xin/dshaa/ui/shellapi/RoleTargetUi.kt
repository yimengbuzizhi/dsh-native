package xin.dshaa.ui.shellapi

/**
 * M85-E：存储 / 提示词 / 模型三个角色在插件页的统一选择行。
 *
 * 与 [AgentTargetUi] 分开是为了不把 agent 的字段（回合 id 等）硬套到三个角色上；
 * 这里的 [selectionValue] 是「点这一行要写回的规范值」，由宿主按角色现算：
 * - storage/prompt：内置 = `native`，第三方 = `插件id#实现id`；
 * - model：直接用 provider id（内置 deepseek/openai 或插件声明的 id），因为用户选择本来
 *   就是按 provider id 存在设置里的。
 */
data class RoleTargetUi(
    /** `storage` / `prompt` / `model`（插件页分组用）。 */
    val role: String,
    /** 实现 id：storageId / promptId / providerId。 */
    val implementationId: String,
    val label: String,
    val pluginId: String? = null,
    /** 展示用提供者：`内置` 或插件名。 */
    val provider: String = "内置",
    val builtin: Boolean = false,
    val enabled: Boolean = true,
    val permitted: Boolean = true,
    val active: Boolean = false,
    val readOnly: Boolean = false,
    val selectionValue: String = "",
    val unavailableReason: String? = null,
)

/** M85-E：三个角色的解析结果都由宿主写成这个结构（插件页/横幅共用）。 */
data class RoleSafetyNotice(
    val role: String,
    /** 角色中文名：存储 / 提示词 / 模型。 */
    val title: String,
    val reason: String,
)
