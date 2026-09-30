package moe.rukamori.archivetune.playback.dsp

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The engine router's output codec: converts whatever encoding the engine
 * chain actually emitted into the encoding the router declared to the sink.
 *
 * Encoding-based contract: whenever the emitted encoding MATCHES the declared
 * output encoding the bytes pass through untouched — so native PCM24-packed
 * and PCM32 stay native whenever both sides agree, and PCM24 can never
 * silently collapse to PCM16 (or to float) unless the actually selected
 * output requires that conversion. The only real conversions remain the two
 * historical ones: 16-bit widening to float for the USB-exclusive stream and
 * float narrowing to 16-bit for the shared-mixer path.
 *
 * The Tryptify chain passes its input encoding through (16-bit in, 16-bit
 * out — every stage accepts both), and LastWave's NativePcmAudioProcessor
 * always emits float regardless of input. Treating 16-bit chain bytes as
 * float packs two shorts into one garbage float and halves the frame count —
 * audio at 2x speed, fully distorted, and a playback position that outruns
 * the feed until the track stalls and never recovers.
 *
 * Single playback-thread owner (the primary player's engine router is the
 * only consumer), so the scratch buffers are plain object state.
 */
internal object EnginePcmCodec {

    /**
     * Passthrough cases return the input itself; the caller copies it out.
     * Any encoding pair that is NOT one of the two supported conversions is a
     * byte-for-byte passthrough — native formats stay native.
     */
    fun encode(
        data: ByteBuffer,
        dataEncoding: Int,
        outputEncoding: Int,
        channels: Int,
    ): ByteBuffer = when {
        dataEncoding == outputEncoding -> data
        outputEncoding == C.ENCODING_PCM_FLOAT && dataEncoding == C.ENCODING_PCM_16BIT ->
            pcm16ToFloat(data, channels)
        outputEncoding == C.ENCODING_PCM_16BIT && dataEncoding == C.ENCODING_PCM_FLOAT ->
            floatToPcm16(data, channels)
        else -> data
    }

    fun floatToPcm16(input: ByteBuffer, channels: Int): ByteBuffer {
        val frames = input.remaining() / (4 * channels)
        val samples = frames * channels
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

    /** Widens 16-bit engine output to the float the exclusive stream expects. */
    fun pcm16ToFloat(input: ByteBuffer, channels: Int): ByteBuffer {
        val frames = input.remaining() / (2 * channels)
        val samples = frames * channels
        if (widenScratch.capacity() < samples * 4) {
            widenScratch = ByteBuffer.allocateDirect(samples * 4)
                .order(ByteOrder.nativeOrder())
        } else {
            widenScratch.clear()
        }
        val srcPos = input.position()
        for (i in 0 until samples) {
            val s = input.getShort(srcPos + (i shl 1))
            widenScratch.putFloat(i shl 2, s / 32768f)
        }
        widenScratch.position(0)
        widenScratch.limit(samples * 4)
        return widenScratch
    }

    private var downconvertScratch: ByteBuffer = ByteBuffer.allocateDirect(0)
        .order(ByteOrder.nativeOrder())

    private var widenScratch: ByteBuffer = ByteBuffer.allocateDirect(0)
        .order(ByteOrder.nativeOrder())
}
