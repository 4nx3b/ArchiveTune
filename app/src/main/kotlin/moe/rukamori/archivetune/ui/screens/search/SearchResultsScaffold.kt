/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package moe.rukamori.archivetune.ui.screens.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.getBottom
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.kyant.backdrop.Backdrop
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.HideSearchChromeWhileScrollingKey
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.ui.component.IconButton as AppIconButton
import moe.rukamori.archivetune.ui.component.LiquidGlassActionPill
import moe.rukamori.archivetune.ui.component.glassSource
import moe.rukamori.archivetune.ui.component.liquidGlass
import moe.rukamori.archivetune.ui.component.liquidGlassContentColor
import moe.rukamori.archivetune.ui.component.rememberThrottledBackdrop
import moe.rukamori.archivetune.ui.screens.rememberScreenHeaderHaze
import moe.rukamori.archivetune.ui.player.LocalPlayerLyricsFullScreen
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.OnlineSearchSort
import android.os.Build

/**
 * Shared bottom-chrome state for the online search-results screens (and the main
 * settings page): a haze source for the transparent blurred top plus the layer
 * backdrop that feeds the liquid-glass pills. Same gating as
 * [moe.rukamori.archivetune.ui.screens.rememberGlassScreenHeader].
 */
@Stable
class SearchResultsBarState(
    val liquidGlassActive: Boolean,
    val backdrop: Backdrop?,
    val haze: HazeState,
)

@Composable
fun rememberSearchResultsBarState(): SearchResultsBarState {
    val liquidGlassEnabled by rememberPreference(LiquidGlassEnabledKey, defaultValue = false)
    val lyricsFullScreen = LocalPlayerLyricsFullScreen.current
    val surfaceColor = MaterialTheme.colorScheme.surface

    // Throttled recorder: the results list redraws on every scroll frame, and
    // re-recording it into the glass layer per frame (plus re-running every
    // pill's blur shader) is what made this page lag while the glass mini
    // player was on screen. 10 Hz is visually identical behind an 18dp blur.
    val backdrop = rememberThrottledBackdrop(surfaceColor)
    val haze = rememberScreenHeaderHaze()
    val active =
        liquidGlassEnabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !lyricsFullScreen
    return SearchResultsBarState(
        liquidGlassActive = active,
        backdrop = if (active) backdrop else null,
        haze = haze,
    )
}

/** Tags the scrolling content as the source for both the top haze and the glass pills. */
fun Modifier.searchResultsBarSource(state: SearchResultsBarState): Modifier =
    this
        .then(
            when (val backdrop = state.backdrop) {
                null -> Modifier
                else -> Modifier.glassSource(backdrop)
            }
        )
        .hazeSource(state.haze)

/**
 * Adapts an existing [moe.rukamori.archivetune.ui.screens.GlassScreenHeader] (used by the
 * settings pages) into a [SearchResultsBarState] so the shared bottom overlay can reuse
 * the same backdrop/haze sources without double-tagging the content.
 */
fun moe.rukamori.archivetune.ui.screens.GlassScreenHeader.toSearchResultsBarState(): SearchResultsBarState =
    SearchResultsBarState(
        liquidGlassActive = liquidGlassActive,
        backdrop = backdrop,
        haze = haze,
    )

/** Extra bottom content padding so list items clear the bottom overlay. */
val SearchResultsOverlayReserve: Dp = 148.dp

/**
 * The bottom overlay of the search-results screens:
 *
 *  [ category pills (glass/blur) ]
 *  [ back pill ] [ search pill    ]
 *
 * The back button is its own liquid-glass pill on the same line as the search
 * pill; the category pills sit above it. With liquid glass unavailable
 * (SDK < S or toggle off) both fall back to plain tonal pills.
 */
@Composable
fun BoxScope.SearchResultsBottomOverlay(
    state: SearchResultsBarState,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onBackLongClick: () -> Unit = {},
    placeholder: String = "",
    bottomPadding: Dp = 0.dp,
    lazyListState: LazyListState? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    chipsRow: (@Composable () -> Unit)? = null,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    var fieldFocused by rememberSaveable { mutableStateOf(false) }

    // "Hide search bar and category pills while scrolling" (Appearance): the
    // whole chrome slides away while the list is actively scrolling below the
    // top and slides back the moment the fling settles.
    val hideWhileScrolling by rememberPreference(HideSearchChromeWhileScrollingKey, defaultValue = false)
    val chromeHidden =
        hideWhileScrolling &&
            lazyListState != null &&
            lazyListState.isScrollInProgress &&
            lazyListState.canScrollBackward

    // While the keyboard is open it fully covers the mini player, so the
    // player-aware bottom reserve would only add dead space between the search
    // pill and the IME. Drop it for as long as the IME is visible.
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val effectiveBottomPadding = (if (imeVisible) 0.dp else bottomPadding) + 10.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
                .padding(start = 12.dp, end = 12.dp, bottom = effectiveBottomPadding),
    ) {
        AnimatedVisibility(
            visible = !chromeHidden,
            enter = fadeIn(tween(200)) + slideInVertically(tween(240)) { it / 2 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(200)) { it / 2 },
        ) {
            Column {
                if (chipsRow != null) {
                    AnimatedVisibility(
                        visible = !fieldFocused,
                        enter = fadeIn(tween(160)) + expandVerticallySoft(),
                        exit = fadeOut(tween(120)) + shrinkVerticallySoft(),
                    ) {
                        Column {
                            chipsRow()
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SearchBackPill(
                        state = state,
                        onBack = onBack,
                        onBackLongClick = onBackLongClick,
                    )

                    SearchInputPill(
                        state = state,
                        query = query,
                        onQueryChange = onQueryChange,
                        onSearch = { text ->
                            onSearch(text)
                            keyboardController?.hide()
                        },
                        placeholder = placeholder,
                        onFocusChanged = { fieldFocused = it },
                        trailing = trailing,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun expandVerticallySoft() =
    expandVertically(
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
    )

private fun shrinkVerticallySoft() =
    shrinkVertically(
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
    )

@Composable
private fun SearchBackPill(
    state: SearchResultsBarState,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val backdrop = state.backdrop
    if (backdrop != null) {
        LiquidGlassActionPill(
            backdrop = backdrop,
            interactive = true,
        ) {
            AppIconButton(
                onClick = onBack,
                onLongClick = onBackLongClick,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = stringResource(R.string.back_button_desc),
                    tint = liquidGlassContentColor(),
                )
            }
        }
    } else {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.size(48.dp),
        ) {
            AppIconButton(
                onClick = onBack,
                onLongClick = onBackLongClick,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = stringResource(R.string.back_button_desc),
                )
            }
        }
    }
}

@Composable
private fun SearchInputPill(
    state: SearchResultsBarState,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    placeholder: String,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val backdrop = state.backdrop
    val contentColor =
        if (backdrop != null) liquidGlassContentColor() else MaterialTheme.colorScheme.onSurface
    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant

    val pillShape = RoundedCornerShape(24.dp)
    val baseModifier =
        if (backdrop != null) {
            Modifier.liquidGlass(
                backdrop = backdrop,
                shape = pillShape,
                interactive = true,
            )
        } else {
            Modifier.background(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = pillShape,
            )
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .height(48.dp)
                .then(baseModifier)
                .padding(start = 6.dp, end = 2.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.search),
            contentDescription = null,
            tint = placeholderColor,
            modifier =
                Modifier
                    .padding(start = 10.dp)
                    .size(22.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle =
                MaterialTheme.typography.titleMedium
                    .copy(color = contentColor),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions =
                KeyboardActions(
                    onSearch = {
                        if (query.isNotBlank()) onSearch(query)
                    },
                ),
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .onFocusChanged { onFocusChanged(it.isFocused) },
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.titleMedium,
                            color = placeholderColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (trailing != null) {
            trailing()
        }
    }
}

/**
 * Horizontally scrollable category pills rendered above the search row. Every pill
 * is a frosted glass chip (blur of the content scrolling behind it) with a plain
 * tonal fallback, mirroring the search/back pill styling.
 */
@Composable
fun <E> GlassFilterChipsRow(
    state: SearchResultsBarState,
    chips: List<Pair<E, String>>,
    currentValue: E,
    onValueUpdate: (E) -> Unit,
    icons: Map<E, Int> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.width(2.dp))
        chips.forEach { (value, label) ->
            GlassFilterChip(
                state = state,
                label = label,
                iconRes = icons[value],
                selected = currentValue == value,
                onClick = { onValueUpdate(value) },
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun GlassFilterChip(
    state: SearchResultsBarState,
    label: String,
    iconRes: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val backdrop = state.backdrop
    val shape = RoundedCornerShape(20.dp)

    val container =
        if (backdrop != null) {
            Modifier.liquidGlass(
                backdrop = backdrop,
                shape = shape,
                interactive = true,
                baseColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
                    } else {
                        Color.Unspecified
                    },
            )
        } else {
            Modifier.background(
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                shape = shape,
            )
        }

    val contentColor =
        if (selected) {
            MaterialTheme.colorScheme.primary
        } else if (backdrop != null) {
            liquidGlassContentColor()
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        modifier =
            Modifier
                .padding(vertical = 2.dp)
                .then(container)
                .clickable(onClick = onClick)
                .padding(start = 14.dp, end = 18.dp, top = 10.dp, bottom = 10.dp)
                .height(22.dp),
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/** Compact sort menu shown inside the search pill's trailing slot. */
@Composable
fun SearchResultsSortMenu(
    selectedSort: OnlineSearchSort,
    onSortSelected: (OnlineSearchSort) -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(R.drawable.filter_alt),
                contentDescription = null,
                tint = tint,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            listOf(OnlineSearchSort.DEFAULT, OnlineSearchSort.VIEWS).forEach { sort ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text =
                                stringResource(
                                    if (sort == OnlineSearchSort.DEFAULT) {
                                        R.string.default_style
                                    } else {
                                        R.string.views
                                    },
                                ),
                        )
                    },
                    onClick = {
                        expanded = false
                        onSortSelected(sort)
                    },
                    leadingIcon = {
                        if (sort == selectedSort) {
                            Icon(
                                painter = painterResource(R.drawable.done),
                                contentDescription = null,
                            )
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                )
            }
        }
    }
}
