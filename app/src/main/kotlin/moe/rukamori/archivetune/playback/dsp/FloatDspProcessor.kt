@file:OptIn(androidx.media3.common.util.UnstableApi::class)

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 *
 * The audio-processor tail of the app's chain: runs the native 32-bit float
 * DSP whenever the service has engaged it for the current stream (lossless
 * or high-quality), and — while the USB-exclusive output is active — hands
 * the sink 32-bit float PCM instead of TPDF-dithered 16-bit.
 *
 * Position in the chain (see createRenderersFactory): the float DSP sits
 * AFTER Sonic/Haptics/StereoPan/TransitionFilter, so every existing
 * processor keeps seeing exactly the 16-bit stream it sees today. The
 * encoding flip (16-bit <-> float) happens HERE, at the tail, which is the
 * only safe place: the sink's OutputConfig always mirrors whatever this
 * processor produced for the current track.
 */

package moe.rukamori.archivetune.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

class FloatDspProcessor : BaseAudioProcessor() {

    /** Set by the service when the current stream deserves the DSP. */
    @Volatile
    var engaged: Boolean = false

    /**
     * Set by the service while a USB-exclusive float output is (about to be)
     * active: the processor then emits ENCODING_PCM_FLOAT instead of
     * TPDF-dithered 16-bit. Takes effect at the next configure (next track) —
     * the sink and the processor must agree on the encoding, and per-track
     * boundaries are the only race-free place to change it.
     */
    @Volatile
    var outputFloat: Boolean = false

    private var dspHandle: Long = 0L

    fun setEngaged(value: Boolean) {
        engaged = value
        if (dspHandle != 0L) FloatDsp.nativeSetEngaged(dspHandle, value)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!FloatDsp.available) return AudioProcessor.AudioFormat.NOT_SET
        val encoding = inputAudioFormat.encoding
        if (engaged && (encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT)) {
            return AudioProcessor.AudioFormat(
                inputAudioFormat.sampleRate,
                inputAudioFormat.channelCount,
                if (outputFloat) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT,
            )
        }
        return AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        if (!engaged || dspHandle == 0L && !ensureHandle()) {
            // Not engaged (or the native lib is absent): bit-exact passthrough.
            replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
            return
        }

        val channels = inputAudioFormat.channelCount
        val inputFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (inputFloat) 4 else 2
        val frames = inputBuffer.remaining() / (bytesPerSample * channels)
        if (frames <= 0 || (!inputFloat && inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)) {
            replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
            return
        }

        // The input's live window is [position, limit); the JNI side writes
        // through base+position, so pass the byte offset alongside.
        val inOffset = inputBuffer.position()

        if (outputFloat) {
            val outBytes = frames * channels * 4
            val output = replaceOutputBuffer(outBytes)
            if (inputFloat) {
                FloatDsp.nativeProcessFloatToFloat(dspHandle, inputBuffer, inOffset, output, frames, channels)
            } else {
                FloatDsp.nativeProcessShortToFloat(dspHandle, inputBuffer, inOffset, output, frames, channels)
            }
            // Native wrote at the buffer's base address (position is still
            // 0); expose exactly the written window.
            output.position(0)
            output.limit(outBytes)
            inputBuffer.position(inputBuffer.limit())
        } else {
            if (inputFloat) {
                // Float input but the output stayed 16-bit (USB exclusive
                // went away between configure and now): passthrough is the
                // only encoding-safe option without a down-conversion call.
                replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
                return
            }
            val outBytes = frames * channels * 2
            val output = replaceOutputBuffer(outBytes)
            FloatDsp.nativeProcessShortToShort(dspHandle, inputBuffer, inOffset, output, frames, channels)
            output.position(0)
            output.limit(outBytes)
            inputBuffer.position(inputBuffer.limit())
        }
    }

    private fun ensureHandle(): Boolean {
        if (!FloatDsp.available) return false
        dspHandle = FloatDsp.nativeCreate(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        if (dspHandle == 0L) return false
        FloatDsp.nativeSetEngaged(dspHandle, engaged)
        return true
    }

    override fun onFlush() {
        if (dspHandle != 0L) FloatDsp.nativeReset(dspHandle)
    }

    override fun onReset() {
        if (dspHandle != 0L) {
            FloatDsp.nativeRelease(dspHandle)
            dspHandle = 0L
        }
    }

    @Suppress("FinalPrivate")
    protected fun finalize() {
        // Best-effort reclaim: the normal teardown is onReset, but a player
        // released without a pipeline reset would otherwise hold the tiny
        // native context until process death.
        if (dspHandle != 0L) {
            runCatching { FloatDsp.nativeRelease(dspHandle) }
            dspHandle = 0L
        }
    }
}
