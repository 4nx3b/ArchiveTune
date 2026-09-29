@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback.dsp

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.lastwave.app.playback.NativePcmAudioProcessor
import java.nio.ByteBuffer
import tf.monochrome.android.audio.dsp.ChannelDetectorProcessor
import tf.monochrome.android.audio.dsp.DownmixProcessor
import tf.monochrome.android.audio.dsp.DspNativeLoader
import tf.monochrome.android.audio.dsp.MixBusProcessor
import tf.monochrome.android.audio.eq.AutoEqProcessor
import tf.monochrome.android.audio.eq.ParametricEqProcessor
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.audio.usb.ToFloatPcmAudioProcessor

class AudioEngineRouterProcessor(

    private val tryptifyEnabled: () -> Boolean,

    private val lastwaveEnabled: () -> Boolean,

    private val tryptifyMixBus: MixBusProcessor,
    private val tryptifyAutoEq: AutoEqProcessor,
    private val tryptifyParamEq: ParametricEqProcessor,

    private val channelDetector: ChannelDetectorProcessor,

    private val downmix: DownmixProcessor,

    private val spectrumTap: SpectrumAnalyzerTap,

    private val variRate: tf.monochrome.android.audio.resample.VariRateAudioProcessor,

    private val stretch: tf.monochrome.android.audio.stretch.StretchAudioProcessor,

    private val lastwaveProcessor: NativePcmAudioProcessor,

    private val stockDsp: FloatDspProcessor,
) : BaseAudioProcessor() {
    enum class Engine { NONE, TRYPTIFY, LASTWAVE }

    @Volatile
    var outputFloat: Boolean = false

    /**
     * The encoding of the bytes the active engine path actually emits — NOT
     * the encoding this processor declares downstream. The Tryptify chain
     * passes its input encoding through (16-bit in, 16-bit out — every stage
     * accepts both), and the sink's ToInt16PcmAudioProcessor guarantees 16-bit
     * reaches this processor whenever sink-side float output is off (always,
     * with the custom chain installed). LastWave's NativePcmAudioProcessor is
     * the opposite: it always emits float regardless of input. Treating the
     * chain bytes as float when they are 16-bit packs two shorts into one
     * garbage float and halves the frame count — audio at 2x speed, pure
     * distortion, and a playback position that outruns the feed until the
     * track stalls. The emit path below branches on this field instead
     * (through [EnginePcmCodec]).
     */
    @Volatile
    private var engineDataEncoding: Int = C.ENCODING_PCM_16BIT

    @Volatile
    var activeEngine: Engine = Engine.NONE
        private set

    @Volatile
    private var activeOutputFloat: Boolean = false

    /** Set by the service when the engine preference pair flips mid-track:
     *  the playback thread re-evaluates the engine at the next queueInput and
     *  re-routes WITHOUT waiting for the next onConfigure (track change) or a
     *  service restart — the old engine's processing stops and the new one
     *  starts within one buffer (~tens of milliseconds). */
    @Volatile
    private var reevaluateRequested: Boolean = false

    fun requestEngineReevaluate() {
        reevaluateRequested = true
    }

    private val tryptifyChain = tf.monochrome.android.audio.usb.AudioProcessorChain(
        listOf(
            ToFloatPcmAudioProcessor(),
            channelDetector,
            downmix,
            tryptifyMixBus,
            tryptifyAutoEq,
            tryptifyParamEq,
            spectrumTap,
            variRate,
            stretch,
        ),
    )

    private var engineAvailableTryptify: Boolean = tryptifyNativeAvailable()
    private var engineAvailableLastwave: Boolean = lastwaveProcessor.isAvailable

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT &&
            encoding != C.ENCODING_PCM_24BIT && encoding != C.ENCODING_PCM_32BIT
        ) {
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

        EngineRuntime.activeEngine = selected
        EngineRuntime.outputFloat = outputFloat

        return when (selected) {
            Engine.TRYPTIFY -> {

                val chainOut = tryptifyChain.configure(inputAudioFormat)
                engineDataEncoding =
                    chainOut.encoding.takeIf { it > 0 } ?: inputAudioFormat.encoding
                val outEncoding =
                    if (activeOutputFloat) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT
                if (changed) {
                    Log.i(
                        TAG,
                        "Tryptify engine ENGAGED (rate=${inputAudioFormat.sampleRate} ch=${inputAudioFormat.channelCount} " +
                            "in=${encodingName(inputAudioFormat.encoding)} " +
                            "chainData=${encodingName(engineDataEncoding)} " +
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

                val lastwaveOut = runCatching { lastwaveProcessor.configure(inputAudioFormat) }
                    .getOrElse { AudioProcessor.AudioFormat.NOT_SET }
                if (lastwaveOut == AudioProcessor.AudioFormat.NOT_SET) {
                    activeEngine = Engine.NONE
                    return configureStock(inputAudioFormat)
                }
                // NativePcmAudioProcessor always emits float (its onConfigure
                // declares ENCODING_PCM_FLOAT whatever came in).
                engineDataEncoding = C.ENCODING_PCM_FLOAT
                val outEncoding =
                    if (activeOutputFloat) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT
                if (changed) {
                    Log.i(
                        TAG,
                        "Lastwave engine ENGAGED (rate=${inputAudioFormat.sampleRate} ch=${inputAudioFormat.channelCount} " +
                            "in=${encodingName(inputAudioFormat.encoding)} " +
                            "chainData=float floatOut=$activeOutputFloat out=${lastwaveOut.sampleRate}Hz)",
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

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        if (reevaluateRequested) {
            reevaluateRequested = false
            rerouteEngineIfChanged()
        }
        when (activeEngine) {
            Engine.TRYPTIFY -> queueTryptify(inputBuffer)
            Engine.LASTWAVE -> queueLastwave(inputBuffer)
            Engine.NONE -> queueStock(inputBuffer)
        }
    }

    /**
     * Mid-stream engine switch: re-run the configure-time engine decision on
     * the PLAYBACK thread and, when the winner changed, flush the old path and
     * configure the new one against the SAME input format. The sink contract
     * (this processor's declared output format) is untouched — [activeOutputFloat]
     * keeps the encoding the sink negotiated, and [EnginePcmCodec] converts the
     * new path's data encoding to it. When the new engine path would declare a
     * different sample rate or channel count than the sink is running at (a
     * resampling engine mid-chain), the switch is deferred to the next natural
     * onConfigure instead of corrupting the stream.
     */
    private fun rerouteEngineIfChanged() {
        val input = inputAudioFormat
        if (input == AudioProcessor.AudioFormat.NOT_SET) return
        engineAvailableTryptify = tryptifyNativeAvailable()
        engineAvailableLastwave = lastwaveProcessor.isAvailable
        val desired =
            when {
                tryptifyEnabled() && engineAvailableTryptify -> Engine.TRYPTIFY
                lastwaveEnabled() && engineAvailableLastwave -> Engine.LASTWAVE
                else -> Engine.NONE
            }
        if (desired == activeEngine) return

        val declaredOut = outputAudioFormat
        // Locals only — nothing commits until every deferral check passes.
        var newDataEncoding = input.encoding
        val newOut: AudioProcessor.AudioFormat =
            when (desired) {
                Engine.TRYPTIFY -> {
                    val chainOut = tryptifyChain.configure(input)
                    newDataEncoding =
                        chainOut.encoding.takeIf { it > 0 } ?: input.encoding
                    AudioProcessor.AudioFormat(
                        chainOut.sampleRate.takeIf { it > 0 } ?: input.sampleRate,
                        chainOut.channelCount.takeIf { it > 0 } ?: input.channelCount,
                        if (activeOutputFloat) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT,
                    )
                }
                Engine.LASTWAVE -> {
                    if (input.channelCount > 2) {
                        logSwitchDeferred("lastwave rejects >2ch")
                        return
                    }
                    val out =
                        runCatching { lastwaveProcessor.configure(input) }
                            .getOrElse { AudioProcessor.AudioFormat.NOT_SET }
                    if (out == AudioProcessor.AudioFormat.NOT_SET) {
                        logSwitchDeferred("lastwave configure failed")
                        return
                    }
                    newDataEncoding = C.ENCODING_PCM_FLOAT
                    AudioProcessor.AudioFormat(
                        out.sampleRate,
                        out.channelCount,
                        if (activeOutputFloat) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT,
                    )
                }
                Engine.NONE -> {
                    val out =
                        runCatching { stockDsp.configure(input) }
                            .getOrElse { AudioProcessor.AudioFormat.NOT_SET }
                    out
                }
            }
        if (newOut == AudioProcessor.AudioFormat.NOT_SET) {
            logSwitchDeferred("new path refused the format")
            return
        }
        if (declaredOut != AudioProcessor.AudioFormat.NOT_SET &&
            newOut.sampleRate != declaredOut.sampleRate &&
            (newOut.sampleRate > 0 && declaredOut.sampleRate > 0)
        ) {
            logSwitchDeferred("rate ${newOut.sampleRate}!=${declaredOut.sampleRate}")
            return
        }
        if (declaredOut != AudioProcessor.AudioFormat.NOT_SET &&
            newOut.channelCount != declaredOut.channelCount &&
            (newOut.channelCount > 0 && declaredOut.channelCount > 0)
        ) {
            logSwitchDeferred("channels ${newOut.channelCount}!=${declaredOut.channelCount}")
            return
        }

        val old = activeEngine
        // Flush the abandoned path's tail so nothing stale drains later.
        runCatching { tryptifyChain.flush() }
        runCatching { lastwaveProcessor.flush() }
        runCatching { stockDsp.flush() }
        activeEngine = desired
        engineDataEncoding = newDataEncoding
        EngineRuntime.activeEngine = desired
        Log.i(
            TAG,
            "engine SWITCHED mid-track $old -> $desired " +
                "(in=${encodingName(input.encoding)} data=${encodingName(engineDataEncoding)} " +
                "floatOut=$activeOutputFloat)",
        )
    }

    private fun logSwitchDeferred(reason: String) {
        Log.i(TAG, "engine switch deferred to next configure: $reason")
    }

    private fun queueTryptify(inputBuffer: ByteBuffer) {
        // Membership follows the live isActive() of every stage: a speed
        // change flips VariRate's ratio-driven isActive mid-track, and
        // without this refresh the resampler would stay out of the pipeline
        // until the next configure (the speed change silently lost).
        tryptifyChain.refreshActive()
        val chainOut = tryptifyChain.process(inputBuffer)
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

    private fun emitEngineOutput(engineOutput: ByteBuffer) {
        if (!engineOutput.hasRemaining()) return
        val encoded = EnginePcmCodec.encode(
            data = engineOutput,
            dataIsFloat = engineDataEncoding == C.ENCODING_PCM_FLOAT,
            outputFloat = activeOutputFloat,
            channels = outputAudioFormat.channelCount,
        )
        replaceOutputBuffer(encoded.remaining()).put(encoded).flip()
    }

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
        engineDataEncoding = C.ENCODING_PCM_16BIT
    }

    override fun onQueueEndOfStream() {
        // Forward EOS through the whole Tryptify chain (VariRate flushes its
        // sinc tail at EOS) and drain whatever the chain still holds, then
        // emit it — previously the tail frames were dropped at every track
        // end, and LastWave's resampler flush output was created but never
        // read.
        val tryptifyTail = tryptifyChain.queueEndOfStreamAndDrain()
        if (tryptifyTail.hasRemaining()) {
            emitEngineOutput(tryptifyTail)
        }
        runCatching {
            lastwaveProcessor.queueEndOfStream()
            val lastwaveTail = lastwaveProcessor.output
            if (lastwaveTail.hasRemaining()) {
                emitEngineOutput(lastwaveTail)
            }
        }
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

        internal fun encodingName(encoding: Int): String = when (encoding) {
            C.ENCODING_PCM_16BIT -> "pcm16"
            C.ENCODING_PCM_FLOAT -> "float"
            C.ENCODING_PCM_24BIT -> "pcm24"
            C.ENCODING_PCM_32BIT -> "pcm32"
            else -> "encoding[$encoding]"
        }
    }
}
