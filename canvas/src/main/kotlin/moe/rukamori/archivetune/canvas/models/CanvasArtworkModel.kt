/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.canvas.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CanvasArtwork(
    val name: String? = null,
    val artist: String? = null,
    @SerialName("albumId")
    val albumId: String? = null,
    val albumName: String? = null,
    val static: String? = null,
    val animated: String? = null,
    val animatedVertical: String? = null,
    val videoUrl: String? = null,
    val videoUrlVertical: String? = null,

    /**
     * Origin of the artwork (see the PROVIDER_* constants). The playback
     * cache rewrites the URL fields to local file URIs once a video is
     * downloaded, so the provider must be recorded explicitly to stay
     * distinguishable — the "which provider filled which field" shape only
     * holds for freshly resolved artwork. Optional with a default so older
     * persisted cache JSON keeps decoding.
     */
    val provider: String? = null,
) {
    val preferredAnimationUrl: String?
        get() = animated ?: videoUrl

    val preferredVerticalAnimationUrl: String?
        get() = animatedVertical ?: videoUrlVertical

    /**
     * Provider, falling back to the fresh-resolve field shape when the tag
     * is missing (legacy cache entries): Spotify canvases only populate the
     * videoUrl fields, Apple Music / ArchiveTune animated artwork only
     * populates the animated fields.
     */
    fun inferredProvider(): String? =
        when {
            provider != null -> provider
            !animated.isNullOrBlank() || !animatedVertical.isNullOrBlank() -> PROVIDER_APPLE_MUSIC
            !videoUrl.isNullOrBlank() || !videoUrlVertical.isNullOrBlank() -> PROVIDER_SPOTIFY
            else -> null
        }

    companion object {
        const val PROVIDER_SPOTIFY = "spotify"
        const val PROVIDER_APPLE_MUSIC = "apple_music"
    }
}
