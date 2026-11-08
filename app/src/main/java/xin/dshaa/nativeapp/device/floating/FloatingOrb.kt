package xin.dshaa.nativeapp.device.floating

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Operit `SiriBall.kt`(529) + `BallParticles.kt`(233) 的逐参数移植。
 * 对照表：docs/76 §八 B1-B16。
 *
 * 注意：
 * - 外层 Box 是 ballSize*1.6，Canvas 是 ballSize*3.0（Operit 如此，不是笔误）；
 * - 粒子只在拖动（pressing）时显示，生成中只有白色 Loading 弧；
 * - 长按不触发语音（Operit 源码里长按整段被注释）；原生也不挂长按菜单（2026-11-09 用户要求删掉，
 *   点按展开的 WINDOW 里已有全部功能，长按菜单重复且突兀）。
 */
@Composable
fun FloatingOrb(
    modifier: Modifier = Modifier,
    size: Dp = 60.dp,
    active: Boolean = false,
    fadingOut: Boolean = false,
    onTap: () -> Unit = {},
    onDrag: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
) {
    val mainColor = Color(0xFF0A84FF)
    val accentColor1 = Color(0xFFBF5AF2)
    val accentColor2 = Color(0xFFFF375F)
    val accentColor3 = Color(0xFF00D4FF)

    var pressing by remember { mutableStateOf(false) }
    val particles = remember { ParticleSystem(2) }
    particles.UpdateEffect(pressing)

    val pressScale by animateFloatAsState(
        targetValue = if (pressing) 0.85f else 1f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "pressScale",
    )

    val infinite = rememberInfiniteTransition(label = "siri")
    val rotation by infinite.animateFloat(
        0f,
        360f,
        infiniteRepeatable(tween(15000, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation",
    )
    val breathe by infinite.animateFloat(
        0.95f,
        1f,
        infiniteRepeatable(tween(3000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val ripple1Scale by infinite.animateFloat(
        1f, 1.2f,
        infiniteRepeatable(tween(2500, easing = LinearOutSlowInEasing), RepeatMode.Restart),
        label = "ripple1Scale",
    )
    val ripple1Alpha by infinite.animateFloat(
        0.5f, 0f,
        infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Restart),
        label = "ripple1Alpha",
    )
    val ripple2Scale by infinite.animateFloat(
        1f, 1.2f,
        infiniteRepeatable(
            tween(2500, easing = LinearOutSlowInEasing),
            RepeatMode.Restart,
            initialStartOffset = StartOffset(833),
        ),
        label = "ripple2Scale",
    )
    val ripple2Alpha by infinite.animateFloat(
        0.5f, 0f,
        infiniteRepeatable(
            tween(2500, easing = LinearEasing),
            RepeatMode.Restart,
            initialStartOffset = StartOffset(833),
        ),
        label = "ripple2Alpha",
    )
    val ripple3Scale by infinite.animateFloat(
        1f, 1.2f,
        infiniteRepeatable(
            tween(2500, easing = LinearOutSlowInEasing),
            RepeatMode.Restart,
            initialStartOffset = StartOffset(1666),
        ),
        label = "ripple3Scale",
    )
    val ripple3Alpha by infinite.animateFloat(
        0.5f, 0f,
        infiniteRepeatable(
            tween(2500, easing = LinearEasing),
            RepeatMode.Restart,
            initialStartOffset = StartOffset(1666),
        ),
        label = "ripple3Alpha",
    )

    val fade = remember { Animatable(0f) }
    LaunchedEffect(fadingOut) {
        if (fadingOut) {
            fade.snapTo(0f)
            fade.animateTo(1f, tween(100, easing = LinearEasing))
        } else {
            fade.snapTo(0f)
        }
    }

    val loading = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            loading.animateTo(
                360f,
                infiniteRepeatable(tween(1000, easing = LinearEasing)),
            )
        } else {
            loading.snapTo(0f)
        }
    }

    Box(
        modifier = modifier
            .size(size * 1.6f)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pressing = true },
                    onDragEnd = {
                        pressing = false
                        onDragEnd()
                    },
                    onDragCancel = { pressing = false },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    // 长按不弹菜单、也不当作一次点按展开（2026-11-09 用户要求删掉长按功能）。
                    // 空回调会把长按吞掉，避免松手时落回 onTap。
                    onLongPress = { },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 3f)) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val baseRadius = (this.size.minDimension / 2f) * pressScale
            val fadeAlpha = if (fadingOut) 1f - fade.value else 1f
            if (fadeAlpha <= 0.01f) return@Canvas

            with(particles) {
                drawBackParticles(center, baseRadius * 0.5f)
            }

            // 3 层音波：颜色/透明度按 Operit 顺序（青/紫/蓝）。
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        Color.Transparent,
                        accentColor3.copy(alpha = ripple1Alpha * 0.25f * fadeAlpha),
                        Color.Transparent,
                    ),
                    center,
                    baseRadius * ripple1Scale,
                ),
                center = center,
                radius = baseRadius * ripple1Scale,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        Color.Transparent,
                        accentColor1.copy(alpha = ripple2Alpha * 0.2f * fadeAlpha),
                        Color.Transparent,
                    ),
                    center,
                    baseRadius * ripple2Scale,
                ),
                center = center,
                radius = baseRadius * ripple2Scale,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        Color.Transparent,
                        mainColor.copy(alpha = ripple3Alpha * 0.15f * fadeAlpha),
                        Color.Transparent,
                    ),
                    center,
                    baseRadius * ripple3Scale,
                ),
                center = center,
                radius = baseRadius * ripple3Scale,
            )

            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        mainColor.copy(alpha = 0.3f * fadeAlpha),
                        accentColor1.copy(alpha = 0.2f * fadeAlpha),
                        Color.Transparent,
                    ),
                    center,
                    baseRadius * breathe * 0.7f,
                ),
                center = center,
                radius = baseRadius * breathe * 0.7f,
                blendMode = BlendMode.Screen,
            )

            drawColorBlob(center, baseRadius, rotation, baseRadius * 0.2f * breathe, mainColor, 0.7f, fadeAlpha)
            drawColorBlob(center, baseRadius, rotation + 90f, baseRadius * 0.25f * breathe, accentColor1, 0.65f, fadeAlpha)
            drawColorBlob(center, baseRadius, rotation + 180f, baseRadius * 0.22f * breathe, accentColor2, 0.6f, fadeAlpha)
            drawColorBlob(center, baseRadius, rotation + 270f, baseRadius * 0.23f * breathe, accentColor3, 0.68f, fadeAlpha)

            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        Color.White.copy(alpha = 0.7f * fadeAlpha),
                        mainColor.copy(alpha = 0.6f * fadeAlpha),
                        accentColor1.copy(alpha = 0.5f * fadeAlpha),
                        Color.Transparent,
                    ),
                    center,
                    baseRadius * 0.65f * breathe,
                ),
                center = center,
                radius = baseRadius * 0.65f * breathe,
                blendMode = BlendMode.Screen,
            )

            val highlight = Offset(
                center.x - baseRadius * 0.25f,
                center.y - baseRadius * 0.25f,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        Color.White.copy(alpha = 0.5f * fadeAlpha),
                        Color.White.copy(alpha = 0.25f * fadeAlpha),
                        Color.Transparent,
                    ),
                    highlight,
                    baseRadius * 0.35f,
                ),
                center = highlight,
                radius = baseRadius * 0.35f,
                blendMode = BlendMode.Screen,
            )

            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.05f * fadeAlpha),
                        Color.White.copy(alpha = 0.15f * fadeAlpha),
                        Color.Transparent,
                    ),
                    center,
                    baseRadius * breathe,
                ),
                center = center,
                radius = baseRadius * breathe,
            )

            with(particles) {
                drawFrontParticles(center, baseRadius * 0.5f)
            }

            if (active) {
                drawArc(
                    color = Color.White,
                    startAngle = loading.value,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(
                        center.x - baseRadius * 0.5f,
                        center.y - baseRadius * 0.5f,
                    ),
                    size = Size(baseRadius, baseRadius),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }
}

private fun DrawScope.drawColorBlob(
    center: Offset,
    radius: Float,
    angle: Float,
    distance: Float,
    color: Color,
    sizeFactor: Float,
    fadeAlpha: Float,
) {
    val rad = angle * PI / 180f
    val blob = Offset(
        center.x + distance * cos(rad).toFloat(),
        center.y + distance * sin(rad).toFloat(),
    )
    drawCircle(
        brush = Brush.radialGradient(
            listOf(
                color.copy(alpha = 0.8f * fadeAlpha),
                color.copy(alpha = 0.5f * fadeAlpha),
                color.copy(alpha = 0.2f * fadeAlpha),
                Color.Transparent,
            ),
            blob,
            radius * sizeFactor,
        ),
        center = blob,
        radius = radius * sizeFactor,
        blendMode = BlendMode.Screen,
    )
}

// ---------------- 粒子系统（BallParticles.kt:27-232 的逐项移植） ----------------

private data class Vector3(val x: Float, val y: Float, val z: Float)

private class OrbParticle(
    var angle: Float,
    val speed: Float,
    val orbitRadius: Float,
    val tiltX: Float,
    val tiltY: Float,
    val color: Color,
    val accentColor: Color,
) {
    val trail = ArrayDeque<Vector3>()
    val maxTrail = 100
    val baseSize = 20f
}

private class ParticleSystem(count: Int) {
    private val particles = ArrayList<OrbParticle>()
    private var active by mutableStateOf(false)
    private val visibility = Animatable(0f)

    init {
        val palette = listOf(
            Color(0xFF0A84FF),
            Color(0xFFBF5AF2),
            Color(0xFFFF375F),
            Color(0xFF00D4FF),
            Color(0xFF30D158),
            Color(0xFFFF9F0A),
        )
        repeat(count) { index ->
            val main = palette.random()
            val accent = palette.filter { it != main }.random()
            particles += OrbParticle(
                angle = Random.nextFloat() * 360f,
                speed = (Random.nextFloat() * 1.2f + 1.2f) * (if (Random.nextBoolean()) 1 else -1),
                orbitRadius = Random.nextFloat() * 0.1f + 1.4f,
                tiltX = (if (index == 0) 60f else 150f) * (PI.toFloat() / 180f),
                tiltY = (if (index == 0) 30f else 120f) * (PI.toFloat() / 180f),
                color = main,
                accentColor = accent,
            )
        }
    }

    @Composable
    fun UpdateEffect(pressed: Boolean) {
        LaunchedEffect(pressed) {
            if (pressed) {
                active = true
                visibility.animateTo(1f, tween(400, easing = LinearEasing))
            } else {
                visibility.animateTo(0f, tween(300, easing = LinearEasing))
                active = false
                particles.forEach { it.trail.clear() }
            }
        }
        LaunchedEffect(Unit) {
            while (true) {
                withFrameNanos {
                    if (visibility.value > 0f) updateParticles()
                }
            }
        }
    }

    private fun updateParticles() {
        particles.forEach { p ->
            p.angle = (p.angle + p.speed) % 360f
            val rad = p.angle * (PI.toFloat() / 180f)
            val rawX = cos(rad) * p.orbitRadius
            val rawZ = sin(rad) * p.orbitRadius
            val y1 = -rawZ * sin(p.tiltX)
            val z1 = rawZ * cos(p.tiltX)
            val x2 = rawX * cos(p.tiltY) + z1 * sin(p.tiltY)
            val z2 = -rawX * sin(p.tiltY) + z1 * cos(p.tiltY)
            p.trail.addFirst(Vector3(x2, y1, z2))
            if (p.trail.size > p.maxTrail) p.trail.removeLast()
        }
    }

    fun DrawScope.drawBackParticles(center: Offset, baseRadius: Float) {
        if (visibility.value <= 0.01f) return
        drawTrails(center, baseRadius)
    }

    fun DrawScope.drawFrontParticles(center: Offset, baseRadius: Float) {
        if (visibility.value <= 0.01f) return
        drawTrails(center, baseRadius)
    }

    private fun DrawScope.drawTrails(center: Offset, baseRadius: Float) {
        val vis = visibility.value
        val focalLength = 1000f
        particles.forEach { p ->
            for (i in p.trail.lastIndex downTo 0) {
                val pos = p.trail[i]
                if (pos.z > 0) continue
                val edgeFade = if (pos.z > -0.3f) (pos.z / -0.3f).coerceIn(0f, 1f) else 1f
                val depth = pos.z * baseRadius + focalLength
                val scale = if (depth > 0) focalLength / depth else 1f
                val progress = i.toFloat() / p.maxTrail
                val sizeScale = scale * (1f - progress * 0.9f)
                val alphaFade = 1f - progress.pow(0.9f)
                val alphaBase = p.color.alpha * vis * alphaFade * edgeFade
                if (alphaBase <= 0.01f) continue
                val tailAlpha = (alphaBase * 0.08f).coerceIn(0f, 1f)
                val baseColor = if (i % 5 == 0) p.accentColor else p.color
                drawCircle(
                    color = Color(
                        red = 0.85f + 0.15f * baseColor.red,
                        green = 0.85f + 0.15f * baseColor.green,
                        blue = 0.85f + 0.15f * baseColor.blue,
                        alpha = tailAlpha,
                    ),
                    radius = p.baseSize * sizeScale,
                    center = Offset(
                        center.x + pos.x * baseRadius * scale,
                        center.y + pos.y * baseRadius * scale,
                    ),
                )
            }
        }
    }
}
