/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)

package moe.rukamori.archivetune.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Build
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalMiniPlayerVisible
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AppBarHeight
import moe.rukamori.archivetune.constants.HistorySource
import moe.rukamori.archivetune.constants.InnerTubeCookieKey
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.db.entities.EventWithSong
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.pages.HistoryPage
import moe.rukamori.archivetune.innertube.utils.hasYouTubeLoginCookie
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.ui.component.AppleMusicPlaylistHero
import moe.rukamori.archivetune.ui.component.AppleMusicStyleAccentColor
import moe.rukamori.archivetune.ui.component.BottomFadeOverlay
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.FrostedHeaderPill
import moe.rukamori.archivetune.ui.component.HideOnScrollFAB
import moe.rukamori.archivetune.ui.component.LibraryHomeDockButton
import moe.rukamori.archivetune.ui.component.LiquidGlassActionPill
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.PlatformBackdrop
import moe.rukamori.archivetune.ui.component.SongListItem
import moe.rukamori.archivetune.ui.component.TopSearch
import moe.rukamori.archivetune.ui.component.YouTubeListItem
import moe.rukamori.archivetune.ui.component.layerBackdrop
import moe.rukamori.archivetune.ui.component.rememberBackdrop
import moe.rukamori.archivetune.ui.menu.SelectionMediaMetadataMenu
import moe.rukamori.archivetune.ui.menu.SongMenu
import moe.rukamori.archivetune.ui.menu.YouTubeSongMenu
import moe.rukamori.archivetune.ui.player.LocalPlayerLyricsFullScreen
import moe.rukamori.archivetune.ui.utils.appBarScrollBehavior
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.DateAgo
import moe.rukamori.archivetune.viewmodels.HistoryViewModel
import moe.rukamori.archivetune.viewmodels.RemoteHistoryUiState
import java.time.format.DateTimeFormatter
import moe.rukamori.archivetune.ui.component.IconButton as AppIconButton

@Composable
fun HistoryScreen(
    navController: NavController,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val animationsDisabled = LocalAnimationsDisabled.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()

    val historySource by viewModel.historySource.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val isLoadingMoreEvents by viewModel.isLoadingMoreEvents.collectAsStateWithLifecycle()
    val canLoadMoreEvents by viewModel.canLoadMoreEvents.collectAsStateWithLifecycle()
    val remoteHistoryState by viewModel.remoteHistoryState.collectAsStateWithLifecycle()

    val innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    val isLoggedIn =
        remember(innerTubeCookie) {
            hasYouTubeLoginCookie(innerTubeCookie)
        }

    var isSearching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    var selectedEventIds by rememberSaveable { mutableStateOf(emptyList<Long>()) }

    val focusRequester = remember { FocusRequester() }
    val localListState = rememberLazyListState()
    val remoteListState = rememberLazyListState()
    // Dedicated LazyListState instances for the search-mode list. Previously
    // the search-mode LazyColumn shared `localListState` / `remoteListState`
    // with the non-search LazyColumn, and during the AnimatedVisibility exit
    // window BOTH LazyColumns were composed simultaneously — fighting over
    // the same LazyListState and causing the list to disappear or stick on
    // the skeleton loader when the back button was pressed.
    val localSearchListState = rememberLazyListState()
    val remoteSearchListState = rememberLazyListState()
    val scrollBehavior =
        appBarScrollBehavior(
            canScroll = { !isSearching && selectedEventIds.isEmpty() },
        )

    val searchQuery = query.text.trim()
    val showSearchBar = isSearching || searchQuery.isNotBlank()
    val selectedEventIdSet by remember(selectedEventIds) {
        derivedStateOf { selectedEventIds.toSet() }
    }

    val filteredEvents =
        remember(events, searchQuery) {
            filterLocalEvents(events, searchQuery)
        }
    val localVisibleEvents =
        remember(filteredEvents) {
            filteredEvents.values.flatten()
        }
    val localVisibleEventIds =
        remember(localVisibleEvents) {
            localVisibleEvents.map { it.event.id }
        }
    val localVisibleEventIdSet by remember(localVisibleEventIds) {
        derivedStateOf { localVisibleEventIds.toSet() }
    }
    val selectedSongs =
        remember(localVisibleEvents, selectedEventIdSet) {
            localVisibleEvents.filter { it.event.id in selectedEventIdSet }
        }
    val selectedHistoryEventIds =
        remember(selectedSongs) {
            selectedSongs.map { it.event.id }
        }
    val selectionCount = selectedSongs.size

    val filteredRemoteSections =
        remember(remoteHistoryState, searchQuery) {
            when (remoteHistoryState) {
                is RemoteHistoryUiState.Success -> {
                    filterRemoteSections(
                        (remoteHistoryState as RemoteHistoryUiState.Success).page.sections.orEmpty(),
                        searchQuery,
                    )
                }

                else -> {
                    emptyList()
                }
            }
        }
    val remoteVisibleSongs =
        remember(filteredRemoteSections) {
            filteredRemoteSections.flatMap { it.songs }
        }
    val availableSources =
        remember(isLoggedIn) {
            if (isLoggedIn) {
                listOf(HistorySource.LOCAL, HistorySource.REMOTE)
            } else {
                listOf(HistorySource.LOCAL)
            }
        }
    val activeListState = if (historySource == HistorySource.REMOTE) remoteListState else localListState
    val motionDuration = if (animationsDisabled) 0 else 220

    val clearSelection =
        remember {
            { selectedEventIds = emptyList() }
        }
    val resetSearch =
        remember(focusManager) {
            {
                isSearching = false
                query = TextFieldValue()
                focusManager.clearFocus()
            }
        }

    val dateAgoToString: (DateAgo) -> String =
        remember(context) {
            { dateAgo ->
                when (dateAgo) {
                    DateAgo.Today -> context.getString(R.string.today)
                    DateAgo.Yesterday -> context.getString(R.string.yesterday)
                    DateAgo.ThisWeek -> context.getString(R.string.this_week)
                    DateAgo.LastWeek -> context.getString(R.string.last_week)
                    is DateAgo.Other -> dateAgo.date.format(DateTimeFormatter.ofPattern("yyyy/MM"))
                }
            }
        }

    val currentVisibleCount =
        if (historySource == HistorySource.REMOTE) {
            remoteVisibleSongs.size
        } else {
            localVisibleEvents.size
        }

    var showClearHistoryDialog by remember { mutableStateOf(false) }

    // ── Liquid Glass header setup ──────────────────────────────────────────
    // The History page's FrostedHeaderPill components (back + "Library" pill
    // at top-start, search pill at top-end) currently scroll away with the
    // LargeFlexibleTopAppBar. The user reports "There's no liquid glass in
    // history page headers. add it and it should also be constant like other
    // pages." — meaning the pills should (a) actually render with real
    // liquid glass (vibrancy + blur + lens) and (b) stay pinned at the top
    // while the user scrolls (matching LocalPlaylistScreen /
    // AutoPlaylistScreen / CachePlaylistScreen / LocalSongScreen).
    //
    // Pattern: create a screen-scoped `backdrop` and apply
    // `Modifier.layerBackdrop(backdrop)` to the LazyColumn. Then add
    // persistent LiquidGlassActionPill siblings of the LazyColumn (inside
    // the content Box, NOT inside the recorded LazyColumn — that would
    // crash the RuntimeShader). Hide the LargeFlexibleTopAppBar when
    // liquid glass is active so the persistent pills take over.
    val liquidGlassEnabled by rememberPreference(LiquidGlassEnabledKey, defaultValue = false)
    val lyricsFullScreen = LocalPlayerLyricsFullScreen.current
    val liquidGlassHeaderActive =
        liquidGlassEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !lyricsFullScreen
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current
    val surfaceColor = MaterialTheme.colorScheme.surface
    val backdrop = rememberBackdrop(surfaceColor)
    // The persistent liquid glass pills only render when:
    //  - Liquid Glass master toggle is on (liquidGlassHeaderActive)
    //  - Not in search mode (the TopSearch overlay has its own back button)
    //  - Not in selection mode (the LargeFlexibleTopAppBar shows the count
    //    + clear/close in selection mode)
    val showPersistentLiquidGlassHeader =
        liquidGlassHeaderActive && !showSearchBar && selectionCount == 0

    if (showClearHistoryDialog) {
        DefaultDialog(
            onDismiss = { showClearHistoryDialog = false },
            title = { Text(text = stringResource(R.string.history)) },
            content = {
                Text(
                    text = stringResource(R.string.remove_from_history_confirm),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            },
            buttons = {
                androidx.compose.material3.TextButton(
                    onClick = { showClearHistoryDialog = false },
                    shapes = androidx.compose.material3.ButtonDefaults.shapes(),
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }
                androidx.compose.material3.TextButton(
                    onClick = {
                        showClearHistoryDialog = false
                        val allEventIds = localVisibleEvents.map { it.event.id }
                        viewModel.removeEventsFromHistory(allEventIds)
                    },
                    shapes = androidx.compose.material3.ButtonDefaults.shapes(),
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
        )
    }

    val historySourceDock: @Composable () -> Unit = {
        // iOS-inspired hero with small pink accent label, large bold title,
        // metadata line, and rounded Play/Shuffle/Clear pill controls. The
        // existing local/remote source selector is preserved below the hero
        // actions so the user can still switch between local and YouTube
        // remote history — only the visual treatment changed, not the logic.
        Column(modifier = Modifier.fillMaxWidth()) {
            AppleMusicPlaylistHero(
                sectionLabel = stringResource(R.string.recently_played),
                title = stringResource(R.string.history),
                subtitle = pluralStringResource(R.plurals.n_song, currentVisibleCount, currentVisibleCount),
                onPlay = {
                    if (historySource == HistorySource.REMOTE) {
                        if (remoteVisibleSongs.isNotEmpty()) {
                            playerConnection.playQueue(
                                ListQueue(
                                    title = context.getString(R.string.history),
                                    items = remoteVisibleSongs.map { it.toMediaItem() },
                                ),
                            )
                        }
                    } else if (localVisibleEvents.isNotEmpty()) {
                        playerConnection.playQueue(
                            ListQueue(
                                title = context.getString(R.string.history),
                                items = localVisibleEvents.map { it.song.toMediaItem() },
                            ),
                        )
                    }
                },
                onShuffle = {
                    if (historySource == HistorySource.REMOTE) {
                        if (remoteVisibleSongs.isNotEmpty()) {
                            playerConnection.playQueue(
                                ListQueue(
                                    title = context.getString(R.string.history),
                                    items = remoteVisibleSongs.map { it.toMediaItem() }.shuffled(),
                                ),
                            )
                        }
                    } else if (localVisibleEvents.isNotEmpty()) {
                        playerConnection.playQueue(
                            ListQueue(
                                title = context.getString(R.string.history),
                                items = localVisibleEvents.map { it.song.toMediaItem() }.shuffled(),
                            ),
                        )
                    }
                },
                onPrimaryTrailing =
                    if (historySource == HistorySource.LOCAL && localVisibleEvents.isNotEmpty()) {
                        { showClearHistoryDialog = true }
                    } else {
                        null
                    },
                primaryTrailingIcon = R.drawable.close,
                primaryTrailingDescription = R.string.clear,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        // When the persistent Liquid Glass header pills are
                        // shown, the LargeFlexibleTopAppBar is hidden and the
                        // LazyColumn starts at the very top of the screen
                        // (under the status bar). Push the hero down by
                        // `systemBarsTopPadding + AppBarHeight + 8.dp` so it
                        // sits below the persistent pills (matching the
                        // LocalPlaylistScreen pattern). When liquid glass is
                        // off, the LargeFlexibleTopAppBar reserves the top
                        // space and the hero's existing 8.dp top padding is
                        // enough.
                        .padding(
                            top = if (showPersistentLiquidGlassHeader) {
                                systemBarsTopPadding + AppBarHeight + 8.dp
                            } else {
                                8.dp
                            },
                        ),
            )
            if (availableSources.size > 1) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            // Per user request (2026-08-28 follow-up):
                            // "Shift it to the left and align it with the
                            // red play pill". The AppleMusicPlaylistHero
                            // above uses `padding(start = 20.dp, ...)` for
                            // its inner content (so the play pill's left
                            // edge sits at 20dp from the page edge). Using
                            // the same 20dp start padding here places the
                            // HistorySourcePill's left edge at the same
                            // 20dp inset — visually aligned with the play
                            // pill above it.
                            .padding(top = 12.dp, start = 20.dp, end = 20.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HistorySourcePill(
                        currentSource = historySource,
                        availableSources = availableSources,
                        onSourceChange = { newSource ->
                            if (newSource == historySource) return@HistorySourcePill

                            viewModel.historySource.value = newSource
                            if (newSource == HistorySource.REMOTE) {
                                when (remoteHistoryState) {
                                    is RemoteHistoryUiState.Error -> {
                                        viewModel.fetchRemoteHistory()
                                    }

                                    is RemoteHistoryUiState.Empty -> {
                                        viewModel.enqueueSilentFetch()
                                    }

                                    else -> {
                                        Unit
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    val historyContent: @Composable (Dp, Boolean) -> Unit = { topPadding, searchMode ->
        // Pick the LazyListState appropriate for this composition. Search-mode
        // uses its own dedicated state instances so the two LazyColumns that
        // briefly co-exist during the AnimatedVisibility exit transition
        // don't fight over the same state.
        val activeLocalState = if (searchMode) localSearchListState else localListState
        val activeRemoteState = if (searchMode) remoteSearchListState else remoteListState
        Crossfade(
            targetState = historySource,
            animationSpec = tween(durationMillis = motionDuration),
            label = "HistorySourceContent",
        ) { source ->
            when (source) {
                HistorySource.REMOTE -> {
                    RemoteHistoryFeed(
                        listState = activeRemoteState,
                        topPadding = topPadding,
                        backdrop = backdrop.takeIf { liquidGlassHeaderActive },
                        headerContent = historySourceDock,
                        remoteHistoryState = remoteHistoryState,
                        filteredSections = filteredRemoteSections,
                        isPlaying = isPlaying,
                        activeMediaId = mediaMetadata?.id,
                        navController = navController,
                        onRetry = viewModel::fetchRemoteHistory,
                        onSongMenu = { song ->
                            menuState.show {
                                YouTubeSongMenu(
                                    song = song,
                                    navController = navController,
                                    onDismiss = menuState::dismiss,
                                )
                            }
                        },
                        onSongClick = { song ->
                            if (song.id == mediaMetadata?.id) {
                                playerConnection.player.togglePlayPause()
                            } else {
                                playerConnection.playQueue(
                                    YouTubeQueue.radio(song.toMediaMetadata()),
                                )
                            }
                        },
                    )
                }

                HistorySource.LOCAL -> {
                    LocalHistoryFeed(
                        listState = activeLocalState,
                        topPadding = topPadding,
                        backdrop = backdrop.takeIf { liquidGlassHeaderActive },
                        headerContent = historySourceDock,
                        filteredEvents = filteredEvents,
                        visibleEvents = localVisibleEvents,
                        isLoadingMore = isLoadingMoreEvents,
                        canLoadMore = canLoadMoreEvents,
                        isSearchActive = searchQuery.isNotBlank(),
                        selectedEventIds = selectedEventIdSet,
                        isPlaying = isPlaying,
                        activeMediaId = mediaMetadata?.id,
                        dateAgoToString = dateAgoToString,
                        navController = navController,
                        onToggleSelection = { eventId ->
                            selectedEventIds =
                                if (eventId in selectedEventIdSet) {
                                    selectedEventIds - eventId
                                } else {
                                    selectedEventIds + eventId
                                }
                        },
                        onStartSelection = { eventId ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (eventId !in selectedEventIdSet) {
                                selectedEventIds = selectedEventIds + eventId
                            }
                        },
                        onSongMenu = { event ->
                            menuState.show {
                                SongMenu(
                                    originalSong = event.song,
                                    event = event.event,
                                    navController = navController,
                                    onDismiss = menuState::dismiss,
                                )
                            }
                        },
                        onSongClick = { dateAgo, songsForDate, index, event ->
                            if (event.song.id == mediaMetadata?.id) {
                                playerConnection.player.togglePlayPause()
                            } else {
                                playerConnection.playQueue(
                                    ListQueue(
                                        title = dateAgoToString(dateAgo),
                                        items = songsForDate.map { it.song.toMediaItem() },
                                        startIndex = index,
                                    ),
                                )
                            }
                        },
                        onLoadMore = viewModel::loadMoreEvents,
                    )
                }
            }
        }
    }

    LaunchedEffect(isSearching) {
        if (isSearching) {
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(historySource, isLoggedIn) {
        if (!isLoggedIn && historySource == HistorySource.REMOTE) {
            viewModel.historySource.value = HistorySource.LOCAL
        }
        if (historySource != HistorySource.LOCAL && selectedEventIds.isNotEmpty()) {
            selectedEventIds = emptyList()
        }
    }

    LaunchedEffect(localVisibleEventIds) {
        if (selectedEventIds.any { it !in localVisibleEventIdSet }) {
            selectedEventIds = selectedEventIds.filter(localVisibleEventIdSet::contains)
        }
    }

    // A. When screen opens + user is logged in → fetch remote history in background
    LaunchedEffect("prefetch", isLoggedIn) {
        if (!isLoggedIn) return@LaunchedEffect
        if (remoteHistoryState is RemoteHistoryUiState.Success) return@LaunchedEffect
        delay(1_000) // wait for screen

        viewModel.fetchRemoteHistorySilent()
    }

    // B. When playback sync happens → retry with backoff
    LaunchedEffect("sync", isLoggedIn) {
        YouTube.historySyncEvent.collect {
            if (!isLoggedIn) return@collect

            // Retry 3 times with increasing delay (handles slow internet)
            repeat(3) { attempt ->
                delay(3000L * (attempt + 1)) // 3s, 6s, 9s
                viewModel.fetchRemoteHistorySilent()
                if (remoteHistoryState is RemoteHistoryUiState.Success) return@collect
            }
        }
    }

    BackHandler(enabled = showSearchBar) {
        resetSearch()
    }

    BackHandler(enabled = selectionCount > 0 && !showSearchBar) {
        clearSelection()
    }

    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // ── Top bar conditional rendering ───────────────────────────────
            // When the persistent Liquid Glass header is shown
            // (showPersistentLiquidGlassHeader), the LargeFlexibleTopAppBar is
            // skipped entirely — the persistent LiquidGlassActionPill siblings
            // of the LazyColumn (rendered below in the content Box) take over
            // the back + "Library" + search affordances, and they stay pinned
            // at the top while the user scrolls (matching the iOS Music
            // reference and the other redesigned pages).
            //
            // In all other cases (Liquid Glass off, search mode, selection
            // mode, full-screen lyrics), the LargeFlexibleTopAppBar is
            // rendered as before with the FrostedHeaderPill (surface
            // fallback) components inside.
            if (!showSearchBar && !showPersistentLiquidGlassHeader) {
                LargeFlexibleTopAppBar(
                    title = {
                        // Only show a title pill here when the user is in
                        // multi-selection mode (the count makes sense as a
                        // header pill in that context). Otherwise the title
                        // pill is omitted entirely so the hero below
                        // (AppleMusicPlaylistHero) is the ONLY place "History"
                        // appears as a large title — matching the iOS Music
                        // reference and avoiding the duplicate "History"
                        // header the previous implementation rendered (one in
                        // the top app bar pill, one in the hero).
                        if (selectionCount > 0) {
                            FrostedHeaderPill {
                                Text(
                                    text = pluralStringResource(R.plurals.n_song, selectionCount, selectionCount),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        // iOS-inspired back pill: translucent frosted capsule
                        // containing a left-pointing chevron followed by the
                        // text "Library", matching the user's reference
                        // screenshot. Tapping it pops back to the previous
                        // destination (or clears the multi-selection);
                        // long-pressing it jumps straight to the Home tab.
                        FrostedHeaderPill {
                            AppIconButton(
                                onClick = {
                                    if (selectionCount > 0) {
                                        clearSelection()
                                    } else {
                                        navController.navigateUp()
                                    }
                                },
                                onLongClick = {
                                    if (selectionCount == 0) {
                                        navController.backToMain()
                                    }
                                },
                            ) {
                                Icon(
                                    painter =
                                        painterResource(
                                            if (selectionCount > 0) R.drawable.close else R.drawable.arrow_back,
                                        ),
                                    contentDescription = null,
                                )
                            }
                            Text(
                                text = stringResource(R.string.library),
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    },
                    actions = {
                        if (selectionCount == 0) {
                            FrostedHeaderPill(modifier = Modifier.padding(end = 8.dp)) {
                                AppIconButton(
                                    onClick = { isSearching = true },
                                    onLongClick = {},
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.search),
                                        contentDescription = null,
                                    )
                                }
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    colors =
                        TopAppBarDefaults.largeTopAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = Color.Transparent,
                        ),
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (!showSearchBar) {
                // When the persistent Liquid Glass header pills are shown,
                // the LargeFlexibleTopAppBar is hidden so innerPadding's
                // top is 0 — the LazyColumn starts at the very top of the
                // screen (under the persistent pills). The hero item inside
                // the LazyColumn carries its own top padding (see
                // historySourceDock above) to sit below the pills.
                //
                // When Liquid Glass is off (or in selection mode), the
                // LargeFlexibleTopAppBar reserves the top space and the
                // LazyColumn's top padding equals the topBar's height — same
                // as before.
                val topPaddingForContent =
                    if (showPersistentLiquidGlassHeader) 0.dp
                    else innerPadding.calculateTopPadding()
                historyContent(topPaddingForContent, false)
            }

            // ── Persistent Liquid Glass header pills ───────────────────────
            // Siblings of the LazyColumn (inside this content Box). They
            // sample the `backdrop` (recorded by `Modifier.layerBackdrop`
            // applied to the LazyColumn inside LocalHistoryFeed /
            // RemoteHistoryFeed) to render real liquid glass — vibrancy +
            // blur + lens. PERSISTENT — they stay pinned at the top while
            // the user scrolls, matching the iOS Music reference and the
            // LocalPlaylistScreen / AutoPlaylistScreen / CachePlaylistScreen
            // / LocalSongScreen pattern.
            //
            // Visible only when the Liquid Glass master toggle is on AND
            // not searching AND not in selection mode. In those other cases
            // the LargeFlexibleTopAppBar (with FrostedHeaderPill fallbacks)
            // handles the back + search affordances.
            if (showPersistentLiquidGlassHeader) {
                // iOS-inspired back pill: persistent translucent liquid-glass
                // capsule containing a left-pointing chevron followed by the
                // text "Library", matching the user's reference screenshot.
                // The pill samples the backdrop to render the liquid-glass
                // blur. Tapping it pops back to the previous destination;
                // long-pressing it jumps straight to the Home tab.
                LiquidGlassActionPill(
                    backdrop = backdrop,
                    interactive = true,
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 12.dp, top = systemBarsTopPadding + 12.dp),
                ) {
                    AppIconButton(
                        onClick = { navController.navigateUp() },
                        onLongClick = { navController.backToMain() },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = stringResource(R.string.library),
                            tint = Color.White,
                        )
                    }
                    Text(
                        text = stringResource(R.string.library),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
                // Search pill at top-end. Same LiquidGlassActionPill styling
                // as the back pill so the two read as a pair. `interactive`
                // is left at its default (false) because callers wrap their
                // own clickable children — the kyant press detector would
                // otherwise compete with the inner IconButton.onClick and
                // can swallow the UP event on some devices. The back pill
                // above opts in to `interactive = true` for the press-based
                // lens animation because it carries both onClick and
                // onLongClick (matching the LocalPlaylistScreen pattern).
                LiquidGlassActionPill(
                    backdrop = backdrop,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 12.dp, top = systemBarsTopPadding + 12.dp),
                ) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppIconButton(
                            onClick = { isSearching = true },
                            onLongClick = {},
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.search),
                                contentDescription = null,
                                tint = Color.White,
                            )
                        }
                    }
                }
            }

            // Bottom fade overlay — a vertical gradient that fades the bottom
            // of the scrolling history list into the page background, matching
            // the iOS Music reference screenshot. Only visible when the user
            // has scrolled past the hero header, so the first frame (hero
            // visible, list not yet scrolling) doesn't show a stray fade band
            // overlapping the hero's Play/Shuffle/Clear pills.
            //
            // When a mini player is visible, the fade anchors THROUGH the mini
            // player's area (instead of cutting off at the top of the mini
            // player) so there's no straight-cut horizontal line at the mini
            // player's top edge. When no mini player is visible, the fade
            // anchors at the home-icon dock pill as before.
            val isListScrolling by remember {
                derivedStateOf {
                    val activeState = if (historySource == HistorySource.REMOTE) remoteListState else localListState
                    activeState.firstVisibleItemIndex > 0 ||
                        activeState.firstVisibleItemScrollOffset > 0
                }
            }
            // Bottom fade overlay + Floating Home dock button were removed
            // per user request (2026-08-28). The scrollable list now ends
            // cleanly at the bottom of the page surface; the floating
            // liquid-glass "Home" dock button at bottom-start is also gone
            // — both were reported as visual clutter on the playlist detail
            // screens.

            AnimatedVisibility(
                visible = showSearchBar,
                enter = fadeIn(tween(durationMillis = motionDuration)),
                exit = fadeOut(tween(durationMillis = motionDuration)),
            ) {
                TopSearch(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = { focusManager.clearFocus() },
                    active = showSearchBar,
                    onActiveChange = { active ->
                        if (active) {
                            isSearching = true
                        } else {
                            resetSearch()
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    // Match the page background so the search overlay doesn't
                    // show a different shade of surface than the underlying
                    // Scaffold. Without this override TopSearch defaults to
                    // surfaceContainerLow + 6.dp tonal elevation, which is
                    // visibly different from the page's `surface`.
                    colors = SearchBarDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    tonalElevation = 0.dp,
                    placeholder = {
                        Text(text = stringResource(R.string.search))
                    },
                    leadingIcon = {
                        AppIconButton(
                            onClick = { resetSearch() },
                            onLongClick = {
                                if (query.text.isBlank()) {
                                    navController.backToMain()
                                }
                            },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.arrow_back),
                                contentDescription = null,
                            )
                        }
                    },
                    trailingIcon = {
                        if (query.text.isNotBlank()) {
                            AppIconButton(
                                onClick = { query = TextFieldValue() },
                                onLongClick = {},
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.close),
                                    contentDescription = null,
                                )
                            }
                        }
                    },
                    focusRequester = focusRequester,
                ) {
                    historyContent(0.dp, true)
                }
            }

            HistorySelectionToolbar(
                visible = selectionCount > 0 && !showSearchBar && historySource == HistorySource.LOCAL,
                allVisibleSelected = localVisibleEvents.isNotEmpty() && selectionCount == localVisibleEvents.size,
                onToggleAll = {
                    selectedEventIds =
                        if (selectionCount == localVisibleEvents.size) {
                            emptyList()
                        } else {
                            localVisibleEvents.map { it.event.id }
                        }
                },
                onMoreClick = {
                    menuState.show {
                        SelectionMediaMetadataMenu(
                            songSelection = selectedSongs.map { it.song.toMediaItem().metadata!! },
                            onDismiss = menuState::dismiss,
                            clearAction = clearSelection,
                            currentItems = emptyList(),
                            onRemoveFromHistory = {
                                viewModel.removeEventsFromHistory(selectedHistoryEventIds)
                            },
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun LocalHistoryFeed(
    listState: LazyListState,
    topPadding: Dp,
    backdrop: PlatformBackdrop?,
    headerContent: @Composable () -> Unit,
    filteredEvents: Map<DateAgo, List<EventWithSong>>,
    visibleEvents: List<EventWithSong>,
    isLoadingMore: Boolean,
    canLoadMore: Boolean,
    isSearchActive: Boolean,
    selectedEventIds: Set<Long>,
    isPlaying: Boolean,
    activeMediaId: String?,
    dateAgoToString: (DateAgo) -> String,
    navController: NavController,
    onToggleSelection: (Long) -> Unit,
    onStartSelection: (Long) -> Unit,
    onSongMenu: (EventWithSong) -> Unit,
    onSongClick: (DateAgo, List<EventWithSong>, Int, EventWithSong) -> Unit,
    onLoadMore: () -> Unit,
) {
    val isSelectionMode = selectedEventIds.isNotEmpty()

    LaunchedEffect(listState, canLoadMore) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            canLoadMore && lastVisibleIndex >= layoutInfo.totalItemsCount - HISTORY_LOAD_MORE_THRESHOLD
        }.collect { shouldLoadMore ->
            if (shouldLoadMore) onLoadMore()
        }
    }

    LazyColumn(
        state = listState,
        modifier =
            Modifier
                .fillMaxSize()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 840.dp)
                .padding(top = topPadding)
                // Liquid Glass backdrop source: when `backdrop` is non-null
                // (Liquid Glass master toggle on, Android 12+, not in
                // full-screen lyrics), record this LazyColumn's content into
                // the backdrop so the persistent LiquidGlassActionPill
                // siblings of this LazyColumn (rendered in HistoryScreen's
                // content Box) can sample it via the kyant `drawBackdrop`
                // effect stack. MUST be applied here (sibling of the
                // persistent pills) — nesting inside the recorded layer
                // crashes the RuntimeShader.
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                    ),
                ),
        contentPadding = PaddingValues(bottom = 112.dp),
    ) {
        item("history_overview") {
            headerContent()
        }

        if (visibleEvents.isEmpty() && isLoadingMore) {
            item("local_history_initial_loading") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ContainedLoadingIndicator()
                }
            }
        } else if (visibleEvents.isEmpty()) {
            item("local_history_empty") {
                HistoryStateCard(
                    title =
                        stringResource(
                            if (isSearchActive) R.string.history_no_results_title else R.string.history_local_empty_title,
                        ),
                    description =
                        stringResource(
                            if (isSearchActive) R.string.history_no_results_desc else R.string.history_local_empty_desc,
                        ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    icon = if (isSearchActive) R.drawable.search else R.drawable.history,
                )
            }
        } else {
            filteredEvents.forEach { (dateAgo, songsForDate) ->
                item(key = "header_$dateAgo", contentType = "history_section_header") {
                    HistorySectionHeader(
                        title = dateAgoToString(dateAgo),
                        songCount = songsForDate.size,
                    )
                }

                itemsIndexed(
                    items = songsForDate,
                    key = { _, event -> event.event.id },
                    contentType = { _, _ -> "local_history_song" },
                ) { index, event ->
                    val isActive = event.song.id == activeMediaId
                    HistorySongGroupItem(
                        index = index,
                        lastIndex = songsForDate.lastIndex,
                        isSelected = event.event.id in selectedEventIds,
                        isActive = isActive,
                        modifier = Modifier.animateItem(),
                    ) { containerColor ->
                        SongListItem(
                            song = event.song,
                            isActive = isActive,
                            isPlaying = isPlaying,
                            showInLibraryIcon = true,
                            isSelected = event.event.id in selectedEventIds,
                            swipeContentBackgroundColor = containerColor,
                            showActiveContainer = false,
                            trailingContent = {
                                androidx.compose.material3.IconButton(
                                    onClick = {
                                        if (!isSelectionMode) {
                                            onSongMenu(event)
                                        }
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.more_vert),
                                        contentDescription = null,
                                    )
                                }
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (isSelectionMode) {
                                                onToggleSelection(event.event.id)
                                            } else {
                                                onSongClick(dateAgo, songsForDate, index, event)
                                            }
                                        },
                                        onLongClick = {
                                            onStartSelection(event.event.id)
                                        },
                                    ),
                        )
                    }
                }
            }

            if (canLoadMore) {
                item("local_history_loading_more") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isLoadingMore) {
                            ContainedLoadingIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteHistoryFeed(
    listState: LazyListState,
    topPadding: Dp,
    backdrop: PlatformBackdrop?,
    headerContent: @Composable () -> Unit,
    remoteHistoryState: RemoteHistoryUiState,
    filteredSections: List<HistoryPage.HistorySection>,
    isPlaying: Boolean,
    activeMediaId: String?,
    navController: NavController,
    onRetry: () -> Unit,
    onSongMenu: (moe.rukamori.archivetune.innertube.models.SongItem) -> Unit,
    onSongClick: (moe.rukamori.archivetune.innertube.models.SongItem) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier =
            Modifier
                .fillMaxSize()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 840.dp)
                .padding(top = topPadding)
                // Liquid Glass backdrop source: see LocalHistoryFeed above
                // for the same comment. The persistent LiquidGlassActionPill
                // siblings of this LazyColumn sample this backdrop.
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                    ),
                ),
        contentPadding = PaddingValues(bottom = 112.dp),
    ) {
        item("history_overview") {
            headerContent()
        }

        when (remoteHistoryState) {
            RemoteHistoryUiState.Loading -> {
                item("remote_history_loading") {
                    HistoryStateCard(
                        title = stringResource(R.string.history_remote_loading),
                        description = stringResource(R.string.history_remote_summary),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        loading = true,
                    )
                }
            }

            RemoteHistoryUiState.Empty -> {
                item("remote_history_empty") {
                    HistoryStateCard(
                        title = stringResource(R.string.history_remote_empty_title),
                        description = stringResource(R.string.history_remote_empty_desc),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        icon = R.drawable.history,
                    )
                }
            }

            RemoteHistoryUiState.Error -> {
                item("remote_history_error") {
                    HistoryStateCard(
                        title = stringResource(R.string.history_remote_error_title),
                        description = stringResource(R.string.history_remote_error_desc),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        actionLabel = stringResource(R.string.retry),
                        onActionClick = onRetry,
                        icon = R.drawable.history,
                    )
                }
            }

            is RemoteHistoryUiState.Success -> {
                if (filteredSections.isEmpty()) {
                    item("remote_history_search_empty") {
                        HistoryStateCard(
                            title = stringResource(R.string.history_no_results_title),
                            description = stringResource(R.string.history_no_results_desc),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            icon = R.drawable.search,
                        )
                    }
                } else {
                    filteredSections.forEach { section ->
                        item(key = "header_${section.title}", contentType = "history_section_header") {
                            HistorySectionHeader(
                                title = section.title,
                                songCount = section.songs.size,
                            )
                        }

                        itemsIndexed(
                            items = section.songs,
                            key = { _, song -> "${section.title}_${song.id}" },
                            contentType = { _, _ -> "remote_history_song" },
                        ) { index, song ->
                            val isActive = song.id == activeMediaId
                            HistorySongGroupItem(
                                index = index,
                                lastIndex = section.songs.lastIndex,
                                isActive = isActive,
                                modifier = Modifier.animateItem(),
                            ) { containerColor ->
                                YouTubeListItem(
                                    item = song,
                                    isActive = isActive,
                                    isPlaying = isPlaying,
                                    swipeContentBackgroundColor = containerColor,
                                    showActiveContainer = false,
                                    trailingContent = {
                                        androidx.compose.material3.IconButton(
                                            onClick = { onSongMenu(song) },
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.more_vert),
                                                contentDescription = null,
                                            )
                                        }
                                    },
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = { onSongClick(song) },
                                                onLongClick = { onSongMenu(song) },
                                            ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistorySourceDock(
    visibleSongCount: Int,
    availableSources: List<HistorySource>,
    currentSource: HistorySource,
    onSourceChange: (HistorySource) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth().padding(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(64.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.history),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text =
                                stringResource(
                                    if (currentSource == HistorySource.LOCAL) {
                                        R.string.local_history
                                    } else {
                                        R.string.remote_history
                                    },
                                ),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text =
                                stringResource(
                                    if (currentSource == HistorySource.LOCAL) {
                                        R.string.history_local_summary
                                    } else {
                                        R.string.history_remote_summary
                                    },
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = pluralStringResource(R.plurals.n_song, visibleSongCount, visibleSongCount),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (availableSources.size > 1) {
                    HistorySourcePill(
                        currentSource = currentSource,
                        availableSources = availableSources,
                        onSourceChange = onSourceChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun HistorySongGroupItem(
    index: Int,
    lastIndex: Int,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    isActive: Boolean = false,
    content: @Composable (containerColor: Color) -> Unit,
) {
    val outerShape = MaterialTheme.shapes.extraLarge
    val innerCorner = remember { CornerSize(4.dp) }
    val shape =
        remember(index, lastIndex, outerShape, innerCorner) {
            when {
                lastIndex == 0 -> {
                    outerShape
                }

                index == 0 -> {
                    outerShape.copy(
                        bottomStart = innerCorner,
                        bottomEnd = innerCorner,
                    )
                }

                index == lastIndex -> {
                    outerShape.copy(
                        topStart = innerCorner,
                        topEnd = innerCorner,
                    )
                }

                else -> {
                    outerShape.copy(
                        topStart = innerCorner,
                        topEnd = innerCorner,
                        bottomStart = innerCorner,
                        bottomEnd = innerCorner,
                    )
                }
            }
        }

    val containerColor =
        when {
            isActive -> MaterialTheme.colorScheme.secondaryContainer
            isSelected -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        shape = shape,
        color = containerColor,
    ) {
        content(containerColor)
    }
}

@Composable
private fun HistorySectionHeader(
    title: String,
    songCount: Int,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(12.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primary,
            ) {}
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = pluralStringResource(R.plurals.n_song, songCount, songCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistorySourcePill(
    currentSource: HistorySource,
    availableSources: List<HistorySource>,
    onSourceChange: (HistorySource) -> Unit,
) {
    // Per user request (2026-08-28): "below the play and shuffle icon in
    // history page remove the remote and history pill and instead there
    // should be a single pill which one click opens a drop-down menu for
    // switching between local and remote history".
    //
    // Previously this rendered a Material3 Expressive `ToggleButton`
    // segmented control (two connected buttons side-by-side, each 52dp
    // tall, taking the full row width). The new design matches the Play
    // and Shuffle pills above it: a single 46dp pill button (mirroring
    // `PillActionButton` from `AppleMusicPlaylistHero`) that, when
    // clicked, opens a DropdownMenu with one item per available source.
    // If only one source is available (e.g. user is not logged in to
    // InnerTube so Remote is hidden), the pill still renders but the
    // dropdown has only one entry — tapping it is a no-op.
    //
    // Per user request (2026-08-28 follow-up): "The local switch pill in
    // history page is still in the middle. Shift it to the left and align
    // it with the red play pill and also change the accent like the
    // play/shuffle button too." The accent is switched from
    // `colorScheme.primary` to `AppleMusicStyleAccentColor` (the same
    // red/pink used by Play and Shuffle in `AppleMusicPlaylistHero`),
    // and the outer Box no longer forces `.fillMaxWidth()` + center
    // alignment — the caller's Row (`Arrangement.Start` + start padding)
    // now naturally anchors the pill at the same left inset as the play
    // pill.
    var expanded by remember { mutableStateOf(false) }
    val accent = AppleMusicStyleAccentColor
    val onBackgroundColor = MaterialTheme.colorScheme.onBackground
    val containerColor = onBackgroundColor.copy(alpha = 0.06f)
    val currentLabel =
        stringResource(
            if (currentSource == HistorySource.LOCAL) {
                R.string.local_history
            } else {
                R.string.remote_history
            },
        )

    Box(
        modifier =
            Modifier
                // Left-anchor the source pill at the same horizontal inset
                // as the Play/Shuffle pill row inside
                // AppleMusicPlaylistHero (`padding(start = 20.dp, ...)`).
                // The parent Row's own `start = 20.dp` padding (set by
                // the caller) places the pill's left edge at exactly
                // 20dp from the screen's left edge — matching the play
                // pill's left edge.
                .wrapContentWidth(align = Alignment.Start),
        contentAlignment = Alignment.TopStart,
    ) {
        Box {
            Surface(
                onClick = { if (availableSources.size > 1) expanded = true },
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .height(46.dp),
                shape = RoundedCornerShape(percent = 50),
                color = containerColor,
            ) {
                // fillMaxHeight() so the icon+label cluster is vertically
                // centered within the 46dp pill (matching the
                // PillActionButton fix in AppleMusicPlaylistHero).
                Row(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.history),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = currentLabel,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (availableSources.size > 1) {
                        Icon(
                            painter = painterResource(R.drawable.expand_more),
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                availableSources.forEach { source ->
                    val label =
                        stringResource(
                            if (source == HistorySource.LOCAL) {
                                R.string.local_history
                            } else {
                                R.string.remote_history
                            },
                        )
                    val isSelected = source == currentSource
                    DropdownMenuItem(
                        text = { Text(label) },
                        trailingIcon = {
                            if (isSelected) {
                                Icon(
                                    painter = painterResource(R.drawable.check),
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            if (!isSelected) onSourceChange(source)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryStateCard(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    loading: Boolean = false,
    icon: Int? = null,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 360.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 40.dp),
        ) {
            if (loading) {
                ContainedLoadingIndicator()
            } else if (icon != null) {
                val stateShape = MaterialShapes.Cookie9Sided.toShape()
                Surface(
                    modifier = Modifier.size(88.dp),
                    shape = stateShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(icon),
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )

            if (actionLabel != null && onActionClick != null) {
                FilledTonalButton(
                    onClick = onActionClick,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = actionLabel)
                }
            }
        }
    }
}

@Composable
private fun BoxScope.HistorySelectionToolbar(
    visible: Boolean,
    allVisibleSelected: Boolean,
    onToggleAll: () -> Unit,
    onMoreClick: () -> Unit,
) {
    val animationsDisabled = LocalAnimationsDisabled.current
    AnimatedVisibility(
        visible = visible,
        enter =
            fadeIn(tween(if (animationsDisabled) 0 else 220)) +
                slideInVertically(animationSpec = tween(if (animationsDisabled) 0 else 220)) { it / 2 },
        exit =
            fadeOut(tween(if (animationsDisabled) 0 else 220)) +
                slideOutVertically(animationSpec = tween(if (animationsDisabled) 0 else 220)) { it / 2 },
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                    ),
                ).padding(16.dp),
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            floatingActionButton = {
                FloatingToolbarDefaults.VibrantFloatingActionButton(
                    onClick = onMoreClick,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.more_vert),
                        contentDescription = stringResource(R.string.more_options),
                    )
                }
            },
            colors =
                FloatingToolbarDefaults.standardFloatingToolbarColors(
                    toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
        ) {
            HistoryToolbarAction(
                icon = if (allVisibleSelected) R.drawable.deselect else R.drawable.select_all,
                label = stringResource(if (allVisibleSelected) R.string.clear_selection else R.string.select),
                onClick = onToggleAll,
            )
        }
    }
}

@Composable
private fun HistoryToolbarAction(
    icon: Int,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier =
            Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun filterLocalEvents(
    events: Map<DateAgo, List<EventWithSong>>,
    query: String,
): Map<DateAgo, List<EventWithSong>> {
    if (query.isBlank()) return events

    return events
        .mapValues { (_, songs) ->
            songs.filter { event ->
                event.song.song.title
                    .contains(query, ignoreCase = true) ||
                    event.song.artists.any { artist ->
                        artist.name.contains(query, ignoreCase = true)
                    }
            }
        }.filterValues { it.isNotEmpty() }
}

private fun filterRemoteSections(
    sections: List<HistoryPage.HistorySection>,
    query: String,
): List<HistoryPage.HistorySection> {
    if (query.isBlank()) return sections

    return sections
        .map { section ->
            section.copy(
                songs =
                    section.songs.filter { song ->
                        song.title.contains(query, ignoreCase = true) ||
                            song.artists.any { artist ->
                                artist.name.contains(query, ignoreCase = true)
                            }
                    },
            )
        }.filter { it.songs.isNotEmpty() }
}

private const val HISTORY_LOAD_MORE_THRESHOLD = 12
