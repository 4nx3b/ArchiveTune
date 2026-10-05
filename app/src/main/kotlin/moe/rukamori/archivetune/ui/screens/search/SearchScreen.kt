/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalFoundationApi::class)

package moe.rukamori.archivetune.ui.screens.search

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.ui.component.liquidGlassContentColor
import moe.rukamori.archivetune.ui.component.liquidGlass
import moe.rukamori.archivetune.ui.component.glassSource
import kotlinx.coroutines.delay
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.hilt.navigation.compose.hiltViewModel
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.DefaultSearchSourceKey
import moe.rukamori.archivetune.constants.DisableBlurKey
import moe.rukamori.archivetune.constants.SearchProvider
import moe.rukamori.archivetune.constants.SearchSource
import moe.rukamori.archivetune.db.entities.SearchHistory
import moe.rukamori.archivetune.ui.component.SearchSourcePicker
import moe.rukamori.archivetune.ui.screens.HomeAtmosphereBackground
import moe.rukamori.archivetune.ui.screens.LocalSearchHazeState
import dev.chrisbanes.haze.hazeSource
import moe.rukamori.archivetune.viewmodels.SearchHistoryViewModel
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

private val SearchHorizontalPadding = 24.dp
private const val RecentsHeightFraction = 0.55f

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    navController: NavController,
    onSearchQuery: (String) -> Unit,
    onVoiceSearch: () -> Unit = {},
    headerScrollConnection: NestedScrollConnection? = null,
    historyViewModel: SearchHistoryViewModel = hiltViewModel(),
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchProvider by rememberEnumPreference(DefaultSearchSourceKey, SearchProvider.YOUTUBE)

    val onSearchSourceSelection: (SearchSource, SearchProvider) -> Unit = { _, provider ->
        searchProvider = provider
    }
    val recentSearches by historyViewModel.recentSearches.collectAsStateWithLifecycle()
    val searchHazeState = LocalSearchHazeState.current
    val (disableBlur) = rememberPreference(DisableBlurKey, false)

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        var attempt = 0
        while (attempt < 3) {
            delay(if (attempt == 0) 120L else 220L)
            val focused = runCatching { focusRequester.requestFocus() }.isSuccess
            if (focused) break
            attempt++
        }
        keyboardController?.show()
    }

    val barState = rememberSearchResultsBarState()

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .let { m -> if (searchHazeState != null) m.hazeSource(searchHazeState) else m }
                .then(
                    if (headerScrollConnection != null) {
                        Modifier.nestedScroll(headerScrollConnection)
                    } else {
                        Modifier
                    },
                ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .let { m ->
                        if (barState.backdrop != null) m.glassSource(barState.backdrop!!) else m
                    },
        ) {
            if (!disableBlur) {
                HomeAtmosphereBackground()
            }
        }

        BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(
                            WindowInsets.ime.union(
                                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom),
                            ),
                        ),
            ) {
                val recentsMaxHeight = maxHeight * RecentsHeightFraction

                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RecentSearchesPanel(
                        recentSearches = recentSearches,
                        maxHeight = recentsMaxHeight,
                        onClearAll = historyViewModel::clearAll,
                        onPick = onSearchQuery,
                    )

                    Spacer(Modifier.height(10.dp))

                    SearchTabBottomChrome(
                        barState = barState,
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = {
                            onSearchQuery(it)
                        },
                        onVoiceSearch = onVoiceSearch,
                        onBack = navController::navigateUp,
                        onBackLongClick = navController::backToMain,
                        focusRequester = focusRequester,
                        searchProvider = searchProvider,
                        onSourceSelection = onSearchSourceSelection,
                    )
                }
            }
    }
}

@Composable
private fun RecentSearchesPanel(
    recentSearches: List<SearchHistory>,
    maxHeight: androidx.compose.ui.unit.Dp,
    onClearAll: () -> Unit,
    onPick: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState()),
    ) {
        if (recentSearches.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painter = painterResource(R.drawable.search),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.search_no_recent),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = SearchHorizontalPadding,
                            end = SearchHorizontalPadding,
                            top = 10.dp,
                            bottom = 6.dp,
                        ),
            ) {
                Text(
                    text = stringResource(R.string.search_recent_searches),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.clear),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onClearAll)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            recentSearches.forEachIndexed { index, item ->
                RecentSearchRow(
                    history = item,
                    onClick = { onPick(item.query) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SearchHorizontalPadding),
                )
                if (index < recentSearches.lastIndex) {
                    HorizontalDivider(
                        modifier =
                            Modifier.padding(
                                start = SearchHorizontalPadding,
                                end = SearchHorizontalPadding,
                            ),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchTabBottomChrome(
    barState: SearchResultsBarState,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onVoiceSearch: () -> Unit,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
    focusRequester: FocusRequester,
    searchProvider: SearchProvider,
    onSourceSelection: (SearchSource, SearchProvider) -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val backdrop = barState.backdrop
    val glassContentColor = if (backdrop != null) liquidGlassContentColor() else MaterialTheme.colorScheme.onSurface

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                ),
    ) {
        val backShape = CircleShape
        val backModifier =
            if (backdrop != null) {
                Modifier.liquidGlass(
                    backdrop = backdrop,
                    shape = backShape,
                    interactive = true,
                )
            } else {
                Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, backShape)
            }
        Box(
            modifier =
                backModifier
                    .size(48.dp)
                    .combinedClickable(
                        onClick = onBack,
                        onLongClick = onBackLongClick,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.arrow_back),
                contentDescription = stringResource(R.string.back_button_desc),
                tint = glassContentColor,
                modifier = Modifier.size(22.dp),
            )
        }

        val pillShape = RoundedCornerShape(24.dp)
        val pillModifier =
            if (backdrop != null) {
                Modifier.liquidGlass(
                    backdrop = backdrop,
                    shape = pillShape,
                    interactive = true,
                )
            } else {
                Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, pillShape)
            }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .weight(1f)
                    .height(52.dp)
                    .then(pillModifier)
                    .padding(start = 6.dp, end = 2.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.search),
                contentDescription = null,
                tint = glassContentColor.copy(alpha = 0.72f),
                modifier =
                    Modifier
                        .padding(start = 12.dp)
                        .size(22.dp),
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle =
                    MaterialTheme.typography.titleMedium
                        .copy(fontSize = 16.sp)
                        .copy(color = glassContentColor),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions =
                    KeyboardActions(
                        onSearch = {
                            if (query.isNotEmpty()) {
                                onSearch(query)
                                keyboardController?.hide()
                            }
                        },
                    ),
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                        .focusRequester(focusRequester),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                text =
                                    stringResource(
                                        if (searchProvider == SearchProvider.SPOTIFY) {
                                            R.string.search_source_spotify
                                        } else if (searchProvider == SearchProvider.APPLE_MUSIC) {
                                            R.string.search_source_apple_music
                                        } else {
                                            R.string.search_yt_music
                                        },
                                    ),
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                                color = glassContentColor.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            IconButton(
                onClick = onVoiceSearch,
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.mic),
                    contentDescription = stringResource(R.string.voice_search),
                    tint = glassContentColor.copy(alpha = 0.72f),
                    modifier = Modifier.size(22.dp),
                )
            }
            SearchSourcePicker(
                currentScope = SearchSource.ONLINE,
                currentProvider = searchProvider,
                onSelection = onSourceSelection,
                includeLocal = false,
            )
        }
    }
}

@Composable
private fun RecentSearchRow(
    history: SearchHistory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = history.query,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.search_recent_label),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painter = painterResource(R.drawable.arrow_forward),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

