/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.menu

import android.content.Intent
import android.media.audiofx.AudioEffect
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalDownloadUtil
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.ArchiveTuneCanvasKey
import moe.rukamori.archivetune.constants.ArtistSeparatorsKey
import moe.rukamori.archivetune.constants.ExternalDownloaderEnabledKey
import moe.rukamori.archivetune.constants.ExternalDownloaderPackageKey
import moe.rukamori.archivetune.constants.PlayerDesignStyle
import moe.rukamori.archivetune.constants.PlayerDesignStyleKey
import moe.rukamori.archivetune.constants.SpeedDialSongIdsKey
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.playback.CanvasArtworkRefetchResult
import moe.rukamori.archivetune.playback.ExoDownloadService
import moe.rukamori.archivetune.playback.queues.YouTubeQueue
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.db.entities.ArtistEntity
import moe.rukamori.archivetune.applemusic.AppleMusicAudioProvider
import moe.rukamori.archivetune.deezer.DeezerAudioProvider
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.jiosaavn.SaavnService
import moe.rukamori.archivetune.tidal.TidalAudioProvider
import moe.rukamori.archivetune.qobuz.QobuzAudioProvider
import moe.rukamori.archivetune.qobuz.QobuzBackupProvider
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.ListDialog
import moe.rukamori.archivetune.ui.component.MenuSurfaceSection
import moe.rukamori.archivetune.ui.component.NewAction
import moe.rukamori.archivetune.ui.component.NewActionGrid
import moe.rukamori.archivetune.ui.player.rememberDeviceMusicVolumeController
import moe.rukamori.archivetune.ui.utils.YtimgResizePolicy
import moe.rukamori.archivetune.ui.utils.resize
import moe.rukamori.archivetune.ui.player.CanvasArtworkPlaybackCache
import moe.rukamori.archivetune.utils.SpeedDialPin
import moe.rukamori.archivetune.utils.SpeedDialPinType
import moe.rukamori.archivetune.utils.isLocalMediaId
import moe.rukamori.archivetune.utils.parseSpeedDialPins
import moe.rukamori.archivetune.audiosource.SongSourceOverride
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.constants.SongSourceOverrideKey
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberLowDataModeActive
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.utils.serializeSpeedDialPins
import moe.rukamori.archivetune.utils.shareLocalAudio
import moe.rukamori.archivetune.utils.toggleSpeedDialPin
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import moe.rukamori.archivetune.ui.component.KeepStatusBarHiddenInDialog
import moe.rukamori.archivetune.ui.component.MenuSectionDivider

@Composable
fun PlayerMenu(
    mediaMetadata: MediaMetadata?,
    navController: NavController,
    playerBottomSheetState: BottomSheetState,
    isQueueTrigger: Boolean? = false,
    onPlayNextFromQueue: (() -> Unit)? = null,
    onRemoveFromQueue: (() -> Unit)? = null,
    onShowDetailsDialog: () -> Unit,
    onDismiss: () -> Unit,
) {
    mediaMetadata ?: return
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val deviceMusicVolumeController = rememberDeviceMusicVolumeController()
    val onPlayerVolumeChange =
        remember(deviceMusicVolumeController) {
            { volume: Float -> deviceMusicVolumeController.setVolumeFraction(volume) }
        }
    val activityResultLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val librarySong by database.song(mediaMetadata.id).collectAsStateWithLifecycle(initialValue = null)
    val coroutineScope = rememberCoroutineScope()

    val downloadUtil = LocalDownloadUtil.current
    val download by downloadUtil
        .getDownload(mediaMetadata.id)
        .collectAsStateWithLifecycle(initialValue = null)

    val artists =
        remember(mediaMetadata.artists) {
            mediaMetadata.artists.filter { it.id != null }
        }

    val (artistSeparators) = rememberPreference(ArtistSeparatorsKey, defaultValue = ",;/&")
    val (externalDownloaderEnabled) = rememberPreference(ExternalDownloaderEnabledKey, defaultValue = false)
    val (externalDownloaderPackage) = rememberPreference(ExternalDownloaderPackageKey, defaultValue = "")
    val (archiveTuneCanvasEnabled) = rememberPreference(ArchiveTuneCanvasKey, defaultValue = false)
    val playerDesignStyle by rememberEnumPreference(PlayerDesignStyleKey, defaultValue = PlayerDesignStyle.V4)
    val lowDataModeActive = rememberLowDataModeActive()
    val isCanvasArtworkRefetching by playerConnection.isCanvasArtworkRefetching.collectAsStateWithLifecycle()

    var hasCanvasArtwork by remember(mediaMetadata.id) { mutableStateOf(false) }
    LaunchedEffect(mediaMetadata.id, isCanvasArtworkRefetching) {
        hasCanvasArtwork = CanvasArtworkPlaybackCache.hasEntry(mediaMetadata.id)
    }
    val (speedDialSongIds, onSpeedDialSongIdsChange) = rememberPreference(SpeedDialSongIdsKey, "")
    val speedDialPins = remember(speedDialSongIds) { parseSpeedDialPins(speedDialSongIds) }
    val songPin = remember(mediaMetadata.id) { SpeedDialPin(type = SpeedDialPinType.SONG, id = mediaMetadata.id) }
    val isInSpeedDial =
        remember(speedDialPins, songPin) {
            speedDialPins.any { it.type == songPin.type && it.id == songPin.id }
        }
    val isLocalMedia =
        remember(librarySong?.song?.isLocal, mediaMetadata.id) {
            librarySong?.song?.isLocal == true || mediaMetadata.id.isLocalMediaId()
        }
    val castPlayerMenuAction = rememberCastPlayerMenuAction()

    data class SplitArtist(
        val name: String,
        val originalArtist: MediaMetadata.Artist?,
    )

    val splitArtists =
        remember(artists, artistSeparators) {
            if (artistSeparators.isEmpty()) {
                artists.map { SplitArtist(it.name, it) }
            } else {
                val separatorRegex = "[${Regex.escape(artistSeparators)}]".toRegex()
                artists.flatMap { artist ->
                    val parts =
                        artist.name
                            .split(separatorRegex)
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                    if (parts.size > 1) {

                        parts.map { name -> SplitArtist(name, artist) }
                    } else {
                        listOf(SplitArtist(artist.name, artist))
                    }
                }
            }
        }

    val artistIdsKey =
        remember(splitArtists) {
            splitArtists.mapNotNull { it.originalArtist?.id }.distinct().sorted()
        }
    val artistThumbnailsByKey: Map<String?, String?> by produceState(
        initialValue = emptyMap(),
        artistIdsKey,
    ) {
        withContext(Dispatchers.IO) {
            val result = mutableMapOf<String?, String?>()
            val nameById =
                splitArtists
                    .mapNotNull { sa ->
                        sa.originalArtist?.id?.let { id -> id to sa.originalArtist.name }
                    }.toMap()

            splitArtists.mapNotNull { it.originalArtist?.id }.distinct().forEach { artistId ->
                val dbEntity = database.getArtistById(artistId)
                val cached = dbEntity?.thumbnailUrl
                if (!cached.isNullOrBlank()) {
                    result[artistId] = cached
                    value = result.toMap()
                } else {

                    val fetched =
                        runCatching { YouTube.artist(artistId) }
                            .getOrNull()
                            ?.getOrNull()
                            ?.artist
                            ?.thumbnail
                    if (!fetched.isNullOrBlank()) {
                        result[artistId] = fetched
                        value = result.toMap()
                        runCatching {
                            database.query {
                                upsert(
                                    ArtistEntity(
                                        id = artistId,
                                        name = dbEntity?.name ?: nameById[artistId].orEmpty(),
                                        thumbnailUrl = fetched,
                                        channelId = dbEntity?.channelId,
                                        lastUpdateTime = dbEntity?.lastUpdateTime ?: LocalDateTime.now(),
                                        bookmarkedAt = dbEntity?.bookmarkedAt,
                                        blockedAt = dbEntity?.blockedAt,
                                        isLocal = dbEntity?.isLocal ?: false,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    AddToPlaylistDialog(
        isVisible = showChoosePlaylistDialog,
        onGetSong = {
            database.withTransaction {
                insert(mediaMetadata)
            }
            listOf(mediaMetadata.id)
        },
        onDismiss = {
            showChoosePlaylistDialog = false
        },
        onAddComplete = { songCount, playlistNames ->
            val message =
                when {
                    playlistNames.size == 1 -> context.getString(R.string.added_to_playlist, playlistNames.first())
                    else -> context.getString(R.string.added_to_n_playlists, playlistNames.size)
                }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        },
    )

    val (songSourceRaw, onSongSourceChange) = rememberPreference(SongSourceOverrideKey, "")
    val currentSongSource =
        remember(songSourceRaw, mediaMetadata.id) {
            SongSourceOverride.get(songSourceRaw.ifBlank { null }, mediaMetadata.id)
        }
    var showSourceDialog by rememberSaveable { mutableStateOf(false) }

    val sourceRevision by playerConnection.service.resolvedSourcesRevision.collectAsStateWithLifecycle()
    LaunchedEffect(showSourceDialog, mediaMetadata.id) {
        if (showSourceDialog) {
            playerConnection.service.refreshSourcesForSong(mediaMetadata.id)
        }
    }
    val availableSources =
        remember(mediaMetadata.id, showSourceDialog, sourceRevision) {
            playerConnection.service.availableSourcesForSong(mediaMetadata.id)
        }

    if (showSourceDialog) {
        SongSourceDialog(
            sources = availableSources,
            selected = currentSongSource,
            initialQuery = mediaMetadata.title,
            onDismiss = { showSourceDialog = false },
            onSelect = { source ->
                onSongSourceChange(SongSourceOverride.withOverride(songSourceRaw, mediaMetadata.id, source))
                playerConnection.service.setSongSourceOverride(mediaMetadata.id, source)
                showSourceDialog = false
            },
            onPlaySong = { song ->

                playerConnection.playQueue(YouTubeQueue.radio(song.toMediaMetadata()))
            },
            onPlayFromSource = { result ->

                val source = result.source
                val trackId = result.trackId
                if (source != AudioSourceType.YOUTUBE && trackId.isNotBlank()) {

                    onSongSourceChange(
                        SongSourceOverride.withOverride(songSourceRaw, mediaMetadata.id, source),
                    )

                    when (source) {
                        AudioSourceType.QOBUZ ->
                            playerConnection.service.setSongSourceOverrideWithQobuzTrackId(
                                mediaId = mediaMetadata.id,
                                source = source,
                                qobuzTrackId = trackId,
                            )

                        AudioSourceType.QOBUZ_BACKUP ->
                            playerConnection.service.setSongSourceOverrideWithQobuzBackupVideoId(
                                mediaId = mediaMetadata.id,
                                source = source,
                                qobuzBackupVideoId = trackId,
                            )

                        else ->
                            playerConnection.service.setSongSourceOverride(
                                mediaId = mediaMetadata.id,
                                source = source,
                            )
                    }
                    showSourceDialog = false
                }
            },
        )
    }

    var showSelectArtistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showSelectArtistDialog) {
        ListDialog(
            onDismiss = { showSelectArtistDialog = false },
        ) {
            items(splitArtists.distinctBy { it.name }, key = { it.name }) { splitArtist ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = splitArtist.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = {

                        val thumbUrl =
                            splitArtist.originalArtist?.id?.let { id ->
                                artistThumbnailsByKey[id]
                            }
                        if (thumbUrl.isNullOrBlank()) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.music_note),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else {
                            AsyncImage(
                                model =
                                    thumbUrl.resize(
                                        width = 200,
                                        height = 200,
                                        ytimgResizePolicy = YtimgResizePolicy.PreserveOriginal,
                                    ),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier =
                                    Modifier
                                        .size(40.dp)
                                        .clip(CircleShape),
                            )
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                splitArtist.originalArtist?.let { artist ->
                                    navController.navigate("artist/${artist.id}")
                                    showSelectArtistDialog = false
                                    playerBottomSheetState.collapseSoft()
                                    onDismiss()
                                }
                            },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }

    var showPitchTempoDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showPitchTempoDialog) {
        TempoPitchDialog(
            onDismiss = { showPitchTempoDialog = false },
        )
    }

    var showSleepTimerSheet by rememberSaveable { mutableStateOf(false) }

    var showEqualizerDialog by rememberSaveable {
        mutableStateOf(false)
    }

    if (showEqualizerDialog) {
        EqualizerDialog(
            onDismiss = { showEqualizerDialog = false },
            openSystemEqualizer = {
                val intent =
                    Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                        putExtra(
                            AudioEffect.EXTRA_AUDIO_SESSION,
                            playerConnection.localPlayer.audioSessionId,
                        )
                        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                    }
                if (intent.resolveActivity(context.packageManager) != null) {
                    activityResultLauncher.launch(intent)
                }
            },
        )
    }

    var showSaveCanvasDialog by rememberSaveable { mutableStateOf(false) }

    if (showSaveCanvasDialog) {
        SaveCanvasDialog(
            mediaId = mediaMetadata.id,
            songTitle = mediaMetadata.title,
            artistName = mediaMetadata.artists.joinToString(separator = ", ") { it.name },
            albumTitle = mediaMetadata.album?.title,
            storefront = remember {
                val country = java.util.Locale.getDefault().country
                if (country.length == 2) country.lowercase(java.util.Locale.ROOT) else "us"
            },
            onDismiss = { showSaveCanvasDialog = false },
        )
    }

    val nowPlayingTitle =
        remember(mediaMetadata.title) {
            mediaMetadata.title.ifBlank { context.getString(R.string.no_title) }
        }

    val nowPlayingSubtitle =
        remember(mediaMetadata.artists) {
            mediaMetadata.artists.joinToString(separator = " • ") { it.name }
        }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            val thumb = mediaMetadata.thumbnailUrl
            if (thumb.isNullOrBlank()) {
                Box(
                    modifier =
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.music_note),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            } else {
                AsyncImage(
                    model = thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp)),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.now_playing),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = nowPlayingTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(),
                )
                if (nowPlayingSubtitle.isNotBlank()) {
                    Text(
                        text = nowPlayingSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.basicMarquee(),
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding =
            PaddingValues(
                start = 0.dp,
                top = 0.dp,
                end = 0.dp,
                bottom = 12.dp,
            ),
    ) {

        if (showSleepTimerSheet) {
            item {
                AppleMusicSleepTimerSheet(
                    sleepTimer = playerConnection.service.sleepTimer,
                    onDismiss = {
                        showSleepTimerSheet = false
                        onDismiss()
                    },
                )
            }
        } else {
        item {
            MenuSurfaceSection {
                NewActionGrid(
                    actions =
                        buildList {
                            castPlayerMenuAction?.let(::add)
                            if (!isLocalMedia) {
                                add(
                                    NewAction(
                                        icon = {
                                            Icon(
                                                painter = painterResource(R.drawable.radio),
                                                contentDescription = null,
                                                modifier = Modifier.size(28.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        text = stringResource(R.string.start_radio),
                                        onClick = {
                                            playerConnection.startRadio(mediaMetadata)
                                            onDismiss()
                                        },
                                    ),
                                )
                            }
                            if (
                                !isLocalMedia &&
                                isQueueTrigger != true &&
                                archiveTuneCanvasEnabled &&
                                !lowDataModeActive &&
                                playerDesignStyle != PlayerDesignStyle.V5 &&
                                hasCanvasArtwork
                            ) {
                                add(
                                    NewAction(
                                        icon = {
                                            if (isCanvasArtworkRefetching) {
                                                CircularWavyProgressIndicator(modifier = Modifier.size(28.dp))
                                            } else {
                                                Icon(
                                                    painter = painterResource(R.drawable.sync),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(28.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        text = stringResource(R.string.refetch_canvas),
                                        onClick = {
                                            coroutineScope.launch {
                                                when (
                                                    playerConnection.refetchCanvasArtwork(
                                                        metadata = mediaMetadata,
                                                        requireVertical = playerDesignStyle == PlayerDesignStyle.V7,
                                                    )
                                                ) {
                                                    CanvasArtworkRefetchResult.Success -> onDismiss()
                                                    CanvasArtworkRefetchResult.Failure -> {
                                                        Toast
                                                            .makeText(
                                                                context,
                                                                R.string.canvas_refetch_failed,
                                                                Toast.LENGTH_SHORT,
                                                            ).show()
                                                    }

                                                    CanvasArtworkRefetchResult.AlreadyRunning -> Unit
                                                }
                                            }
                                        },
                                        enabled = !isCanvasArtworkRefetching,
                                    ),
                                )
                            }

                            add(
                                if (isLocalMedia) {
                                    NewAction(
                                        icon = {
                                            Icon(
                                                painter = painterResource(R.drawable.share),
                                                contentDescription = null,
                                                modifier = Modifier.size(28.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        text = stringResource(R.string.share),
                                        onClick = {
                                            shareLocalAudio(context, mediaMetadata.id, librarySong?.format?.mimeType)
                                            onDismiss()
                                        },
                                    )
                                } else {
                                    NewAction(
                                        icon = {
                                            Icon(
                                                painter = painterResource(R.drawable.link),
                                                contentDescription = null,
                                                modifier = Modifier.size(28.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        text = stringResource(R.string.copy_link),
                                        onClick = {
                                            val clipboard =
                                                context.getSystemService(
                                                    android.content.Context.CLIPBOARD_SERVICE,
                                                ) as android.content.ClipboardManager
                                            val clip =
                                                android.content.ClipData.newPlainText(
                                                    context.getString(R.string.copy_link),
                                                    "https://music.youtube.com/watch?v=${mediaMetadata.id}",
                                                )
                                            clipboard.setPrimaryClip(clip)
                                            android.widget.Toast
                                                .makeText(
                                                    context,
                                                    R.string.link_copied,
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            onDismiss()
                                        },
                                    )
                                },
                            )
                            if (!isLocalMedia) {

                                add(
                                    NewAction(
                                        icon = {
                                            Icon(
                                                painter = painterResource(R.drawable.tune),
                                                contentDescription = null,
                                                modifier = Modifier.size(28.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        text = stringResource(R.string.source),
                                        onClick = { showSourceDialog = true },
                                    ),
                                )
                            }
                            if (isQueueTrigger != true) {
                                add(
                                    NewAction(
                                        icon = {
                                            Icon(
                                                painter = painterResource(R.drawable.bedtime),
                                                contentDescription = null,
                                                modifier = Modifier.size(28.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        },
                                        text = stringResource(R.string.aod_mode),
                                        onClick = {
                                            playerConnection.aodModeEnabled.value = true
                                            onDismiss()
                                        },
                                    ),
                                )
                            }
                        },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
        item {
            MenuSectionDivider()
        }

        if (
            !isLocalMedia &&
            isQueueTrigger != true &&
            archiveTuneCanvasEnabled &&
            !lowDataModeActive &&
            playerDesignStyle != PlayerDesignStyle.V5 &&
            hasCanvasArtwork
        ) {
            item {
                MenuSurfaceSection {
                    ListItem(
                        headlineContent = { Text(text = stringResource(R.string.save_canvas)) },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.motion_photos_on),
                                contentDescription = null,
                            )
                        },
                        modifier =
                            Modifier.clickable {
                                showSaveCanvasDialog = true
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            item {
                MenuSectionDivider()
            }
        }

        item {
            MenuSurfaceSection {
                Column {
                    ListItem(
                        headlineContent = { Text(text = stringResource(R.string.add_to_playlist)) },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.playlist_add),
                                contentDescription = null,
                            )
                        },
                        modifier =
                            Modifier.clickable {
                                showChoosePlaylistDialog = true
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    ListItem(
                        headlineContent = {
                            Text(
                                text =
                                    stringResource(
                                        if (isInSpeedDial) {
                                            R.string.remove_from_speed_dial
                                        } else {
                                            R.string.pin_to_speed_dial
                                        },
                                    ),
                            )
                        },
                        leadingContent = {
                            Icon(
                                painter =
                                    painterResource(
                                        if (isInSpeedDial) R.drawable.bookmark_filled else R.drawable.bookmark,
                                    ),
                                contentDescription = null,
                            )
                        },
                        modifier =
                            Modifier.clickable {
                                val updatedPins = toggleSpeedDialPin(speedDialPins, songPin)
                                onSpeedDialSongIdsChange(serializeSpeedDialPins(updatedPins))
                                onDismiss()
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
        item {
            MenuSectionDivider()
        }
        if (splitArtists.isNotEmpty() || mediaMetadata.album != null) {
            item {
                MenuSurfaceSection {
                    Column {
                        if (splitArtists.isNotEmpty()) {
                            ListItem(
                                headlineContent = { Text(text = stringResource(R.string.view_artist)) },
                                leadingContent = {
                                    Icon(
                                        painter = painterResource(R.drawable.artist),
                                        contentDescription = null,
                                    )
                                },
                                modifier =
                                    Modifier.clickable {
                                        if (splitArtists.size == 1 && splitArtists[0].originalArtist != null) {
                                            onDismiss()
                                            playerBottomSheetState.snapTo(playerBottomSheetState.collapsedBound)
                                            navController.navigate("artist/${splitArtists[0].originalArtist!!.id}")
                                        } else {
                                            showSelectArtistDialog = true
                                        }
                                    },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }

                        if (splitArtists.isNotEmpty() && mediaMetadata.album != null) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 56.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }

                        if (mediaMetadata.album != null) {
                            ListItem(
                                headlineContent = { Text(text = stringResource(R.string.view_album)) },
                                leadingContent = {
                                    Icon(
                                        painter = painterResource(R.drawable.album),
                                        contentDescription = null,
                                    )
                                },
                                modifier =
                                    Modifier.clickable {
                                        onDismiss()
                                        playerBottomSheetState.snapTo(playerBottomSheetState.collapsedBound)
                                        navController.navigate("album/${mediaMetadata.album.id}")
                                    },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                }
            }
            item {
                MenuSectionDivider()
            }
        }
        if (!isLocalMedia) {
            item {
                MenuSurfaceSection {
                    when (download?.state) {
                        Download.STATE_COMPLETED -> {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = stringResource(R.string.remove_download),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        painter = painterResource(R.drawable.offline),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                modifier =
                                    Modifier.clickable {
                                        DownloadService.sendRemoveDownload(
                                            context,
                                            ExoDownloadService::class.java,
                                            mediaMetadata.id,
                                            false,
                                        )
                                    },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }

                        Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
                            ListItem(
                                headlineContent = { Text(text = stringResource(R.string.downloading)) },
                                leadingContent = {
                                    CircularWavyProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                    )
                                },
                                modifier =
                                    Modifier.clickable {
                                        DownloadService.sendRemoveDownload(
                                            context,
                                            ExoDownloadService::class.java,
                                            mediaMetadata.id,
                                            false,
                                        )
                                    },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }

                        else -> {
                            ListItem(
                                headlineContent = { Text(text = stringResource(R.string.action_download)) },
                                leadingContent = {
                                    Icon(
                                        painter = painterResource(R.drawable.download),
                                        contentDescription = null,
                                    )
                                },
                                modifier =
                                    Modifier.clickable {
                                        database.transaction {
                                            insert(mediaMetadata)
                                        }

                                        coroutineScope.launch {
                                            runCatching {
                                                downloadUtil.prewarmSongForDownload(mediaMetadata.id)
                                            }
                                            val downloadRequest =
                                                DownloadRequest
                                                    .Builder(mediaMetadata.id, mediaMetadata.id.toUri())
                                                    .setCustomCacheKey(mediaMetadata.id)
                                                    .setData(mediaMetadata.title.toByteArray())
                                                    .build()
                                            DownloadService.sendAddDownload(
                                                context,
                                                ExoDownloadService::class.java,
                                                downloadRequest,
                                                false,
                                            )
                                        }
                                    },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                    if (externalDownloaderEnabled) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        ListItem(
                            headlineContent = { Text(text = stringResource(R.string.open_with_downloader)) },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.download),
                                    contentDescription = null,
                                )
                            },
                            modifier =
                                Modifier.clickable {
                                    onDismiss()
                                    val url = "https://music.youtube.com/watch?v=${mediaMetadata.id}"
                                    if (externalDownloaderPackage.isBlank()) {
                                        Toast
                                            .makeText(
                                                context,
                                                context.getString(R.string.external_downloader_not_configured),
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        return@clickable
                                    }
                                    val intent =
                                        android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                            setPackage(externalDownloaderPackage)
                                            data = android.net.Uri.parse(url)
                                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    try {
                                        context.startActivity(intent)
                                    } catch (e: android.content.ActivityNotFoundException) {
                                        Toast
                                            .makeText(
                                                context,
                                                context.getString(R.string.external_downloader_not_installed),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                    }
                                },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        }
        item {
            MenuSurfaceSection {
                Column {
                    if (isQueueTrigger == true && onPlayNextFromQueue != null) {
                        ListItem(
                            headlineContent = {
                                Text(text = stringResource(R.string.play_next))
                            },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.playlist_play),
                                    contentDescription = null,
                                )
                            },
                            modifier =
                                Modifier.clickable {
                                    onPlayNextFromQueue()
                                    onDismiss()
                                },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }

                    if (isQueueTrigger == true && onRemoveFromQueue != null) {
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = stringResource(R.string.remove_from_queue),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.delete),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            modifier =
                                Modifier.clickable {
                                    onRemoveFromQueue()
                                    onDismiss()
                                },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )

                        ListItem(
                            headlineContent = { Text(text = stringResource(R.string.play_next)) },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.playlist_play),
                                    contentDescription = null,
                                )
                            },
                            modifier =
                                Modifier.clickable {
                                    mediaMetadata?.toMediaItem()?.let { playerConnection.playNext(it) }
                                    onDismiss()
                                },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                        ListItem(
                            headlineContent = { Text(text = stringResource(R.string.add_to_queue)) },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.queue_music),
                                    contentDescription = null,
                                )
                            },
                            modifier =
                                Modifier.clickable {
                                    mediaMetadata?.toMediaItem()?.let { playerConnection.addToQueue(it) }
                                    onDismiss()
                                },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }

                    ListItem(
                        headlineContent = { Text(text = stringResource(R.string.details)) },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.info),
                                contentDescription = null,
                            )
                        },
                        modifier =
                            Modifier.clickable {
                                onShowDetailsDialog()
                                onDismiss()
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )

                    if (isQueueTrigger != true) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )

                        if (playerDesignStyle != PlayerDesignStyle.APPLE_MUSIC) {
                            ListItem(
                                headlineContent = { Text(text = stringResource(R.string.sleep_timer)) },
                                leadingContent = {
                                    Icon(
                                        painter = painterResource(R.drawable.bedtime),
                                        contentDescription = null,
                                    )
                                },
                                modifier =
                                    Modifier.clickable { showSleepTimerSheet = true },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )

                            HorizontalDivider(
                                modifier = Modifier.padding(start = 56.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }

                        ListItem(
                            headlineContent = { Text(text = stringResource(R.string.equalizer)) },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.equalizer),
                                    contentDescription = null,
                                )
                            },
                            modifier = Modifier.clickable { showEqualizerDialog = true },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )

                        ListItem(
                            headlineContent = { Text(text = stringResource(R.string.tempo_and_pitch)) },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.speed),
                                    contentDescription = null,
                                )
                            },
                            supportingContent = {
                                val playbackParameters by playerConnection.playbackParameters.collectAsStateWithLifecycle()
                                Text(
                                    text = "x${formatMultiplier(
                                        playbackParameters.speed,
                                    )} • x${formatMultiplier(playbackParameters.pitch)}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier.clickable { showPitchTempoDialog = true },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
fun TempoPitchDialog(onDismiss: () -> Unit) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val initialSpeed = remember { playerConnection.player.playbackParameters.speed }
    val initialPitch = remember { playerConnection.player.playbackParameters.pitch }

    var tempo by remember {
        mutableFloatStateOf(initialSpeed.safeCoerceIn(TempoMin, TempoMax, fallback = 1f))
    }

    var pitch by remember {
        mutableFloatStateOf(initialPitch.safeCoerceIn(PitchMin, PitchMax, fallback = 1f))
    }

    var pitchMode by rememberSaveable {
        mutableStateOf(
            if (isPitchSemitoneAligned(pitch)) PitchMode.Semitones else PitchMode.Multiplier,
        )
    }

    val applyPlaybackParameters: (Float, Float) -> Unit = { speed, pitchMultiplier ->
        playerConnection.player.playbackParameters =
            PlaybackParameters(
                speed.coerceIn(TempoMin, TempoMax),
                pitchMultiplier.coerceIn(PitchMin, PitchMax),
            )
    }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.tempo_and_pitch))
        },
        dismissButton = {
            TextButton(
                onClick = {
                    tempo = 1f
                    pitch = 1f
                    applyPlaybackParameters(tempo, pitch)
                },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.reset))
            }
        },
        confirmButton = {
            KeepStatusBarHiddenInDialog()
            TextButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.speed),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )

                    Text(
                        text = stringResource(R.string.tempo),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )

                    Text(
                        text = "x${formatMultiplier(tempo)}",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.End,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(
                        enabled = tempo > TempoMin,
                        onClick = {
                            tempo = (tempo - 0.01f).coerceIn(TempoMin, TempoMax).quantize(0.01f)
                            applyPlaybackParameters(tempo, pitch)
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.remove),
                            contentDescription = null,
                        )
                    }

                    Slider(
                        value = multiplierToSlider(tempo),
                        onValueChange = { slider ->
                            val updated = sliderToMultiplier(slider).quantize(0.01f)
                            if (abs(updated - tempo) >= 0.005f) {
                                tempo = updated
                                applyPlaybackParameters(tempo, pitch)
                            }
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(),
                    )

                    IconButton(
                        enabled = tempo < TempoMax,
                        onClick = {
                            tempo = (tempo + 0.01f).coerceIn(TempoMin, TempoMax).quantize(0.01f)
                            applyPlaybackParameters(tempo, pitch)
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.add),
                            contentDescription = null,
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                ) {
                    val presets = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                    presets.forEach { preset ->
                        val selected = abs(tempo - preset) < 0.005f
                        FilterChip(
                            selected = selected,
                            onClick = {
                                tempo = preset
                                applyPlaybackParameters(tempo, pitch)
                            },
                            label = { Text("x${formatMultiplier(preset)}") },
                        )
                    }
                }

                HorizontalDivider()

                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.discover_tune),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )

                    Text(
                        text = stringResource(R.string.pitch),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )

                    Text(
                        text =
                            when (pitchMode) {
                                PitchMode.Semitones -> {
                                    val semitones = pitchToSemitones(pitch)
                                    "${if (semitones > 0) "+" else ""}$semitones"
                                }

                                PitchMode.Multiplier -> {
                                    "x${formatMultiplier(pitch)}"
                                }
                            },
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.End,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                ) {
                    FilterChip(
                        selected = pitchMode == PitchMode.Semitones,
                        onClick = { pitchMode = PitchMode.Semitones },
                        label = { Text(stringResource(R.string.pitch_mode_semitones_short)) },
                    )
                    FilterChip(
                        selected = pitchMode == PitchMode.Multiplier,
                        onClick = { pitchMode = PitchMode.Multiplier },
                        label = { Text(stringResource(R.string.pitch_mode_multiplier_short)) },
                    )
                }

                when (pitchMode) {
                    PitchMode.Semitones -> {
                        val currentSemitones = pitchToSemitones(pitch)
                        Slider(
                            value = currentSemitones.toFloat(),
                            onValueChange = { slider ->
                                val semitones = slider.roundToInt().coerceIn(-12, 12)
                                val updated = semitonesToPitch(semitones)
                                if (abs(updated - pitch) >= 0.0005f) {
                                    pitch = updated
                                    applyPlaybackParameters(tempo, pitch)
                                }
                            },
                            valueRange = -12f..12f,
                            steps = 23,
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(),
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                        ) {
                            val presets = listOf(-12, -7, -5, 0, 5, 7, 12)
                            presets.forEach { preset ->
                                val selected = currentSemitones == preset
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        pitch = semitonesToPitch(preset)
                                        applyPlaybackParameters(tempo, pitch)
                                    },
                                    label = { Text("${if (preset > 0) "+" else ""}$preset") },
                                )
                            }
                        }
                    }

                    PitchMode.Multiplier -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            IconButton(
                                enabled = pitch > PitchMin,
                                onClick = {
                                    pitch = (pitch - 0.01f).coerceIn(PitchMin, PitchMax).quantize(0.01f)
                                    applyPlaybackParameters(tempo, pitch)
                                },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.remove),
                                    contentDescription = null,
                                )
                            }

                            Slider(
                                value = multiplierToSlider(pitch),
                                onValueChange = { slider ->
                                    val updated = sliderToMultiplier(slider).quantize(0.01f)
                                    if (abs(updated - pitch) >= 0.005f) {
                                        pitch = updated
                                        applyPlaybackParameters(tempo, pitch)
                                    }
                                },
                                valueRange = 0f..1f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(),
                            )

                            IconButton(
                                enabled = pitch < PitchMax,
                                onClick = {
                                    pitch = (pitch + 0.01f).coerceIn(PitchMin, PitchMax).quantize(0.01f)
                                    applyPlaybackParameters(tempo, pitch)
                                },
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.add),
                                    contentDescription = null,
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                        ) {
                            val presets = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                            presets.forEach { preset ->
                                val selected = abs(pitch - preset) < 0.005f
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        pitch = preset
                                        applyPlaybackParameters(tempo, pitch)
                                    },
                                    label = { Text("x${formatMultiplier(preset)}") },
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

private enum class PitchMode {
    Semitones,
    Multiplier,
}

private const val TempoMin = 0.25f
private const val TempoMax = 2f
private const val PitchMin = 0.25f
private const val PitchMax = 2f

private fun Float.safeCoerceIn(
    min: Float,
    max: Float,
    fallback: Float,
): Float {
    val safe = if (this.isFinite()) this else fallback
    return safe.coerceIn(min, max)
}

private fun Float.quantize(step: Float): Float {
    if (step <= 0f) return this
    return (round(this / step) * step).coerceAtLeast(0f)
}

private fun pitchToSemitones(pitch: Float): Int {
    val safePitch = pitch.safeCoerceIn(PitchMin, PitchMax, fallback = 1f).coerceAtLeast(0.0001f)
    return (12f * log2(safePitch)).roundToInt().coerceIn(-12, 12)
}

private fun semitonesToPitch(semitones: Int): Float = 2f.pow(semitones.toFloat() / 12f).coerceIn(PitchMin, PitchMax)

private fun isPitchSemitoneAligned(pitch: Float): Boolean {
    val safePitch = pitch.safeCoerceIn(PitchMin, PitchMax, fallback = 1f).coerceAtLeast(0.0001f)
    val semitones = (12f * log2(safePitch)).roundToInt()
    val reconstructed = 2f.pow(semitones.toFloat() / 12f)
    return abs(reconstructed - pitch) < 0.0015f
}

private fun formatMultiplier(multiplier: Float): String = String.format("%.2f", multiplier)

private fun sliderToMultiplier(slider: Float): Float {
    val t = slider.coerceIn(0f, 1f)
    val y = (t - 0.5f) * 2f
    val curve = 2.2f
    val absY = abs(y).pow(curve)
    val shaped =
        when {
            y > 0f -> absY
            y < 0f -> -absY
            else -> 0f
        }
    val exponent = if (y < 0f) 2f * shaped else shaped
    return 2f.pow(exponent).coerceIn(TempoMin, TempoMax)
}

private fun multiplierToSlider(multiplier: Float): Float {
    val m = multiplier.coerceIn(TempoMin, TempoMax)
    val log = log2(m)
    val curve = 2.2f
    val shaped = if (m < 1f) (log / 2f) else log
    val absShaped = abs(shaped).pow(1f / curve)
    val y =
        when {
            shaped > 0f -> absShaped
            shaped < 0f -> -absShaped
            else -> 0f
        }
    return (0.5f + y / 2f).coerceIn(0f, 1f)
}

private fun AudioSourceType.sourceLabelRes(): Int =
    when (this) {
        AudioSourceType.TIDAL -> R.string.source_tidal
        AudioSourceType.QOBUZ -> R.string.source_qobuz
        AudioSourceType.QOBUZ_BACKUP -> R.string.source_qobuz_backup
        AudioSourceType.DEEZER -> R.string.source_deezer
        AudioSourceType.APPLE -> R.string.source_apple_music
        AudioSourceType.JIOSAAVN -> R.string.source_jiosaavn
        AudioSourceType.YOUTUBE -> R.string.source_youtube
    }

private fun AudioSourceType.sourceIconRes(): Int =
    when (this) {
        AudioSourceType.TIDAL -> R.drawable.provider_tidal
        AudioSourceType.QOBUZ -> R.drawable.provider_qobuz
        AudioSourceType.QOBUZ_BACKUP -> R.drawable.provider_qobuz
        AudioSourceType.DEEZER -> R.drawable.provider_deezer
        AudioSourceType.APPLE -> R.drawable.provider_apple
        AudioSourceType.JIOSAAVN -> R.drawable.provider_jiosaavn
        AudioSourceType.YOUTUBE -> R.drawable.play
    }

private data class SourceSearchResult(
    val source: AudioSourceType,
    val trackId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationMs: Long?,
    val qualityLabel: String?,
    val songItem: SongItem?,
)

/**
 * One source pill for the play-from search. Same shape, corner and colors as [ChipsRow] so the
 * row reads as the app's chip language, plus a small spinner on the trailing edge while that
 * source's search is in flight — the per-source loading indicator.
 */
@Composable
private fun SourceSearchPill(
    label: String,
    iconRes: Int,
    selected: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
        },
        trailingIcon = {
            if (isLoading) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        },
        shape = RoundedCornerShape(16.dp),
        border = null,
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    )
}

/**
 * Runs ONE source's search against [query]. Every call is self-contained so the dialog can fire
 * all sources at once and let each land independently.
 */
private suspend fun searchOneSource(
    source: AudioSourceType,
    query: String,
    aacLabel: String,
    saavnLabel: String,
    losslessLabel: String,
    deezerLabel: String,
    appleQualityLabel: String?,
): List<SourceSearchResult> =
    withContext(Dispatchers.IO) {
        when (source) {
            AudioSourceType.YOUTUBE -> {
                val ytResult =
                    runCatching {
                        YouTube.search(query, YouTube.SearchFilter.FILTER_SONG, useAccountContext = false).getOrNull()
                    }.getOrNull()
                val songs =
                    ytResult?.items
                        ?.filterIsInstance<SongItem>()
                        .orEmpty()
                songs.map { song ->
                    SourceSearchResult(
                        source = AudioSourceType.YOUTUBE,
                        trackId = song.id,
                        title = song.title,
                        artist = song.artists.joinToString(", ") { it.name },
                        thumbnailUrl = song.thumbnail,
                        durationMs = song.duration?.toLong()?.times(1000L),
                        qualityLabel = aacLabel,
                        songItem = song,
                    )
                }
            }

            AudioSourceType.TIDAL -> {
                val tidalQuery =
                    TidalAudioProvider.Query(
                        mediaId = "",
                        title = query,
                        artists = emptyList(),
                        album = null,
                        isrc = null,
                        durationMs = null,
                    )
                runCatching { TidalAudioProvider.searchCandidates(tidalQuery, limit = 8) }
                    .getOrDefault(emptyList())
                    .map { candidate ->
                        SourceSearchResult(
                            source = AudioSourceType.TIDAL,
                            trackId = candidate.trackId,
                            title = candidate.title,
                            artist = candidate.artist,
                            thumbnailUrl = candidate.thumbnailUrl,
                            durationMs = candidate.durationMs,
                            qualityLabel = losslessLabel,
                            songItem = null,
                        )
                    }
            }

            AudioSourceType.QOBUZ -> {
                runCatching { QobuzAudioProvider.searchCandidates(query, limit = 8) }
                    .getOrDefault(emptyList())
                    .map { candidate ->
                        val thumb = candidate.thumbnailUrl ?: run {
                            val term =
                                listOfNotNull(
                                    candidate.artist?.takeIf(String::isNotBlank),
                                    candidate.title,
                                ).joinToString(" ")
                            val ytResult =
                                YouTube.search(term, YouTube.SearchFilter.FILTER_SONG, useAccountContext = false).getOrNull()
                            ytResult?.items
                                ?.filterIsInstance<SongItem>()
                                ?.firstOrNull()
                                ?.thumbnail
                        }
                        SourceSearchResult(
                            source = AudioSourceType.QOBUZ,
                            trackId = candidate.trackId,
                            title = candidate.title,
                            artist = candidate.artist.orEmpty(),
                            thumbnailUrl = thumb,
                            durationMs = candidate.durationMs,
                            qualityLabel = losslessLabel,
                            songItem = null,
                        )
                    }
            }

            AudioSourceType.QOBUZ_BACKUP -> {
                runCatching { QobuzBackupProvider.searchCandidates(query, limit = 8) }
                    .getOrDefault(emptyList())
                    .map { candidate ->
                        SourceSearchResult(
                            source = AudioSourceType.QOBUZ_BACKUP,
                            trackId = candidate.videoId,
                            title = candidate.title,
                            artist = candidate.artist.orEmpty(),
                            thumbnailUrl = candidate.thumbnailUrl,
                            durationMs = null,
                            qualityLabel = if (candidate.isLossless) losslessLabel else aacLabel,
                            songItem = null,
                        )
                    }
            }

            AudioSourceType.DEEZER -> {
                runCatching { DeezerAudioProvider.searchCandidates(query, limit = 8) }
                    .getOrDefault(emptyList())
                    .map { candidate ->
                        SourceSearchResult(
                            source = AudioSourceType.DEEZER,
                            trackId = candidate.trackId,
                            title = candidate.title,
                            artist = candidate.artist.orEmpty(),
                            thumbnailUrl = candidate.coverUrl,
                            durationMs = candidate.durationMs,
                            qualityLabel = deezerLabel,
                            songItem = null,
                        )
                    }
            }

            AudioSourceType.APPLE -> {
                runCatching { AppleMusicAudioProvider.searchCandidates(query, limit = 8) }
                    .getOrDefault(emptyList())
                    .map { candidate ->
                        SourceSearchResult(
                            source = AudioSourceType.APPLE,
                            trackId = candidate.songId,
                            title = candidate.title,
                            artist = candidate.artist.orEmpty(),
                            thumbnailUrl = candidate.thumbnailUrl,
                            durationMs = candidate.durationMs,
                            qualityLabel = appleQualityLabel,
                            songItem = null,
                        )
                    }
            }

            AudioSourceType.JIOSAAVN -> {
                SaavnService.searchSongs(query)
                    .getOrDefault(emptyList())
                    .map { saavnSong ->
                        val cover =
                            saavnSong.image.maxByOrNull {
                                runCatching { it.quality.substringBefore("x").toInt() }.getOrDefault(0)
                            }?.url
                        SourceSearchResult(
                            source = AudioSourceType.JIOSAAVN,
                            trackId = saavnSong.id,
                            title = saavnSong.name,
                            artist = saavnSong.artists.primary.joinToString(", ") { it.name },
                            thumbnailUrl = cover,
                            durationMs = saavnSong.duration?.toLong()?.times(1000L),
                            qualityLabel = saavnLabel,
                            songItem = null,
                        )
                    }
            }
        }
    }

@Composable
private fun SongSourceDialog(
    sources: List<AudioSourceType>,
    selected: AudioSourceType?,
    onDismiss: () -> Unit,
    onSelect: (AudioSourceType?) -> Unit,
    onPlaySong: (SongItem) -> Unit,
    onPlayFromSource: (SourceSearchResult) -> Unit,
    initialQuery: String = "",
) {
    var searchMode by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sourceFilter by rememberSaveable { mutableStateOf<AudioSourceType?>(null) }

    // Per-source results and per-source loading flags. Every searchable source runs its own
    // search in parallel as soon as there is a query, and each source's results land the moment
    // that source answers. Switching the source pill NEVER re-triggers a search: the pills only
    // filter what is displayed, so results survive any number of pill changes.
    var resultsBySource by remember {
        mutableStateOf<Map<AudioSourceType, List<SourceSearchResult>>>(emptyMap())
    }
    var loadingSources by remember { mutableStateOf<Set<AudioSourceType>>(emptySet()) }

    val aacLabel = stringResource(R.string.quality_badge_aac)
    val saavnLabel = stringResource(R.string.quality_badge_saavn)
    val mp3Label = stringResource(R.string.quality_badge_mp3)
    val losslessLabel = stringResource(R.string.quality_badge_lossless)
    val noResultsText = stringResource(R.string.source_search_no_results)

    val deezerLabel =
        remember(losslessLabel, mp3Label) {
            val availability = DeezerAudioProvider.accountAvailability()
            if (availability.manualPremium || availability.pooledPremium > 0) losslessLabel else mp3Label
        }

    val appleQualityLabel =
        remember(losslessLabel) {
            if (AppleMusicAudioProvider.isAvailable()) losslessLabel else null
        }

    val searchableSources =
        listOf(
            AudioSourceType.YOUTUBE,
            AudioSourceType.TIDAL,
            AudioSourceType.QOBUZ,
            AudioSourceType.QOBUZ_BACKUP,
            AudioSourceType.DEEZER,
            AudioSourceType.APPLE,
            AudioSourceType.JIOSAAVN,
        )

    LaunchedEffect(searchMode, searchQuery) {
        if (!searchMode || searchQuery.length < 2) {
            resultsBySource = emptyMap()
            loadingSources = emptySet()
            return@LaunchedEffect
        }
        // Debounce typing; every source then searches the same query at once, each landing in
        // resultsBySource as it answers so partial results show without waiting for stragglers.
        delay(350L)
        val query = searchQuery
        loadingSources = searchableSources.toSet()
        resultsBySource = emptyMap()
        coroutineScope {
            searchableSources
                .map { source ->
                    async {
                        val results =
                            runCatching {
                                searchOneSource(
                                    source = source,
                                    query = query,
                                    aacLabel = aacLabel,
                                    saavnLabel = saavnLabel,
                                    losslessLabel = losslessLabel,
                                    deezerLabel = deezerLabel,
                                    appleQualityLabel = appleQualityLabel,
                                )
                            }.getOrDefault(emptyList())
                        resultsBySource = resultsBySource + (source to results)
                        loadingSources = loadingSources - source
                    }
                }
                .awaitAll()
        }
    }

    // The displayed set: the pill filters these lists client-side only.
    val results =
        remember(resultsBySource, sourceFilter, searchableSources) {
            searchableSources
                .flatMap { source -> resultsBySource[source].orEmpty() }
                .filter { sourceFilter == null || it.source == sourceFilter }
        }
    val filterLoading =
        sourceFilter?.let { it in loadingSources } ?: loadingSources.isNotEmpty()

    DefaultDialog(
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    ) {
        Column(modifier = Modifier.padding(top = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.play_from),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        if (!searchMode) {
                            // Entering search mode with the song's name already in the bar: the
                            // point of the search is finding THIS track on another service.
                            searchQuery = initialQuery
                            sourceFilter = null
                            searchMode = true
                        } else {
                            searchMode = false
                            searchQuery = ""
                            sourceFilter = null
                        }
                    },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.search),
                        contentDescription = stringResource(R.string.download_source_search),
                        tint = if (searchMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (searchMode) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.download_source_search_hint)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                )
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                ) {
                    Spacer(Modifier.width(12.dp))
                    SourceSearchPill(
                        label = stringResource(R.string.source_search_filter_all),
                        iconRes = R.drawable.search,
                        selected = sourceFilter == null,
                        isLoading = false,
                        onClick = { sourceFilter = null },
                    )
                    searchableSources.forEach { source ->
                        Spacer(Modifier.width(8.dp))
                        SourceSearchPill(
                            label = stringResource(source.sourceLabelRes()),
                            iconRes = source.sourceIconRes(),
                            selected = sourceFilter == source,
                            isLoading = source in loadingSources,
                            onClick = { sourceFilter = source },
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                }
                when {
                    searchQuery.length < 2 -> {
                        Text(
                            text = noResultsText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        )
                    }
                    results.isEmpty() && filterLoading -> {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.source_search_searching),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    results.isEmpty() -> {
                        Text(
                            text = noResultsText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                        ) {
                            items(results, key = { result -> "${result.source.name}:${result.trackId}" }) { result ->
                                SourceSearchResultRow(result = result) {

                                    if (result.songItem != null) {
                                        onPlaySong(result.songItem)
                                    } else {
                                        onPlayFromSource(result)
                                    }
                                    onDismiss()
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
            } else {

                SongSourceRow(
                    iconRes = R.drawable.tune,
                    label = stringResource(R.string.play_from_automatic),
                    checked = selected == null,
                    onClick = { onSelect(null) },
                )
                sources.forEach { source ->
                    SongSourceRow(
                        iconRes = source.sourceIconRes(),
                        label = stringResource(source.sourceLabelRes()),
                        checked = selected == source,
                        onClick = { onSelect(source) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceSearchResultRow(
    result: SourceSearchResult,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!result.thumbnailUrl.isNullOrBlank()) {
            AsyncImage(
                model = result.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.music_note),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val subtitle =
                buildString {
                    append(result.artist)
                    result.durationMs?.let { ms ->
                        val totalSec = ms / 1000
                        val mm = totalSec / 60
                        val ss = totalSec % 60
                        append(" · ").append("%d:%02d".format(mm, ss))
                    }
                }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!result.qualityLabel.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = result.qualityLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            painter = painterResource(result.source.sourceIconRes()),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun SongSourceRow(
    iconRes: Int,
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = checked, onClick = onClick)
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.padding(horizontal = 12.dp).size(24.dp),
        )
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
