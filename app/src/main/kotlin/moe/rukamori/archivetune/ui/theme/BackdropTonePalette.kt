/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * The immersive-player (V7) "colour gradience" ladder: a dominant colour
 * projected into three value bands — a bright top, a mid body and a deep
 * bottom — that the V7 player paints as the vertical gradient behind its
 * bottom controls. Extracted here so the artist page ambient background can
 * speak exactly the same gradient language (no blur, pure tones).
 */
data class BackdropTonePalette(
    val top: Color,
    val mid: Color,
    val bottom: Color,
) {
    companion object {
        fun fromColors(
            colors: List<Color>,
            fallbackColor: Int,
        ): BackdropTonePalette {
            val dominantColor = colors.firstOrNull()
            val fallback = Color(fallbackColor).backdropTone(valueMin = 0.12f, valueMax = 0.38f)
            val top = dominantColor?.backdropTone(valueMin = 0.20f, valueMax = 0.72f) ?: fallback
            val mid = dominantColor?.backdropTone(valueMin = 0.13f, valueMax = 0.48f) ?: top
            val bottom = dominantColor?.backdropTone(valueMin = 0.08f, valueMax = 0.32f) ?: mid
            return BackdropTonePalette(
                top = top,
                mid = mid,
                bottom = bottom,
            )
        }
    }
}

/** HSV value-banding + gentle saturation lift — the V7 tone derivation. */
fun Color.backdropTone(
    valueMin: Float,
    valueMax: Float,
): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[1] =
        if (hsv[1] < 0.12f) {
            hsv[1].coerceAtMost(0.08f)
        } else {
            (hsv[1] * 1.27f).coerceIn(0f, 1f)
        }
    hsv[2] = hsv[2].coerceIn(valueMin, valueMax)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
