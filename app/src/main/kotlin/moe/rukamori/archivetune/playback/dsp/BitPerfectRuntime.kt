/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.dsp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import java.util.concurrent.atomic.AtomicBoolean

object BitPerfectRuntime {
    @Volatile
    var requested: Boolean = false

    @Volatile
    var nativeSampleRatePreferred: Boolean = true

    private val bypassEngaged = AtomicBoolean(false)

    @Volatile
    private var latchedUsbRateHz: Int = 0

    @Volatile
    private var latchedUsbBits: Int = 0

    var status by mutableStateOf(Status.idle())
        private set

    data class Status(
        val sourceEncoding: Int = C.ENCODING_PCM_16BIT,
        val sourceBitDepth: Int = 16,
        val sourceSampleRate: Int = 0,
        val sourceIsLossy: Boolean = false,
        val decodedEncoding: Int = C.ENCODING_PCM_16BIT,
        val decodedBitDepth: Int = 16,
        val outputEncoding: Int = C.ENCODING_PCM_16BIT,
        val outputBitDepth: Int = 16,
        val outputSampleRate: Int = 0,
        val channels: Int = 2,
        val directPlaybackSupported: Boolean = false,
        val nativeRateMatched: Boolean = false,
        val resamplerActive: Boolean = false,
        val dspActive: Boolean = false,
        val softwareVolumeActive: Boolean = false,
        val usbExclusiveActive: Boolean = false,
        val mixerBitPerfectActive: Boolean = false,
        val verifiedBitPerfect: Boolean = false,
        val failureReason: String? = null,
    ) {
        companion object {
            fun idle(): Status = Status()
        }
    }

    val chainBypassActive: Boolean
        get() = bypassEngaged.get()

    fun evaluateTrack(
        context: Context,
        inputEncoding: Int,
        inputSampleRate: Int,
        inputChannels: Int,
        engineOrDspEngaged: Boolean,
        usbExclusive: Boolean,
        outputSampleRateHz: Int = 0,
        effectiveVolume: Float = 1f,
    ): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val bits = bitDepthOf(inputEncoding)
        val channels = inputChannels.coerceIn(1, 2)

        var direct = false
        var nativeRateMatched = false
        var failure: String? = null
        var usbRouteVerified = false

        if (!requested) {
            failure = null
        } else if (usbExclusive) {

            // The engine / float-DSP USB-exclusive transport writes PCM straight to the
            // DAC through its own driver (libusb / usbdevfs / AAud), so the shared Android
            // mixer is bypassed entirely. Rate and bit-depth are verified from the live
            // wire values latched via notifyUsbExclusive().
            direct = true
            usbRouteVerified = latchedUsbRateHz > 0 &&
                latchedUsbRateHz == inputSampleRate &&
                latchedUsbBits >= bits
            nativeRateMatched = latchedUsbRateHz <= 0 || latchedUsbRateHz == inputSampleRate
            if (!usbRouteVerified && latchedUsbRateHz > 0) {
                failure =
                    if (latchedUsbRateHz != inputSampleRate) {
                        "USB clock locked at ${latchedUsbRateHz}Hz"
                    } else {
                        "USB transport carries ${latchedUsbBits}bit"
                    }
            }
        } else if (engineOrDspEngaged) {

            // The ported engines deliver bit-perfect output through their own
            // USB-exclusive transport; over the shared mixer their chain is
            // truncated to 16-bit by the platform sink.
            failure = "DSP engine active \u2014 enable USB-exclusive output"
        } else if (audioManager == null) {
            failure = "AudioManager unavailable"
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            val channelConfig =
                if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val queryFormat =
                AudioFormat.Builder()
                    .setEncoding(inputEncoding)
                    .setSampleRate(inputSampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            val flags =
                runCatching {
                    @Suppress("NewApi")
                    AudioManager.getDirectPlaybackSupport(queryFormat, AUDIO_ATTRIBUTES_MUSIC)
                }.getOrDefault(AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED)

            // DIRECT_PLAYBACK_SUPPORTED marks PCM that the platform can hand to the sink
            // without transcoding; BITSTREAM only applies to compressed passthrough and can
            // never be granted for a PCM query.
            direct = (flags and AudioManager.DIRECT_PLAYBACK_SUPPORTED) != 0
            if (!direct) {
                failure = "No direct support for ${bits}bit/${inputSampleRate}Hz"
            } else {
                nativeRateMatched = true
            }
        } else {

            failure = "Requires Android 13+"
            direct = false
        }

        val bypass = requested && !engineOrDspEngaged && direct
        bypassEngaged.set(bypass)

        val outputRate = if (outputSampleRateHz > 0) outputSampleRateHz else inputSampleRate

        val seedSource = status.sourceSampleRate == 0
        status = status.copy(
            sourceEncoding = if (seedSource) inputEncoding else status.sourceEncoding,
            sourceBitDepth = if (seedSource) bits else status.sourceBitDepth,
            sourceSampleRate = if (seedSource) inputSampleRate else status.sourceSampleRate,
            sourceIsLossy = if (seedSource) false else status.sourceIsLossy,
            decodedEncoding = inputEncoding,
            decodedBitDepth = bits,
            outputEncoding = if (direct) inputEncoding else C.ENCODING_PCM_16BIT,
            outputBitDepth = if (direct) bits else 16,
            outputSampleRate = outputRate,
            channels = channels,
            directPlaybackSupported = direct,
            nativeRateMatched = nativeRateMatched,
            resamplerActive = !nativeRateMatched && inputSampleRate != outputRate,
            dspActive = engineOrDspEngaged,
            softwareVolumeActive = bypass && effectiveVolume != 1f,
            usbExclusiveActive = usbExclusive,
            mixerBitPerfectActive = if (usbExclusive) false else status.mixerBitPerfectActive,
            verifiedBitPerfect = bypass || (requested && usbExclusive && usbRouteVerified),
            failureReason = failure,
        )
        return bypass
    }

    fun reportContainerFormat(
        inputEncoding: Int,
        inputSampleRate: Int,
        inputChannels: Int,
    ) {
        if (inputSampleRate <= 0) return
        val hasPcmDepth = inputEncoding != C.ENCODING_INVALID && inputEncoding != Format.NO_VALUE
        status = status.copy(
            sourceEncoding = if (hasPcmDepth) inputEncoding else C.ENCODING_INVALID,
            sourceBitDepth = if (hasPcmDepth) bitDepthOf(inputEncoding) else 0,
            sourceSampleRate = inputSampleRate,
            sourceIsLossy = !hasPcmDepth,
            channels = inputChannels.coerceIn(1, 2),
        )
    }

    fun clearTrack() {
        bypassEngaged.set(false)
        latchedUsbRateHz = 0
        latchedUsbBits = 0
        status = Status.idle()
    }

    fun notifyVolume(effectiveVolume: Float) {
        if (status.verifiedBitPerfect && (effectiveVolume == 1f) != !status.softwareVolumeActive) {
            status = status.copy(softwareVolumeActive = effectiveVolume != 1f)
        }
    }

    fun notifyMixerBitPerfect(active: Boolean, outputRateHz: Int) {
        status = status.copy(
            mixerBitPerfectActive = active,
            outputSampleRate = if (active) outputRateHz else status.outputSampleRate,
        )
    }

    fun notifyUsbExclusive(
        active: Boolean,
        rate: Int,
        bits: Int,
        engineTransport: Boolean = false,
    ) {
        status = status.copy(usbExclusiveActive = active)
        if (active) {
            latchedUsbRateHz = rate
            latchedUsbBits = bits
            status = status.copy(
                outputSampleRate = rate,
                outputBitDepth = bits,
                directPlaybackSupported = true,
                nativeRateMatched = status.sourceSampleRate == rate,
                resamplerActive = status.sourceSampleRate > 0 && status.sourceSampleRate != rate,
                verifiedBitPerfect = requested &&
                    status.sourceSampleRate == rate &&
                    bits >= status.sourceBitDepth &&
                    (engineTransport || !status.dspActive),
            )
        } else {
            latchedUsbRateHz = 0
            latchedUsbBits = 0
        }
    }

    fun preferredMixerAttributes(
        context: Context,
        device: AudioDeviceInfo?,
        encoding: Int,
        sampleRate: Int,
        channels: Int,
    ): AudioMixerAttributes? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        if (device == null) return null
        if (device.type != AudioDeviceInfo.TYPE_USB_DEVICE &&
            device.type != AudioDeviceInfo.TYPE_USB_HEADSET
        ) {
            return null
        }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val supported = runCatching {
            @Suppress("NewApi")
            audioManager.getSupportedMixerAttributes(device)
        }.getOrNull().orEmpty()
        val channelConfig =
            if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        return supported.firstOrNull { attrs ->
            attrs.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                attrs.format.encoding == encoding &&
                attrs.format.sampleRate == sampleRate &&
                attrs.format.channelCount == channels.coerceAtMost(2)
        } ?: supported.firstOrNull { attrs ->
            attrs.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
        }
    }

    fun preferredMixerAttributesAreBitPerfect(
        context: Context,
        device: AudioDeviceInfo?,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        if (device == null) return false
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return runCatching {
            @Suppress("NewApi")
            val preferred = audioManager.getPreferredMixerAttributes(AUDIO_ATTRIBUTES_MUSIC, device)
            preferred != null && preferred.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
        }.getOrDefault(false)
    }

    fun bitDepthOf(encoding: Int): Int =
        when (encoding) {
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT -> 32
            C.ENCODING_PCM_FLOAT -> 32
            else -> 16
        }

    private val AUDIO_ATTRIBUTES_MUSIC =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
}
