/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.menu

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kyant.backdrop.Backdrop
import moe.rukamori.archivetune.ui.component.NewAction
import moe.rukamori.archivetune.ui.component.PlatformBackdrop

@Composable
fun rememberCastPlayerMenuAction(@Suppress("UNUSED_PARAMETER") renderSheet: Boolean = true): NewAction? = null

@Composable
fun CastRoutePickerGlassOverlay(
    @Suppress("UNUSED_PARAMETER") backdrop: PlatformBackdrop?,
    @Suppress("UNUSED_PARAMETER") eligible: Boolean,
) {
}

@Composable
fun CastRoutePickerRootOverlay(
    @Suppress("UNUSED_PARAMETER") backdrop: Backdrop?,
    @Suppress("UNUSED_PARAMETER") modifier: Modifier = Modifier,
) {
}
