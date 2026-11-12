package com.pitchlab.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitchlab.domain.model.CameraUiState
import com.pitchlab.domain.model.PitchType
import com.pitchlab.domain.model.DetectionSource
import com.pitchlab.domain.model.LivePitchMetrics
import com.pitchlab.ui.calibration.CalibrationOverlay
import com.pitchlab.ui.camera.components.RadarSpeedometer
import com.pitchlab.ui.camera.components.StrikeZoneOverlay
import com.pitchlab.ui.theme.AlarmRed
import com.pitchlab.ui.theme.LaserCyan
import com.pitchlab.ui.theme.PitchSurface
import com.pitchlab.ui.theme.RadarAmber
import com.pitchlab.ui.theme.StrikeZoneGreen
import com.pitchlab.ui.theme.TextPrimary
import com.pitchlab.ui.theme.TextSecondary

/**
 * 全屏相機頁面：
 *  - CameraX PreviewView 鋪滿
 *  - Canvas HUD（好球帶 / 軌跡 / 雷達測速）
 *  - 權限請求（ActivityResultContracts，不引入 Accompanist）
 */
@Composable
fun CameraScreen(
    onOpenHistory: () -> Unit = {},
    viewModel: CameraViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val calibrationFrame by viewModel.calibrationFrame.collectAsStateWithLifecycle()

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        cameraGranted = result[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        audioGranted = result[Manifest.permission.RECORD_AUDIO] == true
    }

    LaunchedEffect(Unit) {
        if (!cameraGranted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                )
            )
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }

    LaunchedEffect(cameraGranted, previewView) {
        if (cameraGranted) {
            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
            viewModel.bindCamera(
                lifecycleOwner = lifecycleOwner,
                surfaceProvider = previewView.surfaceProvider,
                targetRotation = rotation
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (cameraGranted) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            StrikeZoneOverlay(
                state = state,
                modifier = Modifier.fillMaxSize(),
                onStrikeZoneChange = viewModel::updateStrikeZone
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .systemBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                StatusStrip(state = state)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = viewModel::enterCalibrationMode,
                        label = { Text("標定", fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Straighten,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = if (state.geometryCalibrated) StrikeZoneGreen else TextPrimary,
                            leadingIconContentColor = if (state.geometryCalibrated) StrikeZoneGreen else TextPrimary
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = onOpenHistory,
                        label = { Text("紀錄 ${state.storedSessionCount}", fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = TextPrimary,
                            leadingIconContentColor = RadarAmber
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = viewModel::toggleFeedback,
                        label = { Text(if (state.feedbackEnabled) "回饋 ON" else "回饋 OFF", fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (state.feedbackEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                                contentDescription = if (state.feedbackEnabled) "關閉出手回饋" else "開啟出手回饋",
                                modifier = Modifier.size(15.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = if (state.feedbackEnabled) LaserCyan else TextSecondary,
                            leadingIconContentColor = if (state.feedbackEnabled) LaserCyan else TextSecondary
                        )
                    )
                }
                if (state.analysisWidth > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "拖動框線可平移好球帶 · 分析 ${state.analysisWidth}×${state.analysisHeight}",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            RadarSpeedometer(
                speedMph = state.liveMetrics.instantaneousMph,
                measuredFps = state.measuredFps,
                requestedFps = state.requestedFps,
                processingMs = state.lastProcessingMs,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .size(168.dp)
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                PitchMetricsRow(state.liveMetrics, state.speedCalibrated)
                state.liveMetrics.releaseHeightFeet?.let { height ->
                    Text(
                        text = "3D 放球 " + String.format("%.1f", height) + " ft" +
                            (state.liveMetrics.releaseDistanceFeet?.let {
                                " · 距壘 " + String.format("%.1f", it) + " ft"
                            } ?: ""),
                        color = LaserCyan,
                        fontSize = 9.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 3.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                Spacer(Modifier.height(8.dp))
                PitchTypeRow(
                    selected = state.selectedPitchType,
                    onSelect = viewModel::selectPitchType
                )
                Spacer(Modifier.height(6.dp))
                ControlBar(
                    state = state,
                    onRecordClick = viewModel::toggleRecording,
                    onToggleColorMode = viewModel::toggleColorMode,
                    onClearHistory = viewModel::clearSessions
                )
            }

            state.errorMessage?.let { message ->
                ErrorBanner(
                    message = message,
                    onDismiss = viewModel::clearError,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
            }

            if (state.calibration.visible) {
                CalibrationOverlay(
                    state = state.calibration,
                    frame = calibrationFrame,
                    onTapCorner = viewModel::addCalibrationCorner,
                    onCameraHeightChange = viewModel::updateCameraHeight,
                    onCameraPitchChange = viewModel::updateCameraPitch,
                    onRadarChange = viewModel::updateRadarInput,
                    onEstimatedChange = viewModel::updateEstimatedInput,
                    onCompute = viewModel::computeCalibration,
                    onUndo = viewModel::undoCalibrationCorner,
                    onReset = viewModel::resetCalibrationCorners,
                    onSave = viewModel::saveCalibration,
                    onClearCalibration = viewModel::clearCalibration,
                    onCancel = viewModel::exitCalibrationMode
                )
            }
        } else {
            PermissionGate(
                audioGranted = audioGranted,
                onRequest = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun StatusStrip(state: CameraUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PitchSurface.copy(alpha = 0.82f)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusDot(active = state.cameraReady, isRecording = state.isRecording)
                Text(
                    text = if (state.isRecording) "REC" else "LIVE",
                    color = if (state.isRecording) AlarmRed else LaserCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${state.measuredFps.toInt()} fps",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = state.requestRangeLabel.ifEmpty { "…" },
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${String.format("%.1f", state.lastProcessingMs)} ms/幀",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Tag(
                    text = when (state.detectionSource) {
                        DetectionSource.MOG2_MOTION -> "MOG2 追蹤中"
                        DetectionSource.HSV_WHITE -> "白色過濾"
                        DetectionSource.NONE -> "無球"
                    },
                    color = when (state.detectionSource) {
                        DetectionSource.NONE -> TextSecondary
                        else -> StrikeZoneGreen
                    }
                )
                Tag(text = "投球 ${state.pitchCount}", color = RadarAmber)
                Tag(text = "好 ${state.strikeCount}", color = StrikeZoneGreen)
                Tag(text = "壞 ${state.ballCount}", color = AlarmRed)
                Tag(
                    text = if (state.poseAvailable) "出手 AUTO" else "出手 OFF",
                    color = if (state.poseAvailable) StrikeZoneGreen else TextSecondary
                )
                state.mechanicsScore?.let { score ->
                    Tag(
                        text = "姿勢 $score ${state.mechanicsGrade ?: ""}",
                        color = if (score >= 70) StrikeZoneGreen else RadarAmber
                    )
                }
                if (!state.videoSupported) {
                    Tag(text = "錄影不可用", color = AlarmRed)
                }
            }
            if (state.statusMessage.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.statusMessage,
                    color = TextSecondary.copy(alpha = 0.9f),
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun StatusDot(active: Boolean, isRecording: Boolean) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(
                when {
                    isRecording -> AlarmRed
                    active -> LaserCyan
                    else -> TextSecondary
                }
            )
    )
}

@Composable
private fun Tag(text: String, color: Color) {
    Box(
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = text, color = color, fontSize = 9.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PitchMetricsRow(metrics: LivePitchMetrics, speedCalibrated: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        MetricCell(
            label = "球速",
            value = metrics.instantaneousMph?.let { String.format("%.1f", it) } ?: "—",
            unit = if (speedCalibrated) "MPH · CAL" else "MPH · EST"
        )
        MetricCell(
            label = "水平位移",
            value = metrics.horizontalBreakInches?.let { String.format("%+.1f", it) } ?: "—",
            unit = "IN"
        )
        MetricCell(
            label = "垂直位移",
            value = metrics.verticalBreakInches?.let { String.format("%+.1f", it) } ?: "—",
            unit = "IN"
        )
        MetricCell(
            label = "追蹤",
            value = metrics.trackedFrames.toString(),
            unit = "FRAMES"
        )
    }
}

@Composable
private fun MetricCell(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = TextSecondary, fontSize = 10.sp)
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text(text = unit, color = TextSecondary.copy(alpha = 0.7f), fontSize = 9.sp)
    }
}

/**
 * 球種選擇列：再點一次同一個球種就取消標記。
 * 放橫向捲動是因為五個球種＋「未標記」在窄螢幕上一定會超出寬度，
 * 硬塞成兩排會把相機畫面壓縮掉太多。
 */
@Composable
private fun PitchTypeRow(selected: PitchType?, onSelect: (PitchType?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        PitchTypeChip(label = "未標記", active = selected == null, onClick = { onSelect(null) })
        PitchType.entries.forEach { type ->
            PitchTypeChip(
                label = type.label,
                active = selected == type,
                onClick = { onSelect(type) }
            )
        }
    }
}

@Composable
private fun PitchTypeChip(label: String, active: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(text = label, fontSize = 9.sp) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (active) LaserCyan.copy(alpha = 0.20f) else PitchSurface.copy(alpha = 0.72f),
            labelColor = if (active) LaserCyan else TextSecondary
        )
    )
}

@Composable
private fun ControlBar(
    state: CameraUiState,
    onRecordClick: () -> Unit,
    onToggleColorMode: () -> Unit,
    onClearHistory: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        AssistChip(
            onClick = onToggleColorMode,
            label = {
                Text(
                    text = if (state.colorModeEnabled) "白色過濾" else "運動偵測",
                    fontSize = 11.sp
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                labelColor = if (state.colorModeEnabled) StrikeZoneGreen else TextPrimary,
                leadingIconContentColor = if (state.colorModeEnabled) StrikeZoneGreen else TextPrimary
            )
        )

        RecordButton(
            isRecording = state.isRecording,
            enabled = state.videoSupported && state.cameraReady,
            onClick = onRecordClick
        )

        IconButton(onClick = onClearHistory) {
            Icon(
                imageVector = Icons.Filled.DeleteSweep,
                contentDescription = "清空歷史",
                tint = TextSecondary
            )
        }
    }
}

@Composable
private fun RecordButton(isRecording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (isRecording) AlarmRed else Color.White.copy(alpha = 0.92f),
        modifier = Modifier
            .size(72.dp)
            .padding(0.dp)
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                contentDescription = if (isRecording) "停止錄影" else "開始錄影",
                tint = if (isRecording) Color.White else AlarmRed,
                modifier = Modifier.size(34.dp)
            )
        }
    }
}

@Composable
private fun PermissionGate(audioGranted: Boolean, onRequest: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = PitchSurface),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "PitchLab 需要相機權限",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "相機用於 60/120fps 高速擷取與逐幀棒球追蹤；" +
                        "麥克風僅在錄影時收音（可不授權）。",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (audioGranted) "麥克風：已授權" else "麥克風：未授權（錄影將靜音）",
                    color = TextSecondary.copy(alpha = 0.8f),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = onRequest,
                    colors = ButtonDefaults.buttonColors(containerColor = LaserCyan),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(text = "授予權限", color = Color.Black, fontWeight = FontWeight.Bold)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "若已拒絕兩次，請到系統設定 → 應用程式 → PitchLab 手動開啟",
                        color = TextSecondary.copy(alpha = 0.6f),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PitchSurface),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = message,
                color = TextPrimary,
                fontSize = 13.sp
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) {
                    Text(text = "知道了", color = LaserCyan)
                }
            }
        }
    }
}
