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
 * the hero in the middle, the action rail riding the right edge over it, and
 * the track's identity + transport pinned along the bottom. Everything is
 * edge-to-edge; legibility comes from the scrim, not from panels.
 */

package moe.rukamori.archivetune.ui.player.tiktok

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.ui.component.BottomSheetPageState
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.MenuState
import moe.rukamori.archivetune.ui.utils.resize

/**
 * One page of the feed. Sizing is derived from the page's own constraints, so
 * the layout adapts to any screen ratio without hardcoded coordinates: the
 * artwork is the largest square that fits the middle zone (width-limited on
 * tall screens, height-limited on wide ones), and the rail and bottom zone
 * overlay it the same way everywhere.
 */
@Composable
internal fun TikTokSongPage(
    pageMetadata: MediaMetadata,
    isCurrentPage: Boolean,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    playerConnection: PlayerConnection,
    sheetState: BottomSheetState,
    onOpenLyrics: () -> Unit,
    onAddToPlaylist: () -> Unit,
    navController: NavController,
    menuState: MenuState,
    bottomSheetPageState: BottomSheetPageState,
) {
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

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Backdrop: the artwork's own blurred self + dark gradient ──
        TikTokBackdrop(artUrl = artUrl)

        // ── Content: hero artwork, rail, bottom info ──
        Column(modifier = Modifier.fillMaxSize()) {
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
                                .size(artSize)
                                .shadow(
                                    elevation = 18.dp,
                                    shape = RoundedCornerShape(cornerRadius),
                                    clip = true,
                                ),
                    )
                }

                // The action rail rides the right edge of the media zone.
                TikTokRail(
                    pageMetadata = pageMetadata,
                    isCurrentPage = isCurrentPage,
                    playerConnection = playerConnection,
                    sheetState = sheetState,
                    onOpenLyrics = onOpenLyrics,
                    onAddToPlaylist = onAddToPlaylist,
                    navController = navController,
                    menuState = menuState,
                    bottomSheetPageState = bottomSheetPageState,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }

            // ── Bottom: identity, meta, progress, transport ──
            TikTokBottomZone(
                pageMetadata = pageMetadata,
                isCurrentPage = isCurrentPage,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                playerConnection = playerConnection,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 10.dp),
            )
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

/**
 * TikTok's legibility scrim: dark at both edges (where the header and the
 * info zone live) and clear through the middle (where the artwork carries
 * itself). One static gradient, drawn in the draw phase.
 */
internal fun Modifier.tiktokScrim(): Modifier =
    drawBehind { drawRect(TIKTOK_SCRIM) }

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
