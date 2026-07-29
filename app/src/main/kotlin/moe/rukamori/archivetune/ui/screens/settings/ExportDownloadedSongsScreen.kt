/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalDownloadUtil
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.db.entities.detectAudioExtensionFromSpans
import moe.rukamori.archivetune.db.entities.extensionToMimeType
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.utils.backToMain

/**
 * A single downloadable song surfaced in the export picker. The [songId] is the
 * raw media id (no source prefix); the actual cached spans may live under
 * "qobuz:$songId" or "tidal:$songId" — see [resolveSpans].
 */
private data class DownloadedSongRow(
    val songId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationText: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportDownloadedSongsScreen(navController: NavController) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val coroutineScope = rememberCoroutineScope()

    var songs by remember { mutableStateOf<List<DownloadedSongRow>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isExporting by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var exportedCount by remember { mutableStateOf(0) }
    var deletedCount by remember { mutableStateOf(0) }
    var totalCount by remember { mutableStateOf(0) }
    val selectedIds: SnapshotStateList<String> = remember { mutableStateListOf() }

    // Load the list of downloaded songs from the cache + database.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val cache = downloadUtil.downloadCache
            // De-duplicate cache keys by the underlying song id so "qobuz:<id>"
            // and "<id>" don't both show up as separate rows.
            val songIds =
                cache.keys
                    .map { it.substringAfter(":") }
                    .filter { it.isNotBlank() }
                    .distinct()
            val rows =
                songIds.mapNotNull { songId ->
                    // Only include songs that actually have cached spans.
                    val hasSpans =
                        listOf("qobuz:$songId", "tidal:$songId", songId).any { key ->
                            cache.getCachedSpans(key).isNotEmpty()
                        }
                    if (!hasSpans) return@mapNotNull null
                    val songEntity = database.getSongByIdBlocking(songId)
                    val title =
                        songEntity?.song?.title?.takeIf { it.isNotBlank() }
                            ?: "Unknown song ($songId)"
                    val artist =
                        songEntity?.artists?.firstOrNull()?.name?.takeIf { it.isNotBlank() }
                            ?: songEntity?.album?.title?.takeIf { it.isNotBlank() }
                            ?: ""
                    val thumb = songEntity?.song?.thumbnailUrl
                    DownloadedSongRow(
                        songId = songId,
                        title = title,
                        artist = artist,
                        thumbnailUrl = thumb,
                        durationText = null,
                    )
                }.sortedBy { it.title.lowercase() }
            songs = rows
            isLoading = false
        }
    }

    // SAF folder picker — fires when the user taps "Pick export folder".
    val pickFolderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri == null) return@rememberLauncherForActivityResult
            val toExport = songs.filter { it.songId in selectedIds }
            if (toExport.isEmpty()) {
                Toast.makeText(
                    context,
                    context.getString(R.string.export_downloaded_songs_pick_folder_first),
                    Toast.LENGTH_SHORT,
                ).show()
                return@rememberLauncherForActivityResult
            }
            isExporting = true
            totalCount = toExport.size
            exportedCount = 0
            coroutineScope.launch {
                var exported = 0
                var failed = 0
                try {
                    withContext(Dispatchers.IO) {
                        val cache = downloadUtil.downloadCache
                        val parentDocUri =
                            android.provider.DocumentsContract.buildDocumentUriUsingTree(
                                treeUri,
                                android.provider.DocumentsContract.getTreeDocumentId(treeUri),
                            )
                        for (row in toExport) {
                            val spans =
                                resolveSpans(cache, row.songId) ?: run { failed++; continue }
                            val detectedExt = detectAudioExtensionFromSpans(spans)
                            val mime = extensionToMimeType(detectedExt)
                            val safeTitle =
                                row.title
                                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                    .ifBlank { "audio_${row.songId}" }
                            val destUri =
                                android.provider.DocumentsContract.createDocument(
                                    context.contentResolver,
                                    parentDocUri,
                                    mime,
                                    "$safeTitle.$detectedExt",
                                ) ?: run { failed++; continue }
                            runCatching {
                                context.contentResolver.openOutputStream(destUri, "w")?.use { output ->
                                    spans.sortedBy { it.position }.forEach { span ->
                                        java.io.FileInputStream(span.file).use { input ->
                                            input.copyTo(output)
                                        }
                                    }
                                    output.flush()
                                }
                            }.onSuccess {
                                exported++
                                exportedCount = exported
                            }.onFailure { failed++ }
                        }
                    }
                } finally {
                    isExporting = false
                }
                val failedMsg = if (failed > 0) ", $failed failed" else ""
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.export_downloaded_songs_complete,
                        exported,
                        failedMsg,
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    val allSelected = songs.isNotEmpty() && selectedIds.size == songs.size

    /**
     * Deletes the cached spans for the currently-selected song IDs. Releases
     * the underlying cache entries via [Cache.removeResource] for every
     * source-prefixed key the song may live under (qobuz:, tidal:, bare id).
     * Updates the in-memory list so the UI reflects the deletion immediately.
     */
    fun deleteSelected() {
        val toDelete = songs.filter { it.songId in selectedIds }
        if (toDelete.isEmpty()) return
        isDeleting = true
        totalCount = toDelete.size
        deletedCount = 0
        coroutineScope.launch {
            var deleted = 0
            var failed = 0
            try {
                withContext(Dispatchers.IO) {
                    val cache = downloadUtil.downloadCache
                    val playerCache = downloadUtil.playerCache
                    for (row in toDelete) {
                        var removed = false
                        for (key in listOf("qobuz:${row.songId}", "tidal:${row.songId}", row.songId)) {
                            runCatching { cache.removeResource(key) }.onSuccess { removed = true }
                            runCatching { playerCache.removeResource(key) }.onSuccess { removed = true }
                        }
                        if (removed) deleted++ else failed++
                        deletedCount = deleted
                    }
                    // Also cancel any pending Media3 download requests for these ids
                    // so they don't immediately re-create the cache entries.
                    runCatching {
                        toDelete.forEach { row ->
                            downloadUtil.downloadManager.removeDownload(row.songId)
                        }
                    }
                }
            } finally {
                isDeleting = false
            }
            // Refresh the song list to reflect deletions.
            val failedMsg = if (failed > 0) ", $failed failed" else ""
            Toast.makeText(
                context,
                context.getString(
                    R.string.export_downloaded_songs_delete_complete,
                    deleted,
                    failedMsg,
                ),
                Toast.LENGTH_LONG,
            ).show()
            // Update the local list + clear selection.
            val deletedIds = toDelete.map { it.songId }.toSet()
            songs = songs.filterNot { it.songId in deletedIds }
            selectedIds.removeAll(deletedIds)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.export_downloaded_songs)) },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    if (songs.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                if (allSelected) selectedIds.clear()
                                else {
                                    selectedIds.clear()
                                    selectedIds.addAll(songs.map { it.songId })
                                }
                            },
                            onLongClick = {},
                        ) {
                            Icon(
                                painter =
                                    painterResource(
                                        if (allSelected) R.drawable.player_deselect else R.drawable.select_all,
                                    ),
                                contentDescription = null,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (songs.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 4.dp,
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(
                                    LocalPlayerAwareWindowInsets.current.only(
                                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                                    ),
                                ).padding(16.dp),
                    ) {
                        // Count + progress line — full width so the "X of Y selected"
                        // text never gets squeezed into a vertical strip by the two
                        // action buttons below it (previously rendered as "1 / o / f / 1 / …").
                        Text(
                            text =
                                stringResource(
                                    R.string.export_downloaded_songs_selected_count,
                                    selectedIds.size,
                                    songs.size,
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                        if (isExporting || isDeleting) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text =
                                    if (isExporting) {
                                        stringResource(
                                            R.string.export_downloaded_songs_progress,
                                            exportedCount,
                                            totalCount,
                                        )
                                    } else {
                                        stringResource(
                                            R.string.export_downloaded_songs_delete_progress,
                                            deletedCount,
                                            totalCount,
                                        )
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Delete button — destructive action, gated by a confirmation dialog.
                            OutlinedButton(
                                onClick = { showDeleteConfirm = true },
                                enabled = !isExporting && !isDeleting && selectedIds.isNotEmpty(),
                                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                                modifier = Modifier.weight(1f),
                            ) {
                                if (isDeleting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                } else {
                                    Icon(
                                        painter = painterResource(R.drawable.delete),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(stringResource(R.string.export_downloaded_songs_delete))
                            }
                            FilledTonalButton(
                                onClick = { pickFolderLauncher.launch(null) },
                                enabled = !isExporting && !isDeleting && selectedIds.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            ) {
                                if (isExporting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                } else {
                                    Icon(
                                        painter = painterResource(R.drawable.send),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(stringResource(R.string.export_downloaded_songs_pick_folder))
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
            songs.isEmpty() -> {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.export_downloaded_songs_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(
                            top = innerPadding.calculateTopPadding(),
                            bottom = 120.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(songs, key = { it.songId }) { row ->
                        val isSelected = row.songId in selectedIds
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isSelected) selectedIds.remove(row.songId)
                                        else selectedIds.add(row.songId)
                                    }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (!row.thumbnailUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = row.thumbnailUrl,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(R.drawable.music_note),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = row.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (row.artist.isNotBlank()) {
                                    Text(
                                        text = row.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Box(
                                modifier =
                                    Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            },
                                        ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) {
                                    Icon(
                                        painter = painterResource(R.drawable.check),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog — destructive action needs an explicit OK.
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.export_downloaded_songs_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.export_downloaded_songs_delete_confirm_message,
                        selectedIds.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        deleteSelected()
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.export_downloaded_songs_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

/**
 * Returns the cached spans for [songId], preferring source-prefixed keys
 * ("qobuz:$songId" / "tidal:$songId") so the export pulls the lossless FLAC
 * bytes when available, falling back to the bare media id.
 */
private fun resolveSpans(
    cache: androidx.media3.datasource.cache.Cache,
    songId: String,
): java.util.NavigableSet<androidx.media3.datasource.cache.CacheSpan>? {
    for (key in listOf("qobuz:$songId", "tidal:$songId", songId)) {
        val spans = cache.getCachedSpans(key)
        if (spans.isNotEmpty()) return spans
    }
    return null
}
