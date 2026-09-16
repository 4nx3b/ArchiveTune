/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player.spatialflow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.ui.component.BottomSheetState

/**
 * The SpatialFlow full-player -> mini-player transition, ported from
 * MythicalSHUB/SpatialFlow's PlayerBottomSheetCompose: ONE artwork layer,
 * always mounted in the sheet root (outside both crossfade containers),
 * morphing continuously between the mini player's circular artwork slot and
 * the full player's artwork slot across the whole sheet travel.
 *
 * All morphing happens in the draw phase (graphicsLayer reads the sheet
 * progress and the two slot rects) — zero recomposition, zero layout
 * invalidation per frame. The pager's swipe gesture is only enabled near the
 * expanded end, exactly like the original, so the mini player keeps its own
 * horizontal swipe-to-dismiss behaviour.
 *
 * Slot rects are measured in root layout coordinates via onGloballyPositioned;
 * the sheet's graphicsLayer slide does not affect layout positions, and since
 * every participant (mini slot, full slot, this layer) lives inside the same
 * sliding sheet box, the shared offset cancels out.
 */
@Composable
fun BoxScope.SpatialFlowFloatingArtwork(
    state: BottomSheetState,
    mediaMetadata: MediaMetadata,
    queueWindows: List<androidx.media3.common.Timeline.Window>,
    currentWindowIndex: Int,
    artUrl: String?,
    isPlaying: Boolean,
    fullArtworkRect: Rect?,
    miniArtworkRect: Rect?,
    lyricsOpen: Boolean,
    onPlaySongAtWindow: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (fullArtworkRect == null || miniArtworkRect == null || fullArtworkRect.width <= 0f) {
        return
    }
    val full = fullArtworkRect
    val mini = miniArtworkRect
    Box(
        modifier =
            modifier
                .align(Alignment.TopStart)
                .size(with(androidx.compose.ui.platform.LocalDensity.current) { full.width.toDp() })
                .graphicsLayer {
                    val p = state.progress.coerceIn(0f, 1f)
                    // Hide while the lyrics overlay owns the artwork morph
                    // (the flying artwork in SpatialFlowPlayer takes over).
                    val visible = !lyricsOpen
                    alpha = if (visible) 1f else 0f
                    if (!visible) return@graphicsLayer

                    // Scale the full-size artwork down to the mini circle.
                    val scale = lerp(mini.width / full.width, 1f, p)
                    scaleX = scale
                    scaleY = scale

                    // Lerp the centre from the mini slot to the full slot:
                    // translation keeps the scaled centre on the lerped point.
                    val targetCentreX = lerp(mini.center.x, full.center.x, p)
                    val targetCentreY = lerp(mini.center.y, full.center.y, p)
                    translationX = targetCentreX - full.center.x
                    translationY = targetCentreY - full.center.y

                    // Circle when mini, the full player's 16dp radius when
                    // expanded — continuous in between.
                    val cornerPx = lerp(mini.width / 2f, 16.dp.toPx(), p)
                    shape = RoundedCornerShape(cornerPx)
                    clip = true

                    shadowElevation = lerp(0f, 16.dp.toPx(), p)
                },
    ) {
        SpatialFlowArtworkPager(
            mediaMetadata = mediaMetadata,
            queueWindows = queueWindows,
            currentWindowIndex = currentWindowIndex,
            userScrollEnabled = state.progress > 0.95f && !lyricsOpen,
            artUrl = artUrl,
            isPlaying = isPlaying,
            cornerRadius = 16.dp,
            shadowElevation = 0.dp,
            onPlaySongAtWindow = onPlaySongAtWindow,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction
