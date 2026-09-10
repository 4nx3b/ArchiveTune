/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package moe.rukamori.archivetune.ui.screens.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.*
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.ui.component.EditTextPreference
import moe.rukamori.archivetune.ui.component.FrostedHeaderPill
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.ListPreference
import moe.rukamori.archivetune.ui.component.MultiSelectListPreference
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.component.TextFieldDialog
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.utils.setAppLocale
import moe.rukamori.archivetune.viewmodels.AiContentFilterSettingsEffect
import moe.rukamori.archivetune.viewmodels.AiContentFilterSettingsState
import moe.rukamori.archivetune.viewmodels.ContentSettingsViewModel
import java.util.Locale
import moe.rukamori.archivetune.ui.screens.ScreenHeaderHaze
import moe.rukamori.archivetune.ui.screens.rememberScreenHeaderHaze
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import dev.chrisbanes.haze.hazeSource
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color

@Composable
fun ContentSettings(
    navController: NavController,
    viewModel: ContentSettingsViewModel = hiltViewModel(),
    scrollTo: String? = null,
) {
    val context = LocalContext.current
    val aiContentFilterState by viewModel.aiContentFilterState.collectAsStateWithLifecycle()
    val sponsorBlockSettingsViewModel: moe.rukamori.archivetune.viewmodels.SponsorBlockSettingsViewModel = hiltViewModel()
    val sponsorBlockSettingsState by
        sponsorBlockSettingsViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel, context) {
        viewModel.aiContentFilterEffects.collect { effect ->
            when (effect) {
                is AiContentFilterSettingsEffect.ShowMessage -> {
                    snackbarHostState.showSnackbar(context.getString(effect.messageResId))
                }

                is AiContentFilterSettingsEffect.OpenUrl -> {
                    context.startActivity(Intent(Intent.ACTION_VIEW, effect.url.toUri()))
                }
            }
        }
    }

    val (appLanguage, onAppLanguageChange) = rememberPreference(key = AppLanguageKey, defaultValue = SYSTEM_DEFAULT)

    val (contentLanguage, onContentLanguageChange) = rememberPreference(key = ContentLanguageKey, defaultValue = "system")
    val (contentCountry, onContentCountryChange) = rememberPreference(key = ContentCountryKey, defaultValue = "system")
    val (playlistSuggestionSource, onPlaylistSuggestionSourceChange) =
        rememberEnumPreference(
            key = PlaylistSuggestionSourceKey,
            defaultValue = PlaylistSuggestionSource.BOTH,
        )
    val (hideExplicit, onHideExplicitChange) = rememberPreference(key = HideExplicitKey, defaultValue = false)
    val (hideVideo, onHideVideoChange) = rememberPreference(key = HideVideoKey, defaultValue = false)
    val (homeCatalogueSwitch, onHomeCatalogueSwitchChange) =
        rememberPreference(key = HomeCatalogueSwitchKey, defaultValue = false)
    val (allowAgeRestricted, onAllowAgeRestrictedChange) = rememberPreference(key = AllowAgeRestrictedKey, defaultValue = false)
    val (lengthTop, onLengthTopChange) = rememberPreference(key = TopSize, defaultValue = "50")

    @Suppress("UNUSED_VARIABLE")
    val (quickPicks, onQuickPicksChange) = rememberEnumPreference(key = QuickPicksKey, defaultValue = QuickPicks.QUICK_PICKS)

    val scrollState = rememberScrollState()
    val positions = rememberPreferencePositions()

    LaunchedEffect(scrollTo) { positions.scrollToKey(scrollTo, scrollState) }

    val playerAwareBottomPadding =
        LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Bottom)
            .asPaddingValues()
            .calculateBottomPadding()

    val headerHaze = rememberScreenHeaderHaze()
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    androidx.compose.material3.Scaffold(
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
                            text = stringResource(R.string.content),
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
    val topPadding = innerPadding.calculateTopPadding()
    Box(modifier = Modifier.fillMaxSize()) {
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
            modifier = positions.modifierFor("content_language"),
            title = stringResource(R.string.general),
        ) {
            item {
                ListPreference(
                    title = { Text(stringResource(R.string.content_language)) },
                    icon = { Icon(painterResource(R.drawable.language), null) },
                    selectedValue = contentLanguage,
                    values = listOf(SYSTEM_DEFAULT) + LanguageCodeToName.keys.toList(),
                    valueText = {
                        LanguageCodeToName.getOrElse(it) { stringResource(R.string.system_default) }
                    },
                    onValueSelected = { newValue ->
                        val locale = Locale.getDefault()
                        val languageTag = locale.toLanguageTag().replace("-Hant", "")

                        YouTube.locale =
                            YouTube.locale.copy(
                                hl =
                                    newValue.takeIf { it != SYSTEM_DEFAULT }
                                        ?: locale.language.takeIf { it in LanguageCodeToName }
                                        ?: languageTag.takeIf { it in LanguageCodeToName }
                                        ?: "en",
                            )

                        onContentLanguageChange(newValue)
                    },
                )
            }

            item {
                ListPreference(
                    modifier = positions.modifierFor("content_country"),
                    title = { Text(stringResource(R.string.content_country)) },
                    icon = { Icon(painterResource(R.drawable.location_on), null) },
                    selectedValue = contentCountry,
                    values = listOf(SYSTEM_DEFAULT) + CountryCodeToName.keys.toList(),
                    valueText = {
                        CountryCodeToName.getOrElse(it) { stringResource(R.string.system_default) }
                    },
                    onValueSelected = { newValue ->
                        val locale = Locale.getDefault()

                        YouTube.locale =
                            YouTube.locale.copy(
                                gl =
                                    newValue.takeIf { it != SYSTEM_DEFAULT }
                                        ?: locale.country.takeIf { it in CountryCodeToName }
                                        ?: "US",
                            )

                        onContentCountryChange(newValue)
                    },
                )
            }

            item {
                ListPreference(
                    modifier = positions.modifierFor("you_might_like_source"),
                    title = { Text(stringResource(R.string.you_might_like_source)) },
                    icon = { Icon(painterResource(R.drawable.playlist_play), null) },
                    selectedValue = playlistSuggestionSource,
                    values =
                        listOf(
                            PlaylistSuggestionSource.PLAYLIST_TITLE,
                            PlaylistSuggestionSource.PLAYLIST_CONTENT,
                            PlaylistSuggestionSource.BOTH,
                        ),
                    valueText = {
                        when (it) {
                            PlaylistSuggestionSource.PLAYLIST_TITLE -> stringResource(R.string.playlist_suggestion_source_title)
                            PlaylistSuggestionSource.PLAYLIST_CONTENT -> stringResource(R.string.playlist_suggestion_source_content)
                            PlaylistSuggestionSource.BOTH -> stringResource(R.string.playlist_suggestion_source_both)
                        }
                    },
                    onValueSelected = onPlaylistSuggestionSourceChange,
                )
            }

            item {
                SwitchPreference(
                    modifier = positions.modifierFor("hide_explicit"),
                    title = { Text(stringResource(R.string.hide_explicit)) },
                    icon = { Icon(painterResource(R.drawable.explicit), null) },
                    checked = hideExplicit,
                    onCheckedChange = onHideExplicitChange,
                )
            }

            item {
                SwitchPreference(
                    modifier = positions.modifierFor("hide_video"),
                    title = { Text(stringResource(R.string.hide_video)) },
                    icon = { Icon(painterResource(R.drawable.slow_motion_video), null) },
                    checked = hideVideo,
                    onCheckedChange = onHideVideoChange,
                )
            }

            item {
                SwitchPreference(
                    modifier = positions.modifierFor("enable_catalogue_switch"),
                    title = { Text(stringResource(R.string.enable_catalogue_switch)) },
                    description = stringResource(R.string.enable_catalogue_switch_summary),
                    icon = { Icon(painterResource(R.drawable.sync), null) },
                    checked = homeCatalogueSwitch,
                    onCheckedChange = onHomeCatalogueSwitchChange,
                )
            }

            item {
                SwitchPreference(
                    modifier = positions.modifierFor("allow_age_restricted"),
                    title = { Text(stringResource(R.string.allow_age_restricted)) },
                    description = stringResource(R.string.allow_age_restricted_summary),
                    icon = { Icon(painterResource(R.drawable.login), null) },
                    checked = allowAgeRestricted,
                    onCheckedChange = onAllowAgeRestrictedChange,
                )
            }
        }

        AiContentFilterPreferences(
            state = aiContentFilterState,
            onEnabledChange = viewModel::setAiContentFilterEnabled,
            onIncludeModerateChange = viewModel::setAiContentFilterIncludeModerate,
            onRefresh = viewModel::refreshAiContentFilter,
            onOpenSource = viewModel::openAiContentFilterSource,
            positions = positions,
        )

        SponsorBlockPreferences(
            state = sponsorBlockSettingsState,
            onEnabledChange =
                remember(sponsorBlockSettingsViewModel) {
                    sponsorBlockSettingsViewModel::onEnabledChange
                },
            onCategorySheetOpen =
                remember(sponsorBlockSettingsViewModel) {
                    sponsorBlockSettingsViewModel::onCategorySheetOpen
                },
            onCategorySheetDismiss =
                remember(sponsorBlockSettingsViewModel) {
                    sponsorBlockSettingsViewModel::onCategorySheetDismiss
                },
            onCategoryCheckedChange =
                remember(sponsorBlockSettingsViewModel) {
                    sponsorBlockSettingsViewModel::onCategoryOptionCheckedChange
                },
            onCategorySelectionConfirm =
                remember(sponsorBlockSettingsViewModel) {
                    sponsorBlockSettingsViewModel::onCategorySelectionConfirm
                },
            onApiUrlEditorOpen =
                remember(sponsorBlockSettingsViewModel) {
                    sponsorBlockSettingsViewModel::onApiUrlEditorOpen
                },
            onApiUrlEditorDismiss =
                remember(sponsorBlockSettingsViewModel) {
                    sponsorBlockSettingsViewModel::onApiUrlEditorDismiss
                },
            onApiUrlDraftChange =
                remember(sponsorBlockSettingsViewModel) {
                    sponsorBlockSettingsViewModel::onApiUrlDraftChange
                },
            onApiUrlConfirm =
                remember(sponsorBlockSettingsViewModel) {
                    sponsorBlockSettingsViewModel::onApiUrlConfirm
                },
            onRetry =
                remember(sponsorBlockSettingsViewModel) {
                    sponsorBlockSettingsViewModel::retry
                },
        )

        PreferenceGroup(
            modifier = positions.modifierFor("app_language"),
            title = stringResource(R.string.app_language),
        ) {
            item {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.app_language)) },
                        icon = { Icon(painterResource(R.drawable.language), null) },
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APP_LOCALE_SETTINGS,
                                    "package:${context.packageName}".toUri(),
                                ),
                            )
                        },
                    )
                } else {
                    ListPreference(
                        title = { Text(stringResource(R.string.app_language)) },
                        icon = { Icon(painterResource(R.drawable.language), null) },
                        selectedValue = appLanguage,
                        values = listOf(SYSTEM_DEFAULT) + LanguageCodeToName.keys.toList(),
                        valueText = {
                            LanguageCodeToName.getOrElse(it) { stringResource(R.string.system_default) }
                        },
                        onValueSelected = { langTag ->
                            val newLocale =
                                langTag
                                    .takeUnless { it == SYSTEM_DEFAULT }
                                    ?.let { Locale.forLanguageTag(it) }
                                    ?: Locale.getDefault()

                            onAppLanguageChange(langTag)
                            setAppLocale(context, newLocale)
                        },
                    )
                }
            }
        }

        PreferenceGroup(
            modifier = positions.modifierFor("quick_picks"),
            title = stringResource(R.string.misc),
        ) {
            item {
                EditTextPreference(
                    modifier = positions.modifierFor("ai_content_filter"),
                    title = { Text(stringResource(R.string.top_length)) },
                    icon = { Icon(painterResource(R.drawable.trending_up), null) },
                    value = lengthTop,
                    isInputValid = { it.toIntOrNull()?.let { num -> num > 0 } == true },
                    onValueChange = onLengthTopChange,
                )
            }

        }
    }

        ScreenHeaderHaze(
            hazeState = headerHaze,
            systemBarsTopPadding = systemBarsTopPadding,
        )

    Box(Modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    }
    }
}

@Composable
private fun AiContentFilterPreferences(
    state: AiContentFilterSettingsState,
    onEnabledChange: (Boolean) -> Unit,
    onIncludeModerateChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onOpenSource: () -> Unit,
    positions: PreferencePositions,
) {
    PreferenceGroup(title = stringResource(R.string.ai_content_filter)) {
        when (state) {
            AiContentFilterSettingsState.Loading -> {
                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.ai_content_filter)) },
                        description = stringResource(R.string.loading),
                        icon = { Icon(painterResource(R.drawable.auto_awesome), null) },
                        isEnabled = false,
                    )
                }
            }

            AiContentFilterSettingsState.Empty -> {
                Unit
            }

            is AiContentFilterSettingsState.Error -> {
                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.ai_content_filter)) },
                        description = stringResource(state.messageResId),
                        icon = { Icon(painterResource(R.drawable.auto_awesome), null) },
                        onClick = onRefresh,
                    )
                }
            }

            is AiContentFilterSettingsState.Success -> {
                val model = state.model
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("ai_content_filter_hide"),
                        title = { Text(stringResource(R.string.ai_content_filter_hide)) },
                        description = stringResource(R.string.ai_content_filter_hide_summary),
                        icon = { Icon(painterResource(R.drawable.auto_awesome), null) },
                        checked = model.enabled,
                        onCheckedChange = onEnabledChange,
                    )
                }
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("ai_content_filter_moderate"),
                        title = { Text(stringResource(R.string.ai_content_filter_moderate)) },
                        description = stringResource(R.string.ai_content_filter_moderate_summary),
                        icon = { Icon(painterResource(R.drawable.filter_alt), null) },
                        checked = model.includeModerateConfidence,
                        onCheckedChange = onIncludeModerateChange,
                        isEnabled = model.enabled,
                    )
                }
                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("ai_content_filter_update"),
                        title = { Text(stringResource(R.string.ai_content_filter_update)) },
                        description =
                            if (model.refreshing) {
                                stringResource(R.string.loading)
                            } else {
                                stringResource(
                                    R.string.ai_content_filter_list_counts,
                                    model.blocklistCount,
                                    model.warnlistCount,
                                )
                            },
                        icon = { Icon(painterResource(R.drawable.sync), null) },
                        onClick = onRefresh,
                        isEnabled = !model.refreshing,
                    )
                }
                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("ai_content_filter_source"),
                        title = { Text(stringResource(R.string.ai_content_filter_source)) },
                        description = stringResource(R.string.ai_content_filter_source_summary),
                        icon = { Icon(painterResource(R.drawable.info), null) },
                        onClick = onOpenSource,
                    )
                }
            }
        }
    }
}

/**
 * SponsorBlock group (ported from the upstream player settings, moved here
 * into Content settings per user request): enable switch, category
 * multi-select and API URL override.
 */
@Composable
private fun SponsorBlockPreferences(
    state: moe.rukamori.archivetune.viewmodels.SponsorBlockSettingsScreenState,
    onEnabledChange: (Boolean) -> Unit,
    onCategorySheetOpen: () -> Unit,
    onCategorySheetDismiss: () -> Unit,
    onCategoryCheckedChange: (moe.rukamori.archivetune.viewmodels.SponsorBlockCategoryUiModel, Boolean) -> Unit,
    onCategorySelectionConfirm: () -> Unit,
    onApiUrlEditorOpen: () -> Unit,
    onApiUrlEditorDismiss: () -> Unit,
    onApiUrlDraftChange: (String) -> Unit,
    onApiUrlConfirm: () -> Unit,
    onRetry: () -> Unit,
) {
    val data = (state as? moe.rukamori.archivetune.viewmodels.SponsorBlockSettingsScreenState.Success)?.data
    val controlsEnabled = data != null
    val configurationEnabled = controlsEnabled && data?.enabled == true
    val categoryOptions = data?.categoryOptions ?: emptyList()
    val draftCategoryOptions = data?.draftCategoryOptions ?: emptyList()

    PreferenceGroup(title = stringResource(R.string.sponsor_block_group)) {
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.sponsor_block_use)) },
                icon = { Icon(painterResource(R.drawable.block), null) },
                checked = data?.enabled ?: false,
                onCheckedChange = onEnabledChange,
                isEnabled = controlsEnabled,
            )
        }

        item {
            val selectedCount = data?.selectedCategoryOptions?.size ?: 0
            MultiSelectListPreference(
                title = { Text(stringResource(R.string.sponsor_block_categories)) },
                description = stringResource(R.string.sponsor_block_categories_desc),
                icon = { Icon(painterResource(R.drawable.fast_forward), null) },
                values = categoryOptions,
                checkedValues = draftCategoryOptions,
                selectionText =
                    androidx.compose.ui.res.pluralStringResource(
                        R.plurals.n_selected,
                        selectedCount,
                        selectedCount,
                    ),
                valueText = { option -> stringResource(option.labelRes) },
                isBottomSheetVisible = data?.isCategorySheetVisible == true,
                onOpen = onCategorySheetOpen,
                onDismiss = onCategorySheetDismiss,
                onValueCheckedChange = onCategoryCheckedChange,
                onConfirm = onCategorySelectionConfirm,
                isEnabled = configurationEnabled,
            )
        }

        item {
            PreferenceEntry(
                title = { Text(stringResource(R.string.sponsor_block_api_url)) },
                description = data?.apiUrl ?: moe.rukamori.archivetune.sponsorblock.DEFAULT_SPONSOR_BLOCK_API_URL,
                icon = { Icon(painterResource(R.drawable.link), null) },
                onClick = onApiUrlEditorOpen,
                isEnabled = configurationEnabled,
            )
        }

        if (state is moe.rukamori.archivetune.viewmodels.SponsorBlockSettingsScreenState.Error) {
            item {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.retry)) },
                    description = stringResource(state.messageRes),
                    onClick = onRetry,
                )
            }
        }
    }

    SponsorBlockApiUrlDialog(
        state = state,
        onValueChange = onApiUrlDraftChange,
        onConfirm = onApiUrlConfirm,
        onDismiss = onApiUrlEditorDismiss,
    )
}

@Composable
private fun SponsorBlockApiUrlDialog(
    state: moe.rukamori.archivetune.viewmodels.SponsorBlockSettingsScreenState,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val data = (state as? moe.rukamori.archivetune.viewmodels.SponsorBlockSettingsScreenState.Success)?.data ?: return
    if (!data.isApiUrlEditorVisible) return
    val isInputValid =
        remember(data.isApiUrlDraftValid) {
            { _: String -> data.isApiUrlDraftValid }
        }
    val confirmValue =
        remember(onConfirm) {
            { _: String -> onConfirm() }
        }

    TextFieldDialog(
        title = { Text(stringResource(R.string.sponsor_block_api_url)) },
        textFieldValue = data.apiUrlDraft,
        onTextFieldValueChange = onValueChange,
        keyboardOptions =
            androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            ),
        isInputValid = isInputValid,
        dismissOnDone = false,
        onDone = confirmValue,
        onDismiss = onDismiss,
    )
}
