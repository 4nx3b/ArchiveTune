/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.settings

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.db.entities.Song
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    private val _authIssue = MutableStateFlow<String?>(null)
    val authIssueFlow = _authIssue.asStateFlow()

    @Volatile
    private var authBackoffUntilMs: Long = 0L

    @Volatile
    private var lastPlayingNowKey: String? = null

    @Volatile
    private var lastPlayingNowAtMs: Long = 0L

    private val backoffActive: Boolean
        get() = System.currentTimeMillis() < authBackoffUntilMs

    fun resetAuthState() {
        authBackoffUntilMs = 0L
        _authIssue.value = null
        lastPlayingNowKey = null
        lastPlayingNowAtMs = 0L
    }

    private fun extractArtistName(song: Song): String =
        song.artists
            .mapNotNull { it.name.takeIf(String::isNotBlank) }
            .joinToString(" & ")
            .ifBlank { "Unknown Artist" }

    private fun buildAdditionalInfo(
        durationMs: Long,
        extraFields: Map<String, Long>,
    ): JSONObject {
        val additionalInfo = JSONObject()
        if (durationMs > 0) {
            additionalInfo.put("duration_ms", durationMs)
        }
        extraFields.forEach { (key, value) ->
            if (value >= 0) {
                additionalInfo.put(key, value)
            }
        }
        additionalInfo.put("submission_client", "ArchiveTune")
        return additionalInfo
    }

    private fun buildTrackMetadata(
        song: Song,
        durationMs: Long,
        extraFields: Map<String, Long> = emptyMap(),
    ): JSONObject {
        val metadata = JSONObject()
        metadata.put("artist_name", extractArtistName(song))
        metadata.put("track_name", song.title)
        song.album?.title?.takeIf(String::isNotBlank)?.let {
            metadata.put("release_name", it)
        }
        metadata.put("additional_info", buildAdditionalInfo(durationMs, extraFields))
        return metadata
    }

    private fun buildRequestBody(
        listenType: String,
        payload: JSONArray,
    ): String {
        val body = JSONObject()
        body.put("listen_type", listenType)
        body.put("payload", payload)
        return body.toString()
    }

    suspend fun submitPlayingNow(
        context: Context,
        token: String,
        song: Song?,
        positionMs: Long,
    ): Boolean {
        if (token.isBlank()) return false
        if (song == null) return false

        if (backoffActive) return false

        val nowMs = System.currentTimeMillis()
        val key = song.song.id
        if (key == lastPlayingNowKey && nowMs - lastPlayingNowAtMs < PLAYING_NOW_DEDUPE_MS) {
            return true
        }
        lastPlayingNowKey = key
        lastPlayingNowAtMs = nowMs
        return withContext(Dispatchers.IO) {
            try {
                val durationMs = (song.song.duration * 1000.0).toLong()
                val trackMetadata =
                    buildTrackMetadata(
                        song = song,
                        durationMs = durationMs,
                        extraFields = mapOf("position_ms" to positionMs.coerceAtLeast(0L)),
                    )
                val payload = JSONArray().put(trackMetadata)
                val bodyJson = buildRequestBody(listenType = "playing_now", payload = payload)
                Timber.tag(logTag).d("submitPlayingNow JSON: %s", bodyJson)
                submit(token, bodyJson, "playing_now for ${song.title}")
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
                val durationMs = (song.song.duration * 1000.0).toLong()
                val MIN_LISTEN_TS = 1033430400L
                var listenedAtStart = (startMs / 1000L)
                if (listenedAtStart < MIN_LISTEN_TS) {
                    Timber.tag(logTag).w("listened_at %s looks too small, replacing with current epoch seconds", listenedAtStart)
                    listenedAtStart = System.currentTimeMillis() / 1000L
                }
                val listenEntry = JSONObject()
                listenEntry.put("listened_at", listenedAtStart)
                listenEntry.put(
                    "track_metadata",
                    buildTrackMetadata(
                        song = song,
                        durationMs = durationMs,
                        extraFields =
                            mapOf(
                                "start_ms" to startMs.coerceAtLeast(0L),
                                "end_ms" to endMs.coerceAtLeast(0L),
                            ),
                    ),
                )
                val payload = JSONArray().put(listenEntry)
                val bodyJson = buildRequestBody(listenType = "single", payload = payload)
                Timber.tag(logTag).d("submitFinished JSON: %s", bodyJson)
                submit(token, bodyJson, "finished listen for ${song.title}")
            } catch (ex: Exception) {
                Timber.tag(logTag).e(ex, "submitFinished failed")
                false
            }
        }
    }

    private suspend fun submit(
        token: String,
        bodyJson: String,
        description: String,
    ): Boolean {
        val trimmedToken = token.trim()
        if (trimmedToken.isBlank()) return false
        return withContext(Dispatchers.IO) {
            try {
                val mediaType = "application/json".toMediaType()
                val body = bodyJson.toRequestBody(mediaType)
                val request =
                    Request
                        .Builder()
                        .url("https://api.listenbrainz.org/1/submit-listens")
                        .post(body)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Authorization", "Token $trimmedToken")
                        .build()

                httpClient.newCall(request).execute().use { resp ->
                    val success = resp.isSuccessful
                    if (success) {
                        _lastSubmitTime.value = System.currentTimeMillis()
                        _authIssue.value = null
                        Timber.tag(logTag).d("%s submitted", description)
                    } else {
                        val respBody =
                            try {
                                resp.body?.string() ?: ""
                            } catch (e: Exception) {
                                "<unable to read>"
                            }
                        handleAuthRejection(resp.code, respBody, description)
                    }
                    success
                }
            } catch (ex: Exception) {
                Timber.tag(logTag).e(ex, "%s failed", description)
                false
            }
        }
    }

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

    private const val PLAYING_NOW_DEDUPE_MS = 30_000L
    private const val AUTH_BACKOFF_MS = 6L * 60L * 60L * 1000L

    fun isRunning(): Boolean = started.get()
}
