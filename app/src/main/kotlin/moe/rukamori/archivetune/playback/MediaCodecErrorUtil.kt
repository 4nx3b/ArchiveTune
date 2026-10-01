/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import android.media.MediaCodec
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer

private val EXCLUSIVE_WRITE_ERROR_CODES = setOf(-9101, -9102, -9001, -9002, -896)

internal fun isRecoverableExclusiveAudioWriteError(error: PlaybackException): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED) return false
    return generateSequence<Throwable>(error) { it.cause }.any { throwable ->
        val code = when (throwable) {
            is AudioSink.WriteException -> throwable.errorCode
            is AudioOutput.WriteException -> throwable.errorCode
            else -> null
        }
        code != null && code in EXCLUSIVE_WRITE_ERROR_CODES
    }
}

internal fun isRecoverableMediaCodecStateError(error: PlaybackException): Boolean {
    val isDecodingErrorCode =
        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED

    val causeChain = generateSequence<Throwable>(error) { it.cause }

    val hasCodecExceptionClass = causeChain.any { throwable ->
        throwable is MediaCodec.CodecException ||
            throwable is MediaCodecDecoderException ||
            throwable is MediaCodecRenderer.DecoderInitializationException
    }

    val hasCodecStateMessage = causeChain.any { throwable ->
        val message = throwable.message.orEmpty()
        (message.contains("queueInputBuffer", ignoreCase = true) &&
            message.contains("Executing states", ignoreCase = true)) ||
            message.contains("currently at Released state", ignoreCase = true) ||
            message.contains("codec is in state", ignoreCase = true) ||

            (message.contains("Decoder failed", ignoreCase = true) &&
                message.contains("decoder", ignoreCase = true)) ||

            message.contains("0x80000000", ignoreCase = true) ||

            message.contains("alac.decoder", ignoreCase = true) ||
            message.contains("c2.mtk.alac", ignoreCase = true)
    }

    return (isDecodingErrorCode && (hasCodecExceptionClass || hasCodecStateMessage)) ||
        hasCodecExceptionClass ||
        hasCodecStateMessage
}
