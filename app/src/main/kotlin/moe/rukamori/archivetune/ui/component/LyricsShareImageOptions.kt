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
    // High-resolution exports: the square card renders at 2048 internally so
    // shared images stay crisp on every social platform.
    Square(
        labelRes = R.string.lyrics_share_layout_square,
        exportWidth = 2048,
        exportHeight = 2048,
    ),
    Portrait(
        labelRes = R.string.lyrics_share_layout_portrait,
        exportWidth = 2048,
        exportHeight = 2560,
    ),
    Story(
        labelRes = R.string.lyrics_share_layout_story,
        exportWidth = 1440,
        exportHeight = 2560,
    ),
    ;

    val previewAspectRatio: Float
        get() = exportWidth.toFloat() / exportHeight.toFloat()
}

@Immutable
data class LyricsShareImageOptions(
    val aspectRatio: LyricsShareAspectRatio = LyricsShareAspectRatio.Square,
    val blurRadius: Float = 24f,
    val showArtwork: Boolean = true,

    val vinylMode: Boolean = false,
) {
    val sanitizedBlurRadius: Float
        get() = blurRadius.coerceIn(0f, 48f)
}

@Immutable
data class LyricsSharePayload(
    val lyricsText: String,
    val songTitle: String,
    val artists: String,
)
