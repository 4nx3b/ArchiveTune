/*
 * Copyright (C) 2024 Rukamori
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 */
package moe.rukamori.archivetune.download

enum class AudioContainer(
    val extension: String,
    val mimeType: String,
) {
    FLAC("flac", "audio/flac"),
    MP3("mp3", "audio/mpeg"),
    MP4("m4a", "audio/mp4"),
    OGG("ogg", "audio/ogg"),
    WEBM("webm", "audio/webm"),
    WAV("wav", "audio/wav"),
    ;

    companion object {

        const val PROBE_BYTES = 64

        fun detect(header: ByteArray): AudioContainer? {
            if (header.size < 12) return null

            fun matches(
                offset: Int,
                vararg ascii: Char,
            ): Boolean {
                if (offset + ascii.size > header.size) return false
                return ascii.withIndex().all { (i, c) -> header[offset + i] == c.code.toByte() }
            }

            return when {
                matches(0, 'f', 'L', 'a', 'C') -> FLAC
                matches(0, 'O', 'g', 'g', 'S') -> OGG

                matches(0, 'R', 'I', 'F', 'F') && matches(8, 'W', 'A', 'V', 'E') -> WAV

                header[0] == 0x1A.toByte() &&
                    header[1] == 0x45.toByte() &&
                    header[2] == 0xDF.toByte() &&
                    header[3] == 0xA3.toByte() -> WEBM

                matches(4, 'f', 't', 'y', 'p') -> MP4

                matches(0, 'I', 'D', '3') -> resolveAfterId3(header) ?: MP3

                header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0 -> MP3
                else -> null
            }
        }

        private fun resolveAfterId3(header: ByteArray): AudioContainer? {
            if (header.size < 10) return null
            val size =
                ((header[6].toInt() and 0x7F) shl 21) or
                    ((header[7].toInt() and 0x7F) shl 14) or
                    ((header[8].toInt() and 0x7F) shl 7) or
                    (header[9].toInt() and 0x7F)
            val start = 10 + size
            if (start + 8 > header.size) return null
            val isFtyp =
                header[start + 4] == 'f'.code.toByte() &&
                    header[start + 5] == 't'.code.toByte() &&
                    header[start + 6] == 'y'.code.toByte() &&
                    header[start + 7] == 'p'.code.toByte()
            return if (isFtyp) MP4 else null
        }

        fun extensionForMime(mimeType: String?): String =
            when {
                mimeType == null -> "m4a"
                mimeType.contains("flac", true) -> "flac"
                mimeType.contains("mpeg", true) || mimeType.contains("mp3", true) -> "mp3"
                mimeType.contains("ogg", true) || mimeType.contains("opus", true) -> "ogg"
                mimeType.contains("webm", true) -> "webm"
                mimeType.contains("wav", true) -> "wav"
                else -> "m4a"
            }
    }
}
