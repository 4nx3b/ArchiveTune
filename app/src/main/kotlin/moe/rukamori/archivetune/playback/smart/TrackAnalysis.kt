/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Automix analysis pipeline ported from BitChord
 * (https://github.com/kushagrasinghx/BitChord), which derives it from
 * Orchard (https://github.com/SFG5453/Orchard). Orchard's original source
 * is licensed AGPL-3.0-or-later; per AGPLv3 section 13 this file is
 * combined into ArchiveTune -- a GPL-3.0-or-later work -- and remains
 * itself governed by the AGPLv3 as part of that combination.
 */

package moe.rukamori.archivetune.playback.smart

data class TrackAnalysis(

    val status: String = "",

    val trackId: String = "",
    val duration: Double = 0.0,

    val bpm: Double = 0.0,

    val beatInterval: Double = 0.0,

    val beatConfidence: Double = 0.0,
    val downbeats: List<Double> = emptyList(),
    val phraseBoundaries: List<Double> = emptyList(),
    val firstBeat: Double = 0.0,

    val key: String = "",
    val keyConfidence: Double = 0.0,

    val audibleStartTime: Double? = null,
    val pickupTime: Double? = null,
    val introEndTime: Double = 0.0,

    val contentEndTime: Double = 0.0,
    val outroStartTime: Double = 0.0,

    val mixInTime: Double = 0.0,
    val mixOutTime: Double = 0.0,
    val mixInCandidates: List<MixCandidate> = emptyList(),
    val mixOutCandidates: List<MixCandidate> = emptyList(),

    val energyCurve: List<EnergySample> = emptyList(),

    val lowEnergyCurve: List<EnergySample> = emptyList(),

    val vocalActivityMask: List<Double> = emptyList(),

    val vocalProbability: Double = 0.0,
) {
    val isUsable: Boolean get() = status == STATUS_READY && bpm > 0

    companion object {
        const val STATUS_READY = "ready"
    }
}

data class EnergySample(val time: Double, val energy: Double)

data class MixCandidate(val time: Double, val score: Double, val type: String)

data class RankedMixCandidate(
    val time: Double,
    val score: Double,
    val type: String,
    val rankScore: Double,

    val discardedMusicSeconds: Double = 0.0,

    val measured: Boolean = true,
)

data class MixOutAnchor(
    val time: Double,
    val type: String,
    val discardedMusicSeconds: Double,
)

data class TransitionPolicyVerdict(
    val tier: TransitionTier,

    val reasons: List<String>,
    val beatConfidence: Double,
)

enum class TransitionTier {
    BEATMATCHED,

    DJ_ASSISTED,

    PLAIN_CROSSFADE,
}
