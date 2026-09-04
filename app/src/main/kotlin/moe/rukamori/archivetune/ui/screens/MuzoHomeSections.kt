/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import kotlinx.coroutines.CoroutineScope
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.playback.queues.YouTubeAlbumRadio
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.ui.component.MenuState
import moe.rukamori.archivetune.ui.menu.YouTubeAlbumMenu
import moe.rukamori.archivetune.ui.menu.YouTubePlaylistMenu

// ============================================================================
// Muzo-style home hero sections (2026-09-04 redesign).
//
// Recreates the reference home design: a dark immersive atmosphere with
// blue/teal/violet glows, a glass "Menu" pill + search/avatar pill header, a
// "Hi, welcome back" hero headline, a layered stacked "Trending Playlist"
// carousel (circular artwork, waveform, circular play button), and a
// "Popular Albums" shelf of tall glass cards with overlay play buttons.
//
// All data is real (the YouTube Music home feed's playlists/albums, the
// account's own playlists as fallback) and every action routes through the
// existing infrastructure: playback via the one PlayerConnection (the same
// YouTubeQueue.playlist / YouTubeAlbumRadio queues the song menus use),
// navigation via the same routes the feed cards use, long-press via the same
// YouTubePlaylistMenu / YouTubeAlbumMenu. The glass surfaces are translucent
// gradient surfaces with hairline borders — no per-card blur passes, so the
// feed keeps the existing liquid-glass budget for the top progressive fade.
// ============================================================================

/** Page gutter the Muzo hero sections align to (reference: 20px margins). */
private val MuzoGutter = 20.dp

/** Corner radius of the big trending-playlist card (reference: ~25px). */
private val MuzoPlaylistCardCorner = 25.dp

/** Corner radius of an album card (reference: ~20px). */
private val MuzoAlbumCardCorner = 20.dp

/** Circular artwork diameter inside the trending card (reference: ~70px). */
private val MuzoPlaylistArtworkSize = 72.dp

/** The circular play button diameter (reference: ~36-40px). */
private val MuzoPlayButtonSize = 44.dp

/** How far each stacked ghost card peeks below the one in front of it. */
private val MuzoStackOffset = 10.dp

/** Width of one album card — matches the feed's compact shelf card. */
private val MuzoAlbumCardWidth = 150.dp

/** Horizontal gap between album cards (reference: ~12-15px). */
private val MuzoAlbumCardSpacing = 14.dp

// ----------------------------------------------------------------------------
// Atmosphere — the deep, softly-lit background the reference floats on.
// ----------------------------------------------------------------------------

/**
 * Full-screen atmospheric backdrop: a near-black (light: surface) base with
 * violet, teal and blue radial glows bleeding through, deepest at the edges
 * and softly lit where content sits. Drawn once per size change into a
 * `drawWithCache` (no blur passes, no per-frame work), so the whole effect
 * costs a single pre-built shader list per layout.
 */
@Composable
fun HomeAtmosphereBackground(
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val base = if (dark) Color(0xFF0D0E12) else MaterialTheme.colorScheme.surface
    // Glow strength: vivid enough to read on the deep dark base, soft enough
    // to stay premium on the light surface.
    val glow = if (dark) 0.17f else 0.12f
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(base)
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val violet = Color(0xFF7B4DFF)
                    val teal = Color(0xFF00B8A9)
                    val blue = Color(0xFF2E6BFF)
                    val topWash =
                        if (dark) {
                            Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = 0.045f), Color.Transparent),
                                startY = 0f,
                                endY = h * 0.22f,
                            )
                        } else {
                            Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = 0.5f), Color.Transparent),
                                startY = 0f,
                                endY = h * 0.16f,
                            )
                        }
                    val violetBrush =
                        Brush.radialGradient(
                            colors = listOf(violet.copy(alpha = glow), Color.Transparent),
                            center = Offset(w * 0.12f, h * 0.10f),
                            radius = w * 0.62f,
                        )
                    val tealBrush =
                        Brush.radialGradient(
                            colors = listOf(teal.copy(alpha = glow * 0.8f), Color.Transparent),
                            center = Offset(w * 0.98f, h * 0.30f),
                            radius = w * 0.55f,
                        )
                    val blueBrush =
                        Brush.radialGradient(
                            colors = listOf(blue.copy(alpha = glow * 0.85f), Color.Transparent),
                            center = Offset(w * 0.18f, h * 0.92f),
                            radius = w * 0.70f,
                        )
                    val bottomShade =
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = if (dark) 0.30f else 0.05f)),
                            startY = h * 0.55f,
                            endY = h,
                        )
                    onDrawBehind {
                        drawRect(violetBrush)
                        drawRect(tealBrush)
                        drawRect(blueBrush)
                        drawRect(bottomShade)
                        drawRect(topWash)
                    }
                },
    )
}

// ----------------------------------------------------------------------------
// Welcome header — the reference's greeting + hero headline.
// ----------------------------------------------------------------------------

@Composable
fun HomeWelcomeHeader(
    accountName: String,
    modifier: Modifier = Modifier,
) {
    val small = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    val headline = MaterialTheme.colorScheme.onSurface
    val displayName = accountName.trim()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = MuzoGutter)
                .padding(top = 10.dp, bottom = 16.dp),
    ) {
        Text(
            text =
                if (displayName.isNotBlank()) {
                    stringResource(R.string.home_welcome_line, displayName)
                } else {
                    stringResource(R.string.home_welcome_line_anonymous)
                },
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = small,
        )
        Spacer(Modifier.height(4.dp))
        // The reference headline: two lines, large, clean, tight leading.
        Text(
            text = stringResource(R.string.home_headline_line1),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            lineHeight = 37.sp,
            color = headline,
        )
        Text(
            text = stringResource(R.string.home_headline_line2),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            lineHeight = 37.sp,
            color = headline,
        )
    }
}

// ----------------------------------------------------------------------------
// Section header — the reference's title + "See all" row.
// ----------------------------------------------------------------------------

@Composable
fun MuzoSectionHeader(
    title: String,
    onSeeAll: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = MuzoGutter)
                .padding(top = 8.dp, bottom = 12.dp),
    ) {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onSeeAll != null) {
            Text(
                text = stringResource(R.string.see_all),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onSeeAll)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

// ----------------------------------------------------------------------------
// Shared glass surface — translucent gradient + hairline border + soft shadow.
// ----------------------------------------------------------------------------

/**
 * The reference's card material: a dark translucent surface with a subtle
 * vertical gradient, a 1dp hairline border and a soft ambient shadow — the
 * same recipe the VLM breakdown of the reference gives (rgba(30,32,40,.6),
 * 1px rgba(255,255,255,.08) border, 0 10 30 shadow). Deliberately NOT a
 * backdrop-blur surface: the home feed already spends the blur budget on the
 * pinned top bar's progressive fade, and dozens of independently blurred
 * cards is exactly what the redesign's performance brief forbids.
 */
private fun Modifier.muzoGlassSurface(
    shape: Shape,
    elevation: Dp = 10.dp,
): Modifier =
    composed {
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        val base = if (dark) Color(0xFF232530) else Color(0xFFECEDF3)
        val topAlpha = if (dark) 0.74f else 0.92f
        val bottomAlpha = if (dark) 0.50f else 0.72f
        val border =
            if (dark) {
                Color.White.copy(alpha = 0.09f)
            } else {
                Color.White.copy(alpha = 0.65f)
            }
        Modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.35f),
                spotColor = Color.Black.copy(alpha = 0.30f),
            ).background(
                brush =
                    Brush.verticalGradient(
                        colors = listOf(base.copy(alpha = topAlpha), base.copy(alpha = bottomAlpha)),
                    ),
                shape = shape,
            ).border(width = 1.dp, color = border, shape = shape)
    }

// ----------------------------------------------------------------------------
// Trending Playlist — the layered stacked carousel.
//
// NOT RENDERED since 2026-09-04: the user asked to "remove the Trending
// playlist section from home page" (the home feed no longer calls this), so
// this block and its private helpers are dormant. Kept compiled (unused
// private composables are a warning, not an error, and detekt is not part
// of the CI gate) so restoring the section is a one-line change in
// HomeScreen.kt.
// ----------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrendingPlaylistSection(
    playlists: List<PlaylistItem>,
    seeAllRoute: String?,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    if (playlists.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        MuzoSectionHeader(
            title = stringResource(R.string.home_trending_playlists),
            onSeeAll = seeAllRoute?.let { route -> { navController.navigate(route) } },
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val pageWidth = maxWidth - MuzoGutter * 2f
            val cardHeight = muzoPlaylistCardHeight(hasNowPlaying = mediaMetadata != null)
            val pagerState = rememberPagerState(initialPage = 0) { playlists.size }
            HorizontalPager(
                state = pagerState,
                pageSize = PageSize.Fixed(pageWidth),
                pageSpacing = 16.dp,
                contentPadding = PaddingValues(horizontal = MuzoGutter),
                modifier = Modifier.height(cardHeight + MuzoStackOffset * 2f),
            ) { page ->
                // The real playlist collection supplies the stacked layers:
                // the next two playlists peek out below the front card, the
                // reference's depth cue — never decorative placeholders.
                val ghost1 = playlists.getOrNull(page + 1) ?: playlists.getOrNull(0)
                val ghost2 = playlists.getOrNull(page + 2) ?: playlists.getOrNull(1)
                val primary = playlists[page]
                Box(modifier = Modifier.fillMaxSize()) {
                    if (ghost2 != null && ghost2.id != primary.id && playlists.size > 2) {
                        TrendingGhostCard(
                            playlist = ghost2,
                            cardWidth = pageWidth,
                            cardHeight = cardHeight,
                            offset = MuzoStackOffset * 2f,
                            alpha = 0.35f,
                        )
                    }
                    if (ghost1 != null && ghost1.id != primary.id && playlists.size > 1) {
                        TrendingGhostCard(
                            playlist = ghost1,
                            cardWidth = pageWidth,
                            cardHeight = cardHeight,
                            offset = MuzoStackOffset,
                            alpha = 0.55f,
                        )
                    }
                    TrendingPlaylistCard(
                        playlist = primary,
                        cardWidth = pageWidth,
                        cardHeight = cardHeight,
                        nowPlayingTitle = mediaMetadata?.title,
                        nowPlayingDuration = mediaMetadata?.duration,
                        isPlaying = isPlaying,
                        onPlay = {
                            primary.playEndpoint?.let { endpoint ->
                                playerConnection.playQueue(YouTubeQueue.playlist(endpoint))
                            }
                        },
                        onClick = { navController.navigate("online_playlist/${primary.id}") },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuState.show {
                                YouTubePlaylistMenu(
                                    playlist = primary,
                                    coroutineScope = scope,
                                    onDismiss = menuState::dismiss,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/** The card's height: fixed by its content so the ghosts can be laid out identically. */
private fun muzoPlaylistCardHeight(hasNowPlaying: Boolean): Dp {
    val padding = 18.dp
    val headerRow = MuzoPlaylistArtworkSize
    val gapAfterHeader = 16.dp
    val waveRow = MuzoPlayButtonSize
    val footer = if (hasNowPlaying) 22.dp + 6.dp else 0.dp
    return padding * 2f + headerRow + gapAfterHeader + waveRow + footer
}

/** A simplified stacked layer: the same glass treatment + circular artwork, nothing else. */
@Composable
private fun TrendingGhostCard(
    playlist: PlaylistItem,
    cardWidth: Dp,
    cardHeight: Dp,
    offset: Dp,
    alpha: Float,
) {
    Box(
        modifier =
            Modifier
                .width(cardWidth)
                .height(cardHeight)
                .offset(y = offset)
                .alpha(alpha)
                .muzoGlassSurface(shape = RoundedCornerShape(MuzoPlaylistCardCorner)),
    ) {
        MuzoArtwork(
            url = playlist.thumbnail,
            size = MuzoPlaylistArtworkSize,
            shape = CircleShape,
            contentDescription = null,
            modifier = Modifier.padding(18.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrendingPlaylistCard(
    playlist: PlaylistItem,
    cardWidth: Dp,
    cardHeight: Dp,
    nowPlayingTitle: String?,
    nowPlayingDuration: Int?,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // Premium press feedback (the reference's card press): a 2% scale dip on
    // press, driven by the draw-phase graphicsLayer so no layout invalidation.
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "muzoTrendingPress",
    )
    Box(
        modifier =
            Modifier
                .width(cardWidth)
                .height(cardHeight)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .muzoGlassSurface(shape = RoundedCornerShape(MuzoPlaylistCardCorner), elevation = 14.dp)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(18.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MuzoArtwork(
                    url = playlist.thumbnail,
                    size = MuzoPlaylistArtworkSize,
                    shape = CircleShape,
                    contentDescription = playlist.title,
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playlist.title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    playlist.author?.name?.let { author ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = author,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    playlist.songCountText?.let { count ->
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = count,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                MuzoWaveform(
                    seed = playlist.id.hashCode(),
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(28.dp)
                            .padding(end = 14.dp),
                )
                // The reference's card always carries the play glyph: the
                // running queue can't be reliably attributed back to a feed
                // playlist, so a pause glyph here would misrepresent state.
                MuzoPlayButton(
                    isPlaying = false,
                    onPlay = onPlay,
                )
            }
            if (nowPlayingTitle != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = nowPlayingTitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatMuzoDuration(nowPlayingDuration),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

/**
 * The reference's circular play button: a light circle, dark play glyph and a
 * soft glow shadow, turning into pause when this queue is already playing.
 * Playback routes through the app's one PlayerConnection queue API.
 */
@Composable
private fun MuzoPlayButton(
    isPlaying: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val circle =
        if (dark) {
            Color(0xFFF4F5F8)
        } else {
            Color(0xFFFFFFFF)
        }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(MuzoPlayButtonSize)
                .shadow(
                    elevation = 8.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.4f),
                    spotColor = Color.Black.copy(alpha = 0.35f),
                ).background(circle, CircleShape)
                .clip(CircleShape)
                .clickable(onClick = onPlay),
    ) {
        Icon(
            painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
            contentDescription = stringResource(R.string.play),
            tint = Color(0xFF15161A),
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * The card's waveform strip. The app has no playback visualizer, so this is
 * the reference's design element — a deterministic bar pattern seeded per
 * playlist, drawn in one Canvas pass. It does not simulate playback state.
 */
@Composable
private fun MuzoWaveform(
    seed: Int,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val barColor =
        if (dark) {
            Color(0xFF6B6D78)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        }
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val bars = 34
        val gap = 2.dp.toPx()
        val barWidth = (size.width - gap * (bars - 1)) / bars
        // Deterministic pseudo-random heights from the seed: same playlist,
        // same shape, every time — no state, no recomposition churn.
        var s = if (seed == 0) 1 else seed
        val rng = { s = s * 1_103_515_245 + 12_345; ((s ushr 16) and 0xFFFF).toFloat() / 65_535f }
        val heights = FloatArray(bars) { 0.22f + rng() * 0.78f }
        for (i in 0 until bars) {
            val h = heights[i] * size.height
            val x = i * (barWidth + gap)
            val y = (size.height - h) / 2f
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
            )
        }
    }
}

// ----------------------------------------------------------------------------
// Popular Albums — the horizontal glass card shelf.
// ----------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PopularAlbumsSection(
    albums: List<AlbumItem>,
    seeAllRoute: String?,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    modifier: Modifier = Modifier,
) {
    if (albums.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        MuzoSectionHeader(
            title = stringResource(R.string.home_popular_albums),
            onSeeAll = seeAllRoute?.let { route -> { navController.navigate(route) } },
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = MuzoGutter),
            horizontalArrangement = Arrangement.spacedBy(MuzoAlbumCardSpacing),
        ) {
            items(
                items = albums,
                key = { it.id },
                contentType = { "muzo_album" },
            ) { album ->
                MuzoAlbumCard(
                    album = album,
                    // Pause glyph only while this very album is the playing queue.
                    isPlaying = isPlaying && album.id == mediaMetadata?.album?.id,
                    onPlay = { playerConnection.playQueue(YouTubeAlbumRadio(album.playlistId)) },
                    onClick = { navController.navigate("album/${album.id}") },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuState.show {
                            YouTubeAlbumMenu(
                                albumItem = album,
                                navController = navController,
                                onDismiss = menuState::dismiss,
                            )
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MuzoAlbumCard(
    album: AlbumItem,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // Same press feedback as the trending card: a subtle scale dip.
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "muzoAlbumPress",
    )
    Column(
        modifier =
            Modifier
                .width(MuzoAlbumCardWidth)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .muzoGlassSurface(shape = RoundedCornerShape(MuzoAlbumCardCorner), elevation = 10.dp)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp)),
        ) {
            MuzoArtwork(
                url = album.thumbnail,
                size = null,
                shape = RoundedCornerShape(14.dp),
                contentDescription = album.title,
                modifier = Modifier.fillMaxSize(),
            )
            // Circular play button centered over the artwork (reference).
            MuzoPlayButton(
                isPlaying = isPlaying,
                onPlay = onPlay,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = album.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = album.artists?.joinToString { it.name }.orEmpty(),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ----------------------------------------------------------------------------
// Artwork + helpers.
// ----------------------------------------------------------------------------

/**
 * Artwork loader reusing the feed's sizing/cache recipe: an ImageRequest
 * pinned to the displayed size with disk + memory caching and a crossfade,
 * so artwork loads smoothly, decodes once and avoids re-decoding on scroll.
 */
@Composable
private fun MuzoArtwork(
    url: String?,
    size: Dp?,
    shape: Shape,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    if (url == null) {
        Box(
            modifier =
                modifier.then(
                    if (size != null) Modifier.size(size) else Modifier,
                ).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape),
        )
        return
    }
    val density = LocalDensity.current
    val px =
        remember(url, size, density) {
            val target = size ?: Dp.Unspecified
            if (target != Dp.Unspecified) {
                with(density) { target.roundToPx().coerceAtLeast(1) }
            } else {
                0
            }
        }
    val request =
        remember(url, px) {
            ImageRequest
                .Builder(context)
                .data(url)
                .apply {
                    if (px > 0) size(Size(px, px))
                    diskCachePolicy(CachePolicy.ENABLED)
                    memoryCachePolicy(CachePolicy.ENABLED)
                    crossfade(true)
                }.build()
        }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier =
            modifier.then(
                if (size != null) Modifier.size(size) else Modifier,
            ),
    )
}

private fun formatMuzoDuration(durationSeconds: Int?): String {
    if (durationSeconds == null || durationSeconds <= 0) return "--:--"
    val m = durationSeconds / 60
    val s = durationSeconds % 60
    return "%02d:%02d".format(m, s)
}
