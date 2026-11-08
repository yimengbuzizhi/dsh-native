package xin.dshaa.core.attachment

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import xin.dshaa.core.tools.ImageStoreException
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * 真机（instrumented）验证归一化：BitmapFactory 解码 / 缩小 / 重编码这条 Android 专属链路。
 * 跑法：`gradle :core:attachment:connectedDebugAndroidTest`（需要连着设备）。
 */
@RunWith(AndroidJUnit4::class)
class ImageNormalizerInstrumentedTest {

    private fun sample(width: Int, height: Int, alpha: Boolean = false): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (alpha) Color.argb(128, 40, 110, 220) else Color.rgb(40, 110, 220))
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = (height / 8f).coerceAtLeast(12f)
        }
        canvas.drawText("DSH $width x $height", width / 12f, height / 2f, paint)
        return bitmap
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int = 90): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(format, quality, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Test
    fun smallPngPassesThroughByteIdentical() {
        val png = encode(sample(320, 240), Bitmap.CompressFormat.PNG)
        val normalized = ImageNormalizer.normalize(png, "image/png")
        assertTrue(normalized.data.contentEquals(png))
        assertEquals(320, normalized.width)
        assertEquals(240, normalized.height)
        assertEquals("image/png", normalized.mediaType)
        assertEquals(null, normalized.originalWidth)
    }

    @Test
    fun largePngIsDownscaledIntoPixelBudget() {
        val png = encode(sample(3000, 4000), Bitmap.CompressFormat.PNG)
        val normalized = ImageNormalizer.normalize(png, "image/png")
        assertTrue(normalized.width.toLong() * normalized.height <= ImageNormalizer.NORMALIZED_MAX_PIXELS)
        assertEquals(3000, normalized.originalWidth)
        assertEquals(4000, normalized.originalHeight)
        assertTrue(normalized.data.size <= ImageNormalizer.NORMALIZED_MAX_BYTES)
        assertEquals("image/png", normalized.mediaType)
    }

    @Test
    fun largeJpegBecomesJpegWithinBudget() {
        val jpeg = encode(sample(5000, 3000), Bitmap.CompressFormat.JPEG, 92)
        val normalized = ImageNormalizer.normalize(jpeg, "image/jpeg")
        assertTrue(normalized.width.toLong() * normalized.height <= ImageNormalizer.NORMALIZED_MAX_PIXELS)
        assertEquals("image/jpeg", normalized.mediaType)
        assertTrue(normalized.data.size <= ImageNormalizer.NORMALIZED_MAX_BYTES)
    }

    @Test
    fun garbageRejectedAsInvalidImage() {
        val error = assertFailsWith<ImageStoreException> {
            ImageNormalizer.normalize(ByteArray(64) { 7 }, "image/png")
        }
        assertEquals(ImageStoreException.Code.INVALID_IMAGE, error.code)
    }

    @Test
    fun mismatchedDeclaredTypeRejected() {
        val jpeg = encode(sample(64, 64), Bitmap.CompressFormat.JPEG, 80)
        val error = assertFailsWith<ImageStoreException> {
            ImageNormalizer.normalize(jpeg, "image/png")
        }
        assertEquals(ImageStoreException.Code.IMAGE_TYPE_MISMATCH, error.code)
    }
}
