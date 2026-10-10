/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

private const val DefaultRubberBandConstant = 0.55f
private const val DefaultBounceStiffness = 247f
private const val FallbackContainerPx = 2000f

// Release velocity is damped and clamped before it is injected into the
// bounce-back spring. The full leftover fling velocity (up to 10000 px/s
// before) stretched the band by close to a thousand extra pixels and made the
// settle crawl for one to two seconds — during which a grab had to walk the
// huge pull all the way back before the list scrolled, reading exactly as
// "the app ignores touch until the overscroll settles". UIKit transfers only a
// heavily damped fraction of the release velocity into the bounce.
private const val BounceVelocityTransferFactor = 0.2f
private const val MaxBounceVelocity = 3000f

private fun rubberBand(
    rawDistance: Float,
    containerPx: Float,
    rubberBandConstant: Float,
): Float {
    val c = rubberBandConstant.coerceIn(0.05f, 4f)
    val dimension = if (containerPx > 0f) containerPx else FallbackContainerPx
    val distance = abs(rawDistance)
    val banded =
        (1f - 1f / (distance * c / dimension + 1f)) * dimension / c
    return banded * sign(rawDistance)
}

private class IosOverscrollEffect(
    private val scope: CoroutineScope,
    private val rubberBandConstant: Float,
    private val bounceStiffness: Float,
) : OverscrollEffect {
    private val rawPullX = mutableFloatStateOf(0f)
    private val rawPullY = mutableFloatStateOf(0f)
    private var containerWidthPx = 0f
    private var containerHeightPx = 0f

    // Settle animations for the bounce-back, one per axis. They run on the
    // factory-provided scope (independent of the fling dispatch), so
    // applyToFling returns as soon as the list's own fling finishes — the
    // scrollable's mutation is released immediately and a new finger drag
    // never queues behind the bounce. A new user drag CANCELS the settles
    // (see applyToScroll): the finger grabs the rubber band wherever it is.
    private var settleJobX: Job? = null
    private var settleJobY: Job? = null

    override val isInProgress: Boolean
        get() = rawPullX.floatValue != 0f || rawPullY.floatValue != 0f

    private fun cancelSettles() {
        settleJobX?.cancel()
        settleJobY?.cancel()
        settleJobX = null
        settleJobY = null
    }

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        if (source == NestedScrollSource.UserInput) {
            // The finger grabs the rubber band where it is right now: stop the
            // running settle immediately so it stops fighting the drag. The
            // pull keeps its current value and payDown below walks it back
            // under the finger, exactly like UIKit.
            cancelSettles()
        }
        val paidX = payDown(rawPullX, delta.x)
        val paidY = payDown(rawPullY, delta.y)
        val remaining = Offset(delta.x - paidX, delta.y - paidY)
        val consumed = performScroll(remaining)
        val leftover = remaining - consumed

        var stretchedX = 0f
        var stretchedY = 0f
        if (source == NestedScrollSource.UserInput) {
            if (leftover.x != 0f) {
                rawPullX.floatValue += leftover.x
                stretchedX = leftover.x
            }
            if (leftover.y != 0f) {
                rawPullY.floatValue += leftover.y
                stretchedY = leftover.y
            }
        }
        return Offset(
            consumed.x + paidX + stretchedX,
            consumed.y + paidY + stretchedY,
        )
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        val consumed = performFling(velocity)
        val leftover = velocity - consumed
        if (!isInProgress && leftover == Velocity.Zero) return

        cancelSettles()
        settleJobX = scope.launch { settle(rawPullX, leftover.x) }
        settleJobY = scope.launch { settle(rawPullY, leftover.y) }
    }

    private fun payDown(pull: MutableFloatState, delta: Float): Float {
        val current = pull.floatValue
        if (current == 0f || delta == 0f || sign(current) == sign(delta)) return 0f
        val target = current + delta
        val next = if (sign(target) != sign(current)) 0f else target
        pull.floatValue = next
        return next - current
    }

    private suspend fun settle(pull: MutableFloatState, leftoverVelocity: Float) {
        if (pull.floatValue == 0f && leftoverVelocity == 0f) return
        // The user's bounce-back-speed setting is always honoured (the old
        // fast-fling hardcode made the slider look dead: every noticeable
        // bounce comes from a fling, and those always took the hardcoded
        // stiffness). Release velocity is damped + clamped — see the
        // BounceVelocityTransferFactor note above.
        val velocity =
            (leftoverVelocity * BounceVelocityTransferFactor)
                .coerceIn(-MaxBounceVelocity, MaxBounceVelocity)
        animate(
            initialValue = pull.floatValue,
            targetValue = 0f,
            initialVelocity = velocity,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = bounceStiffness.coerceIn(30f, 2000f),
                ),
        ) { value, _ ->
            pull.floatValue = value
        }
    }

    override val node: DelegatableNode =
        IosOverscrollNode(
            offsetX = { rubberBand(rawPullX.floatValue, containerWidthPx, rubberBandConstant) },
            offsetY = { rubberBand(rawPullY.floatValue, containerHeightPx, rubberBandConstant) },
            onMeasured = { width, height ->
                containerWidthPx = width
                containerHeightPx = height
            },
            onDetachedFromHierarchy = {
                // The scrollable left composition mid-bounce: stop animating a
                // pull nobody renders anymore and reset the band.
                cancelSettles()
                rawPullX.floatValue = 0f
                rawPullY.floatValue = 0f
            },
        )
}

private class IosOverscrollNode(
    private val offsetX: () -> Float,
    private val offsetY: () -> Float,
    private val onMeasured: (Float, Float) -> Unit,
    private val onDetachedFromHierarchy: () -> Unit,
) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        onMeasured(
            (if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width).toFloat(),
            (if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height).toFloat(),
        )
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                translationX = offsetX()
                translationY = offsetY()
            }
        }
    }

    override fun onDetach() {
        super.onDetach()
        onDetachedFromHierarchy()
    }
}

private class IosOverscrollFactory(
    private val density: Density,
    private val scope: CoroutineScope,
    private val rubberBandConstant: Float,
    private val bounceStiffness: Float,
) : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect =
        IosOverscrollEffect(scope, rubberBandConstant, bounceStiffness)

    override fun equals(other: Any?): Boolean =
        other is IosOverscrollFactory &&
            other.density == density &&
            other.scope === scope &&
            other.rubberBandConstant == rubberBandConstant &&
            other.bounceStiffness == bounceStiffness

    override fun hashCode(): Int =
        31 * (31 * (31 * density.hashCode() + scope.hashCode()) + rubberBandConstant.hashCode()) +
            bounceStiffness.hashCode()
}

class NoOverscrollFactory : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = NoOverscrollEffect()

    override fun equals(other: Any?): Boolean = other is NoOverscrollFactory
    override fun hashCode(): Int = NoOverscrollFactory::class.hashCode()
}

private class NoOverscrollEffect : OverscrollEffect {
    override val isInProgress: Boolean = false

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset = performScroll(delta)

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        performFling(velocity)
    }

    override val node: DelegatableNode =
        object : Modifier.Node(), LayoutModifierNode {
            override fun MeasureScope.measure(
                measurable: Measurable,
                constraints: Constraints,
            ): MeasureResult {
                val placeable = measurable.measure(constraints)
                return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
        }
}

@Composable
fun rememberIosOverscrollFactory(
    rubberBandTension: Float = DefaultRubberBandConstant,
    bounceStiffness: Float = DefaultBounceStiffness,
): OverscrollFactory {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    return remember(density, scope, rubberBandTension, bounceStiffness) {
        IosOverscrollFactory(density, scope, rubberBandTension, bounceStiffness)
    }
}
