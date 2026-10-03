/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.search

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
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
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.applemusic.AppleMusicPlaybackResolver
import moe.rukamori.archivetune.applemusic.AppleMusicSearchItem
import moe.rukamori.archivetune.constants.SearchProvider
import moe.rukamori.archivetune.constants.SearchSource
import moe.rukamori.archivetune.constants.ListThumbnailSize
import moe.rukamori.archivetune.constants.ThumbnailCornerRadius
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.ui.component.EmptyPlaceholder
import moe.rukamori.archivetune.ui.component.ItemThumbnail
import moe.rukamori.archivetune.ui.component.ListItem
import moe.rukamori.archivetune.ui.component.SearchSourcePicker
import moe.rukamori.archivetune.ui.screens.ScreenHeaderHaze
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.joinByBullet
import moe.rukamori.archivetune.viewmodels.AppleMusicSearchViewModel
import moe.rukamori.archivetune.viewmodels.OnlineSearchSort
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

private enum class AppleMusicSearchFilter {
    ALL,
    TRACKS,
    ALBUMS,
    ARTISTS,
}

@Composable
internal fun AppleMusicOnlineSearchResult(
    navController: NavController,
    searchSort: OnlineSearchSort = OnlineSearchSort.DEFAULT,
    onSearchSortChange: (OnlineSearchSort) -> Unit = {},
    viewModel: AppleMusicSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current
    val lazyListState = rememberLazyListState()
    var filter by rememberSaveable { mutableStateOf(AppleMusicSearchFilter.ALL) }
    var fieldQuery by rememberSaveable(viewModel.query) { mutableStateOf(viewModel.query) }

    val visibleItems =
        remember(state.items, filter) {
            state.items.filter { item ->
                when (filter) {
                    AppleMusicSearchFilter.ALL -> true
                    AppleMusicSearchFilter.TRACKS -> item is AppleMusicSearchItem.Track
                    AppleMusicSearchFilter.ALBUMS -> item is AppleMusicSearchItem.Album
                    AppleMusicSearchFilter.ARTISTS -> item is AppleMusicSearchItem.Artist
                }
            }
        }

    LaunchedEffect(lazyListState, state.hasMore, state.isLoading) {
        if (!state.hasMore) return@LaunchedEffect
        snapshotFlow { lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastIndex ->
                if (lastIndex != null && lastIndex >= visibleItems.lastIndex - 2) {
                    viewModel.loadMore()
                }
            }
    }

    val barState = rememberSearchResultsBarState()
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current
    val playerAwareBottomPadding =
        LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Bottom)
            .asPaddingValues()
            .calculateBottomPadding()

    val header: @Composable () -> Unit = {
        SearchResultsTopHeader(
            query = viewModel.query,
            onBack = { navController.navigateUp() },
            onBackLongClick = { navController.backToMain() },
            chipsRow = {
                SolidFilterChipsRow(
                    chips =
                        listOf(
                            AppleMusicSearchFilter.ALL to stringResource(R.string.filter_all),
                            AppleMusicSearchFilter.TRACKS to stringResource(R.string.filter_songs),
                            AppleMusicSearchFilter.ALBUMS to stringResource(R.string.filter_albums),
                            AppleMusicSearchFilter.ARTISTS to stringResource(R.string.filter_artists),
                        ),
                    currentValue = filter,
                    onValueUpdate = { filter = it },
                )
            },
        )
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .searchResultsBarSource(barState),
        ) {
            when {
                state.isLoading && state.items.isEmpty() -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(top = systemBarsTopPadding + 4.dp),
                    ) {
                        header()
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }

                state.errorMessage != null && state.items.isEmpty() -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(top = systemBarsTopPadding + 4.dp),
                    ) {
                        header()
                        EmptyPlaceholder(
                            icon = R.drawable.apple_music_icon,
                            text = state.errorMessage ?: stringResource(R.string.no_results_found),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                visibleItems.isEmpty() -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(top = systemBarsTopPadding + 4.dp),
                    ) {
                        header()
                        EmptyPlaceholder(
                            icon = R.drawable.search,
                            text = stringResource(R.string.no_results_found),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        state = lazyListState,
                        contentPadding =
                            LocalPlayerAwareWindowInsets.current
                                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                                .add(WindowInsets(top = systemBarsTopPadding + 4.dp))
                                .add(WindowInsets(bottom = SearchResultsOverlayReserve))
                                .asPaddingValues(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        item(key = "results_header", contentType = "results_header") {
                            header()
                        }
                        item(key = "apple_music_result_label") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.search_apple_music),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        itemsIndexed(
                            items = visibleItems,
                            key = { _, item -> item.key },
                            contentType = { _, item -> item::class },
                        ) { _, item ->
                            AppleMusicSearchResultRow(
                                item = item,
                                playerConnection = playerConnection,
                                coroutineScope = coroutineScope,
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            )
                        }
                        if (state.isLoading) {
                            item(key = "apple_music_loading_more") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                }
            }
        }

        ScreenHeaderHaze(
            hazeState = barState.haze,
            systemBarsTopPadding = systemBarsTopPadding + 4.dp,
            scrolled = lazyListState.canScrollBackward,
        )

        SearchResultsBottomOverlay(
            state = barState,
            query = fieldQuery,
            onQueryChange = { fieldQuery = it },
            onSearch = { text ->
                if (text.isNotBlank()) {
                    val replacementRoute = onlineSearchResultRoute(text, SearchProvider.APPLE_MUSIC)
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
            },
            onBack = { navController.navigateUp() },
            onBackLongClick = { navController.backToMain() },
            placeholder = stringResource(R.string.search_source_apple_music),
            bottomPadding = playerAwareBottomPadding,
            lazyListState = lazyListState,
            trailing = {
                SearchSourcePicker(
                    currentScope = SearchSource.ONLINE,
                    currentProvider = SearchProvider.APPLE_MUSIC,
                    onSelection = { _, provider ->
                        val text = fieldQuery.ifBlank { viewModel.query }
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
                    },
                    includeLocal = false,
                )

            },
        )
    }
}

@Composable
private fun AppleMusicSearchResultRow(
    item: AppleMusicSearchItem,
    playerConnection: moe.rukamori.archivetune.playback.PlayerConnection?,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
) {
    val context = LocalContext.current
    var resolving by remember(item.key) { mutableStateOf(false) }

    val openExternal: () -> Unit = {
        val url =
            when (item) {
                is AppleMusicSearchItem.Track -> item.viewUrl
                is AppleMusicSearchItem.Album -> item.viewUrl
                is AppleMusicSearchItem.Artist -> item.viewUrl
            }
        if (url != null) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
    }

    val onClick: () -> Unit = {
        when (item) {
            is AppleMusicSearchItem.Track -> {
                if (playerConnection != null && !resolving) {
                    resolving = true
                    coroutineScope.launch {
                        try {
                            val song =
                                withContext(Dispatchers.IO) {
                                    AppleMusicPlaybackResolver.resolveTrack(item)
                                }
                            if (song != null) {
                                playerConnection.playQueue(YouTubeQueue.radio(song.toMediaMetadata()))
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.apple_music_track_unavailable),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        } finally {
                            resolving = false
                        }
                    }
                }
            }

            is AppleMusicSearchItem.Album,
            is AppleMusicSearchItem.Artist,
            -> openExternal()
        }
    }

    AppleMusicItemRow(
        item = item,
        onClick = onClick,
        trailingContent = {
            if (resolving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        },
    )
}

@Composable
internal fun AppleMusicItemRow(
    item: AppleMusicSearchItem,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    val subtitle =
        when (item) {
            is AppleMusicSearchItem.Track -> item.artist

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
        badges = {
            if (item is AppleMusicSearchItem.Track && item.explicit) {
                Icon(
                    painter = painterResource(R.drawable.explicit),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        thumbnailContent = {
            ItemThumbnail(
                thumbnailUrl = item.artworkUrl,
                isActive = false,
                isPlaying = false,
                shape =
                    when (item) {
                        is AppleMusicSearchItem.Artist -> CircleShape
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
                painter = painterResource(R.drawable.apple_music_icon),
                contentDescription = stringResource(R.string.search_source_apple_music),
                modifier = Modifier.size(18.dp),
            )
        },
        modifier = rowModifier,
    )
}
