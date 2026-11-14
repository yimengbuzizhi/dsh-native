package xin.dshaa.nativeapp.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import xin.dshaa.ui.design.theme.DshTheme2

/**
 * 输入栏的浮层菜单（M60，逐项照 Operit `AgentModelSelectorPopup` / `AgentExtraSettingsPopup`）。
 *
 * 关键几何（AgentChatInputSection.kt:1550-1595、2289-2334）：
 * - `Popup(alignment = TopStart, focusable)` 里铺一层 `fillMaxSize` 的透明点击层，点外面关闭；
 * - 卡片 `BottomEnd` 对齐，`padding(bottom = 44.dp, end = 12.dp)`（正好浮在输入栏上方）；
 * - 宽 300.dp、`heightIn(max = 420.dp)`、圆角 8.dp、elevation 4.dp，内容 `verticalScroll`；
 * - 分组头是「标题 + 值 + 上下箭头」，点一下原地展开，不是跳页面、也不是底部弹层。
 *
 * 鲸歌原来的 `ModalSheet` 是底部弹层，结构对不上，已由本文件替换。
 */
@Composable
fun ComposerMenuPopup(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return
    val c = DshTheme2.colors
    val dismissInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }
    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = dismissInteraction,
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.BottomEnd,
        ) {
            Card(
                Modifier
                    .padding(bottom = 44.dp, end = 12.dp)
                    .width(300.dp)
                    .heightIn(max = 420.dp)
                    .clickable(
                        interactionSource = cardInteraction,
                        indication = null,
                        onClick = {},
                    ),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = c.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .verticalScroll(rememberScrollState()),
                    content = content,
                )
            }
        }
    }
}

/** 分组头：`图标 标题  值  ⌃/⌄`（Operit `AgentSettingsGroupHeader`，1618-1672）。 */
@Composable
fun ComposerMenuGroupHeader(
    title: String,
    value: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    icon: ImageVector? = null,
) {
    val c = DshTheme2.colors
    val background by animateColorAsState(
        targetValue = if (expanded) c.brand.copy(alpha = 0.08f) else Color.Transparent,
        animationSpec = tween(160),
        label = "group-bg",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .heightIn(min = 38.dp)
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                null,
                tint = if (expanded) c.brand else c.sub.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = if (expanded) c.brandBright else c.text,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = if (expanded) c.brandBright else c.sub,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
            null,
            modifier = Modifier.size(18.dp),
            tint = if (expanded) c.brand else c.sub.copy(alpha = 0.7f),
        )
    }
}

/** 展开动画容器（180ms 展开 / 150ms 收起，避免整卡跳一下）。 */
@Composable
fun ComposerMenuExpansion(expanded: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(animationSpec = tween(180)) + fadeIn(animationSpec = tween(140)),
        exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(animationSpec = tween(110)),
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

/** 展开区：淡色卡片 + 1dp 描边（Operit `AgentToolsPermissionGroupItem` 的展开容器）。 */
@Composable
fun ComposerMenuGroupBody(content: @Composable ColumnScope.() -> Unit) {
    val c = DshTheme2.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = c.surfaceAlt.copy(alpha = 0.55f)),
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, c.line.copy(alpha = 0.6f)),
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), content = content)
        }
    }
}

/** 菜单里的普通行（标题 + 值/说明 + 可选箭头）。 */
@Composable
fun ComposerMenuRow(
    title: String,
    subtitle: String = "",
    value: String = "",
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    color: Color? = null,
) {
    val c = DshTheme2.colors
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = color ?: c.text)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value.isNotBlank()) {
            Text(
                value,
                style = MaterialTheme.typography.labelSmall,
                color = c.brandBright,
                maxLines = 1,
            )
        }
        trailing?.invoke()
    }
}
