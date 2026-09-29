@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback.dsp

import android.hardware.usb.UsbDevice
import android.media.AudioDeviceInfo
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tf.monochrome.android.audio.usb.BypassVolumeController
import tf.monochrome.android.audio.usb.LibusbUacDriver

class TryptifyLibusbAudioOutput(
    private val config: OutputConfig,
    private val usbDevice: UsbDevice,
    private val driver: LibusbUacDriver,
    private val volumeController: BypassVolumeController,
    private val permissionScope: CoroutineScope,
) : AudioOutput {
    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()

    private var engaged = false
    private var released = false
    private var started = false
    private var outBitsPerSample = 0
    private var usbBytesPerSample = 0
    private val channels: Int = channelCountForMask(config.channelMask)
    private val sourceIsFloat: Boolean = config.encoding == C.ENCODING_PCM_FLOAT

    private var firstWriteElapsedMs = -1L
    private var lastPlayedFrames = -1L
    private var lastAdvanceElapsedMs = 0L

    private var packScratch: ByteBuffer = ByteBuffer.allocateDirect(0)
        .order(ByteOrder.nativeOrder())

    @Synchronized
    private fun ensureEngaged(): Boolean {
        if (released) return false
        if (engaged) return true
        val rate = if (config.sampleRate > 0) config.sampleRate else 48000

        if (!driver.isOpen.value) {
            if (!driver.open(usbDevice)) {
                permissionScope.launch { runCatching { driver.requestPermission(usbDevice) } }
                Log.i(TAG, "libusb open needs permission — request queued, falling back for this track")
                return false
            }
        }

        val ladder = if (sourceIsFloat) intArrayOf(24, 16) else intArrayOf(16)
        for (bits in ladder) {
            if (driver.isStreamingFormat(rate, bits, channels) || driver.start(rate, bits, channels)) {
                engaged = true
                outBitsPerSample = bits
                usbBytesPerSample = driver.diagnostics.value
                    ?.takeIf { it.bitsPerSample == bits && it.channels == channels }
                    ?.bytesPerSample?.takeIf { it > 0 }
                    ?: (bits / 8)
                firstWriteElapsedMs = -1L
                lastPlayedFrames = -1L
                Log.i(
                    TAG,
                    "Tryptify libusb UAC stream ENGAGED: rate=$rate bits=$bits ch=$channels " +
                        "subslot=${usbBytesPerSample}B uac=${driver.diagnostics.value?.uacVersion} — bit-perfect direct to the DAC",
                )
                return true
            }
        }
        Log.w(TAG, "libusb start failed at every ladder rung: ${driver.lastStartError.value} — falling back")
        return false
    }

    private fun channelCountForMask(channelMask: Int): Int = when (channelMask) {
        android.media.AudioFormat.CHANNEL_OUT_MONO -> 1
        android.media.AudioFormat.CHANNEL_OUT_QUAD -> 4
        android.media.AudioFormat.CHANNEL_OUT_5POINT1, android.media.AudioFormat.CHANNEL_OUT_7POINT1_SURROUND -> 6
        else -> 2
    }

    @Synchronized
    override fun play() {
        val ok = ensureEngaged()
        if (ok && !started) {
            started = true
            listeners.forEach { it.onPositionAdvancing(android.os.SystemClock.elapsedRealtime()) }
        }
    }

    @Synchronized
    override fun pause() {

        started = false
    }

    @Synchronized
    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        if (!ensureEngaged()) {
            throw AudioOutput.WriteException(USB_ENGAGE_FAILED, true)
        }
        val bytesPerSample = if (sourceIsFloat) 4 else 2
        val bytesPerFrame = bytesPerSample * channels
        val frames = buffer.remaining() / bytesPerFrame
        if (frames <= 0) return true

        val now = android.os.SystemClock.elapsedRealtime()
        if (firstWriteElapsedMs < 0) firstWriteElapsedMs = now

        val gain = volumeController.getVolume()
        val toWrite = if (sourceIsFloat) {
            packFloatForUsb(buffer, frames, gain)
        } else {
            buffer.slice().order(ByteOrder.nativeOrder())
        }

        val written = driver.write(toWrite, frames)
        if (written <= 0) {
            Log.w(TAG, "libusb write failed (code=$written) — dropping the stream")
            disengage()
            throw AudioOutput.WriteException(USB_WRITE_FAILED, false)
        }
        buffer.position(buffer.position() + written * bytesPerFrame)
        checkWatchdog(now)
        return written >= frames
    }

    private fun packFloatForUsb(src: ByteBuffer, frames: Int, gain: Float): ByteBuffer {
        val samples = frames * channels
        val out = ensurePackScratch(samples * usbBytesPerSample)
        val srcPos = src.position()
        var o = 0
        for (i in 0 until samples) {
            val raw = src.getFloat(srcPos + (i shl 2)) * gain
            val clamped = if (raw > 1f) 1f else if (raw < -1f) -1f else raw
            val sample24 = (clamped * 8_388_607f).toInt()
            val sample = when {
                usbBytesPerSample >= 4 -> sample24 shl 8
                usbBytesPerSample == 2 -> sample24 shr 8
                else -> sample24
            }
            out.put(o, sample.toByte())
            if (usbBytesPerSample > 1) out.put(o + 1, (sample shr 8).toByte())
            if (usbBytesPerSample > 2) out.put(o + 2, (sample shr 16).toByte())
            if (usbBytesPerSample > 3) out.put(o + 3, (sample shr 24).toByte())
            o += usbBytesPerSample
        }
        out.position(0)
        out.limit(samples * usbBytesPerSample)
        return out
    }

    private fun ensurePackScratch(needBytes: Int): ByteBuffer {
        if (packScratch.capacity() < needBytes) {
            packScratch = ByteBuffer.allocateDirect(needBytes).order(ByteOrder.nativeOrder())
        } else {
            packScratch.clear()
        }
        return packScratch
    }

    private fun checkWatchdog(now: Long) {
        val played = driver.playedFrames()
        if (played != lastPlayedFrames) {
            lastPlayedFrames = played
            lastAdvanceElapsedMs = now
        }
        val sinceFirstWrite = now - firstWriteElapsedMs
        val sinceAdvance = now - lastAdvanceElapsedMs
        if (sinceFirstWrite > ISO_WARMUP_MS && sinceAdvance > ISO_STALL_MS) {
            Log.w(
                TAG,
                "libusb iso pump wedged — playedFrames=$played stuck for ${sinceAdvance}ms; disengaging",
            )
            disengage()
        }
    }

    @Synchronized
    private fun disengage() {
        runCatching { driver.stop() }
        engaged = false
        started = false
        firstWriteElapsedMs = -1L
    }

    @Synchronized
    override fun flush() {
        if (engaged) runCatching { driver.flushRing() }
    }

    @Synchronized
    override fun stop() {
        started = false
    }

    @Synchronized
    override fun release() {
        released = true
        disengage()
        listeners.forEach { it.onReleased() }
        listeners.clear()
    }

    override fun setVolume(volume: Float) {
        volumeController.setVolume(volume)
    }

    override fun isOffloadedPlayback(): Boolean = false

    override fun getAudioSessionId(): Int = 0

    override fun getSampleRate(): Int =
        driver.diagnostics.value?.sampleRateHz ?: config.sampleRate

    override fun getBufferSizeInFrames(): Long = driver.pendingFrames()

    override fun getPositionUs(): Long {
        val rate = driver.diagnostics.value?.sampleRateHz ?: return 0L
        if (rate <= 0) return 0L
        val frames = driver.playedFrames()
        return (frames * 1_000_000L) / rate
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
        private const val TAG = "TryptifyUsbAudio"
        private const val ISO_WARMUP_MS = 400L
        private const val ISO_STALL_MS = 400L
        private const val USB_ENGAGE_FAILED = -9001
        private const val USB_WRITE_FAILED = -9002

        @Volatile
        private var nativeAvailable: Boolean? = null

        fun available(): Boolean {
            nativeAvailable?.let { return it }
            val ok = runCatching {
                tf.monochrome.android.audio.usb.UsbNativeLoader.ensureLoaded()
            }.isSuccess
            nativeAvailable = ok
            return ok
        }
    }
}
