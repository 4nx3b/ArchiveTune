/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    thumbnail: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    HomeFeedSectionHeader(
        title = title,
        subtitle = label.orEmpty(),
        thumbnail = thumbnail,
        leadingIcon = leadingIcon,
        onClick = onClick,
        modifier = modifier,
    )
}
