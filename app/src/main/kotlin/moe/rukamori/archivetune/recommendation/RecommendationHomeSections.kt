/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.recommendation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.db.entities.ArtistEntity
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.db.entities.SongEntity
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.ui.component.MenuState
import moe.rukamori.archivetune.ui.component.MenuSectionDivider
import moe.rukamori.archivetune.ui.component.MenuSurfaceSection
import moe.rukamori.archivetune.ui.component.MuzoQuickAction
import moe.rukamori.archivetune.ui.component.MuzoQuickActionRow
import moe.rukamori.archivetune.ui.screens.BitChordPageGutter
import moe.rukamori.archivetune.ui.screens.BitChordSectionHeader
import moe.rukamori.archivetune.ui.screens.BitChordShelfBottomSpacing
import moe.rukamori.archivetune.ui.screens.BitChordShelfCard
import moe.rukamori.archivetune.ui.screens.BitChordShelfCardSpacing

fun RankedRecommendation.toSong(): Song =
    Song(
        song =
            SongEntity(
                id = stats.songId,
                title = stats.title,
                thumbnailUrl = stats.thumbnailUrl,
                albumId = stats.albumId,
                albumName = stats.albumTitle,
            ),
        artists =
            stats.artistIds.mapIndexed { index, artistId ->
                ArtistEntity(
                    id = artistId,
                    name = stats.artistNames.getOrElse(index) { artistId },
                )
            },
    )

@Composable
fun RankedRecommendation.reasonText(): String =
    when (reason) {
        RecReason.TOP_ARTIST ->
            reasonSeed?.takeIf { it.isNotBlank() }?.let { name ->
                stringResource(R.string.rec_reason_top_artist, name)
            } ?: ""

        RecReason.NOVEL_FROM_LIKED_ARTIST ->
            reasonSeed?.let { name ->
                stringResource(R.string.rec_reason_liked_artist, name)
            } ?: ""

        RecReason.SAME_ARTIST_AS_CONTEXT -> stringResource(R.string.rec_reason_similar)
        RecReason.SAME_ALBUM_AS_CONTEXT -> stringResource(R.string.rec_reason_same_album)
        RecReason.FORGOTTEN_FAVORITE -> stringResource(R.string.rec_reason_forgotten)
        RecReason.DISCOVERY_KEYWORD -> stringResource(R.string.rec_reason_discovery)
        RecReason.KEEP_LISTENING_ARTIST -> stringResource(R.string.rec_reason_keep_listening)
    }

@Composable
fun RecommendationShelf(
    title: String,
    items: List<RankedRecommendation>,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    onPlayQueue: (List<Song>, Int) -> Unit,
    onFeedback: (RecommendationAction.Feedback) -> Unit,
    menuState: MenuState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val songs = items.map { it.toSong() }
    Column(modifier) {
        BitChordSectionHeader(title = title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = BitChordPageGutter),
            horizontalArrangement = Arrangement.spacedBy(BitChordShelfCardSpacing),
        ) {
            items(items, key = { "rec_${it.stats.songId}" }) { item ->
                val song = item.toSong()
                val isActive = mediaMetadata?.id == item.stats.songId
                BitChordShelfCard(
                    artworkUrl = item.stats.thumbnailUrl,
                    title = item.stats.title,
                    subtitle = item.stats.artistNames.joinToString { it },
                    isCurrent = isActive,
                    isPlaying = isPlaying,
                    onClick = {
                        val index = songs.indexOfFirst { it.song.id == item.stats.songId }
                        onPlayQueue(songs, index.coerceAtLeast(0))
                    },
                    onLongClick = {
                        menuState.show {
                            RecommendationItemMenu(
                                item = item,
                                onFeedback = onFeedback,
                                onDismiss = menuState::dismiss,
                            )
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(BitChordShelfBottomSpacing))
    }
}

@Composable
fun RecommendationItemMenu(
    item: RankedRecommendation,
    onFeedback: (RecommendationAction.Feedback) -> Unit,
    onDismiss: () -> Unit,
) {
    val quickActions =
        listOf(
            MuzoQuickAction(
                icon = { Icon(painterResource(R.drawable.play), null) },
                label = stringResource(R.string.play),
                onClick = onDismiss,
            ),
        )
    androidx.compose.foundation.lazy.LazyColumn(
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        item { MuzoQuickActionRow(actions = quickActions) }
        item { MenuSectionDivider() }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = item.reasonText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            MenuSurfaceSection {
                val dividerModifier = Modifier.padding(horizontal = 16.dp)
                Column {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.rec_more_like_this)) },
                        leadingContent = { Icon(painterResource(R.drawable.auto_awesome), null) },
                        modifier =
                            Modifier.clickable {
                                onFeedback(
                                    RecommendationAction.Feedback(
                                        songId = item.stats.songId,
                                        artistId = item.stats.artistIds.firstOrNull(),
                                        kind = RecFeedbackKind.MORE_LIKE,
                                    ),
                                )
                                onDismiss()
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    HorizontalDivider(
                        modifier = dividerModifier,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 0.5.dp,
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.rec_not_for_me)) },
                        leadingContent = { Icon(painterResource(R.drawable.close), null) },
                        modifier =
                            Modifier.clickable {
                                onFeedback(
                                    RecommendationAction.Feedback(
                                        songId = item.stats.songId,
                                        artistId = null,
                                        kind = RecFeedbackKind.DISLIKE,
                                    ),
                                )
                                onDismiss()
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    HorizontalDivider(
                        modifier = dividerModifier,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 0.5.dp,
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.rec_dismiss)) },
                        leadingContent = { Icon(painterResource(R.drawable.close), null) },
                        modifier =
                            Modifier.clickable {
                                onFeedback(
                                    RecommendationAction.Feedback(
                                        songId = item.stats.songId,
                                        artistId = null,
                                        kind = RecFeedbackKind.DISMISS,
                                    ),
                                )
                                onDismiss()
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    HorizontalDivider(
                        modifier = dividerModifier,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 0.5.dp,
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.rec_exclude_artist)) },
                        leadingContent = { Icon(painterResource(R.drawable.block), null) },
                        modifier =
                            Modifier.clickable {
                                onFeedback(
                                    RecommendationAction.Feedback(
                                        songId = null,
                                        artistId = item.stats.artistIds.firstOrNull(),
                                        kind = RecFeedbackKind.EXCLUDE_ARTIST,
                                    ),
                                )
                                onDismiss()
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}
