package xin.dshaa.nativeapp.node

import android.content.Context
import android.os.Process as AndroidProcess
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Node 宿主端到端自测（P2 验收：正常链 / 错误回传 / 超时 / 超时后仍可用 / WS 鉴权 /
 * 提示词段归属 / kill -9 自动恢复 / 崩溃循环收手 / 停进程回收）。
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

                // 第 2 项：错误 token 必须被宿主拒绝（不收到 hello、连接被关）。
                val auth = authRejected(host.port)
                record("ws_auth_reject", auth.first, auth.second)

                // 第 11 项：提示词段只认领插件自己的，不混入 dsh identity/persona。
                val payload = host.promptPayload()
                val sectionNames = payload?.sections?.mapNotNull { (it["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    .orEmpty()
                val reserved = listOf("identity", "persona", "harness", "system")
                val blended = sectionNames.filter { name -> reserved.any { name.equals(it, ignoreCase = true) } }
                record(
                    "prompt_sections_owned",
                    payload != null && "hello-plugin/greeting" in sectionNames && blended.isEmpty(),
                    "source=${payload?.source} sections=$sectionNames blended=$blended",
                )

                // 第 3 项：审计落盘有 start/done 两类行。
                val auditLines = runCatching { host.auditFile().readLines() }.getOrDefault(emptyList())
                val doneLines = auditLines.count { it.contains("\"phase\":\"done\"") }
                record(
                    "audit_jsonl",
                    auditLines.isNotEmpty() && doneLines >= 4,
                    "lines=${auditLines.size} done=$doneLines file=${host.auditFile().absolutePath}",
                )

                // P3-b：插件登记的模型供应商 —— 可用的要报上来，callback-only 必须如实标不可用。
                val refreshed = host.refreshRegistrations()
                val providers = host.llmProviders.value
                val callbackOnly = providers.firstOrNull { it.streamMode == NodeLlmProviders.MODE_CALLBACK }
                record(
                    "llm_providers",
                    refreshed && providers.isNotEmpty() && providers.any { it.usable } &&
                        callbackOnly?.usable == false,
                    providers.joinToString("; ") {
                        "${it.id} usable=${it.usable} stream=${it.streamMode} reason=${it.reason.take(60)}"
                    },
                )

                // P3-c：命令列表 + 成功 / 失败 / 未注册三条结果（未注册必须是明确错误）。
                val commandNames = host.commands.value.map { it.name }
                val commandOk = host.invokeCommand("hello-node", " selftest", 10_000)
                val commandFail = host.invokeCommand("hello-node-fail", "", 10_000)
                val commandUnknown = host.invokeCommand("definitely-not-registered", "", 10_000)
                record(
                    "command_list",
                    commandNames.contains("hello-node") && commandNames.contains("hello-node-fail"),
                    "commands=$commandNames",
                )
                record(
                    "command_invoke",
                    commandOk is NodeCommandCall.Ok && commandFail is NodeCommandCall.Failure,
                    "ok=${(commandOk as? NodeCommandCall.Ok)?.text} " +
                        "fail=${(commandFail as? NodeCommandCall.Failure)?.error}",
                )
                record(
                    "command_unknown",
                    commandUnknown is NodeCommandCall.Failure,
                    (commandUnknown as? NodeCommandCall.Failure)?.error ?: commandUnknown.toString(),
                )

                // 命令审计：与工具调用同一个文件、同一个 host 标记。
                val commandAudit = runCatching { host.auditFile().readLines() }.getOrDefault(emptyList())
                    .count { it.contains("\"tool\":\"command:hello-node\"") }
                record("command_audit", commandAudit >= 2, "lines=$commandAudit")

                // 第 5 项：kill -9 宿主 → 自动恢复（第 1 次），再连杀 2 次 → Failed（5 分钟内 3 次）。
                val firstPid = host.pid
                AndroidProcess.killProcess(firstPid)
                val recovered = awaitState(host, 20_000) { state ->
                    state is NodePluginHost.State.Ready && state.pid != firstPid
                }
                val afterCrash = if (recovered) {
                    val call = host.invoke("hello_probe", args("""{"note":"after-kill"}"""), 20_000)
                    !call.isError
                } else {
                    false
                }
                record(
                    "crash_restart_1",
                    recovered && afterCrash,
                    "pid=$firstPid → ${host.describeState()} recovered=$recovered callOk=$afterCrash",
                )

                var crashAttempts = 1
                var failedState = false
                while (crashAttempts < 3) {
                    val currentPid = host.pid
                    if (currentPid <= 0) break
                    AndroidProcess.killProcess(currentPid)
                    crashAttempts += 1
                    if (crashAttempts < 3) {
                        // 前两次：等新 pid 就绪再杀，确保是「连续崩溃」而不是重复杀同一个死进程。
                        awaitState(host, 20_000) { state ->
                            state is NodePluginHost.State.Ready && state.pid != currentPid
                        }
                    } else {
                        failedState = awaitState(host, 20_000) { it is NodePluginHost.State.Failed }
                    }
                }
                record(
                    "crash_loop_failed",
                    failedState,
                    "kills=$crashAttempts state=${host.describeState()} crashTimes=${host.crashTimes().size}",
                )
            }

            val stopStarted = System.currentTimeMillis()
            val pidBeforeStop = host.pid
            host.stop()
            val stopped = awaitState(host, 6_000) { it is NodePluginHost.State.Stopped }
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
            put("auditFile", host.auditFile().absolutePath)
        }
        val target = outputPath?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: File(context.filesDir, "node-selftest.json")
        runCatching { target.parentFile?.mkdirs(); target.writeText(report.toString(), Charsets.UTF_8) }
        Log.i(TAG, "selftest report: ${target.absolutePath} -> $report")
    }

    /** 错误 token 的连接：不许收到 hello，必须被宿主关掉。 */
    private suspend fun authRejected(port: Int): Pair<Boolean, String> {
        if (port <= 0) return false to "port=$port"
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        var helloSeen = false
        var closedReason = ""
        val closed = CountDownLatch(1)
        val ws = client.newWebSocket(
            Request.Builder().url("ws://127.0.0.1:$port/?token=wrong-token").build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    helloSeen = true
                    closed.countDown()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // OkHttp 对服务端发起的 close 只先回调 onClosing；必须在这里记下 code 并回 close，
                    // 否则会停在半开连接上（首版 selftest 就是漏了这个，宿主其实已经 4401 关掉）。
                    closedReason = "closing($code) ${reason}".trim()
                    closed.countDown()
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closedReason = "closed($code) ${reason}".trim()
                    closed.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    closedReason = "failure: ${t.message}"
                    closed.countDown()
                }
            },
        )
        val settled = withTimeoutOrNull(4_000) {
            while (closed.count > 0L) delay(100)
            true
        } ?: false
        runCatching { ws.cancel() }
        runCatching { client.dispatcher.executorService.shutdown() }
        // 断言宿主给的是 4401（鉴权失败），而不是随便断网/超时。
        return (settled && !helloSeen && closedReason.contains("4401")) to
            "settled=$settled helloSeen=$helloSeen reason=$closedReason"
    }

    private suspend fun awaitState(
        host: NodePluginHost,
        timeoutMs: Long,
        predicate: (NodePluginHost.State) -> Boolean,
    ): Boolean = withTimeoutOrNull(timeoutMs) {
        while (!predicate(host.state.value)) delay(100)
        true
    } ?: false

    private fun args(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    private suspend fun <T> timed(block: suspend () -> T): Pair<T, Long> {
        val at = System.currentTimeMillis()
        val value = block()
        return value to (System.currentTimeMillis() - at)
    }
}
