/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.settings

import moe.rukamori.archivetune.playback.dsp.BitPerfectRuntime
import moe.rukamori.archivetune.constants.BIT_PERFECT_NATIVE_RATE_DEFAULT
import moe.rukamori.archivetune.constants.BIT_PERFECT_OUTPUT_DEFAULT
import moe.rukamori.archivetune.constants.BitPerfectNativeRateKey
import moe.rukamori.archivetune.constants.BitPerfectOutputKey
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.navigation.NavController
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.playback.dsp.AudioEngineRouterProcessor
import moe.rukamori.archivetune.playback.dsp.EngineRuntime
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AudioOffload
import moe.rukamori.archivetune.constants.CrossfadeEnabledKey
import moe.rukamori.archivetune.constants.FloatDspEnabledKey
import moe.rukamori.archivetune.constants.LastwaveAudioProcessingKey
import moe.rukamori.archivetune.constants.TryptifyAudioProcessingKey
import moe.rukamori.archivetune.constants.UsbExclusiveAudioKey
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SettingsPageTopBar
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.ui.screens.rememberScreenHeaderHaze
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.constants.AutomixEnabledKey

/**
 * The "Audiophile" sub-page of Playback settings — the single home for the
 * engine stack: the 32-bit float DSP pipeline, the Tryptify and LastWave
 * engine toggles (mutually exclusive), every Tryptify engine feature, and the
 * engine-owned USB-exclusive output route. Everything here writes the exact
 * same preference keys the playback service collects live, so behaviour is
 * unchanged from when these rows lived on the main Playback page.
 */
@Composable
fun AudiophileSettings(
    navController: NavController,
    scrollTo: String? = null,
) {
    val (floatDsp, onFloatDspChange) =
        rememberPreference(
            FloatDspEnabledKey,
            defaultValue = false,
        )
    val (bitPerfect, onBitPerfectChange) =
        rememberPreference(
            BitPerfectOutputKey,
            defaultValue = BIT_PERFECT_OUTPUT_DEFAULT,
        )
    val (bitPerfectNativeRate, onBitPerfectNativeRateChange) =
        rememberPreference(
            BitPerfectNativeRateKey,
            defaultValue = BIT_PERFECT_NATIVE_RATE_DEFAULT,
        )
    val (usbExclusiveAudio, onUsbExclusiveAudioChange) =
        rememberPreference(
            UsbExclusiveAudioKey,
            defaultValue = false,
        )
    val (tryptifyAudioProcessing, onTryptifyAudioProcessingChange) =
        rememberPreference(
            TryptifyAudioProcessingKey,
            defaultValue = false,
        )
    val (lastwaveAudioProcessing, onLastwaveAudioProcessingChange) =
        rememberPreference(
            LastwaveAudioProcessingKey,
            defaultValue = false,
        )

    // The exclusive route and the blending / offload paths each hold their own
    // output pipeline — flipping one on must release the others.
    val (_, onAudioOffloadChange) =
        rememberPreference(
            AudioOffload,
            defaultValue = false,
        )
    val (_, onCrossfadeEnabledChange) =
        rememberPreference(
            CrossfadeEnabledKey,
            defaultValue = false,
        )
    val (_, onAutomixEnabledChange) =
        rememberPreference(
            AutomixEnabledKey,
            defaultValue = false,
        )

    // ── Tryptify engine feature prefs (upstream's own settings surface,
    // read/written through the ported PreferencesManager so the live
    // collectors in the playback service see the changes) ─────────────────
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tryptifyPrefs =
        remember(context) {
            tf.monochrome.android.data.preferences.PreferencesManager(context)
        }
    val tryptifyUsbPin by tryptifyPrefs.usbBitPerfectEnabled.collectAsStateWithLifecycle(false)
    val onTryptifyUsbPinChange: (Boolean) -> Unit = { enabled ->
        scope.launch { tryptifyPrefs.setUsbBitPerfectEnabled(enabled) }
    }
    val tryptifySystemWideEq by tryptifyPrefs.systemWideAutoEqEnabled.collectAsStateWithLifecycle(false)
    val onTryptifySystemWideEqChange: (Boolean) -> Unit = { enabled ->
        scope.launch { tryptifyPrefs.setSystemWideAutoEqEnabled(enabled) }
    }
    val tryptifyDownmixOn by tryptifyPrefs.multichannelDownmixEnabled.collectAsStateWithLifecycle(true)
    val onTryptifyDownmixChange: (Boolean) -> Unit = { enabled ->
        scope.launch { tryptifyPrefs.setMultichannelDownmixEnabled(enabled) }
    }
    val tryptifyBlockSize by tryptifyPrefs.dspBlockSize.collectAsStateWithLifecycle(1024)
    val tryptifySpectrumOn by tryptifyPrefs.spectrumAnalyzerEnabled.collectAsStateWithLifecycle(true)
    val onTryptifySpectrumChange: (Boolean) -> Unit = { enabled ->
        scope.launch { tryptifyPrefs.setSpectrumAnalyzerEnabled(enabled) }
    }
    val tryptifyFftSize by tryptifyPrefs.spectrumFftSize.collectAsStateWithLifecycle(8192)
    var showTryptifyBlockSizeDialog by rememberSaveable { mutableStateOf(false) }
    var showTryptifyFftDialog by rememberSaveable { mutableStateOf(false) }
    val tryptifyBlockSizeLabel =
        remember(tryptifyBlockSize) {
            when {
                tryptifyBlockSize >= 1024 && tryptifyBlockSize % 1024 == 0 -> "${tryptifyBlockSize / 1024}K"
                else -> tryptifyBlockSize.toString()
            }
        }
    val usbRouter = remember(context) { tf.monochrome.android.audio.UsbAudioRouter(context) }
    val usbDevice by usbRouter.usbOutputDevice.collectAsStateWithLifecycle(initialValue = null)
    val tryptifyUsbPinSubtitle =
        usbDevice?.let { device ->
            context.getString(R.string.tryptify_usb_pin_on, usbRouter.describe(device))
        }

    if (showTryptifyBlockSizeDialog) {
        TryptifyBlockSizeDialog(
            current = tryptifyBlockSize,
            onDismiss = { showTryptifyBlockSizeDialog = false },
            onPick = { size ->
                scope.launch { tryptifyPrefs.setDspBlockSize(size) }
                showTryptifyBlockSizeDialog = false
            },
        )
    }

    if (showTryptifyFftDialog) {
        TryptifyFftSizeDialog(
            current = tryptifyFftSize,
            onDismiss = { showTryptifyFftDialog = false },
            onPick = { size ->
                scope.launch { tryptifyPrefs.setSpectrumFftSize(size) }
                showTryptifyFftDialog = false
            },
        )
    }

    val headerHaze = rememberScreenHeaderHaze()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsPageTopBar(
                titleText = stringResource(R.string.audiophile_settings_title),
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
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal),
                    )
                    .then(positions.containerModifier())
                    .verticalScroll(scrollState)
                    .hazeSource(headerHaze)
                    .padding(top = topPadding)
                    .padding(bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding),
            ) {
                // The live audio chain pill: real-time INPUT -> stage -> OUTPUT
                // exactly as the decoder/sink/output are wired right now,
                // refreshed every second and on every track/route change.
                LiveAudioChainCard(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp),
                )

                PreferenceGroup(title = stringResource(R.string.audiophile_engines_group)) {
                    item {
                        Column(modifier = positions.modifierFor("bit_perfect_output")) {
                            SwitchPreference(
                                title = { Text(stringResource(R.string.bit_perfect_output)) },
                                description = stringResource(R.string.bit_perfect_output_desc),
                                icon = { Icon(painterResource(R.drawable.solar_headphones), null) },
                                checked = bitPerfect,
                                onCheckedChange = onBitPerfectChange,
                            )
                            // Read-only LIVE status: what the active output is
                            // actually doing right now (never the request).
                            val statusText = rememberBitPerfectStatusLine()
                            if (bitPerfect && statusText != null) {
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 56.dp, top = 2.dp, end = 16.dp),
                                )
                            }
                            if (bitPerfect) {
                                SwitchPreference(
                                    title = { Text(stringResource(R.string.bit_perfect_native_rate)) },
                                    description = stringResource(R.string.bit_perfect_native_rate_desc),
                                    icon = { Icon(painterResource(R.drawable.solar_aspect_ratio_linear), null) },
                                    checked = bitPerfectNativeRate,
                                    onCheckedChange = onBitPerfectNativeRateChange,
                                )
                            }
                        }
                    }

                    item(visible = !tryptifyAudioProcessing && !lastwaveAudioProcessing) {
                        Column(modifier = positions.modifierFor("float_dsp")) {
                            SwitchPreference(
                                title = { Text(stringResource(R.string.float_dsp)) },
                                description = stringResource(R.string.float_dsp_desc),
                                icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                                checked = floatDsp,
                                onCheckedChange = onFloatDspChange,
                            )
                        }
                    }

                    item {
                        // The ported Tryptify engine: C++17 mixing console + Oxford
                        // effects + measurement-driven AutoEQ + its libusb UAC
                        // bit-perfect USB-DAC driver (when USB-exclusive is on).
                        // Enabling it adds the "Tryptify EQ" tab to the Equalizer.
                        Column(modifier = positions.modifierFor("tryptify_audio_processing")) {
                            SwitchPreference(
                                title = { Text(stringResource(R.string.tryptify_audio_processing)) },
                                description = stringResource(R.string.tryptify_audio_processing_desc),
                                icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                                checked = tryptifyAudioProcessing,
                                onCheckedChange = { enabled ->
                                    onTryptifyAudioProcessingChange(enabled)
                                    if (enabled) {
                                        // Exactly one engine may own the DSP tail.
                                        onLastwaveAudioProcessingChange(false)
                                    }
                                },
                            )
                        }
                    }

                    item {
                        // The ported LastWave-native engine: its native Oboe/soxr
                        // DSP (15-band graphic EQ + Studio Master Clarity) and
                        // usbdevfs exclusive USB-DAC driver / bit-perfect mixer
                        // attributes (when USB-exclusive is on).
                        Column(modifier = positions.modifierFor("lastwave_audio_processing")) {
                            SwitchPreference(
                                title = { Text(stringResource(R.string.lastwave_audio_processing)) },
                                description = stringResource(R.string.lastwave_audio_processing_desc),
                                icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                                checked = lastwaveAudioProcessing,
                                onCheckedChange = { enabled ->
                                    onLastwaveAudioProcessingChange(enabled)
                                    if (enabled) {
                                        onTryptifyAudioProcessingChange(false)
                                    }
                                },
                            )
                        }
                    }
                }

                PreferenceGroup(title = stringResource(R.string.tryptify_engine_features_group)) {
                    // ── Tryptify engine features (visible only while the engine
                    // is on; every one is wired to the live audio chain) ────────
                    item(visible = tryptifyAudioProcessing) {
                        Column(modifier = positions.modifierFor("tryptify_usb_pin")) {
                            SwitchPreference(
                                title = { Text(stringResource(R.string.tryptify_usb_pin)) },
                                description = tryptifyUsbPinSubtitle
                                    ?: stringResource(R.string.tryptify_usb_pin_desc),
                                icon = { Icon(painterResource(R.drawable.solar_volume_up_linear), null) },
                                checked = tryptifyUsbPin,
                                onCheckedChange = { onTryptifyUsbPinChange(it) },
                            )
                        }
                    }

                    item(visible = tryptifyAudioProcessing) {
                        Column(modifier = positions.modifierFor("tryptify_system_wide_autoeq")) {
                            SwitchPreference(
                                title = { Text(stringResource(R.string.tryptify_system_wide_autoeq)) },
                                description = stringResource(R.string.tryptify_system_wide_autoeq_desc),
                                icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                                checked = tryptifySystemWideEq,
                                onCheckedChange = { onTryptifySystemWideEqChange(it) },
                            )
                        }
                    }

                    item(visible = tryptifyAudioProcessing) {
                        Column(modifier = positions.modifierFor("tryptify_multichannel_downmix")) {
                            SwitchPreference(
                                title = { Text(stringResource(R.string.tryptify_multichannel_downmix)) },
                                description = stringResource(R.string.tryptify_multichannel_downmix_desc),
                                icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                                checked = tryptifyDownmixOn,
                                onCheckedChange = { onTryptifyDownmixChange(it) },
                            )
                        }
                    }

                    item(visible = tryptifyAudioProcessing) {
                        Column(modifier = positions.modifierFor("tryptify_dsp_block_size")) {
                            PreferenceEntry(
                                title = { Text(stringResource(R.string.tryptify_dsp_block_size)) },
                                description = stringResource(R.string.tryptify_dsp_block_size_desc),
                                icon = { Icon(painterResource(R.drawable.info), null) },
                                trailingContent = {
                                    Text(
                                        text = tryptifyBlockSizeLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                onClick = { showTryptifyBlockSizeDialog = true },
                            )
                        }
                    }

                    item(visible = tryptifyAudioProcessing) {
                        Column(modifier = positions.modifierFor("tryptify_spectrum")) {
                            SwitchPreference(
                                title = { Text(stringResource(R.string.tryptify_spectrum_analyzer)) },
                                description = stringResource(R.string.tryptify_spectrum_analyzer_desc),
                                icon = { Icon(painterResource(R.drawable.stats), null) },
                                checked = tryptifySpectrumOn,
                                onCheckedChange = { onTryptifySpectrumChange(it) },
                            )
                        }
                    }

                    item(visible = tryptifyAudioProcessing && tryptifySpectrumOn) {
                        Column(modifier = positions.modifierFor("tryptify_spectrum_fft")) {
                            PreferenceEntry(
                                title = { Text(stringResource(R.string.tryptify_spectrum_fft_size)) },
                                description = stringResource(R.string.tryptify_spectrum_fft_size_desc),
                                icon = { Icon(painterResource(R.drawable.stats), null) },
                                trailingContent = {
                                    Text(
                                        text = "$tryptifyFftSize",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                onClick = { showTryptifyFftDialog = true },
                            )
                        }
                    }

                    item(visible = lastwaveAudioProcessing) {
                        Column(modifier = positions.modifierFor("lastwave_engine_hint")) {
                            PreferenceEntry(
                                title = { Text(stringResource(R.string.lastwave_eq_tab_hint)) },
                                description = stringResource(R.string.lastwave_eq_tab_hint_desc),
                                icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                            )
                        }
                    }
                }

                PreferenceGroup(title = stringResource(R.string.audiophile_output_group)) {
                    // The engine-owned exclusive route sits BELOW both engines'
                    // feature blocks: every engine-dependent setting must appear
                    // under the toggle that enables it, and this one belongs to
                    // whichever engine is on (its driver serves the stream).
                    item(visible = tryptifyAudioProcessing || lastwaveAudioProcessing) {
                        Column(modifier = positions.modifierFor("usb_exclusive_audio")) {
                            SwitchPreference(
                                title = { Text(stringResource(R.string.usb_exclusive_audio)) },
                                description =
                                    stringResource(
                                        if (tryptifyAudioProcessing) {
                                            R.string.usb_exclusive_audio_tryptify_desc
                                        } else {
                                            R.string.usb_exclusive_audio_lastwave_desc
                                        },
                                    ),
                                icon = { Icon(painterResource(R.drawable.solar_volume_up_linear), null) },
                                checked = usbExclusiveAudio,
                                onCheckedChange = { enabled ->
                                    onUsbExclusiveAudioChange(enabled)
                                    if (enabled) {
                                        // One exclusive stream only: the blending
                                        // engines and offload each hold their own
                                        // output path.
                                        onAudioOffloadChange(false)
                                        onCrossfadeEnabledChange(false)
                                        onAutomixEnabledChange(false)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TryptifyBlockSizeDialog(
    current: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    DefaultDialog(
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    ) {
        Column(modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = stringResource(R.string.tryptify_dsp_block_size),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = stringResource(R.string.tryptify_dsp_block_size_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            // The upstream chip row, laid out as wrap rows of selectable chips.
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tf.monochrome.android.data.preferences.PreferencesManager.DSP_BLOCK_SIZES.forEach { size ->
                    androidx.compose.material3.FilterChip(
                        selected = size == current,
                        onClick = { onPick(size) },
                        label = {
                            Text(
                                if (size >= 1024 && size % 1024 == 0) {
                                    "${size / 1024}K"
                                } else {
                                    size.toString()
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TryptifyFftSizeDialog(
    current: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    DefaultDialog(
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    ) {
        Column(modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = stringResource(R.string.tryptify_spectrum_fft_size),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = stringResource(R.string.tryptify_spectrum_fft_size_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(4096, 8192, 16384).forEach { size ->
                    androidx.compose.material3.FilterChip(
                        selected = size == current,
                        onClick = { onPick(size) },
                        label = { Text(size.toString()) },
                    )
                }
            }
        }
    }
}

/**
 * The read-only Bit-Perfect status line. It renders the ACTUAL active output
 * (from [BitPerfectRuntime], updated per track by the playback service), never
 * merely the requested format:
 *
 *  - verified + rate matched  -> "Bit-Perfect • 24-bit • 96 kHz"
 *  - verified, rate carried    -> "Native Rate • 24-bit • 44.1 kHz"
 *  - rate converted            -> "Resampling • 24-bit/96 kHz → 48 kHz"
 *  - anything else             -> "Bit-Perfect unavailable" (+ reason)
 */
@Composable
private fun rememberBitPerfectStatusLine(): String? {
    val status = BitPerfectRuntime.status
    return remember(status) {
        with(status) {
            when {
                usbExclusiveActive && verifiedBitPerfect && nativeRateMatched ->
                    "Bit-Perfect • ${sourceBitDepth}-bit • ${rateKhz(sourceSampleRate)}"
                verifiedBitPerfect && nativeRateMatched ->
                    "Bit-Perfect • ${sourceBitDepth}-bit • ${rateKhz(sourceSampleRate)}"
                verifiedBitPerfect ->
                    "Native Rate • ${sourceBitDepth}-bit • ${rateKhz(sourceSampleRate)}"
                resamplerActive && sourceSampleRate > 0 && outputSampleRate > 0 ->
                    "Resampling • ${sourceBitDepth}-bit/${rateKhz(sourceSampleRate)} → ${rateKhz(outputSampleRate)}"
                else -> "Bit-Perfect unavailable" + (failureReason?.let { " ($it)" } ?: "")
            }
        }
    }
}

private fun rateKhz(hz: Int): String =
    if (hz % 1000 == 0) "${hz / 1000} kHz" else {
        val k = hz / 1000.0
        if (k == k.toInt().toDouble()) "${k.toInt()} kHz" else String.format(java.util.Locale.US, "%.1f kHz", k)
    }

/**
 * The Live Audio Chain pill — the real-time signal-path trace at the top of
 * the Audiophile page. Shows exactly what is being sent to the output device
 * RIGHT NOW:
 *
 *   [INPUT 24-bit | 44.1 kHz] → ( DSP / DIRECT HAL / ANDROID MIXER ) → [OUTPUT 24-bit | 44.1 kHz]
 *                                · USB Exclusive · Bit-Perfect ·
 *
 * INPUT comes from the playback service's decoded-format mirror (native PCM
 * depth, or the f32 pipe when the float route is engaged); OUTPUT reflects
 * the ACTUAL negotiated route (usbdevfs/libusb exclusive wire, verified
 * bit-perfect mixer attributes, or the Android mixer's HAL rate). Refreshes
 * every second and on every track/route change — [BitPerfectRuntime.status]
 * is Compose state and the engine/HAL half is polled.
 */
@Composable
private fun LiveAudioChainCard(modifier: Modifier = Modifier) {
    val status = BitPerfectRuntime.status
    val context = LocalContext.current

    // One-second poll of the volatile engine runtime + the HAL's own view.
    // Compose state (status) already covers "whenever there's any change".
    var pollTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000L)
            pollTick++
        }
    }
    val runtime = EngineRuntime
    val halRateHz = remember(pollTick) { readHalSampleRateHz(context) }
    val routedLabel = remember(pollTick) { readRoutedOutputLabel(context) }
    val usbExclusive = remember(pollTick) { runtime.usbExclusiveActive }
    val tryptifyPinActive = remember(pollTick) { runtime.tryptifyUsbPinActive }
    val engine = remember(pollTick) { runtime.activeEngine }
    val usbRateHz = remember(pollTick) {
        runtime.lastwaveUsbRateHz.takeIf { it > 0 }
            ?: runtime.tryptifyUsbStream?.sampleRateHz?.takeIf { it > 0 }
    }
    val usbBits = remember(pollTick) {
        runtime.lastwaveUsbBitsPerSample.takeIf { it > 0 }
            ?: runtime.tryptifyUsbStream?.bitsPerSample?.takeIf { it > 0 }
    }

    val hasSignal = status.sourceSampleRate > 0
    val floatPcmLabel = stringResource(R.string.live_audio_chain_float_pcm)
    val inputBitsLabel =
        if (status.sourceEncoding == C.ENCODING_PCM_FLOAT) floatPcmLabel
        else "${status.sourceBitDepth}-bit"
    val inputRateLabel = if (hasSignal) rateKhz(status.sourceSampleRate) else "—"

    val stageLabel = when {
        engine == AudioEngineRouterProcessor.Engine.TRYPTIFY -> "TRYPTIFY DSP"
        engine == AudioEngineRouterProcessor.Engine.LASTWAVE -> "LASTWAVE DSP"
        usbExclusive || status.verifiedBitPerfect -> stringResource(R.string.live_audio_chain_direct_hal)
        else -> stringResource(R.string.live_audio_chain_android_mixer)
    }

    val outputBitsLabel: String
    val outputRateLabel: String
    when {
        usbExclusive && usbBits != null && usbRateHz != null -> {
            outputBitsLabel = if (status.sourceEncoding == C.ENCODING_PCM_FLOAT) floatPcmLabel else "${usbBits}-bit"
            outputRateLabel = rateKhz(usbRateHz)
        }
        status.verifiedBitPerfect -> {
            outputBitsLabel =
                if (status.outputEncoding == C.ENCODING_PCM_FLOAT) floatPcmLabel
                else "${status.outputBitDepth}-bit"
            outputRateLabel =
                if (status.outputSampleRate > 0) rateKhz(status.outputSampleRate) else inputRateLabel
        }
        hasSignal -> {
            // The Android mixer route: the DSP sink's 16-bit pipeline feeds the
            // HAL at its native mix rate — report that honestly.
            outputBitsLabel = "16-bit"
            outputRateLabel = halRateHz?.let(::rateKhz) ?: inputRateLabel
        }
        else -> {
            outputBitsLabel = "—"
            outputRateLabel = "—"
        }
    }

    val outputRouteLabel = when {
        usbExclusive -> stringResource(R.string.live_audio_chain_usb_exclusive)
        tryptifyPinActive -> stringResource(R.string.live_audio_chain_usb_framework)
        else -> routedLabel
    }

    val statusLine = rememberBitPerfectStatusLine()

    val liveDotAlpha by rememberInfiniteTransition(label = "liveChainDot")
        .animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_200, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "liveChainDotAlpha",
        )

    val chainShape = RoundedCornerShape(28.dp)
    val pillShape = RoundedCornerShape(10.dp)
    val chipShape = RoundedCornerShape(14.dp)
    val accent = MaterialTheme.colorScheme.primary
    val outputAccent = MaterialTheme.colorScheme.tertiary

    Column(
        modifier = modifier
            .clip(chainShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(outputAccent.copy(alpha = liveDotAlpha)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.live_audio_chain_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = outputRouteLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(12.dp))

        if (!hasSignal) {
            Text(
                text = stringResource(R.string.live_audio_chain_idle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ChainSideColumn(
                    label = stringResource(R.string.live_audio_chain_input),
                    primaryValue = inputBitsLabel,
                    secondaryValue = inputRateLabel,
                    tint = accent,
                    pillShape = pillShape,
                    modifier = Modifier.weight(1.15f),
                )

                ChainStageChip(
                    label = stageLabel,
                    shape = chipShape,
                    modifier = Modifier.weight(1.1f),
                )

                ChainSideColumn(
                    label = stringResource(R.string.live_audio_chain_output),
                    primaryValue = outputBitsLabel,
                    secondaryValue = outputRateLabel,
                    tint = outputAccent,
                    pillShape = pillShape,
                    modifier = Modifier.weight(1.15f),
                )
            }

            if (statusLine != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (status.verifiedBitPerfect) {
                        outputAccent
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ChainSideColumn(
    label: String,
    primaryValue: String,
    secondaryValue: String,
    tint: Color,
    pillShape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(pillShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = primaryValue,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            Box(
                modifier = Modifier
                    .clip(pillShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = secondaryValue,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ChainStageChip(
    label: String,
    shape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.graphic_eq),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                maxLines = 1,
            )
        }
    }
}

/** The HAL's advertised output mix rate — what the Android mixer runs at. */
private fun readHalSampleRateHz(context: Context): Int? {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
    return runCatching {
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
    }.getOrNull()?.takeIf { it > 0 }
}

/** A short label for the currently routed output device (no callbacks). */
private fun readRoutedOutputLabel(context: Context): String {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        ?: return "Android Mixer"
    val outputs = runCatching {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    }.getOrDefault(emptyList())
    val orderedTypes = intArrayOf(
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    )
    // firstNotNullOfOrNull has no IntArray overload — walk the priority list.
    var device: AudioDeviceInfo? = null
    for (type in orderedTypes) {
        val found = outputs.firstOrNull { it.type == type }
        if (found != null) {
            device = found
            break
        }
    }
    val routed = device ?: outputs.firstOrNull() ?: return "Android Mixer"
    return when (routed.type) {
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        -> "USB Audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        -> "Wired"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        else -> "Speaker"
    }
}
