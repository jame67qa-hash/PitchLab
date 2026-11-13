package com.pitchlab.domain.repository

import com.pitchlab.domain.model.Pitcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 投手檔案的倉庫。目前選取的投手要記在裝置上，
 * 重開 App 後仍知道「現在在測誰」，否則新球會落到未指定。
 */
interface PitcherRepository {
    fun observePitchers(): Flow<List<Pitcher>>

    /** null = 未指定（所有投手的球一起看，或裝置只有一位投手） */
    val activePitcherId: StateFlow<Long?>

    suspend fun add(name: String): Long
    fun setActive(id: Long?)
    suspend fun rename(id: Long, name: String)
    suspend fun delete(id: Long)
}
