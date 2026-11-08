package xin.dshaa.core.attachment

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xin.dshaa.core.model.ImageRef
import xin.dshaa.core.tools.AttachmentHost
import java.io.File
import java.security.MessageDigest

/**
 * 内容寻址的本地附件存储（对齐上游 `dsh-attachment-local` 的存放方式）：
 *
 * ```
 * <root>/objects/<sha256 前两位>/<sha256 全hex>
 * attachmentId = "sha256:<hex>"
 * ```
 *
 * 同一张图（归一化后字节相同）只落一份盘；会话 JSONL 只记 `attachmentId`，附件不参与会话格式。
 * 位图解码/归一化在 [ImageNormalizer]（Android），本类只用 `java.io`，可在 JVM 单测里跑。
 */
class AttachmentStore(private val root: File) : AttachmentHost {

    override suspend fun saveImage(data: ByteArray, mediaType: String, name: String?): ImageRef =
        withContext(Dispatchers.IO) {
            val normalized = ImageNormalizer.normalize(data, mediaType)
            val hex = saveNormalized(normalized.data)
            ImageRef(
                attachmentId = "sha256:$hex",
                mediaType = normalized.mediaType,
                bytes = normalized.data.size,
                width = normalized.width,
                height = normalized.height,
                name = name,
                originalWidth = normalized.originalWidth,
                originalHeight = normalized.originalHeight,
            )
        }

    /** 写一份已归一化的字节（幂等），返回 sha256 hex。 */
    fun saveNormalized(data: ByteArray): String {
        val hex = sha256Hex(data)
        val file = objectFile(hex)
        if (file.exists() && file.length() == data.size.toLong()) return hex
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "$hex.tmp-${System.nanoTime()}")
        try {
            tmp.writeBytes(data)
            if (!tmp.renameTo(file)) {
                // 目标已存在时 rename 在部分文件系统上失败：内容相同就直接复用
                if (!file.exists()) {
                    file.writeBytes(data)
                }
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return hex
    }

    /** 读回一张图；id 非法或文件不存在返回 null。 */
    fun readImage(attachmentId: String): ByteArray? {
        val file = imageFile(attachmentId) ?: return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    /** 附件对象文件；id 非法或文件不存在返回 null。 */
    fun imageFile(attachmentId: String): File? {
        val hex = hexOf(attachmentId) ?: return null
        val file = objectFile(hex)
        return if (file.isFile) file else null
    }

    fun objectFile(hex: String): File = File(File(root, "objects"), hex.substring(0, 2)).resolve(hex)

    companion object {
        private val ID_PATTERN = Regex("^sha256:([a-f0-9]{64})$")

        fun hexOf(attachmentId: String): String? = ID_PATTERN.find(attachmentId)?.groupValues?.get(1)

        fun sha256Hex(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(data)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
