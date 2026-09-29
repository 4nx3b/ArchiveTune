@file:OptIn(androidx.media3.common.util.UnstableApi::class)

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 *
 * LastWave-native's usbdevfs exclusive USB-DAC output as a Media3
 * [AudioOutput] (https://github.com/Clash-Projects/LastWave-native,
 * playback/ExclusiveUsbOutput.kt), handed to DefaultAudioSink by
 * [UsbExclusiveAudioOutputProvider] whenever "Enable Lastwave Audio
 * Processing" is on and USB-exclusive output is requested. The ported
 * [com.lastwave.app.playback.ExclusiveUsbOutput] keeps doing everything it
 * does upstream: claims the AudioStreaming interface away from the kernel
 * snd-usb-audio driver, picks the best alt setting / bit depth for the
 * negotiated rate (its own rate-family matching with soxr-resampled
 * fallback handled by the caller-side processor), drives the 80-URB
 * isochronous ring through the decent-usb-audio-driver module, and applies
 * UAC Feature Unit hardware volume when the DAC exposes one.
 */

package moe.rukamori.archivetune.playback.dsp

import android.media.AudioDeviceInfo
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import androidx.media3.exoplayer.audio.AudioSink
import com.lastwave.app.playback.ExclusiveUsbOutput
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet

class LastwaveUsbdevfsAudioOutput(
    private val config: OutputConfig,
    private val exclusive: ExclusiveUsbOutput,
) : AudioOutput {

    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()
    private var configured = false
    private var started = false

    private val sourceFormat: Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setSampleRate(if (config.sampleRate > 0) config.sampleRate else 48000)
        .setChannelCount(channelCountForMask(config.channelMask))
        .setPcmEncoding(config.encoding)
        .build()

    private val isFloat: Boolean = config.encoding == C.ENCODING_PCM_FLOAT

    @Synchronized
    private fun ensureConfigured(): Boolean {
        if (configured && exclusive.isActive()) return true
        exclusive.setWanted(true)
        val ok = exclusive.configure(sourceFormat)
        if (!ok) {
            Log.w(
                TAG,
                "Lastwave usbdevfs exclusive configure failed (rate=${sourceFormat.sampleRate} " +
                    "ch=${sourceFormat.channelCount} float=$isFloat) — falling back to the standard route",
            )
            return false
        }
        configured = true
        Log.i(
            TAG,
            "Lastwave usbdevfs exclusive stream ENGAGED: rate=${exclusive.currentRateHz()}Hz " +
                "hardwareRates=${exclusive.supportedHardwareRatesHz()} clockMatched=${exclusive.isClockMatched()} " +
                "hwVolume=${exclusive.usesHardwareVolume()} — bit-perfect direct to the DAC",
        )
        return true
    }

    private fun channelCountForMask(channelMask: Int): Int = when (channelMask) {
        android.media.AudioFormat.CHANNEL_OUT_MONO -> 1
        android.media.AudioFormat.CHANNEL_OUT_QUAD -> 4
        android.media.AudioFormat.CHANNEL_OUT_5POINT1, android.media.AudioFormat.CHANNEL_OUT_7POINT1_SURROUND -> 6
        else -> 2
    }

    @Synchronized
    override fun play() {
        if (!ensureConfigured()) {
            throw AudioOutput.WriteException(USB_ENGAGE_FAILED, true)
        }
        exclusive.setPaused(false)
        exclusive.restartIfStopped()
        if (!started) {
            started = true
            listeners.forEach { it.onPositionAdvancing(android.os.SystemClock.elapsedRealtime()) }
        }
    }

    @Synchronized
    override fun pause() {
        exclusive.setPaused(true)
        started = false
    }

    @Synchronized
    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        if (!buffer.hasRemaining()) return true
        if (!ensureConfigured()) {
            throw AudioOutput.WriteException(USB_ENGAGE_FAILED, true)
        }
        if (exclusive.isStreamAlive() && !exclusive.isStreamingAudio()) {
            // The writer thread drains the queue even while the transport is
            // settling; only a dead stream is a real failure.
            if (!exclusive.restartIfStopped()) {
                Log.w(TAG, "Lastwave usbdevfs stream died mid-write — reporting a recoverable failure")
                configured = false
                throw AudioOutput.WriteException(USB_STREAM_DIED, true)
            }
        }
        // The ported write() consumes the whole window or nothing (queue
        // full = "try again", AudioTrack semantics) and already advanced the
        // buffer position for the float path.
        val before = buffer.position()
        val accepted = exclusive.write(buffer, presentationTimeUs, isFloatBuffer = isFloat)
        if (!accepted) {
            buffer.position(before)
            return false
        }
        return true
    }

    @Synchronized
    override fun flush() {
        exclusive.flush()
    }

    @Synchronized
    override fun stop() {
        exclusive.setPaused(true)
        started = false
    }

    @Synchronized
    override fun release() {
        configured = false
        exclusive.setWanted(false)
        exclusive.release()
        listeners.forEach { it.onReleased() }
        listeners.clear()
    }

    override fun setVolume(volume: Float) {
        exclusive.setVolume(volume.coerceIn(0f, 1f))
    }

    override fun isOffloadedPlayback(): Boolean = false

    override fun getAudioSessionId(): Int = 0

    override fun getSampleRate(): Int =
        exclusive.currentRateHz().takeIf { it > 0 } ?: config.sampleRate

    override fun getBufferSizeInFrames(): Long = exclusive.framesWritten()

    override fun getPositionUs(): Long {
        val position = exclusive.getCurrentPositionUs()
        return if (position == AudioSink.CURRENT_POSITION_NOT_SET) 0L else position
    }

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    override fun isStalled(): Boolean = false

    override fun addListener(listener: AudioOutput.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: AudioOutput.Listener) {
        listeners.remove(listener)
    }

    override fun setPlaybackParameters(playbackParams: PlaybackParameters) = Unit

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit

    override fun setOffloadEndOfStream() = Unit

    override fun attachAuxEffect(effectId: Int) = Unit

    override fun setAuxEffectSendLevel(level: Float) = Unit

    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit

    companion object {
        private const val TAG = "LastwaveUsbAudio"
        private const val USB_ENGAGE_FAILED = -9101
        private const val USB_STREAM_DIED = -9102
    }
}
