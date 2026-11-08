package xin.dshaa.ui.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * 轻量 Markdown（M1 够用版）：代码块 / 标题 / 列表 / 引用 / 粗体 / 行内代码。
 * 流式时未闭合的代码围栏按代码块渲染，避免闪断。
 * M2 换 Operit 的流式渲染器（StreamKmpGraph），这里保留作降级实现。
 */
private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Bullets(val items: List<String>, val ordered: Boolean) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
}

private fun parseMarkdown(src: String): List<MdBlock> {
    val lines = src.replace("\r\n", "\n").split('\n')
    val out = ArrayList<MdBlock>()
    var i = 0
    val para = StringBuilder()

    fun flushPara() {
        if (para.isNotBlank()) {
            out += MdBlock.Paragraph(para.toString().trim())
            para.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        if (line.startsWith("```")) {
            flushPara()
            val lang = line.removePrefix("```").trim()
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].startsWith("```")) {
                code.append(lines[i]).append('\n')
                i++
            }
            if (i < lines.size) i++ // 吃掉闭合围栏；未闭合也照常结束
            out += MdBlock.Code(lang, code.toString().trimEnd('\n'))
            continue
        }
        val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
        if (heading != null) {
            flushPara()
            out += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
            i++
            continue
        }
        // 表格：| a | b |  +  |---| 分隔行 + 数据行
        if (line.trimStart().startsWith("|")) {
            val raw = ArrayList<String>()
            var j = i
            while (j < lines.size && lines[j].trimStart().startsWith("|")) {
                raw += lines[j]
                j++
            }
            val separator = Regex("^\\s*\\|?[\\s:|-]+\\|\\s*$")
            if (raw.size >= 2 && separator.matches(raw[1])) {
                flushPara()
                val header = splitTableRow(raw[0])
                val rows = raw.drop(2).map { splitTableRow(it) }
                out += MdBlock.Table(header, rows)
                i = j
                continue
            }
        }
        if (line.startsWith("> ")) {
            flushPara()
            val q = StringBuilder()
            while (i < lines.size && lines[i].startsWith("> ")) {
                q.append(lines[i].removePrefix("> ")).append('\n')
                i++
            }
            out += MdBlock.Quote(q.toString().trimEnd())
            continue
        }
        val bullet = Regex("^\\s*([-*+]|\\d+[.)])\\s+(.*)$").find(line)
        if (bullet != null) {
            flushPara()
            val ordered = bullet.groupValues[1].first().isDigit()
            val items = ArrayList<String>()
            while (i < lines.size) {
                val m = Regex("^\\s*([-*+]|\\d+[.)])\\s+(.*)$").find(lines[i]) ?: break
                items += m.groupValues[2]
                i++
            }
            out += MdBlock.Bullets(items, ordered)
            continue
        }
        if (line.isBlank()) {
            flushPara()
        } else {
            if (para.isNotEmpty()) para.append('\n')
            para.append(line)
        }
        i++
    }
    flushPara()
    return out
}

private fun splitTableRow(line: String): List<String> =
    line.trim().trimEnd('|').removePrefix("|").split('|').map { it.trim() }

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = DshTheme2.colors.text,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val c = DshTheme2.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> InlineText(block.text, style, color)
                is MdBlock.Heading -> {
                    val size = when (block.level) {
                        1 -> 24.sp
                        2 -> 21.sp
                        3 -> 19.sp
                        else -> 17.sp
                    }
                    InlineText(
                        block.text,
                        style.copy(fontSize = size, fontWeight = FontWeight.SemiBold, lineHeight = (size.value * 1.35f).sp),
                        color,
                    )
                }
                is MdBlock.Code -> CodeBlock(block.lang, block.code)
                is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row {
                            Text(
                                if (block.ordered) "${index + 1}." else "•",
                                style = style,
                                color = c.faint,
                                modifier = Modifier.width(22.dp),
                            )
                            InlineText(item, style, color, Modifier.weight(1f))
                        }
                    }
                }
                is MdBlock.Quote -> Row {
                    Box(Modifier.width(3.dp).height(46.dp).clip(RoundedCornerShape(2.dp)).background(c.line))
                    Spacer(Modifier.width(10.dp))
                    InlineText(block.text, style, c.sub, Modifier.weight(1f))
                }
                is MdBlock.Table -> TableBlock(block.header, block.rows, c)
            }
        }
    }
}

@Composable
private fun TableBlock(header: List<String>, rows: List<List<String>>, c: xin.dshaa.ui.design.theme.DshColors) {
    val columns = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (c.isDark) Color(0xFF101725) else Color(0xFFF7F9FC))
            .padding(vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
            for (col in 0 until columns) {
                Text(
                    header.getOrElse(col) { "" },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = c.text,
                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line2))
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                for (col in 0 until columns) {
                    Text(
                        row.getOrElse(col) { "" },
                        style = MaterialTheme.typography.bodySmall,
                        color = c.sub,
                        modifier = Modifier.weight(1f).padding(end = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, code: String) {
    val c = DshTheme2.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (c.isDark) Color(0xFF0D131E) else Color(0xFF1B1F27)),
    ) {
        if (lang.isNotBlank()) {
            Text(
                lang,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF8FA0BC),
                modifier = Modifier.padding(start = 14.dp, top = 8.dp),
            )
        }
        SelectionContainer {
            Text(
                code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                ),
                color = Color(0xFFD7E2F3),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/** 行内：**粗体**、`代码`、*斜体*。 */
@Composable
private fun InlineText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val c = DshTheme2.colors
    SelectionContainer {
        Text(
            annotated(text, style, color, c),
            style = style,
            color = color,
            modifier = modifier,
        )
    }
}

private fun annotated(text: String, style: TextStyle, color: Color, c: xin.dshaa.ui.design.theme.DshColors): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val link = Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)").find(text, i)
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end < 0) {
                        append(text.substring(i)); i = text.length
                    } else {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    }
                }
                link != null && link.range.first == i -> {
                    // [label](url)：Compose 1.7 的 LinkAnnotation，点按由 Text 自己处理
                    val url = link.groupValues[2]
                    withLink(
                        androidx.compose.ui.text.LinkAnnotation.Url(
                            url = url,
                            styles = androidx.compose.ui.text.TextLinkStyles(
                                style = SpanStyle(color = c.brandBright, fontWeight = FontWeight.Medium),
                            ),
                        ),
                    ) { append(link.groupValues[1]) }
                    i = link.range.last + 1
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end < 0) {
                        append(text.substring(i)); i = text.length
                    } else {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = if (c.isDark) Color(0xFF1C2637) else Color(0xFFEDF1F7),
                                color = color,
                            ),
                        ) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    }
                }
                else -> {
                    val next = listOf(
                        text.indexOf("**", i + 1),
                        text.indexOf('`', i + 1),
                        if (link != null && link.range.first > i) link.range.first else -1,
                    )
                        .filter { it > i }
                        .minOrNull() ?: text.length
                    append(text.substring(i, next))
                    i = next
                }
            }
        }
    }
