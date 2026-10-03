/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import moe.rukamori.archivetune.R

enum class LyricsShareAspectRatio(
    @StringRes val labelRes: Int,
    val exportWidth: Int,
    val exportHeight: Int,
) {
    Square(
        labelRes = R.string.lyrics_share_layout_square,
        exportWidth = 3072,
        exportHeight = 3072,
    ),
    Portrait(
        labelRes = R.string.lyrics_share_layout_portrait,
        exportWidth = 3072,
        exportHeight = 3840,
    ),
    Story(
        labelRes = R.string.lyrics_share_layout_story,
        exportWidth = 2160,
        exportHeight = 3840,
    ),
    ;

    val previewAspectRatio: Float
        get() = exportWidth.toFloat() / exportHeight.toFloat()
}

@Immutable
data class LyricsShareImageOptions(
    val aspectRatio: LyricsShareAspectRatio = LyricsShareAspectRatio.Square,

    val style: LyricsShareStyle = LyricsShareStyle.LIQUID_GLASS,

    val blurRadius: Float = 24f,

    val dimAmount: Float = 0.45f,

    val liquidyAmount: Float = 0.35f,

    val refractionAmount: Float = 0.45f,

    val glassOpacity: Float = 0.55f,
    val showArtwork: Boolean = true,

    val vinylMode: Boolean = false,
) {
    val sanitizedBlurRadius: Float
        get() = blurRadius.coerceIn(0f, 48f)

    val sanitizedDimAmount: Float
        get() = dimAmount.coerceIn(0f, 1f)

    val sanitizedLiquidyAmount: Float
        get() = liquidyAmount.coerceIn(0f, 1f)

    val sanitizedRefractionAmount: Float
        get() = refractionAmount.coerceIn(0f, 1f)

    val sanitizedGlassOpacity: Float
        get() = glassOpacity.coerceIn(0f, 1f)
}

enum class LyricsShareStyle(
    @StringRes val labelRes: Int,
) {
    LIQUID_GLASS(R.string.lyrics_share_style_liquid_glass),
    FROSTED_DARK(R.string.lyrics_share_style_frosted_dark),
    FROSTED_LIGHT(R.string.lyrics_share_style_frosted_light),
    CLEAR_GLASS(R.string.lyrics_share_style_clear_glass),
    DEEP_BLUR(R.string.lyrics_share_style_deep_blur),
    VIVID_GLOW(R.string.lyrics_share_style_vivid_glow),
}

@Immutable
data class LyricsSharePayload(
    val lyricsText: String,
    val songTitle: String,
    val artists: String,
)
