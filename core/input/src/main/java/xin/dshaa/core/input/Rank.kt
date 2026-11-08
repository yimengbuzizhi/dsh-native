package xin.dshaa.core.input

/**
 * `/` 菜单的候选排序（上游 `dsh-client-ui-commands` / `ui-skill` 共用）：
 * 查询按不区分大小写的有序子序列匹配名字；前缀命中排最前，同分保持注册顺序。
 * 空查询按原顺序返回。
 */
fun rankByName(entries: List<String>, query: String): List<String> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return entries
    val matches = entries.mapIndexedNotNull { index, name ->
        val lower = name.lowercase()
        val prefix = lower.startsWith(q)
        if (!prefix && !FileReferenceSearch.isSubsequence(q, lower)) return@mapIndexedNotNull null
        val score = if (prefix) 0 else 10 + FileReferenceSearch.firstMatchIndex(q, lower)
        Triple(score, index, name)
    }
    return matches.sortedWith(compareBy<Triple<Int, Int, String>> { it.first }.thenBy { it.second }).map { it.third }
}
