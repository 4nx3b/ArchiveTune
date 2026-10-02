/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.constants.EnableHapticFeedbackKey
import moe.rukamori.archivetune.ui.utils.isScrollingUp
import moe.rukamori.archivetune.utils.rememberPreference

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HideOnScrollFAB(
    visible: Boolean = true,
    lazyListState: LazyListState,
    @DrawableRes icon: Int,
    label: String,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    onClick: () -> Unit,
) {
    val animationsDisabled = LocalAnimationsDisabled.current
    AnimatedVisibility(
        visible = visible && lazyListState.isScrollingUp(),
        enter = slideInVertically(animationSpec = tween(if (animationsDisabled) 0 else 220)) { it },
        exit = slideOutVertically(animationSpec = tween(if (animationsDisabled) 0 else 220)) { it },
        modifier =
            modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            ),
    ) {
        HideOnScrollFabButton(
            icon = icon,
            label = label,
            backdrop = backdrop,
            onClick = onClick,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BoxScope.HideOnScrollFAB(
    visible: Boolean = true,
    lazyListState: LazyListState,
    @DrawableRes icon: Int,
    label: String,
    backdrop: Backdrop? = null,
    onClick: () -> Unit,
) {
    HideOnScrollFAB(
        visible = visible,
        lazyListState = lazyListState,
        icon = icon,
        label = label,
        modifier = Modifier.align(Alignment.BottomEnd),
        backdrop = backdrop,
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BoxScope.HideOnScrollFAB(
    visible: Boolean = true,
    lazyListState: LazyGridState,
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
) {
    val animationsDisabled = LocalAnimationsDisabled.current
    AnimatedVisibility(
        visible = visible && lazyListState.isScrollingUp(),
        enter = slideInVertically(animationSpec = tween(if (animationsDisabled) 0 else 220)) { it },
        exit = slideOutVertically(animationSpec = tween(if (animationsDisabled) 0 else 220)) { it },
        modifier =
            Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current
                        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                ),
    ) {
        HideOnScrollFabButton(
            icon = icon,
            label = label,
            onClick = onClick,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BoxScope.HideOnScrollFAB(
    visible: Boolean = true,
    scrollState: ScrollState,
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
) {
    val animationsDisabled = LocalAnimationsDisabled.current
    AnimatedVisibility(
        visible = visible && scrollState.isScrollingUp(),
        enter = slideInVertically(animationSpec = tween(if (animationsDisabled) 0 else 220)) { it },
        exit = slideOutVertically(animationSpec = tween(if (animationsDisabled) 0 else 220)) { it },
        modifier =
            Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current
                        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                ),
    ) {
        HideOnScrollFabButton(
            icon = icon,
            label = label,
            onClick = onClick,
        )
    }
}

@Composable
private fun HideOnScrollFabButton(
    @DrawableRes icon: Int,
    label: String,
    backdrop: Backdrop? = null,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    val (enableHapticFeedback) = rememberPreference(EnableHapticFeedbackKey, true)

    // Liquid-glass variant: pages that already own a glass backdrop (artist
    // hero) pass it here so the switch pill matches the header glass instead
    // of a solid container. Falls back to the classic ExtendedFAB whenever no
    // backdrop is supplied, so every other call site is unchanged. Content
    // color adapts to surface luminance (dark/light) via liquidGlassContentColor.
    if (backdrop != null) {
        LiquidGlassActionPill(
            backdrop = backdrop,
            interactive = true,
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier =
                    Modifier
                        .height(48.dp)
                        .clickable(
                            onClick = {
                                if (enableHapticFeedback) {
                                    view.performHapticFeedback(
                                        android.view.HapticFeedbackConstants.CONTEXT_CLICK,
                                        android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                                    )
                                }
                                onClick()
                            },
                        )
                        .padding(horizontal = 18.dp),
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = liquidGlassContentColor(),
                )
                Text(
                    text = label,
                    color = liquidGlassContentColor(),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
        return
    }

    ExtendedFloatingActionButton(
        modifier = Modifier.padding(16.dp),
        onClick = {
            if (enableHapticFeedback) {
                view.performHapticFeedback(
                    android.view.HapticFeedbackConstants.CONTEXT_CLICK,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                )
            }
            onClick()
        },
        icon = {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
            )
        },
        text = { Text(label) },
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}
