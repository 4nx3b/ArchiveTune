/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Liquid glass / backdrop blur effect, ported from SimpMusic
 * (https://github.com/maxrave-dev/SimpMusic) and simplified for the
 * Android-only ArchiveTune build. The original KMP expect/actual
 * pattern is collapsed into a single file because ArchiveTune does
 * not have a JVM/iOS target.
 */

package moe.rukamori.archivetune.ui.component

import android.os.SystemClock
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as Material3IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.graphicsLayer

/** Alias so call sites can refer to a stable type name regardless of the backdrop impl. */
typealias PlatformBackdrop = LayerBackdrop

/**
 * Returns `true` immediately on first composition.
 *
 * Historically this deferred the expensive `Modifier.layerBackdrop` recording
 * until the NavHost page transition (default 250ms slide-in-from-right) had
 * finished, to avoid the incoming page's backdrop + the outgoing page's
 * backdrop + the app-wide NavHost backdrop all competing for the GPU/frame
 * budget during the slide.
 *
 * Per user report (2026-08-29): "There is a slight delay for liquid glass
 * pills to take effect now. I want them to be immediate." The user perceives
 * the 250ms settle window as a visible "frosted → liquid glass" swap.
 *
 * A batch-8 attempt to remove the delay by switching `MainActivity`'s
 * bottom-nav slide wrapper from `Modifier.offset { IntOffset(0, y) }`
 * (layout phase) to `Modifier.graphicsLayer { translationY = y }` (draw
 * phase) was reverted per user report (2026-08-30): "i somehow messed up
 * liquid glass navigation bar. Restore it to how it used to be before."
 * The graphicsLayer variant caused the nav bar to render on top of the
 * mini-player (the wrapper Box's layout space stayed claimed at
 * BottomCenter even when translated off-screen, so the BottomSheetPlayer's
 * collapsed anchor couldn't see the bar's actual position).
 *
 * The original lag symptom may therefore reappear during page transitions
 * when liquid glass surfaces are present. The user has explicitly opted
 * for immediate pills anyway — perceived transition lag is preferable to
 * the visible "frosted → liquid glass" swap. If the lag becomes unacceptable
 * again, a future fix should target the root cause (per-frame re-layout of
 * the FloatingNavigationToolbar subtree) WITHOUT changing the wrapper's
 * layout phase, e.g. by hoisting the `onGloballyPositioned` callbacks into
 * a `Modifier.Node` or by reading `bottomNavigationBarHeight` and
 * `playerBottomSheetState.progress` directly from a State rather than
 * recomposing the wrapper Box every frame.
 *
 * Call sites pattern (unchanged):
 * ```
 * val screenSettled = rememberLayerBackdropSettled()
 * val layerBackdropActive = liquidGlassHeaderActive && !lyricsFullScreen && screenSettled
 * ```
 *
 * @param delayMillis Kept for API compatibility; ignored.
 */
@Composable
fun rememberLayerBackdropSettled(@Suppress("UNUSED_PARAMETER") delayMillis: Long = 0L): Boolean = true

/**
 * Records the content of the composable it is called on into a [LayerBackdrop]
 * that downstream [liquidGlass] modifiers can sample from. Mirrors SimpMusic's
 * `rememberBackdrop` + `Modifier.layerBackdrop` pair.
 */
@Composable
fun rememberBackdrop(color: Color): PlatformBackdrop =
    rememberLayerBackdrop {
        drawRect(color)
        drawContent()
    }

fun Modifier.layerBackdrop(backdrop: PlatformBackdrop): Modifier = this.layerBackdrop(backdrop)

/**
 * App-content [LayerBackdrop] used by the Liquid Glass mini player and the Liquid
 * Glass navigation bar. Created in [moe.rukamori.archivetune.MainActivity] and
 * applied via [Modifier.layerBackdrop] to the same Box that already records
 * content for the frosted nav bar — so the backdrop captures the entire app
 * surface every frame, and any sibling consumer (mini player / nav bar) can
 * sample it with [Modifier.liquidGlass].
 *
 * Null when Liquid Glass is disabled or the device is below Android 12 (the
 * kyant RuntimeShader stack requires API 31+).
 */
val LocalLiquidGlassBackdrop = compositionLocalOf<LayerBackdrop?> { null }

/**
 * Dedicated [LayerBackdrop] for the floating liquid-glass overflow menu
 * ([BottomSheetMenu]) — the song/artist/album popup (2026-09-04, user report:
 * "The liquid glass blur behind the song popup is static. it should render in
 * real time just like new lyrics popup").
 *
 * The app-wide [LocalLiquidGlassBackdrop] only records the NavHost content
 * slot, which excludes the bottom-bar slot — the mini player and the
 * navigation bar — exactly the region the floating popup hovers over. While
 * the menu is open nothing in the NavHost changes (the scrim blocks
 * interaction), so sampling that layer renders a frozen smear: the "static"
 * glass the user reported. The lyrics popup renders in real time because it
 * samples the full player surface behind it, whose drifting artwork and
 * progress continuously re-record the layer.
 *
 * This backdrop is attached (conditionally, only while the menu is visible)
 * to the container that wraps the ENTIRE app surface — the rail row plus the
 * Scaffold with its top bar, NavHost pages, mini player and navigation bar —
 * so the popup's frost samples everything actually behind it. The mini
 * player's animated progress line and artwork crossfades re-record the layer
 * every frame they change, and the kyant GraphicsLayer reference chain
 * propagates those changes into the popup's blur without any manual
 * invalidation — the exact same live mechanism the lyrics popup uses.
 *
 * 2026-09-04 (scroll-smoothness): the value is now a [Backdrop] rather than a
 * hard [LayerBackdrop], because the recorder attached in MainActivity is a
 * [ThrottledLayerBackdrop] — same live sampling, but the layer is only
 * re-recorded at most every [ThrottledLayerBackdropDefaultIntervalMillis]
 * (see its docs for why).
 *
 * Provided by MainActivity; null when Liquid Glass is off or below
 * Android 12. [BottomSheetMenu] prefers it over [LocalLiquidGlassBackdrop].
 */
val LocalMenuGlassBackdrop = compositionLocalOf<Backdrop?> { null }

/**
 * Default refresh cap for [ThrottledLayerBackdrop] — ~30 fps.
 *
 * User report (2026-09-04): "the scrolling in popup lags a bit sometimes
 * when there's canvas, sometimes without canvas too". Root cause: kyant's
 * `Modifier.layerBackdrop` re-records the ENTIRE app surface into its
 * GraphicsLayer on EVERY draw invalidation of the recorded subtree — the
 * mini player's progress line ticks, artwork crossfades, and above all a
 * playing canvas (video) re-record the whole screen every single frame,
 * stealing main-thread + GPU budget from the popup's LazyColumn scroll.
 *
 * Capping the RECORD rate at ~30 fps halves that cost while keeping the
 * frost genuinely real-time: behind a 32 dp blur, a dim scrim and a
 * translucent tint, a 30 fps frost is indistinguishable from 60 fps —
 * the drifting artwork and the progress line still visibly move through
 * the glass. When the recorded content is static (nothing animates behind
 * the menu) the node's draw never re-runs, so no records happen at all
 * and the cost is zero, exactly like the unthrottled recorder.
 */
internal const val ThrottledLayerBackdropDefaultIntervalMillis = 33L

/**
 * A [Backdrop] whose recorded layer refreshes at most once per
 * [minIntervalMillis] — the throttled sibling of kyant's
 * `rememberLayerBackdrop` + `Modifier.layerBackdrop` pair (same consumer
 * protocol: any `Modifier.drawBackdrop(backdrop = ...)` can sample it).
 *
 * The recorder node draws the content TWICE per refresh — once to the
 * screen (normal `drawContent()`) and once into [graphicsLayer] (the
 * side-recording the frost samples) — which is exactly what kyant's
 * `LayerBackdropNode` does, except kyant re-records on every invalidation.
 * See [ThrottledLayerBackdropDefaultIntervalMillis] for why the cap exists.
 */
@Stable
class ThrottledLayerBackdrop internal constructor(
    val graphicsLayer: GraphicsLayer,
    internal val minIntervalMillis: Long,
) : Backdrop {

    override val isCoordinatesDependent: Boolean get() = true

    /** Coordinates of the node carrying [Modifier.throttledLayerBackdrop]. */
    internal var layerCoordinates: LayoutCoordinates? by mutableStateOf(null)

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val coordinates = coordinates ?: return
        val layerCoordinates = layerCoordinates ?: return
        withTransform({
            // No layerBlock is ever passed by the menu/cast consumers, so the
            // inverse-transform branch of kyant's LayerBackdrop is not needed;
            // keep the same defensive position fallback it uses.
            val offset =
                try {
                    layerCoordinates.localPositionOf(coordinates)
                } catch (_: Exception) {
                    coordinates.positionInWindow() - layerCoordinates.positionInWindow()
                }
            translate(-offset.x, -offset.y)
        }) {
            drawLayer(graphicsLayer)
        }
    }
}

/** Creates a [ThrottledLayerBackdrop] (see [ThrottledLayerBackdropDefaultIntervalMillis]). */
@Composable
fun rememberThrottledLayerBackdrop(
    graphicsLayer: GraphicsLayer = rememberGraphicsLayer(),
    minIntervalMillis: Long = ThrottledLayerBackdropDefaultIntervalMillis,
): ThrottledLayerBackdrop = remember(graphicsLayer) {
    ThrottledLayerBackdrop(graphicsLayer, minIntervalMillis)
}

/** Attaches the throttled recorder for [backdrop] to this modifier's node. */
fun Modifier.throttledLayerBackdrop(backdrop: ThrottledLayerBackdrop): Modifier =
    this then ThrottledLayerBackdropElement(backdrop)

private class ThrottledLayerBackdropElement(
    val backdrop: ThrottledLayerBackdrop,
) : ModifierNodeElement<ThrottledLayerBackdropNode>() {
    override fun create() = ThrottledLayerBackdropNode(backdrop)

    override fun update(node: ThrottledLayerBackdropNode) {
        if (node.backdrop !== backdrop) {
            node.backdrop.layerCoordinates = null
            node.backdrop = backdrop
        }
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "throttledLayerBackdrop"
        properties["backdrop"] = backdrop
        properties["minIntervalMillis"] = backdrop.minIntervalMillis
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ThrottledLayerBackdropElement) return false
        return backdrop === other.backdrop
    }

    override fun hashCode(): Int = backdrop.hashCode()
}

private class ThrottledLayerBackdropNode(
    var backdrop: ThrottledLayerBackdrop,
) : DrawModifierNode, GlobalPositionAwareModifierNode, Modifier.Node() {

    private var lastRecordUptimeMillis = 0L

    override fun onAttach() {
        super.onAttach()
        // A fresh attach records on the very first draw instead of waiting
        // out a stale interval window.
        lastRecordUptimeMillis = 0L
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val now = SystemClock.uptimeMillis()
        if (now - lastRecordUptimeMillis >= backdrop.minIntervalMillis) {
            lastRecordUptimeMillis = now
            val density = requireDensity()
            backdrop.graphicsLayer.record(size.toIntSize()) {
                val previousDensity = drawContext.density
                drawContext.density = density
                try {
                    // Explicit receiver: GraphicsLayer.record's block is a plain
                    // DrawScope, so the ContentDrawScope member needs to be named
                    // (kyant's LayerBackdropNode does the same via this@draw).
                    this@draw.drawContent()
                } finally {
                    drawContext.density = previousDensity
                }
            }
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            backdrop.layerCoordinates = coordinates
        }
    }

    override fun onDetach() {
        backdrop.layerCoordinates = null
    }
}

/**
 * Blur radius the pill-shaped glass surfaces (header pills, icon pills,
 * action pills) sample the backdrop at (2026-09-04, user report: "The pills
 * have opaque background. it should be blurred.").
 *
 * The original 8dp resting blur — ported from SimpMusic — reads as a flat
 * tint whenever the sampled band behind the pill is short on detail, which
 * made the header pills look like opaque chips. 18dp makes the frost
 * unmistakably glass: whatever sits behind the pill (list content, artwork,
 * the lens-refracted card edges) visibly smears through the surface.
 *
 * Scoped to the PILL composables only — the navigation bar, the mini player
 * and the other full-width surfaces keep the default 8dp so the look the
 * user already approved there is untouched.
 */
val LiquidGlassPillBlurRadius = 18.dp

/**
 * Light-mode ink color for content (icons / labels) drawn on top of a Liquid
 * Glass surface.
 *
 * Liquid Glass surfaces sample the content behind them and then add a light
 * luminance overlay (see [liquidGlass]'s `onDrawSurface`). In dark mode the
 * sampled content is dark, so the surface reads as dark frosted glass and
 * `Color.White` content is perfectly legible. In LIGHT mode, however, the
 * sampled content is bright and the surface renders as a bright frosted
 * white — so `Color.White` icons/labels become nearly invisible (user report
 * 2026-09-03 with Playlists/Library screenshots: header pill text, back
 * arrow, and top-end action icons all "almost invisible").
 *
 * This near-black ink restores legibility on light glass. Dark mode keeps
 * `Color.White` unchanged.
 */
private val LiquidGlassLightContentColor = Color(0xFF1C1B1F)

/**
 * Theme-aware content color (icon tint / label color) for elements rendered
 * inside a Liquid Glass surface that samples page content.
 *
 * - Dark mode: [Color.White] (unchanged — matches the original look).
 * - Light mode: [LiquidGlassLightContentColor] near-black ink, because the
 *   glass surface renders bright in light mode.
 *
 * Dark/light is detected the same way [liquidGlass]'s surface overlay does it
 * (see the "black-pills fix" note there): the APP's MaterialTheme surface
 * luminance, not the system dark mode — so "light mode turned on in the app"
 * with a dark system gets the light-mode ink too.
 *
 * Only use this for glass that samples PAGE CONTENT (header pills, nav bar,
 * mini player). Glass that sits on top of dark artwork (player surfaces,
 * scrims over images) should keep `Color.White` — the artwork keeps the
 * surface dark in both themes.
 */
@Composable
fun liquidGlassContentColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color.White else LiquidGlassLightContentColor

/**
 * Applies the SimpMusic liquid-glass effect to any element.
 *
 * Encapsulates the per-surface [GraphicsLayer], the Kyant `drawBackdrop`
 * effect stack and the press/hold "liquid" interaction (a slight scale-up,
 * deeper refraction and a radial glow that follows the finger, springing
 * back on release). The press gesture is observe-only, so wrapped click
 * handlers keep working.
 *
 * The element MUST be a sibling of the backdrop source (the box carrying
 * [layerBackdrop]); nesting it inside the source creates a render-feedback
 * loop that crashes the RuntimeShader.
 *
 * **Performance note:** The modifier chain is wrapped in `remember` keyed on
 * [backdrop], [shape], [interactive], [baseColor], [blurRadius] and the
 * current dark-theme state. This is the single biggest lever for the "lag
 * when switching pages" symptom: without memoization, every recomposition of
 * the host screen (which can happen many times per second during scroll)
 * rebuilt the entire `drawBackdrop` modifier chain — re-allocating the kyant
 * effect stack and re-installing the RuntimeShader on the GraphicsLayer.
 * Memoizing it means the chain is built ONCE per (backdrop, shape,
 * dark-theme) tuple and reused across recompositions, so scroll-driven
 * recompositions no longer trigger per-frame GPU setup cost.
 *
 * @param baseColor Optional OPAQUE color drawn UNDER the backdrop sample
 *   (via the kyant `onDrawBehind` callback). When the backdrop has content
 *   (e.g. album art behind the nav bar), the backdrop sample covers the
 *   base color — producing the liquid glass refraction effect. When the
 *   backdrop is EMPTY (e.g. bottom of a short page with no content behind),
 *   the backdrop sample is transparent and the opaque base color shows
 *   through — so the element is always visible instead of "completely
 *   transparent". Pass `Color.Unspecified` to skip the base color (the
 *   original SimpMusic behavior — relies on the backdrop always having
 *   content).
 */
@Composable
fun Modifier.liquidGlass(
    backdrop: PlatformBackdrop,
    shape: Shape = CircleShape,
    interactive: Boolean = true,
    baseColor: Color = Color.Unspecified,
    blurRadius: Dp = 8.dp,
): Modifier {
    // Theme-aware dark/light surface overlay (part of the 2026-09-03 light-mode
    // black-pills fix). This used to read isSystemInDarkTheme(), which follows
    // the SYSTEM dark mode — but the app carries its own light/dark preference
    // (AppearanceSettings), so "light mode turned on in the app" with a dark
    // system produced a BLACK 27% tint over an otherwise correctly-lit glass
    // surface. Reading the actual MaterialTheme surface luminance follows the
    // APP's palette in every combination (app-light + system-dark included;
    // pure-black dark mode has surface luminance 0 and stays dark).
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    // Liquid-glass perf fix (ported from 4nx3b, 2026-08-28): memoize the entire
    // drawBackdrop modifier chain so it isn't rebuilt on every recomposition. The
    // chain depends only on (backdrop, shape, interactive, baseColor, isDark) — all
    // stable across scroll-driven recompositions of the host screen. Without this
    // memo, every recomposition rebuilt the kyant effect stack and re-installed the
    // RuntimeShader on the GraphicsLayer, which was the dominant cause of the "lag
    // when switching pages" symptom (the new page's first frames all paid that GPU
    // setup cost while the user was already trying to scroll).
    return remember(backdrop, shape, interactive, baseColor, blurRadius, isDark) {
        this.drawBackdrop(
            backdrop = backdrop,
            effects = {
                val l = 0f
                vibrancy()
                blur(
                    if (l > 0f) {
                        lerp(blurRadius.toPx() * 2f, blurRadius.toPx() * 4f, l)
                    } else {
                        blurRadius.toPx()
                    },
                )
                lens(24f.dp.toPx(), size.minDimension / 4f, false)
            },
            onDrawBackdrop = { drawBackdrop ->
                drawBackdrop()
            },
            shape = { shape },
            onDrawBehind =
                if (baseColor != Color.Unspecified) {
                    { drawRect(baseColor) }
                } else {
                    null
                },
            onDrawSurface = {
                val luminanceAnimation = 0.5f
                val darken = lerp(
                    0.12f,
                    0.5f,
                    ((luminanceAnimation - 0.3f) / 0.5f).coerceIn(0f, 1f),
                )
                drawRect((if (isDark) Color.Black else Color.White).copy(alpha = darken))
            },
        )
    }
}

/**
 * A liquid-glass surface wrapping arbitrary [content] (e.g. a pill of icon
 * buttons). Thin convenience over [liquidGlass]; pure common code.
 *
 * `interactive` defaults to `false` here because callers wrap their own
 * clickable children (e.g. `Material3IconButton`). When `interactive = true`,
 * the kyant `drawBackdrop` modifier installs a press-observing `pointerInput`
 * on the container that competes with the inner click handler — on some
 * devices/Compose versions the press detector consumes the UP event before
 * the inner `IconButton.onClick` fires, making the icon "unclickable".
 * Disabling interactivity keeps the visual blur/vibrancy/lens effect while
 * letting clicks pass through to the wrapped children. Callers that want the
 * press-based lens animation on the container itself can opt back in.
 */
@Composable
fun LiquidGlassContainer(
    backdrop: PlatformBackdrop,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    interactive: Boolean = false,
    blurRadius: Dp = LiquidGlassPillBlurRadius,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.liquidGlass(backdrop, shape, interactive, blurRadius = blurRadius),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * A rounded-rect liquid-glass pill that hosts a row of icon buttons — the
 * SimpMusic "heart + more" cluster that floats at the top-end of the album /
 * artist / playlist header. The pill is 48dp tall with a 24dp corner radius
 * and uses the same `Modifier.liquidGlass` effect as the circular back
 * button.
 *
 * The pill MUST be a sibling of (not a child of) the composable carrying
 * [layerBackdrop] — otherwise the RuntimeShader enters a render-feedback
 * loop and crashes.
 *
 * Caller is responsible for laying out the row of icon buttons inside
 * [content]; each icon should be a 48dp square to match the back button
 * tap target size.
 */
@Composable
fun LiquidGlassActionPill(
    backdrop: PlatformBackdrop,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    blurRadius: Dp = LiquidGlassPillBlurRadius,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier =
            modifier
                .height(48.dp)
                .liquidGlass(
                    backdrop = backdrop,
                    shape = RoundedCornerShape(24.dp),
                    interactive = interactive,
                    blurRadius = blurRadius,
                ),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Cap on a header pill's title width, as a fraction of the screen width,
 * so a long title can never push the leading pill into the trailing pill
 * (2026-09-05, user report: "Playlists with large names have their headers
 * colliding. Introduce a fixed length with text scroll inside").
 *
 * 0.42 of the screen keeps the leading pill (48dp back icon + title + 12dp
 * end pad + 12dp start margin) + the trailing two-icon pill + 12dp margin
 * inside even a 360dp viewport.
 */
private const val GlassPillTitleMaxWidthFraction = 0.42f

/**
 * The title text for a [LiquidGlassActionPill] back pill: capped at a fixed
 * length (a screen-width fraction) and marquee-scrolls inside that length
 * when the title is longer, instead of growing the pill until it collides
 * with the trailing actions pill. Same ink treatment the inline titles used
 * (liquid glass content color, SemiBold, single line).
 */
@Composable
fun GlassPillTitleText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    Text(
        text = text,
        color = liquidGlassContentColor(),
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier =
            modifier
                .widthIn(max = (screenWidthDp * GlassPillTitleMaxWidthFraction).dp)
                .basicMarquee()
                .padding(end = 12.dp),
    )
}

/**
 * Convenience wrapper around [LiquidGlassContainer] for the common single-icon
 * case (e.g. the circular back button shared by the detail screens).
 *
 * Accepts a [Painter] (e.g. from `painterResource(R.drawable.arrow_back)`)
 * because ArchiveTune's existing iconography uses painter resources, not
 * ImageVectors. This keeps call sites unchanged.
 */
@Composable
fun LiquidGlassIconButton(
    backdrop: PlatformBackdrop,
    painter: Painter,
    modifier: Modifier = Modifier.size(48.dp),
    shape: Shape = CircleShape,
    // Default is Color.Unspecified so the resolved tint can be theme-aware
    // (white in dark mode, dark ink in light mode) — see
    // [liquidGlassContentColor]. Passing an explicit color still wins.
    tint: Color = Color.Unspecified,
    contentDescription: String? = null,
    interactive: Boolean = false,
    onClick: () -> Unit,
) {
    val resolvedTint = if (tint == Color.Unspecified) liquidGlassContentColor() else tint
    LiquidGlassContainer(
        backdrop = backdrop,
        modifier = modifier,
        shape = shape,
        interactive = interactive,
    ) {
        Material3IconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                painter = painter,
                contentDescription = contentDescription,
                tint = resolvedTint,
            )
        }
    }
}

/**
 * ImageVector overload — kept for parity with SimpMusic's API.
 */
@Composable
fun LiquidGlassIconButton(
    backdrop: PlatformBackdrop,
    imageVector: ImageVector,
    modifier: Modifier = Modifier.size(48.dp),
    shape: Shape = CircleShape,
    // Default is Color.Unspecified so the resolved tint can be theme-aware
    // (white in dark mode, dark ink in light mode) — see
    // [liquidGlassContentColor]. Passing an explicit color still wins.
    tint: Color = Color.Unspecified,
    contentDescription: String? = null,
    interactive: Boolean = false,
    onClick: () -> Unit,
) {
    val resolvedTint = if (tint == Color.Unspecified) liquidGlassContentColor() else tint
    LiquidGlassContainer(
        backdrop = backdrop,
        modifier = modifier,
        shape = shape,
        interactive = interactive,
    ) {
        Material3IconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                tint = resolvedTint,
            )
        }
    }
}

/**
 * ── Glass-pipeline pre-warm (2026-09-04) ─────────────────────────────────────
 *
 * User report: "When I open the songs overflow popup for the first few
 * seconds after opening the app, the scrolling in the popup is a bit laggy."
 *
 * The first time a glass popup opens in a process, three things are COLD:
 *  1. the AGSL vibrancy shader + the blur RenderEffect (compiled lazily on
 *     first use — on the popup's own first frames);
 *  2. the [ThrottledLayerBackdrop] recorder (its first full-screen
 *     [GraphicsLayer] record allocates the layer and runs the whole record
 *     path for the first time);
 *  3. the menu row composables themselves (JIT).
 *
 * This composable runs all three ONCE, ~2 s after launch, while nothing is
 * on screen: a 1 dp, ~2%-alpha strip (invisible, but still DRAWN — alpha 0
 * would skip drawing and warm nothing) that samples [backdrop] with the
 * exact vibrancy + 32 dp blur recipe the real popups use, with an optional
 * [content] slot for composing a couple of real menu rows. The caller also
 * keeps the throttled recorder attached for the warm-up window so the first
 * full-screen record happens here, not inside the popup's first scroll.
 *
 * Costs one Record + a couple of frames of 1-dp blur, once per process.
 */
@Composable
fun GlassPipelinePrewarm(
    backdrop: Backdrop?,
    active: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    if (!active || backdrop == null) return
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(1.dp)
                // NOT alpha 0: a zero-alpha layer is skipped entirely and
                // warms nothing. 2% over one strip of pixels is invisible.
                .graphicsLayer { alpha = 0.02f }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { androidx.compose.ui.graphics.RectangleShape },
                    effects = {
                        vibrancy()
                        blur(32.dp.toPx())
                    },
                ),
    ) {
        content()
    }
}
