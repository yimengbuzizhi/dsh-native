package xin.dshaa.core.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xin.dshaa.core.model.ImageRef
import xin.dshaa.core.tools.fs.ReadImageTool
import xin.dshaa.core.tools.fs.formatImageReadOutput
import xin.dshaa.core.tools.fs.sniffImageMediaType
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `read_image` 的行为契约（对齐上游 `dsh-tool-fs/read-image`）：
 * 参数/后缀/能力门先拒绝 → 读字节 + 文件头嗅探 → 附件归一化 → `<path>/<type>/<content>` 信封。
 * 用假 AttachmentHost，位图归一化另在 :core:attachment 测。
 */
class ReadImageToolTest {

    private val workspace: File = Files.createTempDirectory("read-image-test").toFile().canonicalFile
    private val tool = ReadImageTool()

    @AfterTest
    fun cleanup() {
        workspace.deleteRecursively()
    }

    private class FakeHost(
        val ref: (ByteArray, String) -> ImageRef = { data, mediaType ->
            ImageRef("sha256:" + "a".repeat(64), mediaType, data.size, 10, 20)
        },
    ) : AttachmentHost {
        var savedData: ByteArray? = null
        var savedMediaType: String? = null
        var savedName: String? = null
        var failure: ImageStoreException? = null

        override suspend fun saveImage(data: ByteArray, mediaType: String, name: String?): ImageRef {
            savedData = data
            savedMediaType = mediaType
            savedName = name
            failure?.let { throw it }
            return ref(data, mediaType)
        }
    }

    private fun ctx(host: AttachmentHost? = FakeHost(), imageCapable: Boolean = true) = ToolContext(
        workspace = workspace,
        attachments = host,
        imageRoute = ImageRoute(provider = "deepseek-account", model = "deepseek-flash", supportsImage = imageCapable),
    )

    private fun args(path: String) = buildJsonObject { put("file_path", path) }

    private fun pngFile(name: String, payload: ByteArray = byteArrayOf()): File {
        val file = File(workspace, name)
        file.writeBytes(PNG_SIGNATURE + payload)
        return file
    }

    @Test
    fun emptyPathRejected() {
        val result = kotlinx.coroutines.runBlocking { tool.execute(buildJsonObject { put("file_path", "   ") }, ctx()) }
        assertTrue(result.isError)
        assertEquals("file_path must be a non-empty string", result.text)
    }

    @Test
    fun unsupportedExtensionRejectedBeforeIo() {
        val host = FakeHost()
        val result = kotlinx.coroutines.runBlocking { tool.execute(args("notes.txt"), ctx(host)) }
        assertTrue(result.isError)
        assertEquals(
            "cannot read \"notes.txt\": the .txt extension does not declare a supported image format; " +
                "read_image accepts PNG/JPEG/WebP/GIF files, including extension-less files in those formats",
            result.text,
        )
        assertEquals(null, host.savedData)
    }

    @Test
    fun hiddenFileHasNoExtension() {
        // Node path.extname(".hidden") == ""：隐藏文件当「无后缀」，按字节嗅探
        val host = FakeHost()
        val file = File(workspace, ".hidden").apply { writeBytes(PNG_SIGNATURE) }
        val result = kotlinx.coroutines.runBlocking { tool.execute(args(file.path), ctx(host)) }
        assertTrue(!result.isError, result.text)
        assertEquals("image/png", host.savedMediaType)
    }

    @Test
    fun noAttachmentService() {
        val result = kotlinx.coroutines.runBlocking { tool.execute(args("a.png"), ctx(host = null)) }
        assertEquals("cannot read \"a.png\" as an image: no attachment service is mounted", result.text)
    }

    @Test
    fun nonImageCapableModelRejected() {
        val result = kotlinx.coroutines.runBlocking { tool.execute(args("a.png"), ctx(imageCapable = false)) }
        assertEquals(
            "cannot read \"a.png\" as an image: model \"deepseek-flash\" does not declare image input; " +
                "switch to an image-capable model to read images",
            result.text,
        )
    }

    @Test
    fun missingFile() {
        val file = File(workspace, "missing.png")
        val result = kotlinx.coroutines.runBlocking { tool.execute(args(file.path), ctx()) }
        assertEquals("cannot read \"${file.path}\": not found", result.text)
    }

    @Test
    fun directoryIsNotRegularFile() {
        val dir = File(workspace, "pics.png").apply { mkdirs() }
        val result = kotlinx.coroutines.runBlocking { tool.execute(args(dir.path), ctx()) }
        assertEquals("cannot read \"${dir.path}\": not a regular file", result.text)
    }

    @Test
    fun extensionlessFileSniffed() {
        val host = FakeHost()
        val file = pngFile("shot", byteArrayOf(1, 2, 3))
        val result = kotlinx.coroutines.runBlocking { tool.execute(args(file.path), ctx(host)) }
        assertTrue(!result.isError, result.text)
        assertEquals("image/png", host.savedMediaType)
        assertEquals("shot", host.savedName)
        assertEquals((PNG_SIGNATURE + byteArrayOf(1, 2, 3)).toList(), host.savedData!!.toList())
        assertContains(result.text, "<path>${file.path}</path>")
    }

    @Test
    fun extensionMismatchRejected() {
        val file = File(workspace, "actually-jpeg.png").apply { writeBytes(JPEG_SIGNATURE + byteArrayOf(1)) }
        val result = kotlinx.coroutines.runBlocking { tool.execute(args(file.path), ctx()) }
        assertEquals(
            "cannot read \"${file.path}\": the .png extension declares image/png, but the bytes use a different " +
                "image format; rename the file to match its actual format if it is PNG/JPEG/WebP/GIF, " +
                "or convert it to one of those formats",
            result.text,
        )
    }

    @Test
    fun unsupportedBytesWithoutExtension() {
        val file = File(workspace, "blob").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val result = kotlinx.coroutines.runBlocking { tool.execute(args(file.path), ctx()) }
        assertEquals(
            "cannot read \"${file.path}\": the file content is not a supported image format; " +
                "read_image accepts PNG/JPEG/WebP/GIF",
            result.text,
        )
    }

    @Test
    fun envelopeMatchesUpstream() {
        val ref = ImageRef("sha256:" + "b".repeat(64), "image/png", 1234, 720, 1570)
        assertEquals(
            "<path>/tmp/x.png</path>\n<type>image</type>\n<content>\nimage/png image, 720x1570 px, 1234 bytes\n</content>",
            formatImageReadOutput("/tmp/x.png", ref),
        )
    }

    @Test
    fun downscaledEnvelopeCarriesCoordinateMultipliers() {
        val equal = ImageRef("sha256:x", "image/png", 10, 1500, 1000, originalWidth = 3000, originalHeight = 2000)
        assertContains(formatImageReadOutput("/a.png", equal), "(downscaled from 3000x2000 px; multiply coordinates by 2.00 to locate features in the original file)")
        val unequal = ImageRef("sha256:x", "image/png", 10, 1600, 900, originalWidth = 4000, originalHeight = 1800)
        assertContains(
            formatImageReadOutput("/a.png", unequal),
            "multiply x coordinates by 2.50 and y coordinates by 2.00",
        )
    }

    @Test
    fun hostInvalidImageMapsToUpstreamText() {
        val host = FakeHost().apply { failure = ImageStoreException(ImageStoreException.Code.INVALID_IMAGE) }
        val file = pngFile("broken.png")
        val result = kotlinx.coroutines.runBlocking { tool.execute(args(file.path), ctx(host)) }
        assertEquals("Unsupported or malformed image data.", result.text)

        val hostNoExt = FakeHost().apply { failure = ImageStoreException(ImageStoreException.Code.INVALID_IMAGE) }
        val noExt = pngFile("broken")
        val resultNoExt = kotlinx.coroutines.runBlocking { tool.execute(args(noExt.path), ctx(hostNoExt)) }
        assertEquals(
            "cannot read \"${noExt.path}\": the bytes do not decode as a supported PNG/JPEG/WebP/GIF image; " +
                "the file may be truncated or corrupt",
            resultNoExt.text,
        )
    }

    @Test
    fun hostLimitsMapToUpstreamText() {
        fun message(code: ImageStoreException.Code): String {
            val host = FakeHost().apply { failure = ImageStoreException(code) }
            val file = pngFile("big.png")
            return kotlinx.coroutines.runBlocking { tool.execute(args(file.path), ctx(host)) }.text
        }
        assertContains(message(ImageStoreException.Code.IMAGE_DIMENSION_TOO_LARGE), "at least one image side exceeds the 65535px limit")
        assertContains(message(ImageStoreException.Code.IMAGE_TOO_MANY_PIXELS), "the image exceeds the 64000000-pixel decoded-size limit")
        assertContains(message(ImageStoreException.Code.IMAGE_TOO_LARGE), "the image cannot be stored within the deployment's byte limits")
    }

    @Test
    fun sniffRecognisesAllFourFormats() {
        assertEquals("image/png", sniffImageMediaType(PNG_SIGNATURE))
        assertEquals("image/jpeg", sniffImageMediaType(JPEG_SIGNATURE))
        assertEquals("image/gif", sniffImageMediaType("GIF89a".toByteArray()))
        assertEquals("image/webp", sniffImageMediaType("RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray()))
        assertEquals(null, sniffImageMediaType(byteArrayOf(0, 1, 2)))
    }

    @Test
    fun toolResultCarriesImageRef() {
        val ref = ImageRef("sha256:" + "c".repeat(64), "image/jpeg", 7, 1, 2)
        val host = FakeHost(ref = { _, _ -> ref })
        val file = File(workspace, "p.jpg").apply { writeBytes(JPEG_SIGNATURE) }
        val result = kotlinx.coroutines.runBlocking { tool.execute(args(file.path), ctx(host)) }
        assertEquals(listOf(ref), result.images)
        assertEquals(file.path, result.meta["path"])
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        val JPEG_SIGNATURE = byteArrayOf(-1, -40, -1, -32)
    }
}
