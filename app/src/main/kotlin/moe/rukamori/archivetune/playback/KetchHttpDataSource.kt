/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import com.ketch.Status
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest

/**
 * A Media3 [DataSource] that delegates the actual HTTP fetching to
 * [Ketch](https://github.com/khushpanchal/Ketch) — a WorkManager-based
 * file downloader with parallel chunked downloads.
 *
 * ## Why Ketch instead of [ParallelRangeOkHttpDataSource]?
 *
 * The previous [ParallelRangeOkHttpDataSource] split each file into 4
 * byte-ranges, fetched them in parallel via OkHttp, and wrote each range
 * to the [CacheDataSink] at the correct offset. In practice this:
 *
 *  - Re-implemented logic Ketch already provides (and Ketch's chunked
 *    download is battle-tested across many apps).
 *  - Was slow on Qobuz / Tidal — both CDNs rate-limit per connection,
 *    but our 4 parallel ranges came from the *same* connection pool
 *    and so were effectively serialized by the host.
 *  - Had a class of bugs around partial-range retries that occasionally
 *    corrupted the downloaded file (Code 3003 —
 *    UnrecognizedInputFormatException).
 *
 * Ketch uses OkHttp internally with proper chunked transfer, retries,
 * and pause/resume. It writes to a single file on disk (no in-memory
 * stitching), then we expose that file via [FileDataSource] so Media3's
 * [CacheDataSink] can copy it into the download cache as fragments.
 *
 * ## Data flow
 *
 * ```
 * Media3 DownloadManager
 *   └─ CacheDataSource (downloadCache)
 *      └─ ResolvingDataSource (resolves mediaId → URL)
 *         └─ CacheDataSource (playerCache, read-only upstream)
 *            └─ KetchHttpDataSource   ←  this class
 *               ├─ Ketch.download(url, tempDir, fileName)
 *               ├─ wait for Status.SUCCESS via Flow
 *               └─ FileDataSource(tempFile).read(buffer, off, len)
 * ```
 *
 * The temp file is deleted in [close]. If the same URL is requested
 * again (e.g. a retry), a fresh temp file is created — Ketch's internal
 * WorkManager state handles resume from where it left off if the file
 * already exists at the same path.
 *
 * ## Range requests
 *
 * Media3's [DownloadManager] always opens with `position=0,
 * length=C.LENGTH_UNSET` (full file) for downloads. We support ranges
 * anyway by seeking into the temp file via [FileDataSource] — this
 * keeps the data source usable for non-download callers.
 */
internal class KetchHttpDataSource private constructor(
    private val context: Context,
    private val userAgent: String,
) : BaseDataSource(true) {

    private var tempFile: File? = null
    private var fileSource: FileDataSource? = null
    private var bytesRemaining: Long = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val url = dataSpec.uri.toString()
        val ketch = KetchHolder.getOrNull()
            ?: throw java.io.IOException("Ketch not initialized — call KetchHolder.init() in App.onCreate()")

        // Build a unique temp file path keyed by URL + position so that
        // concurrent downloads of the same URL with different ranges
        // don't collide. Ketch requires an absolute directory path and
        // a fileName (no path separators in the name).
        val tempDir = File(context.cacheDir, "ketch_tmp").apply { mkdirs() }
        val nameHash = sha1("$url|${dataSpec.position}|${dataSpec.length}")
        val safeName = "dl_$nameHash"
        val target = File(tempDir, safeName)

        // If a stale file from a previous attempt lingers, delete it
        // so Ketch starts a fresh download instead of resuming a
        // potentially-corrupt partial file. Ketch's resume logic only
        // kicks in if the file is intact AND we pass supportPauseResume
        // = true — we pass false here so we always start fresh.
        target.delete()

        // Ketch expects headers as a HashMap. Forward the DataSpec's
        // request headers (if any) plus a User-Agent.
        val headers = HashMap<String, String>()
        if (userAgent.isNotBlank()) headers["User-Agent"] = userAgent
        dataSpec.httpRequestHeaders.forEach { (k, v) ->
            if (!k.equals("Range", ignoreCase = true)) headers[k] = v
        }

        // Ketch.download() returns the download ID immediately. We
        // then observe the status Flow until SUCCESS / FAILED /
        // CANCELLED. Wrap in a timeout so a hung download doesn't
        // block the DownloadManager worker forever.
        val downloadId = ketch.download(
            url = url,
            path = tempDir.absolutePath,
            fileName = safeName,
            tag = "media3_download",
            headers = headers,
            supportPauseResume = false,
        )

        val finalStatus = runBlocking {
            withTimeoutOrNull(KETCH_WAIT_TIMEOUT_MS) {
                ketch.observeDownloadById(downloadId).first { model ->
                    model != null && (
                        model.status == Status.SUCCESS ||
                            model.status == Status.FAILED ||
                            model.status == Status.CANCELLED
                    )
                }
            }
        } ?: throw java.io.IOException("Ketch download timed out for $url")

        // observeDownloadById returns Flow<DownloadModel?> — if the DB
        // row was deleted mid-flight we'd get a null emission. Treat as
        // a download failure.
        val finalModel = finalStatus
            ?: throw java.io.IOException("Ketch download disappeared mid-flight for $url")

        when (finalModel.status) {
            Status.SUCCESS -> Unit
            Status.FAILED -> throw java.io.IOException(
                "Ketch download failed: ${finalModel.failureReason.ifBlank { "unknown" }} for $url",
            )
            Status.CANCELLED -> throw java.io.IOException("Ketch download cancelled for $url")
            else -> throw java.io.IOException("Ketch download ended in unexpected state ${finalModel.status} for $url")
        }

        if (!target.exists() || target.length() == 0L) {
            throw java.io.IOException("Ketch reported SUCCESS but temp file is missing/empty: $target")
        }

        tempFile = target

        // Build a child DataSpec pointing at the local file, preserving
        // position / length / key. FileDataSource honors position to
        // seek into the file (for range requests).
        val fileSpec = dataSpec.withUri(Uri.fromFile(target))
        val fs = FileDataSource()
        val reportedLength = fs.open(fileSpec)
        fileSource = fs

        // bytesRemaining = what FileDataSource reports is left to read.
        // If DataSpec.length is set (bounded range), use the smaller of
        // the two.
        val totalRemaining = if (reportedLength < 0) target.length() - dataSpec.position else reportedLength
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, totalRemaining)
        } else {
            totalRemaining
        }
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val fs = fileSource ?: return -1
        val toRead = if (bytesRemaining in 1..Int.MAX_VALUE.toLong()) {
            minOf(length, bytesRemaining.toInt())
        } else {
            length
        }
        if (toRead <= 0) return -1
        val read = fs.read(buffer, offset, toRead)
        if (read > 0) {
            bytesRemaining -= read
            bytesTransferred(read)
        }
        return read
    }

    override fun getUri(): Uri? = fileSource?.uri

    override fun close() {
        fileSource?.let { runCatching { it.close() } }
        fileSource = null
        tempFile?.let { runCatching { it.delete() } }
        tempFile = null
        bytesRemaining = 0L
    }

    private fun sha1(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Factory for [KetchHttpDataSource]. Each [createDataSource] call
     * returns an independent instance — Ketch itself is a singleton held
     * by [KetchHolder], so the factory is cheap to construct.
     */
    class Factory(
        private val context: Context,
        private val userAgent: String = DEFAULT_USER_AGENT,
    ) : DataSource.Factory {
        override fun createDataSource(): KetchHttpDataSource =
            KetchHttpDataSource(context, userAgent)
    }

    companion object {
        private const val DEFAULT_USER_AGENT = "ArchiveTune"
        private const val KETCH_WAIT_TIMEOUT_MS = 30L * 60 * 1000 // 30 min — large FLACs
    }
}
