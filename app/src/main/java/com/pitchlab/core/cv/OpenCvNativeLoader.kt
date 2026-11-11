package com.pitchlab.core.cv

import android.util.Log
import org.opencv.core.Core

/**
 * OpenCV 4.9+ 的 Maven Central 官方 AAR 已內置 native 庫（arm64-v8a / armeabi-v7a），
 * 不再需要 OpenCVManager APK。這裏集中做一次載入，避免各處在首次調用時才崩。
 */
object OpenCvNativeLoader {

    private const val TAG = "OpenCvNativeLoader"

    @Volatile
    private var loaded: Boolean = false

    @Volatile
    private var loadError: String? = null

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            return try {
                System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
                loaded = true
                Log.i(TAG, "OpenCV native library loaded: ${Core.NATIVE_LIBRARY_NAME}")
                true
            } catch (t: Throwable) {
                loadError = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "OpenCV native library load failed", t)
                false
            }
        }
    }

    fun isLoaded(): Boolean = loaded

    fun lastError(): String? = loadError
}
