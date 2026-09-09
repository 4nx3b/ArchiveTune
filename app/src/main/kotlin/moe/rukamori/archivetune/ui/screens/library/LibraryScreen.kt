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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import dev.chrisbanes.haze.hazeSource
import moe.rukamori.archivetune.ui.screens.HomeAtmosphereBackground
import moe.rukamori.archivetune.ui.screens.LocalLibraryHazeState
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

            listOf(
                LibraryFilter.LIBRARY,
                LibraryFilter.SONGS,
                LibraryFilter.ALBUMS,
            )
        }

    if (showTagsManagementDialog) {
        TagsManagementDialog(
            onDismiss = { showTagsManagementDialog = false },
        )
    }

    val defaultPage = remember(defaultFilter, libraryFilters) {
        libraryFilters.indexOf(defaultFilter).takeIf { it >= 0 } ?: 0
    }
    var lastSelectedPage by rememberSaveable { mutableIntStateOf(defaultPage) }
    val pagerState =
        rememberPagerState(
            initialPage = lastSelectedPage,
        ) { libraryFilters.size }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != lastSelectedPage) {
            lastSelectedPage = pagerState.currentPage
        }
    }

    val coroutineScope = rememberCoroutineScope()
    BackHandler(enabled = pagerState.currentPage != 0) {
        coroutineScope.launch {
            pagerState.animateScrollToPage(0)
        }
    }

    val libraryHazeState = LocalLibraryHazeState.current
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .let { m -> if (libraryHazeState != null) m.hazeSource(libraryHazeState) else m }
                .background(MaterialTheme.colorScheme.background),
    ) {
        if (!disableBlur) {
            HomeAtmosphereBackground()
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()

                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(
                            WindowInsetsSides.Horizontal,
                        ),
                    ),
        ) {
            val coroutineScope = rememberCoroutineScope()

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

                    else -> {
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
                }
                }
            }
        }
    }
}

@Composable
internal fun PlaylistTagFilterRow(
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
