/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.YouTubeListItem
import moe.rukamori.archivetune.constants.SearchProvider
import moe.rukamori.archivetune.viewmodels.OnlineSearchSuggestionViewModel
import moe.rukamori.archivetune.ui.screens.search.SpotifySearchItemRow
import moe.rukamori.archivetune.ui.screens.search.queryText
import moe.rukamori.archivetune.applemusic.queryText as appleMusicQueryText
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import moe.rukamori.archivetune.innertube.models.*
import moe.rukamori.archivetune.ui.menu.*

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OnlineSearchScreen(
    query: String,
    onQueryChange: (TextFieldValue) -> Unit,
    navController: NavController,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
    pureBlack: Boolean,
    searchProvider: SearchProvider = SearchProvider.YOUTUBE,
    viewModel: OnlineSearchSuggestionViewModel = hiltViewModel(),
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return

    val haptic = LocalHapticFeedback.current
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()

    val coroutineScope = rememberCoroutineScope()
    val viewState by viewModel.viewState.collectAsStateWithLifecycle()

    val lazyListState = rememberLazyListState()

    LaunchedEffect(Unit) {
        snapshotFlow { lazyListState.firstVisibleItemScrollOffset }
            .drop(1)
            .collect {
                keyboardController?.hide()
            }
    }

    LaunchedEffect(query, searchProvider) {
        viewModel.updateProvider(searchProvider)
        viewModel.updateQuery(query)
    }

    val backgroundColor = if (pureBlack) Color.Black else MaterialTheme.colorScheme.background
    val distinctResultItems = remember(viewState.items) { viewState.items.distinctBy { it.id } }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(backgroundColor),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            state = lazyListState,
            contentPadding =
                PaddingValues(
                    top = maxOf(12.dp, WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()),
                    bottom =
                        WindowInsets.systemBars
                            .only(WindowInsetsSides.Bottom)
                            .asPaddingValues()
                            .calculateBottomPadding(),
                ),
            verticalArrangement = Arrangement.spacedBy(SearchRowSpacing),
            modifier =
                Modifier
                    .widthIn(max = SearchContentMaxWidth)
                    .fillMaxSize(),
        ) {
            if (viewState.history.isNotEmpty()) {
                item(
                    key = "history_header",
                    contentType = "section_header",
                ) {
                    SearchSectionHeader(
                        title = stringResource(R.string.search_history),
                        pureBlack = pureBlack,
                        modifier = Modifier.animateItem(),
                    )
                }

                itemsIndexed(
                    items = viewState.history,
                    key = { _, history -> "history_${history.query}" },
                    contentType = { _, _ -> "history" },
                ) { index, history ->
                    SuggestionItem(
                        query = history.query,
                        online = false,
                        onClick = {
                            onSearch(history.query)
                            onDismiss()
                        },
                        onDelete = {
                            viewModel.deleteHistory(history)
                        },
                        onFillTextField = {
                            onQueryChange(TextFieldValue(history.query, TextRange(history.query.length)))
                        },
                        showDivider = index < viewState.history.lastIndex,
                        modifier = Modifier.animateItem(),
                        pureBlack = pureBlack,
                    )
                }
            }

            if (viewState.suggestions.isNotEmpty()) {
                item(
                    key = "suggestions_header",
                    contentType = "section_header",
                ) {
                    SearchSectionHeader(
                        title = stringResource(R.string.suggestions),
                        pureBlack = pureBlack,
                        modifier = Modifier.animateItem(),
                    )
                }

                itemsIndexed(
                    items = viewState.suggestions,
                    key = { _, suggestion -> "suggestion_$suggestion" },
                    contentType = { _, _ -> "suggestion" },
                ) { index, suggestion ->
                    SuggestionItem(
                        query = suggestion,
                        online = true,
                        onClick = {
                            onSearch(suggestion)
                            onDismiss()
                        },
                        onFillTextField = {
                            onQueryChange(TextFieldValue(suggestion, TextRange(suggestion.length)))
                        },
                        showDivider = index < viewState.suggestions.lastIndex,
                        modifier = Modifier.animateItem(),
                        pureBlack = pureBlack,
                    )
                }
            }
            if (viewState.spotifyItems.isNotEmpty()) {
                item(
                    key = "spotify_results_header",
                    contentType = "section_header",
                ) {
                    SearchSectionHeader(
                        title = stringResource(R.string.search_spotify),
                        pureBlack = pureBlack,
                        modifier = Modifier.animateItem(),
                    )
                }

                itemsIndexed(
                    items = viewState.spotifyItems,
                    key = { _, item -> "spotify_${item.key}" },
                    contentType = { _, _ -> "spotify_result" },
                ) { _, item ->
                    SpotifySearchItemRow(
                        item = item,
                        modifier =
                            Modifier.combinedClickable(
                                onClick = {
                                    onSearch(item.queryText())
                                    onDismiss()
                                },
                                onLongClick = {},
                            ).animateItem(),
                    )
                }
            }

            if (viewState.appleMusicItems.isNotEmpty()) {
                item(
                    key = "apple_music_results_header",
                    contentType = "section_header",
                ) {
                    SearchSectionHeader(
                        title = stringResource(R.string.search_apple_music),
                        pureBlack = pureBlack,
                        modifier = Modifier.animateItem(),
                    )
                }

                itemsIndexed(
                    items = viewState.appleMusicItems,
                    key = { _, item -> "apple_music_${item.key}" },
                    contentType = { _, _ -> "apple_music_result" },
                ) { _, item ->
                    AppleMusicItemRow(
                        item = item,
                        modifier =
                            Modifier.combinedClickable(
                                onClick = {
                                    onSearch(item.appleMusicQueryText())
                                    onDismiss()
                                },
                                onLongClick = {},
                            ).animateItem(),
                    )
                }
            }

            if (viewState.items.isNotEmpty()) {
                item(
                    key = "top_results_header",
                    contentType = "section_header",
                ) {
                    SearchSectionHeader(
                        title = stringResource(R.string.top_results),
                        pureBlack = pureBlack,
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            items(
                items = distinctResultItems,
                key = { item -> "item_${item.id}" },
                contentType = { item -> item::class },
            ) { item ->
                YouTubeListItem(
                    item = item,
                    isActive =
                        when (item) {
                            is SongItem -> mediaMetadata?.id == item.id
                            is AlbumItem -> mediaMetadata?.album?.id == item.id
                            else -> false
                        },
                    isPlaying = isPlaying,
                    trailingContent = {
                        if (item !is PodcastItem && item !is EpisodeItem) {
                            IconButton(
                                onClick = {
                                    menuState.show {
                                        when (item) {
                                            is SongItem -> {
                                                YouTubeSongMenu(
                                                    song = item,
                                                    navController = navController,
                                                    onDismiss = {
                                                        menuState.dismiss()
                                                        onDismiss()
                                                    },
                                                )
                                            }

                                            is AlbumItem -> {
                                                YouTubeAlbumMenu(
                                                    albumItem = item,
                                                    navController = navController,
                                                    onDismiss = {
                                                        menuState.dismiss()
                                                        onDismiss()
                                                    },
                                                )
                                            }

                                            is ArtistItem -> {
                                                YouTubeArtistMenu(
                                                    artist = item,
                                                    onDismiss = {
                                                        menuState.dismiss()
                                                        onDismiss()
                                                    },
                                                )
                                            }

                                            is PlaylistItem -> {
                                                YouTubePlaylistMenu(
                                                    playlist = item,
                                                    coroutineScope = coroutineScope,
                                                    onDismiss = {
                                                        menuState.dismiss()
                                                        onDismiss()
                                                    },
                                                )
                                            }

                                            is PodcastItem, is EpisodeItem -> Unit
                                        }
                                    }
                                },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.more_vert),
                                    contentDescription = null,
                                )
                            }
                        }
                    },
                    modifier =
                        Modifier
                            .combinedClickable(
                                onClick = {
                                    when (item) {
                                        is SongItem -> {
                                            if (item.id == mediaMetadata?.id) {
                                                playerConnection.player.togglePlayPause()
                                            } else {
                                                playerConnection.playQueue(
                                                    YouTubeQueue.radio(item.toMediaMetadata()),
                                                )
                                                onDismiss()
                                            }
                                        }

                                        is AlbumItem -> {
                                            navController.navigate("album/${item.id}")
                                            onDismiss()
                                        }

                                        is ArtistItem -> {
                                            navController.navigate("artist/${item.id}")
                                            onDismiss()
                                        }

                                        is PlaylistItem -> {
                                            navController.navigate("online_playlist/${item.id}")
                                            onDismiss()
                                        }

                                        is PodcastItem -> {
                                            navController.navigate("podcast/${android.net.Uri.encode(item.browseId)}")
                                            onDismiss()
                                        }

                                        is EpisodeItem -> {
                                            playerConnection.playQueue(
                                                ListQueue(
                                                    title = item.podcast?.name ?: item.title,
                                                    items = listOf(item.toMediaItem()),
                                                ),
                                            )
                                            onDismiss()
                                        }
                                    }
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuState.show {
                                        when (item) {
                                            is SongItem -> {
                                                YouTubeSongMenu(
                                                    song = item,
                                                    navController = navController,
                                                    onDismiss = {
                                                        menuState.dismiss()
                                                        onDismiss()
                                                    },
                                                )
                                            }

                                            is AlbumItem -> {
                                                YouTubeAlbumMenu(
                                                    albumItem = item,
                                                    navController = navController,
                                                    onDismiss = {
                                                        menuState.dismiss()
                                                        onDismiss()
                                                    },
                                                )
                                            }

                                            is ArtistItem -> {
                                                YouTubeArtistMenu(
                                                    artist = item,
                                                    onDismiss = {
                                                        menuState.dismiss()
                                                        onDismiss()
                                                    },
                                                )
                                            }

                                            is PlaylistItem -> {
                                                YouTubePlaylistMenu(
                                                    playlist = item,
                                                    coroutineScope = coroutineScope,
                                                    onDismiss = {
                                                        menuState.dismiss()
                                                        onDismiss()
                                                    },
                                                )
                                            }

                                            is PodcastItem, is EpisodeItem -> Unit
                                        }
                                    }
                                },
                            ).animateItem(),
                )
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(
    title: String,
    pureBlack: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color =
            if (pureBlack) {
                Color.White.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    start = SearchHorizontalPadding + 4.dp,
                    top = 16.dp,
                    end = SearchHorizontalPadding + 4.dp,
                    bottom = 6.dp,
                ),
    )
}

@Composable
fun SuggestionItem(
    modifier: Modifier = Modifier,
    query: String,
    online: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit = {},
    onFillTextField: () -> Unit,
    pureBlack: Boolean,
    showDivider: Boolean = true,
) {
    val iconTint =
        if (pureBlack) {
            Color.White.copy(alpha = 0.78f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = SearchHorizontalPadding),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .heightIn(min = SearchRowMinHeight)
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Icon(
                painterResource(if (online) R.drawable.search else R.drawable.history),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.padding(start = 10.dp).size(20.dp),
            )

            Spacer(Modifier.width(14.dp))

            Text(
                text = query,
                style = MaterialTheme.typography.bodyLarge,
                color = if (pureBlack) Color.White.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (!online) {
                IconButton(onClick = onDelete) {
                    Icon(
                        painter = painterResource(R.drawable.close),
                        contentDescription = stringResource(R.string.remove_from_history),
                        tint =
                            if (pureBlack) {
                                Color.White.copy(alpha = 0.62f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            IconButton(onClick = onFillTextField) {
                Icon(
                    painter = painterResource(R.drawable.arrow_top_left),
                    contentDescription = stringResource(R.string.search),
                    tint =
                        if (pureBlack) {
                            Color.White.copy(alpha = 0.62f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 46.dp),
                thickness = 0.5.dp,
                color =
                    if (pureBlack) {
                        Color.White.copy(alpha = 0.10f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    },
            )
        }
    }
}

private val SearchContentMaxWidth = 720.dp
private val SearchHorizontalPadding = 12.dp
private val SearchRowMinHeight = 64.dp
private val SearchRowSpacing = 2.dp
