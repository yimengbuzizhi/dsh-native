package xin.dshaa.core.session

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * v4 会话的压缩细节（2026-11-06 实测）：
 * 文件不是「一个 zstd 帧装整个 JSONL」，而是**每条事件一个独立帧顺序拼接**。
 * 因此读取必须逐帧解压到 EOF；写入则是每条事件单独压一帧再 append。
 */
object ZstdFrames {

    /** 解压由多个 zstd frame 拼接成的字节流。 */
    fun decompressAll(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream(bytes.size * 3)
        var offset = 0
        while (offset < bytes.size) {
            val input = ByteArrayInputStream(bytes, offset, bytes.size - offset)
            var consumed = 0
            ZstdInputStream(input).use { zis ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = zis.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                consumed = (bytes.size - offset) - input.available()
            }
            if (consumed <= 0) break
            offset += consumed
        }
        return out.toByteArray()
    }

    /** 单条内容压成一帧（写入用）。 */
    fun compressFrame(text: String, level: Int = 3): ByteArray =
        Zstd.compress(text.toByteArray(Charsets.UTF_8), level)

    /** 解开 `bytes[0, length)` 这一帧（[length] 必须是 [firstFrameLength] 的结果）。 */
    fun decompressFrame(bytes: ByteArray, length: Int): ByteArray {
        require(length in 1..bytes.size) { "frame length out of range" }
        return ZstdInputStream(ByteArrayInputStream(bytes, 0, length)).use { it.readBytes() }
    }

    /**
     * 第一帧的**压缩后**长度（用于只读会话头，不碰后面的事件帧）。
     *
     * v4 是「每条事件一帧」顺序拼接，标准 zstd 帧格式：
     * magic(4) + Frame_Header_Descriptor(1) + [Window_Descriptor] + [Dict_ID] + [Frame_Content_Size]
     * + 若干 Block_Header(3) + block data + [checksum(4)]。
     * 不是 zstd / 截断 / 保留块类型 → null（调用方保守跳过，不动文件）。
     */
    fun firstFrameLength(bytes: ByteArray): Int? {
        if (bytes.size < 6) return null
        // magic 0xFD2FB528 的小端字节序
        if (bytes[0] != 0x28.toByte() || bytes[1] != 0xB5.toByte() ||
            bytes[2] != 0x2F.toByte() || bytes[3] != 0xFD.toByte()
        ) {
            return null
        }
        var pos = 4
        val descriptor = bytes[pos].toInt() and 0xFF
        pos++
        val fcsFlag = (descriptor ushr 6) and 0x3
        val singleSegment = (descriptor ushr 5) and 0x1
        val checksum = (descriptor ushr 2) and 0x1
        val dictFlag = descriptor and 0x3

        if (singleSegment == 0) pos++ // Window_Descriptor
        pos += when (dictFlag) {
            0 -> 0
            1 -> 1
            2 -> 2
            else -> 4
        }
        pos += when (fcsFlag) {
            0 -> if (singleSegment == 1) 1 else 0
            1 -> 2
            2 -> 4
            else -> 8
        }
        if (pos > bytes.size) return null

        while (true) {
            if (pos + 3 > bytes.size) return null
            val header = (bytes[pos].toInt() and 0xFF) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 16)
            pos += 3
            val lastBlock = (header and 0x1) == 1
            val blockType = (header ushr 1) and 0x3
            val blockSize = header ushr 3
            if (blockType == 3) return null // Reserved
            val payload = when (blockType) {
                0 -> blockSize // Raw
                1 -> 1 // RLE
                else -> blockSize // Compressed
            }
            if (payload < 0 || pos + payload > bytes.size) return null
            pos += payload
            if (lastBlock) break
        }
        if (checksum == 1) pos += 4
        return if (pos <= bytes.size) pos else null
    }
}
