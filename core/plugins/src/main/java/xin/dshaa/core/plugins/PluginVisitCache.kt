package xin.dshaa.core.plugins

/**
 * `Tools.Net.visit` 的 visitKey 缓存（M68）。
 *
 * Operit 的 `visit_web` 每次成功抓取都生成一个 UUID 当 `visitKey`，把**整份结果**
 * （url/title/links/imageLinks）存进进程级 map；包里再调 `visit({visit_key, link_number})`
 * 就是「从那份结果的 links 里取第 N 条再抓一次」。所以 key 不是「最近一次」，可以多份并存。
 *
 * 为什么必须放宿主：鲸歌每次工具调用都新建 QuickJS 实例（`PluginModule` 注释写明了），
 * JS 里的 Map 跨调用必丢；只有宿主进程级缓存才能让模型在**下一次工具调用**里接着用 visitKey。
 *
 * 有意偏差（文档要写清）：
 * - 最多留 [MAX_ENTRIES] 条，LRU 淘汰（Operit 是无上限 ConcurrentHashMap，长期跑会涨内存）；
 * - 只存「链接表 + 标题 + 图片链接」，不存正文——已知调用方（various_search / google_search）
 *   从缓存里只读 url/links，正文留在当次返回值里就够；
 * - key 按插件隔离（同一个 key 字符串在不同插件间不互通），避免一个插件拿到另一个插件的抓取结果。
 */
internal object PluginVisitCache {

    data class Link(val url: String, val text: String)

    data class Entry(
        val url: String,
        val title: String,
        val links: List<Link>,
        val imageLinks: List<String>,
    )

    private const val MAX_ENTRIES = 32

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MAX_ENTRIES
    }

    fun put(pluginId: String, key: String, entry: Entry) {
        if (key.isBlank()) return
        synchronized(entries) { entries[scoped(pluginId, key)] = entry }
    }

    fun get(pluginId: String, key: String): Entry? = synchronized(entries) {
        entries[scoped(pluginId, key)]
    }

    fun size(): Int = synchronized(entries) { entries.size }

    private fun scoped(pluginId: String, key: String): String = "$pluginId\u0000$key"
}
