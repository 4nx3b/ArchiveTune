/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.EnableBetterLyricsKey
import moe.rukamori.archivetune.constants.EnableBetterLyricsPortatoKey
import moe.rukamori.archivetune.constants.EnableKugouKey
import moe.rukamori.archivetune.constants.EnableLrcLibKey
import moe.rukamori.archivetune.constants.EnableMusixmatchExperimentalKey
import moe.rukamori.archivetune.constants.EnableUnisonLyricsKey
import moe.rukamori.archivetune.constants.EnableYouLyPlusLyricsKey
import moe.rukamori.archivetune.constants.LyricsClickKey
import moe.rukamori.archivetune.constants.LyricsLineBlurKey
import moe.rukamori.archivetune.constants.LyricsLineSpacingKey
import moe.rukamori.archivetune.constants.LyricsProviderOrderKey
import moe.rukamori.archivetune.constants.LyricsRomanizeChineseKey
import moe.rukamori.archivetune.constants.LyricsRomanizeHindiKey
import moe.rukamori.archivetune.constants.LyricsRomanizeJapaneseKey
import moe.rukamori.archivetune.constants.LyricsRomanizeKoreanKey
import moe.rukamori.archivetune.constants.LyricsRomanizeOtherLanguagesKey
import moe.rukamori.archivetune.constants.LyricsScrollKey
import moe.rukamori.archivetune.constants.AutoHideLyricsPlayerControlsKey
import moe.rukamori.archivetune.constants.ShowLyricsPlayerControlsKey
import moe.rukamori.archivetune.constants.LyricsTextSizeKey
import moe.rukamori.archivetune.constants.PreferredLyricsProvider
import moe.rukamori.archivetune.constants.QueueLyricsPreloadCountKey
import moe.rukamori.archivetune.constants.deserializeLyricsProviderOrder
import moe.rukamori.archivetune.lyrics.JapaneseLanguagePackManager
import moe.rukamori.archivetune.lyrics.JapaneseLanguagePackState
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.EnumListPreference
import moe.rukamori.archivetune.ui.component.FrostedHeaderPill
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.NumberPickerPreference
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.ui.screens.ScreenHeaderHaze
import moe.rukamori.archivetune.ui.screens.rememberScreenHeaderHaze
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import dev.chrisbanes.haze.hazeSource
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.ContentSettingsViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.roundToInt

@Composable
fun LyricsSettings(
    navController: NavController,
    viewModel: ContentSettingsViewModel = hiltViewModel(),
    scrollTo: String? = null,
) {
    // PaxsenixStatsDialog and its state plumbing removed (2026-08-30) along
    // with the PaxsenixLyrics backend that the dialog queried. The
    // fetchPaxsenixStats / paxsenixStatsState surface has been removed from
    // ContentSettingsViewModel, and the PaxsenixStatsContent /
    // PaxsenixStatusBar / PaxsenixProviderRow / PaxsenixServerStatus /
    // successRateToStatus helpers below have been deleted too.

    val (lyricsClick, onLyricsClickChange) = rememberPreference(LyricsClickKey, defaultValue = true)
    val (lyricsScroll, onLyricsScrollChange) = rememberPreference(LyricsScrollKey, defaultValue = true)
    // Restored (2026-09-04): the two control-preference reads behind the restored
    // "Show player controls" / "Auto-hide controls" settings (see the items below).
    val (showPlayerControls, onShowPlayerControlsChange) =
        rememberPreference(ShowLyricsPlayerControlsKey, defaultValue = true)
    val (autoHidePlayerControls, onAutoHidePlayerControlsChange) =
        rememberPreference(AutoHideLyricsPlayerControlsKey, defaultValue = true)
    val (lyricsTextSize, onLyricsTextSizeChange) = rememberPreference(LyricsTextSizeKey, defaultValue = 26f)
    val (lyricsLineSpacing, onLyricsLineSpacingChange) = rememberPreference(LyricsLineSpacingKey, defaultValue = 1.3f)
    // LyricsMode picker removed by user request — Enhanced is the only renderer now, so the
    // "V2 Legacy / Enhanced" choice is no longer surfaced. The LyricsMode enum and LyricsModeKey
    // preference are kept in PreferenceKeys.kt for backward compatibility with existing DataStore
    // values (the player code reads the enum but only the ENHANCED branch is reachable now).
    val (enableLrclib, onEnableLrclibChange) = rememberPreference(key = EnableLrcLibKey, defaultValue = true)
    val (enableKugou, onEnableKugouChange) = rememberPreference(key = EnableKugouKey, defaultValue = true)
    val (enableBetterLyrics, onEnableBetterLyricsChange) = rememberPreference(key = EnableBetterLyricsKey, defaultValue = true)
    val (enableBetterLyricsPortato, onEnableBetterLyricsPortatoChange) =
        rememberPreference(key = EnableBetterLyricsPortatoKey, defaultValue = true)
    val (enableYouLyPlusLyrics, onEnableYouLyPlusLyricsChange) =
        rememberPreference(key = EnableYouLyPlusLyricsKey, defaultValue = true)
    // SimpMusic / BiniLyrics lyrics providers removed per user request
    // (2026-08-30): "Remove simpmusic and binilyrics lyrics provider and
    // their entire code too". The provider files, settings toggles, enum
    // entries, gradle module includes and the underlying :lyrics:simpmusic
    // / :lyrics:paxsenix gradle modules have all been deleted.
    //
    // The Paxsenix* enable keys / rememberPreference calls below were also
    // removed because the PaxsenixLyrics backend was the only consumer; the
    // keys remain defined in PreferenceKeys.kt as no-ops for source compat.
    // Megalobiz lyrics provider removed per user request (2026-08-28):
    // "Remove megalobiz lyrics provider". The MegalobizLyricsProvider
    // file was deleted; the PreferredLyricsProvider.MEGALOBIZ enum value
    // and the DefaultLyricsProviderOrder entry are also gone.
    val (enableUnisonLyrics, onEnableUnisonLyricsChange) = rememberPreference(key = EnableUnisonLyricsKey, defaultValue = true)
    val (enableMusixmatchExperimental, onEnableMusixmatchExperimentalChange) =
        rememberPreference(key = EnableMusixmatchExperimentalKey, defaultValue = false)
    val (providerOrderStr, onProviderOrderStrChange) =
        rememberPreference(
            key = LyricsProviderOrderKey,
            defaultValue = "",
        )
    val providerOrder =
        remember(providerOrderStr) {
            deserializeLyricsProviderOrder(providerOrderStr)
        }
    val (lyricsLineBlur, onLyricsLineBlurChange) = rememberPreference(LyricsLineBlurKey, defaultValue = false)
    val (lyricsRomanizeJapanese, onLyricsRomanizeJapaneseChange) = rememberPreference(LyricsRomanizeJapaneseKey, defaultValue = false)
    val (lyricsRomanizeKorean, onLyricsRomanizeKoreanChange) = rememberPreference(LyricsRomanizeKoreanKey, defaultValue = true)
    val (lyricsRomanizeChinese, onLyricsRomanizeChineseChange) = rememberPreference(LyricsRomanizeChineseKey, defaultValue = true)
    val (lyricsRomanizeHindi, onLyricsRomanizeHindiChange) = rememberPreference(LyricsRomanizeHindiKey, defaultValue = true)
    val (lyricsRomanizeOtherLanguages, onLyricsRomanizeOtherLanguagesChange) =
        rememberPreference(
            LyricsRomanizeOtherLanguagesKey,
            defaultValue = true,
        )
    val (queueLyricsPreloadCount, onQueueLyricsPreloadCountChange) = rememberPreference(QueueLyricsPreloadCountKey, defaultValue = 3)
    val japaneseLanguagePackState by JapaneseLanguagePackManager.state.collectAsStateWithLifecycle()

    var showProviderOrderDialog by rememberSaveable { mutableStateOf(false) }

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

    val scrollState = rememberScrollState()
    val positions = rememberPreferencePositions()

    LaunchedEffect(scrollTo) { positions.scrollToKey(scrollTo, scrollState) }

    // ── Home-screen header haze (2026-09-05, revised) ──
    // The 2026-09-05 morning attempt recorded the Column into a kyant
    // layerBackdrop for a glass pill in the TopAppBar — but the pill and
    // the recorded layer never overlap (the bar overlays the NavHost Box,
    // while the recording only covered the area below the bar), so the pill
    // rendered opaque with no visible blur (user report: "liquid glass but
    // the background is opaque and there's no haze effect"). This now uses
    // the canonical pattern the 30+ approved settings screens use
    // (PlayerSettings et al.): the scrolling Column is the haze source, the
    // ScreenHeaderHaze overlay blurs whatever scrolls under the transparent
    // TopAppBar, and the pill is the plain single-pill look.
    val headerHaze = rememberScreenHeaderHaze()
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current
    // Mini-player aware bottom padding — keeps the last row clear of the
    // persistent mini player (the insets' bottom half of the old single
    // windowInsetsPadding call).
    val playerAwareBottomPadding =
        LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Bottom)
            .asPaddingValues()
            .calculateBottomPadding()
    // The insets' top half (status bar + AppBarHeight) moves INSIDE the
    // scroll so items scroll up under the transparent bar into the blur.
    val headerTopPadding =
        LocalPlayerAwareWindowInsets.current
            .asPaddingValues()
            .calculateTopPadding()

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
            // Chained before verticalScroll so it measures the viewport, not the scrolling content.
            .then(positions.containerModifier())
            .verticalScroll(scrollState)
            .hazeSource(headerHaze)
            .padding(top = headerTopPadding)
            .padding(bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding),
    ) {
        var showLyricsTextSizeDialog by rememberSaveable { mutableStateOf(false) }

        if (showLyricsTextSizeDialog) {
            var tempTextSize by remember { mutableFloatStateOf(lyricsTextSize) }

            DefaultDialog(
                onDismiss = {
                    tempTextSize = lyricsTextSize
                    showLyricsTextSizeDialog = false
                },
                buttons = {
                    TextButton(
                        onClick = { tempTextSize = 24f },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(R.string.reset))
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    TextButton(
                        onClick = {
                            tempTextSize = lyricsTextSize
                            showLyricsTextSizeDialog = false
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            onLyricsTextSizeChange(tempTextSize)
                            showLyricsTextSizeDialog = false
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.lyrics_text_size),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )

                    Text(
                        text = "${tempTextSize.roundToInt()} sp",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )

                    Slider(
                        value = tempTextSize,
                        onValueChange = { tempTextSize = it },
                        valueRange = 16f..36f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        var showLyricsLineSpacingDialog by rememberSaveable { mutableStateOf(false) }

        if (showLyricsLineSpacingDialog) {
            var tempLineSpacing by remember { mutableFloatStateOf(lyricsLineSpacing) }

            DefaultDialog(
                onDismiss = {
                    tempLineSpacing = lyricsLineSpacing
                    showLyricsLineSpacingDialog = false
                },
                buttons = {
                    TextButton(
                        onClick = { tempLineSpacing = 1.3f },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(R.string.reset))
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    TextButton(
                        onClick = {
                            tempLineSpacing = lyricsLineSpacing
                            showLyricsLineSpacingDialog = false
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            onLyricsLineSpacingChange(tempLineSpacing)
                            showLyricsLineSpacingDialog = false
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.lyrics_line_spacing),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )

                    Text(
                        text = "${String.format("%.1f", tempLineSpacing)}x",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )

                    Slider(
                        value = tempLineSpacing,
                        onValueChange = { tempLineSpacing = it },
                        valueRange = 1.0f..2.0f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Language packs entry moved here from the main settings page (Task 6).
        // Sits above the display group so users can install/enable packs before
        // toggling romanization for the relevant languages below.
        PreferenceGroup(
            modifier = positions.modifierFor("language_packs"),
            title = stringResource(R.string.language_packs),
        ) {
            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.language_packs)) },
                    description = stringResource(R.string.settings_language_packs_subtitle),
                    icon = { Icon(painterResource(R.drawable.translate), null) },
                    onClick = { navController.navigate("settings/language_packs") },
                )
            }
        }

        // "Providers" sub-page entry — opens the new LyricsProvidersSettings screen which
        // houses all provider toggles + experimental lyrics (Task 2). The inline provider
        // group that used to live below is moved there.
        PreferenceGroup(
            modifier = positions.modifierFor("lyrics_provider"),
            title = stringResource(R.string.providers),
        ) {
            item {
                PreferenceEntry(
                    modifier = positions.modifierFor("providers"),
                    title = { Text(stringResource(R.string.providers)) },
                    description = stringResource(R.string.settings_lyrics_providers_subtitle),
                    icon = { Icon(painterResource(R.drawable.lyrics), null) },
                    onClick = { navController.navigate("settings/lyrics/providers") },
                )
            }
        }

        // "Romanisation" sub-page entry — opens the new LyricsRomanisationSettings screen
        // which houses all per-language romanisation toggles (Task 3).
        PreferenceGroup(
            modifier = positions.modifierFor("lyrics_romanize"),
            title = stringResource(R.string.romanization),
        ) {
            item {
                PreferenceEntry(
                    modifier = positions.modifierFor("romanization"),
                    title = { Text(stringResource(R.string.romanization)) },
                    description = stringResource(R.string.settings_lyrics_romanisation_subtitle),
                    icon = { Icon(painterResource(R.drawable.lyrics), null) },
                    onClick = { navController.navigate("settings/lyrics/romanisation") },
                )
            }
        }

        PreferenceGroup(
            modifier = positions.modifierFor("lyrics_font_size"),
            title = stringResource(R.string.display),
        ) {
            // ── Lyrics mode picker ("V2 Legacy" / "Enhanced") and "Lyrics animation style"
            // entry removed by user request. Enhanced is the sole lyrics renderer now, so the
            // mode selector was redundant, and the animation style page only adjusted V2-specific
            // sliders (Bounce Amplitude / Glow Intensity / Fill Transition / Line Bounce Effect)
            // that no longer have a renderer to affect. The navigation route
            // "settings/appearance/lyrics_animations" and the LyricsAnimationSettings screen
            // are also removed (see NavigationBuilder.kt and the deleted file). ──

            item {
                SwitchPreference(
                    modifier = positions.modifierFor("lyrics_click"),
                    title = { Text(stringResource(R.string.lyrics_click_change)) },
                    icon = { Icon(painterResource(R.drawable.lyrics), null) },
                    checked = lyricsClick,
                    onCheckedChange = onLyricsClickChange,
                )
            }

            item {
                SwitchPreference(
                    modifier = positions.modifierFor("lyrics_scroll"),
                    title = { Text(stringResource(R.string.lyrics_auto_scroll)) },
                    icon = { Icon(painterResource(R.drawable.lyrics), null) },
                    checked = lyricsScroll,
                    onCheckedChange = onLyricsScrollChange,
                )
            }

            // ── Restored (2026-09-04) ──────────────────────────────────────────
            // "Show player controls" / "Auto-hide controls" toggles, back by
            // user request after the Sept 3→4 upstream port removed them together
            // with the Apple Music five-second auto-hide. The keys kept their
            // original names so previously-saved values continue to apply. The
            // description matches the restored behaviour: fade after 5s, tap to
            // bring back.
            item {
                SwitchPreference(
                    modifier = positions.modifierFor("show_lyrics_player_controls"),
                    title = { Text(stringResource(R.string.show_lyrics_player_controls)) },
                    icon = { Icon(painterResource(R.drawable.play), null) },
                    checked = showPlayerControls,
                    onCheckedChange = onShowPlayerControlsChange,
                )
            }

            item {
                SwitchPreference(
                    modifier = positions.modifierFor("auto_hide_lyrics_player_controls"),
                    title = { Text(stringResource(R.string.auto_hide_lyrics_player_controls)) },
                    description = stringResource(R.string.auto_hide_lyrics_player_controls_description),
                    icon = { Icon(painterResource(R.drawable.timer), null) },
                    checked = autoHidePlayerControls,
                    onCheckedChange = onAutoHidePlayerControlsChange,
                    isEnabled = showPlayerControls,
                )
            }

            item {
                SwitchPreference(
                    modifier = positions.modifierFor("lyrics_line_blur"),
                    title = { Text(stringResource(R.string.lyrics_line_blur)) },
                    icon = { Icon(painterResource(R.drawable.lyrics), null) },
                    checked = lyricsLineBlur,
                    onCheckedChange = onLyricsLineBlurChange,
                )
            }

            item {
                PreferenceEntry(
                    modifier = positions.modifierFor("lyrics_text_size"),
                    title = { Text(stringResource(R.string.lyrics_text_size)) },
                    description = "${lyricsTextSize.roundToInt()} sp",
                    icon = { Icon(painterResource(R.drawable.text_fields), null) },
                    onClick = { showLyricsTextSizeDialog = true },
                )
            }

            item {
                PreferenceEntry(
                    modifier = positions.modifierFor("lyrics_line_spacing"),
                    title = { Text(stringResource(R.string.lyrics_line_spacing)) },
                    description = "${String.format("%.1f", lyricsLineSpacing)}x",
                    icon = { Icon(painterResource(R.drawable.text_fields), null) },
                    onClick = { showLyricsLineSpacingDialog = true },
                )
            }
        }

        // Provider toggles, experimental lyrics, and romanisation settings have been moved
        // into dedicated sub-pages (see `settings/lyrics/providers` and
        // `settings/lyrics/romanisation` routes, plus the new entries above that navigate
        // to them). The inline groups that used to render them here are removed.

        PreferenceGroup(
            modifier = positions.modifierFor("lyrics_preload"),
            title = stringResource(R.string.queue),
        ) {
            // The count value is the SOLE control: 0 = off, >0 = pre-load that
            // many songs. The old master switch was removed because it was
            // confusing — users would set the count but the switch was off,
            // so nothing happened. Now the count picker is always visible and
            // shows "Off" when 0.
            item {
                NumberPickerPreference(
                    modifier = positions.modifierFor("preload_queue_lyrics"),
                    title = { Text(stringResource(R.string.preload_queue_lyrics)) },
                    icon = { Icon(painterResource(R.drawable.lyrics), null) },
                    value = queueLyricsPreloadCount,
                    onValueChange = onQueueLyricsPreloadCountChange,
                    minValue = 0,
                    maxValue = 10,
                    valueText = { if (it == 0) "Off" else it.toString() },
                )
            }
        }

    }

    // Header haze overlay — drawn ON TOP of the scrolling content (later
    // sibling), UNDER the transparent TopAppBar (emitted after it).
    ScreenHeaderHaze(
        hazeState = headerHaze,
        systemBarsTopPadding = systemBarsTopPadding,
    )

    TopAppBar(
        title = {},
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            ),
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
                    text = stringResource(R.string.lyrics),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        },
    )
    } // end full-screen haze Box
}

internal fun PreferredLyricsProvider.displayName(): String =
    when (this) {
        PreferredLyricsProvider.LRCLIB -> "LrcLib"
        PreferredLyricsProvider.KUGOU -> "KuGou"
        PreferredLyricsProvider.BETTER_LYRICS -> "BetterLyrics"
        PreferredLyricsProvider.BETTER_LYRICS_PORTATO -> "BetterLyrics Portato"
        PreferredLyricsProvider.YOULY_PLUS -> "YouLyPlus"
        // SIMPMUSIC and BINI_LYRICS cases removed per user request (2026-08-30).
        PreferredLyricsProvider.UNISON -> "Unison"
        // Ported from upstream 2026-08-31 window: Apple Music account lyrics
        // (via the logged-in Apple Music/pool account).
        PreferredLyricsProvider.APPLE_MUSIC -> "Apple Music (account)"
        PreferredLyricsProvider.MUSIXMATCH_EXPERIMENTAL -> "Musixmatch (experimental)"
    }

@Composable
internal fun LyricsProviderOrderDialog(
    initialOrder: List<PreferredLyricsProvider>,
    onDismiss: () -> Unit,
    onConfirm: (List<PreferredLyricsProvider>) -> Unit,
) {
    val providers = remember { mutableStateListOf(*initialOrder.toTypedArray()) }
    val lazyListState = rememberLazyListState()
    val reorderableState =
        rememberReorderableLazyListState(lazyListState) { from, to ->
            val item = providers.removeAt(from.index)
            providers.add(to.index, item)
        }

    DefaultDialog(
        onDismiss = onDismiss,
        constrainContentHeight = true,
        buttons = {
            TextButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(android.R.string.cancel))
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { onConfirm(providers.toList()) },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
    ) {
        Column(modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = stringResource(R.string.set_first_lyrics_provider),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            LazyColumn(
                state = lazyListState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp),
            ) {
                itemsIndexed(providers, key = { _, item -> item.name }) { index, provider ->
                    ReorderableItem(reorderableState, key = provider.name) {
                        val isFirst = index == 0
                        val containerColor =
                            if (isFirst) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            }
                        val contentColor =
                            if (isFirst) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }

                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = if (index < providers.size - 1) 4.dp else 0.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(containerColor)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = contentColor.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = provider.displayName(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = contentColor,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                painter = painterResource(R.drawable.drag_handle),
                                contentDescription = null,
                                tint = contentColor.copy(alpha = 0.6f),
                                modifier =
                                    Modifier
                                        .size(20.dp)
                                        .draggableHandle(),
                            )
                        }
                    }
                }
            }
        }
    }
}

