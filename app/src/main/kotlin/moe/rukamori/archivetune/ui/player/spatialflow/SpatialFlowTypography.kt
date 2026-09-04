/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * The SpatialFlow player style's type.
 *
 * SpatialFlow (github.com/MythicalSHUB/SpatialFlow, Apache-2.0, see NOTICE in
 * the repo root) renders its whole UI in Google Sans Flex with the ROND
 * (roundness) variable-font axis pinned to 100% — the fully-rounded cut — and
 * uses the non-rounded cut (ROND 0) for lyrics so dense text stays crisp.
 * Both cuts ship here as the same variable TTF
 * (res/font/spatialflow_google_sans_flex.ttf) with two FontFamily entries that
 * differ only in their variation settings, exactly as SpatialFlow's
 * ui/theme/Type.kt defines them. Only the spatialflow package uses these; the
 * rest of the app keeps its own typography.
 */

package moe.rukamori.archivetune.ui.player.spatialflow

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import moe.rukamori.archivetune.R

/** Google Sans Flex, ROND axis at 100% — the rounded cut the player UI uses. */
@OptIn(ExperimentalTextApi::class)
val SpatialFlowGoogleSansFlex: FontFamily =
    FontFamily(
        Font(
            resId = R.font.spatialflow_google_sans_flex,
            variationSettings =
                FontVariation.Settings(
                    FontVariation.Setting("ROND", 100f),
                ),
        ),
    )

/** Google Sans Flex with ROND at 0% — the non-rounded cut, for lyrics text. */
@OptIn(ExperimentalTextApi::class)
val SpatialFlowGoogleSansFlexNonRounded: FontFamily =
    FontFamily(
        Font(
            resId = R.font.spatialflow_google_sans_flex,
            variationSettings =
                FontVariation.Settings(
                    FontVariation.Setting("ROND", 0f),
                ),
        ),
    )

/**
 * SpatialFlow's own Material Typography (its ui/theme/Type.kt), built on the
 * rounded Google Sans Flex cut. Sizes, weights, line heights and letter
 * spacing are copied one-for-one so the player's text metrics match the
 * original app exactly.
 */
val SpatialFlowTypography: Typography =
    Typography(
        displayLarge =
            TextStyle(
                fontFamily = SpatialFlowGoogleSansFlex,
                fontWeight = FontWeight.Normal,
                fontSize = 57.sp,
                lineHeight = 64.sp,
                letterSpacing = (-0.25).sp,
            ),
        displayMedium =
            TextStyle(
                fontFamily = SpatialFlowGoogleSansFlex,
                fontWeight = FontWeight.Normal,
                fontSize = 45.sp,
                lineHeight = 52.sp,
            ),
        displaySmall =
            TextStyle(
                fontFamily = SpatialFlowGoogleSansFlex,
                fontWeight = FontWeight.Normal,
                fontSize = 36.sp,
                lineHeight = 44.sp,
            ),
        headlineLarge =
            TextStyle(
                fontFamily = SpatialFlowGoogleSansFlex,
                fontWeight = FontWeight.Normal,
                fontSize = 32.sp,
                lineHeight = 40.sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = SpatialFlowGoogleSansFlex,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                lineHeight = 36.sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = SpatialFlowGoogleSansFlex,
                fontWeight = FontWeight.Normal,
                fontSize = 24.sp,
                lineHeight = 32.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = SpatialFlowGoogleSansFlex,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = SpatialFlowGoogleSansFlex,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.15.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = SpatialFlowGoogleSansFlex,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = SpatialFlowGoogleSansFlex,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.5.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = SpatialFlowGoogleSansFlex,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.25.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = SpatialFlowGoogleSansFlex,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.4.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = SpatialFlowGoogleSansFlex,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = SpatialFlowGoogleSansFlex,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = SpatialFlowGoogleSansFlex,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
    )
