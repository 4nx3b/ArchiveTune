/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.search

import moe.rukamori.archivetune.constants.SearchProvider
import java.util.Base64

internal const val OnlineSearchResultRoute = "search/{encodedQuery}?provider={provider}"
internal const val OnlineSearchResultRoutePrefix = "search/"
internal const val OnlineSearchResultArgument = "encodedQuery"
internal const val OnlineSearchProviderArgument = "provider"

private const val EmptyOnlineSearchQuery = "~"

private val OnlineSearchQueryEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
private val OnlineSearchQueryDecoder: Base64.Decoder = Base64.getUrlDecoder()

internal fun onlineSearchResultRoute(
    query: String,
    provider: SearchProvider = SearchProvider.YOUTUBE,
): String {
    val encodedQuery =
        if (query.isEmpty()) {
            EmptyOnlineSearchQuery
        } else {
            OnlineSearchQueryEncoder.encodeToString(query.toByteArray(Charsets.UTF_8))
        }

    return "$OnlineSearchResultRoutePrefix$encodedQuery?provider=${provider.name}"
}

internal fun decodeOnlineSearchQuery(encodedQuery: String): String =
    if (encodedQuery == EmptyOnlineSearchQuery) {
        ""
    } else {
        runCatching {
            String(
                OnlineSearchQueryDecoder.decode(encodedQuery),
                Charsets.UTF_8,
            )
        }.getOrElse {
            encodedQuery
        }
    }
