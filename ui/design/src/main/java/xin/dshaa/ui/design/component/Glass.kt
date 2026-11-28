package xin.dshaa.ui.design.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xin.dshaa.ui.design.theme.DshTheme2
import xin.dshaa.ui.design.theme.LocalLiquidUi
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
    // 原生界面壳（docs/187 §4.2）：玻璃构图整体退化成「实底色 + 一圈描边 + 原圆角」，
    // 不画高光/色散/阴影/噪点——用户选的「原生」必须和 0.3.5 截图一致。
    if (!LocalLiquidUi.current) {
        val c = DshTheme2.colors
        val fill = tint ?: if (level == LiquidLevel.Pill) c.surfaceAlt else c.surface
        val shadowed = if (elevated && level != LiquidLevel.Pill) {
            this.shadow(elevation = if (level == LiquidLevel.Card) 1.dp else 2.dp, shape = shape)
        } else {
            this
        }
        return shadowed
            .clip(shape)
            .background(fill)
            .border(0.75.dp, c.line, shape)
    }
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
    val baseFill = when {
        tint != null -> fillTop
        level == LiquidLevel.Pill -> tokens.pillFill
        level == LiquidLevel.Panel || level == LiquidLevel.Hero -> lerp(tokens.glassTint, tokens.glassStrongTop, 0.25f)
        else -> tokens.glassTint
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
        .drawWithCache {
            val w = size.width
            val h = size.height
            val hairPx = 1.dp.toPx()
            val rimW = 3.dp.toPx()
            val hasRim = w > rimW * 6f && h > rimW * 6f
            val hairPath = insetOutlinePath(shape, size, layoutDirection, this, hairPx * 0.5f)
            val rimPath = if (hasRim) insetOutlinePath(shape, size, layoutDirection, this, rimW * 0.5f) else null

            val topSheenBrush = Brush.linearGradient(
                0f to fillTop,
                0.40f to fillBottom,
                0.68f to Color.Transparent,
                1f to fillBottom,
                start = Offset(w * 0.04f, 0f),
                end = Offset(w * 0.36f, h.coerceAtLeast(1f)),
            )
            val wallBrush = Brush.radialGradient(
                0f to tokens.wallSide,
                0.62f to Color.Transparent,
                center = Offset(w * 0.5f, -h * 0.12f),
                radius = (w * 1.30f).coerceAtLeast(1f),
            )
            val rimBrush = if (hasRim) {
                Brush.radialGradient(
                    0f to Color.Transparent,
                    0.50f to Color.Transparent,
                    0.88f to tokens.wallSide,
                    1f to tokens.rimBottom,
                    center = Offset(w * 0.5f, h * 0.5f),
                    radius = (maxOf(w, h) * 0.62f).coerceAtLeast(1f),
                )
            } else {
                null
            }
            val specBrush = Brush.linearGradient(
                0f to tokens.rimTop,
                0.30f to Color.White.copy(alpha = 0.12f),
                0.56f to Color.White.copy(alpha = 0.02f),
                1f to tokens.rimBottom,
                start = Offset(0f, 0f),
                end = Offset(w * 0.34f, h.coerceAtLeast(1f)),
            )
            val rimStroke = Stroke(width = rimW)
            val hairStroke = Stroke(width = hairPx)

            onDrawBehind {
                // 0) 实底（弹窗 / 二级面板 / 抽屉）
                solidBase?.let { drawRect(color = it) }
                // 1) 可选厚透镜折射（默认关）
                if (lensOn && solidBase == null && rootOrigin != Offset.Unspecified) {
                    drawWallIntoLens(
                        lens = wallLens!!,
                        cardOrigin = rootOrigin,
                        cardSize = size,
                        magnify = if (tokens.isDark) 1.16f else 1.10f,
                    )
                }
                // 2) 基础雾面填充
                drawRect(color = baseFill)
                // 3) 顶面受光带
                drawRect(brush = topSheenBrush)
                // 4) 内壁聚光
                drawRect(brush = wallBrush)
                // 5) 3dp 侧壁浓边（内缩 rimW/2，四边对称不裁切）
                if (rimPath != null && rimBrush != null) {
                    drawPath(path = rimPath, brush = rimBrush, style = rimStroke)
                }
                // 6) 顶部/底部反光带 + 贴合圆角的发丝线（已由 Modifier.clip(shape) 裁切，免 clipPath 开销）
                drawRect(color = tokens.rimTop, topLeft = Offset(0f, 0f), size = Size(w, hairPx))
                drawRect(color = tokens.rimBottom, topLeft = Offset(0f, h - hairPx), size = Size(w, hairPx))
                drawPath(path = hairPath, color = tokens.hairline, style = hairStroke)
                // 7) 1px 镜面环（内缩 hairPx/2，四边完整闭合）
                drawPath(path = hairPath, brush = specBrush, style = hairStroke)
            }
        }
}

/** 生成向内收缩 [inset] 像素的轮廓路径，避免 Stroke 在右/下边被裁切且圆角保持同心。 */
private fun insetOutlinePath(
    shape: Shape,
    size: Size,
    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
    density: androidx.compose.ui.unit.Density,
    inset: Float,
): Path {
    val outline = shape.createOutline(size, layoutDirection, density)
    return Path().apply {
        when (outline) {
            is Outline.Rectangle -> {
                val r = outline.rect
                if (r.width > inset * 2f && r.height > inset * 2f) {
                    addRect(r.deflate(inset))
                } else {
                    addRect(r)
                }
            }
            is Outline.Rounded -> {
                val rr = outline.roundRect
                if (rr.width > inset * 2f && rr.height > inset * 2f) {
                    addRoundRect(
                        RoundRect(
                            left = rr.left + inset,
                            top = rr.top + inset,
                            right = rr.right - inset,
                            bottom = rr.bottom - inset,
                            topLeftCornerRadius = CornerRadius(
                                (rr.topLeftCornerRadius.x - inset).coerceAtLeast(0f),
                                (rr.topLeftCornerRadius.y - inset).coerceAtLeast(0f),
                            ),
                            topRightCornerRadius = CornerRadius(
                                (rr.topRightCornerRadius.x - inset).coerceAtLeast(0f),
                                (rr.topRightCornerRadius.y - inset).coerceAtLeast(0f),
                            ),
                            bottomRightCornerRadius = CornerRadius(
                                (rr.bottomRightCornerRadius.x - inset).coerceAtLeast(0f),
                                (rr.bottomRightCornerRadius.y - inset).coerceAtLeast(0f),
                            ),
                            bottomLeftCornerRadius = CornerRadius(
                                (rr.bottomLeftCornerRadius.x - inset).coerceAtLeast(0f),
                                (rr.bottomLeftCornerRadius.y - inset).coerceAtLeast(0f),
                            ),
                        ),
                    )
                } else {
                    addRoundRect(rr)
                }
            }
            is Outline.Generic -> addPath(outline.path)
        }
    }
}

/**
 * 统一顶栏表面：
 * - 液态玻璃模式：先 `statusBarsPadding()` 让开系统状态栏，再套悬浮圆角玻璃条（避免状态栏图标压进圆角玻璃）；
 * - 原生模式：贴边通栏 `c.surface` + 内部 `statusBarsPadding()`，保持 0.3.5 原生观感。
 */
@Composable
fun Modifier.dshTopBarSurface(): Modifier {
    val liquid = LocalLiquidUi.current
    return if (liquid) {
        this
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .liquidGlass(shape = RoundedCornerShape(LiquidRadius.xl), level = LiquidLevel.Panel)
    } else {
        this
            .background(DshTheme2.colors.surface)
            .statusBarsPadding()
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
    // 原生壳不做错峰入场（0.3.5 是直接出现），也省掉每行一个 Animatable。
    if (!enabled || !LocalLiquidUi.current) return this
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
    val isDark = DshTheme2.colors.isDark
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val current = animateFloatAsState(
        targetValue = if (pressed && enabled) scale else 1f,
        animationSpec = LiquidMotion.press,
        label = "liquid-click",
    )
    return this
        .graphicsLayer {
            val s = current.value
            scaleX = s
            scaleY = s
        }
        .clickable(
            interactionSource = interaction,
            indication = ripple(bounded = true, color = if (isDark) Color.White else Color.Black),
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
    val glassMod = modifier.liquidGlass(
        shape = shape,
        level = level,
        tokens = tokens,
        tint = tint,
        tintGradient = tintGradient,
        elevated = elevated,
    )
    if (onClick == null) {
        Box(modifier = glassMod, content = content)
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed && enabled) 0.978f else 1f,
        animationSpec = LiquidMotion.press,
        label = "glass-press",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                val s = scale.value
                scaleX = s
                scaleY = s
            }
            .liquidGlass(
                shape = shape,
                level = level,
                tokens = tokens,
                tint = tint,
                tintGradient = tintGradient,
                elevated = elevated,
            )
            .clickable(
                interactionSource = interaction,
                indication = ripple(
                    bounded = true,
                    color = if (tokens.isDark) Color.White else Color.Black,
                ),
                enabled = enabled,
                onClick = onClick,
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
    val liquidUi = LocalLiquidUi.current
    val legacyLine = DshTheme2.colors.line
    Box(
        modifier
            .then(if (horizontal) Modifier.height(0.75.dp) else Modifier.width(0.75.dp))
            .drawBehind {
                if (!liquidUi) {
                    // 原生壳：0.3.5 的实色分隔线（原来的玻璃发丝线是白色半透，铺在白卡上看不见）
                    drawRect(legacyLine)
                    return@drawBehind
                }
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