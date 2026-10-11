/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Runs next to every song download: fetches the lyrics through the user's
 * lyrics-provider priority and persists them into the lyrics database, and
 * resolves the canvas through the artwork-provider priority so
 * CanvasArtworkPlaybackCache downloads the video files for offline playback.
 * Exported files additionally get the stored lyrics embedded as a LYRICS tag
 * and the canvas url as a custom field (AudioTagger).
 */

package moe.rukamori.archivetune.downloads

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.lyrics.LyricsHelper
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.ui.player.resolveCanvasArtworkForPlayback
import moe.rukamori.archivetune.utils.isLowDataModeActive
import timber.log.Timber
import java.util.Locale

object DownloadExtrasCoordinator {
    private const val TAG = "DownloadExtras"

    suspend fun fetchExtrasForSong(
        context: Context,
        database: MusicDatabase,
        lyricsHelper: LyricsHelper,
        mediaId: String,
    ) {
        if (mediaId.isBlank()) return
        withContext(Dispatchers.IO) {
            val song = runCatching { database.getSongByIdBlocking(mediaId) }.getOrNull()
            val metadata = song?.toMediaMetadata()
            if (metadata == null || metadata.isPodcast) {
                Timber.tag(TAG).d("Skipping download extras for %s (no metadata)", mediaId)
                return@withContext
            }

            val lowData = runCatching { context.isLowDataModeActive() }.getOrDefault(false)

            if (!lowData) {
                fetchLyrics(database, lyricsHelper, mediaId, metadata)
                fetchCanvas(context, mediaId, metadata)
            }
        }
    }

    private suspend fun fetchLyrics(
        database: MusicDatabase,
        lyricsHelper: LyricsHelper,
        mediaId: String,
        metadata: moe.rukamori.archivetune.models.MediaMetadata,
    ) {
        runCatching {
            val stored = database.lyrics(mediaId).first()
            if (stored != null && stored.lyrics != LyricsEntity.LYRICS_NOT_FOUND) return
            val result = lyricsHelper.getLyricsWithProvider(metadata)
            if (result.lyrics.isNotBlank() && result.lyrics != LyricsEntity.LYRICS_NOT_FOUND) {
                database.query {
                    replaceLyricsIfAbsentOrNotFound(
                        id = mediaId,
                        lyrics = result.lyrics,
                        providerName = result.providerName,
                    )
                }
                Timber.tag(TAG).i("Lyrics stored for download %s via %s", mediaId, result.providerName)
            }
        }.onFailure { e ->
            Timber.tag(TAG).w(e, "Lyrics fetch failed for download %s", mediaId)
        }
    }

    private suspend fun fetchCanvas(
        context: Context,
        mediaId: String,
        metadata: moe.rukamori.archivetune.models.MediaMetadata,
    ) {
        runCatching {
            val country = Locale.getDefault().country
            val storefront = if (country.length == 2) country.lowercase(Locale.ROOT) else "us"
            val artwork =
                resolveCanvasArtworkForPlayback(
                    mediaId = mediaId,
                    songTitleRaw = metadata.title,
                    artistNameRaw = metadata.artists.firstOrNull()?.name.orEmpty(),
                    storefront = storefront,
                    requireVertical = false,
                    allowNetwork = true,
                    albumTitle = metadata.album?.title,
                    trySpotifyCanvas = true,
                )
            if (artwork != null) {
                Timber.tag(TAG).i("Canvas cached for download %s (%s)", mediaId, artwork.inferredProvider())
            }
        }.onFailure { e ->
            Timber.tag(TAG).w(e, "Canvas fetch failed for download %s", mediaId)
        }
    }
}
