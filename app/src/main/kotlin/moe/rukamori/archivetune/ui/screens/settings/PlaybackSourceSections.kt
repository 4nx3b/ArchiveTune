/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Streaming-source sections rendered inline inside Player & Audio settings:
 *  - a single lyrics-style drag-to-reorder "preferred sources" picker (top = preferred),
 *  - a common section (YouTube history sync),
 *  - and per-source sections (YouTube note, Tidal, Qobuz).
 *
 * Account login / instance / API management lives in the Integration section, not here.
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.audiosource.AudioSourceConfig
import moe.rukamori.archivetune.constants.AudioSourceOrderKey
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.constants.DabMusicBaseUrlKey
import moe.rukamori.archivetune.constants.DabMusicCfClearanceKey
import moe.rukamori.archivetune.constants.DabMusicEmailKey
import moe.rukamori.archivetune.constants.DabMusicEnabledKey
import moe.rukamori.archivetune.constants.DabMusicPasswordKey
import moe.rukamori.archivetune.constants.DabMusicSessionCookieKey
import moe.rukamori.archivetune.constants.DeezerAudioQuality
import moe.rukamori.archivetune.constants.DeezerAudioQualityKey
import moe.rukamori.archivetune.constants.DeezerEnabledKey
import moe.rukamori.archivetune.constants.QobuzAudioQuality
import moe.rukamori.archivetune.constants.QobuzAudioQualityKey
import moe.rukamori.archivetune.constants.QobuzEnabledKey
import moe.rukamori.archivetune.constants.TidalAccountFirstKey
import moe.rukamori.archivetune.constants.TidalAnimatedCoversEnabledKey
import moe.rukamori.archivetune.constants.TidalAudioQuality
import moe.rukamori.archivetune.constants.TidalAudioQualityKey
import moe.rukamori.archivetune.constants.TidalEnabledKey
import androidx.compose.runtime.LaunchedEffect
import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.constants.AudioQualityKey
import moe.rukamori.archivetune.constants.PlayerStreamClient
import moe.rukamori.archivetune.constants.PlayerStreamClientKey
import moe.rukamori.archivetune.dabmusic.DabMusicAudioProvider
import moe.rukamori.archivetune.dabmusic.DabMusicCloudflareBypassDialog
import moe.rukamori.archivetune.innertube.utils.hasYouTubeLoginCookie
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.EnumListPreference
import moe.rukamori.archivetune.ui.component.InfoLabel
import moe.rukamori.archivetune.ui.component.ListPreference
import moe.rukamori.archivetune.ui.component.EditTextPreference
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import android.widget.Toast

private fun AudioSourceType.displayName(context: android.content.Context): String =
    when (this) {
        AudioSourceType.TIDAL -> context.getString(R.string.source_tidal)
        AudioSourceType.QOBUZ -> context.getString(R.string.source_qobuz)
        AudioSourceType.DEEZER -> context.getString(R.string.source_deezer)
        AudioSourceType.DABMUSIC -> context.getString(R.string.source_dabmusic)
        AudioSourceType.YOUTUBE -> context.getString(R.string.source_youtube)
    }

private fun AudioSourceType.iconRes(): Int =
    when (this) {
        AudioSourceType.TIDAL -> R.drawable.provider_tidal
        AudioSourceType.QOBUZ -> R.drawable.provider_qobuz
        AudioSourceType.DEEZER -> R.drawable.provider_deezer
        AudioSourceType.DABMUSIC -> R.drawable.provider_dabmusic
        AudioSourceType.YOUTUBE -> R.drawable.play
    }

/**
 * Renders all streaming-source preference groups inline in the caller's scrolling Column. Meant to
 * be called from [PlayerSettings]. Emits, in order: the common "Sources" group (preferred-source
 * picker + YouTube history sync), then YouTube, Tidal and Qobuz specific groups.
 */
@Composable
fun PlaybackSourceSections(navController: NavController) {
    val context = LocalContext.current

    val (sourceOrderRaw, onSourceOrderChange) = rememberPreference(AudioSourceOrderKey, "")
    val (tidalEnabled, onTidalEnabledChange) = rememberPreference(TidalEnabledKey, true)
    val (qobuzEnabled, onQobuzEnabledChange) = rememberPreference(QobuzEnabledKey, false)
    val (deezerEnabled, onDeezerEnabledChange) = rememberPreference(DeezerEnabledKey, false)
    val (dabMusicEnabled, onDabMusicEnabledChange) = rememberPreference(DabMusicEnabledKey, false)
    val (dabMusicBaseUrl, onDabMusicBaseUrlChange) = rememberPreference(DabMusicBaseUrlKey, "")
    val (dabMusicEmail, onDabMusicEmailChange) = rememberPreference(DabMusicEmailKey, "")
    val (dabMusicPassword, onDabMusicPasswordChange) = rememberPreference(DabMusicPasswordKey, "")
    val (dabMusicSessionCookie, onDabMusicSessionCookieChange) =
        rememberPreference(DabMusicSessionCookieKey, "")
    val (dabMusicCfClearance, onDabMusicCfClearanceChange) =
        rememberPreference(DabMusicCfClearanceKey, "")
    val (deezerQuality, onDeezerQualityChange) =
        rememberEnumPreference(DeezerAudioQualityKey, DeezerAudioQuality.FLAC)

    val (tidalAccountFirst, onTidalAccountFirstChange) = rememberPreference(TidalAccountFirstKey, true)
    val (audioQuality, onAudioQualityChange) =
        rememberEnumPreference(TidalAudioQualityKey, TidalAudioQuality.FLAC)

    // YouTube-specific playback state
    val (ytAudioQuality, onYtAudioQualityChange) =
        rememberEnumPreference(AudioQualityKey, defaultValue = AudioQuality.AUTO)
    val (playerStreamClient, onPlayerStreamClientChange) =
        rememberEnumPreference(PlayerStreamClientKey, defaultValue = PlayerStreamClient.WEB_REMIX)
    val playerStreamClients =
        remember { PlayerStreamClient.entries }
    val selectedPlayerStreamClient =
        if (playerStreamClient in playerStreamClients) playerStreamClient
        else PlayerStreamClient.WEB_REMIX

    val (qobuzQuality, onQobuzQualityChange) =
        rememberEnumPreference(QobuzAudioQualityKey, QobuzAudioQuality.FLAC)
    // The Tidal artwork-fetching toggle lives in Player Settings → Artwork (same key).
    val (animatedCovers, onAnimatedCoversChange) =
        rememberPreference(TidalAnimatedCoversEnabledKey, false)

    val sourceOrder =
        remember(sourceOrderRaw) {
            AudioSourceConfig.parseOrder(sourceOrderRaw.ifBlank { null })
        }

    fun isEnabled(source: AudioSourceType): Boolean =
        when (source) {
            AudioSourceType.TIDAL -> tidalEnabled
            AudioSourceType.QOBUZ -> qobuzEnabled
            AudioSourceType.DEEZER -> deezerEnabled
            AudioSourceType.DABMUSIC -> dabMusicEnabled
            AudioSourceType.YOUTUBE -> true
        }

    var showOrderDialog by rememberSaveable { mutableStateOf(false) }

    if (showOrderDialog) {
        SourceOrderDialog(
            initialOrder = sourceOrder,
            isEnabled = ::isEnabled,
            onDismiss = { showOrderDialog = false },
            onConfirm = { newOrder ->
                onSourceOrderChange(newOrder.joinToString(",") { it.name })
                showOrderDialog = false
            },
        )
    }

    val preferred = sourceOrder.firstOrNull { isEnabled(it) } ?: AudioSourceType.YOUTUBE

    PreferenceGroup(title = stringResource(R.string.playback_sources)) {
        item {
            PreferenceEntry(
                title = { Text(stringResource(R.string.preferred_sources)) },
                description = preferred.displayName(context),
                icon = { Icon(painterResource(preferred.iconRes()), null) },
                onClick = { showOrderDialog = true },
            )
        }
    }

    PreferenceGroup(title = stringResource(R.string.source_youtube)) {
        item {
            EnumListPreference(
                title = { Text(stringResource(R.string.audio_quality)) },
                icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                selectedValue = ytAudioQuality,
                onValueSelected = onYtAudioQualityChange,
                valueText = {
                    when (it) {
                        AudioQuality.HIGHEST -> stringResource(R.string.audio_quality_max)
                        AudioQuality.HIGH -> stringResource(R.string.audio_quality_high)
                        AudioQuality.AUTO -> stringResource(R.string.audio_quality_auto)
                        AudioQuality.LOW -> stringResource(R.string.audio_quality_low)
                    }
                },
            )
        }

        item {
            ListPreference(
                title = { Text(stringResource(R.string.player_stream_client)) },
                description = stringResource(R.string.player_stream_client_desc),
                icon = { Icon(painterResource(R.drawable.integration), null) },
                selectedValue = selectedPlayerStreamClient,
                values = playerStreamClients,
                onValueSelected = onPlayerStreamClientChange,
                valueText = {
                    when (it) {
                        PlayerStreamClient.WEB_REMIX ->
                            stringResource(R.string.player_stream_client_web_remix)
                        else -> stringResource(R.string.player_stream_client_web_remix)
                    }
                },
                valueDescription = {
                    when (it) {
                        PlayerStreamClient.WEB_REMIX ->
                            stringResource(R.string.player_stream_client_web_remix_desc)
                        else -> stringResource(R.string.player_stream_client_web_remix_desc)
                    }
                },
            )
        }

        item {
            PreferenceEntry(
                title = { Text(stringResource(R.string.mori_cipher_settings_title)) },
                description = stringResource(R.string.mori_cipher_settings_description),
                icon = { Icon(painterResource(R.drawable.security), null) },
                onClick = { navController.navigate("settings/player/chiper") },
            )
        }
    }

    PreferenceGroup(title = stringResource(R.string.tidal_specific)) {
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.tidal_enable)) },
                description = stringResource(R.string.tidal_enable_description),
                icon = { Icon(painterResource(R.drawable.provider_tidal), null) },
                checked = tidalEnabled,
                onCheckedChange = onTidalEnabledChange,
            )
        }

        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.tidal_account_first)) },
                description = stringResource(R.string.tidal_account_first_description),
                icon = { Icon(painterResource(R.drawable.token), null) },
                checked = tidalAccountFirst,
                onCheckedChange = onTidalAccountFirstChange,
                isEnabled = tidalEnabled,
            )
        }

        item {
            EnumListPreference(
                title = { Text(stringResource(R.string.tidal_audio_quality)) },
                icon = { Icon(painterResource(R.drawable.play), null) },
                selectedValue = audioQuality,
                onValueSelected = onAudioQualityChange,
                isEnabled = tidalEnabled,
                valueText = { quality ->
                    when (quality) {
                        TidalAudioQuality.AAC_320 -> stringResource(R.string.tidal_quality_aac_320)
                        TidalAudioQuality.FLAC -> stringResource(R.string.tidal_quality_flac)
                        TidalAudioQuality.HI_RES_LOSSLESS -> stringResource(R.string.tidal_quality_hires)
                    }
                },
            )
        }

        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.tidal_animated_covers)) },
                description = stringResource(R.string.tidal_animated_covers_description),
                checked = animatedCovers,
                onCheckedChange = onAnimatedCoversChange,
                isEnabled = tidalEnabled,
            )
        }

        item {
            PreferenceEntry(
                title = { Text(stringResource(R.string.tidal_manage_instances)) },
                description = stringResource(R.string.manage_in_integration),
                icon = { Icon(painterResource(R.drawable.integration), null) },
                onClick = { navController.navigate("settings/integration") },
            )
        }
    }

    PreferenceGroup(title = stringResource(R.string.qobuz_specific)) {
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.qobuz_enable)) },
                description = stringResource(R.string.qobuz_enable_description),
                icon = { Icon(painterResource(R.drawable.provider_qobuz), null) },
                checked = qobuzEnabled,
                onCheckedChange = onQobuzEnabledChange,
            )
        }

        item {
            EnumListPreference(
                title = { Text(stringResource(R.string.qobuz_audio_quality)) },
                icon = { Icon(painterResource(R.drawable.play), null) },
                selectedValue = qobuzQuality,
                onValueSelected = onQobuzQualityChange,
                isEnabled = qobuzEnabled,
                valueText = { quality ->
                    when (quality) {
                        QobuzAudioQuality.FLAC -> stringResource(R.string.qobuz_quality_flac)
                        QobuzAudioQuality.HI_RES -> stringResource(R.string.qobuz_quality_hires)
                        QobuzAudioQuality.MAX -> stringResource(R.string.qobuz_quality_max)
                    }
                },
            )
        }

        item {
            PreferenceEntry(
                title = { Text(stringResource(R.string.qobuz_manage_instances)) },
                description = stringResource(R.string.manage_in_integration),
                icon = { Icon(painterResource(R.drawable.integration), null) },
                onClick = { navController.navigate("settings/integration") },
            )
        }
    }

    PreferenceGroup(title = stringResource(R.string.deezer_specific)) {
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.deezer_enable)) },
                description = stringResource(R.string.deezer_enable_description),
                icon = { Icon(painterResource(R.drawable.provider_deezer), null) },
                checked = deezerEnabled,
                onCheckedChange = onDeezerEnabledChange,
            )
        }

        item {
            EnumListPreference(
                title = { Text(stringResource(R.string.deezer_audio_quality)) },
                icon = { Icon(painterResource(R.drawable.play), null) },
                selectedValue = deezerQuality,
                onValueSelected = onDeezerQualityChange,
                isEnabled = deezerEnabled,
                valueText = { quality ->
                    when (quality) {
                        DeezerAudioQuality.FLAC -> stringResource(R.string.deezer_quality_flac)
                        DeezerAudioQuality.MP3_320 -> stringResource(R.string.deezer_quality_mp3_320)
                        DeezerAudioQuality.MP3_128 -> stringResource(R.string.deezer_quality_mp3_128)
                    }
                },
            )
        }

    }

    PreferenceGroup(title = stringResource(R.string.dabmusic_specific)) {
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.dabmusic_enable)) },
                description = stringResource(R.string.dabmusic_enable_description),
                icon = { Icon(painterResource(R.drawable.provider_dabmusic), null) },
                checked = dabMusicEnabled,
                onCheckedChange = onDabMusicEnabledChange,
            )
        }

        item {
            val isLoggedIn = dabMusicSessionCookie.isNotEmpty()
            InfoLabel(
                text = stringResource(
                    if (isLoggedIn) R.string.dabmusic_logged_in
                    else R.string.dabmusic_not_logged_in,
                ),
            )
        }

        item {
            EditTextPreference(
                title = { Text(stringResource(R.string.dabmusic_email)) },
                value = dabMusicEmail,
                onValueChange = onDabMusicEmailChange,
                icon = { Icon(painterResource(R.drawable.account), null) },
                isEnabled = dabMusicEnabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                isInputValid = { it.isBlank() || android.util.Patterns.EMAIL_ADDRESS.matcher(it).matches() },
            )
        }

        item {
            EditTextPreference(
                title = { Text(stringResource(R.string.dabmusic_password)) },
                value = dabMusicPassword,
                onValueChange = onDabMusicPasswordChange,
                icon = { Icon(painterResource(R.drawable.lock), null) },
                isEnabled = dabMusicEnabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isInputValid = { it.isNotEmpty() },
            )
        }

        item {
            val coroutineScope = rememberCoroutineScope()
            var isLoggingIn by remember { mutableStateOf(false) }
            val isLoggedIn = dabMusicSessionCookie.isNotEmpty()
            PreferenceEntry(
                title = {
                    Text(
                        stringResource(
                            if (isLoggedIn) R.string.dabmusic_relogin
                            else R.string.dabmusic_login,
                        ),
                    )
                },
                description = stringResource(R.string.dabmusic_login_description),
                icon = { Icon(painterResource(R.drawable.login), null) },
                isEnabled = dabMusicEnabled && !isLoggingIn &&
                    dabMusicEmail.isNotBlank() && dabMusicPassword.isNotBlank(),
                onClick = {
                    coroutineScope.launch {
                        isLoggingIn = true
                        DabMusicAudioProvider.setCredentials(dabMusicEmail, dabMusicPassword)
                        val result = withContext(Dispatchers.IO) { DabMusicAudioProvider.login() }
                        isLoggingIn = false
                        when (result) {
                            is DabMusicAudioProvider.LoginResult.Success -> {
                                onDabMusicSessionCookieChange(DabMusicAudioProvider.getSessionCookie())
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.dabmusic_login_success),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            is DabMusicAudioProvider.LoginResult.CloudflareBlocked -> {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.dabmusic_cloudflare_blocked),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            is DabMusicAudioProvider.LoginResult.Failure -> {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.dabmusic_login_failed, result.reason),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                },
            )
        }

        item {
            PreferenceEntry(
                title = { Text(stringResource(R.string.dabmusic_sign_out)) },
                description = stringResource(R.string.dabmusic_sign_out_description),
                icon = { Icon(painterResource(R.drawable.logout), null) },
                isEnabled = dabMusicEnabled && dabMusicSessionCookie.isNotEmpty(),
                onClick = {
                    DabMusicAudioProvider.signOut()
                    onDabMusicSessionCookieChange("")
                },
            )
        }

        // ---------------------------------------------------------------------------------------------
        // Cloudflare bypass
        // ---------------------------------------------------------------------------------------------
        // dabmusic.xyz is fronted by Cloudflare's "managed challenge". /api/auth/login is
        // whitelisted (so the user can authenticate), but /api/search and /api/stream are
        // challenged — OkHttp can't solve the JS challenge and the request hangs for the full
        // readTimeout (30s) before failing with SocketTimeoutException. The bypass opens an
        // in-app WebView that solves the challenge transparently; we extract the cf_clearance
        // cookie and send it with every subsequent OkHttp request. ~30-minute-lived.
        item {
            val coroutineScope = rememberCoroutineScope()
            var showBypassDialog by remember { mutableStateOf(false) }
            val hasBypass = dabMusicCfClearance.isNotEmpty()

            InfoLabel(
                text = stringResource(
                    if (hasBypass) R.string.dabmusic_cf_bypass_active
                    else R.string.dabmusic_cf_bypass_inactive,
                ),
            )
            if (showBypassDialog) {
                val bypassBaseUrl = dabMusicBaseUrl.ifBlank { DabMusicAudioProvider.DEFAULT_BASE_URL }
                DabMusicCloudflareBypassDialog(
                    baseUrl = bypassBaseUrl,
                    onDismiss = { showBypassDialog = false },
                    onCfClearanceCaptured = { cfClearance ->
                        coroutineScope.launch {
                            onDabMusicCfClearanceChange(cfClearance)
                        }
                    },
                )
            }
            PreferenceEntry(
                title = { Text(stringResource(R.string.dabmusic_cf_bypass)) },
                description = stringResource(R.string.dabmusic_cf_bypass_description),
                icon = { Icon(painterResource(R.drawable.security), null) },
                isEnabled = dabMusicEnabled,
                onClick = { showBypassDialog = true },
            )
        }

        item {
            PreferenceEntry(
                title = { Text(stringResource(R.string.dabmusic_cf_bypass_clear)) },
                description = stringResource(R.string.dabmusic_cf_bypass_clear_description),
                icon = { Icon(painterResource(R.drawable.delete), null) },
                isEnabled = dabMusicEnabled && dabMusicCfClearance.isNotEmpty(),
                onClick = {
                    DabMusicAudioProvider.setCfClearance(null)
                    onDabMusicCfClearanceChange("")
                },
            )
        }

        item {
            EditTextPreference(
                title = { Text(stringResource(R.string.dabmusic_base_url)) },
                value = dabMusicBaseUrl,
                onValueChange = onDabMusicBaseUrlChange,
                icon = { Icon(painterResource(R.drawable.integration), null) },
                isEnabled = dabMusicEnabled,
                isInputValid = { it.isBlank() || it.startsWith("http://") || it.startsWith("https://") },
            )
        }
    }
}

@Composable
private fun SourceOrderDialog(
    initialOrder: List<AudioSourceType>,
    isEnabled: (AudioSourceType) -> Boolean,
    onDismiss: () -> Unit,
    onConfirm: (List<AudioSourceType>) -> Unit,
) {
    val context = LocalContext.current
    val sources = remember { mutableStateListOf(*initialOrder.toTypedArray()) }
    val lazyListState = rememberLazyListState()
    val reorderableState =
        rememberReorderableLazyListState(lazyListState) { from, to ->
            val item = sources.removeAt(from.index)
            sources.add(to.index, item)
        }

    DefaultDialog(
        onDismiss = onDismiss,
        buttons = {
            TextButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(android.R.string.cancel))
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { onConfirm(sources.toList()) },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = stringResource(R.string.set_source_priority),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            LazyColumn(
                state = lazyListState,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp),
            ) {
                itemsIndexed(sources, key = { _, item -> item.name }) { index, source ->
                    ReorderableItem(reorderableState, key = source.name) {
                        val isFirst = index == 0
                        val enabled = isEnabled(source)
                        val containerColor =
                            if (isFirst) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            }
                        val contentColor =
                            if (isFirst) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }

                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = if (index < sources.size - 1) 4.dp else 0.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(containerColor)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = contentColor.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp),
                            )
                            Icon(
                                painter = painterResource(source.iconRes()),
                                contentDescription = null,
                                tint = contentColor,
                                modifier = Modifier.size(20.dp),
                            )
                            androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = source.displayName(context),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = contentColor,
                                )
                                if (!enabled) {
                                    Text(
                                        text = stringResource(R.string.audio_source_disabled),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = contentColor.copy(alpha = 0.7f),
                                    )
                                }
                            }
                            Icon(
                                painter = painterResource(R.drawable.drag_handle),
                                contentDescription = null,
                                tint = contentColor.copy(alpha = 0.6f),
                                modifier =
                                    Modifier
                                        .size(20.dp)
                                        .draggableHandle(),
                            )
                        }
                    }
                }
            }
        }
    }
}
