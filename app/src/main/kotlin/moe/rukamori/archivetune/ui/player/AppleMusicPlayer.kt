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

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AutoTranslateExcludedLanguagesKey
import moe.rukamori.archivetune.constants.AutoHideLyricsPlayerControlsKey
import moe.rukamori.archivetune.constants.AutoTranslateLyricsKey
import moe.rukamori.archivetune.constants.ShowLyricsPlayerControlsKey
import moe.rukamori.archivetune.constants.ThumbnailCornerRadiusKey
import moe.rukamori.archivetune.constants.TranslatorTargetLangKey
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.db.entities.codecLabel
import moe.rukamori.archivetune.db.entities.isLossless
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.PlayerConnection
import moe.rukamori.archivetune.ui.component.BottomSheetPageState
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.LyricsEnhanced
import moe.rukamori.archivetune.ui.component.PlatformBackdrop
import moe.rukamori.archivetune.ui.component.layerBackdrop
import moe.rukamori.archivetune.ui.component.rememberBackdrop
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

private val AppleMusicContentPadding = 28.dp
private val AppleMusicChipSize = 34.dp
private val AppleMusicTransportIconSize = 52.dp
private val AppleMusicPlayPauseIconSize = 62.dp

private val AppleMusicBottomIconSize = 26.dp
private val AppleMusicBottomButtonSize = 48.dp
private val AppleMusicMiniArtworkSize = 56.dp

private const val AmLyricsBlurDriftScale = 2.4f

private const val AmCoverBlurScale = 1.2f

private const val AmLyricsBackdropMorphMs = 650

private val AmBackdropBlurRadius = 64.dp

private const val AppleMusicLyricsContentDeferMs = 160L

private const val AppleMusicLyricsControlsAutoHideDelayMs = 5_000L

private const val ControlsGesturePokeThrottleMs = 1_000L

private fun shouldAutoHideAppleMusicControls(
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    autoHideEnabled: Boolean,
): Boolean = (lyricsOpen || queueOpen) && autoHideEnabled

private val AmCanvasSeamFadeDp = 88.dp

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
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    lyricsSyncOffset: Int = 0,
    onLyricsSyncOffsetChange: (Int) -> Unit = {},

    onLyricsVisibilityChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
) {

    var queueOpen by remember { mutableStateOf(false) }

    var lyricsOpen by remember { mutableStateOf(false) }

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

    var playerControlsExpanded by remember { mutableStateOf(true) }
    var controlsRevealToken by remember { mutableIntStateOf(0) }
    val autoHideDelayMs = AppleMusicLyricsControlsAutoHideDelayMs
    val playerExpanded = state.isExpanded

    LaunchedEffect(lyricsOpen, queueOpen) {
        playerControlsExpanded = true
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

    LaunchedEffect(
        lyricsOpen,
        queueOpen,
        controlsRevealToken,
        autoHideLyricsPlayerControls,
        showLyricsPlayerControls,
        playerExpanded,
        mediaMetadata.id,
    ) {
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

    var canvasVisibleForLyrics by remember { mutableStateOf(true) }
    LaunchedEffect(lyricsOpen) {
        if (lyricsOpen) {

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

    val blurWander = rememberBlurWanderDrift(active = lyricsBackdropActive)

    val driftDpToPx = with(LocalDensity.current) { 1.dp.toPx() }

    val lyricsBackdropProgress =
        animateFloatAsState(
            targetValue = if (lyricsOpen) 1f else 0f,
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

    val popupBackdrop: PlatformBackdrop? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            rememberBackdrop(Color.Transparent)
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

        runCatching {
            context.startActivity(Intent("android.settings.panel.action.MEDIA_OUTPUT"))
        }
    }

    BoxWithConstraints(modifier = modifier) {

        BoxWithConstraints(
            modifier =
                Modifier
                    .matchParentSize()
                    .let { base ->
                        if (popupBackdrop != null) {
                            base.layerBackdrop(popupBackdrop)
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

        var morphAreaHeightPx by remember { mutableIntStateOf(0) }

        val videoShowing =
            LocalVideoArtworkState.current != null &&
                mediaMetadata.isMusicVideo &&
                !mediaMetadata.id.isLocalMediaId()
        val isPreS = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        val canvasActive =
            !canvasPrimaryUrl.isNullOrBlank() || !canvasFallbackUrl.isNullOrBlank()

        val useCanvasBackdrop = canvasActive && !videoShowing && !isPreS
        val context = LocalContext.current
        val imageLoader = context.imageLoader
        val preBlurredBitmap by produceState<Bitmap?>(null, artworkUrl) {

            if (!isPreS || artworkUrl.isNullOrBlank() || videoShowing || useCanvasBackdrop) {
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
                        ImageBlurUtils.blur(bitmap, 72f * density)
                    } else null
                } catch (_: Exception) {
                    null
                }
            }
        }

        if (!videoShowing) {

            val driftGraphicsLayer: GraphicsLayerScope.() -> Unit = {

                val progress = lyricsBackdropProgress.value

                val scale = AmCoverBlurScale + (AmLyricsBlurDriftScale - AmCoverBlurScale) * progress
                scaleX = scale
                scaleY = scale
                if (progress > 0f) {

                    translationX = blurWander.xDp.floatValue * driftDpToPx * progress
                    translationY = blurWander.yDp.floatValue * driftDpToPx * progress

                }

                compositingStrategy = CompositingStrategy.Offscreen
            }

            val canvasSeamFade: Modifier =
                if (landscape) {
                    Modifier
                } else {
                    Modifier
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val seam = morphAreaHeightPx.toFloat()

                            if (seam <= 0f || seam >= size.height) return@drawWithContent
                            val fadeStart = ((seam - AmCanvasSeamFadeDp.toPx()) / size.height).coerceIn(0f, 1f)
                            drawRect(
                                brush =
                                    Brush.verticalGradient(
                                        fadeStart to Color.Black,
                                        (seam / size.height) to Color.Transparent,
                                    ),
                                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                            )
                        }
                }

            val backdropFootprint =
                remember(maxWidth, maxHeight) {
                    blurBackdropFootprint(
                        width = maxWidth,
                        height = maxHeight,
                        restScale = AmCoverBlurScale,
                        driftScale = AmLyricsBlurDriftScale,
                    )
                }
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .clipToBounds(),
                contentAlignment = Alignment.Center,
            ) {
                if (isPreS && preBlurredBitmap != null) {

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
                    AsyncImage(
                        model = artworkRequest ?: artworkUrl,
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

            if (useCanvasBackdrop) {

                CanvasArtworkPlayer(
                    primaryUrl = canvasPrimaryUrl,
                    fallbackUrl = canvasFallbackUrl,
                    isPlaying = isPlaying && canvasVisibleForLyrics,
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                    visible = canvasVisibleForLyrics,
                    modifier =
                        Modifier
                            .matchParentSize()

                            .then(canvasSeamFade)
                            .blur(72.dp)
                            .graphicsLayer {

                                scaleX = AmCoverBlurScale
                                scaleY = AmCoverBlurScale
                                alpha = 1f - lyricsBackdropProgress.value
                            },
                )
            }
            val preBlurLoading = isPreS && preBlurredBitmap == null && !canvasActive

            val backdropScrimBrush =
                remember(useCanvasBackdrop, preBlurLoading, Build.VERSION.SDK_INT) {
                    val (a1, a2, a3) =
                        if (useCanvasBackdrop || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Triple(0.25f, 0.40f, 0.65f)
                        } else if (preBlurLoading) {
                            Triple(0.55f, 0.65f, 0.85f)
                        } else {
                            Triple(0.40f, 0.55f, 0.75f)
                        }
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = a1),
                        0.5f to Color.Black.copy(alpha = a2),
                        1f to Color.Black.copy(alpha = a3),
                    )
                }
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(backdropScrimBrush),
            )
        }

        if (landscape) {
            Row(
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
                    artworkCornerRadiusDp = artworkCornerRadiusDp,
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                )
                AnimatedVisibility(

                    visible =
                        (!lyricsOpen && !queueOpen) ||
                            (queueOpen && playerControlsExpanded) ||
                            (lyricsOpen && playerControlsExpanded),
                    enter = fadeIn(tween(120)),
                    exit = fadeOut(tween(100)),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
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
                        onQueueClick = onQueueClick,
                        onLyricsClick = onLyricsClick,
                        onSliderValueChange = onControlsSliderValueChange,
                        onSliderValueChangeFinished = onControlsSliderValueChangeFinished,
                        currentFormat = currentFormat,
                        onQualityChipClick = {
                            bottomSheetPageState.show { ShowMediaInfo(mediaMetadata.id) }
                        },
                        onMorePositioned = { moreIconBounds = it },
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(bottom = contentBottomPadding),
                    )
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

                    modifier = Modifier.weight(1f).onSizeChanged { morphAreaHeightPx = it.height },
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

                            LyricsEnhanced(
                                sliderPositionProvider = lyricsPosProvider,
                                lyricsSyncOffset = lyricsSyncOffset,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = lyricsHorizontalPadding),
                            )
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

    showCanvas: Boolean = true,

    fullPlayerHeight: Dp? = null,

    artworkCornerRadiusDp: Dp = 16.dp,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current

    val artworkFadeBrush = remember {
        Brush.verticalGradient(
            0.62f to Color.Black,
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
                val artworkSize =
                    (maxWidth - horizontalPadding * 2)
                        .coerceAtMost(artworkHeightLimit)
                        .coerceAtLeast(artworkMinSize)

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
            AsyncImage(
                model = artworkRequest ?: artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }

        if (showCanvas && !showVideo &&
            (!canvasPrimaryUrl.isNullOrBlank() || !canvasFallbackUrl.isNullOrBlank())
        ) {
            CanvasArtworkPlayer(
                primaryUrl = canvasPrimaryUrl,
                fallbackUrl = canvasFallbackUrl,
                isPlaying = isPlaying,
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                modifier = Modifier.matchParentSize(),
            )
        }

        if (showVideo) {
            InlineVideoPlayer(
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
    val scrubberToTransportGap = if (veryCompactHeight) 12.dp else if (compactHeight) 16.dp else 22.dp
    val transportToVolumeGap = if (veryCompactHeight) 8.dp else if (compactHeight) 14.dp else 20.dp
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

    Spacer(Modifier.height(scrubberToTransportGap))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppleMusicTransportButton(
            iconRes = R.drawable.player_fast_forward,
            enabled = canSkipPrevious,
            mirrored = true,
            iconSize = AppleMusicTransportIconSize,
            onClick = playerConnection::seekToPrevious,
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(AppleMusicPlayPauseIconSize + 20.dp)
                    .clip(CircleShape),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(AppleMusicPlayPauseIconSize),
                    strokeWidth = 3.dp,
                )
            } else {
                AppleMusicTransportButton(
                    iconRes = if (isPlaying) R.drawable.player_pause else R.drawable.player_play,
                    enabled = true,
                    mirrored = false,
                    iconSize = AppleMusicPlayPauseIconSize,
                    onClick = onPlayPauseClick,
                )
            }
        }
        AppleMusicTransportButton(
            iconRes = R.drawable.player_fast_forward,
            enabled = canSkipNext,
            mirrored = false,
            iconSize = AppleMusicTransportIconSize,
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
