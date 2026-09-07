/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

internal class PlaybackStreamRecoveryTracker(
    private val maxAttemptsPerMediaItem: Int = 3,
) {
    private var attemptedMediaId: String? = null
    private var attemptCount = 0

    fun registerRetryAttempt(mediaId: String): Boolean {
        if (attemptedMediaId != mediaId) {
            attemptedMediaId = mediaId
            attemptCount = 0
        }
        if (attemptCount >= maxAttemptsPerMediaItem) return false
        attemptCount++
        return true
    }

    fun onPlaybackRecovered(mediaId: String?) {
        if (mediaId != null && attemptedMediaId == mediaId) {
            attemptedMediaId = null
            attemptCount = 0
        }
    }

    fun onMediaItemChanged(currentMediaId: String?) {
        if (attemptedMediaId != currentMediaId) {
            attemptedMediaId = null
            attemptCount = 0
        }
    }
}
