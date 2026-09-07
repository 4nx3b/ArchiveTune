/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.library

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.spotify.SpotifyLibraryViewModel
import moe.rukamori.archivetune.ui.component.AppleMusicStyleAccentColor
import moe.rukamori.archivetune.ui.component.ExpressivePullToRefreshBox
import moe.rukamori.archivetune.ui.component.FrostedHeaderPill
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.LiquidGlassActionPill
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.SpotifyLikedSongsListItem
import moe.rukamori.archivetune.ui.component.SpotifyLibraryPlaylistListItem
import moe.rukamori.archivetune.ui.component.layerBackdrop
import moe.rukamori.archivetune.ui.component.liquidGlassContentColor
import moe.rukamori.archivetune.ui.component.rememberBackdrop
import moe.rukamori.archivetune.ui.component.rememberLayerBackdropSettled
import moe.rukamori.archivetune.ui.menu.SpotifyPlaylistMenu
import moe.rukamori.archivetune.ui.player.LocalPlayerLyricsFullScreen
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberPreference

@Composable
fun LibrarySpotifyPlaylistsScreen(
    navController: NavController,
    viewModel: SpotifyLibraryViewModel = hiltViewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val menuState = LocalMenuState.current
    val coroutineScope = rememberCoroutineScope()

    var sortByRecent by remember { mutableStateOf(true) }
    var sortByName by remember { mutableStateOf(false) }
    var sortByTrackCount by remember { mutableStateOf(false) }
    var sortDescending by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showSearchField by rememberSaveable { mutableStateOf(false) }

    val hiddenPlaylistIds by viewModel.hiddenPlaylistIds.collectAsStateWithLifecycle()
    val visiblePlaylists =
        remember(playlists, sortByRecent, sortByName, sortByTrackCount, sortDescending, showHidden, hiddenPlaylistIds.size, searchQuery) {
            val hiddenSnapshot = hiddenPlaylistIds
            val query = searchQuery.trim()
            playlists
                .filter { playlist -> showHidden || playlist.id !in hiddenSnapshot }
                .filter { playlist -> query.isBlank() || playlist.name.contains(query, ignoreCase = true) }
                .let { source ->
                    when {
                        sortByName -> if (sortDescending) source.sortedByDescending { it.name.lowercase() } else source.sortedBy { it.name.lowercase() }
                        sortByTrackCount -> if (sortDescending) source.sortedByDescending { it.tracks?.total ?: 0 } else source.sortedBy { it.tracks?.total ?: 0 }
                        else -> source
                    }
                }
        }
    val currentSortLabel = when {
        sortByName -> if (sortDescending) stringResource(R.string.sort_z_to_a) else stringResource(R.string.sort_a_to_z)
        sortByTrackCount -> stringResource(R.string.tracks_count_label)
        else -> stringResource(R.string.recently_added)
    }
    val playerAwareBottomPadding =
        LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Bottom)
            .asPaddingValues()
            .calculateBottomPadding() + 12.dp

    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    val liquidGlassEnabled by rememberPreference(LiquidGlassEnabledKey, defaultValue = false)
    val liquidGlassHeaderActive =
        liquidGlassEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val lyricsFullScreen = LocalPlayerLyricsFullScreen.current

    val screenSettled = rememberLayerBackdropSettled()

    val layerBackdropActive = liquidGlassHeaderActive && !lyricsFullScreen && screenSettled
    val surfaceColor = MaterialTheme.colorScheme.surface
    val artworkBackdrop = rememberBackdrop(surfaceColor)

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

    Box(modifier = Modifier.fillMaxSize()) {
        ExpressivePullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refreshPlaylists,
            modifier = Modifier.fillMaxSize(),
            indicatorOffset = LibraryPullToRefreshIndicatorOffset,
        ) {
            LazyColumn(
                state = rememberLazyListState(),

                contentPadding =
                    PaddingValues(

                        top = systemBarsTopPadding + 64.dp,
                        bottom = playerAwareBottomPadding,
                    ),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (layerBackdropActive) {
                                Modifier.layerBackdrop(artworkBackdrop)
                            } else {
                                Modifier
                            },
                        ),
            ) {
                item(key = "spotify_heading", contentType = "spotify_heading") {
                    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                        Text(
                            text = "LIST",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = AppleMusicStyleAccentColor,
                        )
                        Text(
                            text = stringResource(R.string.spotify),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = pluralStringResource(R.plurals.n_playlist, playlists.size, playlists.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        )

                        if (showSearchField) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
                                        .padding(horizontal = 18.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.search),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                androidx.compose.foundation.text.BasicTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                    cursorBrush = SolidColor(AppleMusicStyleAccentColor),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = {  }),
                                    modifier = Modifier.weight(1f),
                                )
                                if (searchQuery.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    androidx.compose.material3.IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            painter = painterResource(R.drawable.close),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
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
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.recently_added)) },
                                    onClick = {
                                        sortByRecent = true
                                        sortByName = false
                                        sortByTrackCount = false
                                        showSortMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sort_a_to_z)) },
                                    onClick = {
                                        sortByRecent = false
                                        sortByName = true
                                        sortByTrackCount = false
                                        sortDescending = false
                                        showSortMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sort_z_to_a)) },
                                    onClick = {
                                        sortByRecent = false
                                        sortByName = true
                                        sortByTrackCount = false
                                        sortDescending = true
                                        showSortMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.tracks_count_label)) },
                                    onClick = {
                                        sortByRecent = false
                                        sortByName = false
                                        sortByTrackCount = true
                                        sortDescending = false
                                        showSortMenu = false
                                    },
                                )

                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.hidden_playlists)) },
                                    onClick = {
                                        showHidden = !showHidden
                                        showSortMenu = false
                                    },
                                    leadingIcon = { Icon(painter = painterResource(R.drawable.visibility_off), contentDescription = null) },
                                    trailingIcon = {
                                        if (showHidden) {
                                            Icon(painter = painterResource(R.drawable.check), contentDescription = null)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                item(key = "spotify_liked_songs", contentType = "spotify_liked_songs") {
                    SpotifyLikedSongsListItem(navController = navController)
                }

                if (playlists.isEmpty()) {
                    item(key = "spotify_empty", contentType = "spotify_empty") {
                        Text(
                            text = stringResource(R.string.spotify_no_sources),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }

                itemsIndexed(
                    items = visiblePlaylists,
                    key = { _, playlist -> playlist.id },
                    contentType = { _, _ -> "spotify_playlist" },
                ) { _, playlist ->
                    SpotifyLibraryPlaylistListItem(
                        playlist = playlist,
                        navController = navController,
                        onMenuClick = {

                            menuState.show {
                                SpotifyPlaylistMenu(
                                    playlist = playlist,
                                    coroutineScope = coroutineScope,
                                    onDismiss = menuState::dismiss,
                                    onHide = {

                                        viewModel.toggleHiddenPlaylist(playlist.id)
                                    },
                                )
                            }
                        },
                    )
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
                    text = stringResource(R.string.spotify),
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
                    text = stringResource(R.string.spotify),
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

                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.IconButton(onClick = {
                        showSearchField = !showSearchField
                        if (!showSearchField) searchQuery = ""
                    }) {
                        Icon(
                            painter = painterResource(if (showSearchField) R.drawable.close else R.drawable.search),
                            contentDescription = stringResource(R.string.search),
                            tint = liquidGlassContentColor(),
                        )
                    }
                }
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.IconButton(
                        onClick = { viewModel.refreshPlaylists() },
                        enabled = !isRefreshing,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.sync),
                            contentDescription = stringResource(R.string.refresh),
                            tint = liquidGlassContentColor(),
                        )
                    }
                }
            }
        }
    }
}
