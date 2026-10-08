/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * The search tab, ported from BitChord's SearchScreen
 * (https://github.com/kushagrasinghx/BitChord, GPL-3.0) as an original
 * re-implementation on ArchiveTune's data layer: the 46dp search field with
 * the magnifier-as-submit and re-focusing clear button, the pill source and
 * filter rows (12dp corners, inverted selection), up to three text
 * completions with north-west fill arrows, the typeahead media dropdown,
 * recent searches as entity rows with per-row removal, the promoted Top
 * result card, sectioned results with 0.5dp dividers inset past the 52dp
 * artwork, the animated now-playing bars over the current track's artwork,
 * and the 4-item-lookahead pagination with trailing skeletons. YouTube and
 * Local Music search in place; Spotify and Apple Music keep the results page.
 */

@file:OptIn(ExperimentalFoundationApi::class)

package moe.rukamori.archivetune.ui.screens.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.DefaultSearchSourceKey
import moe.rukamori.archivetune.constants.HideExplicitKey
import moe.rukamori.archivetune.constants.HideVideoKey
import moe.rukamori.archivetune.constants.PauseSearchHistoryKey
import moe.rukamori.archivetune.constants.SearchProvider
import moe.rukamori.archivetune.db.entities.SearchHistory
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.models.ItemsPage
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.ui.menu.YouTubeAlbumMenu
import moe.rukamori.archivetune.ui.menu.YouTubeArtistMenu
import moe.rukamori.archivetune.ui.menu.YouTubePlaylistMenu
import moe.rukamori.archivetune.ui.menu.YouTubeSongMenu
import moe.rukamori.archivetune.ui.screens.HomeAtmosphereBackground
import moe.rukamori.archivetune.ui.screens.LocalSearchHazeState
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.LocalSearchViewModel
import moe.rukamori.archivetune.viewmodels.SearchHistoryViewModel
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.models.filterExplicit
import moe.rukamori.archivetune.innertube.models.filterVideo
import moe.rukamori.archivetune.innertube.pages.SearchSummaryPage

private val PageGutter = 16.dp
private val RowDividerInset = 84.dp
private val FilterPillShape = RoundedCornerShape(12.dp)
private val RowArtSize = 52.dp
private const val SuggestDebounceMs = 180L
private const val TypeaheadMaxRows = 15
private const val PaginationLookahead = 4

private data class CommittedSearch(
    val query: String,
    val provider: SearchProvider,
) : java.io.Serializable

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    navController: NavController,
    onSearchQuery: (String) -> Unit,
    onVoiceSearch: () -> Unit = {},
    headerScrollConnection: NestedScrollConnection? = null,
    historyViewModel: SearchHistoryViewModel = hiltViewModel(),
    localSearchViewModel: LocalSearchViewModel = hiltViewModel(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    var provider by rememberEnumPreference(DefaultSearchSourceKey, SearchProvider.YOUTUBE)
    var committed by rememberSaveable { mutableStateOf<CommittedSearch?>(null) }

    var liveSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var typeaheadItems by remember { mutableStateOf<List<YTItem>>(emptyList()) }

    var summary by remember { mutableStateOf<SearchSummaryPage?>(null) }
    var filterPage by remember { mutableStateOf<ItemsPage?>(null) }
    var resultsLoading by remember { mutableStateOf(false) }
    var resultsError by remember { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var selectedFilter by rememberSaveable { mutableStateOf("") }

    val (hideExplicit) = rememberPreference(HideExplicitKey, defaultValue = false)
    val (hideVideo) = rememberPreference(HideVideoKey, defaultValue = false)
    val (pauseSearchHistory) = rememberPreference(PauseSearchHistoryKey, defaultValue = false)
    val searchHazeState = LocalSearchHazeState.current
    val (disableBlur) = rememberPreference(moe.rukamori.archivetune.constants.DisableBlurKey, false)

    val playerConnection = LocalPlayerConnection.current
    val mediaMetadata = playerConnection?.mediaMetadata?.collectAsStateWithLifecycle()?.value
    val isPlaying = playerConnection?.isPlaying?.collectAsStateWithLifecycle()?.value ?: false
    val recentSearches by historyViewModel.recentSearches.collectAsStateWithLifecycle()
    val localResult by localSearchViewModel.result.collectAsStateWithLifecycle()
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val recentLabel = stringResource(R.string.search_recent_label)
    var searchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val localSongs =
        localResult.map[moe.rukamori.archivetune.viewmodels.LocalFilter.SONG]
            .orEmpty()
            .filterIsInstance<moe.rukamori.archivetune.db.entities.Song>()

    fun recordHistory(
        queryKey: String,
        displayTitle: String,
        subtitle: String? = null,
        artworkUrl: String? = null,
        entityType: String? = null,
    ) {
        if (pauseSearchHistory) return
        historyViewModel.record(
            SearchHistory(
                query = queryKey,
                displayTitle = displayTitle,
                subtitle = subtitle,
                artworkUrl = artworkUrl,
                entityType = entityType,
            ),
        )
    }

    fun runSearch(term: String, filterValue: String) {
        selectedFilter = filterValue
        resultsLoading = true
        resultsError = null
        summary = null
        filterPage = null
        loadingMore = false
        searchJob?.cancel()
        searchJob = scope.launch {
            val trimmed = term.trim()
            if (trimmed.isEmpty()) return@launch
            if (provider == SearchProvider.YOUTUBE) {
                if (filterValue.isEmpty()) {
                    YouTube.searchSummary(trimmed)
                        .onSuccess { page ->
                            summary =
                                page
                                    .filterExplicit(hideExplicit)
                                    .filterVideo(hideVideo)
                        }
                        .onFailure { resultsError = it.message }
                    resultsLoading = false
                } else {
                    YouTube
                        .search(trimmed, YouTube.SearchFilter(filterValue))
                        .onSuccess { result ->
                            filterPage =
                                ItemsPage(
                                    result.items
                                        .distinctBy { it.id }
                                        .filterExplicit(hideExplicit)
                                        .filterVideo(hideVideo),
                                    result.continuation,
                                )
                        }
                        .onFailure { resultsError = it.message }
                    resultsLoading = false
                }
            } else {
                resultsLoading = false
            }
        }
    }

    fun submit(term: String, record: Boolean = true) {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return
        query = term
        committed = CommittedSearch(trimmed, provider)
        liveSuggestions = emptyList()
        typeaheadItems = emptyList()
        keyboardController?.hide()
        scope.launch { listState.scrollToItem(0) }
        if (record) {
            recordHistory(
                queryKey = "q:$trimmed",
                displayTitle = trimmed,
                subtitle = recentLabel,
            )
        }
        if (provider == SearchProvider.SPOTIFY || provider == SearchProvider.APPLE_MUSIC) {
            onSearchQuery(trimmed)
        } else if (provider == SearchProvider.LOCAL) {
            localSearchViewModel.query.value = trimmed
            localSearchViewModel.filter.value =
                moe.rukamori.archivetune.viewmodels.LocalFilter.SONG
        } else {
            runSearch(trimmed, selectedFilter)
        }
    }

    LaunchedEffect(query, provider, committed) {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || trimmed == committed?.query) {
            liveSuggestions = emptyList()
            typeaheadItems = emptyList()
            return@LaunchedEffect
        }
        if (provider == SearchProvider.LOCAL) {
            localSearchViewModel.query.value = trimmed
            localSearchViewModel.filter.value =
                moe.rukamori.archivetune.viewmodels.LocalFilter.SONG
            return@LaunchedEffect
        }
        if (provider != SearchProvider.YOUTUBE) return@LaunchedEffect
        delay(SuggestDebounceMs)
        if (query.trim() == committed?.query) return@LaunchedEffect
        YouTube.searchSuggestions(trimmed)
            .onSuccess { page ->
                if (query.trim() != committed?.query) {
                    liveSuggestions =
                        page.queries
                            .filter { it.isNotBlank() && !it.equals(trimmed, ignoreCase = true) }
                            .take(3)
                    typeaheadItems =
                        page.recommendedItems
                            .filterExplicit(hideExplicit)
                            .filterVideo(hideVideo)
                            .take(TypeaheadMaxRows)
                }
            }
    }

    LaunchedEffect(selectedFilter) {
        val current = committed ?: return@LaunchedEffect
        if (current.provider == SearchProvider.YOUTUBE &&
            current.query.isNotBlank() &&
            query.trim() == current.query
        ) {
            runSearch(current.query, selectedFilter)
        }
    }
    LaunchedEffect(Unit) {
        var attempt = 0
        while (attempt < 3) {
            delay(if (attempt == 0) 120L else 220L)
            val focused = runCatching { focusRequester.requestFocus() }.isSuccess
            if (focused) break
            attempt++
        }
        keyboardController?.show()
    }

    LaunchedEffect(committed) {
        val current = committed ?: return@LaunchedEffect
        if (current.provider != SearchProvider.YOUTUBE) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = layout.totalItemsCount
            Triple(lastVisible, total, filterPage?.continuation)
        }.collect { (lastVisible, total, continuation) ->
            if (
                continuation != null && total > 0 &&
                lastVisible >= total - PaginationLookahead
            ) {
                loadingMore = true
                try {
                    val result = YouTube.searchContinuation(continuation).getOrNull()
                    if (result != null) {
                        filterPage =
                            ItemsPage(
                                (filterPage?.items.orEmpty() + result.items).distinctBy { it.id },
                                result.continuation,
                            )
                    }
                } finally {
                    loadingMore = false
                }
            }
        }
    }

    val midEdit = query.isNotBlank() && query.trim() != committed?.query
    val showResults =
        query.isNotBlank() && query.trim() == committed?.query && !midEdit &&
            (committed?.provider == SearchProvider.YOUTUBE || committed?.provider == SearchProvider.LOCAL)

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .let { m -> if (searchHazeState != null) m.hazeSource(searchHazeState) else m }
                .then(
                    if (headerScrollConnection != null) {
                        Modifier.nestedScroll(headerScrollConnection)
                    } else {
                        Modifier
                    },
                ),
    ) {
        if (!disableBlur) {
            HomeAtmosphereBackground()
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.ime.union(
                            LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom),
                        ),
                    ),
        ) {
            BitChordSearchField(
                query = query,
                onQueryChange = { query = it },
                onSubmit = { submit(query) },
                focusRequester = focusRequester,
                placeholder = stringResource(
                    when (provider) {
                        SearchProvider.LOCAL -> R.string.search_hint_local
                        else -> R.string.search_hint_all
                    },
                ),
                modifier = Modifier.padding(start = PageGutter, end = PageGutter, bottom = 4.dp),
            )

            AnimatedVisibility(
                visible = query.isEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                ProviderPills(
                    provider = provider,
                    onProviderChange = { provider = it },
                    modifier = Modifier.padding(start = PageGutter, end = PageGutter, bottom = 4.dp),
                )
            }

            if (showResults && committed?.provider == SearchProvider.YOUTUBE) {
                SearchFilterTabs(
                    selected = selectedFilter,
                    onSelect = { selectedFilter = it },
                )
            }

            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                when {
                    midEdit -> {
                        searchSuggestions(
                            suggestions = liveSuggestions,
                            onClick = { term ->
                                submit(term)
                            },
                            onFill = { query = it },
                        )
                        if (provider == SearchProvider.YOUTUBE && typeaheadItems.isNotEmpty()) {
                            searchTypeaheadDropdown(
                                items = typeaheadItems,
                                currentMediaId = mediaMetadata?.id,
                                isPlaying = isPlaying,
                                navController = navController,
                                playerConnection = playerConnection,
                                menuState = menuState,
                                haptic = haptic,
                                onRecord = ::recordHistory,
                            )
                        } else if (provider == SearchProvider.LOCAL && localSongs.isNotEmpty()) {
                            localSongRows(
                                songs = localSongs,
                                currentMediaId = mediaMetadata?.id,
                                isPlaying = isPlaying,
                                playerConnection = playerConnection,
                                navController = navController,
                                menuState = menuState,
                                haptic = haptic,
                                onRecord = ::recordHistory,
                            )
                        }
                    }

                    showResults && committed?.provider == SearchProvider.LOCAL -> {
                        if (localSongs.isEmpty() && localResult.query != committed?.query) {
                            songListSkeleton()
                        } else if (localSongs.isEmpty()) {
                            item(key = "local:empty") {
                                MessageState(stringResource(R.string.search_no_results_msg))
                            }
                        } else {
                            localSongRows(
                                songs = localSongs,
                                currentMediaId = mediaMetadata?.id,
                                isPlaying = isPlaying,
                                playerConnection = playerConnection,
                                navController = navController,
                                menuState = menuState,
                                haptic = haptic,
                                onRecord = ::recordHistory,
                            )
                        }
                    }

                    showResults && committed?.provider == SearchProvider.YOUTUBE -> {
                        if (resultsLoading) {
                            songListSkeleton()
                        } else if (resultsError != null) {
                            item(key = "results:error") { MessageState(resultsError.orEmpty()) }
                        } else if (selectedFilter.isEmpty()) {
                            allModeSections(
                                summary = summary,
                                currentMediaId = mediaMetadata?.id,
                                currentAlbumId = mediaMetadata?.album?.id,
                                isPlaying = isPlaying,
                                navController = navController,
                                playerConnection = playerConnection,
                                menuState = menuState,
                                haptic = haptic,
                                onRecord = ::recordHistory,
                            )
                        } else {
                            filteredModeRows(
                                page = filterPage,
                                currentMediaId = mediaMetadata?.id,
                                currentAlbumId = mediaMetadata?.album?.id,
                                isPlaying = isPlaying,
                                navController = navController,
                                playerConnection = playerConnection,
                                menuState = menuState,
                                haptic = haptic,
                                onRecord = ::recordHistory,
                            )
                            if (loadingMore) {
                                songListSkeleton(count = 3, keyPrefix = "skeleton:search:more")
                            }
                        }
                    }

                    else -> if (recentSearches.isEmpty()) {
                        item(key = "history:empty") {
                            MessageState(stringResource(R.string.search_no_recent))
                        }
                    } else {
                        recentSearches(
                            history = recentSearches,
                            currentMediaId = mediaMetadata?.id,
                            isPlaying = isPlaying,
                            onPick = { entry ->
                                val entityQuery = entry.displayTitle ?: entry.query
                                when (entry.entityType) {
                                    "song" -> {
                                        provider = SearchProvider.YOUTUBE
                                        submit(entityQuery, record = false)
                                    }

                                    "album" -> navController.navigate("album/${entry.query.removePrefix("album:")}")

                                    "artist" -> navController.navigate("artist/${entry.query.removePrefix("artist:")}")

                                    "playlist" -> navController.navigate("online_playlist/${entry.query.removePrefix("playlist:")}")

                                    else -> submit(entityQuery, record = false)
                                }
                            },
                            onRemove = { entry ->
                                historyViewModel.remove(entry)
                            },
                            onClear = historyViewModel::clearAll,
                        )
                    }
                }
            }
        }
    }
}

private fun playYouTubeSong(
    playerConnection: PlayerConnection?,
    song: SongItem,
    currentMediaId: String?,
) {
    if (playerConnection == null) return
    if (song.id == currentMediaId) {
        playerConnection.player.togglePlayPause()
    } else {
        playerConnection.playQueue(
            YouTubeQueue(
                song.endpoint ?: WatchEndpoint(videoId = song.id),
                song.toMediaMetadata(),
            ),
        )
    }
}

private fun openItemMenu(
    item: YTItem,
    navController: NavController,
    menuState: moe.rukamori.archivetune.ui.component.MenuState,
    onDismiss: () -> Unit,
) {
    menuState.show {
        when (item) {
            is SongItem -> YouTubeSongMenu(song = item, navController = navController, onDismiss = onDismiss)
            is AlbumItem -> YouTubeAlbumMenu(albumItem = item, navController = navController, onDismiss = onDismiss)
            is ArtistItem -> YouTubeArtistMenu(artist = item, onDismiss = onDismiss)
            is PlaylistItem ->
                YouTubePlaylistMenu(
                    playlist = item,
                    coroutineScope = rememberCoroutineScope(),
                    onDismiss = onDismiss,
                )

            else -> {}
        }
    }
}

private fun ytItemEntityRecord(item: YTItem): SearchHistory? =
    when (item) {
        is SongItem ->
            SearchHistory(
                query = "song:${item.id}",
                displayTitle = item.title,
                subtitle = item.artists.joinToString(" · ") { it.name },
                artworkUrl = item.thumbnail,
                entityType = "song",
            )

        is AlbumItem ->
            SearchHistory(
                query = "album:${item.id}",
                displayTitle = item.title,
                subtitle = item.artists?.joinToString(" · ") { it.name },
                artworkUrl = item.thumbnail,
                entityType = "album",
            )

        is ArtistItem ->
            SearchHistory(
                query = "artist:${item.id}",
                displayTitle = item.title,
                subtitle = item.subscriberCountText,
                artworkUrl = item.thumbnail,
                entityType = "artist",
            )

        is PlaylistItem ->
            SearchHistory(
                query = "playlist:${item.id}",
                displayTitle = item.title,
                subtitle = listOfNotNull(item.author?.name, item.songCountText).joinToString(" · "),
                artworkUrl = item.thumbnail,
                entityType = "playlist",
            )

        else -> null
    }

private fun Modifier.itemRowColors(
    isCurrent: Boolean,
): Modifier {
    return this
}


@Composable
private fun BitChordYTItemRow(
    item: YTItem,
    isCurrent: Boolean,
    isPlaying: Boolean,
    currentMediaId: String?,
    navController: NavController,
    playerConnection: PlayerConnection?,
    menuState: moe.rukamori.archivetune.ui.component.MenuState,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onRecord: (String, String, String?, String?, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleColor by androidx.compose.animation.animateColorAsState(
        targetValue =
            if (isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onBackground
            },
        label = "BitChordSearchRowTitle",
    )
    val rowBackground by androidx.compose.animation.animateColorAsState(
        targetValue =
            if (isCurrent) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            } else {
                Color.Transparent
            },
        label = "BitChordSearchRowBackground",
    )
    val isArtist = item is ArtistItem
    val artworkShape = if (isArtist) CircleShape else RoundedCornerShape(8.dp)

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(rowBackground)
                .combinedClickable(
                    onClick = {
                        ytItemEntityRecord(item)?.let { entry ->
                            onRecord(
                                entry.query,
                                entry.displayTitle,
                                entry.subtitle,
                                entry.artworkUrl,
                                entry.entityType,
                            )
                        }
                        when (item) {
                            is SongItem -> playYouTubeSong(playerConnection, item, currentMediaId)

                            is AlbumItem -> navController.navigate("album/${item.id}")

                            is ArtistItem -> navController.navigate("artist/${item.id}")

                            is PlaylistItem -> navController.navigate("online_playlist/${item.id}")

                            else -> {}
                        }
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        openItemMenu(item, navController, menuState, menuState::dismiss)
                    },
                )
                .padding(horizontal = PageGutter, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(RowArtSize)) {
            AsyncImage(
                model = item.thumbnail,
                contentDescription = null,
                modifier =
                    Modifier
                        .size(RowArtSize)
                        .clip(artworkShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (isCurrent && isPlaying) {
                SearchPlayingBars(Modifier.align(Alignment.Center))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.subtitleText(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun YTItem.subtitleText(): String =
    when (this) {
        is SongItem -> artists.joinToString(" · ") { it.name }
        is AlbumItem -> artists?.joinToString(" · ") { it.name }
        is ArtistItem -> subscriberCountText.orEmpty()
        is PlaylistItem -> listOfNotNull(author?.name, songCountText).joinToString(" · ")
        else -> ""
    }

@Composable
private fun SearchRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = RowDividerInset),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun BitChordSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
                .padding(start = 8.dp, end = 12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(
                        enabled = query.isNotBlank(),
                        onClick = onSubmit,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(6.dp),
            )
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle =
                MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onBackground,
                ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions =
                KeyboardActions(
                    onSearch = {
                        if (query.isNotEmpty()) onSubmit()
                    },
                ),
            modifier =
                Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        androidx.compose.animation.AnimatedVisibility(
            visible = query.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(
                            onClick = {
                                onQueryChange("")
                                focusRequester.requestFocus()
                                keyboardController?.show()
                            },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ProviderPills(
    provider: SearchProvider,
    onProviderChange: (SearchProvider) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val providers =
        listOf(
            SearchProvider.YOUTUBE to stringResource(R.string.search_source_youtube),
            SearchProvider.LOCAL to stringResource(R.string.search_library),
            SearchProvider.SPOTIFY to stringResource(R.string.search_source_spotify),
            SearchProvider.APPLE_MUSIC to stringResource(R.string.search_source_apple_music),
        )
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        providers.forEach { (candidate, label) ->
            val selected = candidate == provider
            Box(
                modifier =
                    Modifier
                        .clip(FilterPillShape)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.onBackground
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        )
                        .clickable {
                            if (!selected) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onProviderChange(candidate)
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color =
                        if (selected) {
                            MaterialTheme.colorScheme.background
                        } else {
                            MaterialTheme.colorScheme.onBackground
                        },
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SearchFilterTabs(
    selected: String,
    onSelect: (String) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val filters =
        listOf(
            "" to stringResource(R.string.filter_all),
            YouTube.SearchFilter.Companion.FILTER_SONG.value to stringResource(R.string.filter_songs),
            YouTube.SearchFilter.Companion.FILTER_VIDEO.value to stringResource(R.string.filter_videos),
            YouTube.SearchFilter.Companion.FILTER_ALBUM.value to stringResource(R.string.filter_albums),
            YouTube.SearchFilter.Companion.FILTER_ARTIST.value to stringResource(R.string.filter_artists),
            YouTube.SearchFilter.Companion.FILTER_COMMUNITY_PLAYLIST.value to stringResource(R.string.filter_playlists),
        )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = PageGutter, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        filters.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                modifier =
                    Modifier
                        .clip(FilterPillShape)
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.onBackground
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        )
                        .clickable {
                            if (!isSelected) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(value)
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color =
                        if (isSelected) {
                            MaterialTheme.colorScheme.background
                        } else {
                            MaterialTheme.colorScheme.onBackground
                        },
                    maxLines = 1,
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.searchSuggestions(
    suggestions: List<String>,
    onClick: (String) -> Unit,
    onFill: (String) -> Unit,
) {
    item(key = "suggestions:top-inset") { Spacer(Modifier.height(8.dp)) }
    itemsIndexed(suggestions, key = { index, term -> "suggest:$term:$index" }) { _, term ->
        SuggestionRow(
            term = term,
            onFill = { onFill(term) },
            onClick = { onClick(term) },
        )
    }
}

@Composable
private fun SuggestionRow(
    term: String,
    onFill: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = PageGutter, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = term,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onFill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.NorthWest,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.searchTypeaheadDropdown(
    items: List<YTItem>,
    currentMediaId: String?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection?,
    menuState: moe.rukamori.archivetune.ui.component.MenuState,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onRecord: (String, String, String?, String?, String?) -> Unit,
) {
    item(key = "typeahead:divider") {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = PageGutter),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        )
    }
    itemsIndexed(items, key = { index, item -> "ta:${item.id}:$index" }) { _, item ->
        BitChordYTItemRow(
            item = item,
            isCurrent = item.id == currentMediaId,
            isPlaying = isPlaying,
            currentMediaId = currentMediaId,
            navController = navController,
            playerConnection = playerConnection,
            menuState = menuState,
            haptic = haptic,
            onRecord = onRecord,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.recentSearches(
    history: List<SearchHistory>,
    currentMediaId: String?,
    isPlaying: Boolean,
    onPick: (SearchHistory) -> Unit,
    onRemove: (SearchHistory) -> Unit,
    onClear: () -> Unit,
) {
    item(key = "recent:header") {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PageGutter, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.search_recent_searches),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.clear),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable(onClick = onClear)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
    items(history, key = { "recent:${it.id}" }) { entity ->
        RecentSearchEntityRow(
            entity = entity,
            isCurrent = entity.entityType == "song" && entity.query.removePrefix("song:") == currentMediaId,
            isPlaying = isPlaying,
            onClick = { onPick(entity) },
            onRemove = { onRemove(entity) },
        )
    }
}

@Composable
private fun RecentSearchEntityRow(
    entity: SearchHistory,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = PageGutter, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(RowArtSize)) {
            if (entity.artworkUrl != null) {
                AsyncImage(
                    model = entity.artworkUrl,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(RowArtSize)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .size(RowArtSize)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (isCurrent && isPlaying) {
                SearchPlayingBars(Modifier.align(Alignment.Center))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entity.displayTitle ?: entity.query,
                style = MaterialTheme.typography.titleMedium,
                color =
                    if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onBackground
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entity.subtitle ?: stringResource(R.string.search_recent_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.localSongRows(
    songs: List<moe.rukamori.archivetune.db.entities.Song>,
    currentMediaId: String?,
    isPlaying: Boolean,
    playerConnection: PlayerConnection?,
    navController: NavController,
    menuState: moe.rukamori.archivetune.ui.component.MenuState,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onRecord: (String, String, String?, String?, String?) -> Unit,
) {
    itemsIndexed(songs, key = { index, song -> "local:${song.id}:$index" }) { index, song ->
        LocalSongRow(
            song = song,
            isCurrent = song.id == currentMediaId,
            isPlaying = isPlaying,
            playerConnection = playerConnection,
            navController = navController,
            menuState = menuState,
            haptic = haptic,
            onRecord = onRecord,
        )
        if (index < songs.lastIndex) {
            SearchRowDivider()
        }
    }
}

@Composable
private fun LocalSongRow(
    song: moe.rukamori.archivetune.db.entities.Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    playerConnection: PlayerConnection?,
    navController: NavController,
    menuState: moe.rukamori.archivetune.ui.component.MenuState,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onRecord: (String, String, String?, String?, String?) -> Unit,
) {
    val titleColor by androidx.compose.animation.animateColorAsState(
        targetValue =
            if (isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onBackground
            },
        label = "BitChordLocalRowTitle",
    )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        onRecord(
                            "song:${song.id}",
                            song.song.title,
                            song.artists.joinToString(" · ") { it.name },
                            song.thumbnailUrl,
                            "song",
                        )
                        playerConnection?.playQueue(
                            ListQueue(
                                title = song.song.title,
                                items = listOf(song.toMediaItem()),
                            ),
                        )
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuState.show {
                            moe.rukamori.archivetune.ui.menu.SongMenu(
                                originalSong = song,
                                navController = navController,
                                onDismiss = menuState::dismiss,
                            )
                        }
                    },
                )
                .padding(horizontal = PageGutter, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(RowArtSize)) {
            AsyncImage(
                model = song.thumbnailUrl,
                contentDescription = null,
                modifier =
                    Modifier
                        .size(RowArtSize)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (isCurrent && isPlaying) {
                SearchPlayingBars(Modifier.align(Alignment.Center))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.song.title,
                style = MaterialTheme.typography.titleMedium,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = song.artists.joinToString(" · ") { it.name },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.allModeSections(
    summary: SearchSummaryPage?,
    currentMediaId: String?,
    currentAlbumId: String?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection?,
    menuState: moe.rukamori.archivetune.ui.component.MenuState,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onRecord: (String, String, String?, String?, String?) -> Unit,
) {
    if (summary == null) {
        item(key = "results:empty") {
            MessageState(stringResource(R.string.search_no_results_msg))
        }
        return
    }
    val topSection = summary.summaries.firstOrNull()
    val topResultSong =
        if (topSection != null &&
            (
                topSection.title.contains("top", ignoreCase = true) ||
                    topSection.title.contains("result", ignoreCase = true) ||
                    (topSection.items.size == 1 && topSection.items.firstOrNull() is SongItem)
                )
        ) {
            topSection.items.filterIsInstance<SongItem>().firstOrNull()
        } else {
            null
        }
    if (topResultSong != null) {
        item(key = "search:top-result:${topResultSong.id}") {
            TopResultCard(
                song = topResultSong,
                isCurrent = topResultSong.id == currentMediaId,
                currentMediaId = currentMediaId,
                navController = navController,
                playerConnection = playerConnection,
                menuState = menuState,
                haptic = haptic,
                onRecord = onRecord,
            )
        }
    }
    summary.summaries
        .filter { it.items.isNotEmpty() }
        .forEachIndexed { sectionIndex, section ->
            val sectionItems =
                if (topResultSong != null && section === topSection && section.items.size <= 1) {
                    emptyList()
                } else {
                    section.items
                }
            if (sectionItems.isEmpty()) return@forEachIndexed
            item(key = "search-section:${section.title}:$sectionIndex") {
                Text(
                    text = section.title,
                    modifier = Modifier.padding(start = PageGutter, end = PageGutter, top = 16.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            itemsIndexed(
                sectionItems,
                key = { index, item -> "all_${item.id}_$index" },
            ) { index, item ->
                BitChordYTItemRow(
                    item = item,
                    isCurrent =
                        when (item) {
                            is SongItem -> item.id == currentMediaId
                            is AlbumItem -> item.id == currentAlbumId
                            else -> false
                        },
                    isPlaying = isPlaying,
                    currentMediaId = currentMediaId,
                    navController = navController,
                    playerConnection = playerConnection,
                    menuState = menuState,
                    haptic = haptic,
                    onRecord = onRecord,
                )
                if (index < sectionItems.lastIndex) {
                    SearchRowDivider()
                }
            }
        }
}

@Composable
private fun TopResultCard(
    song: SongItem,
    isCurrent: Boolean,
    currentMediaId: String?,
    navController: NavController,
    playerConnection: PlayerConnection?,
    menuState: moe.rukamori.archivetune.ui.component.MenuState,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onRecord: (String, String, String?, String?, String?) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = PageGutter, end = PageGutter, top = 18.dp, bottom = 8.dp)
                .combinedClickable(
                    onClick = {
                        onRecord(
                            "song:${song.id}",
                            song.title,
                            song.artists.joinToString(" · ") { it.name },
                            song.thumbnail,
                            "song",
                        )
                        playYouTubeSong(playerConnection, song, currentMediaId)
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        openItemMenu(song, navController, menuState, menuState::dismiss)
                    },
                ),
    ) {
        Text(
            text = stringResource(R.string.top_result),
            style = MaterialTheme.typography.labelLarge,
            color =
                if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = song.thumbnail,
                contentDescription = null,
                modifier =
                    Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleLarge,
                    color =
                        if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = song.artists.joinToString(" · ") { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    openItemMenu(song, navController, menuState, menuState::dismiss)
                },
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    onRecord(
                        "song:${song.id}",
                        song.title,
                        song.artists.joinToString(" · ") { it.name },
                        song.thumbnail,
                        "song",
                    )
                    playYouTubeSong(playerConnection, song, currentMediaId)
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.play))
            }
            OutlinedButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    openItemMenu(song, navController, menuState, menuState::dismiss)
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.add_to_playlist))
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.filteredModeRows(
    page: ItemsPage?,
    currentMediaId: String?,
    currentAlbumId: String?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection?,
    menuState: moe.rukamori.archivetune.ui.component.MenuState,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onRecord: (String, String, String?, String?, String?) -> Unit,
) {
    if (page == null) {
        songListSkeleton()
        return
    }
    if (page.items.isEmpty()) {
        item(key = "filtered:empty") {
            MessageState(stringResource(R.string.search_no_results_msg))
        }
        return
    }
    itemsIndexed(
        page.items,
        key = { index, item -> "filtered_${item.id}_$index" },
    ) { index, item ->
        BitChordYTItemRow(
            item = item,
            isCurrent =
                when (item) {
                    is SongItem -> item.id == currentMediaId
                    is AlbumItem -> item.id == currentAlbumId
                    else -> false
                },
            isPlaying = isPlaying,
            currentMediaId = currentMediaId,
            navController = navController,
            playerConnection = playerConnection,
            menuState = menuState,
            haptic = haptic,
            onRecord = onRecord,
        )
        if (index < page.items.lastIndex) {
            SearchRowDivider()
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.songListSkeleton(
    count: Int = 8,
    keyPrefix: String = "skeleton:search",
) {
    items(count, key = { "$keyPrefix:$it" }) {
        SearchSkeletonRow()
    }
}

@Composable
private fun SearchSkeletonRow(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "SearchSkeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.35f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "SearchSkeletonAlpha",
    )
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = PageGutter, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(RowArtSize)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(0.62f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)),
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(0.4f)
                        .height(11.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha * 0.8f)),
            )
        }
    }
}

@Composable
private fun MessageState(message: String) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PageGutter + 6.dp, vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchPlayingBars(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "SearchPlayingBars")
    val fractions =
        listOf(0.38f to 1f, 0.78f to 0.88f, 0.52f to 0.76f)
    Row(
        modifier =
            modifier
                .background(
                    color = Color.Black.copy(alpha = 0.52f),
                    shape = RoundedCornerShape(6.dp),
                )
                .padding(horizontal = 5.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        fractions.forEachIndexed { index, (initial, target) ->
            val fraction by transition.animateFloat(
                initialValue = initial,
                targetValue = target,
                animationSpec =
                    infiniteRepeatable(
                        animation =
                            keyframes {
                                durationMillis = 520 + index * 130
                                initial at 0 using androidx.compose.animation.core.LinearEasing
                                target at (260 + index * 50) using androidx.compose.animation.core.LinearEasing
                                initial at durationMillis using androidx.compose.animation.core.LinearEasing
                            },
                        repeatMode = RepeatMode.Restart,
                    ),
                label = "SearchPlayingBar$index",
            )
            Box(
                modifier =
                    Modifier
                        .width(3.dp)
                        .height(4.dp + 10.dp * fraction)
                        .background(
                            color = Color.White,
                            shape = RoundedCornerShape(2.dp),
                        ),
            )
        }
    }
}
