/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.ArchiveTuneCanvasKey
import moe.rukamori.archivetune.constants.AudioNormalizationKey
import moe.rukamori.archivetune.constants.AudioOffload
import moe.rukamori.archivetune.constants.AutoDownloadOnLikeKey
import moe.rukamori.archivetune.constants.AutoSkipNextOnErrorKey
import moe.rukamori.archivetune.constants.AutoStartOnBluetoothKey
import moe.rukamori.archivetune.constants.CrossfadeEnabledKey
import moe.rukamori.archivetune.constants.CrossfadeGaplessKey
import moe.rukamori.archivetune.constants.DisableBlurKey
import moe.rukamori.archivetune.constants.DynamicThemeKey
import moe.rukamori.archivetune.constants.LowDataModeKey
import moe.rukamori.archivetune.constants.PauseOnDeviceMuteKey
import moe.rukamori.archivetune.constants.PermanentShuffleKey
import moe.rukamori.archivetune.constants.PersistentQueueKey
import moe.rukamori.archivetune.constants.PureBlackKey
import moe.rukamori.archivetune.constants.SeekExtraSeconds
import moe.rukamori.archivetune.constants.SkipSilenceKey
import moe.rukamori.archivetune.constants.StopMusicOnTaskClearKey
import moe.rukamori.archivetune.constants.TidalArtworkFallbackEnabledKey
import moe.rukamori.archivetune.constants.WakelockKey
import moe.rukamori.archivetune.utils.rememberPreference

@Composable
private fun SearchResultSwitch(
    key: androidx.datastore.preferences.core.Preferences.Key<Boolean>,
    defaultValue: Boolean,
) {
    val (checked, onCheckedChange) = rememberPreference(key, defaultValue)
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
fun buildSettingsGroups(
    navController: NavController,
    isAndroid12OrLater: Boolean,
    hasUpdate: Boolean,
    context: Context,
): List<SettingsGroup> {
    val account =
        SettingsItem(
            key = "account",
            icon = painterResource(R.drawable.account),
            title = stringResource(R.string.account),
            subtitle = stringResource(R.string.settings_account_subtitle),
            accentColor = MaterialTheme.colorScheme.primary,
            keywords = listOf("account", "profile", "youtube", "sign in", "login", "logout"),
            onClick = { navController.navigate("settings/account") },
        )
    val stats =
        SettingsItem(
            key = "stats",
            icon = painterResource(R.drawable.stats),
            title = stringResource(R.string.settings_stats_title),
            subtitle = stringResource(R.string.settings_stats_subtitle),
            accentColor = MaterialTheme.colorScheme.primary,
            keywords = listOf("stats", "statistics", "listening", "history", "top", "most played", "time"),
            onClick = { navController.navigate("stats") },
        )
    val appearance =
        SettingsItem(
            key = "appearance",
            icon = painterResource(R.drawable.palette),
            title = stringResource(R.string.appearance),
            subtitle = stringResource(R.string.settings_appearance_subtitle),
            accentColor = MaterialTheme.colorScheme.secondary,
            keywords = listOf("appearance", "theme", "dark", "light", "color", "palette", "style", "design"),
            onClick = { navController.navigate("settings/appearance") },
            children = listOf(
                SettingsChild("Dynamic theme", "dynamic_theme", listOf("dynamic theme", "material you", "material you", "dynamic color")) { SearchResultSwitch(DynamicThemeKey, false) },
                SettingsChild("Dark theme", "dark_theme", listOf("dark", "dark theme", "night", "amoled")),
                SettingsChild("Pure black", "pure_black", listOf("pure black", "amoled", "oled", "black background")) { SearchResultSwitch(PureBlackKey, false) },
                SettingsChild("Color palette", "color_palette", listOf("color palette", "accent color", "theme color")),
                SettingsChild("App icon", "app_icon", listOf("icon", "app icon", "icon pack")),
                SettingsChild("Disable blur", "disable_blur", listOf("blur", "disable blur", "no blur", "performance")) { SearchResultSwitch(DisableBlurKey, false) },
                SettingsChild("Blur intensity", "blur_intensity", listOf("blur intensity", "blur amount", "blur level")),
                SettingsChild("Font preference", "font_preference", listOf("font", "font style", "typography")),
                SettingsChild("Player design style", "player_design_style", listOf("player design", "player layout", "player style")),
                SettingsChild("Player background style", "player_background_style", listOf("player background", "player bg", "background style")),
                SettingsChild("Lyrics background style", "lyrics_background_style", listOf("lyrics background", "lyrics bg")),
                SettingsChild("Mini player background style", "mini_player_background_style", listOf("mini player", "mini player background")),
                SettingsChild("Player buttons style", "player_buttons_style", listOf("player buttons", "button style", "controls style")),
                SettingsChild("Player slider style", "player_slider_style", listOf("player slider", "slider style", "progress bar")),
                SettingsChild("Swipe sensitivity", "swipe_sensitivity", listOf("swipe", "gesture", "sensitivity")),
                SettingsChild("Navigation bar style", "navigation_bar_style", listOf("navigation bar", "nav bar", "bottom bar")),
                SettingsChild("Default open tab", "default_open_tab", listOf("default tab", "home tab", "start page")),
                SettingsChild("Grid layout", "grid_layout", listOf("grid", "layout", "list view", "artist grid")),
                SettingsChild("Language", "app_language", listOf("language", "app language", "locale")),
                SettingsChild("Color source", "color_source", listOf("color", "color source", "dynamic color", "material you")),
                SettingsChild("Default open tab", "default_open_tab", listOf("default tab", "home tab", "start page", "open tab")),
            ),
        )
    val playback =
        SettingsItem(
            key = "playback",
            icon = painterResource(R.drawable.music_note),
            title = stringResource(R.string.settings_playback_title),
            subtitle = stringResource(R.string.settings_playback_subtitle),
            accentColor = MaterialTheme.colorScheme.tertiary,
            keywords = listOf("playback", "player", "audio", "quality", "equalizer", "eq", "volume", "crossfade", "gapless", "flac", "lossless", "hi-res", "sample rate", "bitrate"),
            onClick = { navController.navigate("settings/player") },
            children = listOf(
                SettingsChild("Low data mode", "low_data_mode", listOf("data", "data saver", "low quality", "data mode")) { SearchResultSwitch(LowDataModeKey, true) },
                SettingsChild("History duration", "history_duration", listOf("history", "duration", "recent", "queue length")),
                SettingsChild("Crossfade", "crossfade", listOf("crossfade", "fade", "transition", "mix", "blend")) { SearchResultSwitch(CrossfadeEnabledKey, false) },
                SettingsChild("Crossfade gapless", "crossfade_gapless", listOf("crossfade gapless", "gapless crossfade", "seamless crossfade")) { SearchResultSwitch(CrossfadeGaplessKey, true) },
                SettingsChild("Skip silence", "skip_silence", listOf("silence", "skip silence", "blank", "quiet")) { SearchResultSwitch(SkipSilenceKey, false) },
                SettingsChild("Audio normalization", "audio_normalization", listOf("normalization", "loudness", "normalize", "volume level")) { SearchResultSwitch(AudioNormalizationKey, true) },
                SettingsChild("Audio offload", "audio_offload", listOf("offload", "audio offload", "hardware decoder")) { SearchResultSwitch(AudioOffload, false) },
                SettingsChild("Seek seconds add-up", "seek_seconds", listOf("seek", "skip", "forward", "rewind", "seconds")) { SearchResultSwitch(SeekExtraSeconds, false) },
                SettingsChild("Pause on device mute", "pause_mute", listOf("mute", "pause mute", "headphone", "silence detect")) { SearchResultSwitch(PauseOnDeviceMuteKey, false) },
                SettingsChild("Device mute recovery volume", "device_mute_recovery_volume", listOf("recovery volume", "mute recovery", "volume restore")),
                SettingsChild("Auto start on Bluetooth", "bluetooth_auto_start", listOf("bluetooth", "auto start", "auto play", "connect")) { SearchResultSwitch(AutoStartOnBluetoothKey, false) },
                SettingsChild("ArchiveTune Canvas", "archivetune_canvas", listOf("canvas", "animated artwork", "motion artwork", "live artwork")) { SearchResultSwitch(ArchiveTuneCanvasKey, true) },
                SettingsChild("Tidal artwork fallback", "tidal_artwork_fallback", listOf("tidal artwork", "artwork fallback", "tidal cover", "hi-res artwork")) { SearchResultSwitch(TidalArtworkFallbackEnabledKey, true) },
                SettingsChild("Persistent queue", "persistent_queue", listOf("queue", "persistent", "save queue", "resume")) { SearchResultSwitch(PersistentQueueKey, true) },
                SettingsChild("Permanent shuffle", "permanent_shuffle", listOf("shuffle", "random", "permanent")) { SearchResultSwitch(PermanentShuffleKey, false) },
                SettingsChild("Auto download on like", "auto_download_like", listOf("auto download", "like", "download liked")) { SearchResultSwitch(AutoDownloadOnLikeKey, false) },
                SettingsChild("Auto skip on error", "auto_skip_error", listOf("skip", "error", "auto skip", "failed")) { SearchResultSwitch(AutoSkipNextOnErrorKey, false) },
                SettingsChild("Stop music on task clear", "stop_task_clear", listOf("stop", "task clear", "background", "close app")) { SearchResultSwitch(StopMusicOnTaskClearKey, false) },
                SettingsChild("Wakelock", "wakelock", listOf("wakelock", "wake lock", "keep awake", "cpu")) { SearchResultSwitch(WakelockKey, false) },
                SettingsChild("Artist separators", "artist_separators", listOf("artist", "separator", "split", "featuring")),
                SettingsChild("Manage playlist tags", "manage_playlist_tags", listOf("playlist tags", "tag management", "organize playlists")),
                SettingsChild("External downloader", "external_downloader", listOf("external downloader", "download app", "custom downloader")),
                SettingsChild("External downloader package", "external_downloader_package", listOf("downloader package", "downloader app name")),
            ),
        )
    val sources =
        SettingsItem(
            key = "sources",
            icon = painterResource(R.drawable.provider_tidal),
            title = stringResource(R.string.source_settings),
            subtitle = stringResource(R.string.source_settings_subtitle),
            accentColor = MaterialTheme.colorScheme.tertiary,
            keywords = listOf("source", "music source", "youtube music", "tidal", "qobuz", "provider", "streaming", "telegram", "telegram channel", "flac", "lossless", "private channel"),
            onClick = { navController.navigate("settings/sources") },
            children = listOf(
                SettingsChild("YouTube Music", "youtube_music", listOf("youtube", "youtube music", "yt music")),
                SettingsChild("Qobuz", "qobuz", listOf("qobuz", "hires", "hi-res", "flac", "lossless", "cd quality")),
                SettingsChild("Tidal", "tidal", listOf("tidal", "lossless", "hifi", "master", "mq")),
            ),
        )
    val lyrics =
        SettingsItem(
            key = "lyrics",
            icon = painterResource(R.drawable.lyrics),
            title = stringResource(R.string.lyrics),
            subtitle = stringResource(R.string.settings_lyrics_subtitle),
            accentColor = MaterialTheme.colorScheme.secondary,
            keywords = listOf("lyrics", "lyric", "subtitle", "text", "sing along", "lrc"),
            onClick = { navController.navigate("settings/lyrics") },
            children = listOf(
                SettingsChild("Lyrics provider", "lyrics_provider", listOf("lyrics provider", "source", "lrclib")),
                SettingsChild("Show lyrics", "show_lyrics", listOf("show lyrics", "display lyrics", "lyrics toggle")),
                SettingsChild("Lyrics font size", "lyrics_font_size", listOf("font size", "lyrics size", "text size")),
                SettingsChild("Lyrics animations", "lyrics_animations", listOf("animation", "animated lyrics", "lyrics effect")),
            ),
        )
    val content =
        SettingsItem(
            key = "content",
            icon = painterResource(R.drawable.language),
            title = stringResource(R.string.content),
            subtitle = stringResource(R.string.settings_content_subtitle),
            accentColor = MaterialTheme.colorScheme.primary,
            keywords = listOf("content", "language", "locale", "country", "region", "app language", "explicit", "age restricted", "age", "mature", "video"),
            onClick = { navController.navigate("settings/content") },
            children = listOf(
                SettingsChild("Content language", "content_language", listOf("language", "content language", "locale", "country")),
                SettingsChild("Content country", "content_country", listOf("country", "region", "content country")),
                SettingsChild("Hide explicit", "hide_explicit", listOf("explicit", "age", "mature", "age restricted", "clean")),
                SettingsChild("Enable video", "enable_video", listOf("video", "music video", "mv")),
            ),
        )
    val languagePacks =
        SettingsItem(
            key = "language_packs",
            icon = painterResource(R.drawable.translate),
            title = stringResource(R.string.language_packs),
            subtitle = stringResource(R.string.settings_language_packs_subtitle),
            accentColor = MaterialTheme.colorScheme.secondary,
            keywords = listOf("language pack", "translation", "translate", "localization", "i18n"),
            onClick = { navController.navigate("settings/language_packs") },
        )
    val behavior =
        SettingsItem(
            key = "behavior",
            icon = painterResource(R.drawable.swipe),
            title = stringResource(R.string.settings_behavior_title),
            subtitle = stringResource(R.string.settings_behavior_subtitle),
            accentColor = MaterialTheme.colorScheme.primary,
            keywords = listOf("behavior", "privacy", "swipe", "gesture", "history", "cache", "data"),
            onClick = { navController.navigate("settings/privacy") },
            children = listOf(
                SettingsChild("Pause listen history", "pause_listen_history", listOf("pause listen", "stop history", "private listening")),
                SettingsChild("Clear listen history", "clear_listen_history", listOf("clear history", "delete history", "reset history")),
                SettingsChild("Pause search history", "pause_search_history", listOf("pause search", "stop search history", "private search")),
                SettingsChild("Clear search history", "clear_search_history", listOf("clear search", "delete search", "reset search")),
                SettingsChild("Haptics", "haptics", listOf("haptic", "vibration", "haptic feedback", "vibrate")),
                SettingsChild("Disable screenshot", "disable_screenshot", listOf("screenshot", "screen capture", "privacy", "no screenshot")),
            ),
        )
    val integration =
        SettingsItem(
            key = "integration",
            icon = painterResource(R.drawable.auto_awesome),
            title = stringResource(R.string.integration),
            subtitle = stringResource(R.string.settings_integration_subtitle),
            accentColor = MaterialTheme.colorScheme.secondary,
            keywords = listOf("integration", "lastfm", "last.fm", "scrobble", "scrobbling", "discord"),
            onClick = { navController.navigate("settings/integration") },
            children = listOf(
                SettingsChild("Last.fm scrobbling", "lastfm_scrobbling", listOf("lastfm", "last.fm", "scrobble", "scrobbling", "listens")),
                SettingsChild("Discord rich presence", "discord_presence", listOf("discord", "rich presence", "status", "now playing")),
                SettingsChild("ListenBrainz", "listenbrainz", listOf("listenbrainz", "listen brainz", "scrobble")),
            ),
        )
    val aiIntegration =
        SettingsItem(
            key = "ai_integration",
            icon = painterResource(R.drawable.ai),
            title = stringResource(R.string.ai_integration),
            subtitle = stringResource(R.string.ai_integration_desc),
            accentColor = MaterialTheme.colorScheme.secondary,
            keywords = listOf("ai", "artificial intelligence", "chatgpt", "openai", "gemini", "llm", "ai integration"),
            onClick = { navController.navigate("settings/ai_integration") },
        )
    val internet =
        SettingsItem(
            key = "internet",
            icon = painterResource(R.drawable.wifi_proxy),
            title = stringResource(R.string.internet),
            subtitle = stringResource(R.string.settings_internet_subtitle),
            accentColor = MaterialTheme.colorScheme.tertiary,
            keywords = listOf("internet", "proxy", "vpn", "network", "wifi", "connection", "traffic"),
            onClick = { navController.navigate("settings/internet") },
            children = listOf(
                SettingsChild("Proxy", "proxy_settings", listOf("proxy", "http proxy", "socks", "vpn")),
                SettingsChild("Enable tor", "enable_tor", listOf("tor", "onion", "anonymous")),
                SettingsChild("Download speed limit", "download_speed_limit", listOf("speed", "limit", "throttle", "bandwidth", "download speed")),
            ),
        )
    val poToken =
        SettingsItem(
            key = "po_token",
            icon = painterResource(R.drawable.token),
            title = stringResource(R.string.po_token_generation),
            subtitle = stringResource(R.string.settings_po_token_subtitle),
            accentColor = MaterialTheme.colorScheme.secondary,
            keywords = listOf("po token", "potoken", "botguard", "youtube token", "playability"),
            onClick = { navController.navigate(PO_TOKEN_ROUTE) },
        )
    val storage =
        SettingsItem(
            key = "storage",
            icon = painterResource(R.drawable.storage),
            title = stringResource(R.string.storage),
            subtitle = stringResource(R.string.settings_storage_subtitle),
            accentColor = MaterialTheme.colorScheme.primary,
            keywords = listOf("storage", "download", "cache", "disk", "space", "memory", "path", "location", "export", "export songs", "local storage", "save songs"),
            onClick = { navController.navigate("settings/storage") },
            children = listOf(
                SettingsChild("Downloaded songs", "downloaded_songs", listOf("downloaded", "offline songs", "saved songs")),
                SettingsChild("Song cache size", "song_cache_size", listOf("cache size", "song cache", "memory", "download cache")),
                SettingsChild("Image cache size", "image_cache_size", listOf("image cache", "thumbnail cache", "artwork cache")),
                SettingsChild("Canvas cache", "canvas_cache", listOf("canvas cache", "motion artwork cache", "animated artwork storage")),
                SettingsChild("Storage folder", "storage_folder", listOf("storage path", "storage location", "storage directory")),
                SettingsChild("Download location", "download_location", listOf("download path", "location", "folder", "directory", "save to")),
                SettingsChild("Export downloaded songs", "export_downloaded_songs", listOf("export", "export songs", "save songs", "local storage", "file")),
            ),
        )
    val backupRestore =
        SettingsItem(
            key = "backup_restore",
            icon = painterResource(R.drawable.backup),
            title = stringResource(R.string.backup_restore),
            subtitle = stringResource(R.string.settings_backup_restore_subtitle),
            accentColor = MaterialTheme.colorScheme.primary,
            keywords = listOf("backup", "restore", "export", "import", "data", "save"),
            onClick = { navController.navigate("settings/backup_restore") },
            children = listOf(
                SettingsChild("Backup", "backup", listOf("backup", "save data", "export backup")),
                SettingsChild("Restore", "restore", listOf("restore", "import", "recover")),
            ),
        )
    val developerOptions =
        SettingsItem(
            key = "developer_options",
            icon = painterResource(R.drawable.experiment),
            title = stringResource(R.string.settings_developer_options_title),
            subtitle = stringResource(R.string.settings_developer_options_subtitle),
            accentColor = MaterialTheme.colorScheme.tertiary,
            keywords = listOf("developer", "debug", "experimental", "advanced", "logcat", "dev"),
            onClick = { navController.navigate("settings/misc") },
            children = listOf(
                SettingsChild("Logcat", "logcat", listOf("logcat", "log", "debug log")),
                SettingsChild("Update channel", "update_channel", listOf("update channel", "canary", "stable", "beta")),
            ),
        )
    val defaultLinks =
        if (isAndroid12OrLater) {
            SettingsItem(
                key = "default_links",
                icon = painterResource(R.drawable.link),
                title = stringResource(R.string.default_links),
                subtitle = stringResource(R.string.open_supported_links),
                accentColor = MaterialTheme.colorScheme.secondary,
                keywords = listOf("default links", "links", "urls", "deep link", "supported links"),
                onClick = {
                    try {
                        val intent =
                            Intent(
                                Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        when (e) {
                            is ActivityNotFoundException,
                            is SecurityException,
                            -> {
                                Toast
                                    .makeText(
                                        context,
                                        R.string.open_app_settings_error,
                                        Toast.LENGTH_LONG,
                                    ).show()
                            }

                            else -> {
                                Toast
                                    .makeText(
                                        context,
                                        R.string.open_app_settings_error,
                                        Toast.LENGTH_LONG,
                                    ).show()
                            }
                        }
                    }
                },
            )
        } else {
            null
        }
    val updates =
        if (BuildConfig.UPDATER_AVAILABLE) {
            SettingsItem(
                key = "updates",
                icon = painterResource(R.drawable.update),
                title = stringResource(R.string.updates),
                keywords = listOf("update", "upgrade", "version", "new version", "release", "canary", "stable"),
                subtitle =
                    if (hasUpdate) {
                        stringResource(R.string.new_version_available)
                    } else {
                        stringResource(R.string.settings_updates_subtitle)
                    },
                showUpdateIndicator = hasUpdate,
                accentColor =
                    if (hasUpdate) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                badge = if (hasUpdate) "v${BuildConfig.VERSION_NAME}" else BuildConfig.VERSION_NAME,
                onClick = { navController.navigate("settings/update") },
            )
        } else {
            null
        }
    val about =
        SettingsItem(
            key = "about",
            icon = painterResource(R.drawable.info),
            title = stringResource(R.string.about),
            subtitle = stringResource(R.string.settings_about_subtitle),
            accentColor = MaterialTheme.colorScheme.secondary,
            keywords = listOf("about", "info", "version", "license", "credits", "contributors", "changelog"),
            onClick = { navController.navigate("settings/about") },
            children = listOf(
                SettingsChild("Version", "about_version", listOf("version", "build")),
                SettingsChild("Changelog", "about_changelog", listOf("changelog", "changes", "release notes", "what's new")),
                SettingsChild("License", "about_license", listOf("license", "gpl", "open source")),
            ),
        )

    return listOf(
        SettingsGroup(
            title = stringResource(R.string.settings),
            items = listOf(account, stats),
        ),
        SettingsGroup(
            title = stringResource(R.string.settings_section_player_content),
            items = listOf(appearance, playback, sources, lyrics, languagePacks, content, behavior),
        ),
        SettingsGroup(
            title = stringResource(R.string.integration),
            items = listOf(integration, aiIntegration, internet, poToken),
        ),
        SettingsGroup(
            title = stringResource(R.string.storage),
            items = listOf(storage, backupRestore),
        ),
        SettingsGroup(
            title = stringResource(R.string.about),
            items =
                buildList {
                    add(developerOptions)
                    defaultLinks?.let(::add)
                    updates?.let(::add)
                    add(about)
                },
        ),
    )
}
