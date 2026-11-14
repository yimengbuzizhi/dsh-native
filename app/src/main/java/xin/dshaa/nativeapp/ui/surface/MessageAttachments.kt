package xin.dshaa.nativeapp.ui.surface

import java.util.Locale

/**
 * M85-B3：消息里的「附件块」识别（与 legacy `QuotedAttachment` 同一套格式）。
 *
 * 用户发文件时宿主把附件拼进用户消息的 text 块（`AppViewModel.send`），一行的形状是：
 * - `[附件: name]\n\n<全文>`                  文本内联（≤ 512KB 的文本文件）
 * - `<attachment id="…" filename="…" …/>`     路径引用（二进制/超大文件，模型用 read 读）
 *
 * legacy 聊天页把这两种块折成「文件名 + 大小 · 行数 · 点开看原文」的 chip；
 * Surface 壳没有聊天语义，所以这里只把**宿主自己产出**的附件格式解析成通用行数据
 * （[SurfaceRow.Attachment]），壳再用普通原语拼卡片和展开态。
 */
internal data class ParsedAttachment(
    val name: String,
    val detail: String,
    /** 内联正文；路径引用没有正文（null）。 */
    val body: String?,
)

internal fun parseMessageAttachment(text: String): ParsedAttachment? {
    val trimmed = text.trimStart()
    if (trimmed.startsWith("[附件: ")) {
        val close = trimmed.indexOf(']')
        if (close <= 0) return null
        val name = trimmed.substring("[附件: ".length, close).trim().ifBlank { "附件" }
        val body = trimmed.substring(close + 1).trimStart('\n', ' ', '\t')
        if (body.isEmpty()) return null
        val lines = body.count { it == '\n' } + 1
        return ParsedAttachment(
            name = name,
            detail = "${formatAttachmentBytes(body.toByteArray().size.toLong())} · $lines 行 · 点开看原文",
            body = body,
        )
    }
    if (trimmed.startsWith("<attachment ")) {
        val filename = ATTRIBUTE("filename").find(trimmed)?.groupValues?.get(1).orEmpty()
        val size = ATTRIBUTE("size").find(trimmed)?.groupValues?.get(1)?.toLongOrNull()
        val sizeLabel = size?.let { formatAttachmentBytes(it) + " · " }.orEmpty()
        return ParsedAttachment(
            name = filename.ifBlank { "附件" },
            detail = "${sizeLabel}已复制到工作区（模型用 read 读取）",
            body = null,
        )
    }
    return null
}

private fun ATTRIBUTE(key: String) = Regex("$key=\"([^\"]*)\"")

internal fun formatAttachmentBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
