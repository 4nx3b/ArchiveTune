/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.together

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

object TogetherOnlineEndpoint {
    @Suppress("UNUSED_PARAMETER")
    suspend fun baseUrlOrNull(dataStore: DataStore<Preferences>): String? = null

    @Suppress("UNUSED_PARAMETER")
    fun onlineWebSocketUrlOrNull(
        rawWsUrl: String,
        baseUrl: String,
    ): String? = null
}
