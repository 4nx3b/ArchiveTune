/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object CrossfadePolicy {

    fun outgoingGain(progress: Float): Float {
        val clamped = progress.coerceIn(0f, 1f)
        return cos(clamped.toDouble() * (PI / 2.0)).toFloat()
    }

    fun incomingGain(progress: Float): Float {
        val clamped = progress.coerceIn(0f, 1f)
        return sin(clamped.toDouble() * (PI / 2.0)).toFloat()
    }

    fun outgoingVolume(
        progress: Float,
        baseVolume: Float,
        maxGain: Float,
    ): Float = (baseVolume * outgoingGain(progress)).coerceIn(0f, maxGain)

    fun incomingVolume(
        progress: Float,
        baseVolume: Float,
        maxGain: Float,
    ): Float = (baseVolume * incomingGain(progress)).coerceIn(0f, maxGain)

    fun resolveTargetIndex(
        repeatOne: Boolean,
        currentIndex: Int,
        nextIndex: Int,
        itemCount: Int,
        unsetIndex: Int,
    ): Int? {
        if (itemCount <= 0 || currentIndex !in 0 until itemCount) return null
        val target = if (repeatOne) currentIndex else nextIndex
        if (target == unsetIndex || target !in 0 until itemCount) return null
        if (!repeatOne && target == currentIndex) return null
        return target
    }

    fun effectiveDurationMs(
        requestedMs: Long,
        trackDurationMs: Long,
        endGuardMs: Long,
        minDurationMs: Long,
        unsetTime: Long,
    ): Long? {
        if (trackDurationMs == unsetTime || trackDurationMs <= 0L) return null
        val maxDuration = trackDurationMs - endGuardMs
        if (maxDuration < minDurationMs) return null
        return requestedMs.coerceIn(minDurationMs, maxDuration)
    }

    data class ReadinessSnapshot(
        val isReady: Boolean,
        val isIdle: Boolean,
        val isEnded: Boolean,
        val hasError: Boolean,
        val bufferedEnough: Boolean,
    )

    fun isReadyForFadeStart(snapshot: ReadinessSnapshot): Boolean =
        !snapshot.hasError && !snapshot.isEnded && snapshot.isReady && snapshot.bufferedEnough

    fun mustAbortReadiness(snapshot: ReadinessSnapshot): Boolean = snapshot.hasError || snapshot.isEnded

    data class AudioAdvancementSnapshot(
        val isReady: Boolean,
        val isPlaying: Boolean,
        val hasError: Boolean,
        val positionMs: Long,
        val previousPositionMs: Long,
    )

    fun hasAudioAdvanced(snapshot: AudioAdvancementSnapshot): Boolean =
        !snapshot.hasError &&
            snapshot.isReady &&
            snapshot.isPlaying &&
            snapshot.positionMs > snapshot.previousPositionMs

    data class PromotionSnapshot(
        val generationMatches: Boolean,
        val targetIndex: Int,
        val hasError: Boolean,
        val isIdle: Boolean,
        val isEnded: Boolean,
        val unsetIndex: Int,
    )

    fun mayPromote(snapshot: PromotionSnapshot): Boolean =
        snapshot.generationMatches &&
            snapshot.targetIndex != snapshot.unsetIndex &&
            !snapshot.hasError &&
            !snapshot.isIdle &&
            !snapshot.isEnded
}
