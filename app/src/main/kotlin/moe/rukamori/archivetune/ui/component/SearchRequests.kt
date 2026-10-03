/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsStateWithLifecycle
import androidx.navigation.NavController

/**
 * Observes the "openSearch" request written by the compact search circle next
 * to the mini player (MainActivity). Screens that own an in-page search
 * affordance register this observer and flip their own search state; every
 * other route keeps the circle's global song-search behaviour.
 *
 * The request is consumed immediately (reset to false) so a returning
 * navigation does not re-trigger the search.
 */
@Composable
fun ObserveOpenSearchRequest(
    navController: NavController,
    onOpenSearch: () -> Unit,
) {
    val openSearchRequest =
        navController.currentBackStackEntry
            ?.savedStateHandle
            ?.getStateFlow("openSearch", false)
            ?.collectAsStateWithLifecycle()

    LaunchedEffect(openSearchRequest?.value) {
        if (openSearchRequest?.value == true) {
            navController.currentBackStackEntry?.savedStateHandle?.set("openSearch", false)
            onOpenSearch()
        }
    }
}
