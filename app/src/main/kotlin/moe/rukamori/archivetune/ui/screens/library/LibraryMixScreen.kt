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
import moe.rukamori.archivetune.spotify.SPOTIFY_LIKED_SONGS_ID
import moe.rukamori.archivetune.constants.LibrarySource
import moe.rukamori.archivetune.constants.HideAiMixKey
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

private val LibraryHeaderTopPadding = 12.dp
private val LibraryHeaderHorizontalPadding = 20.dp
private val LibraryCategoryRowHeight = 56.dp
private val LibraryCategoryIconSize = 28.dp
private val LibraryGridSpacing = 14.dp
private val LibraryGridHorizontalPadding = 20.dp
private val LibraryArtworkCornerRadius = 10.dp

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

    val likedSongsCount by database.likedSongsCount().collectAsStateWithLifecycle(initialValue = 0)

    val downloadsMap by downloadUtil.downloads.collectAsStateWithLifecycle()
    val downloadedSongsCount = remember(downloadsMap) {
        downloadsMap.values.count { it.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED }
    }
    val historyEventsCount by database.historyEventsCount().collectAsStateWithLifecycle(initialValue = 0)
    val localSongsCount by database
        .localSongs()
        .map { it.size }
        .collectAsStateWithLifecycle(initialValue = 0)

    val recentlyLikedSongs by database
        .likedSongs(SongSortType.CREATE_DATE, descending = true)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val (hideLikedSongsCard) = rememberPreference(HideLikedSongsCardKey, false)
    val (hideOfflineCard) = rememberPreference(HideOfflineCardKey, false)
    val (hideCachedCard) = rememberPreference(HideCachedCardKey, false)
    val (hideLocalFilesCard) = rememberPreference(HideLocalFilesCardKey, false)
    val (hideTop50Card) = rememberPreference(HideTop50CardKey, false)

    val (topSize) = rememberPreference(TopSize, "50")

    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    val spotifyPlaylists by spotifyLibraryViewModel.playlists.collectAsStateWithLifecycle()

    // Which service the library sections are showing. The Liked songs card follows it —
    // the count only means something for the local library, so Spotify's is named instead.
    val librarySource = rememberLibrarySource()

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

    val playerAwareTopPadding =
        LocalPlayerAwareWindowInsets.current
            .asPaddingValues()
            .calculateTopPadding()

    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        ExpressivePullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.syncAllLibrary() },
            modifier = Modifier.fillMaxSize(),

        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        top = playerAwareTopPadding + LibraryHeaderTopPadding,
                        bottom = playerAwareBottomPadding,
                    ),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {

                item(key = "library_header", contentType = "header") {
                    LibraryHeaderRow()
                }

                // The Library's source pills, above the shortcut cards they steer. Renders nothing
                // without a usable Spotify session.
                item(key = "library_source_pills", contentType = "library_source_pills") {
                    LibrarySourcePills()
                }

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
                        likedOnSpotify = librarySource == LibrarySource.SPOTIFY,
                        onPlaylistsClick = { navController.navigate("library_playlists") },
                        onSpotifyClick = { navController.navigate("library_spotify_playlists") },
                        onArtistsClick = { navController.navigate("library_artists") },
                        onFavoritesClick = {
                            if (librarySource == LibrarySource.SPOTIFY) {
                                navController.navigate("spotify_playlist/$SPOTIFY_LIKED_SONGS_ID")
                            } else {
                                navController.navigate("auto_playlist/liked")
                            }
                        },
                        onOfflineClick = { navController.navigate("auto_playlist/downloaded") },
                        onCachedClick = { navController.navigate("cache_playlist/cached") },
                        onLocalFilesClick = { navController.navigate("local_songs") },
                        onTop50Click = { navController.navigate("top_playlist/$topSize") },
                        onHistoryClick = { navController.navigate("history") },
                    )
                }

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

@Composable
private fun LibraryHeaderRow() {

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

    }
}

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
    likedOnSpotify: Boolean,
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

            if (!hideLikedSongs) {
                add(
                    LibraryCategory(
                        title = stringResource(R.string.favorites),
                        // Spotify's liked songs are a remote list — the local count is meaningless
                        // there, so the row names the source instead of counting it.
                        count = if (likedOnSpotify) -1 else favoritesCount,
                        countLabel =
                            if (likedOnSpotify) {
                                stringResource(R.string.home_source_spotify)
                            } else {
                                null
                            },
                        iconRes = R.drawable.favorite,
                        onClick = onFavoritesClick,
                    ),
                )
            }

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

    val iconTint: Color? = null,
    val countLabel: String? = null,
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

            if (category.countLabel != null) {
                Text(
                    text = category.countLabel,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.50f),
                    fontWeight = FontWeight.Normal,
                    fontSize = 19.sp,
                    maxLines = 1,
                )
            } else if (category.count > 0) {
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

@Composable
private fun RecentlyLikedList(
    songs: List<Song>,
    playerConnection: PlayerConnection,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {

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
