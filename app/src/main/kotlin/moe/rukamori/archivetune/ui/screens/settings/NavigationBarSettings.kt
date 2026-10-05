/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package moe.rukamori.archivetune.ui.screens.settings

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.HideNavigationBarLabelsKey
import moe.rukamori.archivetune.constants.NAVIGATION_BAR_CORNER_RADIUS_DEFAULT
import moe.rukamori.archivetune.constants.NAVIGATION_BAR_HEIGHT_DEFAULT
import moe.rukamori.archivetune.constants.NAVIGATION_BAR_LABEL_SPACING_DEFAULT
import moe.rukamori.archivetune.constants.NAVIGATION_BAR_OPACITY_DEFAULT
import moe.rukamori.archivetune.constants.NAVIGATION_BAR_TRANSPARENCY_DEFAULT
import moe.rukamori.archivetune.constants.NAVIGATION_BAR_WIDTH_DEFAULT
import moe.rukamori.archivetune.constants.NavigationBarCompactBehavior
import moe.rukamori.archivetune.constants.NavigationBarCompactBehaviorKey
import moe.rukamori.archivetune.constants.NavigationBarCornerRadiusKey
import moe.rukamori.archivetune.constants.NavigationBarFrostedBlurKey
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.constants.LiquidGlassNavBarEnabledKey
import moe.rukamori.archivetune.ui.component.NavigationBarGlassGlowKey
import moe.rukamori.archivetune.ui.component.NavigationBarGlassGlowIntensityKey
import moe.rukamori.archivetune.ui.component.NAVIGATION_BAR_GLASS_GLOW_INTENSITY_DEFAULT
import moe.rukamori.archivetune.constants.NavigationBarTintFrostedBlurKey
import moe.rukamori.archivetune.constants.NavigationBarHeight
import moe.rukamori.archivetune.constants.NavigationBarHeightKey
import moe.rukamori.archivetune.constants.NavigationBarLabelSpacingKey
import moe.rukamori.archivetune.constants.NavigationBarOpacityKey
import moe.rukamori.archivetune.constants.NavigationBarTransparencyKey
import moe.rukamori.archivetune.constants.NavigationBarWidthKey
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.FrostedHeaderPill
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.EnumListPreference
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.screens.Screens
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.utils.rememberEnumPreference
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.asPaddingValues
import moe.rukamori.archivetune.ui.screens.ScreenHeaderHaze
import moe.rukamori.archivetune.ui.screens.rememberScreenHeaderHaze
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import dev.chrisbanes.haze.hazeSource
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import moe.rukamori.archivetune.ui.component.SettingsPageTopBar

@Composable
fun NavigationBarSettings(navController: NavController, scrollTo: String? = null) {
    val (navigationBarFrostedBlur, onNavigationBarFrostedBlurChange) =
        rememberPreference(NavigationBarFrostedBlurKey, defaultValue = false)
    val (navigationBarTintFrostedBlur, onNavigationBarTintFrostedBlurChange) =
        rememberPreference(NavigationBarTintFrostedBlurKey, defaultValue = false)

    val (liquidGlassEnabled) =
        rememberPreference(LiquidGlassEnabledKey, defaultValue = true)
    val (liquidGlassNavBarEnabled, onLiquidGlassNavBarEnabledChange) =
        rememberPreference(LiquidGlassNavBarEnabledKey, defaultValue = false)
    val (navigationBarGlassGlow, onNavigationBarGlassGlowChange) =
        rememberPreference(NavigationBarGlassGlowKey, defaultValue = true)
    val (navigationBarGlassGlowIntensity, onNavigationBarGlassGlowIntensityChange) =
        rememberPreference(
            NavigationBarGlassGlowIntensityKey,
            defaultValue = NAVIGATION_BAR_GLASS_GLOW_INTENSITY_DEFAULT,
        )

    val onFrostedBlurChange: (Boolean) -> Unit = { checked ->
        onNavigationBarFrostedBlurChange(checked)
        if (checked && navigationBarTintFrostedBlur) {
            onNavigationBarTintFrostedBlurChange(false)
        }
    }
    val onTintFrostedBlurChange: (Boolean) -> Unit = { checked ->
        onNavigationBarTintFrostedBlurChange(checked)
        if (checked && navigationBarFrostedBlur) {
            onNavigationBarFrostedBlurChange(false)
        }
    }
    val (hideNavigationBarLabels, onHideNavigationBarLabelsChange) =
        rememberPreference(HideNavigationBarLabelsKey, defaultValue = false)

    val (compactBehavior, onCompactBehaviorChange) =
        rememberEnumPreference(
            NavigationBarCompactBehaviorKey,
            defaultValue = NavigationBarCompactBehavior.ADAPTIVE,
        )

    val (navigationBarWidth, onNavigationBarWidthChange) =
        rememberPreference(NavigationBarWidthKey, defaultValue = NAVIGATION_BAR_WIDTH_DEFAULT)
    val (navigationBarHeight, onNavigationBarHeightChange) =
        rememberPreference(NavigationBarHeightKey, defaultValue = NAVIGATION_BAR_HEIGHT_DEFAULT)
    val (navigationBarOpacity, onNavigationBarOpacityChange) =
        rememberPreference(NavigationBarOpacityKey, defaultValue = NAVIGATION_BAR_OPACITY_DEFAULT)
    val (navigationBarTransparency, onNavigationBarTransparencyChange) =
        rememberPreference(
            NavigationBarTransparencyKey,
            defaultValue = NAVIGATION_BAR_TRANSPARENCY_DEFAULT,
        )
    val (navigationBarLabelSpacing, onNavigationBarLabelSpacingChange) =
        rememberPreference(
            NavigationBarLabelSpacingKey,
            defaultValue = NAVIGATION_BAR_LABEL_SPACING_DEFAULT,
        )
    val (navigationBarCornerRadius, onNavigationBarCornerRadiusChange) =
        rememberPreference(
            NavigationBarCornerRadiusKey,
            defaultValue = NAVIGATION_BAR_CORNER_RADIUS_DEFAULT,
        )

    val headerHaze = rememberScreenHeaderHaze()
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
                SettingsPageTopBar(
                    titleText = stringResource(R.string.navigation_bar_settings_title),
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
                    LocalPlayerAwareWindowInsets.current.only(
                        WindowInsetsSides.Horizontal,
                    ),
                )

                .then(positions.containerModifier())
                .verticalScroll(scrollState)
                .hazeSource(headerHaze)
                .padding(top = topPadding)
                .padding(bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding),
        ) {
            PreferenceGroup(title = stringResource(R.string.navigation_bar_compact_behavior)) {
                item {
                    CompactBehaviorPreview(
                        behavior = compactBehavior,
                        modifier = positions.modifierFor("navigation_bar_compact_behavior_preview"),
                    )
                }
                item {
                    EnumListPreference(
                        title = { Text(stringResource(R.string.navigation_bar_compact_behavior)) },
                        description = stringResource(R.string.navigation_bar_compact_behavior_desc),
                        icon = { Icon(painterResource(R.drawable.nav_bar), null) },
                        selectedValue = compactBehavior,
                        onValueSelected = onCompactBehaviorChange,
                        valueText = {
                            when (it) {
                                NavigationBarCompactBehavior.ADAPTIVE ->
                                    stringResource(R.string.navigation_bar_compact_adaptive)
                                NavigationBarCompactBehavior.ALWAYS_EXPANDED ->
                                    stringResource(R.string.navigation_bar_compact_always_expanded)
                                NavigationBarCompactBehavior.ALWAYS_COMPACT ->
                                    stringResource(R.string.navigation_bar_compact_always_compact)
                            }
                        },
                    )
                }
            }

            PreferenceGroup(title = stringResource(R.string.general)) {
                item {
                    Column {
                        SwitchPreference(
                            modifier = positions.modifierFor("navigation_bar_frosted_blur", "frosted_nav_bar"),
                            title = { Text(stringResource(R.string.navigation_bar_frosted_blur)) },
                            description = stringResource(R.string.navigation_bar_frosted_blur_desc),
                            icon = { Icon(painterResource(R.drawable.blur_on), null) },
                            checked = navigationBarFrostedBlur,
                            onCheckedChange = onFrostedBlurChange,
                        )
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && navigationBarFrostedBlur) {
                            Text(
                                text = stringResource(R.string.navigation_bar_frosted_blur_unsupported),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 56.dp, top = 4.dp, end = 16.dp),
                            )
                        }
                    }
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("navigation_bar_tint_frosted_blur"),
                        title = { Text(stringResource(R.string.navigation_bar_tint_frosted_blur)) },
                        description = stringResource(R.string.navigation_bar_tint_frosted_blur_desc),
                        icon = { Icon(painterResource(R.drawable.format_paint), null) },
                        checked = navigationBarTintFrostedBlur,
                        onCheckedChange = onTintFrostedBlurChange,
                    )
                }

                item {
                    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    SwitchPreference(
                        modifier = positions.modifierFor("liquid_glass_nav_bar"),
                        title = { Text(stringResource(R.string.liquid_glass_nav_bar)) },
                        description =
                            when {
                                !supported -> stringResource(R.string.liquid_glass_effects_unsupported)
                                !liquidGlassEnabled -> stringResource(R.string.liquid_glass_nav_bar_disabled)
                                else -> stringResource(R.string.liquid_glass_nav_bar_desc)
                            },
                        icon = { Icon(painterResource(R.drawable.blur_on), null) },
                        checked = liquidGlassNavBarEnabled,

                        isEnabled = liquidGlassEnabled && supported,
                        onCheckedChange = onLiquidGlassNavBarEnabledChange,
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("navigation_bar_glass_glow"),
                        title = { Text(stringResource(R.string.navigation_bar_glass_glow)) },
                        description = stringResource(R.string.navigation_bar_glass_glow_desc),
                        icon = { Icon(painterResource(R.drawable.solar_brightness_high_linear), null) },
                        checked = navigationBarGlassGlow,
                        onCheckedChange = onNavigationBarGlassGlowChange,
                    )
                }

                item {
                    SliderPreferenceRow(
                        title = stringResource(R.string.navigation_bar_glass_glow_intensity),
                        description = stringResource(R.string.navigation_bar_glass_glow_intensity_desc),
                        iconRes = R.drawable.tune,
                        value = navigationBarGlassGlowIntensity,
                        onValueChange = onNavigationBarGlassGlowIntensityChange,
                        range = 0.2f..2f,
                        valueLabel = { "${(it * 100).roundToInt()}%" },
                        default = NAVIGATION_BAR_GLASS_GLOW_INTENSITY_DEFAULT,
                        enabled = navigationBarGlassGlow,
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("hide_navigation_bar_labels"),
                        title = { Text(stringResource(R.string.hide_navigation_bar_labels)) },
                        description = stringResource(R.string.hide_navigation_bar_labels_desc),
                        icon = { Icon(painterResource(R.drawable.nav_bar), null) },
                        checked = hideNavigationBarLabels,
                        onCheckedChange = onHideNavigationBarLabelsChange,
                    )
                }
            }

            PreferenceGroup(
                modifier = positions.modifierFor("navigation_bar_dimensions"),
                title = stringResource(R.string.navigation_bar_dimensions),
            ) {
                item {
                    SliderPreferenceRow(
                        title = stringResource(R.string.navigation_bar_width),
                        description = stringResource(R.string.navigation_bar_width_desc),
                        iconRes = R.drawable.tune,
                        value = navigationBarWidth,
                        onValueChange = onNavigationBarWidthChange,
                        range = 0.5f..1.0f,
                        valueLabel = { "${(it * 100).roundToInt()}%" },
                        default = NAVIGATION_BAR_WIDTH_DEFAULT,
                        preview = { tempWidth ->
                            NavBarPreview(
                                widthFraction = tempWidth,
                                heightMultiplier = navigationBarHeight,
                                opacity = navigationBarOpacity,
                                transparency = navigationBarTransparency,
                                labelSpacing = navigationBarLabelSpacing,
                                cornerRadius = navigationBarCornerRadius,
                            )
                        },
                        enabled = !liquidGlassNavBarEnabled,
                    )
                }

                item {
                    SliderPreferenceRow(
                        title = stringResource(R.string.navigation_bar_height),
                        description = stringResource(R.string.navigation_bar_height_desc),
                        iconRes = R.drawable.tune,
                        value = navigationBarHeight,
                        onValueChange = onNavigationBarHeightChange,
                        range = 0.8f..1.4f,
                        valueLabel = { "${(it * 100).roundToInt()}%" },
                        default = NAVIGATION_BAR_HEIGHT_DEFAULT,
                        preview = { tempHeight ->
                            NavBarPreview(
                                widthFraction = navigationBarWidth,
                                heightMultiplier = tempHeight,
                                opacity = navigationBarOpacity,
                                transparency = navigationBarTransparency,
                                labelSpacing = navigationBarLabelSpacing,
                                cornerRadius = navigationBarCornerRadius,
                            )
                        },
                        enabled = !liquidGlassNavBarEnabled,
                    )
                }

                item {
                    SliderPreferenceRow(
                        title = stringResource(R.string.navigation_bar_opacity),
                        description = stringResource(R.string.navigation_bar_opacity_desc),
                        iconRes = R.drawable.tune,
                        value = navigationBarOpacity,
                        onValueChange = onNavigationBarOpacityChange,
                        range = 0.2f..1.0f,
                        valueLabel = { "${(it * 100).roundToInt()}%" },
                        default = NAVIGATION_BAR_OPACITY_DEFAULT,
                        preview = { tempOpacity ->
                            NavBarPreview(
                                widthFraction = navigationBarWidth,
                                heightMultiplier = navigationBarHeight,
                                opacity = tempOpacity,
                                transparency = navigationBarTransparency,
                                labelSpacing = navigationBarLabelSpacing,
                                cornerRadius = navigationBarCornerRadius,
                            )
                        },
                        enabled = !liquidGlassNavBarEnabled,
                    )
                }

                item {
                    SliderPreferenceRow(
                        title = stringResource(R.string.navigation_bar_transparency),
                        description = stringResource(R.string.navigation_bar_transparency_desc),
                        iconRes = R.drawable.tune,
                        value = navigationBarTransparency,
                        onValueChange = onNavigationBarTransparencyChange,
                        range = 0.0f..0.95f,
                        valueLabel = { "${(it * 100).roundToInt()}%" },
                        default = NAVIGATION_BAR_TRANSPARENCY_DEFAULT,
                        preview = { tempTransparency ->
                            NavBarPreview(
                                widthFraction = navigationBarWidth,
                                heightMultiplier = navigationBarHeight,
                                opacity = navigationBarOpacity,
                                transparency = tempTransparency,
                                labelSpacing = navigationBarLabelSpacing,
                                cornerRadius = navigationBarCornerRadius,
                            )
                        },
                        enabled = !liquidGlassNavBarEnabled,
                    )
                }

                item {
                    SliderPreferenceRow(
                        title = stringResource(R.string.navigation_bar_label_spacing),
                        description = stringResource(R.string.navigation_bar_label_spacing_desc),
                        iconRes = R.drawable.tune,
                        value = navigationBarLabelSpacing,
                        onValueChange = onNavigationBarLabelSpacingChange,
                        range = 0f..16f,
                        valueLabel = { "${it.roundToInt()} dp" },
                        default = NAVIGATION_BAR_LABEL_SPACING_DEFAULT,
                        preview = { tempSpacing ->
                            NavBarPreview(
                                widthFraction = navigationBarWidth,
                                heightMultiplier = navigationBarHeight,
                                opacity = navigationBarOpacity,
                                transparency = navigationBarTransparency,
                                labelSpacing = tempSpacing,
                                cornerRadius = navigationBarCornerRadius,
                            )
                        },
                        enabled = !liquidGlassNavBarEnabled,
                    )
                }

                item {
                    SliderPreferenceRow(
                        title = stringResource(R.string.navigation_bar_corner_radius),
                        description = stringResource(R.string.navigation_bar_corner_radius_desc),
                        iconRes = R.drawable.tune,
                        value = navigationBarCornerRadius,
                        onValueChange = onNavigationBarCornerRadiusChange,
                        range = 0f..48f,
                        valueLabel = { "${it.roundToInt()} dp" },
                        default = NAVIGATION_BAR_CORNER_RADIUS_DEFAULT,
                        preview = { tempRadius ->
                            NavBarPreview(
                                widthFraction = navigationBarWidth,
                                heightMultiplier = navigationBarHeight,
                                opacity = navigationBarOpacity,
                                transparency = navigationBarTransparency,
                                labelSpacing = navigationBarLabelSpacing,
                                cornerRadius = tempRadius,
                            )
                        },
                        enabled = !liquidGlassNavBarEnabled,
                    )
                }

                item {
                    val allDefaults =
                        navigationBarWidth == NAVIGATION_BAR_WIDTH_DEFAULT &&
                            navigationBarHeight == NAVIGATION_BAR_HEIGHT_DEFAULT &&
                            navigationBarOpacity == NAVIGATION_BAR_OPACITY_DEFAULT &&
                            navigationBarTransparency == NAVIGATION_BAR_TRANSPARENCY_DEFAULT &&
                            navigationBarLabelSpacing == NAVIGATION_BAR_LABEL_SPACING_DEFAULT &&
                            navigationBarCornerRadius == NAVIGATION_BAR_CORNER_RADIUS_DEFAULT

                    OutlinedButton(
                        onClick = {
                            onNavigationBarWidthChange(NAVIGATION_BAR_WIDTH_DEFAULT)
                            onNavigationBarHeightChange(NAVIGATION_BAR_HEIGHT_DEFAULT)
                            onNavigationBarOpacityChange(NAVIGATION_BAR_OPACITY_DEFAULT)
                            onNavigationBarTransparencyChange(NAVIGATION_BAR_TRANSPARENCY_DEFAULT)
                            onNavigationBarLabelSpacingChange(NAVIGATION_BAR_LABEL_SPACING_DEFAULT)
                            onNavigationBarCornerRadiusChange(NAVIGATION_BAR_CORNER_RADIUS_DEFAULT)
                        },
                        enabled = !allDefaults && !liquidGlassNavBarEnabled,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.restore),
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(stringResource(R.string.navigation_bar_reset_dimensions))
                    }
                }
            }
        }

        ScreenHeaderHaze(
            hazeState = headerHaze,
            systemBarsTopPadding = systemBarsTopPadding,
        )
        }
}
}

@Composable
private fun SliderPreferenceRow(
    title: String,
    description: String,
    iconRes: Int,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: (Float) -> String,
    default: Float? = null,
    preview: (@Composable (Float) -> Unit)? = null,

    enabled: Boolean = true,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }

    if (showDialog) {
        var tempValue by remember { mutableFloatStateOf(value) }

        DefaultDialog(
            onDismiss = {
                tempValue = value
                showDialog = false
            },
            buttons = {
                if (default != null) {
                    TextButton(
                        onClick = { tempValue = default },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(R.string.reset))
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        tempValue = value
                        showDialog = false
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        onValueChange(tempValue)
                        showDialog = false
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(16.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                if (preview != null) {
                    Text(
                        text = stringResource(R.string.preview),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    preview(tempValue)
                    Spacer(modifier = Modifier.padding(top = 16.dp))
                }

                Text(
                    text = valueLabel(tempValue),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                Slider(
                    value = tempValue,
                    onValueChange = { tempValue = it },
                    valueRange = range,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (description.isNotBlank()) {
                    Spacer(modifier = Modifier.padding(top = 12.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    PreferenceEntry(
        title = { Text(title) },
        description = valueLabel(value),
        icon = { Icon(painterResource(iconRes), null) },
        onClick = { showDialog = true },
        isEnabled = enabled,
    )
}

@Composable
private fun NavBarPreview(
    widthFraction: Float,
    heightMultiplier: Float,
    opacity: Float,
    transparency: Float,
    labelSpacing: Float,
    cornerRadius: Float,
) {
    val resolvedBarHeight = NavigationBarHeight * heightMultiplier
    val shape = RoundedCornerShape(cornerRadius.dp)

    val baseColor = MaterialTheme.colorScheme.surfaceContainer
    val effectiveAlpha = opacity * (1f - transparency)
    val barColor = baseColor.copy(alpha = effectiveAlpha.coerceIn(0.05f, 1f))
    val indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)

    val fauxScreenBrush =
        Brush.verticalGradient(
            colors = listOf(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                MaterialTheme.colorScheme.surfaceVariant,
            ),
        )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(fauxScreenBrush),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier =
                Modifier
                    .padding(
                        bottom = 16.dp,
                        start = 16.dp,
                        end = 16.dp,
                    ).fillMaxWidth(widthFraction.coerceIn(0.5f, 1f))
                    .height(resolvedBarHeight),
            shape = shape,
            color = barColor,
            tonalElevation = NavigationBarDefaults.Elevation,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                val items = Screens.MainScreens
                items.forEachIndexed { index, screen ->
                    val selected = index == 0
                    val selectedColor = MaterialTheme.colorScheme.primary
                    val unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(if (selected) indicatorColor else Color.Transparent)
                                    .padding(horizontal = 18.dp, vertical = 7.dp),
                        ) {
                            Icon(
                                painter =
                                    painterResource(
                                        if (selected) screen.iconIdActive else screen.iconIdInactive,
                                    ),
                                contentDescription = null,
                                tint = if (selected) selectedColor else unselectedColor,
                            )
                        }
                        Spacer(Modifier.height(labelSpacing.dp))
                        Text(
                            text = stringResource(screen.titleId),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) selectedColor else unselectedColor,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactBehaviorPreview(
    behavior: NavigationBarCompactBehavior,
    modifier: Modifier = Modifier,
) {
    var demoCompact by remember { mutableStateOf(false) }
    LaunchedEffect(behavior) {
        when (behavior) {
            NavigationBarCompactBehavior.ALWAYS_EXPANDED -> demoCompact = false
            NavigationBarCompactBehavior.ALWAYS_COMPACT -> demoCompact = true
            NavigationBarCompactBehavior.ADAPTIVE -> Unit
        }
    }
    val compactFraction by animateFloatAsState(
        targetValue = when (behavior) {
            NavigationBarCompactBehavior.ALWAYS_EXPANDED -> 0f
            NavigationBarCompactBehavior.ALWAYS_COMPACT -> 1f
            NavigationBarCompactBehavior.ADAPTIVE -> if (demoCompact) 1f else 0f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = 350f),
        label = "compactBehaviorPreviewFraction",
    )

    val primary = MaterialTheme.colorScheme.primary
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val fauxScreenBrush =
        Brush.verticalGradient(
            listOf(
                primary.copy(alpha = 0.30f),
                MaterialTheme.colorScheme.surfaceVariant,
            ),
        )

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(212.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(fauxScreenBrush)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (behavior == NavigationBarCompactBehavior.ADAPTIVE) {
                            demoCompact = !demoCompact
                        }
                    },
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                repeat(2) {
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(primary.copy(alpha = 0.22f)),
                    )
                }
            }

            val miniPlayerHeight = lerp(30.dp, 26.dp, compactFraction)
            val navSlide = lerp(0.dp, 62.dp, compactFraction)
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 62.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(miniPlayerHeight)
                            .offset { IntOffset(x = 0, y = (navSlide.toPx() * 0.35f).roundToInt()) }
                            .clip(RoundedCornerShape(14.dp))
                            .background(surfaceContainer.copy(alpha = 0.96f)),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(18.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(primary.copy(alpha = 0.55f)),
                        )
                        Box(
                            modifier =
                                Modifier
                                    .padding(start = 8.dp)
                                    .weight(1f)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(onSurfaceVariant.copy(alpha = 0.35f)),
                        )
                        Icon(
                            painter = painterResource(R.drawable.solar_play_linear),
                            contentDescription = null,
                            tint = onSurfaceVariant,
                            modifier =
                                Modifier
                                    .size(16.dp)
                                    .alpha(1f - compactFraction * 0.6f),
                        )
                    }
                }
                CompactPreviewCircle(
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .offset { IntOffset(x = (-46.dp.toPx() * compactFraction).roundToInt(), y = 0) }
                            .alpha(compactFraction),
                    tint = surfaceContainer,
                    iconTint = onSurfaceVariant,
                )
                CompactPreviewCircle(
                    modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .offset { IntOffset(x = (46.dp.toPx() * compactFraction).roundToInt(), y = 0) }
                            .alpha(compactFraction),
                    tint = surfaceContainer,
                    iconTint = onSurfaceVariant,
                )
            }

            Surface(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp)
                        .fillMaxWidth(0.8f)
                        .height(44.dp)
                        .offset { IntOffset(x = 0, y = (navSlide.toPx()).roundToInt()) }
                        .graphicsLayer { alpha = 1f - compactFraction * 0.95f },
                shape = RoundedCornerShape(24.dp),
                color = surfaceContainer.copy(alpha = 0.96f),
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    val items = Screens.MainScreens
                    items.forEachIndexed { index, screen ->
                        val selected = index == 0
                        Icon(
                            painter = painterResource(
                                if (selected) screen.iconIdActive else screen.iconIdInactive,
                            ),
                            contentDescription = null,
                            tint = if (selected) primary else onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.navigation_bar_tap_to_try),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun CompactPreviewCircle(
    modifier: Modifier = Modifier,
    tint: Color,
    iconTint: Color,
) {
    Box(
        modifier =
            modifier
                .size(28.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(tint.copy(alpha = 0.96f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.search),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(14.dp),
        )
    }
}
