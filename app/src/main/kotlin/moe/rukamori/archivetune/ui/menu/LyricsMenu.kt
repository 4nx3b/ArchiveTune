/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.menu

import android.app.SearchManager
import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.drawBackdrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bush.translator.Language
import me.bush.translator.Translator
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ai.AiLyricsDocumentParser
import moe.rukamori.archivetune.ai.AiLyricsSegment
import moe.rukamori.archivetune.constants.AiApiKeyKey
import moe.rukamori.archivetune.constants.AiApiValidationStatus
import moe.rukamori.archivetune.constants.AiApiValidationStatusKey
import moe.rukamori.archivetune.constants.AiCustomEndpointKey
import moe.rukamori.archivetune.constants.AutoHideLyricsPlayerControlsKey
import moe.rukamori.archivetune.constants.AiProvider
import moe.rukamori.archivetune.constants.AiProviderKey
import moe.rukamori.archivetune.constants.TranslatorTargetLangKey
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.lyrics.AiLyricsRomanization
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.LocalLiquidGlassBackdrop
import moe.rukamori.archivetune.ui.component.MenuSurfaceSection
import moe.rukamori.archivetune.ui.component.NewAction
import moe.rukamori.archivetune.ui.component.NewActionGrid
import moe.rukamori.archivetune.ui.component.NewMenuItem
import moe.rukamori.archivetune.ui.component.PlatformBackdrop
import moe.rukamori.archivetune.ui.component.TextFieldDialog
import moe.rukamori.archivetune.utils.TranslatorLang
import moe.rukamori.archivetune.utils.TranslatorLanguages
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.LyricsMenuViewModel
import moe.rukamori.archivetune.viewmodels.LyricsSearchResultUiModel
import moe.rukamori.archivetune.viewmodels.LyricsSearchScreenState
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import moe.rukamori.archivetune.ui.component.KeepStatusBarHiddenInDialog

private enum class LyricsTranslationSource {
    AI_TRANSLATION,
    TRANSLATION,
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LyricsMenu(
    lyricsProvider: () -> LyricsEntity?,
    mediaMetadataProvider: () -> MediaMetadata,
    lyricsSyncOffset: Int,
    onLyricsSyncOffsetChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    viewModel: LyricsMenuViewModel = hiltViewModel(),
    // Restored (2026-09-04): the control preferences are optional because the standalone
    // lyrics screen owns its own state; callers that do not provide callbacks keep the menu
    // focused on lyric actions only. The Apple Music player's anchored popup passes them so
    // the two "Show / Auto-hide player controls" toggles appear again (they were removed by
    // the Sept 3→4 upstream port together with the auto-hide behaviour itself).
    showPlayerControlsState: State<Boolean>? = null,
    onShowPlayerControlsChange: ((Boolean) -> Unit)? = null,
    onAutoHidePlayerControlsChange: (Boolean) -> Unit = {},
    showControlsToggles: Boolean = false,
    // When true, the outer `MenuSurfaceSection` card (which is otherwise an
    // OPAQUE `surfaceContainerLow` Surface) is replaced with a TRANSPARENT
    // Surface of the same shape. Used by `AnchoredLyricsOverflowMenu` so the
    // frosted-glass blur applied to the popup's outer Box is NOT hidden by
    // an opaque white card sitting on top of it. Without this, the user
    // sees a plain white popup instead of the intended frosted-glass look
    // (user report 2026-08-30: "the white popup is loading on top of the
    // liquid glass effect").
    transparentSurface: Boolean = false,
) {
    val context = LocalContext.current
    // Restored (2026-09-04): shared auto-hide preference, so the toggle row in
    // this menu and the Lyrics settings entry write the same DataStore value.
    val showPlayerControls = showPlayerControlsState?.value ?: true
    val (autoHidePlayerControls, onAutoHidePlayerControlsPreferenceChange) =
        rememberPreference(AutoHideLyricsPlayerControlsKey, defaultValue = true)

    var showEditDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var showTranslateDialog by rememberSaveable { mutableStateOf(false) }
    var showLyricsSyncOffsetDialog by rememberSaveable { mutableStateOf(false) }
    val isRefetching by viewModel.isRefetching.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.refetchCompletionEvents.collect {
            onDismiss()
        }
    }

    if (showEditDialog) {
        TextFieldDialog(
            onDismiss = { showEditDialog = false },
            icon = { Icon(painter = painterResource(R.drawable.edit), contentDescription = null) },
            title = { Text(text = mediaMetadataProvider().title) },
            initialTextFieldValue = TextFieldValue(lyricsProvider()?.lyrics.orEmpty()),
            singleLine = false,
            onDone = {
                viewModel.updateLyrics(mediaMetadataProvider(), it)
            },
        )
    }

    var showSearchDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showSearchResultDialog by rememberSaveable {
        mutableStateOf(false)
    }

    val searchMediaMetadata =
        remember(showSearchDialog) {
            mediaMetadataProvider()
        }
    val (titleField, onTitleFieldChange) =
        rememberSaveable(showSearchDialog, stateSaver = TextFieldValue.Saver) {
            mutableStateOf(
                TextFieldValue(
                    text = mediaMetadataProvider().title,
                ),
            )
        }
    val (artistField, onArtistFieldChange) =
        rememberSaveable(showSearchDialog, stateSaver = TextFieldValue.Saver) {
            mutableStateOf(
                TextFieldValue(
                    text = mediaMetadataProvider().artists.joinToString { it.name },
                ),
            )
        }

    val isNetworkAvailable by viewModel.isNetworkAvailable.collectAsStateWithLifecycle()
    val lyricsSearchState by viewModel.lyricsSearchState.collectAsStateWithLifecycle()
    val isAiTranslating by viewModel.isAiTranslating.collectAsStateWithLifecycle()
    val translationUndo by viewModel.translationUndo.collectAsStateWithLifecycle()
    val (aiProvider) = rememberEnumPreference(AiProviderKey, AiProvider.NONE)
    val (aiApiKey) = rememberPreference(AiApiKeyKey, "")
    val (aiCustomEndpoint) = rememberPreference(AiCustomEndpointKey, "")
    val (aiValidationStatus) = rememberEnumPreference(AiApiValidationStatusKey, AiApiValidationStatus.UNKNOWN)
    var expandedSearchResultId by rememberSaveable { mutableStateOf<String?>(null) }
    val currentLyrics = lyricsProvider()?.lyrics.orEmpty()
    val isTranslateEnabled =
        currentLyrics.isNotBlank() &&
            currentLyrics != LyricsEntity.LYRICS_NOT_FOUND
    val canUndoTranslation = translationUndo?.mediaId == mediaMetadataProvider().id
    val isAiProviderConfigured = aiProvider != AiProvider.NONE
    val isAiTranslationEnabled =
        currentLyrics.isNotBlank() &&
            currentLyrics != LyricsEntity.LYRICS_NOT_FOUND &&
            isAiProviderConfigured &&
            aiApiKey.isNotBlank() &&
            (aiProvider != AiProvider.CUSTOM || aiCustomEndpoint.isNotBlank()) &&
            aiValidationStatus != AiApiValidationStatus.FAILED

    var translationJob by remember { mutableStateOf<Job?>(null) }
    var isStandardTranslating by remember { mutableStateOf(false) }
    var isDialogAiTranslationRunning by rememberSaveable { mutableStateOf(false) }

    // ── AI romanisation ──
    // The manual counterpart to the "Auto AI Romanisation" setting: with auto off the renderers never
    // send a request on their own, so without this the master switch would silently disable the
    // built-in romanisers and offer nothing in their place. Requesting is idempotent per track — the
    // coordinator joins an in-flight call and serves a cached one — so a second tap is free.
    val aiRomanizationSettings = AiLyricsRomanization.rememberSettings()
    val isAiRomanizing by AiLyricsRomanization.running.collectAsStateWithLifecycle()
    val isAiRomanizationEnabled =
        aiRomanizationSettings.active &&
            currentLyrics.isNotBlank() &&
            currentLyrics != LyricsEntity.LYRICS_NOT_FOUND &&
            !isAiRomanizing

    LaunchedEffect(Unit) {
        viewModel.aiTranslationEvents.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Terminal outcomes for async AI romanisation. The synchronous branches (SETTINGS_DISABLED,
    // NO_LYRICS, EXCLUDED_LANGUAGE, NO_ROMANIZABLE_SCRIPT, IN_FLIGHT, ALREADY_CACHED, STARTED) are
    // returned from `AiLyricsRomanization.request` and the click handler toasts immediately. Only
    // EMPTY_RESULT materialises later — when the model returns all-identical-to-source echoes or
    // all-null entries — so we surface it here as a follow-up toast so the user understands why the
    // "Romanising lyrics with AI…" toast they saw on click did not result in any visible
    // romanisation. See [AiLyricsRomanization.requestOutcomes] for the rationale.
    LaunchedEffect(Unit) {
        AiLyricsRomanization.requestOutcomes.collect { status ->
            if (status == AiLyricsRomanization.RequestStatus.EMPTY_RESULT) {
                Toast
                    .makeText(context, context.getString(R.string.ai_romanize_empty_result), Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    if (isRefetching) {
        DefaultDialog(onDismiss = {}) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(12.dp),
            ) {
                LoadingIndicator(modifier = Modifier.size(40.dp))
            }
        }
    }

    if (showSearchDialog) {
        SearchLyricsInputDialog(
            titleField = titleField,
            onTitleFieldChange = onTitleFieldChange,
            artistField = artistField,
            onArtistFieldChange = onArtistFieldChange,
            onDismiss = { showSearchDialog = false },
            onSearchOnline = {
                showSearchDialog = false
                onDismiss()
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_WEB_SEARCH).apply {
                            putExtra(
                                SearchManager.QUERY,
                                "${artistField.text} ${titleField.text} lyrics",
                            )
                        },
                    )
                } catch (_: Exception) {
                }
            },
            onSearch = {
                viewModel.search(
                    searchMediaMetadata.id,
                    titleField.text,
                    artistField.text,
                    searchMediaMetadata.album?.title,
                    searchMediaMetadata.duration,
                )
                showSearchResultDialog = true

                if (!isNetworkAvailable) {
                    Toast.makeText(context, context.getString(R.string.error_no_internet), Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    if (showSearchResultDialog) {
        LyricsSearchResultDialog(
            state = lyricsSearchState,
            expandedResultId = expandedSearchResultId,
            onExpandedResultChange = { resultId ->
                expandedSearchResultId = if (expandedSearchResultId == resultId) null else resultId
            },
            onRefetch = {
                expandedSearchResultId = null
                viewModel.search(
                    searchMediaMetadata.id,
                    titleField.text,
                    artistField.text,
                    searchMediaMetadata.album?.title,
                    searchMediaMetadata.duration,
                )
            },
            onResultSelected = { result ->
                onDismiss()
                viewModel.cancelSearch()
                viewModel.updateLyrics(
                    mediaMetadata = searchMediaMetadata,
                    lyrics = result.lyrics,
                    source = LyricsEntity.Source.USER_SELECTION,
                    providerName = result.providerName,
                )
            },
            onDismiss = {
                expandedSearchResultId = null
                showSearchResultDialog = false
                viewModel.resetSearchState()
            },
        )
    }

    if (showLyricsSyncOffsetDialog) {
        var tempLyricsSyncOffset by remember { mutableFloatStateOf(lyricsSyncOffset.toFloat()) }

        DefaultDialog(
            onDismiss = {
                tempLyricsSyncOffset = lyricsSyncOffset.toFloat()
                showLyricsSyncOffsetDialog = false
            },
            icon = {
                Icon(painter = painterResource(R.drawable.speed), contentDescription = null)
            },
            title = { Text(stringResource(R.string.lyrics_sync_offset)) },
            buttons = {
                TextButton(
                    onClick = { tempLyricsSyncOffset = 0f },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.reset))
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        tempLyricsSyncOffset = lyricsSyncOffset.toFloat()
                        showLyricsSyncOffsetDialog = false
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        onLyricsSyncOffsetChange(tempLyricsSyncOffset.roundToInt())
                        showLyricsSyncOffsetDialog = false
                        onDismiss()
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
                    text = formatLyricsSyncOffset(tempLyricsSyncOffset.roundToInt()),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                Slider(
                    value = tempLyricsSyncOffset,
                    onValueChange = { tempLyricsSyncOffset = it },
                    valueRange = -1000f..1000f,
                    steps = 79,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(4.dp))

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val defaultLanguageCode =
        remember(configuration) {
            configuration.locales
                .get(0)
                .getDisplayLanguage(Locale.ENGLISH)
                .uppercase(Locale.US)
                .replace(' ', '_')
        }
    val (targetLanguage, setTargetLanguage) = rememberPreference(TranslatorTargetLangKey, defaultLanguageCode)
    val isTranslationInProgress = isStandardTranslating || isAiTranslating

    if (showTranslateDialog) {
        val initialText = lyricsProvider()?.lyrics.orEmpty()
        val (textFieldValue, setTextFieldValue) =
            rememberSaveable(stateSaver = TextFieldValue.Saver) {
                mutableStateOf(TextFieldValue(text = initialText))
            }

        val languages by produceState(initialValue = emptyList<TranslatorLang>()) {
            withContext(Dispatchers.IO) {
                value = TranslatorLanguages.load(context)
            }
        }
        var sourceExpanded by remember { mutableStateOf(false) }
        var languageExpanded by remember { mutableStateOf(false) }
        var selectedSource by rememberSaveable {
            mutableStateOf(
                if (isAiTranslationEnabled) {
                    LyricsTranslationSource.AI_TRANSLATION
                } else {
                    LyricsTranslationSource.TRANSLATION
                },
            )
        }
        var selectedLanguageCode by rememberSaveable { mutableStateOf(targetLanguage.ifBlank { defaultLanguageCode }) }
        val selectedLanguageName =
            languages.firstOrNull { it.code == selectedLanguageCode }?.name ?: selectedLanguageCode
        val canUseSelectedSource = selectedSource != LyricsTranslationSource.AI_TRANSLATION || isAiTranslationEnabled

        LaunchedEffect(isAiTranslationEnabled) {
            if (!isAiTranslationEnabled && selectedSource == LyricsTranslationSource.AI_TRANSLATION) {
                selectedSource = LyricsTranslationSource.TRANSLATION
            }
        }

        LaunchedEffect(isAiTranslating, isDialogAiTranslationRunning) {
            if (isDialogAiTranslationRunning && !isAiTranslating) {
                isDialogAiTranslationRunning = false
                showTranslateDialog = false
            }
        }

        BasicAlertDialog(
            onDismissRequest = {},
            properties =
                DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false,
                ),
            modifier =
                Modifier
                    .padding(24.dp)
                    .navigationBarsPadding()
                    .imePadding(),
        ) {
            Surface(
                shape = AlertDialogDefaults.shape,
                color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation,
                modifier = Modifier.widthIn(max = 560.dp),
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.translate),
                        contentDescription = null,
                        tint = AlertDialogDefaults.iconContentColor,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.translate),
                        style = MaterialTheme.typography.headlineSmall,
                        color = AlertDialogDefaults.titleContentColor,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(16.dp))
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        OutlinedTextField(
                            value = textFieldValue,
                            onValueChange = setTextFieldValue,
                            enabled = !isTranslationInProgress,
                            singleLine = false,
                            label = { Text(stringResource(R.string.lyrics)) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 80.dp, max = 220.dp),
                        )

                        Spacer(Modifier.height(12.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.source),
                                modifier = Modifier.width(96.dp),
                            )

                            ExposedDropdownMenuBox(
                                expanded = sourceExpanded,
                                onExpandedChange = {
                                    if (!isTranslationInProgress) sourceExpanded = it
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                OutlinedTextField(
                                    value =
                                        when (selectedSource) {
                                            LyricsTranslationSource.AI_TRANSLATION -> stringResource(R.string.ai_translation_menu)
                                            LyricsTranslationSource.TRANSLATION -> stringResource(R.string.translate)
                                        },
                                    onValueChange = {},
                                    enabled = !isTranslationInProgress,
                                    readOnly = true,
                                    singleLine = true,
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceExpanded)
                                    },
                                    modifier =
                                        Modifier
                                            .menuAnchor()
                                            .fillMaxWidth(),
                                )

                                ExposedDropdownMenu(
                                    expanded = sourceExpanded,
                                    onDismissRequest = { sourceExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.ai_translation_menu)) },
                                        enabled = isAiTranslationEnabled,
                                        onClick = {
                                            selectedSource = LyricsTranslationSource.AI_TRANSLATION
                                            sourceExpanded = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.translate)) },
                                        onClick = {
                                            selectedSource = LyricsTranslationSource.TRANSLATION
                                            sourceExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.language_label),
                                modifier = Modifier.width(96.dp),
                            )

                            ExposedDropdownMenuBox(
                                expanded = languageExpanded,
                                onExpandedChange = {
                                    if (!isTranslationInProgress) languageExpanded = it
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                OutlinedTextField(
                                    value = selectedLanguageName,
                                    onValueChange = {},
                                    enabled = !isTranslationInProgress,
                                    readOnly = true,
                                    singleLine = true,
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded)
                                    },
                                    modifier =
                                        Modifier
                                            .menuAnchor()
                                            .fillMaxWidth(),
                                )

                                ExposedDropdownMenu(
                                    expanded = languageExpanded,
                                    onDismissRequest = { languageExpanded = false },
                                ) {
                                    languages.forEach { lang ->
                                        DropdownMenuItem(
                                            text = { Text(lang.name) },
                                            onClick = {
                                                selectedLanguageCode = lang.code
                                                setTargetLanguage(lang.code)
                                                languageExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                translationJob?.cancel()
                                translationJob = null
                                isStandardTranslating = false
                                if (isAiTranslating) {
                                    viewModel.cancelAiTranslation()
                                }
                                isDialogAiTranslationRunning = false
                                showTranslateDialog = false
                            },
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(stringResource(android.R.string.cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            enabled = !isTranslationInProgress && canUseSelectedSource,
                            onClick = {
                                val inputText = textFieldValue.text
                                val languageCode = selectedLanguageCode
                                val languageName = selectedLanguageName
                                setTargetLanguage(languageCode)

                                when (selectedSource) {
                                    LyricsTranslationSource.AI_TRANSLATION -> {
                                        isDialogAiTranslationRunning = true
                                        viewModel.translateLyricsWithAi(
                                            mediaMetadata = mediaMetadataProvider(),
                                            lyrics = inputText,
                                            targetLanguage = languageCode,
                                        )
                                    }

                                    LyricsTranslationSource.TRANSLATION -> {
                                        isStandardTranslating = true
                                        translationJob =
                                            coroutineScope.launch {
                                                try {
                                                    val lang =
                                                        try {
                                                            Language(languageCode)
                                                        } catch (e: Exception) {
                                                            try {
                                                                Language(languageName)
                                                            } catch (_: Exception) {
                                                                null
                                                            }
                                                        }

                                                    if (lang == null) {
                                                        Toast
                                                            .makeText(
                                                                context,
                                                                context.getString(R.string.unsupported_language, languageName),
                                                                Toast.LENGTH_SHORT,
                                                            ).show()
                                                        return@launch
                                                    }

                                                    val translatedLyrics = translateLyricsWithTranslator(inputText, lang)
                                                    viewModel.updateLyrics(
                                                        mediaMetadata = mediaMetadataProvider(),
                                                        lyrics = translatedLyrics,
                                                        source = LyricsEntity.Source.AI_TRANSLATION,
                                                    )
                                                    showTranslateDialog = false
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    Toast
                                                        .makeText(
                                                            context,
                                                            context.getString(R.string.translation_failed) + ": " +
                                                                (e.localizedMessage ?: e.toString()),
                                                            Toast.LENGTH_SHORT,
                                                        ).show()
                                                } finally {
                                                    isStandardTranslating = false
                                                    translationJob = null
                                                }
                                            }
                                    }
                                }
                            },
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            if (isTranslationInProgress) {
                                LoadingIndicator(modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.translate))
                        }
                    }
                }
            }
        }
    }

    LazyColumn(
        userScrollEnabled = true,
        contentPadding =
            PaddingValues(
                start = 0.dp,
                top = 0.dp,
                end = 0.dp,
                bottom = 12.dp,
            ),
    ) {
        item {
            // ─────────────────────────────────────────────────────────────────────────
            // Apple Music–style lyrics overflow menu (user request 2026-08-30):
            // "I've attached a screenshot reference. I want the same Lyrics ui
            //  overflow menu but only in apple music style. Recreate the exact
            //  same design but keep the present features from my app"
            //
            // The screenshot shows: a dark translucent sheet, no header, a vertical
            // list of action rows separated by hairline (0.5dp) dividers. Each row
            // is ~57dp tall, text is LEFT-aligned, a 22dp icon sits on the FAR RIGHT
            // edge, font is ~17pt regular. Destructive items ("Delete from Library"
            // in the screenshot) are tinted `error` red — we mirror that on
            // "Undo Translation" because it is the closest analogue (an undo/remove
            // action). All other rows use `onSurface` text + `onSurfaceVariant`
            // icon, matching Apple Music's white-text/light-grey-icon.
            //
            // Outer card: existing `MenuSurfaceSection` (rounded extra-large surface,
            // `surfaceContainerLow` color — adapts to light/dark theme, matching
            // Apple Music's dark card on dark theme).
            //
            // Inner rows: built from the local `AppleMusicLyricsMenuRow` helper
            // (defined at the bottom of this file) — a `Surface(onClick)` wrapping a
            // Material3 `ListItem` with the icon in `trailingContent` (right edge),
            // `headlineContent` = `Text(label)` on the left. Hairline dividers
            // (`HorizontalDivider`, `outlineVariant` color, `0.5.dp` thickness) are
            // emitted BETWEEN items only (no divider before the first or after the
            // last), matching Apple Music's grid-separated look.
            //
            // The 6 action rows preserve the exact same handlers as the previous
            // `NewActionGrid` (Edit / Refetch / Translate / AI Romanise Now /
            // Undo Translation / Search). No features were removed; only the
            // visual presentation changed.
            // ─────────────────────────────────────────────────────────────────────────
            val lyricsText = lyricsProvider()?.lyrics.orEmpty()
            val menuItems: List<AppleMusicLyricsMenuItem> =
                listOf(
                    AppleMusicLyricsMenuItem(
                        label = stringResource(R.string.edit),
                        iconRes = R.drawable.edit,
                        isDestructive = false,
                        enabled = true,
                        onClick = { showEditDialog = true },
                    ),
                    AppleMusicLyricsMenuItem(
                        label = stringResource(R.string.refetch),
                        iconRes = R.drawable.cached,
                        isDestructive = false,
                        enabled = true,
                        onClick = {
                            viewModel.refetchLyrics(mediaMetadataProvider())
                        },
                    ),
                    AppleMusicLyricsMenuItem(
                        label = stringResource(R.string.translate),
                        iconRes = R.drawable.translate,
                        isDestructive = false,
                        enabled = isTranslateEnabled,
                        onClick = { showTranslateDialog = true },
                    ),
                    AppleMusicLyricsMenuItem(
                        label = stringResource(R.string.ai_romanize_now),
                        iconRes = R.drawable.language,
                        isDestructive = false,
                        enabled = isAiRomanizationEnabled,
                        onClick = {
                            // ── Manual AI romanisation — explicit feedback per outcome ──
                            // Previously the toast fired unconditionally before `request()`,
                            // which silently no-op'd in several cases (excluded language,
                            // all-Latin lyrics, in-flight, settings disabled). The user reported
                            // "shows a toast when I click on it but never romanises anything"
                            // — that was the SETTINGS_DISABLED / EXCLUDED_LANGUAGE /
                            // NO_ROMANIZABLE_SCRIPT / EMPTY_RESULT branches. Now we surface
                            // each outcome with a specific toast so the failure mode is
                            // actionable. The renderer (LyricsEnhanced.kt:495) already observes
                            // `AiLyricsRomanization.results`; once a `STARTED` or
                            // `ALREADY_CACHED` call publishes, the lyrics re-resolve.
                            // A `nonce` field on `Result` defeats the StateFlow equality
                            // masking on cache-hit re-publish so even a second tap on a cached
                            // result re-emits and the renderer re-resolves.
                            val status = AiLyricsRomanization.request(
                                sessionKey =
                                    AiLyricsRomanization.sessionKey(
                                        mediaId = mediaMetadataProvider().id,
                                        lyrics = lyricsText,
                                    ),
                                lines = AiLyricsRomanization.linesOf(lyricsText, mediaMetadataProvider().duration),
                                settings = aiRomanizationSettings,
                                // force = true: this is the manual menu action. The user explicitly
                                // asked the AI to romanise, so we hand the lyrics to the model even
                                // when they look Latin-script. The model is instructed to echo
                                // Latin lines unchanged, so the visible effect on Latin lyrics is
                                // still "nothing changes" — but the user no longer sees the
                                // misleading "nothing to romanise" toast that said the click did
                                // nothing.
                                force = true,
                            )
                            val toastResId = when (status) {
                                AiLyricsRomanization.RequestStatus.STARTED -> R.string.ai_romanize_started
                                AiLyricsRomanization.RequestStatus.ALREADY_CACHED -> R.string.ai_romanize_already_cached
                                AiLyricsRomanization.RequestStatus.IN_FLIGHT -> R.string.ai_romanize_in_flight
                                AiLyricsRomanization.RequestStatus.SETTINGS_DISABLED -> R.string.ai_romanize_settings_disabled
                                AiLyricsRomanization.RequestStatus.NO_LYRICS -> R.string.ai_romanize_no_lyrics
                                AiLyricsRomanization.RequestStatus.EXCLUDED_LANGUAGE -> R.string.ai_romanize_excluded_language
                                AiLyricsRomanization.RequestStatus.NO_ROMANIZABLE_SCRIPT -> R.string.ai_romanize_no_romanizable_script
                                AiLyricsRomanization.RequestStatus.EMPTY_RESULT -> R.string.ai_romanize_empty_result
                            }
                            Toast
                                .makeText(context, context.getString(toastResId), Toast.LENGTH_SHORT)
                                .show()
                        },
                    ),
                    AppleMusicLyricsMenuItem(
                        label = stringResource(R.string.undo_translation),
                        iconRes = R.drawable.restore,
                        // Apple Music styles "Delete from Library" (the destructive-action
                        // row in the screenshot) as bright-red text + red icon. Our closest
                        // analogue is "Undo Translation" — a remove/restore action — so we
                        // give it the same `error`-tinted treatment.
                        isDestructive = true,
                        enabled = canUndoTranslation,
                        onClick = { viewModel.undoTranslation(mediaMetadataProvider().id) },
                    ),
                    // "Lyrics sync offset" action removed per user request
                    // (2026-08-28): "Remove lyrics sync offset from
                    // lyrics overflow menu from apple music and non
                    // apple music styles". The internal
                    // `lyricsSyncOffset` plumbing is preserved so
                    // any user who previously set an offset still
                    // has it applied (LyricsEnhanced.kt reads the
                    // value during line-timing computation), but
                    // the menu entry that let them change it is
                    // gone. The `showLyricsSyncOffsetDialog` state
                    // and the dialog block below are kept as dead
                    // code so we don't have to thread-break the
                    // function signature.
                    AppleMusicLyricsMenuItem(
                        label = stringResource(R.string.search),
                        iconRes = R.drawable.search,
                        isDestructive = false,
                        enabled = true,
                        onClick = { showSearchDialog = true },
                    ),
                )

            // Two visual presentations share this one menu implementation:
            //
            //  - `transparentSurface = true` (Apple Music player's anchored popup):
            //    transparent Surface so the frosted-glass blur applied to the popup's
            //    outer Box is visible, with the compact dark-glass Apple Music rows
            //    (white text, iOS-red destructive row, hairline dividers) from
            //    batches 12-15.
            //
            //  - `transparentSurface = false` (non-Apple-Music player styles —
            //    LyricsScreen's ModalBottomSheet slide-up popup): RESTORED to the
            //    original pre-batch-10 presentation (user request 2026-09-01:
            //    "Restore the old bottom screen popup in lyrics page in non
            //    apple music player styles"): a `MenuSurfaceSection` card with the
            //    `NewActionGrid` 3-column action tiles (28dp theme-tinted icons over
            //    labels) exactly as the app's other menus render. The Apple Music
            //    rows' hardcoded white/red colors were never meant for this light
            //    `surfaceContainerLow` card — they left the rows unreadable here.
            //
            // Vertical padding 8dp -> 4dp per "apply the dimensions and
            // scaling from this commit" (batch-13 reference, 2026-08-31) —
            // popup path only.
            if (transparentSurface) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = Color.Transparent,
                    modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(vertical = 0.dp)) {
                        menuItems.forEachIndexed { index, item ->
                            AppleMusicLyricsMenuRow(
                                item = item,
                            )
                            // Hairline divider BETWEEN items only — no divider before the first
                            // or after the last, matching Apple Music's grid-separated look.
                            // Color: white at 12% opacity per reference "Divider lines
                            //   should be approximately 10–18% white/gray opacity"
                            //   (kept from batch-14 visual style).
                            // Thickness: 1dp -> 0.5dp and horizontal padding 20dp ->
                            //   16dp per batch-13 dimensions (2026-08-31) — the
                            //   batch-14 redesign was reported as too big.
                            if (index < menuItems.size - 1) {
                                HorizontalDivider(
                                    color = Color.White.copy(alpha = 0.12f),
                                    thickness = 0.5.dp,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        }
                        // Restored (2026-09-04): "Show player controls" / "Auto-hide
                        // player controls" toggles inside the Apple Music anchored
                        // lyrics popup — the exact rows the Sept 3→4 upstream port
                        // deleted together with the five-second auto-hide. Rendered
                        // as AppleMusicLyricsMenuRow-styled rows with a Switch in the
                        // trailing slot instead of an icon.
                        if (showControlsToggles) {
                            HorizontalDivider(
                                color = Color.White.copy(alpha = 0.12f),
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            AppleMusicLyricsMenuToggleRow(
                                label = stringResource(R.string.show_lyrics_player_controls),
                                checked = showPlayerControls,
                                onCheckedChange = { v -> onShowPlayerControlsChange?.invoke(v) },
                            )
                            HorizontalDivider(
                                color = Color.White.copy(alpha = 0.12f),
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            AppleMusicLyricsMenuToggleRow(
                                label = stringResource(R.string.auto_hide_lyrics_player_controls),
                                checked = autoHidePlayerControls,
                                enabled = showPlayerControls,
                                onCheckedChange = { v ->
                                    onAutoHidePlayerControlsPreferenceChange(v)
                                    onAutoHidePlayerControlsChange(v)
                                },
                            )
                        }
                    }
                }
            } else {
                MenuSurfaceSection {
                    NewActionGrid(
                        actions =
                            menuItems.map { item ->
                                NewAction(
                                    icon = {
                                        Icon(
                                            painter = painterResource(item.iconRes),
                                            contentDescription = null,
                                            modifier = Modifier.size(28.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    text = item.label,
                                    onClick = item.onClick,
                                    enabled = item.enabled,
                                )
                            },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LyricsSearchResultDialog(
    state: LyricsSearchScreenState,
    expandedResultId: String?,
    onExpandedResultChange: (String) -> Unit,
    onRefetch: () -> Unit,
    onResultSelected: (LyricsSearchResultUiModel) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        KeepStatusBarHiddenInDialog() // status bar stays hidden while this dialog window is focused
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .imePadding()
                    .navigationBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            val listContentPadding =
                remember {
                    PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 20.dp)
                }
            val listArrangement = remember { Arrangement.spacedBy(10.dp) }

            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = 640.dp)
                        .heightIn(max = maxHeight),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = AlertDialogDefaults.TonalElevation,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LyricsSearchResultHeader(
                        state = state,
                        onRefetch = onRefetch,
                        onDismiss = onDismiss,
                    )
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                        contentPadding = listContentPadding,
                        verticalArrangement = listArrangement,
                    ) {
                        when (state) {
                            LyricsSearchScreenState.Loading -> {
                                item(contentType = "lyrics_search_loading") {
                                    LyricsSearchLoadingContent()
                                }
                            }

                            LyricsSearchScreenState.Empty -> {
                                item(contentType = "lyrics_search_empty") {
                                    LyricsSearchEmptyContent()
                                }
                            }

                            is LyricsSearchScreenState.Error -> {
                                item(contentType = "lyrics_search_error") {
                                    LyricsSearchErrorContent(messageResId = state.messageResId)
                                }
                            }

                            is LyricsSearchScreenState.Success -> {
                                itemsIndexed(
                                    items = state.results,
                                    key = { _, result -> result.id },
                                    contentType = { _, _ -> "lyrics_search_result" },
                                ) { _, result ->
                                    LyricsSearchResultItem(
                                        result = result,
                                        isExpanded = result.id == expandedResultId,
                                        onExpandedChange = { onExpandedResultChange(result.id) },
                                        onResultSelected = { onResultSelected(result) },
                                    )
                                }

                                if (state.isSearching) {
                                    item(contentType = "lyrics_search_footer_loading") {
                                        LyricsSearchFooterLoading()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsSearchResultHeader(
    state: LyricsSearchScreenState,
    onRefetch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val subtitle =
        when (state) {
            LyricsSearchScreenState.Loading -> {
                stringResource(R.string.lyrics_searching_providers)
            }

            LyricsSearchScreenState.Empty -> {
                stringResource(R.string.lyrics_not_found)
            }

            is LyricsSearchScreenState.Error -> {
                stringResource(state.messageResId)
            }

            is LyricsSearchScreenState.Success -> {
                stringResource(
                    R.string.lyrics_search_results_count,
                    state.results.size,
                )
            }
        }
    val isSearching =
        state == LyricsSearchScreenState.Loading ||
            state is LyricsSearchScreenState.Success && state.isSearching
    val isSearchComplete =
        when (state) {
            LyricsSearchScreenState.Loading -> false

            is LyricsSearchScreenState.Success -> !state.isSearching

            LyricsSearchScreenState.Empty,
            is LyricsSearchScreenState.Error,
            -> true
        }
    val rowArrangement = remember { Arrangement.spacedBy(16.dp) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, top = 18.dp, end = 10.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = rowArrangement,
        ) {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        painter = painterResource(R.drawable.manage_search),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.search_lyrics),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isSearching) {
                LoadingIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (isSearchComplete) {
                IconButton(
                    onClick = onRefetch,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.cached),
                        contentDescription = stringResource(R.string.refetch),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.close),
                    contentDescription = stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LyricsSearchResultItem(
    result: LyricsSearchResultUiModel,
    isExpanded: Boolean,
    onExpandedChange: () -> Unit,
    onResultSelected: () -> Unit,
) {
    val motionScheme = MaterialTheme.motionScheme
    val lyricsType =
        when {
            result.isWordSynced -> stringResource(R.string.lyrics_word_sync)
            result.isLineSynced -> stringResource(R.string.lyrics_synced_badge)
            else -> stringResource(R.string.lyrics_search_plain_badge)
        }
    val stats =
        stringResource(
            R.string.lyrics_search_result_stats,
            result.lineCount,
            result.characterCount,
        )
    val metadataArrangement = remember { Arrangement.spacedBy(8.dp) }
    val containerColor =
        if (isExpanded) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
    val contentColor =
        if (isExpanded) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    val outlineColor =
        if (isExpanded) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        }
    val itemArrangement = remember { Arrangement.spacedBy(14.dp) }

    Surface(
        onClick = onResultSelected,
        modifier =
            Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = motionScheme.defaultSpatialSpec()),
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, outlineColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = itemArrangement,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = metadataArrangement,
            ) {
                LyricsSearchTypeIcon(
                    result = result,
                    isExpanded = isExpanded,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.providerName,
                        style = MaterialTheme.typography.labelLarge,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = lyricsType,
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = onExpandedChange,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        painter =
                            painterResource(
                                if (isExpanded) R.drawable.expand_less else R.drawable.expand_more,
                            ),
                        contentDescription = stringResource(R.string.details),
                        tint = contentColor,
                    )
                }
            }
            LyricsSearchResultSupportingContent(
                preview = result.preview,
                isExpanded = isExpanded,
                contentColor = contentColor,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = metadataArrangement,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LyricsSearchMetadataPill(
                    icon = R.drawable.info,
                    text = lyricsType,
                    isExpanded = isExpanded,
                    modifier = Modifier.weight(1f),
                )
                LyricsSearchMetadataPill(
                    icon = R.drawable.text_fields,
                    text = stats,
                    isExpanded = isExpanded,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LyricsSearchTypeIcon(
    result: LyricsSearchResultUiModel,
    isExpanded: Boolean,
) {
    val icon =
        when {
            result.isWordSynced -> R.drawable.lyrics
            result.isLineSynced -> R.drawable.sync
            else -> R.drawable.format_align_left
        }
    val containerColor =
        if (isExpanded) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.tertiaryContainer
        }
    val contentColor =
        if (isExpanded) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onTertiaryContainer
        }

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun LyricsSearchResultSupportingContent(
    preview: String,
    isExpanded: Boolean,
    contentColor: Color,
) {
    Text(
        text = preview,
        modifier = Modifier.fillMaxWidth(),
        maxLines = if (isExpanded) 8 else 2,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium,
        color = contentColor,
    )
}

@Composable
private fun LyricsSearchMetadataPill(
    icon: Int,
    text: String,
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val containerColor =
        if (isExpanded) {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.52f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    val contentColor =
        if (isExpanded) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val pillArrangement = remember { Arrangement.spacedBy(6.dp) }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = pillArrangement,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LyricsSearchLoadingContent() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LoadingIndicator(modifier = Modifier.size(40.dp))
        Text(
            text = stringResource(R.string.lyrics_searching_providers),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LyricsSearchFooterLoading() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        LoadingIndicator(modifier = Modifier.size(24.dp))
        Text(
            text = stringResource(R.string.lyrics_search_still_searching),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LyricsSearchEmptyContent() {
    LyricsSearchMessageContent(
        icon = R.drawable.search_off,
        text = stringResource(R.string.lyrics_not_found),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    )
}

@Composable
private fun LyricsSearchErrorContent(messageResId: Int) {
    LyricsSearchMessageContent(
        icon = R.drawable.error,
        text = stringResource(messageResId),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    )
}

@Composable
private fun LyricsSearchMessageContent(
    icon: Int,
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = containerColor,
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatLyricsSyncOffset(offsetMs: Int): String = if (offsetMs > 0) "+$offsetMs ms" else "$offsetMs ms"

private suspend fun translateLyricsWithTranslator(
    lyrics: String,
    language: Language,
): String =
    withContext(Dispatchers.IO) {
        val document = AiLyricsDocumentParser.parse(lyrics)
        if (document.segments.isEmpty()) return@withContext lyrics

        val translator = Translator()
        val translatedSegments = mutableMapOf<Int, String>()
        document.segments.chunkedForTranslator().forEach { batch ->
            val separator = uniqueTranslationSeparator(batch)
            val joined = batch.joinToString(separator = separator) { segment -> segment.text }
            val translatedJoined = translator.translateBlocking(joined, language).translatedText
            val parts = translatedJoined.split(separator)

            if (parts.size == batch.size) {
                batch.forEachIndexed { index, segment ->
                    translatedSegments[segment.id] = parts[index]
                }
            } else {
                batch.forEach { segment ->
                    translatedSegments[segment.id] = translator.translateBlocking(segment.text, language).translatedText
                }
            }
        }

        document.rebuild(translatedSegments)
    }

private fun List<AiLyricsSegment>.chunkedForTranslator(): List<List<AiLyricsSegment>> {
    val chunks = ArrayList<List<AiLyricsSegment>>()
    val current = ArrayList<AiLyricsSegment>()
    var currentChars = 0

    forEach { segment ->
        val nextSize = currentChars + segment.text.length
        if (current.isNotEmpty() && (current.size >= MaxTranslatorItemsPerBatch || nextSize > MaxTranslatorCharsPerBatch)) {
            chunks.add(current.toList())
            current.clear()
            currentChars = 0
        }
        current.add(segment)
        currentChars += segment.text.length
    }

    if (current.isNotEmpty()) chunks.add(current.toList())
    return chunks
}

private fun uniqueTranslationSeparator(segments: List<AiLyricsSegment>): String {
    var separator = "<<<SEP-${UUID.randomUUID()}>>>"
    while (segments.any { segment -> segment.text.contains(separator) }) {
        separator = "<<<SEP-${UUID.randomUUID()}>>>"
    }
    return separator
}

private const val MaxTranslatorItemsPerBatch = 50
private const val MaxTranslatorCharsPerBatch = 4000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchLyricsInputDialog(
    titleField: TextFieldValue,
    onTitleFieldChange: (TextFieldValue) -> Unit,
    artistField: TextFieldValue,
    onArtistFieldChange: (TextFieldValue) -> Unit,
    onDismiss: () -> Unit,
    onSearchOnline: () -> Unit,
    onSearch: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val maximumDialogHeight = (configuration.screenHeightDp.dp - 48.dp).coerceAtLeast(280.dp)
    val scrollState = rememberScrollState()
    val contentArrangement = remember { Arrangement.spacedBy(24.dp) }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier =
            Modifier
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .navigationBarsPadding()
                .imePadding()
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maximumDialogHeight),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(24.dp),
                verticalArrangement = contentArrangement,
            ) {
                LyricsSearchInputHeader(onDismiss = onDismiss)

                LyricsSearchInputFields(
                    titleField = titleField,
                    onTitleFieldChange = onTitleFieldChange,
                    artistField = artistField,
                    onArtistFieldChange = onArtistFieldChange,
                    onSearch = onSearch,
                )

                LyricsSearchInputActions(
                    onSearchOnline = onSearchOnline,
                    onSearch = onSearch,
                )
            }
        }
    }
}

@Composable
private fun LyricsSearchInputHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.search_lyrics),
            style = MaterialTheme.typography.headlineSmall,
            color = AlertDialogDefaults.titleContentColor,
            modifier = Modifier.weight(1f),
        )

        IconButton(
            onClick = onDismiss,
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(
                painter = painterResource(R.drawable.close),
                contentDescription = stringResource(R.string.close),
            )
        }
    }
}

@Composable
private fun LyricsSearchInputFields(
    titleField: TextFieldValue,
    onTitleFieldChange: (TextFieldValue) -> Unit,
    artistField: TextFieldValue,
    onArtistFieldChange: (TextFieldValue) -> Unit,
    onSearch: () -> Unit,
) {
    val fieldArrangement = remember { Arrangement.spacedBy(16.dp) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = fieldArrangement,
    ) {
        LyricsSearchTextField(
            value = titleField,
            onValueChange = onTitleFieldChange,
            label = stringResource(R.string.song_title),
            iconResId = R.drawable.music_note,
            imeAction = ImeAction.Next,
            onSearch = onSearch,
        )
        LyricsSearchTextField(
            value = artistField,
            onValueChange = onArtistFieldChange,
            label = stringResource(R.string.song_artists),
            iconResId = R.drawable.artist,
            imeAction = ImeAction.Search,
            onSearch = onSearch,
        )
    }
}

@Composable
private fun LyricsSearchTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    iconResId: Int,
    imeAction: ImeAction,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardOptions = remember(imeAction) { KeyboardOptions(imeAction = imeAction) }
    val currentOnSearch by rememberUpdatedState(onSearch)
    val keyboardActions =
        remember(imeAction) {
            if (imeAction == ImeAction.Search) {
                KeyboardActions(onSearch = { currentOnSearch() })
            } else {
                KeyboardActions.Default
            }
        }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                painter = painterResource(iconResId),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon =
            if (value.text.isNotEmpty()) {
                {
                    IconButton(onClick = { onValueChange(TextFieldValue()) }) {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = stringResource(R.string.clear),
                        )
                    }
                }
            } else {
                null
            },
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
    )
}

@Composable
private fun LyricsSearchInputActions(
    onSearchOnline: () -> Unit,
    onSearch: () -> Unit,
) {
    val horizontalArrangement = remember { Arrangement.spacedBy(8.dp, Alignment.End) }
    val verticalArrangement = remember { Arrangement.spacedBy(8.dp) }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = verticalArrangement,
    ) {
        OutlinedButton(
            onClick = onSearchOnline,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(
                painter = painterResource(R.drawable.language),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.search_online))
        }

        Button(
            onClick = onSearch,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(
                painter = painterResource(R.drawable.search),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.search))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Apple Music–style lyrics overflow menu — local row helpers
// (user request 2026-08-30: "I want the same Lyrics ui overflow menu but
//  only in apple music style. Recreate the exact same design but keep the
//  present features from my app")
//
// The screenshot reference shows: each row is ~57dp tall, text is LEFT-aligned
// at ~17pt regular weight, a 22dp icon sits on the FAR RIGHT edge, hairline
// dividers separate rows. Destructive rows ("Delete from Library" in the
// screenshot) are tinted bright `error` red, with semibold text to draw the
// eye — we mirror that on `isDestructive = true` items.
//
// We do NOT extend the shared `NewMenuItem` component because its API takes a
// `leadingContent` (icon on the LEFT) — Apple Music's design puts the icon on
// the RIGHT. `AppleMusicLyricsMenuRow` instead wraps a Material3 `ListItem`
// directly so the icon can go in `trailingContent`.
// ─────────────────────────────────────────────────────────────────────────

/**
 * Immutable description of one row in the Apple Music–style lyrics overflow
 * menu. Kept as a value-only data class so the rows list can be hoisted out
 * of composition via `remember` without stability warnings.
 */
@Immutable
private data class AppleMusicLyricsMenuItem(
    val label: String,
    val iconRes: Int,
    val isDestructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * One row of the Apple Music–style lyrics overflow menu. Layout:
 *  - text on the LEFT (Material3 `ListItem.headlineContent`),
 *  - 22dp icon on the FAR RIGHT (`trailingContent`),
 *  - no leading icon, no chevron — matches the screenshot's `info-circle /
 *    trash / share / mountain / person / dashed-square` set on the right.
 *
 * Destructive rows get `MaterialTheme.colorScheme.error` text + icon, with
 * `FontWeight.SemiBold` on the label so the eye lands on them, mirroring
 * Apple Music's red "Delete from Library" row. Normal rows use
 * `onSurface` text + `onSurfaceVariant` icon at `FontWeight.Medium` to match
 * the screenshot's white-on-dark visual.
 *
 * The row is wrapped in a `Surface(onClick = ...)` so the click handler runs
 * through Material3's ripple + enabled-gating without re-implementing them.
 * The surface itself is transparent — the outer `MenuSurfaceSection` provides
 * the visible card.
 */
@Composable
private fun AppleMusicLyricsMenuRow(
    item: AppleMusicLyricsMenuItem,
    modifier: Modifier = Modifier,
) {
    // Per "redesign the lyrics popup — match the second reference image"
    // (2026-08-30): the reference uses pure white text + off-white icons on
    // a dark translucent vibrancy surface, with the destructive row in
    // bright system red. The previous implementation used Material3
    // `onSurface`/`onSurfaceVariant` (theme-tinted) which adapts to light/
    // dark theme — but the reference popup is ALWAYS dark charcoal glass,
    // so theme-tinted colors are wrong. Hardcoding white + red matches the
    // reference's "white/off-white" + "bright system-style red" spec.
    val headlineColor =
        if (item.isDestructive) {
            Color(0xFFFF453A) // iOS System Red (dark mode)
        } else {
            Color.White
        }
    val iconColor =
        if (item.isDestructive) {
            Color(0xFFFF453A)
        } else {
            Color.White
        }
    val headlineWeight = if (item.isDestructive) FontWeight.SemiBold else FontWeight.Medium

    // Row geometry reverted to batch-13 compact dimensions (2026-08-31)
    // per user report that the batch-14 redesign made the popup too big.
    // The batch-14 visual style (white text + iOS System Red destructive)
    // is preserved above — only the dimensions revert:
    //   - row min height 56dp -> 44dp (batch-13 value; still meets touch
    //     target guidance).
    //   - horizontal padding 20dp -> 16dp (batch-13 value).
    //   - vertical padding 8dp -> 4dp (batch-13 value; tighter row
    //     breathing room).
    //   - icon size 24dp -> 20dp (batch-13 value).
    //   - text fontSize 17sp -> 16sp (batch-13 value).
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(),
                    enabled = item.enabled,
                    onClick = item.onClick,
                )
                .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.label,
            color = headlineColor,
            fontWeight = headlineWeight,
            fontSize = 16.sp,
        )
        Icon(
            painter = painterResource(item.iconRes),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = iconColor,
        )
    }
}

/**
 * Restored (2026-09-04): a settings row for the Apple Music anchored lyrics
 * popup — same geometry/material as [AppleMusicLyricsMenuRow] (44dp min height,
 * 16dp horizontal padding, white 16sp Medium label) but with a [Switch] in the
 * trailing slot instead of an icon. Backs the restored "Show player controls" /
 * "Auto-hide player controls" toggles for the five-second auto-hide.
 */
@Composable
private fun AppleMusicLyricsMenuToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    enabled = enabled,
                ) {
                    if (enabled) onCheckedChange(!checked)
                }
                .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/**
 * Apple-Music-style anchored overflow popup for the lyrics screen.
 *
 * Renders the SAME menu content as [LyricsMenu] (Edit / Refetch / Translate /
 * Romanise with AI / Undo translation / Search) but as a frosted-glass popup
 * anchored to the top-right overflow ("...") icon — instead of the previous
 * bottom slide-up ModalBottomSheet.
 *
 * ## Visual design
 *
 * - Anchored to [iconBoundsInRoot.right] x [iconBoundsInRoot.bottom] so the
 *   popup's right edge aligns with the icon's right edge, with the popup
 *   appearing just below the icon.
 * - 220dp width (compact fixed width per batch-13 reference, restored 2026-08-31
 *   after batch-14's 65%-of-screen width was reported as too big), 16dp corner
 *   radius (was `MaterialTheme.shapes.extraLarge` ~28dp, reduced 2026-08-30
 *   per reference "24 px corner radius at the reference scale"), frosted-glass
 *   vibrancy background — when the
 *   caller provides a non-null [backdrop] (a kyant [PlatformBackdrop] that
 *   captures the player content behind the popup), the popup samples that
 *   backdrop with a 20dp blur, producing a real "frosted glass" effect that
 *   shows the blurred album art / lyrics behind it. When [backdrop] is null
 *   (e.g. on pre-Android-12 or when Liquid Glass is disabled), the popup
 *   falls back to a translucent dark tint — still visible against any
 *   background but without the real blur.
 * - NO outer border and NO outer dark tint around the inner [MenuSurfaceSection]
 *   card. The previous implementation layered a translucent dark background
 *   (`Color.Black.copy(alpha = 0.7f)`) and a faint white border AROUND the
 *   inner [MenuSurfaceSection], which has its own opaque `surfaceContainerLow`
 *   surface with a different corner radius (`MaterialTheme.shapes.extraLarge`
 *   ~ 28dp) than the popup's 16dp outer clip. The radius mismatch left a
 *   visible dark gap around the inner card that the user perceived as a
 *   "thick black border". The fix: drop the outer dark tint + border, and
 *   match the popup's clip radius to the inner card's surface shape
 *   (`extraLarge`) so the inner card fills the popup's entire surface. The
 *   popup's only visible surface is now the inner card itself — clean,
 *   single-card appearance matching Apple Music's anchored action sheet.
 *
 * ## Morph animation
 *
 * The popup enters by scaling up from a small initial scale centred on the
 * top-right corner of itself — the corner that meets the icon — so it
 * visually appears to "grow out of" the overflow icon the user tapped. This
 * matches the iOS Action Sheet behaviour the user explicitly referenced
 * ("enlarge smoothly from the overflow menu icon just like the morph
 * animation"). On dismissal the same animation reverses (scale back down
 * to the icon position), then the parent removes the composable from
 * composition via [onDismiss].
 *
 * ## Opening animation fix (batch-11)
 *
 * The previous implementation used `animateFloatAsState` keyed on the
 * `dismissed` flag. `animateFloatAsState` initialises its first frame to
 * the target value (1f for scale, 1f for alpha on enter) — so the OPENING
 * animation never played; the popup just appeared at full size/opacity
 * immediately. Only the CLOSING animation played (because the target
 * changed from 1f to 0f / 0.3f when `dismissed` flipped to true).
 *
 * The fix: use [Animatable] initialised to the ENTER values (0.3f scale,
 * 0f alpha), and a `LaunchedEffect(Unit)` that fires once on first
 * composition to call `animateTo(1f, ...)` — this is a real state change
 * from 0.3f -> 1f, so the opening animation plays. A separate
 * `LaunchedEffect(dismissed)` fires when `dismissed` flips to true and
 * animates back to the EXIT values (0.3f scale, 0f alpha), then calls
 * [onDismiss] after the exit animation completes.
 *
 * ## Taps
 *
 * Taps inside the popup are consumed (the popup's clickable has an empty
 * onClick) so they never bubble up to the scrim and dismiss the menu
 * accidentally. Taps anywhere outside the popup (on the scrim) trigger
 * dismissal via [DismissRequest] — same as the previous ModalBottomSheet
 * tap-outside-to-dismiss behaviour.
 *
 * @param iconBoundsInRoot On-screen rectangle of the overflow icon, in the
 *   coordinates of the lyrics screen's root composable (i.e. as reported
 *   by `Modifier.onGloballyPositioned { coords -> iconBounds =
 *   coords.boundsInRoot() }`). The popup's right edge aligns with
 *   [Rect.right] and the popup's top edge sits [Rect.bottom] + 4dp below
 *   the icon's bottom edge — UNLESS the icon sits so low on screen that
 *   the popup would not fit below it (an anchor in a bottom caption row,
 *   as the TikTok style's ⋯ button), in which case the popup opens ABOVE
 *   the icon instead: its bottom edge at [Rect.top] - 4dp, growing from
 *   its bottom-right corner — the corner that meets the icon — so the
 *   "grows out of the icon" morph reads identically either way.
 * @param backdrop Optional kyant [PlatformBackdrop] that captures the
 *   player content behind the popup. When non-null, the popup samples this
 *   backdrop with a 20dp blur to produce a real frosted-glass effect. The
 *   backdrop MUST be set up by the caller via [rememberBackdrop] + applied
 *   to a sibling Box via [Modifier.layerBackdrop] so the popup is NOT
 *   nested inside the layer-capturing Box (the kyant library warns that
 *   nesting a drawBackdrop sampler inside the layer-capturing Box creates a
 *   render-feedback loop that crashes the RuntimeShader).
 * @param onDismiss Called after the exit animation finishes — the parent
 *   should set `showLyricsMenu = false` (or equivalent) in response so the
 *   composable leaves composition.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AnchoredLyricsOverflowMenu(
    iconBoundsInRoot: Rect,
    lyricsProvider: () -> LyricsEntity?,
    mediaMetadataProvider: () -> MediaMetadata,
    lyricsSyncOffset: Int,
    onLyricsSyncOffsetChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    viewModel: LyricsMenuViewModel = hiltViewModel(),
    backdrop: PlatformBackdrop? = null,
    // Restored (2026-09-04): the player-control preference hooks, passed
    // straight through to [LyricsMenu] so the two restored toggles render
    // inside this popup. Only the Apple Music player supplies them.
    showPlayerControlsState: State<Boolean>? = null,
    onShowPlayerControlsChange: ((Boolean) -> Unit)? = null,
    onAutoHidePlayerControlsChange: (Boolean) -> Unit = {},
    showControlsToggles: Boolean = false,
) {
    // Local dismissal state — set to true when the user requests dismissal
    // (tap on scrim / a menu item that closes). Drives the exit animation
    // (alpha -> 0, scale -> 0.3). The animation's completion is observed by
    // a LaunchedEffect which then calls [onDismiss] so the parent removes
    // the composable from composition.
    var dismissed by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // NOTE: batch-14 captured `LocalConfiguration.current` here to compute
    // a 65%-of-screen popup width. batch-15 (2026-08-31) reverts to a fixed
    // 220dp width per user request "apply the dimensions and scaling from
    // this commit" (batch-13 reference). The capture is no longer needed.

    // Scale: starts at 0.3f (small, centred on top-right corner) on first
    // composition, animates to 1.0f via LaunchedEffect(Unit) below. On
    // dismissal, animates back to 0.3f. Spring for a slight overshoot that
    // matches Apple Music's "morph" feel.
    //
    // NOTE: Animatable (not animateFloatAsState) is critical here. The
    // previous implementation used animateFloatAsState which initialises its
    // first frame to the target value — so the OPENING animation never
    // played; the popup just appeared at full scale immediately. Animatable
    // lets us set an explicit initial value (0.3f) and then drive a real
    // state change (0.3f -> 1f) via LaunchedEffect, which IS animated.
    val scaleAnim = remember { Animatable(0.3f) }
    val alphaAnim = remember { Animatable(0f) }

    // Enter animation: fires ONCE on first composition. Animates 0.3f -> 1f
    // scale and 0f -> 1f alpha in parallel. The state changes are real
    // (from the initial Animatable values to the new targets) so the
    // animations actually play, unlike the previous animateFloatAsState
    // approach where the first frame was already at the target.
    //
    // Damping: `DampingRatioNoBouncy` (was `MediumBouncy`) — user report
    // 2026-08-30: "I don't want the bounce effect at the end of the opening
    // animation". The previous medium-bouncy spring produced a visible
    // overshoot at the end of the scale-in, which the user found jarring.
    // NoBouncy gives a clean ease-out deceleration with zero overshoot.
    LaunchedEffect(Unit) {
        // Don't play if already dismissing (defensive — shouldn't happen
        // on first composition but guards against edge cases).
        if (dismissed) return@LaunchedEffect
        // Animate scale and alpha in parallel via two launched coroutines.
        val scaleJob = scope.launch {
            scaleAnim.animateTo(
                targetValue = 1f,
                animationSpec =
                    spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
            )
        }
        val alphaJob = scope.launch {
            alphaAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(180),
            )
        }
        scaleJob.join()
        alphaJob.join()
    }

    // Exit animation: fires when `dismissed` flips to true. Animates
    // 1f -> 0.3f scale and 1f -> 0f alpha, then calls onDismiss after both
    // complete so the parent removes the composable from composition.
    LaunchedEffect(dismissed) {
        if (!dismissed) return@LaunchedEffect
        val scaleJob = scope.launch {
            scaleAnim.animateTo(
                targetValue = 0.3f,
                animationSpec =
                    spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
            )
        }
        val alphaJob = scope.launch {
            alphaAnim.animateTo(
                targetValue = 0f,
                animationSpec = tween(180),
            )
        }
        scaleJob.join()
        alphaJob.join()
        // Both exit animations have completed — tell the parent to remove
        // us from composition.
        onDismiss()
    }

    val scale = scaleAnim.value
    val alpha = alphaAnim.value

    // ── Above-anchor flip (2026-09-02) ──
    // The TikTok style anchors this popup to the horizontal-dots button in
    // the BOTTOM caption row (user request: "the lyrics animation should
    // play attached with the three horizontal dots"), where a below-the-
    // icon popup would run off the bottom of the screen. The scrim's own
    // measured height is the popup's available space, and the popup's
    // measured height is what it needs; when the two don't fit below the
    // anchor, the popup flips to open above it instead (bottom edge at the
    // icon's top, growth origin switched to the popup's bottom-right
    // corner). Both reads are draw-phase (offset placement + graphicsLayer),
    // so measuring settles the placement without recomposing the menu.
    var anchorSpaceHeightPx by remember { mutableIntStateOf(0) }
    var popupHeightPx by remember { mutableIntStateOf(0) }
    val verticalOffsetPx = with(density) { 4.dp.toPx() }.toInt()

    // Whether the popup opens above its anchor: true when the space below
    // the icon can't hold the popup. The popup's height is 0 until its
    // first layout completes — the first frames fall back to a generous
    // estimate, which only ever makes the flip decision MORE conservative
    // (and the popup is at alpha 0 then, so the settle is invisible).
    fun opensAboveAnchor(): Boolean {
        val neededHeightPx =
            if (popupHeightPx > 0) popupHeightPx else with(density) { 360.dp.toPx() }.toInt()
        return anchorSpaceHeightPx > 0 &&
            iconBoundsInRoot.bottom + verticalOffsetPx + neededHeightPx > anchorSpaceHeightPx
    }

    // Memoize the drawBackdrop modifier chain so it isn't rebuilt on every
    // recomposition. The chain depends only on `backdrop` — stable across
    // scroll-driven recompositions of the host screen. Without this,
    // every recomposition rebuilt the kyant effect stack and re-installed
    // the RuntimeShader on the GraphicsLayer.
    val frostedBlurModifier = remember(backdrop) {
        if (backdrop != null) {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                effects = {
                    vibrancy()
                    // Blur radius 20f -> 32f per reference "strong backdrop
                    // blur". The reference popup is a dark charcoal translucent
                    // glass with heavy blur of the content behind — 20dp was
                    // too subtle; 32dp produces a more premium vibrancy look.
                    blur(32f.dp.toPx())
                },
                onDrawBackdrop = { drawBackdrop ->
                    drawBackdrop()
                },
                shape = { RoundedCornerShape(16.dp) },
            )
        } else {
            null
        }
    }

    // Full-screen scrim — translucent black so the lyrics view is still
    // visible behind (matches Apple Music's "dim the background but don't
    // black it out" style). Clickable to trigger dismissal.
    //
    // Scrim alpha 0.35f -> 0.45f per reference "darkened/dimmed background
    // ... existing lyrics remain faintly visible". The reference shows a
    // stronger dim than the previous 35% — 45% matches the reference's
    // ~40-50% dim. The `* alpha` multiplier is preserved so the scrim
    // continues to fade in/out with the existing enter/exit animation —
    // THIS IS A STATIC COLOR CHANGE, NOT AN ANIMATION CHANGE.
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onSizeChanged { anchorSpaceHeightPx = it.height }
                .background(Color.Black.copy(alpha = 0.45f * alpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (!dismissed) dismissed = true
                },
    ) {
        // The popup itself. Positioned via Modifier.offset { } so its
        // right edge aligns with the icon's right edge (in root coords),
        // and its top edge sits 4dp below the icon's bottom edge.
        //
        // transformOrigin = (1f, 0f) = top-right of the popup — so the
        // scale animation grows from the top-right corner, the corner
        // where the popup meets the icon the user just tapped. This is
        // the "morph from the overflow icon" effect the user asked for.
        //
        // Per "apply the dimensions and scaling from this commit"
        // (2026-08-31, batch-13 reference), the popup width reverts from
        // batch-14's `fillMaxWidth(0.65f)` (65% screen) back to batch-13's
        // compact `widthIn(max = 220.dp)`. The user reported the batch-14
        // redesign made the popup too big; we keep batch-14's visual style
        // (dark-glass material, white text, blur, shadow, red destructive)
        // but restore batch-13's compact dimensions.
        //
        // The popup remains anchored to the overflow icon's right edge so
        // the existing enter/exit scale animation (transformOrigin =
        // top-right) continues to look like the popup "grows out of" the
        // icon — THE ANIMATION IS COMPLETELY UNCHANGED.
        //
        // Visual stack (outermost -> innermost):
        //   1. offset — positions the popup's top-right corner at the icon.
        //   2. widthIn(max = 220.dp) — compact fixed width (batch-13 value).
        //   3. heightIn(max = 520.dp) — safety bound for tall content.
        //   4. graphicsLayer — UNCHANGED animation reads (alpha + scale +
        //      transformOrigin). NEW (batch-16, 2026-08-31): merged the
        //      `shadowElevation` + `shape` + `clip = false` INTO this same
        //      graphicsLayer block — previously this was a SEPARATE
        //      `Modifier.shadow(16.dp, RoundedCornerShape(16.dp), clip = false)`
        //      call which created its own internal graphicsLayer, resulting in
        //      TWO graphicsLayer render passes per frame during the scale
        //      animation (popup lag). Merging into one layer halves the layer
        //      overhead while producing identical visuals: the shadow is still
        //      drawn with 16dp elevation, RoundedCornerShape(16.dp) outline,
        //      outside the bounds (clip=false), and is still transformed by
        //      the same alpha/scale/transformOrigin — so it scales + fades
        //      with the popup's enter/exit animation exactly as before.
        //      User request: "Fix it without removing or sacrificing anything".
        //   5. frostedBlurModifier (or fallback dark tint) — backdrop blur.
        //   6. background(Color.Black at 55%) — dark charcoal tint over the
        //      blur, per reference "dark charcoal/black translucent
        //      material". The graphicsLayer's alpha animates this tint in/out.
        //   7. clip(RoundedCornerShape(16.dp)) — was `extraLarge` (~28dp);
        //      reduced to 16dp per reference "24 px corner radius at the
        //      reference scale" (24px ≈ 16dp at mdpi).
        //   8. clickable — consumes taps inside the popup.
        Box(
            modifier =
                Modifier
                    .offset {
                        val popupWidthPx = with(density) { 220.dp.toPx() }.toInt()
                        val horizontalMarginPx = with(density) { 16.dp.toPx() }.toInt()
                        val iconRight = iconBoundsInRoot.right.toInt()
                        val iconBottom = iconBoundsInRoot.bottom.toInt()
                        val x =
                            (iconRight - popupWidthPx)
                                .coerceAtLeast(horizontalMarginPx)
                        val y =
                            if (opensAboveAnchor()) {
                                // Open ABOVE the anchor: the popup's bottom
                                // edge sits 4dp above the icon's top edge, so
                                // the corner the scale animation grows from
                                // (bottom-right, set in the graphicsLayer
                                // below) lands right on the icon.
                                (iconBoundsInRoot.top - verticalOffsetPx -
                                    (if (popupHeightPx > 0) popupHeightPx else with(density) { 360.dp.toPx() }.toInt()))
                                    .coerceAtLeast(0f)
                                    .toInt()
                            } else {
                                iconBottom + verticalOffsetPx
                            }
                        IntOffset(x = x, y = y)
                    }
                    .widthIn(max = 220.dp)
                    .heightIn(max = 520.dp)
                    .onSizeChanged { popupHeightPx = it.height }
                    .graphicsLayer {
                        this.alpha = alpha
                        this.scaleX = scale
                        this.scaleY = scale
                        // The growth corner is the corner that meets the icon:
                        // top-right when the popup opens below the anchor,
                        // bottom-right when it flips above (TikTok's caption-
                        // row anchor) — either way the popup reads as growing
                        // straight out of the icon the user tapped.
                        this.transformOrigin =
                            TransformOrigin(1f, if (opensAboveAnchor()) 1f else 0f)
                        // Merged from the previous `.shadow(16.dp, RoundedCornerShape(16.dp), clip = false)`
                        // modifier (batch-16, 2026-08-31). Setting these inside the existing
                        // graphicsLayer avoids creating a SECOND internal graphicsLayer — the
                        // separate Modifier.shadow internally wraps content in another
                        // graphicsLayer to render the elevation shadow, so stacking them caused
                        // 2 layer passes per frame during the scale animation (laggy popup).
                        // Visuals are identical: 16dp elevation shadow, RoundedCornerShape(16.dp)
                        // outline, shadow drawn outside bounds (clip = false). The shadow still
                        // scales + fades with the popup's alpha/scale/transformOrigin because
                        // these properties live on the same layer.
                        this.shadowElevation = with(density) { 16.dp.toPx() }
                        this.shape = RoundedCornerShape(16.dp)
                        this.clip = false
                    }
                    // Apply the frosted-blur backdrop sampler FIRST
                    // (before clip + background), so the blur samples the
                    // full backdrop at the popup's location, then the clip
                    // + background draw on top. When `backdrop` is null
                    // (pre-S / Liquid Glass off), fall back to a plain
                    // translucent dark tint — still visible but without
                    // the real blur.
                    .then(
                        frostedBlurModifier
                            ?: Modifier.background(Color.Black.copy(alpha = 0.65f * alpha)),
                    )
                    // Dark charcoal tint over the blur. The reference popup
                    // is NOT a clear glass window — it is a dark translucent
                    // vibrancy surface. Without this tint, the blurred
                    // content shows through too brightly (e.g., album art
                    // colors would dominate). 55% black over the blur
                    // produces the reference's "dark charcoal" feel while
                    // still letting the blur's vibrancy show through.
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        // Consume taps inside the popup so they don't bubble
                        // up to the scrim and trigger dismissal.
                    },
        ) {
            // Delegate the actual menu items to the existing [LyricsMenu]
            // composable — same Edit / Refetch / Translate / Romanise /
            // Undo / Search list, same click handlers, same dialogs. With
            // transparentSurface = true, LyricsMenu renders its compact
            // Apple-Music dark-glass row list inside a transparent Surface,
            // so the frosted-glass blur applied to this outer Box stays
            // visible (user report: "liquid glass effect is behind the white
            // popup but the white popup is loading on top of it"). The
            // non-Apple-Music styles (transparentSurface = false) render
            // the restored NewActionGrid bottom-sheet card instead.
            LyricsMenu(
                lyricsProvider = lyricsProvider,
                mediaMetadataProvider = mediaMetadataProvider,
                lyricsSyncOffset = lyricsSyncOffset,
                onLyricsSyncOffsetChange = onLyricsSyncOffsetChange,
                onDismiss = {
                    // Defer the actual removal to the exit-animation completion.
                    if (!dismissed) dismissed = true
                },
                viewModel = viewModel,
                transparentSurface = true,
                showPlayerControlsState = showPlayerControlsState,
                onShowPlayerControlsChange = onShowPlayerControlsChange,
                onAutoHidePlayerControlsChange = onAutoHidePlayerControlsChange,
                showControlsToggles = showControlsToggles,
            )
        }
    }
}
