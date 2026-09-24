@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.C
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class StereoPanAudioProcessor : BaseAudioProcessor() {
    @Volatile
    private var balance: Float = 0f

    @Volatile
    private var rotationEnabled: Boolean = false

    @Volatile
    private var rotationSpeedHz: Float = DEFAULT_SPEED_HZ

    private var phase: Double = 0.0

    private var echoHistory: ShortArray? = null
    private var echoWriteIndex: Int = 0
    private var echoDelay1Samples: Int = 0
    private var echoDelay2Samples: Int = 0

    fun setBalance(value: Float) {
        balance = value.coerceIn(-1f, 1f)
    }

    fun setRotation(enabled: Boolean, speedHz: Float) {
        rotationEnabled = enabled
        rotationSpeedHz = speedHz.coerceIn(MIN_SPEED_HZ, MAX_SPEED_HZ)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == STEREO_CHANNEL_COUNT) {

            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }

    override fun queueInput(inputBuffer: ByteBuffer) {

        if (!inputBuffer.hasRemaining()) return

        val format = inputAudioFormat
        val balanceNow = balance
        val rotationOn = rotationEnabled
        val balanceOn = abs(balanceNow) > BALANCE_EPSILON
        if (
            format.encoding != C.ENCODING_PCM_16BIT ||
            format.channelCount != STEREO_CHANNEL_COUNT ||
            format.sampleRate <= 0 ||
            (!rotationOn && !balanceOn)
        ) {
            replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
            return
        }

        val sampleRate = format.sampleRate

        val history = ensureEchoHistory(sampleRate)
        val frameCount = inputBuffer.remaining() / (BYTES_PER_SAMPLE * STEREO_CHANNEL_COUNT)
        val output = replaceOutputBuffer(frameCount * BYTES_PER_SAMPLE * STEREO_CHANNEL_COUNT)

        val speedHz = rotationSpeedHz
        val phaseStep = 2.0 * PI * speedHz / sampleRate
        val swingHalfWidth = PULSATOR_WIDTH / 2f

        val dryGain = if (rotationOn) ECHO_OUT_GAIN else 1f
        val echoGain = if (rotationOn) ECHO_OUT_GAIN * ECHO_IN_GAIN else 0f
        val tap1Gain = echoGain * ECHO_TAP1_DECAY
        val tap2Gain = echoGain * ECHO_TAP2_DECAY

        val balanceLeft = if (balanceOn) 1f - max(0f, balanceNow) else 1f
        val balanceRight = if (balanceOn) 1f - max(0f, -balanceNow) else 1f

        var localPhase = phase
        val delay1 = echoDelay1Samples
        val delay2 = echoDelay2Samples
        val historySize = history.size
        val framesCount = historySize / STEREO_CHANNEL_COUNT
        var writeIndex = echoWriteIndex

        repeat(frameCount) {
            val inLeft = inputBuffer.short.toInt()
            val inRight = inputBuffer.short.toInt()

            val left: Float
            val right: Float
            if (rotationOn) {

                val swing = sin(localPhase).toFloat()
                val modLeft = 0.5f + swingHalfWidth * swing
                val modRight = 0.5f - swingHalfWidth * swing
                left = inLeft * modLeft
                right = inRight * modRight
                localPhase += phaseStep
            } else {
                left = inLeft.toFloat()
                right = inRight.toFloat()
            }

            var outLeft = left * balanceLeft * dryGain
            var outRight = right * balanceRight * dryGain
            if (rotationOn) {
                val frame1 = (writeIndex - delay1 + framesCount) % framesCount
                val frame2 = (writeIndex - delay2 + framesCount) % framesCount
                outLeft += tap1Gain * history[frame1 * STEREO_CHANNEL_COUNT] +
                    tap2Gain * history[frame2 * STEREO_CHANNEL_COUNT]
                outRight += tap1Gain * history[frame1 * STEREO_CHANNEL_COUNT + 1] +
                    tap2Gain * history[frame2 * STEREO_CHANNEL_COUNT + 1]
            }

            if (rotationOn) {
                outLeft = softLimit(outLeft)
                outRight = softLimit(outRight)
            }

            if (rotationOn) {
                history[writeIndex * STEREO_CHANNEL_COUNT] = inLeft.toShort()
                history[writeIndex * STEREO_CHANNEL_COUNT + 1] = inRight.toShort()
                writeIndex = (writeIndex + 1) % framesCount
            }

            output.putShort(clampToPcm16(outLeft))
            output.putShort(clampToPcm16(outRight))
        }

        phase = localPhase % (2.0 * PI)
        echoWriteIndex = writeIndex

        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun onFlush() {

        echoHistory?.fill(0)
        echoWriteIndex = 0
    }

    override fun onReset() {
        phase = 0.0
        echoHistory = null
        echoWriteIndex = 0
    }

    private fun ensureEchoHistory(sampleRate: Int): ShortArray {
        val delay1 = max(1, sampleRate * ECHO_TAP1_DELAY_MS / 1000)
        val delay2 = max(delay1 + 1, sampleRate * ECHO_TAP2_DELAY_MS / 1000)
        val frames = delay2 + 1
        val existing = echoHistory
        if (existing != null && echoDelay1Samples == delay1 && echoDelay2Samples == delay2 && existing.size == frames * STEREO_CHANNEL_COUNT) {
            return existing
        }
        echoDelay1Samples = delay1
        echoDelay2Samples = delay2
        val fresh = ShortArray(frames * STEREO_CHANNEL_COUNT)
        echoHistory = fresh
        echoWriteIndex = 0
        return fresh
    }

    private fun softLimit(value: Float): Float {
        if (abs(value) <= LIMITER_CEILING) return value
        val sign = if (value >= 0) 1 else -1

        val overshoot = abs(value) - LIMITER_CEILING
        return sign * (LIMITER_CEILING + overshoot / (1f + overshoot * KNEE_SHARPNESS))
    }

    private fun clampToPcm16(value: Float): Short = value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private companion object {
        const val STEREO_CHANNEL_COUNT = 2
        const val BYTES_PER_SAMPLE = 2

        const val PULSATOR_WIDTH = 0.75f

        const val ECHO_IN_GAIN = 0.6f
        const val ECHO_OUT_GAIN = 0.4f
        const val ECHO_TAP1_DELAY_MS = 30
        const val ECHO_TAP2_DELAY_MS = 60
        const val ECHO_TAP1_DECAY = 0.2f
        const val ECHO_TAP2_DECAY = 0.15f

        const val LIMITER_CEILING = 0.97f * Short.MAX_VALUE
        const val KNEE_SHARPNESS = 0.02f

        const val BALANCE_EPSILON = 0.005f

        const val MIN_SPEED_HZ = 0.03f
        const val MAX_SPEED_HZ = 0.25f
        const val DEFAULT_SPEED_HZ = 0.2f
    }
}
