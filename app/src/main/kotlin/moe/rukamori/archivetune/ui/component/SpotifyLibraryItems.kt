/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.ListThumbnailSize
import moe.rukamori.archivetune.constants.ThumbnailCornerRadius
import moe.rukamori.archivetune.db.entities.Playlist
import moe.rukamori.archivetune.db.entities.PlaylistEntity
import moe.rukamori.archivetune.spotify.SpotifyMapper
import moe.rukamori.archivetune.spotify.SPOTIFY_LIKED_SONGS_ID
import moe.rukamori.archivetune.spotify.models.SpotifyPlaylist
import moe.rukamori.archivetune.spotify.models.SpotifyTrack
import moe.rukamori.archivetune.ui.utils.resize
import moe.rukamori.archivetune.utils.joinByBullet
import moe.rukamori.archivetune.utils.makeTimeString

@Composable
fun SpotifyLibraryPlaylistListItem(
    playlist: SpotifyPlaylist,
    navController: NavController,
    onMenuClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
) {

    val libraryPlaylist = remember(playlist) { playlist.toLibraryPlaylist() }
    val openPlaylist = {
        navController.navigate("spotify_playlist/${playlist.id}")
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "SpotifyPlaylistListRowScale",
    )

    val subtitleText =
        if (libraryPlaylist.songCount > 0) {
            pluralStringResource(
                R.plurals.n_song,
                libraryPlaylist.songCount,
                libraryPlaylist.songCount,
            )
        } else {
            null
        }

    ListItem(
        title = libraryPlaylist.playlist.name,
        subtitle = subtitleText,
        badges = {},
        thumbnailContent = {
            ItemThumbnail(
                thumbnailUrl = libraryPlaylist.thumbnails.getOrNull(0),
                isActive = false,
                isPlaying = false,
                shape = RoundedCornerShape(ThumbnailCornerRadius),
                contentScale = ContentScale.Crop,
                showPlaceholder = true,

                modifier = Modifier.size(ListThumbnailSize),
            )
        },
        trailingContent = {
            if (onMenuClick != null) {
                IconButton(onClick = onMenuClick) {
                    Icon(
                        painter = painterResource(R.drawable.more_vert),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                painter = painterResource(R.drawable.navigate_next),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f),
                modifier = Modifier.size(20.dp),
            )
        },
        modifier =
            modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = openPlaylist,
                ).focusable(),
    )
}

@Composable
fun SpotifyLikedSongsListItem(
    navController: NavController,
    modifier: Modifier = Modifier,
) {

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "SpotifyLikedSongsRowScale",
    )
    val accentColor = MaterialTheme.colorScheme.primary

    ListItem(
        title = stringResource(R.string.liked_songs),
        subtitle = null,
        badges = {},
        thumbnailContent = {
            Surface(
                color = accentColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(ThumbnailCornerRadius),
                modifier = Modifier.size(ListThumbnailSize),
            ) {
                Icon(
                    painter = painterResource(R.drawable.favorite),
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.padding(8.dp),
                )
            }
        },
        trailingContent = {
            Icon(
                painter = painterResource(R.drawable.navigate_next),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f),
                modifier = Modifier.size(20.dp),
            )
        },
        modifier =
            modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { navController.navigate("spotify_playlist/$SPOTIFY_LIKED_SONGS_ID") },
                ).focusable(),
    )
}

@Composable
fun SpotifyTrackListItem(
    track: SpotifyTrack,
    modifier: Modifier = Modifier,
    albumIndex: Int? = null,
    badges: @Composable RowScope.() -> Unit = {
        if (track.explicit) {
            Icon(
                painter = painterResource(R.drawable.explicit),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    },
    isSelected: Boolean = false,
    isActive: Boolean = false,
    isPlaying: Boolean = false,
    showSongIconPlaceholder: Boolean = true,
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    val duration =
        track.durationMs
            .takeIf { it > 0 }
            ?.toLong()
            ?.let(::makeTimeString)
    val subtitle =
        joinByBullet(
            track.artists.joinToString { it.name },
            duration,
        )

    ListItem(
        title = track.name,
        subtitle = subtitle,
        badges = badges,
        thumbnailContent = {
            ItemThumbnail(
                thumbnailUrl = SpotifyMapper.getTrackThumbnail(track)?.resize(200, 200),
                albumIndex = albumIndex,
                isSelected = isSelected,
                isActive = isActive,
                isPlaying = isPlaying,
                shape = RoundedCornerShape(ThumbnailCornerRadius),
                placeholderIconRes = if (showSongIconPlaceholder) R.drawable.music_note else null,
                modifier = Modifier.size(ListThumbnailSize),
            )
        },
        trailingContent = trailingContent,
        modifier = modifier,
        isActive = isActive,
    )
}

private fun SpotifyPlaylist.toLibraryPlaylist(): Playlist =
    Playlist(
        playlist =
            PlaylistEntity(
                id = "SPOTIFY_PLAYLIST_$id",
                name = name,
                thumbnailUrl = SpotifyMapper.getPlaylistThumbnail(this),
                remoteSongCount = tracks?.total ?: 0,
                isEditable = false,
            ),
        songCount = tracks?.total ?: 0,
        songThumbnails = images.map { it.url },
    )
