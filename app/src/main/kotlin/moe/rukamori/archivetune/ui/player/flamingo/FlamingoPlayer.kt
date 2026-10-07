/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Player design ported from Flamingo (yos.music.player) ui/pages/NowPlaying.kt — GPLv3,
 * https://github.com/shouryadixitisverycool/Flamingo
 *
 * Layout: album page (artwork 0.595 of height + title row + action buttons),
 * in-page state machine Album / Lyric / PlayingList with a shared-element
 * artwork morph into the compact PlayingBar header, cross-fade page transitions
 * (300ms), queue overlay fade at 114dp, auto-hiding controls (2500ms on the
 * lyrics page), 19.5/18.5sp title typography, and the floating-light background.
 */

package moe.rukamori.archivetune.ui.player.flamingo

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.EaseOutQuart
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AiRomanizeLyricsKey
import moe.rukamori.archivetune.constants.AutoAiRomanizeLyricsKey
import moe.rukamori.archivetune.constants.AutoTranslateExcludedLanguagesKey
import moe.rukamori.archivetune.constants.AutoTranslateLyricsKey
import moe.rukamori.archivetune.constants.FlamingoBackgroundEffectKey
import moe.rukamori.archivetune.constants.FlamingoShowVolumeBarKey
import moe.rukamori.archivetune.constants.TranslatorTargetLangKey
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.db.entities.codecLabel
import moe.rukamori.archivetune.db.entities.isLossless
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.extensions.move
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.ui.component.BottomSheetPageState
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.LyricsEnhanced
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.PlatformBackdrop
import moe.rukamori.archivetune.ui.component.layerBackdrop
import moe.rukamori.archivetune.ui.component.rememberBackdrop
import moe.rukamori.archivetune.ui.component.rememberLiquidGlassEnabled
import moe.rukamori.archivetune.ui.menu.AnchoredLyricsOverflowMenu
import moe.rukamori.archivetune.ui.menu.PlayerMenu
import moe.rukamori.archivetune.ui.menu.rememberCastPlayerMenuAction
import moe.rukamori.archivetune.ui.utils.ShowMediaInfo
import moe.rukamori.archivetune.ui.utils.highRes
import moe.rukamori.archivetune.utils.rememberLowDataModeActive
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.LyricsMenuViewModel
import moe.rukamori.archivetune.ui.player.CanvasArtworkPlayer
import moe.rukamori.archivetune.ui.player.LocalVideoArtworkState
import moe.rukamori.archivetune.ui.player.rememberThumbnailSwapState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.media3.ui.AspectRatioFrameLayout
import coil3.compose.AsyncImage
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import moe.rukamori.archivetune.utils.isLocalMediaId

private const val AnimDurationMillis = 300
private const val ShareAlbumKey = "flamingoAlbum"
private val QueueRowHeight = 64.dp
private val QueueDraggingItemShape = RoundedCornerShape(0.dp)

// ---- Canvas stage ----
// There is deliberately NO blurred-canvas backdrop behind the controls
// anymore (neither orientation). The previous design ran a SECOND
// CanvasArtworkPlayer at a 1/6 footprint with a 12dp blur, upscaled 6x by a
// graphics layer to fill the whole player. A TextureView cannot composite
// into a Compose offscreen/RenderEffect layer, so the video escaped the
// blur/scale layer and rendered ABOVE the player controls (user report
// 2026-10-07: "the canvas ... is playing in the background even above the
// player controls and it's completely blurred"), kept a second full video
// decoder alive (the style's lag reports), and its layout box swallowed
// taps in the title-row band — the dead overflow chip on the album page
// (the queue page's chip, in a different band, kept working). The canvas
// now plays ONLY on the artwork stage, and the static floating-light
// background is the sole backdrop.
private const val FlamingoSharpStageFadeStart = 0.62f

private val FlamingoCanvasScrimBrush =
    Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.25f),
        0.5f to Color.Black.copy(alpha = 0.40f),
        1f to Color.Black.copy(alpha = 0.65f),
    )

// Overlay (not DstIn) gradients for the stage blends: the DstIn fades used
// to wrap the whole stage in CompositingStrategy.Offscreen, which is the
// other half of the same interop bug — the TextureView escaped the
// offscreen layer, so the sharp canvas never rendered inside the artwork
// stage. Drawing the blend ON TOP of the video as a plain scrim keeps the
// video compositing normally while still dissolving the stage edges.
private val FlamingoSharpStageBottomScrim =
    Brush.verticalGradient(
        FlamingoSharpStageFadeStart to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.55f),
    )

private val FlamingoLandscapeRightScrim =
    Brush.horizontalGradient(
        0.55f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.45f),
    )

// Lyrics content is composed only after the page crossfade + artwork morph
// settle: mid-morph composition of the word-synced karaoke machinery is what
// made the artwork transition janky (user report 2026-10-07).
private const val FlamingoLyricsContentDeferMs = 600L

private data class QueueReorderTarget(
    val nextInQueue: Boolean,
    val index: Int,
)

private fun formatTime(seconds: Long): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return "$minutes:${if (secs < 10) "0$secs" else "$secs"}"
}

private fun MediaMetadata.artistNames(): String =
    artists.joinToString { it.name }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FlamingoPlayerContent(
    mediaMetadata: MediaMetadata,
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    sliderPosition: Long?,
    positionProvider: () -> Long,
    duration: Long,
    playerConnection: PlayerConnection,
    navController: NavController,
    state: BottomSheetState,
    bottomSheetPageState: BottomSheetPageState,
    currentSongLiked: Boolean,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    canvasPrimaryUrl: String?,
    canvasFallbackUrl: String?,
    currentFormat: FormatEntity?,
    contentBottomPadding: Dp,
    onQueueClick: () -> Unit = {},
    onLyricsClick: () -> Unit = {},
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    lyricsSyncOffset: Int = 0,
    onLyricsSyncOffsetChange: (Int) -> Unit = {},
    onLyricsVisibilityChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
    orientationRefreshEpoch: Int = 0,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        contentColor = Color.White,
        color = Color.Transparent,
    ) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val topInset = LocalStableSystemBarsTopPadding.current

        val player = playerConnection.player

        // ---- Flamingo settings (ported defaults) ----
        val (showVolumeBar) = rememberPreference(FlamingoShowVolumeBarKey, defaultValue = true)
        val (backgroundEffect) = rememberPreference(FlamingoBackgroundEffectKey, defaultValue = false)

        // ---- Playback state ----
        val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsStateWithLifecycle()
        val repeatMode by playerConnection.repeatMode.collectAsStateWithLifecycle()
        val queueWindows by playerConnection.queueWindows.collectAsStateWithLifecycle()
        val currentWindowIndex by playerConnection.currentWindowIndex.collectAsStateWithLifecycle()

        val currentLyrics by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)

        val isPlayingStatusLambda = rememberUpdatedState(isPlaying)

        // ---- Canvas state ----
        val videoShowing =
            LocalVideoArtworkState.current != null &&
                mediaMetadata.isMusicVideo &&
                !mediaMetadata.id.isLocalMediaId()
        val isPreS = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        val canvasActive =
            !canvasPrimaryUrl.isNullOrBlank() || !canvasFallbackUrl.isNullOrBlank()
        val canvasVisualActive = canvasActive && !videoShowing && !isPreS

        // Scrim over the static floating-light background while a canvas is
        // active (keeps the stage's video readable against the background).
        val canvasScrimReveal by animateFloatAsState(
            targetValue = if (canvasVisualActive) 1f else 0f,
            animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            label = "flamingo-canvas-scrim-reveal",
        )

        val lastClickTime = rememberSaveable(key = "FlamingoNowPlaying_lastClickTime") {
            mutableLongStateOf(0L)
        }

        val showControl = rememberSaveable(key = "FlamingoNowPlaying_showControl") {
            mutableStateOf(!landscape)
        }

        var nowPage by rememberSaveable(key = "FlamingoNowPlaying_nowPage") {
            mutableStateOf(if (landscape) FlamingoPage.Lyric else FlamingoPage.Album)
        }

        LaunchedEffect(mediaMetadata.id, landscape) {
            nowPage = if (landscape) FlamingoPage.Lyric else FlamingoPage.Album
            showControl.value = !landscape
        }

        val nowPageLambda = rememberUpdatedState(nowPage)
        val showControlLambda = rememberUpdatedState(showControl.value)

        // In-page lyrics/queue take priority over the sheet-level back handling.
        BackHandler(enabled = nowPage != FlamingoPage.Album) {
            nowPage = FlamingoPage.Album
        }

        // Report immersive lyrics state to the host (mirrors the old Apple Music
        // player's inline-lyrics visibility reporting).
        LaunchedEffect(nowPage, state.isExpandedOrExpanding) {
            onLyricsVisibilityChange(nowPage != FlamingoPage.Album && state.isExpandedOrExpanding)
        }

        // ---- Lyrics content defer ----
        // The lyrics page's LyricsEnhanced (word-synced karaoke especially)
        // composes only AFTER the page crossfade + shared-element artwork
        // morph settle — composing it mid-morph is what made the artwork
        // transition janky.
        var lyricsContentReady by remember { mutableStateOf(false) }
        LaunchedEffect(nowPage) {
            if (nowPage == FlamingoPage.Lyric) {
                lyricsContentReady = false
                delay(FlamingoLyricsContentDeferMs)
                lyricsContentReady = true
            } else {
                lyricsContentReady = false
            }
        }

        val alphaAnim = remember { Animatable(0f) }
        LaunchedEffect(nowPage) {
            val targetAlpha = if (nowPage == FlamingoPage.Lyric) 1f else 0f
            scope.launch {
                alphaAnim.animateTo(targetAlpha)
            }
        }

        // ---- Artwork ----
        val baseArtworkUrl = mediaMetadata.thumbnailUrl?.highRes()
        val thumbnailSwapState = rememberThumbnailSwapState(
            videoId = mediaMetadata.id,
            ytmUrl = baseArtworkUrl,
            lowDataMode = rememberLowDataModeActive(),
            isMusicVideo = mediaMetadata.isMusicVideo,
        )
        val artworkUrl = thumbnailSwapState.displayUrl

        // ---- Translation / romanisation (AI only, no popups, no toasts) ----
        val (autoTranslateLyrics, onAutoTranslateLyricsChange) =
            rememberPreference(AutoTranslateLyricsKey, defaultValue = false)
        val (translatorTargetLang) = rememberPreference(TranslatorTargetLangKey, defaultValue = "")
        val (autoTranslateExcludedLanguages) =
            rememberPreference(AutoTranslateExcludedLanguagesKey, defaultValue = emptySet())
        val (aiRomanizeLyricsPref, onAiRomanizeLyricsChange) =
            rememberPreference(AiRomanizeLyricsKey, defaultValue = false)
        val (autoAiRomanizeLyrics, onAutoAiRomanizeLyricsChange) =
            rememberPreference(AutoAiRomanizeLyricsKey, defaultValue = false)
        val romanizationOn = aiRomanizeLyricsPref && autoAiRomanizeLyrics

        val lyricsMenuViewModel: LyricsMenuViewModel = hiltViewModel()
        val translationDismissedMediaIds by lyricsMenuViewModel.translationDismissedMediaIds
            .collectAsStateWithLifecycle()

        // Automatic AI translation: when the feature is enabled every song's
        // lyrics are translated in the background, no manual interaction needed.
        LaunchedEffect(
            mediaMetadata.id,
            currentLyrics?.lyrics,
            currentLyrics?.source,
            autoTranslateLyrics,
            translatorTargetLang,
            autoTranslateExcludedLanguages,
            translationDismissedMediaIds,
        ) {
            if (!autoTranslateLyrics) return@LaunchedEffect
            val snapshot = currentLyrics ?: return@LaunchedEffect
            val text = snapshot.lyrics ?: return@LaunchedEffect
            if (text.isBlank() || text == LyricsEntity.LYRICS_NOT_FOUND) return@LaunchedEffect

            if (snapshot.source == LyricsEntity.Source.AI_TRANSLATION.value &&
                LyricsUtils.hasTranslation(text)
            ) return@LaunchedEffect

            if (mediaMetadata.id in translationDismissedMediaIds) return@LaunchedEffect

            if (!LyricsUtils.shouldAutoTranslate(
                    lyrics = text,
                    targetLanguage = translatorTargetLang,
                    excludedLanguageCodes = autoTranslateExcludedLanguages,
                )
            ) {
                return@LaunchedEffect
            }

            lyricsMenuViewModel.translateLyricsWithAi(
                mediaMetadata = mediaMetadata,
                lyrics = text,
                targetLanguage = translatorTargetLang,
            )
        }

        val currentLyricsState = rememberUpdatedState(currentLyrics)
        val targetLangState = rememberUpdatedState(translatorTargetLang)

        fun setTranslationEnabled(enabled: Boolean) {
            onAutoTranslateLyricsChange(enabled)
            if (enabled) {
                val snapshot = currentLyricsState.value
                val text = snapshot?.lyrics
                if (snapshot != null && !text.isNullOrBlank() &&
                    text != LyricsEntity.LYRICS_NOT_FOUND &&
                    snapshot.source != LyricsEntity.Source.AI_TRANSLATION.value
                ) {
                    // Silent AI translation — the automatic path emits no toasts.
                    scope.launch {
                        lyricsMenuViewModel.translateLyricsWithAi(
                            mediaMetadata = mediaMetadata,
                            lyrics = text,
                            targetLanguage = targetLangState.value,
                        )
                    }
                }
            }
        }

        fun setRomanizationEnabled(enabled: Boolean) {
            onAiRomanizeLyricsChange(enabled)
            onAutoAiRomanizeLyricsChange(enabled)
        }

        var translationPanelOpen by remember { mutableStateOf(false) }

        // The translation panel is a lyrics-page affordance: it must never
        // survive a page switch or an auto-hide of the controls (otherwise it
        // floats over the album page with no way to dismiss it).
        LaunchedEffect(nowPage, showControl.value) {
            if (nowPage != FlamingoPage.Lyric || !showControl.value) {
                translationPanelOpen = false
            }
        }

        // ---- Overflow menu / anchored lyrics overflow (pre-redesign behaviour) ----
        val menuState = LocalMenuState.current
        var showAnchoredLyricsMenu by remember { mutableStateOf(false) }
        var moreIconBounds by remember { mutableStateOf(Rect.Zero) }

        val popupBackdrop: PlatformBackdrop? =
            if (rememberLiquidGlassEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                rememberBackdrop(Color.Transparent)
            } else {
                null
            }

        val onMoreClick = {
            if (nowPageLambda.value == FlamingoPage.Lyric) {
                showAnchoredLyricsMenu = true
            } else {
                menuState.show {
                    PlayerMenu(
                        mediaMetadata = mediaMetadata,
                        navController = navController,
                        playerBottomSheetState = state,
                        onShowDetailsDialog = {
                            mediaMetadata.id.let {
                                bottomSheetPageState.show {
                                    ShowMediaInfo(it)
                                }
                            }
                        },
                        onDismiss = menuState::dismiss,
                    )
                }
            }
        }

        // ---- Controls auto-hide (2500ms on the lyrics page) ----
        FlamingoWrapper {
            LaunchedEffect(
                showControlLambda.value,
                nowPageLambda.value,
                lastClickTime.longValue,
                translationPanelOpen,
            ) {
                if (nowPageLambda.value != FlamingoPage.Lyric && !showControlLambda.value) {
                    showControl.value = true
                }
                // The translation panel is open: the controls stay put until
                // the user manually closes it — no auto-hide beneath it (user
                // report 2026-10-07: "when I've opened it the player controls
                // shouldn't hide unless i manually close it").
                if (translationPanelOpen) return@LaunchedEffect
                if (showControlLambda.value) {
                    val time = 2500L
                    delay(time)
                    withContext(Dispatchers.Main) {
                        if (System.currentTimeMillis() - lastClickTime.longValue >= time &&
                            nowPageLambda.value == FlamingoPage.Lyric
                        ) {
                            showControl.value = false
                        }
                    }
                }
            }
        }

        // ---- Lyrics position provider ----
        val sliderPositionState = rememberUpdatedState(sliderPosition)
        val lyricsPosProvider = remember { { sliderPositionState.value } }

        // Everything the glass popups sample while the anchored lyrics menu OR
        // the translation panel is open (the panel's liquid-glass background
        // samples this layer too).
        val glassLayerActive = showAnchoredLyricsMenu || translationPanelOpen
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .let { base ->
                        if (popupBackdrop != null && glassLayerActive) {
                            base.layerBackdrop(popupBackdrop)
                        } else {
                            base
                        }
                    },
        ) {
            // ---- Background (color behind the artwork) ----
            FlamingoWrapper {
                Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    FlamingoFloatingLight(
                        albumUrl = { artworkUrl },
                        isPlaying = { isPlayingStatusLambda.value },
                        modifier = Modifier.fillMaxSize(),
                        nowPage = { nowPageLambda.value },
                        backgroundEffect = backgroundEffect,
                    )

                    // (No canvas backdrop here — see the constants block comment
                    // above for why the second, blurred CanvasArtworkPlayer was
                    // removed: interop layer escape above the controls, double
                    // decoder, and the tap-swallowing layout box.)

                    if (!videoShowing && canvasVisualActive) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .graphicsLayer { alpha = canvasScrimReveal }
                                .background(FlamingoCanvasScrimBrush),
                        )
                    }
                }
            }

            // ---- Content ----
            FlamingoWrapper {
                val translationButtonEnabled = remember("FlamingoNowPlaying_translationButtonEnabled") {
                    derivedStateOf {
                        showControlLambda.value && alphaAnim.value != 0f
                    }
                }

                if (landscape) {
                    // ---- Landscape (pre-redesign two-pane layout restored) ----
                    val pokeControls = {
                        if (!showControlLambda.value) {
                            showControl.value = true
                        }
                        lastClickTime.longValue = System.currentTimeMillis()
                    }

                    val landscapeSwipeModifier =
                        Modifier
                            // Touch ANYWHERE on the artwork pane pokes the hidden
                            // controls back into view (user report 2026-10-07: "in
                            // horizontal player layout the bottom controls should
                            // show even when I touch somewhere") — the swipe gesture
                            // below only reacts to drags, so a plain tap needs its
                            // own detector. The captured lambda only touches
                            // remember-backed State objects, so the Unit key is safe.
                            .pointerInput(Unit) {
                                detectTapGestures { pokeControls() }
                            }
                            .pointerInput(playerConnection) {
                                val swipeThresholdPx = 72.dp.toPx()
                                var accumulatedDrag = 0f
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        when {
                                            accumulatedDrag <= -swipeThresholdPx -> playerConnection.seekToNext()
                                            accumulatedDrag >= swipeThresholdPx -> playerConnection.seekToPrevious()
                                        }
                                        accumulatedDrag = 0f
                                    },
                                ) { change, dragAmount ->
                                    change.consume()
                                    accumulatedDrag += dragAmount
                                }
                            }

                    Row(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // Left pane: pure artwork/canvas stage (pre-redesign layout —
                        // the title lives in the right pane with the controls).
                        BoxWithConstraints(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                        ) {
                            val landscapeCanvasFullBleed = canvasActive && !videoShowing
                            if (landscapeCanvasFullBleed) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .then(landscapeSwipeModifier),
                                ) {
                                    FlamingoLandscapeStage(
                                        artworkUrl = artworkUrl,
                                        canvasPrimaryUrl = canvasPrimaryUrl,
                                        canvasFallbackUrl = canvasFallbackUrl,
                                        isPlaying = isPlaying,
                                        fullBleed = true,
                                        artworkSize = null,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            } else {
                                val landscapeArtworkSize =
                                    (maxWidth - 96.dp)
                                        .coerceAtMost(maxHeight * 0.68f)
                                        .coerceAtLeast(220.dp)

                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .then(landscapeSwipeModifier),
                                ) {
                                    FlamingoLandscapeStage(
                                        artworkUrl = artworkUrl,
                                        canvasPrimaryUrl = canvasPrimaryUrl,
                                        canvasFallbackUrl = canvasFallbackUrl,
                                        isPlaying = isPlaying,
                                        fullBleed = false,
                                        artworkSize = landscapeArtworkSize,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }

                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .pointerInput(Unit) {
                                        // Any tap on the right pane pokes the hidden
                                        // controls back into view (pre-redesign behaviour).
                                        awaitEachGesture {
                                            awaitFirstDown(requireUnconsumed = false)
                                            pokeControls()
                                        }
                                    },
                        ) {
                            // In-pane lyrics (enhanced lyrics animation library with
                            // AI translation / romanisation).
                            androidx.compose.animation.AnimatedVisibility(
                                visible = nowPage == FlamingoPage.Lyric,
                                enter = fadeIn(tween(400, easing = FastOutSlowInEasing)),
                                exit = fadeOut(tween(300, easing = FastOutSlowInEasing)),
                                modifier = Modifier.matchParentSize(),
                            ) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 16.dp),
                                ) {
                                    if (lyricsContentReady) {
                                        val isAiTranslated =
                                            currentLyrics?.source == LyricsEntity.Source.AI_TRANSLATION.value
                                        AnimatedContent(
                                            targetState = Triple(autoTranslateLyrics, romanizationOn, isAiTranslated),
                                            transitionSpec = {
                                                fadeIn(tween(360, easing = FastOutSlowInEasing)) togetherWith
                                                    fadeOut(tween(280, easing = FastOutSlowInEasing))
                                            },
                                            modifier = Modifier.fillMaxSize(),
                                            label = "FlamingoLandscapeLyricsRender",
                                        ) { renderKey ->
                                            val (showTranslationLines, showRomanization, _) = renderKey
                                            LyricsEnhanced(
                                                sliderPositionProvider = lyricsPosProvider,
                                                lyricsSyncOffset = lyricsSyncOffset,
                                                translationVisibleOverride = showTranslationLines,
                                                phoneticVisibleOverride = if (showRomanization) null else false,
                                                textColorOverride = Color.White,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }
                                }
                            }

                            // Controls: hidden while the lyrics pane is up until poked.
                            androidx.compose.animation.AnimatedVisibility(
                                visible = nowPage != FlamingoPage.Lyric || showControl.value,
                                enter = fadeIn(tween(120)),
                                exit = fadeOut(tween(100)),
                                modifier = Modifier.matchParentSize(),
                            ) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                onClick = pokeControls,
                                            ),
                                ) {
                                    Column(
                                        modifier =
                                            Modifier
                                                .fillMaxSize()
                                                .padding(bottom = contentBottomPadding),
                                        verticalArrangement = Arrangement.Bottom,
                                    ) {
                                        // Title block (pre-redesign layout: the title
                                        // lives above the controls in the right pane,
                                        // never pinned to the left screen edge).
                                        FlamingoLandscapeTitleBlock(
                                            mediaMetadata = mediaMetadata,
                                            currentSongLiked = currentSongLiked,
                                            playerConnection = playerConnection,
                                            navController = navController,
                                            state = state,
                                            bottomSheetPageState = bottomSheetPageState,
                                            onMoreClick = onMoreClick,
                                            onMorePositioned = { moreIconBounds = it },
                                        )

                                        FlamingoPlayerControl(
                                            isPlayingLambda = { isPlayingStatusLambda.value },
                                            playbackState = playbackState,
                                            positionProvider = positionProvider,
                                            durationProvider = { duration },
                                            playerConnection = playerConnection,
                                            currentFormat = currentFormat,
                                            showVolumeBar = showVolumeBar,
                                            volume = volume,
                                            onVolumeChange = onVolumeChange,
                                            nowPage = { nowPageLambda.value },
                                            onLyrics = {
                                                nowPage = if (nowPageLambda.value == FlamingoPage.Lyric) {
                                                    FlamingoPage.Album
                                                } else {
                                                    FlamingoPage.Lyric
                                                }
                                            },
                                            onPlaylist = {
                                                nowPage = if (nowPageLambda.value == FlamingoPage.PlayingList) {
                                                    FlamingoPage.Album
                                                } else {
                                                    FlamingoPage.PlayingList
                                                }
                                            },
                                            onSlider = {
                                                showControl.value = true
                                                lastClickTime.longValue = System.currentTimeMillis()
                                            },
                                            onSliderValueChange = onSliderValueChange,
                                            onSliderValueChangeFinished = onSliderValueChangeFinished,
                                            modifier = Modifier
                                                .padding(top = 8.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Queue page overlay (full screen in landscape)
                    FlamingoWrapper {
                        AnimatedVisibility(
                            visible = nowPage == FlamingoPage.PlayingList,
                            enter = fadeIn(tween(AnimDurationMillis)),
                            exit = fadeOut(tween(AnimDurationMillis)),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = topInset + 20.dp),
                        ) {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .clickable(enabled = false, onClick = {}),
                            ) {
                                FlamingoPlayingList(
                                    playerConnection = playerConnection,
                                    queueWindows = queueWindows,
                                    currentWindowIndex = currentWindowIndex,
                                    shuffleModeEnabled = shuffleModeEnabled,
                                    repeatMode = repeatMode,
                                )
                            }
                        }
                    }
                } else {
                    // ---- Portrait ----

                    // Lyrics page: fully removed from composition while the album
                    // page is showing. The enhanced-lyrics list runs per-frame
                    // scroll tracking; keeping it composed (even at alpha 0) made
                    // the whole player style feel laggy, so it now mounts/unmounts
                    // with the page like the pre-redesign player did.
                    FlamingoWrapper {
                        AnimatedVisibility(
                            visible = nowPage == FlamingoPage.Lyric,
                            enter = fadeIn(tween(400, easing = FastOutSlowInEasing)),
                            exit = fadeOut(tween(300, easing = FastOutSlowInEasing)),
                        ) {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        compositingStrategy = CompositingStrategy.ModulateAlpha
                                        this.alpha = alphaAnim.value
                                    },
                            ) {
                                Spacer(modifier = Modifier.height(topInset + 104.dp))

                                if (lyricsContentReady) {
                                    val isAiTranslated =
                                        currentLyrics?.source == LyricsEntity.Source.AI_TRANSLATION.value
                                    AnimatedContent(
                                        targetState = Triple(autoTranslateLyrics, romanizationOn, isAiTranslated),
                                        transitionSpec = {
                                            fadeIn(tween(360, easing = FastOutSlowInEasing)) togetherWith
                                                fadeOut(tween(280, easing = FastOutSlowInEasing))
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                        label = "FlamingoLyricsRender",
                                    ) { renderKey ->
                                        val (showTranslationLines, showRomanization, _) = renderKey
                                        LyricsEnhanced(
                                            sliderPositionProvider = lyricsPosProvider,
                                            lyricsSyncOffset = lyricsSyncOffset,
                                            translationVisibleOverride = showTranslationLines,
                                            phoneticVisibleOverride = if (showRomanization) null else false,
                                            textColorOverride = Color.White,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .flamingoLyricsEdgeFade(
                                                    weightLambda = { showControlLambda.value },
                                                ),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Drag handle (小把手)
                    FlamingoWrapper {
                        Column(Modifier.fillMaxWidth()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = topInset + 20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier
                                        .overlayEffect()
                                        .size(
                                            width = 32.dp,
                                            height = 4.5.dp,
                                        )
                                        .background(Color(0x4DFFFFFF), RoundedCornerShape(2.25.dp)),
                                )
                            }
                        }
                    }

                    // Main view: artwork page / playing bar pages, cross-faded with a
                    // shared-element artwork morph between them.
                    FlamingoWrapper {
                        SharedTransitionLayout {
                            AnimatedContent(
                                targetState = nowPage,
                                transitionSpec = {
                                    fadeIn() togetherWith fadeOut()
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = topInset + 22.dp),
                                label = "FlamingoPageCrossfade",
                            ) { page ->
                                when (page) {
                                    FlamingoPage.Album ->
                                        Column(
                                            Modifier
                                                .fillMaxSize()
                                                .clickable(enabled = false, onClick = {}),
                                        ) {
                                            FlamingoWrapper {
                                                Column(Modifier.fillMaxHeight(0.595f)) {
                                                    FlamingoAlbum(
                                                        modifier = Modifier.sharedBounds(
                                                            sharedContentState = rememberSharedContentState(
                                                                key = ShareAlbumKey,
                                                            ),
                                                            animatedVisibilityScope = this@AnimatedContent,
                                                            boundsTransform = BoundsTransform { _, _ ->
                                                                spring(
                                                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                                                    stiffness = Spring.StiffnessMediumLow,
                                                                )
                                                            },
                                                        ),
                                                        artworkUrl = artworkUrl,
                                                        isPlaying = { isPlayingStatusLambda.value },
                                                        canvasPrimaryUrl = canvasPrimaryUrl,
                                                        canvasFallbackUrl = canvasFallbackUrl,
                                                        orientationRefreshEpoch = orientationRefreshEpoch,
                                                    )
                                                    AnimatedContent(
                                                        targetState = mediaMetadata,
                                                        transitionSpec = {
                                                            fadeIn() togetherWith fadeOut()
                                                        },
                                                        modifier = Modifier.padding(horizontal = 32.dp),
                                                    ) { metadata ->
                                                        Row(
                                                            Modifier
                                                                .fillMaxWidth(),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                        ) {
                                                            Column(
                                                                Modifier
                                                                    .fillMaxWidth()
                                                                    .weight(1f)
                                                                    .padding(end = 15.dp),
                                                            ) {
                                                                Text(
                                                                    text = metadata.title,
                                                                    fontSize = 19.5.sp,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                    fontWeight = FontWeight.Medium,
                                                                )
                                                                Text(
                                                                    text = metadata.artistNames(),
                                                                    fontSize = 18.5.sp,
                                                                    modifier = Modifier.overlayEffect(),
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                    color = Color.White.copy(alpha = 0.35f),
                                                                )
                                                            }

                                                            FlamingoWrapper {
                                                                FlamingoActionButtonsRow(
                                                                    mediaMetadata = metadata,
                                                                    playerConnection = playerConnection,
                                                                    navController = navController,
                                                                    state = state,
                                                                    bottomSheetPageState = bottomSheetPageState,
                                                                    currentSongLiked = currentSongLiked,
                                                                    onMoreClick = onMoreClick,
                                                                    onMorePositioned = { moreIconBounds = it },
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                    FlamingoPage.Lyric ->
                                        Column(Modifier.fillMaxSize()) {
                                            FlamingoWrapper {
                                                FlamingoPlayingBar(
                                                    modifier = Modifier.sharedBounds(
                                                        sharedContentState = rememberSharedContentState(
                                                            key = ShareAlbumKey,
                                                        ),
                                                        animatedVisibilityScope = this@AnimatedContent,
                                                        boundsTransform = BoundsTransform { _, _ ->
                                                            spring(
                                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                                stiffness = Spring.StiffnessMediumLow,
                                                            )
                                                        },
                                                    ),
                                                    artworkUrl = artworkUrl,
                                                    mediaMetadata = mediaMetadata,
                                                    playerConnection = playerConnection,
                                                    navController = navController,
                                                    state = state,
                                                    bottomSheetPageState = bottomSheetPageState,
                                                    currentSongLiked = currentSongLiked,
                                                    onAlbumClick = { nowPage = FlamingoPage.Album },
                                                    onMoreClick = onMoreClick,
                                                    onMorePositioned = { moreIconBounds = it },
                                                )
                                            }
                                        }

                                    FlamingoPage.PlayingList ->
                                        FlamingoWrapper {
                                            Column(
                                                Modifier
                                                    .fillMaxSize()
                                                    .clickable(enabled = false, onClick = {}),
                                            ) {
                                                FlamingoPlayingBar(
                                                    modifier = Modifier.sharedBounds(
                                                        sharedContentState = rememberSharedContentState(
                                                            key = ShareAlbumKey,
                                                        ),
                                                        animatedVisibilityScope = this@AnimatedContent,
                                                        boundsTransform = BoundsTransform { _, _ ->
                                                            spring(
                                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                                stiffness = Spring.StiffnessMediumLow,
                                                            )
                                                        },
                                                    ),
                                                    artworkUrl = artworkUrl,
                                                    mediaMetadata = mediaMetadata,
                                                    playerConnection = playerConnection,
                                                    navController = navController,
                                                    state = state,
                                                    bottomSheetPageState = bottomSheetPageState,
                                                    currentSongLiked = currentSongLiked,
                                                    onAlbumClick = { nowPage = FlamingoPage.Album },
                                                    onMoreClick = onMoreClick,
                                                    onMorePositioned = { moreIconBounds = it },
                                                )
                                            }
                                        }
                                }
                            }
                        }
                    }

                    // Queue page overlay
                    FlamingoWrapper {
                        AnimatedVisibility(
                            visible = nowPage == FlamingoPage.PlayingList,
                            enter = fadeIn(tween(AnimDurationMillis)),
                            exit = fadeOut(tween(AnimDurationMillis)),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = topInset + 114.dp),
                        ) {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .clickable(enabled = false, onClick = {}),
                            ) {
                                FlamingoPlayingList(
                                    playerConnection = playerConnection,
                                    queueWindows = queueWindows,
                                    currentWindowIndex = currentWindowIndex,
                                    shuffleModeEnabled = shuffleModeEnabled,
                                    repeatMode = repeatMode,
                                )
                            }
                        }
                    }

                    // Player controls
                    FlamingoWrapper {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(top = topInset),
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            Box(
                                Modifier
                                    .fillMaxHeight(0.437f)
                                    .fillMaxWidth(),
                            ) {
                                FlamingoWrapper {
                                    // Lyrics-page tap catcher: restores the hidden
                                    // controls on tap. Never present on the album
                                    // page — the previous always-on variant sat on
                                    // top of the album title row and swallowed the
                                    // like/overflow button taps.
                                    if (nowPage == FlamingoPage.Lyric) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(top = 40.dp)
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null,
                                                    onClick = {
                                                        // Poke the controls back on tap.
                                                        showControl.value = true
                                                        lastClickTime.longValue =
                                                            System.currentTimeMillis()
                                                    },
                                                ),
                                        )
                                    }
                                }

                                FlamingoWrapper {
                                    // Outside-tap dismiss scrim for the translation
                                    // panel: consumes stray taps in the controls
                                    // region and closes the panel (the panel itself
                                    // composes above this catcher).
                                    if (translationPanelOpen) {
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null,
                                                    onClick = {
                                                        translationPanelOpen = false
                                                        showControl.value = true
                                                        lastClickTime.longValue =
                                                            System.currentTimeMillis()
                                                    },
                                                ),
                                        )
                                    }
                                }

                                FlamingoWrapper {
                                    Column(
                                        Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.Bottom,
                                    ) {
                                        AnimatedVisibility(
                                            visible = showControl.value,
                                            enter = fadeIn() + expandVertically(
                                                expandFrom = Alignment.Top,
                                                initialHeight = { (it / 1.4).toInt() },
                                            ),
                                            exit = fadeOut() + shrinkVertically(
                                                shrinkTowards = Alignment.Top,
                                                targetHeight = { (it / 1.4).toInt() },
                                            ),
                                        ) {
                                            FlamingoWrapper {
                                                // Translation options panel: expands above the
                                                // bottom controls with two toggles — translation
                                                // and romanisation (both AI, silent). Liquid
                                                // glass blur background with a single divider
                                                // line between the rows (user spec 2026-10-07),
                                                // translucent — never fully opaque — and it
                                                // consumes taps so touches never fall through
                                                // to the lyrics behind it.
                                                AnimatedVisibility(
                                                    visible = translationPanelOpen,
                                                    enter = fadeIn(tween(220)) + expandVertically(
                                                        expandFrom = Alignment.Bottom,
                                                    ) + scaleIn(
                                                        initialScale = 0.85f,
                                                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f),
                                                    ),
                                                    exit = fadeOut(tween(160)) + shrinkVertically(
                                                        shrinkTowards = Alignment.Bottom,
                                                    ) + scaleOut(
                                                        targetScale = 0.85f,
                                                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f),
                                                    ),
                                                ) {
                                                    // Liquid-glass backdrop: samples the player
                                                    // content behind the panel (the same
                                                    // popupBackdrop the anchored lyrics menu
                                                    // uses) with vibrancy + blur. Translucent
                                                    // charcoal when glass is unavailable
                                                    // (pre-S / liquid glass toggle off).
                                                    val panelGlassModifier =
                                                        remember(popupBackdrop) {
                                                            if (popupBackdrop != null) {
                                                                Modifier.drawBackdrop(
                                                                    backdrop = popupBackdrop,
                                                                    effects = {
                                                                        vibrancy()
                                                                        blur(28f.dp.toPx())
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
                                                    val panelTintAlpha = if (panelGlassModifier != null) 0.24f else 0.72f

                                                    Box(
                                                        contentAlignment = Alignment.CenterEnd,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = 32.dp)
                                                            .padding(bottom = 6.dp),
                                                    ) {
                                                        Column(
                                                            horizontalAlignment = Alignment.End,
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(16.dp))
                                                                .then(
                                                                    if (panelGlassModifier != null) {
                                                                        panelGlassModifier
                                                                            .background(Color.Black.copy(alpha = panelTintAlpha))
                                                                    } else {
                                                                        Modifier
                                                                            .background(Color.Black.copy(alpha = panelTintAlpha))
                                                                    },
                                                                )
                                                                .border(
                                                                    width = 1.dp,
                                                                    color = Color.White.copy(alpha = 0.10f),
                                                                    shape = RoundedCornerShape(16.dp),
                                                                )
                                                                .clickable(
                                                                    interactionSource = remember { MutableInteractionSource() },
                                                                    indication = null,
                                                                    onClick = {},
                                                                )
                                                                .padding(horizontal = 8.dp, vertical = 8.dp),
                                                        ) {
                                                            FlamingoTranslationOptionRow(
                                                                label = stringResource(R.string.flamingo_translation_option),
                                                                active = autoTranslateLyrics,
                                                                onClick = {
                                                                    FlamingoHaptics.click(context)
                                                                    setTranslationEnabled(!autoTranslateLyrics)
                                                                },
                                                            )
                                                            // The single divider line between the two
                                                            // options (replaces the old spacer gap).
                                                            Box(
                                                                modifier = Modifier
                                                                    .padding(horizontal = 12.dp)
                                                                    .fillMaxWidth()
                                                                    .height(1.dp)
                                                                    .background(Color.White.copy(alpha = 0.16f)),
                                                            )
                                                            FlamingoTranslationOptionRow(
                                                                label = stringResource(R.string.flamingo_romanisation_option),
                                                                active = romanizationOn,
                                                                onClick = {
                                                                    FlamingoHaptics.click(context)
                                                                    setRomanizationEnabled(!romanizationOn)
                                                                },
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            FlamingoWrapper {
                                                Row(
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 32.dp)
                                                        .graphicsLayer {
                                                            compositingStrategy =
                                                                CompositingStrategy.ModulateAlpha
                                                            this.alpha = alphaAnim.value
                                                        },
                                                    horizontalArrangement = Arrangement.End,
                                                ) {
                                                    FlamingoWrapper {
                                                        Box(
                                                            modifier = Modifier
                                                                .overlayEffect()
                                                                .alpha(0.4f)
                                                                .clickable(
                                                                    enabled = translationButtonEnabled.value,
                                                                    onClick = {
                                                                        FlamingoHaptics.click(context)
                                                                        translationPanelOpen = !translationPanelOpen
                                                                        showControl.value = true
                                                                        lastClickTime.longValue =
                                                                            System.currentTimeMillis()
                                                                    },
                                                                    indication = null,
                                                                    interactionSource = remember { MutableInteractionSource() },
                                                                ),
                                                            contentAlignment = Alignment.Center,
                                                        ) {
                                                            AnimatedContent(
                                                                targetState = autoTranslateLyrics || romanizationOn,
                                                                transitionSpec = {
                                                                    fadeIn() togetherWith fadeOut()
                                                                },
                                                            ) { translationOn ->
                                                                if (translationOn) {
                                                                    Icon(
                                                                        painterResource(id = R.drawable.flamingo_np_translateon),
                                                                        contentDescription = null,
                                                                        tint = Color.Unspecified,
                                                                        modifier = Modifier
                                                                            .size(30.dp),
                                                                    )
                                                                } else {
                                                                    Icon(
                                                                        painterResource(id = R.drawable.flamingo_np_translate),
                                                                        contentDescription = null,
                                                                        tint = Color.Unspecified,
                                                                        modifier = Modifier
                                                                            .size(30.dp),
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            FlamingoPlayerControl(
                                                isPlayingLambda = { isPlayingStatusLambda.value },
                                                playbackState = playbackState,
                                                positionProvider = positionProvider,
                                                durationProvider = { duration },
                                                playerConnection = playerConnection,
                                                currentFormat = currentFormat,
                                                showVolumeBar = showVolumeBar,
                                                volume = volume,
                                                onVolumeChange = onVolumeChange,
                                                nowPage = { nowPageLambda.value },
                                                onLyrics = {
                                                    translationPanelOpen = false
                                                    nowPage = if (nowPageLambda.value == FlamingoPage.Lyric) {
                                                        FlamingoPage.Album
                                                    } else {
                                                        FlamingoPage.Lyric
                                                    }
                                                },
                                                onPlaylist = {
                                                    translationPanelOpen = false
                                                    nowPage = if (nowPageLambda.value == FlamingoPage.PlayingList) {
                                                        FlamingoPage.Album
                                                    } else {
                                                        FlamingoPage.PlayingList
                                                    }
                                                },
                                                onSlider = {
                                                    showControl.value = true
                                                    lastClickTime.longValue = System.currentTimeMillis()
                                                },
                                                onSliderValueChange = onSliderValueChange,
                                                onSliderValueChangeFinished = onSliderValueChangeFinished,
                                                modifier = Modifier
                                                    .padding(top = 52.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Anchored lyrics overflow popup (pre-redesign animation/design/behaviour;
            // translate/undo/romanise rows are intentionally absent in this style).
        }

        if (showAnchoredLyricsMenu) {
            AnchoredLyricsOverflowMenu(
                iconBoundsInRoot = moreIconBounds,
                lyricsProvider = { currentLyrics },
                mediaMetadataProvider = { mediaMetadata },
                lyricsSyncOffset = lyricsSyncOffset,
                onLyricsSyncOffsetChange = onLyricsSyncOffsetChange,
                onDismiss = { showAnchoredLyricsMenu = false },
                backdrop = popupBackdrop,
                showTranslationActions = false,
            )
        }
    }
}

/** Edge fade mask for the lyrics list (ported from the Flamingo lyric view). */
private fun Modifier.flamingoLyricsEdgeFade(
    weightLambda: () -> Boolean,
): Modifier = drawWithCache {
    val overlayPaint = Paint().apply {
        blendMode = BlendMode.Plus
    }
    val rect = androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)

    onDrawWithContent {
        val canvas = this.drawContext.canvas
        canvas.saveLayer(rect, overlayPaint)

        val colors = if (weightLambda()) {
            listOf(
                Color.Transparent,
                Color(0x59000000),
                Color.Black,
                Color.Black,
                Color.Black,
                Color.Black,
                Color.Black,
                Color.Black,
                Color(0x59000000),
                Color(0x21000000),
                Color.Transparent,
                Color.Transparent,
                Color.Transparent,
                Color.Transparent,
                Color.Transparent,
                Color.Transparent,
                Color.Transparent,
                Color.Transparent,
            )
        } else {
            listOf(
                Color.Transparent,
                Color(0x59000000),
                Color.Black,
                Color.Black,
                Color.Black,
                Color.Black,
                Color.Black,
                Color.Black,
                Color.Black,
                Color.Black,
                Color.Black,
                Color.Black,
                Color.Black,
                Color.Black,
                Color.Black,
                Color.Black,
                Color.Black,
                Color.Black,
            )
        }

        drawContent()

        drawRect(
            brush = Brush.verticalGradient(colors),
            blendMode = BlendMode.DstIn,
        )

        canvas.restore()
    }
}

@Composable
private fun FlamingoTranslationOptionRow(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val backgroundAlpha by animateFloatAsState(
        targetValue = if (active) 0.30f else 0.14f,
        label = "FlamingoTranslationOptionBackground",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width(186.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = backgroundAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = if (active) 1f else 0.62f),
            modifier = Modifier.weight(1f),
        )
        val indicatorAlpha by animateFloatAsState(
            targetValue = if (active) 1f else 0.28f,
            label = "FlamingoTranslationOptionIndicator",
        )
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(
                    Color.White.copy(alpha = indicatorAlpha),
                    RoundedCornerShape(50),
                ),
        )
    }
}

@Composable
private fun ColumnScope.FlamingoAlbum(
    modifier: Modifier,
    artworkUrl: String?,
    isPlaying: () -> Boolean,
    canvasPrimaryUrl: String?,
    canvasFallbackUrl: String?,
    orientationRefreshEpoch: Int,
) {
    val hasCanvas = !canvasPrimaryUrl.isNullOrBlank() || !canvasFallbackUrl.isNullOrBlank()

    Box(
        Modifier
            .weight(1f)
            .then(
                if (hasCanvas) {
                    // The canvas stage fills the whole area up to the title row.
                    Modifier
                } else {
                    Modifier
                        .padding(top = 20.dp)
                        .padding(horizontal = 15.dp)
                        .padding(bottom = 33.dp)
                },
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (hasCanvas) {
            // ---- Sharp canvas stage ----
            // The canvas fills this whole area (the artwork stage, edge to
            // edge, ZOOM-cropped), with the static artwork underneath as the
            // buffering/failure base. There is deliberately NO offscreen
            // compositing around the video: the previous DstIn fade wrapper
            // (CompositingStrategy.Offscreen) made the TextureView escape
            // the layer, so the canvas never rendered inside the stage and
            // floated above the player controls instead (user report
            // 2026-10-07). The bottom blend is a plain gradient scrim drawn
            // ON TOP of the video.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .then(modifier),
            ) {
                AsyncImage(
                    model = artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )

                CanvasArtworkPlayer(
                    primaryUrl = canvasPrimaryUrl,
                    fallbackUrl = canvasFallbackUrl,
                    isPlaying = isPlaying(),
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                    refreshEpoch = orientationRefreshEpoch,
                    modifier = Modifier.matchParentSize(),
                )

                // Stage bottom dissolve (overlay scrim — no offscreen layer).
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(FlamingoSharpStageBottomScrim),
                )
            }
        } else {
            val springSpec: AnimationSpec<Float> = remember("FlamingoAlbum_springSpec") {
                SpringSpec(stiffness = 300f, dampingRatio = 1f, visibilityThreshold = 0.001f)
            }

            val tweenSpec: AnimationSpec<Float> = remember("FlamingoAlbum_tweenSpec") {
                TweenSpec(durationMillis = 350, easing = EaseOutQuart)
            }

            val scale = animateFloatAsState(
                targetValue = if (isPlaying()) 0f else 1f,
                animationSpec = if (isPlaying()) springSpec else tweenSpec,
                visibilityThreshold = 0.001f,
            )

            FlamingoWrapper {
                val dp = (7 + (27 * scale.value)).dp
                ShadowImageWithCache(
                    dataLambda = { artworkUrl },
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                        .padding(start = dp, end = dp, bottom = dp)
                        .then(modifier),
                    imageQuality = ImageQuality.RAW,
                    shadowOverlay = true,
                )
            }
        }
    }
}

/**
 * Landscape artwork pane: full-bleed canvas (RESIZE_MODE_FIT + right-edge fade,
 * pre-redesign behaviour) or the centred squircle artwork.
 */
@Composable
private fun FlamingoLandscapeStage(
    artworkUrl: String?,
    canvasPrimaryUrl: String?,
    canvasFallbackUrl: String?,
    isPlaying: Boolean,
    fullBleed: Boolean,
    artworkSize: Dp?,
    modifier: Modifier = Modifier,
) {
    val hasCanvas = !canvasPrimaryUrl.isNullOrBlank() || !canvasFallbackUrl.isNullOrBlank()

    Box(modifier = modifier) {
        if (fullBleed && hasCanvas) {
            // The canvas fills the whole pane (ZOOM-cropped, edge to edge) so
            // nothing of the static artwork shows behind it (user report
            // 2026-10-07: "the canvas plays in horizontal mode but there's a
            // static thumbnail of that song behind that song too"). The static
            // artwork + dim stay underneath ONLY as the buffering/failure
            // placeholder and crossfade away once the video renders its first
            // frame. No offscreen compositing around the TextureView (same
            // interop rule as the portrait stage); the right-edge blend is a
            // plain gradient scrim drawn on top.
            var canvasRendering by remember(canvasPrimaryUrl, canvasFallbackUrl) {
                mutableStateOf(false)
            }
            val staticBaseAlpha by animateFloatAsState(
                targetValue = if (canvasRendering) 0f else 1f,
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                label = "flamingoLandscapeStaticCrossfade",
            )

            Box(
                modifier = Modifier.matchParentSize(),
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = staticBaseAlpha },
                ) {
                    AsyncImage(
                        model = artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.55f)),
                    )
                }

                CanvasArtworkPlayer(
                    primaryUrl = canvasPrimaryUrl,
                    fallbackUrl = canvasFallbackUrl,
                    isPlaying = isPlaying,
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                    onPlaybackAvailabilityChange = { canvasRendering = it },
                    modifier = Modifier.matchParentSize(),
                )

                // Right-edge blend toward the controls pane (overlay scrim).
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(FlamingoLandscapeRightScrim),
                )
            }
        } else {
            val springSpec: AnimationSpec<Float> = remember("FlamingoLandscapeStage_springSpec") {
                SpringSpec(stiffness = 300f, dampingRatio = 1f, visibilityThreshold = 0.001f)
            }
            val tweenSpec: AnimationSpec<Float> = remember("FlamingoLandscapeStage_tweenSpec") {
                TweenSpec(durationMillis = 350, easing = EaseOutQuart)
            }
            val scale = animateFloatAsState(
                targetValue = if (isPlaying) 0f else 1f,
                animationSpec = if (isPlaying) springSpec else tweenSpec,
                visibilityThreshold = 0.001f,
            )
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                FlamingoWrapper {
                    val dp = (7 + (27 * scale.value)).dp
                    ShadowImageWithCache(
                        dataLambda = { artworkUrl },
                        contentDescription = null,
                        modifier = Modifier
                            .size(artworkSize ?: 220.dp)
                            .graphicsLayer {
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            }
                            .padding(start = dp, end = dp, bottom = dp),
                        imageQuality = ImageQuality.RAW,
                        shadowOverlay = true,
                    )
                }
            }
        }
    }
}

/**
 * Landscape title block: the Flamingo title typography plus the like/overflow
 * chips, rendered above the controls in the right pane (pre-redesign layout).
 */
@Composable
private fun FlamingoLandscapeTitleBlock(
    mediaMetadata: MediaMetadata,
    currentSongLiked: Boolean,
    playerConnection: PlayerConnection,
    navController: NavController,
    state: BottomSheetState,
    bottomSheetPageState: BottomSheetPageState,
    onMoreClick: () -> Unit,
    onMorePositioned: (Rect) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(
                text = mediaMetadata.title,
                fontSize = 19.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = mediaMetadata.artistNames(),
                fontSize = 18.5.sp,
                modifier = Modifier.overlayEffect(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White.copy(alpha = 0.35f),
            )
        }

        FlamingoActionButtonsRow(
            mediaMetadata = mediaMetadata,
            playerConnection = playerConnection,
            navController = navController,
            state = state,
            bottomSheetPageState = bottomSheetPageState,
            currentSongLiked = currentSongLiked,
            onMoreClick = onMoreClick,
            onMorePositioned = onMorePositioned,
        )
    }
}

private data class FlamingoQueueDragInfo(
    val draggedItemUid: Any?,
    val destinationUid: Any?,
)

@Composable
private fun FlamingoPlayingList(
    playerConnection: PlayerConnection,
    queueWindows: List<Timeline.Window>,
    currentWindowIndex: Int,
    shuffleModeEnabled: Boolean,
    repeatMode: Int,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localPlayer = playerConnection.localPlayer

    Spacer(modifier = Modifier.height(12.dp))

    val upNextCount = (queueWindows.size - currentWindowIndex - 1).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.545f),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 30.dp)
                .padding(top = 10.dp)
                .height(65.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Text(
                    text = stringResource(id = R.string.flamingo_queue_header_title),
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(id = R.string.flamingo_queue_header_count, upNextCount),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .overlayEffect()
                        .alpha(0.35f),
                )
            }

            Row(
                modifier = Modifier
                    .overlayEffect()
                    .alpha(0.6f),
            ) {
                val dp = 36.dp
                FlamingoWrapper {
                    val shuffleBackgroundAlpha =
                        animateFloatAsState(targetValue = if (shuffleModeEnabled) 0.9f else 0f)
                    Box(
                        modifier = Modifier
                            .clickable(
                                onClick = {
                                    FlamingoHaptics.click(context)
                                    playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled
                                },
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            )
                            .size(36.dp)
                            .background(
                                Color.White.copy(alpha = shuffleBackgroundAlpha.value),
                                shape = FlamingoSmoothCornerShape(10.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        FlamingoWrapper {
                            val shuffleIconTint =
                                animateColorAsState(targetValue = if (shuffleModeEnabled) Color.Black else Color.White)
                            Icon(
                                painterResource(id = R.drawable.flamingo_np_shuffle),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(dp),
                                tint = shuffleIconTint.value,
                            )
                        }
                    }
                }
                FlamingoWrapper {
                    val repeatHighlight =
                        repeatMode == REPEAT_MODE_ALL || repeatMode == REPEAT_MODE_ONE
                    val repeatBackgroundAlpha =
                        animateFloatAsState(targetValue = if (repeatHighlight) 0.9f else 0f)
                    Box(
                        modifier = Modifier
                            .clickable(
                                onClick = {
                                    FlamingoHaptics.click(context)
                                    val targetMode = when (repeatMode) {
                                        REPEAT_MODE_OFF -> REPEAT_MODE_ALL
                                        REPEAT_MODE_ALL -> REPEAT_MODE_ONE
                                        else -> REPEAT_MODE_OFF
                                    }
                                    playerConnection.player.repeatMode = targetMode
                                },
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            )
                            .padding(start = 10.dp)
                            .size(36.dp)
                            .background(
                                Color.White.copy(alpha = repeatBackgroundAlpha.value),
                                shape = FlamingoSmoothCornerShape(10.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        FlamingoWrapper {
                            AnimatedContent(targetState = repeatMode, transitionSpec = {
                                fadeIn() togetherWith fadeOut()
                            }) { mode ->
                                when (mode) {
                                    REPEAT_MODE_ONE -> Icon(
                                        painterResource(id = R.drawable.flamingo_np_repeatone),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(dp),
                                        tint = animateColorAsState(
                                            targetValue = if (repeatHighlight) Color.Black else Color.White,
                                        ).value,
                                    )

                                    else -> Icon(
                                        painterResource(id = R.drawable.flamingo_np_repeat),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(dp),
                                        tint = animateColorAsState(
                                            targetValue = if (repeatHighlight) Color.Black else Color.White,
                                        ).value,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (upNextCount == 0) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.flamingo_queue_empty),
                    contentDescription = null,
                    modifier = Modifier
                        .overlayEffect()
                        .size(70.dp)
                        .alpha(0.6f),
                )
                Text(
                    text = stringResource(id = R.string.flamingo_queue_empty_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(top = 18.dp, bottom = 12.dp),
                )
                FlamingoWrapper {
                    Text(
                        text = stringResource(id = R.string.flamingo_queue_empty_desc),
                        fontSize = 16.sp,
                        color = Color.White,
                        modifier = Modifier
                            .overlayEffect()
                            .alpha(0.4f),
                    )
                }
            }
        } else {
            val state = rememberLazyListState(
                initialFirstVisibleItemIndex = 0,
                initialFirstVisibleItemScrollOffset = -15,
            )
            val mutableQueueWindows = remember { mutableStateListOf<Timeline.Window>() }

            val currentPlayingUid =
                remember(currentWindowIndex, queueWindows) {
                    if (currentWindowIndex in queueWindows.indices) {
                        queueWindows[currentWindowIndex].uid
                    } else {
                        null
                    }
                }

            var dragInfo by remember { mutableStateOf<FlamingoQueueDragInfo?>(null) }
            var justCommittedDragUid by remember { mutableStateOf<Any?>(null) }

            val reorderableState =
                rememberReorderableLazyListState(
                    lazyListState = state,
                ) onMove@{ from, to ->
                    // LazyColumn layout: 0 = blank_before, 1 = section header, items at 2..
                    val fromMirror = from.index - 2
                    val toMirror = to.index - 2
                    if (fromMirror !in mutableQueueWindows.indices ||
                        toMirror !in mutableQueueWindows.indices
                    ) {
                        return@onMove
                    }

                    val draggedItemUid =
                        dragInfo?.draggedItemUid ?: mutableQueueWindows[fromMirror].uid
                    val actualFromQueueIndex =
                        mutableQueueWindows.indexOfFirst { it.uid == draggedItemUid }
                    if (actualFromQueueIndex == -1) return@onMove

                    mutableQueueWindows.move(actualFromQueueIndex, toMirror)

                    val destinationUid: Any? =
                        if (toMirror == 0) {
                            currentPlayingUid
                        } else {
                            mutableQueueWindows.getOrNull(toMirror - 1)?.uid
                        }
                    dragInfo = FlamingoQueueDragInfo(draggedItemUid, destinationUid)
                }

            // Drag haptics (port of Flamingo's draggableHandle callbacks, which
            // the newer reorderable library no longer exposes).
            LaunchedEffect(reorderableState) {
                androidx.compose.runtime.snapshotFlow { reorderableState.isAnyItemDragging }
                    .collect { dragging ->
                        if (dragging) {
                            FlamingoHaptics.longClick(context)
                        } else {
                            FlamingoHaptics.click(context)
                        }
                    }
            }

            LaunchedEffect(queueWindows, currentWindowIndex, reorderableState.isAnyItemDragging) {
                if (reorderableState.isAnyItemDragging) return@LaunchedEffect

                val completedDrag = dragInfo
                if (completedDrag != null) {
                    val sourceIndex =
                        queueWindows.indexOfFirst { it.uid == completedDrag.draggedItemUid }
                    val destinationAnchorIndex =
                        queueWindows.indexOfFirst { it.uid == completedDrag.destinationUid }
                    dragInfo = null

                    if (sourceIndex != -1) {
                        val destinationIndex =
                            if (destinationAnchorIndex == -1) {
                                currentWindowIndex + 1
                            } else if (sourceIndex < destinationAnchorIndex) {
                                destinationAnchorIndex
                            } else {
                                (destinationAnchorIndex + 1).coerceAtMost(queueWindows.lastIndex)
                            }

                        if (sourceIndex != destinationIndex) {
                            justCommittedDragUid = completedDrag.draggedItemUid
                            if (!shuffleModeEnabled) {
                                playerConnection.player.moveMediaItem(sourceIndex, destinationIndex)
                            } else {
                                localPlayer.setShuffleOrder(
                                    DefaultShuffleOrder(
                                        queueWindows
                                            .map { it.firstPeriodIndex }
                                            .toMutableList()
                                            .move(sourceIndex, destinationIndex)
                                            .toIntArray(),
                                        System.currentTimeMillis(),
                                    ),
                                )
                            }
                            return@LaunchedEffect
                        }
                    }
                }

                if (justCommittedDragUid != null) {
                    justCommittedDragUid = null
                    return@LaunchedEffect
                }

                Snapshot.withMutableSnapshot {
                    mutableQueueWindows.clear()
                    val startIndex = currentWindowIndex + 1
                    mutableQueueWindows.addAll(queueWindows.drop(startIndex))
                }
            }

            FlamingoWrapper {
                LazyColumn(
                    state = state,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithCache {
                            onDrawWithContent {
                                val colors = listOf(
                                    Color.Transparent,
                                    Color.Black,
                                    Color.Black,
                                    Color.Black,
                                    Color.Black,
                                    Color.Black,
                                    Color.Black,
                                    Color.Black,
                                    Color.Black,
                                    Color.Black,
                                    Color.Black,
                                    Color.Black,
                                    Color.Black,
                                    Color.Black,
                                    Color.Black,
                                    Color.Transparent,
                                )

                                drawContent()

                                drawRect(
                                    brush = Brush.verticalGradient(colors),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                        },
                ) {
                    item("blank_before") {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    item("up_next_header") {
                        QueueSectionHeader(title = stringResource(id = R.string.flamingo_queue_up_next))
                    }

                    itemsIndexed(
                        items = mutableQueueWindows,
                        key = { _, window -> window.uid.hashCode() },
                    ) { _, window ->
                        ReorderableItem(reorderableState, key = window.uid.hashCode()) { isDragging ->
                            FlamingoQueueMusicListItem(
                                window = window,
                                queueWindows = queueWindows,
                                currentWindowIndex = currentWindowIndex,
                                reorderEnabled = mutableQueueWindows.size > 1,
                                isDragging = isDragging,
                                reorderHandleModifier = Modifier.draggableHandle(),
                                onMoveToNextQueue = {
                                    val absIndex =
                                        queueWindows.indexOfFirst { it.uid == window.uid }
                                    if (absIndex > 0) {
                                        playerConnection.player.moveMediaItem(
                                            absIndex,
                                            currentWindowIndex + 1,
                                        )
                                        true
                                    } else {
                                        false
                                    }
                                },
                                onRemove = {
                                    val absIndex =
                                        queueWindows.indexOfFirst { it.uid == window.uid }
                                    if (absIndex != -1) {
                                        playerConnection.player.removeMediaItem(absIndex)
                                        true
                                    } else {
                                        false
                                    }
                                },
                            ) {
                                val absIndex =
                                    queueWindows.indexOfFirst { it.uid == window.uid }
                                if (absIndex != -1) {
                                    playerConnection.player.seekToDefaultPosition(absIndex)
                                    playerConnection.player.playWhenReady = true
                                }
                            }
                        }
                    }

                    item("blank_after") {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueSectionHeader(
    title: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 30.dp)
            .padding(top = 12.dp, bottom = 6.dp)
            .overlayEffect(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FlamingoQueueMusicListItem(
    window: Timeline.Window,
    queueWindows: List<Timeline.Window>,
    currentWindowIndex: Int,
    reorderEnabled: Boolean,
    isDragging: Boolean,
    reorderHandleModifier: Modifier,
    onMoveToNextQueue: (suspend () -> Boolean)?,
    onRemove: (suspend () -> Boolean)?,
    itemClick: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val addedToQueueToast = stringResource(id = R.string.flamingo_queue_added_toast)
    val music = window.mediaItem.metadata
    var rowWidthPx by remember(window.uid) {
        mutableFloatStateOf(0f)
    }
    var rowHeightPx by remember(window.uid) {
        mutableFloatStateOf(0f)
    }
    var swipeOffsetPx by remember(window.uid) {
        mutableFloatStateOf(0f)
    }
    var deleteHeightPx by remember(window.uid) {
        mutableFloatStateOf(0f)
    }
    var resetAnimationJob by remember(window.uid) {
        mutableStateOf<Job?>(null)
    }
    var deleteAnimating by remember(window.uid) {
        mutableStateOf(false)
    }
    var deleteCollapsing by remember(window.uid) {
        mutableStateOf(false)
    }

    val swipeRightEnabled = onMoveToNextQueue != null
    val swipeLeftEnabled = onRemove != null
    val triggerOffsetPx = rowWidthPx * 0.20f
    val minSwipeOffsetPx = if (swipeLeftEnabled) -rowWidthPx else 0f
    val maxSwipeOffsetPx = if (swipeRightEnabled) rowWidthPx else 0f
    val absoluteSwipeOffsetPx = if (swipeOffsetPx < 0f) -swipeOffsetPx else swipeOffsetPx
    val swipeProgress = if (rowWidthPx > 0f) {
        (absoluteSwipeOffsetPx / rowWidthPx).coerceIn(0f, 1f)
    } else {
        0f
    }
    val rowHeight = with(density) {
        rowHeightPx.toDp()
    }
    val swipeRevealWidth = with(density) {
        absoluteSwipeOffsetPx.toDp()
    }
    val deleteHeight = with(density) {
        deleteHeightPx.coerceAtLeast(0f).toDp()
    }

    val swipeModifier = if ((swipeRightEnabled || swipeLeftEnabled) && !deleteAnimating) {
        Modifier.draggable(
            orientation = Orientation.Horizontal,
            state = rememberDraggableState { delta ->
                if (rowWidthPx <= 0f || deleteAnimating) {
                    return@rememberDraggableState
                }

                resetAnimationJob?.cancel()

                val wasPastAddThreshold = swipeOffsetPx >= triggerOffsetPx
                val wasPastDeleteThreshold = swipeOffsetPx <= -triggerOffsetPx
                swipeOffsetPx = (swipeOffsetPx + delta).coerceIn(minSwipeOffsetPx, maxSwipeOffsetPx)
                val isPastAddThreshold = swipeOffsetPx >= triggerOffsetPx
                val isPastDeleteThreshold = swipeOffsetPx <= -triggerOffsetPx

                if (wasPastAddThreshold != isPastAddThreshold || wasPastDeleteThreshold != isPastDeleteThreshold) {
                    if (isPastAddThreshold || isPastDeleteThreshold) {
                        FlamingoHaptics.longClick(context)
                    } else {
                        FlamingoHaptics.click(context)
                    }
                }
            },
            onDragStopped = {
                val shouldMoveToNextQueue = triggerOffsetPx > 0f && swipeOffsetPx >= triggerOffsetPx
                val shouldRemove = triggerOffsetPx > 0f && swipeOffsetPx <= -triggerOffsetPx

                resetAnimationJob?.cancel()
                resetAnimationJob = coroutineScope.launch {
                    if (shouldRemove && onRemove != null) {
                        deleteAnimating = true
                        deleteHeightPx = if (rowHeightPx > 0f) {
                            rowHeightPx
                        } else {
                            with(density) { QueueRowHeight.toPx() }
                        }

                        androidx.compose.animation.core.animate(
                            initialValue = swipeOffsetPx,
                            targetValue = -rowWidthPx,
                            animationSpec = tween(
                                durationMillis = 110,
                                easing = EaseOutQuart,
                            ),
                        ) { value, _ ->
                            swipeOffsetPx = value
                        }

                        swipeOffsetPx = 0f
                        deleteCollapsing = true

                        androidx.compose.animation.core.animate(
                            initialValue = deleteHeightPx,
                            targetValue = 0f,
                            animationSpec = tween(
                                durationMillis = 170,
                                easing = EaseOutQuart,
                            ),
                        ) { value, _ ->
                            deleteHeightPx = value
                        }

                        onRemove.invoke()
                        return@launch
                    }

                    if (shouldMoveToNextQueue && onMoveToNextQueue?.invoke() == true) {
                        Toast.makeText(context, addedToQueueToast, Toast.LENGTH_SHORT).show()
                    }

                    val animationStart = swipeOffsetPx

                    androidx.compose.animation.core.animate(
                        initialValue = animationStart,
                        targetValue = 0f,
                        animationSpec = SpringSpec(
                            dampingRatio = 0.72f,
                            stiffness = 420f,
                            visibilityThreshold = 0.5f,
                        ),
                    ) { value, _ ->
                        swipeOffsetPx = value
                    }
                }
            },
        )
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging || deleteAnimating) 1f else 0f)
            .then(if (deleteCollapsing) Modifier.height(deleteHeight) else Modifier)
            .onSizeChanged {
                rowWidthPx = it.width.toFloat()
                if (!deleteCollapsing) {
                    rowHeightPx = it.height.toFloat()
                }
            },
    ) {
        if (deleteCollapsing) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(deleteHeight)
                    .background(Color(0xFFD32F2F)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.flamingo_swipe_delete),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        } else if (swipeRightEnabled && swipeOffsetPx > 0f) {
            Box(
                modifier = Modifier
                    .width(swipeRevealWidth)
                    .height(rowHeight)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.flamingo_swipe_queue),
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier
                        .size(28.dp)
                        .graphicsLayer {
                            val iconScale = 0.82f + (swipeProgress * 0.18f)
                            scaleX = iconScale
                            scaleY = iconScale
                        },
                )
            }
        }

        if (!deleteCollapsing && swipeLeftEnabled && swipeOffsetPx < 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(swipeRevealWidth)
                    .height(rowHeight)
                    .background(Color(0xFFD32F2F)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.flamingo_swipe_delete),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(28.dp)
                        .graphicsLayer {
                            val iconScale = 0.82f + (swipeProgress * 0.18f)
                            scaleX = iconScale
                            scaleY = iconScale
                        },
                )
            }
        }

        if (!deleteCollapsing) {
            FlamingoSmallMusicListItem(
                music = music,
                reorderEnabled = reorderEnabled,
                isDragging = isDragging,
                modifier = Modifier
                    .graphicsLayer {
                        translationX = swipeOffsetPx
                    }
                    .then(swipeModifier),
                reorderHandleModifier = reorderHandleModifier,
                itemClick = itemClick,
            )
        }
    }
}

@Composable
private fun FlamingoSmallMusicListItem(
    music: MediaMetadata?,
    reorderEnabled: Boolean,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
    reorderHandleModifier: Modifier,
    itemClick: () -> Unit,
) {
    val draggedItemBackground by animateColorAsState(
        targetValue = if (isDragging) {
            Color.White.copy(alpha = 0.08f)
        } else {
            Color.Transparent
        },
        label = "FlamingoQueueDraggedItemBackground",
    )
    Surface(
        modifier = modifier
            .height(QueueRowHeight)
            .fillMaxWidth(),
        color = draggedItemBackground,
        contentColor = Color.White,
        shape = QueueDraggingItemShape,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clickable {
                    itemClick()
                }
                .padding(horizontal = 30.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShadowImageWithCache(
                dataLambda = { music?.thumbnailUrl },
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                cornerRadius = 4.dp,
                shadowAlpha = 0f,
                imageQuality = ImageQuality.LOW,
            )

            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp, end = 12.dp),
            ) {
                Text(
                    text = music?.title ?: "",
                    modifier = Modifier.padding(bottom = 1.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 16.sp,
                    lineHeight = 16.sp,
                )

                Text(
                    text = music?.artistNames() ?: "",
                    modifier = Modifier.alpha(0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.5.sp,
                    lineHeight = 11.5.sp,
                )
            }

            if (reorderEnabled) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .then(reorderHandleModifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.flamingo_queue_reorder),
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.34f),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FlamingoActionButtonsRow(
    mediaMetadata: MediaMetadata,
    playerConnection: PlayerConnection,
    navController: NavController,
    state: BottomSheetState,
    bottomSheetPageState: BottomSheetPageState,
    currentSongLiked: Boolean,
    onMoreClick: () -> Unit,
    onMorePositioned: (Rect) -> Unit = {},
) {
    Row(
        modifier = Modifier
            .overlayEffect(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dp = 28.dp

        val context = LocalContext.current
        val menuState = LocalMenuState.current

        Box(
            modifier = Modifier
                .clickable(
                    onClick = {
                        FlamingoHaptics.click(context)
                        playerConnection.toggleLike()
                    },
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                )
                .size(dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = currentSongLiked,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
            ) { liked ->
                if (liked) {
                    Icon(
                        painterResource(id = R.drawable.flamingo_np_favorited),
                        contentDescription = null,
                        modifier = Modifier
                            .size(dp),
                    )
                } else {
                    Icon(
                        painterResource(id = R.drawable.flamingo_np_favorite),
                        contentDescription = null,
                        modifier = Modifier
                            .overlayEffect()
                            .size(dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Box(
            modifier = Modifier
                .clickable(
                    onClick = onMoreClick,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                )
                .let { base ->
                    if (onMorePositioned != null) {
                        base.onGloballyPositioned { coords ->
                            onMorePositioned(coords.boundsInRoot())
                        }
                    } else {
                        base
                    }
                }
                .size(dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = menuState.isVisible,
                transitionSpec = {
                    fadeIn(animationSpec = tween(durationMillis = 300)) togetherWith
                        fadeOut(animationSpec = tween(durationMillis = 300))
                },
            ) { menuOpen ->
                if (menuOpen) {
                    Icon(
                        painterResource(id = R.drawable.flamingo_np_more_fill),
                        contentDescription = null,
                        modifier = Modifier
                            .size(dp),
                    )
                } else {
                    Icon(
                        painterResource(id = R.drawable.flamingo_np_more),
                        contentDescription = null,
                        modifier = Modifier
                            .overlayEffect()
                            .size(dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FlamingoPlayingBar(
    modifier: Modifier,
    artworkUrl: String?,
    mediaMetadata: MediaMetadata,
    playerConnection: PlayerConnection,
    navController: NavController,
    state: BottomSheetState,
    bottomSheetPageState: BottomSheetPageState,
    currentSongLiked: Boolean,
    onAlbumClick: () -> Unit,
    onMoreClick: () -> Unit,
    onMorePositioned: (Rect) -> Unit,
) = FlamingoWrapper {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.5.dp)
            .padding(top = 22.dp)
            .height(70.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShadowImageWithCache(
            dataLambda = { artworkUrl },
            contentDescription = null,
            modifier = modifier
                .size(69.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {
                        onAlbumClick()
                    },
                ),
            cornerRadius = 5.dp,
            imageQuality = ImageQuality.LOW,
            shadowType = ShadowType.Small,
            shadowOverlay = true,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = 12.dp, end = 15.dp),
        ) {
            Text(
                text = mediaMetadata.title,
                fontSize = 16.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
                lineHeight = 16.5.sp,
            )
            Text(
                text = mediaMetadata.artistNames(),
                fontSize = 15.sp,
                modifier = Modifier.overlayEffect(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White.copy(alpha = 0.35f),
            )
        }

        FlamingoWrapper {
            FlamingoActionButtonsRow(
                mediaMetadata = mediaMetadata,
                playerConnection = playerConnection,
                navController = navController,
                state = state,
                bottomSheetPageState = bottomSheetPageState,
                currentSongLiked = currentSongLiked,
                onMoreClick = onMoreClick,
                onMorePositioned = onMorePositioned,
            )
        }
    }
}

@Composable
private fun RowScope.FlamingoAirPlay() {
    val contextCompose = LocalContext.current
    val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    val connectedDevices =
        remember("FlamingoAirPlay_connectedDevices") { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    val audioDeviceName = remember("FlamingoAirPlay_audioDeviceName") { mutableStateOf("") }
    val showName = remember("FlamingoAirPlay_showName") { mutableStateOf(false) }

    FlamingoWrapper {
        DisposableEffect(Unit) {
            val filter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED).apply {
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val action = intent?.action

                    if (action == BluetoothDevice.ACTION_ACL_CONNECTED ||
                        action == BluetoothDevice.ACTION_ACL_DISCONNECTED
                    ) {
                        if (ActivityCompat.checkSelfPermission(
                                contextCompose,
                                Manifest.permission.BLUETOOTH_CONNECT,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            return
                        }
                        connectedDevices.value =
                            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()

                        val thisName =
                            connectedDevices.value.firstOrNull {
                                it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO &&
                                    it.isConnectedCompat()
                            }?.aliasCompat()
                        showName.value = thisName != null
                        if (thisName != null) {
                            audioDeviceName.value = thisName.trim()
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(
                contextCompose,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )

            if (ActivityCompat.checkSelfPermission(
                    contextCompose,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                connectedDevices.value = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
                val thisName =
                    connectedDevices.value.firstOrNull {
                        it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO &&
                            it.isConnectedCompat()
                    }?.aliasCompat()
                showName.value = thisName != null
                if (thisName != null) {
                    audioDeviceName.value = thisName.trim()
                }
            }

            onDispose {
                runCatching {
                    contextCompose.unregisterReceiver(receiver)
                }
            }
        }
    }

    FlamingoWrapper {
        val context = LocalContext.current

        val castAction = rememberCastPlayerMenuAction()
        val onOutputClick: () -> Unit = castAction?.onClick ?: {
            runCatching {
                context.startActivity(Intent("android.settings.panel.action.MEDIA_OUTPUT"))
            }
        }

        val navBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        Column(
            modifier = Modifier
                .heightIn(min = 53.dp)
                .height(navBottomInset + 48.dp)
                .weight(1f)
                .clickable(
                    onClick = onOutputClick,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.height(36.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = showName.value,
                    transitionSpec = {
                        (scaleIn(initialScale = 0.3f) + fadeIn()).togetherWith(
                            scaleOut(
                                targetScale = 0.3f,
                            ) + fadeOut(),
                        )
                    },
                    contentAlignment = Alignment.Center,
                ) { connected ->
                    if (connected) {
                        Icon(
                            painterResource(id = R.drawable.flamingo_earphone),
                            contentDescription = null,
                            modifier = Modifier
                                .size(27.dp),
                        )
                    } else {
                        Icon(
                            painterResource(id = R.drawable.flamingo_np_airplay),
                            contentDescription = null,
                            modifier = Modifier
                                .size(21.5.dp),
                        )
                    }
                }
            }

            AnimatedVisibility(
                showName.value,
                enter = scaleIn(initialScale = 0.3f) + fadeIn(),
                exit = scaleOut(targetScale = 0.3f) + fadeOut(),
            ) {
                Text(
                    text = audioDeviceName.value,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    lineHeight = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun BluetoothDevice.isConnectedCompat(): Boolean {
    return runCatching {
        val isConnectedMethod =
            BluetoothDevice::class.java.getMethod("isConnected")
        isConnectedMethod.isAccessible = true
        isConnectedMethod.invoke(this) as Boolean
    }.getOrDefault(false)
}

private fun BluetoothDevice.aliasCompat(): String? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { alias }.getOrNull() ?: name
    } else {
        name
    }
}

@Composable
private fun FlamingoPlayerControl(
    isPlayingLambda: () -> Boolean,
    playbackState: Int,
    positionProvider: () -> Long,
    durationProvider: () -> Long,
    playerConnection: PlayerConnection,
    currentFormat: FormatEntity?,
    showVolumeBar: Boolean,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    nowPage: () -> String,
    onLyrics: () -> Unit,
    onPlaylist: () -> Unit,
    onSlider: () -> Unit,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    modifier: Modifier,
) {
    val playingDuration = rememberSaveable(key = "FlamingoPlayerControl_playingDuration") {
        mutableLongStateOf(0L)
    }
    val playingPosition = rememberSaveable(key = "FlamingoPlayerControl_playingPosition") {
        mutableLongStateOf(0L)
    }
    val context = LocalContext.current
    val playedTime = rememberSaveable(key = "FlamingoPlayerControl_playedTime") { mutableStateOf("0:00") }
    val remainingTime =
        rememberSaveable(key = "FlamingoPlayerControl_remainingTime") { mutableStateOf("-0:00") }
    val sliderPosition = remember("FlamingoPlayerControl_sliderPosition") { mutableFloatStateOf(0f) }
    val isSliding = remember("FlamingoPlayerControl_isSliding") {
        mutableStateOf(false)
    }

    val latestDurationProvider = rememberUpdatedState(durationProvider)
    val latestPositionProvider = rememberUpdatedState(positionProvider)

    val player = playerConnection.player

    FlamingoWrapper {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 25.dp)
                .padding(bottom = 15.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FlamingoWrapper {
                FlamingoWrapper {
                    val lifecycleState =
                        LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()

                    LaunchedEffect(Unit) {
                        var lastPosition = 0L
                        while (true) {
                            if (lifecycleState.value.isAtLeast(Lifecycle.State.RESUMED)) {
                                playingDuration.longValue = latestDurationProvider.value()
                                playingPosition.longValue = latestPositionProvider.value()

                                if (!isSliding.value && playingDuration.longValue > 0L) {
                                    val totalSeconds =
                                        playingPosition.longValue.coerceAtLeast(0) / 1000
                                    if (totalSeconds != lastPosition) {
                                        playedTime.value = formatTime(totalSeconds)

                                        sliderPosition.floatValue =
                                            playingPosition.longValue.coerceAtLeast(0).toFloat()

                                        val remainingSeconds =
                                            playingDuration.longValue.coerceAtLeast(0) / 1000 - totalSeconds
                                        remainingTime.value = "-${formatTime(remainingSeconds)}"
                                        lastPosition = totalSeconds
                                    }
                                }
                            }

                            delay(1000)
                        }
                    }
                }

                // Progress slider
                FlamingoWrapper {
                    Slider(
                        value = sliderPosition.floatValue,
                        onValueChange = { newValue ->
                            isSliding.value = true

                            sliderPosition.floatValue = newValue
                            val newTotalSeconds = newValue.toLong() / 1000
                            playedTime.value = formatTime(newTotalSeconds)

                            val newRemainingSeconds =
                                playingDuration.longValue / 1000 - newTotalSeconds
                            remainingTime.value = "-${formatTime(newRemainingSeconds)}"

                            onSliderValueChange(newValue.toLong())
                            onSlider()
                        },
                        onValueChangeFinished = {
                            FlamingoHaptics.longClick(context)
                            player.seekTo(sliderPosition.floatValue.toLong())
                            onSliderValueChangeFinished()
                            isSliding.value = false
                        },
                        valueRange = 0f..playingDuration.longValue.toFloat().coerceAtLeast(0f),
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color(0x0DFFFFFF),
                        ),
                        modifier = Modifier
                            .overlayEffect()
                            .alpha(0.45f)
                            .height(14.dp),
                        thumb = {
                            Spacer(modifier = Modifier.size(0.dp))
                        },
                        track = { _ ->
                            FlamingoTrack(
                                activeFraction = if (playingDuration.longValue > 0L) {
                                    (sliderPosition.floatValue / playingDuration.longValue).coerceIn(0f, 1f)
                                } else {
                                    0f
                                },
                                height = 7.dp,
                            )
                        },
                    )
                }

                // Time labels & transport buttons
                FlamingoWrapper {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 7.dp)
                            .height(22.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = playedTime.value,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.3.sp,
                                color = Color.White.copy(alpha = 0.3f),
                                modifier = Modifier.overlayEffect(),
                            )
                            Text(
                                text = remainingTime.value,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.3.sp,
                                color = Color.White.copy(alpha = 0.3f),
                                modifier = Modifier.overlayEffect(),
                            )
                        }

                        FlamingoQualityIndicator(currentFormat = currentFormat)
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(61.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple(bounded = false),
                                        onClick = {
                                            FlamingoHaptics.click(context)
                                            player.seekToPreviousMediaItem()
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painterResource(id = R.drawable.flamingo_np_rewind),
                                    contentDescription = "Previous",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(10.dp),
                                )
                            }

                            Spacer(modifier = Modifier.width(43.dp))

                            Box(
                                modifier = Modifier
                                    .size(58.5.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple(bounded = false),
                                        onClick = {
                                            FlamingoHaptics.click(context)
                                            if (playbackState == Player.STATE_ENDED) {
                                                player.seekTo(0, 0)
                                                player.playWhenReady = true
                                            } else {
                                                player.playWhenReady = !player.playWhenReady
                                            }
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                AnimatedContent(
                                    targetState = isPlayingLambda(),
                                    transitionSpec = {
                                        (scaleIn(initialScale = 0.3f) + fadeIn()).togetherWith(
                                            scaleOut(
                                                targetScale = 0.3f,
                                            ) + fadeOut(),
                                        )
                                    },
                                ) { playing ->
                                    if (playing) {
                                        Icon(
                                            painterResource(id = R.drawable.flamingo_np_pause),
                                            contentDescription = "Pause",
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(10.dp),
                                        )
                                    } else {
                                        Icon(
                                            painterResource(id = R.drawable.flamingo_np_play),
                                            contentDescription = "Play",
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(9.dp),
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(43.dp))
                            Box(
                                modifier = Modifier
                                    .size(61.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple(bounded = false),
                                        onClick = {
                                            FlamingoHaptics.click(context)
                                            player.seekToNextMediaItem()
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painterResource(id = R.drawable.flamingo_np_fforward),
                                    contentDescription = "Next",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(10.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Volume
            FlamingoWrapper {
                if (showVolumeBar) {
                    FlamingoVolumeSlider(
                        volume = volume,
                        onVolumeChange = onVolumeChange,
                        onSlider = onSlider,
                    )
                }
            }

            // Bottom lyrics & queue bar
            FlamingoWrapper {
                Row(
                    modifier = Modifier
                        .overlayEffect()
                        .fillMaxWidth()
                        .alpha(0.4f),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    val dp = 32.dp
                    Box(
                        modifier = Modifier
                            .height(36.dp)
                            .weight(1f)
                            .clickable(
                                onClick = { onLyrics() },
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedContent(
                            targetState = nowPage() == FlamingoPage.Lyric,
                            transitionSpec = {
                                fadeIn() togetherWith fadeOut()
                            },
                        ) { lyricsOn ->
                            if (lyricsOn) {
                                Icon(
                                    painterResource(id = R.drawable.flamingo_np_lyricson),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(dp),
                                )
                            } else {
                                Icon(
                                    painterResource(id = R.drawable.flamingo_np_lyrics),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(dp),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(0.1f))

                    FlamingoAirPlay()

                    Spacer(modifier = Modifier.weight(0.1f))

                    Box(
                        modifier = Modifier
                            .height(36.dp)
                            .weight(1f)
                            .clickable(
                                onClick = { onPlaylist() },
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedContent(
                            targetState = nowPage() == FlamingoPage.PlayingList,
                            transitionSpec = {
                                fadeIn() togetherWith fadeOut()
                            },
                        ) { queueOn ->
                            if (queueOn) {
                                Icon(
                                    painterResource(id = R.drawable.flamingo_np_queueon),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(dp),
                                )
                            } else {
                                Icon(
                                    painterResource(id = R.drawable.flamingo_np_queue),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlamingoVolumeSlider(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onSlider: () -> Unit,
) {
    val context = LocalContext.current
    val sliderPosition =
        remember("FlamingoVolumeSlider_sliderPosition") { mutableFloatStateOf(volume) }
    val sliding = remember("FlamingoVolumeSlider_sliding") {
        mutableStateOf(false)
    }

    LaunchedEffect(volume) {
        if (!sliding.value) {
            sliderPosition.floatValue = volume
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(end = 1.5.dp)
            .padding(horizontal = 8.dp)
            .padding(top = 4.dp, bottom = 2.5.dp)
            .overlayEffect()
            .alpha(0.45f),
    ) {
        Icon(
            painter = painterResource(id = R.drawable.flamingo_np_volume),
            contentDescription = "Mute",
            modifier = Modifier.size(20.dp),
        )

        FlamingoWrapper {
            val animatedProgress = if (sliding.value) {
                sliderPosition
            } else {
                animateFloatAsState(
                    targetValue = sliderPosition.floatValue,
                    animationSpec = androidx.compose.material3.ProgressIndicatorDefaults.ProgressAnimationSpec,
                    visibilityThreshold = 0.0001f,
                )
            }

            Slider(
                value = animatedProgress.value,
                onValueChange = { newValue ->
                    sliding.value = true
                    sliderPosition.floatValue = newValue
                    onVolumeChange(newValue)
                    onSlider()
                },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color(0x0DFFFFFF),
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 1.5.dp, end = 5.dp),
                thumb = {
                    Spacer(modifier = Modifier.size(0.dp))
                },
                track = { _ ->
                    FlamingoTrack(
                        activeFraction = animatedProgress.value.coerceIn(0f, 1f),
                        height = 7.dp,
                    )
                },
                onValueChangeFinished = {
                    FlamingoHaptics.longClick(context)
                    sliding.value = false
                },
            )
        }
        Icon(
            painter = painterResource(id = R.drawable.flamingo_np_volume_full),
            contentDescription = "Max Volume",
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun FlamingoTrack(
    activeFraction: Float,
    modifier: Modifier = Modifier,
    height: Dp,
) = FlamingoWrapper {
    val inactiveTrackColor = Color.White.copy(alpha = 0.5f)
    val activeTrackColor = Color.White
    val inactiveTickColor = Color.White.copy(alpha = 0.5f)
    val activeTickColor = Color.White
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val isRtl = layoutDirection == LayoutDirection.Rtl
        val sliderLeft = Offset(0f, center.y)
        val sliderRight = Offset(size.width, center.y)
        val sliderStart = if (isRtl) sliderRight else sliderLeft
        val sliderEnd = if (isRtl) sliderLeft else sliderRight
        val tickSize = 2.0.dp.toPx()
        val trackStrokeWidth = height.toPx()
        drawLine(
            inactiveTrackColor,
            sliderStart,
            sliderEnd,
            trackStrokeWidth,
            StrokeCap.Round,
        )
        val sliderValueEnd = Offset(
            sliderStart.x +
                (sliderEnd.x - sliderStart.x) * activeFraction,
            center.y,
        )

        val sliderValueStart = Offset(
            sliderStart.x +
                (sliderEnd.x - sliderStart.x) * 0f,
            center.y,
        )

        drawLine(
            activeTrackColor,
            sliderValueStart,
            sliderValueEnd,
            trackStrokeWidth,
            StrokeCap.Round,
        )
    }
}

@Composable
private fun FlamingoQualityIndicator(
    currentFormat: FormatEntity?,
) {
    if (currentFormat == null) return

    val musicBitrateKbps = currentFormat.bitrate / 1000
    val musicSamplingRate = currentFormat.sampleRate ?: 0

    val isLossless = currentFormat.isLossless() ||
        (musicBitrateKbps >= 700 && musicSamplingRate >= 44100)
    val isHiRes = musicBitrateKbps >= 2000 && musicSamplingRate >= 96000

    // The pill shows for every quality (pre-redesign behaviour): lossless and
    // hi-res badges, and the codec label (OPUS / AAC / VORBIS / …) otherwise.
    val qualityLabel =
        remember(currentFormat) {
            when {
                isHiRes -> "Hi-Res"
                isLossless -> null // resolved via stringResource below
                else -> currentFormat.codecLabel()
            }
        }
    val text = qualityLabel ?: stringResource(id = R.string.flamingo_quality_lossless)

    Box(
        Modifier
            .fillMaxWidth()
            .height(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .overlayEffect()
                .background(
                    color = Color(0x14FFFFFF),
                    shape = FlamingoSmoothCornerShape(5.dp),
                )
                .height(20.dp)
                .padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(
                        if (isHiRes || isLossless) {
                            R.drawable.flamingo_quality_lossless
                        } else {
                            R.drawable.player_graphic_eq
                        },
                    ),
                    contentDescription = "quality_badge",
                    modifier = Modifier
                        .height(if (isHiRes || isLossless) 9.dp else 10.dp)
                        .alpha(0.45f),
                )
                Spacer(modifier = Modifier.width(5.dp))

                AnimatedContent(
                    targetState = text,
                    transitionSpec = {
                        fadeIn() togetherWith fadeOut()
                    },
                ) { thisText ->
                    Text(
                        text = thisText,
                        modifier = Modifier.alpha(0.45f),
                        fontSize = 11.sp,
                        lineHeight = 11.sp,
                    )
                }
            }
        }
    }
}
