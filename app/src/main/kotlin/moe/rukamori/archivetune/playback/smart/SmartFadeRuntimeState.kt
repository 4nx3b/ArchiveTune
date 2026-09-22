/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Automix runtime state ported from BitChord
 * (https://github.com/kushagrasinghx/BitChord).
 */

package moe.rukamori.archivetune.playback.smart

import kotlinx.coroutines.flow.MutableStateFlow

enum class TrackAnalysisState {
    /** Nothing in flight and no result — usually waiting on bytes to arrive. */
    WAITING,

    /** Decode and inference running now; a result is a few seconds away. */
    ANALYSING,

    /** Measured, with a tempo the planner can actually use. */
    ANALYSED,

    /**
     * Measured off the track's opening, with the whole-track pass running now
     * to replace those numbers with better ones.
     */
    REFINING,

    /**
     * Tried and came back with nothing usable — a decode error, or audio that
     * yielded no tempo. Distinct from [WAITING] because nothing further will
     * happen on its own: waiting is a matter of time, this is not.
     */
    FAILED,
}

/**
 * Both sides of the next transition, for the player's Automix status line.
 *
 * A transition needs *both* tracks measured before it can beat-match or cue
 * the incoming one into its arrangement, so reporting them separately is what
 * makes a plain crossfade explicable rather than mysterious.
 */
data class SmartAnalysis(
    val current: TrackAnalysisState = TrackAnalysisState.WAITING,
    val next: TrackAnalysisState = TrackAnalysisState.WAITING,
)

/**
 * A span of the playing track, in fractions of its duration, that the next
 * transition is planned to occupy.
 */
data class TransitionWindow(val start: Float, val end: Float)

/**
 * Process-wide, publish-only surface the player UI collects: the automix
 * master switch, the per-track analysis states for the current and next
 * songs, the planned transition window, and whether a real (beat-matched,
 * cued or filtered) mix is audible right now. The smart-fade driver in
 * MusicService is the only writer.
 */
object SmartFadeRuntimeState {
    val enabled = MutableStateFlow(false)
    val analysis = MutableStateFlow(SmartAnalysis())
    val transitionWindow = MutableStateFlow<TransitionWindow?>(null)
    val mixing = MutableStateFlow(false)
}
