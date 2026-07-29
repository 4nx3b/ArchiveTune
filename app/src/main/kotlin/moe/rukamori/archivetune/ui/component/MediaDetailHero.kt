/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.component

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AppBarHeight
import moe.rukamori.archivetune.ui.utils.YtimgResizePolicy
import moe.rukamori.archivetune.ui.utils.fadingEdge
import moe.rukamori.archivetune.ui.utils.resize

@Composable
public fun MediaDetailHero(
    title: String,
    thumbnailUrl: String?,
    @DrawableRes fallbackIcon: Int,
    systemBarsTopPadding: Dp,
    isAdded: Boolean,
    @StringRes addContentDescription: Int,
    @StringRes removeContentDescription: Int,
    onShuffle: (() -> Unit)?,
    onPlay: (() -> Unit)?,
    onToggleAdd: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: AnnotatedString? = null,
    metadata: String? = null,
    description: String? = null,
    additionalPrimaryActions: (@Composable RowScope.(Color) -> Unit)? = null,
    /**
     * When true, the bottom of the hero is rendered as a blurred duplicate of
     * the thumbnail instead of a flat surface-color gradient. Looks closer to
     * modern music apps (Spotify / Apple Music / YouTube Music) which blur the
     * artwork under the action row.
     *
     * Defaults to true — every playlist/album screen in the app opts in.
     */
    useBlurredBackdrop: Boolean = true,
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val menuState = LocalMenuState.current
    val heroContentColor =
        if (surfaceColor.luminance() > 0.5f) {
            MaterialTheme.colorScheme.onSurface
        } else {
            Color.White
        }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = MediaDetailHeroMinHeight)
                .background(surfaceColor)
                .clipToBounds(),
    ) {
        if (thumbnailUrl != null) {
            AsyncImage(
                model =
                    thumbnailUrl.resize(
                        width = MediaDetailHeroArtworkSizePx,
                        height = MediaDetailHeroArtworkSizePx,
                        sizeBuckets = MediaDetailHeroArtworkSizeBuckets,
                        ytimgResizePolicy = YtimgResizePolicy.PreserveOriginal,
                    ),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(fallbackIcon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(96.dp),
                )
            }
        }

        if (useBlurredBackdrop && thumbnailUrl != null) {
            // ─── Blurred backdrop at the bottom ─────────────────────────────
            // Render a second copy of the thumbnail, blurred, clipped to the
            // bottom ~62% of the hero — covering everything from just below
            // the playlist title down through the play / shuffle / download
            // action buttons.
            //
            // The previous 50% band was too short: the action-button Column
            // is positioned with `top = systemBarsTopPadding + AppBarHeight +
            // 96.dp` and its action row sat *just below* the blur band's top
            // edge, so on tall screens the buttons appeared over a hard
            // transition line between sharp artwork and blurred backdrop.
            // 62% guarantees the entire button row is fully inside the blur.
            //
            // The blur is the *primary* backdrop layer (replacing the previous
            // flat surfaceColor gradient). A frosted tint is layered on top
            // so white text remains readable on bright thumbnails, but the
            // tint is kept LIGHT enough that the blurred artwork is
            // unambiguously visible — addressing the previous bug where the
            // area appeared "completely transparent".
            //
            // Layer order (bottom → top):
            //   1. Original thumbnail (full hero, sharp)
            //   2. Blurred thumbnail duplicate, clipped to bottom 62%
            //      — Modifier.blur() (48dp) dispatches to a hardware-
            //        accelerated RenderEffect on API 31+ and falls back to
            //        a software blur on older APIs.
            //   3. Frosted-glass tint (surfaceColor @ 35% alpha) so the blur
            //      reads as frosted glass rather than just a soft copy of
            //      the artwork.
            //   4. Vertical scrim — darker at the very bottom edge so the
            //      action buttons remain readable on bright thumbnails,
            //      fading to transparent at the top so the blur shows.
            //   5. Top-of-band gradient — softly fades the *top* of the blur
            //      band into the sharp artwork above it (no hard line).
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.62f),
            ) {
                AsyncImage(
                    model =
                        thumbnailUrl.resize(
                            width = MediaDetailHeroArtworkSizePx,
                            height = MediaDetailHeroArtworkSizePx,
                            sizeBuckets = MediaDetailHeroArtworkSizeBuckets,
                            ytimgResizePolicy = YtimgResizePolicy.PreserveOriginal,
                        ),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .matchParentSize()
                            .then(MediaDetailHeroBlurModifier),
                )
                // Frosted-glass tint — 35% alpha. Strong enough to mute the
                // sharpest color peaks from the artwork (so white text reads),
                // light enough that the 48dp blur is still obviously visible.
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .background(surfaceColor.copy(alpha = 0.35f)),
                )
                // Top-of-band fade — softly fades the top 22% of the blur
                // band from transparent → surfaceColor so there is no hard
                // line where the blurred duplicate meets the sharp artwork
                // above it. Without this, the eye reads the blur as a
                // separate panel rather than a continuous frosted surface.
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to surfaceColor.copy(alpha = 0.35f),
                                    0.22f to Color.Transparent,
                                    0.50f to Color.Transparent,
                                ),
                            ),
                )
                // Legibility scrim — darker only at the very bottom edge so
                // the action buttons remain readable on bright thumbnails.
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.55f to Color.Transparent,
                                    0.80f to Color.Black.copy(alpha = 0.18f),
                                    1f to Color.Black.copy(alpha = 0.36f),
                                ),
                            ),
                )
            }
            // Top-of-hero scrim for status-bar legibility — kept separate from
            // the blur band so it covers the full hero height (the blur band
            // only covers the bottom 62%).
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.42f),
                                0.18f to Color.Transparent,
                                0.50f to Color.Transparent,
                            ),
                        ),
            )
        } else {
            // Legacy flat-gradient backdrop — used when there's no thumbnail
            // to blur or when the caller explicitly opts out.
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.42f),
                                0.18f to Color.Transparent,
                                0.42f to Color.Transparent,
                                0.72f to surfaceColor.copy(alpha = 0.78f),
                                1f to surfaceColor,
                            ),
                        ),
            )
        }

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .widthIn(max = MediaDetailContentMaxWidth)
                    .padding(
                        start = MediaDetailHorizontalPadding,
                        top = systemBarsTopPadding + AppBarHeight + 96.dp,
                        end = MediaDetailHorizontalPadding,
                        bottom = 24.dp,
                    ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Title — use a tighter lineHeight than headlineLarge's default
            // 40sp to avoid the "weird spacing" the user reported when a
            // playlist title wraps to two lines.
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge.copy(lineHeight = 36.sp),
                color = heroContentColor,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium.copy(lineHeight = 22.sp),
                    color = heroContentColor.copy(alpha = 0.82f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            description?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                    color = heroContentColor.copy(alpha = 0.76f),
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            metadata?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = heroContentColor.copy(alpha = 0.62f),
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                )
            }

            MediaDetailPrimaryActions(
                isAdded = isAdded,
                contentColor = heroContentColor,
                contrastingColor = surfaceColor,
                addContentDescription = addContentDescription,
                removeContentDescription = removeContentDescription,
                onShuffle = onShuffle,
                onPlay = onPlay,
                onToggleAdd =
                    remember(isAdded, menuState, onToggleAdd, removeContentDescription, title) {
                        onToggleAdd?.let { toggleAdd ->
                            if (isAdded) {
                                {
                                    menuState.showDialog {
                                        MediaDetailRemovalConfirmationDialog(
                                            title = title,
                                            removeContentDescription = removeContentDescription,
                                            onDismiss = menuState::dismissDialog,
                                            onConfirm = {
                                                menuState.dismissDialog()
                                                toggleAdd()
                                            },
                                        )
                                    }
                                }
                            } else {
                                toggleAdd
                            }
                        }
                    },
                additionalActions = additionalPrimaryActions,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun MediaDetailRemovalConfirmationDialog(
    title: String,
    @StringRes removeContentDescription: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    DefaultDialog(
        onDismiss = onDismiss,
        title = {
            Text(text = stringResource(removeContentDescription))
        },
        buttons = {
            TextButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(text = stringResource(android.R.string.cancel))
            }
            TextButton(
                onClick = onConfirm,
                shapes = ButtonDefaults.shapes(),
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text(text = stringResource(removeContentDescription))
            }
        },
    ) {
        Text(
            text = stringResource(R.string.remove_from_library_confirm, title),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
public fun MediaDetailPrimaryActions(
    isAdded: Boolean,
    contentColor: Color,
    contrastingColor: Color,
    @StringRes addContentDescription: Int,
    @StringRes removeContentDescription: Int,
    onShuffle: (() -> Unit)?,
    onPlay: (() -> Unit)?,
    onToggleAdd: (() -> Unit)?,
    modifier: Modifier = Modifier,
    additionalActions: (@Composable RowScope.(Color) -> Unit)? = null,
) {
    val secondaryButtonColors =
        IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = contentColor.copy(alpha = 0.16f),
            contentColor = contentColor,
            disabledContainerColor = contentColor.copy(alpha = 0.08f),
            disabledContentColor = contentColor.copy(alpha = 0.38f),
        )
    val actionScrollState = rememberScrollState()
    val actionScrollMaxValue = actionScrollState.maxValue

    LaunchedEffect(actionScrollMaxValue) {
        if (
            actionScrollMaxValue > 0 &&
            actionScrollMaxValue != Int.MAX_VALUE &&
            actionScrollState.value == 0
        ) {
            actionScrollState.scrollTo(actionScrollMaxValue / 2)
        }
    }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .widthIn(max = MediaDetailContentMaxWidth),
    ) {
        val actionViewportWidth = maxWidth

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fadingEdge(horizontal = MediaDetailActionEdgeFade)
                    .horizontalScroll(actionScrollState),
        ) {
            MediaDetailBalancedActionLayout(
                actionRowScope = this,
                modifier = Modifier.widthIn(min = actionViewportWidth),
            ) {
                onShuffle?.let { shuffle ->
                    FilledTonalIconButton(
                        onClick = shuffle,
                        shape = CircleShape,
                        colors = secondaryButtonColors,
                        modifier =
                            Modifier
                                .layoutId(MediaDetailActionLayoutId.Shuffle)
                                .size(MediaDetailSecondaryActionSize),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.shuffle),
                            contentDescription = stringResource(R.string.shuffle),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                onPlay?.let { play ->
                    val playButtonHeight = ButtonDefaults.MediumContainerHeight
                    Button(
                        onClick = play,
                        shape = RoundedCornerShape(percent = 50),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = contentColor,
                                contentColor = contrastingColor,
                            ),
                        contentPadding = ButtonDefaults.contentPaddingFor(playButtonHeight, hasStartIcon = true),
                        modifier =
                            Modifier
                                .layoutId(MediaDetailActionLayoutId.Play)
                                .heightIn(min = playButtonHeight),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.play),
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.iconSizeFor(playButtonHeight)),
                        )
                        Spacer(modifier = Modifier.width(ButtonDefaults.iconSpacingFor(playButtonHeight)))
                        Text(
                            text = stringResource(R.string.play),
                            style = ButtonDefaults.textStyleFor(playButtonHeight),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                onToggleAdd?.let { toggleAdd ->
                    FilledTonalIconButton(
                        onClick = toggleAdd,
                        shape = CircleShape,
                        colors = secondaryButtonColors,
                        modifier =
                            Modifier
                                .layoutId(MediaDetailActionLayoutId.ToggleAdd)
                                .size(MediaDetailSecondaryActionSize),
                    ) {
                        Icon(
                            painter = painterResource(if (isAdded) R.drawable.done else R.drawable.add),
                            contentDescription =
                                stringResource(
                                    if (isAdded) removeContentDescription else addContentDescription,
                                ),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                additionalActions?.invoke(this, contentColor)
            }
        }
    }
}

@Composable
private fun MediaDetailBalancedActionLayout(
    actionRowScope: RowScope,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Layout(
        content = { content(actionRowScope) },
        modifier = modifier,
    ) { measurables, constraints ->
        val actionSpacing = MediaDetailActionSpacing.roundToPx()
        val shuffleActionIndex = measurables.indexOfFirst { it.layoutId == MediaDetailActionLayoutId.Shuffle }
        val playActionIndex = measurables.indexOfFirst { it.layoutId == MediaDetailActionLayoutId.Play }
        val toggleAddActionIndex = measurables.indexOfFirst { it.layoutId == MediaDetailActionLayoutId.ToggleAdd }
        val placeables =
            measurables.map { measurable ->
                measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            }
        val shuffleAction = placeables.getOrNull(shuffleActionIndex)
        val playAction = placeables.getOrNull(playActionIndex)
        val toggleAddAction = placeables.getOrNull(toggleAddActionIndex)
        val otherActions =
            placeables.filterIndexed { index, _ ->
                index != shuffleActionIndex &&
                    index != playActionIndex &&
                    index != toggleAddActionIndex
            }
        val centeredContentWidth =
            placeables.sumOf { it.width } +
                actionSpacing * (placeables.size - 1).coerceAtLeast(0)
        val leftOtherActionCount = otherActions.size / 2
        val leftActions =
            buildList {
                addAll(otherActions.take(leftOtherActionCount))
                if (shuffleAction != null) {
                    add(shuffleAction)
                }
            }
        val rightActions =
            buildList {
                if (toggleAddAction != null) {
                    add(toggleAddAction)
                }
                addAll(otherActions.drop(leftOtherActionCount))
            }
        val leftActionsWidth =
            leftActions.sumOf { it.width } +
                actionSpacing * (leftActions.size - 1).coerceAtLeast(0)
        val rightActionsWidth =
            rightActions.sumOf { it.width } +
                actionSpacing * (rightActions.size - 1).coerceAtLeast(0)
        val balancedContentWidth =
            if (playAction == null) {
                centeredContentWidth
            } else {
                val sideSpacing = if (leftActions.isEmpty() && rightActions.isEmpty()) 0 else actionSpacing
                playAction.width + 2 * (maxOf(leftActionsWidth, rightActionsWidth) + sideSpacing)
            }
        val layoutWidth =
            if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                balancedContentWidth.coerceAtLeast(constraints.minWidth)
            }
        val contentHeight = placeables.maxOfOrNull { it.height } ?: 0
        val layoutHeight =
            if (constraints.hasBoundedHeight) {
                contentHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
            } else {
                contentHeight.coerceAtLeast(constraints.minHeight)
            }

        layout(layoutWidth, layoutHeight) {
            if (playAction == null) {
                var actionX = (layoutWidth - centeredContentWidth) / 2
                placeables.forEach { action ->
                    action.placeRelative(
                        x = actionX,
                        y = (layoutHeight - action.height) / 2,
                    )
                    actionX += action.width + actionSpacing
                }
                return@layout
            }

            val playActionX = (layoutWidth - playAction.width) / 2
            var leftActionX = playActionX - actionSpacing - leftActionsWidth
            var rightActionX = playActionX + playAction.width + actionSpacing

            leftActions.forEach { action ->
                action.placeRelative(
                    x = leftActionX,
                    y = (layoutHeight - action.height) / 2,
                )
                leftActionX += action.width + actionSpacing
            }
            rightActions.forEach { action ->
                action.placeRelative(
                    x = rightActionX,
                    y = (layoutHeight - action.height) / 2,
                )
                rightActionX += action.width + actionSpacing
            }
            playAction.placeRelative(
                x = playActionX,
                y = (layoutHeight - playAction.height) / 2,
            )
        }
    }
}

@Composable
public fun MediaDetailAction(
    @StringRes contentDescription: Int,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isDestructive: Boolean = false,
    content: @Composable () -> Unit,
) {
    val actionDescription = stringResource(contentDescription)
    val colors =
        if (isDestructive) {
            IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                contentColor = MaterialTheme.colorScheme.error,
                disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                disabledContentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.38f),
            )
        } else {
            IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = contentColor.copy(alpha = 0.16f),
                contentColor = contentColor,
                disabledContainerColor = contentColor.copy(alpha = 0.08f),
                disabledContentColor = contentColor.copy(alpha = 0.38f),
            )
        }

    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = colors,
        modifier =
            modifier
                .size(MediaDetailActionSize)
                .semantics { this.contentDescription = actionDescription },
    ) {
        content()
    }
}

@Composable
public fun MediaDetailIconAction(
    @DrawableRes icon: Int,
    @StringRes contentDescription: Int,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isDestructive: Boolean = false,
) {
    MediaDetailAction(
        contentDescription = contentDescription,
        contentColor = contentColor,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        isDestructive = isDestructive,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
    }
}

private const val MediaDetailHeroArtworkSizePx = 1200
private val MediaDetailHeroArtworkSizeBuckets = listOf(MediaDetailHeroArtworkSizePx)
private val MediaDetailHeroMinHeight = 560.dp
private val MediaDetailHorizontalPadding = 24.dp
private val MediaDetailContentMaxWidth = 720.dp
private val MediaDetailActionSpacing = 12.dp
private val MediaDetailActionEdgeFade = 20.dp
private val MediaDetailSecondaryActionSize = 52.dp
private val MediaDetailActionSize = 48.dp

/**
 * Blur modifier used by the hero's backdrop. Compose's [Modifier.blur]
 * already dispatches to a hardware-accelerated
 * `android.graphics.RenderEffect` on API 31+ (Android 12) and falls back
 * to a software-rendered blur on older API levels — so calling
 * `RenderEffect.createBlurEffect` directly is unnecessary and produces a
 * type mismatch (`android.graphics.RenderEffect` vs the Compose
 * `androidx.compose.ui.graphics.RenderEffect` expected by
 * `GraphicsLayerScope.renderEffect`).
 *
 * The radius (48dp) is intentionally large — a 24dp blur was still too
 * subtle behind the dense collage of a 2×2 playlist thumbnail and read
 * as "slightly soft" rather than "frosted glass". 48dp produces an
 * unambiguously blurred backdrop that the action-button row can sit on
 * top of, while still preserving enough color information that the
 * artwork is recognisable. A frosted-glass tint is layered on top in
 * the hero to guarantee legibility regardless of the thumbnail's
 * average color.
 */
private val MediaDetailHeroBlurModifier: Modifier = Modifier.blur(48.dp)

private enum class MediaDetailActionLayoutId {
    Shuffle,
    Play,
    ToggleAdd,
}
