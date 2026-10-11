/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Canvas sub-settings page (playback): every canvas feature — the video
 * canvas sources and resolvers plus the procedural static-artwork motion
 * ported from Sekai Tune (Kawarp fluid warp, Square Tunnel, audio-reactive
 * bass modulation).
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.chrisbanes.haze.hazeSource
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AlbumCanvasEnabledKey
import moe.rukamori.archivetune.constants.ArchiveTuneCanvasKey
import moe.rukamori.archivetune.constants.ArtworkProviderOrderKey
import moe.rukamori.archivetune.constants.CanvasAudioReactiveKey
import moe.rukamori.archivetune.constants.CanvasResolverEndpointsKey
import moe.rukamori.archivetune.constants.ProceduralArtworkEnabledKey
import moe.rukamori.archivetune.constants.ProceduralArtworkStyleKey
import moe.rukamori.archivetune.constants.ProceduralCanvasStyle
import moe.rukamori.archivetune.constants.SpotifyCanvasKey
import moe.rukamori.archivetune.constants.TidalArtworkFallbackEnabledKey
import moe.rukamori.archivetune.constants.TidalEnabledKey
import moe.rukamori.archivetune.constants.deserializeArtworkProviderOrder
import moe.rukamori.archivetune.ui.component.EnumListPreference
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.component.TextFieldDialog
import moe.rukamori.archivetune.ui.component.SettingsPageTopBar
import moe.rukamori.archivetune.ui.screens.ScreenHeaderHaze
import moe.rukamori.archivetune.ui.screens.rememberScreenHeaderHaze
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.CanvasResolverEndpoints
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasSettingsScreen(
    navController: NavController,
    scrollTo: String? = null,
) {
    val headerHaze = rememberScreenHeaderHaze()
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    val (archiveTuneCanvasEnabled, onArchiveTuneCanvasEnabledChange) =
        rememberPreference(
            ArchiveTuneCanvasKey,
            defaultValue = false,
        )

    val (spotifyCanvasEnabled, onSpotifyCanvasEnabledChange) =
        rememberPreference(
            SpotifyCanvasKey,
            defaultValue = false,
        )

    val (albumCanvasEnabled, onAlbumCanvasEnabledChange) =
        rememberPreference(
            AlbumCanvasEnabledKey,
            defaultValue = true,
        )

    val (canvasResolverEndpointsRaw, onCanvasResolverEndpointsChange) =
        rememberPreference(
            CanvasResolverEndpointsKey,
            defaultValue = "",
        )

    val (proceduralArtworkEnabled, onProceduralArtworkEnabledChange) =
        rememberPreference(
            ProceduralArtworkEnabledKey,
            defaultValue = false,
        )

    val (proceduralArtworkStyle, onProceduralArtworkStyleChange) =
        rememberEnumPreference(
            ProceduralArtworkStyleKey,
            defaultValue = ProceduralCanvasStyle.KAWARP,
        )

    val (canvasAudioReactive, onCanvasAudioReactiveChange) =
        rememberPreference(
            CanvasAudioReactiveKey,
            defaultValue = false,
        )

    val (tidalEnabled, _) =
        rememberPreference(
            TidalEnabledKey,
            defaultValue = true,
        )
    val (tidalArtworkEnabled, onTidalArtworkEnabledChange) =
        rememberPreference(
            TidalArtworkFallbackEnabledKey,
            defaultValue = false,
        )

    val (artworkProviderOrderStr, onArtworkProviderOrderStrChange) =
        rememberPreference(
            ArtworkProviderOrderKey,
            defaultValue = "",
        )
    val artworkProviderOrder =
        remember(artworkProviderOrderStr) {
            deserializeArtworkProviderOrder(artworkProviderOrderStr)
        }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsPageTopBar(
                titleText = stringResource(R.string.canvas_settings_title),
                onBack = navController::navigateUp,
                onBackLongClick = navController::backToMain,
            )
        },
    ) { paddingValues ->
        val playerAwareBottomPadding =
            LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()
        val scrollState = rememberScrollState()
        val positions = rememberPreferencePositions()
        LaunchedEffect(scrollTo) { positions.scrollToKey(scrollTo, scrollState) }

        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
                    .then(positions.containerModifier())
                    .verticalScroll(scrollState)
                    .hazeSource(headerHaze)
                    .padding(top = paddingValues.calculateTopPadding())
                    .padding(bottom = playerAwareBottomPadding + 16.dp),
            ) {
                PreferenceGroup(
                    modifier = positions.modifierFor("archive_tune_canvas"),
                    title = stringResource(R.string.tidal_artwork),
                ) {
                    item {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.archivetune_canvas)) },
                            description = stringResource(R.string.archivetune_canvas_desc),
                            icon = { Icon(painterResource(R.drawable.motion_photos_on), null) },
                            checked = archiveTuneCanvasEnabled,
                            onCheckedChange = onArchiveTuneCanvasEnabledChange,
                        )
                    }

                    item {
                        Column(modifier = positions.modifierFor("spotify_canvas")) {
                            SwitchPreference(
                                title = { Text(stringResource(R.string.spotify_canvas)) },
                                description = stringResource(R.string.spotify_canvas_desc),
                                icon = { Icon(painterResource(R.drawable.slow_motion_video), null) },
                                checked = spotifyCanvasEnabled,
                                onCheckedChange = onSpotifyCanvasEnabledChange,
                            )
                        }
                    }

                    item {
                        SwitchPreference(
                            modifier = positions.modifierFor("album_canvas_enabled"),
                            title = { Text(stringResource(R.string.album_canvas_enabled)) },
                            description = stringResource(R.string.album_canvas_enabled_desc),
                            icon = { Icon(painterResource(R.drawable.album), null) },
                            checked = albumCanvasEnabled,
                            onCheckedChange = onAlbumCanvasEnabledChange,
                        )
                    }

                    item(visible = spotifyCanvasEnabled) {
                        var showCanvasResolversDialog by remember { mutableStateOf(false) }
                        val configuredResolvers =
                            remember(canvasResolverEndpointsRaw) {
                                CanvasResolverEndpoints.parse(canvasResolverEndpointsRaw)
                            }
                        Column(modifier = positions.modifierFor("canvas_resolvers")) {
                            PreferenceEntry(
                                title = { Text(stringResource(R.string.canvas_resolvers)) },
                                description =
                                    if (configuredResolvers.isEmpty()) {
                                        stringResource(R.string.canvas_resolvers_none)
                                    } else {
                                        stringResource(
                                            R.string.canvas_resolvers_count,
                                            configuredResolvers.size,
                                        )
                                    },
                                icon = { Icon(painterResource(R.drawable.solar_server_linear), null) },
                                onClick = { showCanvasResolversDialog = true },
                            )
                        }
                        if (showCanvasResolversDialog) {
                            TextFieldDialog(
                                onDismiss = { showCanvasResolversDialog = false },
                                title = { Text(stringResource(R.string.canvas_resolvers)) },
                                placeholder = {
                                    Text(stringResource(R.string.canvas_resolvers_description))
                                },
                                textFieldValue = canvasResolverEndpointsRaw,
                                onTextFieldValueChange = onCanvasResolverEndpointsChange,
                                singleLine = false,
                                maxLines = 8,
                                isInputValid = { true },
                                onDone = { raw ->
                                    onCanvasResolverEndpointsChange(
                                        CanvasResolverEndpoints.serialize(
                                            CanvasResolverEndpoints.parse(raw),
                                        ),
                                    )
                                },
                            )
                        }
                    }

                    item {
                        var showCanvasCheckDialog by remember { mutableStateOf(false) }
                        Column(modifier = positions.modifierFor("canvas_check")) {
                            PreferenceEntry(
                                title = { Text(stringResource(R.string.canvas_check)) },
                                description = stringResource(R.string.canvas_check_description),
                                icon = { Icon(painterResource(R.drawable.solar_check_circle_linear), null) },
                                onClick = { showCanvasCheckDialog = true },
                            )
                        }
                        if (showCanvasCheckDialog) {
                            CanvasCheckDialog(
                                resolverEndpointsRaw = canvasResolverEndpointsRaw,
                                onDismiss = { showCanvasCheckDialog = false },
                            )
                        }
                    }
                }

                PreferenceGroup(
                    modifier = positions.modifierFor("procedural_artwork"),
                    title = stringResource(R.string.procedural_artwork_group_title),
                ) {
                    item {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.procedural_artwork_title)) },
                            description = stringResource(R.string.procedural_artwork_desc),
                            icon = { Icon(painterResource(R.drawable.motion_photos_on), null) },
                            checked = proceduralArtworkEnabled,
                            onCheckedChange = onProceduralArtworkEnabledChange,
                        )
                    }

                    item(visible = proceduralArtworkEnabled) {
                        Column(modifier = positions.modifierFor("procedural_artwork_style")) {
                            EnumListPreference(
                                title = { Text(stringResource(R.string.procedural_artwork_style)) },
                                description = stringResource(R.string.procedural_artwork_style_desc),
                                icon = { Icon(painterResource(R.drawable.waves), null) },
                                selectedValue = proceduralArtworkStyle,
                                onValueSelected = onProceduralArtworkStyleChange,
                                valueText = {
                                    when (it) {
                                        ProceduralCanvasStyle.KAWARP ->
                                            stringResource(R.string.procedural_style_kawarp)

                                        ProceduralCanvasStyle.SQUARE_TUNNEL ->
                                            stringResource(R.string.procedural_style_square_tunnel)
                                    }
                                },
                            )
                        }
                    }

                    item(visible = proceduralArtworkEnabled) {
                        Column(modifier = positions.modifierFor("canvas_audio_reactive")) {
                            SwitchPreference(
                                title = {
                                    Text(
                                        stringResource(R.string.canvas_audio_reactive_title) + " " +
                                            stringResource(R.string.experimental_tag),
                                    )
                                },
                                description = stringResource(R.string.canvas_audio_reactive_desc),
                                icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                                checked = canvasAudioReactive,
                                onCheckedChange = onCanvasAudioReactiveChange,
                            )
                        }
                    }
                }

                PreferenceGroup(
                    modifier = positions.modifierFor("tidal_artwork_fallback"),
                    title = stringResource(R.string.artwork_priority_group_title),
                ) {
                    item {
                        SwitchPreference(
                            title = { Text(stringResource(R.string.tidal_artwork_fallback)) },
                            description =
                                stringResource(
                                    if (tidalEnabled) {
                                        R.string.tidal_artwork_fallback_description
                                    } else {
                                        R.string.tidal_artwork_fallback_unavailable
                                    },
                                ),
                            icon = { Icon(painterResource(R.drawable.image), null) },
                            checked = tidalArtworkEnabled,
                            onCheckedChange = onTidalArtworkEnabledChange,
                            isEnabled = tidalEnabled,
                        )
                    }

                    item {
                        var showArtworkOrderDialog by remember { mutableStateOf(false) }
                        Column(modifier = positions.modifierFor("artwork_priority")) {
                            PreferenceEntry(
                                title = { Text(stringResource(R.string.artwork_priority)) },
                                description = artworkProviderOrder.firstOrNull()?.displayName(),
                                icon = { Icon(painterResource(R.drawable.filter_alt), null) },
                                onClick = { showArtworkOrderDialog = true },
                            )
                        }
                        if (showArtworkOrderDialog) {
                            ArtworkProviderOrderDialog(
                                initialOrder = artworkProviderOrder,
                                onDismiss = { showArtworkOrderDialog = false },
                                onConfirm = { newOrder ->
                                    onArtworkProviderOrderStrChange(newOrder.joinToString(",") { it.name })
                                    showArtworkOrderDialog = false
                                },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            ScreenHeaderHaze(
                hazeState = headerHaze,
                systemBarsTopPadding = systemBarsTopPadding,
            )
        }
    }
}
