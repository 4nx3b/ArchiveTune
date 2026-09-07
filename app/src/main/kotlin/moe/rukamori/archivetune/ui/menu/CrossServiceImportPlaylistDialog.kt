/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.menu

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.InnerTubeCookieKey
import moe.rukamori.archivetune.constants.YtmSyncKey
import moe.rukamori.archivetune.db.entities.PlaylistEntity
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.utils.hasYouTubeLoginCookie
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playlist.CrossServiceImportCredentials
import moe.rukamori.archivetune.playlist.CrossServicePlaylistImporter
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.utils.dataStore
import timber.log.Timber
import java.time.LocalDateTime

@Composable
fun CrossServiceImportPlaylistDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
) {
    val database = LocalDatabase.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var urlValue by remember { mutableStateOf(TextFieldValue("")) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var resolvedCount by remember { mutableStateOf(0) }
    var totalCount by remember { mutableStateOf(0) }

    if (!isVisible) return

    fun resetState() {
        urlValue = TextFieldValue("")
        isLoading = false
        statusMessage = null
        resolvedCount = 0
        totalCount = 0
    }

    DefaultDialog(
        onDismiss = {
            if (!isLoading) {
                resetState()
                onDismiss()
            }
        },
        title = { Text(text = stringResource(R.string.cross_service_import_playlist_title)) },
        icon = { Icon(painter = painterResource(R.drawable.playlist_import), contentDescription = null) },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                OutlinedTextField(
                    value = urlValue,
                    onValueChange = { urlValue = it },
                    label = { Text(stringResource(R.string.cross_service_import_playlist_url_label)) },
                    placeholder = { Text(stringResource(R.string.cross_service_import_playlist_url_placeholder)) },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.cross_service_import_playlist_supported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (statusMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = statusMessage!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (isLoading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    CircularWavyProgressIndicator(modifier = Modifier.size(36.dp))
                    if (totalCount > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(
                                R.string.cross_service_import_progress,
                                resolvedCount,
                                totalCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        buttons = {
            TextButton(
                enabled = !isLoading,
                onClick = {
                    resetState()
                    onDismiss()
                },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(text = stringResource(android.R.string.cancel))
            }
            Button(
                enabled = !isLoading && urlValue.text.isNotBlank(),
                onClick = {
                    val url = urlValue.text.trim()
                    if (url.isBlank()) return@Button
                    isLoading = true
                    statusMessage = context.getString(R.string.cross_service_import_resolving_playlist)
                    coroutineScope.launch(Dispatchers.IO) {
                        try {

                            val credentials = CrossServiceImportCredentials.load(context)
                            val resolved = CrossServicePlaylistImporter.fetchPlaylist(url, credentials)
                                .getOrElse { e ->
                                    withContext(Dispatchers.Main) {
                                        statusMessage = null
                                        isLoading = false
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.cross_service_import_failed,
                                                e.message ?: "Unknown error",
                                            ),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                    return@launch
                                }

                            if (resolved.tracks.isEmpty()) {
                                withContext(Dispatchers.Main) {
                                    statusMessage = null
                                    isLoading = false
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.cross_service_import_no_tracks),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                                return@launch
                            }

                            val songs: List<MediaMetadata> =
                                if (resolved.source == CrossServicePlaylistImporter.ImportSource.YOUTUBE_MUSIC) {
                                    CrossServicePlaylistImporter.fetchYouTubePlaylistSongs(resolved.sourcePlaylistId)
                                } else {
                                    withContext(Dispatchers.Main) {
                                        statusMessage = context.getString(
                                            R.string.cross_service_import_searching_yt,
                                            resolved.tracks.size,
                                        )
                                        totalCount = resolved.tracks.size
                                        resolvedCount = 0
                                    }
                                    CrossServicePlaylistImporter.resolveToYouTubeMusicMetadata(
                                        tracks = resolved.tracks,
                                        onProgress = { done, total ->
                                            resolvedCount = done
                                            totalCount = total
                                        },
                                    )
                                }

                            if (songs.isEmpty()) {
                                withContext(Dispatchers.Main) {
                                    statusMessage = null
                                    isLoading = false
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.cross_service_import_no_matches),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                                return@launch
                            }

                            database.withTransaction {
                                songs.forEach { meta -> insert(meta) }
                            }
                            val songIds = songs.map { it.id }

                            val playlistName = resolved.title.ifBlank {
                                "${resolved.source.displayName} Import"
                            }

                            val syntheticBrowseId = "import:${resolved.source.name}:${resolved.sourcePlaylistId}"
                            val existing = database.playlistByBrowseId(syntheticBrowseId).firstOrNull()
                            val targetPlaylistId = if (existing != null) {
                                database.query {
                                    update(
                                        existing.playlist.copy(
                                            name = playlistName,
                                            bookmarkedAt = existing.playlist.bookmarkedAt ?: LocalDateTime.now(),
                                            lastUpdateTime = LocalDateTime.now(),
                                        ),
                                    )
                                }
                                existing.playlist.id
                            } else {
                                val newPlaylist = PlaylistEntity(
                                    name = playlistName,
                                    browseId = syntheticBrowseId,
                                    isEditable = true,
                                    bookmarkedAt = LocalDateTime.now(),
                                    thumbnailUrl = resolved.thumbnailUrl,
                                )
                                database.query { insert(newPlaylist) }
                                newPlaylist.id
                            }

                            val playlist = database.playlist(targetPlaylistId).firstOrNull()
                            if (playlist != null) {
                                database.addSongToPlaylist(playlist, songIds)
                            }

                            val preferences = context.dataStore.data.firstOrNull()
                            val isSignedIn = preferences != null &&
                                hasYouTubeLoginCookie(preferences[InnerTubeCookieKey].orEmpty())
                            val isYtSyncEnabled = preferences == null || (preferences[YtmSyncKey] ?: true)

                            if (isSignedIn && isYtSyncEnabled && songIds.isNotEmpty()) {

                                YouTube.createPlaylist(playlistName, songIds)
                                    .onSuccess { remoteBrowseId ->
                                        if (remoteBrowseId.isNotBlank()) {
                                            val toUpdate = database.playlist(targetPlaylistId).firstOrNull()
                                            if (toUpdate != null) {
                                                database.query {
                                                    update(
                                                        toUpdate.playlist.copy(
                                                            browseId = remoteBrowseId,
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
                                            "Remote YT Music playlist creation failed during import; " +
                                                "playlist remains local-only.",
                                        )
                                    }
                            }

                            withContext(Dispatchers.Main) {
                                isLoading = false
                                statusMessage = null
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.cross_service_import_success,
                                        songIds.size,
                                        resolved.tracks.size,
                                        playlistName,
                                    ),
                                    Toast.LENGTH_LONG,
                                ).show()
                                resetState()
                                onDismiss()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                statusMessage = null
                                isLoading = false
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.cross_service_import_failed,
                                        e.message ?: "Unknown error",
                                    ),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(text = stringResource(R.string.cross_service_import_action))
            }
        },
    )
}
