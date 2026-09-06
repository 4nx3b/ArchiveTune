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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

/**
 * A screen-scoped Liquid Glass header kit (2026-09-04): the persistent
 * liquid-glass back pill (+ optional search pill) and the header haze, sharing
 * one backdrop and one [HazeState] — the exact pattern the History screen
 * uses, extracted so every page can adopt it with a few lines:
 *
 * ```
 * val glassHeader = rememberGlassScreenHeader()
 * Scaffold(topBar = { if (!glassHeader.liquidGlassActive) { ...normal bar... } }) { innerPadding ->
 *     Box(Modifier.fillMaxSize()) {
 *         LazyColumn(modifier = Modifier.fillMaxSize().glassHeaderSource(glassHeader), ...) { ... }
 *         GlassScreenHeaderOverlay(glassHeader, "Title", onBack = ..., onBackLongClick = ...)
 *     }
 * }
 * ```
 *
 * While Liquid Glass is ON (Android 12+, not in full-screen lyrics), the
 * screen's normal top bar is replaced by the persistent glass pills, the
 * scrolling content is recorded into the screen backdrop (so the pills render
 * real vibrancy glass) and tagged as the haze source, and the
 * [ScreenHeaderHaze] overlay blurs whatever scrolls under the header zone.
 * While OFF, only the haze remains (it needs no glass toggle) and the normal
 * top bar renders.
 */
@Stable
class GlassScreenHeader(
    val liquidGlassActive: Boolean,
    val backdrop: PlatformBackdrop?,
    val haze: HazeState,
)

/** Creates the screen-scoped [GlassScreenHeader] (backdrop + haze + gating). */
@Composable
fun rememberGlassScreenHeader(): GlassScreenHeader {
    val liquidGlassEnabled by rememberPreference(LiquidGlassEnabledKey, defaultValue = false)
    val lyricsFullScreen = LocalPlayerLyricsFullScreen.current
    val surfaceColor = MaterialTheme.colorScheme.surface
    // The backdrop layer is created unconditionally (cheap, a GraphicsLayer
    // holder) but only recorded / sampled while the glass is actually active.
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

/**
 * Applies to the SCROLLING CONTENT ROOT of a screen: records it into the
 * header's backdrop (liquid glass source) and tags it as the haze source.
 * The pills/haze overlay must be a SIBLING of the composable carrying this
 * modifier — never a descendant (nested sampling crashes the RuntimeShader).
 */
fun Modifier.glassHeaderSource(header: GlassScreenHeader): Modifier =
    this
        .then(if (header.backdrop != null) Modifier.layerBackdrop(header.backdrop) else Modifier)
        .hazeSource(header.haze)

/**
 * The pinned header overlay: the progressive header haze plus, in Liquid
 * Glass mode, the persistent translucent glass pills (back + screen title on
 * the leading edge, optional search or custom actions on the trailing edge) —
 * the History screen's behaviour the user asked to replicate ("constant
 * liquid glass navigation pill like history page... same behaviour").
 *
 * Call inside the same Box that hosts the scrolling content, AFTER the
 * content (later sibling = drawn on top).
 */
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

    // The header haze renders in BOTH modes — it only needs the (transparent)
    // header zone, not the Liquid Glass toggle.
    ScreenHeaderHaze(
        hazeState = header.haze,
        systemBarsTopPadding = systemBarsTopPadding,
    )

    val backdrop = header.backdrop
    if (!header.liquidGlassActive || backdrop == null) {
        return
    }

    // Leading pill: back chevron + the screen title, always pinned while the
    // user scrolls. Long-press jumps straight to the Home tab (History
    // behaviour).
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
        // Fixed-length, marquee-scrolling title (2026-09-05): a long screen
        // title used to grow this pill until it collided with the trailing
        // actions pill — the same defect the playlist pages showed.
        GlassPillTitleText(text = title)
    }

    // Trailing pill: search for screens that have a search affordance, or a
    // custom row of actions (e.g. Music Recognition's history + settings).
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
