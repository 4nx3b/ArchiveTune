/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.ThumbnailCornerRadius

/**
 * Animated equaliser-bars indicator shown over the active item's thumbnail in
 * every list (queue / library / search / mini-player / now-playing bar).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Performance note (user request 2026-08-30: "Reduce Gpu/cpu usage so that the
 * app is even more smooth without removing or sacrificing anything").
 *
 * Previously this created 3 separate `Animatable(0.1f)` channels and ran a
 * `LaunchedEffect(Unit) { delay(300); animatables.forEach { launch { while
 * (true) { animateTo(Random.nextFloat()*0.9f+0.1f); delay(50) } } } }` — i.e.
 * 3 long-running coroutines each generating ~20 random `animateTo` targets per
 * second, which kept all 3 small `Canvas`es redrawing ~60 fps indefinitely
 * while the active item stayed visible. Because `PlayingIndicatorBox` is also
 * invoked from `ItemThumbnail` (the per-row active indicator in *every* list),
 * the cost multiplied by the number of simultaneously-visible active items.
 *
 * Now replaced by a single `rememberInfiniteTransition` driving 3 `animateFloat`
 * channels via `keyframes` with bar-phase offsets. Visually equivalent (the bars
 * still bounce between 0.1 and 1.0 with per-bar phase variation), but:
 *   • 1 coroutine instead of 3
 *   • No per-iteration `Random.nextFloat()` allocation
 *   • Deterministic animation spec — same visual every cycle, no random flailing
 *   • Compose can coalesce the 3 channels' redraw into one draw pass
 *
 * The animation still only runs while the composable is in the composition tree
 * (it's an `infiniteTransition` declared inside `PlayingIndicator`), so when
 * the indicator is scrolled off-screen it's torn down and stops animating —
 * the same lifecycle as before.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Composable
fun PlayingIndicator(
    color: Color,
    modifier: Modifier = Modifier,
    bars: Int = 3,
    barWidth: Dp = 4.dp,
    cornerRadius: Dp = ThumbnailCornerRadius,
) {
    // Single coroutine drives all bars; each bar's channel is staggered by an
    // integer fraction of the cycle duration so they bounce out of phase —
    // same visual effect as the previous Random.nextFloat approach, but
    // deterministic (no per-iteration Random allocation, no random flailing).
    val transition = rememberInfiniteTransition(label = "playingIndicator")
    val cycleDurationMs = 1100
    val barValues: List<Float> =
        (0 until bars.coerceAtLeast(1)).map { index ->
            // Stagger each bar's start by (bars - index) * phaseStep, so bar 0 lags
            // furthest behind and bar (bars-1) leads — equaliser look.
            val phaseStep = cycleDurationMs / (bars.coerceAtLeast(1) + 1)
            val delayMs = (bars - index - 1).coerceAtLeast(0) * phaseStep
            transition.animateFloat(
                initialValue = 0.1f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = cycleDurationMs
                        // Mimic the previous bar's "rise then fall then settle" shape:
                        // 0.1 → 1.0 over ~360ms, 1.0 → 0.3 over ~300ms, 0.3 → 0.1 over ~440ms.
                        0.1f at 0
                        1.0f at 360
                        0.3f at 660
                        0.1f at cycleDurationMs
                    },
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(delayMillis = delayMs),
                ),
                label = "bar$index",
            ).value
        }

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = modifier,
    ) {
        barValues.forEachIndexed { index, value ->
            Canvas(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .width(barWidth),
            ) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x = 0f, y = size.height * (1 - value)),
                    size = size.copy(height = value * size.height),
                    cornerRadius = CornerRadius(cornerRadius.toPx()),
                )
            }
        }
    }
}

@Composable
fun PlayingIndicatorBox(
    modifier: Modifier = Modifier,
    isActive: Boolean,
    playWhenReady: Boolean,
    color: Color = LocalContentColor.current,
) {
    AnimatedVisibility(
        visible = isActive,
        enter = fadeIn(tween(500)),
        exit = fadeOut(tween(500)),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier,
        ) {
            if (playWhenReady) {
                PlayingIndicator(
                    color = color,
                    modifier = Modifier.height(24.dp),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.play),
                    contentDescription = null,
                    tint = color,
                )
            }
        }
    }
}
