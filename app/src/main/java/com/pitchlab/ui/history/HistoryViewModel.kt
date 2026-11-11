package com.pitchlab.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pitchlab.domain.model.PitchSession
import com.pitchlab.domain.repository.PitchSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val message: String? = null
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
    private val repository: PitchSessionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeSessions().collect { sessions -> publish(sessions) }
        }
    }

    private fun publish(sessions: List<PitchSession>) {
        val speeds = sessions.mapNotNull { session -> session.fastestMph }
        // observeRecent 已是 started_at DESC，取前 N 後反轉為「舊 -> 新」
        val trend = speeds.take(12).reversed()
        val marks = sessions
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
        _uiState.update {
            it.copy(
                sessions = sessions,
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
}
