@file:OptIn(androidx.media3.common.util.UnstableApi::class)

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 *
 * Kotlin host for the native 32-bit float DSP (libarchivetune_dsp).
 */

package moe.rukamori.archivetune.playback.dsp

import java.nio.ByteBuffer

/**
 * JNI surface of the native 32-bit float DSP chain. One DSP context is held
 * per [FloatDspProcessor] instance; the handle-based API keeps the hot path
 * free of allocations (direct ByteBuffers in, direct ByteBuffers out).
 */
object FloatDsp {
    /** True when libarchivetune_dsp loaded. Absence degrades to passthrough. */
    val available: Boolean = runCatching { System.loadLibrary("archivetune_dsp") }.isSuccess

    @JvmStatic
    external fun nativeCreate(sampleRate: Int, channels: Int): Long

    @JvmStatic
    external fun nativeRelease(handle: Long)

    @JvmStatic
    external fun nativeSetEngaged(handle: Long, engaged: Boolean)

    @JvmStatic
    external fun nativeReset(handle: Long)

    @JvmStatic
    external fun nativeSetLimiterCeiling(handle: Long, ceilingDb: Float)

    /** 16-bit direct buffer in (window at [inOffsetBytes, ..)) -> FLOAT direct buffer out. */
    @JvmStatic
    external fun nativeProcessShortToFloat(
        handle: Long,
        inBuffer: ByteBuffer,
        inOffsetBytes: Int,
        outBuffer: ByteBuffer,
        frames: Int,
        channels: Int,
    )

    /** FLOAT direct buffer in (window at [inOffsetBytes, ..)) -> FLOAT direct buffer out. */
    @JvmStatic
    external fun nativeProcessFloatToFloat(
        handle: Long,
        inBuffer: ByteBuffer,
        inOffsetBytes: Int,
        outBuffer: ByteBuffer,
        frames: Int,
        channels: Int,
    )

    /** 16-bit direct buffer in (window at [inOffsetBytes, ..)) -> dithered 16-bit direct buffer out. */
    @JvmStatic
    external fun nativeProcessShortToShort(
        handle: Long,
        inBuffer: ByteBuffer,
        inOffsetBytes: Int,
        outBuffer: ByteBuffer,
        frames: Int,
        channels: Int,
    )
}
