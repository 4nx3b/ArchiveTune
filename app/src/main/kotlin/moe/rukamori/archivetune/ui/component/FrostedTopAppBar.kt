/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R

@Composable
fun FrostedTopAppBar(
    titleRes: Int,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit = {},
    actions: (@Composable () -> Unit)? = null,
    backdrop: PlatformBackdrop? = null,
) {
    FrostedTopAppBar(
        title = { Text(stringResource(titleRes)) },
        onBack = onBack,
        onBackLongClick = onBackLongClick,
        actions = actions,
        backdrop = backdrop,
    )
}

@Composable
fun FrostedTopAppBar(
    title: @Composable () -> Unit,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit = {},
    actions: (@Composable () -> Unit)? = null,
    backdrop: PlatformBackdrop? = null,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        title = {
            FrostedHeaderPill(backdrop = backdrop) {
                title()
            }
        },
        navigationIcon = {
            FrostedHeaderPill(backdrop = backdrop) {
                IconButton(
                    onClick = onBack,
                    onLongClick = onBackLongClick,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            }
        },
        actions = if (actions != null) {
            {
                FrostedHeaderPill(
                    modifier = Modifier.padding(end = 8.dp),
                    backdrop = backdrop,
                ) {
                    actions()
                }
            }
        } else {
            {}
        },
    )
}

@Composable
fun LargeFrostedTopAppBar(
    titleRes: Int,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit = {},
    actions: (@Composable () -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    backdrop: PlatformBackdrop? = null,
) {
    LargeFlexibleTopAppBar(
        colors = TopAppBarDefaults.largeTopAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
        title = {
            FrostedHeaderPill(backdrop = backdrop) {
                Text(
                    text = stringResource(titleRes),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        },
        navigationIcon = {
            FrostedHeaderPill(backdrop = backdrop) {
                IconButton(
                    onClick = onBack,
                    onLongClick = onBackLongClick,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            }
        },
        actions = if (actions != null) {
            {
                FrostedHeaderPill(
                    modifier = Modifier.padding(end = 8.dp),
                    backdrop = backdrop,
                ) {
                    actions()
                }
            }
        } else {
            {}
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
fun LargeFrostedTopAppBar(
    title: @Composable () -> Unit,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit = {},
    actions: (@Composable () -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    backdrop: PlatformBackdrop? = null,
) {
    LargeFlexibleTopAppBar(
        colors = TopAppBarDefaults.largeTopAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
        title = {
            FrostedHeaderPill(backdrop = backdrop) {
                title()
            }
        },
        navigationIcon = {
            FrostedHeaderPill(backdrop = backdrop) {
                IconButton(
                    onClick = onBack,
                    onLongClick = onBackLongClick,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            }
        },
        actions = if (actions != null) {
            {
                FrostedHeaderPill(
                    modifier = Modifier.padding(end = 8.dp),
                    backdrop = backdrop,
                ) {
                    actions()
                }
            }
        } else {
            {}
        },
        scrollBehavior = scrollBehavior,
    )
}
