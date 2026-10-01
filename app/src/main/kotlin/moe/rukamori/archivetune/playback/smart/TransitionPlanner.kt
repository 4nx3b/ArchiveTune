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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class CrossfadeMode { STANDARD, SMART }

data class TransitionTrackInfo(
    val id: String,
    val durationMs: Long,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: String = "",
)

private const val AUTO_TRANSITION_MAX_BEATS = 16.0
private const val AUTO_MIN_SECONDS = 4.0
private const val AUTO_FAST_TRACK_MIN_SECONDS = 6.0
private const val AUTO_TRANSITION_MAX_SECONDS = 12.0
private const val AUTO_FALLBACK_SECONDS = 8.0

private const val MIN_SMART_DURATION_SECONDS = 45.0

private const val MIN_INCOMING_CLEARANCE_SECONDS = 5.0

private val KEY_INDEX = mapOf(
    "C" to 0, "C♯" to 1, "D♭" to 1, "D" to 2, "D♯" to 3, "E♭" to 3,
    "E" to 4, "F" to 5, "F♯" to 6, "G♭" to 6, "G" to 7, "G♯" to 8,
    "A♭" to 8, "A" to 9, "A♯" to 10, "B♭" to 10, "B" to 11,
)

private val BLOCKED_TEXT = Regex(
    """\b(podcast|episode|audiobook|live|concert|performance)\b""",
    RegexOption.IGNORE_CASE,
)

enum class TransitionStyle {
    EQUAL_POWER,

    GAPLESS,

    DJ_BLEND,

    DJ_FILTER,
}

data class TransitionPlan(
    val shouldStart: Boolean = false,
    val markerVisible: Boolean = false,
    val blocked: Boolean = false,
    val reason: String = "",
    val transitionStart: Double = 0.0,
    val transitionEnd: Double = 0.0,
    val fadeSeconds: Double = 0.0,
    val transitionStyle: TransitionStyle = TransitionStyle.EQUAL_POWER,

    val incomingCueTime: Double = 0.0,

    val incomingHandoffTime: Double = 0.0,
    val incomingPlaybackRate: Double = 1.0,
    val handoffStartSeconds: Double = 0.0,
    val handoffDuration: Double = 0.0,
    val pickupSeconds: Double = 0.0,
    val transitionBeats: Int = 0,
    val bassSwap: Boolean = false,
    val handoffFraction: Double = HANDOFF_FRACTION,
    val bedPosition: Double = BED_POSITION,
    val bassSwapFraction: Double = 0.7,
    val filterSweep: Double = 0.0,

    val vocalOverlap: Double = 0.0,

    val outgoingBpm: Double = 0.0,
    val incomingBpm: Double = 0.0,

    val policyReasons: List<String> = emptyList(),
) {
    val fadeMs: Long get() = (fadeSeconds * 1000).roundToLong()
}

private fun blocked(reason: String, transitionStart: Double = 0.0, transitionEnd: Double = 0.0) =
    TransitionPlan(
        blocked = true,
        reason = reason,
        transitionStart = transitionStart,
        transitionEnd = transitionEnd,
    )

private fun trackDurationSeconds(track: TransitionTrackInfo?): Double =
    if (track == null || track.durationMs <= 0) 0.0 else track.durationMs / 1000.0

private fun itemText(track: TransitionTrackInfo?): String =
    if (track == null) "" else listOf(track.title, track.artist, track.album)
        .filter { it.isNotBlank() }
        .joinToString(" ")

private fun sameAlbum(left: TransitionTrackInfo?, right: TransitionTrackInfo?): Boolean {
    if (left == null || right == null) return false
    if (left.albumId.isNotBlank() && left.albumId == right.albumId) return true
    return left.album.isNotBlank() && left.album == right.album && left.artist == right.artist
}

private fun normalizedTempoRatio(currentBpm: Double, nextBpm: Double): Double {
    if (currentBpm <= 0 || nextBpm <= 0) return 1.0
    var ratio = nextBpm / currentBpm
    while (ratio > 1.5) ratio /= 2
    while (ratio < 0.67) ratio *= 2
    return ratio
}

private fun splitKey(key: String): Pair<Int?, String?> {
    val parts = key.trim().split(' ')
    return KEY_INDEX[parts.firstOrNull()] to parts.getOrNull(1)
}

private fun keyDistance(left: String, right: String): Int? {
    val (leftIndex, leftMode) = splitKey(left)
    val (rightIndex, rightMode) = splitKey(right)
    if (leftIndex == null || rightIndex == null) return null
    val pitchDistance = min((leftIndex - rightIndex + 12) % 12, (rightIndex - leftIndex + 12) % 12)
    return pitchDistance + if (leftMode != null && rightMode != null && leftMode != rightMode) 1 else 0
}

private fun harmonicallyCompatible(left: String, right: String): Boolean {
    val (leftIndex, leftMode) = splitKey(left)
    val (rightIndex, rightMode) = splitKey(right)
    if (leftIndex == null || rightIndex == null) return false
    val distance = min((leftIndex - rightIndex + 12) % 12, (rightIndex - leftIndex + 12) % 12)
    if (leftMode != null && rightMode != null && leftMode != rightMode) return distance <= 1

    return distance <= 2 || distance == 5
}

private fun trustedKey(analysis: TrackAnalysis): String =
    if (analysis.key.isBlank() || analysis.keyConfidence < 0.25) "" else analysis.key

private fun nearestTimedValue(
    values: List<Double>,
    target: Double,
    tolerance: Double = Double.POSITIVE_INFINITY,
    minimum: Double = 0.0,
): Double? = values
    .filter { it.isFinite() && it >= minimum && abs(it - target) <= tolerance }
    .minByOrNull { abs(it - target) }

private fun timedValueNearOrBefore(
    values: List<Double>,
    target: Double,
    tolerance: Double = Double.POSITIVE_INFINITY,
    minimum: Double = 0.0,
): Double? = values
    .filter { it.isFinite() && it >= minimum && it <= target && target - it <= tolerance }
    .maxOrNull()

private fun alignedTransitionStart(
    analysis: TrackAnalysis,
    target: Double,
    end: Double,
    preferEarlier: Boolean,
    minimum: Double,
): Double {
    val interval = analysis.beatInterval.orZero().takeIf { it > 0 }
        ?: if (analysis.bpm.orZero() > 0) 60 / analysis.bpm else 0.0
    val phraseTolerance = max(1.0, interval * 4)
    val downbeatTolerance = max(0.75, interval * 2)
    val phrase = if (preferEarlier) {
        timedValueNearOrBefore(analysis.phraseBoundaries, target, phraseTolerance, minimum)
    } else {
        nearestTimedValue(analysis.phraseBoundaries, target, phraseTolerance, minimum)
    }
    val downbeat = if (preferEarlier) {
        timedValueNearOrBefore(analysis.downbeats, target, downbeatTolerance, minimum)
    } else {
        nearestTimedValue(analysis.downbeats, target, downbeatTolerance, minimum)
    }
    return clamp(phrase ?: downbeat ?: target, minimum, end)
}

internal fun incomingCuePoint(analysis: TrackAnalysis): Double {
    rankMixInCandidates(analysis).firstOrNull()?.let { return it.time }

    val interval = analysis.beatInterval.orZero().takeIf { it > 0 }
        ?: if (analysis.bpm.orZero() > 0) 60 / analysis.bpm else 0.0
    val downbeats = analysis.downbeats

    val analyzedMixIn = analysis.mixInTime
    if (analyzedMixIn.isFinite() && analyzedMixIn > 0) {
        return nearestTimedValue(downbeats, analyzedMixIn, max(0.5, interval * 2)) ?: analyzedMixIn
    }

    val pickup = max(
        0.0,
        analysis.introEndTime.orZero().takeIf { it != 0.0 }
            ?: (analysis.audibleStartTime ?: analysis.pickupTime).orZero().takeIf { it != 0.0 }
            ?: analysis.firstBeat.orZero(),
    )
    val duration = analysis.duration.orZero().takeIf { it != 0.0 } ?: 300.0
    if (pickup > 0 && pickup < duration - 10) {
        downbeats.firstOrNull { it >= pickup }?.let { return it }
    }
    val phrases = analysis.phraseBoundaries
    if (phrases.size > 1 && phrases[1] > 4) return phrases[1]
    if (downbeats.size >= 8) return downbeats[min(8, downbeats.size - 1)].orZero()
    return pickup
}

private fun incomingStartPoint(analysis: TrackAnalysis): Double =
    listOfNotNull(analysis.audibleStartTime, analysis.pickupTime, analysis.firstBeat)
        .firstOrNull { it.isFinite() && it >= 0 } ?: 0.0

private const val MIN_FADE_BEATS = 4
private const val MAX_FADE_BEATS = 16

private const val MAX_OVERLAP_SECONDS = 16.0

internal const val ARRANGEMENT_OVERLAP_BEATS = 8

const val HANDOFF_FRACTION = 0.5
const val BED_POSITION = 0.5

private const val DEFAULT_BASS_SWAP_FRACTION = 0.7

private const val MAX_BASS_SWAP_FRACTION = 0.85

private const val MIN_BASS_STRUCTURE_SCORE = 0.25

private const val BASS_SWAP_MAX_SECONDS = 6.0

const val FILTER_SWEEP = 1.0

private const val MIN_CLEARANCE_SECONDS = 5.0

private fun averageLowEnergy(curve: List<EnergySample>, from: Double, until: Double): Double? {
    if (until <= from) return null
    var index = curve.binarySearchBy(from) { it.time }.let { if (it >= 0) it else -it - 1 }
    var sum = 0.0
    var count = 0
    while (index < curve.size && curve[index].time < until) {
        val point = curve[index++]
        if (point.time.isFinite() && point.energy.isFinite() && point.energy >= 0) {
            sum += point.energy
            count++
        }
    }
    return if (count > 0) sum / count else null
}

private fun lowEnergyReference(curve: List<EnergySample>): Double? {
    val energies = curve.map { it.energy }.filter { it.isFinite() && it >= 0 }.sorted()
    if (energies.isEmpty()) return null
    val upperDecile = energies[(energies.lastIndex * 0.9).toInt()]
    val reference = max(upperDecile, (energies.lastOrNull() ?: 0.0) * 0.25)
    return reference.takeIf { it > 1e-9 }
}

private fun lowEnergyResolution(curve: List<EnergySample>): Double {
    val gaps = curve.zipWithNext { left, right -> right.time - left.time }
        .filter { it.isFinite() && it > 0 }
        .sorted()
    return gaps.getOrNull(gaps.size / 2) ?: 0.0
}

private fun lowEnergyChange(
    curve: List<EnergySample>,
    reference: Double?,
    at: Double,
    windowSeconds: Double,
): Double? {
    if (curve.isEmpty() || reference == null || windowSeconds <= 0) return null
    val before = averageLowEnergy(curve, at - windowSeconds, at) ?: return null
    val after = averageLowEnergy(curve, at, at + windowSeconds) ?: return null
    return (after / reference).coerceIn(0.0, 1.5) -
        (before / reference).coerceIn(0.0, 1.5)
}

private fun bassSwapFractionFor(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    transitionStart: Double,
    incomingCueTime: Double,
    outgoingBeatSeconds: Double,
    incomingBeatSeconds: Double,
    overlapSeconds: Double,
    overlapBeats: Int,
): Double {
    if (overlapSeconds <= 0) return DEFAULT_BASS_SWAP_FRACTION

    val latestFraction = min(MAX_BASS_SWAP_FRACTION, BASS_SWAP_MAX_SECONDS / overlapSeconds)
        .coerceIn(0.0, 1.0)
    val prior = min(DEFAULT_BASS_SWAP_FRACTION, latestFraction)
    if (overlapBeats < 2) return prior

    val earliestFraction = min(HANDOFF_FRACTION, latestFraction)
    val earliestBeat = ceil(earliestFraction * overlapBeats - 1e-9).toInt()
        .coerceIn(1, overlapBeats - 1)
    val latestBeat = floor(latestFraction * overlapBeats + 1e-9).toInt()
        .coerceIn(earliestBeat, overlapBeats - 1)
    val candidates = (earliestBeat..latestBeat).toList()
    val fallbackBeat = candidates.minWithOrNull(
        compareBy<Int> { abs(it.toDouble() / overlapBeats - prior) }
            .thenBy { if (it % 4 == 0) 0 else 1 },
    ) ?: return prior

    data class BassCandidate(val beat: Int, val score: Double)

    val outgoingReference = lowEnergyReference(analysis.lowEnergyCurve)
    val incomingReference = lowEnergyReference(nextAnalysis.lowEnergyCurve)
    val outgoingWindow = max(
        outgoingBeatSeconds,
        lowEnergyResolution(analysis.lowEnergyCurve) * 1.1,
    )
    val incomingWindow = max(
        incomingBeatSeconds,
        lowEnergyResolution(nextAnalysis.lowEnergyCurve) * 1.1,
    )
    val strongest = candidates.mapNotNull { beat ->
        val outgoingAt = transitionStart + beat * outgoingBeatSeconds
        val incomingAt = incomingCueTime + beat * incomingBeatSeconds
        val incomingChange = lowEnergyChange(
            nextAnalysis.lowEnergyCurve,
            incomingReference,
            incomingAt,
            incomingWindow,
        )
        val outgoingChange = lowEnergyChange(
            analysis.lowEnergyCurve,
            outgoingReference,
            outgoingAt,
            outgoingWindow,
        )
        if (incomingChange == null && outgoingChange == null) return@mapNotNull null
        BassCandidate(beat, (incomingChange ?: 0.0) - (outgoingChange ?: 0.0))
    }.maxWithOrNull(
        compareBy<BassCandidate> { it.score }
            .thenBy { if (it.beat % 4 == 0) 1 else 0 }
            .thenBy { -abs(it.beat.toDouble() / overlapBeats - prior) },
    )

    val chosenBeat = strongest?.takeIf { it.score >= MIN_BASS_STRUCTURE_SCORE }?.beat
        ?: fallbackBeat
    return chosenBeat.toDouble() / overlapBeats
}

private fun plannedVocalOverlap(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    transitionStart: Double,
    transitionEnd: Double,
    incomingCueTime: Double,
    incomingPlaybackRate: Double,
): Double {
    val outgoingSpan = transitionEnd - transitionStart
    if (outgoingSpan <= 0.0 || !outgoingSpan.isFinite()) return 0.0
    val rate = incomingPlaybackRate.takeIf { it.isFinite() && it > 0 } ?: 1.0
    return simultaneousVocalFraction(
        outgoing = analysis,
        incoming = nextAnalysis,
        outStart = transitionStart,
        outEnd = transitionEnd,
        inStart = incomingCueTime,
        rate = rate,
    ) ?: 0.0
}

private fun nearestAtOrBefore(values: List<Double>, target: Double): Double? =
    values.filter { it.isFinite() && it >= 0 && it <= target }.maxOrNull()

sealed interface WsolaPlanResult {
    data class Refused(val reason: String) : WsolaPlanResult

    data class Planned(
        val tier: TransitionTier,
        val beatConfidence: Double,
        val mixOutType: String,
        val vocalClash: Boolean,
        val transitionStart: Double,
        val transitionEnd: Double,
        val overlapSeconds: Double,
        val beats: Int,
        val fadeBeats: Int,
        val handoffFraction: Double,
        val bedPosition: Double,
        val bassSwapFraction: Double,
        val filterSweep: Double,
        val outgoingBpm: Double,
        val incomingBpm: Double,
        val stretchRatio: Double,
        val incomingCueTime: Double,
        val incomingDropTime: Double,
        val incomingHandoffTime: Double,
        val incomingResumeTime: Double,
    ) : WsolaPlanResult
}

fun incomingMixInPoint(analysis: TrackAnalysis): Double? {
    val beatSeconds = analysis.beatInterval.orZero().takeIf { it > 0 }
        ?: if (analysis.bpm.orZero() > 0) 60 / analysis.bpm else 0.0
    val tolerance = max(0.5, beatSeconds * 2)
    val target = listOfNotNull(rankMixInCandidates(analysis).firstOrNull()?.time, analysis.mixInTime)
        .firstOrNull { it.isFinite() && it > 0 }
        ?: return null
    return nearestValue(analysis.downbeats, target, tolerance) ?: target
}

fun incomingAudibleStart(analysis: TrackAnalysis): Double = audibleStartOf(analysis)

fun planWsolaTransition(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    duration: Double = 0.0,
    nextDuration: Double = 0.0,
): WsolaPlanResult {
    val policy = assessTransitionTier(analysis, nextAnalysis)
    if (policy.tier != TransitionTier.BEATMATCHED) {
        return WsolaPlanResult.Refused(policy.reasons.firstOrNull() ?: "policy")
    }

    val outgoingBpm = analysis.bpm.orZero()
    val incomingBpm = alignTempoOctave(outgoingBpm, nextAnalysis.bpm.orZero())
    val stretchRatio = outgoingBpm / incomingBpm

    val outgoingLength = max(duration.orZero(), analysis.duration.orZero())
    val incomingLength = max(nextDuration.orZero(), nextAnalysis.duration.orZero())
    if (outgoingLength <= 0 || incomingLength <= 0) return WsolaPlanResult.Refused("missing-duration")

    val incomingBeatSeconds = 60 / incomingBpm
    val outgoingBeatSeconds = 60 / outgoingBpm

    val incomingDropTime = incomingMixInPoint(nextAnalysis)
    if (incomingDropTime == null || !incomingDropTime.isFinite() || incomingDropTime < 0) {
        return WsolaPlanResult.Refused("incoming-mix-in")
    }

    val contentEnd = analysis.contentEndTime.orZero().takeIf { it != 0.0 } ?: outgoingLength
    val mixOutAnchor = resolveMixOutAnchor(analysis, contentEnd = contentEnd, duration = outgoingLength)
    val unshiftedOverlapEnd = min(outgoingLength, mixOutAnchor.time)
    val outgoingArrangementOverlap =
        if (mixOutAnchor.type == "content_end") {
            min(ARRANGEMENT_OVERLAP_BEATS * outgoingBeatSeconds, MAX_DISCARDED_MUSIC_SECONDS)
        } else {
            0.0
        }
    val overlapEndTarget = max(MIN_CLEARANCE_SECONDS, unshiftedOverlapEnd - outgoingArrangementOverlap)

    val audibleStart = incomingAudibleStart(nextAnalysis)
    val availableFadeBeats = max(0.0, incomingDropTime - audibleStart) / incomingBeatSeconds
    val cappedByOverlap = floor(floor(MAX_OVERLAP_SECONDS / incomingBeatSeconds) / 4).toInt() * 4
    if (cappedByOverlap < MIN_FADE_BEATS) return WsolaPlanResult.Refused("overlap-too-long")
    var fadeBeats = minOf(
        MAX_FADE_BEATS,
        cappedByOverlap,
        floor(availableFadeBeats / 4).toInt() * 4,
    )
    if (fadeBeats < MIN_FADE_BEATS) fadeBeats = MIN_FADE_BEATS

    fun clashOver(beats: Int): Boolean {
        val outStart = overlapEndTarget - beats * outgoingBeatSeconds
        val inStart = max(audibleStart, incomingDropTime - beats * incomingBeatSeconds)
        val outVocal = vocalActivityBetween(analysis, outStart, overlapEndTarget)
        val inVocal = vocalActivityBetween(nextAnalysis, inStart, incomingDropTime)

        val simultaneous = simultaneousVocalFraction(
            outgoing = analysis,
            incoming = nextAnalysis,
            outStart = outStart,
            outEnd = overlapEndTarget,
            inStart = inStart,
            rate = if (outgoingBeatSeconds > 0) incomingBeatSeconds / outgoingBeatSeconds else 1.0,
        )
        if (simultaneous != null && simultaneous > VOCAL_CLASH_TOLERANCE) return true

        if (isVocalClash(outVocal, inVocal)) return true

        if (beats > 8 && outVocal != null && outVocal >= VOCAL_ACTIVE_THRESHOLD) {
            val deepVocal = vocalActivityBetween(analysis, outStart, overlapEndTarget - 8 * outgoingBeatSeconds)
            if (deepVocal != null && deepVocal >= VOCAL_ACTIVE_THRESHOLD) {
                return true
            }
        }
        return false
    }
    var fadeVocalClash = clashOver(fadeBeats)
    while (fadeVocalClash && fadeBeats > MIN_FADE_BEATS) {
        fadeBeats -= 4
        fadeVocalClash = clashOver(fadeBeats)
    }

    val coverableBeats = floor(max(0.0, incomingDropTime - audibleStart) / incomingBeatSeconds).toInt()
    val overlapBeats = min(fadeBeats, coverableBeats)
    if (overlapBeats < 1) return WsolaPlanResult.Refused("incoming-no-intro")

    val outgoingOverlapSeconds = overlapBeats * outgoingBeatSeconds
    val overlapSeconds = overlapBeats * incomingBeatSeconds

    val requestedIncomingHandoff =
        incomingDropTime + ARRANGEMENT_OVERLAP_BEATS * incomingBeatSeconds
    val maxIncomingHandoff = incomingLength - MIN_CLEARANCE_SECONDS
    if (maxIncomingHandoff < incomingDropTime) return WsolaPlanResult.Refused("incoming-too-short")
    val incomingHandoffTime = min(requestedIncomingHandoff, maxIncomingHandoff)
    val incomingCueTime = incomingHandoffTime - overlapSeconds
    if (incomingCueTime < audibleStart - 0.05) return WsolaPlanResult.Refused("incoming-no-runway")

    val startTarget = overlapEndTarget - outgoingOverlapSeconds
    val transitionStart = nearestAtOrBefore(analysis.downbeats, startTarget) ?: startTarget
    if (transitionStart < MIN_CLEARANCE_SECONDS) return WsolaPlanResult.Refused("outgoing-too-short")
    val transitionEnd = transitionStart + outgoingOverlapSeconds
    if (transitionEnd > outgoingLength + 0.05) return WsolaPlanResult.Refused("outgoing-overlap-overruns")

    val incomingResumeTime = incomingCueTime + overlapSeconds
    if (incomingResumeTime + MIN_CLEARANCE_SECONDS > incomingLength) {
        return WsolaPlanResult.Refused("incoming-too-short")
    }

    return WsolaPlanResult.Planned(
        tier = policy.tier,
        beatConfidence = policy.beatConfidence,
        mixOutType = mixOutAnchor.type,
        vocalClash = fadeVocalClash,
        transitionStart = transitionStart,
        transitionEnd = transitionEnd,
        overlapSeconds = overlapSeconds,
        beats = overlapBeats,
        fadeBeats = overlapBeats,
        handoffFraction = HANDOFF_FRACTION,
        bedPosition = BED_POSITION,
        bassSwapFraction = bassSwapFractionFor(
            analysis = analysis,
            nextAnalysis = nextAnalysis,
            transitionStart = transitionStart,
            incomingCueTime = incomingCueTime,
            outgoingBeatSeconds = outgoingBeatSeconds,
            incomingBeatSeconds = incomingBeatSeconds,
            overlapSeconds = overlapSeconds,
            overlapBeats = overlapBeats,
        ),
        filterSweep = FILTER_SWEEP,
        outgoingBpm = outgoingBpm,
        incomingBpm = incomingBpm,
        stretchRatio = stretchRatio,
        incomingCueTime = incomingCueTime,
        incomingDropTime = incomingDropTime,
        incomingHandoffTime = incomingHandoffTime,
        incomingResumeTime = incomingResumeTime,
    )
}

private fun phraseSwitch(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    length: Double,
    nextLength: Double,
): TransitionPlan? {
    if (!harmonicallyCompatible(trustedKey(analysis), trustedKey(nextAnalysis))) return null

    val planned = planWsolaTransition(
        analysis = analysis,
        nextAnalysis = nextAnalysis,
        duration = length,
        nextDuration = nextLength,
    ) as? WsolaPlanResult.Planned ?: return null

    val overlap = planned.transitionEnd - planned.transitionStart
    return TransitionPlan(
        markerVisible = true,
        transitionStart = planned.transitionStart,
        transitionEnd = planned.transitionEnd,
        fadeSeconds = overlap,
        handoffStartSeconds = 0.0,
        handoffDuration = overlap,
        incomingCueTime = planned.incomingCueTime,
        incomingHandoffTime = planned.incomingHandoffTime,
        incomingPlaybackRate = (planned.stretchRatio * 10000).roundToInt() / 10000.0,
        pickupSeconds = incomingAudibleStart(nextAnalysis),
        transitionBeats = planned.beats,
        bassSwap = true,
        handoffFraction = planned.handoffFraction,
        bedPosition = planned.bedPosition,
        bassSwapFraction = planned.bassSwapFraction,

        filterSweep = 0.0,

        vocalOverlap = plannedVocalOverlap(
            analysis = analysis,
            nextAnalysis = nextAnalysis,
            transitionStart = planned.transitionStart,
            transitionEnd = planned.transitionEnd,
            incomingCueTime = planned.incomingCueTime,
            incomingPlaybackRate = planned.stretchRatio,
        ),
        outgoingBpm = planned.outgoingBpm,
        incomingBpm = planned.incomingBpm,
        transitionStyle = TransitionStyle.DJ_BLEND,
    )
}

private data class Overlap(
    val overlap: Double,
    val transitionBeats: Int,
    val incomingPlaybackRate: Double,
)

private fun adaptiveOverlap(analysis: TrackAnalysis, nextAnalysis: TrackAnalysis): Overlap {
    val currentBpm = analysis.bpm.orZero()
    val nextBpm = nextAnalysis.bpm.orZero()
    if (currentBpm <= 0 || nextBpm <= 0) {
        return Overlap(AUTO_FALLBACK_SECONDS, 0, 1.0)
    }

    val ratio = normalizedTempoRatio(currentBpm, nextBpm)
    val distance = keyDistance(trustedKey(analysis), trustedKey(nextAnalysis))
    val vocalConflict = analysis.vocalProbability >= 0.62 && nextAnalysis.vocalProbability >= 0.62
    val transitionBeats =
        if (!vocalConflict && (abs(1 - ratio) > 0.07 || (distance != null && distance > 4))) 16 else 8
    val beatSeconds = 60 / currentBpm
    val minimumOverlap = if (currentBpm >= 140) AUTO_FAST_TRACK_MIN_SECONDS else AUTO_MIN_SECONDS

    return Overlap(
        overlap = clamp(transitionBeats * beatSeconds, minimumOverlap, AUTO_TRANSITION_MAX_SECONDS),
        transitionBeats = transitionBeats,
        incomingPlaybackRate = if (ratio in 0.9..1.1) {
            (clamp(1 / ratio, 0.9, 1.1) * 10000).roundToInt() / 10000.0
        } else {
            1.0
        },
    )
}

private fun standardTransition(
    length: Double,
    playbackTime: Double,
    fadeSeconds: Double,
    minFadeSeconds: Double,
    reason: String = "standard",
): TransitionPlan {
    val fade = clamp(fadeSeconds, minFadeSeconds, 12.0)
    val transitionStart = max(0.0, length - fade)
    val started = playbackTime >= transitionStart
    return TransitionPlan(
        shouldStart = started,
        markerVisible = true,
        transitionStart = transitionStart,
        transitionEnd = length,
        fadeSeconds = fade,
        transitionStyle = TransitionStyle.EQUAL_POWER,
        reason = if (started) reason else "before-$reason-window",
    )
}

private fun analysisReadyForTrack(analysis: TrackAnalysis, track: TransitionTrackInfo?): Boolean {
    if (analysis.status.isBlank()) return true
    if (analysis.status != TrackAnalysis.STATUS_READY) return false
    return analysis.trackId.isBlank() || track?.id.isNullOrBlank() || analysis.trackId == track.id
}

fun planTransition(
    analysis: TrackAnalysis = TrackAnalysis(),
    nextAnalysis: TrackAnalysis = TrackAnalysis(),
    currentTrack: TransitionTrackInfo? = null,
    nextTrack: TransitionTrackInfo? = null,
    currentTime: Double = 0.0,
    duration: Double = 0.0,
    fadeSeconds: Double = 6.0,
    minFadeSeconds: Double = 1.0,
    mode: CrossfadeMode = CrossfadeMode.STANDARD,
    albumSequential: Boolean = false,
): TransitionPlan {
    val length = max(duration.orZero(), trackDurationSeconds(currentTrack))
    val playbackTime = max(0.0, currentTime.orZero())
    if (length <= 0) return blocked("no-duration")

    val standardFade = clamp(fadeSeconds, minFadeSeconds, 12.0)
    if (mode != CrossfadeMode.SMART) {
        return standardTransition(length, playbackTime, standardFade, minFadeSeconds)
    }

    if (length < MIN_SMART_DURATION_SECONDS) {
        return blocked("short-duration-guard", transitionStart = length, transitionEnd = length)
    }

    val analyzedContentEnd = analysis.contentEndTime.orZero().takeIf { it != 0.0 } ?: length
    val finalMixAnchor = if (analyzedContentEnd > 0 && analyzedContentEnd <= length) {
        analyzedContentEnd
    } else {
        length
    }
    val mixOutAnchor = resolveMixOutAnchor(analysis, contentEnd = finalMixAnchor, duration = length)
    val hasInteriorMixOut = mixOutAnchor.time < finalMixAnchor - 1

    if (albumSequential && sameAlbum(currentTrack, nextTrack) && !hasInteriorMixOut) {
        val transitionStart = max(0.0, length - 0.45)
        val started = playbackTime >= transitionStart
        return TransitionPlan(
            shouldStart = started,
            markerVisible = true,
            transitionStart = transitionStart,
            transitionEnd = length,
            fadeSeconds = 0.12,
            transitionStyle = TransitionStyle.GAPLESS,
            reason = if (started) "same-album-gapless" else "before-gapless-window",
        )
    }

    if (BLOCKED_TEXT.containsMatchIn("${itemText(currentTrack)} ${itemText(nextTrack)}")) {
        return blocked("blocked-speech-or-live")
    }

    if (!analysisReadyForTrack(analysis, currentTrack) ||
        !analysisReadyForTrack(nextAnalysis, nextTrack)
    ) {
        return standardTransition(
            length,
            playbackTime,
            standardFade,
            minFadeSeconds,
            "smart-analysis-fallback",
        )
    }

    val preferredMixAnchor = min(length, mixOutAnchor.time)
    val mixAnchor =
        if (playbackTime >= preferredMixAnchor - 0.05 && preferredMixAnchor < finalMixAnchor - 1) {
            finalMixAnchor
        } else {
            preferredMixAnchor
        }

    val policy = assessTransitionTier(analysis, nextAnalysis)
    if (policy.tier == TransitionTier.PLAIN_CROSSFADE) {
        val transitionStart = max(0.0, mixAnchor - standardFade)
        val started = playbackTime >= transitionStart
        return TransitionPlan(
            shouldStart = started,
            markerVisible = true,
            transitionStart = transitionStart,
            transitionEnd = mixAnchor,
            fadeSeconds = mixAnchor - transitionStart,
            transitionStyle = TransitionStyle.EQUAL_POWER,
            incomingCueTime = incomingStartPoint(nextAnalysis),
            policyReasons = policy.reasons,
            reason = if (started) "smart-plain-crossfade" else "before-plain-crossfade-window",
        )
    }

    val nextLength = max(nextAnalysis.duration.orZero(), trackDurationSeconds(nextTrack))

    phraseSwitch(analysis, nextAnalysis, length, nextLength)
        ?.takeIf { playbackTime < it.transitionEnd }
        ?.let { plan ->
            val started = playbackTime >= plan.transitionStart
            return plan.copy(
                shouldStart = started,
                policyReasons = policy.reasons,
                reason = if (started) "smart-phrase-switch" else "before-phrase-switch",
            )
        }

    val (overlap, transitionBeats, incomingPlaybackRate) = adaptiveOverlap(analysis, nextAnalysis)
    val currentBpm = analysis.bpm.orZero()
    val nextBpm = nextAnalysis.bpm.orZero()
    val handoffBpm = if (currentBpm > 0) currentBpm else nextBpm
    val sameBeatBlend = currentBpm > 0 && nextBpm > 0 &&
        abs(1 - normalizedTempoRatio(currentBpm, nextBpm)) <= 0.05 &&
        (analysis.beatConfidence.orZero() >= 0.2 || nextAnalysis.beatConfidence.orZero() >= 0.2)
    val outgoingArrangementOverlap =
        if (sameBeatBlend && mixOutAnchor.type == "content_end") {
            min(ARRANGEMENT_OVERLAP_BEATS * 60 / currentBpm, MAX_DISCARDED_MUSIC_SECONDS)
        } else {
            0.0
        }
    val mixEnd = max(0.0, mixAnchor - outgoingArrangementOverlap)
    val maximumOverlap = minOf(
        if (handoffBpm > 0) (AUTO_TRANSITION_MAX_BEATS * 60) / handoffBpm else AUTO_TRANSITION_MAX_SECONDS,
        AUTO_TRANSITION_MAX_SECONDS,
        mixEnd * 0.4,
        if (nextLength > 0) nextLength * 0.4 else AUTO_TRANSITION_MAX_SECONDS,
    )
    val handoffBeats = if (sameBeatBlend) 8 else 4
    val beatSeconds = if (handoffBpm > 0) 60 / handoffBpm else 0.5
    val handoffSeconds = if (handoffBpm > 0) {
        clamp((handoffBeats * 60) / handoffBpm, 2.0, if (sameBeatBlend) 6.0 else 5.0)
    } else {
        4.0
    }
    val analyzedPickup = nextAnalysis.audibleStartTime ?: nextAnalysis.pickupTime
    val pickupSeconds = if (analyzedPickup != null && analyzedPickup.isFinite() && analyzedPickup >= 0) {
        analyzedPickup
    } else {
        0.0
    }
    val incomingDropTime = incomingCuePoint(nextAnalysis)
    val alignedIncomingBpm = alignTempoOctave(currentBpm, nextBpm)
    val requestedIncomingHandoff =
        if (sameBeatBlend && alignedIncomingBpm > 0) {
            incomingDropTime + ARRANGEMENT_OVERLAP_BEATS * 60 / alignedIncomingBpm
        } else {
            incomingDropTime
        }
    val maxIncomingHandoff = nextLength - MIN_INCOMING_CLEARANCE_SECONDS
    val incomingHandoffTime =
        if (maxIncomingHandoff >= incomingDropTime) {
            min(requestedIncomingHandoff, maxIncomingHandoff)
        } else {
            incomingDropTime
        }
    val rawIncomingCueTime = incomingStartPoint(nextAnalysis)
    val analyzedIncomingHandoff = nextAnalysis.mixInTime
    val hasIncomingPreroll = analyzedIncomingHandoff.isFinite() &&
        analyzedIncomingHandoff > rawIncomingCueTime + 0.5
    val incomingCueTime = if (hasIncomingPreroll) rawIncomingCueTime else incomingHandoffTime
    val introPreroll = max(
        0.0,
        (if (hasIncomingPreroll) incomingHandoffTime - incomingCueTime else 0.0) /
            max(0.8, incomingPlaybackRate),
    )

    val finalIncomingCueTime: Double
    val transitionStart: Double

    if (sameBeatBlend && beatSeconds > 0) {
        val introDropTime = incomingHandoffTime / max(0.8, incomingPlaybackRate)
        val totalOverlap = clamp(introDropTime, min(12.0, maximumOverlap), maximumOverlap)
        val targetStart = max(0.0, mixEnd - totalOverlap)
        val earliestTransitionStart = max(0.0, mixEnd - maximumOverlap)
        transitionStart = alignedTransitionStart(
            analysis,
            targetStart,
            mixEnd - 0.05,
            preferEarlier = true,
            minimum = earliestTransitionStart,
        )
        finalIncomingCueTime =
            max(0.0, incomingHandoffTime - (mixEnd - transitionStart) * incomingPlaybackRate)
    } else {
        val desiredOverlap = max(overlap, introPreroll + handoffSeconds * 0.42)
        val actualOverlap = clamp(desiredOverlap, min(handoffSeconds, maximumOverlap), maximumOverlap)
        val targetStart = max(0.0, mixEnd - actualOverlap)
        val earliestTransitionStart = max(0.0, mixEnd - maximumOverlap)
        transitionStart = alignedTransitionStart(
            analysis,
            targetStart,
            mixEnd - 0.05,
            preferEarlier = desiredOverlap > overlap + 0.5,
            minimum = earliestTransitionStart,
        )
        finalIncomingCueTime = if (hasIncomingPreroll) {
            max(0.0, incomingHandoffTime - (mixEnd - transitionStart) * incomingPlaybackRate)
        } else {
            incomingCueTime
        }
    }

    val alignedOverlap = mixEnd - transitionStart
    val hasBassContent = analysis.lowEnergyCurve.isNotEmpty() || nextAnalysis.lowEnergyCurve.isNotEmpty()
    val started = playbackTime >= transitionStart
    return TransitionPlan(
        shouldStart = started,
        markerVisible = true,
        transitionStart = transitionStart,
        transitionEnd = mixEnd,
        fadeSeconds = alignedOverlap,
        handoffStartSeconds = 0.0,
        handoffDuration = alignedOverlap,
        incomingCueTime = finalIncomingCueTime,
        incomingHandoffTime = incomingHandoffTime,
        incomingPlaybackRate = incomingPlaybackRate,
        pickupSeconds = pickupSeconds,
        transitionBeats = transitionBeats,
        bassSwap = sameBeatBlend || hasBassContent,
        transitionStyle = if (sameBeatBlend) TransitionStyle.DJ_BLEND else TransitionStyle.DJ_FILTER,

        filterSweep = if (sameBeatBlend) 0.0 else FILTER_SWEEP,
        vocalOverlap = plannedVocalOverlap(
            analysis = analysis,
            nextAnalysis = nextAnalysis,
            transitionStart = transitionStart,
            transitionEnd = mixEnd,
            incomingCueTime = finalIncomingCueTime,
            incomingPlaybackRate = incomingPlaybackRate,
        ),
        policyReasons = policy.reasons,
        reason = if (started) "smart-duration" else "before-smart-duration",
    )
}
