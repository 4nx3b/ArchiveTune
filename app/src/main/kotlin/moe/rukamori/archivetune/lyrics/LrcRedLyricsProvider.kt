/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Adapted from BitChord (GPL-3.0) https://github.com/kushagrasinghx/BitChord
 * (shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LrcRed.kt) —
 * original re-implementation for ArchiveTune.
 */

package moe.rukamori.archivetune.lyrics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.EnableLrcRedKey
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * lrc.red — Apple Music TTML, per-syllable, filed by ISRC.
 *
 * Every document lives at `https://lrc.red/s/{ISRC}.ttml`, so a track whose
 * recording is already known costs one request and cannot come back as the
 * wrong edit. That is the usual case: [BiniLyricsProvider] answers its
 * searches out of this same catalogue — its `lyricsUrl` points here.
 *
 * Without an ISRC, lrc.red's own search (`/search.json?q=`) is asked with the
 * title and artist, and the hits are matched here rather than trusted in
 * order: the search is a free-text one and ranks "Bohemian Rhapsody
 * (Operatic Section / 2011 A Cappella Mix)" above the song itself. See
 * [best].
 *
 * Line-synced entries are TTML too (`lrc:timing="Line"`), which the app's own
 * TTML parser already reads, so one parser covers both.
 */
object LrcRedLyricsProvider : LyricsProvider {
    override val name = "lrc.red"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableLrcRedKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> = getLyrics(id, title, artist, album, duration, isrc = null)

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        isrc: String?,
    ): Result<String> =
        runCatching {
            fetchLyrics(title, artist, duration, isrc)
                ?: throw IllegalStateException("No lrc.red lyrics for $title — $artist")
        }

    private suspend fun fetchLyrics(
        title: String,
        artist: String,
        duration: Int,
        isrc: String?,
    ): String? =
        withContext(Dispatchers.IO) {
            val known = isrc?.let(::documentUrl)
            if (known != null) {
                document(known)?.let { return@withContext it }
            }

            // A known recording lrc.red doesn't have — a local file's tag naming a
            // release it never indexed — still gets a search: the same song is
            // often catalogued under a sibling release's code.
            val hit = search(title.forLyricsSearch(), artist.artistForLyricsSearch(), duration)
                ?: return@withContext null
            val found = hit.isrc?.let(::documentUrl)?.takeIf { it != known } ?: return@withContext null
            document(found)
        }

    private suspend fun document(url: String): String? =
        LyricsProviderHttp.get(url)?.let(LyricsPayload::toLyrics)

    private suspend fun search(
        title: String,
        artist: String,
        duration: Int,
    ): Hit? {
        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (query.isEmpty()) return null
        val url = "https://lrc.red/search.json?q=${LyricsProviderHttp.encode(query)}"
        val body = LyricsProviderHttp.get(url) ?: return null
        val root = LyricsProviderHttp.parseJson(body).asObject() ?: return null
        val hits = root.array("hits") ?: return null
        val parsed = hits.mapNotNull { element ->
            val hit = element.asObject() ?: return@mapNotNull null
            Hit(
                isrc = hit.string("isrc"),
                title = hit.string("title"),
                artist = hit.string("artist"),
                duration = hit.double("duration"),
            )
        }
        return best(parsed, title, artist, duration)
    }

    /**
     * The hit that is this recording, or null if none of them is sure to be.
     *
     * A miss here is recoverable — the sources behind this one get their turn —
     * and the wrong words scrolling in time with the right song is not, so
     * every test is a requirement rather than a score:
     *
     *  - the title, outside its brackets and any " - " suffix, is the same;
     *  - the words in those brackets that name a different recording
     *    ([VERSION_WORDS]: live, remix, acoustic…) are the same on both
     *    sides — "Remastered" and "From 'Aashiqui 2'" don't count;
     *  - at least one credited artist is shared;
     *  - the length is within [DURATION_TOLERANCE_SECONDS], when both are known.
     *
     * Of the hits that pass, the closest in length wins.
     */
    private fun best(
        hits: List<Hit>,
        title: String,
        artist: String,
        duration: Int,
    ): Hit? {
        val wantedTitle = coreOf(title)
        val wantedVersion = versionOf(title)
        val wantedArtists = artistsOf(artist)
        val seconds = duration.toDouble()

        fun distance(hit: Hit): Double =
            if (duration > 0 && hit.duration != null) abs(hit.duration - seconds) else 0.0

        return hits
            .filter { hit ->
                val name = hit.title ?: return@filter false
                hit.isrc?.let { documentUrl(it) } != null &&
                    coreOf(name) == wantedTitle &&
                    versionOf(name) == wantedVersion &&
                    (wantedArtists.isEmpty() || artistsOf(hit.artist.orEmpty()).any { it in wantedArtists }) &&
                    distance(hit) <= DURATION_TOLERANCE_SECONDS
            }.minByOrNull(::distance)
    }

    /** "Song (Live) - 2011 Remaster" → "song". */
    private fun coreOf(title: String): String =
        normalized(title.replace(BRACKETED, " ").substringBefore(" - "))
            .ifEmpty { normalized(title) }

    /** The version words in the parts [coreOf] throws away. */
    private fun versionOf(title: String): Set<String> {
        val extras =
            BRACKETED.findAll(title).joinToString(" ") { it.value } +
                " " + title.substringAfter(" - ", "")
        return normalized(extras).split(' ').filter { it in VERSION_WORDS }.toSet()
    }

    private fun artistsOf(artist: String): Set<String> =
        artist.split(ARTIST_SEPARATORS).map(::normalized).filter { it.isNotEmpty() }.toSet()

    /** Lower case, accents off ("ROSÉ" is "rose"), and only letters and digits. */
    private fun normalized(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .replace(WHITESPACE, " ")
            .trim()

    /**
     * `CC-XXX-YY-NNNNN` without the dashes — and nothing else goes into a path.
     */
    private fun documentUrl(isrc: String): String? =
        isrc.trim().uppercase(Locale.ROOT).takeIf { ISRC.matches(it) }?.let { "$BASE_URL/s/$it.ttml" }

    private data class Hit(
        val isrc: String?,
        val title: String?,
        val artist: String?,
        val duration: Double?,
    )

    /**
     * How far a hit's length may be from the playing track's. The app reports
     * whole seconds, and lrc.red lists the 2011 remaster of "Bohemian Rhapsody"
     * at 356.5 s against 352.0 s for the live cut — close enough that the
     * version words in [best] have to do most of the work, not this.
     */
    private const val DURATION_TOLERANCE_SECONDS = 3.0

    private const val BASE_URL = "https://lrc.red"

    private val ISRC = Regex("""[A-Z]{2}[A-Z0-9]{3}\d{7}""")

    private val BRACKETED = Regex("""[(\[][^)\]]*[)\]]""")
    private val COMBINING_MARKS = Regex("""\p{Mn}+""")
    private val WHITESPACE = Regex("""\s+""")
    private val ARTIST_SEPARATORS =
        Regex(
            """\s*(?:,|&|;|/|\s+and\s+|\s+x\s+|\s+with\s+|\s+feat\.?\s+|\s+ft\.?\s+)\s*""",
            RegexOption.IGNORE_CASE,
        )

    /** Bracket words that make it a different recording, not a different label. */
    private val VERSION_WORDS =
        setOf(
            "live", "remix", "remixed", "mix", "acoustic", "unplugged", "instrumental",
            "karaoke", "cappella", "acapella", "demo", "edit", "version", "cover",
            "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral", "extended",
        )
}
