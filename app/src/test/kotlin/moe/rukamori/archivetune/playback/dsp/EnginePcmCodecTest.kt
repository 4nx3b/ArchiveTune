/*
 * ArchiveTune (2026)
 * © Rukamori — GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The engine router's emit contract: the bytes the engine chain produced must
 * leave the router in the encoding it DECLARED to the sink, whatever encoding
 * the chain actually emitted.
 *
 * The Tryptify chain passes its input encoding through (16-bit in, 16-bit
 * out) while the sink's ToInt16 stage guarantees 16-bit reaches the router;
 * the original emit path assumed float unconditionally, which packed two
 * shorts into one garbage float and halved the frame count — audio at 2x
 * speed, fully distorted, playback stalling once the position outran the
 * feed. These tests lock all four encoding combinations of the codec.
 */
class EnginePcmCodecTest {

    private fun stereoPcm16(vararg shorts: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(shorts.size * 2).order(ByteOrder.nativeOrder())
        shorts.forEach { buffer.putShort(it.toShort()) }
        buffer.flip()
        return buffer
    }

    private fun stereoFloat(vararg floats: Float): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(floats.size * 4).order(ByteOrder.nativeOrder())
        floats.forEach { buffer.putFloat(it) }
        buffer.flip()
        return buffer
    }

    /**
     * THE regression: 16-bit chain bytes (what the Tryptify chain emits for a
     * 16-bit input) must pass through byte-for-byte when the router declared
     * 16-bit output — frame count and content preserved, no float re-read.
     */
    @Test
    fun pcm16ChainData_passesThroughByteForByte_whenPcm16Declared() {
        val input = stereoPcm16(0, 16384, -16384, 32767, -32768, 1234, -5678, 90)
        val expected = stereoPcm16(0, 16384, -16384, 32767, -32768, 1234, -5678, 90)

        val output = EnginePcmCodec.encode(
            data = input,
            dataEncoding = androidx.media3.common.C.ENCODING_PCM_16BIT,
            outputEncoding = androidx.media3.common.C.ENCODING_PCM_16BIT,
            channels = 2,
        )

        assertSame(input, output)
        assertEquals(expected.remaining(), output.remaining())
        while (expected.hasRemaining() && output.hasRemaining()) {
            assertEquals(expected.short, output.short)
        }
        assertFalse(output.hasRemaining())
    }

    /** 16-bit chain bytes widen exactly 2x with short/32768 scaling. */
    @Test
    fun pcm16ChainData_widensToFloat_whenFloatDeclared() {
        val output = EnginePcmCodec.encode(
            data = stereoPcm16(16384, -16384, 32767, -32768),
            dataEncoding = androidx.media3.common.C.ENCODING_PCM_16BIT,
            outputEncoding = androidx.media3.common.C.ENCODING_PCM_FLOAT,
            channels = 2,
        )

        assertEquals(16, output.remaining())
        assertEquals(16384 / 32768f, output.float, 1e-6f)
        assertEquals(-16384 / 32768f, output.float, 1e-6f)
        assertEquals(32767 / 32768f, output.float, 1e-6f)
        assertEquals(-32768 / 32768f, output.float, 1e-6f)
        assertFalse(output.hasRemaining())
    }

    /** Float chain bytes halve with clamp-to-range floatToPcm16 math. */
    @Test
    fun floatChainData_downconvertsToPcm16_whenPcm16Declared() {
        val output = EnginePcmCodec.encode(
            data = stereoFloat(0.5f, -0.5f, 2f, -2f),
            dataEncoding = androidx.media3.common.C.ENCODING_PCM_FLOAT,
            outputEncoding = androidx.media3.common.C.ENCODING_PCM_16BIT,
            channels = 2,
        )

        assertEquals(8, output.remaining())
        assertEquals((0.5f * 32767f).toInt().toShort(), output.short)
        assertEquals((-0.5f * 32767f).toInt().toShort(), output.short)
        assertEquals(32767, output.short.toInt())
        assertEquals(-32767, output.short.toInt())
        assertFalse(output.hasRemaining())
    }

    /** Float chain bytes (LastWave's contract) pass through untouched. */
    @Test
    fun floatChainData_passesThroughByteForByte_whenFloatDeclared() {
        val input = stereoFloat(0.25f, -0.75f, 0.9f, -0.1f)

        val output = EnginePcmCodec.encode(
            data = input,
            dataEncoding = androidx.media3.common.C.ENCODING_PCM_FLOAT,
            outputEncoding = androidx.media3.common.C.ENCODING_PCM_FLOAT,
            channels = 2,
        )

        assertSame(input, output)
        assertEquals(16, output.remaining())
        assertEquals(0.25f, output.float, 1e-6f)
        assertEquals(-0.75f, output.float, 1e-6f)
        assertEquals(0.9f, output.float, 1e-6f)
        assertEquals(-0.1f, output.float, 1e-6f)
    }

    /** Scratch reuse across calls must never leak stale bytes. */
    @Test
    fun smallerBufferAfterLarger_leavesNoStaleTail() {
        EnginePcmCodec.encode(
            data = stereoFloat(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f),
            dataEncoding = androidx.media3.common.C.ENCODING_PCM_FLOAT,
            outputEncoding = androidx.media3.common.C.ENCODING_PCM_16BIT,
            channels = 2,
        )

        val smaller = EnginePcmCodec.encode(
            data = stereoFloat(0.25f, -0.25f),
            dataEncoding = androidx.media3.common.C.ENCODING_PCM_FLOAT,
            outputEncoding = androidx.media3.common.C.ENCODING_PCM_16BIT,
            channels = 2,
        )

        assertEquals(4, smaller.remaining())
        assertEquals((0.25f * 32767f).toInt().toShort(), smaller.short)
        assertEquals((-0.25f * 32767f).toInt().toShort(), smaller.short)
        assertFalse(smaller.hasRemaining())
    }

    /** Mono data respects the 1-channel frame size. */
    @Test
    fun monoData_convertsWithSingleChannelFrames() {
        val output = EnginePcmCodec.encode(
            data = stereoPcm16(16384, -16384),
            dataEncoding = androidx.media3.common.C.ENCODING_PCM_16BIT,
            outputEncoding = androidx.media3.common.C.ENCODING_PCM_FLOAT,
            channels = 1,
        )

        assertEquals(8, output.remaining())
        assertEquals(16384 / 32768f, output.float, 1e-6f)
        assertEquals(-16384 / 32768f, output.float, 1e-6f)
    }
}

    /**
     * Native-format preservation (Bit-Perfect): PCM24-packed and PCM32 bytes
     * pass through byte-for-byte whenever the declared output encoding
     * matches the emitted one — a 24-bit stream can never silently collapse
     * to 16-bit (or float) inside the codec.
     */
    @Test
    fun pcm24AndPcm32_stayNative_whenDeclaredOutputMatches() {
        val pcm24 = ByteBuffer.allocateDirect(9).order(ByteOrder.nativeOrder())
        pcm24.put(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, -0x66, 0x7F)).flip()
        val pcm24Expected = ByteBuffer.allocateDirect(9).order(ByteOrder.nativeOrder())
        pcm24Expected.put(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, -0x66, 0x7F)).flip()

        val out24 = EnginePcmCodec.encode(
            data = pcm24,
            dataEncoding = androidx.media3.common.C.ENCODING_PCM_24BIT,
            outputEncoding = androidx.media3.common.C.ENCODING_PCM_24BIT,
            channels = 2,
        )
        assertEquals(pcm24Expected.remaining(), out24.remaining())
        pcm24Expected.position(0)
        out24.position(0)
        while (pcm24Expected.hasRemaining()) {
            assertEquals(pcm24Expected.get(), out24.get())
        }

        val pcm32 = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        pcm32.putInt(0x12345678).putInt(-0x12345678).flip()
        val pcm32Expected = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        pcm32Expected.putInt(0x12345678).putInt(-0x12345678).flip()

        val out32 = EnginePcmCodec.encode(
            data = pcm32,
            dataEncoding = androidx.media3.common.C.ENCODING_PCM_32BIT,
            outputEncoding = androidx.media3.common.C.ENCODING_PCM_32BIT,
            channels = 1,
        )
        assertSame(pcm32, out32)
    }
