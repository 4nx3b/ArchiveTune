/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.viewmodels

import android.content.Context
import moe.rukamori.archivetune.canvas.models.CanvasArtwork
import moe.rukamori.archivetune.constants.AlbumCanvasEnabledKey
import moe.rukamori.archivetune.ui.player.resolveCanvasArtworkForPlayback
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.isLowDataModeActive
import java.util.Locale

/**
 * Page-level canvas for playlist headers — the playlist twin of
 * AlbumViewModel's album-page canvas. Keyed on the first song of the
 * list (Apple Music's motion-artwork endpoint deliberately rejects
 * playlist ids, so playlists resolve through song identity instead)
 * and gated by the shared "Enable canvas in albums and playlists page"
 * toggle plus low-data mode.
 */
internal suspend fun fetchPlaylistCanvasArtwork(
    context: Context,
    firstSongId: String?,
    firstSongTitle: String?,
    firstSongArtist: String?,
    firstSongAlbumTitle: String? = null,
    spotifyTrackId: String? = null,
): CanvasArtwork? {
    if (firstSongId.isNullOrBlank() || firstSongTitle.isNullOrBlank()) return null

    if (!context.dataStore.get(AlbumCanvasEnabledKey, true)) return null

    if (context.isLowDataModeActive()) return null

    val country = Locale.getDefault().country
    val storefront = if (country.length == 2) country.lowercase(Locale.ROOT) else "us"

    return resolveCanvasArtworkForPlayback(
        mediaId = firstSongId,
        songTitleRaw = firstSongTitle,
        artistNameRaw = firstSongArtist.orEmpty(),
        storefront = storefront,
        requireVertical = false,
        allowNetwork = true,
        albumTitle = firstSongAlbumTitle,
        trySpotifyCanvas = true,
        spotifyTrackId = spotifyTrackId,
    )
}
