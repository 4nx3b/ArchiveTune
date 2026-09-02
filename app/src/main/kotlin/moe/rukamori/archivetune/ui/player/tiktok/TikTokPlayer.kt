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
 * Belongs exclusively to the TikTok player style; not shared with any other
 * player style, per the self-containment rule for player styles. What it does
 * share is the app's playback substrate, deliberately: the one PlayerConnection
 * (engine, queue, play/pause/seek), the one like state in Room, the one lyrics
 * page, the one download manager. No second audio engine, no copied playback
 * state, no fake songs — the feed is a *view* over the real queue, not a
 * player of its own.
 */

package moe.rukamori.archivetune.ui.player.tiktok

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.ui.component.BottomSheetPageState
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.MenuState

/**
 * The feed player. Parameters mirror BitChordPlayerContent so Player.kt
 * dispatches every self-contained style the same way, plus [onOpenLyrics] for
 * the one shared surface this style deliberately reuses instead of owning —
 * the full-screen lyrics page.
 */
@Composable
fun TikTokPlayerContent(
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
    modifier: Modifier = Modifier,
    onOpenLyrics: () -> Unit = {},
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

    // "Add to playlist" lives at the content level (not per page) because the
    // dialog is one composable; the rail's bookmark button just tells it which
    // song was tapped.
    var addToPlaylistSong by remember { mutableStateOf<MediaMetadata?>(null) }

    Box(modifier = modifier) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // One page either side is pre-composed so the incoming page is fully
            // rendered before it slides in; nothing beyond that is composed,
            // ever, no matter how long the queue is. Compose disposes pages the
            // moment they leave this window — the "three pages, no more" rule.
            beyondBoundsPageCount = 1,
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
                positionMs = position,
                durationMs = duration,
                playerConnection = playerConnection,
                sheetState = state,
                onOpenLyrics = onOpenLyrics,
                onAddToPlaylist = { addToPlaylistSong = pageMetadata },
                navController = navController,
                menuState = menuState,
                bottomSheetPageState = bottomSheetPageState,
            )
        }

        // ── Header: the minimal top chrome ──
        TikTokHeader(
            isLoading = isLoading,
            onCollapse = { state.collapseSoft() },
        )
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
 * The top chrome: a back button that folds the player back into the mini
 * player, and — while the player is resolving the stream — a small spinner so
 * a stalled load doesn't read as a dead screen. Nothing else: TikTok's header
 * is a dismiss affordance plus content, and the "content" here is the feed.
 */
@Composable
private fun TikTokHeader(
    isLoading: Boolean,
    onCollapse: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        IconButton(onClick = onCollapse) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "Close player",
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (isLoading) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.2.dp,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(18.dp))
    }
}

/** How long a feed-initiated skip may stay unconfirmed before the sync watchdog takes over. */
private const val PENDING_SEEK_TIMEOUT_MS = 1_500L
