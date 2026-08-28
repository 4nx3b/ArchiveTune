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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
) {
    // Per user request (2026-08-28): "I want the list style of library
    // page, how the playlists, artists and others are displayed exactly
    // like that." and "There's empty spacing in Spotify playlists too.
    // Fix that and the liked songs in Spotify playlists looks a bit
    // faded. Fix it and make it compact." Previously the Spotify
    // playlists sub-page rendered each playlist via
    // `LibraryPlaylistFeatureCard` (a rounded 26dp-corner card with 72dp
    // thumbnail + name + count + Spotify trailing icon). The new
    // compact row mirrors `PlaylistListCard` and `LibraryCategoryRow`:
    //   - 40dp rounded-square thumbnail on the left
    //   - 22sp medium-weight playlist name in the middle
    //   - Song count + chevron on the right (replaces the trailing
    //     Spotify logo — the green Spotify accent was the source of the
    //     "looks a bit faded" complaint because it sat next to the
    //     chevron-less count and visually competed; the chevron alone
    //     reads cleaner).
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

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                // Bumped from 56.dp to 72.dp so the bumped 56.dp thumbnail
                // (was 40.dp) sits with comfortable top/bottom breathing
                // room — matches the shared ListItemHeight constant used
                // by the generic Items.kt rows. Per user request
                // (2026-08-28): "Increase the size of the thumbnails in
                // playlist and Spotify playlists page".
                .height(72.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = openPlaylist,
                ).focusable()
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f),
        ) {
            ItemThumbnail(
                thumbnailUrl = libraryPlaylist.thumbnails.getOrNull(0),
                isActive = false,
                isPlaying = false,
                shape = RoundedCornerShape(8.dp),
                contentScale = ContentScale.Crop,
                showPlaceholder = true,
                // Bumped from 40.dp to 56.dp — matches the shared
                // ListThumbnailSize constant used by SongListItem /
                // AlbumListItem / ArtistListItem / PlaylistListItem in
                // Items.kt. Per user request (2026-08-28): "Increase the
                // size of the thumbnails in playlist and Spotify playlists
                // page".
                modifier = Modifier.size(56.dp),
            )
            Text(
                text = libraryPlaylist.playlist.name,
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
            if (libraryPlaylist.songCount > 0) {
                Text(
                    text = libraryPlaylist.songCount.toString(),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.50f),
                    fontWeight = FontWeight.Normal,
                    fontSize = 19.sp,
                    maxLines = 1,
                )
            }
            Icon(
                painter = painterResource(R.drawable.navigate_next),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.40f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun SpotifyLikedSongsListItem(
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    // Compact row matching the new PlaylistListCard / LibraryCategoryRow
    // style: 40dp rounded-square icon tile on the left, "Liked Songs"
    // title in the middle, chevron on the right. Replaces the previous
    // `LibraryPinnedCollectionTile` (a tall rounded card with a
    // gradient backdrop and accent surface — the "looks a bit faded"
    // complaint was specifically about this tile in the Spotify
    // playlists sub-page, since its gradient muted the pink accent
    // against the surrounding list items).
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "SpotifyLikedSongsRowScale",
    )
    val accentColor = MaterialTheme.colorScheme.primary

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                // Bumped from 56.dp to 72.dp to match the bumped 56dp
                // icon tile (was 40dp) on the left. Per user request
                // (2026-08-28): "Increase the size of the thumbnails in
                // playlist and Spotify playlists page".
                .height(72.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { navController.navigate("spotify_playlist/$SPOTIFY_LIKED_SONGS_ID") },
                ).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f),
        ) {
            Surface(
                color = accentColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp),
                // Bumped from 40.dp to 56.dp to match the bumped playlist
                // row thumbnails above.
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.favorite),
                    contentDescription = null,
                    tint = accentColor,
                    modifier =
                        Modifier
                            .padding(8.dp),
                )
            }
            Text(
                text = stringResource(R.string.liked_songs),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
                fontSize = 22.sp,
                letterSpacing = (-0.2).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            painter = painterResource(R.drawable.navigate_next),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.40f),
            modifier = Modifier.size(20.dp),
        )
    }
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
