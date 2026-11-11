package com.pitchlab.domain.model

/**
 * 相機座標系下的三維點（單位：英寸）。
 * x 向右、y 向下、z 爲沿光軸的前向距離（球離相機的遠近）。
 */
data class Point3D(
    val xInches: Double,
    val yInches: Double,
    val zInches: Double
)
