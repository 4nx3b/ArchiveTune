/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.db.entities.Song
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

object ListenBrainzManager {
    private val logTag = "ListenBrainzManager"
    private val started = AtomicBoolean(false)
    private val httpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

    private val _lastSubmitTime = MutableStateFlow<Long?>(null)
    val lastSubmitTimeFlow = _lastSubmitTime.asStateFlow()

    /** Non-null while the account itself is rejecting submissions (401 with
     *  an actionable reason, e.g. an unverified MetaBrainz email). The login
     *  screen surfaces this so the user knows WHAT to fix instead of watching
     *  every listen silently fail. Null again after a successful submit or a
     *  token change. */
    private val _authIssue = MutableStateFlow<String?>(null)
    val authIssueFlow = _authIssue.asStateFlow()

    /** Hard backoff after an auth rejection: submissions are skipped entirely
     *  (the account will keep rejecting them — the log used to fill with one
     *  401 per listen attempt, three playing_now per track change). Cleared
     *  when the token changes via [resetAuthState] (new login). */
    @Volatile
    private var authBackoffUntilMs: Long = 0L

    /** playing_now dedupe window: the service fires the submit from BOTH the
     *  timeline-change and the is-playing/transition event batches, which
     *  tripled identical payloads within milliseconds. */
    @Volatile
    private var lastPlayingNowKey: String? = null

    @Volatile
    private var lastPlayingNowAtMs: Long = 0L

    private val backoffActive: Boolean
        get() = System.currentTimeMillis() < authBackoffUntilMs

    /** Call on token change (new login / logout) — clears the auth issue and
     *  the backoff so the new credentials get a clean first attempt. */
    fun resetAuthState() {
        authBackoffUntilMs = 0L
        _authIssue.value = null
        lastPlayingNowKey = null
        lastPlayingNowAtMs = 0L
    }

    private fun extractArtistName(artist: Any): String {
        try {
            val getterNames = listOf("getName", "getArtistName", "name")
            for (methodName in getterNames) {
                try {
                    val method = artist.javaClass.getMethod(methodName)
                    val result = method.invoke(artist)
                    if (result is String && result.isNotBlank()) {
                        return result
                    }
                } catch (e: Exception) {
                }
            }

            val fieldNames = listOf("name", "artistName")
            for (fieldName in fieldNames) {
                try {
                    val field = artist.javaClass.getDeclaredField(fieldName)
                    field.isAccessible = true
                    val result = field.get(artist)
                    if (result is String && result.isNotBlank()) {
                        return result
                    }
                } catch (e: Exception) {
                }
            }

            val str = artist.toString()
            val namePattern = Regex("""name\s*=\s*([^,)\]]+)""")
            val match = namePattern.find(str)
            if (match != null) {
                val extractedName = match.groupValues[1].trim()
                if (extractedName.isNotBlank()) {
                    return extractedName
                }
            }

            return str
        } catch (e: Exception) {
            Timber.tag(logTag).w(e, "extractArtistName failed, using toString()")
            return artist.toString()
        }
    }

    suspend fun submitPlayingNow(
        context: Context,
        token: String,
        song: Song?,
        positionMs: Long,
    ): Boolean {
        if (token.isBlank()) return false
        if (song == null) return false
        // Skip while the account is hard-rejecting submissions (401 backoff).
        if (backoffActive) return false
        // Dedupe: the service's event fan-out fires this 2-3x per track change
        // with identical payloads. One playing_now per track (and at most one
        // re-assert per 30s) is all ListenBrainz models ask for.
        val nowMs = System.currentTimeMillis()
        val key = song.song.id
        if (key == lastPlayingNowKey && nowMs - lastPlayingNowAtMs < PLAYING_NOW_DEDUPE_MS) {
            return true
        }
        lastPlayingNowKey = key
        lastPlayingNowAtMs = nowMs
        return withContext(Dispatchers.IO) {
            try {
                val listenedAt = System.currentTimeMillis() / 1000L
                val duration = song.song.duration
                val durationMs = (duration * 1000).toLong()
                val artistNames =
                    song.artists
                        .map { artist -> extractArtistName(artist) }
                        .joinToString(" & ")
                val releaseName = song.album?.title ?: ""
                val releasePart = if (releaseName.isBlank()) "" else "\"release_name\":\"${escapeJson(releaseName)}\","
                val trackMetadata = "{\"track_metadata\":{\"artist_name\":\"${escapeJson(
                    artistNames,
                )}\",\"track_name\":\"${escapeJson(
                    song.title,
                )}\",${releasePart}\"additional_info\":{\"duration_ms\":$durationMs,\"position_ms\":$positionMs,\"submission_client\":\"ArchiveTune\"}}}"
                val listensJson = "[$trackMetadata]"
                val bodyJson = "{\"listen_type\":\"playing_now\",\"payload\":$listensJson}"
                Timber.tag(logTag).d("submitPlayingNow JSON: %s", bodyJson)
                val mediaType = "application/json".toMediaType()
                val body = bodyJson.toRequestBody(mediaType)
                val request =
                    Request
                        .Builder()
                        .url("https://api.listenbrainz.org/1/submit-listens")
                        .post(body)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Authorization", "Token $token")
                        .build()

                httpClient.newCall(request).execute().use { resp ->
                    val success = resp.isSuccessful
                    if (success) {
                        _lastSubmitTime.value = System.currentTimeMillis()
                        _authIssue.value = null
                        Timber.tag(logTag).d("playing_now submitted for %s", song.title)
                    } else {
                        val respBody =
                            try {
                                resp.body?.string() ?: ""
                            } catch (e: Exception) {
                                "<unable to read>"
                            }
                        handleAuthRejection(resp.code, respBody, "playing_now")
                    }
                    success
                }
            } catch (ex: Exception) {
                Timber.tag(logTag).e(ex, "submitPlayingNow failed")
                false
            }
        }
    }

    suspend fun submitFinished(
        context: Context,
        token: String,
        song: Song?,
        startMs: Long,
        endMs: Long,
    ): Boolean {
        if (token.isBlank()) return false
        if (song == null) return false
        if (backoffActive) return false
        return withContext(Dispatchers.IO) {
            try {
                val listenedAt = endMs / 1000L
                val duration = song.song.duration
                val durationMs = (duration * 1000).toLong()
                val artistNames =
                    song.artists
                        .map { artist -> extractArtistName(artist) }
                        .joinToString(" & ")
                val releaseName = song.album?.title ?: ""
                val releasePart = if (releaseName.isBlank()) "" else "\"release_name\":\"${escapeJson(releaseName)}\","
                var listenedAtStart = (startMs / 1000L)
                val MIN_LISTEN_TS = 1033430400L
                if (listenedAtStart < MIN_LISTEN_TS) {
                    Timber.tag(logTag).w("listened_at %s looks too small, replacing with current epoch seconds", listenedAtStart)
                    listenedAtStart = System.currentTimeMillis() / 1000L
                }
                val trackMetadataSingle = "{\"listened_at\":$listenedAtStart,\"track_metadata\":{\"artist_name\":\"${escapeJson(
                    artistNames,
                )}\",\"track_name\":\"${escapeJson(
                    song.title,
                )}\",${releasePart}\"additional_info\":{\"duration_ms\":$durationMs,\"start_ms\":$startMs,\"end_ms\":$endMs,\"submission_client\":\"ArchiveTune\"}}}"
                val listensJson = "[$trackMetadataSingle]"
                val bodyJson = "{\"listen_type\":\"single\",\"payload\":$listensJson}"
                Timber.tag(logTag).d("submitFinished JSON: %s", bodyJson)
                val mediaType = "application/json".toMediaType()
                val body = bodyJson.toRequestBody(mediaType)
                val request =
                    Request
                        .Builder()
                        .url("https://api.listenbrainz.org/1/submit-listens")
                        .post(body)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Authorization", "Token $token")
                        .build()

                httpClient.newCall(request).execute().use { resp ->
                    val success = resp.isSuccessful
                    if (success) {
                        _lastSubmitTime.value = System.currentTimeMillis()
                        _authIssue.value = null
                        Timber.tag(logTag).d("finished listen submitted for %s", song.title)
                    } else {
                        val respBody =
                            try {
                                resp.body?.string() ?: ""
                            } catch (e: Exception) {
                                "<unable to read>"
                            }
                        handleAuthRejection(resp.code, respBody, "finished listen")
                    }
                    success
                }
            } catch (ex: Exception) {
                Timber.tag(logTag).e(ex, "submitFinished failed")
                false
            }
        }
    }

    /** 401 means the ACCOUNT rejected us, not the network: the observed case
     *  is an unverified MetaBrainz email address. Every retry would fail the
     *  same way, so submissions back off hard for 6 hours and the actionable
     *  reason is published for the UI instead of filling the log with one 401
     *  per attempt. */
    private fun handleAuthRejection(code: Int, respBody: String, kind: String) {
        if (code == 401) {
            val message =
                when {
                    respBody.contains("verified email", ignoreCase = true) ->
                        "Verify your MetaBrainz email address — listens are being rejected until then."
                    else -> "ListenBrainz rejected the token (401) — check your token in settings."
                }
            if (!backoffActive || _authIssue.value != message) {
                Timber.tag(logTag).w(
                    "%s submit rejected (401) — pausing ListenBrainz submissions for %d minutes: %s",
                    kind,
                    AUTH_BACKOFF_MS / 60_000L,
                    message,
                )
            }
            authBackoffUntilMs = System.currentTimeMillis() + AUTH_BACKOFF_MS
            _authIssue.value = message
        } else {
            Timber.tag(logTag).w("%s submit failed: %s - %s", kind, code, respBody)
        }
    }

    private fun escapeJson(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private const val PLAYING_NOW_DEDUPE_MS = 30_000L
    private const val AUTH_BACKOFF_MS = 6L * 60L * 60L * 1000L

    fun isRunning(): Boolean = started.get()
}
