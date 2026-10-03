/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.automix

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

@UnstableApi
class AutoMixFilterProcessor : BaseAudioProcessor() {
    @Volatile
    private var targetLowPassHz = OPEN_HZ

    @Volatile
    private var targetHighPassHz = OFF_HZ

    private var channelCount = 0
    private var sampleRate = 0
    private var isFloat = false

    private var currentLowPassHz = OPEN_HZ
    private var currentHighPassHz = OFF_HZ

    private var lowLow = FloatArray(0)
    private var lowBand = FloatArray(0)
    private var highLow = FloatArray(0)
    private var highBand = FloatArray(0)

    private var lowF = 0f
    private var highF = 0f

    fun setCutoffs(
        lowPassHz: Double,
        highPassHz: Double,
    ) {
        targetLowPassHz = lowPassHz.toFloat().coerceIn(MIN_HZ, OPEN_HZ)
        targetHighPassHz = highPassHz.toFloat().coerceIn(OFF_HZ, MAX_HIGH_PASS_HZ)
    }

    fun open() = setCutoffs(OPEN_HZ.toDouble(), OFF_HZ.toDouble())

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (moe.rukamori.archivetune.playback.dsp.BitPerfectRuntime.chainBypassActive) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        if (inputAudioFormat.channelCount < 1 ||
            (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
                inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT)
        ) {
            Log.w(
                TAG,
                "automix filter inactive: encoding=${inputAudioFormat.encoding} " +
                    "channels=${inputAudioFormat.channelCount} is not 16-bit/float PCM",
            )
            return AudioProcessor.AudioFormat.NOT_SET
        }
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        lowLow = FloatArray(channelCount)
        lowBand = FloatArray(channelCount)
        highLow = FloatArray(channelCount)
        highBand = FloatArray(channelCount)
        currentLowPassHz = targetLowPassHz
        currentHighPassHz = targetHighPassHz
        updateCoefficients()
        return inputAudioFormat
    }

    override fun onFlush() {
        lowLow.fill(0f)
        lowBand.fill(0f)
        highLow.fill(0f)
        highBand.fill(0f)
        currentLowPassHz = targetLowPassHz
        currentHighPassHz = targetHighPassHz
        updateCoefficients()
    }

    override fun onReset() {
        targetLowPassHz = OPEN_HZ
        targetHighPassHz = OFF_HZ
        currentLowPassHz = OPEN_HZ
        currentHighPassHz = OFF_HZ
        lowLow = FloatArray(0)
        lowBand = FloatArray(0)
        highLow = FloatArray(0)
        highBand = FloatArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytesPerSample = if (isFloat) FLOAT_BYTES else SHORT_BYTES
        val bytesPerFrame = bytesPerSample * channelCount
        if (bytesPerFrame == 0) return
        val frameCount = inputBuffer.remaining() / bytesPerFrame
        if (frameCount == 0) return
        val outputBuffer = replaceOutputBuffer(frameCount * bytesPerFrame)

        val targetLow = targetLowPassHz
        val targetHigh = targetHighPassHz

        val parked = targetLow >= OPEN_HZ && targetHigh <= OFF_HZ &&
            currentLowPassHz >= OPEN_HZ - SETTLED_HZ && currentHighPassHz <= OFF_HZ + SETTLED_HZ
        if (parked) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        val lowActive = targetLow < OPEN_HZ - SETTLED_HZ || currentLowPassHz < OPEN_HZ - SETTLED_HZ
        val highActive = targetHigh > OFF_HZ + SETTLED_HZ || currentHighPassHz > OFF_HZ + SETTLED_HZ

        var frame = 0
        while (frame < frameCount) {
            if (frame % GLIDE_FRAMES == 0) {
                currentLowPassHz += (targetLow - currentLowPassHz) * GLIDE_ALPHA
                currentHighPassHz += (targetHigh - currentHighPassHz) * GLIDE_ALPHA
                updateCoefficients()
            }
            for (channel in 0 until channelCount) {
                val input = if (isFloat) inputBuffer.float else inputBuffer.short / 32768f
                var sample = input
                if (lowActive) sample = lowPassSample(channel, sample)
                if (highActive) sample = highPassSample(channel, sample)
                if (isFloat) {
                    outputBuffer.putFloat(sample)
                } else {
                    outputBuffer.putShort((sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                }
            }
            frame++
        }

        outputBuffer.flip()
    }

    private fun lowPassSample(
        channel: Int,
        input: Float,
    ): Float {
        val low = lowLow[channel]
        val band = lowBand[channel]
        val newLow = low + lowF * band
        val newBand = band + lowF * (input - newLow - DAMPING * band)
        lowLow[channel] = newLow
        lowBand[channel] = newBand
        return newLow
    }

    private fun highPassSample(
        channel: Int,
        input: Float,
    ): Float {
        val low = highLow[channel]
        val band = highBand[channel]
        val newLow = low + highF * band
        val newBand = band + highF * (input - newLow - DAMPING * band)
        highLow[channel] = newLow
        highBand[channel] = newBand
        return input - newLow - DAMPING * newBand
    }

    private fun updateCoefficients() {
        val nyquist = sampleRate / 2f
        val lowHz = currentLowPassHz.coerceIn(MIN_HZ, nyquist * 0.45f)
        val highHz = currentHighPassHz.coerceIn(MIN_HZ, nyquist * 0.45f)
        lowF = (2f * sin(FLOAT_PI * lowHz / sampleRate)).coerceAtMost(MAX_F)
        highF = (2f * sin(FLOAT_PI * highHz / sampleRate)).coerceAtMost(MAX_F)
    }

    companion object {
        private const val TAG = "AutoMixFilter"

        const val OPEN_HZ = 20_000f

        const val OFF_HZ = 10f

        const val MIN_HZ = 20f
        const val MAX_HIGH_PASS_HZ = 7_000f

        private const val SHORT_BYTES = 2
        private const val FLOAT_BYTES = 4
        private const val SETTLED_HZ = 400f
        private const val GLIDE_FRAMES = 64
        private const val GLIDE_ALPHA = 0.25f
        private const val DAMPING = 1.0f
        private const val MAX_F = 0.8f
        private const val FLOAT_PI = Math.PI.toFloat()
    }
}
