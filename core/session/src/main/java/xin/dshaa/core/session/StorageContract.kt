package xin.dshaa.core.session

// M85-E：storage/v1 —— 存储（会话仓库）角色的契约 + 选择规则。
//
// 设计基线是 docs/144 的第 3 节与第 7 节：宿主只认「一个存储插件」这一角色，
// 不关心它是 Kotlin 内置实现还是导入的 JS 插件。
// - 内置 builtin.storage.jsonl = 现有 FileSessionRepository 的 native 实现（沿用 files/sessions/*.jsonl，
//   平滑迁移、可回退）；
// - 第三方插件用 ctx.storage.register({ id, label, readOnly, list, load }) 声明只读导出器；
//   常驻 runtime 里 dispatch storage.list / storage.load，返回会话摘要 / 原始会话文档。
// 两条路共用同一份「启用 / storage 授权 / 同 id 覆盖 / 停用 → 安全提示」判定。
// 本文件是纯 Kotlin（无 android / 无 app 依赖），选择规则可在 JVM 单测里锁死。

/** 契约版本（插件页/调试 dump 里展示；演进时宿主同时支持旧版）。 */
const val STORAGE_CONTRACT: String = "storage/v1"

/** 内置存储插件（FileSessionRepository 的 native 实现）的稳定 id。 */
const val BUILTIN_STORAGE_ID: String = "builtin.storage.jsonl"

/** 常驻 JS 运行时里的两个只读 dispatch 方法名。 */
const val STORAGE_LIST_METHOD: String = "storage.list"
const val STORAGE_LOAD_METHOD: String = "storage.load"

/**
 * 一个可选的存储实现。
 *
 * - [pluginId] 为空 = 内置 native 实现（`builtin.storage.jsonl`）；
 * - 同 id 覆盖：第三方插件声明了 `storageId = builtin.storage.jsonl` 且启用 + 有 `storage` 授权时优先；
 * - [enabled] / [permitted] 是宿主在选择时现算的开关与授权（插件停用后立刻失效）；
 * - [readOnly] = 第三方只读导出器（v1 只开放 list/load，不能新建/写入）。
 */
data class StorageCandidate(
    val storageId: String,
    val label: String,
    val pluginId: String? = null,
    val builtin: Boolean = false,
    val enabled: Boolean = true,
    val permitted: Boolean = true,
    val readOnly: Boolean = false,
)

/** 已选中的存储实现（App 启动/插件开关变化后重新解析）。 */
data class ResolvedStorage(
    val storageId: String,
    val label: String,
    val pluginId: String? = null,
    val builtin: Boolean = false,
    val readOnly: Boolean = false,
)

/** 解析结果：要么有可用存储，要么给出「为什么没有」的安全提示文案。 */
sealed interface StorageResolution {
    data class Active(val storage: ResolvedStorage) : StorageResolution

    data class Unavailable(val reason: String) : StorageResolution
}

/**
 * `dsh_storage` 调试项 / 插件页选择 → 具体存储实现。
 *
 * 取值：
 * - 空、`builtin.storage.jsonl`：默认存储，可被**同 id** 第三方插件接管（与 agent/壳同规则）；
 * - `native`：强制内置文件实现（不给同 id 插件接管）；
 * - 插件 id：选该插件声明的第一个存储；
 * - `插件id#存储id` / 存储 id：精确选择；
 * - 选不中 → [StorageResolution.Unavailable]（安全提示），**不会**悄悄退回内置实现。
 */
object StorageRouting {

    fun resolve(pref: String?, candidates: List<StorageCandidate>): StorageResolution {
        val raw = pref?.trim().orEmpty()
        if (raw.equals("native", true)) {
            val builtin = candidates.firstOrNull {
                it.builtin && it.storageId.equals(BUILTIN_STORAGE_ID, true) && it.enabled && it.permitted
            } ?: return StorageResolution.Unavailable(
                "内置存储插件 $BUILTIN_STORAGE_ID 已停用；没有可用的会话存储。",
            )
            return StorageResolution.Active(builtin.resolved())
        }
        val wantsDefault = raw.isEmpty() || raw.equals(BUILTIN_STORAGE_ID, true)
        if (wantsDefault) {
            val usable = candidates.filter {
                it.enabled && it.permitted && it.storageId.equals(BUILTIN_STORAGE_ID, true)
            }
            if (usable.isEmpty()) {
                return StorageResolution.Unavailable(
                    "内置存储插件 $BUILTIN_STORAGE_ID 已停用；没有可用的会话存储。",
                )
            }
            // 同 id 接管：第三方优先（按候选顺序），否则用内置 native。
            val chosen = usable.firstOrNull { !it.builtin } ?: usable.first()
            return StorageResolution.Active(chosen.resolved())
        }
        val target = raw.substringBefore('#').trim()
        val wantedStorageId = raw.substringAfter('#', "").trim()
        val match = candidates.firstOrNull { candidate ->
            if (!candidate.enabled || !candidate.permitted) return@firstOrNull false
            candidate.storageId.equals(target, true) ||
                (!candidate.builtin && candidate.pluginId?.equals(target, true) == true &&
                    (wantedStorageId.isEmpty() || candidate.storageId.equals(wantedStorageId, true)))
        }
        if (match != null) return StorageResolution.Active(match.resolved())
        // 同 id 覆盖插件停用后回退内置实现（「覆盖后回退原实现」，不是安全模式）。
        val wantsDefaultId = target.equals(BUILTIN_STORAGE_ID, true) ||
            wantedStorageId.equals(BUILTIN_STORAGE_ID, true)
        if (wantsDefaultId) {
            val builtin = candidates.firstOrNull {
                it.builtin && it.storageId.equals(BUILTIN_STORAGE_ID, true) && it.enabled && it.permitted
            }
            if (builtin != null) return StorageResolution.Active(builtin.resolved())
        }
        val known = candidates.firstOrNull {
            it.storageId.equals(target, true) || it.pluginId?.equals(target, true) == true
        }
        return StorageResolution.Unavailable(
            when {
                known == null -> "未安装存储插件 $raw（没有声明 $STORAGE_CONTRACT 的启用插件）。"
                !known.enabled -> "存储插件 $raw 已停用。"
                !known.permitted -> "存储插件 $raw 缺少 storage 授权。"
                else -> "存储插件 $raw 不可用。"
            },
        )
    }

    /** 写回 `dsh_storage` 的规范值：内置实现写 `native`，第三方写 `插件id#存储id`。 */
    fun prefOf(storage: ResolvedStorage): String =
        if (storage.builtin || storage.pluginId.isNullOrBlank()) "native" else "${storage.pluginId}#${storage.storageId}"
}

private fun StorageCandidate.resolved(): ResolvedStorage = ResolvedStorage(
    storageId = storageId,
    label = label,
    pluginId = pluginId,
    builtin = builtin,
    readOnly = readOnly,
)
