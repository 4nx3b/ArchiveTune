@file:OptIn(androidx.media3.common.util.UnstableApi::class)

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.dsp

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor

/**
 * An [AudioProcessorChain] whose silence trimming and speed/pitch processors
 * run BEFORE the encoding-flipping DSP tail, instead of after it.
 *
 * DefaultAudioSink.DefaultAudioProcessorChain — whichever overload is used —
 * always appends its own SilenceSkippingAudioProcessor and SonicAudioProcessor
 * AFTER every processor handed to it. With the 32-bit float DSP passed as a
 * plain vararg that ordering is fatal: when the DSP is engaged with
 * USB-exclusive output it emits ENCODING_PCM_FLOAT, and the appended
 * SilenceSkippingAudioProcessor rejects float input outright with
 * UnhandledAudioFormatException (PlaybackException 5001,
 * ERROR_CODE_AUDIO_TRACK_INIT_FAILED) even while inactive — BaseAudioProcessor
 * always invokes onConfigure.
 *
 * This chain keeps the canonical media3 ordering — user processors first,
 * then silence trimming, then speed/pitch — and places the DSP after them as
 * the true tail, the only position from which an encoding flip is safe:
 * nothing downstream has to accept float, and the sink's OutputConfig mirrors
 * exactly what the tail produced for the current track.
 *
 * skipSilenceEnabled and playbackParameters (speed/pitch) are wired to the
 * same instances the chain contains, mirroring DefaultAudioProcessorChain.
 */
class DspTailAudioProcessorChain(
    private val silenceSkippingAudioProcessor: SilenceSkippingAudioProcessor,
    private val sonicAudioProcessor: SonicAudioProcessor,
    preProcessors: Array<AudioProcessor>,
    private val tailProcessor: AudioProcessor,
) : AudioProcessorChain {

    // Named chainProcessors (not audioProcessors): a private Kotlin property
    // named audioProcessors would generate a getAudioProcessors() JVM signature
    // that clashes with the interface override below.
    private val chainProcessors: Array<AudioProcessor> =
        preProcessors +
            arrayOf(
                silenceSkippingAudioProcessor,
                sonicAudioProcessor,
                tailProcessor,
            )

    override fun getAudioProcessors(): Array<AudioProcessor> = chainProcessors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        sonicAudioProcessor.setSpeed(playbackParameters.speed)
        sonicAudioProcessor.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        silenceSkippingAudioProcessor.setEnabled(skipSilenceEnabled)
        return skipSilenceEnabled
    }

    override fun getMediaDuration(playoutDuration: Long): Long =
        if (sonicAudioProcessor.isActive()) {
            sonicAudioProcessor.getMediaDuration(playoutDuration)
        } else {
            playoutDuration
        }

    override fun getSkippedOutputFrameCount(): Long = silenceSkippingAudioProcessor.getSkippedFrames()
}
