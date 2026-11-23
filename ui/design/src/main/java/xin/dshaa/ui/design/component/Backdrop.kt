package xin.dshaa.ui.design.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import xin.dshaa.ui.design.theme.LiquidTokens
import xin.dshaa.ui.design.theme.LocalLiquidTokens
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 「水族箱」背景：最底层墙面渐变 + 三团缓慢流动的光斑 + 细噪点 + 跟手光源。
 *
 * 性能约束（照搬设计稿的哲学）：
 * - 全部是渐变与圆，单次 draw 通过，不开额外图层、不做模糊；
 * - 三团光斑由 21~43s 的无限过渡驱动，帧间只重绘不重组；
 * - 噪点图一次生成、全局复用，靠 Shader 平铺；
 * - 跟手光源只在手指移动时跑帧循环，收敛即停（不空转、不耗电）。
 *
 * 每个页面只需在最外层套一次，页面里的玻璃会「隔着它」发亮。
 */
@Composable
fun LiquidBackdrop(
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    withSheen: Boolean = true,
    withGrain: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = LocalLiquidTokens.current
    // 模拟器（goldfish/ranchu + 软件栅格）扛不住每帧全屏重绘，会把 guest 的 GPU 线程拖死；
    // 真机照常动画。截图验证也不需要动画，所以这里直接按设备类型降级。
    val drift = rememberDriftStates(animated && !isEmulator())
    val grain = if (withGrain) rememberGrain() else null
    val grainBrush = remember(grain) { grain?.let { ShaderBrush(ImageShader(it, TileMode.Repeated, TileMode.Repeated)) } }
    // 跟手光源：目标点由指针写入，帧循环里指数逼近，收敛即退出循环
    val sheenTarget = remember { mutableStateOf(Offset(0.5f, 0.22f)) }
    val sheenNow = remember { mutableStateOf(Offset(0.5f, 0.22f)) }
    val sheenMoving = remember { mutableStateOf(false) }
    if (withSheen) {
        LaunchedEffect(sheenMoving.value) {
            if (!sheenMoving.value) return@LaunchedEffect
            var last = 0L
            while (sheenMoving.value) {
                withFrameNanos { now ->
                    val dtMs = if (last == 0L) 16f else ((now - last) / 1_000_000f).coerceIn(1f, 64f)
                    last = now
                    // 与设计稿 13.B 同一套指数逼近：k = 1 - 0.0016^(dt/1000)
                    val k = 1f - Math.pow(0.0016, (dtMs / 1000f).toDouble()).toFloat()
                    val target = sheenTarget.value
                    val current = sheenNow.value
                    val nx = current.x + (target.x - current.x) * k
                    val ny = current.y + (target.y - current.y) * k
                    if (abs(nx - current.x) > 0.0006f || abs(ny - current.y) > 0.0006f) {
                        sheenNow.value = Offset(nx, ny)
                    } else {
                        sheenNow.value = target
                        sheenMoving.value = false
                    }
                }
            }
        }
    }
    // 背景自己的窗口位置：玻璃要用它把「墙」重画到自己身上（厚透镜折射）
    val wallOrigin = remember { mutableStateOf(Offset.Zero) }
    val wallSize = remember { mutableStateOf(Size.Zero) }
    val lens = remember(tokens, drift, sheenNow) {
        WallLens(tokens = tokens, drift = drift, sheen = sheenNow, origin = wallOrigin, size = wallSize)
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned {
                wallOrigin.value = it.positionInRoot()
                wallSize.value = Size(it.size.width.toFloat(), it.size.height.toFloat())
            }
            .then(
                if (withSheen) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.lastOrNull() ?: continue
                                if (!change.pressed) continue
                                val w = size.width.coerceAtLeast(1).toFloat()
                                val h = size.height.coerceAtLeast(1).toFloat()
                                sheenTarget.value = Offset(
                                    (change.position.x / w).coerceIn(0f, 1f),
                                    (change.position.y / h).coerceIn(0f, 1f),
                                )
                                sheenMoving.value = true
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .drawBehind {
                drawWall(tokens, drift, size.width, size.height)
                if (withSheen) drawSheen(tokens, sheenNow.value, size.width, size.height)
                grainBrush?.let { drawRect(brush = it, alpha = tokens.grainAlpha) }
            },
        content = {
            CompositionLocalProvider(LocalWallLens provides lens) { content() }
        },
    )
}

/**
 * 光斑相位：返回 `State` 而不是 Float 值 —— 值只在 draw 阶段读，
 * 于是每帧只触发「重绘」不触发「重组」（否则整棵树会被动画拖着每帧重组）。
 */
@Composable
private fun rememberDriftStates(animated: Boolean): List<State<Float>> {
    if (!animated) return remember { listOf(mutableStateOf(0.15f), mutableStateOf(0.45f), mutableStateOf(0.78f)) }
    val transition = rememberInfiniteTransition(label = "liquid-wall")
    val t1 = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 22_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "blob-a",
    )
    val t2 = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 28_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "blob-b",
    )
    val t3 = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 24_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "blob-c",
    )
    return remember(transition) { listOf(t1, t2, t3) }
}

/**
 * 三团背景流体色斑的几何，**逐项照抄设计稿 `.scenery`**（单位 vmax = max(w,h)/100）：
 * - s1：58×44 vmax，left -12 / top -8  → 中心 (17,14)，半径 (29,22)，bgFlowA 11s，位移 (10,6)
 * - s2：54×42 vmax，right -12 / bottom -7 → 中心 (85,86)，半径 (27,21)，bgFlowB 14s，位移 (11,7)
 * - flow：60×42 vmax，left 14 / top 24 → 中心 (44,45)，半径 (30,21)，bgFlowC 12s，位移 (6,10)
 * 每团是 `radial-gradient(ellipse at 50% 50%, c0 0%, c1 48%, transparent 74%)`。
 */
private data class SceneryBlob(
    val cx: Float,
    val cy: Float,
    val rx: Float,
    val ry: Float,
    val dx: Float,
    val dy: Float,
    val color: Color,
)

private fun sceneryBlobs(tokens: LiquidTokens): List<SceneryBlob> = listOf(
    SceneryBlob(17f, 14f, 29f, 22f, 10f, 6f, tokens.blobA),
    SceneryBlob(85f, 86f, 27f, 21f, 11f, 7f, tokens.blobB),
    SceneryBlob(44f, 45f, 30f, 21f, 6f, 10f, tokens.blobC),
)

/** 画一团椭圆色斑：Compose 的 radialGradient 是正圆，用 scale 变换把它拉成椭圆。 */
private fun DrawScope.drawSceneryBlob(
    blob: SceneryBlob,
    t: Float,
    vmax: Float,
    magnify: Float = 1f,
    map: ((Offset) -> Offset)? = null,
) {
    val a = t * 2f * PI.toFloat()
    val gx = (blob.cx + blob.dx * sin(a)) * vmax
    val gy = (blob.cy + blob.dy * (1f - cos(a)) * 0.5f) * vmax
    val s = (1f + 0.05f * sin(a * 0.8f)) * magnify
    val rx = blob.rx * vmax * s
    val ry = blob.ry * vmax * s
    val raw = Offset(gx, gy)
    val c = map?.invoke(raw) ?: raw
    withTransform({ scale(scaleX = rx / ry, scaleY = 1f, pivot = c) }) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to blob.color,
                0.48f to blob.color.copy(alpha = blob.color.alpha * 0.40f),
                0.74f to Color.Transparent,
                center = c,
                radius = ry,
            ),
            radius = ry,
            center = c,
        )
    }
}

private fun DrawScope.drawWall(tokens: LiquidTokens, drift: List<State<Float>>, w: Float, h: Float) {
    // 设计稿 .scenery__layer：一层底色 + 三团流动色斑。背景是"水"，厚玻璃才有东西可折射。
    drawRect(color = tokens.wallTop)
    val vmax = maxOf(w, h) / 100f
    sceneryBlobs(tokens).forEachIndexed { i, b ->
        drawSceneryBlob(blob = b, t = drift[i].value, vmax = vmax)
    }
}

private fun DrawScope.drawSheen(tokens: LiquidTokens, pos: Offset, w: Float, h: Float) {
    // 设计稿 .sheen：34vmax × 26vmax 的椭圆光，
    // rgba(160,200,255,.20) → rgba(96,168,255,.075) 38% → transparent 70%，元素整体 opacity .92。
    val cx = pos.x * w
    val cy = pos.y * h
    val rx = maxOf(w, h) * 0.34f
    val ry = maxOf(w, h) * 0.26f
    val center = Offset(cx, cy)
    val mid = Color(0xFFA0C8FF).copy(alpha = 0.20f * 0.92f)
    val outer = Color(0xFF60A8FF).copy(alpha = 0.075f * 0.92f)
    withTransform({ scale(scaleX = rx / ry, scaleY = 1f, pivot = center) }) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to mid,
                0.38f to outer,
                0.70f to Color.Transparent,
                center = center,
                radius = ry,
            ),
            radius = ry,
            center = center,
        )
    }
}

/** 粗判模拟器：只有它需要关掉背景动画（软件栅格撑不住连续全屏重绘）。 */
private fun isEmulator(): Boolean {
    val fp = android.os.Build.FINGERPRINT
    val hw = android.os.Build.HARDWARE
    val product = android.os.Build.PRODUCT
    return fp.startsWith("generic") ||
        fp.contains("emulator", ignoreCase = true) ||
        hw.contains("goldfish", ignoreCase = true) ||
        hw.contains("ranchu", ignoreCase = true) ||
        product.contains("sdk", ignoreCase = true)
}

/** 噪点图：96×96 灰度随机，生成一次全局复用；打散 AMOLED 暗部的色带。 */
@Composable
fun rememberGrain(): ImageBitmap? = remember {
    runCatching {
        val n = 96
        val bmp = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(20261119L)
        val px = IntArray(n * n)
        for (i in px.indices) {
            val v = rnd.nextInt(256)
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bmp.setPixels(px, 0, n, 0, 0, n, n)
        bmp.asImageBitmap()
    }.getOrNull()
}

/** 只要背景不要内容的简写（全屏弹层 / 独立 Activity 用）。 */
@Composable
fun LiquidWall(modifier: Modifier = Modifier) {
    LiquidBackdrop(modifier = modifier, withSheen = false) { }
}

/**
 * 「墙画笔」：把背景墙按**卡片的屏幕位置**重画一遍，并围绕卡片中心放大 [magnify] 倍。
 *
 * 这是 Compose 里做厚透镜折射的关键：Android 没有 backdrop-filter，但我们自己的背景是**已知的**
 * —— 在玻璃内部再画一次墙并稍微放大，就等于玻璃把背后的流体弯折、放大了。
 * 设计稿里同一件事由 `backdrop-filter: blur(3px) saturate(195%)` 完成。
 */
class WallLens internal constructor(
    internal val tokens: LiquidTokens,
    internal val drift: List<State<Float>>,
    internal val sheen: State<Offset>,
    internal val origin: State<Offset>,
    internal val size: State<Size>,
)

val LocalWallLens = staticCompositionLocalOf<WallLens?> { null }
fun DrawScope.drawWallIntoLens(
    lens: WallLens,
    cardOrigin: Offset,
    cardSize: Size,
    magnify: Float = 1.12f,
) {
    val wallSize = lens.size.value
    if (wallSize.width <= 0f || wallSize.height <= 0f) return
    val vw = wallSize.width
    val vh = wallSize.height
    val dx = lens.origin.value.x - cardOrigin.x
    val dy = lens.origin.value.y - cardOrigin.y
    val cx = cardSize.width / 2f
    val cy = cardSize.height / 2f

    drawRect(color = lens.tokens.wallTop)
    val vmax = maxOf(vw, vh) / 100f
    val map: (Offset) -> Offset = { o ->
        Offset((o.x + dx - cx) * magnify + cx, (o.y + dy - cy) * magnify + cy)
    }
    sceneryBlobs(lens.tokens).forEachIndexed { i, b ->
        drawSceneryBlob(blob = b, t = lens.drift[i].value, vmax = vmax, magnify = magnify, map = map)
    }
}