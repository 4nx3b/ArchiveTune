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
    // Sort mode for the Spotify list. Mirrors the Playlists page's
    // PlaylistSortType but reduced to the options Spotify's API exposes
    // (no Last Updated, no Custom order — Spotify returns its own ordering
    // we can't reorder). Per user report (2026-08-29): "Also Add a sorting
    // button below the number of playlists in Pink/red accent like history
    // page which lets me sort playlists."
    var sortByRecent by remember { mutableStateOf(true) }     // true = Recently added (default API order)
    var sortByName by remember { mutableStateOf(false) }
    var sortByTrackCount by remember { mutableStateOf(false) }
    var sortDescending by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    // Per user report (2026-08-29): "The search button should search the
    // Playlists available in Spotify. Right now it takes me to the normal
    // song search." Tapping the search icon in the top-end pill now toggles
    // an inline search field above the sort pill; typing a query filters the
    // list by name (case-insensitive substring match) — the list never
    // navigates away from this screen.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showSearchField by rememberSaveable { mutableStateOf(false) }
    // Persisted across sessions via DataStore so hiding a Spotify playlist
    // survives process death AND is surfaced in the account-page
    // "Hidden playlists" section. The repository exposes the set as a
    // StateFlow so the screen re-renders when other screens (e.g.
    // HiddenPlaylistsScreen) unhide a playlist.
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
                        else -> source // Recently added — keep Spotify's default API order
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

    // Stable system-bars top inset so the header pill stays anchored
    // below the status bar even when the bar is transiently hidden.
    // Matches the pattern used in LocalPlaylistScreen. Declared near
    // the top so it can be referenced both by the LazyColumn
    // contentPadding below and by the persistent header pill.
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    // Per user request (2026-08-29): "The liquid glass navigation buttons
    // is not in liquid glass in playlist and Spotify page. Its just
    // frosted. Use the exact same logic from playlist page for liquid
    // glass buttons on the header".
    //
    // The playlist detail page (LocalPlaylistScreen / SpotifyPlaylistScreen)
    // uses `LiquidGlassActionPill(backdrop = artworkBackdrop, interactive =
    // true, ...) { back arrow + title text }` as the persistent top-start
    // header, with `Modifier.layerBackdrop(artworkBackdrop)` applied to the
    // scrolling LazyColumn to record the content the pill samples from.
    // Mirroring that pattern here gives the Spotify Library page the same
    // liquid glass header the user explicitly asked for.
    //
    // This screen is now a separate NavHost route (no longer a child of
    // the Library HorizontalPager), so sampling the screen-local backdrop
    // no longer risks the render-feedback loop documented in
    // FrostedHeaderPill.kt — the backdrop is created and consumed inside
    // the same composition boundary.
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
    val surfaceColor = MaterialTheme.colorScheme.surface
    val artworkBackdrop = rememberBackdrop(surfaceColor)

    // BackHandler so the predictive back gesture always escapes the
    // Spotify Library page. Per user report (2026-08-29). Same pattern
    // as SpotifyPlaylistScreen.kt — popBackStack first, fall back to
    // navigateUp, then navigate("library") so the gesture NEVER
    // silently fails.
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
                // Last-resort: let the system handle the back press
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
                // Per user request (2026-08-29 redesign): "The Playlist
                // Detail page (source of truth) has NO visible divider
                // lines between rows; spacing is clean and relies on
                // whitespace to separate items." The hairline divider
                // block has been removed and horizontal contentPadding
                // is 0 so each shared `ListItem` row's internal 8dp +
                // 8dp Box padding gives the row 16dp horizontal breathing
                // room — exactly matching the Playlist Detail page's
                // song rows.
                contentPadding =
                    PaddingValues(
                        // Per user report (2026-08-29): "Fix empty space in Spotify
                        // page." The previous 150.dp top padding left a large empty
                        // gap between the header pill and the first row. Reduced
                        // to systemBarsTopPadding + 64.dp so the heading block
                        // sits just below the persistent header pill — matching
                        // the LocalPlaylistScreen / LibraryPlaylistsScreen spacing.
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
                        // Per user report (2026-08-29): "Also Add a sorting
                        // button below the number of playlists in Pink/red
                        // accent like history page which lets me sort
                        // playlists." Visual language mirrors the Playlists
                        // page sort pill: pill-shaped with `accent.copy(0.12f)`
                        // background, accent text, accent expand_more icon.
                        // Sort options live in the DropdownMenu wired to
                        // `showSortMenu` (also triggered by the more_vert
                        // icon in the top-end liquid glass pill).
                        // Inline search field — only visible when the user
                        // taps the search icon in the top-end pill. Mirrors the
                        // Playlists page's pill visual language so the
                        // transition between collapsed and expanded search
                        // doesn't shift the sort pill's position.
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
                                    keyboardActions = KeyboardActions(onSearch = { /* no-op; live filter */ }),
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
                                // Hidden playlists toggle — mirrors the Playlists
                                // page's "Hidden playlists" entry (same leadingIcon
                                // + conditional check trailingIcon). Per user report:
                                // "Also i should be able to hide Spotify playlists too."
                                // Per user report (2026-08-29): "Also the hidden
                                // playlist category from sort drop-down in Spotify
                                // page doesn't do anything. Fix it" — now wired to
                                // the persisted `hiddenPlaylistIds` set + has a
                                // visual check indicator like the Playlists page.
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
                            // Per user report (2026-08-29): "There should also be
                            // Overflow menu icon in liquid glass inside Spotify
                            // Playlists. I've attached two images on how it should be
                            // and what functions i should have. You can copy the exact
                            // code for the functions from Normal playlists code." The
                            // per-row 3-dot menu now opens a SpotifyPlaylistMenu
                            // bottom sheet (mirrors PlaylistMenu's structure + actions
                            // adapted for Spotify playlists).
                            menuState.show {
                                SpotifyPlaylistMenu(
                                    playlist = playlist,
                                    coroutineScope = coroutineScope,
                                    onDismiss = menuState::dismiss,
                                    onHide = {
                                        // Persisted via SpotifyLibraryRepository's
                                        // DataStore-backed hidden-playlist-id set so
                                        // the hide survives process death and surfaces
                                        // on the account-page "Hidden playlists" section.
                                        viewModel.toggleHiddenPlaylist(playlist.id)
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }

        // Persistent header pill at top-start. Mirrors the playlist-detail
        // page layout: `LiquidGlassActionPill(backdrop = artworkBackdrop,
        // interactive = true, ...) { back arrow + sub-tab title text }` when
        // liquid glass is active, falling back to `FrostedHeaderPill` (no
        // backdrop) when the master toggle is off or the platform doesn't
        // support the kyant RuntimeShader.
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
                        tint = Color.White,
                    )
                }
                Text(
                    text = stringResource(R.string.spotify),
                    color = Color.White,
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

        // Persistent header pill at top-end. Mirrors the Playlist Detail
        // page (source of truth) layout which has a LiquidGlassActionPill
        // at top-end with Search + More icon buttons. The Spotify Library
        // page's equivalent right-side action is a Refresh button — same
        // behavior as the existing pull-to-refresh, but reachable from
        // the header without scrolling. Per user request (2026-08-29
        // redesign): "The right-side controls should also follow the
        // same visual language as the Playlist Detail page."
        if (layerBackdropActive) {
            LiquidGlassActionPill(
                backdrop = artworkBackdrop,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 12.dp, top = systemBarsTopPadding + 12.dp),
            ) {
                // Per user report (2026-08-29): "Remove the ... overflow
                // liquid glass icon on the top right in Spotify page" — the
                // more_vert overflow icon has been removed. The sort menu is
                // still reachable via the sort pill in the heading block.
                //
                // Per user report (2026-08-29): "The search button should
                // search the Playlists available in Spotify. Right now it
                // takes me to the normal song search." The search icon now
                // toggles an inline search field above the sort pill that
                // filters `visiblePlaylists` by name (case-insensitive
                // substring match). No navigation away from the screen.
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.IconButton(onClick = {
                        showSearchField = !showSearchField
                        if (!showSearchField) searchQuery = ""
                    }) {
                        Icon(
                            painter = painterResource(if (showSearchField) R.drawable.close else R.drawable.search),
                            contentDescription = stringResource(R.string.search),
                            tint = Color.White,
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
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}
