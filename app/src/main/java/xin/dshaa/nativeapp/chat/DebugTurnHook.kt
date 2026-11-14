package xin.dshaa.nativeapp.chat

import android.util.Log
import xin.dshaa.core.engine.EngineHooks
import xin.dshaa.nativeapp.BuildConfig

/**
 * M84-C2：debug 包挂到 [EngineHooks] 上的观察器——只写 logcat（tag `DshTurnHook`），
 * 不改引擎行为。真机验收靠它确认「step 前后、工具执行前后、停止、错误/重试」的顺序。
 *
 * release 返回全 no-op（编译期常量 [BuildConfig.DEBUG]，不会被优化掉的只有空对象）。
 */
object DebugTurnHook {
    private const val TAG = "DshTurnHook"

    val hooks: EngineHooks = if (!BuildConfig.DEBUG) {
        EngineHooks()
    } else {
        EngineHooks(
            onStepStart = { point ->
                Log.i(TAG, "step-start turn=${point.turn} step=${point.step}")
            },
            onStepEnd = { point ->
                Log.i(TAG, "step-end turn=${point.turn} step=${point.step}")
            },
            onToolStart = { call ->
                Log.i(TAG, "tool-start name=${call.name} id=${call.id}")
            },
            onToolEnd = { call, result ->
                Log.i(
                    TAG,
                    "tool-end name=${call.name} id=${call.id} error=${result.isError} chars=${result.text.length}",
                )
            },
            onStop = { stop ->
                Log.i(TAG, "stop reason=${stop.reason} user=${stop.userRequested} turn=${stop.turn} step=${stop.step}")
            },
            onError = { failure ->
                Log.i(
                    TAG,
                    "error code=${failure.code ?: "-"} status=${failure.status ?: "-"} " +
                        "message=${failure.message.take(160)}",
                )
            },
            onRetry = { retry ->
                Log.i(TAG, "retry kind=${retry.kind} attempt=${retry.attempt}/${retry.max}")
            },
        )
    }
}
