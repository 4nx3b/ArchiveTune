/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * TikTok player style — the vertical feed.
 *
 * A full-screen, vertically swiped music player: every queue entry is one page,
 * swiping up lands on the next song, swiping down on the previous, and an
 * abandoned swipe springs back — playback itself is never interrupted by the
 * gesture, the switch only happens when a page settles.
 *
 * Around the feed sits the reference's chrome: the top navigation (the
 * fullscreen toggle on the left, the app's real section tabs in the middle,
 * search on the right), the persistent bottom navigation over the app's real
 * destinations, and the progress row between them.
 *
 * Belongs exclusively to the TikTok player style; not shared with any other
 * player style, per the self-containment rule for player styles. What it does
 * share is the app's playback substrate, deliberately: the one PlayerConnection
 * (engine, queue, play/pause/seek), the one like state in Room, the one lyrics
 * page, the one download manager, the app's video fullscreen overlay. No
 * second audio engine, no copied playback state, no fake songs — the feed is
 * a *view* over the real queue, not a player of its own.
 */

package moe.rukamori.archivetune.ui.player.tiktok

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.currentBackStackEntryAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.ui.component.BottomSheetPageState
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.MenuState
import moe.rukamori.archivetune.ui.player.LocalVideoArtworkState
import moe.rukamori.archivetune.ui.player.LocalVideoFullscreenState

/** Height of the top navigation row (icons + tabs). */
internal val TIKTOK_TOP_NAV_HEIGHT = 44.dp

/**
 * The feed player. Parameters mirror the other self-contained styles so
 * Player.kt dispatches every style the same way, plus [onOpenLyrics] for the
 * one shared surface this style deliberately reuses instead of owning — the
 * full-screen lyrics page — and the app's standard seek callbacks so the
 * feed's progress row scrubs exactly like every other style's slider.
 */
@Composable
fun TikTokPlayerContent(
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
    lyricsVisible: Boolean,
    onOpenLyrics: () -> Unit = {},
    onOpenQueue: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onSeekFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val player = playerConnection.player
    val haptics = LocalHapticFeedback.current

    // ── The queue, in play order ──
    // queueWindows is rebuilt by Player.getQueueWindows() walking the timeline
    // in play order (shuffle-aware), so list index == play-order index and
    // currentWindowIndex points at the live item inside it. Pages are keyed by
    // mediaId so Compose keeps page identity (and the artwork cache) stable
    // across timeline refreshes.
    val queueWindows by playerConnection.queueWindows.collectAsStateWithLifecycle()
    val currentWindowIndex by playerConnection.currentWindowIndex.collectAsStateWithLifecycle()
    val queueTitle by playerConnection.queueTitle.collectAsStateWithLifecycle(initialValue = null)

    val pagerState =
        rememberPagerState(
            initialPage = currentWindowIndex.coerceAtLeast(0),
        ) { queueWindows.size }

    // A feed-initiated skip that the engine hasn't confirmed yet (the index
    // flow updates asynchronously from the seek call). While one is pending,
    // the "engine → feed" effect below never snaps the pager — the pager is
    // already sitting on the page the user chose, and snapping on a
    // half-updated index is exactly how a page visibly flickers back and
    // forth during a skip.
    var pendingSeekTarget by remember { mutableStateOf<Int?>(null) }

    // ── Feed → engine ──
    // A page only takes over playback once it has *settled* — a drag thrown
    // halfway, or a fling the pager decides to cancel, never touches the
    // audio. Single-step moves reuse the app's own skip logic (which handles
    // crossfade preparation and session bookkeeping); multi-page jumps fall
    // back to a queue seek, the same call the queue sheet makes for a tap.
    val skipInvoker =
        remember(playerConnection, canSkipNext, canSkipPrevious) {
            { target: Int, from: Int ->
                when (target) {
                    from -> Unit
                    from + 1 -> if (canSkipNext) playerConnection.seekToNext()
                    from - 1 -> if (canSkipPrevious) playerConnection.seekToPrevious()
                    else -> if (target in 0 until queueWindows.size) player.seekTo(target, 0)
                }
                Unit
            }
        }
    LaunchedEffect(pagerState, queueWindows, currentWindowIndex, skipInvoker) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { settled ->
                if (queueWindows.isNotEmpty() &&
                    settled != currentWindowIndex &&
                    settled in 0 until queueWindows.size
                ) {
                    pendingSeekTarget = settled
                    skipInvoker(settled, currentWindowIndex)
                }
            }
    }

    // ── Engine → feed ──
    // When the song changes from *anywhere* — mini player, queue sheet,
    // notification, another screen — the feed follows. Snapping (not
    // animating) keeps the page and the audio from ever visibly disagreeing
    // for more than a frame; when the change came from the feed itself the
    // indices already match and this is a no-op.
    LaunchedEffect(currentWindowIndex, queueWindows.size) {
        if (queueWindows.isEmpty()) return@LaunchedEffect
        val pending = pendingSeekTarget
        if (pending != null) {
            // A feed skip is in flight: the engine confirming it releases the
            // lock. Anything else (a stale index mid-update) just waits — never
            // snap while the pager is already on the page the user picked.
            if (currentWindowIndex == pending) pendingSeekTarget = null
            return@LaunchedEffect
        }
        if (currentWindowIndex in 0 until queueWindows.size &&
            pagerState.currentPage != currentWindowIndex &&
            !pagerState.isScrollInProgress
        ) {
            pagerState.scrollToPage(currentWindowIndex)
        }
    }

    // Watchdog: if a pending feed skip is never confirmed (the seek silently
    // failed, or something else took over playback first), release the lock
    // and re-sync the feed to wherever the engine actually is.
    LaunchedEffect(pendingSeekTarget) {
        val pending = pendingSeekTarget ?: return@LaunchedEffect
        delay(PENDING_SEEK_TIMEOUT_MS)
        if (pendingSeekTarget == pending) {
            pendingSeekTarget = null
            val idx = currentWindowIndex
            if (queueWindows.isNotEmpty() &&
                idx in 0 until queueWindows.size &&
                pagerState.currentPage != idx &&
                !pagerState.isScrollInProgress
            ) {
                pagerState.scrollToPage(idx)
            }
        }
    }

    // A haptic tick the moment a settled page commits a song switch — the
    // feed's own "the next video just loaded" beat.
    var lastHapticIndex by remember { mutableStateOf(currentWindowIndex) }
    LaunchedEffect(currentWindowIndex) {
        if (currentWindowIndex != lastHapticIndex) {
            lastHapticIndex = currentWindowIndex
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    // ── Full screen (the reference's pill) ──
    // With a playable video for the current song this is the app's existing
    // fullscreen video overlay, verbatim; without one it is the feed's own
    // immersive mode — everything but the artwork fades away.
    val videoFullscreenHolder = LocalVideoFullscreenState.current
    val videoState = LocalVideoArtworkState.current
    var immersive by rememberSaveable { mutableStateOf(false) }
    // Back exits immersive mode before anything else; lyrics (which is its
    // own full-screen surface) keeps its own back handling.
    BackHandler(enabled = immersive && !lyricsVisible) { immersive = false }
    // Reset immersive when the player sheet collapses so re-expanding always
    // shows the full feed chrome, and when the lyrics page opens.
    LaunchedEffect(state.isExpanded, lyricsVisible) {
        if ((!state.isExpanded || lyricsVisible) && immersive) immersive = false
    }
    val onFullscreenAction =
        remember(videoState, videoFullscreenHolder) {
            {
                if (videoState != null) {
                    videoFullscreenHolder.isFullscreen = true
                } else {
                    immersive = !immersive
                }
            }
        }

    // ── Reserved chrome heights shared by pages and the global overlays ──
    val statusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val topChromeHeight = statusBarInset + TIKTOK_TOP_NAV_HEIGHT + 4.dp
    val bottomChromeHeight =
        TIKTOK_PROGRESS_ROW_HEIGHT + TIKTOK_BOTTOM_NAV_HEIGHT + navigationBarInset

    // "Add to playlist" lives at the content level (not per page) because the
    // dialog is one composable; the rail's bookmark button just tells it which
    // song was tapped.
    var addToPlaylistSong by remember { mutableStateOf<MediaMetadata?>(null) }

    val displayPositionMs = sliderPosition ?: position

    Box(modifier = modifier.fillMaxSize()) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // One page either side is pre-composed so the incoming page is fully
            // rendered before it slides in; nothing beyond that is composed,
            // ever, no matter how long the queue is. Compose disposes pages the
            // moment they leave this window — the "three pages, no more" rule.
            // (Named beyondViewportPageCount in this Compose version — the
            // older beyondBoundsPageCount name was removed.)
            beyondViewportPageCount = 1,
            pageSpacing = 0.dp,
        ) { page ->
            val window = queueWindows.getOrNull(page)
            val isCurrentPage = page == currentWindowIndex
            if (window == null) {
                // A page past the end can be composed during a queue shrink;
                // an empty dark page keeps the pager from drawing garbage there.
                Box(
                    Modifier
                        .fillMaxSize()
                        .tiktokScrim(),
                )
                return@VerticalPager
            }
            // The live mediaMetadata param for the current song is richer than
            // the window's own item (DB-merged album etc.), so the current page
            // uses it; other pages fall back to the window's metadata, or a
            // minimal one for queue entries that somehow carry none.
            val pageMetadata =
                if (isCurrentPage) {
                    mediaMetadata
                } else {
                    window.mediaItem.metadata
                        ?: MediaMetadata(
                            id = window.mediaItem.mediaId,
                            title = "",
                            artists = emptyList(),
                            duration = -1,
                        )
                }
            TikTokSongPage(
                pageMetadata = pageMetadata,
                isCurrentPage = isCurrentPage,
                isPlaying = isPlaying,
                playerConnection = playerConnection,
                queueTitle = queueTitle,
                immersive = immersive,
                topChromeHeight = topChromeHeight,
                bottomChromeHeight = bottomChromeHeight,
                sheetState = state,
                onOpenLyrics = onOpenLyrics,
                onAddToPlaylist = { addToPlaylistSong = pageMetadata },
                onTogglePlayPause = { player.togglePlayPause() },
                onFullscreen = onFullscreenAction,
                onQueueClick = onOpenQueue,
                navController = navController,
                menuState = menuState,
                bottomSheetPageState = bottomSheetPageState,
            )
        }

        // ── Top navigation: [fullscreen] [section tabs] [search] ──
        AnimatedVisibility(
            visible = !immersive,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            TikTokTopNavigation(
                navController = navController,
                state = state,
                isLoading = isLoading,
                onFullscreen = onFullscreenAction,
            )
        }

        // ── Bottom chrome: playback progress + persistent navigation ──
        AnimatedVisibility(
            visible = !immersive,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    Color.Black.copy(alpha = 0f),
                                    Color.Black.copy(alpha = 0.45f),
                                    Color.Black,
                                ),
                        ),
                    ),
        ) {
            TikTokBottomChrome(
                displayPositionMs = displayPositionMs,
                durationMs = duration,
                onSeek = onSeek,
                onSeekFinished = onSeekFinished,
                navController = navController,
                sheetState = state,
            )
        }

        // ── Immersive exit affordance ──
        if (immersive) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 6.dp, end = 12.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .tiktokNoRippleClickable(onClick = { immersive = false }),
            ) {
                Icon(
                    painter = painterResource(R.drawable.solar_fullscreen_exit_linear),
                    contentDescription = stringResource(R.string.tiktok_feed_exit_fullscreen),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }

    // The one dialog the style needs: the playlist picker for the rail's
    // bookmark action. Reuses the app's shared AddToPlaylistDialog (app
    // infrastructure, not another style's component).
    addToPlaylistSong?.let { song ->
        TikTokAddToPlaylist(
            song = song,
            onDismiss = { addToPlaylistSong = null },
        )
    }
}

/**
 * The top chrome, the reference's structure: the fullscreen toggle on the
 * left, the app's real section tabs in the middle (selected = bold white
 * with the short underline), and search on the right. Tapping a tab or the
 * search icon folds the player back into the mini player and navigates —
 * the same destinations, the same semantics, as the main navigation.
 */
@Composable
private fun TikTokTopNavigation(
    navController: NavController,
    state: BottomSheetState,
    isLoading: Boolean,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val tabs =
        remember(context) {
            listOf(
                TikTokFeedTab(
                    label = context.getString(R.string.home),
                    route = "home",
                ),
                TikTokFeedTab(
                    label = context.getString(R.string.filter_library),
                    route = "library",
                ),
            )
        }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val selectedRoutes =
        remember(navBackStackEntry) {
            navBackStackEntry?.destination?.hierarchy?.map { it.route }?.toSet()
                ?: emptySet<String>()
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(TIKTOK_TOP_NAV_HEIGHT)
                .padding(horizontal = 6.dp),
    ) {
        // Left: the fullscreen/expand toggle.
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .tiktokNoRippleClickable(onClick = onFullscreen),
        ) {
            Icon(
                painter = painterResource(R.drawable.solar_fullscreen_linear),
                contentDescription = stringResource(R.string.tiktok_feed_fullscreen),
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }

        Spacer(Modifier.weight(1f))

        // Center: the section tabs (selected = bold white + short underline).
        Row(verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { tab ->
                val isSelected = tab.route in selectedRoutes
                val onTabClick =
                    remember(navController, state, tab) {
                        {
                            state.collapseSoft()
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .tiktokNoRippleClickable(onClick = onTabClick)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = tab.label,
                        color = if (isSelected) Color.White else TIKTOK_INACTIVE_GRAY,
                        fontSize = 16.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier =
                            Modifier
                                .width(22.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(
                                    if (isSelected) Color.White else Color.Transparent,
                                ),
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // While the stream resolves, a small spinner keeps a stalled load
        // from reading as a dead screen.
        if (isLoading) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.2.dp,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
        }

        // Right: search.
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .tiktokNoRippleClickable(
                        onClick =
                            remember(navController, state) {
                                {
                                    state.collapseSoft()
                                    navController.navigate("search") {
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                    ),
        ) {
            Icon(
                painter = painterResource(R.drawable.solar_magnifer_linear),
                contentDescription = stringResource(R.string.search),
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private data class TikTokFeedTab(
    val label: String,
    val route: String,
)

/**
 * Ripple-free clickable for the feed's flat chrome — the reference has no
 * ripple feedback anywhere, just the white-on-black hit targets.
 */
@Composable
internal fun Modifier.tiktokNoRippleClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.then(
        if (enabled) {
            Modifier.clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
        } else {
            Modifier
        },
    )
}

/** How long a feed-initiated skip may stay unconfirmed before the sync watchdog takes over. */
private const val PENDING_SEEK_TIMEOUT_MS = 1_500L
