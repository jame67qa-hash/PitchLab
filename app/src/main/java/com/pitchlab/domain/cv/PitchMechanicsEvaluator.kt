package com.pitchlab.domain.cv

import com.pitchlab.domain.pose.PoseFrame
import com.pitchlab.domain.pose.PoseIndices
import com.pitchlab.domain.pose.PosePoint
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 出手影格的姿勢評分（Phase 4）。
 *
 * 為什麼評分要放在純 Kotlin 而不是直接寫進 MediaPipe 回調：
 * 1. 角度公式是最容易寫錯、也最需要被測試的部分，抽出來就能用合成關鍵點驗證；
 * 2. 「多少度算好」是教學口徑，未來一定會調參，集中在這裡比散在 ViewModel 裡可維護。
 *
 * 四項指標（皆為 2D 投影值，只用於姿勢比較，不用於 3D 重建）：
 * - 肩髖分離：肩線與髖線的夾角，代表軀幹扭轉；
 * - 前導手肘：出手瞬間投球臂的肘部夾角，越接近伸直（180°）代表放球越延伸；
 * - 軀幹前傾：肩中點→髖中點連線與垂直線的夾角；
 * - 放球相對高度：手腕高度佔「肩到踝」身高的比例，接近 1 表示在肩高附近放球。
 *
 * 分數 = 100 - 各項偏離理想區間的扣分（上限 30/30/20/20），
 * 因此單項嚴重偏差不會把總分歸零，仍可看出其他項目的表現。
 */
class PitchMechanicsEvaluator(
    private val visibilityThreshold: Double = 0.5
) {

    data class MechanicsFeedback(
        val score: Int,
        val grade: String,
        val separationDeg: Double?,
        val leadElbowDeg: Double?,
        val trunkTiltDeg: Double?,
        val releaseHeightRatio: Double?,
        val notes: List<String>
    )

    fun evaluate(frame: PoseFrame): MechanicsFeedback? {
        val lShoulder = frame.visible(PoseIndices.LEFT_SHOULDER) ?: return null
        val rShoulder = frame.visible(PoseIndices.RIGHT_SHOULDER) ?: return null
        val lHip = frame.visible(PoseIndices.LEFT_HIP) ?: return null
        val rHip = frame.visible(PoseIndices.RIGHT_HIP) ?: return null

        val leftWrist = frame.visible(PoseIndices.LEFT_WRIST)
        val rightWrist = frame.visible(PoseIndices.RIGHT_WRIST)
        // 只看得到一隻手腕時就用那一側；兩隻都看得到則取可見度高的（通常是投球手）
        val throwingRight = when {
            leftWrist == null && rightWrist == null -> return null
            leftWrist == null -> true
            rightWrist == null -> false
            else -> rightWrist.visibility >= leftWrist.visibility
        }
        val shoulder = if (throwingRight) rShoulder else lShoulder
        val elbow = frame.visible(if (throwingRight) PoseIndices.RIGHT_ELBOW else PoseIndices.LEFT_ELBOW)
        val wrist = if (throwingRight) rightWrist else leftWrist
        val ankle = frame.visible(if (throwingRight) PoseIndices.RIGHT_ANKLE else PoseIndices.LEFT_ANKLE)
            ?: ((frame.visible(PoseIndices.LEFT_ANKLE) ?: frame.visible(PoseIndices.RIGHT_ANKLE)))

        val separation = runCatching {
            lineAngleDeg(rShoulder, lShoulder).let { shoulderAngle ->
                lineAngleDeg(rHip, lHip).let { hipAngle ->
                    var diff = abs(shoulderAngle - hipAngle)
                    if (diff > 180.0) diff = 360.0 - diff
                    if (diff > 90.0) 180.0 - diff else diff
                }
            }
        }.getOrNull()

        val elbowAngle = if (elbow != null && wrist != null) angleAt(elbow, shoulder, wrist) else null
        val trunkTilt = trunkTiltDeg(lShoulder, rShoulder, lHip, rHip)
        val heightRatio = if (wrist != null && ankle != null) {
            val shoulderMidY = (lShoulder.y + rShoulder.y) / 2.0
            val span = ankle.y - shoulderMidY
            if (span > 1e-6) ((ankle.y - wrist.y) / span) else null
        } else null

        val notes = mutableListOf<String>()
        var deduction = 0.0

        separation?.let { value ->
            when {
                value < 20.0 -> {
                    deduction += ((20.0 - value) * 2.5).coerceAtMost(30.0)
                    notes += "肩髖分離偏小（${fmt(value)}°，建議 20–45°）：髖部太早開，力量會漏掉"
                }
                value > 45.0 -> {
                    deduction += ((value - 45.0) * 2.5).coerceAtMost(30.0)
                    notes += "肩髖分離過大（${fmt(value)}°，建議 20–45°）：容易造成腰部代償"
                }
            }
        }

        elbowAngle?.let { value ->
            when {
                value < 150.0 -> {
                    deduction += ((150.0 - value) * 2.0).coerceAtMost(30.0)
                    notes += "出手時手肘彎曲（${fmt(value)}°，建議 150–178°）：放球點會偏後、球速折損"
                }
                value > 178.0 -> notes += "手肘完全鎖死（${fmt(value)}°）：長期容易造成肘部負擔"
            }
        }

        trunkTilt?.let { value ->
            when {
                value < 5.0 -> {
                    deduction += ((5.0 - value) * 2.0).coerceAtMost(20.0)
                    notes += "軀幹幾乎直立（${fmt(value)}°）：沒有把體重帶向本壘"
                }
                value > 25.0 -> {
                    deduction += ((value - 25.0) * 2.0).coerceAtMost(20.0)
                    notes += "軀幹前傾過多（${fmt(value)}°，建議 5–25°）：好球帶會壓低"
                }
            }
        }

        heightRatio?.let { value ->
            when {
                value < 0.80 -> {
                    deduction += ((0.80 - value) * 40.0).coerceAtMost(20.0)
                    notes += "放球點偏低（身高比 ${String.format("%.2f", value)}）：容易形成高角度壞球"
                }
                value > 1.00 -> {
                    deduction += ((value - 1.00) * 40.0).coerceAtMost(20.0)
                    notes += "放球點偏高（身高比 ${String.format("%.2f", value)}）：進壘容易偏高"
                }
            }
        }

        val score = (100.0 - deduction).coerceIn(0.0, 100.0).toInt()
        val grade = when {
            score >= 85 -> "A"
            score >= 70 -> "B"
            score >= 55 -> "C"
            else -> "D"
        }
        if (notes.isEmpty()) notes += "四項指標都在理想區間，姿勢穩定"

        return MechanicsFeedback(
            score = score,
            grade = grade,
            separationDeg = separation,
            leadElbowDeg = elbowAngle,
            trunkTiltDeg = trunkTilt,
            releaseHeightRatio = heightRatio,
            notes = notes
        )
    }

    private fun PoseFrame.visible(index: Int): PosePoint? =
        point(index)?.takeIf { it.visibility >= visibilityThreshold }

    /** 線段角度（度，-180..180），以 +x 為 0、影像座標 y 向下 */
    private fun lineAngleDeg(a: PosePoint, b: PosePoint): Double =
        Math.toDegrees(atan2(b.y - a.y, b.x - a.x))

    /** 以 [vertex] 為頂點，[a] 與 [b] 兩端形成的夾角（0..180） */
    private fun angleAt(vertex: PosePoint, a: PosePoint, b: PosePoint): Double {
        val ax = a.x - vertex.x
        val ay = a.y - vertex.y
        val bx = b.x - vertex.x
        val by = b.y - vertex.y
        val lenA = hypot(ax, ay)
        val lenB = hypot(bx, by)
        if (lenA < 1e-6 || lenB < 1e-6) return 0.0
        val cos = ((ax * bx + ay * by) / (lenA * lenB)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }

    /** 肩中點→髖中點連線與垂直線的夾角；垂直（直立）為 0°，前傾/後仰為正 */
    private fun trunkTiltDeg(
        lShoulder: PosePoint,
        rShoulder: PosePoint,
        lHip: PosePoint,
        rHip: PosePoint
    ): Double {
        val shoulderMidX = (lShoulder.x + rShoulder.x) / 2.0
        val shoulderMidY = (lShoulder.y + rShoulder.y) / 2.0
        val hipMidX = (lHip.x + rHip.x) / 2.0
        val hipMidY = (lHip.y + rHip.y) / 2.0
        val dx = shoulderMidX - hipMidX
        val dy = hipMidY - shoulderMidY
        if (abs(dy) < 1e-6 && abs(dx) < 1e-6) return 0.0
        return Math.toDegrees(abs(atan2(dx, dy)))
    }

    private fun fmt(value: Double): String = String.format("%.0f", value)

    /** 供測試使用的幾何工具，避免測試自行複製公式 */
    fun angleBetweenPoints(vertex: PosePoint, a: PosePoint, b: PosePoint): Double =
        angleAt(vertex, a, b)

    fun distance(a: PosePoint, b: PosePoint): Double = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
}
