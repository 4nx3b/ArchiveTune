/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package moe.rukamori.archivetune.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.HazeState
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.ui.component.IconButton as AppIconButton
import moe.rukamori.archivetune.ui.component.GlassPillTitleText
import moe.rukamori.archivetune.ui.component.LiquidGlassActionPill
import moe.rukamori.archivetune.ui.component.glassSource
import moe.rukamori.archivetune.ui.component.liquidGlassContentColor
import moe.rukamori.archivetune.ui.component.rememberThrottledBackdrop
import com.kyant.backdrop.Backdrop
import moe.rukamori.archivetune.ui.player.LocalPlayerLyricsFullScreen
import moe.rukamori.archivetune.utils.rememberPreference
import androidx.compose.runtime.getValue

@Stable
class GlassScreenHeader(
    val liquidGlassActive: Boolean,
    val backdrop: Backdrop?,
    val haze: HazeState,
)

@Composable
fun rememberGlassScreenHeader(): GlassScreenHeader {
    val liquidGlassEnabled by rememberPreference(LiquidGlassEnabledKey, defaultValue = true)
    val lyricsFullScreen = LocalPlayerLyricsFullScreen.current

    val surfaceColor = MaterialTheme.colorScheme.surface

    val backdrop = rememberThrottledBackdrop(surfaceColor)
    val haze = rememberScreenHeaderHaze()
    val available =
        liquidGlassEnabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val active = available && !lyricsFullScreen
    return GlassScreenHeader(
        liquidGlassActive = active,
        backdrop = if (available) backdrop else null,
        haze = haze,
    )
}

fun Modifier.glassHeaderSource(header: GlassScreenHeader): Modifier =
    this
        .then(
            when (val backdrop = header.backdrop) {
                null -> Modifier
                else -> Modifier.glassSource(backdrop)
            }
        )
        .hazeSource(header.haze)

@Composable
fun BoxScope.GlassScreenHeaderOverlay(
    header: GlassScreenHeader,
    title: String,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
    scrolled: Boolean = true,
    trailing: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
) {
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    ScreenHeaderHaze(
        hazeState = header.haze,
        systemBarsTopPadding = systemBarsTopPadding,
        scrolled = scrolled,
    )

    val backdrop = header.backdrop
    if (!header.liquidGlassActive || backdrop == null) {
        return
    }

    LiquidGlassActionPill(
        backdrop = backdrop,
        interactive = true,
        modifier =
            modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = systemBarsTopPadding + 12.dp),
    ) {
        AppIconButton(
            onClick = onBack,
            onLongClick = onBackLongClick,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.arrow_back),
                contentDescription = title,
                tint = liquidGlassContentColor(),
            )
        }

        GlassPillTitleText(text = title)
    }

    if (onSearch != null || trailing != null) {
        LiquidGlassActionPill(
            backdrop = backdrop,
            modifier =
                modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 12.dp, top = systemBarsTopPadding + 12.dp),
        ) {
            if (onSearch != null) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIconButton(
                        onClick = onSearch,
                        onLongClick = {},
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.search),
                            contentDescription = stringResource(R.string.search),
                            tint = liquidGlassContentColor(),
                        )
                    }
                }
            } else {
                trailing?.invoke(this)
            }
        }
    }
}
