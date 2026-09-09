/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.lastfm.LastFM
import moe.rukamori.archivetune.models.MediaMetadata
import timber.log.Timber
import kotlin.math.min

class ScrobbleManager(
    private val scope: CoroutineScope,
    var minSongDuration: Int = 30,
    var scrobbleDelayPercent: Float = 0.5f,
    var scrobbleDelaySeconds: Int = 180,
) {
    private var scrobbleJob: Job? = null
    private var scrobbleRemainingMillis: Long = 0L
    private var scrobbleTimerStartedAt: Long = 0L
    private var songStartedAt: Long = 0L
    private var songStarted = false
    var useNowPlaying = true

    private var currentMetadata: MediaMetadata? = null
    private var currentThresholdMillis: Long = 0L

    private var scrobbleTimerRunning: Boolean = false

    fun destroy() {
        scrobbleJob?.cancel()
        scrobbleRemainingMillis = 0L
        scrobbleTimerStartedAt = 0L
        songStartedAt = 0L
        songStarted = false
        currentMetadata = null
        currentThresholdMillis = 0L
        scrobbleTimerRunning = false
    }

    fun onSongStart(
        metadata: MediaMetadata?,
        duration: Long? = null,
    ) {
        if (metadata == null) return

        flushPendingScrobbleIfNeeded()
        songStartedAt = System.currentTimeMillis() / 1000
        songStarted = true
        startScrobbleTimer(metadata, duration)
        if (useNowPlaying) {
            updateNowPlaying(metadata)
        }
    }

    fun onSongResume(metadata: MediaMetadata) {
        resumeScrobbleTimer(metadata)
    }

    fun onSongPause() {
        pauseScrobbleTimer()
    }

    fun onSongStop() {

        flushPendingScrobbleIfNeeded()
        stopScrobbleTimer()
        songStarted = false
    }

    private fun startScrobbleTimer(
        metadata: MediaMetadata,
        duration: Long? = null,
    ) {
        scrobbleJob?.cancel()
        val resolvedDuration = duration?.toInt()?.div(1000) ?: metadata.duration

        if (resolvedDuration <= minSongDuration) {

            currentMetadata = metadata
            currentThresholdMillis = 0L
            scrobbleTimerRunning = false
            return
        }

        val threshold = resolvedDuration * 1000L * scrobbleDelayPercent
        scrobbleRemainingMillis = min(threshold.toLong(), scrobbleDelaySeconds * 1000L)
        currentThresholdMillis = scrobbleRemainingMillis
        currentMetadata = metadata

        if (scrobbleRemainingMillis <= 0) {
            scrobbleSong(metadata)
            scrobbleTimerRunning = false
            return
        }
        scrobbleTimerStartedAt = System.currentTimeMillis()
        scrobbleTimerRunning = true
        scrobbleJob =
            scope.launch {
                delay(scrobbleRemainingMillis)
                scrobbleSong(metadata)
                scrobbleJob = null
                scrobbleTimerRunning = false
                scrobbleTimerStartedAt = 0L
            }
    }

    private fun pauseScrobbleTimer() {
        if (!scrobbleTimerRunning) return
        scrobbleJob?.cancel()
        if (scrobbleTimerStartedAt != 0L) {
            val elapsed = System.currentTimeMillis() - scrobbleTimerStartedAt
            scrobbleRemainingMillis -= elapsed
            if (scrobbleRemainingMillis < 0) scrobbleRemainingMillis = 0
            scrobbleTimerStartedAt = 0L
        }
        scrobbleTimerRunning = false
    }

    private fun resumeScrobbleTimer(metadata: MediaMetadata) {

        if (scrobbleTimerRunning) return
        if (scrobbleRemainingMillis <= 0) return

        val current = currentMetadata
        if (current != null && !sameSong(current, metadata)) return
        scrobbleJob?.cancel()
        scrobbleTimerStartedAt = System.currentTimeMillis()
        scrobbleTimerRunning = true
        scrobbleJob =
            scope.launch {
                delay(scrobbleRemainingMillis)
                scrobbleSong(current ?: metadata)
                scrobbleJob = null
                scrobbleTimerRunning = false
                scrobbleTimerStartedAt = 0L
            }
    }

    private fun stopScrobbleTimer() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        scrobbleRemainingMillis = 0
        scrobbleTimerRunning = false
        scrobbleTimerStartedAt = 0L
    }

    private fun flushPendingScrobbleIfNeeded() {
        val metadata = currentMetadata ?: return
        if (currentThresholdMillis <= 0L) {

            currentMetadata = null
            currentThresholdMillis = 0L
            return
        }
        if (scrobbleTimerRunning && scrobbleTimerStartedAt != 0L) {
            val elapsed = System.currentTimeMillis() - scrobbleTimerStartedAt

            val totalElapsed = (currentThresholdMillis - scrobbleRemainingMillis) + elapsed
            if (totalElapsed >= currentThresholdMillis) {

                scrobbleSong(metadata)
            }
        } else if (!scrobbleTimerRunning && scrobbleRemainingMillis <= 0L) {

        }

        scrobbleJob?.cancel()
        scrobbleJob = null
        scrobbleTimerRunning = false
        scrobbleTimerStartedAt = 0L
        scrobbleRemainingMillis = 0L
        currentMetadata = null
        currentThresholdMillis = 0L
    }

    private fun sameSong(a: MediaMetadata, b: MediaMetadata): Boolean {

        if (a.id == b.id) return true
        if (a.title == b.title &&
            a.artists.size == b.artists.size &&
            a.artists.zip(b.artists).all { (x, y) -> x.name == y.name }
        ) return true
        return false
    }

    private fun scrobbleSong(metadata: MediaMetadata) {
        scope.launch {
            LastFM
                .scrobble(
                    artist = metadata.artists.joinToString(", ") { artist -> artist.name },
                    track = metadata.title,
                    duration = metadata.duration,
                    timestamp = songStartedAt,
                    album = metadata.album?.title,
                ).onSuccess {
                    Timber
                        .tag(
                            "ScrobbleManager",
                        ).d("Scrobbled: ${metadata.title} by ${metadata.artists.joinToString(", ") { artist -> artist.name }}")
                }.onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    Timber.tag("ScrobbleManager").e(throwable, "Failed to scrobble: ${metadata.title}")
                }
        }
    }

    private fun updateNowPlaying(metadata: MediaMetadata) {
        scope.launch {
            LastFM
                .updateNowPlaying(
                    artist = metadata.artists.joinToString(", ") { artist -> artist.name },
                    track = metadata.title,
                    album = metadata.album?.title,
                    duration = metadata.duration,
                ).onSuccess {
                    Timber.tag("ScrobbleManager").d("Updated now playing: ${metadata.title}")
                }.onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    Timber.tag("ScrobbleManager").e(throwable, "Failed to update now playing: ${metadata.title}")
                }
        }
    }

    fun onPlayerStateChanged(
        isPlaying: Boolean,
        metadata: MediaMetadata?,
        duration: Long? = null,
    ) {
        if (metadata == null) return
        if (isPlaying) {
            if (!songStarted) {
                onSongStart(metadata, duration)
            } else {
                onSongResume(metadata)
            }
        } else {
            onSongPause()
        }
    }
}
