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

import org.drinkless.tdlib.TdApi

sealed class TelegramException(message: String) : Throwable(message) {
    object ClientNotAttached :
        TelegramException(
            "Client is not attached. Please call TelegramFlow.attachClient() " +
                "before calling a Telegram function"
        )

    class Error(message: String) : TelegramException(message)
    class UnexpectedResult(result: TdApi.Object) : TelegramException("unexpected result: $result")
}
