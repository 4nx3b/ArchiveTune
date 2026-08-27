/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R

/**
 * A frosted-glass pill that sits at the top-start of the redesigned History,
 * Liked Songs, Cached Songs, and Playlist pages.
 *
 * Visually it matches the user's iOS Music reference screenshot: a translucent
 * dark pill containing a left-pointing chevron followed by the text
 * "Library". Tapping it calls [onClick] (typically `navController.navigateUp()`
 * to pop back to the Library tab); long-pressing it calls [onLongClick]
 * (typically `navController.backToMain()` to jump straight to the Home tab).
 *
 * The pill is built on top of [FrostedHeaderPill] so it inherits the same
 * translucent `surfaceContainer` background that the rest of the app's
 * header pills use — keeping the visual language consistent across the
 * redesigned screens and the existing Library / Apple Music player surfaces.
 *
 * The "Library" label is hardcoded to the `R.string.library` resource so
 * callers don't have to thread a string through. If a screen needs a
 * different back-label (e.g. "Home" or "Search"), it should pass a different
 * composable as its `navigationIcon` rather than reuse this pill.
 *
 * @param onClick Tap action — typically `navController.navigateUp()`.
 * @param onLongClick Long-press action — typically `navController.backToMain()`.
 *                   Pass null to disable long-press (no ripple effect).
 * @param modifier Modifier for the outer pill layout.
 */
@Composable
fun LibraryBackPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    FrostedHeaderPill(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The local `IconButton` (in the same package) supports both
            // onClick and onLongClick — matching the back-button semantics
            // used elsewhere in the app (tap to navigate up, long-press to
            // jump to the Home tab). The Material3 `IconButton` is shadowed
            // here, so we reference the local overload directly.
            IconButton(
                onClick = onClick,
                onLongClick = onLongClick ?: {},
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = stringResource(R.string.library),
                )
            }
            // Tight, non-tappable "Library" label sitting immediately to the
            // right of the back chevron. The whole pill is rendered on top of
            // the page's scrolling content via a translucent surface, so the
            // text color should match the page's foreground color to stay
            // readable on both dark and light backgrounds.
            Text(
                text = stringResource(R.string.library),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }
}

/**
 * A circular floating Home-icon dock button rendered in the same frosted
 * liquid-glass style as [FrostedHeaderPill]. Designed to sit at the
 * bottom-start corner of the redesigned History / Liked / Cached / Playlist
 * pages, matching the iOS Music reference screenshot that shows a circular
 * frosted "Home" pill at the bottom-left.
 *
 * Tapping it calls [onClick] — typically `navController.backToMain()` so the
 * user can jump straight back to the Home tab from anywhere in a long
 * playlist or history list, without having to scroll back to the top to
 * reach the top-start back pill.
 *
 * The icon color is the same pink/red accent ([AppleMusicStyleAccentColor])
 * used across the redesigned hero surfaces so the Home pill visually pairs
 * with the Play/Shuffle pill accents in the hero above.
 *
 * The pill is a fixed 48dp circle so it matches the touch-target size of
 * the other liquid-glass icon buttons in the app. Callers should position it
 * (e.g. with `Modifier.align(Alignment.BottomStart)`) and apply their own
 * bottom inset padding so the button sits above the floating navigation
 * toolbar / mini-player.
 *
 * **Liquid glass mode:** Pass a non-null [backdrop] (typically created via
 * [rememberBackdrop] and applied to a sibling `LazyColumn` via
 * [Modifier.layerBackdrop]) to switch the dock to real kyant `drawBackdrop`
 * rendering (vibrancy + blur + lens), matching the top-start
 * `LiquidGlassActionPill` in `LocalPlaylistScreen`. The dock MUST be a
 * sibling of the composable carrying `layerBackdrop` — nesting inside the
 * source crashes the RuntimeShader. When [backdrop] is `null` (the
 * default), the dock degrades to the translucent `surfaceContainer` surface.
 *
 * @param onClick Tap action — typically `navController.backToMain()`.
 * @param modifier Modifier for the outer layout.
 * @param iconRes The home icon drawable. Defaults to `R.drawable.home_filled`
 *                so the dock button reads as a filled pink home glyph at
 *                rest, matching the reference.
 * @param backdrop Optional kyant `LayerBackdrop` to sample for real liquid
 *                glass. Pass `null` (default) to use the translucent surface
 *                fallback.
 */
@Composable
fun LibraryHomeDockButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int = R.drawable.home_filled,
    backdrop: PlatformBackdrop? = null,
) {
    if (backdrop != null) {
        // Real liquid glass path — same kyant `drawBackdrop` effect stack
        // (vibrancy + blur + lens) used by the top-start LiquidGlassActionPill
        // in LocalPlaylistScreen. The dock samples whatever was recorded into
        // the backdrop by `Modifier.layerBackdrop(backdrop)` applied to a
        // sibling composable (the LazyColumn carrying the scrolling content).
        // MUST be a sibling — nesting inside the source crashes the
        // RuntimeShader.
        Box(
            modifier =
                modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .liquidGlass(
                        backdrop = backdrop,
                        shape = CircleShape,
                        interactive = false,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = onClick,
                onLongClick = {},
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = stringResource(R.string.home),
                    tint = AppleMusicStyleAccentColor,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    } else {
        // Fallback path: translucent surface (no real backdrop blur).
        val baseColor = MaterialTheme.colorScheme.surfaceContainer
        Surface(
            modifier =
                modifier
                    .size(48.dp)
                    .clip(CircleShape),
            shape = CircleShape,
            color = baseColor.copy(alpha = 0.55f),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                // Use the local `IconButton` (same package) which supports both
                // onClick and onLongClick — pass an empty long-click since the
                // Home dock only needs the tap action.
                IconButton(
                    onClick = onClick,
                    onLongClick = {},
                ) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = stringResource(R.string.home),
                        tint = AppleMusicStyleAccentColor,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/**
 * A scroll-aware vertical gradient overlay that fades the bottom edge of a
 * scrolling list into the page background. The gradient is anchored to the
 * bottom of the host Box, has a fixed height (default 96dp), and is only
 * visible while the user is actively scrolling — matching the iOS Music
 * reference where the bottom of the song list gently blurs/fades into the
 * floating navigation area as the user scrolls.
 *
 * Behavior:
 *   • When [visible] is `true`, the gradient paints from transparent at the
 *     top to [fadeColor] (default: page surface color) at the bottom.
 *   • When [visible] is `false`, the overlay is fully transparent and
 *     consumes no input events.
 *
 * Caller is responsible for computing the [visible] flag from the host
 * LazyListState (e.g. `lazyListState.firstVisibleItemIndex > 0 ||
 * lazyListState.firstVisibleItemScrollOffset > 0`).
 *
 * @param visible Whether the fade should currently be visible.
 * @param fadeColor The color the fade should blend into. Default is the page
 *                  surface color, which makes the bottom of the scrolling
 *                  list dissolve into the page background.
 * @param height The height of the gradient band.
 * @param modifier Modifier for the overlay (caller should usually pass
 *                  `Modifier.align(Alignment.BottomCenter).fillMaxWidth()`).
 */
@Composable
fun BottomFadeOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    fadeColor: Color = MaterialTheme.colorScheme.surface,
    height: Dp = 96.dp,
) {
    if (!visible) return
    Box(
        modifier =
            modifier
                .height(height)
                .background(
                    brush =
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    fadeColor.copy(alpha = 0f),
                                    fadeColor.copy(alpha = 0.6f),
                                    fadeColor,
                                ),
                        ),
                ),
    )
}
