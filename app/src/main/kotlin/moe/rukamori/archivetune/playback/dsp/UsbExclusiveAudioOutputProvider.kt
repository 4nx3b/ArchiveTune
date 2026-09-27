@file:OptIn(androidx.media3.common.util.UnstableApi::class)

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 *
 * The AudioOutputProvider handed to DefaultAudioSink: serves the AAudio
 * EXCLUSIVE float output whenever the user enabled USB-exclusive audio AND
 * a USB output device is currently attached, and otherwise delegates to the
 * stock AudioTrackAudioOutputProvider so everything behaves exactly as
 * before.
 *
 * The decision is re-evaluated at every sink configure (every track
 * transition), which makes USB plug/unplug effective on the next song
 * without rebuilding the player. Device add/remove also fires
 * onFormatSupportChanged() so a mid-session route change is noticed.
 */

package moe.rukamori.archivetune.playback.dsp

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatConfig
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatSupport
import androidx.media3.exoplayer.audio.AudioOutputProvider.Listener
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import java.util.concurrent.CopyOnWriteArraySet

class UsbExclusiveAudioOutputProvider(
    context: Context,
    /** Fresh read at every decision point: the USB-exclusive pref. */
    private val exclusiveEnabled: () -> Boolean,
) : AudioOutputProvider {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val inner: AudioTrackAudioOutputProvider =
        AudioTrackAudioOutputProvider.Builder(appContext).build()

    private val listeners = CopyOnWriteArraySet<Listener>()

    init {
        // A USB DAC appearing or disappearing changes what this provider
        // supports: surface it so the sink re-configures at the next track.
        runCatching {
            audioManager.registerAudioDeviceCallback(
                object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                        if (addedDevices.any { it.isUsbSink() }) {
                            listeners.forEach { it.onFormatSupportChanged() }
                        }
                    }

                    override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                        if (removedDevices.any { it.isUsbSink() }) {
                            listeners.forEach { it.onFormatSupportChanged() }
                        }
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }
    }

    private fun AudioDeviceInfo.isUsbSink(): Boolean =
        isSink && type in USB_SINK_TYPES

    /** The first USB output device right now, or null. */
    private fun currentUsbDevice(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.isUsbSink() }

    /** True when the AAudio exclusive path should serve [formatConfig]. */
    private fun exclusiveApplies(formatConfig: FormatConfig): Boolean {
        if (!exclusiveEnabled()) return false
        if (!FloatDsp.available) return false
        val format = formatConfig.format
        if (MimeTypes.AUDIO_RAW != format.sampleMimeType) return false
        val encoding = format.pcmEncoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) return false
        if (format.channelCount > MAX_EXCLUSIVE_CHANNELS) return false
        return currentUsbDevice() != null
    }

    override fun getFormatSupport(formatConfig: FormatConfig): FormatSupport =
        if (exclusiveApplies(formatConfig)) {
            FormatSupport.Builder()
                .setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY)
                .build()
        } else {
            inner.getFormatSupport(formatConfig)
        }

    override fun getOutputConfig(formatConfig: FormatConfig): OutputConfig {
        if (!exclusiveApplies(formatConfig)) {
            return inner.getOutputConfig(formatConfig)
        }
        val format = formatConfig.format
        // Faithful to the chain's output encoding: the AAudio stream itself
        // is always float; 16-bit chain output is up-converted inside the
        // output's write path. Keeping the config encoding identical to the
        // data the sink writes keeps every frame-size calculation honest.
        val encoding = format.pcmEncoding
        val channels = format.channelCount.coerceAtMost(MAX_EXCLUSIVE_CHANNELS)
        val sampleRate = if (format.sampleRate > 0) format.sampleRate else 48000
        val frameSize = Util.getPcmFrameSize(encoding, channels)
        // ~250 ms of buffer: enough crossfade-free headroom for exclusive
        // streams, small enough to keep the latency claim honest.
        val bufferSize = (sampleRate / 4) * frameSize
        return OutputConfig.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelMaskFor(channels))
            .setEncoding(encoding)
            .setBufferSize(bufferSize)
            .setAudioSessionId(formatConfig.audioSessionId)
            .setAudioAttributes(formatConfig.audioAttributes)
            .setIsOffload(false)
            .setIsTunneling(false)
            .setUsePlaybackParameters(false)
            .setUseOffloadGapless(false)
            .setVirtualDeviceId(formatConfig.virtualDeviceId)
            .build()
    }

    override fun getAudioOutput(config: OutputConfig): AudioOutput {
        val usbDevice = currentUsbDevice()
        if (exclusiveEnabled() && usbDevice != null && FloatDsp.available) {
            return AaudioExclusiveAudioOutput(config, usbDevice.id)
        }
        return inner.getAudioOutput(config)
    }

    override fun addListener(listener: Listener) {
        listeners.add(listener)
        inner.addListener(listener)
    }

    override fun removeListener(listener: Listener) {
        listeners.remove(listener)
        inner.removeListener(listener)
    }

    override fun release() {
        listeners.clear()
        inner.release()
    }

    private fun channelMaskFor(channels: Int): Int {
        val mask = Util.getAudioTrackChannelConfig(channels)
        return if (mask > 0) mask else AudioFormat.CHANNEL_OUT_STEREO
    }

    companion object {
        private val USB_SINK_TYPES =
            intArrayOf(
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
            )
        private const val MAX_EXCLUSIVE_CHANNELS = 2
    }
}
