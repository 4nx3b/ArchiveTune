/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.canvas.AppleMusicProvider
import moe.rukamori.archivetune.canvas.SpotifyCanvasProvider
import moe.rukamori.archivetune.canvas.models.CanvasArtwork
import moe.rukamori.archivetune.canvas.models.looselyMatchesSongIdentity
import moe.rukamori.archivetune.canvas.models.matchesSongIdentity
import moe.rukamori.archivetune.constants.PreferredArtworkProvider
import moe.rukamori.archivetune.telegram.isTelegramMediaId
import moe.rukamori.archivetune.utils.isLocalMediaId
import timber.log.Timber

/**
 * Live mirror of the user's canvas ranking inside the artwork provider order
 * (Settings -> Player -> Artwork priority). The static artwork resolver skips
 * the two canvas entries entirely, so without this mirror the VIDEO canvas
 * pipeline resolved in a hard-coded order (Spotify first) no matter how the
 * user ranked "ArchiveTune Canvas" vs "Spotify Canvas" — the exact
 * "Spotify canvas plays first even though ArchiveTune is top priority"
 * report. MusicService pushes the deserialized order here whenever the
 * preference changes.
 */
internal object CanvasProviderPriority {
    @Volatile
    internal var preferArchiveTuneCanvasFirst: Boolean = false
        private set

    /** media ids whose lower-priority cached canvas already failed a priority upgrade — don't hammer the network on every play. */
    private val failedUpgradeMediaIds: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun updateFrom(order: List<PreferredArtworkProvider>) {
        val archiveTuneRank = order.indexOf(PreferredArtworkProvider.ARCHIVETUNE_CANVAS)
        val spotifyRank = order.indexOf(PreferredArtworkProvider.SPOTIFY_CANVAS)
        val preferArchiveTuneFirst =
            archiveTuneRank >= 0 && (spotifyRank < 0 || archiveTuneRank < spotifyRank)
        if (preferArchiveTuneCanvasFirst != preferArchiveTuneFirst) {
            preferArchiveTuneCanvasFirst = preferArchiveTuneFirst
            failedUpgradeMediaIds.clear()
        }
    }

    fun markUpgradeAttemptFailed(mediaId: String) {
        failedUpgradeMediaIds.add(mediaId)
    }

    fun hasFailedUpgradeAttempt(mediaId: String): Boolean = mediaId in failedUpgradeMediaIds

    /** 0 = top-priority canvas provider, 1 = the other; unknown/null ranks last. */
    internal fun providerRank(provider: String?): Int =
        when {
            provider == CanvasArtwork.PROVIDER_APPLE_MUSIC && preferArchiveTuneCanvasFirst -> 0
            provider == CanvasArtwork.PROVIDER_SPOTIFY && !preferArchiveTuneCanvasFirst -> 0
            provider == CanvasArtwork.PROVIDER_APPLE_MUSIC || provider == CanvasArtwork.PROVIDER_SPOTIFY -> 1
            else -> 2
        }
}

internal suspend fun resolveCanvasArtworkForPlayback(
    mediaId: String,
    songTitleRaw: String,
    artistNameRaw: String,
    storefront: String,
    requireVertical: Boolean,
    allowNetwork: Boolean,
    albumTitle: String? = null,
    trySpotifyCanvas: Boolean = false,

    spotifyTrackId: String? = null,
): CanvasArtwork? {

    val strictIdentity = !(mediaId.isTelegramMediaId() || mediaId.isLocalMediaId())

    val preferArchiveTuneCanvasFirst = CanvasProviderPriority.preferArchiveTuneCanvasFirst

    val cachedArtwork =
        withContext(Dispatchers.IO) {
            CanvasArtworkPlaybackCache.getCachedOnlyFast(mediaId)
                ?: CanvasArtworkPlaybackCache.get(
                    mediaId = mediaId,
                    preferCachedOnly = true,
                )
        }
    if (cachedArtwork != null) {
        val isValid =
            cachedArtwork.hasRequiredCanvasVariant(requireVertical) &&
                cachedArtwork.matchesIdentity(songTitleRaw, artistNameRaw, strictIdentity)
        if (isValid) {
            // Cache hit — but if the entry came from the lower-priority canvas
            // provider, try to upgrade it once. A failed upgrade is remembered
            // per media id so playback never re-asks the network on every play.
            if (
                allowNetwork &&
                preferArchiveTuneCanvasFirst &&
                cachedArtwork.inferredProvider() == CanvasArtwork.PROVIDER_SPOTIFY &&
                !CanvasProviderPriority.hasFailedUpgradeAttempt(mediaId)
            ) {
                val upgraded =
                    fetchCanvasArtworkForPlayback(
                        songTitleRaw = songTitleRaw,
                        artistNameRaw = artistNameRaw,
                        storefront = storefront,
                        requireVertical = requireVertical,
                        strictIdentity = strictIdentity,
                        albumTitle = albumTitle,
                    )
                if (upgraded != null) {
                    Timber.tag(CanvasArtworkLogTag).d("Upgrading cached Spotify canvas to ArchiveTune canvas for %s", mediaId)
                    return CanvasArtworkPlaybackCache.put(mediaId, upgraded)
                }
                CanvasProviderPriority.markUpgradeAttemptFailed(mediaId)
            }
            return cachedArtwork
        }
        withContext(Dispatchers.IO) {
            CanvasArtworkPlaybackCache.remove(mediaId)
        }
    }

    if (!allowNetwork || mediaId.isBlank()) {
        Timber.tag(CanvasArtworkLogTag).d("Skipping canvas network lookup for %s", mediaId)
        return null
    }

    return withContext(Dispatchers.IO) {
        // Resolution order follows the user's artwork-provider priority: when
        // ArchiveTune Canvas outranks Spotify Canvas, the Apple Music
        // (ArchiveTune) provider is queried first and Spotify becomes the
        // fallback — the inverse of the historical hard-coded order.
        if (preferArchiveTuneCanvasFirst) {
            val fetchedFirst =
                fetchCanvasArtworkForPlayback(
                    songTitleRaw = songTitleRaw,
                    artistNameRaw = artistNameRaw,
                    storefront = storefront,
                    requireVertical = requireVertical,
                    strictIdentity = strictIdentity,
                    albumTitle = albumTitle,
                )
            if (fetchedFirst != null) {
                Timber.tag(CanvasArtworkLogTag).d("ArchiveTune canvas resolved first for %s", mediaId)
                return@withContext CanvasArtworkPlaybackCache.put(mediaId, fetchedFirst)
            }

            if (trySpotifyCanvas && strictIdentity) {
                val spotifyFallback =
                    runCatching {
                        SpotifyCanvasProvider.getByVideoId(
                            videoId = mediaId,
                            songTitle = songTitleRaw,
                            artistName = artistNameRaw,
                            spotifyTrackUri = spotifyTrackId?.takeIf { it.isNotBlank() }?.let { "spotify:track:$it" },
                        )
                    }.onFailure { throwable ->
                        Timber.tag(CanvasArtworkLogTag).w(throwable, "Spotify Canvas lookup failed for %s", mediaId)
                    }.getOrNull()
                if (spotifyFallback != null && spotifyFallback.hasRequiredCanvasVariant(requireVertical)) {
                    Timber.tag(CanvasArtworkLogTag).d("Spotify Canvas fallback resolved for %s", mediaId)
                    return@withContext CanvasArtworkPlaybackCache.put(mediaId, spotifyFallback)
                }
            }

            Timber.tag(CanvasArtworkLogTag).d("No playable canvas resolved for %s", mediaId)
            return@withContext null
        }

        if (trySpotifyCanvas && strictIdentity) {
            val spotifyCanvas =
                runCatching {
                    SpotifyCanvasProvider.getByVideoId(
                        videoId = mediaId,
                        songTitle = songTitleRaw,
                        artistName = artistNameRaw,

                        spotifyTrackUri = spotifyTrackId?.takeIf { it.isNotBlank() }?.let { "spotify:track:$it" },
                    )
                }.onFailure { throwable ->
                    Timber.tag(CanvasArtworkLogTag).w(throwable, "Spotify Canvas lookup failed for %s", mediaId)
                }.getOrNull()
            if (spotifyCanvas != null && spotifyCanvas.hasRequiredCanvasVariant(requireVertical)) {
                Timber.tag(CanvasArtworkLogTag).d("Spotify Canvas resolved for %s", mediaId)
                return@withContext CanvasArtworkPlaybackCache.put(mediaId, spotifyCanvas)
            }
        }

        val fetched =
            fetchCanvasArtworkForPlayback(
                songTitleRaw = songTitleRaw,
                artistNameRaw = artistNameRaw,
                storefront = storefront,
                requireVertical = requireVertical,
                strictIdentity = strictIdentity,
                albumTitle = albumTitle,
            )

        if (fetched == null) {
            Timber.tag(CanvasArtworkLogTag).d("No playable canvas resolved for %s", mediaId)
            return@withContext null
        }

        CanvasArtworkPlaybackCache.put(mediaId, fetched)
    }
}

internal suspend fun fetchCanvasArtworkForPlayback(
    songTitleRaw: String,
    artistNameRaw: String,
    storefront: String,
    requireVertical: Boolean,
    forceRefresh: Boolean = false,
    strictIdentity: Boolean = true,
    albumTitle: String? = null,
): CanvasArtwork? {
    val songTitle = normalizeCanvasSongTitle(songTitleRaw)
    val artistName = normalizeCanvasArtistName(artistNameRaw)
    val candidates =
        linkedSetOf(
            songTitle to artistName,
            songTitleRaw to artistName,
            songTitle to artistNameRaw,
            songTitleRaw to artistNameRaw,
        ).filter { (song, artist) ->
            song.isNotBlank() && artist.isNotBlank()
        }

    return candidates.firstNotNullOfOrNull { (song, artist) ->
        AppleMusicProvider
            .getBySongArtist(
                song = song,
                artist = artist,
                storefront = storefront,
                forceRefresh = forceRefresh,
                album = albumTitle,
            )?.takeIf { artwork ->
                artwork.matchesIdentity(songTitleRaw, artistNameRaw, strictIdentity) &&
                    artwork.hasRequiredCanvasVariant(requireVertical)
            }
    }
}

/**
 * Cheap availability probe backing the song-overflow "Canvas" entry: true when
 * any integrated canvas provider (ArchiveTune/Apple Music, Spotify) can serve
 * this song, or a playback-cache entry already exists. Provider-side TTL
 * caches absorb repeated probes.
 */
internal suspend fun hasAnyCanvasSource(
    mediaId: String,
    songTitleRaw: String,
    artistNameRaw: String,
    storefront: String,
    albumTitle: String? = null,
    includeAppleMusic: Boolean = true,
    includeSpotify: Boolean = true,
): Boolean {
    if (mediaId.isBlank()) return false
    if (CanvasArtworkPlaybackCache.hasEntry(mediaId)) return true

    val strictIdentity = !(mediaId.isTelegramMediaId() || mediaId.isLocalMediaId())

    if (includeAppleMusic) {
        val appleMusic =
            fetchCanvasArtworkForPlayback(
                songTitleRaw = songTitleRaw,
                artistNameRaw = artistNameRaw,
                storefront = storefront,
                requireVertical = false,
                strictIdentity = strictIdentity,
                albumTitle = albumTitle,
            )
        if (appleMusic != null) return true
    }

    if (includeSpotify && strictIdentity) {
        val spotify =
            runCatching {
                SpotifyCanvasProvider.getByVideoId(
                    videoId = mediaId,
                    songTitle = songTitleRaw,
                    artistName = artistNameRaw,
                )
            }.getOrNull()
        if (spotify != null && !spotify.preferredAnimationUrl.isNullOrBlank()) return true
    }

    return false
}

internal suspend fun refetchCanvasArtworkForPlayback(
    mediaId: String,
    songTitleRaw: String,
    artistNameRaw: String,
    storefront: String,
    requireVertical: Boolean,
    albumTitle: String? = null,
): CanvasArtwork? {
    if (mediaId.isBlank()) return null

    return withContext(Dispatchers.IO) {
        val fetched =
            fetchCanvasArtworkForPlayback(
                songTitleRaw = songTitleRaw,
                artistNameRaw = artistNameRaw,
                storefront = storefront,
                requireVertical = requireVertical,
                forceRefresh = true,
                strictIdentity = !(mediaId.isTelegramMediaId() || mediaId.isLocalMediaId()),
                albumTitle = albumTitle,
            ) ?: return@withContext null

        CanvasArtworkPlaybackCache.replace(mediaId, fetched)
    }
}

private fun CanvasArtwork.matchesIdentity(
    songTitleRaw: String,
    artistNameRaw: String,
    strict: Boolean,
): Boolean =
    if (strict) {
        matchesSongIdentity(songTitleRaw, artistNameRaw)
    } else {

        looselyMatchesSongIdentity(songTitleRaw, artistNameRaw) || !albumName.isNullOrBlank()
    }

private fun CanvasArtwork.hasRequiredCanvasVariant(requireVertical: Boolean): Boolean =
    if (requireVertical) {
        !preferredVerticalAnimationUrl.isNullOrBlank()
    } else {
        !preferredAnimationUrl.isNullOrBlank()
    }

private const val CanvasArtworkLogTag = "CanvasArtwork"


private fun normalizeCanvasSongTitle(raw: String): String {
    val stripped =
        raw

            .replace(Regex("^\\s*\\d{1,3}\\s*[.\\-]\\s*"), "")
            .replace(Regex("\\s*\\[[^]]*]"), "")
            .replace(
                Regex(
                    "\\s*\\((?:feat\\.?|ft\\.?|featuring|with)\\b[^)]*\\)",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            ).replace(
                Regex(
                    "\\s*\\((?:official\\s*)?(?:music\\s*)?(?:video|mv|lyrics?|audio|visualizer|live|remaster(?:ed)?|version|edit|mix|remix)[^)]*\\)",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            ).replace(
                Regex(
                    "\\s*-\\s*(?:official\\s*)?(?:music\\s*)?(?:video|mv|lyrics?|audio|visualizer|live|remaster(?:ed)?|version|edit|mix|remix)\\b.*$",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            ).replace(Regex("\\s+"), " ")
            .trim()

    return stripped
        .trim('-')
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun normalizeCanvasArtistName(raw: String): String {
    val first =
        raw
            .split(
                Regex(
                    "(?:\\s*,\\s*|\\s*&\\s*|\\s+x\\s+|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b)",
                    RegexOption.IGNORE_CASE,
                ),
                limit = 2,
            ).firstOrNull()
            .orEmpty()

    return first.replace(Regex("\\s+"), " ").trim()
}
