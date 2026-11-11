package com.pitchlab.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val PitchLabDarkColors = darkColorScheme(
    primary = LaserCyan,
    onPrimary = PitchBlack,
    primaryContainer = LaserCyanDim,
    onPrimaryContainer = TextPrimary,
    secondary = RadarAmber,
    onSecondary = PitchBlack,
    tertiary = StrikeZoneGreen,
    background = PitchBlack,
    onBackground = TextPrimary,
    surface = PitchSurface,
    onSurface = TextPrimary,
    surfaceVariant = PitchSurfaceHigh,
    onSurfaceVariant = TextSecondary,
    error = AlarmRed
)

/**
 * 影片分析類 App 固定使用深色主題：淺色背景會在取景畫面上產生反光與誤判。
 * 參數保留 isSystemInDarkTheme 僅爲將來擴展，此處始終返回深色。
 */
@Composable
fun PitchLabTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PitchLabDarkColors,
        typography = PitchLabTypography,
        content = content
    )
}
