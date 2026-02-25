package moe.koiverse.archivetune.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class GlassSurfaceStyle(
    val surfaceTint: Color,
    val surfaceAlpha: Float,
    val overlayColor: Color,
    val overlayAlpha: Float,
    val blurRadius: Dp,
    val useVibrancy: Boolean,
    val useLens: Boolean,
    val lensHeight: Dp,
    val lensAmount: Dp,
    val borderColor: Color,
    val borderAlpha: Float,
    val backgroundDimAlpha: Float,
)

object GlassEffectDefaults {

    val NavigationBarDark = GlassSurfaceStyle(
        surfaceTint = Color(0xFF0A0A14),
        surfaceAlpha = 0.52f,
        overlayColor = Color(0xFF0A0A14),
        overlayAlpha = 0.35f,
        blurRadius = 40.dp,
        useVibrancy = true,
        useLens = true,
        lensHeight = 16.dp,
        lensAmount = 32.dp,
        borderColor = Color.White,
        borderAlpha = 0.08f,
        backgroundDimAlpha = 0.45f,
    )

    val NavigationBarLight = GlassSurfaceStyle(
        surfaceTint = Color.White,
        surfaceAlpha = 0.48f,
        overlayColor = Color.White,
        overlayAlpha = 0.30f,
        blurRadius = 40.dp,
        useVibrancy = true,
        useLens = true,
        lensHeight = 16.dp,
        lensAmount = 32.dp,
        borderColor = Color.White,
        borderAlpha = 0.40f,
        backgroundDimAlpha = 0.20f,
    )

    val NavigationBarPureBlack = GlassSurfaceStyle(
        surfaceTint = Color(0xFF050508),
        surfaceAlpha = 0.68f,
        overlayColor = Color(0xFF0A0A14),
        overlayAlpha = 0.45f,
        blurRadius = 40.dp,
        useVibrancy = true,
        useLens = true,
        lensHeight = 14.dp,
        lensAmount = 28.dp,
        borderColor = Color.White,
        borderAlpha = 0.05f,
        backgroundDimAlpha = 0.55f,
    )

    val MiniPlayerDark = GlassSurfaceStyle(
        surfaceTint = Color(0xFF0A0A14),
        surfaceAlpha = 0.48f,
        overlayColor = Color(0xFF0A0A14),
        overlayAlpha = 0.32f,
        blurRadius = 36.dp,
        useVibrancy = true,
        useLens = true,
        lensHeight = 18.dp,
        lensAmount = 36.dp,
        borderColor = Color.White,
        borderAlpha = 0.10f,
        backgroundDimAlpha = 0.40f,
    )

    val MiniPlayerLight = GlassSurfaceStyle(
        surfaceTint = Color.White,
        surfaceAlpha = 0.44f,
        overlayColor = Color.White,
        overlayAlpha = 0.28f,
        blurRadius = 36.dp,
        useVibrancy = true,
        useLens = true,
        lensHeight = 18.dp,
        lensAmount = 36.dp,
        borderColor = Color.White,
        borderAlpha = 0.45f,
        backgroundDimAlpha = 0.18f,
    )

    val MiniPlayerPureBlack = GlassSurfaceStyle(
        surfaceTint = Color(0xFF050508),
        surfaceAlpha = 0.62f,
        overlayColor = Color(0xFF0A0A14),
        overlayAlpha = 0.42f,
        blurRadius = 36.dp,
        useVibrancy = true,
        useLens = true,
        lensHeight = 16.dp,
        lensAmount = 32.dp,
        borderColor = Color.White,
        borderAlpha = 0.06f,
        backgroundDimAlpha = 0.50f,
    )

    fun navigationBarStyle(isDark: Boolean, isPureBlack: Boolean): GlassSurfaceStyle {
        return when {
            isPureBlack -> NavigationBarPureBlack
            isDark -> NavigationBarDark
            else -> NavigationBarLight
        }
    }

    fun miniPlayerStyle(isDark: Boolean, isPureBlack: Boolean): GlassSurfaceStyle {
        return when {
            isPureBlack -> MiniPlayerPureBlack
            isDark -> MiniPlayerDark
            else -> MiniPlayerLight
        }
    }
}
