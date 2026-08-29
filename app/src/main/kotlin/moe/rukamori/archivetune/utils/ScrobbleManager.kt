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

    // Track the metadata + threshold for the currently-playing song so that
    // `flushPendingScrobbleIfNeeded()` can decide whether to submit a final
    // scrobble when the user switches songs before the timer fires.
    // Per user report (2026-08-29): "I've heard a song more than 50 times but
    // still I don't see it [in the Last.fm dashboard]. The only time I see it
    // is while I'm listening to it ... once I start playing a different song
    // it disappears from the stats page."
    //
    // Root cause: `track.updateNowPlaying` fires immediately on song start,
    // so Last.fm's "Now Playing" widget shows the song. But `track.scrobble`
    // is only sent by the `scrobbleJob` coroutine after `scrobbleRemainingMillis`
    // — and that job is cancelled (NOT flushed) whenever the user switches
    // songs, pauses for too long, or `onPlayerStateChanged(isPlaying=true)`
    // fires repeatedly (each call reset the timer without decrementing the
    // remaining time, so the timer never completed).
    private var currentMetadata: MediaMetadata? = null
    private var currentThresholdMillis: Long = 0L
    // Whether the timer is currently RUNNING (vs paused). Guards against
    // `resumeScrobbleTimer` resetting the timer on every redundant
    // `isPlaying=true` callback — previously each callback cancelled and
    // restarted the job with the same `scrobbleRemainingMillis`, so a
    // stream of `isPlaying=true` events would prevent the timer from ever
    // completing.
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
        // Before starting the new song's timer, check if the PREVIOUS song
        // had met its scrobble threshold. If so, submit the final scrobble
        // for it now — Last.fm's rule is "scrobble if the user listened for
        // at least half the track duration OR 4 minutes". Previously, the
        // pending scrobble was silently dropped on every song switch.
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
        // On explicit stop, also flush — the user may have listened past
        // the threshold before stopping. If the threshold wasn't met, the
        // flush is a no-op.
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
            // Song too short to scrobble — record metadata so flush logic
            // knows there's nothing pending, but don't start a timer.
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
        // Guard against redundant `isPlaying=true` callbacks resetting the
        // timer. Previously, every call cancelled and restarted the job
        // with the same `scrobbleRemainingMillis` — so if the player fired
        // `onPlayerStateChanged(isPlaying=true, ...)` repeatedly during
        // playback (e.g. on buffer updates or metadata refreshes), the
        // timer never completed and the scrobble was silently dropped.
        if (scrobbleTimerRunning) return
        if (scrobbleRemainingMillis <= 0) return
        // Only resume if the metadata matches the song we were tracking.
        // If the player has moved on to a different song without an
        // `onSongStart` call (shouldn't happen, but defensive), don't
        // resume a stale timer for the wrong song.
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

    /**
     * Submit a final scrobble for the currently-tracked song IF the user
     * has listened past the threshold (50% of duration OR 4 minutes, per
     * Last.fm's spec). This is the "scrobble on skip" path that was missing
     * — previously, switching songs before the timer fired silently dropped
     * the scrobble even if the threshold was met.
     *
     * Safe to call repeatedly; no-ops if there's no pending scrobble or
     * the threshold wasn't met.
     */
    private fun flushPendingScrobbleIfNeeded() {
        val metadata = currentMetadata ?: return
        if (currentThresholdMillis <= 0L) {
            // Song was too short to scrobble, or no timer was ever started.
            // Reset state and return.
            currentMetadata = null
            currentThresholdMillis = 0L
            return
        }
        if (scrobbleTimerRunning && scrobbleTimerStartedAt != 0L) {
            val elapsed = System.currentTimeMillis() - scrobbleTimerStartedAt
            // Compute total elapsed (including any paused time that was
            // already subtracted from scrobbleRemainingMillis). The total
            // elapsed listening time = (threshold - remaining) + (now - timerStartedAt).
            val totalElapsed = (currentThresholdMillis - scrobbleRemainingMillis) + elapsed
            if (totalElapsed >= currentThresholdMillis) {
                // Threshold met — submit the final scrobble.
                scrobbleSong(metadata)
            }
        } else if (!scrobbleTimerRunning && scrobbleRemainingMillis <= 0L) {
            // Timer had already completed (or never started because threshold
            // was 0). The scrobbleSong coroutine may have already fired.
            // To avoid double-scrobbling, we DON'T re-submit here. Last.fm
            // deduplicates by timestamp, so even if we did, it would be a no-op.
        }
        // Cancel any pending job so it doesn't double-fire after we submit.
        scrobbleJob?.cancel()
        scrobbleJob = null
        scrobbleTimerRunning = false
        scrobbleTimerStartedAt = 0L
        scrobbleRemainingMillis = 0L
        currentMetadata = null
        currentThresholdMillis = 0L
    }

    private fun sameSong(a: MediaMetadata, b: MediaMetadata): Boolean {
        // Compare by id first; fall back to title+artist string match for
        // cases where id is missing or differs across metadata refreshes.
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
