/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Singleton wrapper around TDLib (org.drinkless.tdlib) that powers the Telegram channel streaming
 * integration: account login (phone → code → optional 2FA password), public channel search,
 * paging through a channel's audio/document messages, and partial-file access for streaming
 * playback (see TelegramDataSource).
 *
 * The user supplies their own api_id/api_hash from https://my.telegram.org (stored in DataStore);
 * the actual session lives in TDLib's own database under filesDir/telegram and survives restarts,
 * so login is a one-time flow.
 */

package moe.rukamori.archivetune.telegram

import android.content.Context
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.rukamori.archivetune.BuildConfig
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface TelegramAuthState {

    data object Idle : TelegramAuthState

    data object Connecting : TelegramAuthState

    data object WaitPhoneNumber : TelegramAuthState

    data class WaitCode(
        val phoneNumber: String,

        val codeType: TelegramCodeType,

        val canResend: Boolean,

        val resendTimeoutSeconds: Int,
    ) : TelegramAuthState

    data class WaitPassword(
        val passwordHint: String?,
    ) : TelegramAuthState

    data object Ready : TelegramAuthState

    data object LoggingOut : TelegramAuthState

    data class Unsupported(
        val stateName: String,
    ) : TelegramAuthState
}

enum class TelegramCodeType {
    TELEGRAM_APP,
    SMS,
    CALL,
    OTHER,
}

class TelegramApiException(
    val code: Int,
    message: String,
) : IOException("Telegram error $code: $message")

object TelegramClient {
    private const val TAG = "TelegramClient"

    const val STREAM_DOWNLOAD_PRIORITY = 32

    private const val HISTORY_PRIME_LIMIT = 100

    private const val LOCAL_CHAT_SEARCH_LIMIT = 30

    private val lock = Any()

    @Volatile
    private var client: Client? = null

    @Volatile
    private var appContext: Context? = null

    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Idle)
    val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

    private val chatCache = ConcurrentHashMap<Long, TdApi.Chat>()

    val isReady: Boolean
        get() = _authState.value is TelegramAuthState.Ready

    fun ensureStarted(context: Context): Boolean {
        val ctx = context.applicationContext
        synchronized(lock) {
            if (client != null) return true
            if (BuildConfig.TELEGRAM_API_ID <= 0 || BuildConfig.TELEGRAM_API_HASH.isBlank()) return false

            if (!TdLibNativeLibrary.ensureLoaded(ctx)) {
                Timber.tag(TAG).w("TDLib native library is not available yet; not starting")
                _authState.value = TelegramAuthState.Unsupported("NativeLibraryMissing")
                return false
            }
            appContext = ctx
            runCatching { Client.execute(TdApi.SetLogVerbosityLevel(1)) }
            _authState.value = TelegramAuthState.Connecting
            client =
                Client.create(
                    { update -> onUpdate(update) },
                    { throwable -> Timber.tag(TAG).e(throwable, "TDLib update handler exception") },
                    { throwable -> Timber.tag(TAG).e(throwable, "TDLib exception") },
                )
            return true
        }
    }

    private fun sessionDir(context: Context) = File(File(context.filesDir, "telegram"), "db")

    fun startIfSessionExists(context: Context): Boolean {
        if (!runCatching { sessionDir(context).exists() }.getOrDefault(false)) return false
        return ensureStarted(context)
    }

    suspend fun logOut() {

        runCatching { TelegramDataSource.cancelRetainedDownloads() }
        runCatching { send(TdApi.LogOut()) }
            .onFailure { Timber.tag(TAG).w(it, "logOut failed") }
    }

    suspend fun submitPhoneNumber(phoneNumber: String) {
        send(TdApi.SetAuthenticationPhoneNumber(phoneNumber.trim(), null))
    }

    suspend fun submitCode(code: String) {
        send(TdApi.CheckAuthenticationCode(code.trim()))
    }

    suspend fun submitPassword(password: String) {
        send(TdApi.CheckAuthenticationPassword(password))
    }

    suspend fun resendCode() {
        send(TdApi.ResendAuthenticationCode(TdApi.ResendCodeReasonUserRequest()))
    }

    suspend fun getMe(): TdApi.User = send(TdApi.GetMe())

    suspend fun searchChannels(query: String): List<TelegramChannel> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val chatIds = linkedSetOf<Long>()

        extractInviteLink(trimmed)?.let { inviteLink ->
            runCatching {
                val inviteInfo = send<TdApi.ChatInviteLinkInfo>(TdApi.CheckChatInviteLink(inviteLink))

                val joinedChatId = runCatching {
                    send<TdApi.Chat>(TdApi.JoinChatByInviteLink(inviteLink)).id
                }.getOrNull()
                val chatId = joinedChatId ?: inviteInfo.chatId
                if (chatId != 0L) {
                    chatIds += chatId
                }
            }.onFailure { e ->
                Timber.w(e, "Failed to resolve Telegram invite link")
            }
        }

        extractUsername(trimmed)?.let { username ->
            runCatching { send(TdApi.SearchPublicChat(username)) }
                .onSuccess { chatIds += it.id }
        }
        runCatching { send(TdApi.SearchPublicChats(trimmed)) }
            .onSuccess { chatIds += it.chatIds.toList() }

        runCatching { send(TdApi.SearchChats(trimmed, LOCAL_CHAT_SEARCH_LIMIT)) }
            .onSuccess { chatIds += it.chatIds.toList() }

        return chatIds.mapNotNull { chatId ->
            runCatching { toChannel(getChat(chatId)) }.getOrNull()
        }
    }

    suspend fun getChat(chatId: Long): TdApi.Chat = chatCache[chatId] ?: send(TdApi.GetChat(chatId))

    suspend fun openChat(chatId: Long) {
        send(TdApi.OpenChat(chatId))
    }

    suspend fun primeChatHistory(
        chatId: Long,
        maxRounds: Int = 8,
        perRoundDelayMs: Long = 400L,
    ): Boolean {

        runCatching { getChat(chatId) }

        var fromMessageId = 0L
        repeat(maxRounds) { round ->
            val messages =
                runCatching {

                    send(TdApi.GetChatHistory(chatId, fromMessageId, 0, HISTORY_PRIME_LIMIT, false))
                }.getOrNull()

            val count = messages?.messages?.size ?: 0
            if (count > 0) {
                Timber.tag(TAG).d(
                    "primeChatHistory(%d): round %d loaded %d messages",
                    chatId,
                    round + 1,
                    count,
                )
                return true
            }

            delay(perRoundDelayMs)
            fromMessageId = 0L
        }
        Timber.tag(TAG).w("primeChatHistory(%d): no history after %d rounds", chatId, maxRounds)
        return false
    }

    suspend fun channelInfo(chatId: Long): TelegramChannel? =
        runCatching { toChannel(getChat(chatId)) }.getOrNull()

    suspend fun fetchAudioPage(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
        filter: TdApi.SearchMessagesFilter,
    ): TelegramAudioPage {
        val found =
            send(
                TdApi.SearchChatMessages(
                    chatId,
                    null,
                    "",
                    null,
                    fromMessageId,
                    0,
                    limit,
                    filter,
                ),
            )
        return TelegramAudioPage(
            tracks = found.messages.mapNotNull(::messageToTrack),
            nextFromMessageId = found.nextFromMessageId,
        )
    }

    fun messageToTrack(message: TdApi.Message): TelegramTrack? =
        when (val content = message.content) {
            is TdApi.MessageAudio -> {
                val audio = content.audio
                TelegramTrack(
                    chatId = message.chatId,
                    messageId = message.id,
                    fileId = audio.audio.id,
                    fileUniqueId = audio.audio.remote?.uniqueId.orEmpty(),
                    title = audio.title.orEmpty(),
                    performer = audio.performer?.takeIf(String::isNotBlank),
                    fileName = audio.fileName.orEmpty(),
                    mimeType = audio.mimeType.orEmpty(),
                    durationSeconds = audio.duration,
                    sizeBytes = audio.audio.size,
                    dateSeconds = message.date,
                    albumCoverMinithumbnail = audio.albumCoverMinithumbnail?.data,
                    thumbnailFileId = audio.albumCoverThumbnail?.file?.id ?: 0,
                )
            }

            is TdApi.MessageDocument -> {
                val document = content.document
                val fileName = document.fileName.orEmpty()
                val mimeType = document.mimeType.orEmpty()
                if (!isAudioDocument(mimeType, fileName)) {
                    null
                } else {
                    TelegramTrack(
                        chatId = message.chatId,
                        messageId = message.id,
                        fileId = document.document.id,
                        fileUniqueId = document.document.remote?.uniqueId.orEmpty(),
                        title = "",
                        performer = null,
                        fileName = fileName,
                        mimeType = mimeType,
                        durationSeconds = 0,
                        sizeBytes = document.document.size,
                        dateSeconds = message.date,
                        albumCoverMinithumbnail = document.minithumbnail?.data,
                        thumbnailFileId = document.thumbnail?.file?.id ?: 0,
                    )
                }
            }

            else -> null
        }

    suspend fun getFile(fileId: Int): TdApi.File = send(TdApi.GetFile(fileId))

    suspend fun resolveTrackFile(
        chatId: Long,
        messageId: Long,
    ): TdApi.File? {
        runCatching { getChat(chatId) }
        val message = runCatching { send(TdApi.GetMessage(chatId, messageId)) }.getOrNull() ?: return null
        return when (val content = message.content) {
            is TdApi.MessageAudio -> content.audio.audio
            is TdApi.MessageDocument -> content.document.document
            else -> null
        }
    }

    suspend fun startDownload(
        fileId: Int,
        offset: Long,
    ): TdApi.File =
        send(
            TdApi.DownloadFile(fileId, STREAM_DOWNLOAD_PRIORITY, offset, 0L, false),
        )

    suspend fun cancelDownload(fileId: Int) {
        runCatching { send(TdApi.CancelDownloadFile(fileId, false)) }
    }

    suspend fun readyFilePath(
        fileId: Int,
        minPrefixBytes: Long = 64 * 1024,
    ): String? {
        val file = runCatching { getFile(fileId) }.getOrNull() ?: return null
        val local = file.local
        val path = local.path
        if (path.isEmpty()) return null
        val headerReady =
            local.isDownloadingCompleted ||
                (local.downloadOffset == 0L && local.downloadedPrefixSize >= minPrefixBytes)
        return if (headerReady) path else null
    }

    suspend fun readFilePart(
        fileId: Int,
        offset: Long,
        count: Long,
    ): ByteArray = send(TdApi.ReadFilePart(fileId, offset, count)).data

    suspend fun downloadFileBlocking(fileId: Int): String? {
        if (fileId <= 0) return null
        val existing = runCatching { getFile(fileId) }.getOrNull()
        existing?.local?.takeIf { it.isDownloadingCompleted && it.path.isNotEmpty() }?.let { return it.path }
        val downloaded =
            runCatching {
                send(TdApi.DownloadFile(fileId, STREAM_DOWNLOAD_PRIORITY, 0L, 0L, true))
            }.getOrNull() ?: return null
        return downloaded.local.path.takeIf { it.isNotEmpty() }
    }

    fun cacheArtwork(
        uniqueKey: String,
        data: ByteArray?,
    ): String? {
        if (data == null || data.isEmpty()) return null
        val context = appContext ?: return null
        return runCatching {
            val dir = File(context.cacheDir, "telegram_artwork").apply { mkdirs() }
            val safeKey = uniqueKey.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val file = File(dir, "$safeKey.jpg")
            if (!file.exists()) {
                file.writeBytes(data)
            }
            "file://${file.absolutePath}"
        }.getOrNull()
    }

    suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T {
        val currentClient =
            client ?: throw IOException("Telegram client is not running")
        return suspendCancellableCoroutine { continuation ->
            currentClient.send(function) { result ->
                when (result) {
                    is TdApi.Error ->
                        continuation.resumeWithException(
                            TelegramApiException(result.code, result.message),
                        )

                    else -> {
                        @Suppress("UNCHECKED_CAST")
                        continuation.resume(result as T)
                    }
                }
            }
        }
    }

    private fun onUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> handleAuthorizationState(update.authorizationState)
            is TdApi.UpdateNewChat -> chatCache[update.chat.id] = update.chat
            is TdApi.UpdateChatTitle -> chatCache[update.chatId]?.title = update.title
            is TdApi.UpdateChatPhoto -> chatCache[update.chatId]?.photo = update.photo

            is TdApi.UpdateNewMessage -> TelegramBotClient.onNewMessage(update.message)
        }
    }

    private fun handleAuthorizationState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> sendTdlibParameters()
            is TdApi.AuthorizationStateWaitPhoneNumber -> _authState.value = TelegramAuthState.WaitPhoneNumber
            is TdApi.AuthorizationStateWaitCode -> {
                val codeInfo = state.codeInfo
                _authState.value =
                    TelegramAuthState.WaitCode(
                        phoneNumber = codeInfo?.phoneNumber.orEmpty(),
                        codeType = codeTypeOf(codeInfo?.type),
                        canResend = codeInfo?.nextType != null,
                        resendTimeoutSeconds = codeInfo?.timeout ?: 0,
                    )
            }

            is TdApi.AuthorizationStateWaitPassword ->
                _authState.value =
                    TelegramAuthState.WaitPassword(state.passwordHint?.takeIf(String::isNotBlank))

            is TdApi.AuthorizationStateReady -> _authState.value = TelegramAuthState.Ready
            is TdApi.AuthorizationStateLoggingOut -> _authState.value = TelegramAuthState.LoggingOut
            is TdApi.AuthorizationStateClosed -> {
                synchronized(lock) { client = null }
                chatCache.clear()
                _authState.value = TelegramAuthState.Idle
            }

            else -> _authState.value = TelegramAuthState.Unsupported(state.javaClass.simpleName)
        }
    }

    private fun codeTypeOf(type: TdApi.AuthenticationCodeType?): TelegramCodeType =
        when (type) {
            is TdApi.AuthenticationCodeTypeTelegramMessage -> TelegramCodeType.TELEGRAM_APP
            is TdApi.AuthenticationCodeTypeSms -> TelegramCodeType.SMS
            is TdApi.AuthenticationCodeTypeCall -> TelegramCodeType.CALL
            else -> TelegramCodeType.OTHER
        }

    private fun sendTdlibParameters() {
        val context = appContext ?: return
        val apiId = BuildConfig.TELEGRAM_API_ID
        val apiHash = BuildConfig.TELEGRAM_API_HASH
        val baseDir = File(context.filesDir, "telegram")
        val parameters =
            TdApi.SetTdlibParameters(
                false,
                File(baseDir, "db").absolutePath,
                File(baseDir, "files").absolutePath,
                ByteArray(0),
                true,
                true,
                true,
                false,
                apiId,
                apiHash,
                Locale.getDefault().language.ifBlank { "en" },
                Build.MODEL ?: "Android",
                Build.VERSION.RELEASE ?: "0",
                BuildConfig.VERSION_NAME,
            )
        client?.send(parameters) { result ->
            if (result is TdApi.Error) {
                Timber.tag(TAG).e("SetTdlibParameters failed: %s", result.message)
                _authState.value = TelegramAuthState.Unsupported("InvalidApiCredentials")
            }
        }
    }

    private suspend fun toChannel(chat: TdApi.Chat): TelegramChannel? {
        val type = chat.type as? TdApi.ChatTypeSupergroup ?: return null
        val supergroup = runCatching { chatSupergroup(type.supergroupId) }.getOrNull()
        return TelegramChannel(
            chatId = chat.id,
            title = chat.title,
            username = supergroup?.username,
            memberCount = supergroup?.memberCount ?: 0,
            isBroadcastChannel = type.isChannel,
            photoMinithumbnail = chat.photo?.minithumbnail?.data,
            photoFileId = chat.photo?.small?.id ?: 0,
        )
    }

    private data class SupergroupInfo(
        val username: String?,
        val memberCount: Int,
    )

    private suspend fun chatSupergroup(supergroupId: Long): SupergroupInfo {
        val supergroup = send(TdApi.GetSupergroup(supergroupId))
        val username =
            supergroup.usernames
                ?.activeUsernames
                ?.firstOrNull()
                ?.takeIf(String::isNotBlank)
        return SupergroupInfo(username = username, memberCount = supergroup.memberCount)
    }

    private fun extractUsername(query: String): String? {
        val trimmed = query.trim()
        val fromLink =
            Regex("(?:https?://)?t(?:elegram)?\\.me/([A-Za-z0-9_]{3,})", RegexOption.IGNORE_CASE)
                .find(trimmed)
                ?.groupValues
                ?.get(1)
        if (fromLink != null) return fromLink
        if (trimmed.startsWith("@")) {
            return trimmed.removePrefix("@").takeIf { it.matches(Regex("[A-Za-z0-9_]{3,}")) }
        }
        return null
    }

    private fun extractInviteLink(query: String): String? {
        val trimmed = query.trim()
        val inviteRegex =
            Regex(
                "((?:https?://)?t(?:elegram)?\\.me/(?:\\+|joinchat/|add/)[A-Za-z0-9_-]+)",
                RegexOption.IGNORE_CASE,
            )
        val match = inviteRegex.find(trimmed) ?: return null
        val link = match.groupValues[1]

        return if (link.startsWith("http")) link else "https://$link"
    }
}
