/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * The TDLight engine singleton. Owns the td-ktx [TelegramFlow] (vendored
 * core) on top of the TDLight native client (tdlight-team/tdlight, TDLib
 * 1.8.66 base; org.drinkless.tdlib binding vendored from the same commit
 * under app/src/main/java).
 *
 * - Starts the native library through [TdLibNativeLibrary] (bundled or
 *   runtime-downloaded from the tdlight release, digest-pinned — the APK
 *   itself never carries the multi-MB per-ABI .so).
 * - Creates the [Client] with a channel-backed update handler: every TDLib
 *   update is buffered in an unlimited channel and dispatched sequentially
 *   by a single collector, so rapid bursts (bot albums, chat syncs) never
 *   drop an update the way a conflating StateFlow would.
 * - All RPCs go through td-ktx's `TelegramFlow.sendFunctionAsync`
 *   (coroutine-based request/response with per-call handlers).
 *
 * Updates are routed to [TelegramClient] (authorization state + chat cache)
 * and [TelegramBotClient] (new messages). Per-request responses bypass this
 * queue entirely (they resume the requesting coroutine directly), so a
 * handler may safely send further requests while the queue is drained.
 */

package moe.rukamori.archivetune.telegram

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.telegram.core.TelegramException
import kotlinx.telegram.core.TelegramFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal object TdEngine {
    private const val TAG = "TdEngine"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val started = AtomicBoolean(false)

    @PublishedApi
    internal var flow: TelegramFlow? = null
        private set

    /** Last start failure detail, surfaced through TelegramAuthState.RuntimeFailed. */
    @Volatile
    var lastStartError: String? = null
        private set

    val isRunning: Boolean
        get() = flow != null

    /**
     * Channel-backed [TelegramFlow.ResultHandlerFlow]: TDLib pushes updates
     * into an unlimited channel; the single collector turns them into a
     * cold Flow. No conflation, no drops.
     */
    private class ChannelResultHandler(
        private val channel: Channel<TdApi.Object>,
    ) : TelegramFlow.ResultHandlerFlow, Flow<TdApi.Object> {
        private val cold = channel.receiveAsFlow()

        override fun onResult(result: TdApi.Object?) {
            result?.let { channel.trySend(it) }
        }

        override suspend fun collect(collector: FlowCollector<TdApi.Object>) {
            cold.collect(collector)
        }
    }

    private val updateChannel = Channel<TdApi.Object>(Channel.UNLIMITED)

    /**
     * Boots the native library and the TDLib client. Returns false (and
     * records [lastStartError]) when the native library is unavailable.
     */
    fun start(context: Context): Boolean {
        if (started.get()) return true
        synchronized(this) {
            if (started.get()) return true

            val appContext = context.applicationContext
            if (!TdLibNativeLibrary.ensureLoaded(appContext)) {
                lastStartError = "TDLib native library is not available"
                return false
            }

            runCatching { Client.execute(TdApi.SetLogVerbosityLevel(1)) }

            val handler = ChannelResultHandler(updateChannel)
            val client =
                Client.create(
                    handler,
                    { throwable -> Timber.tag(TAG).e(throwable, "TDLib update handler exception") },
                    { throwable -> Timber.tag(TAG).e(throwable, "TDLib exception") },
                )
            val telegramFlow = TelegramFlow(handler)
            telegramFlow.attachClient(client)
            flow = telegramFlow
            lastStartError = null
            started.set(true)

            scope.launch {
                runCatching { telegramFlow.collect { dispatch(it) } }
                    .onFailure { Timber.tag(TAG).w(it, "TDLib update loop ended") }
            }
            return true
        }
    }

    /**
     * Drops the engine so the next [start] creates a fresh TDLib client
     * (used after authorization closes, and for offline local sign-out).
     */
    fun reset() {
        synchronized(this) {
            started.set(false)
            flow = null
        }
    }

    /**
     * td-ktx-backed RPC channel. Every Telegram call in the app funnels
     * through here so the transport is uniformly td-ktx's coroutine bridge.
     */
    suspend inline fun <reified T : TdApi.Object> send(function: TdApi.Function<T>): T {
        val current = flow ?: throw IOException("Telegram engine is not running")
        return try {
            current.sendFunctionAsync(function)
        } catch (e: TelegramException.Error) {
            throw TelegramApiException(0, e.message ?: "unknown Telegram error")
        }
    }

    private suspend fun dispatch(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState ->
                TelegramClient.onAuthorizationStateUpdate(update.authorizationState)

            is TdApi.UpdateNewChat -> TelegramClient.onChatUpdate(update.chat)
            is TdApi.UpdateChatTitle -> TelegramClient.onChatTitleUpdate(update.chatId, update.title)
            is TdApi.UpdateChatPhoto -> TelegramClient.onChatPhotoUpdate(update.chatId, update.photo)

            is TdApi.UpdateNewMessage -> TelegramBotClient.onNewMessage(update.message)
        }
    }
}
