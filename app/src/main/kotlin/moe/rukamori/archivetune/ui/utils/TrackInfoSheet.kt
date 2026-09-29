@file:OptIn(androidx.media3.common.util.UnstableApi::class)

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.utils

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.db.entities.codecLabel
import moe.rukamori.archivetune.db.entities.isLossless
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.dsp.AudioEngineRouterProcessor
import moe.rukamori.archivetune.playback.dsp.EngineRuntime
import moe.rukamori.archivetune.utils.isLocalMediaId
import moe.rukamori.archivetune.telegram.isTelegramMediaId
import tf.monochrome.android.audio.dsp.ChannelDetectorProcessor
import tf.monochrome.android.audio.pipeline.AudioPipelineMonitor
import tf.monochrome.android.audio.pipeline.OutputDeviceProbe
import com.lastwave.app.playback.UsbDacMonitor

/**
 * Track Info & Specs — the whole truth about the playing track in one place:
 * what the file is (codec / bit depth / sample rate / bitrate), where it came
 * from (the provider that actually won, delivery protocol), what the other
 * providers were doing, how loud it plays, and where the samples end up
 * (engine, DAC, signal path, hardware clock). Everything on the page is live:
 * flows are collected from the service / DB and the volatile engine facts are
 * re-polled every second while the sheet is open.
 *
 * Lives in the player overflow menu (a list row, not a pill action) and
 * replaces the old Developer-options Audio Pipeline panel, which showed a
 * subset of the same facts in a place nobody looks.
 */

private enum class TrackInfoTab {
    OVERVIEW,
    AUDIO_SPECS,
}

private data class TrackInfoBadge(
    val iconRes: Int,
    val text: String,
    val highlight: Boolean = false,
)

private data class TrackInfoRow(
    val label: String,
    val value: String,
)

private data class ProviderRow(
    val name: String,
    val status: String,
    val worked: Boolean,
    val note: String,
)

@HiltViewModel
class TrackInfoViewModel @Inject constructor(
    monitor: AudioPipelineMonitor,
    channelDetector: ChannelDetectorProcessor,
    outputProbe: OutputDeviceProbe,
    usbDacMonitor: UsbDacMonitor,
) : ViewModel() {

    data class Polled(
        val halSampleRateHz: Int?,
        val engineName: String,
        val outputFloat: Boolean,
        val usbExclusive: Boolean,
        val usbVendorId: Int,
        val usbProductId: Int,
        val tryptifyUsbRateHz: Int,
        val tryptifyUsbBits: Int,
    )

    private val polled = flow {
        while (true) {
            val runtime = EngineRuntime
            emit(
                Polled(
                    halSampleRateHz = outputProbe.halSampleRateHz(),
                    engineName = when (runtime.activeEngine) {
                        AudioEngineRouterProcessor.Engine.TRYPTIFY -> "Tryptify"
                        AudioEngineRouterProcessor.Engine.LASTWAVE -> "LastWave"
                        else -> "None"
                    },
                    outputFloat = runtime.outputFloat,
                    usbExclusive = runtime.usbExclusiveActive,
                    usbVendorId = usbDacMonitor.state.value.dac?.vendorId ?: -1,
                    usbProductId = usbDacMonitor.state.value.dac?.productId ?: -1,
                    tryptifyUsbRateHz = runtime.tryptifyUsbStream?.sampleRateHz ?: 0,
                    tryptifyUsbBits = runtime.tryptifyUsbStream?.bitsPerSample ?: 0,
                ),
            )
            delay(POLL_INTERVAL_MS)
        }
    }

    data class PipelineFacts(
        val decodedBits: Int?,
        val decodedFloat: Boolean,
        val decodedRateHz: Int?,
        val decodedChannels: Int?,
        val decoderName: String?,
        val chainChannels: Int?,
        val chainLayoutName: String?,
        val routedName: String?,
        val halSampleRateHz: Int?,
        val engineName: String,
        val outputFloat: Boolean,
        val usbExclusive: Boolean,
        val usbVendorId: Int,
        val usbProductId: Int,
        val tryptifyUsbRateHz: Int,
        val tryptifyUsbBits: Int,
    )

    val facts: StateFlow<PipelineFacts?> = combine(
        monitor.stream,
        monitor.decoderName,
        channelDetector.state,
        outputProbe.routed,
        polled,
    ) { stream, decoder, chain, routed, poll ->
        PipelineFacts(
            decodedBits = stream?.pcmBits,
            decodedFloat = stream?.pcmIsFloat == true,
            decodedRateHz = stream?.sampleRate,
            decodedChannels = stream?.channelCount,
            decoderName = decoder,
            chainChannels = chain?.channelCount,
            chainLayoutName = chain?.layoutName,
            routedName = routed?.name,
            halSampleRateHz = poll.halSampleRateHz,
            engineName = poll.engineName,
            outputFloat = poll.outputFloat,
            usbExclusive = poll.usbExclusive,
            usbVendorId = poll.usbVendorId,
            usbProductId = poll.usbProductId,
            tryptifyUsbRateHz = poll.tryptifyUsbRateHz,
            tryptifyUsbBits = poll.tryptifyUsbBits,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(POLL_INTERVAL_MS), null)

    private companion object {
        const val POLL_INTERVAL_MS = 1000L
    }
}

@Composable
fun TrackInfoAndSpecs() {
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current

    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val currentFormat by playerConnection.currentFormat.collectAsStateWithLifecycle()
    val viewModel: TrackInfoViewModel = hiltViewModel()
    val facts by viewModel.facts.collectAsStateWithLifecycle()

    val streamInfo by playerConnection.service.currentStreamInfo.collectAsStateWithLifecycle()
    val normalizeFactor by playerConnection.service.liveNormalizeFactor.collectAsStateWithLifecycle()
    val sourcesRevision by playerConnection.service.resolvedSourcesRevision.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableStateOf(TrackInfoTab.OVERVIEW) }

    // Playback position / duration poll — the page is live, so the elapsed
    // time ticks rather than freezing at open time.
    var playbackMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            playbackMs = playerConnection.player.currentPosition.coerceAtLeast(0L)
            durationMs = playerConnection.player.duration.takeIf { it > 0L } ?: 0L
            delay(500L)
        }
    }

    val metadata = mediaMetadata ?: return

    val liveStreamInfo = streamInfo?.takeIf { it.mediaId == metadata.id }
    val availableSources = remember(metadata.id, sourcesRevision) {
        runCatching { playerConnection.service.availableSourcesForSong(metadata.id) }.getOrDefault(emptyList())
    }

    val unknown = stringResource(R.string.unknown)
    val isLocal = metadata.id.isLocalMediaId() || metadata.id.isTelegramMediaId()

    // Size formatting needs a Context — resolve it once at composition.
    val sizeText =
        currentFormat?.contentLength?.takeIf { it > 0L }
            ?.let { android.text.format.Formatter.formatShortFileSize(context, it) }

    val codec = currentFormat.codecName()
    val bitrateKbps = currentFormat?.bitrate?.takeIf { it > 0 }?.let { it / 1000 }
    val sampleRateHz = liveStreamInfo?.sampleRate?.takeIf { it > 0 }
        ?: currentFormat?.sampleRate?.takeIf { it > 0 }
    val bitDepth = liveStreamInfo?.bitDepth?.takeIf { it > 0 }
        ?: facts?.decodedBits?.takeIf { it > 0 }
    val channels = facts?.chainChannels ?: facts?.decodedChannels
    val loudnessDb = currentFormat?.loudnessDb

    val qualityTier = remember(codec, bitDepth, sampleRateHz, bitrateKbps) {
        qualityTierLabel(codec, bitDepth, sampleRateHz, bitrateKbps, unknown)
    }

    val providerName = when {
        liveStreamInfo != null -> liveStreamInfo.label
        isLocal -> "Local Library"
        else -> null
    }

    val badges = buildList {
        liveStreamInfo?.let { add(TrackInfoBadge(R.drawable.graphic_eq, "Worked: ${it.label}", highlight = true)) }
        if (isLocal) add(TrackInfoBadge(R.drawable.graphic_eq, "Worked: Local Library", highlight = true))
        if (facts?.usbExclusive == true) {
            val rate = facts?.tryptifyUsbRateHz?.takeIf { it > 0 } ?: sampleRateHz
            add(TrackInfoBadge(R.drawable.bolt, "USB DAC" + (rate?.let { " • ${formatHz(it)}" } ?: "")))
        }
        codec?.let { add(TrackInfoBadge(R.drawable.graphic_eq, it.uppercase(Locale.ROOT))) }
        qualityTier?.let { add(TrackInfoBadge(R.drawable.graphic_eq, it)) }
        bitrateKbps?.let { add(TrackInfoBadge(R.drawable.graphic_eq, "$it kbps")) }
        sampleRateHz?.let { add(TrackInfoBadge(R.drawable.graphic_eq, formatHz(it))) }
        bitDepth?.let { add(TrackInfoBadge(R.drawable.graphic_eq, "$it-bit")) }
        loudnessDb?.let { add(TrackInfoBadge(R.drawable.graphic_eq, "${"%.1f".format(Locale.ROOT, it)} LUFS")) }
    }

    val summaryText = remember(metadata, currentFormat, liveStreamInfo, facts, durationMs, sizeText) {
        buildSummaryText(metadata, currentFormat, liveStreamInfo, facts, durationMs, channels, qualityTier, sizeText)
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.track_info_specs),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(16.dp))

        TrackIdentityCard(metadata)

        Spacer(Modifier.height(14.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            InfoActionChip(
                label = stringResource(R.string.copy_info),
                iconRes = R.drawable.content_copy,
                modifier = Modifier.weight(1f),
            ) {
                copyToClipboard(context, summaryText)
            }
            InfoActionChip(
                label = stringResource(R.string.share),
                iconRes = R.drawable.share,
                modifier = Modifier.weight(1f),
            ) {
                shareText(context, summaryText)
            }
        }

        Spacer(Modifier.height(14.dp))
        if (badges.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
            ) {
                badges.forEach { badge -> BadgePill(badge) }
            }
            Spacer(Modifier.height(14.dp))
        }

        InfoTabRow(selectedTab) { selectedTab = it }
        Spacer(Modifier.height(14.dp))

        when (selectedTab) {
            TrackInfoTab.OVERVIEW -> OverviewTab(
                codec = codec,
                bitrateKbps = bitrateKbps,
                sampleRateHz = sampleRateHz,
                bitDepth = bitDepth,
                channels = channels,
                loudnessDb = loudnessDb,
                qualityTier = qualityTier,
                providerName = providerName,
                engineName = facts?.engineName,
                durationMs = durationMs,
                playbackMs = playbackMs,
                normalizeFactor = normalizeFactor,
                sizeText = sizeText,
            )
            TrackInfoTab.AUDIO_SPECS -> AudioSpecsTab(
                context = context,
                metadata = metadata,
                currentFormat = currentFormat,
                liveStreamInfo = liveStreamInfo,
                isLocal = isLocal,
                facts = facts,
                availableSources = availableSources,
                channels = channels,
                bitDepth = bitDepth,
                sampleRateHz = sampleRateHz,
                bitrateKbps = bitrateKbps,
                loudnessDb = loudnessDb,
                normalizeFactor = normalizeFactor,
                durationMs = durationMs,
                sizeText = sizeText,
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun TrackIdentityCard(metadata: MediaMetadata) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AsyncImage(
                model = metadata.thumbnailUrl,
                contentDescription = null,
                modifier =
                    Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = metadata.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = metadata.artists.joinToString { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun InfoActionChip(
    label: String,
    iconRes: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = onClick,
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun BadgePill(badge: TrackInfoBadge) {
    val container =
        if (badge.highlight) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    Surface(shape = RoundedCornerShape(50), color = container) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(6.dp)
                        .background(
                            if (badge.highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            CircleShape,
                        ),
            )
            Text(
                text = badge.text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (badge.highlight) FontWeight.SemiBold else FontWeight.Medium,
                color =
                    if (badge.highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoTabRow(selected: TrackInfoTab, onSelect: (TrackInfoTab) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            TrackInfoTab.entries.forEach { tab ->
                val selectedHere = tab == selected
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color =
                        if (selectedHere) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        } else {
                            Color.Transparent
                        },
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text =
                            when (tab) {
                                TrackInfoTab.OVERVIEW -> stringResource(R.string.overview)
                                TrackInfoTab.AUDIO_SPECS -> stringResource(R.string.audio_specs)
                            },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selectedHere) FontWeight.Bold else FontWeight.Medium,
                        color =
                            if (selectedHere) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 9.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoSectionCard(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) { content() }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String, isLast: Boolean = false) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 11.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(140.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
        }
        if (!isLast) {
            Box(
                Modifier
                    .padding(horizontal = 18.dp)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

@Composable
private fun OverviewTab(
    codec: String?,
    bitrateKbps: Int?,
    sampleRateHz: Int?,
    bitDepth: Int?,
    channels: Int?,
    loudnessDb: Double?,
    qualityTier: String?,
    providerName: String?,
    engineName: String?,
    durationMs: Long,
    playbackMs: Long,
    normalizeFactor: Float,
    sizeText: String?,
) {
    val unknown = stringResource(R.string.unknown)
    val cells =
        buildList {
            add(TrackInfoRow(stringResource(R.string.ti_codec), codec?.uppercase(Locale.ROOT) ?: unknown))
            add(TrackInfoRow(stringResource(R.string.bitrate), bitrateKbps?.let { "$it kbps" } ?: unknown))
            add(TrackInfoRow(stringResource(R.string.sample_rate), sampleRateHz?.let { formatHz(it) } ?: unknown))
            add(TrackInfoRow(stringResource(R.string.ti_bit_depth), bitDepth?.let { "$it-bit" } ?: unknown))
            add(TrackInfoRow(stringResource(R.string.ti_channels), channelsLabel(channels, unknown)))
            add(TrackInfoRow(stringResource(R.string.loudness), loudnessDb?.let { "${"%.1f".format(Locale.ROOT, it)} LUFS" } ?: unknown))
            add(TrackInfoRow(stringResource(R.string.ti_quality), qualityTier ?: unknown))
            add(TrackInfoRow(stringResource(R.string.ti_worked_provider), providerName ?: unknown))
            add(TrackInfoRow(stringResource(R.string.ti_engine), engineName ?: unknown))
            add(TrackInfoRow(stringResource(R.string.ti_duration), formatDuration(durationMs, playbackMs)))
            add(TrackInfoRow(stringResource(R.string.file_size), sizeText ?: unknown))
        }
    InfoSectionCard(stringResource(R.string.overview)) {
        cells.forEachIndexed { index, row ->
            InfoRow(row.label, row.value, isLast = index == cells.size - 1)
        }
    }
}

@Composable
private fun AudioSpecsTab(
    context: android.content.Context,
    metadata: MediaMetadata,
    currentFormat: FormatEntity?,
    liveStreamInfo: moe.rukamori.archivetune.audiosource.CurrentStreamInfo?,
    isLocal: Boolean,
    facts: TrackInfoViewModel.PipelineFacts?,
    availableSources: List<AudioSourceType>,
    channels: Int?,
    bitDepth: Int?,
    sampleRateHz: Int?,
    bitrateKbps: Int?,
    loudnessDb: Double?,
    normalizeFactor: Float,
    durationMs: Long,
    sizeText: String?,
) {
    val unknown = stringResource(R.string.unknown)
    val codec = currentFormat.codecName()

    // ---- CODEC ------------------------------------------------------------
    val codecDetail =
        when {
            isLocal -> "Local file (direct decode)"
            codec.equals("flac", true) -> "Free Lossless Audio Codec (Bit-Perfect PCM)"
            codec.equals("alac", true) -> "Apple Lossless Audio Codec (Bit-Perfect PCM)"
            codec.equals("opus", true) -> "Opus (IETF low-latency codec)"
            codec.equals("aac", true) -> "Advanced Audio Coding"
            codec.equals("mp3", true) -> "MPEG-1 Audio Layer III"
            codec.equals("vorbis", true) -> "Vorbis"
            codec != null -> "Compressed audio stream"
            else -> null
        }
    val codecProfile =
        when {
            currentFormat?.isLossless() == true -> "Lossless (Bit-Perfect Non-Destructive)"
            codec != null -> "Lossy (Perceptual Coding)"
            else -> null
        }
    val container =
        currentFormat?.mimeType?.substringBefore(";")?.substringAfter("audio/")?.uppercase(Locale.ROOT)
            ?: codec?.uppercase(Locale.ROOT)
    InfoSectionCard(stringResource(R.string.ti_section_codec)) {
        val rows =
            buildList {
                add(TrackInfoRow(stringResource(R.string.ti_codec), codec?.uppercase(Locale.ROOT) ?: unknown))
                codecDetail?.let { add(TrackInfoRow(stringResource(R.string.ti_detail), it)) }
                codecProfile?.let { add(TrackInfoRow(stringResource(R.string.ti_profile), it)) }
                container?.let { add(TrackInfoRow(stringResource(R.string.ti_container), "$it${if (isLocal) " file" else " stream"}")) }
                qualityTierLabel(codec, bitDepth, sampleRateHz, bitrateKbps, null)?.let {
                    add(TrackInfoRow(stringResource(R.string.ti_quality), it))
                }
            }
        rows.forEachIndexed { index, row -> InfoRow(row.label, row.value, isLast = index == rows.size - 1) }
    }

    // ---- SIGNAL -----------------------------------------------------------
    InfoSectionCard(stringResource(R.string.ti_section_signal)) {
        val rows =
            buildList {
                add(TrackInfoRow(stringResource(R.string.ti_bit_depth), bitDepth?.let { "$it-bit${if (bitDepth == 16) " (CD Quality Standard)" else "" }" } ?: unknown))
                add(TrackInfoRow(stringResource(R.string.sample_rate), sampleRateHz?.let { "${formatHz(it)} ($it Hz)" } ?: unknown))
                add(TrackInfoRow(stringResource(R.string.bitrate), bitrateKbps?.let { "$it kbps${if (currentFormat?.isLossless() == true) " (Lossless)" else ""}" } ?: unknown))
                add(TrackInfoRow(stringResource(R.string.ti_channels), channelsLabel(channels, unknown)))
            }
        rows.forEachIndexed { index, row -> InfoRow(row.label, row.value, isLast = index == rows.size - 1) }
    }

    // ---- SOURCE & ACTIVE STREAM -------------------------------------------
    InfoSectionCard(stringResource(R.string.ti_section_source_stream)) {
        val rows =
            buildList {
                add(
                    TrackInfoRow(
                        stringResource(R.string.ti_worked_provider),
                        liveStreamInfo?.label
                            ?: if (isLocal) "Local Library" else unknown,
                    ),
                )
                add(
                    TrackInfoRow(
                        stringResource(R.string.ti_stream_delivery),
                        liveStreamInfo?.label
                            ?: if (isLocal) "On-device file" else unknown,
                    ),
                )
                add(TrackInfoRow(stringResource(R.string.ti_protocol), liveStreamInfo?.protocol ?: if (isLocal) "Local File" else unknown))
                add(TrackInfoRow(stringResource(R.string.media_id), metadata.id))
            }
        rows.forEachIndexed { index, row -> InfoRow(row.label, row.value, isLast = index == rows.size - 1) }
    }

    // ---- REAL PROVIDERS PIPELINE -------------------------------------------
    if (!isLocal) {
        InfoSectionCard(stringResource(R.string.ti_section_providers)) {
            val workedSource = liveStreamInfo?.source
            val providerRows =
                availableSources.map { source ->
                    val worked = workedSource != null && source == workedSource
                    ProviderRow(
                        name = providerDisplayName(source),
                        status = when {
                            worked -> stringResource(R.string.ti_worked)
                            else -> stringResource(R.string.ti_standby)
                        },
                        worked = worked,
                        note = providerNote(source),
                    )
                }
            if (providerRows.isEmpty()) {
                InfoRow(stringResource(R.string.ti_standby), unknown, isLast = true)
            } else {
                providerRows.forEachIndexed { index, row ->
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 11.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = row.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (row.worked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = row.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = row.status,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (row.worked) FontWeight.Bold else FontWeight.Medium,
                                color = if (row.worked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (index < providerRows.size - 1) {
                            Box(
                                Modifier
                                    .padding(horizontal = 18.dp)
                                    .fillMaxWidth()
                                    .height(0.5.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- PLAYBACK -----------------------------------------------------------
    InfoSectionCard(stringResource(R.string.ti_section_playback)) {
        val rows =
            buildList {
                add(
                    TrackInfoRow(
                        stringResource(R.string.loudness),
                        loudnessDb?.let { "${"%.1f".format(Locale.ROOT, it)} LUFS" } ?: unknown,
                    ),
                )
                add(
                    TrackInfoRow(
                        stringResource(R.string.ti_normalization),
                        if (normalizeFactor != 1f) "×${"%.3f".format(Locale.ROOT, normalizeFactor)}" else "Off (unity)",
                    ),
                )
                add(TrackInfoRow(stringResource(R.string.ti_duration), formatDuration(durationMs, 0L)))
                add(TrackInfoRow(stringResource(R.string.file_size), sizeText ?: unknown))
            }
        rows.forEachIndexed { index, row -> InfoRow(row.label, row.value, isLast = index == rows.size - 1) }
    }

    // ---- HARDWARE DAC & OUTPUT ROUTE ----------------------------------------
    InfoSectionCard(stringResource(R.string.ti_section_dac)) {
        val factsHere = facts
        val signalPath =
            when {
                factsHere?.usbExclusive == true -> "USB Exclusive (Direct DAC)"
                EngineRuntime.tryptifyUsbPinActive -> "USB Framework (Pinned Route)"
                else -> "Default Android System Mixer"
            }
        val hwClock =
            factsHere?.tryptifyUsbRateHz?.takeIf { it > 0 }
                ?: factsHere?.halSampleRateHz
                ?: sampleRateHz
        val usbId =
            if (factsHere != null && factsHere.usbVendorId >= 0 && factsHere.usbProductId >= 0) {
                "0x%04X:0x%04X".format(Locale.ROOT, factsHere.usbVendorId, factsHere.usbProductId)
            } else {
                null
            }
        val pipelineState: String
        if (factsHere != null) {
            val inForm =
                if (factsHere.decodedFloat) {
                    "Float PCM"
                } else {
                    "${factsHere.decodedBits ?: 16}-bit Integer PCM"
                }
            val outForm = if (factsHere.outputFloat) "float out" else "16-bit out"
            pipelineState = "$inForm → $outForm"
        } else {
            pipelineState = unknown
        }
        val rows =
            buildList {
                add(
                    TrackInfoRow(
                        stringResource(R.string.ti_dac_device),
                        factsHere?.routedName ?: "Android Audio (Built-in Output)",
                    ),
                )
                add(TrackInfoRow(stringResource(R.string.ti_signal_path), signalPath))
                add(TrackInfoRow(stringResource(R.string.ti_hardware_clock), hwClock?.let { formatHz(it) } ?: unknown))
                add(TrackInfoRow(stringResource(R.string.ti_usb_id), usbId ?: "—"))
                add(TrackInfoRow(stringResource(R.string.ti_pipeline_state), pipelineState))
                add(TrackInfoRow(stringResource(R.string.ti_decoder), factsHere?.decoderName ?: "—"))
                add(TrackInfoRow(stringResource(R.string.ti_engine), factsHere?.engineName ?: "None"))
            }
        rows.forEachIndexed { index, row -> InfoRow(row.label, row.value, isLast = index == rows.size - 1) }
    }
}

private fun FormatEntity?.codecName(): String? =
    this?.codecLabel()?.takeIf { it.isNotBlank() && it != "—" }
        ?: this?.codecs?.takeIf { it.isNotBlank() }?.substringBefore('.')
        ?: this?.mimeType?.substringAfter("audio/", "")?.substringBefore(';')?.takeIf { it.isNotBlank() && it != "mpeg" }

private fun formatHz(hz: Int): String =
    when {
        hz >= 1_000_000 -> "${"%.1f".format(Locale.ROOT, hz / 1_000_000.0)} MHz"
        hz >= 1000 -> {
            val value = hz / 1000.0
            if (value == value.toInt().toDouble()) {
                "${value.toInt()} kHz"
            } else {
                "${"%.1f".format(Locale.ROOT, value)} kHz"
            }
        }
        else -> "$hz Hz"
    }

private fun channelsLabel(channels: Int?, unknown: String): String =
    when (channels) {
        null -> unknown
        1 -> "Mono (1.0 channel)"
        2 -> "Stereo (2.0 channels • Left / Right)"
        else -> "$channels channels"
    }

private fun qualityTierLabel(
    codec: String?,
    bitDepth: Int?,
    sampleRateHz: Int?,
    bitrateKbps: Int?,
    unknown: String?,
): String? =
    when {
        codec.equals("flac", true) || codec.equals("alac", true) ->
            when {
                (bitDepth ?: 16) > 16 || (sampleRateHz ?: 44100) > 48000 -> "Hi-Res Lossless Audio"
                else -> "CD Quality Audio ($bitDepth-bit / ${sampleRateHz?.let { formatHz(it) } ?: "44.1 kHz"} Lossless)"
            }
        (bitrateKbps ?: 0) >= 320 -> "High Quality Audio (320 kbps+)"
        (bitrateKbps ?: 0) > 0 -> "Standard Quality Audio"
        else -> unknown
    }

private fun providerDisplayName(source: AudioSourceType): String =
    when (source) {
        AudioSourceType.TIDAL -> "Tidal (HiFi FLAC)"
        AudioSourceType.QOBUZ -> "Qobuz (Studio FLAC)"
        AudioSourceType.QOBUZ_BACKUP -> "Qobuz Backup (Mirror)"
        AudioSourceType.DEEZER -> "Deezer (Lossless FLAC)"
        AudioSourceType.APPLE -> "Apple Music (Catalogue)"
        AudioSourceType.JIOSAAVN -> "JioSaavn (AAC)"
        AudioSourceType.YOUTUBE -> "YouTube Music (Standard)"
    }

private fun providerNote(source: AudioSourceType): String =
    when (source) {
        AudioSourceType.TIDAL -> "Direct API • SourcePool"
        AudioSourceType.QOBUZ -> "SourcePool • Akamai CDN"
        AudioSourceType.QOBUZ_BACKUP -> "Secondary mirror • endpoint chain"
        AudioSourceType.DEEZER -> "Lossless resolver"
        AudioSourceType.APPLE -> "Metadata & lyrics tier"
        AudioSourceType.JIOSAAVN -> "AAC resolver"
        AudioSourceType.YOUTUBE -> "Ultimate zero-skip fallback"
    }

private fun formatDuration(durationMs: Long, playbackMs: Long): String {
    val total = if (durationMs > 0) durationMs else playbackMs
    if (total <= 0) return "0 min 00s"
    val minutes = total / 60000L
    val seconds = (total % 60000L) / 1000L
    return "$minutes min ${"%02d".format(Locale.ROOT, seconds)}s"
}

private fun buildSummaryText(
    metadata: MediaMetadata,
    format: FormatEntity?,
    streamInfo: moe.rukamori.archivetune.audiosource.CurrentStreamInfo?,
    facts: TrackInfoViewModel.PipelineFacts?,
    durationMs: Long,
    channels: Int?,
    qualityTier: String?,
    sizeText: String?,
): String = buildString {
    appendLine("ArchiveTune — Track Info & Specs")
    appendLine()
    appendLine("Title: ${metadata.title}")
    appendLine("Artist: ${metadata.artists.joinToString { it.name }}")
    metadata.album?.title?.let { appendLine("Album: $it") }
    appendLine("ID: ${metadata.id}")
    appendLine()
    appendLine("Codec: ${format.codecName()?.uppercase(Locale.ROOT) ?: "Unknown"}")
    format?.bitrate?.takeIf { it > 0 }?.let { appendLine("Bitrate: ${it / 1000} kbps") }
    format?.sampleRate?.takeIf { it > 0 }?.let { appendLine("Sample rate: $it Hz") }
    facts?.decodedBits?.takeIf { it > 0 }?.let { appendLine("Bit depth: $it-bit") }
    appendLine("Channels: ${channels ?: "?"}")
    qualityTier?.let { appendLine("Quality: $it") }
    format?.loudnessDb?.let { appendLine("Loudness: ${"%.1f".format(Locale.ROOT, it)} LUFS") }
    appendLine()
    streamInfo?.let {
        appendLine("Source: ${it.label}")
        appendLine("Protocol: ${it.protocol ?: "—"}")
    }
    facts?.let {
        appendLine("Engine: ${it.engineName}")
        appendLine("Decoder: ${it.decoderName ?: "—"}")
        appendLine("Signal path: ${if (it.usbExclusive) "USB exclusive" else "System mixer"}")
    }
    if (durationMs > 0) {
        appendLine("Duration: ${formatDuration(durationMs, 0L)}")
    }
    sizeText?.let { appendLine("Size: $it") }
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    runCatching {
        val clipboard =
            context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Track Info", text))
    }
}

private fun shareText(context: android.content.Context, text: String) {
    runCatching {
        val intent =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(intent, null))
    }
}
