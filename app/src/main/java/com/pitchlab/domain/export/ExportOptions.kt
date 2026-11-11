package com.pitchlab.domain.export

import com.pitchlab.domain.model.PitchSession

/**
 * 匯出條件。放在 domain 純 Kotlin，讓「要匯出哪些球」這件事可以單元測試，
 * 不必把判斷散在 ViewModel 與 SessionExporter 兩處（兩處各寫一份遲早不一致）。
 *
 * @param strikesOnly 只匯出好球（給教練看「好球長什麼樣」時最有用）
 * @param daysBack 只匯出最近 N 天；null = 全部
 * @param maxMediaBytes 媒體總量上限；[NO_MEDIA] 表示只匯出 CSV + PNG
 */
data class ExportOptions(
    val strikesOnly: Boolean = false,
    val daysBack: Int? = null,
    val maxMediaBytes: Long = SessionExporter.MEDIA_BUDGET_BYTES
) {
    companion object {
        /** 不帶任何影片，匯出包只有 CSV 與 PNG（通常 < 1 MB，任何 App 都傳得出去） */
        const val NO_MEDIA = 0L

        val DEFAULT = ExportOptions()

        val STRIKES_ONLY_MONTH = ExportOptions(strikesOnly = true, daysBack = 30)

        val DOCS_ONLY = ExportOptions(maxMediaBytes = NO_MEDIA)
    }
}

object ExportSelector {

    private const val MILLIS_PER_DAY = 86_400_000L

    /**
     * 依條件篩選。`ultraStrict` 的取捨：`daysBack` 以「現在」為基準往前推，
     * 而不是以最後一筆紀錄為基準——使用者要的是「最近 7 天我投了什麼」，
     * 不是「最後一次練習前 7 天」。
     */
    fun select(
        sessions: List<PitchSession>,
        options: ExportOptions,
        nowMillis: Long = System.currentTimeMillis()
    ): List<PitchSession> {
        var filtered = sessions
        if (options.strikesOnly) {
            filtered = filtered.filter { it.isStrike == true }
        }
        val days = options.daysBack
        if (days != null && days > 0) {
            val earliest = nowMillis - days * MILLIS_PER_DAY
            filtered = filtered.filter { it.startedAtMillis >= earliest }
        }
        return filtered
    }

    /** 給 UI 顯示「這次會匯出幾球」用 */
    fun describe(options: ExportOptions): String {
        val scope = when {
            options.daysBack == 7 -> "最近 7 天"
            options.daysBack == 30 -> "最近 30 天"
            options.daysBack != null && options.daysBack > 0 -> "最近 ${options.daysBack} 天"
            else -> "全部紀錄"
        }
        val strike = if (options.strikesOnly) "、只含好球" else ""
        val media = if (options.maxMediaBytes == ExportOptions.NO_MEDIA) "、不含影片" else "、含影片片段"
        return "$scope$strike$media"
    }
}
