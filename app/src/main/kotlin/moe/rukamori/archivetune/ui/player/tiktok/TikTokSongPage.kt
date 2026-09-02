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
 * tap-to-pause; its edges dissolve into the backdrop so the two renderings
 * of the same image never meet in a hard line), the action rail riding the
 * right edge over it, the fullscreen affordance under it, and the track's
 * identity pinned along the bottom. Everything is edge-to-edge; legibility
 * comes from the scrim, not from panels.
 */

package moe.rukamori.archivetune.ui.player.tiktok

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
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
    onOpenLyricsMenu: () -> Unit,
    onMoreIconPositioned: (Rect) -> Unit,
    navController: NavController,
    menuState: MenuState,
    bottomSheetPageState: BottomSheetPageState,
) {
    val haptics = LocalHapticFeedback.current
    // The page's one like action — shared by the rail's heart (a plain
    // toggle) and the artwork's double-tap (like-only, TikTok's rule that
    // a double-tap never unlikes). Same Room row + sync path the song
    // menu uses; see rememberTikTokLikeAction in TikTokRail.kt.
    val likeAction =
        rememberTikTokLikeAction(
            pageMetadata = pageMetadata,
            isCurrentPage = isCurrentPage,
            playerConnection = playerConnection,
        )
    // Live hearts for double-taps: each burst removes itself when its own
    // animation finishes, so rapid double-taps stack like the reference's.
    val heartBursts = remember { mutableStateListOf<TikTokHeartBurst>() }
    var nextHeartBurstId by remember { mutableStateOf(0L) }
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
                                        }
                                        // Blend the artwork's top and bottom edges into
                                        // the blurred backdrop behind it. The sharp art
                                        // and the backdrop are the same image, so an
                                        // alpha ramp at each edge reads as focus falling
                                        // off — without it the two renderings meet in a
                                        // hard straight line (user report 2026-09-02:
                                        // "straight lines / color inconsistency").
                                        .graphicsLayer {
                                            compositingStrategy =
                                                CompositingStrategy.Offscreen
                                        }
                                        .drawWithContent {
                                            drawContent()
                                            drawRect(
                                                brush = TIKTOK_ART_EDGE_FADE,
                                                blendMode = BlendMode.DstIn,
                                            )
                                        }.let { m ->
                                            // Tap on the hero = play/pause, TikTok's
                                            // tap-the-video gesture; a double-tap
                                            // anywhere on it likes the song — the
                                            // detector disambiguates the two the
                                            // platform way (the single tap waits
                                            // out the double-tap timeout first).
                                            // Only the page that is actually
                                            // playing responds.
                                            if (isCurrentPage) {
                                                m.pointerInput(pageMetadata.id) {
                                                    detectTapGestures(
                                                        onTap = {
                                                            haptics.performHapticFeedback(
                                                                HapticFeedbackType.TextHandleMove,
                                                            )
                                                            onTogglePlayPause()
                                                        },
                                                        onDoubleTap = { tap ->
                                                            haptics.performHapticFeedback(
                                                                HapticFeedbackType.LongPress,
                                                            )
                                                            likeAction(true)
                                                            heartBursts +=
                                                                TikTokHeartBurst(
                                                                    id = nextHeartBurstId++,
                                                                    x = tap.x.toDp(),
                                                                    y = tap.y.toDp(),
                                                                )
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

                                // Double-tap hearts, spawned at the tap point.
                                heartBursts.forEach { burst ->
                                    key(burst.id) {
                                        TikTokHeartBurstView(
                                            burst = burst,
                                            onFinished = {
                                                heartBursts.removeAll { it.id == burst.id }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── Right-edge legibility wash ──
                    // The soft dark wash the reference keeps behind its action
                    // rail: over light artwork the rail's white glyphs would
                    // otherwise wash out. It fades to clear well inside the
                    // media so it reads as part of the cover, never as a panel —
                    // and it fades to clear at its own top and bottom too, so it
                    // never starts or ends in a straight line (the wash used to
                    // span the full zone height, drawing two hard horizontal
                    // edges behind the top tabs and above the song info).
                    // Hidden while the lyrics pane owns the zone: a dark wash
                    // over the karaoke text is exactly the "black layer over the
                    // lyrics" the user reported, and the glyphs' own drop
                    // shadows carry their legibility without it.
                    if (!immersive && !showInlineLyrics) {
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .width(TIKTOK_RAIL_WASH_WIDTH)
                                    .graphicsLayer {
                                        compositingStrategy =
                                            CompositingStrategy.Offscreen
                                    }
                                    .drawWithContent {
                                        drawRect(brush = TIKTOK_RAIL_WASH)
                                        drawRect(
                                            brush = TIKTOK_RAIL_WASH_VERTICAL_FADE,
                                            blendMode = BlendMode.DstIn,
                                        )
                                    },
                        )
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
                            onOpenLyricsMenu = onOpenLyricsMenu,
                            onMoreIconPositioned = onMoreIconPositioned,
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
 * own tap-a-line-to-seek and per-line sync. The pane rides directly on the
 * page's blurred backdrop — no background of its own, the Apple Music
 * treatment: the artwork's colour reads through the lyrics, darkened only by
 * the page gradient that already covers the top and bottom edges. (A previous
 * revision added a scrim here; that was the "black layer over the lyrics" the
 * user reported twice, so the pane now owns no background at all.) Its end
 * padding clears the action rail so no line ever runs underneath it.
 */
@Composable
private fun TikTokInlineLyricsPane(
    sliderPositionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier.fillMaxSize(),
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.14f))
                        .tiktokNoRippleClickable(onClick = onQueueClick)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.solar_music_note_2_linear),
                    contentDescription = null,
                    tint = TIKTOK_INACTIVE_GRAY,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = queueTitle,
                    color = TIKTOK_INACTIVE_GRAY,
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
                color = TIKTOK_INACTIVE_GRAY,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The fullscreen affordance from the reference, lower-left of the media: a
 * compact icon-only pill. The label was removed per user request (2026-09-02:
 * "remove the full screen text on the bottom") — the glyph plus its content
 * description carry the action, and the top navigation's fullscreen toggle
 * remains the labelled path.
 */
@Composable
private fun TikTokFullscreenPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .tiktokNoRippleClickable(onClick = onClick)
                .padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.solar_fullscreen_linear),
            contentDescription = stringResource(R.string.tiktok_feed_fullscreen),
            tint = Color.White,
            modifier = Modifier.size(15.dp),
        )
    }
}

/** One live double-tap heart, at the tap point (artwork-box coordinates). */
private data class TikTokHeartBurst(
    val id: Long,
    val x: Dp,
    val y: Dp,
)

/**
 * TikTok's double-tap heart: a big red heart pops in at the tap point with a
 * spring overshoot, then drifts up and fades out. The rotation is derived
 * from the burst id so rapid double-taps never stack perfectly aligned, and
 * the whole view removes itself from the burst list once its animation
 * finishes. The like it celebrates lands through the same Room row the
 * rail's heart uses, so the rail button pops in sync.
 */
@Composable
private fun TikTokHeartBurstView(
    burst: TikTokHeartBurst,
    onFinished: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(burst.id) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(TIKTOK_HEART_BURST_MS, easing = LinearOutSlowInEasing),
        )
        onFinished()
    }
    val p = progress.value
    // Pop in fast with overshoot, settle, hold; rise and fade through the
    // second half — the reference's double-tap beat, in one progress value.
    val scale = when {
        p < 0.22f -> 0.2f + (p / 0.22f) * 1.15f
        p < 0.38f -> 1.35f - ((p - 0.22f) / 0.16f) * 0.35f
        else -> 1f
    }
    val alpha = if (p < 0.55f) 1f else 1f - ((p - 0.55f) / 0.45f)
    val rise = 160.dp * (p * p)
    val rotation = ((burst.id % 5) - 2) * 6f
    Icon(
        painter = painterResource(R.drawable.solar_heart_bold),
        contentDescription = null,
        tint = TIKTOK_RED,
        modifier =
            Modifier
                .offset(
                    x = burst.x - TIKTOK_HEART_BURST_SIZE / 2f,
                    y = burst.y - TIKTOK_HEART_BURST_SIZE / 2f - rise,
                ).size(TIKTOK_HEART_BURST_SIZE)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha.coerceIn(0f, 1f)
                    rotationZ = rotation
                },
    )
}

/** Size of the double-tap heart glyph. */
private val TIKTOK_HEART_BURST_SIZE = 88.dp

/** How long a double-tap heart lives, start to fade-out end. */
private const val TIKTOK_HEART_BURST_MS = 650

/** Width of the rail's right-edge legibility wash. */
private val TIKTOK_RAIL_WASH_WIDTH = 120.dp

/**
 * The soft right-edge wash behind the action rail — clear at its left edge
 * so it blends into the cover: plain black fading in toward the rail, which
 * is what keeps the white glyphs legible over light artwork without
 * touching the rest of the media.
 */
private val TIKTOK_RAIL_WASH =
    Brush.horizontalGradient(
        listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
    )

/**
 * The wash's vertical mask: fully present through the middle (where the
 * rail's buttons live), fading to clear over the top and bottom fifths so
 * the wash never begins or ends in a visible straight line. Applied with
 * BlendMode.DstIn inside an offscreen layer, in the same place the wash is
 * drawn.
 */
private val TIKTOK_RAIL_WASH_VERTICAL_FADE =
    Brush.verticalGradient(
        0.00f to Color.Transparent,
        0.20f to Color.Black,
        0.80f to Color.Black,
        1.00f to Color.Transparent,
    )

/**
 * The artwork's edge blend, applied with BlendMode.DstIn in an offscreen
 * layer: opaque through the art, clear at the very top and bottom so the
 * sharp rendering dissolves into the blurred backdrop of the same image
 * instead of meeting it in a hard straight edge. The fade band is ~6% of
 * the artwork's height per edge — enough to hide the seam, little enough
 * that the card still reads as a card.
 */
private val TIKTOK_ART_EDGE_FADE =
    Brush.verticalGradient(
        0.00f to Color.Transparent,
        0.06f to Color.Black,
        0.94f to Color.Black,
        1.00f to Color.Transparent,
    )

/** TikTok's legibility scrim: dark at both edges (where the header and the
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
