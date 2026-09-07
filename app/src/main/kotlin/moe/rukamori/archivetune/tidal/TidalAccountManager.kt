/**
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 *
 * Tidal account login via the OAuth 2.0 Authorization Code + PKCE flow (with a Bearer-capture
 * fallback), plus subscription (HiFi/Premium) detection and account-based stream resolution.
 * Signing in unlocks the account playback path; the legacy device-code grant has been removed in
 * favour of the in-app WebView login (see [buildPkceChallenge]/[exchangePkceCode]).
 */

package moe.rukamori.archivetune.tidal

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.audiosource.DirectStream
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object TidalAccountManager {
    private data class SearchMatch(
        val id: String,
        val title: String,
        val artist: String?,
        val album: String?,
        val durationMs: Long?,
    )

    private const val CLIENT_ID = "zU4XHVVkc2tDPo4t"
    private const val CLIENT_SECRET = "VJKhDFqJPqvsPVNBV6ukXTJmwlvbttP7wlMlrc72se4="

    private const val PKCE_CLIENT_ID = "6BDSRdpK9hqEBTgU"
    private const val PKCE_CLIENT_SECRET = "xeuPmY7nbpZ9IIbLAcQ93shka1VNheUAqN6IcszjTG8="
    private const val PKCE_AUTHORIZE_ENDPOINT = "https://login.tidal.com/authorize"
    const val PKCE_REDIRECT_URI = "https://tidal.com/android/login/auth"

    const val FLOW_OAUTH = "oauth"
    const val FLOW_PKCE = "pkce"
    const val FLOW_WEBCAPTURE = "webcapture"

    private const val TOKEN_ENDPOINT = "https://auth.tidal.com/v1/oauth2/token"
    private const val API_BASE = "https://api.tidal.com/v1"
    private const val SCOPE = "r_usr+w_usr+w_sub"
    private const val COUNTRY_CODE = "US"

    private val client =
        OkHttpClient
            .Builder()
            .dns(TidalDns)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

    private val resolveClient =
        client
            .newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()

    data class TokenResult(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtMillis: Long,
        val userId: Long?,
        val username: String?,
        val countryCode: String? = null,
    )

    enum class Subscription {
        UNKNOWN,
        PREMIUM,
        FREE,
    }

    suspend fun refreshAccessToken(
        refreshToken: String,
        flow: String = FLOW_OAUTH,
    ): TokenResult? =
        withContext(Dispatchers.IO) {
            if (flow == FLOW_WEBCAPTURE) {
                Timber.tag("TidalAccount").w("web-capture session has no refresh token; re-login required")
                return@withContext null
            }
            val clientId = if (flow == FLOW_PKCE) PKCE_CLIENT_ID else CLIENT_ID
            val clientSecret = if (flow == FLOW_PKCE) PKCE_CLIENT_SECRET else CLIENT_SECRET
            val body =
                FormBody
                    .Builder()
                    .add("client_id", clientId)
                    .add("client_secret", clientSecret)
                    .add("refresh_token", refreshToken)
                    .add("grant_type", "refresh_token")
                    .add("scope", SCOPE)
                    .build()
            val request =
                Request
                    .Builder()
                    .url(TOKEN_ENDPOINT)
                    .post(body)
                    .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    if (!response.isSuccessful || payload.isBlank()) {
                        Timber.tag("TidalAccount").w("token refresh failed: %d", response.code)
                        return@use null
                    }
                    val json = JSONObject(payload)
                    val user = json.optJSONObject("user")
                    TokenResult(
                        accessToken = json.getString("access_token"),
                        refreshToken = json.optString("refresh_token").ifBlank { null },
                        expiresAtMillis =
                            System.currentTimeMillis() + (json.optLong("expires_in", 3600L) * 1000L),
                        userId = user?.optLong("userId")?.takeIf { it > 0 },
                        username = user?.optString("username")?.ifBlank { null },
                        countryCode = user?.optString("countryCode")?.ifBlank { null },
                    )
                }
            }.getOrElse {
                Timber.tag("TidalAccount").w(it, "token refresh error")
                null
            }
        }

    data class PkceChallenge(
        val verifier: String,
        val challenge: String,
        val uniqueKey: String,
        val authUrl: String,
    )

    private fun base64UrlNoPad(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun buildPkceChallenge(): PkceChallenge {
        val random = SecureRandom()
        val verifierBytes = ByteArray(64).also { random.nextBytes(it) }
        val verifier = base64UrlNoPad(verifierBytes)
        val challenge =
            base64UrlNoPad(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val uniqueKeyBytes = ByteArray(8).also { random.nextBytes(it) }
        val uniqueKey = uniqueKeyBytes.joinToString("") { "%02x".format(it) }

        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        val authUrl =
            buildString {
                append(PKCE_AUTHORIZE_ENDPOINT)
                append("?response_type=code")
                append("&redirect_uri=").append(enc(PKCE_REDIRECT_URI))
                append("&client_id=").append(PKCE_CLIENT_ID)
                append("&lang=EN")
                append("&appMode=android")
                append("&client_unique_key=").append(uniqueKey)
                append("&code_challenge=").append(challenge)
                append("&code_challenge_method=S256")
                append("&restrict_signup=true")
            }
        return PkceChallenge(verifier, challenge, uniqueKey, authUrl)
    }

    suspend fun exchangePkceCode(
        code: String,
        verifier: String,
        uniqueKey: String,
    ): TokenResult? =
        withContext(Dispatchers.IO) {
            val body =
                FormBody
                    .Builder()
                    .add("code", code)
                    .add("client_id", PKCE_CLIENT_ID)
                    .add("client_secret", PKCE_CLIENT_SECRET)
                    .add("grant_type", "authorization_code")
                    .add("redirect_uri", PKCE_REDIRECT_URI)
                    .add("scope", SCOPE)
                    .add("code_verifier", verifier)
                    .add("client_unique_key", uniqueKey)
                    .build()
            val request =
                Request
                    .Builder()
                    .url(TOKEN_ENDPOINT)
                    .post(body)
                    .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    if (!response.isSuccessful || payload.isBlank()) {
                        Timber.tag("TidalAccount").w("PKCE code exchange failed: %d %s", response.code, payload.take(200))
                        return@use null
                    }
                    val json = JSONObject(payload)
                    val user = json.optJSONObject("user")
                    TokenResult(
                        accessToken = json.getString("access_token"),
                        refreshToken = json.optString("refresh_token").ifBlank { null },
                        expiresAtMillis =
                            System.currentTimeMillis() + (json.optLong("expires_in", 3600L) * 1000L),
                        userId = user?.optLong("userId")?.takeIf { it > 0 },
                        username = user?.optString("username")?.ifBlank { null },
                        countryCode = user?.optString("countryCode")?.ifBlank { null },
                    )
                }
            }.getOrElse {
                Timber.tag("TidalAccount").w(it, "PKCE code exchange error")
                null
            }
        }

    suspend fun buildSessionFromBearer(accessToken: String): TokenResult? =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url("$API_BASE/sessions")
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    if (!response.isSuccessful || payload.isBlank()) {
                        Timber.tag("TidalAccount").w("bearer session validation failed: %d", response.code)
                        return@use null
                    }
                    val json = JSONObject(payload)
                    TokenResult(
                        accessToken = accessToken,
                        refreshToken = null,

                        expiresAtMillis = System.currentTimeMillis() + 3600L * 1000L,
                        userId = json.optLong("userId").takeIf { it > 0 },
                        username = json.optString("username").ifBlank { null },
                        countryCode = json.optString("countryCode").ifBlank { null },
                    )
                }
            }.getOrElse {
                Timber.tag("TidalAccount").w(it, "bearer session validation error")
                null
            }
        }

    suspend fun fetchSubscription(
        accessToken: String,
        userId: Long,
    ): Subscription =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url("$API_BASE/users/$userId/subscription?countryCode=$COUNTRY_CODE")
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Timber.tag("TidalAccount").w("subscription lookup failed: %d", response.code)
                        return@use Subscription.UNKNOWN
                    }
                    val json = JSONObject(payload)
                    val type =
                        json
                            .optJSONObject("subscription")
                            ?.optString("type")
                            ?.uppercase()
                            .orEmpty()
                    val soundQuality = json.optString("highestSoundQuality").uppercase()

                    when {
                        json.has("premiumAccess") ->
                            if (json.optBoolean("premiumAccess", false)) {
                                Subscription.PREMIUM
                            } else {
                                Subscription.FREE
                            }
                        type.contains("FREE") -> Subscription.FREE

                        soundQuality.contains("LOSSLESS") || soundQuality.contains("HI_RES") ->
                            Subscription.PREMIUM

                        soundQuality == "LOW" -> Subscription.FREE

                        type.contains("HIFI") || type.contains("PREMIUM") || type.contains("PLUS") ->
                            Subscription.PREMIUM
                        else -> Subscription.UNKNOWN
                    }
                }
            }.getOrElse {
                Timber.tag("TidalAccount").w(it, "subscription lookup error")
                Subscription.UNKNOWN
            }
        }

    class TidalUnauthorizedException : Exception("TIDAL access token rejected (401)")

    suspend fun resolveDirectStream(
        accessToken: String,
        title: String,
        artists: List<String>,
        durationMs: Long?,
        audioQuality: String,
        cacheDir: File,
        preferLiveDash: Boolean = false,
        countryCode: String = COUNTRY_CODE,
    ): DirectStream? =
        withContext(Dispatchers.IO) {
            val country = countryCode.ifBlank { COUNTRY_CODE }
            val match = searchTrack(accessToken, title, artists, durationMs, country) ?: return@withContext null
            resolvePlaybackInfo(
                accessToken = accessToken,
                trackId = match.id,
                audioQuality = audioQuality,
                durationMs = durationMs,
                cacheDir = cacheDir,
                preferLiveDash = preferLiveDash,
            )?.copy(
                matchedTitle = match.title,
                matchedArtist = match.artist,
                matchedAlbum = match.album,
                matchedDurationMs = match.durationMs,
            )
        }

    private fun searchTrack(
        accessToken: String,
        title: String,
        artists: List<String>,
        durationMs: Long?,
        countryCode: String = COUNTRY_CODE,
    ): SearchMatch? {
        val primaryArtist = artists.firstOrNull().orEmpty()
        val query = URLEncoder.encode("$title $primaryArtist".trim(), "UTF-8")
        val request =
            Request
                .Builder()
                .url("$API_BASE/search/tracks?query=$query&limit=15&countryCode=$countryCode")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
        return runCatching {
            resolveClient.newCall(request).execute().use { response ->
                if (response.code == 401) throw TidalUnauthorizedException()
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful || payload.isBlank()) return@use null
                val items = JSONObject(payload).optJSONArray("items") ?: return@use null

                var bestMatch: SearchMatch? = null
                var bestScore = Int.MIN_VALUE
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val id = item.optLong("id").takeIf { it > 0 }?.toString() ?: continue
                    var score = 0
                    val candTitle = item.optString("title")
                    if (candTitle.equals(title, ignoreCase = true)) {
                        score += 50
                    } else if (candTitle.contains(title, ignoreCase = true) ||
                        title.contains(candTitle, ignoreCase = true)
                    ) {
                        score += 25
                    }
                    val candArtists =
                        item.optJSONArray("artists")?.let { arr ->
                            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
                        }.orEmpty()
                    if (primaryArtist.isNotBlank() &&
                        candArtists.any { it.contains(primaryArtist, ignoreCase = true) }
                    ) {
                        score += 30
                    }
                    val candDurationMs = item.optLong("duration").takeIf { it > 0 }?.times(1000L)
                    if (durationMs != null && candDurationMs != null &&
                        abs(candDurationMs - durationMs) <= 5000L
                    ) {
                        score += 20
                    }
                    if (score > bestScore) {
                        bestScore = score
                        bestMatch =
                            SearchMatch(
                                id = id,
                                title = candTitle,
                                artist = candArtists.joinToString(", ").takeIf { it.isNotBlank() },
                                album = item.optJSONObject("album")?.optString("title")?.takeIf { it.isNotBlank() },
                                durationMs = candDurationMs,
                            )
                    }
                }

                if (bestScore >= 40) bestMatch else null
            }
        }.getOrElse {
            if (it is TidalUnauthorizedException) throw it
            Timber.tag("TidalAccount").w(it, "account track search error")
            null
        }
    }

    private fun resolvePlaybackInfo(
        accessToken: String,
        trackId: String,
        audioQuality: String,
        durationMs: Long?,
        cacheDir: File,
        preferLiveDash: Boolean,
    ): DirectStream? {
        val url =
            "$API_BASE/tracks/$trackId/playbackinfopostpaywall" +
                "?audioquality=$audioQuality&playbackmode=STREAM&assetpresentation=FULL"
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
        return runCatching {
            resolveClient.newCall(request).execute().use { response ->
                if (response.code == 401) throw TidalUnauthorizedException()
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful || payload.isBlank()) {
                    Timber.tag("TidalAccount").w("playbackinfo failed: %d", response.code)
                    return@use null
                }
                val json = JSONObject(payload)

                if (json.optString("assetPresentation").equals("PREVIEW", ignoreCase = true)) {
                    Timber.tag("TidalAccount").w("playbackinfo returned PREVIEW; skipping account stream")
                    return@use null
                }
                val manifestB64 = json.optString("manifest").takeIf { it.isNotBlank() } ?: return@use null
                val manifestMime = json.optString("manifestMimeType").ifBlank { null }
                TidalAudioProvider.resolveAccountManifest(
                    manifestB64 = manifestB64,
                    declaredMimeType = manifestMime,
                    trackId = trackId,
                    quality = audioQuality,
                    durationMs = durationMs,
                    cacheDir = cacheDir,
                    preferLiveDash = preferLiveDash,
                )
            }
        }.getOrElse {
            if (it is TidalUnauthorizedException) throw it
            Timber.tag("TidalAccount").w(it, "playbackinfo error")
            null
        }
    }

    suspend fun getLyrics(
        accessToken: String,
        title: String,
        artists: List<String>,
        durationMs: Long?,
        countryCode: String = COUNTRY_CODE,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val match =
                    searchTrack(accessToken, title, artists, durationMs, countryCode)
                        ?: throw java.io.IOException("no Tidal match for lyrics")
                val url = "$API_BASE/tracks/${match.id}/lyrics"
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("Authorization", "Bearer $accessToken")
                        .get()
                        .build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 401) throw TidalUnauthorizedException()
                    if (!response.isSuccessful) throw java.io.IOException("Tidal lyrics HTTP ${response.code}")
                    val root = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                        ?: throw java.io.IOException("bad Tidal lyrics payload")
                    root.optString("lyrics").takeIf { it.isNotBlank() }
                        ?: throw java.io.IOException("empty Tidal lyrics")
                }
            }
        }

    fun isUnauthorized(root: Throwable?): Boolean {
        val stack = ArrayDeque<Throwable>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        root?.let { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val t = stack.removeLast()
            if (!seen.add(t)) continue
            if (t is TidalUnauthorizedException) return true
            t.cause?.let { stack.addLast(it) }
            t.suppressed.forEach { stack.addLast(it) }
        }
        return false
    }
}
