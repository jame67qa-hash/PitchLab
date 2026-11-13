package com.pitchlab.ui.history

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.pitchlab.domain.cv.BaselineDelta
import com.pitchlab.domain.cv.MechanicsBaseline
import com.pitchlab.domain.cv.MechanicsBaselineCalculator
import com.pitchlab.domain.cv.ControlAnalyzer
import com.pitchlab.domain.cv.ControlReport
import com.pitchlab.domain.export.ExportOptions
import com.pitchlab.domain.export.ExportSelector
import com.pitchlab.domain.model.PitchSession
import com.pitchlab.domain.model.PitchType
import com.pitchlab.domain.cv.PitchTypeClassifier
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
    val context = LocalContext.current
    var showExportDialog by remember { mutableStateOf(false) }

    // 匯出完成 -> 交給系統分享（ViewModel 只給路徑，Intent 留在 UI 層）
    LaunchedEffect(state.shareZipPath) {
        val path = state.shareZipPath
        if (!path.isNullOrEmpty()) {
            shareZip(context, path)
            viewModel.clearShareRequest()
        }
    }

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
            IconButton(
                onClick = { showExportDialog = true },
                enabled = !state.exporting && state.sessions.isNotEmpty()
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = "匯出分享包",
                    tint = if (state.exporting || state.sessions.isEmpty()) TextSecondary else LaserCyan
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

        state.baseline?.let { baseline ->
            BaselineCard(baseline = baseline, deltas = state.baselineDeltas)
        }

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
        } else if (state.filteredSessions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "這個球種還沒有紀錄\n在相機頁選球種後再投幾球",
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
                if (state.availableTypes.isNotEmpty()) {
                    item(key = "type-filter") {
                        TypeFilterRow(
                            types = state.availableTypes,
                            selected = state.typeFilter,
                            onSelect = viewModel::setTypeFilter
                        )
                    }
                }
                val activeType = state.typeFilter
                val typeBaseline = state.typeBaseline
                if (activeType != null && typeBaseline != null) {
                    item(key = "type-baseline") {
                        TypeBaselineCard(type = activeType, baseline = typeBaseline)
                    }
                }
                if (state.controlReport.hasData) {
                    item(key = "control-card") { ControlCard(report = state.controlReport) }
                }
                items(state.filteredSessions, key = { it.id }) { session ->
                    SessionRow(session = session, onDelete = { viewModel.delete(session.id) })
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    if (showExportDialog) {
        ExportOptionsDialog(
            sessionCount = state.sessions.size,
            strikeCount = state.strikeCount,
            onDismiss = { showExportDialog = false },
            onConfirm = { options ->
                showExportDialog = false
                viewModel.exportAll(options)
            }
        )
    }
}

/**
 * 匯出條件對話框：三個決策點（好球/天數/媒體），預設值是「全部 + 300 MB 片段」。
 * 只匯出 CSV+PNG 的選項很重要——有些教練只想要表格，而且檔案小到一定能傳出去。
 */
@Composable
private fun ExportOptionsDialog(
    sessionCount: Int,
    strikeCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (ExportOptions) -> Unit
) {
    var strikesOnly by remember { mutableStateOf(false) }
    var daysBack by remember { mutableStateOf<Int?>(null) }
    var maxMediaBytes by remember { mutableStateOf(ExportOptions.DEFAULT.maxMediaBytes) }
    val options = ExportOptions(strikesOnly = strikesOnly, daysBack = daysBack, maxMediaBytes = maxMediaBytes)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("匯出分享包", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(checked = strikesOnly, onCheckedChange = { strikesOnly = it })
                    Text("只含好球（$strikeCount / $sessionCount）", color = TextPrimary, fontSize = 12.sp)
                }
                Text("時間範圍", color = TextSecondary, fontSize = 10.sp)
                ChoiceRow("全部紀錄", daysBack == null) { daysBack = null }
                ChoiceRow("最近 7 天", daysBack == 7) { daysBack = 7 }
                ChoiceRow("最近 30 天", daysBack == 30) { daysBack = 30 }
                Spacer(Modifier.height(6.dp))
                Text("影片媒體", color = TextSecondary, fontSize = 10.sp)
                ChoiceRow("含片段（上限 300 MB）", maxMediaBytes == 300L * 1024 * 1024) {
                    maxMediaBytes = 300L * 1024 * 1024
                }
                ChoiceRow("含片段（上限 100 MB）", maxMediaBytes == 100L * 1024 * 1024) {
                    maxMediaBytes = 100L * 1024 * 1024
                }
                ChoiceRow("不含影片（只匯出 CSV + PNG）", maxMediaBytes == ExportOptions.NO_MEDIA) {
                    maxMediaBytes = ExportOptions.NO_MEDIA
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "將匯出：${ExportSelector.describe(options)}",
                    color = LaserCyan,
                    fontSize = 10.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(options) }) {
                Text("開始匯出", color = LaserCyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
        },
        containerColor = PitchSurface
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, color = TextPrimary, fontSize = 12.sp)
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


/**
 * 個人基準卡：把「通用理想區間」換成「你自己的中位數」。
 *
 * 樣本不足 5 球時只顯示提示、不顯示數字：用兩球建立的基準會誤導使用者。
 * 偏差只標出超過容許誤差的項目，避免「每次都在差 0.4 度」的雜訊。
 */
@Composable
private fun BaselineCard(baseline: MechanicsBaseline, deltas: List<BaselineDelta>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PitchSurface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "個人基準",
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${baseline.sampleCount} 球中位數" +
                        (baseline.averageScore?.let { " · 平均 ${it.toInt()} 分" } ?: ""),
                    color = TextSecondary,
                    fontSize = 8.sp
                )
            }
            val fingerprint = baseline.fingerprint
            if (fingerprint != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = fingerprint.label() +
                        if (baseline.ignoredSampleCount > 0) {
                            " · 另有 ${baseline.ignoredSampleCount} 球屬其他機位，已略過"
                        } else {
                            " · 同機位"
                        },
                    color = TextSecondary,
                    fontSize = 8.sp
                )
            }
            if (!baseline.isUsable) {
                val need = MechanicsBaselineCalculator.MIN_SAMPLES - baseline.sampleCount
                Text(
                    text = when {
                        fingerprint != null && baseline.ignoredSampleCount > 0 ->
                            "此機位只有 ${baseline.sampleCount} 球，再投 $need 球才建立基準"
                        fingerprint == null ->
                            "尚未標定機位，再投 $need 球後開始顯示基準（未綁機位的基準可信度較低）"
                        else -> "再投 $need 球後開始顯示基準"
                    },
                    color = RadarAmber,
                    fontSize = 9.sp
                )
                return@Column
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BaselineCell("肩髖", baseline.separationDeg, "°", Modifier.weight(1f))
                BaselineCell("肘角", baseline.elbowDeg, "°", Modifier.weight(1f))
                BaselineCell("前傾", baseline.trunkTiltDeg, "°", Modifier.weight(1f))
                BaselineCell("放球高", baseline.releaseHeightRatio, "", Modifier.weight(1f))
            }
            if (deltas.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                val significant = deltas.filter { it.significant }
                Text(
                    text = if (significant.isEmpty()) {
                        "上一球：四項都在你的正常範圍內"
                    } else {
                        "上一球 vs 基準：" + significant.joinToString("、") {
                            "${it.label} ${it.deltaText(if (it.unit.isEmpty()) 2 else 0)}"
                        }
                    },
                    color = if (significant.isEmpty()) StrikeZoneGreen else RadarAmber,
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
private fun BaselineCell(label: String, value: Double?, unit: String, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(text = label, color = TextSecondary, fontSize = 8.sp)
        Text(
            text = value?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(text = unit.ifEmpty { "RATIO" }, color = TextSecondary.copy(alpha = 0.55f), fontSize = 7.sp)
    }
}


/**
 * 球種專屬基準：門檻 30 球。樣本不足時只顯示進度，不拿 3 球的「基準」去誤導人。
 */
@Composable
private fun TypeBaselineCard(type: PitchType, baseline: MechanicsBaseline) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = LaserCyan.copy(alpha = 0.08f),
            contentColor = TextPrimary
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${type.label}基準",
                    color = LaserCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${baseline.sampleCount} 球中位數" +
                        (baseline.averageScore?.let { " · 平均 ${it.toInt()} 分" } ?: ""),
                    color = TextSecondary,
                    fontSize = 8.sp
                )
            }
            if (!baseline.isUsable) {
                Text(
                    text = "再投 ${baseline.requiredSamples - baseline.sampleCount} 球才建立${type.label}基準（門檻 ${baseline.requiredSamples} 球）",
                    color = RadarAmber,
                    fontSize = 9.sp
                )
                return@Column
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BaselineCell("肩髖", baseline.separationDeg, "°", Modifier.weight(1f))
                BaselineCell("肘角", baseline.elbowDeg, "°", Modifier.weight(1f))
                BaselineCell("前傾", baseline.trunkTiltDeg, "°", Modifier.weight(1f))
                BaselineCell("放球高", baseline.releaseHeightRatio, "", Modifier.weight(1f))
            }
        }
    }
}

/**
 * 控球熱區：九宮格命中次數 + 平均落點 + 散佈半徑。
 *
 * 只把「好球帶內」的球填進九宮格，框外的球另外計數——若把框外硬塞進邊角，
 * 熱區會看起來比實際更準，失去診斷價值。散佈半徑是各球到平均落點的 RMS 距離，
 * 與裁判判決無關，能反映「投得準不準」。
 */
@Composable
private fun ControlCard(report: ControlReport) {
    val maxCount = report.cells.maxOfOrNull { it.count } ?: 0
    val centroidCell = report.centroidXInches?.let { x ->
        report.centroidYInches?.let { y -> ControlAnalyzer.cellIndex(x, y) }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = PitchSurface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("控球熱區", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${report.sampleCount} 球 · 散佈 " +
                        String.format(Locale.US, "%.1f", report.spreadRadiusInches ?: 0.0) + " in",
                    color = TextSecondary,
                    fontSize = 8.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Row {
                Column {
                    for (row in 0 until ControlAnalyzer.GRID) {
                        Row {
                            for (column in 0 until ControlAnalyzer.GRID) {
                                val index = row * ControlAnalyzer.GRID + column
                                HeatCell(
                                    count = report.cells[index].count,
                                    maxCount = maxCount,
                                    isCentroid = centroidCell == index
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.align(Alignment.CenterVertically)) {
                    Text(
                        text = "控球 ${report.score ?: "—"} ${report.grade}",
                        color = if ((report.score ?: 0) >= 70) StrikeZoneGreen else RadarAmber,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "平均落點 ${ControlAnalyzer.describeOffset(report.centroidXInches, report.centroidYInches)}",
                        color = TextSecondary,
                        fontSize = 9.sp
                    )
                    Text(
                        text = "框外 ${report.outsideZoneCount} 球 · 散佈越小越準",
                        color = TextSecondary,
                        fontSize = 9.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun HeatCell(count: Int, maxCount: Int, isCentroid: Boolean) {
    val intensity = if (maxCount == 0) 0f else count.toFloat() / maxCount
    Box(
        modifier = Modifier
            .size(30.dp)
            .padding(1.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(LaserCyan.copy(alpha = 0.08f + 0.42f * intensity))
            .then(
                if (isCentroid) Modifier.border(1.dp, RadarAmber, RoundedCornerShape(5.dp))
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count == 0) "·" else count.toString(),
            color = if (count == 0) TextSecondary.copy(alpha = 0.4f) else TextPrimary,
            fontSize = 9.sp
        )
    }
}

@Composable
private fun TypeFilterRow(types: List<PitchType>, selected: PitchType?, onSelect: (PitchType?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TypeChip(label = "全部", active = selected == null, onClick = { onSelect(null) })
        types.forEach { type ->
            TypeChip(label = type.label, active = selected == type, onClick = { onSelect(type) })
        }
    }
}

@Composable
private fun TypeChip(label: String, active: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(text = label, fontSize = 10.sp) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (active) LaserCyan.copy(alpha = 0.20f) else PitchSurface,
            labelColor = if (active) LaserCyan else TextSecondary
        )
    )
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
                    session.pitchType?.let { type ->
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(LaserCyan.copy(alpha = 0.14f), RoundedCornerShape(5.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(text = type.label, color = LaserCyan, fontSize = 9.sp)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(text = plate, color = TextSecondary, fontSize = 9.sp)
                }
                if (session.pitchType == null) {
                    val suggestion = PitchTypeClassifier.suggest(session)
                    if (suggestion != null) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "建議 ${suggestion.type.label}：${suggestion.reason}" +
                                if (!suggestion.confident) "（特徵不明顯，請自行確認）" else "",
                            color = RadarAmber,
                            fontSize = 9.sp
                        )
                    }
                }
                if (session.releaseHeightFt != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "放球 " + String.format("%.1f", session.releaseHeightFt) + " ft" +
                            (session.releaseDistanceFt?.let {
                                " · 距壘 " + String.format("%.1f", it) + " ft"
                            } ?: ""),
                        color = LaserCyan,
                        fontSize = 9.sp
                    )
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


/**
 * 分享匯出包：走 FileProvider（authority 與 Manifest 相同，路徑已在 file_paths.xml 宣告），
 * 用 ACTION_SEND + 單一 zip，避免部分通訊軟體把多檔分享拆散。
 */
private fun shareZip(context: Context, path: String) {
    val file = File(path)
    if (!file.exists()) return
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "PitchLab 投球紀錄匯出")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "分享 PitchLab 匯出包").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
