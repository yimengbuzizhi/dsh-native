package xin.dshaa.nativeapp.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * 一条被引用的附件（M74）：聊天页只显示摘要，点开看原文；模型侧内容不变。
 *
 * 2026-11-11 起从 `ChatScreen.kt` 抽出来，主界面与悬浮窗共用（用户反馈：悬浮窗聊天页
 * 里引用包/文件仍铺开全文，只有主界面折成了 chip）。
 */
internal class QuotedAttachment(
    val name: String,
    val sizeLabel: String,
    /** null = 只有路径引用（正文在模型侧用 read 读），没有可展开的原文。 */
    val body: String?,
)

/**
 * 识别发送时拼进用户消息的附件块（`AppViewModel.send` 的两种形状）：
 * - `[附件: name]\n\n<全文>`（文本内联/插件包/记忆引用）
 * - `<attachment id="…" filename="…" type="…" size="…"/>`（按路径引用，模型用工具读）
 * 普通文本返回 null。
 */
internal fun parseQuotedAttachment(text: String): QuotedAttachment? {
    val trimmed = text.trimStart()
    if (trimmed.startsWith("[附件: ")) {
        val close = trimmed.indexOf(']')
        if (close <= 0) return null
        val name = trimmed.substring("[附件: ".length, close).trim()
        val body = trimmed.substring(close + 1).trimStart('\n', ' ', '\t')
        val lines = body.count { it == '\n' } + 1
        val size = body.toByteArray().size.toLong()
        return QuotedAttachment(
            name = name.ifBlank { "附件" },
            sizeLabel = listOf(formatAttachmentBytes(size), "$lines 行").joinToString(" · "),
            body = body.ifBlank { null },
        )
    }
    if (trimmed.startsWith("<attachment ")) {
        val filename = ATTACHMENT_ATTR("filename").find(trimmed)?.groupValues?.get(1).orEmpty()
        val size = ATTACHMENT_ATTR("size").find(trimmed)?.groupValues?.get(1)?.toLongOrNull()
        return QuotedAttachment(
            name = filename.ifBlank { "附件" },
            sizeLabel = if (size != null) {
                "${formatAttachmentBytes(size)} · 已复制到工作区（模型用 read 读取）"
            } else {
                "已复制到工作区（模型用 read 读取）"
            },
            body = null,
        )
    }
    return null
}

private fun ATTACHMENT_ATTR(key: String) = Regex("$key=\"([^\"]*)\"")

internal fun formatAttachmentBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * 引用附件 chip：一行摘要 + 点开看全文（默认折叠）。长引用不再把气泡顶成一大长串，
 * 但内容还在消息里（会话文件、模型请求都没变）。
 */
@Composable
internal fun QuotedAttachmentChip(item: QuotedAttachment) {
    val c = DshTheme2.colors
    val expandable = item.body != null
    var expanded by remember(item.name, item.body?.hashCode()) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface.copy(alpha = if (c.isDark) 0.35f else 0.6f))
            .border(1.dp, c.line.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .let { base -> if (expandable) base.clickable { expanded = !expanded } else base }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.AttachFile, null, tint = c.brandBright, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (expandable) "${item.sizeLabel} · 点开看原文" else item.sizeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expandable) {
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    if (expanded) "收起" else "展开",
                    tint = c.faint,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (expanded) {
            item.body?.let { body ->
                val bodyScroll = rememberScrollState()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .background(c.background.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        body,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.sub,
                        fontFamily = FontFamily.Monospace,
                        // 正文自己滚到头后不把整页聊天列表一起带走（用户 2026-11-11 反馈）。
                        modifier = Modifier
                            .consumeInnerScroll(bodyScroll)
                            .verticalScroll(bodyScroll),
                    )
                }
            }
        }
    }
}
