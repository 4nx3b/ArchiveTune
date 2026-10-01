@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback.dsp

import android.media.AudioDeviceInfo
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioSink.ConfigurationException
import androidx.media3.exoplayer.audio.AudioSink.WriteException
import java.nio.ByteBuffer

/**
 * A live-switchable audio sink pair that finally makes the Bit-Perfect toggle
 * effective WITHOUT a playback-service restart.
 *
 * Why two sinks: DefaultAudioSink bakes `enableFloatOutput` in at build time,
 * and that single flag decides two things at once —
 *
 *  1. what [AudioSink.getFormatSupport] answers for PCM_FLOAT (the renderer
 *     configures MediaCodec's KEY_PCM_ENCODING to float ONLY when the sink
 *     reports float as supported DIRECTLY — that is what keeps 24-bit FLAC
 *     from being decoded down to 16-bit), and
 *  2. whether the sink's internal float pipeline ([trimming, channelMapping,
 *     toFloat]) is used — which deliberately BYPASSES the custom DSP chain.
 *
 * One sink cannot serve both worlds: the DSP path needs the 16-bit pipeline
 * so the engine router owns the encoding, while Bit-Perfect needs the float
 * pipeline so 24/32-bit mantissas ride untouched to a direct PCM_FLOAT track.
 *
 * The pair is therefore built once per player build and the ACTIVE route is
 * re-evaluated continuously from [routeActive]:
 *
 *  - `routeActive() == true`  -> [bitPerfectSink] (float pipeline, custom
 *    chain excluded — byte-exact to the output).
 *  - `routeActive() == false` -> [dspSink] (16-bit pipeline + the engine
 *    router / float DSP tail — exactly the previous behaviour).
 *
 * The selection is latched in [configure] so a running configuration never
 * switches underneath the renderer; [getFormatSupport] answers with the sink
 * that the NEXT configure will select, which is precisely what the renderer's
 * codec negotiation consults. Flipping the toggle mid-playback therefore
 * re-negotiates at the next track boundary, or immediately when the service
 * re-prepares the player (see the Bit-Perfect preference collector).
 *
 * State setters are mirrored to BOTH sinks so whichever becomes active is
 * already primed (attributes, volume, session id, playback parameters); state
 * queries and the write path always go to the sink that owns the current
 * configuration.
 */
class BitPerfectSwitchingAudioSink(
    private val dspSink: AudioSink,
    private val bitPerfectSink: AudioSink,
    private val routeActive: () -> Boolean,
) : AudioSink {

    /**
     * The sink that owns the CURRENT configuration (latched at [configure]).
     * Falls back to the live preference before the first configure.
     */
    private var configuredSink: AudioSink? = null

    private fun liveSink(): AudioSink = if (routeActive()) bitPerfectSink else dspSink

    private fun currentSink(): AudioSink = configuredSink ?: liveSink()

    // ---- Codec-negotiation surface: answers must reflect the route that the
    // ---- NEXT configure will latch — this is what makes the decode depth
    // ---- follow the toggle at runtime.

    override fun supportsFormat(format: Format): Boolean = liveSink().supportsFormat(format)

    override fun getFormatSupport(format: Format): Int = liveSink().getFormatSupport(format)

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        liveSink().getFormatOffloadSupport(format)

    // ---- Configuration + write path: always the owning sink.

    @Throws(ConfigurationException::class)
    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        val target = liveSink()
        target.configure(inputFormat, specifiedBufferSize, outputChannels)
        // Latch only after a successful configure so a ConfigurationException
        // never leaves the route pointing at a half-configured sink.
        configuredSink = target
    }

    override fun play() = currentSink().play()

    override fun pause() = currentSink().pause()

    override fun handleDiscontinuity() = currentSink().handleDiscontinuity()

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean = currentSink().handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

    @Throws(WriteException::class)
    override fun playToEndOfStream() = currentSink().playToEndOfStream()

    override fun flush() = currentSink().flush()

    override fun reset() {
        configuredSink = null
        dspSink.reset()
        bitPerfectSink.reset()
    }

    override fun release() {
        configuredSink = null
        dspSink.release()
        bitPerfectSink.release()
    }

    // ---- State queries: the owning sink.

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        currentSink().getCurrentPositionUs(sourceEnded)

    override fun isEnded(): Boolean = currentSink().isEnded()

    override fun hasPendingData(): Boolean = currentSink().hasPendingData()

    override fun getPlaybackParameters(): PlaybackParameters = currentSink().playbackParameters

    override fun getSkipSilenceEnabled(): Boolean = currentSink().skipSilenceEnabled

    override fun getAudioAttributes(): AudioAttributes = currentSink().audioAttributes

    override fun getAudioTrackBufferSizeUs(): Long = currentSink().audioTrackBufferSizeUs

    // ---- Setters: mirrored to both so a route switch never finds an
    // ---- unprimed sink. Idempotent for the shared silence/sonic instances.

    override fun setListener(listener: AudioSink.Listener) {
        dspSink.setListener(listener)
        bitPerfectSink.setListener(listener)
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        dspSink.playbackParameters = playbackParameters
        bitPerfectSink.playbackParameters = playbackParameters
    }

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        dspSink.skipSilenceEnabled = skipSilenceEnabled
        bitPerfectSink.skipSilenceEnabled = skipSilenceEnabled
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        dspSink.audioAttributes = audioAttributes
        bitPerfectSink.audioAttributes = audioAttributes
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        dspSink.setAudioSessionId(audioSessionId)
        bitPerfectSink.setAudioSessionId(audioSessionId)
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        dspSink.setAuxEffectInfo(auxEffectInfo)
        bitPerfectSink.setAuxEffectInfo(auxEffectInfo)
    }

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        dspSink.setPreferredDevice(audioDeviceInfo)
        bitPerfectSink.setPreferredDevice(audioDeviceInfo)
    }

    override fun setVolume(volume: Float) {
        dspSink.setVolume(volume)
        bitPerfectSink.setVolume(volume)
    }

    override fun setAudioOutputProvider(audioOutputProvider: AudioOutputProvider) {
        dspSink.setAudioOutputProvider(audioOutputProvider)
        bitPerfectSink.setAudioOutputProvider(audioOutputProvider)
    }

    // ---- Default-method surface (kept symmetric where harmless).

    override fun setPlayerId(playerId: androidx.media3.exoplayer.analytics.PlayerId?) {
        dspSink.setPlayerId(playerId)
        bitPerfectSink.setPlayerId(playerId)
    }

    override fun setClock(clock: androidx.media3.common.util.Clock) {
        dspSink.setClock(clock)
        bitPerfectSink.setClock(clock)
    }

    override fun setVirtualDeviceId(virtualDeviceId: Int) {
        dspSink.setVirtualDeviceId(virtualDeviceId)
        bitPerfectSink.setVirtualDeviceId(virtualDeviceId)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        dspSink.setOutputStreamOffsetUs(outputStreamOffsetUs)
        bitPerfectSink.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun setOffloadMode(offloadMode: Int) {
        dspSink.setOffloadMode(offloadMode)
        bitPerfectSink.setOffloadMode(offloadMode)
    }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {
        dspSink.setOffloadDelayPadding(delayInFrames, paddingInFrames)
        bitPerfectSink.setOffloadDelayPadding(delayInFrames, paddingInFrames)
    }

    override fun enableTunnelingV21() {
        dspSink.enableTunnelingV21()
        bitPerfectSink.enableTunnelingV21()
    }

    override fun disableTunneling() {
        dspSink.disableTunneling()
        bitPerfectSink.disableTunneling()
    }

    /** Exposed for diagnostics: which route owns the current configuration. */
    fun bitPerfectRouteEngaged(): Boolean = currentSink() === bitPerfectSink

    companion object {
        /** App-defined WriteException codes raised by the exclusive outputs. */
        val EXCLUSIVE_WRITE_ERROR_CODES =
            setOf(
                -9101, // LastWave usbdevfs engage failed
                -9102, // LastWave usbdevfs stream died
                -9001, // Tryptify libusb engage failed
                -9002, // Tryptify libusb write failed
                -896, // AAudio ERROR_INVALID_STATE from the exclusive stream
            )

        /** Heuristic check: does this PCM encoding carry float samples? */
        fun isFloatEncoding(encoding: Int): Boolean = encoding == C.ENCODING_PCM_FLOAT
    }
}
