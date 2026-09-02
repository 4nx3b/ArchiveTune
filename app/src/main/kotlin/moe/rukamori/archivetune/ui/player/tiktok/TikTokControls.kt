/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * TikTok player style — the bottom chrome.
 *
 * What the reference pins under the feed: the track's own progress with its
 * time labels (driven by the app's EXISTING seek callbacks — no side timer,
 * no drift), and the persistent bottom navigation bar over the app's REAL
 * destinations. Tapping a tab collapses the player and navigates with the
 * same popUpTo/saveState/restoreState semantics the main bottom bar uses;
 * the selected tab mirrors the current back-stack hierarchy.
 */

package moe.rukamori.archivetune.ui.player.tiktok

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.navigation.NavController
import androidx.navigation.currentBackStackEntryAsState
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.utils.makeTimeString

/** Height of the persistent navigation bar row (icons + labels). */
internal val TIKTOK_BOTTOM_NAV_HEIGHT = 56.dp

/** Height of the progress row (time labels + bar). */
internal val TIKTOK_PROGRESS_ROW_HEIGHT = 44.dp

@Composable
internal fun TikTokBottomChrome(
    displayPositionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    navController: NavController,
    sheetState: BottomSheetState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        TikTokProgressRow(
            positionMs = displayPositionMs,
            durationMs = durationMs,
            onSeek = onSeek,
            onSeekFinished = onSeekFinished,
        )
        TikTokBottomNavigation(
            navController = navController,
            sheetState = sheetState,
        )
    }
}

/**
 * The feed's scrubber row: current time — bar — duration, TikTok's thin
 * under-caption progress. The drag and tap write through the app's shared
 * seek callbacks, so scrubbing behaves exactly like every other player
 * style's slider (including crossfade-aware seeking).
 */
@Composable
internal fun TikTokProgressRow(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val seekEnabled = durationMs > 0L && durationMs != C.TIME_UNSET

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .height(TIKTOK_PROGRESS_ROW_HEIGHT)
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = makeTimeString(positionMs),
            color = TIKTOK_INACTIVE_GRAY,
            fontSize = 12.sp,
            maxLines = 1,
        )
        Spacer(Modifier.width(10.dp))
        TikTokProgressSlider(
            positionMs = positionMs,
            durationMs = durationMs,
            enabled = seekEnabled,
            onSeek = onSeek,
            onSeekFinished = onSeekFinished,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (seekEnabled) makeTimeString(durationMs) else "—:—",
            color = TIKTOK_INACTIVE_GRAY,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

/**
 * The feed's scrubber: a hairline bar with a thumb that appears under the
 * finger. Horizontal drags and taps both write through the shared seek
 * callbacks; vertical motion never reaches it, so the pager owns the feed
 * gesture exclusively.
 */
@Composable
private fun TikTokProgressSlider(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }
    val fraction =
        if (durationMs > 0L && durationMs != C.TIME_UNSET) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    val displayFraction = if (dragging) dragFraction else fraction

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .height(TIKTOK_SLIDER_TOUCH_HEIGHT)
                .let { m ->
                    if (enabled) {
                        m.pointerInput(durationMs) {
                            detectHorizontalDragGestures(
                                onDragStart = { _ ->
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    dragging = true
                                    dragFraction = fraction
                                },
                                onHorizontalDrag = { change, _ ->
                                    change.consume()
                                    dragFraction =
                                        (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    onSeek((dragFraction * durationMs).toLong())
                                },
                                onDragEnd = {
                                    dragging = false
                                    onSeekFinished()
                                },
                                onDragCancel = {
                                    dragging = false
                                    onSeekFinished()
                                },
                            )
                        }.pointerInput(durationMs) {
                            detectTapGestures(
                                onTap = { offset ->
                                    dragFraction =
                                        (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    onSeek((dragFraction * durationMs).toLong())
                                    onSeekFinished()
                                },
                            )
                        }
                    } else {
                        m
                    }
                },
    ) {
        Canvas(
            modifier =
                Modifier
                    .weight(1f)
                    .height(TIKTOK_SLIDER_IDLE),
        ) {
            val barHeight = TIKTOK_SLIDER_IDLE.toPx()
            val centerY = size.height / 2f
            val trackWidth = size.width
            val corner = CornerRadius(barHeight / 2f, barHeight / 2f)

            // Full track
            drawRoundRect(
                color = Color.White.copy(alpha = 0.28f),
                topLeft = Offset(0f, centerY - barHeight / 2f),
                size = Size(trackWidth, barHeight),
                cornerRadius = corner,
            )
            // Played portion
            val playedWidth = trackWidth * displayFraction
            if (playedWidth > 0f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.95f),
                    topLeft = Offset(0f, centerY - barHeight / 2f),
                    size = Size(playedWidth.coerceAtLeast(barHeight), barHeight),
                    cornerRadius = corner,
                )
            }
            // Thumb (only while scrubbing, like the feed scrubber)
            if (dragging) {
                drawCircle(
                    color = Color.White,
                    radius = 6.dp.toPx(),
                    center = Offset(playedWidth, centerY),
                )
            }
        }
    }
}

/**
 * The persistent bottom navigation over the app's REAL destinations —
 * Home / Search / Library, the same routes and the same navigation
 * semantics (popUpTo + saveState + restoreState) as the main bottom bar.
 * Tapping a tab folds the player back into the mini player and lands on
 * the tab, exactly like tapping a feed-external link in the reference.
 */
@Composable
internal fun TikTokBottomNavigation(
    navController: NavController,
    sheetState: BottomSheetState,
    modifier: Modifier = Modifier,
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val selectedRoutes =
        remember(navBackStackEntry) {
            navBackStackEntry?.destination?.hierarchy?.map { it.route }?.toSet()
                ?: emptySet<String>()
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .height(TIKTOK_BOTTOM_NAV_HEIGHT),
    ) {
        TikTokBottomNavItem(
            label = stringResource(R.string.home),
            iconActive = R.drawable.solar_home_bold,
            iconInactive = R.drawable.solar_home_linear,
            selected = "home" in selectedRoutes,
            route = "home",
            navController = navController,
            sheetState = sheetState,
            modifier = Modifier.weight(1f),
        )
        TikTokBottomNavItem(
            label = stringResource(R.string.search),
            iconActive = R.drawable.solar_magnifer_bold,
            iconInactive = R.drawable.solar_magnifer_linear,
            selected = "search" in selectedRoutes,
            route = "search",
            navController = navController,
            sheetState = sheetState,
            modifier = Modifier.weight(1f),
        )
        TikTokBottomNavItem(
            label = stringResource(R.string.filter_library),
            iconActive = R.drawable.solar_library_bold,
            iconInactive = R.drawable.solar_library_linear,
            selected = "library" in selectedRoutes,
            route = "library",
            navController = navController,
            sheetState = sheetState,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TikTokBottomNavItem(
    label: String,
    iconActive: Int,
    iconInactive: Int,
    selected: Boolean,
    route: String,
    navController: NavController,
    sheetState: BottomSheetState,
    modifier: Modifier = Modifier,
) {
    val onClick =
        remember(navController, sheetState, route) {
            {
                sheetState.collapseSoft()
                navController.navigate(route) {
                    popUpTo(navController.graph.startDestinationId) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier =
            modifier
                .fillMaxHeight()
                .tiktokNoRippleClickable(onClick = onClick)
                .padding(vertical = 6.dp),
    ) {
        Icon(
            painter = painterResource(if (selected) iconActive else iconInactive),
            contentDescription = label,
            tint = if (selected) Color.White else TIKTOK_INACTIVE_GRAY,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = if (selected) Color.White else TIKTOK_INACTIVE_GRAY,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

private val TIKTOK_SLIDER_IDLE = 3.dp
private val TIKTOK_SLIDER_TOUCH_HEIGHT = 30.dp
