/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import moe.rukamori.archivetune.spotify.SpotifyLibraryViewModel
import moe.rukamori.archivetune.ui.component.ExpressivePullToRefreshBox
import moe.rukamori.archivetune.ui.component.FrostedHeaderPill
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.SpotifyLikedSongsListItem
import moe.rukamori.archivetune.ui.component.SpotifyLibraryPlaylistListItem

@Composable
fun LibrarySpotifyPlaylistsScreen(
    navController: NavController,
    viewModel: SpotifyLibraryViewModel = hiltViewModel(),
    // Back-to-LIBRARY-sub-tab callback invoked by the frosted header pill's
    // back arrow. Per user request (2026-08-29): "There's no liquid glass
    // headers in Spotify and playlist pages. I've attached two images
    // where it should be" — the user wants the playlist-detail-page-style
    // frosted header pill (back arrow + sub-tab title) at the top of these
    // sub-tab pages too. The back arrow scrolls the Library pager to page 0
    // (LIBRARY main sub-tab) instead of popping the NavController — the
    // user is already in Library, the pill just takes them back to the
    // Library overview.
    onBack: () -> Unit = {},
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val playerAwareBottomPadding =
        LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Bottom)
            .asPaddingValues()
            .calculateBottomPadding() + 12.dp

    // Stable system-bars top inset so the frosted header pill stays
    // anchored below the status bar even when the bar is transiently
    // hidden. Matches the pattern used in LocalPlaylistScreen.
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

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
                modifier = Modifier.fillMaxSize(),
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

        // Persistent frosted header pill at top-start. Mirrors the
        // playlist-detail-page layout (back arrow + sub-tab title text
        // inside a FrostedHeaderPill). Tapping the back arrow scrolls the
        // Library pager to page 0 (LIBRARY main sub-tab) via [onBack].
        //
        // The pill uses the FrostedHeaderPill fallback path (no backdrop)
        // because the Library pager lives inside the NavHost and sampling
        // the app-wide backdrop from inside it would create a render-
        // feedback loop. The fallback `surfaceContainerHigh.copy(alpha=
        // 0.88)` surface gives the user the visible frosted-glass pill
        // they explicitly asked for.
        FrostedHeaderPill(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = systemBarsTopPadding + 12.dp),
        ) {
            IconButton(
                onClick = onBack,
                onLongClick = {},
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
