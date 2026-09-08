/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Parsers for the JSON protocol spoken with the mtcute host bundle
 * (see scripts/telegram-js/host/main.ts — every handler's return shape).
 * Top-level extensions so the whole telegram package can use them directly.
 */

package moe.rukamori.archivetune.telegram

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal val tgJson = Json { ignoreUnknownKeys = true }

internal fun JsonObject.tgLong(key: String): Long = this[key]?.jsonPrimitive?.longOrNull ?: 0L

internal fun JsonObject.tgInt(key: String): Int = this[key]?.jsonPrimitive?.longOrNull?.toInt() ?: 0

internal fun JsonObject.tgString(key: String): String = this[key]?.jsonPrimitive?.content ?: ""

internal fun JsonObject.tgBool(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: false

internal fun JsonObject.tgStringOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() && it != "null" }

internal fun JsonObject.tgBytesB64(key: String): ByteArray? =
    tgStringOrNull(key)?.let { value ->
        runCatching { Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING) }
            .recoverCatching { Base64.decode(value, Base64.DEFAULT) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

internal fun JsonObject.tgObj(key: String): JsonObject? = this[key]?.jsonObject

internal fun JsonObject.tgObjArray(key: String): List<JsonObject> =
    runCatching {
        this[key]?.jsonArray?.mapNotNull { it.jsonObject }
    }.getOrNull()?.filterNotNull() ?: emptyList()

internal fun parseTgObject(raw: String): JsonObject = tgJson.parseToJsonElement(raw).jsonObject

internal fun parseTgTrack(raw: JsonObject?): TelegramTrack? {
    if (raw == null) return null
    val chatId = raw.tgLong("chatId")
    val messageId = raw.tgLong("messageId")
    if (chatId == 0L || messageId == 0L) return null
    return TelegramTrack(
        chatId = chatId,
        messageId = messageId,
        fileUniqueId = raw.tgString("uniqueFileId"),
        docId = raw.tgString("docId"),
        accessHash = raw.tgString("accessHash"),
        fileReference = raw.tgString("fileReference"),
        dcId = raw.tgInt("dcId"),
        title = raw.tgString("title"),
        performer = raw.tgStringOrNull("performer"),
        fileName = raw.tgString("fileName"),
        mimeType = raw.tgString("mimeType"),
        durationSeconds = raw.tgInt("durationSeconds"),
        sizeBytes = raw.tgLong("sizeBytes"),
        dateSeconds = raw.tgInt("dateSeconds"),
        albumCoverMinithumbnail = raw.tgBytesB64("albumCoverStripped")?.let(TgStrippedJpeg::reconstruct),
        hasThumbnail = raw.tgBool("hasThumbnail"),
    )
}

internal fun parseTgChannel(raw: JsonObject): TelegramChannel? {
    val chatId = raw.tgLong("chatId")
    if (chatId == 0L) return null
    return TelegramChannel(
        chatId = chatId,
        title = raw.tgString("title"),
        username = raw.tgStringOrNull("username"),
        memberCount = raw.tgInt("memberCount"),
        isBroadcastChannel = raw.tgBool("isBroadcastChannel"),
        photoMinithumbnail = raw.tgBytesB64("photoStripped")?.let(TgStrippedJpeg::reconstruct),
        photoDownloadable = raw.tgBool("photoDownloadable"),
    )
}

internal fun parseTgAccount(raw: JsonObject?): TelegramAccount? {
    if (raw == null) return null
    return TelegramAccount(
        id = raw.tgLong("id"),
        firstName = raw.tgString("firstName"),
        lastName = raw.tgStringOrNull("lastName"),
        username = raw.tgStringOrNull("username"),
        phoneNumber = raw.tgStringOrNull("phoneNumber"),
        isBot = raw.tgBool("isBot"),
    )
}
