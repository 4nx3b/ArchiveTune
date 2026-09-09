/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Media3 DataSource that streams a Deezer CDN file and Blowfish-decrypts it in flight, so the
 * extractor above it sees an ordinary FLAC/MP3 byte stream.
 *
 * This is deliberately a DataSource rather than a standalone downloader. Downloads in this app run
 * through Media3's DownloadManager over the same DataSource factory as playback, so implementing
 * Deezer here means downloading, caching, tag embedding and codec reporting all keep working with no
 * Deezer-specific code in those paths. An external HTTP download loop is what produced corrupted
 * files and unreadable tags in earlier attempts at Deezer support elsewhere.
 */

package moe.rukamori.archivetune.deezer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import timber.log.Timber
import java.io.IOException
import kotlin.math.min

internal class DeezerDecryptingDataSource(
    private val upstreamFactory: DataSource.Factory,
) : BaseDataSource(true) {
    private var upstream: DataSource? = null
    private var currentUri: Uri? = null
    private var key: ByteArray? = null

    private val chunk = ByteArray(DeezerCrypto.CHUNK_SIZE)

    private var chunkLength = 0
    private var chunkOffset = 0

    private var nextChunkIndex = 0L

    private var bytesRemaining = C.LENGTH_UNSET.toLong()

    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val ref =
            DeezerCrypto.parseUri(dataSpec.uri)
                ?: throw IOException("Not a Deezer stream URI: ${dataSpec.uri}")
        val realUri = ref.url

        key = DeezerCrypto.deriveKey(ref.trackId, ref.salt)
        currentUri = dataSpec.uri

        val requestedPosition = dataSpec.position
        val alignedPosition = requestedPosition - (requestedPosition % DeezerCrypto.CHUNK_SIZE)
        val discardCount = (requestedPosition - alignedPosition).toInt()
        nextChunkIndex = alignedPosition / DeezerCrypto.CHUNK_SIZE

        val upstreamLength =
            if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                C.LENGTH_UNSET.toLong()
            } else {
                dataSpec.length + discardCount
            }

        val source = upstreamFactory.createDataSource()
        upstream = source

        val upstreamLengthReported =
            source.open(
                dataSpec
                    .buildUpon()
                    .setUri(realUri)
                    .setPosition(alignedPosition)
                    .setLength(upstreamLength)
                    .build(),
            )

        bytesRemaining =
            when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                upstreamLengthReported == C.LENGTH_UNSET.toLong() -> C.LENGTH_UNSET.toLong()

                else -> (upstreamLengthReported - discardCount).coerceAtLeast(0L)
            }

        opened = true
        transferStarted(dataSpec)

        if (discardCount > 0) discardFully(discardCount)

        return bytesRemaining
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        if (chunkOffset >= chunkLength && !fillChunk()) return C.RESULT_END_OF_INPUT

        var available = chunkLength - chunkOffset
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            available = min(available.toLong(), bytesRemaining).toInt()
        }
        val toCopy = min(length, available)
        if (toCopy == 0) return C.RESULT_END_OF_INPUT

        chunk.copyInto(buffer, offset, chunkOffset, chunkOffset + toCopy)
        chunkOffset += toCopy
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= toCopy
        bytesTransferred(toCopy)
        return toCopy
    }

    private fun fillChunk(): Boolean {
        val source = upstream ?: return false
        var filled = 0

        while (filled < DeezerCrypto.CHUNK_SIZE) {
            val read = source.read(chunk, filled, DeezerCrypto.CHUNK_SIZE - filled)
            if (read == C.RESULT_END_OF_INPUT) break
            filled += read
        }
        if (filled == 0) return false

        if (filled == DeezerCrypto.CHUNK_SIZE && DeezerCrypto.isEncryptedChunk(nextChunkIndex)) {
            val chunkKey = key ?: return false
            try {
                DeezerCrypto.decryptChunk(chunk, filled, chunkKey)
            } catch (e: Exception) {

                throw IOException("Deezer chunk decryption failed at index $nextChunkIndex", e)
            }
        }

        nextChunkIndex++
        chunkLength = filled
        chunkOffset = 0
        return true
    }

    private fun discardFully(count: Int) {
        var left = count
        while (left > 0) {
            if (chunkOffset >= chunkLength && !fillChunk()) return
            val skip = min(left, chunkLength - chunkOffset)
            chunkOffset += skip
            left -= skip
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream?.responseHeaders ?: emptyMap()

    override fun close() {
        chunkLength = 0
        chunkOffset = 0
        key = null
        currentUri = null
        bytesRemaining = C.LENGTH_UNSET.toLong()
        try {
            upstream?.close()
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "Failed to close Deezer upstream")
        } finally {
            upstream = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(
        private val upstreamFactory: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = DeezerDecryptingDataSource(upstreamFactory)
    }

    private companion object {
        private const val TAG = "DeezerDataSource"
    }
}
