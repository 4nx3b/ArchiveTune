/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.telegram

import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.models.MediaMetadata

fun TelegramTrack.toMediaMetadata(channelTitle: String? = null): MediaMetadata {

    val metadata = lookupMetadata
    return MediaMetadata(
        id = mediaId,
        title = metadata.title,
        artists =
            listOf(
                MediaMetadata.Artist(
                    id = null,
                    name = metadata.artist ?: channelTitle ?: "Telegram",
                ),
            ),
        duration = durationSeconds,

        thumbnailUrl =
            telegramArtworkModel(thumbnailFileId, metadata.title, metadata.artist)
                ?: TelegramClient.cacheArtwork(
                    uniqueKey = fileUniqueId.ifEmpty { "$chatId-$messageId" },
                    data = albumCoverMinithumbnail,
                ),
    )
}

fun TelegramTrack.toFormatEntity(): FormatEntity {
    val extension = fileExtension(fileName)
    val mime =
        mimeType.substringBefore(";").ifBlank {
            when (extension) {
                "flac" -> "audio/flac"
                "wav", "wave" -> "audio/wav"
                "aiff", "aif" -> "audio/aiff"
                "alac" -> "audio/alac"
                "ape" -> "audio/x-ape"
                "wv" -> "audio/x-wavpack"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "ogg", "oga" -> "audio/ogg"
                "opus" -> "audio/opus"
                else -> "audio/*"
            }
        }
    val averageBitrate =
        if (durationSeconds > 0 && sizeBytes > 0) {
            (sizeBytes * 8 / durationSeconds).toInt().coerceAtLeast(0)
        } else {
            0
        }
    return FormatEntity(
        id = mediaId,
        itag = -1,
        mimeType = mime,
        codecs = extension,
        bitrate = averageBitrate,
        sampleRate = null,
        contentLength = sizeBytes,
        loudnessDb = null,
        perceptualLoudnessDb = null,
        playbackUrl = null,
    )
}
