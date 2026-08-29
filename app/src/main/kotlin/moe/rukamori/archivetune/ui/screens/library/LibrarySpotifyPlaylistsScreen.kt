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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import moe.rukamori.archivetune.ui.component.ExpressivePullToRefreshBox
import moe.rukamori.archivetune.ui.component.FrostedHeaderPill
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.LiquidGlassActionPill
import moe.rukamori.archivetune.ui.component.SpotifyLikedSongsListItem
import moe.rukamori.archivetune.ui.component.SpotifyLibraryPlaylistListItem
import moe.rukamori.archivetune.ui.component.layerBackdrop
import moe.rukamori.archivetune.ui.component.rememberBackdrop
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
    val layerBackdropActive = liquidGlassHeaderActive && !lyricsFullScreen
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
                contentPadding =
                    PaddingValues(
                        start = 24.dp,
                        top = systemBarsTopPadding + 64.dp,
                        end = 24.dp,
                        bottom = playerAwareBottomPadding,
                    ),
                // Per user request (2026-08-28): "add divider lines like
                // it's on main library page". The Library main page renders
                // a 0.6dp hairline divider between rows with no vertical
                // spacing. Mirroring that here: zero spacing, divider drawn
                // between rows.
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
                    items = playlists,
                    key = { _, playlist -> playlist.id },
                    contentType = { _, _ -> "spotify_playlist" },
                ) { index, playlist ->
                    SpotifyLibraryPlaylistListItem(
                        playlist = playlist,
                        navController = navController,
                    )
                    // Hairline divider between rows, NOT after the last —
                    // matches the Library main page style.
                    if (index < playlists.lastIndex) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 72.dp)
                                    .height(0.6.dp)
                                    .background(
                                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f),
                                    ),
                        )
                    }
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
    }
}
