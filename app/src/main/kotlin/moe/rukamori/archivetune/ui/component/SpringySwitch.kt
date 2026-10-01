/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.R

@Composable
fun SpringySwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val animationsDisabled = LocalAnimationsDisabled.current
    val density = LocalDensity.current

    val trackColor by animateColorAsState(
        targetValue =
            when {
                !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                checked -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        animationSpec = if (animationsDisabled) tween(0) else tween(180),
        label = "springySwitchTrack",
    )
    val thumbColor =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            checked -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurface
        }

    val trackWidth = 52.dp
    val trackHeight = 32.dp
    val thumbDiameter = 24.dp
    val travel = trackWidth - thumbDiameter - 4.dp

    val travelFraction = remember { Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked) {
        travelFraction.animateTo(
            targetValue = if (checked) 1f else 0f,
            animationSpec =
                if (animationsDisabled) {
                    tween(0)
                } else {
                    spring(
                        dampingRatio = 0.68f,
                        stiffness = Spring.StiffnessMedium,
                    )
                },
        )
    }

    val thumbIconAlpha = (travelFraction.value * 2f).coerceIn(0f, 1f)
    val strokeColor =
        if (trackColor.luminance() > 0.5f) {
            Color.Black.copy(alpha = 0.06f)
        } else {
            Color.White.copy(alpha = 0.08f)
        }

    Box(
        modifier =
            modifier
                .size(trackWidth, trackHeight)
                .clip(CircleShape)
                .toggleable(
                    value = checked,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(modifier = Modifier.size(trackWidth, trackHeight)) {
            val trackCorner = CornerRadius(size.height / 2f)
            drawRoundRect(
                color = trackColor,
                cornerRadius = trackCorner,
                size = size,
                topLeft = Offset.Zero,
            )
            drawRoundRect(
                color = strokeColor,
                cornerRadius = trackCorner,
                size = size,
                topLeft = Offset.Zero,
                style = Stroke(width = 1.dp.toPx()),
            )

            val thumbSizePx = thumbDiameter.toPx()
            val travelPx = travel.toPx()
            val center =
                Offset(
                    2.dp.toPx() + travelPx * travelFraction.value + thumbSizePx / 2f,
                    size.height / 2f,
                )

            val velocityStretch =
                if (animationsDisabled) {
                    0f
                } else {
                    (travelFraction.velocity * 0.02f).coerceIn(-1f, 1f)
                }
            val stretch = 1f + velocityStretch * 0.28f
            val squash = 1f - velocityStretch * 0.22f
            val wide = thumbSizePx * stretch
            val high = thumbSizePx * squash

            drawOval(
                color = thumbColor,
                topLeft = Offset(center.x - wide / 2f, center.y - high / 2f),
                size = Size(wide, high),
            )
        }

        Box(
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(
                        start = with(density) {
                            (2.dp + travel * travelFraction.value + (thumbDiameter - 16.dp) / 2f)
                        },
                    )
                    .size(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = checked && thumbIconAlpha > 0.4f,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(80)),
            ) {
                Icon(
                    painter = painterResource(R.drawable.check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
