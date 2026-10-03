/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.discord

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class DiscordOAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        finish()
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        DiscordAuthCoordinator.emit(uri)
        // Complete the login app-side: the token exchange must not depend on
        // the settings screen (or any UI) still being alive - the activity can
        // be recreated by process death while the browser is open, and the old
        // flow then dropped the redirect as a silent state mismatch ("tap
        // Authorize, nothing happens"). The exchange runs on the repository's
        // process-scoped supervisor and stores the session in DataStore; any
        // live settings screen observes the result flow and the token
        // preference updates on their own.
        DiscordOAuthRepository.completeFromRedirectAsync(applicationContext, uri)
    }
}
