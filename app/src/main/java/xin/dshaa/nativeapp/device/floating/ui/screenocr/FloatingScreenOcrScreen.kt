package xin.dshaa.nativeapp.device.floating.ui.screenocr

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xin.dshaa.nativeapp.chat.ChatMirror
import xin.dshaa.ui.shellapi.chat.PendingFile
import xin.dshaa.nativeapp.device.floating.FloatingWindowState

private data class CropBounds(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

private enum class SelectionHandle {
    NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, LEFT, RIGHT, TOP, BOTTOM
}

/**
 * Operit `FloatingScreenOcrScreen.kt`(883) 的原生移植（docs/76 §十 O1-O11）。
 *
 * 手绘轨迹 → 包围盒 → 可拖 8 手柄 → 裁剪 → ML Kit 中英 OCR → 文本附件 → 回 previousMode。
 * 截图由 FloatingWindowManager 在切模式前抓（先切全屏再截会把黑底截进去）。
 */
@Composable
fun FloatingScreenOcrScreen(
    state: FloatingWindowState,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val handleRadiusPx = with(density) { 18.dp.toPx() }
    val minSizePx = with(density) { 32.dp.toPx() }

    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    val points = remember { mutableStateListOf<Offset>() }
    var selectionRect by remember { mutableStateOf<Rect?>(null) }
    var showConfirm by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var activeHandle by remember { mutableStateOf(SelectionHandle.NONE) }
    var handleStartRect by remember { mutableStateOf<Rect?>(null) }
    var handleStartPos by remember { mutableStateOf(Offset.Zero) }
    var error by remember { mutableStateOf<String?>(state.ocrError) }
    val bitmap: Bitmap? = state.ocrBitmap

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) {
        if (bitmap == null && error == null) {
            error = "没有拿到截图，请回到聊天页重试"
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { overlaySize = it },
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }

        if (bitmap != null) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.99f }
                    .pointerInput(isBusy, showConfirm, overlaySize) {
                        if (isBusy) return@pointerInput
                        if (!showConfirm) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    points.clear()
                                    points.add(offset)
                                    selectionRect = null
                                    showConfirm = false
                                },
                                onDrag = { change, _ ->
                                    val pos = change.position
                                    val last = points.lastOrNull()
                                    if (last == null || (pos - last).getDistance() >= 2.5f) {
                                        points.add(pos)
                                    }
                                },
                                onDragCancel = {
                                    points.clear()
                                    selectionRect = null
                                },
                                onDragEnd = {
                                    if (points.size < 3) {
                                        toast("圈选太小，重新画一下")
                                        points.clear()
                                        return@detectDragGestures
                                    }
                                    val minX = points.minOf { it.x }
                                    val minY = points.minOf { it.y }
                                    val maxX = points.maxOf { it.x }
                                    val maxY = points.maxOf { it.y }
                                    if (maxX - minX < 10f || maxY - minY < 10f) {
                                        toast("圈选太小，重新画一下")
                                        points.clear()
                                        return@detectDragGestures
                                    }
                                    selectionRect = clampSelectionRect(
                                        Rect(minX, minY, maxX, maxY),
                                        overlaySize,
                                        minSizePx,
                                    )
                                    showConfirm = true
                                },
                            )
                            return@pointerInput
                        }
                        detectDragGestures(
                            onDragStart = { offset ->
                                val rect = selectionRect ?: return@detectDragGestures
                                val handle = hitTestHandle(offset, rect, handleRadiusPx)
                                activeHandle = handle
                                if (handle == SelectionHandle.NONE) {
                                    handleStartRect = null
                                    return@detectDragGestures
                                }
                                handleStartRect = rect
                                handleStartPos = offset
                            },
                            onDragCancel = {
                                activeHandle = SelectionHandle.NONE
                                handleStartRect = null
                            },
                            onDragEnd = {
                                activeHandle = SelectionHandle.NONE
                                handleStartRect = null
                            },
                            onDrag = { change, _ ->
                                val handle = activeHandle
                                val start = handleStartRect ?: return@detectDragGestures
                                if (handle == SelectionHandle.NONE) return@detectDragGestures
                                val delta = change.position - handleStartPos
                                selectionRect = clampSelectionRect(
                                    applyHandleDelta(start, delta, handle),
                                    overlaySize,
                                    minSizePx,
                                )
                                change.consume()
                            },
                        )
                    },
            ) {
                drawRect(Color(0x591976D2))
                selectionRect?.let { rect ->
                    drawRect(
                        color = Color.Transparent,
                        topLeft = rect.topLeft,
                        size = rect.size,
                        blendMode = BlendMode.Clear,
                    )
                    drawRect(
                        color = Color.White.copy(alpha = 0.85f),
                        topLeft = rect.topLeft,
                        size = rect.size,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    val corner = 10.dp.toPx()
                    val sw = 4.dp.toPx()
                    drawLine(Color.White, rect.topLeft, rect.topLeft + Offset(corner, 0f), sw)
                    drawLine(Color.White, rect.topLeft, rect.topLeft + Offset(0f, corner), sw)
                    drawLine(Color.White, rect.topRight, rect.topRight - Offset(corner, 0f), sw)
                    drawLine(Color.White, rect.topRight, rect.topRight + Offset(0f, corner), sw)
                    drawLine(Color.White, rect.bottomLeft, rect.bottomLeft + Offset(corner, 0f), sw)
                    drawLine(Color.White, rect.bottomLeft - Offset(0f, corner), rect.bottomLeft, sw)
                    drawLine(Color.White, rect.bottomRight, rect.bottomRight - Offset(corner, 0f), sw)
                    drawLine(Color.White, rect.bottomRight - Offset(0f, corner), rect.bottomRight, sw)

                    if (showConfirm) {
                        val r = 7.dp.toPx()
                        listOf(
                            rect.topLeft, rect.topRight, rect.bottomLeft, rect.bottomRight,
                            Offset(rect.center.x, rect.top), Offset(rect.center.x, rect.bottom),
                            Offset(rect.left, rect.center.y), Offset(rect.right, rect.center.y),
                        ).forEach { drawCircle(Color.White, r, it) }
                    }
                }

                if (!showConfirm && points.size >= 2) {
                    val path = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                    }
                    drawPath(
                        path,
                        color = Color(0xFF00E5FF).copy(alpha = 0.4f),
                        style = Stroke(12.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                        blendMode = BlendMode.Screen,
                    )
                    drawPath(
                        path,
                        color = Color(0xFF00E5FF).copy(alpha = 0.8f),
                        style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                        blendMode = BlendMode.Screen,
                    )
                    drawPath(
                        path,
                        color = Color.White,
                        style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }

        if (error != null) {
            Text(
                error!!,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = Color.White,
            )
        }

        if (showConfirm && selectionRect != null) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xCCD9503F),
                    modifier = Modifier.clickable {
                        points.clear()
                        selectionRect = null
                        showConfirm = false
                    },
                ) {
                    Icon(
                        Icons.Default.Close,
                        "取消",
                        tint = Color.White,
                        modifier = Modifier.padding(14.dp).size(24.dp),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xCC2F6FE0),
                    modifier = Modifier.clickable(enabled = !isBusy) {
                        val src = state.ocrBitmap ?: return@clickable
                        val rect = selectionRect ?: return@clickable
                        if (isBusy) return@clickable
                        val bounds = computeCropBounds(src, rect, overlaySize) ?: return@clickable
                        isBusy = true
                        scope.launch {
                            try {
                                val cropped = withContext(Dispatchers.Default) {
                                    Bitmap.createBitmap(
                                        src,
                                        bounds.left,
                                        bounds.top,
                                        bounds.width,
                                        bounds.height,
                                    )
                                }
                                val text = withContext(Dispatchers.IO) {
                                    OcrEngine.recognize(cropped)
                                }
                                android.util.Log.d(
                                    "FloatingOcr",
                                    "crop=${bounds.width}x${bounds.height} chars=${text.length} text=${text.take(80)}",
                                )
                                if (text.isBlank()) toast("没有识别到文字")
                                val imgW = src.width.toFloat().coerceAtLeast(1f)
                                val imgH = src.height.toFloat().coerceAtLeast(1f)
                                val content = buildString {
                                    append("【屏幕圈选识别】位置: screen_selection")
                                    append("; image_px=${src.width}x${src.height}")
                                    append(
                                        "; rect_norm=${fmt4(bounds.left / imgW)},${fmt4(bounds.top / imgH)}," +
                                            "${fmt4((bounds.left + bounds.width) / imgW)}," +
                                            "${fmt4((bounds.top + bounds.height) / imgH)}"
                                    )
                                    append("\n\n")
                                    append(text.ifBlank { "（没有识别到文字）" })
                                    append("\n\n")
                                    append("请结合这张截图里圈出的文字回答。")
                                }
                                val session = ChatMirror.state
                                if (session == null) {
                                    toast("还没有打开的会话，先回鲸歌选一个会话")
                                } else {
                                    session.pendingFiles += PendingFile("screen_ocr.txt", content)
                                    toast("已添加圈选识别附件")
                                    onClose()
                                }
                            } catch (t: Throwable) {
                                toast("OCR 失败：${t.message ?: "未知错误"}")
                            } finally {
                                isBusy = false
                            }
                        }
                    },
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(14.dp).size(24.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Default.Check,
                            "确认",
                            tint = Color.White,
                            modifier = Modifier.padding(14.dp).size(24.dp),
                        )
                    }
                }
            }
        }

        Surface(
            // 全屏 overlay 从 y=0 铺起，状态栏盖在最上层：不加 statusBarsPadding 的话
            // 这个 32dp 关闭键正好落在状态栏的触摸区里，点击会被系统拦走（2026-11-08 实测点不到）。
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp)
                .size(32.dp),
            shape = RoundedCornerShape(8.dp),
            color = Color.Black.copy(alpha = 0.45f),
        ) {
            Icon(
                Icons.Default.Close,
                "关闭",
                tint = Color.White,
                modifier = Modifier.padding(7.dp).clickable { onClose() },
            )
        }
    }
}

private fun fmt4(v: Float): String =
    ((v * 10000f).roundToInt() / 10000f).toString()

private fun computeCropBounds(src: Bitmap, rectUi: Rect, overlay: IntSize): CropBounds? {
    if (overlay.width <= 0 || overlay.height <= 0) return null
    val scale = min(
        overlay.width.toFloat() / src.width.toFloat(),
        overlay.height.toFloat() / src.height.toFloat(),
    )
    val drawW = src.width * scale
    val drawH = src.height * scale
    val offsetX = (overlay.width - drawW) / 2f
    val offsetY = (overlay.height - drawH) / 2f
    val left = rectUi.left.coerceIn(offsetX, offsetX + drawW)
    val top = rectUi.top.coerceIn(offsetY, offsetY + drawH)
    val right = rectUi.right.coerceIn(offsetX, offsetX + drawW)
    val bottom = rectUi.bottom.coerceIn(offsetY, offsetY + drawH)
    val leftPx = ((left - offsetX) / scale).toInt().coerceIn(0, src.width - 1)
    val topPx = ((top - offsetY) / scale).toInt().coerceIn(0, src.height - 1)
    val rightPx = ((right - offsetX) / scale).toInt().coerceIn(leftPx + 1, src.width)
    val bottomPx = ((bottom - offsetY) / scale).toInt().coerceIn(topPx + 1, src.height)
    val w = (rightPx - leftPx).coerceAtLeast(1)
    val h = (bottomPx - topPx).coerceAtLeast(1)
    if (w <= 1 || h <= 1) return null
    return CropBounds(leftPx, topPx, w, h)
}

private fun hitTestHandle(pos: Offset, rect: Rect, r: Float): SelectionHandle {
    fun near(p: Offset) = (pos - p).getDistance() <= r
    return when {
        near(rect.topLeft) -> SelectionHandle.TOP_LEFT
        near(rect.topRight) -> SelectionHandle.TOP_RIGHT
        near(rect.bottomLeft) -> SelectionHandle.BOTTOM_LEFT
        near(rect.bottomRight) -> SelectionHandle.BOTTOM_RIGHT
        near(Offset(rect.center.x, rect.top)) -> SelectionHandle.TOP
        near(Offset(rect.center.x, rect.bottom)) -> SelectionHandle.BOTTOM
        near(Offset(rect.left, rect.center.y)) -> SelectionHandle.LEFT
        near(Offset(rect.right, rect.center.y)) -> SelectionHandle.RIGHT
        rect.contains(pos) -> SelectionHandle.MOVE
        else -> SelectionHandle.NONE
    }
}

private fun clampSelectionRect(rect: Rect, overlay: IntSize, minSize: Float): Rect {
    var left = minOf(rect.left, rect.right)
    var top = minOf(rect.top, rect.bottom)
    var right = maxOf(rect.left, rect.right)
    var bottom = maxOf(rect.top, rect.bottom)
    right = left + (right - left).coerceAtLeast(minSize)
    bottom = top + (bottom - top).coerceAtLeast(minSize)
    val maxW = overlay.width.toFloat().coerceAtLeast(1f)
    val maxH = overlay.height.toFloat().coerceAtLeast(1f)
    if (left < 0f) {
        right -= left
        left = 0f
    }
    if (top < 0f) {
        bottom -= top
        top = 0f
    }
    if (right > maxW) {
        left -= (right - maxW)
        right = maxW
    }
    if (bottom > maxH) {
        top -= (bottom - maxH)
        bottom = maxH
    }
    left = left.coerceIn(0f, (maxW - minSize).coerceAtLeast(0f))
    top = top.coerceIn(0f, (maxH - minSize).coerceAtLeast(0f))
    right = right.coerceIn(left + minSize, maxW)
    bottom = bottom.coerceIn(top + minSize, maxH)
    return Rect(left, top, right, bottom)
}

private fun applyHandleDelta(start: Rect, delta: Offset, handle: SelectionHandle): Rect =
    when (handle) {
        SelectionHandle.MOVE -> Rect(
            start.left + delta.x, start.top + delta.y,
            start.right + delta.x, start.bottom + delta.y,
        )
        SelectionHandle.TOP_LEFT -> Rect(
            start.left + delta.x, start.top + delta.y, start.right, start.bottom,
        )
        SelectionHandle.TOP_RIGHT -> Rect(
            start.left, start.top + delta.y, start.right + delta.x, start.bottom,
        )
        SelectionHandle.BOTTOM_LEFT -> Rect(
            start.left + delta.x, start.top, start.right, start.bottom + delta.y,
        )
        SelectionHandle.BOTTOM_RIGHT -> Rect(
            start.left, start.top, start.right + delta.x, start.bottom + delta.y,
        )
        SelectionHandle.LEFT -> Rect(
            start.left + delta.x, start.top, start.right, start.bottom,
        )
        SelectionHandle.RIGHT -> Rect(
            start.left, start.top, start.right + delta.x, start.bottom,
        )
        SelectionHandle.TOP -> Rect(
            start.left, start.top + delta.y, start.right, start.bottom,
        )
        SelectionHandle.BOTTOM -> Rect(
            start.left, start.top, start.right, start.bottom + delta.y,
        )
        SelectionHandle.NONE -> start
    }
