package xin.dshaa.ui.design.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设计稿效果的 Compose 复刻集合。
 *
 * 只放「一次 draw 就能做完」的效果 —— 循环动画、遮罩一律在 draw 阶段读取动画值，
 * 不进组合阶段，避免整页重组（本项目反复踩过的坑）。
 */

private val InkEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * 三.3 水墨晕染式流式打字。
 *
 * 设计稿给每个字 span 挂一遍 `inkWashIn`（opacity 0→1 + translateY 3px + scale .96）。
 * 真按字做在 Compose 里意味着一帧几十次文字测量，流式输出会把帧率打穿，
 * 所以这里等价实现成**一段横向扫过的软边遮罩**：同一时刻只有一个动画量在动，
 * 观感是「文字从左往右洇出来」，成本只有一次 DstIn 合成。
 *
 * [trigger] 变化（例如文本长度）时重新洇一遍；首帧不洇，避免整页一起闪。
 */
@Composable
fun Modifier.inkReveal(
    trigger: Any?,
    durationMs: Int = 360,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return this
    val progress = remember { Animatable(1f) }
    val primed = remember { mutableStateOf(false) }
    LaunchedEffect(trigger) {
        if (!primed.value) {
            primed.value = true
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMs, easing = InkEase))
    }
    return this
        // DstIn 必须在**离屏图层**内合成，否则它会按窗口底色去 blend，
        // 结果是文字区域出现一条黑→透明渐变（用户截图：正文被黑块盖住）。
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            val p = progress.value
            drawContent()
            if (p >= 0.999f) return@drawWithContent
            val w = size.width.coerceAtLeast(1f)
            val lead = p * w * 1.08f
            val edge = w * 0.12f
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black,
                    ((lead - edge) / lead).coerceIn(0f, 0.999f) to Color.Black,
                    1f to Color.Transparent,
                    startX = 0f,
                    endX = lead,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}


/**
 * 会话流的上下渐隐遮罩（设计稿 `.thread` 的 `mask-image`）：
 * 顶部 0→54px 由透明到实心、底部最后 88px 渐隐，
 * 让气泡从顶栏/输入台背后「穿过去」，而不是被一刀切断。
 */
fun Modifier.threadEdgeFade(
    topSolid: Dp = 54.dp,
    bottomSolid: Dp = 88.dp,
    fadeIn: Dp = 18.dp,
    fadeOut: Dp = 22.dp,
): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
    drawContent()
    val h = size.height.coerceAtLeast(1f)
    val tSolid = topSolid.toPx().coerceAtMost(h)
    val bSolid = (h - bottomSolid.toPx()).coerceAtLeast(tSolid)
    val t0 = fadeIn.toPx().coerceAtMost(tSolid)
    val b0 = (bSolid - fadeOut.toPx()).coerceAtLeast(tSolid)
    val stops = arrayListOf(
        0f to Color.Transparent,
        (t0 / h).coerceIn(0f, 1f) to Color(0x73000000),
        (tSolid / h).coerceIn(0f, 1f) to Color.Black,
        (bSolid / h).coerceIn(0f, 1f) to Color.Black,
        (b0 / h).coerceIn(0f, 1f) to Color(0x73000000),
        1f to Color.Transparent,
    )
    drawRect(
        brush = Brush.verticalGradient(colorStops = stops.toTypedArray(), startY = 0f, endY = h),
        blendMode = BlendMode.DstIn,
    )
}

/**
 * 聚焦双环（设计稿 `.composer:focus-within` 的
 * `0 0 0 1.5px focus-ring-inner, 0 0 0 7px focus-ring-outer`）。
 *
 * 环画在自身边界**外面**，调用方需预留 [ringSpace] 外边距，否则会被父级裁掉。
 */
@Composable
fun Modifier.focusRings(
    focused: Boolean,
    shape: Shape,
    inner: Color,
    outer: Color,
): Modifier {
    val p = animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(220, easing = InkEase),
        label = "focus-rings",
    ).value
    if (p <= 0.01f) return this
    return drawBehind {
        val innerW = 1.5.dp.toPx()
        val outerW = 7.dp.toPx()
        strokeShape(shape, 0f, innerW, inner.copy(alpha = inner.alpha * p))
        strokeShape(shape, 0f, outerW, outer.copy(alpha = outer.alpha * p))
    }
}

private fun DrawScope.strokeShape(shape: Shape, offset: Float, width: Float, color: Color) {
    val outline = shape.createOutline(
        size = Size(size.width + width * 2f, size.height + width * 2f),
        layoutDirection = layoutDirection,
        density = this,
    )
    translate(left = -width + offset, top = -width + offset) {
        when (outline) {
            is Outline.Rounded -> drawPath(
                path = Path().apply { addRoundRect(outline.roundRect) },
                color = color,
                style = Stroke(width),
            )
            is Outline.Rectangle -> drawRect(
                color = color,
                size = Size(size.width + width * 2f + offset, size.height + width * 2f + offset),
                style = Stroke(width),
            )
            is Outline.Generic -> drawPath(outline.path, color = color, style = Stroke(width))
        }
    }
}

/** 停顿用的空偏移（避免每处都写 `Offset.Zero` 时重复 import）。 */
internal val NoOffset = Offset.Zero
