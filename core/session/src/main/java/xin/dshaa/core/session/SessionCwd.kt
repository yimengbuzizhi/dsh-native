package xin.dshaa.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream

/**
 * 会话 header 的 `cwd`（工作区）改写（M36，docs/60）。
 *
 * 场景：从别的设备恢复备份时，`header.cwd` 是原设备的路径（`/storage/emulated/0/xxx`、
 * `D:\...`）。路径在新设备不存在、或不可写时，聊天能看，但工具写入会失败。
 * 这里只改**第一帧里的 header 一行**：后面的每条事件帧原样按字节接回去，
 * 不重新压缩、不丢未知字段、不动消息正文。
 *
 * 只读的旧版导入会话（有 `imported.json` 侧车）由调用方跳过，保留历史工作区标签。
 */
object SessionCwd {

    /** header 帧的正常大小；超过这个值说明文件不对，保守返回 null（不冒险改写）。 */
    private const val MAX_HEADER_FRAME = 4 * 1024 * 1024

    /** 只映射正常大小的会话；超大文件（App 打开也会 OOM）不动，避免恢复时把内存打爆。 */
    private const val MAX_REMAP_BYTES = 64L * 1024 * 1024

    data class Remap(
        val changed: Boolean,
        val oldCwd: String?,
        val targetCwd: String,
        /** 非空 = 没改成功（会话保持原样，调用方可以展示/记录）。 */
        val error: String? = null,
    )

    /** 只读第一帧拿 `header.cwd`；不是标准帧/读不了返回 null。 */
    fun readCwd(file: File): String? {
        if (!file.isFile || file.length() > MAX_REMAP_BYTES) return null
        val bytes = try {
            file.readBytes()
        } catch (_: Exception) {
            return null
        }
        val frameLength = ZstdFrames.firstFrameLength(bytes) ?: return null
        if (frameLength > MAX_HEADER_FRAME) return null
        val headerLine = try {
            ZstdFrames.decompressFrame(bytes, frameLength)
                .toString(Charsets.UTF_8)
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            Json.parseToJsonElement(headerLine).jsonObject["cwd"]?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 当 [file] 的 `header.cwd` 在新设备上不可用（[canUse] 返回 false）时改成 [targetCwd]。
     *
     * - 空白 cwd（旧版/异常会话）和已经可用的 cwd 不动；
     * - 改写是原子的：先写同目录临时文件，再 rename 覆盖；失败时原文件不动；
     * - 事件帧逐字节保留（只换第一帧）。
     */
    fun remapIfNeeded(
        file: File,
        targetCwd: String,
        canUse: (String) -> Boolean,
    ): Remap {
        if (targetCwd.isBlank()) return Remap(false, null, targetCwd, "目标工作区为空")
        if (!file.isFile || file.length() > MAX_REMAP_BYTES) {
            return Remap(false, null, targetCwd, "会话文件过大（>64MB），跳过工作区映射")
        }
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            return Remap(false, null, targetCwd, "读不了会话文件：${e.message ?: "IO 错误"}")
        }
        val frameLength = ZstdFrames.firstFrameLength(bytes)
            ?: return Remap(false, null, targetCwd, "会话头不是标准 zstd 帧")
        if (frameLength > MAX_HEADER_FRAME) {
            return Remap(false, null, targetCwd, "会话头异常大（$frameLength B）")
        }

        val headerText = try {
            ZstdFrames.decompressFrame(bytes, frameLength).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            return Remap(false, null, targetCwd, "解不开会话头：${e.message ?: "zstd 错误"}")
        }
        val headerLine = headerText.lineSequence().firstOrNull { it.isNotBlank() }
            ?: return Remap(false, null, targetCwd, "会话头是空的")
        val header = try {
            Json.parseToJsonElement(headerLine).jsonObject
        } catch (_: Exception) {
            return Remap(false, null, targetCwd, "会话头不是 JSON 对象")
        }
        val oldCwd = header["cwd"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (oldCwd.isBlank()) return Remap(false, oldCwd, targetCwd)
        if (oldCwd == targetCwd || runCatching { canUse(oldCwd) }.getOrDefault(false)) {
            return Remap(false, oldCwd, targetCwd)
        }

        val newHeader = buildJsonObject {
            header.forEach { (key, value) -> put(key, value) }
            put("cwd", targetCwd)
        }
        val newFrame = ZstdFrames.compressFrame(newHeader.toString() + "\n")
        val tmp = File(file.parentFile, ".${file.name}.cwd-remap")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(newFrame)
                out.write(bytes, frameLength, bytes.size - frameLength)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                // 极端情况（跨文件系统/Windows 占位）退回覆盖写
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            return Remap(false, oldCwd, targetCwd, "写入失败：${e.message ?: "IO 错误"}")
        }
        return Remap(true, oldCwd, targetCwd)
    }
}
