/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.automix

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

/** Analysis cadence of the energy/vocal curves (one value per 250 ms). */
const val AUTO_MIX_CURVE_STEP_MS = 250L

@Serializable
data class AutoMixAnalysis(
    val durationMs: Long,
    /** Estimated tempo in beats per minute; 0.0 when the grid is unusable. */
    val bpm: Double,
    val beatIntervalMs: Double,
    /** 0..1 prominence of the autocorrelation tempo peak. */
    val beatConfidence: Double,
    /** Grid origin (ms) of the detected downbeats (4/4 assumed). */
    val downbeatPhaseMs: Double,
    val audibleStartMs: Long,
    val introEndMs: Long,
    val outroStartMs: Long,
    val contentEndMs: Long,
    /**
     * Dynamic mix-out trigger: the position where the track's own energy
     * begins its final sustained decline toward silence (the natural
     * fade-out). Equal to [contentEndMs] for hard cuts (no natural fade) and
     * 0 for legacy cached analyses written before this field existed.
     */
    val finalFadeOnsetMs: Long = 0L,
    val mixInCandidatesMs: List<Long>,
    val mixOutCandidatesMs: List<Long>,
    /** Loudness curve, one sample per [AUTO_MIX_CURVE_STEP_MS]. */
    val energyCurve: List<Float>,
    /** Vocal-likelihood curve (0..1), one sample per [AUTO_MIX_CURVE_STEP_MS]. */
    val vocalActivity: List<Float>,
) {
    val isUsable: Boolean
        get() = durationMs > 0L && contentEndMs > 0L && energyCurve.isNotEmpty()

    fun energyAt(ms: Long): Float = curveValueAt(energyCurve, ms)

    fun vocalAt(ms: Long): Float = curveValueAt(vocalActivity, ms)

    private fun curveValueAt(curve: List<Float>, ms: Long): Float {
        if (curve.isEmpty()) return 0f
        val index = (ms / AUTO_MIX_CURVE_STEP_MS).toInt()
        return curve.getOrElse(index) { if (index < 0) curve.first() else curve.last() }
    }

    companion object {
        fun unusable(durationMs: Long) =
            AutoMixAnalysis(
                durationMs = durationMs,
                bpm = 0.0,
                beatIntervalMs = 0.0,
                beatConfidence = 0.0,
                downbeatPhaseMs = 0.0,
                audibleStartMs = 0L,
                introEndMs = 0L,
                outroStartMs = durationMs,
                contentEndMs = durationMs,
                finalFadeOnsetMs = durationMs,
                mixInCandidatesMs = emptyList(),
                mixOutCandidatesMs = emptyList(),
                energyCurve = emptyList(),
                vocalActivity = emptyList(),
            )
    }
}

enum class AutoMixAnalysisState {
    WAITING,
    ANALYSING,
    ANALYSED,
    FAILED,
}

data class AutoMixAnalysisStates(
    val current: AutoMixAnalysisState = AutoMixAnalysisState.WAITING,
    val next: AutoMixAnalysisState = AutoMixAnalysisState.WAITING,
)

data class AutoMixTransitionWindow(
    val startFraction: Float,
    val endFraction: Float,
)

/** UI-facing runtime state, consumed by the BitChord status line. */
object AutoMixUiState {
    val enabled = MutableStateFlow(false)
    val analysis = MutableStateFlow(AutoMixAnalysisStates())
    val transitionWindow = MutableStateFlow<AutoMixTransitionWindow?>(null)
    val mixing = MutableStateFlow(false)
}

enum class AutoMixStyle {
    /** Same-album consecutive tracks: let the primary player run through. */
    GAPLESS,

    /** Plain equal-power fade - no beat alignment available. */
    EQUAL_POWER,

    /** Beat-matched overlap with optional tempo alignment and a bass handover. */
    DJ_BLEND,

    /** Filter-ride blend: outgoing low-passes out, incoming high-passes in. */
    DJ_FILTER,
}

data class AutoMixTrackInfo(
    val id: String,
    val durationMs: Long,
    val title: String,
    val artist: String,
    val album: String,
)

data class AutoMixPlan(
    val blocked: Boolean,
    val reason: String,
    val style: AutoMixStyle,
    /** Wall-clock millisecond marks of the transition inside the OUTGOING track. */
    val transitionStartMs: Long,
    val transitionEndMs: Long,
    val fadeMs: Long,
    /** Start position of the incoming track when it fades in. */
    val incomingCueMs: Long,
    /** Playback-rate alignment for the incoming player (1.0 = native). */
    val incomingPlaybackRate: Double,
    val bassSwap: Boolean,
    /** 0..1 position inside [transitionStartMs, transitionEndMs] where the bass hands over. */
    val bassSwapFraction: Double,
    /** 0..1 strength of the low/high-pass ride. */
    val filterSweep: Double,
    /** 0..1 estimated vocal clash across the overlap. */
    val vocalOverlap: Double,
    val markerVisible: Boolean,
) {
    companion object {
        fun fallback(
            fadeMs: Long,
            anchorMs: Long,
            reason: String,
        ): AutoMixPlan =
            AutoMixPlan(
                blocked = false,
                reason = reason,
                style = AutoMixStyle.EQUAL_POWER,
                transitionStartMs = anchorMs,
                transitionEndMs = anchorMs + fadeMs,
                fadeMs = fadeMs,
                incomingCueMs = 0L,
                incomingPlaybackRate = 1.0,
                bassSwap = false,
                bassSwapFraction = 0.5,
                filterSweep = 0.0,
                vocalOverlap = 0.0,
                markerVisible = false,
            )
    }
}

/** Analysis quality/parallelism requested from the settings screen. */
enum class AutoMixPerformanceMode {
    EFFICIENT,
    BALANCED,
    PERFORMANCE,
}
