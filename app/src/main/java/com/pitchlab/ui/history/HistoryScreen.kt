package com.pitchlab.ui.history

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitchlab.domain.model.PitchSession
import com.pitchlab.ui.theme.AlarmRed
import com.pitchlab.ui.theme.LaserCyan
import com.pitchlab.ui.theme.PitchBlack
import com.pitchlab.ui.theme.PitchSurface
import com.pitchlab.ui.theme.RadarAmber
import com.pitchlab.ui.theme.StrikeZoneGreen
import com.pitchlab.ui.theme.TextPrimary
import com.pitchlab.ui.theme.TextSecondary
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 歷史頁：每一球的速度、位移、好球判定與錄影檔案。
 *
 * 為什麼要在 Phase 2 就做這一頁：
 * 投球練習真正的價值在「比較」——今天跟上一週的球速、進壘點分布。
 * 沒有歷史，前面兩階段的量測只是一次性的數字。
 */
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PitchBlack)
            .systemBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = TextPrimary
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "投球紀錄",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "共 ${state.sessions.size} 球 · 好球 ${state.strikeCount} / 壞球 ${state.ballCount}",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
            TextButton(onClick = viewModel::clearAll) {
                Text("清空", color = AlarmRed, fontSize = 12.sp)
            }
        }

        SummaryRow(
            fastestMph = state.fastestMph,
            averageMph = state.averageMph,
            total = state.sessions.size,
            strikeRate = if (state.strikeCount + state.ballCount > 0) {
                state.strikeCount.toDouble() / (state.strikeCount + state.ballCount)
            } else {
                null
            }
        )

        if (state.sessions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PlateMapCard(marks = state.plateMarks, modifier = Modifier.weight(1f))
                SpeedTrendCard(trend = state.speedTrend, modifier = Modifier.weight(1f))
            }
        }

        if (state.sessions.isEmpty() && !state.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "還沒有紀錄\n到相機頁投幾球，資料會自動存進來",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.sessions, key = { it.id }) { session ->
                    SessionRow(session = session, onDelete = { viewModel.delete(session.id) })
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun SummaryRow(fastestMph: Double?, averageMph: Double?, total: Int, strikeRate: Double?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryCell(
            label = "最快球速",
            value = fastestMph?.let { String.format("%.1f", it) } ?: "—",
            unit = "MPH",
            modifier = Modifier.weight(1f)
        )
        SummaryCell(
            label = "平均球速",
            value = averageMph?.let { String.format("%.1f", it) } ?: "—",
            unit = "MPH",
            modifier = Modifier.weight(1f)
        )
        SummaryCell(
            label = "總球數",
            value = total.toString(),
            unit = "PITCHES",
            modifier = Modifier.weight(1f)
        )
        SummaryCell(
            label = "好球率",
            value = strikeRate?.let { String.format("%.0f%%", it * 100) } ?: "—",
            unit = "STRIKE%",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SummaryCell(label: String, value: String, unit: String, modifier: Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PitchSurface),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(text = label, color = TextSecondary, fontSize = 9.sp)
            Text(text = value, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(text = unit, color = TextSecondary.copy(alpha = 0.6f), fontSize = 8.sp)
        }
    }
}

/**
 * 好球帶散點圖：把最近 20 球的進壘點畫在 17 x 23 in 的好球帶框上。
 *
 * 座標範圍刻意比好球帶大（x ±20 in、y 8~46 in），這樣「投到框外」的球
 * 才不會被裁掉——球投到哪裡、偏差多少，比只統計好壞球更有訓練價值。
 */
@Composable
private fun PlateMapCard(marks: List<PlateMark>, modifier: Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PitchSurface),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(text = "進壘點分佈", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(text = "最近 ${marks.size} 球", color = TextSecondary, fontSize = 8.sp)
            Spacer(Modifier.height(4.dp))
            Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
                val xMin = -20.0
                val xMax = 20.0
                val yMin = 8.0
                val yMax = 46.0
                fun px(x: Double) = ((x - xMin) / (xMax - xMin) * size.width).toFloat()
                fun py(y: Double) = ((yMax - y) / (yMax - yMin) * size.height).toFloat()

                // 好球帶框：寬 17 in（x -8.5 ~ 8.5），高 19 ~ 42 in
                val left = px(-8.5)
                val right = px(8.5)
                val top = py(42.0)
                val bottom = py(19.0)
                drawRect(
                    color = StrikeZoneGreen.copy(alpha = 0.55f),
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    style = Stroke(width = 1.5f)
                )
                // 九宮格
                for (i in 1..2) {
                    val gx = left + (right - left) * i / 3f
                    drawLine(StrikeZoneGreen.copy(alpha = 0.18f), Offset(gx, top), Offset(gx, bottom), 1f)
                    val gy = top + (bottom - top) * i / 3f
                    drawLine(StrikeZoneGreen.copy(alpha = 0.18f), Offset(left, gy), Offset(right, gy), 1f)
                }
                // 本壘板示意
                drawRect(
                    color = TextSecondary.copy(alpha = 0.5f),
                    topLeft = Offset(px(-8.5), py(8.0)),
                    size = Size(right - left, 2f)
                )
                // 散點（越新越亮：index 越大 alpha 越高）
                marks.reversed().forEachIndexed { index, mark ->
                    val alpha = 0.35f + 0.55f * (index + 1) / marks.size.toFloat()
                    val color = when (mark.isStrike) {
                        true -> StrikeZoneGreen
                        false -> AlarmRed
                        null -> RadarAmber
                    }
                    drawCircle(
                        color = color.copy(alpha = alpha),
                        radius = 2.6f,
                        center = Offset(px(mark.xInches), py(mark.yInches))
                    )
                }
            }
            Text(
                text = "綠=好球 紅=壞球 黃=未判定",
                color = TextSecondary.copy(alpha = 0.7f),
                fontSize = 7.sp
            )
        }
    }
}

/** 最近 12 球球速折線；估速與雷達校準用不同顏色點區分 */
@Composable
private fun SpeedTrendCard(trend: List<Double>, modifier: Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PitchSurface),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(text = "球速趨勢", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (trend.isEmpty()) "尚無球速" else "最近 ${trend.size} 球（舊 → 新）",
                color = TextSecondary,
                fontSize = 8.sp
            )
            Spacer(Modifier.height(4.dp))
            Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
                if (trend.isEmpty()) return@Canvas
                val minValue = (trend.min() - 2.0).coerceAtLeast(1.0)
                val maxValue = trend.max() + 2.0
                val span = (maxValue - minValue).coerceAtLeast(1.0)
                val stepX = if (trend.size <= 1) 0f else size.width / (trend.size - 1)
                fun py(v: Double) = ((maxValue - v) / span * size.height * 0.86f + size.height * 0.07f).toFloat()

                // 基準線：平均
                val avg = trend.average()
                drawLine(
                    color = TextSecondary.copy(alpha = 0.35f),
                    start = Offset(0f, py(avg)),
                    end = Offset(size.width, py(avg)),
                    strokeWidth = 1f
                )
                val path = Path()
                trend.forEachIndexed { index, value ->
                    val point = Offset(stepX * index, py(value))
                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                drawPath(path = path, color = LaserCyan, style = Stroke(width = 2f))
                trend.forEachIndexed { index, value ->
                    drawCircle(color = LaserCyan, radius = 3f, center = Offset(stepX * index, py(value)))
                }
            }
            Text(
                text = trend.takeIf { it.isNotEmpty() }
                    ?.let { "${String.format("%.1f", it.min())} – ${String.format("%.1f", it.max())} MPH" }
                    ?: "—",
                color = TextSecondary.copy(alpha = 0.7f),
                fontSize = 7.sp
            )
        }
    }
}

@Composable
private fun SessionRow(session: PitchSession, onDelete: () -> Unit) {
    val formatter = remember { SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()) }
    val context = LocalContext.current
    val strikeLabel = when (session.isStrike) {
        true -> "好球"
        false -> "壞球"
        null -> "未判定"
    }
    val strikeColor = when (session.isStrike) {
        true -> StrikeZoneGreen
        false -> AlarmRed
        null -> TextSecondary
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = PitchSurface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = session.fastestMph?.let { String.format("%.1f", it) } ?: "—",
                        color = TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (session.calibrated) " MPH · CAL" else " MPH · EST",
                        color = if (session.calibrated) LaserCyan else TextSecondary,
                        fontSize = 9.sp
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatter.format(Date(session.startedAtMillis)),
                    color = TextSecondary,
                    fontSize = 10.sp
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "橫移 " + (session.horizontalBreakInches?.let { String.format("%+.1f in", it) } ?: "—"),
                        color = TextSecondary,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "縱移 " + (session.verticalBreakInches?.let { String.format("%+.1f in", it) } ?: "—"),
                        color = TextSecondary,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "軌跡 ${session.trackedFrames} 幀",
                        color = TextSecondary,
                        fontSize = 10.sp
                    )
                }
                val plate = if (session.plateXInches != null && session.plateYInches != null) {
                    "進壘點 ${String.format("%+.1f", session.plateXInches)} / " +
                        String.format("%+.1f", session.plateYInches) + " in"
                } else {
                    "進壘點 —"
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(strikeColor.copy(alpha = 0.18f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(text = strikeLabel, color = strikeColor, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(text = plate, color = TextSecondary, fontSize = 9.sp)
                }
                val poseScore = session.poseScore
                if (poseScore != null) {
                    val grade = when {
                        poseScore >= 85 -> "A"
                        poseScore >= 70 -> "B"
                        poseScore >= 55 -> "C"
                        else -> "D"
                    }
                    val detail = buildList {
                        session.separationDeg?.let { add("肩髖 ${String.format("%.0f", it)}°") }
                        session.elbowDeg?.let { add("肘 ${String.format("%.0f", it)}°") }
                        session.trunkTiltDeg?.let { add("前傾 ${String.format("%.0f", it)}°") }
                    }.joinToString(" / ")
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "姿勢 $poseScore · $grade" + if (detail.isNotEmpty()) " ｜ $detail" else "",
                        color = if (poseScore >= 70) StrikeZoneGreen else RadarAmber,
                        fontSize = 9.sp
                    )
                }
                val clipPath = session.clipPath
                if (!clipPath.isNullOrEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { openClip(context, clipPath) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        modifier = Modifier.height(22.dp)
                    ) {
                        Text(
                            text = "▶ 出手片段 · " + clipPath.substringAfterLast('/'),
                            color = StrikeZoneGreen,
                            fontSize = 9.sp
                        )
                    }
                }
                if (!session.videoPath.isNullOrEmpty()) {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = "影片 " + session.videoPath.substringAfterLast('/'),
                        color = RadarAmber,
                        fontSize = 9.sp
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "刪除",
                    tint = TextSecondary.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}


/**
 * 用系統播放器開啟剪輯片段。
 * 走 FileProvider 是因為 Android 7+ 禁止跨 App 傳 file:// URI，
 * 少了這段會直接拋 FileUriExposedException 而不是「無法播放」。
 */
private fun openClip(context: Context, path: String) {
    val file = File(path)
    if (!file.exists()) return
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
