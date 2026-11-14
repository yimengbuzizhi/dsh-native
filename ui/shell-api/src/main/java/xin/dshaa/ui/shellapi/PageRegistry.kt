package xin.dshaa.ui.shellapi

/**
 * M85-C3-4（`page/v1` 完整版）：页面来源。
 *
 * - [Shell]：内置界面插件声明并渲染的页面（[ShellPage]）；
 * - [Plugin]：M84-G 插件整页（`page:true`，route id 与壳页同名 = 覆盖）；渲染仍由宿主
 *   [NativeShellHostUi.PluginPage] 这个页面槽提供，壳不 import `pluginui`。
 */
enum class PageOrigin { Shell, Plugin }

/**
 * M85-C3-4：**统一页面表的一项**（壳声明页 + 插件整页）。
 *
 * 这是 `page/v1` 的唯一数据结构：抽屉、路由解析、调试 dump 都只读这张表；
 * 壳页怎么画仍由壳自己的 dispatch 决定，插件页怎么画由宿主页面槽决定。
 */
data class RegisteredPage(
    val id: String,
    val title: String,
    val level: PageLevel,
    val origin: PageOrigin,
    /** 插件整页的插件 id/名字/route id（壳页为空）。 */
    val pluginId: String? = null,
    val pluginName: String? = null,
    val routeId: String? = null,
    /** 插件整页的渲染目标（record+route），交给 [NativeShellHostUi.PluginPage]；壳页为 null。 */
    val pluginTarget: PluginPageTarget? = null,
    /** true = 插件整页覆盖了同 id 的壳页；false 且 [isPlugin] = 插件新增的独立页（进抽屉）。 */
    val overridesShellPage: Boolean = false,
) {
    val isPlugin: Boolean get() = origin == PageOrigin.Plugin
}

/**
 * M85-C3-4：`page/v1` 注册表（M85-C2 在宿主侧的 `ShellPageRegistry` 现在搬进契约模块）。
 *
 * 两个来源并成一张表：
 * 1. 壳声明页（[declared]，来自 [NativeShells.pages]）；
 * 2. M84-G 插件整页（[PluginPageEntry]；`page:true` + 启用 + 有 `dsh.client`）。
 *
 * 插件 route id 与壳页同名时**覆盖**那一项（M84-G 的替换语义），否则追加为独立插件页。
 * 本对象是纯函数，不持有状态：壳和宿主各自传入自己看到的插件清单，结果一致。
 */
object ShellPageRegistry {

    /** 已注册壳声明的全部页面（顺序 = 壳声明顺序；调试 dump / 校验用）。 */
    fun declared(): List<ShellPage> = NativeShells.pages()

    /**
     * 合并成唯一一张页面表。
     *
     * @param shellPages 当前壳声明的页面（壳内调用传自己的声明；宿主 dump 传 [declared]）。
     * @param pluginPages 宿主的 `pluginPageEntries()`（含同 id 覆盖项）。
     */
    fun table(
        shellPages: List<ShellPage> = declared(),
        pluginPages: List<PluginPageEntry> = emptyList(),
    ): List<RegisteredPage> {
        val pluginById = pluginPages.distinctBy { it.routeId }.associateBy { it.routeId }
        val merged = shellPages.map { page ->
            val plugin = pluginById[page.id]
            if (plugin == null) {
                RegisteredPage(
                    id = page.id,
                    title = page.title,
                    level = page.level,
                    origin = PageOrigin.Shell,
                )
            } else {
                RegisteredPage(
                    id = page.id,
                    title = plugin.title.ifBlank { page.title },
                    level = page.level,
                    origin = PageOrigin.Plugin,
                    pluginId = plugin.pluginId,
                    pluginName = plugin.pluginName,
                    routeId = plugin.routeId,
                    pluginTarget = plugin.target,
                    overridesShellPage = true,
                )
            }
        }
        val shellIds = shellPages.map { it.id }.toSet()
        val extra = pluginPages.asSequence()
            .filter { it.routeId !in shellIds }
            .distinctBy { it.routeId }
            .map { plugin ->
                RegisteredPage(
                    id = plugin.routeId,
                    title = plugin.title,
                    level = PageLevel.Primary,
                    origin = PageOrigin.Plugin,
                    pluginId = plugin.pluginId,
                    pluginName = plugin.pluginName,
                    routeId = plugin.routeId,
                    pluginTarget = plugin.target,
                )
            }
            .toList()
        return merged + extra
    }

    /** 抽屉「插件页面」段：只列独立插件页，同 id 覆盖项走内置页入口（M84-K 语义）。 */
    fun drawerPluginPages(
        shellPages: List<ShellPage> = declared(),
        pluginPages: List<PluginPageEntry> = emptyList(),
    ): List<RegisteredPage> =
        table(shellPages, pluginPages).filter { it.isPlugin && !it.overridesShellPage }

    /**
     * M84-G 路由解析：`pageId`/`subId` 命中插件整页时由插件提供（`pageId` 优先，和旧
     * `pluginPageFor` 一致；二级页覆盖落到 `subId`）。
     */
    fun resolve(
        shellPages: List<ShellPage> = declared(),
        pluginPages: List<PluginPageEntry> = emptyList(),
        pageId: String?,
        subId: String?,
    ): RegisteredPage? {
        val table = table(shellPages, pluginPages)
        val hit = listOfNotNull(pageId, subId)
            .filter { it.isNotBlank() }
            .firstOrNull { id -> table.any { it.isPlugin && it.id == id } }
            ?: return null
        return table.firstOrNull { it.isPlugin && it.id == hit }
    }

    /** 壳声明的页面 id 必须和宿主的 id 词表（[BuiltinPageIds]，插件归属用）完全一致。 */
    fun catalogMatch(): Boolean = declared().map { it.id } == BuiltinPageIds.all

    /** 内置插件里声明了、但没有壳提供渲染的页面 id（拼错时调试 dump 能看出来）。 */
    fun unknownPluginScreens(pluginScreens: List<String>): List<String> =
        pluginScreens.filter { id -> declared().none { it.id == id } }.distinct()

    /** 同一个页面被多个内置插件声明（关一个另一个还开着 → 语义不清）。 */
    fun duplicatePluginScreens(pluginScreens: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return pluginScreens.filter { !seen.add(it) }
    }

    /** 没有插件归属的核心页（插件 / 设置——空壳也能进）。 */
    fun unownedPages(ownedIds: Set<String>): List<String> =
        declared().filter { it.id !in ownedIds }.map { it.id }
}
