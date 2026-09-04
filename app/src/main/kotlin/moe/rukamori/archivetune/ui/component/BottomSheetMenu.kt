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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/**
 * ── Floating liquid-glass overflow popup (2026-09-04 redesign) ─────────────
 *
 * The shared song/artist/album overflow menu container — everything opened
 * through `menuState.show { ... }` — is no longer a Material [ModalBottomSheet]
 * (user request 2026-09-04: "The song overflow popup shouldn't be a bottomsheet
 * popup anymore... a floating popup on the bottom of the page"). It is now a
 * detached, rounded floating card anchored to the bottom of the screen with
 * 16dp side margins and a gap above the navigation-bar inset, matching the
 * reference screenshot.
 *
 * Glass: the popup surface itself carries the liquid-glass blur (same
 * treatment as the Apple-Music-style lyrics overflow popup — kyant
 * `drawBackdrop` with vibrancy + a 32dp blur over the content behind, plus a
 * dark translucent tint), sampled from the app-wide NavHost backdrop provided
 * via [LocalLiquidGlassBackdrop]. The area OUTSIDE the popup is NOT blurred —
 * it only gets a plain dim scrim (user: "remove the blur outside of the
 * popup"). When Liquid Glass is off (or pre-Android-12), the popup falls back
 * to a near-opaque charcoal card — the same look the sheet had before frost.
 *
 * The previous PixelCopy snapshot frost (window capture + off-thread box
 * blur) was removed along with this redesign: the kyant path is live,
 * cheaper and works while content scrolls behind the popup; the fallback no
 * longer needs a capture at all.
 *
 * Content theming: the menu content renders inside a [MaterialTheme] overlay
 * that remaps the container colors the menus actually use —
 * `surfaceContainerLow` (the section cards and "Now playing" header surfaces)
 * becomes transparent so the glass shows through, `surfaceContainerHigh`
 * (the action-grid chips) becomes a translucent hairline chip, dividers turn
 * faint white, and the destructive rows use iOS System Red — the exact
 * material the lyrics popup uses. No menu file needed to change.
 *
 * Animation: the popup slides up from the bottom with a no-bounce spring and
 * fades in; dismissal (scrim tap, back button, or any menu item calling
 * [MenuState.dismiss]) reverses the animation before the popup leaves
 * composition. `state.content` is untouched — every menu item, dialog and
 * callback keeps working exactly as before.
 */
@Composable
fun BottomSheetMenu(
    modifier: Modifier = Modifier,
    state: MenuState,
    background: Color = Color.Unspecified,
) {
    val focusManager = LocalFocusManager.current

    state.dialogContent?.invoke()

    // Render state: true from the moment the popup enters composition until
    // its exit animation completes — `state.isVisible` flipping false only
    // REQUESTS dismissal; the popup stays composed while the exit animation
    // plays. This gives every dismissal path (scrim tap, back press, menu
    // item) the same animated exit the old ModalBottomSheet had.
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

    // Back button dismisses the popup (the old sheet handled back via its
    // dialog window; the overlay has to opt in explicitly).
    BackHandler(enabled = renderState) {
        state.isVisible = false
    }

    if (!renderState) return

    val alpha = enterProgress.value

    // ── Liquid glass surface (same recipe as the lyrics overflow popup) ──
    // The NavHost-layer backdrop is recorded by MainActivity and shared via
    // CompositionLocal; this popup is a SIBLING of the layer-capturing NavHost
    // Box (both live in the activity's root Box), so sampling it here is the
    // safe non-reentrant case.
    val liquidGlassBackdrop = LocalLiquidGlassBackdrop.current
    val glassModifier =
        remember(liquidGlassBackdrop) {
            if (liquidGlassBackdrop != null && background.isUnspecified) {
                Modifier.drawBackdrop(
                    backdrop = liquidGlassBackdrop,
                    effects = {
                        vibrancy()
                        // 32dp matches the lyrics overflow popup's "strong
                        // backdrop blur" reference.
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
    // Translucent tint drawn OVER the blur — the "dark charcoal glass" of the
    // reference (dark theme) / a bright frosted card (light theme).
    val glassTint =
        if (dark) {
            Color(0x8C1C1C1E)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f)
        }
    // Near-opaque fallback when there is no backdrop to sample (Liquid Glass
    // off, pre-Android-12, or a caller pinned an explicit background color).
    val fallbackColor =
        when {
            !background.isUnspecified -> background
            dark -> Color(0xF01C1C1E)
            else -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f)
        }

    // Content colors on the glass: white ink on the dark charcoal glass, dark
    // ink on the light frosted card — mirrors [liquidGlassContentColor].
    val contentInk =
        if (dark) {
            Color.White
        } else {
            Color(0xFF1C1B1F)
        }

    // Theme overlay so the menus' own MaterialTheme color reads render the
    // lyrics-popup material on the glass without touching any menu file:
    //  * surfaceContainerLow  -> transparent (MenuSurfaceSection cards + the
    //    "Now playing" header surfaces sit directly on the glass)
    //  * surfaceContainerHigh -> translucent chip (NewActionGrid buttons)
    //  * outlineVariant       -> hairline divider
    //  * error                -> iOS System Red destructive rows
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
    // The popup never covers the whole screen — it floats at the bottom and
    // its content scrolls internally (the menus are LazyColumns).
    // Compact sizing (user request 2026-09-04: "make the popup compact so
    // that it only around 40% of the screen length"): the card is capped at
    // 40% of the screen height. Taller menus scroll inside the card exactly
    // as before — no menu item, dialog or callback is lost; the menus'
    // LazyColumns keep their keys and scroll positions.
    val maxPopupHeight = configuration.screenHeightDp.dp * 0.40f
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(modifier = modifier.fillMaxSize()) {
        // Plain dim scrim — NO blur outside the popup (user request
        // 2026-09-04). Fades in/out with the popup's enter/exit animation.
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

        // The floating popup card.
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
                        // Slide up from below while entering, sink back while
                        // exiting — draw-phase offset, no layout invalidation.
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
                        // Consume taps inside the popup so they never reach
                        // the scrim and dismiss the menu accidentally.
                    },
        ) {
            CompositionLocalProvider(
                LocalContentColor provides contentInk,
                // The lyrics-popup transparent-surface fix, generalised (2026-09-04):
                // signal menu content that it sits on live glass so section cards
                // (MenuSurfaceSection) render transparent instead of the opaque
                // Muzo grey that was hiding the frost (user report: "the background
                // behind the text is still opaque"). Only provided when the kyant
                // backdrop is actually sampling — the fallback charcoal card keeps
                // the opaque section material for contrast.
                LocalGlassMenuContent provides (glassModifier != null),
            ) {
                MaterialTheme(colorScheme = glassColorScheme) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        state.content(this)
                    }
                }
            }
        }
    }
}

/** The floating menu card's shape — fully rounded, detached from every edge. */
private val FloatingMenuShape = RoundedCornerShape(28.dp)
