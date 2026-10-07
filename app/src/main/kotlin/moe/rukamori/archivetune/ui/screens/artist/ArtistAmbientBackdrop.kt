/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.artist

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.luminance
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.playback.artwork.PlayerPaletteCacheKey
import moe.rukamori.archivetune.playback.artwork.guessArtworkProvider
import moe.rukamori.archivetune.ui.theme.BackdropTonePalette
import moe.rukamori.archivetune.ui.theme.PlayerPaletteCache

/**
 * Ambient backdrop for the artist SUB-pages (all albums / songs / items —
 * 2026-10-08: "In artist page if I go to all albums or any other page it
 * should also have the backdrop color extracted from the artists profile
 * picture"): the same bottom-band palette extraction + tone-mapped vertical
 * gradient the main artist page runs, factored out so every artist route
 * shares one implementation (and one palette cache mode).
 */
@Composable
fun ArtistAmbientBackdrop(
    artistId: String?,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
    val darkTheme = surfaceColor.luminance() < 0.5f

    var artistArtworkColors by remember { mutableStateOf<List<Color>?>(null) }
    LaunchedEffect(artistId, artworkUrl, darkTheme) {
        artistArtworkColors =
            extractArtistAmbientColors(
                context = context,
                mediaId = "artist:${artistId.orEmpty()}",
                artworkUrl = artworkUrl,
                darkTheme = darkTheme,
            )
    }

    val ambientPalette =
        remember(artistArtworkColors, surfaceColor) {
            BackdropTonePalette.fromColorsLight(
                colors = artistArtworkColors.orEmpty(),
                fallbackColor = surfaceColor.toArgb(),
            )
        }
    val animatedTop by animateColorAsState(
        targetValue = ambientPalette.top,
        animationSpec = tween(durationMillis = ARTIST_SHARED_AMBIENT_CROSSFADE_MS),
        label = "artistSubAmbientTop",
    )
    val animatedMid by animateColorAsState(
        targetValue = ambientPalette.mid,
        animationSpec = tween(durationMillis = ARTIST_SHARED_AMBIENT_CROSSFADE_MS),
        label = "artistSubAmbientMid",
    )
    val animatedBottom by animateColorAsState(
        targetValue = ambientPalette.bottom,
        animationSpec = tween(durationMillis = ARTIST_SHARED_AMBIENT_CROSSFADE_MS),
        label = "artistSubAmbientBottom",
    )

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to animatedTop,
                        0.5f to animatedMid,
                        1f to animatedBottom,
                    ),
                ),
    )
}

internal const val ARTIST_SHARED_AMBIENT_MODE = "ARTIST_AMBIENT"
internal const val ARTIST_SHARED_AMBIENT_CROSSFADE_MS = 1200

/**
 * Same extraction recipe as the main artist page (bottom-band dominant +
 * muted swatches at 64px, cached under the shared ARTIST_AMBIENT mode).
 */
internal suspend fun extractArtistAmbientColors(
    context: Context,
    mediaId: String,
    artworkUrl: String?,
    darkTheme: Boolean,
): List<Color>? {
    if (artworkUrl.isNullOrBlank()) return null
    val cacheKey =
        PlayerPaletteCacheKey(
            mediaId = mediaId,
            provider = guessArtworkProvider(artworkUrl),
            artworkIdentity = artworkUrl,
            backgroundMode = ARTIST_SHARED_AMBIENT_MODE,
            darkTheme = darkTheme,
        )
    PlayerPaletteCache.get(cacheKey)?.let { return it }

    val request =
        ImageRequest
            .Builder(context)
            .data(artworkUrl)
            .memoryCacheKey(artworkUrl)
            .diskCacheKey(artworkUrl)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .size(ARTIST_AMBIENT_EXTRACT_SIZE_PX, ARTIST_AMBIENT_EXTRACT_SIZE_PX)
            .allowHardware(false)
            .build()
    val result =
        try {
            withContext(Dispatchers.IO) { context.imageLoader.execute(request) }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            null
        } ?: return null
    if (result !is SuccessResult) return null
    val bitmap = result.image?.toBitmap() ?: return null

    val bandPalette =
        withContext(Dispatchers.Default) {
            val bandTop =
                (bitmap.height * ARTWORK_AMBIENT_BAND_START).toInt()
                    .coerceIn(0, (bitmap.height - 2).coerceAtLeast(1))
            runCatching {
                Palette
                    .from(bitmap)
                    .setRegion(0, bandTop, bitmap.width, bitmap.height)
                    .maximumColorCount(24)
                    .resizeBitmapArea(2000)
                    .generate()
            }.getOrElse {
                Palette
                    .from(bitmap)
                    .maximumColorCount(24)
                    .resizeBitmapArea(2000)
                    .generate()
            }
        }
    val dominantSwatch = bandPalette.dominantSwatch
    val mutedSwatch = bandPalette.mutedSwatch
    val fallbackColor = if (darkTheme) 0xFF15151A.toInt() else 0xFFF3F3F6.toInt()
    val stops =
        listOfNotNull(
            dominantSwatch?.let { Color(it.rgb) },
            mutedSwatch?.takeIf { mutedSwatch.rgb != dominantSwatch?.rgb }?.let { Color(it.rgb) },
        ).take(2)
            .ifEmpty { listOf(Color(fallbackColor)) }
    PlayerPaletteCache.put(cacheKey, stops)
    return stops
}

private const val ARTIST_AMBIENT_EXTRACT_SIZE_PX = 64
private const val ARTWORK_AMBIENT_BAND_START = 0.62f
