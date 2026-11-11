package com.pitchlab.domain.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Environment
import android.util.Log
import androidx.annotation.WorkerThread
import com.pitchlab.domain.model.PitchSession
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 匯出分享包：CSV + 好球帶 PNG + 出手片段（＋沒有片段時的原始影片）→ 一個 zip。
 *
 * 設計取捨：
 * - **媒體有上限**：原始錄影 10 秒可能就 20–60 MB，若一次匯出 50 球會變成 GB 級，
 *   分享意圖（傳給教練）反而失敗。因此複製「片段優先、原始影片其次」，總量上限 [MEDIA_BUDGET_BYTES]；
 *   超出時在 README.txt 明確寫出未複製的球數，而不是默默漏掉。
 * - **PNG 用原生 Canvas 畫**：不引入圖表函式庫，也不依賴 Compose（背景執行緒不能碰 Compose）。
 * - **zip 是唯一分享出去的東西**：多檔分享在部分 App（LINE、Messenger）會被拆散或只傳第一個，
 *   單一 zip 最穩定；解壓後 CSV 仍可直接用 Excel 開。
 */
@Singleton
class SessionExporter @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "SessionExporter"
        /** 媒體總量上限（300 MB），避免分享包大到無法傳送 */
        const val MEDIA_BUDGET_BYTES = 300L * 1024 * 1024
        private const val PNG_WIDTH = 900
        private const val PNG_HEIGHT = 1100
    }

    data class ExportResult(
        val zipFile: File?,
        val message: String,
        val exportedCount: Int = 0,
        val mediaCount: Int = 0,
        val skippedMediaCount: Int = 0,
        val totalBytes: Long = 0L
    )

    @WorkerThread
    fun export(sessions: List<PitchSession>): ExportResult {
        if (sessions.isEmpty()) {
            return ExportResult(null, "沒有可匯出的紀錄")
        }
        return try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val root = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir,
                "PitchLabExports"
            )
            val workDir = File(root, "export_$stamp")
            workDir.mkdirs()
            if (!workDir.exists()) {
                return ExportResult(null, "無法建立匯出目錄")
            }

            // 1) CSV
            File(workDir, "pitchlab_sessions.csv").writeText(
                SessionCsvFormatter.toCsv(sessions),
                Charsets.UTF_8
            )

            // 2) 好球帶 PNG
            writeStrikeZonePng(File(workDir, "strike_zone.png"), sessions)

            // 3) 媒體（片段優先）
            val mediaDir = File(workDir, "media").apply { mkdirs() }
            var budget = MEDIA_BUDGET_BYTES
            var mediaCount = 0
            var skipped = 0
            for (session in sessions) {
                val clip = session.clipPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
                val video = session.videoPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
                val source = clip ?: video
                if (source == null) continue
                if (source.length() > budget) {
                    skipped++
                    continue
                }
                val target = File(mediaDir, "p${session.id}_${source.name}")
                source.copyTo(target, overwrite = true)
                budget -= source.length()
                mediaCount++
            }

            // 4) 說明檔
            val strikeCount = sessions.count { it.isStrike == true }
            val ballCount = sessions.count { it.isStrike == false }
            val speeds = sessions.mapNotNull { it.fastestMph }
            val scores = sessions.mapNotNull { it.poseScore }
            val readme = buildString {
                appendLine("PitchLab 匯出包")
                appendLine("產生時間：$stamp")
                appendLine("球數：${sessions.size}（好球 $strikeCount / 壞球 $ballCount / 未判定 ${sessions.size - strikeCount - ballCount}）")
                if (sessions.isNotEmpty()) {
                    appendLine("球速：最快 ${speeds.maxOrNull()?.let { String.format(Locale.US, "%.1f", it) } ?: "—"} mph、" +
                        "平均 ${if (speeds.isEmpty()) "—" else String.format(Locale.US, "%.1f", speeds.average())} mph")
                    appendLine("姿勢平均分：${if (scores.isEmpty()) "—" else scores.average().toInt()}")
                }
                appendLine(SessionCsvFormatter.unitsNote)
                appendLine()
                appendLine("檔案：")
                appendLine("- pitchlab_sessions.csv：全部紀錄（UTF-8 含 BOM，可直接用 Excel 開）")
                appendLine("- strike_zone.png：好球帶進壘點圖")
                appendLine("- media/：出手片段（前後各 2 秒，優先）或原始影片，共 $mediaCount 個")
                if (skipped > 0) {
                    appendLine("- 注意：有 $skipped 個媒體因超過 300 MB 匯出上限而未複製")
                }
                appendLine()
                appendLine("限制：球速與位移由單一鏡頭估算，需經雷達校準才有絕對精度；")
                appendLine("姿勢分數是 2D 投影角度，只適合在同一機位下比較相對變化。")
            }
            File(workDir, "README.txt").writeText(readme, Charsets.UTF_8)

            // 5) 打包
            val zipFile = File(root, "PitchLab_export_$stamp.zip")
            zipDirectory(workDir, zipFile)

            workDir.deleteRecursively()

            ExportResult(
                zipFile = zipFile,
                message = "已匯出 ${sessions.size} 球（媒體 $mediaCount 個）",
                exportedCount = sessions.size,
                mediaCount = mediaCount,
                skippedMediaCount = skipped,
                totalBytes = zipFile.length()
            )
        } catch (t: Throwable) {
            Log.e(TAG, "匯出失敗", t)
            ExportResult(null, "匯出失敗：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun writeStrikeZonePng(target: File, sessions: List<PitchSession>) {
        val bitmap = Bitmap.createBitmap(PNG_WIDTH, PNG_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#0B0F14"))

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6EDF3")
            textSize = 42f
            isFakeBoldText = true
        }
        val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8B98A5")
            textSize = 26f
        }
        val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3DDC97")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2A3B4A")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        canvas.drawText("PitchLab · 進壘點分佈", 48f, 96f, titlePaint)
        canvas.drawText("最近 ${minOf(sessions.size, 40)} 球（綠=好球 紅=壞球 黃=未判定）", 48f, 142f, hintPaint)

        val plotLeft = 96f
        val plotTop = 220f
        val plotWidth = PNG_WIDTH - 192f
        val plotHeight = 720f

        val xMin = -20.0
        val xMax = 20.0
        val yMin = 8.0
        val yMax = 46.0
        fun px(x: Double) = plotLeft + ((x - xMin) / (xMax - xMin) * plotWidth).toFloat()
        fun py(y: Double) = plotTop + ((yMax - y) / (yMax - yMin) * plotHeight).toFloat()

        // 好球帶 17 x 23 in（19–42 in）
        val left = px(-8.5)
        val right = px(8.5)
        val top = py(42.0)
        val bottom = py(19.0)
        canvas.drawRect(RectF(left, top, right, bottom), zonePaint)
        for (i in 1..2) {
            val gx = left + (right - left) * i / 3f
            canvas.drawLine(gx, top, gx, bottom, gridPaint)
            val gy = top + (bottom - top) * i / 3f
            canvas.drawLine(left, gy, right, gy, gridPaint)
        }
        // 本壘板
        canvas.drawRect(RectF(left, py(8.2), right, py(8.2) + 4f), gridPaint)

        val marks = sessions.asSequence()
            .filter { it.plateXInches != null && it.plateYInches != null }
            .take(40)
            .toList()
        marks.reversed().forEachIndexed { index, session ->
            val alpha = (90 + 165 * ((index + 1).toFloat() / marks.size.coerceAtLeast(1))).toInt()
                .coerceIn(0, 255)
            val color = when (session.isStrike) {
                true -> Color.parseColor("#3DDC97")
                false -> Color.parseColor("#FF5C5C")
                null -> Color.parseColor("#FFB020")
            }
            markPaint.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawCircle(px(session.plateXInches!!), py(session.plateYInches!!), 9f, markPaint)
        }

        bgPaint.color = Color.parseColor("#8B98A5")
        bgPaint.textSize = 24f
        canvas.drawText("好球帶寬 17 in、高 19–42 in；座標範圍 x ±20 in、y 8–46 in", 48f, PNG_HEIGHT - 48f, bgPaint)

        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
    }

    private fun zipDirectory(sourceDir: File, targetZip: File) {
        if (targetZip.exists()) targetZip.delete()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(targetZip))).use { zip ->
            sourceDir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val entryName = file.relativeTo(sourceDir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }
}
