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
import com.downloader.Error
import com.downloader.OnDownloadListener
import com.downloader.PRDownloader
import com.downloader.request.DownloadRequest
import moe.rukamori.archivetune.utils.StreamClientUtils
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class PRDownloaderDataSource private constructor(
    private val context: Context,
    private val userAgent: String,
) : BaseDataSource(true) {

    private var tempFile: File? = null
    private var fileSource: FileDataSource? = null
    private var bytesRemaining: Long = 0L

    private var activeDownloadId: Int = -1

    /** Request key whose fetch progress is currently published. */
    private var progressKey: String? = null

    private val headClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(4, 30_000, TimeUnit.MILLISECONDS))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .build()
    }

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val url = dataSpec.uri.toString()
        progressKey = dataSpec.key

        var lastError: IOException? = null
        for (attempt in 1..MAX_DOWNLOAD_ATTEMPTS) {
            try {
                return openSingle(dataSpec, url, attempt)
            } catch (e: IOException) {
                lastError = e

                if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS * attempt)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
            } finally {
                if (attempt == MAX_DOWNLOAD_ATTEMPTS) {
                    // The whole-file fetch is over — stop reporting progress for
                    // this request key so the UI does not keep showing a stale
                    // percentage after completion/failure.
                    progressKey?.let(DownloadFetchProgress::clear)
                }
            }
        }
        throw lastError ?: IOException("PRDownloader failed after $MAX_DOWNLOAD_ATTEMPTS attempts for $url")
    }

    private fun openSingle(dataSpec: DataSpec, url: String, attempt: Int): Long {

        val tempDir = File(context.cacheDir, "prd_tmp").apply { mkdirs() }
        val nameHash = sha1("$url|${dataSpec.position}|${dataSpec.length}")
        val safeName = "dl_$nameHash"
        val target = File(tempDir, safeName)

        target.delete()

        val requestBuilder = PRDownloader.download(url, tempDir.absolutePath, safeName)

        val youTubeMediaProfile = runCatching {
            StreamClientUtils.resolveRequestProfile(url)
        }.getOrNull()
        val resolvedUserAgent = youTubeMediaProfile?.userAgent?.takeIf(String::isNotBlank)
            ?: userAgent
        if (resolvedUserAgent.isNotBlank()) requestBuilder.setUserAgent(resolvedUserAgent)
        if (youTubeMediaProfile != null) {
            youTubeMediaProfile.origin?.takeIf(String::isNotBlank)?.let {
                requestBuilder.setHeader("Origin", it)
            }
            youTubeMediaProfile.referer?.takeIf(String::isNotBlank)?.let {
                requestBuilder.setHeader("Referer", it)
            }

            requestBuilder.setHeader("Accept-Encoding", "identity")
            requestBuilder.setHeader("Connection", "keep-alive")
        }
        dataSpec.httpRequestHeaders.forEach { (k, v) ->

            val lower = k.lowercase()
            if (lower != "range" &&
                lower != "user-agent" &&
                lower != "origin" &&
                lower != "referer" &&
                lower != "accept-encoding"
            ) {
                requestBuilder.setHeader(k, v)
            }
        }
        val builder: DownloadRequest = requestBuilder.build()

        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<Error?>(null)
        // Stall watchdog bookkeeping: PRDownloader invokes the progress
        // listener on every delivered chunk. A healthy fetch — even a slow
        // one — keeps producing bytes; a wedged connection (googlevideo
        // throttling to zero, a dead keep-alive socket) stops calling it
        // entirely while the 5-minute read timeout inside PRDownloader keeps
        // the request "alive". The latch poll below compares the last
        // progress timestamp against PROGRESS_STALL_TIMEOUT_MS and cancels
        // the request so the download fails fast and retries with a fresh
        // resolution instead of hanging at 0% for the full latch window —
        // the "YouTube downloads infinite loading" report.
        val lastProgressAtMs = AtomicLong(android.os.SystemClock.elapsedRealtime())

        builder.setOnProgressListener { progress ->
            lastProgressAtMs.set(android.os.SystemClock.elapsedRealtime())
            // Surface the real fetch progress: Media3's own Download stays at
            // 0% for this whole-file stage (it only counts bytes it has read
            // from the temp file), which is what made YouTube downloads look
            // "stuck on loading" for the entire network fetch.
            progressKey?.let { key ->
                DownloadFetchProgress.update(
                    key = key,
                    bytes = progress.currentBytes,
                    total = progress.totalBytes,
                )
            }
        }

        activeDownloadId = builder.start(object : OnDownloadListener {
            override fun onDownloadComplete() {
                latch.countDown()
            }

            override fun onError(error: Error) {
                errorRef.set(error)
                latch.countDown()
            }
        })

        // Poll the latch instead of awaiting it blind: every poll checks
        // (a) whether the download finished, (b) whether bytes stopped
        // flowing (stall watchdog), and (c) the overall deadline. Bounded by
        // DOWNLOAD_WAIT_TIMEOUT_MINUTES so a slow-but-healthy large file
        // still has minutes of headroom, while a stalled one dies in
        // seconds.
        val deadlineNs = System.nanoTime() + TimeUnit.MINUTES.toNanos(DOWNLOAD_WAIT_TIMEOUT_MINUTES)
        var stalled = false
        var timedOut = false
        while (true) {
            if (latch.await(PROGRESS_POLL_SECONDS, TimeUnit.SECONDS)) break
            val idleMs = android.os.SystemClock.elapsedRealtime() - lastProgressAtMs.get()
            if (idleMs > PROGRESS_STALL_TIMEOUT_MS) {
                stalled = true
                break
            }
            if (System.nanoTime() >= deadlineNs) {
                timedOut = true
                break
            }
        }
        if (stalled || timedOut) {
            runCatching { PRDownloader.cancel(activeDownloadId) }
            // Give the cancelled request a brief window to surface its error
            // through the listener before we discard the temp file.
            latch.await(2, TimeUnit.SECONDS)
            runCatching { target.delete() }
            throw IOException(
                if (stalled) {
                    "PRDownloader stalled for $url (no bytes for ${PROGRESS_STALL_TIMEOUT_MS / 1000}s)"
                } else {
                    "PRDownloader timed out after $DOWNLOAD_WAIT_TIMEOUT_MINUTES min for $url"
                },
            )
        }

        errorRef.get()?.let { err ->
            runCatching { target.delete() }
            val msg = buildString {
                append("PRDownloader failed for $url")
                if (err.isConnectionError) append(" (connection error)")
                if (err.isServerError) append(" (server error)")
                err.serverErrorMessage?.takeIf(String::isNotBlank)?.let { append(": $it") }
                err.connectionException?.message?.takeIf(String::isNotBlank)?.let { append(" — $it") }
                if (err.responseCode > 0) append(" (HTTP ${err.responseCode})")
            }
            throw IOException(msg)
        }

        if (!target.exists() || target.length() == 0L) {
            runCatching { target.delete() }
            throw IOException("PRDownloader reported success but temp file is missing/empty: $target")
        }

        val expectedLength = resolveExpectedContentLength(url, dataSpec)
        if (expectedLength > 0L) {
            val actualLength = target.length()
            if (actualLength < expectedLength) {
                runCatching { target.delete() }
                throw IOException(
                    "Partial download for $url: got $actualLength / $expectedLength bytes " +
                        "(" + (actualLength * 100 / expectedLength) + "%)",
                )
            }
        }

        tempFile = target

        val fileSpec = dataSpec.withUri(Uri.fromFile(target))
        val fs = FileDataSource()
        val reportedLength = fs.open(fileSpec)
        fileSource = fs

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

        if (activeDownloadId != -1) {
            runCatching { PRDownloader.cancel(activeDownloadId) }
            activeDownloadId = -1
        }
        progressKey?.let(DownloadFetchProgress::clear)
        progressKey = null
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

    private fun resolveExpectedContentLength(url: String, dataSpec: DataSpec): Long {

        dataSpec.httpRequestHeaders["X-Expected-Content-Length"]?.toLongOrNull()
            ?.let { if (it > 0L) return it }

        val youTubeMediaProfile = runCatching {
            StreamClientUtils.resolveRequestProfile(url)
        }.getOrNull()
        val resolvedUserAgent = youTubeMediaProfile?.userAgent?.takeIf(String::isNotBlank)
            ?: userAgent
        val origin = youTubeMediaProfile?.origin?.takeIf(String::isNotBlank)
        val referer = youTubeMediaProfile?.referer?.takeIf(String::isNotBlank)

        val headLength = runCatching {
            val builder = Request.Builder().url(url).head()
                .header("User-Agent", resolvedUserAgent)
                .header("Accept-Encoding", "identity")
            origin?.let { builder.header("Origin", it) }
            referer?.let { builder.header("Referer", it) }
            headClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 200 && response.code != 206) {
                    0L
                } else {
                    response.header("Content-Length")?.toLongOrNull() ?: 0L
                }
            }
        }.getOrDefault(0L)
        if (headLength > 0L) return headLength

        return runCatching {
            val builder = Request.Builder().url(url)
                .header("Range", "bytes=0-0")
                .header("User-Agent", resolvedUserAgent)
                .header("Accept-Encoding", "identity")
            origin?.let { builder.header("Origin", it) }
            referer?.let { builder.header("Referer", it) }
            headClient.newCall(builder.build()).execute().use { response ->
                if (response.code != 206) return@use 0L
                val contentRange = response.header("Content-Range") ?: return@use 0L

                val slashIdx = contentRange.lastIndexOf('/')
                if (slashIdx < 0 || slashIdx == contentRange.length - 1) return@use 0L
                contentRange.substring(slashIdx + 1).trim().toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)
    }

    class Factory(
        private val context: Context,
        private val userAgent: String = DEFAULT_USER_AGENT,
    ) : DataSource.Factory {
        override fun createDataSource(): PRDownloaderDataSource =
            PRDownloaderDataSource(context, userAgent)
    }

    companion object {
        private const val DEFAULT_USER_AGENT = "ArchiveTune"

        /**
         * Overall bound for one whole-file PRDownloader fetch. A music stream
         * is a few MB — anything that cannot fetch it in this window is
         * throttled or wedged, and parking the download at 0% for longer is
         * exactly the "infinite download" experience. On failure the
         * DownloadManager-level auto-retry resolves a fresh stream URL.
         */
        private const val DOWNLOAD_WAIT_TIMEOUT_MINUTES = 5L

        /** Cancel the fetch when no bytes have arrived for this long. */
        private const val PROGRESS_STALL_TIMEOUT_MS = 45_000L

        /** How often the latch poll re-checks progress staleness. */
        private const val PROGRESS_POLL_SECONDS = 5L

        private const val MAX_DOWNLOAD_ATTEMPTS = 2

        private const val RETRY_DELAY_MS = 1_500L
    }
}

/**
 * Live whole-file fetch progress reported by [PRDownloaderDataSource], keyed by
 * the download request's cache key (e.g. "ytm:<id>"). While the PRDownloader
 * stage buffers the stream to a temp file, Media3's Download object reports 0%
 * — UIs collect [flow] to show the REAL progress of the network fetch.
 */
internal object DownloadFetchProgress {
    data class Fetch(
        val bytes: Long,
        val total: Long,
        val updatedAtMs: Long,
    ) {
        val percent: Int? get() = if (total > 0) ((bytes * 100) / total).coerceIn(0, 100).toInt() else null
    }

    private val _flow = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Fetch>>(emptyMap())
    val flow: kotlinx.coroutines.flow.StateFlow<Map<String, Fetch>> = _flow.asStateFlow()

    fun update(
        key: String,
        bytes: Long,
        total: Long,
    ) {
        _flow.value = _flow.value + (key to Fetch(bytes, total, android.os.SystemClock.elapsedRealtime()))
    }

    fun clear(key: String) {
        if (_flow.value.containsKey(key)) {
            _flow.value = _flow.value - key
        }
    }

    fun get(key: String): Fetch? = _flow.value[key]
}
