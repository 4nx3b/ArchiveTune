/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * SpatialFlow player style — the full-screen lyrics overlay.
 *
 * A port of SpatialFlow's FullScreenLyricsOverlay + the circular-reveal
 * modifier (github.com/MythicalSHUB/SpatialFlow, GPL-3.0,
 * ui/player/FullScreenLyricsOverlay.kt): the overlay reveals with a circular
 * clip expanding from the Lyrics chip, carries the centred "LYRICS • Synced
 * Lyrics" header with the song title, the auto-scrolling synced lines (active
 * 38sp Bold, inactive 20sp dimmed, tap to seek), the plain-lyrics fallback and
 * the metadata footer. Dimensions, colors and reveal timing are SpatialFlow's
 * own. The lyric DATA comes from ArchiveTune's own lyrics store
 * (playerConnection.currentLyrics parsed by LyricsUtils) — real providers, no
 * second lyrics implementation.
 */

package moe.rukamori.archivetune.ui.player.spatialflow

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.lyrics.LyricsEntry
import moe.rukamori.archivetune.models.MediaMetadata

/**
 * Optimized circular reveal modifier utilizing a remembered path and in-place
 * reset/rebuild (SpatialFlow's `circularRevealFrom`).
 */
private fun Modifier.circularRevealFrom(
    progressProvider: () -> Float,
    centerProvider: () -> Offset?,
): Modifier =
    this.drawWithCachePathClip(progressProvider, centerProvider)

private fun Modifier.drawWithCachePathClip(
    progressProvider: () -> Float,
    centerProvider: () -> Offset?,
): Modifier =
    this.drawWithCache {
            val revealPath = Path()
            var lastCenter: Offset? = null
            var lastRadius = -1f
            onDrawWithContent {
                val progress = progressProvider()
                if (progress >= 1f) {
                    drawContent()
                    return@onDrawWithContent
                }
                if (progress <= 0f) {
                    return@onDrawWithContent
                }
                val revealCenter = centerProvider() ?: Offset(size.width / 2f, size.height / 3f)
                if (revealCenter != lastCenter || lastRadius == -1f) {
                    lastCenter = revealCenter
                    lastRadius =
                        maxOf(
                            kotlin.math.hypot(revealCenter.x.toDouble(), revealCenter.y.toDouble()).toFloat(),
                            kotlin.math.hypot((size.width - revealCenter.x).toDouble(), revealCenter.y.toDouble()).toFloat(),
                            kotlin.math.hypot(revealCenter.x.toDouble(), (size.height - revealCenter.y).toDouble()).toFloat(),
                            kotlin.math.hypot((size.width - revealCenter.x).toDouble(), (size.height - revealCenter.y).toDouble()).toFloat(),
                        )
                }
                val radius = lastRadius * progress
                revealPath.reset()
                revealPath.addOval(
                    androidx.compose.ui.geometry.Rect(
                        left = revealCenter.x - radius,
                        top = revealCenter.y - radius,
                        right = revealCenter.x + radius,
                        bottom = revealCenter.y + radius,
                    ),
                )
                clipPath(revealPath, ClipOp.Intersect) {
                    drawContent()
                }
            }
        }

@Composable
internal fun SpatialFlowLyricsOverlay(
    currentSong: MediaMetadata,
    syncedLyrics: List<LyricsEntry>?,
    plainLyrics: String?,
    lyricsProvider: String?,
    currentPositionProvider: () -> Long,
    contentReady: Boolean,
    backgroundBrush: Brush,
    revealProgressProvider: () -> Float,
    revealCenterProvider: () -> Offset?,
    contentColor: Color,
    contentSecondary: Color,
    onSeekTo: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val consumeClicks = remember { MutableInteractionSource() }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
        }
    }

    Box(
        modifier =
            modifier
                .circularRevealFrom(
                    progressProvider = revealProgressProvider,
                    centerProvider = revealCenterProvider,
                ).background(backgroundBrush)
                .clickable(
                    interactionSource = consumeClicks,
                    indication = null,
                    onClick = {},
                ).statusBarsPadding()
                .navigationBarsPadding()
                .padding(vertical = 12.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Centered Title Header Layout
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.size(48.dp))

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.basicMarqueeWithFadedEdges(edgeWidth = 8.dp),
                    ) {
                        Text(
                            text = "LYRICS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = contentColor.copy(alpha = 0.5f),
                            letterSpacing = 1.sp,
                        )
                        AnimatedVisibility(
                            visible = !syncedLyrics.isNullOrEmpty(),
                            enter =
                                fadeIn(
                                    animationSpec =
                                        androidx.compose.animation.core.spring(
                                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                            stiffness = androidx.compose.animation.core.Spring.StiffnessLow,
                                        ),
                                ) + slideInHorizontally(initialOffsetX = { it / 2 }),
                            exit = fadeOut() + slideOutHorizontally(),
                        ) {
                            Text(
                                text = " • Synced Lyrics",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = contentColor.copy(alpha = 0.4f),
                                letterSpacing = 1.sp,
                            )
                        }
                    }
                    Text(
                        text = currentSong.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                        maxLines = 1,
                        modifier = Modifier.basicMarqueeWithFadedEdges(edgeWidth = 8.dp),
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(R.drawable.close),
                        contentDescription = "Close Lyrics",
                        tint = contentColor.copy(alpha = 0.8f),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    !contentReady -> Unit

                    !syncedLyrics.isNullOrEmpty() ->
                        SpatialFlowSyncedLyrics(
                            lyrics = syncedLyrics,
                            currentPositionProvider = currentPositionProvider,
                            contentColor = contentColor,
                            onSeekTo = onSeekTo,
                            modifier = Modifier.fillMaxSize(),
                        )

                    !plainLyrics.isNullOrBlank() ->
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 28.dp),
                        ) {
                            Text(
                                text = plainLyrics,
                                style = MaterialTheme.typography.titleLarge,
                                color = contentColor.copy(alpha = 0.9f),
                            )
                            LyricsMetadataFooter(
                                currentSong = currentSong,
                                selectedProvider = lyricsProvider,
                                contentColor = contentColor,
                            )
                        }

                    else ->
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 32.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "No lyrics found",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = contentColor.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 64.dp, bottom = 12.dp),
                            )
                            Text(
                                text = "Lyrics for this song are not available yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = contentColor.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center,
                            )
                            LyricsMetadataFooter(
                                currentSong = currentSong,
                                selectedProvider = lyricsProvider,
                                contentColor = contentColor,
                            )
                        }
                }
            }
        }
    }
}

/**
 * The synced-lyrics list — SpatialFlow's SyncedLyricsCompose metrics: active
 * line 38sp Bold in the content colour, inactive lines 20sp Medium at 35%
 * alpha, generous 28dp inter-line spacing, tap a line to seek, the list
 * auto-scrolls so the active line stays centred.
 */
@Composable
private fun SpatialFlowSyncedLyrics(
    lyrics: List<LyricsEntry>,
    currentPositionProvider: () -> Long,
    contentColor: Color,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val dimColor = contentColor.copy(alpha = 0.35f)

    val activeIndex by remember {
        derivedStateOf {
            val position = currentPositionProvider()
            var index = -1
            for (i in lyrics.indices) {
                if (lyrics[i].time <= position) index = i else break
            }
            index
        }
    }

    // Auto-scroll: only snap when the active line CHANGES, not every poll.
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            listState.animateScrollToItem(
                index = activeIndex,
                scrollOffset = -200,
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding =
            androidx.compose.foundation.layout.PaddingValues(
                start = 32.dp,
                end = 32.dp,
                top = 48.dp,
                bottom = 96.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(
            items = lyrics,
            key = { index, line -> "$index-${line.time}" },
        ) { index, line ->
            val isActive = index == activeIndex
            Text(
                text = line.text,
                fontSize = if (isActive) 38.sp else 20.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                color = if (isActive) contentColor else dimColor,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSeekTo(line.time) },
            )
        }
    }
}
