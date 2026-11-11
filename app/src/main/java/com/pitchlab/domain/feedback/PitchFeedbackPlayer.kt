package com.pitchlab.domain.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 出手回饋：震動 + 中文語音。
 *
 * 為什麼預設關閉：
 * 1. 錄影時喇叭播放的「好球」會被麥克風收進片段裡，聽起來像 App 在自言自語；
 * 2. 在室內打擊練習場大聲播報會干擾其他人。
 * 所以做成明確的開關，狀態存在 SharedPreferences，重開 App 仍記得。
 *
 * 震動模式刻意不同：好球單一下短震、壞球兩下，戴著手套也能分辨。
 */
@Singleton
class PitchFeedbackPlayer @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "PitchFeedback"
        private const val PREFS = "pitch_feedback"
        private const val KEY_ENABLED = "enabled"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val vibrator: Vibrator? = resolveVibrator()
    private var tts: TextToSpeech? = null

    @Volatile
    private var ttsReady = false

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            if (!value) {
                runCatching { tts?.stop() }
            }
        }

    init {
        runCatching {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val chinese = Locale("zh", "TW")
                    val support = tts?.isLanguageAvailable(chinese)
                    val result = if (support == TextToSpeech.LANG_AVAILABLE ||
                        support == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                        support == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
                    ) {
                        tts?.setLanguage(chinese)
                    } else {
                        tts?.setLanguage(Locale.CHINESE)
                    }
                    ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                    if (!ttsReady) {
                        Log.w(TAG, "裝置沒有可用的中文語音，回饋只剩震動")
                    }
                } else {
                    Log.w(TAG, "TextToSpeech 初始化失敗 status=$status")
                }
            }
        }.onFailure { Log.w(TAG, "TextToSpeech 建立失敗", it) }
    }

    /** 一次出手的完整回饋：先震動（立即），再語音（可能晚幾百毫秒） */
    fun feedback(isStrike: Boolean?, text: String) {
        if (!enabled) return
        vibrate(isStrike)
        if (ttsReady) {
            runCatching {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pitchlab-${System.currentTimeMillis()}")
            }.onFailure { Log.w(TAG, "語音播放失敗", it) }
        }
    }

    private fun vibrate(isStrike: Boolean?) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (isStrike == true) {
                    VibrationEffect.createOneShot(40L, 140)
                } else {
                    VibrationEffect.createWaveform(longArrayOf(0L, 30L, 70L, 30L), -1)
                }
                device.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                device.vibrate(if (isStrike == true) 40L else 100L)
            }
        }.onFailure { Log.w(TAG, "震動失敗", it) }
    }

    private fun resolveVibrator(): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()
}
