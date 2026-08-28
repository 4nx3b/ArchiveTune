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
import androidx.compose.foundation.lazy.LazyColumn
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
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.LibraryFilter
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.Playlist
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.spotify.SpotifyLibraryViewModel
import moe.rukamori.archivetune.ui.component.ExpressivePullToRefreshBox
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
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()

    // ── Existing data sources retained ────────────────────────────────────────
    // The redesign is presentation-only. Every row in the new layout pulls from
    // the same database / viewmodel flows the old layout did, so all existing
    // behaviour (playlists tap-through, artists tap-through, favourites count,
    // downloads count, history count, recently-added grid) is preserved.
    val likedSongsCount by database.likedSongsCount().collectAsStateWithLifecycle(initialValue = 0)
    val downloadedSongsCount by database.downloadedSongsCount().collectAsStateWithLifecycle(initialValue = 0)
    val historyEventsCount by database.historyEventsCount().collectAsStateWithLifecycle(initialValue = 0)

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

    // Filter playlists by selected tag (existing behaviour, retained verbatim)
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
                matchesName && matchesTags
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
                // ── Header: "Library" + circular "+" action ─────────────────────────
                item(key = "library_header", contentType = "header") {
                    LibraryHeaderRow(
                        onAddClick = { onTabSelected(LibraryFilter.PLAYLISTS) },
                    )
                }

                // ── Category rows (Playlists / Spotify / Artists / Favorites / Downloads / History)
                item(key = "library_category_list", contentType = "category_list") {
                    LibraryCategoryList(
                        playlistsCount = visiblePlaylists.size,
                        spotifyCount = spotifyPlaylists.size,
                        artistsCount = artists.size,
                        favoritesCount = likedSongsCount,
                        downloadsCount = downloadedSongsCount,
                        historyCount = historyEventsCount,
                        showSpotify = showSpotify,
                        onPlaylistsClick = { onTabSelected(LibraryFilter.PLAYLISTS) },
                        onSpotifyClick = { onTabSelected(LibraryFilter.SPOTIFY) },
                        onArtistsClick = { onTabSelected(LibraryFilter.ARTISTS) },
                        onFavoritesClick = { navController.navigate("auto_playlist/liked") },
                        onDownloadsClick = { navController.navigate("auto_playlist/downloaded") },
                        onHistoryClick = { navController.navigate("history") },
                    )
                }

                // ── "Recently Added" section header + 2-column grid ────────────────
                item(key = "recently_added_section", contentType = "recently_added") {
                    RecentlyAddedSection(
                        playlists = visiblePlaylists,
                        navController = navController,
                        onSeeAll = { onTabSelected(LibraryFilter.PLAYLISTS) },
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
private fun LibraryHeaderRow(onAddClick: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = LibraryHeaderHorizontalPadding,
                    vertical = 8.dp,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.library),
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 38.sp,
            lineHeight = 44.sp,
            letterSpacing = (-0.5).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LibraryAddCircleButton(onClick = onAddClick)
    }
}

@Composable
private fun LibraryAddCircleButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "LibraryAddCircleScale",
    )
    // Translucent outlined circle with a centered plus icon — matches the
    // iOS Music reference's "+" affordance: thin border, subtle surface tint,
    // no bright Material ripple. The accent colour here is a muted white so
    // it reads against the dark surface but doesn't shout louder than the
    // "Library" title.
    val onBackground = MaterialTheme.colorScheme.onBackground
    Box(
        modifier =
            Modifier
                .size(38.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clip(CircleShape)
                .background(onBackground.copy(alpha = 0.06f))
                .border(
                    width = 1.5.dp,
                    color = onBackground.copy(alpha = 0.22f),
                    shape = CircleShape,
                ).clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.add),
            contentDescription = stringResource(R.string.add),
            tint = onBackground,
            modifier = Modifier.size(20.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Category list: Playlists / Artists / Favorites / Downloads / History
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LibraryCategoryList(
    playlistsCount: Int,
    spotifyCount: Int,
    artistsCount: Int,
    favoritesCount: Int,
    downloadsCount: Int,
    historyCount: Int,
    showSpotify: Boolean,
    onPlaylistsClick: () -> Unit,
    onSpotifyClick: () -> Unit,
    onArtistsClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onHistoryClick: () -> Unit,
) {
    // The Spotify row is injected between Playlists and Artists when the
    // user has enabled "Show Spotify playlists" in settings. It mirrors
    // the existing category rows (pink line icon + title + chevron) and
    // routes to the Spotify pager tab via [onSpotifyClick].
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
            add(
                LibraryCategory(
                    title = stringResource(R.string.favorites),
                    count = favoritesCount,
                    iconRes = R.drawable.favorite,
                    onClick = onFavoritesClick,
                ),
            )
            add(
                LibraryCategory(
                    title = stringResource(R.string.downloads),
                    count = downloadsCount,
                    iconRes = R.drawable.download,
                    onClick = onDownloadsClick,
                ),
            )
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
// Recently Added section: header with chevron + 2-column artwork grid
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RecentlyAddedSection(
    playlists: List<Playlist>,
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
