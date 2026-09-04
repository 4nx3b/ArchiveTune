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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.source.ShuffleOrder
import coil3.compose.AsyncImage
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import moe.rukamori.archivetune.LocalDownloadUtil
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.extensions.move
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.playback.ExoDownloadService
import moe.rukamori.archivetune.ui.component.BottomSheetPageState
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.MenuState
import moe.rukamori.archivetune.ui.player.rememberMeshPalette
import moe.rukamori.archivetune.ui.utils.highRes
import androidx.compose.foundation.layout.heightIn
import androidx.navigation.NavController

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
    onSeek: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current

    val isDark = isSystemInDarkTheme()
    val contentColor = if (isDark) Color.White else Color(0xFF1C1B1F)
    val contentSecondary = if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF1C1B1F).copy(alpha = 0.6f)

    // ── Playback substrate: queue, like state, lyrics, downloads ──────────
    val queueWindows by playerConnection.queueWindows.collectAsStateWithLifecycle()
    val currentWindowIndex by playerConnection.currentWindowIndex.collectAsStateWithLifecycle()
    val currentSong by playerConnection.currentSong.collectAsStateWithLifecycle(initialValue = null)
    val currentLyricsEntity by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsStateWithLifecycle()
    val repeatMode by playerConnection.repeatMode.collectAsStateWithLifecycle()
    val download by LocalDownloadUtil.current
        .getDownload(mediaMetadata.id)
        .collectAsStateWithLifecycle(initialValue = null)

    val artUrl = remember(mediaMetadata.id, mediaMetadata.thumbnailUrl) { mediaMetadata.thumbnailUrl?.highRes() }
    val palette = rememberMeshPalette(artUrl)
    val playerBackgroundColor = palette.colors.firstOrNull() ?: Color(0xFF202022)

    val dynamicAccentColor =
        remember(playerBackgroundColor, isDark) {
            val hsl = FloatArray(3)
            androidx.core.graphics.ColorUtils.colorToHSL(playerBackgroundColor.toArgb(), hsl)
            if (hsl[1] < 0.08f) {
                // Monochromatic / Grayscale
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

    // ── Lyrics mode ────────────────────────────────────────────────────────
    // SpatialFlow's FullPlayerScreen holds this in the shared ViewModel; here
    // it is local state that survives recompositions and re-opens.
    var lyricsModeEnabled by rememberSaveable(mediaMetadata.id) { mutableStateOf(false) }
    val syncedLyrics =
        remember(currentLyricsEntity?.lyrics) {
            val text = currentLyricsEntity?.lyrics
            if (text.isNullOrBlank()) {
                null
            } else {
                // Word-synced fix (2026-09-05): plain parseLyrics() only understands line-synced
                // LRC — it STRIPS the inline word timings and never dispatches TTML, so a
                // word-timed track degraded to line-level highlighting here (the "word synced
                // lyrics don't work correctly in SpatialFlow player" report). The same dispatch
                // SimpMusicLyrics/LyricsEnhanced use: TTML through parseTtml (which keeps the
                // per-word spans the karaoke renderer erases with), everything else through
                // parseLyrics.
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

    // ── Queue drawer + sleep timer state (SpatialFlow's VM state, local) ──
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

    // Unify BackHandler to collapse the sliding queue drawer / lyrics first
    // (SpatialFlow's exact priority: lyrics, then queue).
    BackHandler(enabled = lyricsModeEnabled || queueExpanded) {
        if (lyricsModeEnabled) {
            lyricsModeEnabled = false
        } else if (queueExpanded) {
            queueExpanded = false
        }
    }

    // ── Music haptics (SpatialFlow's PlayerHapticManager, Visualizer-fed) ──
    val musicHaptics =
        remember(context) {
            SpatialFlowMusicHaptics(context, SpatialFlowHapticEngine(context))
        }
    var hapticsEnabled by remember { mutableStateOf(musicHaptics.isEngineEnabled()) }
    DisposableEffect(view, musicHaptics) {
        musicHaptics.engine.attachView(view)
        onDispose {
            musicHaptics.releaseVisualizer()
            musicHaptics.engine.detachView()
        }
    }

    // Modern Compose-way of handling audio recording permission (the
    // Visualizer tap requires it, exactly as SpatialFlow's haptics chip does).
    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            if (isGranted) {
                musicHaptics.setEnabled(true)
                hapticsEnabled = true
            }
        }

    // Attach the haptics tap to the live audio session while enabled.
    LaunchedEffect(hapticsEnabled) {
        if (hapticsEnabled) {
            val sessionId = runCatching { playerConnection.localPlayer.audioSessionId }.getOrDefault(0)
            musicHaptics.attachToAudioSession(sessionId)
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(backgroundBrush),
    ) {
        // ── Blurred-artwork backdrop ──────────────────────────────────────────
        // The reference SpatialFlow build washes the player in a blurred copy of
        // the current artwork under a vertical darkening scrim — lighter and more
        // saturated at the top, darker at the bottom. The palette surface below
        // stays as the base so a song without artwork still gets a themed screen.
        SpatialFlowBlurredBackdrop(
            artUrl = artUrl,
            modifier = Modifier.matchParentSize(),
        )

        // The whole style renders in SpatialFlow's own Google Sans Flex
        // (ROND 100%) typography — same metrics as the original app's Type.kt.
        // Colors and shapes still come from the ambient theme.
        MaterialTheme(typography = SpatialFlowTypography) {
                val configuration = LocalConfiguration.current
                val screenWidth = configuration.screenWidthDp.dp
                val albumArtSize = screenWidth * 0.9f

                var lyricsButtonCenterInRoot by remember { mutableStateOf<Offset?>(null) }
                val lyricsRevealProgress by animateFloatAsState(
                    targetValue = if (lyricsModeEnabled) 1f else 0f,
                    animationSpec = tween(durationMillis = 340, easing = FastOutSlowInEasing),
                    label = "LyricsCircularReveal",
                )

                // Tie the visibility/readiness directly to the lyricsRevealProgress animation state
                val lyricsContentReady = lyricsRevealProgress > 0.8f

                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(top = LocalStableSystemBarsTopPadding.current)
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                // Header Row (Nav controls + collapse) - Symmetric centering
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

                // Flexible header-to-artwork gap: the artwork sits clear of the
                // header and the whole column stretches to fill the screen, exactly
                // as the reference build does — no dead band under the controls.
                Spacer(
                    modifier =
                        Modifier
                            .heightIn(min = 12.dp)
                            .weight(0.32f),
                )

                // Artwork pager over the real queue — swipe to change song
                SpatialFlowArtworkPager(
                    mediaMetadata = mediaMetadata,
                    queueWindows = queueWindows,
                    currentWindowIndex = currentWindowIndex,
                    userScrollEnabled = !lyricsModeEnabled && !queueExpanded,
                    artUrl = artUrl,
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

                // Metadata row: title/artist
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
                            style = MaterialTheme.typography.headlineMediumEmphasized,
                            fontWeight = FontWeight.Bold,
                            color = contentColor,
                            maxLines = 1,
                            modifier = Modifier.basicMarqueeWithFadedEdges(),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = mediaMetadata.artists.joinToString { it.name },
                            style = MaterialTheme.typography.bodyMedium,
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

                // Premium YT Music style horizontal control chips row
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
                        // ArchiveTune has no persistent dislike; the trailing half
                        // clears the like (the real unlike path) so the split
                        // chip's affordance stays a real action.
                        onDislikeClick = {
                            if (currentSong?.song?.liked == true) playerConnection.toggleLike()
                        },
                        contentColor = contentColor,
                        accentColor = dynamicAccentColor,
                        isDark = isDark,
                    )

                    // Interactive Music Haptics Chip
                    PillChip(
                        icon = painterResource(id = R.drawable.spatialflow_ic_haptic),
                        label = "Music Haptics",
                        isSelected = hapticsEnabled,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val hasPermission =
                                androidx.core.content.ContextCompat.checkSelfPermission(
                                    context,
                                    android.Manifest.permission.RECORD_AUDIO,
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                            if (!hasPermission) {
                                permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            } else {
                                val next = !hapticsEnabled
                                musicHaptics.setEnabled(next)
                                hapticsEnabled = next
                            }
                        },
                        contentColor = contentColor,
                        accentColor = dynamicAccentColor,
                        isDark = isDark,
                    )

                    // Interactive Lyrics Chip
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
                            download?.percentDownloaded?.roundToInt()
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
                            when (download?.state) {
                                Download.STATE_COMPLETED, Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
                                    DownloadService.sendRemoveDownload(
                                        context,
                                        ExoDownloadService::class.java,
                                        mediaMetadata.id,
                                        false,
                                    )
                                }

                                else -> {
                                    // The exact start-download branch the song
                                    // overflow menu uses.
                                    val dl = download
                                    if (dl != null && dl.state != Download.STATE_COMPLETED) {
                                        DownloadService.sendRemoveDownload(
                                            context,
                                            ExoDownloadService::class.java,
                                            mediaMetadata.id,
                                            false,
                                        )
                                    }
                                    val downloadRequest =
                                        DownloadRequest
                                            .Builder(mediaMetadata.id, mediaMetadata.id.toUri())
                                            .setCustomCacheKey(mediaMetadata.id)
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

                // The reference build leaves a generous stretch of background
                // between the chip row and the seek bar — this flexible gap, with the
                // header gap above, is what makes the column fill the screen height
                // and keeps the transport anchored to the bottom.
                Spacer(
                    modifier =
                        Modifier
                            .heightIn(min = 24.dp)
                            .weight(0.68f),
                )

                // Premium Wavy Seek Bar (Isolated) — with the centered codec badge
                // (the reference's "AAC" chip) between the two time labels.
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

                // M3 Expressive transport: three custom buttons in a ButtonGroup
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

                // Swipe Up / Click Chevron Up Indicator to expand Queue
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
                    revealProgressProvider = { lyricsRevealProgress },
                    revealCenterProvider = { lyricsButtonCenterInRoot },
                    contentColor = contentColor,
                    contentSecondary = contentSecondary,
                    onSeekTo = onSeek,
                    onDismiss = { lyricsModeEnabled = false },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // ── CUSTOM EMBEDDED SLIDING PLAY QUEUE ─────────────────────────────
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
                    // OFF -> ALL -> ONE -> OFF
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

            // ── Standalone Sleep Timer Bottom Sheet ────────────────────────────
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
        } // close the SpatialFlowTypography MaterialTheme scope
    }
}

/**
 * The queue-backed artwork pager — SpatialFlow's ArtworkPager over
 * ArchiveTune's real queue windows: swipe to the next/previous song, the page
 * follows external queue changes, 16dp rounded corners + 16dp elevation.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpatialFlowArtworkPager(
    mediaMetadata: MediaMetadata,
    queueWindows: List<androidx.media3.common.Timeline.Window>,
    currentWindowIndex: Int,
    userScrollEnabled: Boolean,
    artUrl: String?,
    cornerRadius: androidx.compose.ui.unit.Dp,
    shadowElevation: androidx.compose.ui.unit.Dp,
    onPlaySongAtWindow: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState =
        rememberPagerState(initialPage = currentWindowIndex.coerceAtLeast(0)) {
            queueWindows.size.coerceAtLeast(1)
        }

    // Sync Pager Page when the active song changes externally
    LaunchedEffect(currentWindowIndex) {
        if (currentWindowIndex >= 0 &&
            currentWindowIndex < pagerState.pageCount &&
            pagerState.currentPage != currentWindowIndex
        ) {
            pagerState.animateScrollToPage(currentWindowIndex)
        }
    }

    // Sync the queue when swiped in the Pager (only when settled, to avoid
    // race conditions — SpatialFlow's exact guard).
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
