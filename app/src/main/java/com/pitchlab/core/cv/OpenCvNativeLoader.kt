package com.pitchlab.core.cv

import android.util.Log
import org.opencv.core.Core
import org.opencv.core.Mat

/**
 * OpenCV 4.9+ 的 Maven Central 官方 AAR 已內置 native 庫（arm64-v8a / armeabi-v7a / x86_64），
 * 不再需要 OpenCVManager APK。這裏集中做一次載入，避免各處在首次調用時才崩。
 *
 * 注意：org.opencv:opencv:4.10.0 的 classes.jar 內 Core.NATIVE_LIBRARY_NAME 為
 * "opencv_java4100"，但同一個 AAR 實際打包的檔案是 "libopencv_java4.so"，兩者不一致。
 * 若只依賴 Core.NATIVE_LIBRARY_NAME，Android 會拋
 * `dlopen failed: library "libopencv_java4100.so" not found`，
 * 進而在第一次 new Mat() 時拋 UnsatisfiedLinkError 閃退。
 * 因此這裏按「實際打包檔名優先」的順序嘗試，並在載入後用一次 Mat 建立/釋放做自檢。
 */
object OpenCvNativeLoader {

    private const val TAG = "OpenCvNativeLoader"

    /** 依序嘗試的 native 庫名：先試官方 AAR 實際檔名，再退回 Java 常數與舊版名稱。 */
    private val CANDIDATES: List<String> = buildList {
        add("opencv_java4")
        runCatching { add(Core.NATIVE_LIBRARY_NAME) }
        add("opencv_java4100")
        add("opencv_java3")
    }.distinct()

    @Volatile
    private var loaded: Boolean = false

    @Volatile
    private var loadedLibrary: String? = null

    @Volatile
    private var loadError: String? = null

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            val errors = StringBuilder()
            for (name in CANDIDATES) {
                try {
                    System.loadLibrary(name)
                    if (verifyJni()) {
                        loaded = true
                        loadedLibrary = name
                        loadError = null
                        Log.i(TAG, "OpenCV native library loaded: lib$name.so")
                        return true
                    }
                    errors.append("$name: JNI 自檢失敗; ")
                    Log.w(TAG, "OpenCV native library load $name but JNI self-check failed")
                } catch (t: Throwable) {
                    errors.append("$name: ${t.message ?: t.javaClass.simpleName}; ")
                    Log.w(TAG, "OpenCV native library load $name failed: ${t.message}")
                }
            }
            loadError = errors.toString().trim()
            Log.e(TAG, "OpenCV native library unavailable: $loadError")
            return false
        }
    }

    /** 以建立/釋放一個 Mat 驗證 JNI 符號真的已註冊。 */
    private fun verifyJni(): Boolean {
        return try {
            val probe = Mat()
            probe.release()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "OpenCV JNI self-check threw: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    fun isLoaded(): Boolean = loaded

    fun libraryName(): String? = loadedLibrary

    fun lastError(): String? = loadError
}
