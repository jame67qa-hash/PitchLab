package com.pitchlab.ui.history

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
import androidx.compose.ui.graphics.Color
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
            total = state.sessions.size,
            strikeRate = if (state.strikeCount + state.ballCount > 0) {
                state.strikeCount.toDouble() / (state.strikeCount + state.ballCount)
            } else {
                null
            }
        )

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
private fun SummaryRow(fastestMph: Double?, total: Int, strikeRate: Double?) {
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

@Composable
private fun SessionRow(session: PitchSession, onDelete: () -> Unit) {
    val formatter = remember { SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()) }
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
                if (!session.videoPath.isNullOrEmpty()) {
                    Spacer(Modifier.height(2.dp))
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
