package xin.dshaa.nativeapp.node

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Node 宿主端到端自测（P2 验收：正常链 / 错误回传 / 超时 / 超时后仍可用 / 停进程回收）。
 *
 * 真机跑法：`adb shell am start -n xin.dshaa.nativeapp/.MainActivity --ez dsh_node_selftest true`
 * 结果落 `filesDir/node-selftest.json`（也可用 `--es dsh_node_selftest_out <路径>` 指定）。
 *
 * 只依赖打包在运行时里的演示插件（hello_probe / hello_fail / hello_slow），所以它验的是
 * "桥 + 宿主 + 插件加载" 这条链，不依赖用户显式装了什么。
 */
object NodeHostSelfTest {

    private const val TAG = "DshNodeHost"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(context: Context, outputPath: String? = null) {
        val steps = mutableListOf<JsonObject>()
        var failed = 0
        val host = NodePluginHost(context)
        fun record(step: String, ok: Boolean, detail: String, ms: Long = 0) {
            if (!ok) failed += 1
            steps += buildJsonObject {
                put("step", step)
                put("ok", ok)
                put("detail", detail)
                put("ms", ms)
            }
            Log.i(TAG, "selftest $step ok=$ok ${detail.take(400)}")
        }

        val started = System.currentTimeMillis()
        try {
            val ready = host.start()
            record(
                "start",
                ready,
                if (ready) "${host.describeState()} rss=${host.rssKb ?: -1}KB" else host.describeState(),
                System.currentTimeMillis() - started,
            )
            if (ready) {
                val probe = timed { host.invoke("hello_probe", args("""{"note":"selftest"}"""), 20_000) }
                record("hello_probe", !probe.first.isError, probe.first.text, probe.second)

                val todo = timed {
                    host.invoke(
                        "todo_write",
                        args("""{"todos":[{"content":"node host selftest","status":"pending"}]}"""),
                        20_000,
                    )
                }
                record("todo_write", !todo.first.isError, todo.first.text, todo.second)

                val failing = timed { host.invoke("hello_fail", args("""{"reason":"expected failure"}"""), 20_000) }
                record("hello_fail", failing.first.isError, failing.first.text, failing.second)

                // 超时：插件睡 5s，桥给 1500ms；预期拿 Node 侧的超时文案。
                val slow = timed { host.invoke("hello_slow", args("""{"ms":5000}"""), 1_500) }
                record("hello_slow_timeout", slow.first.isError, slow.first.text, slow.second)

                // 超时之后宿主必须还能用（历史上最容易踩的就是这里被焊死）。
                val after = timed { host.invoke("hello_probe", args("""{"note":"after-timeout"}"""), 20_000) }
                record("after_timeout_liveness", !after.first.isError, after.first.text, after.second)

                val toolNames = host.apiTools().joinToString(",") { it.name }
                record("tool_surface", host.apiTools().isNotEmpty(), toolNames)
            }

            val stopStarted = System.currentTimeMillis()
            val pidBeforeStop = host.pid
            host.stop()
            val stopped = withTimeoutOrNull(6_000) {
                while (host.state.value !is NodePluginHost.State.Stopped) delay(100)
                true
            } ?: false
            record("stop", stopped, "pid=$pidBeforeStop → ${host.describeState()}", System.currentTimeMillis() - stopStarted)
        } catch (error: Throwable) {
            record("exception", false, "${error.javaClass.simpleName}: ${error.message}")
        } finally {
            runCatching { host.shutdown() }
        }

        val report = buildJsonObject {
            put("ok", failed == 0)
            put("at", System.currentTimeMillis())
            put("totalMs", System.currentTimeMillis() - started)
            put("steps", buildJsonArray { steps.forEach { add(it) } })
            put("logs", buildJsonArray { host.tailLogs(12).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("state", host.describeState())
        }
        val target = outputPath?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: File(context.filesDir, "node-selftest.json")
        runCatching { target.parentFile?.mkdirs(); target.writeText(report.toString(), Charsets.UTF_8) }
        Log.i(TAG, "selftest report: ${target.absolutePath} -> $report")
    }

    private fun args(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    private suspend fun <T> timed(block: suspend () -> T): Pair<T, Long> {
        val at = System.currentTimeMillis()
        val value = block()
        return value to (System.currentTimeMillis() - at)
    }
}
