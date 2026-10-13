/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlinx.coroutines.flow.onEach
import moe.rukamori.archivetune.ui.screens.settings.about.SukisuAboutBackground
import android.os.Build
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.component.FrostedHeaderPill
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.viewmodels.AboutContributorUiCollection
import moe.rukamori.archivetune.viewmodels.AboutContributorsUiState
import moe.rukamori.archivetune.viewmodels.AboutDependencyLicenseUiCollection
import moe.rukamori.archivetune.viewmodels.AboutDependencyLicensesUiState
import moe.rukamori.archivetune.viewmodels.AboutDialog
import moe.rukamori.archivetune.viewmodels.AboutLinkCollection
import moe.rukamori.archivetune.viewmodels.AboutScreenEffect
import moe.rukamori.archivetune.viewmodels.AboutScreenState
import moe.rukamori.archivetune.viewmodels.AboutTranslationContributorUiCollection
import moe.rukamori.archivetune.viewmodels.AboutTranslationContributorsUiState
import moe.rukamori.archivetune.viewmodels.AboutUiModel
import moe.rukamori.archivetune.viewmodels.AboutViewModel
import moe.rukamori.archivetune.viewmodels.TeamMember
import moe.rukamori.archivetune.viewmodels.TeamMemberCollection
import androidx.compose.foundation.layout.asPaddingValues
import moe.rukamori.archivetune.ui.component.KeepStatusBarHiddenInDialog
import androidx.compose.runtime.getValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    navController: NavController,
    viewModel: AboutViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(viewModel, uriHandler) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is AboutScreenEffect.OpenUri -> uriHandler.openUri(effect.uri)
            }
        }
    }

    AboutScreenContent(
        state = state,
        onNavigateUp = navController::navigateUp,
        onNavigateHome = navController::backToMain,
        onOpenUri = viewModel::openUri,
        onRetryContributors = viewModel::retryContributors,
        onOpenTranslationContributors = viewModel::openTranslationContributors,
        onOpenDependencyLicenses = viewModel::openDependencyLicenses,
        onDismissDialog = viewModel::dismissDialog,
        onRetryTranslationContributors = viewModel::retryTranslationContributors,
        onRetryDependencyLicenses = viewModel::retryDependencyLicenses,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutScreenContent(
    state: AboutScreenState,
    onNavigateUp: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenUri: (String) -> Unit,
    onRetryContributors: () -> Unit,
    onOpenTranslationContributors: () -> Unit,
    onOpenDependencyLicenses: () -> Unit,
    onDismissDialog: () -> Unit,
    onRetryTranslationContributors: () -> Unit,
    onRetryDependencyLicenses: () -> Unit,
) {
    val listState = rememberLazyListState()
    var logoHeightPx by remember { mutableIntStateOf(0) }
    val scrollProgress by remember {
        derivedStateOf {
            if (logoHeightPx <= 0) {
                0f
            } else {
                val index = listState.firstVisibleItemIndex
                val offset = listState.firstVisibleItemScrollOffset
                if (index > 0) 1f else (offset.toFloat() / logoHeightPx).coerceIn(0f, 1f)
            }
        }
    }

    val playerAwareBottomPadding =
        LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Bottom)
            .asPaddingValues()
            .calculateBottomPadding()
    val playerAwareInsets =
        LocalPlayerAwareWindowInsets.current.only(
            WindowInsetsSides.Horizontal,
        )

    val aboutGlassBackdrop = rememberLayerBackdrop()
    val aboutBarBackdrop = rememberLayerBackdrop()
    val glassCapable =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            isRuntimeShaderSupported()

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(playerAwareInsets),
    ) {
        SukisuAboutBackground(
            modifier = Modifier.fillMaxSize(),
            bgModifier =
                if (glassCapable) {
                    Modifier.kyantLayerBackdrop(aboutGlassBackdrop)
                } else {
                    Modifier
                },
            alpha = { 1f - scrollProgress },
            surface = MaterialTheme.colorScheme.surface,
        ) { }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .height(AboutTopBarHeight),
                ) {
                    if (glassCapable && scrollProgress > 0.99f) {
                        Box(
                            modifier =
                                Modifier
                                    .matchParentSize()
                                    .drawBackdrop(
                                        backdrop = aboutBarBackdrop,
                                        shape = { RectangleShape },
                                        effects = { blur(28f) },
                                    ),
                        )
                    }
                    Row(
                        modifier =
                            Modifier
                                .matchParentSize()
                                .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                        }
                    }
                    Text(
                        text = stringResource(R.string.about),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        color =
                            MaterialTheme.colorScheme.onBackground.copy(
                                alpha = ((scrollProgress - 0.35f) / 0.65f).coerceIn(0f, 1f),
                            ),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            },
        ) { innerPadding ->
            Box(
                modifier =
                    if (glassCapable) {
                        Modifier
                            .fillMaxSize()
                            .kyantLayerBackdrop(aboutBarBackdrop)
                    } else {
                        Modifier.fillMaxSize()
                    },
            ) {
                when (state) {
                    AboutScreenState.Loading -> {
                        AboutLoadingContent(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding),
                        )
                    }

                    AboutScreenState.Empty -> {
                        AboutMessageContent(
                            message = stringResource(R.string.no_results_found),
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding),
                        )
                    }

                    is AboutScreenState.Error -> {
                        AboutMessageContent(
                            message = stringResource(state.messageResId),
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding),
                        )
                    }

                    is AboutScreenState.Success -> {
                        AboutSuccessContent(
                            model = state.model,
                            onOpenUri = onOpenUri,
                            onRetryContributors = onRetryContributors,
                            onOpenTranslationContributors = onOpenTranslationContributors,
                            onOpenDependencyLicenses = onOpenDependencyLicenses,
                            glassBackdrop = if (glassCapable) aboutGlassBackdrop else null,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding =
                                PaddingValues(
                                    top = innerPadding.calculateTopPadding() + AboutSpacing.xs,
                                    bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding,
                                ),
                            listState = listState,
                            onLogoHeightChanged = { logoHeightPx = it },
                        )
                    }
                }
            }
        }
    }

    if (state is AboutScreenState.Success) {
        AboutFullScreenDialogs(
            model = state.model,
            onDismiss = onDismissDialog,
            onRetryTranslationContributors = onRetryTranslationContributors,
            onRetryDependencyLicenses = onRetryDependencyLicenses,
        )
    }
}

@Composable
private fun AboutLoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        LoadingIndicator(modifier = Modifier.size(40.dp))
    }
}

@Composable
private fun AboutMessageContent(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(AboutSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AboutFullScreenDialogs(
    model: AboutUiModel,
    onDismiss: () -> Unit,
    onRetryTranslationContributors: () -> Unit,
    onRetryDependencyLicenses: () -> Unit,
) {
    when (model.activeDialog) {
        AboutDialog.NONE -> {
            Unit
        }

        AboutDialog.TRANSLATION_CONTRIBUTORS -> {
            AboutFullScreenDialog(
                title = stringResource(R.string.about_contributor_translation),
                onDismiss = onDismiss,
            ) { modifier ->
                TranslationContributorsDialogContent(
                    state = model.translationContributorsState,
                    onRetry = onRetryTranslationContributors,
                    modifier = modifier,
                )
            }
        }

        AboutDialog.DEPENDENCY_LICENSES -> {
            AboutFullScreenDialog(
                title = stringResource(R.string.about_license),
                onDismiss = onDismiss,
            ) { modifier ->
                DependencyLicensesDialogContent(
                    state = model.dependencyLicensesState,
                    onRetry = onRetryDependencyLicenses,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun AboutFullScreenDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        KeepStatusBarHiddenInDialog()
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = title,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        androidx.compose.material3.IconButton(onClick = onDismiss) {
                            Icon(
                                painter = painterResource(R.drawable.close),
                                contentDescription = stringResource(R.string.close_dialog),
                            )
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            scrolledContainerColor = Color.Transparent,
                        ),
                )
            },
        ) { innerPadding ->
            content(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun TranslationContributorsDialogContent(
    state: AboutTranslationContributorsUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        AboutTranslationContributorsUiState.Loading -> {
            DialogStatusContent(
                message = stringResource(R.string.loading),
                showRetry = false,
                onRetry = onRetry,
                modifier = modifier,
            )
        }

        AboutTranslationContributorsUiState.Empty -> {
            DialogStatusContent(
                message = stringResource(R.string.no_results_found),
                showRetry = true,
                onRetry = onRetry,
                modifier = modifier,
            )
        }

        is AboutTranslationContributorsUiState.Error -> {
            DialogStatusContent(
                message = stringResource(state.messageResId),
                showRetry = true,
                onRetry = onRetry,
                modifier = modifier,
            )
        }

        is AboutTranslationContributorsUiState.Success -> {
            TranslationContributorList(
                contributors = state.contributors,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun DependencyLicensesDialogContent(
    state: AboutDependencyLicensesUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        AboutDependencyLicensesUiState.Loading -> {
            DialogStatusContent(
                message = stringResource(R.string.loading),
                showRetry = false,
                onRetry = onRetry,
                modifier = modifier,
            )
        }

        AboutDependencyLicensesUiState.Empty -> {
            DialogStatusContent(
                message = stringResource(R.string.no_results_found),
                showRetry = true,
                onRetry = onRetry,
                modifier = modifier,
            )
        }

        is AboutDependencyLicensesUiState.Error -> {
            DialogStatusContent(
                message = stringResource(state.messageResId),
                showRetry = true,
                onRetry = onRetry,
                modifier = modifier,
            )
        }

        is AboutDependencyLicensesUiState.Success -> {
            DependencyLicenseList(
                licenses = state.licenses,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun DialogStatusContent(
    message: String,
    showRetry: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(AboutSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!showRetry) {
            LoadingIndicator(modifier = Modifier.size(40.dp))
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = AboutSpacing.sm),
        )
        if (showRetry) {
            TextButton(
                onClick = onRetry,
                modifier = Modifier.padding(top = AboutSpacing.sm),
            ) {
                Text(text = stringResource(R.string.retry))
            }
        }
    }
}

@Composable
private fun TranslationContributorList(
    contributors: AboutTranslationContributorUiCollection,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = AboutDimensions.DialogContentMaxWidth)
                    .fillMaxWidth(),
            contentPadding = AboutDimensions.DialogListPadding,
            verticalArrangement = Arrangement.spacedBy(AboutSpacing.xs),
        ) {
            items(
                count = contributors.size,
                key = { index -> contributors[index].language },
                contentType = { "translation_contributor" },
            ) { index ->
                val contributor = contributors[index]
                TranslationContributorListItem(
                    language = contributor.language,
                    contributors = contributor.contributors,
                )
            }
        }
    }
}

@Composable
private fun TranslationContributorListItem(
    language: String,
    contributors: String?,
    modifier: Modifier = Modifier,
) {
    AboutGlassRow(
        modifier = modifier,
        minHeight = if (contributors == null) 64.dp else 76.dp,
        leading = {
            AboutLeadingIcon(iconResId = R.drawable.language)
        },
        supporting = contributors?.let { contributorNames ->
            {
                Text(
                    text = contributorNames,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) {
        Text(
            text = language,
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DependencyLicenseList(
    licenses: AboutDependencyLicenseUiCollection,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = AboutDimensions.DialogContentMaxWidth)
                    .fillMaxWidth(),
            contentPadding = AboutDimensions.DialogListPadding,
            verticalArrangement = Arrangement.spacedBy(AboutSpacing.xs),
        ) {
            items(
                count = licenses.size,
                key = { index -> "${licenses[index].name}:${licenses[index].version.orEmpty()}:$index" },
                contentType = { "dependency_license" },
            ) { index ->
                val dependency = licenses[index]
                DependencyLicenseListItem(
                    name = dependency.name,
                    version = dependency.version,
                    licenses = dependency.licenses,
                )
            }
        }
    }
}

@Composable
private fun DependencyLicenseListItem(
    name: String,
    version: String?,
    licenses: String?,
    modifier: Modifier = Modifier,
) {
    AboutGlassRow(
        modifier = modifier,
        minHeight = 76.dp,
        leading = {
            AboutLeadingIcon(iconResId = R.drawable.info)
        },
        supporting = {
            if (version != null) {
                Text(
                    text = version,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = licenses ?: stringResource(R.string.about_license_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AboutSuccessContent(
    model: AboutUiModel,
    onOpenUri: (String) -> Unit,
    onRetryContributors: () -> Unit,
    onOpenTranslationContributors: () -> Unit,
    onOpenDependencyLicenses: () -> Unit,
    glassBackdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    listState: LazyListState,
    onLogoHeightChanged: (Int) -> Unit,
) {
    val leadDevelopers = model.leadDevelopers
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    var logoHeightDp by remember { mutableStateOf(300.dp) }
    var logoAreaY by remember { mutableFloatStateOf(0f) }
    var iconY by remember { mutableFloatStateOf(0f) }
    var projectNameY by remember { mutableFloatStateOf(0f) }
    var versionCodeY by remember { mutableFloatStateOf(0f) }

    var iconProgress by remember { mutableFloatStateOf(0f) }
    var projectNameProgress by remember { mutableFloatStateOf(0f) }
    var versionCodeProgress by remember { mutableFloatStateOf(0f) }
    var initialLogoAreaY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemScrollOffset }
            .onEach { offset ->
                if (listState.firstVisibleItemIndex > 0) {
                    if (iconProgress != 1f) iconProgress = 1f
                    if (projectNameProgress != 1f) projectNameProgress = 1f
                    if (versionCodeProgress != 1f) versionCodeProgress = 1f
                    return@onEach
                }

                if (initialLogoAreaY == 0f && logoAreaY > 0f) {
                    initialLogoAreaY = logoAreaY
                }
                val refLogoAreaY = if (initialLogoAreaY > 0f) initialLogoAreaY else logoAreaY

                val stage1TotalLength = (refLogoAreaY - versionCodeY).coerceAtLeast(1f)
                val stage2TotalLength = (versionCodeY - projectNameY).coerceAtLeast(1f)
                val stage3TotalLength = (projectNameY - iconY).coerceAtLeast(1f)

                val versionCodeDelay = stage1TotalLength * 0.5f
                versionCodeProgress =
                    ((offset.toFloat() - versionCodeDelay) / (stage1TotalLength - versionCodeDelay).coerceAtLeast(1f))
                        .coerceIn(0f, 1f)
                projectNameProgress =
                    ((offset.toFloat() - stage1TotalLength) / stage2TotalLength)
                        .coerceIn(0f, 1f)
                iconProgress =
                    ((offset.toFloat() - stage1TotalLength - stage2TotalLength) / stage3TotalLength)
                        .coerceIn(0f, 1f)
            }
            .collect { }
    }

    val horizontalContentPadding =
        PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            end = contentPadding.calculateEndPadding(layoutDirection),
        )

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(AboutSpacing.sm),
        ) {
            item(key = "logoSpacer", contentType = "about_logo_spacer") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(
                            logoHeightDp + 52.dp + contentPadding.calculateTopPadding() + 126.dp,
                        )
                        .onSizeChanged { size -> onLogoHeightChanged(size.height) }
                        .onGloballyPositioned { coordinates ->
                            val y = coordinates.positionInWindow().y
                            val size = coordinates.size
                            logoAreaY = y + size.height
                        },
                )
            }

            item(key = "links", contentType = "about_links") {
                AboutContentContainer(horizontalPadding = horizontalContentPadding) {
                    AboutLinksCard(
                        links = model.primaryLinks,
                        onOpenUri = onOpenUri,
                        glassBackdrop = glassBackdrop,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item(key = "project_information", contentType = "about_actions") {
                AboutContentContainer(horizontalPadding = horizontalContentPadding) {
                    AboutProjectInformationSection(
                        onOpenTranslationContributors = onOpenTranslationContributors,
                        onOpenDependencyLicenses = onOpenDependencyLicenses,
                        glassBackdrop = glassBackdrop,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item(key = "lead_developer", contentType = "about_team_section") {
                AboutContentContainer(horizontalPadding = horizontalContentPadding) {
                    TeamMemberSection(
                        title = stringResource(R.string.about_lead_developer),
                        members = leadDevelopers,
                        onOpenUri = onOpenUri,
                        glassBackdrop = glassBackdrop,
                        prominentFirstItem = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item(key = "team", contentType = "about_team_section") {
                AboutContentContainer(horizontalPadding = horizontalContentPadding) {
                    TeamMemberSection(
                        title = stringResource(R.string.about_archive_tune_team),
                        members = model.collaborators,
                        onOpenUri = onOpenUri,
                        glassBackdrop = glassBackdrop,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item(key = "respecters", contentType = "about_team_section") {
                AboutContentContainer(horizontalPadding = horizontalContentPadding) {
                    TeamMemberSection(
                        title = stringResource(R.string.about_respecter),
                        members = model.respecters,
                        onOpenUri = onOpenUri,
                        glassBackdrop = glassBackdrop,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item(key = "contributors", contentType = "about_contributors") {
                AboutContentContainer(horizontalPadding = horizontalContentPadding) {
                    ContributorsSection(
                        state = model.contributorsState,
                        readMoreUrl = model.contributorsReadMoreUrl,
                        onOpenProfile = onOpenUri,
                        onRetry = onRetryContributors,
                        glassBackdrop = glassBackdrop,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        top = contentPadding.calculateTopPadding() + 52.dp,
                        start = horizontalContentPadding.calculateStartPadding(layoutDirection),
                        end = horizontalContentPadding.calculateEndPadding(layoutDirection),
                    )
                    .onSizeChanged { size ->
                        with(density) { logoHeightDp = size.height.toDp() }
                    },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(112.dp)
                        .clipToBounds()
                        .graphicsLayer {
                            alpha = 1 - iconProgress
                            scaleX = 1 - (iconProgress * 0.05f)
                            scaleY = 1 - (iconProgress * 0.05f)
                        }
                        .onGloballyPositioned { coordinates ->
                            if (iconY != 0f) return@onGloballyPositioned
                            val y = coordinates.positionInWindow().y
                            val size = coordinates.size
                            iconY = y + size.height
                        },
            ) {
                SurfaceAppIcon(modifier = Modifier.size(112.dp), iconSize = 80.dp)
            }
            Text(
                text = stringResource(model.appNameResId),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 35.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .padding(top = 12.dp, bottom = 5.dp)
                        .onGloballyPositioned { coordinates ->
                            if (projectNameY != 0f) return@onGloballyPositioned
                            val y = coordinates.positionInWindow().y
                            val size = coordinates.size
                            projectNameY = y + size.height
                        }
                        .graphicsLayer {
                            alpha = 1 - projectNameProgress
                            scaleX = 1 - (projectNameProgress * 0.05f)
                            scaleY = 1 - (projectNameProgress * 0.05f)
                        },
            )
            Text(
                text = "v${model.versionName}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .onGloballyPositioned { coordinates ->
                            if (versionCodeY != 0f) return@onGloballyPositioned
                            val y = coordinates.positionInWindow().y
                            val size = coordinates.size
                            versionCodeY = y + size.height
                        }
                        .graphicsLayer {
                            alpha = 1 - versionCodeProgress
                            scaleX = 1 - (versionCodeProgress * 0.05f)
                            scaleY = 1 - (versionCodeProgress * 0.05f)
                        },
            )
        }
    }
}

@Composable
private fun AboutContentContainer(
    modifier: Modifier = Modifier,
    horizontalPadding: PaddingValues = PaddingValues(),
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    start = SettingsDimensions.ScreenHorizontalPadding.coerceAtLeast(
                        horizontalPadding.calculateStartPadding(LocalLayoutDirection.current),
                    ),
                    end = SettingsDimensions.ScreenHorizontalPadding.coerceAtLeast(
                        horizontalPadding.calculateEndPadding(LocalLayoutDirection.current),
                    ),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .widthIn(max = AboutDimensions.ScreenContentMaxWidth)
                    .fillMaxWidth(),
        ) {
            content()
        }
    }
}

@Composable
private fun AboutGlassCard(
    glassBackdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val cardShape = RoundedCornerShape(16.dp)
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Column(
        modifier =
            modifier
                .then(
                    if (glassBackdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = glassBackdrop,
                            shape = { cardShape },
                            effects = {
                                blur(60f)
                            },
                            onDrawSurface = {
                                if (isDark) {
                                    drawRect(AboutGlassDarkTint, blendMode = BlendMode.Luminosity)
                                    drawRect(AboutGlassDarkShade, blendMode = BlendMode.Darken)
                                } else {
                                    drawRect(AboutGlassLightTint, blendMode = BlendMode.Overlay)
                                    drawRect(AboutGlassLightShade, blendMode = BlendMode.HardLight)
                                }
                            },
                        )
                    } else {
                        Modifier
                            .clip(cardShape)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                    },
                ),
    ) {
        content()
    }
}

@Composable
private fun AboutLinksCard(
    links: AboutLinkCollection,
    onOpenUri: (String) -> Unit,
    glassBackdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
) {
    AboutGlassCard(
        glassBackdrop = glassBackdrop,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(vertical = AboutSpacing.xs)) {
            repeat(links.size) { index ->
                val link = links[index]
                val onClick = remember(link.url, onOpenUri) { { onOpenUri(link.url) } }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AboutSpacing.sm),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onClick)
                            .padding(horizontal = AboutSpacing.md, vertical = 14.dp),
                ) {
                    AboutLeadingIcon(iconResId = link.iconResId)
                    Text(
                        text = stringResource(link.labelResId),
                        style = MaterialTheme.typography.bodyLargeEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        painter = painterResource(R.drawable.arrow_forward),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SurfaceAppIcon(
    modifier: Modifier = Modifier,
    iconSize: Dp = 64.dp,
) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        val iconTint = MaterialTheme.colorScheme.onPrimaryContainer
        val iconColorFilter = remember(iconTint) { ColorFilter.tint(iconTint) }
        Image(
            painter = painterResource(R.drawable.about_splash),
            contentDescription = null,
            colorFilter = iconColorFilter,
            modifier =
                Modifier
                    .padding(AboutSpacing.sm)
                    .size(iconSize),
        )
    }
}

@Composable
private fun AboutGlassRow(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    minHeight: Dp = 64.dp,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AboutSpacing.sm),
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = AboutSpacing.md, vertical = 12.dp),
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            content()
            supporting?.invoke()
        }
        trailing?.invoke()
    }
}

@Composable
private fun AboutProjectInformationSection(
    onOpenTranslationContributors: () -> Unit,
    onOpenDependencyLicenses: () -> Unit,
    glassBackdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
) {
    AboutGlassCard(
        glassBackdrop = glassBackdrop,
        modifier = modifier,
    ) {
        AboutGlassRow(
            onClick = onOpenTranslationContributors,
            leading = { AboutLeadingIcon(iconResId = R.drawable.translate) },
            trailing = {
                Icon(
                    painter = painterResource(R.drawable.arrow_forward),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        ) {
            Text(
                text = stringResource(R.string.about_contributor_translation),
                style = MaterialTheme.typography.bodyLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AboutGlassRow(
            onClick = onOpenDependencyLicenses,
            leading = { AboutLeadingIcon(iconResId = R.drawable.info) },
            trailing = {
                Icon(
                    painter = painterResource(R.drawable.arrow_forward),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        ) {
            Text(
                text = stringResource(R.string.about_license),
                style = MaterialTheme.typography.bodyLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TeamMemberSection(
    title: String,
    members: TeamMemberCollection,
    onOpenUri: (String) -> Unit,
    glassBackdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    prominentFirstItem: Boolean = false,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AboutSpacing.xs),
    ) {
        AboutSectionHeader(title = title)
        AboutGlassCard(glassBackdrop = glassBackdrop) {
            Column(modifier = Modifier.padding(vertical = AboutSpacing.xs)) {
                repeat(members.size) { index ->
                    TeamMemberListItem(
                        member = members[index],
                        onOpenUri = onOpenUri,
                        avatarSize = if (prominentFirstItem && index == 0) 64.dp else 52.dp,
                        minHeight = if (prominentFirstItem && index == 0) 96.dp else 80.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMediumEmphasized,
        color = MaterialTheme.colorScheme.onSurface,
        modifier =
            modifier.padding(
                horizontal = SettingsDimensions.SectionHeaderHorizontalPadding,
                vertical = SettingsDimensions.SectionHeaderBottomPadding,
            ),
    )
}

@Composable
private fun TeamMemberListItem(
    member: TeamMember,
    onOpenUri: (String) -> Unit,
    modifier: Modifier = Modifier,
    avatarSize: Dp,
    minHeight: Dp,
) {
    val onClick: () -> Unit =
        remember(member.profileUrl, onOpenUri) {
            {
                val profileUrl = member.profileUrl
                if (!profileUrl.isNullOrBlank()) {
                    onOpenUri(profileUrl)
                }
            }
        }

    AboutGlassRow(
        onClick = onClick,
        modifier = modifier,
        minHeight = minHeight,
        leading = {
            AsyncImage(
                model = member.avatarUrl,
                contentDescription = member.name,
                modifier =
                    Modifier
                        .size(avatarSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        },
        supporting = {
            Text(
                text = stringResource(member.positionResId),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailing = {
            MemberLinkActions(
                links = member.links,
                onOpenUri = onOpenUri,
            )
        },
    ) {
        Text(
            text = member.name,
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemberLinkActions(
    links: AboutLinkCollection,
    onOpenUri: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.widthIn(max = 156.dp),
        horizontalArrangement = Arrangement.spacedBy(AboutSpacing.xxs, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(AboutSpacing.xxs),
    ) {
        repeat(links.size) { index ->
            val link = links[index]
            val onClick = remember(link.url, onOpenUri) { { onOpenUri(link.url) } }

            FilledTonalIconButton(
                onClick = onClick,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(link.iconResId),
                    contentDescription = stringResource(link.labelResId),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ContributorsSection(
    state: AboutContributorsUiState,
    readMoreUrl: String,
    onOpenProfile: (String) -> Unit,
    onRetry: () -> Unit,
    glassBackdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AboutSpacing.xs),
    ) {
        AboutSectionHeader(title = stringResource(R.string.about_contributors))
        when (state) {
            AboutContributorsUiState.Loading -> {
                ContributorStatusContent(
                    message = stringResource(R.string.loading),
                    showRetry = false,
                    onRetry = onRetry,
                    glassBackdrop = glassBackdrop,
                )
            }

            AboutContributorsUiState.Empty -> {
                ContributorStatusContent(
                    message = stringResource(R.string.no_results_found),
                    showRetry = true,
                    onRetry = onRetry,
                    glassBackdrop = glassBackdrop,
                )
            }

            is AboutContributorsUiState.Error -> {
                ContributorStatusContent(
                    message = stringResource(state.messageResId),
                    showRetry = true,
                    onRetry = onRetry,
                    glassBackdrop = glassBackdrop,
                )
            }

            is AboutContributorsUiState.Success -> {
                ContributorList(
                    contributors = state.contributors,
                    readMoreUrl = readMoreUrl,
                    onOpenProfile = onOpenProfile,
                    glassBackdrop = glassBackdrop,
                )
            }
        }
    }
}

@Composable
private fun ContributorStatusContent(
    message: String,
    showRetry: Boolean,
    onRetry: () -> Unit,
    glassBackdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
) {
    AboutGlassCard(
        glassBackdrop = glassBackdrop,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(AboutSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AboutSpacing.sm),
        ) {
            if (!showRetry) {
                LoadingIndicator(modifier = Modifier.size(32.dp))
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showRetry) {
                TextButton(onClick = onRetry) {
                    Text(text = stringResource(R.string.retry))
                }
            }
        }
    }
}

@Composable
private fun ContributorList(
    contributors: AboutContributorUiCollection,
    readMoreUrl: String,
    onOpenProfile: (String) -> Unit,
    glassBackdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
) {
    AboutGlassCard(
        glassBackdrop = glassBackdrop,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = AboutSpacing.xs)) {
            repeat(contributors.size) { index ->
                val contributor = contributors[index]
                ContributorListItem(
                    login = contributor.login,
                    avatarUrl = contributor.avatarUrl,
                    profileUrl = contributor.profileUrl,
                    onOpenProfile = onOpenProfile,
                )
            }
            ContributorReadMoreListItem(
                readMoreUrl = readMoreUrl,
                onOpenProfile = onOpenProfile,
            )
        }
    }
}

@Composable
private fun ContributorListItem(
    login: String,
    avatarUrl: String,
    profileUrl: String,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val onClick = remember(profileUrl, onOpenProfile) { { onOpenProfile(profileUrl) } }

    AboutGlassRow(
        onClick = onClick,
        modifier = modifier,
        minHeight = 72.dp,
        leading = {
            AsyncImage(
                model = avatarUrl,
                contentDescription = login,
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        },
    ) {
        Text(
            text = login,
            style = MaterialTheme.typography.bodyLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ContributorReadMoreListItem(
    readMoreUrl: String,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val onClick = remember(readMoreUrl, onOpenProfile) { { onOpenProfile(readMoreUrl) } }

    AboutGlassRow(
        onClick = onClick,
        modifier = modifier,
        minHeight = 72.dp,
        leading = {
            AboutLeadingIcon(iconResId = R.drawable.add_circle)
        },
        trailing = {
            Icon(
                painter = painterResource(R.drawable.arrow_forward),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        Text(
            text = stringResource(R.string.more),
            style = MaterialTheme.typography.bodyLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AboutLeadingIcon(
    iconResId: Int,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Icon(
            painter = painterResource(iconResId),
            contentDescription = null,
            modifier =
                Modifier
                    .padding(AboutSpacing.sm)
                    .size(20.dp),
        )
    }
}

private object AboutDimensions {
    val ScreenContentMaxWidth = 840.dp
    val DialogContentMaxWidth = 920.dp
    val HorizontalHeroBreakpoint = 600.dp
    val DialogListPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    val TopBarHeight = 64.dp
}

private object AboutSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 16.dp
    val md = 24.dp
}

private val AboutGlassDarkTint = Color(0x4DA9A9A9)
private val AboutGlassDarkShade = Color(0x1A9C9C9C)
private val AboutGlassLightTint = Color(0x340034F9)
private val AboutGlassLightShade = Color(0xB3FFFFFF)

