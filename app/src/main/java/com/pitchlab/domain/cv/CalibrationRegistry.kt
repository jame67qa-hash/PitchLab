package com.pitchlab.domain.cv

import com.pitchlab.domain.model.CalibrationProfile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 當前生效標定的共享持有者。
 *
 * 為什麼要一個 mutable holder 而不是讓 calculator 直接依賴倉庫：
 * - 逐幀路徑（60~120fps）不可能每幀去讀數據庫或等 Flow；
 * - 標定是低頻事件（手動觸發），用一個 `@Volatile` 引用做可見性同步就足夠。
 *
 * 寫入方：CameraViewModel（觀察 Room 的 active profile，或剛存檔後立即刷新）。
 * 讀取方：PitchMetricsCalculator（套用速度刻度）。
 */
@Singleton
class CalibrationRegistry @Inject constructor() {

    @Volatile
    var profile: CalibrationProfile = CalibrationProfile.NONE
        private set

    /** 速度刻度；未校準時為 1.0（深度與速度同步縮放） */
    val speedScale: Double
        get() = profile.speedScale.takeIf { it > 0.3 && it < 3.0 } ?: 1.0

    val hasGeometry: Boolean get() = profile.isValid

    fun update(newProfile: CalibrationProfile?) {
        profile = newProfile ?: CalibrationProfile.NONE
    }

    fun clear() {
        profile = CalibrationProfile.NONE
    }
}
