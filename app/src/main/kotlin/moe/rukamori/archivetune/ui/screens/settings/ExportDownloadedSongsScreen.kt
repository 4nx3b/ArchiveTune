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
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalDownloadUtil
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.DownloadSource
import moe.rukamori.archivetune.constants.DownloadSourceConfig
import moe.rukamori.archivetune.constants.DownloadSourceOrderKey
import moe.rukamori.archivetune.db.entities.detectAudioExtensionFromSpans
import moe.rukamori.archivetune.db.entities.extensionToMimeType
import moe.rukamori.archivetune.ui.component.FrostedHeaderPill
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.dataStore
import androidx.compose.foundation.layout.asPaddingValues
import moe.rukamori.archivetune.ui.component.KeepStatusBarHiddenInDialog

/**
 * Every cache key a song's downloaded bytes can live under, ordered by the
 * user's download-source priority (YOUTUBE_MUSIC maps to the plain mediaId
 * key). The plain key is appended last as the final fallback so unknown
 * future sources still resolve. This replaces the old hardcoded
 * [qobuz:|tidal:|deezer:|plain] list, which made jiosaavn:/apple:/
 * qobuz_backup: downloads invisible in this page AND unexportable.
 */
private fun downloadCandidateKeys(
    songId: String,
    order: List<DownloadSource>,
): List<String> =
    buildList {
        for (source in order) {
            when (source) {
                DownloadSource.YOUTUBE_MUSIC -> add(songId)
                DownloadSource.AUTO -> {}
                else -> add("${source.name.lowercase(java.util.Locale.US)}:$songId")
            }
        }
        add(songId)
    }.distinct()

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
    var exportedCount by remember { mutableIntStateOf(0) }
    var deletedCount by remember { mutableIntStateOf(0) }
    var totalCount by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    val selectedIds: SnapshotStateList<String> = remember { mutableStateListOf() }
    var sourceOrder by remember {
        mutableStateOf<List<DownloadSource>>(DownloadSourceConfig.DEFAULT_ORDER)
    }

    val displayedSongs = remember(songs, searchQuery) {
        if (searchQuery.isBlank()) songs
        else {
            val q = searchQuery.lowercase().trim()
            songs.filter {
                it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val cache = downloadUtil.downloadCache

            // The user's download-source priority decides which source's
            // cached spans win when several exist for the same song.
            val storedOrder = runCatching {
                runBlocking { context.dataStore.data.first()[DownloadSourceOrderKey] }
            }.getOrNull()
            val order = DownloadSourceConfig.parseOrder(storedOrder)
            sourceOrder = order

            val songIds =
                cache.keys
                    .map { it.substringAfter(":") }
                    .filter { it.isNotBlank() }
                    .distinct()
            val rows =
                songIds.mapNotNull { songId ->

                    val hasSpans = downloadCandidateKeys(songId, order).any { key ->
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
                var skippedIncompatible = 0
                try {
                    withContext(Dispatchers.IO) {
                        val cache = downloadUtil.downloadCache
                        val parentDocUri =
                            android.provider.DocumentsContract.buildDocumentUriUsingTree(
                                treeUri,
                                android.provider.DocumentsContract.getTreeDocumentId(treeUri),
                            )
                        val tempDir = java.io.File(context.cacheDir, "export_tmp").apply { mkdirs() }
                        loop@ for (row in toExport) {
                            val resolved = resolveSpansWithSource(cache, row.songId, sourceOrder) ?: run { failed++; continue@loop }
                            val spans = resolved.spans

                            if (spans.isEmpty()) { failed++; continue@loop }
                            val totalSpanBytes = spans.sumOf { it.length }
                            if (totalSpanBytes <= 0L) { failed++; continue@loop }
                            val detectedExt = detectAudioExtensionFromSpans(spans)

                            if (detectedExt == "webm" || detectedExt == "opus") {
                                skippedIncompatible++
                                continue@loop
                            }

                            val isYouTubeSource = resolved.sourceKey == null

                            // Export with the extension the bytes actually have —
                            // the old `if (isYouTubeSource) "mp3"` override
                            // mislabeled m4a (itag 140) downloads as .mp3, which
                            // jaudiotagger and most players then fail to open.
                            val exportExt = detectedExt
                            val mime = extensionToMimeType(exportExt)
                            val safeTitle =
                                row.title
                                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                    .ifBlank { "audio_${row.songId}" }

                            val tempFile = java.io.File(tempDir, "${row.songId}.$detectedExt")
                            try {
                                runCatching {
                                    java.io.FileOutputStream(tempFile).use { output ->
                                        spans.sortedBy { it.position }.forEach { span ->
                                            java.io.FileInputStream(span.file).use { input ->
                                                input.copyTo(output)
                                            }
                                        }
                                        output.flush()
                                    }
                                }.getOrElse {
                                    tempFile.delete()
                                    failed++
                                    continue@loop
                                }

                                val resolvedMetadata = resolveExportMetadata(database, row, isYouTubeSource)
                                moe.rukamori.archivetune.playback.AudioTagger.tag(tempFile, resolvedMetadata)

                                val destUri =
                                    android.provider.DocumentsContract.createDocument(
                                        context.contentResolver,
                                        parentDocUri,
                                        mime,
                                        "$safeTitle.$exportExt",
                                    ) ?: run { failed++; continue@loop }
                                runCatching {
                                    context.contentResolver.openOutputStream(destUri, "w")?.use { output ->
                                        java.io.FileInputStream(tempFile).use { input ->
                                            input.copyTo(output)
                                        }
                                        output.flush()
                                    }
                                }.onSuccess {
                                    exported++
                                    exportedCount = exported
                                }.onFailure { failed++ }
                            } finally {
                                tempFile.delete()
                            }
                        }

                        runCatching { tempDir.listFiles()?.forEach { it.delete() } }
                    }
                } finally {
                    isExporting = false
                }
                val failedMsg = if (failed > 0) ", $failed failed" else ""
                val skippedMsg = if (skippedIncompatible > 0) {
                    "\n" + context.getString(
                        R.string.export_downloaded_songs_skipped_incompatible,
                        skippedIncompatible,
                    )
                } else ""
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.export_downloaded_songs_complete,
                        exported,
                        failedMsg,
                    ) + skippedMsg,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    val allSelected = displayedSongs.isNotEmpty() && selectedIds.size == displayedSongs.size

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
                        for (key in downloadCandidateKeys(row.songId, sourceOrder)) {
                            runCatching { cache.removeResource(key) }.onSuccess { removed = true }
                            runCatching { playerCache.removeResource(key) }.onSuccess { removed = true }
                        }
                        if (removed) deleted++ else failed++
                        deletedCount = deleted
                    }

                    runCatching {
                        toDelete.forEach { row ->
                            downloadUtil.downloadManager.removeDownload(row.songId)
                        }
                    }
                }
            } finally {
                isDeleting = false
            }

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

            val deletedIds = toDelete.map { it.songId }.toSet()
            songs = songs.filterNot { it.songId in deletedIds }
            selectedIds.removeAll(deletedIds)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    FrostedHeaderPill(plain = true) {
                        IconButton(
                            onClick = {
                                if (isSearchActive) {
                                    isSearchActive = false
                                    searchQuery = ""
                                } else {
                                    navController.navigateUp()
                                }
                            },
                            onLongClick = navController::backToMain,
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (isSearchActive) R.drawable.arrow_back else R.drawable.arrow_back,
                                ),
                                contentDescription = null,
                            )
                        }
                        Text(
                            text = stringResource(R.string.search),
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                },
                actions = {
                    if (!isSearchActive && songs.isNotEmpty()) {

                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                painter = painterResource(R.drawable.search),
                                contentDescription = stringResource(R.string.search),
                            )
                        }
                        IconButton(
                            onClick = {
                                if (allSelected) selectedIds.clear()
                                else {
                                    selectedIds.clear()
                                    selectedIds.addAll(displayedSongs.map { it.songId })
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
                                    // Include the Bottom side so the selection bar
                                    // (count + export/delete buttons) pads above the
                                    // miniplayer and the gesture bar — previously the
                                    // Horizontal-only inset let the miniplayer overlap
                                    // and half-hide these controls.
                                    LocalPlayerAwareWindowInsets.current.only(
                                        WindowInsetsSides.Horizontal +
                                            WindowInsetsSides.Bottom,
                                    ),
                                ).padding(16.dp),
                    ) {

                        Text(
                            text =
                                stringResource(
                                    R.string.export_downloaded_songs_selected_count,
                                    selectedIds.size,
                                    displayedSongs.size,
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
        val playerAwareBottomPadding =
            LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()
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
            displayedSongs.isEmpty() -> {

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
                        painter = painterResource(R.drawable.search_off),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.export_downloaded_songs_search_empty, searchQuery),
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
                            bottom = playerAwareBottomPadding + 120.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(displayedSongs, key = { it.songId }) { row ->
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
                KeepStatusBarHiddenInDialog()
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

private data class ResolvedSpansWithSource(
    val spans: java.util.NavigableSet<androidx.media3.datasource.cache.CacheSpan>,
    val sourceKey: String?,
)

private fun resolveSpansWithSource(
    cache: androidx.media3.datasource.cache.Cache,
    songId: String,
    order: List<DownloadSource>,
): ResolvedSpansWithSource? {
    for (key in downloadCandidateKeys(songId, order)) {
        val spans = cache.getCachedSpans(key)
        if (spans.isNotEmpty()) {
            val sourceKey = key.takeIf { it != songId }
            return ResolvedSpansWithSource(spans, sourceKey)
        }
    }
    return null
}

private fun fetchArtworkBytes(url: String): ByteArray? = runCatching {
    val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 15_000
    connection.requestMethod = "GET"
    connection.setRequestProperty("User-Agent", "ArchiveTune")
    connection.instanceFollowRedirects = true
    connection.useCaches = true
    try {
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) return@runCatching null
        val contentType = connection.contentType ?: ""

        if (!contentType.startsWith("image/")) return@runCatching null
        connection.inputStream.use { it.readBytes() }
    } finally {
        connection.disconnect()
    }
}.getOrNull()

private suspend fun resolveExportMetadata(
    database: moe.rukamori.archivetune.db.MusicDatabase,
    row: DownloadedSongRow,
    isYouTubeSource: Boolean,
): moe.rukamori.archivetune.playback.AudioTagger.Metadata {
    val songEntity = database.getSongByIdBlocking(row.songId)

    val dbTitle = songEntity?.song?.title?.takeIf(String::isNotBlank)
    val title = dbTitle ?: row.title.takeIf { it.isNotBlank() }

    val dbArtists = songEntity?.artists?.mapNotNull { it.name.takeIf(String::isNotBlank) }
        ?.takeIf { it.isNotEmpty() }
    val dbArtistStr = dbArtists?.joinToString(", ")

    val dbAlbum = songEntity?.album?.title?.takeIf(String::isNotBlank)
        ?: songEntity?.song?.albumName?.takeIf(String::isNotBlank)

    val dbYear = songEntity?.song?.year?.takeIf { it > 0 }

    val dbThumb = songEntity?.song?.thumbnailUrl?.takeIf(String::isNotBlank)
    val thumbUrl = dbThumb ?: row.thumbnailUrl?.takeIf(String::isNotBlank)

    val hasFullMetadata = title != null && !dbArtistStr.isNullOrBlank() && thumbUrl != null
    if (hasFullMetadata) {
        val artworkBytes = thumbUrl?.let { fetchArtworkBytes(it) }
        return moe.rukamori.archivetune.playback.AudioTagger.Metadata(
            title = title,
            artist = dbArtistStr,
            albumArtist = dbArtists?.firstOrNull(),
            album = dbAlbum,
            year = dbYear,
            artworkBytes = artworkBytes,
        )
    }

    val mediaInfo = runCatching {
        if (isYouTubeSource) {
            moe.rukamori.archivetune.innertube.YouTube.getMediaInfo(row.songId).getOrNull()
        } else {
            null
        }
    }.getOrNull()

    val resolvedTitle = title
        ?: mediaInfo?.title?.takeIf(String::isNotBlank)
        ?: row.title
    val resolvedArtist = dbArtistStr
        ?: mediaInfo?.author?.takeIf(String::isNotBlank)
        ?: ""

    val resolvedThumb = thumbUrl
        ?: if (isYouTubeSource) "https://i.ytimg.com/vi/${row.songId}/hqdefault.jpg" else null

    val artworkBytes = resolvedThumb?.let { fetchArtworkBytes(it) }

    return moe.rukamori.archivetune.playback.AudioTagger.Metadata(
        title = resolvedTitle?.takeIf(String::isNotBlank),
        artist = resolvedArtist.takeIf(String::isNotBlank),
        albumArtist = (dbArtists?.firstOrNull() ?: mediaInfo?.author)?.takeIf(String::isNotBlank),
        album = dbAlbum,
        year = dbYear,
        artworkBytes = artworkBytes,
    )
}
