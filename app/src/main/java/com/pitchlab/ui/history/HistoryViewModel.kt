package com.pitchlab.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitchlab.domain.model.PitchSession
import com.pitchlab.domain.model.PitchType
import com.pitchlab.domain.model.CalibrationProfile
import com.pitchlab.domain.repository.CalibrationRepository
import com.pitchlab.domain.repository.PitchSessionRepository
import com.pitchlab.domain.cv.BaselineDelta
import com.pitchlab.domain.cv.CameraFingerprint
import com.pitchlab.domain.cv.ControlAnalyzer
import com.pitchlab.domain.cv.ControlReport
import com.pitchlab.domain.cv.PlateSample
import com.pitchlab.domain.cv.MechanicsBaseline
import com.pitchlab.domain.cv.MechanicsBaselineCalculator
import com.pitchlab.domain.export.ExportOptions
import com.pitchlab.domain.export.SessionExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class HistoryUiState(
    val sessions: List<PitchSession> = emptyList(),
    val isLoading: Boolean = true,
    val strikeCount: Int = 0,
    val ballCount: Int = 0,
    val unknownCount: Int = 0,
    val fastestMph: Double? = null,
    val averageMph: Double? = null,
    /** 最近 12 球的速度趨勢（舊 -> 新），給歷史頁折線圖用 */
    val speedTrend: List<Double> = emptyList(),
    /** 最近 20 球的進壘點（給好球帶散點圖用） */
    val plateMarks: List<PlateMark> = emptyList(),
    val message: String? = null,
    /** 匯出中（按鈕顯示進度、避免重複觸發） */
    val exporting: Boolean = false,
    /** 匯出完成、等待 UI 交給系統分享的 zip 路徑；分享後清空 */
    val shareZipPath: String? = null,
    /** 個人化機制基準（歷史中位數）；null = 完全沒有姿勢資料 */
    val baseline: MechanicsBaseline? = null,
    /** 最近一球與個人基準的差距（只含有值的指標） */
    val baselineDeltas: List<BaselineDelta> = emptyList(),
    /** 控球熱區與穩定性（最近 40 球有落點的樣本） */
    val controlReport: ControlReport = ControlReport.EMPTY,
    /** Phase 12：目前篩選的球種；null = 全部 */
    val typeFilter: PitchType? = null,
    /** 歷史裡實際出現過的球種（決定顯示哪些篩選 chip） */
    val availableTypes: List<PitchType> = emptyList(),
    /** 篩選後要列出的球（列表用；匯出仍用 sessions 全部） */
    val filteredSessions: List<PitchSession> = emptyList()
)

/** 一顆球的進壘點 + 判定 + 球速，供歷史頁散點圖繪製 */
data class PlateMark(
    val xInches: Double,
    val yInches: Double,
    val isStrike: Boolean?,
    val mph: Double?
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: PitchSessionRepository,
    private val calibrationRepository: CalibrationRepository,
    private val exporter: SessionExporter
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())

    /** 最近一次收到的資料與標定，切換球種篩選時直接重算，不必重讀資料庫 */
    private var lastSessions: List<PitchSession> = emptyList()
    private var lastProfile: CalibrationProfile? = null
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // 基準要綁定「目前生效的機位」，所以同時觀察標定與紀錄；
            // 重新標定後，不同機位的舊球會自動被排除，不必手動清資料。
            combine(
                repository.observeSessions(),
                calibrationRepository.observeActive()
            ) { sessions, profile -> sessions to profile }
                .collect { (sessions, profile) -> publish(sessions, profile) }
        }
    }

    private fun publish(sessions: List<PitchSession>, profile: CalibrationProfile?) {
        lastSessions = sessions
        lastProfile = profile
        // 球種篩選只影響「看得到的統計」：熱區、趨勢、列表；
        // 基準仍用全部同機位的球（球種不該改變你的動作基準）。
        val filter = _uiState.value.typeFilter
        val visible = if (filter == null) sessions else sessions.filter { it.pitchType == filter }
        val speeds = visible.mapNotNull { session -> session.fastestMph }
        // observeRecent 已是 started_at DESC，取前 N 後反轉為「舊 -> 新」
        val trend = speeds.take(12).reversed()
        val marks = visible
            .asSequence()
            .filter { it.plateXInches != null && it.plateYInches != null }
            .take(20)
            .map {
                PlateMark(
                    xInches = it.plateXInches!!,
                    yInches = it.plateYInches!!,
                    isStrike = it.isStrike,
                    mph = it.fastestMph
                )
            }
            .toList()
        val controlSamples = visible
            .asSequence()
            .filter { it.plateXInches != null && it.plateYInches != null }
            .take(40)
            .map { PlateSample(it.plateXInches!!, it.plateYInches!!, it.isStrike) }
            .toList()
        val controlReport = ControlAnalyzer.analyze(controlSamples)

        val fingerprint = profile
            ?.takeIf { it.isValid }
            ?.let { CameraFingerprint(it.cameraHeightIn, it.cameraPitchDeg, it.speedScale) }
        val baseline = MechanicsBaselineCalculator.from(sessions, fingerprint)
        val latestScored = sessions.firstOrNull { it.poseScore != null }
        val deltas = if (baseline.isUsable && latestScored != null) {
            MechanicsBaselineCalculator.deviations(
                MechanicsBaselineCalculator.snapshotOf(latestScored),
                baseline
            )
        } else {
            emptyList()
        }
        _uiState.update {
            it.copy(
                sessions = sessions,
                filteredSessions = visible,
                availableTypes = sessions.mapNotNull { session -> session.pitchType }.distinct(),
                typeFilter = filter,
                baseline = baseline.takeIf { baseline.sampleCount > 0 },
                baselineDeltas = deltas,
                controlReport = controlReport,
                isLoading = false,
                strikeCount = sessions.count { session -> session.isStrike == true },
                ballCount = sessions.count { session -> session.isStrike == false },
                unknownCount = sessions.count { session -> session.isStrike == null },
                fastestMph = speeds.maxOrNull(),
                averageMph = if (speeds.isEmpty()) null else speeds.average(),
                speedTrend = trend,
                plateMarks = marks
            )
        }
    }

    /** Phase 12：切換球種篩選；再點同一個球種＝回到全部 */
    fun setTypeFilter(type: PitchType?) {
        _uiState.update { it.copy(typeFilter = if (it.typeFilter == type) null else type) }
        publish(lastSessions, lastProfile)
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            runCatching { repository.delete(id) }
                .onFailure { error ->
                    _uiState.update { it.copy(message = "刪除失敗：${error.message}") }
                }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            runCatching { repository.clear() }
            _uiState.update { it.copy(message = "歷史記錄已清空") }
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    /**
     * 匯出分享包（CSV + 好球帶 PNG + 片段）。壓縮與複製都在 IO 執行緒，
     * 完成後只把路徑放進 state，由 UI 負責發 share intent——ViewModel 不碰 Context/Intent。
     */
    fun exportAll(options: ExportOptions = ExportOptions()) {
        val sessions = _uiState.value.sessions
        if (sessions.isEmpty()) {
            _uiState.update { it.copy(message = "沒有可匯出的紀錄") }
            return
        }
        if (_uiState.value.exporting) return
        _uiState.update { it.copy(exporting = true, message = "正在打包…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { exporter.export(sessions, options) }
            _uiState.update {
                it.copy(
                    exporting = false,
                    shareZipPath = result.zipFile?.absolutePath,
                    message = result.message
                )
            }
        }
    }

    fun clearShareRequest() {
        _uiState.update { it.copy(shareZipPath = null) }
    }
}
