/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Media3 DataSource that streams a Telegram file through the sequential spool
 * cache (TelegramStreamCache, backed by mtcute's precise download chunks).
 * open() positions the spool at the requested byte offset; read() serves bytes
 * out of the already-downloaded window, waiting for the prefetch to catch up
 * when the player reads faster than the network. Seeking simply re-opens the
 * source at the new position, which re-targets the spool — so FLAC seeking
 * works without waiting for the whole file.
 */

package moe.rukamori.archivetune.telegram

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.IOException

class TelegramDataSource : BaseDataSource(true) {
    private var currentUri: Uri? = null
    private var mediaId: TelegramMediaId? = null
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

        val size =
            runBlocking {
                TelegramStreamCache.open(decoded, dataSpec.position)
            }
        fileSize = size
        position = dataSpec.position

        if (fileSize in 1 until position) {
            throw IOException("Position $position beyond Telegram file size $fileSize")
        }

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
                        val id = mediaId ?: return@withTimeout ByteArray(0)
                        TelegramStreamCache.read(id, position, toRead)
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
        fileSize = 0
        position = 0
        bytesRemaining = C.LENGTH_UNSET.toLong()
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = TelegramDataSource()
    }

    companion object {
        private const val TAG = "TelegramDataSource"
        private const val READ_TIMEOUT_MS = 40_000L

        suspend fun cancelRetainedDownloads() {
            TelegramStreamCache.cancelRetained()
        }
    }
}
