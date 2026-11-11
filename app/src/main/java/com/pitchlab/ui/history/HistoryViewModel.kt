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
    val message: String? = null
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
        _uiState.update {
            it.copy(
                sessions = sessions,
                isLoading = false,
                strikeCount = sessions.count { session -> session.isStrike == true },
                ballCount = sessions.count { session -> session.isStrike == false },
                unknownCount = sessions.count { session -> session.isStrike == null },
                fastestMph = sessions.mapNotNull { session -> session.fastestMph }.maxOrNull()
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
