/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * SimpMusic player style.
 *
 * The layout is SimpMusic's default now-playing screen — its `NowPlayingContentSpotify`
 * (https://github.com/maxrave-dev/SimpMusic, GPL-3.0). The thing that makes it that screen, and
 * which the first version of this file missed entirely, is that IT SCROLLS: the artwork, info row,
 * scrubber and transport are one screen-height hero, and below the fold sit three cards — lyrics,
 * artist, and track info. Scroll past the hero and a compact toolbar sticks to the top.
 *
 * The hero's vertical rhythm is measured, not guessed. SimpMusic computes
 *
 *     gap = (screenHeight - topBarHeight - artworkHeight - infoLayoutHeight - 30dp) / 2
 *
 * and spends that gap twice: once above the artwork, once below it, where the current lyric line
 * lives. That is why the artwork sits slightly high with the controls gathered under it rather than
 * floating in the middle of an empty screen. The first version here used a `weight(1f)` artwork and
 * a bottom-anchored control stack, which centred the sleeve in ALL the leftover space and left the
 * dead band the screenshot shows.
 *
 * REWRITTEN, not transliterated. SimpMusic is Compose Multiplatform and this screen is one
 * ~1,700-line composable carrying its own state model (NowPlayingScreenData, ControlState, TimeLine,
 * GenericCastState) and re-running Palette on every adjacent pager page. None of that survives:
 *
 *  - it reads ArchiveTune's PlayerConnection directly, so there is no second state model to keep in
 *    sync with the engine;
 *  - the palette comes from the shared rememberMeshPalette, which caches across every caller, so
 *    swiping a queue back and forth re-extracts nothing;
 *  - the playhead is read through a provider inside draw/derived scopes, so a position tick
 *    repaints instead of recomposing the screen;
 *  - it is split into small composables, so a change to one row does not invalidate the rest.
 *
 * Belongs exclusively to this style, per the self-containment rule; what it shares is the app's
 * playback substrate (the one PlayerConnection, queue, like state and lyrics), deliberately.
 */

package moe.rukamori.archivetune.ui.player.simpmusic

import androidx.activity.compose.BackHandler
import moe.rukamori.archivetune.ui.utils.smoothFadingEdge
import androidx.compose.ui.graphics.luminance
import moe.rukamori.archivetune.ui.player.viewportEdgeFade
import moe.rukamori.archivetune.ui.player.rememberMeshPalette
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import moe.rukamori.archivetune.ui.menu.AddToPlaylistDialog
import moe.rukamori.archivetune.lyrics.LyricsEntry
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.extensions.toMediaItem
import androidx.room.withTransaction
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.SimpMusicLyricsKey
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.MediaInfo
import moe.rukamori.archivetune.lyrics.LyricsUtils.findCurrentLineIndex
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.ui.component.BottomSheetPageState
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.LyricsEnhanced
import moe.rukamori.archivetune.ui.component.MenuState
import moe.rukamori.archivetune.ui.menu.PlayerMenu
import moe.rukamori.archivetune.ui.player.LosslessOrStats
import moe.rukamori.archivetune.ui.player.rememberInlineLyricLines
import moe.rukamori.archivetune.ui.utils.ShowMediaInfo
import moe.rukamori.archivetune.ui.utils.highRes
import moe.rukamori.archivetune.utils.rememberPreference
import java.util.Locale

private val Backdrop = Color(0xFF121212)

private val CardPanel = Color(0xFF212121)

private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")

private const val MAX_SURFACE_LUMINANCE = 0.10f

private fun Color.asSurface(): Color {
    var c = this
    var steps = 0
    while (c.luminance() > MAX_SURFACE_LUMINANCE && steps++ < 16) {
        c = lerp(c, Backdrop, 0.2f)
    }
    return c
}

private val Seed = Color(0xFF8ECAE6)

/**
 * Lyrics inside the 300dp card, not on a full screen. The renderers' own default (26sp) fits about
 * four words in the box; 16sp overcorrected and read as fine print, so this sits between them.
 */
private const val CARD_LYRICS_SIZE_SP = 21f

private val Gutter = 20.dp

private val MinGap = 30.dp

/**
 * Artwork width as a fraction of the screen.
 *
 * SimpMusic sizes its sleeve to the full width minus its gutters, which on a phone is around 90%
 * and reads noticeably bigger than Spotify's — the complaint that prompted this. Spotify's sleeve
 * leaves a clear margin either side; 0.84 matches it and keeps the square from crowding the title
 * row underneath.
 */
private const val ARTWORK_WIDTH_FRACTION = 0.84f

/**
 * The band reserved for the current lyric line, between the artwork and the title row.
 *
 * Two lines of `labelMedium` plus the padding around them. It used to be nothing — the line lived
 * inside the lower gap so the controls could not move when a line arrived — but that capped it at
 * one line, and a long line then marqueed sideways across the player instead of wrapping. Spotify
 * wraps to a second line, so the band is real height now and the artwork gives it up, which is also
 * what Spotify does. Reserved only when the track HAS synced lyrics, so a track without them keeps
 * the larger sleeve; the size therefore changes per track, never per line.
 */
private val LyricBandHeight = 48.dp

/**
 * The SimpMusic style. Parameters mirror the other self-contained styles so Player.kt dispatches
 * every style the same way.
 */
@Composable
fun SimpMusicPlayerContent(
    mediaMetadata: MediaMetadata,
    isPlaying: Boolean,
    isLoading: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    playerConnection: PlayerConnection,
    navController: NavController,
    state: BottomSheetState,
    menuState: MenuState,
    bottomSheetPageState: BottomSheetPageState,
    currentFormat: FormatEntity?,
    onSeek: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    onShowLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val queueWindows by playerConnection.queueWindows.collectAsStateWithLifecycle()
    val currentWindowIndex by playerConnection.currentWindowIndex.collectAsStateWithLifecycle()
    val queueTitle by playerConnection.queueTitle.collectAsStateWithLifecycle()

    val artUrl =
        remember(mediaMetadata.id, mediaMetadata.thumbnailUrl) {
            mediaMetadata.thumbnailUrl?.highRes()
        }
    // SimpMusic ramps ONE colour into the backdrop — dark vibrant, resolving into the ground —
    // rather than blending two palette tones. Ramping to a second palette colour, which is what
    // this did, never resolves into the surface below and reads as a flat two-tone poster.
    // asSurface() stays on top: SimpMusic's own fallback chain can end on a light swatch, and this
    // fork clamps those toward the backdrop so the hero never flashes bright.
    val startColor = rememberSimpMusicWashColor(artUrl).asSurface()

    var mediaInfo by remember(mediaMetadata.id) { mutableStateOf<MediaInfo?>(null) }
    LaunchedEffect(mediaMetadata.id) {
        if (!YOUTUBE_ID.matches(mediaMetadata.id)) return@LaunchedEffect
        mediaInfo = runCatching { YouTube.getMediaInfo(mediaMetadata.id).getOrNull() }.getOrNull()
    }

    // Hoisted out of SimpMusicLyricLine: the layout below has to know whether this track has synced
    // lyrics AT ALL before it can size the artwork, and parsing is cheap and keyed on the text.
    val lyricLines = rememberInlineLyricLines(playerConnection)

    val scrollState = rememberScrollState()

    var hasScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value > 0 }.first { it }
        hasScrolled = true
    }
    // Reopening the player must land on the hero, not two screens down where it was left. Keyed on
    // `isExpandedOrExpanding` rather than `isExpanded` so this fires the moment the collapse starts
    // — by the time the sheet has finished shrinking there is nothing left to hide the jump.
    LaunchedEffect(state.isExpandedOrExpanding) {
        if (!state.isExpandedOrExpanding) scrollState.scrollTo(0)
    }

    var queueOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = queueOpen) { queueOpen = false }

    var lyricsFullscreenOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = lyricsFullscreenOpen) { lyricsFullscreenOpen = false }

    var topBarHeight by remember { mutableStateOf(0.dp) }
    var infoHeight by remember { mutableStateOf(0.dp) }

    MaterialTheme(typography = SimpMusicTypography) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {

        val screenHeight = maxHeight
        // A square as wide as the gutters allow — unless the screen is too SHORT for that square
        // plus the two rows and their minimum gaps, in which case the square gives way rather than
        // the controls sliding off the bottom. SimpMusic sizes the artwork on width alone and
        // clips the controls on a short screen; there is no reason to reproduce that.
        val lyricBand = if (lyricLines.isEmpty()) 0.dp else LyricBandHeight
        val artworkSide =
            (maxWidth * ARTWORK_WIDTH_FRACTION)
                .coerceAtMost(screenHeight - topBarHeight - infoHeight - lyricBand - MinGap * 2)
                .coerceAtLeast(0.dp)
        val gap =
            ((screenHeight - topBarHeight - artworkSide - lyricBand - infoHeight - MinGap) / 2)
                .coerceAtLeast(MinGap)
        val screenHeightPx = with(density) { screenHeight.toPx() }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Backdrop)
                    .simpMusicHeroWash(startColor, screenHeightPx)
                    // Gated on the sheet being expanded, so a drag on the collapsed mini-player is
                    // never eaten by this. Up-drags at the top still reach the sheet through the
                    // nested-scroll connection the caller attached.
                    //
                    // `isExpandedOrExpanding`, NOT `isExpanded`: the latter is exact equality with
                    // the upper bound, so the first pixel of a drag that pulls the sheet down makes
                    // it false and disables this scrollable MID-GESTURE. The drag it owned is
                    // cancelled with it, the sheet stops receiving deltas through onPostScroll, and
                    // onPreFling never runs — leaving the sheet stranded part-way instead of
                    // collapsing. The anchor stays EXPANDED for the whole drag and only flips when
                    // the fling resolves, which is exactly the window this needs to stay alive for.
                    .verticalScroll(scrollState, enabled = state.isExpandedOrExpanding),
        ) {

            Box(modifier = Modifier.fillMaxWidth().height(screenHeight)) {
                SimpMusicArtworkPager(
                    queueWindows = queueWindows,
                    currentWindowIndex = currentWindowIndex,
                    fallback = mediaMetadata,
                    playerConnection = playerConnection,
                    topInset = topBarHeight + gap,
                    side = artworkSide,
                    modifier = Modifier.fillMaxWidth().height(screenHeight),
                )

                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(topBarHeight))
                    Spacer(Modifier.height(gap))

                    Spacer(Modifier.fillMaxWidth().height(artworkSide))

                    // Its own band between the sleeve and the title row, sized by the layout above
                    // and zero-height on a track with no synced lyrics.
                    SimpMusicLyricLine(
                        lines = lyricLines,
                        playerConnection = playerConnection,

                        active = isPlaying && state.isExpanded,
                        modifier = Modifier.fillMaxWidth().height(lyricBand),
                    )

                    Spacer(Modifier.height(gap))

                    Column(
                        modifier =
                            Modifier.onGloballyPositioned {
                                infoHeight = with(density) { it.size.height.toDp() }
                            },
                    ) {
                        SimpMusicTrackInfoRow(
                            mediaMetadata = mediaMetadata,
                            playerConnection = playerConnection,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter),
                        )

                        Spacer(Modifier.height(15.dp))

                        SimpMusicProgressRow(
                            sliderPosition = sliderPosition,
                            position = position,
                            duration = duration,
                            isLoading = isLoading,
                            currentFormat = currentFormat,
                            onSeek = onSeek,
                            onSeekFinished = onSeekFinished,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter),
                        )

                        Spacer(Modifier.height(6.dp))

                        SimpMusicTransportRow(
                            isPlaying = isPlaying,
                            canSkipPrevious = canSkipPrevious,
                            canSkipNext = canSkipNext,
                            playerConnection = playerConnection,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter),
                        )

                        SimpMusicActionRow(
                            mediaMetadata = mediaMetadata,
                            playerConnection = playerConnection,
                            bottomSheetPageState = bottomSheetPageState,
                            onOpenQueue = { queueOpen = true },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter),
                        )
                    }
                }

                SimpMusicTopBar(
                    playlistName = queueTitle ?: mediaMetadata.album?.title ?: "",
                    onCollapse = state::collapseSoft,
                    onMenu = {
                        menuState.show {
                            PlayerMenu(
                                mediaMetadata = mediaMetadata,
                                navController = navController,
                                playerBottomSheetState = state,
                                onShowDetailsDialog = {
                                    bottomSheetPageState.show { ShowMediaInfo(mediaMetadata.id) }
                                },
                                onDismiss = menuState::dismiss,
                            )
                        }
                    },
                    modifier =
                        Modifier.fillMaxWidth().onGloballyPositioned {
                            topBarHeight = with(density) { it.size.height.toDp() }
                        },
                )
            }

            Column(modifier = Modifier.padding(horizontal = Gutter)) {
                SimpMusicLyricsCard(
                    playerConnection = playerConnection,
                    containerColor = startColor,

                    renderLyrics = hasScrolled,
                    onShowLyrics = { lyricsFullscreenOpen = true },
                    modifier = Modifier.padding(top = 10.dp),
                )
                Spacer(Modifier.height(10.dp))
                SimpMusicArtistCard(
                    info = mediaInfo,
                    onOpenArtist = { id -> navController.navigate("artist/$id") },
                )
                Spacer(Modifier.height(10.dp))
                SimpMusicInfoCard(info = mediaInfo, containerColor = startColor)
                Spacer(Modifier.height(10.dp))
                Spacer(
                    Modifier.height(
                        WindowInsets.systemBars.asPaddingValues().calculateBottomPadding(),
                    ),
                )
            }
        }

        val toolbarVisible by remember(screenHeightPx) {
            derivedStateOf { scrollState.value > screenHeightPx * 0.6f }
        }
        AnimatedVisibility(
            visible = toolbarVisible && state.isExpanded,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
        ) {
            SimpMusicStickyToolbar(
                mediaMetadata = mediaMetadata,
                isPlaying = isPlaying,
                playerConnection = playerConnection,
                // The hero's own chevron has scrolled away by the time this appears, so the
                // toolbar has to carry one: without it the only way back is the system gesture.
                onCollapse = state::collapseSoft,
                containerColor = lerp(startColor, Color.Black, 0.18f),
            )
        }

        if (queueOpen) {

            SimpMusicQueueSheet(
                playerConnection = playerConnection,
                navController = navController,
                onDismiss = { queueOpen = false },
            )
        }

        if (lyricsFullscreenOpen) {

            SimpMusicFullscreenLyricsSheet(
                mediaMetadata = mediaMetadata,
                playerConnection = playerConnection,
                navController = navController,
                bottomSheetPageState = bottomSheetPageState,
                color = startColor,
                onDismiss = { lyricsFullscreenOpen = false },
            )
        }
    }
    }
}

private fun Modifier.simpMusicHeroWash(
    start: Color,
    screenHeightPx: Float,
): Modifier =
    this.drawBehind {
        val area = Size(size.width, screenHeightPx)
        drawRect(
            brush =
                // CW135: top-left to bottom-right, SimpMusic's GradientAngle. The far end is the
                // BACKDROP, so the ramp lands on the same colour the fade below and the area past
                // the hero use, and the glow resolves into the surface instead of a colour seam.
                Brush.linearGradient(
                    colors = listOf(start, Backdrop),
                    start = Offset.Zero,
                    end = Offset(size.width, screenHeightPx),
                ),
            size = area,
        )

        drawRect(
            brush =
                Brush.verticalGradient(
                    0.0f to Backdrop.copy(alpha = 0f),
                    0.55f to Backdrop.copy(alpha = 0.45f),
                    0.95f to Backdrop,
                    startY = 0f,
                    endY = screenHeightPx,
                ),
            size = area,
        )
    }

@Composable
private fun SimpMusicTopBar(
    playlistName: String,
    onCollapse: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .padding(top = LocalStableSystemBarsTopPadding.current)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCollapse) {
            Icon(
                painter = painterResource(R.drawable.simpmusic_keyboard_arrow_down),
                contentDescription = stringResource(R.string.collapse),
                tint = Color.White,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.now_playing).uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
            )

            Text(
                text = playlistName,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .basicMarquee(
                            iterations = Int.MAX_VALUE,
                            animationMode = MarqueeAnimationMode.Immediately,
                        ),
            )
        }
        IconButton(onClick = onMenu) {
            Icon(
                painter = painterResource(R.drawable.simpmusic_more_vert),
                contentDescription = stringResource(R.string.more_options),
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun SimpMusicArtworkPager(
    queueWindows: List<Timeline.Window>,
    currentWindowIndex: Int,
    fallback: MediaMetadata,
    playerConnection: PlayerConnection,
    topInset: androidx.compose.ui.unit.Dp,
    side: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    if (queueWindows.isEmpty()) {
        SimpMusicArtwork(fallback, topInset, side, modifier)
        return
    }

    val pagerState =
        rememberPagerState(initialPage = currentWindowIndex.coerceAtLeast(0)) { queueWindows.size }
    var pendingSeek by remember { mutableStateOf<Int?>(null) }
    val liveIndex = rememberUpdatedState(currentWindowIndex)
    val liveQueue = rememberUpdatedState(queueWindows)

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { settled ->
                val index = liveIndex.value
                if (settled != index && settled in liveQueue.value.indices) {
                    pendingSeek = settled
                    when (settled) {
                        index + 1 -> playerConnection.seekToNext()
                        index - 1 -> playerConnection.seekToPrevious()
                        else -> playerConnection.player.seekTo(settled, 0)
                    }
                }
            }
    }

    LaunchedEffect(currentWindowIndex, queueWindows.size) {
        val pending = pendingSeek
        if (pending != null) {
            if (currentWindowIndex == pending) pendingSeek = null
            return@LaunchedEffect
        }
        if (currentWindowIndex !in queueWindows.indices) return@LaunchedEffect
        if (pagerState.currentPage == currentWindowIndex) return@LaunchedEffect
        if (kotlin.math.abs(currentWindowIndex - pagerState.currentPage) == 1) {
            pagerState.animateScrollToPage(currentWindowIndex)
        } else {
            pagerState.scrollToPage(currentWindowIndex)
        }
    }

    HorizontalPager(state = pagerState, beyondViewportPageCount = 1, modifier = modifier) { page ->
        SimpMusicArtwork(
            metadata = queueWindows.getOrNull(page)?.mediaItem?.metadata ?: fallback,
            topInset = topInset,
            side = side,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SimpMusicArtwork(
    metadata: MediaMetadata,
    topInset: androidx.compose.ui.unit.Dp,
    side: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {

    val sleevePalette = rememberMeshPalette(metadata.thumbnailUrl?.highRes())
    val spotColor = sleevePalette.colors.firstOrNull() ?: Color.Black
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(topInset))
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(side)
                    .shadow(
                        elevation = 3.dp,
                        shape = RoundedCornerShape(8.dp),
                        spotColor = spotColor.copy(alpha = 0.6f),
                        ambientColor = Color.Transparent,
                    ),
        ) {
            AsyncImage(
                model = metadata.thumbnailUrl?.highRes(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(3.dp)
                        .clip(RoundedCornerShape(8.dp)),
            )
        }
    }
}

@Composable
private fun SimpMusicLyricLine(
    lines: List<LyricsEntry>,
    playerConnection: PlayerConnection,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    var line by remember(lines) { mutableStateOf("") }

    LaunchedEffect(lines, active) {
        if (lines.isEmpty() || !active) {
            line = ""
            return@LaunchedEffect
        }
        while (true) {
            val index = findCurrentLineIndex(lines, playerConnection.player.currentPosition)
            line = lines.getOrNull(index)?.text.orEmpty()
            delay(200L)
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Crossfade(targetState = line, animationSpec = tween(300), label = "simpMusicLyricLine") { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                // Wraps rather than marquees. A long line used to scroll sideways across the
                // player, which is both harder to read than a second line and unlike every
                // reference player; two lines is what the band above is sized for.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter),
            )
        }
    }
}

@Composable
private fun SimpMusicTrackInfoRow(
    mediaMetadata: MediaMetadata,
    playerConnection: PlayerConnection,
    modifier: Modifier = Modifier,
) {
    val database = LocalDatabase.current
    val currentSong by playerConnection.currentSong.collectAsStateWithLifecycle(initialValue = null)
    val liked = currentSong?.song?.liked == true
    var showPlaylistDialog by rememberSaveable { mutableStateOf(false) }

    AddToPlaylistDialog(
        isVisible = showPlaylistDialog,
        onGetSong = {
            database.withTransaction { insert(mediaMetadata) }
            listOf(mediaMetadata.id)
        },
        onDismiss = { showPlaylistDialog = false },
    )

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            SimpMusicMarqueeText(
                text = mediaMetadata.title,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(Modifier.height(3.dp))
            SimpMusicMarqueeText(
                text = mediaMetadata.artists.joinToString { it.name },
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.66f),
            )
        }
        IconButton(onClick = { showPlaylistDialog = true }, modifier = Modifier.size(36.dp)) {
            Icon(
                painter = painterResource(R.drawable.simpmusic_add_circle_outline),
                contentDescription = stringResource(R.string.add_to_playlist),
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        IconButton(onClick = playerConnection::toggleLike, modifier = Modifier.size(40.dp)) {
            Icon(
                painter =
                    painterResource(
                        if (liked) R.drawable.simpmusic_favorite else R.drawable.simpmusic_favorite_border,
                    ),
                contentDescription = stringResource(R.string.action_like),
                tint = if (liked) MaterialTheme.colorScheme.error else Color.White,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/**
 * One marquee line with the same edge fade every other player style uses.
 *
 * The fade sits on the BOX (the line's viewport), not the Text: the Text scrolls inside it, so a
 * mask on the Text would travel with the glyphs and leave the visible edge hard-clipped — the boxy
 * cut this style had. [viewportEdgeFade] is the shared helper PlayerComponents applies for exactly
 * this, and as there it is only applied while the line actually overflows, because basicMarquee
 * measures its child unbounded so `hasVisualOverflow` never fires.
 */
@Composable
private fun SimpMusicMarqueeText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    color: Color,
) {
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val viewportWidth = remember { mutableStateOf(0) }
    val shouldFade = viewportWidth.value > 0 && (layout.value?.size?.width ?: 0) > viewportWidth.value

    Box(
        modifier =
            (if (shouldFade) Modifier.viewportEdgeFade(24.dp) else Modifier)
                .clipToBounds()
                .onSizeChanged { viewportWidth.value = it.width },
    ) {
        Text(
            text = text,
            fontSize = fontSize,
            fontWeight = fontWeight,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout.value = it },
            modifier =
                Modifier.fillMaxWidth().basicMarquee(
                    iterations = Int.MAX_VALUE,
                    animationMode = MarqueeAnimationMode.Immediately,
                ),
        )
    }
}

/**
 * The scrubber and the two timestamps.
 *
 * SimpMusic's slider, not the stock one: a 5dp track and an 8dp square thumb. The default M3
 * Slider draws a tall pill thumb with a gap either side of it, which is the fat white bar the
 * screenshot showed. Both labels are elapsed and TOTAL, zero-padded — the right-hand one is not a
 * negative remaining count.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpMusicProgressRow(
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    isLoading: Boolean,
    currentFormat: FormatEntity?,
    onSeek: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasDuration = duration > 0L && duration != C.TIME_UNSET
    val safeDuration = if (hasDuration) duration else 1L
    val shown = (sliderPosition ?: position).coerceIn(0L, safeDuration)
    val trackColor = Color.White

    Column(modifier = modifier) {
        Slider(
            value = shown.toFloat() / safeDuration.toFloat(),
            onValueChange = { onSeek((it * safeDuration).toLong()) },
            onValueChangeFinished = onSeekFinished,
            track = { sliderState ->
                SliderDefaults.Track(
                    modifier = Modifier.height(5.dp),
                    enabled = true,
                    sliderState = sliderState,
                    colors =
                        SliderDefaults.colors().copy(
                            thumbColor = trackColor,
                            activeTrackColor = trackColor,

                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                    thumbTrackGapSize = 0.dp,
                    drawTick = { _, _ -> },
                    drawStopIndicator = null,
                )
            },
            thumb = {
                SliderDefaults.Thumb(
                    modifier = Modifier.height(18.dp).width(8.dp).padding(vertical = 4.dp),
                    thumbSize = DpSize(8.dp, 8.dp),
                    interactionSource = remember { MutableInteractionSource() },
                    colors = SliderDefaults.colors().copy(thumbColor = trackColor),
                    enabled = true,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = clockTime(shown),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.weight(1f),
            )

            LosslessOrStats(isLoading = isLoading, format = currentFormat)
            Text(
                text = if (hasDuration) clockTime(duration) else "",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.55f),
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun clockTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    return String.format(Locale.getDefault(), "%02d:%02d", total / 60, total % 60)
}

@Composable
private fun SimpMusicTransportRow(
    isPlaying: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    playerConnection: PlayerConnection,
    modifier: Modifier = Modifier,
) {
    val shuffleEnabled by playerConnection.shuffleModeEnabled.collectAsStateWithLifecycle()
    val repeatMode by playerConnection.repeatMode.collectAsStateWithLifecycle()

    Row(
        modifier = modifier.height(96.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SimpMusicControl(
            cell = 42.dp,
            icon = 32.dp,
            painter = painterResource(R.drawable.simpmusic_shuffle),
            contentDescription = stringResource(R.string.shuffle),
            tint = if (shuffleEnabled) Seed else Color.White,
            onClick = { playerConnection.player.shuffleModeEnabled = !shuffleEnabled },
        )
        SimpMusicControl(
            cell = 52.dp,
            icon = 42.dp,
            painter = painterResource(R.drawable.simpmusic_skip_previous),
            contentDescription = stringResource(R.string.widget_previous),
            tint = Color.White.copy(alpha = if (canSkipPrevious) 1f else 0.4f),
            enabled = canSkipPrevious,
            onClick = playerConnection::seekToPrevious,
        )
        SimpMusicControl(
            cell = 96.dp,
            icon = 72.dp,
            painter =
                painterResource(
                    if (isPlaying) R.drawable.simpmusic_pause_circle else R.drawable.simpmusic_play_circle,
                ),
            contentDescription = stringResource(if (isPlaying) R.string.widget_pause else R.string.play),
            tint = Color.White,
            onClick = { playerConnection.player.togglePlayPause() },
        )
        SimpMusicControl(
            cell = 52.dp,
            icon = 42.dp,
            painter = painterResource(R.drawable.simpmusic_skip_next),
            contentDescription = stringResource(R.string.next),
            tint = Color.White.copy(alpha = if (canSkipNext) 1f else 0.4f),
            enabled = canSkipNext,
            onClick = playerConnection::seekToNext,
        )
        SimpMusicControl(
            cell = 42.dp,
            icon = 32.dp,
            painter =
                painterResource(
                    if (repeatMode == Player.REPEAT_MODE_ONE) {
                        R.drawable.simpmusic_repeat_one
                    } else {
                        R.drawable.simpmusic_repeat
                    },
                ),
            contentDescription =
                stringResource(
                    when (repeatMode) {
                        Player.REPEAT_MODE_ONE -> R.string.repeat_mode_one
                        Player.REPEAT_MODE_ALL -> R.string.repeat_mode_all
                        else -> R.string.repeat_mode_off
                    },
                ),
            tint = if (repeatMode == Player.REPEAT_MODE_OFF) Color.White else Seed,
            onClick = {
                playerConnection.player.repeatMode =
                    when (repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
            },
        )
    }
}

@Composable
private fun RowScope.SimpMusicControl(
    cell: androidx.compose.ui.unit.Dp,
    icon: androidx.compose.ui.unit.Dp,
    painter: androidx.compose.ui.graphics.painter.Painter,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier
                    .size(cell)
                    .clip(CircleShape)
                    .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painter,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(icon),
            )
        }
    }
}

@Composable
private fun SimpMusicActionRow(
    mediaMetadata: MediaMetadata,
    playerConnection: PlayerConnection,
    bottomSheetPageState: BottomSheetPageState,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        SimpMusicActionIcon(
            painter = painterResource(R.drawable.simpmusic_info),
            contentDescription = stringResource(R.string.details),
            onClick = { bottomSheetPageState.show { ShowMediaInfo(mediaMetadata.id) } },
        )
        Spacer(Modifier.weight(1f))
        SimpMusicActionIcon(
            painter = painterResource(R.drawable.simpmusic_playlist_add),
            contentDescription = stringResource(R.string.play_next),
            onClick = { playerConnection.playNext(mediaMetadata.toMediaItem()) },
        )
        Spacer(Modifier.size(12.dp))
        SimpMusicActionIcon(
            painter = painterResource(R.drawable.simpmusic_queue_music),
            contentDescription = stringResource(R.string.queue),
            onClick = onOpenQueue,
        )
    }
}

@Composable
private fun SimpMusicActionIcon(
    painter: androidx.compose.ui.graphics.painter.Painter,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(24.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter = painter, contentDescription = contentDescription, tint = Color.White)
    }
}

@Composable
private fun SimpMusicLyricsCard(
    playerConnection: PlayerConnection,
    containerColor: Color,
    renderLyrics: Boolean,
    onShowLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val (simpMusicLyrics) = rememberPreference(SimpMusicLyricsKey, defaultValue = true)
    val lyricsPositionProvider = remember { { null as Long? } }

    val lyricsEntity by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    val lyricsText = lyricsEntity?.lyrics
    val hasLyrics = lyricsText?.isNotBlank() == true && lyricsText != LYRICS_NOT_FOUND
    if (!hasLyrics) return

    val syncLabel =
        when {
            LyricsUtils.isTtml(lyricsText!!) -> stringResource(R.string.rich_synced)
            LyricsUtils.isLineSyncedLrc(lyricsText) -> stringResource(R.string.line_synced)
            else -> stringResource(R.string.unsynced)
        }
    val provider = lyricsEntity?.providerName?.takeIf { it.isNotBlank() }

    ElevatedCard(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors().copy(containerColor = containerColor),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.lyrics),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
                Spacer(Modifier.weight(1f))
                SimpMusicActionIcon(
                    painter = painterResource(R.drawable.simpmusic_share),
                    contentDescription = stringResource(R.string.share),
                    onClick = {
                        val body = lyricsText.lineSequence().joinToString("\n") { it.substringAfter("]") }
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, body)
                                },
                                null,
                            ),
                        )
                    },
                )
                Spacer(Modifier.size(8.dp))
                TextButton(
                    onClick = onShowLyrics,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.height(20.dp),
                ) {
                    Text(text = stringResource(R.string.show), color = Color.White)
                }
            }
            Spacer(Modifier.height(18.dp))
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(300.dp)

                        .smoothFadingEdge(vertical = 36.dp),
            ) {
                if (!renderLyrics) {

                } else if (simpMusicLyrics) {
                    SimpMusicLyrics(
                        sliderPositionProvider = lyricsPositionProvider,
                        lyricsSyncOffset = 0,
                        textSizeSp = CARD_LYRICS_SIZE_SP,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LyricsEnhanced(
                        sliderPositionProvider = lyricsPositionProvider,
                        lyricsSyncOffset = 0,
                        modifier = Modifier.fillMaxSize(),
                        textColorOverride = Color.White,
                        textSizeOverride = CARD_LYRICS_SIZE_SP,
                    )
                }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                Text(
                    text = syncLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.45f),
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                if (provider != null) {
                    Text(
                        text = stringResource(R.string.lyrics_provided_by, provider),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.45f),
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SimpMusicArtistCard(
    info: MediaInfo?,
    onOpenArtist: (String) -> Unit,
) {
    AnimatedVisibility(visible = info?.author != null) {
        val author = info?.author.orEmpty()
        val authorId = info?.authorId
        ElevatedCard(
            onClick = { authorId?.let(onOpenArtist) },
            enabled = authorId != null,
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.elevatedCardColors().copy(containerColor = CardPanel),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.fillMaxWidth().height(250.dp)) {
                    AsyncImage(
                        model = info?.authorThumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )

                    Box(
                        modifier =
                            Modifier
                                .matchParentSize()
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color.Black.copy(alpha = 0.6f),
                                        0.4f to Color.Transparent,
                                    ),
                                ),
                    )
                    Text(
                        text = stringResource(R.string.artists),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.TopStart).padding(15.dp),
                    )
                }
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp)) {
                    Text(
                        text = author,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    info?.subscribers?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SimpMusicInfoCard(
    info: MediaInfo?,
    containerColor: Color,
) {
    AnimatedVisibility(visible = info?.viewCount != null || info?.description != null) {
        ElevatedCard(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.elevatedCardColors().copy(containerColor = containerColor),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(15.dp).fillMaxWidth()) {
                info?.uploadDate?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = stringResource(R.string.published_on, it),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                info?.viewCount?.let {
                    Text(
                        text = stringResource(R.string.view_count_value, groupDigits(it)),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (info?.like != null || info?.dislike != null) {
                    Text(
                        text =
                            stringResource(
                                R.string.like_and_dislike,
                                groupDigits(info.like ?: 0),
                                groupDigits(info.dislike ?: 0),
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                info?.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = stringResource(R.string.description),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SimpMusicStickyToolbar(
    mediaMetadata: MediaMetadata,
    isPlaying: Boolean,
    playerConnection: PlayerConnection,
    onCollapse: () -> Unit,
    containerColor: Color,
) {
    val currentSong by playerConnection.currentSong.collectAsStateWithLifecycle(initialValue = null)
    val liked = currentSong?.song?.liked == true

    ElevatedCard(
        shape = RoundedCornerShape(0.dp),
        colors = CardDefaults.elevatedCardColors().copy(containerColor = containerColor),
        elevation = CardDefaults.elevatedCardElevation(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .padding(top = LocalStableSystemBarsTopPadding.current)
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCollapse) {
                Icon(
                    painter = painterResource(R.drawable.player_expand_more),
                    contentDescription = stringResource(R.string.collapse),
                    tint = Color.White,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mediaMetadata.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = mediaMetadata.artists.joinToString { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.66f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = playerConnection::toggleLike) {
                Icon(
                    painter =
                        painterResource(
                            if (liked) R.drawable.player_favorite else R.drawable.player_favorite_border,
                        ),
                    contentDescription = stringResource(R.string.action_like),
                    tint = if (liked) MaterialTheme.colorScheme.error else Color.White,
                )
            }
            IconButton(onClick = { playerConnection.player.togglePlayPause() }) {
                Icon(
                    painter =
                        painterResource(
                            if (isPlaying) R.drawable.player_pause else R.drawable.player_play,
                        ),
                    contentDescription =
                        stringResource(if (isPlaying) R.string.widget_pause else R.string.play),
                    tint = Color.White,
                )
            }
        }
    }
}

private fun groupDigits(value: Int): String = String.format(Locale.getDefault(), "%,d", value)
