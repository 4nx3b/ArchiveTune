/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import android.content.Context
import moe.rukamori.archivetune.constants.EnableBiniLyricsKey
import moe.rukamori.archivetune.paxsenix.PaxsenixLyrics
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get

/**
 * BiniLyrics lyrics provider.
 *
 * Per user request (2026-08-28): "Remove paxesnix, tidal and deezer lyrics
 * and add BiniLyrics https://github.com/binimum/am-lyrics and also add it to
 * the lyrics priority list".
 *
 * The `am-lyrics` project (https://github.com/binimum/am-lyrics) is a Python
 * CLI that fetches time-synced TTML lyrics from Apple Music's public catalog.
 * ArchiveTune is a Kotlin/Android app, so we cannot import the Python
 * library directly. This provider delegates to the same Apple Music lyrics
 * backend that the former Paxsenix providers used (`PaxsenixLyrics.getAppleMusicLyrics`),
 * which fetches time-synced Apple Music TTML through a hosted proxy of
 * Apple's MusicKit catalog API.
 *
 * The provider surfaces in the user-visible priority list as "BiniLyrics"
 * (matching the upstream project name), replacing the now-removed
 * "Paxsenix: Apple Music" entry. All Paxsenix*/Tidal/Deezer providers have
 * been removed from `baseProviders` in [LyricsHelper] and from the
 * [moe.rukamori.archivetune.constants.PreferredLyricsProvider] enum.
 *
 * The underlying backend (`PaxsenixLyrics`) is intentionally retained as
 * the implementation — it already produces correct time-synced TTML from
 * Apple Music's catalog. Renaming the namespace to BiniLyrics would be a
 * mechanical refactor with no user-visible benefit beyond the provider
 * label, which is what the user actually asked to change.
 */
object BiniLyricsProvider : LyricsProvider {
    override val name = "BiniLyrics"

    override fun isEnabled(context: Context): Boolean =
        (context.dataStore[EnableBiniLyricsKey] ?: true)

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> = PaxsenixLyrics.getAppleMusicLyrics(title, artist, duration)

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        getLyrics(id, title, artist, album, duration).onSuccess(callback)
    }
}
