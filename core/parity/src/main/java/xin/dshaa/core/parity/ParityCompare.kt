package xin.dshaa.core.parity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 归一化：把「每次必然不同」的东西抹平，否则永远 FAIL（docs/02 §三）。
 *
 * - 工作区绝对路径 → `<WORKDIR>`
 * - UUID / 会话 ID → `<ID:n>`（同一次 run 内保持一致映射）
 * - 13 位毫秒时间戳 / ISO 时间 → `<TS>`
 * - 易变字段名（createdAt/time/sessionId…）→ `<TS>` / `<SESSION>`
 *
 * **模型输出不归一**：回放夹具固定了输出，归一会掩盖真实差异。
 */
class RunNormalizer(workdir: String? = null) {

    private val workdirs = buildList {
        workdir?.takeIf { it.isNotBlank() }?.let { add(it.trimEnd('/')) }
        add("/data/user/0/xin.dshaa.nativeapp/files/workspace")
    }.distinct()

    private val idMap = HashMap<String, String>()
    private var nextId = 0

    private val uuidRegex =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val epochMillisRegex = Regex("(?<![0-9])1[0-9]{12}(?![0-9])")
    private val isoRegex =
        Regex("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})?")
    private val loopbackBaseUrlRegex =
        Regex("https?://(?:127\\.0\\.0\\.1|localhost|10\\.0\\.2\\.2|\\[::1]):\\d{2,5}")
    private val anyPortRegex = Regex("(https?://[^\\s\"'/]+):\\d{2,5}")

    private val timeKeys = setOf("createdAt", "time", "timestamp", "updatedAt", "startedAt", "endedAt", "mtime")
    private val sessionKeys = setOf("sessionId", "session_id", "session")
    /** 端点属于运行配置（真实 API / 回放器），不属于行为；请求体 sha 已经证明发出去的东西一致。 */
    private val endpointKeys = setOf("baseUrl", "base_url", "endpoint")

    fun normalizeText(input: String): String {
        var out = input
        workdirs.forEach { wd -> if (wd.isNotBlank()) out = out.replace(wd, "<WORKDIR>") }
        // 回放器端口每次可能不同（18080/18081），属于运行配置而不是行为（docs/02 §三）
        out = loopbackBaseUrlRegex.replace(out, "<REPLAY_BASE_URL>")
        out = anyPortRegex.replace(out) { m -> m.groupValues[1] + ":<PORT>" }
        out = uuidRegex.replace(out) { m ->
            val key = m.value.lowercase()
            "<ID:${idMap.getOrPut(key) { (nextId++).toString() }}>"
        }
        out = isoRegex.replace(out, "<TS>")
        out = epochMillisRegex.replace(out, "<TS>")
        return out
    }

    fun normalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            element.forEach { (key, value) ->
                put(
                    key,
                    when {
                        key in timeKeys -> JsonPrimitive("<TS>")
                        key in sessionKeys -> JsonPrimitive("<SESSION>")
                        key in endpointKeys -> JsonPrimitive("<BASEURL>")
                        else -> normalize(value)
                    },
                )
            }
        }
        is JsonArray -> buildJsonArray { element.forEach { add(normalize(it)) } }
        is JsonPrimitive -> if (element.isString) JsonPrimitive(normalizeText(element.content)) else element
    }

    fun normalizeLine(obj: JsonObject): JsonObject = normalize(obj) as JsonObject
}

data class FirstDiff(
    val index: Int,
    val stage: String,
    val path: String,
    val expected: String,
    val actual: String,
    val contextA: List<String>,
    val contextB: List<String>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("stage", stage)
        put("line", index + 1)
        put("path", path)
        put("expected", expected)
        put("actual", actual)
    }
}

data class CompareReport(
    val scenario: String,
    val status: String,
    val linesA: Int,
    val linesB: Int,
    val firstDiff: FirstDiff?,
    val dirA: String,
    val dirB: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("scenario", scenario)
        put("status", status)
        put("linesA", linesA)
        put("linesB", linesB)
        put("dirA", dirA)
        put("dirB", dirB)
        firstDiff?.let { put("firstDiff", it.toJson()) }
    }

    fun text(): String = buildString {
        appendLine("scenario: $scenario")
        appendLine("status:   $status")
        appendLine("lines:    $linesA vs $linesB")
        appendLine("runA:     $dirA")
        appendLine("runB:     $dirB")
        firstDiff?.let { d ->
            appendLine()
            appendLine("first diff @ line ${d.index + 1} [${d.stage}] ${d.path}")
            appendLine("  expected: ${d.expected}")
            appendLine("  actual:   ${d.actual}")
            appendLine("  context A: ${d.contextA.joinToString(" | ")}")
            appendLine("  context B: ${d.contextB.joinToString(" | ")}")
        }
    }
}

/**
 * 比对两个 run 目录（`run.jsonl` 逐行归一后比较），**只报第一个差异**。
 */
object ParityCompare {

    fun compare(dirA: File, dirB: File, workdir: String? = null, scenario: String? = null): CompareReport {
        val a = RunRecorder.read(File(dirA, "run.jsonl"))
        val b = RunRecorder.read(File(dirB, "run.jsonl"))
        val na = RunNormalizer(workdir)
        val nb = RunNormalizer(workdir)
        val normA = a.map { na.normalizeLine(it) }
        val normB = b.map { nb.normalizeLine(it) }

        val diff = firstDiff(normA, normB)
        val name = scenario
            ?: (normA.firstOrNull()?.get("scenario") as? JsonPrimitive)?.content
            ?: dirA.name
        return CompareReport(
            scenario = name,
            status = if (diff == null) "PASS" else "FAIL",
            linesA = normA.size,
            linesB = normB.size,
            firstDiff = diff,
            dirA = dirA.absolutePath,
            dirB = dirB.absolutePath,
        )
    }

    private fun firstDiff(a: List<JsonObject>, b: List<JsonObject>): FirstDiff? {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val left = a.getOrNull(i)
            val right = b.getOrNull(i)
            if (left == null || right == null) {
                return buildDiff(i, a, b, "line.presence", "$.line", left?.toString() ?: "<missing>", right?.toString() ?: "<missing>")
            }
            if (left != right) {
                val found = diffPath("$", left, right) ?: ("$" to (left to right))
                val (path, pair) = found
                val stage = (left["t"] as? JsonPrimitive)?.content ?: "unknown"
                return buildDiff(i, a, b, stage, path, fmt(pair.first), fmt(pair.second))
            }
        }
        return null
    }

    private fun buildDiff(
        index: Int,
        a: List<JsonObject>,
        b: List<JsonObject>,
        stage: String,
        path: String,
        expected: String,
        actual: String,
    ): FirstDiff = FirstDiff(
        index = index,
        stage = stage,
        path = path,
        expected = expected,
        actual = actual,
        contextA = context(a, index),
        contextB = context(b, index),
    )

    private fun context(lines: List<JsonObject>, index: Int): List<String> =
        (maxOf(0, index - 1)..minOf(lines.size - 1, index + 1)).map { i -> fmt(lines[i], 160) }

    private fun fmt(element: JsonElement?, limit: Int = 400): String {
        val text = when (element) {
            null -> "<missing>"
            is JsonPrimitive -> if (element.isString) element.content else element.toString()
            else -> element.toString()
        }
        return if (text.length <= limit) text else text.take(limit) + "…(${text.length})"
    }

    /** 递归找第一处不同，返回 JSON 路径与两边的值。 */
    private fun diffPath(path: String, a: JsonElement, b: JsonElement): Pair<String, Pair<JsonElement, JsonElement>>? {
        if (a is JsonObject && b is JsonObject) {
            val keys = (a.keys + b.keys).sorted()
            keys.forEach { key ->
                diffPath("$path.$key", a[key] ?: JsonNull, b[key] ?: JsonNull)?.let { return it }
            }
            return null
        }
        if (a is JsonArray && b is JsonArray) {
            for (i in 0 until maxOf(a.size, b.size)) {
                diffPath("$path[$i]", a.getOrNull(i) ?: JsonNull, b.getOrNull(i) ?: JsonNull)?.let { return it }
            }
            return null
        }
        return if (a == b) null else path to (a to b)
    }
}
