/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)

package moe.rukamori.archivetune.ui.screens.settings

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.component.FrostedHeaderPill
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.screens.ScreenHeaderHaze
import moe.rukamori.archivetune.ui.screens.rememberScreenHeaderHaze
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import dev.chrisbanes.haze.hazeSource
import androidx.compose.runtime.saveable.rememberSaveable
import moe.rukamori.archivetune.viewmodels.AppIconSortOrder
import moe.rukamori.archivetune.viewmodels.AppIconUiModel
import moe.rukamori.archivetune.viewmodels.IconScreenEffect
import moe.rukamori.archivetune.viewmodels.IconScreenState
import moe.rukamori.archivetune.viewmodels.IconScreenUiModel
import moe.rukamori.archivetune.viewmodels.IconPackDownloadUi
import moe.rukamori.archivetune.viewmodels.IconViewModel
import java.io.File
import androidx.compose.material3.IconButton as MaterialIconButton
import androidx.compose.foundation.layout.asPaddingValues

@Composable
fun IconScreen(
    navController: NavController,
    viewModel: IconViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val uriHandler = LocalUriHandler.current
    val selectedTaskIconResId =
        (state as? IconScreenState.Success)
            ?.model
            ?.selectedIcon
            ?.previewDrawableResId

    LaunchedEffect(activity, selectedTaskIconResId) {
        if (activity != null && selectedTaskIconResId != null) {
            activity.updateTaskIcon(selectedTaskIconResId)
        }
    }
    LaunchedEffect(viewModel, uriHandler) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is IconScreenEffect.OpenUri -> uriHandler.openUri(effect.uri)
            }
        }
    }
    val navigateUp: () -> Unit =
        remember(navController) {
            {
                navController.navigateUp()
                Unit
            }
        }
    val navigateHome: () -> Unit =
        remember(navController) {
            {
                navController.backToMain()
            }
        }
    val retry = remember(viewModel) { { viewModel.retry() } }
    val selectIcon =
        remember(viewModel) {
            { iconId: String -> viewModel.selectIcon(iconId) }
        }
    val openAuthorProfile =
        remember(viewModel) {
            { iconId: String -> viewModel.openAuthorProfile(iconId) }
        }
    val updateSearchQuery =
        remember(viewModel) {
            { query: String -> viewModel.updateSearchQuery(query) }
        }
    val showSortMenu = remember(viewModel) { { viewModel.showSortMenu() } }
    val dismissSortMenu = remember(viewModel) { { viewModel.dismissSortMenu() } }
    val updateSortOrder =
        remember(viewModel) {
            { order: AppIconSortOrder -> viewModel.updateSortOrder(order) }
        }
    val downloadPack = remember(viewModel) { { viewModel.downloadPack() } }

    IconScreenContent(
        state = state,
        onNavigateUp = navigateUp,
        onNavigateHome = navigateHome,
        onRetry = retry,
        onSelectIcon = selectIcon,
        onOpenAuthorProfile = openAuthorProfile,
        onSearchQueryChange = updateSearchQuery,
        onShowSortMenu = showSortMenu,
        onDismissSortMenu = dismissSortMenu,
        onSortOrderChange = updateSortOrder,
        onDownloadPack = downloadPack,
    )
}

@Composable
private fun IconScreenContent(
    state: IconScreenState,
    onNavigateUp: () -> Unit,
    onNavigateHome: () -> Unit,
    onRetry: () -> Unit,
    onSelectIcon: (String) -> Unit,
    onOpenAuthorProfile: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onShowSortMenu: () -> Unit,
    onDismissSortMenu: () -> Unit,
    onSortOrderChange: (AppIconSortOrder) -> Unit,
    onDownloadPack: () -> Unit,
) {

    val headerHaze = rememberScreenHeaderHaze()
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {

            TopAppBar(
                title = {},
                navigationIcon = {
                    FrostedHeaderPill(plain = true) {
                        IconButton(
                            onClick = onNavigateUp,
                            onLongClick = onNavigateHome,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.arrow_back),
                                contentDescription = stringResource(R.string.back_button_desc),
                            )
                        }
                        Text(
                            text = stringResource(R.string.app_icon),
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
            )
        },
    ) { innerPadding ->
        val playerAwareBottomPadding =
            LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .hazeSource(headerHaze),
            ) {
        when (state) {
            IconScreenState.Loading -> {
                IconScreenLoading(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .playerAwareInsets(),
                )
            }

            IconScreenState.Empty -> {
                IconScreenMessage(
                    message = stringResource(R.string.app_icon_empty),
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .playerAwareInsets(),
                )
            }

            is IconScreenState.Error -> {
                IconScreenMessage(
                    message = stringResource(state.messageResId),
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .playerAwareInsets(),
                    action = onRetry,
                )
            }

            is IconScreenState.Success -> {
                // "Ask me if I want to download the Icon Pack" — once per
                // not-yet-downloaded pack, every time the screen is opened.
                if (state.model.packDownload == IconPackDownloadUi.NEEDED) {
                    var promptDismissed by rememberSaveable { mutableStateOf(false) }
                    if (!promptDismissed) {
                        IconPackDownloadDialog(
                            onDownload = {
                                promptDismissed = true
                                onDownloadPack()
                            },
                            onDismiss = { promptDismissed = true },
                        )
                    }
                }
                AppIconList(
                    model = state.model,
                    contentPadding =
                        PaddingValues(
                            start = SettingsDimensions.ScreenHorizontalPadding,
                            top = innerPadding.calculateTopPadding() + 8.dp,
                            end = SettingsDimensions.ScreenHorizontalPadding,
                            bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding,
                        ),
                    onSelectIcon = onSelectIcon,
                    onOpenAuthorProfile = onOpenAuthorProfile,
                    onSearchQueryChange = onSearchQueryChange,
                    onShowSortMenu = onShowSortMenu,
                    onDismissSortMenu = onDismissSortMenu,
                    onSortOrderChange = onSortOrderChange,
                    onDownloadPack = onDownloadPack,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .playerAwareInsets(),
                )
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
private fun AppIconList(
    model: IconScreenUiModel,
    contentPadding: PaddingValues,
    onSelectIcon: (String) -> Unit,
    onOpenAuthorProfile: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onShowSortMenu: () -> Unit,
    onDismissSortMenu: () -> Unit,
    onSortOrderChange: (AppIconSortOrder) -> Unit,
    onDownloadPack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.selectableGroup(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (model.packDownload != IconPackDownloadUi.NOT_NEEDED) {
            item(
                key = PackDownloadContentKey,
                contentType = PackDownloadContentType,
            ) {
                IconPackDownloadRow(
                    state = model.packDownload,
                    percent = model.packDownloadPercent,
                    indeterminate = model.packDownloadIndeterminate,
                    error = model.packDownloadError,
                    onDownload = onDownloadPack,
                    modifier =
                        Modifier
                            .widthIn(max = IconListMaxWidth)
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                )
            }
        }
        item(
            key = CurrentIconContentKey,
            contentType = CurrentIconContentType,
        ) {
            CurrentIconCard(
                icon = model.selectedIcon,
                modifier =
                    Modifier
                        .widthIn(max = IconListMaxWidth)
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
            )
        }
        item(
            key = IconSearchContentKey,
            contentType = IconSearchContentType,
        ) {
            AppIconSearchBar(
                query = model.searchQuery,
                sortOrder = model.sortOrder,
                isSortMenuExpanded = model.isSortMenuExpanded,
                onQueryChange = onSearchQueryChange,
                onShowSortMenu = onShowSortMenu,
                onDismissSortMenu = onDismissSortMenu,
                onSortOrderChange = onSortOrderChange,
                modifier =
                    Modifier
                        .widthIn(max = IconListMaxWidth)
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
            )
        }
        item(
            key = IconSectionHeaderKey,
            contentType = IconSectionHeaderContentType,
        ) {
            IconListHeader(
                iconCount = model.icons.size,
                modifier =
                    Modifier
                        .widthIn(max = IconListMaxWidth)
                        .fillMaxWidth()
                        .padding(
                            start = 4.dp,
                            top = 4.dp,
                            end = 4.dp,
                            bottom = 10.dp,
                        ),
            )
        }
        items(
            count = model.icons.size,
            key = { index -> model.icons[index].id },
            contentType = { AppIconContentType },
        ) { index ->
            val icon = model.icons[index]
            AppIconRow(
                icon = icon,
                index = index,
                count = model.icons.size,
                enabled = model.selectionInProgressId == null,
                isApplying = model.selectionInProgressId == icon.id,
                onClick = onSelectIcon,
                onOpenAuthorProfile = onOpenAuthorProfile,
            )
        }
        if (model.hasCommunityIcons) {
            item(
                key = CommunityNoticeContentKey,
                contentType = CommunityNoticeContentType,
            ) {
                CommunityIconNotice(
                    modifier =
                        Modifier
                            .widthIn(max = IconListMaxWidth)
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                )
            }
        }
        if (model.iconsAreRuntime) {
            item(
                key = RuntimeIconsNoticeKey,
                contentType = RuntimeIconsNoticeContentType,
            ) {
                Text(
                    text = stringResource(R.string.app_icon_runtime_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .widthIn(max = IconListMaxWidth)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun IconPackDownloadRow(
    state: IconPackDownloadUi,
    percent: Int,
    indeterminate: Boolean,
    error: String?,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                        when (state) {
                            IconPackDownloadUi.DOWNLOADING ->
                                stringResource(R.string.icon_pack_downloading, percent)

                            IconPackDownloadUi.FAILED ->
                                stringResource(R.string.icon_pack_download_failed)

                            else -> stringResource(R.string.icon_pack_download_needed)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (state == IconPackDownloadUi.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                )
                if (state == IconPackDownloadUi.NEEDED || state == IconPackDownloadUi.FAILED) {
                    Text(
                        text = stringResource(R.string.icon_pack_download_needed_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state == IconPackDownloadUi.FAILED && !error.isNullOrBlank()) {
                    // The concrete installer failure (HTTP code, digest
                    // mismatch, timeout, …) straight from
                    // IconPackRuntimeManager.lastInstallFailure() — the row
                    // used to say only "failed" with no way to see why.
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (state == IconPackDownloadUi.DOWNLOADING) {
                if (indeterminate) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    CircularProgressIndicator(
                        progress = { percent.coerceIn(0, 100) / 100f },
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                FilledTonalButton(
                    onClick = onDownload,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.download))
                }
            }
        }
    }
}

@Composable
private fun IconPackDownloadDialog(
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.icon_pack_download_title)) },
        text = { Text(stringResource(R.string.icon_pack_download_desc)) },
        confirmButton = {
            TextButton(onClick = onDownload, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.not_now))
            }
        },
    )
}

@Composable
private fun AppIconSearchBar(
    query: String,
    sortOrder: AppIconSortOrder,
    isSortMenuExpanded: Boolean,
    onQueryChange: (String) -> Unit,
    onShowSortMenu: () -> Unit,
    onDismissSortMenu: () -> Unit,
    onSortOrderChange: (AppIconSortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val submitSearch =
        remember(keyboardController) {
            { _: String ->
                keyboardController?.hide()
                Unit
            }
        }
    val clearSearch = remember(onQueryChange) { { onQueryChange("") } }

    SearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = submitSearch,
                expanded = false,
                onExpandedChange = {},
                placeholder = {
                    Text(text = stringResource(R.string.search))
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.search),
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (query.isNotEmpty()) {
                            MaterialIconButton(onClick = clearSearch) {
                                Icon(
                                    painter = painterResource(R.drawable.close),
                                    contentDescription = stringResource(R.string.clear),
                                )
                            }
                        }
                        Box {
                            MaterialIconButton(onClick = onShowSortMenu) {
                                Icon(
                                    painter = painterResource(R.drawable.filter_alt),
                                    contentDescription = null,
                                )
                            }
                            DropdownMenu(
                                expanded = isSortMenuExpanded,
                                onDismissRequest = onDismissSortMenu,
                                modifier = Modifier.widthIn(min = 184.dp),
                            ) {
                                AppIconSortOrder.entries.forEach { order ->
                                    val selectOrder =
                                        remember(order, onSortOrderChange) {
                                            { onSortOrderChange(order) }
                                        }
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text =
                                                    stringResource(
                                                        when (order) {
                                                            AppIconSortOrder.NEW_ADDED -> {
                                                                R.string.recently_added
                                                            }

                                                            AppIconSortOrder.ALPHABETICAL -> {
                                                                R.string.sort_a_to_z
                                                            }
                                                        },
                                                    ),
                                            )
                                        },
                                        trailingIcon = {
                                            Icon(
                                                painter =
                                                    painterResource(
                                                        if (sortOrder == order) {
                                                            R.drawable.radio_button_checked
                                                        } else {
                                                            R.drawable.radio_button_unchecked
                                                        },
                                                    ),
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = selectOrder,
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        expanded = false,
        onExpandedChange = {},
        modifier = modifier,
        windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
    ) {}
}

@Composable
private fun CurrentIconCard(
    icon: AppIconUiModel,
    modifier: Modifier = Modifier,
) {
    val name = appIconName(icon)

    ElevatedCard(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            AppIconPreview(
                icon = icon,
                size = 88.dp,
                imagePadding = if (icon.isDefault) 6.dp else 0.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_icon_current),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (icon.isDefault) {
                    Text(
                        text = stringResource(R.string.app_icon_builtin_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.app_icon_identifier, icon.id),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun IconListHeader(
    iconCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.app_icon_available),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = pluralStringResource(R.plurals.app_icon_count, iconCount, iconCount),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AppIconRow(
    icon: AppIconUiModel,
    index: Int,
    count: Int,
    enabled: Boolean,
    isApplying: Boolean,
    onClick: (String) -> Unit,
    onOpenAuthorProfile: (String) -> Unit,
) {
    val name = appIconName(icon)
    val author = icon.author
    val select = remember(icon.id, onClick) { { onClick(icon.id) } }
    val rowShapes = ListItemDefaults.segmentedShapes(index = index, count = count)
    val selectedBorderColor = MaterialTheme.colorScheme.primary
    val selectedBorderShape = rowShapes.selectedShape
    val selectedBorder =
        remember(icon.isSelected, selectedBorderColor, selectedBorderShape) {
            if (icon.isSelected) {
                Modifier.border(
                    width = 1.dp,
                    color = selectedBorderColor,
                    shape = selectedBorderShape,
                )
            } else {
                Modifier
            }
        }

    SegmentedListItem(
        selected = icon.isSelected,
        onClick = select,
        enabled = enabled,
        shapes = rowShapes,
        modifier =
            Modifier
                .widthIn(max = IconListMaxWidth)
                .fillMaxWidth()
                .heightIn(min = 104.dp)
                .then(selectedBorder),
        colors =
            ListItemDefaults.segmentedColors(
                containerColor =
                    if (icon.isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
            ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        leadingContent = {
            AppIconPreview(
                icon = icon,
                size = 72.dp,
                imagePadding = if (icon.isDefault) 4.dp else 0.dp,
            )
        },
        overlineContent = {
            IconTypeBadge(
                isDefault = icon.isDefault,
            )
        },
        supportingContent =
            {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (icon.isDefault) {
                        Text(
                            text = stringResource(R.string.app_icon_builtin_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.app_icon_identifier, icon.id),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    if (author != null) {
                        AuthorAttribution(
                            icon = icon,
                            onOpenAuthorProfile = onOpenAuthorProfile,
                        )
                    }
                }
            },
        trailingContent = {
            if (isApplying) {
                LoadingIndicator(modifier = Modifier.size(32.dp))
            } else {
                RadioButton(
                    selected = icon.isSelected,
                    onClick = null,
                    enabled = enabled,
                )
            }
        },
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun IconTypeBadge(
    isDefault: Boolean,
    modifier: Modifier = Modifier,
) {
    val containerColor =
        if (isDefault) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.tertiaryContainer
        }
    val contentColor =
        if (isDefault) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onTertiaryContainer
        }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painter =
                    painterResource(
                        if (isDefault) {
                            R.drawable.auto_awesome
                        } else {
                            R.drawable.person
                        },
                    ),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text =
                    stringResource(
                        if (isDefault) {
                            R.string.app_icon_builtin
                        } else {
                            R.string.app_icon_community
                        },
                    ),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun AuthorAttribution(
    icon: AppIconUiModel,
    onOpenAuthorProfile: (String) -> Unit,
) {
    val author = icon.author ?: return
    val githubAuthorUrl = icon.githubAuthorUrl
    if (githubAuthorUrl != null) {
        val openProfile =
            remember(icon.id, onOpenAuthorProfile) {
                { onOpenAuthorProfile(icon.id) }
            }
        val linkedAuthor =
            remember(author, githubAuthorUrl, openProfile) {
                buildAnnotatedString {
                    withLink(
                        LinkAnnotation.Clickable(githubAuthorUrl) { openProfile() },
                    ) {
                        append(author)
                    }
                }
            }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.github),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = linkedAuthor,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Text(
            text = author,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CommunityIconNotice(modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.primary,
                border =
                    BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.auto_awesome),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.app_icon_community_notice),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppIconPreview(
    icon: AppIconUiModel,
    size: Dp,
    imagePadding: Dp,
) {
    Surface(
        modifier = Modifier.size(size),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 1.dp,
    ) {
        val model: Any =
            icon.previewFilePath?.let { path -> File(path) }
                ?: icon.previewDrawableResId
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(imagePadding),
        )
    }
}

@Composable
private fun appIconName(icon: AppIconUiModel): String =
    icon.nameResId?.let { nameResId ->
        stringResource(nameResId)
    } ?: icon.name.orEmpty()

@Composable
private fun IconScreenLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        LoadingIndicator(modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun IconScreenMessage(
    message: String,
    modifier: Modifier = Modifier,
    action: (() -> Unit)? = null,
) {
    Column(
        modifier =
            modifier.padding(
                horizontal = SettingsDimensions.ScreenHorizontalPadding,
                vertical = 24.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) {
            FilledTonalButton(
                onClick = action,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(text = stringResource(R.string.retry))
            }
        }
    }
}

@Composable
private fun Modifier.playerAwareInsets(): Modifier =
    windowInsetsPadding(
        LocalPlayerAwareWindowInsets.current.only(
            WindowInsetsSides.Horizontal,
        ),
    )

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@Suppress("DEPRECATION")
private suspend fun Activity.updateTaskIcon(
    @DrawableRes drawableResId: Int,
) {
    val description =
        withContext(Dispatchers.Default) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityManager.TaskDescription
                    .Builder()
                    .setIcon(drawableResId)
                    .build()
            } else {
                val iconSize =
                    getSystemService(ActivityManager::class.java)
                        ?.launcherLargeIconSize
                        ?.coerceIn(1, MaximumLegacyTaskIconSize)
                        ?: DefaultLegacyTaskIconSize
                val drawable = getDrawable(drawableResId) ?: return@withContext null
                val bitmap =
                    Bitmap.createBitmap(
                        iconSize,
                        iconSize,
                        Bitmap.Config.ARGB_8888,
                    )
                drawable.setBounds(0, 0, iconSize, iconSize)
                drawable.draw(Canvas(bitmap))
                ActivityManager.TaskDescription(null, bitmap)
            }
        } ?: return
    setTaskDescription(description)
}

private const val AppIconContentType = "app_icon"
private const val CurrentIconContentKey = "current_icon"
private const val CurrentIconContentType = "current_icon_summary"
private const val IconSearchContentKey = "icon_search"
private const val IconSearchContentType = "icon_search"
private const val IconSectionHeaderKey = "icon_section_header"
private const val IconSectionHeaderContentType = "icon_section_header"
private const val RuntimeIconsNoticeKey = "runtime_icons_notice"
private const val RuntimeIconsNoticeContentType = "runtime_icons_notice"
private const val PackDownloadContentKey = "icon_pack_download"
private const val PackDownloadContentType = "icon_pack_download"
private const val CommunityNoticeContentKey = "community_icon_notice"
private const val CommunityNoticeContentType = "community_icon_notice"
private const val DefaultLegacyTaskIconSize = 192
private const val MaximumLegacyTaskIconSize = 512
private val IconListMaxWidth = 720.dp
