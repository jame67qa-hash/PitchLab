package com.pitchlab.domain.feedback

import kotlin.math.roundToInt

/**
 * 出手後的語音播報文字（純 Kotlin，可單元測試）。
 *
 * 刻意做得短：使用者站在投手丘上、眼睛看著好球帶，一句「好球，72 英里」
 * 比「本次投球判定為好球，球速為每小時七十二英里」實用得多，也比較不會蓋掉現場聲音。
 * 未經雷達校準時加上「預估」，避免把單目估算的數字當成真實球速。
 */
object PitchAnnouncement {

    fun text(isStrike: Boolean?, mph: Double?, calibrated: Boolean = false): String {
        val verdict = when (isStrike) {
            true -> "好球"
            false -> "壞球"
            null -> "未判定"
        }
        val speed = mph?.takeIf { it > 0 }?.roundToInt()
        return when {
            speed == null -> verdict
            calibrated -> "$verdict，$speed 英里"
            else -> "$verdict，預估 $speed 英里"
        }
    }
}
