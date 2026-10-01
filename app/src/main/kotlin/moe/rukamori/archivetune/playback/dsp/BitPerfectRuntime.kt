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

/**
 * The single source of truth for the Bit-Perfect / Native Output system.
 *
 * Bit-Perfect mode bypasses EVERY sample-modifying processor (EQ, AutoEQ,
 * ReplayGain, loudness normalization, crossfeed, bass boost, reverb,
 * virtualizer, balance/pan, Sonic speed/pitch, silence skipping, transition
 * DSP, both DSP engines and software volume) and hands the decoder's PCM to
 * the output UNTOUCHED whenever the active route can carry that exact
 * encoding/rate/channel configuration. PCM16, PCM24-packed, PCM32 and FLOAT
 * all stay native; the sink's float pipeline (lossless for 24-bit, which is
 * exactly representable in an f32 mantissa) carries 24/32-bit depth to
 * direct-playback tracks.
 *
 * The evaluation runs per track (on the playback thread, at processor-chain
 * configure time) via [AudioManager.getDirectPlaybackSupport] on API 33+,
 * and the RESULT — not the request — drives the status line and the mixer
 * verification, so "Bit-Perfect" is only ever reported when the active output
 * genuinely carries the source format.
 */
object BitPerfectRuntime {

    /** Master toggle (Bit-Perfect Output). */
    @Volatile
    var requested: Boolean = false

    /** Native Sample Rate sub-toggle (default ON): prefer the source rate. */
    @Volatile
    var nativeSampleRatePreferred: Boolean = true

    /**
     * True while the processor chain should be fully bypassed for the CURRENT
     * track: bit-perfect requested, no engine/DSP engaged, and the output
     * directly supports the source format. Set at chain-configure time by
     * [evaluateTrack]; cleared by [clearTrack].
     */
    private val bypassEngaged = AtomicBoolean(false)

    /** Live state snapshot for the settings status row. */
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

    /** Whether the DSP chain must pass bytes through untouched right now. */
    val chainBypassActive: Boolean
        get() = bypassEngaged.get()

    /**
     * Per-track evaluation. Called from the playback thread when the audio
     * processor chain configures against a new input format.
     *
     * @param engineOrDspEngaged an engine (Tryptify/LastWave) or the float DSP
     *        is currently engaged — bit-perfect never fights the engines.
     * @param outputSampleRateHz the ACTUAL opened output rate when known
     *        (0 = unknown yet).
     */
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

        if (!requested) {
            failure = null // simply off — not an error state
        } else if (engineOrDspEngaged) {
            failure = "DSP engine active"
        } else if (audioManager == null) {
            failure = "AudioManager unavailable"
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Verified against AOSP AudioPolicyManager::getDirectPlaybackSupport:
            // a PCM profile that matches the format/rate/channels under the
            // DIRECT output flag reports AUDIO_DIRECT_BITSTREAM_SUPPORTED —
            // despite the name, that flag is what a direct PCM path sets.
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
                    @Suppress("NewApi") // guarded by SDK_INT
                    AudioManager.getDirectPlaybackSupport(queryFormat, AUDIO_ATTRIBUTES_MUSIC)
                }.getOrDefault(AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED)
            direct = (flags and AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED) != 0
            if (!direct) {
                // The route cannot carry this exact format — normal fallback
                // plays instead; never silently label it bit-perfect.
                failure = "No direct support for ${bits}bit/${inputSampleRate}Hz"
            } else {
                // Direct support exists at the source rate itself: no
                // resampling on the direct path.
                nativeRateMatched = true
            }
        } else {
            // Below API 33 there is no direct-support query; report honestly
            // instead of guessing.
            failure = "Requires Android 13+"
            direct = false
        }

        val bypass = requested && !engineOrDspEngaged && direct
        bypassEngaged.set(bypass)

        val outputRate = if (outputSampleRateHz > 0) outputSampleRateHz else inputSampleRate
        // Seed the container side only when no input-format report has landed
        // yet for this track (identical-format track switches may not re-fire
        // the renderer's input-format event).
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
            mixerBitPerfectActive = false,
            verifiedBitPerfect = bypass,
            failureReason = failure,
        )
        return bypass
    }

    /**
     * Records the CONTAINER truth for the current track — the bit depth the
     * file/stream actually carries (media3's FLAC extractor puts the
     * STREAMINFO depth into Format.pcmEncoding; WAV/AIFF extractors do the
     * same). Compressed formats without a pcm depth are flagged lossy. This
     * is independent of the decoded depth: the platform FLAC decoder
     * truncates 24-bit material to 16-bit PCM unless the float route
     * negotiated an f32 decode — the INPUT side of the chain pill reports
     * THIS value.
     */
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

    /** Clears the per-track state (player release / no format). */
    fun clearTrack() {
        bypassEngaged.set(false)
        status = Status.idle()
    }

    /** Notifies the runtime that the effective (software) volume changed. */
    fun notifyVolume(effectiveVolume: Float) {
        if (status.verifiedBitPerfect && (effectiveVolume == 1f) != !status.softwareVolumeActive) {
            status = status.copy(softwareVolumeActive = effectiveVolume != 1f)
        }
    }

    /** Records a verified Android 14+ BIT_PERFECT mixer-attribute result. */
    fun notifyMixerBitPerfect(active: Boolean, outputRateHz: Int) {
        status = status.copy(
            mixerBitPerfectActive = active,
            outputSampleRate = if (active) outputRateHz else status.outputSampleRate,
        )
    }

    /** Notifies the runtime of the actual USB-exclusive route state. */
    fun notifyUsbExclusive(active: Boolean, rate: Int, bits: Int) {
        status = status.copy(usbExclusiveActive = active)
        if (active) {
            status = status.copy(
                outputSampleRate = rate,
                outputBitDepth = bits,
                // The exclusive wire bypasses the Android mixer entirely.
                directPlaybackSupported = true,
                nativeRateMatched = status.sourceSampleRate == rate,
                resamplerActive = status.sourceSampleRate != rate,
                verifiedBitPerfect = status.sourceSampleRate == rate &&
                    bits >= status.sourceBitDepth &&
                    !status.dspActive,
            )
        }
    }

    /**
     * Android 14+ BIT_PERFECT mixer attributes for the active USB route:
     * returns the mixer attributes the platform should apply, or null when
     * none apply. Verification is done by the caller via
     * [preferredMixerAttributesAreBitPerfect].
     */
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
            @Suppress("NewApi") // guarded by SDK_INT
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

    /** Verifies the applied mixer attributes really are BIT_PERFECT. */
    fun preferredMixerAttributesAreBitPerfect(
        context: Context,
        device: AudioDeviceInfo?,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        if (device == null) return false
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return runCatching {
            @Suppress("NewApi") // guarded by SDK_INT
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
