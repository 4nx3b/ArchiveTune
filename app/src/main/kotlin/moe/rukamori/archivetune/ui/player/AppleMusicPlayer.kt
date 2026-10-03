/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * "Apple Music" player design: edge-to-edge artwork on top with a blurred continuation of the
 * artwork behind the lower controls (progressive-blur look), bold white title/artist with star and
 * "more" chips, a thin scrubber with elapsed/-remaining times, bare oversized transport glyphs, a
 * flat volume slider, and a bottom lyrics / output / queue icon row. Everything is tinted by the
 * artwork itself (no palette extraction needed — the blur provides the color).
 */

package moe.rukamori.archivetune.ui.player

import moe.rukamori.archivetune.utils.oem.SystemMediaControlResolver
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.OverlayClip
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import kotlin.math.abs
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size as CoilSize
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AutoTranslateExcludedLanguagesKey
import moe.rukamori.archivetune.constants.AutoHideLyricsPlayerControlsKey
import moe.rukamori.archivetune.constants.AutoTranslateLyricsKey
import moe.rukamori.archivetune.constants.LyricsMode
import moe.rukamori.archivetune.constants.LyricsModeKey
import moe.rukamori.archivetune.constants.ShowLyricsPlayerControlsKey
import moe.rukamori.archivetune.constants.ThumbnailCornerRadiusKey
import moe.rukamori.archivetune.constants.TranslatorTargetLangKey
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.db.entities.codecLabel
import moe.rukamori.archivetune.db.entities.isLossless
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.ui.component.BottomSheetPageState
import moe.rukamori.archivetune.ui.component.rememberLiquidGlassEnabled
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.LyricsEnhanced
import moe.rukamori.archivetune.ui.component.LyricsV2
import moe.rukamori.archivetune.ui.component.glassSource
import moe.rukamori.archivetune.ui.component.rememberThrottledBackdrop
import moe.rukamori.archivetune.ui.menu.AnchoredLyricsOverflowMenu
import moe.rukamori.archivetune.ui.menu.PlayerMenu
import moe.rukamori.archivetune.ui.menu.rememberCastPlayerMenuAction
import moe.rukamori.archivetune.ui.utils.ShowMediaInfo
import moe.rukamori.archivetune.ui.utils.highRes
import moe.rukamori.archivetune.utils.ImageBlurUtils
import moe.rukamori.archivetune.utils.isLocalMediaId
import moe.rukamori.archivetune.utils.makeTimeString
import moe.rukamori.archivetune.utils.rememberLowDataModeActive
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.LyricsMenuViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

private val AppleMusicContentPadding = 28.dp
private val AppleMusicChipSize = 34.dp
private val AppleMusicTransportIconSize = 48.dp
private val AppleMusicPlayPauseIconSize = 80.dp

private val AppleMusicPlayPauseSpinnerSize = 48.dp

private val AppleMusicBottomIconSize = 26.dp
private val AppleMusicBottomButtonSize = 48.dp
private val AppleMusicMiniArtworkSize = 56.dp

private const val AmLyricsBlurDriftScale = 2.4f

private const val AmCoverBlurScale = 1.2f

private const val AmLandscapeDriftFactor = 0.55f

private const val AmLyricsBackdropMorphMs = 650

private val AmBackdropBlurRadius = 64.dp

private const val AmCanvasBackdropUpscale = 6f

private val AmCanvasBackdropBlurRadius = 72.dp

private fun Modifier.canvasSnapshotSource(
    graphicsLayer: GraphicsLayer,
    minIntervalMillis: Long,
): Modifier = this then CanvasSnapshotSourceElement(graphicsLayer, minIntervalMillis)

private class CanvasSnapshotSourceElement(
    val graphicsLayer: GraphicsLayer,
    val minIntervalMillis: Long,
) : ModifierNodeElement<CanvasSnapshotSourceNode>() {
    override fun create() = CanvasSnapshotSourceNode(graphicsLayer, minIntervalMillis)

    override fun update(node: CanvasSnapshotSourceNode) {
        if (node.graphicsLayer !== graphicsLayer || node.minIntervalMillis != minIntervalMillis) {
            node.graphicsLayer = graphicsLayer
            node.minIntervalMillis = minIntervalMillis
            node.lastRecordUptimeMillis = 0L
        }
        node.invalidateDraw()
    }

    override fun equals(other: Any?): Boolean =
        other is CanvasSnapshotSourceElement &&
            other.graphicsLayer === graphicsLayer &&
            other.minIntervalMillis == minIntervalMillis

    override fun hashCode(): Int =
        graphicsLayer.hashCode() * 31 + minIntervalMillis.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        name = "canvasSnapshotSource"
        properties["minIntervalMillis"] = minIntervalMillis
    }
}

private class CanvasSnapshotSourceNode(
    var graphicsLayer: GraphicsLayer,
    var minIntervalMillis: Long,
) : DrawModifierNode, Modifier.Node() {
    var lastRecordUptimeMillis = 0L

    var isRecording = false

    override fun ContentDrawScope.draw() {
        val now = SystemClock.uptimeMillis()
        if (!isRecording && now - lastRecordUptimeMillis >= minIntervalMillis) {
            lastRecordUptimeMillis = now
            isRecording = true
            try {
                runCatching {
                    graphicsLayer.record(size.toIntSize()) {
                        this@draw.drawContent()
                    }
                }
            } finally {
                isRecording = false
            }
        }

    }
}

private const val AmCanvasBackdropMaxVideoEdgePx = 256

private const val AmCanvasSnapshotIntervalMs = 50L

private const val AppleMusicLyricsContentDeferMs = 160L

private const val AppleMusicLyricsControlsAutoHideDelayMs = 5_000L

private const val ControlsGesturePokeThrottleMs = 1_000L

private fun shouldAutoHideAppleMusicControls(
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    autoHideEnabled: Boolean,
): Boolean = (lyricsOpen || queueOpen) && autoHideEnabled

private class AdaptiveCornerShape(
    private val smallRadius: Dp,
    private val smallSize: Dp,
    private val largeRadius: Dp,
    private val largeSize: Dp,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val elementSize = minOf(size.width, size.height)
        val smallSizePx = with(density) { smallSize.toPx() }
        val largeSizePx = with(density) { largeSize.toPx() }
        val t =
            if (largeSizePx > smallSizePx) {
                ((elementSize - smallSizePx) / (largeSizePx - smallSizePx)).coerceIn(0f, 1f)
            } else {
                1f
            }
        val smallRadiusPx = with(density) { smallRadius.toPx() }
        val largeRadiusPx = with(density) { largeRadius.toPx() }
        val radius = smallRadiusPx + (largeRadiusPx - smallRadiusPx) * t
        return Outline.Rounded(
            RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                cornerRadius = CornerRadius(radius, radius),
            ),
        )
    }
}

private enum class AppleMusicPlayerState { COVER, QUEUE, LYRICS }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppleMusicPlayerContent(
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
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    lyricsSyncOffset: Int = 0,
    onLyricsSyncOffsetChange: (Int) -> Unit = {},

    onLyricsVisibilityChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
    orientationRefreshEpoch: Int = 0,
) {
    val canvasLoopSync = remember { CanvasLoopSync() }

    var queueOpen by remember { mutableStateOf(false) }

    var lyricsOpen by remember(landscape) { mutableStateOf(landscape) }

    LaunchedEffect(mediaMetadata.id) { lyricsOpen = landscape }

    val lyricsMode by rememberEnumPreference(LyricsModeKey, defaultValue = LyricsMode.ENHANCED)

    val animationsDisabled = LocalAnimationsDisabled.current

    val toggleQueue = {
        lyricsOpen = false
        queueOpen = !queueOpen
    }
    val toggleLyrics = {
        queueOpen = false
        lyricsOpen = !lyricsOpen
    }

    val morphOpen = queueOpen || lyricsOpen
    androidx.activity.compose.BackHandler(enabled = morphOpen) {
        if (lyricsOpen) lyricsOpen = false
        if (queueOpen) queueOpen = false
    }

    val morphState =
        when {
            queueOpen -> AppleMusicPlayerState.QUEUE
            lyricsOpen -> AppleMusicPlayerState.LYRICS
            else -> AppleMusicPlayerState.COVER
        }

    val restoreCover = {
        queueOpen = false
        lyricsOpen = false
    }

    val showLyricsPlayerControlsState = rememberPreference(ShowLyricsPlayerControlsKey, defaultValue = true)
    val showLyricsPlayerControls by showLyricsPlayerControlsState
    val (autoHideLyricsPlayerControls, onAutoHideLyricsPlayerControlsChange) =
        rememberPreference(AutoHideLyricsPlayerControlsKey, defaultValue = true)

    var playerControlsExpanded by remember { mutableStateOf(!landscape) }
    var controlsRevealToken by remember { mutableIntStateOf(0) }
    val autoHideDelayMs = AppleMusicLyricsControlsAutoHideDelayMs
    val playerExpanded = state.isExpanded

    LaunchedEffect(lyricsOpen, queueOpen, controlsRevealToken, autoHideLyricsPlayerControls, showLyricsPlayerControls, playerExpanded) {
        if (landscape && lyricsOpen && controlsRevealToken == 0) {
            playerControlsExpanded = false
            return@LaunchedEffect
        }
        playerControlsExpanded = true
        if (!shouldAutoHideAppleMusicControls(lyricsOpen, queueOpen, autoHideLyricsPlayerControls)) {
            return@LaunchedEffect
        }
        if (!playerExpanded) {
            return@LaunchedEffect
        }
        if (lyricsOpen && !showLyricsPlayerControls) {
            return@LaunchedEffect
        }
        delay(autoHideDelayMs)
        playerControlsExpanded = false
    }

    val pokePlayerControlsVisibility: () -> Unit = remember { { controlsRevealToken++ } }

    val lastGesturePokeMs = remember { longArrayOf(0L) }
    val pokePlayerControlsVisibilityThrottled: () -> Unit =
        remember {
            {
                val now = SystemClock.uptimeMillis()
                if (now - lastGesturePokeMs[0] >= ControlsGesturePokeThrottleMs) {
                    lastGesturePokeMs[0] = now
                    controlsRevealToken++
                }
            }
        }
    val onControlsSliderValueChange: (Long) -> Unit =
        remember(onSliderValueChange) {
            { position ->
                pokePlayerControlsVisibilityThrottled()
                onSliderValueChange(position)
            }
        }
    val onControlsSliderValueChangeFinished: () -> Unit =
        remember(onSliderValueChangeFinished) {
            {
                pokePlayerControlsVisibility()
                onSliderValueChangeFinished()
            }
        }
    val onControlsVolumeChange: (Float) -> Unit =
        remember(onVolumeChange) {
            { newVolume ->
                pokePlayerControlsVisibilityThrottled()
                onVolumeChange(newVolume)
            }
        }

    LaunchedEffect(lyricsOpen) {
        onLyricsVisibilityChange(lyricsOpen)
    }
    DisposableEffect(Unit) {
        onDispose { onLyricsVisibilityChange(false) }
    }

    var wasPlayerExpandedForMorph by remember { mutableStateOf(false) }
    LaunchedEffect(playerExpanded) {
        if (wasPlayerExpandedForMorph && !playerExpanded) {
            if (lyricsOpen) lyricsOpen = false
            if (queueOpen) queueOpen = false
        }
        wasPlayerExpandedForMorph = playerExpanded
    }

    var canvasVisibleForLyrics by remember { mutableStateOf(true) }
    LaunchedEffect(lyricsOpen) {
        if (lyricsOpen && !landscape) {
            canvasVisibleForLyrics = true
            delay(AmLyricsBackdropMorphMs.toLong())
            canvasVisibleForLyrics = false
        } else {
            canvasVisibleForLyrics = true
        }
    }

    var lyricsBackdropActive by remember { mutableStateOf(false) }
    LaunchedEffect(lyricsOpen) {
        if (lyricsOpen) {
            lyricsBackdropActive = true
        } else {
            delay(AmLyricsBackdropMorphMs.toLong())
            lyricsBackdropActive = false
        }
    }
    var lyricsContentReady by remember { mutableStateOf(false) }
    LaunchedEffect(lyricsOpen) {
        if (!lyricsOpen) {
            lyricsContentReady = false
            return@LaunchedEffect
        }

        lyricsContentReady = false
        delay(AppleMusicLyricsContentDeferMs)
        lyricsContentReady = true
    }

    val sliderPositionState = rememberUpdatedState(sliderPosition)
    val lyricsPosProvider = remember {
        { sliderPositionState.value }
    }

    val driftDpToPx = with(LocalDensity.current) { 1.dp.toPx() }

    val lyricsBackdropProgress =
        animateFloatAsState(

            targetValue = if (lyricsOpen && !landscape) 1f else 0f,
            animationSpec =
                tween(
                    durationMillis = AmLyricsBackdropMorphMs,
                    easing = FastOutSlowInEasing,
                ),
            label = "am-lyrics-backdrop-progress",
        )

    val (thumbnailCornerRadius, _) = rememberPreference(
        ThumbnailCornerRadiusKey,
        defaultValue = 16f,
    )
    val artworkCornerRadiusDp = thumbnailCornerRadius.coerceAtMost(32f).dp

    val baseArtworkUrl = mediaMetadata.thumbnailUrl?.highRes()
    val thumbnailSwapState =
        rememberThumbnailSwapState(
            videoId = mediaMetadata.id,
            ytmUrl = baseArtworkUrl,
            lowDataMode = rememberLowDataModeActive(),
            isMusicVideo = mediaMetadata.isMusicVideo,
        )
    val artworkUrl = thumbnailSwapState.displayUrl
    val artworkRequest = rememberOfflineArtworkImageRequest(artworkUrl)
    val titleActions = rememberPlayerTitleActions(mediaMetadata, navController, state)
    val menuState = LocalMenuState.current
    val context = LocalContext.current

    val currentLyrics by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)

    val (autoTranslateLyrics) = rememberPreference(AutoTranslateLyricsKey, defaultValue = false)
    val (translatorTargetLang) = rememberPreference(TranslatorTargetLangKey, defaultValue = "")

    val (autoTranslateExcludedLanguages) =
        rememberPreference(AutoTranslateExcludedLanguagesKey, defaultValue = emptySet())
    val lyricsMenuViewModel: LyricsMenuViewModel = hiltViewModel()

    val translationDismissedMediaIds by lyricsMenuViewModel.translationDismissedMediaIds
        .collectAsStateWithLifecycle()
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

    val onPlayPauseClick = {
        if (playbackState == STATE_ENDED) {
            playerConnection.player.seekTo(0, 0)
            playerConnection.player.playWhenReady = true
        } else {
            playerConnection.player.togglePlayPause()
        }
    }

    var showAnchoredLyricsMenu by remember { mutableStateOf(false) }
    var moreIconBounds by remember { mutableStateOf(Rect.Zero) }

    var tapAreaRootOrigin by remember { mutableStateOf(Offset.Zero) }

    val popupBackdrop: com.kyant.backdrop.Backdrop? =
        if (rememberLiquidGlassEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            rememberThrottledBackdrop(Color.Transparent)
        } else {
            null
        }

    val onMoreClick = {
        if (lyricsOpen) {
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

    val castAction = rememberCastPlayerMenuAction()
    val onOutputClick: () -> Unit = castAction?.onClick ?: {
        SystemMediaControlResolver.openMediaOutputSwitcher(context)
    }

    BoxWithConstraints(modifier = modifier) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .matchParentSize()
                    .let { base ->

                        if (popupBackdrop != null && showAnchoredLyricsMenu) {
                            base.glassSource(popupBackdrop)
                        } else {
                            base
                        }
                    },
        ) {
        val sharpArtworkHeight = if (landscape) maxHeight else maxHeight * 0.55f

        val fullPlayerHeightForArtwork: Dp? = if (landscape) null else maxHeight

        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .background(Color.Black),
        )

        val landscapeSwipeModifier =
            Modifier.pointerInput(playerConnection) {
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

        val videoShowing =
            LocalVideoArtworkState.current != null &&
                mediaMetadata.isMusicVideo &&
                !mediaMetadata.id.isLocalMediaId()
        val isPreS = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        val canvasActive =
            !canvasPrimaryUrl.isNullOrBlank() || !canvasFallbackUrl.isNullOrBlank()

        val canvasVisualActive = canvasActive && !videoShowing && !isPreS

        val useCanvasBackdrop = canvasVisualActive && !landscape

        val canvasBackdropReveal =
            remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(useCanvasBackdrop) {
            canvasBackdropReveal.animateTo(
                targetValue = if (useCanvasBackdrop) 1f else 0f,
                animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            )
        }

        val twinSnapshotLayer = rememberGraphicsLayer()
        var blurredTwinBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
        var twinBakeFailures by remember { mutableStateOf(0) }
        val twinBackdropHealthy = twinBakeFailures < 5
        val twinSnapshotBlurRadiusPx =
            with(LocalDensity.current) { (AmCanvasBackdropBlurRadius / AmCanvasBackdropUpscale).toPx() }

        LaunchedEffect(useCanvasBackdrop, canvasPrimaryUrl, canvasFallbackUrl) {
            blurredTwinBitmap = null
            twinBakeFailures = 0
            if (!useCanvasBackdrop) return@LaunchedEffect
            while (isActive) {

                if (twinBakeFailures >= 5) {
                    blurredTwinBitmap = null
                    delay(AmCanvasSnapshotIntervalMs)
                    continue
                }
                val layer = twinSnapshotLayer
                if (layer.size.width >= 8 && layer.size.height >= 8) {

                    withFrameNanos { }
                    val snapshot =
                        runCatching {
                            layer.toImageBitmap().asAndroidBitmap()
                        }.getOrNull()
                    if (snapshot != null) {
                        val baked =
                            withContext(Dispatchers.Default) {
                                runCatching {
                                    ImageBlurUtils
                                        .blur(snapshot, twinSnapshotBlurRadiusPx)
                                        .asImageBitmap()
                                }.getOrNull()
                            }
                        if (baked != null) {
                            twinBakeFailures = 0
                            blurredTwinBitmap = baked
                        } else {
                            twinBakeFailures++
                        }
                    } else {
                        twinBakeFailures++
                    }
                }
                delay(AmCanvasSnapshotIntervalMs)
            }
        }
        val canvasScrimReveal by animateFloatAsState(

            targetValue = if (canvasVisualActive) 1f else 0f,
            animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            label = "am-canvas-scrim-reveal",
        )
        val context = LocalContext.current
        val imageLoader = context.imageLoader

        val preBlurredBitmap by produceState<Bitmap?>(null, artworkUrl) {
            if (artworkUrl.isNullOrBlank() || videoShowing) {
                value = null
                return@produceState
            }
            value = withContext(Dispatchers.IO) {
                try {
                    val request = ImageRequest.Builder(context)
                        .data(artworkUrl)
                        .allowHardware(false)
                        .memoryCacheKey("$artworkUrl#amplayer")
                        .diskCacheKey("$artworkUrl#amplayer")
                        .size(CoilSize(720, 720))
                        .build()
                    val result = imageLoader.execute(request)
                    if (result is SuccessResult) {
                        val bitmap = result.image.toBitmap()
                            .copy(Bitmap.Config.ARGB_8888, true)
                        val density = context.resources.displayMetrics.density

                        val radiusPx =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                AmBackdropBlurRadius.value * density
                            } else {
                                72f * density
                            }
                        ImageBlurUtils.blur(bitmap, radiusPx)
                    } else null
                } catch (_: Exception) {
                    null
                }
            }
        }

        if (!videoShowing) {
            val wanderMaxDrift = movingBlurWanderMaxDriftDp(maxWidth, maxHeight)

            val wanderActive = lyricsBackdropActive || landscape
            val blurWander = rememberBlurWanderDrift(active = wanderActive, maxDriftDp = wanderMaxDrift)
            val driftGraphicsLayer: GraphicsLayerScope.() -> Unit = {
                val progress = lyricsBackdropProgress.value

                val scale = AmCoverBlurScale + (AmLyricsBlurDriftScale - AmCoverBlurScale) * progress
                scaleX = scale
                scaleY = scale

                val driftFactor = if (landscape) AmLandscapeDriftFactor else progress
                if (driftFactor > 0f) {
                    translationX = blurWander.xDp.floatValue * driftDpToPx * driftFactor
                    translationY = blurWander.yDp.floatValue * driftDpToPx * driftFactor

                }

                compositingStrategy = CompositingStrategy.Offscreen
            }

            val backdropFootprint =
                remember(maxWidth, maxHeight, landscape) {
                    if (landscape) {
                        blurBackdropFootprintLandscape(
                            width = maxWidth,
                            height = maxHeight,
                            restScale = AmCoverBlurScale,
                            maxDriftDp = wanderMaxDrift,
                            driftFactor = AmLandscapeDriftFactor,
                            blurEdgeMarginDp = AmBackdropBlurRadius,
                        )
                    } else {
                        blurBackdropFootprint(
                            width = maxWidth,
                            height = maxHeight,
                            restScale = AmCoverBlurScale,
                            driftScale = AmLyricsBlurDriftScale,
                            maxDriftDp = wanderMaxDrift,
                        )
                    }
                }
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .clipToBounds(),
                contentAlignment = Alignment.Center,
            ) {
                if (preBlurredBitmap != null) {

                    Image(
                        bitmap = preBlurredBitmap!!.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .requiredSize(backdropFootprint)
                                .graphicsLayer(driftGraphicsLayer),
                    )
                } else {

                    val backdropModel = artworkRequest ?: artworkUrl
                    Crossfade(
                        targetState = backdropModel,
                        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
                        label = "am-backdrop-artwork",
                    ) { model ->
                        AsyncImage(
                            model = model,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .requiredSize(backdropFootprint)

                                    .graphicsLayer(driftGraphicsLayer)
                                    .then(
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            Modifier.blur(AmBackdropBlurRadius)
                                        } else {
                                            Modifier
                                        },
                                    ),
                        )
                    }
                }
            }

            if (useCanvasBackdrop || canvasBackdropReveal.value > 0.01f) {
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                val scale = AmCoverBlurScale * AmCanvasBackdropUpscale
                                scaleX = scale
                                scaleY = scale
                                alpha = canvasBackdropReveal.value * (1f - lyricsBackdropProgress.value)
                            },
                    contentAlignment = Alignment.Center,
                ) {

                    if (twinBackdropHealthy) {
                        CanvasArtworkPlayer(
                            primaryUrl = canvasPrimaryUrl,
                            fallbackUrl = canvasFallbackUrl,
                            isPlaying = isPlaying && canvasVisibleForLyrics,
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                            visible = canvasVisibleForLyrics,
                            maxVideoEdgePx = AmCanvasBackdropMaxVideoEdgePx,
                            loopSyncFollower = canvasLoopSync,

                            refreshEpoch = orientationRefreshEpoch,
                            modifier =
                                Modifier
                                    .fillMaxWidth(1f / AmCanvasBackdropUpscale)
                                    .fillMaxHeight(1f / AmCanvasBackdropUpscale)
                                    .canvasSnapshotSource(twinSnapshotLayer, AmCanvasSnapshotIntervalMs),
                        )
                    }
                    blurredTwinBitmap?.let { baked ->
                        Image(
                            bitmap = baked,
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier =
                                Modifier
                                    .fillMaxWidth(1f / AmCanvasBackdropUpscale)
                                    .fillMaxHeight(1f / AmCanvasBackdropUpscale),
                        )
                    }
                }
            }

            val preBlurLoading = preBlurredBitmap == null && !canvasActive

            val canvasScrimBrush =
                remember {
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.15f),
                        0.5f to Color.Black.copy(alpha = 0.28f),
                        1f to Color.Black.copy(alpha = 0.50f),
                    )
                }
            val staticScrimBrush =
                remember(preBlurLoading, Build.VERSION.SDK_INT) {
                    val (a1, a2, a3) =
                        if (preBlurLoading) {
                            Triple(0.55f, 0.65f, 0.85f)
                        } else {
                            Triple(0.28f, 0.42f, 0.60f)
                        }
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = a1),
                        0.5f to Color.Black.copy(alpha = a2),
                        1f to Color.Black.copy(alpha = a3),
                    )
                }
            if (isPreS) {
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .background(staticScrimBrush),
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .graphicsLayer { alpha = 1f - canvasScrimReveal }
                            .background(staticScrimBrush),
                )
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .graphicsLayer { alpha = canvasScrimReveal }
                            .background(canvasScrimBrush),
                )
            }
        }

        if (landscape) {
            Row(
                modifier =
                    Modifier
                        .fillMaxSize(),
            ) {
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
                            AppleMusicSharpArtwork(
                                artworkRequest = artworkRequest,
                                artworkUrl = artworkUrl,
                                canvasPrimaryUrl = canvasPrimaryUrl,
                                canvasFallbackUrl = canvasFallbackUrl,
                                isPlaying = isPlaying,
                                fadeBottom = false,
                                videoId = mediaMetadata.id.takeIf { !it.isLocalMediaId() },
                                isMusicVideo = mediaMetadata.isMusicVideo,
                                landscape = true,
                                landscapeCanvasFullBleed = true,

                                fadeRightEdge = true,
                                artworkCornerRadiusDp = artworkCornerRadiusDp,
                                canvasLoopSync = canvasLoopSync,
                                modifier = Modifier.fillMaxSize(),
                            )

                            Box(
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .padding(bottom = contentBottomPadding),
                            ) {
                                AppleMusicLandscapeTitleBlock(
                                    mediaMetadata = mediaMetadata,
                                    currentSongLiked = currentSongLiked,
                                    titleActions = titleActions,
                                    onToggleLike = playerConnection::toggleLike,
                                    onMoreClick = onMoreClick,
                                    onMorePositioned = { moreIconBounds = it },
                                    contentWidth = null,

                                    iconsOnly = true,
                                )
                            }
                        }
                    } else {
                    val landscapeArtworkSize =
                        (maxWidth - 96.dp)
                            .coerceAtMost(maxHeight * 0.68f)
                            .coerceAtLeast(220.dp)

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(bottom = contentBottomPadding),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(top = 32.dp)
                                    .padding(horizontal = 16.dp)
                                    .then(landscapeSwipeModifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppleMusicSharpArtwork(
                                artworkRequest = artworkRequest,
                                artworkUrl = artworkUrl,
                                canvasPrimaryUrl = canvasPrimaryUrl,
                                canvasFallbackUrl = canvasFallbackUrl,
                                isPlaying = isPlaying,
                                fadeBottom = false,
                                videoId = mediaMetadata.id.takeIf { !it.isLocalMediaId() },
                                isMusicVideo = mediaMetadata.isMusicVideo,
                                landscape = true,
                                landscapeArtworkSize = landscapeArtworkSize,
                                fadeRightEdge = true,
                                artworkCornerRadiusDp = artworkCornerRadiusDp,
                                canvasLoopSync = canvasLoopSync,
                                modifier =
                                    Modifier
                                        .fillMaxSize(),
                            )
                        }

                        AppleMusicLandscapeTitleBlock(
                            mediaMetadata = mediaMetadata,
                            currentSongLiked = currentSongLiked,
                            titleActions = titleActions,
                            onToggleLike = playerConnection::toggleLike,
                            onMoreClick = onMoreClick,
                            onMorePositioned = { moreIconBounds = it },
                            contentWidth = landscapeArtworkSize,
                        )
                    }
                    }
                }
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = lyricsOpen,
                        enter = fadeIn(tween(400, easing = FastOutSlowInEasing)),
                        exit = fadeOut(tween(300, easing = FastOutSlowInEasing)),
                        modifier = Modifier.matchParentSize(),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = AppleMusicContentPadding - 16.dp),
                        ) {
                            if (lyricsContentReady) {
                                when (lyricsMode) {
                                    LyricsMode.V2 ->
                                        LyricsV2(
                                            sliderPositionProvider = lyricsPosProvider,
                                            lyricsSyncOffset = lyricsSyncOffset,
                                            modifier = Modifier.fillMaxSize(),
                                        )

                                    LyricsMode.ENHANCED ->
                                        LyricsEnhanced(
                                            sliderPositionProvider = lyricsPosProvider,
                                            lyricsSyncOffset = lyricsSyncOffset,
                                            modifier = Modifier.fillMaxSize(),
                                        )

                                    LyricsMode.SPOTIFY ->
                                        LyricsV2(
                                            sliderPositionProvider = lyricsPosProvider,
                                            lyricsSyncOffset = lyricsSyncOffset,
                                            spotifyStyle = true,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                }
                            }
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(

                        visible =
                            (!lyricsOpen && !queueOpen) ||
                                (queueOpen && playerControlsExpanded) ||
                                (lyricsOpen && playerControlsExpanded),
                        enter = fadeIn(tween(120)),
                        exit = fadeOut(tween(100)),
                        modifier = Modifier.matchParentSize(),
                    ) {
                        AppleMusicControlsColumn(
                            mediaMetadata = mediaMetadata,
                            isPlaying = isPlaying,
                            isLoading = isLoading,
                            canSkipPrevious = canSkipPrevious,
                            canSkipNext = canSkipNext,
                            sliderPosition = sliderPosition,
                            positionProvider = positionProvider,
                            duration = duration,
                            playerConnection = playerConnection,
                            currentSongLiked = currentSongLiked,
                            volume = volume,
                            onVolumeChange = onControlsVolumeChange,
                            titleActions = titleActions,
                            onPlayPauseClick = onPlayPauseClick,
                            onMoreClick = onMoreClick,
                            onOutputClick = onOutputClick,
                            onQueueClick = toggleQueue,
                            onLyricsClick = toggleLyrics,
                            onSliderValueChange = onControlsSliderValueChange,
                            onSliderValueChangeFinished = onControlsSliderValueChangeFinished,
                            currentFormat = currentFormat,
                            onQualityChipClick = {
                                bottomSheetPageState.show { ShowMediaInfo(mediaMetadata.id) }
                            },
                            onMorePositioned = { moreIconBounds = it },
                            showTitleRow = false,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(bottom = contentBottomPadding),
                        )
                    }
                }
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()

                        .onGloballyPositioned { tapAreaRootOrigin = it.boundsInRoot().topLeft }

                        .pointerInput(lyricsOpen, queueOpen) {
                            if (!lyricsOpen && !queueOpen) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)

                                if (!moreIconBounds.contains(down.position + tapAreaRootOrigin)) {
                                    pokePlayerControlsVisibility()
                                }
                            }
                        },
            ) {
                BoxWithConstraints(
                    modifier = Modifier.weight(1f),
                ) {
                val topInset = LocalStableSystemBarsTopPadding.current
                val miniHeaderHeight = AppleMusicMiniArtworkSize + 16.dp + topInset
                SharedTransitionLayout(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AnimatedContent(
                        targetState = morphState,
                        transitionSpec = {
                            fadeIn(tween(600, easing = FastOutSlowInEasing)) togetherWith
                                fadeOut(tween(600, easing = FastOutSlowInEasing))
                        },
                        modifier = Modifier.fillMaxSize(),
                        label = "AppleMusicMorph",
                    ) { targetState ->
                        if (targetState == AppleMusicPlayerState.COVER) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                AppleMusicSharpArtwork(
                                    artworkRequest = artworkRequest,
                                    artworkUrl = artworkUrl,
                                    canvasPrimaryUrl = canvasPrimaryUrl,
                                    canvasFallbackUrl = canvasFallbackUrl,
                                    isPlaying = isPlaying,
                                    fadeBottom = !videoShowing,
                                    videoId = mediaMetadata.id.takeIf { !it.isLocalMediaId() },
                                    isMusicVideo = mediaMetadata.isMusicVideo,
                                    landscape = false,

                                    fullPlayerHeight = fullPlayerHeightForArtwork,

                                    artworkCornerRadiusDp = artworkCornerRadiusDp,
                                    canvasLoopSync = canvasLoopSync,
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .sharedBounds(
                                                sharedContentState =
                                                    rememberSharedContentState(key = "amCoverArt"),
                                                animatedVisibilityScope = this@AnimatedContent,

                                                clipInOverlayDuringTransition =
                                                    OverlayClip(
                                                        AdaptiveCornerShape(
                                                            smallRadius = 8.dp,
                                                            smallSize = AppleMusicMiniArtworkSize,
                                                            largeRadius = artworkCornerRadiusDp,
                                                            largeSize = 400.dp,
                                                        ),
                                                    ),

                                                boundsTransform =
                                                    BoundsTransform { _, _ ->
                                                        spring(
                                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                                            stiffness = Spring.StiffnessMediumLow,
                                                        )
                                                    },
                                            ),
                                )
                            }
                        } else {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .windowInsetsPadding(WindowInsets(top = LocalStableSystemBarsTopPadding.current)),
                            ) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    AppleMusicMiniHeader(
                                        artworkRequest = artworkRequest,
                                        artworkUrl = artworkUrl,
                                        mediaMetadata = mediaMetadata,
                                        currentSongLiked = currentSongLiked,
                                        titleActions = titleActions,
                                        onToggleLike = playerConnection::toggleLike,
                                        onMoreClick = onMoreClick,
                                        onArtworkClick = restoreCover,
                                        animatedVisibilityScope = this@AnimatedContent,

                                        artworkCornerRadiusDp = artworkCornerRadiusDp,
                                        onMorePositioned = { moreIconBounds = it },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    if (targetState == AppleMusicPlayerState.QUEUE) {
                                        AppleMusicQueueSheet(
                                            navController = navController,
                                            playerBottomSheetState = state,
                                            modifier =
                                                Modifier
                                                    .fillMaxSize()
                                                    .animateEnterExit(
                                                        enter = slideInVertically(
                                                            animationSpec = tween(600, easing = FastOutSlowInEasing),
                                                        ) { it / 4 } + fadeIn(tween(600)),
                                                        exit = fadeOut(tween(400)) +
                                                            slideOutVertically(
                                                                animationSpec = tween(400, easing = FastOutSlowInEasing),
                                                            ) { it / 4 },
                                                    ),
                                        )
                                    }

                                }
                            }
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = lyricsOpen,
                    enter = fadeIn(tween(400, easing = FastOutSlowInEasing)),
                    exit = fadeOut(tween(300, easing = FastOutSlowInEasing)),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(maxHeight - miniHeaderHeight)
                                .offset(y = miniHeaderHeight),
                    ) {
                        val lyricsHorizontalPadding = AppleMusicContentPadding - 16.dp
                        if (lyricsContentReady) {
                            when (lyricsMode) {
                                LyricsMode.V2 ->
                                    LyricsV2(
                                        sliderPositionProvider = lyricsPosProvider,
                                        lyricsSyncOffset = lyricsSyncOffset,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = lyricsHorizontalPadding),
                                    )

                                LyricsMode.ENHANCED ->
                                    LyricsEnhanced(
                                        sliderPositionProvider = lyricsPosProvider,
                                        lyricsSyncOffset = lyricsSyncOffset,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = lyricsHorizontalPadding),
                                    )

                                LyricsMode.SPOTIFY ->
                                    LyricsV2(
                                        sliderPositionProvider = lyricsPosProvider,
                                        lyricsSyncOffset = lyricsSyncOffset,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = lyricsHorizontalPadding),
                                        spotifyStyle = true,
                                    )
                            }
                        }
                    }
                }
                }

                AnimatedVisibility(

                    visible =
                        (!lyricsOpen && !queueOpen) ||
                            (queueOpen && playerControlsExpanded) ||
                            (lyricsOpen && playerControlsExpanded),
                    enter = if (animationsDisabled) {
                        fadeIn(tween(120))
                    } else {
                        fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 6 }
                    },
                    exit = if (animationsDisabled) {
                        fadeOut(tween(100))
                    } else {
                        fadeOut(tween(140)) + slideOutVertically(tween(140)) { it / 8 }
                    },
                ) {
                    AppleMusicControlsColumn(
                        mediaMetadata = mediaMetadata,
                        isPlaying = isPlaying,
                        isLoading = isLoading,
                        canSkipPrevious = canSkipPrevious,
                        canSkipNext = canSkipNext,
                        sliderPosition = sliderPosition,
                        positionProvider = positionProvider,
                        duration = duration,
                        playerConnection = playerConnection,
                        currentSongLiked = currentSongLiked,
                        volume = volume,
                        onVolumeChange = onControlsVolumeChange,
                        titleActions = titleActions,
                        onPlayPauseClick = onPlayPauseClick,
                        onMoreClick = onMoreClick,
                        onOutputClick = onOutputClick,
                        onQueueClick = toggleQueue,
                        onLyricsClick = toggleLyrics,
                        onSliderValueChange = onControlsSliderValueChange,
                        onSliderValueChangeFinished = onControlsSliderValueChangeFinished,
                        currentFormat = currentFormat,
                        onQualityChipClick = {
                            bottomSheetPageState.show { ShowMediaInfo(mediaMetadata.id) }
                        },
                        showTitleRow = !morphOpen,
                        isQueueActive = queueOpen,
                        isLyricsActive = lyricsOpen,
                        onMorePositioned = { moreIconBounds = it },
                        modifier =
                            Modifier
                                .fillMaxWidth()

                                .padding(bottom = contentBottomPadding),
                    )
                }
            }
        }
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

            )
        }

    }
}

@Composable
private fun AppleMusicSharpArtwork(
    artworkRequest: coil3.request.ImageRequest?,
    artworkUrl: String?,
    canvasPrimaryUrl: String?,
    canvasFallbackUrl: String?,
    isPlaying: Boolean,
    fadeBottom: Boolean,
    videoId: String? = null,
    isMusicVideo: Boolean = false,
    landscape: Boolean = false,

    landscapeArtworkSize: Dp? = null,

    landscapeCanvasFullBleed: Boolean = false,

    fadeRightEdge: Boolean = false,

    showCanvas: Boolean = true,

    fullPlayerHeight: Dp? = null,

    artworkCornerRadiusDp: Dp = 16.dp,
    canvasLoopSync: CanvasLoopSync? = null,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current

    var canvasFrameReady by remember(canvasPrimaryUrl, canvasFallbackUrl) {
        mutableStateOf(false)
    }
    val staticBaseAlpha by animateFloatAsState(
        targetValue = if (canvasFrameReady) 0f else 1f,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "am-canvas-static-base",
    )

    val artworkFadeBrush = remember {
        Brush.verticalGradient(
            0.62f to Color.Black,
            1f to Color.Transparent,
        )
    }

    val canvasRightFadeBrush = remember {
        Brush.horizontalGradient(
            0f to Color.Black,
            0.55f to Color.Black,
            1f to Color.Transparent,
        )
    }
    Box(
        modifier =
            modifier.then(
                if (fadeBottom) {
                    Modifier
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = artworkFadeBrush,
                                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                            )
                        }
                } else {
                    Modifier
                },
            ),
    ) {
        val videoArtworkState = LocalVideoArtworkState.current
        val showVideo =
            videoArtworkState != null &&
                !videoArtworkState.hasPlaybackFailed &&
                isMusicVideo &&
                !videoId.isNullOrBlank() &&
                playerConnection != null

        val hasCanvas = !canvasPrimaryUrl.isNullOrBlank() || !canvasFallbackUrl.isNullOrBlank()
        val immersiveExtendedCard = !showVideo && !hasCanvas
        if (showVideo) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(Color.Black),
            )
        } else if (immersiveExtendedCard) {
            BoxWithConstraints(modifier = Modifier.matchParentSize()) {
                val horizontalPadding = if (maxWidth < 380.dp) 16.dp else 20.dp
                val effectiveFullHeight = fullPlayerHeight ?: if (landscape) maxHeight else maxHeight / 0.55f
                val compactHeight = effectiveFullHeight < 760.dp
                val veryCompactHeight = effectiveFullHeight < 700.dp

                val artworkSize =
                    landscapeArtworkSize
                        ?: run {
                            val artworkMinSize =
                                when {
                                    veryCompactHeight -> 200.dp
                                    compactHeight -> 216.dp
                                    else -> 236.dp
                                }

                            val artworkHeightLimitFromFull =
                                effectiveFullHeight *
                                    when {
                                        veryCompactHeight -> 0.32f
                                        compactHeight -> 0.35f
                                        else -> 0.40f
                                    }
                            val artworkHeightLimitFromMorph = maxHeight * 0.82f
                            val artworkHeightLimit =
                                minOf(artworkHeightLimitFromFull, artworkHeightLimitFromMorph)
                            (maxWidth - horizontalPadding * 2)
                                .coerceAtMost(artworkHeightLimit)
                                .coerceAtLeast(artworkMinSize)
                        }

                val artworkPauseScale by animateFloatAsState(
                    targetValue = if (isPlaying) 1f else 0.92f,
                    animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                    label = "artworkPauseScale",
                )
                Box(
                    modifier = Modifier.matchParentSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = artworkRequest ?: artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .size(artworkSize)
                                .graphicsLayer {
                                    scaleX = artworkPauseScale
                                    scaleY = artworkPauseScale

                                    shadowElevation = 8f
                                    clip = true
                                    shape = RoundedCornerShape(artworkCornerRadiusDp)
                                },
                    )
                }
            }
        } else {
            Box(modifier = Modifier.matchParentSize()) {
                if (staticBaseAlpha > 0.01f) {
                    AsyncImage(
                        model = artworkRequest ?: artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .matchParentSize()
                                .graphicsLayer { alpha = staticBaseAlpha },
                    )
                }

                if (hasCanvas && landscapeCanvasFullBleed && staticBaseAlpha > 0.01f) {
                    Box(
                        modifier =
                            Modifier
                                .matchParentSize()
                                .graphicsLayer { alpha = staticBaseAlpha }
                                .background(Color.Black.copy(alpha = 0.55f)),
                    )
                }
            }
        }

        if (showCanvas && !showVideo &&
            (!canvasPrimaryUrl.isNullOrBlank() || !canvasFallbackUrl.isNullOrBlank())
        ) {
            val canvasModifier =
                if (fadeRightEdge) {
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = canvasRightFadeBrush,
                                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                            )
                        }
                } else {
                    Modifier.matchParentSize()
                }
            CanvasArtworkPlayer(
                primaryUrl = canvasPrimaryUrl,
                fallbackUrl = canvasFallbackUrl,
                isPlaying = isPlaying,

                resizeMode =
                    if (landscapeCanvasFullBleed) {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    } else {
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    },
                loopSyncLeader = canvasLoopSync,
                onFirstFrameRendered = { canvasFrameReady = true },
                modifier = canvasModifier,
            )
        }

        if (showVideo) {
            InlineVideoPlayer(
                controlsOnTap = true,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun AppleMusicControlsColumn(
    mediaMetadata: MediaMetadata,
    isPlaying: Boolean,
    isLoading: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    sliderPosition: Long?,
    positionProvider: () -> Long,
    duration: Long,
    playerConnection: PlayerConnection,
    currentSongLiked: Boolean,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    titleActions: PlayerTitleActions,
    onPlayPauseClick: () -> Unit,
    onMoreClick: () -> Unit,
    onOutputClick: () -> Unit,
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,

    currentFormat: FormatEntity?,

    onQualityChipClick: () -> Unit,

    showTitleRow: Boolean = true,

    isQueueActive: Boolean = false,

    isLyricsActive: Boolean = false,

    onMorePositioned: ((Rect) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var swipeUpAccumulated by remember { mutableFloatStateOf(0f) }
    val swipeUpThreshold = 120f
    val swipeActivationThreshold = 72f
    val resetSwipeUp = remember {
        {
            if (swipeUpAccumulated != 0f) swipeUpAccumulated = 0f
        }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(300); resetSwipeUp() }

    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val compactHeight = screenHeight < 720.dp
    val veryCompactHeight = screenHeight < 620.dp
    val titleToScrubberGap = if (veryCompactHeight) 8.dp else if (compactHeight) 14.dp else 20.dp
    val scrubberToTransportGap = if (veryCompactHeight) 12.dp else if (compactHeight) 14.dp else 18.dp
    val transportToVolumeGap = if (veryCompactHeight) 8.dp else if (compactHeight) 12.dp else 16.dp
    val volumeToActionsGap = if (veryCompactHeight) 12.dp else if (compactHeight) 16.dp else 22.dp

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = AppleMusicContentPadding)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var accumulated = 0f
                        var swipeActivated = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull() ?: break
                            if (change.changedToUp()) break

                            val dragDelta = change.positionChange().y

                            if (!swipeActivated) {
                                if (dragDelta < 0f) {
                                    accumulated += dragDelta
                                }
                                if (abs(accumulated) > swipeActivationThreshold) {
                                    swipeActivated = true
                                    swipeUpAccumulated = accumulated
                                    change.consume()
                                }
                            } else {
                                if (dragDelta < 0f) {
                                    swipeUpAccumulated =
                                        (swipeUpAccumulated + dragDelta).coerceAtLeast(-swipeUpThreshold * 1.5f)
                                }
                                change.consume()
                            }
                        }

                        if (swipeActivated && swipeUpAccumulated < -swipeUpThreshold) {
                            onQueueClick()
                        }
                        swipeUpAccumulated = 0f
                    }
            },

        verticalArrangement = Arrangement.Bottom,
    ) {
    if (showTitleRow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayerTextBackdrop(
                textColor = Color.White,
                modifier = Modifier.weight(1f),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val titleLayout = remember { mutableStateOf<TextLayoutResult?>(null) }
                    val artistLayout = remember { mutableStateOf<TextLayoutResult?>(null) }
                    val titleViewport = remember { mutableStateOf(0) }
                    val artistViewport = remember { mutableStateOf(0) }
                    val hasTitleOverflow =
                        titleViewport.value > 0 &&
                            (titleLayout.value?.size?.width ?: 0) > titleViewport.value
                    val hasArtistOverflow =
                        artistViewport.value > 0 &&
                            (artistLayout.value?.size?.width ?: 0) > artistViewport.value
                    androidx.compose.foundation.layout.Box(
                        modifier = (if (hasTitleOverflow) Modifier.fillMaxWidth().viewportEdgeFade() else Modifier.fillMaxWidth()).clipToBounds()
                            .onSizeChanged { titleViewport.value = it.width }.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = titleActions.onTitleClick,
                        ),
                    ) {
                        Text(
                            text = mediaMetadata.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { titleLayout.value = it },
                            modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                        )
                    }
                    androidx.compose.foundation.layout.Box(
                        modifier = (if (hasArtistOverflow) Modifier.fillMaxWidth().viewportEdgeFade() else Modifier.fillMaxWidth()).clipToBounds()
                            .onSizeChanged { artistViewport.value = it.width }.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            mediaMetadata.artists.firstOrNull()?.id?.let(titleActions.onArtistClick)
                        },
                    ) {
                        Text(
                            text = mediaMetadata.artists.joinToString { it.name },
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.64f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { artistLayout.value = it },
                            modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            AppleMusicChip(
                iconRes = if (currentSongLiked) R.drawable.player_star_filled else R.drawable.player_star,
                tint = Color.White,
                contentDescription = null,
                onClick = playerConnection::toggleLike,
            )
            Spacer(Modifier.width(10.dp))
            AppleMusicChip(
                iconRes = R.drawable.player_more_horiz,
                tint = Color.White,
                contentDescription = null,
                onClick = onMoreClick,
                onPositioned = onMorePositioned,
            )
        }
    }

    Spacer(Modifier.height(titleToScrubberGap))

    AppleMusicPositionSection(
        positionProvider = positionProvider,
        sliderPosition = sliderPosition,
        duration = duration,
        currentFormat = currentFormat,
        onSliderValueChange = onSliderValueChange,
        onSliderValueChangeFinished = onSliderValueChangeFinished,
        onQualityChipClick = onQualityChipClick,
    )

    Spacer(Modifier.height(scrubberToTransportGap))

    val transportIconSize =
        if (veryCompactHeight) 40.dp else if (compactHeight) 44.dp else AppleMusicTransportIconSize
    val playPauseIconSize =
        if (veryCompactHeight) 67.dp else if (compactHeight) 73.dp else AppleMusicPlayPauseIconSize

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppleMusicTransportButton(
            iconRes = R.drawable.apple_skip_previous,
            enabled = canSkipPrevious,
            mirrored = false,
            iconSize = transportIconSize,
            onClick = playerConnection::seekToPrevious,
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(playPauseIconSize + 20.dp)
                    .clip(CircleShape),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(AppleMusicPlayPauseSpinnerSize),
                    strokeWidth = 3.dp,
                )
            } else {
                AppleMusicTransportButton(
                    iconRes = if (isPlaying) R.drawable.pause_applemusic else R.drawable.play_applemusic,
                    enabled = true,
                    mirrored = false,
                    iconSize = playPauseIconSize,
                    onClick = onPlayPauseClick,
                )
            }
        }
        AppleMusicTransportButton(
            iconRes = R.drawable.apple_skip_next,
            enabled = canSkipNext,
            mirrored = false,
            iconSize = transportIconSize,
            onClick = playerConnection::seekToNext,
        )
    }

    Spacer(Modifier.height(transportToVolumeGap))

    AppleMusicVolumeRow(
        volume = volume,
        onVolumeChange = onVolumeChange,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(volumeToActionsGap))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppleMusicBottomButton(
            iconRes = R.drawable.player_lyrics,
            contentDescription = stringResource(R.string.lyrics),
            onClick = onLyricsClick,
            tint = if (isLyricsActive) Color.White else Color.White.copy(alpha = 0.85f),
        )
        AppleMusicBottomButton(
            iconRes = R.drawable.cast,
            contentDescription = null,
            onClick = onOutputClick,
        )
        AppleMusicBottomButton(
            iconRes = R.drawable.player_queue_music,
            contentDescription = stringResource(R.string.queue),
            onClick = onQueueClick,
            tint = if (isQueueActive) Color.White else Color.White.copy(alpha = 0.85f),
        )
    }
    }
}

@Composable
private fun AppleMusicLandscapeTitleBlock(
    mediaMetadata: MediaMetadata,
    currentSongLiked: Boolean,
    titleActions: PlayerTitleActions,
    onToggleLike: () -> Unit,
    onMoreClick: () -> Unit,
    onMorePositioned: ((Rect) -> Unit)? = null,
    contentWidth: Dp? = null,
    iconsOnly: Boolean = false,
) {
    if (iconsOnly) {

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier =
                Modifier
                    .let { base ->
                        if (contentWidth != null) {
                            base.width(contentWidth)
                        } else {
                            base
                                .fillMaxWidth()
                                .padding(horizontal = AppleMusicContentPadding)
                        }
                    }
                    .padding(top = 12.dp, bottom = 10.dp),
        ) {
            Spacer(Modifier.weight(1f))
            AppleMusicChip(
                iconRes = if (currentSongLiked) R.drawable.player_star_filled else R.drawable.player_star,
                tint = Color.White,
                contentDescription = null,
                onClick = onToggleLike,
            )
            AppleMusicChip(
                iconRes = R.drawable.player_more_horiz,
                tint = Color.White,
                contentDescription = null,
                onClick = onMoreClick,
                onPositioned = onMorePositioned,
            )
        }
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .let { base ->
                    if (contentWidth != null) {
                        base
                            .width(contentWidth)
                    } else {
                        base
                            .fillMaxWidth()
                            .padding(horizontal = AppleMusicContentPadding)
                    }
                }
                .padding(top = 12.dp, bottom = 10.dp),
    ) {
        PlayerTextBackdrop(
            textColor = Color.White,
            modifier = Modifier.weight(1f),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = mediaMetadata.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .basicMarquee(iterations = Int.MAX_VALUE)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = titleActions.onTitleClick,
                            ),
                )
                Text(
                    text = mediaMetadata.artists.joinToString { it.name },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.64f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .basicMarquee(iterations = Int.MAX_VALUE)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                mediaMetadata.artists.firstOrNull()?.id?.let(titleActions.onArtistClick)
                            },
                )
            }
        }

        AppleMusicChip(
            iconRes = if (currentSongLiked) R.drawable.player_star_filled else R.drawable.player_star,
            tint = Color.White,
            contentDescription = null,
            onClick = onToggleLike,
        )
        Spacer(Modifier.width(4.dp))
        AppleMusicChip(
            iconRes = R.drawable.player_more_horiz,
            tint = Color.White,
            contentDescription = null,
            onClick = onMoreClick,
            onPositioned = onMorePositioned,
        )
    }
}

@Composable
private fun AppleMusicChip(
    iconRes: Int,
    tint: Color,
    contentDescription: String?,
    onClick: () -> Unit,

    onPositioned: ((Rect) -> Unit)? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(AppleMusicChipSize)
                .let { base ->
                    if (onPositioned != null) {
                        base.onGloballyPositioned { coords ->
                            onPositioned(coords.boundsInRoot())
                        }
                    } else {
                        base
                    }
                }
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f))
                .clickable(onClick = onClick),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun AppleMusicTransportButton(
    iconRes: Int,
    enabled: Boolean,
    mirrored: Boolean,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(iconSize + 20.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = iconSize / 2 + 10.dp),
                    enabled = enabled,
                    onClick = onClick,
                ),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
            modifier =
                Modifier
                    .size(iconSize)
                    .graphicsLayer { if (mirrored) scaleX = -1f },
        )
    }
}

@Composable
private fun AppleMusicBottomButton(
    iconRes: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    tint: Color = Color.White.copy(alpha = 0.85f),
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(AppleMusicBottomButtonSize)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = AppleMusicBottomButtonSize / 2),
                    onClick = onClick,
                ),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(AppleMusicBottomIconSize),
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.AppleMusicMiniHeader(
    artworkRequest: coil3.request.ImageRequest?,
    artworkUrl: String?,
    mediaMetadata: MediaMetadata,
    currentSongLiked: Boolean,
    titleActions: PlayerTitleActions,
    onToggleLike: () -> Unit,
    onMoreClick: () -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onArtworkClick: () -> Unit = {},

    artworkCornerRadiusDp: Dp = 16.dp,

    onMorePositioned: ((Rect) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .padding(horizontal = AppleMusicContentPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(AppleMusicMiniArtworkSize)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = false, radius = AppleMusicMiniArtworkSize / 2),
                        onClick = onArtworkClick,
                    )
                    .sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "amCoverArt"),
                        animatedVisibilityScope = animatedVisibilityScope,
                        clipInOverlayDuringTransition =
                            OverlayClip(
                                AdaptiveCornerShape(
                                    smallRadius = 8.dp,
                                    smallSize = AppleMusicMiniArtworkSize,
                                    largeRadius = artworkCornerRadiusDp,
                                    largeSize = 400.dp,
                                ),
                            ),

                        boundsTransform =
                            BoundsTransform { _, _ ->
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                )
                            },
                    ),
        ) {
            AsyncImage(
                model = artworkRequest ?: artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        PlayerTextBackdrop(
            textColor = Color.White,
            modifier = Modifier.weight(1f),
        ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val miniTitleLayout = remember { mutableStateOf<TextLayoutResult?>(null) }
            val miniArtistLayout = remember { mutableStateOf<TextLayoutResult?>(null) }
            val miniTitleViewport = remember { mutableStateOf(0) }
            val miniArtistViewport = remember { mutableStateOf(0) }
            val hasMiniTitleOverflow =
                miniTitleViewport.value > 0 &&
                    (miniTitleLayout.value?.size?.width ?: 0) > miniTitleViewport.value
            val hasMiniArtistOverflow =
                miniArtistViewport.value > 0 &&
                    (miniArtistLayout.value?.size?.width ?: 0) > miniArtistViewport.value
            androidx.compose.foundation.layout.Box(
                modifier = (if (hasMiniTitleOverflow) Modifier.fillMaxWidth().viewportEdgeFade() else Modifier.fillMaxWidth()).clipToBounds()
                    .onSizeChanged { miniTitleViewport.value = it.width }.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = titleActions.onTitleClick,
                ),
            ) {
                Text(
                    text = mediaMetadata.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { miniTitleLayout.value = it },
                    modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                )
            }
                androidx.compose.foundation.layout.Box(
                    modifier = (if (hasMiniArtistOverflow) Modifier.fillMaxWidth().viewportEdgeFade() else Modifier.fillMaxWidth()).clipToBounds()
                        .onSizeChanged { miniArtistViewport.value = it.width }.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        mediaMetadata.artists.firstOrNull()?.id?.let(titleActions.onArtistClick)
                    },
                ) {
                    Text(
                        text = mediaMetadata.artists.joinToString { it.name },
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { miniArtistLayout.value = it },
                        modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                    )
                }
            }
        }
        AppleMusicChip(
            iconRes = if (currentSongLiked) R.drawable.player_star_filled else R.drawable.player_star,
            tint = Color.White,
            contentDescription = null,
            onClick = onToggleLike,
        )
        Spacer(Modifier.width(8.dp))
        AppleMusicChip(
            iconRes = R.drawable.player_more_horiz,
            tint = Color.White,
            contentDescription = null,
            onClick = onMoreClick,
            onPositioned = onMorePositioned,
        )
    }
}

@Composable
private fun AppleMusicSeekBar(
    position: Long,
    duration: Long,
    onScrub: (Long) -> Unit,
    onScrubFinished: () -> Unit,
) {
    val enabled = duration > 0L
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val playedFraction =
        if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownFraction = if (dragging) dragFraction else playedFraction

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .pointerInput(enabled, duration) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        onScrub((fraction * duration).toLong())
                        onScrubFinished()
                    }
                }.pointerInput(enabled, duration) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                            onScrub((dragFraction * duration).toLong())
                        },
                        onDragEnd = {
                            dragging = false
                            onScrubFinished()
                        },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            onScrub((dragFraction * duration).toLong())
                        },
                    )
                }.drawWithContent {
                    val trackHeight = if (dragging) 10.dp.toPx() else 7.dp.toPx()
                    val top = (size.height - trackHeight) / 2f
                    val radius = CornerRadius(trackHeight / 2f)
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.28f),
                        topLeft = Offset(0f, top),
                        size = Size(size.width, trackHeight),
                        cornerRadius = radius,
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = if (dragging) 1f else 0.85f),
                        topLeft = Offset(0f, top),
                        size = Size(size.width * shownFraction, trackHeight),
                        cornerRadius = radius,
                    )
                },
    )
}

@Composable
private fun AppleMusicQualityChip(
    currentFormat: FormatEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = remember(currentFormat.mimeType, currentFormat.codecs) {
        currentFormat.codecLabel()
    }
    val lossless = remember(currentFormat.codecs, currentFormat.mimeType) {
        currentFormat.isLossless()
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color.White.copy(alpha = 0.1f),
        border = BorderStroke(width = 1.dp, color = Color.White.copy(alpha = 0.13f)),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(
                painter = painterResource(
                    if (lossless) R.drawable.ic_mqa else R.drawable.player_graphic_eq,
                ),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.72f),
                modifier = Modifier.size(if (lossless) 18.dp else 15.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AppleMusicPositionSection(
    positionProvider: () -> Long,
    sliderPosition: Long?,
    duration: Long,
    currentFormat: FormatEntity?,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    onQualityChipClick: () -> Unit,
) {
    val currentPosition = positionProvider()

    Column {
        AppleMusicSeekBar(
            position = sliderPosition ?: currentPosition,
            duration = duration,
            onScrub = onSliderValueChange,
            onScrubFinished = onSliderValueChangeFinished,
        )
        Spacer(Modifier.height(6.dp))

        Box(Modifier.fillMaxWidth()) {
            Text(
                text = makeTimeString(sliderPosition ?: currentPosition),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.CenterStart),
            )
            if (currentFormat != null) {
                AppleMusicQualityChip(
                    currentFormat = currentFormat,
                    onClick = onQualityChipClick,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Text(
                text = "-" + makeTimeString((duration - (sliderPosition ?: currentPosition)).coerceAtLeast(0L)),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}
