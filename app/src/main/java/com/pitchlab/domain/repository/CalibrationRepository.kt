package com.pitchlab.domain.repository

import com.pitchlab.domain.model.CalibrationProfile
import kotlinx.coroutines.flow.Flow

/** 標定資料的倉庫接口（domain 層不認識 Room） */
interface CalibrationRepository {
    /** 最新的標定（沒有則 emit null） */
    fun observeActive(): Flow<CalibrationProfile?>

    suspend fun loadActive(): CalibrationProfile?

    suspend fun save(profile: CalibrationProfile): Long

    suspend fun clearAll()
}
