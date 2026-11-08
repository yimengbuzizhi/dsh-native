package xin.dshaa.core.parity

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.decodeBase64
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

data class ReplayRequest(
    val seq: Int,
    val method: String,
    val path: String,
    val bodySha256: String,
    val bodyBytes: Int,
    val fixtureIndex: Int,
    val matched: Boolean,
)

/**
 * 回放器（门 2 对拍台 · 回放侧）。
 *
 * 一个只听 `127.0.0.1` 的极简 HTTP/1.1 服务器：
 * - 默认**按序**返回 fixture（第 1 个请求 → 第 1 个捕获），`strictMatch` 时按 `sha256(request body)` 匹配；
 * - 每次 `write` 后 `flush()`，**保持录制时的 chunk 边界**（docs/02 §八.1）；
 * - `realTime` 按录制时的相对时间重放（验证中断/超时用）；
 * - 永远 `Connection: close`，避免 keep-alive 状态影响下一次请求。
 *
 * 没有用 NanoHTTPD：只需要一条 POST 路径 + 一个控制端点，手写 60 行比引依赖更可控。
 */
class ReplayServer(
    captures: List<SseCapture>,
    private val port: Int = DEFAULT_PORT,
    private val realTime: Boolean = false,
    private val strictMatch: Boolean = false,
    private val loops: Int = 1,
    private val bindHost: String = "127.0.0.1",
) : Closeable {

    val fixtures: List<SseCapture> = captures
    private val bySha = captures.mapNotNull { c -> c.header.requestSha256?.let { it to c } }.toMap()
    private val cursor = AtomicInteger(0)
    private val capacity = if (strictMatch) Int.MAX_VALUE else fixtures.size * loops.coerceAtLeast(1)
    private val server = ServerSocket()
    private val pool: ExecutorService =
        Executors.newCachedThreadPool { r -> Thread(r, "parity-replay").apply { isDaemon = true } }

    val requests: MutableList<ReplayRequest> = Collections.synchronizedList(mutableListOf())
    val problems: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var actualPort: Int = port
        private set

    @Volatile
    private var closed = false

    fun start(): ReplayServer {
        server.reuseAddress = true
        server.bind(InetSocketAddress(bindHost, port))
        actualPort = server.localPort
        thread(isDaemon = true, name = "parity-accept") { acceptLoop() }
        return this
    }

    val servedCount: Int get() = requests.size

    fun statsJson(): String = buildJsonObject {
        put("served", requests.size)
        put("fixtures", fixtures.size)
        put("loops", loops)
        put("strict", strictMatch)
        put("realTime", realTime)
        put("problems", problems.size)
    }.toString()

    private fun acceptLoop() {
        while (!closed) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                if (!closed) problems += "accept: ${e.message}"
                break
            }
            pool.execute {
                runCatching { handle(socket) }.onFailure { problems += "handle: ${it.message}" }
                runCatching { socket.close() }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        val input = socket.getInputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        if (lines.isEmpty() || lines.first().isBlank()) return
        val requestLine = lines.first().split(" ")
        val method = requestLine.getOrElse(0) { "" }
        val path = requestLine.getOrElse(1) { "/" }
        val headers = mutableMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val actualBody = if (read == length) body else body.copyOf(read)

        if (path == "/__parity/stats") {
            respondJson(socket, 200, statsJson())
            return
        }
        if (path == "/__parity/reset") {
            // 每次 run 前把夹具游标归零：重试/多次 run 都不会错位
            cursor.set(0)
            problems.clear()
            respondJson(socket, 200, """{"reset":true,"fixtures":${fixtures.size}}""")
            return
        }
        if (path == "/__parity/requests") {
            val body = buildJsonObject {
                put("served", requests.size)
                put("requests", buildJsonArray {
                    requests.forEach { r ->
                        add(buildJsonObject {
                            put("seq", r.seq)
                            put("fixture", r.fixtureIndex)
                            put("matched", r.matched)
                            put("bytes", r.bodyBytes)
                            put("sha256", r.bodySha256)
                        })
                    }
                })
            }.toString()
            respondJson(socket, 200, body)
            return
        }
        if (method != "POST") {
            respondJson(socket, 405, """{"error":"only POST is replayed"}""")
            return
        }

        val sha = ParityJson.sha256(actualBody)
        val seq = requests.size + 1
        val fixture: SseCapture
        val fixtureIndex: Int
        if (strictMatch) {
            val hit = bySha[sha]
            if (hit == null) {
                val index = cursor.getAndIncrement()
                requests += ReplayRequest(seq, method, path, sha, actualBody.size, index, false)
                problems += "未匹配到 fixture（request #$seq sha=${sha.take(12)}…）"
                respondJson(socket, 409, """{"error":"no fixture matches request sha256","seq":$seq,"sha256":"$sha"}""")
                return
            }
            fixture = hit
            fixtureIndex = fixtures.indexOf(hit)
        } else {
            val index = cursor.getAndIncrement()
            // loops>1 时按序循环使用同一批夹具：一次起服即可跑多次 run
            val slot = if (index < capacity) index % fixtures.size else -1
            val hit = if (slot >= 0) fixtures[slot] else null
            fixtureIndex = slot
            requests += ReplayRequest(seq, method, path, sha, actualBody.size, slot, hit != null)
            if (hit == null) {
                problems += "fixture 用尽（request #$seq，容量 $capacity）"
                respondJson(socket, 409, """{"error":"fixture exhausted","seq":$seq,"capacity":$capacity}""")
                return
            }
            fixture = hit
        }

        val out = BufferedOutputStream(socket.getOutputStream())
        println("PARITY_REQ #$seq fixture=$fixtureIndex sha=${sha.take(12)} bytes=${actualBody.size}")
        System.out.flush()
        val headBytes = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream; charset=utf-8\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Connection: close\r\n")
            append("X-Parity-Fixture: $fixtureIndex\r\n")
            append("X-Parity-Seq: $seq\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
        out.write(headBytes)
        out.flush()

        var previousMs = 0L
        fixture.chunks.forEach { chunk ->
            if (realTime) {
                val delta = (chunk.tMs - previousMs).coerceAtLeast(0)
                if (delta > 0) Thread.sleep(delta)
            }
            previousMs = chunk.tMs
            val bytes = chunk.b64.decodeBase64()?.toByteArray() ?: ByteArray(0)
            if (bytes.isNotEmpty()) {
                out.write(bytes)
                // 逐 chunk flush：合并写会让流式时序判据失效
                out.flush()
            }
        }
        out.flush()
    }

    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream(1024)
        var state = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            out.write(b)
            state = when (state) {
                0 -> if (b == CR) 1 else 0
                1 -> if (b == LF) 2 else if (b == CR) 1 else 0
                2 -> if (b == CR) 3 else 0
                else -> if (b == LF) return out.toString("UTF-8") else 0
            }
            if (out.size() > MAX_HEAD) throw IllegalStateException("请求头超过 $MAX_HEAD 字节")
        }
    }

    private fun respondJson(socket: Socket, status: Int, json: String) {
        val body = json.toByteArray(Charsets.UTF_8)
        val out = BufferedOutputStream(socket.getOutputStream())
        val reason = when (status) {
            200 -> "OK"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            else -> "Error"
        }
        out.write(
            ("HTTP/1.1 $status $reason\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8),
        )
        out.write(body)
        out.flush()
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        pool.shutdownNow()
    }

    companion object {
        const val DEFAULT_PORT = 18080
        private const val CR = '\r'.code
        private const val LF = '\n'.code
        private const val MAX_HEAD = 64 * 1024

        /** 从目录读全部捕获（按文件名排序，就是请求顺序）。 */
        fun fromDirectory(
            dir: java.io.File,
            port: Int = DEFAULT_PORT,
            realTime: Boolean = false,
            loops: Int = 1,
        ): ReplayServer =
            ReplayServer(SseCapture.readAll(dir), port = port, realTime = realTime, loops = loops).start()
    }
}
