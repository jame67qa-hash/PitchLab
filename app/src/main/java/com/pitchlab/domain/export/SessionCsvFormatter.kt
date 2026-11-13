package com.pitchlab.domain.export

import com.pitchlab.domain.model.PitchSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 投球紀錄 → CSV（純 Kotlin，可單元測試）。
 *
 * 兩個容易出錯的細節：
 * 1. **BOM**：Excel（尤其繁中版）打開沒有 BOM 的 UTF-8 CSV 會把中文變亂碼，
 *    因此檔案開頭固定寫 `\uFEFF`；
 * 2. **跳脫**：備註型欄位（檔名）可能含逗號或引號，必須用 RFC 4180 的雙引號包裹 + 引號加倍，
 *    否則整份表格會錯位。
 *
 * 欄位用英文 snake_case 而不是中文標題：這份 CSV 的主要用途是匯入 Excel/試算表或做程式分析，
 * 英文欄名比較不會因為編碼或全形字元在不同工具間出問題；說明留在 README.txt。
 */
object SessionCsvFormatter {

    const val BOM = "\uFEFF"
    private const val LINE_END = "\r\n"
    private const val UNITS_NOTE = "速度單位 mph、位移與進壘點單位 inch、放球高度與距離單位 ft、時間為裝置本地時間"

    val HEADER: List<String> = listOf(
        "id",
        "started_at",
        "duration_s",
        "fastest_mph",
        "average_mph",
        "h_break_in",
        "v_break_in",
        "plate_x_in",
        "plate_y_in",
        "strike",
        "calibrated",
        "pose_score",
        "separation_deg",
        "elbow_deg",
        "trunk_tilt_deg",
        "release_height_ratio",
        "release_height_ft",
        "release_distance_ft",
        "release_offset_ms",
        "tracked_frames",
        "detected_fps",
        "video_file",
        "clip_file",
        "camera_height_in",
        "camera_pitch_deg",
        "speed_scale",
        "pitch_type",
        "pitcher_name"
    )

    /**
     * @param pitcherNames 投手 id -> 名稱；未指定投手的球會留空。
     *        名稱放在 CSV 而不是只給 id，是為了讓教練直接用 Excel 樞紐分析。
     */
    fun toCsv(
        sessions: List<PitchSession>,
        pitcherNames: Map<Long, String> = emptyMap()
    ): String {
        val builder = StringBuilder(BOM)
        builder.append(HEADER.joinToString(",")).append(LINE_END)
        for (session in sessions) {
            val name = session.pitcherId?.let { pitcherNames[it] } ?: ""
            builder.append(rowOf(session, name).joinToString(",") { escape(it) }).append(LINE_END)
        }
        return builder.toString()
    }

    fun rowOf(session: PitchSession, pitcherName: String = ""): List<String> {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return listOf(
            session.id.toString(),
            formatter.format(Date(session.startedAtMillis)),
            seconds(session.durationMillis),
            number(session.fastestMph, 2),
            number(session.averageMph, 2),
            number(session.horizontalBreakInches, 2),
            number(session.verticalBreakInches, 2),
            number(session.plateXInches, 2),
            number(session.plateYInches, 2),
            when (session.isStrike) {
                true -> "YES"
                false -> "NO"
                null -> ""
            },
            if (session.calibrated) "YES" else "NO",
            session.poseScore?.toString() ?: "",
            number(session.separationDeg, 1),
            number(session.elbowDeg, 1),
            number(session.trunkTiltDeg, 1),
            number(session.releaseHeightRatio, 3),
            number(session.releaseHeightFt, 2),
            number(session.releaseDistanceFt, 2),
            session.releaseOffsetMs?.toString() ?: "",
            session.trackedFrames.toString(),
            session.detectedFps.toString(),
            session.videoPath?.substringAfterLast('/') ?: "",
            session.clipPath?.substringAfterLast('/') ?: "",
            number(session.cameraHeightIn, 1),
            number(session.cameraPitchDeg, 1),
            number(session.speedScale, 2),
            session.pitchType?.code ?: "",
            pitcherName
        )
    }

    /** RFC 4180：含逗號、引號、換行時用雙引號包起來，內部引號加倍 */
    fun escape(value: String): String {
        val needsQuote = value.contains(',') || value.contains('"') ||
            value.contains('\n') || value.contains('\r')
        if (!needsQuote) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    val unitsNote: String get() = UNITS_NOTE

    private fun number(value: Double?, decimals: Int): String =
        if (value == null) "" else String.format(Locale.US, "%.${decimals}f", value)

    private fun seconds(durationMillis: Long): String =
        String.format(Locale.US, "%.1f", durationMillis / 1000.0)
}
