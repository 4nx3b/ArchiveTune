/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * SimpMusic's lyrics view.
 *
 * The look is SimpMusic's Classic renderer (its LyricsView / LyricsLineItem / RichSyncLyricsLineItem,
 * https://github.com/maxrave-dev/SimpMusic, GPL-3.0): left-aligned, one line per row, everything a
 * dim grey except the line being sung, which steps up a type size and goes white. A word-timed line
 * lights word by word as it is sung, the rest of the line waiting behind it.
 *
 * Only the SimpMusic player style can reach this, and only while the user has turned it on — every
 * other surface in the app follows LyricsModeKey.
 *
 * REWRITTEN rather than transliterated, and deliberately much smaller than either shared renderer:
 * it shows lyrics, follows the song, and seeks on tap. Romanisation, AI translation, per-word blur
 * and the karaoke fill all belong to Enhanced and V2 — reproducing them here would be a third copy
 * of machinery that already exists twice, and none of it is what makes this view look like
 * SimpMusic. The one thing it does share is the parse: LyricsUtils, the same functions both other
 * renderers call, so a format either works in all three or in none.
 */

package moe.rukamori.archivetune.ui.player.simpmusic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.constants.LyricsClickKey
import moe.rukamori.archivetune.constants.LyricsTextSizeKey
import moe.rukamori.archivetune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import moe.rukamori.archivetune.lyrics.AiLyricsRomanization
import moe.rukamori.archivetune.lyrics.LyricsEntry
import moe.rukamori.archivetune.lyrics.LyricsEntry.Companion.HEAD_LYRICS_ENTRY
import moe.rukamori.archivetune.lyrics.LyricsUtils.findCurrentLineIndex
import moe.rukamori.archivetune.lyrics.LyricsUtils.hasTrueWordSync
import moe.rukamori.archivetune.lyrics.LyricsUtils.insertInstrumentalBreaks
import moe.rukamori.archivetune.lyrics.LyricsUtils.isLineSyncedLrc
import moe.rukamori.archivetune.lyrics.LyricsUtils.isTtml
import moe.rukamori.archivetune.lyrics.LyricsUtils.parseLyrics
import moe.rukamori.archivetune.lyrics.LyricsUtils.parseTtml
import moe.rukamori.archivetune.utils.rememberPreference

private val DimLine = Color.LightGray.copy(alpha = 0.35f)

private val PendingWord = Color.LightGray.copy(alpha = 0.6f)

private const val LRC_LEAD_MS = 300L
private const val TTML_LEAD_MS = 0L
private const val VISUAL_TUNING_OFFSET_MS = 150L

private const val MANUAL_SCROLL_HOLD_MS = 4_000L

@Composable
fun SimpMusicLyrics(
    sliderPositionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    modifier: Modifier = Modifier,
    textColorOverride: Color? = null,

    textSizeSp: Float? = null,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val player = playerConnection.player
    val currentColor = textColorOverride ?: Color.White

    val (lyricsClick) = rememberPreference(LyricsClickKey, defaultValue = true)
    val (lyricsTextSizePreference) = rememberPreference(LyricsTextSizeKey, defaultValue = 26f)
    val lyricsTextSize = textSizeSp ?: lyricsTextSizePreference

    val currentLyrics by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    val lyrics = currentLyrics?.lyrics

    val isSynced = remember(lyrics) { lyrics != null && (isLineSyncedLrc(lyrics) || isTtml(lyrics)) }
    val isTtmlFormat = remember(lyrics) { lyrics != null && isTtml(lyrics) }

    var parsed by remember(lyrics) { mutableStateOf<List<LyricsEntry>?>(null) }
    LaunchedEffect(lyrics) {
        val text = lyrics
        if (text == null || text == LYRICS_NOT_FOUND) {
            parsed = emptyList()
            return@LaunchedEffect
        }
        val durationMs = player.duration.takeIf { it > 0L } ?: 0L
        parsed =
            withContext(Dispatchers.Default) {

                fun plainLines(): List<LyricsEntry> =
                    text
                        .lines()
                        .filter { it.isNotBlank() }
                        .map { LyricsEntry(time = -1L, text = it.trim()) }

                val lines: List<LyricsEntry> =
                    when {
                        isTtml(text) -> runCatching { parseTtml(text) }.getOrDefault(emptyList())
                        isLineSyncedLrc(text) ->
                            runCatching { insertInstrumentalBreaks(parseLyrics(text), durationMs) }
                                .getOrDefault(emptyList())
                                .ifEmpty { plainLines() }
                        else -> plainLines()
                    }

                if (lines.isNotEmpty() && lines.first().time >= 0L) {
                    listOf(HEAD_LYRICS_ENTRY) + lines
                } else {
                    lines
                }
            }
    }
    val entries = parsed.orEmpty()

    val aiRomanizationSettings = AiLyricsRomanization.rememberSettings()
    val aiRomanizationSessionKey =
        remember(lyrics) {
            AiLyricsRomanization.sessionKey(playerConnection.mediaMetadata.value?.id, lyrics)
        }
    val aiRomanizationResult by AiLyricsRomanization.results.collectAsStateWithLifecycle()
    val romanizedLines: List<String?> =
        remember(aiRomanizationResult, aiRomanizationSessionKey, aiRomanizationSettings.active, entries) {
            if (!aiRomanizationSettings.active) {
                emptyList()
            } else {
                AiLyricsRomanization.linesFor(aiRomanizationSessionKey, entries.map { it.text })
            }
        }
    LaunchedEffect(aiRomanizationSessionKey, entries, aiRomanizationSettings) {
        if (!aiRomanizationSettings.active || !aiRomanizationSettings.auto) return@LaunchedEffect
        if (entries.isEmpty()) return@LaunchedEffect
        AiLyricsRomanization.request(
            sessionKey = aiRomanizationSessionKey,
            lines = entries.map { it.text },
            settings = aiRomanizationSettings,
        )
    }

    val positionState = remember(lyrics) { mutableLongStateOf(0L) }
    val positionProvider: () -> Long = remember { { positionState.longValue } }
    var currentLineIndex by remember(lyrics) { mutableIntStateOf(-1) }
    val latestSliderPositionProvider = rememberUpdatedState(sliderPositionProvider)

    LaunchedEffect(entries, isSynced, isTtmlFormat, lyricsSyncOffset) {
        if (!isSynced || entries.isEmpty()) return@LaunchedEffect
        val leadMs = if (isTtmlFormat) TTML_LEAD_MS else LRC_LEAD_MS

        val pollMs = if (isTtmlFormat) 16L else 50L
        while (isActive) {
            val raw = latestSliderPositionProvider.value() ?: player.currentPosition
            val shifted = (raw + lyricsSyncOffset.toLong()).coerceAtLeast(0L)
            positionState.longValue = (shifted + leadMs + VISUAL_TUNING_OFFSET_MS).coerceAtLeast(0L)
            currentLineIndex = findCurrentLineIndex(entries, positionState.longValue, 0L)
            delay(pollMs)
        }
    }

    val listState = rememberLazyListState()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var manualUntilMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(dragged) {
        if (dragged) {
            manualUntilMs = Long.MAX_VALUE
        } else if (manualUntilMs == Long.MAX_VALUE) {

            manualUntilMs = System.currentTimeMillis() + MANUAL_SCROLL_HOLD_MS
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {

        val topPad = maxHeight * 0.32f
        val bottomPad = maxHeight * 0.5f

        var placed by remember(entries) { mutableStateOf(false) }
        LaunchedEffect(currentLineIndex, entries.size) {
            if (currentLineIndex !in entries.indices) return@LaunchedEffect
            if (System.currentTimeMillis() < manualUntilMs) return@LaunchedEffect
            if (placed) {
                listState.animateScrollToItem(currentLineIndex)
            } else {

                listState.scrollToItem(currentLineIndex)
                placed = true
            }
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = topPad, bottom = bottomPad, start = 24.dp, end = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(entries) { index, entry ->
                SimpMusicLyricsLine(
                    entry = entry,
                    isCurrent = index == currentLineIndex,
                    baseSizeSp = lyricsTextSize,
                    currentColor = currentColor,
                    positionProvider = positionProvider,
                    romanizedText = romanizedLines.getOrNull(index)?.takeIf { it.isNotBlank() },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = lyricsClick && entry.time >= 0L) {
                                player.seekTo(entry.time)
                            }.padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SimpMusicLyricsLine(
    entry: LyricsEntry,
    isCurrent: Boolean,
    baseSizeSp: Float,
    currentColor: Color,
    positionProvider: () -> Long,
    romanizedText: String? = null,
    modifier: Modifier = Modifier,
) {
    val text = if (entry.isInstrumental) "♪" else entry.text
    if (text.isBlank()) return

    val style =
        MaterialTheme.typography.headlineMedium.copy(
            fontSize = (if (isCurrent) baseSizeSp else baseSizeSp * 0.82f).sp,
            lineHeight = (if (isCurrent) baseSizeSp else baseSizeSp * 0.82f).sp * 1.25f,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
        )

    val romanization: (@Composable () -> Unit)? =
        romanizedText?.takeIf { it.isNotBlank() && it != text }?.let { romanized ->
            {
                Text(
                    text = romanized,
                    style =
                        MaterialTheme.typography.bodyMedium.copy(
                            fontSize = (baseSizeSp * 0.55f).sp,
                            fontWeight = FontWeight.Normal,
                        ),
                    color = if (isCurrent) currentColor.copy(alpha = 0.65f) else Color.LightGray.copy(alpha = 0.28f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

    val wordSynced = remember(entry) { hasTrueWordSync(entry) }
    if (!isCurrent || !wordSynced) {
        Column(modifier = modifier) {
            Text(
                text = text,
                style = style,
                color = if (isCurrent) currentColor else DimLine,
            )
            romanization?.invoke()
        }
        return
    }

    val words = remember(entry) { entry.words.orEmpty().filter { it.text.isNotBlank() } }

    val sungThrough by remember(words) {
        derivedStateOf {
            val now = positionProvider()
            words.indexOfLast { (it.startTime * 1000.0).toLong() <= now }
        }
    }

    Column(modifier = modifier) {
        FlowRowWords(
            words = words.map { it.text },
            sungThrough = sungThrough,
            style = style,
            sungColor = currentColor,
        )
        romanization?.invoke()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowWords(
    words: List<String>,
    sungThrough: Int,
    style: TextStyle,
    sungColor: Color,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        words.forEachIndexed { index, word ->
            Text(
                text = word,
                style = style,
                color = if (index <= sungThrough) sungColor else PendingWord,
            )
        }
    }
}
