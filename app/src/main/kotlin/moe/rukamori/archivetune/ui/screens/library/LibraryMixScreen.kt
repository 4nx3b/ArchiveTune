/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.library

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalDownloadUtil
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.HideCachedCardKey
import moe.rukamori.archivetune.constants.HideLikedSongsCardKey
import moe.rukamori.archivetune.constants.HideLocalFilesCardKey
import moe.rukamori.archivetune.constants.HideOfflineCardKey
import moe.rukamori.archivetune.constants.HideTop50CardKey
import moe.rukamori.archivetune.constants.LibraryFilter
import moe.rukamori.archivetune.constants.SongSortType
import moe.rukamori.archivetune.constants.TopSize
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.Playlist
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.spotify.SpotifyLibraryViewModel
import moe.rukamori.archivetune.ui.component.ExpressivePullToRefreshBox
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.LibraryMixViewModel

/**
 * Builds a sized, cache-enabled [ImageRequest] for a thumbnail that will be
 * displayed at [widthDp] × [heightDp]. Without an explicit `.size()` Coil
 * downloads the original full-resolution image (often 1280×720+ for YT
 * thumbnails, 640×640 for Spotify playlist covers) and downsamples on the
 * fly — slow on cold start, especially when the Library tab fires 10+
 * parallel requests at once.
 *
 * Passing an explicit size lets the CDN serve the smallest bucket it has
 * (YT `mqdefault` is 320×180, Spotify `image` URLs honour `=w300-h300`),
 * which combined with the tuned OkHttp pool in [moe.rukamori.archivetune.App.newImageLoader]
 * makes thumbnails load near-instantly after the first cache miss.
 */
@Composable
private fun rememberSizedImageRequest(
    url: String?,
    widthDp: Dp,
    heightDp: Dp,
): ImageRequest? {
    if (url.isNullOrBlank()) return null
    val context = LocalContext.current
    val density = LocalDensity.current
    val widthPx = with(density) { widthDp.roundToPx().coerceAtLeast(1) }
    val heightPx = with(density) { heightDp.roundToPx().coerceAtLeast(1) }
    return remember(url, widthPx, heightPx) {
        ImageRequest
            .Builder(context)
            .data(url)
            .size(Size(widthPx, heightPx))
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .build()
    }
}

// ── Visual constants ─────────────────────────────────────────────────────────────
//
// Tuned against the reference Apple Music Library screenshot. Sizes are kept in
// dp so density scaling still works on tablets / narrow phones; the overall
// rhythm of the screen (header height, row height, divider gaps, grid columns)
// matches the reference, not Material 3 defaults.
private val LibraryHeaderTopPadding = 12.dp
private val LibraryHeaderHorizontalPadding = 20.dp
private val LibraryCategoryRowHeight = 56.dp
private val LibraryCategoryIconSize = 28.dp
private val LibraryGridSpacing = 14.dp
private val LibraryGridHorizontalPadding = 20.dp
private val LibraryArtworkCornerRadius = 10.dp

/**
 * The pink/magenta accent used throughout the redesigned Library overview.
 *
 * Matches Apple Music's pink (#FF375F) used for the active tab indicator, the
 * line-style category icons, and the inline-play affordance on artwork tiles.
 * Picked as a saturated brand pink that has acceptable WCAG AA contrast on
 * both the dark mode (near-black) and light mode (off-white) page surfaces so
 * the icons read cleanly regardless of theme.
 */
private val LibraryAccentColor: Color = Color(0xFFFF375F)

@Composable
fun LibraryMixScreen(
    navController: NavController,
    filterContent: (@Composable () -> Unit)?,
    selectedTagIds: Set<String>,
    showSpotify: Boolean,
    onTabSelected: (LibraryFilter) -> Unit,
    viewModel: LibraryMixViewModel = hiltViewModel(),
    spotifyLibraryViewModel: SpotifyLibraryViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val downloadUtil = LocalDownloadUtil.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()

    // ── Existing data sources retained ────────────────────────────────────────
    // The redesign is presentation-only. Every row in the new layout pulls from
    // the same database / viewmodel flows the old layout did, so all existing
    // behaviour (playlists tap-through, artists tap-through, favourites count,
    // downloads count, history count, recently-added grid) is preserved.
    val likedSongsCount by database.likedSongsCount().collectAsStateWithLifecycle(initialValue = 0)
    // Real downloaded-songs count: counts only songs whose Media3 Download
    // state is `STATE_COMPLETED`. The previous implementation called
    // `database.downloadedSongsCount()` which queries
    // `SELECT COUNT(1) FROM song WHERE dateDownload IS NOT NULL` — but
    // `SongEntity.dateDownload` defaults to `LocalDateTime.now()` on every
    // newly-inserted song (see SongEntity.kt:49), so that query returns the
    // total number of songs in the DB (e.g. 13406 for the user's full
    // synced library) instead of the actual downloaded count. Reading
    // from `downloadUtil.downloads` mirrors what
    // `MediaLibrarySessionCallback.downloadedSongs()` does and returns
    // the true "songs actually on disk" count.
    val downloadsMap by downloadUtil.downloads.collectAsStateWithLifecycle()
    val downloadedSongsCount = remember(downloadsMap) {
        downloadsMap.values.count { it.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED }
    }
    val historyEventsCount by database.historyEventsCount().collectAsStateWithLifecycle(initialValue = 0)
    val localSongsCount by database
        .localSongs()
        .map { it.size }
        .collectAsStateWithLifecycle(initialValue = 0)
    // Recently liked songs in sequential order (most recent first). Used by the
    // newly-added "Recently Liked" subsection of the Recently Added block so
    // the user sees songs they have liked alongside their recently added
    // playlists. Pulled from `likedSongs(SongSortType.CREATE_DATE, descending=true)`
    // which delegates to `likedSongsByCreateDateAsc().asReversed()`.
    val recentlyLikedSongs by database
        .likedSongs(SongSortType.CREATE_DATE, descending = true)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // ── Per-card visibility toggles (Extras section) ──────────────────────────
    // The user can hide each quick-access card from Appearance → Extras. These
    // toggles previously only affected the OLD ShortcutCard grid; the
    // redesigned category rows now honour them so hiding Offline, Cached,
    // Local Files, My top 50, or Liked Songs in Extras removes the
    // corresponding row from this screen too. The default value for each
    // toggle is false (visible).
    val (hideLikedSongsCard) = rememberPreference(HideLikedSongsCardKey, false)
    val (hideOfflineCard) = rememberPreference(HideOfflineCardKey, false)
    val (hideCachedCard) = rememberPreference(HideCachedCardKey, false)
    val (hideLocalFilesCard) = rememberPreference(HideLocalFilesCardKey, false)
    val (hideTop50Card) = rememberPreference(HideTop50CardKey, false)
    // Top playlist size (Content settings). "My top 50" routes to
    // `top_playlist/{topSize}` — the route argument is read by
    // TopPlaylistViewModel from SavedStateHandle.
    val (topSize) = rememberPreference(TopSize, "50")

    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    // Spotify playlist count is surfaced on the new Spotify category row
    // (visible only when `showSpotify` is on). `SpotifyLibraryViewModel` is
    // already injected above so the Spotify sync service keeps running; we
    // additionally collect its `playlists` flow here to power the row's
    // count badge.
    val spotifyPlaylists by spotifyLibraryViewModel.playlists.collectAsStateWithLifecycle()
    // `spotifyLibraryViewModel` is still injected as a parameter so the
    // Spotify playlist sync service keeps running (its init block watches
    // the showSpotifyPlaylists preference and pushes playlists into the
    // SpotifyLibraryViewModel state). We no longer render a Spotify row on
    // this redesigned Library overview (the reference doesn't have one),
    // but the view-model remains alive and the Spotify tab in the bottom
    // chip row remains the access point for Spotify playlists.

    // Filter playlists by selected tag (existing behaviour, retained verbatim).
    // Additionally exclude hidden playlists so they don't appear in the
    // Recently Added grid even when the user has tagged or bookmarked them.
    val filteredPlaylistIds by database
        .playlistIdsByTags(
            if (selectedTagIds.isEmpty()) emptyList() else selectedTagIds.toList(),
        ).collectAsStateWithLifecycle(initialValue = emptyList())

    val visiblePlaylists =
        remember(playlists, selectedTagIds, filteredPlaylistIds) {
            playlists.filter { playlist ->
                val name = playlist.playlist.name
                val matchesName = !name.contains("episode", ignoreCase = true)
                val matchesTags = selectedTagIds.isEmpty() || playlist.id in filteredPlaylistIds
                val matchesVisibility = !playlist.playlist.isHidden
                matchesName && matchesTags && matchesVisibility
            }
        }

    val playerAwareBottomPadding =
        LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Bottom)
            .asPaddingValues()
            .calculateBottomPadding() + 12.dp

    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        ExpressivePullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.syncAllLibrary() },
            modifier = Modifier.fillMaxSize(),
            indicatorOffset = LibraryPullToRefreshIndicatorOffset,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        top = LibraryHeaderTopPadding,
                        bottom = playerAwareBottomPadding,
                    ),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                // ── Header: "Library" ─────────────────────────
                // "+" affordance removed per user request (2026-08-28).
                // The big bold "Library" title now lives in the
                // MainActivity shared TopAppBar's title slot for the
                // Library route (per user request 2026-08-28: "The Big
                // library text should be the header of the page. The
                // size should prevail and not become any smaller. And
                // since it'll be the header, there should be no empty
                // space either"). The empty `LibraryHeaderRow` below
                // still reserves its 8.dp vertical padding slot for
                // breathing room between the TopAppBar and the first
                // category row, but no longer renders any text.
                item(key = "library_header", contentType = "header") {
                    LibraryHeaderRow()
                }

                // ── Category rows (Playlists / Spotify / Artists / Favorites / Offline / Cached / Local Files / My Top 50 / History)
                item(key = "library_category_list", contentType = "category_list") {
                    LibraryCategoryList(
                        playlistsCount = visiblePlaylists.size,
                        spotifyCount = spotifyPlaylists.size,
                        artistsCount = artists.size,
                        favoritesCount = likedSongsCount,
                        offlineCount = downloadedSongsCount,
                        localFilesCount = localSongsCount,
                        topSize = topSize,
                        historyCount = historyEventsCount,
                        showSpotify = showSpotify,
                        hideLikedSongs = hideLikedSongsCard,
                        hideOffline = hideOfflineCard,
                        hideCached = hideCachedCard,
                        hideLocalFiles = hideLocalFilesCard,
                        hideTop50 = hideTop50Card,
                        onPlaylistsClick = { navController.navigate("library_playlists") },
                        onSpotifyClick = { navController.navigate("library_spotify_playlists") },
                        onArtistsClick = { navController.navigate("library_artists") },
                        onFavoritesClick = { navController.navigate("auto_playlist/liked") },
                        onOfflineClick = { navController.navigate("auto_playlist/downloaded") },
                        onCachedClick = { navController.navigate("cache_playlist/cached") },
                        onLocalFilesClick = { navController.navigate("local_songs") },
                        onTop50Click = { navController.navigate("top_playlist/$topSize") },
                        onHistoryClick = { navController.navigate("history") },
                    )
                }

                // ── "Recently Added" section header + 2-column grid + "Recently Liked" row ────────────────
                item(key = "recently_added_section", contentType = "recently_added") {
                    RecentlyAddedSection(
                        playlists = visiblePlaylists,
                        recentlyLikedSongs = recentlyLikedSongs,
                        navController = navController,
                        onSeeAll = { navController.navigate("library_playlists") },
                        playerConnection = playerConnection,
                        coroutineScope = coroutineScope,
                        database = database,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Header row: "Library" title + circular "+" action
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LibraryHeaderRow() {
    // The big bold "Library" title has moved to the MainActivity shared
    // TopAppBar's title slot for the Library route (per user request
    // 2026-08-28: "The Big library text should be the header of the page.
    // The size should prevail and not become any smaller. And since it'll
    // be the header, there should be no empty space either"). The
    // TopAppBar is pinned for the Library route (scrollBehavior is `null`,
    // see MainActivity.kt) so the 38sp title stays at full size through
    // scroll — it does not collapse or shrink.
    //
    // This composable is now an empty spacer: it reserves a small vertical
    // padding slot for breathing room between the (now-empty) TopAppBar
    // title's bottom inset and the first category row, but renders no text
    // itself. Keeping the call site (rather than deleting the item
    // entirely) preserves the LazyColumn item keys used for
    // scroll-position restoration across recompositions.
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = LibraryHeaderHorizontalPadding,
                    vertical = 0.dp,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Intentionally empty — the title is in the TopAppBar now.
    }
}

// `LibraryAddCircleButton` was removed per user request (2026-08-28):
// the "+" affordance in the Library header was redundant and visually
// competed with the bold "Library" title. The composable is deleted
// rather than left as dead code so the unused imports below it (Box,
// border, CircleShape, R.drawable.add, R.string.add, clickable,
// MutableInteractionSource, collectIsPressedAsState, animateFloatAsState,
// spring, Spring) get cleaned up by IDE inspection on next refactor.

// ─────────────────────────────────────────────────────────────────────────────
// Category list: Playlists / Spotify / Artists / Favorites / Offline / Cached /
// Local Files / My Top 50 / History. Each row's visibility is gated by the
// corresponding `Hide*CardKey` preference from Appearance → Extras so the
// user can curate which quick-access categories appear on their Library
// overview. Hidden rows are filtered out of `categories` BEFORE the
// divider logic so we never emit a dangling divider after the last visible
// row.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LibraryCategoryList(
    playlistsCount: Int,
    spotifyCount: Int,
    artistsCount: Int,
    favoritesCount: Int,
    offlineCount: Int,
    localFilesCount: Int,
    topSize: String,
    historyCount: Int,
    showSpotify: Boolean,
    hideLikedSongs: Boolean,
    hideOffline: Boolean,
    hideCached: Boolean,
    hideLocalFiles: Boolean,
    hideTop50: Boolean,
    onPlaylistsClick: () -> Unit,
    onSpotifyClick: () -> Unit,
    onArtistsClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    onOfflineClick: () -> Unit,
    onCachedClick: () -> Unit,
    onLocalFilesClick: () -> Unit,
    onTop50Click: () -> Unit,
    onHistoryClick: () -> Unit,
) {
    // The Spotify row is injected between Playlists and Artists when the
    // user has enabled "Show Spotify playlists" in settings. It mirrors
    // the existing category rows (pink line icon + title + chevron) and
    // routes to the Spotify pager tab via [onSpotifyClick].
    //
    // Order matches the original Apple Music reference plus the user's
    // request to surface Offline / Cached / Local Files / My Top 50 as
    // first-class list rows (not just cards in the old grid).
    val categories =
        buildList {
            add(
                LibraryCategory(
                    title = stringResource(R.string.playlists),
                    count = playlistsCount,
                    iconRes = R.drawable.queue_music,
                    onClick = onPlaylistsClick,
                ),
            )
            if (showSpotify) {
                add(
                    LibraryCategory(
                        title = stringResource(R.string.spotify),
                        count = spotifyCount,
                        iconRes = R.drawable.spotify_icon,
                        // Spotify brand green (#1DB954) so the logo reads in
                        // its own colour, matching how the existing
                        // ExpressiveTabChip pill on the Library tab rendered
                        // the Spotify logo in brand colour (not pink-tinted).
                        iconTint = Color(0xFF1DB954),
                        onClick = onSpotifyClick,
                    ),
                )
            }
            add(
                LibraryCategory(
                    title = stringResource(R.string.artists),
                    count = artistsCount,
                    iconRes = R.drawable.person,
                    onClick = onArtistsClick,
                ),
            )
            // Favorites ↔ "Liked Songs" card from the old design.
            if (!hideLikedSongs) {
                add(
                    LibraryCategory(
                        title = stringResource(R.string.favorites),
                        count = favoritesCount,
                        iconRes = R.drawable.favorite,
                        onClick = onFavoritesClick,
                    ),
                )
            }
            // Offline ↔ "Offline / Downloaded" card from the old design.
            // Routes to the same `auto_playlist/downloaded` page as the old
            // card. The label uses `R.string.offline_shortcut` ("Offline")
            // to match the card's title in the prior design.
            if (!hideOffline) {
                add(
                    LibraryCategory(
                        title = stringResource(R.string.offline_shortcut),
                        count = offlineCount,
                        iconRes = R.drawable.offline,
                        onClick = onOfflineClick,
                    ),
                )
            }
            // Cached ↔ "Cached (Instant playback)" card from the old design.
            // Routes to the cache playlist screen which shows songs that
            // have been streamed enough to be cached for instant playback.
            // No count badge is shown — the count is not directly available
            // from the database (it's computed by the CachePlaylistViewModel
            // from the player cache + download cache) and the original card
            // just showed "Instant playback" as the subtitle. We omit the
            // badge here for the same reason — an empty Cached library
            // stays blank, matching History's empty-state.
            if (!hideCached) {
                add(
                    LibraryCategory(
                        title = stringResource(R.string.cached),
                        count = 0,
                        iconRes = R.drawable.cached,
                        onClick = onCachedClick,
                    ),
                )
            }
            // Local Files ↔ "Local Files (On device)" card from the old
            // design. Routes to the local song browser which lists songs
            // the user has imported from device storage.
            if (!hideLocalFiles) {
                add(
                    LibraryCategory(
                        title = stringResource(R.string.local_files),
                        count = localFilesCount,
                        iconRes = R.drawable.snippet_folder,
                        onClick = onLocalFilesClick,
                    ),
                )
            }
            // My Top 50 ↔ "My top 50 (All time)" card from the old design.
            // Routes to the TopPlaylistScreen which shows the user's most
            // played songs for the configured period (default All time).
            // Count badge shows the configured top size (e.g. "50").
            if (!hideTop50) {
                add(
                    LibraryCategory(
                        title = stringResource(R.string.my_top_50),
                        count = topSize.toIntOrNull() ?: 50,
                        iconRes = R.drawable.trending_up,
                        onClick = onTop50Click,
                    ),
                )
            }
            add(
                LibraryCategory(
                    title = stringResource(R.string.history),
                    count = historyCount,
                    iconRes = R.drawable.history,
                    onClick = onHistoryClick,
                ),
            )
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = LibraryHeaderHorizontalPadding)
                .padding(top = 12.dp),
    ) {
        categories.forEachIndexed { index, category ->
            LibraryCategoryRow(category = category)
            // Subtle divider between rows, but NOT after the last row (matches
            // reference: dividers sit BETWEEN rows, not above/below the list).
            if (index < categories.lastIndex) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 48.dp)
                            .height(0.6.dp)
                            .background(
                                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f),
                            ),
                )
            }
        }
    }
}

private data class LibraryCategory(
    val title: String,
    val count: Int,
    val iconRes: Int,
    val onClick: () -> Unit,
    // Optional override for the icon's tint. Defaults to null, which keeps
    // the pink [LibraryAccentColor]. The Spotify row passes the brand green
    // so the Spotify logo reads in its own colour rather than pink-tinted —
    // matching how the existing pill (ExpressiveTabChip) showed the Spotify
    // logo in its brand colour on the Library tab.
    val iconTint: Color? = null,
)

@Composable
private fun LibraryCategoryRow(category: LibraryCategory) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "LibraryCategoryRowScale",
    )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(LibraryCategoryRowHeight)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = category.onClick,
                ).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                painter = painterResource(id = category.iconRes),
                contentDescription = null,
                tint = category.iconTint ?: LibraryAccentColor,
                modifier = Modifier.size(LibraryCategoryIconSize),
            )
            Text(
                text = category.title,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                fontSize = 22.sp,
                letterSpacing = (-0.2).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Count badge — only meaningful counts are shown (matches the
            // reference: an empty History stays blank, not "0").
            if (category.count > 0) {
                Text(
                    text = category.count.toString(),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.50f),
                    fontWeight = FontWeight.Normal,
                    fontSize = 19.sp,
                    maxLines = 1,
                )
            }
            Icon(
                painter = painterResource(id = R.drawable.navigate_next),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.40f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Recently Added section: header with chevron + 2-column playlist artwork
// grid + Recently Liked horizontal song row.
//
// Per user request (2026-08-28): the section now shows BOTH recently added
// playlists AND recently liked songs in sequential order (most recent first).
// The liked songs render as a horizontal scroller of compact song tiles
// below the playlist grid — same horizontal padding rhythm so it visually
// belongs to the same "Recently Added" block. Tapping a liked song tile
// plays it from the user's liked-songs queue starting at that index.
//
// Hidden playlists are filtered upstream in `LibraryMixScreen.visiblePlaylists`
// so they never reach this composable — the previous bug where hidden
// playlists still showed in Recently Added is fixed at the data layer.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RecentlyAddedSection(
    playlists: List<Playlist>,
    recentlyLikedSongs: List<Song>,
    navController: NavController,
    onSeeAll: () -> Unit,
    playerConnection: PlayerConnection,
    coroutineScope: CoroutineScope,
    database: MusicDatabase,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 28.dp),
    ) {
        RecentlyAddedHeader(onSeeAll = onSeeAll)
        RecentlyAddedGrid(
            playlists = playlists,
            navController = navController,
            playerConnection = playerConnection,
            coroutineScope = coroutineScope,
            database = database,
        )
        // Only render the Recently Liked row when the user actually has
        // liked songs. An empty list means the section would just show a
        // header with no content — better to omit it entirely so the
        // Recently Added block ends cleanly at the playlist grid.
        if (recentlyLikedSongs.isNotEmpty()) {
            RecentlyLikedList(
                songs = recentlyLikedSongs,
                playerConnection = playerConnection,
                modifier = Modifier.padding(top = 28.dp),
            )
        }
    }
}

@Composable
private fun RecentlyAddedHeader(onSeeAll: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onSeeAll,
                ).padding(
                    horizontal = LibraryHeaderHorizontalPadding,
                    vertical = 8.dp,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.recently_added),
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            letterSpacing = (-0.3).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            painter = painterResource(id = R.drawable.navigate_next),
            contentDescription = stringResource(R.string.see_all),
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.60f),
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun RecentlyAddedGrid(
    playlists: List<Playlist>,
    navController: NavController,
    playerConnection: PlayerConnection,
    coroutineScope: CoroutineScope,
    database: MusicDatabase,
) {
    // Two-column grid using rows of two items each, exactly matching the
    // reference. Empty / no-artwork playlists fall back to a dark
    // placeholder tile with a centered muted music-note icon (also matching
    // the reference's "no artwork" placeholder).
    val rows: List<List<Playlist>> = playlists.take(8).chunked(2)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = LibraryGridHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(LibraryGridSpacing),
    ) {
        rows.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LibraryGridSpacing),
            ) {
                rowItems.forEach { playlist ->
                    RecentlyAddedGridItem(
                        playlist = playlist,
                        navController = navController,
                        playerConnection = playerConnection,
                        coroutineScope = coroutineScope,
                        database = database,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Pad the trailing row with a transparent spacer so the
                // single-tile row still aligns with the grid column width
                // (matches the reference where an odd-tail row's lone tile
                // keeps the same column width as paired tiles).
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RecentlyAddedGridItem(
    playlist: Playlist,
    navController: NavController,
    playerConnection: PlayerConnection,
    coroutineScope: CoroutineScope,
    database: MusicDatabase,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "RecentlyAddedGridItemScale",
    )

    Column(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        if (!playlist.playlist.isEditable && playlist.songCount == 0 &&
                            playlist.playlist.remoteSongCount != 0
                        ) {
                            navController.navigate("online_playlist/${playlist.playlist.browseId}")
                        } else {
                            navController.navigate("local_playlist/${playlist.id}")
                        }
                    },
                ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
        ) {
            val thumbnailUrl = playlist.thumbnails.getOrNull(0)
            if (thumbnailUrl.isNullOrBlank()) {
                // Dark placeholder + muted music-note icon, matching the
                // reference's empty-artwork tile (e.g. "Anime" in the screenshot).
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(LibraryArtworkCornerRadius))
                            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.music_note),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                        modifier = Modifier.size(44.dp),
                    )
                }
            } else {
                AsyncImage(
                    model = rememberSizedImageRequest(thumbnailUrl, 160.dp, 160.dp),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(LibraryArtworkCornerRadius)),
                )
            }
            // Inline play affordance, kept compact and pinned to the bottom-end
            // so it doesn't cover the artwork's focal point (matches the
            // reference where present-playlist tiles have a small circular play
            // button at bottom-right).
            if (playlist.songCount > 0) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(LibraryAccentColor)
                            .clickable {
                                coroutineScope.launch {
                                    database.playlistSongs(playlist.id).firstOrNull()?.let { songs ->
                                        if (songs.isNotEmpty()) {
                                            playerConnection.playQueue(
                                                ListQueue(items = songs.map { it.song.toMediaItem() }),
                                            )
                                        }
                                    }
                                }
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.play),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = playlist.playlist.name,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text =
                pluralStringResource(
                    R.plurals.n_song,
                    playlist.songCount,
                    playlist.songCount,
                ),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.50f),
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Recently Liked horizontal scroller — compact song tiles.
//
// Rendered below the playlist grid in the "Recently Added" block. Each tile
// shows the song's thumbnail, title, and a one-line artist list. Tapping
// a tile plays the entire liked-songs queue starting at that song so the
// user can pick up exactly where they want in their recently-liked
// sequence. The play affordance matches the playlist grid tiles (pink
// circular play button pinned to the bottom-end of the artwork) so the
// visual rhythm of the Recently Added block stays consistent.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RecentlyLikedList(
    songs: List<Song>,
    playerConnection: PlayerConnection,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Sub-header, visually quieter than the main "Recently Added"
        // header so the parent block remains the dominant section title
        // while the liked-songs scroller reads as a sub-section.
        Text(
            text = stringResource(R.string.recently_liked),
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            letterSpacing = (-0.3).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.padding(
                    horizontal = LibraryHeaderHorizontalPadding,
                    vertical = 4.dp,
                ),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = LibraryGridHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(LibraryGridSpacing),
            verticalAlignment = Alignment.Top,
        ) {
            items(
                items = songs,
                key = { it.id },
                contentType = { "recently_liked_song" },
            ) { song ->
                RecentlyLikedItem(
                    song = song,
                    playerConnection = playerConnection,
                    songs = songs,
                    modifier = Modifier.width(RecentlyLikedTileWidth),
                )
            }
        }
    }
}

// Each liked-song tile is a fixed-width column. Width is sized to mirror
// the playlist grid's half-width (screen_half - grid_padding) so the
// horizontal rhythm matches the grid above; 160dp is a comfortable min
// for two-line titles on most densities and matches the sized image
// request we already pass for playlist thumbnails.
private val RecentlyLikedTileWidth = 160.dp
private val RecentlyLikedArtworkSize = 160.dp

@Composable
private fun RecentlyLikedItem(
    song: Song,
    songs: List<Song>,
    playerConnection: PlayerConnection,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "RecentlyLikedItemScale",
    )

    Column(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        val startIndex = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                        playerConnection.playQueue(
                            ListQueue(
                                title = "Liked Songs",
                                items = songs.map { it.toMediaItem() },
                                startIndex = startIndex,
                            ),
                        )
                    },
                ),
    ) {
        Box(
            modifier =
                Modifier
                    .size(RecentlyLikedArtworkSize)
                    .aspectRatio(1f),
        ) {
            val thumbnailUrl = song.song.thumbnailUrl
            if (thumbnailUrl.isNullOrBlank()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(LibraryArtworkCornerRadius))
                            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.music_note),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                        modifier = Modifier.size(44.dp),
                    )
                }
            } else {
                AsyncImage(
                    model = rememberSizedImageRequest(thumbnailUrl, RecentlyLikedArtworkSize, RecentlyLikedArtworkSize),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(LibraryArtworkCornerRadius)),
                )
            }
            // Inline play affordance pinned to bottom-end, matching the
            // playlist grid tile aesthetic so the two sub-sections read as
            // one coherent "Recently Added" block.
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(LibraryAccentColor)
                        .clickable {
                            val startIndex = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                            playerConnection.playQueue(
                                ListQueue(
                                    title = "Liked Songs",
                                    items = songs.map { it.toMediaItem() },
                                    startIndex = startIndex,
                                ),
                            )
                        },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.play),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = song.song.title,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = song.artists.joinToString(", ") { it.name },
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.50f),
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
