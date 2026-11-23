package xin.dshaa.ui.shellapi

/**
 * M87-F（docs/181 §3.1 第 7 条）：插件页「扩展点」页签的一行 —— 按插件聚合。
 *
 * 只展示宿主**已经拿到**的注册元数据与本次进程的命中统计；不改插件行为、不猜测。
 * 中间件链顺序 = 插件清单顺序（[order]，1 起），链内顺序按各扩展点的注册先后。
 */
data class PluginExtensionUi(
    val pluginId: String,
    val pluginName: String,
    val enabled: Boolean,
    /** 1-based；与 `plugins.json` 清单顺序一致（工具/请求/存储/事件链都按它排）。 */
    val order: Int,
    val chains: List<PluginExtensionChainUi>,
)

/**
 * 一条扩展点链路/注册项。
 *
 * [status] 是宿主视角的结论：生效 / 缺授权（附权限名）/ 未常驻 / 已停用 / 总开关已关。
 * [stats] 是本次进程的命中统计（`命中 N · 拒绝 N · 异常 N · 最近 Nms`，工具改写附前后哈希）；
 * 没有统计时为空串，不假装有数据。
 */
data class PluginExtensionChainUi(
    val kind: String,
    val name: String,
    val status: String,
    val stats: String = "",
    val detail: String? = null,
)

/**
 * docs/185 P2：Node 宿主里加载成功/失败的一个插件。
 *
 * [tools] 是这个插件注册进 `ctx.tools` 的工具名（宿主按加载前后差集算的），
 * 用户/模型看到的注册名是 `node_<tools[i]>`。P4 起带上清单里的 id/name/version，
 * 插件页可以不只显示一个模块路径。
 */
data class NodeHostPluginUi(
    val module: String,
    val ok: Boolean,
    val error: String? = null,
    val tools: List<String> = emptyList(),
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val hasClient: Boolean = false,
)

/**
 * docs/188 P4：Node 宿主状态的人话版本。
 *
 * [code] 是稳定的小写状态码（`off` / `idle` / `extracting` / `starting` / `ready` /
 * `failed` / `stopped`），[title] 里同时给出大写状态与中文（例如「已就绪（READY）」）。
 * 启动中/失败/缺什么必须可见：失败时 [detail] 是原始原因，[hint] 是可操作的一句话。
 */
data class NodeHostStatusUi(
    val code: String,
    val title: String,
    val detail: String = "",
    val hint: String = "",
) {
    val failed: Boolean get() = code == "failed"
    val running: Boolean get() = code == "ready"
    val busy: Boolean get() = code == "extracting" || code == "starting"
}

/**
 * docs/190-C：全局 Node 启动小卡片的结构化进度（任何页面都能看见，非模态）。
 *
 * [phase] 是稳定小写状态码：`hidden`（不显示）/ `extracting` / `starting` / `restarting` /
 * `ready`（1s 后自动隐藏）/ `failed`（保持，直到用户收起或重试）。
 * [startedAtMs] 供界面自己按秒刷新「已用 N 秒」；[elapsedMs] 是发快照那一刻的值（日志/测试用）。
 */
data class NodeBootProgressUi(
    val phase: String = "hidden",
    val percent: Int = 0,
    val detail: String = "",
    val elapsedMs: Long = 0,
    val error: String = "",
    val startedAtMs: Long = 0,
    val attempt: Int = 0,
    val visible: Boolean = false,
) {
    val busy: Boolean get() = phase == "extracting" || phase == "starting" || phase == "restarting"
    val ready: Boolean get() = phase == "ready"
    val failed: Boolean get() = phase == "failed"
}

/** docs/188 P4：Node 清单里已登记的一个插件（宿主关着/没加载时也要看得见）。 */
data class NodeHostManifestUi(
    val id: String,
    val name: String,
    val version: String,
    val module: String,
    val hasClient: Boolean = false,
)

/**
 * docs/188 P4：一次待确认的插件导入。
 *
 * [ownership] 是系统按 [signals] 识别出的默认归属（`native` / `node`）；用户在确认对话框里
 * 可以改选。已经装在另一侧时 [nativeInstalled]/[nodeInstalled] 为真，确认迁移会先删掉旧侧
 * （重置配置），同一个 id 不会双装。
 */
data class PluginImportDraftUi(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val ownership: String,
    val signals: List<String> = emptyList(),
    val hasClient: Boolean = false,
    val sourceName: String = "",
    val nativeInstalled: Boolean = false,
    val nodeInstalled: Boolean = false,
    val limitsNative: String = "",
    val limitsNode: String = "",
)
