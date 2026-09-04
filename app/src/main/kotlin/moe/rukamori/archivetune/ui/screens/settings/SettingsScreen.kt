/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AppBarHeight
import moe.rukamori.archivetune.ui.component.FrostedHeaderPill
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.LiquidGlassIconButton
import moe.rukamori.archivetune.ui.component.glassAwareSurface
import moe.rukamori.archivetune.ui.component.LocalSettingsDialogShowing
import moe.rukamori.archivetune.ui.component.rememberSettingsDialogHostState
import moe.rukamori.archivetune.ui.screens.GlassScreenHeader
import moe.rukamori.archivetune.ui.screens.ScreenHeaderHaze
import moe.rukamori.archivetune.ui.screens.glassHeaderSource
import moe.rukamori.archivetune.ui.screens.rememberGlassScreenHeader
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.Updater

private val CROSS_PAGE_SCROLL_OWNERS: Map<String, String> =
    buildMap {
        fun own(
            owner: String,
            parent: String,
            vararg keys: String,
        ) = keys.forEach { put("$parent/$it", owner) }

        own(
            "discord", "integration",
            "discord_options", "discord_connection", "discord_activity", "discord_images",
            "activity_status", "platform_status", "discord_activity_name",
            "discord_activity_details", "discord_activity_state", "discord_activity_type",
            "discord_show_when_paused", "large_image", "large_text", "small_image",
        )
        own("discord_experimental", "integration", "discord_experimental")
        own(
            "lastfm", "integration",
            "lastfm_options", "lastfm_scrobbling_config", "enable_scrobbling", "lastfm_now_playing",
            "lastfm_prefer_yt_thumbnails", "scrobble_min_track_duration", "scrobble_delay_percent",
            "scrobble_delay_minutes", "lastfm_connect_button", "lastfm_connect_librefm_button",
            "lastfm_connect_custom_button",
        )
        own("tidal", "integration", "tidal_account", "tidal_instances")
        own("qobuz", "integration", "qobuz_account", "qobuz_tokens", "qobuz_instances")
        own(
            "telegram", "integration",
            "telegram_login", "telegram_browse_channels", "telegram_lossless_only",
            "telegram_logout", "telegram_bots_title",
        )

        own(
            "lyrics_providers", "lyrics",
            "first_lyrics_provider", "set_first_lyrics_provider", "prioritize_word_synced_lyrics",
            "enable_tidal_lyrics", "enable_deezer_lyrics", "enable_musixmatch_experimental",
            "betterlyrics", "betterlyrics_portato", "youlyplus_lyrics", "lrclib", "kugou",
            "unison_lyrics",

        )
        own(
            "lyrics_romanisation", "lyrics",
            "lyrics_romanize_japanese", "lyrics_romanize_korean", "lyrics_romanize_chinese",
            "lyrics_romanize_hindi", "lyrics_romanize_other",
        )

        own("appearance_player", "lyrics", "lyrics_background_style")
        // The lyrics translator shipped alongside the Discord experiments and still lives there.
        own("discord_experimental", "lyrics", "translate_lyrics", "enable_translator")

        own(
            "sources", "playback",
            "preferred_sources", "auto_choose_playback_client", "player_stream_client",
            "check_source", "spotify_catalog_source", "tidal_enable", "tidal_account_first",
            "tidal_audio_quality", "tidal_animated_covers", "tidal_manage_instances",
            "qobuz_enable", "qobuz_audio_quality", "qobuz_backup_enable", "qobuz_manage_instances",
            "deezer_enable", "deezer_audio_quality", "jiosaavn_enable", "jiosaavn_audio_quality",
        )
        own("ytdlp", "playback", "ytdlp")
        own("sources", "deezer", "deezer_enable", "deezer_audio_quality")
        own("qobuz", "sources", "qobuz")
        own("tidal", "sources", "tidal")


        // Appearance is now a hub of three pages, so every row indexed under it has to say which.
        own(
            "appearance_theme", "appearance",
            "dynamic_theme", "random_theme_on_startup", "dark_theme", "pure_black", "color_palette",
            "color_source", "palette_picker", "theme_creator", "app_icon", "disable_animations",
            "hide_status_bar", "ui_scale", "blur_intensity", "disable_blur", "backdrop_blur",
            "backdrop_blur_amount", "font_preference", "use_system_font", "custom_font",
            "liquid_glass_effects", "wallpaper_permission",
        )
        own(
            "appearance_player", "appearance",
            "player_design_style", "player_background_style", "lyrics_background_style",
            "mini_player_background_style", "player_buttons_style", "player_slider_style",
            "show_player_volume_bar", "hide_player_thumbnail", "crop_thumbnail_to_square",
            "thumbnail_corner_radius", "customized_background", "album_canvas_enabled",
            "apple_music_animated_artwork", "simpmusic_lyrics", "apple_music_experience",
        )
        own(
            "appearance_interface", "appearance",
            "minimal_home_mode", "default_open_tab",
            "tablet_mode", "navigation_bar_style", "navigation_bar_settings", "hide_scrollbar",
            "grid_layout", "default_lib_chips", "extras", "app_language",
        )

        // Appearance rows that were moved out to their own pages.
        own("navigation_bar", "appearance", "frosted_nav_bar", "liquid_glass_nav_bar", "hide_navigation_bar_labels")
        own("appearance_extras", "appearance", "show_home_category_chips")
        own("playback", "appearance", "swipe_sensitivity")
        own("behavior", "appearance", "force_high_refresh_rate")
        own("appearance_extras", "behavior", "show_tags_in_library")
        own("downloads", "storage", "downloaded_songs", "download_location")
    }

private fun searchableSettingsRoute(parentKey: String, scrollKey: String?): String? {

    val ownerKey = CROSS_PAGE_SCROLL_OWNERS["$parentKey/${scrollKey.orEmpty()}"] ?: parentKey
    val route =
        when (ownerKey) {
            "account" -> "settings/account"
            "appearance" -> "settings/appearance"
            "appearance_theme" -> "settings/appearance/theme"
            "appearance_player" -> "settings/appearance/player"
            "appearance_interface" -> "settings/appearance/interface"
            "appearance_extras" -> "settings/appearance/extras"
            "aod" -> "settings/appearance/aod_customized"
            "navigation_bar" -> "settings/appearance/navigation_bar"

            "playback" -> "settings/player"
            "ytdlp" -> "settings/player/ytdlp"
            "sources" -> "settings/sources"
            "applemusic" -> "settings/applemusic"
            "jiosaavn" -> "settings/jiosaavn"
            "deezer" -> "settings/deezer"
            "lyrics" -> "settings/lyrics"
            "lyrics_providers" -> "settings/lyrics/providers"
            "lyrics_romanisation" -> "settings/lyrics/romanisation"
            "content" -> "settings/content"
            "behavior" -> "settings/privacy"
            "integration" -> "settings/integration"
            "internet" -> "settings/internet"
            "storage" -> "settings/storage"
            "downloads" -> "settings/downloads"
            "backup_restore" -> "settings/backup_restore"
            "developer_options" -> "settings/misc"
            "logcat" -> "settings/logcat"
            "music_together" -> "settings/music_together"
            "about" -> "settings/about"
            "discord" -> "settings/discord"
            "discord_experimental" -> "settings/discord/experimental"
            "tidal" -> "settings/tidal"
            "qobuz" -> "settings/qobuz"
            "telegram" -> "settings/telegram"
            "lastfm" -> "settings/lastfm"
            "ai_integration" -> "settings/ai_integration"
            "language_packs" -> "settings/language_packs"
            "po_token" -> PO_TOKEN_ROUTE
            else -> return null
        }

    val supportsScroll =
        ownerKey !in
            setOf(
                "developer_options",
                "about",
                "po_token",
                "account",
                "logcat",
                "music_together",
            )
    return if (!supportsScroll || scrollKey.isNullOrBlank()) route else "$route?scrollTo=$scrollKey"
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    latestVersionName: String,
    onClearUpdateBadge: () -> Unit = {},
) {
    val context = LocalContext.current
    val isAndroid12OrLater = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val storagePermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    val notificationPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }

    var isStorageGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, storagePermission) == PackageManager.PERMISSION_GRANTED,
        )
    }

    var isNotificationGranted by remember {
        mutableStateOf(
            notificationPermission == null ||
                ContextCompat.checkSelfPermission(context, notificationPermission) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            isStorageGranted = result[storagePermission] == true || isStorageGranted
            if (notificationPermission != null) {
                isNotificationGranted = result[notificationPermission] == true || isNotificationGranted
            }
        }

    var searchQuery by remember { mutableStateOf("") }
    val shouldShowPermissionHint = !isStorageGranted || !isNotificationGranted
    val hasUpdate =
        BuildConfig.UPDATER_AVAILABLE &&
            Updater.isUpdateAvailable(latestVersionName, BuildConfig.VERSION_NAME)
    var isUpdateDismissed by remember { mutableStateOf(false) }
    val allSettingsGroups = buildSettingsGroups(navController, isAndroid12OrLater, hasUpdate, context)

    val filteredChildResults = remember(searchQuery, allSettingsGroups) {
        if (searchQuery.isBlank()) {
            emptyList()
        } else {
            SettingsSearch.search(
                groups = allSettingsGroups,
                rawQuery = searchQuery,
                routeFor = { parentKey, scrollKey -> searchableSettingsRoute(parentKey, scrollKey) },
            )
        }
    }
    val filteredGroups = remember(allSettingsGroups) {
        allSettingsGroups.map { group ->
            group.copy(items = group.items.filterNot(SettingsItem::hidden))
        }.filter { it.items.isNotEmpty() }
    }

    val settingsDialogShowing = rememberSettingsDialogHostState()

    val glassHeader = rememberGlassScreenHeader()
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    CompositionLocalProvider(LocalSettingsDialogShowing provides settingsDialogShowing) {
        Scaffold(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(

                        if (settingsDialogShowing.value) {
                            Modifier.blur(10.dp)
                        } else {
                            Modifier
                        },
                    ),
            containerColor = glassAwareSurface(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { _ ->
            Box(modifier = Modifier.fillMaxSize()) {

                val playerAwareBottomPadding =
                    LocalPlayerAwareWindowInsets.current
                        .only(WindowInsetsSides.Bottom)
                        .asPaddingValues()
                        .calculateBottomPadding()
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .fillMaxSize()

                            .glassHeaderSource(glassHeader)
                            .windowInsetsPadding(
                                LocalPlayerAwareWindowInsets.current.only(
                                    WindowInsetsSides.Horizontal,
                                ),
                            ),
                    contentPadding =
                        PaddingValues(

                            top = systemBarsTopPadding + AppBarHeight + 8.dp,
                            bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding,
                        ),
                ) {
            if (hasUpdate && !isUpdateDismissed && searchQuery.isBlank()) {
                item(key = "update", contentType = "settings_banner") {
                    SettingsUpdateBanner(
                        latestVersion = latestVersionName,
                        onClick = { navController.navigate("settings/update") },
                        onDismiss = { isUpdateDismissed = true },
                        modifier =
                            Modifier
                                .padding(horizontal = SettingsDimensions.ScreenHorizontalPadding)
                                .padding(bottom = SettingsDimensions.SectionSpacing),
                    )
                }
            }

            if (shouldShowPermissionHint && searchQuery.isBlank()) {
                item(key = "permission", contentType = "settings_banner") {
                    SettingsPermissionBanner(
                        onRequestPermission = {
                            val toRequest =
                                buildList {
                                    if (!isStorageGranted) add(storagePermission)
                                    if (!isNotificationGranted && notificationPermission != null) {
                                        add(notificationPermission)
                                    }
                                }
                            if (toRequest.isNotEmpty()) {
                                permissionLauncher.launch(toRequest.toTypedArray())
                            }
                        },
                        modifier =
                            Modifier
                                .padding(horizontal = SettingsDimensions.ScreenHorizontalPadding)
                                .padding(bottom = SettingsDimensions.SectionSpacing),
                    )
                }
            }

            item(key = "search_bar", contentType = "search_bar") {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.search_settings),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.search),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                    modifier = Modifier
                        .padding(horizontal = SettingsDimensions.SegmentedGroupHorizontalPadding)
                        .fillMaxWidth(),
                )
            }

            item(key = "search_spacing", contentType = "spacing") {
                Spacer(modifier = Modifier.height(SettingsDimensions.SectionSpacing))
            }

            if (searchQuery.isNotBlank() && filteredChildResults.isNotEmpty()) {
                itemsIndexed(
                    items = filteredChildResults,
                    key = { index, result -> result.parentKey + ":" + result.title + ":" + index },
                    contentType = { _, _ -> "search_result" },
                ) { _, result ->
                    SettingsSearchResultItem(
                        result = result,
                        onClick = {
                            result.parentRoute?.let(navController::navigate) ?: result.onClick()
                        },
                        modifier = Modifier.padding(
                            horizontal = SettingsDimensions.SegmentedGroupHorizontalPadding,
                            vertical = 4.dp,
                        ),
                    )
                }
            } else if (searchQuery.isNotBlank()) {
                item(key = "no_results") {
                    Text(
                        text = stringResource(R.string.no_results_found),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = SettingsDimensions.SegmentedGroupHorizontalPadding,
                            vertical = 16.dp,
                        ),
                    )
                }
            } else {
                filteredGroups.forEachIndexed { groupIndex, group ->
                    if (groupIndex > 0) {
                        item(
                            key = "settings_group_spacing_$groupIndex",
                            contentType = "settings_group_spacing",
                        ) {
                            Spacer(modifier = Modifier.height(SettingsDimensions.SectionSpacing))
                        }
                    }

                    itemsIndexed(
                        items = group.items,
                        key = { _, item -> item.key },
                        contentType = { _, _ -> "settings_segment" },
                    ) { index, settingsItem ->
                        SettingsSegmentedItem(
                            item = settingsItem,
                            index = index,
                            count = group.items.size,
                            modifier =
                                Modifier
                                    .padding(horizontal = SettingsDimensions.SegmentedGroupHorizontalPadding)
                                    .padding(
                                        bottom =
                                            if (index < group.items.lastIndex) {
                                                SettingsDimensions.SegmentedItemGap
                                            } else {
                                                0.dp
                                            },
                                    ),
                        )
                    }
                }
            }
        }

                SettingsHomeStyleHeader(
                    glassHeader = glassHeader,
                    listState = listState,
                    onBack = navController::navigateUp,
                    onBackLongClick = navController::backToMain,
                    onSearch = {
                        coroutineScope.launch {
                            listState.animateScrollToItem(0)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun BoxScope.SettingsHomeStyleHeader(
    glassHeader: GlassScreenHeader,
    listState: LazyListState,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
    onSearch: () -> Unit,
) {
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    ScreenHeaderHaze(
        hazeState = glassHeader.haze,
        systemBarsTopPadding = systemBarsTopPadding,
    )

    Box(
        modifier =
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = systemBarsTopPadding)
                .height(AppBarHeight),
    ) {

        Text(
            text = stringResource(R.string.settings),
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            modifier = Modifier.align(Alignment.Center),
        )

        val backdrop = glassHeader.backdrop
        if (backdrop != null) {
            LiquidGlassIconButton(
                backdrop = backdrop,
                painter = painterResource(R.drawable.arrow_back),
                contentDescription = stringResource(R.string.back_button_desc),
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 12.dp),
                onClick = onBack,
            )
        } else {
            IconButton(
                onClick = onBack,
                onLongClick = onBackLongClick,
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 12.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = stringResource(R.string.back_button_desc),
                )
            }
        }

        val isScrolling by remember {
            derivedStateOf {
                listState.firstVisibleItemIndex > 0 ||
                    listState.firstVisibleItemScrollOffset > 200
            }
        }
        AnimatedVisibility(
            visible = isScrolling,
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(140)),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            if (backdrop != null) {
                LiquidGlassIconButton(
                    backdrop = backdrop,
                    painter = painterResource(R.drawable.search),
                    contentDescription = stringResource(R.string.search),
                    modifier = Modifier.padding(end = 12.dp),
                    onClick = onSearch,
                )
            } else {
                FrostedHeaderPill(modifier = Modifier.padding(end = 8.dp), plain = true) {
                    IconButton(
                        onClick = onSearch,
                        onLongClick = {},
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.search),
                            contentDescription = stringResource(R.string.search),
                        )
                    }
                }
            }
        }
    }
}
