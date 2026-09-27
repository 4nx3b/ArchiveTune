/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 *
 * The iOS-style grouped settings cards for the redesigned Settings home:
 * one rounded translucent surface per section (the same alpha-glass language
 * the app's other cards speak, via glassAwareCardColor), rows inside it with
 * hairline separators, small caption above, generous spacing around —
 * the reference's "grouped rounded cards" structure.
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectIsPressedAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.component.glassAwareCardBorder
import moe.rukamori.archivetune.ui.component.glassAwareCardColor

/** iOS-style geometry for the grouped settings cards. */
object SettingsCardDimensions {
    /** Screen edge -> card. */
    val ScreenPadding = 16.dp

    /** Between adjacent groups (and caption -> previous group). */
    val GroupSpacing = 18.dp

    /** Caption's bottom gap to its card. */
    val CaptionGap = 7.dp

    /** Card corner radius — large, like the reference. */
    val CardCorner = 20.dp

    /** Row height floor — compact like the reference (~52-56). */
    val RowMinHeight = 54.dp

    /** Row internal padding. */
    val RowHorizontalPadding = 16.dp
    val RowVerticalPadding = 10.dp

    /** Icon size — 20-22dp system-style, per the reference. */
    val RowIconSize = 22.dp

    /** Gap between icon and label. */
    val IconLabelGap = 14.dp
}

/**
 * One settings section: caption above, one unified translucent card below,
 * [SettingsItem]s as rows separated by hairline dividers.
 */
@Composable
fun SettingsGroupCard(
    group: SettingsGroup,
    modifier: Modifier = Modifier,
    rowTrailing: (@Composable (SettingsItem) -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SettingsGroupCaption(
            title = group.title,
            modifier = Modifier.padding(
                start = SettingsCardDimensions.ScreenPadding + 8.dp,
                end = SettingsCardDimensions.ScreenPadding + 8.dp,
            ),
        )
        Spacer(Modifier.height(SettingsCardDimensions.CaptionGap))
        SettingsCardSurface {
            Column {
                group.items.forEachIndexed { index, item ->
                    if (index > 0) {
                        SettingsRowDivider()
                    }
                    SettingsListRow(
                        item = item,
                        trailing = rowTrailing?.let { it(item) },
                    )
                }
            }
        }
    }
}

/** The shared translucent grouped-surface — also used by search results. */
@Composable
fun SettingsCardSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(SettingsCardDimensions.CardCorner)
    val container = glassAwareCardColor()
    Surface(
        shape = shape,
        color = container,
        border = null,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsCardDimensions.ScreenPadding)
                .glassAwareCardBorder(shape),
        content = content,
    )
}

/** Small, quiet section caption above each card (the reference's captions). */
@Composable
fun SettingsGroupCaption(
    title: String,
    modifier: Modifier = Modifier,
) {
    if (title.isBlank()) return
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Hairline separator inset to align with the row's label. */
@Composable
fun SettingsRowDivider(modifier: Modifier = Modifier) {
    androidx.compose.material3.HorizontalDivider(
        thickness = 0.5.dp,
        color = dividerColor(),
        modifier = modifier.padding(
            start = SettingsCardDimensions.RowHorizontalPadding +
                SettingsCardDimensions.RowIconSize +
                SettingsCardDimensions.IconLabelGap,
        ),
    )
}

@Composable
private fun dividerColor(): Color {
    val glass = moe.rukamori.archivetune.ui.component.rememberLiquidGlassEnabled()
    return if (glass) {
        if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
            Color.White.copy(alpha = 0.10f)
        } else {
            Color.Black.copy(alpha = 0.07f)
        }
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    }
}

/**
 * The reference row: [icon] [title (+subtitle)] [badge/value] [chevron].
 *
 * Neutral, thin, system-style iconography; a restrained accent only on the
 * icon tint (ArchiveTune's identity); chevron only when the row navigates.
 */
@Composable
fun SettingsListRow(
    item: SettingsItem,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "settingsRowScale",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(SettingsCardDimensions.CardCorner / 2))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = item.onClick,
                )
                .heightIn(min = SettingsCardDimensions.RowMinHeight)
                .padding(
                    horizontal = SettingsCardDimensions.RowHorizontalPadding,
                    vertical = SettingsCardDimensions.RowVerticalPadding,
                ),
    ) {
        Box(
            modifier = Modifier.size(SettingsCardDimensions.RowIconSize),
            contentAlignment = Alignment.Center,
        ) {
            if (item.showUpdateIndicator) {
                BadgedBox(
                    badge = {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(9.dp),
                        )
                    },
                ) {
                    Icon(
                        painter = item.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(SettingsCardDimensions.RowIconSize),
                    )
                }
            } else {
                Icon(
                    painter = item.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(SettingsCardDimensions.RowIconSize),
                )
            }
        }

        Spacer(Modifier.width(SettingsCardDimensions.IconLabelGap))

        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.subtitle?.let { subtitle ->
                Spacer(Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        item.badge?.let { badge ->
            Spacer(Modifier.width(10.dp))
            Text(
                text = badge,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        trailing?.let {
            Spacer(Modifier.width(10.dp))
            it()
        }

        Spacer(Modifier.width(6.dp))
        Icon(
            painter = painterResource(R.drawable.chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(18.dp),
        )
    }
}
