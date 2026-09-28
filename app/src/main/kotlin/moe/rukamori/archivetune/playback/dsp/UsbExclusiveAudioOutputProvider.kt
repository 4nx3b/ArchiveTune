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
 * With an audio engine engaged the exclusive route switches to that engine's
 * own bit-perfect USB driver: Tryptify's libusb UAC1/UAC2 driver
 * (TryptifyLibusbAudioOutput) or LastWave-native's usbdevfs driver
 * (LastwaveUsbdevfsAudioOutput). The engine choice is re-evaluated at every
 * sink configure (every track transition) from live service fields, so
 * flipping an engine toggle lands on the next track without a player
 * rebuild — same contract as the rest of this provider.
 *
 * The decision is re-evaluated at every sink configure (every track
 * transition), which makes USB plug/unplug effective on the next song
 * without rebuilding the player. Device add/remove also fires
 * onFormatSupportChanged() so a mid-session route change is noticed.
 */

package moe.rukamori.archivetune.playback.dsp

import android.content.Context
import android.hardware.usb.UsbManager
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
import com.lastwave.app.playback.ExclusiveUsbOutput
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import tf.monochrome.android.audio.usb.BypassVolumeController
import tf.monochrome.android.audio.usb.LibusbUacDriver

/** Which audio engine owns playback right now (live read by the provider). */
enum class AudioEngineKind { NONE, TRYPTIFY, LASTWAVE }

class UsbExclusiveAudioOutputProvider(
    context: Context,
    /** Fresh read at every decision point: the USB-exclusive pref. */
    private val exclusiveEnabled: () -> Boolean,
    /** Fresh read at every decision point: which engine is enabled. */
    private val engineSelection: () -> AudioEngineKind = { AudioEngineKind.NONE },
    /** Tryptify's libusb UAC driver (bit-perfect USB output). */
    private val tryptifyDriver: LibusbUacDriver? = null,
    /** Tryptify's bypass software volume (no AudioFlinger on that route). */
    private val tryptifyVolume: BypassVolumeController? = null,
    /** LastWave's usbdevfs exclusive output. */
    private val lastwaveExclusiveUsb: ExclusiveUsbOutput? = null,
    /** Scope for the asynchronous USB permission request. */
    private val permissionScope: CoroutineScope? = null,
) : AudioOutputProvider {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val usbManager =
        appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val inner: AudioOutputProvider =
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

    /** The hardware [android.hardware.usb.UsbDevice] behind a USB sink, or null. */
    private fun currentHardwareUsbDevice(): android.hardware.usb.UsbDevice? {
        val info = currentUsbDevice() ?: return null
        return usbManager.deviceList.values.firstOrNull { it.deviceId == info.id }
            ?: usbManager.deviceList.values.firstOrNull { device ->
                (0 until device.interfaceCount).any {
                    device.getInterface(it).interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_AUDIO
                }
            }
    }

    /** True when the exclusive path should serve [formatConfig]. */
    private fun exclusiveApplies(formatConfig: FormatConfig): Boolean {
        if (!exclusiveEnabled()) return false
        val format = formatConfig.format
        if (MimeTypes.AUDIO_RAW != format.sampleMimeType) return false
        val encoding = format.pcmEncoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) return false
        if (format.channelCount > MAX_EXCLUSIVE_CHANNELS) return false
        if (currentUsbDevice() == null) return false
        return when (engineSelection()) {
            AudioEngineKind.TRYPTIFY ->
                TryptifyLibusbAudioOutput.available() && tryptifyDriver != null &&
                    currentHardwareUsbDevice() != null
            AudioEngineKind.LASTWAVE ->
                lastwaveExclusiveUsb != null && currentHardwareUsbDevice() != null
            AudioEngineKind.NONE -> FloatDsp.available
        }
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
        // Faithful to the chain's output encoding: the engine tail decides
        // float vs 16-bit at configure time and the exclusive outputs accept
        // both (16-bit is up-converted inside the write path). Keeping the
        // config encoding identical to the data the sink writes keeps every
        // frame-size calculation honest.
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
        val engine = engineSelection()
        val usbDevice = currentUsbDevice()
        if (exclusiveEnabled() && usbDevice != null) {
            when (engine) {
                AudioEngineKind.TRYPTIFY -> {
                    val hardware = currentHardwareUsbDevice()
                    val driver = tryptifyDriver
                    if (hardware != null && driver != null && TryptifyLibusbAudioOutput.available()) {
                        return TryptifyLibusbAudioOutput(
                            config = config,
                            usbDevice = hardware,
                            driver = driver,
                            volumeController = tryptifyVolume ?: BypassVolumeController(),
                            permissionScope = permissionScope
                                ?: CoroutineScope(kotlinx.coroutines.Dispatchers.IO),
                        )
                    }
                }
                AudioEngineKind.LASTWAVE -> {
                    val exclusiveUsb = lastwaveExclusiveUsb
                    if (exclusiveUsb != null && currentHardwareUsbDevice() != null) {
                        return LastwaveUsbdevfsAudioOutput(config, exclusiveUsb)
                    }
                }
                AudioEngineKind.NONE -> Unit
            }
        }
        if (engine == AudioEngineKind.NONE && exclusiveEnabled() && usbDevice != null && FloatDsp.available) {
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

    /**
     * Builder-style engine wiring: returns a NEW provider carrying the engine
     * route (selection lambda + the engine's own bit-perfect USB output
     * stack). The receiver keeps serving the plain AAudio path — the
     * secondary crossfade player builds its provider without engines.
     */
    fun withEngines(
        engineSelection: () -> AudioEngineKind,
        tryptifyDriver: LibusbUacDriver?,
        tryptifyVolume: BypassVolumeController?,
        lastwaveExclusiveUsb: ExclusiveUsbOutput?,
        permissionScope: CoroutineScope?,
    ): UsbExclusiveAudioOutputProvider =
        UsbExclusiveAudioOutputProvider(
            context = appContext,
            exclusiveEnabled = exclusiveEnabled,
            engineSelection = engineSelection,
            tryptifyDriver = tryptifyDriver,
            tryptifyVolume = tryptifyVolume,
            lastwaveExclusiveUsb = lastwaveExclusiveUsb,
            permissionScope = permissionScope,
        )

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
