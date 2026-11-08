package xin.dshaa.nativeapp.device.floating

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 原生语音后端（docs/76 §十三.2）。
 *
 * Operit 用 `SpeechServiceFactory` + `VoiceServiceFactory` + 唤醒词/全双工；原生没有那套依赖，
 * 这里用系统 `SpeechRecognizer` + `TextToSpeech` 实现「按住说话/点按说话 + 流式朗读」。
 * 明确差异：不常驻监听唤醒词，不做边播边听的全双工。
 */
class FloatingSpeechManager(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    var isRecording: Boolean = false
        private set
    var isProcessing: Boolean = false
        private set
    var volume: Float = 0f
        private set

    var onPartial: ((String) -> Unit)? = null
    var onFinal: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onState: ((String) -> Unit)? = null

    fun initialize() {
        if (recognizer == null && SpeechRecognizer.isRecognitionAvailable(context)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        onState?.invoke("在听…")
                    }

                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) {
                        volume = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    }

                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() {
                        isRecording = false
                        isProcessing = true
                        onState?.invoke("识别中…")
                    }

                    override fun onError(error: Int) {
                        isRecording = false
                        isProcessing = false
                        onError?.invoke(speechError(error))
                    }

                    override fun onResults(results: Bundle?) {
                        isRecording = false
                        isProcessing = false
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        if (text.isBlank()) onError?.invoke("没听清，再说一次")
                        else onFinal?.invoke(text)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val text = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        if (text.isNotBlank()) onPartial?.invoke(text)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
            }
        }
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) tts?.language = Locale.SIMPLIFIED_CHINESE
            }
        }
    }

    fun startListening() {
        val r = recognizer ?: run {
            onError?.invoke("系统没有可用的语音识别服务")
            return
        }
        if (isRecording) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        isRecording = true
        isProcessing = false
        volume = 0f
        onState?.invoke("在听…")
        runCatching { r.startListening(intent) }.onFailure {
            isRecording = false
            onError?.invoke(it.message ?: "启动语音识别失败")
        }
    }

    fun stopListening(cancel: Boolean) {
        val r = recognizer ?: return
        if (cancel) {
            r.cancel()
            isRecording = false
            isProcessing = false
            onState?.invoke("已取消")
        } else {
            runCatching { r.stopListening() }
        }
    }

    fun speak(text: String, interrupt: Boolean = true) {
        val t = tts ?: return
        if (!ttsReady || text.isBlank()) return
        t.speak(
            text,
            if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null,
            "dsh-floating-${System.nanoTime()}",
        )
    }

    fun stopSpeaking() {
        runCatching { tts?.stop() }
    }

    fun isSpeaking(): Boolean = runCatching { tts?.isSpeaking == true }.getOrDefault(false)

    fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
        isRecording = false
        isProcessing = false
    }

    private fun speechError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "录音错误"
        SpeechRecognizer.ERROR_CLIENT -> "语音识别客户端错误"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有录音权限"
        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再说一次"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙，稍后再试"
        SpeechRecognizer.ERROR_SERVER -> "识别服务错误"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听到声音"
        else -> "语音识别失败（$code）"
    }
}
