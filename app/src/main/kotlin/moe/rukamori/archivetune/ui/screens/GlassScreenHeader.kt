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
import androidx.compose.runtime.getValue
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
import moe.rukamori.archivetune.ui.component.PlatformBackdrop
import moe.rukamori.archivetune.ui.component.layerBackdrop
import moe.rukamori.archivetune.ui.component.liquidGlassContentColor
import moe.rukamori.archivetune.ui.component.rememberBackdrop
import moe.rukamori.archivetune.ui.player.LocalPlayerLyricsFullScreen
import moe.rukamori.archivetune.utils.rememberPreference

@Stable
class GlassScreenHeader(
    val liquidGlassActive: Boolean,
    val backdrop: PlatformBackdrop?,
    val haze: HazeState,
)

@Composable
fun rememberGlassScreenHeader(): GlassScreenHeader {
    val liquidGlassEnabled by rememberPreference(LiquidGlassEnabledKey, defaultValue = false)
    val lyricsFullScreen = LocalPlayerLyricsFullScreen.current
    val surfaceColor = MaterialTheme.colorScheme.surface

    val backdrop = rememberBackdrop(surfaceColor)
    val haze = rememberScreenHeaderHaze()
    val active =
        liquidGlassEnabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !lyricsFullScreen
    return GlassScreenHeader(
        liquidGlassActive = active,
        backdrop = if (active) backdrop else null,
        haze = haze,
    )
}

fun Modifier.glassHeaderSource(header: GlassScreenHeader): Modifier =
    this
        .then(if (header.backdrop != null) Modifier.layerBackdrop(header.backdrop) else Modifier)
        .hazeSource(header.haze)

@Composable
fun BoxScope.GlassScreenHeaderOverlay(
    header: GlassScreenHeader,
    title: String,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
    trailing: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
) {
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    ScreenHeaderHaze(
        hazeState = header.haze,
        systemBarsTopPadding = systemBarsTopPadding,
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
