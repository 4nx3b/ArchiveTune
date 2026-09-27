@file:OptIn(androidx.media3.common.util.UnstableApi::class)

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 *
 * The AAudio-exclusive [androidx.media3.exoplayer.audio.AudioOutput]: a
 * USB-direct 32-bit float output with EXCLUSIVE sharing mode, handed to
 * DefaultAudioSink by [UsbExclusiveAudioOutputProvider].
 *
 * DefaultAudioSink keeps doing ALL the heavy lifting — the processor chain
 * (Sonic speed, haptics, stereo pan, transition DSP, the 32-bit float DSP
 * tail), position anchoring, playback parameters, volume ramps. This class
 * only implements the low-level stream contract:
 *  - open an exclusive AAudio float stream pinned to the USB device;
 *  - write the sink's final PCM window (float, or 16-bit up-converted);
 *  - report playout position / buffer sizes / session id / underruns.
 *
 * The sink applies speed through the chain's SonicAudioProcessor, so this
 * output reports the DEFAULT playback parameters and never applies speed
 * itself (OutputConfig.usePlaybackParameters is false for this output).
 */

package moe.rukamori.archivetune.playback.dsp

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
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

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Synchronized
    private fun ensureStream(): AaudioNativeStream? {
        if (released) return null
        stream?.let { return it }
        if (!FloatDsp.available) return null
        val opened = AaudioNativeStream()
        val result = opened.open(
            sampleRate = if (config.sampleRate > 0) config.sampleRate else 48000,
            channels = channelCountForMask(config.channelMask),
            deviceId = deviceId,
            exclusive = true,
        )
        if (result != 0 || !opened.isExclusive()) {
            opened.release()
            return null
        }
        stream = opened
        lastXRunCount = 0
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

    // ------------------------------------------------------------------
    // AudioOutput contract
    // ------------------------------------------------------------------

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

    /**
     * Fully synchronized against the lifecycle methods: write() blocks in
     * native code for up to [WRITE_TIMEOUT_MS] and reads through the stream
     * handle afterwards (xRunCount/position), while release() DELETES the
     * native object. An unsynchronized write racing a release is a
     * use-after-free that takes the whole process down as a bare SIGSEGV —
     * no Java exception, no crash dialog, nothing in the app's own logs.
     * Holding this monitor for the duration of the write is the price of
     * making the pair safe; release/pause simply wait out the in-flight
     * write (bounded by the same timeout).
     */
    @Synchronized
    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        val current = stream ?: ensureStream()
            ?: // The exclusive stream could not be opened (device gone, HAL
                // refused): report a recoverable write failure so the sink's
                // error path takes over instead of spinning on a null output.
                throw AudioOutput.WriteException(AAUDIO_ERROR_INVALID_STATE, true)
        val isFloat = config.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val bytesPerFrame = bytesPerSample * channelCount
        val frames = buffer.remaining() / bytesPerFrame
        if (frames <= 0) return true
        val written = current.write(buffer, frames, channelCount, isFloat, volume, WRITE_TIMEOUT_MS)
        if (written < 0) {
            // A disconnected USB DAC: drop the stream so a later write (or the
            // provider's next configure) re-resolves the route.
            stream?.close()
            stream?.release()
            stream = null
            started = false
            throw AudioOutput.WriteException(written, false)
        }
        // Advance the buffer over what the stream consumed (the sink retries
        // from position on partial writes, AudioTrack.write-style).
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
        // Speed/pitch live in the processor chain (Sonic); this output never
        // applies them (see OutputConfig.usePlaybackParameters == false).
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
        private const val WRITE_TIMEOUT_MS = 1000

        /** Mirrors AAUDIO_ERROR_INVALID_STATE from the NDK headers. */
        private const val AAUDIO_ERROR_INVALID_STATE = -896

        /**
         * True when an exclusive float stream can be opened to [deviceId]
         * right now — used as the provider's cheap go/no-go probe before it
         * hands this output class to the sink.
         */
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
