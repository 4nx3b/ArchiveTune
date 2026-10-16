/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Adapted from BitChord (GPL-3.0) https://github.com/kushagrasinghx/BitChord
 * (shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/SimpMusicLyrics.kt) —
 * original re-implementation for ArchiveTune.
 */

package moe.rukamori.archivetune.lyrics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.EnableSimpMusicLyricsKey
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import kotlin.math.abs

object SimpMusicLyricsProvider : LyricsProvider {
    override val name = "SimpMusic"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableSimpMusicLyricsKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> =
        runCatching {
            fetchLyrics(id, duration)
                ?: throw IllegalStateException("No SimpMusic lyrics for $id")
        }

    private suspend fun fetchLyrics(
        videoId: String,
        duration: Int,
    ): String? =
        withContext(Dispatchers.IO) {
            if (!VIDEO_ID.matches(videoId)) return@withContext null

            val body = LyricsProviderHttp.get("$BASE_URL$videoId") ?: return@withContext null
            val root = LyricsProviderHttp.parseJson(body).asObject() ?: return@withContext null
            if (root.boolean("success") != true) return@withContext null

            val tracks =
                root.array("data")?.mapNotNull { it.asObject() } ?: return@withContext null
            val track =
                tracks
                    .filter { track ->
                        val trackDuration = track.long("duration") ?: 0L
                        duration <= 0 || abs(trackDuration - duration) <= DURATION_TOLERANCE_SECONDS
                    }.minByOrNull { track -> abs((track.long("duration") ?: 0L) - duration) }
                    ?: return@withContext null

            track.string("richSyncLyrics")?.let { rich ->
                LyricsPayload.decodeEntities(rich).takeIf { it.isNotBlank() }
            } ?: track.string("syncedLyrics")?.takeIf { it.isNotBlank() }
        }

    private const val DURATION_TOLERANCE_SECONDS = 10L

    private const val BASE_URL = "https://api-lyrics.simpmusic.org/v1/"

    private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")
}
