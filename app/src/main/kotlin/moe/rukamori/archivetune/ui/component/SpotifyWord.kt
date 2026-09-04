/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.rukamori.archivetune.lyrics.WordTimestamp

/**
 * Minimum duration of a word's fill-sweep animation. Guarantees a visible fill even
 * for words shorter than the position-poll interval.
 */
internal const val MIN_SWEEP_MS = 180L

/**
 * One word of the Spotify-style word-synced lyrics renderer: a resting pill behind the
 * whole word with a fill that sweeps with the word timing, plus a bright sung-text layer
 * clipped to the same sweep edge.
 *
 * Extracted verbatim from upstream vossgraves/dev LyricsV2.kt (2026-08-31 window port)
 * so word-synced lyric renderers can sweep each word without pulling in the rest of
 * the V2 renderer.
 */
@Composable
internal fun SpotifyWord(
    word: WordTimestamp,
    isLineActive: Boolean,
    pillVisible: Boolean,
    currentPositionMs: Long,
    textColor: Color,
    inactiveAlpha: Float,
    fontSize: Float,
    isBackground: Boolean,
    lyricsFontFamily: FontFamily?,
    isRtl: Boolean,
) {
    val wordStartMs = (word.startTime * 1000).toLong()
    val wordEndMs = (word.endTime * 1000).toLong()
    val isWordComplete = currentPositionMs >= wordEndMs
    val isWordActive = currentPositionMs in wordStartMs until wordEndMs

    // Same Animatable-driven sweep as AnimatedWordV2: guarantees a visible
    // fill even for words shorter than the position-poll interval.
    val sweepAnimatable = remember(word) { Animatable(0f) }
    LaunchedEffect(isWordActive, isWordComplete, wordStartMs, wordEndMs) {
        when {
            isWordComplete && sweepAnimatable.value < 1f -> {
                sweepAnimatable.animateTo(
                    1f,
                    tween(durationMillis = 80, easing = LinearEasing),
                )
            }

            isWordActive -> {
                val remainingMs = (wordEndMs - currentPositionMs).coerceAtLeast(1L)
                sweepAnimatable.animateTo(
                    1f,
                    tween(
                        durationMillis = maxOf(remainingMs, MIN_SWEEP_MS).toInt().coerceAtLeast(1),
                        easing = LinearEasing,
                    ),
                )
            }

            else -> {
                sweepAnimatable.snapTo(0f)
            }
        }
    }
    val progress = if (isWordComplete) 1f else sweepAnimatable.value

    val pillRadius = 6.dp
    val pillPaddingHorizontal = 7.dp
    val pillPaddingVertical = 1.dp
    val interWordGap = 2.dp

    val baseHeadlineStyle = MaterialTheme.typography.headlineMedium
    val textStyle =
        remember(baseHeadlineStyle, fontSize, lyricsFontFamily) {
            baseHeadlineStyle.copy(
                fontSize = fontSize.sp,
                fontWeight = FontWeight.SemiBold,
                fontStyle = FontStyle.Normal,
                lineHeight = (fontSize * 1.35f).sp,
                fontFamily = lyricsFontFamily ?: baseHeadlineStyle.fontFamily,
            )
        }

    Box(
        modifier =
            Modifier
                .padding(horizontal = interWordGap)
                .drawBehind {
                    if (!pillVisible) return@drawBehind
                    val r = pillRadius.toPx()
                    // resting slot behind the whole word
                    drawRoundRect(
                        color = textColor.copy(alpha = 0.15f),
                        cornerRadius = CornerRadius(r),
                    )
                    // sung fill, sweeps LTR (or RTL) with the word timing
                    val pillWidth = size.width
                    val fillPx = pillWidth * progress
                    if (fillPx > 0f) {
                        val left = if (isRtl) pillWidth - fillPx else 0f
                        clipRect(left, 0f, left + fillPx, size.height) {
                            drawRoundRect(
                                color = textColor.copy(alpha = 0.32f),
                                cornerRadius = CornerRadius(r),
                            )
                        }
                    }
                }
                .padding(horizontal = pillPaddingHorizontal, vertical = pillPaddingVertical),
    ) {
        // Layer 1: dim unsung text (the resting look for the whole line)
        Text(
            text = word.text,
            style = textStyle,
            color = textColor.copy(alpha = if (isBackground) inactiveAlpha * 0.75f else (inactiveAlpha + 0.1f).coerceAtMost(1f),
            ),
        )

        // Layer 2: bright sung text, clipped to the same sweep as the pill.
        // Only composed while the word is animating or done on the active line.
        if (pillVisible && (isWordComplete || isWordActive) && isLineActive) {
            Text(
                text = word.text,
                style = textStyle,
                color = textColor.copy(alpha = if (isBackground) 0.75f else 1f),
                modifier =
                    if (isWordActive && !isWordComplete) {
                        Modifier
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                // Align the text clip edge with the pill fill edge:
                                // pill spans [textWidth + 2*pillPaddingHorizontal].
                                val padHpx = pillPaddingHorizontal.toPx()
                                val pillWidth = size.width + padHpx * 2f
                                val fillPx = pillWidth * progress
                                val pillLeft = if (isRtl) pillWidth - fillPx else 0f
                                val rawLeft = pillLeft - padHpx
                                val solidFraction = (rawLeft / size.width).coerceIn(0f, 1f)
                                drawContent()
                                // Hard-edge alpha mask at the sweep position (same edge as the pill),
                                // via DstIn like AnimatedWordV2 — drawContent() cannot be called
                                // inside a nested clipRect receiver, so mask instead of clip.
                                drawRect(
                                    brush =
                                        if (isRtl) {
                                            Brush.horizontalGradient(
                                                0f to Color.Transparent,
                                                solidFraction.coerceAtMost(1f) to Color.Transparent,
                                                solidFraction.coerceAtLeast(0f) to Color.Black,
                                                1f to Color.Black,
                                            )
                                        } else {
                                            Brush.horizontalGradient(
                                                0f to Color.Black,
                                                solidFraction.coerceIn(0f, 1f) to Color.Black,
                                                solidFraction.coerceAtLeast(0f) to Color.Transparent,
                                                1f to Color.Transparent,
                                            )
                                        },
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                    } else {
                        Modifier
                    },
            )
        }
    }
}
