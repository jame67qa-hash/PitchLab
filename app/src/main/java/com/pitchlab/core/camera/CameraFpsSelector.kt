package com.pitchlab.core.camera

import android.util.Range
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次幀率選擇結果。
 *
 * @param range     將寫入 CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE 的區間
 * @param targetFps 期望的鎖定幀率（區間上界）
 * @param isFixed   true 表示區間爲 [N,N]，AE 只能在該幀率工作（可能偏暗）
 * @param note      給 UI 的降級說明
 */
data class FpsChoice(
    val range: Range<Int>,
    val targetFps: Int,
    val isFixed: Boolean,
    val note: String
)

/**
 * 高幀率選擇器。
 *
 * 關鍵點：CameraX 的 ImageAnalysis 預設不會主動要求高幀率，
 * 必須通過 Camera2Interop 寫入 AE 目標幀率區間，否則很多機型只有 30fps。
 */
@Singleton
class CameraFpsSelector @Inject constructor() {

    /**
     * @param available  CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
     * @param preferences 期望優先級，預設 120 -> 60 -> 30
     */
    fun select(
        available: Array<Range<Int>>?,
        preferences: List<Int> = listOf(120, 60, 30)
    ): FpsChoice {
        if (available.isNullOrEmpty()) {
            return FpsChoice(
                range = Range(30, 30),
                targetFps = 30,
                isFixed = true,
                note = "相機未上報幀率區間，回退 30fps"
            )
        }

        // 1) 優先精確鎖幀：[N,N] 區間最能保證分析節奏穩定
        for (preferred in preferences) {
            val exact = available.firstOrNull { it.lower == preferred && it.upper == preferred }
            if (exact != null) {
                return FpsChoice(
                    range = exact,
                    targetFps = preferred,
                    isFixed = true,
                    note = "已鎖定 ${preferred}fps（[${exact.lower}, ${exact.upper}]）"
                )
            }
        }

        // 2) 沒有精確區間時，退化爲「上界最高、下界儘量高」的區間
        val sorted = available.sortedWith(
            compareByDescending<Range<Int>> { it.upper }.thenByDescending { it.lower }
        )
        val best = sorted.first()

        // 3) 若上界低於 60，明確告知使用者裝置不支援高幀率
        val target = best.upper
        val isHighSpeed = target >= 60
        val note = if (isHighSpeed) {
            "裝置僅支援區間 [${best.lower}, ${best.upper}]，AE 會在區間內浮動"
        } else {
            "裝置最高僅 ${best.upper}fps，高速分析會降級（建議 60fps 以上裝置）"
        }
        return FpsChoice(best, target, false, note)
    }

    /** 可選幀率列表，用於 UI 展示機型能力 */
    fun describe(available: Array<Range<Int>>?): String {
        if (available.isNullOrEmpty()) return "未知"
        return available.joinToString("  ") { "[${it.lower}-${it.upper}]" }
    }
}
