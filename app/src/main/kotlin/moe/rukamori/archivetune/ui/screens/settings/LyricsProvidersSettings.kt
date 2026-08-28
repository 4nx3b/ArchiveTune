/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.EnableBetterLyricsKey
import moe.rukamori.archivetune.constants.EnableBetterLyricsPortatoKey
import moe.rukamori.archivetune.constants.EnableBiniLyricsKey
import moe.rukamori.archivetune.constants.EnableKugouKey
import moe.rukamori.archivetune.constants.EnableLrcLibKey
import moe.rukamori.archivetune.constants.EnableMusixmatchExperimentalKey
import moe.rukamori.archivetune.constants.EnableSimpMusicLyricsKey
import moe.rukamori.archivetune.constants.EnableUnisonLyricsKey
import moe.rukamori.archivetune.constants.EnableYouLyPlusLyricsKey
import moe.rukamori.archivetune.constants.LyricsProviderOrderKey
import moe.rukamori.archivetune.constants.PreferredLyricsProvider
import moe.rukamori.archivetune.constants.PrioritizeWordSyncedLyricsKey
import moe.rukamori.archivetune.constants.deserializeLyricsProviderOrder
import moe.rukamori.archivetune.lyrics.LyricsProviderTestOutcome
import moe.rukamori.archivetune.lyrics.LyricsProviderTestResult
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.FrostedHeaderPill
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.ContentSettingsViewModel
import moe.rukamori.archivetune.viewmodels.LyricsTestState
import androidx.compose.foundation.layout.asPaddingValues

/**
 * Lyrics providers sub-page (Task 2): houses every lyrics-provider toggle plus the
 * Musixmatch experimental section that used to live inline on the Lyrics settings page.
 *
 * Behaviour preserved verbatim from the original inline groups:
 *   • All provider switches default to on (except Musixmatch experimental).
 *   • "Set first lyrics provider" opens the reorderable dialog. The dialog itself lives
 *     in LyricsSettings.kt and is `internal` so this screen can reuse it.
 *   • "Lyrics test" runs a sweep across every enabled provider with a known test
 *     track and shows per-provider outcomes — see [LyricsTestDialog] below.
 *
 * Paxsenix/Tidal/Deezer toggles and their sub-toggles (Apple Music / NetEase / Spotify /
 * Musixmatch / YouTube), the Paxsenix stats dialog, API key entry, endpoint entry, and
 * endpoint check dialog have been removed (user request 2026-08-28: "I still see enable
 * paxesnix lyrics switch in lyrics provider. Remove it"). BiniLyrics replaces the Paxsenix
 * Apple Music path as the user-visible label on the same backend.
 */
@Composable
fun LyricsProvidersSettings(
    navController: NavController,
    viewModel: ContentSettingsViewModel = hiltViewModel(),
    scrollTo: String? = null,
) {
    val (enableLrclib, onEnableLrclibChange) = rememberPreference(key = EnableLrcLibKey, defaultValue = true)
    val (enableKugou, onEnableKugouChange) = rememberPreference(key = EnableKugouKey, defaultValue = true)
    val (enableBetterLyrics, onEnableBetterLyricsChange) =
        rememberPreference(key = EnableBetterLyricsKey, defaultValue = true)
    val (enableBetterLyricsPortato, onEnableBetterLyricsPortatoChange) =
        rememberPreference(key = EnableBetterLyricsPortatoKey, defaultValue = true)
    val (enableYouLyPlusLyrics, onEnableYouLyPlusLyricsChange) =
        rememberPreference(key = EnableYouLyPlusLyricsKey, defaultValue = true)
    val (enableSimpMusicLyrics, onEnableSimpMusicLyricsChange) =
        rememberPreference(key = EnableSimpMusicLyricsKey, defaultValue = true)
    val (enableBiniLyrics, onEnableBiniLyricsChange) =
        rememberPreference(key = EnableBiniLyricsKey, defaultValue = true)
    val (enableUnisonLyrics, onEnableUnisonLyricsChange) =
        rememberPreference(key = EnableUnisonLyricsKey, defaultValue = true)
    val (prioritizeWordSynced, onPrioritizeWordSyncedChange) =
        rememberPreference(key = PrioritizeWordSyncedLyricsKey, defaultValue = false)
    val (enableMusixmatchExperimental, onEnableMusixmatchExperimentalChange) =
        rememberPreference(key = EnableMusixmatchExperimentalKey, defaultValue = false)
    val (providerOrderStr, onProviderOrderStrChange) =
        rememberPreference(key = LyricsProviderOrderKey, defaultValue = "")
    val providerOrder =
        remember(providerOrderStr) {
            deserializeLyricsProviderOrder(providerOrderStr)
        }

    var showProviderOrderDialog by remember { mutableStateOf(false) }
    var showLyricsTestDialog by remember { mutableStateOf(false) }

    if (showLyricsTestDialog) {
        val testState by viewModel.lyricsTestState.collectAsStateWithLifecycle()
        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (testState is LyricsTestState.Idle) {
                viewModel.runLyricsTest()
            }
        }
        LyricsTestDialog(
            state = testState,
            onDismiss = { showLyricsTestDialog = false },
            onRetry = { viewModel.runLyricsTest() },
        )
    }

    if (showProviderOrderDialog) {
        LyricsProviderOrderDialog(
            initialOrder = providerOrder,
            onDismiss = { showProviderOrderDialog = false },
            onConfirm = { newOrder ->
                onProviderOrderStrChange(newOrder.joinToString(",") { it.name })
                showProviderOrderDialog = false
            },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    FrostedHeaderPill {
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
                            text = stringResource(R.string.providers),
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val playerAwareBottomPadding =
            LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()
        val scrollState = rememberScrollState()
        val positions = rememberPreferencePositions()
        androidx.compose.runtime.LaunchedEffect(scrollTo) { positions.scrollToKey(scrollTo, scrollState) }
        Column(
            Modifier
                .padding(top = innerPadding.calculateTopPadding())
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(
                        WindowInsetsSides.Horizontal,
                    ),
                )
                // Chained before verticalScroll so it measures the viewport, not the scrolling content.
                .then(positions.containerModifier())
                .verticalScroll(scrollState)
                .padding(bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding),
        ) {
            PreferenceGroup(title = stringResource(R.string.providers)) {
                // "Prioritize Word Synced Lyrics" sits at the TOP of the providers
                // group because when it's ON it overrides every other toggle and the
                // Lyrics Priority order below — the app queries only BetterLyrics,
                // BetterLyrics Portato, YouLyPlus, and Unison directly. Putting it
                // first makes the override relationship visually obvious.
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("prioritize_word_synced_lyrics"),
                        title = { Text(stringResource(R.string.prioritize_word_synced_lyrics)) },
                        description = stringResource(R.string.prioritize_word_synced_lyrics_desc),
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        checked = prioritizeWordSynced,
                        onCheckedChange = onPrioritizeWordSyncedChange,
                    )
                }

                // When "Prioritize Word Synced Lyrics" is ON, the per-provider
                // toggles and Lyrics Priority order are ignored by LyricsHelper
                // (the four word-sync-capable providers are queried directly).
                // We grey them out here to signal that they have no effect while
                // the override is active. They remain visible (not hidden) so the
                // user can still see their state and understand what will resume
                // when the override is turned back off.
                val providerTogglesEnabled = !prioritizeWordSynced

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("enable_betterlyrics", "betterlyrics"),
                        title = { Text(stringResource(R.string.enable_betterlyrics)) },
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        checked = enableBetterLyrics,
                        onCheckedChange = onEnableBetterLyricsChange,
                        isEnabled = providerTogglesEnabled,
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("enable_betterlyrics_portato", "betterlyrics_portato"),
                        title = { Text(stringResource(R.string.enable_betterlyrics_portato)) },
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        checked = enableBetterLyricsPortato,
                        onCheckedChange = onEnableBetterLyricsPortatoChange,
                        isEnabled = providerTogglesEnabled,
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("enable_youlyplus_lyrics", "youlyplus_lyrics"),
                        title = { Text(stringResource(R.string.enable_youlyplus_lyrics)) },
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        checked = enableYouLyPlusLyrics,
                        onCheckedChange = onEnableYouLyPlusLyricsChange,
                        isEnabled = providerTogglesEnabled,
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("enable_lrclib", "lrclib"),
                        title = { Text(stringResource(R.string.enable_lrclib)) },
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        checked = enableLrclib,
                        onCheckedChange = onEnableLrclibChange,
                        isEnabled = providerTogglesEnabled,
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("enable_kugou", "kugou"),
                        title = { Text(stringResource(R.string.enable_kugou)) },
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        checked = enableKugou,
                        onCheckedChange = onEnableKugouChange,
                        isEnabled = providerTogglesEnabled,
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("enable_unison_lyrics", "unison_lyrics"),
                        title = { Text(stringResource(R.string.enable_unison_lyrics)) },
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        checked = enableUnisonLyrics,
                        onCheckedChange = onEnableUnisonLyricsChange,
                        isEnabled = providerTogglesEnabled,
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("enable_simpmusic_lyrics", "simpmusic_lyrics"),
                        title = { Text(stringResource(R.string.enable_simpmusic_lyrics)) },
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        checked = enableSimpMusicLyrics,
                        onCheckedChange = onEnableSimpMusicLyricsChange,
                        isEnabled = providerTogglesEnabled,
                    )
                }

                // Megalobiz lyrics provider removed per user request
                // (2026-08-28): "Remove megalobiz lyrics provider". The
                // MegalobizLyricsProvider file was deleted; the
                // PreferredLyricsProvider.MEGALOBIZ enum value and the
                // DefaultLyricsProviderOrder entry are also gone.

                // BiniLyrics replaces the Paxsenix: Apple Music provider in the
                // user-visible priority list (commit 717db4f19). The toggle
                // here is the user's on/off for that single provider — the
                // per-Paxsenix sub-toggles (NetEase / Spotify / Musixmatch /
                // YouTube) and the retired Paxsenix stats / endpoint / API
                // key entries are removed per user request (2026-08-28):
                // "I still see enable paxesnix lyrics switch in lyrics
                // provider. Remove it".
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("enable_bini_lyrics", "bini_lyrics"),
                        title = { Text(stringResource(R.string.enable_bini_lyrics)) },
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        checked = enableBiniLyrics,
                        onCheckedChange = onEnableBiniLyricsChange,
                        isEnabled = providerTogglesEnabled,
                    )
                }

                // "Lyrics test" — sweeps every enabled provider with a known
                // test track (Ed Sheeran — Shape of You) and reports per-
                // provider outcomes (Working / No lyrics for test track /
                // Timed out / Failed) in a dialog. Per user request
                // (2026-08-28): "Add an option in lyrics provider named
                // Lyrics test. when I click on it, it should show that
                // whether all the lyrics providers are working or not".
                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("lyrics_test"),
                        title = { Text(stringResource(R.string.lyrics_test)) },
                        description = stringResource(R.string.lyrics_test_description),
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        onClick = { showLyricsTestDialog = true },
                        isEnabled = providerTogglesEnabled,
                    )
                }

                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("set_first_lyrics_provider", "first_lyrics_provider"),
                        title = { Text(stringResource(R.string.set_first_lyrics_provider)) },
                        description = providerOrder.firstOrNull()?.displayName(),
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        onClick = { showProviderOrderDialog = true },
                        isEnabled = providerTogglesEnabled,
                    )
                }
            }

            PreferenceGroup(title = stringResource(R.string.musixmatch_experimental_section)) {
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("enable_musixmatch_experimental"),
                        title = { Text(stringResource(R.string.enable_musixmatch_experimental)) },
                        description = stringResource(R.string.enable_musixmatch_experimental_desc),
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        checked = enableMusixmatchExperimental,
                        onCheckedChange = onEnableMusixmatchExperimentalChange,
                        isEnabled = !prioritizeWordSynced,
                    )
                }
                item(visible = enableMusixmatchExperimental) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                    ) {
                        Text(
                            text = stringResource(R.string.musixmatch_experimental_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * "Lyrics test" dialog — runs a sweep across every enabled provider with a
 * known test track (Ed Sheeran — "Shape of You") and shows per-provider
 * outcomes: Working / No lyrics for test track / Timed out / Failed.
 *
 * The four outcomes are deliberately distinct:
 *   • Working — the provider returned meaningful lyrics for the test track.
 *     The provider is reachable and serving the test case.
 *   • No lyrics for test track — the provider responded cleanly but had no
 *     entry for the test track. The provider is reachable; it just doesn't
 *     have this specific song. Most providers should land here for the test
 *     track since "Shape of You" is widely catalogued, but a provider that
 *     uses a different index format may legitimately not have it.
 *   • Timed out — the provider didn't respond within the per-provider budget
 *     (12s). May indicate a slow endpoint or a temporary network issue.
 *   • Failed — the provider errored out (network/DNS/5xx/exception). The
 *     provider is currently not usable.
 *
 * A "Retry" button re-runs the sweep, replacing stale results with a fresh
 * probe.
 */
@Composable
private fun LyricsTestDialog(
    state: LyricsTestState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text(stringResource(R.string.lyrics_test)) },
        icon = { Icon(painterResource(R.drawable.lyrics), contentDescription = null) },
        buttons = {
            if (state is LyricsTestState.Done) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.retry))
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
    ) {
        when (state) {
            LyricsTestState.Idle,
            LyricsTestState.Loading,
            -> {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                    Text(
                        text = stringResource(R.string.lyrics_test_running),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is LyricsTestState.Done -> {
                val working = state.results.count { it.outcome == LyricsProviderTestOutcome.OK }
                val total = state.results.size
                val summary =
                    if (working == 0) {
                        stringResource(R.string.lyrics_test_summary_none)
                    } else {
                        stringResource(R.string.lyrics_test_summary_ok, working, total)
                    }
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    state.results.forEach { result ->
                        val (label, tint, iconRes) =
                            when (result.outcome) {
                                LyricsProviderTestOutcome.OK ->
                                    Triple(
                                        stringResource(R.string.lyrics_test_ok),
                                        MaterialTheme.colorScheme.primary,
                                        R.drawable.check,
                                    )
                                LyricsProviderTestOutcome.NO_MATCH ->
                                    Triple(
                                        stringResource(R.string.lyrics_test_no_match),
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                        R.drawable.info,
                                    )
                                LyricsProviderTestOutcome.TIMEOUT ->
                                    Triple(
                                        stringResource(R.string.lyrics_test_timeout),
                                        MaterialTheme.colorScheme.error,
                                        R.drawable.error,
                                    )
                                LyricsProviderTestOutcome.FAILED ->
                                    Triple(
                                        stringResource(R.string.lyrics_test_failed),
                                        MaterialTheme.colorScheme.error,
                                        R.drawable.error,
                                    )
                            }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                painterResource(iconRes),
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(18.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = result.providerName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tint,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
