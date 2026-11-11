package com.pitchlab.domain.cv

import kotlin.math.hypot

/** 一次手腕觀測（座標為歸一化影像座標，visibility < 0.5 視為不可信） */
data class WristSample(
    val timestampMs: Long,
    val x: Double,
    val y: Double,
    val visibility: Double
)

/**
 * 出手點偵測：手腕加速度峰值。
 *
 * 為什麼用加速度而不是「速度超過門檻」：
 * 投手在整個投球動作中手腕都在移動（抬手、跨步、揮臂），單純的速度門檻會被
 * 揮臂前半段誤觸發；而**加速度峰值**對應「手臂末端甩出去」那一瞬，是最接近
 * 實際放球點的運動學特徵。
 *
 * 判定流程：
 * 1. 每筆觀測算出瞬時速度（歸一化單位 / 秒），再做加速度差分；
 * 2. 加速度超過 [accelThreshold] 時記為候選峰值（保留當下時間與座標）；
 * 3. 加速度回落到峰值 60% 以下（表示甩臂結束）才**回溯**輸出該候選點，
 *    這樣輸出時間戳就是峰值時間，而不是「發現峰值已過」的時間；
 * 4. 冷卻 [cooldownMs] 內不重複輸出，避免同一次投球被切成兩個出手點。
 *
 * 這個類別刻意做成純 Kotlin（不碰 MediaPipe / Bitmap），才能在 JVM 單元測試裡
 * 用合成軌跡驗證峰值偵測與冷卻邏輯。
 */
class ReleaseDetector(
    private val minVisibility: Double = 0.5,
    private val accelThreshold: Double = 8.0,
    private val cooldownMs: Long = 900L,
    private val maxWindowMs: Long = 400L
) {

    data class Release(
        val timestampMs: Long,
        val acceleration: Double,
        val wristX: Double,
        val wristY: Double
    )

    private val samples = ArrayDeque<WristSample>()

    private var previousSpeed: Double? = null
    private var previousTimestampMs: Long = 0L

    private var candidateAccel = 0.0
    private var candidateTimestampMs = 0L
    private var candidateX = 0.0
    private var candidateY = 0.0
    private var hasCandidate = false

    private var lastEmitMs: Long = Long.MIN_VALUE / 4

    fun reset() {
        samples.clear()
        previousSpeed = null
        previousTimestampMs = 0L
        candidateAccel = 0.0
        candidateTimestampMs = 0L
        hasCandidate = false
        lastEmitMs = Long.MIN_VALUE / 4
    }

    /** 目前累積的觀測數（給 UI 顯示「姿態訊號 N 筆」用） */
    val sampleCount: Int get() = samples.size

    fun update(sample: WristSample): Release? {
        if (sample.visibility < minVisibility) {
            // 遮擋時只清掉速度歷史，避免遮擋前後用一個假的巨大位移算出假峰值
            previousSpeed = null
            return null
        }

        samples.addLast(sample)
        while (samples.isNotEmpty() && sample.timestampMs - samples.first().timestampMs > maxWindowMs) {
            samples.removeFirst()
        }
        if (samples.size < 2) return null

        val last = samples.last()
        val previous = samples[samples.size - 2]
        val deltaSec = (last.timestampMs - previous.timestampMs) / 1000.0
        if (deltaSec <= 0.0) return null

        val speed = hypot(last.x - previous.x, last.y - previous.y) / deltaSec
        val previousSpeedValue = previousSpeed
        val accel = if (previousSpeedValue == null || previousTimestampMs == 0L) {
            0.0
        } else {
            val spanSec = ((last.timestampMs - previousTimestampMs) / 1000.0).coerceAtLeast(1e-3)
            (speed - previousSpeedValue) / spanSec
        }
        previousSpeed = speed
        previousTimestampMs = last.timestampMs

        if (accel > accelThreshold && accel >= candidateAccel) {
            candidateAccel = accel
            candidateTimestampMs = last.timestampMs
            candidateX = last.x
            candidateY = last.y
            hasCandidate = true
        }

        if (hasCandidate) {
            val decayed = accel < candidateAccel * 0.6
            val timedOut = last.timestampMs - candidateTimestampMs > maxWindowMs
            if (decayed || timedOut) {
                val release = Release(
                    timestampMs = candidateTimestampMs,
                    acceleration = candidateAccel,
                    wristX = candidateX,
                    wristY = candidateY
                )
                val cooledDown = candidateTimestampMs - lastEmitMs >= cooldownMs
                hasCandidate = false
                candidateAccel = 0.0
                if (cooledDown) {
                    lastEmitMs = candidateTimestampMs
                    return release
                }
            }
        }
        return null
    }
}
