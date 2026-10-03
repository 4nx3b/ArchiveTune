@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback.dsp

import android.media.AudioDeviceInfo
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioSink.ConfigurationException
import androidx.media3.exoplayer.audio.AudioSink.WriteException
import java.nio.ByteBuffer

class BitPerfectSwitchingAudioSink(
    private val dspSink: AudioSink,
    private val bitPerfectSink: AudioSink,
    private val routeActive: () -> Boolean,
) : AudioSink {
    private var configuredSink: AudioSink? = null

    private fun liveSink(): AudioSink = if (routeActive()) bitPerfectSink else dspSink

    private fun currentSink(): AudioSink = configuredSink ?: liveSink()

    override fun supportsFormat(format: Format): Boolean = liveSink().supportsFormat(format)

    override fun getFormatSupport(format: Format): Int = liveSink().getFormatSupport(format)

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        liveSink().getFormatOffloadSupport(format)

    @Throws(ConfigurationException::class)
    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        val target = liveSink()
        target.configure(inputFormat, specifiedBufferSize, outputChannels)

        configuredSink = target

        EngineRuntime.publishSinkRoute(
            floatRoute = target === bitPerfectSink,
            decodedEncoding = if (inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW &&
                inputFormat.pcmEncoding != C.ENCODING_INVALID &&
                inputFormat.pcmEncoding != Format.NO_VALUE
            ) {
                inputFormat.pcmEncoding
            } else {
                EngineRuntime.sinkDecodedEncoding
            },
        )
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

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        currentSink().getCurrentPositionUs(sourceEnded)

    override fun isEnded(): Boolean = currentSink().isEnded()

    override fun hasPendingData(): Boolean = currentSink().hasPendingData()

    override fun getPlaybackParameters(): PlaybackParameters = currentSink().playbackParameters

    override fun getSkipSilenceEnabled(): Boolean = currentSink().skipSilenceEnabled

    override fun getAudioAttributes(): AudioAttributes =

        currentSink().getAudioAttributes() ?: AudioAttributes.DEFAULT

    override fun getAudioTrackBufferSizeUs(): Long = currentSink().audioTrackBufferSizeUs

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
        dspSink.setAudioAttributes(audioAttributes)
        bitPerfectSink.setAudioAttributes(audioAttributes)
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

    fun bitPerfectRouteEngaged(): Boolean = currentSink() === bitPerfectSink

    companion object {
        val EXCLUSIVE_WRITE_ERROR_CODES =
            setOf(
                -9101,
                -9102,
                -9001,
                -9002,
                -896,
            )

        fun isFloatEncoding(encoding: Int): Boolean = encoding == C.ENCODING_PCM_FLOAT
    }
}
