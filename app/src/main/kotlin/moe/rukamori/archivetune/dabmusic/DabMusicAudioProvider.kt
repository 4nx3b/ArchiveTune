/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.dabmusic

import moe.rukamori.archivetune.audiosource.TrackMatching
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Resolves playable lossless streams for a track from the DabMusic catalog at
 * https://dabmusic.xyz.
 *
 * API contract (reverse-engineered from the official dabcli client, github.com/LeoAj2005/dabmusicclient):
 *
 *  - `POST /api/auth/login` with JSON `{"email": "...", "password": "..."}`. On success the
 *    server returns HTTP 200 and sets a `session=<token>` cookie on the response. That cookie
 *    MUST be sent on every subsequent request — the catalog and stream endpoints both 401
 *    without it. The login flow is triggered explicitly by the user tapping "Login" in Sources
 *    settings; this provider never logs in on its own (it would leak credentials into background
 *    network windows and re-prompt for them on every cold start).
 *
 *  - `GET /api/search?q=<query>&type=track` returns `{"tracks": [{id, title, artist, artistId,
 *    albumId, albumTitle, releaseDate, ...}, ...]}`. The `type` parameter also accepts `album`
 *    and `artist`; we always send `track` because the player is asking for a single track.
 *
 *  - `GET /api/stream?trackId=<id>&quality=<quality>` returns `{"url": "<direct stream URL>"}`.
 *    `quality` is a numeric string: `"27"` = FLAC (lossless), `"5"` = MP3. We always request `27`
 *    because DabMusic is advertised as a lossless source; if the catalog doesn't have a FLAC
 *    master it falls back to MP3 server-side and the `url` extension tells us which one we got.
 *
 *  - `GET /api/lyrics?title=<title>&artist=<artist>` returns `{"lyrics": "...", "unsynced": bool}`.
 *    Not used here yet — wired in for future lyrics-provider integration.
 *
 * Cloudflare note: dabmusic.xyz is fronted by a Cloudflare "Just a moment..." interstitial that
 * challenges non-browser clients. The WAF rule on this gateway whitelists
 * `POST /api/auth/login` (so the user can authenticate) but applies a "managed challenge" to
 * `GET /api/search` and `GET /api/stream`. On challenged requests Cloudflare returns HTTP 200
 * with `Transfer-Encoding: chunked` and drip-feeds the interstitial HTML body over 30+ seconds,
 * which causes OkHttp's `readTimeout` to fire as a `SocketTimeoutException` (rather than the
 * fast 403+HTML that data-center IPs see). The provider defends against this in three layers:
 *
 *   1. Browser-like headers — `Referer`, `Origin`, `Sec-Fetch-*`, `Accept-Encoding` are sent on
 *      every request so Cloudflare's heuristics are less likely to flag the client as a bot.
 *      On residential mobile IPs this is often enough — login works without any extra setup.
 *
 *   2. `cf_clearance` cookie — when the user taps "Solve Cloudflare Challenge" in Sources
 *      settings, an in-app WebView loads `https://dabmusic.xyz/`, the WebView's JS engine
 *      solves Cloudflare's challenge automatically, and the resulting `cf_clearance` cookie is
 *      extracted via `CookieManager` and pushed into this provider via [setCfClearance]. The
 *      cookie is ~30-minute-lived and must be refreshed periodically. With this cookie present,
 *      Cloudflare passes the request through to the backend and the API returns real JSON.
 *
 *   3. Cloudflare detection — when neither (1) nor (2) is sufficient, the provider detects the
 *      interstitial by inspecting the response body (HTML body, "Just a moment", "cf-challenge",
 *      "challenge-platform") regardless of status code, and returns null so the playback layer
 *      falls through to the next source in the chain. The UI also surfaces a
 *      [LoginResult.CloudflareBlocked]-style signal so the user knows to tap the bypass button.
 *
 * All calls run blocking network I/O and must not be made from the main thread.
 */
object DabMusicAudioProvider {
    const val DEFAULT_BASE_URL = "https://dabmusic.xyz"

    private const val TAG = "DabMusic"
    private const val SEARCH_LIMIT = 10
    private const val SEARCH_CACHE_MS = 10 * 60 * 1000L
    private const val STREAM_CACHE_MS = 5 * 60 * 1000L
    private const val FAILURE_CACHE_MS = 5 * 60 * 1000L

    private const val MIME_FLAC = "audio/flac"
    private const val MIME_MPEG = "audio/mpeg"

    /** Quality codes used by the DabMusic /api/stream endpoint. `27` = FLAC, `5` = MP3. */
    const val QUALITY_FLAC = "27"
    const val QUALITY_MP3 = "5"

    /**
     * A desktop Chrome User-Agent. The DabMusic gateway is behind Cloudflare and serves the
     * interstitial to clients it identifies as bots, so we present as a browser to maximise the
     * chance the REST endpoint is reachable at all.
     */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // Cloudflare's managed-challenge drip-feed can take 20+ seconds to either flush the
            // body or close the connection; 30s gives us a real answer instead of a premature
            // SocketTimeoutException on slow mobile networks.
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

    /** What the player asked for. Mirrors the shape of the other providers' Query types. */
    data class Query(
        val mediaId: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
    ) {
        fun cacheKey(): String = "$mediaId|${title.lowercase()}|${artists.joinToString(",").lowercase()}"
    }

    /** A resolved DabMusic stream, before mapping to the playback layer's [moe.rukamori.archivetune.audiosource.DirectStream]. */
    data class Resolved(
        val uri: String,
        val mimeType: String,
        val codecs: String,
        val contentLength: Long?,
        val label: String,
        val matchedTitle: String?,
        val matchedArtist: String?,
        val matchedAlbum: String?,
        val matchedDurationMs: Long?,
        val sampleRate: Int?,
        val bitDepth: Int?,
    )

    /** Outcome of a login attempt. The UI surfaces this to the user. */
    sealed class LoginResult {
        data object Success : LoginResult()

        data class Failure(val reason: String) : LoginResult()

        data class CloudflareBlocked(val rayId: String?) : LoginResult()
    }

    @Volatile
    private var baseUrl: String = DEFAULT_BASE_URL

    /** Email + password pair entered by the user. Used only when [login] is called explicitly. */
    private val credentials = AtomicReference<Pair<String, String>?>(null)

    /**
     * The session cookie authorising /api/search and /api/stream. Set by [login] on success, or
     * pushed directly from preferences via [setSessionCookie] when the app cold-starts with a
     * previously-persisted session. Blank = not logged in; resolve() will short-circuit to null.
     */
    @Volatile
    private var sessionCookie: String = ""

    /**
     * The Cloudflare `cf_clearance` cookie obtained by solving the interstitial in an in-app
     * WebView (see [moe.rukamori.archivetune.dabmusic.DabMusicCloudflareBypass]). When present,
     * it is appended to the `Cookie` header on every request alongside the `session` cookie.
     * Blank = no bypass active; the provider will attempt the request and rely on browser-like
     * headers + Cloudflare detection. ~30-minute-lived; must be refreshed by re-solving.
     */
    @Volatile
    private var cfClearance: String = ""

    @Volatile
    var lastResolvedTrackId: String? = null
        private set

    private data class CachedSearch(val candidate: TrackMatching.Candidate?, val expiresAt: Long)
    private data class CachedStream(val stream: Resolved, val expiresAt: Long)

    private val searchCache = ConcurrentHashMap<String, CachedSearch>()
    private val streamCache = ConcurrentHashMap<String, CachedStream>()
    private val failureCache = ConcurrentHashMap<String, Long>()

    /**
     * Sets the base URL used to compose DabMusic endpoints. Blank resets to [DEFAULT_BASE_URL].
     * Trailing slashes are trimmed. Cheap and idempotent; safe to push the current preference
     * value on every settings change.
     */
    fun setBaseUrl(url: String?) {
        val trimmed = url?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        baseUrl = trimmed ?: DEFAULT_BASE_URL
    }

    /**
     * Sets the email + password pair used by [login]. Does NOT trigger a login — the UI calls
     * [login] explicitly so the user sees the result. Blank/null clears the stored credentials.
     */
    fun setCredentials(email: String?, password: String?) {
        val e = email?.trim().orEmpty()
        val p = password.orEmpty()
        credentials.set(if (e.isNotEmpty() && p.isNotEmpty()) e to p else null)
    }

    /**
     * Sets the session cookie directly. Used at app cold-start to restore a previously-persisted
     * session without re-prompting for credentials. Blank = not logged in.
     */
    fun setSessionCookie(cookie: String?) {
        sessionCookie = cookie?.trim().orEmpty()
        if (sessionCookie.isEmpty()) {
            // Signing out: drop caches so a re-login doesn't serve stale results.
            searchCache.clear()
            streamCache.clear()
            failureCache.clear()
        }
    }

    /**
     * Sets the Cloudflare `cf_clearance` cookie obtained by solving the interstitial in a WebView.
     * Blank = clear the bypass (e.g. when it expires or the user signs out). Cheap and idempotent;
     * safe to push the current preference value on every settings change.
     */
    fun setCfClearance(cookie: String?) {
        val trimmed = cookie?.trim().orEmpty()
        if (cfClearance != trimmed) {
            cfClearance = trimmed
            // cf_clearance is request-level — drop caches so we don't serve stale "no match"
            // results that were computed under the old (or absent) bypass.
            searchCache.clear()
            failureCache.clear()
        }
    }

    /** Returns true when a cf_clearance cookie is present (the bypass is armed). */
    fun hasCfClearance(): Boolean = cfClearance.isNotEmpty()

    /** Returns the current session cookie (may be blank). The UI uses this to display status. */
    fun getSessionCookie(): String = sessionCookie

    /** Returns true when the user has either stored credentials OR a stored session cookie. */
    fun hasAccount(): Boolean = sessionCookie.isNotEmpty() || credentials.get() != null

    /**
     * Lightweight probe used by the "Solve Cloudflare Challenge" UI to decide whether the bypass
     * is needed at all. Sends a GET /api/search?q=probe&type=track and inspects the response body
     * — if it's JSON, the gateway is reachable directly; if it's the Cloudflare interstitial, the
     * user needs to solve the challenge. Returns true when Cloudflare is blocking (bypass needed),
     * false when the gateway is reachable (no bypass needed). Never throws.
     *
     * Blocking network I/O — must not be called from the main thread.
     */
    fun isCloudflareBlocking(): Boolean {
        val url =
            baseUrl
                .toHttpUrl()
                .newBuilder()
                .addPathSegment("api")
                .addPathSegment("search")
                .addQueryParameter("q", "probe")
                .addQueryParameter("type", "track")
                .build()
        val request = baseRequest(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                looksLikeCloudflare(body) || (!response.isSuccessful && response.code != 401)
            }
        } catch (e: Exception) {
            // SocketTimeoutException is the most common symptom of Cloudflare's drip-feed —
            // treat it as "blocked" so the UI offers the bypass.
            Timber.tag(TAG).w(e, "reachability probe failed; assuming Cloudflare is blocking")
            true
        }
    }

    /**
     * Calls POST /api/auth/login with the stored credentials. On success, persists the session
     * cookie via [setSessionCookie] and returns [LoginResult.Success]. The caller (the UI) is
     * responsible for saving the returned cookie to [moe.rukamori.archivetune.constants.DabMusicSessionCookieKey].
     *
     * Returns [LoginResult.CloudflareBlocked] when the gateway returns the Cloudflare
     * interstitial (HTTP 403 + HTML body) — this is a common failure mode for non-browser
     * clients and is surfaced distinctly so the UI can explain it.
     *
     * Blocking network I/O — must not be called from the main thread.
     */
    fun login(): LoginResult {
        val creds = credentials.get()
        if (creds == null) {
            return LoginResult.Failure("No email/password set")
        }
        val (email, password) = creds

        val url =
            baseUrl
                .toHttpUrl()
                .newBuilder()
                .addPathSegment("api")
                .addPathSegment("auth")
                .addPathSegment("login")
                .build()
        val jsonBody = JSONObject().put("email", email).put("password", password).toString()
        val request =
            baseRequest(url)
                .post(jsonBody.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 403) {
                    val body = response.body?.string().orEmpty()
                    if (looksLikeCloudflare(body)) {
                        val rayId = response.header("cf-ray")
                        Timber.tag(TAG).w("login blocked by Cloudflare (cf-ray=%s)", rayId)
                        return LoginResult.CloudflareBlocked(rayId)
                    }
                    return LoginResult.Failure("HTTP 403 — service unavailable")
                }
                if (response.code == 401) {
                    return LoginResult.Failure("Invalid email or password")
                }
                if (!response.isSuccessful) {
                    return LoginResult.Failure("HTTP ${response.code}")
                }

                // Extract the session cookie from Set-Cookie headers. OkHttp exposes them via
                // response.headers — there may be multiple Set-Cookie headers; we want the one
                // named "session".
                val cookies = response.headers("Set-Cookie")
                val session =
                    cookies
                        .firstNotNullOfOrNull { raw ->
                            val parts = raw.split(";").map { it.trim() }
                            val kv = parts.firstOrNull()?.split("=", limit = 2)
                            if (kv != null && kv.size == 2 && kv[0].equals("session", ignoreCase = true)) {
                                kv[1].trim().ifBlank { null }
                            } else {
                                null
                            }
                        }
                        ?.takeIf { it.isNotEmpty() }

                if (session.isNullOrBlank()) {
                    // Some deployments return the session token in the JSON body instead.
                    val body = response.body?.string().orEmpty()
                    val parsed = runCatching { JSONObject(body) }.getOrNull()
                    val bodyToken = parsed?.optString("session")?.ifBlank { null } ?: parsed?.optString("token")?.ifBlank { null }
                    if (bodyToken.isNullOrBlank()) {
                        Timber.tag(TAG).w("login succeeded (HTTP %d) but no session cookie in response", response.code)
                        return LoginResult.Failure("Login succeeded but no session cookie returned")
                    }
                    setSessionCookie(bodyToken)
                    return LoginResult.Success
                }

                setSessionCookie(session)
                Timber.tag(TAG).i("login succeeded, session cookie length=%d", session.length)
                LoginResult.Success
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "login network failed")
            LoginResult.Failure(e.message ?: "Network error")
        }
    }

    /** Clears the session cookie (sign-out). The UI calls this when the user taps "Sign out". */
    fun signOut() {
        setSessionCookie(null)
    }

    /**
     * Resolves a playable stream for [query]. Returns null when the user is not logged in, when
     * the catalog has no acceptable match, when the gateway is unreachable, or when Cloudflare
     * challenges the request. Never throws — failures are cached for [FAILURE_CACHE_MS] so we
     * don't hammer the gateway on every retry.
     */
    fun resolve(
        query: Query,
        quality: String = QUALITY_FLAC,
    ): Resolved? {
        if (sessionCookie.isEmpty()) {
            Timber.tag(TAG).d("resolve skipped — not logged in")
            return null
        }

        val now = System.currentTimeMillis()
        val cacheKey = query.cacheKey() + ":" + quality
        streamCache[cacheKey]?.let { cached ->
            if (cached.expiresAt > now) return cached.stream
            streamCache.remove(cacheKey)
        }
        failureCache[cacheKey]?.let { failedUntil ->
            if (failedUntil > now) return null
            failureCache.remove(cacheKey)
        }

        val match =
            runCatching { search(query) }
                .onFailure { Timber.tag(TAG).w(it, "search failed for \"%s\"", query.title) }
                .getOrNull()
        if (match == null) {
            failureCache[cacheKey] = now + FAILURE_CACHE_MS
            return null
        }

        val streamUrl =
            runCatching { requestStream(match.id, quality) }
                .onFailure { Timber.tag(TAG).w(it, "stream resolve failed for track %s", match.id) }
                .getOrNull()
        if (streamUrl == null) {
            failureCache[cacheKey] = now + FAILURE_CACHE_MS
            return null
        }

        lastResolvedTrackId = match.id
        val isFlac =
            streamUrl.substringAfterLast('.', "").equals("flac", ignoreCase = true) ||
                quality == QUALITY_FLAC
        val resolved =
            Resolved(
                uri = streamUrl,
                mimeType = if (isFlac) MIME_FLAC else MIME_MPEG,
                codecs = if (isFlac) "flac" else "mp3",
                contentLength = null,
                label =
                    when {
                        isFlac -> "DabMusic FLAC"
                        quality == QUALITY_MP3 -> "DabMusic MP3"
                        else -> "DabMusic"
                    },
                matchedTitle = match.title,
                matchedArtist = match.artists.firstOrNull(),
                matchedAlbum = match.album,
                matchedDurationMs = match.durationMs,
                // DabMusic's gateway does not report sample rate / bit depth; assume CD-quality
                // for FLAC (the catalog's lossless tier is 16-bit/44.1 kHz) and leave MP3 to the
                // playback layer's tier heuristic.
                sampleRate = if (isFlac) 44_100 else null,
                bitDepth = if (isFlac) 16 else null,
            )
        streamCache[cacheKey] = CachedStream(resolved, now + STREAM_CACHE_MS)
        return resolved
    }

    /** Evicts cached search/stream/failure entries for [query]+[quality]. */
    fun invalidate(
        query: Query,
        quality: String = QUALITY_FLAC,
    ) {
        val key = query.cacheKey() + ":" + quality
        streamCache.remove(key)
        failureCache.remove(key)
    }

    // ---------------------------------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------------------------------

    /** Finds the DabMusic track id that best matches [query], or null when nothing scores high enough. */
    private fun search(query: Query): TrackMatching.Candidate? {
        val key = query.cacheKey()
        val now = System.currentTimeMillis()
        searchCache[key]?.let { cached ->
            if (cached.expiresAt > now) return cached.candidate
            searchCache.remove(key)
        }

        val terms = listOf(TrackMatching.searchTitle(query.title)) + query.artists.take(1).map { TrackMatching.searchArtist(it) }
        val queryString = terms.filter { it.isNotBlank() }.joinToString(" ").trim()
        if (queryString.isBlank()) return null

        val url =
            baseUrl
                .toHttpUrl()
                .newBuilder()
                .addPathSegment("api")
                .addPathSegment("search")
                .addQueryParameter("q", queryString)
                .addQueryParameter("type", "track")
                .build()
        val request = baseRequest(url).get().build()

        val candidates =
            runCatching {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    // Cloudflare's "managed challenge" can return 200 OK with an HTML body
                    // (the interstitial page) — detect it regardless of status code so we
                    // don't waste time trying to parse HTML as JSON.
                    if (looksLikeCloudflare(body)) {
                        Timber
                            .tag(TAG)
                            .w(
                                "search blocked by Cloudflare (http=%d, cf-ray=%s, hasCfClearance=%b)",
                                response.code,
                                response.header("cf-ray"),
                                cfClearance.isNotEmpty(),
                            )
                        return@use emptyList<TrackMatching.Candidate>()
                    }
                    if (!response.isSuccessful) {
                        Timber.tag(TAG).d("search HTTP %d for \"%s\"", response.code, queryString)
                        return@use emptyList<TrackMatching.Candidate>()
                    }
                    parseSearchResults(body)
                }
            }.onFailure { Timber.tag(TAG).w(it, "search network failed") }
                .getOrDefault(emptyList())

        val winner = TrackMatching.best(
            TrackMatching.Target(
                title = query.title,
                artists = query.artists,
                album = query.album,
                durationMs = query.durationMs,
            ),
            candidates,
        )
        searchCache[key] = CachedSearch(winner, now + SEARCH_CACHE_MS)
        return winner
    }

    private fun parseSearchResults(body: String): List<TrackMatching.Candidate> {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("<")) {
            // Cloudflare interstitial HTML — not a JSON response.
            if (trimmed.contains("Just a moment", ignoreCase = true)) {
                Timber.tag(TAG).w("DabMusic gateway returned the Cloudflare interstitial; skipping source")
            }
            return emptyList()
        }
        val root =
            runCatching { JSONObject(trimmed) }
                .getOrElse {
                    // Some deployments return a bare array; tolerate it.
                    runCatching { JSONArray(trimmed) }.getOrNull()?.let { arr ->
                        return (0 until arr.length()).mapNotNull { i ->
                            arr.optJSONObject(i)?.toCandidate()
                        }
                    } ?: return emptyList()
                }
        // The official dabcli contract is { "tracks": [...] } for type=track. Tolerate the
        // alternates used by some mirrors for robustness.
        val data =
            root.optJSONArray("tracks")
                ?: root.optJSONArray("results")
                ?: root.optJSONArray("data")
                ?: root.optJSONArray("items")
                ?: JSONArray()
        return (0 until data.length()).mapNotNull { i ->
            data.optJSONObject(i)?.toCandidate()
        }
    }

    private fun JSONObject.toCandidate(): TrackMatching.Candidate? {
        // Per the official dabcli client, track objects have:
        //   id, title, artist (string), artistId, albumId, albumTitle, releaseDate
        val id = optString("id").ifBlank { optString("trackId") }.ifBlank { optString("track_id") }
        if (id.isBlank()) return null
        val title = optString("title").ifBlank { optString("name") }
        if (title.isBlank()) return null
        val artist =
            optString("artist").ifBlank {
                optJSONArray("artists")?.optString(0)?.ifBlank { null }
            }
        // DabMusic uses `albumTitle` (not `album`) for the album name. Fall back to `album` for
        // mirrors that use the conventional field name.
        val album = optString("albumTitle").ifBlank { optString("album") }.ifBlank { optJSONObject("album")?.optString("title") }
        val durationMs = optLong("durationMs", 0L).takeIf { it > 0 }
            ?: optLong("duration_ms", 0L).takeIf { it > 0 }
            ?: (optLong("duration", 0L).takeIf { it > 0 }?.times(1000L))
        return TrackMatching.Candidate(
            id = id,
            title = title,
            artists = listOfNotNull(artist),
            album = album?.takeIf { it.isNotBlank() },
            durationMs = durationMs,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Stream
    // ---------------------------------------------------------------------------------------------

    /**
     * Calls GET /api/stream?trackId=<id>&quality=<quality> and returns the direct stream URL
     * extracted from the `{"url": "..."}` response. Returns null on any failure.
     */
    private fun requestStream(
        trackId: String,
        quality: String,
    ): String? {
        val url =
            baseUrl
                .toHttpUrl()
                .newBuilder()
                .addPathSegment("api")
                .addPathSegment("stream")
                .addQueryParameter("trackId", trackId)
                .addQueryParameter("quality", quality)
                .build()
        val request = baseRequest(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            // Cloudflare's "managed challenge" can return 200 OK with an HTML body
            // (the interstitial page) — detect it regardless of status code.
            if (looksLikeCloudflare(body)) {
                Timber
                    .tag(TAG)
                    .w(
                        "stream blocked by Cloudflare (http=%d, cf-ray=%s, hasCfClearance=%b)",
                        response.code,
                        response.header("cf-ray"),
                        cfClearance.isNotEmpty(),
                    )
                return null
            }
            if (!response.isSuccessful) {
                Timber.tag(TAG).d("stream HTTP %d for track %s", response.code, trackId)
                return null
            }
            val trimmed = body.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("<")) {
                Timber.tag(TAG).w("stream returned non-JSON body (likely Cloudflare interstitial)")
                return null
            }
            val root =
                runCatching { JSONObject(trimmed) }.getOrNull()
                    ?: return null
            // The official contract is { "url": "..." }. Tolerate alternates for mirrors.
            val streamUrl = root.optString("url").ifBlank { root.optString("streamUrl") }.ifBlank { root.optString("link") }
            if (streamUrl.isBlank()) {
                Timber.tag(TAG).w("stream response had no url field: %s", trimmed.take(200))
                return null
            }
            return streamUrl
        }
    }

    private fun baseRequest(url: okhttp3.HttpUrl): Request.Builder {
        // Compose the Cookie header. Both cookies are name=value pairs joined with "; ".
        // cf_clearance is optional (only present after the user solves the WebView challenge);
        // session is required for all post-login endpoints.
        val cookieParts = buildList {
            if (sessionCookie.isNotEmpty()) add("session=$sessionCookie")
            if (cfClearance.isNotEmpty()) add("cf_clearance=$cfClearance")
        }
        // Referer/Origin must match the API host or Cloudflare's WAF will treat the request as
        // cross-origin and apply stricter heuristics. We use the configured baseUrl's scheme +
        // host + (non-default) port — path is left off because Cloudflare expects a page-level
        // Referer, not a deep API path.
        val origin =
            buildString {
                append(url.scheme)
                append("://")
                append(url.host)
                if (url.port != -1 &&
                    !((url.scheme == "https" && url.port == 443) || (url.scheme == "http" && url.port == 80))
                ) {
                    append(":")
                    append(url.port)
                }
            }
        return Request
            .Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Referer", "$origin/")
            .header("Origin", origin)
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")
            .apply {
                if (cookieParts.isNotEmpty()) {
                    header("Cookie", cookieParts.joinToString("; "))
                }
            }
    }

    /**
     * True when the response body looks like Cloudflare's "Just a moment..." interstitial.
     * Inspects the first 1KB of the body so drip-fed chunked responses (where OkHttp has read
     * part of the body before readTimeout fires) are still detected as Cloudflare rather than
     * treated as a malformed JSON response.
     */
    private fun looksLikeCloudflare(body: String): Boolean {
        if (body.isEmpty()) return false
        val sample = if (body.length > 1024) body.substring(0, 1024) else body
        return sample.contains("Just a moment", ignoreCase = true) ||
            sample.contains("cf-challenge", ignoreCase = true) ||
            sample.contains("cf-chl-bypass", ignoreCase = true) ||
            sample.contains("challenge-platform", ignoreCase = true) ||
            (sample.contains("<title>", ignoreCase = true) && sample.contains("cloudflare", ignoreCase = true)) ||
            (sample.trimStart().startsWith("<!DOCTYPE", ignoreCase = true) &&
                sample.contains("challenges.cloudflare.com", ignoreCase = true))
    }
}
