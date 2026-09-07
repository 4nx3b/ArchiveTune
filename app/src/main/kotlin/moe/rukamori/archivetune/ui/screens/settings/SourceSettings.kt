/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Top-level "Sources" settings screen. Hosts independent catalog-source selectors (metadata and
 * search) plus the streaming-source preference groups (priority, toggles and quality). Manual
 * account/instance sign-in still lives in Integration (gated).
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.R
import android.content.Intent
import android.net.Uri
import moe.rukamori.archivetune.constants.PoolApiKeyKey
import moe.rukamori.archivetune.tidal.TidalInstanceHealthManager
import moe.rukamori.archivetune.ui.component.FrostedHeaderPill
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.PoolAccountManager
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.ui.component.EditTextPreference
import androidx.compose.foundation.layout.asPaddingValues
import moe.rukamori.archivetune.ui.screens.ScreenHeaderHaze
import moe.rukamori.archivetune.ui.screens.rememberScreenHeaderHaze
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import dev.chrisbanes.haze.hazeSource
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceSettings(navController: NavController, scrollTo: String? = null) {

    val headerHaze = rememberScreenHeaderHaze()
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    FrostedHeaderPill(plain = true) {
                        IconButton(
                            onClick = navController::navigateUp,
                            onLongClick = navController::backToMain,
                        ) {
                            Icon(
                                painterResource(R.drawable.arrow_back),
                                contentDescription = null,
                            )
                        }
                        Text(
                            text = stringResource(R.string.source_settings),
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {

        val playerAwareBottomPadding =
            LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()
        val topPadding = innerPadding.calculateTopPadding()
        val scrollState = rememberScrollState()
        val positions = rememberPreferencePositions()

        LaunchedEffect(scrollTo) { positions.scrollToKey(scrollTo, scrollState) }

        Column(
            Modifier
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(
                        WindowInsetsSides.Horizontal,
                    ),
                )

                .then(positions.containerModifier())
                .verticalScroll(scrollState)
                .hazeSource(headerHaze)
                .padding(top = topPadding)
                .padding(bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding),
        ) {

            PoolApiKeySection(positions)

            PoolRefreshSection(positions)

            PlaybackSourceSections(
                navController = navController,
                positions = positions,
            )
        }

        ScreenHeaderHaze(
            hazeState = headerHaze,
            systemBarsTopPadding = systemBarsTopPadding,
        )
        }
}
}

@Composable
private fun PoolApiKeySection(positions: PreferencePositions) {
    if (!PoolAccountManager.isEnabled) return

    val context = LocalContext.current
    val (apiKey, onApiKeyChange) = rememberPreference(PoolApiKeyKey, "")

    PreferenceGroup(
        modifier = positions.modifierFor("pool_api_key"),
        title = stringResource(R.string.pool_api_key_title),
    ) {
        item {
            EditTextPreference(
                modifier = positions.modifierFor("pool_api_key_value"),
                title = { Text(stringResource(R.string.pool_api_key_label)) },
                icon = { Icon(painterResource(R.drawable.token), null) },
                value = apiKey,
                onValueChange = onApiKeyChange,
                singleLine = true,
                isInputValid = { true },
            )
        }
        item {
            PreferenceEntry(
                modifier = positions.modifierFor("pool_get_api_key"),
                title = { Text(stringResource(R.string.pool_get_key_title)) },
                description = stringResource(R.string.pool_get_key_description),
                icon = { Icon(painterResource(R.drawable.website), null) },
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.SOURCE_PROVIDER_URL)),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun PoolRefreshSection(positions: PreferencePositions) {
    if (!PoolAccountManager.isEnabled) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    PreferenceGroup(
        modifier = positions.modifierFor("youtube_music"),
    ) {
        item {
            PreferenceEntry(
                title = {
                    Text(
                        if (refreshing) {
                            stringResource(R.string.pool_refreshing)
                        } else {
                            stringResource(R.string.pool_refresh_title)
                        },
                    )
                },
                icon = { Icon(painterResource(R.drawable.sync), null) },
                trailingContent =
                    if (refreshing) {
                        { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
                    } else {
                        null
                    },
                isEnabled = !refreshing,
                onClick = {
                    if (refreshing) return@PreferenceEntry
                    refreshing = true
                    scope.launch {
                        val ok =
                            withContext(Dispatchers.IO) {

                                val accountsOk = PoolAccountManager.refresh(context, force = true)

                                runCatching {
                                    TidalInstanceHealthManager.refresh(
                                        context,
                                        includeDiscovery = true,
                                        staggered = false,
                                    )
                                }
                                accountsOk
                            }

                        val poolError = PoolAccountManager.lastFeedError
                        val message =
                            when {
                                poolError != null ->
                                    context.getString(R.string.pool_refresh_failed) + "\n" + poolError
                                ok ->
                                    context.getString(
                                        R.string.pool_refresh_done,
                                        PoolAccountManager.tidalAccounts().size,
                                        PoolAccountManager.qobuzAccounts().size,

                                        PoolAccountManager.deezerAccounts().size,
                                    )
                                else -> context.getString(R.string.pool_refresh_failed)
                            }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        refreshing = false
                    }
                },
            )
        }

    }
}
