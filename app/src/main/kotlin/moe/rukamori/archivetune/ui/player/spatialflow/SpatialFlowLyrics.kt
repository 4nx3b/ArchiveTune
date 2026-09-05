/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * SpatialFlow player style — the full-screen lyrics overlay.
 *
 * A port of SpatialFlow's FullScreenLyricsOverlay + the circular-reveal
 * modifier (github.com/MythicalSHUB/SpatialFlow, GPL-3.0,
 * ui/player/FullScreenLyricsOverlay.kt): the overlay reveals with a circular
 * clip expanding from the Lyrics chip, carries the centred "LYRICS • Synced
 * Lyrics" header with the song title, the auto-scrolling synced lines (active
 * 38sp Bold, inactive 20sp dimmed, tap to seek), the plain-lyrics fallback and
 * the metadata footer. Dimensions, colors and reveal timing are SpatialFlow's
 * own. The lyric DATA comes from ArchiveTune's own lyrics store
 * (playerConnection.currentLyrics parsed by LyricsUtils) — real providers, no
 * second lyrics implementation.
 */

package moe.rukamori.archivetune.ui.player.spatialflow

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AutoTranslateExcludedLanguagesKey
import moe.rukamori.archivetune.constants.AutoTranslateLyricsKey
import moe.rukamori.archivetune.constants.TranslatorTargetLangKey
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import moe.rukamori.archivetune.lyrics.AiLyricsRomanization
import moe.rukamori.archivetune.lyrics.LyricsEntry
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.lyrics.WordTimestamp
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.ui.component.PlatformBackdrop
import moe.rukamori.archivetune.ui.component.layerBackdrop
import moe.rukamori.archivetune.ui.component.rememberBackdrop
import moe.rukamori.archivetune.ui.menu.AnchoredLyricsOverflowMenu
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.LyricsMenuViewModel

/**
 * Optimized circular reveal modifier utilizing a remembered path and in-place
 * reset/rebuild (SpatialFlow's `circularRevealFrom`).
 */
private fun Modifier.circularRevealFrom(
    progressProvider: () -> Float,
    centerProvider: () -> Offset?,
): Modifier =
    this.drawWithCachePathClip(progressProvider, centerProvider)

private fun Modifier.drawWithCachePathClip(
    progressProvider: () -> Float,
    centerProvider: () -> Offset?,
): Modifier =
    this.drawWithCache {
            val revealPath = Path()
            var lastCenter: Offset? = null
            var lastRadius = -1f
            onDrawWithContent {
                val progress = progressProvider()
                if (progress >= 1f) {
                    drawContent()
                    return@onDrawWithContent
                }
                if (progress <= 0f) {
                    return@onDrawWithContent
                }
                val revealCenter = centerProvider() ?: Offset(size.width / 2f, size.height / 3f)
                if (revealCenter != lastCenter || lastRadius == -1f) {
                    lastCenter = revealCenter
                    lastRadius =
                        maxOf(
                            kotlin.math.hypot(revealCenter.x.toDouble(), revealCenter.y.toDouble()).toFloat(),
                            kotlin.math.hypot((size.width - revealCenter.x).toDouble(), revealCenter.y.toDouble()).toFloat(),
                            kotlin.math.hypot(revealCenter.x.toDouble(), (size.height - revealCenter.y).toDouble()).toFloat(),
                            kotlin.math.hypot((size.width - revealCenter.x).toDouble(), (size.height - revealCenter.y).toDouble()).toFloat(),
                        )
                }
                val radius = lastRadius * progress
                revealPath.reset()
                revealPath.addOval(
                    androidx.compose.ui.geometry.Rect(
                        left = revealCenter.x - radius,
                        top = revealCenter.y - radius,
                        right = revealCenter.x + radius,
                        bottom = revealCenter.y + radius,
                    ),
                )
                // clipPath's block receiver is a plain DrawScope; drawContent()
                // lives on the ContentDrawScope of onDrawWithContent — qualify it.
                clipPath(revealPath, ClipOp.Intersect) {
                    this@onDrawWithContent.drawContent()
                }
            }
        }

@Composable
internal fun SpatialFlowLyricsOverlay(
    currentSong: MediaMetadata,
    syncedLyrics: List<LyricsEntry>?,
    plainLyrics: String?,
    lyricsProvider: String?,
    currentPositionProvider: () -> Long,
    contentReady: Boolean,
    backgroundBrush: Brush,
    revealProgressProvider: () -> Float,
    revealCenterProvider: () -> Offset?,
    contentColor: Color,
    contentSecondary: Color,
    onSeekTo: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val currentLyricsEntity by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)

    // ── Lyrics overflow menu (2026-09-05) ─────────────────────────────────
    // The SpatialFlow lyrics screen previously had NO lyrics overflow menu
    // at all, so Translate / AI Translation / Romanise / Undo / Search were
    // simply unreachable here (user report: "Translation/AI Translation/
    // Romanisation doesn't work in ... SpatialFlow lyrics screens"). The
    // header's leading slot (a 48dp Spacer) becomes the more button opening
    // the same anchored Apple-Music-style popup the Apple Music and
    // SimpMusic styles show, rendered as the last child of this overlay's
    // root Box (always above the lyrics).
    var showLyricsMenu by remember { mutableStateOf(false) }
    var moreIconBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    // Backdrop that records THIS overlay's content (title header + lyrics)
    // while the popup is open, so its drawBackdrop sampler blurs what is
    // actually behind the menu. Android 12+ only; below that the popup
    // falls back to its dark tint. The popup renders as a SIBLING of the
    // layer-capturing Box (never nested inside it).
    val popupBackdrop: PlatformBackdrop? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            rememberBackdrop(Color.Transparent)
        } else {
            null
        }

    // ── Automatic AI translation (2026-09-05) ──────────────────────────────
    // Mirrors the LaunchedEffect in AppleMusicPlayer.kt / LyricsScreen.kt —
    // the SpatialFlow lyrics screen previously had no auto-translate
    // trigger (user report: "Auto translation and auto romanisation doesn't
    // work in ... SpatialFlow lyrics screens").
    val (autoTranslateLyrics) = rememberPreference(AutoTranslateLyricsKey, defaultValue = false)
    val (translatorTargetLang) = rememberPreference(TranslatorTargetLangKey, defaultValue = "")
    val (autoTranslateExcludedLanguages) =
        rememberPreference(AutoTranslateExcludedLanguagesKey, defaultValue = emptySet())
    val lyricsMenuViewModel: LyricsMenuViewModel = hiltViewModel()
    val translationDismissedMediaIds by lyricsMenuViewModel.translationDismissedMediaIds
        .collectAsStateWithLifecycle()
    LaunchedEffect(
        currentSong.id,
        currentLyricsEntity?.lyrics,
        currentLyricsEntity?.source,
        autoTranslateLyrics,
        translatorTargetLang,
        autoTranslateExcludedLanguages,
        translationDismissedMediaIds,
    ) {
        if (!autoTranslateLyrics) return@LaunchedEffect
        val snapshot = currentLyricsEntity ?: return@LaunchedEffect
        val text = snapshot.lyrics ?: return@LaunchedEffect
        if (text.isBlank() || text == LYRICS_NOT_FOUND) return@LaunchedEffect
        if (snapshot.source == LyricsEntity.Source.AI_TRANSLATION.value &&
            LyricsUtils.hasTranslation(text)
        ) return@LaunchedEffect
        if (currentSong.id in translationDismissedMediaIds) return@LaunchedEffect
        if (!LyricsUtils.shouldAutoTranslate(
                lyrics = text,
                targetLanguage = translatorTargetLang,
                excludedLanguageCodes = autoTranslateExcludedLanguages,
            )
        ) {
            return@LaunchedEffect
        }
        lyricsMenuViewModel.translateLyricsWithAi(
            mediaMetadata = currentSong,
            lyrics = text,
            targetLanguage = translatorTargetLang,
        )
    }

    // ── AI romanisation (2026-09-05) ─────────────────────────────────────
    // Mirrors LyricsEnhanced's consumption of AiLyricsRomanization results —
    // without this the menu's "AI Romanise Now" and the "Auto AI
    // Romanisation" setting had no visible effect in the SpatialFlow
    // lyrics screen. Lines are resolved by line TEXT (not index).
    val aiRomanizationSettings = AiLyricsRomanization.rememberSettings()
    val aiRomanizationSessionKey =
        remember(currentLyricsEntity?.lyrics) {
            AiLyricsRomanization.sessionKey(currentSong.id, currentLyricsEntity?.lyrics)
        }
    val aiRomanizationResult by AiLyricsRomanization.results.collectAsStateWithLifecycle()
    val romanizedLines: List<String?> =
        remember(
            aiRomanizationResult,
            aiRomanizationSessionKey,
            aiRomanizationSettings.active,
            syncedLyrics,
        ) {
            if (!aiRomanizationSettings.active || syncedLyrics == null) {
                emptyList()
            } else {
                AiLyricsRomanization.linesFor(aiRomanizationSessionKey, syncedLyrics.map { it.text })
            }
        }
    LaunchedEffect(aiRomanizationSessionKey, syncedLyrics, aiRomanizationSettings) {
        if (!aiRomanizationSettings.active || !aiRomanizationSettings.auto) return@LaunchedEffect
        if (syncedLyrics.isNullOrEmpty()) return@LaunchedEffect
        AiLyricsRomanization.request(
            sessionKey = aiRomanizationSessionKey,
            lines = syncedLyrics.map { it.text },
            settings = aiRomanizationSettings,
        )
    }

    val consumeClicks = remember { MutableInteractionSource() }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
        }
    }

    Box(
        modifier =
            modifier
                .circularRevealFrom(
                    progressProvider = revealProgressProvider,
                    centerProvider = revealCenterProvider,
                ).background(backgroundBrush)
                .clickable(
                    interactionSource = consumeClicks,
                    indication = null,
                    onClick = {},
                ).padding(top = LocalStableSystemBarsTopPadding.current)
                .navigationBarsPadding()
                .padding(vertical = 12.dp),
    ) {
        // Inner content Box — records the overlay's header + lyrics into
        // `popupBackdrop` WHILE the anchored overflow popup is open (same
        // pattern as the SimpMusic lyrics sheet); the popup renders as a
        // SIBLING below, never nested inside this layer-capturing Box.
        Box(
            modifier =
                Modifier.fillMaxSize().let { base ->
                    if (popupBackdrop != null && showLyricsMenu) {
                        base.layerBackdrop(popupBackdrop)
                    } else {
                        base
                    }
                },
        ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Centered Title Header Layout
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Lyrics overflow menu button (2026-09-05): opens the same
                // anchored Apple-Music-style popup the other player styles
                // show — Translate / AI Translation / Romanise / Undo /
                // Search were unreachable in this screen before.
                IconButton(
                    onClick = { showLyricsMenu = true },
                    modifier =
                        Modifier.onGloballyPositioned { coords ->
                            val pos = coords.positionInRoot()
                            val sz = coords.size
                            moreIconBounds =
                                androidx.compose.ui.geometry.Rect(
                                    offset = pos,
                                    size =
                                        androidx.compose.ui.geometry.Size(
                                            width = sz.width.toFloat(),
                                            height = sz.height.toFloat(),
                                        ),
                                )
                        },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.more_vert),
                        contentDescription = "Lyrics menu",
                        tint = contentColor.copy(alpha = 0.8f),
                        modifier = Modifier.size(24.dp),
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.basicMarqueeWithFadedEdges(edgeWidth = 8.dp),
                    ) {
                        Text(
                            text = "LYRICS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = contentColor.copy(alpha = 0.5f),
                            letterSpacing = 1.sp,
                        )
                        AnimatedVisibility(
                            visible = !syncedLyrics.isNullOrEmpty(),
                            enter =
                                fadeIn(
                                    animationSpec =
                                        androidx.compose.animation.core.spring(
                                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                            stiffness = androidx.compose.animation.core.Spring.StiffnessLow,
                                        ),
                                ) + slideInHorizontally(initialOffsetX = { it / 2 }),
                            exit = fadeOut() + slideOutHorizontally(),
                        ) {
                            Text(
                                text = " • Synced Lyrics",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = contentColor.copy(alpha = 0.4f),
                                letterSpacing = 1.sp,
                            )
                        }
                    }
                    Text(
                        text = currentSong.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                        maxLines = 1,
                        modifier = Modifier.basicMarqueeWithFadedEdges(edgeWidth = 8.dp),
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(R.drawable.close),
                        contentDescription = "Close Lyrics",
                        tint = contentColor.copy(alpha = 0.8f),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    !contentReady -> Unit

                    !syncedLyrics.isNullOrEmpty() ->
                        SpatialFlowSyncedLyrics(
                            lyrics = syncedLyrics,
                            romanizedLines = romanizedLines,
                            currentPositionProvider = currentPositionProvider,
                            contentColor = contentColor,
                            onSeekTo = onSeekTo,
                            modifier = Modifier.fillMaxSize(),
                        )

                    !plainLyrics.isNullOrBlank() ->
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 28.dp),
                        ) {
                            Text(
                                text = plainLyrics,
                                style = MaterialTheme.typography.titleLarge,
                                color = contentColor.copy(alpha = 0.9f),
                            )
                            LyricsMetadataFooter(
                                currentSong = currentSong,
                                selectedProvider = lyricsProvider,
                                contentColor = contentColor,
                            )
                        }

                    else ->
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 32.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "No lyrics found",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = contentColor.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 64.dp, bottom = 12.dp),
                            )
                            Text(
                                text = "Lyrics for this song are not available yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = contentColor.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center,
                            )
                            LyricsMetadataFooter(
                                currentSong = currentSong,
                                selectedProvider = lyricsProvider,
                                contentColor = contentColor,
                            )
                        }
                }
            }
        }
        } // end inner content Box (popup backdrop recording layer)

        // ── Anchored Apple-Music-style overflow popup ─────────────────────
        // Rendered as the LAST child of the overlay's root Box so it draws
        // above everything else (title header, lyrics). Same menu the Apple
        // Music and SimpMusic player styles show over their lyrics.
        if (showLyricsMenu) {
            AnchoredLyricsOverflowMenu(
                iconBoundsInRoot = moreIconBounds,
                lyricsProvider = { currentLyricsEntity },
                mediaMetadataProvider = { currentSong },
                lyricsSyncOffset = 0,
                onLyricsSyncOffsetChange = {},
                onDismiss = { showLyricsMenu = false },
                backdrop = popupBackdrop,
            )
        }
    }
}

/**
 * The synced-lyrics list — SpatialFlow's SyncedLyricsCompose, INCLUDING the word-by-word
 * karaoke highlighting (ported 2026-09-05 after "word synced lyrics don't work correctly in
 * SpatialFlow player"): a karaoke line renders as a dim base Text plus a fully-lit overlay
 * Text whose not-yet-sung characters are erased with a DstOut sweep — per character, driven
 * by each word's own start/end timestamps, with a soft gradient at the sweep front and a
 * 200ms linear position smoothing so the 100ms position polls sweep continuously. Lines with
 * no word timings keep the line-level highlight (active 38sp Bold, inactive 20sp dimmed);
 * instrumental breaks render SpatialFlow's breathing-note interlude row with a wavy progress
 * bar. Tap a line to seek; the list auto-scrolls so the active line stays centred.
 */
@Composable
private fun SpatialFlowSyncedLyrics(
    lyrics: List<LyricsEntry>,
    romanizedLines: List<String?>,
    currentPositionProvider: () -> Long,
    contentColor: Color,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val dimColor = contentColor.copy(alpha = 0.35f)

    // ── Detect karaoke mode (SpatialFlow's isKaraokeMode) ────────────────────────────
    val isKaraokeMode =
        remember(lyrics) {
            lyrics.any { !it.isInstrumental && LyricsUtils.hasTrueWordSync(it) }
        }

    // ── Filter out interludes when in karaoke mode ───────────────────────────────────
    val displayItems =
        remember(lyrics, isKaraokeMode) {
            lyrics.mapIndexedNotNull { index, line ->
                if (isKaraokeMode && line.isInstrumental) null else index
            }
        }

    val activeIndex by remember(displayItems) {
        derivedStateOf {
            val position = currentPositionProvider()
            var index = -1
            for (i in displayItems.indices) {
                if (lyrics[displayItems[i]].time <= position) index = i else break
            }
            index
        }
    }

    // Auto-scroll: only animate when the active line CHANGES, and never fight the user's
    // own scroll (SpatialFlow's guard — animateScrollToItem cancels a drag mid-gesture).
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && !listState.isScrollInProgress) {
            listState.animateScrollToItem(
                index = activeIndex,
                scrollOffset = -200,
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding =
            androidx.compose.foundation.layout.PaddingValues(
                start = 32.dp,
                end = 32.dp,
                top = 48.dp,
                bottom = 96.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(
            items = displayItems,
            key = { displayIndex, lyricsIndex -> "$lyricsIndex-${lyrics[lyricsIndex].time}-$displayIndex" },
        ) { displayIndex, lyricsIndex ->
            val line = lyrics[lyricsIndex]
            val isActive = displayIndex == activeIndex
            if (line.isInstrumental) {
                SpatialFlowInterludeItem(
                    isActive = isActive,
                    currentPositionProvider = currentPositionProvider,
                    line = line,
                    nextLineStartMs = lyrics.getOrNull(lyricsIndex + 1)?.time ?: (line.time + 5000L),
                    accentColor = contentColor,
                )
            } else {
                SpatialFlowLyricLineItem(
                    line = line,
                    isActive = isActive,
                    currentPositionProvider = currentPositionProvider,
                    contentColor = contentColor,
                    romanizedText = romanizedLines.getOrNull(lyricsIndex)?.takeIf { it.isNotBlank() },
                    onClick = { onSeekTo(line.time) },
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// ─ Lyric Line Item — SpatialFlow's APPLE-MUSIC-STYLE WORD HIGHLIGHTING ───────────
// ════════════════════════════════════════════════════════════════════════════════

/**
 * A word span mapped onto the rendered string's character range. [WordTimestamp] carries no
 * char positions, so the spans are computed by sequentially locating each word's text inside
 * the line's text — the same contract SpatialFlow's LyricWord.charRange serves upstream.
 */
private data class WordCharSpan(
    val start: Int,
    val endExclusive: Int,
    val word: WordTimestamp,
)

private fun wordSpansFor(
    text: String,
    words: List<WordTimestamp>,
): List<WordCharSpan> {
    val spans = mutableListOf<WordCharSpan>()
    var cursor = 0
    for (word in words) {
        val idx = text.indexOf(word.text, cursor)
        if (idx >= 0) {
            spans += WordCharSpan(idx, idx + word.text.length, word)
            cursor = idx + word.text.length
        }
    }
    return spans
}

@Composable
private fun SpatialFlowLyricLineItem(
    line: LyricsEntry,
    isActive: Boolean,
    currentPositionProvider: () -> Long,
    contentColor: Color,
    romanizedText: String? = null,
    onClick: () -> Unit,
) {
    val rawWords = line.words.orEmpty().filter { it.text.isNotBlank() }
    val spans = remember(line.text, rawWords) { wordSpansFor(line.text, rawWords) }
    val isKaraoke = LyricsUtils.hasTrueWordSync(line) && spans.isNotEmpty()

    val dimColor = contentColor.copy(alpha = 0.35f)
    val litColor = contentColor

    // 200ms linear smoothing of the playback position (SpatialFlow's SmoothKaraokePos): the
    // position polls every ~100ms, and animating between polls is what makes the per-word
    // sweep continuous instead of stepping.
    val rawPos = if (isKaraoke && isActive) currentPositionProvider() else line.time
    val smoothedPos by animateFloatAsState(
        targetValue = rawPos.toFloat(),
        animationSpec = tween(durationMillis = 200, easing = LinearEasing),
        label = "SmoothKaraokePos",
    )

    val mainTextStyle =
        MaterialTheme.typography.headlineMedium.copy(
            fontFamily = SpatialFlowGoogleSansFlexNonRounded,
            fontSize = 38.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 50.sp,
        )
    val baseTextLayout = remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }

    Box(
        modifier =
            Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Text stack — the base dim text and the karaoke overlay text
            // OVERLAP here (Box children stack), exactly as before; the
            // romanisation sub-line then flows below the stack.
            Box(modifier = Modifier.fillMaxWidth()) {
                // Base dim text — for karaoke lines it stays dim and the overlay lights the sung part;
                // for line-synced lines it carries the whole highlight when active.
                Text(
                    text = line.text,
                    style = mainTextStyle,
                    color = if (isKaraoke || !isActive) dimColor else litColor,
                    textAlign = TextAlign.Center,
                    onTextLayout = { baseTextLayout.value = it },
                    maxLines = Int.MAX_VALUE,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Overlay lit text, erased ahead of the sung position (SpatialFlow's eraseFutureText).
                if (isKaraoke && isActive) {
                    Text(
                        text = line.text,
                        style = mainTextStyle,
                        color = litColor,
                        textAlign = TextAlign.Center,
                        maxLines = Int.MAX_VALUE,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                .drawWithCache {
                                    onDrawWithContent {
                                        val layout = baseTextLayout.value
                                        drawContent()
                                        if (layout != null) {
                                            eraseFutureText(layout, spans, smoothedPos.toLong())
                                        }
                                    }
                                },
                    )
                }
            }

            // AI romanisation sub-line (2026-09-05) — smaller and dimmer under
            // the lyric line, the same presentation the Apple Music renderer's
            // romanisation uses.
            romanizedText
                ?.takeIf { it.isNotBlank() && it != line.text }
                ?.let { romanized ->
                    Text(
                        text = romanized,
                        style =
                            MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = SpatialFlowGoogleSansFlexNonRounded,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                            ),
                        color = if (isActive) litColor.copy(alpha = 0.65f) else dimColor.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
        }
    }
}

/**
 * Erases the characters that have not been sung yet from an overlay text, per character:
 * fully-sung characters stay lit, future characters are erased outright (DstOut), and the
 * character under the sweep front is erased through a short horizontal gradient so the
 * leading edge is soft. Port of SpatialFlow's eraseFutureText/calculateCharProgress.
 */
private fun DrawScope.eraseFutureText(
    layout: androidx.compose.ui.text.TextLayoutResult,
    spans: List<WordCharSpan>,
    pos: Long,
) {
    val textLength = layout.layoutInput.text.length
    for (charIndex in 0 until textLength) {
        val controllingSpan = findControllingSpan(charIndex, spans)
        val charProgress =
            if (controllingSpan != null) {
                calculateCharProgress(charIndex, controllingSpan, pos)
            } else {
                0f
            }

        if (charProgress >= 0.99f) {
            // Fully swept character: leave it fully lit (do not erase).
        } else if (charProgress < 0.01f) {
            // Fully future character: erase it completely.
            val path = layout.getPathForRange(charIndex, charIndex + 1)
            drawPath(path, color = Color.Black, blendMode = BlendMode.DstOut)
        } else {
            // Partially sweeping character: soft gradient erase.
            val path = layout.getPathForRange(charIndex, charIndex + 1)
            val box = layout.getBoundingBox(charIndex)

            val gradientWidth = box.width * 1.5f
            val sweepCenter = box.left + (box.width * charProgress)

            val brush =
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    0.0f to Color.Transparent,
                    1.0f to Color.Black,
                    startX = sweepCenter - (gradientWidth / 2f),
                    endX = sweepCenter + (gradientWidth / 2f),
                )

            drawPath(path, brush = brush, blendMode = BlendMode.DstOut)
        }
    }
}

private fun findControllingSpan(
    charIndex: Int,
    spans: List<WordCharSpan>,
): WordCharSpan? {
    if (spans.isEmpty()) return null

    val exactSpan = spans.find { charIndex >= it.start && charIndex < it.endExclusive }
    if (exactSpan != null) return exactSpan

    if (charIndex < spans.first().start) return spans.first()
    if (charIndex >= spans.last().endExclusive) return spans.last()

    // Between words: the word that already passed owns the gap (spaces stay lit with it).
    return spans.lastOrNull { it.endExclusive <= charIndex } ?: spans.first()
}

private fun calculateCharProgress(
    charIndex: Int,
    span: WordCharSpan,
    pos: Long,
): Float {
    // WordTimestamp times are SECONDS (both the TTML and QRC parsers) — milliseconds here.
    val wordStartMs = (span.word.startTime * 1000.0).toLong()
    val wordEndMs = (span.word.endTime * 1000.0).toLong().coerceAtLeast(wordStartMs + 120L)

    val wordProgress =
        when {
            pos < wordStartMs -> 0f
            pos >= wordEndMs -> 1f
            else -> {
                val duration = (wordEndMs - wordStartMs).toFloat().coerceAtLeast(1f)
                ((pos - wordStartMs).toFloat() / duration).coerceIn(0f, 1f)
            }
        }

    val easedWordProgress = easeOutCubic(wordProgress)

    val wStart = span.start
    val wEnd = span.endExclusive
    val wordLength = (wEnd - wStart).toFloat().coerceAtLeast(1f)

    val sweepWidth = 0.35f
    val sweepPosition = easedWordProgress * (1f + sweepWidth)

    val charOffsetInWord = (charIndex - wStart).toFloat()
    val charRelativePosition = charOffsetInWord / wordLength

    return when {
        sweepPosition < charRelativePosition -> 0f
        sweepPosition >= (charRelativePosition + sweepWidth) -> 1f
        else -> (sweepPosition - charRelativePosition) / sweepWidth
    }
}

private fun easeOutCubic(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)

// ════════════════════════════════════════════════════════════════════════════════
// ─ Interlude Item (instrumental break) ────────────────────────────────────────────
// ════════════════════════════════════════════════════════════════════════════════

@Composable
private fun SpatialFlowInterludeItem(
    isActive: Boolean,
    currentPositionProvider: () -> Long,
    line: LyricsEntry,
    nextLineStartMs: Long,
    accentColor: Color,
) {
    val duration = (nextLineStartMs - line.time).coerceAtLeast(1)
    val rawProgress =
        if (isActive) {
            ((currentPositionProvider() - line.time).toFloat() / duration).coerceIn(0f, 1f)
        } else {
            0f
        }
    val animatedProgress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "InterludeProgress",
    )

    // Breathing scale for the note icon (SpatialFlow's InterludeBreathing).
    val infiniteTransition = rememberInfiniteTransition(label = "InterludeBreathing")
    val breatheScale by infiniteTransition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.18f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "BreathScale",
    )
    val iconAlpha by animateFloatAsState(
        targetValue = if (isActive) 0.85f else 0.25f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "InterludeAlpha",
    )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            painter = painterResource(id = R.drawable.spatialflow_ic_music_note),
            contentDescription = "Interlude",
            tint = accentColor.copy(alpha = iconAlpha),
            modifier =
                Modifier
                    .size(26.dp)
                    .graphicsLayer {
                        scaleX = if (isActive) breatheScale else 1f
                        scaleY = if (isActive) breatheScale else 1f
                    },
        )

        LinearWavyProgressIndicator(
            progress = { animatedProgress },
            modifier =
                Modifier
                    .weight(1f)
                    .height(11.dp),
            color = accentColor.copy(alpha = if (isActive) 0.75f else 0.18f),
            trackColor = accentColor.copy(alpha = 0.06f),
            amplitude = { p -> (0.6f + p) },
        )
    }
}
