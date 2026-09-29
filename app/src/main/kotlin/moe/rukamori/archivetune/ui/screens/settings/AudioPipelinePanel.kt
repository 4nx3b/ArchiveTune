package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.playback.dsp.EngineRuntime
import tf.monochrome.android.audio.dsp.ChannelDetectorProcessor
import tf.monochrome.android.audio.dsp.DspEngineManager
import tf.monochrome.android.audio.dsp.SnapinType
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.audio.pipeline.AudioPipelineInputs
import tf.monochrome.android.audio.pipeline.AudioPipelineMonitor
import tf.monochrome.android.audio.pipeline.ChainInput
import tf.monochrome.android.audio.pipeline.OutputDeviceProbe
import tf.monochrome.android.audio.pipeline.OutputPath
import tf.monochrome.android.audio.pipeline.UsbStream
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.EqRepository

/**
 * Gathers what the Audio Pipeline panel shows (upstream Tryptify's
 * AudioPipelineViewModel, adapted to ArchiveTune's engine architecture):
 * decoder facts from the service-side [AudioPipelineMonitor], the chain's
 * measured input from [ChannelDetectorProcessor], the output route from
 * [OutputDeviceProbe] and [EngineRuntime], the engine's own prefs for block
 * size / preset, the mixer's live stereo-width snapin, and the spectrum
 * tap's FFT size. Everything is WhileSubscribed — nothing polls while the
 * panel is shut.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@dagger.hilt.android.lifecycle.HiltViewModel
class AudioPipelineViewModel @Inject constructor(
    monitor: AudioPipelineMonitor,
    channelDetector: ChannelDetectorProcessor,
    spectrumTap: SpectrumAnalyzerTap,
    outputProbe: OutputDeviceProbe,
    preferences: PreferencesManager,
    eqRepository: EqRepository,
    dspEngine: DspEngineManager,
) : ViewModel() {

    private data class Polled(
        val fftSize: Int,
        val halSampleRateHz: Int?,
    )

    private val polled = flow {
        while (true) {
            emit(
                Polled(
                    fftSize = spectrumTap.fftSize,
                    halSampleRateHz = outputProbe.halSampleRateHz(),
                ),
            )
            delay(POLL_INTERVAL_MS)
        }
    }

    private val eqPresetName: kotlinx.coroutines.flow.Flow<String?> = preferences.eqActivePresetId
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) {
                flowOf<String?>(null)
            } else {
                eqRepository.getPresetByIdFlow(id).let { p ->
                    flow { p.collect { emit(it?.name) } }
                }
            }
        }

    /**
     * The mixer's live Stereo snapin width (upstream's contract): null when
     * the mixer has no non-bypassed stereo stage at all, 0 dB when it has
     * one doing nothing. Read from parameter slot 1 like upstream's
     * STEREO_WIDTH_PARAM.
     */
    private val stereoWidthDb: kotlinx.coroutines.flow.Flow<Float?> = combine(
        dspEngine.enabled,
        dspEngine.buses,
    ) { enabled, buses ->
        if (!enabled) return@combine null
        buses.asSequence()
            .flatMap { it.plugins.asSequence() }
            .firstOrNull { !it.bypassed && it.type == SnapinType.STEREO }
            ?.let { stereo -> stereo.parameters[STEREO_WIDTH_PARAM] }
    }

    private data class DspFacts(
        val blockFrames: Int,
        val eqPresetName: String?,
        val stereoWidthDb: Float?,
        val deviceName: String?,
    )

    val inputs: StateFlow<AudioPipelineInputs?> = combine(
        monitor.stream,
        monitor.decoderName,
        channelDetector.state,
        polled,
        combine(preferences.dspBlockSize, eqPresetName, stereoWidthDb, outputProbe.routed) { block, eq, width, routed ->
            DspFacts(block, eq, width, routed?.name)
        },
    ) { stream, decoder, chain, poll, dsp ->
        buildInputs(stream, decoder, chain, poll, dsp)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(POLL_INTERVAL_MS), null)

    private fun buildInputs(
        stream: tf.monochrome.android.audio.pipeline.DecodedStream?,
        decoder: String?,
        chain: ChannelDetectorProcessor.ChannelState?,
        poll: Polled,
        dsp: DspFacts,
    ): AudioPipelineInputs {
        val runtime = EngineRuntime
        val usbExclusive = runtime.usbExclusiveActive
        val path = when {
            usbExclusive && runtime.activeEngine ==
                moe.rukamori.archivetune.playback.dsp.AudioEngineRouterProcessor.Engine.TRYPTIFY ->
                OutputPath.USB_EXCLUSIVE
            usbExclusive && runtime.activeEngine ==
                moe.rukamori.archivetune.playback.dsp.AudioEngineRouterProcessor.Engine.LASTWAVE ->
                OutputPath.USB_EXCLUSIVE
            usbExclusive -> OutputPath.USB_EXCLUSIVE
            runtime.tryptifyUsbPinActive -> OutputPath.USB_FRAMEWORK
            else -> OutputPath.AUDIO_TRACK
        }
        val tryptifyUsb = runtime.tryptifyUsbStream
        val usbStream = when {
            tryptifyUsb != null -> UsbStream(
                sampleRateHz = tryptifyUsb.sampleRateHz,
                bitsPerSample = tryptifyUsb.bitsPerSample,
                channels = tryptifyUsb.channels,
                detail = buildString {
                    append(if (tryptifyUsb.isUac2) "UAC2" else "UAC1")
                    if (tryptifyUsb.hasFeedbackEndpoint) append(" · async feedback")
                },
            )
            runtime.lastwaveUsbRateHz > 0 -> UsbStream(
                sampleRateHz = runtime.lastwaveUsbRateHz,
                bitsPerSample = runtime.lastwaveUsbBitsPerSample,
                channels = 2,
                detail = "usbdevfs",
            )
            else -> null
        }
        return AudioPipelineInputs(
            stream = stream,
            decoderName = decoder,
            chain = chain?.let {
                ChainInput(
                    sampleRate = it.sampleRate,
                    channelCount = it.channelCount,
                    layoutName = it.layoutName,
                    isFloat = it.isFloat,
                )
            },
            speedRatio = 1f,
            dspBlockFrames = dsp.blockFrames,
            eqPresetName = dsp.eqPresetName,
            stereoWidthDb = dsp.stereoWidthDb,
            visualizerFftSize = poll.fftSize,
            outputPath = path,
            deviceName = dsp.deviceName,
            halSampleRateHz = poll.halSampleRateHz,
            usb = usbStream,
        )
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1000L

        /** Upstream's STEREO_WIDTH_PARAM — parameter slot 1 of the snapin. */
        const val STEREO_WIDTH_PARAM = 1
    }
}

/**
 * The Audio Pipeline diagnostics section for Developer options: the whole
 * signal path, stage by stage — what the file is, what decoded it, what
 * changed its rate, what the DSP did, and where it went.
 *
 * Collapsed by default at BOTH levels (the section and each stage card):
 * tapping the header expands it; tapping a stage card expands that stage's
 * field rows. A field with no source shows an em dash — never a plausible
 * number (this is a diagnostics surface; people screenshot it).
 */
@Composable
internal fun AudioPipelineSection(
    speedRatio: Float,
    taggedCodec: String?,
    taggedBitDepth: Int?,
    taggedBitRateKbps: Int?,
    deviceName: String?,
    viewModel: AudioPipelineViewModel = hiltViewModel(),
) {
    val inputs by viewModel.inputs.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.graphic_eq),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.audio_pipeline_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.audio_pipeline_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (expanded) "−" else "+",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                val snapshot = remember(inputs, speedRatio, taggedCodec, taggedBitDepth, taggedBitRateKbps, deviceName) {
                    inputs?.let { live ->
                        tf.monochrome.android.audio.pipeline.buildAudioPipelineSnapshot(
                            live.copy(
                                speedRatio = speedRatio,
                                taggedCodec = taggedCodec,
                                taggedBitDepth = taggedBitDepth,
                                taggedBitRateKbps = taggedBitRateKbps,
                                deviceName = deviceName ?: live.deviceName,
                            ),
                        )
                    }
                }
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (snapshot == null) {
                        Text(
                            text = stringResource(R.string.audio_pipeline_waiting),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    } else {
                        snapshot.sections.forEachIndexed { index, section ->
                            PipelineStageCard(section = section, accent = accentFor(index))
                            if (index != snapshot.sections.lastIndex) {
                                PipelineConnector()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Three theme accents rotating (upstream's discipline), never invented hues. */
@Composable
private fun accentFor(index: Int): Color = when (index % 3) {
    0 -> MaterialTheme.colorScheme.primary
    1 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.secondary
}

@Composable
private fun PipelineStageCard(
    section: tf.monochrome.android.audio.pipeline.PipelineSection,
    accent: Color,
) {
    var expanded by rememberSaveable(section.stage.name) { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tint = if (section.engaged) accent else muted.copy(alpha = 0.55f)
    val chip = if (section.engaged) accent.copy(alpha = 0.16f) else muted.copy(alpha = 0.07f)

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
            .copy(alpha = if (section.engaged) 0.16f else 0.08f),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    modifier = Modifier.size(32.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = chip,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = stageGlyph(section.stage),
                            style = MaterialTheme.typography.labelLarge,
                            color = tint,
                        )
                    }
                }
                Text(
                    section.stage.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                    modifier = Modifier.weight(1f),
                )
                if (section.engaged) {
                    Box(
                        modifier =
                            Modifier
                                .size(6.dp)
                                .background(accent, CircleShape),
                    )
                }
                if (section.bypassed) {
                    Text(
                        text = stringResource(R.string.audio_pipeline_bypassed),
                        style = MaterialTheme.typography.labelSmall,
                        color = muted.copy(alpha = 0.7f),
                    )
                }
                Text(
                    text = if (expanded) "−" else "+",
                    style = MaterialTheme.typography.titleMedium,
                    color = muted,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    section.fields.forEach { FieldRow(it) }
                    section.note?.let { note ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = muted.copy(alpha = 0.65f),
                        )
                    }
                }
            }
        }
    }
}

/** Compact glyph per stage (icons without a painter import dependency). */
private fun stageGlyph(stage: tf.monochrome.android.audio.pipeline.PipelineStage): String =
    when (stage) {
        tf.monochrome.android.audio.pipeline.PipelineStage.TRACK -> "♪"
        tf.monochrome.android.audio.pipeline.PipelineStage.DECODER -> "▣"
        tf.monochrome.android.audio.pipeline.PipelineStage.RESAMPLER -> "≈"
        tf.monochrome.android.audio.pipeline.PipelineStage.DSP -> "≡"
        tf.monochrome.android.audio.pipeline.PipelineStage.OUTPUT -> "◉"
    }

@Composable
private fun FieldRow(field: tf.monochrome.android.audio.pipeline.PipelineField) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            field.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            field.display,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (field.isKnown) FontWeight.Medium else FontWeight.Normal,
            textAlign = TextAlign.End,
            color =
                if (field.isKnown) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                },
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** The arrow from one stage down into the next. */
@Composable
private fun PipelineConnector() {
    Surface(
        modifier = Modifier.padding(start = 28.dp),
        color = Color.Transparent,
    ) {
        Box(
            modifier =
                Modifier
                    .width(2.dp)
                    .height(14.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        RoundedCornerShape(1.dp),
                    ),
        )
    }
}
