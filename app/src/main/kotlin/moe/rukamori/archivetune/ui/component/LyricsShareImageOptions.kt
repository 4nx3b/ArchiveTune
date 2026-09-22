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
    // Max-resolution exports: the square card renders at 3072 internally so
    // shared images carry zero visible quality loss on every platform — text
    // and artwork are rasterized at native canvas resolution and saved as
    // lossless PNG.
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
    /** Blur intensity behind the glass (px-equivalent at export scale). */
    val blurRadius: Float = 24f,
    /** How dark the ambient background behind the card is (0..1). */
    val dimAmount: Float = 0.45f,
    /** Liquid displacement: the wavy "liquid" flow of the glass (0..1). */
    val liquidyAmount: Float = 0.35f,
    /** Edge refraction: how strongly the glass bends light near its rim (0..1). */
    val refractionAmount: Float = 0.45f,
    /** Opacity of the frosted-glass card fill (0..1). */
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

@Immutable
data class LyricsSharePayload(
    val lyricsText: String,
    val songTitle: String,
    val artists: String,
)
