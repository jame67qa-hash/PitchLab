package com.pitchlab.domain.model

/**
 * 投手檔案（Phase 16）。同一個 App 可能讓教練在同一台裝置上輪流測不同投手，
 * 歷史、基準與熱區必須分開累積，否則兩個人的中位數會混在一起。
 */
data class Pitcher(
    val id: Long,
    val name: String,
    val createdAtMillis: Long
) {
    companion object {
        /** 名稱上限：太長會把 chip 撐爆，也讓 CSV 難讀 */
        const val MAX_NAME_LENGTH = 16

        fun validate(name: String): String? = when {
            name.isBlank() -> "請輸入投手名稱"
            name.trim().length > MAX_NAME_LENGTH -> "名稱最多 $MAX_NAME_LENGTH 字"
            else -> null
        }
    }
}
