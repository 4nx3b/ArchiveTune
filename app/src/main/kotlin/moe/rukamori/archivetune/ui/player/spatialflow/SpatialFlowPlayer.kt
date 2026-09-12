/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * SpatialFlow player style.
 *
 * A port of SpatialFlow's FullPlayer (github.com/MythicalSHUB/SpatialFlow,
 * GPL-3.0, ui/player/FullPlayer.kt) as a fully self-contained player style:
 * its layout, icons, behaviour, dimensions and component set are
 * SpatialFlow's own — the "NOW PLAYING" header, the 0.9-screen artwork pager,
 * the marquee metadata row, the horizontally-scrolling pill-chip row (split
 * like/dislike, Music Haptics, Lyrics, Share, Download), the premium wavy
 * seek bar, the M3 Expressive ButtonGroup transport with animated corners,
 * the swipe-up queue handle, the circular-reveal lyrics overlay, the embedded
 * sliding queue drawer and the sleep-timer sheet. It deliberately shares NO
 * components with the app's other player styles; what it shares is the app's
 * one playback substrate (PlayerConnection queue, like state, lyrics store,
 * download manager) — the same self-containment rule BitChord/TikTok/SimpMusic
 * follow.
 */

package moe.rukamori.archivetune.ui.player.spatialflow

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.offline.Download
import androidx.media3.ui.AspectRatioFrameLayout
import moe.rukamori.archivetune.ui.player.CanvasArtworkPlayer
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.source.ShuffleOrder
import coil3.compose.AsyncImage
import kotlin.math.roundToInt
import moe.rukamori.archivetune.LocalDownloadUtil
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.extensions.move
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.playback.ExoDownloadService
import moe.rukamori.archivetune.playback.MusicHapticsSettings
import moe.rukamori.archivetune.ui.component.BottomSheetPageState
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.MenuState
import moe.rukamori.archivetune.ui.player.rememberMeshPalette
import moe.rukamori.archivetune.ui.utils.highRes
import androidx.compose.foundation.layout.heightIn
import androidx.navigation.NavController


// Blurred canvas backdrop (behind the controls) — Apple Music player recipe.
//
// The frosted twin renders at 1/6 of the player with a 12dp blur on that
// small surface (72/6), upscaled 6x (plus a 10% overscan to hide the blur's
// edge falloff) by the wrapping graphics layer — the blur never processes
// more than a sixth of the pixels, and the decode is capped at 480px since
// the blur cannot resolve anything finer anyway.
private const val SfCanvasBackdropUpscale = 6f
private const val SfCanvasBackdropOverscan = 1.10f
private val SfCanvasBackdropBlurRadius = 72.dp
private const val SfCanvasBackdropMaxVideoEdgePx = 480

// Where the sharp full-bleed canvas starts dissolving into the frosted
// continuation (fractions of the player height). The reference keeps the
// video sharp down to ~60-65% (the song-title level) and blends the last
// stretch; the control dock sits in the frosted area below.
private const val SfSharpCanvasFadeStart = 0.50f
private const val SfSharpCanvasFadeEnd = 0.65f

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun SpatialFlowPlayerContent(
    mediaMetadata: MediaMetadata,
    isPlaying: Boolean,
    isLoading: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    position: Long,
    duration: Long,
    playerConnection: PlayerConnection,
    navController: NavController,
    state: BottomSheetState,
    menuState: MenuState,
    bottomSheetPageState: BottomSheetPageState,
    currentFormat: FormatEntity?,
    positionProvider: () -> Long,
    canvasPrimaryUrl: String? = null,
    canvasFallbackUrl: String? = null,
    onSeek: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val isDark = isSystemInDarkTheme()
    val contentColor = if (isDark) Color.White else Color(0xFF1C1B1F)
    val contentSecondary = if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF1C1B1F).copy(alpha = 0.6f)

    val queueWindows by playerConnection.queueWindows.collectAsStateWithLifecycle()
    val currentWindowIndex by playerConnection.currentWindowIndex.collectAsStateWithLifecycle()
    val currentSong by playerConnection.currentSong.collectAsStateWithLifecycle(initialValue = null)
    val currentLyricsEntity by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsStateWithLifecycle()
    val repeatMode by playerConnection.repeatMode.collectAsStateWithLifecycle()
    val downloadUtil = LocalDownloadUtil.current
    val download by downloadUtil
        .getDownload(mediaMetadata.id)
        .collectAsStateWithLifecycle(initialValue = null)
    val fetchProgressMap by moe.rukamori.archivetune.playback.DownloadFetchProgress.flow
        .collectAsStateWithLifecycle()

    val artUrl = remember(mediaMetadata.id, mediaMetadata.thumbnailUrl) { mediaMetadata.thumbnailUrl?.highRes() }
    val palette = rememberMeshPalette(artUrl)
    val playerBackgroundColor = palette.colors.firstOrNull() ?: Color(0xFF202022)

    val dynamicAccentColor =
        remember(playerBackgroundColor, isDark) {
            val hsl = FloatArray(3)
            androidx.core.graphics.ColorUtils.colorToHSL(playerBackgroundColor.toArgb(), hsl)
            if (hsl[1] < 0.08f) {

                if (isDark) Color.White else Color(0xFF1C1B1F)
            } else {
                if (isDark) {
                    playerBackgroundColor
                } else {
                    hsl[2] = hsl[2].coerceAtMost(0.45f)
                    hsl[1] = hsl[1].coerceAtLeast(0.6f)
                    Color(androidx.core.graphics.ColorUtils.HSLToColor(hsl))
                }
            }
        }

    val backgroundBrush =
        remember(playerBackgroundColor, isDark) {
            val finalColor =
                deriveArtworkSurfaceColor(
                    sourceColor = playerBackgroundColor,
                    isDark = isDark,
                    darkLightness = 0.155f,
                    lightLightness = 0.835f,
                    darkSaturationRange = 0.32f..0.54f,
                    lightSaturationRange = 0.30f..0.48f,
                )
            SolidColor(finalColor)
        }

    val lyricsBackgroundBrush =
        remember(playerBackgroundColor, isDark) {
            val finalColor =
                deriveArtworkSurfaceColor(
                    sourceColor = playerBackgroundColor,
                    isDark = isDark,
                    darkLightness = 0.145f,
                    lightLightness = 0.825f,
                    darkSaturationRange = 0.32f..0.54f,
                    lightSaturationRange = 0.30f..0.48f,
                )
            SolidColor(finalColor)
        }

    var lyricsModeEnabled by rememberSaveable(mediaMetadata.id) { mutableStateOf(false) }
    val syncedLyrics =
        remember(currentLyricsEntity?.lyrics) {
            val text = currentLyricsEntity?.lyrics
            if (text.isNullOrBlank()) {
                null
            } else {

                runCatching {
                    if (LyricsUtils.isTtml(text)) {
                        LyricsUtils.parseTtml(text)
                    } else {
                        LyricsUtils.parseLyrics(text)
                    }
                }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
            }
        }
    val plainLyrics =
        remember(currentLyricsEntity?.lyrics, syncedLyrics) {
            if (syncedLyrics != null) null else currentLyricsEntity?.lyrics?.takeIf { it.isNotBlank() }
        }

    var queueExpanded by rememberSaveable { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    val sleepTimer = remember(playerConnection) { playerConnection.service.sleepTimer }
    val sleepTimerMode =
        remember(sleepTimer.triggerTime, sleepTimer.pauseWhenSongEnd) {
            when {
                sleepTimer.pauseWhenSongEnd -> SpatialFlowSleepTimerMode.END_OF_SONG
                sleepTimer.triggerTime != -1L -> SpatialFlowSleepTimerMode.CUSTOM
                else -> SpatialFlowSleepTimerMode.OFF
            }
        }

    BackHandler(enabled = lyricsModeEnabled || queueExpanded) {
        if (lyricsModeEnabled) {
            lyricsModeEnabled = false
        } else if (queueExpanded) {
            queueExpanded = false
        }
    }

    // Music haptics (SpatialFlow port): toggling writes the shared preference;
    // the engine owned by MusicService picks the change up through its prefs
    // listener and the PCM tap inside the audio processor chain starts feeding
    // it — no permission needed (the old Visualizer tap required RECORD_AUDIO,
    // which is why it silently failed when the mic permission was denied).
    var hapticsEnabled by remember { mutableStateOf(MusicHapticsSettings.isEnabled(context)) }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(backgroundBrush),
    ) {
        SpatialFlowBlurredBackdrop(
            artUrl = artUrl,
            modifier = Modifier.matchParentSize(),
        )

        // Full-bleed canvas — the SpatialFlow canvas reference look: when a
        // canvas (Apple Music / Spotify Canvaz loop) is available the video
        // owns the whole screen (RESIZE_MODE_ZOOM, edge to edge). Per the
        // reference, the sharp video plays ABOVE the player controls while a
        // FROSTED continuation of the same canvas (blurred twin + tint) sits
        // behind the lower-third control dock, and the sharp stage dissolves
        // into that frost through a gradient fade instead of a hard edge —
        // "seamlessly blending both canvas and the player controls". The
        // static blurred backdrop stays beneath as the buffering state; the
        // old bounded-in-artwork-slot canvas is gone.
        val canvasActive = !lyricsModeEnabled && (!canvasPrimaryUrl.isNullOrBlank() || !canvasFallbackUrl.isNullOrBlank())
        if (canvasActive) {
            // 1) Frosted twin: the SAME canvas, low-res decoded, laid out at
            // 1/6 of the player with a 72/6 = 12dp blur on the small surface,
            // upscaled back through the scaling layer (Apple Music's cheap
            // blurred-canvas-backdrop recipe — the blur never processes more
            // than a sixth of the pixels).
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            val scale = SfCanvasBackdropOverscan * SfCanvasBackdropUpscale
                            scaleX = scale
                            scaleY = scale
                        },
                contentAlignment = Alignment.Center,
            ) {
                CanvasArtworkPlayer(
                    primaryUrl = canvasPrimaryUrl,
                    fallbackUrl = canvasFallbackUrl,
                    isPlaying = isPlaying,
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                    maxVideoEdgePx = SfCanvasBackdropMaxVideoEdgePx,
                    modifier =
                        Modifier
                            .fillMaxWidth(1f / SfCanvasBackdropUpscale)
                            .fillMaxHeight(1f / SfCanvasBackdropUpscale)
                            .blur(SfCanvasBackdropBlurRadius / SfCanvasBackdropUpscale),
                )
            }

            // 2) Sharp stage: full-bleed video whose bottom dissolves into the
            // frosted twin via a DstIn fade over the last stretch before the
            // control dock (the reference's "gradually blurs and darkens"
            // transition band).
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush =
                                    Brush.verticalGradient(
                                        SfSharpCanvasFadeStart to Color.Black,
                                        SfSharpCanvasFadeEnd to Color.Transparent,
                                    ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
            ) {
                CanvasArtworkPlayer(
                    primaryUrl = canvasPrimaryUrl,
                    fallbackUrl = canvasFallbackUrl,
                    isPlaying = isPlaying,
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                    modifier = Modifier.matchParentSize(),
                )
            }

            // 3) Frost tint: near-transparent over the sharp video, deepening
            // through the fade band into the dark legibility glass of the
            // control dock — the tint layer that turns the blurred twin into
            // a frosted panel behind the controls.
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.16f),
                                SfSharpCanvasFadeStart to Color.Black.copy(alpha = 0.16f),
                                SfSharpCanvasFadeEnd to Color.Black.copy(alpha = 0.32f),
                                0.85f to Color.Black.copy(alpha = 0.52f),
                                1f to Color.Black.copy(alpha = 0.72f),
                            ),
                        ),
            )
        }

        MaterialTheme(typography = SpatialFlowTypography) {
                val configuration = LocalConfiguration.current
                val screenWidth = configuration.screenWidthDp.dp
                val screenHeight = configuration.screenHeightDp.dp
                val albumArtSize = screenWidth * 0.9f

                // SpatialFlow's exact top offset: the artwork slot is centered
                // by formula, not by flexible spacers — `((screenHeight -
                // albumArtSize) / 2f - 220.dp).coerceAtLeast(statusBar + 68.dp)`
                // (FullPlayer.kt). The flexible Spacer weights that used to
                // stand here stretched with leftover space and opened a gap
                // between the metadata block and the seek bar.
                val statusBarTopDp = LocalStableSystemBarsTopPadding.current
                val minTopOffset = statusBarTopDp + 68.dp
                val topOffset = ((screenHeight - albumArtSize) / 2f - 220.dp).coerceAtLeast(minTopOffset)

                var lyricsButtonCenterInRoot by remember { mutableStateOf<Offset?>(null) }
                val lyricsRevealProgress by animateFloatAsState(
                    targetValue = if (lyricsModeEnabled) 1f else 0f,
                    animationSpec = tween(durationMillis = 340, easing = FastOutSlowInEasing),
                    label = "LyricsCircularReveal",
                )

                val lyricsContentReady = lyricsRevealProgress > 0.8f

                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(top = statusBarTopDp)
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { state.collapseSoft() }) {
                        Icon(
                            painter = painterResource(id = R.drawable.spatialflow_ic_keyboard_arrow_down),
                            contentDescription = "Collapse Player",
                            tint = contentColor.copy(alpha = 0.8f),
                            modifier = Modifier.size(28.dp),
                        )
                    }

                    Text(
                        text = "NOW PLAYING",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = contentSecondary,
                    )

                    Spacer(modifier = Modifier.size(48.dp))
                }

                if (canvasActive) {
                    // Canvas reference layout: the video is the whole screen,
                    // so the metadata/controls stack is pushed to the lower
                    // third — no artwork slot, no top offset.
                    Spacer(modifier = Modifier.weight(1f))
                } else {
                    Spacer(modifier = Modifier.height(topOffset - (statusBarTopDp + 68.dp)))
                }

                if (!canvasActive) {
                    SpatialFlowArtworkPager(
                        mediaMetadata = mediaMetadata,
                        queueWindows = queueWindows,
                        currentWindowIndex = currentWindowIndex,
                        userScrollEnabled = !lyricsModeEnabled && !queueExpanded,
                        artUrl = artUrl,
                        isPlaying = isPlaying,
                        cornerRadius = 16.dp,
                        shadowElevation = 16.dp,
                        onPlaySongAtWindow = { windowIndex ->
                            val window = queueWindows.getOrNull(windowIndex) ?: return@SpatialFlowArtworkPager
                            playerConnection.player.seekToDefaultPosition(window.firstPeriodIndex)
                            playerConnection.player.playWhenReady = true
                        },
                        modifier = Modifier.size(albumArtSize),
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                }

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = mediaMetadata.title,
                            // Canvas reference look: HEAVEN/TWXNY use the display
                            // scale (≈45sp heavy) floating over the video; the
                            // artwork layout keeps the repo's own
                            // headlineMediumEmphasized + bodyMedium pair.
                            style =
                                if (canvasActive) {
                                    MaterialTheme.typography.displayMedium
                                } else {
                                    MaterialTheme.typography.headlineMediumEmphasized
                                },
                            fontWeight = FontWeight.Bold,
                            color = contentColor,
                            maxLines = 1,
                            modifier = Modifier.basicMarqueeWithFadedEdges(),
                        )
                        Spacer(modifier = Modifier.height(if (canvasActive) 6.dp else 4.dp))
                        Text(
                            text = mediaMetadata.artists.joinToString { it.name },
                            style = if (canvasActive) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                            color = contentSecondary,
                            maxLines = 1,
                            modifier =
                                Modifier
                                    .basicMarqueeWithFadedEdges()
                                    .clickable {
                                        mediaMetadata.artists.firstOrNull()?.id?.let { artistId ->
                                            state.collapseSoft()
                                            navController.navigate("artist/$artistId")
                                        }
                                    },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .layout { measurable, constraints ->
                                val pad = 20.dp.roundToPx()
                                val placeable =
                                    measurable.measure(
                                        constraints.copy(
                                            maxWidth = constraints.maxWidth + 2 * pad,
                                        ),
                                    )
                                layout(placeable.width - 2 * pad, placeable.height) {
                                    placeable.place(-pad, 0)
                                }
                            }.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(modifier = Modifier.width(12.dp))

                    SplitLikeDislikeChip(
                        isLiked = currentSong?.song?.liked == true,
                        isDisliked = false,
                        likesCount = "Like",
                        onLikeClick = { playerConnection.toggleLike() },

                        onDislikeClick = {
                            if (currentSong?.song?.liked == true) playerConnection.toggleLike()
                        },
                        contentColor = contentColor,
                        accentColor = dynamicAccentColor,
                        isDark = isDark,
                    )

                    PillChip(
                        icon = painterResource(id = R.drawable.spatialflow_ic_haptic),
                        label = "Music Haptics",
                        isSelected = hapticsEnabled,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val next = !hapticsEnabled
                            MusicHapticsSettings.setEnabled(context, next)
                            hapticsEnabled = next
                        },
                        contentColor = contentColor,
                        accentColor = dynamicAccentColor,
                        isDark = isDark,
                    )

                    PillChip(
                        icon = painterResource(id = R.drawable.spatialflow_ic_lyrics),
                        label = "Lyrics",
                        isSelected = lyricsModeEnabled,
                        onClick = { lyricsModeEnabled = true },
                        contentColor = contentColor,
                        accentColor = dynamicAccentColor,
                        isDark = isDark,
                        modifier =
                            Modifier.onGloballyPositioned { coordinates ->
                                val position = coordinates.positionInRoot()
                                lyricsButtonCenterInRoot =
                                    Offset(
                                        x = position.x + coordinates.size.width / 2f,
                                        y = position.y + coordinates.size.height / 2f,
                                    )
                            },
                    )

                    PillChip(
                        icon = painterResource(id = R.drawable.spatialflow_ic_share),
                        label = "Share",
                        onClick = {
                            val shareIntent =
                                Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "https://music.youtube.com/watch?v=${mediaMetadata.id}",
                                    )
                                    type = "text/plain"
                                }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Track"))
                        },
                        contentColor = contentColor,
                        accentColor = dynamicAccentColor,
                        isDark = isDark,
                    )

                    val realDownloaded = download?.state == Download.STATE_COMPLETED
                    val realDownloadProgress =
                        if (download?.state == Download.STATE_DOWNLOADING) {
                            // While PRDownloader buffers the whole stream to its
                            // temp file Media3 reports 0% — combine in the live
                            // fetch progress so the label reflects the network
                            // download instead of a fake "Downloading 0%".
                            // media3's percentDownloaded is a Java float; the
                            // elvis must stay Float (a 0.0 Double fallback
                            // widens the type to Number&Comparable, which no
                            // maxOf overload accepts).
                            val media3Percent = (download?.percentDownloaded ?: 0f).toDouble()
                            val fetchPercent = fetchProgressMap[downloadUtil
                                .currentSourceDownloadTarget(mediaMetadata.id)
                                .key]?.percent ?: 0
                            maxOf(media3Percent, fetchPercent.toDouble()).roundToInt()
                        } else {
                            null
                        }
                    val isDownloading = realDownloadProgress != null

                    val downloadLabel =
                        when {
                            realDownloaded -> "Downloaded"
                            isDownloading -> "Downloading $realDownloadProgress%"
                            else -> "Download"
                        }
                    val downloadIcon: Any =
                        if (realDownloaded) {
                            painterResource(id = R.drawable.spatialflow_ic_downloaded)
                        } else {
                            painterResource(id = R.drawable.spatialflow_ic_download)
                        }
                    PillChip(
                        icon = downloadIcon,
                        label = downloadLabel,
                        isSelected = realDownloaded || isDownloading,
                        progress = if (isDownloading) (realDownloadProgress ?: 0) / 100f else null,
                        onClick = {
                            // Source-scoped request ids ("ytm:<id>", …) everywhere:
                            // remove must target the SAME entry the menus queue,
                            // otherwise a running download can never be cancelled
                            // from here ("infinite download").
                            val target = downloadUtil.currentSourceDownloadTarget(mediaMetadata.id)
                            when (download?.state) {
                                Download.STATE_COMPLETED, Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
                                    DownloadService.sendRemoveDownload(
                                        context,
                                        ExoDownloadService::class.java,
                                        target.key,
                                        false,
                                    )
                                }

                                else -> {

                                    val dl = download
                                    if (dl != null && dl.state != Download.STATE_COMPLETED) {
                                        DownloadService.sendRemoveDownload(
                                            context,
                                            ExoDownloadService::class.java,
                                            dl.request.id,
                                            false,
                                        )
                                    }
                                    val downloadRequest =
                                        DownloadRequest
                                            .Builder(target.key, mediaMetadata.id.toUri())
                                            .setCustomCacheKey(target.key)
                                            .setData(mediaMetadata.title.toByteArray())
                                            .build()
                                    DownloadService.sendAddDownload(
                                        context,
                                        ExoDownloadService::class.java,
                                        downloadRequest,
                                        false,
                                    )
                                }
                            }
                        },
                        contentColor = contentColor,
                        accentColor = dynamicAccentColor,
                        isDark = isDark,
                    )

                    Spacer(modifier = Modifier.width(12.dp))
                }

                // Fixed 24dp, straight from FullPlayer.kt — a weighted spacer
                // here grew with leftover space and produced the "empty space
                // between the seek bar and the song title" report.
                Spacer(modifier = Modifier.height(24.dp))

                WavySliderWithLabels(
                    currentPositionProvider = positionProvider,
                    duration = duration,
                    isPlaying = isPlaying,
                    onSeekTo = { seekMs ->
                        onSeek(seekMs)
                        onSeekFinished()
                    },
                    dynamicAccentColor = dynamicAccentColor,
                    contentColor = contentColor,
                    contentSecondary = contentSecondary,
                    isDark = isDark,
                    currentFormat = currentFormat,
                )

                Spacer(modifier = Modifier.height(16.dp))

                ButtonGroup(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    expandedRatio = 0.3f,
                    overflowIndicator = {},
                ) {
                    val scope = this

                    customItem(
                        buttonGroupContent = {
                            val interactionSource = remember { MutableInteractionSource() }
                            val isPressed by interactionSource.collectIsPressedAsState()
                            val cornerRadius by animateDpAsState(
                                targetValue = if (isPressed) 12.dp else 28.dp,
                                animationSpec =
                                    spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                label = "PrevCorner",
                            )
                            Button(
                                onClick = { playerConnection.seekToPrevious() },
                                modifier =
                                    with(scope) {
                                        Modifier
                                            .animateWidth(interactionSource)
                                            .weight(1f)
                                            .height(76.dp)
                                    },
                                interactionSource = interactionSource,
                                shape = RoundedCornerShape(cornerRadius),
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor = contentColor.copy(alpha = if (isDark) 0.08f else 0.06f),
                                        contentColor = contentColor,
                                    ),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                enabled = canSkipPrevious,
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.spatialflow_ic_skip_previous),
                                        contentDescription = "Previous Song",
                                        modifier = Modifier.size(36.dp),
                                    )
                                }
                            }
                        },
                        menuContent = {},
                    )
                    customItem(
                        buttonGroupContent = {
                            val interactionSource = remember { MutableInteractionSource() }
                            val isPressed by interactionSource.collectIsPressedAsState()
                            val cornerRadius by animateDpAsState(
                                targetValue = if (isPressed) 12.dp else 28.dp,
                                animationSpec =
                                    spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                label = "PlayCorner",
                            )
                            Button(
                                onClick = {
                                    if (isPlaying) {
                                        playerConnection.player.pause()
                                    } else {
                                        playerConnection.player.play()
                                    }
                                },
                                modifier =
                                    with(scope) {
                                        Modifier
                                            .animateWidth(interactionSource)
                                            .weight(1.2f)
                                            .height(76.dp)
                                    },
                                interactionSource = interactionSource,
                                shape = RoundedCornerShape(cornerRadius),
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor = dynamicAccentColor,
                                        contentColor = if (isDark) Color(0xFF1C1B1F) else Color.White,
                                    ),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isLoading && !isPlaying) {
                                        CircularWavyProgressIndicator(modifier = Modifier.size(42.dp))
                                    } else {
                                        Icon(
                                            painter =
                                                painterResource(
                                                    id = if (isPlaying) R.drawable.spatialflow_ic_pause else R.drawable.spatialflow_ic_play,
                                                ),
                                            contentDescription = if (isPlaying) "Pause" else "Play",
                                            modifier = Modifier.size(42.dp),
                                        )
                                    }
                                }
                            }
                        },
                        menuContent = {},
                    )
                    customItem(
                        buttonGroupContent = {
                            val interactionSource = remember { MutableInteractionSource() }
                            val isPressed by interactionSource.collectIsPressedAsState()
                            val cornerRadius by animateDpAsState(
                                targetValue = if (isPressed) 12.dp else 28.dp,
                                animationSpec =
                                    spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                label = "NextCorner",
                            )
                            Button(
                                onClick = { playerConnection.seekToNext() },
                                modifier =
                                    with(scope) {
                                        Modifier
                                            .animateWidth(interactionSource)
                                            .weight(1f)
                                            .height(76.dp)
                                    },
                                interactionSource = interactionSource,
                                shape = RoundedCornerShape(cornerRadius),
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor = contentColor.copy(alpha = if (isDark) 0.08f else 0.06f),
                                        contentColor = contentColor,
                                    ),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                enabled = canSkipNext,
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.spatialflow_ic_skip_next),
                                        contentDescription = "Next Song",
                                        modifier = Modifier.size(36.dp),
                                    )
                                }
                            }
                        },
                        menuContent = {},
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .pointerInput(Unit) {
                                detectVerticalDragGestures { _, dragAmount ->
                                    if (dragAmount < -10f && !queueExpanded && !lyricsModeEnabled) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        queueExpanded = true
                                    }
                                }
                            }.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                queueExpanded = true
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.spatialflow_ic_keyboard_arrow_down),
                        contentDescription = "Open Queue",
                        tint = contentColor.copy(alpha = 0.5f),
                        modifier =
                            Modifier
                                .size(32.dp)
                                .graphicsLayer { rotationZ = 180f },
                    )
                }
            }

            if (lyricsRevealProgress > 0f) {
                SpatialFlowLyricsOverlay(
                    currentSong = mediaMetadata,
                    syncedLyrics = syncedLyrics,
                    plainLyrics = plainLyrics,
                    lyricsProvider = currentLyricsEntity?.providerName,
                    currentPositionProvider = positionProvider,
                    contentReady = lyricsContentReady,
                    backgroundBrush = lyricsBackgroundBrush,
                    movingBlurColors = palette.colors,
                    revealProgressProvider = { lyricsRevealProgress },
                    revealCenterProvider = { lyricsButtonCenterInRoot },
                    contentColor = contentColor,
                    contentSecondary = contentSecondary,
                    onSeekTo = onSeek,
                    onDismiss = { lyricsModeEnabled = false },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            SlidingQueueDrawer(
                isQueueExpanded = queueExpanded,
                onQueueExpandedChange = { queueExpanded = it },
                queue =
                    queueWindows.mapNotNull { window ->
                        (window.mediaItem?.metadata as? MediaMetadata)?.let { metadata ->
                            metadata to window.firstPeriodIndex
                        }
                    },
                currentSongIndex = currentWindowIndex,
                isShuffleEnabled = shuffleModeEnabled,
                repeatMode = repeatMode,
                sleepTimerMode = sleepTimerMode,
                onReorderQueue = { from, to ->
                    if (from == to) return@SlidingQueueDrawer
                    if (!playerConnection.player.shuffleModeEnabled) {
                        playerConnection.player.moveMediaItem(from, to)
                    } else {
                        playerConnection.localPlayer.setShuffleOrder(
                            ShuffleOrder.DefaultShuffleOrder(
                                queueWindows
                                    .map { it.firstPeriodIndex }
                                    .toMutableList()
                                    .move(from, to)
                                    .toIntArray(),
                                System.currentTimeMillis(),
                            ),
                        )
                    }
                },
                onPlaySongAtIndex = { windowIndex ->
                    val window = queueWindows.getOrNull(windowIndex) ?: return@SlidingQueueDrawer
                    playerConnection.player.seekToDefaultPosition(window.firstPeriodIndex)
                    playerConnection.player.playWhenReady = true
                },
                onToggleShuffle = {
                    playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled
                },
                onToggleLoopMode = {

                    playerConnection.player.repeatMode =
                        when (repeatMode) {
                            androidx.media3.common.Player.REPEAT_MODE_OFF ->
                                androidx.media3.common.Player.REPEAT_MODE_ALL

                            androidx.media3.common.Player.REPEAT_MODE_ALL ->
                                androidx.media3.common.Player.REPEAT_MODE_ONE

                            else -> androidx.media3.common.Player.REPEAT_MODE_OFF
                        }
                },
                onShowSleepTimerDialog = { showSleepTimerDialog = true },
                playerBackgroundColor = playerBackgroundColor,
                dynamicAccentColor = dynamicAccentColor,
                isDark = isDark,
            )

            if (showSleepTimerDialog) {
                SpatialFlowSleepTimerSheet(
                    onDismissRequest = { showSleepTimerDialog = false },
                    sleepTimerEndTime = sleepTimer.triggerTime,
                    sleepTimerMode = sleepTimerMode,
                    onStartTimer = { mins -> playerConnection.service.sleepTimer.start(mins) },
                    onCancelTimer = { playerConnection.service.sleepTimer.clear() },
                    onSetEndOfSong = { enable ->
                        if (enable) {
                            playerConnection.service.sleepTimer.start(-1)
                        } else {
                            playerConnection.service.sleepTimer.clear()
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpatialFlowArtworkPager(
    mediaMetadata: MediaMetadata,
    queueWindows: List<androidx.media3.common.Timeline.Window>,
    currentWindowIndex: Int,
    userScrollEnabled: Boolean,
    artUrl: String?,
    isPlaying: Boolean,
    cornerRadius: androidx.compose.ui.unit.Dp,
    shadowElevation: androidx.compose.ui.unit.Dp,
    onPlaySongAtWindow: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState =
        rememberPagerState(initialPage = currentWindowIndex.coerceAtLeast(0)) {
            queueWindows.size.coerceAtLeast(1)
        }

    LaunchedEffect(currentWindowIndex) {
        if (currentWindowIndex >= 0 &&
            currentWindowIndex < pagerState.pageCount &&
            pagerState.currentPage != currentWindowIndex
        ) {
            pagerState.animateScrollToPage(currentWindowIndex)
        }
    }

    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress &&
            currentWindowIndex >= 0 &&
            pagerState.currentPage != currentWindowIndex
        ) {
            onPlaySongAtWindow(pagerState.currentPage)
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = userScrollEnabled,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val pageMetadata =
                if (page == currentWindowIndex) {
                    mediaMetadata
                } else {
                    queueWindows.getOrNull(page)?.mediaItem?.metadata ?: mediaMetadata
                }
            val pageArtUrl = if (page == currentWindowIndex) artUrl else pageMetadata.thumbnailUrl

            var isError by remember(pageArtUrl) { mutableStateOf(false) }

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .shadow(shadowElevation, RoundedCornerShape(cornerRadius))
                        .clip(RoundedCornerShape(cornerRadius)),
                contentAlignment = Alignment.Center,
            ) {
                if (!pageArtUrl.isNullOrBlank() && !isError) {
                    AsyncImage(
                        model = pageArtUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onError = { isError = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        colors =
                                            listOf(
                                                MaterialTheme.colorScheme.surfaceVariant,
                                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                            ),
                                    ),
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.spatialflow_ic_music_note),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(96.dp),
                        )
                    }
                }
            }
        }
    }
}
