package xin.dshaa.ui.design.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xin.dshaa.ui.design.theme.DshTheme2
import xin.dshaa.ui.design.theme.LiquidLevel
import xin.dshaa.ui.design.theme.LiquidMotion
import xin.dshaa.ui.design.theme.LiquidRadius
import xin.dshaa.ui.design.theme.LiquidTokens
import xin.dshaa.ui.design.theme.LocalLiquidTokens
import xin.dshaa.ui.design.theme.shadowDp

/**
 * 厚透镜玻璃：一次 draw 画完填充 / 镜面 / 侧壁 / 色散 / 发丝线。
 *
 * 绘制顺序（都在 [drawBehind] 里，位于内容之下，文字永远不会被高光糊住）：
 * 1. 雾面渐变填充（上亮下暗，模拟玻璃厚度）
 * 2. 顶部镜面高光带（1/3 高度渐隐）
 * 3. 左右边缘色散（左暖右冷 1.5dp，光穿过玻璃壁时红/蓝分离）
 * 4. 侧壁浓边（inset 2.5dp 的描边，制造「厚壁」错觉）
 * 5. 发丝内线（整圈 0.75dp）
 * 6. 底部回光
 *
 * 不做真实 backdrop-filter：Compose 没有逐帧背景采样，列表里必然掉帧；设计稿本身
 * 也是用「光学暗示」换零成本（镜面硬边 + 侧壁 + 半像素分界线），这里照搬同一套。
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(LiquidRadius.m),
    level: LiquidLevel = LiquidLevel.Card,
    tokens: LiquidTokens = LocalLiquidTokens.current,
    /** 覆盖填充色（主操作按钮 / 选中态），null 用档位默认雾面白 */
    tint: Color? = null,
    /** tint 之上叠的明暗分离强度，0 = 纯色平面 */
    tintGradient: Float = 0.35f,
    /** 是否画环境阴影（嵌套在玻璃里的内层元素关掉，省一次阴影绘制） */
    elevated: Boolean = true,
    /**
     * 下面再铺一层几乎不透明的实底（弹窗 / 二级面板用）。
     * 玻璃本身是半透的，叠在上一级窗口上会「透出下一级」，加上实底才有真正的层级。
     */
    solidBase: Color? = null,
    /**
     * 厚透镜：把背景墙（渐变 + 光斑 + 跟手光源）按卡片位置重画到玻璃里并放大 1.12 倍，
     * 等价于 HTML 的 `backdrop-filter: blur() saturate()` 折射效果。
     * 小胶囊开销不划算，默认 Pill 档关闭。
     */
    // 透镜重画（把背后的墙按卡片位置再画一遍）默认**关闭**：
    // 它每张玻璃每帧都要重绘 3 团径向渐变 + 底色，长列表/滚动时直接把帧率拖垮
    // （用户 2026-11-19「卡顿」）。视觉上它只差一点折射放大，性价比太低。
    lens: Boolean = false,
): Modifier {
    val top: Color
    val mid: Color
    val bottom: Color
    when (level) {
        LiquidLevel.Pill, LiquidLevel.Card -> {
            top = tokens.glassTop
            mid = tokens.glassMid
            bottom = tokens.glassBottom
        }
        LiquidLevel.Panel, LiquidLevel.Hero -> {
            top = tokens.glassStrongTop
            mid = lerp(tokens.glassStrongTop, tokens.glassStrongBottom, 0.42f)
            bottom = tokens.glassStrongBottom
        }
    }
    val fillTop = if (tint == null) {
        top
    } else {
        lerp(tint, Color.White, (if (tokens.isDark) 0.10f else 0.22f) * tintGradient)
    }
    val fillBottom = if (tint == null) {
        bottom
    } else {
        lerp(tint, Color.Black, (if (tokens.isDark) 0.16f else 0.06f) * tintGradient)
    }
    val fillMid = if (tint == null) mid else lerp(fillTop, fillBottom, 0.5f)
    // 卡片在窗口里的位置：只在 draw 阶段读，所以不会引起重组
    val wallLens = LocalWallLens.current
    var rootOrigin by remember { mutableStateOf(Offset.Unspecified) }
    val lensOn = lens && wallLens != null
    val outerShadow = level.shadowDp(tokens.isDark)
    val withShadow = if (elevated && outerShadow > 0.dp) {
        this.shadow(
            elevation = outerShadow,
            shape = shape,
            clip = false,
            ambientColor = tokens.ambient,
            spotColor = tokens.ambient,
        )
    } else {
        this
    }
    return withShadow
        .clip(shape)
        .then(
            if (lensOn) {
                Modifier.onGloballyPositioned { rootOrigin = it.positionInRoot() }
            } else {
                Modifier
            },
        )
        .drawBehind {
            val w = size.width
            val h = size.height
            // 0) 实底（弹窗 / 二级面板 / 抽屉）：先铺一层几乎不透明的墙色，断开与下一级窗口的透视
            solidBase?.let { drawRect(color = it) }
            // 1) 等价于设计稿的 backdrop-filter: blur(3px) saturate(195%)
            //    把背后的墙按本组件的位置重画并稍微放大 —— 玻璃这才真的"透"出流体，而不是糊一层白
            if (lensOn && solidBase == null && rootOrigin != Offset.Unspecified) {
                drawWallIntoLens(
                    lens = wallLens!!,
                    cardOrigin = rootOrigin,
                    cardSize = size,
                    magnify = if (tokens.isDark) 1.16f else 1.10f,
                )
            }
            val outline = shape.createOutline(size, layoutDirection, this)
            val ring = Path().apply { addOutline(outline) }
            // 2) background-color: var(--glass-tint)（小胶囊用更实的 --pill-fill，不上背景采样）
            val base = when {
                tint != null -> lerp(tint, Color.White, (if (tokens.isDark) 0.10f else 0.22f) * tintGradient)
                level == LiquidLevel.Pill -> tokens.pillFill
                else -> tokens.glassTint
            }
            drawRect(color = base)
            // 3) background-image ①：顶面受光带
            //    linear-gradient(168deg, --glass-white-10 0%, --glass-white-low 40%, transparent 68%, --glass-white-low 100%)
            drawRect(
                brush = Brush.linearGradient(
                    0f to tokens.glassTop,
                    0.40f to tokens.glassBottom,
                    0.68f to Color.Transparent,
                    1f to tokens.glassBottom,
                    start = Offset(w * 0.04f, 0f),
                    end = Offset(w * 0.36f, h),
                ),
            )
            // 4) background-image ②：内壁聚光（越靠边越亮，这是"厚度"的来源）
            //    radial-gradient(130% 90% at 50% -12%, --wall-a 0%, transparent 62%)
            drawRect(
                brush = Brush.radialGradient(
                    0f to tokens.wallSide,
                    0.62f to Color.Transparent,
                    center = Offset(w * 0.5f, -h * 0.12f),
                    radius = w * 1.30f,
                ),
            )
            // 5) CSS ::after —— 3dp 侧壁浓边（--rim-width: 3px），小件跳过
            val rimW = 3.dp.toPx()
            if (w > rimW * 6f && h > rimW * 6f) {
                translate(left = rimW * 0.5f, top = rimW * 0.5f) {
                    drawPath(
                        path = ring,
                        brush = Brush.radialGradient(
                            0f to Color.Transparent,
                            0.50f to Color.Transparent,
                            0.88f to tokens.wallSide,
                            1f to tokens.rimBottom,
                            center = Offset(w * 0.5f, h * 0.5f),
                            radius = maxOf(w, h) * 0.62f,
                        ),
                        style = Stroke(width = rimW),
                    )
                }
            }
            // 6) inset 0 1px --spec-top / inset 0 -1px --spec-bottom / inset 0 0 0 .5px --edge-line
            val hairPx = 1.dp.toPx()
            clipPath(ring) {
                drawRect(color = tokens.rimTop, topLeft = Offset(0f, 0f), size = Size(w, hairPx))
                drawRect(color = tokens.rimBottom, topLeft = Offset(0f, h - hairPx), size = Size(w, hairPx))
                drawRect(
                    color = tokens.hairline,
                    topLeft = Offset(hairPx * 0.5f, hairPx * 0.5f),
                    size = Size(w - hairPx, h - hairPx),
                    style = Stroke(width = hairPx),
                )
            }
            // 7) CSS ::before —— 1px 镜面环
            //    linear-gradient(160deg, --spec-top 0%, rgba(255,255,255,.12) 30%, rgba(255,255,255,.02) 56%, --spec-bottom 100%)
            translate(left = hairPx * 0.5f, top = hairPx * 0.5f) {
                drawPath(
                    path = ring,
                    brush = Brush.linearGradient(
                        0f to tokens.rimTop,
                        0.30f to Color.White.copy(alpha = 0.12f),
                        0.56f to Color.White.copy(alpha = 0.02f),
                        1f to tokens.rimBottom,
                        start = Offset(0f, 0f),
                        end = Offset(w * 0.34f, h),
                    ),
                    style = Stroke(width = hairPx),
                )
            }
        }
}

/**
 * 二级面板玻璃：实底 + 高光玻璃。
 * 用于弹窗、底部弹层、二级页面这些「叠在上一级之上」的表面 —— 玻璃照旧，
 * 但下面垫了一层实底，所以不会再透出下面那一级。
 */
@Composable
fun Modifier.liquidPanelGlass(
    shape: Shape = RoundedCornerShape(LiquidRadius.m),
    level: LiquidLevel = LiquidLevel.Panel,
    tokens: LiquidTokens = LocalLiquidTokens.current,
    tint: Color? = null,
    tintGradient: Float = 0.35f,
    elevated: Boolean = true,
): Modifier = this.liquidGlass(
    shape = shape,
    level = level,
    tokens = tokens,
    tint = tint,
    tintGradient = tintGradient,
    elevated = elevated,
    solidBase = tokens.panelBase,
)

/**
 * 错峰入场：第 [index] 个元素延后 `staggerMs × index`，340ms 内从「下方 10dp + 透明」升到原位。
 * 列表首屏 / 抽屉分组 / 设置分组用它，和设计稿 13.5 同一套参数。
 */
@Composable
fun Modifier.liquidEnter(index: Int = 0, enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val delayMs = (index.coerceAtLeast(0) * LiquidMotion.staggerMs).toLong()
        if (delayMs > 0) delay(delayMs)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = LiquidMotion.enterMs, easing = LiquidMotion.enterEasing),
        )
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 10.dp.toPx()
    }
}

/**
 * 会自己收按压状态的点击区：按下 0.976 缩放 + 涟漪 + 90ms 跟手。
 * 所有可点元素都走这个，避免每处各写一遍 interactionSource。
 */
@Composable
fun Modifier.liquidClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    scale: Float = 0.976f,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val current by animateFloatAsState(
        targetValue = if (pressed && enabled) scale else 1f,
        animationSpec = LiquidMotion.press,
        label = "liquid-click",
    )
    return this
        .graphicsLayer {
            scaleX = current
            scaleY = current
        }
        .clickable(
            interactionSource = interaction,
            indication = ripple(bounded = true, color = Color.White),
            enabled = enabled,
            onClickLabel = onClickLabel,
            onClick = onClick,
        )
}

/** 玻璃容器：填充 + 光学边 + 可选点击 + 按压缩放。内容画在玻璃之上。 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(LiquidRadius.m),
    level: LiquidLevel = LiquidLevel.Card,
    tint: Color? = null,
    tintGradient: Float = 0.35f,
    elevated: Boolean = true,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = LocalLiquidTokens.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && onClick != null) 0.978f else 1f,
        animationSpec = LiquidMotion.press,
        label = "glass-press",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .liquidGlass(
                shape = shape,
                level = level,
                tokens = tokens,
                tint = tint,
                tintGradient = tintGradient,
                elevated = elevated,
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = ripple(
                            bounded = true,
                            color = if (tokens.isDark) Color.White else Color.Black,
                        ),
                        enabled = enabled,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        content = content,
    )
}

/** 主操作按钮：品牌色玻璃。一屏只允许一个主操作（b-end 规范）。 */
@Composable
fun GlassPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = DshTheme2.colors
    val tokens = LocalLiquidTokens.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.966f else 1f,
        animationSpec = LiquidMotion.press,
        label = "primary-press",
    )
    val brand = if (enabled) c.brand else c.faint
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .liquidGlass(
                shape = RoundedCornerShape(LiquidRadius.s),
                level = LiquidLevel.Pill,
                tokens = tokens,
                tint = brand,
                tintGradient = 0.9f,
            )
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true, color = Color.White),
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            leading?.invoke()
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }
    }
}

/** 描一圈轮廓：Compose 没有统一的 drawOutline 入口，这里按 Outline 三种形态分派。 */
private fun DrawScope.strokeOutline(outline: Outline, color: Color, width: Float) {
    val style = Stroke(width = width)
    when (outline) {
        is Outline.Rectangle -> drawRect(
            color = color,
            topLeft = outline.rect.topLeft,
            size = outline.rect.size,
            style = style,
        )
        is Outline.Rounded -> {
            val path = Path().apply { addRoundRect(outline.roundRect) }
            drawPath(path = path, color = color, style = style)
        }
        is Outline.Generic -> drawPath(path = outline.path, color = color, style = style)
    }
}

/** 玻璃分隔线：0.75dp 渐变，两端淡出，比 1dp 实线轻一档。 */
@Composable
fun GlassDivider(
    modifier: Modifier = Modifier,
    horizontal: Boolean = true,
    inset: Dp = 0.dp,
) {
    val tokens = LocalLiquidTokens.current
    Box(
        modifier
            .then(if (horizontal) Modifier.height(0.75.dp) else Modifier.width(0.75.dp))
            .drawBehind {
                val brush = if (horizontal) {
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.15f to tokens.hairline,
                        0.85f to tokens.hairline,
                        1f to Color.Transparent,
                        startX = 0f,
                        endX = size.width,
                    )
                } else {
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.15f to tokens.hairline,
                        0.85f to tokens.hairline,
                        1f to Color.Transparent,
                        startY = 0f,
                        endY = size.height,
                    )
                }
                drawRect(brush)
            },
    )
}