/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package moe.rukamori.archivetune.ui.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.component.LocalBottomSheetPageState
import moe.rukamori.archivetune.utils.AudioOutputStats
import moe.rukamori.archivetune.utils.AudioOutputStatsProvider
import android.text.format.Formatter

private enum class MediaInfoTab(
    val labelRes: Int,
    val iconRes: Int,
) {
    Information(R.string.information, R.drawable.solar_info),
    Details(R.string.details, R.drawable.solar_ruler),
    Numbers(R.string.numbers, R.drawable.solar_hertz),
}

private data class MediaInfoQuickFact(
    val iconRes: Int,
    val text: String,
)

private data class MediaInfoDetail(
    val iconRes: Int,
    val label: String,
    val value: String,
    val multiline: Boolean = false,
)

private data class MediaInfoMetric(
    val iconRes: Int,
    val labelRes: Int,
    val value: String,
)

/** Spring used for the expressive tab morph + content swaps. */
private val ExpressiveSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

@Composable
fun ShowMediaInfo(videoId: String) {
    if (videoId.isBlank()) return

    val context = LocalContext.current
    val database = LocalDatabase.current
    val bottomSheetPageState = LocalBottomSheetPageState.current
    val playerConnection = LocalPlayerConnection.current
    val song by database.song(videoId).collectAsStateWithLifecycle(initialValue = null)
    val currentFormat by database.format(videoId).collectAsStateWithLifecycle(initialValue = null)
    val info = rememberMediaInfo(videoId)
    var selectedTab by rememberSaveable(videoId) { mutableStateOf(MediaInfoTab.Information) }
    var outputStats by remember(videoId) { mutableStateOf<AudioOutputStats?>(null) }

    val unknownText = stringResource(R.string.unknown)
    val pleaseWaitText = stringResource(R.string.please_wait)
    val copyText = stringResource(R.string.copy)
    val shareText = stringResource(R.string.share)
    val closeText = stringResource(R.string.close)
    val songTitleLabel = stringResource(R.string.song_title)
    val songArtistsLabel = stringResource(R.string.song_artists)
    val mediaIdLabel = stringResource(R.string.media_id)
    val mimeTypeLabel = stringResource(R.string.mime_type)
    val codecsLabel = stringResource(R.string.codecs)
    val bitrateLabel = stringResource(R.string.bitrate)
    val sampleRateLabel = stringResource(R.string.sample_rate)
    val loudnessLabel = stringResource(R.string.loudness)
    val volumeLabel = stringResource(R.string.volume)
    val fileSizeLabel = stringResource(R.string.file_size)
    val descriptionLabel = stringResource(R.string.description)

    val mediaUrl = remember(videoId) { "https://music.youtube.com/watch?v=$videoId" }

    // rememberMediaInfo already runs the YouTube.getMediaInfo load internally
    // (see MediaInfoLoader.kt); here we only refresh the audio-route snapshot.
    LaunchedEffect(videoId) {
        outputStats = AudioOutputStatsProvider.resolve(context)
    }

    val heroTitle = song?.title ?: info?.title ?: videoId
    val heroSubtitle =
        song
            ?.artists
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString { it.name }
            ?: info?.author
            ?: unknownText
    val artworkModel = song?.thumbnailUrl ?: info?.authorThumbnail
    val playbackVolume = playerConnection?.let { "${(it.player.volume * 100).toInt()}%" }

    val overviewDetails =
        buildList {
            add(
                MediaInfoDetail(
                    iconRes = R.drawable.solar_music_note,
                    label = songTitleLabel,
                    value = song?.title ?: info?.title ?: unknownText,
                ),
            )
            add(
                MediaInfoDetail(
                    iconRes = R.drawable.solar_users,
                    label = songArtistsLabel,
                    value =
                        song
                            ?.artists
                            ?.takeIf { it.isNotEmpty() }
                            ?.joinToString { it.name }
                            ?: info?.author
                            ?: unknownText,
                ),
            )
            add(
                MediaInfoDetail(
                    iconRes = R.drawable.solar_hash,
                    label = mediaIdLabel,
                    value = videoId,
                ),
            )
        }

    val technicalDetails =
        buildList {
            currentFormat?.itag?.takeIf { it > 0 }?.toString()?.let {
                add(MediaInfoDetail(iconRes = R.drawable.solar_ruler, label = "Itag", value = it))
            }
            currentFormat
                ?.mimeType
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    add(MediaInfoDetail(iconRes = R.drawable.solar_database, label = mimeTypeLabel, value = it))
                }
            currentFormat
                ?.codecs
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    add(MediaInfoDetail(iconRes = R.drawable.solar_code, label = codecsLabel, value = it))
                }
            currentFormat
                ?.bitrate
                ?.takeIf { it > 0 }
                ?.let {
                    add(MediaInfoDetail(iconRes = R.drawable.solar_speed, label = bitrateLabel, value = "${it / 1000} Kbps"))
                }
            currentFormat
                ?.sampleRate
                ?.takeIf { it > 0 }
                ?.let {
                    add(MediaInfoDetail(iconRes = R.drawable.solar_hertz, label = sampleRateLabel, value = "$it Hz"))
                }
            currentFormat?.loudnessDb?.let {
                add(MediaInfoDetail(iconRes = R.drawable.solar_volume, label = loudnessLabel, value = "$it dB"))
            }
            playbackVolume?.let {
                add(MediaInfoDetail(iconRes = R.drawable.solar_headphones, label = volumeLabel, value = it))
            }
            currentFormat
                ?.contentLength
                ?.takeIf { it > 0 }
                ?.let {
                    add(
                        MediaInfoDetail(
                            iconRes = R.drawable.solar_database,
                            label = fileSizeLabel,
                            value = Formatter.formatShortFileSize(context, it),
                        ),
                    )
                }
        }

    val quickFacts =
        buildList {
            currentFormat
                ?.mimeType
                ?.substringBefore(';')
                ?.takeIf { it.isNotBlank() }
                ?.let { add(MediaInfoQuickFact(iconRes = R.drawable.solar_wave, text = it)) }
            currentFormat
                ?.bitrate
                ?.takeIf { it > 0 }
                ?.let { add(MediaInfoQuickFact(iconRes = R.drawable.solar_speed, text = "${it / 1000} Kbps")) }
            currentFormat
                ?.sampleRate
                ?.takeIf { it > 0 }
                ?.let { add(MediaInfoQuickFact(iconRes = R.drawable.solar_hertz, text = "${it / 1000.0} kHz")) }
            currentFormat
                ?.contentLength
                ?.takeIf { it > 0 }
                ?.let {
                    add(
                        MediaInfoQuickFact(
                            iconRes = R.drawable.solar_database,
                            text = Formatter.formatShortFileSize(context, it),
                        ),
                    )
                }
            info
                ?.subscribers
                ?.takeIf { it.isNotBlank() }
                ?.let { add(MediaInfoQuickFact(iconRes = R.drawable.solar_users, text = it)) }
        }

    val metrics =
        if (info != null) {
            listOf(
                MediaInfoMetric(R.drawable.solar_users, R.string.subscribers, info?.subscribers ?: unknownText),
                MediaInfoMetric(R.drawable.solar_eye, R.string.views, info?.viewCount?.let(::numberFormatter) ?: unknownText),
                MediaInfoMetric(R.drawable.solar_heart, R.string.likes, info?.like?.let(::numberFormatter) ?: unknownText),
                MediaInfoMetric(R.drawable.solar_dislike, R.string.dislikes, info?.dislike?.let(::numberFormatter) ?: unknownText),
            )
        } else {
            emptyList()
        }

    // Staggered entrance — the sheet content pops in with expressive springs.
    var entered by remember(videoId) { mutableStateOf(false) }
    LaunchedEffect(videoId) {
        entered = false
        kotlinx.coroutines.delay(80)
        entered = true
    }

    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(contentType = "Hero") {
            MediaInfoExpressiveEntrance(entered = entered, index = 0) {
                MediaInfoExpressiveHero(
                    title = heroTitle,
                    subtitle = heroSubtitle,
                    artworkModel = artworkModel,
                    isLoading = info == null,
                    loadingText = pleaseWaitText,
                    closeText = closeText,
                    onCopy = { copyToClipboard(context, videoId) },
                    onShare = { shareMediaLink(context, mediaUrl) },
                    copyText = copyText,
                    shareText = shareText,
                    onClose = bottomSheetPageState::dismiss,
                )
            }
        }

        if (quickFacts.isNotEmpty()) {
            item(contentType = "QuickFacts") {
                MediaInfoExpressiveEntrance(entered = entered, index = 1) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        quickFacts.forEach { fact ->
                            MediaInfoQuickPill(
                                iconRes = fact.iconRes,
                                text = fact.text,
                                onClick = { copyToClipboard(context, fact.text) },
                            )
                        }
                    }
                }
            }
        }

        item(contentType = "Tabs") {
            MediaInfoExpressiveEntrance(entered = entered, index = 2) {
                MediaInfoExpressiveTabs(
                    selectedTab = selectedTab,
                    onSelect = { selectedTab = it },
                )
            }
        }

        item(contentType = "SelectedContent") {
            MediaInfoExpressiveEntrance(entered = entered, index = 3) {
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        (slideInVertically(
                            animationSpec = tween(220),
                            initialOffsetY = { it / 6 },
                        ) + fadeIn(tween(220))) togetherWith
                            (slideOutVertically(
                                animationSpec = tween(160),
                                targetOffsetY = { -it / 8 },
                            ) + fadeOut(tween(160)))
                    },
                    label = "mediaInfoTab",
                ) { tab ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        when (tab) {
                            MediaInfoTab.Information -> {
                                MediaInfoExpressiveCard {
                                    overviewDetails.forEachIndexed { index, item ->
                                        MediaInfoExpressiveRow(
                                            iconRes = item.iconRes,
                                            label = item.label,
                                            value = item.value,
                                            showDivider = index != overviewDetails.lastIndex,
                                            onClick = { copyToClipboard(context, item.value) },
                                        )
                                    }
                                }

                                if (info == null) {
                                    MediaInfoExpressivePending(
                                        iconRes = R.drawable.solar_text,
                                        title = descriptionLabel,
                                        message = pleaseWaitText,
                                    )
                                } else {
                                    MediaInfoNarrativeCard(
                                        iconRes = R.drawable.solar_text,
                                        title = descriptionLabel,
                                        body = info?.description?.takeIf { it.isNotBlank() } ?: unknownText,
                                        onCopy = {
                                            info
                                                ?.description
                                                ?.takeIf { value -> value.isNotBlank() }
                                                ?.let { copyToClipboard(context, it) }
                                        },
                                    )
                                }
                            }

                            MediaInfoTab.Details -> {
                                if (technicalDetails.isEmpty()) {
                                    MediaInfoExpressivePending(
                                        iconRes = R.drawable.solar_ruler,
                                        title = stringResource(R.string.details),
                                        message = pleaseWaitText,
                                    )
                                } else {
                                    MediaInfoExpressiveCard {
                                        technicalDetails.forEachIndexed { index, item ->
                                            MediaInfoExpressiveRow(
                                                iconRes = item.iconRes,
                                                label = item.label,
                                                value = item.value,
                                                showDivider = index != technicalDetails.lastIndex,
                                                onClick = { copyToClipboard(context, item.value) },
                                            )
                                        }
                                    }
                                }
                                MediaInfoOutputCard(
                                    outputStats = outputStats,
                                    sourceSampleRate = currentFormat?.sampleRate,
                                )
                            }

                            MediaInfoTab.Numbers -> {
                                if (metrics.isEmpty()) {
                                    MediaInfoExpressivePending(
                                        iconRes = R.drawable.solar_hertz,
                                        title = stringResource(R.string.numbers),
                                        message = pleaseWaitText,
                                    )
                                } else {
                                    metrics.chunked(2).forEach { rowMetrics ->
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            rowMetrics.forEach { metric ->
                                                MediaInfoExpressiveMetric(
                                                    iconRes = metric.iconRes,
                                                    labelRes = metric.labelRes,
                                                    value = metric.value,
                                                    modifier = Modifier.weight(1f),
                                                )
                                            }
                                            if (rowMetrics.size == 1) {
                                                Spacer(modifier = Modifier.weight(1f))
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
    }
}

/** Wraps a section with the staggered expressive pop-in. */
@Composable
private fun MediaInfoExpressiveEntrance(
    entered: Boolean,
    index: Int,
    content: @Composable () -> Unit,
) {
    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
            visibilityThreshold = 0.01f,
        ),
        label = "entrance_$index",
    )
    Box(
        modifier =
            Modifier
                .graphicsLayer {
                    val p = progress.coerceIn(0f, 1f)
                    alpha = p
                    scaleX = 0.92f + 0.08f * p
                    scaleY = 0.92f + 0.08f * p
                    translationY = (1f - p) * 18f
                },
    ) {
        content()
    }
}

@Composable
private fun MediaInfoExpressiveHero(
    title: String,
    subtitle: String,
    artworkModel: String?,
    isLoading: Boolean,
    loadingText: String,
    closeText: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    copyText: String,
    shareText: String,
    onClose: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(96.dp),
            ) {
                if (artworkModel != null) {
                    AsyncImage(
                        model = artworkModel,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(24.dp)),
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.solar_music_note),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f),
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = stringResource(R.string.media_info_title).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(),
                )
                if (isLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LoadingIndicator(
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = loadingText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            FilledTonalIconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.solar_close_circle_linear),
                    contentDescription = closeText,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilledTonalButton(
                onClick = onCopy,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    painter = painterResource(R.drawable.solar_copy),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = copyText)
            }

            OutlinedButton(
                onClick = onShare,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    painter = painterResource(R.drawable.solar_share_linear),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = shareText)
            }
        }
    }
}

@Composable
private fun MediaInfoQuickPill(
    iconRes: Int,
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

/** Material 3 Expressive segmented tabs with a springy morphing selection pill. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaInfoExpressiveTabs(
    selectedTab: MediaInfoTab,
    onSelect: (MediaInfoTab) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
        ) {
            MediaInfoTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                val selectionScale by animateFloatAsState(
                    targetValue = if (selected) 1f else 0.94f,
                    animationSpec = ExpressiveSpring,
                    label = "tabScale_${tab.name}",
                )
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color =
                        if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        },
                    modifier =
                        Modifier
                            .weight(1f)
                            .graphicsLayer {
                                scaleX = selectionScale
                                scaleY = selectionScale
                            },
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .clickable { onSelect(tab) }
                                .padding(vertical = 10.dp),
                    ) {
                        AnimatedVisibility(visible = selected) {
                            Row {
                                Icon(
                                    painter = painterResource(tab.iconRes),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                        }
                        Text(
                            text = stringResource(tab.labelRes),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color =
                                if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaInfoExpressiveCard(
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
private fun MediaInfoExpressiveRow(
    iconRes: Int,
    label: String,
    value: String,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                modifier = Modifier.size(34.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(1.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                painter = painterResource(R.drawable.solar_copy),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp),
            )
        }
        if (showDivider) {
            Box(
                modifier =
                    Modifier
                        .padding(start = 64.dp)
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .alpha(0.5f)
                        .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
private fun MediaInfoNarrativeCard(
    iconRes: Int,
    title: String,
    body: String,
    onCopy: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalIconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.solar_copy),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Explains the real output route: which device is active, which sample rates it
 * advertises, what Android mixes at, and whether the playing source gets
 * resampled (and by whom — the OS resampler, not the app).
 */
@Composable
private fun MediaInfoOutputCard(
    outputStats: AudioOutputStats?,
    sourceSampleRate: Int?,
) {
    val stats = outputStats ?: return
    if (!stats.hasData) return

    val unknownText = stringResource(R.string.unknown)
    val conversionNote =
        stats.conversionDescription(sourceSampleRate)
            ?: stringResource(R.string.output_no_conversion)

    val details =
        buildList {
            add(
                MediaInfoDetail(
                    iconRes = R.drawable.solar_headphones,
                    label = stringResource(R.string.output_device),
                    value = "${stats.deviceLabel} · ${stats.deviceTypeLabel}",
                ),
            )
            if (stats.deviceSampleRates.isNotEmpty()) {
                add(
                    MediaInfoDetail(
                        iconRes = R.drawable.solar_hertz,
                        label = stringResource(R.string.output_device_rates),
                        value = stats.deviceSampleRates.joinToString(" / ") { "$it Hz" },
                    ),
                )
            }
            if (stats.mixSampleRate > 0) {
                add(
                    MediaInfoDetail(
                        iconRes = R.drawable.solar_wave,
                        label = stringResource(R.string.output_mix_rate),
                        value = "${stats.mixSampleRate} Hz",
                    ),
                )
            }
            add(
                MediaInfoDetail(
                    iconRes = R.drawable.solar_speed,
                    label = stringResource(R.string.output_conversion),
                    value = conversionNote,
                    multiline = true,
                ),
            )
        }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            details.forEachIndexed { index, item ->
                MediaInfoExpressiveRow(
                    iconRes = item.iconRes,
                    label = item.label,
                    value = item.value,
                    showDivider = index != details.lastIndex,
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun MediaInfoExpressiveMetric(
    iconRes: Int,
    labelRes: Int,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MediaInfoExpressivePending(
    iconRes: Int,
    title: String,
    message: String,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
        ) {
            LoadingIndicator(modifier = Modifier.size(36.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun copyToClipboard(
    context: Context,
    value: String,
) {
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboardManager.setPrimaryClip(ClipData.newPlainText("text", value))
    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}

private fun shareMediaLink(
    context: Context,
    mediaUrl: String,
) {
    val shareIntent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, mediaUrl)
        }
    context.startActivity(Intent.createChooser(shareIntent, null))
}
