/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * The maximized-player <-> miniplayer artwork docking, re-implemented after
 * BitChord's PlayerDock flight (https://github.com/kushagrasinghx/BitChord,
 * GPL-3.0): the expanded player keeps its REAL artwork alive and records it
 * into a GraphicsLayer every frame of the sheet's travel, and the flight host
 * redraws those recorded pixels over the fading player — one continuous
 * visual object from the player's artwork bounds to the miniplayer's cover,
 * never a second thumbnail, never a crossfade. The miniplayer's cover
 * reports its LayoutCoordinates continuously and stands aside for the flight
 * (see LocalPlayerDockFlight). All geometry is measured in root layout
 * coordinates through the dock, so navigation-bar, compact-state and
 * layout changes re-target the flight on the next frame.
 */

package moe.rukamori.archivetune.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.toSize
import kotlin.math.roundToInt

@Stable
class PlayerDock {
    private var miniArt: LayoutCoordinates? = null
    private var miniArtCorner: Dp = 10.dp

    private var sleeveArt: LayoutCoordinates? = null
    private var sleeveCorner: Dp = 8.dp

    var sheetProgress: (() -> Float)? = null
    var sleeveLayer: GraphicsLayer? = null

    fun reportMiniArt(coordinates: LayoutCoordinates, corner: Dp) {
        miniArt = coordinates
        miniArtCorner = corner
    }

    fun releaseMiniArt(coordinates: LayoutCoordinates) {
        if (miniArt === coordinates) miniArt = null
    }

    fun reportSleeveArt(coordinates: LayoutCoordinates, corner: Dp) {
        sleeveArt = coordinates
        sleeveCorner = corner
    }

    fun releaseSleeveArt(coordinates: LayoutCoordinates) {
        if (sleeveArt === coordinates) sleeveArt = null
    }

    internal fun miniOnRoot(): Rect? =
        miniArt?.takeIf { it.isAttached }?.let {
            Rect(it.positionInRoot(), it.size.toSize())
        }

    internal fun sleeveOnRoot(): Rect? =
        sleeveArt?.takeIf { it.isAttached }?.let {
            Rect(it.positionInRoot(), it.size.toSize())
        }

    internal fun miniCorner(): Dp = miniArtCorner

    internal fun sleeveCorner(): Dp = sleeveCorner

    fun docking(): Boolean {
        val t = flightFraction()
        return t > 0f && t < 1f
    }

    fun flightFraction(): Float {
        val p = sheetProgress?.invoke() ?: return 1f
        if (p >= 1f) return 1f
        if (miniArt?.isAttached != true || sleeveArt?.isAttached != true) return 1f
        return p.coerceIn(0f, 1f)
    }
}

val LocalPlayerDock = staticCompositionLocalOf<PlayerDock?> { null }

val LocalPlayerDockFlight = compositionLocalOf<State<Boolean>> {
    mutableStateOf(false)
}

@Composable
fun Modifier.playerDockArt(cornerRadius: Dp): Modifier {
    val dock = LocalPlayerDock.current ?: return this
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    DisposableEffect(dock) {
        onDispose { placed[0]?.let(dock::releaseMiniArt) }
    }
    return this.onPlaced { coordinates ->
        placed[0] = coordinates
        dock.reportMiniArt(coordinates, cornerRadius)
    }
}

@Composable
fun Modifier.dockFlightHidden(): Modifier {
    val flight = LocalPlayerDockFlight.current
    return this.graphicsLayer {
        alpha = if (flight.value) 1f / 255f else 1f
    }
}

@Composable
fun Modifier.dockSleeve(cornerRadius: Dp): Modifier {
    val dock = LocalPlayerDock.current ?: return this
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    DisposableEffect(dock) {
        onDispose { placed[0]?.let(dock::releaseSleeveArt) }
    }
    return this
        .onPlaced { coordinates ->
            placed[0] = coordinates
            dock.reportSleeveArt(coordinates, cornerRadius)
        }
        .drawWithContent {
            val layer = dock.sleeveLayer
            if (layer != null && dock.docking()) {
                layer.record(size.toIntSize()) { this@drawWithContent.drawContent() }
            } else {
                drawContent()
            }
        }
}

@Composable
fun BoxScope.PlayerDockFlightHost(
    dock: PlayerDock,
    sheetProgress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val layer = rememberGraphicsLayer()
    dock.sleeveLayer = layer
    dock.sheetProgress = sheetProgress
    DisposableEffect(dock) {
        onDispose {
            dock.sleeveLayer = null
            dock.sheetProgress = null
        }
    }
    var hostOrigin by remember { mutableStateOf<Offset?>(null) }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onGloballyPositioned { hostOrigin = it.positionInRoot() }
                .layout { measurable, _ ->
                    val t = dock.flightFraction()
                    val mini = dock.miniOnRoot()
                    val sleeve = dock.sleeveOnRoot()
                    val origin = hostOrigin
                    if (t <= 0f || t >= 1f || mini == null || sleeve == null || origin == null) {
                        val empty = measurable.measure(Constraints.fixed(0, 0))
                        layout(0, 0) { empty.place(0, 0) }
                    } else {
                        val left = lerp(mini.left, sleeve.left, t)
                        val top = lerp(mini.top, sleeve.top, t)
                        val width = lerp(mini.width, sleeve.width, t)
                        val height = lerp(mini.height, sleeve.height, t)
                        val placeable =
                            measurable.measure(
                                Constraints.fixed(
                                    width.roundToInt().coerceAtLeast(1),
                                    height.roundToInt().coerceAtLeast(1),
                                ),
                            )
                        layout(0, 0) {
                            placeable.place(
                                (left - origin.x).roundToInt(),
                                (top - origin.y).roundToInt(),
                            )
                        }
                    }
                }
                .graphicsLayer {
                    val t = dock.flightFraction()
                    alpha = if (t > 0f && t < 1f) 1f else 0f
                    if (alpha > 0f) {
                        val cornerPx = lerp(dock.miniCorner(), dock.sleeveCorner(), t).toPx()
                        shape = RoundedCornerShape(cornerPx)
                        clip = true
                        shadowElevation = 10.dp.toPx() * 4f * t * (1f - t)
                    }
                }
                .drawWithContent {
                    val sleeve = dock.sleeveOnRoot()
                    val layer = dock.sleeveLayer
                    if (sleeve != null && layer != null && sleeve.width > 0f && sleeve.height > 0f) {
                        scale(
                            size.width / sleeve.width,
                            size.height / sleeve.height,
                            pivot = Offset.Zero,
                        ) {
                            drawLayer(layer)
                        }
                    }
                },
    )
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction
