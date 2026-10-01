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
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class BeatTracker(private val context: Context) {
    data class Grid(
        val beats: List<Double>,
        val downbeats: List<Double>,
        val bpm: Double,
        val beatInterval: Double,
        val firstBeat: Double,
        val beatConfidence: Double,
    )

    @Volatile private var session: OrtSession? = null
    @Volatile private var sessionThreads = 0
    private val lock = Any()

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
            }.onFailure { Log.w(TAG, "Beat model unavailable; falling back to no grid", it) }
                .getOrNull()
        }
    }

    fun track(pcm: FloatArray, offsetSeconds: Double = 0.0): Grid? {
        val melStarted = System.currentTimeMillis()
        val spectrogram = MelSpectrogram.compute(pcm) ?: return null
        val melMs = System.currentTimeMillis() - melStarted
        val active = session() ?: return null

        val beatLogits = FloatArray(spectrogram.frames)
        val downbeatLogits = FloatArray(spectrogram.frames)
        val inferStarted = System.currentTimeMillis()
        if (!infer(active, spectrogram, beatLogits, downbeatLogits)) return null
        Log.d(
            TAG,
            "mel ${melMs}ms (${spectrogram.frames} frames) " +
                "infer ${System.currentTimeMillis() - inferStarted}ms",
        )

        val fps = MelSpectrogram.frameRate
        val beatFrames = pickPeaks(beatLogits)
        val beats = beatFrames.map { it / fps + offsetSeconds }
        if (beats.size < MIN_BEATS) return null

        val bpm = tempoFromBeats(beats)
        if (bpm <= 0) return null

        val downbeats = pickPeaks(downbeatLogits)
            .map { it / fps + offsetSeconds }
            .map { time -> beats.minByOrNull { abs(it - time) } ?: beats.first() }
            .distinct()
            .sorted()

        return Grid(
            beats = beats,
            downbeats = downbeats,
            bpm = bpm,
            beatInterval = 60 / bpm,
            firstBeat = beats.first(),
            beatConfidence = gridConfidence(
                beats,
                beatFrames.map { frame -> beatLogits.getOrElse(frame.roundToInt()) { 0f }.toDouble() },
            ),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun infer(
        session: OrtSession,
        spectrogram: MelSpectrogram.Spectrogram,
        beatLogits: FloatArray,
        downbeatLogits: FloatArray,
    ): Boolean = runCatching {
        val environment = OrtEnvironment.getEnvironment()
        val mels = spectrogram.mels
        val name = session.inputNames.first()
        val stride = CHUNK_FRAMES - 2 * BORDER_FRAMES

        var start = 0
        while (start < spectrogram.frames) {
            val length = min(CHUNK_FRAMES, spectrogram.frames - start)

            if (length <= 2 * BORDER_FRAMES && start > 0) break

            val chunk = spectrogram.values.copyOfRange(start * mels, (start + length) * mels)
            val shape = longArrayOf(1, length.toLong(), mels.toLong())

            OnnxTensor.createTensor(environment, FloatBuffer.wrap(chunk), shape).use { tensor ->
                session.run(mapOf(name to tensor)).use { outputs ->
                    val beat = (outputs.get(0).value as Array<FloatArray>)[0]
                    val downbeat = (outputs.get(1).value as Array<FloatArray>)[0]

                    val keepFrom = if (start == 0) 0 else BORDER_FRAMES
                    val keepTo = if (start + length >= spectrogram.frames) length else length - BORDER_FRAMES
                    for (index in keepFrom until keepTo) {
                        val target = start + index
                        if (target >= beatLogits.size) break
                        beatLogits[target] = beat[index]
                        downbeatLogits[target] = downbeat[index]
                    }
                }
            }

            if (start + length >= spectrogram.frames) break
            start += stride
        }
        true
    }.onFailure { Log.w(TAG, "Beat inference failed", it) }.getOrDefault(false)

    fun release() {
        synchronized(lock) {
            runCatching { session?.close() }
            session = null
            sessionThreads = 0
        }
    }

    companion object {
        private const val TAG = "BitChordBeatTracker"
        private const val MODEL_ASSET = "beat_this_int8.onnx"

        const val CHUNK_FRAMES = 1500
        const val BORDER_FRAMES = 6

        const val WINDOW_SECONDS = (CHUNK_FRAMES - 2 * BORDER_FRAMES) / 50.0

        private const val PEAK_WINDOW = 7
        private const val MIN_BEATS = 8

        private const val MIN_TEMPO = 40.0
        private const val MAX_TEMPO = 220.0

        private fun median(values: List<Double>): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            return sorted[sorted.size / 2]
        }

        fun pickPeaks(logits: FloatArray): List<Double> {
            val half = PEAK_WINDOW / 2
            val peaks = ArrayList<Int>()
            for (index in logits.indices) {
                if (logits[index] <= 0f) continue
                var isMaximum = true
                for (offset in -half..half) {
                    val neighbour = index + offset
                    if (neighbour < 0 || neighbour >= logits.size) continue
                    if (logits[neighbour] > logits[index]) {
                        isMaximum = false
                        break
                    }
                }
                if (isMaximum) peaks += index
            }

            val deduped = ArrayList<Int>()
            var index = 0
            while (index < peaks.size) {
                var mean = peaks[index].toDouble()
                var count = 1
                while (index + 1 < peaks.size && peaks[index + 1] - mean <= 1) {
                    index += 1
                    count += 1
                    mean += (peaks[index] - mean) / count
                }
                deduped += mean.roundToInt()
                index += 1
            }

            return deduped.map { frame ->
                if (frame <= 0 || frame + 1 >= logits.size) return@map frame.toDouble()
                val left = logits[frame - 1].toDouble()
                val centre = logits[frame].toDouble()
                val right = logits[frame + 1].toDouble()
                val denominator = left - 2 * centre + right
                if (abs(denominator) <= 1e-9) return@map frame.toDouble()
                frame + (0.5 * (left - right) / denominator).coerceIn(-0.5, 0.5)
            }
        }

        fun tempoFromBeats(beats: List<Double>): Double {
            if (beats.size < MIN_BEATS) return 0.0
            val gaps = beats.zipWithNext { left, right -> right - left }
            val rough = median(gaps)
            if (rough <= 0) return 0.0

            val kept = gaps.filter { abs(it - rough) <= rough * 0.2 }
            val interval = median(if (kept.size >= 4) kept else gaps)
            if (interval <= 0) return 0.0
            val bpm = 60 / interval
            return if (bpm in MIN_TEMPO..MAX_TEMPO) bpm else 0.0
        }

        fun gridConfidence(beats: List<Double>, peakLogits: List<Double>): Double {
            if (beats.size < MIN_BEATS) return 0.0
            val gaps = beats.zipWithNext { left, right -> right - left }
            val interval = median(gaps)
            if (interval <= 0) return 0.0

            val regular = gaps.count { abs(it - interval) <= interval * 0.1 }.toDouble() / gaps.size

            val strength = 1 / (1 + exp(-(median(peakLogits) - 0.5)))
            return max(0.0, min(0.95, 0.35 + 0.4 * regular + 0.25 * strength))
        }
    }
}
