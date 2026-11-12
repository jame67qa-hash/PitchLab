package com.pitchlab.ui.calibration

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.pitchlab.domain.model.CalibrationUiState
import com.pitchlab.domain.model.NormalizedPoint
import com.pitchlab.ui.theme.AlarmRed
import com.pitchlab.ui.theme.LaserCyan
import com.pitchlab.ui.theme.PitchSurface
import com.pitchlab.ui.theme.RadarAmber
import com.pitchlab.ui.theme.StrikeZoneGreen
import com.pitchlab.ui.theme.TextPrimary
import com.pitchlab.ui.theme.TextSecondary

/**
 * 四點標定覆蓋層。
 *
 * 之所以做成「凍結畫面 + 點四角」而不是即時預覽點擊：
 * - 投球時相機在動，球場上的人也會走動，即時畫面點角點很容易點偏；
 * - 凍結一張幀，使用者可以放大、慢慢對齊本壘板五邊形的外接框；
 * - 分析已暫停，不會出現「點完角點畫面又跳一格」的錯位。
 *
 * 角點順序：左後 → 右後 → 右前 → 左前（俯視逆時針），與 [CameraViewModel.fieldCorners] 對應。
 */
@Composable
fun CalibrationOverlay(
    state: CalibrationUiState,
    frame: Bitmap?,
    onTapCorner: (NormalizedPoint) -> Unit,
    onCameraHeightChange: (Float) -> Unit,
    onCameraPitchChange: (Float) -> Unit,
    onRadarChange: (String) -> Unit,
    onEstimatedChange: (String) -> Unit,
    onCompute: () -> Unit,
    onUndo: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onClearCalibration: () -> Unit,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.96f))
    ) {
        if (frame != null) {
            FrozenFrameWithCorners(
                bitmap = frame,
                corners = state.corners,
                onTapCorner = onTapCorner,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 92.dp, bottom = 320.dp)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = "場地標定 · 四點單應",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "把本壘板的外接矩形四角依序點出來（左後 → 右後 → 右前 → 左前）。" +
                    "點完後系統會用真實尺寸 17 in × 8.5 in 解出單應矩陣，好球帶框會自動依此對位。",
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = state.nextCornerHint,
                color = if (state.corners.size == 4) StrikeZoneGreen else LaserCyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = PitchSurface.copy(alpha = 0.95f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "相機高度 ${String.format("%.0f", state.cameraHeightIn)} in",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                        Slider(
                            value = state.cameraHeightIn,
                            onValueChange = onCameraHeightChange,
                            valueRange = 24f..72f,
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = LaserCyan,
                                activeTrackColor = LaserCyan,
                                inactiveTrackColor = TextSecondary.copy(alpha = 0.35f)
                            )
                        )
                    }
                    Text(
                        text = "鏡頭離地高度（好球帶高度 = 相機高度 − 落點縱向偏移）",
                        color = TextSecondary.copy(alpha = 0.75f),
                        fontSize = 9.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "俯仰 ${String.format("%+.1f", state.cameraPitchDeg)}°",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                        Slider(
                            value = state.cameraPitchDeg,
                            onValueChange = onCameraPitchChange,
                            valueRange = -15f..15f,
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = LaserCyan,
                                activeTrackColor = LaserCyan,
                                inactiveTrackColor = TextSecondary.copy(alpha = 0.35f)
                            )
                        )
                    }
                    Text(
                        text = "鏡頭上仰為正。上仰 5°、球在 55 ft 外，忽略俯仰會讓高度差約 5.8 ft",
                        color = TextSecondary.copy(alpha = 0.75f),
                        fontSize = 9.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(
                            label = "雷達實測 MPH",
                            value = state.radarMphInput,
                            onValueChange = onRadarChange,
                            modifier = Modifier.weight(1f)
                        )
                        NumberField(
                            label = "App 估算 MPH",
                            value = state.estimatedMphInput,
                            onValueChange = onEstimatedChange,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when {
                            state.speedScalePreview != null ->
                                "速度刻度 ${String.format("%.2f", state.speedScalePreview)}× · " +
                                    "之後所有球速與深度都會乘上這個倍率"
                            state.speedCalibrated -> "已套用過速度刻度，可重新輸入雷達值覆蓋"
                            else -> "填好兩個數字即可校準球速（留空則只做幾何標定）"
                        },
                        color = if (state.speedScalePreview != null) StrikeZoneGreen else TextSecondary,
                        fontSize = 10.sp
                    )

                    if (state.reprojectionErrorIn > 0.0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "本壘板寬 ${String.format("%.0f", state.plateWidthPx)} px · " +
                                "${String.format("%.1f", state.pixelsPerInch)} px/in · " +
                                "四角誤差 ${String.format("%.2f", state.reprojectionErrorIn)} in",
                            color = if (state.reprojectionErrorIn < 1.5) StrikeZoneGreen else RadarAmber,
                            fontSize = 10.sp
                        )
                    }

                    if (state.reprojectionErrorIn > 0.0) {
                        val grade = when {
                            state.reprojectionErrorIn < 0.5 -> "品質：優（可以直接投球）"
                            state.reprojectionErrorIn < 1.5 -> "品質：可接受"
                            else -> "品質：偏差偏大，建議重新點四角"
                        }
                        val gradeColor = when {
                            state.reprojectionErrorIn < 0.5 -> StrikeZoneGreen
                            state.reprojectionErrorIn < 1.5 -> RadarAmber
                            else -> AlarmRed
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(text = grade, color = gradeColor, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    }

                    state.message?.let { message ->
                        Spacer(Modifier.height(6.dp))
                        Text(text = message, color = TextSecondary, fontSize = 10.sp)
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(onClick = onCancel) {
                            Text("取消", color = TextSecondary, fontSize = 12.sp)
                        }
                        TextButton(onClick = onUndo) {
                            Text("撤銷", color = TextSecondary, fontSize = 12.sp)
                        }
                        TextButton(onClick = onReset) {
                            Text("重置", color = TextSecondary, fontSize = 12.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onCompute) {
                            Text("計算", color = LaserCyan, fontSize = 12.sp)
                        }
                        Button(
                            onClick = onSave,
                            enabled = state.corners.size == 4,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LaserCyan,
                                contentColor = Color.Black,
                                disabledContainerColor = TextSecondary.copy(alpha = 0.3f)
                            )
                        ) {
                            Text("儲存", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        TextButton(onClick = onClearCalibration) {
                            Text("清除既有標定", color = AlarmRed, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 凍結畫面 + 角點疊加。
 *
 * 座標換算的關鍵：Image 用了 ContentScale.Fit，內容區比容器小，
 * 點擊時必須先扣掉 letterbox 的偏移再歸一化，否則角點會整體偏移。
 */
@Composable
private fun FrozenFrameWithCorners(
    bitmap: Bitmap,
    corners: List<NormalizedPoint>,
    onTapCorner: (NormalizedPoint) -> Unit,
    modifier: Modifier = Modifier
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }
        val imageAspect = image.width.toFloat() / image.height.toFloat()
        val containerAspect = if (containerHeightPx > 0f) containerWidthPx / containerHeightPx else imageAspect

        val contentWidthPx: Float
        val contentHeightPx: Float
        if (imageAspect > containerAspect) {
            contentWidthPx = containerWidthPx
            contentHeightPx = containerWidthPx / imageAspect
        } else {
            contentHeightPx = containerHeightPx
            contentWidthPx = containerHeightPx * imageAspect
        }
        val offsetX = (containerWidthPx - contentWidthPx) / 2f
        val offsetY = (containerHeightPx - contentHeightPx) / 2f

        Image(
            bitmap = image,
            contentDescription = "標定用凍結畫面",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(corners.size) {
                    detectTapGestures { offset ->
                        val nx = (offset.x - offsetX) / contentWidthPx
                        val ny = (offset.y - offsetY) / contentHeightPx
                        if (nx in 0f..1f && ny in 0f..1f) {
                            onTapCorner(NormalizedPoint(nx, ny))
                        }
                    }
                }
        ) {
            fun toOffset(point: NormalizedPoint) = Offset(
                x = offsetX + point.x * contentWidthPx,
                y = offsetY + point.y * contentHeightPx
            )

            // 本壘板邊框（依點擊順序連線，最後一點連回第一點）
            if (corners.size >= 2) {
                val path = androidx.compose.ui.graphics.Path()
                val first = toOffset(corners[0])
                path.moveTo(first.x, first.y)
                corners.drop(1).forEach { point ->
                    val p = toOffset(point)
                    path.lineTo(p.x, p.y)
                }
                if (corners.size == 4) path.close()
                drawPath(
                    path = path,
                    color = LaserCyan.copy(alpha = 0.9f),
                    style = Stroke(width = 2.5f)
                )
            }

            corners.forEachIndexed { index, point ->
                val p = toOffset(point)
                drawCircle(color = LaserCyan.copy(alpha = 0.25f), radius = 16f, center = p)
                drawCircle(color = LaserCyan, radius = 6f, center = p)
                drawCircle(color = Color.Black, radius = 2f, center = p)
            }
        }

        // 角點編號用 Compose Text 畫，避免 Canvas 文字排版差異
        corners.forEachIndexed { index, point ->
            Text(
                text = "${index + 1}",
                color = Color.Black,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .offset(
                        x = with(density) { (offsetX + point.x * contentWidthPx).toDp() } +
                            (4).dp,
                        y = with(density) { (offsetY + point.y * contentHeightPx).toDp() } -
                            (18).dp
                    )
                    .size(14.dp)
                    .background(LaserCyan, RoundedCornerShape(4.dp))
            )
        }

        if (corners.isEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "點擊本壘板「左後角」開始",
                    color = TextPrimary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 10.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = TextPrimary),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = LaserCyan,
            unfocusedBorderColor = TextSecondary.copy(alpha = 0.4f),
            focusedLabelColor = LaserCyan,
            unfocusedLabelColor = TextSecondary
        ),
        modifier = modifier.height(64.dp)
    )
}
