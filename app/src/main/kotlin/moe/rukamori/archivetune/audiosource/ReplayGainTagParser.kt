/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.audiosource

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream

/**
 * Parses ReplayGain / R128 loudness-correction tags from local audio files so the
 * playback normalization pipeline can level-lossless tracks the same way YouTube
 * tracks are leveled from their API loudness metadata.
 *
 * Supported containers:
 *  - FLAC  (Vorbis comments in METADATA_BLOCK type 4)
 *  - Ogg   (Vorbis comment header — searched in the leading pages)
 *  - MP3   (ID3v2.3/2.4 TXXX frames)
 *
 * Recognized tags (values in dB, e.g. "-6.53 dB" or "-6.53"):
 *  - REPLAYGAIN_TRACK_GAIN / REPLAYGAIN_ALBUM_GAIN   (reference ~ -18 LUFS, RG2 / 89 dB RG1)
 *  - R128_TRACK_GAIN / R128_ALBUM_GAIN               (reference -23 LUFS, EBU R128; Q7.8 fixed-point)
 *
 * All values are normalized to the ReplayGain 2.0 scale (reference -18 LUFS):
 * R128 values get +5 dB so both scales can feed the same loudness math.
 */
object ReplayGainTagParser {

    /** Reference loudness of ReplayGain 2.0 tags, in LUFS. */
    const val REPLAYGAIN_REFERENCE_LUFS = -18.0

    private const val MAX_HEADER_BYTES = 1 shl 20 // 1 MiB cap — tags live at the head

    data class ReplayGain(
        val trackDb: Double?,
        val albumDb: Double?,
    ) {
        val hasAny: Boolean get() = trackDb != null || albumDb != null
    }

    fun parse(resolver: ContentResolver, uri: Uri, displayName: String?): ReplayGain {
        val result =
            runCatching {
                resolver.openInputStream(uri)?.use { stream ->
                    val head = ByteArray(MAX_HEADER_BYTES)
                    var filled = 0
                    while (filled < head.size) {
                        val read = stream.read(head, filled, head.size - filled)
                        if (read <= 0) break
                        filled += read
                    }
                    if (filled < 8) return@use null
                    parseBytes(head.copyOf(filled))
                }
            }.getOrNull()
        return result ?: ReplayGain(trackDb = null, albumDb = null)
    }

    internal fun parseBytes(bytes: ByteArray): ReplayGain? {
        return when {
            bytes.size > 4 &&
                bytes[0] == 'f'.code.toByte() &&
                bytes[1] == 'L'.code.toByte() &&
                bytes[2] == 'a'.code.toByte() &&
                bytes[3] == 'C'.code.toByte() -> parseFlac(bytes)

            bytes.size > 3 &&
                bytes[0] == 'I'.code.toByte() &&
                bytes[1] == 'D'.code.toByte() &&
                bytes[2] == '3'.code.toByte() -> parseId3(bytes)

            bytes.size > 4 &&
                bytes[0] == 'O'.code.toByte() &&
                bytes[1] == 'g'.code.toByte() &&
                bytes[2] == 'g'.code.toByte() &&
                bytes[3] == 'S'.code.toByte() -> parseOgg(bytes)

            else -> null
        }
    }

    // ── FLAC ─────────────────────────────────────────────────────────────────

    private fun parseFlac(bytes: ByteArray): ReplayGain? {
        var offset = 4 // "fLaC"
        val comments = mutableListOf<Pair<String, String>>()
        var blocks = 0
        while (offset + 4 <= bytes.size && blocks < 64) {
            val header = bytes[offset].toInt() and 0xFF
            val isLast = (header and 0x80) != 0
            val blockType = header and 0x7F
            val length =
                ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
                    ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
                    (bytes[offset + 3].toLong() and 0xFF)
            offset += 4
            if (offset + length > bytes.size) break
            if (blockType == 4 && length > 8) {
                parseVorbisComments(bytes, offset, length.toInt())?.let(comments::addAll)
            }
            if (isLast) break
            offset += length.toInt()
            blocks++
        }
        return fromComments(comments)
    }

    // ── Ogg (Vorbis / Opus comment header) ───────────────────────────────────

    private fun parseOgg(bytes: ByteArray): ReplayGain? {
        // The Vorbis comment header lives inside the first few Ogg pages. Instead of
        // implementing full page framing, scan the leading bytes for the comment
        // header signature: "\x03vorbis" (Vorbis) or "OpusTags" (Opus).
        val signatures = listOf(byteArrayOf(3, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte()), byteArrayOf('O'.code.toByte(), 'p'.code.toByte(), 'u'.code.toByte(), 's'.code.toByte()))
        for (signature in signatures) {
            val index = indexOf(bytes, signature, limit = 64 * 1024) ?: continue
            val start =
                if (signature[0] == 3.toByte()) index + 7 else index + 8 // skip "OpusTags"
            val available = (bytes.size - start).coerceAtMost(256 * 1024)
            parseVorbisComments(bytes, start, available)?.let { return fromComments(it) }
        }
        return null
    }

    // ── ID3v2 (MP3) ──────────────────────────────────────────────────────────

    private fun parseId3(bytes: ByteArray): ReplayGain? {
        val majorVersion = bytes[3].toInt() and 0xFF
        val flags = bytes[5].toInt() and 0xFF
        val tagSize = syncSafeInt(bytes, 7) ?: return null
        val hasExtendedHeader = (flags and 0x40) != 0
        var offset = 10
        var end = (10 + tagSize).coerceAtMost(bytes.size)

        if (hasExtendedHeader) {
            val extSize =
                if (majorVersion == 4) syncSafeInt(bytes, offset) ?: return null
                else intBE(bytes, offset) ?: return null
            offset += 4 + (extSize - 4).coerceAtLeast(0)
        }

        val comments = mutableListOf<Pair<String, String>>()
        while (offset + 10 <= end) {
            val frameId = String(bytes, offset, 4, Charsets.ISO_8859_1)
            if (frameId.isBlank() || frameId[0] == '\u0000') break
            val frameSize =
                if (majorVersion == 4) syncSafeInt(bytes, offset + 4) ?: break
                else intBE(bytes, offset + 4) ?: break
            if (frameSize <= 0 || offset + 10 + frameSize > bytes.size) break
            val dataStart = offset + 10
            if (frameId == "TXXX" && frameSize > 2) {
                val encoding = bytes[dataStart].toInt() and 0xFF
                var cursor = dataStart + 1
                // Description (null-terminated in the frame encoding)
                val descriptionEnd = findTerminator(bytes, cursor, end, encoding) ?: run { offset = dataStart + frameSize; continue }
                val description = decodeString(bytes, cursor, descriptionEnd - cursor, encoding)
                cursor =
                    when (encoding) {
                        0, 3 -> descriptionEnd + 1
                        else -> descriptionEnd + 2
                    }
                if (cursor < dataStart + frameSize) {
                    val valueLen = (dataStart + frameSize - cursor).coerceAtLeast(0)
                    val value = decodeString(bytes, cursor, valueLen, encoding)
                    comments += description.trim().uppercase() to value.trim()
                }
            }
            offset = dataStart + frameSize
        }
        return fromComments(comments)
    }

    // ── Vorbis comment list ──────────────────────────────────────────────────

    private fun parseVorbisComments(bytes: ByteArray, start: Int, length: Int): List<Pair<String, String>>? {
        var offset = start
        val end = (start + length).coerceAtMost(bytes.size)
        if (offset + 4 > end) return null
        val vendorLength = leInt(bytes, offset) ?: return null
        offset += 4
        if (vendorLength < 0 || offset + vendorLength > end) return null
        offset += vendorLength
        if (offset + 4 > end) return null
        val count = leInt(bytes, offset) ?: return null
        offset += 4
        if (count < 0 || count > 10_000) return null
        val comments = mutableListOf<Pair<String, String>>()
        repeat(count) {
            if (offset + 4 > end) return comments
            val commentLength = leInt(bytes, offset) ?: return comments
            offset += 4
            if (commentLength <= 0 || offset + commentLength > end) return comments
            val text = String(bytes, offset, commentLength, Charsets.UTF_8)
            offset += commentLength
            val eq = text.indexOf('=')
            if (eq > 0) {
                comments += text.substring(0, eq).trim().uppercase() to text.substring(eq + 1).trim()
            }
        }
        return comments
    }

    // ── value extraction ─────────────────────────────────────────────────────

    private fun fromComments(comments: List<Pair<String, String>>): ReplayGain? {
        if (comments.isEmpty()) return null
        var track: Double? = null
        var album: Double? = null
        for ((key, value) in comments) {
            if (track == null && (key == "REPLAYGAIN_TRACK_GAIN" || key == "TRACK_GAIN")) {
                track = parseGainDb(value)
            }
            if (album == null && (key == "REPLAYGAIN_ALBUM_GAIN" || key == "ALBUM_GAIN")) {
                album = parseGainDb(value)
            }
            // R128 tags use Q7.8 fixed point (e.g. -1132 = -8.84 dB, ref -23 LUFS).
            if (track == null && key == "R128_TRACK_GAIN") {
                track = parseQ78(value)?.plus(R128_TO_RG2_OFFSET_DB)
            }
            if (album == null && key == "R128_ALBUM_GAIN") {
                album = parseQ78(value)?.plus(R128_TO_RG2_OFFSET_DB)
            }
        }
        if (track == null && album == null) return null
        return ReplayGain(trackDb = track, albumDb = album)
    }

    private const val R128_TO_RG2_OFFSET_DB = 5.0 // -23 LUFS ref -> -18 LUFS ref

    private fun parseGainDb(value: String): Double? {
        // "-6.53 dB" / "-6.53dB" / "-6.53"
        val numeric = value.substringBefore("dB").trim()
        val parsed = numeric.toDoubleOrNull()
        return parsed?.takeIf { it.isFinite() && it in -60.0..60.0 }
    }

    private fun parseQ78(value: String): Double? {
        val raw = value.trim().toDoubleOrNull() ?: return null
        if (!raw.isFinite() || raw !in -23040.0..23040.0) return null
        return raw / 256.0
    }

    // ── byte helpers ─────────────────────────────────────────────────────────

    private fun syncSafeInt(bytes: ByteArray, offset: Int): Int? {
        if (offset + 4 > bytes.size) return null
        val b0 = bytes[offset].toInt() and 0x7F
        val b1 = bytes[offset + 1].toInt() and 0x7F
        val b2 = bytes[offset + 2].toInt() and 0x7F
        val b3 = bytes[offset + 3].toInt() and 0x7F
        return (b0 shl 21) or (b1 shl 14) or (b2 shl 7) or b3
    }

    private fun intBE(bytes: ByteArray, offset: Int): Int? {
        if (offset + 4 > bytes.size) return null
        return (bytes[offset].toInt() and 0xFF shl 24) or
            (bytes[offset + 1].toInt() and 0xFF shl 16) or
            (bytes[offset + 2].toInt() and 0xFF shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int? {
        if (offset + 4 > bytes.size) return null
        return (bytes[offset + 3].toInt() and 0xFF shl 24) or
            (bytes[offset + 2].toInt() and 0xFF shl 16) or
            (bytes[offset + 1].toInt() and 0xFF shl 8) or
            (bytes[offset].toInt() and 0xFF)
    }

    private fun findTerminator(bytes: ByteArray, from: Int, to: Int, encoding: Int): Int? {
        if (encoding == 1 || encoding == 2) {
            var i = from
            while (i + 1 < to) {
                if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0) return i
                i += 2
            }
            return null
        }
        for (i in from until to) {
            if (bytes[i].toInt() == 0) return i
        }
        return null
    }

    private fun decodeString(bytes: ByteArray, from: Int, length: Int, encoding: Int): String {
        if (length <= 0) return ""
        val safeLength = length.coerceAtMost(bytes.size - from)
        return when (encoding) {
            1 -> String(bytes, from, safeLength, Charsets.UTF_16LE)
            2 -> String(bytes, from, safeLength, Charsets.UTF_16BE)
            4 -> String(bytes, from, safeLength, Charsets.UTF_8) // id3v2.4 allows UTF-8 as 4? not standard; tolerated
            else -> String(bytes, from, safeLength, Charsets.ISO_8859_1)
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, limit: Int): Int? {
        val searchLimit = haystack.size.coerceAtMost(limit)
        outer@ for (i in 0..searchLimit - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return null
    }
}
