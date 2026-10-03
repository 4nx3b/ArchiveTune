/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.automix

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Clean-room transition planner. Chooses the transition tier from the two
 * track analyses and computes the concrete anchors:
 *
 *  - same-album consecutive tracks                -> GAPLESS
 *  - both beat grids confident + tempo compatible -> DJ_BLEND (beat-matched
 *    overlap, optional +/-4% incoming-rate alignment, bass handover)
 *  - at least one grid partially usable           -> DJ_FILTER (filter ride)
 *  - otherwise                                    -> EQUAL_POWER at the outgoing
 *    track's own structural anchor (outro start / content end)
 *
 * Vocal clash across the overlap shrinks it; a clash too large to shrink
 * degrades the tier to the filter ride.
 */
object AutoMixPlanner {
    private const val MIN_BEATMATCH_CONFIDENCE = 0.55
    private const val MIN_FILTER_CONFIDENCE = 0.20
    private const val MAX_RATE_ALIGNMENT = 0.04
    private const val MIN_OVERLAP_BEATS = 4.0
    private const val MAX_OVERLAP_BEATS = 16.0
    private const val MAX_OVERLAP_MS = 12_000L
    private const val END_GUARD_MS = 1_500L

    fun plan(
        current: AutoMixAnalysis?,
        next: AutoMixAnalysis?,
        currentTrack: AutoMixTrackInfo,
        nextTrack: AutoMixTrackInfo,
        positionMs: Long,
        fallbackFadeMs: Long,
        minFadeMs: Long,
        gaplessAlbum: Boolean,
    ): AutoMixPlan {
        val currentUsable = current?.isUsable == true
        val nextUsable = next?.isUsable == true

        if (gaplessAlbum) {
            return AutoMixPlan(
                blocked = false,
                reason = "same-album sequence",
                style = AutoMixStyle.GAPLESS,
                transitionStartMs = currentTrack.durationMs,
                transitionEndMs = currentTrack.durationMs,
                fadeMs = 0L,
                incomingCueMs = 0L,
                incomingPlaybackRate = 1.0,
                bassSwap = false,
                bassSwapFraction = 0.5,
                filterSweep = 0.0,
                vocalOverlap = 0.0,
                markerVisible = currentUsable && nextUsable,
            )
        }

        if (!currentUsable || !nextUsable) {
            val anchor = fallbackAnchor(current, currentTrack)
            return AutoMixPlan.fallback(
                fadeMs = fallbackFadeMs,
                anchorMs = anchor,
                reason = if (!currentUsable && !nextUsable) "no analysis" else "partial analysis",
            )
        }
        current!!
        next!!

        val outgoingAnchor = pickMixOutAnchor(current, currentTrack)
        val incomingCue = pickMixInCue(next, nextTrack)

        val tempoRatio =
            if (current.bpm > 1.0 && next.bpm > 1.0) next.bpm / current.bpm else 1.0
        val beatMatchable =
            current.beatConfidence >= MIN_BEATMATCH_CONFIDENCE &&
                next.beatConfidence >= MIN_BEATMATCH_CONFIDENCE &&
                abs(tempoRatio - 1.0) <= MAX_RATE_ALIGNMENT
        val filterable =
            current.beatConfidence >= MIN_FILTER_CONFIDENCE ||
                next.beatConfidence >= MIN_FILTER_CONFIDENCE

        val availableMs =
            (currentTrack.durationMs - outgoingAnchor - END_GUARD_MS).coerceAtLeast(0L)

        return when {
            beatMatchable -> planBeatMatched(
                current = current,
                next = next,
                currentTrack = currentTrack,
                outgoingAnchor = outgoingAnchor,
                incomingCue = incomingCue,
                availableMs = availableMs,
                fallbackFadeMs = fallbackFadeMs,
                minFadeMs = minFadeMs,
            )

            filterable -> planFilterRide(
                current = current,
                outgoingAnchor = outgoingAnchor,
                incomingCue = incomingCue,
                availableMs = availableMs,
                fallbackFadeMs = fallbackFadeMs,
                minFadeMs = minFadeMs,
            )

            else -> AutoMixPlan.fallback(
                fadeMs = fallbackFadeMs,
                anchorMs = outgoingAnchor,
                reason = "low beat confidence",
            )
        }
    }

    // -------------------------------------------------------------------

    private fun planBeatMatched(
        current: AutoMixAnalysis,
        next: AutoMixAnalysis,
        currentTrack: AutoMixTrackInfo,
        outgoingAnchor: Long,
        incomingCue: Long,
        availableMs: Long,
        fallbackFadeMs: Long,
        minFadeMs: Long,
    ): AutoMixPlan {
        val beatMs = current.beatIntervalMs
        if (beatMs <= 0.0) {
            return AutoMixPlan.fallback(fallbackFadeMs, outgoingAnchor, "no beat interval")
        }

        val overlapBeats =
            (MAX_OVERLAP_BEATS.coerceAtMost(availableMs / beatMs))
                .coerceAtLeast(MIN_OVERLAP_BEATS)
        var overlapMs = (overlapBeats * beatMs).roundToLong()
            .coerceAtMost(MAX_OVERLAP_MS)
            .coerceAtLeast(minFadeMs)

        // Vocal clash: shrink the overlap while both sides sing; below the
        // minimum, degrade to the filter ride instead of butchering vocals.
        var vocalOverlap = vocalClashAcross(current, next, outgoingAnchor, incomingCue, overlapMs)
        if (vocalOverlap > 0.55 && overlapMs > minFadeMs * 1.5) {
            overlapMs = (overlapMs * 0.6).roundToLong().coerceAtLeast(minFadeMs)
            vocalOverlap = vocalClashAcross(current, next, outgoingAnchor, incomingCue, overlapMs)
        }
        if (overlapMs < minFadeMs || vocalOverlap > 0.8) {
            return filterRidePlan(
                outgoingAnchor = outgoingAnchor,
                incomingCue = incomingCue,
                fadeMs = fallbackFadeMs.coerceAtMost(availableMs).coerceAtLeast(minFadeMs),
                vocalOverlap = vocalOverlap,
                reason = "vocal clash",
            )
        }

        // Align the transition start to the nearest downbeat at or after the
        // structural anchor.
        val startMs = alignToDownbeat(current, outgoingAnchor)
        val endMs = startMs + overlapMs

        val rateAlignment =
            if (next.bpm > 1.0 && current.bpm > 1.0) {
                (current.bpm / next.bpm).coerceIn(1.0 - MAX_RATE_ALIGNMENT, 1.0 + MAX_RATE_ALIGNMENT)
            } else {
                1.0
            }

        // Bass handover at the quietest beat inside the overlap.
        val bassSwapFraction = quietestBeatFraction(current, startMs, overlapMs)

        return AutoMixPlan(
            blocked = false,
            reason = "beat-matched %.1f->%.1f bpm".format(current.bpm, next.bpm),
            style = AutoMixStyle.DJ_BLEND,
            transitionStartMs = startMs,
            transitionEndMs = endMs,
            fadeMs = overlapMs,
            incomingCueMs = incomingCue,
            incomingPlaybackRate = rateAlignment,
            bassSwap = true,
            bassSwapFraction = bassSwapFraction,
            filterSweep = 0.0,
            vocalOverlap = vocalOverlap,
            markerVisible = true,
        )
    }

    private fun planFilterRide(
        current: AutoMixAnalysis,
        outgoingAnchor: Long,
        incomingCue: Long,
        availableMs: Long,
        fallbackFadeMs: Long,
        minFadeMs: Long,
    ): AutoMixPlan {
        val fadeMs = fallbackFadeMs
            .coerceAtMost(availableMs)
            .coerceAtLeast(minFadeMs)
            .coerceAtMost(MAX_OVERLAP_MS)
        val vocalOverlap = vocalClashAcross(current, current, outgoingAnchor, incomingCue, fadeMs)
        return filterRidePlan(
            outgoingAnchor = outgoingAnchor,
            incomingCue = incomingCue,
            fadeMs = fadeMs,
            vocalOverlap = vocalOverlap,
            reason = "filter ride",
        )
    }

    private fun filterRidePlan(
        outgoingAnchor: Long,
        incomingCue: Long,
        fadeMs: Long,
        vocalOverlap: Double,
        reason: String,
    ): AutoMixPlan =
        AutoMixPlan(
            blocked = false,
            reason = reason,
            style = AutoMixStyle.DJ_FILTER,
            transitionStartMs = outgoingAnchor,
            transitionEndMs = outgoingAnchor + fadeMs,
            fadeMs = fadeMs,
            incomingCueMs = incomingCue,
            incomingPlaybackRate = 1.0,
            bassSwap = false,
            bassSwapFraction = 0.5,
            filterSweep = 1.0,
            vocalOverlap = vocalOverlap,
            markerVisible = true,
        )

    // -------------------------------------------------------------------

    /** Outgoing anchor: earliest structural mix-out candidate with room to blend. */
    private fun pickMixOutAnchor(
        analysis: AutoMixAnalysis,
        track: AutoMixTrackInfo,
    ): Long {
        val hardLimit = (track.durationMs - END_GUARD_MS).coerceAtLeast(0L)
        val fallback = (analysis.outroStartMs)
            .coerceAtMost(hardLimit)
            .coerceAtLeast(0L)
        val candidates = analysis.mixOutCandidatesMs
            .filter { it in 1L until hardLimit }
            .ifEmpty { listOf(fallback) }
        // Prefer the LATEST candidate that still leaves >= 8s of room; the
        // later anchor keeps more of the outgoing track intact.
        return candidates.lastOrNull { it <= hardLimit - 8_000L } ?: candidates.first()
    }

    /** Incoming cue: a mix-in candidate or the intro end, aligned to a downbeat. */
    private fun pickMixInCue(
        analysis: AutoMixAnalysis,
        track: AutoMixTrackInfo,
    ): Long {
        val base = analysis.mixInCandidatesMs.firstOrNull { it > 0L && it < track.durationMs / 2 }
            ?: analysis.introEndMs.coerceIn(0L, track.durationMs / 2)
        return alignToDownbeat(analysis, base)
    }

    /** Snaps a millisecond position to the nearest detected downbeat. */
    private fun alignToDownbeat(
        analysis: AutoMixAnalysis,
        ms: Long,
    ): Long {
        val bar = analysis.downbeatPhaseMs
        val interval = analysis.beatIntervalMs * 4
        if (interval <= 0.0 || bar.isNaN()) return ms
        val phase = (ms - bar) / interval
        val snapped = bar + kotlin.math.ceil(phase) * interval
        val snappedDown = bar + kotlin.math.floor(phase) * interval
        val up = snapped.roundToLong()
        val down = snappedDown.roundToLong()
        return if (abs(up - ms) <= abs(down - ms)) up.coerceAtLeast(0L) else down.coerceAtLeast(0L)
    }

    /**
     * Fraction of simultaneous vocal activity across the overlap window,
     * sampled from both tracks' vocal curves.
     */
    private fun vocalClashAcross(
        current: AutoMixAnalysis,
        next: AutoMixAnalysis,
        currentStartMs: Long,
        nextCueMs: Long,
        overlapMs: Long,
    ): Double {
        if (overlapMs <= 0L) return 0.0
        val steps = (overlapMs / AUTO_MIX_CURVE_STEP_MS).toInt().coerceIn(1, 48)
        var clash = 0.0
        for (s in 0 until steps) {
            val offset = s * AUTO_MIX_CURVE_STEP_MS
            val a = current.vocalAt(currentStartMs + offset)
            val b = next.vocalAt(nextCueMs + offset)
            clash += minOf(a, b).toDouble()
        }
        return (clash / steps).coerceIn(0.0, 1.0)
    }

    /** Position (0..1) of the lowest-energy beat inside the overlap window. */
    private fun quietestBeatFraction(
        analysis: AutoMixAnalysis,
        startMs: Long,
        overlapMs: Long,
    ): Double {
        val beat = analysis.beatIntervalMs
        if (beat <= 0.0 || overlapMs <= 0L) return 0.5
        var bestFraction = 0.5
        var bestEnergy = Float.MAX_VALUE
        var t = 0.0
        while (t < overlapMs) {
            val energy = analysis.energyAt(startMs + t.roundToLong())
            if (energy < bestEnergy) {
                bestEnergy = energy
                bestFraction = t / overlapMs
            }
            t += beat
        }
        return bestFraction.coerceIn(0.15, 0.85)
    }

    private fun fallbackAnchor(
        analysis: AutoMixAnalysis?,
        track: AutoMixTrackInfo,
    ): Long {
        val anchor = analysis?.outroStartMs ?: track.durationMs
        return (anchor - 12_000L).coerceAtLeast(0L)
    }
}
