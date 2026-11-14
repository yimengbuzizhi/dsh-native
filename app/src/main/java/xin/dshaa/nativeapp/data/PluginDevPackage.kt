package xin.dshaa.nativeapp.data

import xin.dshaa.core.plugins.PluginPackage
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Directory imports use the same archive parser and limits as SAF imports. */
internal object PluginDevPackage {
    fun pack(directory: File, archive: File) {
        val source = directory.canonicalFile
        require(source.isDirectory) { "插件目录不存在" }
        require(archive.canonicalFile != source && !archive.canonicalPath.startsWith(source.path + File.separator)) {
            "临时压缩包不能放在插件源目录内"
        }
        var count = 0
        var visited = 0
        var total = 0L
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            fun visit(dir: File) {
                val children = requireNotNull(dir.listFiles()) { "读不了目录：${dir.name}" }
                children.sortedBy { it.name }.forEach { file ->
                    require(++visited <= PluginPackage.MAX_ENTRIES * 2) { "插件目录条目过多" }
                    val canonical = file.canonicalFile
                    // Reject all symlinks, including links within the package (cycles/aliases).
                    require(!java.nio.file.Files.isSymbolicLink(file.toPath()) && canonical.path == file.absoluteFile.normalize().path) { "插件目录不能包含符号链接：${file.name}" }
                    require(canonical.path.startsWith(source.path + File.separator)) { "插件路径逃逸" }
                    val relative = canonical.relativeTo(source).invariantSeparatorsPath
                    if (file.name in setOf(".git", "node_modules", ".DS_Store", "__MACOSX")) return@forEach
                    if (file.isDirectory) {
                        require(relative.count { it == '/' } < 32) { "插件目录层级超过 32" }
                        visit(file)
                    } else {
                        require(file.isFile) { "不是普通文件：$relative" }
                        require(++count <= PluginPackage.MAX_ENTRIES) { "插件文件数超过 ${PluginPackage.MAX_ENTRIES}" }
                        require(file.length() <= PluginPackage.MAX_ENTRY_BYTES) { "单个文件超过 4MB：$relative" }
                        zip.putNextEntry(ZipEntry(requireNotNull(PluginPackage.normalizePath(relative))))
                        var size = 0L
                        file.inputStream().buffered().use { input ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                size += n
                                total += n
                                require(size <= PluginPackage.MAX_ENTRY_BYTES) { "单个文件超过 4MB：$relative" }
                                require(total <= PluginPackage.MAX_TOTAL_BYTES) { "插件总大小超过 32MB" }
                                zip.write(buffer, 0, n)
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            visit(source)
        }
    }
}
