/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

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
