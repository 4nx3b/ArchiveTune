/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.menu

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalSyncUtils
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.InnerTubeCookieKey
import moe.rukamori.archivetune.constants.ListThumbnailSize
import moe.rukamori.archivetune.constants.YtmSyncKey
import moe.rukamori.archivetune.db.entities.Playlist
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.ui.component.CreatePlaylistDialog
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.ListDialog
import moe.rukamori.archivetune.ui.component.ListItem
import moe.rukamori.archivetune.ui.component.PlaylistListItem
import moe.rukamori.archivetune.innertube.utils.hasYouTubeLoginCookie
import moe.rukamori.archivetune.utils.dataStore
import timber.log.Timber
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.firstOrNull

@Composable
fun AddToPlaylistDialogOnline(
    isVisible: Boolean,
    allowSyncing: Boolean = true,
    initialTextFieldValue: String? = null,
    songs: SnapshotStateList<Song>,
    onDismiss: () -> Unit,
    onProgressStart: (Boolean) -> Unit,
    onPercentageChange: (Int) -> Unit,
    onStatusChange: (String) -> Unit = {},
) {
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val syncUtils = LocalSyncUtils.current
    var allPlaylists by remember { mutableStateOf(emptyList<Playlist>()) }
    val playlists = remember(allPlaylists) { playlistsForAddToPlaylist(allPlaylists).asReversed() }

    var showCreatePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var selectedPlaylist by remember {
        mutableStateOf<Playlist?>(null)
    }
    val songIds by remember {
        mutableStateOf<List<String>?>(null)
    }

    var showResultDialog by remember { mutableStateOf(false) }
    var processingSummary by remember { mutableStateOf<ProcessingSummary?>(null) }

    LaunchedEffect(Unit) {
        database.playlistsByCreateDateAsc().collect {
            allPlaylists = it
        }
    }

    fun processSongs(
        targetPlaylist: Playlist?,
        addToLiked: Boolean,
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            val snapshotSongs = songs.toList()
            val total = snapshotSongs.size
            if (total == 0) {
                withContext(Dispatchers.Main) {
                    onProgressStart(false)
                    onPercentageChange(0)
                    onDismiss()
                }
                return@launch
            }

            try {
                withContext(Dispatchers.Main) {
                    onProgressStart(true)
                    onPercentageChange(0)
                    onStatusChange("Preparing import...")
                    onDismiss()
                }

                val processed = AtomicInteger(0)
                val successCount = AtomicInteger(0)
                val failCount = AtomicInteger(0)
                val failedSongs = mutableListOf<String>()

                // YouTube Music ids of the songs that actually landed in the
                // local playlist — what the auto-sync below pushes remotely.
                val succeededIds = java.util.Collections.synchronizedList(mutableListOf<String>())

                val semaphore = Semaphore(5)

                val tasks =
                    snapshotSongs.map { song ->
                        async {
                            semaphore.withPermit {
                                val allArtists =
                                    song.artists
                                        .joinToString(" ") { artist ->
                                            try {
                                                URLDecoder.decode(artist.name, StandardCharsets.UTF_8.toString())
                                            } catch (e: Exception) {
                                                artist.name
                                            }
                                        }.trim()

                                val query =
                                    if (allArtists.isEmpty()) {
                                        song.title
                                    } else {
                                        "${song.title} - $allArtists"
                                    }

                                var success = false
                                try {
                                    val result = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                                    result
                                        .onSuccess { search ->
                                            val firstSong = search.items.distinctBy { it.id }.firstOrNull() as? SongItem
                                            if (firstSong != null) {
                                                val media = firstSong.toMediaMetadata()
                                                val ids = listOf(firstSong.id)
                                                try {
                                                    database.insert(media)
                                                    if (targetPlaylist != null) {
                                                        database.addSongToPlaylist(targetPlaylist, ids)
                                                    }
                                                    if (addToLiked) {
                                                        val entity = media.toSongEntity()
                                                        database.query {
                                                            update(entity.toggleLike())
                                                        }
                                                    }
                                                    synchronized(succeededIds) {
                                                        succeededIds.addAll(ids)
                                                    }
                                                    success = true
                                                } catch (e: Exception) {
                                                    Timber.e(e, "Error inserting/adding song")
                                                }
                                            }
                                        }.onFailure {
                                            Timber.w(it, "Search failed for $query")
                                        }
                                } catch (e: Exception) {
                                    Timber.e(e, "Error processing song $query")
                                }

                                if (success) {
                                    successCount.incrementAndGet()
                                } else {
                                    failCount.incrementAndGet()
                                    synchronized(failedSongs) {
                                        failedSongs.add(song.title)
                                    }
                                }

                                val currentProcessed = processed.incrementAndGet()
                                val percent =
                                    ((currentProcessed.toDouble() / total.toDouble()) * 100)
                                        .toInt()
                                        .coerceIn(0, 100)

                                withContext(Dispatchers.Main) {
                                    onPercentageChange(percent)
                                    onStatusChange("Importing: $currentProcessed/$total\nFailed: ${failCount.get()}")
                                }
                            }
                        }
                    }

                runCatching { tasks.awaitAll() }.onFailure {
                    Timber.e(it, "Import failed")
                }

                // Auto-sync (2026-09-12): importing a playlist finishes into a
                // local playlist that used to sit there until the user opened
                // the playlist menu and pressed Sync. Now the import itself
                // pushes it: a remote playlist gets an incremental sync (which
                // uploads the just-added songs), and a local-only playlist gets
                // its YouTube counterpart created and linked, exactly like the
                // cross-service import dialog does.
                if (targetPlaylist != null && succeededIds.isNotEmpty()) {
                    runCatching {
                        val preferences = context.dataStore.data.firstOrNull()
                        val isSignedIn = preferences != null &&
                            hasYouTubeLoginCookie(preferences[InnerTubeCookieKey].orEmpty())
                        val isYtSyncEnabled = preferences == null || (preferences[YtmSyncKey] ?: true)
                        if (isSignedIn && isYtSyncEnabled) {
                            val livePlaylist = database.playlist(targetPlaylist.id).firstOrNull() ?: targetPlaylist
                            val remoteBrowseId = livePlaylist.playlist.browseId
                            if (!remoteBrowseId.isNullOrBlank()) {
                                withContext(Dispatchers.Main) {
                                    onStatusChange("Syncing playlist to YouTube Music...")
                                }
                                syncUtils.syncPlaylistNow(remoteBrowseId, livePlaylist.id)
                            } else {
                                withContext(Dispatchers.Main) {
                                    onStatusChange("Creating remote playlist...")
                                }
                                YouTube.createPlaylist(livePlaylist.playlist.name, succeededIds.toList())
                                    .onSuccess { createdBrowseId ->
                                        if (createdBrowseId.isNotBlank()) {
                                            val toUpdate = database.playlist(livePlaylist.id).firstOrNull()
                                            if (toUpdate != null) {
                                                database.query {
                                                    update(
                                                        toUpdate.playlist.copy(
                                                            browseId = createdBrowseId,
                                                            isEditable = true,
                                                            bookmarkedAt = toUpdate.playlist.bookmarkedAt ?: LocalDateTime.now(),
                                                            lastUpdateTime = LocalDateTime.now(),
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                    }.onFailure { error ->
                                        Timber.w(
                                            error,
                                            "Remote YT Music playlist creation after import failed; " +
                                                "playlist remains local-only.",
                                        )
                                    }
                            }
                        }
                    }.onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        Timber.w(error, "Post-import playlist sync failed; playlist remains local-only.")
                    }
                }

                withContext(Dispatchers.Main) {
                    onPercentageChange(100)
                    processingSummary =
                        ProcessingSummary(
                            total = total,
                            success = successCount.get(),
                            failed = failCount.get(),
                            failedItems = failedSongs,
                        )
                    showResultDialog = true
                }
            } finally {
                withContext(Dispatchers.Main) {
                    onProgressStart(false)
                }
            }
        }
    }

    if (isVisible) {
        ListDialog(
            onDismiss = onDismiss,
        ) {
            item {
                ListItem(
                    title = stringResource(R.string.create_playlist),
                    thumbnailContent = {
                        Image(
                            painter = painterResource(id = R.drawable.playlist_add),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                            modifier = Modifier.size(ListThumbnailSize),
                        )
                    },
                    modifier =
                        Modifier.clickable {
                            showCreatePlaylistDialog = true
                        },
                )
            }

            items(playlists, key = { it.id }) { playlist ->
                PlaylistListItem(
                    playlist = playlist,
                    modifier =
                        Modifier.clickable {
                            selectedPlaylist = playlist
                            processSongs(targetPlaylist = playlist, addToLiked = false)
                        },
                )
            }

            item {
                ListItem(
                    modifier =
                        Modifier.clickable {
                            processSongs(targetPlaylist = null, addToLiked = true)
                        },
                    title = stringResource(R.string.liked_songs),
                    thumbnailContent = {
                        Image(
                            painter = painterResource(id = R.drawable.favorite),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                        )
                    },
                    trailingContent = {},
                )
            }

            item {
                Text(
                    text = stringResource(R.string.playlist_add_local_to_synced_note),
                    fontSize = TextUnit(12F, TextUnitType.Sp),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            initialTextFieldValue = initialTextFieldValue,
            allowSyncing = allowSyncing,
        )
    }

    if (showResultDialog && processingSummary != null) {
        val summary = processingSummary!!
        DefaultDialog(
            title = { Text("Import Complete") },
            onDismiss = { showResultDialog = false },
            buttons = {
                TextButton(onClick = { showResultDialog = false }, shapes = ButtonDefaults.shapes()) {
                    Text("OK")
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Total Processed: ${summary.total}")
                Text("Successfully Imported: ${summary.success}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                if (summary.failed > 0) {
                    Text("Failed: ${summary.failed}", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Failed Items:", style = MaterialTheme.typography.labelLarge)
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                    ) {
                        items(summary.failedItems, key = { it }) { title ->
                            Text(
                                text = "• $title",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

data class ProcessingSummary(
    val total: Int,
    val success: Int,
    val failed: Int,
    val failedItems: List<String>,
)
