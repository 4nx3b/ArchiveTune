/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.rukamori.archivetune.R

private val MuzoGutter = 20.dp

@Composable
fun HomeAtmosphereBackground(
    modifier: Modifier = Modifier,
    subtle: Boolean = false,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    if (dark && !subtle) {
        // Dark mode's home screen no longer paints a flat black slab over
        // the root layer — the app-wide subtle dark gradient (drawn by
        // MainActivity while blur effects are on) shows through instead.
        return
    }
    val base = MaterialTheme.colorScheme.surface

    // The app-wide subtle variant rides under every page in DARK mode (the
    // light mode is untouched — its home screen wears the full-intensity
    // wash and every other page stays flat). On a near-black surface the
    // previous values (0.045 glow / 0.03 wash) were imperceptible, so the
    // dark subtle band is pushed up until the three-colour radial wash
    // actually reads as a gentle gradient.
    val glow = if (subtle) 0.10f else 0.12f
    val topWashAlpha = when {
        subtle && dark -> 0.05f
        subtle -> 0.16f
        else -> 0.5f
    }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(base)
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val violet = Color(0xFF7B4DFF)
                    val teal = Color(0xFF00B8A9)
                    val blue = Color(0xFF2E6BFF)
                    val topWash =
                        if (dark) {
                            Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = topWashAlpha), Color.Transparent),
                                startY = 0f,
                                endY = h * 0.22f,
                            )
                        } else {
                            Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = topWashAlpha), Color.Transparent),
                                startY = 0f,
                                endY = h * 0.16f,
                            )
                        }
                    val violetBrush =
                        Brush.radialGradient(
                            colors = listOf(violet.copy(alpha = glow), Color.Transparent),
                            center = Offset(w * 0.12f, h * 0.10f),
                            radius = w * 0.62f,
                        )
                    val tealBrush =
                        Brush.radialGradient(
                            colors = listOf(teal.copy(alpha = glow * 0.8f), Color.Transparent),
                            center = Offset(w * 0.98f, h * 0.30f),
                            radius = w * 0.55f,
                        )
                    val blueBrush =
                        Brush.radialGradient(
                            colors = listOf(blue.copy(alpha = glow * 0.85f), Color.Transparent),
                            center = Offset(w * 0.18f, h * 0.92f),
                            radius = w * 0.70f,
                        )
                    val bottomShade =
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = if (dark) (if (subtle) 0.15f else 0.30f) else 0.05f)),
                            startY = h * 0.55f,
                            endY = h,
                        )
                    onDrawBehind {
                        drawRect(violetBrush)
                        drawRect(tealBrush)
                        drawRect(blueBrush)
                        drawRect(bottomShade)
                        drawRect(topWash)
                    }
                },
    )
}

@Composable
fun HomeWelcomeHeader(
    accountName: String,
    modifier: Modifier = Modifier,
) {
    val small = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    val headline = MaterialTheme.colorScheme.onSurface
    val displayName = accountName.trim()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = MuzoGutter)
                .padding(top = 10.dp, bottom = 16.dp),
    ) {
        Text(
            text =
                if (displayName.isNotBlank()) {
                    stringResource(R.string.home_welcome_line, displayName)
                } else {
                    stringResource(R.string.home_welcome_line_anonymous)
                },
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = small,
        )
        Spacer(Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.home_headline_line1),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            lineHeight = 37.sp,
            color = headline,
        )
        Text(
            text = stringResource(R.string.home_headline_line2),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            lineHeight = 37.sp,
            color = headline,
        )
    }
}
