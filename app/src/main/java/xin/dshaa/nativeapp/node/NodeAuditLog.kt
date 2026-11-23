package xin.dshaa.nativeapp.node

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * Node 桥审计落盘（docs/187 §九 第 3 项）：`files/node-audit.jsonl`，一行一个 JSON。
 *
 * 字段（与方案逐字对齐）：`ts, host:"node", callId, tool, argsSha256, approval:"ask"/"auto",
 * ok, error, ms, pid`。桥调用**前**写 `phase:"start"`（此时还没有 ok/ms），调用**后**写
 * `phase:"done"`（带 ok/error/ms）；两行用 callId 配对，进程崩溃也能看到"发出去没回来"的调用。
 *
 * 只追加、不重排；超过 [MAX_BYTES] 轮转一份 `.1`，避免常驻 App 把私有目录写爆。
 */
class NodeAuditLog(filesDir: File) {

    private val file = File(filesDir, "node-audit.jsonl")

    fun start(callId: String, tool: String, argsSha256: String, approval: String, pid: Int) {
        append(
            buildJsonObject {
                put("ts", System.currentTimeMillis())
                put("phase", "start")
                base(callId, tool, argsSha256, approval, pid)
            }.toString(),
        )
    }

    fun done(
        callId: String,
        tool: String,
        argsSha256: String,
        approval: String,
        ok: Boolean,
        error: String?,
        ms: Long,
        pid: Int,
    ) {
        append(
            buildJsonObject {
                put("ts", System.currentTimeMillis())
                put("phase", "done")
                base(callId, tool, argsSha256, approval, pid)
                put("ok", ok)
                put("error", error?.let(::JsonPrimitive) ?: JsonNull)
                put("ms", ms)
            }.toString(),
        )
    }

    /**
     * docs/190-B：宿主管理动作（`nodehost_*`）审计行。
     *
     * 桥调用用 `start`/`done` 两行按 callId 配对；这类动作没有桥 callId，单独写
     * `phase:"event"`，一行一动作，字段保持 `tool/detail/approval/ok`（不落明文参数）。
     */
    fun event(tool: String, detail: String, ok: Boolean, approval: String) {
        append(
            buildJsonObject {
                put("ts", System.currentTimeMillis())
                put("phase", "event")
                put("host", "node")
                put("tool", tool)
                put("detail", detail.take(MAX_DETAIL_CHARS))
                put("approval", approval)
                put("ok", ok)
            }.toString(),
        )
    }

    /** 一轮调用的公共字段，逐字对应方案里的字段表。 */
    private fun JsonObjectBuilder.base(
        callId: String,
        tool: String,
        argsSha256: String,
        approval: String,
        pid: Int,
    ) {
        put("host", "node")
        put("callId", callId)
        put("tool", tool)
        put("argsSha256", argsSha256)
        put("approval", approval)
        put("pid", pid)
    }

    private fun append(line: String) {
        synchronized(LOCK) {
            runCatching {
                file.parentFile?.mkdirs()
                if (file.isFile && file.length() > MAX_BYTES) rotate()
                file.appendText(line + "\n", Charsets.UTF_8)
            }
        }
    }

    private fun rotate() {
        val backup = File(file.parentFile, file.name + ".1")
        runCatching { backup.delete() }
        runCatching { file.renameTo(backup) }
    }

    companion object {
        private val LOCK = Any()
        private const val MAX_BYTES = 4L * 1024 * 1024
        private const val MAX_DETAIL_CHARS = 2_000

        /** 参数摘要：对 `args.toString()`（JsonObject 规范序）求 SHA-256，不落明文参数。 */
        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
