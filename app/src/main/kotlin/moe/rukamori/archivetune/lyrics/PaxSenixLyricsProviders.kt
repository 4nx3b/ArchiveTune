/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Adapted from BitChord (GPL-3.0) https://github.com/kushagrasinghx/BitChord
 * (shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/PaxSenix.kt) —
 * original re-implementation for ArchiveTune.
 */

package moe.rukamori.archivetune.lyrics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import moe.rukamori.archivetune.canvas.AppleMusicProvider
import moe.rukamori.archivetune.constants.EnablePaxsenixAppleMusicLyricsKey
import moe.rukamori.archivetune.constants.EnablePaxsenixMusixmatchLyricsKey
import moe.rukamori.archivetune.constants.EnablePaxsenixSpotifyLyricsKey
import moe.rukamori.archivetune.constants.PaxsenixApiKeyKey
import moe.rukamori.archivetune.constants.PaxsenixEndpointKey
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

object PaxSenixAppleMusicLyricsProvider : LyricsProvider {
    override val name = "PaxSenix (Apple Music)"

    override fun isEnabled(context: Context): Boolean {
        PaxSenixApi.refreshConfig(context)
        return context.dataStore[EnablePaxsenixAppleMusicLyricsKey] ?: true
    }

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> =
        runCatching {
            PaxSenixApi.appleMusicLyrics(title.forLyricsSearch(), artist.artistForLyricsSearch(), duration)
                ?: throw IllegalStateException("No PaxSenix Apple Music lyrics for $title — $artist")
        }
}

object PaxSenixSpotifyLyricsProvider : LyricsProvider {
    override val name = "PaxSenix (Spotify)"

    override fun isEnabled(context: Context): Boolean {
        PaxSenixApi.refreshConfig(context)
        return (context.dataStore[EnablePaxsenixSpotifyLyricsKey] ?: true) && PaxSenixApi.hasApiKey()
    }

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> =
        runCatching {
            PaxSenixApi.spotifyLyrics(title.forLyricsSearch(), artist.artistForLyricsSearch(), duration)
                ?: throw IllegalStateException("No PaxSenix Spotify lyrics for $title — $artist")
        }
}

object PaxSenixMusixmatchLyricsProvider : LyricsProvider {
    override val name = "PaxSenix (Musixmatch)"

    override fun isEnabled(context: Context): Boolean {
        PaxSenixApi.refreshConfig(context)
        return (context.dataStore[EnablePaxsenixMusixmatchLyricsKey] ?: true) && PaxSenixApi.hasApiKey()
    }

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> =
        runCatching {
            PaxSenixApi.musixmatchLyrics(title.forLyricsSearch(), artist.artistForLyricsSearch(), duration)
                ?: throw IllegalStateException("No PaxSenix Musixmatch lyrics for $title — $artist")
        }
}

internal object PaxSenixApi {
    private const val DEFAULT_API = "https://api.paxsenix.org"
    private const val PUBLIC_PROXY = "https://lyrics.paxsenix.org"
    private const val APPLE_SEARCH = "https://amp-api.music.apple.com/v1/catalog/us/search"
    private const val MINIMUM_MATCH_SCORE = 10

    @Volatile
    private var apiKey: String = ""

    @Volatile
    private var apiEndpoint: String = DEFAULT_API

    private val tokenMutex = Mutex()
    private val cachedAppleToken = AtomicReference<String?>(null)

    fun refreshConfig(context: Context) {
        val key = context.dataStore[PaxsenixApiKeyKey]?.trim().orEmpty()
        apiKey = normalizeApiKey(key)
        val custom = context.dataStore[PaxsenixEndpointKey]?.trim()?.takeIf { it.startsWith("http") }
        apiEndpoint = (custom ?: DEFAULT_API).trimEnd('/')
    }

    fun hasApiKey(): Boolean = apiKey.isNotBlank()

    private fun normalizeApiKey(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.startsWith("Bearer ", ignoreCase = true)) {
            trimmed.substringAfter(' ').trim()
        } else {
            trimmed
        }
    }

    suspend fun appleMusicLyrics(
        title: String,
        artist: String,
        durationSec: Int,
    ): String? =
        withContext(Dispatchers.IO) {
            val trackId = searchPublicAppleTrackId(title, artist, durationSec)
                ?: return@withContext null
            val url = "$PUBLIC_PROXY/apple-music/lyrics?id=$trackId&ttml=true"
            LyricsProviderHttp.get(url)?.let(LyricsPayload::toLyrics)
        }

    suspend fun spotifyLyrics(
        title: String,
        artist: String,
        durationSec: Int,
    ): String? =
        withContext(Dispatchers.IO) {
            val id = searchTrackId("spotify/search", title, artist, durationSec)
            id?.let { trackId ->
                authorizedGet("$apiEndpoint/lyrics/spotify?id=$trackId")?.let(LyricsPayload::toLyrics)
            } ?: genericAuthenticatedLyrics(title, artist, durationSec)
        }

    suspend fun musixmatchLyrics(
        title: String,
        artist: String,
        durationSec: Int,
    ): String? =
        withContext(Dispatchers.IO) {
            val url =
                "$apiEndpoint/lyrics/musixmatch" +
                    "?t=${LyricsProviderHttp.encode(title)}" +
                    "&a=${LyricsProviderHttp.encode(artist)}" +
                    "&d=$durationSec"
            authorizedGet(url)?.let(LyricsPayload::toLyrics)
                ?: genericAuthenticatedLyrics(title, artist, durationSec)
        }

    private suspend fun searchPublicAppleTrackId(
        title: String,
        artist: String,
        durationSec: Int,
    ): String? {
        val token = appleToken() ?: return null
        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (query.isEmpty()) return null
        val url =
            "$APPLE_SEARCH?term=${LyricsProviderHttp.encode(query)}" +
                "&types=songs&limit=10&l=en-US"
        val body =
            LyricsProviderHttp.get(
                url,
                mapOf(
                    "Authorization" to "Bearer $token",
                    "Accept" to "application/json",

                    "Origin" to "https://music.apple.com",
                    "Referer" to "https://music.apple.com/",
                ),
            ) ?: return null
        val root = LyricsProviderHttp.parseJson(body) ?: return null
        return bestCandidate(root, title, artist, durationSec)?.id
    }

    private suspend fun appleToken(): String? {

        AppleMusicProvider.devTokenProvider?.invoke()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return cachedAppleToken.get() ?: tokenMutex.withLock {
            cachedAppleToken.get() ?: scrapeAppleToken()?.also { cachedAppleToken.set(it) }
        }
    }

    private suspend fun scrapeAppleToken(): String? {
        val page = LyricsProviderHttp.get("https://music.apple.com/us/new") ?: return null
        val scriptPath = APPLE_INDEX_SCRIPT.find(page)?.value ?: return null
        val script = LyricsProviderHttp.get("https://music.apple.com$scriptPath") ?: return null
        return APPLE_TOKEN.find(script)?.value
    }

    private suspend fun searchTrackId(
        path: String,
        title: String,
        artist: String,
        durationSec: Int,
    ): String? {
        val query = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (query.isEmpty()) return null
        val body = authorizedGet("$apiEndpoint/$path?q=${LyricsProviderHttp.encode(query)}") ?: return null
        val root = LyricsProviderHttp.parseJson(body) ?: return null
        return bestCandidate(root, title, artist, durationSec)?.id
    }

    private suspend fun genericAuthenticatedLyrics(
        title: String,
        artist: String,
        durationSec: Int,
    ): String? {
        val query = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (query.isEmpty()) return null
        val body = authorizedGet("$apiEndpoint/lyrics/lrcget?q=${LyricsProviderHttp.encode(query)}")
            ?: return null
        val root = LyricsProviderHttp.parseJson(body).asObject() ?: return LyricsPayload.toLyrics(body)
        val documents = root.array("lyrics") ?: return LyricsPayload.toLyrics(body)
        if (documents.isEmpty()) return LyricsPayload.toLyrics(body)

        val wantedMs = durationSec * 1000L
        val scored =
            documents.mapNotNull { element ->
                val text = LyricsPayload.toLyrics(element.toString()) ?: return@mapNotNull null
                val metadataScore = element.asObject()?.lyricCandidateScore(title, artist, wantedMs) ?: 0
                ScoredDocument(text, metadataScore, durationDistanceMs(text, wantedMs))
            }
        return scored.maxWithOrNull(
            compareBy<ScoredDocument> { it.metadataScore }
                .thenBy { -it.durationDistanceMs }
                .thenBy { LyricsUtils.hasWordSyncedLyrics(it.text) },
        )?.text
    }

    private suspend fun authorizedGet(url: String): String? {
        val key = apiKey.takeIf(String::isNotBlank) ?: return null
        return LyricsProviderHttp.get(
            url,
            mapOf(
                "Authorization" to "Bearer $key",
                "Accept" to "application/json, text/plain, */*",
            ),
        )
    }

    private fun bestCandidate(
        root: JsonElement,
        title: String,
        artist: String,
        durationSec: Int,
    ): Candidate? {
        val candidates = buildList { root.collectCandidates(this) }
        val wantedMs = durationSec * 1000L
        return candidates
            .map { it to it.score(title, artist, wantedMs) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= MINIMUM_MATCH_SCORE }
            ?.first
    }

    private fun JsonElement.collectCandidates(into: MutableList<Candidate>) {
        when (this) {
            is JsonArray -> forEach { it.collectCandidates(into) }
            is JsonObject -> {
                toCandidate()?.let(into::add)
                values.forEach { it.collectCandidates(into) }
            }
            else -> Unit
        }
    }

    private fun JsonObject.toCandidate(): Candidate? {
        val details = this["attributes"].asObject() ?: this
        val id = firstString(ID_KEYS) ?: details.firstString(ID_KEYS) ?: return null
        val title = details.firstString(TITLE_KEYS) ?: return null
        val artist = details.firstString(ARTIST_KEYS) ?: details.artistNames().orEmpty()
        return Candidate(id, title, artist, details.firstLong(DURATION_KEYS).toDurationMs())
    }

    private fun JsonObject.artistNames(): String? =
        when (val artists = this["artists"] ?: this["artist"]) {
            is JsonPrimitive -> artists.contentOrNull
            is JsonObject -> artists.firstString(listOf("name", "artistName", "title"))
            is JsonArray ->
                artists.mapNotNull {
                    when (it) {
                        is JsonPrimitive -> it.contentOrNull
                        is JsonObject -> it.firstString(listOf("name", "artistName", "title"))
                        else -> null
                    }
                }.joinToString(", ").takeIf(String::isNotEmpty)
            else -> null
        }

    private fun JsonObject.firstString(keys: List<String>): String? =
        keys.firstNotNullOfOrNull { string(it) }

    private fun JsonObject.firstLong(keys: List<String>): Long? =
        keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.longOrNull }

    private fun JsonObject.lyricCandidateScore(
        title: String,
        artist: String,
        wantedMs: Long,
    ): Int {
        val details = this["attributes"].asObject() ?: this
        val candidate =
            Candidate(
                id = firstString(ID_KEYS) ?: "",
                title = details.firstString(TITLE_KEYS).orEmpty(),
                artist = details.firstString(ARTIST_KEYS) ?: details.artistNames().orEmpty(),
                durationMs = details.firstLong(DURATION_KEYS).toDurationMs(),
            )
        return candidate.score(title, artist, wantedMs)
    }

    private fun Long?.toDurationMs(): Long =
        when {
            this == null || this <= 0L -> 0L
            this < 10_000L -> this * 1000L
            else -> this
        }

    private fun Candidate.score(
        wantedTitle: String,
        wantedArtist: String,
        wantedDurationMs: Long,
    ): Int {
        var score = textScore(title, wantedTitle, 20, 10) + textScore(artist, wantedArtist, 15, 5)
        if (wantedDurationMs > 0L && durationMs > 0L) {
            score +=
                when {
                    abs(durationMs - wantedDurationMs) < 3_000L -> 10
                    abs(durationMs - wantedDurationMs) < 10_000L -> 5
                    else -> 0
                }
        }
        return score
    }

    private fun textScore(
        candidate: String,
        wanted: String,
        exact: Int,
        partial: Int,
    ): Int =
        when {
            candidate.isBlank() || wanted.isBlank() -> 0
            candidate.equals(wanted, ignoreCase = true) -> exact
            candidate.contains(wanted, ignoreCase = true) || wanted.contains(candidate, ignoreCase = true) -> partial
            else -> 0
        }

    private fun durationDistanceMs(
        text: String,
        wantedMs: Long,
    ): Long {
        if (wantedMs <= 0L) return 0L
        val last = lastTimestampMs(text) ?: return Long.MAX_VALUE
        return abs(last - wantedMs)
    }

    private fun lastTimestampMs(text: String): Long? {
        var last = 0L
        LRC_STAMP.findAll(text).forEach { match ->
            val ms =
                (match.groupValues[1].toLongOrNull() ?: 0L) * 60_000L +
                    (match.groupValues[2].toLongOrNull() ?: 0L) * 1_000L +
                    fractionMs(match.groupValues[3])
            if (ms > last) last = ms
        }
        TTML_BEGIN.findAll(text).forEach { match ->
            val seconds = match.groupValues[1].toDoubleOrNull() ?: 0.0
            val ms = (seconds * 1000.0).toLong()
            if (ms > last) last = ms
        }
        return last.takeIf { it > 0L }
    }

    private fun fractionMs(fraction: String): Long =
        when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLongOrNull()?.times(100L) ?: 0L
            2 -> fraction.toLongOrNull()?.times(10L) ?: 0L
            else -> fraction.take(3).toLongOrNull() ?: 0L
        }

    private data class Candidate(
        val id: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
    )

    private data class ScoredDocument(
        val text: String,
        val metadataScore: Int,
        val durationDistanceMs: Long,
    )

    private val ID_KEYS = listOf("id", "trackId", "track_id", "realId")
    private val TITLE_KEYS = listOf("name", "title", "trackName", "track_name")
    private val ARTIST_KEYS = listOf("artistName", "artist_name")
    private val DURATION_KEYS = listOf("durationInMillis", "durationMs", "duration_ms", "duration")

    private val APPLE_INDEX_SCRIPT = Regex("""/assets/index~[^\"]+\.js""")
    private val APPLE_TOKEN = Regex("""eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")

    private val LRC_STAMP = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val TTML_BEGIN = Regex("""begin="(\d+(?:\.\d+)?)"\s""")
}
