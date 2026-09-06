/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.annotation.DrawableRes
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateSet
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.CoroutineScope
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.GridThumbnailHeight
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.ui.component.IconButton as AppIconButton
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.YouTubeGridItem
import moe.rukamori.archivetune.ui.component.shimmer.GridItemPlaceHolder
import moe.rukamori.archivetune.ui.component.shimmer.ShimmerHost
import moe.rukamori.archivetune.ui.menu.YouTubeAlbumMenu
import moe.rukamori.archivetune.ui.utils.backToMain
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.ui.component.liquidGlassContentColor
import moe.rukamori.archivetune.viewmodels.NewReleaseContent
import moe.rukamori.archivetune.viewmodels.NewReleaseUiState
import moe.rukamori.archivetune.viewmodels.NewReleaseViewModel

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NewReleaseScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: NewReleaseViewModel = hiltViewModel(),
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    // "Marked as read" toast — shown when the user marks releases read via
    // the selection mode's action bar (2026-09-06 redesign: manual
    // multi-select + mark-as-read; the previous long-press-to-mark and
    // mark-all header button were replaced by it).
    val showMarkedAsReadToast: () -> Unit = {
        Toast.makeText(context, R.string.marked_as_read, Toast.LENGTH_SHORT).show()
    }
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableStateOf(NewReleaseTab.All) }
    // Local search state — filters releases by album/artist name. The search
    // icon in the top app bar toggles a search field; typing filters the
    // visible grid in-place. Empty query = show all releases.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchActive by rememberSaveable { mutableStateOf(false) }

    // Selection mode ("mark as read", 2026-09-06): the header's edit icon
    // (liquid-glass pen in glass mode, pen IconButton in the plain top bar)
    // enters selection mode. Tapping releases toggles them; the bottom
    // action bar marks any number of selected releases as read (persisted,
    // removed from the feed, notifications cancelled) with a
    // "Marked as read" toast. Long-press outside selection mode keeps its
    // pre-2026-09-05 behavior: opens the album menu.
    var isSelectionMode by rememberSaveable { mutableStateOf(false) }
    val selectedReleaseIds = remember { mutableStateSetOf<String>() }

    // Persistent Liquid Glass header (2026-09-04): the History-page pattern —
    // back pill + search pill pinned over the scrolling content, plus the
    // header haze — replaces the normal top bar while Liquid Glass is on.
    val glassHeader = rememberGlassScreenHeader()
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // While the glass pills own the header, the normal bar (and the
            // in-bar search mode) is hidden — search moves to the trailing
            // glass pill. In search mode the SearchBar still renders in the
            // topBar slot so it stays reachable.
            if (isSearchActive || !glassHeader.liquidGlassActive) {
            // Switch between the normal top app bar and a Material3 SearchBar
            // when the user taps the search icon. Rendering the search bar in
            // the topBar slot (instead of as a grid item below the top app bar)
            // ensures it is always visible when search is active — even if the
            // grid has been scrolled down. Matches the pattern used by
            // NewsScreen and HistoryScreen.
            AnimatedContent(
                targetState = isSearchActive,
                transitionSpec = {
                    fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) togetherWith
                        fadeOut(spring(stiffness = Spring.StiffnessMediumLow))
                },
                label = "newReleaseTopBar",
            ) { searching ->
                if (searching) {
                    SearchBar(
                        inputField = {
                            SearchBarDefaults.InputField(
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                                onSearch = { isSearchActive = false },
                                expanded = false,
                                onExpandedChange = {},
                                placeholder = {
                                    Text(text = stringResource(R.string.search))
                                },
                                leadingIcon = {
                                    IconButton(
                                        onClick = {
                                            searchQuery = ""
                                            isSearchActive = false
                                        },
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.solar_arrow_left_linear),
                                            contentDescription = null,
                                        )
                                    }
                                },
                                trailingIcon =
                                    if (searchQuery.isNotEmpty()) {
                                        {
                                            IconButton(
                                                onClick = { searchQuery = "" },
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.solar_close_circle_linear),
                                                    contentDescription = null,
                                                )
                                            }
                                        }
                                    } else {
                                        null
                                    },
                            )
                        },
                        expanded = false,
                        onExpandedChange = {},
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(top = 8.dp, bottom = 4.dp),
                    ) {}
                } else {
                    // Plain top bar — no frosted pills. Modern, minimal.
                    LargeFlexibleTopAppBar(
                        title = {
                            Text(
                                text = stringResource(R.string.new_releases),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        },
                        navigationIcon = {
                            AppIconButton(
                                onClick = navController::navigateUp,
                                onLongClick = navController::backToMain,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.solar_arrow_left_linear),
                                    contentDescription = null,
                                )
                            }
                        },
                        actions = {
                            // Selection-mode toggle (2026-09-06): the plain-bar
                            // twin of the glass header's pen pill. Toggles
                            // selection mode so any number of releases can be
                            // selected manually and marked as read from the
                            // bottom action bar (replaces the previous
                            // mark-all-read button + long-press marking).
                            IconButton(
                                onClick = {
                                    isSelectionMode = !isSelectionMode
                                    selectedReleaseIds.clear()
                                },
                            ) {
                                Icon(
                                    painter =
                                        painterResource(
                                            if (isSelectionMode) {
                                                R.drawable.solar_close_circle_linear
                                            } else {
                                                R.drawable.solar_pen_linear
                                            },
                                        ),
                                    contentDescription = stringResource(R.string.select_releases),
                                    tint =
                                        if (isSelectionMode) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                )
                            }
                            // Using Material3's standard IconButton here (not the
                            // custom AppIconButton) because the custom one uses
                            // combinedClickable which can fail to register taps in
                            // the LargeFlexibleTopAppBar actions slot on some
                            // Material3 1.5.0-alpha builds. The standard IconButton
                            // uses a plain clickable and is more reliable here.
                            IconButton(
                                onClick = { isSearchActive = true },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.solar_magnifer_linear),
                                    contentDescription = stringResource(R.string.search),
                                )
                            }
                        },
                        colors = TopAppBarDefaults.largeTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        scrollBehavior = scrollBehavior,
                    )
                }
            }
            }
        },
        contentWindowInsets = LocalPlayerAwareWindowInsets.current,
    ) { paddingValues ->
        // In glass-header mode the topBar is empty, so the Scaffold's top
        // padding is 0 — the grid instead gets the pill zone (status bar +
        // pills + breathing room) as its content top padding, and the items
        // scroll under the pills/haze exactly like the History page.
        val contentTopPadding =
            if (glassHeader.liquidGlassActive && !isSearchActive) {
                systemBarsTopPadding + 72.dp // History pattern: content sits 12dp under the
            // pills so the glass actually samples it (2026-09-04 fix)
            } else {
                paddingValues.calculateTopPadding()
            }
        val adjustedPaddingValues =
            PaddingValues(
                start = paddingValues.calculateStartPadding(LocalLayoutDirection.current),
                top = contentTopPadding,
                end = paddingValues.calculateEndPadding(LocalLayoutDirection.current),
                bottom = paddingValues.calculateBottomPadding(),
            )
        Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = uiState,
            transitionSpec = {
                fadeIn(tween(300)) togetherWith fadeOut(tween(150))
            },
            modifier = Modifier.fillMaxSize().glassHeaderSource(glassHeader),
            label = "NewReleaseContent",
        ) { state ->
            when (state) {
                NewReleaseUiState.Loading -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = GridThumbnailHeight + 24.dp),
                        contentPadding = adjustedPaddingValues,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(12) {
                            ShimmerHost {
                                GridItemPlaceHolder(fillMaxWidth = true)
                            }
                        }
                    }
                }

                is NewReleaseUiState.Success -> {
                    NewReleaseGridContent(
                        content = state.content,
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it },
                        paddingValues = adjustedPaddingValues,
                        activeAlbumId = mediaMetadata?.album?.id,
                        isPlaying = isPlaying,
                        coroutineScope = coroutineScope,
                        searchQuery = searchQuery,
                        isSelectionMode = isSelectionMode,
                        selectedIds = selectedReleaseIds,
                        onReleaseClick = { album ->
                            if (isSelectionMode) {
                                // Selection mode: tap toggles the release's
                                // selection instead of navigating (the user
                                // picks what to mark as read).
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                if (album.id in selectedReleaseIds) {
                                    selectedReleaseIds.remove(album.id)
                                } else {
                                    selectedReleaseIds.add(album.id)
                                }
                            } else {
                                navController.navigate("album/${album.id}")
                            }
                        },
                        onReleaseLongClick = { album ->
                            // Restored pre-2026-09-05 behaviour (user request
                            // 2026-09-06: "remove that hold to mark as read"):
                            // long-press opens the album menu again.
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuState.show {
                                YouTubeAlbumMenu(
                                    albumItem = album,
                                    navController = navController,
                                    onDismiss = menuState::dismiss,
                                )
                            }
                        },
                        onRefresh = viewModel::retry,
                    )
                }

                NewReleaseUiState.Error -> {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(adjustedPaddingValues)
                                .padding(horizontal = 24.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                painter = painterResource(R.drawable.solar_danger_circle_linear),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "New releases are temporarily unavailable",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "ArchiveTune could not load this YouTube Music section. Try again later.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(24.dp))
                            Button(
                                onClick = viewModel::retry,
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }

                NewReleaseUiState.Empty -> {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(adjustedPaddingValues)
                                .padding(horizontal = 24.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.no_results_found),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(24.dp))
                            Button(
                                onClick = viewModel::retry,
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(stringResource(R.string.refresh))
                            }
                        }
                    }
                }
            }
        }

        // Persistent glass pills + header haze (History-page behaviour). The
        // search pill activates the same in-bar SearchBar flow the normal
        // top bar's search icon used, so the feature is fully preserved.
        if (glassHeader.liquidGlassActive && !isSearchActive) {
            GlassScreenHeaderOverlay(
                header = glassHeader,
                title = stringResource(R.string.new_releases),
                onBack = navController::navigateUp,
                onBackLongClick = navController::backToMain,
                // Trailing liquid-glass pill (2026-09-06, user request:
                // "add an edit icon in liquid glass on the right header that
                // lets me manually select as much as I like manually and then
                // I can mark them as read"): the selection-mode pen +
                // search, the same two affordances the plain top bar's
                // actions carry. In selection mode the pen becomes a close
                // button that clears the selection and exits.
                trailing = {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppIconButton(
                            onClick = {
                                isSelectionMode = !isSelectionMode
                                selectedReleaseIds.clear()
                            },
                            onLongClick = {},
                        ) {
                            Icon(
                                painter =
                                    painterResource(
                                        if (isSelectionMode) {
                                            R.drawable.solar_close_circle_linear
                                        } else {
                                            R.drawable.solar_pen_linear
                                        },
                                    ),
                                contentDescription = stringResource(R.string.select_releases),
                                tint = liquidGlassContentColor(),
                            )
                        }
                    }
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppIconButton(
                            onClick = { isSearchActive = true },
                            onLongClick = {},
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.search),
                                contentDescription = stringResource(R.string.search),
                                tint = liquidGlassContentColor(),
                            )
                        }
                    }
                },
            )
        }

        // Selection action bar (2026-09-06) — appears only in selection mode
        // with at least one release selected. "Mark as read" removes the
        // selected releases from the feed (persisted via the ViewModel, the
        // matching system notifications are cancelled), clears the selection
        // and shows a "Marked as read" toast. "Select all" selects every
        // release currently in the feed so the previous mark-all-read
        // capability remains available through manual selection.
        AnimatedVisibility(
            visible = isSelectionMode && selectedReleaseIds.isNotEmpty(),
            enter =
                slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it / 2 } +
                    fadeIn(tween(200)),
            exit =
                slideOutVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it / 2 } +
                    fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                border =
                    BorderStroke(
                        0.5.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    ),
                modifier =
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .navigationBarsPadding()
                        .fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.selected_count, selectedReleaseIds.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            // Select every release currently in the feed.
                            val state = uiState
                            if (state is NewReleaseUiState.Success) {
                                selectedReleaseIds.addAll(
                                    (state.content.albums + state.content.singles + state.content.eps)
                                        .map { it.id },
                                )
                            }
                        },
                    ) {
                        Text(stringResource(R.string.select_all))
                    }
                    TextButton(
                        onClick = {
                            isSelectionMode = false
                            selectedReleaseIds.clear()
                        },
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    FilledTonalButton(
                        onClick = {
                            viewModel.markAsRead(selectedReleaseIds.toSet())
                            showMarkedAsReadToast()
                            selectedReleaseIds.clear()
                            isSelectionMode = false
                        },
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.solar_check_circle_linear),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.mark_as_read))
                    }
                }
            }
        }
        }
    }
}

@Immutable
private enum class NewReleaseTab(
    @StringRes val titleRes: Int,
    @DrawableRes val iconRes: Int,
    val contentType: String,
) {
    All(
        titleRes = R.string.filter_all,
        iconRes = R.drawable.solar_library_linear,
        contentType = "new_release_all_grid_item",
    ),
    Albums(
        titleRes = R.string.albums,
        iconRes = R.drawable.solar_album_linear,
        contentType = "new_release_album_grid_item",
    ),
    Singles(
        titleRes = R.string.singles,
        iconRes = R.drawable.solar_music_note_2_linear,
        contentType = "new_release_single_grid_item",
    ),
    Ep(
        titleRes = R.string.ep,
        iconRes = R.drawable.solar_queue_music_linear,
        contentType = "new_release_ep_grid_item",
    ),
}

@Immutable
private data class NewReleaseSection(
    val tab: NewReleaseTab,
    val releases: List<AlbumItem>,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewReleaseGridContent(
    content: NewReleaseContent,
    selectedTab: NewReleaseTab,
    onTabSelected: (NewReleaseTab) -> Unit,
    paddingValues: PaddingValues,
    activeAlbumId: String?,
    isPlaying: Boolean,
    coroutineScope: CoroutineScope,
    searchQuery: String,
    isSelectionMode: Boolean,
    selectedIds: SnapshotStateSet<String>,
    onReleaseClick: (AlbumItem) -> Unit,
    onReleaseLongClick: (AlbumItem) -> Unit,
    onRefresh: () -> Unit,
) {
    val allSections =
        remember(content) {
            content.releaseSections()
        }
    val releases =
        remember(content, selectedTab) {
            if (selectedTab == NewReleaseTab.All) emptyList() else content.releasesFor(selectedTab)
        }

    // Apply search filter to releases — matches album title OR artist name,
    // case-insensitive. Empty query = no filtering.
    val query = searchQuery.trim()
    fun matchesQuery(album: AlbumItem): Boolean {
        if (query.isEmpty()) return true
        val title = album.title.lowercase()
        val artists = album.artists?.joinToString(" ") { it.name }?.lowercase().orEmpty()
        val q = query.lowercase()
        return title.contains(q) || artists.contains(q)
    }

    val filteredReleases = remember(releases, query) { releases.filter(::matchesQuery) }
    val filteredAllSections = remember(allSections, query) {
        if (query.isEmpty()) allSections
        else allSections.map { it.copy(releases = it.releases.filter(::matchesQuery)) }.filter { it.releases.isNotEmpty() }
    }

    // Progressive rendering (2026-09-06, user request: "it should just
    // display the total number and not load everything at once — only load
    // when I scroll"). The full dataset is known (the summary header shows
    // the total count immediately) but only the first `visibleCount` releases
    // are composed into the tab grids; scrolling near the end reveals the
    // next batch. The "All" tab's horizontal sections are already lazy (a
    // LazyHorizontalGrid), so they compose on scroll natively.
    val gridState = rememberLazyGridState()
    var visibleCount by rememberSaveable(selectedTab) { mutableStateOf(NewReleaseVisibleBatchSize) }

    val shouldLoadMore by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisibleIndex = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = info.totalItemsCount
            total > 0 && lastVisibleIndex >= total - NewReleasePrefetchDistance
        }
    }
    LaunchedEffect(shouldLoadMore, filteredReleases.size) {
        if (shouldLoadMore && visibleCount < filteredReleases.size) {
            visibleCount += NewReleaseVisibleBatchSize
        }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = GridThumbnailHeight + 24.dp),
        contentPadding = paddingValues,
        modifier = Modifier.fillMaxSize(),
    ) {
        item(
            key = "new_release_summary",
            span = { GridItemSpan(maxLineSpan) },
            contentType = "new_release_summary",
        ) {
            NewReleaseSummaryHeader(
                content = content,
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
            )
        }

        if (query.isNotEmpty() && filteredAllSections.isEmpty() && filteredReleases.isEmpty()) {
            item(
                key = "new_release_search_empty",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "new_release_empty",
            ) {
                NewReleaseCategoryEmptyState(onRefresh = onRefresh)
            }
        } else if (selectedTab == NewReleaseTab.All) {
            filteredAllSections.forEach { section ->
                item(
                    key = "new_release_section_header_${section.tab.name}",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "new_release_section_header",
                ) {
                    NewReleaseSectionHeader(
                        title = stringResource(section.tab.titleRes),
                        count = section.releases.size,
                        leadingIcon = section.tab.iconRes,
                    )
                }

                item(
                    key = "new_release_section_${section.tab.name}",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "new_release_horizontal_section",
                ) {
                    NewReleaseHorizontalSection(
                        releases = section.releases,
                        contentType = section.tab.contentType,
                        activeAlbumId = activeAlbumId,
                        isPlaying = isPlaying,
                        coroutineScope = coroutineScope,
                        isSelectionMode = isSelectionMode,
                        selectedIds = selectedIds,
                        onReleaseClick = onReleaseClick,
                        onReleaseLongClick = onReleaseLongClick,
                    )
                }
            }
        } else if (filteredReleases.isEmpty()) {
            item(
                key = "new_release_empty_${selectedTab.name}",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "new_release_empty",
            ) {
                NewReleaseCategoryEmptyState(onRefresh = onRefresh)
            }
        } else {
            items(
                items = remember(filteredReleases, visibleCount) { filteredReleases.take(visibleCount) },
                key = { it.id },
                contentType = { selectedTab.contentType },
            ) { album ->
                SelectableReleaseItem(
                    album = album,
                    fillMaxWidth = true,
                    isSelectionMode = isSelectionMode,
                    selectedIds = selectedIds,
                    activeAlbumId = activeAlbumId,
                    isPlaying = isPlaying,
                    coroutineScope = coroutineScope,
                    onReleaseClick = onReleaseClick,
                    onReleaseLongClick = onReleaseLongClick,
                    // animateItem is a LazyGridItemScope extension — it must
                    // be invoked inside the items {} lambda, so it is applied
                    // to the passed-in modifier instead of inside the composable.
                    itemModifier = Modifier.animateItem(),
                )
            }
        }
    }
}

/** Number of releases composed per progressive-rendering batch. */
private const val NewReleaseVisibleBatchSize = 24

/** Grid items remaining before the next batch is revealed. */
private const val NewReleasePrefetchDistance = 8

/**
 * A release grid item with selection affordances. Outside selection mode this
 * is exactly the previous [YouTubeGridItem] with its click/long-click
 * behavior. Inside selection mode a translucent scrim, a selection border and
 * a circular check badge are layered on top, and taps toggle selection (via
 * [onReleaseClick], which routes to the selection handler in selection mode).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectableReleaseItem(
    album: AlbumItem,
    fillMaxWidth: Boolean,
    isSelectionMode: Boolean,
    selectedIds: SnapshotStateSet<String>,
    activeAlbumId: String?,
    isPlaying: Boolean,
    coroutineScope: CoroutineScope,
    onReleaseClick: (AlbumItem) -> Unit,
    onReleaseLongClick: (AlbumItem) -> Unit,
    itemModifier: Modifier = Modifier,
) {
    val selected = album.id in selectedIds
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier =
            itemModifier
                .let { if (fillMaxWidth) it.fillMaxWidth() else it }
                .let {
                    if (isSelectionMode) {
                        it.border(
                            width = if (selected) 2.dp else 1.dp,
                            color =
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                },
                            shape = shape,
                        )
                    } else {
                        it
                    }
                },
    ) {
        YouTubeGridItem(
            item = album,
            isActive = activeAlbumId == album.id,
            isPlaying = isPlaying,
            fillMaxWidth = fillMaxWidth,
            coroutineScope = coroutineScope,
            modifier =
                Modifier.combinedClickable(
                    onClick = { onReleaseClick(album) },
                    onLongClick = { onReleaseLongClick(album) },
                ),
        )
        if (isSelectionMode) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .clip(shape)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            } else {
                                MaterialTheme.colorScheme.scrim.copy(alpha = 0.10f)
                            },
                        ),
            )
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)
                            },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.solar_check_circle_linear),
                    contentDescription = null,
                    tint =
                        if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun NewReleaseSectionHeader(
    title: String,
    count: Int,
    @DrawableRes leadingIcon: Int? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Leading icon in a circular container — matches the Home page's
            // HomeSectionLeadingIcon pattern (e.g. clock for Recently Played,
            // bolt for Speed Dial) so every section header across the app has
            // a recognisable affordance before its title.
            if (leadingIcon != null) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(leadingIcon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            // Compact count chip — small rounded background with the count.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewReleaseHorizontalSection(
    releases: List<AlbumItem>,
    contentType: String,
    activeAlbumId: String?,
    isPlaying: Boolean,
    coroutineScope: CoroutineScope,
    isSelectionMode: Boolean,
    selectedIds: SnapshotStateSet<String>,
    onReleaseClick: (AlbumItem) -> Unit,
    onReleaseLongClick: (AlbumItem) -> Unit,
) {
    LazyHorizontalGrid(
        rows = GridCells.Fixed(1),
        contentPadding = PaddingValues(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .height(216.dp),
    ) {
        items(
            items = releases,
            key = { it.id },
            contentType = { contentType },
        ) { album ->
            SelectableReleaseItem(
                album = album,
                fillMaxWidth = false,
                isSelectionMode = isSelectionMode,
                selectedIds = selectedIds,
                activeAlbumId = activeAlbumId,
                isPlaying = isPlaying,
                coroutineScope = coroutineScope,
                onReleaseClick = onReleaseClick,
                onReleaseLongClick = onReleaseLongClick,
                itemModifier = Modifier.animateItem(),
            )
        }
    }
}

/**
 * Modern summary header — replaces the old frosted-glass summary card.
 *
 * Layout:
 *  - Top row: "Total releases" label + count number grouped together on the
 *    left (so the number sits beside the label, not floating at the right
 *    edge — user-requested fix), with a search affordance icon on the right
 *  - Bottom: tab strip as a horizontally-scrollable row of clean tonal chips
 *    (scrollable so 4 tabs never truncate "Albums" → "Albu" on narrow screens)
 *
 * No frosted glass, no oversized rounded container — just typography +
 * a clean tab strip.
 */
@Composable
private fun NewReleaseSummaryHeader(
    content: NewReleaseContent,
    selectedTab: NewReleaseTab,
    onTabSelected: (NewReleaseTab) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 8.dp),
    ) {
        // Total releases — label and count grouped together on the LEFT so
        // the count number reads as part of the label (e.g. "Total releases 200")
        // rather than floating alone at the right edge of the screen. The
        // search affordance icon is rendered by the top app bar instead.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Small leading icon — matches the section header pattern so the
            // summary header has the same visual language as the per-section
            // headers below it.
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.solar_library_linear),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = stringResource(R.string.total_releases),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Count number — bold and prominent, immediately after the label.
            Text(
                text = content.totalReleases.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(Modifier.height(16.dp))

        // Modern tab strip — clean chips with no frosted pill background.
        // Horizontally scrollable so all 4 tab labels ("All", "Albums",
        // "Singles", "EP") are fully visible regardless of screen width.
        NewReleaseTabs(
            selectedTab = selectedTab,
            onTabSelected = onTabSelected,
        )
    }
}

@Composable
private fun NewReleaseTabs(
    selectedTab: NewReleaseTab,
    onTabSelected: (NewReleaseTab) -> Unit,
) {
    val tabs = remember { NewReleaseTab.entries.toList() }
    val selectedContainer = MaterialTheme.colorScheme.primary
    val selectedContentColor = MaterialTheme.colorScheme.onPrimary
    val unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val scrollState = rememberScrollState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
    ) {
        tabs.forEach { tab ->
            val selected = tab == selectedTab
            val title = stringResource(tab.titleRes)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .wrapContentWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) selectedContainer else Color.Transparent)
                    .combinedClickable(onClick = { onTabSelected(tab) })
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = painterResource(tab.iconRes),
                    contentDescription = title,
                    modifier = Modifier.size(18.dp),
                    tint = if (selected) selectedContentColor else unselectedContentColor,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (selected) selectedContentColor else unselectedContentColor,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun NewReleaseCategoryEmptyState(onRefresh: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 56.dp),
    ) {
        Text(
            text = stringResource(R.string.no_releases_found),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(
            onClick = onRefresh,
            shape = RoundedCornerShape(20.dp),
        ) {
            Text(stringResource(R.string.refresh))
        }
    }
}

private fun NewReleaseContent.releasesFor(tab: NewReleaseTab): List<AlbumItem> =
    when (tab) {
        NewReleaseTab.All -> emptyList()
        NewReleaseTab.Albums -> albums
        NewReleaseTab.Singles -> singles
        NewReleaseTab.Ep -> eps
    }

private fun NewReleaseContent.releaseSections(): List<NewReleaseSection> =
    buildList {
        if (albums.isNotEmpty()) {
            add(NewReleaseSection(NewReleaseTab.Albums, albums))
        }
        if (singles.isNotEmpty()) {
            add(NewReleaseSection(NewReleaseTab.Singles, singles))
        }
        if (eps.isNotEmpty()) {
            add(NewReleaseSection(NewReleaseTab.Ep, eps))
        }
    }
