/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package moe.rukamori.archivetune.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

val LocalMenuState = compositionLocalOf { MenuState() }

@Stable
class MenuState(
    isVisible: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    var isVisible by mutableStateOf(isVisible)
    var content by mutableStateOf(content)
    internal var dialogContent by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set

    fun show(content: @Composable ColumnScope.() -> Unit) {
        dialogContent = null
        isVisible = true
        this.content = content
    }

    fun dismiss() {
        isVisible = false
    }

    fun showDialog(content: @Composable () -> Unit) {
        isVisible = false
        dialogContent = content
    }

    fun dismissDialog() {
        dialogContent = null
    }
}

@Composable
fun BottomSheetMenu(
    modifier: Modifier = Modifier,
    state: MenuState,
    background: Color = Color.Unspecified,
) {
    val focusManager = LocalFocusManager.current

    state.dialogContent?.invoke()

    var renderState by remember { mutableStateOf(false) }
    val enterProgress = remember { Animatable(0f) }

    val scrimInteractionSource = remember { MutableInteractionSource() }
    val popupInteractionSource = remember { MutableInteractionSource() }

    LaunchedEffect(state.isVisible) {
        if (state.isVisible) {
            renderState = true
            enterProgress.snapTo(0f)
            enterProgress.animateTo(
                targetValue = 1f,
                animationSpec =
                    spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
            )
        } else if (renderState) {
            enterProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 200),
            )
            focusManager.clearFocus()
            renderState = false
        }
    }

    BackHandler(enabled = renderState) {
        state.isVisible = false
    }

    if (!renderState) return

    val alpha = enterProgress.value

    val menuGlassBackdrop = LocalMenuGlassBackdrop.current
    val liquidGlassBackdrop = menuGlassBackdrop ?: LocalLiquidGlassBackdrop.current
    val glassModifier =
        remember(liquidGlassBackdrop) {
            if (liquidGlassBackdrop != null && background.isUnspecified) {
                Modifier.drawBackdrop(
                    backdrop = liquidGlassBackdrop,
                    effects = {
                        vibrancy()

                        blur(32f.dp.toPx())
                    },
                    onDrawBackdrop = { drawBackdrop ->
                        drawBackdrop()
                    },
                    shape = { FloatingMenuShape },
                )
            } else {
                null
            }
        }

    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    val glassTint =
        if (dark) {
            Color(0x8C1C1C1E)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.42f)
        }

    // Fully opaque when liquid glass is off: the previous 0xF0 (94%) fill let
    // the player's controls behind ghost through the card (the "glitched"
    // look), and the light 0.97 fill was near enough that it only banding-diffed.
    // Callers that pass an explicit background keep full control of the alpha.
    val fallbackColor =
        when {
            !background.isUnspecified -> background
            dark -> Color(0xFF1C1C1E)
            else -> MaterialTheme.colorScheme.surfaceContainer
        }

    val contentInk =
        if (dark) {
            Color.White
        } else {
            Color(0xFF1C1B1F)
        }

    val glassColorScheme =
        MaterialTheme.colorScheme.copy(
            onSurface = contentInk,
            onBackground = contentInk,
            onSurfaceVariant = contentInk.copy(alpha = 0.72f),
            surfaceContainerLow = Color.Transparent,
            surfaceContainer = Color.Transparent,
            surfaceContainerHigh =
                if (dark) {
                    Color.White.copy(alpha = 0.08f)
                } else {
                    Color.Black.copy(alpha = 0.05f)
                },
            surfaceContainerHighest =
                if (dark) {
                    Color.White.copy(alpha = 0.14f)
                } else {
                    Color.Black.copy(alpha = 0.08f)
                },
            outlineVariant = contentInk.copy(alpha = 0.12f),
            error = Color(0xFFFF453A),
        )

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    val maxPopupHeight = configuration.screenHeightDp.dp * 0.40f
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(modifier = modifier.fillMaxSize()) {

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = alpha }
                    .background(Color.Black.copy(alpha = 0.50f))
                    .clickable(
                        interactionSource = scrimInteractionSource,
                        indication = null,
                    ) {
                        state.isVisible = false
                    },
        )

        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = bottomInset + 12.dp)
                    .widthIn(max = 640.dp)
                    .heightIn(max = maxPopupHeight)
                    .fillMaxWidth()
                    .graphicsLayer {
                        this.alpha = alpha

                        translationY = with(density) { (1f - alpha) * 48.dp.toPx() }
                    }
                    .shadow(
                        elevation = 24.dp,
                        shape = FloatingMenuShape,
                        clip = false,
                    )
                    .then(
                        if (glassModifier != null) {
                            glassModifier.background(glassTint)
                        } else {
                            Modifier.background(fallbackColor)
                        },
                    )
                    .clip(FloatingMenuShape)
                    .clickable(
                        interactionSource = popupInteractionSource,
                        indication = null,
                    ) {

                    },
        ) {

            val unglassedColorScheme = MaterialTheme.colorScheme

            // Glass ink theme only when there is actual glass (or the caller
            // pinned an explicit background — that surface may not match the
            // app theme, so the fixed white/dark ink keeps text readable).
            // With liquid glass OFF and no explicit background the popup is an
            // opaque theme surface: menu content must keep the app's regular
            // color scheme, otherwise action tiles (surfaceContainerHigh →
            // white@8%), section cards and dividers (outlineVariant →
            // white@12%) render as translucent ghost shapes on the solid
            // card — the "weird and glitched out" unglassed popup.
            val useGlassInk = glassModifier != null || !background.isUnspecified
            val menuContent: @Composable () -> Unit = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    state.content(this)
                }
            }

            CompositionLocalProvider(
                LocalContentColor provides if (useGlassInk) contentInk else unglassedColorScheme.onSurface,

                LocalGlassMenuContent provides (glassModifier != null),

                LocalUnglassColorScheme provides unglassedColorScheme,
            ) {
                if (useGlassInk) {
                    MaterialTheme(colorScheme = glassColorScheme) {
                        menuContent()
                    }
                } else {
                    menuContent()
                }
            }
        }
    }
}

private val FloatingMenuShape = RoundedCornerShape(28.dp)
