package xin.dshaa.core.js

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable

/**
 * QuickJS 宿主回调（在 JS 线程上同步/异步调用）。
 *
 * 约定：
 * - [callSync] 返回字符串（接进 JS 的返回值）；抛异常会被 C 侧吞掉并返回空串。
 * - [callAsync] 必须**立刻返回**，之后在任意线程调用 [QuickJs.complete] 结算；
 *   不抛异常（实现方自己保证，异常会让 Promise 永久 pending）。
 */
interface JsHost {
    fun callSync(method: String, payloadJson: String): String?

    fun callAsync(method: String, payloadJson: String, callId: Int)
}

enum class JsStatus { COMPLETED, ERROR, INTERRUPTED }

/**
 * 一次脚本执行的结果。
 *
 * [valueJson] 是根 Promise 兑现值的原始 JSON（`undefined` 归一成 `null`）；
 * [error] 是上游 `renderThrown` 风格的失败文本（优先 stack）。
 */
data class JsOutcome(
    val status: JsStatus,
    val valueJson: String? = null,
    val error: String? = null,
)

/**
 * QuickJS 运行时（android 库，JNI 见 `src/main/cpp/js_bridge.cpp`）。
 *
 * 一个实例 = 一次脚本执行：`evaluate` 在调用线程上新建 runtime、跑脚本、等根 Promise，
 * 执行完销毁 runtime。异步 hook 通过 [complete] 从任意线程回灌。
 *
 * 线程模型：`evaluate` 会阻塞调用线程；JS 回调也在该线程上。调用方应当放到
 * 单次专用线程（coroutine 里 `withContext(Dispatchers.IO)` 亦可）。
 */
class QuickJs(private val host: JsHost) : Closeable {

    private val bridge = NativeBridge(host)

    @Volatile
    private var handle: Long = NativeQuickJs.create(bridge)

    @Volatile
    private var closed = false

    /** 跑一段全局脚本；根 Promise settle（或中断）后返回。只能调用一次。 */
    fun evaluate(code: String, filename: String = "workflow"): JsOutcome {
        check(handle != 0L) { "QuickJS runtime is not available on this device" }
        val bytes = NativeQuickJs.run(handle, code.toByteArray(Charsets.UTF_8), filename.toByteArray(Charsets.UTF_8))
            ?: return JsOutcome(JsStatus.ERROR, error = "QuickJS run returned null")
        return parseEnvelope(bytes.toString(Charsets.UTF_8))
    }

    /** 结算一条异步 host 调用（任意线程）。[payloadJson] 是 resolve 值或 {message,code,fatal}。 */
    fun complete(callId: Int, ok: Boolean, payloadJson: String?) {
        if (handle == 0L) return
        NativeQuickJs.complete(handle, callId, ok, payloadJson?.toByteArray(Charsets.UTF_8))
    }

    /** 请求中断脚本（任意线程）；脚本会以 interrupted 结束。 */
    fun interrupt() {
        if (handle == 0L) return
        NativeQuickJs.interrupt(handle)
    }

    override fun close() {
        if (closed) return
        closed = true
        val current = handle
        handle = 0
        if (current != 0L) NativeQuickJs.destroy(current)
    }

    private fun parseEnvelope(text: String): JsOutcome {
        if (text.isEmpty()) return JsOutcome(JsStatus.ERROR, error = "empty QuickJS envelope")
        return try {
            val obj = Json.parseToJsonElement(text).jsonObject
            when (obj["status"]?.jsonPrimitive?.content) {
                "completed" -> JsOutcome(JsStatus.COMPLETED, valueJson = obj["value"]?.toString() ?: "null")
                "interrupted" -> JsOutcome(JsStatus.INTERRUPTED)
                else -> JsOutcome(JsStatus.ERROR, error = obj["error"]?.jsonPrimitive?.content ?: text)
            }
        } catch (t: Throwable) {
            Log.w("dsh-quickjs", "unparseable envelope: ${text.take(200)}", t)
            JsOutcome(JsStatus.ERROR, error = "unparseable QuickJS envelope: ${text.take(200)}")
        }
    }

    private class NativeBridge(private val host: JsHost) {
        @Suppress("unused")  // JNI 调用
        fun callSync(method: ByteArray, payload: ByteArray): ByteArray = try {
            (host.callSync(method.toString(Charsets.UTF_8), payload.toString(Charsets.UTF_8)) ?: "")
                .toByteArray(Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w("dsh-quickjs", "host callSync(${method.toString(Charsets.UTF_8)}) failed", t)
            ByteArray(0)
        }

        @Suppress("unused")  // JNI 调用
        fun callAsync(method: ByteArray, payload: ByteArray, callId: Int) {
            try {
                host.callAsync(method.toString(Charsets.UTF_8), payload.toString(Charsets.UTF_8), callId)
            } catch (t: Throwable) {
                Log.w("dsh-quickjs", "host callAsync(${method.toString(Charsets.UTF_8)}) failed", t)
            }
        }
    }
}

/** JNI 入口（对象名即 C 侧符号前缀，别改）。 */
internal object NativeQuickJs {
    init {
        System.loadLibrary("dshquickjs")
    }

    external fun create(bridge: Any): Long

    external fun destroy(handle: Long)

    external fun run(handle: Long, code: ByteArray, filename: ByteArray): ByteArray?

    external fun complete(handle: Long, callId: Int, ok: Boolean, payload: ByteArray?)

    external fun interrupt(handle: Long)
}
