/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.utils

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import moe.rukamori.archivetune.constants.DownloadSourceConfig
import moe.rukamori.archivetune.playback.DownloadUtil
import moe.rukamori.archivetune.playback.ExoDownloadService

@Immutable
sealed interface HeaderDownloadState {
    data object None : HeaderDownloadState

    data object Completed : HeaderDownloadState

    @Immutable
    data class Partial(
        val progress: Float,
        val paused: Boolean,
    ) : HeaderDownloadState
}

@Immutable
data class HeaderDownloadItem(
    val id: String,
    val title: String,
)

/**
 * The download entry for a song id as tracked by the download index. Download
 * requests are keyed by their source-scoped ids ("ytm:<id>", "qobuz:<id>", …,
 * or the legacy plain id), so a plain song id never matches a map keyed by
 * request ids — every helper below resolves through
 * [DownloadSourceConfig.songIdToDownloadIds] instead.
 */
private fun Map<String, Download>.forSongId(songId: String): Download? =
    DownloadSourceConfig.songIdToDownloadIds(songId).firstNotNullOfOrNull { this[it] }

private fun Map<String, Download>.isDownloadingSong(songId: String): Boolean =
    DownloadSourceConfig.songIdToDownloadIds(songId).any { id ->
        when (this[id]?.state) {
            Download.STATE_QUEUED,
            Download.STATE_DOWNLOADING,
            Download.STATE_RESTARTING,
            -> true

            else -> false
        }
    }

fun headerDownloadState(
    songIds: List<String>,
    downloads: Map<String, Download>,
): HeaderDownloadState {
    if (songIds.isEmpty()) return HeaderDownloadState.None

    var completedCount = 0
    var progressTotal = 0f
    var hasAnyDownload = false
    var hasRunningDownload = false
    var hasPausedDownload = false

    val distinctSongIds = songIds.distinct()

    distinctSongIds.forEach { songId ->
        val download = downloads.forSongId(songId)
        when (download?.state) {
            Download.STATE_COMPLETED -> {
                completedCount++
                progressTotal += 1f
                hasAnyDownload = true
            }

            Download.STATE_QUEUED,
            Download.STATE_DOWNLOADING,
            Download.STATE_RESTARTING,
            -> {
                val progress =
                    download.percentDownloaded
                        .takeIf { it >= 0f }
                        ?.div(100f)
                        ?: 0f
                progressTotal += progress.coerceIn(0f, 1f)
                hasAnyDownload = true
                hasRunningDownload = true
            }

            Download.STATE_STOPPED -> {
                if (download.stopReason != DOWNLOAD_STOP_REASON_NONE) {
                    val progress =
                        download.percentDownloaded
                            .takeIf { it >= 0f }
                            ?.div(100f)
                            ?: 0f
                    progressTotal += progress.coerceIn(0f, 1f)
                    hasAnyDownload = true
                    hasPausedDownload =
                        hasPausedDownload || download.stopReason == COLLECTION_PAUSE_STOP_REASON
                }
            }
        }
    }

    val distinctCount = distinctSongIds.size
    return when {
        completedCount == distinctCount -> {
            HeaderDownloadState.Completed
        }

        hasRunningDownload || hasPausedDownload -> {
            HeaderDownloadState.Partial(
                progress = (progressTotal / distinctCount).coerceIn(0f, 1f),
                paused = hasPausedDownload && !hasRunningDownload,
            )
        }

        else -> {
            HeaderDownloadState.None
        }
    }
}

fun sendAddMissingDownloads(
    context: Context,
    songs: List<HeaderDownloadItem>,
    downloads: Map<String, Download>,
    downloadUtil: DownloadUtil,
) {
    songs
        .distinctBy { it.id }
        .filter { item -> !downloads.isDownloadingSong(item.id) && downloads.forSongId(item.id)?.state.shouldRequestDownload() }
        .forEach { item ->
            // Source-scoped request id — the SAME id the single-song download
            // menus queue. The old plain-id request created a second, invisible
            // (and uncancellable) download entry beside the source-scoped one.
            val downloadId = downloadUtil.currentSourceDownloadTarget(item.id).key
            val downloadRequest =
                DownloadRequest
                    .Builder(downloadId, item.id.toUri())
                    .setCustomCacheKey(downloadId)
                    .setData(item.title.toByteArray())
                    .build()
            DownloadService.sendAddDownload(
                context,
                ExoDownloadService::class.java,
                downloadRequest,
                false,
            )
        }
}

fun sendRemoveDownloads(
    context: Context,
    songIds: List<String>,
) {
    songIds.distinct().forEach { songId ->
        // Remove every source-scoped variant (plus the legacy plain entry):
        // the header's visible state counts any variant, so removal must clear
        // them all — sendRemoveDownload is a no-op for ids that do not exist.
        DownloadSourceConfig.songIdToDownloadIds(songId).forEach { downloadId ->
            DownloadService.sendRemoveDownload(
                context,
                ExoDownloadService::class.java,
                downloadId,
                false,
            )
        }
    }
}

fun sendPauseRunningDownloads(
    context: Context,
    songIds: List<String>,
    downloads: Map<String, Download>,
) {
    songIds
        .distinct()
        .forEach { songId ->
            DownloadSourceConfig.songIdToDownloadIds(songId).forEach { downloadId ->
                when (downloads[downloadId]?.state) {
                    Download.STATE_QUEUED,
                    Download.STATE_DOWNLOADING,
                    Download.STATE_RESTARTING,
                    -> DownloadService.sendSetStopReason(
                        context,
                        ExoDownloadService::class.java,
                        downloadId,
                        COLLECTION_PAUSE_STOP_REASON,
                        false,
                    )

                    else -> Unit
                }
            }
        }
}

fun sendResumePausedDownloads(
    context: Context,
    songIds: List<String>,
    downloads: Map<String, Download>,
) {
    songIds
        .distinct()
        .forEach { songId ->
            DownloadSourceConfig.songIdToDownloadIds(songId).forEach { downloadId ->
                val download = downloads[downloadId]
                if (download?.state == Download.STATE_STOPPED &&
                    download.stopReason == COLLECTION_PAUSE_STOP_REASON
                ) {
                    DownloadService.sendSetStopReason(
                        context,
                        ExoDownloadService::class.java,
                        downloadId,
                        DOWNLOAD_STOP_REASON_NONE,
                        false,
                    )
                }
            }
        }
}

private fun Int?.shouldRequestDownload(): Boolean =
    when (this) {
        Download.STATE_COMPLETED,
        Download.STATE_QUEUED,
        Download.STATE_DOWNLOADING,
        Download.STATE_RESTARTING,
        -> false

        else -> true
    }

private const val DOWNLOAD_STOP_REASON_NONE = 0
private const val COLLECTION_PAUSE_STOP_REASON = 1
