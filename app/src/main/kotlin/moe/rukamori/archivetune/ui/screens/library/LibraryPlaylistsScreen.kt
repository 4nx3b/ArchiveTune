/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.library

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.ListThumbnailSize
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.constants.PlaylistEditLockKey
import moe.rukamori.archivetune.constants.PlaylistSortDescendingKey
import moe.rukamori.archivetune.constants.PlaylistSortType
import moe.rukamori.archivetune.constants.PlaylistSortTypeKey
import moe.rukamori.archivetune.constants.ThumbnailCornerRadius
import moe.rukamori.archivetune.constants.PlaylistViewTypeKey
import moe.rukamori.archivetune.constants.LibraryViewType
import moe.rukamori.archivetune.constants.PureBlackKey
import moe.rukamori.archivetune.constants.ShowTagsInLibraryKey
import moe.rukamori.archivetune.db.entities.Playlist
import moe.rukamori.archivetune.extensions.move
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.ui.component.AppleMusicStyleAccentColor
import moe.rukamori.archivetune.ui.component.CreatePlaylistDialog
import moe.rukamori.archivetune.ui.component.ExpressivePullToRefreshBox
import moe.rukamori.archivetune.ui.component.FrostedHeaderPill
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.ItemThumbnail
import moe.rukamori.archivetune.ui.component.ListItem
import moe.rukamori.archivetune.ui.component.LiquidGlassActionPill
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.PlaylistThumbnail
import moe.rukamori.archivetune.ui.component.TagsManagementDialog
import moe.rukamori.archivetune.ui.component.layerBackdrop
import moe.rukamori.archivetune.ui.component.liquidGlassContentColor
import moe.rukamori.archivetune.ui.component.rememberBackdrop
import moe.rukamori.archivetune.ui.component.rememberLayerBackdropSettled
import moe.rukamori.archivetune.ui.menu.PlaylistMenu
import moe.rukamori.archivetune.ui.menu.YouTubePlaylistMenu
import moe.rukamori.archivetune.ui.player.LocalPlayerLyricsFullScreen
import moe.rukamori.archivetune.ui.theme.PlayerColorExtractor
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.LibraryPlaylistsViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryPlaylistsScreen(
    navController: NavController,
    viewModel: LibraryPlaylistsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val coroutineScope = rememberCoroutineScope()
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val haptic = LocalHapticFeedback.current

    val (selectedTagIds, onSelectedTagIdsChange) = rememberPlaylistTagFilterState(database)
    val allTags by database.allTags().collectAsStateWithLifecycle(initialValue = emptyList())
    val (showTagsInLibrary) = rememberPreference(ShowTagsInLibraryKey, defaultValue = true)
    val activeSelectedTagIds = if (showTagsInLibrary) selectedTagIds else emptySet()
    var showTagsManagementDialog by rememberSaveable { mutableStateOf(false) }

    val liquidGlassEnabled by rememberPreference(LiquidGlassEnabledKey, defaultValue = false)
    val liquidGlassHeaderActive =
        liquidGlassEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val lyricsFullScreen = LocalPlayerLyricsFullScreen.current

    val screenSettled = rememberLayerBackdropSettled()

    val layerBackdropActive = liquidGlassHeaderActive && !lyricsFullScreen && screenSettled
    val surfaceColor = MaterialTheme.colorScheme.surface
    val artworkBackdrop = rememberBackdrop(surfaceColor)

    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    BackHandler {
        try {
            if (!navController.popBackStack()) {
                navController.navigate("library") { launchSingleTop = true }
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

    if (showTagsManagementDialog) {
        TagsManagementDialog(
            onDismiss = { showTagsManagementDialog = false },
        )
    }

    val (sortType, onSortTypeChange) =
        rememberEnumPreference(
            PlaylistSortTypeKey,
            PlaylistSortType.CUSTOM,
        )
    val (sortDescending, onSortDescendingChange) = rememberPreference(PlaylistSortDescendingKey, true)
    var locked by rememberPreference(PlaylistEditLockKey, defaultValue = true)
    val playlists by viewModel.allPlaylists.collectAsStateWithLifecycle()
    val filteredPlaylistIds by database
        .playlistIdsByTags(
            if (selectedTagIds.isEmpty()) emptyList() else selectedTagIds.toList(),
        ).collectAsStateWithLifecycle(initialValue = emptyList())

    var showHidden by rememberSaveable { mutableStateOf(false) }

    val visiblePlaylists =
        remember(playlists, selectedTagIds, filteredPlaylistIds, showHidden) {
            playlists.filter { playlist ->
                val name = playlist.playlist.name
                val matchesName = !name.contains("episode", ignoreCase = true)
                val matchesTags = selectedTagIds.isEmpty() || playlist.id in filteredPlaylistIds
                val matchesVisibility = showHidden || !playlist.playlist.isHidden
                matchesName && matchesTags && matchesVisibility
            }
        }
    val mutablePlaylists = remember { mutableStateListOf<Playlist>() }

    @Suppress("UnusedVariable") val isGridView = false
    var showCreatePlaylistDialog by rememberSaveable { mutableStateOf(false) }
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val lazyListState = rememberLazyListState()
    var pendingPlaylistOrderUpdate by remember { mutableStateOf(false) }
    val reorderableState =
        rememberReorderableLazyListState(
            lazyListState = lazyListState,
            scrollThresholdPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) { from, to ->
            if (from.index in mutablePlaylists.indices && to.index in mutablePlaylists.indices) {
                mutablePlaylists.move(from.index, to.index)
                pendingPlaylistOrderUpdate = true
            }
        }

    LaunchedEffect(visiblePlaylists) {
        mutablePlaylists.clear()
        mutablePlaylists.addAll(visiblePlaylists)
    }

    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging && pendingPlaylistOrderUpdate) {
            viewModel.updateCustomPlaylistOrder(
                mergeVisiblePlaylistOrder(
                    currentOrder = playlists,
                    visibleOrder = mutablePlaylists,
                ),
            )
            pendingPlaylistOrderUpdate = false
        }
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
        )
    }

    val playerAwareBottomPadding =
        LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Bottom)
            .asPaddingValues()
            .calculateBottomPadding() + 12.dp

    Box(modifier = Modifier.fillMaxSize()) {
        ExpressivePullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.sync() },
            modifier = Modifier.fillMaxSize(),
            indicatorOffset = LibraryPullToRefreshIndicatorOffset,
        ) {
            Column(
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

                        .padding(top = systemBarsTopPadding + 64.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = "LIST",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = AppleMusicStyleAccentColor,
                    )
                    Text(
                        text = stringResource(R.string.playlists),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = pluralStringResource(R.plurals.n_playlist, visiblePlaylists.size, visiblePlaylists.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    )
                }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {

                var showSortMenu by remember { mutableStateOf(false) }
                val currentSortLabel =
                    when (sortType) {
                        PlaylistSortType.CREATE_DATE -> {
                            stringResource(R.string.recently_added)
                        }

                        PlaylistSortType.NAME -> {
                            if (sortDescending) stringResource(R.string.sort_z_to_a) else stringResource(R.string.sort_a_to_z)
                        }

                        PlaylistSortType.SONG_COUNT -> {
                            stringResource(R.string.tracks_count_label)
                        }

                        PlaylistSortType.LAST_UPDATED -> {
                            stringResource(R.string.recently_updated)
                        }

                        PlaylistSortType.CUSTOM -> {
                            stringResource(R.string.custom_order)
                        }
                    }

                val showSortDirection = sortType != PlaylistSortType.CUSTOM
                val sortDirectionRotation by animateFloatAsState(
                    targetValue = if (sortDescending) 0f else 180f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
                    label = "PlaylistSortDirectionRotation",
                )

                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Row(
                            modifier =
                                Modifier
                                    .clip(CircleShape)
                                    .background(AppleMusicStyleAccentColor.copy(alpha = 0.12f))
                                    .clickable { showSortMenu = true }
                                    .padding(horizontal = 18.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = currentSortLabel,
                                modifier = Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = AppleMusicStyleAccentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                painter = painterResource(id = R.drawable.expand_more),
                                contentDescription = null,
                                tint = AppleMusicStyleAccentColor,
                                modifier = Modifier.size(16.dp),
                            )
                        }

                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                        ) {
                            PlaylistSortType.entries.forEach { type ->
                                val label =
                                    when (type) {
                                        PlaylistSortType.CREATE_DATE -> stringResource(R.string.recently_added)
                                        PlaylistSortType.NAME -> stringResource(R.string.sort_a_to_z)
                                        PlaylistSortType.SONG_COUNT -> stringResource(R.string.tracks_count_label)
                                        PlaylistSortType.LAST_UPDATED -> stringResource(R.string.recently_updated)
                                        PlaylistSortType.CUSTOM -> stringResource(R.string.custom_order)
                                    }
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        onSortTypeChange(type)
                                        if (type == PlaylistSortType.NAME) {
                                            onSortDescendingChange(false)
                                        }
                                        showSortMenu = false
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.hidden_playlists)) },
                                onClick = {
                                    showHidden = !showHidden
                                    showSortMenu = false
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.visibility_off),
                                        contentDescription = null,
                                    )
                                },
                                trailingIcon = {
                                    if (showHidden) {
                                        Icon(
                                            painter = painterResource(R.drawable.check),
                                            contentDescription = null,
                                        )
                                    }
                                },
                            )
                        }
                    }

                    if (showSortDirection) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = { onSortDescendingChange(!sortDescending) },
                            colors =
                                IconButtonDefaults.iconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.arrow_downward),
                                contentDescription =
                                    stringResource(
                                        if (sortDescending) {
                                            R.string.sort_order_descending
                                        } else {
                                            R.string.sort_order_ascending
                                        },
                                    ),
                                modifier =
                                    Modifier
                                        .size(16.dp)
                                        .graphicsLayer { rotationZ = sortDirectionRotation },
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (sortType == PlaylistSortType.CUSTOM && !layerBackdropActive) {
                        IconButton(
                            onClick = { locked = !locked },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                painter = painterResource(if (locked) R.drawable.lock else R.drawable.lock_open),
                                contentDescription = null,
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    if (!layerBackdropActive) {
                        Spacer(modifier = Modifier.width(12.dp))

                        IconButton(
                            onClick = { showCreatePlaylistDialog = true },
                            colors =
                                IconButtonDefaults.iconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.add),
                                contentDescription = stringResource(R.string.create_playlist),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                }
            }

            if (showTagsInLibrary) {
                PlaylistTagFilterRow(
                    tags = allTags,
                    selectedTagIds = selectedTagIds,
                    onSelectedTagIdsChange = onSelectedTagIdsChange,
                    onManageTagsClick = { showTagsManagementDialog = true },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isGridView) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = playerAwareBottomPadding),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        items = visiblePlaylists,
                        key = { playlist -> playlist.id },
                        contentType = { "playlist_grid" },
                    ) { playlist ->
                        PlaylistGridCard(
                            playlist = playlist,
                            onClick = {
                                openPlaylist(navController, playlist)
                            },
                            onPlay = {
                                playerConnection?.let { conn ->
                                    coroutineScope.launch {
                                        database.playlistSongs(playlist.id).firstOrNull()?.let { songs ->
                                            if (songs.isNotEmpty()) {
                                                conn.playQueue(ListQueue(items = songs.map { it.song.toMediaItem() }))
                                            }
                                        }
                                    }
                                }
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuState.show {
                                    triggerPlaylistMenu(playlist, coroutineScope, menuState)
                                }
                            },
                        )
                    }
                }
            } else {
                val listPlaylists = if (sortType == PlaylistSortType.CUSTOM) mutablePlaylists else visiblePlaylists
                val showDragHandles = sortType == PlaylistSortType.CUSTOM && !locked
                LazyColumn(
                    state = lazyListState,

                    contentPadding = PaddingValues(bottom = playerAwareBottomPadding),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(
                        items = listPlaylists,
                        key = { _, playlist -> playlist.id },
                        contentType = { _, _ -> "playlist_list" },
                    ) { _, playlist ->
                        ReorderableItem(
                            state = reorderableState,
                            key = playlist.id,
                            modifier =
                                Modifier.graphicsLayer {
                                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                                },
                        ) {
                            PlaylistListCard(
                                playlist = playlist,
                                onClick = {
                                    openPlaylist(navController, playlist)
                                },
                                onPlay = {
                                    playerConnection?.let { conn ->
                                        coroutineScope.launch {
                                            database.playlistSongs(playlist.id).firstOrNull()?.let { songs ->
                                                if (songs.isNotEmpty()) {
                                                    conn.playQueue(ListQueue(items = songs.map { it.song.toMediaItem() }))
                                                }
                                            }
                                        }
                                    }
                                },
                                onMenuClick = {
                                    menuState.show {
                                        triggerPlaylistMenu(playlist, coroutineScope, menuState)
                                    }
                                },
                                showDragHandle = showDragHandles,
                                dragHandleModifier =
                                    Modifier
                                        .draggableHandle()
                                        .graphicsLayer { alpha = 0.99f },
                            )
                        }
                    }
                }
            }
        }
        }

        if (layerBackdropActive) {
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
                        if (!navController.navigateUp()) {
                            navController.navigate("library") {
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    onLongClick = { navController.backToMain() },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = stringResource(R.string.back_button_desc),
                        tint = liquidGlassContentColor(),
                    )
                }
                Text(
                    text = stringResource(R.string.playlists),
                    color = liquidGlassContentColor(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        } else {
            FrostedHeaderPill(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 12.dp, top = systemBarsTopPadding + 12.dp),
            ) {
                IconButton(
                    onClick = {
                        if (!navController.navigateUp()) {
                            navController.navigate("library") {
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    onLongClick = { navController.backToMain() },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = stringResource(R.string.back_button_desc),
                    )
                }
                Text(
                    text = stringResource(R.string.playlists),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }

        if (layerBackdropActive) {
            LiquidGlassActionPill(
                backdrop = artworkBackdrop,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 12.dp, top = systemBarsTopPadding + 12.dp),
            ) {
                if (sortType == PlaylistSortType.CUSTOM) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.IconButton(
                            onClick = { locked = !locked },
                        ) {
                            Icon(
                                painter = painterResource(if (locked) R.drawable.lock else R.drawable.lock_open),
                                contentDescription = null,
                                tint = liquidGlassContentColor(),
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.IconButton(
                        onClick = { showCreatePlaylistDialog = true },
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.add),
                            contentDescription = stringResource(R.string.create_playlist),
                            tint = liquidGlassContentColor(),
                        )
                    }
                }
            }
        }
    }
}

private fun mergeVisiblePlaylistOrder(
    currentOrder: List<Playlist>,
    visibleOrder: List<Playlist>,
): List<Playlist> {
    if (visibleOrder.isEmpty()) return currentOrder

    val visibleIds = visibleOrder.mapTo(HashSet(visibleOrder.size)) { playlist -> playlist.id }
    val reorderedVisible = visibleOrder.iterator()
    return currentOrder.map { playlist ->
        if (playlist.id in visibleIds) {
            reorderedVisible.next()
        } else {
            playlist
        }
    }
}

private fun openPlaylist(
    navController: NavController,
    playlist: Playlist,
) {
    if (!playlist.playlist.isEditable && playlist.songCount == 0 && playlist.playlist.remoteSongCount != 0) {
        navController.navigate("online_playlist/${playlist.playlist.browseId}")
    } else {
        navController.navigate("local_playlist/${playlist.id}")
    }
}

@Composable
private fun triggerPlaylistMenu(
    playlist: Playlist,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    menuState: moe.rukamori.archivetune.ui.component.MenuState,
) {
    if (playlist.playlist.isEditable || playlist.songCount != 0) {
        PlaylistMenu(
            playlist = playlist,
            coroutineScope = coroutineScope,
            onDismiss = menuState::dismiss,
        )
    } else {
        playlist.playlist.browseId?.let { browseId ->
            YouTubePlaylistMenu(
                playlist =
                    PlaylistItem(
                        id = browseId,
                        title = playlist.playlist.name,
                        author = null,
                        songCountText = null,
                        thumbnail = playlist.thumbnails.getOrNull(0) ?: "",
                        playEndpoint =
                            WatchEndpoint(
                                playlistId = browseId,
                                params = playlist.playlist.playEndpointParams,
                            ),
                        shuffleEndpoint =
                            WatchEndpoint(
                                playlistId = browseId,
                                params = playlist.playlist.shuffleEndpointParams,
                            ),
                        radioEndpoint =
                            WatchEndpoint(
                                playlistId = "RDAMPL$browseId",
                                params = playlist.playlist.radioEndpointParams,
                            ),
                        isEditable = false,
                    ),
                coroutineScope = coroutineScope,
                onDismiss = menuState::dismiss,
            )
        }
    }
}

@Composable
fun rememberArtworkGradient(
    thumbnailUrl: String?,
    fallbackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
): List<Color> {
    val context = LocalContext.current
    var colors by remember(thumbnailUrl) { mutableStateOf(listOf(fallbackColor, fallbackColor.copy(alpha = 0.5f))) }

    LaunchedEffect(thumbnailUrl) {
        if (thumbnailUrl == null) return@LaunchedEffect
        val request =
            ImageRequest
                .Builder(context)
                .data(thumbnailUrl)
                .size(PlayerColorExtractor.Config.IMAGE_SIZE, PlayerColorExtractor.Config.IMAGE_SIZE)
                .allowHardware(false)
                .build()

        val result =
            runCatching {
                context.imageLoader.execute(request)
            }.getOrNull()

        if (result != null) {
            val bitmap = result.image?.toBitmap()
            if (bitmap != null) {
                val palette =
                    withContext(Dispatchers.Default) {
                        Palette
                            .from(bitmap)
                            .maximumColorCount(PlayerColorExtractor.Config.MAX_COLOR_COUNT)
                            .resizeBitmapArea(PlayerColorExtractor.Config.BITMAP_AREA)
                            .generate()
                    }

                val extractedColors =
                    PlayerColorExtractor.extractGradientColors(
                        palette = palette,
                        fallbackColor = fallbackColor.toArgb(),
                    )
                if (extractedColors.size >= 2) {
                    colors = extractedColors
                } else if (extractedColors.isNotEmpty()) {
                    colors = listOf(extractedColors[0], extractedColors[0].copy(alpha = 0.5f))
                }
            }
        }
    }
    return colors
}

@Composable
fun rememberArtworkCardColor(
    thumbnailUrl: String?,
    fallbackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
): Color {
    val gradientColors =
        rememberArtworkGradient(
            thumbnailUrl = thumbnailUrl,
            fallbackColor = fallbackColor,
        )
    val surfaceColor = MaterialTheme.colorScheme.surface
    val useDarkTheme = remember(surfaceColor) { ColorUtils.calculateLuminance(surfaceColor.toArgb()) < 0.5 }
    val pureBlack by rememberPreference(PureBlackKey, defaultValue = false)

    return remember(gradientColors, useDarkTheme, pureBlack) {
        val baseColor = gradientColors.firstOrNull() ?: fallbackColor
        val baseArgb = baseColor.toArgb()
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(baseArgb, hsv)
        val hue = hsv[0]

        if (useDarkTheme) {

            val s = (hsv[1] * 0.45f).coerceIn(0.06f, 0.20f)
            val v = if (pureBlack) 0.18f else 0.12f
            Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, s, v)))
        } else {
            val s = (hsv[1] * 0.30f).coerceIn(0.03f, 0.12f)
            val v = 0.95f
            Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, s, v)))
        }
    }
}

@Composable
fun PlaylistListCard(
    playlist: Playlist,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onMenuClick: () -> Unit,
    showDragHandle: Boolean = false,
    dragHandleModifier: Modifier = Modifier,
) {

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "PlaylistListCardScale",
    )
    val hiddenAlpha = if (playlist.playlist.isHidden) 0.45f else 1f

    val subtitleText =
        if (playlist.songCount == 0 && playlist.playlist.remoteSongCount != null) {
            pluralStringResource(
                R.plurals.n_song,
                playlist.playlist.remoteSongCount,
                playlist.playlist.remoteSongCount,
            )
        } else {
            pluralStringResource(
                R.plurals.n_song,
                playlist.songCount,
                playlist.songCount,
            )
        }

    ListItem(
        title = playlist.playlist.name,
        subtitle = subtitleText,
        thumbnailContent = {
            PlaylistThumbnail(
                thumbnails = playlist.thumbnails,
                size = ListThumbnailSize,
                placeHolder = {
                    val painter =
                        when (playlist.playlist.name) {
                            stringResource(R.string.liked) -> R.drawable.favorite_border
                            stringResource(R.string.offline) -> R.drawable.offline
                            stringResource(R.string.cached_playlist) -> R.drawable.cached
                            else -> R.drawable.queue_music
                        }
                    Icon(
                        painter = painterResource(painter),
                        contentDescription = null,
                        tint = LocalContentColor.current.copy(alpha = 0.8f),
                        modifier = Modifier.size(ListThumbnailSize / 2),
                    )
                },
                shape = RoundedCornerShape(ThumbnailCornerRadius),
            )
        },
        trailingContent = {
            if (showDragHandle) {
                Icon(
                    painter = painterResource(id = R.drawable.drag_handle),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f),
                    modifier =
                        Modifier
                            .size(28.dp)
                            .then(dragHandleModifier),
                )
            }
            Icon(
                painter = painterResource(id = R.drawable.navigate_next),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f),
                modifier = Modifier.size(20.dp),
            )
            if (playlist.playlist.isHidden) {
                Icon(
                    painter = painterResource(id = R.drawable.visibility_off),
                    contentDescription = stringResource(R.string.hide_playlist),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        },
        modifier =
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = hiddenAlpha
                }.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistGridCard(
    playlist: Playlist,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onLongClick: () -> Unit,
) {
    val cardBgColor =
        rememberArtworkCardColor(
            thumbnailUrl = playlist.thumbnails.getOrNull(0),
            fallbackColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "PlaylistGridCardScale",
    )

    val hiddenAlpha = if (playlist.playlist.isHidden) 0.45f else 1f

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = hiddenAlpha
                }.clip(RoundedCornerShape(32.dp))
                .background(cardBgColor)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ).padding(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(26.dp)),
        ) {
            ItemThumbnail(
                thumbnailUrl = playlist.thumbnails.getOrNull(0),
                isActive = false,
                isPlaying = false,
                shape = RoundedCornerShape(26.dp),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.play),
                    contentDescription = stringResource(R.string.play),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }

            if (playlist.playlist.isHidden) {
                Icon(
                    painter = painterResource(id = R.drawable.visibility_off),
                    contentDescription = stringResource(R.string.hide_playlist),
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(16.dp),
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = playlist.playlist.name,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${playlist.songCount} ${stringResource(R.string.tracks_label)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
    }
}
