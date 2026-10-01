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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

const val MIN_BEATMATCH_CONFIDENCE = 0.55

const val MIN_DJ_CONFIDENCE = 0.2

const val MIN_BPM = 40.0
const val MAX_BPM = 220.0

const val MAX_STRETCH_DEVIATION = 0.04

const val VOCAL_ACTIVE_THRESHOLD = 0.6

const val MAX_DISCARDED_MUSIC_SECONDS = 12.0

private const val AUDIBLE_ENERGY_FRACTION = 0.1

private val MIX_IN_TYPE_WEIGHT = mapOf(
    "main_drop" to 0.5,
    "intro_drop" to 0.4,
    "pickup" to 0.15,
    "phrase" to 0.1,
)

private val MIX_OUT_TYPE_SCORE = mapOf(
    "energy_cliff" to 0.95,
    "interior_mix_out" to 0.95,
    "outro_start" to 0.9,
    "content_end" to 0.75,
)

internal fun Double.orZero(): Double = if (isFinite()) this else 0.0

internal fun Double?.orZero(): Double = if (this != null && isFinite()) this else 0.0

internal fun clamp(value: Double, min: Double, max: Double): Double =
    if (value.isFinite()) max(min, min(max, value)) else min

fun alignTempoOctave(outgoingBpm: Double, incomingBpm: Double): Double {
    if (outgoingBpm <= 0 || incomingBpm <= 0) return incomingBpm
    var aligned = incomingBpm
    while (aligned / outgoingBpm > 1.5) aligned /= 2
    while (aligned / outgoingBpm < 0.67) aligned *= 2
    return aligned
}

fun vocalActivityBetween(analysis: TrackAnalysis, start: Double, end: Double): Double? {
    val mask = analysis.vocalActivityMask
    val curve = analysis.energyCurve
    if (mask.isEmpty() || mask.size != curve.size || end <= start) return null
    var sum = 0.0
    var count = 0
    for (index in mask.indices) {
        val time = curve[index].time
        if (!time.isFinite() || time < start || time > end) continue
        val value = mask[index]
        if (!value.isFinite()) continue
        sum += value
        count += 1
    }
    return if (count > 0) sum / count else null
}

fun isVocalClash(outgoingActivity: Double?, incomingActivity: Double?): Boolean =
    outgoingActivity != null &&
        incomingActivity != null &&
        outgoingActivity >= VOCAL_ACTIVE_THRESHOLD &&
        incomingActivity >= VOCAL_ACTIVE_THRESHOLD

fun vocalOverlapAmount(outgoingActivity: Double?, incomingActivity: Double?): Double {
    if (outgoingActivity == null || incomingActivity == null) return 0.0
    val both = min(outgoingActivity, incomingActivity)
    if (both <= VOCAL_ACTIVE_THRESHOLD) return 0.0
    return ((both - VOCAL_ACTIVE_THRESHOLD) / (1.0 - VOCAL_ACTIVE_THRESHOLD)).coerceIn(0.0, 1.0)
}

fun simultaneousVocalFraction(
    outgoing: TrackAnalysis,
    incoming: TrackAnalysis,
    outStart: Double,
    outEnd: Double,
    inStart: Double,
    rate: Double,
): Double? {
    val outMask = outgoing.vocalActivityMask
    val outCurve = outgoing.energyCurve
    val inMask = incoming.vocalActivityMask
    val inCurve = incoming.energyCurve
    if (outMask.isEmpty() || outMask.size != outCurve.size) return null
    if (inMask.isEmpty() || inMask.size != inCurve.size) return null
    if (outEnd <= outStart) return null
    val step = if (rate.isFinite() && rate > 0) rate else 1.0

    var inIndex = 0
    var both = 0
    var total = 0
    for (index in outMask.indices) {
        val time = outCurve[index].time
        if (!time.isFinite() || time < outStart) continue
        if (time > outEnd) break
        total += 1
        if (outMask[index] < VOCAL_ACTIVE_THRESHOLD) continue
        val target = inStart + (time - outStart) * step
        while (inIndex + 1 < inCurve.size && inCurve[inIndex + 1].time <= target) inIndex += 1
        if (inMask[inIndex] >= VOCAL_ACTIVE_THRESHOLD) both += 1
    }
    return if (total > 0) both.toDouble() / total else null
}

const val VOCAL_CLASH_TOLERANCE = 0.05

fun audibleSecondsBetween(analysis: TrackAnalysis, start: Double, end: Double): Double? {
    val curve = analysis.energyCurve
    if (curve.size < 2 || end <= start) return null
    val energies = curve.map { it.energy }.filter { it.isFinite() && it >= 0 }.sorted()
    if (energies.isEmpty()) return null
    val reference = energies[floor((energies.size - 1) * 0.85).toInt()].orZero()
    if (reference <= 0) return 0.0
    val threshold = reference * AUDIBLE_ENERGY_FRACTION
    val first = curve.first().time
    val last = curve.last().time
    if (!first.isFinite() || !last.isFinite() || last <= first) return null
    val sampleSeconds = (last - first) / (curve.size - 1)
    var audible = 0.0
    for (point in curve) {
        if (!point.time.isFinite() || point.time < start || point.time > end) continue
        if (point.energy >= threshold) audible += sampleSeconds
    }
    return audible
}

internal fun audibleStartOf(analysis: TrackAnalysis): Double {
    val firstBeat = analysis.firstBeat.takeIf { it.isFinite() && it > 0 }
    val candidates = listOfNotNull(analysis.audibleStartTime, analysis.pickupTime, firstBeat)
        .filter { it.isFinite() && it >= 0 }
    return candidates.minOrNull() ?: 0.0
}

internal fun nearestValue(values: List<Double>, target: Double, tolerance: Double): Double? =
    values.filter { it.isFinite() && abs(it - target) <= tolerance }
        .minByOrNull { abs(it - target) }

fun rankMixInCandidates(analysis: TrackAnalysis): List<RankedMixCandidate> {
    val candidates = analysis.mixInCandidates.filter { it.time.isFinite() && it.time >= 0 }
    if (candidates.isEmpty()) return emptyList()
    val beatSeconds = analysis.beatInterval.orZero()
        .takeIf { it > 0 }
        ?: if (analysis.bpm.orZero() > 0) 60 / analysis.bpm else 0.5
    val audibleStart = audibleStartOf(analysis)
    return candidates.map { candidate ->
        var rankScore = candidate.score.orZero() + (MIX_IN_TYPE_WEIGHT[candidate.type] ?: 0.0)
        if (nearestValue(analysis.downbeats, candidate.time, beatSeconds / 2) != null) rankScore += 0.1

        if (candidate.time - audibleStart < beatSeconds * 4) rankScore -= 0.2

        val vocal = vocalActivityBetween(
            analysis,
            max(audibleStart, candidate.time - beatSeconds * 16),
            candidate.time,
        )
        if (vocal != null) rankScore += (0.5 - vocal) * 0.4
        RankedMixCandidate(
            time = candidate.time,
            score = candidate.score.orZero(),
            type = candidate.type,
            rankScore = rankScore,
        )
    }.sortedByDescending { it.rankScore }
}

private fun mixOutCandidatesOf(analysis: TrackAnalysis, contentEnd: Double): List<MixCandidate> {
    val supplied = analysis.mixOutCandidates.filter { it.time.isFinite() && it.time > 0 }
    val candidates = supplied.map {
        MixCandidate(time = it.time, score = it.score.orZero(), type = it.type)
    }.toMutableList()
    if (supplied.isEmpty()) {
        val mixOut = analysis.mixOutTime.orZero()
        val outroStart = analysis.outroStartTime.orZero()
        if (mixOut > 0 && mixOut < contentEnd - 1) {
            candidates += MixCandidate(mixOut, 0.95, "energy_cliff")
        }
        if (outroStart > 0 && outroStart < contentEnd - 1) {
            candidates += MixCandidate(outroStart, 0.9, "outro_start")
        }
    }

    if (candidates.none { abs(it.time - contentEnd) < 0.05 }) {
        candidates += MixCandidate(contentEnd, 0.75, "content_end")
    }
    return candidates
}

private fun resolveContentEnd(analysis: TrackAnalysis, contentEnd: Double, duration: Double): Double =
    contentEnd.orZero().takeIf { it != 0.0 }
        ?: analysis.contentEndTime.orZero().takeIf { it != 0.0 }
        ?: duration.orZero().takeIf { it != 0.0 }
        ?: analysis.duration.orZero()

fun rankMixOutCandidates(
    analysis: TrackAnalysis,
    contentEnd: Double = 0.0,
    duration: Double = 0.0,
): List<RankedMixCandidate> {
    val end = resolveContentEnd(analysis, contentEnd, duration)
    if (end <= 0) return emptyList()
    return mixOutCandidatesOf(analysis, end)
        .map { candidate ->
            val measured = audibleSecondsBetween(analysis, candidate.time, end)

            RankedMixCandidate(
                time = candidate.time,
                score = candidate.score,
                type = candidate.type,
                rankScore = candidate.score + (MIX_OUT_TYPE_SCORE[candidate.type] ?: 0.0),
                discardedMusicSeconds = measured ?: max(0.0, end - candidate.time),
                measured = measured != null,
            )
        }
        .filter { it.discardedMusicSeconds <= MAX_DISCARDED_MUSIC_SECONDS }
        .sortedWith(compareByDescending<RankedMixCandidate> { it.rankScore }.thenByDescending { it.time })
}

fun resolveMixOutAnchor(
    analysis: TrackAnalysis,
    contentEnd: Double = 0.0,
    duration: Double = 0.0,
): MixOutAnchor {
    val end = resolveContentEnd(analysis, contentEnd, duration)
    val best = rankMixOutCandidates(analysis, end, duration).firstOrNull()
    return MixOutAnchor(
        time = best?.time ?: end,
        type = best?.type ?: "content_end",
        discardedMusicSeconds = best?.discardedMusicSeconds ?: 0.0,
    )
}

fun assessTransitionTier(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
): TransitionPolicyVerdict {
    val outgoingBpm = analysis.bpm.orZero()
    val incomingBpm = nextAnalysis.bpm.orZero()
    val outgoingConfidence = analysis.beatConfidence.orZero()
    val incomingConfidence = nextAnalysis.beatConfidence.orZero()
    val floorConfidence = min(outgoingConfidence, incomingConfidence)
    val reasons = mutableListOf<String>()

    if (outgoingBpm < MIN_BPM || outgoingBpm > MAX_BPM) reasons += "outgoing-tempo"
    if (incomingBpm < MIN_BPM || incomingBpm > MAX_BPM) reasons += "incoming-tempo"
    if (reasons.isNotEmpty()) {
        return TransitionPolicyVerdict(TransitionTier.PLAIN_CROSSFADE, reasons, floorConfidence)
    }

    if (outgoingConfidence < MIN_DJ_CONFIDENCE && incomingConfidence < MIN_DJ_CONFIDENCE) {
        return TransitionPolicyVerdict(
            TransitionTier.PLAIN_CROSSFADE,
            listOf("beat-confidence"),
            floorConfidence,
        )
    }

    val stretchRatio = outgoingBpm / alignTempoOctave(outgoingBpm, incomingBpm)
    if (abs(stretchRatio - 1) > MAX_STRETCH_DEVIATION) reasons += "tempo-distance"
    if (outgoingConfidence < MIN_BEATMATCH_CONFIDENCE || incomingConfidence < MIN_BEATMATCH_CONFIDENCE) {
        reasons += "beat-confidence"
    }

    return TransitionPolicyVerdict(
        tier = if (reasons.isEmpty()) TransitionTier.BEATMATCHED else TransitionTier.DJ_ASSISTED,
        reasons = reasons,
        beatConfidence = floorConfidence,
    )
}
