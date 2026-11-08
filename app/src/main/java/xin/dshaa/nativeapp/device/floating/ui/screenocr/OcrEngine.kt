package xin.dshaa.nativeapp.device.floating.ui.screenocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Operit `OCRUtils.recognizeText(context, bitmap, HIGH)` 的原生等价实现：
 * 拉丁 + 中文各识别一次，两个结果不同就换行合并（Operit OCRUtils.kt:240-256）。
 */
object OcrEngine {

    suspend fun recognize(bitmap: Bitmap): String {
        val latin = runCatching { recognizeWith(bitmap, chinese = false) }.getOrDefault("")
        val chinese = runCatching { recognizeWith(bitmap, chinese = true) }.getOrDefault("")
        return when {
            latin.isBlank() -> chinese
            chinese.isBlank() -> latin
            latin == chinese -> latin
            else -> "$latin\n$chinese"
        }.trim()
    }

    private suspend fun recognizeWith(bitmap: Bitmap, chinese: Boolean): String {
        val recognizer = if (chinese) {
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        } else {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        return try {
            suspendCancellableCoroutine<String> { cont ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result -> cont.resume(result.text) }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            }
        } finally {
            recognizer.close()
        }
    }
}
