/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalMiniPlayerVisible
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalDownloadUtil
import moe.rukamori.archivetune.constants.AppBarHeight
import moe.rukamori.archivetune.constants.HideExplicitKey
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.db.entities.detectAudioExtensionFromSpans
import moe.rukamori.archivetune.db.entities.extensionToMimeType
import moe.rukamori.archivetune.constants.SongSortDescendingKey
import moe.rukamori.archivetune.constants.SongSortType
import moe.rukamori.archivetune.constants.SongSortTypeKey
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.ui.component.AppleMusicPlaylistHero
import moe.rukamori.archivetune.ui.component.BottomFadeOverlay
import moe.rukamori.archivetune.ui.component.DraggableScrollbar
import moe.rukamori.archivetune.ui.component.EmptyPlaceholder
import moe.rukamori.archivetune.ui.component.MediaDetailAction
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.LibraryHomeDockButton
import moe.rukamori.archivetune.ui.component.LiquidGlassActionPill
import moe.rukamori.archivetune.ui.component.GlassPillTitleText
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.SongListItem
import moe.rukamori.archivetune.ui.component.SortHeader
import moe.rukamori.archivetune.ui.component.layerBackdrop
import moe.rukamori.archivetune.ui.component.liquidGlassContentColor
import moe.rukamori.archivetune.ui.component.rememberBackdrop
import moe.rukamori.archivetune.ui.component.rememberLayerBackdropSettled
import moe.rukamori.archivetune.ui.player.LocalMiniPlayerDocked
import moe.rukamori.archivetune.ui.player.LocalPlayerLyricsFullScreen
import moe.rukamori.archivetune.ui.menu.SelectionSongMenu
import moe.rukamori.archivetune.ui.menu.SongMenu
import moe.rukamori.archivetune.ui.utils.ItemWrapper
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.CachePlaylistViewModel
import dev.chrisbanes.haze.hazeSource
import moe.rukamori.archivetune.ui.screens.ScreenHeaderHaze
import moe.rukamori.archivetune.ui.screens.rememberScreenHeaderHaze
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CachePlaylistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: CachePlaylistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val cachedLabel = stringResource(R.string.cached_playlist)
    val playerConnection = LocalPlayerConnection.current ?: return
    val downloadUtil = LocalDownloadUtil.current
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val cachedSongs by viewModel.cachedSongs.collectAsStateWithLifecycle()

    // SAF folder picker for "Export all".
    // Exports songs in their original audio format (FLAC, OPUS, M4A, etc.)
    // based on the FormatEntity codec metadata, instead of hardcoded .mp3.
    val database = LocalDatabase.current
    val exportAllLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri == null) return@rememberLauncherForActivityResult
            coroutineScope.launch {
                var exported = 0
                var failed = 0
                for ((index, song) in cachedSongs.withIndex()) {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            val cache = downloadUtil.downloadCache
                            val spans = getCachedSpansForKey(cache, song.id)
                            if (spans.isEmpty()) {
                                throw IllegalStateException("No cache")
                            }
                            val safeTitle = song.title.trim()
                                .replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "audio" }
                            // Detect the actual audio format from cached data's
                            // magic bytes so the exported file gets the correct
                            // extension (e.g. .opus instead of wrongly .flac).
                            val detectedExt = detectAudioExtensionFromSpans(spans)
                            val mime = extensionToMimeType(detectedExt)
                            // createDocument() requires a document URI (representing the
                            // parent directory as a document), NOT the tree URI returned by
                            // OpenDocumentTree. Convert via getTreeDocumentId + buildDocumentUriUsingTree.
                            val parentDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                                treeUri,
                                android.provider.DocumentsContract.getTreeDocumentId(treeUri),
                            )
                            val destUri = android.provider.DocumentsContract.createDocument(
                                context.contentResolver,
                                parentDocUri,
                                mime,
                                "$safeTitle.$detectedExt",
                            ) ?: throw IllegalStateException("Could not create file")
                            context.contentResolver.openOutputStream(destUri, "w")?.use { output ->
                                spans.sortedBy { it.position }.forEach { span ->
                                    java.io.FileInputStream(span.file).use { input ->
                                        input.copyTo(output)
                                    }
                                }
                                output.flush()
                            } ?: throw IllegalStateException("Could not open stream")
                        }
                    }
                    if (result.isSuccess) exported++ else failed++
                }
                Toast.makeText(
                    context,
                    "Exported $exported song(s)${if (failed > 0) ", $failed failed" else ""}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()

    val (sortType, onSortTypeChange) =
        rememberEnumPreference(
            SongSortTypeKey,
            SongSortType.CREATE_DATE,
        )
    val (sortDescending, onSortDescendingChange) = rememberPreference(SongSortDescendingKey, true)
    val hideExplicit by rememberPreference(key = HideExplicitKey, defaultValue = false)

    val wrappedSongs =
        remember(cachedSongs, sortType, sortDescending) {
            val sortedSongs =
                when (sortType) {
                    SongSortType.CREATE_DATE -> {
                        cachedSongs.sortedBy { it.song.dateDownload ?: LocalDateTime.MIN }
                    }

                    SongSortType.NAME -> {
                        cachedSongs.sortedBy { it.song.title }
                    }

                    SongSortType.ARTIST -> {
                        cachedSongs.sortedBy { song ->
                            song.artists.joinToString(separator = "") { artist -> artist.name }
                        }
                    }

                    SongSortType.PLAY_TIME -> {
                        cachedSongs.sortedBy { it.song.totalPlayTime }
                    }
                }.let { if (sortDescending) it.reversed() else it }

            sortedSongs.map { song -> ItemWrapper(song) }
        }.toMutableStateList()

    var selection by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(TextFieldValue()) }
    val focusRequester = remember { FocusRequester() }
    val lazyListState = rememberLazyListState()

    val selectedCount by remember(wrappedSongs) {
        derivedStateOf { wrappedSongs.count { it.isSelected } }
    }

    LaunchedEffect(selectedCount) {
        if (selection && selectedCount == 0) {
            selection = false
        }
    }

    LaunchedEffect(isSearching) {
        if (isSearching) {
            focusRequester.requestFocus()
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
        // cache playlist page. Per user report (2026-08-29): gesture not
        // working in playlists. New approach: popBackStack() directly
        // first, fall back to navigate("library") if no previous entry.
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
                    // Last-resort: let the system handle the back press.
                }
            }
        }
    }

    val filteredSongs =
        remember(wrappedSongs, query) {
            if (query.text.isEmpty()) {
                wrappedSongs
            } else {
                wrappedSongs.filter { wrapper ->
                    val song = wrapper.item
                    song.title.contains(query.text, true) ||
                        song.artists.any { it.name.contains(query.text, true) }
                }
            }
        }

    val surfaceColor = MaterialTheme.colorScheme.surface

    val showTopBarTitle by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0
        }
    }

    // Whether the user has scrolled past the hero header — used to trigger
    // the SimpMusic-style mini player "shrink + dock to right of Home
    // button" behavior. Hoisted here so it can be propagated to the
    // MiniPlayer subtree via CompositionLocalProvider wrapping the Box.
    val isListScrolling by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0 ||
                lazyListState.firstVisibleItemScrollOffset > 0
        }
    }

    // Liquid Glass header setup. Mirror LocalPlaylistScreen's pattern: read
    // the master toggle, gate on Android 12+ (kyant RuntimeShader requires
    // API 31+), and suspend the layerBackdrop while the full-screen lyrics
    // overlay is open on top of this screen — otherwise the per-frame GPU
    // recording steals budget from the 60 Hz karaoke lyrics sweep.
    val liquidGlassEnabled by rememberPreference(LiquidGlassEnabledKey, defaultValue = false)
    val liquidGlassHeaderActive =
        liquidGlassEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
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
    // Created unconditionally (cheap — just a GraphicsLayer handle). Actual
    // content recording only happens when `Modifier.layerBackdrop(backdrop)`
    // is applied to the LazyColumn below, gated on `layerBackdropActive`.
    //
    // Initial backdrop color is the page surface color (NOT Color.Black) so
    // that positions where the LazyColumn has no content (e.g. the empty
    // band above the hero header item that has top padding =
    // systemBarsTopPadding + AppBarHeight) blend with the page background
    // instead of showing a hard black band behind the liquid glass pills.
    val backdrop = rememberBackdrop(surfaceColor)

    val transparentAppBar by remember {
        derivedStateOf {
            (!selection && !isSearching && !showTopBarTitle) || liquidGlassHeaderActive
        }
    }

    val headerItems by remember {
        derivedStateOf {
            if (filteredSongs.isNotEmpty() && !isSearching) 2 else 0
        }
    }

    // System bars padding
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

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
                    bottom = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
                ),
        ) {
            if (filteredSongs.isEmpty() && !isSearching) {
                item {
                    EmptyPlaceholder(
                        icon = R.drawable.music_note,
                        text = stringResource(R.string.playlist_is_empty),
                    )
                }
            }

            if (filteredSongs.isEmpty() && isSearching) {
                item {
                    EmptyPlaceholder(
                        icon = R.drawable.search,
                        text = stringResource(R.string.no_results_found),
                    )
                }
            } else {
                if (filteredSongs.isNotEmpty() && !isSearching) {
                    // Hero Header Item — iOS-inspired Apple Music style.
                    item(key = "header") {
                        AppleMusicPlaylistHero(
                            sectionLabel = cachedLabel,
                            title = cachedLabel,
                            subtitle =
                                pluralStringResource(
                                    R.plurals.n_song,
                                    filteredSongs.size,
                                    filteredSongs.size,
                                ),
                            onPlay = {
                                playerConnection.playQueue(
                                    ListQueue(
                                        title = "Cache Songs",
                                        items = filteredSongs.map { it.item.toMediaItem() },
                                    ),
                                )
                            },
                            onShuffle = {
                                playerConnection.playQueue(
                                    ListQueue(
                                        title = "Cache Songs",
                                        items = filteredSongs.shuffled().map { it.item.toMediaItem() },
                                    ),
                                )
                            },
                            additionalActions = {
                                // Export-all pill integrated into the hero row,
                                // matching the iOS redesign pill aesthetic (rounded
                                // capsule + pink accent) instead of a separate
                                // FilledTonalButton below the hero that broke the
                                // visual rhythm of the redesigned page.
                                MediaDetailAction(
                                    contentDescription = R.string.export_all_songs,
                                    contentColor = Color.White,
                                    onClick = { exportAllLauncher.launch(null) },
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.download),
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                    )
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

                if (filteredSongs.isNotEmpty()) {
                    // Sort Header
                    item(key = "sortHeader") {
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
                                        SongSortType.CREATE_DATE -> R.string.sort_by_create_date
                                        SongSortType.NAME -> R.string.sort_by_name
                                        SongSortType.ARTIST -> R.string.sort_by_artist
                                        SongSortType.PLAY_TIME -> R.string.sort_by_play_time
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // Song items
                itemsIndexed(filteredSongs, key = { _, song -> song.item.id }) { index, songWrapper ->
                    SongListItem(
                        song = songWrapper.item,
                        isActive = songWrapper.item.id == mediaMetadata?.id,
                        isPlaying = isPlaying,
                        isSelected = songWrapper.isSelected && selection,
                        showInLibraryIcon = true,
                        trailingContent = {
                            androidx.compose.material3.IconButton(onClick = {
                                menuState.show {
                                    SongMenu(
                                        originalSong = songWrapper.item,
                                        navController = navController,
                                        onDismiss = menuState::dismiss,
                                        isFromCache = true,
                                    )
                                }
                            }) {
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
                                        if (!selection) {
                                            if (songWrapper.item.id == mediaMetadata?.id) {
                                                playerConnection.player.togglePlayPause()
                                            } else {
                                                playerConnection.playQueue(
                                                    ListQueue(
                                                        title = "Cache Songs",
                                                        items = cachedSongs.map { it.toMediaItem() },
                                                        startIndex = cachedSongs.indexOfFirst { it.id == songWrapper.item.id },
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

        // ── Header haze overlay (2026-09-04, revised) ──
        // Progressive top-fade blur over the list — declared AFTER the
        // LazyColumn so it draws on top of it, BEFORE the pinned pills so
        // they stay crisp above the frosted strip.
        ScreenHeaderHaze(
            hazeState = headerHaze,
            systemBarsTopPadding = systemBarsTopPadding,
        )

        // Persistent Liquid Glass header buttons. Siblings of the LazyColumn
        // (children of the host Box), positioned at top-start and top-end.
        // They sample the backdrop (which captures the entire scrolling
        // content via Modifier.layerBackdrop on the LazyColumn) to render
        // the frosted-glass effect. PERSISTENT — they stay at the top no
        // matter how far the user scrolls, mirroring the LocalPlaylistScreen
        // pattern. Previously these pills lived inside the TopAppBar, which
        // uses scrollBehavior that slides the whole bar (including the
        // navigation icon and actions) off-screen when scrolling — that's
        // why the pills "scrolled and disappeared" on the cached page. Now
        // the TopAppBar is only used for selection mode and search mode; in
        // the default browsing state the persistent pills above handle back
        // navigation and actions.
        //
        // Shown only when:
        //  - Liquid Glass master toggle is on (liquidGlassHeaderActive)
        //  - Not in selection mode
        //  - Not searching
        //
        // Fixed (2026-09-04, user report: "When I select songs in history page
        // the liquid glass pills disappear and opaque rounded pill appears.
        // Fix this. Fix the same thing for other screens too"): selection mode
        // NO LONGER hides the glass pills. The back pill morphs in place —
        // close (X) icon + the "N songs" count, tap to clear the selection —
        // and the trailing pill swaps to the select-all / deselect toggle and
        // the ⋯ that opens SelectionSongMenu, the exact actions the opaque
        // selection bar offered.
        if (layerBackdropActive && !isSearching) {
            // iOS-inspired back pill: persistent translucent liquid-glass
            // capsule containing a left-pointing chevron followed by the
            // text "Library", matching the user's reference screenshot.
            // Tapping it pops back to the previous destination; long-pressing
            // it jumps straight to the Home tab. In selection mode it becomes
            // the selection header: close (X), the count, tap to clear.
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
                            cachedLabel
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
                    // Selection actions in glass (2026-09-04): select-all /
                    // deselect toggle + the ⋯ that opens SelectionSongMenu —
                    // the exact actions the opaque selection bar carried.
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
                                isFromCache = true,
                                onRemoveFromCache = { songs ->
                                    songs.forEach { viewModel.removeSongFromCache(it.id) }
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
                if (wrappedSongs.isNotEmpty()) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.IconButton(onClick = {
                            menuState.show {
                                SelectionSongMenu(
                                    songSelection = wrappedSongs.map { it.item },
                                    onDismiss = menuState::dismiss,
                                    clearAction = {},
                                    isFromCache = true,
                                    onRemoveFromCache = { songs ->
                                        songs.forEach { viewModel.removeSongFromCache(it.id) }
                                    },
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
                } // end non-selection branch of the trailing pill (2026-09-04)
            }
        }

        // Top App Bar (2026-09-04 revision): shown when Liquid Glass is
        // disabled OR when searching. Selection mode is handled by the glass
        // pills above when glass is on, so the opaque bar only renders for
        // the non-glass case — no more opaque pill swap mid-selection.
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
                        // Theme-aware: hardcoded Color.White was invisible on
                        // light surfaces. onBackground adapts to the active theme.
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
                        val count = wrappedSongs.count { it.isSelected }
                        Text(
                            text = pluralStringResource(R.plurals.n_song, count, count),
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
                        Text(text = stringResource(R.string.cached_playlist))
                    }
                }
            },
            navigationIcon = {
                // Show the back/close arrow when:
                //  - Searching
                //  - In selection mode
                //  - Scrolled past the hero (showTopBarTitle)
                //  - Liquid Glass is OFF (the persistent LiquidGlass back
                //    button isn't there, so the TopAppBar must provide back
                //    navigation even when the hero is visible)
                if (isSearching || selection || showTopBarTitle || !liquidGlassHeaderActive) {
                    IconButton(onClick = {
                        when {
                            isSearching -> {
                                isSearching = false
                                query = TextFieldValue()
                                focusManager.clearFocus()
                            }

                            selection -> {
                                selection = false
                            }

                            else -> {
                                navController.navigateUp()
                            }
                        }
                    }, onLongClick = {
                        if (!isSearching && !selection) {
                            navController.backToMain()
                        }
                    }) {
                        Icon(
                            painter =
                                painterResource(
                                    if (selection || isSearching) R.drawable.close else R.drawable.arrow_back,
                                ),
                            contentDescription = null,
                        )
                    }
                    if (!isSearching && !selection && !liquidGlassHeaderActive) {
                        // Library label next to back chevron — only when
                        // Liquid Glass is OFF (the persistent LiquidGlass
                        // pill above carries the label when LG is on).
                        Text(
                            text = stringResource(R.string.library),
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
            },
            actions = {
                if (selection) {
                    val count = wrappedSongs.count { it.isSelected }
                    androidx.compose.material3.IconButton(onClick = {
                        wrappedSongs.filter { it.isSelected }.forEach {
                            viewModel.removeSongFromCache(it.item.id)
                        }
                        selection = false
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.delete),
                            contentDescription = stringResource(R.string.remove_from_cache),
                        )
                    }

                    androidx.compose.material3.IconButton(onClick = {
                        if (count == wrappedSongs.size) {
                            wrappedSongs.forEach { it.isSelected = false }
                            selection = false
                        } else {
                            wrappedSongs.forEach { it.isSelected = true }
                        }
                    }) {
                        Icon(
                            painter =
                                painterResource(
                                    if (count == wrappedSongs.size) R.drawable.deselect else R.drawable.select_all,
                                ),
                            contentDescription = null,
                        )
                    }

                    androidx.compose.material3.IconButton(onClick = {
                        menuState.show {
                            SelectionSongMenu(
                                songSelection = wrappedSongs.filter { it.isSelected }.map { it.item },
                                onDismiss = menuState::dismiss,
                                clearAction = { selection = false },
                                isFromCache = true,
                                onRemoveFromCache = { songs ->
                                    songs.forEach { viewModel.removeSongFromCache(it.id) }
                                },
                            )
                        }
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.more_vert),
                            contentDescription = null,
                        )
                    }
                } else if (!isSearching) {
                    // Show search + more when:
                    //  - Scrolled past the hero (showTopBarTitle)
                    //  - Liquid Glass is OFF (the persistent LiquidGlass
                    //    pill isn't there, so the TopAppBar must provide
                    //    search+more even when the hero is visible)
                    if (showTopBarTitle || !liquidGlassHeaderActive) {
                        androidx.compose.material3.IconButton(onClick = { isSearching = true }) {
                            Icon(
                                painter = painterResource(R.drawable.search),
                                contentDescription = null,
                            )
                        }
                        if (wrappedSongs.isNotEmpty()) {
                            androidx.compose.material3.IconButton(
                                onClick = {
                                    menuState.show {
                                        SelectionSongMenu(
                                            songSelection = wrappedSongs.map { it.item },
                                            onDismiss = menuState::dismiss,
                                            clearAction = {},
                                            isFromCache = true,
                                            onRemoveFromCache = { songs ->
                                                songs.forEach { viewModel.removeSongFromCache(it.id) }
                                            },
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
        } // end if (!liquidGlassHeaderActive || selection || isSearching)

        // Bottom fade overlay + Floating Home dock button were removed per
        // user request (2026-08-28). The scrollable list now ends cleanly at
        // the bottom of the page surface; the floating liquid-glass "Home"
        // dock button at bottom-start is also gone — both were reported as
        // visual clutter on the playlist detail screens.
    } // end Box
    } // end CompositionLocalProvider
}

/**
 * Resolves cached spans for a given [songId]. Tries the key directly first,
 * then falls back to searching all cache keys for a matching entry.
 */
private fun getCachedSpansForKey(
    cache: androidx.media3.datasource.cache.Cache,
    songId: String,
): java.util.NavigableSet<androidx.media3.datasource.cache.CacheSpan> {
    var spans = cache.getCachedSpans(songId)
    if (spans.isNotEmpty()) return spans
    for (key in cache.keys) {
        val cleanKey = key.substringAfterLast("/")
        if (cleanKey == songId || key == songId) {
            spans = cache.getCachedSpans(key)
            if (spans.isNotEmpty()) return spans
        }
    }
    return spans
}
