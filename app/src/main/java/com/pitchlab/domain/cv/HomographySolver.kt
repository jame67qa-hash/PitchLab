package com.pitchlab.domain.cv

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 四點單應矩陣解算（純 Kotlin，不依賴 OpenCV native）。
 *
 * 為什麼不用 [org.opencv.calib3d.Calib3d.findHomography]：
 * - 四點對應是「恰定」問題（8 個未知、8 個方程），閉式高斯消去即可，沒有 RANSAC 的必要；
 * - 純 Kotlin 可以在 JVM 單元測試裡驗算，不必拉真機或 native 庫；
 * - 少了 native 呼叫，標定頁不必等 OpenCV 載入完成。
 *
 * 座標約定：矩陣 h = [h11 h12 h13; h21 h22 h23; h31 h32 1]，列優先長度 9 的 DoubleArray。
 *
 *   u = (h11*x + h12*y + h13) / (h31*x + h32*y + 1)
 *   v = (h21*x + h22*y + h23) / (h31*x + h32*y + 1)
 */
class HomographySolver {

    /** 一個影像/場地座標點（雙精度，避免鏈式換算累積誤差） */
    data class XY(val x: Double, val y: Double)

    /**
     * 由 4 組對應點解單應矩陣。
     *
     * @param src 影像座標（像素或歸一化皆可，只要與 dst 同一單位體系）
     * @param dst 場地座標
     * @return 長度 9 的矩陣（h33 固定為 1）；點數不足、共線或退化時返回 null
     */
    fun solve(src: List<XY>, dst: List<XY>): DoubleArray? {
        if (src.size < 4 || dst.size < 4) return null
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = src[i].x
            val y = src[i].y
            val u = dst[i].x
            val v = dst[i].y
            a[i * 2] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        val h = solveLinearSystem(a) ?: return null
        return doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0)
    }

    /** 應用到單一像素點；分母接近 0（點落在消隱線上）時返回 null */
    fun apply(h: DoubleArray, x: Double, y: Double): XY? {
        if (h.size < 9) return null
        val denominator = h[6] * x + h[7] * y + h[8]
        if (abs(denominator) < 1e-12) return null
        val u = (h[0] * x + h[1] * y + h[2]) / denominator
        val v = (h[3] * x + h[4] * y + h[5]) / denominator
        return XY(u, v)
    }

    /** 求逆（伴隨矩陣法，3x3 成本極低），用於把場地座標反算回影像 */
    fun invert(h: DoubleArray): DoubleArray? {
        if (h.size < 9) return null
        val a = h[0]; val b = h[1]; val c = h[2]
        val d = h[3]; val e = h[4]; val f = h[5]
        val g = h[6]; val i = h[7]; val j = h[8]
        val det = a * (e * j - f * i) - b * (d * j - f * g) + c * (d * i - e * g)
        if (abs(det) < 1e-12) return null
        val inv = 1.0 / det
        return doubleArrayOf(
            (e * j - f * i) * inv, (c * i - b * j) * inv, (b * f - c * e) * inv,
            (f * g - d * j) * inv, (a * j - c * g) * inv, (c * d - a * f) * inv,
            (d * i - e * g) * inv, (b * g - a * i) * inv, (a * e - b * d) * inv
        )
    }

    /**
     * 標定品質：把四個角點用矩陣正算一次，取與目標點的平均像素誤差。
     * UI 用它告訴使用者「四角點得準不準」。
     */
    fun averageReprojectionError(src: List<XY>, dst: List<XY>, h: DoubleArray): Double {
        if (src.size < 4 || dst.size < 4) return Double.NaN
        var sum = 0.0
        for (i in 0 until 4) {
            val p = apply(h, src[i].x, src[i].y) ?: return Double.NaN
            sum += hypot(p.x - dst[i].x, p.y - dst[i].y)
        }
        return sum / 4.0
    }

    /**
     * 高斯消去（部分主元）。輸入為 n 列、n+1 欄的增廣矩陣，回傳解向量。
     * 主元過小視為奇異矩陣（四點共線、重複點）返回 null。
     */
    private fun solveLinearSystem(matrix: Array<DoubleArray>): DoubleArray? {
        val n = matrix.size
        val a = Array(n) { matrix[it].copyOf() }
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) {
                if (abs(a[row][col]) > abs(a[pivot][col])) pivot = row
            }
            if (abs(a[pivot][col]) < 1e-10) return null
            if (pivot != col) {
                val tmp = a[pivot]
                a[pivot] = a[col]
                a[col] = tmp
            }
            val pivotValue = a[col][col]
            for (k in col..n) a[col][k] /= pivotValue
            for (row in 0 until n) {
                if (row == col) continue
                val factor = a[row][col]
                if (factor == 0.0) continue
                for (k in col..n) a[row][k] -= factor * a[col][k]
            }
        }
        return DoubleArray(n) { a[it][n] }
    }
}
