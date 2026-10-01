/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.search

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.ui.component.liquidGlassContentColor
import moe.rukamori.archivetune.ui.component.liquidGlass
import moe.rukamori.archivetune.ui.component.glassSource
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.onSizeChanged
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
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
import moe.rukamori.archivetune.viewmodels.SearchDiscoveryViewModel
import moe.rukamori.archivetune.viewmodels.SearchHistoryViewModel
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

private val SearchHorizontalPadding = 24.dp
private val SearchCardCornerRadius = 18.dp

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    navController: NavController,
    onSearchQuery: (String) -> Unit,
    onVoiceSearch: () -> Unit = {},
    headerScrollConnection: NestedScrollConnection? = null,
    listState: LazyListState? = null,
    viewModel: SearchDiscoveryViewModel = hiltViewModel(),
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
    LaunchedEffect(Unit) {
        delay(250)
        runCatching { focusRequester.requestFocus() }
    }

    val barState = rememberSearchResultsBarState()

    var chromeHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val chromeReserve = with(density) { chromeHeightPx.toDp() }

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

        Column(
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

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .imePadding(),
            ) {
                val listBottomPadding =
                    LocalPlayerAwareWindowInsets.current
                        .only(WindowInsetsSides.Bottom)
                        .asPaddingValues()
                        .calculateBottomPadding() + 16.dp
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(top = chromeReserve)
                            .verticalScroll(rememberScrollState()),
                    contentPadding = PaddingValues(bottom = listBottomPadding),
                ) {
                if (recentSearches.isEmpty()) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 40.dp),
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
                                    top = 12.dp,
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
                                    .clickable(onClick = historyViewModel::clearAll)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }

                    recentSearches.forEachIndexed { index, item ->
                        RecentSearchRow(
                            history = item,
                            onDelete = historyViewModel::delete,
                            onClick = { onSearchQuery(item.query) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = SearchHorizontalPadding),
                        )
                        if (index < recentSearches.lastIndex) {

                            HorizontalDivider(
                                modifier =
                                    Modifier.padding(
                                        start = SearchHorizontalPadding + 58.dp,
                                        end = SearchHorizontalPadding,
                                    ),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
            }
        }

        SearchTabTopChrome(
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
            onChromeSizeChanged = { chromeHeightPx = it },
        )
    }
}

@Composable
private fun SearchTabTopChrome(
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
    onChromeSizeChanged: (Int) -> Unit = {},
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val backdrop = barState.backdrop
    val glassContentColor = if (backdrop != null) liquidGlassContentColor() else MaterialTheme.colorScheme.onSurface

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .onSizeChanged { onChromeSizeChanged(it.height) },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 10.dp,
                        bottom = 10.dp,
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
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun RecentSearchRow(
    history: SearchHistory,
    onDelete: (SearchHistory) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {

    val dismissState =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { it == SwipeToDismissBoxValue.EndToStart },
            positionalThreshold = { distance -> distance * 0.5f },
        )

    var processedDismiss by remember(history.id) { mutableStateOf(false) }
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart && !processedDismiss) {
            processedDismiss = true
            onDelete(history)
        }
        if (dismissState.currentValue == SwipeToDismissBoxValue.Settled) {
            processedDismiss = false
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val onError = MaterialTheme.colorScheme.error
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(onError.copy(alpha = 0.18f)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    painter = painterResource(R.drawable.delete),
                    contentDescription = null,
                    tint = onError,
                    modifier =
                        Modifier
                            .padding(end = 20.dp)
                            .size(22.dp),
                )
            }
        },
        enableDismissFromStartToEnd = false,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier =
                Modifier
                    .fillMaxWidth()

                    .combinedClickable(onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            RecentSearchMonogram(query = history.query)
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
}

@Composable
private fun RecentSearchMonogram(query: String) {
    val initial = remember(query) {
        query.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "·"
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Icon(
            painter = painterResource(R.drawable.search),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.size(26.dp),
        )

        Text(
            text = initial,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

