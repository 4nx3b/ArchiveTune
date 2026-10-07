/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * The maximized-player -> miniplayer artwork flight (2026-10-08: "Port the
 * maximised player transition into miniplayer animation and how the songs
 * thumbnail flies and then goes into the miniplayer and it should apply to
 * all the miniplayer styles"). Adapted from BitChord's PlayerDock flight
 * (https://github.com/kushagrasinghx/BitChord, GPL-3.0) — re-implemented on
 * ArchiveTune's in-window BottomSheet: the sheet's sharedLayer hosts a
 * thumbnail overlay that interpolates between the player artwork's rect and
 * the miniplayer artwork slot while the sheet progress runs 1 -> 0, landing
 * exactly on the miniplayer's cover (which cross-fades in beneath it at the
 * very end). It never intercepts touches.
 */

package moe.rukamori.archivetune.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.math.roundToInt

val LocalPlayerDockArtwork = compositionLocalOf<(Rect?) -> Unit> { {} }

@Composable
fun Modifier.dockArtworkAnchor(): Modifier {
    val reporter = LocalPlayerDockArtwork.current
    DisposableEffect(reporter) {
        onDispose { reporter(null) }
    }
    return this.onGloballyPositioned { coordinates ->
        val size = coordinates.size
        if (size.width > 0 && size.height > 0) {
            val topLeft = coordinates.positionInRoot()
            reporter(
                Rect(
                    left = topLeft.x,
                    top = topLeft.y,
                    right = topLeft.x + size.width,
                    bottom = topLeft.y + size.height,
                ),
            )
        }
    }
}

@Composable
fun BoxScope.PlayerDockingArtwork(
    sheetProgress: Float,
    fullArtworkRect: Rect?,
    miniArtworkRect: Rect?,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
) {
    if (artworkUrl.isNullOrBlank()) return
    val full =
        fullArtworkRect
            ?.takeIf { it.width > 0f && it.height > 0f }
            ?: return
    val mini = miniArtworkRect ?: full
    val p = sheetProgress.coerceIn(0f, 1f)
    if (p <= 0.01f) return

    val density = LocalDensity.current

    val alpha =
        when {
            p >= 0.85f -> ((1f - p) / 0.15f).coerceIn(0f, 1f)
            p >= 0.12f -> 1f
            else -> (p / 0.12f).coerceIn(0f, 1f)
        }
    if (alpha <= 0.01f) return

    val scale = lerp(mini.width / full.width, 1f, p)
    val targetCentreX = lerp(mini.center.x, full.center.x, p)
    val targetCentreY = lerp(mini.center.y, full.center.y, p)

    Box(
        modifier =
            modifier
                .offset { IntOffset(full.left.roundToInt(), full.top.roundToInt()) }
                .size(with(density) { full.width.toDp() })
                .graphicsLayer {
                    this.alpha = alpha
                    scaleX = scale
                    scaleY = scale
                    translationX = targetCentreX - full.center.x
                    translationY = targetCentreY - full.center.y
                    val cornerPx =
                        lerp(
                            with(density) { 8.dp.toPx() },
                            with(density) { 16.dp.toPx() },
                            p,
                        )
                    shape = RoundedCornerShape(cornerPx)
                    clip = true
                    shadowElevation = lerp(0f, 12.dp.toPx(), p)
                },
    ) {
        AsyncImage(
            model = artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction
