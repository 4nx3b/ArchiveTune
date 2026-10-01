/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.chrisbanes.haze.hazeSource
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.LIQUID_GLASS_ADAPTIVE_LUMINANCE_DEFAULT
import moe.rukamori.archivetune.constants.LIQUID_GLASS_BACKDROP_VIBRANCY_DEFAULT
import moe.rukamori.archivetune.constants.LIQUID_GLASS_BLUR_RADIUS_DEFAULT
import moe.rukamori.archivetune.constants.LIQUID_GLASS_CHROMATIC_ABERRATION_DEFAULT
import moe.rukamori.archivetune.constants.LIQUID_GLASS_DEPTH_3D_DEFAULT
import moe.rukamori.archivetune.constants.LIQUID_GLASS_REFRACTION_AMOUNT_DEFAULT
import moe.rukamori.archivetune.constants.LIQUID_GLASS_REFRACTION_HEIGHT_DEFAULT
import moe.rukamori.archivetune.constants.LIQUID_GLASS_SHADOW_DEPTH_DEFAULT
import moe.rukamori.archivetune.constants.LIQUID_GLASS_TINT_OPACITY_DEFAULT
import moe.rukamori.archivetune.constants.LiquidGlassAdaptiveLuminanceKey
import moe.rukamori.archivetune.constants.LiquidGlassBackdropVibrancyKey
import moe.rukamori.archivetune.constants.LiquidGlassBlurRadiusKey
import moe.rukamori.archivetune.constants.LiquidGlassChromaticAberrationKey
import moe.rukamori.archivetune.constants.LiquidGlassDepth3DKey
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.constants.LiquidGlassIntensity
import moe.rukamori.archivetune.constants.LiquidGlassIntensityKey
import moe.rukamori.archivetune.constants.LiquidGlassRefractionAmountKey
import moe.rukamori.archivetune.constants.LiquidGlassRefractionHeightKey
import moe.rukamori.archivetune.constants.LiquidGlassShadowDepthKey
import moe.rukamori.archivetune.constants.LiquidGlassTintOpacityKey
import moe.rukamori.archivetune.ui.component.EnumListPreference
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SettingsPageTopBar
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.ui.screens.ScreenHeaderHaze
import moe.rukamori.archivetune.ui.screens.rememberScreenHeaderHaze
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference

@Composable
fun LiquidGlassSettings(
    navController: NavController,
    scrollTo: String? = null,
) {
    val (liquidGlassEnabled, onLiquidGlassEnabledChange) =
        rememberPreference(LiquidGlassEnabledKey, defaultValue = true)

    val (intensity, onIntensityChange) =
        rememberEnumPreference(
            LiquidGlassIntensityKey,
            defaultValue = LiquidGlassIntensity.STANDARD,
        )

    val (refractionHeight, onRefractionHeightChange) =
        rememberPreference(
            LiquidGlassRefractionHeightKey,
            defaultValue = LIQUID_GLASS_REFRACTION_HEIGHT_DEFAULT,
        )
    val (refractionAmount, onRefractionAmountChange) =
        rememberPreference(
            LiquidGlassRefractionAmountKey,
            defaultValue = LIQUID_GLASS_REFRACTION_AMOUNT_DEFAULT,
        )
    val (blurRadius, onBlurRadiusChange) =
        rememberPreference(
            LiquidGlassBlurRadiusKey,
            defaultValue = LIQUID_GLASS_BLUR_RADIUS_DEFAULT,
        )
    val (tintOpacity, onTintOpacityChange) =
        rememberPreference(
            LiquidGlassTintOpacityKey,
            defaultValue = LIQUID_GLASS_TINT_OPACITY_DEFAULT,
        )
    val (shadowDepth, onShadowDepthChange) =
        rememberPreference(
            LiquidGlassShadowDepthKey,
            defaultValue = LIQUID_GLASS_SHADOW_DEPTH_DEFAULT,
        )
    val (depth3D, onDepth3DChange) =
        rememberPreference(LiquidGlassDepth3DKey, defaultValue = LIQUID_GLASS_DEPTH_3D_DEFAULT)
    val (chromaticAberration, onChromaticAberrationChange) =
        rememberPreference(
            LiquidGlassChromaticAberrationKey,
            defaultValue = LIQUID_GLASS_CHROMATIC_ABERRATION_DEFAULT,
        )
    val (backdropVibrancy, onBackdropVibrancyChange) =
        rememberPreference(
            LiquidGlassBackdropVibrancyKey,
            defaultValue = LIQUID_GLASS_BACKDROP_VIBRANCY_DEFAULT,
        )
    val (adaptiveLuminance, onAdaptiveLuminanceChange) =
        rememberPreference(
            LiquidGlassAdaptiveLuminanceKey,
            defaultValue = LIQUID_GLASS_ADAPTIVE_LUMINANCE_DEFAULT,
        )

    val resetGlass: () -> Unit = {
        onIntensityChange(LiquidGlassIntensity.STANDARD)
        onRefractionHeightChange(LIQUID_GLASS_REFRACTION_HEIGHT_DEFAULT)
        onRefractionAmountChange(LIQUID_GLASS_REFRACTION_AMOUNT_DEFAULT)
        onBlurRadiusChange(LIQUID_GLASS_BLUR_RADIUS_DEFAULT)
        onTintOpacityChange(LIQUID_GLASS_TINT_OPACITY_DEFAULT)
        onShadowDepthChange(LIQUID_GLASS_SHADOW_DEPTH_DEFAULT)
        onDepth3DChange(LIQUID_GLASS_DEPTH_3D_DEFAULT)
        onChromaticAberrationChange(LIQUID_GLASS_CHROMATIC_ABERRATION_DEFAULT)
        onBackdropVibrancyChange(LIQUID_GLASS_BACKDROP_VIBRANCY_DEFAULT)
        onAdaptiveLuminanceChange(LIQUID_GLASS_ADAPTIVE_LUMINANCE_DEFAULT)
    }

    val headerHaze = rememberScreenHeaderHaze()
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsPageTopBar(
                titleText = stringResource(R.string.liquid_glass_settings_title),
                onBack = navController::navigateUp,
                onBackLongClick = navController::backToMain,
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            val playerAwareBottomPadding =
                LocalPlayerAwareWindowInsets.current
                    .only(WindowInsetsSides.Bottom)
                    .asPaddingValues()
                    .calculateBottomPadding()
            val topPadding = innerPadding.calculateTopPadding()
            val scrollState = rememberScrollState()
            val positions = rememberPreferencePositions()

            LaunchedEffect(scrollTo) { positions.scrollToKey(scrollTo, scrollState) }

            Column(
                Modifier
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal),
                    )
                    .then(positions.containerModifier())
                    .verticalScroll(scrollState)
                    .hazeSource(headerHaze)
                    .padding(top = topPadding)
                    .padding(bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding),
            ) {
                PreferenceGroup(title = stringResource(R.string.general)) {
                    item {
                        Column(modifier = positions.modifierFor("liquid_glass_effects")) {
                            SwitchPreference(
                                title = { Text(stringResource(R.string.liquid_glass_effects)) },
                                description = stringResource(R.string.liquid_glass_effects_desc),
                                icon = { Icon(painterResource(R.drawable.blur_on), null) },
                                checked = liquidGlassEnabled,
                                onCheckedChange = onLiquidGlassEnabledChange,
                            )
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && liquidGlassEnabled) {
                                Text(
                                    text = stringResource(R.string.liquid_glass_effects_unsupported),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 56.dp, top = 4.dp, end = 16.dp),
                                )
                            }
                        }
                    }

                    item {
                        Column(modifier = positions.modifierFor("glass_intensity")) {
                            EnumListPreference(
                                title = { Text(stringResource(R.string.liquid_glass_intensity)) },
                                description = stringResource(R.string.liquid_glass_intensity_desc),
                                icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                                selectedValue = intensity,
                                onValueSelected = onIntensityChange,
                                valueText = {
                                    when (it) {
                                        LiquidGlassIntensity.SUBTLE -> stringResource(R.string.liquid_glass_intensity_subtle)
                                        LiquidGlassIntensity.STANDARD -> stringResource(R.string.liquid_glass_intensity_standard)
                                        LiquidGlassIntensity.VIVID -> stringResource(R.string.liquid_glass_intensity_vivid)
                                    }
                                },
                                isEnabled = liquidGlassEnabled,
                            )
                        }
                    }
                }

                PreferenceGroup(title = stringResource(R.string.liquid_glass_optics_group)) {
                    item {
                        Column(modifier = positions.modifierFor("glass_refraction_height")) {
                            PercentageSliderEntry(
                                title = stringResource(R.string.liquid_glass_refraction_height),
                                description = stringResource(R.string.liquid_glass_refraction_height_desc),
                                value = refractionHeight,
                                onValueChange = onRefractionHeightChange,
                                enabled = liquidGlassEnabled,
                            )
                        }
                    }

                    item {
                        Column(modifier = positions.modifierFor("glass_refraction_amount")) {
                            PercentageSliderEntry(
                                title = stringResource(R.string.liquid_glass_refraction_amount),
                                description = stringResource(R.string.liquid_glass_refraction_amount_desc),
                                value = refractionAmount,
                                onValueChange = onRefractionAmountChange,
                                enabled = liquidGlassEnabled,
                            )
                        }
                    }

                    item {
                        Column(modifier = positions.modifierFor("glass_blur_radius")) {
                            PercentageSliderEntry(
                                title = stringResource(R.string.liquid_glass_blur_radius),
                                description = stringResource(R.string.liquid_glass_blur_radius_desc),
                                value = blurRadius,
                                onValueChange = onBlurRadiusChange,
                                enabled = liquidGlassEnabled,
                            )
                        }
                    }

                    item {
                        Column(modifier = positions.modifierFor("glass_tint_opacity")) {
                            PercentageSliderEntry(
                                title = stringResource(R.string.liquid_glass_tint_opacity),
                                description = stringResource(R.string.liquid_glass_tint_opacity_desc),
                                value = tintOpacity,
                                onValueChange = onTintOpacityChange,
                                enabled = liquidGlassEnabled,
                                range = 0f..2f,
                            )
                        }
                    }

                    item {
                        Column(modifier = positions.modifierFor("glass_shadow_depth")) {
                            PercentageSliderEntry(
                                title = stringResource(R.string.liquid_glass_shadow_depth),
                                description = stringResource(R.string.liquid_glass_shadow_depth_desc),
                                value = shadowDepth,
                                onValueChange = onShadowDepthChange,
                                enabled = liquidGlassEnabled,
                            )
                        }
                    }
                }

                PreferenceGroup(title = stringResource(R.string.liquid_glass_quality_group)) {
                    item {
                        Column(modifier = positions.modifierFor("glass_depth_3d")) {
                            SwitchPreference(
                                title = { Text(stringResource(R.string.liquid_glass_depth_3d)) },
                                description = stringResource(R.string.liquid_glass_depth_3d_desc),
                                icon = { Icon(painterResource(R.drawable.sliders), null) },
                                checked = depth3D,
                                onCheckedChange = onDepth3DChange,
                                isEnabled = liquidGlassEnabled,
                            )
                        }
                    }

                    item {
                        Column(modifier = positions.modifierFor("glass_chromatic_aberration")) {
                            SwitchPreference(
                                title = { Text(stringResource(R.string.liquid_glass_chromatic_aberration)) },
                                description = stringResource(R.string.liquid_glass_chromatic_aberration_desc),
                                icon = { Icon(painterResource(R.drawable.solar_moon_stars_linear), null) },
                                checked = chromaticAberration,
                                onCheckedChange = onChromaticAberrationChange,
                                isEnabled = liquidGlassEnabled,
                            )
                        }
                    }

                    item {
                        Column(modifier = positions.modifierFor("glass_backdrop_vibrancy")) {
                            SwitchPreference(
                                title = { Text(stringResource(R.string.liquid_glass_backdrop_vibrancy)) },
                                description = stringResource(R.string.liquid_glass_backdrop_vibrancy_desc),
                                icon = { Icon(painterResource(R.drawable.solar_moon_stars_linear), null) },
                                checked = backdropVibrancy,
                                onCheckedChange = onBackdropVibrancyChange,
                                isEnabled = liquidGlassEnabled,
                            )
                        }
                    }

                    item {
                        Column(modifier = positions.modifierFor("glass_adaptive_luminance")) {
                            SwitchPreference(
                                title = { Text(stringResource(R.string.liquid_glass_adaptive_luminance)) },
                                description = stringResource(R.string.liquid_glass_adaptive_luminance_desc),
                                icon = { Icon(painterResource(R.drawable.solar_brightness_auto_linear), null) },
                                checked = adaptiveLuminance,
                                onCheckedChange = onAdaptiveLuminanceChange,
                                isEnabled = liquidGlassEnabled,
                            )
                        }
                    }
                }

                PreferenceGroup(title = stringResource(R.string.liquid_glass_reset_group)) {
                    item {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.liquid_glass_reset)) },
                            description = stringResource(R.string.liquid_glass_reset_desc),
                            icon = { Icon(painterResource(R.drawable.restore), null) },
                            onClick = resetGlass,
                        )
                    }
                }
            }

            ScreenHeaderHaze(
                hazeState = headerHaze,
                systemBarsTopPadding = systemBarsTopPadding,
                scrolled = scrollState.value > 0,
            )
        }
    }
}

@Composable
private fun PercentageSliderEntry(
    title: String,
    description: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    enabled: Boolean = true,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    val percentText = stringResource(R.string.liquid_glass_percent_value, (value * 100f).toInt())
    PreferenceEntry(
        title = { Text(title) },
        description = description,
        trailingContent = {
            Text(
                text = percentText,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        },
        isEnabled = enabled,
        content = {
            Spacer(modifier = Modifier.height(10.dp))
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = range,
                steps = if (range == 0f..2f) 39 else 19,
                enabled = enabled,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 56.dp),
            )
            Text(
                text = percentText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 56.dp, top = 4.dp),
            )
        },
    )
}
