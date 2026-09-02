/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * TikTok player style — one feed page.
 *
 * A page is the full-bleed treatment TikTok gives a post: the artwork's own
 * blurred self as the backdrop under a dark gradient, the sharp artwork as
 * the hero in the middle (tapping it toggles playback, TikTok's
 * tap-to-pause), the action rail riding the right edge over it, the
 * "Full screen" pill under it, and the track's identity pinned along the
 * bottom. Everything is edge-to-edge; legibility comes from the scrim, not
 * from panels.
 */

package moe.rukamori.archivetune.ui.player.tiktok

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.ui.component.BottomSheetPageState
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.LyricsEnhanced
import moe.rukamori.archivetune.ui.component.MenuState
import moe.rukamori.archivetune.ui.utils.resize

/** The inactive gray the reference uses for everything unselected/secondary. */
internal val TIKTOK_INACTIVE_GRAY = Color(0xFFA9A9B2)

/**
 * One page of the feed. Sizing is derived from the page's own constraints, so
 * the layout adapts to any screen ratio without hardcoded coordinates: the
 * artwork is the largest square that fits the middle zone (width-limited on
 * tall screens, height-limited on wide ones), and the rail and info block
 * overlay or stack around it the same way everywhere.
 *
 * The current page can trade its artwork for the Apple Music inline lyrics
 * pane — the same component the Apple Music style morphs to, with the same
 * karaoke sweep and tap-to-seek — while other pages keep their artwork, so
 * swiping always previews the neighbouring cover.
 */
@Composable
internal fun TikTokSongPage(
    pageMetadata: MediaMetadata,
    isCurrentPage: Boolean,
    isPlaying: Boolean,
    playerConnection: PlayerConnection,
    queueTitle: String?,
    immersive: Boolean,
    lyricsOpen: Boolean,
    sliderPositionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    topChromeHeight: Dp,
    bottomChromeHeight: Dp,
    sheetState: BottomSheetState,
    onAddToPlaylist: () -> Unit,
    onToggleLyrics: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onFullscreen: () -> Unit,
    onQueueClick: () -> Unit,
    navController: NavController,
    menuState: MenuState,
    bottomSheetPageState: BottomSheetPageState,
) {
    val haptics = LocalHapticFeedback.current
    // In immersive mode the page keeps only the notch-safe top inset (the
    // chrome is hidden), so the artwork grows but never underlaps the notch
    // even while the status bar is hidden. The stable inset floors against
    // the display cutout, which stays non-zero regardless of bar visibility.
    val stableTopInset = LocalStableSystemBarsTopPadding.current

    // One URL for the page's artwork, pre-sized so the sharp hero decodes at
    // full resolution; the blurred backdrop reuses the same URL at a much
    // smaller decode size (it is about to be smeared anyway). Coil keeps the
    // two requests as separate cache entries, which is exactly what we want —
    // the hero stays crisp, the backdrop stays cheap, and pages returning to
    // the feed hit the cache both ways.
    val artUrl =
        remember(pageMetadata.id, pageMetadata.thumbnailUrl) {
            pageMetadata.thumbnailUrl?.resize(
                width = TIKTOK_ART_PX,
                height = TIKTOK_ART_PX,
                maxresAllowed = true,
            )
        }
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize().background(TIKTOK_EMPTY_BACKDROP)) {
        // ── Backdrop: the artwork's own blurred self + dark gradient ──
        TikTokBackdrop(artUrl = artUrl)

        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(if (immersive) stableTopInset else topChromeHeight))

            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                // The largest square the middle zone can hold. On a tall phone
                // that is the page width; on a landscape or short screen it is
                // the zone height — either way the art never clips the rail or
                // the controls, and never letterboxes.
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    val artSize = minOf(maxWidth, maxHeight)
                    val cornerRadius = if (artSize < maxWidth) 16.dp else 10.dp
                    val artworkScale by animateFloatAsState(
                        targetValue = if (immersive) 1.04f else 1f,
                        animationSpec = tween(250),
                        label = "tiktokArtworkScale",
                    )
                    // The Apple Music inline lyrics pane owns the middle zone
                    // while it is open on the playing page; every other page
                    // (and closing it) shows the artwork.
                    val showInlineLyrics = isCurrentPage && lyricsOpen

                    AnimatedContent(
                        targetState = showInlineLyrics,
                        transitionSpec = {
                            fadeIn(tween(240)) togetherWith fadeOut(tween(240))
                        },
                        contentAlignment = Alignment.Center,
                        label = "tiktokCoverOrLyrics",
                    ) { showLyrics ->
                        if (showLyrics) {
                            TikTokInlineLyricsPane(
                                sliderPositionProvider = sliderPositionProvider,
                                lyricsSyncOffset = lyricsSyncOffset,
                            )
                        } else {
                            Box(
                                modifier =
                                    Modifier
                                        .size(artSize)
                                        .graphicsLayer {
                                            scaleX = artworkScale
                                            scaleY = artworkScale
                                        }.let { m ->
                                            // Tap on the hero = play/pause, TikTok's
                                            // tap-the-video gesture; only the page that
                                            // is actually playing responds.
                                            if (isCurrentPage) {
                                                m.pointerInput(pageMetadata.id) {
                                                    detectTapGestures(
                                                        onTap = {
                                                            haptics.performHapticFeedback(
                                                                HapticFeedbackType.TextHandleMove,
                                                            )
                                                            onTogglePlayPause()
                                                        },
                                                    )
                                                }
                                            } else {
                                                m
                                            }
                                        },
                            ) {
                                AsyncImage(
                                    model =
                                        ImageRequest
                                            .Builder(context)
                                            .data(artUrl)
                                            .size(TIKTOK_ART_PX)
                                            .crossfade(true)
                                            .build(),
                                    contentDescription = pageMetadata.title,
                                    contentScale = ContentScale.Crop,
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .shadow(
                                                elevation = 18.dp,
                                                shape = RoundedCornerShape(cornerRadius),
                                                clip = true,
                                            ),
                                )

                                // Paused affordance (current page only) — TikTok's
                                // translucent play glyph while a video is paused.
                                TikTokPausedOverlay(visible = isCurrentPage && !isPlaying)
                            }
                        }
                    }

                    // ── "Full screen" pill (reference: lower-left of the media) ─
                    // A cover affordance — hidden while the lyrics pane owns the zone.
                    if (!immersive && !showInlineLyrics) {
                        TikTokFullscreenPill(
                            onClick = onFullscreen,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(start = 16.dp, bottom = 12.dp),
                        )
                    }

                    // The action rail rides the right edge of the media zone.
                    if (!immersive) {
                        TikTokRail(
                            pageMetadata = pageMetadata,
                            isCurrentPage = isCurrentPage,
                            playerConnection = playerConnection,
                            sheetState = sheetState,
                            lyricsActive = showInlineLyrics,
                            onToggleLyrics = onToggleLyrics,
                            onAddToPlaylist = onAddToPlaylist,
                            navController = navController,
                            menuState = menuState,
                            bottomSheetPageState = bottomSheetPageState,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }
            }

            // ── Bottom-left: the page's identity ──
            if (!immersive) {
                TikTokSongInfo(
                    pageMetadata = pageMetadata,
                    queueTitle = queueTitle,
                    onQueueClick = onQueueClick,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp),
                )
            }

            Spacer(Modifier.height(if (immersive) 0.dp else bottomChromeHeight))
        }
    }
}

/**
 * The blurred, darkened backdrop. Decoding at a fraction of the hero's size
 * keeps memory flat, and below API 31 — where Modifier.blur is a no-op — the
 * upscale of a small bitmap is itself soft enough to read as a blur once the
 * scrim sits on it.
 */
@Composable
private fun TikTokBackdrop(artUrl: String?) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model =
                ImageRequest
                    .Builder(context)
                    .data(artUrl)
                    .size(TIKTOK_BACKDROP_PX)
                    .crossfade(true)
                    .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .fillMaxSize()
                    .blur(TIKTOK_BACKDROP_BLUR),
        )
        // Solid near-black while the artwork URL is loading or missing, so the
        // page never flashes white or shows the page *behind* it.
        if (artUrl == null) {
            Box(Modifier.fillMaxSize().background(TIKTOK_EMPTY_BACKDROP))
        }
        Box(modifier = Modifier.fillMaxSize().tiktokScrim())
    }
}

/** TikTok's paused-video affordance: a soft scrim with a play glyph. */
@Composable
private fun TikTokPausedOverlay(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(150)),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.solar_play_linear),
                contentDescription = stringResource(R.string.play),
                tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.size(64.dp),
            )
        }
    }
}

/**
 * The Apple Music inline lyrics pane, component and behaviour verbatim: the
 * karaoke view in place of the artwork, following the playing song, with its
 * own tap-a-line-to-seek and per-line sync. The pane rides over the page's
 * blurred backdrop with an extra scrim for legibility; its end padding
 * clears the action rail so no line ever runs underneath it.
 */
@Composable
private fun TikTokInlineLyricsPane(
    sliderPositionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f)),
    ) {
        LyricsEnhanced(
            sliderPositionProvider = sliderPositionProvider,
            lyricsSyncOffset = lyricsSyncOffset,
            textColorOverride = Color.White,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = 4.dp, bottom = 4.dp)
                    // Clear the action rail (48dp buttons + the rail's own
                    // end inset) so the lines never slide under it.
                    .padding(start = 6.dp, end = 62.dp),
        )
    }
}

/**
 * The track identity pinned at the bottom-left of the page: the queue it
 * comes from as a small chip (tap opens the queue sheet), then the big bold
 * title and the "artist • album" secondary line — the reference's username +
 * caption treatment.
 */
@Composable
private fun TikTokSongInfo(
    pageMetadata: MediaMetadata,
    queueTitle: String?,
    onQueueClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (!queueTitle.isNullOrBlank()) {
            // The queue chip carries its own contrast — a dark plate, a hairline
            // light border and a soft shadow — so it stays findable on even the
            // darkest artwork, where a bare translucent gray chip disappears.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .shadow(4.dp, RoundedCornerShape(10.dp))
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.42f))
                        .border(0.75.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
                        .tiktokNoRippleClickable(onClick = onQueueClick)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.solar_music_note_2_linear),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.88f),
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = queueTitle,
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(
            text = pageMetadata.title,
            color = Color.White,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        val artistName = pageMetadata.artists.joinToString(", ") { it.name }
        val secondary =
            if (pageMetadata.album?.title.isNullOrBlank() || artistName.isBlank()) {
                artistName.ifBlank { pageMetadata.album?.title.orEmpty() }
            } else {
                "$artistName • ${pageMetadata.album?.title}"
            }
        if (secondary.isNotBlank()) {
            Text(
                text = secondary,
                color = Color.White.copy(alpha = 0.80f),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The "Full screen" capsule from the reference: a semi-transparent dark
 * rounded pill with an icon and a label, sitting under the main visual. The
 * hairline light border + soft shadow keep it legible over dark artwork —
 * a bare dark pill would vanish against it.
 */
@Composable
private fun TikTokFullscreenPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .shadow(6.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .border(0.75.dp, Color.White.copy(alpha = 0.32f), RoundedCornerShape(14.dp))
                .tiktokNoRippleClickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.solar_fullscreen_linear),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = stringResource(R.string.tiktok_feed_fullscreen),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * TikTok's legibility scrim: dark at both edges (where the header and the
 * info zone live) and clear through the middle (where the artwork carries
 * itself). One static gradient, drawn in the draw phase.
 */
internal fun Modifier.tiktokScrim(): Modifier = drawBehind { drawRect(TIKTOK_SCRIM) }

/** Decode size for the hero artwork, in pixels. */
internal const val TIKTOK_ART_PX = 1080

/** Decode size for the blurred backdrop — a fraction of the hero's. */
internal const val TIKTOK_BACKDROP_PX = 256

internal val TIKTOK_BACKDROP_BLUR: Dp = 48.dp

internal val TIKTOK_EMPTY_BACKDROP = Color(0xFF0B0B0F)

/**
 * The single scrim gradient shared by every page — hoisted to a constant so
 * no page ever allocates a new Brush in composition or per draw.
 */
private val TIKTOK_SCRIM =
    Brush.verticalGradient(
        colorStops =
            arrayOf(
                0.00f to Color.Black.copy(alpha = 0.50f),
                0.22f to Color.Black.copy(alpha = 0.12f),
                0.55f to Color.Black.copy(alpha = 0.18f),
                1.00f to Color.Black.copy(alpha = 0.72f),
            ),
    )
