/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.downloads

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.media3.exoplayer.offline.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.DownloadSource
import moe.rukamori.archivetune.constants.DownloadSourceConfig
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.models.toMediaMetadata
import javax.inject.Inject
import kotlin.math.roundToInt

enum class DownloadMediaType {
    PLAYLIST,
    ALBUM,
    SONG,
}

@Immutable
data class DownloadEntryUiModel(
    val id: String,
    val title: String,
    val supportingText: String?,
    val thumbnailUrl: String?,
    val destinationRoute: String?,
    val playbackMetadata: MediaMetadata?,
    val durationSeconds: Int?,
    val songIds: List<String>,
    val totalCount: Int,
    val progress: Float,
    val percent: Int,
    val speedBytesPerSecond: Long,
    val paused: Boolean,
    val failed: Boolean,
)

@Immutable
data class DownloadSectionUiModel(
    val mediaType: DownloadMediaType,
    val entries: List<DownloadEntryUiModel>,
) {
    val songIds: List<String> = entries.flatMap(DownloadEntryUiModel::songIds).distinct()
    val paused: Boolean = entries.isNotEmpty() && entries.all(DownloadEntryUiModel::paused)
    val percent: Int =
        entries
            .map(DownloadEntryUiModel::progress)
            .average()
            .times(100)
            .roundToInt()
            .coerceIn(0, 100)
    val speedBytesPerSecond: Long = entries.sumOf(DownloadEntryUiModel::speedBytesPerSecond)
}

@Immutable
data class DownloadLibraryUiModel(
    val downloadedSections: List<DownloadSectionUiModel>,
    val progressSections: List<DownloadSectionUiModel>,
) {
    val isEmpty: Boolean = downloadedSections.isEmpty() && progressSections.isEmpty()
}

class ManageDownloadsUseCase
    @Inject
    constructor(
        private val repository: Media3DownloadRepository,
    ) {
        private val byteSamples = mutableMapOf<String, ByteSample>()

        fun observe(): Flow<DownloadLibraryUiModel> = repository.observeDownloads().map(::mapSnapshot).flowOn(Dispatchers.Default)

        fun pause(songIds: Collection<String>) = repository.pause(songIds)

        fun resume(songIds: Collection<String>) = repository.resume(songIds)

        fun remove(songIds: Collection<String>) = repository.remove(songIds)

        private fun mapSnapshot(snapshot: DownloadRepositorySnapshot): DownloadLibraryUiModel {
            val now = SystemClock.elapsedRealtime()
            val speeds = calculateSpeeds(snapshot.downloads, now)
            // Download index ids are source-scoped ("ytm:<id>", "qobuz:<id>",
            // ... plus legacy plain "<id>"); DB groupings key off the RAW song
            // id, so normalize once here and map back for actions.
            val songsById = snapshot.songs.associateBy { it.song.id }
            val downloadIdsByRawSongId: Map<String, List<String>> =
                snapshot.downloads.keys.groupBy { DownloadSourceConfig.downloadIdToSongId(it) }
            val completedIds =
                snapshot.downloads.values
                    .filter { it.state == Download.STATE_COMPLETED }
                    .mapTo(mutableSetOf()) { it.request.id }
            val completedRawIds = completedIds.mapTo(mutableSetOf()) { DownloadSourceConfig.downloadIdToSongId(it) }
            val activeIds =
                snapshot.downloads.values
                    .filter { it.isVisibleInProgress() }
                    .mapTo(mutableSetOf()) { it.request.id }
            val activeRawIds = activeIds.mapTo(mutableSetOf()) { DownloadSourceConfig.downloadIdToSongId(it) }

            val albumSongIds =
                snapshot.songs
                    .mapNotNull { song ->
                        val albumId = song.album?.id ?: song.song.albumId
                        albumId?.let { it to song.song.id }
                    }.groupBy({ it.first }, { it.second })
            val playlistSongIds =
                snapshot.playlistSongMaps
                    .groupBy({ it.playlistId }, { it.songId })

            val downloadedSections =
                buildList {
                    buildPlaylistEntries(
                        snapshot = snapshot,
                        groupSongIds = playlistSongIds,
                        relevantIds = completedRawIds,
                        downloads = snapshot.downloads,
                        speeds = speeds,
                        requireCompleted = true,
                        downloadIdsByRawSongId = downloadIdsByRawSongId,
                    ).takeIf { it.isNotEmpty() }?.let {
                        add(DownloadSectionUiModel(DownloadMediaType.PLAYLIST, it))
                    }
                    buildAlbumEntries(
                        snapshot = snapshot,
                        groupSongIds = albumSongIds,
                        relevantIds = completedRawIds,
                        downloads = snapshot.downloads,
                        speeds = speeds,
                        requireCompleted = true,
                        downloadIdsByRawSongId = downloadIdsByRawSongId,
                    ).takeIf { it.isNotEmpty() }?.let {
                        add(DownloadSectionUiModel(DownloadMediaType.ALBUM, it))
                    }
                    buildSongEntries(
                        songIds = completedIds,
                        songsById = songsById,
                        downloads = snapshot.downloads,
                        speeds = speeds,
                    ).takeIf { it.isNotEmpty() }?.let {
                        add(DownloadSectionUiModel(DownloadMediaType.SONG, it))
                    }
                }

            val progressSections =
                buildList {
                    buildPlaylistEntries(
                        snapshot = snapshot,
                        groupSongIds = playlistSongIds,
                        relevantIds = activeRawIds,
                        downloads = snapshot.downloads,
                        speeds = speeds,
                        requireCompleted = false,
                        downloadIdsByRawSongId = downloadIdsByRawSongId,
                    ).takeIf { it.isNotEmpty() }?.let {
                        add(DownloadSectionUiModel(DownloadMediaType.PLAYLIST, it))
                    }
                    buildAlbumEntries(
                        snapshot = snapshot,
                        groupSongIds = albumSongIds,
                        relevantIds = activeRawIds,
                        downloads = snapshot.downloads,
                        speeds = speeds,
                        requireCompleted = false,
                        downloadIdsByRawSongId = downloadIdsByRawSongId,
                    ).takeIf { it.isNotEmpty() }?.let {
                        add(DownloadSectionUiModel(DownloadMediaType.ALBUM, it))
                    }
                    buildSongEntries(
                        songIds = activeIds,
                        songsById = songsById,
                        downloads = snapshot.downloads,
                        speeds = speeds,
                    ).takeIf { it.isNotEmpty() }?.let {
                        add(DownloadSectionUiModel(DownloadMediaType.SONG, it))
                    }
                }

            return DownloadLibraryUiModel(
                downloadedSections = downloadedSections,
                progressSections = progressSections,
            )
        }

        private fun buildPlaylistEntries(
            snapshot: DownloadRepositorySnapshot,
            groupSongIds: Map<String, List<String>>,
            relevantIds: Set<String>,
            downloads: Map<String, Download>,
            speeds: Map<String, Long>,
            requireCompleted: Boolean,
            downloadIdsByRawSongId: Map<String, List<String>>,
        ): List<DownloadEntryUiModel> =
            snapshot.playlists
                .mapNotNull { playlist ->
                    val rawSongIds = groupSongIds[playlist.id].orEmpty().distinct()
                    if (!rawSongIds.isCollectionMatch(relevantIds, downloads, requireCompleted)) return@mapNotNull null
                    // Expand raw ids to their per-source download ids so
                    // pause/resume/remove act on every offline copy.
                    val songIds = rawSongIds.flatMap { downloadIdsByRawSongId[it].orEmpty() }.ifEmpty { rawSongIds }
                    buildEntry(
                        id = "playlist:${playlist.id}",
                        title = playlist.title,
                        supportingText = null,
                        thumbnailUrl = playlist.thumbnails.firstOrNull(),
                        destinationRoute =
                            playlist.playlist.browseId?.let { "online_playlist/$it" }
                                ?: "local_playlist/${playlist.id}",
                        playbackMetadata = null,
                        durationSeconds = null,
                        songIds = songIds,
                        downloads = downloads,
                        speeds = speeds,
                    )
                }.sortedByDescending { entry -> entry.songIds.maxOfOrNull { downloads[it]?.updateTimeMs ?: 0L } ?: 0L }

        private fun buildAlbumEntries(
            snapshot: DownloadRepositorySnapshot,
            groupSongIds: Map<String, List<String>>,
            relevantIds: Set<String>,
            downloads: Map<String, Download>,
            speeds: Map<String, Long>,
            requireCompleted: Boolean,
            downloadIdsByRawSongId: Map<String, List<String>>,
        ): List<DownloadEntryUiModel> =
            snapshot.albums
                .mapNotNull { album ->
                    val rawSongIds = groupSongIds[album.id].orEmpty().distinct()
                    if (!rawSongIds.isCollectionMatch(relevantIds, downloads, requireCompleted)) return@mapNotNull null
                    val songIds = rawSongIds.flatMap { downloadIdsByRawSongId[it].orEmpty() }.ifEmpty { rawSongIds }
                    buildEntry(
                        id = "album:${album.id}",
                        title = album.title,
                        supportingText = album.artists.joinToString { it.name }.ifBlank { null },
                        thumbnailUrl = album.thumbnailUrl,
                        destinationRoute = "album/${album.id}",
                        playbackMetadata = null,
                        durationSeconds = null,
                        songIds = songIds,
                        downloads = downloads,
                        speeds = speeds,
                    )
                }.sortedByDescending { entry -> entry.songIds.maxOfOrNull { downloads[it]?.updateTimeMs ?: 0L } ?: 0L }

        private fun buildSongEntries(
            songIds: Set<String>,
            songsById: Map<String, Song>,
            downloads: Map<String, Download>,
            speeds: Map<String, Long>,
        ): List<DownloadEntryUiModel> =
            songIds
                .mapNotNull { songId ->
                    val download = downloads[songId] ?: return@mapNotNull null
                    // songId is a source-scoped download id — DB lookups use the
                    // raw song id, and the supporting text carries the source
                    // label ("Qobuz", "YouTube Music", ...) so the offline page
                    // shows one entry per downloaded source, as intended.
                    val rawSongId = DownloadSourceConfig.downloadIdToSongId(songId)
                    val song = songsById[rawSongId]
                    val sourceLabel = downloadSourceLabel(songId)
                    val artistText = song?.artists?.joinToString { it.name }?.ifBlank { null }
                    buildEntry(
                        id = "song:$songId",
                        title = song?.song?.title ?: download.requestTitle(),
                        supportingText =
                            if (artistText.isNullOrBlank()) sourceLabel else "$artistText · $sourceLabel",
                        thumbnailUrl = song?.song?.thumbnailUrl,
                        destinationRoute = null,
                        playbackMetadata =
                            song?.toMediaMetadata()
                                ?: MediaMetadata(
                                    id = rawSongId,
                                    title = download.requestTitle(),
                                    artists = emptyList(),
                                    duration = -1,
                                ),
                        durationSeconds = song?.song?.duration?.takeIf { it > 0 },
                        songIds = listOf(songId),
                        downloads = downloads,
                        speeds = speeds,
                    )
                }.sortedByDescending { downloads[it.songIds.single()]?.updateTimeMs ?: 0L }

        private fun downloadSourceLabel(downloadId: String): String =
            when (DownloadSourceConfig.downloadSourceForCacheKey(downloadId)) {
                DownloadSource.QOBUZ -> "Qobuz"
                DownloadSource.TIDAL -> "Tidal"
                DownloadSource.APPLE -> "Apple Music"
                DownloadSource.DEEZER -> "Deezer"
                DownloadSource.JIOSAAVN -> "JioSaavn"
                DownloadSource.QOBUZ_BACKUP -> "Qobuz Backup"
                else -> "YouTube Music"
            }

        private fun buildEntry(
            id: String,
            title: String,
            supportingText: String?,
            thumbnailUrl: String?,
            destinationRoute: String?,
            playbackMetadata: MediaMetadata?,
            durationSeconds: Int?,
            songIds: List<String>,
            downloads: Map<String, Download>,
            speeds: Map<String, Long>,
        ): DownloadEntryUiModel {
            val entries = songIds.mapNotNull(downloads::get)
            val activeEntries = entries.filter { it.isVisibleInProgress() }
            val progress =
                songIds
                    .map { songId ->
                        when (val download = downloads[songId]) {
                            null -> {
                                0f
                            }

                            else -> {
                                if (download.state == Download.STATE_COMPLETED) {
                                    1f
                                } else {
                                    download.percentDownloaded.takeIf { it >= 0f }?.div(100f) ?: 0f
                                }
                            }
                        }
                    }.average()
                    .toFloat()
                    .coerceIn(0f, 1f)
            return DownloadEntryUiModel(
                id = id,
                title = title,
                supportingText = supportingText,
                thumbnailUrl = thumbnailUrl,
                destinationRoute = destinationRoute,
                playbackMetadata = playbackMetadata,
                durationSeconds = durationSeconds,
                songIds = songIds,
                totalCount = songIds.size,
                progress = progress,
                percent = (progress * 100f).roundToInt().coerceIn(0, 100),
                speedBytesPerSecond = activeEntries.sumOf { speeds[it.request.id] ?: 0L },
                paused = activeEntries.isNotEmpty() && activeEntries.all { it.state.isPausedState() },
                failed = activeEntries.any { it.state == Download.STATE_FAILED },
            )
        }

        private fun calculateSpeeds(
            downloads: Map<String, Download>,
            now: Long,
        ): Map<String, Long> {
            byteSamples.keys.retainAll(downloads.keys)
            return downloads.mapValues { (songId, download) ->
                val previous = byteSamples[songId]
                val currentBytes = download.bytesDownloaded.coerceAtLeast(0L)
                val speed =
                    if (download.state == Download.STATE_DOWNLOADING && previous != null) {
                        val elapsedMs = (now - previous.timestampMs).coerceAtLeast(1L)
                        val downloadedBytes = (currentBytes - previous.bytes).coerceAtLeast(0L)
                        downloadedBytes * 1_000L / elapsedMs
                    } else {
                        0L
                    }
                byteSamples[songId] = ByteSample(currentBytes, now)
                speed
            }
        }

        private fun List<String>.isCollectionMatch(
            relevantIds: Set<String>,
            downloads: Map<String, Download>,
            requireCompleted: Boolean,
        ): Boolean {
            if (size < MIN_COLLECTION_SIZE || none(relevantIds::contains)) return false
            // rawSongIds here; resolve each to its per-source download ids for
            // the state check (a collection shows as downloaded only when every
            // member has a completed offline copy from SOME source).
            val membersDownloadIds = mapNotNull { rawId ->
                val ids = DownloadSourceConfig.songIdToDownloadIds(rawId).filter { it in downloads }
                ids.firstOrNull { downloads[it]?.state == Download.STATE_COMPLETED } ?: ids.firstOrNull()
            }
            if (membersDownloadIds.size < size) return false
            return if (requireCompleted) {
                membersDownloadIds.all { downloads[it]?.state == Download.STATE_COMPLETED }
            } else {
                membersDownloadIds.all { downloads[it]?.state?.isTrackedState() == true }
            }
        }

        private fun Download.requestTitle(): String = request.data.toString(Charsets.UTF_8).ifBlank { request.id }

        private fun Download.isVisibleInProgress(): Boolean = state.isProgressState()

        private fun Int.isTrackedState(): Boolean = this == Download.STATE_COMPLETED || isProgressState()

        private fun Int.isProgressState(): Boolean =
            this == Download.STATE_QUEUED ||
                this == Download.STATE_DOWNLOADING ||
                this == Download.STATE_RESTARTING ||
                this == Download.STATE_STOPPED ||
                this == Download.STATE_FAILED

        private fun Int.isPausedState(): Boolean = this == Download.STATE_STOPPED || this == Download.STATE_FAILED

        private data class ByteSample(
            val bytes: Long,
            val timestampMs: Long,
        )

        private companion object {
            const val MIN_COLLECTION_SIZE = 2
        }
    }
