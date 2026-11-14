package xin.dshaa.nativeapp.ui.surface

import android.graphics.BitmapFactory
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image as ImageIcon
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xin.dshaa.core.plugins.SurfaceNode
import xin.dshaa.core.plugins.SurfaceTree
import xin.dshaa.ui.design.component.MarkdownText
import xin.dshaa.ui.design.theme.DshTheme2

/** 自带点击/输入处理的节点：`onTap` 由它们自己的渲染器消费，不能再包一层。 */
private val INTERACTIVE_TYPES = setOf("button", "chip", "textfield", "switch", "slider")

/**
 * M85-A：Surface 树 → Compose。
 *
 * 宿主只认识 18 个原语；这里不做任何「聊天 / 工具卡」判断，产品语义全在插件返回的树里。
 * 绑定（`bind`）在 Compose 组合里直接读宿主 state（[SurfaceDataSource]），
 * 流式 token 只触发重组、**不跨 JS**；结构变化才由壳宿主 dispatch `shell.state`。
 */
@Composable
fun SurfaceTreeView(
    tree: SurfaceTree,
    data: SurfaceDataSource,
    onAction: (action: String, row: SurfaceRow?, index: Int) -> Unit,
    onDraft: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        SurfaceNodeView(tree.root, data, onAction, onDraft, row = null, index = -1, external = Modifier)
    }
}

/** 壳拿到的只读数据（在组合里读 Compose 状态，不做快照/JS 往返）。 */
interface SurfaceDataSource {
    @Composable fun rows(bind: String, parent: SurfaceRow?): List<SurfaceRow>?

    @Composable fun text(bind: String, row: SurfaceRow?): String

    @Composable fun flag(bind: String, row: SurfaceRow?): Boolean
}

/** 一条列表数据（M85-A/B：消息 / 会话 / 消息里的工具调用）。 */
sealed interface SurfaceRow {
    val id: String
    val index: Int

    data class Message(
        override val id: String,
        override val index: Int,
        val role: String,
        val text: String,
        val thinking: String,
        val streaming: Boolean,
        val error: String?,
        val toolCalls: List<ToolCall>,
        /** M85-B2：消息里的图片（历史消息）与待发附件共用 [Attachment]。 */
        val attachments: List<Attachment> = emptyList(),
    ) : SurfaceRow

    data class Session(
        override val id: String,
        override val index: Int,
        val title: String,
        val current: Boolean,
        val updatedAt: Long,
    ) : SurfaceRow

    /** M86-G：已经持久化、等待回合或安全边界交付的人类消息。 */
    data class QueuedPrompt(
        override val id: String,
        override val index: Int,
        val text: String,
        val delivery: String,
        val status: String,
        val pausedReason: String?,
        val imageCount: Int,
        val fileCount: Int,
        val createdAt: Long,
        val canCancel: Boolean,
    ) : SurfaceRow

    /** 助手消息里的一个 tool_use + 对应 tool_result（M85-B）。 */
    data class ToolCall(
        override val id: String,
        override val index: Int,
        val name: String,
        val arguments: String,
        val result: String,
        val isError: Boolean,
        val running: Boolean,
        val durationMs: Long = 0L,
    ) : SurfaceRow

    /**
     * M85-B2：一张待发/历史图片或一个待发文件。
     *
     * 宿主只给壳「名字 + 本地路径 + 一行说明」，图片缩略图走 `image` 原语的 `bind:item.path`，
     * 壳不需要知道内容寻址附件存储的格式。
     */
    data class Attachment(
        override val id: String,
        override val index: Int,
        val kind: String,
        val name: String,
        val path: String,
        val detail: String,
        /** M85-B3：文本附件的原文（路径引用/图片为空，壳据此显示「点开看原文」）。 */
        val body: String = "",
    ) : SurfaceRow

    /** M85-B3：当前会话的一条定时提醒（宿主只给标题/下次触发文案，壳负责查看/删除）。 */
    data class Schedule(
        override val id: String,
        override val index: Int,
        val title: String,
        val label: String,
        val active: Boolean,
    ) : SurfaceRow

    /** M85-B3：其它会话正在等用户点审批/回答（横幅只显示第一条与总数）。 */
    data class Waiting(
        override val id: String,
        override val index: Int,
        val title: String,
        val toolName: String,
        val summary: String,
        val question: Boolean,
    ) : SurfaceRow

    /** M85-B2-3：`ask_user_question` 的一条问题（plan 审批也走这里，detail 是完整 plan）。 */
    data class Question(
        override val id: String,
        override val index: Int,
        val header: String,
        val question: String,
        val multiSelect: Boolean,
        val detail: String,
        val options: List<Option>,
    ) : SurfaceRow

    /** 一条问题的选项；[selected] 由壳的 `shellView`（`answer:<qid>:<label>`）决定。 */
    data class Option(
        override val id: String,
        override val index: Int,
        val questionId: String,
        val label: String,
        val description: String,
        val selected: Boolean,
    ) : SurfaceRow
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun SurfaceNodeView(
    node: SurfaceNode,
    data: SurfaceDataSource,
    onAction: (String, SurfaceRow?, Int) -> Unit,
    onDraft: (String) -> Unit,
    row: SurfaceRow?,
    index: Int,
    external: Modifier,
) {
    if (node.type == "spacer") {
        val size = node.int("size") ?: 8
        Spacer(external.size(size.dp))
        return
    }
    if (node.type == "divider") {
        Box(external.fillMaxWidth().height(1.dp).background(colorOf(node.string("background"), DshTheme2.colors.line)))
        return
    }
    val visible = node.string("visible")?.let { data.flag(it, row) } ?: true
    if (!visible) return

    val base = external.then(baseModifier(node))
    // M85-B2-2：任何非交互原语都可以带 onTap（壳用它把整行做成折叠头）。
    // M85-B3：同一条通道再加 onLongPress（消息长按引用）；button/chip 已有自己的点击，
    // textfield/switch/slider 是输入控件，不重复包点击。
    val tap = node.onTap
    val longPress = node.onLongPress
    val modifier = if (node.type !in INTERACTIVE_TYPES && (tap != null || longPress != null)) {
        base.combinedClickable(
            onClick = if (tap != null) {
                {
                    android.util.Log.i("DshShell", "tap action=$tap row=${row?.id}")
                    onAction(tap, row, index)
                }
            } else {
                {}
            },
            onLongClick = longPress?.let { action ->
                {
                    android.util.Log.i("DshShell", "longPress action=$action row=${row?.id}")
                    onAction(action, row, index)
                }
            },
        )
    } else {
        base
    }
    when (node.type) {
        "column" -> Column(
            modifier,
            verticalArrangement = Arrangement.spacedBy((node.int("gap") ?: 0).dp),
            horizontalAlignment = alignOf(node.string("align")),
        ) {
            node.children.forEach { child ->
                SurfaceNodeView(
                    child,
                    data,
                    onAction,
                    onDraft,
                    row,
                    index,
                    external = if (child.int("grow") != null) {
                        Modifier.weight((child.int("grow") ?: 1).toFloat())
                    } else {
                        Modifier
                    },
                )
            }
        }

        "row" -> Row(
            modifier,
            horizontalArrangement = justifyOf(node.string("justify"), node.int("gap") ?: 0),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            node.children.forEach { child ->
                SurfaceNodeView(
                    child,
                    data,
                    onAction,
                    onDraft,
                    row,
                    index,
                    external = if (child.int("grow") != null) {
                        Modifier.weight((child.int("grow") ?: 1).toFloat())
                    } else {
                        Modifier
                    },
                )
            }
        }

        "stack" -> Box(modifier) {
            node.children.forEach { child ->
                SurfaceNodeView(child, data, onAction, onDraft, row, index, external = Modifier)
            }
        }

        "scroll" -> Column(modifier.verticalScroll(rememberScrollState())) {
            node.children.forEach { child ->
                SurfaceNodeView(child, data, onAction, onDraft, row, index, external = Modifier)
            }
        }

        "list" -> SurfaceListView(node, data, onAction, onDraft, modifier, row, index)

        "text" -> {
            val text = node.bind?.let { data.text(it, row) } ?: node.string("text").orEmpty()
            val size = node.int("size")
            Text(
                text = text,
                modifier = modifier,
                color = colorOf(node.string("color"), DshTheme2.colors.text),
                fontSize = size?.sp ?: MaterialTheme.typography.bodyLarge.fontSize,
                fontWeight = fontWeightOf(node.string("weight")),
                fontFamily = if (node.boolean("mono") == true) FontFamily.Monospace else null,
                maxLines = node.int("maxLines") ?: Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
                textAlign = textAlignOf(node.string("align")),
            )
        }

        "markdown" -> {
            val text = node.bind?.let { data.text(it, row) } ?: node.string("text").orEmpty()
            MarkdownText(text = text, modifier = modifier, color = colorOf(node.string("color"), DshTheme2.colors.text))
        }

        "image" -> SurfaceImageView(node, data, modifier, row)

        "icon" -> Icon(
            imageVector = iconOf(node.string("name")),
            contentDescription = node.string("contentDescription"),
            modifier = modifier.size((node.int("size") ?: 20).dp),
            tint = colorOf(node.string("color"), DshTheme2.colors.sub),
        )

        "button" -> SurfaceButtonView(node, data, modifier, row, onAction)

        "textfield" -> SurfaceTextFieldView(node, data, modifier, row, onAction, onDraft)

        "switch" -> {
            val checked = node.string("checked")?.let { data.flag(it, row) }
                ?: node.boolean("checked") ?: false
            Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                node.string("label")?.let {
                    Text(it, color = DshTheme2.colors.text, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                }
                Switch(
                    checked = checked,
                    onCheckedChange = { node.onChange?.let { action -> onAction(action, row, index) } },
                )
            }
        }

        "slider" -> {
            val value = node.string("value")?.let { data.text(it, row).toFloatOrNull() } ?: 0f
            Slider(
                value = value.coerceIn(0f, 1f),
                onValueChange = { node.onChange?.let { action -> onAction(action, row, index) } },
                modifier = modifier,
                steps = node.int("steps") ?: 0,
            )
        }

        "progress" -> {
            val label = node.string("label")
            Column(modifier) {
                label?.let {
                    Text(it, color = DshTheme2.colors.sub, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                }
                val value = node.string("value")?.let { data.text(it, row).toFloatOrNull() }
                if (value == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { value.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        "chip" -> SurfaceChipView(node, data, modifier, row, index, onAction)

        "webview" -> SurfaceWebView(node, modifier)
    }
}

@Composable
private fun SurfaceListView(
    node: SurfaceNode,
    data: SurfaceDataSource,
    onAction: (String, SurfaceRow?, Int) -> Unit,
    onDraft: (String) -> Unit,
    modifier: Modifier,
    parentRow: SurfaceRow?,
    parentIndex: Int,
) {
    val bind = node.bind ?: return
    val rows = data.rows(bind, parentRow).orEmpty()
    val empty = node.string("empty")
    if (rows.isEmpty()) {
        if (!empty.isNullOrBlank()) {
            Text(
                empty,
                modifier = modifier.padding(12.dp),
                color = DshTheme2.colors.faint,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }
    val template = node.item ?: return
    val horizontal = node.boolean("horizontal") == true
    // M85-B2-3：`lazy:false` 给「放在 scroll 里的短列表」用（答题卡），避免 LazyColumn 嵌套滚动测量崩溃
    val lazyList = node.boolean("lazy") != false
    val gap = (node.int("gap") ?: 0).dp
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val last = rows.lastOrNull()
    // 结构变化（新消息）与流式文本增长都跟到底部；这是宿主本地滚动，不跨 JS。
    val lastTextLength = (last as? SurfaceRow.Message)?.text?.length ?: 0
    LaunchedEffect(rows.size, last?.id, lastTextLength, horizontal, lazyList) {
        if (lazyList && !horizontal && rows.isNotEmpty() && listState.layoutInfo.totalItemsCount > 0) {
            // 一条消息可能比屏幕高（长回复）：大偏移会被 LazyColumn 夹到内容末尾，跟随流式输出。
            listState.scrollToItem(rows.lastIndex, scrollOffset = 1_000_000)
        }
    }
    if (horizontal) {
        LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
            itemsIndexed(rows, key = { _, item -> item.id }) { idx, item ->
                SurfaceNodeView(template, data, onAction, onDraft, item, idx, Modifier)
            }
        }
    } else if (!lazyList || parentRow != null) {
        // 嵌套在 list item 里的列表（工具卡列表）或显式 lazy:false（答题卡在 scroll 里）：
        // LazyColumn 会拿到无限高约束并崩溃，这里用普通 Column（行数很小，不需要虚拟化）。
        Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
            rows.forEachIndexed { idx, item ->
                SurfaceNodeView(template, data, onAction, onDraft, item, idx, Modifier)
            }
        }
    } else {
        LazyColumn(
            modifier,
            state = listState,
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            itemsIndexed(rows, key = { _, item -> item.id }) { idx, item ->
                SurfaceNodeView(template, data, onAction, onDraft, item, idx, Modifier)
            }
        }
    }
}

@Composable
private fun SurfaceButtonView(
    node: SurfaceNode,
    data: SurfaceDataSource,
    modifier: Modifier,
    row: SurfaceRow?,
    onAction: (String, SurfaceRow?, Int) -> Unit,
) {
    val c = DshTheme2.colors
    val enabled = node.string("enabled")?.let { data.flag(it, row) } ?: true
    val label = node.bind?.let { data.text(it, row) } ?: node.string("label").orEmpty()
    val style = node.string("style") ?: "primary"
    val bg = when (style) {
        "primary" -> c.brand
        "danger" -> c.danger
        "tonal" -> c.brandSoft
        else -> Color.Transparent
    }
    val fg = when (style) {
        "primary", "danger" -> c.onHero
        "tonal" -> c.brand
        else -> c.sub
    }
    val shape = RoundedCornerShape((node.int("corner") ?: 12).dp)
    Row(
        modifier
            .clip(shape)
            .background(if (enabled) bg else c.surfaceAlt)
            .clickable(enabled = enabled) {
                android.util.Log.i("DshShell", "tap action=${node.onTap}")
                node.onTap?.let { action -> onAction(action, row, row?.index ?: -1) }
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val icon = node.string("icon")
        if (!icon.isNullOrBlank()) {
            Icon(iconOf(icon), contentDescription = null, modifier = Modifier.size(16.dp), tint = fg)
            if (label.isNotBlank()) Spacer(Modifier.width(6.dp))
        }
        if (label.isNotBlank()) {
            Text(
                label,
                color = fg,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SurfaceTextFieldView(
    node: SurfaceNode,
    data: SurfaceDataSource,
    modifier: Modifier,
    row: SurfaceRow?,
    onAction: (String, SurfaceRow?, Int) -> Unit,
    onDraft: (String) -> Unit,
) {
    val c = DshTheme2.colors
    val bind = node.bind ?: node.string("value")
    val external = bind?.let { data.text(it, row) }.orEmpty()
    var value by remember(node.key, bind) { mutableStateOf(TextFieldValue(external)) }
    LaunchedEffect(external) {
        if (external != value.text) value = TextFieldValue(external, value.selection)
    }
    val enabled = node.string("enabled")?.let { data.flag(it, row) } ?: true
    val multiline = node.boolean("multiline") == true
    val shape = RoundedCornerShape((node.int("corner") ?: 14).dp)

    BasicTextField(
        value = value,
        onValueChange = { next ->
            value = next
            onDraft(next.text)
            node.onChange?.let { action -> onAction(action, row, row?.index ?: -1) }
        },
        modifier = modifier
            .clip(shape)
            .background(c.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text),
        maxLines = if (multiline) (node.int("maxLines") ?: 6) else 1,
        decorationBox = { inner ->
            Box {
                if (value.text.isEmpty()) {
                    Text(
                        node.string("placeholder").orEmpty(),
                        color = c.faint,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                inner()
            }
        },
        keyboardActions = KeyboardActions(
            onSend = { node.onSubmit?.let { action -> onAction(action, row, row?.index ?: -1) } },
            onDone = { node.onSubmit?.let { action -> onAction(action, row, row?.index ?: -1) } },
        ),
        keyboardOptions = KeyboardOptions(
            imeAction = if (multiline) ImeAction.Default else ImeAction.Send,
        ),
    )
}

@Composable
private fun SurfaceChipView(
    node: SurfaceNode,
    data: SurfaceDataSource,
    modifier: Modifier,
    row: SurfaceRow?,
    index: Int,
    onAction: (String, SurfaceRow?, Int) -> Unit,
) {
    val c = DshTheme2.colors
    val selected = node.string("selected")?.let { data.flag(it, row) } ?: false
    val label = node.bind?.let { data.text(it, row) } ?: node.string("label").orEmpty()
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(if (selected) c.brandSoft else c.surfaceAlt)
            .clickable { node.onTap?.let { action -> onAction(action, row, index) } }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = node.string("icon")
        if (!icon.isNullOrBlank()) {
            Icon(
                iconOf(icon),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (selected) c.brand else c.sub,
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            color = if (selected) c.brand else c.sub,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SurfaceImageView(
    node: SurfaceNode,
    data: SurfaceDataSource,
    modifier: Modifier,
    row: SurfaceRow?,
) {
    // M85-B2：`src` 可以被 `bind` 覆盖（附件缩略图拿的是 `item.path`），壳仍然只读、不写。
    val src = node.bind?.let { data.text(it, row) } ?: node.string("src").orEmpty()
    val shape = RoundedCornerShape((node.int("corner") ?: 8).dp)
    var bitmap by remember(src) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(src) {
        bitmap = if (src.startsWith("/") || src.startsWith("file://")) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val path = src.removePrefix("file://")
                    BitmapFactory.decodeFile(path)
                }.getOrNull()
            }
        } else {
            null
        }
    }
    Box(modifier.clip(shape).background(DshTheme2.colors.surfaceAlt), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            val width = node.int("width")
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = node.string("contentDescription"),
                contentScale = if (node.string("fit") == "cover") ContentScale.Crop else ContentScale.Fit,
                modifier = (if (width != null) Modifier.width(width.dp) else Modifier.fillMaxWidth())
                    .height((node.int("height") ?: 160).dp),
            )
        } else {
            Text(
                if (src.isBlank()) "没有图片" else if (src.startsWith("http")) "图片只支持本地路径" else "图片已丢失",
                color = DshTheme2.colors.faint,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun SurfaceWebView(node: SurfaceNode, modifier: Modifier) {
    val html = node.string("html")
    val url = node.string("url")
    val height = (node.int("height") ?: 300).dp
    if (html.isNullOrBlank() && url.isNullOrBlank()) {
        Text("webview 没有内容", modifier = modifier.padding(12.dp), color = DshTheme2.colors.faint)
        return
    }
    if (!html.isNullOrBlank()) {
        AndroidView(
            modifier = modifier.fillMaxWidth().height(height),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = WebViewClient()
                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }
            },
        )
    } else {
        // M85-A：只开放 html 内容；远程 url 要等壳后端（M85-F）把网络权限/白名单接上。
        Text(
            "webview url 在 M85-F 开放（当前只支持 html）",
            modifier = modifier.padding(12.dp),
            color = DshTheme2.colors.faint,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

// ---- 属性 / 样式小工具 ----

@Composable
private fun baseModifier(node: SurfaceNode): Modifier {
    var modifier: Modifier = Modifier
    node.int("padding")?.let { modifier = modifier.padding(it.dp) }
    node.int("paddingH")?.let { modifier = modifier.padding(horizontal = it.dp) }
    node.int("paddingV")?.let { modifier = modifier.padding(vertical = it.dp) }
    node.int("margin")?.let { modifier = modifier.padding(it.dp) }
    when (node.string("width")) {
        "fill" -> modifier = modifier.fillMaxWidth()
        null, "wrap" -> Unit
        else -> node.int("width")?.let { modifier = modifier.width(it.dp) }
    }
    when (node.string("height")) {
        "fill" -> modifier = modifier.fillMaxSize()
        null, "wrap" -> Unit
        else -> node.int("height")?.let { modifier = modifier.height(it.dp) }
    }
    node.int("maxHeight")?.let { modifier = modifier.heightIn(max = it.dp) }
    node.string("background")?.let { token ->
        val color = colorOf(token, Color.Transparent)
        modifier = modifier.background(color)
    }
    node.int("corner")?.let { modifier = modifier.clip(RoundedCornerShape(it.dp)) }
    return modifier
}

@Composable
private fun colorOf(token: String?, fallback: Color): Color {
    val c = DshTheme2.colors
    return when (token) {
        null, "" -> fallback
        "text" -> c.text
        "sub" -> c.sub
        "faint" -> c.faint
        "brand" -> c.brand
        "brandSoft" -> c.brandSoft
        "danger" -> c.danger
        "dangerSoft" -> c.dangerSoft
        "success" -> c.success
        "successSoft" -> c.successSoft
        "warn" -> c.warn
        "warnSoft" -> c.warnSoft
        "purple" -> c.purple
        "background" -> c.background
        "surface" -> c.surface
        "surfaceAlt" -> c.surfaceAlt
        "line" -> c.line
        "onHero" -> c.onHero
        else -> parseHexColor(token, fallback)
    }
}

private fun parseHexColor(token: String, fallback: Color): Color {
    val text = token.trim()
    if (!text.startsWith("#")) return fallback
    return runCatching {
        val hex = text.removePrefix("#")
        when (hex.length) {
            6 -> Color(0xFF000000 or hex.toLong(16))
            8 -> Color(hex.toLong(16))
            else -> fallback
        }
    }.getOrDefault(fallback)
}

private fun alignOf(token: String?): Alignment.Horizontal = when (token) {
    "center" -> Alignment.CenterHorizontally
    "end" -> Alignment.End
    else -> Alignment.Start
}

private fun textAlignOf(token: String?): TextAlign = when (token) {
    "center" -> TextAlign.Center
    "end" -> TextAlign.End
    else -> TextAlign.Start
}

private fun justifyOf(token: String?, gap: Int): Arrangement.Horizontal = when (token) {
    "between" -> Arrangement.SpaceBetween
    "center" -> Arrangement.Center
    "end" -> Arrangement.End
    else -> if (gap > 0) Arrangement.spacedBy(gap.dp) else Arrangement.Start
}

private fun fontWeightOf(token: String?): FontWeight = when (token) {
    "medium" -> FontWeight.Medium
    "semibold" -> FontWeight.SemiBold
    "bold" -> FontWeight.Bold
    else -> FontWeight.Normal
}

private fun iconOf(name: String?): ImageVector = when (name?.lowercase()) {
    "add", "plus", "new" -> Icons.Rounded.Add
    "close" -> Icons.Rounded.Close
    "check" -> Icons.Rounded.Check
    "menu" -> Icons.Rounded.Menu
    "settings" -> Icons.Rounded.Settings
    "send" -> Icons.Rounded.Send
    "stop" -> Icons.Rounded.Stop
    "refresh" -> Icons.Rounded.Refresh
    "search" -> Icons.Rounded.Search
    "back" -> Icons.Rounded.ArrowBack
    "forward" -> Icons.Rounded.ArrowForward
    "delete" -> Icons.Rounded.Delete
    "edit" -> Icons.Rounded.Edit
    "home" -> Icons.Rounded.Home
    "info" -> Icons.Rounded.Info
    "person" -> Icons.Rounded.Person
    "play" -> Icons.Rounded.PlayArrow
    "history" -> Icons.Rounded.History
    "folder" -> Icons.Rounded.Folder
    "image" -> Icons.Rounded.ImageIcon
    "camera", "photo" -> Icons.Rounded.PhotoCamera
    "screenshot" -> Icons.Rounded.Screenshot
    "expand", "expand_more", "down" -> Icons.Rounded.ExpandMore
    "collapse", "expand_less", "up" -> Icons.Rounded.ExpandLess
    "flag", "goal" -> Icons.Rounded.Flag
    "help", "question" -> Icons.Rounded.HelpOutline
    "build" -> Icons.Rounded.Build
    "warning" -> Icons.Rounded.Warning
    "notifications" -> Icons.Rounded.Notifications
    "share" -> Icons.Rounded.Share
    "more" -> Icons.Rounded.MoreVert
    "chat" -> Icons.Rounded.ChatBubble
    else -> Icons.Rounded.Info
}
