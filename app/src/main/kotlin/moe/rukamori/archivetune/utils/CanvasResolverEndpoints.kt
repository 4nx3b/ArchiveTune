/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

object CanvasResolverEndpoints {
    private const val MAX_ENDPOINTS = 8

    fun parse(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw
            .split('\n', ',')
            .map { it.trim().trimEnd('/') }
            .filter { candidate ->
                candidate.startsWith("http://", ignoreCase = true) ||
                    candidate.startsWith("https://", ignoreCase = true)
            }.distinct()
            .take(MAX_ENDPOINTS)
    }

    fun serialize(endpoints: List<String>): String = endpoints.joinToString("\n")
}
