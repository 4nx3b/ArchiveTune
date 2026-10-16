/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.DisableScreenshotKey
import moe.rukamori.archivetune.constants.EnableHapticFeedbackKey
import moe.rukamori.archivetune.constants.ForceHighRefreshRateKey
import moe.rukamori.archivetune.constants.LowDataModeKey
import moe.rukamori.archivetune.constants.PauseListenHistoryKey
import moe.rukamori.archivetune.constants.PauseSearchHistoryKey
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.FrostedHeaderPill
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.playback.MusicHapticsSettings
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import kotlin.math.roundToInt
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.utils.backToMain
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import moe.rukamori.archivetune.ui.component.SettingsPageTopBar
import moe.rukamori.archivetune.constants.OverscrollStyle
import moe.rukamori.archivetune.constants.OverscrollStyleKey
import moe.rukamori.archivetune.constants.OverscrollRubberBandTensionKey
import moe.rukamori.archivetune.constants.OverscrollBounceStiffnessKey
import moe.rukamori.archivetune.utils.rememberEnumPreference
import androidx.compose.material3.RadioButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettings(
    navController: NavController,
    scrollTo: String? = null,
) {
    val database = LocalDatabase.current
    val context = LocalContext.current
    val isAndroid12OrLater = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    val (pauseListenHistory, onPauseListenHistoryChange) =
        rememberPreference(
            key = PauseListenHistoryKey,
            defaultValue = false,
        )
    val (pauseSearchHistory, onPauseSearchHistoryChange) =
        rememberPreference(
            key = PauseSearchHistoryKey,
            defaultValue = false,
        )
    val (disableScreenshot, onDisableScreenshotChange) =
        rememberPreference(
            key = DisableScreenshotKey,
            defaultValue = false,
        )
    val (enableHapticFeedback, onEnableHapticFeedbackChange) =
        rememberPreference(
            key = EnableHapticFeedbackKey,
            defaultValue = true,
        )

    var musicHapticsEnabled by remember { mutableStateOf(MusicHapticsSettings.isEnabled(context)) }
    var musicHapticsStrength by remember { mutableStateOf(MusicHapticsSettings.strengthPercent(context)) }
    val (lowDataMode, onLowDataModeChange) =
        rememberPreference(
            key = LowDataModeKey,
            defaultValue = true,
        )
    val (forceHighRefreshRate, onForceHighRefreshRateChange) =
        rememberPreference(
            key = ForceHighRefreshRateKey,
            defaultValue = false,
        )
    val (overscrollStyle, onOverscrollStyleChange) =
        rememberEnumPreference(OverscrollStyleKey, defaultValue = OverscrollStyle.IOS_RUBBER_BAND)
    val (overscrollTension, onOverscrollTensionChange) =
        rememberPreference(OverscrollRubberBandTensionKey, defaultValue = 0.55f)
    val (overscrollStiffness, onOverscrollStiffnessChange) =
        rememberPreference(OverscrollBounceStiffnessKey, defaultValue = 247f)

    var showOverscrollStyleDialog by rememberSaveable { mutableStateOf(false) }

    if (showOverscrollStyleDialog) {
        DefaultDialog(
            onDismiss = { showOverscrollStyleDialog = false },
            buttons = {
                TextButton(
                    onClick = {
                        onOverscrollStyleChange(OverscrollStyle.IOS_RUBBER_BAND)
                        showOverscrollStyleDialog = false
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.reset))
                }

                Spacer(modifier = Modifier.weight(1f))

                TextButton(
                    onClick = { showOverscrollStyleDialog = false },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.overscroll_style),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                OverscrollStyle.entries.forEach { style ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = style == overscrollStyle,
                            onClick = {
                                onOverscrollStyleChange(style)
                                showOverscrollStyleDialog = false
                            },
                        )
                        Text(
                            text = stringResource(
                                when (style) {
                                    OverscrollStyle.IOS_RUBBER_BAND -> R.string.overscroll_style_ios
                                    OverscrollStyle.ANDROID_STRETCH -> R.string.overscroll_style_android
                                    OverscrollStyle.OFF -> R.string.overscroll_style_off
                                },
                            ),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }

    var showClearListenHistoryDialog by remember {
        mutableStateOf(false)
    }

    if (showClearListenHistoryDialog) {
        DefaultDialog(
            onDismiss = { showClearListenHistoryDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.clear_listen_history_confirm),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            },
            buttons = {
                TextButton(
                    onClick = { showClearListenHistoryDialog = false },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showClearListenHistoryDialog = false
                        database.query {
                            clearListenHistory()
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
        )
    }

    var showClearSearchHistoryDialog by remember {
        mutableStateOf(false)
    }

    if (showClearSearchHistoryDialog) {
        DefaultDialog(
            onDismiss = { showClearSearchHistoryDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.clear_search_history_confirm),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            },
            buttons = {
                TextButton(
                    onClick = { showClearSearchHistoryDialog = false },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showClearSearchHistoryDialog = false
                        database.query {
                            clearSearchHistory()
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
        )
    }

    val headerHaze = rememberScreenHeaderHaze()
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Transparent,
        topBar = {
                SettingsPageTopBar(
                    titleText = stringResource(R.string.settings_behavior_title),
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
                modifier = positions.modifierFor("pause_listen_history"),
                title = stringResource(R.string.listen_history),
            ) {
                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.pause_listen_history)) },
                        icon = { Icon(painterResource(R.drawable.history), null) },
                        checked = pauseListenHistory,
                        onCheckedChange = onPauseListenHistoryChange,
                    )
                }

                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("clear_listen_history"),
                        title = { Text(stringResource(R.string.clear_listen_history)) },
                        icon = { Icon(painterResource(R.drawable.delete_history), null) },
                        onClick = { showClearListenHistoryDialog = true },
                    )
                }
            }

            PreferenceGroup(
                modifier = positions.modifierFor("pause_search_history"),
                title = stringResource(R.string.search_history),
            ) {
                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.pause_search_history)) },
                        icon = { Icon(painterResource(R.drawable.search_off), null) },
                        checked = pauseSearchHistory,
                        onCheckedChange = onPauseSearchHistoryChange,
                    )
                }

                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("clear_search_history"),
                        title = { Text(stringResource(R.string.clear_search_history)) },
                        icon = { Icon(painterResource(R.drawable.clear_all), null) },
                        onClick = { showClearSearchHistoryDialog = true },
                    )
                }
            }

            PreferenceGroup(
                modifier = positions.modifierFor("overscroll"),
                title = stringResource(R.string.overscroll_physics),
            ) {
                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("overscroll_style"),
                        title = { Text(stringResource(R.string.overscroll_style)) },
                        description =
                            stringResource(
                                when (overscrollStyle) {
                                    OverscrollStyle.IOS_RUBBER_BAND -> R.string.overscroll_style_ios
                                    OverscrollStyle.ANDROID_STRETCH -> R.string.overscroll_style_android
                                    OverscrollStyle.OFF -> R.string.overscroll_style_off
                                },
                            ),
                        icon = { Icon(painterResource(R.drawable.swipe), null) },
                        onClick = { showOverscrollStyleDialog = true },
                    )
                }

                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("overscroll_rubber_band_tension"),
                        title = { Text(stringResource(R.string.overscroll_rubber_band_tension)) },
                        description = stringResource(
                            R.string.overscroll_rubber_band_tension_value,
                            overscrollTension,
                        ),
                        icon = { Icon(painterResource(R.drawable.expand_more), null) },
                        isEnabled = overscrollStyle == OverscrollStyle.IOS_RUBBER_BAND,
                        content = {
                            Spacer(modifier = Modifier.height(10.dp))
                            Slider(
                                value = overscrollTension,
                                onValueChange = { onOverscrollTensionChange((it * 100).roundToInt() / 100f) },
                                valueRange = 0.2f..1.2f,
                                steps = 19,
                                enabled = overscrollStyle == OverscrollStyle.IOS_RUBBER_BAND,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                    )
                }

                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("overscroll_bounce_speed"),
                        title = { Text(stringResource(R.string.overscroll_bounce_speed)) },
                        description = stringResource(
                            R.string.overscroll_bounce_speed_value,
                            overscrollStiffness.roundToInt(),
                        ),
                        icon = { Icon(painterResource(R.drawable.tune), null) },
                        isEnabled = overscrollStyle == OverscrollStyle.IOS_RUBBER_BAND,
                        content = {
                            Spacer(modifier = Modifier.height(10.dp))
                            Slider(
                                value = overscrollStiffness,
                                onValueChange = { onOverscrollStiffnessChange(it.roundToInt().toFloat()) },
                                valueRange = 60f..600f,
                                steps = 17,
                                enabled = overscrollStyle == OverscrollStyle.IOS_RUBBER_BAND,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                    )
                }
            }

            PreferenceGroup(
                modifier = positions.modifierFor("haptics"),
                title = stringResource(R.string.misc),
            ) {
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("low_data_mode"),
                        title = { Text(stringResource(R.string.low_data_mode_title)) },
                        description = stringResource(R.string.low_data_mode_description),
                        icon = { Icon(painterResource(R.drawable.android_cell), null) },
                        checked = lowDataMode,
                        onCheckedChange = onLowDataModeChange,
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("force_high_refresh_rate"),
                        title = { Text(stringResource(R.string.force_high_refresh_rate)) },
                        icon = { Icon(painterResource(R.drawable.speed), null) },
                        checked = forceHighRefreshRate,
                        onCheckedChange = onForceHighRefreshRateChange,
                    )
                }

                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.haptics)) },
                        description = stringResource(R.string.haptics_desc),
                        icon = { Icon(painterResource(R.drawable.vibration), null) },
                        checked = enableHapticFeedback,
                        onCheckedChange = onEnableHapticFeedbackChange,
                    )
                }

                item {
                    SwitchPreference(
                        title = { Text(stringResource(R.string.music_haptics)) },
                        description = stringResource(R.string.music_haptics_desc),
                        icon = { Icon(painterResource(R.drawable.vibration), null) },
                        checked = musicHapticsEnabled,
                        onCheckedChange = { next ->
                            MusicHapticsSettings.setEnabled(context, next)
                            musicHapticsEnabled = next
                        },
                    )
                }

                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.music_haptics_strength)) },
                        description = stringResource(R.string.music_haptics_strength_value, musicHapticsStrength),
                        icon = { Icon(painterResource(R.drawable.vibration), null) },
                        isEnabled = musicHapticsEnabled,
                        content = {
                            Spacer(modifier = Modifier.height(10.dp))
                            Slider(
                                value = musicHapticsStrength.toFloat(),
                                onValueChange = { musicHapticsStrength = it.roundToInt() },
                                onValueChangeFinished = {
                                    MusicHapticsSettings.setStrengthPercent(context, musicHapticsStrength)
                                },
                                valueRange = 0f..100f,
                                steps = 19,
                                enabled = musicHapticsEnabled,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("disable_screenshot"),
                        title = { Text(stringResource(R.string.disable_screenshot)) },
                        description = stringResource(R.string.disable_screenshot_desc),
                        icon = { Icon(painterResource(R.drawable.screenshot), null) },
                        checked = disableScreenshot,
                        onCheckedChange = onDisableScreenshotChange,
                    )
                }

                if (isAndroid12OrLater) {
                    item {
                        PreferenceEntry(
                            modifier = positions.modifierFor("open_supported_links"),
                            title = { Text(stringResource(R.string.open_supported_links)) },
                            description = stringResource(R.string.default_links),
                            icon = { Icon(painterResource(R.drawable.link), null) },
                            onClick = {
                                try {
                                    val intent =
                                        Intent(
                                            Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                                            Uri.parse("package:${context.packageName}"),
                                        ).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast
                                        .makeText(
                                            context,
                                            R.string.open_app_settings_error,
                                            Toast.LENGTH_LONG,
                                        ).show()
                                }
                            },
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
}
