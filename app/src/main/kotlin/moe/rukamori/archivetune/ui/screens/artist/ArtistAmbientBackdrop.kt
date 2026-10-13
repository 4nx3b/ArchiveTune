/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.artist

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.canvas.models.CanvasArtwork
import moe.rukamori.archivetune.constants.AlbumCanvasEnabledKey
import moe.rukamori.archivetune.playback.artwork.PlayerPaletteCacheKey
import moe.rukamori.archivetune.playback.artwork.guessArtworkProvider
import moe.rukamori.archivetune.ui.player.CanvasArtworkPlayer
import moe.rukamori.archivetune.ui.player.LocalPlayerLyricsFullScreen
import moe.rukamori.archivetune.ui.player.LocalPlayerSheetOverlayActive
import moe.rukamori.archivetune.ui.theme.BackdropTonePalette
import moe.rukamori.archivetune.ui.theme.PlayerPaletteCache
import moe.rukamori.archivetune.utils.isLowDataModeActive
import moe.rukamori.archivetune.utils.rememberPreference

object ArtistAmbientSnapshotCache {
    private const val MAX_ENTRIES = 24

    private val cache = LinkedHashMap<String, List<Color>>(16, 0.75f, true)

    @Synchronized
    fun get(
        mediaId: String,
        darkTheme: Boolean,
    ): List<Color>? = cache["$mediaId|$darkTheme"]

    @Synchronized
    fun put(
        mediaId: String,
        darkTheme: Boolean,
        colors: List<Color>,
    ) {
        cache["$mediaId|$darkTheme"] = colors
        while (cache.size > MAX_ENTRIES) {
            val eldest = cache.keys.firstOrNull() ?: break
            cache.remove(eldest)
        }
    }
}

object ArtistCanvasArtworkCache {
    private var entry by mutableStateOf<Pair<String, CanvasArtwork>?>(null)

    fun put(
        artistId: String,
        artwork: CanvasArtwork,
    ) {
        entry = artistId to artwork
    }

    fun get(artistId: String): CanvasArtwork? = entry?.takeIf { it.first == artistId }?.second

    fun clear() {
        entry = null
    }
}

@Composable
fun ArtistPageAmbientBackdrop(
    artistId: String,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    listScrolling: Boolean = false,
) {
    val context = LocalContext.current
    val surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
    val darkTheme = surfaceColor.luminance() < 0.5f

    val artistCanvasEnabled by rememberPreference(key = AlbumCanvasEnabledKey, defaultValue = true)
    val lowDataMode = context.isLowDataModeActive()
    val canvasArtwork = ArtistCanvasArtworkCache.get(artistId)
    val heroCanvasPrimaryUrl =
        (canvasArtwork?.animated ?: canvasArtwork?.videoUrl)?.takeIf { artistCanvasEnabled && !lowDataMode }
    val heroCanvasFallbackUrl =
        canvasArtwork?.videoUrl?.takeIf { artistCanvasEnabled && !lowDataMode }
    val heroCanvasPresent =
        !heroCanvasPrimaryUrl.isNullOrBlank() || !heroCanvasFallbackUrl.isNullOrBlank()

    val playerSheetOverlayActive = LocalPlayerSheetOverlayActive.current
    val lyricsFullScreen = LocalPlayerLyricsFullScreen.current
    val canvasSamplingActive =
        heroCanvasPresent && !lyricsFullScreen && !playerSheetOverlayActive && !listScrolling

    val canvasSampleLayer: GraphicsLayer = rememberGraphicsLayer()
    val canvasRecordClock = remember { longArrayOf(0L) }
    var canvasSampleLayerSize by remember { mutableStateOf(IntSize.Zero) }
    var canvasAmbientColors by remember { mutableStateOf<List<Color>?>(null) }

    LaunchedEffect(canvasSamplingActive) {
        if (!canvasSamplingActive) {
            canvasAmbientColors = null
        } else {
            delay(SUBPAGE_CANVAS_SAMPLE_WARMUP_MS)
            while (true) {
                val layerSize = canvasSampleLayerSize
                if (layerSize.width >= 8 && layerSize.height >= 8) {
                    val sampled =
                        try {
                            val snapshot =
                                canvasSampleLayer.toImageBitmap().asAndroidBitmap()
                                    .copy(Bitmap.Config.ARGB_8888, false)
                            if (snapshot.width < 8 || snapshot.height < 8) {
                                null
                            } else {
                                withContext(Dispatchers.Default) {
                                    val bandTop =
                                        (snapshot.height * SUBPAGE_CANVAS_AMBIENT_BAND_START).toInt()
                                            .coerceIn(0, (snapshot.height - 2).coerceAtLeast(1))
                                    val palette =
                                        Palette
                                            .from(snapshot)
                                            .setRegion(0, bandTop, snapshot.width, snapshot.height)
                                            .maximumColorCount(8)
                                            .generate()
                                    val dominant = palette.dominantSwatch
                                    val muted = palette.mutedSwatch
                                    when {
                                        dominant != null && muted != null && muted.rgb != dominant.rgb ->
                                            listOf(Color(dominant.rgb), Color(muted.rgb))

                                        dominant != null -> listOf(Color(dominant.rgb))
                                        muted != null -> listOf(Color(muted.rgb))
                                        else -> null
                                    }
                                }
                            }
                        } catch (_: Throwable) {
                            null
                    }
                    if (!sampled.isNullOrEmpty()) canvasAmbientColors = sampled
                }
                delay(SUBPAGE_CANVAS_SAMPLE_INTERVAL_MS)
            }
        }
    }

    var artistArtworkColors by remember(artistId, darkTheme) {
        mutableStateOf(ArtistAmbientSnapshotCache.get("artist:$artistId", darkTheme))
    }
    LaunchedEffect(artistId, artworkUrl, darkTheme) {
        if (artworkUrl.isNullOrBlank()) return@LaunchedEffect
        val extracted =
            extractArtistAmbientColors(
                context = context,
                mediaId = "artist:$artistId",
                artworkUrl = artworkUrl,
                darkTheme = darkTheme,
            )
        if (extracted != null) {
            ArtistAmbientSnapshotCache.put("artist:$artistId", darkTheme, extracted)
        }
        artistArtworkColors = extracted ?: artistArtworkColors
    }

    val ambientSource = canvasAmbientColors ?: artistArtworkColors

    val ambientPalette =
        remember(ambientSource, surfaceColor) {
            BackdropTonePalette.fromColorsLight(
                colors = ambientSource.orEmpty(),
                fallbackColor = surfaceColor.toArgb(),
                darkTheme = darkTheme,
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
                .background(surfaceColor),
    ) {
        if (heroCanvasPresent) {
            Box(
                modifier =
                    Modifier
                        .size(SUBPAGE_CANVAS_SAMPLE_STAGE_SIZE)
                        .onSizeChanged { canvasSampleLayerSize = it }
                        .drawWithContent {
                            if (canvasSamplingActive) {
                                val now = SystemClock.uptimeMillis()
                                if (now - canvasRecordClock[0] >= SUBPAGE_CANVAS_RECORD_INTERVAL_MS) {
                                    canvasRecordClock[0] = now
                                    runCatching {
                                        val recordScale =
                                            (SUBPAGE_CANVAS_SAMPLE_LAYER_MAX_WIDTH_PX.toFloat() / size.width)
                                                .coerceAtMost(1f)
                                        canvasSampleLayer.record(
                                            IntSize(
                                                (size.width * recordScale).toInt().coerceAtLeast(8),
                                                (size.height * recordScale).toInt().coerceAtLeast(8),
                                            ),
                                        ) {
                                            withTransform({ scale(recordScale, recordScale) }) {
                                                this@drawWithContent.drawContent()
                                            }
                                        }
                                    }
                                }
                            }
                            drawContent()
                        },
            ) {
                CanvasArtworkPlayer(
                    primaryUrl = heroCanvasPrimaryUrl,
                    fallbackUrl = heroCanvasFallbackUrl,
                    isPlaying = true,
                    visible = !lyricsFullScreen && !playerSheetOverlayActive,
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                    maxVideoEdgePx = SUBPAGE_CANVAS_SAMPLE_MAX_VIDEO_EDGE_PX,
                    modifier = Modifier.size(SUBPAGE_CANVAS_SAMPLE_STAGE_SIZE),
                )
            }
        }

        Box(
            modifier =
                Modifier
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
}

@Composable
fun AmbientArtworkBackdrop(
    mediaId: String,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
    val darkTheme = surfaceColor.luminance() < 0.5f

    var extractedColors by remember(mediaId, darkTheme) {
        mutableStateOf(ArtistAmbientSnapshotCache.get(mediaId, darkTheme))
    }
    LaunchedEffect(mediaId, artworkUrl, darkTheme) {
        if (artworkUrl.isNullOrBlank()) return@LaunchedEffect
        val extracted =
            extractArtistAmbientColors(
                context = context,
                mediaId = mediaId,
                artworkUrl = artworkUrl,
                darkTheme = darkTheme,
            )
        if (extracted != null) {
            ArtistAmbientSnapshotCache.put(mediaId, darkTheme, extracted)
        }
        extractedColors = extracted ?: extractedColors
    }

    val ambientPalette =
        remember(extractedColors, surfaceColor) {
            BackdropTonePalette.fromColorsLight(
                colors = extractedColors.orEmpty(),
                fallbackColor = surfaceColor.toArgb(),
                darkTheme = darkTheme,
            )
        }
    val animatedTop by animateColorAsState(
        targetValue = ambientPalette.top,
        animationSpec = tween(durationMillis = ARTIST_SHARED_AMBIENT_CROSSFADE_MS),
        label = "ambientArtworkTop",
    )
    val animatedMid by animateColorAsState(
        targetValue = ambientPalette.mid,
        animationSpec = tween(durationMillis = ARTIST_SHARED_AMBIENT_CROSSFADE_MS),
        label = "ambientArtworkMid",
    )
    val animatedBottom by animateColorAsState(
        targetValue = ambientPalette.bottom,
        animationSpec = tween(durationMillis = ARTIST_SHARED_AMBIENT_CROSSFADE_MS),
        label = "ambientArtworkBottom",
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

private val SUBPAGE_CANVAS_SAMPLE_STAGE_SIZE = 128.dp
private const val SUBPAGE_CANVAS_SAMPLE_MAX_VIDEO_EDGE_PX = 256
private const val SUBPAGE_CANVAS_SAMPLE_LAYER_MAX_WIDTH_PX = 128
private const val SUBPAGE_CANVAS_RECORD_INTERVAL_MS = 120L
private const val SUBPAGE_CANVAS_SAMPLE_INTERVAL_MS = 350L
private const val SUBPAGE_CANVAS_SAMPLE_WARMUP_MS = 280L
private const val SUBPAGE_CANVAS_AMBIENT_BAND_START = 0.62f

internal const val ARTIST_SHARED_AMBIENT_MODE = "ARTIST_AMBIENT"
internal const val ARTIST_SHARED_AMBIENT_CROSSFADE_MS = 1200

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
