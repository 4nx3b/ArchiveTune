/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Plain models for the Telegram channel browser plus the lossless-format detection used to filter
 * channel content. Kept free of Android/mtcute imports so the detection logic is unit-testable.
 *
 * Track addressing changed with the TDLib -> mtcute swap: TDLib-local file ids
 * are gone; a track now carries the server-stable unique file id
 * ("<docId>:<dcId>") plus the raw document coordinates (docId / accessHash /
 * fileReference / dcId) needed to build MTProto download locations.
 */

package moe.rukamori.archivetune.telegram

import java.util.Locale

data class TelegramChannel(
    val chatId: Long,
    val title: String,
    val username: String?,
    val memberCount: Int,
    val isBroadcastChannel: Boolean,
    val photoMinithumbnail: ByteArray?,

    // mtcute era: chat photos are resolved by chat id (inputPeerPhotoFileLocation)
    val photoDownloadable: Boolean = false,
) {
    override fun equals(other: Any?): Boolean = other is TelegramChannel && other.chatId == chatId

    override fun hashCode(): Int = chatId.hashCode()
}

data class TelegramTrack(
    val chatId: Long,
    val messageId: Long,
    val fileUniqueId: String,
    val docId: String,
    val accessHash: String,
    val fileReference: String,
    val dcId: Int,
    val title: String,
    val performer: String?,
    val fileName: String,
    val mimeType: String,
    val durationSeconds: Int,
    val sizeBytes: Long,
    val dateSeconds: Int,
    val albumCoverMinithumbnail: ByteArray?,

    val hasThumbnail: Boolean = false,
) {
    val mediaId: String
        get() =
            TelegramMediaId(
                chatId = chatId,
                messageId = messageId,
                fileUniqueId = fileUniqueId,
            ).encode()

    val isLossless: Boolean
        get() = isLosslessAudio(mimeType, fileName)

    val displayTitle: String
        get() =
            title.ifBlank {
                fileName.substringBeforeLast('.').ifBlank { fileName }
            }

    val lookupMetadata: TelegramTrackMetadata
        get() = deriveTrackMetadata(tagTitle = title, tagPerformer = performer, fileName = fileName)

    override fun equals(other: Any?): Boolean =
        other is TelegramTrack && other.chatId == chatId && other.messageId == messageId

    override fun hashCode(): Int = (chatId * 31 + messageId).hashCode()
}

data class TelegramAudioPage(
    val tracks: List<TelegramTrack>,
    val nextFromMessageId: Long,
)

data class TelegramAccount(
    val id: Long,
    val firstName: String,
    val lastName: String?,
    val username: String?,
    val phoneNumber: String?,
    val isBot: Boolean,
) {
    val displayName: String
        get() = listOfNotNull(firstName.takeIf { it.isNotBlank() }, lastName?.takeIf { it.isNotBlank() })
            .joinToString(" ")
            .ifBlank { username ?: "" }
}

data class TelegramBotInfo(
    val chatId: Long,
    val userId: Long,
    val firstName: String,
    val isBot: Boolean,
)

private val LOSSLESS_MIME_TYPES =
    setOf(
        "audio/flac",
        "audio/x-flac",
        "audio/wav",
        "audio/x-wav",
        "audio/wave",
        "audio/vnd.wave",
        "audio/aiff",
        "audio/x-aiff",
        "audio/x-ape",
        "audio/x-monkeys-audio",
        "audio/x-wavpack",
        "audio/wavpack",
        "audio/x-tak",
        "audio/x-tta",
        "audio/x-dsd",
        "audio/dsd",
        "audio/x-dsf",
        "audio/alac",
    )

private val LOSSLESS_EXTENSIONS =
    setOf(
        "flac",
        "wav",
        "wave",
        "aiff",
        "aif",
        "ape",
        "alac",
        "wv",
        "tak",
        "tta",
        "dsf",
        "dff",
        "shn",
    )

private val AUDIO_EXTENSIONS =
    LOSSLESS_EXTENSIONS +
        setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wma", "mka")

fun fileExtension(fileName: String): String = fileName.substringAfterLast('.', "").lowercase(Locale.US)

fun isLosslessAudio(
    mimeType: String,
    fileName: String,
): Boolean {
    val mime = mimeType.trim().lowercase(Locale.US)
    if (mime in LOSSLESS_MIME_TYPES) return true
    return fileExtension(fileName) in LOSSLESS_EXTENSIONS
}

fun isAudioDocument(
    mimeType: String,
    fileName: String,
): Boolean {
    val mime = mimeType.trim().lowercase(Locale.US)
    if (mime.startsWith("audio/")) return true
    return fileExtension(fileName) in AUDIO_EXTENSIONS
}

data class TelegramTrackMetadata(
    val title: String,
    val artist: String?,
)

private val NOISE_SUFFIX_REGEX =
    Regex("\\((?:official|lyric|audio|video|hd|hq|visualizer)[^)]*\\)", RegexOption.IGNORE_CASE)
private val BRACKET_TAG_REGEX = Regex("\\[[^\\]]*\\]")
private val LEADING_TRACK_NUMBER_REGEX = Regex("^\\s*\\d{1,3}\\s*[.\\-]\\s*")
private val WHITESPACE_REGEX = Regex("\\s+")

fun cleanTrackName(raw: String): String =
    raw
        .replace(NOISE_SUFFIX_REGEX, " ")
        .replace(BRACKET_TAG_REGEX, " ")
        .replace(LEADING_TRACK_NUMBER_REGEX, "")
        .replace(WHITESPACE_REGEX, " ")
        .trim()

fun deriveTrackMetadata(
    tagTitle: String,
    tagPerformer: String?,
    fileName: String,
): TelegramTrackMetadata {
    val performer = tagPerformer?.trim()?.takeIf(String::isNotEmpty)
    if (tagTitle.isNotBlank()) {
        return TelegramTrackMetadata(title = cleanTrackName(tagTitle).ifBlank { tagTitle.trim() }, artist = performer)
    }
    val base = cleanTrackName(fileName.substringBeforeLast('.').ifBlank { fileName })
    if (performer == null) {
        val separators = listOf(" - ", " – ", " — ")
        for (separator in separators) {
            val index = base.indexOf(separator)
            if (index > 0 && index < base.length - separator.length) {
                val artist = base.substring(0, index).trim()
                val title = base.substring(index + separator.length).trim()
                if (artist.isNotEmpty() && title.isNotEmpty()) {
                    return TelegramTrackMetadata(title = title, artist = artist)
                }
            }
        }
    }
    return TelegramTrackMetadata(title = base.ifBlank { fileName }, artist = performer)
}
