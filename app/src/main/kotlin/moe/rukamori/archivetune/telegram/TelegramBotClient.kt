/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * TDLib wrapper for the "Telegram bots" feature. Bots are private 1:1 chats with a Telegram bot
 * account. The user pastes a song link, the app sends it as a text message to the bot via
 * [TdApi.SendMessage], and then listens on a per-chat SharedFlow of incoming messages
 * (driven by [TdApi.UpdateNewMessage] in TelegramClient.onUpdate) until the bot replies with an
 * audio/document message — that reply becomes a playable [TelegramTrack].
 *
 * Forwarding to the user's own channel uses [TdApi.ForwardMessages] so the audio bytes aren't
 * re-uploaded (Telegram copies the file server-side) — this matches the user's spec: "if I add it
 * to my telegram playlist the song should also get forwarded to my own channel automatically".
 *
 * A 60s timeout caps how long we wait for a bot reply. Bots that stream "a lot of files" (e.g.
 * a Spotify-album link returns one message per track) all arrive on the same SharedFlow and are
 * collected into the result list.
 *
 * Inline-keyboard support: many music bots reply to a song link with a message that has a
 * [TdApi.ReplyMarkupInlineKeyboard] ("Choose quality: ALAC / AAC / Cancel") instead of the audio
 * directly. The user must tap one of the buttons to actually trigger the audio download. This
 * file exposes [collectBotReplies] which returns BOTH audio tracks and inline-keyboard prompts,
 * and [clickInlineButton] which sends a [TdApi.GetCallbackQueryAnswer] to simulate tapping a
 * button — after which the bot sends the actual audio file, which the caller collects with
 * another [collectBotReplies] cycle.
 */

package moe.rukamori.archivetune.telegram

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

object TelegramBotClient {
    private const val TAG = "TelegramBotClient"

    val BOT_REPLY_TIMEOUT = 60.seconds

    private const val POST_REPLY_GRACE_MS = 5_000L

    private val chatMessageFlows = ConcurrentHashMap<Long, MutableSharedFlow<TdApi.Message>>()

    fun messagesForChat(chatId: Long): SharedFlow<TdApi.Message> =
        chatMessageFlows.getOrPut(chatId) {
            MutableSharedFlow(replay = 0, extraBufferCapacity = 64)
        }.asSharedFlow()

    internal fun onNewMessage(message: TdApi.Message) {
        chatMessageFlows[message.chatId]?.tryEmit(message)
    }

    fun forgetChat(chatId: Long) {
        chatMessageFlows.remove(chatId)
    }

    suspend fun resolveBot(username: String): TdApi.Chat? {
        val cleaned = username.removePrefix("@").trim().lowercase()
        if (cleaned.isEmpty()) return null
        val chat = runCatching {
            TelegramClient.send(TdApi.SearchPublicChat(cleaned))
        }.getOrNull() ?: return null

        if (chat.type !is TdApi.ChatTypePrivate) {
            Timber.tag(TAG).w("resolveBot: %s is not a private/bot chat (type=%s)", cleaned, chat.type)
            return null
        }

        val userId = (chat.type as TdApi.ChatTypePrivate).userId
        val user = runCatching { TelegramClient.send(TdApi.GetUser(userId)) }.getOrNull()
        if (user != null && user.type !is TdApi.UserTypeBot) {
            Timber.tag(TAG).w("resolveBot: @%s is a user, not a bot — refusing", cleaned)
            return null
        }
        return chat
    }

    suspend fun sendTextMessage(chatId: Long, text: String): TdApi.Message {
        val input = TdApi.InputMessageText(
            TdApi.FormattedText(text, emptyArray()),

            null,
            false,
        )

        return TelegramClient.send(
            TdApi.SendMessage(chatId, null, null, null, null, input),
        )
    }

    suspend fun fetchBotCommands(chatId: Long): List<TelegramBotCommand> = runCatching {
        val result = TelegramClient.send(
            TdApi.GetCommands(TdApi.BotCommandScopeChat(chatId), ""),
        )
        result?.commands?.map { cmd ->
            TelegramBotCommand(command = cmd.command, description = cmd.description)
        } ?: emptyList()
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
                    if (message.id <= afterMessageId) return@collect
                    val track = TelegramClient.messageToTrack(message) ?: return@collect
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
                    if (message.id <= afterMessageId) return@collect
                    val reply = messageToBotReply(message) ?: return@collect
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

    private fun messageToBotReply(message: TdApi.Message): BotReply? {

        val track = TelegramClient.messageToTrack(message)
        if (track != null) return BotReply.Track(track)

        val markup = message.replyMarkup as? TdApi.ReplyMarkupInlineKeyboard ?: return null
        if (markup.rows.isEmpty()) return null
        val rows = markup.rows.mapNotNull { row ->
            val buttons = row.mapNotNull { button -> button.toPromptButton() }
            if (buttons.isEmpty()) null else buttons
        }
        if (rows.isEmpty()) return null

        val text = (message.content as? TdApi.MessageText)?.text?.text.orEmpty()
        return BotReply.Prompt(
            TelegramBotPrompt(
                chatId = message.chatId,
                messageId = message.id,
                text = text,
                rows = rows,
            ),
        )
    }

    private fun TdApi.InlineKeyboardButton.toPromptButton(): TelegramBotPromptButton? {
        val label = text.takeIf { it.isNotBlank() } ?: return null
        return when (val type = type) {
            is TdApi.InlineKeyboardButtonTypeCallback ->
                TelegramBotPromptButton(text = label, callbackData = type.data)
            is TdApi.InlineKeyboardButtonTypeUrl ->
                TelegramBotPromptButton(text = label, url = type.url)
            else -> null
        }
    }

    suspend fun clickInlineButton(
        chatId: Long,
        messageId: Long,
        callbackData: ByteArray,
    ): TdApi.CallbackQueryAnswer? = runCatching {
        TelegramClient.send(
            TdApi.GetCallbackQueryAnswer(
                chatId,
                messageId,
                TdApi.CallbackQueryPayloadData(callbackData),
            ),
        )
    }.onFailure { e ->
        Timber.tag(TAG).w(e, "clickInlineButton: callback query failed")
    }.getOrNull()

    suspend fun forwardMessages(
        toChatId: Long,
        fromChatId: Long,
        messageIds: LongArray,
    ): List<TdApi.Message> {
        if (messageIds.isEmpty()) return emptyList()
        val result = TelegramClient.send(
            TdApi.ForwardMessages(
                toChatId,
                null,
                fromChatId,
                messageIds,
                null,
                false,
                false,
            ),
        )

        return result.messages?.toList() ?: emptyList()
    }

    suspend fun forwardMessage(
        toChatId: Long,
        fromChatId: Long,
        messageId: Long,
    ): Long {
        val forwarded = forwardMessages(toChatId, fromChatId, longArrayOf(messageId))
        return forwarded.firstOrNull()?.id ?: 0L
    }
}

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
