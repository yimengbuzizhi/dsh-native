package xin.dshaa.nativeapp.node

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * P0 探针（仅 debug 通道）：
 * 1. [run]：验证 bionic Node 能从 `nativeLibraryDir/libnode.so` 启动；
 * 2. [runHost]：启动真 dsh 包组成的 node host（cordis + dsh-tools + 1 个真工具包），
 *    走回环 WS 收 `host.hello`，再 `tool.invoke` 一次，顺带读 `/proc/<pid>/status` 的 VmRSS。
 *
 * 这个类只服务 P0 验收，不是正式 Node 宿主；正式宿主（生命周期/审批/桥面）在 P2。
 */
object NodeProbe {

    fun run(context: Context, outPath: String) {
        Thread {
            val started = System.currentTimeMillis()
            val result = runCatching { versionProbe(context) }
                .getOrElse { "PROBE_ERROR ${it.stackTraceToString()}" }
            val text = "elapsedMs=${System.currentTimeMillis() - started}\n$result"
            write(outPath, text)
        }.start()
    }

    fun runHost(context: Context, outPath: String, scriptPath: String) {
        Thread {
            val started = System.currentTimeMillis()
            val result = runCatching { hostProbe(context, scriptPath, started) }
                .getOrElse { "PROBE_ERROR ${it.stackTraceToString()}" }
            write(outPath, result)
        }.start()
    }

    private fun versionProbe(context: Context): String {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val bin = File(nativeDir, "libnode.so")
        if (!bin.isFile) return "PROBE_ERROR missing ${bin.absolutePath}"
        val libDir = File(context.filesDir, "node/lib")
        val home = File(context.filesDir, "node/home").apply { mkdirs() }
        val tmp = File(context.cacheDir, "node-tmp").apply { mkdirs() }

        val process = ProcessBuilder(
            bin.absolutePath,
            "-e",
            "console.log('NODE_OK', process.version, process.arch, process.platform, process.execPath)",
        ).redirectErrorStream(true).apply { nodeEnv(libDir, nativeDir, home, tmp) }.start()

        val done = process.waitFor(20, TimeUnit.SECONDS)
        if (!done) {
            process.destroyForcibly()
            return "PROBE_TIMEOUT\nbin=${bin.absolutePath}\nlibs=${libDir.list()?.size ?: -1}"
        }
        val output = process.inputStream.bufferedReader().readText().trim()
        return "exit=${process.exitValue()}\nbin=${bin.absolutePath}\n" +
            "libs=${libDir.list()?.size ?: -1}\noutput=$output"
    }

    /**
     * 真 host 探针：输出 JSON（timings / hello / tool result / RSS），供 P0 数字直接引用。
     * [scriptPath] 是设备上的 host.mjs（P0 阶段放在应用外部 files 目录，正式打包再进 assets）。
     */
    private fun hostProbe(context: Context, scriptPath: String, startedAt: Long): String {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val bin = File(nativeDir, "libnode.so")
        val script = File(scriptPath)
        if (!bin.isFile) return "PROBE_ERROR missing ${bin.absolutePath}"
        if (!script.isFile) return "PROBE_ERROR missing ${script.absolutePath}"
        val libDir = File(context.filesDir, "node/lib")
        val home = File(context.filesDir, "node/home").apply { mkdirs() }
        val tmp = File(context.cacheDir, "node-tmp").apply { mkdirs() }

        val process = ProcessBuilder(bin.absolutePath, script.name)
            .directory(script.parentFile)
            .redirectErrorStream(true)
            .apply { nodeEnv(libDir, nativeDir, home, tmp) }
            .start()
        val spawnAt = System.currentTimeMillis()

        val lines = LinkedBlockingQueue<String>()
        Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    Log.i("DshNodeProbe", "node: $line")
                    lines.put(line)
                }
            }
        }.apply { isDaemon = true }.start()

        var readyLine: String? = null
        val readyDeadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < readyDeadline) {
            val line = lines.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (line.startsWith("HOST_READY ")) {
                readyLine = line
                break
            }
        }
        if (readyLine == null) {
            process.destroyForcibly()
            return JSONObject()
                .put("ok", false)
                .put("error", "no HOST_READY in 20s")
                .put("elapsedMs", System.currentTimeMillis() - startedAt)
                .toString(2)
        }
        val hostReadyMs = System.currentTimeMillis() - spawnAt
        val port = readyLine.split(' ').getOrNull(1)?.toIntOrNull()
            ?: run {
                process.destroyForcibly()
                return JSONObject().put("ok", false).put("error", "bad HOST_READY: $readyLine").toString(2)
            }

        var hello: JSONObject? = null
        var toolResult: JSONObject? = null
        val helloLatch = CountDownLatch(1)
        val resultLatch = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val socket = client.newWebSocket(
            Request.Builder().url("ws://127.0.0.1:$port/").build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (obj.optString("op")) {
                        "host.hello" -> { hello = obj; helloLatch.countDown() }
                        "tool.result" -> { toolResult = obj; resultLatch.countDown() }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w("DshNodeProbe", "ws failure: ${t.message}")
                    helloLatch.countDown()
                    resultLatch.countDown()
                }
            },
        )
        val helloOk = helloLatch.await(10, TimeUnit.SECONDS)
        val helloMs = System.currentTimeMillis() - spawnAt
        val pid = hello?.optInt("pid") ?: -1
        val rssKb = if (pid > 0) readVmRssKb(pid) else -1

        val callId = "p0-${System.currentTimeMillis()}"
        if (helloOk && hello != null) {
            socket.send(
                JSONObject()
                    .put("op", "tool.invoke")
                    .put("id", callId)
                    .put("name", "todo_write")
                    .put("args", JSONObject().put(
                        "todos",
                        org.json.JSONArray().put(
                            JSONObject().put("content", "p0 bridge probe").put("status", "in_progress"),
                        ),
                    ))
                    .toString(),
            )
        }
        val toolOk = resultLatch.await(20, TimeUnit.SECONDS)
        val toolMs = System.currentTimeMillis() - spawnAt

        socket.send(JSONObject().put("op", "host.stop").toString())
        socket.close(1000, "probe done")
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()

        return JSONObject()
            .put("ok", helloOk && toolOk && toolResult?.optBoolean("ok") == true)
            .put("nodeVersion", hello?.optString("version"))
            .put("hostBootMs", hello?.optLong("bootMs") ?: -1)
            .put("hostReadyMs", hostReadyMs)
            .put("spawnToHelloMs", helloMs)
            .put("spawnToToolResultMs", toolMs)
            .put("rssKb", rssKb)
            .put("pid", pid)
            .put("hello", hello)
            .put("toolResult", toolResult)
            .put("elapsedMs", System.currentTimeMillis() - startedAt)
            .toString(2)
    }

    private fun ProcessBuilder.nodeEnv(libDir: File, nativeDir: File, home: File, tmp: File) {
        environment().apply {
            put("LD_LIBRARY_PATH", "${libDir.absolutePath}:${nativeDir.absolutePath}")
            put("HOME", home.absolutePath)
            put("TMPDIR", tmp.absolutePath)
            put("PATH", "/system/bin")
        }
    }

    private fun readVmRssKb(pid: Int): Long = runCatching {
        File("/proc/$pid/status").readLines()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toLongOrNull() ?: -1L
    }.getOrDefault(-1L)

    private fun write(outPath: String, text: String) {
        runCatching { File(outPath).apply { parentFile?.mkdirs() }.writeText(text) }
        Log.i("DshNodeProbe", text.take(6000))
    }
}
