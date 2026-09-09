/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Kotlin face of the Telegram integration, backed by TDLib 1.8.56 through
 * td-ktx's TelegramFlow (see TdEngine + the vendored kotlinx.telegram.core).
 *
 * Account login (phone -> code -> optional 2FA password) runs on TDLib's own
 * authorization state machine: submitPhoneNumber/setAuthenticationPhoneNumber
 * makes TDLib send the code and emit WaitCode, so the login UI is driven
 * entirely by [authState]. Public channel search, paging through a channel's
 * audio/document messages, chat photo and artwork helpers, and the
 * byte-level file access used for streaming playback (see TelegramDataSource)
 * round out the surface.
 *
 * The user supplies their own api_id/api_hash from https://my.telegram.org
 * (compiled in via BuildConfig); the session lives in TDLib's own database
 * under filesDir/telegram and survives restarts, so login is a one-time flow.
 *
 * The TDLib native library (~20 MB per ABI) is NOT bundled: slim builds
 * download it once from this repo's GitHub release (digest-pinned) the first
 * time the user opens the Telegram login, keeping the APK minimal. The
 * download progress is exposed through [nativeDownloadProgress].
 *
 * NOTE for mtcute-era users: mtcute sessions cannot be migrated to TDLib, so
 * accounts logged in with the mtcute build need one re-login after updating.
 */

package moe.rukamori.archivetune.telegram

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.BuildConfig
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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

    /** The TDLib engine itself failed to boot (native library unavailable). */
    data class RuntimeFailed(
        val detail: String?,
    ) : TelegramAuthState

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

/** Channel audio paging filters (maps onto TdApi.SearchMessagesFilter). */
enum class TelegramMessageFilter {
    AUDIO,
    DOCUMENT,
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

    /** How long to wait for TDLib's first authorization state after boot. */
    private const val FIRST_AUTH_STATE_TIMEOUT_MS = 10_000L

    private const val LOGOUT_RPC_TIMEOUT_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val initMutex = Mutex()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var account: TelegramAccount? = null

    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Idle)
    val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

    /**
     * TDLib native-library download progress: null = not downloading,
     * -1 = indeterminate, otherwise 0..1.
     */
    private val _nativeDownloadProgress = MutableStateFlow<Float?>(null)
    val nativeDownloadProgress: StateFlow<Float?> = _nativeDownloadProgress.asStateFlow()

    private val chatCache = ConcurrentHashMap<Long, TdApi.Chat>()

    val isReady: Boolean
        get() = _authState.value is TelegramAuthState.Ready

    val hasApiCredentials: Boolean
        get() = BuildConfig.TELEGRAM_API_ID > 0 || BuildConfig.TELEGRAM_API_HASH.isNotBlank()

    // ---------------------------------------------------------------------------
    // lifecycle
    // ---------------------------------------------------------------------------

    /**
     * Fire-and-forget start used from non-suspending UI callbacks. Returns false
     * only when the build has no Telegram api credentials (or a previous start
     * failed permanently); runtime failures surface later through [authState]
     * and per-call exceptions.
     */
    fun ensureStarted(context: Context): Boolean {
        if (!hasApiCredentials) return false
        if (TdEngine.isRunning || isReady) return true
        if (_authState.value is TelegramAuthState.Unsupported) return false
        if (_authState.value is TelegramAuthState.RuntimeFailed) return false
        appContext = context.applicationContext
        val hasSession = runCatching { sessionDir(context).exists() }.getOrDefault(false)
        scope.launch {
            runCatching { initialize(context, allowEngineDownload = hasSession) }
                .onFailure { Timber.tag(TAG).w(it, "Telegram init failed") }
        }
        return true
    }

    /** Awaited start used from coroutines (login screen, app boot). */
    suspend fun ensureStartedAwait(context: Context): Boolean {
        if (!hasApiCredentials) return false
        appContext = context.applicationContext
        return initialize(context, allowEngineDownload = true)
    }

    fun startIfSessionExists(context: Context): Boolean {
        if (!runCatching { sessionDir(context).exists() }.getOrDefault(false)) return false
        return ensureStarted(context)
    }

    private fun sessionDir(context: Context): File = File(File(context.filesDir, "telegram"), "db")

    private suspend fun initialize(
        context: Context,
        allowEngineDownload: Boolean,
    ): Boolean =
        initMutex.withLock {
            if (TdEngine.isRunning || isReady) return true

            if (TdLibNativeLibrary.needsDownload(context)) {
                if (!allowEngineDownload) {
                    // No session and no interactive caller: don't pull the
                    // ~20 MB engine just for peeking at settings — the login
                    // screen downloads it on demand with a progress UI.
                    return true
                }
                _nativeDownloadProgress.value = 0f
                val downloaded =
                    runCatching { TdLibNativeLibrary.download(context) { p -> _nativeDownloadProgress.value = p } }
                        .getOrDefault(false)
                _nativeDownloadProgress.value = null
                if (!downloaded) {
                    _authState.value =
                        TelegramAuthState.RuntimeFailed("Could not download the Telegram engine")
                    return false
                }
            }

            if (!TdEngine.start(context)) {
                _authState.value =
                    TelegramAuthState.RuntimeFailed(TdEngine.lastStartError?.take(200))
                return false
            }

            _authState.value = TelegramAuthState.Connecting

            // TDLib reports its first authorization state locally (after
            // SetTdlibParameters): WaitPhoneNumber for a fresh install, or
            // WaitCode/WaitPassword/Ready when a session already exists.
            withTimeoutOrNull(FIRST_AUTH_STATE_TIMEOUT_MS) {
                while (true) {
                    val state = _authState.value
                    if (state !is TelegramAuthState.Idle && state !is TelegramAuthState.Connecting) {
                        return@withTimeoutOrNull
                    }
                    delay(100)
                }
            }
            true
        }

    // ---------------------------------------------------------------------------
    // auth
    // ---------------------------------------------------------------------------

    suspend fun logOut() {
        runCatching { TelegramDataSource.cancelRetainedDownloads() }
        _authState.value = TelegramAuthState.LoggingOut
        val loggedOut =
            runCatching {
                withTimeout(LOGOUT_RPC_TIMEOUT_MS) { TdEngine.send<TdApi.Ok>(TdApi.LogOut()) }
            }.isSuccess
        if (!loggedOut) {
            // offline (server unreachable): close the client, wipe the local
            // TDLib database and reset the engine so the account is signed
            // out locally as well
            runCatching { withTimeout(LOGOUT_RPC_TIMEOUT_MS) { TdEngine.send<TdApi.Ok>(TdApi.Close()) } }
            runCatching { delay(500) }
            val context = appContext
            if (context != null) {
                runCatching { File(context.filesDir, "telegram").deleteRecursively() }
            }
            TdEngine.reset()
            _authState.value = TelegramAuthState.Idle
        }
        account = null
        chatCache.clear()
    }

    suspend fun submitPhoneNumber(phoneNumber: String) {
        requireStarted()
        TdEngine.send<TdApi.Ok>(
            TdApi.SetAuthenticationPhoneNumber(phoneNumber.trim(), null),
        )
    }

    suspend fun submitCode(code: String) {
        requireStarted()
        TdEngine.send<TdApi.Ok>(TdApi.CheckAuthenticationCode(code.trim()))
    }

    suspend fun submitPassword(password: String) {
        requireStarted()
        TdEngine.send<TdApi.Ok>(TdApi.CheckAuthenticationPassword(password))
    }

    suspend fun resendCode() {
        requireStarted()
        TdEngine.send<TdApi.Ok>(
            TdApi.ResendAuthenticationCode(TdApi.ResendCodeReasonUserRequest()),
        )
    }

    suspend fun getMe(): TelegramAccount {
        requireStarted()
        val me = userToAccount(TdEngine.send<TdApi.User>(TdApi.GetMe()))
        account = me
        return me
    }

    // ---------------------------------------------------------------------------
    // TDLib authorization state machine (routed from TdEngine)
    // ---------------------------------------------------------------------------

    internal suspend fun onAuthorizationStateUpdate(state: TdApi.AuthorizationState) {
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
                    TelegramAuthState.WaitPassword(
                        state.passwordHint?.takeIf(String::isNotBlank),
                    )

            is TdApi.AuthorizationStateReady -> {
                _authState.value = TelegramAuthState.Ready
                scope.launch { runCatching { getMe() } }
            }

            is TdApi.AuthorizationStateLoggingOut -> _authState.value = TelegramAuthState.LoggingOut

            is TdApi.AuthorizationStateClosed -> {
                TdEngine.reset()
                chatCache.clear()
                account = null
                _authState.value = TelegramAuthState.Idle
            }

            else -> _authState.value = TelegramAuthState.Unsupported(state.javaClass.simpleName)
        }
    }

    private suspend fun sendTdlibParameters() {
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
        try {
            TdEngine.send<TdApi.Ok>(parameters)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "SetTdlibParameters failed")
            _authState.value = TelegramAuthState.Unsupported("InvalidApiCredentials")
        }
    }

    internal fun onChatUpdate(chat: TdApi.Chat) {
        chatCache[chat.id] = chat
    }

    internal fun onChatTitleUpdate(
        chatId: Long,
        title: String,
    ) {
        chatCache[chatId]?.title = title
    }

    internal fun onChatPhotoUpdate(
        chatId: Long,
        photo: TdApi.ChatPhotoInfo?,
    ) {
        chatCache[chatId]?.photo = photo
    }

    private fun codeTypeOf(type: TdApi.AuthenticationCodeType?): TelegramCodeType =
        when (type) {
            is TdApi.AuthenticationCodeTypeTelegramMessage -> TelegramCodeType.TELEGRAM_APP
            is TdApi.AuthenticationCodeTypeSms -> TelegramCodeType.SMS
            is TdApi.AuthenticationCodeTypeCall,
            is TdApi.AuthenticationCodeTypeFlashCall,
            is TdApi.AuthenticationCodeTypeMissedCall,
            -> TelegramCodeType.CALL

            else -> TelegramCodeType.OTHER
        }

    // ---------------------------------------------------------------------------
    // chats & channels
    // ---------------------------------------------------------------------------

    suspend fun searchChannels(query: String): List<TelegramChannel> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val chatIds = linkedSetOf<Long>()

        extractInviteLink(trimmed)?.let { inviteLink ->
            runCatching {
                val inviteInfo =
                    TdEngine.send<TdApi.ChatInviteLinkInfo>(TdApi.CheckChatInviteLink(inviteLink))
                val joinedChatId =
                    runCatching {
                        TdEngine.send<TdApi.Chat>(TdApi.JoinChatByInviteLink(inviteLink)).id
                    }.getOrNull()
                val chatId = joinedChatId ?: inviteInfo.chatId
                if (chatId != 0L) {
                    chatIds += chatId
                }
            }.onFailure { e ->
                Timber.tag(TAG).w(e, "Failed to resolve Telegram invite link")
            }
        }

        extractUsername(trimmed)?.let { username ->
            runCatching { TdEngine.send<TdApi.Chat>(TdApi.SearchPublicChat(username)) }
                .onSuccess { chatIds += it.id }
        }
        runCatching { TdEngine.send<TdApi.Chats>(TdApi.SearchPublicChats(trimmed)) }
            .onSuccess { chatIds += it.chatIds.toList() }

        runCatching { TdEngine.send<TdApi.Chats>(TdApi.SearchChats(trimmed, LOCAL_CHAT_SEARCH_LIMIT)) }
            .onSuccess { chatIds += it.chatIds.toList() }

        return chatIds.mapNotNull { chatId ->
            runCatching { toChannel(getChat(chatId)) }.getOrNull()
        }
    }

    suspend fun getChat(chatId: Long): TdApi.Chat = chatCache[chatId] ?: TdEngine.send<TdApi.Chat>(TdApi.GetChat(chatId))

    suspend fun channelInfo(chatId: Long): TelegramChannel? =
        runCatching { toChannel(getChat(chatId)) }.getOrNull()

    suspend fun openChat(chatId: Long) {
        runCatching { TdEngine.send<TdApi.Ok>(TdApi.OpenChat(chatId)) }
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
                    TdEngine.send<TdApi.Messages>(
                        TdApi.GetChatHistory(chatId, fromMessageId, 0, HISTORY_PRIME_LIMIT, false),
                    )
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

    suspend fun fetchAudioPage(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
        filter: TelegramMessageFilter,
    ): TelegramAudioPage {
        val found =
            TdEngine.send<TdApi.FoundChatMessages>(
                TdApi.SearchChatMessages(
                    chatId,
                    null,
                    "",
                    null,
                    fromMessageId,
                    0,
                    limit,
                    tdFilterOf(filter),
                ),
            )
        return TelegramAudioPage(
            tracks = found.messages.mapNotNull(::messageToTrack),
            nextFromMessageId = found.nextFromMessageId,
        )
    }

    /** Re-resolves a message into a track (fresh file id / unique id). */
    suspend fun resolveTrack(
        chatId: Long,
        messageId: Long,
    ): TelegramTrack? {
        val message =
            runCatching { TdEngine.send<TdApi.Message>(TdApi.GetMessage(chatId, messageId)) }
                .getOrNull() ?: return null
        return messageToTrack(message)
    }

    private fun tdFilterOf(filter: TelegramMessageFilter): TdApi.SearchMessagesFilter =
        when (filter) {
            TelegramMessageFilter.AUDIO -> TdApi.SearchMessagesFilterAudio()
            TelegramMessageFilter.DOCUMENT -> TdApi.SearchMessagesFilterDocument()
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
                    hasThumbnail = audio.albumCoverThumbnail != null,
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
                        hasThumbnail = document.thumbnail != null,
                    )
                }
            }

            else -> null
        }

    // ---------------------------------------------------------------------------
    // file access (streaming + downloads)
    // ---------------------------------------------------------------------------

    suspend fun getFile(fileId: Int): TdApi.File = TdEngine.send<TdApi.File>(TdApi.GetFile(fileId))

    /** Resolves a message into its playable TdApi.File (audio or document). */
    suspend fun resolveTrackFile(
        chatId: Long,
        messageId: Long,
    ): TdApi.File? {
        runCatching { getChat(chatId) }
        val message =
            runCatching { TdEngine.send<TdApi.Message>(TdApi.GetMessage(chatId, messageId)) }
                .getOrNull() ?: return null
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
        TdEngine.send<TdApi.File>(
            TdApi.DownloadFile(fileId, STREAM_DOWNLOAD_PRIORITY, offset, 0L, false),
        )

    suspend fun cancelDownload(fileId: Int) {
        runCatching { TdEngine.send<TdApi.Ok>(TdApi.CancelDownloadFile(fileId, false)) }
    }

    /**
     * Reads an arbitrary byte range of a Telegram file through TDLib's
     * partial-download cache (TdApi.ReadFilePart).
     */
    suspend fun readFilePart(
        fileId: Int,
        offset: Long,
        count: Long,
    ): ByteArray = TdEngine.send<TdApi.Data>(TdApi.ReadFilePart(fileId, offset, count)).data

    /**
     * Path of the downloaded (or sufficiently prefetched) local file for a
     * chat/message — used by the format refiner to probe bitrate/sample rate
     * from the real bytes.
     */
    suspend fun readyFilePath(
        chatId: Long,
        messageId: Long,
        minPrefixBytes: Long = 64 * 1024,
    ): String? {
        val file = resolveTrackFile(chatId, messageId) ?: return null
        val local = file.local
        val path = local.path
        if (path.isEmpty()) {
            runCatching { startDownload(file.id, 0L) }
            return null
        }
        val headerReady =
            local.isDownloadingCompleted ||
                (local.downloadOffset == 0L && local.downloadedPrefixSize >= minPrefixBytes)
        return if (headerReady) path else null
    }

    /** Downloads a whole file (or its thumbnail) — used for artwork. */
    suspend fun downloadFullFile(
        chatId: Long,
        messageId: Long,
        thumb: Boolean,
    ): ByteArray? =
        runCatching {
            val fileId: Int =
                if (thumb) {
                    val message =
                        TdEngine.send<TdApi.Message>(TdApi.GetMessage(chatId, messageId))
                    val thumbFileId =
                        when (val content = message.content) {
                            is TdApi.MessageAudio -> content.audio.albumCoverThumbnail?.file?.id
                            is TdApi.MessageDocument -> content.document.thumbnail?.file?.id
                            else -> null
                        } ?: return@runCatching null
                    thumbFileId
                } else {
                    val file = resolveTrackFile(chatId, messageId) ?: return@runCatching null
                    file.id
                }
            val path = downloadFileBlocking(fileId) ?: return@runCatching null
            File(path).takeIf { it.isFile }?.readBytes()?.takeIf { it.isNotEmpty() }
        }.getOrNull()

    suspend fun downloadFileBlocking(fileId: Int): String? {
        if (fileId <= 0) return null
        val existing = runCatching { getFile(fileId) }.getOrNull()
        existing?.local?.takeIf { it.isDownloadingCompleted && it.path.isNotEmpty() }?.let { return it.path }
        val downloaded =
            runCatching {
                TdEngine.send<TdApi.File>(
                    TdApi.DownloadFile(fileId, STREAM_DOWNLOAD_PRIORITY, 0L, 0L, true),
                )
            }.getOrNull() ?: return null
        return downloaded.local.path.takeIf { it.isNotEmpty() }
    }

    /**
     * Downloads a chat photo (avatar) and returns the local file path of the
     * downloaded photo.
     */
    suspend fun downloadChatPhotoFile(
        chatId: Long,
        big: Boolean = false,
    ): String? =
        runCatching {
            val chat = getChat(chatId)
            val photo = chat.photo ?: return@runCatching null
            val size = if (big) photo.big else photo.small
            downloadFileBlocking(size.id)
        }.getOrNull()

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

    // ---------------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------------

    private suspend fun requireStarted() {
        val context = appContext
        if (!TdEngine.isRunning && context != null) {
            initialize(context, allowEngineDownload = true)
        }
        if (!TdEngine.isRunning) {
            throw IOException("Telegram client is not running")
        }
    }

    private suspend fun toChannel(chat: TdApi.Chat): TelegramChannel? {
        val type = chat.type as? TdApi.ChatTypeSupergroup ?: return null
        val supergroup =
            runCatching { TdEngine.send<TdApi.Supergroup>(TdApi.GetSupergroup(type.supergroupId)) }
                .getOrNull()
        return TelegramChannel(
            chatId = chat.id,
            title = chat.title,
            username = supergroup?.usernames?.activeUsernames?.firstOrNull()?.takeIf(String::isNotBlank),
            memberCount = supergroup?.memberCount ?: 0,
            isBroadcastChannel = type.isChannel,
            photoMinithumbnail = chat.photo?.minithumbnail?.data,
        )
    }

    private fun userToAccount(user: TdApi.User): TelegramAccount =
        TelegramAccount(
            id = user.id,
            firstName = user.firstName.orEmpty(),
            lastName = user.lastName?.takeIf(String::isNotBlank),
            username = user.usernames?.activeUsernames?.firstOrNull(),
            phoneNumber = user.phoneNumber?.takeIf(String::isNotBlank),
            isBot = user.type is TdApi.UserTypeBot,
        )

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
