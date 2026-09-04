/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.tidal

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.canvas.CanvasSourceDiagnosis
import moe.rukamori.archivetune.constants.TidalAccessTokenKey
import moe.rukamori.archivetune.constants.TidalAuthFlowKey
import moe.rukamori.archivetune.constants.TidalRefreshTokenKey
import moe.rukamori.archivetune.constants.TidalTokenExpiryKey
import moe.rukamori.archivetune.utils.dataStore
import kotlinx.coroutines.flow.first

/**
 * ── Tidal row of the Canvas Check diagnostic (2026-09-04) ───────────────────
 *
 * User request: "Also in the canvas check there's no tidal option."
 *
 * Tidal in ArchiveTune contributes high-res still artwork (and the animated
 * cover preference) through TWO real layers, and this check exercises both
 * with the exact requests playback would make — no mock pings:
 *
 *  1. **Your Tidal account** (when signed in): the stored access token is
 *     validated, refreshing it through auth.tidal.com when it has expired
 *     (the same exchange [TidalAccountManager.refreshAccessToken] performs).
 *     The refreshed token is deliberately NOT persisted here — this is a
 *     diagnosis, not a login; playback keeps owning the token lifecycle.
 *  2. **The Tidal catalog search** the artwork path performs
 *     ([TidalAudioProvider.probeCatalogSearch]): the user's own
 *     HiFi/QQDL instances first, then the public tidal.com/v1 API.
 *
 * The result merges the two layers into one honest status line.
 */
object TidalCanvasCheck {
    suspend fun diagnose(
        context: Context,
        title: String,
        artist: String?,
    ): CanvasSourceDiagnosis = withContext(Dispatchers.IO) {
        val accountDiagnosis = checkAccount(context)
        val catalogDiagnosis = checkCatalog(title, artist)

        merge(accountDiagnosis, catalogDiagnosis)
    }

    /** Outcome of the account leg — null means "no account, nothing to check". */
    private sealed interface AccountDiagnosis {
        data object Ok : AccountDiagnosis

        data class Rejected(val detail: String) : AccountDiagnosis

        data class Error(val detail: String) : AccountDiagnosis
    }

    private suspend fun checkAccount(context: Context): AccountDiagnosis? {
        val accessToken = readString(context, TidalAccessTokenKey)
        if (accessToken.isBlank()) return null // not signed in — not a failure

        val expiry = readLong(context, TidalTokenExpiryKey)
        val freshEnough = expiry - System.currentTimeMillis() > 60_000L
        if (freshEnough) return AccountDiagnosis.Ok

        val refreshToken = readString(context, TidalRefreshTokenKey)
        if (refreshToken.isBlank()) {
            return AccountDiagnosis.Rejected(
                "Your Tidal login was captured without a refresh token — re-login to Tidal.",
            )
        }
        val flow = readString(context, TidalAuthFlowKey).ifBlank { TidalAccountManager.FLOW_OAUTH }
        return try {
            val refreshed = TidalAccountManager.refreshAccessToken(refreshToken, flow)
            if (refreshed != null) {
                AccountDiagnosis.Ok
            } else {
                AccountDiagnosis.Rejected(
                    "Tidal refused to refresh your token — re-login to Tidal.",
                )
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            AccountDiagnosis.Error("Token refresh error: ${throwable.message ?: throwable::class.simpleName}")
        }
    }

    /** Outcome of the catalog-search leg. */
    private suspend fun checkCatalog(
        title: String,
        artist: String?,
    ): CanvasSourceDiagnosis {
        val term = (artist?.trim()?.plus(' ') ?: "") + title
        val query = term.trim().ifBlank { title }
        return try {
            val results = TidalAudioProvider.probeCatalogSearch(query)
            if (results != null) {
                val count = results.length()
                if (count > 0) {
                    CanvasSourceDiagnosis.Ok(
                        canvasFound = true,
                        detail = "Catalog answered $count match(es) for the probe track.",
                    )
                } else {
                    CanvasSourceDiagnosis.Ok(
                        canvasFound = false,
                        detail = "Catalog answered — no Tidal match for the probe track.",
                    )
                }
            } else {
                val instances = TidalAudioProvider.configuredInstanceCount()
                CanvasSourceDiagnosis.Unreachable(
                    detail =
                        if (instances > 0) {
                            "Unreachable — all $instances configured instance(s) and the public Tidal API failed."
                        } else {
                            "Unreachable — the public Tidal catalog API failed (no private instances configured)."
                        },
                )
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            CanvasSourceDiagnosis.Unreachable("Unreachable: ${throwable.message ?: throwable::class.simpleName}")
        }
    }

    private fun merge(
        account: AccountDiagnosis?,
        catalog: CanvasSourceDiagnosis,
    ): CanvasSourceDiagnosis =
        when (account) {
            null ->
                when (catalog) {
                    // No account signed in, but the catalog layer works.
                    is CanvasSourceDiagnosis.Ok ->
                        catalog.copy(
                            detail = catalog.detail + " (no Tidal account signed in — add one in Tidal settings for account-quality sources)",
                        )
                    else -> catalog
                }
            is AccountDiagnosis.Ok ->
                when (catalog) {
                    is CanvasSourceDiagnosis.Ok ->
                        catalog.copy(detail = "Account OK. " + catalog.detail)
                    else -> catalog
                }
            is AccountDiagnosis.Rejected ->
                CanvasSourceDiagnosis.Rejected(
                    httpStatus = null,
                    detail = account.detail,
                )
            is AccountDiagnosis.Error ->
                CanvasSourceDiagnosis.Unreachable(detail = account.detail)
        }

    private suspend fun readString(
        context: Context,
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
    ): String = runCatching { context.dataStore.data.first()[key] ?: "" }.getOrDefault("")

    private suspend fun readLong(
        context: Context,
        key: androidx.datastore.preferences.core.Preferences.Key<Long>,
    ): Long = runCatching { context.dataStore.data.first()[key] ?: 0L }.getOrDefault(0L)
}
