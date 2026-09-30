/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.dsp

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import java.nio.ByteBuffer

/**
 * The FIRST processor of the audio chain. It never touches a single byte —
 * its job is to evaluate the CURRENT track against the active output route
 * the moment media3 configures the chain, and to latch the Bit-Perfect bypass
 * into [BitPerfectRuntime] so every processor configured AFTER it in the same
 * pass (all of them) sees the verdict immediately.
 *
 * While the bypass is engaged it also pins Sonic to unity and disables
 * silence skipping — those two media3 processors live in the fixed chain
 * array and have no app-level onConfigure to guard.
 */
@androidx.media3.common.util.UnstableApi
class BitPerfectGateProcessor(
    private val contextProvider: () -> Context?,
    private val engineOrDspEngaged: () -> Boolean,
    private val usbExclusiveActive: () -> Boolean,
    private val effectiveVolume: () -> Float,
) : AudioProcessor {

    @Volatile private var sonicAudioProcessor: SonicAudioProcessor? = null
    @Volatile private var silenceSkippingAudioProcessor: SilenceSkippingAudioProcessor? = null

    /** The player factory hands in the media3 transport processors it created. */
    fun attachTransparentTargets(
        sonicAudioProcessor: SonicAudioProcessor,
        silenceSkippingAudioProcessor: SilenceSkippingAudioProcessor,
    ) {
        this.sonicAudioProcessor = sonicAudioProcessor
        this.silenceSkippingAudioProcessor = silenceSkippingAudioProcessor
    }

    private var buffer: ByteBuffer = EMPTY_BUFFER

    override fun getName(): String = "BitPerfectGate"

    /** Never active: an inactive processor is routed around entirely. */
    override fun isActive(): Boolean = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val context = contextProvider()
        if (context == null) {
            BitPerfectRuntime.clearTrack()
        } else {
            val bypass = BitPerfectRuntime.evaluateTrack(
                context = context,
                inputEncoding = inputAudioFormat.encoding,
                inputSampleRate = inputAudioFormat.sampleRate,
                inputChannels = inputAudioFormat.channelCount,
                engineOrDspEngaged = engineOrDspEngaged(),
                usbExclusive = usbExclusiveActive(),
                effectiveVolume = effectiveVolume(),
            )
            if (bypass) {
                // Silence skipping and Sonic WOULD modify samples (gaps and
                // time-stretch) — pin them to their transparent settings.
                silenceSkippingAudioProcessor?.setEnabled(false)
                sonicAudioProcessor?.setSpeed(1f)
                sonicAudioProcessor?.setPitch(1f)
                Timber.i(
                    "Bit-Perfect ENGAGED: %dbit/%dHz/%dch passes the chain untouched",
                    BitPerfectRuntime.bitDepthOf(inputAudioFormat.encoding),
                    inputAudioFormat.sampleRate,
                    inputAudioFormat.channelCount,
                )
            }
        }
        return AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Inactive: never called.
    }

    override fun queueEndOfStream() {
        // Inactive: never called.
    }

    override fun getOutput(): ByteBuffer = buffer

    override fun flush() {
        buffer = EMPTY_BUFFER
    }

    override fun reset() {
        buffer = EMPTY_BUFFER
    }

    private companion object {
        val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0)
    }
}

private object Timber {
    fun i(message: String, vararg args: Any?) {
        android.util.Log.i(
            "BitPerfectRuntime",
            if (args.isEmpty()) message else java.util.Formatter().format(message, *args).toString(),
        )
    }
}
