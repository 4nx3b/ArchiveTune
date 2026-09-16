/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package moe.rukamori.archivetune.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.flow.collectLatest
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.androidauto.AndroidAutoActionSlot
import moe.rukamori.archivetune.androidauto.AndroidAutoConnectionStatus
import moe.rukamori.archivetune.androidauto.AndroidAutoCustomAction
import moe.rukamori.archivetune.androidauto.AndroidAutoSettingsSnapshot
import moe.rukamori.archivetune.constants.AppBarHeight
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.ListPreference
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.component.glassAwareSurface
import moe.rukamori.archivetune.ui.screens.GlassScreenHeaderOverlay
import moe.rukamori.archivetune.ui.screens.ScreenHeaderHaze
import moe.rukamori.archivetune.ui.screens.glassHeaderSource
import moe.rukamori.archivetune.ui.screens.rememberGlassScreenHeader
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.viewmodels.AndroidAutoSettingsAction
import moe.rukamori.archivetune.viewmodels.AndroidAutoSettingsEvent
import moe.rukamori.archivetune.viewmodels.AndroidAutoSettingsState
import moe.rukamori.archivetune.viewmodels.AndroidAutoSettingsUiModel
import moe.rukamori.archivetune.viewmodels.AndroidAutoSettingsViewModel

private val primaryAndroidAutoActions =
    AndroidAutoCustomAction.entries.filterNot { it == AndroidAutoCustomAction.NONE }
private val secondaryAndroidAutoActions = AndroidAutoCustomAction.entries

@Composable
fun AndroidAutoSettings(
    navController: NavController,
    scrollTo: String? = null,
    viewModel: AndroidAutoSettingsViewModel = hiltViewModel(),
) {
    val onBack = remember(navController) { { navController.navigateUp(); Unit } }
    val onBackLongClick = remember(navController) { { navController.backToMain(); Unit } }
    AndroidAutoSettingsRoute(
        onBack = onBack,
        onBackLongClick = onBackLongClick,
        viewModel = viewModel,
        scrollTo = scrollTo,
    )
}

@Composable
fun AndroidAutoSettingsRoute(
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
    viewModel: AndroidAutoSettingsViewModel = hiltViewModel(),
    scrollTo: String? = null,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onAction = remember(viewModel) { viewModel::onAction }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onAction(AndroidAutoSettingsAction.PermissionResult) }
    LaunchedEffect(viewModel, context, permissionLauncher) {
        viewModel.events.collectLatest { event ->
            when (event) {
                AndroidAutoSettingsEvent.RequestAudioPermission -> permissionLauncher.launch(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Manifest.permission.READ_MEDIA_AUDIO
                    } else {
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    },
                )
                AndroidAutoSettingsEvent.OpenAppPermissions -> runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }.onFailure {
                    onAction(AndroidAutoSettingsAction.ExternalActionFailed)
                }
            }
        }
    }
    AndroidAutoSettingsContent(
        state = state,
        onAction = onAction,
        onBack = onBack,
        onBackLongClick = onBackLongClick,
        scrollTo = scrollTo,
    )
}

@Composable
private fun AndroidAutoSettingsContent(
    state: AndroidAutoSettingsState,
    onAction: (AndroidAutoSettingsAction) -> Unit,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
    scrollTo: String? = null,
    modifier: Modifier = Modifier,
) {
    // Settings-main-page recipe: the scrolling preferences are the haze/backdrop
    // source and extend behind the header row, a progressive ScreenHeaderHaze
    // band fades over the status bar and the header is a liquid-glass round
    // back button plus a centred title when the liquid-glass look is enabled.
    // The header row sits flush below the status bar (no double inset) and the
    // content behind it is real scrolling content, so the glass reads
    // translucent instead of sampling an opaque empty surface.
    val glassHeader = rememberGlassScreenHeader()
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    Scaffold(
        modifier = modifier,
        containerColor = glassAwareSurface(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            when (state) {
                AndroidAutoSettingsState.Loading -> Box(
                    Modifier
                        .fillMaxSize()
                        .glassHeaderSource(glassHeader),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                is AndroidAutoSettingsState.Success -> AndroidAutoSettingsBody(
                    model = state.model,
                    onAction = onAction,
                    scrollTo = scrollTo,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .glassHeaderSource(glassHeader)
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Horizontal +
                                        WindowInsetsSides.Bottom,
                                ),
                            ),
                )
                AndroidAutoSettingsState.Empty -> AndroidAutoSettingsFailure(
                    onAction,
                    Modifier
                        .fillMaxSize()
                        .glassHeaderSource(glassHeader),
                )
                is AndroidAutoSettingsState.Error -> AndroidAutoSettingsFailure(
                    onAction = onAction,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .glassHeaderSource(glassHeader),
                    messageRes = state.messageRes,
                )
            }

            // Glass header exactly like the home screen: the back button AND
            // the title live together inside one liquid-glass pill, floating
            // over the scrolling content with the ScreenHeaderHaze band over
            // the status bar. Without glass: a plain flush app bar row.
            val backdrop = glassHeader.backdrop
            if (backdrop != null) {
                GlassScreenHeaderOverlay(
                    header = glassHeader,
                    title = stringResource(R.string.android_auto),
                    onBack = onBack,
                    onBackLongClick = onBackLongClick,
                )
            } else {
                ScreenHeaderHaze(
                    hazeState = glassHeader.haze,
                    systemBarsTopPadding = systemBarsTopPadding,
                )
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(top = systemBarsTopPadding)
                            .height(AppBarHeight),
                ) {
                    Text(
                        text = stringResource(R.string.android_auto),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    IconButton(
                        onClick = onBack,
                        onLongClick = onBackLongClick,
                        modifier =
                            Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 12.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = stringResource(R.string.back_button_desc),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AndroidAutoSettingsBody(
    model: AndroidAutoSettingsUiModel,
    onAction: (AndroidAutoSettingsAction) -> Unit,
    scrollTo: String? = null,
    modifier: Modifier = Modifier,
) {
    val configuration = model.snapshot.configuration
    val positions = rememberPreferencePositions()
    val scrollState = rememberScrollState()
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current
    LaunchedEffect(scrollTo, model) { positions.scrollToKey(scrollTo, scrollState) }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .then(positions.containerModifier())
                .padding(bottom = SettingsDimensions.ScreenBottomPadding),
    ) {
        // Content scrolls behind the header row, like the settings main page.
        Spacer(Modifier.height(systemBarsTopPadding + AppBarHeight + 8.dp))

        AndroidAutoConnectionPreferences(snapshot = model.snapshot, onAction = onAction)

        PreferenceGroup(title = stringResource(R.string.android_auto_content)) {
            item {
                SwitchPreference(
                    modifier = positions.modifierFor("android_auto_online_recommendations"),
                    title = { Text(stringResource(R.string.android_auto_online_recommendations)) },
                    description = stringResource(R.string.android_auto_online_recommendations_desc),
                    icon = { Icon(painterResource(R.drawable.discover_tune), null) },
                    checked = configuration.onlineRecommendations,
                    onCheckedChange = remember(onAction) {
                        { onAction(AndroidAutoSettingsAction.SetOnlineRecommendations(it)) }
                    },
                    isEnabled = !model.busy,
                )
            }
            item {
                SwitchPreference(
                    modifier = positions.modifierFor("android_auto_online_voice_search"),
                    title = { Text(stringResource(R.string.android_auto_online_voice_search)) },
                    description = stringResource(R.string.android_auto_online_voice_search_desc),
                    icon = { Icon(painterResource(R.drawable.mic), null) },
                    checked = configuration.onlineVoiceSearch,
                    onCheckedChange = remember(onAction) {
                        { onAction(AndroidAutoSettingsAction.SetOnlineVoiceSearch(it)) }
                    },
                    isEnabled = !model.busy,
                )
            }
            item {
                SwitchPreference(
                    modifier = positions.modifierFor("android_auto_local_songs"),
                    title = { Text(stringResource(R.string.android_auto_local_songs)) },
                    description =
                        stringResource(
                            if (configuration.localSongs && !model.snapshot.hasLocalAudioPermission) {
                                R.string.android_auto_audio_permission_missing
                            } else {
                                R.string.android_auto_local_songs_desc
                            },
                        ),
                    icon = { Icon(painterResource(R.drawable.library_music), null) },
                    checked = configuration.localSongs,
                    onCheckedChange = remember(onAction, model.snapshot.hasLocalAudioPermission) {
                        { enabled ->
                            onAction(AndroidAutoSettingsAction.SetLocalSongs(enabled))
                            if (enabled && !model.snapshot.hasLocalAudioPermission) {
                                onAction(AndroidAutoSettingsAction.RequestAudioPermission)
                            }
                        }
                    },
                    isEnabled = !model.busy,
                )
            }
        }

        PreferenceGroup(title = stringResource(R.string.android_auto_data)) {
            item {
                SwitchPreference(
                    modifier = positions.modifierFor("android_auto_metered_playback"),
                    title = { Text(stringResource(R.string.android_auto_metered_playback)) },
                    description = stringResource(R.string.android_auto_metered_playback_desc),
                    icon = { Icon(painterResource(R.drawable.android_cell), null) },
                    checked = configuration.meteredPlayback,
                    onCheckedChange = remember(onAction) {
                        { onAction(AndroidAutoSettingsAction.SetMeteredPlayback(it)) }
                    },
                    isEnabled = !model.busy,
                )
            }
            item {
                SwitchPreference(
                    modifier = positions.modifierFor("android_auto_metered_artwork"),
                    title = { Text(stringResource(R.string.android_auto_metered_artwork)) },
                    description = stringResource(R.string.android_auto_metered_artwork_desc),
                    icon = { Icon(painterResource(R.drawable.image), null) },
                    checked = configuration.meteredArtwork,
                    onCheckedChange = remember(onAction) {
                        { onAction(AndroidAutoSettingsAction.SetMeteredArtwork(it)) }
                    },
                    isEnabled = !model.busy,
                )
            }
        }

        PreferenceGroup(title = stringResource(R.string.android_auto_controls)) {
            item {
                ListPreference(
                    modifier = positions.modifierFor("android_auto_primary_action"),
                    title = { Text(stringResource(R.string.android_auto_primary_action)) },
                    icon = {
                        Icon(
                            painterResource(configuration.primaryAction.iconResource()),
                            contentDescription = null,
                        )
                    },
                    selectedValue = configuration.primaryAction,
                    values = primaryAndroidAutoActions,
                    valueText = { stringResource(it.labelResource()) },
                    onValueSelected = remember(onAction) {
                        { action ->
                            onAction(
                                AndroidAutoSettingsAction.SetAction(
                                    slot = AndroidAutoActionSlot.PRIMARY,
                                    action = action,
                                ),
                            )
                        }
                    },
                    isEnabled = !model.busy,
                )
            }
            item {
                ListPreference(
                    modifier = positions.modifierFor("android_auto_secondary_action"),
                    title = { Text(stringResource(R.string.android_auto_secondary_action)) },
                    icon = {
                        Icon(
                            painterResource(configuration.secondaryAction.iconResource()),
                            contentDescription = null,
                        )
                    },
                    selectedValue = configuration.secondaryAction,
                    values = secondaryAndroidAutoActions,
                    valueText = { stringResource(it.labelResource()) },
                    onValueSelected = remember(onAction) {
                        { action ->
                            onAction(
                                AndroidAutoSettingsAction.SetAction(
                                    slot = AndroidAutoActionSlot.SECONDARY,
                                    action = action,
                                ),
                            )
                        }
                    },
                    isEnabled = !model.busy,
                )
            }
        }
    }
}

@Composable
private fun AndroidAutoConnectionPreferences(
    snapshot: AndroidAutoSettingsSnapshot,
    onAction: (AndroidAutoSettingsAction) -> Unit,
) {
    PreferenceGroup(title = stringResource(R.string.android_auto_connection)) {
        item {
            PreferenceEntry(
                title = { Text(stringResource(R.string.android_auto)) },
                description =
                    stringResource(
                        when (snapshot.connectionStatus) {
                            AndroidAutoConnectionStatus.DISCONNECTED -> R.string.android_auto_disconnected
                            AndroidAutoConnectionStatus.PROJECTION -> R.string.android_auto_projection
                            AndroidAutoConnectionStatus.NATIVE -> R.string.android_auto_native
                        },
                    ),
                icon = { Icon(painterResource(R.drawable.directions_car), null) },
            )
        }
        item {
            PreferenceEntry(
                title = { Text(stringResource(R.string.android_auto_app_permissions)) },
                icon = { Icon(painterResource(R.drawable.security), null) },
                onClick = remember(onAction) {
                    { onAction(AndroidAutoSettingsAction.OpenAppPermissions) }
                },
            )
        }
    }
}

@Composable
private fun AndroidAutoSettingsFailure(
    onAction: (AndroidAutoSettingsAction) -> Unit,
    modifier: Modifier = Modifier,
    @StringRes messageRes: Int = R.string.android_auto_settings_load_failed,
) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(messageRes), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = remember(onAction) { { onAction(AndroidAutoSettingsAction.Retry) } }) {
            Text(stringResource(R.string.retry_button))
        }
    }
}

@StringRes
private fun AndroidAutoCustomAction.labelResource(): Int = when (this) {
    AndroidAutoCustomAction.LIKE -> R.string.android_auto_action_like
    AndroidAutoCustomAction.START_RADIO -> R.string.android_auto_action_radio
    AndroidAutoCustomAction.SHUFFLE -> R.string.android_auto_action_shuffle
    AndroidAutoCustomAction.REPEAT -> R.string.android_auto_action_repeat
    AndroidAutoCustomAction.NONE -> R.string.android_auto_action_none
}

@DrawableRes
private fun AndroidAutoCustomAction.iconResource(): Int = when (this) {
    AndroidAutoCustomAction.LIKE -> R.drawable.favorite
    AndroidAutoCustomAction.START_RADIO -> R.drawable.radio
    AndroidAutoCustomAction.SHUFFLE -> R.drawable.shuffle
    AndroidAutoCustomAction.REPEAT -> R.drawable.repeat
    AndroidAutoCustomAction.NONE -> R.drawable.more_horiz
}
