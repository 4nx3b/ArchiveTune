/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * The maximized-player <-> miniplayer artwork flight. One continuous
 * thumbnail travels between the expanded player's artwork bounds and the
 * miniplayer's cover while the sheet progress runs 1 -> 0: it is re-measured
 * and re-placed at the interpolated rect every frame (layout phase only, the
 * sheet's own spring drives it), the expanded artwork and the miniplayer
 * cover stand aside for it, and at each end of the journey it hands its
 * pixels over to the node that owns that position — never a fade, never a
 * second copy of the artwork. Re-implemented on ArchiveTune's in-window
 * BottomSheet after BitChord's PlayerDock flight semantics
 * (https://github.com/kushagrasinghx/BitChord, GPL-3.0): the artwork leads,
 * the rest of the player follows behind it.
 */

package moe.rukamori.archivetune.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.math.roundToInt

data class PlayerDockAnchor(
    val rect: Rect,
    val cornerRadius: Dp,
)

val LocalPlayerDockArtwork = compositionLocalOf<(PlayerDockAnchor?) -> Unit> { {} }

val LocalPlayerDockFlight = compositionLocalOf<State<Boolean>> {
    mutableStateOf(false)
}

@Composable
fun Modifier.dockArtworkAnchor(cornerRadius: Dp = 8.dp): Modifier {
    val reporter = LocalPlayerDockArtwork.current
    DisposableEffect(reporter) {
        onDispose { reporter(null) }
    }
    return this.onGloballyPositioned { coordinates ->
        val size = coordinates.size
        if (size.width > 0 && size.height > 0) {
            val topLeft = coordinates.positionInRoot()
            reporter(
                PlayerDockAnchor(
                    rect = Rect(
                        left = topLeft.x,
                        top = topLeft.y,
                        right = topLeft.x + size.width,
                        bottom = topLeft.y + size.height,
                    ),
                    cornerRadius = cornerRadius,
                ),
            )
        }
    }
}

@Composable
fun Modifier.dockFlightHidden(): Modifier {
    val flight = LocalPlayerDockFlight.current
    return this.graphicsLayer {
        alpha = if (flight.value) 0f else 1f
    }
}

@Composable
fun BoxScope.PlayerDockingArtwork(
    sheetProgress: () -> Float,
    fullAnchor: PlayerDockAnchor?,
    miniArtworkRect: Rect?,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
) {
    if (artworkUrl.isNullOrBlank()) return
    val full =
        fullAnchor?.rect
            ?.takeIf { it.width > 0f && it.height > 0f }
            ?: return
    val mini = miniArtworkRect ?: full
    val density = LocalDensity.current
    val fullCornerPx = with(density) { (fullAnchor?.cornerRadius ?: 8.dp).toPx() }
    val miniCornerPx = with(density) { 10.dp.toPx() }

    Box(
        modifier =
            modifier
                .layout { measurable, _ ->
                    val p = sheetProgress().coerceIn(0f, 1f)
                    val left = lerp(mini.left, full.left, p)
                    val top = lerp(mini.top, full.top, p)
                    val width = lerp(mini.width, full.width, p)
                    val height = lerp(mini.height, full.height, p)
                    val placeable =
                        measurable.measure(
                            Constraints.fixed(
                                width.roundToInt().coerceAtLeast(1),
                                height.roundToInt().coerceAtLeast(1),
                            ),
                        )
                    layout(0, 0) {
                        placeable.place(left.roundToInt(), top.roundToInt())
                    }
                }
                .graphicsLayer {
                    val p = sheetProgress().coerceIn(0f, 1f)
                    alpha = if (p > 0f && p < 1f) 1f else 0f
                    val cornerPx = lerp(miniCornerPx, fullCornerPx, p)
                    shape = RoundedCornerShape(cornerPx)
                    clip = true
                    shadowElevation = 10.dp.toPx() * (4f * p * (1f - p))
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
