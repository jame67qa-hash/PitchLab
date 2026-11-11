package com.pitchlab.core.cv

import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * 從 YUV_420_888 的 Y plane 直接構造灰度 Mat。
 *
 * 爲什麼走 Y plane 而不是先轉 RGBA：
 * - 背景相減只需要亮度，省掉一次全圖色彩轉換，720p 下可省 15~25ms/幀；
 * - Y plane 的 rowStride 可能帶 padding，必須按行拷貝，不能假設連續。
 *
 * 注意：本類不是執行緒安全的，只允許在 CameraX 的單一分析執行緒上覆用實例。
 */
object YuvPlaneExtractor {

    fun yPlaneToGrayMat(image: ImageProxy, destination: Mat, rowBuffer: ByteArray): Pair<Mat, ByteArray> {
        require(image.format == ImageFormat.YUV_420_888) {
            "YuvPlaneExtractor 僅支援 YUV_420_888，實際爲 ${image.format}"
        }
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        if (destination.empty() || destination.rows() != height ||
            destination.cols() != width || destination.type() != CvType.CV_8UC1
        ) {
            destination.create(height, width, CvType.CV_8UC1)
        }

        when {
            // 最常見：連續記憶體，可以一次性拷完
            pixelStride == 1 && rowStride == width -> {
                val bulk = ByteArray(width * height)
                buffer.position(0)
                buffer.get(bulk, 0, width * height)
                destination.put(0, 0, bulk)
            }

            // 行有 padding：逐行拷貝（720 行 => 720 次 JNI put，開銷可接受）
            pixelStride == 1 -> {
                val row = if (rowBuffer.size >= width) rowBuffer else ByteArray(width)
                for (rowIndex in 0 until height) {
                    buffer.position(rowIndex * rowStride)
                    buffer.get(row, 0, width)
                    destination.put(rowIndex, 0, row)
                }
                return destination to row
            }

            // pixelStride > 1（少數廠商），退化爲逐像素讀取
            else -> {
                val row = if (rowBuffer.size >= width) rowBuffer else ByteArray(width)
                val rowStart = IntArray(height)
                for (rowIndex in 0 until height) {
                    rowStart[rowIndex] = rowIndex * rowStride
                    for (col in 0 until width) {
                        row[col] = buffer.get(rowStart[rowIndex] + col * pixelStride)
                    }
                    destination.put(rowIndex, 0, row)
                }
                return destination to row
            }
        }

        val row = if (rowBuffer.size >= width) rowBuffer else ByteArray(width)
        return destination to row
    }

    /**
     * 構造 NV21（VU 交織）位元組數組，供 OpenCV 的 COLOR_YUV2RGBA_NV21 使用。
     * 相比逐像素手寫 RGB 轉換，cvtColor 走 SIMD，720p 下快一個數量級。
     */
    fun toNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val out = ByteArray(ySize + (width * height) / 2)

        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val row = ByteArray(width)
        var offset = 0
        for (r in 0 until height) {
            if (yPixelStride == 1) {
                yBuffer.position(r * yRowStride)
                yBuffer.get(row, 0, width)
            } else {
                for (c in 0 until width) {
                    row[c] = yBuffer.get(r * yRowStride + c * yPixelStride)
                }
            }
            System.arraycopy(row, 0, out, offset, width)
            offset += width
        }

        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        var chromaOffset = ySize
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (r in 0 until chromaHeight) {
            val uRowStart = r * uRowStride
            val vRowStart = r * vRowStride
            for (c in 0 until chromaWidth) {
                val u = uBuffer.get(uRowStart + c * uPixelStride)
                val v = vBuffer.get(vRowStart + c * vPixelStride)
                // NV21 佈局：V 在前，U 在後
                out[chromaOffset++] = v
                out[chromaOffset++] = u
            }
        }
        return out
    }
}
