/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.search

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.extensions.toMediaMetadata
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.soundcloud.SoundCloudAudioProvider
import moe.rukamori.archivetune.ui.component.EmptyPlaceholder
import moe.rukamori.archivetune.ui.component.ItemThumbnail

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
    var loading by remember(query) { mutableStateOf(true) }
    var resolvingIds by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(query) {
        loading = true
        val results =
            withContext(Dispatchers.IO) {
                runCatching { SoundCloudAudioProvider.searchMetadata(query, 30) }.getOrDefault(emptyList())
            }
        tracks = results
        loading = false
    }

    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (tracks.isEmpty()) {
        EmptyPlaceholder(
            icon = R.drawable.search,
            text = stringResource(R.string.search_no_result),
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(tracks, key = { it.trackId }) { track ->
            val isActive =
                mediaMetadata?.title == track.title &&
                    mediaMetadata?.artists?.firstOrNull()?.name == track.artist
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
        }
    }
}

private suspend fun resolveSoundCloudTrackToMetadata(
    track: SoundCloudAudioProvider.SoundCloudTrackMetadata,
): MediaMetadata? {
    val query = listOf(track.artist, track.title).filter { it.isNotBlank() }.joinToString(" ")
    val result =
        YouTube.search(query, YouTube.SearchFilter.FILTER_SONG, useAccountContext = false).getOrNull()
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
            if (wantedDurationSec != null && candidate.duration > 0) {
                val delta = kotlin.math.abs(candidate.duration - wantedDurationSec)
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
    return best.toMediaMetadata()
}
