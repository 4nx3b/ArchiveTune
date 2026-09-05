/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.playlist

import android.annotation.SuppressLint
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastSumBy
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.offline.Download
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalDownloadUtil
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalMiniPlayerVisible
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AppBarHeight
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.constants.PlaylistEditLockKey
import moe.rukamori.archivetune.constants.PlaylistSongSortType
import moe.rukamori.archivetune.constants.SwipeToSongKey
import moe.rukamori.archivetune.db.entities.PlaylistSong
import moe.rukamori.archivetune.extensions.move
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.playback.queues.LocalMixQueue
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.ui.component.AssignTagsDialog
import moe.rukamori.archivetune.ui.component.BottomFadeOverlay
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.DraggableScrollbar
import moe.rukamori.archivetune.ui.component.EditPlaylistDialog
import moe.rukamori.archivetune.ui.component.EmptyPlaceholder
import moe.rukamori.archivetune.ui.component.ExpressivePullToRefreshBox
import moe.rukamori.archivetune.ui.component.FrostedHeaderPill
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.AppleMusicPlaylistHero
import moe.rukamori.archivetune.ui.component.LibraryHomeDockButton
import moe.rukamori.archivetune.ui.component.LiquidGlassActionPill
import moe.rukamori.archivetune.ui.component.GlassPillTitleText
import moe.rukamori.archivetune.ui.component.LiquidGlassIconButton
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.MediaDetailAction
import moe.rukamori.archivetune.ui.component.layerBackdrop
import moe.rukamori.archivetune.ui.component.liquidGlassContentColor
import moe.rukamori.archivetune.ui.component.rememberBackdrop
import moe.rukamori.archivetune.ui.component.MediaDetailIconAction
import moe.rukamori.archivetune.ui.component.SongListItem
import moe.rukamori.archivetune.ui.component.SortHeader
import moe.rukamori.archivetune.ui.component.rememberLayerBackdropSettled
import moe.rukamori.archivetune.ui.menu.PlaylistMenu
import moe.rukamori.archivetune.ui.menu.SelectionSongMenu
import moe.rukamori.archivetune.ui.menu.SongMenu
import moe.rukamori.archivetune.ui.menu.removeSongFromRemotePlaylist
import dev.chrisbanes.haze.hazeSource
import moe.rukamori.archivetune.ui.screens.playlist.PlaylistSuggestionsSection
import moe.rukamori.archivetune.ui.screens.ScreenHeaderHaze
import moe.rukamori.archivetune.ui.screens.TELEGRAM_BOTS_ROUTE
import moe.rukamori.archivetune.ui.screens.rememberScreenHeaderHaze
import moe.rukamori.archivetune.ui.utils.HeaderDownloadItem
import moe.rukamori.archivetune.ui.utils.HeaderDownloadProgressIndicator
import moe.rukamori.archivetune.ui.utils.HeaderDownloadState
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.ui.utils.formatCompactCount
import moe.rukamori.archivetune.ui.utils.headerDownloadState
import moe.rukamori.archivetune.ui.utils.sendAddMissingDownloads
import moe.rukamori.archivetune.ui.utils.sendRemoveDownloads
import moe.rukamori.archivetune.ui.utils.sendPauseRunningDownloads
import moe.rukamori.archivetune.ui.utils.sendResumePausedDownloads
import moe.rukamori.archivetune.utils.makeTimeString
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.LocalPlaylistViewModel
import moe.rukamori.archivetune.viewmodels.PlaylistCoverEvent
import moe.rukamori.archivetune.viewmodels.PlaylistCoverState
import moe.rukamori.archivetune.ui.player.LocalMiniPlayerDocked
import moe.rukamori.archivetune.ui.player.LocalPlayerLyricsFullScreen
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.time.LocalDateTime

@SuppressLint("RememberReturnType")
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LocalPlaylistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: LocalPlaylistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val database = LocalDatabase.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()

    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val songs by viewModel.playlistSongs.collectAsStateWithLifecycle()
    val viewCounts by viewModel.viewCounts.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val coverState by viewModel.coverState.collectAsStateWithLifecycle()
    val mutableSongs = remember { mutableStateListOf<PlaylistSong>() }
    val playlistLength =
        remember(songs) {
            songs.fastSumBy { it.song.song.duration }
        }
    val sortType by viewModel.sortType.collectAsStateWithLifecycle()
    val sortDescending by viewModel.sortDescending.collectAsStateWithLifecycle()
    val onSortTypeChange: (PlaylistSongSortType) -> Unit = { viewModel.updateSortPreference(it, sortDescending) }
    val onSortDescendingChange: (Boolean) -> Unit = { viewModel.updateSortPreference(sortType, it) }
    var locked by rememberPreference(PlaylistEditLockKey, defaultValue = true)
    val swipeToSongEnabled by rememberPreference(SwipeToSongKey, defaultValue = true)
    // Liquid Glass master toggle. When off, the Liquid Glass header pills are
    // not shown and the standard TopAppBar is used instead. The kyant
    // RuntimeShader stack requires Android 12+.
    val liquidGlassEnabled by rememberPreference(LiquidGlassEnabledKey, defaultValue = false)
    val liquidGlassHeaderActive =
        liquidGlassEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // Suspend the LiquidGlass header + layerBackdrop while the full-screen
    // lyrics overlay is open on top of this screen. The overlay is opaque,
    // so this screen's pixels are never visible — but without this gate the
    // kyant layerBackdrop keeps recording the entire LazyColumn into a
    // GraphicsLayer every frame, and the LiquidGlass header pills keep
    // sampling that backdrop through a RuntimeShader (vibrancy + blur +
    // lens). That per-frame GPU work starves the 60 Hz karaoke lyrics
    // sweep running on top, causing the "enhanced word-synced lyrics lag
    // when launched from a playlist page" bug. HomeScreen doesn't have
    // LiquidGlass, which is why the same lyrics path doesn't lag from home.
    val lyricsFullScreen = LocalPlayerLyricsFullScreen.current
    // Defer the layerBackdrop activation for ~500ms after first composition so
    // the page transition (NavHost default 250ms slide-in-from-right) doesn't
    // compete with the kyant RuntimeShader recording for the GPU/frame budget.
    // Per user report (2026-08-29): "Whenever I open a page the transition/page
    // switch animation lags a lot. this only happens in the pages that has
    // liquid glass implementation." Keep the FrostedHeaderPill fallback (no
    // backdrop, no per-frame recording) until the screen has settled, then swap
    // to the real LiquidGlassActionPill + layerBackdrop. Liquid glass itself is
    // NOT removed — only delayed.
    val screenSettled = rememberLayerBackdropSettled()

    val layerBackdropActive = liquidGlassHeaderActive && !lyricsFullScreen && screenSettled
    var showAssignTagsDialog by remember { mutableStateOf(false) }

    if (showAssignTagsDialog && playlist != null) {
        AssignTagsDialog(
            playlistId = playlist!!.id,
            onDismiss = { showAssignTagsDialog = false },
        )
    }

    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.coverEvents.collect { event ->
            when (event) {
                is PlaylistCoverEvent.ShowMessage -> {
                    snackbarHostState.showSnackbar(context.getString(event.messageRes))
                }
            }
        }
    }

    // Stable top inset: does not collapse to 0 when the status bar is transiently hidden,
    // so the search bar offset and the playlist header always stay anchored below the TopAppBar.
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    var isSearching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }

    val filteredSongs =
        remember(songs, query) {
            if (query.text.isEmpty()) {
                songs
            } else {
                songs.filter { song ->
                    song.song.song.title
                        .contains(query.text, ignoreCase = true) ||
                        song.song.artists.fastAny { it.name.contains(query.text, ignoreCase = true) }
                }
            }
        }

    val focusRequester = remember { FocusRequester() }

    // Save scroll position when entering search, restore when leaving.
    // The header item collapses to height 0 when isSearching becomes true, which
    // shifts all items below it. By saving firstVisibleItemIndex + scrollOffset
    // BEFORE the collapse and restoring them AFTER the expand, the visible
    // position is preserved across open/close search.
    // (The LaunchedEffect that uses these is declared further below, AFTER
    // lazyListState is created — Kotlin requires vals to be declared before use.)
    var savedScrollIndex by remember { mutableIntStateOf(0) }
    var savedScrollOffset by remember { mutableIntStateOf(0) }
    LaunchedEffect(isSearching) {
        if (isSearching) {
            focusRequester.requestFocus()
        }
    }

    var selection by remember { mutableStateOf(false) }
    var selectedSongMapIds by remember { mutableStateOf(emptySet<Int>()) }
    val visibleSongMapIds =
        remember(filteredSongs) {
            filteredSongs.map { it.map.id }.toSet()
        }
    val selectedPlaylistSongs =
        remember(filteredSongs, selectedSongMapIds) {
            filteredSongs.filter { it.map.id in selectedSongMapIds }
        }

    LaunchedEffect(selection, visibleSongMapIds) {
        if (selection) {
            val visibleSelectedSongMapIds = selectedSongMapIds.intersect(visibleSongMapIds)
            if (visibleSelectedSongMapIds.size != selectedSongMapIds.size) {
                selectedSongMapIds = visibleSelectedSongMapIds
            }
        } else if (selectedSongMapIds.isNotEmpty()) {
            selectedSongMapIds = emptySet()
        }
    }

    if (isSearching) {
        BackHandler {
            isSearching = false
            query = TextFieldValue()
        }
    } else if (selection) {
        BackHandler {
            selection = false
        }
    } else {
        // BackHandler so the predictive back gesture always escapes the
        // playlist page. Per user report (2026-08-29): "I'm in the playlist
        // but I can't get back using the navigation gesture." The previous
        // implementation called `navController.navigate("library") {
        // popUpTo(navController.graph.startDestinationId) ... }` which could
        // fail silently when `navController.graph` was momentarily null
        // during fast back-to-back navigation or when the start destination
        // ID was the same as the target ("library" route when the user's
        // default tab is Library) — the IllegalArgumentException was caught
        // and the catch-branch `popBackStack()` was reached, but if that
        // also encountered a transient state, the gesture was swallowed.
        //
        // New approach: call `popBackStack()` directly first — this is the
        // most primitive NavController operation and reliably pops the
        // current entry to reveal the previous one (almost always Library
        // when the user came from the Library tab). If `popBackStack()`
        // returns false (no previous entry, e.g. deep-link entry), fall
        // back to navigating to the Library route. Wrapped in try/catch as
        // defense-in-depth so the gesture NEVER silently fails — if
        // everything throws, we let the system handle the back press by
        // re-dispatching (the system will then exit the activity).
        BackHandler {
            try {
                if (!navController.popBackStack()) {
                    navController.navigate("library") {
                        launchSingleTop = true
                    }
                }
            } catch (_: Exception) {
                // Last-resort: try navigateUp() which handles graph lookup
                // differently and may succeed where popBackStack failed.
                try {
                    if (!navController.navigateUp()) {
                        navController.navigate("library") { launchSingleTop = true }
                    }
                } catch (_: Exception) {
                    // Truly stuck — let the system handle the back press
                    // (which will exit the activity). This is the worst
                    // case; we don't silently swallow.
                }
            }
        }
    }

    val downloadUtil = LocalDownloadUtil.current
    var downloads by remember { mutableStateOf<Map<String, Download>>(emptyMap()) }
    var downloadState by remember { mutableStateOf<HeaderDownloadState>(HeaderDownloadState.None) }
    val globalDownloadState = remember(downloads) {
        val activeDownloads = downloads.values.filter {
            it.state == Download.STATE_DOWNLOADING ||
            it.state == Download.STATE_QUEUED ||
            it.state == Download.STATE_RESTARTING ||
            it.state == Download.STATE_STOPPED
        }
        if (activeDownloads.isEmpty()) {
            HeaderDownloadState.None
        } else {
            var progressTotal = 0f
            var hasRunning = false
            var hasPaused = false
            activeDownloads.forEach { download ->
                val progress = download.percentDownloaded.takeIf { it >= 0f }?.div(100f) ?: 0f
                progressTotal += progress.coerceIn(0f, 1f)
                if (download.state == Download.STATE_STOPPED) {
                    hasPaused = hasPaused || download.stopReason == 1
                } else {
                    hasRunning = true
                }
            }
            HeaderDownloadState.Partial(
                progress = progressTotal / activeDownloads.size,
                paused = hasPaused && !hasRunning,
            )
        }
    }

    val editable: Boolean = playlist?.playlist?.isEditable == true
    val isReorderingEnabled =
        editable &&
            sortType == PlaylistSongSortType.CUSTOM &&
            !locked &&
            !selection &&
            !isSearching
    val isSwipeToDeleteEnabled =
        editable &&
            !locked &&
            !selection &&
            !swipeToSongEnabled &&
            !isReorderingEnabled

    LaunchedEffect(songs) {
        mutableSongs.apply {
            clear()
            addAll(songs)
        }
        val songIds = songs.map { it.song.id }
        downloadUtil.downloads.collect { currentDownloads ->
            downloads = currentDownloads
            downloadState = headerDownloadState(songIds, currentDownloads)
        }
    }

    val pickCoverLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::updateCover)
        }

    var showEditDialog by remember { mutableStateOf(false) }

    if (showEditDialog) {
        playlist?.let { playlistData ->
            EditPlaylistDialog(
                initialName = playlistData.playlist.name,
                onDismiss = { showEditDialog = false },
                onSave = { name ->
                    database.query {
                        update(
                            playlistData.playlist.copy(
                                name = name,
                                lastUpdateTime = LocalDateTime.now(),
                            ),
                        )
                    }
                    viewModel.viewModelScope.launch(Dispatchers.IO) {
                        playlistData.playlist.browseId?.let { YouTube.renamePlaylist(it, name) }
                    }
                },
            )
        }
    }

    var showRemoveDownloadDialog by remember { mutableStateOf(false) }

    if (showRemoveDownloadDialog) {
        DefaultDialog(
            onDismiss = { showRemoveDownloadDialog = false },
            content = {
                Text(
                    text =
                        stringResource(
                            R.string.remove_download_playlist_confirm,
                            playlist?.playlist!!.name,
                        ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            },
            buttons = {
                TextButton(
                    onClick = { showRemoveDownloadDialog = false },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showRemoveDownloadDialog = false
                        if (!editable) {
                            database.transaction {
                                playlist?.id?.let { clearPlaylist(it) }
                            }
                        }
                        sendRemoveDownloads(
                            context = context,
                            songIds = songs.map { it.song.id },
                        )
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
        )
    }

    var showDeletePlaylistDialog by remember { mutableStateOf(false) }
    if (showDeletePlaylistDialog) {
        DefaultDialog(
            onDismiss = { showDeletePlaylistDialog = false },
            content = {
                Text(
                    text =
                        stringResource(
                            R.string.delete_playlist_confirm,
                            playlist?.playlist!!.name,
                        ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            },
            buttons = {
                TextButton(
                    onClick = { showDeletePlaylistDialog = false },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        showDeletePlaylistDialog = false
                        database.query {
                            playlist?.let { delete(it.playlist) }
                        }
                        viewModel.viewModelScope.launch(Dispatchers.IO) {
                            playlist?.playlist?.browseId?.let { YouTube.deletePlaylist(it) }
                        }
                        navController.popBackStack()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
        )
    }

    val headerItems by remember {
        derivedStateOf {
            val current = playlist
            val hasContent =
                current != null &&
                    (current.songCount > 0 || current.playlist.remoteSongCount != 0)
            if (hasContent && !isSearching) 2 else 0
        }
    }
    val lazyListState = rememberLazyListState()
    var dragInfo by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val reorderableState =
        rememberReorderableLazyListState(
            lazyListState = lazyListState,
            scrollThresholdPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) { from, to ->
            if (to.index >= headerItems && from.index >= headerItems) {
                val currentDragInfo = dragInfo
                dragInfo =
                    if (currentDragInfo == null) {
                        (from.index - headerItems) to (to.index - headerItems)
                    } else {
                        currentDragInfo.first to (to.index - headerItems)
                    }
                mutableSongs.move(from.index - headerItems, to.index - headerItems)
            }
        }

    // Save scroll position when entering search, restore when leaving.
    // The header item collapses to height 0 when isSearching becomes true, which
    // shifts all items below it. By saving firstVisibleItemIndex + scrollOffset
    // BEFORE the collapse and restoring them AFTER the expand, the visible
    // position is preserved across open/close search.
    LaunchedEffect(isSearching) {
        if (isSearching) {
            savedScrollIndex = lazyListState.firstVisibleItemIndex
            savedScrollOffset = lazyListState.firstVisibleItemScrollOffset
        } else {
            // Wait one frame for the header to re-expand before restoring.
            withFrameNanos {}
            lazyListState.scrollToItem(savedScrollIndex, savedScrollOffset)
        }
    }

    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging) {
            dragInfo?.let { (from, to) ->
                val orderedBeforeMove = songs
                val browseId =
                    viewModel.playlist.value
                        ?.playlist
                        ?.browseId
                val movedSetVideoId = orderedBeforeMove.getOrNull(from)?.map?.setVideoId
                val successorIndex = if (from > to) to else to + 1
                val successorSetVideoId = orderedBeforeMove.getOrNull(successorIndex)?.map?.setVideoId

                coroutineScope.launch(Dispatchers.IO) {
                    database.withTransaction {
                        move(viewModel.playlistId, from, to)
                    }

                    if (browseId != null && movedSetVideoId != null) {
                        runCatching {
                            YouTube
                                .moveSongPlaylist(
                                    browseId,
                                    movedSetVideoId,
                                    successorSetVideoId,
                                ).getOrThrow()
                        }.onFailure {
                            withContext(Dispatchers.Main) {
                                snackbarHostState.showSnackbar(
                                    message = context.getString(R.string.error_unknown),
                                    withDismissAction = true,
                                )
                            }
                        }
                    }
                }
                dragInfo = null
            }
        }
    }

    val showTopBarTitle by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0
        }
    }

    // Whether the user has scrolled past the hero header — used to trigger
    // the SimpMusic-style mini player "shrink + dock to right of Home
    // button" behavior. Hoisted here so it can be propagated to the
    // MiniPlayer subtree via CompositionLocalProvider wrapping the
    // ExpressivePullToRefreshBox.
    val isListScrolling by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0 ||
                lazyListState.firstVisibleItemScrollOffset > 0
        }
    }

    val surfaceColor = MaterialTheme.colorScheme.surface

    val transparentAppBar by remember {
        derivedStateOf {
            !selection && !showTopBarTitle && !isSearching
        }
    }

    // Liquid Glass backdrop: created unconditionally (cheap — just a GraphicsLayer
    // handle). The actual content recording only happens when
    // `Modifier.layerBackdrop(artworkBackdrop)` is applied to the LazyColumn below,
    // which is gated on `liquidGlassHeaderActive`.
    //
    // The initial backdrop color is the page surface color (NOT Color.Black).
    // When the persistent liquid glass pills sample the backdrop at a position
    // where the LazyColumn has no content (e.g. the empty band above the hero
    // header item that has top padding = systemBarsTopPadding + AppBarHeight),
    // the backdrop layer is transparent and the initial color shows through.
    // Using the surface color makes those areas blend with the page background
    // instead of showing a hard black band behind the pill.
    val artworkBackdrop = rememberBackdrop(surfaceColor)

    // Wrap the entire screen subtree in a CompositionLocalProvider so the
    // MiniPlayer (rendered by the parent BottomSheetPlayer outside this
    // screen) can read LocalMiniPlayerDocked and shrink/dock when the user
    // scrolls past the hero header.
    CompositionLocalProvider(
        LocalMiniPlayerDocked provides isListScrolling,
    ) {
    // Header haze (2026-09-04, revised): the home page's blurred top haze,
    // ported to this screen. The haze SOURCE is the scrolling LazyColumn, the
    // overlay renders ON TOP of it (a later sibling, beneath the pinned
    // Liquid Glass pills) — the overlay was previously the FIRST child under
    // the list, so the list drew straight over it and the haze was never
    // visible (user report 2026-09-04: "I don't see the haze effect").
    val headerHaze = rememberScreenHeaderHaze()
    ExpressivePullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
        modifier =
            Modifier
                .fillMaxSize()
                .background(surfaceColor),
    ) {
        LazyColumn(
            state = lazyListState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(
                        if (layerBackdropActive) {
                            Modifier.layerBackdrop(artworkBackdrop)
                        } else {
                            Modifier
                        },
                    )
                    .hazeSource(headerHaze),
            contentPadding =
                PaddingValues(
                    // When searching, the header item collapses to zero height and the
                    // TopAppBar (which renders the search field) is overlaid on top of
                    // the LazyColumn. Reserve top space so the first songs aren't hidden
                    // behind the TopAppBar + status bar.
                    top = if (isSearching) systemBarsTopPadding + 64.dp else 0.dp,
                    bottom =
                        LocalPlayerAwareWindowInsets.current
                            .union(WindowInsets.ime)
                            .asPaddingValues()
                            .calculateBottomPadding(),
                ),
        ) {
            playlist?.let { playlist ->
                if (playlist.songCount == 0 && playlist.playlist.remoteSongCount == 0) {
                    item {
                        EmptyPlaceholder(
                            icon = R.drawable.music_note,
                            text = stringResource(R.string.playlist_is_empty),
                        )
                    }
                } else {
                    item(key = "header") {
                        if (!isSearching) {
                            val songCount =
                                if (
                                    playlist.songCount == 0 &&
                                    playlist.playlist.remoteSongCount != null
                                ) {
                                    playlist.playlist.remoteSongCount
                                } else {
                                    playlist.songCount
                                }
                            val metadata =
                                listOfNotNull(
                                    pluralStringResource(
                                        R.plurals.n_song,
                                        songCount,
                                        songCount,
                                    ),
                                    playlistLength
                                        .takeIf { it > 0 }
                                        ?.let { makeTimeString(it * 1000L) },
                                ).joinToString(MediaDetailMetadataSeparator)

                            // SimpMusic-style liquid glass backdrop source: the
                            // LazyColumn itself carries the layerBackdrop modifier
                            // (see the LazyColumn definition above), so the entire
                            // scrolling content is recorded into the backdrop. The
                            // floating Liquid Glass back button (top-start) and
                            // search+more pill (top-end) are siblings of the
                            // LazyColumn (children of the ExpressivePullToRefreshBox),
                            // so they sample the backdrop without being recorded into
                            // it. They are PERSISTENT — they stay at the top of the
                            // screen no matter how far the user scrolls.
                            //
                            // The hero item now renders the iOS-inspired
                            // AppleMusicPlaylistHero: small pink accent label,
                            // large bold white title, metadata line, rounded
                            // Play/Shuffle/Download pill controls. No large
                            // artwork backdrop — matches the user's reference
                            // screenshots (plain dark page with confident title).
                            AppleMusicPlaylistHero(
                                sectionLabel = stringResource(R.string.playlist),
                                title = playlist.playlist.name,
                                subtitle = metadata,
                                onPlay =
                                    if (songs.isEmpty()) {
                                        null
                                    } else {
                                        {
                                            playerConnection.playQueue(
                                                ListQueue(
                                                    title = playlist.playlist.name,
                                                    items = songs.map { it.song.toMediaItem() },
                                                ),
                                            )
                                        }
                                    },
                                onShuffle =
                                    if (songs.isEmpty()) {
                                        null
                                    } else {
                                        {
                                            playerConnection.playQueue(
                                                ListQueue(
                                                    title = playlist.playlist.name,
                                                    items =
                                                        songs
                                                            .shuffled()
                                                            .map { it.song.toMediaItem() },
                                                ),
                                            )
                                        }
                                    },
                                additionalActions =
                                    if (songs.isNotEmpty()) {
                                        {
                                            val isCompleted =
                                                downloadState == HeaderDownloadState.Completed
                                            MediaDetailAction(
                                                contentDescription =
                                                    if (isCompleted) {
                                                        R.string.remove_download
                                                    } else {
                                                        R.string.download
                                                    },
                                                contentColor = Color.White,
                                                onClick = {
                                                    val headerState = downloadState
                                                    when (headerState) {
                                                        HeaderDownloadState.Completed -> {
                                                            showRemoveDownloadDialog = true
                                                        }
                                                        is HeaderDownloadState.Partial -> {
                                                            // Pause/Resume (2026-09-05): the icon used to
                                                            // REMOVE every download of the playlist here —
                                                            // the 90 already-downloaded songs included,
                                                            // which is exactly what was reported. Now it
                                                            // only pauses the pending downloads; resuming
                                                            // picks them back up; nothing is removed.
                                                            if (headerState.paused) {
                                                                sendResumePausedDownloads(
                                                                    context = context,
                                                                    songIds = songs.map { it.song.id },
                                                                    downloads = downloads,
                                                                )
                                                            } else {
                                                                sendPauseRunningDownloads(
                                                                    context = context,
                                                                    songIds = songs.map { it.song.id },
                                                                    downloads = downloads,
                                                                )
                                                            }
                                                        }
                                                        HeaderDownloadState.None -> {
                                                            sendAddMissingDownloads(
                                                                context = context,
                                                                songs =
                                                                    songs.map {
                                                                        HeaderDownloadItem(
                                                                            id = it.song.id,
                                                                            title = it.song.song.title,
                                                                        )
                                                                    },
                                                                downloads = downloads,
                                                            )
                                                        }
                                                    }
                                                },
                                            ) {
                                                when (val state = downloadState) {
                                                    HeaderDownloadState.Completed -> {
                                                        Icon(
                                                            painter = painterResource(R.drawable.offline),
                                                            contentDescription = null,
                                                            modifier = Modifier.size(22.dp),
                                                        )
                                                    }
                                                    is HeaderDownloadState.Partial -> {
                                                        HeaderDownloadProgressIndicator(
                                                            progress = state.progress,
                                                            paused = state.paused,
                                                            icon = R.drawable.download,
                                                        )
                                                    }
                                                    HeaderDownloadState.None -> {
                                                        Icon(
                                                            painter = painterResource(R.drawable.download),
                                                            contentDescription = null,
                                                            modifier = Modifier.size(22.dp),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            top = systemBarsTopPadding + AppBarHeight + 8.dp,
                                        ),
                            )
                        }
                    }

                    // Sort Header
                    item(key = "sort_header") {
                        if (!isSearching) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 16.dp),
                            ) {
                                SortHeader(
                                    sortType = sortType,
                                    sortDescending = sortDescending,
                                    onSortTypeChange = onSortTypeChange,
                                    onSortDescendingChange = onSortDescendingChange,
                                    sortTypeText = { sortType ->
                                        when (sortType) {
                                            PlaylistSongSortType.CUSTOM -> R.string.sort_by_custom
                                            PlaylistSongSortType.CREATE_DATE -> R.string.sort_by_create_date
                                            PlaylistSongSortType.NAME -> R.string.sort_by_name
                                            PlaylistSongSortType.ARTIST -> R.string.sort_by_artist
                                            PlaylistSongSortType.PLAY_TIME -> R.string.sort_by_play_time
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                if (editable && sortType == PlaylistSongSortType.CUSTOM) {
                                    IconButton(
                                        onClick = { locked = !locked },
                                        onLongClick = {},
                                        modifier = Modifier.padding(horizontal = 6.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(if (locked) R.drawable.lock else R.drawable.lock_open),
                                            contentDescription = null,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Songs List
            if (!selection) {
                itemsIndexed(
                    items = if (isSearching) filteredSongs else mutableSongs,
                    key = { _, song -> song.map.id },
                ) { index, song ->
                    ReorderableItem(
                        state = reorderableState,
                        key = song.map.id,
                        modifier =
                            Modifier.graphicsLayer {
                                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                            },
                    ) {
                        val currentItem by rememberUpdatedState(song)

                        fun deleteFromPlaylist() {
                            val map = currentItem.map
                            val browseId = playlist?.playlist?.browseId
                            coroutineScope.launch(Dispatchers.IO) {
                                if (browseId != null) {
                                    val remoteResult = removeSongFromRemotePlaylist(browseId, map)
                                    if (remoteResult.isFailure) {
                                        withContext(Dispatchers.Main) {
                                            snackbarHostState.showSnackbar(
                                                message = context.getString(R.string.error_unknown),
                                                withDismissAction = true,
                                            )
                                        }
                                        return@launch
                                    }
                                }
                                database.withTransaction {
                                    move(map.playlistId, map.position, Int.MAX_VALUE)
                                    delete(map.copy(position = Int.MAX_VALUE))
                                }
                            }
                        }

                        val dismissBoxState =
                            rememberSwipeToDismissBoxState(
                                positionalThreshold = { totalDistance -> totalDistance },
                                confirmValueChange = { targetValue ->
                                    targetValue == SwipeToDismissBoxValue.Settled || !lazyListState.isScrollInProgress
                                },
                            )
                        var processedDismiss by remember { mutableStateOf(false) }
                        LaunchedEffect(dismissBoxState.currentValue) {
                            val dv = dismissBoxState.currentValue
                            if (!processedDismiss && (
                                    dv == SwipeToDismissBoxValue.StartToEnd ||
                                        dv == SwipeToDismissBoxValue.EndToStart
                                )
                            ) {
                                processedDismiss = true
                                deleteFromPlaylist()
                            }
                            if (dv == SwipeToDismissBoxValue.Settled) {
                                processedDismiss = false
                            }
                        }

                        val content: @Composable () -> Unit = {
                            SongListItem(
                                song = song.song,
                                viewCountText =
                                    viewCounts[song.song.id]?.let { count -> formatCompactCount(count.toLong()) },
                                isActive = song.song.id == mediaMetadata?.id,
                                isPlaying = isPlaying,
                                showInLibraryIcon = true,
                                trailingContent = {
                                    IconButton(
                                        onClick = {
                                            menuState.show {
                                                SongMenu(
                                                    originalSong = song.song,
                                                    playlistSong = song,
                                                    playlistBrowseId = playlist?.playlist?.browseId,
                                                    navController = navController,
                                                    onDismiss = menuState::dismiss,
                                                )
                                            }
                                        },
                                        onLongClick = {},
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.more_vert),
                                            contentDescription = null,
                                        )
                                    }

                                    if (isReorderingEnabled) {
                                        IconButton(
                                            onClick = { },
                                            onLongClick = {},
                                            modifier =
                                                Modifier
                                                    .draggableHandle()
                                                    .graphicsLayer { alpha = 0.99f },
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.drag_handle),
                                                contentDescription = null,
                                            )
                                        }
                                    }
                                },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                if (song.song.id == mediaMetadata?.id) {
                                                    playerConnection.player.togglePlayPause()
                                                } else {
                                                    playerConnection.playQueue(
                                                        ListQueue(
                                                            title = playlist!!.playlist.name,
                                                            items = songs.map { it.song.toMediaItem() },
                                                            startIndex = songs.indexOfFirst { it.map.id == song.map.id },
                                                        ),
                                                    )
                                                }
                                            },
                                            onLongClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                if (!selection) {
                                                    selection = true
                                                }
                                                selectedSongMapIds = setOf(song.map.id)
                                            },
                                        ),
                            )
                        }

                        if (isSwipeToDeleteEnabled) {
                            SwipeToDismissBox(
                                state = dismissBoxState,
                                backgroundContent = {},
                            ) {
                                content()
                            }
                        } else {
                            content()
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = filteredSongs,
                    key = { _, song -> song.map.id },
                ) { index, song ->
                    ReorderableItem(
                        state = reorderableState,
                        key = song.map.id,
                        modifier =
                            Modifier.graphicsLayer {
                                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                            },
                    ) {
                        val content: @Composable () -> Unit = {
                            SongListItem(
                                song = song.song,
                                viewCountText =
                                    viewCounts[song.song.id]?.let { count -> formatCompactCount(count.toLong()) },
                                isActive = song.song.id == mediaMetadata?.id,
                                isPlaying = isPlaying,
                                showInLibraryIcon = true,
                                trailingContent = {
                                    IconButton(
                                        onClick = {
                                            menuState.show {
                                                SongMenu(
                                                    originalSong = song.song,
                                                    playlistBrowseId = playlist?.playlist?.browseId,
                                                    navController = navController,
                                                    onDismiss = menuState::dismiss,
                                                )
                                            }
                                        },
                                        onLongClick = {},
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.more_vert),
                                            contentDescription = null,
                                        )
                                    }
                                },
                                isSelected = song.map.id in selectedSongMapIds,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                if (!selection) {
                                                    if (song.song.id == mediaMetadata?.id) {
                                                        playerConnection.player.togglePlayPause()
                                                    } else {
                                                        playerConnection.playQueue(
                                                            ListQueue(
                                                                title = playlist!!.playlist.name,
                                                                items = songs.map { it.song.toMediaItem() },
                                                                startIndex = index,
                                                            ),
                                                        )
                                                    }
                                                } else {
                                                    selectedSongMapIds =
                                                        if (song.map.id in selectedSongMapIds) {
                                                            selectedSongMapIds - song.map.id
                                                        } else {
                                                            selectedSongMapIds + song.map.id
                                                        }
                                                }
                                            },
                                            onLongClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                if (!selection) {
                                                    selection = true
                                                }
                                                selectedSongMapIds = setOf(song.map.id)
                                            },
                                        ),
                            )
                        }

                        content()
                    }
                }
            }

            // Playlist Suggestions Section ("You might like") — hidden for Telegram-channel
            // playlists (LPtg<chatId>) because the suggestions are YouTube-Music queries built
            // from the playlist name + songs, which is meaningless for a Telegram channel whose
            // songs are bot-fetched files (titles/performers often don't match YT Music). The
            // section would either show irrelevant results or fail to load, polluting the
            // playlist screen. Telegram playlists are managed via "Refresh from Telegram" /
            // "Add songs" (in the overflow menu) instead.
            val isTelegramPlaylist = playlist?.playlist?.id?.startsWith("LPtg") == true
            if (!selection && !isSearching && !isTelegramPlaylist) {
                item {
                    PlaylistSuggestionsSection(
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
        }

        DraggableScrollbar(
            modifier =
                Modifier
                    .padding(
                        LocalPlayerAwareWindowInsets.current
                            .union(WindowInsets.ime)
                            .asPaddingValues(),
                    ).align(Alignment.CenterEnd),
            scrollState = lazyListState,
            headerItems = headerItems,
        )

        // ── Header haze overlay (2026-09-04, revised) ──
        // Progressive top-fade blur over the list — declared AFTER the
        // LazyColumn so it draws on top of it, BEFORE the pinned pills so
        // they stay crisp above the frosted strip.
        ScreenHeaderHaze(
            hazeState = headerHaze,
            systemBarsTopPadding = systemBarsTopPadding,
        )

        // Persistent Liquid Glass header buttons. Siblings of the LazyColumn
        // (children of the ExpressivePullToRefreshBox), positioned at top-start
        // and top-end. They sample the artworkBackdrop (which captures the
        // entire scrolling content via Modifier.layerBackdrop on the LazyColumn)
        // to render the frosted-glass effect. PERSISTENT — stay at the top no
        // matter how far the user scrolls.
        //
        // Shown only when:
        //  - Liquid Glass master toggle is on (liquidGlassHeaderActive)
        //  - Not searching
        //  - Playlist is loaded
        //
        // Fixed (2026-09-04, user report: "When I select songs in history page
        // the liquid glass pills disappear and opaque rounded pill appears.
        // Fix this. Fix the same thing for other screens too"): selection mode
        // NO LONGER hides the glass pills. The back pill morphs in place —
        // close (X) icon + the "N songs" count, tap to clear the selection —
        // and the trailing search/more pill hides while songs are selected
        // (the opaque top bar below still carries the select-all and
        // SelectionSongMenu actions when Liquid Glass is off; with glass on,
        // the floating list-level affordances own them).
        // Capture playlist in a local val so the compiler can smart-cast it
        // to non-null inside the block (playlist is a delegate, so the
        // compiler can't smart-cast the property directly).
        val currentPlaylist = playlist
        if (layerBackdropActive && !isSearching && currentPlaylist != null) {
            // iOS-inspired back pill: persistent translucent liquid-glass
            // capsule containing a left-pointing chevron followed by the
            // text "Library", matching the user's reference screenshot.
            // The pill samples the artworkBackdrop (the entire scrolling
            // content) to render the liquid-glass blur. Tapping it pops
            // back to the previous destination; long-pressing it jumps
            // straight to the Home tab. In selection mode it becomes the
            // selection header: close (X), the count, tap to clear.
            LiquidGlassActionPill(
                backdrop = artworkBackdrop,
                interactive = true,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 12.dp, top = systemBarsTopPadding + 12.dp),
            ) {
                IconButton(
                    onClick = {
                        if (selection) {
                            selection = false
                            selectedSongMapIds = emptySet()
                        } else {
                            navController.navigateUp()
                        }
                    },
                    onLongClick = {
                        if (!selection) {
                            navController.backToMain()
                        }
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        painter =
                            painterResource(
                                if (selection) R.drawable.close else R.drawable.arrow_back,
                            ),
                        contentDescription = stringResource(R.string.library),
                        tint = liquidGlassContentColor(),
                    )
                }
                GlassPillTitleText(
                    text =
                        if (selection) {
                            val count = selectedPlaylistSongs.size
                            pluralStringResource(R.plurals.n_song, count, count)
                        } else {
                            playlist?.playlist?.name ?: stringResource(R.string.playlists)
                        },
                )
            }
            if (selection) {
                // Selection-actions pill (2026-09-04): the SAME actions the
                // opaque top bar offered in selection mode, rendered as glass
                // instead of swapping to an opaque bar — select-all / deselect
                // toggle and the ⋯ that opens SelectionSongMenu.
                val selectedCount = selectedPlaylistSongs.size
                val allSelected = selectedCount == filteredSongs.size && filteredSongs.isNotEmpty()
                LiquidGlassActionPill(
                    backdrop = artworkBackdrop,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 12.dp, top = systemBarsTopPadding + 12.dp),
                ) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.IconButton(
                            onClick = {
                                if (allSelected) {
                                    selectedSongMapIds = emptySet()
                                } else {
                                    selectedSongMapIds = visibleSongMapIds
                                }
                            },
                        ) {
                            Icon(
                                painter =
                                    painterResource(
                                        if (allSelected) R.drawable.deselect else R.drawable.select_all,
                                    ),
                                contentDescription = null,
                                tint = liquidGlassContentColor(),
                            )
                        }
                    }
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.IconButton(onClick = {
                            menuState.show {
                                SelectionSongMenu(
                                    songSelection =
                                        selectedPlaylistSongs.map { it.song },
                                    songPosition =
                                        selectedPlaylistSongs.map { it.map },
                                    onDismiss = menuState::dismiss,
                                    clearAction = {
                                        selection = false
                                        selectedSongMapIds = emptySet()
                                    },
                                )
                            }
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.more_vert),
                                contentDescription = null,
                                tint = liquidGlassContentColor(),
                            )
                        }
                    }
                }
            } else {
            LiquidGlassActionPill(
                backdrop = artworkBackdrop,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 12.dp, top = systemBarsTopPadding + 12.dp),
            ) {
                // Search
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.IconButton(onClick = { isSearching = true }) {
                        Icon(
                            painter = painterResource(R.drawable.search),
                            contentDescription = null,
                            tint = liquidGlassContentColor(),
                        )
                    }
                }
                // More
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.IconButton(onClick = {
                        menuState.show {
                            PlaylistMenu(
                                playlist = currentPlaylist,
                                coroutineScope = coroutineScope,
                                onDismiss = menuState::dismiss,
                                onChangeCover =
                                    if (
                                        currentPlaylist.playlist.isEditable == true &&
                                        coverState !is PlaylistCoverState.Loading
                                    ) {
                                        {
                                            menuState.dismiss()
                                            pickCoverLauncher.launch(arrayOf("image/*"))
                                        }
                                    } else {
                                        null
                                    },
                                onRemoveCover =
                                    if (
                                        currentPlaylist.playlist.hasCustomCover &&
                                        coverState !is PlaylistCoverState.Loading
                                    ) {
                                        {
                                            menuState.dismiss()
                                            viewModel.removeCover()
                                        }
                                    } else {
                                        null
                                    },
                                onAddSongs = {
                                    navController.navigate(TELEGRAM_BOTS_ROUTE)
                                },
                            )
                        }
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.more_horiz),
                            contentDescription = null,
                            tint = liquidGlassContentColor(),
                        )
                    }
                }
            }
            } // end trailing-pill selection guard (2026-09-04: hidden while selecting)
        }

        // Top App Bar (2026-09-04 revision): shown when Liquid Glass is
        // disabled OR when searching. Selection mode is handled by the glass
        // pills above when glass is on, so the opaque bar only renders for
        // the non-glass case — no more opaque pill swap mid-selection.
        if (!liquidGlassHeaderActive || isSearching) {
        // Top App Bar
        val topAppBarColors =
            if (transparentAppBar) {
                TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                    navigationIconContentColor = Color.White,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White,
                )
            } else {
                TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = Color.Transparent,
                )
            }

        TopAppBar(
            colors = topAppBarColors,
            windowInsets =
                WindowInsets(top = systemBarsTopPadding)
                    .union(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
            title = {
                if (selection) {
                    val count = selectedPlaylistSongs.size
                    Text(
                        text = pluralStringResource(R.plurals.n_song, count, count),
                        style = MaterialTheme.typography.titleLarge,
                    )
                } else if (isSearching) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.search),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                    )
                } else if (showTopBarTitle) {
                    Text(playlist?.playlist?.name.orEmpty())
                }
            },
            navigationIcon = {
                // Show the back/close arrow when:
                //  - Searching
                //  - In selection mode
                //  - Scrolled past the hero (showTopBarTitle)
                //  - Liquid Glass is OFF (the persistent LiquidGlass back button
                //    isn't there, so the TopAppBar must provide back navigation
                //    even when the hero is visible)
                if (isSearching || selection || showTopBarTitle || !liquidGlassHeaderActive) {
                    IconButton(
                        onClick = {
                            if (isSearching) {
                                isSearching = false
                                query = TextFieldValue()
                            } else if (selection) {
                                selection = false
                            } else {
                                navController.navigateUp()
                            }
                        },
                        onLongClick = {
                            if (!isSearching) {
                                navController.backToMain()
                            }
                        },
                    ) {
                        Icon(
                            painter =
                                painterResource(
                                    if (selection || isSearching) R.drawable.close else R.drawable.arrow_back,
                                ),
                            contentDescription = null,
                        )
                    }
                }
            },
            actions = {
                if (selection) {
                    val count = selectedPlaylistSongs.size
                    IconButton(
                        onClick = {
                            if (count == filteredSongs.size) {
                                selectedSongMapIds = emptySet()
                            } else {
                                selectedSongMapIds = visibleSongMapIds
                            }
                        },
                        onLongClick = {},
                    ) {
                        Icon(
                            painter =
                                painterResource(
                                    if (count == filteredSongs.size) R.drawable.deselect else R.drawable.select_all,
                                ),
                            contentDescription = null,
                        )
                    }

                    IconButton(
                        onClick = {
                            menuState.show {
                                SelectionSongMenu(
                                    songSelection =
                                        selectedPlaylistSongs.map { it.song },
                                    songPosition =
                                        selectedPlaylistSongs.map { it.map },
                                    onDismiss = menuState::dismiss,
                                    clearAction = {
                                        selection = false
                                        selectedSongMapIds = emptySet()
                                    },
                                )
                            }
                        },
                        onLongClick = {},
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.more_vert),
                            contentDescription = null,
                        )
                    }
                } else if (!isSearching) {
                    // Show search + more when:
                    //  - Scrolled past the hero (showTopBarTitle)
                    //  - Liquid Glass is OFF (the persistent LiquidGlass pill
                    //    isn't there, so the TopAppBar must provide search+more
                    //    even when the hero is visible)
                    if (showTopBarTitle || !liquidGlassHeaderActive) {
                        IconButton(
                            onClick = { isSearching = true },
                            onLongClick = {},
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.search),
                                contentDescription = null,
                            )
                        }
                        playlist?.let { currentPlaylist ->
                            IconButton(
                                onClick = {
                                    menuState.show {
                                        PlaylistMenu(
                                            playlist = currentPlaylist,
                                            coroutineScope = coroutineScope,
                                            onDismiss = menuState::dismiss,
                                            onChangeCover =
                                                if (
                                                    currentPlaylist.playlist.isEditable == true &&
                                                    coverState !is PlaylistCoverState.Loading
                                                ) {
                                                    {
                                                        menuState.dismiss()
                                                        pickCoverLauncher.launch(arrayOf("image/*"))
                                                    }
                                                } else {
                                                    null
                                                },
                                            onRemoveCover =
                                                if (
                                                    currentPlaylist.playlist.hasCustomCover &&
                                                    coverState !is PlaylistCoverState.Loading
                                                ) {
                                                    {
                                                        menuState.dismiss()
                                                        viewModel.removeCover()
                                                    }
                                                } else {
                                                    null
                                                },
                                            onAddSongs = {
                                                navController.navigate(TELEGRAM_BOTS_ROUTE)
                                            },
                                        )
                                    }
                                },
                                onLongClick = {},
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.more_horiz),
                                    contentDescription = stringResource(R.string.more_options),
                                )
                            }
                        }
                    }
                }
            },
        )
        } // end if (!liquidGlassHeaderActive || selection || isSearching)

        // Bottom fade overlay + Floating Home dock button were removed per
        // user request (2026-08-28). The scrollable list now ends cleanly at
        // the bottom of the page surface; the floating liquid-glass "Home"
        // dock button at bottom-start is also gone — both were reported as
        // visual clutter on the playlist detail screens.

        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.union(WindowInsets.ime))
                    .align(Alignment.BottomCenter),
        )
    } // end ExpressivePullToRefreshBox
    } // end CompositionLocalProvider
}

private const val MediaDetailMetadataSeparator = "  •  "
