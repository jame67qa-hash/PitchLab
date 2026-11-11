package com.pitchlab

import android.app.Application
import android.util.Log
import com.pitchlab.core.cv.OpenCvNativeLoader
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt 根組件。
 * OpenCV native 庫在這裏做一次性載入：CameraX 第一幀就會用到 Mat，
 * 若等到分析執行緒再載入，首幀會因 JNI 初始化阻塞而丟幀。
 */
@HiltAndroidApp
class PitchLabApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val loaded = OpenCvNativeLoader.ensureLoaded()
        if (!loaded) {
            Log.e(TAG, "OpenCV 載入失敗：${OpenCvNativeLoader.lastError()}（分析功能將不可用）")
        }
    }

    private companion object {
        const val TAG = "PitchLabApp"
    }
}
