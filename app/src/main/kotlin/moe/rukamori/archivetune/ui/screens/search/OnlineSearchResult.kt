/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.SearchProvider
import moe.rukamori.archivetune.constants.SearchSource
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.innertube.YouTube.SearchFilter.Companion.FILTER_ALBUM
import moe.rukamori.archivetune.innertube.YouTube.SearchFilter.Companion.FILTER_ARTIST
import moe.rukamori.archivetune.innertube.YouTube.SearchFilter.Companion.FILTER_COMMUNITY_PLAYLIST
import moe.rukamori.archivetune.innertube.YouTube.SearchFilter.Companion.FILTER_FEATURED_PLAYLIST
import moe.rukamori.archivetune.innertube.YouTube.SearchFilter.Companion.FILTER_SONG
import moe.rukamori.archivetune.innertube.YouTube.SearchFilter.Companion.FILTER_VIDEO
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.EpisodeItem
import moe.rukamori.archivetune.innertube.models.PodcastItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.pages.SearchSummary
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.ui.component.EmptyPlaceholder
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.SearchSourcePicker
import moe.rukamori.archivetune.ui.component.YouTubeListItem
import moe.rukamori.archivetune.ui.component.shimmer.ListItemPlaceHolder
import moe.rukamori.archivetune.ui.component.shimmer.ShimmerHost
import moe.rukamori.archivetune.ui.menu.YouTubeAlbumMenu
import moe.rukamori.archivetune.ui.menu.YouTubeArtistMenu
import moe.rukamori.archivetune.ui.menu.YouTubePlaylistMenu
import moe.rukamori.archivetune.ui.menu.YouTubeSongMenu
import moe.rukamori.archivetune.ui.screens.ScreenHeaderHaze
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.viewmodels.OnlineSearchSort
import moe.rukamori.archivetune.viewmodels.OnlineSearchViewModel
import androidx.compose.runtime.getValue
import moe.rukamori.archivetune.viewmodels.PODCAST_SEARCH_FILTER

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OnlineSearchResult(
    navController: NavController,
    searchSort: OnlineSearchSort,
    onSearchSortChange: (OnlineSearchSort) -> Unit = {},
    viewModel: OnlineSearchViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val haptic = LocalHapticFeedback.current
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    if (viewModel.searchProvider == SearchProvider.SPOTIFY) {
        SpotifyOnlineSearchResult(
            navController = navController,
            searchSort = searchSort,
            onSearchSortChange = onSearchSortChange,
        )
        return
    }
    if (viewModel.searchProvider == SearchProvider.APPLE_MUSIC) {
        AppleMusicOnlineSearchResult(
            navController = navController,
            searchSort = searchSort,
            onSearchSortChange = onSearchSortChange,
        )
        return
    }

    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    // ── Bottom search field state (seeded from the route, re-search navigates) ──
    var fieldQuery by rememberSaveable(viewModel.query) { mutableStateOf(viewModel.query) }
    val navigateWithQuery: (String, SearchProvider) -> Unit = { text, provider ->
        if (text.isNotBlank()) {
            val replacementRoute = onlineSearchResultRoute(text, provider)
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
    }

    val searchFilter by viewModel.filter.collectAsStateWithLifecycle()
    val searchSummary = viewModel.summaryPage
    val itemsPage by remember(searchFilter) {
        derivedStateOf {
            searchFilter?.value?.let {
                viewModel.viewStateMap[it]
            }
        }
    }
    val allModeSections =
        buildList<SearchSummary> {
            searchSummary
                ?.summaries
                ?.firstOrNull()
                ?.takeIf { it.items.isNotEmpty() }
                ?.let(::add)

            listOf(
                FILTER_SONG to stringResource(R.string.filter_songs),
                FILTER_VIDEO to stringResource(R.string.filter_videos),
                FILTER_ALBUM to stringResource(R.string.filter_albums),
                FILTER_ARTIST to stringResource(R.string.filter_artists),
                FILTER_COMMUNITY_PLAYLIST to stringResource(R.string.filter_community_playlists),
                FILTER_FEATURED_PLAYLIST to stringResource(R.string.filter_featured_playlists),
            ).forEach { (sectionFilter, sectionTitle) ->
                viewModel.viewStateMap[sectionFilter.value]
                    ?.items
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { items ->
                        add(SearchSummary(title = sectionTitle, items = viewModel.sortedItems(items, searchSort)))
                    }
            }
        }
    val isAllModeLoaded =
        searchSummary != null ||
            listOf(
                FILTER_SONG,
                FILTER_VIDEO,
                FILTER_ALBUM,
                FILTER_ARTIST,
                FILTER_COMMUNITY_PLAYLIST,
                FILTER_FEATURED_PLAYLIST,
            ).all { viewModel.viewStateMap.containsKey(it.value) }

    LaunchedEffect(lazyListState) {
        snapshotFlow {
            lazyListState.layoutInfo.visibleItemsInfo.any { it.key == "loading" }
        }.collect { shouldLoadMore ->
            if (!shouldLoadMore) return@collect
            viewModel.loadMore()
        }
    }

    val ytItemContent: @Composable LazyItemScope.(YTItem) -> Unit = { item: YTItem ->
        val longClick: (() -> Unit)? =
            if (item is PodcastItem || item is EpisodeItem) {
                null
            } else {
                {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuState.show {
                        when (item) {
                            is SongItem -> {
                                YouTubeSongMenu(
                                    song = item,
                                    navController = navController,
                                    onDismiss = menuState::dismiss,
                                )
                            }

                            is AlbumItem -> {
                                YouTubeAlbumMenu(
                                    albumItem = item,
                                    navController = navController,
                                    onDismiss = menuState::dismiss,
                                )
                            }

                            is ArtistItem -> {
                                YouTubeArtistMenu(
                                    artist = item,
                                    onDismiss = menuState::dismiss,
                                )
                            }

                            is PlaylistItem -> {
                                YouTubePlaylistMenu(
                                    playlist = item,
                                    coroutineScope = coroutineScope,
                                    onDismiss = menuState::dismiss,
                                )
                            }

                            is PodcastItem, is EpisodeItem -> Unit
                        }
                    }
                }
            }
        YouTubeListItem(
            item = item,
            viewCountText = (item as? SongItem)?.viewCountText,
            isActive =
                when (item) {
                    is SongItem -> mediaMetadata?.id == item.id
                    is AlbumItem -> mediaMetadata?.album?.id == item.id
                    else -> false
                },
            isPlaying = isPlaying,
            trailingContent = {
                if (longClick != null) {
                    IconButton(
                        onClick = longClick,
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
                                    val playAsVideo = searchFilter == FILTER_VIDEO
                                    val sameTrack = item.id == mediaMetadata?.id
                                    val currentIsVideo = sameTrack && mediaMetadata?.isMusicVideo == true
                                    if (sameTrack && (!playAsVideo || currentIsVideo)) {
                                        playerConnection.player.togglePlayPause()
                                    } else {
                                        val seedMetadata =
                                            item.toMediaMetadata().let { metadata ->
                                                if (playAsVideo && !metadata.isMusicVideo) {
                                                    metadata.copy(isMusicVideo = true)
                                                } else {
                                                    metadata
                                                }
                                            }
                                        playerConnection.playQueue(
                                            YouTubeQueue(
                                                item.endpoint ?: WatchEndpoint(videoId = item.id),
                                                seedMetadata,
                                            ),
                                        )
                                    }
                                }

                                is AlbumItem -> {
                                    navController.navigate("album/${item.id}")
                                }

                                is ArtistItem -> {
                                    navController.navigate("artist/${item.id}")
                                }

                                is PlaylistItem -> {
                                    navController.navigate("online_playlist/${item.id}")
                                }

                                is PodcastItem -> {
                                    navController.navigate("podcast/${android.net.Uri.encode(item.browseId)}")
                                }

                                is EpisodeItem -> {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = item.podcast?.name ?: item.title,
                                            items = listOf(item.toMediaItem()),
                                        ),
                                    )
                                }
                            }
                        },
                        onLongClick = longClick,
                    ).animateItem(),
        )
    }

    // ── Layout: transparent haze top (like Home) + bottom glass pills ──
    val barState = rememberSearchResultsBarState()
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current
    val playerAwareBottomPadding =
        LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Bottom)
            .asPaddingValues()
            .calculateBottomPadding()

    // The top header (glass "< Search" pill + title + chips) is a SIBLING of
    // the glass-tagged LazyColumn, never an item inside it: a recorder that
    // contains its own liquidGlass consumers is circular (the consumer would
    // draw the very layer being recorded) and crashes the RenderThread. It
    // still scrolls away exactly like an in-flow first item — translated by
    // the list's scroll offset and fading out — and its measured height feeds
    // the list's top content padding.
    var resultsHeaderHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val resultsHeaderReserve =
        remember(resultsHeaderHeightPx, density) {
            with(density) { resultsHeaderHeightPx.toDp() }
        }
    val headerScrollAwayFraction by remember {
        derivedStateOf {
            when {
                resultsHeaderHeightPx <= 0 -> 0f
                lazyListState.firstVisibleItemIndex > 0 -> 1f
                else -> {
                    // The first item's viewport offset decreases continuously
                    // from its padded rest position as the list scrolls — a
                    // smooth proxy for "how far the header has scrolled away"
                    // that never pops across item boundaries.
                    val firstTop =
                        lazyListState.layoutInfo.visibleItemsInfo
                            .firstOrNull()?.offset?.toFloat() ?: 0f
                    1f - (firstTop / resultsHeaderHeightPx).coerceIn(0f, 1f)
                }
            }
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            state = lazyListState,
            contentPadding =
                LocalPlayerAwareWindowInsets.current
                    .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                    .add(WindowInsets(top = systemBarsTopPadding + 8.dp + resultsHeaderReserve))
                    .add(WindowInsets(bottom = SearchResultsOverlayReserve))
                    .asPaddingValues(),
            modifier =
                Modifier
                    .fillMaxSize()
                    .searchResultsBarSource(barState),
        ) {

            if (searchFilter == null) {
                allModeSections.forEachIndexed { index, summary ->
                    if (index > 0) {
                        item(key = "divider_$index", contentType = "divider") {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
                        }
                    }

                    item(
                        key = "section_header_${summary.title}_$index",
                        contentType = "section_header",
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .width(3.dp)
                                        .height(18.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(MaterialTheme.colorScheme.primary),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = summary.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    itemsIndexed(
                        items = viewModel.sortedItems(summary.items, searchSort),
                        key = { itemIndex, item -> "${summary.title}/${item.id}/$itemIndex" },
                        contentType = { _, _ -> "search_result" },
                    ) { _, item ->
                        ytItemContent(item)
                    }

                    item(
                        key = "section_spacer_${summary.title}_$index",
                        contentType = "section_spacer",
                    ) {
                        Spacer(Modifier.height(4.dp))
                    }
                }

                if (allModeSections.isEmpty() && isAllModeLoaded) {
                    item(key = "empty_all", contentType = "empty") {
                        EmptyPlaceholder(
                            icon = R.drawable.search,
                            text = stringResource(R.string.no_results_found),
                        )
                    }
                }
            } else {
                items(
                    items = viewModel.sortedItems(itemsPage?.items.orEmpty().distinctBy { it.id }, searchSort),
                    key = { "filtered_${it.id}" },
                    contentType = { "search_result" },
                    itemContent = ytItemContent,
                )

                if (itemsPage?.continuation != null) {
                    item(key = "loading", contentType = "loading") {
                        ShimmerHost {
                            repeat(3) {
                                ListItemPlaceHolder()
                            }
                        }
                    }
                }

                if (itemsPage?.items?.isEmpty() == true) {
                    item(key = "empty_filtered", contentType = "empty") {
                        EmptyPlaceholder(
                            icon = R.drawable.search,
                            text = stringResource(R.string.no_results_found),
                        )
                    }
                }
            }

            if (searchFilter == null && allModeSections.isEmpty() && !isAllModeLoaded || searchFilter != null && itemsPage == null) {
                item(key = "initial_loading", contentType = "loading") {
                    ShimmerHost {
                        repeat(8) {
                            ListItemPlaceHolder()
                        }
                    }
                }
            }
        }

        // ── Top header overlay: glass "< Search" pill, LARGE query title,
        // solid category pills — a SIBLING above the glass-tagged list that
        // scrolls and fades away with the list's first item. ─────────────
        SearchResultsTopHeader(
            state = barState,
            query = viewModel.query,
            onBack = { navController.navigateUp() },
            onBackLongClick = { navController.backToMain() },
            chipsRow = {
                SolidFilterChipsRow(
                    chips =
                        listOf(
                            null to stringResource(R.string.filter_all),
                            FILTER_SONG to stringResource(R.string.filter_songs),
                            FILTER_VIDEO to stringResource(R.string.filter_videos),
                            FILTER_ALBUM to stringResource(R.string.filter_albums),
                            FILTER_ARTIST to stringResource(R.string.filter_artists),
                            FILTER_COMMUNITY_PLAYLIST to stringResource(R.string.filter_community_playlists),
                            FILTER_FEATURED_PLAYLIST to stringResource(R.string.filter_featured_playlists),
                            PODCAST_SEARCH_FILTER to stringResource(R.string.filter_podcasts),
                        ),
                    currentValue = searchFilter,
                    onValueUpdate = {
                        if (viewModel.filter.value != it) {
                            viewModel.filter.value = it
                        }
                        coroutineScope.launch {
                            // The header is no longer list item 0 — jumping to
                            // the first CONTENT item now means index 0.
                            lazyListState.animateScrollToItem(0)
                        }
                    },
                )
            },
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    // The header previously rode the list's top content
                    // padding; as an overlay it carries its own.
                    .padding(top = systemBarsTopPadding + 8.dp)
                    .onSizeChanged { resultsHeaderHeightPx = it.height }
                    .graphicsLayer {
                        translationY = -resultsHeaderHeightPx * headerScrollAwayFraction
                        alpha = 1f - headerScrollAwayFraction
                    },
        )

        // Transparent blurred top, exactly like the home screen — but only
        // once the results have actually scrolled under it.
        ScreenHeaderHaze(
            hazeState = barState.haze,
            systemBarsTopPadding = systemBarsTopPadding + 8.dp,
            scrolled = lazyListState.canScrollBackward,
        )

        // Bottom chrome: category pills above the back + search glass pills.
        SearchResultsBottomOverlay(
            state = barState,
            query = fieldQuery,
            onQueryChange = { fieldQuery = it },
            onSearch = { navigateWithQuery(it, viewModel.searchProvider) },
            onBack = { navController.navigateUp() },
            onBackLongClick = { navController.backToMain() },
            placeholder = stringResource(R.string.search_yt_music),
            bottomPadding = playerAwareBottomPadding,
            lazyListState = lazyListState,
            trailing = {
                SearchSourcePicker(
                    currentScope = SearchSource.ONLINE,
                    currentProvider = viewModel.searchProvider,
                    onSelection = { _, provider ->
                        navigateWithQuery(fieldQuery.ifBlank { viewModel.query }, provider)
                    },
                    includeLocal = false,
                )
                SearchResultsSortMenu(
                    selectedSort = searchSort,
                    onSortSelected = onSearchSortChange,
                )
            },
        )
    }
}
