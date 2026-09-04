/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * True while menu content renders INSIDE the floating liquid-glass popup
 * ([BottomSheetMenu] with a live kyant backdrop) — the exact condition the
 * lyrics popup's `transparentSurface` flag encodes at its call site. While
 * true, [MenuSurfaceSection] swaps its opaque Muzo card material for a
 * transparent [Surface] of the same shape so the popup's frosted-glass blur
 * stays visible behind the rows (user report 2026-09-04: "The background
 * behind the text is still opaque. This issue was also present in new lyrics
 * popup design. investigate how it fixed that issue and use the same thing
 * for this case too" — the LyricsMenu fix was the transparent surface).
 * Menus rendered anywhere else (plain sheets, dialogs) keep the opaque
 * material because there is no glass to reveal.
 */
val LocalGlassMenuContent = staticCompositionLocalOf { false }

@Composable
fun NewActionButton(
    icon: @Composable () -> Unit,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
) {
    val containerColor = if (backgroundColor.isSpecified) backgroundColor else MaterialTheme.colorScheme.surfaceContainerHigh
    val actionContentColor = if (contentColor.isSpecified) contentColor else MaterialTheme.colorScheme.onSurfaceVariant

    FilledTonalButton(
        onClick = onClick,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp),
        enabled = enabled,
        shape = ButtonDefaults.squareShape,
        colors =
            ButtonDefaults.filledTonalButtonColors(
                containerColor = containerColor,
                contentColor = actionContentColor,
            ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }

            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )
        }
    }
}

@Composable
fun NewMenuItem(
    headlineContent: @Composable () -> Unit,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val content: @Composable () -> Unit = {
        ListItem(
            headlineContent = headlineContent,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            supportingContent = supportingContent,
            modifier = Modifier.padding(horizontal = 4.dp),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            tonalElevation = 0.dp,
        )
    }

    if (onClick == null) {
        Box(modifier = modifier.fillMaxWidth()) {
            content()
        }
    } else {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = Color.Transparent,
        ) {
            content()
        }
    }
}

@Composable
fun NewActionGrid(
    actions: List<NewAction>,
    modifier: Modifier = Modifier,
    columns: Int = 3,
) {
    if (actions.isEmpty()) return

    val columnCount = columns.coerceAtLeast(1)
    val rows = actions.chunked(columnCount)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { action ->
                    NewActionButton(
                        icon = action.icon,
                        text = action.text,
                        onClick = action.onClick,
                        modifier = Modifier.weight(1f),
                        enabled = action.enabled,
                        backgroundColor = action.backgroundColor,
                        contentColor = action.contentColor,
                    )
                }

                repeat(columnCount - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

data class NewAction(
    val icon: @Composable () -> Unit,
    val text: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val backgroundColor: Color = Color.Unspecified,
    val contentColor: Color = Color.Unspecified,
)

@Composable
fun NewMenuContent(
    headerContent: @Composable (() -> Unit)? = null,
    actionGrid: @Composable (() -> Unit)? = null,
    menuItems: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        headerContent?.invoke()
        actionGrid?.invoke()

        if (actionGrid != null && menuItems != null) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }

        menuItems?.invoke()
    }
}

@Composable
fun NewMenuContainer(
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
    ) {
        content()
    }
}

@Composable
fun MenuSurfaceSection(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // ── Muzo sheet section material (2026-09-04 redesign) ──
    // The reference's grouped-action surface: a lighter translucent step
    // above the sheet's own charcoal (#3A3A3C over #1C1C1E), with the
    // reference's ~16pt radius. One surface per group of rows; the rows
    // inside carry thin dividers, not individual cards.
    //
    // 2026-09-04: inside the floating liquid-glass popup this card is the
    // opaque flat grey box the user reported ("the background behind the
    // text is still opaque"). The SAME fix the new lyrics popup uses
    // (LyricsMenu's `transparentSurface = true`): replace the card with a
    // transparent Surface of the same shape so the popup's frosted blur
    // shows through. Gated by [LocalGlassMenuContent], provided only while
    // the glass popup is actually sampling a backdrop — every menu rendered
    // through `menuState.show { ... }` gets the fix without touching any
    // menu file, and non-glass contexts keep the original material.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val sectionColor =
        if (dark) {
            Color(0xFF3A3A3C).copy(alpha = 0.92f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f)
        }
    val onGlassPopup = LocalGlassMenuContent.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (onGlassPopup) Color.Transparent else sectionColor,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

/**
 * The divider BETWEEN menu sections (2026-09-04, user report: "between some
 * list there's no dividers and they have empty space between them too").
 *
 * Rows inside one [MenuSurfaceSection] have always drawn [HorizontalDivider]s
 * between them, but the SECTIONS themselves were separated only by the 4dp
 * gap the previous tightening left — and since [MenuSurfaceSection] renders
 * transparent on the glass popup, there was no card boundary either: some
 * neighbouring rows showed a divider, others just blank space. This draws
 * the same divider the in-section rows use (start-inset to clear the icons,
 * the theme's outlineVariant — which the glass overlay remaps to a faint
 * white hairline), so every list boundary is a visible hairline with no
 * blank gap.
 */
@Composable
fun MenuSectionDivider(
    modifier: Modifier = Modifier,
) {
    HorizontalDivider(
        modifier = modifier.padding(start = 56.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
