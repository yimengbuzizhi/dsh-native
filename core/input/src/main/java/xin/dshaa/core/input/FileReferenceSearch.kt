package xin.dshaa.core.input

import java.io.File
import java.nio.file.Files

/** 一个 `@` 候选：相对工作区的路径（`/` 分隔）+ 是否目录。 */
data class FileCandidate(val path: String, val directory: Boolean)

/**
 * `@file` 候选发现（M21，对齐上游 `dsh-file-reference-local` 的可观察行为）。
 *
 * 上游对每个 agent 工作区建一个有界索引（默认 50000 项），裸查询做模糊排序，
 * 含 `/` 的查询直接列目标目录的条目；单次最多 20 条；不跟随目录符号链接；
 * 默认排除构建产物目录。原生在每次工具结果/轮次结束后 [invalidate]，下次查询重建——
 * Android 手机上的工作区通常很小，不做后台增量重建那套。
 */
class FileReferenceSearch(
    private val root: File,
    private val maxResults: Int = 20,
    private val maxEntries: Int = 50_000,
    private val excludedDirectories: Set<String> = DEFAULT_EXCLUDED,
) {

    private data class Entry(val path: String, val name: String, val directory: Boolean)

    @Volatile
    private var index: List<Entry>? = null

    /** 工作区变了（write/edit/bash 等）：下次查询重建索引。 */
    fun invalidate() {
        index = null
    }

    /** 查询候选；IO 线程调用（会扫盘）。 */
    fun candidates(query: String): List<FileCandidate> {
        val q = query.replace('\\', '/')
        if (q.isEmpty()) {
            return listDirectory("")
        }
        if ('/' in q) {
            val slash = q.lastIndexOf('/')
            val parent = q.substring(0, slash)
            val base = q.substring(slash + 1)
            return listDirectory(parent).filter { it.path.substringAfterLast('/').startsWith(base, ignoreCase = true) }
                .take(maxResults)
        }
        val entries = indexed()
        val scored = ArrayList<Pair<Double, Entry>>()
        for (entry in entries) {
            val score = score(entry, q) ?: continue
            scored += score to entry
        }
        scored.sortWith(compareBy<Pair<Double, Entry>> { it.first }.thenBy { it.second.path })
        return scored.take(maxResults).map { it.second.toCandidate() }
    }

    private fun listDirectory(parent: String): List<FileCandidate> {
        val dir = if (parent.isEmpty()) root else File(root, parent)
        if (!dir.isDirectory) return emptyList()
        val children = runCatching { dir.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
        return children.asSequence()
            .filter { it.name !in excludedDirectories }
            .filter { it.name.none { c -> c == '"' || c.isISOControl() } }
            .filter { !Files.isSymbolicLink(it.toPath()) }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .take(maxResults)
            .mapNotNull { file ->
                val rel = if (parent.isEmpty()) file.name else "$parent/${file.name}"
                val directory = file.isDirectory
                if (formatFileMention(rel, directory) == null) null else FileCandidate(rel, directory)
            }
            .toList()
    }

    private fun indexed(): List<Entry> {
        index?.let { return it }
        val result = ArrayList<Entry>()
        walk(root, "", result)
        index = result
        return result
    }

    private fun walk(dir: File, prefix: String, out: MutableList<Entry>) {
        if (out.size >= maxEntries) return
        val children = runCatching { dir.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
        for (file in children) {
            if (out.size >= maxEntries) return
            if (file.name in excludedDirectories) continue
            if (file.name.any { c -> c == '"' || c.isISOControl() }) continue
            if (Files.isSymbolicLink(file.toPath())) continue
            val rel = if (prefix.isEmpty()) file.name else "$prefix/${file.name}"
            val isDir = file.isDirectory
            if (formatFileMention(rel, isDir) != null) out += Entry(rel, file.name, isDir)
            if (isDir) walk(file, rel, out)
        }
    }

    /** null = 不匹配；分数越小越靠前（目录有小幅加成，与上游「目录有加成」一致）。 */
    private fun score(entry: Entry, query: String): Double? {
        val q = query.lowercase()
        val name = entry.name.lowercase()
        val path = entry.path.lowercase()
        val base = when {
            name == q -> 0.0
            name.startsWith(q) -> 10.0
            path.startsWith(q) -> 20.0
            name.contains(q) -> 30.0
            path.contains(q) -> 40.0
            isSubsequence(q, name) -> 50.0 + firstMatchIndex(q, name)
            isSubsequence(q, path) -> 80.0 + firstMatchIndex(q, path)
            else -> return null
        }
        return base + if (entry.directory) -0.5 else 0.0
    }

    private fun Entry.toCandidate(): FileCandidate = FileCandidate(path, directory)

    companion object {
        /** 上游默认排除项（本地文件引用 provider 的 `excludedDirectories`）。 */
        val DEFAULT_EXCLUDED: Set<String> = setOf(
            ".git", "node_modules", "dist", "build", "out", "coverage", "target",
            ".next", ".nuxt", ".turbo", ".venv", "__pycache__", ".pytest_cache", ".mypy_cache", ".gradle",
        )

        /** 大小写不敏感的有序子序列（`dsh-client-ui` 的 `rankByName` 同形）。 */
        fun isSubsequence(query: String, value: String): Boolean {
            if (query.isEmpty()) return true
            var i = 0
            for (c in value) {
                if (i < query.length && c == query[i]) i++
                if (i == query.length) return true
            }
            return i == query.length
        }

        /** 子序列首次命中位置（越小越靠前）。 */
        fun firstMatchIndex(query: String, value: String): Int {
            if (query.isEmpty()) return 0
            var i = 0
            var first = -1
            for ((index, c) in value.withIndex()) {
                if (i < query.length && c == query[i]) {
                    if (i == 0) first = index
                    i++
                    if (i == query.length) return first
                }
            }
            return value.length
        }
    }
}
