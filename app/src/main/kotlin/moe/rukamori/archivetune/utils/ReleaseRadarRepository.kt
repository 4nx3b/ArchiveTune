/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 *
 * Upcoming-release radar for the Pre-save & Release Countdown feature.
 * MULTIPLE online catalogues are consulted and their answers merged:
 *  - Deezer's public REST catalogue (no account, no key) — pre-release
 *    entries for announced albums, singles and EPs;
 *  - the iTunes Search catalogue (no account, no key) — carries pre-release
 *    entries Deezer misses and survives Deezer's regional API gaps.
 * Duplicates (the same release listed by both) collapse onto the entry with
 * the better artwork, keyed by normalised title + date.
 *
 * Everything is best-effort: a missing artist, a failing lookup or a
 * catalogue hole simply yields whatever the others found, and an artist
 * unknown everywhere renders no countdown section, exactly as before.
 */

package moe.rukamori.archivetune.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One upcoming (not-yet-released) catalogue entry. */
data class UpcomingRelease(
    /** Deezer album id — used only as a stable identity for this radar. */
    val releaseId: String,
    val title: String,
    val artistName: String,
    /** Type label from the catalogue ("album"/"single"/"ep"). */
    val releaseType: String,
    /** Epoch milliseconds at midnight UTC of the announced release date. */
    val releaseAtMillis: Long,
    /** Preferred (largest) artwork URL. */
    val thumbnailUrl: String?,
)

object ReleaseRadarRepository {
    private const val TAG = "ReleaseRadar"
    private const val API_BASE = "https://api.deezer.com"
    private const val ITUNES_API = "https://itunes.apple.com"

    /** Cache TTL: an announced release date rarely changes. */
    private const val CACHE_TTL_MILLIS = 6L * 60 * 60 * 1000

    /** Releases further out than this are not "about to release". */
    private const val MAX_HORIZON_MILLIS = 400L * 24 * 60 * 60 * 1000

    /** MusicBrainz requires a meaningful User-Agent; both APIs get one. */
    private const val USER_AGENT = "ArchiveTune/16.0 (https://github.com/4nx3b/ArchiveTune)"

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

    private class CacheEntry(
        val releases: List<UpcomingRelease>,
        val storedAt: Long,
    )

    private val cache = HashMap<String, CacheEntry>()
    private val cacheLock = Mutex()

    /**
     * Upcoming releases for the artist named [artistName], newest-release
     * first, or an empty list when nothing upcoming is known. Results are
     * cached per (normalised) artist name for [CACHE_TTL_MILLIS].
     */
    suspend fun upcomingReleasesForArtist(artistName: String): List<UpcomingRelease> {
        val query = artistName.trim()
        if (query.isEmpty()) return emptyList()

        cacheLock.withLock {
            cache[query.lowercase()]?.let { entry ->
                if (System.currentTimeMillis() - entry.storedAt < CACHE_TTL_MILLIS) {
                    return entry.releases
                }
            }
        }

        val found =
            withContext(Dispatchers.IO) {
                val deezer =
                    runCatching { fetchUpcomingDeezer(query) }.onFailure {
                        Log.d(TAG, "Deezer upcoming-release lookup failed for \"$query\": ${it.message}")
                    }.getOrDefault(emptyList())
                val itunes =
                    runCatching { fetchUpcomingItunes(query) }.onFailure {
                        Log.d(TAG, "iTunes upcoming-release lookup failed for \"$query\": ${it.message}")
                    }.getOrDefault(emptyList())
                mergeReleases(deezer + itunes)
            }

        cacheLock.withLock {
            cache[query.lowercase()] = CacheEntry(found, System.currentTimeMillis())
            if (cache.size > 64) {
                // Trim the oldest half when the cache grows past its budget.
                val keep =
                    cache.entries
                        .sortedByDescending { it.value.storedAt }
                        .take(32)
                        .associate { it.key to it.value }
                cache.clear()
                cache.putAll(keep)
            }
        }
        return found
    }

    private fun fetchUpcomingDeezer(artistName: String): List<UpcomingRelease> {
        val artistId = searchArtistId(artistName) ?: return emptyList()

        val albumsRequest =
            Request
                .Builder()
                .url("$API_BASE/artist/$artistId/albums?limit=100")
                .get()
                .build()
        client.newCall(albumsRequest).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val items = JSONObject(body).optJSONArray("data") ?: return emptyList()

            val now = System.currentTimeMillis()
            val upcoming = ArrayList<UpcomingRelease>()
            for (index in 0 until items.length()) {
                val album = items.optJSONObject(index) ?: continue
                val releaseDate = album.optString("release_date", "")
                val releaseAt = parseReleaseDate(releaseDate) ?: continue
                if (releaseAt <= now || releaseAt - now > MAX_HORIZON_MILLIS) continue

                upcoming +=
                    UpcomingRelease(
                        releaseId = album.optLong("id").toString(),
                        title = album.optString("title", ""),
                        artistName = artistName,
                        releaseType = album.optString("record_type", "album"),
                        releaseAtMillis = releaseAt,
                        thumbnailUrl = album.optString("cover_xl", "").ifBlank { album.optString("cover_big", "") },
                    )
            }
            return upcoming.sortedBy { it.releaseAtMillis }
        }
    }

    /**
     * The iTunes Search catalogue: one call returns the artist's releases with
     * `releaseDate` fields — entries whose date lies in the future are the
     * pre-release announcements. Artwork comes as 100x100; upscaled to 600x600
     * by the standard URL substitution.
     */
    private fun fetchUpcomingItunes(artistName: String): List<UpcomingRelease> {
        val request =
            Request
                .Builder()
                .url("$ITUNES_API/search?term=${urlEncode(artistName)}&entity=album&attribute=artistTerm&limit=100")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val items = JSONObject(body).optJSONArray("results") ?: return emptyList()

            val now = System.currentTimeMillis()
            val upcoming = ArrayList<UpcomingRelease>()
            for (index in 0 until items.length()) {
                val album = items.optJSONObject(index) ?: continue
                val wrapperType = album.optString("wrapperType", "")
                if (wrapperType.isNotBlank() && wrapperType != "collection") continue
                val releaseAt = parseReleaseDate(album.optString("releaseDate", "")) ?: continue
                if (releaseAt <= now || releaseAt - now > MAX_HORIZON_MILLIS) continue

                val title = album.optString("collectionName", "").ifBlank { continue }
                upcoming +=
                    UpcomingRelease(
                        releaseId = "itunes:${album.optLong("collectionId", 0L)}",
                        title = title,
                        artistName = album.optString("artistName", artistName).ifBlank { artistName },
                        releaseType =
                            when (album.optString("collectionType", "")) {
                                "Single" -> "single"
                                "EP" -> "ep"
                                else -> "album"
                            },
                        releaseAtMillis = releaseAt,
                        thumbnailUrl =
                            album
                                .optString("artworkUrl100", "")
                                .ifBlank { null }
                                ?.replace("/100x100bb.jpg", "/600x600bb.jpg"),
                    )
            }
            return upcoming
        }
    }

    /**
     * Collapses cross-catalogue duplicates (same normalised title and release
     * date) onto the entry with the better artwork, then sorts by date.
     */
    private fun mergeReleases(all: List<UpcomingRelease>): List<UpcomingRelease> {
        val byKey = LinkedHashMap<String, UpcomingRelease>()
        for (release in all) {
            val key = "${normalizeTitleKey(release.title)}@${release.releaseAtMillis}"
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = release
            } else {
                // Keep the entry with artwork; otherwise keep the first.
                if (existing.thumbnailUrl.isNullOrBlank() && !release.thumbnailUrl.isNullOrBlank()) {
                    byKey[key] = release
                }
            }
        }
        return byKey.values.sortedBy { it.releaseAtMillis }
    }

    private fun normalizeTitleKey(title: String): String =
        title
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .replace(" - single", "")
            .replace(" - ep", "")
            .trim()

    private fun searchArtistId(artistName: String): String? {
        val request =
            Request
                .Builder()
                .url("$API_BASE/search/artist?q=${urlEncode(artistName)}&limit=1")
                .get()
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val data = JSONObject(body).optJSONArray("data") ?: return null
            val artist = data.optJSONObject(0) ?: return null
            val id = artist.optLong("id", -1L)
            if (id <= 0L) return null
            // Guard against a wildcard match resolving to a different artist:
            // the top result's name must contain the queried one (either way
            // around) for the radar to adopt it.
            val matchedName = artist.optString("name", "").lowercase()
            val queried = artistName.lowercase()
            val related = matchedName.contains(queried) || queried.contains(matchedName)
            return if (related) id.toString() else null
        }
    }

    /** Deezer dates are YYYY-MM-DD; iTunes dates are ISO-8601. Returns
     *  midnight UTC of that day. */
    private fun parseReleaseDate(raw: String): Long? {
        if (raw.isBlank()) return null
        val datePart =
            if (raw.length > 10 && raw.getOrNull(10) == 'T') {
                raw.substring(0, 10)
            } else {
                raw
            }
        val parts = datePart.split("-")
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (year < 2020 || month !in 1..12 || day !in 1..31) return null
        return java.util.Calendar
            .getInstance(java.util.TimeZone.getTimeZone("UTC"))
            .apply {
                clear()
                set(year, month - 1, day, 0, 0, 0)
            }.timeInMillis
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}
