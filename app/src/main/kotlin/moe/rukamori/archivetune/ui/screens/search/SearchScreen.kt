/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalFoundationApi::class)

package moe.rukamori.archivetune.ui.screens.search

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.ui.component.liquidGlassContentColor
import moe.rukamori.archivetune.ui.component.liquidGlass
import moe.rukamori.archivetune.ui.component.glassSource
import kotlinx.coroutines.delay
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.ExperimentalFoundationApi
import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.hilt.navigation.compose.hiltViewModel
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.DefaultSearchSourceKey
import moe.rukamori.archivetune.constants.DisableBlurKey
import moe.rukamori.archivetune.constants.SearchProvider
import moe.rukamori.archivetune.constants.SearchSource
import moe.rukamori.archivetune.db.entities.SearchHistory
import moe.rukamori.archivetune.ui.component.SearchSourcePicker
import moe.rukamori.archivetune.ui.screens.LocalSearchHazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import dev.chrisbanes.haze.HazeProgressive
import moe.rukamori.archivetune.viewmodels.SearchHistoryViewModel
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.constants.HideExplicitKey
import moe.rukamori.archivetune.constants.HideVideoKey
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SearchSuggestions
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.models.filterExplicit
import moe.rukamori.archivetune.innertube.models.filterVideo
import moe.rukamori.archivetune.innertube.pages.SearchSummaryPage
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.ui.component.YouTubeListItem
import androidx.compose.runtime.setValue

private val SearchHorizontalPadding = 24.dp
private const val RecentsHeightFraction = 0.55f

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    navController: NavController,
    onSearchQuery: (String) -> Unit,
    onVoiceSearch: () -> Unit = {},
    headerScrollConnection: NestedScrollConnection? = null,
    historyViewModel: SearchHistoryViewModel = hiltViewModel(),
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchProvider by rememberEnumPreference(DefaultSearchSourceKey, SearchProvider.YOUTUBE)

    var liveSuggestions by remember { mutableStateOf<SearchSuggestions?>(null) }
    var liveSummary by remember { mutableStateOf<SearchSummaryPage?>(null) }
    val (hideExplicit) = rememberPreference(HideExplicitKey, defaultValue = false)
    val (hideVideo) = rememberPreference(HideVideoKey, defaultValue = false)
    LaunchedEffect(searchQuery, searchProvider, hideExplicit, hideVideo) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            liveSuggestions = null
            liveSummary = null
            return@LaunchedEffect
        }

        delay(250)
        if (searchQuery.trim() != query) return@LaunchedEffect

        YouTube
            .searchSuggestions(query)
            .onSuccess { page ->
                if (searchQuery.trim() == query) {
                    liveSuggestions =
                        SearchSuggestions(
                            queries = page.queries,
                            recommendedItems =
                                page.recommendedItems
                                    .filterExplicit(hideExplicit)
                                    .filterVideo(hideVideo),
                        )
                }
            }
        if (searchProvider == SearchProvider.YOUTUBE) {
            YouTube
                .searchSummary(query)
                .onSuccess { page ->
                    if (searchQuery.trim() == query) {
                        liveSummary =
                            page
                                .filterExplicit(hideExplicit)
                                .filterVideo(hideVideo)
                    }
                }
        }
    }

    val onSearchSourceSelection: (SearchSource, SearchProvider) -> Unit = { _, provider ->
        searchProvider = provider
    }
    val recentSearches by historyViewModel.recentSearches.collectAsStateWithLifecycle()
    val searchHazeState = LocalSearchHazeState.current
    val (disableBlur) = rememberPreference(DisableBlurKey, false)

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
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

    val barState = rememberSearchResultsBarState()

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
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .let { m ->
                        if (barState.backdrop != null) m.glassSource(barState.backdrop!!) else m
                    },
        ) {
            // Root-level subtle atmosphere gradient — see MainActivity.
        }

        BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(
                            WindowInsets.ime.union(
                                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom),
                            ),
                        ),
            ) {
                val recentsMaxHeight = maxHeight * RecentsHeightFraction

                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (searchQuery.isNotBlank()) {
                        LiveSearchSuggestionsPanel(
                            suggestions = liveSuggestions,
                            summary = liveSummary,
                            navController = navController,
                            maxHeight = recentsMaxHeight,
                            hazeState = searchHazeState,
                            disableBlur = disableBlur,
                            onQueryPick = { picked ->
                                searchQuery = picked
                                onSearchQuery(picked)
                            },
                        )
                    } else {
                        RecentSearchesPanel(
                            recentSearches = recentSearches,
                            maxHeight = recentsMaxHeight,
                            onClearAll = historyViewModel::clearAll,
                            onPick = onSearchQuery,
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    SearchTabBottomChrome(
                        barState = barState,
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = {
                            onSearchQuery(it)
                        },
                        onVoiceSearch = onVoiceSearch,
                        onBack = navController::navigateUp,
                        onBackLongClick = navController::backToMain,
                        focusRequester = focusRequester,
                        searchProvider = searchProvider,
                        onSourceSelection = onSearchSourceSelection,
                    )
                }
            }
    }
}

@Composable
private fun RecentSearchesPanel(
    recentSearches: List<SearchHistory>,
    maxHeight: androidx.compose.ui.unit.Dp,
    onClearAll: () -> Unit,
    onPick: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState()),
    ) {
        if (recentSearches.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painter = painterResource(R.drawable.search),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.search_no_recent),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = SearchHorizontalPadding,
                            end = SearchHorizontalPadding,
                            top = 10.dp,
                            bottom = 6.dp,
                        ),
            ) {
                Text(
                    text = stringResource(R.string.search_recent_searches),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.clear),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onClearAll)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            recentSearches.forEachIndexed { index, item ->
                RecentSearchRow(
                    history = item,
                    onClick = { onPick(item.query) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SearchHorizontalPadding),
                )
                if (index < recentSearches.lastIndex) {
                    HorizontalDivider(
                        modifier =
                            Modifier.padding(
                                start = SearchHorizontalPadding,
                                end = SearchHorizontalPadding,
                            ),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun LiveSearchSuggestionsPanel(
    suggestions: SearchSuggestions?,
    summary: SearchSummaryPage?,
    navController: NavController,
    maxHeight: Dp,
    hazeState: HazeState?,
    disableBlur: Boolean,
    onQueryPick: (String) -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()

    val queries = suggestions?.queries.orEmpty()
    val summarySections =
        summary?.summaries.orEmpty().filter { it.items.isNotEmpty() }
    val fallbackItems =
        if (summarySections.isEmpty()) suggestions?.recommendedItems.orEmpty() else emptyList()

    if (queries.isEmpty() && summarySections.isEmpty() && fallbackItems.isEmpty()) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight),
        )
        return
    }

    // Recommendations render ABOVE the query completions: newly loaded
    // summary sections prepend at the panel's top (always inside the visible
    // window once the list clips) while the query rows the user is reading
    // stay anchored right above the search bar instead of jumping upward.
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight),
    ) {
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxWidth(),
        ) {
            if (summarySections.isNotEmpty()) {
                summarySections.forEach { section ->
                    item(key = "summary_header_${section.title}") {
                        Text(
                            text = section.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = SearchHorizontalPadding,
                                        end = SearchHorizontalPadding,
                                        top = 14.dp,
                                        bottom = 4.dp,
                                    ),
                        )
                    }
                    items(
                        items = section.items,
                        key = { it.id },
                        contentType = { 2 },
                    ) { item ->
                        SuggestedResultRow(
                            item = item,
                            currentMediaId = mediaMetadata?.id,
                            currentAlbumId = mediaMetadata?.album?.id,
                            isPlaying = isPlaying,
                            navController = navController,
                            playerConnection = playerConnection,
                            haptic = haptic,
                        )
                    }
                }
            } else if (fallbackItems.isNotEmpty()) {
                items(
                    items = fallbackItems,
                    key = { it.id },
                    contentType = { 2 },
                ) { item ->
                    SuggestedResultRow(
                        item = item,
                        currentMediaId = mediaMetadata?.id,
                        currentAlbumId = mediaMetadata?.album?.id,
                        isPlaying = isPlaying,
                        navController = navController,
                        playerConnection = playerConnection,
                        haptic = haptic,
                    )
                }
            }

            items(
                count = queries.size,
                key = { index -> "query_$index" },
                contentType = { 1 },
            ) { index ->
                val query = queries[index]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onQueryPick(query) },
                                onLongClick = {},
                            )
                            .padding(horizontal = SearchHorizontalPadding, vertical = 10.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.search),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = query,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Progressive haze fade over the panel's top edge while the list is
        // scrolled — the same ultraThin progressive treatment the home header
        // uses, so content dissolving off the top blurs into the background.
        if (hazeState != null && !disableBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scrolledAway by remember {
                derivedStateOf {
                    listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
                }
            }
            val fadeAlpha by animateFloatAsState(
                targetValue = if (scrolledAway) 1f else 0f,
                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                label = "searchSuggestionsHaze",
            )
            if (fadeAlpha > 0.01f) {
                val pageColor = MaterialTheme.colorScheme.surface
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(28.dp)
                            .graphicsLayer { alpha = fadeAlpha }
                            .hazeEffect(
                                state = hazeState,
                                style = HazeMaterials.ultraThin(pageColor),
                            ) {
                                progressive =
                                    HazeProgressive.verticalGradient(
                                        easing = EaseOutCubic,
                                        startIntensity = 0.75f,
                                        endIntensity = 0f,
                                    )
                                noiseFactor = 0f
                            },
                )
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(28.dp)
                            .graphicsLayer { alpha = fadeAlpha }
                            .background(
                                Brush.verticalGradient(
                                    colorStops =
                                        Array(4) { i ->
                                            val t = i / 3f
                                            t to pageColor.copy(alpha = 0.30f * (1f - t))
                                        },
                                ),
                            ),
                )
            }
        }
    }
}

@Composable
private fun SuggestedResultRow(
    item: YTItem,
    currentMediaId: String?,
    currentAlbumId: String?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    haptic: HapticFeedback,
) {
    YouTubeListItem(
        item = item,
        isActive =
            when (item) {
                is SongItem -> currentMediaId == item.id
                is AlbumItem -> currentAlbumId == item.id
                else -> false
            },
        isPlaying = isPlaying,
        isSwipeable = false,
        modifier =
            Modifier.combinedClickable(
                onClick = {
                    when (item) {
                        is SongItem -> {
                            if (item.id == currentMediaId) {
                                playerConnection.player.togglePlayPause()
                            } else {
                                playerConnection.playQueue(
                                    YouTubeQueue(
                                        item.endpoint ?: WatchEndpoint(videoId = item.id),
                                        item.toMediaMetadata(),
                                    ),
                                )
                            }
                        }

                        is AlbumItem -> navController.navigate("album/${item.id}")

                        is ArtistItem -> navController.navigate("artist/${item.id}")

                        is PlaylistItem -> navController.navigate("online_playlist/${item.id}")

                        else -> {}
                    }
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            ),
    )
}

@Composable
private fun SearchTabBottomChrome(
    barState: SearchResultsBarState,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onVoiceSearch: () -> Unit,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
    focusRequester: FocusRequester,
    searchProvider: SearchProvider,
    onSourceSelection: (SearchSource, SearchProvider) -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val backdrop = barState.backdrop
    val glassContentColor = if (backdrop != null) liquidGlassContentColor() else MaterialTheme.colorScheme.onSurface

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                ),
    ) {
        val backShape = CircleShape
        val backModifier =
            if (backdrop != null) {
                Modifier.liquidGlass(
                    backdrop = backdrop,
                    shape = backShape,
                    interactive = true,
                )
            } else {
                Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, backShape)
            }
        Box(
            modifier =
                backModifier
                    .size(48.dp)
                    .combinedClickable(
                        onClick = onBack,
                        onLongClick = onBackLongClick,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.arrow_back),
                contentDescription = stringResource(R.string.back_button_desc),
                tint = glassContentColor,
                modifier = Modifier.size(22.dp),
            )
        }

        val pillShape = RoundedCornerShape(24.dp)
        val pillModifier =
            if (backdrop != null) {
                Modifier.liquidGlass(
                    backdrop = backdrop,
                    shape = pillShape,
                    interactive = true,
                )
            } else {
                Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, pillShape)
            }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .weight(1f)
                    .height(52.dp)
                    .then(pillModifier)
                    .padding(start = 6.dp, end = 2.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.search),
                contentDescription = null,
                tint = glassContentColor.copy(alpha = 0.72f),
                modifier =
                    Modifier
                        .padding(start = 12.dp)
                        .size(22.dp),
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle =
                    MaterialTheme.typography.titleMedium
                        .copy(fontSize = 16.sp)
                        .copy(color = glassContentColor),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions =
                    KeyboardActions(
                        onSearch = {
                            if (query.isNotEmpty()) {
                                onSearch(query)
                                keyboardController?.hide()
                            }
                        },
                    ),
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                        .focusRequester(focusRequester),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                text =
                                    stringResource(
                                        if (searchProvider == SearchProvider.SPOTIFY) {
                                            R.string.search_source_spotify
                                        } else if (searchProvider == SearchProvider.APPLE_MUSIC) {
                                            R.string.search_source_apple_music
                                        } else {
                                            R.string.search_yt_music
                                        },
                                    ),
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                                color = glassContentColor.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            IconButton(
                onClick = onVoiceSearch,
                // Start padding shifts the mic RIGHT, away from the text field
                // and toward the source picker (increasing the end padding
                // instead moved it left — the picker is pinned to the row's
                // right edge, so end padding only widens the mic-picker gap).
                modifier = Modifier.padding(start = 10.dp, end = 4.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.mic),
                    contentDescription = stringResource(R.string.voice_search),
                    tint = glassContentColor.copy(alpha = 0.72f),
                    modifier = Modifier.size(22.dp),
                )
            }
            SearchSourcePicker(
                currentScope = SearchSource.ONLINE,
                currentProvider = searchProvider,
                onSelection = onSourceSelection,
                includeLocal = false,
            )
        }
    }
}

@Composable
private fun RecentSearchRow(
    history: SearchHistory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = history.query,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.search_recent_label),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painter = painterResource(R.drawable.arrow_forward),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

