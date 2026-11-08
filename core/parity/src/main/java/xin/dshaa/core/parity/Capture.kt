package xin.dshaa.core.parity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import xin.dshaa.core.llm.SseTap
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * SSE 捕获（门 2 对拍台 · 录制侧）。
 *
 * 文件格式：JSONL，第一行是头，其余每行一个 chunk：
 * ```
 * {"type":"parity.capture","version":1,"requestSha256":"...","chunkCount":42,"byteCount":913,"note":null}
 * {"seq":0,"tMs":0,"b64":"ZXZlbnQ6..."}
 * ```
 *
 * - `b64` 是**原始字节**，不做任何解码/规范化；`seq` 从 0 连续。
 * - `tMs` 是相对第一条字节的到达时间（毫秒）。**不存绝对时间**——否则回放会被当成差异（docs/02 §八.3）。
 * - chunk 边界 = LlmClient 解析器每次 read() 的边界，回放时逐块写出并 flush。
 */
@Serializable
data class CaptureHeader(
    val type: String = TYPE,
    val version: Int = VERSION,
    @SerialName("requestSha256") val requestSha256: String? = null,
    val requestBytes: Int = 0,
    val chunkCount: Int = 0,
    val byteCount: Int = 0,
    val note: String? = null,
) {
    companion object {
        const val TYPE = "parity.capture"
        const val VERSION = 1
    }
}

@Serializable
data class CaptureChunk(
    val seq: Int,
    /** 相对首字节的到达时间（ms），用于 --real-time 回放。 */
    val tMs: Long,
    val b64: String,
)

class SseCapture(
    val header: CaptureHeader,
    val chunks: List<CaptureChunk>,
) {
    /** 完整响应体字节（已拼回）。 */
    fun bytes(): ByteArray {
        val out = ByteArrayOutputStream(header.byteCount.coerceAtLeast(16))
        chunks.forEach { out.write(it.b64.decodeBase64()?.toByteArray() ?: ByteArray(0)) }
        return out.toByteArray()
    }

    /** 每个 chunk 的字节数，回放与诊断用。 */
    fun chunkSizes(): List<Int> = chunks.map { it.b64.decodeBase64()?.size ?: 0 }

    fun write(file: File) {
        file.parentFile?.mkdirs()
        file.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write(ParityJson.encode(CaptureHeader.serializer(), header)); w.newLine()
            chunks.forEach { c ->
                w.write(ParityJson.encode(CaptureChunk.serializer(), c)); w.newLine()
            }
        }
    }

    companion object {
        fun read(file: File): SseCapture {
            val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            require(lines.isNotEmpty()) { "空捕获文件：${file.path}" }
            val header = ParityJson.decode(CaptureHeader.serializer(), lines.first())
            require(header.type == CaptureHeader.TYPE) { "不是 SSE 捕获文件：${file.path}" }
            require(header.version == CaptureHeader.VERSION) {
                "捕获版本不兼容：${header.version}（当前支持 ${CaptureHeader.VERSION}）"
            }
            val chunks = lines.drop(1).map { ParityJson.decode(CaptureChunk.serializer(), it) }
            return SseCapture(header, chunks)
        }

        fun readAll(dir: File): List<SseCapture> {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
                ?.sortedBy { it.name }
                ?: emptyList()
            return files.map { read(it) }
        }

        /** 由已切好的 chunk 构造（测试/回放直用）。 */
        fun of(
            chunks: List<ByteArray>,
            deltasMs: List<Long> = chunks.map { 0L },
            requestBody: ByteArray? = null,
            note: String? = null,
        ): SseCapture {
            require(chunks.size == deltasMs.size) { "chunks 与 deltas 数量不一致" }
            val encoded = chunks.mapIndexed { i, bytes ->
                CaptureChunk(i, deltasMs[i], bytes.toByteString().base64())
            }
            val header = CaptureHeader(
                requestSha256 = requestBody?.let { ParityJson.sha256(it) },
                requestBytes = requestBody?.size ?: 0,
                chunkCount = encoded.size,
                byteCount = chunks.sumOf { it.size },
                note = note,
            )
            return SseCapture(header, encoded)
        }

        /** 把一段完整响应体按固定大小切成 chunk（只用于造夹具，真实录制走 SseTap）。 */
        fun ofWholeBody(
            body: ByteArray,
            chunkSize: Int = 256,
            requestBody: ByteArray? = null,
            note: String? = null,
        ): SseCapture {
            val parts = ArrayList<ByteArray>()
            var offset = 0
            while (offset < body.size) {
                val end = minOf(offset + chunkSize, body.size)
                parts += body.copyOfRange(offset, end)
                offset = end
            }
            if (parts.isEmpty()) parts += ByteArray(0)
            return of(parts, requestBody = requestBody, note = note)
        }
    }
}

/**
 * 把 LlmClient 的 tap 落到 `<dir>/<prefix>-0001.jsonl`。
 * 一个 tap 对应一次请求；用原子计数保证多请求不串文件。
 */
class SseCaptureRecorder(
    private val dir: File,
    private val prefix: String = "llm",
) {
    private val counter = AtomicInteger(0)

    init {
        dir.mkdirs()
    }

    fun newTap(): SseTap {
        val index = counter.incrementAndGet()
        return FileSseTap(dir, File(dir, fileName(index)), index)
    }

    fun fileFor(index: Int): File = File(dir, fileName(index))

    private fun fileName(index: Int): String = "%s-%04d.jsonl".format(prefix, index)

    /** 已落盘的捕获（按文件名排序）。 */
    fun captures(): List<SseCapture> = SseCapture.readAll(dir)

    private class FileSseTap(
        private val dir: File,
        private val file: File,
        private val index: Int,
    ) : SseTap {
        private val chunks = ArrayList<ByteArray>()
        private val deltas = ArrayList<Long>()
        private var startNs = 0L
        private var request: ByteArray? = null
        private var done = false

        override fun onStart(requestBody: ByteArray) {
            synchronized(this) {
                request = requestBody
                startNs = System.nanoTime()
            }
        }

        override fun onBytes(buffer: ByteArray, offset: Int, length: Int) {
            synchronized(this) {
                if (done || length <= 0) return
                val copy = buffer.copyOfRange(offset, offset + length)
                chunks += copy
                deltas += (System.nanoTime() - startNs) / 1_000_000
            }
        }

        override fun onEnd() {
            synchronized(this) {
                if (done) return
                done = true
            }
            runCatching {
                SseCapture.of(chunks.toList(), deltas.toList(), request, note = "request#$index").write(file)
            }
        }
    }
}

/** 对拍台统一的 JSON 配置（紧凑、宽容、不写 null）。 */
object ParityJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    fun <T> encode(serializer: kotlinx.serialization.SerializationStrategy<T>, value: T): String =
        json.encodeToString(serializer, value)

    fun <T> decode(deserializer: kotlinx.serialization.DeserializationStrategy<T>, text: String): T =
        json.decodeFromString(deserializer, text)

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
