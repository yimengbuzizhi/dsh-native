package xin.dshaa.nativeapp.data

import java.io.File
import java.security.MessageDigest

/**
 * 随包技能（bundled skill）与设备上已安装副本的同步规则。
 *
 * 以前 `ensureInstalled` 只在文件不存在时写入：新版 APK 改了 `SKILL.md`，设备上还会一直用
 * 旧副本（插件化相关说明过期就是这么来的）。这里把规则抽成纯 JVM 逻辑，便于单测：
 *
 * - 没有副本 → 写入；
 * - 副本与当前随包内容一致 → 只补记录（幂等）；
 * - 副本仍是「上次随包写入的内容」（`.bundled-sha256` 记录一致）→ 更新；
 * - 用户改过副本（与记录不一致）→ **不覆盖**；
 * - 老安装没有记录 → 先备份 `SKILL.md.pre-bundled.bak` 再更新（一次性迁移）。
 *
 * 写入走临时文件 + rename，避免技能扫描读到半截 `SKILL.md`。
 */
internal object BundledSkillSync {

    const val MARKER = ".bundled-sha256"
    const val BACKUP = "SKILL.md.pre-bundled.bak"

    enum class Result { CREATED, UPDATED, MIGRATED_WITH_BACKUP, ALREADY_CURRENT, KEPT_USER_EDIT }

    fun sync(dir: File, bundled: ByteArray): Result {
        dir.mkdirs()
        val target = File(dir, "SKILL.md")
        val marker = File(dir, MARKER)
        val bundledHash = sha256(bundled)
        val installed = target.takeIf { it.isFile && it.length() > 0 }?.let { sha256(it.readBytes()) }
        val recorded = marker.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            installed == null -> { write(target, bundled); writeMarker(marker, bundledHash); Result.CREATED }
            installed == bundledHash -> { writeMarker(marker, bundledHash); Result.ALREADY_CURRENT }
            recorded != null && installed == recorded -> { write(target, bundled); writeMarker(marker, bundledHash); Result.UPDATED }
            recorded != null -> Result.KEPT_USER_EDIT
            else -> {
                runCatching { target.copyTo(File(dir, BACKUP), overwrite = true) }
                write(target, bundled)
                writeMarker(marker, bundledHash)
                Result.MIGRATED_WITH_BACKUP
            }
        }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun write(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }

    private fun writeMarker(marker: File, hash: String) {
        runCatching {
            val tmp = File(marker.parentFile, marker.name + ".tmp")
            tmp.writeText(hash)
            if (!tmp.renameTo(marker)) {
                marker.writeText(hash)
                tmp.delete()
            }
        }
    }
}
