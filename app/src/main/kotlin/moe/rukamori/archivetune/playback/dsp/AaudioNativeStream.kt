@file:OptIn(androidx.media3.common.util.UnstableApi::class)

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 *
 * Kotlin wrapper over the native AAudio exclusive stream (handle-based).
 */

package moe.rukamori.archivetune.playback.dsp

import java.nio.ByteBuffer

/**
 * Handle wrapper around the native AAudio stream. Every method is a thin JNI
 * hop; lifecycle is owned by [AaudioExclusiveAudioOutput].
 */
class AaudioNativeStream {

    var handle: Long = 0L
        private set

    val isOpen: Boolean
        get() = handle != 0L

    /**
     * Opens the stream. Returns 0 on success, a negative AAudio error code
     * otherwise (exclusive denied, device gone, ...).
     */
    fun open(
        sampleRate: Int,
        channels: Int,
        deviceId: Int,
        exclusive: Boolean,
        usage: Int = 1, // USAGE_MEDIA
        contentType: Int = 2, // CONTENT_TYPE_MUSIC
        bufferCapacityFrames: Int = 0,
    ): Int {
        if (handle != 0L) release()
        handle = nativeOpen(
            sampleRate, channels, deviceId, exclusive, usage, contentType, bufferCapacityFrames,
        )
        if (handle <= 0L) {
            // Negative handles encode the AAudio failure code.
            val error = handle.toInt()
            handle = 0L
            return if (error != 0) error else -900
        }
        return 0
    }

    /**
     * Blocking write of the buffer's [position, limit) window. Returns frames
     * written (>= 0) or a negative AAudio error.
     */
    fun write(
        buffer: ByteBuffer,
        frames: Int,
        channels: Int,
        isFloat: Boolean,
        volume: Float,
        timeoutMs: Int,
    ): Int = nativeWrite(handle, buffer, buffer.position(), frames, channels, isFloat, volume, timeoutMs)

    fun start() = nativeStart(handle)

    fun pause() = nativePause(handle)

    fun flush() = nativeFlush(handle)

    fun stop() = nativeStop(handle)

    fun close() = nativeClose(handle)

    fun release() {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0L
        }
    }

    fun positionFrames(): Long = nativePositionFrames(handle)

    fun sessionId(): Int = nativeSessionId(handle)

    fun sampleRate(): Int = nativeSampleRate(handle)

    fun channelCount(): Int = nativeChannelCount(handle)

    fun xRunCount(): Int = nativeXRunCount(handle)

    fun bufferCapacityFrames(): Long = nativeBufferCapacityFrames(handle)

    fun bufferSizeFrames(): Long = nativeBufferSizeFrames(handle)

    fun isExclusive(): Boolean = nativeIsExclusive(handle)

    private external fun nativeOpen(
        sampleRate: Int,
        channels: Int,
        deviceId: Int,
        exclusive: Boolean,
        usage: Int,
        contentType: Int,
        bufferCapacityFrames: Int,
    ): Long

    private external fun nativeClose(handle: Long)

    private external fun nativeRelease(handle: Long)

    private external fun nativeStart(handle: Long)

    private external fun nativePause(handle: Long)

    private external fun nativeFlush(handle: Long)

    private external fun nativeStop(handle: Long)

    private external fun nativeWrite(
        handle: Long,
        buffer: ByteBuffer,
        offsetBytes: Int,
        frames: Int,
        channels: Int,
        isFloat: Boolean,
        volume: Float,
        timeoutMs: Int,
    ): Int

    private external fun nativePositionFrames(handle: Long): Long

    private external fun nativeSessionId(handle: Long): Int

    private external fun nativeSampleRate(handle: Long): Int

    private external fun nativeChannelCount(handle: Long): Int

    private external fun nativeXRunCount(handle: Long): Int

    private external fun nativeBufferCapacityFrames(handle: Long): Long

    private external fun nativeBufferSizeFrames(handle: Long): Long

    private external fun nativeIsExclusive(handle: Long): Boolean

    companion object {
        init {
            // The shared library is loaded by FloatDsp's availability probe;
            // this class is only used alongside the DSP path.
            runCatching { FloatDsp.available }
        }
    }
}
