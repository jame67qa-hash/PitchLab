package com.pitchlab.domain.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import com.pitchlab.domain.model.PitchSession
import java.io.File
import java.io.FileOutputStream

/**
 * 兩頁 A4 的 PDF 報告：文字摘要 + 好球帶熱區圖 + 3D 放球分佈圖。
 *
 * 為什麼不用第三方 PDF 函式庫：Android 內建的 [PdfDocument] 就夠畫文字與點陣圖，
 * 少一個依賴、也少一次 APK 膨脹；這份報告不需要向量表格或可選文字。
 * 兩張圖各自一頁，避免縮得太小看不清楚。
 */
object PitchReportPdf {

    /** A4 在 72 dpi 的點數：595 x 842 */
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f

    private const val COLOR_BG = 0xFF0B0F14.toInt()
    private const val COLOR_TEXT = 0xFFE6EDF3.toInt()
    private const val COLOR_HINT = 0xFF8B98A5.toInt()
    private const val COLOR_ACCENT = 0xFF3DDC97.toInt()

    /**
     * @return true = 已產出 report.pdf；false = 過程中失敗（呼叫端只會少一個檔案，不影響 CSV/PNG）
     */
    fun write(
        target: File,
        sessions: List<PitchSession>,
        options: ExportOptions,
        stamp: String,
        strikeZonePng: File,
        releaseMapPng: File
    ): Boolean {
        val document = PdfDocument()
        return try {
            drawSummaryPage(document, sessions, options, stamp, strikeZonePng)
            drawReleasePage(document, releaseMapPng)
            FileOutputStream(target).use { out -> document.writeTo(out) }
            true
        } catch (t: Throwable) {
            android.util.Log.e("PitchReportPdf", "PDF 產生失敗", t)
            false
        } finally {
            document.close()
        }
    }

    private fun drawSummaryPage(
        document: PdfDocument,
        sessions: List<PitchSession>,
        options: ExportOptions,
        stamp: String,
        strikeZonePng: File
    ) {
        val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create())
        val canvas = page.canvas
        canvas.drawColor(COLOR_BG)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT
            textSize = 24f
            isFakeBoldText = true
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT
            textSize = 11f
        }
        canvas.drawText(PitchReportSummary.headline(stamp), MARGIN, MARGIN + 6f, titlePaint)
        bodyPaint.color = COLOR_HINT
        canvas.drawText(PitchReportSummary.subtitle(stamp), MARGIN, MARGIN + 24f, bodyPaint)
        bodyPaint.color = COLOR_ACCENT
        canvas.drawLine(MARGIN, MARGIN + 34f, PAGE_WIDTH - MARGIN, MARGIN + 34f, bodyPaint)

        bodyPaint.color = COLOR_TEXT
        val text = PitchReportSummary.build(sessions, options).joinToString("\n")
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, bodyPaint, (PAGE_WIDTH - MARGIN * 2).toInt())
            .setLineSpacing(4f, 1.0f)
            .build()
        canvas.save()
        canvas.translate(MARGIN, MARGIN + 50f)
        layout.draw(canvas)
        canvas.restore()

        val imageTop = MARGIN + 50f + layout.height + 16f
        drawImage(canvas, strikeZonePng, imageTop, PAGE_HEIGHT - MARGIN)
        document.finishPage(page)
    }

    private fun drawReleasePage(document: PdfDocument, releaseMapPng: File) {
        val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 2).create())
        val canvas = page.canvas
        canvas.drawColor(COLOR_BG)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT
            textSize = 18f
            isFakeBoldText = true
        }
        canvas.drawText("3D 放球分佈", MARGIN, MARGIN + 6f, titlePaint)
        drawImage(canvas, releaseMapPng, MARGIN + 24f, PAGE_HEIGHT - MARGIN)
        document.finishPage(page)
    }

    /** 等比縮放並置中，超出可用高度就不再畫（避免圖片被裁掉半張） */
    private fun drawImage(canvas: android.graphics.Canvas, file: File, top: Float, bottom: Float) {
        if (!file.exists()) return
        val bitmap: Bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
        try {
            val availableWidth = PAGE_WIDTH - MARGIN * 2
            val availableHeight = (bottom - top).coerceAtLeast(0f)
            if (availableHeight < 80f) return
            val scale = minOf(
                availableWidth / bitmap.width,
                availableHeight / bitmap.height
            )
            val width = (bitmap.width * scale).toInt()
            val height = (bitmap.height * scale).toInt()
            val left = ((PAGE_WIDTH - width) / 2).toInt()
            val dest = Rect(left, top.toInt(), left + width, top.toInt() + height)
            canvas.drawBitmap(bitmap, null, dest, Paint(Paint.FILTER_BITMAP_FLAG))
        } finally {
            bitmap.recycle()
        }
    }
}
