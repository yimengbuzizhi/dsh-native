package xin.dshaa.ui.design.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xin.dshaa.ui.design.theme.DshTheme2
import xin.dshaa.ui.design.theme.LocalLiquidTokens
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 水球 / 水囊：设计稿 `.water-orb` 与 `.water-orb.is-capsule` 的 Compose 复刻。
 *
 * 一颗「厚透镜水球」由五样东西叠出来（和 CSS 一一对应）：
 * ① `.water-orb` 自己的两层 radial 底 + 五条 inset 阴影（1.2px 亮棱 + 上下内辉光 + 底部品牌色内弧）
 * ② `::after` 那道 16px 厚凸透镜折射环（58% → 86% → 100% 的白色渐强环带）
 * ③ `.water-orb__wave--1/2` 两层缓慢旋转的水体折射面
 * ④ `.water-orb__spec` 左上斜置的镜面高光（胶囊态会挪位、变角度）
 * ⑤ `.water-orb__meniscus` 底部弯月面
 *
 * ## 球 ⇄ 胶囊：做的是「液体形变」而不是「矩形缩放」
 *
 * 设计稿里两个形态各有一组 keyframes（`solidWaterSphere` / `solidWaterCapsule`），
 * 尺寸只靠 `transition: width/height 440ms`。那在浏览器里就是「矩形在缩放」，
 * 因为 border-radius 的百分比会跟着尺寸一起变。这里换成三层叠加：
 *
 * 1. **两套关键帧各自采样**：球态按 144 的比例、胶囊态按 218×94 的绝对 px，
 *    各自跟同一个 5.2s 呼吸相位走，再按形变进度逐角插值 ——
 *    于是中间态是「球和胶囊的混血」，四个角的半径各自走自己的路。
 * 2. **形变时间线**：先往回蓄力（水被挤向另一头）→ 过冲（水被甩出去）→ 回弹落定。
 *    过冲和蓄力都在**行程**上归一，所以球→囊、囊→球都一样有弹性。
 * 3. **体积守恒的挤压 + 表面张力涟漪**：形变途中横向甩开时纵向收一点，
 *    轮廓上再叠一组行波（落定即消失），这才是水，不是果冻。
 *
 * 性能：整颗球在一帧的 [Canvas] 里画完，所有相位以 `State` 传入、**只在 draw 阶段读取**，
 * 球在呼吸时只重绘不重组；外壳尺寸固定，形变过程**不触发重新测量**。
 */
@Composable
fun WaterOrb(
    modifier: Modifier = Modifier,
    size: Dp = 144.dp,
    animated: Boolean = true,
    /** 点一下会像水袋一样颤一下（设计稿 `orbPokeWobble`） */
    poking: Boolean = true,
    /** 胶囊形态（设计稿 `.water-orb.is-capsule`）：218×94 的水囊 */
    capsule: Boolean = false,
    capsuleWidth: Dp = 218.dp,
    capsuleHeight: Dp = 94.dp,
    /**
     * 是否按「胶囊」的尺寸占位。开着的时候外壳固定成 `max(球, 胶囊)` 的大小，
     * 形变过程中不再重新测量布局（设计稿的 `.hero__stage` 就是这个思路）。
     */
    reserveCapsuleSpace: Boolean = false,
    /** 点按回调（球 / 胶囊之间切换由调用方决定） */
    onTap: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val tokens = LocalLiquidTokens.current
    val brand = DshTheme2.colors.brand
    /** `--accent-teal`：第二层水体与底部焦散用的青绿（取背景光斑同一支色） */
    val teal = tokens.blobB.copy(alpha = 1f)
    val motion = rememberOrbMotion(animated)
    val density = LocalDensity.current
    val dpScale = density.density

    val spherePx = with(density) { size.toPx() }
    val capsuleWPx = with(density) { capsuleWidth.toPx() }
    val capsuleHPx = with(density) { capsuleHeight.toPx() }

    // ---- 形态时间线 -------------------------------------------------------
    val morph = remember { Animatable(if (capsule) 1f else 0f) }
    val kick = remember { Animatable(1f) }
    /** 这次形变的方向（+1 = 球→囊），挤压的方向跟着它走 */
    var dirSign by remember { mutableStateOf(if (capsule) 1f else -1f) }

    // ---- 点按：颤动 + 涟漪环 ----------------------------------------------
    val scope = rememberCoroutineScope()
    val poke = remember { Animatable(1f) }
    val ring = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    val initialCapsule = remember { capsule }

    /** 设计稿 `orbPokeWobble`：0 → 1 抖一下，配合 onPress 与外部切换形态共用 */
    fun wobble() {
        scope.launch {
            ring.snapTo(0f)
            ring.animateTo(1f, tween(620, easing = LinearEasing))
        }
        scope.launch {
            poke.snapTo(0f)
            poke.animateTo(1f, tween(640, easing = LinearEasing))
        }
    }

    LaunchedEffect(capsule) {
        val to = if (capsule) 1f else 0f
        val from = morph.value
        // 形态被外部改掉（比如点第一颗胶囊）时，水囊也该像被戳了一下
        if (poking && capsule != initialCapsule) wobble()
        if (abs(to - from) < 0.001f) {
            morph.snapTo(to)
            kick.snapTo(1f)
            return@LaunchedEffect
        }
        val dir = if (to > from) 1f else -1f
        dirSign = dir
        // 行程归一：半路被打断也不会「跳一段」
        val travel = abs(to - from)
        val gather = from - dir * GATHER * travel
        val overshoot = to + dir * OVERSHOOT * travel
        val settle = to - dir * SETTLE * travel
        launch {
            morph.animateTo(
                targetValue = to,
                animationSpec = keyframes {
                    durationMillis = MORPH_MS
                    gather at 120 using CubicBezierEasing(0.33f, 0f, 0.67f, 1f)
                    overshoot at 420 using CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
                    settle at 560 using CubicBezierEasing(0.33f, 0f, 0.67f, 1f)
                    to at MORPH_MS
                },
            )
        }
        kick.snapTo(0f)
        kick.animateTo(1f, tween(MORPH_MS, easing = LinearEasing))
    }

    val frameW = if (reserveCapsuleSpace) maxOf(size, capsuleWidth) else size
    val frameH = if (reserveCapsuleSpace) maxOf(size, capsuleHeight) else size

    Box(
        modifier = modifier
            .size(frameW, frameH)
            .pointerInput(poking, onTap) {
                detectTapGestures(
                    onPress = {
                        if (poking) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                ring.snapTo(0f)
                                ring.animateTo(1f, tween(620, easing = LinearEasing))
                            }
                            poke.snapTo(0f)
                            try {
                                awaitRelease()
                            } finally {
                                poke.snapTo(0f)
                                poke.animateTo(1f, tween(640, easing = LinearEasing))
                            }
                        }
                    },
                    onTap = onTap?.let { cb -> { cb() } },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val fx = this.size.width / 2f
            val fy = this.size.height / 2f
            val u = motion.breath.value
            val tt = morph.value
            val kp = kick.value.coerceIn(0f, 1f)
            val ct = tt.coerceIn(-0.10f, 1.12f)
            val ct01 = tt.coerceIn(0f, 1f)

            // ---- ① 两套关键帧各自采样，再逐角融合 ----
            val sk = sampleKeys(SPHERE_TIMES, SPHERE_KEYS, u)
            val ck = sampleKeys(CAPSULE_TIMES, CAPSULE_KEYS, u)
            val cwScale = capsuleWPx / CAPSULE_BASE_W
            val chScale = capsuleHPx / CAPSULE_BASE_H

            val w = spherePx + (capsuleWPx - spherePx) * ct
            val h = spherePx + (capsuleHPx - spherePx) * ct

            // ---- ② 体积守恒的挤压（横向甩开 → 纵向收）----
            val pulse = sin((PI * kp).toFloat()) // 0 → 1 → 0
            val fling = dirSign * 0.115f * pulse * (1f - 0.45f * kp)
            val sx = (sk[9] + (ck[9] - sk[9]) * ct) * (1f + fling)
            val sy = (sk[10] + (ck[10] - sk[10]) * ct) * (1f - fling * 0.82f)
            val rot = sk[11] + (ck[11] - sk[11]) * ct
            val ty = (sk[8] + (ck[8] - sk[8]) * ct) * dpScale

            // ---- ③ 表面张力涟漪：形变途中轮廓多一组行波 ----
            val ripple = 0.055f * pulse
            val wavePhase = kp * 2f * PI.toFloat() * 1.35f
            val rx = FloatArray(4)
            val ry = FloatArray(4)
            for (i in 0 until 4) {
                val ph = wavePhase + i * (PI.toFloat() / 2f)
                rx[i] = (sk[i] * spherePx + (ck[i] * cwScale - sk[i] * spherePx) * ct + ripple * w * sin(ph))
                    .coerceIn(1f, w)
                ry[i] = (sk[4 + i] * spherePx + (ck[4 + i] * chScale - sk[4 + i] * spherePx) * ct + ripple * h * sin(ph + PI.toFloat() * 0.72f))
                    .coerceIn(1f, h)
            }

            val path = organicPath(w, h, rx, ry)
            val centerLocal = Offset(w / 2f, h / 2f)
            val cornerRadius = hypot(w / 2f, h / 2f)

            // 眼球跟着水一起被挤压（内容不卷进形变，但会轻轻被带着走）
            val rimBright = Color.White.copy(alpha = if (tokens.isDark) 0.72f else 0.98f)
            val rimBoost = 0.45f * pulse

            translate(left = fx - w / 2f, top = fy - h / 2f) {
                rotate(rot, pivot = centerLocal) {
                    translate(top = ty) {
                        scale(sx, sy, pivot = centerLocal) {
                            // ---- 落影（设计稿 `0 20px 40px -14px rgba(2,8,22,.46)`）----
                            drawOval(
                                brush = Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    1f to tokens.ambient.copy(
                                        alpha = tokens.ambient.alpha * if (tokens.isDark) 0.72f else 0.9f,
                                    ),
                                    startY = h * 0.55f,
                                    endY = h * 1.06f,
                                ),
                                topLeft = Offset(w * 0.06f, h * 0.62f),
                                size = Size(w * 0.88f, h * 0.44f),
                            )

                            clipPath(path) {
                                // ① 水本身：一层极淡的白（rgba(255,255,255,.03)）
                                drawRect(Color.White.copy(alpha = 0.03f))

                                // ② 左上受光的球面（radial at 30% 22%）
                                drawRect(
                                    brush = Brush.radialGradient(
                                        0f to Color.White.copy(alpha = 0.28f),
                                        0.48f to Color.Transparent,
                                        center = Offset(w * 0.30f, h * 0.22f),
                                        radius = max(w, h) * 0.62f,
                                    ),
                                )

                                // ③ 底部水体积：白 → 品牌色 → 透明（ellipse 82% 52% at 52% 88%）
                                drawOval(
                                    brush = Brush.radialGradient(
                                        0f to Color.White.copy(alpha = 0.24f),
                                        0.48f to brand.copy(alpha = 0.14f),
                                        0.78f to Color.Transparent,
                                        1f to Color.Transparent,
                                        center = Offset(w * 0.52f, h * 0.88f),
                                        radius = w * 0.46f,
                                    ),
                                    topLeft = Offset(w * 0.11f, h * 0.62f),
                                    size = Size(w * 0.82f, h * 0.52f),
                                )

                                // ④ 两层水体折射面（内部缓慢反向旋转）
                                waveSurface(
                                    center = centerLocal,
                                    left = -w * 0.25f,
                                    top = h * 0.32f,
                                    waveW = w * 1.50f,
                                    waveH = h * 1.50f,
                                    rx = WAVE1_RX,
                                    ry = WAVE1_RY,
                                    degrees = motion.sloshA.value * 360f,
                                    dx = 4.dp.toPx() * sin((PI * motion.sloshA.value).toFloat()),
                                    dy = -5.dp.toPx() * sin((PI * motion.sloshA.value).toFloat()),
                                    topColor = Color.White.copy(alpha = if (tokens.isDark) 0.14f else 0.48f),
                                    midColor = brand.copy(alpha = 0.10f),
                                    ripple = pulse,
                                )
                                waveSurface(
                                    center = centerLocal,
                                    left = -w * 0.22f,
                                    top = h * 0.42f,
                                    waveW = w * 1.45f,
                                    waveH = h * 1.45f,
                                    rx = WAVE2_RX,
                                    ry = WAVE2_RY,
                                    degrees = -motion.sloshB.value * 360f,
                                    dx = -5.dp.toPx() * sin((PI * motion.sloshB.value).toFloat()),
                                    dy = 4.dp.toPx() * sin((PI * motion.sloshB.value).toFloat()),
                                    topColor = Color.White.copy(alpha = if (tokens.isDark) 0.10f else 0.34f),
                                    midColor = teal.copy(alpha = 0.10f),
                                    ripple = pulse,
                                )

                                // ⑤ 底部内辉光（inset 0 -4px 8px + inset 0 -18px 30px 品牌色）
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0.62f to Color.Transparent,
                                        0.86f to Color.White.copy(alpha = if (tokens.isDark) 0.10f else 0.22f),
                                        1f to Color.White.copy(alpha = if (tokens.isDark) 0.30f else 0.52f),
                                        startY = 0f,
                                        endY = h,
                                    ),
                                )
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0.55f to Color.Transparent,
                                        1f to brand.copy(alpha = if (tokens.isDark) 0.24f else 0.16f),
                                        startY = 0f,
                                        endY = h,
                                    ),
                                )

                                // ⑥ 顶部镜面高光（球：14% / 8% · 42% × 22% · −22°；
                                //    胶囊：10% / 9% · 34% × 26% · −8°）
                                val specLeft = w * (0.14f + (0.10f - 0.14f) * ct01)
                                val specTop = h * (0.08f + (0.09f - 0.08f) * ct01)
                                val specW = w * (0.42f + (0.34f - 0.42f) * ct01)
                                val specH = h * (0.22f + (0.26f - 0.22f) * ct01)
                                val specDeg = -22f + (-8f + 22f) * ct01
                                rotate(
                                    degrees = specDeg,
                                    pivot = Offset(specLeft + specW / 2f, specTop + specH / 2f),
                                ) {
                                    drawOval(
                                        brush = Brush.radialGradient(
                                            0f to Color.White.copy(alpha = if (tokens.isDark) 0.72f else 0.96f),
                                            0.52f to Color.White.copy(alpha = if (tokens.isDark) 0.22f else 0.42f),
                                            0.82f to Color.Transparent,
                                            1f to Color.Transparent,
                                            center = Offset(specLeft + specW / 2f, specTop + specH * 0.35f),
                                            radius = specW * 0.62f,
                                        ),
                                        topLeft = Offset(specLeft, specTop),
                                        size = Size(specW, specH),
                                    )
                                }

                                // ⑦ 底部弯月面（bottom 5% · left/right 16% · 高 20%）
                                drawOval(
                                    brush = Brush.radialGradient(
                                        0f to Color.White.copy(alpha = if (tokens.isDark) 0.52f else 0.62f),
                                        0.52f to Color.White.copy(alpha = 0.12f),
                                        0.78f to Color.Transparent,
                                        1f to Color.Transparent,
                                        center = Offset(w / 2f, h * 0.80f),
                                        radius = w * 0.34f,
                                    ),
                                    topLeft = Offset(w * 0.16f, h * 0.70f),
                                    size = Size(w * 0.68f, h * 0.25f),
                                )

                                // ⑧ `::after` 16px 厚凸透镜折射环（0 → 58% 透明 → 86% .22 → 100% .48）
                                drawRect(
                                    brush = Brush.radialGradient(
                                        0f to Color.Transparent,
                                        0.58f to Color.Transparent,
                                        0.86f to Color.White.copy(alpha = if (tokens.isDark) 0.20f else 0.26f),
                                        1f to Color.White.copy(alpha = if (tokens.isDark) 0.46f else 0.54f),
                                        center = centerLocal,
                                        radius = cornerRadius,
                                    ),
                                )
                            }

                            // ⑨ 厚壁：内侧冷色暗边（没有它球看着像塑料圆片）+ 1.2px 亮棱
                            drawPath(
                                path = path,
                                color = brand.copy(alpha = if (tokens.isDark) 0.22f else 0.12f),
                                style = Stroke(width = 2.5.dp.toPx()),
                            )
                            drawPath(
                                path = path,
                                brush = Brush.verticalGradient(
                                    0f to rimBright,
                                    0.45f to rimBright.copy(alpha = rimBright.alpha * 0.38f),
                                    1f to rimBright.copy(alpha = rimBright.alpha * 0.86f),
                                    startY = 0f,
                                    endY = h,
                                ),
                                style = Stroke(width = 1.2.dp.toPx()),
                            )
                            // 形变瞬间棱光被点亮一下（水被甩开时边缘最亮）
                            if (rimBoost > 0.01f) {
                                drawPath(
                                    path = path,
                                    color = Color.White.copy(alpha = rimBoost * (if (tokens.isDark) 0.34f else 0.46f)),
                                    style = Stroke(width = 1.6.dp.toPx()),
                                )
                            }
                        }
                    }
                }
            }

            // ---- ⑩ 点按涟漪环：跟着轮廓一起扩大 ----
            val rp = ring.value
            if (rp > 0f && rp < 1f) {
                translate(left = fx - w / 2f, top = fy - h / 2f) {
                    scale(
                        scaleX = 1f + rp * 0.55f,
                        scaleY = 1f + rp * 0.45f,
                        pivot = centerLocal,
                    ) {
                        drawPath(
                            path = path,
                            color = Color.White.copy(alpha = (1f - rp) * 0.42f),
                            style = Stroke(width = (2.2f - rp * 1.6f).dp.toPx()),
                        )
                    }
                }
            }
        }

        // 内容（鲸鱼 + 文字）按颤动节奏轻压一下，但不参与形变缩放
        Box(
            modifier = Modifier.graphicsLayer {
                val p = poke.value
                val (wsx, wsy) = wobbleScale(p)
                scaleX = wsx
                scaleY = wsy
            },
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/** 水球下面那团光斑投影（设计稿 `.hero__caustic-shadow` / `shadowBreathe`）。 */
@Composable
fun WaterOrbShadow(
    modifier: Modifier = Modifier,
    width: Dp = 116.dp,
    height: Dp = 22.dp,
    animated: Boolean = true,
) {
    val tokens = LocalLiquidTokens.current
    val brand = DshTheme2.colors.brand
    val teal = tokens.blobB.copy(alpha = 1f)
    val motion = rememberOrbMotion(animated)
    Canvas(modifier.size(width, height)) {
        // shadowBreathe：scaleX .92 → 1.08、scaleY .9 → 1.1、opacity .65 → .95
        val b = motion.breath.value
        val sx = 0.92f + 0.16f * b
        val sy = 0.90f + 0.20f * b
        val a = 0.65f + 0.30f * b
        val w = size.width * sx
        val h = size.height * sy
        drawOval(
            brush = Brush.radialGradient(
                0f to brand.copy(alpha = (if (tokens.isDark) 0.34f else 0.28f) * a),
                0.52f to teal.copy(alpha = 0.14f * a),
                0.78f to Color.Transparent,
                1f to Color.Transparent,
                center = Offset(size.width / 2f, size.height / 2f),
                radius = w * 0.5f,
            ),
            topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
            size = Size(w, h),
        )
    }
}

/**
 * 球里那条小鲸鱼（设计稿 `.water-orb__whale`，viewBox 36×24、1.8 描边）。
 * 用 Canvas 画而不是图标字体：这条路径是设计稿独有的形状，换一个图标就变味了。
 */
@Composable
fun WaterOrbWhale(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    alpha: Float = 0.92f,
) {
    val tint = if (color == Color.Unspecified) DshTheme2.colors.text else color
    Canvas(modifier) {
        val s = min(size.width / WHALE_VB_W, size.height / WHALE_VB_H)
        translate(left = (size.width - WHALE_VB_W * s) / 2f, top = (size.height - WHALE_VB_H * s) / 2f) {
            scale(s, s, pivot = Offset.Zero) {
                val body = Path().apply {
                    moveTo(4f, 15.5f)
                    cubicTo(4f, 9.5f, 10.5f, 5.5f, 18.5f, 6.5f)
                    cubicTo(24.5f, 7.2f, 28.2f, 11.2f, 32.5f, 8.5f)
                    cubicTo(31.5f, 13.2f, 28.5f, 16.5f, 23.5f, 17.5f)
                    cubicTo(16.5f, 18.8f, 9.5f, 17.8f, 4f, 15.5f)
                    close()
                }
                val belly = Path().apply {
                    moveTo(11f, 13.2f)
                    cubicTo(14.5f, 14.5f, 18.5f, 14.5f, 22f, 13f)
                }
                drawPath(
                    path = body,
                    color = tint.copy(alpha = alpha),
                    style = Stroke(
                        width = 1.8f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
                drawPath(
                    path = belly,
                    color = tint.copy(alpha = alpha * 0.6f),
                    style = Stroke(
                        width = 1.8f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 时间线常量

/** 形变总时长：比设计稿的 440ms 长一点，因为多了一段「回弹落定」 */
private const val MORPH_MS = 680

/** 起手蓄力（相对行程） */
private const val GATHER = 0.06f

/** 过冲（相对行程） */
private const val OVERSHOOT = 0.09f

/** 过冲后的回落（相对行程） */
private const val SETTLE = 0.03f

/** 胶囊关键帧的基准尺寸（设计稿 .is-capsule 是 218×94 的绝对 px） */
private const val CAPSULE_BASE_W = 218f
private const val CAPSULE_BASE_H = 94f

private const val WHALE_VB_W = 36f
private const val WHALE_VB_H = 24f

/**
 * 关键帧数据（每条 12 个数）：
 * `[rx0..rx3, ry0..ry3, translateY(dp), scaleX, scaleY, rotate(deg)]`
 *
 * 球的 rx/ry 是**占宽高的比例**（CSS 的 `55% 45% 52% 48% / 47% 54% 46% 53%`）；
 * 胶囊的 rx/ry 是**绝对 px**（218×94 基准），换尺寸时等比缩放。
 */
private val SPHERE_TIMES = floatArrayOf(0f, 0.33f, 0.66f, 1f)

private val SPHERE_KEYS = arrayOf(
    floatArrayOf(0.55f, 0.45f, 0.52f, 0.48f, 0.47f, 0.54f, 0.46f, 0.53f, 5f, 1.02f, 0.98f, -2f),
    floatArrayOf(0.46f, 0.54f, 0.47f, 0.53f, 0.54f, 0.47f, 0.53f, 0.46f, -2f, 0.98f, 1.03f, 1.5f),
    floatArrayOf(0.52f, 0.48f, 0.55f, 0.45f, 0.46f, 0.53f, 0.47f, 0.54f, -6f, 1.03f, 0.97f, -1f),
    floatArrayOf(0.48f, 0.52f, 0.46f, 0.54f, 0.52f, 0.46f, 0.54f, 0.48f, 2f, 0.99f, 1.02f, 2f),
)

private val CAPSULE_TIMES = floatArrayOf(0f, 0.5f, 1f)

private val CAPSULE_KEYS = arrayOf(
    floatArrayOf(46f, 54f, 48f, 52f, 43f, 49f, 45f, 47f, 4f, 1.02f, 0.98f, -1.2f),
    floatArrayOf(54f, 46f, 52f, 48f, 48f, 44f, 48f, 44f, -5f, 0.98f, 1.03f, 1.2f),
    floatArrayOf(48f, 52f, 54f, 46f, 44f, 48f, 44f, 48f, 1f, 1.01f, 0.99f, -0.8f),
)

/** `.water-orb__wave--1` 的四角比例：43% 57% 46% 54% / 45% 52% 48% 55% */
private val WAVE1_RX = floatArrayOf(0.43f, 0.57f, 0.46f, 0.54f)
private val WAVE1_RY = floatArrayOf(0.45f, 0.52f, 0.48f, 0.55f)

/** `.water-orb__wave--2` 的四角比例：54% 46% 52% 48% / 50% 44% 56% 50% */
private val WAVE2_RX = floatArrayOf(0.54f, 0.46f, 0.52f, 0.48f)
private val WAVE2_RY = floatArrayOf(0.50f, 0.44f, 0.56f, 0.50f)

// ---------------------------------------------------------------- internals

private class OrbMotion(
    val breath: State<Float>,
    val sloshA: State<Float>,
    val sloshB: State<Float>,
)

/**
 * 三条相位：
 * - `breath`：5.2s `alternate`，喂给 `solidWaterSphere` / `solidWaterCapsule` 的关键帧；
 * - `sloshA`：6.4s 线性 0→360°，`internalSloshA` 那层水体；
 * - `sloshB`：8.2s 线性，反向那一层。
 */
@Composable
private fun rememberOrbMotion(animated: Boolean): OrbMotion {
    if (!animated) {
        val zero = remember { mutableStateOf(0f) }
        return remember { OrbMotion(zero, zero, zero) }
    }
    val transition = rememberInfiniteTransition(label = "water-orb")
    val breath = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(5200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orb-breath",
    )
    val sloshA = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orb-slosh-a",
    )
    val sloshB = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(8200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orb-slosh-b",
    )
    return remember(transition) { OrbMotion(breath, sloshA, sloshB) }
}

/**
 * 逐段采样的关键帧插值（每段用 smoothstep，近似 CSS 每段 `ease-in-out`）。
 */
private fun sampleKeys(times: FloatArray, keys: Array<FloatArray>, u: Float): FloatArray {
    val n = keys[0].size
    val uu = u.coerceIn(times[0], times[times.size - 1])
    var i = 0
    while (i < times.size - 2 && uu > times[i + 1]) i++
    val span = times[i + 1] - times[i]
    var f = if (span <= 0f) 0f else (uu - times[i]) / span
    f = f.coerceIn(0f, 1f)
    f = f * f * (3f - 2f * f)
    val a = keys[i]
    val b = keys[i + 1]
    return FloatArray(n) { k -> a[k] + (b[k] - a[k]) * f }
}

/** 四角独立半径的圆角矩形（有机水囊轮廓）。 */
private fun organicPath(
    w: Float,
    h: Float,
    rx: FloatArray,
    ry: FloatArray,
): Path {
    val k = 0.5523f
    val tlX = rx[0]; val tlY = ry[0]
    val trX = rx[1]; val trY = ry[1]
    val brX = rx[2]; val brY = ry[2]
    val blX = rx[3]; val blY = ry[3]
    return Path().apply {
        moveTo(tlX, 0f)
        lineTo(w - trX, 0f)
        cubicTo(w - trX + k * trX, 0f, w, trY - k * trY, w, trY)
        lineTo(w, h - brY)
        cubicTo(w, h - brY + k * brY, w - brX + k * brX, h, w - brX, h)
        lineTo(blX, h)
        cubicTo(blX - k * blX, h, 0f, h - blY + k * blY, 0f, h - blY)
        lineTo(0f, tlY)
        cubicTo(0f, tlY - k * tlY, tlX - k * tlX, 0f, tlX, 0f)
        close()
    }
}

/**
 * 球内一层缓慢旋转的水面（`.water-orb__wave--1/2`）。
 *
 * 局部坐标做法：以 `(left, top)` 为左上、`waveW × waveH` 为大小的有机圆角块，
 * 绕球心旋转、再叠一点中点位移；渐变从块顶白到底淡出。
 */
private fun DrawScope.waveSurface(
    center: Offset,
    left: Float,
    top: Float,
    waveW: Float,
    waveH: Float,
    rx: FloatArray,
    ry: FloatArray,
    degrees: Float,
    dx: Float,
    dy: Float,
    topColor: Color,
    midColor: Color,
    ripple: Float,
) {
    val rX = FloatArray(4) { rx[it] * waveW }
    val rY = FloatArray(4) { ry[it] * waveH }
    val p = organicPath(waveW, waveH, rX, rY)
    // 形变瞬间水面更汹涌一点（幅度跟着涟漪走，落定即回到常态）
    val boost = 1f + ripple * 1.6f
    rotate(degrees, pivot = center) {
        translate(left = left + dx * boost, top = top + dy * boost) {
            drawPath(
                path = p,
                brush = Brush.verticalGradient(
                    0f to topColor,
                    0.45f to midColor,
                    1f to Color.Transparent,
                    startY = 0f,
                    endY = waveH,
                ),
            )
            // inset 0 1.5px 4px rgba(255,255,255,.28)：水面顶部那道细亮边
            drawPath(
                path = p,
                brush = Brush.verticalGradient(
                    0f to topColor.copy(alpha = min(1f, topColor.alpha * 1.6f)),
                    0.08f to Color.Transparent,
                    startY = 0f,
                    endY = waveH,
                ),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

/** 设计稿 `orbPokeWobble` 的四个关键帧（0 / .22 / .46 / .70 / 1）。 */
private fun wobbleScale(p: Float): Pair<Float, Float> {
    val keys = floatArrayOf(0f, 0.22f, 0.46f, 0.70f, 1f)
    val sx = floatArrayOf(1f, 1.12f, 0.92f, 1.04f, 1f)
    val sy = floatArrayOf(1f, 0.89f, 1.08f, 0.97f, 1f)
    val t = p.coerceIn(0f, 1f)
    for (i in 0 until keys.size - 1) {
        if (t <= keys[i + 1]) {
            val span = keys[i + 1] - keys[i]
            val f = if (span <= 0f) 0f else (t - keys[i]) / span
            // 缓入缓出，避免折线感
            val e = f * f * (3f - 2f * f)
            return Pair(
                sx[i] + (sx[i + 1] - sx[i]) * e,
                sy[i] + (sy[i + 1] - sy[i]) * e,
            )
        }
    }
    return Pair(1f, 1f)
}
