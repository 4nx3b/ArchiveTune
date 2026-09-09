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

typealias PlatformBackdrop = LayerBackdrop

@Composable
fun rememberLayerBackdropSettled(@Suppress("UNUSED_PARAMETER") delayMillis: Long = 0L): Boolean = true

@Composable
fun rememberBackdrop(color: Color): PlatformBackdrop =
    rememberLayerBackdrop {
        drawRect(color)
        drawContent()
    }

fun Modifier.layerBackdrop(backdrop: PlatformBackdrop): Modifier = this.layerBackdrop(backdrop)

val LocalLiquidGlassBackdrop = compositionLocalOf<LayerBackdrop?> { null }

val LocalMenuGlassBackdrop = compositionLocalOf<Backdrop?> { null }

/**
 * How often the real-time menu-glass recorder may re-record the app subtree
 * while a glass popup is showing: 100 ms (~10 fps). The frost samples this
 * layer through a 32 dp blur, which low-passes the image so heavily that a
 * 10 fps refresh of moving content behind the popup is visually
 * indistinguishable from 30 fps — while cutting the full-screen
 * GraphicsLayer record (the dominant per-frame cost of the live popup, and
 * it ran on every mini-player progress tick) by ~3x. Popup open/close, its
 * own scroll and all its interactions are unaffected: only the *background
 * sampling* rate changes.
 */
internal const val ThrottledLayerBackdropDefaultIntervalMillis = 100L

@Stable
class ThrottledLayerBackdrop internal constructor(
    val graphicsLayer: GraphicsLayer,
    internal val minIntervalMillis: Long,
) : Backdrop {

    override val isCoordinatesDependent: Boolean get() = true

    internal var layerCoordinates: LayoutCoordinates? by mutableStateOf(null)

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val coordinates = coordinates ?: return
        val layerCoordinates = layerCoordinates ?: return
        withTransform({

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

@Composable
fun rememberThrottledLayerBackdrop(
    graphicsLayer: GraphicsLayer = rememberGraphicsLayer(),
    minIntervalMillis: Long = ThrottledLayerBackdropDefaultIntervalMillis,
): ThrottledLayerBackdrop = remember(graphicsLayer) {
    ThrottledLayerBackdrop(graphicsLayer, minIntervalMillis)
}

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

val LiquidGlassPillBlurRadius = 18.dp

private val LiquidGlassLightContentColor = Color(0xFF1C1B1F)

@Composable
fun liquidGlassContentColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color.White else LiquidGlassLightContentColor

@Composable
fun Modifier.liquidGlass(
    backdrop: PlatformBackdrop,
    shape: Shape = CircleShape,
    interactive: Boolean = true,
    baseColor: Color = Color.Unspecified,
    blurRadius: Dp = 8.dp,
): Modifier {

    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

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

private const val GlassPillTitleMaxWidthFraction = 0.42f

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

@Composable
fun LiquidGlassIconButton(
    backdrop: PlatformBackdrop,
    painter: Painter,
    modifier: Modifier = Modifier.size(48.dp),
    shape: Shape = CircleShape,

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

@Composable
fun LiquidGlassIconButton(
    backdrop: PlatformBackdrop,
    imageVector: ImageVector,
    modifier: Modifier = Modifier.size(48.dp),
    shape: Shape = CircleShape,

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
