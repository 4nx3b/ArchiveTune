/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.applemusic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.rukamori.archivetune.canvas.AppleMusicProvider
import moe.rukamori.archivetune.constants.AppleMusicQuality
import moe.rukamori.archivetune.utils.PoolAccountManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Apple Music full-track playback source.
 *
 * Resolution pipeline (all verified against the live API with a Media-User-Token):
 *  1. Catalog search via the public AMP API (`/v1/catalog/{storefront}/search`) using the
 *     dev (Bearer) JWT + Media-User-Token — same endpoints as the canvas/lyrics providers.
 *  2. `POST https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback` with
 *     `{"salableAdamId": <songId>}` + both tokens → per-flavor asset list. The `ctrp`
 *     (AES-CTR "clear-mode") flavors are the playable ones; `cbcp` flavors are FairPlay
 *     (`skd://`) and cannot be played on Android.
 *  3. The chosen asset URL serves an HLS playlist whose segments are byteranges of a single
 *     encrypted CENC fMP4 file (`METHOD=ISO-23001-7`). [AppleMusicProgressiveDataSource]
 *     serves that file progressively; the decryption key is obtained at the codec level via
 *     a Widevine L3 license exchange against the response's `hls-key-server-url`
 *     (`wa/acquireWebPlaybackLicense`) — the same protocol Chrome/EME speaks.
 *
 * The Media-User-Token must belong to an account with an active Apple Music subscription.
 */
object AppleMusicAudioProvider {
    private const val TAG = "AppleMusicSource"

    private const val AMP_BASE = "https://amp-api.music.apple.com"
    private const val WEB_PLAYBACK_URL =
        "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/145.0.0.0 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private const val STOREFRONT_TTL_MS = 24 * 60 * 60 * 1000L

    private const val WIDEVINE_KEYFORMAT = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"

    @Volatile private var cachedStorefront: String? = null
    @Volatile private var cachedStorefrontAtMs = 0L
    private val storefrontMutex = Mutex()

    suspend fun resolveStorefront(): String {
        val now = System.currentTimeMillis()
        cachedStorefront?.let { if (now - cachedStorefrontAtMs < STOREFRONT_TTL_MS) return it }
        return storefrontMutex.withLock {
            val stillFresh = cachedStorefront?.takeIf { now - cachedStorefrontAtMs < STOREFRONT_TTL_MS }
            if (stillFresh != null) return@withLock stillFresh
            val media = mediaUserToken()?.takeIf { it.isNotBlank() } ?: return@withLock cachedStorefront ?: "us"
            val dev = devToken() ?: return@withLock cachedStorefront ?: "us"
            fetchedStorefront(media, dev)?.let { fetched ->
                cachedStorefront = fetched
                cachedStorefrontAtMs = System.currentTimeMillis()
                return@withLock fetched
            }
            cachedStorefront ?: "us"
        }
    }

    private suspend fun fetchedStorefront(mediaToken: String, devToken: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request =
                    Request.Builder()
                        .url("$AMP_BASE/v1/me/storefront")
                        .header("Authorization", "Bearer $devToken")
                        .header("Media-User-Token", mediaToken)
                        .header("Origin", "https://music.apple.com")
                        .header("Referer", "https://music.apple.com/")
                        .header("User-Agent", UA)
                        .get()
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "storefront fetch failed: %d".format(response.code))
                        return@use null
                    }
                    val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                    root["data"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                }
            }.getOrNull()
        }

    fun devToken(): String? = AppleMusicProvider.devTokenProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() }

    fun mediaUserToken(): String? = AppleMusicProvider.mediaUserTokenProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() }

    fun isAvailable(): Boolean = devToken() != null && mediaUserToken() != null

    private class AuthException : Exception("apple media-user-token rejected (401/403)")

    private data class RingEntry(val token: String, val poolId: Long?)

    @Volatile
    private var ring: List<RingEntry> = emptyList()

    @Volatile
    private var ringBuiltAt = 0L

    @Volatile
    private var ringIndex = 0

    private fun accountRing(): List<RingEntry> {
        val now = System.currentTimeMillis()
        val cached = ring
        if (cached.isNotEmpty() && now - ringBuiltAt < 60_000L) return cached
        val personal = mediaUserToken()
        val pool = PoolAccountManager.appleMusicAccounts().map { RingEntry(it.mediaUserToken, it.id) }
        val built = ((personal?.let { listOf(RingEntry(it, null)) } ?: emptyList()) + pool)
            .distinctBy { it.token }
        ring = built
        ringBuiltAt = now
        if (ringIndex >= built.size) ringIndex = 0
        return built
    }

    data class AppleMusicStream(
        val songId: String,
        val playlistUrl: String,
        val mediaUrl: String,
        val licenseUrl: String,
        val keyIdHex: String?,
        val drmUri: String,
        val flavor: String,
        val contentLength: Long?,
        val matchedTitle: String,
        val matchedArtist: String?,
        val matchedAlbum: String?,
        val matchedDurationMs: Long?,
    )

    data class AppleMusicCandidate(
        val songId: String,
        val title: String,
        val artist: String?,
        val thumbnailUrl: String?,
        val durationMs: Long?,
    )

    suspend fun searchCandidates(
        query: String,
        limit: Int = 8,
    ): List<AppleMusicCandidate> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val devToken = devToken() ?: return@withContext emptyList()
            val ringEntries = accountRing()
            if (ringEntries.isEmpty()) return@withContext emptyList()

            for (attempt in ringEntries.indices) {
                val index = (ringIndex + attempt) % ringEntries.size
                val entry = ringEntries[index]
                val rows =
                    runCatching {
                        searchCatalogRows(query, limit, entry.token, devToken)
                    }.getOrElse { error ->
                        if (error !is AuthException) {
                            Log.w(TAG, "search failed: ${error.message}")
                            return@withContext emptyList()
                        }
                        Log.w(TAG, "media-user-token rejected — rotating account ${index + 1}/${ringEntries.size}")
                        if (entry.poolId != null) {
                            PoolAccountManager.report(
                                service = "apple-music",
                                kind = "account",
                                id = entry.poolId,
                                reportType = "dead",
                            )
                        }
                        null
                    }
                if (rows != null) {
                    ringIndex = index
                    return@withContext rows
                }
            }
            emptyList()
        }

    private suspend fun searchCatalogRows(
        query: String,
        limit: Int,
        mediaToken: String,
        devToken: String,
    ): List<AppleMusicCandidate> =
        withContext(Dispatchers.IO) {
            val storefront = resolveStorefront()
            val url =
                "$AMP_BASE/v1/catalog/$storefront/search".toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("term", query)
                    .addQueryParameter("types", "songs")
                    .addQueryParameter("limit", limit.coerceAtMost(25).toString())
                    .build()
            val request =
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $devToken")
                    .header("Media-User-Token", mediaToken)
                    .header("Origin", "https://music.apple.com")
                    .header("Referer", "https://music.apple.com/")
                    .header("User-Agent", UA)
                    .get()
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 401 || response.code == 403) throw AuthException()
                    Log.w(TAG, "search failed: %d".format(response.code))
                    return@withContext emptyList()
                }
                val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val songs =
                    root["results"]?.jsonObject?.get("songs")?.jsonObject?.get("data")?.jsonArray
                        ?: return@withContext emptyList()
                songs.mapNotNull { element ->
                    val song = element.jsonObject
                    val attributes = song["attributes"]?.jsonObject ?: return@mapNotNull null
                    val songId = song["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val name = attributes["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    AppleMusicCandidate(
                        songId = songId,
                        title = name,
                        artist = attributes["artistName"]?.jsonPrimitive?.contentOrNull,
                        thumbnailUrl =
                            attributes["artwork"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                                ?.replace("{w}", "300")
                                ?.replace("{h}", "300"),
                        durationMs =
                            attributes["durationInMillis"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                    )
                }
            }
        }

    suspend fun resolveCandidates(
        title: String,
        artists: List<String>,
        album: String?,
        durationMs: Long?,
        quality: AppleMusicQuality = AppleMusicQuality.LOSSLESS,
    ): List<AppleMusicStream> =
        withContext(Dispatchers.IO) {
            val devToken = devToken() ?: return@withContext emptyList()
            val ringEntries = accountRing()
            if (ringEntries.isEmpty()) return@withContext emptyList()

            for (attempt in ringEntries.indices) {
                val index = (ringIndex + attempt) % ringEntries.size
                val entry = ringEntries[index]
                val streams =
                    runCatching {
                        resolveWithToken(entry.token, devToken, title, artists, quality)
                    }.getOrElse { error ->
                        if (error !is AuthException) {
                            Log.w(TAG, "resolve failed: ${error.message}")
                            return@withContext emptyList()
                        }
                        Log.w(TAG, "media-user-token rejected — rotating account ${index + 1}/${ringEntries.size}")
                        if (entry.poolId != null) {
                            PoolAccountManager.report(
                                service = "apple-music",
                                kind = "account",
                                id = entry.poolId,
                                reportType = "dead",
                            )
                        }
                        null
                    }
                if (streams != null) {
                    ringIndex = index
                    return@withContext streams
                }
            }
            emptyList()
        }

    private suspend fun resolveWithToken(
        mediaToken: String,
        devToken: String,
        title: String,
        artists: List<String>,
        quality: AppleMusicQuality,
    ): List<AppleMusicStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                val storefront = resolveStorefront()

                val query = if (title.contains(artists.firstOrNull().orEmpty(), ignoreCase = true)) {
                    title
                } else {
                    "${artists.firstOrNull().orEmpty()} $title".trim()
                }
                val songIds = searchSongIds(query, storefront, devToken, mediaToken)
                if (songIds.isEmpty()) return@runCatching emptyList()

                val out = mutableListOf<AppleMusicStream>()
                for (id in songIds.take(5)) {
                    if (out.size >= 3) break
                    webPlayback(id, devToken, mediaToken, storefront, quality)?.let { out += it }
                }
                out
            }.getOrElse { error ->

                if (error is AuthException) throw error
                Log.w(TAG, "resolve failed: ${error.message}")
                emptyList()
            }
        }

    private fun searchSongIds(
        query: String,
        storefront: String,
        devToken: String,
        mediaToken: String,
    ): List<String> {
        val url =
            "$AMP_BASE/v1/catalog/$storefront/search".toHttpUrl()
                .newBuilder()
                .addQueryParameter("term", query)
                .addQueryParameter("types", "songs")
                .addQueryParameter("limit", "5")
                .build()
        val request =
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $devToken")
                .header("Media-User-Token", mediaToken)
                .header("Origin", "https://music.apple.com")
                .header("Referer", "https://music.apple.com/")
                .header("User-Agent", UA)
                .get()
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 401 || response.code == 403) throw AuthException()
                Log.w(TAG, "search failed: %d".format(response.code))
                return emptyList()
            }
            val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            val songs =
                root["results"]?.jsonObject?.get("songs")?.jsonObject?.get("data")?.jsonArray
                    ?: return emptyList()
            return songs.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
        }
    }

    private fun webPlayback(
        songId: String,
        devToken: String,
        mediaToken: String,
        storefront: String,
        quality: AppleMusicQuality,
    ): AppleMusicStream? {
        val body = """{"salableAdamId":$songId,"language":"en-us"}""".toRequestBody(JSON_MEDIA)
        val request =
            Request.Builder()
                .url(WEB_PLAYBACK_URL)
                .post(body)
                .header("Authorization", "Bearer $devToken")
                .header("Media-User-Token", mediaToken)
                .header("Origin", "https://music.apple.com")
                .header("Referer", "https://music.apple.com/")
                .header("User-Agent", UA)
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 401 || response.code == 403) throw AuthException()
                Log.w(TAG, "webPlayback failed for %s: %d".format(songId, response.code))
                return null
            }
            val root = runCatching { json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject }
                .getOrNull() ?: return null
            val song = root["songList"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val licenseUrl = song["hls-key-server-url"]?.jsonPrimitive?.contentOrNull
            val assets = song["assets"]?.jsonArray ?: return null

            data class Asset(val flavor: String, val url: String, val kbps: Int)

            val candidates =
                assets.mapNotNull { element ->
                    val asset = element.jsonObject
                    val flavor = asset["flavor"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val url = asset["URL"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    if (!flavor.contains("ctrp")) return@mapNotNull null
                    val kbps = Regex("(\\d+)$").find(flavor)?.groupValues?.last()?.toIntOrNull() ?: 0
                    Asset(flavor, url, kbps)
                }.sortedByDescending { it.kbps }
            val asset = when (quality) {
                AppleMusicQuality.AAC ->
                    candidates.firstOrNull { it.kbps <= 320 } ?: candidates.minByOrNull { it.kbps }
                AppleMusicQuality.LOSSLESS ->
                    candidates.firstOrNull { it.kbps in 321..1411 } ?: candidates.lastOrNull()
                AppleMusicQuality.HI_RES_LOSSLESS -> candidates.firstOrNull()
            } ?: return null

            val playlistUrl = asset.url
            val parsed = parsePlaylist(playlistUrl) ?: return null
            val metadata = song["metadata"] as? JsonObject
            return AppleMusicStream(
                songId = songId,
                playlistUrl = playlistUrl,
                mediaUrl = parsed.mediaUrl,
                licenseUrl = licenseUrl ?: WEB_PLAYBACK_URL.replace("webPlayback", "acquireWebPlaybackLicense"),
                keyIdHex = parsed.keyIdHex,
                flavor = asset.flavor,
                contentLength = parsed.contentLength,
                matchedTitle = metadata?.title() ?: songId,
                matchedArtist = metadata?.artist(),
                matchedAlbum = metadata?.album(),
                matchedDurationMs = metadata?.durationMs(),
                drmUri = parsed.drmUri,
            )
        }
    }

    private fun JsonObject.title(): String? = this["title"]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.artist(): String? =
        this["artistName"]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.album(): String? =
        this["albumName"]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.durationMs(): Long? =
        this["trackDuration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private class ParsedPlaylist(
        val mediaUrl: String,
        val keyIdHex: String?,
        val drmUri: String,
        val contentLength: Long?,
    )

    private fun parsePlaylist(playlistUrl: String): ParsedPlaylist? {
        val request = Request.Builder().url(playlistUrl).header("User-Agent", UA).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "playlist fetch failed: %d".format(response.code))
                return null
            }
            val text = response.body?.string().orEmpty()
            var keyIdHex: String? = null
            var drmUri: String? = null
            var mediaName: String? = null
            val dataLines = mutableListOf<String>()
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                when {
                    line.startsWith("#EXT-X-KEY") && keyIdHex == null -> {

                        if (line.contains(WIDEVINE_KEYFORMAT, ignoreCase = true)) {

                            Regex("URI=\"([^\"]+)\"").find(line)?.let { match -> drmUri = match.groupValues[1] }
                            Regex("URI=\"data:[^\"]*base64,([^\"]+)\"").find(line)?.let { match ->
                                keyIdHex =
                                    runCatching {
                                        java.util.Base64.getDecoder().decode(match.groupValues[1].trim())
                                    }.getOrNull()
                                        ?.takeIf { it.size == AppleMusicVirtualStream.KID_BYTES }
                                        ?.joinToString("") { "%02x".format(it) }
                            }
                        }
                    }
                    line.startsWith("#EXT-X-MAP") && mediaName == null -> {
                        Regex("URI=\"([^\"]+)\"").find(line)?.let { match -> mediaName = match.groupValues[1] }
                    }
                    !line.startsWith("#") && line.isNotBlank() -> dataLines += line
                }
            }

            val name = mediaName ?: dataLines.firstOrNull() ?: return null
            val mediaUrl = playlistUrl.substringBeforeLast('/').trimEnd('/') + "/" + name
            return ParsedPlaylist(mediaUrl, keyIdHex, drmUri ?: "", null)
        }
    }
}
