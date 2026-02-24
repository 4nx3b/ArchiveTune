/*
 * ArchiveTune Project Original (2026)
 * Kòi Natsuko (github.com/koiverse)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package moe.koiverse.archivetune.ui.screens.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import moe.koiverse.archivetune.BuildConfig
import moe.koiverse.archivetune.LocalPlayerAwareWindowInsets
import moe.koiverse.archivetune.R
import moe.koiverse.archivetune.ui.component.IconButton
import moe.koiverse.archivetune.ui.component.TopSearch
import moe.koiverse.archivetune.ui.utils.backToMain

data class SettingsQuickAction(
    val icon: Painter,
    val label: String,
    val onClick: () -> Unit,
    val accentColor: Color,
)

data class SettingsCategory(
    val title: String,
    val items: List<PremiumSettingsItem>,
)

data class PremiumSettingsItem(
    val icon: Painter,
    val title: String,
    val subtitle: String? = null,
    val badge: String? = null,
    val showUpdateIndicator: Boolean = false,
    val accentColor: Color = Color.Unspecified,
    val onClick: () -> Unit,
)

data class SettingsIntegrationAction(
    val icon: Painter,
    val label: String,
    val onClick: () -> Unit,
    val accentColor: Color,
)

private fun filterQuickActions(
    actions: List<SettingsQuickAction>,
    query: String,
): List<SettingsQuickAction> {
    if (query.isBlank()) return actions
    return actions.filter { it.label.contains(query, ignoreCase = true) }
}

private fun filterSettingsCategories(
    categories: List<SettingsCategory>,
    query: String,
): List<SettingsCategory> {
    if (query.isBlank()) return categories
    return categories.mapNotNull { category ->
        if (category.title.contains(query, ignoreCase = true)) {
            category
        } else {
            val filteredItems = category.items.filter { item ->
                item.title.contains(query, ignoreCase = true) ||
                    (item.subtitle?.contains(query, ignoreCase = true) == true) ||
                    (item.badge?.contains(query, ignoreCase = true) == true)
            }
            if (filteredItems.isEmpty()) null else category.copy(items = filteredItems)
        }
    }
}

private fun filterIntegrations(
    integrations: List<SettingsIntegrationAction>,
    query: String,
): List<SettingsIntegrationAction> {
    if (query.isBlank()) return integrations
    return integrations.filter { it.label.contains(query, ignoreCase = true) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    latestVersionName: String,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val isAndroid12OrLater = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val listState = rememberLazyListState()

    var isSearching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(TextFieldValue()) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearching) {
        if (isSearching) {
            focusRequester.requestFocus()
        }
    }

    val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        null
    }

    var isStorageGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, storagePermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    var isNotificationGranted by remember {
        mutableStateOf(
            notificationPermission == null ||
                ContextCompat.checkSelfPermission(context, notificationPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        isStorageGranted = result[storagePermission] == true || isStorageGranted
        if (notificationPermission != null) {
            isNotificationGranted = result[notificationPermission] == true || isNotificationGranted
        }
    }

    val shouldShowPermissionHint = !isStorageGranted || !isNotificationGranted
    val hasUpdate = latestVersionName != BuildConfig.VERSION_NAME

    var heroVisible by remember { mutableStateOf(false) }
    var bannerVisible by remember { mutableStateOf(false) }
    var quickActionsVisible by remember { mutableStateOf(false) }
    var integrationsVisible by remember { mutableStateOf(false) }
    var categoriesVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(50)
        heroVisible = true
        delay(60)
        bannerVisible = true
        delay(60)
        quickActionsVisible = true
        delay(70)
        integrationsVisible = true
        delay(70)
        categoriesVisible = true
    }

    val quickActions = listOf(
        SettingsQuickAction(
            icon = painterResource(R.drawable.palette),
            label = stringResource(R.string.appearance),
            onClick = { navController.navigate("settings/appearance") },
            accentColor = MaterialTheme.colorScheme.primary,
        ),
        SettingsQuickAction(
            icon = painterResource(R.drawable.play),
            label = stringResource(R.string.player_and_audio),
            onClick = { navController.navigate("settings/player") },
            accentColor = MaterialTheme.colorScheme.tertiary,
        ),
        SettingsQuickAction(
            icon = painterResource(R.drawable.storage),
            label = stringResource(R.string.storage),
            onClick = { navController.navigate("settings/storage") },
            accentColor = MaterialTheme.colorScheme.secondary,
        ),
        SettingsQuickAction(
            icon = painterResource(R.drawable.security),
            label = stringResource(R.string.privacy),
            onClick = { navController.navigate("settings/privacy") },
            accentColor = MaterialTheme.colorScheme.error,
        ),
    )

    val integrationActions = listOf(
        SettingsIntegrationAction(
            icon = painterResource(R.drawable.discord),
            label = stringResource(R.string.discord),
            onClick = { navController.navigate("settings/discord") },
            accentColor = Color(0xFF5865F2),
        ),
        SettingsIntegrationAction(
            icon = painterResource(R.drawable.link),
            label = stringResource(R.string.integration),
            onClick = { navController.navigate("settings/integration") },
            accentColor = MaterialTheme.colorScheme.secondary,
        ),
        SettingsIntegrationAction(
            icon = painterResource(R.drawable.play),
            label = stringResource(R.string.music_together),
            onClick = { navController.navigate("settings/music_together") },
            accentColor = MaterialTheme.colorScheme.tertiary,
        ),
    )

    val resetSearch: () -> Unit = {
        isSearching = false
        query = TextFieldValue()
        focusManager.clearFocus()
    }

    val settingsCategories = buildList {
        add(
            SettingsCategory(
                title = stringResource(R.string.settings_section_ui),
                items = listOf(
                    PremiumSettingsItem(
                        icon = painterResource(R.drawable.palette),
                        title = stringResource(R.string.appearance),
                        subtitle = stringResource(R.string.dark_theme),
                        accentColor = MaterialTheme.colorScheme.primary,
                        onClick = { navController.navigate("settings/appearance") },
                    ),
                ),
            ),
        )

        add(
            SettingsCategory(
                title = stringResource(R.string.settings_section_player_content),
                items = listOf(
                    PremiumSettingsItem(
                        icon = painterResource(R.drawable.play),
                        title = stringResource(R.string.player_and_audio),
                        subtitle = stringResource(R.string.audio_quality),
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        onClick = { navController.navigate("settings/player") },
                    ),
                    PremiumSettingsItem(
                        icon = painterResource(R.drawable.language),
                        title = stringResource(R.string.content),
                        subtitle = stringResource(R.string.content_language),
                        accentColor = MaterialTheme.colorScheme.secondary,
                        onClick = { navController.navigate("settings/content") },
                    ),
                ),
            ),
        )

        add(
            SettingsCategory(
                title = stringResource(R.string.settings_section_privacy),
                items = listOf(
                    PremiumSettingsItem(
                        icon = painterResource(R.drawable.security),
                        title = stringResource(R.string.privacy),
                        subtitle = stringResource(R.string.pause_listen_history),
                        accentColor = MaterialTheme.colorScheme.error,
                        onClick = { navController.navigate("settings/privacy") },
                    ),
                ),
            ),
        )

        add(
            SettingsCategory(
                title = stringResource(R.string.settings_section_storage),
                items = listOf(
                    PremiumSettingsItem(
                        icon = painterResource(R.drawable.storage),
                        title = stringResource(R.string.storage),
                        subtitle = stringResource(R.string.cache),
                        accentColor = MaterialTheme.colorScheme.secondary,
                        onClick = { navController.navigate("settings/storage") },
                    ),
                    PremiumSettingsItem(
                        icon = painterResource(R.drawable.restore),
                        title = stringResource(R.string.backup_restore),
                        subtitle = stringResource(R.string.action_backup),
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        onClick = { navController.navigate("settings/backup_restore") },
                    ),
                ),
            ),
        )

        add(
            SettingsCategory(
                title = stringResource(R.string.settings_section_system),
                items = buildList {
                    if (isAndroid12OrLater) {
                        add(
                            PremiumSettingsItem(
                                icon = painterResource(R.drawable.link),
                                title = stringResource(R.string.default_links),
                                subtitle = stringResource(R.string.open_supported_links),
                                accentColor = MaterialTheme.colorScheme.primary,
                                onClick = {
                                    try {
                                        val intent = Intent(
                                            Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        when (e) {
                                            is ActivityNotFoundException,
                                            is SecurityException,
                                            -> {
                                                Toast.makeText(
                                                    context,
                                                    R.string.open_app_settings_error,
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                            else -> {
                                                Toast.makeText(
                                                    context,
                                                    R.string.open_app_settings_error,
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                        }
                                    }
                                },
                            ),
                        )
                    }
                    add(
                        PremiumSettingsItem(
                            icon = painterResource(R.drawable.experiment),
                            title = stringResource(R.string.experiment_settings),
                            subtitle = stringResource(R.string.misc),
                            accentColor = MaterialTheme.colorScheme.tertiary,
                            onClick = { navController.navigate("settings/misc") },
                        ),
                    )
                    add(
                        PremiumSettingsItem(
                            icon = painterResource(R.drawable.update),
                            title = stringResource(R.string.updates),
                            subtitle = if (hasUpdate) {
                                stringResource(R.string.new_version_available)
                            } else {
                                BuildConfig.VERSION_NAME
                            },
                            showUpdateIndicator = hasUpdate,
                            accentColor = if (hasUpdate) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            onClick = { navController.navigate("settings/update") },
                        ),
                    )
                    add(
                        PremiumSettingsItem(
                            icon = painterResource(R.drawable.info),
                            title = stringResource(R.string.about),
                            subtitle = "ArchiveTune",
                            accentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = { navController.navigate("settings/about") },
                        ),
                    )
                },
            ),
        )
    }

    val wrappedQuickActions = quickActions.map { action ->
        val originalOnClick = action.onClick
        action.copy(onClick = { resetSearch(); originalOnClick() })
    }

    val wrappedIntegrations = integrationActions.map { action ->
        val originalOnClick = action.onClick
        action.copy(onClick = { resetSearch(); originalOnClick() })
    }

    val wrappedCategories = settingsCategories.map { category ->
        category.copy(
            items = category.items.map { item ->
                val originalOnClick = item.onClick
                item.copy(onClick = { resetSearch(); originalOnClick() })
            }
        )
    }

    val queryText = query.text.trim()
    val showSearchBar = isSearching || queryText.isNotBlank()

    val filteredQuickActions = filterQuickActions(wrappedQuickActions, queryText)
    val filteredIntegrations = filterIntegrations(wrappedIntegrations, queryText)
    val filteredCategories = filterSettingsCategories(wrappedCategories, queryText)

    val hasSearchResults by remember(filteredQuickActions, filteredCategories, filteredIntegrations) {
        derivedStateOf {
            filteredQuickActions.isNotEmpty() ||
                filteredCategories.isNotEmpty() ||
                filteredIntegrations.isNotEmpty()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SettingsMeshGradientBackground(
            modifier = Modifier.align(Alignment.TopCenter)
        )

        if (!showSearchBar) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                        )
                    ),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                item(key = "topSpacer") {
                    Spacer(
                        Modifier.windowInsetsPadding(
                            LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)
                        )
                    )
                }

                item(key = "hero") {
                    AnimatedVisibility(
                        visible = heroVisible,
                        enter = fadeIn(spring(stiffness = Spring.StiffnessLow)) +
                            slideInVertically(
                                initialOffsetY = { it / 5 },
                                animationSpec = spring(
                                    stiffness = Spring.StiffnessLow,
                                    dampingRatio = 0.85f,
                                ),
                            ),
                    ) {
                        SettingsHeroHeader(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(top = 4.dp, bottom = 14.dp),
                        )
                    }
                }

                if (queryText.isBlank()) {
                    item(key = "permission") {
                        AnimatedVisibility(
                            visible = bannerVisible && shouldShowPermissionHint,
                            enter = fadeIn(spring(stiffness = Spring.StiffnessLow)) +
                                expandVertically(spring(stiffness = Spring.StiffnessLow)) +
                                slideInVertically(
                                    initialOffsetY = { -it / 4 },
                                    animationSpec = spring(
                                        stiffness = Spring.StiffnessLow,
                                        dampingRatio = 0.85f,
                                    ),
                                ),
                            exit = fadeOut(tween(300)) + shrinkVertically(tween(300)),
                        ) {
                            PremiumPermissionCard(
                                onRequestPermission = {
                                    val toRequest = buildList {
                                        if (!isStorageGranted) add(storagePermission)
                                        if (!isNotificationGranted && notificationPermission != null) {
                                            add(notificationPermission)
                                        }
                                    }
                                    if (toRequest.isNotEmpty()) {
                                        permissionLauncher.launch(toRequest.toTypedArray())
                                    }
                                },
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 14.dp),
                            )
                        }
                    }

                    item(key = "update") {
                        AnimatedVisibility(
                            visible = bannerVisible && hasUpdate,
                            enter = fadeIn(spring(stiffness = Spring.StiffnessLow)) +
                                expandVertically(spring(stiffness = Spring.StiffnessLow)) +
                                slideInVertically(
                                    initialOffsetY = { -it / 4 },
                                    animationSpec = spring(
                                        stiffness = Spring.StiffnessLow,
                                        dampingRatio = 0.85f,
                                    ),
                                ),
                            exit = fadeOut(tween(300)) + shrinkVertically(tween(300)),
                        ) {
                            PremiumUpdateCard(
                                latestVersion = latestVersionName,
                                onClick = { navController.navigate("settings/update") },
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 14.dp),
                            )
                        }
                    }
                }

                if (queryText.isBlank() || filteredQuickActions.isNotEmpty()) {
                    item(key = "quickActions") {
                        AnimatedVisibility(
                            visible = quickActionsVisible,
                            enter = fadeIn(spring(stiffness = Spring.StiffnessLow)) +
                                slideInVertically(
                                    initialOffsetY = { it / 6 },
                                    animationSpec = spring(
                                        stiffness = Spring.StiffnessLow,
                                        dampingRatio = 0.85f,
                                    ),
                                ),
                        ) {
                            val actionsToShow = if (queryText.isBlank()) {
                                wrappedQuickActions
                            } else {
                                filteredQuickActions
                            }
                            SettingsQuickActionsGrid(
                                title = stringResource(R.string.quick_picks),
                                actions = actionsToShow,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 12.dp),
                            )
                        }
                    }
                }

                if (queryText.isBlank() || filteredIntegrations.isNotEmpty()) {
                    item(key = "integrations") {
                        AnimatedVisibility(
                            visible = integrationsVisible,
                            enter = fadeIn(spring(stiffness = Spring.StiffnessLow)) +
                                slideInVertically(
                                    initialOffsetY = { it / 6 },
                                    animationSpec = spring(
                                        stiffness = Spring.StiffnessLow,
                                        dampingRatio = 0.85f,
                                    ),
                                ),
                        ) {
                            val toShow = if (queryText.isBlank()) {
                                wrappedIntegrations
                            } else {
                                filteredIntegrations
                            }
                            SettingsIntegrationsRow(
                                integrations = toShow,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 12.dp),
                            )
                        }
                    }
                }

                if (queryText.isNotBlank() && !hasSearchResults) {
                    item(key = "empty") {
                        Spacer(modifier = Modifier.height(24.dp))
                        EmptyResultsCard(
                            title = stringResource(R.string.no_results_found),
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                } else {
                    val categoriesToShow = if (queryText.isBlank()) {
                        wrappedCategories
                    } else {
                        filteredCategories
                    }
                    items(
                        count = categoriesToShow.size,
                        key = { categoriesToShow[it].title },
                    ) { index ->
                        val category = categoriesToShow[index]
                        AnimatedVisibility(
                            visible = categoriesVisible,
                            enter = fadeIn(tween(420, delayMillis = index * 60)) +
                                slideInVertically(
                                    initialOffsetY = { it / 5 },
                                    animationSpec = tween(420, delayMillis = index * 60),
                                ),
                        ) {
                            PremiumSettingsSection(
                                category = category,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 12.dp),
                            )
                        }
                    }
                }
            }
        }

        if (!showSearchBar) {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(
                            painterResource(R.drawable.arrow_back),
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { isSearching = true },
                        onLongClick = {},
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.search),
                            contentDescription = null,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                ),
            )
        }

        AnimatedVisibility(
            visible = showSearchBar,
            enter = fadeIn(tween(durationMillis = 220)),
            exit = fadeOut(tween(durationMillis = 160)),
        ) {
            TopSearch(
                query = query,
                onQueryChange = { query = it },
                onSearch = { focusManager.clearFocus() },
                active = showSearchBar,
                onActiveChange = { active ->
                    if (active) {
                        isSearching = true
                    } else {
                        resetSearch()
                    }
                },
                placeholder = { Text(text = stringResource(R.string.search)) },
                leadingIcon = {
                    IconButton(
                        onClick = { resetSearch() },
                        onLongClick = {
                            if (queryText.isBlank()) {
                                navController.backToMain()
                            }
                        },
                    ) {
                        Icon(
                            painterResource(R.drawable.arrow_back),
                            contentDescription = null,
                        )
                    }
                },
                trailingIcon = {
                    Row {
                        if (query.text.isNotBlank()) {
                            IconButton(
                                onClick = { query = TextFieldValue() },
                                onLongClick = {},
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.close),
                                    contentDescription = null,
                                )
                            }
                        }
                    }
                },
                focusRequester = focusRequester,
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            LocalPlayerAwareWindowInsets.current.only(
                                WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                            )
                        ),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    if (queryText.isNotBlank() && !hasSearchResults) {
                        item {
                            Spacer(modifier = Modifier.height(24.dp))
                            EmptyResultsCard(
                                title = stringResource(R.string.no_results_found),
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    } else {
                        if (filteredQuickActions.isNotEmpty()) {
                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                                SettingsQuickActionsGrid(
                                    title = stringResource(R.string.quick_picks),
                                    actions = filteredQuickActions,
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp)
                                        .padding(top = 8.dp, bottom = 12.dp),
                                )
                            }
                        }

                        if (filteredIntegrations.isNotEmpty()) {
                            item {
                                SettingsIntegrationsRow(
                                    integrations = filteredIntegrations,
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp)
                                        .padding(bottom = 12.dp),
                                )
                            }
                        }

                        items(filteredCategories.size) { index ->
                            val category = filteredCategories[index]
                            PremiumSettingsSection(
                                category = category,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsMeshGradientBackground(modifier: Modifier = Modifier) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val surfaceColor = MaterialTheme.colorScheme.surface

    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.45f)
            .drawWithCache {
                val width = size.width
                val height = size.height

                val blob1 = Brush.radialGradient(
                    colors = listOf(primaryColor.copy(alpha = 0.28f), Color.Transparent),
                    center = Offset(width * 0.2f, height * 0.15f),
                    radius = width * 0.55f,
                )
                val blob2 = Brush.radialGradient(
                    colors = listOf(tertiaryColor.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(width * 0.8f, height * 0.25f),
                    radius = width * 0.45f,
                )
                val blob3 = Brush.radialGradient(
                    colors = listOf(secondaryColor.copy(alpha = 0.18f), Color.Transparent),
                    center = Offset(width * 0.5f, height * 0.55f),
                    radius = width * 0.5f,
                )
                val fadeBrush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Transparent,
                        surfaceColor.copy(alpha = 0.3f),
                        surfaceColor.copy(alpha = 0.7f),
                        surfaceColor,
                    ),
                    startY = height * 0.35f,
                    endY = height,
                )

                onDrawBehind {
                    drawRect(blob1)
                    drawRect(blob2)
                    drawRect(blob3)
                    drawRect(fadeBrush)
                }
            },
    )
}

@Composable
private fun SettingsHeroHeader(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.85f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f),
                            Color.Transparent,
                        ),
                    ),
                )
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.small_icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyResultsCard(
    title: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.search),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.search_try_different),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PremiumPermissionCard(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                            MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ),
                )
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.security),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.permissions_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.permissions_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = onRequestPermission,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.check),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.allow),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PremiumUpdateCard(
    latestVersion: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "updateScale",
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.24f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    ),
                )
                .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.update),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.new_version_available),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "v$latestVersion",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_forward),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsQuickActionsGrid(
    title: String,
    actions: List<SettingsQuickAction>,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.star),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            val rows = actions.chunked(2)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEach { rowActions ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        rowActions.forEach { action ->
                            SettingsQuickActionTile(
                                action = action,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowActions.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsQuickActionTile(
    action: SettingsQuickAction,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "tileScale",
    )
    val tileAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "tileAlpha",
    )

    Surface(
        modifier = modifier
            .scale(scale)
            .graphicsLayer { alpha = tileAlpha }
            .aspectRatio(1.7f),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        onClick = action.onClick,
        interactionSource = interactionSource,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            action.accentColor.copy(alpha = 0.12f),
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ),
                )
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            action.accentColor.copy(alpha = 0.08f),
                            Color.Transparent,
                        ),
                        center = Offset(0f, 0f),
                        radius = 500f,
                    ),
                )
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(action.accentColor.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = action.icon,
                        contentDescription = null,
                        tint = action.accentColor,
                        modifier = Modifier.size(22.dp),
                    )
                }

                Text(
                    text = action.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SettingsIntegrationsRow(
    integrations: List<SettingsIntegrationAction>,
    modifier: Modifier = Modifier,
) {
    if (integrations.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.link),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp),
                    )
                }

                Text(
                    text = stringResource(R.string.integrations),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(
                    count = integrations.size,
                    key = { integrations[it].label },
                ) { index ->
                    IntegrationChip(action = integrations[index])
                }
            }
        }
    }
}

@Composable
private fun IntegrationChip(
    action: SettingsIntegrationAction,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "chipScale",
    )

    Surface(
        modifier = modifier.scale(scale),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        onClick = action.onClick,
        interactionSource = interactionSource,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(action.accentColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = action.icon,
                    contentDescription = null,
                    tint = action.accentColor,
                    modifier = Modifier.size(16.dp),
                )
            }

            Text(
                text = action.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PremiumSettingsSection(
    category: SettingsCategory,
    modifier: Modifier = Modifier,
) {
    val sectionAccent = category.items.firstOrNull()?.let { item ->
        if (item.accentColor.isSpecified) item.accentColor else null
    } ?: MaterialTheme.colorScheme.primary

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                sectionAccent.copy(alpha = 0.5f),
                                sectionAccent.copy(alpha = 0.15f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )

            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = category.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(sectionAccent.copy(alpha = 0.5f)),
                    )
                    Text(
                        text = "${category.items.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                category.items.forEachIndexed { index, item ->
                    PremiumSettingsItemRow(
                        item = item,
                        showDivider = index < category.items.size - 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun PremiumSettingsItemRow(
    item: PremiumSettingsItem,
    showDivider: Boolean,
) {
    val effectiveAccent = if (item.accentColor.isSpecified) {
        item.accentColor
    } else {
        MaterialTheme.colorScheme.primary
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (isPressed) 0.75f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "rowAlpha",
    )

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = item.onClick,
                )
                .graphicsLayer { this.alpha = alpha }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (item.showUpdateIndicator) {
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                                    effectiveAccent.copy(alpha = 0.16f),
                                ),
                            )
                        } else {
                            Brush.linearGradient(
                                colors = listOf(
                                    effectiveAccent.copy(alpha = 0.14f),
                                    effectiveAccent.copy(alpha = 0.08f),
                                ),
                            )
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (item.showUpdateIndicator) {
                    BadgedBox(
                        badge = {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(10.dp),
                            )
                        },
                    ) {
                        Icon(
                            painter = item.icon,
                            contentDescription = null,
                            tint = effectiveAccent,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                } else {
                    Icon(
                        painter = item.icon,
                        contentDescription = null,
                        tint = effectiveAccent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                item.subtitle?.let { subtitle ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.showUpdateIndicator) {
                            effectiveAccent
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            item.badge?.let { badge ->
                Spacer(modifier = Modifier.width(8.dp))
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(text = badge) },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    border = null,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                painter = painterResource(R.drawable.navigate_next),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp),
            )
        }

        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 76.dp, end = 18.dp),
                thickness = 0.4.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
            )
        }
    }
}
