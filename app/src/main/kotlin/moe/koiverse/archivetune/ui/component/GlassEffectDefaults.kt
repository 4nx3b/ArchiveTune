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
    val borderColor: Color,
    val borderAlpha: Float,
)

object GlassEffectDefaults {

    val NavigationBarDark = GlassSurfaceStyle(
        surfaceTint = Color(0xFF0A0A0A),
        surfaceAlpha = 0.42f,
        overlayColor = Color.White,
        overlayAlpha = 0.04f,
        blurRadius = 28.dp,
        useVibrancy = true,
        borderColor = Color.White,
        borderAlpha = 0.10f,
    )

    val NavigationBarLight = GlassSurfaceStyle(
        surfaceTint = Color.White,
        surfaceAlpha = 0.55f,
        overlayColor = Color.White,
        overlayAlpha = 0.20f,
        blurRadius = 28.dp,
        useVibrancy = true,
        borderColor = Color.White,
        borderAlpha = 0.45f,
    )

    val NavigationBarPureBlack = GlassSurfaceStyle(
        surfaceTint = Color.Black,
        surfaceAlpha = 0.65f,
        overlayColor = Color.White,
        overlayAlpha = 0.02f,
        blurRadius = 28.dp,
        useVibrancy = true,
        borderColor = Color.White,
        borderAlpha = 0.06f,
    )

    val MiniPlayerDark = GlassSurfaceStyle(
        surfaceTint = Color(0xFF0A0A0A),
        surfaceAlpha = 0.38f,
        overlayColor = Color.White,
        overlayAlpha = 0.05f,
        blurRadius = 24.dp,
        useVibrancy = true,
        borderColor = Color.White,
        borderAlpha = 0.12f,
    )

    val MiniPlayerLight = GlassSurfaceStyle(
        surfaceTint = Color.White,
        surfaceAlpha = 0.50f,
        overlayColor = Color.White,
        overlayAlpha = 0.22f,
        blurRadius = 24.dp,
        useVibrancy = true,
        borderColor = Color.White,
        borderAlpha = 0.50f,
    )

    val MiniPlayerPureBlack = GlassSurfaceStyle(
        surfaceTint = Color.Black,
        surfaceAlpha = 0.60f,
        overlayColor = Color.White,
        overlayAlpha = 0.03f,
        blurRadius = 24.dp,
        useVibrancy = true,
        borderColor = Color.White,
        borderAlpha = 0.08f,
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
