package com.pitchlab.ui.camera.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitchlab.domain.model.CameraUiState
import com.pitchlab.domain.model.NormalizedPoint
import com.pitchlab.domain.model.NormalizedRect
import com.pitchlab.ui.theme.AlarmRed
import com.pitchlab.ui.theme.LaserCyan
import com.pitchlab.ui.theme.RadarAmber
import com.pitchlab.ui.theme.StrikeZoneGreen
import com.pitchlab.ui.theme.TextPrimary
import com.pitchlab.ui.theme.TextSecondary
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 預覽上層的 HUD：好球帶框 + 球體軌跡 + 預計落點。
 *
 * 座標系：分析幀與預覽都固定 16:9，因此用 letterbox 內容區換算歸一化座標即可對齊；
 * Phase 2 會換用 CameraX 的 CoordinateTransform 做像素級精確映射。
 */
@Composable
fun StrikeZoneOverlay(
    state: CameraUiState,
    modifier: Modifier = Modifier,
    onStrikeZoneChange: (NormalizedRect) -> Unit = {}
) {
    val zoneState = rememberUpdatedState(state.strikeZone)
    val aspectState = rememberUpdatedState(state.analysisAspectRatio)
    var dragStart by remember { mutableStateOf<NormalizedRect?>(null) }
    val trail = remember { mutableStateListOf<NormalizedPoint>() }

    LaunchedEffect(state.ballMarker) {
        val marker = state.ballMarker
        if (marker == null) {
            if (trail.isNotEmpty()) trail.clear()
        } else {
            trail.add(marker)
            while (trail.size > 28) trail.removeAt(0)
        }
    }

    Canvas(
        modifier = modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragStart = zoneState.value },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val start = dragStart ?: return@detectDragGestures
                        val content = letterboxRect(
                            viewWidth = size.width.toFloat(),
                            viewHeight = size.height.toFloat(),
                            aspect = aspectState.value
                        )
                        if (content.width <= 0f || content.height <= 0f) return@detectDragGestures
                        onStrikeZoneChange(
                            start.shifted(
                                dx = dragAmount.x / content.width,
                                dy = dragAmount.y / content.height
                            )
                        )
                    },
                    onDragEnd = { dragStart = null },
                    onDragCancel = { dragStart = null }
                )
            }
    ) {
        val content = letterboxRect(size.width, size.height, state.analysisAspectRatio)
        if (content.width <= 0f || content.height <= 0f) return@Canvas

        val zone = state.strikeZone
        val left = content.left + zone.left * content.width
        val right = content.left + zone.right * content.width
        val top = content.top + zone.top * content.height
        val bottom = content.top + zone.bottom * content.height
        val inset = (right - left) * 0.055f

        // 梯形：模擬從投手後方觀察好球帶產生的透視收縮
        val zonePath = Path().apply {
            moveTo(left + inset, top)
            lineTo(right - inset, top)
            lineTo(right, bottom)
            lineTo(left, bottom)
            close()
        }

        drawPath(zonePath, color = StrikeZoneGreen.copy(alpha = 0.10f))

        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
        drawPath(
            path = zonePath,
            color = StrikeZoneGreen.copy(alpha = 0.85f),
            style = Stroke(width = 2.dp.toPx(), pathEffect = dash)
        )

        // 九宮格參考線
        for (i in 1..2) {
            val fraction = i / 3f
            val xTop = left + inset + (right - left - 2 * inset) * fraction
            val xBottom = left + (right - left) * fraction
            drawLine(
                color = StrikeZoneGreen.copy(alpha = 0.22f),
                start = Offset(xTop, top),
                end = Offset(xBottom, bottom),
                strokeWidth = 1.dp.toPx()
            )
            val y = top + (bottom - top) * fraction
            val xLeft = left + inset * (1f - fraction)
            val xRight = right - inset * (1f - fraction)
            drawLine(
                color = StrikeZoneGreen.copy(alpha = 0.22f),
                start = Offset(xLeft, y),
                end = Offset(xRight, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        // 標籤
        drawIntoCanvas { canvas ->
            val labelPaint = Paint().apply {
                isAntiAlias = true
                color = StrikeZoneGreen.copy(alpha = 0.9f).toArgb()
                textSize = 11.sp.toPx()
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = 0.12f
            }
            canvas.nativeCanvas.drawText("STRIKE ZONE", left + inset, top - 8.dp.toPx(), labelPaint)
        }

        // 軌跡
        for (index in 0 until trail.size - 1) {
            val alpha = (index.toFloat() / trail.size) * 0.7f
            val p0 = trail[index]
            val p1 = trail[index + 1]
            drawLine(
                color = LaserCyan.copy(alpha = alpha),
                start = Offset(content.left + p0.x * content.width, content.top + p0.y * content.height),
                end = Offset(content.left + p1.x * content.width, content.top + p1.y * content.height),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // 球體標記
        state.ballMarker?.let { marker ->
            val cx = content.left + marker.x * content.width
            val cy = content.top + marker.y * content.height
            val radius = max(
                6.dp.toPx(),
                state.ballRadiusNorm * content.width * 1.35f
            )
            drawCircle(
                color = LaserCyan.copy(alpha = 0.22f),
                radius = radius * 2.4f,
                center = Offset(cx, cy)
            )
            drawCircle(
                color = Color.White,
                radius = radius,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5.dp.toPx())
            )
            drawLine(
                color = LaserCyan,
                start = Offset(cx - radius * 1.9f, cy),
                end = Offset(cx + radius * 1.9f, cy),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = LaserCyan,
                start = Offset(cx, cy - radius * 1.9f),
                end = Offset(cx, cy + radius * 1.9f),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

/**
 * 雷達測速儀表盤。
 * 刻度 0~105 mph，指針隨實時球速擺動；底部顯示實測分析幀率。
 */
@Composable
fun RadarSpeedometer(
    speedMph: Double?,
    measuredFps: Double,
    requestedFps: Int,
    processingMs: Double,
    modifier: Modifier = Modifier
) {
    val maxMph = 105.0
    Canvas(modifier = modifier) {
        val strokeWidth = 12.dp.toPx()
        val radius = (size.minDimension - strokeWidth * 2f) / 2f
        if (radius <= 0f) return@Canvas
        val center = Offset(size.width / 2f, size.height / 2f)
        val startAngle = 150f
        val sweepTotal = 240f

        drawArc(
            color = Color.White.copy(alpha = 0.08f),
            startAngle = startAngle,
            sweepAngle = sweepTotal,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        val fraction = ((speedMph ?: 0.0) / maxMph).coerceIn(0.0, 1.0).toFloat()

        drawArc(
            color = if (fraction > 0f) RadarAmber else Color.Transparent,
            startAngle = startAngle,
            sweepAngle = sweepTotal * fraction,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        val tickRadius = radius - strokeWidth
        for (mph in 0..105 step 15) {
            val angle = Math.toRadians((startAngle + sweepTotal * (mph / maxMph)).toDouble())
            val outer = Offset(
                center.x + cos(angle).toFloat() * tickRadius,
                center.y + sin(angle).toFloat() * tickRadius
            )
            val inner = Offset(
                center.x + cos(angle).toFloat() * (tickRadius - 7.dp.toPx()),
                center.y + sin(angle).toFloat() * (tickRadius - 7.dp.toPx())
            )
            drawLine(
                color = if (mph <= (speedMph ?: 0.0)) RadarAmber else TextSecondary.copy(alpha = 0.35f),
                start = inner,
                end = outer,
                strokeWidth = 2.dp.toPx()
            )
        }

        // 指針
        val needleAngle = Math.toRadians((startAngle + sweepTotal * fraction).toDouble())
        val needleEnd = Offset(
            center.x + cos(needleAngle).toFloat() * (radius * 0.72f),
            center.y + sin(needleAngle).toFloat() * (radius * 0.72f)
        )
        drawLine(
            color = if (speedMph != null) AlarmRed else TextSecondary,
            start = Offset(
                center.x - cos(needleAngle).toFloat() * (radius * 0.12f),
                center.y - sin(needleAngle).toFloat() * (radius * 0.12f)
            ),
            end = needleEnd,
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(color = LaserCyan, radius = 5.dp.toPx(), center = center)

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val valuePaint = Paint().apply {
                isAntiAlias = true
                color = TextPrimary.toArgb()
                textAlign = Paint.Align.CENTER
                textSize = 30.sp.toPx()
                typeface = Typeface.create("monospace", Typeface.BOLD)
            }
            val unitPaint = Paint().apply {
                isAntiAlias = true
                color = RadarAmber.toArgb()
                textAlign = Paint.Align.CENTER
                textSize = 11.sp.toPx()
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = 0.1f
            }
            val footPaint = Paint().apply {
                isAntiAlias = true
                color = TextSecondary.copy(alpha = 0.8f).toArgb()
                textAlign = Paint.Align.CENTER
                textSize = 9.sp.toPx()
                typeface = Typeface.create("monospace", Typeface.NORMAL)
            }

            val valueText = speedMph?.let { String.format("%.1f", it) } ?: "—"
            native.drawText(valueText, center.x, center.y + 12.dp.toPx(), valuePaint)
            native.drawText("MPH · EST", center.x, center.y + 27.dp.toPx(), unitPaint)
            native.drawText(
                "PROC ${String.format("%.1f", processingMs)}ms",
                center.x,
                center.y + 42.dp.toPx(),
                footPaint
            )
            native.drawText(
                "LIVE ${String.format("%.0f", measuredFps)}fps / REQ ${requestedFps}fps",
                center.x,
                size.height - 2.dp.toPx(),
                footPaint
            )
        }
    }
}

/** 計算 letterbox 內容區（保持圖像寬高比，多餘區域留黑） */
fun letterboxRect(viewWidth: Float, viewHeight: Float, aspect: Float): Rect {
    if (viewWidth <= 0f || viewHeight <= 0f || aspect <= 0f) {
        return Rect(0f, 0f, viewWidth, viewHeight)
    }
    val viewAspect = viewWidth / viewHeight
    return if (viewAspect > aspect) {
        val height = viewHeight
        val width = height * aspect
        val left = (viewWidth - width) / 2f
        Rect(left, 0f, left + width, height)
    } else {
        val width = viewWidth
        val height = width / aspect
        val top = (viewHeight - height) / 2f
        Rect(0f, top, width, top + height)
    }
}

/** 整體平移好球帶 */
fun NormalizedRect.shifted(dx: Float, dy: Float): NormalizedRect {
    val width = this.width
    val height = this.height
    var newLeft = left + dx
    var newTop = top + dy
    if (newLeft < 0f) newLeft = 0f
    if (newTop < 0f) newTop = 0f
    if (newLeft + width > 1f) newLeft = 1f - width
    if (newTop + height > 1f) newTop = 1f - height
    return copy(left = newLeft, top = newTop, right = newLeft + width, bottom = newTop + height)
}
