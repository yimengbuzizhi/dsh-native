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

    private val bridge = JsNativeBridge(host)

    @Volatile
    private var handle: Long = NativeQuickJs.create(bridge)

    @Volatile
    private var closed = false

    /** 跑一段全局脚本；根 Promise settle（或中断）后返回。只能调用一次。 */
    fun evaluate(code: String, filename: String = "workflow"): JsOutcome {
        check(handle != 0L) { "QuickJS runtime is not available on this device" }
        val bytes = NativeQuickJs.run(handle, code.toByteArray(Charsets.UTF_8), filename.toByteArray(Charsets.UTF_8))
            ?: return JsOutcome(JsStatus.ERROR, error = "QuickJS run returned null")
        return parseJsEnvelope(bytes.toString(Charsets.UTF_8))
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
}

/**
 * M84-H 常驻 QuickJS 会话：一个插件一个 runtime，`apply` 之后**不销毁**。
 *
 * 与 [QuickJs] 的区别：
 * - [start] 跑一次插件模块 + apply，等它结算后 runtime 留着；
 * - [dispatch] 之后每次都调同一个 JS 全局函数 `__dshDispatch(method, payloadJson)`，插件注册的
 *   回调（`sessionProjections` 的 `apply`）因此保留闭包与状态；
 * - [close] 释放 runtime。
 *
 * 线程：JNI 里的 runtime 只能被一个线程碰。这里自带一条单线程执行器，start/dispatch/close
 * 全部投进去执行，宿主事件因此按投递顺序串行进 JS（投影折叠依赖顺序）。
 * [start]/[dispatch] 会**阻塞调用线程**到 JS 结算；插件侧死循环由超时 + interrupt 兜底。
 */
class QuickJsSession(private val host: JsHost) : Closeable {

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dsh-quickjs-resident").apply { isDaemon = true }
    }
    private val bridge = JsNativeBridge(host)

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var closed = false

    /** 起常驻 runtime 并跑启动脚本；[timeoutMs] 到点中断。 */
    fun start(code: String, filename: String = "plugin", timeoutMs: Long = 20_000): JsOutcome =
        run("start", timeoutMs) { h ->
            NativeQuickJs.start(h, code.toByteArray(Charsets.UTF_8), filename.toByteArray(Charsets.UTF_8))
        }

    /** 往常驻 runtime 投一次 dispatch；[timeoutMs] 到点中断（插件回调必须同步、快）。 */
    fun dispatch(method: String, payloadJson: String, timeoutMs: Long = 5_000): JsOutcome =
        run("dispatch", timeoutMs) { h ->
            NativeQuickJs.dispatch(
                h,
                method.toByteArray(Charsets.UTF_8),
                payloadJson.toByteArray(Charsets.UTF_8),
            )
        }

    fun interrupt() {
        val current = handle
        if (current != 0L) runCatching { NativeQuickJs.interrupt(current) }
    }

    /** 结算常驻 runtime 里的一条异步 host 调用（任意线程）。 */
    fun complete(callId: Int, ok: Boolean, payloadJson: String?) {
        val current = handle
        if (current == 0L) return
        runCatching { NativeQuickJs.complete(current, callId, ok, payloadJson?.toByteArray(Charsets.UTF_8)) }
    }

    private fun run(label: String, timeoutMs: Long, call: (Long) -> ByteArray?): JsOutcome {
        if (closed) return JsOutcome(JsStatus.ERROR, error = "常驻 JS 运行时已关闭")
        val future = executor.submit(java.util.concurrent.Callable {
            if (handle == 0L) handle = NativeQuickJs.create(bridge)
            val current = handle
            if (current == 0L) {
                return@Callable JsOutcome(JsStatus.ERROR, error = "QuickJS 常驻 runtime 创建失败（这个 ABI 没编进去？）")
            }
            val bytes = call(current)
                ?: return@Callable JsOutcome(JsStatus.ERROR, error = "QuickJS $label returned null")
            parseJsEnvelope(bytes.toString(Charsets.UTF_8))
        })
        return try {
            future.get(timeoutMs.coerceAtLeast(500), java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (timeout: java.util.concurrent.TimeoutException) {
            interrupt()
            // 给中断一个收尾窗口：脚本会在 20ms 轮询里看到标记并退回 interrupted。
            runCatching { future.get(2, java.util.concurrent.TimeUnit.SECONDS) }
            JsOutcome(JsStatus.INTERRUPTED, error = "$label 超时（>${timeoutMs}ms）")
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            JsOutcome(JsStatus.ERROR, error = "$label 失败：${cause.message ?: cause.javaClass.simpleName}")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching {
            executor.submit {
                val current = handle
                handle = 0
                if (current != 0L) runCatching { NativeQuickJs.shutdown(current) }
                if (current != 0L) runCatching { NativeQuickJs.destroy(current) }
            }.get(2, java.util.concurrent.TimeUnit.SECONDS)
        }
        executor.shutdownNow()
    }
}

/** 宿主桥的 JNI 适配（[QuickJs] 与 [QuickJsSession] 共用；JNI 按实例方法名反射）。 */
internal class JsNativeBridge(private val host: JsHost) {
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

/** 解析 C 侧 envelope（`{status, value|error}`）。 */
internal fun parseJsEnvelope(text: String): JsOutcome {
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

/** JNI 入口（对象名即 C 侧符号前缀，别改）。 */
internal object NativeQuickJs {
    init {
        System.loadLibrary("dshquickjs")
    }

    external fun create(bridge: Any): Long

    external fun destroy(handle: Long)

    external fun run(handle: Long, code: ByteArray, filename: ByteArray): ByteArray?

    external fun start(handle: Long, code: ByteArray, filename: ByteArray): ByteArray?

    external fun dispatch(handle: Long, method: ByteArray, payload: ByteArray): ByteArray?

    external fun shutdown(handle: Long)

    external fun complete(handle: Long, callId: Int, ok: Boolean, payload: ByteArray?)

    external fun interrupt(handle: Long)
}
