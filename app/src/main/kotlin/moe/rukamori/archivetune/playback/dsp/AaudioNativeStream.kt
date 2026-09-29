@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback.dsp

import java.nio.ByteBuffer

class AaudioNativeStream {
    var handle: Long = 0L
        private set

    val isOpen: Boolean
        get() = handle != 0L

    fun open(
        sampleRate: Int,
        channels: Int,
        deviceId: Int,
        exclusive: Boolean,
        usage: Int = 1,
        contentType: Int = 2,
        bufferCapacityFrames: Int = 0,
    ): Int {
        if (handle != 0L) release()
        handle = nativeOpen(
            sampleRate, channels, deviceId, exclusive, usage, contentType, bufferCapacityFrames,
        )
        if (handle <= 0L) {
            val error = handle.toInt()
            handle = 0L
            return if (error != 0) error else -900
        }
        return 0
    }

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

            runCatching { FloatDsp.available }
        }
    }
}
