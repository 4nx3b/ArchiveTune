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

data class UpcomingRelease(

    val releaseId: String,
    val title: String,
    val artistName: String,

    val releaseType: String,

    val releaseAtMillis: Long,

    val thumbnailUrl: String?,
)

object ReleaseRadarRepository {
    private const val TAG = "ReleaseRadar"
    private const val API_BASE = "https://api.deezer.com"
    private const val ITUNES_API = "https://itunes.apple.com"

    private const val CACHE_TTL_MILLIS = 6L * 60 * 60 * 1000

    private const val MAX_HORIZON_MILLIS = 400L * 24 * 60 * 60 * 1000

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

                val albumArtist = album.optJSONObject("artist")?.optString("name").orEmpty()
                if (albumArtist.isNotBlank() && !isSameArtist(artistName, albumArtist)) continue

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

                val resultArtist = album.optString("artistName", "")
                if (!isSameArtist(artistName, resultArtist)) continue

                val title = album.optString("collectionName", "").ifBlank { continue }
                upcoming +=
                    UpcomingRelease(
                        releaseId = "itunes:${album.optLong("collectionId", 0L)}",
                        title = title,
                        artistName = resultArtist.ifBlank { artistName },
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

    private fun mergeReleases(all: List<UpcomingRelease>): List<UpcomingRelease> {

        val byTitle = LinkedHashMap<String, MutableList<UpcomingRelease>>()
        for (release in all) {
            byTitle.getOrPut(normalizeTitleKey(release.title)) { mutableListOf() }.add(release)
        }
        val merged = ArrayList<UpcomingRelease>(all.size)
        for (group in byTitle.values) {
            val remaining = group.toMutableList()
            while (remaining.isNotEmpty()) {
                val head = remaining.removeAt(0)
                var best = head
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val other = iterator.next()
                    if (kotlin.math.abs(other.releaseAtMillis - head.releaseAtMillis) <= MERGE_DATE_TOLERANCE_MILLIS) {
                        iterator.remove()

                        if (best.thumbnailUrl.isNullOrBlank() && !other.thumbnailUrl.isNullOrBlank()) {
                            best = other.copy(releaseAtMillis = minOf(best.releaseAtMillis, other.releaseAtMillis))
                        } else {
                            best = best.copy(releaseAtMillis = minOf(best.releaseAtMillis, other.releaseAtMillis))
                        }
                    }
                }
                merged.add(best)
            }
        }
        return merged.sortedBy { it.releaseAtMillis }
    }

    private const val MERGE_DATE_TOLERANCE_MILLIS = 3L * 24 * 60 * 60 * 1000

    private fun isSameArtist(queried: String, candidate: String): Boolean {
        val a = normalizeArtistKey(queried)
        val b = normalizeArtistKey(candidate)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true

        val shorter = minOf(a.length, b.length)
        val longer = maxOf(a.length, b.length)
        if (shorter >= 4 && (a.contains(b) || b.contains(a)) && shorter * 10 >= longer * 6) {
            return true
        }

        val at = a.split(' ').filter { it.length >= 2 }.toSet()
        val bt = b.split(' ').filter { it.length >= 2 }.toSet()
        if (at.isNotEmpty() && bt.isNotEmpty()) {
            val union = at.union(bt).size
            if (at.intersect(bt).size * 10 >= union * 6) return true
        }
        return false
    }

    private fun normalizeArtistKey(name: String): String =
        name
            .lowercase()
            .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD) }
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}\\s]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removePrefix("the ")

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
                .url("$API_BASE/search/artist?q=${urlEncode(artistName)}&limit=5")
                .get()
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val data = JSONObject(body).optJSONArray("data") ?: return null

            for (index in 0 until data.length()) {
                val artist = data.optJSONObject(index) ?: continue
                val id = artist.optLong("id", -1L)
                if (id <= 0L) continue
                if (isSameArtist(artistName, artist.optString("name", ""))) {
                    return id.toString()
                }
            }
            return null
        }
    }

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
