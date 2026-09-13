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
 * core files keeps the APK minimal while still using td-ktx as the
 * Telegram API layer. It runs unchanged on top of the TDLight engine: the
 * org.drinkless.tdlib Client/TdApi binding (vendored under
 * app/src/main/java) is generated from the same TDLight commit that builds
 * the runtime-downloaded libtdjni.so, so the API always matches.
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

class TelegramFlow(
    private val resultHandler: ResultHandlerFlow = ResultHandlerStateFlow()
) : Flow<TdApi.Object> by resultHandler, Closeable {

    interface ResultHandlerFlow : Client.ResultHandler, Flow<TdApi.Object>

    var client: Client? = null

    fun attachClient(
        existingClient: Client? = null
    ) {
        if (client != null) return

        client = existingClient
            ?: Client.create(
                resultHandler,
                null,
                null
            )
    }

    inline fun <reified T : TdApi.Object> getUpdatesFlowOfType() =
        buffer(64).filterIsInstance<T>()

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

    suspend fun sendFunctionLaunch(function: TdApi.Function<TdApi.Ok>) {
        sendFunctionAsync<TdApi.Ok>(function)
    }

    override fun close() {
    }
}
