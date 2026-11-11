package com.pitchlab.domain.repository

import com.pitchlab.domain.model.PitchSession
import kotlinx.coroutines.flow.Flow

/** 領域層倉庫接口：UI/ViewModel 只依賴它，不關心 Room */
interface PitchSessionRepository {
    fun observeSessions(): Flow<List<PitchSession>>
    fun observeCount(): Flow<Int>
    suspend fun save(session: PitchSession): Long
    suspend fun delete(id: Long)
    suspend fun clear()
}
