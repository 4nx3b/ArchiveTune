/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * UIKit-style overscroll physics (2026-10-08: "Port the overscroll physics
 * too and make sure the content don't tear up like they do now in my app").
 * Adapted from BitChord's IosOverscroll (which adapts Convx's IosOverscroll,
 * GPL-3.0 — https://github.com/kushagrasinghx/BitChord) as an original
 * re-implementation: a self-limiting rubber band during the drag and a
 * critically-damped spring back on release, built purely on the public
 * NestedScrollConnection API (no experimental overscroll internals, so it
 * survives any Compose foundation version).
 *
 * Applied once around the whole NavHost: every scrollable on every screen
 * inherits the rubber band, while pull-to-refresh (which sits closer to the
 * list and consumes the pull first) keeps working unchanged.
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

// UIKit's rubber-band constant and the settle springs.
private const val RubberBandConstant = 0.55f
private const val FallbackContainerPx = 2000f
private const val NormalBounceStiffness = 247f
private const val FastBounceStiffness = 130f
private const val FastBounceVelocityThreshold = 5000f

/**
 * UIKit's self-limiting rubber band: the further you pull, the less each
 * pixel of finger travel moves the content. [containerPx] is the viewport
 * dimension the falloff is tuned against.
 */
private fun rubberBand(rawDistance: Float, containerPx: Float): Float {
    val dimension = if (containerPx > 0f) containerPx else FallbackContainerPx
    val distance = abs(rawDistance)
    val banded = (1f - 1f / (distance * RubberBandConstant / dimension + 1f)) * dimension / RubberBandConstant
    return banded * sign(rawDistance)
}

/** The inverse of [rubberBand]: the raw stretch that maps to [banded]. */
private fun inverseRubberBand(banded: Float, containerPx: Float): Float {
    val dimension = if (containerPx > 0f) containerPx else FallbackContainerPx
    if (abs(banded) >= dimension / RubberBandConstant) return banded
    val k = banded * RubberBandConstant / dimension
    if (abs(k) >= 1f) return banded
    return dimension / RubberBandConstant * (1f / (1f - abs(k)) - 1f) * sign(banded)
}

/**
 * iOS overscroll for a content tree: attach to a container that hosts
 * scrollable content. While a descendant list is dragged past its edge the
 * content follows the finger with the rubber-band falloff; scrolling back
 * into the band pays it down before the list moves; on release it springs
 * back critically damped (softer for fast flings). The leftover overscroll
 * is consumed, so the platform stretch/glow never fires alongside it.
 */
@Composable
fun Modifier.iosOverscroll(): Modifier {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val containerPx =
        with(density) {
            configuration.screenHeightDp.dp.toPx().coerceAtLeast(1f)
        }

    // offset = the DRAWN translation (rubber-banded); raw = the unconsumed
    // finger travel it derives from. Both live in one remembered holder so
    // the connection and the recomposed modifiers always share them.
    val offset = remember { Animatable(0f) }
    val band =
        remember {
            object {
                var rawStretch = 0f
                var settleJob: Job? = null
            }
        }

    fun stretchTo(newRaw: Float) {
        band.rawStretch = newRaw
        val target = rubberBand(newRaw, containerPx)
        scope.launch { offset.snapTo(target) }
    }

    val connection =
        remember(scope, containerPx) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput) return Offset.Zero
                    val dy = available.y
                    if (dy == 0f) return Offset.Zero

                    // If a settle animation is running, the user grabbed the
                    // content mid-bounce: resync the raw stretch to what is
                    // currently drawn before applying the new delta.
                    if (band.settleJob?.isActive == true) {
                        band.settleJob?.cancel()
                        band.rawStretch = inverseRubberBand(offset.value, containerPx)
                    }

                    // Scrolling back INTO the band pays it down first — the
                    // list only moves once the band is fully paid (same sign
                    // of travel as the band means paying it down).
                    if (band.rawStretch != 0f && dy * band.rawStretch > 0f) {
                        val consumedRaw =
                            if (abs(dy) >= abs(band.rawStretch)) {
                                band.rawStretch
                            } else {
                                dy
                            }
                        stretchTo(band.rawStretch - consumedRaw)
                        return Offset(0f, consumedRaw)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(
                    available: Offset,
                    consumed: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source != NestedScrollSource.UserInput) return Offset.Zero
                    val dy = available.y
                    if (dy == 0f) return Offset.Zero
                    band.settleJob?.cancel()
                    // The list could not consume this travel: bend instead.
                    // Finger past the TOP (dy < 0) -> content travels DOWN
                    // (positive); past the BOTTOM (dy > 0) -> content UP.
                    stretchTo(band.rawStretch - dy)
                    return available
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    if (offset.value != 0f) {
                        band.rawStretch = inverseRubberBand(offset.value, containerPx)
                        val stiffness =
                            if (abs(available.y) > FastBounceVelocityThreshold) {
                                FastBounceStiffness
                            } else {
                                NormalBounceStiffness
                            }
                        band.settleJob =
                            scope.launch {
                                offset.animateTo(
                                    targetValue = 0f,
                                    animationSpec =
                                        spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = stiffness,
                                            visibilityThreshold = 0.5f,
                                        ),
                                )
                                band.rawStretch = 0f
                            }
                    }
                    return Velocity.Zero
                }
            }
        }

    return this
        .nestedScroll(connection)
        .graphicsLayer {
            translationY = offset.value
        }
}
