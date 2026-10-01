@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback.dsp

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import kotlin.math.abs

class DspTailAudioProcessorChain(
    private val silenceSkippingAudioProcessor: SilenceSkippingAudioProcessor,
    private val sonicAudioProcessor: SonicAudioProcessor,
    preProcessors: Array<AudioProcessor>,
    private val tailProcessor: AudioProcessor,

    private val engineTransportActive: () -> Boolean = { false },
    private val engineVariRate: tf.monochrome.android.audio.resample.VariRateAudioProcessor? = null,
    private val engineStretch: tf.monochrome.android.audio.stretch.StretchAudioProcessor? = null,
) : AudioProcessorChain {
    private val chainProcessors: Array<AudioProcessor> =
        preProcessors +
            arrayOf(
                silenceSkippingAudioProcessor,
                sonicAudioProcessor,
                tailProcessor,
            )

    override fun getAudioProcessors(): Array<AudioProcessor> = chainProcessors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        val speed = playbackParameters.speed
        val pitch = playbackParameters.pitch
        if (engineTransportActive() && engineVariRate != null && engineStretch != null) {

            val ridesTempo = abs(pitch - speed) < TOLERANCE
            if (ridesTempo) {
                engineVariRate.setRatio(speed)
                engineStretch.setSemitones(0f)
            } else {
                engineVariRate.setRatio(speed / pitch.coerceAtLeast(0.01f))
                engineStretch.setSemitones(12f * log2(pitch))
            }
            sonicAudioProcessor.setSpeed(1f)
            sonicAudioProcessor.setPitch(1f)
        } else {
            engineVariRate?.setRatio(1f)
            engineStretch?.setSemitones(0f)
            sonicAudioProcessor.setSpeed(speed)
            sonicAudioProcessor.setPitch(pitch)
        }
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        silenceSkippingAudioProcessor.setEnabled(skipSilenceEnabled)
        return skipSilenceEnabled
    }

    override fun getMediaDuration(playoutDuration: Long): Long {
        if (engineTransportActive() && engineVariRate != null) {
            val ratio = engineVariRate.getRatio()
            return if (abs(ratio - 1f) >= TOLERANCE) {
                (playoutDuration * ratio.toDouble()).toLong()
            } else {
                playoutDuration
            }
        }
        return if (sonicAudioProcessor.isActive()) {
            sonicAudioProcessor.getMediaDuration(playoutDuration)
        } else {
            playoutDuration
        }
    }

    override fun getSkippedOutputFrameCount(): Long = silenceSkippingAudioProcessor.getSkippedFrames()

    private companion object {
        const val TOLERANCE = 1e-4f
    }
}

private fun log2(value: Float): Float = kotlin.math.ln(value.coerceAtLeast(1e-6f)) / kotlin.math.ln(2f)
