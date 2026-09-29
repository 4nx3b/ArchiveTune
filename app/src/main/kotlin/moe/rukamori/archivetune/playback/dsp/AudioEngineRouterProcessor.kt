@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback.dsp

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.lastwave.app.playback.NativePcmAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    @Volatile
    private var activeOutputFloat: Boolean = false

    @Volatile
    var activeEngine: Engine = Engine.NONE
        private set

    private var downconvertScratch: ByteBuffer = ByteBuffer.allocateDirect(0)
        .order(ByteOrder.nativeOrder())

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

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        when (activeEngine) {
            Engine.TRYPTIFY -> queueTryptify(inputBuffer)
            Engine.LASTWAVE -> queueLastwave(inputBuffer)
            Engine.NONE -> queueStock(inputBuffer)
        }
    }

    private fun queueTryptify(inputBuffer: ByteBuffer) {
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
        if (activeOutputFloat) {
            replaceOutputBuffer(engineOutput.remaining()).put(engineOutput).flip()
        } else {
            val pcm16 = floatToPcm16(engineOutput)
            replaceOutputBuffer(pcm16.remaining()).put(pcm16).flip()
        }
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
