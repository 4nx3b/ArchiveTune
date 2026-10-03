@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback.dsp

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet

@Suppress("unused")
class AaudioExclusiveAudioOutput(
    private val config: OutputConfig,
    private val deviceId: Int,
) : AudioOutput {
    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()

    private var stream: AaudioNativeStream? = null
    private var volume = 1f
    private var started = false
    private var released = false
    private var lastXRunCount = 0
    private val channelCount: Int = channelCountForMask(config.channelMask)

    @Synchronized
    private fun ensureStream(): AaudioNativeStream? {
        if (released) return null
        stream?.let { return it }
        if (!FloatDsp.available) return null
        val opened = AaudioNativeStream()
        val wantedRate = if (config.sampleRate > 0) config.sampleRate else 48000
        val wantedChannels = channelCountForMask(config.channelMask)
        val result = opened.open(
            sampleRate = wantedRate,
            channels = wantedChannels,
            deviceId = deviceId,
            exclusive = true,
        )
        if (result != 0 || !opened.isExclusive()) {
            Log.w(
                TAG,
                "USB-exclusive AAudio stream NOT engaged (result=$result exclusive=${opened.isExclusive()} deviceId=$deviceId) — falling back to the standard output",
            )
            opened.release()
            return null
        }
        // Verify the GRANTED format: a HAL that silently opens a different
        // rate would pitch-shift the stream (worse than resampling), and one
        // that opens a different channel count corrupts the layout. Either
        // mismatch falls back to the standard output at the source rate.
        if (opened.sampleRate() != wantedRate || opened.channelCount() != wantedChannels) {
            Log.w(
                TAG,
                "USB-exclusive AAudio stream granted rate=${opened.sampleRate()}Hz " +
                    "ch=${opened.channelCount()} instead of ${wantedRate}Hz/$wantedChannels — " +
                    "falling back to the standard output instead of pitching the audio",
            )
            opened.release()
            return null
        }
        stream = opened
        lastXRunCount = 0
        Log.i(
            TAG,
            "USB-exclusive AAudio stream OPEN: rate=${opened.sampleRate()} channels=${opened.channelCount()} " +
                "deviceId=$deviceId bufferCapacity=${opened.bufferCapacityFrames()} — 32-bit float direct to the DAC",
        )
        return opened
    }

    private fun channelCountForMask(channelMask: Int): Int {
        return when (channelMask) {
            android.media.AudioFormat.CHANNEL_OUT_MONO -> 1
            android.media.AudioFormat.CHANNEL_OUT_QUAD -> 4
            android.media.AudioFormat.CHANNEL_OUT_5POINT1, android.media.AudioFormat.CHANNEL_OUT_7POINT1_SURROUND -> 6
            else -> 2
        }
    }

    @Synchronized
    override fun play() {
        val current = stream ?: ensureStream() ?: return
        current.start()
        if (!started) {
            started = true
            listeners.forEach { it.onPositionAdvancing(SystemClock.elapsedRealtime()) }
        }
    }

    @Synchronized
    override fun pause() {
        stream?.pause()
        started = false
    }

    @Synchronized
    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        val current = stream ?: ensureStream()
            ?:

                throw AudioOutput.WriteException(AAUDIO_ERROR_INVALID_STATE, true)
        val isFloat = config.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val bytesPerFrame = bytesPerSample * channelCount
        val frames = buffer.remaining() / bytesPerFrame
        if (frames <= 0) return true
        val written = current.write(buffer, frames, channelCount, isFloat, volume, WRITE_TIMEOUT_MS)
        if (written < 0) {
            Log.w(TAG, "USB-exclusive AAudio write failed (code=$written) — dropping the stream; the provider re-resolves the route")

            stream?.close()
            stream?.release()
            stream = null
            started = false
            throw AudioOutput.WriteException(written, false)
        }

        buffer.position(buffer.position() + written * bytesPerFrame)
        reportUnderruns(current)
        return written >= frames
    }

    @Synchronized
    override fun flush() {
        stream?.let {
            it.pause()
            it.flush()
        }
    }

    @Synchronized
    override fun stop() {
        stream?.stop()
        started = false
    }

    @Synchronized
    override fun release() {
        released = true
        stream?.let {
            it.stop()
            it.close()
            it.release()
        }
        stream = null
        listeners.forEach { it.onReleased() }
        listeners.clear()
    }

    @Synchronized
    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
    }

    override fun isOffloadedPlayback(): Boolean = false

    @Synchronized
    override fun getAudioSessionId(): Int = stream?.sessionId() ?: 0

    override fun getSampleRate(): Int = stream?.sampleRate() ?: config.sampleRate

    override fun getBufferSizeInFrames(): Long = stream?.bufferSizeFrames() ?: 0L

    override fun getPositionUs(): Long {
        val current = stream ?: return 0L
        val sampleRate = current.sampleRate().takeIf { it > 0 } ?: return 0L
        val frames = current.positionFrames()
        return (frames * 1_000_000L) / sampleRate
    }

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    override fun isStalled(): Boolean = false

    override fun addListener(listener: AudioOutput.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: AudioOutput.Listener) {
        listeners.remove(listener)
    }

    override fun setPlaybackParameters(playbackParams: PlaybackParameters) {
    }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit

    override fun setOffloadEndOfStream() = Unit

    override fun attachAuxEffect(effectId: Int) = Unit

    override fun setAuxEffectSendLevel(level: Float) = Unit

    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit

    private fun reportUnderruns(current: AaudioNativeStream) {
        val xRuns = current.xRunCount()
        if (xRuns > lastXRunCount) {
            lastXRunCount = xRuns
            listeners.forEach { it.onUnderrun() }
        }
    }

    companion object {
        private const val TAG = "UsbExclusiveAudio"
        private const val WRITE_TIMEOUT_MS = 1000

        private const val AAUDIO_ERROR_INVALID_STATE = -896

        fun probeAvailable(deviceId: Int): Boolean {
            if (!FloatDsp.available) return false
            val probe = AaudioNativeStream()
            val result = probe.open(
                sampleRate = 48000,
                channels = 2,
                deviceId = deviceId,
                exclusive = true,
                bufferCapacityFrames = 256,
            )
            val exclusive = probe.isExclusive()
            probe.close()
            probe.release()
            return result == 0 && exclusive
        }
    }
}
