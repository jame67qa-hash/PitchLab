package com.pitchlab.core.cv

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * ImageProxy(YUV_420_888) -> Bitmap。
 *
 * 用途：
 * 1) 調試截幀（UI 上「抓幀」按鈕）；
 * 2) Phase 3 把幀餵給 MediaPipe Pose（它只接受 Bitmap/MediaImage，不喫 YUV）。
 *
 * 效能：只應在需要時調用。若每幀都轉，720p@60fps 會成爲瓶頸，
 * 因此預設分析鏈走 Y plane 灰度 + OpenCV，不經過 Bitmap。
 */
class YuvToRgbConverter {

    private val yuvMat = Mat()
    private val rgbaMat = Mat()

    private var reusableBitmap: Bitmap? = null
    private var nv21Cache: ByteArray = ByteArray(0)

    fun toBitmap(image: ImageProxy): Bitmap {
        val width = image.width
        val height = image.height
        val nv21 = YuvPlaneExtractor.toNv21(image)
        if (nv21Cache.size != nv21.size) {
            nv21Cache = nv21
        } else {
            System.arraycopy(nv21, 0, nv21Cache, 0, nv21.size)
        }

        if (yuvMat.empty() || yuvMat.rows() != height * 3 / 2 || yuvMat.cols() != width) {
            yuvMat.create(height * 3 / 2, width, CvType.CV_8UC1)
        }
        yuvMat.put(0, 0, nv21Cache)
        Imgproc.cvtColor(yuvMat, rgbaMat, Imgproc.COLOR_YUV2RGBA_NV21)

        val bitmap = bitmapFor(width, height)
        Utils.matToBitmap(rgbaMat, bitmap)
        return bitmap
    }

    /** 已有 RGBA Mat 時的直接轉換（例如疊加雷達軌跡後回寫） */
    fun matToBitmap(rgba: Mat): Bitmap {
        require(rgba.type() == CvType.CV_8UC4) { "需要 CV_8UC4 RGBA Mat" }
        val bitmap = bitmapFor(rgba.cols(), rgba.rows())
        Utils.matToBitmap(rgba, bitmap)
        return bitmap
    }

    private fun bitmapFor(width: Int, height: Int): Bitmap {
        val cached = reusableBitmap
        if (cached != null && cached.width == width && cached.height == height && !cached.isRecycled) {
            return cached
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        reusableBitmap = bitmap
        return bitmap
    }

    fun release() {
        if (!yuvMat.empty()) yuvMat.release()
        if (!rgbaMat.empty()) rgbaMat.release()
        reusableBitmap?.recycle()
        reusableBitmap = null
        Log.d(TAG, "YuvToRgbConverter released")
    }

    private companion object {
        const val TAG = "YuvToRgbConverter"
    }
}
