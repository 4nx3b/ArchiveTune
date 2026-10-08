/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/*
 * "Blossom" — the in-house Apple-Music-style lyrics animation renderer, built
 * after the LyricsBlossom 8.x experience (https://lyricsblossom.theoscarshen.com,
 * a standalone app without an embeddable Android library, so its look is
 * re-implemented natively here):
 *
 *  - word-by-word emphasis: each word lifts (alpha 0.4 -> 1, gentle scale
 *    arc) exactly while it is being sung, drawn through graphicsLayer lambdas
 *    so per-frame progress never triggers recomposition;
 *  - dimmed + blurred upcoming lines that sharpen as they approach the
 *    active line (mirroring the enhanced renderer's blur falloff);
 *  - followed auto-scroll with the active line pinned at a top anchor,
 *    suspended while the user scrolls and resumed after ~3s idle;
 *  - breathing dots across instrumental interludes;
 *  - full honouring of the shared lyrics settings: font weight, font size,
 *    translations, phonetics, line blur and click-to-seek.
 */

private val BlossomLineSpacing = 26.dp
private val BlossomManualResumeMs = 3000L
private const val BlossomUpcomingAlpha = 0.38f
private const val BlossomPastAlpha = 0.28f
private const val BlossomWordDimAlpha = 0.4f
private const val BlossomWordLiftScale = 0.07f
private const val BlossomBlurDeltaPerLine = 3f
private const val BlossomInterludeGapMs = 5000

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlossomLyricsView(
    lyrics: SyncedLyrics,
    currentPosition: () -> Int,
    activeLineIndex: Int,
    onLineClicked: (ISyncedLine) -> Unit,
    onLinePressed: (ISyncedLine) -> Unit,
    modifier: Modifier = Modifier,
    normalTextStyle: TextStyle,
    accompanimentTextStyle: TextStyle,
    translationTextStyle: TextStyle,
    phoneticTextStyle: TextStyle,
    textColor: Color = Color.White,
    useBlurEffect: Boolean = true,
    showTranslation: Boolean = true,
    showPhonetic: Boolean = true,
    anchorTopPadding: Dp = 112.dp,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val anchorTopPx = with(density) { anchorTopPadding.roundToPx() }
    val latestPosition = rememberUpdatedState(currentPosition)

    var manualScrolling by remember { mutableStateOf(false) }
    var lastManualScrollAt by remember { mutableLongStateOf(0L) }
    val scrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    manualScrolling = true
                    lastManualScrollAt = System.currentTimeMillis()
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(manualScrolling, lastManualScrollAt) {
        if (manualScrolling) {
            delay(BlossomManualResumeMs)
            if (System.currentTimeMillis() - lastManualScrollAt >= BlossomManualResumeMs - 64L) {
                manualScrolling = false
            }
        }
    }

    // Followed scrolling: the active line's top is pinned at the anchor.
    // Negative offsets place the item below the viewport's top edge.
    LaunchedEffect(activeLineIndex, manualScrolling, lyrics) {
        if (manualScrolling || lyrics.lines.isEmpty()) return@LaunchedEffect
        val target = activeLineIndex.coerceIn(0, lyrics.lines.lastIndex)
        runCatching { listState.animateScrollToItem(target, -anchorTopPx) }
    }

    BoxWithConstraints(modifier = modifier) {
        val bottomSafePadding = maxHeight - anchorTopPadding + 48.dp
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollConnection),
            contentPadding =
                PaddingValues(
                    top = anchorTopPadding,
                    bottom = bottomSafePadding,
                ),
            verticalArrangement = Arrangement.spacedBy(BlossomLineSpacing),
        ) {
            itemsIndexed(
                items = lyrics.lines,
                key = { index, line -> "${line.start}-${line.end}-$index" },
            ) { index, line ->
                val previous = lyrics.lines.getOrNull(index - 1)
                val gapBeforeMs =
                    if (previous == null || previous.end < 0) {
                        -1
                    } else {
                        line.start - previous.end
                    }
                BlossomLyricsLine(
                    line = line,
                    isActive = index == activeLineIndex,
                    signedDistance = index - activeLineIndex,
                    hasInterludeBefore = gapBeforeMs > BlossomInterludeGapMs,
                    interludeActive = {
                        latestPosition.value() in previous!!.end..line.start
                    },
                    position = { latestPosition.value() },
                    normalTextStyle = normalTextStyle,
                    accompanimentTextStyle = accompanimentTextStyle,
                    translationTextStyle = translationTextStyle,
                    phoneticTextStyle = phoneticTextStyle,
                    textColor = textColor,
                    useBlurEffect = useBlurEffect,
                    showTranslation = showTranslation,
                    showPhonetic = showPhonetic,
                    onLineClicked = { onLineClicked(line) },
                    onLinePressed = { onLinePressed(line) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlossomLyricsLine(
    line: ISyncedLine,
    isActive: Boolean,
    signedDistance: Int,
    hasInterludeBefore: Boolean,
    interludeActive: () -> Boolean,
    position: () -> Int,
    normalTextStyle: TextStyle,
    accompanimentTextStyle: TextStyle,
    translationTextStyle: TextStyle,
    phoneticTextStyle: TextStyle,
    textColor: Color,
    useBlurEffect: Boolean,
    showTranslation: Boolean,
    showPhonetic: Boolean,
    onLineClicked: () -> Unit,
    onLinePressed: () -> Unit,
) {
    val lineAlpha by animateFloatAsState(
        targetValue =
            when {
                isActive -> 1f
                signedDistance < 0 -> BlossomPastAlpha
                else -> BlossomUpcomingAlpha
            },
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "blossom-line-alpha",
    )
    val lineScale by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.955f,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        label = "blossom-line-scale",
    )
    val distance = abs(signedDistance)
    val blurRadius by animateDpAsState(
        targetValue =
            if (useBlurEffect && distance > 0) {
                (distance * BlossomBlurDeltaPerLine).dp
            } else {
                0.dp
            },
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "blossom-line-blur",
    )
    val rightAligned = (line as? KaraokeLine)?.alignment == KaraokeAlignment.End

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    this.alpha = lineAlpha
                    scaleX = lineScale
                    scaleY = lineScale
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
                .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier)
                .combinedClickable(
                    onClick = onLineClicked,
                    onLongClick = onLinePressed,
                )
                .padding(vertical = 2.dp),
        horizontalAlignment = if (rightAligned) Alignment.End else Alignment.Start,
    ) {
        if (hasInterludeBefore) {
            BlossomInterludeDots(
                active = interludeActive,
                dotColor = textColor,
            )
            Spacer(Modifier.height(BlossomLineSpacing))
        }

        when (line) {
            is KaraokeLine.MainKaraokeLine -> {
                line.accompanimentLines?.forEach { bgLine ->
                    BlossomWordsRow(
                        syllables = bgLine.syllables,
                        position = position,
                        isActive = isActive,
                        textStyle = accompanimentTextStyle,
                        textColor = textColor,
                        wordAlphaFloor = 0.85f,
                        showPhonetic = false,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                BlossomWordsRow(
                    syllables = line.syllables,
                    position = position,
                    isActive = isActive,
                    textStyle = normalTextStyle,
                    textColor = textColor,
                    wordAlphaFloor = 1f,
                    showPhonetic = showPhonetic,
                )
            }

            is KaraokeLine.AccompanimentKaraokeLine -> {
                BlossomWordsRow(
                    syllables = line.syllables,
                    position = position,
                    isActive = isActive,
                    textStyle = accompanimentTextStyle,
                    textColor = textColor,
                    wordAlphaFloor = 1f,
                    showPhonetic = showPhonetic,
                )
            }

            is SyncedLine -> {
                BlossomPlainText(
                    content = line.content,
                    isActive = isActive,
                    position = position,
                    start = line.start,
                    end = line.end,
                    textStyle = normalTextStyle,
                    textColor = textColor,
                )
            }

            else -> {}
        }

        // translation/phonetic live on the concrete line types, not on the
        // shared ISyncedLine interface — resolve them through smart casts.
        val lineTranslation =
            when (line) {
                is KaraokeLine -> line.translation
                is SyncedLine -> line.translation
                else -> null
            }
        val linePhonetic =
            when (line) {
                is KaraokeLine -> line.phonetic
                is SyncedLine -> line.phonetic
                else -> null
            }

        if (showTranslation && !lineTranslation.isNullOrBlank()) {
            Text(
                text = lineTranslation,
                style = translationTextStyle,
                color = textColor.copy(alpha = 0.72f),
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        if (showPhonetic && !linePhonetic.isNullOrBlank()) {
            Text(
                text = linePhonetic,
                style = phoneticTextStyle,
                color = textColor.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlossomWordsRow(
    syllables: List<KaraokeSyllable>,
    position: () -> Int,
    isActive: Boolean,
    textStyle: TextStyle,
    textColor: Color,
    wordAlphaFloor: Float,
    showPhonetic: Boolean,
) {
    FlowRow {
        syllables.forEach { syllable ->
            val phonetic = syllable.phonetic
            if (showPhonetic && !phonetic.isNullOrBlank()) {
                Column {
                    BlossomWord(
                        syllable = syllable,
                        position = position,
                        isActive = isActive,
                        textStyle = textStyle,
                        textColor = textColor,
                        wordAlphaFloor = wordAlphaFloor,
                    )
                    Text(
                        text = phonetic,
                        style = textStyle.copy(fontSize = textStyle.fontSize * 0.46f),
                        color = textColor.copy(alpha = 0.5f),
                    )
                }
            } else {
                BlossomWord(
                    syllable = syllable,
                    position = position,
                    isActive = isActive,
                    textStyle = textStyle,
                    textColor = textColor,
                    wordAlphaFloor = wordAlphaFloor,
                )
            }
        }
    }
}

@Composable
private fun BlossomWord(
    syllable: KaraokeSyllable,
    position: () -> Int,
    isActive: Boolean,
    textStyle: TextStyle,
    textColor: Color,
    wordAlphaFloor: Float,
) {
    // All per-frame progress is read inside the graphicsLayer lambda: the
    // word never recomposes while it is being sung — only its layer is
    // re-rendered (the signature Apple Music word lift).
    Text(
        text = syllable.content,
        style = textStyle,
        color = textColor,
        modifier =
            Modifier.graphicsLayer {
                val pos = position()
                val singing = isActive && pos >= syllable.start && pos < syllable.end
                val sung = pos >= syllable.end
                val alpha =
                    when {
                        !isActive -> 1f
                        sung -> 1f
                        singing -> {
                            val p = syllable.safeProgress(pos)
                            (BlossomWordDimAlpha + (1f - BlossomWordDimAlpha) * p) * wordAlphaFloor
                        }
                        else -> BlossomWordDimAlpha * wordAlphaFloor
                    }
                this.alpha = alpha
                val lift =
                    if (singing) {
                        sin(syllable.safeProgress(pos) * 2f * PI.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                val s = 1f + BlossomWordLiftScale * lift
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0.5f, 0.78f)
            },
    )
}

@Composable
private fun BlossomPlainText(
    content: String,
    isActive: Boolean,
    position: () -> Int,
    start: Int,
    end: Int,
    textStyle: TextStyle,
    textColor: Color,
) {
    Text(
        text = content,
        style = textStyle,
        color = textColor,
        modifier =
            Modifier.graphicsLayer {
                val pos = position()
                val alpha =
                    if (!isActive) {
                        1f
                    } else {
                        val span = (end - start).coerceAtLeast(1)
                        val p = ((pos - start).toFloat() / span).coerceIn(0f, 1f)
                        BlossomWordDimAlpha + (1f - BlossomWordDimAlpha) * p
                    }
                this.alpha = alpha
            },
    )
}

@Composable
private fun BlossomInterludeDots(
    active: () -> Boolean,
    dotColor: Color,
) {
    val activeState by remember { derivedStateOf { active() } }
    val pulse by rememberInfiniteTransition(label = "blossom-interlude").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "blossom-interlude-pulse",
    )
    Row {
        repeat(3) { index ->
            val phase = (pulse + index * 0.33f) % 1f
            val wave = (sin(phase * 2f * PI.toFloat()) + 1f) * 0.5f
            val alpha by animateFloatAsState(
                targetValue = if (activeState) 0.35f + 0.65f * wave else 0.22f,
                animationSpec = tween(240, easing = FastOutSlowInEasing),
                label = "blossom-dot-alpha",
            )
            val scale = if (activeState) 0.7f + 0.3f * wave else 0.7f
            Box(
                modifier =
                    Modifier
                        .padding(horizontal = 5.dp)
                        .size(7.dp)
                        .graphicsLayer {
                            this.alpha = alpha
                            scaleX = scale
                            scaleY = scale
                        }
                        .clip(CircleShape)
                        .background(dotColor.copy(alpha = 0.9f)),
            )
        }
    }
}

/** Zero-duration syllables never divide by zero — they flip straight to sung. */
private fun KaraokeSyllable.safeProgress(pos: Int): Float {
    val duration = end - start
    return if (duration <= 0) {
        if (pos >= end) 1f else 0f
    } else {
        ((pos - start).toFloat() / duration).coerceIn(0f, 1f)
    }
}
