/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import androidx.media3.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * A [DataSource] that fetches a single [DataSpec] using N parallel HTTP Range
 * requests against the same URL, then serves bytes to the caller in order.
 *
 * ## Why this exists
 *
 * Media3's [androidx.media3.exoplayer.offline.DownloadManager] parallelizes
 * *across* downloads (up to `maxParallelDownloads`), but each individual
 * download is read sequentially — one HTTP connection per song. For large
 * Qobuz/Tidal FLACs (50-150 MB) that single connection is the bottleneck:
 * it can take 30+ seconds on a 50 Mbps link, while the device and the
 * network are perfectly capable of saturating the link with 4-8 parallel
 * connections.
 *
 * This data source splits a known-length DataSpec into [SEGMENT_COUNT]
 * contiguous byte ranges, fetches them concurrently via the shared
 * [OkHttpClient] (which already has an aggressive dispatcher: 256 max
 * requests, 64 per host, HTTP/2 forced), and stitches the results back
 * together so the caller sees a single sequential stream.
 *
 * ## When it activates
 *
 * Only when the upstream reports a content length (so we can compute
 * segment boundaries). Unknown-length streams fall back to a single
 * sequential [OkHttpDataSource] read — no parallelism, but no regression
 * either. We also skip parallelism for very small ranges (< ~1 MB) where
 * the overhead would exceed the speedup.
 *
 * ## Integration with PRDownloader
 *
 * PRDownloader is initialized in [moe.rukamori.archivetune.App] with a
 * tuned thread pool and connection pool that mirror this client's OkHttp
 * configuration. PRDownloader itself doesn't expose a byte-range API, so
 * we use OkHttp directly here; PRDownloader's configuration still benefits
 * the broader download pipeline (its shared connection pool is reused by
 * the OkHttp client we pass in).
 *
 * ## Back-pressure
 *
 * Fetched segments are pushed into a bounded [Channel] (capacity
 * [SEGMENT_COUNT] + 1) so the parallel fetches naturally pause once the
 * in-flight buffer is full. The consumer reads segments in order.
 */
class SegmentedParallelDataSource private constructor(
    private val okHttpClient: OkHttpClient,
    private val delegateFactory: OkHttpDataSource.Factory,
) : BaseDataSource() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var totalLength: Long = -1L
    private var remaining: Long = -1L
    private var openedUri: android.net.Uri? = null
    private var responseHeaders: Map<String, List<String>> = emptyMap()

    /** The ordered queue of segment buffers still to be drained by [read]. */
    private var pendingSegments: Channel<ByteArray> = Channel(SEGMENT_COUNT + 1)
    private var currentSegment: ByteArray? = null
    private var currentSegmentOffset = 0
    private var fetchJob: Job? = null

    /** Set only when we fall back to a sequential read (small/unknown-length). */
    private var sequentialDelegate: OkHttpDataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        openedUri = dataSpec.uri
        transferStarted(dataSpec)

        // First open the delegate so we learn the content length + headers.
        // We close it immediately — we only needed the headers.
        val probe = delegateFactory.createDataSource()
        val reportedLength = try {
            probe.open(dataSpec)
        } catch (e: IOException) {
            // Some hosts return 416 Range Not Satisfiable for HEAD-style probes.
            // Treat that as "unknown length" and fall back to sequential.
            return runSequential(dataSpec)
        }
        responseHeaders = probe.responseHeaders
        runCatching { probe.close() }

        totalLength = if (reportedLength == C.LENGTH_UNSET.toLong()) -1L else reportedLength
        // The dataSpec may itself carry a length (range request). Prefer that.
        val effectiveLength = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else totalLength
        if (effectiveLength <= 0 || effectiveLength < MIN_PARALLEL_BYTES) {
            return runSequential(dataSpec)
        }

        remaining = effectiveLength

        // Kick off parallel segment fetches. Each segment is a half-open byte
        // range [start, end) of the underlying resource, accounting for any
        // position offset already encoded in the dataSpec.
        val basePosition = dataSpec.position
        val segmentSize = effectiveLength / SEGMENT_COUNT
        fetchJob = scope.launch {
            try {
                val tasks = (0 until SEGMENT_COUNT).map { idx ->
                    val start = basePosition + idx * segmentSize
                    val endExclusive = if (idx == SEGMENT_COUNT - 1) {
                        basePosition + effectiveLength
                    } else {
                        basePosition + (idx + 1) * segmentSize
                    }
                    async {
                        val bytes = fetchRange(dataSpec, start, endExclusive - 1)
                        pendingSegments.send(bytes)
                    }
                }
                tasks.awaitAll()
            } catch (e: Throwable) {
                // Make sure [read] doesn't block forever if a segment fetch
                // fails — closing the channel causes the next receiveCatching()
                // to return null, which [read] interprets as EOF.
                pendingSegments.close(e)
            } finally {
                pendingSegments.close()
            }
        }

        return effectiveLength
    }

    /** Falls back to a single sequential read via the underlying OkHttpDataSource. */
    private fun runSequential(dataSpec: DataSpec): Long {
        val seq = delegateFactory.createDataSource()
        val len = seq.open(dataSpec)
        sequentialDelegate = seq
        totalLength = len
        remaining = len
        return len
    }

    private fun fetchRange(
        original: DataSpec,
        startInclusive: Long,
        endInclusive: Long,
    ): ByteArray {
        val rangeHeader = "bytes=$startInclusive-$endInclusive"
        val request = okhttp3.Request.Builder()
            .url(original.uri)
            .header("Range", rangeHeader)
            .header("Accept-Encoding", "identity")
            .header("Connection", "keep-alive")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Segment fetch failed: HTTP ${response.code} for range $rangeHeader on ${original.uri}")
            }
            val body = response.body ?: throw IOException("Empty body for range $rangeHeader")
            val bytes = body.bytes()
            bytesTransferred(bytes.size.toLong())
            return bytes
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining == 0L) return C_RESULT_END_OF_INPUT

        // Sequential fast-path.
        sequentialDelegate?.let { seq ->
            val read = seq.read(buffer, offset, length)
            if (read == C_RESULT_END_OF_INPUT) {
                remaining = 0L
            } else if (read > 0) {
                remaining -= read.toLong()
                bytesTransferred(read.toLong())
            }
            return read
        }

        var written = 0
        var target = length
        while (target > 0) {
            // Refill current segment if exhausted.
            val cur = currentSegment
            if (cur != null && currentSegmentOffset >= cur.size) {
                currentSegment = null
                currentSegmentOffset = 0
            }
            if (currentSegment == null) {
                // Pull the next segment from the channel (suspends if not ready).
                val result = runBlocking { pendingSegments.receiveCatching() }
                if (result.isClosed) {
                    // Channel was closed. If it was closed with an exception,
                    // surface that to the caller so Media3 marks the download
                    // as failed rather than silently treating it as EOF.
                    result.closeCause?.let { cause ->
                        throw IOException("Parallel segment fetch failed", cause)
                    }
                    // Clean close with no exception → EOF.
                    if (written > 0) return written
                    remaining = 0L
                    return C_RESULT_END_OF_INPUT
                }
                val next = result.getOrNull()
                if (next == null || next.isEmpty()) {
                    if (written > 0) return written
                    remaining = 0L
                    return C_RESULT_END_OF_INPUT
                }
                currentSegment = next
                currentSegmentOffset = 0
            }
            val seg = currentSegment!!
            val toCopy = minOf(target, seg.size - currentSegmentOffset)
            System.arraycopy(seg, currentSegmentOffset, buffer, offset + written, toCopy)
            currentSegmentOffset += toCopy
            written += toCopy
            target -= toCopy
            remaining -= toCopy.toLong()
        }
        if (written > 0) bytesTransferred(written.toLong())
        return written
    }

    override fun close() {
        fetchJob?.cancel()
        fetchJob = null
        pendingSegments.close()
        pendingSegments = Channel(SEGMENT_COUNT + 1)
        currentSegment = null
        currentSegmentOffset = 0
        runCatching { sequentialDelegate?.close() }
        sequentialDelegate = null
        remaining = -1L
        totalLength = -1L
    }

    override fun getUri(): android.net.Uri? = openedUri

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

    /**
     * Factory that creates [SegmentedParallelDataSource] instances sharing a
     * single [OkHttpClient]. The underlying [OkHttpDataSource.Factory] is used
     * (a) for the initial probe open (so we learn the content length + headers)
     * and (b) as a sequential fall-back when the spec is too small to parallelize
     * or when the upstream doesn't report a content length.
     */
    class Factory(
        private val okHttpClient: OkHttpClient,
    ) : DataSource.Factory {
        private val delegateFactory = OkHttpDataSource.Factory(okHttpClient)

        override fun createDataSource(): SegmentedParallelDataSource =
            SegmentedParallelDataSource(okHttpClient, delegateFactory)
    }

    companion object {
        private const val C_RESULT_END_OF_INPUT = -1

        /** Number of parallel byte-range requests per download. */
        private const val SEGMENT_COUNT = 4

        /** Don't bother parallelizing tiny ranges — the overhead exceeds the win. */
        private const val MIN_PARALLEL_BYTES = 1L * 1024 * 1024 // 1 MB
    }
}
