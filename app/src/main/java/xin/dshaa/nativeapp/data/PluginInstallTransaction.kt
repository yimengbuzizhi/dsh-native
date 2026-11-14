package xin.dshaa.nativeapp.data

import java.io.Closeable
import java.io.File
import java.util.UUID

/** Only moves/deletes direct children of the app's plugin root; never session data. */
internal class PluginInstallTransaction(
    private val root: File,
    targetName: String,
    private val deletePackage: ((File) -> Boolean)? = null,
) : Closeable {
    private val base = root.canonicalFile
    val target: File = File(base, targetName)
    val staging: File = File(base, ".stage-${UUID.randomUUID()}")
    private val backup: File = File(base, ".backup-${UUID.randomUUID()}")

    init {
        require(targetName.isNotBlank() && targetName !in setOf(".", "..") && !targetName.contains('/') && !targetName.contains('\\')) {
            "不安全的插件目录名"
        }
        check(base.isDirectory || base.mkdirs()) { "建不了插件根目录" }
        checked(target)
        check(checked(staging).mkdir()) { "建不了插件暂存目录" }
    }

    private fun checked(file: File): File {
        require(file.parentFile == base && !java.nio.file.Files.isSymbolicLink(file.toPath()) && file.canonicalFile == file.absoluteFile.normalize()) { "插件目录路径逃逸：${file.name}" }
        return file
    }

    /** Metadata must be committed only after directory promotion; callback failure restores the old package. */
    fun commit(save: () -> Unit) {
        val hadPrevious = checked(target).exists()
        if (hadPrevious) check(target.renameTo(checked(backup))) { "旧插件目录无法备份" }
        try {
            check(checked(staging).renameTo(checked(target))) { "插件暂存目录无法替换" }
            save()
        } catch (failure: Throwable) {
            val removed = !checked(target).exists() || removeTree(target)
            val restored = removed && (!hadPrevious || checked(backup).renameTo(checked(target)))
            if (!restored) {
                val recovery = if (hadPrevious) "旧包保留在 ${backup.absolutePath}" else "新包残留在 ${target.absolutePath}"
                throw IllegalStateException("安装失败；$recovery，回滚未完成", failure)
            }
            throw failure
        }
        if (hadPrevious) removeTree(checked(backup))
    }

    private fun removeTree(directory: File): Boolean {
        val safeDirectory = checked(directory)
        deletePackage?.let { return it(safeDirectory) }
        val path = safeDirectory.toPath()
        // walkFileTree does not follow symbolic links, including links nested in an old package.
        return runCatching {
            java.nio.file.Files.walkFileTree(path, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    java.nio.file.Files.delete(file)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: java.nio.file.Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
                    if (exc != null) throw exc
                    java.nio.file.Files.delete(dir)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            })
        }.isSuccess
    }

    override fun close() {
        // Never remove backup here: a failed rollback must leave it recoverable.
        if (checked(staging).exists()) removeTree(staging)
    }
}
