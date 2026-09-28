@file:OptIn(androidx.media3.common.util.UnstableApi::class)

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 *
 * The engine-routing tail of the app's audio processor chain. One processor,
 * three engines behind it:
 *
 *  - TRYPTIFY  — the ported Tryptify stack (https://github.com/tryptz/
 *    Tryptify): MixBusProcessor (C++17 mixing console, 36 snapin types,
 *    Oxford Inflator/Compressor, crossfeed) → AutoEqProcessor (measurement-
 *    driven parametric correction) → ParametricEqProcessor, driven through
 *    Tryptify's own AudioProcessorChain helper.
 *  - LASTWAVE  — the ported LastWave-native stack (https://github.com/
 *    Clash-Projects/LastWave-native): NativePcmAudioProcessor running the
 *    native Oboe/soxr engine with its 15-band graphic EQ + Studio Master
 *    Clarity chain.
 *  - NONE      — the stock ArchiveTune FloatDspProcessor, bit-exactly as
 *    before (quality-gated engagement, 16-bit/float flip at the tail).
 *
 * Engine selection is re-evaluated at every configure (every track): the
 * service hands over fresh lambdas reading its live pref fields, so flipping
 * a toggle takes effect on the next track without rebuilding the player —
 * the same contract the USB-exclusive route already follows.
 *
 * Encoding discipline (the hard-won lesson of the automix crash): the tail
 * is the ONLY safe place for an encoding flip, and the flip is snapshotted
 * at configure time. Both engines always RUN in float; the tail then emits
 * float when the exclusive USB output is active, or converts back to 16-bit
 * for the AudioTrack route. Mid-track flag flips land at the next configure.
 */

package moe.rukamori.archivetune.playback.dsp

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.lastwave.app.playback.NativePcmAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import tf.monochrome.android.audio.dsp.DspNativeLoader
import tf.monochrome.android.audio.dsp.MixBusProcessor
import tf.monochrome.android.audio.eq.AutoEqProcessor
import tf.monochrome.android.audio.eq.ParametricEqProcessor

class AudioEngineRouterProcessor(
    /** Fresh read at every decision point: the Tryptify engine toggle. */
    private val tryptifyEnabled: () -> Boolean,
    /** Fresh read at every decision point: the Lastwave engine toggle. */
    private val lastwaveEnabled: () -> Boolean,
    /** The Tryptify sub-chain, in Tryptify's own order. */
    private val tryptifyMixBus: MixBusProcessor,
    private val tryptifyAutoEq: AutoEqProcessor,
    private val tryptifyParamEq: ParametricEqProcessor,
    /** The Lastwave engine processor. */
    private val lastwaveProcessor: NativePcmAudioProcessor,
    /** The stock ArchiveTune float DSP — engine mode NONE. */
    private val stockDsp: FloatDspProcessor,
) : BaseAudioProcessor() {

    enum class Engine { NONE, TRYPTIFY, LASTWAVE }

    /**
     * Set by the service while a USB-exclusive float output is (about to be)
     * active. Engines always process in float; this flag decides whether the
     * tail EMITS float (exclusive USB output) or 16-bit (AudioTrack route).
     * Takes effect at the next configure — same contract the stock DSP has.
     */
    @Volatile
    var outputFloat: Boolean = false

    /** Snapshot of [outputFloat] as it was when THIS track was configured. */
    @Volatile
    private var activeOutputFloat: Boolean = false

    /** Snapshot of the engine selection for THIS track. */
    @Volatile
    var activeEngine: Engine = Engine.NONE
        private set

    // 16-bit → float up-conversion scratch (engines need float input; the
    // upstream chain still hands the tail 16-bit whenever Sonic ran).
    private var upconvertScratch: ByteBuffer = ByteBuffer.allocateDirect(0)
        .order(ByteOrder.nativeOrder())

    // float → 16-bit down-conversion scratch (AudioTrack route).
    private var downconvertScratch: ByteBuffer = ByteBuffer.allocateDirect(0)
        .order(ByteOrder.nativeOrder())

    private val tryptifyChain = tf.monochrome.android.audio.usb.AudioProcessorChain(
        listOf(tryptifyMixBus, tryptifyAutoEq, tryptifyParamEq),
    )

    private var engineAvailableTryptify: Boolean = tryptifyNativeAvailable()
    private var engineAvailableLastwave: Boolean = lastwaveProcessor.isAvailable

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        engineAvailableTryptify = tryptifyNativeAvailable()
        engineAvailableLastwave = lastwaveProcessor.isAvailable

        val selected = when {
            tryptifyEnabled() && engineAvailableTryptify -> Engine.TRYPTIFY
            lastwaveEnabled() && engineAvailableLastwave -> Engine.LASTWAVE
            else -> Engine.NONE
            }
        val changed = selected != activeEngine
        activeEngine = selected
        activeOutputFloat = outputFloat

        return when (selected) {
            Engine.TRYPTIFY -> {
                if (inputAudioFormat.channelCount > 2) {
                    // The Tryptify chain handles at most stereo; multichannel
                    // falls back to the stock tail (passthrough for >2ch).
                    activeEngine = Engine.NONE
                    return configureStock(inputAudioFormat)
                }
                // Feed the chain float (it preserves its input encoding, and
                // the engines' precision lives in the float domain).
                val floatFormat = AudioProcessor.AudioFormat(
                    inputAudioFormat.sampleRate,
                    inputAudioFormat.channelCount,
                    C.ENCODING_PCM_FLOAT,
                )
                val chainOut = tryptifyChain.configure(floatFormat)
                val outEncoding =
                    if (activeOutputFloat) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT
                if (changed) {
                    Log.i(
                        TAG,
                        "Tryptify engine ENGAGED (rate=${inputAudioFormat.sampleRate} ch=${inputAudioFormat.channelCount} " +
                            "floatOut=$activeOutputFloat chainOut=${chainOut.encoding})",
                    )
                }
                AudioProcessor.AudioFormat(
                    chainOut.sampleRate.takeIf { it > 0 } ?: inputAudioFormat.sampleRate,
                    chainOut.channelCount.takeIf { it > 0 } ?: inputAudioFormat.channelCount,
                    outEncoding,
                )
            }
            Engine.LASTWAVE -> {
                if (inputAudioFormat.channelCount > 2) {
                    activeEngine = Engine.NONE
                    return configureStock(inputAudioFormat)
                }
                // NativePcmAudioProcessor accepts 16-bit and float input and
                // outputs float at the (clamped) source rate.
                val lastwaveOut = runCatching { lastwaveProcessor.configure(inputAudioFormat) }
                    .getOrElse { AudioProcessor.AudioFormat.NOT_SET }
                if (lastwaveOut == AudioProcessor.AudioFormat.NOT_SET) {
                    activeEngine = Engine.NONE
                    return configureStock(inputAudioFormat)
                }
                val outEncoding =
                    if (activeOutputFloat) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT
                if (changed) {
                    Log.i(
                        TAG,
                        "Lastwave engine ENGAGED (rate=${inputAudioFormat.sampleRate} ch=${inputAudioFormat.channelCount} " +
                            "floatOut=$activeOutputFloat out=${lastwaveOut.sampleRate}Hz)",
                    )
                }
                AudioProcessor.AudioFormat(
                    lastwaveOut.sampleRate,
                    lastwaveOut.channelCount,
                    outEncoding,
                )
            }
            Engine.NONE -> configureStock(inputAudioFormat)
        }
    }

    private fun configureStock(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        runCatching { stockDsp.configure(inputAudioFormat) }
            .getOrElse { AudioProcessor.AudioFormat.NOT_SET }

    // ------------------------------------------------------------------
    // Processing
    // ------------------------------------------------------------------

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        when (activeEngine) {
            Engine.TRYPTIFY -> queueTryptify(inputBuffer)
            Engine.LASTWAVE -> queueLastwave(inputBuffer)
            Engine.NONE -> queueStock(inputBuffer)
        }
    }

    private fun queueTryptify(inputBuffer: ByteBuffer) {
        val inputFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val floatInput = if (inputFloat) inputBuffer else upconvertToFloat(inputBuffer)
        val chainOut = tryptifyChain.process(floatInput)
        emitEngineOutput(chainOut)
    }

    private fun queueLastwave(inputBuffer: ByteBuffer) {
        lastwaveProcessor.queueInput(inputBuffer)
        emitEngineOutput(lastwaveProcessor.output)
    }

    private fun queueStock(inputBuffer: ByteBuffer) {
        stockDsp.queueInput(inputBuffer)
        val out = stockDsp.output
        if (!out.hasRemaining()) return
        replaceOutputBuffer(out.remaining()).put(out).flip()
    }

    /**
     * Copies an engine's float output into this processor's output buffer,
     * converting to 16-bit when this track was configured for the AudioTrack
     * route. The input window is consumed.
     */
    private fun emitEngineOutput(engineOutput: ByteBuffer) {
        if (!engineOutput.hasRemaining()) return
        if (activeOutputFloat) {
            replaceOutputBuffer(engineOutput.remaining()).put(engineOutput).flip()
        } else {
            val pcm16 = floatToPcm16(engineOutput)
            replaceOutputBuffer(pcm16.remaining()).put(pcm16).flip()
        }
    }

    private fun upconvertToFloat(input: ByteBuffer): ByteBuffer {
        val frames = input.remaining() / (2 * inputAudioFormat.channelCount)
        val samples = frames * inputAudioFormat.channelCount
        if (upconvertScratch.capacity() < samples * 4) {
            upconvertScratch = ByteBuffer.allocateDirect(samples * 4)
                .order(ByteOrder.nativeOrder())
        } else {
            upconvertScratch.clear()
        }
        val srcPos = input.position()
        for (i in 0 until samples) {
            val s = input.getShort(srcPos + (i shl 1))
            upconvertScratch.putFloat(i shl 2, s / 32768f)
        }
        upconvertScratch.position(0)
        upconvertScratch.limit(samples * 4)
        input.position(input.limit())
        return upconvertScratch
    }

    private fun floatToPcm16(input: ByteBuffer): ByteBuffer {
        val frames = input.remaining() / (4 * outputAudioFormat.channelCount)
        val samples = frames * outputAudioFormat.channelCount
        if (downconvertScratch.capacity() < samples * 2) {
            downconvertScratch = ByteBuffer.allocateDirect(samples * 2)
                .order(ByteOrder.nativeOrder())
        } else {
            downconvertScratch.clear()
        }
        val srcPos = input.position()
        for (i in 0 until samples) {
            val f = input.getFloat(srcPos + (i shl 2))
            val clamped = if (f > 1f) 1f else if (f < -1f) -1f else f
            downconvertScratch.putShort(i shl 1, (clamped * 32767f).toInt().toShort())
        }
        downconvertScratch.position(0)
        downconvertScratch.limit(samples * 2)
        return downconvertScratch
    }

    // ------------------------------------------------------------------
    // Lifecycle plumbing
    // ------------------------------------------------------------------

    override fun onFlush() {
        tryptifyChain.flush()
        runCatching { lastwaveProcessor.flush() }
        stockDsp.flush()
    }

    override fun onReset() {
        tryptifyChain.reset()
        runCatching { lastwaveProcessor.reset() }
        stockDsp.reset()
        activeEngine = Engine.NONE
        activeOutputFloat = false
    }

    override fun onQueueEndOfStream() {
        tryptifyMixBus.queueEndOfStream()
        tryptifyAutoEq.queueEndOfStream()
        tryptifyParamEq.queueEndOfStream()
        runCatching { lastwaveProcessor.queueEndOfStream() }
        stockDsp.queueEndOfStream()
    }

    companion object {
        private const val TAG = "AudioEngineRouter"

        @Volatile
        private var tryptifyNativeProbe: Boolean? = null

        fun tryptifyNativeAvailable(): Boolean {
            tryptifyNativeProbe?.let { return it }
            val ok = runCatching { DspNativeLoader.ensureLoaded() }.isSuccess
            tryptifyNativeProbe = ok
            return ok
        }
    }
}
