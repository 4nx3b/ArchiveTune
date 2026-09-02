/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * TikTok player style — the bottom info zone.
 *
 * What TikTok pins under the media: the post's identity up top (here: title,
 * artist, album), then the track's own progress with its time labels, then
 * the one transport control the spec calls for — play/pause. Swiping is the
 * skip gesture, so no next/previous buttons compete with it.
 *
 * The progress reflects the REAL playback position — the same 100 ms poll
 * Player.kt feeds every other style — and seeking writes straight to the one
 * player. No side timer, no drift.
 */

package moe.rukamori.archivetune.ui.player.tiktok

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection

@Composable
internal fun TikTokBottomZone(
    pageMetadata: MediaMetadata,
    isCurrentPage: Boolean,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    playerConnection: PlayerConnection,
    modifier: Modifier = Modifier,
) {
    val player = playerConnection.player
    val haptics = LocalHapticFeedback.current

    // While the finger is down the bar follows the finger (and seeks live, the
    // same immediate-seek behaviour the BitChord scrubber uses); when it lifts
    // the bar falls back to the real reported position.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val durationValid = durationMs > 0L && durationMs != C.TIME_UNSET
    val reportedFraction =
        if (durationValid) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val shownFraction = dragFraction ?: reportedFraction

    Column(modifier = modifier) {
        // ── Identity ──
        Text(
            text = pageMetadata.title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        val artistNames = pageMetadata.artists.joinToString(", ") { it.name }
        if (artistNames.isNotBlank()) {
            Text(
                text = artistNames,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
            )
        }
        pageMetadata.album?.title?.takeIf { it.isNotBlank() }?.let { album ->
            Text(
                text = album,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
            )
        }

        Spacer(Modifier.height(14.dp))

        // ── Progress: a subtle bar that thickens under the finger ──
        TikTokProgressSlider(
            progress = shownFraction,
            enabled = isCurrentPage && durationValid,
            onSeek = { fraction ->
                dragFraction = fraction
                val d = player.duration
                if (d > 0 && d != C.TIME_UNSET) {
                    player.seekTo((fraction * d).toLong())
                }
            },
            onSeekFinished = { dragFraction = null },
        )

        // ── Time labels ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, start = 4.dp, end = 4.dp),
        ) {
            Text(
                text = formatTikTokTime((shownFraction * durationMs).toLong().coerceAtLeast(0L)),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (durationValid) formatTikTokTime(durationMs) else "—:—",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── Play / pause: the one transport the feed needs ──
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(62.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        player.togglePlayPause()
                    },
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/**
 * The feed's scrubber: a hairline capsule that swells under the finger. One
 * gesture loop serves taps and drags (two separate detectors steal taps from
 * each other), and the whole height is the touch target — the visible bar is
 * only a few dp tall.
 */
@Composable
private fun TikTokProgressSlider(
    progress: Float,
    enabled: Boolean,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    val barHeight by animateDpAsState(
        targetValue = if (dragging) TIKTOK_SLIDER_ACTIVE else TIKTOK_SLIDER_IDLE,
        label = "tiktokSliderHeight",
    )

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(TIKTOK_SLIDER_TOUCH_HEIGHT)
                .then(
                    if (enabled) {
                        Modifier.pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                dragging = true
                                onSeek((down.position.x / size.width).coerceIn(0f, 1f))
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pointer =
                                        event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!pointer.pressed) {
                                        pointer.consume()
                                        break
                                    }
                                    if (pointer.positionChanged()) {
                                        onSeek((pointer.position.x / size.width).coerceIn(0f, 1f))
                                        pointer.consume()
                                    }
                                }
                                dragging = false
                                onSeekFinished()
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(barHeight),
        ) {
            val radius = CornerRadius(size.height / 2f)
            drawRoundRect(color = TIKTOK_SLIDER_TRACK, cornerRadius = radius)
            val filled = size.width * progress.coerceIn(0f, 1f)
            if (filled > 0f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.95f),
                    size = Size(filled.coerceAtLeast(size.height), size.height),
                    cornerRadius = radius,
                )
            }
        }
    }
}

private fun formatTikTokTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private val TIKTOK_SLIDER_IDLE = 3.dp
private val TIKTOK_SLIDER_ACTIVE = 7.dp
private val TIKTOK_SLIDER_TOUCH_HEIGHT = 30.dp
private val TIKTOK_SLIDER_TRACK = Color.White.copy(alpha = 0.28f)