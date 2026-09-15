/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.search

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.applemusic.AppleMusicPlaybackResolver
import moe.rukamori.archivetune.applemusic.AppleMusicSearchItem
import moe.rukamori.archivetune.constants.AppBarHeight
import moe.rukamori.archivetune.constants.ListThumbnailSize
import moe.rukamori.archivetune.constants.ThumbnailCornerRadius
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.ui.component.EmptyPlaceholder
import moe.rukamori.archivetune.ui.component.ItemThumbnail
import moe.rukamori.archivetune.ui.component.ListItem
import moe.rukamori.archivetune.utils.joinByBullet
import moe.rukamori.archivetune.utils.makeTimeString
import moe.rukamori.archivetune.viewmodels.AmazonSearchViewModel

/**
 * Results page for SearchProvider.AMAZON — the Amazon twin of [AppleMusicOnlineSearchResult].
 * Amazon's catalogue API only returns tracks, so there are no album/artist filter chips here, and
 * tapping a track resolves it exactly the way an Apple Music result does: a YouTube title/artist
 * text search through [AppleMusicPlaybackResolver] (Amazon streams are CENC-protected and this
 * fork ships no decryption step, so there is no direct playback path).
 */
@Composable
internal fun AmazonOnlineSearchResult(
    navController: NavController,
    viewModel: AmazonSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding())
                    .padding(top = AppBarHeight),
        ) {
            // No filter chips: the Amazon catalogue search returns tracks only.
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.search_amazon),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        when {
            state.isLoading && state.items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.errorMessage != null && state.items.isEmpty() -> {
                EmptyPlaceholder(
                    icon = R.drawable.ic_music,
                    text = state.errorMessage ?: stringResource(R.string.no_results_found),
                    modifier = Modifier.fillMaxSize(),
                )
            }

            state.items.isEmpty() -> {
                EmptyPlaceholder(
                    icon = R.drawable.search,
                    text = stringResource(R.string.no_results_found),
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                LazyColumn(
                    contentPadding =
                        LocalPlayerAwareWindowInsets.current
                            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                            .add(WindowInsets(top = 8.dp))
                            .asPaddingValues(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    itemsIndexed(
                        items = state.items,
                        key = { _, item -> item.key },
                        contentType = { _, item -> item::class },
                    ) { _, item ->
                        AmazonSearchResultRow(
                            item = item,
                            playerConnection = playerConnection,
                            coroutineScope = coroutineScope,
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AmazonSearchResultRow(
    item: AppleMusicSearchItem.Track,
    playerConnection: moe.rukamori.archivetune.playback.PlayerConnection?,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
) {
    val context = LocalContext.current
    var resolving by remember(item.key) { mutableStateOf(false) }

    val onClick: () -> Unit = {
        if (playerConnection != null && !resolving) {
            resolving = true
            coroutineScope.launch {
                try {
                    // Same resolution path an Apple Music result takes: YouTube title/artist
                    // text search — there is no direct Amazon stream to play (CENC).
                    val song =
                        withContext(Dispatchers.IO) {
                            AppleMusicPlaybackResolver.resolveTrack(item)
                        }
                    if (song != null) {
                        playerConnection.playQueue(YouTubeQueue.radio(song.toMediaMetadata()))
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(R.string.amazon_track_unavailable),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                } finally {
                    resolving = false
                }
            }
        }
    }

    AmazonSearchItemRow(
        item = item,
        onClick = onClick,
        trailingContent = {
            if (resolving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        },
    )
}

/**
 * The Amazon twin of [AppleMusicItemRow] — same shape (title / artist bullet duration / artwork
 * thumbnail), Amazon stand-in icon and label. Also used by OnlineSearchScreen's suggestion
 * section, where tapping a suggestion simply fills the search field with "artist title".
 */
@Composable
internal fun AmazonSearchItemRow(
    item: AppleMusicSearchItem,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    val subtitle =
        when (item) {
            is AppleMusicSearchItem.Track ->
                joinByBullet(
                    item.artist,
                    item.durationMs.takeIf { it > 0 }?.let(::makeTimeString),
                )

            is AppleMusicSearchItem.Album ->
                joinByBullet(
                    item.artist,
                    item.releaseYear,
                    item.trackCount.takeIf { it > 0 }?.let { count -> "$count tracks" },
                )

            is AppleMusicSearchItem.Artist -> item.genre
        }

    val rowModifier =
        if (onClick != null) {
            modifier.clickable(onClick = onClick)
        } else {
            modifier
        }

    ListItem(
        title = item.title,
        subtitle = subtitle,
        thumbnailContent = {
            ItemThumbnail(
                thumbnailUrl = item.artworkUrl,
                isActive = false,
                isPlaying = false,
                shape =
                    when (item) {
                        is AppleMusicSearchItem.Artist -> androidx.compose.foundation.shape.CircleShape
                        else -> RoundedCornerShape(ThumbnailCornerRadius)
                    },
                placeholderIconRes =
                    when (item) {
                        is AppleMusicSearchItem.Track -> R.drawable.music_note
                        is AppleMusicSearchItem.Album -> R.drawable.album
                        is AppleMusicSearchItem.Artist -> R.drawable.person
                    },
                modifier = Modifier.size(ListThumbnailSize),
            )
        },
        trailingContent = {
            trailingContent()
            Icon(
                painter = painterResource(R.drawable.ic_music),
                contentDescription = stringResource(R.string.source_amazon),
                modifier = Modifier.size(18.dp),
            )
        },
        modifier = rowModifier,
    )
}
