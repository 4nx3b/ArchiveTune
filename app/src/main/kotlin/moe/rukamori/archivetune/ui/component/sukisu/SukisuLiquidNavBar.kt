/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * SukiSU liquid glass navigation bar, ported from SukiSU-Ultra's
 * FloatingBottomBar (https://github.com/sukisu-ultra/sukisu-ultra), which
 * adapts compose-miuix-ui's IosLiquidGlassNavigationBar (Apache 2.0) on top
 * of the kyant backdrop library already used by ArchiveTune.
 */

package moe.rukamori.archivetune.ui.component.sukisu

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow as KyantShadow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.constants.DisableAnimationsKey
import moe.rukamori.archivetune.constants.FloatingNavigationBarMaxWidth
import moe.rukamori.archivetune.constants.HideNavigationBarLabelsKey
import moe.rukamori.archivetune.ui.component.InnerShadow
import moe.rukamori.archivetune.ui.component.LiquidGlassDragAnimation
import moe.rukamori.archivetune.ui.component.innerShadow
import moe.rukamori.archivetune.ui.screens.Screens
import moe.rukamori.archivetune.utils.rememberPreference
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

private val SukisuBarHeight = 64.dp
private val SukisuPillHeight = 56.dp
private val SukisuItemPadding = 4.dp

private const val SukisuBarHighlightExtraDegrees = -45f
private const val SukisuPillHighlightExtraDegrees = 90f

@Composable
fun SukisuLiquidNavBar(
    items: List<Screens>,
    modifier: Modifier = Modifier,
    backdrop: Backdrop?,
    isSelected: (Screens) -> Boolean,
    onItemClick: (Screens, Boolean) -> Unit,
) {
    if (items.isEmpty()) return

    val (hideLabels) = rememberPreference(HideNavigationBarLabelsKey, defaultValue = false)
    val (disableAnimations) = rememberPreference(DisableAnimationsKey, defaultValue = false)

    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val pillShape = remember { CircleShape }
    val accentColor = MaterialTheme.colorScheme.primary
    val tabContentColor = MaterialTheme.colorScheme.onSurface
    val containerColor =
        if (backdrop != null) {
            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }

    val tabsBackdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()

    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }

    val offsetAnimation = remember { Animatable(0f) }
    val rubberBandPx = with(density) { 4.dp.toPx() }
    val panelOffset by remember(rubberBandPx) {
        derivedStateOf {
            if (totalWidthPx == 0f) {
                0f
            } else {
                val fraction = (offsetAnimation.value / totalWidthPx).fastCoerceIn(-1f, 1f)
                rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
            }
        }
    }

    val tabsCount = items.size
    var currentIndex by remember { mutableIntStateOf(0) }
    val itemsUpdated = rememberUpdatedState(items)
    val isSelectedUpdated = rememberUpdatedState(isSelected)
    val onItemClickUpdated = rememberUpdatedState(onItemClick)

    fun indexAt(positionX: Float): Int {
        if (tabWidthPx == 0f) return currentIndex
        val horizontalPaddingPx = with(density) { SukisuItemPadding.toPx() }
        val logicalX = if (isLtr) positionX else totalWidthPx - positionX
        return ((logicalX - horizontalPaddingPx) / tabWidthPx)
            .toInt()
            .coerceIn(0, tabsCount - 1)
    }

    val dampedDragAnimation =
        remember(animationScope, tabsCount, density, isLtr) {
            LiquidGlassDragAnimation(
                animationScope = animationScope,
                initialValue = currentIndex.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                canDrag = { offset -> offset.x in 0f..totalWidthPx },
                onDragStarted = { position ->
                    updateValue(indexAt(position.x).toFloat())
                },
                onDragStopped = {
                    val targetIndex = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                    if (currentIndex != targetIndex) {
                        currentIndex = targetIndex
                        val screen = itemsUpdated.value.getOrNull(targetIndex)
                        if (screen != null) {
                            onItemClickUpdated.value(screen, true)
                        }
                    }
                    updateValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    if (tabWidthPx > 0f && dragAmount.x != 0f) {
                        updateValue(
                            (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                                .coerceIn(0f, (tabsCount - 1).toFloat()),
                        )
                        animationScope.launch {
                            offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                        }
                    }
                },
            )
        }

    fun activateTab(index: Int) {
        if (index !in 0 until tabsCount) return
        if (currentIndex != index) {
            currentIndex = index
            onItemClickUpdated.value(itemsUpdated.value[index], true)
        }
        dampedDragAnimation.animateToValue(index.toFloat())
    }

    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow {
            val list = itemsUpdated.value
            list.indexOfFirst { isSelectedUpdated.value(it) }
        }.drop(1).collect { idx ->
            if (idx >= 0 && idx < tabsCount && idx != currentIndex) {
                currentIndex = idx
                dampedDragAnimation.animateToValue(idx.toFloat())
            }
        }
    }

    val interactiveHighlight =
        remember(animationScope, tabWidthPx, dampedDragAnimation) {
            SukisuInteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) {
                            (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset
                        } else {
                            size.width - (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset
                        },
                        size.height / 2f,
                    )
                },
            )
        }

    val gravityAngleRad = rememberSukisuGravityAngleRad()
    val barHighlight by remember(gravityAngleRad) {
        derivedStateOf {
            val angleDegrees =
                -(Math.toDegrees(gravityAngleRad.value.toDouble())).toFloat() -
                    SukisuBarHighlightExtraDegrees
            sukisuSpecularHighlight(angleDegrees)
        }
    }
    val pillHighlight by remember(gravityAngleRad) {
        derivedStateOf {
            val angleDegrees =
                -(Math.toDegrees(gravityAngleRad.value.toDouble())).toFloat() -
                    SukisuPillHighlightExtraDegrees
            sukisuSpecularHighlight(angleDegrees)
        }
    }

    val combinedBackdrop =
        if (backdrop != null) {
            rememberCombinedBackdrop(backdrop, tabsBackdrop)
        } else {
            null
        }
    val tabScaleProvider =
        remember(dampedDragAnimation) {
            { lerp(1f, 1.2f, dampedDragAnimation.pressProgress) }
        }

    val glassIsNight = isSystemInDarkTheme()
    val glassSelectedColor =
        if (glassIsNight) Color.White else MaterialTheme.colorScheme.onSurface
    val glassUnselectedColor =
        if (glassIsNight) {
            Color.White
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val itemColors =
        ShortNavigationBarItemDefaults.colors(
            selectedIndicatorColor = Color.Transparent,
            selectedIconColor = glassSelectedColor,
            selectedTextColor = glassSelectedColor,
            unselectedIconColor = glassUnselectedColor,
            unselectedTextColor = glassUnselectedColor,
        )
    val transparentItemColors =
        ShortNavigationBarItemDefaults.colors(
            selectedIndicatorColor = Color.Transparent,
            selectedIconColor = Color.Transparent,
            selectedTextColor = Color.Transparent,
            unselectedIconColor = Color.Transparent,
            unselectedTextColor = Color.Transparent,
        )
    val ghostItemColors =
        ShortNavigationBarItemDefaults.colors(
            selectedIndicatorColor = Color.Transparent,
            selectedIconColor = accentColor,
            selectedTextColor = accentColor,
            unselectedIconColor = accentColor,
            unselectedTextColor = accentColor,
            disabledIconColor = accentColor,
            disabledTextColor = accentColor,
        )
    val displayIndex by remember(dampedDragAnimation) {
        derivedStateOf {
            dampedDragAnimation.value.roundToInt().coerceIn(0, items.lastIndex)
        }
    }

    @Composable
    fun sukisuItemLabel(screen: Screens) {
        val nightGlassLabelStyle =
            if (glassIsNight) {
                LocalTextStyle.current.copy(
                    shadow =
                        androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.8f),
                            blurRadius = 10f,
                        ),
                )
            } else {
                LocalTextStyle.current
            }
        Text(
            text = stringResource(screen.titleId),
            maxLines = 1,
            modifier = Modifier.offset(y = (-4).dp),
            fontWeight = FontWeight.Normal,
            style = nightGlassLabelStyle,
        )
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .widthIn(max = FloatingNavigationBarMaxWidth)
                    .fillMaxWidth()
                    .height(SukisuBarHeight),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (backdrop != null) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clearAndSetSemantics {}
                            .alpha(0f)
                            .kyantLayerBackdrop(tabsBackdrop)
                            .graphicsLayer { translationX = panelOffset }
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { pillShape },
                                effects = {
                                    vibrancy()
                                    blur(4.dp.toPx())
                                    lens(
                                        refractionHeight = 24.dp.toPx(),
                                        refractionAmount = 24.dp.toPx(),
                                    )
                                },
                                onDrawSurface = { drawRect(containerColor) },
                            )
                            .then(interactiveHighlight.modifier)
                            .height(SukisuPillHeight)
                            .padding(horizontal = SukisuItemPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.forEachIndexed { index, screen ->
                        ShortNavigationBarItem(
                            selected = index == currentIndex,
                            onClick = {},
                            enabled = false,
                            colors = ghostItemColors,
                            icon = {
                                Icon(
                                    painter = painterResource(screen.iconIdActive),
                                    contentDescription = null,
                                )
                            },
                            label = if (hideLabels) null else {
                                { sukisuItemLabel(screen) }
                            },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                        )
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        totalWidthPx = coords.size.width.toFloat()
                        val contentWidthPx =
                            totalWidthPx - with(density) { (SukisuItemPadding * 2).toPx() }
                        tabWidthPx = (contentWidthPx / tabsCount).coerceAtLeast(0f)
                    }
                    .graphicsLayer { translationX = panelOffset }
                    .then(
                        if (backdrop != null) {
                            Modifier.drawBackdrop(
                                backdrop = backdrop,
                                shape = { pillShape },
                                effects = {
                                    padding = maxOf(padding, 40.dp.toPx())
                                    vibrancy()
                                    blur(4.dp.toPx())
                                    lens(
                                        refractionHeight = 24.dp.toPx(),
                                        refractionAmount = 24.dp.toPx(),
                                    )
                                },
                                highlight = { barHighlight.copy(alpha = 0.75f) },
                                shadow = {
                                    KyantShadow(
                                        radius = 10.dp,
                                        color = Color.Black,
                                        alpha = if (isDark) 0.2f else 0.1f,
                                    )
                                },
                                layerBlock = {
                                    val width = size.width.coerceAtLeast(1f)
                                    val s =
                                        lerp(
                                            1f,
                                            1f + 16.dp.toPx() / width,
                                            dampedDragAnimation.pressProgress,
                                        )
                                    scaleX = s
                                    scaleY = s
                                },
                                onDrawSurface = { drawRect(containerColor) },
                            )
                        } else {
                            Modifier.background(containerColor, pillShape)
                        },
                    )
                    .then(
                        if (backdrop != null) {
                            interactiveHighlight.modifier.then(interactiveHighlight.gestureModifier)
                        } else {
                            Modifier
                        },
                    )
                    .then(dampedDragAnimation.modifier)
                    .height(SukisuBarHeight)
                    .padding(SukisuItemPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides tabContentColor,
                ) {
                    items.forEachIndexed { index, screen ->
                        val selected = isSelected(screen)
                        val iconScale = remember(screen) { Animatable(1f) }
                        LaunchedEffect(selected, disableAnimations) {
                            if (disableAnimations) {
                                iconScale.snapTo(1f)
                            } else if (selected) {
                                iconScale.snapTo(0.8f)
                                iconScale.animateTo(
                                    targetValue = 1f,
                                    animationSpec =
                                        spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                )
                            } else {
                                iconScale.snapTo(1f)
                            }
                        }

                        ShortNavigationBarItem(
                            selected = selected,
                            onClick = { activateTab(index) },
                            colors = if (index == displayIndex) transparentItemColors else itemColors,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .graphicsLayer {
                                        val scale = tabScaleProvider()
                                        scaleX = scale
                                        scaleY = scale
                                    },
                            icon = {
                                Crossfade(
                                    targetState = selected,
                                    animationSpec = tween(durationMillis = 150),
                                    label = "sukisuNavItemIcon",
                                ) { isSelected ->
                                    Icon(
                                        painter =
                                            painterResource(
                                                if (isSelected) screen.iconIdActive else screen.iconIdInactive,
                                            ),
                                        contentDescription = null,
                                        modifier =
                                            Modifier.graphicsLayer {
                                                scaleX = iconScale.value
                                                scaleY = iconScale.value
                                            },
                                    )
                                }
                            },
                            label = if (hideLabels) null else {
                                { sukisuItemLabel(screen) }
                            },
                        )
                    }
                }
            }

            if (tabWidthPx > 0f) {
                val tabWidthDp = with(density) { tabWidthPx.toDp() }
                if (combinedBackdrop != null) {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .padding(horizontal = SukisuItemPadding)
                            .graphicsLayer {
                                val progressOffset = dampedDragAnimation.value * tabWidthPx
                                translationX =
                                    if (isLtr) {
                                        progressOffset + panelOffset
                                    } else {
                                        -progressOffset + panelOffset
                                    }
                            }
                            .drawBackdrop(
                                backdrop = combinedBackdrop,
                                shape = { pillShape },
                                effects = {
                                    val progress = dampedDragAnimation.pressProgress
                                    lens(
                                        refractionHeight = 10.dp.toPx() * progress,
                                        refractionAmount = 14.dp.toPx() * progress,
                                        depthEffect = true,
                                        chromaticAberration = true,
                                    )
                                },
                                highlight = {
                                    pillHighlight.copy(alpha = dampedDragAnimation.pressProgress)
                                },
                                layerBlock = {
                                    scaleX = dampedDragAnimation.scaleX
                                    scaleY = dampedDragAnimation.scaleY
                                    val velocity = dampedDragAnimation.velocity / 10f
                                    scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                    scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                                },
                                onDrawSurface = {
                                    val progress = dampedDragAnimation.pressProgress
                                    drawRect(
                                        color =
                                            if (!isDark) {
                                                Color.Black.copy(alpha = 0.1f)
                                            } else {
                                                Color.White.copy(alpha = 0.1f)
                                            },
                                        alpha = 1f - progress,
                                    )
                                    drawRect(Color.Black.copy(alpha = 0.03f * progress))
                                },
                            )
                            .innerShadow(shape = pillShape) {
                                if (dampedDragAnimation.pressProgress > 0f) {
                                    InnerShadow(
                                        radius = 8.dp * dampedDragAnimation.pressProgress,
                                        color = Color.Black.copy(alpha = 0.15f),
                                        alpha = dampedDragAnimation.pressProgress,
                                    )
                                } else {
                                    null
                                }
                            }
                            .height(SukisuPillHeight)
                            .width(tabWidthDp),
                    )
                } else {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .padding(horizontal = SukisuItemPadding)
                            .graphicsLayer {
                                val progressOffset = dampedDragAnimation.value * tabWidthPx
                                translationX =
                                    if (isLtr) {
                                        progressOffset + panelOffset
                                    } else {
                                        -progressOffset + panelOffset
                                    }
                            }
                            .clip(pillShape)
                            .background(accentColor.copy(alpha = 0.15f), pillShape)
                            .height(SukisuPillHeight)
                            .width(tabWidthDp),
                    )
                }
            }
        }
    }
}

private fun sukisuSpecularHighlight(angleDegrees: Float): Highlight =
    Highlight(
        width = 1.dp,
        alpha = 1f,
        style =
            HighlightStyle.Default(
                color = Color.White.copy(alpha = 0.12f),
                blendMode = BlendMode.Plus,
                angle = angleDegrees,
                falloff = 1f,
            ),
    )
