/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.lastfm

object LastFmAppCredentials {
    const val API_KEY = "e2c8e7a67eaeb0fe5a71ee539a34641a"
    const val API_SECRET = "94b5c6aa634e459defedbf8180625e8a"

    const val AUTH_CALLBACK_URI = "archivetune://lastfm-auth-callback"

    fun authUrl(): String =
        "https://www.last.fm/api/auth/?api_key=$API_KEY&cb=$AUTH_CALLBACK_URI"
}
