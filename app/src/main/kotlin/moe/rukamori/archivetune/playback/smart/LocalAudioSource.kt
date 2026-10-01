/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Automix analysis pipeline ported from BitChord
 * (https://github.com/kushagrasinghx/BitChord), which derives it from
 * Orchard (https://github.com/SFG5453/Orchard). Orchard's original source
 * is licensed AGPL-3.0-or-later; per AGPLv3 section 13 this file is
 * combined into ArchiveTune -- a GPL-3.0-or-later work -- and remains
 * itself governed by the AGPLv3 as part of that combination.
 */

package moe.rukamori.archivetune.playback.smart
import android.content.ContentResolver
import android.media.MediaDataSource
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import java.io.IOException
import java.util.Locale

internal object LocalAudioSource {
    private const val TAG = "BitChordLocalAudio"

    fun isLocal(uri: Uri): Boolean = when (uri.scheme?.lowercase(Locale.ROOT)) {
        ContentResolver.SCHEME_FILE, ContentResolver.SCHEME_CONTENT -> true
        else -> false
    }

    fun open(resolver: ContentResolver, uri: Uri): MediaDataSource? {
        val descriptor = runCatching { resolver.openFileDescriptor(uri, "r") }
            .onFailure { Log.w(TAG, "Cannot open $uri for analysis", it) }
            .getOrNull() ?: return null
        val size = descriptor.statSize
        if (size <= 0L) {

            Log.w(TAG, "Skipping $uri for analysis: not a seekable file")
            runCatching { descriptor.close() }
            return null
        }
        return Source(descriptor, size)
    }

    private class Source(
        private val descriptor: ParcelFileDescriptor,
        private val length: Long,
    ) : MediaDataSource() {
        override fun getSize(): Long = length

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0 || position >= length) return -1
            if (size <= 0) return 0

            val wanted = minOf(size.toLong(), length - position).toInt()
            return try {

                Os.pread(descriptor.fileDescriptor, buffer, offset, wanted, position)

                    .takeIf { it > 0 } ?: -1
            } catch (error: ErrnoException) {
                throw IOException("pread of $length bytes at $position failed", error)
            }
        }

        override fun close() {
            runCatching { descriptor.close() }
        }
    }
}
