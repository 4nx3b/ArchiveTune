/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 *
 * Upcoming-release radar for the Pre-save & Release Countdown feature:
 * resolves an artist by name against Deezer's public REST catalogue (no
 * account, no key) and reads their album list, keeping the entries whose
 * release date still lies in the future — the pre-release catalogue entries
 * Deezer carries for announced albums, singles and EPs.
 *
 * Everything is best-effort: a missing artist, a failing lookup or a
 * catalogue hole simply yields an empty list and the artist page renders
 * without a countdown section, exactly as before.
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

    /** Cache TTL: an announced release date rarely changes. */
    private const val CACHE_TTL_MILLIS = 6L * 60 * 60 * 1000

    /** Releases further out than this are not "about to release". */
    private const val MAX_HORIZON_MILLIS = 400L * 24 * 60 * 60 * 1000

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
                runCatching { fetchUpcoming(query) }.onFailure {
                    Log.d(TAG, "Upcoming-release lookup failed for \"$query\": ${it.message}")
                }.getOrDefault(emptyList())
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

    private fun fetchUpcoming(artistName: String): List<UpcomingRelease> {
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

    /** Deezer dates are YYYY-MM-DD; returns midnight UTC of that day. */
    private fun parseReleaseDate(raw: String): Long? {
        val parts = raw.split("-")
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
