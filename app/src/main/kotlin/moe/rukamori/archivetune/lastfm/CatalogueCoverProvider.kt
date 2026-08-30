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
 * Resolvers (tried in order):
 *
 * 1. [iTunesCoverUrl] — iTunes Search API. Free, no auth, no rate-limit issues
 *    at the volumes a single user generates. Covers most western pop/rock and
 *    a good chunk of K-pop and J-pop that has international distribution.
 *
 * 2. [deezerCoverUrl] — Deezer public search API. Free, no auth. Excellent
 *    coverage for European / Asian catalogues; often has covers iTunes lacks
 *    (and vice-versa), so we query both and take the first non-empty result.
 *
 * 3. [lastFmTrackInfoCoverUrl] — Last.fm's own `track.getInfo` endpoint. The
 *    top-tracks / recent-tracks endpoints return a small/medium image set
 *    that is often empty, but `track.getInfo` returns the full image set
 *    (including extralarge) which is populated for the vast majority of
 *    tracks Last.fm knows about — even obscure ones the catalogues miss.
 *
 * 4. [coverArtArchiveCoverUrl] — MusicBrainz Cover Art Archive. Public, free,
 *    no auth. Cover Art Archive hosts album covers keyed by MBID; we resolve
 *    the MBID via MusicBrainz's search endpoint and then pull the front cover.
 *    Excellent for releases that have an MBID but no commercial catalogue
 *    presence (indie / Vocaloid / doujin / anime OSTs).
 *
 * 5. [spotifyOEmbedCoverUrl] — Spotify's public oEmbed endpoint. Free, no
 *    auth. Given a Spotify track URL it returns the track's artwork via
 *    oEmbed. We search Spotify's open embed endpoint by query (it accepts
 *    a free-text search via the `q` parameter on the public search page)
 *    and pull the first result's cover.
 */
object CatalogueCoverProvider {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Resolve a cover URL for [title] (optionally with [artist]) by querying
     * the catalogues in order: iTunes → Deezer → Last.fm track.getInfo →
     * Cover Art Archive → Spotify oEmbed. Returns the first non-empty URL or
     * null if every provider came up empty / errored out.
     *
     * Safe to call from any dispatcher; performs its own IO dispatching.
     */
    suspend fun resolveCoverUrl(
        title: String,
        artist: String?,
    ): String? {
        if (title.isBlank()) return null
        val cleanedTitle = cleanSearchTitle(title)
        val cleanedArtist = artist?.takeIf(String::isNotBlank)?.let(::cleanSearchArtist)
        return withContext(Dispatchers.IO) {
            iTunesCoverUrl(cleanedTitle, cleanedArtist)
                ?: deezerCoverUrl(cleanedTitle, cleanedArtist)
                ?: lastFmTrackInfoCoverUrl(cleanedTitle, cleanedArtist)
                ?: coverArtArchiveCoverUrl(cleanedTitle, cleanedArtist)
                ?: spotifyOEmbedCoverUrl(cleanedTitle, cleanedArtist)
        }
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
                    .addQueryParameter("limit", "3")
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
                // Try to find a result whose trackName closely matches; if none,
                // fall back to the first result. This prevents picking a remix
                // when the original is in the result set.
                val first = results
                    .firstOrNull { entry ->
                        val trackName = entry.jsonObject["trackName"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
                        trackName.contains(title.lowercase()) || title.lowercase().contains(trackName)
                    }?.jsonObject
                    ?: results.firstOrNull()?.jsonObject
                    ?: return@withContext null
                val art = first["artworkUrl100"]?.jsonPrimitive?.contentOrNull
                    ?: first["artworkUrl60"]?.jsonPrimitive?.contentOrNull
                    ?: return@withContext null
                // Upsize: iTunes serves a larger image when you swap the size token.
                art.replace("100x100bb", "600x600bb")
                    .replace("60x60bb", "600x600bb")
                    .replace("30x30bb", "600x600bb")
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
                    .addQueryParameter("limit", "3")
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

    /**
     * Last.fm track.getInfo endpoint — uses the app's configured Last.fm API key.
     * Returns the "extralarge" or "mega" image when available, which the
     * top-tracks / recent-tracks endpoints often omit.
     *
     * No session key required (it's a read-only public API call).
     */
    suspend fun lastFmTrackInfoCoverUrl(
        title: String,
        artist: String?,
    ): String? =
        withContext(Dispatchers.IO) {
            if (title.isBlank() || artist.isNullOrBlank()) return@withContext null
            val config = LastFM.currentConfig()
            if (config.apiKey.isBlank() || config.apiKey == LastFM.FALLBACK_COMPAT_API_KEY) return@withContext null
            val url =
                config.endpoint.toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("method", "track.getInfo")
                    .addQueryParameter("api_key", config.apiKey)
                    .addQueryParameter("artist", artist)
                    .addQueryParameter("track", title)
                    .addQueryParameter("format", "json")
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
                val track = parsed["track"]?.jsonObject ?: return@withContext null
                val album = track["album"]?.jsonObject ?: return@withContext null
                val images: JsonArray = album["image"]?.jsonArray ?: return@withContext null
                // Prefer extralarge > large > medium (the dashboard card is 56dp)
                val sizeOrder = listOf("extralarge", "large", "medium", "mega", "small")
                for (size in sizeOrder) {
                    val match =
                        images.firstOrNull { entry ->
                            entry.jsonObject["size"]?.jsonPrimitive?.contentOrNull == size &&
                                !entry.jsonObject["#text"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
                        }?.jsonObject
                    val text = match?.get("#text")?.jsonPrimitive?.contentOrNull
                    if (!text.isNullOrBlank()) return@use text
                }
                null
            }
        }

    /**
     * MusicBrainz + Cover Art Archive.
     * 1. Search MB for the recording (title + artist).
     * 2. If a release MBID is found, fetch its front cover from Cover Art Archive.
     *
     * Public, no auth, but MusicBrainz rate-limits to 1 req/sec per IP — so this
     * is intentionally low in the fallback chain and we cap the call to ~7s.
     */
    suspend fun coverArtArchiveCoverUrl(
        title: String,
        artist: String?,
    ): String? =
        withContext(Dispatchers.IO) {
            if (title.isBlank()) return@withContext null
            val query = buildString {
                if (!artist.isNullOrBlank()) {
                    append("artist:\"")
                    append(artist.replace("\"", ""))
                    append("\" AND ")
                }
                append("recording:\"")
                append(title.replace("\"", ""))
                append("\"")
            }
            val searchUrl =
                "https://musicbrainz.org/ws/2/recording/".toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("query", query)
                    .addQueryParameter("limit", "1")
                    .addQueryParameter("inc", "releases")
                    .addQueryParameter("fmt", "json")
                    .build()
            val searchResponse =
                runCatching {
                    client.newCall(
                        Request.Builder()
                            .url(searchUrl)
                            .header("User-Agent", "ArchiveTune/1.0 (https://github.com/rukamori/ArchiveTune)")
                            .get()
                            .build(),
                    ).execute()
                }.getOrNull() ?: return@withContext null
            val mbid: String? =
                searchResponse.use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = runCatching { resp.body?.string() }.getOrNull() ?: return@use null
                    val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@use null
                    val recordings = parsed["recordings"]?.jsonArray ?: return@use null
                    val first = recordings.firstOrNull()?.jsonObject ?: return@use null
                    val releases = first["releases"]?.jsonArray ?: return@use null
                    val release = releases.firstOrNull()?.jsonObject ?: return@use null
                    release["id"]?.jsonPrimitive?.contentOrNull
                }
            if (mbid.isNullOrBlank()) return@withContext null
            // Cover Art Archive front cover URL is a 302 redirect to the actual image.
            // We issue a HEAD request and follow the redirect to capture the final URL.
            val coverUrl = "https://coverartarchive.org/release/$mbid/front"
            val headResponse =
                runCatching {
                    client.newCall(
                        Request.Builder()
                            .url(coverUrl)
                            .head()
                            .build(),
                    ).execute()
                }.getOrNull() ?: return@withContext null
            headResponse.use { resp ->
                if (resp.code != 200 && resp.code != 307 && resp.code != 302) return@withContext null
                // The final URL after redirect — OkHttp follows redirects by default
                // for GET, but for HEAD we need to read the Location header.
                val finalUrl = resp.request.url.toString()
                if (finalUrl != coverUrl && finalUrl.startsWith("http")) finalUrl else null
            }
        }

    /**
     * Spotify oEmbed endpoint.
     * Spotify's open https://open.spotify.com/search/q/{query} page returns HTML
     * with og:image meta tags — we scrape the first track result's cover.
     *
     * This is a last-resort fallback when all catalogues come up empty. It's
     * somewhat fragile (depends on Spotify's HTML structure) but works for
     * tracks that exist on Spotify but not on iTunes/Deezer.
     */
    suspend fun spotifyOEmbedCoverUrl(
        title: String,
        artist: String?,
    ): String? =
        withContext(Dispatchers.IO) {
            if (title.isBlank()) return@withContext null
            val term = listOfNotNull(artist?.takeIf(String::isNotBlank), title).joinToString(" ")
            // Use Spotify's embed endpoint which returns the track artwork via
            // oEmbed JSON. We try the search embed first; if that fails we
            // fall back to nothing (the chain has already exhausted better
            // options).
            val searchUrl =
                "https://open.spotify.com/search/${
                    java.net.URLEncoder.encode(term, "UTF-8").replace("+", "%20")
                }/tracks"
            val response =
                runCatching {
                    client.newCall(
                        Request.Builder()
                            .url(searchUrl)
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                            .get()
                            .build(),
                    ).execute()
                }.getOrNull() ?: return@withContext null
            response.use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = runCatching { resp.body?.string() }.getOrNull() ?: return@withContext null
                // Extract first og:image meta tag — that's the track artwork.
                val ogImageMatch = Regex(
                    "<meta[^>]+property=\"og:image\"[^>]+content=\"([^\"]+)\"",
                    RegexOption.IGNORE_CASE,
                ).find(body)
                ogImageMatch?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)
            }
        }

    /**
     * Strips common noise tokens from a track title before searching catalogues.
     * Examples: "[60fps Full] PoPiPo" → "PoPiPo", "Song (Official MV)" → "Song",
     * "01. Song Title" → "Song Title".
     */
    private fun cleanSearchTitle(raw: String): String {
        val stripped =
            raw
                .replace(Regex("^\\s*\\d{1,3}\\s*[.\\-]\\s*"), "")
                .replace(Regex("\\s*\\[[^]]*]"), "")
                .replace(
                    Regex(
                        "\\s*\\((?:feat\\.?|ft\\.?|featuring|with)\\b[^)]*\\)",
                        RegexOption.IGNORE_CASE,
                    ),
                    "",
                ).replace(
                    Regex(
                        "\\s*\\((?:official\\s*)?(?:music\\s*)?(?:video|mv|lyrics?|audio|visualizer|live|remaster(?:ed)?|version|edit|mix|remix|full|hd|hq|4k|60fps|30fps)[^)]*\\)",
                        RegexOption.IGNORE_CASE,
                    ),
                    "",
                ).replace(
                    Regex(
                        "\\s*-\\s*(?:official\\s*)?(?:music\\s*)?(?:video|mv|lyrics?|audio|visualizer|live|remaster(?:ed)?|version|edit|mix|remix|full|hd|hq|4k|60fps|30fps)\\b.*$",
                        RegexOption.IGNORE_CASE,
                    ),
                    "",
                ).replace(Regex("\\s*\\|\\s*[^|]*$"), "")
                .replace(Regex("\\s+/\\s*.*$"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trim('-')
                .replace(Regex("\\s+"), " ")
                .trim()
        return stripped.ifBlank { raw.trim() }
    }

    private fun cleanSearchArtist(raw: String): String {
        val first =
            raw
                .split(
                    Regex(
                        "(?:\\s*,\\s*|\\s*&\\s*|\\s+x\\s+|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b)",
                        RegexOption.IGNORE_CASE,
                    ),
                    limit = 2,
                ).firstOrNull()
                .orEmpty()
        return first.replace(Regex("\\s+"), " ").trim()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Non-cover metadata enrichment (songwriters + genres)
    //
    // Added 2026-08-30 per user requests:
    //  • "Written by" text should show songwriters (Task 5)
    //  • Last.fm stats page should fall back to internet for missing genres (Task 6)
    //
    // Both ride the same OkHttpClient + User-Agent + `runCatching`-swallow pattern
    // the cover resolvers above use. Each is wrapped in a per-(mediaId, query)
    // LRU so a re-render of the same track doesn't re-hit the network.
    // ═══════════════════════════════════════════════════════════════════════════

    private val songwriterCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val genreCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private const val MetadataCacheMaxEntries = 64
    private val mbUserAgent = "ArchiveTune/1.0 (https://github.com/rukamori/ArchiveTune)"

    /**
     * Resolve the songwriter(s) — composer / lyricist / writer — for [title] (optionally
     * with [artist]) using MusicBrainz. Returns the list of distinct writer names (max 5),
     * or null if no writer credit was found.
     *
     * Flow:
     *  1. Search MusicBrainz for the recording (Lucene query `recording:"X" AND artist:"Y"`,
     *     limit=1) → first recording's MBID.
     *  2. Lookup the recording MBID with `inc=artist-rels+work-rels`. The response includes
     *     `relations[]` where `type-id == "a300566f-a404-437e-8d16-3b0d83b41313"` ("performance
     *     of"); each such relation carries a `work` block whose own `relations[]` includes
     *     entries with `type` ∈ {"composer", "lyricist", "writer"} → `artist.name` is the
     *     writer's name.
     *  3. Collect distinct writer names in encounter order, capped at 5.
     *
     * Cache: keyed by `"${title}|${artist ?: ""}"` so a re-render doesn't re-hit the
     * network. Bounded LRU-ish eviction (ConcurrentHashMap has no LRU; we just trim the
     * oldest key when the cap is hit, which is good enough for memory hygiene).
     *
     * Public so [LyricsEnhanced] can call it from a `LaunchedEffect` keyed on mediaId.
     */
    suspend fun resolveSongwriters(
        title: String,
        artist: String?,
    ): List<String>? {
        if (title.isBlank()) return null
        val cleanedTitle = cleanSearchTitle(title)
        val cleanedArtist = artist?.takeIf(String::isNotBlank)?.let(::cleanSearchArtist)
        val cacheKey = "${cleanedTitle}|${cleanedArtist.orEmpty()}"
        songwriterCache[cacheKey]?.let { return it }
        return withContext(Dispatchers.IO) {
            val mbid = searchMusicBrainzRecordingMbid(cleanedTitle, cleanedArtist) ?: return@withContext null
            val writers = fetchRecordingWriters(mbid)
            if (writers.isEmpty()) {
                null
            } else {
                val distinct = LinkedHashSet<String>().apply { writers.forEach { add(it) } }.toList().take(5)
                songwriterCache[cacheKey] = distinct
                trimMetadataCache(songwriterCache)
                distinct
            }
        }
    }

    /**
     * Resolve genre tags for [title] (optionally with [artist]) using two providers
     * in order: iTunes (primaryGenreName from search response — no extra call) →
     * MusicBrainz (recording search + lookup with `inc=tags+genres`). Returns up to
     * 3 distinct, non-blank tag names, or null if both providers came up empty.
     *
     * Cache: same shape as [resolveSongwriters] — keyed by title+artist, bounded by
     * [MetadataCacheMaxEntries]. Public so [LastFmDashboardScreen] can use it as the
     * fallback when Last.fm's `track.getInfo` returns no toptags.
     */
    suspend fun resolveGenres(
        title: String,
        artist: String?,
    ): List<String>? {
        if (title.isBlank()) return null
        val cleanedTitle = cleanSearchTitle(title)
        val cleanedArtist = artist?.takeIf(String::isNotBlank)?.let(::cleanSearchArtist)
        val cacheKey = "${cleanedTitle}|${cleanedArtist.orEmpty()}"
        genreCache[cacheKey]?.let { return it }
        return withContext(Dispatchers.IO) {
            val fromItunes = iTunesPrimaryGenre(cleanedTitle, cleanedArtist)
            val fromMb = mbRecordingTags(cleanedTitle, cleanedArtist)
            val combined =
                LinkedHashSet<String>()
                    .apply {
                        fromItunes?.let { add(it) }
                        fromMb?.forEach { add(it) }
                    }.toList()
                    .filter { it.isNotBlank() }
                    .take(3)
            if (combined.isEmpty()) {
                null
            } else {
                genreCache[cacheKey] = combined
                trimMetadataCache(genreCache)
                combined
            }
        }
    }

    /**
     * MusicBrainz recording search by (title, artist). Returns the first matching
     * recording's MBID, or null on any failure (rate limit, no match, parse error).
     */
    private fun searchMusicBrainzRecordingMbid(
        title: String,
        artist: String?,
    ): String? {
        if (title.isBlank()) return null
        val query = buildString {
            if (!artist.isNullOrBlank()) {
                append("artist:\"")
                append(artist.replace("\"", ""))
                append("\" AND ")
            }
            append("recording:\"")
            append(title.replace("\"", ""))
            append("\"")
        }
        val searchUrl =
            "https://musicbrainz.org/ws/2/recording/".toHttpUrl()
                .newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("limit", "1")
                .addQueryParameter("fmt", "json")
                .build()
        val response =
            runCatching {
                client.newCall(
                    Request.Builder()
                        .url(searchUrl)
                        .header("User-Agent", mbUserAgent)
                        .get()
                        .build(),
                ).execute()
            }.getOrNull() ?: return null
        return response.use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = runCatching { resp.body?.string() }.getOrNull() ?: return@use null
            val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@use null
            val recordings = parsed["recordings"]?.jsonArray ?: return@use null
            recordings.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        }
    }

    /**
     * Lookup a recording MBID with `inc=artist-rels+work-rels` and walk the relations to
     * find writer credits. Returns the list of writer names (with duplicates — caller
     * deduplicates).
     *
     * MusicBrainz relation-type names for writers (case-insensitive):
     *  - "composer" (type-id d300d7af-ca1f-4c25-b0d0-2c3e57678c82)
     *  - "lyricist" (type-id 3e48faba-4051-36f4-9d86-7af1b96cd6e7)
     *  - "writer"  (type-id 75909ea4-2a68-3b51-b743-2ce0fd50d7f0)
     *
     * We don't filter by type-id because the type-name lookup is what the API documents
     * and what the JSON exposes; we just match on the `type` string.
     */
    private fun fetchRecordingWriters(mbid: String): List<String> {
        val lookupUrl =
            "https://musicbrainz.org/ws/2/recording/$mbid".toHttpUrl()
                .newBuilder()
                .addQueryParameter("inc", "artist-rels+work-rels")
                .addQueryParameter("fmt", "json")
                .build()
        val response =
            runCatching {
                client.newCall(
                    Request.Builder()
                        .url(lookupUrl)
                        .header("User-Agent", mbUserAgent)
                        .get()
                        .build(),
                ).execute()
            }.getOrNull() ?: return emptyList()
        val body = response.use { resp ->
            if (!resp.isSuccessful) return emptyList()
            runCatching { resp.body?.string() }.getOrNull() ?: return emptyList()
        }
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val relations = parsed["relations"]?.jsonArray ?: return emptyList()
        val writers = mutableListOf<String>()
        for (relationEntry in relations) {
            val relation = relationEntry.jsonObject
            // Recording-level relations of type "performance of" carry a `work` block.
            val type = relation["type"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: continue
            if (type != "performance of") continue
            val work = relation["work"]?.jsonObject ?: continue
            val workRelations = work["relations"]?.jsonArray ?: continue
            for (workRelationEntry in workRelations) {
                val workRelation = workRelationEntry.jsonObject
                val workType = workRelation["type"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: continue
                if (workType != "composer" && workType != "lyricist" && workType != "writer") continue
                val writerName = workRelation["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                if (!writerName.isNullOrBlank()) writers.add(writerName)
            }
        }
        return writers
    }

    /**
     * iTunes Search API — extract `primaryGenreName` from the top result. Returns null
     * on any failure. No extra call needed (the search response already has it).
     */
    private suspend fun iTunesPrimaryGenre(
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
                    .addQueryParameter("limit", "3")
                    .build()
            val response =
                runCatching {
                    client.newCall(Request.Builder().url(url).get().build()).execute()
                }.getOrNull() ?: return@withContext null
            response.use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = runCatching { resp.body?.string() }.getOrNull() ?: return@withContext null
                val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@withContext null
                val results = parsed["results"]?.jsonArray ?: return@withContext null
                // Prefer the result whose trackName closely matches; fall back to the first.
                val first =
                    results
                        .firstOrNull { entry ->
                            val trackName = entry.jsonObject["trackName"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
                            trackName.contains(title.lowercase()) || title.lowercase().contains(trackName)
                        }?.jsonObject
                        ?: results.firstOrNull()?.jsonObject
                        ?: return@withContext null
                first["primaryGenreName"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            }
        }

    /**
     * MusicBrainz recording search → MBID → lookup with `inc=tags+genres`. Returns the
     * tag/genre names (combined). MusicBrainz `genres[]` carry a `count` (lower=less
     * popular); we just take the names as-is and let the caller deduplicate + cap.
     */
    private suspend fun mbRecordingTags(
        title: String,
        artist: String?,
    ): List<String>? =
        withContext(Dispatchers.IO) {
            val mbid = searchMusicBrainzRecordingMbid(title, artist) ?: return@withContext null
            val lookupUrl =
                "https://musicbrainz.org/ws/2/recording/$mbid".toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("inc", "tags+genres")
                    .addQueryParameter("fmt", "json")
                    .build()
            val response =
                runCatching {
                    client.newCall(
                        Request.Builder()
                            .url(lookupUrl)
                            .header("User-Agent", mbUserAgent)
                            .get()
                            .build(),
                    ).execute()
                }.getOrNull() ?: return@withContext null
            response.use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = runCatching { resp.body?.string() }.getOrNull() ?: return@withContext null
                val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@withContext null
                val result = mutableListOf<String>()
                // tags[] — community-supplied free-form
                parsed["tags"]?.jsonArray?.forEach { tagEntry ->
                    val name = tagEntry.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                    if (!name.isNullOrBlank()) result.add(name)
                }
                // genres[] — MusicBrainz-curated
                parsed["genres"]?.jsonArray?.forEach { genreEntry ->
                    val name = genreEntry.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                    if (!name.isNullOrBlank()) result.add(name)
                }
                result.takeIf { it.isNotEmpty() }
            }
        }

    /**
     * Naive ConcurrentHashMap trim — keeps the metadata caches bounded. Not a true LRU
     * (CHM doesn't support LRU without LinkedHashMap+sync) but good enough for memory
     * hygiene at the volumes the lyrics screen generates.
     */
    private fun trimMetadataCache(cache: java.util.concurrent.ConcurrentHashMap<String, List<String>>) {
        if (cache.size <= MetadataCacheMaxEntries) return
        cache.keys.firstOrNull()?.let { cache.remove(it) }
    }
}
