/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * mtcute-host wrapper for the "Telegram bots" feature. Bots are private 1:1
 * chats with a Telegram bot account. The user pastes a song link, the app sends
 * it as a text message to the bot, and then listens on a per-chat SharedFlow of
 * incoming messages (driven by the mtcute host's new_message events routed
 * through TelegramClient) until the bot replies with an audio/document message
 * — that reply becomes a playable [TelegramTrack].
 *
 * Forwarding to the user's own channel uses server-side message forwarding so
 * the audio bytes aren't re-uploaded — this matches the user's spec: "if I add
 * it to my telegram playlist the song should also get forwarded to my own
 * channel automatically".
 *
 * A 60s timeout caps how long we wait for a bot reply. Bots that stream "a lot
 * of files" (e.g. a Spotify-album link returns one message per track) all
 * arrive on the same SharedFlow and are collected into the result list.
 *
 * Inline-keyboard support: many music bots reply to a song link with a message
 * that has an inline keyboard ("Choose quality: ALAC / AAC / Cancel") instead
 * of the audio directly. The user must tap one of the buttons to actually
 * trigger the audio download. This file exposes [collectBotReplies] which
 * returns BOTH audio tracks and inline-keyboard prompts, and
 * [clickInlineButton] which requests the callback answer — after which the bot
 * sends the actual audio file, which the caller collects with another
 * [collectBotReplies] cycle.
 */

package moe.rukamori.archivetune.telegram

import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

object TelegramBotClient {
    private const val TAG = "TelegramBotClient"

    val BOT_REPLY_TIMEOUT = 60.seconds

    private const val POST_REPLY_GRACE_MS = 5_000L

    private val chatMessageFlows = ConcurrentHashMap<Long, MutableSharedFlow<TelegramIncomingMessage>>()

    fun messagesForChat(chatId: Long): SharedFlow<TelegramIncomingMessage> =
        chatMessageFlows.getOrPut(chatId) {
            MutableSharedFlow(replay = 0, extraBufferCapacity = 64)
        }.asSharedFlow()

    /** Routed from TelegramClient's subscription to the mtcute host's new_message events. */
    internal fun onNewMessageEvent(payloadJson: String) {
        val payload =
            runCatching { parseTgObject(payloadJson) }.getOrNull() ?: return
        val chatId = payload.tgLong("chatId")
        val messageId = payload.tgLong("messageId")
        if (chatId == 0L || messageId == 0L) return
        val message =
            TelegramIncomingMessage(
                chatId = chatId,
                messageId = messageId,
                track = parseTgTrack(payload.tgObj("track")),
                prompt = parsePrompt(payload.tgObj("prompt")),
            )
        if (message.track == null && message.prompt == null) return
        chatMessageFlows[chatId]?.tryEmit(message)
    }

    fun forgetChat(chatId: Long) {
        chatMessageFlows.remove(chatId)
    }

    suspend fun resolveBot(username: String): TelegramBotInfo? {
        val cleaned = username.removePrefix("@").trim().lowercase()
        if (cleaned.isEmpty()) return null
        val result =
            runCatching {
                TelegramClient.call(
                    "resolveBot",
                    buildJsonObject {
                        put("username", cleaned)
                    }.toString(),
                )
            }.getOrNull() ?: return null
        val info =
            TelegramBotInfo(
                chatId = result.tgLong("chatId"),
                userId = result.tgLong("userId"),
                firstName = result.tgString("firstName"),
                isBot = result.tgBool("isBot"),
            )
        if (info.chatId == 0L) return null
        if (!info.isBot) {
            Timber.tag(TAG).w("resolveBot: @%s is a user, not a bot — refusing", cleaned)
            return null
        }
        return info
    }

    /** Stripped profile photo of the bot (patched to a displayable JPEG), if any. */
    suspend fun resolveBotPhoto(username: String): ByteArray? {
        val cleaned = username.removePrefix("@").trim().lowercase()
        if (cleaned.isEmpty()) return null
        val result =
            runCatching {
                TelegramClient.call(
                    "resolveBot",
                    buildJsonObject {
                        put("username", cleaned)
                    }.toString(),
                )
            }.getOrNull() ?: return null
        return result.tgBytesB64("photoStripped")?.let(TgStrippedJpeg::reconstruct)
    }

    suspend fun sendTextMessage(
        chatId: Long,
        text: String,
    ): Long {
        val result =
            TelegramClient.call(
                "sendTextMessage",
                buildJsonObject {
                    put("chatId", chatId)
                    put("text", text)
                }.toString(),
            )
        return result.tgLong("messageId")
    }

    suspend fun fetchBotCommands(chatId: Long): List<TelegramBotCommand> =
        runCatching {
            val result =
                TelegramClient.call(
                    "fetchBotCommands",
                    buildJsonObject {
                        put("chatId", chatId)
                    }.toString(),
                )
            result.tgObjArray("commands").map { cmd ->
                TelegramBotCommand(
                    command = cmd.tgString("command"),
                    description = cmd.tgString("description"),
                )
            }
        }.onFailure { e ->
            Timber.tag(TAG).w(e, "fetchBotCommands: failed for chatId=%s", chatId)
        }.getOrDefault(emptyList())

    suspend fun collectAudioReplies(
        chatId: Long,
        afterMessageId: Long,
        expectedCount: Int,
    ): List<TelegramTrack> {
        val channel = Channel<TelegramTrack>(Channel.UNLIMITED)
        val collectJob = CoroutineScope(coroutineContext).launch {
            try {
                messagesForChat(chatId).collect { message ->
                    if (message.messageId <= afterMessageId) return@collect
                    val track = message.track ?: return@collect
                    channel.send(track)
                }
            } finally {
                channel.close()
            }
        }

        try {
            val first = withTimeoutOrNull(BOT_REPLY_TIMEOUT) { channel.receive() }
                ?: return emptyList()
            if (expectedCount == 1) return listOf(first)

            val results = mutableListOf(first)
            var graceDeadlineMs = System.currentTimeMillis() + POST_REPLY_GRACE_MS
            while (true) {
                val remainingMs = (graceDeadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
                val next = withTimeoutOrNull(remainingMs) { channel.receive() } ?: break
                results += next
                if (expectedCount > 0 && results.size >= expectedCount) break
                graceDeadlineMs = System.currentTimeMillis() + POST_REPLY_GRACE_MS
            }
            return results
        } finally {
            collectJob.cancel()
        }
    }

    suspend fun collectBotReplies(
        chatId: Long,
        afterMessageId: Long,
    ): List<BotReply> {
        val channel = Channel<BotReply>(Channel.UNLIMITED)
        val collectJob = CoroutineScope(coroutineContext).launch {
            try {
                messagesForChat(chatId).collect { message ->
                    if (message.messageId <= afterMessageId) return@collect
                    val reply = message.toBotReply() ?: return@collect
                    channel.send(reply)
                }
            } finally {
                channel.close()
            }
        }

        try {
            val first = withTimeoutOrNull(BOT_REPLY_TIMEOUT) { channel.receive() }
                ?: return emptyList()

            val results = mutableListOf(first)
            var graceDeadlineMs = System.currentTimeMillis() + POST_REPLY_GRACE_MS
            while (true) {
                val remainingMs = (graceDeadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
                val next = withTimeoutOrNull(remainingMs) { channel.receive() } ?: break
                results += next
                graceDeadlineMs = System.currentTimeMillis() + POST_REPLY_GRACE_MS
            }
            return results
        } finally {
            collectJob.cancel()
        }
    }

    private fun parsePrompt(raw: JsonObject?): TelegramBotPrompt? {
        if (raw == null) return null
        val rows =
            raw.tgObjArray("rows").mapNotNull { row ->
                val buttons =
                    row.tgObjArray("buttons").mapNotNull { button ->
                        val text = button.tgString("text").takeIf(String::isNotBlank) ?: return@mapNotNull null
                        val callbackB64 = button.tgStringOrNull("callbackData")
                        val url = button.tgStringOrNull("url")
                        if (callbackB64 == null && url == null) return@mapNotNull null
                        TelegramBotPromptButton(
                            text = text,
                            callbackData = callbackB64?.let {
                                runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
                            },
                            url = url,
                        )
                    }
                if (buttons.isEmpty()) null else buttons
            }
        if (rows.isEmpty()) return null
        return TelegramBotPrompt(
            chatId = raw.tgLong("chatId"),
            messageId = raw.tgLong("messageId"),
            text = raw.tgString("text"),
            rows = rows,
        )
    }

    private fun TelegramIncomingMessage.toBotReply(): BotReply? =
        track?.let(BotReply::Track) ?: prompt?.let(BotReply::Prompt)

    suspend fun clickInlineButton(
        chatId: Long,
        messageId: Long,
        callbackData: ByteArray,
    ) {
        runCatching {
            TelegramClient.call(
                "pressInlineButton",
                buildJsonObject {
                    put("chatId", chatId)
                    put("messageId", messageId)
                    put("callbackData", Base64.encodeToString(callbackData, Base64.NO_WRAP))
                }.toString(),
            )
        }.onFailure { e ->
            Timber.tag(TAG).w(e, "clickInlineButton: callback query failed")
        }
    }

    suspend fun forwardMessages(
        toChatId: Long,
        fromChatId: Long,
        messageIds: LongArray,
    ): List<Long> {
        if (messageIds.isEmpty()) return emptyList()
        val result =
            TelegramClient.call(
                "forwardMessages",
                buildJsonObject {
                    put("toChatId", toChatId)
                    put("fromChatId", fromChatId)
                    put("messageIds", JsonArray(messageIds.map { JsonPrimitive(it) }))
                }.toString(),
            )
        val arr = result["messageIds"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            runCatching { el.jsonPrimitive.content.toLongOrNull() }.getOrNull()
        }
    }

    suspend fun forwardMessage(
        toChatId: Long,
        fromChatId: Long,
        messageId: Long,
    ): Long {
        val forwarded = forwardMessages(toChatId, fromChatId, longArrayOf(messageId))
        return forwarded.firstOrNull() ?: 0L
    }
}

data class TelegramIncomingMessage(
    val chatId: Long,
    val messageId: Long,
    val track: TelegramTrack?,
    val prompt: TelegramBotPrompt?,
)

data class TelegramBotPromptButton(
    val text: String,

    val callbackData: ByteArray? = null,

    val url: String? = null,
) {
    val isCallback: Boolean get() = callbackData != null
    val isUrl: Boolean get() = url != null

    override fun equals(other: Any?): Boolean =
        other is TelegramBotPromptButton &&
            other.text == text &&
            other.url == url &&
            ((other.callbackData == null) == (callbackData == null)) &&
            ((callbackData == null) || callbackData!!.contentEquals(other.callbackData))

    override fun hashCode(): Int {
        var result = text.hashCode()
        result = 31 * result + (url?.hashCode() ?: 0)
        result = 31 * result + (callbackData?.contentHashCode() ?: 0)
        return result
    }
}

data class TelegramBotPrompt(
    val chatId: Long,
    val messageId: Long,

    val text: String,

    val rows: List<List<TelegramBotPromptButton>>,
) {
    val allButtons: List<TelegramBotPromptButton> get() = rows.flatten()

    fun isCancelButton(button: TelegramBotPromptButton): Boolean {
        val t = button.text.lowercase()
        return t == "cancel" || t == "✕" || t == "x" || t.contains("cancel")
    }
}

sealed interface BotReply {
    data class Track(val track: TelegramTrack) : BotReply

    data class Prompt(val prompt: TelegramBotPrompt) : BotReply
}

data class TelegramBotCommand(
    val command: String,
    val description: String,
) {
    val withSlash: String get() = "/$command"
}
