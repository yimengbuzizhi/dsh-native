package xin.dshaa.core.tools.fs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import xin.dshaa.core.model.ImageRef
import xin.dshaa.core.tools.DshTool
import xin.dshaa.core.tools.ImageStoreException
import xin.dshaa.core.tools.ToolContext
import xin.dshaa.core.tools.ToolResult
import xin.dshaa.core.tools.resolvePath
import java.io.File
import java.util.Locale

/** `read_image` 接受的扩展名 → 媒体类型（上游 `IMAGE_EXTENSIONS`，小写）。 */
private val IMAGE_EXTENSIONS = mapOf(
    ".png" to "image/png",
    ".jpg" to "image/jpeg",
    ".jpeg" to "image/jpeg",
    ".webp" to "image/webp",
    ".gif" to "image/gif",
)

/** 源文件字节上限：min(maxImageBytes 50MB, maxMessageImageBytes 200MB) = 50MB（上游部署默认值）。 */
internal const val MAX_IMAGE_BYTES = 50L * 1024 * 1024

private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) // 0x89 P N G \r \n 0x1A \n
private val JPEG_SIGNATURE = byteArrayOf(-1, -40, -1) // FF D8 FF

private fun matchesBytes(data: ByteArray, offset: Int, expected: ByteArray): Boolean {
    if (data.size < offset + expected.size) return false
    return expected.indices.all { data[offset + it] == expected[it] }
}

private fun matchesAscii(data: ByteArray, offset: Int, value: String): Boolean {
    if (data.size < offset + value.length) return false
    return value.indices.all { data[offset + it] == value[it].code.toByte() }
}

/** 按文件头识别 PNG/JPEG/GIF/WebP；其它字节返回 null（上游 `sniffImageMediaType`）。 */
fun sniffImageMediaType(data: ByteArray): String? {
    if (matchesBytes(data, 0, PNG_SIGNATURE)) return "image/png"
    if (matchesBytes(data, 0, JPEG_SIGNATURE)) return "image/jpeg"
    if (matchesAscii(data, 0, "GIF87a") || matchesAscii(data, 0, "GIF89a")) return "image/gif"
    if (matchesAscii(data, 0, "RIFF") && matchesAscii(data, 8, "WEBP")) return "image/webp"
    return null
}

/** 路径后缀（Node `path.extname` 语义：`.hidden` / 无后缀 → 空串；`a.tar.gz` → `.gz`）。 */
internal fun imageExtensionOf(path: String): String {
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) "" else name.substring(dot).lowercase()
}

/** 按后缀声明媒体类型；不声明返回 null（上游 `imageMediaTypeForPath`）。 */
fun imageMediaTypeForPath(path: String): String? = IMAGE_EXTENSIONS[imageExtensionOf(path)]

private fun fixed2(value: Double): String = String.format(Locale.US, "%.2f", value)

/**
 * 模型面对的图片信封，逐字对齐上游 `formatImageReadOutput`：
 * 缩小过的图会给出「把坐标乘回去」的比例（模型在缩略图上量坐标时用）。
 */
fun formatImageReadOutput(displayPath: String, image: ImageRef): String {
    var scaled = ""
    val originalWidth = image.originalWidth
    val originalHeight = image.originalHeight
    if (originalWidth != null && originalHeight != null) {
        val x = fixed2(originalWidth.toDouble() / image.width)
        val y = fixed2(originalHeight.toDouble() / image.height)
        val advice = if (x == y) {
            "multiply coordinates by $x"
        } else {
            "multiply x coordinates by $x and y coordinates by $y"
        }
        scaled = " (downscaled from ${originalWidth}x${originalHeight} px; " +
            "$advice to locate features in the original file)"
    }
    return """
        |<path>$displayPath</path>
        |<type>image</type>
        |<content>
        |${image.mediaType} image, ${image.width}x${image.height} px, ${image.bytes} bytes$scaled
        |</content>
    """.trimMargin()
}

/**
 * `read_image`（第 16 个工具）：把一张 PNG/JPEG/WebP/GIF 交给模型。
 *
 * 三段式与上游 `dsh-tool-fs/read-image` 对齐：
 * 1. 参数/后缀/能力门 → 在读文件**之前**拒绝（省 IO，也省得模型白等）；
 * 2. 读字节 + 文件头嗅探（无后缀文件也能认）；
 * 3. 附件服务校验/归一化/落盘，产出 `<path>/<type>/<content>` 信封 + image 块。
 *
 * 位图解码与落盘在 `core:attachment`（Android），本类保持纯 JVM，可直接单测。
 */
class ReadImageTool : DshTool {
    override val name = "read_image"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val requested = runCatching { args["file_path"]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty().trim()
        if (requested.isEmpty()) return ToolResult.err("file_path must be a non-empty string")

        val extension = imageExtensionOf(requested)
        val declared = imageMediaTypeForPath(requested)
        if (declared == null && extension.isNotEmpty()) {
            return ToolResult.err(
                "cannot read \"$requested\": the $extension extension does not declare a supported image format; " +
                    "read_image accepts PNG/JPEG/WebP/GIF files, including extension-less files in those formats",
            )
        }

        val host = ctx.attachments
            ?: return ToolResult.err("cannot read \"$requested\" as an image: no attachment service is mounted")
        val route = ctx.imageRoute
            ?: return ToolResult.err("cannot read \"$requested\" as an image: the current model route could not be resolved")
        if (route.provider == null || route.model == null) {
            return ToolResult.err("cannot read \"$requested\" as an image: the current model route could not be resolved")
        }
        if (!route.supportsImage) {
            return ToolResult.err(
                "cannot read \"$requested\" as an image: model \"${route.model}\" does not declare image input; " +
                    "switch to an image-capable model to read images",
            )
        }

        val target: File = try {
            resolvePath(requested, ctx)
        } catch (e: IllegalArgumentException) {
            return ToolResult.err("cannot read \"$requested\": ${e.message}")
        }
        val displayPath = target.path
        if (!target.exists()) return ToolResult.err("cannot read \"$displayPath\": not found")
        if (!target.isFile) return ToolResult.err("cannot read \"$displayPath\": not a regular file")
        if (target.length() > MAX_IMAGE_BYTES) {
            return ToolResult.err("cannot read \"$displayPath\": the file exceeds the $MAX_IMAGE_BYTES-byte image limit")
        }

        val data = try {
            withContext(Dispatchers.IO) { target.readBytes() }
        } catch (e: Exception) {
            return ToolResult.err("cannot read \"$displayPath\": ${e.message ?: e.javaClass.simpleName}")
        }

        val sniffed = sniffImageMediaType(data)
        if (declared != null && sniffed != null && declared != sniffed) {
            return ToolResult.err(
                "cannot read \"$displayPath\": the $extension extension declares $declared, but the bytes use a " +
                    "different image format; rename the file to match its actual format if it is PNG/JPEG/WebP/GIF, " +
                    "or convert it to one of those formats",
            )
        }
        val mediaType = declared ?: sniffed
            ?: return ToolResult.err(
                "cannot read \"$displayPath\": the file content is not a supported image format; " +
                    "read_image accepts PNG/JPEG/WebP/GIF",
            )

        val ref = try {
            host.saveImage(data, mediaType, target.name)
        } catch (e: ImageStoreException) {
            return ToolResult.err(imageStoreMessage(displayPath, extension, declared, mediaType, e))
        } catch (e: Exception) {
            return ToolResult.err("cannot read \"$displayPath\": ${e.message ?: e.javaClass.simpleName}")
        }

        return ToolResult.ok(formatImageReadOutput(displayPath, ref), meta = mapOf("path" to displayPath), images = listOf(ref))
    }

    /** 上游对 `AttachmentError` 的逐条翻译（顺序与文案都照抄）。 */
    private fun imageStoreMessage(
        displayPath: String,
        extension: String,
        declared: String?,
        mediaType: String,
        error: ImageStoreException,
    ): String = when (error.code) {
        ImageStoreException.Code.IMAGE_DIMENSION_TOO_LARGE ->
            "cannot read \"$displayPath\": at least one image side exceeds the 65535px limit; " +
                "downscale the image and read the smaller copy"

        ImageStoreException.Code.IMAGE_TOO_MANY_PIXELS ->
            "cannot read \"$displayPath\": the image exceeds the 64000000-pixel decoded-size limit; " +
                "downscale the image and read the smaller copy"

        ImageStoreException.Code.IMAGE_TOO_LARGE ->
            "cannot read \"$displayPath\": the image cannot be stored within the deployment's byte limits; " +
                "downscale the image and read the smaller copy"

        ImageStoreException.Code.INVALID_IMAGE ->
            if (declared == null) {
                "cannot read \"$displayPath\": the bytes do not decode as a supported PNG/JPEG/WebP/GIF image; " +
                    "the file may be truncated or corrupt"
            } else {
                "Unsupported or malformed image data."
            }

        ImageStoreException.Code.IMAGE_TYPE_MISMATCH ->
            if (declared == null) {
                "cannot read \"$displayPath\": the file signature claims $mediaType, but the bytes decode as a " +
                    "different image format; the file may be corrupt"
            } else {
                "cannot read \"$displayPath\": the $extension extension declares $declared, but the bytes use a " +
                    "different image format; rename the file to match its actual format if it is PNG/JPEG/WebP/GIF, " +
                    "or convert it to one of those formats"
            }

        ImageStoreException.Code.WRITE_FAILED ->
            "cannot read \"$displayPath\": ${error.message?.ifBlank { "image could not be stored" } ?: "image could not be stored"}"
    }
}
