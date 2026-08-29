/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.PlaylistSortType
import moe.rukamori.archivetune.db.entities.Playlist
import moe.rukamori.archivetune.spotify.SpotifyLibraryViewModel
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.ui.component.AppleMusicStyleAccentColor
import moe.rukamori.archivetune.ui.component.FrostedHeaderPill
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.utils.backToMain
import androidx.compose.foundation.layout.asPaddingValues

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenPlaylistsScreen(navController: NavController) {
    val database = LocalDatabase.current
    // Per user report (2026-08-29): "If I hide a Spotify playlist it should be
    // available in the hidden playlists section of the account page." The
    // SpotifyLibraryViewModel is @HiltViewModel-scoped to this NavBackStackEntry
    // — it shares the @Singleton repository's hiddenPlaylistIds StateFlow with
    // the LibrarySpotifyPlaylistsScreen, so when the user hides a Spotify
    // playlist there, the same id appears here, and tapping "Unhide" here
    // re-enables it on the Library page on the next screen entry.
    val spotifyLibraryViewModel: SpotifyLibraryViewModel = hiltViewModel()
    val spotifyPlaylists by spotifyLibraryViewModel.playlists.collectAsStateWithLifecycle()
    val hiddenSpotifyPlaylistIds by spotifyLibraryViewModel.hiddenPlaylistIds.collectAsStateWithLifecycle()
    val hiddenSpotifyPlaylists =
        remember(spotifyPlaylists, hiddenSpotifyPlaylistIds) {
            spotifyPlaylists.filter { it.id in hiddenSpotifyPlaylistIds }
        }

    val allPlaylists by database
        .playlists(
            PlaylistSortType.CREATE_DATE,
            descending = true,
        ).collectAsStateWithLifecycle(initialValue = emptyList())

    val hiddenLocalPlaylists = allPlaylists.filter { it.playlist.isHidden }
    val isEmpty = hiddenLocalPlaylists.isEmpty() && hiddenSpotifyPlaylists.isEmpty()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    FrostedHeaderPill(plain = true) {
                        IconButton(
                            onClick = navController::navigateUp,
                            onLongClick = navController::backToMain,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.arrow_back),
                                contentDescription = null,
                            )
                        }
                        Text(
                            text = stringResource(R.string.hidden_playlists),
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val playerAwareBottomPadding =
            LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()
        if (isEmpty) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.visibility_off),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.no_hidden_playlists),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(
                            LocalPlayerAwareWindowInsets.current.only(
                                WindowInsetsSides.Horizontal,
                            ),
                        ),
                contentPadding =
                    PaddingValues(
                        start = 16.dp,
                        top = innerPadding.calculateTopPadding() + 8.dp,
                        end = 16.dp,
                        bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding,
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(hiddenLocalPlaylists, key = { it.id }) { playlist ->
                    HiddenPlaylistCard(
                        playlist = playlist,
                        onUnhide = {
                            database.query {
                                update(playlist.playlist.copy(isHidden = false))
                            }
                        },
                    )
                }
                // Hidden Spotify playlists section — mirrors the local
                // HiddenPlaylistCard visual but pulls data from the
                // @Singleton SpotifyLibraryRepository instead of Room.
                if (hiddenSpotifyPlaylists.isNotEmpty()) {
                    item(key = "spotify_section_header") {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.spotify),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = AppleMusicStyleAccentColor,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                        )
                    }
                    items(
                        hiddenSpotifyPlaylists,
                        key = { "spotify_${it.id}" },
                    ) { playlist ->
                        HiddenSpotifyPlaylistCard(
                            playlist = playlist,
                            onUnhide = { spotifyLibraryViewModel.toggleHiddenPlaylist(playlist.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HiddenPlaylistCard(
    playlist: Playlist,
    onUnhide: () -> Unit,
) {
    val cardShape = RoundedCornerShape(20.dp)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = playlist.thumbnails.getOrNull(0),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.playlist.name,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${playlist.songCount} ${stringResource(R.string.tracks_label)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        FilledTonalButton(
            onClick = onUnhide,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.visibility_off),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.unhide),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun HiddenSpotifyPlaylistCard(
    playlist: SpotifyPlaylist,
    onUnhide: () -> Unit,
) {
    val cardShape = RoundedCornerShape(20.dp)
    val trackCount = playlist.tracks?.total ?: 0

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val coverUrl = playlist.images.firstOrNull()?.url
        if (coverUrl != null) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            Spacer(
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text =
                    if (trackCount > 0) {
                        pluralStringResource(R.plurals.n_song, trackCount, trackCount)
                    } else {
                        stringResource(R.string.tracks_label)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        FilledTonalButton(
            onClick = onUnhide,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.visibility_off),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.unhide),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
