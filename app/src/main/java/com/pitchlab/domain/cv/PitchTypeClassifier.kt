package com.pitchlab.domain.cv

import com.pitchlab.domain.model.PitchSession
import com.pitchlab.domain.model.PitchType
import kotlin.math.abs

/**
 * 規則式球種建議。
 *
 * 這不是「自動辨識」——真正的球種分類要看縫線轉軸、出手角度與旋轉效率，
 * 單目相機拿不到這些資訊。這裡只用三個看得到的特徵（球速、橫移、縱移）
 * 做**經驗規則**，用途是幫使用者少按一次 chip、並且在漏標記時留下線索。
 *
 * 規則刻意保守：不確定就回傳 OTHER 並把 [PitchTypeSuggestion.confident] 設為 false，
 * 寧可顯示「建議：其他」讓人自己選，也不要自信地把速球叫成滑球。
 */
data class PitchTypeSuggestion(
    val type: PitchType,
    val reason: String,
    val confident: Boolean
)

object PitchTypeClassifier {

    /** 低於這個球速（mph）就不會是「硬球種」；同時用來避免未校準的亂值 */
    private const val HARD_THRESHOLD_MPH = 82.0
    private const val SLIDER_MIN_MPH = 78.0
    private const val SLIDER_MAX_MPH = 90.0
    private const val CHANGEUP_MAX_MPH = 78.0
    private const val CURVE_MAX_MPH = 82.0

    /** 橫移超過這個量（英寸）才可能是滑球／橫掃球 */
    private const val SLIDER_BREAK_IN = 9.0

    /** 縱移低於這個量（英寸）才可能是曲球（負值＝下墜） */
    private const val CURVE_DROP_IN = -9.0

    private const val MIN_PLAUSIBLE_MPH = 25.0
    private const val MAX_PLAUSIBLE_MPH = 110.0

    /**
     * @param mph 球速（顯示值，非初速）
     * @param horizontalBreakInches 進壘點相對出手點的橫向位移（正負依機位而定，只看量）
     * @param verticalBreakInches 進壘點相對出手點的縱向位移（正＝往上、負＝下墜）
     */
    fun suggest(
        mph: Double?,
        horizontalBreakInches: Double?,
        verticalBreakInches: Double?
    ): PitchTypeSuggestion? {
        if (mph == null || horizontalBreakInches == null || verticalBreakInches == null) return null
        if (mph !in MIN_PLAUSIBLE_MPH..MAX_PLAUSIBLE_MPH) return null

        val breakAbs = abs(horizontalBreakInches)
        val speed = String.format(java.util.Locale.US, "%.0f mph", mph)
        val breakText = String.format(java.util.Locale.US, "橫移 %.0f in、縱移 %+.0f in", horizontalBreakInches, verticalBreakInches)

        // 1) 大下墜 + 不夠快 -> 曲球（先判，因為曲球常同時有橫移）
        if (verticalBreakInches <= CURVE_DROP_IN && mph < CURVE_MAX_MPH) {
            return PitchTypeSuggestion(
                type = PitchType.CURVEBALL,
                reason = "$speed、$breakText，下墜明顯",
                confident = verticalBreakInches <= -12.0 && mph <= 76.0
            )
        }

        // 2) 夠快且接近直線 -> 速球
        if (mph >= HARD_THRESHOLD_MPH && breakAbs <= SLIDER_BREAK_IN) {
            return PitchTypeSuggestion(
                type = PitchType.FASTBALL,
                reason = "$speed、$breakText，球速快且接近直線",
                confident = mph >= 85.0 && breakAbs <= 6.0
            )
        }

        // 3) 橫向位移大且有一定球速 -> 滑球
        if (breakAbs >= SLIDER_BREAK_IN && mph in SLIDER_MIN_MPH..SLIDER_MAX_MPH) {
            return PitchTypeSuggestion(
                type = PitchType.SLIDER,
                reason = "$speed、$breakText，橫向位移大",
                confident = breakAbs >= 12.0
            )
        }

        // 4) 偏慢、位移不大 -> 變速球
        if (mph <= CHANGEUP_MAX_MPH && breakAbs <= 8.0 && verticalBreakInches >= -8.5) {
            return PitchTypeSuggestion(
                type = PitchType.CHANGEUP,
                reason = "$speed、$breakText，球速偏慢且位移溫和",
                confident = mph <= 73.0 && breakAbs <= 5.0
            )
        }

        return PitchTypeSuggestion(
            type = PitchType.OTHER,
            reason = "$speed、$breakText，特徵不典型",
            confident = false
        )
    }

    /** 直接吃一筆紀錄；缺欄位時回傳 null，不硬猜 */
    fun suggest(session: PitchSession): PitchTypeSuggestion? = suggest(
        mph = session.fastestMph,
        horizontalBreakInches = session.horizontalBreakInches,
        verticalBreakInches = session.verticalBreakInches
    )
}
