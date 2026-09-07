/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Media3 DataSource that streams a Telegram file through TDLib's partial-download cache.
 * open() kicks off (or re-targets) a download at the requested byte offset; read() serves bytes
 * out of the already-downloaded prefix via ReadFilePart, waiting for the download to catch up
 * when the player reads faster than the network. Seeking simply re-opens the source at the new
 * position, which TDLib translates into a new download offset — so FLAC seeking works without
 * waiting for the whole file.
 *
 * TDLib keeps the partial file in its own cache, so pause/resume and replays don't re-download.
 */

package moe.rukamori.archivetune.telegram

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.IOException

class TelegramDataSource : BaseDataSource(true) {
    private var currentUri: Uri? = null
    private var mediaId: TelegramMediaId? = null
    private var fileId: Int = 0
    private var fileSize: Long = 0
    private var position: Long = 0
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val decoded =
            TelegramMediaId.decode(dataSpec.uri.toString())
                ?: throw IOException("Not a Telegram media id: ${dataSpec.uri}")
        if (!TelegramClient.isReady) {
            throw IOException("Telegram is not logged in")
        }
        currentUri = dataSpec.uri
        mediaId = decoded
        transferInitializing(dataSpec)

        val file =
            runBlocking {
                resolveFile(decoded)
            } ?: throw IOException("Telegram file unavailable for ${dataSpec.uri}")
        fileId = file.id
        fileSize = if (file.size > 0) file.size else file.expectedSize
        position = dataSpec.position

        if (fileSize in 1 until position) {
            throw IOException("Position $position beyond Telegram file size $fileSize")
        }

        runBlocking {
            ensureDownloading(position)
        }

        retainDownload(fileId)

        bytesRemaining =
            when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                fileSize > 0 -> fileSize - position
                else -> C.LENGTH_UNSET.toLong()
            }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        var toRead = length.toLong()
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            toRead = minOf(toRead, bytesRemaining)
        }
        if (fileSize > 0) {
            val untilEof = fileSize - position
            if (untilEof <= 0) return C.RESULT_END_OF_INPUT
            toRead = minOf(toRead, untilEof)
        }

        val data =
            try {
                runBlocking {
                    withTimeout(READ_TIMEOUT_MS) {
                        awaitAndRead(position, toRead)
                    }
                }
            } catch (e: Exception) {
                throw IOException("Telegram stream read failed at $position", e)
            }

        if (data.isEmpty()) {
            return if (fileSize > 0 && position >= fileSize) C.RESULT_END_OF_INPUT else 0
        }

        System.arraycopy(data, 0, buffer, offset, data.size)
        position += data.size
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= data.size
        }
        bytesTransferred(data.size)
        return data.size
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        currentUri = null
        mediaId = null
        fileId = 0
        fileSize = 0
        position = 0
        bytesRemaining = C.LENGTH_UNSET.toLong()
    }

    private suspend fun resolveFile(decoded: TelegramMediaId): TdApi.File? {
        runCatching { TelegramClient.getFile(decoded.fileId) }
            .onSuccess { file ->
                if (decoded.fileUniqueId.isEmpty() || file.remote?.uniqueId == decoded.fileUniqueId) {
                    return file
                }
            }
        Timber.tag(TAG).i(
            "Stale Telegram file id %d, re-resolving message %d in chat %d",
            decoded.fileId,
            decoded.messageId,
            decoded.chatId,
        )
        return TelegramClient.resolveTrackFile(decoded.chatId, decoded.messageId)
    }

    private suspend fun ensureDownloading(offset: Long) {
        val file = TelegramClient.getFile(fileId)
        val local = file.local
        if (local.isDownloadingCompleted) return
        val covered =
            local.downloadOffset <= offset &&
                local.downloadOffset + local.downloadedPrefixSize > offset
        if (!local.isDownloadingActive || !covered) {
            TelegramClient.startDownload(fileId, offset)
        }
    }

    private suspend fun awaitAndRead(
        offset: Long,
        count: Long,
    ): ByteArray {
        while (true) {
            val file = TelegramClient.getFile(fileId)
            val local = file.local
            if (file.size > 0) {
                fileSize = file.size
            }
            var wanted = count
            if (fileSize > 0) {
                val untilEof = fileSize - offset
                if (untilEof <= 0) return ByteArray(0)
                wanted = minOf(wanted, untilEof)
            }
            if (local.isDownloadingCompleted) {
                return TelegramClient.readFilePart(fileId, offset, wanted)
            }

            val runStart = local.downloadOffset
            val runEnd = local.downloadOffset + local.downloadedPrefixSize
            if (runStart <= offset && runEnd > offset) {
                val available = runEnd - offset
                return TelegramClient.readFilePart(fileId, offset, minOf(wanted, available))
            }

            if (!local.isDownloadingActive || runStart > offset) {
                TelegramClient.startDownload(fileId, offset)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = TelegramDataSource()
    }

    companion object {
        private const val TAG = "TelegramDataSource"
        private const val READ_TIMEOUT_MS = 40_000L
        private const val POLL_INTERVAL_MS = 150L

        private const val MAX_RETAINED_DOWNLOADS = 3

        private val retainedFileIds = LinkedHashSet<Int>()

        private fun retainDownload(fileId: Int) {
            if (fileId <= 0) return
            val evicted =
                synchronized(retainedFileIds) {

                    retainedFileIds.remove(fileId)
                    retainedFileIds.add(fileId)
                    val overflow = retainedFileIds.size - MAX_RETAINED_DOWNLOADS
                    if (overflow <= 0) {
                        emptyList()
                    } else {
                        val oldest = retainedFileIds.take(overflow)
                        retainedFileIds.removeAll(oldest.toSet())
                        oldest
                    }
                }
            if (evicted.isEmpty()) return
            runBlocking {
                evicted.forEach { id ->
                    Timber.tag(TAG).d("Cancelling retained Telegram download for file %d", id)
                    TelegramClient.cancelDownload(id)
                }
            }
        }

        suspend fun cancelRetainedDownloads() {
            val ids =
                synchronized(retainedFileIds) {
                    val snapshot = retainedFileIds.toList()
                    retainedFileIds.clear()
                    snapshot
                }
            ids.forEach { TelegramClient.cancelDownload(it) }
        }
    }
}
