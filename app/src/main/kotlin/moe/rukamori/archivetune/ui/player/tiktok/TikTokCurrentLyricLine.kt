/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player.tiktok

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.TikTokLyricsSecondary
import moe.rukamori.archivetune.constants.TikTokMainLyricsEnabledKey
import moe.rukamori.archivetune.constants.TikTokMainLyricsSecondaryKey
import moe.rukamori.archivetune.lyrics.LyricsEntry
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.ui.player.rememberInlineLyricLines
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference

/**
 * Single-line "now singing" lyrics for the TikTok player's main screen: the current
 * active line only, with a word-timed karaoke sweep and an animated line-to-line
 * transition, plus one mutually exclusive secondary line (translation or
 * romanisation) chosen in Appearance settings.
 */
@Composable
internal fun TikTokCurrentLyricLine(
    playerConnection: PlayerConnection,
    positionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    modifier: Modifier = Modifier,
) {
    val enabled by rememberPreference(TikTokMainLyricsEnabledKey, false)
    if (!enabled) return

    val secondaryMode by rememberEnumPreference(TikTokMainLyricsSecondaryKey, TikTokLyricsSecondary.TRANSLATION)

    val lines = rememberInlineLyricLines(playerConnection)
    if (lines.isEmpty()) return

    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lines) {
        while (isActive) {
            val raw = positionProvider()
            positionMs = ((raw ?: 0L) - lyricsSyncOffset).coerceAtLeast(0L)
            withFrameNanos { }
        }
    }

    val currentIndex = remember(lines, positionMs) {
        LyricsUtils.findCurrentLineIndex(lines, positionMs, leadMs = 120L)
    }
    val entry = lines.getOrNull(currentIndex) ?: return

    val primaryStyle =
        MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    val secondaryStyle =
        MaterialTheme.typography.bodyMedium.copy(
            color = Color.White.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
        )

    AnimatedContent(
        targetState = currentIndex,
        transitionSpec = {
            (slideInVertically(tween(260)) { it / 3 } + fadeIn(tween(260))) togetherWith
                (slideOutVertically(tween(180)) { -it / 3 } + fadeOut(tween(180)))
        },
        contentAlignment = Alignment.Center,
        label = "tiktokCurrentLyricLine",
        modifier = modifier,
    ) { _ ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            KaraokeSweptText(
                entry = entry,
                positionMs = positionMs,
                style = primaryStyle,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            val secondaryText = secondaryLineFor(entry, secondaryMode)
            if (!secondaryText.isNullOrBlank()) {
                BasicText(
                    text = secondaryText,
                    style = secondaryStyle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun KaraokeSweptText(
    entry: LyricsEntry,
    positionMs: Long,
    style: TextStyle,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    if (entry.isInstrumental || entry.text.isBlank()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.music_note),
                contentDescription = null,
                tint = style.color.copy(alpha = 0.7f),
            )
        }
        return
    }

    var layout by remember(entry.text) { mutableStateOf<TextLayoutResult?>(null) }
    val revealFraction = remember(entry, positionMs) { karaokeRevealFraction(entry, positionMs) }

    Box(modifier = modifier) {
        BasicText(
            text = entry.text,
            style = style.copy(color = style.color.copy(alpha = 0.4f)),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth(),
        )
        BasicText(
            text = entry.text,
            style = style,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .drawWithContent {
                        val measured = layout ?: return@drawWithContent
                        sweepClip(measured, revealFraction)
                    },
        )
    }
}

private fun secondaryLineFor(
    entry: LyricsEntry,
    mode: TikTokLyricsSecondary,
): String? =
    when (mode) {
        TikTokLyricsSecondary.TRANSLATION -> entry.providerTranslationText
        TikTokLyricsSecondary.ROMANIZATION -> entry.providerRomanizedText
    }?.trim()?.takeIf { it.isNotBlank() }

private fun karaokeRevealFraction(
    entry: LyricsEntry,
    positionMs: Long,
): Float {
    val words = entry.words
    if (!words.isNullOrEmpty()) {
        val positionSec = positionMs / 1000.0
        var revealed = 0.0
        var total = 0.0
        for (word in words) {
            if (word.isBackground) continue
            val length = word.text.length.toDouble()
            total += length
            when {
                positionSec >= word.endTime -> revealed += length
                positionSec > word.startTime -> {
                    val span = (word.endTime - word.startTime).coerceAtLeast(0.001)
                    revealed += length * ((positionSec - word.startTime) / span)
                }
                else -> Unit
            }
        }
        if (total > 0.0) return (revealed / total).toFloat().coerceIn(0f, 1f)
    }
    val duration = entry.durationMs.takeIf { it > 0L }
        ?: return if (positionMs >= entry.time) 1f else 0f
    return ((positionMs - entry.time).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
}

private fun ContentDrawScope.sweepClip(
    layout: TextLayoutResult,
    fraction: Float,
) {
    val length = layout.layoutInput.text.length
    when {
        length == 0 -> Unit
        fraction >= 1f -> drawContent()
        fraction <= 0f -> Unit
        else -> {
            val edge = fraction * length
            for (visualLine in 0 until layout.lineCount) {
                val start = layout.getLineStart(visualLine)
                if (edge <= start) break
                val end = layout.getLineEnd(visualLine, visibleEnd = true)
                val right =
                    if (edge >= end) {
                        layout.getLineRight(visualLine)
                    } else {
                        layout.getHorizontalPosition(edge.toInt().coerceIn(0, end), usePrimaryDirection = true)
                    }
                clipRect(
                    left = layout.getLineLeft(visualLine),
                    top = layout.getLineTop(visualLine),
                    right = right,
                    bottom = layout.getLineBottom(visualLine),
                ) {
                    this@sweepClip.drawContent()
                }
            }
        }
    }
}
