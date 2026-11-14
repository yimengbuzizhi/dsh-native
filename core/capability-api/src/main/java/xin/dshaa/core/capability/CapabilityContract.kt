package xin.dshaa.core.capability

// M85-F：capability/v1 —— 能力角色的契约 + 选择规则。
//
// 设计基线是 docs/144 第 3 节、第 8 节 M85-F：
// - 能力 = 一个稳定 id（`root` / `nfc` / `shizuku` / `adb-shell` / `notify` / `clipboard.read` …）
//   加一个 `invoke(id, args)` 的提供方；
// - **宿主不写死任何特权能力**：root/NFC/Shizuku/adb-shell 只能由插件提供（内置或第三方）；
//   宿主负责登记、权限门（`capability` 授权）、超时、把失败如实报成「不可用」；
// - 内置只登记骨架平台原语（通知/剪贴板/分享），不是特权通道；
// - 同一 id 有多个提供方时，**启用的插件优先于内置**（第三方同 id 覆盖内置，演示能力可被换），
//   插件之间按登记顺序先到先得；被选中的提供方停用/缺授权就回落到下一个可用提供方。
//
// 本文件是纯 Kotlin（无 android / 无 app 依赖），选择规则在 JVM 单测里锁死。

/** 契约版本（插件页 / 调试 dump 里展示；演进时宿主同时支持旧版）。 */
const val CAPABILITY_CONTRACT: String = "capability/v1"

/** 常驻 JS 运行时里的 dispatch 方法名（宿主 → 插件提供方）。 */
const val CAPABILITY_INVOKE_METHOD: String = "capability.invoke"

/** 内置平台能力的提供方名（不是插件，宿主直接执行）。 */
const val BUILTIN_CAPABILITY_PROVIDER: String = "builtin.capability.platform"

/** 能力 id 的合法形状：短、可读、带命名空间也好（`nfc` / `clipboard.read` / `vendor:foo`）。 */
private val CAPABILITY_ID = Regex("""^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$""")

/** 规范化一个能力 id；不合法返回 null（不猜、不截断成另一个 id）。 */
fun normalizeCapabilityId(raw: String?): String? {
    val clean = raw?.trim().orEmpty()
    if (!CAPABILITY_ID.matches(clean)) return null
    return clean
}

/** 一个能力提供方声明的元数据（内置登记或插件 `ctx.capability.register`）。 */
data class CapabilityDescriptor(
    val id: String,
    val label: String = "",
    val description: String = "",
    /** 展示用分类：`device` / `system` / `privileged` / `demo` …（宿主不解释，只给 UI 分组）。 */
    val kind: String = "",
)

/**
 * 一个候选提供方。
 *
 * - [pluginId] 为空 = 内置平台能力（宿主直接执行）；
 * - [enabled] / [permitted] 是宿主在解析时现算的插件开关与 `capability` 授权；
 * - 内置提供方默认永远可用（骨架平台原语）。
 */
data class CapabilityCandidate(
    val id: String,
    val label: String = "",
    val description: String = "",
    val kind: String = "",
    val pluginId: String? = null,
    val builtin: Boolean = false,
    val enabled: Boolean = true,
    val permitted: Boolean = true,
)

/** 解析出来的提供方。 */
data class ResolvedCapability(
    val id: String,
    val label: String,
    val description: String = "",
    val kind: String = "",
    val pluginId: String? = null,
    val builtin: Boolean = false,
)

/** 解析结果：有可用提供方，或如实说明为什么不可用。 */
sealed interface CapabilityLookup {
    data class Found(val capability: ResolvedCapability) : CapabilityLookup

    data class Unavailable(val reason: String) : CapabilityLookup
}

/**
 * 能力解析：给定 id 与全部候选，选「启用的插件提供方」优先，其次内置。
 *
 * 注意这里**没有唯一 active 角色**：每个能力各自解析（nfc 可以由 A 插件提供、clipboard 由内置提供）。
 */
object CapabilityRouting {

    fun resolve(id: String?, candidates: List<CapabilityCandidate>): CapabilityLookup {
        val clean = normalizeCapabilityId(id)
            ?: return CapabilityLookup.Unavailable("能力 id 不合法：${id?.take(80).orEmpty()}")
        val sameId = candidates.filter { it.id.equals(clean, true) }
        if (sameId.isEmpty()) {
            return CapabilityLookup.Unavailable(
                "能力 $clean 不可用：没有提供方（没有启用且具备 capability 授权的插件，也不是内置能力）。",
            )
        }
        // 插件优先（同 id 覆盖内置）；同一档里按登记顺序先到先得。
        val plugin = sameId.firstOrNull { !it.builtin && it.enabled && it.permitted }
        val builtin = sameId.firstOrNull { it.builtin && it.enabled }
        val chosen = plugin ?: builtin
        if (chosen != null) return CapabilityLookup.Found(chosen.resolved())
        val known = sameId.first()
        val reason = when {
            known.builtin -> "能力 $clean 的内置提供方已停用。"
            !known.enabled -> "能力 $clean 的提供方插件 ${known.pluginId.orEmpty()} 已停用。"
            !known.permitted -> "能力 $clean 的提供方插件 ${known.pluginId.orEmpty()} 缺少 capability 授权。"
            else -> "能力 $clean 不可用。"
        }
        return CapabilityLookup.Unavailable(reason)
    }

    /** 能力清单（按 id 去重，插件优先展示），用于插件页 / `dsh_capability_list`。 */
    fun catalog(candidates: List<CapabilityCandidate>): List<CapabilityCandidate> {
        val out = ArrayList<CapabilityCandidate>()
        val seen = HashSet<String>()
        candidates.forEach { candidate ->
            if (!seen.add(candidate.id.lowercase())) return@forEach
            out += candidate
        }
        return out
    }
}

private fun CapabilityCandidate.resolved(): ResolvedCapability = ResolvedCapability(
    id = id,
    label = label.ifBlank { id },
    description = description,
    kind = kind,
    pluginId = pluginId,
    builtin = builtin,
)
