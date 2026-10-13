/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.search

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.playback.PlayerConnection
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.constants.SearchProvider
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.soundcloud.SoundCloudAudioProvider
import moe.rukamori.archivetune.ui.component.EmptyPlaceholder
import moe.rukamori.archivetune.ui.component.ItemThumbnail
import moe.rukamori.archivetune.utils.joinByBullet
import moe.rukamori.archivetune.ui.screens.ScreenHeaderHaze
import moe.rukamori.archivetune.ui.utils.backToMain

private enum class SoundCloudSearchFilter {
    ALL,
    TRACKS,
    ARTISTS,
    ALBUMS,
}

@Composable
internal fun SoundCloudOnlineSearchResult(
    navController: NavController,
    query: String,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current
    val mediaMetadata by playerConnection?.mediaMetadata?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf<MediaMetadata?>(null) }
    val isPlaying by playerConnection?.isPlaying?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(false) }

    var tracks by remember(query) { mutableStateOf<List<SoundCloudAudioProvider.SoundCloudTrackMetadata>>(emptyList()) }
    var users by remember(query) { mutableStateOf<List<SoundCloudAudioProvider.SoundCloudUserResult>>(emptyList()) }
    var playlists by remember(query) { mutableStateOf<List<SoundCloudAudioProvider.SoundCloudPlaylistResult>>(emptyList()) }
    var loading by remember(query) { mutableStateOf(true) }
    var resolvingIds by remember { mutableStateOf(setOf<String>()) }
    var filter by rememberSaveable { mutableStateOf(SoundCloudSearchFilter.ALL) }
    var fieldQuery by rememberSaveable(query) { mutableStateOf(query) }
    val lazyListState = rememberLazyListState()

    val albums =
        remember(playlists) {
            val albumOnly = playlists.filter { it.isAlbum }
            if (albumOnly.size >= 3) albumOnly else playlists
        }

    LaunchedEffect(query) {
        loading = true
        val (trackResults, userResults, playlistResults) =
            withContext(Dispatchers.IO) {
                val trackResults =
                    async { runCatching { SoundCloudAudioProvider.searchMetadata(query, 30) }.getOrDefault(emptyList()) }
                val userResults =
                    async { runCatching { SoundCloudAudioProvider.searchUsers(query, 20) }.getOrDefault(emptyList()) }
                val playlistResults =
                    async { runCatching { SoundCloudAudioProvider.searchPlaylists(query, 20) }.getOrDefault(emptyList()) }
                Triple(trackResults.await(), userResults.await(), playlistResults.await())
            }
        tracks = trackResults
        users = userResults
        playlists = playlistResults
        loading = false
    }

    val barState = rememberSearchResultsBarState()
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current
    val playerAwareBottomPadding =
        LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Bottom)
            .asPaddingValues()
            .calculateBottomPadding()

    val header: @Composable () -> Unit = {
        SearchResultsTopHeader(
            query = query,
            onBack = { navController.navigateUp() },
            onBackLongClick = { navController.backToMain() },
            chipsRow = {
                SolidFilterChipsRow(
                    chips =
                        listOf(
                            SoundCloudSearchFilter.ALL to stringResource(R.string.filter_all),
                            SoundCloudSearchFilter.TRACKS to stringResource(R.string.filter_songs),
                            SoundCloudSearchFilter.ARTISTS to stringResource(R.string.filter_artists),
                            SoundCloudSearchFilter.ALBUMS to stringResource(R.string.filter_albums),
                        ),
                    currentValue = filter,
                    onValueUpdate = { filter = it },
                )
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .searchResultsBarSource(barState),
        ) {
            when {
                loading -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(top = systemBarsTopPadding + 4.dp),
                    ) {
                        header()
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }

                tracks.isEmpty() -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(top = systemBarsTopPadding + 4.dp),
                    ) {
                        header()
                        EmptyPlaceholder(
                            icon = R.drawable.search,
                            text = stringResource(R.string.no_results_found),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        state = lazyListState,
                        contentPadding =
                            LocalPlayerAwareWindowInsets.current
                                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                                .add(WindowInsets(top = systemBarsTopPadding + 4.dp))
                                .add(WindowInsets(bottom = SearchResultsOverlayReserve))
                                .asPaddingValues(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        item(key = "results_header", contentType = "results_header") {
                            header()
                        }
                        item(key = "soundcloud_result_label") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.search_source_soundcloud),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        if (filter == SoundCloudSearchFilter.ARTISTS) {
                            itemsIndexed(
                                items = users,
                                key = { _, user -> "sc_user_${user.userId}" },
                                contentType = { _, _ -> SoundCloudAudioProvider.SoundCloudUserResult::class },
                            ) { _, user ->
                                SoundCloudArtistRow(user = user)
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                )
                            }
                        }
                        if (filter == SoundCloudSearchFilter.ALBUMS) {
                            itemsIndexed(
                                items = albums,
                                key = { _, playlist -> "sc_playlist_${playlist.playlistId}" },
                                contentType = { _, _ -> SoundCloudAudioProvider.SoundCloudPlaylistResult::class },
                            ) { _, playlist ->
                                SoundCloudPlaylistRow(
                                    playlist = playlist,
                                    playerConnection = playerConnection,
                                    coroutineScope = coroutineScope,
                                    onResolvingChange = { id, active ->
                                        resolvingIds =
                                            if (active) {
                                                resolvingIds + id
                                            } else {
                                                resolvingIds - id
                                            }
                                    },
                                )
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                )
                            }
                        }
                        itemsIndexed(
                            items = tracks,
                            key = { _, track -> track.trackId },
                            contentType = { _, _ -> SoundCloudAudioProvider.SoundCloudTrackMetadata::class },
                        ) { _, track ->
                            val isActive =
                                mediaMetadata?.soundCloudUrl == track.permalinkUrl ||
                                    (
                                        mediaMetadata?.title == track.title &&
                                            mediaMetadata?.artists?.firstOrNull()?.name == track.artist
                                    )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (isActive && playerConnection != null) {
                                                playerConnection.player.togglePlayPause()
                                            } else if (playerConnection != null && track.trackId !in resolvingIds) {
                                                resolvingIds = resolvingIds + track.trackId
                                                coroutineScope.launch {
                                                    try {
                                                        val metadata =
                                                            withContext(Dispatchers.IO) {
                                                                resolveSoundCloudTrackToMetadata(track)
                                                            }
                                                        if (metadata != null) {
                                                            playerConnection.playQueue(YouTubeQueue.radio(metadata))
                                                        } else {
                                                            Toast.makeText(
                                                                context,
                                                                context.getString(R.string.no_results_found),
                                                                Toast.LENGTH_SHORT,
                                                            ).show()
                                                        }
                                                    } finally {
                                                        resolvingIds = resolvingIds - track.trackId
                                                    }
                                                }
                                            }
                                        },
                            ) {
                                ItemThumbnail(
                                    thumbnailUrl = track.artworkUrl,
                                    isActive = isActive,
                                    isPlaying = isPlaying,
                                    shape = RoundedCornerShape(8.dp),
                                    placeholderIconRes = R.drawable.album,
                                    modifier =
                                        Modifier
                                            .padding(start = 12.dp)
                                            .size(48.dp),
                                )
                                Column(
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .padding(vertical = 8.dp, horizontal = 12.dp),
                                ) {
                                    Text(
                                        text = track.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = track.artist,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (track.trackId in resolvingIds) {
                                    CircularProgressIndicator(
                                        modifier =
                                            Modifier
                                                .padding(end = 16.dp)
                                                .size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(R.drawable.solar_music_note_2_linear),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier =
                                            Modifier
                                                .padding(end = 16.dp)
                                                .size(18.dp),
                                    )
                                }
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            )
                        }
                    }
                }
            }
        }

        ScreenHeaderHaze(
            hazeState = barState.haze,
            systemBarsTopPadding = systemBarsTopPadding + 4.dp,
            scrolled = lazyListState.canScrollBackward,
        )

        SearchResultsBottomOverlay(
            state = barState,
            query = fieldQuery,
            onQueryChange = { fieldQuery = it },
            onSearch = { text ->
                if (text.isNotBlank()) {
                    val replacementRoute = onlineSearchResultRoute(text, SearchProvider.SOUNDCLOUD)
                    val currentDestinationId = navController.currentDestination?.id
                    if (currentDestinationId != null) {
                        navController.navigate(replacementRoute) {
                            popUpTo(currentDestinationId) { inclusive = true }
                            launchSingleTop = true
                        }
                    } else {
                        navController.navigate(replacementRoute)
                    }
                }
            },
            onBack = { navController.navigateUp() },
            onBackLongClick = { navController.backToMain() },
            placeholder = stringResource(R.string.search_source_soundcloud),
            bottomPadding = playerAwareBottomPadding,
        )
    }
}

@Composable
private fun SoundCloudArtistRow(user: SoundCloudAudioProvider.SoundCloudUserResult) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(user.permalinkUrl),
                            ),
                        )
                    }
                },
    ) {
        ItemThumbnail(
            thumbnailUrl = user.avatarUrl,
            isActive = false,
            isPlaying = false,
            shape = androidx.compose.foundation.shape.CircleShape,
            placeholderIconRes = R.drawable.artist,
            modifier =
                Modifier
                    .padding(start = 12.dp)
                    .size(48.dp),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp, horizontal = 12.dp),
        ) {
            Text(
                text = user.username,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = joinByBullet(
                    user.trackCount?.takeIf { it > 0 }?.let { count -> "$count tracks" },
                    if (user.isVerified) stringResource(R.string.soundcloud_verified_artist) else null,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            painter = painterResource(R.drawable.search),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .padding(end = 16.dp)
                    .size(18.dp),
        )
    }
}

@Composable
private fun SoundCloudPlaylistRow(
    playlist: SoundCloudAudioProvider.SoundCloudPlaylistResult,
    playerConnection: PlayerConnection?,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onResolvingChange: (String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var resolving by remember(playlist.playlistId) { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable {
                    if (!resolving) {
                        if (playlist.tracks.isNotEmpty() && playerConnection != null) {
                            resolving = true
                            onResolvingChange(playlist.playlistId, true)
                            coroutineScope.launch {
                                try {
                                    val resolved =
                                        withContext(Dispatchers.IO) {
                                            playlist.tracks.mapNotNull { resolveSoundCloudTrackToMetadata(it) }
                                        }
                                    if (resolved.isNotEmpty()) {
                                        playerConnection.playQueue(
                                            ListQueue(
                                                title = playlist.title,
                                                items = resolved.map { it.toMediaItem() },
                                            ),
                                        )
                                    } else {
                                        openSoundCloudExternal(context, playlist.permalinkUrl)
                                    }
                                } finally {
                                    resolving = false
                                    onResolvingChange(playlist.playlistId, false)
                                }
                            }
                        } else {
                            openSoundCloudExternal(context, playlist.permalinkUrl)
                        }
                    }
                },
    ) {
        ItemThumbnail(
            thumbnailUrl = playlist.artworkUrl,
            isActive = false,
            isPlaying = false,
            shape = RoundedCornerShape(8.dp),
            placeholderIconRes = R.drawable.album,
            modifier =
                Modifier
                    .padding(start = 12.dp)
                    .size(48.dp),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp, horizontal = 12.dp),
        ) {
            Text(
                text = playlist.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = joinByBullet(
                    if (playlist.isAlbum) stringResource(R.string.filter_albums) else stringResource(R.string.filter_playlists),
                    "${playlist.trackCount} tracks",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (resolving) {
            CircularProgressIndicator(
                modifier =
                    Modifier
                        .padding(end = 16.dp)
                        .size(18.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.solar_music_note_2_linear),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .padding(end = 16.dp)
                        .size(18.dp),
            )
        }
    }
}

private fun openSoundCloudExternal(
    context: android.content.Context,
    url: String,
) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
        )
    }
}

private suspend fun resolveSoundCloudTrackToMetadata(
    track: SoundCloudAudioProvider.SoundCloudTrackMetadata,
): MediaMetadata? {
    val query = listOf(track.artist, track.title).filter { it.isNotBlank() }.joinToString(" ")
    val result =
        moe.rukamori.archivetune.innertube.YouTube.search(
            query,
            moe.rukamori.archivetune.innertube.YouTube.SearchFilter.FILTER_SONG,
            useAccountContext = false,
        ).getOrNull()
    val candidates = result?.items.orEmpty().filterIsInstance<SongItem>()
    if (candidates.isEmpty()) return null
    val wantedDurationSec = track.durationMs?.let { it / 1000 }
    val best =
        candidates.maxByOrNull { candidate ->
            var score = 0
            if (candidate.title.equals(track.title, ignoreCase = true)) {
                score += 4
            } else if (candidate.title.contains(track.title, ignoreCase = true)) {
                score += 2
            }
            val candidateArtists = candidate.artists.joinToString(" ") { it.name }
            if (track.artist.isNotBlank() && candidateArtists.contains(track.artist, ignoreCase = true)) {
                score += 3
            }
            val candidateDuration = candidate.duration
            if (wantedDurationSec != null && candidateDuration != null && candidateDuration > 0) {
                val delta = kotlin.math.abs(candidateDuration - wantedDurationSec)
                score +=
                    when {
                        delta <= 2 -> 3
                        delta <= 5 -> 1
                        delta > 15 -> -4
                        else -> 0
                    }
            }
            score
        } ?: return null
    val soundCloudDurationSec = track.durationMs?.let { (it / 1000).toInt() }?.takeIf { it > 0 }
    return best.toMediaMetadata().copy(
        title = track.title,
        artists = listOf(
            MediaMetadata.Artist(
                id = null,
                name = track.artist,
            ),
        ),
        duration = soundCloudDurationSec ?: (best.duration ?: -1),
        thumbnailUrl = track.artworkUrl ?: best.thumbnail,
        album = null,
        preferredSource = AudioSourceType.SOUNDCLOUD.name,
        soundCloudUrl = track.permalinkUrl,
    )
}
