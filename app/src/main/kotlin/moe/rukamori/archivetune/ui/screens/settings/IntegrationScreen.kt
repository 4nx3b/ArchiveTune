/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.DeezerArlKey
import moe.rukamori.archivetune.constants.ListenBrainzEnabledKey
import moe.rukamori.archivetune.constants.ListenBrainzTokenKey
import moe.rukamori.archivetune.constants.AppleMusicMediaUserTokenKey
import moe.rukamori.archivetune.constants.ManualSourceLoginEnabledKey
import moe.rukamori.archivetune.constants.PoolApiKeyKey
import moe.rukamori.archivetune.constants.QobuzTokensKey
import moe.rukamori.archivetune.constants.ShowSpotifyPlaylistsKey
import moe.rukamori.archivetune.constants.TidalAccessTokenKey
import androidx.compose.ui.text.input.TextFieldValue
import moe.rukamori.archivetune.spotify.SpotifyAccountViewModel
import moe.rukamori.archivetune.ui.component.FrostedHeaderPill
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.InfoLabel
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.component.TextFieldDialog
import moe.rukamori.archivetune.ui.menu.CrossServiceImportPlaylistDialog
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.PoolAccountManager
import moe.rukamori.archivetune.utils.rememberPreference
import androidx.compose.foundation.layout.asPaddingValues
import moe.rukamori.archivetune.ui.screens.ScreenHeaderHaze
import moe.rukamori.archivetune.ui.screens.rememberScreenHeaderHaze
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import dev.chrisbanes.haze.hazeSource
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import moe.rukamori.archivetune.ui.component.SettingsPageTopBar
import moe.rukamori.archivetune.constants.DiscordAvatarUrlKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationScreen(
    navController: NavController,
    scrollTo: String? = null,
    spotifyAccountViewModel: SpotifyAccountViewModel = hiltViewModel(),
) {
    val (listenBrainzEnabled, onListenBrainzEnabledChange) = rememberPreference(ListenBrainzEnabledKey, false)
    val (listenBrainzToken) = rememberPreference(ListenBrainzTokenKey, "")

    val (discordAvatarUrl) = rememberPreference(DiscordAvatarUrlKey, "")

    val (manualSourceLogin, _) = rememberPreference(ManualSourceLoginEnabledKey, false)

    val (deezerArl, _) = rememberPreference(DeezerArlKey, "")
    val (tidalAccessToken, _) = rememberPreference(TidalAccessTokenKey, "")
    val (qobuzTokens, _) = rememberPreference(QobuzTokensKey, "")
    val showDeezerRow = manualSourceLogin || deezerArl.isNotBlank()
    val showTidalRow = manualSourceLogin || tidalAccessToken.isNotBlank()
    val showQobuzRow = manualSourceLogin || qobuzTokens.isNotBlank()

    val spotifyState by spotifyAccountViewModel.uiState.collectAsStateWithLifecycle()
    val (showSpotifyPlaylists, onShowSpotifyPlaylistsChange) = rememberPreference(ShowSpotifyPlaylistsKey, false)
    var showSpotifyLogin by rememberSaveable { mutableStateOf(false) }

    var showCrossServiceImport by remember { mutableStateOf(false) }
    var showPoolApiKeyEditor by remember { mutableStateOf(false) }

    val (poolApiKey, onPoolApiKeyChange) = rememberPreference(PoolApiKeyKey, "")
    var poolRefreshing by remember { mutableStateOf(false) }
    var poolRefreshMessage by remember { mutableStateOf<String?>(null) }
    val poolScope = rememberCoroutineScope()
    val poolContext = LocalContext.current

    LaunchedEffect(spotifyState.isAuthenticated) {
        if (spotifyState.isAuthenticated) {
            showSpotifyLogin = false
        }
    }

    val headerHaze = rememberScreenHeaderHaze()
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
                SettingsPageTopBar(
                    titleText = stringResource(R.string.integration),
                    onBack = navController::navigateUp,
                    onBackLongClick = navController::backToMain,
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
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))

                .then(positions.containerModifier())
                .verticalScroll(scrollState)
                .hazeSource(headerHaze)
                .padding(top = topPadding)
                .padding(bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding),
        ) {
            PreferenceGroup(
                modifier = positions.modifierFor("ai_integration"),
                title = stringResource(R.string.ai_integration),
            ) {
                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.ai_integration)) },
                        description = stringResource(R.string.ai_integration_desc),
                        icon = {
                            Icon(
                                painterResource(R.drawable.ai),
                                null,
                                tint = SettingsIconPalette.AiIntegration,
                            )
                        },
                        onClick = { navController.navigate("settings/ai_integration") },
                    )
                }
            }

            PreferenceGroup(
                modifier = positions.modifierFor("discord_presence"),
                title = stringResource(R.string.general),
            ) {
                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("discord_account"),
                        title = { Text(stringResource(R.string.discord_integration)) },
                        icon = { DiscordAccountIcon(avatarUrl = discordAvatarUrl) },
                        onClick = {
                            navController.navigate("settings/discord")
                        },
                    )
                }
            }

            PreferenceGroup(
                modifier = positions.modifierFor("music_sources"),
                title = stringResource(R.string.music_sources),
            ) {
                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("applemusic"),
                        title = { Text(stringResource(R.string.applemusic_settings)) },
                        description = stringResource(R.string.applemusic_helper),
                        icon = { Icon(painterResource(R.drawable.album), null) },
                        onClick = { navController.navigate("settings/applemusic") },
                    )
                }

                item(visible = showTidalRow) {
                    PreferenceEntry(
                        modifier = positions.modifierFor("tidal"),
                        title = { Text(stringResource(R.string.tidal_integration)) },
                        description = stringResource(R.string.tidal_integration_description),
                        icon = {
                            Icon(
                                painterResource(R.drawable.provider_tidal),
                                null,
                                tint = SettingsIconPalette.Tidal,
                            )
                        },
                        onClick = {
                            navController.navigate("settings/tidal")
                        },
                    )
                }

                item(visible = showQobuzRow) {
                    PreferenceEntry(
                        modifier = positions.modifierFor("qobuz"),
                        title = { Text(stringResource(R.string.qobuz_integration)) },
                        description = stringResource(R.string.qobuz_integration_description),
                        icon = {
                            Icon(
                                painterResource(R.drawable.provider_qobuz),
                                null,
                                tint = SettingsIconPalette.Qobuz,
                            )
                        },
                        onClick = {
                            navController.navigate("settings/qobuz")
                        },
                    )
                }

                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("deezer"),
                        title = { Text(stringResource(R.string.deezer_integration)) },
                        description = stringResource(R.string.deezer_integration_description),
                        icon = {
                            Icon(
                                painterResource(R.drawable.provider_deezer),
                                null,
                                tint = SettingsIconPalette.Deezer,
                            )
                        },
                        onClick = {
                            navController.navigate("settings/deezer")
                        },
                    )
                }

                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("telegram"),
                        title = { Text(stringResource(R.string.telegram_integration)) },
                        description = stringResource(R.string.telegram_integration_description),
                        icon = {
                            Icon(
                                painterResource(R.drawable.provider_telegram),
                                null,
                                tint = SettingsIconPalette.Telegram,
                            )
                        },
                        onClick = {
                            navController.navigate("settings/telegram")
                        },
                    )
                }
            }

            PreferenceGroup(

                modifier =
                    positions
                        .modifierFor("external_sources")
                        .then(positions.modifierFor("spotify")),
                title = stringResource(R.string.external_sources),
            ) {
                spotifyAccountPreferences(
                    state = spotifyState,
                    showPlaylists = showSpotifyPlaylists,
                    onConnectClick = { showSpotifyLogin = true },
                    onShowPlaylistsChange = onShowSpotifyPlaylistsChange,
                    onReloadClick = spotifyAccountViewModel::reloadPlaylists,
                    onLogoutClick = { spotifyAccountViewModel.logout() },
                )
            }

            PreferenceGroup(

                modifier =
                    positions
                        .modifierFor("listenbrainz")
                        .then(positions.modifierFor("lastfm_scrobbling")),
                title = stringResource(R.string.scrobbling),
            ) {
                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("lastfm_account"),
                        title = { Text(stringResource(R.string.lastfm_integration)) },
                        icon = {
                            Icon(
                                painterResource(R.drawable.token),
                                null,
                                tint = SettingsIconPalette.LastFm,
                            )
                        },
                        onClick = {
                            navController.navigate("settings/lastfm")
                        },
                    )
                }

                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.listenbrainz_scrobbling)) },
                        description = stringResource(R.string.listenbrainz_scrobbling_description),
                        icon = {
                            Icon(
                                painterResource(R.drawable.token),
                                null,
                                tint = SettingsIconPalette.ListenBrainz,
                            )
                        },
                        checked = listenBrainzEnabled,
                        onCheckedChange = onListenBrainzEnabledChange,
                    )
                }

                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("listenbrainz_token"),
                        title = {
                            Text(
                                if (listenBrainzToken.isBlank()) {
                                    stringResource(R.string.set_listenbrainz_token)
                                } else {
                                    stringResource(R.string.edit_listenbrainz_token)
                                },
                            )
                        },
                        icon = {
                            Icon(
                                painterResource(R.drawable.token),
                                null,
                                tint = SettingsIconPalette.ListenBrainz,
                            )
                        },
                        onClick = { navController.navigate(LISTENBRAINZ_LOGIN_ROUTE) },
                    )
                }
            }

            PreferenceGroup(
                modifier = positions.modifierFor("cross_service_import"),
                title = stringResource(R.string.cross_service_import_playlist_title),
            ) {
                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.cross_service_import_entry_title)) },
                        description = stringResource(R.string.cross_service_import_entry_desc),
                        icon = {
                            Icon(
                                painterResource(R.drawable.playlist_import),
                                null,
                                tint = SettingsIconPalette.CrossServiceImport,
                            )
                        },
                        onClick = { showCrossServiceImport = true },
                    )
                }
            }

            PreferenceGroup(
                modifier = positions.modifierFor("source_pool"),
                title = stringResource(R.string.pool_api_key_title),
            ) {
                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.pool_api_key_label)) },
                        description = if (poolApiKey.isBlank()) {
                            stringResource(R.string.pool_api_key_help)
                        } else {
                            poolApiKey.take(8) + "…"
                        },
                        icon = { Icon(painterResource(R.drawable.token), null) },
                        onClick = { showPoolApiKeyEditor = true },
                    )
                }
                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.pool_get_key_title)) },
                        description = stringResource(R.string.pool_get_key_description),
                        icon = { Icon(painterResource(R.drawable.language), null) },
                        onClick = {
                            runCatching {
                                poolContext.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(BuildConfig.SOURCE_PROVIDER_URL),
                                    ),
                                )
                            }
                        },
                    )
                }
                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.pool_refresh_title)) },
                        description = stringResource(R.string.pool_refresh_description),
                        icon = {
                            if (poolRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(painterResource(R.drawable.sync), null)
                            }
                        },
                        isEnabled = !poolRefreshing && BuildConfig.SOURCE_PROVIDER_URL.isNotBlank(),
                        onClick = {
                            if (poolRefreshing) return@PreferenceEntry
                            poolRefreshing = true
                            poolRefreshMessage = null
                            poolScope.launch(Dispatchers.IO) {
                                val refreshed = PoolAccountManager.refresh(poolContext, force = true)
                                val accounts = PoolAccountManager.tidalAccounts()
                                val message =
                                    if (refreshed) {
                                        poolContext.getString(
                                            R.string.pool_refresh_done,
                                            accounts.size,
                                            PoolAccountManager.qobuzAccounts().size,
                                            PoolAccountManager.deezerAccounts().size,
                                        )
                                    } else {
                                        PoolAccountManager.lastFeedError
                                            ?: poolContext.getString(R.string.pool_refresh_failed)
                                    }
                                withContext(Dispatchers.Main) {
                                    poolRefreshMessage = message
                                    poolRefreshing = false
                                }
                            }
                        },
                    )
                }
                poolRefreshMessage?.let { message ->
                    item {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        ScreenHeaderHaze(
            hazeState = headerHaze,
            systemBarsTopPadding = systemBarsTopPadding,
        )
        }
}

    CrossServiceImportPlaylistDialog(
        isVisible = showCrossServiceImport,
        onDismiss = { showCrossServiceImport = false },
    )

    if (showPoolApiKeyEditor) {
        TextFieldDialog(
            initialTextFieldValue = TextFieldValue(poolApiKey),
            onDone = { key ->
                onPoolApiKeyChange(key.trim())
                showPoolApiKeyEditor = false
            },
            onDismiss = { showPoolApiKeyEditor = false },
            singleLine = true,
            maxLines = 1,
            isInputValid = { true },
            extraContent = {
                InfoLabel(text = stringResource(R.string.pool_api_key_help))
            },
        )
    }

    if (showSpotifyLogin) {
        SpotifyLoginSheet(
            onDismiss = { showSpotifyLogin = false },
            onCookiesCaptured = { spDc, spKey ->
                showSpotifyLogin = false
                spotifyAccountViewModel.connectWithCookies(spDc = spDc, spKey = spKey)
            },
        )
    }

    spotifyState.errorMessage?.let { error ->
        SpotifyErrorDialog(
            message = error,
            onDismiss = spotifyAccountViewModel::dismissError,
        )
    }
}

@Composable
private fun DiscordAccountIcon(avatarUrl: String) {
    val context = LocalContext.current
    val requestPx = with(LocalDensity.current) { 44.dp.roundToPx() }
    val avatarRequest =
        remember(context, avatarUrl, requestPx) {
            avatarUrl
                .takeIf(String::isNotBlank)
                ?.let {
                    ImageRequest
                        .Builder(context)
                        .data(it)
                        .size(requestPx)
                        .build()
                }
        }

    if (avatarRequest == null) {
        Icon(
            painter = painterResource(R.drawable.discord),
            contentDescription = null,
            tint = SettingsIconPalette.DiscordExperimental,
        )
        return
    }

    Box(modifier = Modifier.size(44.dp)) {
        Icon(
            painter = painterResource(R.drawable.discord),
            contentDescription = null,
            tint = SettingsIconPalette.DiscordExperimental,
        )
        AsyncImage(
            model = avatarRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                .fillMaxSize()
                .clip(CircleShape),
        )
    }
}
