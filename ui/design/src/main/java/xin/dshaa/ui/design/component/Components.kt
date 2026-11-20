package xin.dshaa.ui.design.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xin.dshaa.ui.design.theme.DshTheme2
import xin.dshaa.ui.design.theme.LiquidLevel
import xin.dshaa.ui.design.theme.LiquidRadius

/**
 * 玻璃卡片：白雾面 + 镜面高光 + 侧壁 + 发丝线，18dp 圆角。
 * 签名与旧版 `DshCard` 一致，全站调用点不用改。
 */
@Composable
fun DshCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(LiquidRadius.m),
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        level = LiquidLevel.Card,
        onClick = onClick,
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = DshTheme2.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = c.text,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.bodySmall,
                color = c.sub,
                modifier = Modifier
                    .liquidClickable(enabled = onAction != null) { onAction?.invoke() }
                    .clip(RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/** 状态点：运行中脉冲（加速版，1600ms 一圈）。 */
@Composable
fun StatusDot(color: Color, pulse: Boolean = false, size: Dp = 8.dp) {
    val transition = rememberInfiniteTransition(label = "dot")
    val halo = if (pulse) {
        transition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1.7f,
            animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart),
            label = "halo",
        )
    } else {
        null
    }
    Box(contentAlignment = Alignment.Center) {
        if (halo != null) {
            Box(
                Modifier
                    .size(size)
                    .scale(halo.value)
                    .alpha((1.6f - halo.value).coerceIn(0f, 0.35f))
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Box(Modifier.size(size).clip(CircleShape).background(color))
    }
}

/** 快捷入口方块：一整块玻璃，内部图标用色块玻璃，按下有缩放反馈。 */
@Composable
fun QuickTile(
    icon: ImageVector,
    label: String,
    tint: Color,
    bg: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = DshTheme2.colors
    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(LiquidRadius.s),
        level = LiquidLevel.Pill,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.height(7.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.text)
        }
    }
}

/** 设置/能力行：左图标 + 标题副标题 + 右侧值/状态。 */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    iconText: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = DshTheme2.colors.brand,
    iconBg: Color = DshTheme2.colors.brandSoft,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = DshTheme2.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.liquidClickable { onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            } else if (iconText != null) {
                Text(
                    iconText,
                    style = MaterialTheme.typography.titleMedium,
                    color = iconTint,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.sub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        trailing?.invoke()
    }
}

/** 胶囊 chip：玻璃底，选中时换成品牌色玻璃。 */
@Composable
fun DshChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = DshTheme2.colors
    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(LiquidRadius.pill),
        level = LiquidLevel.Pill,
        tint = if (selected) c.brand.copy(alpha = if (c.isDark) 0.55f else 0.85f) else null,
        tintGradient = 0.7f,
        elevated = false,
        onClick = onClick,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.White else c.sub,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
fun HeroBrush(): Brush = Brush.linearGradient(
    colors = listOf(
        DshTheme2.colors.heroStart,
        DshTheme2.colors.heroMid,
        DshTheme2.colors.heroEnd,
    ),
)
