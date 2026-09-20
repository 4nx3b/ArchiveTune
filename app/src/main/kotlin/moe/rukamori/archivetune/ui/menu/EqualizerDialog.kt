/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package moe.rukamori.archivetune.ui.menu

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.rukamori.archivetune.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import moe.rukamori.archivetune.equalizer.EqualizerControlMode
import moe.rukamori.archivetune.equalizer.EqualizerTone
import moe.rukamori.archivetune.viewmodels.EqualizerBandUiModel
import moe.rukamori.archivetune.viewmodels.EqualizerToneUiModel
import kotlin.math.roundToInt
import moe.rukamori.archivetune.constants.AudioPlaybackPitchKey
import moe.rukamori.archivetune.constants.AudioPlaybackSpeedKey
import moe.rukamori.archivetune.constants.AudioPlaybackSpeedPitchMatchKey
import moe.rukamori.archivetune.constants.EqualizerAudioEffectsEnabledKey
import moe.rukamori.archivetune.playback.EqReverbPreset
import moe.rukamori.archivetune.ui.component.KeepStatusBarHiddenInDialog
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.EqualizerEffect
import moe.rukamori.archivetune.viewmodels.EqualizerProfileUiModel
import moe.rukamori.archivetune.viewmodels.EqualizerScreenState
import moe.rukamori.archivetune.viewmodels.EqualizerUiModel
import moe.rukamori.archivetune.viewmodels.EqualizerViewModel
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun EqualizerDialog(
    onDismiss: () -> Unit,
    openSystemEqualizer: () -> Unit,
    viewModel: EqualizerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::importProfiles)
        }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let(viewModel::exportProfile)
        }

    LaunchedEffect(viewModel, context, importLauncher, exportLauncher) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is EqualizerEffect.ShowMessage -> {
                    val message =
                        effect.quantity?.let { context.getString(effect.messageResId, it) }
                            ?: context.getString(effect.messageResId)
                    snackbarHostState.showSnackbar(message)
                }

                EqualizerEffect.OpenImportDocument -> {
                    importLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
                }

                is EqualizerEffect.CreateExportDocument -> {
                    exportLauncher.launch(effect.suggestedName)
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,

        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        KeepStatusBarHiddenInDialog()
        EqualizerScreen(
            state = state,
            snackbarHostState = snackbarHostState,
            onDismiss = onDismiss,
            onOpenSystemEqualizer = openSystemEqualizer,
            viewModel = viewModel,
        )
    }
}

@Composable
private fun EqualizerScreen(
    state: EqualizerScreenState,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
    onOpenSystemEqualizer: () -> Unit,
    viewModel: EqualizerViewModel,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
    ) {
        when (state) {
            EqualizerScreenState.Loading -> EqualizerLoading()

            EqualizerScreenState.Empty -> EqualizerUnavailable(onOpenSystemEqualizer)

            is EqualizerScreenState.Error -> EqualizerError(state.messageResId, onOpenSystemEqualizer)

            is EqualizerScreenState.Success -> {
                AudioEffectsContent(
                    model = state.model,
                    onDismiss = onDismiss,
                    onOpenSystemEqualizer = onOpenSystemEqualizer,
                    viewModel = viewModel,
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    val model = (state as? EqualizerScreenState.Success)?.model
    if (model?.saveProfileDialog?.visible == true) {
        SaveProfileDialog(
            name = model.saveProfileDialog.name,
            onNameChange = viewModel::updateProfileName,
            onSave = viewModel::saveProfile,
            onDismiss = viewModel::dismissSaveProfileDialog,
        )
    }
    if (model?.manageProfilesVisible == true) {
        ManageProfilesDialog(
            profiles = model.profiles,
            onApply = viewModel::applyProfile,
            onDelete = viewModel::deleteProfile,
            onExport = viewModel::requestExport,
            onDismiss = viewModel::dismissManageProfiles,
        )
    }
}

@Composable
private fun AudioEffectsContent(
    model: EqualizerUiModel,
    onDismiss: () -> Unit,
    onOpenSystemEqualizer: () -> Unit,
    viewModel: EqualizerViewModel,
) {
    val scrollState = rememberScrollState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    val (playbackSpeed, onPlaybackSpeedChange) = rememberPreference(AudioPlaybackSpeedKey, defaultValue = 1.0f)
    val (isPitchMatched, onPitchMatchedChange) = rememberPreference(AudioPlaybackSpeedPitchMatchKey, defaultValue = false)
    val (playbackPitch, onPlaybackPitchChange) = rememberPreference(AudioPlaybackPitchKey, defaultValue = 1.0f)
    var isSpeedSwitchOn by remember { mutableStateOf(playbackSpeed != 1.0f) }

    var isBalanceSwitchOn by remember { mutableStateOf(model.balance != 0f) }

    val (audioEffectsEnabled, onAudioEffectsEnabledChange) =
        rememberPreference(EqualizerAudioEffectsEnabledKey, defaultValue = false)

    var selectedTab by rememberSaveable { mutableStateOf(0) }

    var showProcessingCard by remember { mutableStateOf(false) }
    var observed8DEnabled by remember { mutableStateOf(model.eightDEnabled) }
    LaunchedEffect(model.eightDEnabled) {
        val changed = model.eightDEnabled != observed8DEnabled
        observed8DEnabled = model.eightDEnabled
        if (changed && model.eightDEnabled) {
            showProcessingCard = true
            delay(1200.milliseconds)
            showProcessingCard = false
        } else {
            showProcessingCard = false
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()

                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 120.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text =
                    stringResource(
                        if (selectedTab == 0) {
                            R.string.eq_tab_equalizer
                        } else {
                            R.string.eq_audio_effects
                        },
                    ),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(top = 8.dp),
            )
            IconButton(onClick = viewModel::showSaveProfileDialog) {
                Icon(
                    painter = painterResource(R.drawable.add),
                    contentDescription = stringResource(R.string.eq_save_profile),
                )
            }
            IconButton(onClick = viewModel::showManageProfiles, enabled = model.profiles.size > 0) {
                Icon(
                    painter = painterResource(R.drawable.tune),
                    contentDescription = stringResource(R.string.eq_manage),
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    painter = painterResource(R.drawable.close),
                    contentDescription = stringResource(R.string.eq_close),
                )
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CategoryPill(
                label = stringResource(R.string.eq_tab_equalizer),
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                modifier = Modifier.weight(1f),
            )
            CategoryPill(
                label = stringResource(R.string.eq_tab_audio_effects),
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                modifier = Modifier.weight(1f),
            )
        }

        val columns = if (isLandscape) 2 else 1

        if (selectedTab == 0) {

            SegmentedFeatureCard(
                items =
                    listOf(
                        {
                            EqualizerSection(
                                enabled = model.enabled,
                                onToggle = viewModel::setEnabled,
                                bands = model.fixedBandsMb.map { it / 100f },
                                onBandChange = { index, db ->
                                    viewModel.updateFixedBandDraft(index, (db * 100).toInt())
                                    viewModel.commitFixedBands()
                                },
                                presets = model.presets,
                                onPresetClick = viewModel::applyPreset,
                            )
                        },
                    ),
            )

            SectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.eq_control_mode),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        EqualizerControlMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = model.controlMode == mode,
                                onClick = { viewModel.setControlMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, EqualizerControlMode.entries.size),
                                icon = {},
                            ) {
                                Text(
                                    text =
                                        stringResource(
                                            if (mode == EqualizerControlMode.BASIC) R.string.eq_basic else R.string.eq_advanced,
                                        ),
                                )
                            }
                        }
                    }
                    Text(
                        text =
                            stringResource(
                                if (model.controlMode == EqualizerControlMode.BASIC) {
                                    R.string.eq_basic_description
                                } else {
                                    R.string.eq_advanced_description
                                },
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (model.controlMode == EqualizerControlMode.BASIC) {
                SegmentedFeatureCard(
                    items =
                        model.tones.map { tone ->
                            {
                                ToneSliderSection(
                                    tone = tone,
                                    enabled = model.enabled,
                                    minimumValueMb = model.minimumBandLevelMb,
                                    maximumValueMb = model.maximumBandLevelMb,
                                    onValueChange = { valueMb ->
                                        viewModel.updateToneDraft(tone.tone, valueMb)
                                    },
                                    onValueChangeFinished = { viewModel.commitTone(tone.tone) },
                                )
                            }
                        },
                )
            } else {
                SectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.eq_bands),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = viewModel::resetBands,
                                enabled = model.enabled,
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(text = stringResource(R.string.reset))
                            }
                        }
                        Text(
                            text = stringResource(R.string.eq_bands_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        repeat(model.bands.size) { index ->
                            BandSliderSection(
                                band = model.bands[index],
                                enabled = model.enabled,
                                minimumValueMb = model.minimumBandLevelMb,
                                maximumValueMb = model.maximumBandLevelMb,
                                onValueChange = { valueMb ->
                                    viewModel.updateBandDraft(index, valueMb)
                                },
                                onValueChangeFinished = viewModel::commitBands,
                            )
                            if (index != model.bands.size - 1) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }
                    }
                }

                LabelSliderSection(
                    title = stringResource(R.string.eq_output_gain),
                    label = stringResource(R.string.eq_signal),
                    value = model.outputGainMb / 100f,
                    range = -15f..15f,
                    checked = model.outputGainEnabled,
                    onToggle = viewModel::setOutputGainEnabled,
                    onValueChange = { db ->
                        viewModel.updateOutputGainDraft((db * 100).toInt().coerceIn(-1500, 1500))
                        viewModel.commitOutputGain()
                    },
                    prefix = if (model.outputGainMb >= 0) "+" else "",
                    suffix = stringResource(R.string.eq_unit_db),
                    interactionEnabled = model.enabled && !model.autoHeadroomEnabled,
                )

                SwitchSection(
                    title = stringResource(R.string.eq_auto_headroom),
                    desc = stringResource(R.string.eq_auto_headroom_description),
                    checked = model.autoHeadroomEnabled,
                    onToggle = viewModel::setAutoHeadroomEnabled,
                    interactionEnabled = model.enabled,
                )

                SectionContainer {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = viewModel::showSaveProfileDialog, enabled = model.enabled, shapes = ButtonDefaults.shapes()) {
                            Icon(painterResource(R.drawable.add), contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(text = stringResource(R.string.eq_save_profile))
                        }
                        OutlinedButton(onClick = viewModel::showManageProfiles, enabled = model.profiles.size > 0, shapes = ButtonDefaults.shapes()) {
                            Text(text = stringResource(R.string.eq_manage))
                        }
                        OutlinedButton(onClick = viewModel::requestImport, shapes = ButtonDefaults.shapes()) {
                            Text(text = stringResource(R.string.eq_import))
                        }
                    }
                }
            }

            SectionContainer {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.eq_open_system_equalizer)) },
                    leadingContent = { Icon(painterResource(R.drawable.tune), contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onOpenSystemEqualizer),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        } else {
            AnimatedVisibility(
                visible = showProcessingCard,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                ProcessingCard(progress = 100)
            }

            SwitchSection(
                title = stringResource(R.string.eq_enable_audio_effects),
                desc = stringResource(R.string.eq_enable_audio_effects_desc),
                checked = audioEffectsEnabled,
                onToggle = onAudioEffectsEnabledChange,
            )

            Spacer(modifier = Modifier.height(16.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = columns,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    SegmentedFeatureCard(
                        items =
                            listOf(
                                {
                                    SwitchSection(
                                        title = stringResource(R.string.eq_8d),
                                        desc = stringResource(R.string.eq_8d_description),
                                        checked = model.eightDEnabled,
                                        onToggle = viewModel::set8DEnabled,
                                        infoTooltip = stringResource(R.string.eq_8d_info),
                                        interactionEnabled = audioEffectsEnabled,
                                    )
                                },
                                {
                                    ReverbSection(
                                        enabled = model.reverbEnabled,
                                        onToggle = viewModel::setReverbEnabled,
                                        presetValue = model.reverbPreset.storageValue.toFloat(),
                                        onPresetChange = { index ->
                                            viewModel.setReverbPreset(EqReverbPreset.fromStorage(index.toInt()))
                                        },
                                        interactionEnabled = audioEffectsEnabled,
                                    )
                                },
                                {
                                    LabelSliderSection(
                                        title = stringResource(R.string.eq_bass_boost),
                                        label = stringResource(R.string.eq_bass_level),
                                        value = model.bassBoostStrength / BASS_STRENGTH_PER_DB,
                                        range = 0f..BASS_MAX_DB,
                                        checked = model.bassBoostEnabled,
                                        onToggle = viewModel::setBassBoostEnabled,
                                        onValueChange = { db ->
                                            val strength = (db * BASS_STRENGTH_PER_DB).toInt().coerceIn(0, 1000)
                                            viewModel.updateBassBoostDraft(strength)
                                            viewModel.commitBassBoost()
                                        },
                                        suffix = stringResource(R.string.eq_unit_db),
                                        interactionEnabled = audioEffectsEnabled,
                                    )
                                },
                            ),
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    SegmentedFeatureCard(
                        items =
                            listOf(
                                {
                                    LabelSliderSection(
                                        title = stringResource(R.string.eq_loudness),
                                        label = stringResource(R.string.eq_gain),
                                        value = (model.outputGainMb.coerceIn(0, 1200)) / 100f,
                                        range = 0f..LOUDNESS_MAX_DB,
                                        checked = model.outputGainEnabled,
                                        onToggle = viewModel::setOutputGainEnabled,
                                        onValueChange = { db ->
                                            val mb = (db * 100).toInt().coerceIn(0, 1200)
                                            viewModel.updateOutputGainDraft(mb)
                                            viewModel.commitOutputGain()
                                        },
                                        prefix = "+",
                                        suffix = stringResource(R.string.eq_unit_db),
                                        interactionEnabled = audioEffectsEnabled,
                                    )
                                },
                                {
                                    BalanceSection(
                                        enabled = isBalanceSwitchOn,
                                        onToggle = { on ->
                                            isBalanceSwitchOn = on
                                            if (!on) {
                                                viewModel.updateBalanceDraft(0f)
                                                viewModel.commitBalance()
                                            }
                                        },
                                        value = model.balance * BALANCE_RANGE,
                                        onChange = { position ->
                                            viewModel.updateBalanceDraft(position / BALANCE_RANGE)
                                            viewModel.commitBalance()
                                        },
                                        interactionEnabled = audioEffectsEnabled,
                                    )
                                },
                                {
                                    SpeedSection(
                                        enabled = isSpeedSwitchOn,
                                        onToggle = { on ->
                                            isSpeedSwitchOn = on
                                            onPlaybackSpeedChange(if (on) playbackSpeed.coerceIn(0.5f, 2.0f) else 1.0f)
                                        },
                                        value = playbackSpeed,
                                        onChange = onPlaybackSpeedChange,
                                        isPitchMatched = isPitchMatched,
                                        onPitchMatchToggle = {
                                            val next = !isPitchMatched
                                            onPitchMatchedChange(next)

                                            if (next) onPlaybackPitchChange(1.0f)
                                        },
                                        pitchValue = playbackPitch,
                                        onPitchChange = onPlaybackPitchChange,
                                        interactionEnabled = audioEffectsEnabled,
                                    )
                                },
                                {
                                    LabelSliderSection(
                                        title = stringResource(R.string.eq_virtualizer),
                                        label = stringResource(R.string.eq_strength),
                                        value = model.virtualizerStrength / 10f,
                                        range = 0f..100f,
                                        checked = model.virtualizerEnabled,
                                        onToggle = viewModel::setVirtualizerEnabled,
                                        onValueChange = { percent ->
                                            val strength = (percent * 10).toInt().coerceIn(0, 1000)
                                            viewModel.updateVirtualizerDraft(strength)
                                            viewModel.commitVirtualizer()
                                        },
                                        suffix = stringResource(R.string.eq_unit_percent),
                                        interactionEnabled = audioEffectsEnabled,
                                    )
                                },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(percent = 50),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        border =
            androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    },
            ),
        modifier = modifier.height(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProcessingCard(progress: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "processing")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "pulse",
    )

    val animatedProgress by animateFloatAsState(
        targetValue = progress / 100f,
        animationSpec = WavyProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "smooth_progress",
    )

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.eq_processing_8d, progress),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.alpha(pulseAlpha),
            )
            Spacer(modifier = Modifier.height(16.dp))

            val density = LocalDensity.current
            val thickStroke =
                remember(density) {
                    Stroke(
                        width = with(density) { 6.dp.toPx() },
                        cap = StrokeCap.Round,
                    )
                }

            LinearWavyProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth().height(12.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                stroke = thickStroke,
                trackStroke = thickStroke,
                wavelength = WavyProgressIndicatorDefaults.LinearDeterminateWavelength,
                amplitude = { p -> WavyProgressIndicatorDefaults.indicatorAmplitude(p) * 2.5f },
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SegmentedFeatureCard(
    items: List<@Composable () -> Unit>,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        items.forEachIndexed { index, item ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = getEffectsSegmentedShape(index = index, count = items.size),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                item()
            }
        }
    }
}

private fun getEffectsSegmentedShape(index: Int, count: Int): androidx.compose.ui.graphics.Shape {
    val outer = 28.dp
    val inner = 4.dp
    return when {
        count <= 1 -> RoundedCornerShape(outer)
        index == 0 -> RoundedCornerShape(topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner)
        index == count - 1 -> RoundedCornerShape(topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer)
        else -> RoundedCornerShape(inner)
    }
}

private const val BASS_STRENGTH_PER_DB = 1000f / 15f
private const val BASS_MAX_DB = 15f
private const val LOUDNESS_MAX_DB = 12f
private const val BALANCE_RANGE = 50f

@Composable
private fun ExpressiveSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        thumbContent =
            if (checked) {
                {
                    Icon(
                        painter = painterResource(R.drawable.check),
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                        tint = Color.White,
                    )
                }
            } else {
                null
            },
    )
}

@Composable
private fun ToneSliderSection(
    tone: EqualizerToneUiModel,
    enabled: Boolean,
    minimumValueMb: Int,
    maximumValueMb: Int,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    SectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text =
                        stringResource(
                            when (tone.tone) {
                                EqualizerTone.BASS -> R.string.eq_bass
                                EqualizerTone.MIDRANGE -> R.string.eq_midrange
                                EqualizerTone.TREBLE -> R.string.eq_treble
                            },
                        ),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                ValuePill(formatDecibels(tone.levelMb))
            }
            ResponsiveSlider(
                value = tone.levelMb.toFloat(),
                onValueChange = { onValueChange(it.roundToInt()) },
                onValueChangeFinished = onValueChangeFinished,
                valueRange = minimumValueMb.toFloat()..maximumValueMb.toFloat(),
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun BandSliderSection(
    band: EqualizerBandUiModel,
    enabled: Boolean,
    minimumValueMb: Int,
    maximumValueMb: Int,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatFrequency(band.centerFrequencyHz),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            ValuePill(formatDecibels(band.levelMb))
        }
        Slider(
            value = band.levelMb.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            onValueChangeFinished = onValueChangeFinished,
            enabled = enabled,
            valueRange = minimumValueMb.toFloat()..maximumValueMb.toFloat(),
        )
    }
}

@Composable
private fun formatDecibels(valueMb: Int): String = stringResource(R.string.eq_decibels, valueMb / 100f)

@Composable
private fun formatFrequency(frequencyHz: Int): String =
    if (frequencyHz >= 1000) {
        stringResource(R.string.eq_frequency_kilohertz, frequencyHz / 1000f)
    } else {
        stringResource(R.string.eq_frequency_hertz, frequencyHz)
    }

@Composable
private fun ValuePill(value: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun SwitchSection(
    title: String,
    desc: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    infoTooltip: String? = null,
    interactionEnabled: Boolean = true,
) {
    var showDialog by remember { mutableStateOf(false) }

    SectionContainer {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (interactionEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (infoTooltip != null) {
                    IconButton(
                        onClick = { showDialog = true },
                        modifier = Modifier.padding(start = 8.dp).size(28.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.info),
                            contentDescription = stringResource(R.string.eq_information),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            ExpressiveSwitch(checked = checked, onCheckedChange = onToggle, enabled = interactionEnabled)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (showDialog && infoTooltip != null) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.eq_information)) },
            text = { Text(infoTooltip) },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.got_it))
                }
            },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.info),
                    contentDescription = null,
                )
            },
        )
    }
}

@Composable
private fun LabelSliderSection(
    title: String,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    onValueChange: (Float) -> Unit,
    prefix: String = "",
    suffix: String = " dB",
    interactionEnabled: Boolean = true,
) {
    SectionContainer {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = if (interactionEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            ExpressiveSwitch(checked = checked, onCheckedChange = onToggle, enabled = interactionEnabled)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                text =
                    (if (value > 0 && prefix == "+") "+" else "") +
                        value.toInt() + suffix,
                style = MaterialTheme.typography.labelLarge,
                color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        ResponsiveSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            enabled = checked && interactionEnabled,
        )
    }
}

@Composable
private fun EqualizerSection(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    bands: List<Float>,
    onBandChange: (Int, Float) -> Unit,
    presets: moe.rukamori.archivetune.viewmodels.EqualizerPresetUiModels,
    onPresetClick: (String) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val eqHeight = if (isLandscape) 180.dp else 240.dp
    val sliderWidth = if (isLandscape) 160.dp else 200.dp

    SectionContainer {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = stringResource(R.string.eq_5band_equalizer), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            ExpressiveSwitch(checked = enabled, onCheckedChange = onToggle)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            listOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz").forEach {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(modifier = Modifier.height(if (isLandscape) 16.dp else 32.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(eqHeight),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bands.forEachIndexed { index, value ->
                Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    ResponsiveSlider(
                        value = value,
                        onValueChange = { onBandChange(index, it) },
                        valueRange = -12f..12f,
                        enabled = enabled,
                        modifier =
                            Modifier
                                .graphicsLayer { rotationZ = 270f; transformOrigin = TransformOrigin.Center }
                                .requiredWidth(sliderWidth),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(if (isLandscape) 16.dp else 24.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            bands.forEach {
                Text(
                    text = stringResource(R.string.eq_band_db, it.toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(presets.size) { index ->
                val preset = presets[index]
                FilterChip(
                    selected = preset.isSelected,
                    onClick = { onPresetClick(preset.id) },
                    enabled = enabled,
                    label = {
                        Text(
                            text =
                                when {
                                    preset.id == "flat" -> stringResource(R.string.eq_flat)
                                    preset.name.isNullOrBlank() -> stringResource(R.string.eq_preset_number, index)
                                    else -> preset.name.orEmpty()
                                },
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer),
                )
            }
        }
    }
}

@Composable
private fun BalanceSection(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    value: Float,
    onChange: (Float) -> Unit,
    interactionEnabled: Boolean = true,
) {
    SectionContainer {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.eq_balance),
                style = MaterialTheme.typography.titleLarge,
                color = if (interactionEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            ExpressiveSwitch(checked = enabled, onCheckedChange = onToggle, enabled = interactionEnabled)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = stringResource(R.string.eq_position), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                text =
                    when {
                        value.toInt() == 0 -> stringResource(R.string.eq_balance_center)
                        value.toInt() < 0 -> stringResource(R.string.eq_balance_l, abs(value.toInt()))
                        else -> stringResource(R.string.eq_balance_r, value.toInt())
                    },
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        ResponsiveSlider(
            value = value,
            onValueChange = onChange,
            valueRange = -BALANCE_RANGE..BALANCE_RANGE,
            enabled = enabled && interactionEnabled,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = stringResource(R.string.eq_left), style = MaterialTheme.typography.labelSmall)
            Text(text = stringResource(R.string.eq_balance_center), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(text = stringResource(R.string.eq_right), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SpeedSection(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    value: Float,
    onChange: (Float) -> Unit,
    isPitchMatched: Boolean,
    onPitchMatchToggle: () -> Unit,
    pitchValue: Float = 1f,
    onPitchChange: (Float) -> Unit = {},
    interactionEnabled: Boolean = true,
) {
    SectionContainer {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.eq_speed),
                style = MaterialTheme.typography.titleLarge,
                color = if (interactionEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            ExpressiveSwitch(checked = enabled, onCheckedChange = onToggle, enabled = interactionEnabled)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text =
                if (isPitchMatched) {
                    stringResource(R.string.eq_speed_pitch_matched)
                } else {
                    stringResource(R.string.eq_speed_vinyl)
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
            TextButton(
                onClick = onPitchMatchToggle,
                enabled = enabled && interactionEnabled,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.height(32.dp),
            ) {
                Text(text = stringResource(R.string.eq_match_pitch), style = MaterialTheme.typography.labelMedium)
            }
        }

        if (!isPitchMatched) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.eq_pitch),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.eq_speed_value, pitchValue),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            }
            ResponsiveSlider(
                value = pitchValue,
                onValueChange = onPitchChange,
                valueRange = 0.5f..2.0f,
                enabled = enabled && interactionEnabled,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = stringResource(R.string.eq_speed_label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.eq_speed_value, value),
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        ResponsiveSlider(
            value = value,
            onValueChange = onChange,
            valueRange = 0.5f..2.0f,
            enabled = enabled && interactionEnabled,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "0.5x", style = MaterialTheme.typography.labelSmall)
            Text(text = stringResource(R.string.eq_speed_normal), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(text = "2.0x", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ReverbSection(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    presetValue: Float,
    onPresetChange: (Float) -> Unit,
    interactionEnabled: Boolean = true,
) {
    SectionContainer {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.eq_reverb),
                style = MaterialTheme.typography.titleLarge,
                color = if (interactionEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            ExpressiveSwitch(checked = enabled, onCheckedChange = onToggle, enabled = interactionEnabled)
        }
        Spacer(modifier = Modifier.height(12.dp))
        val presets =
            listOf(
                stringResource(R.string.eq_reverb_preset_none),
                stringResource(R.string.eq_reverb_preset_small_room),
                stringResource(R.string.eq_reverb_preset_medium_room),
                stringResource(R.string.eq_reverb_preset_large_room),
                stringResource(R.string.eq_reverb_preset_medium_hall),
                stringResource(R.string.eq_reverb_preset_large_hall),
                stringResource(R.string.eq_reverb_preset_plate),
            )
        val index = presetValue.toInt().coerceIn(0, 6)

        var expanded by remember { mutableStateOf(false) }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled && interactionEnabled) expanded = !expanded },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = presets[index],
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.eq_preset)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                enabled = enabled && interactionEnabled,
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                presets.forEachIndexed { i, presetName ->
                    DropdownMenuItem(
                        text = { Text(presetName) },
                        onClick = {
                            onPresetChange(i.toFloat())
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResponsiveSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    var isDragging by remember { mutableStateOf(false) }
    var localValue by remember(value) { mutableFloatStateOf(value.coerceIn(valueRange)) }

    LaunchedEffect(value) {
        if (!isDragging) {
            localValue = value.coerceIn(valueRange)
        }
    }

    val animatedValue by animateFloatAsState(
        targetValue = localValue,
        animationSpec =
            if (isDragging) {
                snap()
            } else {
                spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow,
                )
            },
        label = "expressive_slider",
    )

    Slider(
        value = animatedValue,
        onValueChange = {
            isDragging = true
            localValue = it
        },
        onValueChangeFinished = {
            isDragging = false
            onValueChange(localValue)
            onValueChangeFinished?.invoke()
        },
        valueRange = valueRange,
        enabled = enabled,
        modifier = modifier,
    )
}

@Composable
private fun SectionContainer(content: @Composable ColumnScope.() -> Unit) {
    val configuration = LocalConfiguration.current
    val verticalPadding =
        if (configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 16.dp else 28.dp

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = verticalPadding),
        content = content,
    )
}

@Composable
private fun SaveProfileDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.eq_save_profile)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text(text = stringResource(R.string.eq_profile_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            KeepStatusBarHiddenInDialog()
            TextButton(onClick = onSave, enabled = name.isNotBlank()) { Text(text = stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.eq_close)) } },
    )
}

@Composable
private fun ManageProfilesDialog(
    profiles: moe.rukamori.archivetune.viewmodels.EqualizerProfileUiModels,
    onApply: (String) -> Unit,
    onDelete: (String) -> Unit,
    onExport: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.eq_profiles)) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(count = profiles.size, key = { profiles[it].id }, contentType = { "profile" }) { index ->
                    ProfileRow(profiles[index], onApply, onDelete, onExport)
                }
            }
        },
        confirmButton = {
            KeepStatusBarHiddenInDialog()
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.eq_close)) }
        },
    )
}

@Composable
private fun ProfileRow(
    profile: EqualizerProfileUiModel,
    onApply: (String) -> Unit,
    onDelete: (String) -> Unit,
    onExport: (String) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(if (profile.isSelected) R.drawable.check else R.drawable.equalizer),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = profile.name.ifBlank { stringResource(R.string.eq_imported_profile) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.eq_custom_profile),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onExport(profile.id) }) {
                Icon(painter = painterResource(R.drawable.share), contentDescription = stringResource(R.string.export))
            }
            IconButton(onClick = { onDelete(profile.id) }) {
                Icon(painter = painterResource(R.drawable.delete), contentDescription = stringResource(R.string.delete))
            }
        }
    }
}

@Composable
private fun EqualizerLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LoadingIndicator(modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun EqualizerUnavailable(onOpenSystemEqualizer: () -> Unit) {
    EqualizerMessage(
        message = stringResource(R.string.eq_waiting_for_audio_session),
        onOpenSystemEqualizer = onOpenSystemEqualizer,
    )
}

@Composable
private fun EqualizerError(
    messageResId: Int,
    onOpenSystemEqualizer: () -> Unit,
) {
    EqualizerMessage(stringResource(messageResId), onOpenSystemEqualizer)
}

@Composable
private fun EqualizerMessage(
    message: String,
    onOpenSystemEqualizer: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(painter = painterResource(R.drawable.graphic_eq), contentDescription = null, modifier = Modifier.size(48.dp))
            Text(text = message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            FilledTonalButton(onClick = onOpenSystemEqualizer, shapes = ButtonDefaults.shapes()) {
                Text(text = stringResource(R.string.eq_open_system_equalizer))
            }
        }
    }
}
