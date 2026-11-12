package com.pitchlab.domain.model

/**
 * 球種。刻意只留五種：多數業餘投手實際只投 2–3 種，
 * 分類太細（伸卡、卡特、指叉、曲球、滑球…）反而讓使用者每次都要猶豫，
 * 資料也會因為標記不一致而失去比較價值。
 *
 * [code] 是寫進資料庫與 CSV 的穩定識別碼；[label] 是繁中顯示名稱。
 */
enum class PitchType(val code: String, val label: String) {
    FASTBALL("fastball", "速球"),
    CURVEBALL("curveball", "曲球"),
    SLIDER("slider", "滑球"),
    CHANGEUP("changeup", "變速球"),
    OTHER("other", "其他");

    companion object {
        /** 從資料庫／CSV 的 code 還原；null、空字串或未知值都回傳 null（不猜球種） */
        fun fromCode(code: String?): PitchType? {
            if (code.isNullOrBlank()) return null
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
        }
    }
}
