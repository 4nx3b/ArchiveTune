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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.ArtistFilter
import moe.rukamori.archivetune.constants.ArtistFilterKey
import moe.rukamori.archivetune.constants.ArtistSongSortType
import moe.rukamori.archivetune.constants.ArtistSortDescendingKey
import moe.rukamori.archivetune.constants.ArtistSortType
import moe.rukamori.archivetune.constants.ArtistSortTypeKey
import moe.rukamori.archivetune.constants.ListThumbnailSize
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.constants.YtmSyncKey
import moe.rukamori.archivetune.db.entities.Artist
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.ui.component.AppleMusicStyleAccentColor
import moe.rukamori.archivetune.ui.component.ExpressivePullToRefreshBox
import moe.rukamori.archivetune.ui.component.FrostedHeaderPill
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.ItemThumbnail
import moe.rukamori.archivetune.ui.component.LiquidGlassActionPill
import moe.rukamori.archivetune.ui.component.ListItem
import moe.rukamori.archivetune.ui.component.liquidGlassContentColor
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.layerBackdrop
import moe.rukamori.archivetune.ui.component.rememberBackdrop
import moe.rukamori.archivetune.ui.component.rememberLayerBackdropSettled
import moe.rukamori.archivetune.ui.menu.ArtistMenu
import moe.rukamori.archivetune.ui.player.LocalPlayerLyricsFullScreen
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.LibraryArtistsViewModel

@Composable
fun LibraryArtistsScreen(
    navController: NavController,
    viewModel: LibraryArtistsViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val coroutineScope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current
    val database = LocalDatabase.current

    val (sortType, onSortTypeChange) =
        rememberEnumPreference(
            ArtistSortTypeKey,
            ArtistSortType.CREATE_DATE,
        )
    val (sortDescending, onSortDescendingChange) = rememberPreference(ArtistSortDescendingKey, true)
    val (ytmSync) = rememberPreference(YtmSyncKey, true)

    var filter by rememberEnumPreference(ArtistFilterKey, ArtistFilter.LIKED)

    LaunchedEffect(Unit) {
        if (ytmSync) {
            withContext(Dispatchers.IO) {
                viewModel.sync()
            }
        }
    }

    val artists by viewModel.allArtists.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

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
                        text = stringResource(R.string.artists),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = pluralStringResource(R.plurals.n_artist, artists.size, artists.size),
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
                            ArtistSortType.CREATE_DATE -> {
                                if (sortDescending) stringResource(R.string.newest_first) else stringResource(R.string.oldest_first)
                            }

                            ArtistSortType.NAME -> {
                                if (sortDescending) stringResource(R.string.sort_z_to_a) else stringResource(R.string.sort_a_to_z)
                            }

                            ArtistSortType.SONG_COUNT -> {
                                if (sortDescending) stringResource(R.string.most_tracks) else stringResource(R.string.least_tracks)
                            }

                            ArtistSortType.PLAY_TIME -> {
                                stringResource(R.string.play_time)
                            }
                        }

                    val sortDirectionRotation by animateFloatAsState(
                        targetValue = if (sortDescending) 0f else 180f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
                        label = "ArtistSortDirectionRotation",
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
                                ArtistSortType.entries.forEach { type ->
                                    val label =
                                        when (type) {
                                            ArtistSortType.CREATE_DATE -> stringResource(R.string.recently_added)
                                            ArtistSortType.NAME -> stringResource(R.string.sort_a_to_z)
                                            ArtistSortType.SONG_COUNT -> stringResource(R.string.tracks_count_label)
                                            ArtistSortType.PLAY_TIME -> stringResource(R.string.play_time)
                                        }
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            onSortTypeChange(type)
                                            if (type == ArtistSortType.NAME) onSortDescendingChange(false)
                                            showSortMenu = false
                                        },
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(4.dp))
                        androidx.compose.material3.IconButton(
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

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            Row(
                                modifier =
                                    Modifier
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .clickable {
                                            filter =
                                                if (filter == ArtistFilter.LIKED) {
                                                    ArtistFilter.LIBRARY
                                                } else {
                                                    ArtistFilter.LIKED
                                                }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text =
                                        if (filter == ArtistFilter.LIKED) {
                                            stringResource(R.string.subscribed_only)
                                        } else {
                                            stringResource(R.string.all_artists_filter)
                                        },
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    contentPadding = PaddingValues(bottom = playerAwareBottomPadding),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(
                        items = artists,
                        key = { _, artist -> artist.id },
                        contentType = { _, _ -> "artist_list" },
                    ) { _, artist ->
                        ArtistListCard(
                            artist = artist,
                            onClick = {
                                navController.navigate("artist/${artist.id}")
                            },
                            onPlay = {
                                coroutineScope.launch {
                                    val songs =
                                        database
                                            .artistSongs(
                                                artist.id,
                                                ArtistSongSortType.CREATE_DATE,
                                                true,
                                            ).first()
                                            .map { it.toMediaItem() }
                                    if (songs.isNotEmpty()) {
                                        playerConnection?.playQueue(
                                            ListQueue(
                                                title = artist.artist.name,
                                                items = songs,
                                            ),
                                        )
                                    }
                                }
                            },
                            onMenuClick = {
                                menuState.show {
                                    ArtistMenu(
                                        originalArtist = artist,
                                        coroutineScope = coroutineScope,
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            },
                        )
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
                    text = stringResource(R.string.artists),
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
                    text = stringResource(R.string.artists),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistListCard(
    artist: Artist,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onMenuClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "ArtistListCardScale",
    )

    val subtitleText = pluralStringResource(R.plurals.n_song, artist.songCount, artist.songCount)

    ListItem(
        title = artist.name,
        subtitle = subtitleText,
        thumbnailContent = {
            ItemThumbnail(
                thumbnailUrl = artist.thumbnailUrl,
                isActive = false,
                isPlaying = false,
                shape = CircleShape,
                contentScale = ContentScale.Crop,
                showPlaceholder = true,
                modifier = Modifier.size(ListThumbnailSize),
            )
        },
        trailingContent = {
            androidx.compose.material3.IconButton(
                onClick = onPlay,
                colors =
                    IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.solar_play_linear),
                    contentDescription = stringResource(R.string.play),
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                painter = painterResource(id = R.drawable.navigate_next),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f),
                modifier = Modifier.size(20.dp),
            )
        },
        modifier =
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMenuClick()
                    },
                ),
    )
}
