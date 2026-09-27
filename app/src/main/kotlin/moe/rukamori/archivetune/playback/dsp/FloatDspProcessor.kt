@file:OptIn(androidx.media3.common.util.UnstableApi::class)

/*
 * ArchiveTune (2026)
 * © Rukamori — GPL-3.0 License | Contributors: see git history
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
        private set

    /**
     * Set by the service while a USB-exclusive float output is (about to be)
     * active: the processor then emits ENCODING_PCM_FLOAT instead of
     * TPDF-dithered 16-bit. Takes effect at the next configure (next track) —
     * the sink and the processor must agree on the encoding, and per-track
     * boundaries are the only race-free place to change it.
     */
    @Volatile
    var outputFloat: Boolean = false

    /**
     * The encoding actually being emitted right now: [outputFloat] as it was
     * when THIS track was configured. A mid-track flip of the raw flag (USB
     * plugged/unplugged, automix toggled) must never change what the sink is
     * handed mid-stream — the sink's OutputConfig is frozen for the track —
     * so the queueInput hot path reads this snapshot, not the live flag.
     */
    @Volatile
    private var activeOutputFloat: Boolean = false

    // Volatile: setEngaged() is called from the service thread while the
    // playback thread creates/releases the handle in queueInput/onReset. A
    // plain Long is also tearable on 32-bit ABIs (armeabi-v7a ships in the
    // release matrix) — a torn 64-bit handle dereferenced in JNI is an
    // instant SIGSEGV with no Java-side trace.
    @Volatile
    private var dspHandle: Long = 0L

    /**
     * The input format the live native handle was created for. The handle's
     * per-channel state and filter coefficients are baked in at construction;
     * a track transition that changes the format (44.1<->48 kHz, stereo<->
     * mono — YouTube serves both) reconfigures the chain but does NOT reset
     * the processor, so without this check the stale handle would be driven
     * with the new layout: frames * staleChannels floats pushed through a
     * buffer sized frames * newChannels. That is a heap overrun of the
     * direct output buffer — a bare SIGSEGV with no Java trace, and exactly
     * the automix-crash shape (automix = a format change every song). The
     * handle is now dropped at every configure whose format differs, and
     * queueInput re-verifies before every native call (belt and braces: the
     * native side independently refuses mismatched channel counts).
     */
    @Volatile
    private var handleFormat: AudioProcessor.AudioFormat? = null

    // Guards the handle LIFECYCLE only (create/release/setEngaged):
    // setEngaged arrives from the service thread while create/release run
    // on the playback thread — an unguarded pair can call into a handle that
    // onReset just deleted (use-after-free, bare SIGSEGV). The queueInput hot
    // path stays lock-free: it runs on the same playback thread as
    // onReset/ensureHandle, which serialize it by construction.
    private val handleLock = Any()

    fun setEngaged(value: Boolean) {
        engaged = value
        synchronized(handleLock) {
            if (dspHandle != 0L) FloatDsp.nativeSetEngaged(dspHandle, value)
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!FloatDsp.available) return AudioProcessor.AudioFormat.NOT_SET
        val encoding = inputAudioFormat.encoding
        if (engaged && (encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT)) {
            // The encoding decision freezes for this track here; a live flag
            // flip only lands at the NEXT configure.
            activeOutputFloat = outputFloat
            dropHandleIfFormatChanged(inputAudioFormat)
            return AudioProcessor.AudioFormat(
                inputAudioFormat.sampleRate,
                inputAudioFormat.channelCount,
                if (activeOutputFloat) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT,
            )
        }
        activeOutputFloat = false
        return AudioProcessor.AudioFormat.NOT_SET
    }

    /**
     * Releases the native handle when the incoming format no longer matches
     * the one it was created for. onConfigure and queueInput both run on the
     * playback thread, so this needs no extra synchronization against them.
     */
    private fun dropHandleIfFormatChanged(format: AudioProcessor.AudioFormat) {
        val current = handleFormat ?: return
        if (sameFormat(current, format)) return
        synchronized(handleLock) {
            if (dspHandle != 0L) {
                FloatDsp.nativeRelease(dspHandle)
                dspHandle = 0L
            }
        }
        handleFormat = null
    }

    private fun sameFormat(
        a: AudioProcessor.AudioFormat?,
        b: AudioProcessor.AudioFormat,
    ): Boolean = a != null && a.sampleRate == b.sampleRate && a.channelCount == b.channelCount && a.encoding == b.encoding

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        if (!engaged || dspHandle == 0L && !ensureHandle()) {
            // Not engaged (or the native lib is absent): bit-exact passthrough.
            replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
            return
        }
        // Consistency re-check before any native call: the handle must have
        // been created for exactly the format now streaming. A mismatch means
        // a reconfigure slipped past onConfigure's drop (or the processor was
        // flushed into a new stream) — passthrough this window and let the
        // next ensureHandle() rebuild with the correct layout.
        if (!sameFormat(handleFormat, inputAudioFormat)) {
            releaseHandleForRebuild()
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

        if (activeOutputFloat) {
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
        synchronized(handleLock) {
            dspHandle = FloatDsp.nativeCreate(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
            if (dspHandle == 0L) return false
            handleFormat = inputAudioFormat
            FloatDsp.nativeSetEngaged(dspHandle, engaged)
        }
        return true
    }

    private fun releaseHandleForRebuild() {
        synchronized(handleLock) {
            if (dspHandle != 0L) {
                FloatDsp.nativeRelease(dspHandle)
                dspHandle = 0L
            }
        }
        handleFormat = null
    }

    override fun onFlush() {
        if (dspHandle != 0L) FloatDsp.nativeReset(dspHandle)
    }

    override fun onReset() {
        synchronized(handleLock) {
            if (dspHandle != 0L) {
                FloatDsp.nativeRelease(dspHandle)
                dspHandle = 0L
            }
        }
        handleFormat = null
        activeOutputFloat = false
    }

    @Suppress("FinalPrivate")
    protected fun finalize() {
        // Best-effort reclaim: the normal teardown is onReset, but a player
        // released without a pipeline reset would otherwise hold the tiny
        // native context until process death.
        synchronized(handleLock) {
            if (dspHandle != 0L) {
                runCatching { FloatDsp.nativeRelease(dspHandle) }
                dspHandle = 0L
            }
        }
    }
}
