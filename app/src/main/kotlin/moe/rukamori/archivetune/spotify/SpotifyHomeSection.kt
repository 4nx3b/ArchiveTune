/*
 * YumaPlayer (2026) | Modified work by MuwMx
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.spotify

import androidx.compose.runtime.Immutable
import moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedItem
import moe.rukamori.archivetune.spotify.models.SpotifyTrack

@Immutable
sealed interface SpotifyHomeSection {
    val title: String

    @Immutable
    data class Tracks(
        override val title: String,
        val tracks: List<SpotifyTrack>,
    ) : SpotifyHomeSection

    @Immutable
    data class Cards(
        override val title: String,
        val items: List<SpotifyHomeFeedItem>,
    ) : SpotifyHomeSection
}
