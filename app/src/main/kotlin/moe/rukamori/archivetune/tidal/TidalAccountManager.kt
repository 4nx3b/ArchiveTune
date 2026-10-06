
package moe.rukamori.archivetune.tidal

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptScope
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptTimeouts
import moe.rukamori.archivetune.audiosource.DirectStream
import moe.rukamori.archivetune.audiosource.rethrowIfAudioSourceCancelled
import moe.rukamori.archivetune.audiosource.withAudioSourceAttemptDeadline
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

    // The legacy device client (cid 3235) was retired by Tidal: it still mints tokens, but the
    // refresh grant now rejects them. The registered Android TV client below is accepted for both
    // the initial grant and refreshes.
    private const val CLIENT_ID = "fX2JxdmntZWK0ixT"
    private const val CLIENT_SECRET = "1Nn9AfDAjxrgJFJbKNWLeAyKGVGmINuXPPLHVXAvxAg="

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
        AudioSourceAttemptScope.withinSuspending(AudioSourceAttemptTimeouts.PROVIDER_ATTEMPT_MS) {
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
                    client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
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
                }.onFailure { it.rethrowIfAudioSourceCancelled() }.getOrElse {
                    Timber.tag("TidalAccount").w(it, "token refresh error")
                    null
                }
            }
        } ?: null

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
                client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
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
            }.onFailure { it.rethrowIfAudioSourceCancelled() }.getOrElse {
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
                client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
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
            }.onFailure { it.rethrowIfAudioSourceCancelled() }.getOrElse {
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
                client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
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
            }.onFailure { it.rethrowIfAudioSourceCancelled() }.getOrElse {
                Timber.tag("TidalAccount").w(it, "subscription lookup error")
                Subscription.UNKNOWN
            }
        }

    class TidalUnauthorizedException : Exception("TIDAL access token rejected (401)")

    class TidalPreviewException : Exception("TIDAL playbackinfo returned PREVIEW (no FULL asset)")

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
        AudioSourceAttemptScope.withinSuspending(AudioSourceAttemptTimeouts.PROVIDER_ATTEMPT_MS) {
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
        } ?: null

    suspend fun resolveDirectStreamByTrackId(
        accessToken: String,
        trackId: String,
        durationMs: Long?,
        audioQuality: String,
        cacheDir: File,
        preferLiveDash: Boolean = false,
    ): DirectStream? =
        AudioSourceAttemptScope.withinSuspending(AudioSourceAttemptTimeouts.PROVIDER_ATTEMPT_MS) {
            withContext(Dispatchers.IO) {
                resolvePlaybackInfo(
                    accessToken = accessToken,
                    trackId = trackId,
                    audioQuality = audioQuality,
                    durationMs = durationMs,
                    cacheDir = cacheDir,
                    preferLiveDash = preferLiveDash,
                )?.copy(trustedDirectId = true)
            }
        } ?: null

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
        val result =
            runCatching {
                resolveClient.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
                    if (response.code == 401) throw TidalUnauthorizedException()
                    val payload = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Timber.tag("TidalAccount").w("account track search failed: HTTP %d", response.code)
                        return@use null
                    }
                    if (payload.isBlank()) return@use null
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
            }.onFailure { it.rethrowIfAudioSourceCancelled() }.getOrElse {
                if (it is TidalUnauthorizedException) throw it
                Timber.tag("TidalAccount").w(it, "account track search error")
                null
            }
        if (result == null) {
            Timber.tag("TidalAccount").w("account track search produced no match >= 40 for \"%s\"", title)
        }
        return result
    }

    private fun resolvePlaybackInfo(
        accessToken: String,
        trackId: String,
        audioQuality: String,
        durationMs: Long?,
        cacheDir: File,
        preferLiveDash: Boolean,
    ): DirectStream? {
        try {
            val direct = resolvePlaybackInfoOnce(
                accessToken = accessToken,
                trackId = trackId,
                audioQuality = audioQuality,
                durationMs = durationMs,
                cacheDir = cacheDir,
                preferLiveDash = preferLiveDash,
            )
            if (direct != null) return direct
        } catch (e: TidalPreviewException) {
            val fallbackQuality =
                when (audioQuality) {
                    "HI_RES_LOSSLESS" -> "LOSSLESS"
                    "LOSSLESS" -> "HIGH"
                    else -> null
                }
            if (fallbackQuality != null) {
                Timber.tag("TidalAccount").w(
                    "playbackinfo PREVIEW at %s; retrying at %s",
                    audioQuality,
                    fallbackQuality,
                )
                return resolvePlaybackInfoOnce(
                    accessToken = accessToken,
                    trackId = trackId,
                    audioQuality = fallbackQuality,
                    durationMs = durationMs,
                    cacheDir = cacheDir,
                    preferLiveDash = preferLiveDash,
                )
            }

            Timber.tag("TidalAccount").w("playbackinfo PREVIEW at every quality tier; account cannot stream FULL")
            throw e
        }
        return null
    }

    private fun resolvePlaybackInfoOnce(
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
            resolveClient.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
                if (response.code == 401) throw TidalUnauthorizedException()
                val payload = response.body?.string() ?: return@use null
                if (!response.isSuccessful || payload.isBlank()) {
                    Timber.tag("TidalAccount").w("playbackinfo failed: %d", response.code)
                    return@use null
                }
                val json = JSONObject(payload)

                if (json.optString("assetPresentation").equals("PREVIEW", ignoreCase = true)) {
                    throw TidalPreviewException()
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
        }.onFailure { it.rethrowIfAudioSourceCancelled() }.getOrElse {
            if (it is TidalUnauthorizedException || it is TidalPreviewException) throw it
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
                client.newCall(request).withAudioSourceAttemptDeadline().execute().use { response ->
                    if (response.code == 401) throw TidalUnauthorizedException()
                    if (!response.isSuccessful) throw java.io.IOException("Tidal lyrics HTTP ${response.code}")
                    val root = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                        ?: throw java.io.IOException("bad Tidal lyrics payload")
                    root.optString("lyrics").takeIf { it.isNotBlank() }
                        ?: throw java.io.IOException("empty Tidal lyrics")
                }
            }.onFailure { it.rethrowIfAudioSourceCancelled() }
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

    /**
     * True when a Tidal access JWT is expired, or expires within [marginSecs].
     *
     * Tidal access tokens live about an hour while the app's Source Pool cache is held for hours,
     * so a cached pooled token is usually stale before it is ever used. Reading `exp` lets the
     * resolver notice that and re-lease a fresh one, instead of firing the token, taking a 401,
     * and reporting a perfectly healthy account dead.
     *
     * Non-JWT or unparseable tokens are reported as not expired: an unparseable token is not
     * proof of expiry, so the request itself decides. The signature is deliberately not verified
     * — it only decides whether to re-fetch.
     */
    fun isAccessTokenExpired(token: String, marginSecs: Long = 300L): Boolean {
        val payload = token.split('.').getOrNull(1) ?: return false
        return try {
            val json =
                JSONObject(
                    android.util.Base64.decode(
                        payload.replace('-', '+').replace('_', '/'),
                        android.util.Base64.DEFAULT,
                    ).toString(Charsets.UTF_8),
                )
            val exp = json.optLong("exp", 0L)
            exp > 0 && exp - marginSecs <= System.currentTimeMillis() / 1000L
        } catch (e: Exception) {
            // An unparseable token is not proof of expiry; let the request itself decide.
            false
        }
    }
}
