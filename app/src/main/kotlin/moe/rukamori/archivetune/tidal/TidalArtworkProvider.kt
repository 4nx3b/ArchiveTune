/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.tidal

import moe.rukamori.archivetune.playback.artwork.ArtworkRequest
import moe.rukamori.archivetune.playback.artwork.TidalArtworkFetcher
import moe.rukamori.archivetune.playback.artwork.TidalArtworkMatch

object TidalArtworkProvider {

    fun fetcher(): TidalArtworkFetcher =
        TidalArtworkFetcher { request: ArtworkRequest ->
            val query =
                TidalAudioProvider.Query(
                    mediaId = request.mediaId,
                    title = request.title,
                    artists = request.artists,
                    album = request.album,
                    isrc = request.isrc,
                    durationMs = request.durationMs,
                )
            TidalAudioProvider.resolveArtwork(query)?.let { result ->
                TidalArtworkMatch(
                    trackId = result.trackId,
                    releaseId = result.releaseId,
                    artworkUrl = buildTidalArtworkUrl(result.coverId, ARTWORK_RESOLVER_SIZE),
                    matchMethod = if (result.exactIsrc) "ISRC" else "SEARCH",
                    confidence = result.confidence,
                )
            }
        }

    fun buildTidalArtworkUrl(
        coverId: String,
        size: Int,
    ): String = "https://resources.tidal.com/images/${coverId.replace('-', '/')}/${size}x$size.jpg"

    private const val ARTWORK_RESOLVER_SIZE = 1080
}
