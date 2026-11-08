package xin.dshaa.core.parity

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.File

/**
 * 一次 run 的行为日志（门 2 比较对象 1–3 + 最终会话内容）。
 *
 * 格式：`<runDir>/run.jsonl`，一行一个 JSON 对象，首行 `{"t":"run.meta",...}`。
 * 类型：
 * - `llm.request`  `{n, sha256, bytes, body}`
 * - `llm.event`    `{n, event, ...}`（每个 LlmEvent 一条，含 index/text/tool 名）
 * - `llm.end`      `{n, events}`
 * - `tool.call`    `{n, name, args, result, isError}`
 * - `turn.end`     `{messages:[{role, blocks:[...]}]}`
 * - `error`        `{message}`
 *
 * 不写 wall clock：比较前的时间戳一律归一成 `<TS>`（docs/02 §八.3）。
 */
class RunRecorder(dir: File) : Closeable {

    val file: File = File(dir, "run.jsonl")
    private val lock = Any()
    private val writer: java.io.BufferedWriter
    private var closed = false

    init {
        dir.mkdirs()
        writer = file.bufferedWriter(Charsets.UTF_8)
    }

    fun record(obj: JsonObject) {
        synchronized(lock) {
            if (closed) return
            writer.write(ParityJson.json.encodeToString(JsonObject.serializer(), obj))
            writer.newLine()
            writer.flush()
        }
    }

    fun record(type: String, block: JsonObjectBuilder.() -> Unit) {
        record(
            buildJsonObject {
                put("t", type)
                block()
            },
        )
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { writer.flush() }
            runCatching { writer.close() }
        }
    }

    companion object {
        fun read(file: File): List<JsonObject> =
            if (!file.isFile) emptyList()
            else file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.mapNotNull { line ->
                runCatching { ParityJson.json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            }
    }
}
