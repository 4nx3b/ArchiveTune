/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * UIKit-style overscroll physics (2026-10-08: "The overscroll physics should
 * only apply when I'm at the end of the page or beginning of the page and I
 * still try to scroll in the direction where there's no content available").
 * Implemented as an OverscrollEffect installed through LocalOverscrollFactory:
 * the effect is only ever invoked by a scroll container that has already
 * failed to consume the delta, i.e. content is at its edge in that direction
 * — so the rubber band never fires for non-scrollable screens, mid-content
 * scrolling, or drags over horizontal-only scrollers. During the drag the
 * container's content is displaced on UIKit's self-limiting rubber-band curve;
 * on release it settles back on a critically damped spring. Re-implemented
 * from scratch on the public Compose overscroll API (no experimental
 * internals), following the same approach BitChord uses.
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

private const val RubberBandConstant = 0.55f
private const val FallbackContainerPx = 2000f
private const val MaxBounceVelocity = 10000f
private const val FastBounceVelocityThreshold = 5000f
private const val NormalBounceStiffness = 247f
private const val FastBounceStiffness = 130f

private fun rubberBand(rawDistance: Float, containerPx: Float): Float {
    val dimension = if (containerPx > 0f) containerPx else FallbackContainerPx
    val distance = abs(rawDistance)
    val banded =
        (1f - 1f / (distance * RubberBandConstant / dimension + 1f)) *
            dimension / RubberBandConstant
    return banded * sign(rawDistance)
}

private class IosOverscrollEffect : OverscrollEffect {
    private val rawPullX = mutableFloatStateOf(0f)
    private val rawPullY = mutableFloatStateOf(0f)
    private var containerWidthPx = 0f
    private var containerHeightPx = 0f

    override val isInProgress: Boolean
        get() = rawPullX.floatValue != 0f || rawPullY.floatValue != 0f

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
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

        coroutineScope {
            launch { settle(rawPullX, leftover.x) }
            launch { settle(rawPullY, leftover.y) }
        }
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
        val velocity = leftoverVelocity.coerceIn(-MaxBounceVelocity, MaxBounceVelocity)
        animate(
            initialValue = pull.floatValue,
            targetValue = 0f,
            initialVelocity = velocity,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness =
                        if (abs(velocity) > FastBounceVelocityThreshold) {
                            FastBounceStiffness
                        } else {
                            NormalBounceStiffness
                        },
                ),
        ) { value, _ ->
            pull.floatValue = value
        }
    }

    override val node: DelegatableNode =
        IosOverscrollNode(
            offsetX = { rubberBand(rawPullX.floatValue, containerWidthPx) },
            offsetY = { rubberBand(rawPullY.floatValue, containerHeightPx) },
            onMeasured = { width, height ->
                containerWidthPx = width
                containerHeightPx = height
            },
        )
}

private class IosOverscrollNode(
    private val offsetX: () -> Float,
    private val offsetY: () -> Float,
    private val onMeasured: (Float, Float) -> Unit,
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
}

private class IosOverscrollFactory(
    private val density: Density,
    private val scope: CoroutineScope,
) : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = IosOverscrollEffect()

    override fun equals(other: Any?): Boolean =
        other is IosOverscrollFactory && other.density == density && other.scope === scope

    override fun hashCode(): Int = 31 * density.hashCode() + scope.hashCode()
}

@Composable
fun rememberIosOverscrollFactory(): OverscrollFactory {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    return remember(density, scope) { IosOverscrollFactory(density, scope) }
}
