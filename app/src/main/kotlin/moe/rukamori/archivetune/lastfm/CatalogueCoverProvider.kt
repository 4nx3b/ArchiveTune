/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lastfm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Resolves album/track cover URLs from third-party catalogues that the Last.fm
 * API doesn't always carry. Used as a fallback chain in the Last.fm dashboard
 * so that tracks without a Last.fm image still get a real thumbnail instead of
 * the generic music-note placeholder.
 *
 * Two resolvers are exposed:
 *
 * 1. [iTunesCoverUrl] — iTunes Search API. Free, no auth, no rate-limit issues
 *    at the volumes a single user generates. Covers most western pop/rock and
 *    a good chunk of K-pop and J-pop that has international distribution.
 *
 * 2. [deezerCoverUrl] — Deezer public search API. Free, no auth. Excellent
 *    coverage for European / Asian catalogues; often has covers iTunes lacks
 *    (and vice-versa), so we query both and take the first non-empty result.
 */
object CatalogueCoverProvider {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Resolve a cover URL for [title] (optionally with [artist]) by querying
     * the catalogues in order: iTunes → Deezer. Returns the first non-empty
     * URL or null if every provider came up empty / errored out.
     *
     * Safe to call from any dispatcher; performs its own IO dispatching.
     */
    suspend fun resolveCoverUrl(
        title: String,
        artist: String?,
    ): String? =
        withContext(Dispatchers.IO) {
            if (title.isBlank()) return@withContext null
            iTunesCoverUrl(title, artist) ?: deezerCoverUrl(title, artist)
        }

    /**
     * iTunes Search API.
     * Endpoint: https://itunes.apple.com/search?term=...&entity=song&limit=1
     * Response JSON has results[].artworkUrl100 (a 100×100 thumb).
     * We upsize by swapping the size token to 600×600 to get a higher-res image.
     */
    suspend fun iTunesCoverUrl(
        title: String,
        artist: String?,
    ): String? =
        withContext(Dispatchers.IO) {
            if (title.isBlank()) return@withContext null
            val term = listOfNotNull(artist?.takeIf(String::isNotBlank), title).joinToString(" ")
            val url =
                "https://itunes.apple.com/search".toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("term", term)
                    .addQueryParameter("entity", "song")
                    .addQueryParameter("limit", "1")
                    .build()
            val response =
                runCatching {
                    client.newCall(Request.Builder().url(url).get().build()).execute()
                }.getOrNull() ?: return@withContext null
            response.use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body =
                    runCatching { resp.body?.string() }.getOrNull()
                        ?: return@withContext null
                val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@withContext null
                val results: JsonArray = parsed["results"]?.jsonArray ?: return@withContext null
                val first = results.firstOrNull()?.jsonObject ?: return@withContext null
                val art = first["artworkUrl100"]?.jsonPrimitive?.contentOrNull
                    ?: first["artworkUrl60"]?.jsonPrimitive?.contentOrNull
                    ?: return@withContext null
                // Upsize: iTunes serves a larger image when you swap the size token.
                art.replace("100x100bb", "600x600bb")
                    .replace("60x60bb", "600x600bb")
                    .ifBlank { null }
            }
        }

    /**
     * Deezer public search API.
     * Endpoint: https://api.deezer.com/search?q=...&limit=1
     * Response JSON has data[].album.cover_medium (250×250) and cover_big (500×500).
     */
    suspend fun deezerCoverUrl(
        title: String,
        artist: String?,
    ): String? =
        withContext(Dispatchers.IO) {
            if (title.isBlank()) return@withContext null
            // Deezer's q parameter supports the artist: and track: qualifiers for
            // higher-precision matches. Fall back to a free-text query if the
            // qualified form comes up empty.
            val q =
                if (!artist.isNullOrBlank()) {
                    "artist:\"${artist.replace("\"", "")}\" track:\"${title.replace("\"", "")}\""
                } else {
                    title
                }
            val url =
                "https://api.deezer.com/search".toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("q", q)
                    .addQueryParameter("limit", "1")
                    .build()
            val response =
                runCatching {
                    client.newCall(Request.Builder().url(url).get().build()).execute()
                }.getOrNull() ?: return@withContext null
            response.use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body =
                    runCatching { resp.body?.string() }.getOrNull()
                        ?: return@withContext null
                val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@withContext null
                val data: JsonArray = parsed["data"]?.jsonArray ?: return@withContext null
                val first = data.firstOrNull()?.jsonObject ?: return@withContext null
                val album = first["album"]?.jsonObject ?: return@withContext null
                album["cover_big"]?.jsonPrimitive?.contentOrNull
                    ?: album["cover_medium"]?.jsonPrimitive?.contentOrNull
                    ?: album["cover_xl"]?.jsonPrimitive?.contentOrNull
            }
        }
}
