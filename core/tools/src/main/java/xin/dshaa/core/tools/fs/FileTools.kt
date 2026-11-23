package xin.dshaa.core.tools.fs

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult
import xin.dshaa.core.tools.resolvePath
import java.io.File
import java.io.FileOutputStream

/**
 * `edit` 单侧文本（`old_string` / `new_string`）超过 2MB 时不再传两份大文本：
 * 改用 `write` 的 `append` 分块重写文件（docs/187 §8.4.3）。`write` / `edit` 的硬上限
 * （16MB）在 `core:engine` 的 `ToolInputGuard`，这里只管 edit 的「换个工具更省」阈值。
 */
private const val EDIT_LARGE_STRING_LIMIT_BYTES = 2 * 1024 * 1024

private fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

private fun JsonObject.str(key: String): String? = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
private fun JsonObject.int(key: String): Int? = runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()
private fun JsonObject.bool(key: String): Boolean? = runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()

private fun countOccurrences(text: String, sub: String): Int {
    if (sub.isEmpty()) return 0
    var index = 0
    var count = 0
    while (true) {
        val at = text.indexOf(sub, index)
        if (at < 0) return count
        count++
        index = at + sub.length
    }
}

private fun globToRegex(glob: String): Regex {
    val sb = StringBuilder("^")
    var i = 0
    while (i < glob.length) {
        val c = glob[i]
        when {
            c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                sb.append(".*")
                i++
            }
            c == '*' -> sb.append("[^/]*")
            c == '?' -> sb.append("[^/]")
            c == '\\' -> sb.append('/')
            else -> sb.append(Regex.escape(c.toString()))
        }
        i++
    }
    sb.append('$')
    return Regex(sb.toString())
}

/** 与上游 `dsh-tool-fs` 同一输出格式（OpenCode 风格行号 + <path>/<type>/<content> 信封）。 */
class ReadTool : DshTool {
    override val name = "read"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("file_path") ?: return ToolResult.err("missing file_path")
        val offset = (args.int("offset") ?: 1).coerceAtLeast(1)
        val limit = (args.int("limit") ?: 2000).coerceAtLeast(1)
        val file = resolvePath(path, ctx)
        if (!file.isFile) return ToolResult.err("File not found: $path")
        val text = runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse { return ToolResult.err("read failed: ${it.message}") }
        val rawLines = text.split('\n').map { it.removeSuffix("\r") }
        val lines = if (rawLines.isNotEmpty() && rawLines.last().isEmpty()) rawLines.dropLast(1) else rawLines
        if (offset > lines.size && !(lines.isEmpty() && offset == 1)) {
            return ToolResult.err("offset $offset is out of range for \"$path\" (${lines.size} lines)")
        }
        val selected = ArrayList<Pair<Int, String>>()
        var bytes = 0
        var capped = false
        for (number in offset..lines.size) {
            if (selected.size >= limit) break
            var line = lines[number - 1]
            if (line.length > 2000) line = line.substring(0, 2000) + "... (line truncated to 2000 chars)"
            val add = line.toByteArray(Charsets.UTF_8).size + if (selected.isEmpty()) 0 else 1
            if (bytes + add > 50 * 1024) {
                capped = true
                break
            }
            bytes += add
            selected += number to line
        }
        val endLine = selected.lastOrNull()?.first ?: (offset - 1)
        val footer = when {
            capped -> "(Output capped. Showing lines $offset-$endLine. Use offset=${endLine + 1} to continue.)"
            endLine < lines.size -> "(Showing lines $offset-$endLine of ${lines.size}. Use offset=${endLine + 1} to continue.)"
            else -> "(End of file - total ${lines.size} lines)"
        }
        val body = if (selected.isEmpty()) footer
        else selected.joinToString("\n") { "${it.first}: ${it.second}" } + "\n\n" + footer
        return ToolResult.ok("<path>$path</path>\n<type>file</type>\n<content>\n$body\n</content>")
    }
}

class WriteTool : DshTool {
    override val name = "write"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("file_path") ?: return ToolResult.err("missing file_path")
        val content = args.str("content") ?: return ToolResult.err("missing content")
        // docs/187 §8.4：append 默认 false（覆盖写），true 时追加；追加到不存在的文件要创建它。
        val append = args.bool("append") ?: false
        val file = resolvePath(path, ctx)
        val existed = file.exists()
        val bytes = content.toByteArray(Charsets.UTF_8)
        runCatching {
            file.parentFile?.mkdirs()
            FileOutputStream(file, append).use { out -> out.write(bytes) }
        }.getOrElse { return ToolResult.err("write failed: ${it.message}") }
        // 写后校验：内容非空但文件仍是 0 字节 = 写入没落地（磁盘满/路径被替换等），不能报成功。
        if (bytes.isNotEmpty() && file.length() == 0L) {
            return ToolResult.err("write failed: wrote ${bytes.size} bytes but \"$path\" is 0 bytes")
        }
        val verb = when {
            append && existed -> "Appended to file"
            existed -> "Updated file"
            else -> "Created file"
        }
        return ToolResult.ok(
            "<path>$path</path>\n<type>file</type>\n<content>\n$verb\n</content>",
        )
    }
}

class EditTool : DshTool {
    override val name = "edit"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.str("file_path") ?: return ToolResult.err("missing file_path")
        val old = args.str("old_string") ?: return ToolResult.err("missing old_string")
        val new = args.str("new_string") ?: return ToolResult.err("missing new_string")
        val replaceAll = args.bool("replace_all") ?: false
        // docs/187 §8.4.3：edit 一次要传输 old+new 两份文本；单侧 >2MB 时引导改用 write append
        // 分块重写文件（16MB 的守卫上限仍然生效，这里只是更省传输量的建议/拒绝）。
        val oldBytes = utf8Size(old)
        val newBytes = utf8Size(new)
        if (oldBytes > EDIT_LARGE_STRING_LIMIT_BYTES || newBytes > EDIT_LARGE_STRING_LIMIT_BYTES) {
            return ToolResult.err(
                "old_string / new_string too large ($oldBytes / $newBytes bytes; per-side limit " +
                    "$EDIT_LARGE_STRING_LIMIT_BYTES bytes): edit transfers two large texts in one call. " +
                    "Use write with append to rewrite the file in chunks: first call append=false to create " +
                    "the file, then append=true for subsequent chunks.",
            )
        }
        val file = resolvePath(path, ctx)
        if (!file.isFile) return ToolResult.err("File not found: $path")
        val text = runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse { return ToolResult.err("read failed: ${it.message}") }
        val count = countOccurrences(text, old)
        if (count == 0) return ToolResult.err("old_string not found in $path")
        if (!replaceAll && count > 1) {
            return ToolResult.err("old_string appears $count times in $path; use replace_all or a longer match")
        }
        val updated = if (replaceAll) text.replace(old, new) else text.replaceFirst(old, new)
        runCatching { file.writeText(updated, Charsets.UTF_8) }
            .getOrElse { return ToolResult.err("write failed: ${it.message}") }
        return ToolResult.ok(
            if (replaceAll) "The file $path has been updated. All occurrences were successfully replaced."
            else "The file $path has been updated successfully.",
        )
    }
}

class GlobTool : DshTool {
    override val name = "glob"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val pattern = args.str("pattern") ?: return ToolResult.err("missing pattern")
        val root = args.str("path")?.let { resolvePath(it, ctx) } ?: ctx.workspace
        val regex = globToRegex(pattern)
        val out = ArrayList<String>()
        root.walkTopDown()
            .onEnter { it.name != ".git" && it.name != "node_modules" && it.name != "build" }
            .filter { it.isFile }
            .forEach { f ->
                val rel = f.relativeTo(root).path.replace('\\', '/')
                if (regex.matches(rel)) out += rel
            }
        val capped = out.size > 200
        val shown = out.take(200)
        return ToolResult.ok(
            if (shown.isEmpty()) "No files matched"
            else shown.joinToString("\n") + if (capped) "\n... (truncated to 200 files)" else "",
        )
    }
}

class GrepTool : DshTool {
    override val name = "grep"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val pattern = args.str("pattern") ?: return ToolResult.err("missing pattern")
        val include = args.str("include")
        val root = args.str("path")?.let { resolvePath(it, ctx) } ?: ctx.workspace
        val regex = runCatching { Regex(pattern) }.getOrElse { return ToolResult.err("bad regex: ${it.message}") }
        val includeRegex = include?.let { globToRegex(it) }
        val hits = ArrayList<String>()
        var total = 0
        val files = if (root.isFile) sequenceOf(root) else root.walkTopDown()
            .onEnter { it.name != ".git" && it.name != "node_modules" && it.name != "build" }
            .filter { it.isFile && it.length() < 2_000_000 }
        for (f in files) {
            if (hits.size >= 100) break
            val rel = runCatching { f.relativeTo(root).path.replace('\\', '/') }.getOrElse { f.name }
            if (includeRegex != null && !includeRegex.matches(rel)) continue
            runCatching {
                f.useLines { lines ->
                    lines.forEachIndexed { idx, line ->
                        if (regex.containsMatchIn(line)) {
                            total++
                            if (hits.size < 100) hits += "$rel:${idx + 1}: ${line.take(300)}"
                        }
                    }
                }
            }
        }
        if (hits.isEmpty()) return ToolResult.ok("No matches found")
        val suffix = if (total > hits.size) "\n... ($total matches, showing first ${hits.size})" else ""
        return ToolResult.ok(hits.joinToString("\n") + suffix)
    }
}
