/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * ---------------------------------------------------------------------------
 * VENDORED from td-ktx (https://github.com/tdlibx/td-ktx) tag 1.8.56,
 * Apache-2.0 License, © td-ktx contributors — see TelegramFlow.kt header
 * for the vendoring rationale. No functional changes vs upstream.
 * ---------------------------------------------------------------------------
 */

package kotlinx.telegram.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.drinkless.tdlib.TdApi

/**
 * Class that converts results handler from [TdApi] client to [Flow]
 * of the [TdApi.Object] using [MutableSharedFlow]
 */
class ResultHandlerStateFlow(
    private val sharedFlow: MutableSharedFlow<TdApi.Object> = MutableSharedFlow(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
) : TelegramFlow.ResultHandlerFlow, Flow<TdApi.Object> by sharedFlow {

    override fun onResult(result: TdApi.Object?) {
        result?.let(sharedFlow::tryEmit)
    }
}
