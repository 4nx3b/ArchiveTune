/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.playlist

import android.os.Build
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastSumBy
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.offline.Download
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalDownloadUtil
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AppBarHeight
import moe.rukamori.archivetune.constants.AutoPlaylistSongSortDescendingKey
import moe.rukamori.archivetune.constants.AutoPlaylistSongSortType
import moe.rukamori.archivetune.constants.AutoPlaylistSongSortTypeKey
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.constants.YtmSyncKey
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.ui.component.AppleMusicPlaylistHero
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.DraggableScrollbar
import moe.rukamori.archivetune.ui.component.EmptyPlaceholder
import moe.rukamori.archivetune.ui.lottie.ArchiveTuneLottie
import moe.rukamori.archivetune.ui.component.FrostedHeaderPill
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.LiquidGlassActionPill
import moe.rukamori.archivetune.ui.component.GlassPillTitleText
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.layerBackdrop
import moe.rukamori.archivetune.ui.component.liquidGlassContentColor
import moe.rukamori.archivetune.ui.component.rememberBackdrop
import moe.rukamori.archivetune.ui.player.LocalPlayerLyricsFullScreen
import moe.rukamori.archivetune.ui.component.MediaDetailAction
import moe.rukamori.archivetune.ui.component.SongListItem
import moe.rukamori.archivetune.ui.component.SortHeader
import moe.rukamori.archivetune.ui.component.rememberLayerBackdropSettled
import moe.rukamori.archivetune.ui.menu.SelectionSongMenu
import moe.rukamori.archivetune.ui.menu.SongMenu
import moe.rukamori.archivetune.ui.player.LocalMiniPlayerDocked
import moe.rukamori.archivetune.ui.screens.downloads.DownloadLibraryScreen
import moe.rukamori.archivetune.ui.utils.HeaderDownloadItem
import moe.rukamori.archivetune.ui.utils.HeaderDownloadProgressIndicator
import moe.rukamori.archivetune.ui.utils.HeaderDownloadState
import moe.rukamori.archivetune.ui.utils.ItemWrapper
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.ui.utils.headerDownloadState
import moe.rukamori.archivetune.ui.utils.sendAddMissingDownloads
import moe.rukamori.archivetune.ui.utils.sendRemoveDownloads
import moe.rukamori.archivetune.ui.utils.sendPauseRunningDownloads
import moe.rukamori.archivetune.ui.utils.sendResumePausedDownloads
import moe.rukamori.archivetune.utils.makeTimeString
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.AutoPlaylistViewModel
import dev.chrisbanes.haze.hazeSource
import moe.rukamori.archivetune.ui.screens.ScreenHeaderHaze
import moe.rukamori.archivetune.ui.screens.rememberScreenHeaderHaze

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AutoPlaylistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: AutoPlaylistViewModel = hiltViewModel(),
) {
    if (viewModel.playlist == "downloaded") {
        DownloadLibraryScreen(navController = navController)
        return
    }

    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val playlist =
        if (viewModel.playlist == "liked") stringResource(R.string.liked) else stringResource(R.string.offline)

    val songs by viewModel.likedSongs.collectAsStateWithLifecycle()

    var isSearching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(TextFieldValue()) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearching) {
        if (isSearching) {
            focusRequester.requestFocus()
        }
    }

    val (ytmSync) = rememberPreference(YtmSyncKey, true)

    val likeLength = remember(songs) { songs.fastSumBy { it.song.duration } }

    val playlistId = viewModel.playlist
    val playlistType =
        when (playlistId) {
            "liked" -> PlaylistType.LIKE
            "downloaded" -> PlaylistType.DOWNLOAD
            else -> PlaylistType.OTHER
        }

    val wrappedSongs =
        remember(songs) {
            songs.map { item -> ItemWrapper(item) }.toMutableStateList()
        }

    var selection by remember { mutableStateOf(false) }
    val selectedCount by remember(wrappedSongs) {
        derivedStateOf { wrappedSongs.count { it.isSelected } }
    }

    LaunchedEffect(selection, selectedCount) {
        if (selection && selectedCount == 0) {
            selection = false
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
            wrappedSongs.forEach { it.isSelected = false }
        }
    } else {

        BackHandler {
            try {
                if (!navController.popBackStack()) {
                    navController.navigate("library") {
                        launchSingleTop = true
                    }
                }
            } catch (_: Exception) {
                try {
                    if (!navController.navigateUp()) {
                        navController.navigate("library") { launchSingleTop = true }
                    }
                } catch (_: Exception) {

                }
            }
        }
    }

    val (sortType, onSortTypeChange) =
        rememberEnumPreference(
            AutoPlaylistSongSortTypeKey,
            AutoPlaylistSongSortType.CREATE_DATE,
        )
    val (sortDescending, onSortDescendingChange) = rememberPreference(AutoPlaylistSongSortDescendingKey, true)

    val downloadUtil = LocalDownloadUtil.current
    var downloads by remember { mutableStateOf<Map<String, Download>>(emptyMap()) }
    var downloadState by remember { mutableStateOf<HeaderDownloadState>(HeaderDownloadState.None) }

    LaunchedEffect(ytmSync, playlistType) {
        if (ytmSync && playlistType == PlaylistType.LIKE) {
            viewModel.syncLikedSongs()
        }
    }

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

    LaunchedEffect(songs) {
        val songIds = songs.map { it.song.id }
        downloadUtil.downloads.collect { currentDownloads ->
            downloads = currentDownloads
            downloadState = headerDownloadState(songIds, currentDownloads)
        }
    }

    var showRemoveDownloadDialog by remember { mutableStateOf(false) }

    if (showRemoveDownloadDialog) {
        DefaultDialog(
            onDismiss = { showRemoveDownloadDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.remove_download_playlist_confirm, playlist),
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
                        sendRemoveDownloads(
                            context = context,
                            songIds = songs.orEmpty().map { it.song.id },
                        )
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
        )
    }

    val filteredSongs =
        remember(wrappedSongs, query) {
            val searchQuery = query.text.trim()
            if (searchQuery.isEmpty()) {
                wrappedSongs
            } else {
                wrappedSongs.filter { wrapper ->
                    val song = wrapper.item
                    song.song.title.contains(searchQuery, true) ||
                        song.artists.any { it.name.contains(searchQuery, true) }
                }
            }
        }

    val lazyListState = rememberLazyListState()
    val surfaceColor = MaterialTheme.colorScheme.surface

    val showTopBarTitle by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0
        }
    }

    val isListScrolling by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0 ||
                lazyListState.firstVisibleItemScrollOffset > 0
        }
    }

    val liquidGlassEnabled by rememberPreference(LiquidGlassEnabledKey, defaultValue = false)
    val liquidGlassHeaderActive =
        liquidGlassEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val lyricsFullScreen = LocalPlayerLyricsFullScreen.current

    val screenSettled = rememberLayerBackdropSettled()

    val layerBackdropActive = liquidGlassHeaderActive && !lyricsFullScreen && screenSettled

    val backdrop = rememberBackdrop(surfaceColor)

    val transparentAppBar by remember {
        derivedStateOf {
            (!selection && !isSearching && !showTopBarTitle) || liquidGlassHeaderActive
        }
    }

    val headerItems by remember {
        derivedStateOf {
            if (songs.isNotEmpty() && !isSearching) 2 else 0
        }
    }

    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    CompositionLocalProvider(
        LocalMiniPlayerDocked provides isListScrolling,
    ) {

    val headerHaze = rememberScreenHeaderHaze()
    Box(
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
                            Modifier.layerBackdrop(backdrop)
                        } else {
                            Modifier
                        },
                    )
                    .hazeSource(headerHaze)
                    .padding(
                        top = if (isSearching) systemBarsTopPadding + AppBarHeight else 0.dp,
                    ),
            contentPadding =
                PaddingValues(
                    bottom =
                        LocalPlayerAwareWindowInsets.current
                            .asPaddingValues()
                            .calculateBottomPadding(),
                ),
        ) {
            if (songs.isEmpty()) {
                item(
                    key = "empty",
                    contentType = CONTENT_TYPE_EMPTY,
                ) {
                    EmptyPlaceholder(
                        icon = R.drawable.music_note,
                        text = stringResource(R.string.playlist_is_empty),

                        lottieRes = ArchiveTuneLottie.EmptyStateRes,
                    )
                }
            } else {
                if (!isSearching) {

                    item(
                        key = "header",
                        contentType = CONTENT_TYPE_HEADER,
                    ) {
                        val sectionLabelRes =
                            if (playlistId == "liked") {
                                R.string.liked
                            } else {
                                R.string.offline
                            }
                        AppleMusicPlaylistHero(
                            sectionLabel = stringResource(sectionLabelRes),
                            title = playlist,
                            subtitle =
                                listOf(
                                    pluralStringResource(
                                        R.plurals.n_song,
                                        songs.size,
                                        songs.size,
                                    ),
                                    makeTimeString(likeLength * 1000L),
                                ).joinToString(MediaDetailMetadataSeparator),
                            onPlay = {
                                playerConnection.playQueue(
                                    ListQueue(
                                        title = playlist,
                                        items = songs.map { it.toMediaItem() },
                                    ),
                                )
                            },
                            onShuffle = {
                                playerConnection.playQueue(
                                    ListQueue(
                                        title = playlist,
                                        items = songs.shuffled().map { it.toMediaItem() },
                                    ),
                                )
                            },
                            additionalActions = {
                                val isCompleted = downloadState == HeaderDownloadState.Completed
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
                                                                title = it.song.title,
                                                            )
                                                        },
                                                    downloads = downloads,
                                                    downloadUtil = downloadUtil,
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

                item(
                    key = "sortHeader",
                    contentType = CONTENT_TYPE_HEADER,
                ) {
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
                                    AutoPlaylistSongSortType.CREATE_DATE -> R.string.sort_by_create_date
                                    AutoPlaylistSongSortType.NAME -> R.string.sort_by_name
                                    AutoPlaylistSongSortType.ARTIST -> R.string.sort_by_artist
                                    AutoPlaylistSongSortType.PLAY_TIME -> R.string.sort_by_play_time
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                itemsIndexed(
                    items = filteredSongs,
                    key = { _, song -> song.item.id },
                    contentType = { _, _ -> CONTENT_TYPE_SONG },
                ) { index, songWrapper ->
                    SongListItem(
                        song = songWrapper.item,
                        isActive = songWrapper.item.song.id == mediaMetadata?.id,
                        isPlaying = isPlaying,
                        showInLibraryIcon = true,
                        trailingContent = {
                            androidx.compose.material3.IconButton(
                                onClick = {
                                    menuState.show {
                                        SongMenu(
                                            originalSong = songWrapper.item,
                                            navController = navController,
                                            onDismiss = menuState::dismiss,
                                        )
                                    }
                                },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.more_vert),
                                    contentDescription = null,
                                )
                            }
                        },
                        isSelected = songWrapper.isSelected && selection,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (!selection || selectedCount == 0) {
                                            if (songWrapper.item.song.id == mediaMetadata?.id) {
                                                playerConnection.player.togglePlayPause()
                                            } else {
                                                val visibleSongs = filteredSongs.map { it.item }
                                                playerConnection.playQueue(
                                                    ListQueue(
                                                        title = playlist,
                                                        items = visibleSongs.map { it.toMediaItem() },
                                                        startIndex = index,
                                                    ),
                                                )
                                            }
                                        } else {
                                            songWrapper.isSelected = !songWrapper.isSelected
                                        }
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (!selection) {
                                            selection = true
                                            wrappedSongs.forEach { it.isSelected = false }
                                            songWrapper.isSelected = true
                                        } else {
                                            songWrapper.isSelected = !songWrapper.isSelected
                                        }
                                    },
                                ).animateItem(),
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

        ScreenHeaderHaze(
            hazeState = headerHaze,
            systemBarsTopPadding = systemBarsTopPadding,
        )

        if (layerBackdropActive && !isSearching) {

            LiquidGlassActionPill(
                backdrop = backdrop,
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
                            wrappedSongs.forEach { it.isSelected = false }
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
                            pluralStringResource(R.plurals.n_song, selectedCount, selectedCount)
                        } else {
                            playlist
                        },
                )
            }
            LiquidGlassActionPill(
                backdrop = backdrop,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 12.dp, top = systemBarsTopPadding + 12.dp),
            ) {
                if (selection) {

                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.IconButton(
                            onClick = {
                                if (selectedCount == wrappedSongs.size) {
                                    wrappedSongs.forEach { it.isSelected = false }
                                    selection = false
                                } else {
                                    wrappedSongs.forEach { it.isSelected = true }
                                }
                            },
                        ) {
                            Icon(
                                painter =
                                    painterResource(
                                        if (selectedCount == wrappedSongs.size) R.drawable.deselect else R.drawable.select_all,
                                    ),
                                contentDescription = null,
                                tint = liquidGlassContentColor(),
                            )
                        }
                    }
                    androidx.compose.material3.IconButton(onClick = {
                        menuState.show {
                            SelectionSongMenu(
                                songSelection =
                                    wrappedSongs
                                        .filter { it.isSelected }
                                        .map { it.item },
                                onDismiss = menuState::dismiss,
                                clearAction = {
                                    selection = false
                                    wrappedSongs.forEach { it.isSelected = false }
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
                } else {

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

                if (songs.isNotEmpty()) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.IconButton(onClick = {
                            menuState.show {
                                SelectionSongMenu(
                                    songSelection = songs,
                                    onDismiss = menuState::dismiss,
                                    clearAction = {},
                                )
                            }
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.more_horiz),
                                contentDescription = stringResource(R.string.more_options),
                                tint = liquidGlassContentColor(),
                            )
                        }
                    }
                }
                }
            }
        }

        if (!liquidGlassHeaderActive || isSearching) {
        TopAppBar(
            scrollBehavior = scrollBehavior,
            windowInsets =
                WindowInsets(top = systemBarsTopPadding)
                    .union(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
            colors =
                if (transparentAppBar) {
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,

                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                    )
                } else {
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = Color.Transparent,
                    )
                },
            title = {
                when {
                    selection -> {
                        Text(
                            text = pluralStringResource(R.plurals.n_song, selectedCount, selectedCount),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }

                    isSearching -> {
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
                    }

                    showTopBarTitle -> {
                        Text(text = playlist)
                    }
                }
            },
            navigationIcon = {

                if (isSearching || selection || showTopBarTitle || !liquidGlassHeaderActive) {

                    if (!isSearching && !selection && !liquidGlassHeaderActive) {
                        FrostedHeaderPill {
                            IconButton(
                                onClick = { navController.navigateUp() },
                                onLongClick = { navController.backToMain() },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.arrow_back),
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
                    } else {
                        IconButton(
                            onClick = {
                                when {
                                    isSearching -> {
                                        isSearching = false
                                        query = TextFieldValue()
                                        focusManager.clearFocus()
                                    }

                                    selection -> {
                                        selection = false
                                        wrappedSongs.forEach { it.isSelected = false }
                                    }

                                    else -> {
                                        navController.navigateUp()
                                    }
                                }
                            },
                            onLongClick = {
                                if (!isSearching && !selection) {
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
                }
            },
            actions = {
                if (selection) {
                    androidx.compose.material3.IconButton(
                        onClick = {
                            if (selectedCount == wrappedSongs.size) {
                                wrappedSongs.forEach { it.isSelected = false }
                                selection = false
                            } else {
                                wrappedSongs.forEach { it.isSelected = true }
                            }
                        },
                    ) {
                        Icon(
                            painter =
                                painterResource(
                                    if (selectedCount == wrappedSongs.size) R.drawable.deselect else R.drawable.select_all,
                                ),
                            contentDescription = null,
                        )
                    }

                    androidx.compose.material3.IconButton(
                        onClick = {
                            menuState.show {
                                SelectionSongMenu(
                                    songSelection =
                                        wrappedSongs
                                            .filter { it.isSelected }
                                            .map { it.item },
                                    onDismiss = menuState::dismiss,
                                    clearAction = {
                                        selection = false
                                        wrappedSongs.forEach { it.isSelected = false }
                                    },
                                )
                            }
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.more_vert),
                            contentDescription = null,
                        )
                    }
                } else if (!isSearching) {

                    if (showTopBarTitle || !liquidGlassHeaderActive) {
                        androidx.compose.material3.IconButton(
                            onClick = { isSearching = true },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.search),
                                contentDescription = null,
                            )
                        }
                        if (songs.isNotEmpty()) {
                            androidx.compose.material3.IconButton(
                                onClick = {
                                    menuState.show {
                                        SelectionSongMenu(
                                            songSelection = songs,
                                            onDismiss = menuState::dismiss,
                                            clearAction = {},
                                        )
                                    }
                                },
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
        }

    }
    }
}

private const val CONTENT_TYPE_EMPTY = "empty"
private const val CONTENT_TYPE_HEADER = "header"
private const val CONTENT_TYPE_SONG = "song"
private const val MediaDetailMetadataSeparator = "  •  "

enum class PlaylistType {
    LIKE,
    DOWNLOAD,
    OTHER,
}
