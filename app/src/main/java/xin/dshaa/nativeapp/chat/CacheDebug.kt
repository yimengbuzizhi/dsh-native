package xin.dshaa.nativeapp.chat

import android.content.Context
import android.util.Log
import xin.dshaa.core.engine.CacheDebugSink
import xin.dshaa.nativeapp.BuildConfig
import java.io.File

/**
 * 工作流 D（docs/187 §7.2）：缓存诊断日志的 App 侧落盘。
 *
 * 只在 debug 包生效：`am start -n xin.dshaa.nativeapp/.MainActivity --ez dsh_cache_debug true`
 * 打开，随后每个 step 的每次请求在 `files/cache-debug.jsonl` 追加一行 JSON（字段见
 * [xin.dshaa.core.engine.CacheDebugRecord]）。`--ez dsh_cache_debug false` 关闭。
 *
 * core 不依赖 Android：这里实现 [CacheDebugSink]，通过 `EngineDeps.cacheDebug` 注入引擎
 * （接线补丁见 MainActivity 的 `handleCacheDebugIntent` 与 AppViewModel 的 `buildEngine`）。
 */
object CacheDebug {
    private const val TAG = "DshCacheDebug"
    private const val FILE_NAME = "cache-debug.jsonl"

    @Volatile
    private var sink: CacheDebugSink? = null

    /** 引擎每次请求问一次；release 永远是 null（[BuildConfig.DEBUG] 是编译期常量）。 */
    fun sink(): CacheDebugSink? = if (BuildConfig.DEBUG) sink else null

    /** 打开/关闭（`--ez dsh_cache_debug true|false`）；同一个文件跨进程重启继续追加。 */
    @Synchronized
    fun apply(context: Context, enabled: Boolean) {
        if (!BuildConfig.DEBUG || !enabled) {
            sink = null
            return
        }
        val file = File(context.filesDir, FILE_NAME)
        sink = FileSink(file)
        Log.i(TAG, "cache debug on: ${file.absolutePath}")
    }

    /** 逐行 append（写失败不抛给引擎：诊断日志不能影响回合）。 */
    private class FileSink(private val file: File) : CacheDebugSink {
        private val lock = Any()

        override fun emit(line: String) {
            synchronized(lock) {
                runCatching {
                    file.parentFile?.mkdirs()
                    file.appendText(line + "\n")
                }.onFailure { Log.w(TAG, "append failed: ${it.message ?: it.javaClass.simpleName}") }
            }
        }
    }
}
