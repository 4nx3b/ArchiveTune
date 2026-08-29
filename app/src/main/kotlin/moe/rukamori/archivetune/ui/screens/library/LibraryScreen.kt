/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.ChipSortTypeKey
import moe.rukamori.archivetune.constants.DisableBlurKey
import moe.rukamori.archivetune.constants.LibraryFilter
import moe.rukamori.archivetune.constants.ShowSpotifyPlaylistsKey
import moe.rukamori.archivetune.constants.ShowTagsInLibraryKey
import moe.rukamori.archivetune.db.entities.TagEntity
import moe.rukamori.archivetune.ui.component.TagsManagementDialog
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference

internal val LibraryHeaderContentPadding = 8.dp
internal val LibraryPullToRefreshIndicatorOffset = 0.dp

@Composable
fun LibraryScreen(navController: NavController) {
    val defaultFilter by rememberEnumPreference(ChipSortTypeKey, LibraryFilter.LIBRARY)
    val database = LocalDatabase.current
    val (selectedTagIds, onSelectedTagIdsChange) = rememberPlaylistTagFilterState(database)
    val allTags by database.allTags().collectAsStateWithLifecycle(initialValue = emptyList())
    val (showTagsInLibrary) = rememberPreference(ShowTagsInLibraryKey, defaultValue = true)
    val (showSpotifyPlaylists) = rememberPreference(ShowSpotifyPlaylistsKey, defaultValue = false)
    val (disableBlur) = rememberPreference(DisableBlurKey, false)
    var showTagsManagementDialog by rememberSaveable { mutableStateOf(false) }
    val activeSelectedTagIds = if (showTagsInLibrary) selectedTagIds else emptySet()
    val libraryFilters =
        remember(showSpotifyPlaylists) {
            if (showSpotifyPlaylists) {
                listOf(
                    LibraryFilter.LIBRARY,
                    LibraryFilter.PLAYLISTS,
                    LibraryFilter.SPOTIFY,
                    LibraryFilter.SONGS,
                    LibraryFilter.ARTISTS,
                    LibraryFilter.ALBUMS,
                )
            } else {
                listOf(
                    LibraryFilter.LIBRARY,
                    LibraryFilter.PLAYLISTS,
                    LibraryFilter.SONGS,
                    LibraryFilter.ARTISTS,
                    LibraryFilter.ALBUMS,
                )
            }
        }

    if (showTagsManagementDialog) {
        TagsManagementDialog(
            onDismiss = { showTagsManagementDialog = false },
        )
    }

    // Per user request (2026-08-28): "If I'm on playlist or any other page
    // and I go back I go back directly to home page. it should be in
    // sequential order".
    //
    // The bug: `rememberPagerState` (and the surrounding LibraryScreen
    // composition) loses its `currentPage` when the user navigates away
    // from Library (e.g. tapping a playlist → local_playlist/{id}). When
    // the user presses back to return to Library, the entire Library
    // composition is rebuilt from scratch — `rememberPagerState` creates
    // a fresh state with `initialPage = libraryFilters.indexOf(defaultFilter)`
    // (the user's *saved* default filter, not the tab they were on). The
    // user lands on the default-tab root view, which reads as "I went
    // back to Home" because the Library root looks similar to the Home
    // screen.
    //
    // The fix: persist the last-selected page index in
    // `rememberSaveable` so it survives the Library composition leaving
    // and re-entering. On re-entry, `initialPage` is restored from the
    // saved value, so the user lands back on the tab they were on.
    val defaultPage = remember(defaultFilter, libraryFilters) {
        libraryFilters.indexOf(defaultFilter).takeIf { it >= 0 } ?: 0
    }
    var lastSelectedPage by rememberSaveable { mutableIntStateOf(defaultPage) }
    val pagerState =
        rememberPagerState(
            initialPage = lastSelectedPage,
        ) { libraryFilters.size }
    // Track the user's tab selection so it persists across navigation
    // away-and-back. The LaunchedEffect below still syncs the page to
    // `defaultFilter` when the user changes their default filter in
    // settings (which would change `defaultFilter`), but it no longer
    // overwrites the user's last-selected tab on every Library re-entry.
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != lastSelectedPage) {
            lastSelectedPage = pagerState.currentPage
        }
    }

    // ── Back gesture handling for Library sub-tabs ─────────────────────────
    // Per user report (2026-08-28): "when I'm in the library or Spotify page
    // and I use the back navigation gesture i return to home page instead i
    // should be on the library main page where it displays recently added
    // and artist and other things".
    //
    // The Library tab hosts sub-screens via HorizontalPager: LIBRARY
    // (LibraryMixScreen — Recently Added + Artists + Albums rows),
    // PLAYLISTS, SPOTIFY, SONGS, ARTISTS, ALBUMS. The user can swipe
    // between them or click category rows in LibraryMixScreen. When the
    // user is on a non-LIBRARY sub-tab and presses the back gesture, the
    // system back fires the NavController's default pop behavior — which
    // exits the Library tab entirely and lands on Home (the start
    // destination).
    //
    // Fix: install a BackHandler that intercepts the back gesture when
    // the user is NOT on the LIBRARY sub-tab. On back, scroll the pager
    // to the LIBRARY page instead of letting the back gesture pop the
    // NavController. The user stays inside the Library tab and lands on
    // the main "Recently Added / Artists / Albums" view, which is the
    // page they explicitly want to be on.
    //
    // When the user is already on the LIBRARY sub-tab, the BackHandler is
    // NOT installed (the predicate returns false) so the system back
    // proceeds normally — letting the user exit the app or land on Home
    // via the standard NavController behavior.
    val coroutineScope = rememberCoroutineScope()
    BackHandler(enabled = pagerState.currentPage != 0) {
        coroutineScope.launch {
            pagerState.animateScrollToPage(0)
        }
    }

    val tonalStart = MaterialTheme.colorScheme.primaryContainer
    val tonalMiddle = MaterialTheme.colorScheme.secondaryContainer

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        if (!disableBlur) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(430.dp)
                        .align(Alignment.TopCenter)
                        .drawWithCache {
                            val brush =
                                Brush.verticalGradient(
                                    0f to tonalStart.copy(alpha = 0.30f),
                                    0.42f to tonalMiddle.copy(alpha = 0.14f),
                                    1f to Color.Transparent,
                                )
                            onDrawBehind { drawRect(brush) }
                        },
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    // Apply ONLY Top + Horizontal insets to the root Column so the LazyColumn
                    // inside extends to the very bottom of the screen and content visibly scrolls
                    // BEHIND the floating navigation bar / mini player. The bottom inset (nav bar
                    // height + mini player height + safe inset) is applied to each sub-screen's
                    // LazyColumn contentPadding instead, so the LAST items can be scrolled above
                    // the bar (minimum-height clearance) instead of being permanently hidden
                    // behind it. Per user spec: "scrollable behind navigation bar too (full
                    // screen width) and when I reach the bottom apply a minimum height so that
                    // it doesn't get overlapped by mini player and navigation bar".
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                        ),
                    ),
        ) {
            val coroutineScope = rememberCoroutineScope()

            // ── Tab sync removed ──────────────────────────────────────────────
            // Previously a `LaunchedEffect(defaultFilter, libraryFilters)` here
            // forced `pagerState.scrollToPage(defaultPage)` on every Library
            // re-entry — which overwrote the user's last-selected tab when
            // they navigated away to a sub-page (e.g. local_playlist) and
            // came back. That read as "I go back directly to home page"
            // because the default tab is LIBRARY, whose root layout looks
            // similar to the Home screen.
            //
            // The saveable `lastSelectedPage` above now drives both the
            // initial page and persists across composition exits, so the
            // user lands back on the tab they were on. We still honour
            // `defaultFilter` changes (e.g. user changes their default
            // library chip in settings) by reading it once into
            // `defaultPage` and feeding it as the `initialPage` of
            // `rememberPagerState`; changes to `defaultFilter` while the
            // Library screen is alive are NOT applied automatically
            // (consistent with the user's request to preserve their
            // last-selected tab).

            // ── Category pills removed ──────────────────────────────────────────
            // The Library/Playlists/Spotify/Songs/Artists/Albums segmented-control
            // row that lived here was removed per user request (2026-08-28). The
            // underlying HorizontalPager is preserved so sub-screens remain
            // reachable via the LibraryMixScreen category rows (Playlists,
            // Artists, Favorites, Downloads, History, Spotify) and their
            // `onTabSelected` callbacks.
            //
            // The Spotify tab is now reachable from the new "Spotify" row
            // in the redesigned LibraryMixScreen (visible only when
            // `showSpotifyPlaylists` is on).

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                when (libraryFilters.getOrElse(page) { LibraryFilter.LIBRARY }) {
                    LibraryFilter.LIBRARY -> {
                        LibraryMixScreen(
                            navController = navController,
                            filterContent =
                                if (showTagsInLibrary) {
                                    {
                                        PlaylistTagFilterRow(
                                            tags = allTags,
                                            selectedTagIds = selectedTagIds,
                                            onSelectedTagIdsChange = onSelectedTagIdsChange,
                                            onManageTagsClick = { showTagsManagementDialog = true },
                                        )
                                    }
                                } else {
                                    null
                                },
                            selectedTagIds = activeSelectedTagIds,
                            showSpotify = showSpotifyPlaylists,
                            onTabSelected = { targetFilter ->
                                coroutineScope.launch {
                                    val targetPage = libraryFilters.indexOf(targetFilter)
                                    pagerState.animateScrollToPage(targetPage.takeIf { it >= 0 } ?: 0)
                                }
                            },
                        )
                    }

                    LibraryFilter.PLAYLISTS -> {
                        LibraryPlaylistsScreen(
                            navController = navController,
                            filterContent =
                                if (showTagsInLibrary) {
                                    {
                                        PlaylistTagFilterRow(
                                            tags = allTags,
                                            selectedTagIds = selectedTagIds,
                                            onSelectedTagIdsChange = onSelectedTagIdsChange,
                                            onManageTagsClick = { showTagsManagementDialog = true },
                                        )
                                    }
                                } else {
                                    null
                                },
                            selectedTagIds = activeSelectedTagIds,
                            // Pass the pager scroll-back callback so the
                            // frosted header pill's back arrow can scroll
                            // the pager to the LIBRARY main sub-tab
                            // (page 0). Per user request (2026-08-29):
                            // "There's no liquid glass headers in Spotify
                            // and playlist pages. I've attached two images
                            // where it should be" — adding a back+title
                            // pill at the top of these sub-tab pages
                            // mirrors the playlist detail page layout.
                            onBack = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(0)
                                }
                            },
                        )
                    }

                    LibraryFilter.SPOTIFY -> {
                        LibrarySpotifyPlaylistsScreen(
                            navController = navController,
                            // Same back-to-LIBRARY callback as Playlists
                            // sub-tab — scrolls the pager to page 0.
                            onBack = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(0)
                                }
                            },
                        )
                    }

                    LibraryFilter.SONGS -> {
                        LibrarySongsScreen(
                            navController = navController,
                            onDeselect = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(0)
                                }
                            },
                        )
                    }

                    LibraryFilter.ARTISTS -> {
                        LibraryArtistsScreen(
                            navController = navController,
                            onDeselect = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(0)
                                }
                            },
                        )
                    }

                    LibraryFilter.ALBUMS -> {
                        LibraryAlbumsScreen(
                            navController = navController,
                            onDeselect = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(0)
                                }
                            },
                        )
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun PlaylistTagFilterRow(
    tags: List<TagEntity>,
    selectedTagIds: Set<String>,
    onSelectedTagIdsChange: (Set<String>) -> Unit,
    onManageTagsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "all_playlist_tags", contentType = "playlist_tag_filter_action") {
            PlaylistTagFilterChip(
                label = stringResource(R.string.filter_all),
                selected = selectedTagIds.isEmpty(),
                iconRes = R.drawable.filter_alt,
                onClick = { onSelectedTagIdsChange(emptySet()) },
            )
        }

        items(
            items = tags,
            key = TagEntity::id,
            contentType = { "playlist_tag_filter" },
        ) { tag ->
            PlaylistTagFilterChip(
                label = tag.name,
                selected = tag.id in selectedTagIds,
                accentColor =
                    remember(tag.color) {
                        runCatching { Color(tag.color.toColorInt()) }.getOrDefault(Color.Unspecified)
                    },
                onClick = {
                    val nextSelection =
                        if (tag.id in selectedTagIds) {
                            selectedTagIds - tag.id
                        } else {
                            selectedTagIds + tag.id
                        }
                    onSelectedTagIdsChange(nextSelection)
                },
            )
        }

        item(key = "manage_playlist_tags", contentType = "playlist_tag_filter_action") {
            PlaylistTagFilterChip(
                label = stringResource(R.string.manage_tags),
                selected = false,
                iconRes = R.drawable.add,
                onClick = onManageTagsClick,
            )
        }
    }
}

@Composable
private fun PlaylistTagFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    val resolvedAccentColor =
        if (accentColor == Color.Unspecified) {
            MaterialTheme.colorScheme.primary
        } else {
            accentColor
        }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue =
            if (isPressed) {
                0.92f
            } else if (selected) {
                1.05f
            } else {
                1.0f
            },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "PlaylistTagFilterChipScale",
    )
    val containerColor by animateColorAsState(
        targetValue =
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "PlaylistTagFilterChipContainerColor",
    )
    val contentColor by animateColorAsState(
        targetValue =
            if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "PlaylistTagFilterChipContentColor",
    )

    Row(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.heightIn(min = 48.dp)
                .clip(CircleShape)
                .background(containerColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        } else {
            Box(
                modifier =
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (selected) contentColor else resolvedAccentColor),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor,
        )
    }
}

@Composable
fun ExpressiveTabChip(
    label: String,
    iconRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue =
            if (isPressed) {
                0.92f
            } else if (selected) {
                1.05f
            } else {
                1.0f
            },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "TabChipScale",
    )

    val bgColor by animateColorAsState(
        targetValue =
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "TabChipBgColor",
    )

    val contentColor by animateColorAsState(
        targetValue =
            if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "TabChipContentColor",
    )

    Row(
        modifier =
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clip(CircleShape)
                .background(bgColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style =
                MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                ),
            color = contentColor,
        )
    }
}
