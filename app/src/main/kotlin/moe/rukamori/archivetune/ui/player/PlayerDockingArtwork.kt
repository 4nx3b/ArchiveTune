/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * The maximized-player -> miniplayer artwork flight, after the reference
 * recording (YouTube-Music-style): when the player sheet collapses, the
 * player UI fades away with the sheet while the song thumbnail SEPARATES,
 * sweeps down, and settles into the miniplayer flying in from right to left
 * once the full-screen player is completely minimised. It is a one-shot
 * overlay animation — never drag-synced, never re-measuring a video surface,
 * so canvas and music-video songs can never glitch: what flies is always the
 * STATIC thumbnail. The reverse direction (miniplayer -> full player) has no
 * artwork animation at all, by design.
 *
 * Geometry lives in root layout coordinates: the sleeve and the miniplayer
 * slot both report their rects through positionInRoot, and the trigger
 * captures the sheet's current and settled translations so the flight runs
 * in pure screen space — a monotonic down-then-right-to-left sweep that can
 * never dip past the slot, whatever pace the sheet itself settles at.
 */

package moe.rukamori.archivetune.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil3.compose.AsyncImage
import moe.rukamori.archivetune.R
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-shot controller for the collapse flight.
 *
 * The expanded player's static artwork reports its root-space rect through
 * [reportSleeve]; the miniplayer's artwork slot reports through [reportMini].
 * When the sheet starts collapsing, the host-side trigger calls [launch] with
 * the sheet's current translation (where the artwork is on screen right now)
 * and its settled translation (where the miniplayer's slot ends up) — the
 * flight then runs in pure screen space.
 */
@Stable
class MiniPlayerFlightController {
    internal var sleeveRect: Rect? = null
    internal var sleeveCorner: Dp = 8.dp

    internal var miniRect: Rect? = null

    internal var flightActive by mutableStateOf(false)
        private set

    internal var flightId by mutableIntStateOf(0)
        private set

    internal var startRect: Rect? = null
    internal var startCorner: Dp = 8.dp
    internal var targetRect: Rect? = null
    internal var targetCorner: Dp = 10.dp
    internal var flightUrl: String? = null

    fun reportSleeve(
        rect: Rect,
        corner: Dp,
    ) {
        sleeveRect = rect
        sleeveCorner = corner
    }

    fun reportMini(rect: Rect) {
        miniRect = rect
    }

    /**
     * @param url static thumbnail to fly (never a canvas/video surface).
     * @param startTranslationPx the sheet's translation at trigger time, so
     *   the flight begins exactly where the artwork is visible on screen.
     * @param settledTranslationPx the sheet's translation once fully
     *   collapsed — the miniplayer slot's final on-screen position.
     */
    fun launch(
        url: String?,
        startTranslationPx: Float,
        settledTranslationPx: Float,
    ): Boolean {
        val sleeve = sleeveRect ?: return false
        val mini = miniRect ?: return false
        if (url.isNullOrBlank()) return false
        if (sleeve.width <= 0f || sleeve.height <= 0f) return false
        startRect =
            Rect(
                left = sleeve.left,
                top = sleeve.top + startTranslationPx,
                right = sleeve.right,
                bottom = sleeve.bottom + startTranslationPx,
            )
        targetRect =
            Rect(
                left = mini.left,
                top = mini.top + settledTranslationPx,
                right = mini.right,
                bottom = mini.bottom + settledTranslationPx,
            )
        startCorner = sleeveCorner
        targetCorner = MiniFlightTargetCorner
        flightUrl = url
        flightId += 1
        flightActive = true
        return true
    }

    internal fun finish() {
        flightActive = false
    }
}

private val MiniFlightTargetCorner = 10.dp

/** Total flight time; the sheet settles underneath it. */
private const val MiniFlightDurationMs = 560

val LocalMiniPlayerFlight = staticCompositionLocalOf<MiniPlayerFlightController?> { null }

/**
 * Attached to each player style's main STATIC artwork (the current pager page
 * for the default style, the album square for Apple Music style, the card for
 * BitChord, the sleeve for SimpMusic). Canvas and video surfaces never report
 * — the flight always shows the static thumbnail.
 */
@Composable
fun Modifier.miniFlightSleeve(cornerRadius: Dp): Modifier {
    val controller = LocalMiniPlayerFlight.current ?: return this
    return this.onGloballyPositioned { coordinates ->
        controller.reportSleeve(
            Rect(
                offset = coordinates.positionInRoot(),
                size = Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()),
            ),
            cornerRadius,
        )
    }
}

/**
 * Hides the player's real static artwork while the flight is airborne — the
 * flying thumbnail replaces it exactly, so keeping both would double-draw.
 * Canvas/video surfaces are NOT hidden: they keep rendering and fade away
 * with the sheet while the static thumbnail flies out over them.
 */
@Composable
fun Modifier.miniFlightHidden(): Modifier {
    val controller = LocalMiniPlayerFlight.current ?: return this
    return this.graphicsLayer {
        alpha = if (controller.flightActive) 1f / 255f else 1f
    }
}

/**
 * The flight overlay. Compose it as a top-level sibling ABOVE the player
 * sheet (outside the translating sheet Box) so the thumbnail's rendered
 * position is exactly the screen-space rect the controller computes — a
 * monotonic sweep that lands pixel-exact on the miniplayer's artwork slot.
 */
@Composable
fun MiniPlayerArtworkFlightHost(
    controller: MiniPlayerFlightController,
    isSheetSettled: () -> Boolean,
    isFlightAbandoned: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    if (!controller.flightActive) return
    val start = controller.startRect ?: return
    val target = controller.targetRect ?: return
    val url = controller.flightUrl ?: return

    var hostOrigin by remember { mutableStateOf<Offset?>(null) }

    val progress = remember(controller.flightId) { Animatable(0f) }
    LaunchedEffect(controller.flightId) {
        launch {
            snapshotFlow { isFlightAbandoned() }.first { it }
            controller.finish()
        }
        progress.animateTo(
            1f,
            tween(durationMillis = MiniFlightDurationMs, easing = FastOutSlowInEasing),
        )
        // Hold pinned on the slot until the sheet has finished minimising so
        // the handoff to the miniplayer's own artwork is never mid-motion.
        withTimeoutOrNull(500L) {
            snapshotFlow { isSheetSettled() }.first { it }
        }
        controller.finish()
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onGloballyPositioned { hostOrigin = it.positionInRoot() }
                .layout { measurable, _ ->
                    val origin = hostOrigin
                    if (origin == null) {
                        val empty = measurable.measure(Constraints.fixed(0, 0))
                        layout(0, 0) { empty.place(0, 0) }
                    } else {
                        val width = start.width.roundToInt().coerceAtLeast(1)
                        val height = start.height.roundToInt().coerceAtLeast(1)
                        val placeable = measurable.measure(Constraints.fixed(width, height))
                        layout(0, 0) {
                            placeable.place(
                                (start.left - origin.x).roundToInt(),
                                (start.top - origin.y).roundToInt(),
                            )
                        }
                    }
                }.graphicsLayer {
                    val p = progress.value
                    val p0 = start.center
                    val p2 = target.center
                    // Control point: drop to the miniplayer's band first,
                    // then sweep right-to-left into the slot.
                    val p1x = p0.x + (p2.x - p0.x) * 0.10f
                    val p1y = p2.y
                    val inv = 1f - p
                    val cx = inv * inv * p0.x + 2f * p * inv * p1x + p * p * p2.x
                    val cy = inv * inv * p0.y + 2f * p * inv * p1y + p * p * p2.y
                    // The thumbnail sheds most of its size early — it is
                    // already a small square while the player dissolves.
                    val sizeP = (p * 1.30f).coerceIn(0f, 1f)
                    val w = lerp(start.width, target.width, sizeP)
                    val h = lerp(start.height, target.height, sizeP)
                    translationX = cx - p0.x
                    translationY = cy - p0.y
                    scaleX = if (start.width > 0f) w / start.width else 1f
                    scaleY = if (start.height > 0f) h / start.height else 1f
                    val visualCorner =
                        lerp(controller.startCorner.toPx(), controller.targetCorner.toPx(), sizeP)
                    val cornerX = if (scaleX > 0.01f) visualCorner / scaleX else visualCorner
                    shape = RoundedCornerShape(CornerSize(cornerX))
                    clip = true
                },
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            placeholder = painterResource(R.drawable.ic_music_placeholder),
            error = painterResource(R.drawable.ic_music_placeholder),
            modifier = Modifier.fillMaxSize(),
        )
    }
}
