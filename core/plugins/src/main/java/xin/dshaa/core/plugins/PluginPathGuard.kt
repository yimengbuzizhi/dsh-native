package xin.dshaa.core.plugins

import java.io.File

/** 插件越界/路径不合法时抛这个；宿主会转成 `{ok:false,error}` 给插件，不崩溃对话。 */
class PluginPathException(message: String) : Exception(message)

/**
 * 插件文件路径守卫（M28）。
 *
 * 规则和内置文件工具一致（`core:tools` 的 `resolvePath`）：先把路径 canonical 化，
 * 再要求它等于工作区根或在工作区根之下。canonical 化会展开 `..` 和软链接，所以
 * `../../data/data/...`、工作区里指向外面的软链接都逃不出去。
 *
 * Android 上同一块存储有多个别名（`/sdcard`、`/mnt/sdcard`、`/storage/self/primary`），
 * `getCanonicalPath` 不一定把它们归一到 `/storage/emulated/0`，这里补一层别名归一，
 * 避免用户从文件管理器复制来的路径被误判越界。
 */
object PluginPathGuard {

    fun resolve(root: File, rawPath: String): File {
        if (rawPath.isBlank()) throw PluginPathException("路径为空")
        if (rawPath.indexOf('\u0000') >= 0) throw PluginPathException("路径里有非法字符")

        val normalized = rawPath.trim().replace('\\', '/')
        val asFile = File(normalized)
        val candidate = if (asFile.isAbsolute) asFile else File(root, normalized)

        val canonicalRoot = canonical(root)
        val canonicalCandidate = canonical(candidate)
        val rootPath = canonicalRoot.path
        val candidatePath = canonicalCandidate.path
        val inside = candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
        if (!inside) {
            throw PluginPathException("路径不在工作区里：$rawPath（解析为 $candidatePath，根 $rootPath）")
        }
        return canonicalCandidate
    }

    private fun canonical(file: File): File {
        // 1) 逐段展开软链接。`canonicalFile` 在部分平台（如 Windows 的目录符号链接、
        //    或目标尚不存在的路径）不会展开中间软链接，工作区里的 `escape -> /外面` 就能绕过；
        //    这里用 NIO 的 isSymbolicLink 逐段解析，跨平台行为一致。
        val linked = resolveLinks(file.absoluteFile)
        // 2) 找到「最深的已存在祖先」再 canonical 化，写新文件时也能展开已存在的中间段。
        val absolute = linked.absoluteFile
        var existing: File? = absolute
        val missing = ArrayList<String>()
        while (existing != null && !existing.exists()) {
            missing.add(0, existing.name)
            existing = existing.parentFile
        }
        var base = runCatching { existing?.canonicalFile }.getOrNull() ?: absolute
        missing.forEach { base = File(base, it) }
        val path = runCatching { base.canonicalPath }.getOrElse { base.absolutePath }
        return File(normalizeAlias(path))
    }

    /** 把路径里存在的软链接逐段换成目标（相对目标按链接所在目录解析）。 */
    private fun resolveLinks(file: File): File {
        val absolute = file.absoluteFile.toPath().normalize()
        val root = absolute.root ?: return file
        var current = root
        for (part in absolute) {
            var next = current.resolve(part.toString())
            var depth = 0
            // 链接套链接时最多跟 16 层，避免环。
            while (runCatching { java.nio.file.Files.isSymbolicLink(next) }.getOrDefault(false) && depth < 16) {
                val target = runCatching { java.nio.file.Files.readSymbolicLink(next) }.getOrNull() ?: break
                next = (if (target.isAbsolute) target else current.resolve(target)).normalize()
                depth += 1
            }
            current = next
        }
        return current.toFile()
    }

    /** 只处理「整段前缀」替换，避免 `/sdcard2` 被误伤。 */
    internal fun normalizeAlias(path: String): String {
        val clean = path.replace('\\', '/')
        val aliases = listOf(
            "/storage/self/primary" to "/storage/emulated/0",
            "/mnt/sdcard" to "/storage/emulated/0",
            "/sdcard" to "/storage/emulated/0",
        )
        aliases.forEach { (alias, real) ->
            if (clean == alias) return real
            if (clean.startsWith("$alias/")) return real + clean.removePrefix(alias)
        }
        return clean
    }
}
