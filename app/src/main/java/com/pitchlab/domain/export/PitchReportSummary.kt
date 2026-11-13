package com.pitchlab.domain.export

import com.pitchlab.domain.model.PitchSession
import java.util.Locale

/**
 * PDF 報告的文字內容（純 Kotlin，可單元測試）。
 *
 * 用途：教練或家長拿到的不該只有一張 CSV。這裡把「這次匯出到底包含什麼、
 * 數字是多少、怎麼解讀」寫成一頁，和兩張圖一起放進 PDF，
 * 避免收到檔案的人自己猜。
 */
object PitchReportSummary {

    fun headline(stamp: String): String = "PitchLab 投球報告"

    fun subtitle(stamp: String): String = "產生時間：$stamp"

    fun build(sessions: List<PitchSession>, options: ExportOptions): List<String> {
        val strikes = sessions.count { it.isStrike == true }
        val balls = sessions.count { it.isStrike == false }
        val unknown = sessions.size - strikes - balls
        val speeds = sessions.mapNotNull { it.fastestMph }
        val scores = sessions.mapNotNull { it.poseScore }
        val lines = mutableListOf<String>()

        lines += "匯出條件：${ExportSelector.describe(options)}"
        lines += "球數：${sessions.size}（好球 $strikes / 壞球 $balls / 未判定 $unknown）"

        if (speeds.isNotEmpty()) {
            lines += String.format(
                Locale.US,
                "球速：最快 %.1f mph、平均 %.1f mph",
                speeds.max(),
                speeds.average()
            )
        } else {
            lines += "球速：無有效資料"
        }

        if (scores.isNotEmpty()) {
            lines += String.format(Locale.US, "姿勢平均分：%.0f（A≥85、B≥70、C≥55）", scores.average())
        } else {
            lines += "姿勢分數：此批沒有偵測到投球動作"
        }

        val types = sessions.mapNotNull { it.pitchType }.distinct()
        lines += if (types.isEmpty()) {
            "球種：全部未標記"
        } else {
            "球種：" + types.joinToString("、") { type ->
                "${type.label} ${sessions.count { it.pitchType == type }} 球"
            }
        }

        val releases = sessions.filter { it.releaseHeightFt != null && it.releaseDistanceFt != null }
        lines += if (releases.isEmpty()) {
            "3D 放球：無資料（Phase 10 以前的紀錄沒有這兩個欄位）"
        } else {
            String.format(
                Locale.US,
                "3D 放球：平均高度 %.1f ft、平均距壘 %.1f ft（%d 球）",
                releases.mapNotNull { it.releaseHeightFt }.average(),
                releases.mapNotNull { it.releaseDistanceFt }.average(),
                releases.size
            )
        }

        lines += ""
        lines += "解讀提醒："
        lines += "· 球速與位移由單一鏡頭估算，需經雷達校準才有絕對精度。"
        lines += "· 姿勢分數是 2D 投影角度，只適合在同一機位下比較相對變化。"
        lines += "· 球種欄位是人工標記；未標記者不列入任何球種統計。"
        return lines
    }
}
