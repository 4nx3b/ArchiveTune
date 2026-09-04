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

// ============================================================================
// Muzo-style home atmosphere + welcome header (2026-09-04 redesign).
//
// The "Trending Playlist" carousel and the "Popular Albums" shelf that used to
// live here were removed per user requests (2026-09-04: "Remove the Trending
// playlist section from home page" / "Remove Popular albums section
// entirely") — their composables and all supporting constants were dead code
// and have been deleted.
// ============================================================================

/** Page gutter the welcome header aligns to (reference: 20px margins). */
private val MuzoGutter = 20.dp

// ----------------------------------------------------------------------------
// Atmosphere — the deep, softly-lit background the reference floats on.
// ----------------------------------------------------------------------------

/**
 * Full-screen atmospheric backdrop: a near-black (light: surface) base with
 * violet, teal and blue radial glows bleeding through, deepest at the edges
 * and softly lit where content sits. Drawn once per size change into a
 * `drawWithCache` (no blur passes, no per-frame work), so the whole effect
 * costs a single pre-built shader list per layout.
 */
@Composable
fun HomeAtmosphereBackground(
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val base = if (dark) Color(0xFF0D0E12) else MaterialTheme.colorScheme.surface
    // Glow strength: vivid enough to read on the deep dark base, soft enough
    // to stay premium on the light surface.
    val glow = if (dark) 0.17f else 0.12f
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
                                colors = listOf(Color.White.copy(alpha = 0.045f), Color.Transparent),
                                startY = 0f,
                                endY = h * 0.22f,
                            )
                        } else {
                            Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = 0.5f), Color.Transparent),
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
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = if (dark) 0.30f else 0.05f)),
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

// ----------------------------------------------------------------------------
// Welcome header — the reference's greeting + hero headline.
// ----------------------------------------------------------------------------

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
        // The reference headline: two lines, large, clean, tight leading.
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
