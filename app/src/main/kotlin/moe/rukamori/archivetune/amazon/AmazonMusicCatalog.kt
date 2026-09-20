/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.amazon

import java.net.URLEncoder
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.applemusic.AppleMusicSearchItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AmazonMusicCatalog {
    private const val CONFIG_URL = "https://music.amazon.com/config.json"
    private const val SEARCH_ENDPOINT_NA = "https://na.web.skill.music.a2z.com/api/searchCatalogTracks"
    private const val SEARCH_ENDPOINT_EU = "https://eu.mesk.skill.music.a2z.com/api/searchCatalogTracks"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"

    private const val CONFIG_TTL_MS = 60L * 60L * 1000L
    private const val REQUEST_ID_LENGTH = 13

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

    private val JSON_MEDIA = "text/plain;charset=UTF-8".toMediaType()

    private val configMutex = Mutex()

    @Volatile
    private var cachedConfig: DeviceConfig? = null

    @Volatile
    private var configFetchedAtMs = 0L

    private data class DeviceConfig(
        val deviceId: String,
        val sessionId: String,
        val version: String,
        val csrfToken: String,
        val csrfTimestamp: String,
        val csrfRndNonce: String,
    )

    data class CatalogPage(
        val items: List<AppleMusicSearchItem.Track>,
        val hasMore: Boolean,
    )

    @Serializable
    private data class CatalogSearchResponse(
        val methods: List<MethodContainer> = emptyList(),
    )

    @Serializable
    private data class MethodContainer(
        val template: Template? = null,
    )

    @Serializable
    private data class Template(
        val widgets: List<Widget> = emptyList(),
    )

    @Serializable
    private data class Widget(
        val items: List<CatalogItem> = emptyList(),
    )

    @Serializable
    private data class CatalogItem(
        val primaryText: TextValue? = null,
        val secondaryText: String? = null,
        val image: String? = null,
        val iconButton: IconButtonValue? = null,

        val secondaryLink: SecondaryLink? = null,
    )

    @Serializable
    private data class TextValue(
        val text: String? = null,
    )

    @Serializable
    private data class IconButtonValue(
        val observer: ObserverValue? = null,
    )

    @Serializable
    private data class ObserverValue(

        val storageKey: String? = null,
    )

    @Serializable
    private data class SecondaryLink(
        val deeplink: String? = null,
    )

    suspend fun searchTrackSuggestions(
        query: String,
        limit: Int = 8,
    ): List<AppleMusicSearchItem.Track> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext emptyList()
            runCatching { searchCatalogTracks(trimmed) }
                .getOrDefault(emptyList())
                .take(limit)
        }

    suspend fun searchPage(
        query: String,
        limit: Int,
        offset: Int,
    ): CatalogPage =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext CatalogPage(emptyList(), hasMore = false)

            if (offset > 0) return@withContext CatalogPage(emptyList(), hasMore = false)
            val items =
                runCatching { searchCatalogTracks(trimmed) }
                    .getOrDefault(emptyList())
                    .take(limit)
            CatalogPage(items = items, hasMore = false)
        }

    private suspend fun searchCatalogTracks(query: String): List<AppleMusicSearchItem.Track> {
        val config = freshConfig()
        val body = buildRequestBody(query, config)

        val responseText =
            execute(SEARCH_ENDPOINT_NA, body)
                ?: execute(SEARCH_ENDPOINT_EU, body)
                ?: return emptyList()

        val parsed =
            runCatching { json.decodeFromString(CatalogSearchResponse.serializer(), responseText) }
                .getOrNull()
                ?: return emptyList()

        val items =
            parsed.methods
                .mapNotNull { it.template }
                .flatMap { it.widgets }
                .flatMap { it.items }

        return items.mapNotNull(::toTrack)
    }

    private fun execute(endpoint: String, body: String): String? =
        runCatching {
            val request =
                Request
                    .Builder()
                    .url(endpoint)
                    .header("Content-Type", "text/plain;charset=UTF-8")
                    .header("Origin", "https://music.amazon.com")
                    .header("Referer", "https://music.amazon.com/")
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.string()?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()

    private fun buildRequestBody(
        query: String,
        config: DeviceConfig,
    ): String {
        val timestamp = System.currentTimeMillis().toString()
        val requestId = randomRequestId()

        val csrfHeader =
            JSONObject()
                .put("interface", "CSRFInterface.v1_0.CSRFHeaderElement")
                .put("token", config.csrfToken)
                .put("timestamp", config.csrfTimestamp)
                .put("rndNonce", config.csrfRndNonce)
                .toString()
        val authHeader =
            JSONObject()
                .put("interface", "ClientAuthenticationInterface.v1_0.ClientTokenElement")
                .put("accessToken", "")
                .toString()

        val innerHeaders =
            JSONObject()
                .put("x-amzn-authentication", authHeader)
                .put("x-amzn-device-model", "WEBPLAYER")
                .put("x-amzn-device-width", 1920)
                .put("x-amzn-device-family", "WebPlayer")
                .put("x-amzn-device-id", config.deviceId)
                .put("x-amzn-user-agent", USER_AGENT)
                .put("x-amzn-session-id", config.sessionId)
                .put("x-amzn-device-height", 1080)
                .put("x-amzn-request-id", requestId)
                .put("x-amzn-device-language", "en_US")
                .put("x-amzn-currency-of-preference", "USD")
                .put("x-amzn-os-version", "1.0")
                .put("x-amzn-application-version", config.version)
                .put("x-amzn-device-time-zone", "Asia/Calcutta")
                .put("x-amzn-timestamp", timestamp)
                .put("x-amzn-csrf", csrfHeader)
                .put("x-amzn-music-domain", "music.amazon.com")
                .put("x-amzn-referer", "music.amazon.com")
                .put("x-amzn-affiliate-tags", "")
                .put("x-amzn-ref-marker", "")
                .put("x-amzn-page-url", pageUrl(query))
                .put("x-amzn-weblab-id-overrides", "")
                .put("x-amzn-video-player-token", "")
                .put("x-amzn-feature-flags", "hd-supported,uhd-supported")
                .put("x-amzn-has-profile-id", "")
                .put("x-amzn-age-band", "")

        val userHash = JSONObject().put("level", "LIBRARY_MEMBER").toString()
        return JSONObject()
            .put("keyword", query)
            .put("userHash", userHash)
            .put("headers", innerHeaders.toString())
            .toString()
    }

    private fun pageUrl(query: String): String =
        "https://music.amazon.com/search/" +
            URLEncoder.encode(query, "UTF-8").replace("+", "%20") +
            "/songs"

    private fun randomRequestId(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return buildString {
            repeat(REQUEST_ID_LENGTH) { append(alphabet[Random.nextInt(alphabet.length)]) }
        }
    }

    private fun toTrack(item: CatalogItem): AppleMusicSearchItem.Track? {
        val title = item.primaryText?.text?.takeIf { it.isNotBlank() } ?: return null

        val trackId = item.iconButton?.observer?.storageKey?.takeIf { it.isNotBlank() } ?: return null
        return AppleMusicSearchItem.Track(
            id = trackId,
            title = title,
            artist = item.secondaryText.orEmpty(),
            album = null,
            artworkUrl = item.image,

            durationMs = 0L,
            viewUrl = null,
            explicit = false,
        )
    }

    private suspend fun freshConfig(): DeviceConfig {
        cachedConfig?.let { cached ->
            if (System.currentTimeMillis() - configFetchedAtMs < CONFIG_TTL_MS) return cached
        }
        return configMutex.withLock {
            cachedConfig?.let { cached ->
                if (System.currentTimeMillis() - configFetchedAtMs < CONFIG_TTL_MS) return@withLock cached
            }
            val fetched = fetchConfig()
            if (fetched != null) {
                cachedConfig = fetched
                configFetchedAtMs = System.currentTimeMillis()
            }

            fetched ?: cachedConfig ?: error("Amazon Music device config unavailable")
        }
    }

    private fun fetchConfig(): DeviceConfig? =
        runCatching {
            val request =
                Request
                    .Builder()
                    .url(CONFIG_URL)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Referer", "https://music.amazon.com/")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@runCatching null
                parseConfig(JSONObject(body))
            }
        }.getOrNull()

    private fun parseConfig(root: JSONObject): DeviceConfig? {
        val deviceId = root.optString("deviceId").takeIf { it.isNotBlank() } ?: return null
        val sessionId = root.optString("sessionId").takeIf { it.isNotBlank() } ?: return null
        val version = root.optString("version").takeIf { it.isNotBlank() } ?: return null

        val csrf = root.opt("csrf")
        val triple =
            when (csrf) {
                is JSONObject ->
                    CsrfTriple(
                        token = csrf.optString("token").takeIf { it.isNotBlank() } ?: return null,
                        timestamp = csrf.optString("ts").takeIf { it.isNotBlank() } ?: return null,
                        rndNonce = csrf.optString("rnd").takeIf { it.isNotBlank() } ?: return null,
                    )

                is String -> parseStringifiedCsrf(csrf) ?: return null
                else -> return null
            }

        return DeviceConfig(
            deviceId = deviceId,
            sessionId = sessionId,
            version = version,
            csrfToken = triple.token,
            csrfTimestamp = triple.timestamp,
            csrfRndNonce = triple.rndNonce,
        )
    }

    private data class CsrfTriple(
        val token: String,
        val timestamp: String,
        val rndNonce: String,
    )

    private fun parseStringifiedCsrf(raw: String): CsrfTriple? {
        if (!raw.contains("'")) return null
        val token = CSRF_TOKEN_REGEX.find(raw)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        val timestamp = CSRF_TS_REGEX.find(raw)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        val rndNonce = CSRF_RND_REGEX.find(raw)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        return CsrfTriple(token = token, timestamp = timestamp, rndNonce = rndNonce)
    }

    private val CSRF_TOKEN_REGEX = Regex("'token'\\s*:\\s*'([^']*)'")
    private val CSRF_TS_REGEX = Regex("'ts'\\s*:\\s*(\\d+)")
    private val CSRF_RND_REGEX = Regex("'rnd'\\s*:\\s*'([^']*)'")
}
