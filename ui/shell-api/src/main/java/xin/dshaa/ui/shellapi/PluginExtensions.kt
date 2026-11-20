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
 * 用户/模型看到的注册名是 `node_<tools[i]>`。
 */
data class NodeHostPluginUi(
    val module: String,
    val ok: Boolean,
    val error: String? = null,
    val tools: List<String> = emptyList(),
)
