package xin.dshaa.core.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * M88-G（docs/183 §7）：工具输入的完整性 + 长度上限。
 *
 * 背景：`parseArgs` 对非法/半截 JSON **静默返回 `{}`**，工具拿空参数执行——用户看到的是
 * 「调用工具后停了、没反应」。H1 只收口了 max_tokens 截断（TurnEngine 里 tool_use 整批不执行），
 * 没有覆盖「JSON 半截但没报 max_tokens」与「一次输入过长」两种情况。
 *
 * 拍板（docs/183 §10）：默认上限 64KB，`write` / `edit` 512KB。
 * 2026-11-23 调整（docs/187 §8.4）：512KB 实测仍会撞上，`write` / `edit` 提到 **16MB**
 * （`write` 同时支持 `append=true` 分块写）；普通工具仍 64KB，避免把无关工具的输入放大。
 * 16MB 是防 OOM 的硬护栏，不是「一次写完大文件」的鼓励值——大文件仍然用 `write append` 分块。
 */
object ToolInputGuard {
    /** 普通工具（含插件工具）的默认输入上限。 */
    const val DEFAULT_MAX_BYTES = 64 * 1024

    /** 大内容写入工具（`write` / `edit`）的默认上限。 */
    const val LARGE_MAX_BYTES = 16 * 1024 * 1024

    /** 可注入上限的下界（docs/187 §8.4.3：为高级设置/调试覆盖留口，clamp 到 64KB..64MB）。 */
    const val MIN_LARGE_MAX_BYTES = 64 * 1024

    /** 可注入上限的上界。 */
    const val MAX_LARGE_MAX_BYTES = 64 * 1024 * 1024

    private val LARGE_INPUT_TOOLS = setOf("write", "edit")

    /**
     * 按工具名取上限；未知工具走默认 64KB。
     *
     * [largeLimitBytes] 只对大内容工具生效（默认 [LARGE_MAX_BYTES]），并 clamp 到
     * [MIN_LARGE_MAX_BYTES]..[MAX_LARGE_MAX_BYTES]——上限只有这一个入口，不要散落常量。
     */
    fun limitFor(toolName: String, largeLimitBytes: Int = LARGE_MAX_BYTES): Int =
        if (toolName in LARGE_INPUT_TOOLS) {
            largeLimitBytes.coerceIn(MIN_LARGE_MAX_BYTES, MAX_LARGE_MAX_BYTES)
        } else {
            DEFAULT_MAX_BYTES
        }

    /** UTF-8 字节数（中文内容按真实字节算，不按字符数）。 */
    fun byteSize(raw: String): Int = raw.toByteArray(Charsets.UTF_8).size

    /**
     * 返回给模型的显式错误；null = 本次输入可以执行。
     *
     * 先查长度（不解析超大字符串），再严格解析成 JSON 对象；空串按 `{}` 处理（模型常给空参数）。
     * [largeLimitBytes] 见 [limitFor]。
     */
    fun violation(toolName: String, raw: String, largeLimitBytes: Int = LARGE_MAX_BYTES): String? {
        tooLongMessage(toolName, raw, largeLimitBytes)?.let { return it }
        if (parseStrict(raw) == null) return INVALID_JSON_MESSAGE
        return null
    }

    /** 超限错误文本；没超返回 null。 */
    fun tooLongMessage(toolName: String, raw: String, largeLimitBytes: Int = LARGE_MAX_BYTES): String? {
        val bytes = byteSize(raw)
        val limit = limitFor(toolName, largeLimitBytes)
        if (bytes <= limit) return null
        return "Tool input too long: $bytes bytes > limit $limit bytes (current limit ${humanLimit(limit)}, $toolName); " +
            "this call was not executed. For larger files use write with append chunks: first call append=false to create " +
            "the file, then append=true to add more; for large edits, rewrite in smaller sections."
    }

    /** 严格解析：非法/半截 JSON、非对象 JSON 都返回 null；空白按 `{}`。 */
    fun parseStrict(raw: String): JsonObject? =
        runCatching { Json.parseToJsonElement(raw.ifBlank { "{}" }) as JsonObject }.getOrNull()

    const val INVALID_JSON_MESSAGE =
        "Tool arguments are not valid JSON (truncated or malformed), so this call was not executed. " +
            "If the arguments were truncated, split the content into multiple write/append calls " +
            "(first append=false to create the file, then append=true to add more); " +
            "if it is only a formatting problem, shorten the content and retry."

    private fun humanLimit(limit: Int): String = when {
        limit % (1024 * 1024) == 0 -> "${limit / (1024 * 1024)}MB"
        limit % 1024 == 0 -> "${limit / 1024}KB"
        else -> "$limit bytes"
    }
}
