/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Automix analysis pipeline ported from BitChord
 * (https://github.com/kushagrasinghx/BitChord), which derives it from
 * Orchard (https://github.com/SFG5453/Orchard). Orchard's original source
 * is licensed AGPL-3.0-or-later; per AGPLv3 section 13 this file is
 * combined into ArchiveTune -- a GPL-3.0-or-later work -- and remains
 * itself governed by the AGPLv3 as part of that combination.
 */

package moe.rukamori.archivetune.playback.smart
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.floor

object VocalSpectrogram {
    val available: Boolean get() = MelSpectrogram.available
    val bins: Int by lazy { if (available) nativeBins() else 2049 }
    val sampleRate: Double by lazy { if (available) nativeSampleRate() else 44_100.0 }
    val hop: Int by lazy { if (available) nativeHop() else 1024 }
    val fftSize: Int by lazy { if (available) nativeFftSize() else 4096 }
    val frameRate: Double get() = sampleRate / hop

    fun compute(left: FloatArray, right: FloatArray, rate: Double = sampleRate): Spectrogram? {
        if (!available || left.isEmpty() || left.size != right.size) return null
        val values = nativeCompute(left, right, rate)
        if (values.isEmpty()) return null
        return Spectrogram(values, frames = values.size / (CHANNELS * bins), bins = bins)
    }

    data class Spectrogram(val values: FloatArray, val frames: Int, val bins: Int) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Spectrogram && frames == other.frames &&
                bins == other.bins && values.contentEquals(other.values))

        override fun hashCode(): Int = 31 * (31 * values.contentHashCode() + frames) + bins
    }

    const val CHANNELS = 2

    @JvmStatic private external fun nativeCompute(left: FloatArray, right: FloatArray, rate: Double): FloatArray
    @JvmStatic private external fun nativeBins(): Int
    @JvmStatic private external fun nativeSampleRate(): Double
    @JvmStatic private external fun nativeHop(): Int
    @JvmStatic private external fun nativeFftSize(): Int
}

class VocalTracker(private val context: Context) {
    @Volatile private var session: OrtSession? = null
    @Volatile private var sessionThreads = 0
    private val lock = Any()

    private var mixScratch: ByteBuffer? = null

    private fun scratchBuffer(bins: Int): ByteBuffer {
        val size = VocalSpectrogram.CHANNELS * bins * FIXED_FRAMES * Float.SIZE_BYTES
        val existing = mixScratch
        if (existing != null && existing.capacity() == size) return existing
        return ByteBuffer
            .allocateDirect(size)
            .order(ByteOrder.nativeOrder())
            .also { mixScratch = it }
    }

    private fun session(): OrtSession? {
        val threads = SmartFadeSettings.performanceMode.value.inferenceThreads
        session?.takeIf { sessionThreads == threads }?.let { return it }
        synchronized(lock) {
            session?.takeIf { sessionThreads == threads }?.let { return it }
            runCatching { session?.close() }
            session = null
            return runCatching {
                val file = File(context.filesDir, MODEL_ASSET)

                val assetLength = runCatching {
                    context.assets.open(MODEL_ASSET).use { it.available().toLong() }
                }.getOrDefault(-1L)
                val stale = !file.exists() || file.length() == 0L ||
                    (assetLength > 0 && file.length() != assetLength)
                if (stale) {
                    file.delete()
                    context.assets.open(MODEL_ASSET).use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(threads)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

                    setCPUArenaAllocator(false)
                    setMemoryPatternOptimization(false)
                }
                try {
                    OrtEnvironment.getEnvironment().createSession(file.absolutePath, options)
                        .also {
                            session = it
                            sessionThreads = threads
                        }
                } finally {

                    runCatching { options.close() }
                }
            }.onFailure { Log.w(TAG, "Vocal model unavailable; no mask will be produced", it) }
                .getOrNull()
        }
    }

    fun track(left: FloatArray, right: FloatArray, rate: Double): FloatArray? {
        if (!VocalSpectrogram.available) return null
        val resampledLeft = MelSpectrogram.resample(left, rate, VocalSpectrogram.sampleRate) ?: return null
        val resampledRight = MelSpectrogram.resample(right, rate, VocalSpectrogram.sampleRate) ?: return null

        val started = System.currentTimeMillis()
        val spectrogram = VocalSpectrogram.compute(resampledLeft, resampledRight) ?: return null
        if (spectrogram.frames > FIXED_FRAMES) {
            Log.d(TAG, "Window of ${spectrogram.frames} frames exceeds the model's $FIXED_FRAMES")
            return null
        }
        val active = session() ?: return null

        return runCatching {
            val bins = spectrogram.bins

            val backing = scratchBuffer(bins)
            backing.rewind()
            fillFixedFrames(backing.asFloatBuffer(), spectrogram.values, bins, spectrogram.frames)
            val environment = OrtEnvironment.getEnvironment()
            val shape = longArrayOf(1, VocalSpectrogram.CHANNELS.toLong(), bins.toLong(), FIXED_FRAMES.toLong())

            OnnxTensor.createTensor(environment, backing.asFloatBuffer(), shape).use { tensor ->
                active.run(mapOf(active.inputNames.first() to tensor)).use { outputs ->

                    val target = (outputs.get(0) as OnnxTensor).floatBuffer
                    val curve = reduceToBandCurve(backing.asFloatBuffer(), target, bins, spectrogram.frames)
                    Log.d(
                        TAG,
                        "vocal mask ${spectrogram.frames} frames in " +
                            "${System.currentTimeMillis() - started}ms",
                    )
                    curve
                }
            }
        }.onFailure { Log.w(TAG, "Vocal inference failed", it) }.getOrNull()
    }

    private fun fillFixedFrames(into: FloatBuffer, values: FloatArray, bins: Int, frames: Int) {
        if (frames == FIXED_FRAMES) {
            into.put(values)
            return
        }
        val pad = FloatArray(FIXED_FRAMES - frames)
        for (channel in 0 until VocalSpectrogram.CHANNELS) {
            for (bin in 0 until bins) {
                into.put(values, (channel * bins + bin) * frames, frames)
                into.put(pad)
            }
        }
    }

    private fun reduceToBandCurve(
        mix: FloatBuffer,
        target: FloatBuffer,
        bins: Int,
        usableFrames: Int,
    ): FloatArray {
        val lowBin = floor(LOW_HZ * VocalSpectrogram.fftSize / VocalSpectrogram.sampleRate)
            .toInt().coerceAtLeast(0)
        val highBin = ceil(HIGH_HZ * VocalSpectrogram.fftSize / VocalSpectrogram.sampleRate)
            .toInt().coerceAtMost(bins - 1)
        if (highBin <= lowBin || usableFrames <= 0) return FloatArray(0)

        val curve = FloatArray(usableFrames)
        for (frame in 0 until usableFrames) {
            var sum = 0.0
            var count = 0
            for (channel in 0 until VocalSpectrogram.CHANNELS) {
                for (bin in lowBin..highBin) {

                    val index = (channel * bins + bin) * FIXED_FRAMES + frame
                    val mixValue = mix.get(index)
                    if (mixValue <= 1e-6f) continue
                    val ratio = target.get(index) / mixValue
                    sum += ratio.coerceIn(0f, 1f)
                    count += 1
                }
            }
            curve[frame] = if (count > 0) (sum / count).toFloat() else 0f
        }
        return curve
    }

    fun release() {
        synchronized(lock) {
            runCatching { session?.close() }
            session = null
            sessionThreads = 0
        }
    }

    companion object {
        private const val TAG = "BitChordVocalTracker"
        private const val MODEL_ASSET = "vocals_umxhq_int8.onnx"

        const val FIXED_FRAMES = 960

        private const val LOW_HZ = 200.0
        private const val HIGH_HZ = 4000.0
    }
}
