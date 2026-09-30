/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.kyant.backdrop.Backdrop

/**
 * "Glass glow" for the navigation bar, ported from NuvioMobile's
 * GlassBarSurface fallback: a soft vertical-gradient rim light hugging the
 * bar's capsule edge plus a gentle top sheen and a faint reflected floor
 * light. Drawn as a pure overlay in the draw phase so it composes with any
 * existing background (liquid glass, frosted, plain surface) without
 * touching the background pipeline itself.
 */
val NavigationBarGlassGlowKey = booleanPreferencesKey("navigationBarGlassGlow")

/** Size of the standalone compact control circles ([Home] / [Search]). */
val CompactControlSize = 64.dp

/** Gap between a compact circle and the mini player pill. */
val CompactControlGap = 12.dp

private val GlowEdgeStrokeWidth = 0.75.dp

/**
 * Draws the Nuvio-style glass glow rim over the content this modifier wraps.
 * [strength] animates 0..1 so toggling the setting crossfades the glow.
 */
fun Modifier.glassGlowOverlay(
    strength: Float,
    shape: Shape,
): Modifier =
    if (strength <= 0.01f) {
        this
    } else {
        drawWithContent {
            drawContent()
            if (strength <= 0.01f) return@drawWithContent
            val outline = shape.createOutline(size, layoutDirection, this)
            val corner =
                when (val r = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius) {
                    null -> CornerRadius(size.height / 2f)
                    else -> CornerRadius(r.x, r.y)
                }
            val stroke = GlowEdgeStrokeWidth.toPx()
            // Edge rim: bright at the top, dissolving to nothing at the bottom.
            drawRoundRect(
                brush =
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.27f),
                            Color.White.copy(alpha = 0.02f),
                        ),
                    ),
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = corner,
                style = Stroke(stroke),
                alpha = strength,
            )
            // Top sheen: a whisper of light across the upper third of the glass.
            drawRoundRect(
                brush =
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.10f),
                        0.45f to Color.Transparent,
                        1f to Color.White.copy(alpha = 0.05f),
                    ),
                cornerRadius = corner,
                alpha = strength,
            )
            // Reflected floor light along the very bottom edge.
            drawRoundRect(
                brush =
                    Brush.verticalGradient(
                        0.72f to Color.Transparent,
                        1f to Color.White.copy(alpha = 0.14f),
                    ),
                cornerRadius = corner,
                alpha = strength * 0.7f,
            )
        }
    }

/**
 * A standalone circular glass control used by the compact bottom row:
 * liquid glass when a [Backdrop] is available (and the platform supports
 * it), otherwise a translucent surface that matches the navigation bar.
 */
@Composable
fun CompactControlCircle(
    iconRes: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    backdrop: Backdrop?,
    glowStrength: Float,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val useGlass = backdrop != null
    if (useGlass) {
        Box(
            modifier =
                modifier
                    .size(CompactControlSize)
                    .liquidGlass(
                        backdrop = backdrop,
                        shape = CircleShape,
                        interactive = false,
                    )
                    .glassGlowOverlay(glowStrength, CircleShape)
                    .clip(CircleShape)
                    .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = if (tint == Color.Unspecified) liquidGlassContentColor() else tint,
                modifier = Modifier.size(26.dp),
            )
        }
    } else {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
            tonalElevation = 3.dp,
            shadowElevation = 4.dp,
            modifier =
                modifier
                    .size(CompactControlSize)
                    .glassGlowOverlay(glowStrength, CircleShape)
                    .clip(CircleShape)
                    .clickable(onClick = onClick),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = contentDescription,
                    tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}
