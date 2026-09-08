/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Sequential spool-file cache for Telegram media — the mtcute-era replacement
 * for TDLib's partial-download manager (downloadOffset / downloadedPrefixSize
 * semantics).
 *
 * Each retained track has one spool file under cacheDir/telegram-stream keyed
 * by media id. The spool covers [baseOffset, watermark) of the remote file:
 * open() positions the base at the player's requested offset, a background
 * prefetch coroutine appends 512 KB chunks (mtcute precise download) until the
 * end of the file, and read() blocks until the requested range is covered.
 * A read outside the covered window re-targets the spool (truncate + rebase),
 * so FLAC seeking works without downloading the whole file first — exactly how
 * TDLib's DownloadFile(offset=...) behaved.
 *
 * Concurrency: spool state (baseOffset/watermark/fileSize/failed) is volatile;
 * the per-spool mutex only guards state transitions (re-target, prefetch
 * start/restart) and is NEVER held across delays or network calls. File IO
 * always opens a fresh RandomAccessFile, so reads and appends can overlap.
 * Chunk appends are validated against the current watermark under the lock so
 * a chunk that raced a re-target is dropped instead of corrupting the spool.
 *
 * Pause/resume and replays don't re-download: the spool persists as long as
 * the track is among the MAX_RETAINED most recently played ones.
 */

package moe.rukamori.archivetune.telegram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal object TelegramStreamCache {
    private const val TAG = "TelegramStreamCache"

    private const val CHUNK_SIZE_BYTES = 512L * 1024

    private const val READ_WAIT_INTERVAL_MS = 150L

    private const val MAX_RETAINED = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Spool(
        val mediaId: TelegramMediaId,
        val file: File,
        @Volatile var fileSize: Long,
        @Volatile var baseOffset: Long,
        @Volatile var watermark: Long,
        @Volatile var failed: Boolean = false,
    ) {
        val lock = Mutex()

        @Volatile
        var prefetchJob: Job? = null
    }

    @Volatile
    private var cacheDir: File? = null

    private val spools = ConcurrentHashMap<String, Spool>()
    private val retentionOrder = LinkedHashSet<String>()
    private val retentionLock = Any()

    fun attach(context: android.content.Context) {
        if (cacheDir == null) {
            cacheDir = File(context.cacheDir, "telegram-stream").apply { mkdirs() }
        }
    }

    // ---------------------------------------------------------------------------
    // public API
    // ---------------------------------------------------------------------------

    /** Resolves the file size for a track, re-resolving the message if needed. */
    suspend fun resolveSize(mediaId: TelegramMediaId): Long {
        TelegramClient
            .resolveTrack(mediaId.chatId, mediaId.messageId)
            ?.takeIf { it.sizeBytes > 0 }
            ?.let { return it.sizeBytes }
        return TelegramClient.fileSize(mediaId.chatId, mediaId.messageId) ?: 0L
    }

    /**
     * Opens (or re-targets) the spool for [mediaId] at [position] and returns
     * the total file size (0 when unknown).
     */
    suspend fun open(
        mediaId: TelegramMediaId,
        position: Long,
    ): Long {
        val key = spoolKey(mediaId)
        val existing = spools[key]
        val size =
            if (existing != null && existing.fileSize > 0) {
                existing.fileSize
            } else {
                resolveSize(mediaId)
            }
        retain(key)

        if (existing != null) {
            existing.lock.withLock {
                existing.fileSize = size
                if (position !in existing.baseOffset until existing.watermark) {
                    reTargetLocked(existing, position)
                } else if (existing.prefetchJob?.isActive != true) {
                    startPrefetchLocked(existing)
                }
            }
            return size
        }

        val spool =
            Spool(
                mediaId = mediaId,
                file = spoolFile(key),
                fileSize = size,
                baseOffset = position,
                watermark = position,
            )
        spools[key] = spool
        spool.file.delete()
        spool.lock.withLock {
            startPrefetchLocked(spool)
        }
        return size
    }

    /**
     * Reads up to [length] bytes at [position]; blocks (bounded by the caller's
     * timeouts) while the prefetch catches up. Returns an empty array at EOF.
     */
    suspend fun read(
        mediaId: TelegramMediaId,
        position: Long,
        length: Long,
    ): ByteArray {
        val key = spoolKey(mediaId)
        retain(key)
        val spool = spools[key] ?: return openAndRead(mediaId, position, length)

        if (spool.fileSize in 1 until position) return ByteArray(0)

        while (true) {
            if (position in spool.baseOffset until spool.watermark) {
                val available = (spool.watermark - position).coerceAtMost(length)
                if (available <= 0) return ByteArray(0)
                val bytes = readFromFile(spool, position, available)
                if (bytes.isEmpty()) {
                    // spool file vanished or truncated: re-target once and retry
                    spool.lock.withLock { reTargetLocked(spool, position) }
                    continue
                }
                return bytes
            }

            spool.lock.withLock {
                if (spool.failed) {
                    spool.failed = false
                    reTargetLocked(spool, position)
                } else if (position >= spool.watermark) {
                    if (spool.prefetchJob?.isActive != true) {
                        startPrefetchLocked(spool)
                    }
                } else {
                    // position < baseOffset — rewind the spool
                    reTargetLocked(spool, position)
                }
            }
            delay(READ_WAIT_INTERVAL_MS)
        }
    }

    private suspend fun openAndRead(
        mediaId: TelegramMediaId,
        position: Long,
        length: Long,
    ): ByteArray {
        open(mediaId, position)
        return read(mediaId, position, length)
    }

    /**
     * Path of a fully downloaded spool — used by the format refiner to probe
     * bitrate/sample rate from the real file.
     */
    fun readyFilePath(
        chatId: Long,
        messageId: Long,
    ): String? {
        val key = spoolKey(TelegramMediaId(chatId = chatId, messageId = messageId))
        val spool = spools[key] ?: return null
        if (spool.fileSize <= 0 || spool.watermark < spool.fileSize) return null
        return spool.file.absolutePath
    }

    suspend fun cancelRetained() {
        val snapshot = synchronized(retentionLock) { retentionOrder.toList() }
        snapshot.forEach { releaseKey(it) }
    }

    // ---------------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------------

    private fun readFromFile(
        spool: Spool,
        position: Long,
        length: Long,
    ): ByteArray {
        if (!spool.file.isFile) return ByteArray(0)
        return runCatching {
            RandomAccessFile(spool.file, "r").use { raf ->
                raf.seek((position - spool.baseOffset).coerceAtLeast(0L))
                val buffer = ByteArray(length.toInt())
                var read = 0
                while (read < buffer.size) {
                    val n = raf.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read == buffer.size) buffer else buffer.copyOf(read)
            }
        }.getOrElse {
            Timber.tag(TAG).w(it, "spool read failed for %s", spool.mediaId)
            ByteArray(0)
        }
    }

    private fun reTargetLocked(
        spool: Spool,
        position: Long,
    ) {
        spool.prefetchJob?.cancel()
        spool.prefetchJob = null
        spool.failed = false
        spool.baseOffset = position
        spool.watermark = position
        runCatching { spool.file.delete() }
        startPrefetchLocked(spool)
    }

    private fun startPrefetchLocked(spool: Spool) {
        if (spool.prefetchJob?.isActive == true) return
        spool.prefetchJob =
            scope.launch {
                try {
                    while (true) {
                        val offset = spool.watermark
                        if (spool.fileSize in 1..offset) break
                        val wanted =
                            if (spool.fileSize > 0) {
                                minOf(CHUNK_SIZE_BYTES, spool.fileSize - offset)
                            } else {
                                CHUNK_SIZE_BYTES
                            }
                        val bytes =
                            TelegramClient.readFilePart(
                                chatId = spool.mediaId.chatId,
                                messageId = spool.mediaId.messageId,
                                offset = offset,
                                count = wanted,
                            )
                        if (bytes.isEmpty()) break
                        val accepted =
                            spool.lock.withLock {
                                // chunk raced a re-target? drop it instead of
                                // writing at a stale position
                                if (spool.watermark == offset && !spool.failed) {
                                    appendToSpool(spool, offset, bytes)
                                    spool.watermark = offset + bytes.size
                                    true
                                } else {
                                    false
                                }
                            }
                        if (!accepted) continue
                        if (spool.fileSize in 1..spool.watermark) break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "prefetch failed for %s", spool.mediaId)
                    spool.failed = true
                }
            }
    }

    private fun appendToSpool(
        spool: Spool,
        offset: Long,
        bytes: ByteArray,
    ) {
        RandomAccessFile(spool.file, "rw").use { raf ->
            raf.seek((offset - spool.baseOffset).coerceAtLeast(0L))
            raf.write(bytes)
        }
    }

    private fun retain(key: String) {
        val evicted =
            synchronized(retentionLock) {
                retentionOrder.remove(key)
                retentionOrder.add(key)
                val overflow = retentionOrder.size - MAX_RETAINED
                if (overflow <= 0) {
                    emptyList()
                } else {
                    val oldest = retentionOrder.take(overflow)
                    retentionOrder.removeAll(oldest.toSet())
                    oldest
                }
            }
        if (evicted.isEmpty()) return
        evicted.forEach { releaseKey(it) }
    }

    private fun releaseKey(key: String) {
        val spool = spools.remove(key) ?: return
        spool.prefetchJob?.cancel()
        runCatching { spool.file.delete() }
        Timber.tag(TAG).d("evicted spool %s", key)
    }

    private fun spoolKey(mediaId: TelegramMediaId): String {
        val raw = mediaId.encode()
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun spoolFile(key: String): File {
        val dir = cacheDir ?: File(TelegramClient.cacheDirectory(), "telegram-stream")
        dir.mkdirs()
        return File(dir, "$key.part")
    }
}
