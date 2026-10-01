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
    WAITING,

    ANALYSING,

    ANALYSED,

    REFINING,

    FAILED,
}

data class SmartAnalysis(
    val current: TrackAnalysisState = TrackAnalysisState.WAITING,
    val next: TrackAnalysisState = TrackAnalysisState.WAITING,
)

data class TransitionWindow(val start: Float, val end: Float)

object SmartFadeRuntimeState {
    val enabled = MutableStateFlow(false)
    val analysis = MutableStateFlow(SmartAnalysis())
    val transitionWindow = MutableStateFlow<TransitionWindow?>(null)
    val mixing = MutableStateFlow(false)
}
