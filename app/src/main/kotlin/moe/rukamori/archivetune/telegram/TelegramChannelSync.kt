/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Materialises a Telegram channel into a real local playlist so it reuses the normal playlist UI
 * (list tile, rich playlist screen, search / radio / download / menus) instead of a bespoke screen.
 * Opening a channel creates (or reuses) a playlist with a deterministic id, then pages through the
 * channel's audio files in the background, inserting each as a song + playlist membership and
 * seeding its format row. The playlist screen updates reactively as songs arrive.
 */

package moe.rukamori.archivetune.telegram

import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.PlaylistEntity
import moe.rukamori.archivetune.db.entities.PlaylistSongMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.time.LocalDateTime

object TelegramChannelSync {
    private const val TAG = "TelegramChannelSync"
    private const val PLAYLIST_ID_PREFIX = "LPtg"
    private const val PAGE_FETCH_LIMIT = 100

    private const val READY_TIMEOUT_MS = 15_000L
    private const val READY_POLL_INTERVAL_MS = 500L

    private const val FETCH_RETRY_COUNT = 5
    private const val FETCH_RETRY_DELAY_MS = 600L

    private const val OPEN_CHAT_SETTLE_MS = 800L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val inFlight = mutableSetOf<Long>()
    private val inFlightLock = Mutex()

    fun playlistId(chatId: Long): String = "$PLAYLIST_ID_PREFIX$chatId"

    suspend fun ensurePlaylist(
        database: MusicDatabase,
        chatId: Long,
        title: String,
    ): String {
        val id = playlistId(chatId)

        database.withTransaction {
            insert(
                PlaylistEntity(
                    id = id,
                    name = title.ifBlank { "Telegram channel" },
                    browseId = null,
                    isEditable = true,
                    bookmarkedAt = LocalDateTime.now(),
                ),
            )
        }
        return id
    }

    fun syncAsync(
        database: MusicDatabase,
        chatId: Long,
        title: String,
        losslessOnly: Boolean,
    ) {
        scope.launch {
            val started =
                inFlightLock.withLock {
                    if (chatId in inFlight) false else inFlight.add(chatId)
                }
            if (!started) return@launch
            try {
                sync(database, chatId, title, losslessOnly)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Sync failed for chat %d", chatId)
            } finally {
                inFlightLock.withLock { inFlight.remove(chatId) }
            }
        }
    }

    private suspend fun sync(
        database: MusicDatabase,
        chatId: Long,
        title: String,
        losslessOnly: Boolean,
    ) {

        val ready =
            withTimeoutOrNull(READY_TIMEOUT_MS) {
                while (!TelegramClient.isReady) {
                    delay(READY_POLL_INTERVAL_MS)
                }
                true
            } ?: false
        if (!ready) {
            Timber.tag(TAG).w("TDLib client not ready after %dms, aborting sync for chat %d", READY_TIMEOUT_MS, chatId)
            return
        }

        runCatching { TelegramClient.openChat(chatId) }
            .onFailure { Timber.tag(TAG).w(it, "openChat(%d) failed (non-fatal)", chatId) }

        runCatching { TelegramClient.primeChatHistory(chatId) }
            .onFailure { Timber.tag(TAG).w(it, "primeChatHistory(%d) failed (non-fatal)", chatId) }

        delay(OPEN_CHAT_SETTLE_MS)

        val playlistId = playlistId(chatId)
        val filters = listOf(TdApi.SearchMessagesFilterAudio(), TdApi.SearchMessagesFilterDocument())
        var inserted = 0
        val seen = mutableSetOf<Long>()

        for (filter in filters) {
            var fromMessageId = 0L
            var isFirstPage = true
            while (true) {
                val page =
                    fetchPageWithRetry(chatId, fromMessageId, filter, isFirstPage)

                if (page == null) {

                    Timber.tag(TAG).w("fetchPageWithRetry exhausted for chat %d filter %s", chatId, filter::class.simpleName)
                    break
                }

                for (track in page.tracks) {
                    if (losslessOnly && !track.isLossless) continue
                    if (!seen.add(track.messageId)) continue
                    if (insertTrack(database, playlistId, track, title)) inserted++
                }

                isFirstPage = false
                if (page.nextFromMessageId == 0L) break
                fromMessageId = page.nextFromMessageId
            }
        }
        Timber.tag(TAG).d("Materialised %d tracks for chat %d", inserted, chatId)
    }

    private suspend fun fetchPageWithRetry(
        chatId: Long,
        fromMessageId: Long,
        filter: TdApi.SearchMessagesFilter,
        isFirstPage: Boolean = false,
    ): TelegramAudioPage? {
        repeat(FETCH_RETRY_COUNT) { attempt ->
            val result =
                runCatching {
                    TelegramClient.fetchAudioPage(chatId, fromMessageId, PAGE_FETCH_LIMIT, filter)
                }
            result.onSuccess { page ->

                val isEmptyFirstPage =
                    isFirstPage &&
                        page.tracks.isEmpty() &&
                        page.nextFromMessageId == 0L
                if (isEmptyFirstPage && attempt < FETCH_RETRY_COUNT - 1) {
                    Timber.tag(TAG).w(
                        "fetchAudioPage attempt %d/%d returned empty first page for chat %d filter %s (will retry — TDLib history not indexed yet)",
                        attempt + 1,
                        FETCH_RETRY_COUNT,
                        chatId,
                        filter::class.simpleName,
                    )
                } else {
                    return page
                }
            }
            result.onFailure { e ->
                Timber.tag(TAG).w(
                    e,
                    "fetchAudioPage attempt %d/%d failed for chat %d (will retry)",
                    attempt + 1,
                    FETCH_RETRY_COUNT,
                    chatId,
                )
            }
            delay(FETCH_RETRY_DELAY_MS)
        }
        return null
    }

    private suspend fun insertTrack(
        database: MusicDatabase,
        playlistId: String,
        track: TelegramTrack,
        channelTitle: String,
    ): Boolean =
        database.withTransaction {
            if (checkInPlaylist(playlistId, track.mediaId) > 0) return@withTransaction false
            insert(track.toMediaMetadata(channelTitle))
            upsert(track.toFormatEntity())
            val position = (maxPlaylistSongPosition(playlistId) ?: -1) + 1
            insert(
                PlaylistSongMap(
                    songId = track.mediaId,
                    playlistId = playlistId,
                    position = position,
                ),
            )
            true
        }
}
