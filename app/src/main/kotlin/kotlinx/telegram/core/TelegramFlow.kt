/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * ---------------------------------------------------------------------------
 * VENDORED from td-ktx (https://github.com/tdlibx/td-ktx) tag 1.8.56,
 * Apache-2.0 License, © td-ktx contributors.
 *
 * ArchiveTune vendors the td-ktx core instead of depending on the
 * `com.github.tdlibx:td-ktx` artifact because the artifact's blanket
 * `-keep class kotlinx.telegram.** { *; }` consumer rule would exempt ~2 MB
 * of generated extension wrappers from R8 shrinking. Vendoring the three
 * core files keeps the APK minimal while still using td-ktx as the Telegram
 * API layer over `com.github.tdlibx:td:1.8.56` (TDLib JNI binding).
 * Only change vs upstream: this attribution header. Source is otherwise
 * kept verbatim to stay diffable against the upstream tag.
 * ---------------------------------------------------------------------------
 */

package kotlinx.telegram.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.filterIsInstance
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Main class to interact with Telegram API client
 * @param resultHandler transforms results from [TdApi] client to [Flow] of the [TdApi.Object]
 */
class TelegramFlow(
    private val resultHandler: ResultHandlerFlow = ResultHandlerStateFlow()
) : Flow<TdApi.Object> by resultHandler, Closeable {

    interface ResultHandlerFlow : Client.ResultHandler, Flow<TdApi.Object>

    /**
     * Telegram [Client] instance. Null if instance is not attached
     */
    var client: Client? = null

    /**
     * Attach instance to the existing native Telegram client or create one
     * @param existingClient set an existing client to attach, null by default
     */
    fun attachClient(
        existingClient: Client? = null
    ) {
        if (client != null) return // client is already attached

        client = existingClient
            ?: Client.create(
                resultHandler,
                null,
                null
            )
    }

    /**
     * Return data flow from Telegram API of the given type [T]
     */
    inline fun <reified T : TdApi.Object> getUpdatesFlowOfType() =
        buffer(64).filterIsInstance<T>()

    /**
     * Sends a request to the TDLib and expect a result.
     *
     * @param function [TdApi.Function] representing a TDLib interface function-class.
     * @param ExpectedResult result type expecting from given [function].
     * @throws TelegramException.Error if TdApi request returns an exception
     * @throws TelegramException.UnexpectedResult if TdApi request returns an unexpected result
     * @throws TelegramException.ClientNotAttached if TdApi client has not attached yet
     */
    suspend inline fun <reified ExpectedResult : TdApi.Object>
        sendFunctionAsync(function: TdApi.Function<ExpectedResult>): ExpectedResult =
        suspendCoroutine { continuation ->
            val resultHandler: (TdApi.Object) -> Unit = { result ->
                when (result) {
                    is ExpectedResult -> continuation.resume(result)
                    is TdApi.Error -> continuation.resumeWithException(
                        TelegramException.Error(result.message)
                    )
                    else -> continuation.resumeWithException(
                        TelegramException.UnexpectedResult(result)
                    )
                }
            }
            client?.send(function, resultHandler) { throwable ->
                continuation.resumeWithException(
                    TelegramException.Error(throwable?.message ?: "unknown")
                )
            } ?: throw TelegramException.ClientNotAttached
        }

    /**
     * Sends a request to the TDLib and expect [TdApi.Ok]
     *
     * @param function [TdApi.Function] representing a TDLib interface function-class.
     * @throws TelegramException.Error if TdApi request returns an exception
     * @throws TelegramException.UnexpectedResult if TdApi request returns an unexpected result
     * @throws TelegramException.ClientNotAttached if TdApi client has not attached yet
     */
    suspend fun sendFunctionLaunch(function: TdApi.Function<TdApi.Ok>) {
        sendFunctionAsync<TdApi.Ok>(function)
    }

    /**
     * Closes Client.
     */
    override fun close() {
//        client?.close()
    }
}
