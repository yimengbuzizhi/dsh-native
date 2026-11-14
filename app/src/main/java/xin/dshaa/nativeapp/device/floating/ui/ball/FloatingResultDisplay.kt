package xin.dshaa.nativeapp.device.floating.ui.ball

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xin.dshaa.core.model.BlockType
import xin.dshaa.core.model.MessageRole
import xin.dshaa.nativeapp.chat.ChatMirror
import xin.dshaa.nativeapp.device.floating.BubbleStyle
import xin.dshaa.nativeapp.device.floating.FloatingSlots

/**
 * Operit `FloatingResultDisplay.kt:24-56` 的移植：
 * 最后一条 AI 纯文本 → 白底 12dp 圆角气泡；点击回 BALL。没有则显示「你好」。
 *
 * M84-D 气泡插口：样式由 [style]（插件提供）覆盖；正文可被 `FloatingSlots.bubbleText` 覆盖；
 * 点击是否消费由 [FloatingChatWindow] 的 `FloatingSlots.handleClick(BUBBLE)` 决定。
 */
@Composable
fun FloatingResultDisplay(
    onBackToBall: () -> Unit,
    style: BubbleStyle? = null,
) {
    val messages = ChatMirror.state?.messages.orEmpty()
    val lastAi = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
    val defaultText = lastAi?.content
        ?.filter { it.type == BlockType.TEXT }
        ?.joinToString("\n") { it.text }
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: "你好"
    val text = FloatingSlots.bubbleText(defaultText) ?: defaultText
    val look = style ?: BubbleStyle()

    Box(
        modifier = Modifier
            .background(Color.Transparent)
            .clickable { onBackToBall() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(look.container, RoundedCornerShape(look.cornerDp.dp))
                .clickable { onBackToBall() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text,
                color = look.content,
                fontSize = 16.sp,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
