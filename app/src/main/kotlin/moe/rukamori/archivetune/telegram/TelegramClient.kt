/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Kotlin face of the Telegram integration, backed by the mtcute (MTProto) host
 * inside QuickJS (TgJsRuntime) — previously the TDLib native library.
 *
 * Account login (phone -> code -> optional 2FA password), public channel
 * search, paging through a channel's audio/document messages, chat photo and
 * artwork helpers, and the byte-level file access used for streaming playback
 * (see TelegramDataSource + TelegramStreamCache).
 *
 * The user supplies their own api_id/api_hash from https://my.telegram.org
 * (compiled in via BuildConfig); the session lives in the mtcute storage under
 * filesDir/telegram-js and survives restarts, so login is a one-time flow.
 *
 * NOTE for TDLib-era users: TDLib sessions cannot be migrated to mtcute, so
 * accounts logged in with the TDLib build need one re-login after updating.
 */

package moe.rukamori.archivetune.telegram

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.BuildConfig
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.Locale

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

/** Channel audio paging filters (replaces TdApi.SearchMessagesFilter). */
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

    private const val INIT_TIMEOUT_MS = 25_000L

    /** Auth/search/chat calls fail fast enough for UI feedback; streams use longer limits. */
    private const val INTERACTIVE_CALL_TIMEOUT_MS = 45_000L

    private const val HISTORY_PRIME_LIMIT = 100

    private const val LOCAL_CHAT_SEARCH_LIMIT = 30

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val initMutex = Mutex()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var account: TelegramAccount? = null

    @Volatile
    private var pendingPhone: String? = null

    @Volatile
    private var pendingPhoneCodeHash: String? = null

    private var eventsJob: Job? = null

    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Idle)
    val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

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
        if (initialized || isReady) return true
        if (_authState.value is TelegramAuthState.Unsupported) return false
        appContext = context.applicationContext
        scope.launch {
            runCatching { initialize(context) }
                .onFailure { Timber.tag(TAG).w(it, "Telegram init failed") }
        }
        return true
    }

    /** Awaited start used from coroutines (login screen, app boot). */
    suspend fun ensureStartedAwait(context: Context): Boolean {
        if (!hasApiCredentials) return false
        appContext = context.applicationContext
        return initialize(context)
    }

    fun startIfSessionExists(context: Context): Boolean {
        if (!TgJsStorage.hasSession(context)) return false
        return ensureStarted(context)
    }

    private suspend fun initialize(context: Context): Boolean =
        initMutex.withLock {
            if (initialized && (TgJsRuntime.isRunning || isReady)) return true

            if (!TgJsRuntime.start(context)) {
                _authState.value = TelegramAuthState.Unsupported("RuntimeStartFailed")
                return false
            }
            TelegramStreamCache.attach(context)
            subscribeClientEvents()

            _authState.value = TelegramAuthState.Connecting
            val params =
                buildJsonObject {
                    put("apiId", BuildConfig.TELEGRAM_API_ID)
                    put("apiHash", BuildConfig.TELEGRAM_API_HASH)
                    put("deviceModel", Build.MODEL ?: "Android")
                    put("systemVersion", Build.VERSION.RELEASE ?: "0")
                    put("appVersion", BuildConfig.VERSION_NAME)
                    put("langCode", Locale.getDefault().language.ifBlank { "en" })
                }
            val result =
                withTimeoutOrNull(INIT_TIMEOUT_MS) {
                    runCatching { TgJsRuntime.call("init", params.toString()) }.getOrNull()
                }
            // A timeout here means the transport is still connecting/retrying
            // (e.g. no network) — the host stays alive, login errors surface per call.
            val payload = result
            if (payload == null) {
                initialized = true
                _authState.value = TelegramAuthState.WaitPhoneNumber
                Timber.tag(TAG).w("init timed out (still connecting) — awaiting network")
                return true
            }
            initialized = true
            account = parseTgAccount(payload.tgObj("me"))
            val warning = payload.tgStringOrNull("warning")
            if (warning != null) {
                Timber.tag(TAG).w("init warning: %s", warning)
            }
            _authState.value =
                if (payload.tgBool("authorized")) {
                    TelegramAuthState.Ready
                } else {
                    TelegramAuthState.WaitPhoneNumber
                }
            true
        }

    private fun subscribeClientEvents() {
        if (eventsJob?.isActive == true) return
        eventsJob =
            scope.launch {
                TgJsRuntime.clientEvents.collect { (type, payload) ->
                    if (type == "newMessage") {
                        runCatching { TelegramBotClient.onNewMessageEvent(payload) }
                            .onFailure { Timber.tag(TAG).w(it, "newMessage event failed") }
                    }
                }
            }
    }

    // ---------------------------------------------------------------------------
    // auth
    // ---------------------------------------------------------------------------

    suspend fun logOut() {
        runCatching { TelegramDataSource.cancelRetainedDownloads() }
        _authState.value = TelegramAuthState.LoggingOut
        val loggedOut = runCatching { call("logOut") }.isSuccess
        if (!loggedOut) {
            // offline (server unreachable): wipe the local session and restart the
            // host so the account is signed out locally as well
            runCatching { TgJsRuntime.call("resetSession") }
            val context = appContext
            if (context != null) {
                runCatching { TgJsRuntime.restart(context) }
            }
            initialized = false
        }
        account = null
        pendingPhone = null
        pendingPhoneCodeHash = null
        _authState.value = TelegramAuthState.Idle
    }

    suspend fun submitPhoneNumber(phoneNumber: String) {
        requireStarted()
        val phone = phoneNumber.trim()
        val result =
            call(
                "sendCode",
                buildJsonObject {
                    put("phone", phone)
                }.toString(),
            )
        if (result.tgBool("authorized")) {
            refreshAccount()
            return
        }
        pendingPhone = phone
        pendingPhoneCodeHash = result.tgStringOrNull("phoneCodeHash")
        _authState.value =
            TelegramAuthState.WaitCode(
                phoneNumber = phone,
                codeType = codeTypeOf(result.tgStringOrNull("type")),
                canResend = result.tgStringOrNull("nextType")?.let { it != "none" } ?: false,
                resendTimeoutSeconds = result.tgInt("timeout"),
            )
    }

    suspend fun submitCode(code: String) {
        requireStarted()
        val result =
            call(
                "signIn",
                buildJsonObject {
                    put("phone", pendingPhone.orEmpty())
                    put("phoneCodeHash", pendingPhoneCodeHash.orEmpty())
                    put("code", code.trim())
                }.toString(),
            )
        if (result.tgBool("needsPassword")) {
            val hint =
                runCatching { call("getPasswordHint") }
                    .getOrNull()
                    ?.tgStringOrNull("hint")
            _authState.value = TelegramAuthState.WaitPassword(hint?.takeIf(String::isNotBlank))
        } else {
            refreshAccount()
        }
    }

    suspend fun submitPassword(password: String) {
        requireStarted()
        call(
            "checkPassword",
            buildJsonObject {
                put("password", password)
            }.toString(),
        )
        refreshAccount()
    }

    suspend fun resendCode() {
        requireStarted()
        val result =
            call(
                "resendCode",
                buildJsonObject {
                    put("phone", pendingPhone.orEmpty())
                    put("phoneCodeHash", pendingPhoneCodeHash.orEmpty())
                }.toString(),
            )
        pendingPhoneCodeHash = result.tgStringOrNull("phoneCodeHash") ?: pendingPhoneCodeHash
        _authState.value =
            TelegramAuthState.WaitCode(
                phoneNumber = pendingPhone.orEmpty(),
                codeType = codeTypeOf(result.tgStringOrNull("type")),
                canResend = result.tgStringOrNull("nextType")?.let { it != "none" } ?: false,
                resendTimeoutSeconds = result.tgInt("timeout"),
            )
    }

    private suspend fun refreshAccount() {
        account =
            runCatching { getMe() }.getOrNull()
        _authState.value = TelegramAuthState.Ready
    }

    suspend fun getMe(): TelegramAccount {
        requireStarted()
        val me = call("getMe").let(TgJsProtocol::parseAccount) ?: throw IOException("getMe failed")
        account = me
        return me
    }

    private fun codeTypeOf(type: String?): TelegramCodeType =
        when (type) {
            "app", "email" -> TelegramCodeType.TELEGRAM_APP
            "sms", "sms_word", "sms_phrase", "fragment" -> TelegramCodeType.SMS
            "call", "flash_call", "missed_call" -> TelegramCodeType.CALL
            else -> TelegramCodeType.OTHER
        }

    // ---------------------------------------------------------------------------
    // chats & channels
    // ---------------------------------------------------------------------------

    suspend fun searchChannels(query: String): List<TelegramChannel> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val result =
            call(
                "searchChats",
                buildJsonObject {
                    put("query", trimmed)
                    put("limit", LOCAL_CHAT_SEARCH_LIMIT)
                }.toString(),
            )
        return result.tgObjArray("results").mapNotNull { parseTgChannel(it) }
    }

    suspend fun channelInfo(chatId: Long): TelegramChannel? {
        val result =
            runCatching {
                call(
                    "getChat",
                    buildJsonObject {
                        put("chatId", chatId)
                    }.toString(),
                )
            }.getOrNull() ?: return null
        return parseTgChannel(result)
    }

    suspend fun openChat(chatId: Long) {
        runCatching {
            call(
                "openChat",
                buildJsonObject {
                    put("chatId", chatId)
                }.toString(),
            )
        }
    }

    suspend fun primeChatHistory(
        chatId: Long,
        maxRounds: Int = 8,
        perRoundDelayMs: Long = 400L,
    ): Boolean {
        runCatching { channelInfo(chatId) }

        repeat(maxRounds) { round ->
            val hasMessages =
                runCatching {
                    val result =
                        call(
                            "getHistoryHasMessages",
                            buildJsonObject {
                                put("chatId", chatId)
                                put("limit", HISTORY_PRIME_LIMIT)
                            }.toString(),
                        )
                    result.tgBool("hasMessages")
                }.getOrDefault(false)
            if (hasMessages) {
                Timber.tag(TAG).d("primeChatHistory(%d): ready after round %d", chatId, round + 1)
                return true
            }
            delay(perRoundDelayMs)
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
        val result =
            call(
                "fetchAudioPage",
                buildJsonObject {
                    put("chatId", chatId)
                    put("fromMessageId", fromMessageId)
                    put("limit", limit)
                    put("filter", if (filter == TelegramMessageFilter.DOCUMENT) "document" else "audio")
                }.toString(),
            )
        val tracks =
            result.tgObjArray("tracks").mapNotNull { parseTgTrack(it) }
        return TelegramAudioPage(
            tracks = tracks,
            nextFromMessageId = result.tgLong("nextFromMessageId"),
        )
    }

    /** Re-resolves a message into a track (stale unique id / fresh file reference). */
    suspend fun resolveTrack(chatId: Long, messageId: Long): TelegramTrack? {
        val result =
            runCatching {
                call(
                    "resolveTrack",
                    buildJsonObject {
                        put("chatId", chatId)
                        put("messageId", messageId)
                    }.toString(),
                )
            }.getOrNull() ?: return null
        return parseTgTrack(result.tgObj("track"))
    }

    // ---------------------------------------------------------------------------
    // file access (streaming + downloads)
    // ---------------------------------------------------------------------------

    /**
     * Reads an arbitrary byte range of a channel file — TDLib ReadFilePart's
     * replacement, backed by mtcute's precise download chunks.
     */
    suspend fun readFilePart(
        chatId: Long,
        messageId: Long,
        offset: Long,
        count: Long,
    ): ByteArray =
        TgJsRuntime.callBin(
            "readFilePart",
            buildJsonObject {
                put("chatId", chatId)
                put("messageId", messageId)
                put("offset", offset)
                put("limit", count)
            }.toString(),
        )

    /** Downloads a whole file (or its thumbnail) — used for artwork. */
    suspend fun downloadFullFile(
        chatId: Long,
        messageId: Long,
        thumb: Boolean,
    ): ByteArray? =
        runCatching {
            TgJsRuntime.callBin(
                "downloadFullFile",
                buildJsonObject {
                    put("chatId", chatId)
                    put("messageId", messageId)
                    put("thumb", thumb)
                }.toString(),
            )
        }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** File size + DC for a track's document (used by the streaming cache). */
    suspend fun fileSize(
        chatId: Long,
        messageId: Long,
        thumb: Boolean = false,
    ): Long? =
        runCatching {
            call(
                "fileSize",
                buildJsonObject {
                    put("chatId", chatId)
                    put("messageId", messageId)
                    put("thumb", thumb)
                }.toString(),
            )
        }.getOrNull()?.tgLong("fileSize")?.takeIf { it > 0 }

    /**
     * Downloads a chat photo (avatar) and returns a cached file path, mirroring
     * the old downloadFileBlocking(fileId) behaviour for avatars.
     */
    suspend fun downloadChatPhotoFile(
        chatId: Long,
        big: Boolean = false,
    ): String? {
        val context = appContext ?: return null
        return runCatching {
            val bytes =
                TgJsRuntime.callBin(
                    "downloadChatPhoto",
                    buildJsonObject {
                        put("chatId", chatId)
                        put("big", big)
                    }.toString(),
                )
            if (bytes.isEmpty()) return@runCatching null
            val dir = File(context.cacheDir, "telegram_avatars").apply { mkdirs() }
            val file = File(dir, "$chatId-${if (big) "big" else "small"}.jpg")
            file.writeBytes(bytes)
            file.absolutePath
        }.getOrNull()
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

    // ---------------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------------

    internal fun cacheDirectory(): File? = appContext?.cacheDir

    internal suspend fun call(
        method: String,
        params: String = "{}",
        timeoutMs: Long = INTERACTIVE_CALL_TIMEOUT_MS,
    ) = TgJsRuntime.call(method, params, timeoutMs)

    internal suspend fun callBinary(method: String, params: String = "{}") = TgJsRuntime.callBin(method, params)

    private suspend fun requireStarted() {
        val context = appContext
        if (!TgJsRuntime.isRunning && context != null) {
            initialize(context)
        }
        if (!TgJsRuntime.isRunning) {
            throw IOException("Telegram client is not running")
        }
    }
}
