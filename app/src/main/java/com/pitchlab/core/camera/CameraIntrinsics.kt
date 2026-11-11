package com.pitchlab.core.camera

import android.hardware.camera2.CameraCharacteristics
import android.util.SizeF
import kotlin.math.max
import kotlin.math.min

/**
 * 針孔相機內參（像素單位），用於「像素 -> 物理空間」換算。
 *
 * fx / fy 由「物理焦距(mm) + 傳感器物理尺寸(mm) + 輸出圖像解析度」按比例換算得到；
 * 這是不依賴標定板的近似做法，Phase 2 會用本壘板四點標定得到真正的外參。
 */
data class CameraIntrinsics(
    val focalLengthPxX: Double,
    val focalLengthPxY: Double,
    val principalPointX: Double,
    val principalPointY: Double,
    val imageWidth: Int,
    val imageHeight: Int,
    val physicalFocalLengthMm: Double,
    val sensorWidthMm: Double,
    val sensorHeightMm: Double
) {
    /** 以像素爲單位的 35mm 等效焦距，用於 UI 展示 */
    val equivalent35mm: Int
        get() = if (imageWidth == 0) 0 else {
            val diagPx = Math.sqrt(
                (imageWidth.toDouble() * imageWidth + imageHeight.toDouble() * imageHeight)
            )
            val f35 = 43.266 * focalLengthPxX / diagPx
            Math.round(f35).toInt()
        }
}

object CameraIntrinsicsFactory {

    /**
     * @param analysisWidth  已轉正的圖像寬度（像素）
     * @param analysisHeight 已轉正的圖像高度（像素）
     */
    fun create(
        characteristics: CameraCharacteristics,
        analysisWidth: Int,
        analysisHeight: Int
    ): CameraIntrinsics? {
        if (analysisWidth <= 0 || analysisHeight <= 0) return null

        val physicalFocal = characteristics
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull() ?: return null
        val sensorSize: SizeF = characteristics
            .get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null

        val sensorLong = max(sensorSize.width, sensorSize.height).toDouble()
        val sensorShort = min(sensorSize.width, sensorSize.height).toDouble()
        if (sensorLong <= 0.0 || sensorShort <= 0.0) return null

        val isPortrait = analysisHeight > analysisWidth
        // 傳感器天然是橫向的：轉正後若爲豎圖，圖像寬度對應傳感器短邊
        val sensorForWidth = if (isPortrait) sensorShort else sensorLong
        val sensorForHeight = if (isPortrait) sensorLong else sensorShort

        val fx = physicalFocal.toDouble() / sensorForWidth * analysisWidth
        val fy = physicalFocal.toDouble() / sensorForHeight * analysisHeight

        return CameraIntrinsics(
            focalLengthPxX = fx,
            focalLengthPxY = fy,
            principalPointX = analysisWidth / 2.0,
            principalPointY = analysisHeight / 2.0,
            imageWidth = analysisWidth,
            imageHeight = analysisHeight,
            physicalFocalLengthMm = physicalFocal.toDouble(),
            sensorWidthMm = sensorLong,
            sensorHeightMm = sensorShort
        )
    }
}
