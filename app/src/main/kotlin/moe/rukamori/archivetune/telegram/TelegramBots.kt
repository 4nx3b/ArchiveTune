/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Persisted model + JSON codec for the "Telegram bots" feature. Bots are added by the user via
 * a paste-a-link field on the bots management screen; once added they can be reopened directly
 * (no need to re-resolve the @username each time). We store them as a JSON array under a single
 * DataStore preference key so backups and migrations come for free.
 *
 * The chat id is resolved lazily from the username on first use (see TelegramBotClient.resolveBot)
 * and then cached, so a stored bot keeps working even if Telegram's @-lookup is rate-limited at
 * boot. The model intentionally has no Android imports so it can be unit-tested in pure JVM.
 */

package moe.rukamori.archivetune.telegram

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

data class TelegramBot(

    val id: String,

    val username: String,

    val chatId: Long,

    val title: String,

    val addedAtMs: Long,

    val photoMinithumbnail: ByteArray? = null,
) {
    val displayHandle: String
        get() = "@$username"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TelegramBot) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

object TelegramBotCodec {
    private const val KEY_ID = "id"
    private const val KEY_USERNAME = "username"
    private const val KEY_CHAT_ID = "chatId"
    private const val KEY_TITLE = "title"
    private const val KEY_ADDED_AT = "addedAtMs"
    private const val KEY_PHOTO_MINI = "photoMini"

    fun encode(bots: List<TelegramBot>): String {
        val arr = JSONArray()
        for (bot in bots) {
            arr.put(
                JSONObject().apply {
                    put(KEY_ID, bot.id)
                    put(KEY_USERNAME, bot.username)
                    put(KEY_CHAT_ID, bot.chatId)
                    put(KEY_TITLE, bot.title)
                    put(KEY_ADDED_AT, bot.addedAtMs)
                    bot.photoMinithumbnail?.let {
                        put(KEY_PHOTO_MINI, Base64.getEncoder().encodeToString(it))
                    }
                },
            )
        }
        return arr.toString()
    }

    fun decode(raw: String): List<TelegramBot> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val photoMini =
                    obj.optString(KEY_PHOTO_MINI).takeIf { it.isNotBlank() }?.let {
                        runCatching { Base64.getDecoder().decode(it) }.getOrNull()
                    }
                TelegramBot(
                    id = obj.optString(KEY_ID).ifBlank { UUID.randomUUID().toString() },
                    username = obj.optString(KEY_USERNAME).trim(),
                    chatId = obj.optLong(KEY_CHAT_ID, 0L),
                    title = obj.optString(KEY_TITLE).ifBlank { "" },
                    addedAtMs = obj.optLong(KEY_ADDED_AT, 0L),
                    photoMinithumbnail = photoMini,
                )
            }.filter { it.username.isNotBlank() }
        }.getOrDefault(emptyList())
    }
}

fun parseBotUsername(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val linkMatch = Regex(
        "(?:https?://)?t(?:elegram)?\\.me/([A-Za-z][A-Za-z0-9_]{3,})",
        RegexOption.IGNORE_CASE,
    ).find(trimmed)
    val fromLink = linkMatch?.groupValues?.get(1)
    if (fromLink != null) return fromLink.lowercase()

    if (trimmed.startsWith("@")) {
        val u = trimmed.removePrefix("@")
        return u.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{3,}")) }?.lowercase()
    }

    return trimmed
        .takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{3,}")) }
        ?.lowercase()
}
