/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.constants.MiniPlayerBackgroundStyle
import moe.rukamori.archivetune.constants.MiniPlayerBackgroundStyleKey
import moe.rukamori.archivetune.constants.MiniPlayerHeight
import moe.rukamori.archivetune.constants.NavigationBarMaxWidth
import moe.rukamori.archivetune.constants.SwipeSensitivityKey
import moe.rukamori.archivetune.playback.artwork.PlayerPaletteCacheKey
import moe.rukamori.archivetune.playback.artwork.guessArtworkProvider
import moe.rukamori.archivetune.ui.component.LocalNavigationBarBackdrop
import moe.rukamori.archivetune.ui.component.LocalLiquidGlassBackdrop
import moe.rukamori.archivetune.ui.component.liquidGlass
import moe.rukamori.archivetune.ui.component.rememberPreSFrostedBitmap
import moe.rukamori.archivetune.ui.theme.PlayerColorExtractor
import moe.rukamori.archivetune.ui.theme.PlayerPaletteCache
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.utils.isLowEndDevice
import kotlin.math.roundToInt

@Composable
fun MiniPlayer(
    positionProvider: () -> Long,
    durationProvider: () -> Long,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
    isPairedWithNavigation: Boolean = false,
) {
    // Read the per-screen "docked" flag. When a playlist-style screen has
    // scrolled past its hero header, it sets LocalMiniPlayerDocked = true
    // via a CompositionLocalProvider in its own subtree. The MiniPlayer
    // then visually shrinks and slides to the bottom-start corner, sitting
    // to the right of the floating Home dock button — matching the
    // SimpMusic behavior the user requested. When the user scrolls back up
    // to the hero, the flag flips back to false and the MiniPlayer springs
    // back to its full-width form.
    val docked = LocalMiniPlayerDocked.current
    // Animate scale + translationX for a smooth spring transition between
    // full-width and docked forms.
    val dockedAnim by animateFloatAsState(
        targetValue = if (docked) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "MiniPlayerDockedAnim",
    )
    val density = LocalDensity.current
    val translationXPx = with(density) { (-160).dp.toPx() }
    val translationYPx = with(density) { 10.dp.toPx() }
    val dockedModifier =
        if (dockedAnim > 0.001f) {
            // Scale down to ~50% so the mini player reads as a small
            // docked icon rather than a full-width bar, and translate
            // left so its left edge lines up to the right of the Home
            // dock button (which sits at start=16dp, width=48dp). The
            // translation is in pixels; we use density to convert from
            // dp so the math is resolution-independent.
            //
            // Slight downward nudge so the scaled-down pill sits at
            // the same vertical center as the Home dock button
            // instead of the original MiniPlayer's center (the
            // BottomSheet reserves 70dp at the bottom; the Home dock
            // is 48dp tall + 12dp bottom padding, so its center is
            // ~10dp below the MiniPlayer's center).
            val scale = 1f - 0.5f * dockedAnim // 1.0 -> 0.5
            modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = translationXPx * dockedAnim
                    translationY = translationYPx * dockedAnim
                }
        } else {
            modifier
        }
    NewMiniPlayer(
        positionProvider = positionProvider,
        durationProvider = durationProvider,
        modifier = dockedModifier,
        pureBlack = pureBlack,
        isPairedWithNavigation = isPairedWithNavigation,
    )
}

@Composable
private fun NewMiniPlayer(
    positionProvider: () -> Long,
    durationProvider: () -> Long,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
    isPairedWithNavigation: Boolean,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    val coroutineScope = rememberCoroutineScope()
    val swipeSensitivity by rememberPreference(SwipeSensitivityKey, 0.73f)
    val swipeThumbnail by rememberPreference(moe.rukamori.archivetune.constants.SwipeThumbnailKey, true)
    val miniPlayerBackgroundStyle by rememberEnumPreference(
        key = MiniPlayerBackgroundStyleKey,
        defaultValue = MiniPlayerBackgroundStyle.THEME,
    )
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    // Keep the previous valid palette while the next artwork loads; replace only on success.
    var gradientColors by remember {
        mutableStateOf<List<Color>>(emptyList())
    }
    var hasValidPalette by remember { mutableStateOf(false) }
    val fallbackColor = MaterialTheme.colorScheme.surface.toArgb()
    // Only the artwork-derived styles need palette extraction; THEME, FROSTED and
    // LIQUID_GLASS don't.
    val shouldUseArtworkBackground =
        miniPlayerBackgroundStyle == MiniPlayerBackgroundStyle.GRADIENT ||
            miniPlayerBackgroundStyle == MiniPlayerBackgroundStyle.GLOW
    val darkTheme = isSystemInDarkTheme()

    LaunchedEffect(
        mediaMetadata?.id,
        mediaMetadata?.thumbnailUrl,
        shouldUseArtworkBackground,
        fallbackColor,
        darkTheme,
    ) {
        if (!shouldUseArtworkBackground) {
            gradientColors = emptyList()
            hasValidPalette = false
            return@LaunchedEffect
        }

        val currentMetadata = mediaMetadata
        val thumbnailUrl = currentMetadata?.thumbnailUrl
        if (currentMetadata == null || thumbnailUrl.isNullOrBlank()) {
            if (!hasValidPalette) gradientColors = emptyList()
            return@LaunchedEffect
        }

        val cacheKey =
            PlayerPaletteCacheKey(
                mediaId = currentMetadata.id,
                provider = guessArtworkProvider(thumbnailUrl),
                artworkIdentity = thumbnailUrl,
                backgroundMode = miniPlayerBackgroundStyle.name,
                darkTheme = darkTheme,
            )
        PlayerPaletteCache.get(cacheKey)?.let { cachedColors ->
            gradientColors = cachedColors
            hasValidPalette = true
            return@LaunchedEffect
        }

        val request =
            ImageRequest
                .Builder(context)
                .data(thumbnailUrl)
                .size(PlayerColorExtractor.Config.IMAGE_SIZE, PlayerColorExtractor.Config.IMAGE_SIZE)
                .allowHardware(false)
                .build()

        val extractedColors =
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        context.imageLoader.execute(request)
                    }
                if (result !is SuccessResult) {
                    null
                } else {
                    val bitmap = result.image?.toBitmap()
                    if (bitmap == null) {
                        null
                    } else {
                        val palette =
                            withContext(Dispatchers.Default) {
                                Palette
                                    .from(bitmap)
                                    .maximumColorCount(PlayerColorExtractor.Config.MAX_COLOR_COUNT)
                                    .resizeBitmapArea(PlayerColorExtractor.Config.BITMAP_AREA)
                                    .generate()
                            }
                        PlayerColorExtractor.extractGradientColors(
                            palette = palette,
                            fallbackColor = fallbackColor,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                null
            }

        // On failure/cancellation keep the previous valid palette; never force a grey fallback.
        if (extractedColors != null) {
            val stillCurrent =
                mediaMetadata?.id == currentMetadata.id &&
                    mediaMetadata?.thumbnailUrl == thumbnailUrl
            if (stillCurrent) {
                PlayerPaletteCache.put(cacheKey, extractedColors)
                gradientColors = extractedColors
                hasValidPalette = true
            }
        } else if (!hasValidPalette) {
            gradientColors = emptyList()
        }
    }

    val backgroundPalette =
        remember(gradientColors) {
            MiniPlayerBackgroundPalette.from(gradientColors)
        }
    val liquidGlassMaster by rememberPreference(
        moe.rukamori.archivetune.constants.LiquidGlassEnabledKey,
        defaultValue = false,
    )
    val effectiveBackgroundStyle =
        when {
            miniPlayerBackgroundStyle == MiniPlayerBackgroundStyle.LIQUID_GLASS && !liquidGlassMaster ->
                MiniPlayerBackgroundStyle.THEME
            miniPlayerBackgroundStyle == MiniPlayerBackgroundStyle.LIQUID_GLASS &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> MiniPlayerBackgroundStyle.THEME
            miniPlayerBackgroundStyle == MiniPlayerBackgroundStyle.LIQUID_GLASS ->
                MiniPlayerBackgroundStyle.LIQUID_GLASS
            miniPlayerBackgroundStyle == MiniPlayerBackgroundStyle.FROSTED -> MiniPlayerBackgroundStyle.FROSTED
            shouldUseArtworkBackground && backgroundPalette != null -> miniPlayerBackgroundStyle
            else -> MiniPlayerBackgroundStyle.THEME
        }

    val contentColors =
        rememberMiniPlayerContentColors(
            useArtworkBackground =
                effectiveBackgroundStyle == MiniPlayerBackgroundStyle.GRADIENT ||
                    effectiveBackgroundStyle == MiniPlayerBackgroundStyle.GLOW ||
                    effectiveBackgroundStyle == MiniPlayerBackgroundStyle.LIQUID_GLASS,
        )
    val miniPlayerShape =
        remember(isPairedWithNavigation) {
            if (isPairedWithNavigation) {
                RoundedCornerShape(
                    topStart = 28.dp,
                    topEnd = 28.dp,
                    bottomStart = 12.dp,
                    bottomEnd = 12.dp,
                )
            } else {
                null
            }
        } ?: MaterialTheme.shapes.extraLarge

    SwipeableMiniPlayerBox(
        modifier = modifier,
        contentMaxWidth = if (isPairedWithNavigation) NavigationBarMaxWidth else null,
        swipeSensitivity = swipeSensitivity,
        swipeThumbnail = swipeThumbnail,
        playerConnection = playerConnection,
        layoutDirection = layoutDirection,
        coroutineScope = coroutineScope,
        pureBlack = pureBlack,
        useLegacyBackground = false,
    ) { offsetX ->
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(MiniPlayerHeight)
                    // Per audit (2026-08-30): `Modifier.offset { IntOffset(offsetX.roundToInt(), 0) }`
                    // ran in the LAYOUT phase on every drag frame of the mini player's
                    // horizontal-swipe gesture and invalidated the Box's children for
                    // re-layout each frame. Folding the translation into `graphicsLayer`
                    // moves the transform to the DRAW phase — the layout pass stays cached
                    // while the user swipes. No visual change.
                    .graphicsLayer {
                        translationX = offsetX
                    }
                    .clip(miniPlayerShape),
        ) {
            MiniPlayerBackground(
                style = effectiveBackgroundStyle,
                palette = backgroundPalette,
                modifier = Modifier.fillMaxSize(),
            )
            NewMiniPlayerContent(
                positionProvider = positionProvider,
                durationProvider = durationProvider,
                playerConnection = playerConnection,
                colors = contentColors,
            )
        }
    }
}

@Composable
private fun rememberMiniPlayerContentColors(useArtworkBackground: Boolean): MiniPlayerContentColors {
    val colorScheme = MaterialTheme.colorScheme
    return remember(
        useArtworkBackground,
        colorScheme.primary,
        colorScheme.onPrimary,
        colorScheme.outline,
        colorScheme.onSurface,
        colorScheme.onSurfaceVariant,
        colorScheme.surface,
        colorScheme.surfaceContainerHighest,
        colorScheme.surfaceVariant,
        colorScheme.primaryContainer,
        colorScheme.onPrimaryContainer,
    ) {
        if (useArtworkBackground) {
            MiniPlayerContentColors(
                title = Color.White,
                secondary = Color.White.copy(alpha = 0.72f),
                progress = Color.White,
                progressTrack = Color.White.copy(alpha = 0.24f),
                artworkContainer = Color.White.copy(alpha = 0.14f),
                artworkBorder = Color.White.copy(alpha = 0.22f),
                primaryButtonContainer = Color.White.copy(alpha = 0.92f),
                primaryButtonIcon = Color.Black,
                secondaryButtonContainer = Color.Black.copy(alpha = 0.22f),
                buttonIcon = Color.White,
                disabledButtonIcon = Color.White.copy(alpha = 0.38f),
                togetherContainer = Color.White.copy(alpha = 0.16f),
                togetherContent = Color.White,
            )
        } else {
            MiniPlayerContentColors(
                title = colorScheme.onSurface,
                secondary = colorScheme.onSurfaceVariant,
                progress = colorScheme.primary,
                progressTrack = colorScheme.outline.copy(alpha = 0.18f),
                artworkContainer = colorScheme.surfaceVariant,
                artworkBorder = colorScheme.outline.copy(alpha = 0.2f),
                primaryButtonContainer = colorScheme.primary,
                primaryButtonIcon = colorScheme.onPrimary,
                secondaryButtonContainer = colorScheme.surfaceContainerHighest,
                buttonIcon = colorScheme.onSurface,
                disabledButtonIcon = colorScheme.onSurface.copy(alpha = 0.38f),
                togetherContainer = colorScheme.primaryContainer,
                togetherContent = colorScheme.onPrimaryContainer,
            )
        }
    }
}

// Frosted mini-player backdrop: blur radius in raw px (RenderEffect works in pixels) and the
// bounded fraction of blurred content shown over the opaque base — same recipe as the nav bar.
private const val FrostedMiniPlayerBlurRadiusPx = 60f
private const val FrostedMiniPlayerOverlayAlpha = 0.30f

@Composable
private fun MiniPlayerBackground(
    style: MiniPlayerBackgroundStyle,
    palette: MiniPlayerBackgroundPalette?,
    modifier: Modifier = Modifier,
) {
    // Frosted blur on the mini player relies on RenderEffect (API 31+). On pre-S the CPU-blurred
    // bitmap fallback produced visible glitches on older devices, so FROSTED is forcibly
    // downgraded to THEME. The Settings screen surfaces a "not supported on Android versions
    // below 12" warning under the mini player background selector when running on pre-S.
    val isPreS = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    val effectiveStyle = if (isPreS && style == MiniPlayerBackgroundStyle.FROSTED) {
        MiniPlayerBackgroundStyle.THEME
    } else if (isPreS && style == MiniPlayerBackgroundStyle.LIQUID_GLASS) {
        MiniPlayerBackgroundStyle.THEME
    } else {
        style
    }
    when (effectiveStyle) {
        MiniPlayerBackgroundStyle.THEME -> {
            Box(
                modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }

        MiniPlayerBackgroundStyle.LIQUID_GLASS -> {
            val liquidGlassBackdrop = LocalLiquidGlassBackdrop.current
            val baseColor = MaterialTheme.colorScheme.surfaceContainerHigh
            if (liquidGlassBackdrop != null) {
                Box(
                    modifier =
                        modifier.liquidGlass(
                            backdrop = liquidGlassBackdrop,
                            shape = MaterialTheme.shapes.extraLarge,
                            interactive = false,
                            baseColor = baseColor,
                        ),
                )
            } else {
                Box(
                    modifier = modifier.background(baseColor),
                )
            }
        }

        MiniPlayerBackgroundStyle.FROSTED -> {
            val backdrop = LocalNavigationBarBackdrop.current
            val baseColor = MaterialTheme.colorScheme.surfaceContainerHigh
            if (backdrop == null) {
                Box(modifier = modifier.background(baseColor))
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                // Pre-S: CPU-blurred bitmap fallback. The bitmap is the small slice under the
                // mini player (not the full screen), captured and blurred every ~80 ms — fast
                // enough for smooth frosted tracking without tanking pre-S hardware. The blurred
                // slice is already aligned to the mini player's top-left, so we draw at (0, 0).
                // Per audit (2026-08-30): hoisted to State holders so the
                // onGloballyPositioned lambda can be memoized on the holder
                // (stable across recompositions).
                val positionInRootState = remember { mutableStateOf(Offset.Zero) }
                val miniPlayerSizeState = remember { mutableStateOf(IntSize.Zero) }
                val positionInRoot by positionInRootState
                val miniPlayerSize by miniPlayerSizeState
                val blurredBitmap = rememberPreSFrostedBitmap(
                    backdrop = backdrop,
                    barPositionInRoot = positionInRoot,
                    barSize = miniPlayerSize,
                    blurRadiusPx = FrostedMiniPlayerBlurRadiusPx,
                    updateIntervalMs = if (LocalContext.current.isLowEndDevice()) 160L else 80L,
                )
                Box(
                    modifier =
                        modifier
                            .onGloballyPositioned(
                                // Per audit (2026-08-30): memoize the lambda so the
                                // OnGloballyPositionedElement.equals() returns true
                                // across recompositions.
                                remember(positionInRootState, miniPlayerSizeState) {
                                    { coordinates ->
                                        positionInRootState.value = coordinates.positionInRoot()
                                        miniPlayerSizeState.value = coordinates.size
                                    }
                                },
                            )
                            .background(baseColor),
                ) {
                    if (blurredBitmap != null) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        alpha = FrostedMiniPlayerOverlayAlpha
                                        clip = true
                                    }.drawBehind {
                                        drawImage(blurredBitmap)
                                    },
                        )
                    }
                }
            } else {
                // Per audit (2026-08-30): hoisted to State holder for onGloballyPositioned
                // lambda memoization.
                val positionInRootState = remember { mutableStateOf(Offset.Zero) }
                val positionInRoot by positionInRootState
                Box(
                    modifier =
                        modifier
                            .onGloballyPositioned(
                                remember(positionInRootState) {
                                    { coordinates -> positionInRootState.value = coordinates.positionInRoot() }
                                },
                            )
                            .background(baseColor),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    renderEffect =
                                        BlurEffect(
                                            radiusX = FrostedMiniPlayerBlurRadiusPx,
                                            radiusY = FrostedMiniPlayerBlurRadiusPx,
                                            edgeTreatment = TileMode.Clamp,
                                        )
                                    alpha = FrostedMiniPlayerOverlayAlpha
                                    clip = true
                                }.drawBehind {
                                    val offset = backdrop.contentOffsetInRoot - positionInRoot
                                    translate(offset.x, offset.y) {
                                        drawLayer(backdrop.layer)
                                    }
                                },
                    )
                }
            }
        }

        MiniPlayerBackgroundStyle.GRADIENT -> {
            val colors = requireNotNull(palette)
            // Per audit (2026-08-30): hoist the Brush.verticalGradient + Color
            // constants out of the .background() call into `remember(colors)`.
            // Previously, every recomposition of MiniPlayer (which is on screen
            // 100% of the time during playback) allocated a new ShaderBrush +
            // 3 × Color.copy(alpha=...) values + a Color.Black.copy(alpha=...).
            // The brush is now allocated ONCE per `colors` tuple change.
            val gradientBrush = remember(colors) {
                Brush.verticalGradient(
                    colorStops =
                        arrayOf(
                            0f to colors.first.copy(alpha = 0.95f),
                            0.52f to colors.second.copy(alpha = 0.82f),
                            1f to colors.third.copy(alpha = 0.72f),
                        ),
                )
            }
            val overlayColor = remember { Color.Black.copy(alpha = 0.32f) }
            Box(modifier = modifier) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(gradientBrush),
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(overlayColor),
                )
            }
        }

        MiniPlayerBackgroundStyle.GLOW -> {
            val colors = requireNotNull(palette)
            Box(
                modifier =
                    modifier.drawWithCache {
                        val width = size.width
                        val height = size.height
                        val startGlow =
                            Brush.radialGradient(
                                colors = listOf(colors.first.copy(alpha = 0.82f), colors.first.copy(alpha = 0.38f), Color.Transparent),
                                center = Offset(width * 0.12f, height * 0.42f),
                                radius = width * 0.72f,
                            )
                        val endGlow =
                            Brush.radialGradient(
                                colors = listOf(colors.second.copy(alpha = 0.78f), colors.second.copy(alpha = 0.34f), Color.Transparent),
                                center = Offset(width * 0.88f, height * 0.58f),
                                radius = width * 0.72f,
                            )
                        val topGlow =
                            Brush.radialGradient(
                                colors = listOf(colors.third.copy(alpha = 0.58f), Color.Transparent),
                                center = Offset(width * 0.52f, height * 0.05f),
                                radius = width * 0.54f,
                            )
                        val bottomGlow =
                            Brush.radialGradient(
                                colors = listOf(colors.fourth.copy(alpha = 0.46f), Color.Transparent),
                                center = Offset(width * 0.46f, height * 1.05f),
                                radius = width * 0.54f,
                            )

                        onDrawBehind {
                            drawRect(Color.Black)
                            drawRect(startGlow)
                            drawRect(endGlow)
                            drawRect(topGlow)
                            drawRect(bottomGlow)
                            drawRect(Color.Black.copy(alpha = 0.24f))
                        }
                    },
            )
        }
    }
}

@Immutable
private data class MiniPlayerBackgroundPalette(
    val first: Color,
    val second: Color,
    val third: Color,
    val fourth: Color,
) {
    companion object {
        fun from(colors: List<Color>): MiniPlayerBackgroundPalette? {
            val first = colors.firstOrNull() ?: return null
            val second = colors.getOrElse(1) { first }
            val third = colors.getOrElse(2) { second }
            val fourth = colors.getOrElse(3) { first }
            return MiniPlayerBackgroundPalette(
                first = first,
                second = second,
                third = third,
                fourth = fourth,
            )
        }
    }
}
