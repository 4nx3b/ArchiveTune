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
 * gesture, the switch only happens when a page settles. When the engine
 * itself advances a single step — the song ends, a skip lands from the
 * notification or the queue sheet — the feed plays the same swipe as an
 * animated scroll, so the page change always looks like the reference's.
 *
 * Around the feed sits the reference's chrome: the top navigation (the
 * fullscreen toggle on the left, the app's real section tabs in the middle,
 * search on the right) and the progress row pinned to the bottom over the
 * app's real destinations.
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

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.ui.component.BottomSheetPageState
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.MenuState
import moe.rukamori.archivetune.ui.component.PlatformBackdrop
import moe.rukamori.archivetune.ui.component.layerBackdrop
import moe.rukamori.archivetune.ui.component.rememberBackdrop
import moe.rukamori.archivetune.ui.menu.AnchoredLyricsOverflowMenu
import moe.rukamori.archivetune.ui.player.LocalVideoArtworkState
import moe.rukamori.archivetune.ui.player.LocalVideoFullscreenState

/** Height of the top navigation row (icons + tabs). */
internal val TIKTOK_TOP_NAV_HEIGHT = 44.dp

/**
 * The feed player. Parameters mirror the other self-contained styles so
 * Player.kt dispatches every style the same way, plus [lyricsSyncOffset] for
 * the one shared surface this style deliberately reuses instead of owning —
 * the Apple Music inline lyrics pane — and the app's standard seek callbacks
 * so the feed's progress row scrubs exactly like every other style's slider.
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
    lyricsSyncOffset: Int = 0,
    onLyricsSyncOffsetChange: (Int) -> Unit = {},
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

    // True while the feed plays an engine-initiated swipe (song end,
    // notification skip). The outgoing page suppresses its paused glyph for
    // the duration, so the advance reads as a hand-swipe instead of a
    // pause flash followed by a jump (user report 2026-09-02: "when the
    // song ends it pauses and the next song plays without the swipe
    // animation").
    var autoAdvancing by remember { mutableStateOf(false) }

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
    // This collector must NEVER restart — it keys only on the pager state
    // and reads everything else through rememberUpdatedState. The previous
    // version also keyed on queueWindows / currentWindowIndex / skipInvoker,
    // so every engine-initiated advance (song end, notification skip)
    // RESTARTED the collector, and a restarted snapshotFlow immediately
    // re-emits the current settledPage — the page the engine just LEFT.
    // The restarted collector then saw "settled != index", marked a pending
    // target on the old page and seeked BACK to it, which cancelled the
    // engine→feed swipe mid-animation — the user's song ended, stuttered
    // back, and the next song played with the page never swiping (user
    // report 2026-09-02). One long-lived collector only ever reacts to
    // pages the user actually settles on.
    val currentWindowIndexState = rememberUpdatedState(currentWindowIndex)
    val queueWindowsState = rememberUpdatedState(queueWindows)
    val skipInvokerState = rememberUpdatedState(skipInvoker)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { settled ->
                val liveIndex = currentWindowIndexState.value
                val liveQueue = queueWindowsState.value
                if (liveQueue.isNotEmpty() &&
                    settled != liveIndex &&
                    settled in 0 until liveQueue.size
                ) {
                    pendingSeekTarget = settled
                    skipInvokerState.value(settled, liveIndex)
                }
            }
    }

    // ── Engine → feed ──
    // When the song changes from *anywhere* — mini player, queue sheet,
    // notification, another screen, or the song simply ENDING — the feed
    // follows. A single-step move plays the feed's own swipe (the animated
    // scroll the user sees when they flick the page themselves), so a song
    // that ends on its own advances exactly like a hand-swiped one (user
    // request 2026-09-02: "when the song ends the song should swipe
    // automatically and the swipe animation should also play"); multi-page
    // jumps snap — the queue sheet's far taps should land, not tour the pages.
    // When the change came from the feed itself the indices already match and
    // this is a no-op.
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
        if (currentWindowIndex !in 0 until queueWindows.size) return@LaunchedEffect
        if (pagerState.currentPage == currentWindowIndex) return@LaunchedEffect
        if (pagerState.isScrollInProgress) {
            // The song ended while the user's own gesture is still flying —
            // let it land first, then follow: an animated scroll fired into
            // an active gesture would fight the user's hand.
            snapshotFlow { pagerState.isScrollInProgress }.first { !it }
            if (pagerState.currentPage == currentWindowIndex) return@LaunchedEffect
        }
        val delta = currentWindowIndex - pagerState.currentPage
        if (delta == 1 || delta == -1) {
            // autoAdvancing gates the outgoing page's paused glyph for the
            // duration of the engine-driven swipe (see TikTokSongPage), so
            // the advance reads as one continuous hand-swipe.
            autoAdvancing = true
            try {
                pagerState.animateScrollToPage(currentWindowIndex)
            } finally {
                autoAdvancing = false
            }
        } else {
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

    // ── Inline lyrics (the Apple Music treatment) ──
    // The rail's lyrics action toggles the karaoke pane in place of the
    // artwork — the same LyricsEnhanced component, the same follow-the-song
    // behaviour, the same tap-a-line-to-seek — instead of leaving the feed
    // for the separate lyrics page.
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }

    // ── Lyrics overflow (the Apple Music anchored popup) ──
    // While the inline pane is open, the rail's more button opens the LYRICS
    // overflow menu — the same anchored popup the Apple Music style shows
    // from its own lyrics view, with the same Edit / Refetch / Translate /
    // Search actions — instead of the song menu. The popup carries the same
    // frosted-glass blur (it samples the feed through the layer backdrop
    // below) and opens from the top-right below the top navigation, the
    // spot the Apple Music style's own popup opens from.
    val currentLyrics by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    var showLyricsMenu by remember { mutableStateOf(false) }
    LaunchedEffect(lyricsOpen) {
        if (!lyricsOpen) showLyricsMenu = false
    }

    // ── Full screen (the reference's pill) ──
    // With a playable video for the current song this is the app's existing
    // fullscreen video overlay, verbatim; without one it is the feed's own
    // immersive mode — everything but the artwork fades away.
    val videoFullscreenHolder = LocalVideoFullscreenState.current
    val videoState = LocalVideoArtworkState.current
    var immersive by rememberSaveable { mutableStateOf(false) }
    // Back exits inline lyrics first, immersive mode next; the separate
    // lyrics page (if something else opened it) keeps its own back handling.
    BackHandler(enabled = lyricsOpen && !lyricsVisible) { lyricsOpen = false }
    BackHandler(enabled = immersive && !lyricsVisible) { immersive = false }
    // Reset both when the player sheet collapses so re-expanding always
    // shows the full feed chrome, and when the lyrics page opens.
    LaunchedEffect(state.isExpanded, lyricsVisible) {
        if (!state.isExpanded || lyricsVisible) {
            if (immersive) immersive = false
            if (lyricsOpen) lyricsOpen = false
        }
    }
    val onFullscreenAction =
        remember(videoState, videoFullscreenHolder) {
            {
                if (videoState != null) {
                    videoFullscreenHolder.isFullscreen = true
                } else {
                    immersive = !immersive
                    if (immersive) lyricsOpen = false
                }
            }
        }

    // ── Reserved chrome heights shared by pages and the global overlays ──
    // The top inset is the app's notch-safe value (it floors against the
    // display cutout): when the status bar is hidden — the hide-status-bar
    // preference, a lyrics page, a menu — WindowInsets.statusBars reports 0
    // and a plain statusBarsPadding would let the nav collide with the
    // physical notch. The cutout is reported regardless of bar visibility.
    val stableTopInset = LocalStableSystemBarsTopPadding.current
    val navigationBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val topChromeHeight = stableTopInset + TIKTOK_TOP_NAV_HEIGHT + 4.dp
    val bottomChromeHeight = TIKTOK_PROGRESS_ROW_HEIGHT + navigationBarInset

    // "Add to playlist" lives at the content level (not per page) because the
    // dialog is one composable; the rail's bookmark button just tells it which
    // song was tapped.
    var addToPlaylistSong by remember { mutableStateOf<MediaMetadata?>(null) }

    val displayPositionMs = sliderPosition ?: position

    // The lyrics pane reads the scrub position the way the Apple Music style
    // feeds it: null unless the user is actively scrubbing, so the pane's own
    // frame loop drives the karaoke sweep (never the 100ms-polled position —
    // that steps). Hoisted above the pager so a scrub tick recomposes no page.
    val sliderPositionState = rememberUpdatedState(sliderPosition)
    val lyricsPosProvider = remember { { sliderPositionState.value } }

    // ── The lyrics overflow popup's frosted backdrop ──
    // The Apple Music style's lyrics overflow popup is real frosted glass:
    // the player content records itself into a layer backdrop and the popup
    // samples it (32dp blur + vibrancy). The feed gets the exact same
    // treatment here — the popup IS the Apple Music popup, so it gets the
    // Apple Music blur too (user report 2026-09-02: "the lyrics popup that
    // opens doesn't have blur. I want the exact same popup for lyrics
    // overflow menu from Apple music style"). Below Android S there is no
    // RuntimeShader and the popup falls back to its plain dark-glass card —
    // the same fallback the Apple Music style shows on those devices.
    val popupBackdrop: PlatformBackdrop? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            rememberBackdrop(Color.Transparent)
        } else {
            null
        }

    // The feed root's bounds in root space, kept live — the popup's anchor
    // below is derived from them.
    var rootBounds by remember { mutableStateOf(Rect.Zero) }

    // The popup's anchor: the TOP-RIGHT, just below the top navigation —
    // the position the Apple Music style's lyrics overflow popup opens from
    // (below its mini header's more chip). It used to grow out of the rail's
    // more button, which pinned the whole menu to the bottom of the screen
    // (user report 2026-09-02: "also it opens on the downside. Fix it");
    // the anchor is synthetic now, so the rail no longer reports bounds.
    val density = LocalDensity.current
    val lyricsPopupAnchor =
        remember(rootBounds, stableTopInset, density) {
            with(density) {
                val navBottomPx =
                    rootBounds.top + (stableTopInset + TIKTOK_TOP_NAV_HEIGHT).toPx()
                val rightPx = rootBounds.right - 12.dp.toPx()
                val anchorWidthPx = 40.dp.toPx()
                Rect(
                    left = rightPx - anchorWidthPx,
                    top = navBottomPx - 4.dp.toPx(),
                    right = rightPx,
                    bottom = navBottomPx,
                )
            }
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onGloballyPositioned { rootBounds = it.boundsInRoot() },
    ) {
        VerticalPager(
            state = pagerState,
            // The pager is the backdrop-capturing layer for the lyrics
            // overflow popup: everything the popup can ever cover (lyrics,
            // artwork, rail — it never overlaps the top navigation or the
            // progress row) sits inside it, and the popup itself renders as
            // a SIBLING at the root Box below. A drawBackdrop sampler must
            // never sit INSIDE the layer it samples — that is a
            // render-feedback loop (kyant).
            modifier =
                Modifier
                    .fillMaxSize()
                    .let { base ->
                        if (popupBackdrop != null) {
                            base.layerBackdrop(popupBackdrop)
                        } else {
                            base
                        }
                    },
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
                // While the stream is loading or the feed is playing an
                // engine-initiated swipe, the current page is not "paused"
                // — buffering and auto-advance must not flash the play
                // glyph (user report 2026-09-02: "when the song ends it
                // pauses").
                suppressPauseOverlay = isLoading || autoAdvancing,
                playerConnection = playerConnection,
                queueTitle = queueTitle,
                immersive = immersive,
                lyricsOpen = lyricsOpen,
                sliderPositionProvider = lyricsPosProvider,
                lyricsSyncOffset = lyricsSyncOffset,
                topChromeHeight = topChromeHeight,
                bottomChromeHeight = bottomChromeHeight,
                sheetState = state,
                onAddToPlaylist = { addToPlaylistSong = pageMetadata },
                onToggleLyrics = { lyricsOpen = !lyricsOpen },
                onTogglePlayPause = { player.togglePlayPause() },
                onQueueClick = onOpenQueue,
                onOpenLyricsMenu = { showLyricsMenu = true },
                navController = navController,
                menuState = menuState,
                bottomSheetPageState = bottomSheetPageState,
            )
        }

        // ── Top navigation: [fullscreen] [section tabs + Queue] [search] ──
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

        // ── Bottom chrome: playback progress ──
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
            )
        }

        // ── Immersive exit affordance ──
        if (immersive) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = stableTopInset + 6.dp, end = 12.dp)
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

        // ── Lyrics overflow (the anchored Apple Music popup) ──
        // Rendered as a SIBLING of the pager (the backdrop-capturing layer)
        // at the player's root. While the inline lyrics pane is open, the
        // rail's more button routes here instead of the song menu. The popup
        // samples the feed through [popupBackdrop] — the same frosted-glass
        // blur the Apple Music style's popup has — and its anchor puts it at
        // the top-right below the top navigation, where the Apple Music
        // style's own popup opens (user reports 2026-09-02: "the exact same
        // popup for lyrics overflow menu from Apple music style; also it
        // opens on the downside. Fix it").
        if (lyricsOpen && showLyricsMenu) {
            AnchoredLyricsOverflowMenu(
                iconBoundsInRoot = lyricsPopupAnchor,
                lyricsProvider = { currentLyrics },
                mediaMetadataProvider = { mediaMetadata },
                lyricsSyncOffset = lyricsSyncOffset,
                onLyricsSyncOffsetChange = onLyricsSyncOffsetChange,
                showPlayerControlsState = null,
                onShowPlayerControlsChange = null,
                onAutoHidePlayerControlsChange = {},
                onDismiss = { showLyricsMenu = false },
                showControlsToggles = false,
                backdrop = popupBackdrop,
            )
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
 * the same destinations, the same semantics, as the main navigation. (The
 * Queue action that briefly rode to the tabs' right was removed per user
 * request 2026-09-02: "remove the queue text from the top" — the queue
 * sheet stays reachable through the song info's queue chip.)
 *
 * The row's own padding is the notch-safe stable inset (not
 * statusBarsPadding): the status bar can be hidden — the app's hide-status-
 * bar preference, or a lyrics page — at which point WindowInsets.statusBars
 * reports 0 and the tabs would slide straight under the physical notch.
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
    val stableTopInset = LocalStableSystemBarsTopPadding.current
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
                .padding(top = stableTopInset)
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

        // Right: search — and, while the stream resolves, the loading
        // indicator. The spinner swaps IN PLACE inside the search slot
        // (AnimatedContent) instead of being inserted into the row: nothing
        // to the left of it ever moves, loading or not, so the tabs and every
        // hit target keep absolutely constant positions.
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
            AnimatedContent(
                targetState = isLoading,
                transitionSpec = {
                    fadeIn(tween(160)) togetherWith fadeOut(tween(160))
                },
                contentAlignment = Alignment.Center,
                label = "tiktokSearchLoadingSlot",
            ) { loading ->
                if (loading) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.solar_magnifer_linear),
                        contentDescription = stringResource(R.string.search),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
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
