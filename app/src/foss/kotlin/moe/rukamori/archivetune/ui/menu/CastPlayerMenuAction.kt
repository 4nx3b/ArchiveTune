/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.menu

import androidx.compose.runtime.Composable
import moe.rukamori.archivetune.ui.component.NewAction
import moe.rukamori.archivetune.ui.component.PlatformBackdrop

@Composable
fun rememberCastPlayerMenuAction(@Suppress("UNUSED_PARAMETER") renderSheet: Boolean = true): NewAction? = null

/**
 * Foss flavor stub for the gms real-time liquid-glass Cast route picker —
 * the foss build ships no Cast support, so there is nothing to render.
 */
@Composable
fun CastRoutePickerGlassOverlay(
    @Suppress("UNUSED_PARAMETER") backdrop: PlatformBackdrop?,
    @Suppress("UNUSED_PARAMETER") eligible: Boolean,
) {
}
