/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import moe.rukamori.archivetune.db.entities.Album
import moe.rukamori.archivetune.db.entities.Artist
import moe.rukamori.archivetune.db.entities.LocalItem
import moe.rukamori.archivetune.db.entities.Playlist
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.ui.utils.preferredThumbnailRatio
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.constants.CropThumbnailToSquareKey
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.ui.component.ItemThumbnail
import moe.rukamori.archivetune.ui.component.MenuState
import moe.rukamori.archivetune.ui.menu.AlbumMenu
import moe.rukamori.archivetune.ui.menu.ArtistMenu
import moe.rukamori.archivetune.ui.menu.SongMenu
import moe.rukamori.archivetune.ui.menu.YouTubeAlbumMenu
import moe.rukamori.archivetune.ui.menu.YouTubeArtistMenu
import moe.rukamori.archivetune.ui.menu.YouTubePlaylistMenu
import moe.rukamori.archivetune.ui.menu.YouTubeSongMenu
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

val HomeFeedGutter = 10.dp

val HomeShelfCardWidth = 150.dp

val HomeShelfCardCorner = 12.dp

val HomeShelfCardSpacing = 14.dp

val HomeShelfBottomSpacing = 26.dp

private const val HomeHeroCardFraction = 0.70f

private val HomeHeroCardMaxWidth = 320.dp

const val HomeHeroCardRatio = 0.92f

val HomeHeroCardCorner = 18.dp

fun homeHeroCardWidth(available: Dp): Dp = minOf(available * HomeHeroCardFraction, HomeHeroCardMaxWidth)

fun Modifier.homeFeedThumbnailBorder(shape: Shape): Modifier =
    composed {
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        then(
            Modifier.border(
                width = 1.dp,
                color = if (dark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.15f),
                shape = shape,
            ),
        )
    }

val LocalHomeHazeState = compositionLocalOf<HazeState?> { null }

val LocalSearchHazeState = compositionLocalOf<HazeState?> { null }

val LocalLibraryHazeState = compositionLocalOf<HazeState?> { null }

private const val HomeShimmerPeriodMs = 1400

private val HomeBlockShape = RoundedCornerShape(6.dp)
private val HomeLineShape = RoundedCornerShape(4.dp)

private val HomeTitleWidths = listOf(0.68f, 0.46f, 0.58f, 0.74f, 0.52f)
private val HomeSubtitleWidths = listOf(0.34f, 0.44f, 0.27f, 0.38f, 0.31f)

@Composable
fun HomeShimmerBox(modifier: Modifier = Modifier, shape: Shape = HomeBlockShape) {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight =
        MaterialTheme.colorScheme.onSurfaceVariant
            .copy(alpha = 0.16f)
            .compositeOver(base)
    val sweep =
        rememberInfiniteTransition(label = "home_skeleton").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(HomeShimmerPeriodMs, easing = LinearEasing)),
            label = "home_sweep",
        )
    Box(
        modifier
            .clip(shape)
            .drawWithCache {

                val band = size.width * 0.5f
                val startX = -band + sweep.value * (size.width + band * 2)
                val brush =
                    Brush.horizontalGradient(
                        colors = listOf(base, highlight, base),
                        startX = startX,
                        endX = startX + band,
                    )
                onDrawBehind { drawRect(brush) }
            },
    )
}

@Composable
private fun HomeSkeletonLine(fraction: Float, height: Dp, modifier: Modifier = Modifier) {
    HomeShimmerBox(
        modifier = modifier.fillMaxWidth(fraction).height(height),
        shape = HomeLineShape,
    )
}

@Composable
private fun HomeSectionHeaderSkeleton(index: Int = 0) {
    Column(Modifier.padding(horizontal = HomeFeedGutter, vertical = 10.dp)) {
        HomeSkeletonLine(fraction = HomeTitleWidths[index % HomeTitleWidths.size] * 0.7f, height = 18.dp)
    }
}

@Composable
private fun HomeHeroShelfSkeleton() {
    Column(Modifier.padding(bottom = HomeShelfBottomSpacing)) {
        HomeSectionHeaderSkeleton()
        BoxWithConstraints {
            val cardWidth = homeHeroCardWidth(maxWidth)
            LazyRow(
                contentPadding = PaddingValues(horizontal = HomeFeedGutter),
                horizontalArrangement = Arrangement.spacedBy(HomeShelfCardSpacing),
                userScrollEnabled = false,
            ) {
                items(2) {
                    HomeShimmerBox(
                        modifier =
                            Modifier
                                .width(cardWidth)
                                .aspectRatio(HomeHeroCardRatio),
                        shape = RoundedCornerShape(HomeHeroCardCorner),
                    )
                }
            }
        }
    }
}

@Composable
fun HomeShelfSkeleton(index: Int = 0) {
    Column(Modifier.padding(bottom = HomeShelfBottomSpacing)) {
        HomeSectionHeaderSkeleton(index = index)
        LazyRow(
            contentPadding = PaddingValues(horizontal = HomeFeedGutter),
            horizontalArrangement = Arrangement.spacedBy(HomeShelfCardSpacing),
            userScrollEnabled = false,
        ) {
            items(3) { card ->
                Column(Modifier.width(HomeShelfCardWidth)) {
                    HomeShimmerBox(
                        modifier =
                            Modifier
                                .width(HomeShelfCardWidth)
                                .aspectRatio(1f),
                        shape = RoundedCornerShape(HomeShelfCardCorner),
                    )
                    Spacer(Modifier.height(10.dp))
                    HomeSkeletonLine(
                        fraction = HomeTitleWidths[card % HomeTitleWidths.size],
                        height = 13.dp,
                    )
                    Spacer(Modifier.height(6.dp))
                    HomeSkeletonLine(
                        fraction = HomeSubtitleWidths[card % HomeSubtitleWidths.size],
                        height = 11.dp,
                    )
                }
            }
        }
    }
}

fun LazyListScope.homeFeedSkeleton(shelves: Int = 3) {
    item(key = "home_skeleton_hero") { HomeHeroShelfSkeleton() }
    items(shelves - 1, key = { "home_skeleton_shelf_$it" }) { index ->
        HomeShelfSkeleton(index = index + 1)
    }
}

fun LazyListScope.homeFeedMoreSkeleton() {
    item(key = "home_skeleton_more") { HomeShelfSkeleton() }
}

@Composable
fun HomeFeedSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    thumbnail: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .let { m -> if (onClick != null) m.headerClickable(onClick) else m }
                .padding(horizontal = HomeFeedGutter, vertical = 10.dp),
    ) {
        leadingIcon?.invoke()
        thumbnail?.invoke()
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                fontSize = 22.sp,
                fontWeight = FontWeight.W700,
                letterSpacing = (-0.4).sp,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.W600,
                    letterSpacing = (-0.2).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun Modifier.headerClickable(onClick: () -> Unit): Modifier =
    composed {
        clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeFeedShelfCard(
    thumbnailUrl: String?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isActive: Boolean = false,
    isPlaying: Boolean = false,
    isCircular: Boolean = false,
    thumbnailAspectRatio: Float = 1f,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = if (isCircular) CircleShape else RoundedCornerShape(HomeShelfCardCorner)

    val isVideoFrame = !isCircular && kotlin.math.abs(thumbnailAspectRatio - 1f) > 0.001f
    Column(
        modifier =
            modifier
                .width(HomeShelfCardWidth)
                .let { m ->
                    if (onLongClick != null) {
                        m.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    } else {
                        m.combinedClickable(onClick = onClick)
                    }
                },
    ) {
        ItemThumbnail(
            thumbnailUrl = thumbnailUrl,
            isActive = isActive,
            isPlaying = isPlaying,
            shape = shape,
            thumbnailRatio = thumbnailAspectRatio,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(thumbnailAspectRatio)
                    .let { m -> if (!isVideoFrame) m.homeFeedThumbnailBorder(shape) else m },
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.W600,
                letterSpacing = (-0.2).sp,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            trailing?.invoke()
        }
        Text(
            text = subtitle,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeFeedHeroCard(
    thumbnailUrl: String?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isActive: Boolean = false,
    isPlaying: Boolean = false,
) {
    Box(
        modifier =
            modifier
                .aspectRatio(HomeHeroCardRatio)
                .clip(RoundedCornerShape(HomeHeroCardCorner))
                .homeFeedThumbnailBorder(RoundedCornerShape(HomeHeroCardCorner))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .let { m ->
                    if (onLongClick != null) {
                        m.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    } else {
                        m.combinedClickable(onClick = onClick)
                    }
                },
    ) {
        AsyncImage(
            model = thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                        ),
                    )
                    .padding(start = 16.dp, end = 16.dp, top = 34.dp, bottom = 14.dp),
        ) {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.W700,
                letterSpacing = (-0.3).sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (isActive) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        ),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeFeedSongCard(
    song: Song,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    onPlayFromSection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = song.id == mediaMetadata?.id
    HomeFeedShelfCard(
        thumbnailUrl = song.song.thumbnailUrl,
        title = song.song.title,
        subtitle = song.artists.joinToString { it.name },
        isActive = isActive,
        isPlaying = isPlaying,
        onClick = {
            if (isActive) {
                playerConnection.player.togglePlayPause()
            } else {
                onPlayFromSection()
            }
        },
        onLongClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            menuState.show {
                SongMenu(
                    originalSong = song,
                    navController = navController,
                    onDismiss = menuState::dismiss,
                )
            }
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeFeedYTItemCard(
    item: YTItem,
    mediaMetadata: MediaMetadata?,
    navController: NavController,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    onPlaySongFromSection: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
) {
    val subtitle =
        when (item) {
            is SongItem -> item.artists.joinToString { it.name }
            is AlbumItem -> item.artists?.joinToString { it.name } ?: item.year?.toString().orEmpty()
            is ArtistItem -> item.subscriberCountText.orEmpty()
            is PlaylistItem -> item.songCountText.orEmpty()
        }

    val (cropThumbnailToSquare, _) = rememberPreference(CropThumbnailToSquareKey, false)
    val resolvedThumbnailRatio = item.preferredThumbnailRatio(cropThumbnailToSquare)
    HomeFeedShelfCard(
        thumbnailUrl = item.thumbnail,
        title = item.title,
        subtitle = subtitle,
        isCircular = item is ArtistItem,
        isActive = item.id in listOf(mediaMetadata?.album?.id, mediaMetadata?.id),
        isPlaying = isPlaying,
        thumbnailAspectRatio = if (item is ArtistItem) 1f else resolvedThumbnailRatio,
        onClick = {
            when (item) {
                is SongItem -> onPlaySongFromSection(item.id)
                is AlbumItem -> navController.navigate("album/${item.id}")
                is ArtistItem -> navController.navigate("artist/${item.id}")
                is PlaylistItem -> navController.navigate("online_playlist/${item.id}")
            }
        },
        onLongClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            menuState.show {
                when (item) {
                    is SongItem ->
                        YouTubeSongMenu(
                            song = item,
                            navController = navController,
                            onDismiss = menuState::dismiss,
                        )

                    is AlbumItem ->
                        YouTubeAlbumMenu(
                            albumItem = item,
                            navController = navController,
                            onDismiss = menuState::dismiss,
                        )

                    is ArtistItem ->
                        YouTubeArtistMenu(
                            artist = item,
                            onDismiss = menuState::dismiss,
                        )

                    is PlaylistItem ->
                        YouTubePlaylistMenu(
                            playlist = item,
                            coroutineScope = scope,
                            onDismiss = menuState::dismiss,
                        )
                }
            }
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeFeedLocalItemCard(
    item: LocalItem,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    onPlaySongFromSection: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when (item) {
        is Song ->
            HomeFeedSongCard(
                song = item,
                mediaMetadata = mediaMetadata,
                isPlaying = isPlaying,
                navController = navController,
                playerConnection = playerConnection,
                menuState = menuState,
                haptic = haptic,
                onPlayFromSection = { onPlaySongFromSection(item.id) },
                modifier = modifier,
            )

        is Album ->
            HomeFeedShelfCard(
                thumbnailUrl = item.album.thumbnailUrl,
                title = item.album.title,
                subtitle = item.artists.joinToString { it.name },
                isActive = item.id == mediaMetadata?.album?.id,
                isPlaying = isPlaying,
                onClick = { navController.navigate("album/${item.id}") },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuState.show {
                        AlbumMenu(
                            originalAlbum = item,
                            navController = navController,
                            onDismiss = menuState::dismiss,
                        )
                    }
                },
                modifier = modifier,
            )

        is Artist ->
            HomeFeedShelfCard(
                thumbnailUrl = item.artist.thumbnailUrl,
                title = item.artist.name,
                subtitle = "",
                isCircular = true,
                onClick = { navController.navigate("artist/${item.id}") },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuState.show {
                        ArtistMenu(
                            originalArtist = item,
                            coroutineScope = scope,
                            onDismiss = menuState::dismiss,
                        )
                    }
                },
                modifier = modifier,
            )

        is Playlist -> Unit
    }
}

private val HomeRefreshLineHeight = 2.5.dp

@Composable
fun HomePullRefreshLine(
    refreshing: Boolean,
    distanceFraction: () -> Float,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = refreshing || distanceFraction() > 0.01f,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(220)),
        modifier = modifier,
    ) {
        val lineModifier =
            Modifier
                .fillMaxWidth()
                .height(HomeRefreshLineHeight)
        if (refreshing) {
            LinearProgressIndicator(
                modifier = lineModifier,
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt,
                gapSize = 0.dp,
            )
        } else {
            LinearProgressIndicator(
                progress = { distanceFraction().coerceIn(0f, 1f) },
                modifier = lineModifier,
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

private val HomeTopFadeRun = 88.dp

private const val HomeTopFadePeak = 0.75f

private const val HomeTopScrimPeak = 0.42f

private const val HomeTopScrimStops = 12

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun HomeTopFadeBlur(
    hazeState: HazeState,
    pageColor: Color,
    barHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val height = barHeight + HomeTopFadeRun
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .hazeEffect(
                    state = hazeState,

                    style = HazeMaterials.ultraThin(pageColor),
                ) {

                    progressive =
                        HazeProgressive.verticalGradient(
                            easing = EaseOutCubic,
                            startIntensity = HomeTopFadePeak,
                            endIntensity = 0f,
                        )

                    noiseFactor = 0f
                },
    )
    val scrim =
        remember(pageColor) {
            Brush.verticalGradient(

                colorStops =
                    Array(HomeTopScrimStops) { i ->
                        val t = i / (HomeTopScrimStops - 1f)
                        t to pageColor.copy(alpha = HomeTopScrimPeak * (1f - EaseOutCubic.transform(t)))
                    },
            )
        }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .background(scrim),
    )
}

@Composable
fun rememberScreenHeaderHaze(): HazeState = remember { HazeState() }

@Composable
fun ScreenHeaderHaze(
    hazeState: HazeState,
    systemBarsTopPadding: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (!enabled) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    HomeTopFadeBlur(
        hazeState = hazeState,
        pageColor = MaterialTheme.colorScheme.surface,
        barHeight = systemBarsTopPadding + ScreenHeaderHazeBarZone,
        modifier = modifier,
    )
}

private val ScreenHeaderHazeBarZone = 64.dp
